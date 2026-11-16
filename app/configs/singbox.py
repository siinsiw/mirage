"""Configs as sing-box configuration: plain functions of their arguments, so
the tests can read what would run without running it.

Two shapes come out of here:

  build()        the connection. One mixed inbound on the loopback (what the
                 system proxy points at), and with the adapter a tun inbound
                 as well. Every config of the group is an outbound; a
                 selector called "proxy" picks one, switched live through
                 the Clash API without a restart. With auto on, a urltest
                 picks the fastest and moves on when it stops answering.

  build_probe()  the latency test. One loopback inbound per config, each
                 routed to its own outbound, so each can be asked through
                 separately and at once.

What sing-box cannot carry (engine_for() says 'xray') is an outbound to a
loopback SOCKS port that Xray serves - see xray.py. Everything else about it
is the same: it is in the selector, it is tested the same way.
"""

from tun import smart_dns

from . import links
from . import prefs as _prefs

TEST_URL = 'https://www.cloudflare.com/cdn-cgi/trace'
DOH = '1.1.1.1'
PROXY = 'proxy'
AUTO = 'auto'
DIRECT = 'direct'


def tag_of(cfg):
    return 'c-' + cfg['id']


# -- one outbound --------------------------------------------------------------

def _server_name(cfg):
    if cfg.get('sni'):
        return cfg['sni']
    if cfg.get('host_header'):
        return cfg['host_header']
    return '' if links.is_address(cfg['host']) else cfg['host']


def _tls(cfg, fragment, opts=None):
    if cfg.get('security') not in ('tls', 'reality'):
        return None
    t = {'enabled': True}
    name = _server_name(cfg)
    if name:
        t['server_name'] = name
    alpn = list(cfg.get('alpn') or [])
    if cfg.get('net') in ('ws', 'httpupgrade'):
        # A WebSocket upgrade is HTTP/1.1; offering h2 lets a CDN pick it
        # and the upgrade then never happens.
        alpn = [a for a in alpn if a != 'h2']
    if cfg.get('net') == 'grpc':
        alpn = []          # sing-box sets h2 itself for gRPC
    if alpn:
        t['alpn'] = alpn
    if cfg.get('fp'):
        t['utls'] = {'enabled': True, 'fingerprint': cfg['fp']}
    if cfg['security'] == 'reality':
        t['reality'] = {'enabled': True, 'public_key': cfg['pbk'],
                        'short_id': cfg.get('sid', '')}
    elif cfg.get('allow_insecure'):
        # Only ever from the person's own switch for this one server - never
        # from the link asking for it (links.py keeps the two apart).
        t['insecure'] = True
    if fragment:
        style = (opts or {}).get('fragment_style', 'both')
        if style in ('tls', 'both'):
            t['fragment'] = True
        if style in ('record', 'both'):
            t['record_fragment'] = True
    return t


def _quic_tls(cfg):
    t = {'enabled': True}
    name = _server_name(cfg)
    if name:
        t['server_name'] = name
    if cfg.get('alpn'):
        t['alpn'] = list(cfg['alpn'])
    if cfg.get('allow_insecure'):
        t['insecure'] = True
    return t


def _transport(cfg):
    net = cfg.get('net')
    host = cfg.get('host_header') or ''
    if net == 'ws':
        t = {'type': 'ws', 'path': cfg.get('path') or '/'}
        if host:
            t['headers'] = {'Host': host}
        if cfg.get('ed'):
            t['max_early_data'] = int(cfg['ed'])
            t['early_data_header_name'] = 'Sec-WebSocket-Protocol'
        return t
    if net == 'grpc':
        return {'type': 'grpc', 'service_name': cfg.get('service') or ''}
    if net == 'httpupgrade':
        t = {'type': 'httpupgrade', 'path': cfg.get('path') or '/'}
        if host:
            t['host'] = host
        return t
    if net == 'http':
        t = {'type': 'http', 'path': cfg.get('path') or '/'}
        if host:
            t['host'] = [host]
        return t
    return None


MUX_PROTOS = ('vless', 'vmess', 'trojan', 'ss')


def _mux(cfg, opts):
    """sing-box's multiplex, where the person turned it on and this config
    can take it: not with VLESS's flow (Vision), which mux breaks."""
    o = opts or {}
    if not o.get('mux') or cfg.get('proto') not in MUX_PROTOS or cfg.get('flow'):
        return None
    out = {'enabled': True, 'protocol': o.get('mux_protocol') or 'h2mux',
           'max_streams': int(o.get('mux_streams') or 8)}
    if o.get('mux_padding'):
        out['padding'] = True
    return out


def outbound(cfg, tag, xray=None, fragment=False, opts=None):
    """One config as a sing-box outbound.

    xray is (port, user, password) of the Xray inbound that carries this
    config, for one engine_for() sends there; it then becomes a SOCKS hop on
    the loopback, with a password so nothing else on the machine can use it.
    """
    if _prefs.engine_for(cfg, opts) == 'xray':
        if not xray:
            raise ValueError('this config needs Xray, and none was given')
        port, user, password = xray
        return {'type': 'socks', 'tag': tag, 'server': '127.0.0.1',
                'server_port': int(port), 'version': '5',
                'username': user, 'password': password}
    proto = cfg['proto']
    base = {'tag': tag, 'server': cfg['host'], 'server_port': int(cfg['port'])}
    if proto == 'ss':
        ob = dict(base, type='shadowsocks', method=cfg['method'],
                  password=cfg['password'])
        mux = _mux(cfg, opts)
        if mux:
            ob['multiplex'] = mux
        return ob
    if proto == 'hysteria2':
        ob = dict(base, type='hysteria2', password=cfg['password'],
                  tls=_quic_tls(cfg))
        if cfg.get('obfs'):
            ob['obfs'] = {'type': 'salamander',
                          'password': cfg['obfs_password']}
        return ob
    if proto == 'tuic':
        return dict(base, type='tuic', uuid=cfg['uuid'],
                    password=cfg['password'],
                    congestion_control=cfg.get('congestion') or 'bbr',
                    udp_relay_mode=cfg.get('udp_relay') or 'native',
                    tls=_quic_tls(cfg))
    if proto == 'vless':
        ob = dict(base, type='vless', uuid=cfg['uuid'], packet_encoding='xudp')
        if cfg.get('flow'):
            ob['flow'] = cfg['flow']
    elif proto == 'vmess':
        ob = dict(base, type='vmess', uuid=cfg['uuid'],
                  security=cfg.get('cipher') or 'auto',
                  alter_id=int(cfg.get('alter_id') or 0),
                  packet_encoding='xudp')
    elif proto == 'trojan':
        ob = dict(base, type='trojan', password=cfg['password'])
    else:
        raise ValueError(f'unknown protocol {proto!r}')
    tls = _tls(cfg, fragment, opts)
    if tls:
        ob['tls'] = tls
    tr = _transport(cfg)
    if tr:
        ob['transport'] = tr
    mux = _mux(cfg, opts)
    if mux:
        ob['multiplex'] = mux
    return ob


# -- split tunnelling ----------------------------------------------------------

def _split(r, line_resolver):
    """split.rules() as route and DNS rules. Unlike the provider exits, a
    config carries UDP, so nothing here refuses it."""
    route, dns = [], []
    if not r:
        return route, dns
    apps = list(r.get('apps') or ())
    dest = {}
    if r.get('names'):
        dest['domain_suffix'] = list(r['names'])
    if r.get('nets'):
        dest['ip_cidr'] = list(r['nets'])
    to = DIRECT if r['mode'] == 'bypass' else PROXY
    if apps:
        route.append({'process_name': apps, 'action': 'route', 'outbound': to})
    if dest:
        route.append(dict(dest, action='route', outbound=to))
    if r['mode'] == 'bypass' and r.get('names') and line_resolver:
        dns.append({'domain_suffix': list(r['names']), 'action': 'route',
                    'server': 'dns-line'})
    dns.extend(smart_dns(r, line_resolver))
    return route, dns


def _clash(port, secret):
    return {'clash_api': {'external_controller': f'127.0.0.1:{int(port)}',
                          'secret': secret}}


def _outbounds(configs, xray, fragment, opts=None):
    xray = xray or {}
    return [outbound(c, tag_of(c), xray.get(c['id']), fragment, opts)
            for c in configs]


def _dns_servers(opts, split_dns, line_resolver):
    """The resolvers: the remote one through the config, the direct one
    straight from this line, and the line's own only for what the split
    sends direct. A DoH address given by name is itself looked up by the
    direct one, or - when that is a name too - by the system's."""
    from urllib.parse import urlsplit
    o = opts or {}
    remote = o.get('remote_dns') or 'https://1.1.1.1/dns-query'
    direct = o.get('direct_dns') or 'https://1.1.1.1/dns-query'
    need_local = not links.is_address(urlsplit(direct).hostname or '')
    remote_named = not links.is_address(urlsplit(remote).hostname or '')
    servers = [
        _prefs.dns_server(remote, 'dns-remote', detour=PROXY,
                          **({'domain_resolver': 'dns-direct'} if remote_named else {})),
        _prefs.dns_server(direct, 'dns-direct',
                          **({'domain_resolver': 'dns-local'} if need_local else {})),
    ]
    if need_local:
        servers.append({'type': 'local', 'tag': 'dns-local'})
    if split_dns:
        servers.append({'type': 'udp', 'tag': 'dns-line', 'server': line_resolver})
    return servers


# -- the connection ------------------------------------------------------------

def build(configs, *, port, clash_port, clash_secret, selected=None,
          auto=False, mode='proxy', split_rules=None, line_resolver=None,
          xray=None, fragment=False, own=(), carriers=(), log_path=None,
          auto_limit=50, opts=None):
    """The whole configuration for one connection, as a dict.

    configs   the group, in the order the person sees it (fastest first
              when they have been tested). auto tests the first auto_limit.
    selected  the id picked, or None for the first / for auto.
    mode      'proxy' (a loopback inbound the system proxy points at) or
              'tun' (the adapter as well, as tun.py makes it).
    own, carriers   with the adapter: programs left out of it - this app,
              and Xray, whose connection to the server must not fall back
              into the adapter it is carrying.
    """
    if not configs:
        raise ValueError('no configs to connect with')
    o = opts or {}
    obs = _outbounds(configs, xray, fragment, opts)
    tags = [ob['tag'] for ob in obs]
    chosen = None
    if selected:
        chosen = next((t for t in tags if t == 'c-' + selected), None)
    members = list(tags)
    if auto:
        members = [AUTO] + members
        obs.append({'type': 'urltest', 'tag': AUTO,
                    'outbounds': tags[:max(1, int(auto_limit))],
                    'url': o.get('test_url') or TEST_URL,
                    'interval': '%dm' % min(30, int(o.get('auto_interval') or 10)),
                    'tolerance': max(1, int(o.get('auto_tolerance', 50))),
                    'idle_timeout': '30m',
                    'interrupt_exist_connections': True})
    obs.append({'type': 'selector', 'tag': PROXY, 'outbounds': members,
                'default': AUTO if auto else (chosen or tags[0]),
                'interrupt_exist_connections': True})
    obs.append({'type': 'direct', 'tag': DIRECT})

    split_route, split_dns = _split(split_rules, line_resolver)
    only = bool(split_rules) and split_rules.get('mode') == 'only'
    tun = mode == 'tun'

    rules = []
    if split_rules and split_rules.get('names'):
        # Only where a name can be read: a TLS hello, an HTTP Host, QUIC.
        # Elsewhere - SSH, mail, a database, a server that speaks first -
        # sniffing would only hold every connection for its timeout.
        rules.append({'port': [80, 443], 'action': 'sniff'})
    if tun:
        rules.append({'port': 53, 'action': 'hijack-dns'})
        straight = [_path_rx(p) for p in list(own or ()) + list(carriers or ())
                    if p]
        if straight:
            rules.append({'process_path_regex': straight, 'action': 'route',
                          'outbound': DIRECT})
    # Through the proxy port a connection arrives as a name, and only the
    # lists judge it: resolving every name first so the line's answer could
    # decide would hand the config addresses instead of names for all of it,
    # and make every connection wait on the DoH through the config. Over the
    # adapter the program looks the name up itself, through the rules above.
    rules.append({'ip_is_private': True, 'action': 'route',
                  'outbound': DIRECT})
    rules.extend(split_route)

    dns_rules = []
    carried = [_path_rx(p) for p in carriers or () if p]
    if tun and carried:
        dns_rules.append({'process_path_regex': carried, 'action': 'route',
                          'server': 'dns-direct'})
    dns_rules.extend(split_dns)
    servers = _dns_servers(opts, split_dns, line_resolver)

    inbounds = [{'type': 'mixed', 'tag': 'mixed-in', 'listen': '127.0.0.1',
                 'listen_port': int(port)}]
    if tun:
        inbounds.append(_tun_inbound(opts))

    log = {'level': 'warn', 'timestamp': True}
    if log_path:
        log['output'] = log_path
    route = {'rules': rules, 'final': DIRECT if only else PROXY,
             'default_domain_resolver': 'dns-direct'}
    if tun:
        route['auto_detect_interface'] = True
    return {'log': log,
            'dns': {'servers': servers, 'rules': dns_rules,
                    'final': 'dns-remote', 'strategy': _prefs.dns_strategy(opts, tun)},
            'inbounds': inbounds, 'outbounds': obs, 'route': route,
            'experimental': _clash(clash_port, clash_secret)}


def _tun_inbound(opts=None):
    import tun
    o = opts or {}
    out = {'type': 'tun', 'tag': 'tun-in', 'interface_name': tun.ADAPTER,
           'address': [tun.ADDRESS], 'mtu': int(o.get('tun_mtu') or 9000),
           'auto_route': True, 'strict_route': bool(o.get('strict_route', True)),
           'route_exclude_address': list(tun.LOCAL_NETS)}
    if o.get('tun_stack') in ('system', 'gvisor', 'mixed'):
        out['stack'] = o['tun_stack']
    return out


def _path_rx(path):
    import tun
    return tun._path_rx(path)


# -- the latency test ----------------------------------------------------------

def build_probe(configs, ports, *, clash_port, clash_secret, xray=None,
                fragment=False, log_path=None, opts=None):
    """One loopback inbound per config (ports: id -> port), each routed to
    that config alone. Nothing else is reachable through it."""
    obs = _outbounds(configs, xray, fragment, opts)
    inbounds, rules = [], []
    for cfg, ob in zip(configs, obs):
        tag = 'in-' + cfg['id']
        inbounds.append({'type': 'mixed', 'tag': tag, 'listen': '127.0.0.1',
                         'listen_port': int(ports[cfg['id']])})
        rules.append({'inbound': [tag], 'action': 'route',
                      'outbound': ob['tag']})
    # Whatever did not come in by one of those - nothing should - is refused.
    rules.append({'action': 'reject'})
    log = {'level': 'warn', 'timestamp': True}
    if log_path:
        log['output'] = log_path
    return {'log': log,
            'dns': {'servers': [x for x in _dns_servers(opts, [], None)
                                if x['tag'] != 'dns-remote'],
                    'final': 'dns-direct', 'strategy': _prefs.dns_strategy(opts)},
            'inbounds': inbounds, 'outbounds': obs,
            'route': {'rules': rules,
                      'default_domain_resolver': 'dns-direct'},
            'experimental': _clash(clash_port, clash_secret)}

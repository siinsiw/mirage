"""The configs sing-box cannot carry, as Xray configuration.

xhttp above all - the transport sellers have moved to, which sing-box does
not have - and a few older shapes: a fake HTTP header on TCP, gRPC's multi
mode, VLESS's own encryption. Xray runs beside sing-box as one more carrier,
the way gost does for the user's own server: one SOCKS inbound per config on
the loopback, each with a password made up for this run, and sing-box dials
it as an ordinary outbound. So the selector, the kill switch, the split
rules and the meter are sing-box's either way.

Xray resolves the server's name itself, over DNS-over-HTTPS straight to
1.1.1.1 ("+local": not through any proxy), because the line's own resolver
is the one that forges answers. Its connections are left out of the adapter
by program, as gost's are.
"""

from . import links


def _local_doh(url):
    """A DoH address as Xray's straight-out form, https+local://."""
    url = url or 'https://1.1.1.1/dns-query'
    if not url.startswith('https://'):
        url = 'https://1.1.1.1/dns-query'
    rest = url[len('https://'):]
    if '/' not in rest:
        rest += '/dns-query'
    return 'https+local://' + rest


def _server_name(cfg):
    if cfg.get('sni'):
        return cfg['sni']
    if cfg.get('host_header'):
        return cfg['host_header']
    return '' if links.is_address(cfg['host']) else cfg['host']


def _stream(cfg, fragment):
    net = cfg.get('net') or 'tcp'
    names = {'tcp': 'raw', 'ws': 'ws', 'grpc': 'grpc',
             'httpupgrade': 'httpupgrade', 'xhttp': 'xhttp', 'http': 'xhttp'}
    s = {'network': names.get(net, 'raw'),
         'security': cfg.get('security') or 'none'}
    host = cfg.get('host_header') or ''
    if net == 'tcp' and cfg.get('header') == 'http':
        req = {'path': [cfg.get('path') or '/']}
        if host:
            req['headers'] = {'Host': [host]}
        s['rawSettings'] = {'header': {'type': 'http', 'request': req}}
    elif net == 'ws':
        path = cfg.get('path') or '/'
        if cfg.get('ed'):
            path += f'?ed={int(cfg["ed"])}'
        s['wsSettings'] = {'path': path}
        if host:
            s['wsSettings']['host'] = host
    elif net == 'grpc':
        s['grpcSettings'] = {'serviceName': cfg.get('service') or '',
                             'multiMode': cfg.get('mode') == 'multi'}
    elif net == 'httpupgrade':
        s['httpupgradeSettings'] = {'path': cfg.get('path') or '/'}
        if host:
            s['httpupgradeSettings']['host'] = host
    elif net in ('xhttp', 'http'):
        x = {'path': cfg.get('path') or '/', 'mode': cfg.get('mode') or 'auto'}
        if host:
            x['host'] = host
        if isinstance(cfg.get('extra'), dict):
            x['extra'] = cfg['extra']
        s['xhttpSettings'] = x
    name = _server_name(cfg)
    if s['security'] == 'tls':
        t = {}
        if name:
            t['serverName'] = name
        if cfg.get('alpn'):
            t['alpn'] = list(cfg['alpn'])
        if cfg.get('fp'):
            t['fingerprint'] = cfg['fp']
        if cfg.get('allow_insecure'):
            t['allowInsecure'] = True
        s['tlsSettings'] = t
    elif s['security'] == 'reality':
        s['realitySettings'] = {'serverName': name,
                                'fingerprint': cfg.get('fp') or 'chrome',
                                'publicKey': cfg['pbk'],
                                'shortId': cfg.get('sid', ''),
                                'spiderX': cfg.get('spx') or '/'}
    sockopt = {'domainStrategy': 'UseIPv4'}
    if fragment and s['security'] in ('tls', 'reality'):
        sockopt['dialerProxy'] = 'fragment'
    s['sockopt'] = sockopt
    return s


def outbound(cfg, tag, fragment=False):
    proto = cfg['proto']
    server = {'address': cfg['host'], 'port': int(cfg['port'])}
    if proto == 'vless':
        user = {'id': cfg['uuid'], 'encryption': cfg.get('encryption') or 'none'}
        if cfg.get('flow'):
            user['flow'] = cfg['flow']
        settings = {'vnext': [dict(server, users=[user])]}
    elif proto == 'vmess':
        settings = {'vnext': [dict(server, users=[{
            'id': cfg['uuid'], 'alterId': int(cfg.get('alter_id') or 0),
            'security': cfg.get('cipher') or 'auto'}])]}
    elif proto == 'trojan':
        settings = {'servers': [dict(server, password=cfg['password'])]}
    else:
        raise ValueError(f'{proto} does not go through Xray')
    return {'tag': tag, 'protocol': proto, 'settings': settings,
            'streamSettings': _stream(cfg, fragment)}


def build(entries, fragment=False, log_path=None, opts=None):
    """entries: (cfg, port, user, password) for each config Xray carries.
    opts: Configs' settings - the fragment's packets, sizes and gaps, and the
    direct resolver."""
    o = opts or {}
    inbounds, outbounds, rules = [], [], []
    for n, (cfg, port, user, password) in enumerate(entries):
        inbounds.append({'tag': f'in-{n}', 'listen': '127.0.0.1',
                         'port': int(port), 'protocol': 'socks',
                         'settings': {'auth': 'password', 'udp': True,
                                      'accounts': [{'user': user,
                                                    'pass': password}]}})
        outbounds.append(outbound(cfg, f'out-{n}', fragment))
        rules.append({'type': 'field', 'inboundTag': [f'in-{n}'],
                      'outboundTag': f'out-{n}'})
    # First, so that anything no rule claims - nothing should - goes nowhere
    # rather than out of whichever server happened to be listed first.
    outbounds.insert(0, {'tag': 'block', 'protocol': 'blackhole'})
    if fragment:
        outbounds.append({'tag': 'fragment', 'protocol': 'freedom',
                          'settings': {'fragment': {
                              'packets': o.get('fragment_packets') or 'tlshello',
                              'length': o.get('fragment_length') or '100-200',
                              'interval': o.get('fragment_interval') or '10-20'}}})
    log = {'loglevel': 'warning', 'access': 'none'}
    if log_path:
        log['error'] = log_path
    return {'log': log,
            'dns': {'servers': [_local_doh(o.get('direct_dns'))],
                    'queryStrategy': 'UseIPv4'},
            'inbounds': inbounds, 'outbounds': outbounds,
            'routing': {'domainStrategy': 'AsIs', 'rules': rules}}

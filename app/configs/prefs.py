"""Configs' own settings: what each one is, what it may be set to, and what
it is when nobody has touched it.

One table, so the page, the store and the engines agree on every key. The
page draws its screens from the names it knows (configs-prefs.js); anything
it sends comes back through check(), which refuses a key that is not here
and a value outside what the key allows - so a value that reaches the
config builders is one this file has allowed.

kind:
    'bool'              True / False
    ('choice', (...))   one of the listed strings
    ('int', lo, hi)     a whole number in [lo, hi]
    ('range', lo, hi)   'a-b' with lo <= a <= b <= hi, e.g. fragment lengths
    ('text', n)         a line of at most n characters, no control characters
    ('url', n)          an https:// address (or empty where allowed)
    ('apps', n)         up to n program names, e.g. chrome.exe

reconnect: whether a live connection has to be made again for it to apply.
"""

import re

from . import links

SCHEMA = {
    # -- connection ---------------------------------------------------------
    # system: a local proxy, and Windows pointed at it; tun: the whole PC
    # through an adapter; local: only the local port, Windows left alone.
    'mode': (('choice', ('system', 'tun', 'local')), 'system', True),
    'kill_switch': ('bool', True, False),
    # On opening the app: nothing, the last one used, or the fastest of its
    # group (Fastest automatically).
    'auto_connect': (('choice', ('off', 'last', 'fastest')), 'off', False),
    # The line dropped with the kill switch off: connect the same one again
    # rather than leave the machine on the bare line.
    'reconnect_on_drop': ('bool', False, False),

    # -- routing (the split, kept apart from Mirage's own) ------------------
    'split_mode': (('choice', ('off', 'bypass', 'only')), 'off', True),
    'split_iran': ('bool', True, True),
    'split_apps': (('apps', 200), [], True),
    'split_domains': (('domains', 500), [], True),

    # -- anti-filtering ---------------------------------------------------
    'fragment': ('bool', False, True),
    # sing-box's own split: the hello across TCP segments, across TLS
    # records, or both.
    'fragment_style': (('choice', ('tls', 'record', 'both')), 'both', True),
    # Or Xray's, with sizes and gaps of the person's choosing: every
    # VLESS/VMess/Trojan config with TLS or Reality is then carried by Xray.
    'fragment_engine': (('choice', ('singbox', 'xray')), 'singbox', True),
    'fragment_packets': (('choice', ('tlshello', '1-1', '1-2', '1-3', '1-5')),
                         'tlshello', True),
    'fragment_length': (('range', 1, 2000), '100-200', True),
    'fragment_interval': (('range', 0, 1000), '10-20', True),

    # -- DNS -----------------------------------------------------------------
    # Through the config, for everything that goes through it.
    'remote_dns': (('url', 300), 'https://1.1.1.1/dns-query', True),
    # Straight from this line: the servers' own names, and what the split
    # sends direct. Never the line's own resolver, which forges answers.
    'direct_dns': (('url', 300), 'https://1.1.1.1/dns-query', True),
    'ipv6': (('choice', ('off', 'prefer_ipv4', 'prefer_ipv6')), 'off', True),

    # -- subscriptions ---------------------------------------------------------
    'sub_auto_hours': (('int', 0, 168), 12, False),
    'sub_on_launch': ('bool', False, False),
    'sub_user_agent': (('text', 120), '', False),

    # -- tests and choosing ----------------------------------------------------
    # real: a page loaded through the config; tcp: only the time to open a
    # connection to the server, which says nothing about whether it carries.
    'test_kind': (('choice', ('real', 'tcp')), 'real', False),
    'test_url': (('url', 300), 'https://www.cloudflare.com/cdn-cgi/trace', False),
    'test_timeout': (('int', 2, 30), 8, False),
    'test_parallel': (('int', 1, 32), 8, False),
    'sort': (('choice', ('speed', 'added', 'name')), 'speed', False),
    # At most 30: sing-box refuses an interval longer than the urltest's
    # idle timeout, which is 30 minutes.
    'auto_interval': (('int', 1, 30), 10, True),
    'auto_tolerance': (('int', 1, 1000), 50, True),

    # -- advanced ----------------------------------------------------------------
    'mux': ('bool', False, True),
    'mux_protocol': (('choice', ('h2mux', 'smux', 'yamux')), 'h2mux', True),
    'mux_streams': (('int', 1, 64), 8, True),
    'mux_padding': ('bool', False, True),
    'tun_stack': (('choice', ('auto', 'system', 'gvisor', 'mixed')), 'auto', True),
    'tun_mtu': (('int', 1280, 9000), 9000, True),
    'strict_route': ('bool', True, True),
}

XRAY_FRAGMENTS = ('vless', 'vmess', 'trojan')


def engine_for(cfg, opts=None):
    """'singbox' or 'xray': which engine carries this config under these
    settings. links.engine_for says what each can carry at all; with Xray's
    fragment chosen, the TLS configs Xray can carry go to it as well."""
    base = links.engine_for(cfg)
    o = opts or {}
    if (base != 'xray' and o.get('fragment')
            and o.get('fragment_engine') == 'xray'
            and cfg.get('proto') in XRAY_FRAGMENTS
            and cfg.get('security') in ('tls', 'reality')):
        return 'xray'
    return base


def dns_server(url, tag, **extra):
    """A DoH address as a sing-box DNS server."""
    from urllib.parse import urlsplit
    parts = urlsplit(url or 'https://1.1.1.1/dns-query')
    out = {'type': 'https', 'tag': tag, 'server': parts.hostname or '1.1.1.1'}
    if parts.port:
        out['server_port'] = parts.port
    if parts.path and parts.path != '/dns-query':
        out['path'] = parts.path
    out.update(extra)
    return out


def dns_strategy(opts, tun=False):
    """The resolver's strategy. With the adapter it is IPv4 only whatever
    the setting: the adapter carries IPv4, and an AAAA answer would send a
    program round it on a line with IPv6."""
    if tun:
        return 'ipv4_only'
    return {'off': 'ipv4_only', 'prefer_ipv4': 'prefer_ipv4',
            'prefer_ipv6': 'prefer_ipv6'}.get((opts or {}).get('ipv6'), 'ipv4_only')


def defaults():
    return {k: v[1] for k, v in SCHEMA.items()}


_RANGE = re.compile(r'^\s*(\d{1,5})\s*(?:-\s*(\d{1,5}))?\s*$')
_APP = re.compile(r'^[A-Za-z0-9 ._()+-]{1,64}\.exe$', re.I)


def check(key, value):
    """The value as it will be kept, or ValueError saying why not."""
    if key not in SCHEMA:
        raise ValueError('not a setting')
    kind = SCHEMA[key][0]
    if kind == 'bool':
        return bool(value)
    name = kind[0]
    if name == 'choice':
        if value not in kind[1]:
            raise ValueError('not one of the choices')
        return value
    if name == 'int':
        try:
            n = int(value)
        except (TypeError, ValueError):
            raise ValueError('a whole number, please') from None
        if not kind[1] <= n <= kind[2]:
            raise ValueError(f'between {kind[1]} and {kind[2]}')
        return n
    if name == 'range':
        m = _RANGE.match(str(value or ''))
        if not m:
            raise ValueError('a number, or two like 10-30')
        a = int(m.group(1))
        b = int(m.group(2) or a)
        if not kind[1] <= a <= b <= kind[2]:
            raise ValueError(f'between {kind[1]} and {kind[2]}, smaller first')
        return f'{a}-{b}'
    if name == 'text':
        text = str(value or '').strip()
        if len(text) > kind[1] or any(ord(c) < 32 for c in text):
            raise ValueError(f'one line of at most {kind[1]} characters')
        return text
    if name == 'url':
        text = str(value or '').strip()
        if text and (len(text) > kind[1] or not re.match(r'^https://[^\s/]+', text)):
            raise ValueError('an https:// address')
        if text:
            from urllib.parse import urlsplit
            try:
                parts = urlsplit(text)
                parts.port  # noqa: B018 - raises for a port out of range
            except ValueError:
                raise ValueError('an https:// address with a port up to 65535') from None
            if not parts.hostname:
                raise ValueError('an https:// address')
        return text
    if name == 'domains':
        import split
        items = (value if isinstance(value, (list, tuple))
                 else re.split(r'[\s,;]+', str(value or '')))
        out = []
        for item in items:
            item = str(item).strip()
            if not item:
                continue
            try:
                norm = split.normalize(item)
            except split.Invalid:
                raise ValueError(f'"{item[:60]}" is not a name, address or prefix') from None
            if norm not in out:
                out.append(norm)
        if len(out) > kind[1]:
            raise ValueError(f'at most {kind[1]} entries')
        return out
    if name == 'apps':
        import split
        # One per line (or comma): a program's name may have spaces in it.
        items = (value if isinstance(value, (list, tuple))
                 else re.split(r'[\r\n,;]+', str(value or '')))
        out = []
        for item in items:
            item = str(item).strip()
            if not item:
                continue
            try:
                item = split.normalize_app(item)
            except split.Invalid:
                raise ValueError(f'"{item[:40]}" is not a program name like chrome.exe') from None
            if item.lower() not in (x.lower() for x in out):
                out.append(item)
        if len(out) > kind[1]:
            raise ValueError(f'at most {kind[1]} programs')
        return out
    raise ValueError('not a setting')


def split_of(values):
    """The split in the shape split.py keeps it, from these settings."""
    return {'mode': values.get('split_mode', 'off'),
            'iran': bool(values.get('split_iran', True)),
            'apps': list(values.get('split_apps') or []),
            'domains': list(values.get('split_domains') or [])}


def from_mirage(engine):
    """The first values, copied from Mirage's own switches - after this
    the two are apart."""
    out = {}
    try:
        on = bool(engine.set_system_proxy)
        out['mode'] = ('tun' if on and engine.route_with == 'tun'
                       else ('system' if on else 'local'))
        out['kill_switch'] = bool(engine.kill_switch)
        sp = engine.split or {}
        out['split_mode'] = sp.get('mode') or 'off'
        out['split_iran'] = bool(sp.get('iran', True))
        out['split_apps'] = list(sp.get('apps') or [])
        out['split_domains'] = list(sp.get('domains') or [])
    except Exception:
        return {}
    clean = {}
    for k, v in out.items():
        try:
            clean[k] = check(k, v)
        except ValueError:
            pass
    return clean


def reconnects(key):
    return bool(SCHEMA.get(key, (None, None, False))[2])


def merged(saved):
    """Every key, from what was saved where it is still allowed, else the
    default - so a file from an older or newer copy reads cleanly."""
    out = defaults()
    for k, v in (saved or {}).items():
        if k in SCHEMA:
            try:
                out[k] = check(k, v)
            except ValueError:
                pass
    return out

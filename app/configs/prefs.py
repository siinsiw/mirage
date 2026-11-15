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

SCHEMA = {
    # -- connection ---------------------------------------------------------
    # system: a local proxy, and Windows pointed at it; tun: the whole PC
    # through an adapter; local: only the local port, Windows left alone.
    'mode': (('choice', ('system', 'tun', 'local')), 'system', True),
    'kill_switch': ('bool', True, False),

    # -- routing (the split, kept apart from Mirage's own) ------------------
    'split_mode': (('choice', ('off', 'bypass', 'only')), 'off', True),
    'split_iran': ('bool', True, True),
    'split_apps': (('apps', 200), [], True),
    'split_domains': (('domains', 500), [], True),

    # -- anti-filtering ---------------------------------------------------
    'fragment': ('bool', False, True),
}


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
        items = value if isinstance(value, (list, tuple)) else \
            re.split(r'[\s,;]+', str(value or ''))
        out = []
        for item in items:
            item = str(item).strip()
            if not item:
                continue
            if not _APP.match(item):
                raise ValueError(f'"{item[:40]}" is not a program name like chrome.exe')
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

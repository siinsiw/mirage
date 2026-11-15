"""What is kept: the configs, their groups, the last test of each, and the
section's own switches - in one file, sealed.

Every config carries a password or an id that is as good as one, and a
subscription address is a key to the seller's whole list. So the file is
sealed with DPAPI the way settings.json's secrets are (only this Windows
account can open it), written atomically, and locked to this user. Where
DPAPI is not there at all it is kept as text behind the file's ACL, and that
is logged, rather than the list being dropped.

The page is never handed a secret. listing() is what it draws from;
link_of() is the one way a link comes back out, and only when asked for it.
"""

import json
import os
import threading
import time
import uuid

import paths
import settingsfile

from . import links, prefs

PATH = os.path.join(paths.STATE_DIR, 'configs.json')
MANUAL = 'manual'
MAX_TOTAL = 1000
MAX_GROUPS = 32

_LOCK = threading.RLock()

DEFAULTS = {'v': 1, 'groups': [{'id': MANUAL, 'kind': 'manual', 'name': ''}],
            'configs': [], 'selected': None, 'auto': False,
            'fragment': False, 'settings': {}}


def _empty():
    return json.loads(json.dumps(DEFAULTS))


def load(path=None):
    path = path or PATH
    with _LOCK:
        try:
            with open(path, encoding='utf-8') as f:
                outer = json.load(f)
        except (OSError, ValueError):
            return _empty()
        body = outer.get('sealed') if isinstance(outer, dict) else None
        if isinstance(body, str):
            text = settingsfile._unseal(body)
            if text is None:
                # Another Windows account's, or a copied file: unreadable
                # here, and not to be overwritten by an empty list either.
                raise RuntimeError('configs-unreadable: the saved configs '
                                   'were sealed by another Windows account')
            try:
                state = json.loads(text)
            except ValueError:
                return _empty()
        elif isinstance(outer, dict) and 'configs' in outer:
            state = outer
        else:
            return _empty()
        out = _empty()
        out.update({k: state[k] for k in DEFAULTS if k in state})
        if not any(g.get('id') == MANUAL for g in out['groups']):
            out['groups'].insert(0, {'id': MANUAL, 'kind': 'manual', 'name': ''})
        if not isinstance(out.get('settings'), dict):
            out['settings'] = {}
        # The one switch this section had before it had settings of its own.
        if 'fragment' not in out['settings'] and out.get('fragment'):
            out['settings']['fragment'] = True
        return out


def save(state, path=None):
    path = path or PATH
    with _LOCK:
        os.makedirs(os.path.dirname(path), exist_ok=True)
        text = json.dumps(state, ensure_ascii=False, separators=(',', ':'))
        sealed = settingsfile._seal_or_keep(text)
        outer = ({'v': 1, 'sealed': sealed} if sealed != text
                 else dict(state, v=1))
        settingsfile._write_locked(path, json.dumps(outer, ensure_ascii=False))


# -- changes, each one load-change-save under the lock ---------------------------

def _edit(fn, path=None):
    with _LOCK:
        state = load(path)
        out = fn(state)
        save(state, path)
        return out


def add(configs, group=MANUAL, path=None):
    """New configs into a group. Returns (added, already_there, over_limit)."""
    def go(state):
        have = {c['id'] for c in state['configs']}
        added, dup, over = 0, 0, 0
        for cfg in configs:
            if cfg['id'] in have:
                dup += 1
                continue
            if len(state['configs']) >= MAX_TOTAL:
                over += 1
                continue
            state['configs'].append(dict(cfg, group=group,
                                         allow_insecure=False, last=None))
            have.add(cfg['id'])
            added += 1
        return added, dup, over
    return _edit(go, path)


def replace_group(group, configs, path=None):
    """A subscription's list, swapped for what it serves now. A config that
    is still there keeps its last test and the person's choices about it."""
    def go(state):
        old = {c['id']: c for c in state['configs'] if c.get('group') == group}
        others = [c for c in state['configs'] if c.get('group') != group]
        room = MAX_TOTAL - len(others)
        fresh, seen = [], {c['id'] for c in others}
        for cfg in configs:
            if cfg['id'] in seen or len(fresh) >= room:
                continue
            seen.add(cfg['id'])
            keep = old.get(cfg['id']) or {}
            fresh.append(dict(cfg, group=group,
                              allow_insecure=bool(keep.get('allow_insecure')),
                              last=keep.get('last')))
        state['configs'] = others + fresh
        return len(fresh)
    return _edit(go, path)


def remove(ids, path=None):
    ids = set(ids or ())

    def go(state):
        before = len(state['configs'])
        state['configs'] = [c for c in state['configs'] if c['id'] not in ids]
        if state.get('selected') in ids:
            state['selected'] = None
        return before - len(state['configs'])
    return _edit(go, path)


def update(cid, path=None, **fields):
    """Rename, or the person's certificate switch for one server."""
    allowed = {'name', 'allow_insecure'}
    for k in fields:
        if k not in allowed:
            raise ValueError(f'{k} cannot be changed')

    def go(state):
        for c in state['configs']:
            if c['id'] == cid:
                for k, v in fields.items():
                    if k == 'name':
                        v = links.clean_name(v) or c['name']
                    if k == 'allow_insecure':
                        v = bool(v)
                    c[k] = v
                return True
        return False
    return _edit(go, path)


def set_results(results, path=None):
    """Latency results from probe.test, id -> result."""
    def go(state):
        now = time.time()
        for c in state['configs']:
            r = results.get(c['id'])
            if r is not None:
                c['last'] = dict(r, at=now)
    return _edit(go, path)


def set_option(key, value, path=None):
    if key not in ('selected', 'auto', 'fragment'):
        raise ValueError(f'{key} is not an option')

    def go(state):
        state[key] = value if key == 'selected' else bool(value)
    return _edit(go, path)


def settings(state=None):
    """Every setting of this section, saved or default (prefs.SCHEMA)."""
    state = state or load()
    return prefs.merged(state.get('settings'))


def set_settings(changes, path=None):
    """Several settings at once, each checked against prefs.SCHEMA; all or
    none. Returns the keys that changed."""
    clean = {k: prefs.check(k, v) for k, v in (changes or {}).items()}

    def go(state):
        have = prefs.merged(state.get('settings'))
        changed = [k for k, v in clean.items() if have.get(k) != v]
        state['settings'] = dict(state.get('settings') or {}, **clean)
        if 'fragment' in clean:
            state['fragment'] = clean['fragment']
        return changed
    return _edit(go, path)


def add_group(url, name='', path=None):
    def go(state):
        for g in state['groups']:
            if g.get('url') == url:
                return g['id']
        if len(state['groups']) >= MAX_GROUPS:
            raise ValueError('too many subscriptions')
        gid = 's-' + uuid.uuid4().hex[:10]
        state['groups'].append({'id': gid, 'kind': 'sub', 'url': url,
                                'name': links.clean_name(name), 'info': None,
                                'updated': None})
        return gid
    return _edit(go, path)


def update_group(gid, path=None, **fields):
    allowed = {'name', 'info', 'updated', 'error'}

    def go(state):
        for g in state['groups']:
            if g['id'] == gid:
                for k, v in fields.items():
                    if k not in allowed:
                        raise ValueError(f'{k} cannot be changed')
                    g[k] = links.clean_name(v) if k == 'name' else v
                return True
        return False
    return _edit(go, path)


def remove_group(gid, path=None):
    if gid == MANUAL:
        raise ValueError('the manual list cannot be removed')

    def go(state):
        state['groups'] = [g for g in state['groups'] if g['id'] != gid]
        gone = {c['id'] for c in state['configs'] if c.get('group') == gid}
        state['configs'] = [c for c in state['configs']
                            if c.get('group') != gid]
        if state.get('selected') in gone:
            state['selected'] = None
        return len(gone)
    return _edit(go, path)


# -- reading -----------------------------------------------------------------------

def find(cid, state=None):
    state = state or load()
    return next((c for c in state['configs'] if c['id'] == cid), None)


def group_of(cid, state=None):
    """Every config in the same group as cid, in the order they are shown."""
    state = state or load()
    cfg = find(cid, state)
    if not cfg:
        return []
    return ordered([c for c in state['configs']
                    if c.get('group') == cfg.get('group')])


def ordered(configs):
    """Fastest first, untested next, failed last - each kept in its order."""
    def key(c):
        last = c.get('last') or {}
        ms = last.get('ms')
        if ms is not None:
            return (0, ms)
        return (2, 0) if last.get('error') else (1, 0)
    return sorted(configs, key=key)


def link_of(cid):
    cfg = find(cid)
    return (cfg or {}).get('link') or ''


def group_url(gid):
    state = load()
    g = next((g for g in state['groups'] if g['id'] == gid), None)
    return (g or {}).get('url') or ''


def _public_group(g):
    out = {k: g.get(k) for k in ('id', 'kind', 'name', 'info', 'updated',
                                 'error')}
    url = g.get('url') or ''
    if url:
        # The host, not the address: the path is the key to the list.
        try:
            from urllib.parse import urlsplit
            parts = urlsplit(url)
            out['host'] = parts.hostname or ''
            out['plainHttp'] = parts.scheme == 'http'
        except ValueError:
            out['host'] = ''
    return out


def listing(state=None):
    """What the page draws: no id, password or subscription address."""
    state = state or load()
    configs = []
    for c in state['configs']:
        configs.append({
            'id': c['id'], 'group': c.get('group') or MANUAL,
            'name': c.get('name') or '', 'label': links.label(c),
            'proto': c.get('proto'), 'host': c.get('host'),
            'port': c.get('port'), 'warnings': links.warnings(c),
            'engine': links.engine_for(c),
            # Whether there is a certificate to check at all - the switch
            # to skip the check is offered only where there is.
            'tls': c.get('security') == 'tls'
                   or c.get('proto') in ('hysteria2', 'tuic'),
            'allowInsecure': bool(c.get('allow_insecure')),
            'last': c.get('last')})
    return {'groups': [_public_group(g) for g in state['groups']],
            'configs': configs, 'selected': state.get('selected'),
            'auto': bool(state.get('auto')),
            'fragment': bool(state.get('fragment'))}

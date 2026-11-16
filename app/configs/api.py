"""The Configs section, as the window talks to it.

main.Api holds one Section and exposes a single method, configs(action,
payload), that lands here: main.py is already long, and this keeps its share
of the section to a handful of lines. Everything the page can ask is a
do_<action> method below; anything slow (a test, a subscription, a connect)
runs on a thread and answers through events:

    window.onConfigs({kind: 'list' | 'test' | 'tested' | 'sub' | 'note', ...})

plus the events the home screen already listens for - Progress, Connected,
Failed, Blocked, Dropped - so the home shows a config connection the way it
shows any other.
"""

import ctypes
import os
import threading
import time
import traceback

import paths
import split

from . import links, prefs, probe, runner, store, subs


def clipboard_text(limit=links.MAX_TEXT):
    """The clipboard's text, or '' - Windows only, read-only, capped."""
    if os.name != 'nt':
        return ''
    user32, kernel32 = ctypes.windll.user32, ctypes.windll.kernel32
    user32.GetClipboardData.restype = ctypes.c_void_p
    kernel32.GlobalLock.restype = ctypes.c_void_p
    kernel32.GlobalLock.argtypes = [ctypes.c_void_p]
    kernel32.GlobalUnlock.argtypes = [ctypes.c_void_p]
    kernel32.GlobalSize.restype = ctypes.c_size_t
    kernel32.GlobalSize.argtypes = [ctypes.c_void_p]
    CF_UNICODETEXT = 13
    if not user32.OpenClipboard(None):
        return ''
    try:
        handle = user32.GetClipboardData(CF_UNICODETEXT)
        if not handle:
            return ''
        ptr = kernel32.GlobalLock(handle)
        if not ptr:
            return ''
        try:
            chars = min(kernel32.GlobalSize(handle) // 2, limit + 1)
            text = ctypes.wstring_at(ptr, chars)
        finally:
            kernel32.GlobalUnlock(handle)
        return text.split('\x00', 1)[0]
    finally:
        user32.CloseClipboard()


class Section:
    def __init__(self, owner):
        # main.Api: _emit, _retray, _engine, _sysproxy, _busy, _since,
        # _holding. Underscored there, so pywebview never walks into this.
        self.owner = owner
        self.runner = runner.Runner(owner._engine, owner._sysproxy)
        self._cancel = None
        self._fetching = set()
        self._reconnect = None
        # The first time this section has settings of its own, they start
        # as Mirage's; after that the two are apart.
        try:
            store.seed_settings(prefs.from_mirage(owner._engine))
        except Exception:
            self._log('seed: ' + traceback.format_exc())
        # Subscriptions brought up to date on their own, while the app is
        # open: at start when asked, then every N hours (Settings >
        # Subscriptions). Nothing else here runs unasked.
        threading.Thread(target=self._keep_subs_fresh, daemon=True).start()

    # -- the one door ------------------------------------------------------

    def call(self, action, payload=None):
        fn = getattr(self, 'do_' + str(action or ''), None)
        if fn is None:
            return {'ok': False, 'error': 'unknown request'}
        try:
            out = fn(payload if isinstance(payload, dict) else {})
            return dict({'ok': True}, **(out or {}))
        except (links.Invalid, subs.Refused, ValueError, RuntimeError) as e:
            return {'ok': False, 'error': str(e)}
        except Exception as e:
            self._log(f'{action}: ' + traceback.format_exc())
            return {'ok': False, 'error': f'that did not work ({type(e).__name__})'}

    def _emit(self, kind, **data):
        self.owner._emit('Configs', dict(data, kind=kind))

    def _log(self, text):
        try:
            os.makedirs(paths.STATE_DIR, exist_ok=True)
            with open(os.path.join(paths.STATE_DIR, 'configs-errors.log'), 'a',
                      encoding='utf-8') as f:
                f.write(time.strftime('%Y-%m-%d %H:%M:%S ') + text + '\n')
        except OSError:
            pass

    def _listing(self):
        out = store.listing()
        out['engines'] = {'singbox': bool(paths.singbox_exe()),
                          'xray': bool(paths.xray_exe())}
        r = self.runner
        out['live'] = {'on': r.alive(), 'current': r.current,
                       'auto': r.auto, 'using': r.now_using() if r.alive() else None,
                       'country': r.country if r.alive() else '',
                       'blocked': r.blocked() if r.port else False}
        out['testing'] = self._cancel is not None
        # The home's Iran direct switch: on, off, or standing aside in
        # "only these", where Iran is on the list and goes through.
        values = store.settings()
        out['iranDirect'] = split.iran_direct(prefs.split_of(values))
        out['splitOnly'] = values.get('split_mode') == 'only'
        return out

    # -- the list ------------------------------------------------------------

    def do_list(self, p):
        return {'list': self._listing()}

    def do_import(self, p):
        configs, problems = links.parse_text(p.get('text') or '')
        added, dup, over = store.add(configs)
        kinds = {}
        for c in configs:
            for w in links.warnings(c):
                kinds[w] = kinds.get(w, 0) + 1
        return {'added': added, 'dup': dup, 'over': over,
                'problems': problems[:30], 'warned': kinds,
                'ids': [c['id'] for c in configs], 'list': self._listing()}

    def do_remove(self, p):
        ids = [str(i) for i in (p.get('ids') or [])][:store.MAX_TOTAL]
        if self.runner.alive() and self.runner.current in ids:
            raise ValueError('that config is the one connected - disconnect first')
        return {'removed': store.remove(ids), 'list': self._listing()}

    def do_rename(self, p):
        store.update(str(p.get('id')), name=str(p.get('name') or ''))
        return {'list': self._listing()}

    def do_trust(self, p):
        """The person's own switch to skip the certificate check for one
        server. The page asks first, in words, every time it is turned on."""
        store.update(str(p.get('id')), allow_insecure=bool(p.get('on')))
        return {'list': self._listing()}

    def do_copy(self, p):
        return {'link': store.link_of(str(p.get('id')))}

    def do_paste(self, p):
        """What is on the clipboard, read and imported in one step - the
        page's Paste button. Read here rather than by the page because the
        window's own clipboard API asks for a permission it cannot show."""
        text = clipboard_text()
        if not text.strip():
            raise ValueError('the clipboard has no text in it')
        return self.do_import({'text': text})

    def do_option(self, p):
        key = str(p.get('key'))
        if key not in ('fragment',):
            raise ValueError('not an option')
        store.set_settings({key: bool(p.get('on'))})
        return {'list': self._listing(),
                'reconnect': self.runner.alive()}

    def do_pick(self, p):
        """The one Connect will use, without connecting: a config ({id}),
        or a country as a whole ({country}) - the fastest of its configs."""
        country = str(p.get('country') or '').lower()
        if country:
            rows = store.in_country(country)
            if not rows:
                raise ValueError('no config is filed under that country')
            store.set_option('selected', rows[0]['id'])
            store.set_option('country', country)
            return {'list': self._listing()}
        cid = str(p.get('id') or '')
        if not store.find(cid):
            raise ValueError('that config is not there any more')
        store.set_option('selected', cid)
        store.set_option('country', '')
        return {'list': self._listing()}

    # -- the section's own settings ------------------------------------------

    # -- split tunnelling, edited with Mirage's own pane ----------------------

    def do_split(self, p):
        """This section's split, in the shape Mirage's split pane reads, with
        the counts of the Iranian lists it ships."""
        values = store.settings()
        return {'split': split.clean(prefs.split_of(values))[0],
                'splitLists': split.iran_counts(),
                'routeWith': 'tun' if values.get('mode') == 'tun' else 'proxy',
                'live': self.runner.alive()}

    def do_setSplit(self, p):
        """The pane's Apply. Cleaned as Mirage's own is, and what could not be
        a rule handed back. sing-box takes its rules when it starts, so a
        connection that is up is made again with them."""
        cleaned, rejected = split.clean(p if isinstance(p, dict) else {})
        before = split.rules(prefs.split_of(store.settings()))
        store.set_settings({'split_mode': cleaned['mode'],
                            'split_iran': cleaned['iran'],
                            'split_apps': cleaned['apps'],
                            'split_domains': cleaned['domains']})
        self.runner.prefs_changed()
        r = self.runner
        if not r.alive():
            return {'split': cleaned, 'rejected': rejected, 'applied': 'next'}
        if split.rules(cleaned) == before:
            # Nothing in force changed (a list edited while it is Off, say):
            # the connection that is up is left alone.
            return {'split': cleaned, 'rejected': rejected, 'applied': 'now'}
        applied = 'next'
        if not self.owner._busy:
            again = {'id': None if r.current == 'auto' else r.current,
                     'auto': r.auto, 'country': r.country}
            try:
                self.do_connect(again)
                applied = 'reconnect'
            except ValueError:
                # Saved either way; it takes over on the next connect.
                pass
        return {'split': cleaned, 'rejected': rejected, 'applied': applied}

    def do_settings(self, p):
        return {'values': store.settings()}

    def do_set(self, p):
        """One or more settings, {values: {key: value}}. Says whether the
        connection that is up has to be made again for them to apply."""
        changes = p.get('values')
        if not isinstance(changes, dict) or not changes:
            raise ValueError('nothing to change')
        changed = store.set_settings(changes)
        values = store.settings()
        self.runner.prefs_changed()
        applied = self._apply_live(changed, values)
        return {'values': values, 'changed': changed, 'applied': applied,
                'reconnect': self.runner.alive() and not applied
                and any(prefs.reconnects(k) for k in changed)}

    def _apply_live(self, changed, values):
        """What can follow a change on a connection that is up, does: the
        kill switch's hold, and the system proxy put on or taken off. A
        move between the proxy and the adapter connects the same config
        again. True when the change is already in effect."""
        done = False
        if 'kill_switch' in changed:
            # Held is exactly when nothing is active - so this goes first,
            # and on its own: turned off while holding, the hold goes.
            on = bool(values.get('kill_switch'))
            if self.kill_switch_changed(on):
                self.owner._holding = False
                self.owner._emit('Disconnected', {'minutes': 0})
                return True
            done = self.runner.active()
        if not self.runner.active():
            return done
        if 'mode' in changed:
            out = self.reroute()
            done = out is True or out == 'reconnect'
        return done

    # -- subscriptions -----------------------------------------------------

    def _through(self):
        """Where to fetch through: the connection that is up, if any."""
        addr = self.runner.proxy_address()
        if addr:
            return addr
        eng = self.owner._engine
        try:
            if eng.running():
                return f'127.0.0.1:{eng.port}'
        except Exception:
            pass
        return None

    def do_addSub(self, p):
        url = subs.check_url(p.get('url'))
        gid = store.add_group(url, str(p.get('name') or ''))
        self._fetch(gid)
        return {'group': gid, 'list': self._listing()}

    def do_updateSub(self, p):
        gid = str(p.get('group'))
        if not store.group_url(gid):
            raise ValueError('that subscription is not there any more')
        self._fetch(gid)
        return {}

    def do_removeSub(self, p):
        gid = str(p.get('group'))
        cur = self.runner.current
        if self.runner.alive() and any(c['id'] == cur and c.get('group') == gid
                                       for c in store.load()['configs']):
            raise ValueError('a config of that subscription is the one '
                             'connected - disconnect first')
        return {'removed': store.remove_group(gid), 'list': self._listing()}

    def _keep_subs_fresh(self, first_wait=30, every=300):
        stop = getattr(self.owner, '_stop', None)
        wait = (stop.wait if stop is not None else time.sleep)
        if wait(first_wait):
            return
        first = True
        tried = {}
        while True:
            try:
                opts = store.settings()
                hours = int(opts.get('sub_auto_hours') or 0)
                now = time.time()
                # Not while a kill switch is holding: with nothing up the
                # fetch would go out on the very line that is being held.
                held = self.runner.blocked() or self.owner._engine.blocked()
                for g in ([] if held else store.load()['groups']):
                    if g.get('kind') != 'sub':
                        continue
                    stale = hours and (now - float(g.get('updated') or 0)) > hours * 3600
                    # A fetch that failed is not tried again for an hour, or
                    # for the whole interval when that is shorter.
                    recent = now - tried.get(g['id'], 0) < min(3600, max(hours, 1) * 3600)
                    if ((first and opts.get('sub_on_launch')) or stale) and not recent:
                        tried[g['id']] = now
                        self._fetch(g['id'], quiet=True)
            except Exception:
                self._log('subs: ' + traceback.format_exc())
            first = False
            if wait(every):
                return

    def _fetch(self, gid, quiet=False):
        """quiet: a fetch nobody pressed for - its failure is kept on the
        group, not said over whatever the person is doing."""
        if gid in self._fetching:
            return
        self._fetching.add(gid)
        via = self._through()

        def work():
            try:
                agent = store.settings().get('sub_user_agent') or None
                configs, problems, info, name = subs.fetch(
                    store.group_url(gid), via, user_agent=agent)
                n = store.replace_group(gid, configs)
                fields = {'info': info, 'updated': time.time(), 'error': None}
                state = store.load()
                g = next((g for g in state['groups'] if g['id'] == gid), {})
                if not g.get('name') and name:
                    fields['name'] = name
                store.update_group(gid, **fields)
                self._emit('sub', group=gid, ok=True, count=n, quiet=quiet,
                           problems=problems[:30], through=bool(via),
                           list=self._listing())
            except Exception as e:
                said = str(e) if isinstance(e, (subs.Refused, links.Invalid)) \
                    else f'could not be updated ({type(e).__name__})'
                if not isinstance(e, (subs.Refused, links.Invalid)):
                    self._log('sub: ' + traceback.format_exc())
                store.update_group(gid, error=said[:200])
                self._emit('sub', group=gid, ok=False, error=said, quiet=quiet,
                           list=self._listing())
            finally:
                self._fetching.discard(gid)
        threading.Thread(target=work, daemon=True).start()

    # -- tests --------------------------------------------------------------

    def do_test(self, p):
        if self._cancel is not None:
            raise ValueError('a test is already running')
        state = store.load()
        ids = p.get('ids')
        if ids:
            want = set(str(i) for i in ids)
            configs = [c for c in state['configs'] if c['id'] in want]
        else:
            gid = p.get('group')
            configs = [c for c in state['configs']
                       if gid is None or c.get('group') == gid]
        if not configs:
            raise ValueError('nothing to test')
        cancel = self._cancel = threading.Event()
        opts = store.settings(state)
        fragment = bool(opts.get('fragment'))

        def work():
            try:
                results = probe.test(
                    configs, fragment=fragment, cancel=cancel, opts=opts,
                    progress=lambda done, total, cid, r: self._emit(
                        'test', done=done, total=total, id=cid, result=r))
                store.set_results({k: v for k, v in results.items()
                                   if v.get('error') != 'cancelled'})
                self._cancel = None
                self._emit('tested', count=len(results), list=self._listing())
            except Exception as e:
                self._log('test: ' + traceback.format_exc())
                self._cancel = None
                self._emit('tested', count=0, error=str(e)[:200],
                           list=self._listing())
        threading.Thread(target=work, daemon=True).start()
        return {'total': len(configs)}

    def do_cancelTest(self, p):
        if self._cancel is not None:
            self._cancel.set()
        return {}

    # -- the connection --------------------------------------------------

    def do_connect(self, p):
        """{id, auto} for one config (or its group's fastest), {country} for
        the fastest of a country's configs, or neither for the pick saved."""
        cid = str(p.get('id') or '')
        country = str(p.get('country') or '').lower()
        auto = bool(p.get('auto'))
        if not cid and not country:
            saved = store.load()
            cid = str(saved.get('selected') or '')
            country = str(saved.get('country') or '')
        if country:
            rows = store.in_country(country)
            if rows:
                # The fastest of the country leads, whatever was picked
                # inside it last - the pick may have moved country since.
                cid = rows[0]['id']
                auto = True
            elif cid and not p.get('country'):
                # The saved country emptied by a test since: the config
                # picked with it is what is left of the pick.
                country = ''
            else:
                raise ValueError('no config is filed under that country')
        if not cid:
            raise ValueError('pick a config first')
        owner = self.owner
        if owner._busy:
            raise ValueError('busy')
        owner._busy = True
        owner._retray('busy')
        self._reconnect = None

        def work():
            try:
                # One connection at a time: the provider side goes first -
                # held, with the kill switch on, the way a switch between two
                # provider exits is: Windows stays pointed at the worker that
                # is going (or its adapter stays up) until this connection
                # takes over, so nothing in between leaves on the bare line.
                try:
                    if owner._engine.running() or owner._engine.engaged():
                        owner._engine.disconnect(
                            quiet=True, hold=owner._engine.holding())
                except Exception:
                    pass
                status = self.runner.connect(
                    cid, lambda ph: owner._emit('Progress', ph), auto=auto,
                    country=country)
                owner._since = time.time()
                owner._holding = False
                owner._retray('on')
                owner._emit('Connected', dict(status, since=owner._since))
                self._emit('list', list=self._listing())
                self._confirm()
            except Exception as e:
                self._log(f'connect {cid}: ' + traceback.format_exc())
                kind = str(e).split(':')[0] if str(e) else type(e).__name__
                # Held by either side: this one's port, or the provider's,
                # left pointed at when the handoff above held it.
                blocked = False
                try:
                    blocked = self.runner.blocked() or owner._engine.blocked()
                except Exception:
                    pass
                owner._holding = blocked
                if blocked:
                    self._reconnect = (cid, auto, country)
                owner._retray('off')
                owner._emit('Failed', {'kind': kind, 'detail': str(e)[:600],
                                       'port': self.runner.port or 0,
                                       'blocked': blocked, 'mode': 'configs'})
                self._emit('list', list=self._listing())
            finally:
                owner._busy = False
        threading.Thread(target=work, daemon=True).start()
        return {}

    def do_switch(self, p):
        cid = str(p.get('id') or '')
        if self.runner.alive() and not self.runner.auto and self.runner.switch(cid):
            self.owner._emit('Connected', dict(self.runner.status(),
                                               since=self.owner._since))
            self._emit('list', list=self._listing())
            self._confirm()
            return {'live': True}
        return self.do_connect({'id': cid})

    def do_disconnect(self, p):
        return self.owner.disconnect()

    def do_autostart(self, p):
        """At start, Settings > Connection > Connect on opening: the last one
        used, or the fastest of its group. Only with nothing up and nothing
        under way."""
        how = store.settings().get('auto_connect')
        state = store.load()
        cid = state.get('selected')
        owner = self.owner
        if how not in ('last', 'fastest') or not cid or not store.find(cid, state):
            return {'started': False}
        try:
            if owner._busy or self.runner.active() or owner._engine.running():
                return {'started': False}
        except Exception:
            return {'started': False}
        if state.get('country'):
            # A country picked as a whole comes back as that country.
            self.do_connect({'country': state['country']})
        else:
            self.do_connect({'id': cid, 'auto': how == 'fastest'})
        return {'started': True}

    def _confirm(self):
        def work():
            info = self.runner.info
            if not info:
                return
            try:
                info['seen_as'] = self.runner.verify()
            except Exception as e:
                info['unconfirmed'] = str(e)[:120]
            if self.runner.info is info:
                self.owner._emit('Connected', dict(self.runner.status(),
                                                   since=self.owner._since))
                self.owner._retray()
        threading.Thread(target=work, daemon=True).start()

    # -- for main.py -----------------------------------------------------

    def kill_switch_changed(self, on):
        """main.setKillSwitch, for a config connection: the adapter's hold
        follows the switch, and turned off while holding, the hold goes -
        that is what turning it off means. True when something was let go."""
        r = self.runner
        r.set_kill_switch(on)
        if on or r.alive() or not r.port:
            return False
        eng = self.owner._engine
        # A hold Mirage's own connection raised is Mirage's kill switch's.
        if eng.running() or eng.engaged() or not r.owns_hold:
            return False
        if r.engaged() or eng.tun.held_by_firewall():
            r.disconnect(quiet=True)
            self._reconnect = None
            return True
        return False

    def reroute(self):
        """main.setSystemProxy / setRouteWith, for a config connection:
        applied in place where it can be, otherwise the same config is
        connected again - a switch that says one thing while the machine
        does another is the alternative."""
        r = self.runner
        if not r.active():
            return None
        out = r.reroute()
        if out == 'reconnect':
            cid = r.current if r.current and r.current != 'auto' else None
            try:
                self.do_connect({'id': cid, 'auto': r.auto})
            except ValueError as e:
                self._log(f'reroute: {e}')
                return 'failed'
        return out

    def take_reconnect(self):
        """The config to bring back when the home's Connect is pressed while
        this section's kill switch is holding, or None."""
        if self._reconnect and self.runner.blocked():
            return self._reconnect
        return None

    def watch_drop(self):
        """The programs went away by themselves (a crash, an antivirus).
        The kill switch decides, exactly as for the provider worker."""
        r = self.runner
        if r.alive() or not r.expected_up:
            return
        if not r.lock.acquire(timeout=0.5):
            return
        try:
            if not r.expected_up or r.alive():
                return
            r.expected_up = False
            held = r.blocked()
            cur = (r.current, r.auto, r.country)
        finally:
            r.lock.release()
        owner = self.owner
        owner._since = None
        owner._retray('off')
        if held:
            owner._holding = True
            r.owns_hold = True
            self._reconnect = (None if cur[0] == 'auto' else cur[0], cur[1],
                               cur[2])
            owner._emit('Blocked', {'reason': 'adapter' if r.mode == 'tun'
                                    else 'dropped', 'status': r.status()})
        else:
            try:
                r.disconnect(quiet=True)
            except Exception:
                pass
            # Settings > Connection > Reconnect when it drops.
            if r.prefs().get('reconnect_on_drop') and (cur[0] or cur[2]):
                try:
                    self.do_connect({'id': None if cur[0] == 'auto' else cur[0],
                                     'auto': cur[1], 'country': cur[2]})
                    return
                except ValueError:
                    pass
            owner._emit('Dropped', {'status': r.status()})

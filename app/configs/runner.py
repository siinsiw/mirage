"""One connection through a config: up, switched, watched, down.

The machine is pointed at it the way the rest of the app points it at the
provider worker - the same system proxy object, the same adapter (tun.Tun,
started here with this section's own sing-box configuration), the same kill
switch setting and the same split lists - so the person has one set of
switches, whichever section they connect from. Only one connection is up at
a time: main.py takes the other one down before this one comes up, and the
other way round.

    proxy mode  sing-box runs as a child of this app, unelevated, with a mixed
                inbound on the loopback (10808 when it is free). The system
                proxy points there.
    tun mode    the same configuration plus a tun inbound, started elevated
                by tun.Tun exactly as the provider adapter is, watching this
                app's own process.

Configs that only Xray carries (xhttp and a few more) get an Xray child
beside it, unelevated in both modes and left out of the adapter by program.

The group of the chosen config is loaded whole, so switching to another
config of the same group is a word to sing-box's Clash API - no restart, no
UAC prompt, no gap. A config in another group, or turning auto on or off,
is a reconnect.

The meter and the host list are read from the Clash API once a second while
up, and handed over in the same shapes the worker's are, so the page draws
them without knowing which section is connected.
"""

import os
import sys
import threading
import time
import urllib.request

import paths
import split
import tun as tunmod

from . import links, procs, singbox, store, xray

PREFERRED_PORT = 10808
RECORD = os.path.join(procs.DIR, 'running.json')


class Meter:
    """Totals, rates and a host list, from /connections polled each second."""

    MAX_HOSTS = 200

    def __init__(self):
        self.lock = threading.Lock()
        self.reset()

    def reset(self):
        with getattr(self, 'lock', threading.Lock()):
            self.since = time.time()
            self.up = self.down = 0
            self.up_rate = self.down_rate = 0.0
            self.at = 0.0
            self.prev = None
            self.rows = {}
            self.seen = {}

    def feed(self, data, now=None):
        now = now or time.time()
        up = int(data.get('uploadTotal') or 0)
        down = int(data.get('downloadTotal') or 0)
        with self.lock:
            if self.prev:
                span = max(now - self.prev[0], 1e-3)
                self.up_rate = max(0, up - self.prev[1]) / span
                self.down_rate = max(0, down - self.prev[2]) / span
            self.prev = (now, up, down)
            self.up, self.down, self.at = up, down, now
            live = {}
            for c in data.get('connections') or []:
                md = c.get('metadata') or {}
                host = str(md.get('host') or md.get('destinationIP') or '')[:120]
                if not host:
                    continue
                app = os.path.basename(str(md.get('processPath') or ''))[:60] or None
                cid = str(c.get('id') or '')
                cu, cd = int(c.get('upload') or 0), int(c.get('download') or 0)
                pu, pd = self.seen.get(cid, (0, 0))
                row = self.rows.get(host)
                if row is None:
                    if len(self.rows) >= self.MAX_HOSTS:
                        continue
                    row = self.rows[host] = {'host': host, 'app': app, 'pid': 0,
                                             'up': 0, 'down': 0, 'hits': 0,
                                             'live': 0, 'first': now,
                                             'last': now}
                if cid not in self.seen:
                    row['hits'] += 1
                row['up'] += max(0, cu - pu)
                row['down'] += max(0, cd - pd)
                row['last'] = now
                row['app'] = row['app'] or app
                live[host] = live.get(host, 0) + 1
                self.seen[cid] = (cu, cd)
            current = {str(c.get('id') or '') for c in data.get('connections') or []}
            self.seen = {k: v for k, v in self.seen.items() if k in current}
            for host, row in self.rows.items():
                row['live'] = live.get(host, 0)

    def traffic(self):
        with self.lock:
            stale = (time.time() - self.at) > 3.5
            return {'live': True, 'up': self.up, 'down': self.down,
                    'upRate': 0.0 if stale else round(self.up_rate, 1),
                    'downRate': 0.0 if stale else round(self.down_rate, 1),
                    'since': self.since, 'stale': stale}

    def hosts(self):
        with self.lock:
            rows = sorted(self.rows.values(), key=lambda r: -r['last'])
            return {'live': True, 'rows': [dict(r) for r in rows],
                    'total': len(rows), 'since': self.since, 'at': self.at,
                    'every': 1}


class Runner:
    def __init__(self, engine, sysproxy):
        self.engine = engine
        self.sysproxy = sysproxy
        self.lock = threading.RLock()
        self.expected_up = False
        self.mode = None
        self.port = None
        self.clash = None
        self.box = None
        self.xray = None
        self.group = []
        self.auto = False
        self.current = None
        self.info = None
        self.since = None
        self.meter = Meter()
        self._poll_stop = threading.Event()
        self._poll = None

    # -- what state are we in -----------------------------------------------

    def box_alive(self):
        if self.mode == 'tun':
            return bool(self.port and self.engine.tun.engaged_for(self.port))
        return bool(self.box is not None and self.box.poll() is None)

    def xray_alive(self):
        return self.xray is None or self.xray.poll() is None

    def alive(self):
        return self.box_alive() and self.xray_alive()

    def active(self):
        """Up, or meant to be (held by the kill switch counts)."""
        return bool(self.expected_up or self.box_alive())

    def engaged(self):
        if not self.port:
            return False
        try:
            return bool(self.engine.tun.engaged_for(self.port)
                        or self.sysproxy.engaged_for(self.port))
        except Exception:
            return False

    def blocked(self):
        if self.alive():
            return False
        return bool((self.engine.kill_switch and self.engaged())
                    or self.engine.tun.held_by_firewall())

    def holding(self):
        return bool(self.engine.kill_switch
                    and (self.engaged() or self.engine.tun.held_by_firewall()))

    def status(self):
        on = self.alive()
        state = 'on' if on and (self.engaged() or not self.engine.set_system_proxy) \
            else ('broken' if on else 'off')
        out = {'state': state, 'port': self.port or 0,
               'systemProxy': self.engine.set_system_proxy,
               'routeWith': self.engine.route_with,
               'tun': self.mode == 'tun' and on,
               'blocked': self.blocked() if self.port else False,
               'killSwitch': self.engine.kill_switch,
               'owed_restore': bool(self.sysproxy.stashed()),
               'configs': True}
        if self.info:
            out['exit'] = dict(self.info)
        if self.since:
            out['since'] = self.since
        return out

    def traffic(self):
        return self.meter.traffic() if self.alive() else None

    def hosts(self):
        return self.meter.hosts() if self.alive() else None

    # -- up -----------------------------------------------------------------

    def _pick_port(self):
        if procs.port_free(PREFERRED_PORT):
            return PREFERRED_PORT
        return procs.free_ports(1)[0]

    def connect(self, cid, progress, auto=False):
        """Up through config `cid` (its whole group loaded), or with auto,
        through whichever of the group answers fastest."""
        with self.lock:
            state = store.load()
            cfg = store.find(cid, state) if cid else None
            if cfg is None:
                raise RuntimeError('config-missing: that config is not there any more')
            group = store.group_of(cid, state)
            sb = paths.singbox_exe()
            if not sb:
                raise RuntimeError('engine-missing: sing-box.exe was not found')
            xbin = paths.xray_exe()
            if not xbin:
                if links.engine_for(cfg) == 'xray':
                    raise RuntimeError('xray-missing: this config needs Xray '
                                       '(xray.exe), which was not found')
                group = [c for c in group if links.engine_for(c) != 'xray']

            # A switch with the kill switch on keeps the machine pointed here
            # while the old programs go and the new ones come up - and if the
            # new ones never do, it stays held: that is the promise.
            held = self.holding()
            self.disconnect(quiet=True, hold=held)
            progress({'phase': 'starting', 'country': '', 'city': cfg['name']})
            fragment = bool(store.settings(state).get('fragment'))
            mode = ('tun' if self.engine.set_system_proxy
                    and self.engine.route_with == 'tun' else 'proxy')
            port = self.port if (self.port and mode == self.mode
                                 and procs.port_free(self.port)) \
                else self._pick_port()
            clash_port = procs.free_ports(1)[0]
            secret = procs.token()

            hops = {}
            for_xray = [c for c in group if links.engine_for(c) == 'xray']
            try:
                if for_xray:
                    ports = procs.free_ports(len(for_xray))
                    entries = [(c, ports[n], 'relay', procs.token())
                               for n, c in enumerate(for_xray)]
                    hops = {c['id']: (p, u, pw) for c, p, u, pw in entries}
                    path = procs.write_config(xray.build(entries, fragment),
                                              'xray')
                    try:
                        self.xray, xlog = procs.start(xbin, ['run', '-c', path],
                                                      'xray.log')
                        if not procs.wait(self.xray, lambda: all(
                                procs.listening(p) for p in ports), 15):
                            raise RuntimeError('xray-failed: ' + (
                                procs.tail(xlog, 400) or 'Xray did not start'))
                    finally:
                        procs.forget(path)

                split_rules = split.rules(self.engine.split)
                config = singbox.build(
                    group, port=port, clash_port=clash_port,
                    clash_secret=secret, selected=cid, auto=auto, mode=mode,
                    split_rules=split_rules,
                    line_resolver=tunmod.line_dns() if split_rules else None,
                    xray=hops, fragment=fragment, own=[sys.executable],
                    carriers=[xbin, sb])
                self.clash = procs.Clash(clash_port, secret)
                if mode == 'proxy':
                    path = procs.write_config(config, 'run')
                    try:
                        self.box, log = procs.start(
                            sb, ['run', '-c', path, '--disable-color'], 'run.log')
                        if not procs.wait(self.box, self.clash.up, 20):
                            raise RuntimeError('did-not-start: ' + (
                                procs.tail(log, 400) or 'sing-box did not start'))
                    finally:
                        procs.forget(path)
                    if self.engine.set_system_proxy:
                        progress({'phase': 'routing'})
                        self.sysproxy.engage('127.0.0.1', port)
                        if self.engine.tun.held_by_firewall():
                            self.engine.tun.release_firewall()
                else:
                    progress({'phase': 'adapter'})
                    self.engine.tun.start(port, watch=os.getpid(),
                                          hold=self.engine.kill_switch,
                                          config=config)
                    if not procs.wait(_Never(), self.clash.up, 10):
                        raise RuntimeError('did-not-start: the adapter came up '
                                           'but sing-box did not answer')
            except Exception:
                self.mode, self.port = mode, port
                self._teardown(release=not held)
                raise

            self.mode, self.port = mode, port
            self.group = group
            self.auto = bool(auto)
            self.current = 'auto' if auto else cid
            self.since = time.time()
            self.info = self._info(cfg)
            self.expected_up = True
            self._remember()
            self._start_polling()
            store.set_option('selected', cid)
            store.set_option('auto', bool(auto))
            return self.status()

    def _info(self, cfg):
        last = cfg.get('last') or {}
        return {'ip': cfg['host'], 'host': ('Auto · ' if self.auto else '')
                + cfg['name'], 'country': last.get('country') or '',
                'city': '', 'provider': 'configs', 'answered': 0.0,
                'config': {'id': cfg['id'], 'name': cfg['name'],
                           'label': links.label(cfg)}}

    def switch(self, cid):
        """Another config of the same group, live. False when it is not in
        the loaded group (the caller reconnects instead)."""
        with self.lock:
            cfg = next((c for c in self.group if c['id'] == cid), None)
            if cfg is None or not self.alive() or self.auto:
                return False
            self.clash.select(singbox.PROXY, singbox.tag_of(cfg))
            self.current = cid
            self.info = self._info(cfg)
            store.set_option('selected', cid)
            return True

    def now_using(self):
        """With auto: the config the urltest picked, by id."""
        if not self.clash:
            return None
        tag = self.clash.now(singbox.AUTO if self.auto else singbox.PROXY)
        if tag and tag.startswith('c-'):
            return tag[2:]
        return None

    def verify(self, timeout=15):
        """Who the far end thinks we are, asked through the loopback port."""
        proxy = f'http://127.0.0.1:{self.port}'
        opener = urllib.request.build_opener(
            urllib.request.ProxyHandler({'http': proxy, 'https': proxy}))
        req = urllib.request.Request('https://www.cloudflare.com/cdn-cgi/trace',
                                     headers={'User-Agent': 'Mozilla/5.0'})
        with opener.open(req, timeout=timeout) as r:
            body = r.read(4096).decode('utf-8', 'replace')
        seen = dict(line.split('=', 1) for line in body.splitlines()
                    if '=' in line)
        return {'ip': seen.get('ip'), 'country': (seen.get('loc') or '').lower()}

    def proxy_address(self):
        """host:port to fetch through while up, else None."""
        return f'127.0.0.1:{self.port}' if self.alive() else None

    # -- settings changed while up --------------------------------------------

    def set_kill_switch(self, on):
        """The kill switch flipped while a config's adapter is up: its hold
        follows, as the provider adapter's does (main.setKillSwitch only
        knows the provider's port)."""
        try:
            if self.port and self.engine.tun.engaged_for(self.port):
                self.engine.tun.set_hold(bool(on))
                return True
        except Exception:
            pass
        return False

    def reroute(self):
        """The system-proxy switch or proxy-versus-adapter changed while a
        config is up. True when applied in place (the proxy setting put on
        or taken off), 'reconnect' when it is a move between the proxy
        setting and the adapter and the programs have to start again, False
        when nothing is up."""
        with self.lock:
            if not self.alive():
                return False
            want = ('tun' if self.engine.set_system_proxy
                    and self.engine.route_with == 'tun' else 'proxy')
            if want != self.mode:
                return 'reconnect'
            if self.mode == 'proxy':
                if self.engine.set_system_proxy:
                    if not self.sysproxy.engaged_for(self.port):
                        self.sysproxy.engage('127.0.0.1', self.port)
                elif self.sysproxy.engaged_for(self.port):
                    self.sysproxy.restore()
            return True

    # -- down ---------------------------------------------------------------

    def disconnect(self, quiet=False, hold=False):
        """The machine back first, then the programs - unless holding (a
        switch with the kill switch on), when the machine stays pointed at
        the port and only the programs go."""
        with self.lock:
            self.expected_up = False
            self._teardown(release=not hold)
            self.info = None
            self.since = None
            self.current = None
            if not quiet:
                return self.status()

    def _teardown(self, release=True):
        self._poll_stop.set()
        if release and self.port:
            try:
                if self.engine.tun.engaged_for(self.port):
                    self.engine.tun.stop()
                if self.sysproxy.engaged_for(self.port):
                    self.sysproxy.restore()
                if self.engine.tun.held_by_firewall():
                    self.engine.tun.release_firewall()
            except Exception:
                pass
        # Holding (a switch with the kill switch on): nothing is put back. In
        # proxy mode Windows stays pointed at the port while its program
        # goes, so what is in flight fails rather than leaving. In tun mode
        # the adapter is left up: a stop that was asked for raises no
        # firewall hold, so stopping it here would put every program on the
        # bare line until the next one came up. The next tun.Tun.start
        # replaces it, as the provider side's own switches do.
        procs.stop(self.box)
        procs.stop(self.xray)
        self.box = self.xray = None
        self._forget()
        self.meter.reset()

    def tidy(self):
        """After a crash: the programs a previous run left, if they are still
        the programs they were."""
        rec = _read_record()
        for key, image in (('box', 'sing-box.exe'), ('xray', 'xray.exe')):
            procs.kill_if(rec.get(key), image)
        self._forget()

    # -- bookkeeping --------------------------------------------------------

    def _remember(self):
        rec = {'box': self.box.pid if self.box else 0,
               'xray': self.xray.pid if self.xray else 0,
               'port': self.port, 'mode': self.mode}
        try:
            import json
            os.makedirs(procs.DIR, exist_ok=True)
            with open(RECORD, 'w', encoding='utf-8') as f:
                json.dump(rec, f)
        except OSError:
            pass

    def _forget(self):
        try:
            os.remove(RECORD)
        except OSError:
            pass

    def _start_polling(self):
        self._poll_stop = threading.Event()
        self.meter.reset()
        stop, clash = self._poll_stop, self.clash

        def run():
            while not stop.wait(1.0):
                try:
                    self.meter.feed(clash.connections())
                except Exception:
                    continue
        self._poll = threading.Thread(target=run, daemon=True)
        self._poll.start()


class _Never:
    """A stand-in child for procs.wait when there is no child to watch (the
    adapter's sing-box is the elevated script's, not ours)."""

    def poll(self):
        return None


def _read_record():
    try:
        import json
        with open(RECORD, encoding='utf-8') as f:
            rec = json.load(f)
        return rec if isinstance(rec, dict) else {}
    except (OSError, ValueError):
        return {}

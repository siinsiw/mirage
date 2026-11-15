"""The Configs runner with the kill switch and the routing switches: what a
handoff leaves held, and what a switch flipped while a config is up does.

No sing-box and no adapter here - the engine, its adapter, the system proxy
and the child program are stand-ins that remember what was asked of them.
"""

import os
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from configs import api, prefs, runner, store                  # noqa: E402

# _forget removes the runner's record: never the real one.
runner.RECORD = os.path.join(tempfile.mkdtemp(), 'running.json')
# Configs' own settings, kept away from the real file.
store.PATH = os.path.join(tempfile.mkdtemp(), 'configs.json')

PORT = 10808          # the Configs section's own port
ENGINE_PORT = 8877    # the provider worker's


class Tun:
    def __init__(self, port=None):
        self.port = port
        self.hold = False
        self.firewall = False
        self.stops = 0

    def engaged_for(self, port):
        return self.port is not None and self.port == port

    def set_hold(self, on):
        self.hold = bool(on)
        return True

    def stop(self):
        self.stops += 1
        was = self.port is not None
        self.port = None
        return was

    def held_by_firewall(self):
        return self.firewall

    def release_firewall(self):
        self.firewall = False
        return True


class Engine:
    def __init__(self, tun, kill_switch=True, system_proxy=True, route='proxy'):
        self.tun = tun
        self.port = ENGINE_PORT
        self.kill_switch = kill_switch
        self.set_system_proxy = system_proxy
        self.route_with = route

    def running(self):
        return False

    def engaged(self):
        return False


class SysProxy:
    def __init__(self, port=None):
        self.port = port
        self.restores = 0

    def engaged_for(self, port):
        return self.port is not None and self.port == port

    def engage(self, host, port):
        self.port = port

    def restore(self):
        self.restores += 1
        self.port = None

    def stashed(self):
        return None


class Child:
    def __init__(self):
        self.alive = True

    def poll(self):
        return None if self.alive else 0

    def terminate(self):
        self.alive = False

    def kill(self):
        self.alive = False

    def wait(self, timeout=None):
        return 0


def _proxy_runner(kill_switch=True, system_proxy=True):
    eng = Engine(Tun(), kill_switch=kill_switch, system_proxy=system_proxy)
    sp = SysProxy(PORT if system_proxy else None)
    r = runner.Runner(eng, sp)
    # The runner reads Configs' own settings now, not Mirage's.
    r._prefs = dict(prefs.defaults(), kill_switch=kill_switch,
                    mode='system' if system_proxy else 'local')
    r.mode, r.port, r.box, r.expected_up = 'proxy', PORT, Child(), True
    r.current = 'c1'
    return r


def _tun_runner(kill_switch=True):
    eng = Engine(Tun(PORT), kill_switch=kill_switch, route='tun')
    r = runner.Runner(eng, SysProxy())
    r._prefs = dict(prefs.defaults(), kill_switch=kill_switch, mode='tun')
    r.mode, r.port, r.expected_up = 'tun', PORT, True
    r.current = 'c1'
    return r


# -- a handoff with the kill switch on ---------------------------------------

def test_held_handoff_in_proxy_mode_keeps_windows_pointed_at_the_port():
    r = _proxy_runner()
    assert r.holding()
    box = r.box
    r.disconnect(quiet=True, hold=True)
    assert not box.alive, 'the program goes'
    assert r.sysproxy.engaged_for(PORT) and r.sysproxy.restores == 0
    assert r.blocked(), 'a provider connect that fails sees it held'


def test_held_handoff_in_tun_mode_leaves_the_adapter_up():
    r = _tun_runner()
    assert r.holding()
    r.disconnect(quiet=True, hold=True)
    assert r.engine.tun.stops == 0 and r.engine.tun.engaged_for(PORT)


def test_handoff_without_the_kill_switch_puts_everything_back():
    r = _proxy_runner(kill_switch=False)
    assert not r.holding()
    r.disconnect(quiet=True, hold=r.holding())
    assert not r.sysproxy.engaged_for(PORT) and r.sysproxy.restores == 1
    assert not r.blocked()
    t = _tun_runner(kill_switch=False)
    t.disconnect(quiet=True, hold=t.holding())
    assert t.engine.tun.stops == 1


# -- the kill switch flipped while a config is up -----------------------------

def test_kill_switch_follows_the_adapter_on_the_configs_port():
    r = _tun_runner(kill_switch=False)
    assert r.set_kill_switch(True) and r.engine.tun.hold
    assert r.set_kill_switch(False) and not r.engine.tun.hold


def test_kill_switch_leaves_an_adapter_on_another_port_alone():
    r = _tun_runner()
    r.engine.tun.port = ENGINE_PORT
    r.engine.tun.hold = True
    assert not r.set_kill_switch(False)
    assert r.engine.tun.hold, 'the provider adapter is main.py\'s to change'


class Owner:
    def __init__(self, eng, sp):
        self._engine = eng
        self._sysproxy = sp
        self._busy = False


def _section(r):
    s = api.Section(Owner(r.engine, r.sysproxy))
    s.runner = r
    return s


def test_turning_the_kill_switch_off_while_held_lets_go():
    r = _proxy_runner()
    r.box.alive = False           # the program went; Windows still points here
    r.owns_hold = True            # as watch_drop marks it
    assert r.blocked()
    s = _section(r)
    r._prefs['kill_switch'] = False
    assert s.kill_switch_changed(False)
    assert not r.sysproxy.engaged_for(PORT)
    assert not r.blocked()


def test_configs_own_kill_switch_turned_off_while_held_lets_go():
    r = _proxy_runner()
    r.box.alive = False           # held: nothing active, Windows still points here
    r.owns_hold = True
    s = _section(r)
    sent = []
    s.owner._emit = lambda name, payload=None: sent.append(name)
    s.owner._holding = True
    r._prefs['kill_switch'] = False
    assert s._apply_live(['kill_switch'], {'kill_switch': False}) is True
    assert not r.sysproxy.engaged_for(PORT) and not s.owner._holding
    assert sent == ['Disconnected']


def test_a_hold_this_section_did_not_raise_is_not_let_go():
    r = _proxy_runner()
    r.box.alive = False
    r.owns_hold = False           # Mirage's, say
    s = _section(r)
    r._prefs['kill_switch'] = False
    assert not s.kill_switch_changed(False)
    assert r.sysproxy.engaged_for(PORT)


def test_turning_the_kill_switch_on_or_off_while_up_lets_nothing_go():
    r = _proxy_runner()
    s = _section(r)
    assert not s.kill_switch_changed(True)
    r._prefs['kill_switch'] = False
    assert not s.kill_switch_changed(False)
    assert r.alive() and r.sysproxy.engaged_for(PORT)


# -- the system proxy and proxy-versus-adapter flipped while up ---------------

def test_system_proxy_turned_on_or_off_is_applied_in_place():
    r = _proxy_runner(system_proxy=False)
    assert not r.sysproxy.engaged_for(PORT)
    r._prefs['mode'] = 'system'
    assert r.reroute() is True and r.sysproxy.engaged_for(PORT)
    r._prefs['mode'] = 'local'
    assert r.reroute() is True and not r.sysproxy.engaged_for(PORT)


def test_a_move_between_proxy_and_adapter_connects_the_same_config_again():
    r = _proxy_runner()
    r._prefs['mode'] = 'tun'
    assert r.reroute() == 'reconnect'
    s = _section(r)
    asked = []
    s.do_connect = lambda p: asked.append(p)
    assert s.reroute() == 'reconnect'
    assert asked == [{'id': 'c1', 'auto': False}]


def test_reroute_with_nothing_up_does_nothing():
    r = _proxy_runner()
    r.disconnect(quiet=True)
    assert r.reroute() is False
    s = _section(r)
    s.do_connect = lambda p: (_ for _ in ()).throw(AssertionError('connected'))
    assert s.reroute() is None


# -- Configs' own settings, apart from Mirage's ------------------------------

def test_mirages_switches_do_not_steer_a_config_connection():
    r = _proxy_runner(kill_switch=True)
    r.engine.kill_switch = False          # Mirage's own switch
    r.engine.set_system_proxy = False
    assert r.holding(), "Configs' kill switch is its own"
    assert r.points_windows()


def test_settings_start_as_mirages_once():
    eng = Engine(Tun(), kill_switch=False, system_proxy=True, route='tun')
    eng.split = {'mode': 'bypass', 'iran': True, 'apps': ['chrome.exe'], 'domains': []}
    got = prefs.from_mirage(eng)
    assert got['mode'] == 'tun' and got['kill_switch'] is False
    # A file of its own: the sections above have seeded the shared one.
    store.PATH = os.path.join(tempfile.mkdtemp(), 'configs.json')
    assert got['split_mode'] == 'bypass' and got['split_apps'] == ['chrome.exe']
    assert store.seed_settings(got) is True
    assert store.seed_settings({'mode': 'local'}) is False, 'only once'
    assert store.settings()['mode'] == 'tun'
    store.set_settings({'mode': 'local'})
    assert store.settings()['mode'] == 'local'


def test_a_setting_out_of_range_is_refused():
    for key, bad in (('mode', 'vpn'), ('split_apps', 'bad|name'),
                     ('split_domains', 'exa mple..com')):
        try:
            store.set_settings({key: bad})
        except ValueError:
            continue
        raise AssertionError(f'{key}={bad!r} was taken')


if __name__ == '__main__':
    for name, fn in list(globals().items()):
        if name.startswith('test_') and callable(fn):
            fn()
            print('ok', name)

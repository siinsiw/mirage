#!/usr/bin/env python3
"""Share on Wi-Fi: who the worker listens for, who it lets in, and the
firewall rule that lets a phone reach it.

    python app/share-test.py

Nothing here touches the firewall or the network. The PowerShell that adds
and removes the rule is built as text and read, never run - an elevated
change to somebody's firewall is not a thing a test should be able to do.
"""
import importlib.util
import json
import os
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import engine                                                # noqa: E402
import paths                                                 # noqa: E402


def load_worker():
    path = os.path.join(os.path.dirname(HERE), 'core', 'ovpn-proxy.py')
    spec = importlib.util.spec_from_file_location('ovpn_proxy_share', path)
    px = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(px)
    return px


def listen_of(argv):
    return argv[argv.index('--listen') + 1]


def test_worker_listens_on_the_loopback_unless_sharing():
    plain = paths.worker_argv('1.2.3.4', 'h.example', 8877, 'auth.txt')
    assert listen_of(plain) == '127.0.0.1', plain
    shared = paths.worker_argv('1.2.3.4', 'h.example', 8877, 'auth.txt',
                               listen=paths.LAN_HOST)
    assert listen_of(shared) == '0.0.0.0', shared
    # A tunnel worker takes the same flag.
    t = paths.worker_argv(None, None, 8877, 'a', tunnel='127.0.0.1:1080',
                          listen=paths.LAN_HOST)
    assert listen_of(t) == '0.0.0.0' and '--tunnel' in t, t
    # Only the address differs; nothing else about the command line moved.
    a = [x for x in plain if x != '127.0.0.1']
    b = [x for x in shared if x != '0.0.0.0']
    assert a == b


def test_engine_picks_the_address_from_the_setting():
    off = engine.Engine(None, port=8877)
    on = engine.Engine(None, port=8877, lan_share=True)
    assert off.lan_share is False and off.listen_host() == '127.0.0.1'
    assert on.lan_share is True and on.listen_host() == '0.0.0.0'


def test_engine_remembers_the_choice_when_nothing_is_up():
    e = engine.Engine(None, port=8877)
    e.running = lambda: None
    got = e.set_lan_share(True)
    assert got == {'applied': 'next', 'moved': False}, got
    assert e.lan_share is True and e.listen_host() == '0.0.0.0'
    assert e.set_lan_share(False)['applied'] == 'next'
    assert e.lan_share is False


def test_engine_does_not_restart_a_worker_already_right():
    e = engine.Engine(None, port=8877, lan_share=True)
    e.running = lambda: {'pid': 1, 'ip': '1.2.3.4', 'name': 'h',
                         'host': '0.0.0.0', 'port': '8877'}
    e.disconnect = lambda quiet=False: (_ for _ in ()).throw(
        AssertionError('restarted a worker that was already shared'))
    got = e.set_lan_share(True)
    assert got == {'applied': 'now', 'moved': False}, got


def test_worker_accepts_only_its_own_subnets():
    px = load_worker()
    import ipaddress
    # This PC on a home Wi-Fi and an office LAN.
    nets = (ipaddress.ip_network('192.168.1.0/24'),
            ipaddress.ip_network('10.20.0.0/16'))
    for ok in ('127.0.0.1', '127.5.5.5', '192.168.1.20', '192.168.1.254',
               '10.20.3.4', '::ffff:192.168.1.20'):
        assert px.client_allowed(ok, nets), ok
    # Private, but not a network this PC is on; CGNAT and link-local are no
    # longer anyone's LAN; public is never.
    for bad in ('192.168.2.20', '10.21.0.1', '10.0.0.7', '172.16.0.1',
                '100.64.0.1', '100.127.255.254', '169.254.10.10',
                '8.8.8.8', '1.1.1.1', '::ffff:8.8.8.8', '2001:db8::1',
                '::1', '', 'not an address', None):
        assert not px.client_allowed(bad, nets), bad
    # No adapters read: only the loopback gets in.
    assert px.client_allowed('127.0.0.1', ())
    assert not px.client_allowed('192.168.1.20', ())


def test_only_private_subnets_count_as_local():
    px = load_worker()
    real = px._windows_subnets if os.name == 'nt' else px._posix_subnets
    fake = [('192.168.1.20', 24), ('100.80.1.1', 10), ('169.254.3.4', 16),
            ('8.8.4.4', 24), ('10.0.0.5', 0), ('172.20.1.2', 16),
            ('127.0.0.1', 8)]
    try:
        if os.name == 'nt':
            px._windows_subnets = lambda: fake
        else:
            px._posix_subnets = lambda: fake
        got = {str(n) for n in px.local_subnets(fresh=True)}
    finally:
        if os.name == 'nt':
            px._windows_subnets = real
        else:
            px._posix_subnets = real
        px._SUBNETS.update(at=0.0, nets=())
    assert got == {'192.168.1.0/24', '172.20.0.0/16'}, got


def test_this_machine_really_has_subnets():
    # The real enumeration, on the machine running the test. A ctypes struct
    # that misreads the address would leave this empty, and every phone
    # would be refused with sharing switched on.
    px = load_worker()
    raw = px.local_subnets(raw=True)
    import ipaddress
    usable = [a for a, _ in raw
              if not ipaddress.ip_address(a).is_loopback]
    if not usable:
        print('  (skipped: no IPv4 network adapter here)')
        return
    for addr, prefix in raw:
        ipaddress.ip_address(addr)
        assert 0 <= prefix <= 32, (addr, prefix)
    private = [a for a in usable
               if any(ipaddress.ip_address(a) in n for n in px.LAN_PRIVATE)]
    if private:
        nets = px.local_subnets(fresh=True)
        assert nets, raw
        assert any(ipaddress.ip_address(private[0]) in n for n in nets)
        assert px.client_allowed(private[0])
    assert set(usable) <= px.own_addresses()


def test_a_shared_client_cannot_be_taken_to_this_machine():
    px = load_worker()
    own = {'192.168.1.20'}
    for host in ('127.0.0.1', '[::1]', 'localhost', 'LOCALHOST.', 'x.localhost',
                 '0.0.0.0', '169.254.169.254', '192.168.1.20', '224.0.0.1',
                 '::ffff:127.0.0.1', ''):
        assert px.target_is_this_machine(host, own=own), host
    for host in ('example.com', '8.8.8.8', '192.168.1.1', '10.0.0.1'):
        assert not px.target_is_this_machine(host, own=own), host


def test_the_share_password_is_asked_for():
    px = load_worker()
    import base64 as b64
    px.LAN_PASSWORD = 'correct horse'
    try:
        def head(user, pw):
            token = b64.b64encode(f'{user}:{pw}'.encode()).decode()
            return (b'CONNECT example.com:443 HTTP/1.1\r\nHost: example.com\r\n'
                    b'Proxy-Authorization: Basic ' + token.encode())
        assert px.lan_password_ok(px.basic_password(head('phone', 'correct horse')))
        # Any user name; only the password counts.
        assert px.lan_password_ok(px.basic_password(head('', 'correct horse')))
        assert not px.lan_password_ok(px.basic_password(head('phone', 'wrong')))
        assert not px.lan_password_ok(px.basic_password(
            b'CONNECT example.com:443 HTTP/1.1\r\nHost: example.com'))
        assert not px.lan_password_ok(None)
        px.LAN_PASSWORD = None
        # No password set: nothing matches, not even an empty one.
        assert not px.lan_password_ok('')
    finally:
        px.LAN_PASSWORD = None


def test_socks5_from_the_lan_needs_the_password():
    px = load_worker()
    import socket as so
    import struct as st

    def run(greeting, password):
        a, b = so.socketpair()
        try:
            req = (b'\x05\x01\x00\x03' + bytes([11]) + b'example.com'
                   + st.pack('!H', 443))
            a.sendall(greeting(password) + req)
            try:
                got = px.socks_target(b, need_password=True)
            except PermissionError:
                got = None
            a.settimeout(0.3)
            said = b''
            while True:
                try:
                    chunk = a.recv(64)
                except OSError:
                    break
                if not chunk:
                    break
                said += chunk
            return got, said
        finally:
            a.close()
            b.close()

    def with_pw(pw):
        p = pw.encode()
        return b'\x05\x01\x02' + b'\x01\x05phone' + bytes([len(p)]) + p

    px.LAN_PASSWORD = 'correct horse'
    try:
        got, said = run(with_pw, 'correct horse')
        assert got == ('example.com', 443) and said.startswith(b'\x05\x02\x01\x00')
        got, said = run(with_pw, 'nope')
        assert got is None and said == b'\x05\x02\x01\x01'
        # A client offering no authentication is told none is acceptable.
        got, said = run(lambda _: b'\x05\x01\x00', '')
        assert got is None and said == b'\x05\xff'
    finally:
        px.LAN_PASSWORD = None


def test_worker_will_not_share_without_a_password():
    px = load_worker()
    old = os.environ.pop(px.LAN_PASSWORD_ENV, None)
    try:
        for value in (None, 'short'):
            if value:
                os.environ[px.LAN_PASSWORD_ENV] = value
            try:
                px.serve('0.0.0.0', 1, None, True)
            except SystemExit:
                pass
            else:
                raise AssertionError(f'served on the LAN with {value!r}')
    finally:
        os.environ.pop(px.LAN_PASSWORD_ENV, None)
        if old is not None:
            os.environ[px.LAN_PASSWORD_ENV] = old


def test_engine_refuses_to_share_without_a_password():
    assert not engine.lan_password_ok(None)
    # The phone apps' rule exactly: 8-128 characters of printable ASCII,
    # 0x20-0x7E. Window and worker agree because they are the same function.
    px = load_worker()
    assert engine.lan_password_ok is not None
    for bad in (None, '', '1234567', 'x' * 129, '12345678\n', '1234\t5678',
                'رمزعبور12345', 'pässwörd1', '12345678\x7f', 12345678):
        assert not engine.lan_password_ok(bad), bad
        assert not px.lan_password_valid(bad), bad
    for good in ('12345678', 'correct horse battery', ' 12345678 ',
                 'x' * 128, '~!@#$%^&*()_+{}|:"<>?`-=[]\\;\',./'):
        assert engine.lan_password_ok(good), good
        assert px.lan_password_valid(good), good
    e = engine.Engine(None, port=8877, lan_share=True)
    try:
        e._spawn('1.2.3.4', 'h.example')
    except RuntimeError as err:
        assert str(err).startswith('share-needs-password'), err
    else:
        raise AssertionError('a worker was started to share with no password')


def test_worker_knows_a_loopback_listen_from_a_shared_one():
    px = load_worker()
    assert px.is_loopback_host('127.0.0.1')
    assert px.is_loopback_host('localhost')
    assert not px.is_loopback_host('0.0.0.0')
    assert not px.is_loopback_host('192.168.1.5')


def test_worker_keeps_a_list_of_who_it_served():
    px = load_worker()
    with tempfile.TemporaryDirectory() as d:
        px.STATE_PATH = os.path.join(d, 'proxy.state')
        px.CLIENTS.clear()
        assert px.read_clients(8877) == []
        px.note_client(8877, '192.168.1.20')
        px.note_client(8877, '192.168.1.21')
        px.note_client(8877, '192.168.1.20')
        assert px.read_clients(8877) == ['192.168.1.20', '192.168.1.21']
        # Old sightings stop counting.
        with open(px.clients_path(8877), encoding='utf-8') as f:
            data = json.load(f)
        data['seen']['192.168.1.21'] -= 3600
        with open(px.clients_path(8877), 'w', encoding='utf-8') as f:
            json.dump(data, f)
        assert px.read_clients(8877) == ['192.168.1.20']
        px.clear_clients(8877)
        assert px.read_clients(8877) == []
        px.CLIENTS.clear()


def test_a_shared_client_cannot_reach_this_machine_by_going_around():
    px = load_worker()
    px._CALLER.lan = True
    try:
        for host in ('127.0.0.1', 'localhost', '0.0.0.0'):
            try:
                px.straight_to(host, 9)
            except OSError as e:
                assert 'this machine' in str(e), (host, e)
            else:
                raise AssertionError(f'{host} was reachable by a LAN client')
    finally:
        px._CALLER.lan = False


def test_firewall_rule_is_narrow_and_named_for_the_port():
    assert engine.firewall_rule_name(8877) == 'Relay LAN share (8877)'
    add = engine.firewall_script('add', 8877)
    assert "'Relay LAN share (8877)'" in add
    assert 'New-NetFirewallRule' in add
    for want in ('-Direction Inbound', '-Action Allow', '-Protocol TCP',
                 '-LocalPort 8877', '-RemoteAddress LocalSubnet',
                 '-Profile Private'):
        assert want in add, want
    # Never wider than that.
    for never in ('Any', 'Public', 'Domain', '0.0.0.0', 'Disable',
                  'Set-NetFirewallProfile', 'netsh', '*'):
        assert never not in add, never
    # Replaced, not stacked: the old rule of that name goes first.
    assert add.index('Remove-NetFirewallRule') < add.index('New-NetFirewallRule')
    gone = engine.firewall_script('remove', 8877)
    assert "-DisplayName 'Relay LAN share (8877)'" in gone
    assert 'Remove-NetFirewallRule' in gone and 'New-NetFirewallRule' not in gone
    assert '*' not in gone
    # Another port is another rule.
    assert "'Relay LAN share (9050)'" in engine.firewall_script('add', 9050)
    try:
        engine.firewall_script('flush', 8877)
    except ValueError:
        pass
    else:
        raise AssertionError('an unknown action was accepted')


def test_firewall_verdict():
    wifi = {'ip': '192.168.1.20', 'alias': 'Wi-Fi', 'category': 'Private'}
    pub = {'ip': '192.168.1.20', 'alias': 'Wi-Fi', 'category': 'Public'}
    tun = {'ip': '172.19.0.1', 'alias': 'RelayTun', 'category': 'Private'}
    assert engine.firewall_verdict({'rule': True, 'adapters': [wifi]}) == 'ok'
    assert engine.firewall_verdict({'rule': False, 'adapters': [wifi]}) == 'missing'
    # Public beats missing: the rule could not help there.
    assert engine.firewall_verdict(
        {'rule': False, 'adapters': [pub]}) == 'public-network'
    assert engine.firewall_verdict(
        {'rule': True, 'adapters': [pub]}) == 'public-network'
    # One adapter's single result arrives as an object, not a list.
    assert engine.firewall_verdict({'rule': True, 'adapters': wifi}) == 'ok'
    # A Public virtual adapter does not make the Wi-Fi Public, and a lone
    # virtual adapter is not a network to share on.
    pub_tun = dict(tun, category='Public')
    assert engine.firewall_verdict(
        {'rule': True, 'adapters': [wifi, pub_tun]}) == 'ok'
    assert engine.firewall_verdict({'rule': True, 'adapters': [tun]}) == 'unknown'
    assert engine.firewall_verdict(None) == 'unknown'
    assert engine.firewall_verdict({'rule': True, 'adapters': []}) == 'unknown'


def test_addresses_offered_are_real_lan_ones():
    rows = [
        {'ip': '127.0.0.1', 'alias': 'Loopback', 'category': ''},
        {'ip': '169.254.3.4', 'alias': 'Ethernet 2', 'category': ''},
        {'ip': '172.19.0.1', 'alias': 'RelayTun', 'category': 'Private'},
        {'ip': '172.28.64.1', 'alias': 'vEthernet (WSL)', 'category': 'Private'},
        {'ip': '192.168.56.1', 'alias': 'VirtualBox Host-Only', 'category': ''},
        {'ip': '100.100.1.1', 'alias': 'Tailscale', 'category': ''},
        {'ip': '100.80.1.1', 'alias': 'Ethernet 3', 'category': ''},
        {'ip': '8.8.4.4', 'alias': 'Ethernet 4', 'category': ''},
        {'ip': '10.0.0.5', 'alias': 'Ethernet', 'category': 'Private'},
        {'ip': '192.168.1.20', 'alias': 'Wi-Fi', 'category': 'Private'},
    ]
    got = [a for a, _, _ in engine.usable_lan_adapters(rows)]
    assert got == ['192.168.1.20', '10.0.0.5'], got


if __name__ == '__main__':
    for name, fn in list(globals().items()):
        if name.startswith('test_') and callable(fn):
            fn()
            print('ok', name)

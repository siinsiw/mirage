#!/usr/bin/env python3
"""Read the sing-box configuration tunnel mode would run, without running it.

    python app/tun-test.py

The adapter itself needs administrator rights and a real connection, so it
is not something a test can bring up. What can be held still is the part
that decides whether the machine survives it: that the exit and the tunnel
client are routed around the adapter rather than into it, that UDP is
refused, that DNS goes where it should, and that the script handed to an
elevated PowerShell quotes what it is given.
"""
import ipaddress
import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import tun                                                   # noqa: E402


def rule_with(rules, key):
    return [r for r in rules if key in r]


def test_exit_is_routed_around_the_adapter():
    cfg = tun.build_config(8877, keep=['62.197.149.105', '127.0.0.1:9091',
                                       '10.1.2.3', 'not an address', None])
    excluded = cfg['inbounds'][0]['route_exclude_address']
    assert '62.197.149.105/32' in excluded, excluded
    # A tunnel's local end, a private address and junk are not routes.
    assert not [x for x in excluded if x.startswith('127.')], excluded
    assert '10.1.2.3/32' not in excluded, excluded
    for net in tun.LOCAL_NETS:
        assert net in excluded, net
    # The adapter's own subnet must not be among the things routed around.
    own = ipaddress.ip_network(tun.ADDRESS, strict=False)
    assert not any(own.overlaps(ipaddress.ip_network(n)) for n in excluded)


def test_traffic_goes_to_the_worker():
    cfg = tun.build_config(9050)
    relay = [o for o in cfg['outbounds'] if o['tag'] == 'relay'][0]
    assert relay['type'] == 'socks' and relay['server'] == '127.0.0.1'
    assert relay['server_port'] == 9050
    assert cfg['route']['final'] == 'relay'
    assert cfg['route']['auto_detect_interface'] is True
    tun_in = cfg['inbounds'][0]
    assert tun_in['auto_route'] and tun_in['strict_route']
    assert tun_in['interface_name'] == tun.ADAPTER


def test_udp_refused_after_dns():
    rules = tun.build_config(8877)['route']['rules']
    dns = rules.index([r for r in rules if r.get('action') == 'hijack-dns'][0])
    udp = rules.index([r for r in rules if r.get('network') == 'udp'][0])
    assert rules[udp]['action'] == 'reject'
    assert dns < udp, 'DNS has to be answered before UDP is refused'


def test_own_and_carriers_go_straight_out():
    me = r'C:\Program Files\Relay\Relay.exe'
    gost = r'C:\Program Files\Relay\gost.exe'
    cfg = tun.build_config(8877, own=[me], carriers=[gost, None])
    rules = cfg['route']['rules']
    straight = rule_with(rules, 'process_path_regex')
    assert len(straight) == 1 and straight[0]['outbound'] == 'direct'
    patterns = straight[0]['process_path_regex']
    assert len(patterns) == 2
    # Before UDP is refused, so this app's own UDP is not.
    assert rules.index(straight[0]) < rules.index(
        [r for r in rules if r.get('network') == 'udp'][0])
    # Case-insensitive, and anchored: Relay.exe.bak is somebody else.
    rx = re.compile(patterns[0].replace('(?i)', ''), re.I)
    assert rx.match(me.lower())
    assert not rx.match(me + '.bak')
    # gost's DNS is asked directly, or it would wait on itself.
    dns_rules = cfg['dns']['rules']
    assert len(dns_rules) == 1 and dns_rules[0]['server'] == 'dns-direct'
    assert len(dns_rules[0]['process_path_regex']) == 1
    assert cfg['dns']['final'] == 'dns-remote'
    remote = [s for s in cfg['dns']['servers'] if s['tag'] == 'dns-remote'][0]
    assert remote['detour'] == 'relay'
    assert cfg['dns']['strategy'] == 'ipv4_only'


def test_no_carriers_no_dns_rule():
    cfg = tun.build_config(8877)
    assert cfg['dns']['rules'] == []
    assert not rule_with(cfg['route']['rules'], 'process_path_regex')


def test_wrapper_quotes_what_it_is_given():
    config = json.dumps(tun.build_config(8877, own=[r"C:\Users\O’Brien\x.exe"]))
    text = tun.WRAPPER.format(
        exe=tun._ps_string(r"C:\Users\O'Brien\Relay\sing-box.exe"),
        ui=99, out="'o'", err="'e'", pidf="'p'", ready="'r'", failed="'f'",
        stop="'s'", done="'d'", nic="'RelayTun'", watch=1234, startup=25,
        holdf="'h'", held="'hd'", watchf="'w'",
        allow=tun._ps_list([r"C:\Users\O'Brien\Relay\Relay.exe"]),
        config=tun._ps_string(config))
    assert r"$allow  = @('C:\Users\O''Brien\Relay\Relay.exe')" in text
    assert "O''Brien" in text
    # The configuration travels inside the (hash-checked) script, and the
    # elevated side writes it - BOM-less, admin-only, CreateNew - where an
    # ordinary user cannot swap it before sing-box reads it.
    assert 'UTF8Encoding $false' in text
    assert '[IO.FileMode]::CreateNew' in text
    assert "SetAccessRuleProtection($true, $false)" in text
    assert '$ui     = 99' in text
    # ensure_ascii: the configuration is pure ASCII on sing-box's stdin.
    assert r'O\u2019Brien' in text
    assert '$watch  = 1234' in text
    # Every brace that PowerShell needs survived str.format.
    assert text.count('{') == text.count('}')


def test_the_kill_switch_is_a_file_the_script_reads():
    # With the hold file there, the worker dying leaves the adapter up and
    # says so in `held`; a pid in `watch` is the next worker to watch.
    w = tun.WRAPPER
    hold_at = w.index('Test-Path -LiteralPath $holdf')
    assert w.index('Set-Content -LiteralPath $held') > hold_at
    assert 'Test-Path -LiteralPath $watchf' in w
    # The window going does not end the hold any more - it is only said.
    assert 'Relay went away while holding traffic' not in w
    assert 'stays held until Relay opens again' in w
    gone = w.index('$watch -eq 0 -and $uiGone')
    assert 'break' not in w[gone:w.index('if (-not $up)', gone)]
    # sing-box going by itself, with the hold on, hands the hold to the
    # firewall - and only once the adapter had been up.
    exited = w.index('if ($box.HasExited)')
    assert w.index('$up -and (Test-Path -LiteralPath $holdf)', exited) \
        < w.index('Block-RelayOutbound $allow', exited)
    # A leftover firewall hold is lifted only once the new adapter is up.
    assert w.index('Unblock-RelayOutbound') > w.index('if ($seen)')
    assert w.index('Unblock-RelayOutbound') < w.index("-Value 'up'")
    import tempfile
    with tempfile.TemporaryDirectory() as d:
        t = tun.Tun(lambda pid: False)
        t.dir = d
        assert t.set_hold(True) and os.path.exists(os.path.join(d, 'hold'))
        assert t.set_hold(True)
        t.set_hold(False)
        assert not os.path.exists(os.path.join(d, 'hold'))
        with open(os.path.join(d, 'state.json'), 'w') as f:
            json.dump({'port': 8877, 'watch': 1,
                       'fingerprint': tun.Tun.fingerprint(8877, own=['a'])}, f)
        assert t.same_config(8877, own=['a'], keep=['8.8.8.8'])
        assert not t.same_config(8877, own=['b'])
        assert not t.same_config(9000, own=['a'])
        t.rewatch(4321)
        with open(os.path.join(d, 'watch')) as f:
            assert f.read() == '4321'
        assert t.record()['watch'] == 4321


def test_the_firewall_hold_puts_back_what_it_found():
    k = tun.KILL_PS
    # What the profiles said is saved once, before anything is changed, and
    # under HKLM - not in a folder this user can write.
    assert "HKLM:\\SOFTWARE\\Relay\\KillSwitch" in k
    save = k.index('New-ItemProperty')
    assert save < k.index('-DefaultOutboundAction Block')
    assert k.index('if (-not (Test-Path -LiteralPath $RelayKillKey))') < save
    # Outbound blocked on every profile; only the reconnect's programs and
    # the local network allowed; DNS blocked even to the local network.
    assert 'Set-NetFirewallProfile -Profile Domain, Private, Public ' \
           '-DefaultOutboundAction Block' in k
    assert '-Action Allow -Program $prog' in k
    assert "'192.168.0.0/16'" in k
    assert k.count('-RemotePort 53') == 2
    # Put back: only a value the firewall knows, else NotConfigured, and
    # the rules go by their group.
    unblock = k[k.index('function Unblock-RelayOutbound'):]
    assert "-notin 'Allow', 'Block', 'NotConfigured'" in unblock
    assert 'Remove-NetFirewallRule -Group $RelayKillGroup' in unblock
    assert tun.KILL_GROUP in k and tun.KILL_KEY in k.replace('HKLM:\\', '')
    # The programs it lets out are this app and what carries a reconnect.
    allowed = [os.path.normcase(p) for p in tun.carriers_of_a_reconnect()]
    assert os.path.normcase(os.path.normpath(sys.executable)) in allowed


def test_explain():
    assert 'sing-box.exe' in tun.explain('tun-missing: x')
    assert 'administrator' in tun.explain(RuntimeError('tun-refused: no'))
    assert 'boom' in tun.explain(RuntimeError('tun-failed: boom'))
    assert tun.explain(RuntimeError('all-refused')) is None


if __name__ == '__main__':
    for name, fn in list(globals().items()):
        if name.startswith('test_') and callable(fn):
            fn()
            print('ok', name)

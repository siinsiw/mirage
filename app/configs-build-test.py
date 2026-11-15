#!/usr/bin/env python3
"""Configs: what is handed to sing-box and Xray.

    python app/configs-build-test.py

Offline. Where sing-box.exe or xray.exe is found (tunnel/, beside the app,
or on PATH - CI downloads both before the tests run) every configuration
built here is also given to it to check, so a field name either core does
not know fails here rather than on somebody's connect.
"""
import json
import os
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import paths                                                   # noqa: E402
from configs import links, prefs, singbox, xray                # noqa: E402

with open(os.path.join(HERE, '..', 'tests', 'links-fixtures.json'),
          encoding='utf-8') as f:
    FIX = json.load(f)

ALL = [links.parse_link(c['link']) for c in FIX['ok']]
SB = [c for c in ALL if links.engine_for(c) == 'sing-box']
XR = [c for c in ALL if links.engine_for(c) == 'xray']
XRAY_PORTS = {c['id']: (20000 + n, 'u', 'p') for n, c in enumerate(XR)}


def _check(exe, args, config):
    """The core's own verdict on a config, or None when it is not here."""
    if not exe:
        return None
    fd, path = tempfile.mkstemp(suffix='.json')
    try:
        with os.fdopen(fd, 'w', encoding='utf-8') as f:
            json.dump(config, f)
        done = subprocess.run([exe] + args + [path], capture_output=True,
                              text=True, timeout=60)
        return done.returncode, (done.stdout + done.stderr)[-800:]
    finally:
        os.unlink(path)


def _xray_exe():
    return getattr(paths, 'xray_exe', lambda: None)()


def test_every_fixture_becomes_an_outbound():
    for cfg in ALL:
        ob = singbox.outbound(cfg, 't', XRAY_PORTS.get(cfg['id']))
        assert ob['tag'] == 't'
        if cfg['id'] in XRAY_PORTS:
            assert ob['type'] == 'socks' and ob['server'] == '127.0.0.1'


def test_xray_configs_need_a_port():
    try:
        singbox.outbound(XR[0], 't')
    except ValueError:
        return
    raise AssertionError('an xhttp config became an outbound with no Xray')


def test_certificate_check_stays_on_until_the_person_turns_it_off():
    asked = next(c for c in SB if c.get('insecure') and c['proto'] != 'hysteria2')
    ob = singbox.outbound(asked, 't')
    assert 'insecure' not in ob['tls'], ob['tls']
    ob = singbox.outbound(dict(asked, allow_insecure=True), 't')
    assert ob['tls']['insecure'] is True
    hy2 = next(c for c in SB if c['proto'] == 'hysteria2' and c.get('insecure'))
    assert 'insecure' not in singbox.outbound(hy2, 't')['tls']


def test_reality_and_vision():
    cfg = SB[0]
    ob = singbox.outbound(cfg, 't')
    assert ob['flow'] == 'xtls-rprx-vision'
    assert ob['tls']['reality'] == {'enabled': True, 'public_key': cfg['pbk'],
                                    'short_id': cfg['sid']}
    assert ob['tls']['utls'] == {'enabled': True, 'fingerprint': 'chrome'}
    assert ob['tls']['server_name'] == 'www.microsoft.com'


def test_websocket_early_data_and_alpn():
    ws = next(c for c in SB if c['net'] == 'ws' and c['ed'])
    ob = singbox.outbound(ws, 't')
    assert ob['transport'] == {'type': 'ws', 'path': '/ws',
                               'headers': {'Host': 'cdn.example.com'},
                               'max_early_data': 2048,
                               'early_data_header_name': 'Sec-WebSocket-Protocol'}
    assert ob['tls']['alpn'] == ['http/1.1']


def test_the_connection():
    cfg = singbox.build(SB + XR, port=18877, clash_port=19090,
                        clash_secret='s', selected=SB[1]['id'],
                        xray=XRAY_PORTS)
    sel = next(o for o in cfg['outbounds'] if o['tag'] == 'proxy')
    assert sel['type'] == 'selector'
    assert sel['default'] == 'c-' + SB[1]['id']
    assert 'auto' not in sel['outbounds']
    assert cfg['inbounds'] == [{'type': 'mixed', 'tag': 'mixed-in',
                                'listen': '127.0.0.1', 'listen_port': 18877}]
    assert cfg['route']['final'] == 'proxy'
    assert cfg['experimental']['clash_api']['external_controller'] == '127.0.0.1:19090'
    # No UDP refusal anywhere: configs carry it.
    assert not any(r.get('network') == 'udp' for r in cfg['route']['rules'])


def test_auto_and_the_adapter():
    cfg = singbox.build(SB, port=18877, clash_port=19090, clash_secret='s',
                        auto=True, auto_limit=3, mode='tun',
                        own=[r'C:\Relay\Relay.exe'],
                        carriers=[r'C:\Relay\xray.exe'])
    auto = next(o for o in cfg['outbounds'] if o['tag'] == 'auto')
    assert auto['type'] == 'urltest' and len(auto['outbounds']) == 3
    assert auto['interval'] == '10m'
    sel = next(o for o in cfg['outbounds'] if o['tag'] == 'proxy')
    assert sel['default'] == 'auto' and sel['outbounds'][0] == 'auto'
    kinds = [i['type'] for i in cfg['inbounds']]
    assert kinds == ['mixed', 'tun'], kinds
    straight = next(r for r in cfg['route']['rules'] if 'process_path_regex' in r)
    assert straight['outbound'] == 'direct' and len(straight['process_path_regex']) == 2
    assert cfg['dns']['rules'][0]['server'] == 'dns-direct'
    assert cfg['route']['auto_detect_interface'] is True


def test_split_tunnelling():
    rules = {'mode': 'bypass', 'names': ['ir', 'digikala.com'],
             'nets': ['5.0.0.0/16'], 'apps': ['steam.exe']}
    cfg = singbox.build(SB[:2], port=1, clash_port=2, clash_secret='s',
                        split_rules=rules, line_resolver='192.168.1.1')
    r = cfg['route']['rules']
    assert {'action': 'sniff'} in r
    assert {'process_name': ['steam.exe'], 'action': 'route',
            'outbound': 'direct'} in r
    assert any(x.get('domain_suffix') == ['ir', 'digikala.com']
               and x['outbound'] == 'direct' for x in r)
    assert cfg['dns']['rules'][-1]['server'] == 'dns-line'
    only = singbox.build(SB[:2], port=1, clash_port=2, clash_secret='s',
                         split_rules=dict(rules, mode='only'))
    assert only['route']['final'] == 'direct'
    assert any(x.get('outbound') == 'proxy' and x.get('domain_suffix')
               for x in only['route']['rules'])


def test_the_probe():
    ports = {c['id']: 30000 + n for n, c in enumerate(SB + XR)}
    cfg = singbox.build_probe(SB + XR, ports, clash_port=19091,
                              clash_secret='s', xray=XRAY_PORTS)
    assert len(cfg['inbounds']) == len(SB + XR)
    first = cfg['route']['rules'][0]
    assert first['inbound'] == ['in-' + SB[0]['id']]
    assert first['outbound'] == 'c-' + SB[0]['id']
    assert cfg['route']['rules'][-1] == {'action': 'reject'}


def test_xray_side():
    entries = [(c, 20000 + n, 'u', 'p') for n, c in enumerate(XR)]
    cfg = xray.build(entries, fragment=True)
    assert cfg['outbounds'][0]['protocol'] == 'blackhole'
    xh = next(o for o in cfg['outbounds'] if o.get('streamSettings', {})
              .get('network') == 'xhttp')
    assert xh['streamSettings']['xhttpSettings']['mode'] == 'packet-up'
    assert xh['streamSettings']['sockopt'] == {'domainStrategy': 'UseIPv4',
                                               'dialerProxy': 'fragment'}
    assert all(i['listen'] == '127.0.0.1' and i['settings']['auth'] == 'password'
               for i in cfg['inbounds'])


def test_the_cores_accept_it():
    sb = paths.singbox_exe()
    said = []
    for cfg in (singbox.build(SB + XR, port=18877, clash_port=19090,
                              clash_secret='s', xray=XRAY_PORTS, fragment=True,
                              split_rules={'mode': 'bypass', 'names': ['ir'],
                                           'nets': [], 'apps': ['a.exe']},
                              line_resolver='192.168.1.1'),
                singbox.build(SB, port=18877, clash_port=19090, clash_secret='s',
                              auto=True, mode='tun', own=[sys.executable]),
                singbox.build_probe(SB + XR, {c['id']: 30000 + n for n, c
                                              in enumerate(SB + XR)},
                                    clash_port=19091, clash_secret='s',
                                    xray=XRAY_PORTS)):
        got = _check(sb, ['check', '-c'], cfg)
        if got is None:
            said.append('sing-box not here: not checked by it')
            break
        assert got[0] == 0, got[1]
    entries = [(c, 20000 + n, 'u', 'p') for n, c in enumerate(XR)]
    for frag in (False, True):
        got = _check(_xray_exe(), ['run', '-test', '-c'],
                     xray.build(entries, fragment=frag))
        if got is None:
            said.append('xray not here: not checked by it')
            break
        assert got[0] == 0, got[1]
    for line in said:
        print('  (' + line + ')')


# -- Configs' own settings in what is built -----------------------------------

EVERYTHING = dict(prefs.defaults(), fragment=True, fragment_style='tls',
                  remote_dns='https://dns.google/dns-query',
                  direct_dns='https://9.9.9.9/dns-query', ipv6='prefer_ipv6',
                  mux=True, mux_protocol='smux', mux_streams=4, mux_padding=True,
                  tun_stack='gvisor', tun_mtu=1500, strict_route=False,
                  auto_interval=3, auto_tolerance=150,
                  test_url='https://www.gstatic.com/generate_204')


def test_settings_reach_the_connection():
    cfg = singbox.build(SB, port=18877, clash_port=19090, clash_secret='s',
                        auto=True, mode='tun', fragment=True, opts=EVERYTHING)
    dns = {s['tag']: s for s in cfg['dns']['servers']}
    assert dns['dns-remote']['server'] == 'dns.google'
    assert dns['dns-remote']['domain_resolver'] == 'dns-direct'
    assert dns['dns-direct']['server'] == '9.9.9.9' and 'dns-local' not in dns
    assert cfg['dns']['strategy'] == 'ipv4_only', 'the adapter carries IPv4 only'
    proxy = singbox.build(SB, port=18877, clash_port=19090, clash_secret='s',
                          opts=EVERYTHING)
    assert proxy['dns']['strategy'] == 'prefer_ipv6'
    tun = next(i for i in cfg['inbounds'] if i['type'] == 'tun')
    assert tun['stack'] == 'gvisor' and tun['mtu'] == 1500 and not tun['strict_route']
    auto = next(o for o in cfg['outbounds'] if o['tag'] == singbox.AUTO)
    assert auto['interval'] == '3m' and auto['tolerance'] == 150
    assert auto['url'] == 'https://www.gstatic.com/generate_204'
    for ob in cfg['outbounds']:
        tls = ob.get('tls') or {}
        if tls.get('fragment') or tls.get('record_fragment'):
            assert tls.get('fragment') and not tls.get('record_fragment'), 'tls style only'


def test_auto_interval_stays_within_the_idle_timeout():
    # sing-box refuses to start a urltest whose interval is past 30m.
    for bad in (45, 60):
        try:
            prefs.check('auto_interval', bad)
        except ValueError:
            continue
        raise AssertionError(f'{bad} was taken')
    cfg = singbox.build(SB, port=18877, clash_port=19090, clash_secret='s', auto=True,
                        opts=dict(prefs.defaults(), auto_interval=99, auto_tolerance=0))
    auto = next(o for o in cfg['outbounds'] if o['tag'] == singbox.AUTO)
    assert auto['interval'] == '30m' and auto['tolerance'] >= 1


def test_mux_only_where_it_can_go():
    for cfg in SB:
        ob = singbox.outbound(cfg, 't', None, False, EVERYTHING)
        if cfg.get('flow') or cfg['proto'] in ('hysteria2', 'tuic'):
            assert 'multiplex' not in ob, cfg['proto']
        else:
            assert ob['multiplex']['protocol'] == 'smux', cfg['proto']


def test_xray_fragment_takes_the_tls_configs():
    opts = dict(prefs.defaults(), fragment=True, fragment_engine='xray',
                fragment_packets='1-3', fragment_length='20-60')
    moved = [c for c in SB if prefs.engine_for(c, opts) == 'xray']
    assert moved and all(c['proto'] in ('vless', 'vmess', 'trojan')
                         and c.get('security') in ('tls', 'reality') for c in moved)
    entries = [(c, 20000 + n, 'u', 'p') for n, c in enumerate(moved)]
    built = xray.build(entries, fragment=True, opts=opts)
    frag = next(o for o in built['outbounds'] if o['tag'] == 'fragment')
    assert frag['settings']['fragment'] == {'packets': '1-3', 'length': '20-60',
                                            'interval': '10-20'}
    sb = paths.singbox_exe()
    got = _check(_xray_exe(), ['run', '-test', '-c'], built)
    assert got is None or got[0] == 0, got and got[1]
    got = _check(sb, ['check', '-c'], singbox.build(
        SB, port=18877, clash_port=19090, clash_secret='s', fragment=True,
        xray={c['id']: (20000 + n, 'u', 'p') for n, c in enumerate(moved)},
        opts=opts))
    assert got is None or got[0] == 0, got and got[1]


if __name__ == '__main__':
    for name, fn in list(globals().items()):
        if name.startswith('test_') and callable(fn):
            fn()
            print('ok', name)

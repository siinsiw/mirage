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
from configs import links, singbox, xray                       # noqa: E402

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


if __name__ == '__main__':
    for name, fn in list(globals().items()):
        if name.startswith('test_') and callable(fn):
            fn()
            print('ok', name)

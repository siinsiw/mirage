#!/usr/bin/env python3
"""The server's key, read the way the window reads it, and the gost config
written from it.

    python app/key-test.py

The key is made by tunnel/install-server.sh and read here and in the phone's
Go core (mobile/core/relaykey.go). Both readers refuse the same keys; this
holds the desktop's half to that, and checks that a key with Shadowsocks in
it becomes a gost config that dials the server's address on the right port.
"""
import base64
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import tunnel                                                # noqa: E402


def key(body, pad=False):
    raw = base64.urlsafe_b64encode(json.dumps(body).encode()).decode()
    return 'relay://' + (raw if pad else raw.rstrip('='))


PIN = 'ab' * 32
SS = {'method': 'chacha20-ietf-poly1305', 'password': 's3:x+/=', 'port': 8388,
      'exitPort': 8389}


def test_reads_a_full_key():
    k = tunnel.parse_key(key({'v': 1, 'host': '203.0.113.7', 'pin': PIN.upper(),
                              'pw': 'p1', 'apw': 'a2', 'ss': SS}))
    assert k['host'] == '203.0.113.7' and k['pin'] == PIN
    assert k['password'] == 'p1' and k['api_password'] == 'a2'
    assert k['ss'] == SS


def test_reads_a_wrapped_padded_key():
    text = key({'v': 1, 'host': 'relay.example.com', 'pin': '', 'pw': 'p',
                'apw': 'a'}, pad=True)
    wrapped = text[:20] + '\n   ' + text[20:]
    k = tunnel.parse_key(wrapped)
    assert k['host'] == 'relay.example.com' and k['ss'] is None
    # No port in the key is every key made before there could be one.
    assert k['port'] == 443


def test_reads_a_port():
    k = tunnel.parse_key(key({'v': 1, 'host': '203.0.113.7', 'pin': PIN,
                              'pw': 'p', 'apw': 'a', 'port': 8443}))
    assert k['port'] == 8443


def test_refuses_what_the_phone_refuses():
    bad = {
        'empty': '  ',
        'not base64': 'relay://!!!',
        'not json': 'relay://' + base64.urlsafe_b64encode(b'hello').decode(),
        'newer': key({'v': 2, 'host': 'h', 'pw': 'p', 'apw': 'a'}),
        'no host': key({'v': 1, 'pw': 'p', 'apw': 'a'}),
        'no pw': key({'v': 1, 'host': 'h', 'apw': 'a'}),
        'bad host': key({'v': 1, 'host': 'h/../x', 'pw': 'p', 'apw': 'a'}),
        'bad pin': key({'v': 1, 'host': 'h', 'pin': 'xyz', 'pw': 'p', 'apw': 'a'}),
        'bad method': key({'v': 1, 'host': 'h', 'pw': 'p', 'apw': 'a',
                           'ss': {**SS, 'method': 'rc4-md5'}}),
        'bad port': key({'v': 1, 'host': 'h', 'pw': 'p', 'apw': 'a',
                         'ss': {**SS, 'port': 0}}),
        'no ss secret': key({'v': 1, 'host': 'h', 'pw': 'p', 'apw': 'a',
                             'ss': {**SS, 'password': ''}}),
        'port 0': key({'v': 1, 'host': 'h', 'pw': 'p', 'apw': 'a', 'port': 0}),
        'port too big': key({'v': 1, 'host': 'h', 'pw': 'p', 'apw': 'a',
                             'port': 65536}),
        'port as text': key({'v': 1, 'host': 'h', 'pw': 'p', 'apw': 'a',
                             'port': '8443'}),
        'port as bool': key({'v': 1, 'host': 'h', 'pw': 'p', 'apw': 'a',
                             'port': True}),
        'port fraction': key({'v': 1, 'host': 'h', 'pw': 'p', 'apw': 'a',
                              'port': 8443.5}),
    }
    for name, text in bad.items():
        try:
            tunnel.parse_key(text)
        except ValueError:
            continue
        raise AssertionError(f'{name}: accepted')


def test_ss_config_dials_the_address():
    text = tunnel.config_text('203.0.113.7', 'tunnelpw', ss=SS)
    assert 'mwss' not in text and 'edge-' not in text
    try:
        import yaml
    except ImportError:
        # Without PyYAML, the shape is checked by hand.
        assert 'addr: "203.0.113.7:8388"' in text
        assert 'addr: "203.0.113.7:8389"' in text
        assert 'password: "s3:x+/="' in text
        return
    cfg = yaml.safe_load(text)
    chains = {c['name']: c for c in cfg['chains']}
    for mode, port in (('single', 8388), ('bulk', 8388), ('multi', 8389)):
        node = chains[f'ch-{mode}']['hops'][0]['nodes'][0]
        assert node['addr'] == f'203.0.113.7:{port}', node
        assert node['connector']['type'] == 'ss'
        assert node['connector']['auth'] == {'username': SS['method'],
                                             'password': SS['password']}
        assert node['connector']['metadata'] == {'nodelay': True}
        assert node['dialer']['type'] == 'tcp'
    # The local side is unchanged: the window still talks HTTP to 127.0.0.1.
    ports = {s['name']: s['addr'] for s in cfg['services']}
    assert ports == {m: f'127.0.0.1:{p}' for m, p in tunnel.PORTS.items()}


def test_ws_config_is_unchanged():
    text = tunnel.config_text('relay.example.com', 'pw', ['1.2.3.4'])
    assert 'type: ss' not in text and 'mwss' in text
    assert 'addr: "1.2.3.4:443"' in text


def test_ws_config_dials_the_keys_port():
    # A server whose 443 belongs to something else: every WebSocket node -
    # by address or through an edge - dials the port the key named, and the
    # local side does not move.
    for edges in (None, ['1.2.3.4']):
        text = tunnel.config_text('203.0.113.7', 'pw', edges, port=8443)
        assert ':443' not in text, text
        assert text.count(':8443"\n') == len(tunnel.PORTS), text
        for p in tunnel.PORTS.values():
            assert f'"127.0.0.1:{p}"' in text


def test_refuses_keys_whose_values_could_escape_where_they_land():
    # The shared rule: a host is LDH labels or an IPv4 address, and a
    # password is 1-128 printable ASCII without ' " ` $ \ { } # or spaces.
    nasty = ("p'; rm -rf /", 'p"x', 'p`id`', 'p$HOME', 'p\\n', 'p{x}',
             'p#c', 'p q', '', 'x' * 129, 'pä', 'p\tq')
    for pw in nasty:
        for field in ('pw', 'apw'):
            body = {'v': 1, 'host': 'relay.example.com', 'pw': 'ok', 'apw': 'ok'}
            body[field] = pw
            try:
                tunnel.parse_key(key(body))
            except ValueError:
                continue
            raise AssertionError(f'{field}={pw!r}: accepted')
        try:
            tunnel.parse_key(key({'v': 1, 'host': 'h.example', 'pw': 'p',
                                  'apw': 'a', 'ss': {**SS, 'password': pw}}))
        except ValueError:
            pass
        else:
            raise AssertionError(f'ss password {pw!r}: accepted')
    for host in ('h.example;id', 'h example', '-h.example', 'h-.example',
                 'h..example', 'h.example.', '999.1.1.1', '1.2.3', "h'x",
                 'a' * 64 + '.example', '[::1]', 'h_x.example'):
        try:
            tunnel.parse_key(key({'v': 1, 'host': host, 'pw': 'p', 'apw': 'a'}))
        except ValueError:
            continue
        raise AssertionError(f'host {host!r}: accepted')
    # And what the rule allows is accepted as it is.
    ok = tunnel.parse_key(key({'v': 1, 'host': 'xn--mgbh0fb.example',
                               'pw': 'Ab3-_.~!%&*()+,/:;<=>?@[]^|',
                               'apw': 'x' * 128}))
    assert ok['password'] == 'Ab3-_.~!%&*()+,/:;<=>?@[]^|'


def test_yaml_and_shell_quoting_hold_whatever_they_are_given():
    px = tunnel.px
    # Whatever got past validation (or never met it) still cannot break out:
    # YAML scalars are JSON strings, shell words are single-quoted.
    weird = 'a"b\\c\'d}e{f#g: h\n- i'
    text = tunnel.config_text('relay.example.com', weird, ['1.2.3.4'])
    assert json.dumps(weird) in text
    try:
        import yaml
    except ImportError:
        yaml = None
    if yaml:
        cfg = yaml.safe_load(text)
        node = cfg['chains'][0]['hops'][0]['nodes'][0]
        assert node['connector']['auth']['password'] == weird
        assert node['dialer']['tls'] == {'secure': True,
                                         'serverName': 'relay.example.com'}
    assert px.shell_quote("it's") == "'it'\\''s'"
    assert px.shell_quote('$(id)`x`') == "'$(id)`x`'"


def test_every_path_verifies_the_servers_certificate():
    # By name through an edge, by name with no edges, and by address with
    # the kept certificate: secure every time, never gost's unchecked default.
    edged = tunnel.config_text('relay.example.com', 'pw', ['1.2.3.4'])
    plain = tunnel.config_text('relay.example.com', 'pw', [])
    by_ip = tunnel.config_text('203.0.113.7', 'pw', [], ca_file=r'C:\x\c.pem')
    for text in (edged, plain, by_ip):
        tls = [l for l in text.splitlines() if 'tls:' in l]
        assert len(tls) == len(tunnel.PORTS), text
        assert all('secure: true' in l for l in tls), tls
    assert all('serverName: "relay.example.com"' in l
               for l in edged.splitlines() + plain.splitlines() if 'tls:' in l)
    assert 'caFile: "C:/x/c.pem"' in by_ip


def test_exit_tls_follows_the_servers_switch():
    px = tunnel.px
    assert px.exit_tls('de-fra.prod.surfshark.com') == {
        'secure': True, 'serverName': 'de-fra.prod.surfshark.com'}
    assert px.exit_tls('de-fra.prod.surfshark.com', verify=False) == {
        'secure': False}
    # Nothing to check against: refused, never quietly unverified.
    for host in (None, '', '1.2.3.4'):
        try:
            px.exit_tls(host)
        except RuntimeError:
            pass
        else:
            raise AssertionError(f'{host!r}: sent unchecked')
    assert px.exit_tls(None, verify=False) == {'secure': False}
    t = tunnel.Tunnel('gost.exe', '.', 'h.example', 'pw', 'apw')
    sent = []
    t._api = lambda path, payload=None, method='GET', timeout=20: \
        sent.append(payload)
    t.set_exit('1.2.3.4', 'u', 'p', name='x', host='de-fra.prod.surfshark.com')
    t.verify_exit = False
    t.set_slot(1, '1.2.3.4', 'u', 'p', name='x', host='de-fra.prod.surfshark.com')
    assert sent[0]['hops'][0]['nodes'][0]['dialer']['tls'] == {
        'secure': True, 'serverName': 'de-fra.prod.surfshark.com'}
    assert sent[1]['hops'][0]['nodes'][0]['dialer']['tls'] == {'secure': False}


def test_client_uses_the_port_for_the_api_and_the_certificate():
    t = tunnel.Tunnel('gost.exe', '.', '203.0.113.7', 'pw', 'apw', port=8443)
    assert t.origin == '203.0.113.7:8443'
    assert tunnel.Tunnel('gost.exe', '.', 'h.example', 'pw', 'apw').origin == 'h.example'


if __name__ == '__main__':
    for name, fn in list(globals().items()):
        if name.startswith('test_') and callable(fn):
            fn()
            print('ok', name)

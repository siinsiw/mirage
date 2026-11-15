#!/usr/bin/env python3
"""Configs: reading links, against the fixtures both apps are held to.

    python app/configs-links-test.py

tests/links-fixtures.json is shared with the phone's Go core, which reads
the same links its own way; a line here that changes is a line there that
has to change with it.
"""
import base64
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from configs import links                                     # noqa: E402

with open(os.path.join(HERE, '..', 'tests', 'links-fixtures.json'),
          encoding='utf-8') as f:
    FIX = json.load(f)


def _computed(cfg):
    return {'engine': links.engine_for(cfg), 'warnings': links.warnings(cfg),
            'label': links.label(cfg)}


def test_fixtures_that_read():
    for case in FIX['ok']:
        cfg = links.parse_link(case['link'])
        got = dict(cfg, **_computed(cfg))
        for key, want in case['expect'].items():
            assert got.get(key) == want, (case['about'], key, got.get(key), want)


def test_fixtures_that_are_refused():
    for case in FIX['bad']:
        try:
            links.parse_link(case['link'])
        except links.Invalid as e:
            assert case['error'].lower() in str(e).lower(), (case['about'], str(e))
            continue
        raise AssertionError(f'read, and should not have been: {case["about"]}')


def test_refusals_never_repeat_the_secret():
    text = 'trojan://supersecret@bad_host!:443\nvless://x'
    configs, problems = links.parse_text(text)
    assert not configs and len(problems) == 2
    assert 'supersecret' not in json.dumps(problems)
    _, problems = links.parse_text('hunter2-my-password')
    assert problems[0]['what'] == '…', problems


def test_a_paste_of_many_with_duplicates_and_junk():
    ok = [c['link'] for c in FIX['ok'][:5]]
    text = '\n'.join(ok + ok[:2] + ['junk'])
    configs, problems = links.parse_text(text)
    assert len(configs) == 5, len(configs)
    assert len(problems) == 1 and problems[0]['why'] == 'not a link'


def test_a_base64_subscription_body():
    body = base64.b64encode('\n'.join(c['link'] for c in FIX['ok'][:3])
                            .encode()).decode()
    configs, problems = links.parse_text(body)
    assert len(configs) == 3 and not problems


def test_a_singbox_json_subscription():
    data = {'outbounds': [
        {'type': 'vless', 'tag': 'sb-vless', 'server': 'sb.example.com',
         'server_port': 443, 'uuid': 'b831381d-6324-4d53-ad4f-8cda48b30811',
         'flow': 'xtls-rprx-vision',
         'tls': {'enabled': True, 'server_name': 'www.microsoft.com',
                 'utls': {'enabled': True, 'fingerprint': 'chrome'},
                 'reality': {'enabled': True,
                             'public_key': 'jNXHt1yRo0vDuchQlIP6Z0ZvjT3KtzVI-T4E7RoLJS0',
                             'short_id': 'ab'}}},
        {'type': 'shadowsocks', 'tag': 'sb-ss', 'server': '203.0.113.2',
         'server_port': 8388, 'method': 'aes-128-gcm', 'password': 'pw'},
        {'type': 'selector', 'tag': 'proxy', 'outbounds': ['sb-vless']},
        {'type': 'direct', 'tag': 'direct'}]}
    configs, problems = links.parse_text(json.dumps(data))
    assert [c['name'] for c in configs] == ['sb-vless', 'sb-ss'], configs
    assert configs[0]['security'] == 'reality' and configs[0]['sid'] == 'ab'
    assert not problems


def test_limits():
    try:
        links.parse_text('x' * (links.MAX_TEXT + 1))
    except links.Invalid:
        pass
    else:
        raise AssertionError('a paste over the limit was read')
    one = FIX['ok'][0]['link']
    many = '\n'.join(one.replace('203.0.113.10', f'203.0.113.{i % 250 + 1}')
                     .replace(':443', f':{1000 + i}') for i in range(30))
    configs, problems = links.parse_text(many, limit=10)
    assert len(configs) == 10 and 'only the first 10' in problems[-1]['why']
    try:
        links.parse_link('vless://' + 'a' * links.MAX_LINK)
    except links.Invalid as e:
        assert 'too long' in str(e)


def test_id_ignores_the_name_and_follows_the_server():
    a = links.parse_link(FIX['ok'][0]['link'])
    b = links.parse_link(FIX['ok'][0]['link'].split('#')[0] + '#other')
    assert a['id'] == b['id'] and a['name'] != b['name']
    c = links.parse_link(FIX['ok'][0]['link'].replace(':443', ':8443'))
    assert c['id'] != a['id']


def test_the_insecure_ask_is_recorded_not_granted():
    cfg = links.parse_link(
        'trojan://pw@t.example.com:443?allowInsecure=1#t')
    assert cfg['insecure'] is True
    assert 'allow_insecure' not in cfg


if __name__ == '__main__':
    for name, fn in list(globals().items()):
        if name.startswith('test_') and callable(fn):
            fn()
            print('ok', name)

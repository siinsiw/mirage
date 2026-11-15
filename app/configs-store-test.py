#!/usr/bin/env python3
"""Configs: what is kept, subscriptions, test reasons and the meter.

    python app/configs-store-test.py

Offline: the one subscription fetched is served from this machine's own
loopback by the test itself.
"""
import base64
import http.server
import json
import os
import sys
import tempfile
import threading
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import settingsfile                                            # noqa: E402
from configs import links, probe, runner, store, subs          # noqa: E402

with open(os.path.join(HERE, '..', 'tests', 'links-fixtures.json'),
          encoding='utf-8') as f:
    FIX = json.load(f)
CONFIGS = [links.parse_link(c['link']) for c in FIX['ok']]


def _path():
    return os.path.join(tempfile.mkdtemp(), 'configs.json')


def test_add_dedupe_and_listing_has_no_secrets():
    p = _path()
    assert store.add(CONFIGS[:5], path=p) == (5, 0, 0)
    assert store.add(CONFIGS[:6], path=p) == (1, 5, 0)
    shown = json.dumps(store.listing(store.load(p)))
    for c in CONFIGS[:6]:
        for secret in (c.get('uuid'), c.get('password')):
            if secret:
                assert secret not in shown, secret
    assert 'link' not in shown


def test_sealed_on_disk_where_dpapi_is_there():
    p = _path()
    store.add(CONFIGS, path=p)
    with open(p, encoding='utf-8') as f:
        raw = f.read()
    if settingsfile.dpapi_available():
        assert '"sealed": "sealed:' in raw
        assert CONFIGS[0]['uuid'] not in raw
    else:
        print('  (DPAPI not here: sealing not checked)')
    assert len(store.load(p)['configs']) == len(CONFIGS)


def test_replace_group_keeps_what_the_person_chose():
    p = _path()
    gid = store.add_group('https://sub.example.com/abc', 'Seller', path=p)
    assert store.add_group('https://sub.example.com/abc', path=p) == gid
    store.replace_group(gid, CONFIGS[:3], path=p)
    keep = CONFIGS[1]['id']
    store.update(keep, name='mine', allow_insecure=True, path=p)
    store.set_results({keep: {'ms': 120, 'country': 'de', 'error': None}}, path=p)
    store.replace_group(gid, CONFIGS[1:4], path=p)
    state = store.load(p)
    ids = [c['id'] for c in state['configs'] if c['group'] == gid]
    assert ids == [c['id'] for c in CONFIGS[1:4]]
    kept = store.find(keep, state)
    assert kept['allow_insecure'] is True and kept['last']['ms'] == 120
    # A refreshed list brings the seller's name back; the person's rename is
    # of the config as it was, and a new list is new.
    assert store.remove_group(gid, path=p) == 3
    assert store.load(p)['configs'] == []


def test_group_order_fastest_first_failed_last():
    p = _path()
    store.add(CONFIGS[:4], path=p)
    a, b, c, d = (x['id'] for x in CONFIGS[:4])
    store.set_results({a: {'ms': None, 'error': 'timeout'},
                       b: {'ms': 300, 'error': None},
                       c: {'ms': 90, 'error': None}}, path=p)
    order = [x['id'] for x in store.group_of(a, store.load(p))]
    assert order == [c, b, d, a], order


def test_options_and_limits():
    p = _path()
    store.set_option('fragment', 1, path=p)
    assert store.load(p)['fragment'] is True
    try:
        store.set_option('evil', 1, path=p)
    except ValueError:
        pass
    else:
        raise AssertionError('an unknown option was kept')
    try:
        store.update(CONFIGS[0]['id'], path=p, host='x')
    except ValueError:
        pass
    else:
        raise AssertionError('a server address was changed through update')


def test_subscription_addresses():
    for bad in ('ftp://x.example.com/a', 'https://user:pw@x.example.com/a',
                'https://x.example.com/a b', 'https://bad_host!/a', 'javascript:1',
                'https://' + 'a' * 3000):
        try:
            subs.check_url(bad)
        except subs.Refused:
            continue
        raise AssertionError(bad)
    assert subs.check_url(' https://x.example.com/sub?t=1 ') == 'https://x.example.com/sub?t=1'


def test_subscription_headers():
    assert subs.userinfo('upload=1; download=2048; total=10737418240; expire=1767225600') == {
        'upload': 1, 'download': 2048, 'total': 10737418240, 'expire': 1767225600}
    assert subs.userinfo('nonsense') is None
    t = 'base64:' + base64.b64encode('فروشگاه \u202eX'.encode()).decode()
    assert subs.title(t) == 'فروشگاه X'


class _Redirect:
    full_url = 'https://sub.example.com/a'
    headers = {}
    unredirected_hdrs = {}

    def get_method(self):
        return 'GET'


def test_no_redirect_from_https_to_http():
    h = subs._Redirects()
    try:
        h.redirect_request(_Redirect(), None, 302, 'Found', {},
                           'http://sub.example.com/b')
    except subs.Refused as e:
        assert 'https' in str(e)
    else:
        raise AssertionError('a downgrade was followed')


def test_fetch_from_the_loopback():
    body = base64.b64encode('\n'.join(c['link'] for c in FIX['ok'][:4])
                            .encode()).decode().encode()

    class H(http.server.BaseHTTPRequestHandler):
        def do_GET(self):
            self.send_response(200)
            self.send_header('subscription-userinfo', 'upload=5; download=6; total=100')
            self.send_header('profile-title', 'Shop')
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, *a):
            pass

    srv = http.server.HTTPServer(('127.0.0.1', 0), H)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    try:
        url = f'http://127.0.0.1:{srv.server_port}/sub'
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        configs, problems, info, name = subs.fetch(url, opener=opener)
        assert len(configs) == 4 and not problems
        assert info == {'upload': 5, 'download': 6, 'total': 100} and name == 'Shop'
        assert subs.plain_http(url)
    finally:
        srv.shutdown()


def test_reasons():
    assert probe.classify('tls: failed to verify certificate: x509: certificate '
                          'signed by unknown authority') == 'cert'
    assert probe.classify('dial tcp 1.2.3.4:443: i/o timeout') == 'timeout'
    assert probe.classify('connectex: actively refused it') == 'refused'
    assert probe.classify('lookup x.example.com: no such host') == 'dns'
    assert probe.classify('EOF') == 'closed'
    fd, log = tempfile.mkstemp()
    with os.fdopen(fd, 'w') as f:
        f.write('INFO started\n'
                'ERROR [1 2ms] connection: open connection to www.cloudflare.com:443 '
                'using outbound/vless[c-abc]: dial tcp 127.0.0.1:9: refused\n')
    assert probe.reason_in_log(log, 'c-abc') == 'dial tcp 127.0.0.1:9: refused'
    assert probe.reason_in_log(log, 'c-zzz') == ''


def test_meter():
    m = runner.Meter()
    m.feed({'uploadTotal': 100, 'downloadTotal': 1000, 'connections': [
        {'id': '1', 'upload': 100, 'download': 1000,
         'metadata': {'host': 'example.com', 'processPath': r'C:\x\chrome.exe'}}]},
        now=1000.0)
    m.feed({'uploadTotal': 300, 'downloadTotal': 5000, 'connections': [
        {'id': '1', 'upload': 300, 'download': 5000,
         'metadata': {'host': 'example.com'}},
        {'id': '2', 'upload': 0, 'download': 0,
         'metadata': {'destinationIP': '1.1.1.1'}}]}, now=1002.0)
    assert m.up_rate == 100.0 and m.down_rate == 2000.0
    rows = {r['host']: r for r in m.hosts()['rows']}
    assert rows['example.com']['down'] == 5000 and rows['example.com']['hits'] == 1
    assert rows['example.com']['app'] == 'chrome.exe' and rows['example.com']['live'] == 1
    assert rows['1.1.1.1']['hits'] == 1


if __name__ == '__main__':
    for name, fn in list(globals().items()):
        if name.startswith('test_') and callable(fn):
            fn()
            print('ok', name)

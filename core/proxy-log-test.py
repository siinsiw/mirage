#!/usr/bin/env python3
"""proxy.log keeps failures, not a list of the sites they happened on.

    python core/proxy-log-test.py

Offline; writes only to a temporary folder.
"""
import importlib.util
import os
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location(
    'ovpn_proxy', os.path.join(HERE, 'ovpn-proxy.py'))
px = importlib.util.module_from_spec(spec)
spec.loader.exec_module(px)

failed = []


def check(name, got, want):
    if got == want:
        print(f'  ok    {name}')
    else:
        print(f'  FAIL  {name}\n          got  {got!r}\n          want {want!r}')
        failed.append(name)


print('names and addresses come out')
check('a CONNECT target',
      px.scrub_hosts('CONNECT www.example.org:443: timed out'),
      'CONNECT <host>:443: timed out')
check('an address',
      px.scrub_hosts('socks5 142.250.185.78:443: refused'),
      'socks5 <host>:443: refused')
check('a bracketed IPv6 address',
      px.scrub_hosts('CONNECT [2001:db8::1]:443: no route'),
      'CONNECT <host>:443: no route')
check('a URL in a request line',
      px.scrub_hosts('GET http://news.example.co.uk/a?b=c: reset'),
      'GET http://<host>/a?b=c: reset')
check('exception text without a host is left alone',
      px.scrub_hosts('ConnectionResetError: [WinError 10054] An existing '
                     'connection was forcibly closed'),
      'ConnectionResetError: [WinError 10054] An existing connection was '
      'forcibly closed')
check('a timing is not an address', px.scrub_hosts('tunnel idle 300s - closing'),
      'tunnel idle 300s - closing')

print('the file')
with tempfile.TemporaryDirectory() as d:
    px.LOG_PATH = os.path.join(d, 'proxy.log')
    with open(px.LOG_PATH, 'w', encoding='utf-8') as f:
        f.write('2026-11-01 10:00:00  FAIL  CONNECT secret.example.com:443: x\n'
                '2026-11-01 10:00:01  START 127.0.0.1:8877 -> 1.2.3.4 '
                '(de-fra.prod.surfshark.com)\n')
    px._old_log_scrubbed = False
    px.LOG_HOSTS = False
    px.log('FAIL', 'CONNECT another.example.net:443: refused')
    with open(px.LOG_PATH, encoding='utf-8') as f:
        body = f.read()
    check('an older log is scrubbed too', 'secret.example.com' in body, False)
    check('and so is a new line', 'another.example.net' in body, False)
    check('the exit line keeps its server',
          'de-fra.prod.surfshark.com' in body, True)

    px.LOG_CAP = 200
    for _ in range(10):
        px.log('WARN', 'tunnel idle 300s - closing')
    check('past the cap the file is rotated once',
          os.path.exists(px.LOG_PATH + '.1'), True)
    check('and the live file starts again small',
          os.path.getsize(px.LOG_PATH) < 400, True)

print()
print(', '.join(failed) + ' failed' if failed else 'all pass')
raise SystemExit(1 if failed else 0)

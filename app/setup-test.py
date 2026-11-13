#!/usr/bin/env python3
"""The setup after a sign-in: choosing a route, and always finishing.

    python app/setup-test.py

Offline. The proxy below is a socket on this machine that answers late, the
DoH probe is handed a fake opener, and nothing leaves 127.0.0.1. What is
checked is the part of the setup that decided whether the first run after a
sign-in could work at all:

  - a local proxy that answers slowly is still found (it used to be skipped
    after five seconds, and found on "Try again" once it was warm);
  - one failed DoH attempt is not taken for a blocked line;
  - the route is direct when DoH answers, else the proxy that carries a
    lookup, else nothing;
  - a pinning run that hits an exception still ends with 'finished', which
    is the one event the setup sheet waits for.
"""
import json
import os
import socket
import sys
import threading
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import pin                                                   # noqa: E402

failed = []


def check(name, got, want):
    if got == want:
        print(f'  ok    {name}')
    else:
        print(f'  FAIL  {name}\n          got  {got!r}\n          want {want!r}')
        failed.append(name)


def slow_proxy(delay, answer=b'HTTP/1.1 200 Connection established\r\n\r\n'):
    """A local 'proxy' that reads the CONNECT, waits, then answers. Returns
    its port; it serves one connection at a time until the test ends."""
    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.bind(('127.0.0.1', 0))
    server.listen(4)

    def serve():
        while True:
            try:
                conn, _ = server.accept()
            except OSError:
                return
            try:
                conn.recv(512)
                time.sleep(delay)
                conn.sendall(answer)
            except OSError:
                pass
            finally:
                conn.close()

    threading.Thread(target=serve, daemon=True).start()
    return server.getsockname()[1]


print('finding a proxy that is slow to answer')

port = slow_proxy(1.5)
check('a short wait calls it slow', pin.proxy_answer(port, timeout=0.5), 'slow')
check('find_port waits long enough to find it',
      pin.find_port(port, timeout=5.0), port)
check('the default wait is the script\'s twenty seconds', pin.FIND_TIMEOUT, 20.0)

auth = slow_proxy(0, b'HTTP/1.1 407 Proxy Authentication Required\r\n\r\n')
check('a proxy that wants a password is not a route',
      pin.proxy_answer(auth, timeout=2.0), 'auth')


print('asking DoH straight out')


class FakeResponse:
    def __init__(self, body):
        self.body = body

    def read(self):
        return self.body

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False


class FakeOpener:
    """Fails the first `fails` times, then answers with `body`."""

    def __init__(self, fails, body):
        self.fails, self.body, self.calls = fails, body, 0

    def open(self, request, timeout=None):
        self.calls += 1
        if self.calls <= self.fails:
            raise OSError('connection reset')
        return FakeResponse(self.body)


ANSWER = json.dumps({'Status': 0, 'Answer': [
    {'name': 'example.com', 'type': 1, 'TTL': 60, 'data': '93.184.215.14'}]}
).encode()
EMPTY = json.dumps({'Status': 0, 'Answer': []}).encode()

real_build = pin.urllib.request.build_opener
try:
    opener = FakeOpener(1, ANSWER)
    pin.urllib.request.build_opener = lambda *a: opener
    check('one dropped attempt is tried again', pin.direct_doh(timeout=1), True)
    check('and only once more', opener.calls, 2)

    opener = FakeOpener(5, ANSWER)
    pin.urllib.request.build_opener = lambda *a: opener
    check('two failures are a no', pin.direct_doh(timeout=1), False)

    opener = FakeOpener(0, EMPTY)
    pin.urllib.request.build_opener = lambda *a: opener
    check('an answer with no addresses is a no',
          pin.direct_doh(timeout=1), False)

    opener = FakeOpener(0, b'<html>blocked</html>')
    pin.urllib.request.build_opener = lambda *a: opener
    check('a block page is a no', pin.direct_doh(timeout=1), False)
finally:
    pin.urllib.request.build_opener = real_build


print('choosing the route')

real_direct, real_find = pin.direct_doh, pin.find_port
try:
    pin.direct_doh = lambda: True
    pin.find_port = lambda first=None: 10809
    check('direct when DoH answers', pin.auto_route(), ('direct', None))

    pin.direct_doh = lambda: False
    check('else the proxy that carries a lookup', pin.auto_route(),
          ('proxy', 10809))

    pin.find_port = lambda first=None: None
    check('else nothing', pin.auto_route(), (None, None))
finally:
    pin.direct_doh, pin.find_port = real_direct, real_find


print('a run always finishes')

events = []
p = pin.Pin()
p.state = 'running'
p.out = None


def boom(*args):
    raise RuntimeError('a line nobody expected')


p._work = boom
p._run('in', 'out', 'direct', 10808, 4, True, events.append)
finished = [e for e in events if e.get('phase') == 'finished']
check('an exception still ends in finished', len(finished), 1)
check('with the reason', 'a line nobody expected' in (finished[0].get('error')
                                                      or ''), True)
check('and the run is no longer running', p.state, 'done')


print()
print(', '.join(failed) + ' failed' if failed else 'all pass')
raise SystemExit(1 if failed else 0)

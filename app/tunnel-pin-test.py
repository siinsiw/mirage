#!/usr/bin/env python3
"""A server by address is trusted only once its certificate was confirmed.

    python app/tunnel-pin-test.py

Offline: the certificate the 'server' shows is handed in, nothing dials.
"""
import hashlib
import os
import ssl
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import tunnel                                                # noqa: E402

failed = []


def check(name, got, want):
    if got == want:
        print(f'  ok    {name}')
    else:
        print(f'  FAIL  {name}\n          got  {got!r}\n          want {want!r}')
        failed.append(name)


# Any well-formed certificate will do; this one is only ever hashed.
DER_A = bytes(range(64)) * 4
DER_B = bytes(reversed(range(64))) * 4
PEM_A = ssl.DER_cert_to_PEM_cert(DER_A)
PEM_B = ssl.DER_cert_to_PEM_cert(DER_B)
FP_A = hashlib.sha256(DER_A).hexdigest()

shown = {'pem': PEM_A}
real_get = ssl.get_server_certificate
ssl.get_server_certificate = lambda addr, timeout=None: shown['pem']
try:
    with tempfile.TemporaryDirectory() as d:
        t = tunnel.Tunnel(exe='gost.exe', workdir=d, domain='203.0.113.7',
                          password='p', api_password='a')
        try:
            t.ensure_cert()
            check('first contact is not trusted', 'no error', 'PinUnconfirmed')
        except tunnel.PinUnconfirmed as e:
            check('first contact is not trusted', e.fingerprint, FP_A)
            check('and the whole fingerprint is said', FP_A in str(e), True)
        check('nothing kept yet', os.path.exists(t.cert_path), False)

        shown['pem'] = PEM_B
        try:
            t.ensure_cert()
            check('a different second answer is not trusted either',
                  'no error', 'PinUnconfirmed')
        except tunnel.PinUnconfirmed:
            check('a different second answer is not trusted either', True, True)

        shown['pem'] = PEM_B
        check('the same certificate twice is kept', t.ensure_cert(), t.cert_path)
        check('and what is kept is that one',
              t.pin(), hashlib.sha256(DER_B).hexdigest())
        check('the pending note is gone', os.path.exists(t.pending_path), False)

    with tempfile.TemporaryDirectory() as d:
        shown['pem'] = PEM_A
        t = tunnel.Tunnel(exe='gost.exe', workdir=d, domain='203.0.113.7',
                          password='p', api_password='a', expected_pin=FP_A)
        check('with the key\'s pin the first contact is enough',
              t.ensure_cert(), t.cert_path)
        os.remove(t.cert_path)
        shown['pem'] = PEM_B
        try:
            t.ensure_cert()
            check('and a different certificate is refused', 'no error', 'PinChanged')
        except tunnel.PinUnconfirmed:
            check('and a different certificate is refused', 'unconfirmed', 'PinChanged')
        except tunnel.PinChanged:
            check('and a different certificate is refused', True, True)
finally:
    ssl.get_server_certificate = real_get

print()
print(', '.join(failed) + ' failed' if failed else 'all pass')
raise SystemExit(1 if failed else 0)

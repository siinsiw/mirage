#!/usr/bin/env python3
"""A Windows account can still read and update its private roster."""

import builtins
import json
import os
import subprocess
import tempfile
from unittest import mock
from pathlib import Path

import accounts
import windscribe


def test_locked_roster():
    if os.name != 'nt':
        print('skip Windows ACL test')
        return

    with tempfile.TemporaryDirectory() as tmp:
        folder = Path(tmp) / 'Rubika Stock' / 'AppData' / 'Local' / 'Mirage' / '.state'
        folder.mkdir(parents=True)
        store = folder / 'accounts.json'
        store.write_text(json.dumps({
            'accounts': [{'id': 'older', 'provider': 'surfshark',
                          'label': 'Older', 'username': 'older'}],
            'active': {},
        }), encoding='utf-8')

        old_store = accounts.STORE
        old_username = os.environ.get('USERNAME')
        accounts.STORE = str(store)
        # The environment name can be stale or different from the token SID.
        os.environ['USERNAME'] = 'SYSTEM'
        try:
            result = subprocess.run(
                ['icacls', str(store), '/inheritance:r',
                 '/grant:r', 'SYSTEM:F'],
                capture_output=True)
            assert result.returncode == 0, result.stderr
            try:
                store.read_text(encoding='utf-8')
            except PermissionError:
                # A normal account must recover from the damaged ACL.
                state = accounts.load()
            else:
                # Hosted runners may have an elevated token that can read
                # the file despite its broken user ACL. Exercise the same
                # recovery path on those runners without relying on their
                # effective permissions.
                original_open = builtins.open
                denied = False

                def deny_first_read(file, *args, **kwargs):
                    nonlocal denied
                    if not denied and os.fspath(file) == str(store):
                        denied = True
                        raise PermissionError(13, 'Permission denied', str(store))
                    return original_open(file, *args, **kwargs)

                with mock.patch('builtins.open', side_effect=deny_first_read):
                    state = accounts.load()
                assert denied

            assert state['accounts'][0]['id'] == 'older'
            accounts.put('surfshark', 'Newer', 'newer')
            assert {a['id'] for a in accounts.load()['accounts']} >= {'older'}
            assert {a['username'] for a in accounts.load()['accounts']} == {
                'older', 'newer'}
            assert windscribe._lock_down(str(store))
            accounts.put('surfshark', 'Newest', 'newest')
            assert len(accounts.load()['accounts']) == 3
        finally:
            subprocess.run(['icacls', str(store), '/reset'],
                           capture_output=True)
            accounts.STORE = old_store
            if old_username is None:
                os.environ.pop('USERNAME', None)
            else:
                os.environ['USERNAME'] = old_username


def _with_settings_dir(fn):
    import settingsfile
    with tempfile.TemporaryDirectory() as tmp:
        old = settingsfile.SETTINGS
        settingsfile.SETTINGS = os.path.join(tmp, 'settings.json')
        try:
            fn(settingsfile, settingsfile.SETTINGS)
        finally:
            settingsfile.SETTINGS = old
            for name in os.listdir(tmp):
                subprocess.run(['icacls', os.path.join(tmp, name), '/reset'],
                               capture_output=True) if os.name == 'nt' else None


def test_settings_are_sealed_and_locked():
    def run(sf, path):
        # An older copy's file: secrets in the clear, no ACL of its own.
        with open(path, 'w', encoding='utf-8') as f:
            json.dump({'port': 8877, 'tunnelPassword': 'tp-secret',
                       'tunnelApiPassword': 'api-secret',
                       'lanSharePassword': 'share-secret',
                       'tunnelSs': {'method': 'aes-128-gcm',
                                    'password': 'ss-secret', 'port': 1}}, f)
        got = sf.load_settings()
        assert got['tunnelPassword'] == 'tp-secret'
        assert got['tunnelSs']['password'] == 'ss-secret'
        assert got['lanSharePassword'] == 'share-secret'
        if not sf.dpapi_available():
            print('  (DPAPI not here: sealing not checked)')
            return
        # Read once, and both the file and its backup are sealed already -
        # the plaintext original is not kept around as settings.json.bak.
        for p in (path, path + '.bak'):
            with open(p, encoding='utf-8') as f:
                text = f.read()
            for secret in ('tp-secret', 'api-secret', 'share-secret',
                           'ss-secret'):
                assert secret not in text, (p, secret)
            assert json.loads(text)['tunnelPassword'].startswith('sealed:')
        if os.name == 'nt':
            acl = subprocess.run(['icacls', path], capture_output=True,
                                 text=True).stdout
            # No inherited entries: the file is this user's, SYSTEM's and
            # Administrators' only.
            assert '(I)' not in acl, acl
        assert sf.load_settings() == got
    _with_settings_dir(run)


def test_settings_survive_threads_and_corruption():
    import threading as th

    def run(sf, path):
        shared = {'n': 0}
        errors = []

        def churn(k):
            try:
                for i in range(8):
                    sf.update_settings(shared, lambda st: st.__setitem__(
                        f'key{k}', i))
                    shared[f'loose{k}-{i % 3}'] = i      # unlocked writer too
                    sf.save_settings(shared)
            except Exception as e:                    # noqa: BLE001
                errors.append(e)
        threads = [th.Thread(target=churn, args=(k,)) for k in range(6)]
        for t in threads:
            t.start()
        for t in threads:
            t.join()
        assert not errors, errors
        got = sf.load_settings()
        assert all(got[f"key{k}"] == 7 for k in range(6)), got
        # No temporary files left behind.
        leftovers = [n for n in os.listdir(os.path.dirname(path))
                     if n.endswith('.tmp')]
        assert not leftovers, leftovers

        # A file that no longer parses: the backup is read, and the broken
        # one is kept aside rather than overwritten or silently emptied.
        with open(path, 'w', encoding='utf-8') as f:
            f.write('{"port": 88')
        back = sf.load_settings()
        assert back == got, back
        aside = [n for n in os.listdir(os.path.dirname(path))
                 if '.corrupt-' in n]
        assert len(aside) == 1, aside
        # And with no backup either, an empty start - not an exception.
        os.remove(path + '.bak')
        with open(path, 'w', encoding='utf-8') as f:
            f.write('[]')
        assert sf.load_settings() == {}
    _with_settings_dir(run)


if __name__ == '__main__':
    for name, fn in list(globals().items()):
        if name.startswith('test_') and callable(fn):
            fn()
            print('ok', name)

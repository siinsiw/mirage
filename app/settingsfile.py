"""The settings file: one JSON object in .state, shared by every thread of
the window, written whole after each change.

Kept apart from main.py so it can be tested without a window - main.py
imports pywebview and starts talking to Windows the moment it is loaded.
"""

import base64
import json
import os
import threading
import time

import paths

# Resolved when used rather than when imported: --ui-drive points
# paths.STATE_DIR at a fixture tree before anything reads settings, and a
# path fixed at import time would read the real ones.
SETTINGS = None


def settings_path():
    return SETTINGS or os.path.join(paths.STATE_DIR, 'settings.json')


# -- the settings file -------------------------------------------------------
#
# One dict in memory (Api._settings), changed from the page's thread, the
# tray's, the connect worker's and the export's, and written out whole after
# each change. Three things went wrong with that and are why this is longer
# than a json.dump:
#
#   Two writers at once. Both wrote the same "settings.json.tmp", so one could
#   rename the other's half-written file into place - and json.dump of a dict
#   another thread is adding to raises RuntimeError, which was not caught.
#   Writes now take a lock, snapshot the dict first, and use a temporary name
#   of their own.
#
#   A file that does not parse. It used to read as {}, and the next save -
#   any toggle - wrote {} over every setting there was. The last good file is
#   kept as settings.json.bak; a broken one is moved aside under its own name
#   and the backup is read instead.
#
#   Secrets in the clear. The tunnel's passwords, the Shadowsocks password
#   and the Wi-Fi share password are sealed with this Windows account's key
#   (DPAPI, as accounts.py does it) before they are written, and opened again
#   on the way in. A file written by an older copy, with them in the clear, is
#   read as it is and sealed on the next save. The file itself is locked to
#   this user, SYSTEM and Administrators, like the other secret files.

SETTINGS_LOCK = threading.RLock()
SEALED = 'sealed:'
# Top-level keys that hold a secret, and the nested ones: (key, subkey).
SECRET_KEYS = ('tunnelPassword', 'tunnelApiPassword', 'lanSharePassword')
SECRET_NESTED = (('tunnelSs', 'password'),)


def _settings_log(text):
    try:
        os.makedirs(paths.STATE_DIR, exist_ok=True)
        with open(os.path.join(paths.STATE_DIR, 'settings.log'), 'a',
                  encoding='utf-8') as f:
            f.write(time.strftime('%Y-%m-%d %H:%M:%S ') + text + '\n')
    except OSError:
        pass


def _seal(value):
    """'sealed:<base64>' for a secret, or None when it cannot be sealed."""
    if not isinstance(value, str) or not value:
        return value
    if value.startswith(SEALED):
        return value
    try:
        import windscribe
        blob = windscribe._dpapi(value.encode('utf-8'))
    except Exception:
        blob = None
    return SEALED + base64.b64encode(blob).decode() if blob else None


_UNSEALED_SAID = []


def _seal_or_keep(value):
    """Sealed where it can be. Where DPAPI is not there at all (not
    Windows, or a broken profile), the value is kept as it is - in a file
    only this user can read - and that is logged, rather than dropped: a
    tunnel password that silently vanished would cut the person off their
    own server with no hint why."""
    sealed = _seal(value)
    if sealed is None and isinstance(value, str) and value:
        if not _UNSEALED_SAID:
            _UNSEALED_SAID.append(True)
            _settings_log('DPAPI is not available: secrets in settings.json '
                          'are kept unsealed, protected by the file ACL only')
        return value
    return sealed


def dpapi_available():
    return _seal('probe') is not None


def _unseal(value):
    """The secret, from either shape: sealed, or plaintext from an older
    copy. A sealed value that will not open (another Windows account's, a
    copied file) comes back as None rather than as the blob."""
    if not isinstance(value, str) or not value.startswith(SEALED):
        return value
    try:
        import windscribe
        raw = windscribe._dpapi(base64.b64decode(value[len(SEALED):]),
                                unprotect=True)
    except Exception:
        raw = None
    return raw.decode('utf-8', 'replace') if raw else None


def _map_secrets(data, fn):
    """A copy of `data` with every secret field passed through fn. Fields
    fn turns into None are left out."""
    out = dict(data)
    for key in SECRET_KEYS:
        if key in out:
            value = fn(out[key])
            if value is None:
                out.pop(key)
            else:
                out[key] = value
    for key, sub in SECRET_NESTED:
        inner = out.get(key)
        if isinstance(inner, dict) and sub in inner:
            inner = dict(inner)
            value = fn(inner[sub])
            if value is None:
                inner.pop(sub)
            else:
                inner[sub] = value
            out[key] = inner
    return out


def _plain_secrets(raw):
    """Whether a file as read still holds a secret in the clear."""
    found = []
    _map_secrets(raw, lambda v: found.append(v) or v)
    return any(isinstance(v, str) and v and not v.startswith(SEALED)
               for v in found)


def _read_settings_file(path):
    with open(path, encoding='utf-8') as f:
        got = json.load(f)
    if not isinstance(got, dict):
        raise ValueError('settings file does not hold an object')
    return got


def load_settings():
    """The settings, secrets opened. Falls back to the last good copy when
    the file is unreadable, and moves the broken one aside so it is neither
    lost nor written over."""
    with SETTINGS_LOCK:
        try:
            raw = _read_settings_file(settings_path())
            opened = _map_secrets(raw, _unseal)
            if _plain_secrets(raw) and dpapi_available():
                # Written by an older copy, before secrets were sealed. Sealed
                # now, on the way in, rather than on whatever save happens to
                # come next - which may not be for weeks.
                save_settings(opened)
            return opened
        except FileNotFoundError:
            pass
        except PermissionError:
            # An ACL from an older release that did not grant this user
            # back - repaired rather than read as empty.
            try:
                import windscribe
                if windscribe._grant_current_user(settings_path()):
                    return _map_secrets(_read_settings_file(settings_path()), _unseal)
            except Exception:
                pass
            _settings_log('settings.json could not be read: permission denied')
        except (OSError, ValueError) as e:
            aside = f'{settings_path()}.corrupt-{time.strftime("%Y%m%d-%H%M%S")}'
            try:
                os.replace(settings_path(), aside)
                # Kept for whoever wants to read it, by this user only: an
                # older copy's file may have secrets in it in the clear.
                _lock_settings(aside)
            except OSError:
                aside = '(could not be moved aside)'
            _settings_log(f'settings.json did not parse ({e}); kept as '
                          f'{aside}, reading the backup')
        try:
            got = _read_settings_file(settings_path() + '.bak')
            _settings_log('settings restored from settings.json.bak')
            return _map_secrets(got, _unseal)
        except FileNotFoundError:
            return {}
        except (OSError, ValueError) as e:
            _settings_log(f'settings.json.bak did not parse either ({e})')
            return {}


def _snapshot(data):
    """A deep enough copy to serialise while other threads go on changing
    the original. A dict changing size mid-copy raises RuntimeError; it is
    tried again rather than written half-done."""
    for _ in range(5):
        try:
            return json.loads(json.dumps(data))
        except RuntimeError:
            time.sleep(0.01)
    return json.loads(json.dumps(dict(data)))


def save_settings(data):
    """Write the settings, whole. Thread-safe, never raises, and keeps the
    previous good file as settings.json.bak."""
    with SETTINGS_LOCK:
        try:
            os.makedirs(os.path.dirname(settings_path()), exist_ok=True)
            text = json.dumps(_map_secrets(_snapshot(data), _seal_or_keep),
                              indent=2)
            # The file first, then its backup - both from this same sealed
            # text, each written whole and locked before it is moved into
            # place. The backup used to be the previous file renamed, which
            # carried an older copy's plaintext, unlocked file along as
            # settings.json.bak and kept it there.
            _write_locked(settings_path(), text)
            _write_locked(settings_path() + '.bak', text)
        except (OSError, ValueError, TypeError, RuntimeError) as e:
            _settings_log(f'settings were not saved: {e!r}')


def _write_locked(path, text):
    """Write `text` to a fresh temporary file beside `path`, lock it to this
    user, and move it into place - so the file is never half-written and
    never readable by anyone else, even for a moment."""
    import tempfile
    fd, tmp = tempfile.mkstemp(prefix='.settings-', suffix='.tmp',
                               dir=os.path.dirname(path))
    try:
        with os.fdopen(fd, 'w', encoding='utf-8') as f:
            f.write(text)
        _lock_settings(tmp)
        os.replace(tmp, path)
        tmp = None
    finally:
        if tmp:
            try:
                os.remove(tmp)
            except OSError:
                pass


def update_settings(settings, fn):
    """Change the shared dict and save it, as one step: fn(settings) runs
    under the same lock save_settings takes, so two threads changing
    different keys cannot interleave a read of one with a write of the
    other."""
    with SETTINGS_LOCK:
        result = fn(settings)
        save_settings(settings)
        return result


_SID = []


def _lock_settings(path):
    """The file holds sealed secrets and is ours alone: this user, SYSTEM and
    Administrators, nothing inherited - the same SID-based ACL
    windscribe._lock_down gives the credential files. Every save writes a
    fresh temporary file, so this runs once per save; the SID is asked for
    once per run and the ACL is set in a single icacls call, which matters
    for a file written on every flip of a switch. Checked afterwards: an ACL
    that locked this user out is put back to inherited rather than kept."""
    if os.name != 'nt':
        return False
    try:
        import windscribe
        if not _SID:
            _SID.append(windscribe._current_user_sid())
        sid = _SID[0]
        if not sid:
            return windscribe._lock_down(path)
        if not windscribe._icacls(path, '/inheritance:r',
                                  '/grant:r', f'*{sid}:F',
                                  '/grant:r', '*S-1-5-18:F',
                                  '/grant:r', '*S-1-5-32-544:F'):
            return False
        try:
            with open(path, 'rb'), open(path, 'ab'):
                pass
        except OSError:
            windscribe._icacls(path, '/inheritance:e')
            return False
        return True
    except Exception:
        return False

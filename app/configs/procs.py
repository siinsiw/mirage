"""Running sing-box and Xray as children, and talking to sing-box's Clash API.

Each is started with an argument list (never a shell) and a configuration
file written for that one run: a random name under the state folder, locked
to this user, and deleted again as soon as the program has read it - it
holds every password of the group. What a program says goes to a log beside
it, which is what a failure to start is explained from.
"""

import json
import os
import secrets
import socket
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request

import paths
import settingsfile

DIR = os.path.join(paths.STATE_DIR, 'configs')
CREATE_NO_WINDOW = 0x08000000
NEW_GROUP = 0x00000200


def free_ports(n):
    """n ports nothing is listening on right now, on the loopback. Held open
    together while they are picked, so the same one is not handed out twice;
    the moment between letting go and the program binding is the usual
    small race, and a failure to bind says so in the log."""
    socks, out = [], []
    try:
        for _ in range(n):
            s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            s.bind(('127.0.0.1', 0))
            socks.append(s)
            out.append(s.getsockname()[1])
    finally:
        for s in socks:
            s.close()
    return out


def port_free(port):
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    try:
        s.bind(('127.0.0.1', int(port)))
        return True
    except OSError:
        return False
    finally:
        s.close()


def listening(port, timeout=0.3):
    try:
        with socket.create_connection(('127.0.0.1', int(port)), timeout=timeout):
            return True
    except OSError:
        return False


def token():
    return secrets.token_urlsafe(18)


def write_config(config, stem):
    os.makedirs(DIR, exist_ok=True)
    path = os.path.join(DIR, f'{stem}-{secrets.token_hex(6)}.json')
    settingsfile._write_locked(path, json.dumps(config, ensure_ascii=True))
    return path


def forget(path):
    if path:
        try:
            os.remove(path)
        except OSError:
            pass


def start(exe, args, log_name):
    os.makedirs(DIR, exist_ok=True)
    log_path = os.path.join(DIR, log_name)
    with open(log_path, 'w', encoding='utf-8') as log:
        child = subprocess.Popen(
            [exe] + list(args), stdin=subprocess.DEVNULL, stdout=log,
            stderr=subprocess.STDOUT, cwd=DIR,
            creationflags=(CREATE_NO_WINDOW | NEW_GROUP) if os.name == 'nt' else 0)
    return child, log_path


def tail(path, n=600):
    try:
        with open(path, encoding='utf-8', errors='replace') as f:
            text = f.read()
    except OSError:
        return ''
    return text.strip()[-n:]


def wait(child, ready, seconds=15):
    """Until ready() says yes, the child ends, or time runs out."""
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        if child.poll() is not None:
            return False
        try:
            if ready():
                return True
        except Exception:
            pass
        time.sleep(0.15)
    return False


def stop(child, wait_s=5):
    if child is None:
        return
    try:
        if child.poll() is None:
            child.terminate()
            child.wait(timeout=wait_s)
    except Exception:
        try:
            child.kill()
            child.wait(timeout=wait_s)
        except Exception:
            pass


def image_of(pid):
    """The program a pid is running, by name, or '' - so a pid written down
    before a crash is only ended if it is still the program it was."""
    if os.name != 'nt':
        return ''
    try:
        out = subprocess.run(['tasklist', '/FI', f'PID eq {int(pid)}', '/FO',
                              'CSV', '/NH'], capture_output=True, text=True,
                             timeout=10, creationflags=CREATE_NO_WINDOW).stdout
    except Exception:
        return ''
    first = out.strip().split(',')[0].strip('"') if out.strip() else ''
    return first.lower()


def kill_if(pid, image):
    if pid and image_of(pid) == image.lower():
        try:
            subprocess.run(['taskkill', '/PID', str(int(pid)), '/F'],
                           capture_output=True, timeout=10,
                           creationflags=CREATE_NO_WINDOW)
            return True
        except Exception:
            return False
    return False


class Clash:
    """sing-box's Clash API on the loopback, with the run's secret. Never
    through any proxy: the machine's own proxy setting may be pointing at
    the very program being asked."""

    def __init__(self, port, secret):
        self.base = f'http://127.0.0.1:{int(port)}'
        self.secret = secret
        self._open = urllib.request.build_opener(
            urllib.request.ProxyHandler({})).open

    def call(self, path, method='GET', body=None, timeout=5):
        data = json.dumps(body).encode() if body is not None else None
        req = urllib.request.Request(self.base + path, data=data, method=method)
        req.add_header('Authorization', f'Bearer {self.secret}')
        if data is not None:
            req.add_header('Content-Type', 'application/json')
        with self._open(req, timeout=timeout) as r:
            raw = r.read(4 * 1024 * 1024)
        return json.loads(raw) if raw.strip() else {}

    def up(self):
        try:
            return 'version' in self.call('/version', timeout=1)
        except Exception:
            return False

    def select(self, group, tag):
        self.call(f'/proxies/{urllib.parse.quote(group)}', 'PUT',
                  {'name': tag})

    def now(self, group):
        try:
            return self.call(f'/proxies/{urllib.parse.quote(group)}').get('now')
        except Exception:
            return None

    def delay(self, tag, url, timeout_ms=6000):
        """(ms, None) or (None, what went wrong in sing-box's words)."""
        q = urllib.parse.urlencode({'url': url, 'timeout': int(timeout_ms)})
        try:
            got = self.call(f'/proxies/{urllib.parse.quote(tag)}/delay?{q}',
                            timeout=timeout_ms / 1000 + 3)
        except urllib.error.HTTPError as e:
            try:
                said = json.loads(e.read(4096) or b'{}').get('message', '')
            except ValueError:
                said = ''
            return None, said or f'HTTP {e.code}'
        except Exception as e:
            return None, str(e)
        if isinstance(got.get('delay'), int) and got['delay'] > 0:
            return got['delay'], None
        return None, got.get('message') or 'no answer'

    def connections(self):
        return self.call('/connections', timeout=3)

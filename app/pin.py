"""Pinning a folder of configs to real addresses, from the window.

A downloaded .ovpn points at a hostname, and on a censored line that hostname
is the weak link: the resolver answers with an address on your own LAN,
OpenVPN dials it, and nothing ever leaves the building. Resolving that name
over DoH and writing the answer into the config is what fixes it, and
Resolve-OvpnRemote.ps1 is the thing that does it.

That script is not reimplemented here, for the same reason the sweep is not.
It is the file that was measured and argued over - which answers count as
forged, how a config's line endings survive being rewritten, how the
PowerShell that ships with Windows reads the JSON - and a second copy of that
in Python would drift from it within a week. This starts it, reads what it
says, and turns that into something a window can show.

Why this is a quarter the length of sweep.py, which does the same job for the
other script: pinning needs no administrator rights and no openvpn.exe.
Nothing is elevated, so there is a pipe to read rather than a log file to
poll from the other side of a boundary, and stopping it is killing it rather
than asking it to notice a file appearing.

The one thing this adds to the script is the choice the window has to offer.
The script's own default is neither of the two answers - it tries direct and
goes hunting for a local proxy the moment that fails, which is the right
reflex at a terminal and is a third answer nobody chose in a window with two
buttons on it. So the window always says which, every time: -Proxy for one,
-NoProxy for the other.
"""

import json
import math
import os
import re
import socket
import subprocess
import threading
import urllib.request

import paths
import sweep

# The two answers, and the port the one that needs a number defaults to -
# v2rayN, Nekoray and sing-box all listen there, which is why it is first in
# the script's own list of ports to try.
ROUTES = ('proxy', 'direct')
DEFAULT_PORT = 10808

# The script's own default, and its ceiling.
DEFAULT_MAX_IPS = 4
MAX_IPS_CEILING = 32

# One DoH request per distinct hostname, and one TCP probe per file written.
# Both are a second or two on a working line; this is only ever used to say
# "about a minute", so it is deliberately coarse.
SECONDS_EACH = 1.6

# The script's own lines, as they arrive down the pipe. The [ ok ] / [warn] /
# [fail] prefixes are the repo's, and the colour they are printed in does not
# survive redirection, which is convenient.
LINE_TOTAL = re.compile(r'^\s*Configs \((\d+)\)\s*$')
LINE_NOTHING = re.compile(r'^\s*Nothing to do yet\s*$')
LINE_WROTE = re.compile(r'^\s*\[(?: ok |warn)\]\s+(\S+\.ovpn)\s{2,}'
                        r'(\d{1,3}(?:\.\d{1,3}){3}):(\d+)\s*(.*?)\s*$')
LINE_SKIP = re.compile(r'^\s*\[(?:warn|fail)\]\s+(\S+\.ovpn):\s+(.+?)\s*$')
LINE_FORGED = re.compile(r'^\s*\[warn\]\s+(\S+):\s+threw away (.+?)'
                         r' - not a public address\s*$')
LINE_NAMED = re.compile(r'using the proxy you named:\s+(\S+)')
LINE_FOUND = re.compile(r'DoH will go through\s+(\S+)')
LINE_DIRECT = re.compile(r'DoH works directly')
LINE_FAIL = re.compile(r'^\s*\[fail\]\s+(.+?)\s*$')

# de-fra.prod.surfshark.com_tcp_1.2.3.4.ovpn -> the file it was made from.
# Several addresses for one hostname means several files, and they are one
# config's worth of progress between them, not four.
FROM_NAME = re.compile(r'_\d{1,3}(?:\.\d{1,3}){3}\.ovpn$')


# -- what a pinned config may not say ---------------------------------------
#
# A pinned config is a provider's file with one line changed, and the sweep
# hands it to openvpn.exe running as administrator. OpenVPN configs can run
# programs: `up`, `down` and the rest name a command, and `script-security 2`
# lets them run it. A config that arrived with those - a download from
# somewhere other than the provider, an inbox somebody else can write to -
# would be a program run elevated on the next sweep. Nothing this app does
# needs any of them, so they are taken out wherever a pinned file is written
# or about to be used, and a comment says what went.
#
# And the ones that do not run anything themselves but point an elevated
# openvpn at the file system or at another config: `config` pulls in a file
# the strip never saw, `cd`/`chroot` move where relative paths land, the log,
# status, pid and replay files are written as administrator wherever they
# say, `management` opens a control port, and `iproute`/`askpass` name
# programs or files to read. None of them belongs in a pinned config either.
SCRIPT_DIRECTIVES = frozenset((
    'up', 'down', 'route-up', 'route-pre-down', 'ipchange', 'tls-verify',
    'auth-user-pass-verify', 'client-connect', 'client-disconnect',
    'learn-address', 'plugin', 'script-security', 'setenv', 'setenv-safe',
    'up-restart', 'down-pre', 'tls-crypt-v2-verify',
    'config', 'cd', 'chroot', 'log', 'log-append', 'status', 'writepid',
    'tmp-dir', 'management', 'replay-persist', 'iproute', 'daemon',
    'askpass'))
STRIPPED_NOTE = '# relay: removed script directives:'

# Inline blocks whose contents are data - certificates, keys, a password
# file - and pass through untouched. <connection> is not among them: what is
# inside it is options, parsed like the rest of the file, and filtered so.
DATA_BLOCKS = frozenset((
    'ca', 'cert', 'key', 'tls-auth', 'tls-crypt', 'tls-crypt-v2',
    'extra-certs', 'secret', 'pkcs12', 'crl-verify', 'http-proxy-user-pass',
    'auth-user-pass', 'dh'))


def _first_token(line):
    """The option a config line names, read the way OpenVPN's own parser
    reads it - or None for a blank line or a comment.

    Matching the first whitespace-separated word is not enough, because
    OpenVPN unquotes and unescapes before it looks the word up: "up",
    'plugin' and u\\p all name the option they spell. So: leading
    whitespace skipped, # or ; at the start of a token is a comment,
    double quotes (with backslash escapes inside), single quotes (without),
    a backslash outside single quotes taking the next character literally,
    and a leading -- dropped, as OpenVPN does for options in a file.
    Lower-cased, so the match is case-insensitive.
    """
    # A byte-order mark is skipped too: OpenVPN skips one at the start of a
    # file, so "<BOM>up evil" on the first line is the `up` option. Skipped
    # wherever it starts a line, which is never wrong to do here.
    s = line.lstrip().lstrip('﻿').lstrip()
    if not s or s[0] in '#;':
        return None
    tok, quote, i = [], None, 0
    while i < len(s):
        c = s[i]
        if quote == "'":
            if c == "'":
                quote = None
            else:
                tok.append(c)
        elif c == '\\' and i + 1 < len(s):
            i += 1
            tok.append(s[i])
        elif quote == '"':
            if c == '"':
                quote = None
            else:
                tok.append(c)
        elif c in '"\'':
            quote = c
        elif c.isspace():
            break
        else:
            tok.append(c)
        i += 1
    word = ''.join(tok)
    if word.startswith('--'):
        word = word[2:]
    return word.lower()


def strip_script_directives(text):
    """(text, removed) - the config without any option that runs a program
    or points openvpn somewhere it should not, and what was taken out.

    Options are recognised as OpenVPN recognises them (_first_token): quoted,
    escaped or prefixed with --, in any case. Inline data blocks such as
    <ca>...</ca> pass through untouched, and end the way OpenVPN ends them -
    at the first line that *begins* with the closing tag, so a `</ca> up x`
    trick cannot keep the rest of the file hidden inside the block.
    <connection> blocks are options, and filtered like the rest; an inline
    block named after one of the directives (<plugin>...</plugin>) is
    removed whole. Line endings are kept as the file had them.
    """
    out, removed = [], []
    block, dropping = None, False
    for line in text.splitlines(keepends=True):
        if block:
            if not dropping:
                out.append(line)
            if line.lstrip().lower().startswith(f'</{block}>'):
                block, dropping = None, False
            continue
        word = _first_token(line)
        if word and len(word) > 2 and word[0] == '<' and word[-1] == '>' \
                and word[1] != '/':
            tag = word[1:-1]
            if tag in SCRIPT_DIRECTIVES:
                block, dropping = tag, True
                removed.append(tag)
                continue
            if tag in DATA_BLOCKS:
                block = tag
            # <connection>, and any tag not known to hold data, are read on
            # as options: filtering a line that only looked like one costs
            # less than letting through one that was.
            out.append(line)
            continue
        if word in SCRIPT_DIRECTIVES:
            removed.append(word)
            continue
        out.append(line)
    if removed:
        ending = '\r\n' if '\r\n' in text else '\n'
        names = ', '.join(dict.fromkeys(removed))
        note = (f'{STRIPPED_NOTE} {names} - a pinned config never runs '
                f'programs{ending}')
        if out and not out[-1].endswith(('\n', '\r')):
            out[-1] += ending
        out.append(note)
    return ''.join(out), removed


def sanitize_file(path):
    """Strip one config in place. Returns what was removed (empty if the
    file was already clean, or could not be read)."""
    try:
        with open(path, encoding='utf-8', errors='surrogateescape',
                  newline='') as f:
            text = f.read()
    except OSError:
        return []
    clean, removed = strip_script_directives(text)
    if not removed:
        return []
    tmp = f'{path}.{os.getpid()}.tmp'
    try:
        with open(tmp, 'w', encoding='utf-8', errors='surrogateescape',
                  newline='') as f:
            f.write(clean)
        os.replace(tmp, path)
    except OSError:
        try:
            os.remove(tmp)
        except OSError:
            pass
        return []
    return removed


def sanitize_folder(folder):
    """Every .ovpn in a folder, as sanitize_file. {name: removed}."""
    done = {}
    for name in sweep.configs_in(folder):
        removed = sanitize_file(os.path.join(folder, name))
        if removed:
            done[name] = removed
    return done


def source_dir():
    """The inbox: where the files you downloaded from the provider go.

    The same folder the script uses when nobody tells it otherwise, so a run
    from the window and a run from a terminal read the same pile.
    """
    return os.path.join(paths.DATA_DIR, 'configs')


def out_dir():
    """And where the pinned copies land - also the script's own default, and
    one of the three folders the app looks in for servers to connect to."""
    return os.path.join(paths.DATA_DIR, 'pinned')


# The script's own list of ports to try, in its order, for when nobody has
# said which. Copied rather than parsed out of the .ps1 - it is one line, and
# a proxy that is not on it can still be named by hand.
PROXY_PORTS = (10808, 10809, 7890, 7891, 2080, 2081, 1080, 1081, 8889, 8080,
               20171, 12334)


def proxy_answer(port, host='127.0.0.1', timeout=5.0):
    """What a CONNECT to the DoH host through that port gets back.

    'none' nothing is listening; 'ok' it opened the tunnel; 'auth' it wants a
    password (407), which is a proxy that is there and will refuse every
    lookup; 'refused' any other answer, including a SOCKS-only port closing
    on an HTTP request; 'slow' no answer in time, which a proxy on a slow
    tunnel can be and is not held against it.

    The question Test-HttpProxy in the script asks, asked here too, because
    listening is not the same as carrying: Happ listens on 10808 and answers
    407 to everything, and a run through it was 142 "could not resolve"s.
    """
    try:
        sock = socket.create_connection((host, int(port)), 1.0)
    except (OSError, ValueError, OverflowError):
        return 'none'
    try:
        sock.settimeout(timeout)
        sock.sendall(b'CONNECT cloudflare-dns.com:443 HTTP/1.1\r\n'
                     b'Host: cloudflare-dns.com:443\r\n\r\n')
        head = sock.recv(256)
    except socket.timeout:
        return 'slow'
    except OSError:
        return 'refused'
    finally:
        sock.close()
    m = re.match(rb'HTTP/1\.[01] (\d{3})', head)
    if not m:
        return 'refused'
    code = int(m.group(1))
    return 'ok' if code == 200 else 'auth' if code == 407 else 'refused'


# How long a proxy that is listening gets to open its first tunnel - the same
# wait the script gives it (Test-HttpProxy). A proxy that has only just
# started opens its own way out on the first CONNECT, and that first answer
# can take far longer than a warm one. Five seconds could take such a proxy
# for absent, so the setup after a sign-in went on to a port that could not
# work, while "Try again" - by then talking to a warm proxy - found it at once.
FIND_TIMEOUT = 20.0


def find_port(first=DEFAULT_PORT, timeout=FIND_TIMEOUT):
    """The first local port that really carries a lookup, or None.

    A port with nothing on it answers 'none' within a second, so the wait
    only applies to ports where something is actually listening."""
    for port in (first, *(p for p in PROXY_PORTS if p != first)):
        if proxy_answer(port, timeout=timeout) == 'ok':
            return port
    return None


# The question the script asks first, asked from here when nobody has chosen
# a route: does DoH answer straight out? The resolver and the name are the
# script's own (Resolve-Doh, Select-DohRoute).
DOH_PROBE = 'https://cloudflare-dns.com/dns-query?name=example.com&type=A'


def direct_doh(timeout=10.0, tries=2):
    """Whether DNS-over-HTTPS answers with no proxy at all.

    Asked twice before the answer is no: on the line this app is for a
    single attempt can fail where the next one passes, and one failure is
    not the same as a blocked resolver. A forged answer cannot get here -
    the certificate is checked - so any A record back means the route works.
    """
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    request = urllib.request.Request(
        DOH_PROBE, headers={'Accept': 'application/dns-json',
                            'User-Agent': 'ovpn-pin'})
    for _ in range(max(1, tries)):
        try:
            with opener.open(request, timeout=timeout) as response:
                body = json.loads(response.read().decode('utf-8', 'replace'))
        except (OSError, ValueError):
            # URLError, timeouts and TLS failures are all OSErrors.
            continue
        if isinstance(body, dict) and any(
                isinstance(a, dict) and a.get('type') == 1
                for a in body.get('Answer') or []):
            return True
    return False


def auto_route(port_hint=DEFAULT_PORT):
    """('direct', None), ('proxy', port), or (None, None) when neither works.

    Direct first: it needs nothing else running, and a lookup that works
    without a proxy keeps working when that proxy is closed. Then whatever
    local proxy really carries a lookup."""
    if direct_doh():
        return 'direct', None
    found = find_port(clean_port(port_hint))
    if found:
        return 'proxy', found
    return None, None


def clean_port(value, fallback=DEFAULT_PORT):
    try:
        port = int(str(value).strip())
    except (TypeError, ValueError):
        return fallback
    return port if 1 <= port <= 65535 else fallback


def clean_max_ips(value, fallback=DEFAULT_MAX_IPS):
    try:
        n = int(value)
    except (TypeError, ValueError):
        return fallback
    return max(1, min(MAX_IPS_CEILING, n))


def estimate_minutes(count, max_ips):
    """Long enough to be worth saying, short enough that nobody leaves the
    room. One hostname is one lookup however many files come out of it, so
    this leans on the file count rather than multiplying by max_ips."""
    if not count:
        return 0
    return max(1, math.ceil(count * SECONDS_EACH * (1 + max_ips / 8) / 60))


def outcome_of(note):
    """What the note on the end of a written line means.

    'reachable', 'NOT reachable', '(udp - not testable)', or nothing at all
    when the reachability check was turned off.
    """
    said = (note or '').strip().lower()
    if said.startswith('not reachable'):
        return 'unreachable'
    if said.startswith('reachable'):
        return 'reachable'
    if said.startswith('(udp'):
        return 'udp'
    return 'written'


class Pin:
    """One pinning run at a time, and always able to say what it is doing."""

    def __init__(self):
        self.lock = threading.Lock()
        self.thread = None
        self.proc = None
        self.state = 'idle'          # idle | running | done
        self.results = []
        self.error = None
        self.route = None            # what the script said it was using
        self.out = None
        self.total = 0
        self.seen = set()
        self.stopping = False

    # -- before it starts --------------------------------------------------

    def blockers(self, folder, out, route, port, quick=False):
        """Everything that would make a run fail or lie, asked before the
        person presses anything.

        quick leaves out the one check that touches a socket - whether the
        proxy they named is actually there. Right when the sheet opens and
        right before Start; wrong on every keystroke in the port field.
        """
        said = []
        if os.name != 'nt':
            said.append({'kind': 'windows',
                         'say': 'Pinning runs the PowerShell resolver, so it '
                                'only works on Windows.'})
            return said
        if not os.path.isfile(sweep.library_path()):
            said.append({'kind': 'no-script',
                         'say': f'{sweep.LIBRARY} is not beside the app, so '
                                f'there is nothing to resolve with.'})
        if not sweep.configs_in(folder):
            said.append({'kind': 'no-configs',
                         'say': 'No .ovpn files in that folder. Put the ones '
                                'you downloaded from your provider in it '
                                'first.'})
        # Writing the copies back into the folder they came from means the
        # next run reads its own output and pins an address that is already
        # an address. The script has -InPlace for people who really want
        # that; this is not it.
        if folder and out and os.path.normcase(os.path.abspath(folder)) \
                == os.path.normcase(os.path.abspath(out)):
            said.append({'kind': 'same-folder',
                         'say': 'The pinned copies would land on top of the '
                                'originals, and the next run would pin its '
                                'own output. Choose a different folder for '
                                'them.'})
        answer = proxy_answer(port) if route == 'proxy' and not quick else None
        if answer == 'none':
            said.append({'kind': 'no-proxy',
                         'say': f'Nothing is listening on 127.0.0.1:{port}. '
                                f'Start your proxy - v2rayN, Clash, Nekoray, '
                                f'sing-box, Hiddify - or resolve directly.'})
        elif answer == 'auth':
            said.append({'kind': 'no-proxy',
                         'say': f'127.0.0.1:{port} asks for a password, so '
                                f'every lookup through it is refused. Use '
                                f'your proxy\'s HTTP port - often the next '
                                f'number up - or resolve directly.'})
        elif answer == 'refused':
            said.append({'kind': 'no-proxy',
                         'say': f'127.0.0.1:{port} answers but will not '
                                f'carry a lookup. Use your proxy\'s HTTP '
                                f'port, or resolve directly.'})
        return said

    def plan(self, folder=None, out=None, route='proxy', port=DEFAULT_PORT,
             max_ips=DEFAULT_MAX_IPS, quick=False):
        folder = folder or source_dir()
        out = out or out_dir()
        route = route if route in ROUTES else 'proxy'
        port = clean_port(port)
        max_ips = clean_max_ips(max_ips)
        total = len(sweep.configs_in(folder))
        return {'folder': folder,
                'out': out,
                'total': total,
                # What is already there. A folder with four hundred files in
                # it from a run last month is the difference between "this
                # wrote nothing" and "this had nothing to do".
                'existing': len(sweep.configs_in(out)),
                'route': route,
                'port': port,
                'maxIps': max_ips,
                # The ceiling, not a promise: most hostnames answer with
                # fewer addresses than the cap allows.
                'most': total * max_ips,
                'minutes': estimate_minutes(total, max_ips),
                # Whether the proxy was really asked. A quick plan skips the
                # socket, so the page must not paint a stale answer as a
                # fresh one - a port that was answering before somebody
                # changed the number is not an answer about the new number.
                'checked': not quick,
                'blockers': self.blockers(folder, out, route, port, quick),
                'state': self.state}

    # -- running it --------------------------------------------------------

    def start(self, folder, out, emit, route='proxy', port=DEFAULT_PORT,
              max_ips=DEFAULT_MAX_IPS, test=True):
        with self.lock:
            if self.state == 'running':
                return {'ok': False, 'error': 'A run is already going.'}
            folder = folder or source_dir()
            out = out or out_dir()
            route = route if route in ROUTES else 'proxy'
            port = clean_port(port)
            max_ips = clean_max_ips(max_ips)

            blockers = self.blockers(folder, out, route, port)
            if blockers:
                return {'ok': False, 'error': blockers[0]['say'],
                        'blockers': blockers}

            total = len(sweep.configs_in(folder))
            self.state = 'running'
            self.stopping = False
            self.results = []
            self.error = None
            self.route = None
            self.out = out
            self.total = total
            self.seen = set()
            self.thread = threading.Thread(
                target=self._run,
                args=(folder, out, route, port, max_ips, test, emit),
                daemon=True)
            self.thread.start()
        return {'ok': True, 'total': total, 'out': out,
                'minutes': estimate_minutes(total, max_ips)}

    def cancel(self):
        """Kill it. Nothing is elevated and nothing else was started, so this
        is the whole of stopping - the files already written stay written,
        which is the point of writing one per config rather than a batch at
        the end."""
        if self.state != 'running':
            return {'ok': False}
        self.stopping = True
        proc = self.proc
        if proc:
            try:
                proc.kill()
            except OSError:
                pass
        return {'ok': True}

    # -- the work ----------------------------------------------------------

    def _argv(self, folder, out, route, port, max_ips, test):
        argv = ['powershell', '-NoProfile', '-ExecutionPolicy', 'Bypass',
                '-File', sweep.library_path(),
                '-Path', folder,
                '-OutDir', out,
                '-StateDir', paths.STATE_DIR,
                '-MaxIps', str(max_ips)]
        # Always one or the other, never the script's own default. Left to
        # itself it would try direct and then go looking for a proxy, which
        # is a third answer to a question the window asked as two.
        if route == 'proxy':
            argv += ['-Proxy', f'http://127.0.0.1:{port}']
        else:
            argv += ['-NoProxy']
        if not test:
            argv += ['-NoTest']
        return argv

    def _run(self, folder, out, route, port, max_ips, test, emit):
        """The run, and the promise that it always ends in 'finished'.

        The page waits for that one event - the setup sheet after a sign-in
        has nothing else to stop its spinner - so an exception anywhere in
        here still has to end the run out loud rather than kill the thread
        and leave the run 'running' with nobody coming back to it."""
        try:
            self._work(folder, out, route, port, max_ips, test, emit)
        except Exception as e:
            proc, self.proc = self.proc, None
            if proc:
                try:
                    proc.kill()
                except OSError:
                    pass
            if self.state == 'running':
                self._finish(emit, error=f'The resolver run stopped: {e}')

    def _work(self, folder, out, route, port, max_ips, test, emit):
        try:
            os.makedirs(out, exist_ok=True)
        except OSError as e:
            self._finish(emit, error=f'Could not make {out}: {e}')
            return

        emit({'phase': 'starting', 'total': self.total, 'out': out,
              'route': route, 'port': port})

        try:
            self.proc = subprocess.Popen(
                self._argv(folder, out, route, port, max_ips, test),
                stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                stdin=subprocess.DEVNULL,
                creationflags=sweep.CREATE_NO_WINDOW if os.name == 'nt' else 0)
        except (OSError, subprocess.SubprocessError) as e:
            self._finish(emit, error=f'Could not start the resolver: {e}')
            return

        # Bytes rather than text=True on purpose: PowerShell writes whatever
        # the console encoding is once redirected, and a config named in a
        # language this machine is not set to would otherwise raise a decode
        # error in the middle of a run that was going fine.
        last_fail = None
        for raw in self.proc.stdout:
            line = raw.decode('utf-8', 'replace').rstrip('\r\n')
            said = self._saw(line, emit)
            if said:
                last_fail = said
        code = self.proc.wait()
        self.proc = None

        if self.stopping:
            self._finish(emit, cancelled=True)
        elif code != 0:
            # The script's own last words, which are more use than its exit
            # code - it prints the reason and then exits 1.
            self._finish(emit, error=last_fail
                         or f'The resolver stopped with code {code}.')
        else:
            self._finish(emit)

    def _saw(self, line, emit):
        """One line of the script's output. Returns the text of a [fail] line
        so the caller can keep the last one as the reason."""
        m = LINE_TOTAL.search(line)
        if m:
            # What the script found in the folder, which beats what this side
            # counted: it is the one doing the reading.
            self.total = int(m.group(1))
            emit({'phase': 'resolving', 'done': 0, 'total': self.total,
                  'route': self.route})
            return None

        if LINE_NOTHING.search(line):
            self.total = 0
            return None

        m = LINE_NAMED.search(line) or LINE_FOUND.search(line)
        if m:
            self.route = m.group(1)
            emit({'phase': 'route', 'via': self.route})
            return None
        if LINE_DIRECT.search(line):
            self.route = 'direct'
            emit({'phase': 'route', 'via': 'direct'})
            return None

        m = LINE_WROTE.search(line)
        if m:
            name, ip, port, note = m.groups()
            # The script copies the provider's file and changes one line;
            # whatever else the file said comes with it. Scripts do not.
            if self.out:
                sanitize_file(os.path.join(self.out, os.path.basename(name)))
            self._result(emit, FROM_NAME.sub('.ovpn', name), name,
                         outcome_of(note), ip=ip, port=int(port))
            return None

        m = LINE_FORGED.search(line)
        if m:
            # Not a result of its own - it is the censor being caught, and it
            # belongs to whichever config is being read right now.
            emit({'phase': 'forged', 'host': m.group(1),
                  'addresses': m.group(2)})
            return None

        m = LINE_SKIP.search(line)
        if m:
            self._result(emit, m.group(1), None, 'skipped', detail=m.group(2))
            return None

        m = LINE_FAIL.search(line)
        if m:
            return m.group(1)
        return None

    def _result(self, emit, source, written, outcome, ip=None, port=None,
                detail=None):
        self.seen.add(source)
        row = {'source': source, 'name': written or source, 'outcome': outcome,
               'ip': ip, 'port': port, 'detail': detail}
        self.results.append(row)
        emit({'phase': 'result', 'done': len(self.seen), 'total': self.total,
              **row})

    def _finish(self, emit, error=None, cancelled=False):
        # Once more over the whole folder: a file whose line was not
        # recognised above, or one written by an earlier copy of this app.
        if self.out:
            try:
                sanitize_folder(self.out)
            except Exception:
                pass
        self.state = 'done'
        self.error = error
        written = [r for r in self.results if r['outcome'] != 'skipped']
        emit({'phase': 'finished',
              'read': len(self.seen),
              'total': self.total,
              'written': len(written),
              'reachable': len([r for r in written
                                if r['outcome'] == 'reachable']),
              'unreachable': len([r for r in written
                                  if r['outcome'] == 'unreachable']),
              'skipped': len([r for r in self.results
                              if r['outcome'] == 'skipped']),
              'out': self.out,
              # Re-read from disk rather than counted from what went past: a
              # cancelled run leaves what earlier runs wrote standing, and
              # that is what the folder is worth now.
              'inFolder': len(sweep.configs_in(self.out or '')),
              'route': self.route,
              'cancelled': cancelled,
              'error': error})

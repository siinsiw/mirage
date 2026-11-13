"""Latency tests: how long a real page takes to load through each config,
from this line.

Not a ping to the server's port - for a server behind a CDN that measures
the CDN, and for any server it says nothing about whether the far end
carries anything. Each config gets a loopback port of its own on a
sing-box started for the test (and Xray in front, for the ones only Xray
carries); through that port the same trace page the rest of the app uses
is fetched over HTTPS, and the time is from the first byte sent to the page
read. The page also says the country it was asked from, which is the
country the config really comes out in - shown as a flag beside the time.

A config that fails is explained from sing-box's log, whose words are the
ones worth reading ("certificate signed by unknown authority", "i/o
timeout") - turned into a short reason for the page.

Straight from this line, never through a connection that is up: the
programs that run the test are left out of the adapter by program, and the
question is how this config does from here. Nothing runs in the background;
a test is something the person asked for.
"""

import concurrent.futures
import socket
import ssl
import time

import paths

from . import links, procs, singbox, xray

TRACE_HOST = 'www.cloudflare.com'
TRACE_PATH = '/cdn-cgi/trace'
BATCH = 100
CONCURRENCY = 8
TIMEOUT = 8.0


def classify(said):
    m = (said or '').lower()
    if 'x509' in m or 'certificate' in m:
        return 'cert'
    if 'timeout' in m or 'deadline' in m or 'timed out' in m:
        return 'timeout'
    if 'refused' in m:
        return 'refused'
    if 'no such host' in m or 'lookup' in m:
        return 'dns'
    if 'eof' in m or 'reset' in m or 'closed' in m:
        return 'closed'
    return 'failed'


def through(port, timeout=TIMEOUT):
    """(ms, ip, country) through one loopback HTTP proxy port."""
    began = time.monotonic()
    raw = socket.create_connection(('127.0.0.1', int(port)), timeout=timeout)
    try:
        raw.settimeout(timeout)
        raw.sendall(f'CONNECT {TRACE_HOST}:443 HTTP/1.1\r\n'
                    f'Host: {TRACE_HOST}:443\r\n\r\n'.encode('ascii'))
        head = b''
        while b'\r\n\r\n' not in head:
            chunk = raw.recv(1024)
            if not chunk:
                raise OSError('the connection closed before it was made')
            head += chunk
            if len(head) > 8192:
                raise OSError('the local proxy did not answer')
        if b' 200' not in head.split(b'\r\n', 1)[0]:
            raise OSError('the local proxy refused')
        tls = ssl.create_default_context().wrap_socket(
            raw, server_hostname=TRACE_HOST)
        tls.sendall((f'GET {TRACE_PATH} HTTP/1.1\r\nHost: {TRACE_HOST}\r\n'
                     'User-Agent: Mozilla/5.0\r\nConnection: close\r\n\r\n')
                    .encode('ascii'))
        body = b''
        while len(body) < 16384:
            chunk = tls.recv(4096)
            if not chunk:
                break
            body += chunk
            if b'\nloc=' in body and b'\n' in body.split(b'\nloc=', 1)[1]:
                break
        took = int((time.monotonic() - began) * 1000)
    finally:
        raw.close()
    text = body.decode('latin-1')
    if ' 200' not in text.split('\r\n', 1)[0]:
        raise OSError('the trace page did not answer')
    seen = dict(line.split('=', 1) for line in text.splitlines()
                if '=' in line and ' ' not in line.split('=', 1)[0])
    return took, seen.get('ip', ''), (seen.get('loc') or '').lower()[:2]


def reason_in_log(log_path, tag):
    """sing-box's own words for why a config failed: the last line of its
    log that names the config's outbound, after the outbound's name. Its
    Clash API only says "an error occurred"; the log says "certificate
    signed by unknown authority" or "actively refused"."""
    text = procs.tail(log_path, 200_000)
    mark = f'[{tag}]: '
    for line in reversed(text.splitlines()):
        if mark in line:
            return line.split(mark, 1)[1].strip()
    return ''


def _one(cfg, port, log_path, cancel):
    if cancel is not None and cancel.is_set():
        return {'ms': None, 'error': 'cancelled'}
    try:
        ms, ip, country = through(port)
        return {'ms': max(1, ms), 'ip': ip, 'country': country, 'error': None}
    except Exception as e:
        time.sleep(0.2)     # the log line is written as the dial fails
        said = reason_in_log(log_path, singbox.tag_of(cfg)) or str(e)
        return {'ms': None, 'error': classify(said), 'said': said[-160:]}


def test(configs, progress=None, fragment=False, cancel=None):
    """id -> {'ms', 'ip', 'country', 'error', 'said'} for every config.

    progress(done, total, id, result) after each one."""
    sb = paths.singbox_exe()
    if not sb:
        raise RuntimeError('engine-missing: sing-box.exe was not found')
    results = {}
    total = len(configs)
    state = {'done': 0}

    def note(cid, result):
        results[cid] = result
        state['done'] += 1
        if progress:
            try:
                progress(state['done'], total, cid, result)
            except Exception:
                pass

    for start in range(0, total, BATCH):
        if cancel is not None and cancel.is_set():
            break
        _batch(sb, configs[start:start + BATCH], note, fragment, cancel)
    return results


def _batch(sb, batch, note, fragment, cancel):
    xbin = paths.xray_exe()
    for_xray = [c for c in batch if links.engine_for(c) == 'xray']
    if for_xray and not xbin:
        for c in for_xray:
            note(c['id'], {'ms': None, 'error': 'xray-missing'})
        batch = [c for c in batch if links.engine_for(c) != 'xray']
        for_xray = []
    if not batch:
        return
    ports = procs.free_ports(len(batch) + 1 + len(for_xray))
    own = {c['id']: ports[n] for n, c in enumerate(batch)}
    clash_port = ports[len(batch)]
    xports = ports[len(batch) + 1:]
    secret = procs.token()
    child = xchild = None
    files = []
    try:
        hops = {}
        if for_xray:
            entries = [(c, xports[n], 'relay', procs.token())
                       for n, c in enumerate(for_xray)]
            hops = {c['id']: (p, u, pw) for c, p, u, pw in entries}
            path = procs.write_config(xray.build(entries, fragment), 'probe-xray')
            files.append(path)
            xchild, xlog = procs.start(xbin, ['run', '-c', path], 'probe-xray.log')
            if not procs.wait(xchild, lambda: all(procs.listening(p)
                                                  for p in xports), 15):
                said = procs.tail(xlog, 200) or 'Xray did not start'
                for c in for_xray:
                    note(c['id'], {'ms': None, 'error': 'failed', 'said': said})
                batch = [c for c in batch if c['id'] not in hops]
                hops = {}
                procs.stop(xchild)
                xchild = None
            procs.forget(path)
        if not batch:
            return
        config = singbox.build_probe(batch, own, clash_port=clash_port,
                                     clash_secret=secret, xray=hops,
                                     fragment=fragment)
        path = procs.write_config(config, 'probe')
        files.append(path)
        child, log = procs.start(sb, ['run', '-c', path, '--disable-color'],
                                 'probe.log')
        clash = procs.Clash(clash_port, secret)
        if not procs.wait(child, clash.up, 15):
            raise RuntimeError('probe-failed: ' + (procs.tail(log, 300)
                                                   or 'sing-box did not start'))
        procs.forget(path)
        with concurrent.futures.ThreadPoolExecutor(CONCURRENCY) as pool:
            jobs = {pool.submit(_one, c, own[c['id']], log, cancel): c
                    for c in batch}
            for job in concurrent.futures.as_completed(jobs):
                c = jobs[job]
                try:
                    note(c['id'], job.result())
                except Exception as e:
                    note(c['id'], {'ms': None, 'error': 'failed',
                                   'said': str(e)[:160]})
    finally:
        procs.stop(child)
        procs.stop(xchild)
        for f in files:
            procs.forget(f)

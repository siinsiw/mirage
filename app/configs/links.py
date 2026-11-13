"""Links into one plain shape, checked field by field.

Everything here arrives from a place nobody vouches for: a Telegram channel,
a seller's subscription, a QR code photographed off a screen. So nothing is
trusted to be what it claims. Every field is checked against what it can
be - a host is a name or an address, a port is a number in range, an id is
an id - and the result is a small dict of plain values that the config
builders turn into JSON with json.dumps. Link text never reaches a shell, a
file name, or the middle of a string that becomes configuration.

What comes out (one dict per server):

    id        stable across imports: a hash of everything but the name
    name      cleaned of control and direction characters, at most 64 long
    proto     vless vmess trojan ss hysteria2 tuic
    host port where the server is
    uuid password method alter_id cipher flow encryption
    net       tcp ws grpc httpupgrade http xhttp
    path host_header service mode header extra ed
    security  none tls reality
    sni alpn fp pbk sid spx
    insecure  the link asked for certificate checks to be skipped. Asked is
              all it is: the check stays on until the person turns it off
              for this one server (allow_insecure, kept by the store).
    obfs obfs_password congestion udp_relay
    link      the text it came from, so it can be copied back out

engine_for() says which engine can carry it, warnings() what to tell the
person about it, and label() the short name of what it is.
"""

import base64
import binascii
import hashlib
import ipaddress
import json
import re
import unicodedata
import urllib.parse
import uuid

MAX_TEXT = 512 * 1024      # a whole paste, or a whole subscription body
MAX_LINK = 4096            # one link
MAX_PER_SOURCE = 500       # links kept from one paste or one subscription
MAX_NAME = 64
MAX_PATH = 1024
MAX_SECRET = 256
MAX_EXTRA = 4096           # xhttp's `extra`, as JSON text
MAX_QUERY_KEYS = 64

PROTOS = ('vless', 'vmess', 'trojan', 'ss', 'hysteria2', 'tuic')
SCHEMES = {'vless': 'vless', 'vmess': 'vmess', 'trojan': 'trojan',
           'ss': 'ss', 'hysteria2': 'hysteria2', 'hy2': 'hysteria2',
           'tuic': 'tuic'}

NETS = {'': 'tcp', 'tcp': 'tcp', 'raw': 'tcp', 'ws': 'ws', 'websocket': 'ws',
        'grpc': 'grpc', 'gun': 'grpc', 'httpupgrade': 'httpupgrade',
        'http': 'http', 'h2': 'http', 'xhttp': 'xhttp', 'splithttp': 'xhttp'}
# Named so the refusal can say what it is rather than "unknown".
NETS_REFUSED = {'kcp': 'mKCP', 'mkcp': 'mKCP', 'quic': 'QUIC transport',
                'domainsocket': 'domain sockets', 'hysteria': 'Hysteria 1'}

FINGERPRINTS = ('chrome', 'firefox', 'safari', 'ios', 'android', 'edge',
                '360', 'qq', 'random', 'randomized')
ALPNS = ('h2', 'http/1.1', 'h3')
FLOWS = ('', 'xtls-rprx-vision')
VMESS_CIPHERS = ('auto', 'aes-128-gcm', 'chacha20-poly1305', 'none', 'zero')
SS_METHODS = ('aes-128-gcm', 'aes-192-gcm', 'aes-256-gcm',
              'chacha20-ietf-poly1305', 'xchacha20-ietf-poly1305',
              '2022-blake3-aes-128-gcm', '2022-blake3-aes-256-gcm',
              '2022-blake3-chacha20-poly1305')
SS_ALIASES = {'chacha20-poly1305': 'chacha20-ietf-poly1305',
              'xchacha20-poly1305': 'xchacha20-ietf-poly1305'}
XHTTP_MODES = ('auto', 'packet-up', 'stream-up', 'stream-one')
TUIC_CONGESTION = ('bbr', 'cubic', 'new_reno')
TUIC_UDP = ('native', 'quic')

# Shown to the person, so in words. The keys are what the page tells apart.
WARN_PLAIN = 'plain'          # nothing encrypts it on the way to the server
WARN_NOTLS = 'notls'          # encrypted, but no certificate proves the server
WARN_INSECURE = 'insecure'    # the link asks for the certificate check off


class Invalid(ValueError):
    """A link that cannot be used, with the reason in words."""


# -- small checks --------------------------------------------------------------

_LABEL = re.compile(r'^[a-z0-9_](?:[a-z0-9_-]{0,61}[a-z0-9_])?$')
_UUID = re.compile(r'^[0-9a-f]{8}-?[0-9a-f]{4}-?[0-9a-f]{4}-?[0-9a-f]{4}-?'
                   r'[0-9a-f]{12}$', re.I)
_PBK = re.compile(r'^[A-Za-z0-9_-]{43}$')
_SID = re.compile(r'^[0-9a-fA-F]{0,16}$')
_SERVICE = re.compile(r'^[A-Za-z0-9._/-]{0,256}$')
_ENCRYPTION = re.compile(r'^[A-Za-z0-9._-]{1,1024}$')
# Direction marks and overrides: a name carrying them can show as something
# it is not ("evil‮gnp.exe"). Zero-width non-joiner and joiner are not
# among them - Persian spelling needs the first, emoji the second.
_BIDI = set('‎‏‪‫‬‭‮'
            '⁦⁧⁨⁩؜')


def clean_name(text):
    if not isinstance(text, str):
        return ''
    out = []
    for ch in text[:MAX_NAME * 4]:
        if ch in '‌‍':
            out.append(ch)
            continue
        cat = unicodedata.category(ch)
        if ch in _BIDI or cat in ('Cc', 'Cf', 'Cs', 'Co', 'Cn'):
            continue
        out.append(' ' if cat.startswith('Z') else ch)
    return ' '.join(''.join(out).split())[:MAX_NAME].strip()


def host_ok(text, what='server'):
    """A name or an address, normalised - or Invalid."""
    s = str(text or '').strip()
    if s.startswith('[') and s.endswith(']'):
        s = s[1:-1]
    if not s:
        raise Invalid(f'no {what} address')
    try:
        return str(ipaddress.ip_address(s))
    except ValueError:
        pass
    s = s.rstrip('.').lower()
    if any(ord(c) > 127 for c in s):
        try:
            s = s.encode('idna').decode('ascii')
        except UnicodeError:
            raise Invalid(f'the {what} name is not a valid name')
    labels = s.split('.')
    if (len(s) > 253 or len(labels) < 2
            or not all(_LABEL.match(label) for label in labels)
            or labels[-1].isdigit()):
        raise Invalid(f'the {what} name is not a valid name')
    return s


def is_address(host):
    try:
        ipaddress.ip_address(host)
        return True
    except ValueError:
        return False


def port_ok(value):
    try:
        port = int(str(value).strip())
    except (TypeError, ValueError):
        raise Invalid('the port is not a number')
    if not 1 <= port <= 65535:
        raise Invalid('the port is out of range')
    return port


def uuid_ok(text):
    """An id, as a UUID. Xray also takes a short word in its place and maps
    it to a UUID the same way everywhere (version 5, under the all-zero
    namespace), so that is done here too rather than refused."""
    s = str(text or '').strip()
    if _UUID.match(s):
        return str(uuid.UUID(s))
    if 1 <= len(s) <= 30 and all(33 <= ord(c) < 127 for c in s):
        return str(uuid.uuid5(uuid.UUID(int=0), s))
    raise Invalid('the id is not a UUID')


def secret_ok(text, what='password'):
    s = str(text if text is not None else '')
    if not s:
        raise Invalid(f'no {what}')
    if len(s) > MAX_SECRET:
        raise Invalid(f'the {what} is too long')
    if any(unicodedata.category(c) == 'Cc' for c in s):
        raise Invalid(f'the {what} has control characters in it')
    return s


def path_ok(text):
    s = str(text or '')
    if len(s) > MAX_PATH:
        raise Invalid('the path is too long')
    if any(unicodedata.category(c) == 'Cc' or c == ' ' for c in s):
        raise Invalid('the path has spaces or control characters in it')
    if not s.startswith('/'):
        s = '/' + s
    return s


def sni_ok(text):
    """A server name to announce, or '' - an address is not one."""
    s = str(text or '').strip()
    if not s:
        return ''
    host = host_ok(s, 'SNI')
    return '' if is_address(host) else host


def _truthy(value):
    return str(value).strip().lower() in ('1', 'true', 'yes', 'on')


def _b64(text):
    """Base64 in any of the shapes links use: standard or URL-safe, padded or
    not, with line breaks. None when it is not base64 at all."""
    s = ''.join(str(text).split())
    if not s or not re.match(r'^[A-Za-z0-9+/_=-]+$', s):
        return None
    s = s.rstrip('=')
    s += '=' * (-len(s) % 4)
    try:
        if '-' in s or '_' in s:
            return base64.urlsafe_b64decode(s)
        return base64.b64decode(s, validate=False)
    except (binascii.Error, ValueError):
        return None


def _query(text):
    pairs = urllib.parse.parse_qsl(text or '', keep_blank_values=True)
    if len(pairs) > MAX_QUERY_KEYS:
        raise Invalid('the link has too many parameters')
    out = {}
    for k, v in pairs:
        out.setdefault(k.strip(), v.strip())
    return out


def _first(q, *names, default=''):
    for n in names:
        if n in q and q[n] != '':
            return q[n]
    return default


def _split_hostport(text):
    """`host:port` or `[v6]:port`. A port list (hysteria2's port hopping,
    "443,8000-9000") is taken at its first port."""
    s = str(text or '').strip()
    m = re.match(r'^\[([^\]]+)\]:(.+)$', s) or re.match(r'^([^:\[\]]+):(.+)$', s)
    if not m:
        raise Invalid('no port after the server address')
    host, ports = m.group(1), m.group(2)
    first = re.match(r'^(\d{1,5})(?:[,-]\d{1,5})*$', ports)
    if not first:
        raise Invalid('the port is not a number')
    return host, first.group(1)


# -- the shapes links come in ----------------------------------------------------

def _url_parts(link):
    """scheme, userinfo, host, port, query dict, name - for the URL-shaped
    schemes. urllib does the splitting; nothing from it is trusted yet."""
    body, _, frag = link.partition('#')
    parts = urllib.parse.urlsplit(body)
    netloc = parts.netloc
    if '@' not in netloc:
        raise Invalid('no id or password before the server address')
    userinfo, hostport = netloc.rsplit('@', 1)
    host, port = _split_hostport(hostport)
    return (urllib.parse.unquote(userinfo), host, port, _query(parts.query),
            urllib.parse.unquote(frag))


def _common(q):
    """The transport and TLS half that vless, trojan and vmess share."""
    return {
        'net': _first(q, 'type', 'net', default='tcp'),
        'path': _first(q, 'path'),
        'host_header': _first(q, 'host'),
        'service': _first(q, 'serviceName', 'service_name'),
        'mode': _first(q, 'mode'),
        'header': _first(q, 'headerType', 'header_type', default='none'),
        'extra': _first(q, 'extra'),
        'security': _first(q, 'security', default='none'),
        'sni': _first(q, 'sni', 'peer', 'servername'),
        'alpn': _first(q, 'alpn'),
        'fp': _first(q, 'fp', 'fingerprint'),
        'pbk': _first(q, 'pbk', 'publicKey', 'public_key'),
        'sid': _first(q, 'sid', 'shortId', 'short_id'),
        'spx': _first(q, 'spx', 'spiderX'),
        'insecure': _truthy(_first(q, 'allowInsecure', 'allow_insecure',
                                   'insecure', 'skip-cert-verify',
                                   default='0')),
    }


def _vless(link):
    user, host, port, q, name = _url_parts(link)
    r = _common(q)
    r.update(proto='vless', name=name, host=host, port=port, uuid=user,
             flow=_first(q, 'flow'), encryption=_first(q, 'encryption',
                                                       default='none'))
    return r


def _trojan(link):
    user, host, port, q, name = _url_parts(link)
    r = _common(q)
    # Trojan without a word about security is TLS: the protocol has no
    # plain-text form worth the name, and every client assumes it.
    if 'security' not in q:
        r['security'] = 'tls'
    r.update(proto='trojan', name=name, host=host, port=port, password=user)
    return r


def _vmess(link):
    raw = _b64(link[len('vmess://'):].split('#', 1)[0])
    if raw is None or len(raw) > MAX_LINK:
        raise Invalid('a vmess link is base64 of a JSON object, and this is not')
    try:
        m = json.loads(raw.decode('utf-8'))
    except (UnicodeDecodeError, ValueError):
        raise Invalid('a vmess link is base64 of a JSON object, and this is not')
    if not isinstance(m, dict):
        raise Invalid('a vmess link is base64 of a JSON object, and this is not')

    def get(*names, default=''):
        for n in names:
            v = m.get(n)
            if v not in (None, ''):
                return str(v) if not isinstance(v, (dict, list)) else ''
        return default

    net = get('net', default='tcp')
    tls = get('tls', default='none')
    r = {'proto': 'vmess', 'name': get('ps', 'remarks'), 'host': get('add'),
         'port': get('port'), 'uuid': get('id'),
         'alter_id': get('aid', default='0'),
         'cipher': get('scy', 'security', default='auto'),
         'net': net, 'path': get('path'), 'host_header': get('host'),
         'service': '', 'mode': '', 'header': 'none', 'extra': '',
         'security': tls if tls in ('tls', 'reality') else 'none',
         'sni': get('sni', 'peer'), 'alpn': get('alpn'), 'fp': get('fp'),
         'pbk': get('pbk'), 'sid': get('sid'), 'spx': get('spx'),
         'insecure': _truthy(get('allowInsecure', 'insecure',
                                 'skip-cert-verify', default='0'))}
    # In this format `type` is the header for tcp, the mode for grpc and
    # xhttp, and `path` is the service name for grpc.
    kind = get('type')
    n = NETS.get(net.lower(), net.lower())
    if n == 'tcp':
        r['header'] = kind or 'none'
    elif n == 'grpc':
        r['service'], r['path'], r['mode'] = r['path'], '', kind
    elif n == 'xhttp':
        r['mode'] = kind
    return r


def _ss(link):
    body, _, frag = link[len('ss://'):].partition('#')
    name = urllib.parse.unquote(frag)
    body, _, query = body.partition('?')
    body = body.rstrip('/')
    q = _query(query)
    if q.get('plugin'):
        raise Invalid('Shadowsocks plugins (obfs, v2ray-plugin) are not '
                      'supported yet')
    if '@' in body:
        userinfo, hostport = body.rsplit('@', 1)
        userinfo = urllib.parse.unquote(userinfo)
        if ':' not in userinfo:
            decoded = _b64(userinfo)
            if decoded is None:
                raise Invalid('the method and password are not readable')
            userinfo = decoded.decode('utf-8', 'replace')
    else:
        decoded = _b64(body)
        if decoded is None:
            raise Invalid('the method and password are not readable')
        text = decoded.decode('utf-8', 'replace')
        if '@' not in text:
            raise Invalid('no server address in the link')
        userinfo, hostport = text.rsplit('@', 1)
    if ':' not in userinfo:
        raise Invalid('the method and password are not readable')
    method, password = userinfo.split(':', 1)
    host, port = _split_hostport(hostport)
    return {'proto': 'ss', 'name': name, 'host': host, 'port': port,
            'method': method, 'password': password}


def _hysteria2(link):
    link = 'hysteria2://' + link.split('://', 1)[1]
    user, host, port, q, name = _url_parts(link)
    return {'proto': 'hysteria2', 'name': name, 'host': host, 'port': port,
            'password': user, 'security': 'tls',
            'sni': _first(q, 'sni', 'peer'), 'alpn': _first(q, 'alpn'),
            'insecure': _truthy(_first(q, 'insecure', 'allowInsecure',
                                       default='0')),
            'obfs': _first(q, 'obfs'),
            'obfs_password': _first(q, 'obfs-password', 'obfs_password')}


def _tuic(link):
    user, host, port, q, name = _url_parts(link)
    uid, _, password = user.partition(':')
    return {'proto': 'tuic', 'name': name, 'host': host, 'port': port,
            'uuid': uid, 'password': password, 'security': 'tls',
            'sni': _first(q, 'sni'), 'alpn': _first(q, 'alpn', default='h3'),
            'insecure': _truthy(_first(q, 'allow_insecure', 'allowInsecure',
                                       'insecure', default='0')),
            'congestion': _first(q, 'congestion_control', 'congestion',
                                 default='bbr'),
            'udp_relay': _first(q, 'udp_relay_mode', default='native')}


_READERS = {'vless': _vless, 'vmess': _vmess, 'trojan': _trojan, 'ss': _ss,
            'hysteria2': _hysteria2, 'tuic': _tuic}


# -- one shape out of all of them -------------------------------------------------

def _finish(r, link=''):
    """The checked dict, from what a reader pulled out. Every field is
    checked here and only here, whichever format it came from."""
    proto = r['proto']
    cfg = {'proto': proto, 'host': host_ok(r.get('host')),
           'port': port_ok(r.get('port'))}
    cfg['name'] = clean_name(r.get('name'))

    if proto in ('vless', 'vmess'):
        cfg['uuid'] = uuid_ok(r.get('uuid'))
    if proto == 'tuic':
        cfg['uuid'] = uuid_ok(r.get('uuid'))
        cfg['password'] = secret_ok(r.get('password'))
    if proto in ('trojan', 'hysteria2'):
        cfg['password'] = secret_ok(r.get('password'))

    if proto == 'ss':
        method = str(r.get('method') or '').strip().lower()
        method = SS_ALIASES.get(method, method)
        if method not in SS_METHODS:
            raise Invalid(f'Shadowsocks method "{method[:40]}" is not one '
                          'that is supported')
        cfg['method'] = method
        cfg['password'] = secret_ok(r.get('password'))
        cfg['security'] = 'none'
        cfg['net'] = 'tcp'

    if proto == 'vmess':
        try:
            aid = int(str(r.get('alter_id') or '0'))
        except ValueError:
            raise Invalid('alterId is not a number')
        if not 0 <= aid <= 65535:
            raise Invalid('alterId is out of range')
        cfg['alter_id'] = aid
        cipher = str(r.get('cipher') or 'auto').lower()
        cfg['cipher'] = cipher if cipher in VMESS_CIPHERS else 'auto'

    if proto == 'vless':
        enc = str(r.get('encryption') or 'none').strip()
        if enc != 'none' and not _ENCRYPTION.match(enc):
            raise Invalid('the VLESS encryption setting is not readable')
        cfg['encryption'] = enc

    if proto in ('vless', 'vmess', 'trojan'):
        _transport(cfg, r)
        _tls(cfg, r)
        flow = str(r.get('flow') or '').strip().lower() if proto == 'vless' else ''
        if flow not in FLOWS:
            raise Invalid(f'flow "{flow[:40]}" is not supported')
        # Vision is TLS or Reality over plain TCP and nothing else; a link
        # that asks for it over anything else is a link that was made wrong,
        # and both cores refuse it outright.
        if flow and (cfg['net'] != 'tcp' or cfg['security'] == 'none'):
            flow = ''
        cfg['flow'] = flow

    if proto in ('hysteria2', 'tuic'):
        cfg['net'] = 'quic'
        cfg['security'] = 'tls'
        cfg['sni'] = sni_ok(r.get('sni'))
        cfg['alpn'] = _alpn(r.get('alpn'))
        cfg['insecure'] = bool(r.get('insecure'))

    if proto == 'hysteria2':
        obfs = str(r.get('obfs') or '').strip().lower()
        if obfs and obfs != 'salamander':
            raise Invalid(f'Hysteria2 obfuscation "{obfs[:40]}" is not supported')
        cfg['obfs'] = obfs
        cfg['obfs_password'] = (secret_ok(r.get('obfs_password'),
                                          'obfuscation password')
                                if obfs else '')

    if proto == 'tuic':
        cc = str(r.get('congestion') or 'bbr').lower()
        cfg['congestion'] = cc if cc in TUIC_CONGESTION else 'bbr'
        relay = str(r.get('udp_relay') or 'native').lower()
        cfg['udp_relay'] = relay if relay in TUIC_UDP else 'native'
        if not cfg['alpn']:
            cfg['alpn'] = ['h3']

    if not cfg['name']:
        cfg['name'] = f'{proto} {cfg["host"]}'
    if link:
        cfg['link'] = link
    cfg['id'] = config_id(cfg)
    return cfg


def _alpn(value):
    if isinstance(value, (list, tuple)):
        items = value
    else:
        items = str(value or '').split(',')
    out = []
    for item in items:
        a = str(item).strip().lower()
        if a in ALPNS and a not in out:
            out.append(a)
    return out


def _transport(cfg, r):
    raw = str(r.get('net') or 'tcp').strip().lower()
    if raw in NETS_REFUSED:
        raise Invalid(f'{NETS_REFUSED[raw]} is not supported')
    if raw not in NETS:
        raise Invalid(f'transport "{raw[:40]}" is not supported')
    net = NETS[raw]
    cfg['net'] = net
    cfg['path'] = ''
    cfg['host_header'] = ''
    cfg['service'] = ''
    cfg['mode'] = ''
    cfg['header'] = 'none'
    cfg['extra'] = None
    cfg['ed'] = 0
    header_host = str(r.get('host_header') or '').strip()
    if header_host:
        # A comma list is allowed by the format; the first name is the one
        # that is sent.
        header_host = header_host.split(',')[0].strip()
        cfg['host_header'] = host_ok(header_host, 'Host header')
    if net in ('ws', 'httpupgrade', 'http', 'xhttp'):
        path = path_ok(r.get('path') or '/')
        if net == 'ws':
            # "?ed=2048" on a WebSocket path is Xray's way of saying early
            # data; sing-box takes it as two settings instead.
            base, _, query = path.partition('?')
            ed = _query(query).get('ed', '')
            if ed:
                try:
                    cfg['ed'] = max(0, min(int(ed), 65535))
                except ValueError:
                    cfg['ed'] = 0
                path = base or '/'
        cfg['path'] = path
    if net == 'grpc':
        service = str(r.get('service') or r.get('path') or '').strip().strip('/')
        if not _SERVICE.match(service):
            raise Invalid('the gRPC service name is not readable')
        cfg['service'] = service
        mode = str(r.get('mode') or '').strip().lower()
        cfg['mode'] = 'multi' if mode == 'multi' else 'gun'
    if net == 'xhttp':
        mode = str(r.get('mode') or 'auto').strip().lower()
        cfg['mode'] = mode if mode in XHTTP_MODES else 'auto'
        extra = r.get('extra')
        if extra:
            if isinstance(extra, str):
                if len(extra) > MAX_EXTRA:
                    raise Invalid('the xhttp extra settings are too long')
                try:
                    extra = json.loads(extra)
                except ValueError:
                    raise Invalid('the xhttp extra settings are not JSON')
            if not isinstance(extra, dict):
                raise Invalid('the xhttp extra settings are not a JSON object')
            cfg['extra'] = extra
    if net == 'tcp':
        header = str(r.get('header') or 'none').strip().lower()
        if header not in ('none', 'http'):
            raise Invalid(f'TCP header "{header[:40]}" is not supported')
        cfg['header'] = header
        if header == 'http':
            cfg['path'] = path_ok(r.get('path') or '/')


def _tls(cfg, r):
    sec = str(r.get('security') or 'none').strip().lower()
    if sec in ('', 'none'):
        sec = 'none'
    if sec not in ('none', 'tls', 'reality'):
        raise Invalid(f'security "{sec[:40]}" is not supported')
    cfg['security'] = sec
    cfg['sni'] = ''
    cfg['alpn'] = []
    cfg['fp'] = ''
    cfg['pbk'] = ''
    cfg['sid'] = ''
    cfg['spx'] = ''
    cfg['insecure'] = False
    if sec == 'none':
        return
    cfg['sni'] = sni_ok(r.get('sni'))
    cfg['alpn'] = _alpn(r.get('alpn'))
    fp = str(r.get('fp') or '').strip().lower()
    if fp == 'randomizednoalpn':
        fp = 'randomized'
    cfg['fp'] = fp if fp in FINGERPRINTS else ('chrome' if fp else '')
    if sec == 'tls':
        cfg['insecure'] = bool(r.get('insecure'))
    if sec == 'reality':
        pbk = str(r.get('pbk') or '').strip().rstrip('=')
        if not _PBK.match(pbk):
            raise Invalid('the Reality public key is missing or not readable')
        sid = str(r.get('sid') or '').strip()
        if not _SID.match(sid):
            raise Invalid('the Reality short id is not readable')
        cfg['pbk'] = pbk
        cfg['sid'] = sid.lower()
        spx = str(r.get('spx') or '')
        cfg['spx'] = path_ok(spx) if spx else ''
        # Reality is a browser's handshake or it is nothing; both cores
        # refuse it without a fingerprint.
        cfg['fp'] = cfg['fp'] or 'chrome'
        if not cfg['sni']:
            raise Invalid('a Reality link needs the name it borrows (sni)')


# -- what the rest of the app asks ---------------------------------------------

_ID_SKIP = ('id', 'name', 'link', 'allow_insecure', 'source', 'last')


def config_id(cfg):
    canon = {k: v for k, v in cfg.items() if k not in _ID_SKIP}
    text = json.dumps(canon, sort_keys=True, ensure_ascii=True)
    return hashlib.sha256(text.encode('ascii')).hexdigest()[:16]


def engine_for(cfg):
    """'xray' for what only Xray carries, otherwise 'sing-box'."""
    if cfg.get('net') == 'xhttp':
        return 'xray'
    if cfg.get('net') == 'tcp' and cfg.get('header') == 'http':
        return 'xray'
    if cfg.get('net') == 'grpc' and cfg.get('mode') == 'multi':
        return 'xray'
    if cfg.get('proto') == 'vless' and cfg.get('encryption') not in (None, '', 'none'):
        return 'xray'
    return 'sing-box'


def warnings(cfg):
    out = []
    proto, sec = cfg.get('proto'), cfg.get('security')
    if proto in ('vless', 'trojan') and sec == 'none':
        own_crypto = (proto == 'vless'
                      and cfg.get('encryption') not in (None, '', 'none'))
        out.append(WARN_NOTLS if own_crypto else WARN_PLAIN)
    elif proto == 'vmess' and sec == 'none':
        out.append(WARN_PLAIN if cfg.get('cipher') in ('none', 'zero')
                   else WARN_NOTLS)
    elif proto == 'ss':
        out.append(WARN_NOTLS)
    if cfg.get('insecure'):
        out.append(WARN_INSECURE)
    return out


_PROTO_NAMES = {'vless': 'VLESS', 'vmess': 'VMess', 'trojan': 'Trojan',
                'ss': 'SS', 'hysteria2': 'Hysteria2', 'tuic': 'TUIC'}
_NET_NAMES = {'ws': 'WS', 'grpc': 'gRPC', 'httpupgrade': 'HTTPUpgrade',
              'http': 'H2', 'xhttp': 'xhttp'}


def label(cfg):
    parts = [_PROTO_NAMES.get(cfg.get('proto'), '?')]
    if cfg.get('net') in _NET_NAMES:
        parts.append(_NET_NAMES[cfg['net']])
    if cfg.get('security') == 'reality':
        parts.append('Reality')
    elif cfg.get('security') == 'tls' and cfg.get('proto') not in ('hysteria2', 'tuic'):
        parts.append('TLS')
    if cfg.get('flow'):
        parts.append('Vision')
    return '·'.join(parts)


def masked(cfg_or_link):
    """What may be said about one in a log or a refusal: the kind and the
    host, never an id or a password."""
    if isinstance(cfg_or_link, dict):
        return f'{cfg_or_link.get("proto")}://{cfg_or_link.get("host")}'
    scheme = str(cfg_or_link).split('://', 1)[0][:12].lower()
    return f'{scheme}://…' if scheme else '…'


# -- whole pastes ------------------------------------------------------------------

def parse_link(link):
    link = str(link or '').strip()
    if len(link) > MAX_LINK:
        raise Invalid('the link is too long')
    scheme = link.split('://', 1)[0].lower() if '://' in link else ''
    proto = SCHEMES.get(scheme)
    if not proto:
        raise Invalid(f'"{scheme[:12]}://" links are not supported'
                      if scheme else 'not a link')
    if any(unicodedata.category(c) == 'Cc' for c in link):
        raise Invalid('the link has control characters in it')
    try:
        raw = _READERS[proto](link)
    except Invalid:
        raise
    except (ValueError, KeyError, IndexError, UnicodeError) as e:
        raise Invalid(f'the link is not readable ({type(e).__name__})')
    return _finish(raw, link)


def parse_text(text, limit=MAX_PER_SOURCE):
    """Every link in a paste or a subscription body.

    Returns (configs, problems). Problems name the line and the reason, and
    never the link itself past its scheme - a refused link still carries a
    password. Duplicates (by id) are dropped quietly; more than `limit` is
    cut there and said.
    """
    if not isinstance(text, str):
        raise Invalid('nothing to read')
    if len(text) > MAX_TEXT:
        raise Invalid('that is more than this app reads at once (512 KB)')
    text = text.lstrip('﻿').strip()
    if not text:
        raise Invalid('that is empty')
    if text.startswith('{'):
        try:
            data = json.loads(text)
        except ValueError:
            data = None
        if isinstance(data, dict) and isinstance(data.get('outbounds'), list):
            return from_singbox(data, limit)
    tokens = text.split()
    if not any('://' in t for t in tokens):
        decoded = _b64(text)
        if decoded is not None:
            try:
                inner = decoded.decode('utf-8')
            except UnicodeDecodeError:
                inner = ''
            if '://' in inner:
                tokens = inner.split()
    return _collect(((t, lambda t=t: parse_link(t)) for t in tokens), limit)


def _collect(items, limit):
    configs, problems, seen = [], [], set()
    for n, (what, make) in enumerate(items, 1):
        if len(configs) >= limit:
            problems.append({'n': n, 'what': '', 'why':
                             f'only the first {limit} are kept'})
            break
        try:
            cfg = make()
        except Invalid as e:
            problems.append({'n': n, 'what': masked(what) if isinstance(what, str)
                             else str(what), 'why': str(e)})
            continue
        if cfg['id'] in seen:
            continue
        seen.add(cfg['id'])
        configs.append(cfg)
    return configs, problems


# -- sing-box's own JSON, as some subscriptions serve it --------------------------

_SB_TYPES = {'vless': 'vless', 'vmess': 'vmess', 'trojan': 'trojan',
             'shadowsocks': 'ss', 'hysteria2': 'hysteria2', 'tuic': 'tuic'}


def from_singbox(data, limit=MAX_PER_SOURCE):
    outbounds = [o for o in data.get('outbounds', [])[:5000]
                 if isinstance(o, dict) and o.get('type') in _SB_TYPES]

    def make(o):
        def s(key, default=''):
            v = o.get(key, default)
            return v if isinstance(v, (str, int)) else default
        tls = o.get('tls') if isinstance(o.get('tls'), dict) else {}
        reality = tls.get('reality') if isinstance(tls.get('reality'), dict) else {}
        utls = tls.get('utls') if isinstance(tls.get('utls'), dict) else {}
        tr = o.get('transport') if isinstance(o.get('transport'), dict) else {}
        headers = tr.get('headers') if isinstance(tr.get('headers'), dict) else {}
        obfs = o.get('obfs') if isinstance(o.get('obfs'), dict) else {}
        host_header = headers.get('Host') or tr.get('host') or ''
        if isinstance(host_header, list):
            host_header = host_header[0] if host_header else ''
        security = 'none'
        if tls.get('enabled'):
            security = 'reality' if reality.get('enabled') else 'tls'
        r = {'proto': _SB_TYPES[o['type']], 'name': s('tag'),
             'host': s('server'), 'port': s('server_port'),
             'uuid': s('uuid'), 'password': s('password'),
             'method': s('method'), 'alter_id': s('alter_id', 0),
             'cipher': s('security', 'auto'), 'flow': s('flow'),
             'net': tr.get('type') or 'tcp', 'path': tr.get('path') or '',
             'host_header': host_header if isinstance(host_header, str) else '',
             'service': tr.get('service_name') or '', 'mode': '',
             'header': 'none', 'extra': '', 'security': security,
             'sni': tls.get('server_name') or '', 'alpn': tls.get('alpn') or [],
             'fp': utls.get('fingerprint') or '',
             'pbk': reality.get('public_key') or '',
             'sid': reality.get('short_id') or '', 'spx': '',
             'insecure': bool(tls.get('insecure')),
             'obfs': obfs.get('type') or '',
             'obfs_password': obfs.get('password') or '',
             'congestion': s('congestion_control', 'bbr'),
             'udp_relay': s('udp_relay_mode', 'native')}
        if r['proto'] in ('hysteria2', 'tuic'):
            r['security'] = 'tls'
        for k in ('path', 'service', 'sni'):
            if not isinstance(r[k], str):
                r[k] = ''
        return _finish(r)

    return _collect(((f'{o.get("type")}://', lambda o=o: make(o))
                     for o in outbounds), limit)

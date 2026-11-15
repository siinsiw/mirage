"""Fetching a subscription: only when asked, through the connection that is
up when there is one, and read as untrusted from the first byte.

The address is the seller's key to the list, so it is checked like any other
input (http or https, a real host, no password in it) and never logged. The
answer is capped at 2 MB and three redirects, and a redirect from https to
plain http is refused - that is the one way a line could swap the list for
one of its own without the person ever typing http.

Plain http itself is allowed, because some panels serve nothing else; the
page marks such a subscription in red, since the line can read and change
what it serves. Fetched through a connection, the line sees only that.

Two headers most panels send are kept:
    subscription-userinfo   upload=..; download=..; total=..; expire=..
    profile-title           the list's name, sometimes base64:...
"""

import re
import urllib.error
import urllib.parse
import urllib.request

from . import links

MAX_BODY = 2 * 1024 * 1024
MAX_URL = 2048
MAX_REDIRECTS = 3
TIMEOUT = 15
USER_AGENT = 'Mirage'


class Refused(ValueError):
    pass


def check_url(url):
    url = str(url or '').strip()
    if not url or len(url) > MAX_URL:
        raise Refused('that is not a subscription address')
    if any(ord(c) < 33 for c in url):
        raise Refused('the address has spaces or control characters in it')
    try:
        parts = urllib.parse.urlsplit(url)
    except ValueError:
        raise Refused('that is not a subscription address')
    if parts.scheme not in ('http', 'https'):
        raise Refused('a subscription address starts with https:// or http://')
    if parts.username or parts.password:
        raise Refused('a subscription address with a password in it is not taken')
    try:
        links.host_ok(parts.hostname or '', 'subscription')
        if parts.port is not None and not 1 <= parts.port <= 65535:
            raise ValueError
    except (links.Invalid, ValueError):
        raise Refused('the subscription host is not a valid name or address')
    return url


class _Redirects(urllib.request.HTTPRedirectHandler):
    max_redirections = MAX_REDIRECTS

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        new = check_url(urllib.parse.urljoin(req.full_url, newurl))
        if (urllib.parse.urlsplit(req.full_url).scheme == 'https'
                and urllib.parse.urlsplit(new).scheme != 'https'):
            raise Refused('the subscription redirected from https to plain '
                          'http, which is refused')
        return super().redirect_request(req, fp, code, msg, headers, new)


def userinfo(value):
    """subscription-userinfo as numbers, or None."""
    if not value:
        return None
    out = {}
    for part in str(value).split(';'):
        k, _, v = part.strip().partition('=')
        if k.strip().lower() in ('upload', 'download', 'total', 'expire'):
            try:
                out[k.strip().lower()] = max(0, int(float(v.strip())))
            except ValueError:
                continue
    return out or None


def title(value):
    if not value:
        return ''
    value = str(value).strip()
    if value.lower().startswith('base64:'):
        raw = links._b64(value[7:])
        value = raw.decode('utf-8', 'replace') if raw else ''
    return links.clean_name(urllib.parse.unquote(value))


def fetch(url, proxy=None, opener=None, user_agent=None):
    """(configs, problems, info, name). proxy is 'host:port' of an HTTP proxy
    to go through - the connection that is up - or None for straight out."""
    url = check_url(url)
    if opener is None:
        handlers = [_Redirects(),
                    urllib.request.ProxyHandler(
                        {'http': f'http://{proxy}', 'https': f'http://{proxy}'}
                        if proxy else {})]
        opener = urllib.request.build_opener(*handlers)
    req = urllib.request.Request(url, headers={'User-Agent': user_agent or USER_AGENT,
                                               'Accept': '*/*'})
    try:
        with opener.open(req, timeout=TIMEOUT) as r:
            body = r.read(MAX_BODY + 1)
            headers = r.headers
    except Refused:
        raise
    except urllib.error.HTTPError as e:
        raise Refused(f'the subscription answered {e.code}')
    except (urllib.error.URLError, OSError) as e:
        reason = getattr(e, 'reason', e)
        raise Refused(f'the subscription could not be reached ({reason})'[:200])
    if len(body) > MAX_BODY:
        raise Refused('the subscription sent more than 2 MB')
    text = body.decode('utf-8', 'replace')
    try:
        configs, problems = links.parse_text(text)
    except links.Invalid as e:
        raise Refused(f'nothing usable in the subscription ({e})')
    info = userinfo(headers.get('subscription-userinfo'))
    name = title(headers.get('profile-title'))
    if not name:
        name = re.sub(r'^www\.', '', urllib.parse.urlsplit(url).hostname or '')
    return configs, problems, info, name


def plain_http(url):
    return urllib.parse.urlsplit(url).scheme == 'http'

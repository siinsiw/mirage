"""Is there a newer Mirage than this copy?

Asked only when somebody presses the button for it - nothing here runs on
its own, and nothing about this machine is sent: one GET for the public list
of releases, with a User-Agent that names the app and nothing else.

Why it is worth a button: most copies of this app arrive re-shared, not from
the releases page, and a copy that never hears of a newer one stays on the
build it came with - including builds from before a security fix.

The request goes the way a browser's would: through the system proxy when
there is one, which while connected is this app's own, so it leaves through
the tunnel; then straight out, for when that proxy is set but not running.
"""

import json
import re
import urllib.request

REPO = 'https://github.com/siinsiw/mirage'
RELEASES = 'https://api.github.com/repos/siinsiw/mirage/releases?per_page=30'
PAGE = REPO + '/releases'
UA = 'Mirage-update-check'
TIMEOUT = 15

# Windows' own release tags (desktop-release.yml). Android's are android-v*,
# and are not this app's to offer.
TAG = re.compile(r'^desktop-v(\d+(?:\.\d+){0,3})$')

_DIRECT = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def parse(version):
    """'1.2.3' (or 'desktop-v1.2.3') as (1, 2, 3); None for 'dev' and for
    anything that is not a plain release number."""
    text = str(version or '').strip()
    m = TAG.match(text)
    if m:
        text = m.group(1)
    if text.lower().startswith('v'):
        text = text[1:]
    if not re.fullmatch(r'\d+(?:\.\d+){0,3}', text):
        return None
    numbers = tuple(int(n) for n in text.split('.'))
    return numbers + (0,) * (4 - len(numbers))


def newest(releases):
    """The newest published desktop release in a GitHub releases list, as
    (version tuple, tag, page URL) - or None."""
    best = None
    for r in releases if isinstance(releases, list) else []:
        if not isinstance(r, dict) or r.get('draft') or r.get('prerelease'):
            continue
        tag = str(r.get('tag_name') or '')
        if not TAG.match(tag):
            continue
        version = parse(tag)
        url = str(r.get('html_url') or PAGE)
        if not url.startswith('https://github.com/'):
            url = PAGE
        if version and (best is None or version > best[0]):
            best = (version, tag, url)
    return best


def _fetch(timeout=TIMEOUT):
    request = urllib.request.Request(
        RELEASES, headers={'Accept': 'application/vnd.github+json',
                           'User-Agent': UA})
    trouble = None
    for opener in (urllib.request.urlopen, _DIRECT.open):
        try:
            with opener(request, timeout=timeout) as response:
                return json.loads(response.read().decode('utf-8', 'replace'))
        except (OSError, ValueError) as e:
            trouble = e
    raise OSError(f'could not reach GitHub: {trouble}')


def check(current, fetch=_fetch):
    """What the button says. Never raises."""
    try:
        releases = fetch()
    except Exception as e:
        return {'ok': False, 'current': current,
                'error': f'Could not check for a newer version ({e}).'}
    found = newest(releases)
    if not found:
        return {'ok': False, 'current': current,
                'error': 'GitHub did not list a Windows release to compare '
                         'with.'}
    latest, tag, url = found
    mine = parse(current)
    return {'ok': True, 'current': current,
            'latest': TAG.match(tag).group(1),
            'tag': tag, 'url': url,
            # A copy run from source has no number of its own, so it is
            # never told it is behind.
            'newer': bool(mine and latest > mine)}

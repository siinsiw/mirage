#!/usr/bin/env python3
"""The newer-version check, against release lists written here.

    python app/updates-test.py

Offline: the list GitHub would answer with is handed in.
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import updates                                               # noqa: E402

failed = []


def check(name, got, want):
    if got == want:
        print(f'  ok    {name}')
    else:
        print(f'  FAIL  {name}\n          got  {got!r}\n          want {want!r}')
        failed.append(name)


LIST = [
    {'tag_name': 'android-v9.0.0', 'html_url': 'https://github.com/x/a'},
    {'tag_name': 'desktop-v1.10.0', 'draft': True},
    {'tag_name': 'desktop-v1.9.2', 'prerelease': True},
    {'tag_name': 'desktop-v1.4.0',
     'html_url': 'https://github.com/siinsiw/mirage/releases/tag/desktop-v1.4.0'},
    {'tag_name': 'desktop-v1.3.12', 'html_url': 'https://evil.example/x'},
]

print('reading versions')
check('a plain number', updates.parse('1.2.3'), (1, 2, 3, 0))
check('a tag', updates.parse('desktop-v1.10'), (1, 10, 0, 0))
check('a copy from source has none', updates.parse('dev'), None)
check('a CI build number is not a release', updates.parse('0.0.0-dev.12'), None)

print('choosing the newest')
best = updates.newest(LIST)
check('drafts, pre-releases and Android are not offered', best[1],
      'desktop-v1.4.0')
check('1.4.0 beats 1.3.12', best[0] > updates.parse('1.3.12'), True)

print('what the button says')
got = updates.check('1.3.12', fetch=lambda: LIST)
check('behind', (got['ok'], got['newer'], got['latest']), (True, True, '1.4.0'))
check('a link only to GitHub', got['url'].startswith('https://github.com/'),
      True)
got = updates.check('1.4.0', fetch=lambda: LIST)
check('up to date', got['newer'], False)
got = updates.check('dev', fetch=lambda: LIST)
check('from source, never told it is behind', got['newer'], False)
evil = updates.newest([{'tag_name': 'desktop-v2.0',
                        'html_url': 'https://evil.example/x'}])
check('a link off GitHub becomes the releases page', evil[2], updates.PAGE)


def down():
    raise OSError('no route')


got = updates.check('1.0', fetch=down)
check('no answer is said, not raised', (got['ok'], 'no route' in got['error']),
      (False, True))
check('an empty list is said too', updates.check('1.0', fetch=lambda: [])['ok'],
      False)

print()
print(', '.join(failed) + ' failed' if failed else 'all pass')
raise SystemExit(1 if failed else 0)

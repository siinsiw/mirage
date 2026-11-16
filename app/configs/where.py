"""Which country a config is in, before anything has tested it.

A test reads the exit's country off Cloudflare's trace, and that answer
always wins. Until then the list would show a row of blank flags, and the
country view would have nothing to group by - so the country is read from
what the seller wrote: a flag emoji, a country's name (English or Persian),
a city configs are hosted in, or a two-letter code standing on its own.

A guess, said as one: the listing marks where each country came from. It
also decides which configs a country's Connect races, so it leans towards
saying nothing - a config left under "Country not known" is still in the
list, while one filed under the wrong country is a wrong connection.

Read in one pass over the name, earliest mention first: "Turkey via
Germany" is Turkey. Iran is the exception - "IR-DE", "Iran to NL" name the
way in, not the exit - so Iran is only the answer when nothing else is.
"""

import functools
import re

import countries

# Persian names, as sellers write them. A trailing ی is allowed after one
# ("آلمانی", German) and a digit or a mark may follow ("هلند_1", "آلمان2").
FA = {
    'آلمان': 'de', 'هلند': 'nl', 'فرانسه': 'fr', 'انگلیس': 'uk',
    'انگلستان': 'uk', 'بریتانیا': 'uk', 'آمریکا': 'us', 'امریکا': 'us',
    'ترکیه': 'tr', 'فنلاند': 'fi', 'سوئد': 'se', 'سوئیس': 'ch',
    'اتریش': 'at', 'ایتالیا': 'it', 'اسپانیا': 'es', 'لهستان': 'pl',
    'روسیه': 'ru', 'کانادا': 'ca', 'ژاپن': 'jp', 'سنگاپور': 'sg',
    'هنگ کنگ': 'hk', 'هنگ‌کنگ': 'hk', 'هنگکنگ': 'hk', 'امارات': 'ae',
    'دبی': 'ae', 'ارمنستان': 'am', 'گرجستان': 'ge', 'آذربایجان': 'az',
    'قطر': 'qa', 'عربستان': 'sa', 'بحرین': 'bh', 'کویت': 'kw',
    'هندوستان': 'in', 'استرالیا': 'au', 'برزیل': 'br', 'رومانی': 'ro',
    'بلغارستان': 'bg', 'مجارستان': 'hu', 'جمهوری چک': 'cz', 'نروژ': 'no',
    'دانمارک': 'dk', 'بلژیک': 'be', 'ایرلند': 'ie', 'پرتغال': 'pt',
    'یونان': 'gr', 'اوکراین': 'ua', 'لیتوانی': 'lt', 'لتونی': 'lv',
    'استونی': 'ee', 'قزاقستان': 'kz', 'مالزی': 'my', 'کره جنوبی': 'kr',
    'تایوان': 'tw', 'اندونزی': 'id', 'ایران': 'ir', 'قبرس': 'cy',
    'مولداوی': 'md', 'صربستان': 'rs', 'لوکزامبورگ': 'lu',
}

# Other names for a country, kept to words that mean nothing else.
# ("United", "States", "Republic" would each claim far too much.)
ALIASES = {
    'United States': 'us', 'United Kingdom': 'uk', 'USA': 'us', 'UAE': 'ae',
    'Britain': 'uk', 'England': 'uk', 'Holland': 'nl', 'Dutch': 'nl',
    'Swiss': 'ch', 'Turkey': 'tr', 'Turkiye': 'tr', 'Russia': 'ru',
    'South Korea': 'kr', 'Korea': 'kr', 'Emirates': 'ae', 'Czechia': 'cz',
    'Czech': 'cz', 'Deutschland': 'de', 'Iran': 'ir', 'Hongkong': 'hk',
}

# Cities config names carry, beyond the providers' table: the usual hosting
# towns and the one city of each country Windscribe lists once.
CITIES = {
    'Amsterdam': 'nl', 'Rotterdam': 'nl', 'Helsinki': 'fi', 'Stockholm': 'se',
    'Istanbul': 'tr', 'Ankara': 'tr', 'Izmir': 'tr', 'Vienna': 'at',
    'Zurich': 'ch', 'Geneva': 'ch', 'Paris': 'fr', 'London': 'uk',
    'Dublin': 'ie', 'Oslo': 'no', 'Copenhagen': 'dk',
    'Warsaw': 'pl', 'Prague': 'cz', 'Budapest': 'hu', 'Bucharest': 'ro',
    'Sofia': 'bg', 'Moscow': 'ru', 'Kyiv': 'ua', 'Riga': 'lv',
    'Vilnius': 'lt', 'Tallinn': 'ee', 'Tbilisi': 'ge', 'Yerevan': 'am',
    'Baku': 'az', 'Dubai': 'ae', 'Doha': 'qa', 'Tokyo': 'jp', 'Osaka': 'jp',
    'Seoul': 'kr', 'Singapore': 'sg', 'Hong Kong': 'hk', 'Mumbai': 'in',
    'Toronto': 'ca', 'Montreal': 'ca', 'Vancouver': 'ca', 'Sydney': 'au',
    'Milan': 'it', 'Madrid': 'es', 'Lisbon': 'pt', 'Athens': 'gr',
    'Brussels': 'be', 'Nuremberg': 'de', 'Falkenstein': 'de', 'Munich': 'de',
    'Dusseldorf': 'de', 'Hamburg': 'de', 'Tehran': 'ir', 'Gravelines': 'fr',
    'Roubaix': 'fr', 'Strasbourg': 'fr', 'Ashburn': 'us', 'Hillsboro': 'us',
}

# Two capitals read as a country only when they are not something else
# config names say: a volume (GB), Cloudflare, WebSocket, an address, an id,
# "NO TLS", "MY", "IT", "AT&T", "BE", "AM"... The flag or the country's name
# still find these countries.
NOT_CODES = {'gb', 'cf', 'ws', 'ip', 'tv', 'me', 'io', 'ai', 'cc', 'to', 'gg',
             'so', 'do', 'is', 'or', 'an', 'as', 'by', 'on', 'tm', 'fm', 'la',
             'no', 'id', 'my', 'it', 'at', 'be', 'am', 'in', 'pm', 'ms', 'ok',
             'go'}

_FLAG = re.compile('([\U0001F1E6-\U0001F1FF])([\U0001F1E6-\U0001F1FF])')
# A code on its own: no letter or digit against it ("DE-1", "[NL]", "US
# New York"), so "100GB" and "30Day" are not read.
_CODE = re.compile(r'(?<![A-Za-z0-9])([A-Z]{2})(?![A-Za-z])')


def _latin_names():
    names = {}
    for code, name in countries.NAMES.items():
        names[name.lower()] = code
    for name, code in ALIASES.items():
        names[name.lower()] = code
    for code, cities in countries.WINDSCRIBE_CITIES.items():
        for city in cities:
            if len(city) > 3:
                names[city.lower()] = code
    for city, code in CITIES.items():
        names[city.lower()] = code
    return names


def _alternation(names):
    return '|'.join(re.escape(n) for n in sorted(names, key=len, reverse=True))


_LATIN = _latin_names()
# Letters only on either side: digits and _ may touch ("Germany2",
# "Frankfurt01", "@v2_Germany_x"), another letter may not ("Parisian").
_LATIN_RX = re.compile(r'(?<![a-z])(' + _alternation(_LATIN) + r')(?![a-z])')
_FA_RX = re.compile(r'(?<![؀-ۿ])(' + _alternation(FA)
                    + r')(?!(?!ی)[؀-ۿ])')


@functools.lru_cache(maxsize=4096)
def guess(name):
    """A country code from a config's name, or '' when it says none."""
    text = str(name or '')
    if not text:
        return ''
    found = []          # (position, code)
    for m in _FLAG.finditer(text):
        code = ''.join(chr(ord(ch) - 0x1F1E6 + ord('a')) for ch in m.groups())
        found.append((m.start(), countries.canon(code)))
    low = text.lower()
    for m in _LATIN_RX.finditer(low):
        found.append((m.start(), _LATIN[m.group(1)]))
    for m in _FA_RX.finditer(text):
        found.append((m.start(), FA[m.group(1)]))
    for m in _CODE.finditer(text):
        code = m.group(1).lower()
        if code in NOT_CODES:
            continue
        if code in countries.NAMES or code in ('uk', 'ir'):
            found.append((m.start(), code))
    if not found:
        return ''
    found.sort()
    for _, code in found:
        if code != 'ir':
            return countries.canon(code)
    return 'ir'


def of(cfg):
    """(country, how): the test's answer, else the name's, else ('', '')."""
    last = cfg.get('last') or {}
    tested = (last.get('country') or '').lower()
    if tested:
        return countries.canon(tested), 'test'
    named = guess(cfg.get('name'))
    if named:
        return named, 'name'
    return '', ''


def name(code):
    """A country's name for the list. countries.py names the providers'
    countries; a config can be anywhere, Iran included."""
    code = (code or '').lower()
    return {'ir': 'Iran'}.get(code) or countries.country_name(code)

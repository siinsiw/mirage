/* A stand-in for the Python side of Relay, so the real window (app/ui) runs
   in a browser with nothing behind it. Every answer here is sample data:
   nothing connects, nothing is written, no account is real.

   Loaded before app.js. Methods it does not know answer {ok: true}, so a
   pane that asks for something unexpected still opens instead of throwing.

   It also takes orders from the review page around it (postMessage
   {relay: 'go', to: '<section>'}) and puts the window into that state. */

(function () {
  'use strict';

  const now = () => Date.now() / 1000;
  const later = (ms, fn) => setTimeout(fn, ms);
  const emit = (name, payload) => {
    const fn = window['on' + name];
    if (typeof fn === 'function') {
      try { fn(payload || {}); } catch (e) { console.error('[mock] on' + name, e); }
    }
  };

  /* ------------------------------------------------------------ catalogue */

  // [code, name, continent, alias, [[city, surfshark, windscribe, ms|null|'x']]]
  // ms null = not timed yet, 'x' = timed and refused.
  const RAW = [
    ['nl', 'Netherlands', 'Europe', 'holland dutch', [['Amsterdam', 6, 3, 212], ['Rotterdam', 0, 2, 248]]],
    ['de', 'Germany', 'Europe', 'deutschland', [['Frankfurt', 6, 4, 226], ['Berlin', 3, 2, 259]]],
    ['fi', 'Finland', 'Europe', '', [['Helsinki', 3, 2, 241]]],
    ['se', 'Sweden', 'Europe', '', [['Stockholm', 3, 2, 264]]],
    ['ch', 'Switzerland', 'Europe', 'swiss', [['Zurich', 3, 2, 271]]],
    ['at', 'Austria', 'Europe', '', [['Vienna', 3, 1, 282]]],
    ['fr', 'France', 'Europe', '', [['Paris', 4, 2, 296], ['Marseille', 2, 0, 341]]],
    ['uk', 'United Kingdom', 'Europe', 'uk england britain', [['London', 6, 3, 304], ['Manchester', 2, 1, 352]]],
    ['pl', 'Poland', 'Europe', '', [['Warsaw', 3, 1, 318]]],
    ['it', 'Italy', 'Europe', '', [['Milan', 3, 2, 337]]],
    ['es', 'Spain', 'Europe', '', [['Madrid', 3, 1, 389]]],
    ['ro', 'Romania', 'Europe', '', [['Bucharest', 2, 1, 'x']]],
    ['no', 'Norway', 'Europe', '', [['Oslo', 2, 1, null]]],
    ['tr', 'Turkey', 'Middle East', 'turkiye', [['Istanbul', 3, 2, 188]]],
    ['ae', 'United Arab Emirates', 'Middle East', 'uae emirates dubai', [['Dubai', 3, 1, 174]]],
    ['il', 'Israel', 'Middle East', '', [['Tel Aviv', 2, 0, null]]],
    ['sg', 'Singapore', 'Asia', '', [['Singapore', 4, 2, 412]]],
    ['jp', 'Japan', 'Asia', '', [['Tokyo', 4, 2, 486]]],
    ['hk', 'Hong Kong', 'Asia', '', [['Hong Kong', 3, 1, 'x']]],
    ['in', 'India', 'Asia', '', [['Mumbai', 3, 0, 'x']]],
    ['kr', 'South Korea', 'Asia', 'korea', [['Seoul', 2, 1, null]]],
    ['us', 'United States', 'North America', 'usa america united states',
      [['New York', 6, 4, 455], ['Los Angeles', 4, 3, 528], ['Dallas', 3, 2, 497]]],
    ['ca', 'Canada', 'North America', '', [['Toronto', 3, 2, 470], ['Vancouver', 2, 1, null]]],
    ['br', 'Brazil', 'South America', '', [['Sao Paulo', 3, 1, 621]]],
    ['za', 'South Africa', 'Africa', 'rsa', [['Johannesburg', 2, 1, 598]]],
    ['au', 'Australia', 'Oceania', '', [['Sydney', 4, 2, null], ['Melbourne', 2, 1, null]]],
  ];

  const slug = (s) => s.toLowerCase().replace(/[^a-z0-9]+/g, '-');

  function city(code, [name, ss, ws, ms]) {
    const count = ss + ws;
    const tested = ms === null ? 0 : count;
    const ok = ms === null || ms === 'x' ? 0 : count;
    const by = {};
    if (ss) by.surfshark = ss;
    if (ws) by.windscribe = ws;
    return {
      code: slug(name), country: code, name, nick: '',
      count, tested, ok, ping: typeof ms === 'number' ? ms : null,
      load: ws ? 20 + ((name.length * 7) % 60) : null,
      gbps: ws ? 10 : null, p2p: ws ? true : null, by,
    };
  }

  const COUNTRIES = RAW.map(([code, name, continent, alias, rows]) => {
    const cityList = rows.map((r) => city(code, r));
    const by = {}, byOk = {}, byTested = {}, byPing = {};
    let count = 0, tested = 0, ok = 0, ping = null;
    for (const c of cityList) {
      count += c.count; tested += c.tested; ok += c.ok;
      if (c.ping !== null && (ping === null || c.ping < ping)) ping = c.ping;
      for (const [p, n] of Object.entries(c.by)) {
        by[p] = (by[p] || 0) + n;
        if (c.tested) byTested[p] = (byTested[p] || 0) + n;
        if (c.ok) {
          byOk[p] = (byOk[p] || 0) + n;
          const was = byPing[p];
          const mine = c.ping + (p === 'windscribe' ? 23 : 0);
          if (was === undefined || mine < was) byPing[p] = mine;
        }
      }
    }
    return {
      code, name, continent, alias, cities: cityList.length, cityList,
      count, by, tested, ok, ping, byOk, byTested, byPing,
      best: ping === null ? null : ping / 1000,
    };
  });
  COUNTRIES.sort((a, b) => {
    const blocked = (c) => (c.tested && !c.ok ? 1 : 0);
    return blocked(a) - blocked(b)
      || (a.ping === null) - (b.ping === null)
      || (a.ping || 0) - (b.ping || 0)
      || a.name.localeCompare(b.name);
  });
  const SERVERS = COUNTRIES.reduce((n, c) => n + c.count, 0);
  const byCode = Object.fromEntries(COUNTRIES.map((c) => [c.code, c]));

  /* ---------------------------------------------------------------- state */

  const S = {
    connected: false,
    since: 0,
    exit: null,
    picked: 'auto',
    favourites: ['nl', 'de'],
    sortBy: 'windscribe',
    theme: 'black',
    killSwitch: false,
    systemProxy: true,
    routeWith: 'proxy',
    lanShare: false,
    port: 10808,
    mode: 'surfshark',
    up: 0, down: 0,
    tunnelDomain: 'relay.example.net',
    window: { onTop: false, lockWindow: false, startHidden: false, startWithWindows: false },
    keepOnClose: true,
    onClose: '',
    split: { mode: 'off', sites: '', apps: [], iran: false },
  };

  const ACCOUNTS = [
    { id: 'acc-ss', provider: 'surfshark', providerName: 'Surfshark', label: 'Surfshark',
      username: 'kQ7v2mXr9pLs', active: true, hasPassword: true, signedIn: true, added: now() - 86400 * 21 },
    { id: 'acc-ws', provider: 'windscribe', providerName: 'Windscribe', label: 'sample.ws',
      username: 'sample.ws', active: true, hasPassword: true, signedIn: true, added: now() - 86400 * 6 },
  ];

  // The first-run sections are looked at as a fresh install: no accounts,
  // nothing fetched. Everything else has the two sample accounts.
  const FRESH = new Set(['intro', 'welcome', 'setup', 'account-add', 'captcha']);

  const providerState = () => (S.fresh ? {
    surfshark: { servers: 0, account: false, waiting: 0, usable: false },
    windscribe: { servers: 0, account: false, waiting: 0, usable: false },
  } : {
    surfshark: { servers: 84, account: true, waiting: 0, usable: true },
    windscribe: { servers: 41, account: true, waiting: 12, usable: true },
  });

  const sources = () => ({
    ok: true,
    source: 'all',
    rows: [
      { key: 'all', name: 'Everything', note: `${SERVERS} exits · every folder the app reads`, count: COUNTRIES.length, exits: SERVERS },
      { key: 'pinned', name: 'Pinned', note: '118 exits · each one resolved to an address', count: 24, exits: 118 },
      { key: 'verified', name: 'Verified', note: '63 exits · what actually connected, with its times', count: 19, exits: 63 },
      { key: 'starred', name: 'Starred', note: '16 exits · only the places you picked out', count: 2, exits: 16 },
      { key: 'site:web-telegram-org', name: 'web.telegram.org', note: '22 exits · measured getting into this site', count: 9, exits: 22 },
    ],
  });

  const describe = () => ({
    countries: COUNTRIES,
    serverCount: SERVERS,
    folder: 'C:\\Users\\you\\AppData\\Local\\Relay\\pinned',
    folders: ['C:\\Users\\you\\AppData\\Local\\Relay\\pinned'],
    sources: sources(),
    providers: ['surfshark', 'windscribe'],
    providerState: providerState(),
    systemProxy: S.systemProxy,
    routeWith: S.routeWith,
    lanShare: S.lanShare,
    lanSharePasswordSet: true,
    lanShareNotice: '',
    killSwitch: S.killSwitch,
    ownerLookup: true,
    tunReady: true,
    elevated: false,
    split: S.split,
    export: { ready: true, slots: 4, why: '' },
    splitLists: { domains: 412, ranges: 1874, apps: 0 },
    port: S.port,
    defaultPort: 10808,
    picked: S.picked,
    favourites: S.favourites,
    sortBy: S.sortBy,
    connected: S.connected,
    keepOnClose: S.keepOnClose,
    window: S.window,
    orbColors: 'signal',
    onClose: S.onClose,
    theme: S.theme,
    introSeen: true,
    tunnelReady: true,
    mode: S.mode,
    hasCredentials: true,
    username: 'kQ7v2mXr9pLs',
    version: '0.3.0',
    about: 'Relay 0.3.0  -  port 10808\nC:\\Users\\you\\AppData\\Local\\Relay',
  });

  const status = () => {
    const out = {
      state: S.connected ? 'on' : 'off', port: S.port,
      folder: describe().folder, systemProxy: S.systemProxy, routeWith: S.routeWith,
      tun: null, blocked: false, killSwitch: S.killSwitch, owed_restore: false,
    };
    if (S.connected) out.exit = S.exit, out.since = S.since;
    return out;
  };

  /* -------------------------------------------------------------- connect */

  let connectTimers = [];
  let trafficTimer = 0;

  function exitFor(code) {
    let [where, via] = String(code || 'auto').split(':');
    let cityName = '';
    if (where.includes('/')) [where, cityName] = where.split('/');
    const c = byCode[where] || byCode.nl;
    const city = c.cityList.find((x) => x.code === cityName) || c.cityList[0];
    const provider = via || (c.by.surfshark ? 'surfshark' : 'windscribe');
    const host = provider === 'windscribe'
      ? `${c.code}-${city.code.slice(0, 3)}-001.whiskergalaxy.com`
      : `${c.code}-${city.code.slice(0, 3)}.prod.surfshark.com`;
    const ip = `185.${100 + (c.code.charCodeAt(0) % 90)}.${(c.code.charCodeAt(1) * 3) % 250}.${40 + city.name.length}`;
    return { ip, host, country: c.code, city: city.name, provider,
             seen_as: { ip, country: c.code.toUpperCase() } };
  }

  function startTraffic() {
    clearInterval(trafficTimer);
    trafficTimer = setInterval(() => {
      if (!S.connected) return;
      const downRate = 180000 + Math.random() * 900000;
      const upRate = 22000 + Math.random() * 90000;
      S.down += downRate; S.up += upRate;
      emit('Traffic', { live: true, up: Math.round(S.up), down: Math.round(S.down),
                        upRate, downRate, since: S.since, stale: false });
    }, 1000);
  }

  function connect(code, { hold = false, quick = false } = {}) {
    connectTimers.forEach(clearTimeout);
    connectTimers = [];
    const steps = [
      [quick ? 0 : 350, () => emit('Progress', { phase: 'probing', asked: 3, total: 8 })],
      [quick ? 0 : 900, () => emit('Progress', { phase: 'probing', asked: 6, total: 8 })],
      [quick ? 0 : 1400, () => emit('Progress', { phase: 'starting', country: 'nl', city: 'Amsterdam' })],
      [quick ? 0 : 1900, () => emit('Progress', { phase: 'routing' })],
      [quick ? 0 : 2400, () => emit('Progress', { phase: 'verifying' })],
    ];
    if (!hold) {
      steps.push([quick ? 0 : 3000, () => {
        S.connected = true;
        S.since = now() - (quick ? 1287 : 0);
        S.up = quick ? 38.2e6 : 0; S.down = quick ? 412.6e6 : 0;
        S.exit = exitFor(code);
        emit('Connected', status());
        startTraffic();
      }]);
    }
    for (const [ms, fn] of steps) connectTimers.push(later(ms, fn));
    return { ok: true };
  }

  function disconnect() {
    connectTimers.forEach(clearTimeout);
    const minutes = S.connected ? Math.max(1, Math.round((now() - S.since) / 60)) : 0;
    S.connected = false;
    clearInterval(trafficTimer);
    return { ok: true, session: { minutes } };
  }

  /* ---------------------------------------------------------------- hosts */

  const HOSTS = [
    ['web.telegram.org', 'Telegram.exe', 48e6, 3.1e6, 214, 3],
    ['rr4---sn-4g5e6nsz.googlevideo.com', 'chrome.exe', 312e6, 1.8e6, 88, 2],
    ['www.youtube.com', 'chrome.exe', 9.4e6, 0.6e6, 61, 1],
    ['github.com', 'Code.exe', 4.1e6, 0.9e6, 37, 1],
    ['api.anthropic.com', 'Code.exe', 2.2e6, 1.4e6, 26, 1],
    ['gateway.discord.gg', 'Discord.exe', 3.7e6, 0.4e6, 19, 1],
    ['web.whatsapp.com', 'msedge.exe', 1.9e6, 0.3e6, 15, 0],
    ['i.ytimg.com', 'chrome.exe', 6.8e6, 0.1e6, 140, 0],
    ['x.com', 'chrome.exe', 2.6e6, 0.2e6, 33, 0],
    ['update.googleapis.com', null, 0.4e6, 0.05e6, 4, 0],
    ['dl.delivery.mp.microsoft.com', 'svchost.exe', 22e6, 0.1e6, 9, 0],
  ];

  function hosts() {
    const t = now();
    return {
      live: true, total: HOSTS.length, since: S.since || t - 600, at: t, every: 2,
      rows: HOSTS.map(([host, app, down, up, hits, live], i) => ({
        host, app, pid: 4000 + i * 37, up, down, hits, live,
        first: t - 600 + i * 20, last: t - i * 9,
      })),
    };
  }

  /* --------------------------------------------------------- dead exits */

  function deadExits() {
    const rows = [
      ['ro-buh.prod.surfshark.com_tcp.ovpn', 'ro', 'Bucharest', 'surfshark', '86.106.137.12', 'refused: 407 from the proxy'],
      ['hk-hkg.prod.surfshark.com_tcp.ovpn', 'hk', 'Hong Kong', 'surfshark', '84.17.57.98', 'no answer in 6 s'],
      ['in-mum.prod.surfshark.com_tcp.ovpn', 'in', 'Mumbai', 'surfshark', '103.108.117.4', 'TLS reset by the filter'],
      ['HK-Victoria-TCP.ovpn', 'hk', 'Hong Kong', 'windscribe', '103.10.197.50', 'no answer in 6 s'],
    ].map(([file, country, cityName, provider, ip, why]) => ({
      file, folder: 'C:\\Users\\you\\AppData\\Local\\Relay\\pinned', tag: 'pinned',
      country, city: cityName, provider, ip, why, at: now() - 3600,
    }));
    return {
      ok: true, folders: [{ path: 'C:\\Users\\you\\AppData\\Local\\Relay\\pinned', tag: 'pinned', name: 'Pinned', count: 4 }],
      exits: rows, dead: 4, shelved: [{ tag: 'pinned', name: 'Pinned', count: 7, at: now() - 86400 * 2 }], aside: 7,
    };
  }

  function exitsIn(code) {
    let [where, via] = String(code || '').split(':');
    let cityName = null;
    if (where.includes('/')) [where, cityName] = where.split('/');
    const c = byCode[where];
    if (!c) return { ok: true, exits: [] };
    const out = [];
    for (const ct of c.cityList) {
      if (cityName && ct.code !== cityName) continue;
      for (const [p, n] of Object.entries(ct.by)) {
        if (via && via !== p) continue;
        for (let i = 0; i < n; i += 1) {
          const ms = ct.ping === null ? null : ct.ping + i * 17 + (p === 'windscribe' ? 23 : 0);
          out.push({
            file: p === 'windscribe' ? `${c.code.toUpperCase()}-${ct.name.replace(/ /g, '')}-TCP-${i + 1}.ovpn`
                                     : `${c.code}-${ct.code.slice(0, 3)}.prod.surfshark.com_tcp_${i + 1}.ovpn`,
            name: p === 'windscribe' ? `${ct.name} ${i + 1}` : `${c.code}-${ct.code.slice(0, 3)} #${i + 1}`,
            host: `${c.code}-${ct.code.slice(0, 3)}.prod.surfshark.com`,
            ip: `185.${100 + i}.${40 + i * 3}.${12 + i}`,
            city: ct.name, provider: p, ms, ok: ct.ping !== null && ct.tested ? ct.ok > 0 : null,
            tested: !!ct.tested, why: ct.tested && !ct.ok ? 'no answer in 6 s' : '',
            seconds: ms === null ? null : ms / 1000,
          });
        }
      }
    }
    return { ok: true, exits: out };
  }

  /* ----------------------------------------------------------------- pin */

  let pinTimers = [];
  function startPin() {
    pinTimers.forEach(clearTimeout);
    pinTimers = [];
    const total = 96;
    emit('Pin', { phase: 'started', total });
    for (let i = 1; i <= total; i += 1) {
      pinTimers.push(later(i * 120, () => emit('Pin', {
        phase: 'result', done: i, total,
        file: `de-fra.prod.surfshark.com_tcp_${i}.ovpn`, ok: i % 9 !== 0,
        ips: i % 9 ? [`185.${100 + (i % 50)}.${i}.${12 + i}`] : [], why: i % 9 ? '' : 'no address',
      })));
    }
    pinTimers.push(later(total * 120 + 300, () => emit('Pin', {
      phase: 'finished', total, done: total, written: 86, failed: 10, error: '',
    })));
    return { ok: true };
  }

  const PINNED = 'C:\\Users\\you\\AppData\\Local\\Relay\\pinned';
  const pinPlan = () => ({
    ok: true, folder: 'C:\\Users\\you\\AppData\\Local\\Relay\\configs', out: PINNED,
    total: 12, most: 36, minutes: 1, existing: 118, checked: false, blockers: [],
    route: 'direct', port: 10809, maxIps: 3, test: true, outIsStandard: true,
  });

  /* ------------------------------------------------------------- sweep */

  const sweepPlan = () => ({
    ok: true, folder: PINNED, into: PINNED,
    sites: ['web.telegram.org', 'www.instagram.com'], first: 0,
    route: 'provider',
    routeMinutes: { provider: 9, direct: 14, proxy: 11, server: 7, 'server+exit': 12 },
    scope: 'all',
    scopeCounts: { all: 118, company: 31, 'company-location': 44, location: 26 },
    chosen: [],
    companies: [
      { name: 'M247', owner: 'M247 Europe SRL', asn: 'AS9009', count: 38, countries: ['nl', 'de', 'ro', 'uk', 'fr', 'it'] },
      { name: 'Datacamp', owner: 'Datacamp Limited', asn: 'AS60068', count: 27, countries: ['nl', 'de', 'at', 'ch', 'se'] },
      { name: 'Leaseweb', owner: 'Leaseweb Netherlands', asn: 'AS60781', count: 14, countries: ['nl', 'us'] },
      { name: 'Hetzner', owner: 'Hetzner Online GmbH', asn: 'AS24940', count: 9, countries: ['de', 'fi'] },
      { name: 'Vultr', owner: 'The Constant Company', asn: 'AS20473', count: 8, countries: ['jp', 'sg', 'us', 'au'] },
    ],
    untraced: 6,
    siteFolders: [
      { folder: 'C:\\Users\\you\\AppData\\Local\\Relay\\sitetest\\web-telegram-org', tag: 'web.telegram.org', count: 22 },
      { folder: 'C:\\Users\\you\\AppData\\Local\\Relay\\sitetest\\www-instagram-com', tag: 'www.instagram.com', count: 15 },
    ],
    blockers: [], count: 118, total: 118, minutes: 9,
  });

  /* ------------------------------------------------------------ tunnel */

  const tunnelPlan = () => ({
    ok: true, hasClient: true, domain: S.tunnelDomain, running: false, isAddress: false,
    hasPassword: true, hasApiPassword: true, transport: 'tls', hasSs: true, ssPort: 8389,
    pin: 'sha256/9Qk…Vw=', edges: [], verifyExit: true, inIran: false,
  });

  /* ---------------------------------------------------------- the bridge */

  const api = {
    info: describe,
    boot: () => ({ ...describe(), status: status(), recovered: false, recoveredTun: false }),
    whoami: () => {
      later(500, () => emit('RealIp', { ip: '203.0.113.40', country: 'ir', org: 'Iran Cell' }));
      return { ok: true };
    },
    traffic: () => (S.connected
      ? { live: true, up: Math.round(S.up), down: Math.round(S.down), upRate: 0, downRate: 0, since: S.since, stale: false }
      : { live: false }),
    hosts,
    status,
    connect: (code) => connect(code),
    disconnect,
    cancel: () => { connectTimers.forEach(clearTimeout); return { ok: true }; },
    remember: (code) => { S.picked = code; return { ok: true }; },
    accountsList: () => ({ ok: true, accounts: S.fresh ? [] : ACCOUNTS }),
    accountAdd: () => ({ ok: false, error: 'This is a preview: nothing is sent, so no account can be added.' }),
    accountUse: () => ({ ok: true }),
    accountRemove: () => ({ ok: false, error: 'This is a preview: accounts cannot be removed here.' }),
    sources,
    setSource: () => ({ ok: true, ...sources() }),
    favourites: () => ({ ok: true, codes: S.favourites }),
    toggleFavourite: (code) => {
      const i = S.favourites.indexOf(code);
      if (i >= 0) S.favourites.splice(i, 1); else S.favourites.push(code);
      return { ok: true, codes: S.favourites, on: i < 0 };
    },
    setSort: (kind) => { S.sortBy = kind || 'windscribe'; return { ok: true }; },
    setTheme: (key) => { S.theme = key; return { ok: true, theme: key }; },
    setKillSwitch: (on) => { S.killSwitch = !!on; return { ok: true, killSwitch: !!on }; },
    setSystemProxy: (on) => { S.systemProxy = !!on; return { ok: true, systemProxy: !!on }; },
    setRouteWith: (way) => { S.routeWith = way; return { ok: true, routeWith: way }; },
    setPort: (port) => { S.port = Number(port) || S.port; return { ok: true, port: S.port }; },
    setMode: (mode) => { S.mode = mode; return { ok: true, mode }; },
    setWindowOption: (key, on) => { S.window[key] = !!on; return { ok: true, window: S.window }; },
    setKeepOnClose: (on) => { S.keepOnClose = !!on; return { ok: true }; },
    setOnClose: (kind) => { S.onClose = kind; return { ok: true }; },
    deadExits,
    dropExits: () => ({ ok: true, moved: 4 }),
    restoreDropped: () => ({ ok: true, restored: 7 }),
    exitsIn,
    runningApps: () => ({ apps: ['chrome.exe', 'code.exe', 'discord.exe', 'msedge.exe', 'spotify.exe', 'steam.exe', 'telegram.exe'] }),
    pickApps: () => ({ ok: true, apps: [] }),
    lanShareState: () => ({
      on: S.lanShare, port: S.port, addrs: ['192.168.1.34'],
      adapters: [{ ip: '192.168.1.34', name: 'Wi-Fi', network: 'Private' }],
      firewall: 'open', listening: S.lanShare, clients: S.lanShare ? 2 : 0,
    }),
    setLanShare: (on) => { S.lanShare = !!on; return { ok: true, on: !!on }; },
    lanSharePassword: () => ({ ok: true, password: 'river-lamp-42' }),
    pinPlan,
    pinForProvider: () => ({ ok: true, ...pinPlan() }),
    setPinRoute: () => ({ ok: true, ...pinPlan() }),
    startPin,
    cancelPin: () => { pinTimers.forEach(clearTimeout); return { ok: true }; },
    surfsharkServers: () => new Promise((r) => later(1600, () => r({ ok: true, written: 96 }))),
    windscribeServers: () => new Promise((r) => later(1600, () => r({ ok: true, written: 41, pinFolder: '' }))),
    sweepPlan,
    setSweepRoute: () => ({ ok: true, ...sweepPlan() }),
    setSweepScope: () => ({ ok: true, ...sweepPlan() }),
    tunnelPlan,
    serverInIran: () => ({ ok: true, inIran: false }),
    installCommand: () => ({
      ok: true,
      command: "mkdir -p /opt/relay && echo '…installer…' | base64 -d > /opt/relay/install-server.sh && bash /opt/relay/install-server.sh relay.example.net --password '••••' --api-password '••••'",
      status: "systemctl --no-pager --lines=0 status gost sing-box nginx | grep -E '^. |Active:'",
      uninstall: 'systemctl disable --now gost sing-box',
    }),
    exportState: () => ({ ready: true, slots: 4, why: '', host: S.tunnelDomain, running: false, last: null }),
    testReach: () => ({ ok: false, error: 'This is a preview: nothing is timed here.' }),
    testTunnel: () => new Promise((r) => later(1200, () => r({ ok: true, ms: 214, ip: '91.107.233.18' }))),
    chooseFolder: () => ({ ok: false }),
    copy: () => ({ ok: true }),
    minimizeWindow: () => ({ ok: true }),
    hideWindow: () => ({ ok: true }),
    quitApp: () => ({ ok: true }),
    closeRequest: () => ({ ok: true }),
    markIntroSeen: () => ({ ok: true }),
  };

  const log = (name, args) => {
    if (window.__mockQuiet) return;
    console.debug('[mock]', name, ...args);
  };

  window.pywebview = {
    api: new Proxy(api, {
      get(target, name) {
        if (typeof name !== 'string') return undefined;
        return (...args) => {
          log(name, args);
          try {
            const fn = target[name];
            if (!fn) (window.__mockUnknown = window.__mockUnknown || new Set()).add(name);
            const out = fn ? fn(...args) : { ok: true };
            return Promise.resolve(out);
          } catch (e) {
            console.error('[mock]', name, e);
            return Promise.resolve({ ok: false, error: String(e) });
          }
        };
      },
    }),
  };

  /* ---------------------------------------------------- the review page */

  function closeAll() {
    for (const d of document.querySelectorAll('dialog[open]')) {
      try { d.close(); } catch (_) { /* gone */ }
    }
  }

  async function goOff() {
    if (S.connected || state.mode !== 'off') {
      disconnect();
      window.onDisconnected({ minutes: 0 });
    }
    setHint(`${state.countries.length} countries ready.`);
  }

  async function goOn() {
    if (!S.connected) {
      state.mode = 'busy';
      render();
      connect(state.picked === 'auto' ? 'nl' : state.picked, { quick: true });
      await new Promise((r) => later(60, r));
    }
  }

  async function settingsAt(screen) {
    if (!$('prefs').open) $('settings').click();
    await new Promise((r) => later(120, r));
    showScreen(screen);
  }

  const GO = {
    'home-off': async () => { closeAll(); await goOff(); },
    'home-connecting': async () => {
      closeAll();
      await goOff();
      state.mode = 'busy';
      render();
      setStatus('Connecting', 'busy', 'Looking for a server', '');
      setHint('');
      connect('nl', { hold: true });
    },
    'home-on': async () => { closeAll(); await goOn(); },
    'home-blocked': async () => { closeAll(); await goOff(); showBlocked('The connection dropped.'); },
    'home-failed': async () => {
      closeAll();
      await goOff();
      window.onFailedPlain({ kind: 'all-refused', detail: '' });
    },
    picker: async () => { closeAll(); openPicker(); },
    source: async () => { closeAll(); await openSource(); },
    dropped: async () => { closeAll(); openDrop(); },
    log: async () => { closeAll(); await goOn(); later(200, () => $('logOpen').click()); },
    'prefs-menu': async () => { closeAll(); await settingsAt(null); },
    'prefs-accounts': async () => { closeAll(); await settingsAt('accounts'); },
    'prefs-connection': async () => { closeAll(); await settingsAt('connection'); },
    'prefs-share': async () => { closeAll(); await settingsAt('share'); },
    'prefs-servers': async () => { closeAll(); await settingsAt('servers'); },
    'prefs-window': async () => { closeAll(); await settingsAt('window'); },
    'prefs-about': async () => { closeAll(); await settingsAt('about-this-copy'); },
    intro: async () => { closeAll(); runIntro(); },
    welcome: async () => { closeAll(); openWelcome(); },
    setup: async () => { closeAll(); prepareServers('surfshark'); },
    'account-add': async () => {
      closeAll();
      await settingsAt('accounts');
      acctSetProvider('surfshark');
      acctShowForm(true);
    },
    captcha: async () => {
      closeAll();
      await settingsAt('accounts');
      acctSetProvider('windscribe');
      acctShowForm(true);
      wsShowCaptcha({ kind: 'slider', top: 56, background: puzzle.bg(), slider: puzzle.piece() });
    },
    'ask-close': async () => { closeAll(); askClose(); },
  };

  /* Two pictures for the Windscribe puzzle, drawn here rather than shipped:
     a landscape strip and the piece cut from it. Base64 PNG, which is what
     the real puzzle sends. */
  const puzzle = {
    bg() {
      const c = document.createElement('canvas');
      c.width = 700; c.height = 400;
      const g = c.getContext('2d');
      const sky = g.createLinearGradient(0, 0, 0, 400);
      sky.addColorStop(0, '#3a5a7a'); sky.addColorStop(1, '#c9a46b');
      g.fillStyle = sky; g.fillRect(0, 0, 700, 400);
      g.fillStyle = '#2c3d2f';
      g.beginPath(); g.moveTo(0, 300);
      for (let x = 0; x <= 700; x += 50) g.lineTo(x, 260 + Math.sin(x / 70) * 40);
      g.lineTo(700, 400); g.lineTo(0, 400); g.fill();
      g.fillStyle = 'rgba(0,0,0,0.45)'; g.fillRect(420, 56, 120, 120);
      return c.toDataURL('image/png').split(',')[1];
    },
    piece() {
      const c = document.createElement('canvas');
      c.width = 240; c.height = 240;
      const g = c.getContext('2d');
      g.fillStyle = '#d9b66a'; g.fillRect(0, 0, 240, 240);
      g.strokeStyle = '#fff'; g.lineWidth = 8; g.strokeRect(4, 4, 232, 232);
      return c.toDataURL('image/png').split(',')[1];
    },
  };

  let going = Promise.resolve();
  window.addEventListener('message', (e) => {
    const m = e.data;
    if (!m || m.relay !== 'go') return;
    const fn = GO[m.to];
    if (!fn) return;
    going = going.then(async () => {
      const fresh = FRESH.has(m.to);
      if (fresh !== !!S.fresh) {
        S.fresh = fresh;
        try { await acctRefresh(); } catch (_) { /* the pane is not up yet */ }
      }
      try { await fn(); } catch (err) { console.error('[mock] go', m.to, err); }
      try { parent.postMessage({ relay: 'at', to: m.to }, '*'); } catch (_) { /* alone */ }
    });
  });

  window.__relayPreview = { go: (to) => window.postMessage({ relay: 'go', to }, '*'), S };

  // Tell the page around this one when the window is up and taking orders.
  window.addEventListener('load', () => {
    later(400, () => {
      try { parent.postMessage({ relay: 'ready' }, '*'); } catch (_) { /* alone */ }
    });
  });
})();

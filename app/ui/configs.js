/* Configs: servers bought or given from anywhere - vless:// vmess:// trojan://
   ss:// hysteria2:// tuic:// links and subscriptions - in a sheet of their
   own. Drawn from configs('list') and kept current by window.onConfigs.

   Loaded after app.js and leans on what it declares at the top level: $,
   state, render, setStatus, setHint, shut, flagUrl, canonCode, copyText.

   Nothing from a config reaches innerHTML. Names come from Telegram
   channels and sellers' lists, so every piece of one is set as text. */
(function () {
  'use strict';

  const call = (action, payload) =>
    window.pywebview.api.configs(action, payload || {});

  const view = {
    list: null,         // the last configs('list')
    expanded: null,     // the row whose actions are open
    renaming: null,
    testing: null,      // {done, total} while a test runs
    fresh: true,        // animate rows in on the next draw
    mine: false,        // a connect started here, until the line is down again
    by: 'country',      // the list by country, or by where it came from ('group')
    open: new Set(),    // the countries whose configs are shown
    opened: false,      // whether the picked country was opened yet
  };
  try {
    if (localStorage.getItem('cfgView') === 'group') view.by = 'group';
  } catch (e) { /* storage refused: by country */ }

  const WARN = {
    plain: ['No encryption', 'Nothing encrypts this on the way to the server; anyone on the line can read it.'],
    notls: ['No TLS', 'Encrypted, but no certificate proves who the server is.'],
    insecure: ['Asks to skip the certificate check', 'The link asks for the certificate check to be off. It stays on unless you turn it off for this server.'],
  };
  const FAIL = {
    timeout: 'timeout', cert: 'certificate', refused: 'refused',
    dns: 'no address', closed: 'closed', failed: 'failed',
    cancelled: '', 'xray-missing': 'needs Xray',
  };

  /* ------------------------------------------------------------ helpers */

  function el(tag, cls, text) {
    const n = document.createElement(tag);
    if (cls) n.className = cls;
    if (text !== undefined && text !== null) n.textContent = String(text);
    return n;
  }

  function icon(name) {
    const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
    svg.setAttribute('class', 'ico');
    svg.setAttribute('aria-hidden', 'true');
    const use = document.createElementNS('http://www.w3.org/2000/svg', 'use');
    use.setAttribute('href', `#i-${name}`);
    svg.appendChild(use);
    return svg;
  }

  function say(text, bad) {
    const n = $('cfgSaid');
    n.textContent = text || '';
    n.dataset.bad = bad ? '1' : '0';
  }

  function bytes(n) {
    if (!n && n !== 0) return '';
    const u = ['B', 'KB', 'MB', 'GB', 'TB'];
    let i = 0;
    let v = n;
    while (v >= 1024 && i < u.length - 1) { v /= 1024; i += 1; }
    return `${v >= 10 || i === 0 ? Math.round(v) : v.toFixed(1)} ${u[i]}`;
  }

  function ago(ts) {
    if (!ts) return '';
    const s = Math.max(0, Date.now() / 1000 - ts);
    if (s < 90) return 'just now';
    if (s < 5400) return `${Math.round(s / 60)} min ago`;
    if (s < 129600) return `${Math.round(s / 3600)} h ago`;
    return `${Math.round(s / 86400)} days ago`;
  }

  function find(id) {
    return view.list && view.list.configs.find((c) => c.id === id);
  }

  /* A question asked inside the sheet, answered by one of two buttons. */
  function ask(text, yes) {
    const box = $('cfgAsk');
    $('cfgAskText').textContent = text;
    $('cfgAskYes').textContent = yes || 'Yes';
    box.hidden = false;
    $('cfgAskNo').focus();
    return new Promise((resolve) => {
      const done = (answer) => {
        box.hidden = true;
        $('cfgAskYes').onclick = null;
        $('cfgAskNo').onclick = null;
        resolve(answer);
      };
      $('cfgAskYes').onclick = () => done(true);
      $('cfgAskNo').onclick = () => done(false);
    });
  }

  /* ------------------------------------------------------------- drawing */

  function msCell(c, live) {
    const cell = el('span', 'cfg__ms');
    const r = live || c.last;
    if (!r) { cell.textContent = '—'; cell.dataset.q = 'none'; return cell; }
    if (r.ms) {
      cell.textContent = `${r.ms} ms`;
      cell.dataset.q = r.ms < 300 ? 'fast' : (r.ms < 900 ? 'fair' : 'slow');
    } else {
      cell.textContent = FAIL[r.error] !== undefined ? FAIL[r.error] : 'failed';
      cell.dataset.q = 'fail';
      if (r.said) cell.title = r.said;
    }
    return cell;
  }

  function row(c, n) {
    const live = view.list.live || {};
    const r = el('div', 'cfg');
    r.dataset.id = c.id;
    r.style.setProperty('--i', String(Math.min(n, 30)));
    r.tabIndex = 0;
    r.setAttribute('role', 'button');
    const current = live.on && (live.current === c.id || live.using === c.id);
    if (current) r.dataset.on = '1';
    if (view.list.selected === c.id) r.dataset.picked = '1';

    r.appendChild(flagOf(c));

    const main = el('span', 'cfg__main');
    const name = el('span', 'cfg__name', shownName(c));
    name.dir = 'auto';
    main.appendChild(name);
    const meta = el('span', 'cfg__meta');
    meta.appendChild(el('span', 'cfg__kind', c.label));
    meta.appendChild(el('span', 'cfg__host', `${c.host}:${c.port}`));
    if (c.engine === 'xray') meta.appendChild(el('span', 'cfg__via', 'via Xray'));
    main.appendChild(meta);

    // Under the name rather than beside it: the window is 400 pixels wide,
    // and a chip beside a seller's long name leaves room for neither.
    const marks = el('span', 'cfg__marks');
    for (const w of c.warnings || []) {
      if (w === 'insecure' && c.allowInsecure) continue;
      const m = el('span', `cfg__warn cfg__warn--${w}`, WARN[w][0]);
      m.title = WARN[w][1];
      marks.appendChild(m);
    }
    if (c.allowInsecure) {
      const m = el('span', 'cfg__warn cfg__warn--insecure', 'Certificate not checked');
      m.title = 'You turned the certificate check off for this server.';
      marks.appendChild(m);
    }
    if (marks.childNodes.length) main.appendChild(marks);
    r.appendChild(main);
    r.appendChild(msCell(c));

    const more = el('button', 'icon cfg__more');
    more.type = 'button';
    more.setAttribute('aria-label', `More for ${c.name}`);
    more.setAttribute('aria-expanded', view.expanded === c.id ? 'true' : 'false');
    more.appendChild(icon('chev-down'));
    r.appendChild(more);
    return r;
  }

  /* The name as shown beside its flag: a flag emoji in it goes, since the
     flag is drawn already - and Windows has no flag emoji, so it would read
     as two stray capitals ("DE Frankfurt"). The name kept is untouched. */
  function shownName(c) {
    const plain = String(c.name || '').replace(/[\u{1F1E6}-\u{1F1FF}]{2}\s*/gu, '').trim();
    return plain || c.name;
  }

  /* The flag a row carries: the tested exit's, or - before any test - the
     one its name gives, a shade quieter and said to be a guess. */
  function flagOf(c) {
    const flag = el('span', 'cfg__flag');
    if (c.country) {
      flag.style.backgroundImage = flagUrl(canonCode(c.country));
      if (c.countryFrom === 'name') {
        flag.dataset.guess = '1';
        flag.title = `${c.countryName} - from its name; a test reads the real exit`;
      } else {
        flag.title = c.countryName;
      }
    }
    return flag;
  }

  function actions(c) {
    const box = el('div', 'cfgacts');
    box.dataset.for = c.id;
    const add = (label, fn, cls) => {
      const b = el('button', `btn btn--bare${cls ? ` ${cls}` : ''}`, label);
      b.type = 'button';
      b.addEventListener('click', (e) => { e.stopPropagation(); fn(b); });
      box.appendChild(b);
      return b;
    };
    if (view.renaming === c.id) {
      const input = el('input', 'field__in cfgacts__name');
      input.value = c.name;
      input.maxLength = 64;
      input.dir = 'auto';
      input.setAttribute('aria-label', 'New name');
      box.appendChild(input);
      add('Save', async () => {
        const r = await call('rename', { id: c.id, name: input.value });
        view.renaming = null;
        if (r.ok) draw(r.list); else say(r.error, true);
      });
      add('Cancel', () => { view.renaming = null; draw(view.list); });
      setTimeout(() => input.focus(), 0);
      return box;
    }
    add('Test', () => test({ ids: [c.id] }));
    add('Copy link', async (b) => {
      const r = await call('copy', { id: c.id });
      if (r.ok && r.link) copyText(b, r.link);
      else say('This one came from a subscription in sing-box form and has no link to copy.', true);
    });
    add('Rename', () => { view.renaming = c.id; draw(view.list); });
    if (c.tls) {
      add(c.allowInsecure ? 'Check the certificate again' : 'Skip the certificate check',
        async () => {
          if (!c.allowInsecure && !(await ask(
            `Skip the certificate check for “${c.name}”? Anything between you and `
            + 'the server could then pretend to be it and read what you send. Only '
            + 'for a server you set up yourself, or whose owner told you to.',
            'Skip the check'))) return;
          const r = await call('trust', { id: c.id, on: !c.allowInsecure });
          if (r.ok) draw(r.list); else say(r.error, true);
        });
    }
    add('Delete', async () => {
      if (!(await ask(`Delete “${c.name}”?`, 'Delete'))) return;
      const r = await call('remove', { ids: [c.id] });
      if (r.ok) draw(r.list); else say(r.error, true);
    }, 'cfgacts__del');
    return box;
  }

  function groupHead(g, members) {
    const head = el('div', 'cfggroup');
    const title = el('span', 'cfggroup__title',
      g.kind === 'manual' ? 'Added by hand' : (g.name || g.host || 'Subscription'));
    title.dir = 'auto';
    head.appendChild(title);
    const meta = el('span', 'cfggroup__meta');
    const bits = [`${members.length}`];
    const info = g.info || {};
    if (info.total) bits.push(`${bytes((info.upload || 0) + (info.download || 0))} of ${bytes(info.total)}`);
    if (info.expire) {
      const days = Math.round((info.expire - Date.now() / 1000) / 86400);
      bits.push(days >= 0 ? `${days} days left` : 'expired');
    }
    if (g.updated) bits.push(`updated ${ago(g.updated)}`);
    meta.textContent = bits.join(' · ');
    head.appendChild(meta);
    if (g.plainHttp) {
      const w = el('span', 'cfg__warn cfg__warn--plain', 'http');
      w.title = 'Fetched over plain http: the line can read and change this list. Fetched through a connection, only that connection can.';
      head.appendChild(w);
    }
    if (g.error) {
      const e = el('span', 'cfggroup__err', g.error);
      head.appendChild(e);
    }
    const acts = el('span', 'cfggroup__acts');
    const btn = (name, label, fn) => {
      const b = el('button', 'icon icon--sm');
      b.type = 'button';
      b.setAttribute('aria-label', label);
      b.title = label;
      b.appendChild(icon(name));
      b.addEventListener('click', fn);
      acts.appendChild(b);
    };
    if (members.length) btn('gauge', 'Test these', () => test({ group: g.id }));
    if (g.kind === 'sub') {
      btn('refresh', 'Update now', async () => {
        say(`Fetching ${g.name || g.host}…`);
        const r = await call('updateSub', { group: g.id });
        if (!r.ok) say(r.error, true);
      });
      btn('x', 'Remove this subscription', async () => {
        if (!(await ask(`Remove “${g.name || g.host}” and its ${members.length} configs?`,
          'Remove'))) return;
        const r = await call('removeSub', { group: g.id });
        if (r.ok) draw(r.list); else say(r.error, true);
      });
    }
    head.appendChild(acts);
    return head;
  }

  /* ------------------------------------------------------ by country */

  /* Countries as Mirage's own list has them: one card each, the fastest
     time of its configs, a Connect that takes the fastest of them, and the
     configs inside when it is opened. Configs whose name gives no country
     and that were never tested go last, under "Country not known". */
  function countries(list) {
    const by = new Map();
    for (const c of list.configs) {
      const cc = c.country || '';
      if (!by.has(cc)) by.set(cc, { code: cc, name: c.countryName || 'Country not known', rows: [] });
      by.get(cc).rows.push(c);
    }
    const out = [...by.values()];
    for (const k of out) {
      const ok = k.rows.filter((c) => c.last && c.last.ms);
      k.best = ok.length ? Math.min(...ok.map((c) => c.last.ms)) : 0;
      k.tested = k.rows.filter((c) => c.last).length;
      k.dead = k.tested === k.rows.length && !ok.length;
      k.rows.sort((a, b) => ((a.last && a.last.ms) || 1e9) - ((b.last && b.last.ms) || 1e9));
    }
    out.sort((a, b) => {
      if (!a.code !== !b.code) return a.code ? -1 : 1;
      if (a.dead !== b.dead) return a.dead ? 1 : -1;
      if (a.best !== b.best) {
        if (!a.best) return 1;
        if (!b.best) return -1;
        return a.best - b.best;
      }
      return a.name.localeCompare(b.name);
    });
    return out;
  }

  function countryCard(k, n) {
    const list = view.list;
    const live = list.live || {};
    const card = el('div', 'cfgc');
    card.dataset.cc = k.code;
    card.style.setProperty('--i', String(Math.min(n, 30)));
    const on = !!live.on && (live.country
      ? live.country === k.code
      : k.rows.some((c) => c.id === (live.using || live.current)));
    if (on) card.dataset.on = '1';
    if (!live.on && list.country && list.country === k.code) card.dataset.picked = '1';
    const open = view.open.has(k.code);
    card.dataset.open = open ? '1' : '0';
    if (k.dead) card.dataset.dead = '1';

    const head = el('button', 'cfgc__head');
    head.type = 'button';
    head.setAttribute('aria-expanded', String(open));
    const flag = el('span', 'cfgc__flag');
    if (k.code) flag.style.backgroundImage = flagUrl(canonCode(k.code));
    else flag.appendChild(icon('globe'));
    head.appendChild(flag);
    const text = el('span', 'cfgc__text');
    text.appendChild(el('span', 'cfgc__name', k.name));
    const bits = [`${k.rows.length} config${k.rows.length === 1 ? '' : 's'}`];
    if (k.dead) bits.push('none answered');
    else if (k.tested < k.rows.length) {
      bits.push(k.tested ? `${k.rows.length - k.tested} not tested` : 'not tested');
    }
    text.appendChild(el('span', 'cfgc__meta', bits.join(' · ')));
    head.appendChild(text);
    const ms = el('span', 'cfg__ms cfgc__ms');
    if (k.best) {
      ms.textContent = `${k.best} ms`;
      ms.dataset.q = k.best < 300 ? 'fast' : (k.best < 900 ? 'fair' : 'slow');
    } else {
      ms.textContent = k.dead ? 'failed' : '—';
      ms.dataset.q = k.dead ? 'fail' : 'none';
    }
    head.appendChild(ms);
    const chev = icon('chev-down');
    chev.classList.add('cfgc__chev');
    head.appendChild(chev);
    card.appendChild(head);

    if (k.code) {
      const go = el('button', 'cfgc__go', on ? 'Connected' : 'Connect');
      go.type = 'button';
      go.dataset.state = on ? 'on' : 'off';
      go.title = `The fastest of the ${k.rows.length} in ${k.name}, moving on when one stops answering`;
      card.appendChild(go);
    }
    if (open) {
      const inner = el('div', 'cfgc__in');
      k.rows.forEach((c, i) => {
        inner.appendChild(row(c, i));
        if (view.expanded === c.id) inner.appendChild(actions(c));
      });
      card.appendChild(inner);
    }
    return card;
  }

  /* The country in use, or picked, or the picked config's - or null. */
  function homeCountry(list) {
    const live = list.live || {};
    if (live.on && live.country) return live.country;
    if (!live.on && list.country) return list.country;
    const c = find(live.on ? (live.using || live.current) : list.selected);
    return c ? (c.country || '') : null;
  }

  function drawCountries(list, host) {
    const ks = countries(list);
    if (!view.opened) {
      // The country in use, or picked, opens itself once per visit.
      view.opened = true;
      const cc = homeCountry(list);
      if (cc !== null && ks.some((k) => k.code === cc)) view.open.add(cc);
    }
    // Each subscription keeps its own line here too - how much is left, and
    // its update and remove - so none is only reachable from the other view,
    // and one whose fetch failed is not a blank list.
    for (const g of list.groups) {
      if (g.kind !== 'sub') continue;
      host.appendChild(groupHead(g, list.configs.filter((c) => c.group === g.id)));
    }
    ks.forEach((k, n) => host.appendChild(countryCard(k, n)));
    return list.configs.length;
  }

  function draw(list) {
    if (!list) return;
    view.list = list;
    const host = $('cfgList');
    host.textContent = '';
    host.dataset.by = view.by;
    for (const b of $('cfgView').querySelectorAll('.cfgview__opt')) {
      b.setAttribute('aria-selected', String(b.dataset.view === view.by));
    }
    let n = 0;
    if (view.by === 'country') n = drawCountries(list, host);
    else for (const g of list.groups) {
      const members = list.configs.filter((c) => c.group === g.id);
      if (g.kind === 'manual' && !members.length) continue;
      host.appendChild(groupHead(g, members));
      for (const c of members) {
        host.appendChild(row(c, n));
        n += 1;
        if (view.expanded === c.id) host.appendChild(actions(c));
      }
    }
    if (!n && !list.groups.some((g) => g.kind === 'sub')) {
      const empty = el('div', 'cfgempty');
      empty.appendChild(el('p', 'cfgempty__lead', 'Nothing here yet.'));
      empty.appendChild(el('p', 'note',
        'Copy vless://, vmess://, trojan://, ss://, hysteria2:// or tuic:// links '
        + 'from wherever you got them and press Paste links - or add the '
        + 'subscription address your seller gave you.'));
      host.appendChild(empty);
    }
    // A country is always the fastest of its configs: the switch says so and
    // holds, rather than offering a choice that would leave the country.
    const byCountry = (list.live && list.live.on) ? !!list.live.country : !!list.country;
    const auto = $('cfgAuto');
    auto.setAttribute('aria-checked', (list.auto || byCountry) ? 'true' : 'false');
    auto.disabled = byCountry;
    auto.closest('.cfgsw').title = byCountry
      ? 'A country always uses the fastest of its configs'
      : 'Moves to the next when one stops answering';
    if (list.engines && !list.engines.singbox) {
      say('sing-box.exe is not beside Mirage, so configs cannot connect or be tested.', true);
    }
    if (view.fresh) { view.fresh = false; reveal(host); }
    sync();
  }

  /* ------------------------------------------------------------ the home */

  /* The section's own home, from what the window already knows: state.mode
     (app.js), the runner's side of it (list.live), and whether the connect
     under way is this section's. Called on every draw and on every render()
     of the main home, so the two never disagree. */
  function heroState() {
    const live = (view.list && view.list.live) || {};
    // Held by a kill switch, whichever section raised it: said here, with
    // its way out, rather than only on Mirage's page behind this one.
    if (state.blocked) return 'held';
    if (state.mode === 'busy' && view.mine) return 'busy';
    if (state.mode === 'on' && live.on) return 'on';
    if (state.mode === 'fail' && view.mine) return 'fail';
    return 'off';
  }

  function sync() {
    if (!view.list) return;
    const live = view.list.live || {};
    const hs = heroState();
    if (state.mode === 'off' || (state.mode === 'on' && !live.on)) view.mine = false;
    $('cfgHero').dataset.state = hs === 'held' ? 'fail' : hs;
    // A drop, a tray Disconnect, a hold: the list's own idea of what is up
    // is asked for again rather than left saying connected.
    if (live.on && state.mode !== 'on' && state.mode !== 'busy' && !view.asking) {
      view.asking = true;
      refresh().finally(() => { view.asking = false; });
    }
    const id = live.on ? (live.using || live.current) : view.list.selected;
    const c = id && id !== 'auto' ? find(id) : null;
    const word = { off: 'Disconnected', busy: 'Connecting', on: 'Connected', held: 'Blocked' }[hs]
      || ($('status').textContent || 'Not connected');
    $('cfgState').textContent = word;
    $('cfgClock').textContent = hs === 'on' && state.since
      ? clockText(Date.now() / 1000 - state.since) : '00:00:00';
    if (hs !== 'on') paintFlux(null);
    // A country picked, or connected, as a whole.
    const cc = live.on ? live.country : view.list.country;
    const members = cc ? view.list.configs.filter((x) => x.country === cc) : [];
    const flag = $('cfgNowFlag');
    const fc = cc || (c && c.country);
    flag.style.backgroundImage = fc ? flagUrl(canonCode(fc)) : '';
    if (cc && members.length) {
      $('cfgNowName').textContent = members[0].countryName || cc.toUpperCase();
      const best = members.filter((x) => x.last && x.last.ms).map((x) => x.last.ms);
      $('cfgNowSub').textContent = hs === 'busy'
        ? ($('statusWhere').textContent || '').trim() || 'Starting'
        : (live.on && c
          ? `Fastest now: ${shownName(c)}`
          : `Fastest of ${members.length}${best.length ? ` · best ${Math.min(...best)} ms` : ''}`);
    } else if (c) {
      $('cfgNowName').textContent = (live.on && live.auto ? 'Fastest · ' : '') + shownName(c);
      const r = c.last;
      const t = !r ? 'not tested' : r.ms ? `${r.ms} ms` : (FAIL[r.error] || 'failed');
      $('cfgNowSub').textContent = hs === 'busy'
        ? ($('statusWhere').textContent || '').trim() || 'Starting'
        : `${c.label} · ${t}`;
    } else {
      $('cfgNowName').textContent = view.list.configs.length ? 'No config picked' : 'No configs yet';
      $('cfgNowSub').textContent = view.list.configs.length ? 'Pick a country below' : 'Press Add to paste links or a subscription';
    }
    $('cfgTun').setAttribute('aria-checked', view.list.mode === 'tun' ? 'true' : 'false');
    const go = $('cfgGo');
    go.dataset.mode = hs === 'fail' ? 'off' : (hs === 'held' ? 'on' : hs);
    // Busy with a connect that is not this section's: wait for it.
    go.disabled = (hs === 'off' && ((!c && !members.length) || state.mode === 'busy'));
    const provider = !live.on && (state.mode === 'on' || (state.mode === 'busy' && !view.mine));
    $('cfgNote').textContent = hs === 'held'
      ? 'The connection dropped and the kill switch is holding: nothing leaves on your normal line. Disconnect to use it again, or pick a config to reconnect.'
      : provider
        ? 'Mirage is connected through a provider. Connecting here takes over from it.'
        : (hs === 'fail' ? ($('hint').textContent || '') : '');
  }
  window.cfgSync = () => { if ($('configsDlg').open) sync(); };
  setInterval(() => { if ($('configsDlg').open && heroState() === 'on') sync(); }, 1000);

  /* The rows settle in once, when the sheet opens: opacity and a few pixels
     of rise, nothing that moves layout, and nothing at all for people who
     asked for less motion. */
  function reveal(host) {
    if (!window.gsap) return;
    const rows = Array.from(host.querySelectorAll('.cfg, .cfggroup, .cfgc')).slice(0, 30);
    if (!rows.length) return;
    const mm = window.gsap.matchMedia();
    mm.add('(prefers-reduced-motion: no-preference)', () => {
      window.gsap.from(rows, { opacity: 0, y: 6, duration: 0.18, ease: 'power1.out',
        stagger: 0.012, clearProps: 'transform,opacity' });
    });
  }

  /* --------------------------------------------------------- what it does */

  async function refresh() {
    const r = await call('list');
    if (r.ok) draw(r.list); else say(r.error, true);
  }

  function sameGroup(a, b) {
    const x = find(a);
    const y = find(b);
    return x && y && x.group === y.group;
  }

  async function connectTo(id) {
    const c = find(id);
    if (!c || state.mode === 'busy') return;
    view.mine = true;
    if ((c.warnings || []).includes('plain') && !(await ask(
      `“${c.name}” sends your traffic to its server without any encryption. `
      + 'Anyone on the line in between can read it. Connect anyway?', 'Connect anyway'))) return;
    const live = view.list.live || {};
    if (live.on && !live.auto && sameGroup(live.current, id)) {
      const r = await call('switch', { id });
      if (!r.ok) { say(r.error, true); return; }
      return;
    }
    state.mode = 'busy';
    render();
    setStatus('Connecting', 'busy', `Starting ${c.name}`, '');
    setHint('');
    const auto = $('cfgAuto').getAttribute('aria-checked') === 'true';
    const r = await call('connect', { id, auto });
    if (!r.ok) {
      state.mode = 'off';
      view.mine = false;
      render();
      say(r.error, true);
    }
  }

  /* A country's Connect: the fastest of its configs, moving on when one
     stops answering - Mirage's own list connects a country the same way. */
  async function connectCountry(cc) {
    if (!cc || state.mode === 'busy') return;
    const live = view.list.live || {};
    if (live.on && live.country === cc) return;
    const members = view.list.configs.filter((c) => c.country === cc);
    if (!members.length) return;
    // The same question a single config without encryption gets: the race
    // may well land on it.
    const plain = members.filter((c) => (c.warnings || []).includes('plain'));
    if (plain.length && !(await ask(
      `${plain.length} of the ${members.length} configs in ${members[0].countryName} send `
      + 'your traffic without any encryption, and the fastest may be one of them. '
      + 'Anyone on the line in between could read it. Connect anyway?', 'Connect anyway'))) return;
    view.mine = true;
    state.mode = 'busy';
    render();
    setStatus('Connecting', 'busy', `The fastest in ${members[0].countryName}`, '');
    setHint('');
    const r = await call('connect', { country: cc });
    if (!r.ok) {
      state.mode = 'off';
      view.mine = false;
      render();
      say(r.error, true);
    }
  }

  /* A row pressed: with this section's connection up, it moves there (live,
     within a group); otherwise it becomes the one Connect will use. */
  async function choose(id) {
    const live = (view.list && view.list.live) || {};
    if (live.on) { connectTo(id); return; }
    const r = await call('pick', { id });
    if (r.ok) draw(r.list); else say(r.error, true);
  }

  $('cfgTun').addEventListener('click', async () => {
    const on = $('cfgTun').getAttribute('aria-checked') !== 'true';
    const r = await call('set', { values: { mode: on ? 'tun' : 'system' } });
    if (!r.ok) { say(r.error, true); return; }
    say(r.applied ? (on ? 'Moving the whole system onto the config… Windows may ask for administrator approval.'
      : 'Back to the proxy setting…')
      : (on ? 'The whole system goes through the config from the next connect. It asks for administrator approval.'
        : 'Only what follows the proxy setting goes through it from the next connect.'));
    refresh();
  });

  $('cfgGo').addEventListener('click', async () => {
    const hs = heroState();
    if (hs === 'on' || hs === 'busy' || hs === 'held') {
      setStatus(hs === 'busy' ? 'Cancelling' : 'Disconnecting', 'off', 'Putting Windows back', '');
      const r = await window.pywebview.api.disconnect();
      view.mine = false;
      window.onDisconnected(r && r.session);
      refresh();
      return;
    }
    if (view.list && view.list.country) connectCountry(view.list.country);
    else if (view.list && view.list.selected) connectTo(view.list.selected);
  });

  async function test(what) {
    if (view.testing) {
      await call('cancelTest');
      return;
    }
    const r = await call('test', what || {});
    if (!r.ok) { say(r.error, true); return; }
    view.testing = { done: 0, total: r.total };
    paintTesting();
  }

  function paintTesting() {
    const t = view.testing;
    $('cfgTestLabel').textContent = t ? `Stop · ${t.done}/${t.total}` : 'Test all';
    $('cfgTest').dataset.on = t ? '1' : '0';
  }

  async function addLinks(text) {
    const r = text === undefined ? await call('paste') : await call('import', { text });
    if (!r.ok) {
      if (text === undefined) {
        // Nothing usable on the clipboard: the box to paste into by hand.
        $('cfgPasteForm').hidden = false;
        $('cfgPasteText').focus();
      }
      say(r.error, true);
      return;
    }
    view.fresh = true;
    draw(r.list);
    const parts = [];
    parts.push(r.added ? `Added ${r.added}.` : 'Nothing new added.');
    if (r.dup) parts.push(`${r.dup} already here.`);
    if (r.over) parts.push(`${r.over} over the limit of 1000.`);
    if (r.problems && r.problems.length) {
      const p = r.problems[0];
      parts.push(`${r.problems.length} not taken - line ${p.n}: ${p.why}.`);
    }
    const w = r.warned || {};
    if (w.plain) parts.push(`${w.plain} send traffic without encryption.`);
    if (w.insecure) parts.push(`${w.insecure} ask for the certificate check to be off; it stays on.`);
    say(parts.join(' '), !r.added && !r.dup);
    if (r.added) {
      $('cfgPasteForm').hidden = true;
      $('cfgPasteText').value = '';
    }
  }

  /* ------------------------------------------------------------- events */

  window.onConfigs = (e) => {
    if (!e) return;
    if (e.kind === 'list' && $('configsDlg').open) draw(e.list);
    if (e.kind === 'test') {
      view.testing = { done: e.done, total: e.total };
      paintTesting();
      const r = document.querySelector(`#cfgList .cfg[data-id="${CSS.escape(e.id)}"]`);
      if (r && e.result) {
        const c = find(e.id);
        if (c) r.querySelector('.cfg__ms').replaceWith(msCell(c, e.result));
        if (e.result.country) {
          const f = r.querySelector('.cfg__flag');
          f.style.backgroundImage = flagUrl(canonCode(e.result.country));
          delete f.dataset.guess;
        }
      }
    }
    if (e.kind === 'tested') {
      view.testing = null;
      paintTesting();
      draw(e.list);
      say(e.error ? `The test did not run: ${e.error}` : 'Fastest first.', !!e.error);
    }
    if (e.kind === 'sub') {
      // One fetched in the background that failed: kept on the group, not
      // drawn over a rename or an open row, and not said.
      if (e.quiet && !e.ok) return;
      if (e.list && !(e.quiet && (view.renaming || view.expanded))) draw(e.list);
      if (e.quiet) return;
      if (e.ok) {
        say(`Updated: ${e.count} configs${e.problems && e.problems.length
          ? `, ${e.problems.length} not taken` : ''}${e.through ? ', fetched through the connection' : ''}.`);
      } else {
        say(e.error, true);
      }
    }
  };

  /* For configs-shell.js, which opens and closes the section. */
  window.cfgSection = {
    opened() {
      view.fresh = true;
      view.opened = false;
      view.open.clear();
      view.expanded = null;
      view.renaming = null;
      say('');
      refresh();
    },
    refresh,
    list: () => view.list,
    draw,
    // Settings > Connection > Connect on opening, once, at start.
    async autostart() {
      if (state.mode !== 'off') return;
      try {
        const r = await call('autostart');
        if (r.ok && r.started) {
          view.mine = true;
          state.mode = 'busy';
          render();
          setStatus('Connecting', 'busy', 'Starting your config', '');
        }
      } catch (e) { /* a preference, not worth a failure at start */ }
    },
  };

  /* ------------------------------------------------------ the Add menu */

  const calmMenu = matchMedia('(prefers-reduced-motion: reduce)');
  function addMenu(open) {
    const menu = $('cfgAddMenu');
    const btn = $('cfgAddOpen');
    const want = open === undefined ? menu.hidden : open;
    if (!want && menu.hidden) return;
    btn.setAttribute('aria-expanded', String(want));
    if (!want) { menu.hidden = true; return; }
    menu.hidden = false;
    if (window.gsap && !calmMenu.matches) {
      window.gsap.fromTo(menu, { opacity: 0, y: -4, scale: 0.98 },
        { opacity: 1, y: 0, scale: 1, duration: 0.16, ease: 'power2.out',
          clearProps: 'transform,opacity' });
    }
    menu.querySelector('.cfgadd__item').focus();
  }
  $('cfgAddOpen').addEventListener('click', (e) => { e.stopPropagation(); addMenu(); });
  document.addEventListener('click', (e) => {
    if (!e.target.closest('.cfgadd')) addMenu(false);
  });
  $('cfgAddMenu').addEventListener('keydown', (e) => {
    if (e.key === 'Escape') {
      e.preventDefault();
      e.stopPropagation();
      addMenu(false);
      $('cfgAddOpen').focus();
      return;
    }
    // A menu's own keys: up and down move between its three, wrapping.
    if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
      e.preventDefault();
      const items = [...$('cfgAddMenu').querySelectorAll('.cfgadd__item')];
      const at = items.indexOf(document.activeElement);
      const step = e.key === 'ArrowDown' ? 1 : -1;
      items[(at + step + items.length) % items.length].focus();
    }
  });

  $('cfgPaste').addEventListener('click', () => { addMenu(false); addLinks(); });
  $('cfgPasteHand').addEventListener('click', () => {
    addMenu(false);
    $('cfgSubForm').hidden = true;
    $('cfgSubShow').setAttribute('aria-expanded', 'false');
    $('cfgPasteForm').hidden = false;
    $('cfgPasteText').focus();
  });

  /* The home's place card: down to the list, with the country it names
     opened. */
  $('cfgLoc').addEventListener('click', () => {
    const list = view.list;
    if (!list) return;
    const cc = homeCountry(list);
    let target = $('cfgList');
    if (view.by === 'country' && cc !== null) {
      view.open.add(cc);
      draw(list);
      target = $('cfgList').querySelector(`.cfgc[data-cc="${CSS.escape(cc)}"]`) || target;
    }
    target.scrollIntoView({ block: 'start',
      behavior: matchMedia('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth' });
  });

  $('cfgView').addEventListener('click', (e) => {
    const b = e.target.closest('.cfgview__opt');
    if (!b || b.dataset.view === view.by) return;
    view.by = b.dataset.view;
    try { localStorage.setItem('cfgView', view.by); } catch (err) { /* this run only */ }
    view.fresh = true;
    draw(view.list);
  });

  /* ---------------------------------------------------------- the speed */

  const UNITS = ['B', 'KB', 'MB', 'GB', 'TB'];
  function parts(n) {
    let v = Math.max(0, n || 0);
    let u = 0;
    while (v >= 1024 && u < UNITS.length - 1) { v /= 1024; u += 1; }
    return [v.toFixed(u === 0 ? 0 : (v >= 100 ? 0 : (v >= 10 ? 1 : 2))), UNITS[u]];
  }
  /* The figures the home's meter and the one over the top of the screen
     show, from the same reading; dimmed to zero while nothing is up. */
  function paintFlux(t) {
    const live = !!(t && t.live);
    $('cfgFlux').dataset.live = live ? '1' : '0';
    const put = (id, n, rate) => {
      const [v, u] = parts(live ? n : 0);
      $(id).textContent = v;
      $(`${id}U`).textContent = rate ? `${u}/s` : u;
    };
    put('cfgDownRate', t && t.downRate, true);
    put('cfgUpRate', t && t.upRate, true);
    put('cfgDownSum', t && t.down, false);
    put('cfgUpSum', t && t.up, false);
  }
  const homeTraffic = window.onTraffic;
  window.onTraffic = (t) => {
    if (typeof homeTraffic === 'function') homeTraffic(t);
    if ($('configsDlg').open) paintFlux(t && t.live && heroState() === 'on' ? t : null);
  };
  $('cfgPasteForm').addEventListener('submit', (e) => {
    e.preventDefault();
    addLinks($('cfgPasteText').value);
  });

  $('cfgSubShow').addEventListener('click', () => {
    addMenu(false);
    $('cfgPasteForm').hidden = true;
    const f = $('cfgSubForm');
    f.hidden = !f.hidden;
    $('cfgSubShow').setAttribute('aria-expanded', f.hidden ? 'false' : 'true');
    if (!f.hidden) $('cfgSubUrl').focus();
  });
  $('cfgSubForm').addEventListener('submit', async (e) => {
    e.preventDefault();
    const url = $('cfgSubUrl').value.trim();
    if (!url) return;
    say('Fetching…');
    const r = await call('addSub', { url });
    if (!r.ok) { say(r.error, true); return; }
    $('cfgSubUrl').value = '';
    $('cfgSubForm').hidden = true;
    $('cfgSubShow').setAttribute('aria-expanded', 'false');
    view.fresh = true;
    draw(r.list);
  });

  $('cfgTest').addEventListener('click', () => test({}));

  $('cfgAuto').addEventListener('click', async () => {
    const b = $('cfgAuto');
    if (b.disabled) return;
    const on = b.getAttribute('aria-checked') !== 'true';
    b.setAttribute('aria-checked', on ? 'true' : 'false');
    const live = (view.list && view.list.live) || {};
    if (live.on && view.list.selected) {
      // A different shape of connection, so it is made again.
      view.mine = true;
      state.mode = 'busy';
      render();
      setStatus('Connecting', 'busy', on ? 'Fastest, automatically' : 'The one you picked', '');
      const r = await call('connect', { id: view.list.selected, auto: on });
      if (!r.ok) { say(r.error, true); state.mode = 'off'; render(); }
    } else {
      say(on ? 'The next connection picks the fastest of the group, and moves on when it stops answering.'
        : 'The next connection uses the one you pick.');
    }
  });

  $('cfgList').addEventListener('click', (e) => {
    const card = e.target.closest('.cfgc');
    if (card && e.target.closest('.cfgc__go')) {
      connectCountry(card.dataset.cc);
      return;
    }
    if (card && e.target.closest('.cfgc__head')) {
      const cc = card.dataset.cc;
      if (view.open.has(cc)) view.open.delete(cc); else view.open.add(cc);
      draw(view.list);
      return;
    }
    const more = e.target.closest('.cfg__more');
    const r = e.target.closest('.cfg');
    if (!r) return;
    const id = r.dataset.id;
    if (more) {
      view.expanded = view.expanded === id ? null : id;
      view.renaming = null;
      draw(view.list);
      return;
    }
    choose(id);
  });
  $('cfgList').addEventListener('keydown', (e) => {
    const r = e.target.closest('.cfg');
    if (r && e.target === r && (e.key === 'Enter' || e.key === ' ')) {
      e.preventDefault();
      choose(r.dataset.id);
    }
  });
}());

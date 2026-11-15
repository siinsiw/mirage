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
  };

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

    const flag = el('span', 'cfg__flag');
    const cc = c.last && c.last.country;
    if (cc) flag.style.backgroundImage = flagUrl(canonCode(cc));
    r.appendChild(flag);

    const main = el('span', 'cfg__main');
    const name = el('span', 'cfg__name', c.name);
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

  function draw(list) {
    if (!list) return;
    view.list = list;
    const host = $('cfgList');
    host.textContent = '';
    let n = 0;
    for (const g of list.groups) {
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
    $('cfgAuto').setAttribute('aria-checked', list.auto ? 'true' : 'false');
    if (list.engines && !list.engines.singbox) {
      say('sing-box.exe is not beside Relay, so configs cannot connect or be tested.', true);
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
    $('cfgHero').dataset.state = hs;
    const id = live.on ? (live.using || live.current) : view.list.selected;
    const c = id && id !== 'auto' ? find(id) : null;
    const word = { off: 'Disconnected', busy: 'Connecting', on: 'Connected' }[hs]
      || ($('status').textContent || 'Not connected');
    $('cfgState').textContent = word;
    $('cfgClock').textContent = hs === 'on' && state.since
      ? clockText(Date.now() / 1000 - state.since) : '';
    const flag = $('cfgNowFlag');
    const cc = c && c.last && c.last.country;
    flag.style.backgroundImage = cc ? flagUrl(canonCode(cc)) : '';
    if (c) {
      $('cfgNowName').textContent = (live.on && live.auto ? 'Fastest · ' : '') + c.name;
      const r = c.last;
      const t = !r ? 'not tested' : r.ms ? `${r.ms} ms` : (FAIL[r.error] || 'failed');
      $('cfgNowSub').textContent = hs === 'busy'
        ? ($('statusWhere').textContent || '').trim() || 'Starting'
        : `${c.label} · ${t}`;
    } else {
      $('cfgNowName').textContent = view.list.configs.length ? 'No config picked' : 'No configs yet';
      $('cfgNowSub').textContent = view.list.configs.length ? 'Pick one below' : 'Paste links or add a subscription below';
    }
    const go = $('cfgGo');
    go.dataset.mode = hs === 'fail' ? 'off' : hs;
    // Busy with a connect that is not this section's: wait for it.
    go.disabled = (hs === 'off' && (!c || state.mode === 'busy'));
    const provider = !live.on && (state.mode === 'on' || (state.mode === 'busy' && !view.mine));
    $('cfgNote').textContent = provider
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
    const rows = Array.from(host.querySelectorAll('.cfg, .cfggroup')).slice(0, 30);
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

  /* A row pressed: with this section's connection up, it moves there (live,
     within a group); otherwise it becomes the one Connect will use. */
  async function choose(id) {
    const live = (view.list && view.list.live) || {};
    if (live.on) { connectTo(id); return; }
    const r = await call('pick', { id });
    if (r.ok) draw(r.list); else say(r.error, true);
  }

  $('cfgGo').addEventListener('click', async () => {
    const hs = heroState();
    if (hs === 'on' || hs === 'busy') {
      setStatus(hs === 'busy' ? 'Cancelling' : 'Disconnecting', 'off', 'Putting Windows back', '');
      const r = await window.pywebview.api.disconnect();
      view.mine = false;
      window.onDisconnected(r && r.session);
      refresh();
      return;
    }
    if (view.list && view.list.selected) connectTo(view.list.selected);
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
          r.querySelector('.cfg__flag').style.backgroundImage = flagUrl(canonCode(e.result.country));
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
      if (e.list) draw(e.list);
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
      view.expanded = null;
      view.renaming = null;
      say('');
      refresh();
    },
    refresh,
    list: () => view.list,
    draw,
  };

  $('cfgPaste').addEventListener('click', () => addLinks());
  $('cfgPasteForm').addEventListener('submit', (e) => {
    e.preventDefault();
    addLinks($('cfgPasteText').value);
  });

  $('cfgSubShow').addEventListener('click', () => {
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
    const on = b.getAttribute('aria-checked') !== 'true';
    b.setAttribute('aria-checked', on ? 'true' : 'false');
    const live = (view.list && view.list.live) || {};
    if (live.on && view.list.selected) {
      // A different shape of connection, so it is made again.
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

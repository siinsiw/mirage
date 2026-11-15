/* Configs' own settings, drawn from two tables: the screens, and the
   settings on each. The Python side keeps the same keys (configs/prefs.py)
   and checks every value that comes back, so this file only says what each
   one is called and how it is set.

   Loaded after app.js and configs.js: leans on $, shut and window.cfgSection.
   Every value is set as text; nothing here reaches innerHTML. */
(function () {
  'use strict';

  const call = (action, payload) =>
    window.pywebview.api.configs(action, payload || {});

  /* The screens, in the order they are reached for. One with no settings in
     FIELDS is not shown. */
  const SCREENS = [
    { id: 'connection', name: 'Connection', icon: 'power',
      sub: 'Proxy or whole PC, kill switch, starting on its own' },
    { id: 'filter', name: 'Anti-filtering', icon: 'split',
      sub: 'Fragment, noise, the TLS hello' },
    { id: 'dns', name: 'DNS', icon: 'globe',
      sub: 'Which resolver, IPv6' },
    { id: 'routing', name: 'Routing', icon: 'route',
      sub: 'Iranian sites direct, ads, per program' },
    { id: 'subs', name: 'Subscriptions', icon: 'refresh',
      sub: 'Updating on their own' },
    { id: 'tests', name: 'Tests and choosing', icon: 'gauge',
      sub: 'How a test is done, the order, Fastest automatically' },
    { id: 'advanced', name: 'Advanced', icon: 'settings',
      sub: 'Mux, the adapter, sharing, logs' },
  ];

  /* Each setting: its key (prefs.SCHEMA), its screen, what it is called and
     why someone would touch it, and how it is set:
       bool                       a switch
       choice  [[value, label]]   a row of pills
       int / range / text / url   a field, saved when it is left
       apps                       programs, one per line or comma-separated
     `when` greys a setting out while another makes it moot. */
  const FIELDS = [
    { key: 'fragment', screen: 'filter', type: 'bool',
      name: 'Split the TLS hello',
      why: 'Sends the first message to the server in pieces, so a filter that reads the site name in it cannot. Off unless the line needs it.' },
  ];

  const view = { values: null, screen: null };

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
    const n = $('cfgPrefsSaid');
    n.textContent = text || '';
    n.dataset.bad = bad ? '1' : '0';
  }

  async function set(key, value) {
    const r = await call('set', { values: { [key]: value } });
    if (!r.ok) { say(r.error, true); draw(); return; }
    view.values = r.values;
    say(r.reconnect ? 'Saved. It applies when you connect again.'
      : (r.changed && r.changed.length ? 'Saved.' : ''));
    draw();
    if (window.cfgSection) window.cfgSection.refresh();
  }

  function control(f) {
    const v = view.values[f.key];
    if (f.type === 'bool') {
      const b = el('button', 'toggle');
      b.type = 'button';
      b.setAttribute('role', 'switch');
      b.setAttribute('aria-checked', v ? 'true' : 'false');
      b.setAttribute('aria-label', f.name);
      b.appendChild(el('span'));
      b.addEventListener('click', () => set(f.key, !v));
      return b;
    }
    if (f.type === 'choice') {
      const box = el('div', 'cfgopt__seg');
      box.setAttribute('role', 'group');
      box.setAttribute('aria-label', f.name);
      for (const [value, label] of f.choices) {
        const b = el('button', 'cfgopt__segopt', label);
        b.type = 'button';
        b.setAttribute('aria-pressed', value === v ? 'true' : 'false');
        b.addEventListener('click', () => { if (value !== v) set(f.key, value); });
        box.appendChild(b);
      }
      return box;
    }
    const wide = f.type === 'url' || f.type === 'text' || f.type === 'apps';
    const input = el(f.type === 'apps' ? 'textarea' : 'input',
      `cfgopt__in${wide ? ' cfgopt__in--wide' : ''}`);
    if (f.type !== 'apps') input.type = f.type === 'int' ? 'number' : 'text';
    input.spellcheck = false;
    input.setAttribute('aria-label', f.name);
    if (f.placeholder) input.placeholder = f.placeholder;
    input.value = Array.isArray(v) ? v.join('\n') : (v === null || v === undefined ? '' : String(v));
    if (f.type === 'apps') input.rows = 3;
    const commit = () => {
      const was = Array.isArray(v) ? v.join('\n') : String(v ?? '');
      if (input.value !== was) set(f.key, f.type === 'int' ? Number(input.value) : input.value);
    };
    input.addEventListener('change', commit);
    input.addEventListener('keydown', (e) => {
      if (e.key === 'Enter' && f.type !== 'apps') { e.preventDefault(); input.blur(); }
    });
    return input;
  }

  function fieldRow(f) {
    const row = el('div', 'cfgopt');
    row.dataset.key = f.key;
    const text = el('span', 'cfgopt__text');
    text.appendChild(el('span', 'cfgopt__name', f.name));
    if (f.why) text.appendChild(el('span', 'cfgopt__why', f.why));
    row.appendChild(text);
    if (f.when && !f.when(view.values)) row.dataset.off = '1';
    row.appendChild(control(f));
    return row;
  }

  function draw() {
    const body = $('cfgPrefsBody');
    body.textContent = '';
    if (!view.values) return;
    const screen = SCREENS.find((s) => s.id === view.screen);
    $('cfgPrefsBack').hidden = !screen;
    $('cfgPrefsTitle').textContent = screen ? screen.name : 'Settings';
    if (!screen) {
      const menu = el('ul', 'cfgmenu');
      for (const s of SCREENS) {
        if (!FIELDS.some((f) => f.screen === s.id)) continue;
        const li = el('li');
        const b = el('button', 'cfgmenu__item');
        b.type = 'button';
        b.appendChild(icon(s.icon));
        const t = el('span', 'cfgmenu__text');
        t.appendChild(el('span', 'cfgmenu__name', s.name));
        t.appendChild(el('span', 'cfgmenu__sub', s.sub));
        b.appendChild(t);
        b.appendChild(icon('chev-right'));
        b.addEventListener('click', () => { view.screen = s.id; say(''); draw(); });
        li.appendChild(b);
        menu.appendChild(li);
      }
      body.appendChild(menu);
      return;
    }
    let group = null;
    for (const f of FIELDS.filter((x) => x.screen === screen.id)) {
      if (f.group && f.group !== group) {
        group = f.group;
        body.appendChild(el('p', 'cfgprefs__group', group));
      }
      body.appendChild(fieldRow(f));
    }
  }

  async function open() {
    const dlg = $('cfgPrefs');
    view.screen = null;
    say('');
    const r = await call('settings');
    if (!r.ok) { say(r.error, true); return; }
    view.values = r.values;
    draw();
    if (!dlg.open) dlg.showModal();
  }

  function back() {
    if (view.screen) { view.screen = null; say(''); draw(); return; }
    shut($('cfgPrefs'));
  }

  $('cfgPrefsOpen').addEventListener('click', open);
  $('cfgPrefsBack').addEventListener('click', back);
  $('cfgPrefsClose').addEventListener('click', () => shut($('cfgPrefs')));
  $('cfgPrefs').addEventListener('cancel', (e) => { e.preventDefault(); back(); });

  window.cfgPrefs = { open, SCREENS, FIELDS };
}());

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
    { key: 'mode', screen: 'connection', type: 'choice',
      name: 'What goes through it',
      why: 'The whole PC through an adapter (every program, asks for administrator approval), Windows’ proxy setting (browsers and most apps), or only the local port for the programs you point at it.',
      choices: [['system', 'Windows proxy'], ['tun', 'Whole PC'], ['local', 'Local port only']] },
    { key: 'kill_switch', screen: 'connection', type: 'bool',
      name: 'Kill switch',
      why: 'If the connection drops, nothing leaves on your normal line until you reconnect or disconnect. Separate from Mirage’s own.',
      when: (v) => v.mode !== 'local' },

    { key: 'split_mode', screen: 'routing', type: 'choice',
      name: 'Split tunnelling',
      why: 'Everything through the config, everything except the list, or only the list.',
      choices: [['off', 'Everything'], ['bypass', 'All but the list'], ['only', 'Only the list']] },
    { key: 'split_iran', screen: 'routing', type: 'bool',
      name: 'Iranian sites and addresses in the list',
      why: 'Iran’s address ranges and Iranian services, from the lists Mirage ships. With “All but the list” they go straight out, as banks and government sites need.',
      when: (v) => v.split_mode !== 'off' },
    { key: 'split_domains', screen: 'routing', type: 'apps',
      name: 'Sites and addresses',
      why: 'One per line: a name (it covers everything under it), an address or a prefix.',
      placeholder: 'example.com\n1.2.3.0/24',
      when: (v) => v.split_mode !== 'off' },
    { key: 'split_apps', screen: 'routing', type: 'apps',
      name: 'Programs',
      why: 'By file name, one per line. Only the whole-PC mode sees which program a connection is from.',
      placeholder: 'chrome.exe\nTelegram.exe',
      when: (v) => v.split_mode !== 'off' },

    { key: 'auto_connect', screen: 'connection', type: 'choice',
      name: 'Connect on opening',
      why: 'When Mirage opens and nothing is connected: the config you used last, or the fastest of its group.',
      choices: [['off', 'No'], ['last', 'The last one'], ['fastest', 'The fastest']] },
    { key: 'reconnect_on_drop', screen: 'connection', type: 'bool',
      name: 'Reconnect when it drops',
      why: 'With the kill switch off, a dropped connection is made again straight away instead of leaving you on your normal line.',
      when: (v) => !v.kill_switch || v.mode === 'local' },

    { key: 'fragment', screen: 'filter', type: 'bool',
      name: 'Split the TLS hello',
      why: 'Sends the first message to the server in pieces, so a filter that reads the site name in it cannot. Off unless the line needs it.' },
    { key: 'fragment_engine', screen: 'filter', type: 'choice', group: 'How it is split',
      name: 'Done by',
      why: 'sing-box’s own split, or Xray’s, which takes the sizes and gaps below. With Xray, every VLESS, VMess and Trojan config with TLS or Reality goes through Xray.',
      choices: [['singbox', 'sing-box'], ['xray', 'Xray, with sizes']],
      when: (v) => v.fragment },
    { key: 'fragment_style', screen: 'filter', type: 'choice',
      name: 'sing-box splits it into',
      why: 'TCP pieces, TLS records, or both. Both gets past the most filters; one of them may be faster on your line.',
      choices: [['both', 'Both'], ['tls', 'TCP pieces'], ['record', 'TLS records']],
      when: (v) => v.fragment && v.fragment_engine === 'singbox' },
    { key: 'fragment_packets', screen: 'filter', type: 'choice',
      name: 'Xray splits',
      why: 'Only the TLS hello, or the first few packets.',
      choices: [['tlshello', 'The TLS hello'], ['1-1', 'Packet 1'], ['1-2', 'Packets 1–2'], ['1-3', '1–3'], ['1-5', '1–5']],
      when: (v) => v.fragment && v.fragment_engine === 'xray' },
    { key: 'fragment_length', screen: 'filter', type: 'range',
      name: 'Piece size, bytes',
      why: 'A size, or a range to pick from at random, like 100-200. Smaller gets past more filters and is slower.',
      placeholder: '100-200',
      when: (v) => v.fragment && v.fragment_engine === 'xray' },
    { key: 'fragment_interval', screen: 'filter', type: 'range',
      name: 'Gap between pieces, ms',
      why: 'A wait, or a range, like 10-20. 0 sends them back to back.',
      placeholder: '10-20',
      when: (v) => v.fragment && v.fragment_engine === 'xray' },

    { key: 'remote_dns', screen: 'dns', type: 'url',
      name: 'Through the config',
      why: 'Names looked up through the config, for everything it carries. DNS over HTTPS: https://1.1.1.1/dns-query, https://8.8.8.8/dns-query, https://dns.google/dns-query …',
      placeholder: 'https://1.1.1.1/dns-query' },
    { key: 'direct_dns', screen: 'dns', type: 'url',
      name: 'Straight from this line',
      why: 'For the servers’ own names and what goes around the config. DNS over HTTPS, never your line’s resolver, which forges answers. An address (https://1.1.1.1/…) needs nothing else to start.',
      placeholder: 'https://1.1.1.1/dns-query' },
    { key: 'ipv6', screen: 'dns', type: 'choice',
      name: 'IPv6',
      why: 'Off unless your line and the config both carry it.',
      choices: [['off', 'Off'], ['prefer_ipv4', 'Prefer IPv4'], ['prefer_ipv6', 'Prefer IPv6']] },

    { key: 'sub_auto_hours', screen: 'subs', type: 'int',
      name: 'Update every, hours',
      why: 'While Mirage is open, each subscription is fetched again this often, through the connection when one is up. 0 for only when you press update.' },
    { key: 'sub_on_launch', screen: 'subs', type: 'bool',
      name: 'Update when Mirage opens',
      why: 'Every subscription, half a minute after the window opens.' },
    { key: 'sub_user_agent', screen: 'subs', type: 'text',
      name: 'Identify as',
      why: 'Some panels only serve a full list to a client they know, such as v2rayN or Hiddify. Empty for Mirage’s own.',
      placeholder: 'v2rayN/7.0' },

    { key: 'test_kind', screen: 'tests', type: 'choice',
      name: 'What a test measures',
      why: 'A real page loaded through the config - it also shows the country it comes out in - or only how fast the server opens a connection, which says nothing about whether it carries anything.',
      choices: [['real', 'Real page'], ['tcp', 'Connection only']] },
    { key: 'test_url', screen: 'tests', type: 'url',
      name: 'Page to load',
      why: 'Cloudflare’s trace page also says where the traffic comes out; another page gives only a time.',
      placeholder: 'https://www.cloudflare.com/cdn-cgi/trace',
      when: (v) => v.test_kind === 'real' },
    { key: 'test_timeout', screen: 'tests', type: 'int',
      name: 'Give up after, seconds' },
    { key: 'test_parallel', screen: 'tests', type: 'int',
      name: 'Tested at once',
      why: 'More is quicker over a long list and harder on a weak line.' },
    { key: 'sort', screen: 'tests', type: 'choice',
      name: 'Order of the list',
      choices: [['speed', 'Fastest first'], ['added', 'As added'], ['name', 'A–Z']] },
    { key: 'auto_interval', screen: 'tests', type: 'int', group: 'Fastest automatically',
      name: 'Check again every, minutes',
      why: 'While connected with Fastest automatically, how often the group is timed again.' },
    { key: 'auto_tolerance', screen: 'tests', type: 'int',
      name: 'Move only if faster by, ms',
      why: 'So the connection does not hop between two that are nearly the same.' },

    { key: 'mux', screen: 'advanced', type: 'bool', group: 'Mux',
      name: 'Multiplex',
      why: 'Many connections inside one to the server. Only if your server is sing-box with multiplex on; an Xray server will refuse it. Never used with Vision.' },
    { key: 'mux_protocol', screen: 'advanced', type: 'choice',
      name: 'Protocol', choices: [['h2mux', 'h2mux'], ['smux', 'smux'], ['yamux', 'yamux']],
      when: (v) => v.mux },
    { key: 'mux_streams', screen: 'advanced', type: 'int',
      name: 'Streams per connection', when: (v) => v.mux },
    { key: 'mux_padding', screen: 'advanced', type: 'bool',
      name: 'Padding', why: 'Harder to recognise; the server must have it on too.',
      when: (v) => v.mux },
    { key: 'tun_stack', screen: 'advanced', type: 'choice', group: 'Whole PC (adapter)',
      name: 'Network stack',
      why: 'Leave it on Auto unless something does not work: gVisor copes with more, System is lighter.',
      choices: [['auto', 'Auto'], ['system', 'System'], ['gvisor', 'gVisor'], ['mixed', 'Mixed']] },
    { key: 'tun_mtu', screen: 'advanced', type: 'int',
      name: 'MTU', why: '9000 unless a site stalls halfway; then try 1500.' },
    { key: 'strict_route', screen: 'advanced', type: 'bool',
      name: 'Strict route',
      why: 'Keeps traffic from leaking around the adapter. Off only if the local network stops working.' },
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

  async function set(key, value, typed) {
    const r = await call('set', { values: { [key]: value } });
    if (!r.ok) {
      say(r.error, true);
      // A switch or a pill goes back to what is saved; a field keeps what
      // was typed in it, so it can be put right rather than typed again.
      if (!typed) draw();
      return;
    }
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
      if (input.value !== was) set(f.key, f.type === 'int' ? Number(input.value) : input.value, true);
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
  // Capturing, so Esc on a screen steps back to the menu before app.js's
  // handler for every sheet shuts the whole window.
  $('cfgPrefs').addEventListener('cancel', (e) => {
    e.preventDefault();
    e.stopImmediatePropagation();
    back();
  }, { capture: true });

  window.cfgPrefs = { open, SCREENS, FIELDS };
}());

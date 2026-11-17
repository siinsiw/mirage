/* Midnight: the home screen's new parts, kept in step with app.js.

   app.js still owns the state and writes it where it always has - the status
   word, #statusClock, #exitIp, #realIp, #pickLabel, the flux rates, the
   connection on <html data-conn>. This file only reads those and draws the
   reference's pieces from them:

     the big clock      hh:mm:ss, its digits rolling as they change
     the shield line    "Secured connection" in green once the route is up
     the power disc     presses #act; rings breathe out while connected and
                        an accent arc runs round it while connecting
     the pin and tip    once the camera has landed, a pin on the country and
                        a glass card by it: flag, name, address, up and down
     the server card    the line under the place: the address that matters now

   Motion is GSAP, inside gsap.matchMedia so reduced motion gets still frames. */
(function () {
  const $ = (id) => document.getElementById(id);
  const root = document.documentElement;
  const conn = () => root.dataset.conn || 'off';
  const text = (id) => (($(id) && $(id).textContent) || '').trim();
  const has = (v) => v && v !== '—' && v !== '…' && v !== ' ';
  let reduced = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;

  /* ---------------------------------------------------------------- clock */

  const clock = $('bigClock');
  function seconds(s) {
    const p = (s || '').split(':').map((x) => parseInt(x, 10));
    if (!p.length || p.some(isNaN)) return 0;
    return p.reduce((a, b) => a * 60 + b, 0);
  }
  const two = (n) => String(n).padStart(2, '0');
  function paintClock() {
    const on = conn() === 'on';
    const s = on ? seconds(text('statusClock')) : 0;
    const t = `${two(Math.floor(s / 3600))}:${two(Math.floor(s / 60) % 60)}:${two(s % 60)}`;
    clock.dataset.on = String(on);
    // One span a character; only the ones that changed roll.
    if (clock.children.length !== t.length) {
      clock.replaceChildren(...[...t].map((c) => {
        const span = document.createElement('span');
        span.className = c === ':' ? 'bigclock__sep' : 'bigclock__d';
        span.textContent = c;
        return span;
      }));
      return;
    }
    [...t].forEach((c, i) => {
      const span = clock.children[i];
      if (span.textContent === c) return;
      span.textContent = c;
      if (!reduced && window.gsap) {
        gsap.fromTo(span, { yPercent: 55, opacity: 0 },
          { yPercent: 0, opacity: 1, duration: 0.34, ease: 'expo.out', overwrite: true });
      }
    });
  }

  /* ------------------------------------------------------- shield and word */

  const SECURE = {
    on: ['Secured connection', '#i-shield-ok'],
    busy: ['Securing your connection', '#i-shield-no'],
    fail: ['Could not connect', '#i-shield-no'],
    off: ['Not protected', '#i-shield-no'],
  };
  function paintSecure() {
    const [word, ico] = SECURE[conn()] || SECURE.off;
    $('secureWord').textContent = word;
    $('secure').querySelector('use').setAttribute('href', ico);
    $('secure').dataset.state = conn();
  }

  /* ------------------------------------------------------------ the disc */

  const power = $('power');
  const act = $('act');
  power.addEventListener('click', () => { if (!act.disabled) act.click(); });
  function paintPower() {
    const c = conn();
    power.dataset.mode = c;
    power.disabled = act.disabled && c !== 'on' && c !== 'busy';
    power.setAttribute('aria-label', ({ on: 'Disconnect', busy: 'Cancel' })[c] || 'Connect');
  }

  let loops = [];
  function stopLoops() { loops.forEach((t) => t.kill()); loops = []; }
  /* Mirage's astrolabe ring round the orb turns as one layer: once in a
     minute at rest, twice as fast while up, and fast - a turn in 0.8s, its
     bead gone white - while connecting. A failure shakes the Connect button
     once. */
  const astro = document.querySelector('.power__astro');
  function startLoops() {
    stopLoops();
    if (reduced || !window.gsap) return;
    const c = conn();
    if (astro) {
      const turn = { busy: 0.8, on: 30 }[c] || 60;
      loops.push(gsap.to(astro, { rotation: '+=360', duration: turn, ease: 'none', repeat: -1 }));
    }
    if (c === 'on') {
      const rings = power.querySelectorAll('.power__wave i');
      rings.forEach((r, i) => {
        loops.push(gsap.fromTo(r, { scale: 1, opacity: 0.55 },
          { scale: 1.85, opacity: 0, duration: 3.2, ease: 'power1.out', repeat: -1, delay: i * 1.07 }));
      });
    } else if (c === 'fail') {
      loops.push(gsap.fromTo(power, { x: -4 }, { x: 4, duration: 0.06, ease: 'none', yoyo: true, repeat: 7,
        onComplete: () => gsap.set(power, { x: 0 }) }));
    }
  }

  // A press sinks the disc and lets it spring back.
  const disc = power.querySelector('.power__disc');
  power.addEventListener('pointerdown', () => {
    if (!reduced && window.gsap && !power.disabled) gsap.to(disc, { scale: 0.94, duration: 0.12, ease: 'power2.out' });
  });
  const release = () => {
    if (!reduced && window.gsap) gsap.to(disc, { scale: 1, duration: 0.6, ease: 'elastic.out(1, 0.45)' });
  };
  power.addEventListener('pointerup', release);
  power.addEventListener('pointerleave', release);

  /* ------------------------------------------------------- the pin and tip */

  const tip = $('tip'), pin = $('pin');
  let tipTimer = 0, tipShown = false;
  function fillTip() {
    $('tipName').textContent = text('pickLabel');
    $('tipFlag').style.backgroundImage = $('pickFlag').style.backgroundImage || '';
    $('tipIp').textContent = has(text('exitIp')) ? text('exitIp') : 'checking…';
    $('tipUp').textContent = text('fluxUpRate') || 'idle';
    $('tipDown').textContent = text('fluxDownRate') || 'idle';
  }
  function showTip(on) {
    clearTimeout(tipTimer);
    if (!on) {
      tipShown = false;
      if (window.gsap && !reduced) gsap.to([tip, pin], { autoAlpha: 0, duration: 0.2, overwrite: true });
      else { tip.style.visibility = pin.style.visibility = 'hidden'; }
      return;
    }
    // The camera takes about this long to come down onto the country.
    tipTimer = setTimeout(() => {
      tipShown = true;
      fillTip();
      if (window.gsap && !reduced) {
        gsap.fromTo(pin, { autoAlpha: 0, scale: 0.4 }, { autoAlpha: 1, scale: 1, duration: 0.5, ease: 'back.out(2)' });
        gsap.fromTo(tip, { autoAlpha: 0, scale: 0.86, y: 6 },
          { autoAlpha: 1, scale: 1, y: 0, duration: 0.7, ease: 'elastic.out(1, 0.75)', delay: 0.08 });
      } else { tip.style.visibility = pin.style.visibility = 'visible'; tip.style.opacity = pin.style.opacity = 1; }
    }, reduced ? 0 : 2700);
  }

  /* ------------------------------------------------------- the server card */

  function paintSub() {
    const c = conn();
    let line;
    if (c === 'on') line = has(text('exitIp')) ? `IP ${text('exitIp')}` : 'checking the address…';
    else if (c === 'busy') line = text('statusWhere') || 'Looking for a server';
    else line = has(text('realIp')) ? `Your address ${text('realIp')}` : 'Checking your address…';
    $('pickSub').textContent = line;
    $('pick').dataset.flag = $('pickFlag').style.backgroundImage ? '1' : '0';
    paintIps(c);
  }

  /* The address line under the place: connected, the one you had and the one
     sites see now; otherwise the one you have. Built as text, never HTML. */
  function paintIps(c) {
    const box = $('homeIps');
    if (!box) return;
    const real = has(text('realIp')) ? text('realIp') : '';
    const span = (cls, value) => {
      const s = document.createElement('span');
      s.className = cls;
      s.textContent = value;
      return s;
    };
    if (c === 'on') {
      const now = has(text('exitIp')) ? text('exitIp') : 'checking…';
      const arrow = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
      arrow.setAttribute('class', 'homeline__to');
      arrow.setAttribute('viewBox', '0 0 24 24');
      arrow.setAttribute('role', 'img');
      arrow.setAttribute('aria-label', 'now');
      const p = document.createElementNS('http://www.w3.org/2000/svg', 'path');
      p.setAttribute('d', 'M5 12h14M13 6l6 6-6 6');
      arrow.append(p);
      box.replaceChildren(span('homeline__was', real || '—'), arrow, span('homeline__now', now));
    } else {
      const place = text('realPlace');
      const bits = [span('', 'Your IP '), span('homeline__now', real || 'checking…')];
      if (real && place && place !== 'could not check') bits.push(span('', ` · ${place}`));
      box.replaceChildren(...bits);
    }
  }

  /* ------------------------------------------------------------- wiring */

  let lastConn = null;
  function paint() {
    const c = conn();
    paintSecure();
    paintPower();
    paintClock();
    paintSub();
    if (c !== lastConn) {
      lastConn = c;
      startLoops();
      showTip(c === 'on');
    } else if (tipShown) fillTip();
  }

  const watch = new MutationObserver(paint);
  watch.observe(root, { attributes: true, attributeFilter: ['data-conn', 'data-theme'] });
  watch.observe(act, { attributes: true, attributeFilter: ['disabled'] });
  for (const id of ['statusClock', 'exitIp', 'realIp', 'realPlace', 'pickLabel', 'statusWhere', 'fluxUpRate', 'fluxDownRate']) {
    const el = $(id);
    if (el) watch.observe(el, { childList: true, characterData: true, subtree: true });
  }
  watch.observe($('pickFlag'), { attributes: true, attributeFilter: ['style'] });

  if (window.gsap) {
    gsap.matchMedia().add('(prefers-reduced-motion: reduce)', () => {
      reduced = true; stopLoops();
      return () => { reduced = false; startLoops(); };
    });
  }
  document.addEventListener('DOMContentLoaded', paint);
  paint();
})();

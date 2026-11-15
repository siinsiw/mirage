/* The way between Mirage and Configs.

   Configs is an app of its own inside this one: its own home, Connect and
   settings in #configsDlg. Going in, the window goes dark and Mirage's eye
   opens - the lid lifts, the iris comes up, the red ring closes round it -
   and Configs is there; going back, the same in reverse. About 0.7 s each
   way. Only transform and opacity move, plus the ring's dash on one small
   circle, over a still background: a weak machine keeps its frame rate.
   Under reduced motion it is a plain cut.

   Loaded after app.js and configs.js: leans on $, CALM, state and
   window.cfgSection. The section last opened is remembered (setSection) and
   reopened at start. */
(function () {
  'use strict';

  const dlg = $('configsDlg');
  const veil = $('cfgVeil');
  const lid = veil.querySelector('.cfgveil__lid');
  const iris = veil.querySelector('.cfgveil__iris');
  const ring = veil.querySelector('.cfgveil__ring');
  const word = $('cfgVeilWord');
  const RING = 214;          // the ring's dash length, as .ms__done draws it
  let going = null;          // the timeline under way, if any

  const still = () => CALM || !window.gsap;

  /* A window with no frames to give (minimised, covered, a background
     WebView) never ticks GSAP, and a way in that never finishes is a
     section that never opens. So the timeline is finished by the clock if
     the frames have not done it. */
  function finishAnyway(tl) {
    setTimeout(() => { if (going === tl) tl.progress(1); }, 1600);
  }

  function remember(section) {
    try {
      const r = window.pywebview.api.setSection(section);
      if (r && r.catch) r.catch(() => {});
    } catch (e) { /* only a preference */ }
  }

  function openNow() {
    if (!dlg.open) dlg.showModal();
    window.cfgSection.opened();
  }

  /* Into Configs. `quiet` at start, where there is nothing to come from. */
  function enter(quiet) {
    if (dlg.open || going) return;
    remember('configs');
    if (quiet || still()) { openNow(); return; }
    word.textContent = 'Configs';
    veil.hidden = false;
    gsap.set(veil, { opacity: 0 });
    gsap.set(lid, { scaleY: 0.1 });
    gsap.set(iris, { scale: 0.4, opacity: 0 });
    gsap.set(ring, { strokeDashoffset: RING });
    going = gsap.timeline({
      defaults: { ease: 'power3.out' },
      onComplete: () => { going = null; },
    });
    going.to(veil, { opacity: 1, duration: 0.16, ease: 'power1.out' })
      .to(lid, { scaleY: 1, duration: 0.3 }, '<0.06')
      .to(iris, { scale: 1, opacity: 1, duration: 0.28 }, '<0.04')
      .to(ring, { strokeDashoffset: 0, duration: 0.34, ease: 'power2.inOut' }, '<0.06')
      .add(openNow)
      // In the timeline, so a finish by the clock leaves it showing too.
      .fromTo(dlg, { opacity: 0, y: 8 },
        { opacity: 1, y: 0, duration: 0.22, ease: 'power2.out', clearProps: 'opacity,transform',
          immediateRender: false }, '<')
      .add(() => { veil.hidden = true; }, '+=0.02');
    finishAnyway(going);
  }

  /* Back to Mirage. */
  function leave() {
    if (!dlg.open || going) return;
    remember('main');
    const prefs = $('cfgPrefs');
    if (prefs.open) prefs.close();
    if (still()) { dlg.close(); return; }
    word.textContent = 'Mirage';
    veil.hidden = false;
    gsap.set(veil, { opacity: 1 });
    gsap.set(lid, { scaleY: 1 });
    gsap.set(iris, { scale: 1, opacity: 1 });
    gsap.set(ring, { strokeDashoffset: 0 });
    going = gsap.timeline({
      defaults: { ease: 'power2.in' },
      onComplete: () => { going = null; veil.hidden = true; },
    });
    going.to(dlg, { opacity: 0, y: 8, duration: 0.18 })
      .add(() => { dlg.close(); gsap.set(dlg, { clearProps: 'opacity,transform' }); })
      .to(ring, { strokeDashoffset: RING, duration: 0.3, ease: 'power2.inOut' })
      .to(iris, { scale: 0.4, opacity: 0, duration: 0.24 }, '<0.04')
      .to(lid, { scaleY: 0.1, duration: 0.26 }, '<0.04')
      .to(veil, { opacity: 0, duration: 0.18, ease: 'power1.in' }, '-=0.08');
    finishAnyway(going);
  }

  $('configsOpen').addEventListener('click', () => enter(false));
  $('cfgToMain').addEventListener('click', leave);
  // Esc goes back to Mirage, the way the switch does.
  dlg.addEventListener('cancel', (e) => { e.preventDefault(); leave(); });

  window.cfgShell = { enter, leave, isOpen: () => dlg.open };
}());

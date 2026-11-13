/* The world behind the home screen (three.js, plain script: the window loads
   over file://, where modules are refused - see three.min.js).

   It tells the connection as a journey:
     off    straight down onto the country your own address is in, filled with
            its flag; every other country is only its border, in faint neon
     busy   rings of signal ripple out of it while the servers are asked, and
            the camera eases back
     on     the camera pulls out until the planet is in view, turns, and
            comes down onto the country the traffic really leaves from, which
            fills with its flag from its middle (no line is drawn between the
            two countries - the zoom out and in is the journey)
     fail   the camera turns part of the way, the planet reddens for a
            moment, and the camera goes home

   Only one country is ever filled; the neighbours stay outlines. Three
   tilted neon orbits ride round the planet and show only once the camera is
   far enough out to see them.

   What it is told: the state (it watches data-conn), the home country (from
   the address Cloudflare saw before anything of ours was in the way), the
   exit country and the pick. The outlines come from world-data.js
   (Natural Earth, public domain; app/make_world.py). */
(function () {
  'use strict';

  const D2R = Math.PI / 180;
  const FOV = 36;
  const MAX_DPR = 1.75;
  const IDLE_MS = 1000 / 30 - 4;                  /* nothing travelling: 30 fps */
  /* Mirage's dark reds where the Midnight neon was, and the connection in
     Mirage's white rather than green (2026-11-13: black, red and white only). */
  const NEON = { peri: 0xC21C2E, aqua: 0x8E1622, orchid: 0xE0606E, pulse: 0xF4F1EF };
  const INK = { peri: 0x4C63B8, aqua: 0x1E8C98, orchid: 0x8A54C4, pulse: 0x139A48 };   /* the same, for a light page */

  const dirOf = (lon, lat) => {
    const T = window.THREE;
    return new T.Vector3(Math.cos(lat * D2R) * Math.sin(lon * D2R), Math.sin(lat * D2R),
                         Math.cos(lat * D2R) * Math.cos(lon * D2R));
  };

  window.Atlas = (function () {
    let T = null, W = null;
    let canvas = null, renderer = null, scene = null, camera = null;
    let globeMat = null, borders = null, orbits = [], ripples = [];
    let outlineO = null, outlineD = null, arc = null;
    let started = false, ticking = false, isCalm = () => false, drawnAt = 0, moving = 0;
    let light = false;
    let landStyle = 'flag';                         /* 'flag' or 'dots' - see GLOBE_FRAG */
    const landDefines = () => (landStyle === 'flag' ? { FLAG_LAND: '' } : {});
    let state = 'off', homeCode = null, liveCode = null, pickedCode = null;
    let originCode = null, destCode = null, busyTl = null, journey = null;
    const view = { dir: null, dist: 5, shift: 0 };
    const lean = { x: 0, y: 0 };
    const S = { t: 0, orbitSpeed: 1, blink: 0.5, traffic: 0, trafficSpeed: 0.3 };

    /* ------------------------------------------------------------ geography */

    const has = (code) => !!(code && W && W.by[code]);
    const middle = (code) => dirOf(...(W.mid[code] || [0, 0]));
    /* how far out to stand to see the whole country from straight above,
       with its neighbours round it */
    function heightFor(code) {
      const b = W.box[code], lat = (b[1] + b[3]) / 2;
      const halfW = (b[2] - b[0]) / 2 * Math.cos(lat * D2R) * D2R, halfH = (b[3] - b[1]) / 2 * D2R;
      const t = Math.tan(FOV / 2 * D2R), aspect = camera.aspect;
      return 1 + Math.min(Math.max(halfH / t, halfW / (t * aspect)) * 1.6 + 0.12, 3.2);
    }

    /* A country's fill: its outline painted into a texture laid over its own
       box, with the flag stretched over the same box. Both read with the
       same coordinates, so the flag never wanders off the land. */
    const cache = new Map();
    function countryTextures(code) {
      if (cache.has(code)) return cache.get(code);
      const b = W.box[code];
      const cw = 1024, ch = Math.max(64, Math.min(1024, Math.round(1024 * (b[3] - b[1]) / Math.max(0.01, b[2] - b[0]))));
      const cv = document.createElement('canvas'); cv.width = cw; cv.height = ch;
      const g = cv.getContext('2d');
      g.fillStyle = '#fff';
      for (const i of W.by[code]) {
        const r = W.rings[i];
        g.beginPath();
        for (let k = 0; k < r.length; k += 2) {
          const x = (r[k] - b[0]) / (b[2] - b[0]) * cw, y = (b[3] - r[k + 1]) / (b[3] - b[1]) * ch;
          if (k) g.lineTo(x, y); else g.moveTo(x, y);
        }
        g.closePath(); g.fill();
      }
      const mask = new T.CanvasTexture(cv);
      const flag = new T.Texture();
      const entry = { mask, flag, ready: false };
      const img = new Image();
      img.onload = () => {
        /* 2048 across: the camera comes down close enough over a small country
           that 1024 showed its pixels. The colours go in and out untouched -
           this shader writes what it reads, so marking the flag sRGB only had
           it decoded to linear and never encoded back, a flag a shade darker
           than its own file. */
        const f = document.createElement('canvas'); f.width = 2048; f.height = 1536;
        f.getContext('2d').drawImage(img, 0, 0, 2048, 1536);
        flag.image = f; flag.colorSpace = T.NoColorSpace;
        flag.anisotropy = renderer.capabilities.getMaxAnisotropy(); flag.needsUpdate = true;
        entry.ready = true; kick();
      };
      /* the map says gb, the flag files (and the rest of the app) say uk */
      img.src = `flags/${code === 'gb' ? 'uk' : code}.svg`;
      cache.set(code, entry);
      return entry;
    }

    /* ------------------------------------------------------------- building */

    const additive = (color, opacity) => new T.MeshBasicMaterial({
      color, transparent: true, opacity, depthWrite: false,
      blending: light ? T.NormalBlending : T.AdditiveBlending });

    function glowTexture() {
      const c = document.createElement('canvas'); c.width = c.height = 64;
      const g = c.getContext('2d'), r = g.createRadialGradient(32, 32, 0, 32, 32, 32);
      r.addColorStop(0, 'rgba(255,255,255,1)'); r.addColorStop(0.25, 'rgba(255,255,255,.55)');
      r.addColorStop(1, 'rgba(255,255,255,0)');
      g.fillStyle = r; g.fillRect(0, 0, 64, 64);
      return new T.CanvasTexture(c);
    }
    let GLOW = null;
    const sprite = (color, size) => {
      const s = new T.Sprite(new T.SpriteMaterial({ map: GLOW, color, transparent: true, depthWrite: false,
        blending: light ? T.NormalBlending : T.AdditiveBlending }));
      s.scale.setScalar(size);
      return s;
    };

    const GLOBE_VERT = `varying vec3 vN; varying vec3 vV; varying vec3 vO;
      void main(){ vO = normalize(position); vec4 mv = modelViewMatrix * vec4(position, 1.);
        vV = normalize(-mv.xyz); vN = normalize(normalMatrix * normal); gl_Position = projectionMatrix * mv; }`;
    /* Two ways to draw the land, picked by FLAG_LAND (see style()):

       flag (the default, and the lighter one) - the land is one soft dark
       ground over a black sea with a faint graticule; the country in focus
       is its flag, the picture itself laid into its outline, spreading from
       where the signal landed behind a thin line of light. Each pixel reads
       the land once, each country's outline once and each flag once, with
       mip levels from the screen-space rate of the coordinates (taken across
       the antimeridian without a seam), so nothing shimmers far out.

       dots (Midnight, as on Android) - the land is a grid of round white dots;
       the dots of the country in focus take the flag's colour at their own
       place in its box. The grid's pitch (uCell, degrees) follows the camera's
       height; between two pitches the grids cross-fade (uCellMix), so while
       the camera moves every pixel works the grid out twice.

       Canvas textures arrive flipped: north is v = 1 (the world mask is laid
       in the same way). */
    const GLOBE_FRAG = `uniform sampler2D uMaskO, uMaskD, uFlagO, uFlagD, uWorld; uniform vec4 uBoxO, uBoxD;
      uniform float uFillO, uFillD, uSpanO, uSpanD, uRed, uLight, uHasO, uHasD, uCell, uCellMix, uFlagW;
      uniform vec3 uLandO, uLandD;
      varying vec3 vN; varying vec3 vV; varying vec3 vO;
    #ifdef FLAG_LAND
      /* where in a country's box this pixel is, how fast that changes across
         the screen, and how much of the country is under it. Outside the box
         (most of the planet) nothing is read; the gradients are explicit, so
         reading only on some pixels is allowed. */
      float boxed(sampler2D m, vec4 box, float has, vec2 ll, vec2 gx, vec2 gy, out vec2 uv, out vec2 dx, out vec2 dy) {
        vec2 size = max(box.zw - box.xy, vec2(.01));
        uv = (ll - box.xy) / size;
        dx = vec2(gx.x, gy.x) / size; dy = vec2(gx.y, gy.y) / size;
        if (has < .5 || uv.x < 0. || uv.x > 1. || uv.y < 0. || uv.y > 1.) return 0.;
        return textureGrad(m, uv, dx, dy).r;
      }
      vec3 flatLand(vec2 ll, float shade, vec3 sea) {
        /* the rate of longitude, from whichever of two seams is farther away */
        float lon2 = mod(ll.x + 360., 360.);
        vec2 a = vec2(dFdx(ll.x), dFdy(ll.x)), b = vec2(dFdx(lon2), dFdy(lon2));
        vec2 gx = dot(a, a) < dot(b, b) ? a : b;
        vec2 gy = vec2(dFdx(ll.y), dFdy(ll.y));
        vec2 w = vec2(abs(gx.x) + abs(gx.y), abs(gy.x) + abs(gy.y));
        vec2 g = abs(fract(ll / 15. + .5) - .5) * 15.;
        vec2 ln = 1. - smoothstep(vec2(0.), w * 1.4, g);
        float grid = max(ln.x * (1. - smoothstep(70., 80., abs(ll.y))), ln.y) * .045 * (1. - uLight);
        float l = textureGrad(uWorld, vec2((ll.x + 180.) / 360., (ll.y + 90.) / 180.),
                              vec2(gx.x / 360., gy.x / 180.), vec2(gx.y / 360., gy.y / 180.)).r;
        vec2 uvO, uvD, dxO, dyO, dxD, dyD;
        float mO = boxed(uMaskO, uBoxO, uHasO, ll, gx, gy, uvO, dxO, dyO);
        float mD = boxed(uMaskD, uBoxD, uHasD, ll, gx, gy, uvD, dxD, dyD);
        l = max(l, max(mO, mD));
        vec3 ground = mix(vec3(.10, .068, .07), vec3(.80, .82, .86), uLight) * (.62 + .38 * shade)
                      + max(mO, mD) * .035;
        vec3 c = mix(sea + grid * vec3(1., .45, .45), ground, l);
        if (mO + mD < .001) return c;
        float aO = acos(clamp(dot(vO, uLandO), -1., 1.)), aD = acos(clamp(dot(vO, uLandD), -1., 1.));
        float rO = uFillO * uSpanO, rD = uFillD * uSpanD;
        float fO = mO * (1. - smoothstep(rO - .02, rO, aO));
        float fD = mD * (1. - smoothstep(rD - .02, rD, aD));
        /* a thin line of light where the flag is still spreading */
        float eO = mO * (1. - smoothstep(0., .012, abs(aO - rO))) * step(.001, uFillO) * step(uFillO, .999);
        float eD = mD * (1. - smoothstep(0., .012, abs(aD - rD))) * step(.001, uFillD) * step(uFillD, .999);
        vec3 flagO = vec3(0.), flagD = vec3(0.);
        if (fO + eO > .001) flagO = textureGrad(uFlagO, clamp(uvO, 0., 1.), dxO, dyO).rgb;
        if (fD + eD > .001) flagD = textureGrad(uFlagD, clamp(uvD, 0., 1.), dxD, dyD).rgb;
        float f = max(fO, fD);
        vec3 tint = fD >= fO ? flagD : flagO;
        /* the flag lies on the planet: lit from the same side, with a soft sheen */
        vec3 L = normalize(vec3(-.4, .6, .7));
        float sheen = pow(max(dot(reflect(-L, vN), vV), 0.), 18.) * .14;
        c = mix(c, tint * (.74 + .26 * shade) + sheen, f);
        c += (flagO * .6 + .3) * eO * .55 + (flagD * .6 + .3) * eD * .55;
        return c;
      }
    #endif
      float inside(sampler2D m, vec4 box, vec2 ll, out vec2 uv) {
        uv = (ll - box.xy) / (box.zw - box.xy);
        if (uv.x < 0. || uv.x > 1. || uv.y < 0. || uv.y > 1.) return 0.;
        return textureLod(m, uv, 0.).r;
      }
      vec3 flagAt(sampler2D t, vec2 uv, vec4 box, float cell) {
        float texels = cell / max(box.z - box.x, .01) * uFlagW;
        return textureLod(t, clamp(uv, 0., 1.), max(log2(texels), 0.)).rgb;
      }
      vec3 dirAt(vec2 ll) { vec2 r = radians(ll); return vec3(cos(r.y) * sin(r.x), sin(r.y), cos(r.y) * cos(r.x)); }
      vec4 dots(vec2 ll, float cell, float px, float shade) {
        float row = floor((ll.y + 90.) / cell);
        float latC = -90. + (row + .5) * cell;
        float across = max(1., floor(360. * cos(radians(latC)) / cell));
        float stepL = 360. / across;
        float lonC = -180. + (floor((ll.x + 180.) / stepL) + .5) * stepL;
        vec2 c = vec2(lonC, latC);
        float r = length(vec2((ll.x - lonC) * cos(radians(ll.y)), ll.y - latC)) / cell;
        float aa = max(px / cell, .02);
        vec2 uvO, uvD;
        float mO = inside(uMaskO, uBoxO, c, uvO) * uHasO, mD = inside(uMaskD, uBoxD, c, uvD) * uHasD;
        float land = max(step(.5, textureLod(uWorld, vec2((lonC + 180.) / 360., (latC + 90.) / 180.), 0.).r),
                         max(step(.5, mO), step(.5, mD)));
        vec3 p = dirAt(c);
        float aO = acos(clamp(dot(p, uLandO), -1., 1.)), aD = acos(clamp(dot(p, uLandD), -1., 1.));
        float fO = step(.5, mO) * (1. - smoothstep(uFillO * uSpanO - .02, uFillO * uSpanO, aO));
        float fD = step(.5, mD) * (1. - smoothstep(uFillD * uSpanD - .02, uFillD * uSpanD, aD));
        float f = max(fO, fD);
        vec3 white = mix(vec3(.86, .86, .86), vec3(.30, .34, .42), uLight) * (.55 + .45 * shade);
        vec3 tint = fD >= fO ? flagAt(uFlagD, uvD, uBoxD, cell) : flagAt(uFlagO, uvO, uBoxO, cell);
        vec3 col = mix(white, tint * (.92 + .16 * shade), f);
        float front = step(.5, mD) * uHasD * (1. - smoothstep(0., .05, abs(aD - uFillD * uSpanD)))
                      * step(.001, uFillD) * step(uFillD, .999);
        float radius = mix(.30, .37, f) + .08 * front;
        float a = (1. - smoothstep(radius - aa, radius + aa, r)) * land;
        float sea = (1. - land) * (1. - smoothstep(.07 - aa, .07 + aa, r)) * mix(.10, .16, uLight);
        return vec4(land > .5 ? col : white, max(a * mix(.78, 1., f), sea));
      }
      void main(){
        float lat = degrees(asin(clamp(vO.y, -1., 1.))), lon = degrees(atan(vO.x, vO.z));
        vec2 ll = vec2(lon, lat);
        float shade = .55 + .45 * max(dot(vN, normalize(vec3(-.4, .6, .7))), 0.);
        vec3 c = mix(vec3(.048, .026, .028), vec3(.90, .92, .95), uLight) * mix(shade, 1., uLight * .6);
    #ifdef FLAG_LAND
        c = flatLand(ll, shade, c);
    #else
        float px = max(fwidth(ll.y), 1e-4);
        vec4 d = mix(dots(ll, uCell, px, shade), dots(ll, uCell * 2., px, shade), uCellMix);
        c = mix(c, d.rgb, d.a);
    #endif
        float l = dot(c, vec3(.3, .59, .11)); c = mix(c, vec3(l * 1.2, l * .3, l * .35), uRed);
        float rim = pow(1. - max(dot(vN, vV), 0.), 3.2);
        c += mix(vec3(.62, .16, .19) * .55, vec3(-.25, -.2, -.05), uLight) * rim;
        gl_FragColor = vec4(c, 1.);
      }`;

    /* Every country's land on one equirectangular canvas, white on black:
       what the land is drawn from. Rings are unwrapped across the antimeridian
       and drawn a world to either side, so what runs off one edge comes back
       in. On the card it is one byte a texel (a quarter of the canvas's RGBA)
       with mip levels, so the coasts hold still with the planet far out. */
    function worldMask() {
      const w = Math.min(4096, renderer.capabilities.maxTextureSize || 4096), h = w / 2;
      const cv = document.createElement('canvas'); cv.width = w; cv.height = h;
      const g = cv.getContext('2d');
      g.fillStyle = '#000'; g.fillRect(0, 0, w, h);
      g.fillStyle = '#fff';
      for (const r of W.rings) {
        if (r.length < 6) continue;
        const p = new Path2D();
        let shift = 0, prev = r[0];
        for (let k = 0; k < r.length; k += 2) {
          let lon = r[k] + shift;
          if (k) {
            if (lon - prev > 180) { shift -= 360; lon -= 360; } else if (lon - prev < -180) { shift += 360; lon += 360; }
          }
          prev = lon;
          const x = (lon + 180) / 360 * w, y = (90 - r[k + 1]) / 180 * h;
          if (k) p.lineTo(x, y); else p.moveTo(x, y);
        }
        p.closePath();
        for (const dx of [0, w, -w]) { g.save(); g.translate(dx, 0); g.fill(p); g.restore(); }
      }
      /* rows go in bottom first, as a canvas texture would arrive: north is v = 1 */
      const px = g.getImageData(0, 0, w, h).data, data = new Uint8Array(w * h);
      for (let y = 0; y < h; y += 1) {
        const from = (h - 1 - y) * w * 4, to = y * w;
        for (let x = 0; x < w; x += 1) data[to + x] = px[from + x * 4];
      }
      cv.width = cv.height = 1;
      const t = new T.DataTexture(data, w, h, T.RedFormat, T.UnsignedByteType);
      t.generateMipmaps = true; t.minFilter = T.LinearMipmapLinearFilter; t.magFilter = T.LinearFilter;
      t.wrapS = T.RepeatWrapping; t.unpackAlignment = 1; t.colorSpace = T.NoColorSpace; t.needsUpdate = true;
      return t;
    }

    /* The faint network the reference draws over its planet: the middles of
       the larger countries, each tied to its two nearest within ~30 degrees. */
    function network(tone) {
      const codes = Object.keys(W.box).filter((c) => {
        const b = W.box[c]; return (b[2] - b[0]) * (b[3] - b[1]) > 40 && W.mid[c];
      }).sort();
      const pts = codes.map((c) => dirOf(...W.mid[c]));
      const pos = [], seen = new Set();
      pts.forEach((a, i) => {
        const near = pts.map((b, j) => [j, a.angleTo(b)]).filter(([j]) => j !== i)
          .sort((x, y) => x[1] - y[1]).slice(0, 2);
        for (const [j, ang] of near) {
          const key = Math.min(i, j) + ':' + Math.max(i, j);
          if (ang > 0.52 || seen.has(key)) continue;
          seen.add(key);
          let prevP = a;
          for (let k = 1; k <= 10; k++) {
            const q = a.clone().lerp(pts[j], k / 10).normalize();
            pos.push(prevP.x * 1.012, prevP.y * 1.012, prevP.z * 1.012, q.x * 1.012, q.y * 1.012, q.z * 1.012);
            prevP = q;
          }
        }
      });
      const geo = new T.BufferGeometry();
      geo.setAttribute('position', new T.Float32BufferAttribute(pos, 3));
      const lines = new T.LineSegments(geo, lineMat(light ? 0x4C63B8 : 0xB23440, 0.2));
      const nodes = pts.map((p, i) => {
        const s = sprite(light ? 0x4C63B8 : 0xF2B8BE, 0.03);
        s.position.copy(p).multiplyScalar(1.012); s.userData.ph = i * 1.7;
        return s;
      });
      const grp = new T.Group(); grp.add(lines, ...nodes);
      return { grp, lines, nodes };
    }
    let web = null;

    function ringsGeometry(indices, r) {
      const pos = [];
      for (const i of indices) {
        const ring = W.rings[i];
        for (let k = 0; k < ring.length; k += 2) {
          const k2 = (k + 2) % ring.length;
          const a = dirOf(ring[k], ring[k + 1]).multiplyScalar(r), b = dirOf(ring[k2], ring[k2 + 1]).multiplyScalar(r);
          pos.push(a.x, a.y, a.z, b.x, b.y, b.z);
        }
      }
      const g = new T.BufferGeometry();
      g.setAttribute('position', new T.Float32BufferAttribute(pos, 3));
      return g;
    }
    const lineMat = (color, opacity) => new T.LineBasicMaterial({ color, transparent: true, opacity, depthWrite: false,
      blending: light ? T.NormalBlending : T.AdditiveBlending });
    function outline(code, color) {
      const l = new T.LineSegments(ringsGeometry(W.by[code], 1.0022), lineMat(color, 1));
      scene.add(l);
      return l;
    }
    function drop(obj) {
      if (!obj) return;
      scene.remove(obj);
      obj.traverse((o) => { if (o.geometry) o.geometry.dispose(); if (o.material) o.material.dispose(); });
    }

    function build() {
      const tone = light ? INK : NEON;
      scene = new T.Scene();
      camera = new T.PerspectiveCamera(FOV, 1, 0.01, 100);
      GLOW = glowTexture();
      const blank = new T.DataTexture(new Uint8Array([0, 0, 0, 255]), 1, 1); blank.needsUpdate = true;
      globeMat = new T.ShaderMaterial({
        vertexShader: GLOBE_VERT, fragmentShader: GLOBE_FRAG, defines: landDefines(),
        uniforms: {
          uMaskO: { value: blank }, uMaskD: { value: blank }, uFlagO: { value: blank }, uFlagD: { value: blank },
          uBoxO: { value: new T.Vector4(0, 0, 1, 1) }, uBoxD: { value: new T.Vector4(0, 0, 1, 1) },
          uFillO: { value: 0 }, uFillD: { value: 0 }, uSpanO: { value: 0.2 }, uSpanD: { value: 0.2 },
          uLandO: { value: new T.Vector3(0, 0, 1) }, uLandD: { value: new T.Vector3(0, 0, 1) },
          uRed: { value: 0 }, uLight: { value: light ? 1 : 0 }, uHasO: { value: 0 }, uHasD: { value: 0 },
          uWorld: { value: worldMask() }, uCell: { value: 0.8 }, uCellMix: { value: 0 }, uFlagW: { value: 2048 },
        },
      });
      scene.add(new T.Mesh(new T.SphereGeometry(1, 160, 120), globeMat));
      /* the borders only part the land now; the dots draw it */
      borders = new T.LineSegments(ringsGeometry(W.rings.map((_, i) => i), 1.0012),
                                   lineMat(light ? 0x6B7486 : NEON.peri, light ? 0.2 : 0.22));
      scene.add(borders);
      web = network(tone);
      scene.add(web.grp);

      orbits = [];
      for (const d of [{ r: 1.32, tilt: [1.1, 0, 0.3], color: tone.peri, speed: 0.25, n: 3 },
                       { r: 1.5, tilt: [-0.6, 0.8, -0.2], color: tone.aqua, speed: -0.18, n: 2 },
                       { r: 1.68, tilt: [0.25, -0.5, 0.9], color: tone.orchid, speed: 0.14, n: 2 }]) {
        const ring = new T.Group();
        ring.rotation.set(...d.tilt);
        const path = new T.CatmullRomCurve3(new T.EllipseCurve(0, 0, d.r, d.r).getPoints(180)
          .map((p) => new T.Vector3(p.x, 0, p.y)), true);
        const line = new T.Mesh(new T.TubeGeometry(path, 240, 0.0035, 3, true), additive(d.color, 0.6));
        const glow = new T.Mesh(new T.TubeGeometry(path, 240, 0.016, 3, true), additive(d.color, 0.08));
        ring.add(line, glow);
        const riders = Array.from({ length: d.n }, (_, i) => {
          const s = sprite(d.color, 0.09); ring.add(s);
          return { s, u: i / d.n, ph: Math.random() * 6 };
        });
        scene.add(ring);
        orbits.push({ ring, path, line, glow, riders, d });
      }
      ripples = Array.from({ length: 3 }, () => {
        const m = new T.Mesh(new T.RingGeometry(0.96, 1, 64), additive(tone.pulse, 0));
        m.scale.setScalar(0.001); scene.add(m);
        return m;
      });
      view.dir = dirOf(40, 25);
    }

    /* ---------------------------------------------------------------- camera */

    /* The country sits in the middle band, between the status block and the
       panel below; this is how far off the window's centre that is. */
    function bandShift() {
      const mid = document.querySelector('.hero .mid');
      const h = window.innerHeight;
      if (!mid || !h) return 0;
      const r = mid.getBoundingClientRect();
      return ((r.top + r.bottom) / 2 - h / 2) / h;
    }

    const UP = () => new T.Vector3(0, 1, 0);
    function placeCamera() {
      const side = new T.Vector3().crossVectors(UP(), view.dir).normalize();
      const north = new T.Vector3().crossVectors(view.dir, side).normalize();
      const pos = view.dir.clone().addScaledVector(side, lean.x * 0.04)
        .addScaledVector(north, -0.035 - lean.y * 0.03).normalize().multiplyScalar(view.dist);
      camera.position.copy(pos);
      camera.up.copy(north);
      const reach = (view.dist - 1) * 2 * Math.tan(FOV / 2 * D2R);
      camera.lookAt(view.dir.clone().addScaledVector(north, view.shift * reach));
    }

    /* Drawn into Mirage's orb (a canvas inside the home's middle, class
       atlas--orb) it is sized to its own box and centred; drawn behind the
       whole window it fills it and frames the country in the middle band. */
    const inOrb = () => canvas.classList.contains('atlas--orb');
    function layout() {
      const box = inOrb() ? canvas.getBoundingClientRect() : null;
      const w = box ? Math.round(box.width) : window.innerWidth, h = box ? Math.round(box.height) : window.innerHeight;
      if (!w || !h) return false;
      renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, MAX_DPR));
      renderer.setSize(w, h, false);
      camera.aspect = w / h;
      camera.updateProjectionMatrix();
      view.shift = inOrb() ? 0 : bandShift();
      return true;
    }

    /* ------------------------------------------------------------------ loop */

    function visible() {
      return !document.hidden && document.body.dataset.covered !== '1';
    }

    function tick(time, deltaMs) {
      if (!visible()) { stop(); return; }
      const now = performance.now();
      if (!moving && now - drawnAt < IDLE_MS) return;
      const calm = isCalm();
      const d = calm ? 0 : Math.min(deltaMs, 50) / 1000;
      S.t += d;
      const far = T.MathUtils.smoothstep(view.dist, 2.1, 3.6);
      for (const o of orbits) {
        o.ring.rotation.y += d * o.d.speed * 0.2 * S.orbitSpeed;
        o.line.material.opacity = 0.6 * far;
        o.glow.material.opacity = 0.08 * far;
        for (const r of o.riders) {
          r.u = (r.u + d * o.d.speed * 0.2 * S.orbitSpeed + 1) % 1;
          r.s.position.copy(o.path.getPointAt(r.u));
          const b = 0.5 + 0.5 * Math.sin(S.t * (2 + S.blink * 9) + r.ph);
          r.s.material.opacity = far * (0.2 + 0.8 * b * b);
        }
      }
      if (arc && S.traffic > 0) {
        arc.traffic.forEach((s, i) => {
          const u = (S.t * S.trafficSpeed + i / arc.traffic.length) % 1;
          s.position.copy(arc.curve.getPointAt(u));
          s.material.opacity = S.traffic * Math.sin(u * Math.PI);
        });
      }
      // The dots' pitch: ~4.4 CSS px on screen at the view's middle, as a
      // power-of-two step of 0.08 degrees and how far towards the next.
      if (landStyle === 'dots') {
        const u = globeMat.uniforms;
        const ratio = renderer.getPixelRatio(), hPx = Math.max(1, canvas.height || window.innerHeight * ratio);
        const seenDeg = 2 * Math.max(0.02, view.dist - 1) * Math.tan(FOV / 2 * D2R) / D2R;
        const cell = Math.min(3.2, Math.max(0.08, 4.4 * ratio * seenDeg / hPx));
        const level = Math.log2(cell / 0.08), whole = Math.floor(level), f = level - whole;
        u.uCell.value = 0.08 * Math.pow(2, whole);
        u.uCellMix.value = f * f * (3 - 2 * f);
      }
      // The network fades as the camera comes down onto a country; its nodes glint.
      if (web) {
        const wa = T.MathUtils.smoothstep(view.dist, 1.35, 2.6);
        web.grp.visible = wa > 0.01;
        web.lines.material.opacity = 0.2 * wa;
        const size = 0.0065 * view.dist;
        for (const s of web.nodes) {
          s.scale.setScalar(size * 4);
          s.material.opacity = 0.75 * wa * (0.55 + 0.45 * Math.sin(s.userData.ph + S.t * 1.1));
        }
      }
      placeCamera();
      renderer.render(scene, camera);
      drawnAt = now;
      if (calm && !moving) stop();
    }

    function kick() {
      if (!started || ticking || !visible() || !window.gsap) return;
      ticking = true;
      gsap.ticker.add(tick);
    }
    function stop() {
      if (!ticking) return;
      ticking = false;
      gsap.ticker.remove(tick);
    }
    /* a timeline that moves the camera or the arc runs at full rate */
    function run(tl) {
      moving += 1;
      tl.eventCallback('onComplete', () => { moving = Math.max(0, moving - 1); });
      tl.eventCallback('onInterrupt', () => { moving = Math.max(0, moving - 1); });
      kick();
      return tl;
    }
    const dur = (s) => (isCalm() ? 0 : s);

    /* ------------------------------------------------------------- the fills */

    /* How far the flag must spread from where it lands, in radians: to the
       farthest point of the country's land inside its box (the only part the
       flag paints), and a hair more so the edge closes cleanly. */
    function span(code, at) {
      const b = W.box[code];
      let far = 0;
      for (const i of W.by[code]) {
        const r = W.rings[i];
        for (let k = 0; k < r.length; k += 2) {
          const lon = r[k], lat = r[k + 1];
          if (lon < b[0] || lon > b[2] || lat < b[1] || lat > b[3]) continue;
          far = Math.max(far, at.angleTo(dirOf(lon, lat)));
        }
      }
      return far + 0.01;
    }
    function fillAs(which, code, land) {
      const u = globeMat.uniforms, k = which === 'O' ? 'O' : 'D';
      if (!has(code)) { u['uHas' + k].value = 0; return; }
      const tex = countryTextures(code);
      u['uMask' + k].value = tex.mask;
      u['uFlag' + k].value = tex.flag;
      u['uBox' + k].value.set(...W.box[code]);
      const at = land || middle(code);
      u['uSpan' + k].value = span(code, at);
      u['uLand' + k].value.copy(at);
      u['uHas' + k].value = 1;
    }

    /* --------------------------------------------------------------- journeys */

    function dropArc() {
      if (!arc) return;
      [arc.mesh, arc.comet, ...arc.traffic].forEach(drop);
      arc = null;
    }

    /* the slow turn of the planet from one point above it to another */
    function turnTo(tl, to, at, length) {
      const from = view.dir.clone(), p = { t: 0 };
      tl.to(p, { t: 1, duration: dur(length), ease: 'power2.inOut',
                 onUpdate() { view.dir.copy(from).lerp(to, p.t).normalize(); } }, at);
    }

    /* Settle above home - or, before the address is known, above the pick,
       or over the Middle East from far enough out to be a planet. */
    function goHome(first) {
      if (journey) journey.kill();
      if (busyTl) { busyTl.kill(); busyTl = null; }
      const u = globeMat.uniforms;
      const code = has(homeCode) ? homeCode : (has(pickedCode) ? pickedCode : null);
      originCode = code;
      destCode = null;
      if (code) fillAs('O', code);
      if (outlineO) drop(outlineO);
      outlineO = code ? outline(code, light ? INK.peri : NEON.peri) : null;
      if (outlineD) { drop(outlineD); outlineD = null; }
      const tl = gsap.timeline({ defaults: { ease: 'power2.inOut' } });
      const target = code ? middle(code) : dirOf(45, 28);
      const height = code ? heightFor(code) : 4.6;
      if (first) {
        view.dir.copy(target);
        view.dist = 6;
        tl.to(view, { dist: height, duration: dur(2.2), ease: 'expo.inOut' }, 0.2);
        u.uFillO.value = 1;
      } else {
        tl.to(view, { dist: Math.max(view.dist, 3.4), duration: dur(0.8) }, 0);
        turnTo(tl, target, 0.4, 1.4);
        tl.to(view, { dist: height, duration: dur(1.1), ease: 'power3.inOut' }, 1.4)
          .to(u.uFillD, { value: 0, duration: dur(0.5) }, 0)
          .to(u.uFillO, { value: 1, duration: dur(1) }, 1.4);
      }
      tl.to(u.uRed, { value: 0, duration: dur(0.5) }, 0)
        .to(S, { orbitSpeed: 1, blink: 0.5, traffic: 0, duration: dur(1) }, 0)
        .add(dropArc, 0.5);
      ripples.forEach((m) => { m.material.opacity = 0; });
      journey = run(tl);
    }

    function searching() {
      if (busyTl) busyTl.kill();
      const at = has(originCode) ? middle(originCode) : view.dir.clone();
      busyTl = gsap.timeline();
      ripples.forEach((m, i) => {
        m.position.copy(at).multiplyScalar(1.003);
        m.lookAt(0, 0, 0);
        busyTl.fromTo(m.scale, { x: 0.001, y: 0.001 }, { x: 0.16, y: 0.16, duration: 1.6, ease: 'power2.out', repeat: -1 }, i * 0.53)
          .fromTo(m.material, { opacity: 0.9 }, { opacity: 0, duration: 1.6, ease: 'power1.in', repeat: -1 }, i * 0.53);
      });
      busyTl.to(view, { dist: view.dist + 0.35, duration: dur(2.2), ease: 'sine.inOut' }, 0)
        .to(S, { orbitSpeed: 4, blink: 1, duration: dur(0.8) }, 0);
      moving += 1;
      busyTl.eventCallback('onInterrupt', () => { moving = Math.max(0, moving - 1); });
      kick();
    }

    /* Out, over, and down onto where the traffic leaves - or, failing, a
       signal that stops halfway and comes home. */
    function arrive(code, fail) {
      if (journey) journey.kill();
      if (busyTl) { busyTl.kill(); busyTl = null; }
      ripples.forEach((m) => gsap.to(m.material, { opacity: 0, duration: dur(0.3) }));
      if (!has(code)) { if (!fail) return; goHome(false); return; }
      const u = globeMat.uniforms;
      const from = has(originCode) ? middle(originCode) : view.dir.clone();
      const to = middle(code);
      destCode = code;
      fillAs('D', code, to);
      u.uFillD.value = 0;
      if (outlineD) drop(outlineD);
      outlineD = outline(code, light ? INK.pulse : NEON.pulse);
      outlineD.material.opacity = 0;
      dropArc();
      /* No line is drawn between the two countries any more (2026-11-13):
         the camera alone tells the journey - out until the planet is in
         view, round, and down onto the exit. A failure turns part of the way,
         reddens the planet for a moment and goes home. */
      const same = from.angleTo(to) < 0.03;
      if (same && fail) { goHome(false); return; }
      const tl = gsap.timeline({ defaults: { ease: 'power2.inOut' } });
      tl.to(view, { dist: 5, duration: dur(1.3), ease: 'power3.inOut' }, 0);
      const start = view.dir.clone(), p = { t: 0 };
      tl.to(p, { t: fail ? 0.45 : 1, duration: dur(fail ? 1.4 : 2.1),
                 onUpdate() { view.dir.copy(start).lerp(to, p.t).normalize(); } }, 0.55);
      if (fail) {
        tl.to(u.uRed, { value: 0.7, duration: dur(0.3), yoyo: true, repeat: 1 }, 1.6)
          .add(() => goHome(false), '+=1.0');
        journey = run(tl);
        return;
      }
      tl.to(view, { dist: heightFor(code), duration: dur(1.5), ease: 'power3.inOut' }, 2.3)
        .to(outlineD.material, { opacity: 1, duration: dur(0.4) }, 2.4)
        .to(u.uFillD, { value: 1, duration: dur(1.3), ease: 'power2.out' }, 2.6)
        .to(u.uFillO, { value: 0, duration: dur(0.9) }, 2.6)
        .to(S, { orbitSpeed: 1, blink: 0.3, traffic: 1, duration: dur(1) }, 2.6);
      if (outlineO) tl.to(outlineO.material, { opacity: 0.35, duration: dur(0.9) }, 2.6);
      journey = run(tl);
    }

    /* the exit moved while connected (the check came back with a different
       country): a short hop, no planet */
    function hop(code) {
      if (!has(code) || code === destCode) return;
      const u = globeMat.uniforms;
      destCode = code;
      fillAs('D', code);
      if (outlineD) drop(outlineD);
      outlineD = outline(code, light ? INK.pulse : NEON.pulse);
      const tl = gsap.timeline({ defaults: { ease: 'power2.inOut' } });
      u.uFillD.value = 0;
      turnTo(tl, middle(code), 0, 1.2);
      tl.to(view, { dist: heightFor(code), duration: dur(1.2) }, 0)
        .to(u.uFillD, { value: 1, duration: dur(1), ease: 'power2.out' }, 0.8);
      journey = run(tl);
    }

    /* ------------------------------------------------------------------ state */

    function follow() {
      const st = document.documentElement.dataset.conn || 'off';
      if (st === state) {
        if (st === 'on' && liveCode && liveCode !== destCode) hop(liveCode);
        return;
      }
      const was = state;
      state = st;
      if (st === 'busy') searching();
      else if (st === 'on') arrive(liveCode || pickedCode, false);
      else if (st === 'fail') { if (was === 'busy') arrive(pickedCode, true); else goHome(false); }
      else goHome(false);
      kick();
    }

    /* ------------------------------------------------------------------- API */

    /* Called by app.js whenever the state or the country can have changed:
       `live` is where the traffic leaves now, `picked` the choice. */
    function sync(live, picked) {
      liveCode = live || null;
      pickedCode = picked || null;
      if (!started) return;
      if (state === 'off' && !has(homeCode) && has(pickedCode) && originCode !== pickedCode) goHome(false);
      follow();
    }

    /* The country the user's own address is in, as Cloudflare saw it before
       anything of ours was in the way. */
    function home(code) {
      code = (code || '').toLowerCase();
      if (!has(code) || code === homeCode) return;
      const first = homeCode === null && originCode === null;
      homeCode = code;
      if (!started) return;
      if (state === 'off') { goHome(first); return; }
      if (state === 'on' && destCode && code !== destCode) {
        /* the address came back after the route was up: the journey started
           from a guess, so draw it again from where you really are */
        originCode = code;
        fillAs('O', code);
        globeMat.uniforms.uFillO.value = 0;
        if (outlineO) drop(outlineO);
        outlineO = outline(code, light ? INK.peri : NEON.peri);
        outlineO.material.opacity = 0.35;
        dropArc();
        kick();
      }
    }

    function hit() { return true; }

    /* Switch how the land is drawn ('flag' or 'dots'); answers the one in use. */
    function style(name) {
      if (name === 'flag' || name === 'dots') landStyle = name;
      if (globeMat) {
        globeMat.defines = landDefines();
        globeMat.needsUpdate = true;
        drawnAt = 0; kick();
      }
      return landStyle;
    }

    /* For the review desk: the time of one frame, as the median of n runs of
       ten frames drawn back to back and waited out on the card (a one pixel
       read makes the GPU finish). `ratio` draws at that many pixels per CSS
       pixel for the test. */
    function bench(n, ratio, hide) {
      if (!renderer) return null;
      const gl = renderer.getContext(), one = new Uint8Array(4), times = [];
      const parts = { globe: scene.children.find((o) => o.material === globeMat), borders, web: web && web.grp,
                      orbits: orbits.map((o) => o.ring) };
      const hidden = [].concat(...(hide || []).map((k) => [].concat(parts[k] || [])));
      hidden.forEach((o) => { o.userData.wasVisible = o.visible; o.visible = false; });
      if (ratio) { renderer.setPixelRatio(ratio); renderer.setSize(window.innerWidth, window.innerHeight, false); }
      placeCamera();
      renderer.render(scene, camera); gl.readPixels(0, 0, 1, 1, gl.RGBA, gl.UNSIGNED_BYTE, one);
      for (let i = 0; i < (n || 12); i += 1) {
        const t0 = performance.now();
        for (let k = 0; k < 10; k += 1) renderer.render(scene, camera);
        gl.readPixels(0, 0, 1, 1, gl.RGBA, gl.UNSIGNED_BYTE, one);
        times.push((performance.now() - t0) / 10);
      }
      const px = canvas.width + 'x' + canvas.height;
      hidden.forEach((o) => { o.visible = o.userData.wasVisible; });
      if (ratio) layout();
      times.sort((a, b) => a - b);
      return { style: landStyle, median: +times[times.length >> 1].toFixed(2), best: +times[0].toFixed(2),
               px, dist: +view.dist.toFixed(2) };
    }

    function themeNow() { return document.documentElement.dataset.theme === 'white'; }

    function init(cv, opts) {
      T = window.THREE; W = window.WORLD;
      if (!T || !W) return false;
      canvas = cv;
      isCalm = (opts && opts.calm) || (() => false);
      try {
        renderer = new T.WebGLRenderer({ canvas, antialias: true, alpha: true, powerPreference: 'low-power' });
      } catch (e) { renderer = null; }
      if (!renderer) { canvas.hidden = true; return false; }
      renderer.setClearColor(0x000000, 0);
      light = themeNow();
      build();
      started = true;
      layout();
      goHome(true);
      canvas.addEventListener('webglcontextlost', (e) => { e.preventDefault(); stop(); });
      let rz = 0;
      const relayout = () => { cancelAnimationFrame(rz); rz = requestAnimationFrame(() => { layout(); kick(); }); };
      window.addEventListener('resize', relayout);
      if (window.ResizeObserver) {
        const ro = new ResizeObserver(relayout);
        ro.observe(document.getElementById('hero') || document.body);
        if (inOrb()) ro.observe(canvas);
      }
      document.addEventListener('visibilitychange', kick);
      new MutationObserver(kick).observe(document.body, { attributes: true, attributeFilter: ['data-covered'] });
      new MutationObserver(follow).observe(document.documentElement, { attributes: true, attributeFilter: ['data-conn'] });
      /* the theme decides blending, so a change rebuilds the scene where it stands */
      new MutationObserver(() => {
        if (themeNow() === light) return;
        light = themeNow();
        const keep = { dir: view.dir.clone(), dist: view.dist };
        scene.traverse((o) => { if (o.geometry) o.geometry.dispose(); if (o.material) o.material.dispose(); });
        outlineO = outlineD = arc = null;
        build();
        view.dir.copy(keep.dir); view.dist = keep.dist;
        const was = state; state = '__'; originCode = null;
        if (was === 'on' && liveCode) {
          goHome(false); state = 'on';
          arrive(liveCode, false);
        } else { state = was; goHome(false); }
      }).observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme'] });
      const leanX = gsap.quickTo(lean, 'x', { duration: 1.2, ease: 'power3.out' });
      const leanY = gsap.quickTo(lean, 'y', { duration: 1.2, ease: 'power3.out' });
      window.addEventListener('pointermove', (e) => {
        if (isCalm()) return;
        leanX((e.clientX / window.innerWidth - 0.5) * 2);
        leanY((e.clientY / window.innerHeight - 0.5) * 2);
        kick();
      });
      gsap.matchMedia().add('(prefers-reduced-motion: reduce)', () => { kick(); return () => kick(); });
      kick();
      return true;
    }

    return { init, sync, home, hit, style, bench };
  })();
})();

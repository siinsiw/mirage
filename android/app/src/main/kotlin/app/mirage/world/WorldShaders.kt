package app.mirage.world

/*
 * The world's shaders (GLSL ES 3.00). The planet's is the desktop's GLOBE_FRAG
 * line for line; what three.js gave the desktop for free (lines, sprites, rings)
 * is written out here as four small programs.
 */

/**
 * The planet: a dark sphere under a fixed light, a faint 15 degree graticule,
 * one country filled with its flag (the one the journey has left, fading, and
 * the one it arrives at, spreading), and a thin pastel limb.
 *
 * Each fill is a mask (white where the country is, over its own box) and the
 * flag stretched over the same box; both are read at the fragment's own
 * longitude and latitude, so the flag never wanders off the land. The fill
 * spreads outward from where it lands: a fragment is lit while its angle from
 * that point is under fill * span. The bitmaps are uploaded north-row-first, so
 * v is flipped on the way in.
 */
internal const val PLANET_VERT = """#version 300 es
layout(location = 0) in vec3 a_pos;
uniform mat4 uV;
uniform mat4 uP;
out vec3 vN;
out vec3 vV;
out vec3 vO;
void main() {
  vO = normalize(a_pos);
  vec4 mv = uV * vec4(a_pos, 1.0);
  vV = normalize(-mv.xyz);
  vN = normalize(mat3(uV) * a_pos);
  gl_Position = uP * mv;
}
"""

/*
 * The planet as dots (the Midnight redesign). The land is a grid of round
 * dots - white, softly lit - over a deep navy sea; there is no flag picture
 * laid over a country any more. Instead the dots of the country in focus
 * change colour: each one takes the flag's colour at its own place in the
 * country's box, so the dots themselves draw the flag, and they turn from
 * white to that colour as the fill spreads out from where the signal landed.
 *
 * The grid is in longitude and latitude, its columns spaced by 1/cos(lat) so
 * the dots stay round and evenly apart all the way up to the poles. Its pitch
 * (uCell, degrees) is set from how close the camera is, so a dot is always a
 * few pixels across: coarse when the whole planet is in view, fine enough over
 * a small country for its dots to make out its flag. Between two pitches the
 * two grids are cross-faded (uCellMix), so the dots refine as the camera
 * comes down rather than jumping.
 *
 * What is land is read at each dot's centre - so a dot is either all there or
 * not - from a whole-world mask (uWorld, equirectangular) or, for the two
 * countries that may be filled, from their own sharper masks. The flag is
 * read at the dot's centre too, from a mip level about one dot wide, so a dot
 * is the flag's average colour over its own patch rather than one texel of it.
 */
/*
 * FLAG_LAND (the default since 2026-11-13, as on Windows - preview/DECISIONS.md)
 * draws the land the lighter way instead: one soft dark ground over a black
 * sea with a faint graticule, and the country in focus is its flag, the
 * picture itself laid into its outline, spreading from where the signal
 * landed behind a thin line of light. Each fragment reads the land once and a
 * country's mask and flag only inside that country's box, with mip levels
 * from the screen-space rate of the coordinates (taken across the
 * antimeridian without a seam). On the desktop that halved the planet's cost
 * against the dots. Take the #define out to have the dots back.
 */
internal const val PLANET_FRAG = """#version 300 es
#define FLAG_LAND
precision highp float;
precision highp sampler2D;
uniform sampler2D uMaskO, uMaskD, uFlagO, uFlagD, uWorld;
uniform vec4 uBoxO, uBoxD;
uniform float uFillO, uFillD, uSpanO, uSpanD, uRed, uLight, uHasO, uHasD;
uniform float uCell, uCellMix, uFlagW;
uniform vec3 uLandO, uLandD;
in vec3 vN;
in vec3 vV;
in vec3 vO;
out vec4 o;

// A country's mask at a point (lon, lat): 0 outside its box. uv comes back for the flag.
float inside(sampler2D m, vec4 box, vec2 ll, out vec2 uv) {
  uv = (ll - box.xy) / (box.zw - box.xy);
  if (uv.x < 0. || uv.x > 1. || uv.y < 0. || uv.y > 1.) return 0.;
  return textureLod(m, vec2(uv.x, 1. - uv.y), 0.).r;
}

// The flag's colour over the patch one dot covers: a mip level about [cell] of the box wide.
vec3 flagAt(sampler2D t, vec2 uv, vec4 box, float cell) {
  float texels = cell / max(box.z - box.x, .01) * uFlagW;
  return textureLod(t, vec2(clamp(uv.x, 0., 1.), 1. - clamp(uv.y, 0., 1.)), max(log2(texels), 0.)).rgb;
}

vec3 dirOf(vec2 ll) {
  vec2 r = radians(ll);
  return vec3(cos(r.y) * sin(r.x), sin(r.y), cos(r.y) * cos(r.x));
}

#ifdef FLAG_LAND
// Where in a country's box this fragment is, how fast that changes across the screen,
// and how much of the country is under it. Outside the box nothing is read; the
// gradients are explicit, so reading only on some fragments is allowed. The bitmaps
// are north-row-first, so v runs the other way (and so do its rates).
float boxed(sampler2D m, vec4 box, float has, vec2 ll, vec2 gx, vec2 gy, out vec2 uv, out vec2 dx, out vec2 dy) {
  vec2 size = max(box.zw - box.xy, vec2(.01));
  uv = (ll - box.xy) / size;
  dx = vec2(gx.x / size.x, -gy.x / size.y);
  dy = vec2(gx.y / size.x, -gy.y / size.y);
  if (has < .5 || uv.x < 0. || uv.x > 1. || uv.y < 0. || uv.y > 1.) return 0.;
  return textureGrad(m, vec2(uv.x, 1. - uv.y), dx, dy).r;
}

vec3 flatLand(vec2 ll, float shade, vec3 sea) {
  // The rate of longitude, from whichever of two seams is farther away.
  float lon2 = mod(ll.x + 360., 360.);
  vec2 a = vec2(dFdx(ll.x), dFdy(ll.x)), b = vec2(dFdx(lon2), dFdy(lon2));
  vec2 gx = dot(a, a) < dot(b, b) ? a : b;
  vec2 gy = vec2(dFdx(ll.y), dFdy(ll.y));
  vec2 w = vec2(abs(gx.x) + abs(gx.y), abs(gy.x) + abs(gy.y));
  vec2 g = abs(fract(ll / 15. + .5) - .5) * 15.;
  vec2 ln = 1. - smoothstep(vec2(0.), w * 1.4, g);
  float grid = max(ln.x * (1. - smoothstep(70., 80., abs(ll.y))), ln.y) * .045 * (1. - uLight);
  float l = textureGrad(uWorld, vec2((ll.x + 180.) / 360., (90. - ll.y) / 180.),
                        vec2(gx.x / 360., -gy.x / 180.), vec2(gx.y / 360., -gy.y / 180.)).r;
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
  // A thin line of light where the flag is still spreading.
  float eO = mO * (1. - smoothstep(0., .012, abs(aO - rO))) * step(.001, uFillO) * step(uFillO, .999);
  float eD = mD * (1. - smoothstep(0., .012, abs(aD - rD))) * step(.001, uFillD) * step(uFillD, .999);
  vec3 flagO = vec3(0.), flagD = vec3(0.);
  if (fO + eO > .001) flagO = textureGrad(uFlagO, vec2(clamp(uvO.x, 0., 1.), 1. - clamp(uvO.y, 0., 1.)), dxO, dyO).rgb;
  if (fD + eD > .001) flagD = textureGrad(uFlagD, vec2(clamp(uvD.x, 0., 1.), 1. - clamp(uvD.y, 0., 1.)), dxD, dyD).rgb;
  float f = max(fO, fD);
  vec3 tint = fD >= fO ? flagD : flagO;
  // The flag lies on the planet: lit from the same side, with a soft sheen.
  vec3 L = normalize(vec3(-.4, .6, .7));
  float sheen = pow(max(dot(reflect(-L, vN), vV), 0.), 18.) * .14;
  c = mix(c, tint * (.74 + .26 * shade) + sheen, f);
  c += (flagO * .6 + .3) * eO * .55 + (flagD * .6 + .3) * eD * .55;
  return c;
}
#endif

// One grid of dots at pitch [cell]: rgb is the dot's colour, a its coverage of this fragment.
vec4 dots(vec2 ll, float cell, float px, float shade) {
  float row = floor((ll.y + 90.) / cell);
  float latC = -90. + (row + .5) * cell;
  float across = max(1., floor(360. * cos(radians(latC)) / cell));
  float step_ = 360. / across;
  float col = floor((ll.x + 180.) / step_);
  float lonC = -180. + (col + .5) * step_;
  vec2 c = vec2(lonC, latC);
  // Distance from the dot's centre in degrees of arc, then in dot radii.
  vec2 d = vec2((ll.x - lonC) * cos(radians(ll.y)), ll.y - latC);
  float r = length(d) / cell;
  float aa = max(px / cell, .02);

  vec2 uvO, uvD;
  float mO = inside(uMaskO, uBoxO, c, uvO) * uHasO;
  float mD = inside(uMaskD, uBoxD, c, uvD) * uHasD;
  float land = max(step(.5, textureLod(uWorld, vec2((lonC + 180.) / 360., (90. - latC) / 180.), 0.).r),
                   max(step(.5, mO), step(.5, mD)));
  // The fill, by the dot's own centre, spreading out from where it landed.
  vec3 p = dirOf(c);
  float fO = step(.5, mO) * (1. - smoothstep(uFillO * uSpanO - .02, uFillO * uSpanO, acos(clamp(dot(p, uLandO), -1., 1.))));
  float fD = step(.5, mD) * (1. - smoothstep(uFillD * uSpanD - .02, uFillD * uSpanD, acos(clamp(dot(p, uLandD), -1., 1.))));
  float f = max(fO, fD);

  // Land dots are white, lit from the upper left; a coloured dot is a touch bigger and lit evenly.
  vec3 white = mix(vec3(.80, .86, .97), vec3(.30, .34, .42), uLight) * (.55 + .45 * shade);
  vec3 tint = fD >= fO ? flagAt(uFlagD, uvD, uBoxD, cell) : flagAt(uFlagO, uvO, uBoxO, cell);
  vec3 col3 = mix(white, tint * (.92 + .16 * shade), f);
  // The front of the spread: a dot it is passing over swells for a moment.
  float front = step(.5, mD) * uHasD * (1. - smoothstep(0., .05, abs(acos(clamp(dot(p, uLandD), -1., 1.)) - uFillD * uSpanD)))
                * step(.001, uFillD) * step(uFillD, .999);
  float radius = mix(.30, .37, f) + .08 * front;
  float a = (1. - smoothstep(radius - aa, radius + aa, r)) * land;
  // The sea keeps a faint dot of its own, far smaller: texture, not information.
  float sea = (1. - land) * (1. - smoothstep(.07 - aa, .07 + aa, r)) * mix(.10, .16, uLight);
  return vec4(land > .5 ? col3 : white, max(a * mix(.78, 1., f), sea));
}

void main() {
  float lat = degrees(asin(clamp(vO.y, -1., 1.))), lon = degrees(atan(vO.x, vO.z));
  vec2 ll = vec2(lon, lat);
  float shade = .55 + .45 * max(dot(vN, normalize(vec3(-.4, .6, .7))), 0.);
  // The sea: near-black with a dark red in it (Mirage), lit from the upper left.
  vec3 c = mix(vec3(.048, .026, .028), vec3(.90, .92, .95), uLight) * mix(shade, 1., uLight * .6);
#ifdef FLAG_LAND
  c = flatLand(ll, shade, c);
#else
  // How many degrees of latitude one pixel covers here, for the dots' soft edges.
  float px = max(fwidth(ll.y), 1e-4);
  vec4 a = dots(ll, uCell, px, shade);
  vec4 b = dots(ll, uCell * 2., px, shade);
  vec4 dt = mix(a, b, uCellMix);
  c = mix(c, dt.rgb, dt.a);
#endif
  float l = dot(c, vec3(.3, .59, .11)); c = mix(c, vec3(l * 1.2, l * .3, l * .35), uRed);
  // The atmosphere: a dark red rim, brightest at the limb (the desktop's).
  float rim = pow(1. - max(dot(vN, vV), 0.), 3.2);
  c += mix(vec3(.62, .16, .19) * .55, vec3(-.25, -.2, -.05), uLight) * rim;
  o = vec4(c, 1.);
}
"""

/**
 * Lines of a constant width on screen, from segments: one instance is one
 * segment (two points of the unit sphere, or of a circle), the quad's four
 * corners are the shared vertices. Each is stretched sideways in pixels after
 * projection, which is what GL's own lines cannot do (one pixel, and phones
 * have three to the dp). A segment with an end behind the camera is dropped.
 */
internal const val LINE_VERT = """#version 300 es
layout(location = 0) in vec2 a_corner;
layout(location = 1) in vec3 a_a;
layout(location = 2) in vec3 a_b;
uniform mat4 uVP;
uniform mat4 uModel;
uniform float uR;
uniform vec2 uView;
uniform float uWidth;
out float vSide;
void main() {
  vec4 ca = uVP * (uModel * vec4(a_a * uR, 1.0));
  vec4 cb = uVP * (uModel * vec4(a_b * uR, 1.0));
  if (ca.w < 0.01 || cb.w < 0.01) { gl_Position = vec4(2.0, 2.0, 2.0, 1.0); vSide = 0.0; return; }
  vec2 half_ = uView * 0.5;
  vec2 d = (cb.xy / cb.w - ca.xy / ca.w) * half_;
  float len = length(d);
  vec2 dir = len > 1e-4 ? d / len : vec2(1.0, 0.0);
  vec2 n = vec2(-dir.y, dir.x);
  vec4 c = mix(ca, cb, a_corner.x);
  c.xy += n * (a_corner.y * uWidth * 0.5) / half_ * c.w;
  gl_Position = c;
  vSide = a_corner.y;
}
"""

internal const val LINE_FRAG = """#version 300 es
precision mediump float;
in float vSide;
uniform vec4 uColor;
out vec4 o;
void main() {
  float fw = fwidth(vSide);
  float e = 1.0 - smoothstep(1.0 - fw * 1.5, 1.0, abs(vSide));
  o = vec4(uColor.rgb, uColor.a * e);
}
"""

/** A soft round dot that always faces the camera and keeps its size in the world: the riders, the comet, the traffic. */
internal const val SPRITE_VERT = """#version 300 es
layout(location = 0) in vec2 a_corner;
uniform mat4 uV;
uniform mat4 uP;
uniform vec3 uC;
uniform float uSize;
out vec2 vC;
void main() {
  vec4 v = uV * vec4(uC, 1.0);
  v.xy += a_corner * uSize * 0.5;
  gl_Position = uP * v;
  vC = a_corner;
}
"""

/** The desktop's glow texture, as a formula: 1 at the middle, .55 a quarter out, 0 at the edge. */
internal const val SPRITE_FRAG = """#version 300 es
precision mediump float;
in vec2 vC;
uniform vec4 uColor;
out vec4 o;
void main() {
  float r = length(vC);
  if (r >= 1.0) discard;
  float a = r < 0.25 ? mix(1.0, 0.55, r / 0.25) : mix(0.55, 0.0, (r - 0.25) / 0.75);
  o = vec4(uColor.rgb, uColor.a * a);
}
"""

/** The flat ring that ripples out of home. */
internal const val FLAT_VERT = """#version 300 es
layout(location = 0) in vec3 a_pos;
uniform mat4 uMVP;
void main() {
  gl_Position = uMVP * vec4(a_pos, 1.0);
}
"""

internal const val FLAT_FRAG = """#version 300 es
precision mediump float;
uniform vec4 uColor;
out vec4 o;
void main() {
  o = uColor;
}
"""

/** The page behind the planet, dark theme: one soft cone of light from the top (the desktop's body::before). */
internal const val CONE_VERT = """#version 300 es
void main() {
  vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
  gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
}
"""

internal const val CONE_FRAG = """#version 300 es
precision mediump float;
uniform vec2 uRes;
out vec4 o;
void main() {
  vec2 s = vec2(gl_FragCoord.x / uRes.x, 1.0 - gl_FragCoord.y / uRes.y);
  float d = length((s - vec2(0.5, -0.08)) / vec2(0.6, 0.45));
  o = vec4(vec3(1.0), 0.07 * clamp(1.0 - d / 0.7, 0.0, 1.0));
}
"""

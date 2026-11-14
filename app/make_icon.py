"""Draw Mirage's icons: the astrolabe eye, for Windows and for Android.

    python app/make_icon.py

One drawing, every icon. The eye is the approved master in
preview/logo/mirage-eye.svg (120 grid, black tile #0A0909, dark red mark):

    upper lid    Q curve 22,60 -> 98,60 through 60,20, #A3141F, width 4
    lower lid    the reflection, Q curve through 60,100, #6E0D16, width 3,
                 dashed 1.5 / 4.5 with round caps - a row of dots
    degree ring  r 15, 24 ticks (dash 0.9 / 3.03, width 3)
    inner ring   r 11.5, width 1.4
    pupil        r 6, cut by a horizon line 1.2 high from x 44 to 76
    bead         r 2.4 at 70.6, 49.4

The same drawing is used in the app itself (ui/Mirage.kt MirageEye, the
page's #mirageEye), so the icon and the screen are one mark.

Written from here:

  app/assets/app.ico, app.png      the exe, the window, the taskbar, the
                                   tray (main.Tray._image greys it when off)
  android res mipmap-*/ic_launcher.png
                                   the launcher icon before Android 8
  android res drawable/ic_launcher_fg.xml, ic_launcher_mono.xml
                                   the adaptive icon's mark, and the one-colour
                                   one Android 13 tints for themed icons
  android res drawable/ic_relay.xml
                                   the status bar, notification and quick
                                   tile icon (white; Android tints it)
  android res values/ic_launcher.xml
                                   the adaptive icon's background, the tile

Small sizes are drawn, not shrunk: at 16 and 24 px the 24 ticks, the dotted
reflection and the horizon would be grey noise, so those sizes keep the two
lids, the iris and the pupil, drawn heavier.
"""

import math
import os

from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
ICO = os.path.join(HERE, 'assets', 'app.ico')
PNG = os.path.join(HERE, 'assets', 'app.png')
RES = os.path.join(ROOT, 'android', 'app', 'src', 'main', 'res')

TILE = '#0A0909'
RED = '#A3141F'
LID = '#6E0D16'

ICO_SIZES = (16, 24, 32, 48, 64, 128, 256)
MIPMAPS = {'mdpi': 48, 'hdpi': 72, 'xhdpi': 96, 'xxhdpi': 144,
           'xxxhdpi': 192}
SS = 8            # supersampling for the PNGs


# -- the geometry, on the 120 grid -----------------------------------------

def quad(p0, c, p1, t):
    u = 1 - t
    return (u * u * p0[0] + 2 * u * t * c[0] + t * t * p1[0],
            u * u * p0[1] + 2 * u * t * c[1] + t * t * p1[1])


UPPER = ((22, 60), (60, 20), (98, 60))
LOWER = ((22, 60), (60, 100), (98, 60))


def curve(spec, n=96):
    return [quad(*spec, t=i / n) for i in range(n + 1)]


def along(points, step, first):
    """Points every `step` units of arc length, starting `first` in."""
    out, walked, want = [], 0.0, first
    for a, b in zip(points, points[1:]):
        seg = math.dist(a, b)
        while walked + seg >= want:
            f = (want - walked) / seg
            out.append((a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f))
            want += step
        walked += seg
    return out


def lower_dots():
    # Dash 1.5 with round caps every 6 units: the dot's middle sits 0.75 in.
    return along(curve(LOWER, 400), 6.0, 0.75)


def ticks():
    """24 radial ticks on r 15, 3 deep and 0.9 wide: (angle, ...) as the
    SVG's dash pattern lays them, from three o'clock, clockwise on screen."""
    period = 2 * math.pi * 15 / 24
    return [((k * period + 0.45) / 15) for k in range(24)]


# -- the PNGs and the .ico --------------------------------------------------

def _rgb(hexa):
    return tuple(int(hexa[i:i + 2], 16) for i in (1, 3, 5)) + (255,)


def draw(size, tile=True):
    """The icon at `size` px, drawn at SS times and brought down once."""
    big = size * SS
    k = big / 120.0
    img = Image.new('RGBA', (big, big), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    if tile:
        d.rounded_rectangle((0, 0, big - 1, big - 1), radius=26 * k,
                            fill=_rgb(TILE))
    small = size <= 24
    mid = size <= 48

    def line(points, colour, width):
        pts = [(x * k, y * k) for x, y in points]
        w = width * k
        d.line(pts, fill=_rgb(colour), width=max(1, round(w)), joint='curve')
        for x, y in (pts[0], pts[-1]):
            d.ellipse((x - w / 2, y - w / 2, x + w / 2, y + w / 2),
                      fill=_rgb(colour))

    def disc(x, y, r, colour):
        d.ellipse(((x - r) * k, (y - r) * k, (x + r) * k, (y + r) * k),
                  fill=_rgb(colour))

    def ring(r, width, colour):
        d.ellipse(((60 - r) * k, (60 - r) * k, (60 + r) * k, (60 + r) * k),
                  outline=_rgb(colour), width=max(1, round(width * k)))

    if small:
        # Heavier and simpler: what survives at 16 px.
        line(curve(UPPER), RED, 7)
        line(curve(LOWER), LID, 5)
        ring(14, 4.5, RED)
        disc(60, 60, 7.5, RED)
        return img.resize((size, size), Image.LANCZOS)

    line(curve(UPPER), RED, 5 if mid else 4)
    if mid:
        line(curve(LOWER), LID, 3)
    else:
        for x, y in lower_dots():
            disc(x, y, 1.5, LID)
        for a in ticks():
            c, s = math.cos(a), math.sin(a)
            w = 0.9 * k
            p = [((60 + 13.5 * c) * k, (60 + 13.5 * s) * k),
                 ((60 + 16.5 * c) * k, (60 + 16.5 * s) * k)]
            d.line(p, fill=_rgb(RED), width=max(1, round(w)))
    ring(11.5 if not mid else 12.5, 1.4 if not mid else 2.4, RED)
    disc(60, 60, 6, RED)
    # The horizon: the tile showing through.
    d.rectangle((44 * k, 59.4 * k, 76 * k, 60.6 * k), fill=_rgb(TILE))
    disc(70.6, 49.4, 2.4, RED)
    return img.resize((size, size), Image.LANCZOS)


# -- Android vectors --------------------------------------------------------

def _f(v):
    return f'{v:.2f}'.rstrip('0').rstrip('.')


def _q(spec):
    (x0, y0), (cx, cy), (x1, y1) = spec
    return f'M{x0},{y0} Q{cx},{cy} {x1},{y1}'


def _circle(x, y, r):
    return (f'M{_f(x - r)},{_f(y)} a{_f(r)},{_f(r)} 0 1,0 {_f(2 * r)},0 '
            f'a{_f(r)},{_f(r)} 0 1,0 {_f(-2 * r)},0 Z')


def _ring(r, w):
    """A ring as a filled shape (outer disc minus inner, even-odd)."""
    return _circle(60, 60, r + w / 2) + ' ' + _circle(60, 60, r - w / 2)


def _ticks():
    out = []
    for a in ticks():
        c, s = math.cos(a), math.sin(a)
        tx, ty = -s * 0.45, c * 0.45            # half the tick's width
        p = [(60 + 13.5 * c + tx, 60 + 13.5 * s + ty),
             (60 + 16.5 * c + tx, 60 + 16.5 * s + ty),
             (60 + 16.5 * c - tx, 60 + 16.5 * s - ty),
             (60 + 13.5 * c - tx, 60 + 13.5 * s - ty)]
        out.append('M' + ' L'.join(f'{_f(x)},{_f(y)}' for x, y in p) + ' Z')
    return ' '.join(out)


def _pupil_halves():
    """The pupil with the horizon cut out, as two shapes - a vector has no
    way to paint 'nothing' over something already drawn."""
    r, h = 6.0, 0.6
    dx = math.sqrt(r * r - h * h)
    top = (f'M{_f(60 - dx)},{_f(60 - h)} A{_f(r)},{_f(r)} 0 1,1 '
           f'{_f(60 + dx)},{_f(60 - h)} Z')
    bottom = (f'M{_f(60 + dx)},{_f(60 + h)} A{_f(r)},{_f(r)} 0 1,1 '
              f'{_f(60 - dx)},{_f(60 + h)} Z')
    return top + ' ' + bottom


def _paths(colour_red, colour_lid, simple=False):
    """The eye as VectorDrawable <path> elements on the 120 grid."""
    p = []
    p.append(f'<path android:strokeColor="{colour_red}" '
             f'android:strokeWidth="{6 if simple else 4}" '
             f'android:strokeLineCap="round" '
             f'android:pathData="{_q(UPPER)}" />')
    if simple:
        p.append(f'<path android:strokeColor="{colour_lid}" '
                 f'android:strokeWidth="4" android:strokeLineCap="round" '
                 f'android:pathData="{_q(LOWER)}" />')
        p.append(f'<path android:fillColor="{colour_red}" '
                 f'android:fillType="evenOdd" '
                 f'android:pathData="{_ring(13, 4)}" />')
        p.append(f'<path android:fillColor="{colour_red}" '
                 f'android:pathData="{_circle(60, 60, 7)}" />')
        return p
    dots = ' '.join(_circle(x, y, 1.5) for x, y in lower_dots())
    p.append(f'<path android:fillColor="{colour_lid}" '
             f'android:pathData="{dots}" />')
    p.append(f'<path android:fillColor="{colour_red}" '
             f'android:pathData="{_ticks()}" />')
    p.append(f'<path android:fillColor="{colour_red}" '
             f'android:fillType="evenOdd" '
             f'android:pathData="{_ring(11.5, 1.4)}" />')
    p.append(f'<path android:fillColor="{colour_red}" '
             f'android:pathData="{_pupil_halves()}" />')
    p.append(f'<path android:fillColor="{colour_red}" '
             f'android:pathData="{_circle(70.6, 49.4, 2.4)}" />')
    return p


def _vector(comment, size_dp, viewport, group, paths, tint=None):
    inner = '\n'.join('        ' + x for x in paths)
    tint_attr = f'\n    android:tint="{tint}"' if tint else ''
    return f'''<?xml version="1.0" encoding="utf-8"?>
<!--
    {comment}
    Written by app/make_icon.py from the master drawing
    (preview/logo/mirage-eye.svg); change that, not this.
-->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="{size_dp}dp"
    android:height="{size_dp}dp"
    android:viewportWidth="{viewport}"
    android:viewportHeight="{viewport}"{tint_attr}>
    <group {group}>
{inner}
    </group>
</vector>
'''


# The adaptive icon is 108dp, and every launcher mask keeps the middle 66dp.
# The eye is 76 wide on the 120 grid; at 0.8dp a unit it is 61dp across, its
# ends 30.4dp from the middle - inside the 33dp every mask leaves.
ADAPTIVE_GROUP = ('android:scaleX="0.8" android:scaleY="0.8" '
                  'android:translateX="6" android:translateY="6"')


def write_android():
    drawable = os.path.join(RES, 'drawable')
    with open(os.path.join(drawable, 'ic_launcher_fg.xml'), 'w',
              encoding='utf-8', newline='\n') as f:
        f.write(_vector(
            "Mirage's mark for the adaptive launcher icon, on the black tile\n"
            "    (values/ic_launcher.xml), inside the 66dp every mask keeps.",
            108, 108, ADAPTIVE_GROUP, _paths(RED, LID)))
    with open(os.path.join(drawable, 'ic_launcher_mono.xml'), 'w',
              encoding='utf-8', newline='\n') as f:
        f.write(_vector(
            'The mark in one colour, for themed icons on Android 13 and '
            'later:\n    the launcher tints it, so only its shape counts.',
            108, 108, ADAPTIVE_GROUP,
            _paths('#FFFFFF', '#FFFFFF', simple=True)))
    with open(os.path.join(drawable, 'ic_relay.xml'), 'w',
              encoding='utf-8', newline='\n') as f:
        # The status bar's 24dp: the eye filling the width, simplified.
        f.write(_vector(
            'The mark in white for the status bar, the notification and the\n'
            '    quick-settings tile, where Android tints it and only its '
            'shape counts.',
            24, 120,
            'android:scaleX="1.42" android:scaleY="1.42" '
            'android:translateX="-25.2" android:translateY="-25.2"',
            _paths('#FFFFFF', '#FFFFFF', simple=True), tint='#FFFFFF'))
    with open(os.path.join(RES, 'values', 'ic_launcher.xml'), 'w',
              encoding='utf-8', newline='\n') as f:
        f.write('<?xml version="1.0" encoding="utf-8"?>\n<resources>\n'
                '    <!-- The tile under the mark: the master drawing\'s '
                'black. -->\n'
                f'    <color name="ic_launcher_bg">{TILE}</color>\n'
                '</resources>\n')
    for dpi, px in MIPMAPS.items():
        draw(px).save(os.path.join(RES, f'mipmap-{dpi}', 'ic_launcher.png'))


def main():
    # Pillow writes an .ico from the first image plus the sizes asked for,
    # resampling the largest; handing it each size drawn on its own keeps
    # the small ones the simplified drawing.
    frames = [draw(n) for n in ICO_SIZES]
    frames[-1].save(ICO, format='ICO', sizes=[(n, n) for n in ICO_SIZES],
                    append_images=frames[:-1])
    draw(256).save(PNG)
    write_android()
    print('wrote', ICO, PNG, 'and the Android icons under', RES)


if __name__ == '__main__':
    main()

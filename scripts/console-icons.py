#!/usr/bin/env python3
"""
Draws ThorBro's console icons: every console as a little doodle robot - the device is the body,
its screen (or a dark panel on its front) is the face, with arms, round feet, an antenna with smoke
and a sparkle.

The hand-drawn wobble is baked into the paths (no SVG filters), so the same files look identical in
the web panel and in the Compose app (AndroidSVG has no feTurbulence).

    python3 scripts/console-icons.py      # -> companion/app/src/main/assets/consoles/<id>.svg

Ids are the ones in Roms.systems.
"""
import math
import os
import sys

OUT = os.path.join(os.path.dirname(__file__), "..", "companion", "app", "src", "main", "assets", "consoles")

INK = "#1a1e1f"
CREAM = "#EFE7D2"
SAGE = "#A5B98C"
ORANGE = "#F0A94B"
CORAL = "#FF9C84"
SLATE = "#4C5457"
RIM = "#7F9A66"
SHADE = {CREAM: "#D8CEB3", SAGE: "#8BA071", ORANGE: "#D68F35", CORAL: "#E3806A", SLATE: "#3B4245", INK: INK}


# ---------------------------------------------------------------- geometry

def lerp(a, b, t):
    return (a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t)


def densify(pts, closed, step=5.0):
    out = []
    n = len(pts)
    segs = n if closed else n - 1
    for i in range(segs):
        a, b = pts[i], pts[(i + 1) % n]
        d = math.dist(a, b)
        k = max(1, int(d / step))
        for j in range(k):
            out.append(lerp(a, b, j / k))
    if not closed:
        out.append(pts[-1])
    return out


def chaikin(pts, closed, it=2):
    for _ in range(it):
        out = []
        n = len(pts)
        rng = range(n) if closed else range(n - 1)
        if not closed:
            out.append(pts[0])
        for i in rng:
            a, b = pts[i], pts[(i + 1) % n]
            out += [lerp(a, b, .25), lerp(a, b, .75)]
        if not closed:
            out.append(pts[-1])
        pts = out
    return pts


def rrect(x0, y0, x1, y1, r):
    r = min(r, (x1 - x0) / 2, (y1 - y0) / 2)
    pts = []
    for cx, cy, a0 in ((x1 - r, y0 + r, -90), (x1 - r, y1 - r, 0), (x0 + r, y1 - r, 90), (x0 + r, y0 + r, 180)):
        for k in range(7):
            a = math.radians(a0 + k * 15)
            pts.append((cx + r * math.cos(a), cy + r * math.sin(a)))
    return pts


def ellipse(cx, cy, rx, ry, a0=0, a1=360, n=None):
    n = n or max(12, int((rx + ry) * 0.6))
    return [(cx + rx * math.cos(math.radians(a0 + (a1 - a0) * i / n)), cy + ry * math.sin(math.radians(a0 + (a1 - a0) * i / n)))
            for i in range(n + (0 if (a1 - a0) >= 360 else 1))]


def soft(pts, it=2):
    """polygon with rounded doodle corners"""
    return chaikin(pts, True, it)


def star(cx, cy, r):
    pts = []
    for i in range(8):
        a = math.radians(-90 + i * 45)
        rr = r if i % 2 == 0 else r * 0.32
        pts.append((cx + rr * math.cos(a), cy + rr * math.sin(a)))
    return pts


class Noise:
    def __init__(self, seed, amp):
        self.amp = amp
        s = seed * 12.9898
        self.t = []
        for k in range(3):
            f = 0.035 + 0.03 * k
            self.t.append((f * math.cos(s + k), f * math.sin(s * 1.7 + k), (s * 3.1 + k * 2.3) % 6.28, 1 / (k + 1)))

    def __call__(self, x, y):
        v = sum(w * math.sin(fx * x + fy * y + ph) for fx, fy, ph, w in self.t)
        return v * self.amp / 1.83


def wobble(pts, seed, amp):
    nx, ny = Noise(seed, amp), Noise(seed + 31, amp)
    return [(x + nx(x, y), y + ny(x, y)) for x, y in pts]


def d_of(pts, closed):
    """smooth path through wobbly points (midpoint quadratic curves)"""
    f = lambda p: f"{p[0]:.1f} {p[1]:.1f}"
    if len(pts) < 3:
        return f"M{f(pts[0])}" + "".join(f"L{f(p)}" for p in pts[1:])
    if closed:
        m0 = lerp(pts[-1], pts[0], .5)
        d = f"M{f(m0)}"
        for i, p in enumerate(pts):
            d += f"Q{f(p)} {f(lerp(p, pts[(i + 1) % len(pts)], .5))}"
        return d + "Z"
    d = f"M{f(pts[0])}"
    for i in range(1, len(pts) - 1):
        d += f"Q{f(pts[i])} {f(lerp(pts[i], pts[i + 1], .5))}"
    return d + f"L{f(pts[-1])}"


# ---------------------------------------------------------------- drawing items

class Icon:
    def __init__(self, seed):
        self.seed = seed
        self.out = []
        self.clips = []
        self.n = 0

    def _seed(self):
        self.n += 1
        return self.seed * 101 + self.n * 7

    def shape(self, pts, fill=CREAM, sw=6.5, shade=None, closed=True, amp=1.8, offset=True):
        dense = densify(pts, closed)
        s = self._seed()
        if fill and closed:
            fp = wobble(dense, s, amp)
            dx, dy = (2.2, 1.8) if offset else (0, 0)
            d = d_of(fp, True)
            self.out.append(f'<path d="{d}" fill="{fill}" transform="translate({dx} {dy})"/>')
            if shade:
                ys = [p[1] for p in pts]
                xs = [p[0] for p in pts]
                cut = shade if shade > 1 else max(ys) - (max(ys) - min(ys)) * shade
                cid = f"c{len(self.clips)}"
                self.clips.append(f'<clipPath id="{cid}"><path d="{d}" transform="translate({dx} {dy})"/></clipPath>')
                band = soft([(min(xs) - 10, cut + 3), (max(xs) + 10, cut - 3), (max(xs) + 10, max(ys) + 10), (min(xs) - 10, max(ys) + 10)], 1)
                self.out.append(f'<path d="{d_of(wobble(densify(band, True), s + 5, 1.5), True)}" fill="{SHADE.get(fill, fill)}" clip-path="url(#{cid})"/>')
        if sw:
            op = wobble(dense, s + 13, amp)
            self.out.append(f'<path d="{d_of(op, closed)}" fill="none" stroke="{INK}" stroke-width="{sw}"/>')

    def line(self, pts, color=INK, sw=5, smooth=True, double=True, amp=1.4):
        if smooth and len(pts) > 2:
            pts = chaikin(pts, False, 2)
        s = self._seed()
        p = wobble(densify(pts, False, 4), s, amp)
        d = d_of(p, False)
        self.out.append(f'<path d="{d}" fill="none" stroke="{color}" stroke-width="{sw}"/>')
        if double:
            self.out.append(f'<path d="{d}" fill="none" stroke="{color}" stroke-width="{sw * .4:.1f}" transform="translate(1.6 1)"/>')

    def tube(self, pts, w=8):
        """limb: dark outline with a cream core, readable on dark and light backgrounds"""
        pts = chaikin(pts, False, 2) if len(pts) > 2 else pts
        p = wobble(densify(pts, False, 4), self._seed(), 1.2)
        d = d_of(p, False)
        self.out.append(f'<path d="{d}" fill="none" stroke="{INK}" stroke-width="{w}"/>')
        self.out.append(f'<path d="{d}" fill="none" stroke="{CREAM}" stroke-width="{w - 5:.1f}"/>')

    def blob(self, pts, color, amp=1.0):
        """flat fill, no outline (eyes, highlights)"""
        p = wobble(densify(pts, True, 3), self._seed(), amp)
        self.out.append(f'<path d="{d_of(p, True)}" fill="{color}"/>')

    # ------------ shared body parts

    def face(self, x0, y0, x1, y1, r=8, screen=INK, ink=CREAM, outline=True, mood="happy"):
        w, h = x1 - x0, y1 - y0
        if screen:
            self.shape(rrect(x0, y0, x1, y1, r), fill=screen, sw=5.5 if outline else 0, offset=False, amp=1.2)
        cx, cy = (x0 + x1) / 2, y0 + h * 0.44
        ew = min(w * 0.17, h * 0.36)
        eh = ew * 1.0
        gap = max(w * 0.17, ew * 0.95)
        for sx in (-1, 1):
            ex = cx + sx * gap
            if mood == "wink" and sx > 0:
                self.line([(ex - ew / 2, cy + eh * .1), (ex, cy - eh * .25), (ex + ew / 2, cy + eh * .1)], ink, max(2.5, ew * .22), double=False, amp=.5)
                continue
            # round eye with a little glint
            self.blob(ellipse(ex, cy, ew * .44, eh * .62, n=14), ink, .5)
            if ew > 9:
                self.blob(ellipse(ex - ew * .14, cy - eh * .22, ew * .12, ew * .12, n=8), screen or INK, .2)
        sm = max(w * 0.11, ew * .55)
        sy = y0 + h * 0.74
        self.line([(cx - sm, sy - 1), (cx, sy + sm * .45), (cx + sm, sy - 2)], ink, max(2.5, ew * .2), double=False, amp=.5)
        if screen and w > 30:
            # glint
            self.line([(x0 + w * .08, y0 + h * .34), (x0 + w * .08, y0 + h * .2), (x0 + w * .16, y0 + h * .14)], ink, 2.2, double=False, amp=.4)

    def limbs(self, x0, y0, x1, y1, arm_y=None, feet=None, arms=True):
        cx, w = (x0 + x1) / 2, x1 - x0
        x0, x1 = max(x0, 40), min(x1, 216)  # keep the hands inside the canvas
        ay = arm_y if arm_y is not None else y0 + (y1 - y0) * 0.55
        if arms:
            self.tube([(x0 + 4, ay), (x0 - 12, ay + 8), (x0 - 18, ay + 22)], 8.5)
            self.shape(ellipse(x0 - 19, ay + 29, 7.5, 7), CREAM, 5)
            self.tube([(x1 - 4, ay), (x1 + 13, ay - 6), (x1 + 20, ay - 20)], 8.5)
            self.shape(ellipse(x1 + 21, ay - 27, 7.5, 7), CREAM, 5)
        fs = feet or (cx - min(w * 0.24, 34), cx + min(w * 0.24, 34))
        for fx in fs:
            self.tube([(fx, y1 - 4), (fx + 1, y1 + 9)], 9)
            self.shape(ellipse(fx, y1 + 16, 15, 10), CREAM, 5.5)
            self.line([(fx - 11, y1 + 21), (fx, y1 + 25), (fx + 11, y1 + 21)], RIM, 4.5, double=False, amp=.6)

    def antenna(self, bx, by, tx, ty):
        self.tube([(bx, by), (tx, ty)], 8)
        self.shape(ellipse(tx, ty, 6, 6), ORANGE, 4.5)
        for pts, w in (([(tx + 3, ty - 9), (tx - 4, ty - 15), (tx + 3, ty - 21), (tx - 1, ty - 27)], 3.2), ([(tx + 11, ty - 6), (tx + 15, ty - 12), (tx + 11, ty - 18)], 2.8)):
            self.line(pts, CREAM, w + 3, double=False, amp=.3)
            self.line(pts, INK, w, double=False, amp=.3)

    def sparkle(self, cx=38, cy=46, r=13):
        self.shape(star(cx, cy, r), CREAM, 4, amp=.6)
        self.shape(ellipse(cx + 16, cy + 16, 3, 3), INK, 0, amp=.3, offset=False)

    def svg(self, tilt=-3):
        return ('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 256 256" width="256" height="256">'
                + (f"<defs>{''.join(self.clips)}</defs>" if self.clips else "")
                + f'<g transform="rotate({tilt} 128 128)" stroke-linecap="round" stroke-linejoin="round">'
                + "".join(self.out) + "</g></svg>")


# small details
def dpad(I, cx, cy, s=9, fill=INK):
    a, b = s, s * 0.36
    I.shape(soft([(cx - b, cy - a), (cx + b, cy - a), (cx + b, cy - b), (cx + a, cy - b), (cx + a, cy + b), (cx + b, cy + b),
                  (cx + b, cy + a), (cx - b, cy + a), (cx - b, cy + b), (cx - a, cy + b), (cx - a, cy - b), (cx - b, cy - b)], 1),
            fill, 3.5, amp=.5, offset=False)


def btn(I, cx, cy, r=5, fill=CORAL, sw=3.5):
    I.shape(ellipse(cx, cy, r, r), fill, sw, amp=.4, offset=False)


def stick(I, cx, cy, r=7, fill=SLATE):
    I.shape(ellipse(cx, cy, r, r), fill, 3.5, amp=.4, offset=False)
    I.shape(ellipse(cx - r * .2, cy - r * .2, r * .45, r * .45), SHADE.get(fill, fill), 0, amp=.2, offset=False)


def slot(I, x0, x1, y):
    I.line([(x0, y), (x1, y)], INK, 4.5, smooth=False, double=False, amp=.8)


def ridges(I, x0, x1, y0, y1, n, vertical=True, sw=3):
    for i in range(n):
        t = (i + 0.5) / n
        if vertical:
            x = x0 + (x1 - x0) * t
            I.line([(x, y0), (x, y1)], INK, sw, smooth=False, double=False, amp=.6)
        else:
            y = y0 + (y1 - y0) * t
            I.line([(x0, y), (x1, y)], INK, sw, smooth=False, double=False, amp=.6)


def speaker(I, x, y, n=4, l=16):
    for i in range(n):
        I.line([(x + i * 6, y + l * .5), (x + i * 6 + l * .45, y - l * .5)], INK, 3, smooth=False, double=False, amp=.4)


# ---------------------------------------------------------------- consoles
# every function draws one console; body roughly inside x 44..212, y 56..200

def home_box(I, x0, y0, x1, y1, fill=CREAM, r=10, ant=None, arm_y=None, feet=None):
    I.limbs(x0, y0, x1, y1, arm_y, feet)
    bx = ant[0] if ant else x1 - (x1 - x0) * .24
    I.antenna(bx, ant[1] if ant else y0 + 4, bx + 16, (ant[1] if ant else y0) - 30)


def nes(I):
    x0, y0, x1, y1 = 40, 98, 216, 186
    home_box(I, x0, y0, x1, y1)
    I.shape(soft([(x0, y0), (x1, y0), (x1, y1), (x0, y1)], 1), CREAM, shade=.25)
    I.shape(soft([(x0 + 6, y0 + 6), (x1 - 6, y0 + 6), (x1 - 6, y0 + 36), (x0 + 6, y0 + 36)], 1), SHADE[CREAM], 4, offset=False)
    ridges(I, x0 + 24, x1 - 24, y0 + 12, y0 + 30, 9)
    I.shape(soft([(x0, y0 + 46), (x1, y0 + 46), (x1, y0 + 52), (x0, y0 + 52)], 1), INK, 0, offset=False, amp=.6)
    I.face(104, y0 + 56, 200, y1 - 6, 6)
    for i in range(2):
        I.shape(rrect(52 + i * 22, y1 - 26, 68 + i * 22, y1 - 14, 3), INK, 3, offset=False, amp=.4)
    btn(I, 60, y0 + 64, 4.5, CORAL)
    btn(I, 80, y0 + 64, 4.5, CREAM)


def snes(I):
    x0, y0, x1, y1 = 42, 106, 214, 186
    home_box(I, x0, y0, x1, y1, ant=(186, y0 + 6))
    I.shape(rrect(90, y0 - 20, 166, y0 + 10, 12), SHADE[CREAM], 6)
    slot(I, 104, 152, y0 - 8)
    I.shape(rrect(x0, y0, x1, y1, 30), CREAM, shade=.3)
    I.shape(rrect(58, y0 + 8, 76, y0 + 20, 4), SLATE, 3.5, offset=False, amp=.5)
    I.shape(rrect(180, y0 + 8, 198, y0 + 20, 4), SLATE, 3.5, offset=False, amp=.5)
    I.face(88, y0 + 22, 168, y1 - 10, 10)
    for i, c in enumerate((CORAL, ORANGE, SAGE, SLATE)):
        btn(I, 180 + (i % 2) * 13, y0 + 38 + (i // 2) * 13, 4.5, c, 3)


def n64(I):
    x0, y0, x1, y1 = 36, 92, 220, 186
    I.limbs(x0, 134, x1, y1, arm_y=152)
    I.antenna(176, y0 + 8, 192, y0 - 24)
    body = [(x0, 140), (66, 128), (80, y0 + 6), (100, y0), (156, y0), (176, y0 + 6), (190, 128), (x1, 140), (x1, y1), (x0, y1)]
    I.shape(soft(body, 2), SLATE, shade=.25)
    slot(I, 104, 152, y0 + 12)
    I.shape(rrect(96, y0 + 6, 160, y0 + 18, 5), INK, 0, offset=False, amp=.5)
    btn(I, 66, 140, 5, CREAM)
    btn(I, 190, 140, 5, CORAL)
    I.face(88, 124, 168, 176, 8, outline=True)
    for i in range(4):
        I.shape(rrect(90 + i * 20, y1 - 10, 102 + i * 20, y1 - 3, 2), INK, 0, offset=False, amp=.3)


def gb(I, fill=CREAM, small=False):
    x0, y0, x1, y1 = (80, 66, 176, 198) if small else (74, 52, 182, 206)
    I.limbs(x0, y0, x1, y1, arm_y=y0 + (y1 - y0) * .42)
    I.antenna(x0 + 20, y0 + 4, x0 + 2, y0 - 26)
    body = rrect(x0, y0, x1, y1, 10)
    # big rounded bottom-right corner
    body = [p for p in body if not (p[0] > x1 - 12 and p[1] > y1 - 12)]
    body = soft([(x0 + 6, y0), (x1 - 6, y0), (x1, y0 + 6), (x1, y1 - 34), (x1 - 34, y1), (x0 + 6, y1), (x0, y1 - 6), (x0, y0 + 6)], 2)
    I.shape(body, fill, shade=.2)
    I.line([(x0 + 4, y0 + 10), (x1 - 4, y0 + 10)], INK, 2.5, smooth=False, double=False)
    bez = (x0 + 10, y0 + 18, x1 - 10, y0 + (66 if small else 78))
    I.shape(rrect(*bez, 8), SLATE, 4.5, offset=False, amp=.8)
    btn(I, bez[0] + 6, bez[1] + 18, 2.5, CORAL, 0)
    I.face(bez[0] + 14, bez[1] + 8, bez[2] - 10, bez[3] - 8, 4, outline=False)
    by = bez[3] + (24 if small else 30)
    dpad(I, x0 + 26, by, 10)
    btn(I, x1 - 38, by + 6, 7, CORAL)
    btn(I, x1 - 20, by - 4, 7, CORAL)
    speaker(I, x1 - 46, y1 - 22, 4, 14)


def gbc(I):
    gb(I, CORAL, small=True)


def gba(I):
    x0, y0, x1, y1 = 38, 92, 218, 182
    I.limbs(x0, y0, x1, y1)
    I.antenna(178, y0 + 2, 194, y0 - 28)
    body = [(x0 + 8, y0 + 14), (70, y0 + 2), (186, y0 + 2), (x1 - 8, y0 + 14), (x1, 140), (x1 - 10, y1), (x0 + 10, y1), (x0, 140)]
    I.shape(soft(body, 2), SAGE, shade=.22)
    I.shape(rrect(84, y0 + 10, 172, y1 - 12, 10), SLATE, 4.5, offset=False, amp=.8)
    I.face(94, y0 + 18, 162, y1 - 20, 5, outline=False)
    dpad(I, 62, 130, 10)
    btn(I, 190, 138, 7, ORANGE)
    btn(I, 202, 122, 7, ORANGE)
    btn(I, 54, 158, 3, INK, 0)
    btn(I, 64, 162, 3, INK, 0)


def nds(I, fill=CREAM, three=False):
    x0, x1 = (54, 202) if not three else (46, 210)
    top0, top1 = 54, 128
    bot0, bot1 = 132, 204
    I.limbs(x0, bot0, x1, bot1, arm_y=150)
    I.antenna(x1 - 30, top0 + 3, x1 - 14, top0 - 26)
    I.shape(rrect(x0, top0, x1, top1, 12), fill, shade=.2)
    I.shape(rrect(x0, bot0, x1, bot1, 12), fill, shade=.3)
    I.shape(rrect(x0 + 6, top1 - 6, x1 - 6, bot0 + 6, 5), SHADE.get(fill, fill), 4, offset=False, amp=.6)
    if three:
        I.face(x0 + 22, top0 + 10, x1 - 22, top1 - 12, 6)
        stick(I, x0 + 20, bot0 + 20, 8, CREAM)
        dpad(I, x0 + 20, bot0 + 46, 8)
        I.shape(rrect(x0 + 44, bot0 + 10, x1 - 44, bot1 - 14, 4), INK, 4, offset=False, amp=.6)
    else:
        I.face(x0 + 30, top0 + 10, x1 - 30, top1 - 12, 6)
        dpad(I, x0 + 18, bot0 + 30, 9)
        I.shape(rrect(x0 + 40, bot0 + 10, x1 - 40, bot1 - 14, 4), INK, 4, offset=False, amp=.6)
    for i, (dx, dy) in enumerate(((0, -10), (10, 0), (-10, 0), (0, 10))):
        btn(I, x1 - 18 + dx * .9, bot0 + 32 + dy * .9, 4.2, (CORAL, ORANGE, SAGE, CREAM)[i], 3)
    # tiny smile on the bottom screen too? no - it shows a little heart
    hx, hy = (x0 + x1) / 2, (bot0 + bot1) / 2 - 3
    I.blob([(hx, hy + 8), (hx - 9, hy - 1), (hx - 6, hy - 7), (hx, hy - 3), (hx + 6, hy - 7), (hx + 9, hy - 1)], CORAL, .4)


def n3ds(I):
    nds(I, CORAL, three=True)


def virtualboy(I):
    x0, y0, x1, y1 = 40, 72, 216, 140
    # bipod stand
    I.tube([(110, y1), (96, 196)], 10)
    I.tube([(146, y1), (160, 196)], 10)
    I.limbs(x0 + 30, y0, x1 - 30, 196, arm_y=106, arms=False, feet=(96, 160))
    I.limbs(x0, y0, x1, y1, arm_y=104, feet=())
    I.antenna(180, y0 + 4, 196, y0 - 26)
    body = [(x0, y0 + 20), (x0 + 18, y0), (x1 - 18, y0), (x1, y0 + 20), (x1, y1 - 18), (x1 - 30, y1), (150, y1), (128, y1 - 12), (106, y1), (x0 + 30, y1), (x0, y1 - 18)]
    I.shape(soft(body, 2), CORAL, shade=.3)
    I.face(62, y0 + 12, 194, y1 - 22, 14)
    I.shape(rrect(x0 + 6, y0 - 8, x0 + 40, y0 + 4, 4), SLATE, 4, offset=False, amp=.5)


def gc(I):
    x0, y0, x1, y1 = 62, 84, 194, 198
    I.line([(80, y0 + 10), (82, y0 - 16), (174, y0 - 16), (176, y0 + 10)], INK, 12, double=False)
    I.line([(80, y0 + 10), (82, y0 - 16), (174, y0 - 16), (176, y0 + 10)], SLATE, 5, double=False)
    I.limbs(x0, y0, x1, y1)
    I.antenna(x1 - 6, y0 + 20, x1 + 16, y0 - 2)
    I.shape(rrect(x0, y0, x1, y1, 16), SLATE, shade=.22)
    I.shape(rrect(x0 + 6, y0 + 6, x1 - 6, y0 + 28, 8), SHADE[SLATE], 3.5, offset=False, amp=.6)
    I.shape(ellipse(128, y0 + 17, 26, 7), SLATE, 3.5, offset=False, amp=.5)
    I.face(x0 + 18, y0 + 40, x1 - 18, y1 - 22, 10)
    btn(I, x0 + 16, y1 - 12, 3.5, ORANGE, 2.5)
    btn(I, x1 - 16, y1 - 12, 3.5, SAGE, 2.5)


def wii(I):
    x0, y0, x1, y1 = 92, 56, 164, 198
    I.limbs(x0, y0, x1, y1, arm_y=120, feet=(108, 150))
    I.antenna(x1 - 14, y0 + 4, x1 + 2, y0 - 26)
    I.shape(rrect(x0, y0, x1, y1, 14), CREAM, shade=.15)
    I.line([(x1 - 10, y0 + 18), (x1 - 10, y1 - 18)], SAGE, 4.5, smooth=False, double=False)
    I.face(x0 + 8, y0 + 46, x1 - 18, y0 + 104, 8)
    btn(I, x0 + 14, y0 + 16, 4, SAGE, 3)
    # a remote in the right hand
    I.shape(soft([(x1 + 26, 72), (x1 + 38, 74), (x1 + 34, 116), (x1 + 22, 114)], 1), CREAM, 5, amp=.8)
    btn(I, x1 + 31, 84, 3, SAGE, 2.5)
    btn(I, x1 + 29, 100, 2.4, INK, 0)


def wiiu(I):
    x0, y0, x1, y1 = 34, 88, 222, 186
    I.limbs(x0, y0, x1, y1)
    I.antenna(186, y0 + 4, 200, y0 - 26)
    I.shape(soft([(x0 + 4, y0 + 16), (x0 + 14, y0), (x1 - 14, y0), (x1 - 4, y0 + 16), (x1, y1 - 18), (x1 - 16, y1), (x0 + 16, y1), (x0, y1 - 18)], 2), CREAM, shade=.25)
    I.face(80, y0 + 10, 176, y1 - 12, 6)
    stick(I, 56, y0 + 24, 8)
    stick(I, 200, y0 + 24, 8)
    dpad(I, 56, y0 + 60, 9)
    for i, (dx, dy) in enumerate(((0, -9), (9, 0), (-9, 0), (0, 9))):
        btn(I, 200 + dx, y0 + 60 + dy, 3.8, (CORAL, ORANGE, SAGE, CREAM)[i], 2.8)


def switch(I):
    x0, y0, x1, y1 = 30, 92, 226, 184
    I.limbs(x0, y0, x1, y1)
    I.antenna(182, y0 + 3, 196, y0 - 27)
    I.shape(soft([(x0 + 26, y0), (x0 + 40, y0), (x0 + 40, y1), (x0 + 26, y1), (x0, y1 - 22), (x0, y0 + 22)], 2), CORAL, shade=.3)
    I.shape(soft([(x1 - 40, y0), (x1 - 26, y0), (x1, y0 + 22), (x1, y1 - 22), (x1 - 26, y1), (x1 - 40, y1)], 2), SAGE, shade=.3)
    I.shape(rrect(x0 + 40, y0, x1 - 40, y1, 4), SLATE, shade=.3)
    I.face(x0 + 50, y0 + 8, x1 - 50, y1 - 8, 4, outline=False)
    stick(I, x0 + 22, y0 + 24, 7, SLATE)
    for i, (dx, dy) in enumerate(((0, -8), (8, 0), (-8, 0), (0, 8))):
        btn(I, x0 + 22 + dx, y0 + 58 + dy, 3.3, SLATE, 2.5)
        btn(I, x1 - 22 + dx, y0 + 30 + dy, 3.3, SLATE, 2.5)
    stick(I, x1 - 22, y0 + 62, 7, SLATE)


def psx(I):
    x0, y0, x1, y1 = 38, 102, 218, 188
    home_box(I, x0, y0, x1, y1, ant=(196, y0 + 4))
    I.shape(soft([(x0, y0), (x1, y0), (x1, y1), (x0, y1)], 1), CREAM, shade=.4)
    I.shape(ellipse(104, y0 + 22, 50, 15), SHADE[CREAM], 5, offset=False)
    I.shape(ellipse(104, y0 + 22, 12, 4), CREAM, 3, offset=False, amp=.4)
    for i, c in enumerate((SLATE, SLATE, CREAM)):
        btn(I, 172 + i * 16, y0 + 18, 5, c, 3)
    I.face(70, y0 + 44, 186, y1 - 6, 7)
    I.line([(x0 + 6, y0 + 40), (x1 - 6, y0 + 40)], INK, 3, smooth=False, double=False)


def ps2(I):
    x0, y0, x1, y1 = 64, 54, 192, 200
    I.limbs(x0, y0, x1, y1)
    I.antenna(x1 - 26, y0 + 3, x1 - 10, y0 - 26)
    I.shape(soft([(x0, y0), (x1, y0), (x1, y1), (x0, y1)], 1), SLATE, shade=.12)
    # the stepped grooves on its side
    for i in range(7):
        y = y0 + 12 + i * 8
        I.line([(x0 + 8, y), (x0 + 30, y)], INK, 3, smooth=False, double=False, amp=.4)
    I.shape(rrect(x1 - 30, y0 + 10, x1 - 10, y0 + 70, 4), SHADE[SLATE], 3.5, offset=False, amp=.5)
    I.face(x0 + 14, y0 + 78, x1 - 14, y1 - 18, 8, screen=INK)
    btn(I, x1 - 20, y1 - 9, 3.2, SAGE, 0)
    btn(I, x1 - 32, y1 - 9, 3.2, ORANGE, 0)


def ps3(I):
    x0, y0, x1, y1 = 36, 96, 220, 186
    I.limbs(x0, y0 + 20, x1, y1)
    I.antenna(184, y0 + 8, 198, y0 - 22)
    body = ellipse(128, y0 + 40, 92, 46, 180, 360, 24) + [(x1, y1 - 8), (x1 - 8, y1), (x0 + 8, y1), (x0, y1 - 8)]
    I.shape(body, SLATE, shade=.3)
    I.line([(x0 + 6, y0 + 46), (x1 - 6, y0 + 46)], SHADE[SLATE], 4, smooth=False, double=False)
    I.face(74, y0 + 52, 182, y1 - 8, 8)
    slot(I, 92, 164, y0 + 30)


def psp(I, fill=CREAM, vita=False):
    x0, y0, x1, y1 = 30, 96, 226, 176
    I.limbs(x0 + 10, y0, x1 - 10, y1)
    I.antenna(184, y0 + 3, 198, y0 - 27)
    r = 40 if vita else 34
    if vita:
        I.shape(ellipse(128, 136, 98, 42, n=48), fill, shade=.25)
    else:
        I.shape(rrect(x0, y0, x1, y1, r), fill, shade=.25)
    I.face(72, y0 + 10, 184, y1 - 10, 4 if not vita else 8)
    dpad(I, 51, 128 if not vita else 126, 9)
    for i, (dx, dy) in enumerate(((0, -9), (9, 0), (-9, 0), (0, 9))):
        btn(I, 205 + dx, (128 if not vita else 126) + dy, 3.8, (CORAL, ORANGE, SAGE, CREAM)[i], 2.8)
    if vita:
        stick(I, 56, 154, 6, INK)
        stick(I, 200, 154, 6, INK)
    else:
        stick(I, 50, 156, 5, SLATE)
        speaker(I, 196, 160, 4, 8)


def psvita(I):
    psp(I, SLATE, vita=True)


def xbox(I):
    x0, y0, x1, y1 = 36, 98, 220, 190
    home_box(I, x0, y0, x1, y1, ant=(194, y0 + 6))
    I.shape(soft([(x0 + 10, y0), (x1 - 10, y0), (x1, y0 + 14), (x1, y1), (x0, y1), (x0, y0 + 14)], 2), SLATE, shade=.35)
    I.shape(ellipse(128, y0 + 2, 26, 14), SHADE[SLATE], 5, offset=False)
    I.shape(ellipse(128, y0 + 2, 13, 7), SAGE, 3.5, offset=False, amp=.6)
    I.face(68, y0 + 26, 188, y1 - 12, 8)
    btn(I, 50, y1 - 18, 4, SAGE, 3)
    btn(I, 206, y1 - 18, 4, CREAM, 3)


def xbox360(I):
    x0, y0, x1, y1 = 78, 54, 178, 200
    I.limbs(x0 - 4, y0, x1 + 4, y1)
    I.antenna(x1 - 22, y0 + 3, x1 - 6, y0 - 26)
    body = [(x0, y0), (x1, y0), (x1 - 8, 128), (x1, y1), (x0, y1), (x0 + 8, 128)]
    I.shape(soft(body, 2), CREAM, shade=.15)
    I.face(x0 + 16, y0 + 16, x1 - 16, y0 + 74, 8)
    cy = y0 + 104
    I.shape(ellipse(128, cy, 16, 16), CREAM, 4.5, offset=False)
    for k in range(4):
        a0 = k * 90 + 8
        I.line(ellipse(128, cy, 16, 16, a0, a0 + 74, 6), SAGE, 4, smooth=False, double=False, amp=.3)
    btn(I, 128, cy, 6, SLATE, 3)
    slot(I, x0 + 22, x1 - 22, y1 - 22)


def dreamcast(I):
    x0, y0, x1, y1 = 48, 96, 208, 192
    home_box(I, x0, y0, x1, y1, ant=(186, y0 + 4))
    I.shape(soft([(x0 + 6, y0), (x1 - 6, y0), (x1, y1), (x0, y1)], 1), CREAM, shade=.25)
    I.shape(ellipse(128, y0 + 20, 54, 14), SHADE[CREAM], 5, offset=False)
    btn(I, 190, y0 + 22, 4, ORANGE, 3)
    btn(I, 66, y0 + 22, 4, SLATE, 3)
    I.face(80, y0 + 42, 176, y1 - 22, 7)
    for i in range(4):
        I.shape(rrect(70 + i * 31, y1 - 14, 92 + i * 31, y1 - 5, 3), SHADE[CREAM], 3, offset=False, amp=.4)


def saturn(I):
    x0, y0, x1, y1 = 38, 98, 218, 188
    home_box(I, x0, y0, x1, y1, ant=(200, y0 + 4))
    I.shape(soft([(x0, y0 + 10), (x0 + 12, y0), (x1 - 12, y0), (x1, y0 + 10), (x1, y1), (x0, y1)], 2), SLATE, shade=.4)
    I.shape(ellipse(100, y0 + 22, 52, 17), SHADE[SLATE], 5, offset=False)
    I.shape(ellipse(100, y0 + 22, 34, 10), SLATE, 3.5, offset=False, amp=.5)
    I.shape(ellipse(176, y0 + 16, 12, 6), SHADE[SLATE], 3.5, offset=False, amp=.4)
    I.shape(ellipse(196, y0 + 26, 9, 5), SHADE[SLATE], 3.5, offset=False, amp=.4)
    I.face(70, y0 + 46, 186, y1 - 8, 7)


def megadrive(I, y0=106):
    x0, x1, y1 = 34, 222, 188
    I.limbs(x0, y0, x1, y1)
    I.antenna(194, y0 + 4, 208, y0 - 26)
    I.shape(ellipse(118, y0 + 8, 52, 22, 180, 360, 20), SLATE, 5.5)
    I.shape(soft([(x0, y0 + 6), (x0 + 10, y0), (x1 - 10, y0), (x1, y0 + 6), (x1, y1), (x0, y1)], 2), SLATE, shade=.3)
    I.shape(ellipse(118, y0 + 8, 52, 14), SHADE[SLATE], 5, offset=False)
    slot(I, 92, 144, y0 + 6)
    I.shape(rrect(180, y0 + 8, 206, y0 + 18, 3), CORAL, 3.5, offset=False, amp=.5)
    I.face(74, y0 + 30, 182, y1 - 8, 7)


def segacd(I):
    x0, y0, x1, y1 = 34, 136, 222, 202
    I.limbs(x0, y0 - 40, x1, y1, arm_y=150)
    I.antenna(186, 100, 202, 70)
    # the console on top
    I.shape(soft([(56, 96), (200, 96), (204, 134), (52, 134)], 1), SLATE, shade=.3)
    I.shape(ellipse(128, 102, 40, 9), SHADE[SLATE], 4, offset=False)
    I.shape(rrect(170, 106, 192, 114, 3), CORAL, 3, offset=False, amp=.4)
    # the cd unit
    I.shape(soft([(x0, y0), (x1, y0), (x1, y1), (x0, y1)], 1), SLATE, shade=.25)
    I.face(78, y0 + 10, 178, y1 - 6, 6)
    I.line([(x0 + 8, y0 + 10), (x0 + 36, y0 + 10)], INK, 3.5, smooth=False, double=False)
    btn(I, x1 - 18, y0 + 14, 4, ORANGE, 3)


def mastersystem(I):
    x0, y0, x1, y1 = 34, 100, 222, 186
    home_box(I, x0, y0, x1, y1, ant=(200, y0 + 4))
    body = [(x0, y0 + 26), (x0 + 30, y0), (x1 - 50, y0), (x1 - 34, y0 + 18), (x1, y0 + 18), (x1, y1), (x0, y1)]
    I.shape(soft(body, 1), SLATE, shade=.3)
    I.line([(x0 + 4, y0 + 34), (x1 - 4, y0 + 34)], CORAL, 4.5, smooth=False, double=False)
    ridges(I, x0 + 40, x1 - 60, y0 + 6, y0 + 24, 6, sw=2.5)
    I.face(70, y0 + 42, 186, y1 - 8, 6)


def gamegear(I):
    x0, y0, x1, y1 = 28, 94, 228, 184
    I.limbs(x0 + 6, y0, x1 - 6, y1)
    I.antenna(186, y0 + 3, 200, y0 - 27)
    body = [(x0 + 30, y0), (x1 - 30, y0), (x1, y0 + 30), (x1 - 10, y1), (x0 + 10, y1), (x0, y0 + 30)]
    I.shape(soft(body, 2), SLATE, shade=.25)
    I.shape(rrect(76, y0 + 8, 180, y1 - 8, 8), INK, 4, offset=False, amp=.6)
    I.face(86, y0 + 14, 170, y1 - 16, 4, outline=False)
    dpad(I, 52, 140, 11, INK)
    btn(I, 200, 146, 7, CREAM)
    btn(I, 214, 130, 7, CREAM)
    btn(I, 196, 112, 3.5, CORAL, 2.5)


def pcengine(I):
    x0, y0, x1, y1 = 80, 112, 176, 190
    I.limbs(x0, y0, x1, y1, feet=(104, 152))
    I.antenna(x1 - 16, y0 + 4, x1 + 2, y0 - 26)
    # a card sticking out
    I.shape(soft([(98, y0 - 26), (134, y0 - 26), (134, y0 + 4), (98, y0 + 4)], 1), CORAL, 4.5)
    ridges(I, 104, 128, y0 - 22, y0 - 12, 4, sw=2)
    I.shape(rrect(x0, y0, x1, y1, 8), CREAM, shade=.25)
    I.face(x0 + 12, y0 + 12, x1 - 12, y1 - 18, 6)
    for i in range(5):
        I.line([(x0 + 18 + i * 5, y1 - 10), (x0 + 18 + i * 5, y1 - 6)], INK, 2.2, smooth=False, double=False, amp=.2)


def atari2600(I):
    x0, y0, x1, y1 = 34, 104, 222, 190
    home_box(I, x0, y0, x1, y1, ant=(200, y0 + 6))
    I.shape(soft([(x0 + 18, y0), (x1 - 18, y0), (x1, y0 + 22), (x1, y1), (x0, y1), (x0, y0 + 22)], 1), SLATE)
    ridges(I, x0 + 20, x1 - 20, y0 + 5, y0 + 18, 12, sw=2.5)
    # wooden front
    I.shape(soft([(x0 + 2, y0 + 26), (x1 - 2, y0 + 26), (x1 - 2, y1 - 2), (x0 + 2, y1 - 2)], 1), ORANGE, 4.5, offset=False)
    for i in range(3):
        y = y0 + 38 + i * 14
        I.line([(x0 + 8, y), (x0 + 28, y + 3), (x0 + 30, y - 2)], SHADE[ORANGE], 3, double=False, amp=.8)
        I.line([(x1 - 30, y + 2), (x1 - 10, y - 1)], SHADE[ORANGE], 3, double=False, amp=.8)
    I.face(74, y0 + 32, 182, y1 - 10, 6)
    for i in range(4):
        I.shape(rrect(52 + i * 14 + (i > 1) * 96, y0 - 10, 60 + i * 14 + (i > 1) * 96, y0 + 4, 2), INK, 0, offset=False, amp=.3)


def lynx(I):
    x0, y0, x1, y1 = 20, 102, 236, 180
    I.limbs(x0 + 8, y0, x1 - 8, y1, feet=(96, 160))
    I.antenna(196, y0 + 3, 210, y0 - 27)
    body = [(x0 + 10, y0 + 10), (60, y0), (196, y0), (x1 - 10, y0 + 10), (x1, y1 - 10), (x1 - 20, y1), (x0 + 20, y1), (x0, y1 - 10)]
    I.shape(soft(body, 2), SLATE, shade=.3)
    I.shape(rrect(74, y0 + 8, 182, y1 - 10, 6), INK, 4, offset=False, amp=.6)
    I.face(82, y0 + 14, 174, y1 - 18, 4, outline=False)
    dpad(I, 46, 140, 10, INK)
    btn(I, 204, 132, 6, CREAM)
    btn(I, 218, 150, 6, CREAM)
    for i in range(3):
        I.line([(x0 + 8, y0 + 30 + i * 12), (x0 + 14, y0 + 30 + i * 12)], INK, 2.5, smooth=False, double=False, amp=.2)


def ngp(I):
    x0, y0, x1, y1 = 52, 64, 204, 200
    I.limbs(x0, y0, x1, y1, arm_y=150)
    I.antenna(x1 - 30, y0 + 6, x1 - 14, y0 - 24)
    I.shape(soft([(x0 + 18, y0), (x1 - 18, y0), (x1, y0 + 30), (x1, y1 - 24), (x1 - 24, y1), (x0 + 24, y1), (x0, y1 - 24), (x0, y0 + 30)], 2), SAGE, shade=.2)
    I.shape(rrect(x0 + 16, y0 + 12, x1 - 16, y0 + 80, 8), SLATE, 4.5, offset=False, amp=.8)
    I.face(x0 + 28, y0 + 20, x1 - 28, y0 + 72, 4, outline=False)
    I.shape(ellipse(x0 + 38, y0 + 104, 17, 17), SHADE[SAGE], 4, offset=False)
    btn(I, x0 + 38, y0 + 104, 7, CREAM, 3)
    btn(I, x1 - 46, y0 + 110, 8, CREAM)
    btn(I, x1 - 26, y0 + 96, 8, CREAM)


def wonderswan(I):
    x0, y0, x1, y1 = 30, 92, 226, 182
    I.limbs(x0 + 4, y0, x1 - 4, y1)
    I.antenna(188, y0 + 3, 202, y0 - 27)
    I.shape(soft([(x0 + 12, y0), (x1 - 12, y0), (x1, y0 + 14), (x1, y1 - 12), (x1 - 14, y1), (x0 + 14, y1), (x0, y1 - 12), (x0, y0 + 14)], 2), ORANGE, shade=.25)
    I.shape(rrect(72, y0 + 8, 186, y1 - 10, 6), SLATE, 4.5, offset=False, amp=.7)
    I.face(82, y0 + 16, 176, y1 - 18, 4, outline=False)
    for cy in (118, 152):
        for dx, dy in ((0, -7), (7, 0), (-7, 0), (0, 7)):
            btn(I, 50 + dx, cy + dy, 3, SLATE, 2.2)
    btn(I, 202, 144, 6.5, CREAM)
    btn(I, 214, 128, 6.5, CREAM)


def arcade(I):
    x0, y0, x1, y1 = 70, 44, 186, 204
    I.limbs(x0, y0, x1, y1, arm_y=150)
    I.antenna(x1 - 10, y0 + 6, x1 + 10, y0 - 18)
    body = [(x0, y0), (x1, y0), (x1, 118), (x1 - 16, 128), (x1 - 4, 160), (x1, y1), (x0, y1), (x0 + 4, 160), (x0 + 16, 128), (x0, 118)]
    I.shape(soft(body, 1), SLATE, shade=.18)
    I.shape(rrect(x0 + 6, y0 + 6, x1 - 6, y0 + 26, 4), CORAL, 4, offset=False, amp=.6)
    for i in range(3):
        btn(I, 108 + i * 20, y0 + 16, 3, CREAM, 0)
    I.face(x0 + 18, y0 + 34, x1 - 18, 118, 6)
    I.shape(soft([(x0 + 4, 132), (x1 - 4, 132), (x1 + 2, 156), (x0 - 2, 156)], 1), SHADE[SLATE], 4.5, offset=False)
    I.line([(98, 146), (96, 132)], INK, 4, smooth=False, double=False)
    btn(I, 96, 128, 6, CORAL, 3.5)
    for i, c in enumerate((ORANGE, SAGE, CREAM)):
        btn(I, 128 + i * 15, 145, 4.5, c, 3)
    I.shape(rrect(108, 172, 148, 186, 3), INK, 0, offset=False, amp=.3)
    btn(I, 120, 179, 2.5, ORANGE, 0)
    btn(I, 136, 179, 2.5, ORANGE, 0)


CONSOLES = {
    "nes": nes, "snes": snes, "n64": n64, "gb": gb, "gbc": gbc, "gba": gba, "nds": nds, "n3ds": n3ds,
    "virtualboy": virtualboy, "gc": gc, "wii": wii, "wiiu": wiiu, "switch": switch,
    "psx": psx, "ps2": ps2, "ps3": ps3, "psp": psp, "psvita": psvita, "xbox": xbox, "xbox360": xbox360,
    "dreamcast": dreamcast, "saturn": saturn, "megadrive": megadrive, "segacd": segacd,
    "mastersystem": mastersystem, "gamegear": gamegear, "pcengine": pcengine, "atari2600": atari2600,
    "lynx": lynx, "ngp": ngp, "wonderswan": wonderswan, "arcade": arcade,
}


def main():
    os.makedirs(OUT, exist_ok=True)
    only = set(sys.argv[1:])
    for i, (cid, fn) in enumerate(CONSOLES.items()):
        if only and cid not in only:
            continue
        I = Icon(seed=i + 3)
        fn(I)
        I.sparkle()
        with open(os.path.join(OUT, f"{cid}.svg"), "w") as f:
            f.write(I.svg())
    print(f"{len(CONSOLES)} icons -> {os.path.normpath(OUT)}")


if __name__ == "__main__":
    main()

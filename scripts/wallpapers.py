#!/usr/bin/env python3
"""Draws the wallpaper scenes into design/wallpapers/scenes/*.svg.

Each scene is one intricate line drawing, 1000 x 1600 units, that the apps
scale to fill a chat (cropping what doesn't fit). Everything is drawn by
code from a fixed seed, so running this again gives the same drawings:

    scripts/wallpapers.py

Shapes with class="knockout" are painted in the paper colour first, then
outlined, so what's drawn later covers what's behind it, like cut paper.
"""

import math
import pathlib
import random

ROOT = pathlib.Path(__file__).resolve().parent.parent
OUT = ROOT / "design" / "wallpapers" / "scenes"
W, H = 1000, 1600


def fmt(points):
    return " ".join(f"{x:.1f} {y:.1f}" for x, y in points)


def smooth(points, closed=False):
    """A Catmull-Rom curve through the points, as cubic Béziers."""
    if len(points) < 3:
        return "M" + fmt(points[:1]) + " L" + fmt(points[1:])
    pts = points + points[:3] if closed else [points[0]] + points + [points[-1]]
    out = [f"M{pts[1][0]:.1f} {pts[1][1]:.1f}"]
    for i in range(1, len(pts) - 2):
        p0, p1, p2, p3 = pts[i - 1], pts[i], pts[i + 1], pts[i + 2]
        c1 = (p1[0] + (p2[0] - p0[0]) / 6, p1[1] + (p2[1] - p0[1]) / 6)
        c2 = (p2[0] - (p3[0] - p1[0]) / 6, p2[1] - (p3[1] - p1[1]) / 6)
        out.append(f"C{c1[0]:.1f} {c1[1]:.1f} {c2[0]:.1f} {c2[1]:.1f} {p2[0]:.1f} {p2[1]:.1f}")
    if closed:
        out.append("Z")
    return " ".join(out)


class Scene:
    def __init__(self):
        self.parts = []

    def line(self, d, width=1.6):
        self.parts.append(f'<path d="{d}" stroke-width="{width}"/>')

    def knockout(self, d, width=1.6):
        self.parts.append(f'<path class="knockout" d="{d}" stroke-width="{width}"/>')

    def dot(self, x, y, r=1.6):
        self.parts.append(f'<circle cx="{x:.1f}" cy="{y:.1f}" r="{r:.1f}" fill="currentColor" stroke="none"/>')

    def circle(self, x, y, r, width=1.6):
        self.parts.append(f'<circle cx="{x:.1f}" cy="{y:.1f}" r="{r:.1f}" stroke-width="{width}"/>')

    def svg(self, title):
        return (
            f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {W} {H}" fill="none" stroke="currentColor" '
            'stroke-linecap="round" stroke-linejoin="round">\n'
            f"  <title>{title}</title>\n  " + "\n  ".join(self.parts) + "\n</svg>\n"
        )


def ring(cx, cy, r, n, wobble, rng):
    return [
        (cx + (r + rng.uniform(-wobble, wobble)) * math.cos(2 * math.pi * i / n),
         cy + (r + rng.uniform(-wobble, wobble)) * math.sin(2 * math.pi * i / n))
        for i in range(n)
    ]


# ---------------------------------------------------------------- flowers

def petal(cx, cy, angle, inner, outer, width, rng, bend=0.0):
    """A petal from the centre outwards, as a closed curve, with a vein."""
    ax, ay = math.cos(angle), math.sin(angle)
    px, py = -ay, ax
    pts = []
    for t in (0.0, 0.35, 0.7, 0.92, 1.0, 0.92, 0.7, 0.35):
        r = inner + (outer - inner) * t
        side = 1 if len(pts) < 4 else -1
        w = width * math.sin(math.pi * min(t, 0.999)) ** 0.8 * (1 if t < 1 else 0)
        if len(pts) == 4:
            w = 0
        x = cx + ax * r + px * w * side + px * bend * t * t * width
        y = cy + ay * r + py * w * side + py * bend * t * t * width
        pts.append((x + rng.uniform(-0.6, 0.6), y + rng.uniform(-0.6, 0.6)))
    vein = [(cx + ax * (inner + 4), cy + ay * (inner + 4)), (cx + ax * (inner + (outer - inner) * 0.7) + px * bend * width * 0.4,
                                                            cy + ay * (inner + (outer - inner) * 0.7) + py * bend * width * 0.4)]
    return smooth(pts, closed=True), "M" + fmt(vein[:1]) + " L" + fmt(vein[1:])


def daisy(s, cx, cy, r, rng):
    n = rng.randint(14, 22)
    for i in range(n):
        a = 2 * math.pi * i / n + rng.uniform(-0.06, 0.06)
        body, vein = petal(cx, cy, a, r * 0.22, r * rng.uniform(0.85, 1.0), r * 0.14, rng, bend=rng.uniform(-0.4, 0.4))
        s.knockout(body, 1.4)
        s.line(vein, 0.9)
    s.knockout(smooth(ring(cx, cy, r * 0.24, 12, r * 0.015, rng), closed=True), 1.6)
    for _ in range(int(r * 0.6)):
        a, d = rng.uniform(0, 2 * math.pi), r * 0.2 * math.sqrt(rng.random())
        s.dot(cx + d * math.cos(a), cy + d * math.sin(a), 1.1)


def sunflower(s, cx, cy, r, rng):
    for layer, (inner, outer, width) in enumerate([(0.4, 1.0, 0.16), (0.36, 0.78, 0.15)]):
        n = 22 - layer * 4
        off = rng.uniform(0, 1)
        for i in range(n):
            a = 2 * math.pi * (i + off + layer * 0.5) / n
            body, vein = petal(cx, cy, a, r * inner, r * outer, r * width, rng, bend=rng.uniform(-0.6, 0.6))
            s.knockout(body, 1.4)
            s.line(vein, 0.9)
    s.knockout(smooth(ring(cx, cy, r * 0.4, 16, r * 0.02, rng), closed=True), 1.8)
    # A seed head in a golden-angle spiral.
    seeds = int(r * 2.2)
    for i in range(seeds):
        d = r * 0.37 * math.sqrt(i / seeds)
        a = i * 2.39996
        s.dot(cx + d * math.cos(a), cy + d * math.sin(a), 1.2 if i % 3 else 1.6)


def rose(s, cx, cy, r, rng):
    s.knockout(smooth(ring(cx, cy, r, 10, r * 0.08, rng), closed=True), 1.6)
    # Overlapping arcs spiralling in, like layered petals.
    turns, a = 3.2, rng.uniform(0, 2 * math.pi)
    steps = 60
    pts = []
    for i in range(steps):
        t = i / steps
        rr = r * (0.92 - 0.82 * t)
        ang = a + t * turns * 2 * math.pi
        pts.append((cx + rr * math.cos(ang) + rng.uniform(-1, 1), cy + rr * math.sin(ang) + rng.uniform(-1, 1)))
    for k in range(0, steps - 6, 6):
        s.line(smooth(pts[k:k + 8]), 1.3)
    for i in range(5):
        ang = a + i * 1.25
        s.line(smooth([(cx + r * 0.95 * math.cos(ang), cy + r * 0.95 * math.sin(ang)),
                       (cx + r * 0.7 * math.cos(ang + 0.35), cy + r * 0.7 * math.sin(ang + 0.35)),
                       (cx + r * 0.55 * math.cos(ang + 0.9), cy + r * 0.55 * math.sin(ang + 0.9))]), 1.1)


def blossom(s, cx, cy, r, rng):
    n = 5
    a0 = rng.uniform(0, 2 * math.pi)
    for i in range(n):
        a = a0 + 2 * math.pi * i / n
        pts = []
        for t in range(9):
            u = t / 8
            ang = a + (u - 0.5) * 1.2
            rr = r * (0.15 + 0.85 * math.sin(math.pi * u) ** 0.6) * (1 - 0.12 * abs(math.cos(ang * 3)))
            pts.append((cx + rr * math.cos(ang), cy + rr * math.sin(ang)))
        pts = [(cx, cy)] + pts[1:-1]
        s.knockout(smooth(pts, closed=True), 1.5)
        for k in (-0.18, 0, 0.18):
            s.line(f"M{cx + r * 0.12 * math.cos(a):.1f} {cy + r * 0.12 * math.sin(a):.1f} "
                   f"L{cx + r * 0.6 * math.cos(a + k):.1f} {cy + r * 0.6 * math.sin(a + k):.1f}", 0.9)
    s.knockout(smooth(ring(cx, cy, r * 0.1, 8, 0.4, rng), closed=True), 1.4)
    for i in range(8):
        a = a0 + 2 * math.pi * i / 8 + 0.2
        s.dot(cx + r * 0.2 * math.cos(a), cy + r * 0.2 * math.sin(a), 1.3)


def lace(s, cx, cy, r, rng):
    """Queen Anne's lace: stems from one point to small clusters of dots."""
    base = (cx, cy + r * 0.9)
    s.line(smooth([(cx + rng.uniform(-6, 6), cy + r * 2.2), (cx, cy + r * 1.5), base]), 1.3)
    for i in range(11):
        a = -math.pi * (0.12 + 0.76 * i / 10)
        tip = (cx + r * 0.95 * math.cos(a), cy + r * 0.25 + r * 0.75 * math.sin(a))
        s.line("M" + fmt([base]) + " L" + fmt([tip]), 0.9)
        for j in range(6):
            b = -math.pi * (0.1 + 0.8 * j / 5)
            ex, ey = tip[0] + r * 0.18 * math.cos(b), tip[1] + r * 0.18 * math.sin(b)
            s.line("M" + fmt([tip]) + " L" + fmt([(ex, ey)]), 0.7)
            s.dot(ex, ey, 1.4)


def leaf(s, cx, cy, length, angle, rng):
    ax, ay = math.cos(angle), math.sin(angle)
    px, py = -ay, ax
    w = length * 0.28
    pts = []
    for t in (0, 0.2, 0.45, 0.7, 0.9, 1.0):
        pts.append((cx + ax * length * t + px * w * math.sin(math.pi * t) ** 0.9, cy + ay * length * t + py * w * math.sin(math.pi * t) ** 0.9))
    for t in (0.9, 0.7, 0.45, 0.2):
        pts.append((cx + ax * length * t - px * w * math.sin(math.pi * t) ** 0.9, cy + ay * length * t - py * w * math.sin(math.pi * t) ** 0.9))
    s.knockout(smooth(pts, closed=True), 1.4)
    s.line(f"M{cx:.1f} {cy:.1f} L{cx + ax * length * 0.92:.1f} {cy + ay * length * 0.92:.1f}", 1.0)
    for t in (0.25, 0.45, 0.65):
        for side in (1, -1):
            x0, y0 = cx + ax * length * t, cy + ay * length * t
            s.line(f"M{x0:.1f} {y0:.1f} Q{x0 + ax * length * 0.08 + px * side * w * 0.4:.1f} {y0 + ay * length * 0.08 + py * side * w * 0.4:.1f} "
                   f"{x0 + ax * length * 0.16 + px * side * w * 0.75:.1f} {y0 + ay * length * 0.16 + py * side * w * 0.75:.1f}", 0.8)


def flowers():
    rng = random.Random(7)
    s = Scene()
    placed = []
    kinds = [daisy, daisy, sunflower, rose, blossom, blossom, rose, daisy]
    # Back to front: leaves and lace first, then flowers, each covering what's behind.
    for _ in range(70):
        leaf(s, rng.uniform(-40, W + 40), rng.uniform(-40, H + 40), rng.uniform(60, 120), rng.uniform(0, 2 * math.pi), rng)
    for _ in range(18):
        lace(s, rng.uniform(0, W), rng.uniform(0, H), rng.uniform(40, 70), rng)
    tries = 0
    while tries < 6000 and len(placed) < 150:
        tries += 1
        r = rng.choice([rng.uniform(70, 120), rng.uniform(40, 75), rng.uniform(28, 45)])
        x, y = rng.uniform(-30, W + 30), rng.uniform(-30, H + 30)
        # Let flowers overlap a little, like cut-outs heaped together.
        if any(math.hypot(x - px, y - py) < (r + pr) * 0.62 for px, py, pr in placed):
            continue
        placed.append((x, y, r))
    for x, y, r in placed:
        rng.choice(kinds)(s, x, y, r, rng)
        if rng.random() < 0.25:
            leaf(s, x + r * 0.6, y + r * 0.4, r * 0.8, rng.uniform(0, 2 * math.pi), rng)
    return s


# ------------------------------------------------------------ starry night

def starry():
    rng = random.Random(11)
    s = Scene()
    sky_bottom = H * 0.62
    vortices = [(W * 0.38, H * 0.2, 1.0, 210), (W * 0.72, H * 0.33, -0.8, 160), (W * 0.18, H * 0.42, 0.7, 140), (W * 0.85, H * 0.1, -0.6, 120)]

    def field(x, y):
        vx, vy = 1.0, 0.18 * math.sin(x / 140 + y / 90)
        for cx, cy, strength, radius in vortices:
            dx, dy = x - cx, y - cy
            d2 = dx * dx + dy * dy
            k = strength * math.exp(-d2 / (2 * radius * radius)) * 2.4
            vx += -dy / math.sqrt(d2 + 1) * k
            vy += dx / math.sqrt(d2 + 1) * k
        n = math.hypot(vx, vy) or 1
        return vx / n, vy / n

    # Evenly spaced streamlines (a grid keeps lines from crowding).
    gap, cell = 13.0, 6.5
    grid = {}

    def free(x, y, d):
        gx, gy = int(x / cell), int(y / cell)
        r = int(d / cell) + 1
        for i in range(gx - r, gx + r + 1):
            for j in range(gy - r, gy + r + 1):
                for (px, py) in grid.get((i, j), ()):
                    if (px - x) ** 2 + (py - y) ** 2 < d * d:
                        return False
        return True

    stars = [(W * 0.12, H * 0.07, 46), (W * 0.55, H * 0.06, 34), (W * 0.9, H * 0.24, 52), (W * 0.3, H * 0.33, 30),
             (W * 0.62, H * 0.46, 40), (W * 0.08, H * 0.27, 28), (W * 0.46, H * 0.52, 24)]
    moon = (W * 0.84, H * 0.07, 70)

    def in_body(x, y):
        if y > sky_bottom:
            return True
        for sx, sy, sr in stars + [moon]:
            if math.hypot(x - sx, y - sy) < sr * 1.25:
                return True
        return False

    seeds = [(rng.uniform(0, W), rng.uniform(0, sky_bottom)) for _ in range(5000)]
    for sx, sy in seeds:
        if in_body(sx, sy) or not free(sx, sy, gap):
            continue
        line = [(sx, sy)]
        for direction in (1, -1):
            x, y = sx, sy
            pts = []
            for _ in range(260):
                vx, vy = field(x, y)
                x, y = x + direction * vx * 4, y + direction * vy * 4
                if not (-20 < x < W + 20 and -20 < y < sky_bottom) or in_body(x, y) or not free(x, y, gap * 0.55):
                    break
                pts.append((x, y))
            line = (pts[::-1] + line) if direction == -1 else (line + pts)
        if len(line) < 12:
            continue
        for x, y in line:
            grid.setdefault((int(x / cell), int(y / cell)), []).append((x, y))
        s.line(smooth(line[::2]), 1.5)

    # Stars: rings inside rings, and a halo of dashes.
    for sx, sy, sr in stars:
        for k in range(4):
            s.circle(sx, sy, sr * (0.25 + 0.22 * k), 1.6 if k % 2 == 0 else 1.1)
        s.dot(sx, sy, sr * 0.12)
        n = int(sr * 0.7)
        for i in range(n):
            a = 2 * math.pi * i / n
            r0, r1 = sr * 1.02, sr * 1.18
            s.line(f"M{sx + r0 * math.cos(a):.1f} {sy + r0 * math.sin(a):.1f} L{sx + r1 * math.cos(a):.1f} {sy + r1 * math.sin(a):.1f}", 1.4)
    mx, my, mr = moon
    s.line(f"M{mx - mr * 0.2:.1f} {my - mr:.1f} A{mr:.1f} {mr:.1f} 0 1 0 {mx + mr:.1f} {my + mr * 0.3:.1f} "
           f"A{mr * 0.8:.1f} {mr * 0.8:.1f} 0 1 1 {mx - mr * 0.2:.1f} {my - mr:.1f} Z", 2.0)
    for k in range(1, 4):
        s.circle(mx, my, mr * (1 + 0.16 * k), 1.0)

    # Rolling hills down to the bottom, each a band of flowing lines.
    hill_tops = []
    for layer in range(6):
        base = sky_bottom + 40 + layer * 140
        phase = rng.uniform(0, 6)
        top = [(x, base + 45 * math.sin(x / (220 + layer * 35) + phase) + 20 * math.sin(x / 73 + phase * 2)) for x in range(-20, W + 40, 20)]
        hill_tops.append(top)
    for layer, top in enumerate(hill_tops):
        nxt = hill_tops[layer + 1] if layer + 1 < len(hill_tops) else [(x, H + 40) for x, _ in top]
        s.knockout(smooth(top + [(x, y) for x, y in reversed(nxt)], closed=False) + " Z", 1.8)
        for k in range(1, 9):
            u = k / 9
            band = [(x, y0 + (y1 - y0) * u + 6 * math.sin(x / 50 + k + layer)) for (x, y0), (_, y1) in zip(top, nxt)]
            s.line(smooth(band), 1.0)
        # Round bushes along the ridge.
        for _ in range(5):
            i = rng.randrange(2, len(top) - 2)
            bx, by = top[i]
            br = rng.uniform(14, 26)
            s.knockout(smooth(ring(bx, by - br * 0.6, br, 10, br * 0.15, rng), closed=True), 1.4)
            for k in range(3):
                s.line(smooth(ring(bx, by - br * 0.6, br * (0.3 + 0.2 * k), 8, br * 0.1, rng), closed=True), 0.8)

    # The village, nestled on the second hill: houses, windows, a church.
    rng2 = random.Random(3)
    ridge = hill_tops[1]
    def ridge_y(x):
        i = max(0, min(len(ridge) - 1, int((x + 20) / 20)))
        return ridge[i][1]
    x = 330
    while x < W - 30:
        w = rng2.uniform(70, 120)
        h = rng2.uniform(60, 100)
        y0 = ridge_y(x + w / 2) + rng2.uniform(30, 70)
        s.knockout(f"M{x:.1f} {y0:.1f} L{x + w:.1f} {y0:.1f} L{x + w:.1f} {y0 - h:.1f} L{x + w / 2:.1f} {y0 - h - w * 0.45:.1f} L{x:.1f} {y0 - h:.1f} Z", 1.7)
        s.line(f"M{x + w * 0.15:.1f} {y0 - h - w * 0.1:.1f} L{x + w / 2:.1f} {y0 - h - w * 0.36:.1f} L{x + w * 0.85:.1f} {y0 - h - w * 0.1:.1f}", 0.9)
        for row in range(int(h // 32)):
            for col in range(int(w // 36)):
                wx, wy = x + 14 + col * 36, y0 - h + 16 + row * 32
                s.line(f"M{wx:.1f} {wy:.1f} h13 v15 h-13 Z M{wx + 6.5:.1f} {wy:.1f} v15 M{wx:.1f} {wy + 7.5:.1f} h13", 1.0)
        s.line(f"M{x + w * 0.4:.1f} {y0:.1f} v-24 h{w * 0.2:.1f} v24", 1.2)
        x += w + rng2.uniform(-15, 15)
    cx = 600
    gy = ridge_y(cx) + 60
    s.knockout(f"M{cx - 45} {gy} L{cx + 45} {gy} L{cx + 45} {gy - 130} L{cx} {gy - 190} L{cx - 45} {gy - 130} Z", 1.9)
    s.knockout(f"M{cx - 14} {gy - 190} L{cx} {gy - 380} L{cx + 14} {gy - 190} Z", 1.9)
    s.line(f"M{cx} {gy - 380} L{cx} {gy - 405} M{cx - 8} {gy - 395} L{cx + 8} {gy - 395} M{cx - 12} {gy - 60} v40 h24 v-40 a12 12 0 0 0 -24 0", 1.5)

    # The cypress: a tall flame, its outline full of curling tongues.
    base_x, base_y, height = 200, H + 20, 1150
    def half_width(u):
        return 120 * (1 - u) ** 0.75 * (1 + 0.12 * math.sin(u * 23))
    outline_l = [(base_x - half_width(i / 30) + 10 * math.sin(i * 1.7), base_y - height * i / 30) for i in range(31)]
    outline_r = [(base_x + half_width(i / 30) + 10 * math.sin(i * 1.3 + 1), base_y - height * i / 30) for i in range(31)]
    s.knockout(smooth(outline_l + list(reversed(outline_r)), closed=True), 2.2)
    rng3 = random.Random(5)
    for _ in range(170):
        u = rng3.uniform(0, 0.95)
        y = base_y - height * u
        hw = half_width(u) * 0.85
        x = base_x + rng3.uniform(-hw, hw)
        length = rng3.uniform(60, 150) * (1 - u * 0.6)
        sway = rng3.uniform(-1, 1)
        pts = []
        for k in range(7):
            v = k / 6
            yy = y - length * v
            uu = min(0.99, (base_y - yy) / height)
            lim = half_width(uu) * 0.9
            xx = x + sway * 22 * math.sin(v * math.pi) + 8 * math.sin(v * 9 + x)
            pts.append((max(base_x - lim, min(base_x + lim, xx)), yy))
        s.line(smooth(pts), 1.5)
    return s


SCENES = [
    ("valley-of-flowers", "Valley of flowers", flowers),
    ("starry-night", "Starry night", starry),
]


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    order = []
    for name, title, draw in SCENES:
        scene = draw()
        (OUT / f"{name}.svg").write_text(scene.svg(title))
        order.append(f"{name}\t{title}")
        print(f"{name}: {len(scene.parts)} shapes")
    (OUT / "order.txt").write_text("\n".join(order) + "\n")


if __name__ == "__main__":
    main()

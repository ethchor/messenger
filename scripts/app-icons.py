#!/usr/bin/env python3
"""Draws the app icons from design/artwork/mark.svg: the Q, an off-white ring
with a coral tail across it, on near-black.

    scripts/app-icons.py

Writes the iPhone icons (light, dark and tinted) and the Mac icons as PNGs in
the asset catalog, and Android's adaptive launcher icon and status bar icon as
vector drawables. Needs `pip install svgelements cairosvg pillow`.
"""

import io
import json
import pathlib

import cairosvg
from PIL import Image
from svgelements import SVG, Circle, SimpleLine

ROOT = pathlib.Path(__file__).resolve().parent.parent
APPLE = ROOT / "apple" / "Messenger" / "Assets.xcassets" / "AppIcon.appiconset"
RES = ROOT / "android" / "app" / "src" / "main" / "res"

BACKGROUND = "#10100E"
RING = "#EBE8E2"
TAIL = "#EC7B53"


def number(value):
    text = f"{value:.3f}".rstrip("0").rstrip(".")
    return "0" if text in ("-0", "") else text


def load_mark():
    """The ring and the tail, in the drawing's units, and the box around both."""
    svg = SVG.parse(str(ROOT / "design" / "artwork" / "mark.svg"))
    ring = tail = None
    for element in svg.elements():
        classes = (element.values.get("class") or "").split()
        if isinstance(element, Circle) and "ring" in classes:
            ring = (element.cx, element.cy, element.rx, float(element.stroke_width))
        elif isinstance(element, SimpleLine) and "tail" in classes:
            tail = (element.x1, element.y1, element.x2, element.y2, float(element.stroke_width))
    assert ring and tail, "mark.svg needs a circle.ring and a line.tail"
    cx, cy, r, w = ring
    x1, y1, x2, y2, t = tail
    left = min(cx - r - w / 2, x1 - t / 2, x2 - t / 2)
    top = min(cy - r - w / 2, y1 - t / 2, y2 - t / 2)
    right = max(cx + r + w / 2, x1 + t / 2, x2 + t / 2)
    bottom = max(cy + r + w / 2, y1 + t / 2, y2 + t / 2)
    return {"ring": ring, "tail": tail, "box": (left, top, right, bottom)}


MARK = load_mark()


def fit(size, fraction, origin=(0, 0)):
    """Scale and offset that make the mark `fraction` of `size` wide, centred
    in the square of that size at `origin`."""
    left, top, right, bottom = MARK["box"]
    scale = size * fraction / (right - left)
    x = origin[0] + (size - (right - left) * scale) / 2 - left * scale
    y = origin[1] + (size - (bottom - top) * scale) / 2 - top * scale
    return scale, (x, y)


def mark_elements(scale, offset, ring=RING, tail=TAIL):
    cx, cy, r, w = MARK["ring"]
    x1, y1, x2, y2, t = MARK["tail"]
    return (
        f'<g transform="translate({number(offset[0])} {number(offset[1])}) scale({number(scale)})" fill="none">'
        f'<circle cx="{cx}" cy="{cy}" r="{r}" stroke="{ring}" stroke-width="{w}"/>'
        f'<line x1="{x1}" y1="{y1}" x2="{x2}" y2="{y2}" stroke="{tail}" stroke-width="{t}" stroke-linecap="round"/>'
        "</g>"
    )


def png(size, *parts):
    svg = (
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{size}" height="{size}" '
        f'viewBox="0 0 {size} {size}">{"".join(parts)}</svg>'
    )
    return Image.open(io.BytesIO(cairosvg.svg2png(bytestring=svg.encode(), output_width=size, output_height=size)))


def squircle(x, y, size, radius):
    """Path data for a rounded square with Apple's continuous corners."""
    r = min(radius, size / 2 / 1.52866483)
    corner = [
        ("L", (1.52866471, 0)),
        ("C", (1.08849323, 0), (0.86840689, 0), (0.66993427, 0.06549600)),
        ("L", (0.63149399, 0.07491100)),
        ("C", (0.37282392, 0.16905899), (0.16906013, 0.37282401), (0.07491176, 0.63149399)),
        ("C", (0, 0.86840701), (0, 1.08849299), (0, 1.52866483)),
    ]
    right, bottom = x + size, y + size
    # Each corner maps (along the edge, into the corner) to a point.
    corners = [
        lambda a, b: (right - a * r, y + b * r),
        lambda a, b: (right - b * r, bottom - a * r),
        lambda a, b: (x + a * r, bottom - b * r),
        lambda a, b: (x + b * r, y + a * r),
    ]
    out = [f"M{number(x + 1.52866483 * r)} {number(y)}"]
    for place in corners:
        for command, *points in corner:
            coords = " ".join(f"{number(px)} {number(py)}" for px, py in (place(a, b) for a, b in points))
            out.append(command + coords)
    out.append("Z")
    return " ".join(out)


def iphone():
    # Full-bleed squares: iOS rounds the corners itself.
    scale, offset = fit(1024, 0.56)
    background = f'<rect width="1024" height="1024" fill="{BACKGROUND}"/>'
    png(1024, background, mark_elements(scale, offset)).convert("RGB").save(APPLE / "icon-ios-1024.png")
    # Dark: no background, so the system puts its own dark behind the mark.
    png(1024, mark_elements(scale, offset)).save(APPLE / "icon-ios-dark-1024.png")
    # Tinted: shades of grey that the system colours; the tail stays apart by
    # being darker than the ring.
    black = '<rect width="1024" height="1024" fill="#000000"/>'
    png(1024, black, mark_elements(scale, offset, ring="#FFFFFF", tail="#9A9A9A")).convert("L").save(
        APPLE / "icon-ios-tinted-1024.png"
    )


def mac():
    # Apple's Mac grid: an 824-point rounded square in a 1024 canvas. No drop
    # shadow, so the shape is exactly the system's and macOS 26 shows it as is.
    canvas, body, inset = 1024, 824, 100
    scale, offset = fit(body, 0.56, origin=(inset, inset))
    art = png(
        canvas,
        f'<path d="{squircle(inset, inset, body, 185.4)}" fill="{BACKGROUND}"/>',
        mark_elements(scale, offset),
    ).convert("RGBA")
    for points in (16, 32, 128, 256, 512):
        for factor in (1, 2):
            pixels = points * factor
            art.resize((pixels, pixels), Image.LANCZOS).save(APPLE / f"icon-mac-{points}@{factor}x.png")


def contents():
    images = [
        {"filename": "icon-ios-1024.png", "idiom": "universal", "platform": "ios", "size": "1024x1024"},
        {
            "appearances": [{"appearance": "luminosity", "value": "dark"}],
            "filename": "icon-ios-dark-1024.png",
            "idiom": "universal",
            "platform": "ios",
            "size": "1024x1024",
        },
        {
            "appearances": [{"appearance": "luminosity", "value": "tinted"}],
            "filename": "icon-ios-tinted-1024.png",
            "idiom": "universal",
            "platform": "ios",
            "size": "1024x1024",
        },
    ]
    for points in (16, 32, 128, 256, 512):
        for factor in (1, 2):
            images.append(
                {
                    "filename": f"icon-mac-{points}@{factor}x.png",
                    "idiom": "mac",
                    "scale": f"{factor}x",
                    "size": f"{points}x{points}",
                }
            )
    data = {"images": images, "info": {"author": "xcode", "version": 1}}
    (APPLE / "Contents.json").write_text(json.dumps(data, indent=2) + "\n")


def vector(name, size_dp, scale, offset, ring, tail, comment):
    cx, cy, r, w = MARK["ring"]
    x1, y1, x2, y2, t = MARK["tail"]
    circle = f"M{number(cx - r)},{number(cy)} a{number(r)},{number(r)} 0 1,0 {number(2 * r)},0 a{number(r)},{number(r)} 0 1,0 {number(-2 * r)},0"
    xml = (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        f"<!-- {comment} Generated by scripts/app-icons.py. -->\n"
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
        f'    android:width="{size_dp}dp"\n    android:height="{size_dp}dp"\n'
        f'    android:viewportWidth="{size_dp}"\n    android:viewportHeight="{size_dp}">\n'
        f'    <group android:translateX="{number(offset[0])}" android:translateY="{number(offset[1])}"\n'
        f'        android:scaleX="{number(scale)}" android:scaleY="{number(scale)}">\n'
        f'        <path android:pathData="{circle}" android:fillColor="#00000000"\n'
        f'            android:strokeColor="{ring}" android:strokeWidth="{number(w)}" />\n'
        f'        <path android:pathData="M{number(x1)},{number(y1)} L{number(x2)},{number(y2)}" android:fillColor="#00000000"\n'
        f'            android:strokeColor="{tail}" android:strokeWidth="{number(t)}" android:strokeLineCap="round" />\n'
        "    </group>\n</vector>\n"
    )
    (RES / "drawable" / f"{name}.xml").write_text(xml)


def android():
    # Adaptive icons are 108dp; launchers show about the middle 72dp, and the
    # middle 66dp always. The mark takes the same share of it as on iPhone.
    scale, offset = fit(108, 0.56 * 72 / 108)
    vector("ic_launcher_foreground", 108, scale, offset, RING, TAIL, "Launcher icon: the Q.")
    vector("ic_launcher_monochrome", 108, scale, offset, "#FFFFFFFF", "#FFFFFFFF", "Themed launcher icon.")
    scale, offset = fit(24, 0.84)
    vector("ic_notification", 24, scale, offset, "#FFFFFFFF", "#FFFFFFFF", "Status bar icon for new messages: the Q.")
    adaptive = (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
        '    <background android:drawable="@color/ic_launcher_background" />\n'
        '    <foreground android:drawable="@drawable/ic_launcher_foreground" />\n'
        '    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />\n'
        "</adaptive-icon>\n"
    )
    anydpi = RES / "mipmap-anydpi-v26"
    anydpi.mkdir(exist_ok=True)
    for name in ("ic_launcher", "ic_launcher_round"):
        (anydpi / f"{name}.xml").write_text(adaptive)
    (RES / "values" / "ic_launcher_background.xml").write_text(
        '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n'
        f'    <color name="ic_launcher_background">{BACKGROUND}</color>\n</resources>\n'
    )
    # Bitmaps for anything that can't draw the adaptive icon.
    scale, offset = fit(192, 0.56)
    for radius, name in ((36, "ic_launcher"), (96, "ic_launcher_round")):
        icon = png(192, f'<rect width="192" height="192" rx="{radius}" fill="{BACKGROUND}"/>', mark_elements(scale, offset))
        for folder, pixels in (("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)):
            icon.resize((pixels, pixels), Image.LANCZOS).save(RES / f"mipmap-{folder}" / f"{name}.png")


if __name__ == "__main__":
    iphone()
    mac()
    contents()
    android()
    print("icons written")

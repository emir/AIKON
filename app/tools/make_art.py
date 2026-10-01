#!/usr/bin/env python3
"""
Draws the app's spark (same geometry as Logo.java) with Pillow.

  make_art.py icon OUT.png          46x48 MIDlet icon (transparent), packaged in the JAR
  make_art.py logo OUT.png [SIZE]   large logo for README / social posts

Supersampled 8x and downscaled for smooth edges. Deterministic output
(no timestamps in the PNG).
"""
import math
import sys

from PIL import Image, ImageDraw

SPARK = (0xD9, 0x77, 0x57, 255)
RAYS = [100, 82, 94, 78, 100, 86, 92, 80, 98, 84, 90, 79]   # Logo.RAYS


def spark(draw, cx, cy, r, color=SPARK):
    """Same geometry as Logo.draw(): tapered rays with rounded tips + core."""
    n = len(RAYS)
    base = max(1.0, r * 0.17)
    tip = max(1.0, r * 0.09)
    for i, pct in enumerate(RAYS):
        a = math.radians(i * 360 / n)
        ca, sa = math.cos(a), math.sin(a)
        ln = r * pct / 100 - tip / 2
        px, py = -sa, ca
        pts = [(cx + px * base / 2, cy + py * base / 2),
               (cx + ca * ln + px * tip / 2, cy + sa * ln + py * tip / 2),
               (cx + ca * ln - px * tip / 2, cy + sa * ln - py * tip / 2),
               (cx - px * base / 2, cy - py * base / 2)]
        draw.polygon(pts, fill=color)
        ex, ey = cx + ca * ln, cy + sa * ln
        draw.ellipse([ex - tip / 2, ey - tip / 2, ex + tip / 2, ey + tip / 2], fill=color)
    core = max(2, r * 0.24)
    draw.ellipse([cx - core, cy - core, cx + core, cy + core], fill=color)


def render(w, h, radius_frac, bg=(0, 0, 0, 0), ss=8):
    big = Image.new("RGBA", (w * ss, h * ss), bg)
    d = ImageDraw.Draw(big)
    spark(d, w * ss / 2, h * ss / 2, min(w, h) * ss * radius_frac)
    return big.resize((w, h), Image.LANCZOS)


def main():
    kind, out = sys.argv[1], sys.argv[2]
    if kind == "icon":
        img = render(46, 48, 0.47)
    elif kind == "logo":
        size = int(sys.argv[3]) if len(sys.argv) > 3 else 512
        img = render(size, size, 0.46)
    else:
        sys.exit("usage: make_art.py icon|logo OUT [SIZE]")
    img.save(out, optimize=False)


if __name__ == "__main__":
    main()

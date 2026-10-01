#!/usr/bin/env python3
"""
Draws the AIKON mark (same geometry as Logo.java: a speech bubble holding a
3x3 keypad) with Pillow.

  make_art.py icon OUT.png          46x48 MIDlet icon (transparent), packaged in the JAR
  make_art.py logo OUT.png [SIZE]   large logo for README / social posts

Supersampled 8x and downscaled for smooth edges. Deterministic output
(no timestamps in the PNG).
"""
import sys

from PIL import Image, ImageDraw

SPARK = (0xD9, 0x77, 0x57, 255)   # Theme.spark (light palette)
KEY = (0xFB, 0xF3, 0xEE, 255)     # Logo.KEY


def mark(draw, cx, cy, s, color=SPARK, key=KEY):
    """Same geometry as Logo.draw(): rounded bubble, tail bottom left, 3x3 keys."""
    w, h = s, s * 0.80
    x0, y0 = cx - w / 2, cy - h / 2 - s * 0.06
    draw.rounded_rectangle([x0, y0, x0 + w, y0 + h], radius=s * 0.24, fill=color)
    tx, bottom = x0 + w * 0.20, y0 + h - 1
    draw.polygon([(tx, bottom), (tx + w * 0.24, bottom), (x0 + w * 0.10, bottom + s * 0.20)], fill=color)
    pad_x, pad_y = w * 0.25, h * 0.24
    gx, gy = (w - 2 * pad_x) / 2, (h - 2 * pad_y) / 2
    r = s * 0.065
    for i in range(9):
        px, py = x0 + pad_x + (i % 3) * gx, y0 + pad_y + (i // 3) * gy
        draw.ellipse([px - r, py - r, px + r, py + r], fill=key)


def render(w, h, radius_frac, bg=(0, 0, 0, 0), ss=8):
    """radius_frac: the mark's width as twice this fraction of the smaller side (as for the old spark)."""
    big = Image.new("RGBA", (w * ss, h * ss), bg)
    d = ImageDraw.Draw(big)
    mark(d, w * ss / 2, h * ss / 2, min(w, h) * ss * radius_frac * 2)
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

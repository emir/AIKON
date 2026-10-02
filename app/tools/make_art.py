#!/usr/bin/env python3
"""
Draws the AIKON mark (same geometry as Logo.java: a white "AK" monogram on a
blue rounded square) with Pillow.

  make_art.py icon OUT.png          46x48 MIDlet icon (transparent), packaged in the JAR
  make_art.py logo OUT.png [SIZE]   large logo for README / social posts

Supersampled 8x and downscaled for smooth edges. Deterministic output: the
PNG is written here with Python's own zlib (level 9), not by Pillow, whose
builds compress with different zlib code on different platforms (same
pixels, other bytes: the JAR's checksum differed between a Mac and GitHub's
Linux). zlib 1.2.12 (macOS) and 1.3 (Ubuntu 24.04) give the same bytes.
"""
import struct
import sys
import zlib

from PIL import Image, ImageDraw

BLUE = (0x07, 0x40, 0xDE, 255)    # Logo.BLUE
WHITE = (255, 255, 255, 255)
TILE_H, RADIUS = 969, 190         # per 1000 of the width, as in Logo.java
STROKES = [                       # polygons, per 1000 of the width (Logo.java)
    [(41, 700), (174, 700), (474, 396), (474, 252)],               # A diagonal
    [(381, 513), (474, 513), (474, 700), (381, 700)],              # short bar
    [(494, 251), (587, 251), (587, 700), (494, 700)],              # stem
    [(794, 251), (932, 251), (680, 496), (879, 700), (733, 700),   # K arms
     (605, 575), (605, 435)],
]


def mark(draw, cx, cy, s, color=BLUE, ink=WHITE):
    """Same geometry as Logo.draw(): s is the tile's width."""
    th = s * TILE_H / 1000
    x0, y0 = cx - s / 2, cy - th / 2
    draw.rounded_rectangle([x0, y0, x0 + s, y0 + th], radius=s * RADIUS / 1000, fill=color)
    for poly in STROKES:
        draw.polygon([(x0 + x * s / 1000, y0 + y * s / 1000) for x, y in poly], fill=ink)


def render(w, h, radius_frac, bg=(0, 0, 0, 0), ss=8):
    """radius_frac: the mark's width as twice this fraction of the smaller side (as for the old spark)."""
    big = Image.new("RGBA", (w * ss, h * ss), bg)
    d = ImageDraw.Draw(big)
    mark(d, w * ss / 2, h * ss / 2, min(w, h) * ss * radius_frac * 2)
    return big.resize((w, h), Image.LANCZOS)


def write_png(img, out):
    """An RGBA PNG with no ancillary chunks, image data compressed by zlib."""
    img = img.convert("RGBA")
    w, h = img.size
    px = img.tobytes()
    raw = b"".join(b"\x00" + px[y * w * 4:(y + 1) * w * 4] for y in range(h))  # filter 0 per row

    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)

    with open(out, "wb") as f:
        f.write(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0))
                + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))


def main():
    kind, out = sys.argv[1], sys.argv[2]
    if kind == "icon":
        img = render(46, 48, 0.5)
    elif kind == "logo":
        size = int(sys.argv[3]) if len(sys.argv) > 3 else 512
        img = render(size, size, 0.46)
    else:
        sys.exit("usage: make_art.py icon|logo OUT [SIZE]")
    write_png(img, out)


if __name__ == "__main__":
    main()

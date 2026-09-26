#!/usr/bin/env python3
"""
Share material for Claude S40, generated from the real build outputs.

  promo.py SHOTS_DIR OUT_DIR

Reads the FreeJ2ME screenshots (make emu) and writes:
  splash.gif         start-up animation (2x, pixel-exact)
  poster.png         1080x1350 post: logo, title, three phone screens
  square.png         1080x1080 variant
  logo.png           1024x1024 spark on transparent background
  jingle.wav         start-up melody (same notes as Sound.JINGLE)
  chime.wav          reply sound (same notes as Sound.CHIME)

Screens come from the emulator in the app's test mode; the poster says so.
"""
import glob
import math
import os
import struct
import sys
import wave

from PIL import Image, ImageDraw, ImageFont

sys.path.insert(0, os.path.dirname(__file__))
import make_art  # noqa: E402

BG = (250, 246, 239)
INK = (38, 37, 44)
MUTED = (124, 118, 107)
ACCENT = (201, 100, 66)
FONT_DIR = "/System/Library/Fonts"


def font(size, bold=False):
    for path, index in ((f"{FONT_DIR}/Avenir Next.ttc", 2 if bold else 7),
                        (f"{FONT_DIR}/Helvetica.ttc", 1 if bold else 0)):
        try:
            return ImageFont.truetype(path, size, index=index)
        except OSError:
            continue
    return ImageFont.load_default()


# ------------------------------------------------------------------ sound
# Keep in sync with Sound.java: (tempo byte, [(midi note or -1, 1/64 units)])
JINGLE = (30, [(67, 4), (72, 4), (76, 4), (79, 8), (-1, 2), (76, 4), (79, 4), (84, 12),
               (-1, 2), (86, 4), (88, 20)])
CHIME = (40, [(81, 4), (88, 8)])


def render_wav(path, seq, rate=22050):
    tempo, notes = seq
    whole_ms = 240000 / (tempo * 4)
    samples = []
    for note, units in notes:
        n = int(rate * whole_ms * units / 64 / 1000)
        if note < 0:
            samples += [0.0] * n
            continue
        f = 440.0 * 2 ** ((note - 69) / 12)
        attack, release = int(rate * 0.004), int(rate * 0.03)
        for i in range(n):
            t = i / rate
            # soft square: a few odd harmonics, like a phone tone generator
            v = sum(math.sin(2 * math.pi * f * k * t) / k for k in (1, 3, 5, 7)) * 0.9
            env = min(1.0, i / max(1, attack), (n - i) / max(1, release))
            samples.append(v * env * 0.45)
    samples += [0.0] * int(rate * 0.2)
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(rate)
        w.writeframes(b"".join(struct.pack("<h", int(max(-1, min(1, s)) * 32767)) for s in samples))


# ------------------------------------------------------------------ images

def phone(screen, scale=2):
    """A generic candybar phone around a screenshot (no manufacturer marks)."""
    s = screen.resize((screen.width * scale, screen.height * scale), Image.NEAREST)
    pad, top, bottom = 26, 70, 150
    W, H = s.width + 2 * pad, s.height + top + bottom
    body = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(body)
    d.rounded_rectangle([0, 0, W - 1, H - 1], radius=48, fill=(52, 52, 58))
    d.rounded_rectangle([6, 6, W - 7, H - 7], radius=44, fill=(196, 198, 204))
    d.rounded_rectangle([pad - 8, top - 8, W - pad + 7, top + s.height + 7], radius=10, fill=(20, 20, 24))
    body.paste(s, (pad, top))
    d.rounded_rectangle([W // 2 - 40, 26, W // 2 + 40, 34], radius=4, fill=(150, 152, 160))  # earpiece
    cy = top + s.height + bottom // 2
    d.rounded_rectangle([W // 2 - 70, cy - 34, W // 2 + 70, cy + 34], radius=34, fill=(168, 170, 178))
    d.ellipse([W // 2 - 20, cy - 20, W // 2 + 20, cy + 20], fill=(140, 142, 150))
    return body


def compose(size, shots, out):
    W, H = size
    img = Image.new("RGB", (W, H), BG)
    d = ImageDraw.Draw(img)
    logo = make_art.render(140, 140, 0.46)
    img.paste(logo, (W // 2 - 70, 40), logo)
    d.text((W // 2, 215), "Claude S40", font=font(78, True), fill=INK, anchor="mm")
    d.text((W // 2, 280), "Chatting with Claude on a 2007 Nokia 6300", font=font(34), fill=MUTED, anchor="mm")
    phones = [phone(Image.open(p).convert("RGB")) for p in shots]
    chips_h = 150
    avail_h = H - 340 - chips_h
    scale = min(1.0, (W - 80) / sum(p.width + 30 for p in phones), avail_h / phones[0].height)
    phones = [p.resize((int(p.width * scale), int(p.height * scale)), Image.LANCZOS) for p in phones]
    total = sum(p.width for p in phones) + 30 * (len(phones) - 1)
    x = (W - total) // 2
    y = 340 + max(0, (avail_h - phones[0].height) // 3)
    for i, p in enumerate(phones):
        dy = -18 if i == len(phones) // 2 else 0
        img.paste(p, (x, y + dy), p)
        x += p.width + 30
    chips = ["Quick prompts", "Dark mode", "Startup jingle", "No-typing pairing", "TLS 1.0 bridge"]
    f = font(26, True)
    widths = [d.textlength(c, font=f) + 36 for c in chips]
    rows, row, rw = [], [], 0
    for c, cw in zip(chips, widths):
        if row and rw + cw + 14 > W - 80:
            rows.append((row, rw))
            row, rw = [], 0
        row.append((c, cw))
        rw += cw + 14
    rows.append((row, rw))
    cy = y + phones[0].height + max(40, (H - 80 - (y + phones[0].height) - 58 * len(rows)) // 2)
    for row, rw in rows:
        cx = (W - rw + 14) / 2
        for c, cw in row:
            d.rounded_rectangle([cx, cy, cx + cw, cy + 46], radius=23, fill=(246, 227, 217))
            d.text((cx + cw / 2, cy + 23), c, font=f, fill=ACCENT, anchor="mm")
            cx += cw + 14
        cy += 58
    d.text((W // 2, H - 40), "Unofficial client · Screens: emulator, test mode",
           font=font(22), fill=MUTED, anchor="mm")
    img.save(out)


def main():
    shots_dir, out = sys.argv[1], sys.argv[2]
    os.makedirs(out, exist_ok=True)

    frames = [Image.open(f).convert("RGB") for f in sorted(glob.glob(f"{shots_dir}/splash/f*.png"))]
    frames = [f.resize((f.width * 2, f.height * 2), Image.NEAREST) for f in frames]
    if frames:
        frames[0].save(f"{out}/splash.gif", save_all=True, append_images=frames[1:] + [frames[-1]] * 6,
                       duration=150, loop=0, optimize=False)

    def shot(name):
        m = glob.glob(f"{shots_dir}/*_{name}.png")
        return m[0] if m else None

    picks = [p for p in (shot("home_selection"), shot("chat_reply2"), shot("chat_dark_large")) if p]
    if picks:
        compose((1080, 1350), picks, f"{out}/poster.png")
        compose((1080, 1080), picks, f"{out}/square.png")

    make_art.render(1024, 1024, 0.46).save(f"{out}/logo.png")
    render_wav(f"{out}/jingle.wav", JINGLE)
    render_wav(f"{out}/chime.wav", CHIME)
    for f in sorted(os.listdir(out)):
        print(f"{out}/{f}  {os.path.getsize(os.path.join(out, f))} bytes")


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""
Share material for AIKON, generated from the real build outputs.

  promo.py SHOTS_DIR OUT_DIR

Reads the FreeJ2ME screenshots (make emu) and writes:
  splash.gif         start-up animation (2x, pixel-exact)
  cover.png          1280x640 README cover / social preview: drawn, no screenshots
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


def drawn_phone(d, x, y, w, S):
    """A generic candybar phone drawn from shapes (no screenshot, no marks); returns its height."""
    def R(box, r, fill):
        d.rounded_rectangle([v * S for v in box], radius=r * S, fill=fill)

    def E(box, fill):
        d.ellipse([v * S for v in box], fill=fill)

    h = int(w * 2.25)
    R([x + 6, y + 10, x + w + 6, y + h + 10], w * 0.16, (228, 220, 205))        # shadow
    R([x, y, x + w, y + h], w * 0.16, (52, 52, 58))                              # rim
    R([x + 5, y + 5, x + w - 5, y + h - 5], w * 0.15, (200, 202, 208))          # body
    R([x + w / 2 - 26, y + 22, x + w / 2 + 26, y + 28], 3, (150, 152, 160))     # earpiece
    sx, sy, sw = x + 22, y + 48, w - 44
    sh = int(sw * 4 / 3)
    R([sx - 7, sy - 7, sx + sw + 7, sy + sh + 7], 8, (24, 24, 28))              # screen frame
    R([sx, sy, sx + sw, sy + sh], 2, BG)                                         # screen
    # screen: header, a message, a reply with a list, typing dots, status line
    hb = sh * 0.10
    R([sx, sy, sx + sw, sy + hb], 1, INK)
    logo = make_art.render(int(hb * 0.8 * S), int(hb * 0.8 * S), 0.46)
    d._image.paste(logo, (int((sx + 5) * S), int((sy + hb * 0.1) * S)), logo)
    R([sx + hb + 6, sy + hb * 0.35, sx + hb + 6 + sw * 0.34, sy + hb * 0.62], 2, (244, 239, 230))
    ub = [sx + sw * 0.38, sy + hb + 10, sx + sw - 7, sy + hb + 10 + sh * 0.15]
    R(ub, 7, ACCENT)
    for i, frac in enumerate((0.9, 0.6)):
        ly = ub[1] + 9 + i * 10
        R([ub[0] + 7, ly, ub[0] + 7 + (ub[2] - ub[0] - 14) * frac, ly + 4], 2, (255, 236, 226))
    cb = [sx + 7, ub[3] + 8, sx + sw * 0.84, ub[3] + 8 + sh * 0.40]
    R([cb[0] - 1, cb[1] - 1, cb[2] + 1, cb[3] + 1], 7, (228, 220, 205))
    R(cb, 7, (255, 255, 255))
    E([cb[0] + 7, cb[1] + 6, cb[0] + 14, cb[1] + 13], (217, 119, 87))
    R([cb[0] + 18, cb[1] + 8, cb[0] + 50, cb[1] + 11], 2, (230, 170, 150))
    rows = [(0, 0.92), (0, 0.8), (0, 0.55), (1, 0.7), (1, 0.62), (1, 0.5)]
    for i, (dot, frac) in enumerate(rows):
        ly = cb[1] + 19 + i * 11 + (4 if dot else 0)
        lx = cb[0] + 8
        if dot:
            E([lx + 1, ly, lx + 5, ly + 4], MUTED)
            lx += 10
        R([lx, ly, lx + (cb[2] - cb[0] - 18) * frac, ly + 4], 2, (150, 145, 135))
    tb = [sx + 7, cb[3] + 9, sx + 7 + sw * 0.28, cb[3] + 9 + sh * 0.08]
    R(tb, 6, (255, 255, 255))
    for i in range(3):
        cx, cy = tb[0] + (tb[2] - tb[0]) * (0.25 + 0.25 * i), (tb[1] + tb[3]) / 2
        r = 3 if i == 0 else 2.2
        E([cx - r, cy - r, cx + r, cy + r], ACCENT if i == 0 else (215, 208, 196))
    R([sx, sy + sh - hb * 0.8, sx + sw, sy + sh], 1, (255, 255, 255))
    R([sx + 6, sy + sh - hb * 0.5, sx + sw * 0.55, sy + sh - hb * 0.35], 2, (190, 184, 174))
    # keys: softkeys, navigation ring, 4 x 3 keypad
    ky = sy + sh + 22
    R([x + 18, ky, x + w * 0.34, ky + 12], 6, (176, 178, 186))
    R([x + w * 0.66, ky, x + w - 18, ky + 12], 6, (176, 178, 186))
    E([x + w / 2 - 30, ky - 6, x + w / 2 + 30, ky + 54], (168, 170, 178))
    E([x + w / 2 - 12, ky + 12, x + w / 2 + 12, ky + 36], (140, 142, 150))
    gy = ky + 66
    kw, kh, gap = (w - 48) / 3, (y + h - 22 - gy) / 4 - 6, 6
    for r in range(4):
        for c in range(3):
            kx = x + 18 + c * (kw + gap)
            k2 = gy + r * (kh + gap)
            R([kx, k2, kx + kw, k2 + kh], kh / 2, (178, 180, 188))
            R([kx + kw / 2 - 5, k2 + kh / 2 - 2, kx + kw / 2 + 5, k2 + kh / 2 + 2], 2, (140, 142, 150))
    return h


def cover(out, W=1280, H=640, S=3):
    """README cover: logo, title, what it is, feature chips and a drawn phone. No screenshots."""
    img = Image.new("RGB", (W * S, H * S), BG)
    d = ImageDraw.Draw(img)
    d.ellipse([(W - 520) * S, (H / 2 - 300) * S, (W - 20) * S, (H / 2 + 300) * S], fill=(246, 227, 217))
    drawn_phone(d, W - 400, 40, 250, S)
    x = 84
    logo = make_art.render(96 * S, 96 * S, 0.46)
    img.paste(logo, (x * S, 84 * S), logo)
    d.text((x * S, 196 * S), "AIKON", font=font(92 * S, True), fill=INK)
    d.text((x * S, 318 * S), "Claude, ChatGPT, Gemini, Grok on a 2007 Nokia.", font=font(34 * S), fill=MUTED)
    d.text((x * S, 362 * S), "Nokia S40 & S60 app + a small Go server.", font=font(34 * S), fill=MUTED)
    chips = ["Pick your model", "Web search", "Photos + voice", "Türkçe + English", "TLS 1.0 bridge"]
    f = font(22 * S, True)
    cx, cy = x, 432
    for c in chips:
        cw = d.textlength(c, font=f) / S + 30
        if cx + cw > W - 540:
            cx, cy = x, cy + 50
        d.rounded_rectangle([cx * S, cy * S, (cx + cw) * S, (cy + 40) * S], radius=20 * S, fill=(246, 227, 217))
        d.text(((cx + cw / 2) * S, (cy + 20) * S), c, font=f, fill=ACCENT, anchor="mm")
        cx += cw + 12
    d.text((x * S, (H - 44) * S), "Unofficial client. Not made or endorsed by Anthropic, OpenAI, Google, xAI or Nokia.",
           font=font(19 * S), fill=MUTED)
    img.resize((W, H), Image.LANCZOS).save(out)


def compose(size, shots, out):
    W, H = size
    img = Image.new("RGB", (W, H), BG)
    d = ImageDraw.Draw(img)
    logo = make_art.render(140, 140, 0.46)
    img.paste(logo, (W // 2 - 70, 40), logo)
    d.text((W // 2, 215), "AIKON", font=font(78, True), fill=INK, anchor="mm")
    d.text((W // 2, 280), "Today's AI on a 2007 Nokia 6300", font=font(34), fill=MUTED, anchor="mm")
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

    picks = [p for p in (shot("home_selection"), shot("chat_reply_lists"), shot("reading_dark_large")) if p]
    if picks:
        compose((1080, 1350), picks, f"{out}/poster.png")
        compose((1080, 1080), picks, f"{out}/square.png")

    cover(f"{out}/cover.png")
    make_art.render(1024, 1024, 0.46).save(f"{out}/logo.png")
    render_wav(f"{out}/jingle.wav", JINGLE)
    render_wav(f"{out}/chime.wav", CHIME)
    for f in sorted(os.listdir(out)):
        print(f"{out}/{f}  {os.path.getsize(os.path.join(out, f))} bytes")


if __name__ == "__main__":
    main()

package io.github.emir.claudes40;

import javax.microedition.lcdui.Graphics;

/**
 * The AIKON mark: a speech bubble holding a 3x3 keypad (chat on a keypad
 * phone), drawn with primitives; no image asset is embedded. The same
 * geometry is drawn by tools/make_art.py for the MIDlet icon and the promo
 * images.
 */
final class Logo {

    /** All nine keys lit (the mark at rest). */
    static final int ALL = 9;

    /** The keys: always light, so they read on both themes. */
    private static final int KEY = 0xFBF3EE;

    private Logo() {
    }

    /**
     * @param cx,cy centre of the mark (bubble and tail together)
     * @param size  width in pixels
     * @param scale percent, for animation (100 = rest)
     * @param keys  keys lit, 0..ALL in reading order; the others are dimmed
     */
    static void draw(Graphics g, int cx, int cy, int size, int scale, int keys) {
        int s = size * scale / 100;
        if (s < 4) {
            return;
        }
        int w = s;
        int h = s * 80 / 100;
        int x0 = cx - w / 2;
        int y0 = cy - h / 2 - s * 6 / 100;
        g.setColor(Theme.spark);
        roundRect(g, x0, y0, w, h, Math.max(1, s * 24 / 100));
        // tail, bottom left
        int tx = x0 + w * 20 / 100;
        int bottom = y0 + h - 1;
        g.fillTriangle(tx, bottom, tx + w * 24 / 100, bottom, x0 + w * 10 / 100, bottom + s * 20 / 100);
        if (s < 9) {
            return; // too small for keys: a plain bubble
        }
        int padX = w * 25 / 100;
        int padY = h * 24 / 100;
        int gx = (w - 2 * padX) / 2;
        int gy = (h - 2 * padY) / 2;
        int d = Math.max(1, s * 13 / 100);
        int dim = Theme.mix(Theme.spark, KEY, 90);
        for (int i = 0; i < ALL; i++) {
            int px = x0 + padX + (i % 3) * gx;
            int py = y0 + padY + (i / 3) * gy;
            g.setColor(i < keys ? KEY : dim);
            if (d <= 2) {
                g.fillRect(px - d / 2, py - d / 2, d, d);
            } else {
                g.fillArc(px - d / 2, py - d / 2, d, d, 0, 360);
            }
        }
    }

    /**
     * A filled rounded rectangle from rectangles and corner discs: fillRoundRect's
     * arcs differ between phones (and the emulator draws them nearly square).
     */
    private static void roundRect(Graphics g, int x, int y, int w, int h, int r) {
        r = Math.min(r, Math.min(w, h) / 2);
        g.fillRect(x + r, y, w - 2 * r, h);
        g.fillRect(x, y + r, w, h - 2 * r);
        int d = 2 * r;
        g.fillArc(x, y, d, d, 0, 360);
        g.fillArc(x + w - d - 1, y, d, d, 0, 360);
        g.fillArc(x, y + h - d - 1, d, d, 0, 360);
        g.fillArc(x + w - d - 1, y + h - d - 1, d, d, 0, 360);
    }

    /** Small four-point sparkle, used as a decoration. */
    static void sparkle(Graphics g, int cx, int cy, int r, int color) {
        if (r < 1) {
            return;
        }
        int t = Math.max(1, r / 4);
        g.setColor(color);
        g.fillTriangle(cx, cy - r, cx - t, cy, cx + t, cy);
        g.fillTriangle(cx, cy + r, cx - t, cy, cx + t, cy);
        g.fillTriangle(cx - r, cy, cx, cy - t, cx, cy + t);
        g.fillTriangle(cx + r, cy, cx, cy - t, cx, cy + t);
    }
}

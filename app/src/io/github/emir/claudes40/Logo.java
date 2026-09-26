package io.github.emir.claudes40;

import javax.microedition.lcdui.Graphics;

/**
 * Claude-style spark mark (terracotta radial burst), drawn with primitives;
 * no image asset is embedded. Used with the permission the user stated on
 * 2026-09-25; the app still says it is an unofficial client.
 */
final class Logo {

    /** Ray lengths in percent of the radius; slightly uneven, like a hand-drawn spark. */
    private static final int[] RAYS = { 100, 82, 94, 78, 100, 86, 92, 80, 98, 84, 90, 79 };

    private Logo() {
    }

    /**
     * @param cx,cy   centre
     * @param size    diameter in pixels
     * @param twinkle scale in percent for animation (100 = rest)
     * @param spin    rotation in degrees
     */
    static void draw(Graphics g, int cx, int cy, int size, int twinkle, int spin) {
        int r = size / 2 * twinkle / 100;
        if (r < 2) {
            return;
        }
        g.setColor(Theme.spark);
        int n = RAYS.length;
        // tapered rays: wide at the core, narrow and rounded at the tip
        int base = Math.max(1, r * 17 / 100);
        int tipW = Math.max(1, r * 9 / 100);
        for (int i = 0; i < n; i++) {
            double a = Math.toRadians(spin + i * 360.0 / n);
            double ca = Math.cos(a);
            double sa = Math.sin(a);
            double len = r * RAYS[i] / 100.0 - tipW / 2.0;
            // perpendicular unit vector
            double px = -sa;
            double py = ca;
            int b1x = cx + (int) (px * base / 2);
            int b1y = cy + (int) (py * base / 2);
            int b2x = cx - (int) (px * base / 2);
            int b2y = cy - (int) (py * base / 2);
            int t1x = cx + (int) (ca * len + px * tipW / 2);
            int t1y = cy + (int) (sa * len + py * tipW / 2);
            int t2x = cx + (int) (ca * len - px * tipW / 2);
            int t2y = cy + (int) (sa * len - py * tipW / 2);
            g.fillTriangle(b1x, b1y, b2x, b2y, t1x, t1y);
            g.fillTriangle(b2x, b2y, t1x, t1y, t2x, t2y);
            int ex = cx + (int) (ca * len);
            int ey = cy + (int) (sa * len);
            g.fillArc(ex - tipW / 2, ey - tipW / 2, tipW + 1, tipW + 1, 0, 360);
        }
        int core = Math.max(2, r * 24 / 100);
        g.fillArc(cx - core, cy - core, core * 2, core * 2, 0, 360);
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

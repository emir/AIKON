package io.github.emir.claudes40;

import javax.microedition.lcdui.Graphics;

/**
 * The AIKON mark: a white "AK" monogram (the A's diagonal, a short bar, the
 * shared stem, the K's arms) on a blue rounded square, drawn with
 * primitives; no image asset is embedded. Geometry measured from the
 * designer's 1254 px artwork, in 1/1000 of the tile's width (the tile is
 * 0.969 as tall as wide). tools/make_art.py draws the same shapes for the
 * MIDlet icon and the promo images.
 */
final class Logo {

    /** The four strokes: A diagonal, short bar, stem, K arms. */
    static final int ALL = 4;

    static final int BLUE = 0x0740DE;
    private static final int WHITE = 0xFFFFFF;

    /** Tile height and corner radius, per 1000 of the width. */
    private static final int TILE_H = 969;
    private static final int RADIUS = 190;

    // strokes as x,y pairs per 1000 of the tile's width (top left of the tile = 0,0)
    private static final int[] A_DIAG = { 41, 700, 174, 700, 474, 396, 474, 252 };
    private static final int[] SHORT_BAR = { 381, 513, 474, 700 }; // x0, y0, x1, y1
    private static final int[] STEM = { 494, 251, 587, 700 };
    private static final int[] K_UPPER = { 794, 251, 932, 251, 680, 496, 605, 435 };
    private static final int[] K_JOINT = { 605, 435, 680, 496, 605, 575 };
    private static final int[] K_LOWER = { 605, 575, 680, 496, 879, 700, 733, 700 };

    private Logo() {
    }

    /**
     * @param cx,cy centre of the tile
     * @param size  tile width in pixels
     * @param scale percent, for animation (100 = rest)
     * @param parts strokes drawn, 0..ALL in order (the splash builds the monogram)
     */
    static void draw(Graphics g, int cx, int cy, int size, int scale, int parts) {
        int s = size * scale / 100;
        if (s < 4) {
            return;
        }
        int th = s * TILE_H / 1000;
        int x0 = cx - s / 2;
        int y0 = cy - th / 2;
        g.setColor(BLUE);
        roundRect(g, x0, y0, s, th, Math.max(1, s * RADIUS / 1000));
        if (s < 8) {
            return; // too small for the monogram: a plain tile
        }
        g.setColor(WHITE);
        if (parts >= 1) {
            quad(g, A_DIAG, x0, y0, s);
        }
        if (parts >= 2) {
            rect(g, SHORT_BAR, x0, y0, s);
        }
        if (parts >= 3) {
            rect(g, STEM, x0, y0, s);
        }
        if (parts >= 4) {
            quad(g, K_UPPER, x0, y0, s);
            tri(g, K_JOINT, x0, y0, s);
            quad(g, K_LOWER, x0, y0, s);
        }
    }

    private static int px(int v, int origin, int s) {
        return origin + (v * s + 500) / 1000;
    }

    /** A convex quadrilateral as two triangles. */
    private static void quad(Graphics g, int[] p, int x0, int y0, int s) {
        int ax = px(p[0], x0, s), ay = px(p[1], y0, s);
        int bx = px(p[2], x0, s), by = px(p[3], y0, s);
        int cx = px(p[4], x0, s), cy = px(p[5], y0, s);
        int dx = px(p[6], x0, s), dy = px(p[7], y0, s);
        g.fillTriangle(ax, ay, bx, by, cx, cy);
        g.fillTriangle(ax, ay, cx, cy, dx, dy);
    }

    private static void tri(Graphics g, int[] p, int x0, int y0, int s) {
        g.fillTriangle(px(p[0], x0, s), px(p[1], y0, s), px(p[2], x0, s), px(p[3], y0, s),
                px(p[4], x0, s), px(p[5], y0, s));
    }

    private static void rect(Graphics g, int[] r, int x0, int y0, int s) {
        int ax = px(r[0], x0, s);
        int ay = px(r[1], y0, s);
        g.fillRect(ax, ay, Math.max(1, px(r[2], x0, s) - ax), Math.max(1, px(r[3], y0, s) - ay));
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

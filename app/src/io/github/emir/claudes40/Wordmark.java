package io.github.emir.claudes40;

import javax.microedition.lcdui.Image;

/**
 * The AIKON wordmark (docs/images/wordmark.png) as polygons, filled with
 * smooth edges into an ARGB image (coverage as alpha) on the phone; no
 * image asset in the JAR. Coordinates are the artwork's pixels (1055 x 250),
 * measured from it; the letters span x 1..1051, y 3..247. Filled by the
 * non-zero rule (outlines counter-clockwise on screen, holes reversed), so
 * the joined A and I may overlap.
 */
final class Wordmark {

    private static final int X0 = 1;
    private static final int Y0 = 3;
    private static final int W = 1051;
    private static final int H = 244;

    /** Polygons as x,y pairs; a leading -1 marks a hole. */
    private static final int[][] POLYS = {
        { 119, 5, 181, 5, 292, 246, 219, 246, 197, 198, 95, 198, 72, 246, 1, 246 },  // A
        { -1, 148, 85, 175, 144, 121, 144 },                                          // A's counter
        { 288, 5, 350, 5, 350, 246, 288, 246 },                                       // I
        { 361, 5, 421, 5, 421, 246, 361, 246 },                                       // K stem
        { 500, 5, 587, 5, 476, 124, 589, 246, 503, 246, 425, 160, 425, 85 },          // K arms
        { 826, 5, 884, 5, 987, 142, 987, 5, 1051, 5, 1051, 246, 985, 246, 887, 113, 887, 246, 826, 246 }, // N
    };

    /** The letter of each polygon: 0 A, 1 I, 2 K, 3 O (the ellipses), 4 N. */
    private static final int[] LETTER = { 0, 0, 1, 2, 2, 4 };
    /** Each letter's left and right edge in the artwork (A I K O N). */
    private static final int[] LEFT = { 1, 288, 361, 558, 826 };
    private static final int[] RIGHT = { 292, 350, 589, 817, 1051 };

    // O: outer and inner ellipse (centre x, centre y, rx, ry), in tenths of a pixel
    private static final int[] O_OUTER = { 6875, 1250, 1290, 1220 };
    private static final int[] O_INNER = { 6875, 1265, 655, 645 };

    /** A few sizes (header, empty chat, splash), keyed by width and colour. */
    private static final java.util.Hashtable cache = new java.util.Hashtable();

    private Wordmark() {
    }

    /** Height for a given width. */
    static int height(int width) {
        return Math.max(1, width * H / W);
    }

    /** The wordmark, width pixels wide, in an RGB colour; cached. */
    static synchronized Image get(int width, int color) {
        width = Math.max(8, width);
        Long key = new Long(((long) width << 32) | (color & 0xFFFFFFL));
        Image img = (Image) cache.get(key);
        if (img == null) {
            if (cache.size() >= 4) {
                cache.clear(); // a theme or text size change leaves old ones behind
            }
            img = render(width, color, -1);
            cache.put(key, img);
        }
        return img;
    }

    /**
     * Letter i (0 A, 1 I, 2 K, 3 O, 4 N) alone, as drawn in the wordmark of
     * that width: drawn at letterX(i, width) over get(width, ...) it covers
     * the same pixels. Not cached (the splash keeps its own).
     */
    static Image letter(int i, int width, int color) {
        return render(Math.max(8, width), color, i);
    }

    /** Where letter i starts in the wordmark of that width, in whole pixels. */
    static int letterX(int i, int width) {
        return (int) ((LEFT[i] - X0) * (Math.max(8, width) / (float) W));
    }

    /** Letter i's width in the artwork's pixels (scale with width / artWidth()). */
    static int letterArtWidth(int i) {
        return RIGHT[i] - LEFT[i];
    }

    /** The artwork's width (the whole wordmark). */
    static int artWidth() {
        return W;
    }

    private static final int SUB = 4; // sample rows per pixel row

    /** The wordmark w pixels wide, or with letter >= 0 only that letter (cropped, from letterX). */
    private static Image render(int ww, int color, int letter) {
        int h = height(ww);
        float k = ww / (float) W;
        int ox = 0;
        int w = ww;
        if (letter >= 0) {
            ox = letterX(letter, ww);
            w = (int) ((RIGHT[letter] - X0) * k) + 2 - ox;
        }
        // edges: x0, y0, x1, y1, winding (+1/-1), in pixels
        float[] e = new float[5 * 200];
        int n = 0;
        for (int p = 0; p < POLYS.length; p++) {
            if (letter >= 0 && LETTER[p] != letter) {
                continue;
            }
            int[] poly = POLYS[p];
            boolean hole = poly[0] == -1;
            int off = hole ? 1 : 0;
            int pts = (poly.length - off) / 2;
            float[] xs = new float[pts];
            float[] ys = new float[pts];
            for (int i = 0; i < pts; i++) {
                xs[i] = (poly[off + 2 * i] - X0) * k - ox;
                ys[i] = (poly[off + 2 * i + 1] - Y0) * k;
            }
            n = addPoly(e, n, xs, ys, hole);
        }
        if (letter < 0 || letter == 3) {
            n = addEllipse(e, n, O_OUTER, k, ox, false);
            n = addEllipse(e, n, O_INNER, k, ox, true);
        }

        float[] cov = new float[w * h];
        float[] cx = new float[64];
        int[] cd = new int[64];
        for (int row = 0; row < h; row++) {
            for (int s = 0; s < SUB; s++) {
                float y = row + (s + 0.5f) / SUB;
                int m = 0;
                for (int j = 0; j < n; j += 5) {
                    float y0 = e[j + 1], y1 = e[j + 3];
                    if ((y0 <= y && y < y1) || (y1 <= y && y < y0)) {
                        float x = e[j] + (y - y0) * (e[j + 2] - e[j]) / (y1 - y0);
                        // insertion sort by x
                        int i = m++;
                        while (i > 0 && cx[i - 1] > x) {
                            cx[i] = cx[i - 1];
                            cd[i] = cd[i - 1];
                            i--;
                        }
                        cx[i] = x;
                        cd[i] = (int) e[j + 4];
                    }
                }
                int wind = 0;
                for (int i = 0; i < m - 1; i++) {
                    wind += cd[i];
                    if (wind != 0) {
                        span(cov, row * w, w, cx[i], cx[i + 1]);
                    }
                }
            }
        }
        int rgb = color & 0xFFFFFF;
        int[] px = new int[w * h];
        for (int p = 0; p < px.length; p++) {
            int a = (int) (cov[p] * 255 / SUB);
            px[p] = (a > 255 ? 255 : a) << 24 | rgb;
        }
        return Image.createRGBImage(px, w, h, true);
    }

    /** Adds 1/SUB of coverage over x0..x1 on one pixel row, partial pixels by their share. */
    private static void span(float[] cov, int base, int w, float x0, float x1) {
        if (x0 < 0) {
            x0 = 0;
        }
        if (x1 > w) {
            x1 = w;
        }
        if (x1 <= x0) {
            return;
        }
        int a = (int) x0;
        int b = (int) x1;
        if (a == b) {
            cov[base + a] += x1 - x0;
            return;
        }
        cov[base + a] += a + 1 - x0;
        for (int x = a + 1; x < b; x++) {
            cov[base + x] += 1;
        }
        if (b < w) {
            cov[base + b] += x1 - b;
        }
    }

    /** Adds a closed polygon; outlines wind one way, holes the other (by the signed area). */
    private static int addPoly(float[] e, int n, float[] xs, float[] ys, boolean hole) {
        float area = 0;
        int pts = xs.length;
        for (int i = 0; i < pts; i++) {
            int j = (i + 1) % pts;
            area += xs[i] * ys[j] - xs[j] * ys[i];
        }
        int dir = (area > 0) != hole ? 1 : -1;
        for (int i = 0; i < pts; i++) {
            int j = (i + 1) % pts;
            if (ys[i] == ys[j]) {
                continue; // horizontal edges never cross a sample row
            }
            e[n++] = xs[i];
            e[n++] = ys[i];
            e[n++] = xs[j];
            e[n++] = ys[j];
            e[n++] = ys[j] > ys[i] ? dir : -dir;
        }
        return n;
    }

    private static int addEllipse(float[] e, int n, int[] el, float k, int ox, boolean hole) {
        int pts = 40;
        float[] xs = new float[pts];
        float[] ys = new float[pts];
        for (int i = 0; i < pts; i++) {
            double a = Math.PI * 2 * i / pts;
            xs[i] = (float) ((el[0] / 10.0 - X0 + el[2] / 10.0 * Math.cos(a)) * k) - ox;
            ys[i] = (float) ((el[1] / 10.0 - Y0 + el[3] / 10.0 * Math.sin(a)) * k);
        }
        return addPoly(e, n, xs, ys, hole);
    }
}

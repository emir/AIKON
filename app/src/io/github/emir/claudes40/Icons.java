package io.github.emir.claudes40;

import java.util.Hashtable;

import javax.microedition.lcdui.Image;

/**
 * Line icons with smooth edges: every icon is a set of strokes on a 24-unit
 * grid (round caps and joins), rasterised once per size and colour into an
 * ARGB image whose alpha is the stroke's pixel coverage. Graphics primitives
 * have no anti-aliasing; this does, without image assets in the JAR.
 */
final class Icons {

    static final int CHAT = 0;
    static final int CHATS = 1;
    static final int PROMPTS = 2;
    static final int NEW_CHAT = 3;
    static final int SAVED = 4;
    static final int CONN = 5;
    static final int SETTINGS = 6;
    static final int INFO = 7;
    static final int EXIT = 8;
    static final int PIN = 9;
    static final int CHECK = 10;
    static final int GLOBE = 11;
    static final int BOOK = 12;
    static final int CALENDAR = 13;
    static final int SHORT = 14;
    static final int SPARK = 15;
    static final int RESEND = 16;
    static final int SEARCH = 17;

    // shape codes, coordinates in grid units (0..24)
    private static final int LINE = 1;   // n, x0, y0, ... xn-1, yn-1: polyline through n points
    private static final int CIRCLE = 2; // cx, cy, r
    private static final int RECT = 3;   // x0, y0, x1, y1, corner radius
    private static final int DOT = 4;    // cx, cy, radius in tenths: filled
    private static final int ARC = 5;    // cx, cy, r, start, end (degrees, counter-clockwise from 3 o'clock)
    private static final int GEAR = 6;   // no arguments: the settings gear's outline

    private static final int[][] SHAPES = {
        { // chat: speech bubble with three dots
            RECT, 3, 4, 21, 17, 4,
            LINE, 3, 7, 17, 6, 21, 11, 17,
            DOT, 8, 11, 13, DOT, 12, 11, 13, DOT, 16, 11, 13 },
        { // chats: list
            DOT, 4, 6, 14, DOT, 4, 12, 14, DOT, 4, 18, 14,
            LINE, 2, 9, 6, 21, 6, LINE, 2, 9, 12, 21, 12, LINE, 2, 9, 18, 17, 18 },
        { // quick prompts: lightning bolt
            LINE, 7, 13, 2, 4, 14, 12, 14, 11, 22, 20, 10, 12, 10, 13, 2 },
        { // new chat: square with a pencil
            ARC, 7, 7, 3, 90, 180, ARC, 7, 17, 3, 180, 270, ARC, 17, 17, 3, 270, 360,
            LINE, 2, 12, 4, 7, 4, LINE, 2, 4, 7, 4, 17, LINE, 2, 7, 20, 17, 20, LINE, 2, 20, 17, 20, 13,
            LINE, 6, 18, 3, 21, 6, 12, 15, 8, 16, 9, 12, 18, 3 },
        { // saved: bookmark
            ARC, 8, 5, 2, 90, 180, ARC, 16, 5, 2, 0, 90,
            LINE, 2, 8, 3, 16, 3, LINE, 4, 18, 5, 18, 21, 12, 16, 6, 21, LINE, 2, 6, 21, 6, 5 },
        { // connection test: signal bars
            LINE, 2, 5, 17, 5, 20, LINE, 2, 10, 13, 10, 20, LINE, 2, 15, 8, 15, 20, LINE, 2, 20, 3, 20, 20 },
        { // settings: gear
            GEAR, CIRCLE, 12, 12, 3 },
        { // about: circled i
            CIRCLE, 12, 12, 10, DOT, 12, 8, 14, LINE, 2, 12, 12, 12, 17 },
        { // exit: door and arrow
            ARC, 6, 6, 3, 90, 180, ARC, 6, 18, 3, 180, 270,
            LINE, 2, 9, 3, 6, 3, LINE, 2, 3, 6, 3, 18, LINE, 2, 6, 21, 9, 21,
            LINE, 2, 10, 12, 21, 12, LINE, 3, 16, 7, 21, 12, 16, 17 },
        { // pin: push pin
            LINE, 2, 8, 3, 16, 3, LINE, 5, 9, 3, 9, 10, 5, 15, 19, 15, 15, 10, LINE, 2, 15, 10, 15, 3,
            LINE, 2, 12, 15, 12, 21 },
        { // check mark
            LINE, 3, 5, 12, 10, 17, 19, 7 },
        { // globe: translate
            CIRCLE, 12, 12, 10, LINE, 2, 2, 12, 22, 12,
            LINE, 6, 12, 2, 15, 5, 16, 9, 16, 15, 15, 19, 12, 22, LINE, 6, 12, 2, 9, 5, 8, 9, 8, 15, 9, 19, 12, 22 },
        { // open book: reading mode
            LINE, 2, 12, 6, 12, 20, LINE, 6, 12, 6, 8, 4, 3, 4, 3, 18, 8, 18, 12, 20,
            LINE, 6, 12, 6, 16, 4, 21, 4, 21, 18, 16, 18, 12, 20 },
        { // calendar
            RECT, 3, 5, 21, 21, 3, LINE, 2, 3, 10, 21, 10, LINE, 2, 8, 3, 8, 7, LINE, 2, 16, 3, 16, 7 },
        { // shorter: lines getting shorter
            LINE, 2, 4, 6, 20, 6, LINE, 2, 4, 12, 16, 12, LINE, 2, 4, 18, 11, 18 },
        { // sparkle: simpler
            LINE, 9, 12, 3, 14, 10, 21, 12, 14, 14, 12, 21, 10, 14, 3, 12, 10, 10, 12, 3 },
        { // circular arrow: send again
            ARC, 12, 12, 8, 60, 360, LINE, 3, 15, 1, 16, 5, 20, 7 },
        { // magnifier: search
            CIRCLE, 10, 10, 7, LINE, 2, 15, 15, 21, 21 },
    };

    private static final Hashtable cache = new Hashtable();

    private Icons() {
    }

    /** The icon, size x size pixels, in an RGB colour; cached. */
    static Image get(int icon, int size, int color) {
        size = Math.max(8, size);
        Long key = new Long(((long) icon << 48) | ((long) size << 32) | (color & 0xFFFFFFL));
        Image img = (Image) cache.get(key);
        if (img == null) {
            if (cache.size() > 64) {
                cache.clear(); // theme or text size changes leave old ones behind
            }
            img = render(SHAPES[icon], size, color);
            cache.put(key, img);
        }
        return img;
    }

    /** Collects the strokes as segments (x0, y0, x1, y1, half width in pixels), then paints their coverage. */
    private static Image render(int[] shape, int size, int color) {
        float k = size / 24f;
        float hw = Math.max(0.65f, size / 24f); // a 2-unit stroke, never thinner than about 1.3 px
        Seg segs = new Seg();
        for (int i = 0; i < shape.length;) {
            int op = shape[i++];
            if (op == LINE) {
                int n = shape[i++];
                for (int j = 1; j < n; j++) {
                    segs.add(shape[i + 2 * j - 2] * k, shape[i + 2 * j - 1] * k, shape[i + 2 * j] * k, shape[i + 2 * j + 1] * k, hw);
                }
                i += 2 * n;
            } else if (op == CIRCLE) {
                arc(segs, shape[i] * k, shape[i + 1] * k, shape[i + 2] * k, 0, 360, hw);
                i += 3;
            } else if (op == ARC) {
                arc(segs, shape[i] * k, shape[i + 1] * k, shape[i + 2] * k, shape[i + 3], shape[i + 4], hw);
                i += 5;
            } else if (op == RECT) {
                float x0 = shape[i] * k, y0 = shape[i + 1] * k, x1 = shape[i + 2] * k, y1 = shape[i + 3] * k;
                float r = shape[i + 4] * k;
                segs.add(x0 + r, y0, x1 - r, y0, hw);
                segs.add(x1, y0 + r, x1, y1 - r, hw);
                segs.add(x1 - r, y1, x0 + r, y1, hw);
                segs.add(x0, y1 - r, x0, y0 + r, hw);
                arc(segs, x1 - r, y0 + r, r, 0, 90, hw);
                arc(segs, x0 + r, y0 + r, r, 90, 180, hw);
                arc(segs, x0 + r, y1 - r, r, 180, 270, hw);
                arc(segs, x1 - r, y1 - r, r, 270, 360, hw);
                i += 5;
            } else if (op == DOT) {
                float x = shape[i] * k, y = shape[i + 1] * k;
                segs.add(x, y, x, y, Math.max(hw, shape[i + 2] * k / 10));
                i += 3;
            } else if (op == GEAR) {
                gear(segs, 12 * k, 12 * k, k, hw);
            }
        }

        int[] px = new int[size * size];
        int[] a = new int[size * size]; // coverage 0..255
        float[] s = segs.v;
        for (int j = 0; j < segs.n; j += 5) {
            float x0 = s[j], y0 = s[j + 1], x1 = s[j + 2], y1 = s[j + 3], h = s[j + 4];
            float reach = h + 0.5f;
            int left = Math.max(0, (int) (Math.min(x0, x1) - reach));
            int right = Math.min(size - 1, (int) (Math.max(x0, x1) + reach));
            int top = Math.max(0, (int) (Math.min(y0, y1) - reach));
            int bottom = Math.min(size - 1, (int) (Math.max(y0, y1) + reach));
            float dx = x1 - x0, dy = y1 - y0;
            float len2 = dx * dx + dy * dy;
            for (int y = top; y <= bottom; y++) {
                float py = y + 0.5f;
                for (int x = left; x <= right; x++) {
                    float pxf = x + 0.5f;
                    float t = len2 > 0 ? ((pxf - x0) * dx + (py - y0) * dy) / len2 : 0;
                    t = t < 0 ? 0 : t > 1 ? 1 : t;
                    float ex = pxf - x0 - t * dx, ey = py - y0 - t * dy;
                    float d2 = ex * ex + ey * ey;
                    if (d2 >= reach * reach) {
                        continue;
                    }
                    float c = reach - (float) Math.sqrt(d2); // the pixel's coverage, roughly
                    int v = c >= 1 ? 255 : (int) (c * 255);
                    int p = y * size + x;
                    if (v > a[p]) {
                        a[p] = v;
                    }
                }
            }
        }
        int rgb = color & 0xFFFFFF;
        for (int p = 0; p < px.length; p++) {
            px[p] = (a[p] << 24) | rgb;
        }
        return Image.createRGBImage(px, size, size, true);
    }

    /** A circular arc as short segments. */
    private static void arc(Seg segs, float cx, float cy, float r, int from, int to, float hw) {
        int n = Math.max(2, (int) (r * (to - from) / 90)); // about one segment per 1.5 px of arc
        double prevA = Math.toRadians(from);
        for (int i = 1; i <= n; i++) {
            double a = Math.toRadians(from + (to - from) * i / (double) n);
            segs.add(cx + (float) (r * Math.cos(prevA)), cy - (float) (r * Math.sin(prevA)),
                    cx + (float) (r * Math.cos(a)), cy - (float) (r * Math.sin(a)), hw);
            prevA = a;
        }
    }

    /** Eight teeth: root radius 7.5, tip radius 10 grid units; teeth narrower at the tip. */
    private static void gear(Seg segs, float cx, float cy, float k, float hw) {
        int[] deg = { -15, -9, 9, 15 };
        float[] rad = { 7.5f, 10, 10, 7.5f };
        float lx = 0, ly = 0;
        boolean first = true;
        for (int t = 0; t <= 8; t++) {
            for (int p = 0; p < 4 && (t < 8 || p == 0); p++) {
                double a = Math.toRadians(t * 45 + deg[p]);
                float x = cx + (float) (rad[p] * k * Math.cos(a)), y = cy - (float) (rad[p] * k * Math.sin(a));
                if (!first) {
                    segs.add(lx, ly, x, y, hw);
                }
                if (p == 3) {
                    arc(segs, cx, cy, 7.5f * k, t * 45 + 15, t * 45 + 30, hw); // root between teeth
                    double b = Math.toRadians(t * 45 + 30);
                    x = cx + (float) (7.5f * k * Math.cos(b));
                    y = cy - (float) (7.5f * k * Math.sin(b));
                }
                lx = x;
                ly = y;
                first = false;
            }
        }
    }

    /** A growable float list of segments. */
    private static final class Seg {
        float[] v = new float[160];
        int n;

        void add(float x0, float y0, float x1, float y1, float hw) {
            if (n + 5 > v.length) {
                float[] w = new float[v.length * 2];
                System.arraycopy(v, 0, w, 0, n);
                v = w;
            }
            v[n++] = x0;
            v[n++] = y0;
            v[n++] = x1;
            v[n++] = y1;
            v[n++] = hw;
        }
    }
}

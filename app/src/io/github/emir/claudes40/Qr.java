package io.github.emir.claudes40;

/**
 * A small QR code encoder for the shop link (QrPage): byte mode, error
 * correction level M, versions 1 to 6 (up to 106 bytes; no version
 * information blocks needed), the mask with the lowest penalty. Follows
 * ISO/IEC 18004 the way Project Nayuki's reference encoder does.
 */
final class Qr {

    /** Error correction codewords per block and number of blocks, level M, index = version. */
    private static final int[] ECC_PER_BLOCK = { -1, 10, 16, 26, 18, 24, 16 };
    private static final int[] BLOCKS = { -1, 1, 1, 1, 2, 2, 4 };
    private static final int MAX_VERSION = 6;

    private final int size;
    /** [y][x], true = dark. */
    private final boolean[][] modules;
    private final boolean[][] function;

    private Qr(int version) {
        size = version * 4 + 17;
        modules = new boolean[size][size];
        function = new boolean[size][size];
    }

    /** The modules ([y][x], true = dark) of text's UTF-8 bytes, or null if it is too long. */
    static boolean[][] encode(String text) {
        byte[] data;
        try {
            data = text.getBytes("UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            return null;
        }
        for (int v = 1; v <= MAX_VERSION; v++) {
            int capacity = dataCodewords(v) * 8;
            if (4 + 8 + data.length * 8 <= capacity) {
                Qr q = new Qr(v);
                q.draw(v, codewords(v, data));
                return q.modules;
            }
        }
        return null;
    }

    private static int rawCodewords(int v) {
        int bits = (16 * v + 128) * v + 64;
        if (v >= 2) {
            int n = v / 7 + 2;
            bits -= (25 * n - 10) * n - 55;
        }
        return bits / 8;
    }

    private static int dataCodewords(int v) {
        return rawCodewords(v) - ECC_PER_BLOCK[v] * BLOCKS[v];
    }

    /** Data with header, terminator and padding, split into blocks with their ECC, interleaved. */
    private static byte[] codewords(int v, byte[] data) {
        int cap = dataCodewords(v);
        byte[] d = new byte[cap];
        int[] bit = { 0 };
        put(d, bit, 4, 4); // byte mode
        put(d, bit, data.length, 8);
        for (int i = 0; i < data.length; i++) {
            put(d, bit, data[i] & 0xFF, 8);
        }
        put(d, bit, 0, Math.min(4, cap * 8 - bit[0])); // terminator
        int n = (bit[0] + 7) / 8;
        for (int pad = 0xEC; n < cap; pad ^= 0xEC ^ 0x11) {
            d[n++] = (byte) pad;
        }

        int blocks = BLOCKS[v];
        int ecc = ECC_PER_BLOCK[v];
        int raw = rawCodewords(v);
        int shortBlocks = blocks - raw % blocks;
        int shortLen = raw / blocks; // data + ecc of a short block
        byte[] div = divisor(ecc);
        byte[][] bl = new byte[blocks][];
        for (int i = 0, k = 0; i < blocks; i++) {
            int len = shortLen - ecc + (i < shortBlocks ? 0 : 1);
            byte[] dat = new byte[len];
            System.arraycopy(d, k, dat, 0, len);
            k += len;
            byte[] rem = remainder(dat, div);
            byte[] b = new byte[shortLen + 1];
            System.arraycopy(dat, 0, b, 0, len);
            System.arraycopy(rem, 0, b, shortLen + 1 - ecc, ecc);
            bl[i] = b;
        }
        byte[] out = new byte[raw];
        for (int i = 0, k = 0; i < shortLen + 1; i++) {
            for (int j = 0; j < blocks; j++) {
                // a short block has no byte at index shortLen - ecc
                if (i != shortLen - ecc || j >= shortBlocks) {
                    out[k++] = bl[j][i];
                }
            }
        }
        return out;
    }

    private static void put(byte[] d, int[] bit, int value, int len) {
        for (int i = len - 1; i >= 0; i--, bit[0]++) {
            if (((value >>> i) & 1) != 0) {
                d[bit[0] >>> 3] |= (byte) (0x80 >>> (bit[0] & 7));
            }
        }
    }

    private static int mul(int x, int y) {
        int z = 0;
        for (int i = 7; i >= 0; i--) {
            z = (z << 1) ^ ((z >>> 7) * 0x11D);
            z ^= ((y >>> i) & 1) * x;
        }
        return z;
    }

    private static byte[] divisor(int degree) {
        byte[] r = new byte[degree];
        r[degree - 1] = 1;
        int root = 1;
        for (int i = 0; i < degree; i++) {
            for (int j = 0; j < degree; j++) {
                r[j] = (byte) mul(r[j] & 0xFF, root);
                if (j + 1 < degree) {
                    r[j] ^= r[j + 1];
                }
            }
            root = mul(root, 2);
        }
        return r;
    }

    private static byte[] remainder(byte[] data, byte[] div) {
        byte[] r = new byte[div.length];
        for (int i = 0; i < data.length; i++) {
            int factor = (data[i] ^ r[0]) & 0xFF;
            System.arraycopy(r, 1, r, 0, r.length - 1);
            r[r.length - 1] = 0;
            for (int j = 0; j < r.length; j++) {
                r[j] ^= (byte) mul(div[j] & 0xFF, factor);
            }
        }
        return r;
    }

    private void set(int x, int y, boolean dark) {
        modules[y][x] = dark;
        function[y][x] = true;
    }

    private void draw(int v, byte[] cw) {
        for (int i = 0; i < size; i++) {
            set(6, i, i % 2 == 0);
            set(i, 6, i % 2 == 0);
        }
        finder(3, 3);
        finder(size - 4, 3);
        finder(3, size - 4);
        if (v >= 2) {
            int p = size - 7;
            for (int dy = -2; dy <= 2; dy++) {
                for (int dx = -2; dx <= 2; dx++) {
                    set(p + dx, p + dy, Math.max(Math.abs(dx), Math.abs(dy)) != 1);
                }
            }
        }
        format(0); // reserves the format areas

        int i = 0;
        for (int right = size - 1; right >= 1; right -= 2) {
            if (right == 6) {
                right = 5;
            }
            for (int vert = 0; vert < size; vert++) {
                for (int j = 0; j < 2; j++) {
                    int x = right - j;
                    boolean up = ((right + 1) & 2) == 0;
                    int y = up ? size - 1 - vert : vert;
                    if (!function[y][x] && i < cw.length * 8) {
                        modules[y][x] = ((cw[i >>> 3] >>> (7 - (i & 7))) & 1) != 0;
                        i++;
                    }
                }
            }
        }

        int best = 0;
        int bestPenalty = Integer.MAX_VALUE;
        for (int m = 0; m < 8; m++) {
            mask(m);
            format(m);
            int p = penalty();
            if (p < bestPenalty) {
                best = m;
                bestPenalty = p;
            }
            mask(m); // XOR again: undone
        }
        mask(best);
        format(best);
    }

    private void finder(int cx, int cy) {
        for (int dy = -4; dy <= 4; dy++) {
            for (int dx = -4; dx <= 4; dx++) {
                int x = cx + dx;
                int y = cy + dy;
                if (x >= 0 && x < size && y >= 0 && y < size) {
                    int dist = Math.max(Math.abs(dx), Math.abs(dy));
                    set(x, y, dist != 2 && dist != 4);
                }
            }
        }
    }

    private void format(int mask) {
        int data = mask; // level M = 0 in the two top bits
        int rem = data;
        for (int i = 0; i < 10; i++) {
            rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
        }
        int bits = ((data << 10) | rem) ^ 0x5412;
        for (int i = 0; i <= 5; i++) {
            set(8, i, bit(bits, i));
        }
        set(8, 7, bit(bits, 6));
        set(8, 8, bit(bits, 7));
        set(7, 8, bit(bits, 8));
        for (int i = 9; i < 15; i++) {
            set(14 - i, 8, bit(bits, i));
        }
        for (int i = 0; i < 8; i++) {
            set(size - 1 - i, 8, bit(bits, i));
        }
        for (int i = 8; i < 15; i++) {
            set(8, size - 15 + i, bit(bits, i));
        }
        set(8, size - 8, true); // the dark module
    }

    private static boolean bit(int x, int i) {
        return ((x >>> i) & 1) != 0;
    }

    private void mask(int m) {
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                boolean inv;
                switch (m) {
                case 0: inv = (x + y) % 2 == 0; break;
                case 1: inv = y % 2 == 0; break;
                case 2: inv = x % 3 == 0; break;
                case 3: inv = (x + y) % 3 == 0; break;
                case 4: inv = (x / 3 + y / 2) % 2 == 0; break;
                case 5: inv = x * y % 2 + x * y % 3 == 0; break;
                case 6: inv = (x * y % 2 + x * y % 3) % 2 == 0; break;
                default: inv = ((x + y) % 2 + x * y % 3) % 2 == 0; break;
                }
                if (inv && !function[y][x]) {
                    modules[y][x] = !modules[y][x];
                }
            }
        }
    }

    private boolean dark(int x, int y, boolean rows) {
        return rows ? modules[y][x] : modules[x][y];
    }

    /** The standard's penalty: runs, 2x2 blocks, finder-like patterns, dark balance. */
    private int penalty() {
        int p = 0;
        for (int pass = 0; pass < 2; pass++) {
            boolean rows = pass == 0;
            for (int a = 0; a < size; a++) {
                int run = 1;
                for (int b = 1; b < size; b++) {
                    if (dark(b, a, rows) == dark(b - 1, a, rows)) {
                        run++;
                        if (run == 5) {
                            p += 3;
                        } else if (run > 5) {
                            p++;
                        }
                    } else {
                        run = 1;
                    }
                }
                for (int b = 0; b + 11 <= size; b++) {
                    if (finderLike(a, b, rows, false) || finderLike(a, b, rows, true)) {
                        p += 40;
                    }
                }
            }
        }
        int darkCount = 0;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                boolean c = modules[y][x];
                if (c) {
                    darkCount++;
                }
                if (x + 1 < size && y + 1 < size && c == modules[y][x + 1] && c == modules[y + 1][x]
                        && c == modules[y + 1][x + 1]) {
                    p += 3;
                }
            }
        }
        int total = size * size;
        int k = (Math.abs(darkCount * 20 - total * 10) + total - 1) / total - 1;
        return p + Math.max(0, k) * 10;
    }

    /** 1011101 with four light modules before (reversed) or after it, at b in line a. */
    private boolean finderLike(int a, int b, boolean rows, boolean reversed) {
        final String pat = "10111010000";
        for (int i = 0; i < 11; i++) {
            char want = pat.charAt(reversed ? 10 - i : i);
            if (dark(b + i, a, rows) != (want == '1')) {
                return false;
            }
        }
        return true;
    }
}

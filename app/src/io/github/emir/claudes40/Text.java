package io.github.emir.claudes40;

import java.util.Calendar;
import java.util.Date;
import java.util.Random;
import java.util.TimeZone;
import java.util.Vector;

import javax.microedition.lcdui.Font;

/** Small text helpers (CLDC 1.1 has no formatter, no StringBuilder). */
final class Text {

    private static final Random RANDOM = new Random();
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private Text() {
    }

    /** Wraps text to the given pixel width; honours '\n'; splits long words. */
    static void wrap(String text, Font font, int width, Vector out) {
        int n = text.length();
        int start = 0;
        while (start <= n) {
            int nl = text.indexOf('\n', start);
            int end = nl < 0 ? n : nl;
            wrapLine(text.substring(start, end), font, width, out);
            if (nl < 0) {
                break;
            }
            start = nl + 1;
        }
    }

    private static void wrapLine(String s, Font font, int width, Vector out) {
        if (s.length() == 0) {
            out.addElement("");
            return;
        }
        int pos = 0;
        int n = s.length();
        while (pos < n) {
            int end = pos;
            int lastSpace = -1;
            while (end < n && font.substringWidth(s, pos, end + 1 - pos) <= width) {
                if (s.charAt(end) == ' ') {
                    lastSpace = end;
                }
                end++;
            }
            if (end == n) {
                out.addElement(s.substring(pos));
                return;
            }
            if (end == pos) {
                end = pos + 1; // wider than the screen: one character per line
            } else if (lastSpace > pos) {
                end = lastSpace + 1;
            }
            out.addElement(trimEnd(s.substring(pos, end)));
            pos = end;
        }
    }

    private static String trimEnd(String s) {
        int e = s.length();
        while (e > 0 && s.charAt(e - 1) == ' ') {
            e--;
        }
        return s.substring(0, e);
    }

    /** Request id accepted by the gateway: [A-Za-z0-9-]{8,40}. */
    static String requestId() {
        long a = RANDOM.nextLong() ^ System.currentTimeMillis();
        long b = RANDOM.nextLong();
        StringBuffer sb = new StringBuffer("s40-");
        appendHex(sb, a);
        sb.append('-');
        appendHex(sb, b);
        return sb.toString();
    }

    private static void appendHex(StringBuffer sb, long v) {
        for (int i = 60; i >= 0; i -= 4) {
            sb.append(HEX[(int) (v >>> i) & 0xF]);
        }
    }

    static String date(long ms) {
        if (ms <= 0) {
            return "-";
        }
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("GMT"));
        c.setTime(new Date(ms));
        return c.get(Calendar.YEAR) + "-" + two(c.get(Calendar.MONTH) + 1) + "-" + two(c.get(Calendar.DAY_OF_MONTH));
    }

    /** Phone-local "HH:mm" for today, otherwise "dd.MM"; "-" if unknown. */
    static String shortDate(long ms) {
        if (ms <= 0) {
            return "-";
        }
        Calendar now = Calendar.getInstance();
        Calendar c = Calendar.getInstance();
        c.setTime(new Date(ms));
        if (c.get(Calendar.YEAR) == now.get(Calendar.YEAR) && c.get(Calendar.MONTH) == now.get(Calendar.MONTH)
                && c.get(Calendar.DAY_OF_MONTH) == now.get(Calendar.DAY_OF_MONTH)) {
            return two(c.get(Calendar.HOUR_OF_DAY)) + ":" + two(c.get(Calendar.MINUTE));
        }
        return two(c.get(Calendar.DAY_OF_MONTH)) + "." + two(c.get(Calendar.MONTH) + 1);
    }

    private static String two(int v) {
        return v < 10 ? "0" + v : String.valueOf(v);
    }

    static int parseInt(String s, int fallback) {
        try {
            return Integer.parseInt(s.trim());
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    /** Code point count is not needed: the phone and the server both limit by UTF-16 chars / characters of the BMP. */
    static String clip(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}

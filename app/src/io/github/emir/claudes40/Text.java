package io.github.emir.claudes40;

import java.util.Calendar;
import java.util.Date;
import java.util.Random;
import java.util.TimeZone;
import java.util.Vector;

import javax.microedition.lcdui.Font;
import javax.microedition.lcdui.Graphics;

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

    /**
     * One laid-out line of a reply. `gap` lines are the half-height space
     * between paragraphs (no text). `dot` / `number` mark the first line of
     * a list item; its marker is drawn at `mx`, the text (and the item's
     * following lines) at `x`. `off` is the index in the source text where
     * the line starts, used to keep the reading position across layouts.
     */
    static final class Line {
        String s;
        int x;
        int mx;
        boolean dot;
        String number;
        boolean gap;
        int off;
    }

    /**
     * Wraps a reply for display: paragraphs (blank lines) become a gap,
     * "- " items get a dot and "1. " / "1) " items their number, both with a
     * hanging indent; two leading spaces per nesting level (at most 2).
     * Adds Text.Line objects to `out`.
     */
    static void layout(String text, Font font, int width, Vector out) {
        int n = text.length();
        int start = 0;
        boolean pendingGap = false;
        int em = Math.max(4, font.charWidth('m'));
        Vector tmp = new Vector();
        while (start <= n) {
            int nl = text.indexOf('\n', start);
            int end = nl < 0 ? n : nl;
            String raw = text.substring(start, end);
            int sp = 0;
            while (sp < raw.length() && raw.charAt(sp) == ' ') {
                sp++;
            }
            if (sp == raw.length()) {
                pendingGap = out.size() > 0;
            } else {
                if (pendingGap) {
                    Line g = new Line();
                    g.gap = true;
                    g.s = "";
                    g.off = start;
                    out.addElement(g);
                    pendingGap = false;
                }
                int level = Math.min(2, sp / 2);
                int base = level * em;
                String body = raw.substring(sp);
                int bodyOff = start + sp;
                boolean dot = body.startsWith("- ") && body.length() > 2;
                String number = dot ? null : listNumber(body);
                int hang = base;
                if (dot) {
                    body = body.substring(2);
                    bodyOff += 2;
                    hang = base + Math.max(em, font.charWidth(' ') * 3);
                } else if (number != null) {
                    body = body.substring(number.length() + 1);
                    bodyOff += number.length() + 1;
                    hang = base + font.stringWidth(number + " ");
                }
                if (hang > width / 2) {
                    // too narrow for an indent: the item as plain text, marker included
                    dot = false;
                    number = null;
                    body = raw.substring(sp);
                    bodyOff = start + sp;
                    hang = base = 0;
                }
                tmp.removeAllElements();
                wrapLine(body, font, Math.max(font.charWidth('W'), width - hang), tmp);
                int pos = bodyOff;
                for (int i = 0; i < tmp.size(); i++) {
                    Line l = new Line();
                    l.s = (String) tmp.elementAt(i);
                    l.x = hang;
                    l.mx = base;
                    if (i == 0) {
                        l.dot = dot;
                        l.number = number;
                    }
                    // wrapLine keeps characters in order and drops only spaces at line ends
                    int at = text.indexOf(l.s, pos);
                    l.off = at >= 0 && at <= end ? at : pos;
                    pos = l.off + l.s.length();
                    out.addElement(l);
                }
            }
            if (nl < 0) {
                break;
            }
            start = nl + 1;
        }
    }

    /** "12" for "12. text" or "12) text" (1-3 digits); the dot or bracket is kept. */
    private static String listNumber(String s) {
        int i = 0;
        while (i < s.length() && i < 3 && s.charAt(i) >= '0' && s.charAt(i) <= '9') {
            i++;
        }
        if (i == 0 || i + 1 >= s.length() || (s.charAt(i) != '.' && s.charAt(i) != ')') || s.charAt(i + 1) != ' '
                || i + 2 >= s.length()) {
            return null;
        }
        return s.substring(0, i + 1);
    }

    /** Height of laid-out lines: text lines are a font line, paragraph gaps half of one. */
    static int lineH(Line l, Font font) {
        return l.gap ? font.getHeight() / 2 : font.getHeight();
    }

    /** Draws one laid-out line at (x0, y) in the current colour. */
    static void draw(Graphics g, Line l, Font font, int x0, int y) {
        if (l.gap) {
            return;
        }
        if (l.dot) {
            int d = Math.max(4, font.getHeight() / 4);
            int cy = y + font.getBaselinePosition() - font.getBaselinePosition() * 3 / 8;
            g.fillArc(x0 + l.mx + 1, cy - d / 2, d, d, 0, 360);
        } else if (l.number != null) {
            g.drawString(l.number, x0 + l.mx, y, Graphics.TOP | Graphics.LEFT);
        }
        g.drawString(l.s, x0 + l.x, y, Graphics.TOP | Graphics.LEFT);
    }

    /** `s` if it fits in `width` pixels, otherwise cut with "..." at the end. */
    static String fit(String s, Font font, int width) {
        if (s == null || font.stringWidth(s) <= width) {
            return s;
        }
        int dots = font.stringWidth("...");
        int n = s.length();
        while (n > 0 && font.substringWidth(s, 0, n) + dots > width) {
            n--;
        }
        return trimEnd(s.substring(0, n)) + "...";
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

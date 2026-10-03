package io.github.emir.claudes40;

import java.util.Vector;

import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.Font;
import javax.microedition.lcdui.Graphics;

/**
 * What the three keys under the screen do, drawn by the app at the bottom
 * of a full-screen canvas (Settings > Screen > Full screen), where the
 * phone may show no labels of its own: left softkey, centre key (with a
 * key-cap mark), right softkey. Without full screen the phone's own bar
 * is there and nothing is drawn.
 *
 * The canvas reports its commands (add/remove) and, where its centre key
 * is its own key handling rather than a command, what that does now
 * (centre). The split follows the Series 40 rules: BACK/EXIT/CANCEL/STOP
 * on the right, the first OK/ITEM command in the centre, the rest on the
 * left ("Options" when more than one).
 */
final class KeyBar {

    private final Vector cmds = new Vector();
    private String centre;

    synchronized void add(Command c) {
        if (!cmds.contains(c)) {
            cmds.addElement(c);
        }
    }

    synchronized void remove(Command c) {
        cmds.removeElement(c);
    }

    /** The centre key's action when the canvas handles it itself (null: from the commands). */
    synchronized void centre(String label) {
        centre = label;
    }

    /** Height taken at the bottom (0 when the phone's own bar is shown). */
    static int height() {
        return RowList.fullScreen ? Theme.small.getHeight() + 6 : 0;
    }

    private static boolean right(Command c) {
        int t = c.getCommandType();
        return t == Command.BACK || t == Command.EXIT || t == Command.CANCEL || t == Command.STOP;
    }

    private static boolean ok(Command c) {
        int t = c.getCommandType();
        return t == Command.OK || t == Command.ITEM;
    }

    /** Draws the bar along the bottom of a w x h canvas. */
    void paint(Graphics g, int w, int h) {
        int bh = height();
        if (bh == 0) {
            return;
        }
        Command r = null;
        Command c = null;
        Vector rest = new Vector();
        String mid;
        boolean own;
        synchronized (this) {
            own = centre != null;
            for (int i = 0; i < cmds.size(); i++) {
                Command k = (Command) cmds.elementAt(i);
                if (right(k)) {
                    if (r == null || k.getPriority() < r.getPriority()) {
                        r = k;
                    }
                } else if (ok(k) && (c == null || k.getPriority() < c.getPriority())) {
                    c = k;
                }
            }
            for (int i = 0; i < cmds.size(); i++) {
                Command k = (Command) cmds.elementAt(i);
                if (k != r && k != c && !right(k)) {
                    rest.addElement(k);
                }
            }
            mid = centre != null ? centre : c != null ? c.getLabel() : "";
        }
        if (own && c != null) {
            rest.addElement(c); // the centre key does something else; the command is under Options
        }
        String left = rest.size() == 0 ? "" : rest.size() == 1 ? ((Command) rest.elementAt(0)).getLabel()
                : L.t("Options");
        String right = r != null ? r.getLabel() : "";

        int y = h - bh;
        g.setClip(0, y, w, bh);
        g.setColor(Theme.chrome);
        g.fillRect(0, y, w, bh);
        g.setColor(Theme.border);
        g.drawLine(0, y, w, y);
        Font sm = Theme.small;
        int ty = y + (bh - sm.getHeight()) / 2 + 1;
        int third = w / 3;
        g.setFont(sm);
        g.setColor(Theme.ink);
        g.drawString(Text.fit(left, sm, third - 6), 4, ty, Graphics.TOP | Graphics.LEFT);
        g.drawString(Text.fit(right, sm, third - 6), w - 4, ty, Graphics.TOP | Graphics.RIGHT);
        if (mid.length() > 0) {
            Font b = Theme.bold.getHeight() <= sm.getHeight() + 2 ? Theme.bold : sm;
            int cap = sm.getHeight() - 4; // a key cap with a dot: the centre key
            String t = Text.fit(mid, b, third + 20 - cap - 4);
            int tw = cap + 4 + b.stringWidth(t);
            int x = (w - tw) / 2;
            int cy = y + (bh - cap) / 2 + 1;
            g.setColor(Theme.accent);
            g.drawRoundRect(x, cy, cap - 1, cap - 1, 4, 4);
            int d = Math.max(2, cap / 3);
            g.fillArc(x + (cap - d) / 2, cy + (cap - d) / 2, d, d, 0, 360);
            g.setFont(b);
            g.drawString(t, x + cap + 4, ty, Graphics.TOP | Graphics.LEFT);
        }
        g.setClip(0, 0, w, h);
    }

    /** A key in a text: [0]..[9], [*], [#], and [\u2022] for the centre key. */
    private static boolean key(String s, int k) {
        if (k + 2 >= s.length() || s.charAt(k) != '[' || s.charAt(k + 2) != ']') {
            return false;
        }
        char c = s.charAt(k + 1);
        return c >= '0' && c <= '9' || c == '*' || c == '#' || c == '\u2022';
    }

    /**
     * Draws one line (TOP | LEFT at x, y in the current font and colour) with
     * every key token as a small key cap of the token's own width, so the
     * wrapping and fitting done on the plain text stay right.
     */
    static void text(Graphics g, String s, int x, int y) {
        Font f = g.getFont();
        int color = g.getColor();
        int i = 0;
        while (i < s.length()) {
            int k = i;
            while (k < s.length() && !key(s, k)) {
                k++;
            }
            if (k > i) {
                String part = s.substring(i, k);
                g.drawString(part, x, y, Graphics.TOP | Graphics.LEFT);
                x += f.stringWidth(part);
            }
            if (k >= s.length()) {
                break;
            }
            int tw = f.stringWidth(s.substring(k, k + 3));
            int fh = f.getHeight();
            char c = s.charAt(k + 1);
            // a filled cap in the accent colour, the key's sign in the accent's ink
            g.setColor(Theme.accent);
            g.fillRoundRect(x, y + 1, tw - 1, fh - 2, 4, 4);
            g.setColor(Theme.accentInk);
            if (c == '\u2022') {
                int d = Math.max(3, fh / 4);
                g.fillArc(x + (tw - 1 - d) / 2, y + (fh - d) / 2, d, d, 0, 360);
            } else {
                g.drawChar(c, x + tw / 2, y + 1, Graphics.TOP | Graphics.HCENTER);
            }
            g.setColor(color);
            x += tw;
            i = k + 3;
        }
    }
}

package io.github.emir.claudes40;

import java.util.Vector;

import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.CommandListener;
import javax.microedition.lcdui.Display;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.Font;
import javax.microedition.lcdui.Graphics;

/**
 * A page of text drawn like the lists, in place of a Form that only shows
 * text: the title bar of RowList, then items of a small muted label and
 * wrapped text (or a large value, or a progress bar). An item that waits
 * for something (setBusy) has a moving spinner before its text. UP/DOWN scroll.
 * Items can change from worker threads; every method is synchronized.
 * Follows Settings > Screen > Full screen like RowList.
 */
final class TextPage extends Canvas {

    private static final int MARGIN = 8;

    private String title;
    private final Vector items = new Vector();
    private int scroll;
    private int contentH;
    private boolean full;
    /** Gets the digit keys and Clear (a credit code typed on the page), or null. */
    private Digits digits;

    /** The keys of a page that is typed on: 0..9, and Clear / Backspace. */
    interface Digits {
        void digit(char c);

        void clear();
    }

    private static final class Item {
        String label;
        String text;
        boolean big;
        boolean busy;
        int value = -1; // a progress bar when >= 0
        int max;
    }

    /** A page with one text: a question for Yes/No commands, or a message. */
    static TextPage message(String title, String text) {
        TextPage p = new TextPage(title);
        p.append(null, text);
        return p;
    }

    /** A message with "Tamam" (OK) that returns to next; in place of an Alert. */
    static void notice(final Display display, String title, String text, final Displayable next) {
        TextPage p = message(title, text);
        final Command ok = new Command(L.t("OK"), Command.OK, 1);
        p.addCommand(ok);
        p.setCommandListener(new CommandListener() {
            public void commandAction(Command c, Displayable d) {
                display.setCurrent(next);
            }
        });
        display.setCurrent(p);
    }

    TextPage(String title) {
        this.title = title;
        full = RowList.fullScreen;
        setFullScreenMode(full);
    }

    protected void showNotify() {
        if (full != RowList.fullScreen) {
            full = RowList.fullScreen;
            setFullScreenMode(full);
        }
    }

    /** The page's own title bar (no native title over it). */
    public synchronized void setTitle(String t) {
        title = t;
        repaint();
    }

    public synchronized String getTitle() {
        return title;
    }

    /** Adds an item (label may be null). Returns its index. */
    synchronized int append(String label, String text) {
        Item it = new Item();
        it.label = label;
        it.text = text == null ? "" : text;
        items.addElement(it);
        repaint();
        return items.size() - 1;
    }

    /** The item's text in a large bold font (a code, a balance). */
    synchronized void setBig(int i) {
        ((Item) items.elementAt(i)).big = true;
        repaint();
    }

    /** A spinner before the item's text while on (a request under way). */
    synchronized void setBusy(int i, boolean on) {
        if (i >= 0 && i < items.size()) {
            ((Item) items.elementAt(i)).busy = on;
            repaint();
        }
    }

    /** The item's text with the spinner on: what is being waited for. */
    synchronized void waiting(int i, String text) {
        setText(i, text);
        setBusy(i, true);
    }

    /** The item's text with the spinner off: the outcome. */
    synchronized void done(int i, String text) {
        setText(i, text);
        setBusy(i, false);
    }

    synchronized void setText(int i, String text) {
        if (i >= 0 && i < items.size()) {
            ((Item) items.elementAt(i)).text = text == null ? "" : text;
            repaint();
        }
    }

    synchronized void setLabel(int i, String label) {
        if (i >= 0 && i < items.size()) {
            ((Item) items.elementAt(i)).label = label;
            repaint();
        }
    }

    /** The item as a progress bar, value of max (its label is shown above it). */
    synchronized void setProgress(int i, int value, int max) {
        Item it = (Item) items.elementAt(i);
        it.value = Math.max(0, Math.min(value, max));
        it.max = Math.max(1, max);
        repaint();
    }

    synchronized int size() {
        return items.size();
    }

    synchronized void delete(int i) {
        items.removeElementAt(i);
        repaint();
    }

    synchronized void deleteAll() {
        items.removeAllElements();
        scroll = 0;
        repaint();
    }

    synchronized void setDigits(Digits d) {
        digits = d;
    }

    protected void keyPressed(int keyCode) {
        int k = Keys.map(keyCode);
        Digits d;
        synchronized (this) {
            d = digits;
        }
        if (d != null) {
            if (k >= KEY_NUM0 && k <= KEY_NUM9) {
                d.digit((char) ('0' + k - KEY_NUM0));
                return;
            }
            if (k == -8 || k == 8) { // Nokia's Clear key, Backspace on QWERTY
                d.clear();
                return;
            }
        }
        int action = Keys.action(this, k);
        if (action == UP || action == DOWN) {
            scrollBy(action == UP ? -1 : 1);
        }
    }

    protected void keyRepeated(int keyCode) {
        int k = Keys.map(keyCode);
        if (k >= KEY_NUM0 && k <= KEY_NUM9 && digits != null) {
            return; // a held digit is typed once
        }
        keyPressed(keyCode);
    }

    private synchronized void scrollBy(int d) {
        scroll += d * Theme.font.getHeight() * 3;
        repaint();
    }

    private static Font bigFont() {
        return Font.getFont(Font.FACE_PROPORTIONAL, Font.STYLE_BOLD, Font.SIZE_LARGE);
    }

    protected synchronized void paint(Graphics g) {
        int w = getWidth();
        int h = getHeight();
        Font f = Theme.font;
        Font sm = Theme.small;
        g.setColor(Theme.bg);
        g.fillRect(0, 0, w, h);
        int top = RowList.barH();
        int area = h - top;
        int tw = w - 2 * MARGIN - 4;
        int maxScroll = Math.max(0, contentH - area);
        scroll = Math.max(0, Math.min(scroll, maxScroll));

        int y = top + 8 - scroll;
        boolean anyBusy = false;
        for (int i = 0; i < items.size(); i++) {
            Item it = (Item) items.elementAt(i);
            if (it.label != null && it.label.length() > 0) {
                g.setFont(sm);
                g.setColor(Theme.muted);
                Vector ls = new Vector();
                Text.wrap(it.label, sm, tw, ls);
                for (int k = 0; k < ls.size(); k++) {
                    g.drawString((String) ls.elementAt(k), MARGIN, y, Graphics.TOP | Graphics.LEFT);
                    y += sm.getHeight();
                }
                y += 1;
            }
            if (it.value >= 0) {
                int bh = 8;
                g.setColor(Theme.border);
                g.fillRoundRect(MARGIN, y + 2, tw, bh, bh, bh);
                int fw = tw * it.value / it.max;
                if (fw > 0) {
                    g.setColor(Theme.accent);
                    g.fillRoundRect(MARGIN, y + 2, Math.max(bh, fw), bh, bh, bh);
                }
                y += bh + 4;
            }
            if (it.text.length() > 0) {
                Font tf = it.big ? bigFont() : f;
                int indent = 0;
                if (it.busy) {
                    anyBusy = true;
                    int sz = Math.max(16, Math.min(tf.getHeight() + 4, 24));
                    Busy.spinner(g, MARGIN, y + (tf.getHeight() - sz) / 2, sz);
                    indent = sz + 8;
                }
                g.setFont(tf);
                g.setColor(Theme.ink);
                Vector lines = new Vector();
                Text.wrap(it.text, tf, tw - indent, lines);
                while (lines.size() > 0 && ((String) lines.lastElement()).length() == 0) {
                    lines.removeElementAt(lines.size() - 1); // Form texts ended with "\n"
                }
                for (int k = 0; k < lines.size(); k++) {
                    if (y + tf.getHeight() > top && y < h) {
                        g.drawString((String) lines.elementAt(k), MARGIN + indent, y, Graphics.TOP | Graphics.LEFT);
                    }
                    y += tf.getHeight();
                }
            }
            y += 10;
        }
        contentH = y + scroll - top;

        if (contentH > area && area > 0) {
            int bh = Math.max(12, area * area / contentH);
            int by = top + (area - bh) * scroll / Math.max(1, contentH - area);
            g.setColor(Theme.border);
            g.fillRoundRect(w - 3, by, 2, bh, 2, 2);
        }
        RowList.paintBar(g, w, title);
        if (anyBusy) {
            Busy.on(this);
        } else {
            Busy.off(this);
        }
    }
}

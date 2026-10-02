package io.github.emir.claudes40;

import java.util.Vector;

import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.CommandListener;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.Font;
import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.List;

/**
 * A list drawn like the home menu, in place of the phone's own List: a title
 * bar, rows with an optional line icon, a title and a muted second line,
 * a rounded selection; notes (loading, errors, empty) are wrapped, muted
 * and not selectable. UP/DOWN move (game actions), FIRE delivers the select
 * command (or List.SELECT_COMMAND) to the listener like an IMPLICIT List.
 * Rows may change from worker threads; every method is synchronized.
 */
final class RowList extends Canvas {

    /** Row flags. */
    static final int ACCENT = 1; // the icon always in the accent colour (a pin)
    static final int CHECK = 2;  // a check mark at the right (the current choice)
    static final int SWITCH = 4; // an on/off switch at the right
    static final int ON = 8;     // the switch is on

    private static final int MARGIN = 6;

    private String title;
    private final Vector rows = new Vector();
    private int selected = -1;
    private int items;
    /** Keys 1-9 pick the first nine rows (off where a key press would change something at once). */
    private boolean numbers = true;
    private int scroll;
    private CommandListener listener;
    private Command selectCommand;
    private final Command defaultSelect;

    /** One row: a note when title is null. */
    private static final class Row {
        String title;
        String sub;
        String note;
        boolean section;
        boolean skeleton;
        int item; // the row's place among the selectable rows
        int icon = -1;
        int flags;
    }

    /** Settings > Screen > Full screen, like the home and chat screens (set by the MIDlet). */
    static boolean fullScreen = true;

    private boolean full;

    RowList(String title) {
        this.title = title;
        // a centre-key command, as an IMPLICIT List has; setSelectCommand replaces it
        defaultSelect = new Command(L.s("Seç", "Select"), Command.OK, 1);
        selectCommand = defaultSelect;
        addCommand(selectCommand);
        full = fullScreen;
        setFullScreenMode(full);
    }

    private final Enter enter = new Enter(this);

    protected void showNotify() {
        if (full != fullScreen) { // the setting changed since
            full = fullScreen;
            setFullScreenMode(full);
        }
        enter.start();
    }

    synchronized void title(String t) {
        title = t;
        repaint();
    }

    /** The list's own title bar (no native title over it). */
    public void setTitle(String t) {
        title(t);
    }

    public synchronized String getTitle() {
        return title;
    }

    /** The default select command reaches the listener as List.SELECT_COMMAND, like an IMPLICIT List's. */
    public void setCommandListener(CommandListener l) {
        listener = l;
        super.setCommandListener(l == null ? null : new CommandListener() {
            public void commandAction(Command c, Displayable d) {
                listener.commandAction(c == defaultSelect ? List.SELECT_COMMAND : c, d);
            }
        });
    }

    /** Like List.setSelectCommand: FIRE delivers it; it is also an ordinary command. */
    void setSelectCommand(Command c) {
        removeCommand(selectCommand);
        selectCommand = c;
        addCommand(c);
    }

    synchronized void deleteAll() {
        rows.removeAllElements();
        items = 0;
        selected = -1;
        scroll = 0;
        repaint();
    }

    /** A wrapped, muted line that cannot be selected. */
    synchronized void note(String text) {
        Row r = new Row();
        r.note = text;
        rows.addElement(r);
        repaint();
    }

    synchronized void setNumbers(boolean on) {
        numbers = on;
        repaint();
    }

    /** Grey placeholder rows while the real ones load (not selectable). */
    synchronized void skeleton(int n) {
        for (int i = 0; i < n; i++) {
            Row r = new Row();
            r.skeleton = true;
            rows.addElement(r);
        }
        repaint();
    }

    /** A small heading over the rows that follow (not selectable). */
    synchronized void section(String text) {
        Row r = new Row();
        r.note = text;
        r.section = true;
        rows.addElement(r);
        repaint();
    }

    /** Adds a row; icon is an Icons id or -1. Returns its index. */
    synchronized int add(String title, String sub, int icon, int flags) {
        Row r = new Row();
        r.item = items++;
        r.title = title;
        r.sub = sub != null && sub.length() > 0 ? sub : null;
        r.icon = icon;
        r.flags = flags;
        rows.addElement(r);
        if (selected < 0) {
            selected = rows.size() - 1;
        }
        repaint();
        return rows.size() - 1;
    }

    synchronized int size() {
        return rows.size();
    }

    /** The selected row's index, or -1 (nothing selectable). */
    synchronized int getSelectedIndex() {
        return selected;
    }

    /** The selected row's place among the selectable rows (notes and headings not counted), or -1. */
    synchronized int getSelectedItem() {
        return selected < 0 ? -1 : ((Row) rows.elementAt(selected)).item;
    }

    synchronized void setSelectedIndex(int i, boolean on) {
        if (on && i >= 0 && i < rows.size() && ((Row) rows.elementAt(i)).title != null) {
            selected = i;
            repaint();
        }
    }

    /** FIRE: what an IMPLICIT List does on select. */
    void fire() {
        CommandListener l = listener;
        if (l != null && getSelectedIndex() >= 0) {
            l.commandAction(selectCommand == defaultSelect ? List.SELECT_COMMAND : selectCommand, this);
        }
    }

    protected void keyPressed(int keyCode) {
        keyCode = Keys.map(keyCode);
        if (keyCode >= KEY_NUM1 && keyCode <= KEY_NUM9 && pick(keyCode - KEY_NUM1)) {
            fire();
            return;
        }
        int action = Keys.action(this, keyCode);
        if (action == FIRE) {
            fire();
        } else if (action == UP) {
            move(-1);
        } else if (action == DOWN) {
            move(1);
        }
    }

    protected void keyRepeated(int keyCode) {
        int action = Keys.action(this, keyCode);
        if (keyCode < 0 && (action == UP || action == DOWN)) {
            move(action == UP ? -1 : 1);
        }
    }

    /** Selects the n-th selectable row (0-based); false if numbers are off or there is none. */
    private synchronized boolean pick(int n) {
        if (!numbers) {
            return false;
        }
        for (int i = 0; i < rows.size(); i++) {
            Row r = (Row) rows.elementAt(i);
            if (r.title != null && r.item == n) {
                selected = i;
                repaint();
                return true;
            }
        }
        return false;
    }

    private synchronized void move(int d) {
        int n = rows.size();
        if (n == 0 || selected < 0) {
            return;
        }
        int i = selected;
        for (int k = 0; k < n; k++) {
            i = (i + d + n) % n;
            if (((Row) rows.elementAt(i)).title != null) {
                selected = i;
                break;
            }
        }
        repaint();
    }

    /** Height of the title bar of the drawn lists and pages. */
    static int barH() {
        return Math.max(Theme.bold.getHeight() + 10, 26);
    }

    static void paintBar(Graphics g, int w, String title) {
        int hh = barH();
        g.setColor(Theme.bg);
        g.fillRect(0, 0, w, hh);
        g.setColor(Theme.border);
        g.drawLine(0, hh - 1, w, hh - 1);
        g.setFont(Theme.bold);
        g.setColor(Theme.ink);
        g.drawString(Text.fit(title == null ? "" : title, Theme.bold, w - 2 * MARGIN - 4), MARGIN + 4,
                (hh - Theme.bold.getHeight()) / 2, Graphics.TOP | Graphics.LEFT);
    }

    private int rowH(Row r, int w) {
        Font f = Theme.font;
        Font sm = Theme.small;
        if (r.section) {
            return sm.getHeight() + 10;
        }
        if (r.skeleton) {
            return f.getHeight() + sm.getHeight() + 10;
        }
        if (r.title == null) {
            Vector lines = new Vector();
            Text.wrap(r.note, sm, w - 4 * MARGIN, lines);
            return lines.size() * sm.getHeight() + 16;
        }
        return r.sub != null ? f.getHeight() + sm.getHeight() + 10 : Math.max(f.getHeight() + 14, 26);
    }

    protected synchronized void paint(Graphics g) {
        int w = getWidth();
        int h = getHeight();
        Font f = Theme.font;
        Font sm = Theme.small;
        g.setColor(Theme.bg);
        g.fillRect(0, 0, w, h);

        int n = rows.size();
        int[] ys = new int[n + 1];
        boolean icons = false;
        for (int i = 0; i < n; i++) {
            Row r = (Row) rows.elementAt(i);
            ys[i + 1] = ys[i] + rowH(r, w);
            icons |= r.icon >= 0;
        }
        int top = barH() + 4;
        int area = h - top - 4;
        if (selected >= 0) {
            int first = 0;
            while (first < n && ((Row) rows.elementAt(first)).title == null) {
                first++;
            }
            if (selected == first) {
                scroll = 0; // the notes above the first row stay in view
            }
            if (ys[selected] - scroll < 0) {
                scroll = ys[selected];
            } else if (ys[selected + 1] - scroll > area) {
                scroll = ys[selected + 1] - area;
            }
        }
        scroll = Math.max(0, Math.min(scroll, Math.max(0, ys[n] - area)));

        g.setClip(0, top, w, area + 4);
        int dx = enter.dx();
        g.translate(dx, 0);
        int ic = Math.max(14, Math.min(22, f.getHeight() + 2));
        for (int i = 0; i < n; i++) {
            int y = top + ys[i] - scroll;
            int rh = ys[i + 1] - ys[i];
            if (y + rh < top || y > h) {
                continue;
            }
            Row r = (Row) rows.elementAt(i);
            if (r.skeleton) {
                // bars where a title and its second line will be, shorter each row
                int bw = (w - 2 * MARGIN - 12) * (70 - (i % 3) * 12) / 100;
                g.setColor(Theme.mix(Theme.bg, Theme.border, 200));
                g.fillRoundRect(MARGIN + 6, y + 6, bw, f.getHeight() - 6, 6, 6);
                g.fillRoundRect(MARGIN + 6, y + 6 + f.getHeight(), bw * 3 / 5, sm.getHeight() - 6, 6, 6);
                continue;
            }
            if (r.section) {
                g.setFont(sm);
                g.setColor(Theme.muted);
                g.drawString(Text.fit(r.note, sm, w - 2 * MARGIN - 8), MARGIN + 6, y + 8, Graphics.TOP | Graphics.LEFT);
                continue;
            }
            if (r.title == null) {
                Vector lines = new Vector();
                Text.wrap(r.note, sm, w - 4 * MARGIN, lines);
                g.setFont(sm);
                g.setColor(Theme.muted);
                for (int k = 0; k < lines.size(); k++) {
                    g.drawString((String) lines.elementAt(k), w / 2, y + 8 + k * sm.getHeight(), Graphics.TOP | Graphics.HCENTER);
                }
                continue;
            }
            boolean sel = i == selected;
            if (sel) {
                g.setColor(Theme.selection);
                g.fillRoundRect(MARGIN / 2, y + 1, w - MARGIN, rh - 2, 10, 10);
            }
            int x = MARGIN + 6;
            if (icons) {
                if (r.icon >= 0) {
                    int color = sel || (r.flags & ACCENT) != 0 ? Theme.accent : Theme.muted;
                    g.drawImage(Icons.get(r.icon, ic, color), x + ic / 2, y + rh / 2, Graphics.HCENTER | Graphics.VCENTER);
                }
                x += ic + 8;
            }
            int right = w - MARGIN - 6;
            if ((r.flags & SWITCH) != 0) {
                boolean on = (r.flags & ON) != 0;
                int th = Math.max(12, sm.getHeight() - 2);
                int tw2 = th * 2 - 2;
                int sx = right - tw2;
                int sy = y + (rh - th) / 2;
                g.setColor(on ? Theme.accent : Theme.border);
                g.fillRoundRect(sx, sy, tw2, th, th, th);
                g.setColor(on ? Theme.accentInk : Theme.surface);
                int k = th - 4;
                g.fillArc(on ? sx + tw2 - k - 2 : sx + 2, sy + 2, k, k, 0, 360);
                right -= tw2 + 8;
            }
            if ((r.flags & CHECK) != 0) {
                int cs = Math.max(12, f.getHeight() - 2);
                g.drawImage(Icons.get(Icons.CHECK, cs, Theme.accent), right, y + rh / 2, Graphics.RIGHT | Graphics.VCENTER);
                right -= cs + 6;
            }
            if (numbers && items <= 9 && (r.flags & (SWITCH | CHECK)) == 0) {
                String num = String.valueOf(r.item + 1);
                g.setFont(sm);
                g.setColor(Theme.muted);
                g.drawString(num, right, y + (rh - sm.getHeight()) / 2, Graphics.TOP | Graphics.RIGHT);
                right -= sm.stringWidth(num) + 8;
            }
            int tw = right - x;
            g.setFont(f);
            g.setColor(Theme.ink);
            if (r.sub != null) {
                g.drawString(Text.fit(r.title, f, tw), x, y + 5, Graphics.TOP | Graphics.LEFT);
                g.setFont(sm);
                g.setColor(Theme.muted);
                g.drawString(Text.fit(r.sub, sm, tw), x, y + 5 + f.getHeight(), Graphics.TOP | Graphics.LEFT);
            } else {
                g.drawString(Text.fit(r.title, f, tw), x, y + (rh - f.getHeight()) / 2, Graphics.TOP | Graphics.LEFT);
            }
        }
        g.translate(-dx, 0);
        g.setClip(0, 0, w, h);

        // a thin scroll bar when the rows do not fit
        if (ys[n] > area && area > 0) {
            int bh = Math.max(12, area * area / ys[n]);
            int by = top + (area - bh) * scroll / Math.max(1, ys[n] - area);
            g.setColor(Theme.border);
            g.fillRoundRect(w - 3, by, 2, bh, 2, 2);
        }

        paintBar(g, w, title);
    }
}

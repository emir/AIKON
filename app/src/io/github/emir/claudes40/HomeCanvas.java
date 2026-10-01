package io.github.emir.claudes40;

import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.CommandListener;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.Font;
import javax.microedition.lcdui.Graphics;

/**
 * Main menu with drawn icons. UP/DOWN (game actions) move, FIRE or "Seç"
 * opens, number keys 1-9 (Canvas.KEY_NUMx constants) jump directly; the
 * QWERTY N key (9 after Keys.map) only moves to Exit, so a stray N never
 * quits. On short landscape screens (E63) each row is one line.
 * Softkeys are standard Commands.
 */
final class HomeCanvas extends Canvas implements CommandListener {

    private final String[] titles = {
        L.s("Sohbet", "Chat"), L.s("Sohbetler", "Chats"), L.s("Hızlı sorular", "Quick prompts"),
        L.s("Yeni sohbet", "New chat"), L.s("Kaydedilenler", "Saved"),
        L.s("Bağlantı testi", "Connection test"), L.s("Ayarlar", "Settings"), L.s("Hakkında", "About"),
        L.s("Çıkış", "Exit") };
    private final String[] hints = {
        L.s("Kaldığın yerden devam et", "Pick up where you left off"),
        L.s("Önceki sohbetleri aç", "Open earlier chats"),
        L.s("Web'de ara, çevir, özetle...", "Search the web, translate..."),
        L.s("Temiz bir sayfa aç", "Start fresh"),
        L.s("Telefondaki yanıtlar, internetsiz", "Replies on the phone, offline"),
        L.s("Sunucuya ulaşıyor muyuz?", "Can we reach the server?"),
        L.s("Görünüm, dil, ses, eşleştirme", "Look, language, sound, pairing"),
        L.s("AIKON nedir?", "What is AIKON?"),
        L.s("Görüşmek üzere", "See you soon") };

    private static final int MARGIN = 6;

    private final ClaudeS40MIDlet midlet;
    private final Command selectCmd = new Command(L.s("Seç", "Select"), Command.OK, 1);
    private final Command exitCmd = new Command(L.s("Çıkış", "Exit"), Command.EXIT, 2);
    private int selected;
    private int top;

    HomeCanvas(ClaudeS40MIDlet midlet) {
        this.midlet = midlet;
        addCommand(selectCmd);
        addCommand(exitCmd);
        setCommandListener(this);
    }

    public void commandAction(Command c, Displayable d) {
        if (c == selectCmd) {
            midlet.menuSelected(selected);
        } else if (c == exitCmd) {
            midlet.exit();
        }
    }

    protected void keyPressed(int keyCode) {
        int raw = keyCode;
        keyCode = Keys.map(keyCode);
        if (keyCode >= KEY_NUM1 && keyCode <= KEY_NUM9) {
            selected = keyCode - KEY_NUM1;
            repaint();
            if (selected != titles.length - 1 || raw == KEY_NUM9) { // a stray QWERTY N must not quit
                midlet.menuSelected(selected);
            }
            return;
        }
        int action = Keys.action(this, keyCode);
        if (action == UP) {
            selected = (selected + titles.length - 1) % titles.length;
        } else if (action == DOWN) {
            selected = (selected + 1) % titles.length;
        } else if (action == FIRE) {
            midlet.menuSelected(selected);
            return;
        } else {
            return;
        }
        repaint();
    }

    protected void keyRepeated(int keyCode) {
        int action = Keys.action(this, keyCode);
        if (keyCode < 0 && (action == UP || action == DOWN)) { // only the arrows repeat
            keyPressed(keyCode);
        }
    }

    protected void paint(Graphics g) {
        int w = getWidth();
        int h = getHeight();
        Font f = Theme.bold;
        Font sm = Theme.small;

        g.setColor(Theme.bg);
        g.fillRect(0, 0, w, h);

        // header: spark + title + status line
        int headH = Math.max(f.getHeight() + sm.getHeight() + 10, 40);
        g.setColor(Theme.bar);
        g.fillRect(0, 0, w, headH);
        int ls = headH - 12;
        Logo.draw(g, MARGIN + ls / 2 + 2, headH / 2, ls, 100, 0);
        int tx = MARGIN + ls + 10;
        g.setColor(Theme.barInk);
        g.setFont(f);
        g.drawString("AIKON", tx, 5, Graphics.TOP | Graphics.LEFT);
        g.setFont(sm);
        g.setColor(Theme.mix(Theme.barInk, Theme.bar, 90));
        g.drawString(Text.fit(midlet.homeStatus(), sm, w - tx - MARGIN), tx, 5 + f.getHeight(), Graphics.TOP | Graphics.LEFT);

        // rows
        int rowH = Math.max(f.getHeight() + sm.getHeight() + 6, 30);
        int footH = sm.getHeight() + 4;
        int area = h - headH - footH;
        // landscape only (e.g. E63: 320x240 minus the S60 status and softkey bars)
        boolean compact = w > h && area / rowH < 4;
        if (compact) {
            rowH = Math.max(f.getHeight() + 8, 26);
        }
        int visible = Math.max(1, area / rowH);
        if (selected < top) {
            top = selected;
        } else if (selected >= top + visible) {
            top = selected - visible + 1;
        }
        top = Math.max(0, Math.min(top, titles.length - visible)); // no empty slots after a text size change
        int y = headH + Math.max(2, (area - visible * rowH) / 2);
        for (int i = top; i < titles.length && i < top + visible; i++) {
            boolean sel = i == selected;
            if (sel) {
                g.setColor(Theme.selection);
                g.fillRoundRect(MARGIN / 2, y + 1, w - MARGIN, rowH - 2, 12, 12);
                g.setColor(Theme.accent);
                g.fillRoundRect(MARGIN / 2, y + 1, 4, rowH - 2, 4, 4);
            }
            int ic = compact ? rowH - 6 : rowH - 12; // the icons need about 20 px
            icon(g, i, MARGIN + 4 + ic / 2, y + rowH / 2, ic, sel);
            int x = MARGIN + ic + 14;
            String num = String.valueOf(i + 1);
            int textW = w - x - MARGIN - 6 - sm.stringWidth(num);
            g.setFont(f);
            g.setColor(Theme.ink);
            g.drawString(Text.fit(titles[i], f, textW), x, compact ? y + (rowH - f.getHeight()) / 2 : y + 3, Graphics.TOP | Graphics.LEFT);
            g.setFont(sm);
            g.setColor(Theme.muted);
            if (!compact) {
                g.drawString(Text.fit(midlet.homeHint(i, hints[i]), sm, textW), x, y + 3 + f.getHeight(), Graphics.TOP | Graphics.LEFT);
            }
            g.drawString(num, w - MARGIN - 2, y + rowH / 2 - sm.getHeight() / 2, Graphics.TOP | Graphics.RIGHT);
            y += rowH;
        }

        // footer
        g.setFont(sm);
        g.setColor(Theme.muted);
        g.drawString(Text.fit(L.s("Resmî olmayan istemci · ", "Unofficial client · ") + midlet.attr("MIDlet-Version"), sm,
                w - 2 * MARGIN), w / 2, h - footH + 2, Graphics.TOP | Graphics.HCENTER);
    }

    /** Small line icons drawn with primitives. */
    private static void icon(Graphics g, int item, int cx, int cy, int s, boolean sel) {
        int c = sel ? Theme.accent : Theme.muted;
        g.setColor(c);
        int r = s / 2;
        switch (item) {
        case 0: // chat bubble
            g.fillRoundRect(cx - r, cy - r + 2, s, s * 3 / 4, 8, 8);
            g.fillTriangle(cx - r + 3, cy + r / 2, cx - r + 9, cy + r / 2, cx - r + 1, cy + r);
            g.setColor(Theme.bg);
            for (int i = -1; i <= 1; i++) {
                g.fillArc(cx + i * (s / 4) - 2, cy - 1, 4, 4, 0, 360);
            }
            break;
        case 1: // list of chats: three lines with dots
            for (int i = -1; i <= 1; i++) {
                int ly = cy + i * (s / 3) - 1;
                g.fillArc(cx - r, ly, 4, 4, 0, 360);
                g.fillRect(cx - r + 6, ly + 1, s - 6, 2);
            }
            break;
        case 2: // lightning
            g.fillTriangle(cx + 2, cy - r, cx - r / 2, cy + 2, cx + 1, cy + 1);
            g.fillTriangle(cx - 2, cy + r, cx + r / 2, cy - 2, cx - 1, cy - 1);
            break;
        case 3: // plus in a circle
            g.drawArc(cx - r, cy - r, s, s, 0, 360);
            g.fillRect(cx - r / 2, cy - 1, r, 3);
            g.fillRect(cx - 1, cy - r / 2, 3, r);
            break;
        case 4: // a page with a folded corner and lines
            g.fillRect(cx - r + 2, cy - r, s - 4 - r / 2, s);
            g.fillTriangle(cx + r - 2 - r / 2, cy - r, cx + r - 2, cy - r + r / 2, cx + r - 2 - r / 2, cy - r + r / 2);
            g.fillRect(cx + r - 2 - r / 2, cy - r + r / 2, r / 2, s - r / 2);
            g.setColor(Theme.bg);
            for (int i = 0; i < 3; i++) {
                g.fillRect(cx - r + 5, cy - r / 3 + i * (s / 4), s - 10, 2);
            }
            break;
        case 5: // signal bars
            for (int i = 0; i < 4; i++) {
                int bh = (i + 1) * s / 4;
                g.fillRect(cx - r + i * (s / 4), cy + r - bh, Math.max(2, s / 6), bh);
            }
            break;
        case 6: // gear-ish: ring with teeth
            g.fillArc(cx - r, cy - r, s, s, 0, 360);
            g.setColor(Theme.bg);
            g.fillArc(cx - r / 2, cy - r / 2, r, r, 0, 360);
            g.setColor(c);
            g.fillRect(cx - 1, cy - r - 2, 3, 4);
            g.fillRect(cx - 1, cy + r - 2, 3, 4);
            g.fillRect(cx - r - 2, cy - 1, 4, 3);
            g.fillRect(cx + r - 2, cy - 1, 4, 3);
            break;
        case 7: // info "i"
            g.drawArc(cx - r, cy - r, s, s, 0, 360);
            g.fillRect(cx - 1, cy - r / 2, 3, 3);
            g.fillRect(cx - 1, cy - r / 6, 3, r * 2 / 3 + 2);
            break;
        default: // power
            g.drawArc(cx - r, cy - r, s, s, 120, 300);
            g.fillRect(cx - 1, cy - r - 1, 3, r);
            break;
        }
    }
}

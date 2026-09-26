package io.github.emir.claudes40;

import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.CommandListener;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.Font;
import javax.microedition.lcdui.Graphics;

/**
 * Main menu with drawn icons. UP/DOWN (game actions) move, FIRE or "Seç"
 * opens, number keys 1-7 (Canvas.KEY_NUMx constants) jump directly.
 * Softkeys are standard Commands.
 */
final class HomeCanvas extends Canvas implements CommandListener {

    private final String[] titles = {
        L.s("Sohbet", "Chat"), L.s("Hızlı sorular", "Quick prompts"), L.s("Yeni sohbet", "New chat"),
        L.s("Bağlantı testi", "Connection test"), L.s("Ayarlar", "Settings"), L.s("Hakkında", "About"),
        L.s("Çıkış", "Exit") };
    private final String[] hints = {
        L.s("Kaldığın yerden devam et", "Pick up where you left off"),
        L.s("Çevir, cevapla, özetle...", "Translate, reply, summarize..."),
        L.s("Temiz bir sayfa aç", "Start fresh"),
        L.s("Sunucuya ulaşıyor muyuz?", "Can we reach the server?"),
        L.s("Görünüm, dil, ses, eşleştirme", "Look, language, sound, pairing"),
        L.s("Claude S40 nedir?", "What is Claude S40?"),
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
        if (keyCode >= KEY_NUM1 && keyCode <= KEY_NUM7) {
            selected = keyCode - KEY_NUM1;
            repaint();
            midlet.menuSelected(selected);
            return;
        }
        int action;
        try {
            action = getGameAction(keyCode);
        } catch (IllegalArgumentException e) {
            return;
        }
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
        keyPressed(keyCode);
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
        g.drawString("Claude S40", tx, 5, Graphics.TOP | Graphics.LEFT);
        g.setFont(sm);
        g.setColor(Theme.mix(Theme.barInk, Theme.bar, 90));
        g.drawString(midlet.homeStatus(), tx, 5 + f.getHeight(), Graphics.TOP | Graphics.LEFT);

        // rows
        int rowH = Math.max(f.getHeight() + sm.getHeight() + 6, 30);
        int footH = sm.getHeight() + 4;
        int area = h - headH - footH;
        int visible = Math.max(1, area / rowH);
        if (selected < top) {
            top = selected;
        } else if (selected >= top + visible) {
            top = selected - visible + 1;
        }
        int y = headH + Math.max(2, (area - visible * rowH) / 2);
        for (int i = top; i < titles.length && i < top + visible; i++) {
            boolean sel = i == selected;
            if (sel) {
                g.setColor(Theme.selection);
                g.fillRoundRect(MARGIN / 2, y + 1, w - MARGIN, rowH - 2, 12, 12);
                g.setColor(Theme.accent);
                g.fillRoundRect(MARGIN / 2, y + 1, 4, rowH - 2, 4, 4);
            }
            int ic = rowH - 12;
            icon(g, i, MARGIN + 4 + ic / 2, y + rowH / 2, ic, sel);
            int x = MARGIN + ic + 14;
            g.setFont(f);
            g.setColor(Theme.ink);
            g.drawString(titles[i], x, y + 3, Graphics.TOP | Graphics.LEFT);
            g.setFont(sm);
            g.setColor(Theme.muted);
            g.drawString(hints[i], x, y + 3 + f.getHeight(), Graphics.TOP | Graphics.LEFT);
            g.drawString(String.valueOf(i + 1), w - MARGIN - 2, y + rowH / 2 - sm.getHeight() / 2,
                    Graphics.TOP | Graphics.RIGHT);
            y += rowH;
        }

        // footer
        g.setFont(sm);
        g.setColor(Theme.muted);
        g.drawString(L.s("Resmî olmayan istemci · ", "Unofficial client · ") + midlet.attr("MIDlet-Version"), w / 2, h - footH + 2,
                Graphics.TOP | Graphics.HCENTER);
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
        case 1: // lightning
            g.fillTriangle(cx + 2, cy - r, cx - r / 2, cy + 2, cx + 1, cy + 1);
            g.fillTriangle(cx - 2, cy + r, cx + r / 2, cy - 2, cx - 1, cy - 1);
            break;
        case 2: // plus in a circle
            g.drawArc(cx - r, cy - r, s, s, 0, 360);
            g.fillRect(cx - r / 2, cy - 1, r, 3);
            g.fillRect(cx - 1, cy - r / 2, 3, r);
            break;
        case 3: // signal bars
            for (int i = 0; i < 4; i++) {
                int bh = (i + 1) * s / 4;
                g.fillRect(cx - r + i * (s / 4), cy + r - bh, Math.max(2, s / 6), bh);
            }
            break;
        case 4: // gear-ish: ring with teeth
            g.fillArc(cx - r, cy - r, s, s, 0, 360);
            g.setColor(Theme.bg);
            g.fillArc(cx - r / 2, cy - r / 2, r, r, 0, 360);
            g.setColor(c);
            g.fillRect(cx - 1, cy - r - 2, 3, 4);
            g.fillRect(cx - 1, cy + r - 2, 3, 4);
            g.fillRect(cx - r - 2, cy - 1, 4, 3);
            g.fillRect(cx + r - 2, cy - 1, 4, 3);
            break;
        case 5: // info "i"
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

package io.github.emir.claudes40;

import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.CommandListener;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.Font;
import javax.microedition.lcdui.Graphics;

/**
 * Main menu with line icons (Icons). UP/DOWN (game actions) move, FIRE or "Seç"
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
        L.s("Yazı boyutu, görünüm, ses, dil", "Text size, look, sound, language"),
        L.s("AIKON nedir?", "What is AIKON?"),
        L.s("Görüşmek üzere", "See you soon") };

    /**
     * Rows on screen, most used first; the values are the item ids of
     * titles/hints (Icons.CREDIT has its own), Icons and
     * ClaudeS40MIDlet.menuSelected. Keys 1-9 follow the rows. On a server
     * that sells credits the sixth row is Credits; the connection test is
     * then under Settings only.
     */
    private static final int[] ORDER = { Icons.CHAT, Icons.NEW_CHAT, Icons.CHATS, Icons.PROMPTS, Icons.SAVED,
        Icons.CONN, Icons.SETTINGS, Icons.INFO, Icons.EXIT };
    private static final int[] ORDER_CREDITS = { Icons.CHAT, Icons.NEW_CHAT, Icons.CHATS, Icons.PROMPTS, Icons.SAVED,
        Icons.CREDIT, Icons.SETTINGS, Icons.INFO, Icons.EXIT };

    private int[] order() {
        return midlet.settings.credits ? ORDER_CREDITS : ORDER;
    }

    private String title(int id) {
        return id == Icons.CREDIT ? L.s("Kredi", "Credits") : titles[id];
    }

    private String hint(int id) {
        return midlet.homeHint(id, id == Icons.CREDIT ? L.s("Bakiye, kod gir, kredi al", "Balance, add a code, buy")
                : hints[id]);
    }

    private static final int MARGIN = 6;

    private final ClaudeS40MIDlet midlet;
    private final Command selectCmd = new Command(L.s("Seç", "Select"), Command.OK, 1);
    private final Command exitCmd = new Command(L.s("Çıkış", "Exit"), Command.EXIT, 2);
    private final Command updateCmd = new Command(L.s("Güncelle", "Update"), Command.SCREEN, 3);
    private boolean updateShown;
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
            midlet.menuSelected(order()[selected]);
        } else if (c == exitCmd) {
            midlet.exit();
        } else if (c == updateCmd) {
            midlet.confirmUpdate();
        }
    }

    /** Shows "Update" while the server offers a newer version. */
    void setUpdate(boolean show) {
        if (show != updateShown) {
            updateShown = show;
            if (show) {
                addCommand(updateCmd);
            } else {
                removeCommand(updateCmd);
            }
        }
        repaint();
    }

    protected void keyPressed(int keyCode) {
        int raw = keyCode;
        keyCode = Keys.map(keyCode);
        if (keyCode >= KEY_NUM1 && keyCode <= KEY_NUM9) {
            selected = keyCode - KEY_NUM1;
            repaint();
            if (selected != titles.length - 1 || raw == KEY_NUM9) { // a stray QWERTY N must not quit
                midlet.menuSelected(order()[selected]);
            }
            return;
        }
        int action = Keys.action(this, keyCode);
        if (action == UP) {
            selected = (selected + titles.length - 1) % titles.length;
        } else if (action == DOWN) {
            selected = (selected + 1) % titles.length;
        } else if (action == FIRE) {
            midlet.menuSelected(order()[selected]);
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

        // header: only the wordmark, on the bar colour above a hairline
        int headH = RowList.barH();
        g.setColor(Theme.chrome);
        g.fillRect(0, 0, w, headH);
        g.setColor(Theme.border);
        g.drawLine(0, headH - 1, w, headH - 1);
        int wh = Math.min(headH - 16, f.getHeight() + 2);
        int ww = wh * 1051 / 244;
        g.drawImage(Wordmark.get(ww, Theme.accent), MARGIN + 4, (headH - wh) / 2, Graphics.TOP | Graphics.LEFT);

        // rows
        int rowH = Math.max(f.getHeight() + sm.getHeight() + 6, 30);
        int footH = sm.getHeight() + 8;
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
                g.fillRoundRect(MARGIN / 2, y + 1, w - MARGIN, rowH - 2, 10, 10);
            }
            int ic = compact ? rowH - 6 : rowH - 12; // the icons need about 20 px
            icon(g, order()[i], MARGIN + 4 + ic / 2, y + rowH / 2, ic, sel);
            int x = MARGIN + ic + 14;
            String num = String.valueOf(i + 1);
            int textW = w - x - MARGIN - 6 - sm.stringWidth(num);
            g.setFont(f);
            g.setColor(Theme.ink);
            g.drawString(Text.fit(title(order()[i]), f, textW), x, compact ? y + (rowH - f.getHeight()) / 2 : y + 3, Graphics.TOP | Graphics.LEFT);
            g.setFont(sm);
            g.setColor(Theme.muted);
            if (!compact) {
                g.drawString(Text.fit(hint(order()[i]), sm, textW), x, y + 3 + f.getHeight(), Graphics.TOP | Graphics.LEFT);
            }
            g.drawString(num, w - MARGIN - 2, y + rowH / 2 - sm.getHeight() / 2, Graphics.TOP | Graphics.RIGHT);
            y += rowH;
        }

        // footer on the bar colour: the status (setup, credits, update) left, the version right
        g.setColor(Theme.chrome);
        g.fillRect(0, h - footH, w, footH);
        g.setColor(Theme.border);
        g.drawLine(0, h - footH, w, h - footH);
        g.setFont(sm);
        int fy = h - footH + (footH - sm.getHeight()) / 2;
        String ver = "v" + midlet.attr("MIDlet-Version");
        int vw = sm.stringWidth(ver);
        String st = midlet.homeStatus();
        boolean roomForVersion = sm.stringWidth(st) + vw + 3 * MARGIN + 8 <= w;
        g.setColor(midlet.homeStatusUrgent() ? Theme.accent : Theme.muted);
        g.drawString(Text.fit(st, sm, w - 2 * MARGIN - 8 - (roomForVersion ? vw + MARGIN : 0)), MARGIN + 4, fy,
                Graphics.TOP | Graphics.LEFT);
        if (roomForVersion) {
            g.setColor(Theme.mix(Theme.chrome, Theme.muted, 170));
            g.drawString(ver, w - MARGIN - 2, fy, Graphics.TOP | Graphics.RIGHT);
        }
    }

    /** Item ids are Icons ids (CHAT .. EXIT). */
    private static void icon(Graphics g, int item, int cx, int cy, int s, boolean sel) {
        g.drawImage(Icons.get(item, s, sel ? Theme.accent : Theme.muted), cx, cy, Graphics.HCENTER | Graphics.VCENTER);
    }
}

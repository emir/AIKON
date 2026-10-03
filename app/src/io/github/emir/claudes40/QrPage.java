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
 * The shop's address as a QR code, to scan with a smartphone (the shop's
 * payment page is not for this phone's browser): the title bar, the code on
 * white whatever the theme, the address and one line below it. Nothing is
 * opened or sent from here; Back returns.
 */
final class QrPage extends Canvas implements CommandListener {

    private final Display display;
    private final Displayable back;
    private final String title;
    private final String url;
    private final boolean[][] qr;
    private final Command backCmd = new Command(L.t("Back"), Command.BACK, 1);

    private QrPage(Display display, Displayable back, String title, String url, boolean[][] qr) {
        this.display = display;
        this.back = back;
        this.title = title;
        this.url = url;
        this.qr = qr;
        setFullScreenMode(RowList.fullScreen);
        addCommand(backCmd);
        setCommandListener(this);
    }

    /** Shows url as a QR code; false if it does not fit one (then only the address can be shown). */
    static boolean show(Display display, Displayable back, String title, String url) {
        boolean[][] m = Qr.encode(url);
        if (m == null) {
            return false;
        }
        display.setCurrent(new QrPage(display, back, title, url, m));
        return true;
    }

    public void commandAction(Command c, Displayable d) {
        display.setCurrent(back);
    }

    protected void paint(Graphics g) {
        int w = getWidth();
        int h = getHeight();
        g.setColor(Theme.bg);
        g.fillRect(0, 0, w, h);
        int top = RowList.barH();

        Font sm = Theme.small;
        Vector lines = new Vector();
        Text.wrap(url.startsWith("https://") ? url.substring(8) : url, Theme.bold, w - 16, lines);
        int urlLines = lines.size();
        Text.wrap(L.t("Scan it with a smartphone camera, buy a code there, then type the code here."), sm, w - 16, lines);
        int textH = urlLines * Theme.bold.getHeight() + (lines.size() - urlLines) * sm.getHeight() + 8;

        int n = qr.length + 8; // four light modules around the code
        int px = Math.max(1, Math.min((w - 16) / n, (h - top - textH - 12) / n));
        int side = n * px;
        int x0 = (w - side) / 2;
        int y0 = top + 6;
        g.setColor(0xFFFFFF);
        g.fillRect(x0, y0, side, side);
        g.setColor(0x000000);
        for (int y = 0; y < qr.length; y++) {
            for (int x = 0; x < qr.length; x++) {
                if (qr[y][x]) {
                    g.fillRect(x0 + (x + 4) * px, y0 + (y + 4) * px, px, px);
                }
            }
        }

        int ty = y0 + side + 6;
        for (int i = 0; i < lines.size(); i++) {
            boolean u = i < urlLines;
            Font f = u ? Theme.bold : sm;
            g.setFont(f);
            g.setColor(u ? Theme.ink : Theme.muted);
            g.drawString((String) lines.elementAt(i), w / 2, ty, Graphics.TOP | Graphics.HCENTER);
            ty += f.getHeight();
        }
        RowList.paintBar(g, w, title);
    }
}

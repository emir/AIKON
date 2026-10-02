package io.github.emir.claudes40;

import java.util.Timer;
import java.util.TimerTask;

import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Graphics;

/**
 * Start-up screen: the AIKON wordmark is wiped in and twinkles, loading
 * dots below, the jingle plays once. About 2.4 s; any key skips. Full screen, all sizes
 * derived from getWidth()/getHeight().
 */
final class Splash extends Canvas {

    private static final int FRAME_MS = 60;
    private static final int FRAMES = 40;

    private final ClaudeS40MIDlet midlet;
    private Timer timer;
    private int frame;
    private boolean done;

    Splash(ClaudeS40MIDlet midlet) {
        this.midlet = midlet;
        setFullScreenMode(true);
    }

    protected void showNotify() {
        if (timer != null || done) {
            return;
        }
        Sound.play(midlet.settings, Sound.JINGLE);
        timer = new Timer();
        timer.schedule(new TimerTask() {
            public void run() {
                tick();
            }
        }, FRAME_MS, FRAME_MS);
    }

    protected void hideNotify() {
        stop();
    }

    private synchronized void tick() {
        frame++;
        if (frame >= FRAMES) {
            finish();
        } else {
            repaint();
        }
    }

    private void stop() {
        if (timer != null) {
            timer.cancel();
            timer = null;
        }
    }

    private synchronized void finish() {
        if (done) {
            return;
        }
        done = true;
        stop();
        midlet.afterSplash();
    }

    protected void keyPressed(int keyCode) {
        finish();
    }

    /** For the emulator harness and tests: freeze on a given frame. */
    synchronized void setFrame(int f) {
        frame = f;
        repaint();
    }

    protected synchronized void paint(Graphics g) {
        int w = getWidth();
        int h = getHeight();
        g.setColor(Theme.bg);
        g.fillRect(0, 0, w, h);

        // the wordmark is wiped in from the left, then two twinkles
        int ww = Math.min(w * 72 / 100, h * 3 / 4);
        int wh = Wordmark.height(ww);
        int x0 = (w - ww) / 2;
        int y0 = (h - wh) / 2;
        int t = Math.min(256, frame * 256 / 12);
        int shown = ww * (256 * 256 - (256 - t) * (256 - t)) / (256 * 256); // eases out
        if (shown > 0) {
            g.setClip(x0, 0, shown, h);
            g.drawImage(Wordmark.get(ww, Theme.accent), x0, y0, Graphics.TOP | Graphics.LEFT);
            g.setClip(0, 0, w, h);
        }
        if (frame > 12) {
            int tw = (frame % 6 < 3) ? 4 : 2;
            Logo.sparkle(g, x0 + ww + wh / 4, y0 - wh / 4, tw + wh / 8, Theme.accent);
            Logo.sparkle(g, x0 - wh / 5, y0 + wh + wh / 5, (6 - tw) + wh / 10, Theme.accent);
        }
        int cx = w / 2;

        // loading dots
        int dots = (frame / 3) % 4;
        int dy = h - Theme.small.getHeight() * 3;
        for (int i = 0; i < 3; i++) {
            g.setColor(i < dots ? Theme.accent : Theme.border);
            g.fillArc(cx - 14 + i * 12, dy, 6, 6, 0, 360);
        }
    }
}

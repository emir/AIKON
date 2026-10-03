package io.github.emir.claudes40;

import java.util.Timer;
import java.util.TimerTask;

import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Graphics;

/**
 * Start-up screen: the AIKON wordmark is wiped in, a hairline under it
 * fills up, the jingle plays once. About 2.4 s; any key skips. Full screen, all sizes
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
        repaint(); // the spinner, while the backup is still being read
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

        // the wordmark is wiped in from the left; a hairline under it fills up
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
        int ly = y0 + wh + Math.max(10, wh / 2);
        int lw = ww / 3;
        int lx = (w - lw) / 2;
        g.setColor(Theme.border);
        g.fillRect(lx, ly, lw, 2);
        g.setColor(Theme.accent);
        g.fillRect(lx, ly, lw * Math.min(frame, FRAMES) / FRAMES, 2);
        if (done && midlet.restoringSetup()) {
            // the intro is over but the setup backup is still being read (a permission prompt, a slow card)
            int sz = Math.max(16, w / 12);
            Busy.spinner(g, (w - sz) / 2, ly + 14, sz);
            Busy.on(this);
        } else {
            Busy.off(this);
        }
    }
}

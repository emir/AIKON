package io.github.emir.claudes40;

import java.util.Timer;
import java.util.TimerTask;

import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Graphics;

/**
 * Start-up screen: the blue tile pops in and the AK monogram is built
 * stroke by stroke, the title fades in, the jingle plays once. About 2.4 s; any key skips. Full screen, all sizes
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

        int size = Math.min(w, h) * 42 / 100;
        int cx = w / 2;
        int cy = h * 40 / 100;

        // pop-in with a little overshoot, then gentle breathing
        int scale;
        if (frame < 8) {
            scale = frame * 115 / 8;
        } else if (frame < 12) {
            scale = 115 - (frame - 8) * 15 / 4;
        } else {
            scale = 100 + ((frame / 3) % 2 == 0 ? 0 : 3);
        }
        // the monogram is built stroke by stroke from frame 6: A, bar, stem, K
        int parts = frame < 6 ? 0 : Math.min(Logo.ALL, (frame - 6) / 3 + 1);
        Logo.draw(g, cx, cy, size, scale, parts);

        // twinkles around the mark
        if (frame > 10) {
            int r = size * 60 / 100;
            int tw = (frame % 6 < 3) ? 4 : 2;
            Logo.sparkle(g, cx + r, cy - r / 2, tw + size / 30, Theme.accent);
            Logo.sparkle(g, cx - r, cy + r / 3, (6 - tw) + size / 40, Theme.accent);
        }

        // title fades in from the background colour
        int t = frame < 12 ? 0 : Math.min(256, (frame - 12) * 32);
        g.setFont(Theme.bold);
        g.setColor(Theme.mix(Theme.bg, Theme.ink, t));
        int ty = cy + size / 2 + size / 5;
        g.drawString("AIKon", cx, ty, Graphics.TOP | Graphics.HCENTER);
        g.setFont(Theme.small);

        // loading dots and disclaimer
        int dots = (frame / 3) % 4;
        int dy = h - Theme.small.getHeight() * 3;
        for (int i = 0; i < 3; i++) {
            g.setColor(i < dots ? Theme.accent : Theme.border);
            g.fillArc(cx - 14 + i * 12, dy, 6, 6, 0, 360);
        }
    }
}

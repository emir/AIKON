package io.github.emir.claudes40;

import java.util.Timer;
import java.util.TimerTask;
import java.util.Vector;

import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Graphics;

/**
 * Shows that something is under way: a small spinner of eight dots and a
 * slow pulse for placeholder rows. One timer for the whole app repaints the
 * screens that draw either, only while they are shown: a screen registers
 * itself from paint() (on) and is dropped once it is hidden or draws
 * nothing busy (off), so a screen left mid-wait costs nothing.
 */
final class Busy {

    private static final int TICK_MS = 120;
    /** The eight dot positions around the circle, x and y in hundredths. */
    private static final int[] DX = { 0, 71, 100, 71, 0, -71, -100, -71 };
    private static final int[] DY = { -100, -71, 0, 71, 100, 71, 0, -71 };

    private static final Vector screens = new Vector();
    private static Timer timer;

    private Busy() {
    }

    /** From paint(): c draws something busy, keep it moving. */
    static synchronized void on(Canvas c) {
        if (!screens.contains(c)) {
            screens.addElement(c);
        }
        if (timer == null) {
            timer = new Timer();
            timer.schedule(new TimerTask() {
                public void run() {
                    tick();
                }
            }, TICK_MS, TICK_MS);
        }
    }

    /** From paint(): nothing busy on c (any more). */
    static synchronized void off(Canvas c) {
        screens.removeElement(c);
        if (screens.isEmpty() && timer != null) {
            timer.cancel();
            timer = null;
        }
    }

    private static void tick() {
        Canvas[] cs;
        synchronized (Busy.class) {
            cs = new Canvas[screens.size()];
            screens.copyInto(cs);
        }
        for (int i = 0; i < cs.length; i++) {
            if (cs[i].isShown()) {
                cs[i].repaint();
            } else {
                off(cs[i]); // paint() registers it again once shown
            }
        }
    }

    /** 0..255 and back over about 1.2 s, for placeholder rows. */
    static int pulse() {
        int t = (int) (System.currentTimeMillis() % 1200);
        return t < 600 ? t * 255 / 600 : (1200 - t) * 255 / 600;
    }

    /** The spinner in a size x size square at (x, y): the leading dot in the accent colour, a fading tail. */
    static void spinner(Graphics g, int x, int y, int size) {
        int head = (int) ((System.currentTimeMillis() / TICK_MS) % 8);
        int d = Math.max(3, size / 4);
        int r = (size - d) / 2;
        int cx = x + size / 2;
        int cy = y + size / 2;
        for (int i = 0; i < 8; i++) {
            int age = (head - i + 8) % 8;
            g.setColor(Theme.mix(Theme.accent, Theme.border, Math.min(255, age * 36)));
            g.fillArc(cx + DX[i] * r / 100 - d / 2, cy + DY[i] * r / 100 - d / 2, d, d, 0, 360);
        }
    }
}

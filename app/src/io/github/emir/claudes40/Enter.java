package io.github.emir.claudes40;

import java.util.Timer;
import java.util.TimerTask;

import javax.microedition.lcdui.Canvas;

/**
 * A short entry movement for a screen that opens: its content starts a few
 * pixels to the right and settles in three steps of 40 ms. Cheap enough
 * for the slow phones (four repaints), and over before a key matters.
 */
final class Enter {

    private static final int STEPS = 3;
    private static final int STEP_PX = 6;

    private final Canvas canvas;
    private int left;
    private Timer timer;

    Enter(Canvas canvas) {
        this.canvas = canvas;
    }

    synchronized void start() {
        stop();
        left = STEPS;
        final Timer t = new Timer();
        timer = t;
        t.schedule(new TimerTask() {
            public void run() {
                boolean done;
                synchronized (Enter.this) {
                    left--;
                    done = left <= 0;
                    if (done) {
                        left = 0;
                        stop();
                    }
                }
                canvas.repaint();
            }
        }, 40, 40);
    }

    private void stop() {
        if (timer != null) {
            timer.cancel();
            timer = null;
        }
    }

    /** The content's offset to the right now, in pixels. */
    synchronized int dx() {
        return left * STEP_PX;
    }
}

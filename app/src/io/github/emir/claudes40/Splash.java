package io.github.emir.claudes40;

import java.util.Timer;
import java.util.TimerTask;

import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.Image;

/**
 * Start-up screen: AIKON is "Nokia" spelled backwards. The wordmark's own
 * letters fall in from the top one by one as N O K I A, then each moves to
 * its mirrored place (those going right over the top, those going left
 * underneath) and AIKON stands; a hairline under it fills up, the jingle
 * plays once. About 3 s; any key skips. Full screen, all sizes derived from
 * getWidth()/getHeight(). Only the wordmark's letters: no Nokia artwork.
 */
final class Splash extends Canvas {

    private static final int FRAME_MS = 60;
    private static final int FRAMES = 50;
    /** Letter j of N O K I A starts falling at FALL_AT + j * STAGGER, lands FALL frames later, then bounces. */
    private static final int FALL_AT = 2;
    private static final int STAGGER = 3;
    private static final int FALL = 8;
    private static final int BOUNCE = 3;
    /** The letters move to AIKON from MOVE_AT, for MOVE frames. */
    private static final int MOVE_AT = 29;
    private static final int MOVE = 13;
    /** The wordmark's letters (Wordmark.letter: 0 A, 1 I, 2 K, 3 O, 4 N) in the order of N O K I A. */
    private static final int[] NOKIA = { 4, 3, 2, 1, 0 };
    /** Space between the letters of N O K I A, in the artwork's pixels. */
    private static final int GAP = 40;

    private final ClaudeS40MIDlet midlet;
    private Timer timer;
    private int frame;
    private boolean done;
    /** The letters for the wordmark width `lettersW`, made on the first paint. */
    private Image[] letters;
    private int lettersW;

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

    /** N O K I A falling in, or on their way to A I K O N. */
    private void paintLetters(Graphics g, int w, int ww, int wh, int x0, int y0) {
        if (letters == null || lettersW != ww) {
            letters = new Image[5];
            for (int i = 0; i < 5; i++) {
                letters[i] = Wordmark.letter(i, ww, Theme.accent);
            }
            lettersW = ww;
        }
        float k = ww / (float) Wordmark.artWidth();
        // N O K I A laid out with even gaps, centred
        int total = GAP * 4;
        for (int j = 0; j < 5; j++) {
            total += Wordmark.letterArtWidth(NOKIA[j]);
        }
        float nx = (w - total * k) / 2;
        int maxMove = 1;
        int[] from = new int[5];
        int[] to = new int[5];
        for (int j = 0; j < 5; j++) {
            int i = NOKIA[j];
            from[j] = (int) nx;
            to[j] = x0 + Wordmark.letterX(i, ww);
            maxMove = Math.max(maxMove, Math.abs(to[j] - from[j]));
            nx += (Wordmark.letterArtWidth(i) + GAP) * k;
        }
        for (int j = 0; j < 5; j++) {
            int x = from[j];
            int y = y0;
            if (frame < MOVE_AT) {
                int f = frame - (FALL_AT + j * STAGGER);
                if (f < 0) {
                    continue; // not falling yet
                }
                if (f < FALL) {
                    float t = (f + 1) / (float) FALL; // falls faster and faster
                    y = (int) (-wh + (y0 + wh) * t * t);
                } else if (f < FALL + BOUNCE) {
                    y = y0 - (int) (wh / 6 * Math.sin(Math.PI * (f - FALL + 1) / (BOUNCE + 1)));
                }
            } else {
                float t = Math.min(1f, (frame - MOVE_AT + 1) / (float) MOVE);
                float e = t * t * (3 - 2 * t); // slow, fast, slow
                int dx = to[j] - from[j];
                x = from[j] + (int) (dx * e);
                // right over the top, left underneath; the farther, the higher the arc
                y = y0 - (int) (wh * 1.5 * dx / maxMove * Math.sin(Math.PI * t));
            }
            g.drawImage(letters[NOKIA[j]], x, y, Graphics.TOP | Graphics.LEFT);
        }
    }

    protected synchronized void paint(Graphics g) {
        int w = getWidth();
        int h = getHeight();
        g.setColor(Theme.bg);
        g.fillRect(0, 0, w, h);

        int ww = Math.min(w * 72 / 100, h * 3 / 4);
        int wh = Wordmark.height(ww);
        int x0 = (w - ww) / 2;
        int y0 = (h - wh) / 2;
        if (frame >= MOVE_AT + MOVE || done) {
            g.drawImage(Wordmark.get(ww, Theme.accent), x0, y0, Graphics.TOP | Graphics.LEFT);
        } else {
            paintLetters(g, w, ww, wh, x0, y0);
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

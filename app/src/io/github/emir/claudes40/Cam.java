package io.github.emir.claudes40;

import java.io.IOException;
import java.util.Vector;

import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.CommandListener;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.Font;
import javax.microedition.lcdui.Graphics;
import javax.microedition.media.Manager;
import javax.microedition.media.MediaException;
import javax.microedition.media.Player;
import javax.microedition.media.control.VideoControl;

/**
 * Camera viewfinder and snapshot (JSR 135 VideoControl). The only class
 * that uses the JSR 135 camera (tools/check.py); callers check
 * ClaudeS40MIDlet.hasCamera() first. Opening the camera and taking the
 * picture run on worker threads (the phone asks for permission).
 *
 * The snapshot is asked for as a 640x480 JPEG (small enough for mobile
 * data, sharp enough for Claude); if the phone does not take those
 * parameters, the encodings the phone lists in video.snapshot.encodings are
 * tried (with and without the size), then simpler ones, down to the phone's
 * default. If none works, the error names every attempt.
 */
final class Cam extends Canvas implements CommandListener, Runnable {

    /**
     * "capture://image" first: on the Nokia 6300 only that locator takes
     * snapshots ("capture://video" shows a viewfinder but getSnapshot fails).
     */
    private static final String[] LOCATORS = { "capture://image", "capture://video" };
    private static final String[] SNAPSHOTS = { "encoding=jpeg&width=640&height=480",
        "encoding=image/jpeg&width=640&height=480", "encoding=jpeg", "encoding=image/jpeg", null };

    private static final int JOB_OPEN = 0;
    private static final int JOB_SHOOT = 1;

    private final ClaudeS40MIDlet midlet;
    private final Photo photo;
    private final Command shootCmd = new Command(L.t("Capture"), Command.OK, 1);
    private final Command cancelCmd = new Command(L.t("Cancel"), Command.BACK, 1);

    private Player player;
    private VideoControl video;
    private int job;
    private boolean ready;
    private boolean closed;
    private String status = L.t("Opening the camera...");
    /** What each failed snapshot attempt said (shown if none works). */
    private final StringBuffer tried = new StringBuffer();

    Cam(ClaudeS40MIDlet midlet, Photo photo) {
        this.midlet = midlet;
        this.photo = photo;
        addCommand(cancelCmd);
        setCommandListener(this);
    }

    void start() {
        midlet.display().setCurrent(this);
        job = JOB_OPEN;
        new Thread(this).start();
    }

    public void run() {
        int j;
        synchronized (this) {
            j = job;
        }
        if (j == JOB_OPEN) {
            String err = open();
            synchronized (this) {
                if (closed) {
                    release();
                    return;
                }
                if (err == null) {
                    ready = true;
                    status = L.t("Centre key or 'Capture': take the photo");
                    addCommand(shootCmd);
                }
            }
            if (err != null) {
                release();
                photo.failed(err);
            }
            repaint();
            return;
        }
        byte[] b = null;
        String err = null;
        try {
            b = snapshot();
        } catch (SecurityException e) {
            err = L.t("Camera access was denied. Answer 'Yes' when the phone asks.");
        }
        release();
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
        }
        if (b == null) {
            photo.failed(err != null ? err : L.t("Could not take the photo.")
                    + "\n" + tried.toString() + "\n(" + ClaudeS40MIDlet.prop("video.snapshot.encodings") + ")");
        } else {
            photo.chosen(b);
        }
    }

    /** Opens the camera; null on success, or a reason for the user. */
    private String open() {
        String last = "";
        for (int i = 0; i < LOCATORS.length; i++) {
            try {
                player = Manager.createPlayer(LOCATORS[i]);
                player.realize();
                video = (VideoControl) player.getControl("VideoControl");
                if (video == null) {
                    release();
                    continue;
                }
                video.initDisplayMode(VideoControl.USE_DIRECT_VIDEO, this);
                place();
                video.setVisible(true);
                player.start();
                return null;
            } catch (SecurityException e) {
                release();
                return L.t("Camera access was denied. Answer 'Yes' when the phone asks.");
            } catch (MediaException e) {
                last = e.getMessage();
                release();
            } catch (IOException e) {
                last = e.getMessage();
                release();
            } catch (RuntimeException e) {
                last = e.getClass().getName() + ": " + e.getMessage();
                release();
            }
        }
        return L.t("Could not open the camera. ") + (last == null ? "" : last);
    }

    /** The viewfinder: as large as fits above the status line, 4:3. */
    private void place() {
        int line = Font.getDefaultFont().getHeight() + 4;
        int w = getWidth();
        int h = Math.max(1, getHeight() - line);
        int vw = w;
        int vh = w * 3 / 4;
        if (vh > h) {
            vh = h;
            vw = h * 4 / 3;
        }
        try {
            video.setDisplaySize(vw, vh);
        } catch (MediaException e) {
            // the phone keeps its own size
        }
        video.setDisplayLocation((w - vw) / 2, (h - vh) / 2);
    }

    private byte[] snapshot() {
        Vector types = new Vector();
        types.addElement(SNAPSHOTS[0]);
        types.addElement(SNAPSHOTS[1]);
        // what the phone says it can do, e.g. "encoding=jpeg encoding=png"
        String listed = ClaudeS40MIDlet.prop("video.snapshot.encodings").trim();
        int pos = 0;
        while (listed.length() > 1 && pos < listed.length()) {
            int sp = listed.indexOf(' ', pos);
            String e = listed.substring(pos, sp < 0 ? listed.length() : sp).trim();
            pos = sp < 0 ? listed.length() : sp + 1;
            if (e.startsWith("encoding=")) {
                if (e.indexOf("width=") < 0) {
                    add(types, e + "&width=640&height=480");
                }
                add(types, e);
            }
        }
        for (int i = 2; i < SNAPSHOTS.length; i++) {
            add(types, SNAPSHOTS[i]);
        }
        for (int i = 0; i < types.size(); i++) {
            String t = (String) types.elementAt(i);
            try {
                byte[] b = video.getSnapshot(t);
                if (b != null && b.length > 0) {
                    return b;
                }
                note(t, "empty");
            } catch (MediaException e) {
                note(t, e.getMessage());
            } catch (SecurityException e) {
                throw e;
            } catch (RuntimeException e) {
                note(t, e.getClass().getName() + " " + e.getMessage());
            }
        }
        return null;
    }

    private static void add(Vector v, String t) {
        for (int i = 0; i < v.size(); i++) {
            Object o = v.elementAt(i);
            if (o == null ? t == null : o.equals(t)) {
                return;
            }
        }
        v.addElement(t);
    }

    private void note(String type, String what) {
        tried.append(type == null ? "default" : type).append(": ").append(what).append('\n');
    }

    private void shoot() {
        synchronized (this) {
            if (!ready || closed) {
                return;
            }
            ready = false;
            status = L.t("Taking the photo...");
            removeCommand(shootCmd);
            job = JOB_SHOOT;
        }
        repaint();
        new Thread(this).start();
    }

    /** Stops the camera; any thread. */
    private void release() {
        Player p;
        synchronized (this) {
            p = player;
            player = null;
            video = null;
        }
        if (p != null) {
            try {
                p.close();
            } catch (RuntimeException e) {
                // ignore
            }
        }
    }

    protected void paint(Graphics g) {
        int w = getWidth();
        int h = getHeight();
        g.setColor(0x000000);
        g.fillRect(0, 0, w, h);
        Font f = Font.getDefaultFont();
        g.setFont(f);
        g.setColor(0xFFFFFF);
        String s;
        synchronized (this) {
            s = status;
        }
        g.drawString(Text.fit(s, f, w - 4), w / 2, h - 2, Graphics.BOTTOM | Graphics.HCENTER);
    }

    protected void keyPressed(int keyCode) {
        midlet.userActive();
        keyCode = Keys.map(keyCode); // QWERTY phones: G = 5, Enter / raw centre key = FIRE
        if (keyCode == KEY_NUM5 || Keys.action(this, keyCode) == FIRE) {
            shoot();
        }
    }

    public void commandAction(Command c, Displayable d) {
        midlet.userActive();
        if (c == shootCmd) {
            shoot();
        } else if (c == cancelCmd) {
            synchronized (this) {
                if (closed) {
                    return;
                }
                closed = true;
            }
            new Thread(new Runnable() {
                public void run() {
                    release();
                }
            }).start();
            photo.cancelled();
        }
    }
}

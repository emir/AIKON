package io.github.emir.claudes40;

import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.CommandListener;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.Form;
import javax.microedition.lcdui.List;
import javax.microedition.lcdui.StringItem;

/**
 * Adds a photo to the next message: take it with the camera (Cam) or pick
 * a file (PhotoPicker), upload it to /v1/image, then open the editor with
 * the photo attached (ChatSession.setImage) for the user to write the
 * question and send it. The upload never calls Claude and costs nothing but
 * mobile data; the server keeps the same photo under the same id, so
 * "Retry" is harmless. The photo is held in RAM only until the upload ends.
 */
final class Photo implements CommandListener, Runnable, Net.Listener {

    /** The server takes at most 1 MB. */
    static final int MAX_BYTES = 1024 * 1024;

    private final ClaudeS40MIDlet midlet;
    private final boolean fromComposer;
    private List source;
    private final Form form;
    private final StringItem status;
    private final Command retryCmd = new Command(L.s("Tekrar dene", "Retry"), Command.OK, 1);
    private final Command otherCmd = new Command(L.s("Başka fotoğraf", "Another photo"), Command.SCREEN, 2);
    private final Command cancelCmd = new Command(L.s("Vazgeç", "Cancel"), Command.BACK, 1);
    private Command[] shownCmds = new Command[0];

    private byte[] data;
    /** Bumped when the screen moves on; an older upload's answer is ignored. */
    private int generation;
    private boolean closed;

    Photo(ClaudeS40MIDlet midlet, boolean fromComposer) {
        this.midlet = midlet;
        this.fromComposer = fromComposer;
        form = new Form(L.s("Fotoğraf ekle", "Add a photo"));
        status = new StringItem(null, "");
        form.append(status);
        form.append(new StringItem(null, L.s(
                "Fotoğraf sunucuya gider ve bu sohbette Claude'a gösterilir. Sohbetle birlikte sunucuda kalır "
                        + "(30 gün ya da sohbeti silene kadar).",
                "The photo goes to the server and is shown to Claude in this chat. It stays on the server with "
                        + "the chat (30 days, or until you delete the chat).")));
        form.setCommandListener(this);
    }

    /** Camera and files: asks which; otherwise opens the one there is. */
    void start() {
        boolean cam = ClaudeS40MIDlet.hasCamera();
        boolean files = ClaudeS40MIDlet.hasFiles();
        if (cam && files) {
            source = new List(L.s("Fotoğraf ekle", "Add a photo"), List.IMPLICIT);
            source.append(L.s("Kamerayla çek", "Take a photo"), null);
            source.append(L.s("Telefondan seç", "Choose from the phone"), null);
            source.addCommand(cancelCmd);
            source.setCommandListener(this);
            midlet.display().setCurrent(source);
        } else if (cam) {
            new Cam(midlet, this).start();
        } else {
            new PhotoPicker(midlet, this).start();
        }
    }

    // ------------------------------------------------------------ from Cam / PhotoPicker (any thread)

    void chosen(byte[] b) {
        synchronized (this) {
            if (closed) {
                return;
            }
            data = b;
        }
        upload();
    }

    /** Camera or picker closed without a photo: back to the choice, or out. */
    void cancelled() {
        if (source != null && !closed) {
            midlet.display().setCurrent(source);
        } else {
            close();
        }
    }

    void failed(String reason) {
        synchronized (this) {
            if (closed) {
                return;
            }
            show(reason, source != null ? new Command[] { otherCmd, cancelCmd } : new Command[] { cancelCmd });
        }
        midlet.display().setCurrent(form);
        Sound.play(midlet.settings, Sound.ERROR);
    }

    // ------------------------------------------------------------ upload

    private synchronized void upload() {
        generation++;
        show(L.s("Gönderiliyor (", "Sending (") + (data.length + 1023) / 1024 + " KB)...", new Command[] { cancelCmd });
        midlet.display().setCurrent(form);
        new Thread(this).start();
    }

    public void run() {
        byte[] b;
        int gen;
        synchronized (this) {
            b = data;
            gen = generation;
        }
        Settings s = midlet.settings;
        if (s.testMode) {
            try {
                Thread.sleep(800);
            } catch (InterruptedException e) {
                // ignore
            }
            done(gen, "test");
            return;
        }
        Net.Result r = Net.upload(s.url + "/v1/image", s.token, b, contentType(b), midlet.userAgent(), this);
        S40Message m = r.msg;
        String st = m == null ? "" : m.field("status");
        if (r.ok() && "ok".equals(st) && m.field("image").length() == 32) {
            done(gen, m.field("image"));
            return;
        }
        String msg;
        boolean retry = false;
        if (!r.ok()) {
            msg = Net.explain(r);
            retry = true;
        } else if (m == null) {
            msg = L.s("Sunucu yanıtı tanınmadı (HTTP " + r.httpCode + ").", "Unrecognised server reply (HTTP "
                    + r.httpCode + ").");
            retry = true;
        } else if ("bad_image".equals(st)) {
            msg = L.s("Sunucu bu fotoğrafı okuyamadı (JPEG veya PNG olmalı).",
                    "The server could not read this photo (it must be JPEG or PNG).");
        } else if ("too_large".equals(st)) {
            msg = L.s("Fotoğraf çok büyük.", "The photo is too large.");
        } else if ("limit".equals(st)) {
            msg = L.s("Bugünkü fotoğraf hakkınız bitti. Yarın (UTC) yenilenir.",
                    "No photos left today. More tomorrow (UTC).");
        } else if ("unauthorized".equals(st)) {
            msg = L.s("Eşleştirme geçersiz. Ayarlar > Seçenekler > 'Cihazı eşleştir'.",
                    "The pairing is not valid. Settings > Options > 'Pair this phone'.");
        } else if ("not_found".equals(st)) {
            msg = L.s("Sunucu fotoğrafları desteklemiyor (eski sürüm).",
                    "The server does not take photos (older version).");
        } else {
            msg = L.s("Sunucu: ", "Server: ") + st;
            retry = true;
        }
        synchronized (this) {
            if (closed || gen != generation) {
                return;
            }
            Command[] c = retry ? new Command[] { retryCmd, otherCmd, cancelCmd } : new Command[] { otherCmd, cancelCmd };
            show(msg, c);
        }
        Sound.play(midlet.settings, Sound.ERROR);
    }

    public void phase(int phase) {
        if (phase >= Net.PHASE_RESPONSE) {
            synchronized (this) {
                if (!closed) {
                    status.setText(L.s("Sunucu fotoğrafı hazırlıyor...", "The server is preparing the photo..."));
                }
            }
        }
    }

    private void done(int gen, String id) {
        synchronized (this) {
            if (closed || gen != generation) {
                return;
            }
            closed = true;
            data = null;
        }
        midlet.photoAttached(id, fromComposer);
    }

    static String contentType(byte[] b) {
        if (b.length >= 2 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8) {
            return "image/jpeg";
        }
        if (b.length >= 4 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') {
            return "image/png";
        }
        return "application/octet-stream";
    }

    // ------------------------------------------------------------ screen

    /** Called with the lock held. */
    private void show(String text, Command[] cmds) {
        status.setText(text);
        for (int i = 0; i < shownCmds.length; i++) {
            form.removeCommand(shownCmds[i]);
        }
        for (int i = 0; i < cmds.length; i++) {
            form.addCommand(cmds[i]);
        }
        shownCmds = cmds;
    }

    private void close() {
        synchronized (this) {
            closed = true;
            generation++;
            data = null;
        }
        midlet.photoClosed(fromComposer);
    }

    public void commandAction(Command c, Displayable d) {
        midlet.userActive();
        if (d == source) {
            if (c == List.SELECT_COMMAND) {
                if (source.getSelectedIndex() == 0) {
                    new Cam(midlet, this).start();
                } else {
                    new PhotoPicker(midlet, this).start();
                }
            } else {
                close();
            }
            return;
        }
        if (c == retryCmd) {
            upload();
        } else if (c == otherCmd) {
            synchronized (this) {
                generation++;
                data = null;
            }
            start();
        } else if (c == cancelCmd) {
            close();
        }
    }
}

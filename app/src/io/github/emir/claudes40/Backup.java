package io.github.emir.claudes40;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * The setup outside the app, so a new build does not need the setup wizard
 * again: removing the app deletes its RMS settings, but not the file
 * "claude-s40-setup.dat" in the ClaudeS40 folder on the memory card (or in
 * the phone's image folder; Files).
 *
 * Kept: server address, access code, the address the connection test passed
 * for, "setup done", language and the notes for Claude. Written after
 * Settings.save() when one of these changed; read once at start-up when the
 * app has no settings yet (just installed). Settings > Options > "Reset
 * setup" deletes it. The file holds the access code: whoever has the memory
 * card can use it until the device is revoked on the server.
 *
 * File work runs on its own thread (the phone may ask for permission).
 */
final class Backup implements Runnable {

    private static final int MAGIC = 0x43533430; // "CS40"
    private static final int FORMAT = 1;

    /** What the backup file holds now (as far as we know); null: unknown, write on the next save. */
    private static String written;

    private final byte[] data;

    private Backup(byte[] data) {
        this.data = data;
    }

    /** Settings were loaded from RMS: the backup is taken to match them. */
    static synchronized void loaded(Settings s) {
        written = key(s);
    }

    /** After Settings.save(): writes the backup if its part of the settings changed. */
    static void changed(Settings s) {
        if (!ClaudeS40MIDlet.hasFiles()) {
            return;
        }
        String k = key(s);
        synchronized (Backup.class) {
            if (k.equals(written)) {
                return;
            }
            written = k;
        }
        new Thread(new Backup(encode(s))).start();
    }

    public void run() {
        try {
            Files.writeSetup(data);
        } catch (IOException e) {
            failed();
        } catch (RuntimeException e) { // SecurityException: permission denied
            failed();
        }
    }

    private static synchronized void failed() {
        written = null; // try again on the next save
    }

    /**
     * Reads the backup into `s` (worker thread only). Returns false if there
     * is none or it cannot be read; `s` is then unchanged.
     */
    static boolean restore(Settings s) {
        byte[] b;
        try {
            b = Files.readSetup();
        } catch (IOException e) {
            return false;
        } catch (RuntimeException e) {
            return false;
        }
        if (b == null) {
            return false;
        }
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(b));
            if (in.readInt() != MAGIC || in.readInt() != FORMAT) {
                return false;
            }
            String url = in.readUTF();
            String token = in.readUTF();
            String verified = in.readUTF();
            boolean done = in.readBoolean();
            int lang = in.readByte();
            String notes = in.readUTF();
            if (url.length() > 0 && !Net.isHttps(url)) {
                return false;
            }
            s.url = url;
            s.token = token;
            s.verifiedUrl = verified;
            s.setupDone = done;
            s.lang = lang >= L.AUTO && lang <= L.ENGLISH ? lang : L.AUTO;
            s.instructions = Text.clip(notes, Settings.MAX_INSTRUCTIONS);
        } catch (IOException e) {
            return false;
        }
        synchronized (Backup.class) {
            written = key(s);
        }
        return true;
    }

    /** "Reset setup": deletes the file; `s` is already reset and must not be written back. */
    static void forget(Settings s) {
        synchronized (Backup.class) {
            written = key(s);
        }
        if (!ClaudeS40MIDlet.hasFiles()) {
            return;
        }
        new Thread(new Runnable() {
            public void run() {
                try {
                    Files.deleteSetup();
                } catch (IOException e) {
                    // nothing there
                } catch (RuntimeException e) {
                    // permission denied: the file stays
                }
            }
        }).start();
    }

    private static String key(Settings s) {
        return s.url + '\n' + s.token + '\n' + s.verifiedUrl + '\n' + s.setupDone + '\n' + s.lang + '\n' + s.instructions;
    }

    private static byte[] encode(Settings s) {
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bo);
            out.writeInt(MAGIC);
            out.writeInt(FORMAT);
            out.writeUTF(s.url);
            out.writeUTF(s.token);
            out.writeUTF(s.verifiedUrl);
            out.writeBoolean(s.setupDone);
            out.writeByte(s.lang);
            out.writeUTF(s.instructions);
            out.close();
            return bo.toByteArray();
        } catch (IOException e) {
            return new byte[0];
        }
    }
}

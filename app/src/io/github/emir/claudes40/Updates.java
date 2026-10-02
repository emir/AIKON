package io.github.emir.claudes40;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import javax.microedition.rms.RecordStore;
import javax.microedition.rms.RecordStoreException;

/**
 * Update check (0.12.3): a server that offers the app for download names
 * its newest version in /health ("app-version", "app-url": the JAD over
 * https). At most once a day, on a worker thread and only for a verified
 * server, the phone asks; when the offered version is newer than this
 * one, the home screen says so and offers "Update", which opens the JAD
 * in the phone's browser (the phone installs it, keeping the app's data).
 * Nothing is downloaded or installed without the user. The answer is kept
 * in RMS "cs40upd" with the server it came from.
 */
final class Updates implements Runnable {

    private static final String STORE = "cs40upd";
    private static final int FORMAT = 1;
    private static final long DAY = 24L * 60 * 60 * 1000;

    private static String server = "";
    private static String latest = "";
    private static String url = "";
    private static long checkedAt;
    private static boolean loaded;
    private static boolean running;

    private final ClaudeS40MIDlet midlet;

    private Updates(ClaudeS40MIDlet midlet) {
        this.midlet = midlet;
    }

    /** Starts a check when the last one is a day old (or from another server). */
    static void maybeCheck(ClaudeS40MIDlet midlet) {
        Settings s = midlet.settings;
        if (s.testMode || !s.connectionVerified()) {
            return;
        }
        synchronized (Updates.class) {
            load();
            long now = System.currentTimeMillis();
            if (running || (s.url.equals(server) && now - checkedAt < DAY && now >= checkedAt)) {
                return;
            }
            running = true;
        }
        new Thread(new Updates(midlet)).start();
    }

    public void run() {
        Settings s = midlet.settings;
        String base = s.url;
        Net.Result r = Net.request(base + "/health", "GET", null, null, midlet.userAgent(), null);
        boolean changed = false;
        synchronized (Updates.class) {
            running = false;
            if (r.ok() && r.msg != null && "ok".equals(r.msg.field("status"))) {
                String v = r.msg.field("app-version");
                String u = r.msg.field("app-url");
                if (!Net.isHttps(u)) {
                    v = "";
                    u = "";
                }
                changed = !v.equals(latest);
                server = base;
                latest = v;
                url = u;
                checkedAt = System.currentTimeMillis();
                save();
            }
        }
        if (changed) {
            midlet.updateChanged();
        }
    }

    /** The newer version the server offers, or "" (none, or not newer than current). */
    static synchronized String available(String current, String serverUrl) {
        load();
        return serverUrl.equals(server) && newer(latest, current) ? latest : "";
    }

    /** The JAD to open for the update ("" if none). */
    static synchronized String url() {
        load();
        return url;
    }

    /** True when version a (like 0.12.3) is newer than b. */
    static boolean newer(String a, String b) {
        if (a.length() == 0) {
            return false;
        }
        int ia = 0;
        int ib = 0;
        while (ia < a.length() || ib < b.length()) {
            int na = 0;
            while (ia < a.length() && a.charAt(ia) != '.') {
                char ch = a.charAt(ia++);
                if (ch < '0' || ch > '9') {
                    return false;
                }
                na = na * 10 + (ch - '0');
            }
            ia++;
            int nb = 0;
            while (ib < b.length() && b.charAt(ib) != '.') {
                char ch = b.charAt(ib++);
                if (ch >= '0' && ch <= '9') {
                    nb = nb * 10 + (ch - '0');
                }
            }
            ib++;
            if (na != nb) {
                return na > nb;
            }
        }
        return false;
    }

    private static void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        RecordStore rs = null;
        try {
            rs = RecordStore.openRecordStore(STORE, false);
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(rs.getRecord(1)));
            if (in.readInt() != FORMAT) {
                return;
            }
            server = in.readUTF();
            latest = in.readUTF();
            url = in.readUTF();
            checkedAt = in.readLong();
        } catch (RecordStoreException e) {
            // nothing kept yet
        } catch (IOException e) {
            // unreadable: checked again
        } finally {
            close(rs);
        }
    }

    private static void save() {
        RecordStore rs = null;
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bo);
            out.writeInt(FORMAT);
            out.writeUTF(server);
            out.writeUTF(latest);
            out.writeUTF(url);
            out.writeLong(checkedAt);
            out.close();
            byte[] b = bo.toByteArray();
            rs = RecordStore.openRecordStore(STORE, true);
            if (rs.getNumRecords() == 0) {
                rs.addRecord(b, 0, b.length);
            } else {
                rs.setRecord(1, b, 0, b.length);
            }
        } catch (RecordStoreException e) {
            // not kept: checked again next start
        } catch (IOException e) {
            // same
        } finally {
            close(rs);
        }
    }

    private static void close(RecordStore rs) {
        if (rs != null) {
            try {
                rs.closeRecordStore();
            } catch (RecordStoreException e) {
                // ignore
            }
        }
    }
}

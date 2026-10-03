package io.github.emir.claudes40;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Hashtable;

import javax.microedition.rms.RecordStore;
import javax.microedition.rms.RecordStoreException;

/**
 * UI languages other than English come from the server (0.18.0; server
 * GET /v1/lang?c=xx&p=N, no pairing needed): a few parts of "hash TAB
 * translation" lines, the hash being the String.hashCode of the English
 * text. One language is kept in RMS "cs40lang" (record 1: what and from
 * where, then one record per part), so it works offline and at start-up.
 *
 * Fetched when the user picks a language in Settings (the screen waits),
 * and in the background for "Same as phone" or after an app update, a
 * server change or a week (the kept one is used meanwhile). Only on worker
 * threads, never in test mode.
 */
final class LangPack {

    private static final String STORE = "cs40lang";
    private static final int FORMAT = 1;
    private static final long WEEK = 7L * 24 * 60 * 60 * 1000;
    /** Most parts accepted (a part is ~6 KB). */
    private static final int MAX_PARTS = 20;

    private static final long HOUR = 60L * 60 * 1000;

    private static boolean fetching;
    /** What a background fetch last tried (code, server and build) and when; tried again after an hour. */
    private static String tried = "";
    private static long triedAt;
    /** Found fresh in RMS this run (code, server and build): not looked up again. */
    private static String freshFor = "";

    private LangPack() {
    }

    /** The kept translations of `code` (hash -> text), or null if another or none is kept. */
    static synchronized Hashtable table(String code) {
        RecordStore rs = null;
        try {
            rs = RecordStore.openRecordStore(STORE, false);
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(rs.getRecord(1)));
            if (in.readInt() != FORMAT || !in.readUTF().equals(code)) {
                return null;
            }
            in.readUTF(); // version
            in.readUTF(); // server
            in.readUTF(); // build
            in.readLong(); // saved at
            int parts = in.readInt();
            Hashtable h = new Hashtable(800);
            for (int i = 0; i < parts; i++) {
                L.parse(new String(rs.getRecord(2 + i), "UTF-8"), h);
            }
            return h.isEmpty() ? null : h;
        } catch (RecordStoreException e) {
            return null;
        } catch (IOException e) {
            return null;
        } finally {
            close(rs);
        }
    }

    /** True if `code` is kept (from any server, of any age). */
    static synchronized boolean has(String code) {
        RecordStore rs = null;
        try {
            rs = RecordStore.openRecordStore(STORE, false);
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(rs.getRecord(1)));
            return in.readInt() == FORMAT && in.readUTF().equals(code);
        } catch (RecordStoreException e) {
            return false;
        } catch (IOException e) {
            return false;
        } finally {
            close(rs);
        }
    }

    /** True if `code` is kept and was fetched from this server by this build less than a week ago. */
    private static synchronized boolean fresh(String code, String server, String build) {
        RecordStore rs = null;
        try {
            rs = RecordStore.openRecordStore(STORE, false);
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(rs.getRecord(1)));
            if (in.readInt() != FORMAT || !in.readUTF().equals(code)) {
                return false;
            }
            in.readUTF();
            boolean same = in.readUTF().equals(server) && in.readUTF().equals(build);
            long at = in.readLong();
            long now = System.currentTimeMillis();
            return same && now >= at && now - at < WEEK;
        } catch (RecordStoreException e) {
            return false;
        } catch (IOException e) {
            return false;
        } finally {
            close(rs);
        }
    }

    /** Keeps a language (replacing the one kept). False if it could not be written. */
    static synchronized boolean save(String code, String version, String server, String build, String[] parts) {
        try {
            RecordStore.deleteRecordStore(STORE);
        } catch (RecordStoreException e) {
            // none kept
        }
        RecordStore rs = null;
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bo);
            out.writeInt(FORMAT);
            out.writeUTF(code);
            out.writeUTF(version);
            out.writeUTF(server);
            out.writeUTF(build);
            out.writeLong(System.currentTimeMillis());
            out.writeInt(parts.length);
            out.close();
            rs = RecordStore.openRecordStore(STORE, true);
            byte[] b = bo.toByteArray();
            rs.addRecord(b, 0, b.length);
            for (int i = 0; i < parts.length; i++) {
                b = parts[i].getBytes("UTF-8");
                rs.addRecord(b, 0, b.length);
            }
            return true;
        } catch (RecordStoreException e) {
            // half written: dropped below
        } catch (IOException e) {
            // same
        } finally {
            close(rs);
        }
        clear();
        return false;
    }

    /** Drops the kept language. */
    static synchronized void clear() {
        try {
            RecordStore.deleteRecordStore(STORE);
        } catch (RecordStoreException e) {
            // none kept
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

    /**
     * Fetches and keeps `code` from the server (worker thread only). Returns
     * null, or why it failed; what was kept before stays then.
     */
    static String fetch(ClaudeS40MIDlet midlet, String code) {
        Settings s = midlet.settings;
        if (s.testMode) {
            return L.t("Test mode uses no network.");
        }
        if (!s.connectionVerified()) {
            return L.t("Languages come from the server: set it up first.");
        }
        String base = s.url;
        String version = null;
        String[] parts = null;
        for (int i = 0; parts == null || i < parts.length; i++) {
            Net.Result r = Net.request(base + "/v1/lang?c=" + code + "&p=" + i, "GET", null, null,
                    midlet.userAgent(), null);
            if (!r.ok()) {
                return Net.explain(r);
            }
            S40Message m = r.msg;
            String st = m == null ? "?" : m.field("status");
            if ("not_found".equals(st) || "method_not_allowed".equals(st) || "unknown_language".equals(st)) {
                return L.t("This server has no language files yet.");
            }
            if (!"ok".equals(st) || r.bodyCut) {
                return L.f("Could not get the language ({0}).", r.bodyCut ? "size" : st);
            }
            if (parts == null) {
                int n;
                try {
                    n = Integer.parseInt(m.field("parts"));
                } catch (NumberFormatException e) {
                    n = 0;
                }
                if (n < 1 || n > MAX_PARTS) {
                    return L.f("Could not get the language ({0}).", "parts");
                }
                parts = new String[n];
                version = m.field("version");
            } else if (!version.equals(m.field("version"))) {
                return L.t("The language changed on the server; try again.");
            }
            parts[i] = m.text;
        }
        if (!save(code, version, base, midlet.attr("ClaudeS40-Build"), parts)) {
            return L.t("The phone's memory is full.");
        }
        return null;
    }

    /**
     * The language wanted (Settings or the phone's) is not English and
     * not kept, or old: fetched on a worker thread. The first time it
     * comes, the screens switch to it if the menu is shown.
     */
    static void maybeFetch(final ClaudeS40MIDlet midlet) {
        final Settings s = midlet.settings;
        final String code = L.code();
        if (code.equals("en") || s.testMode || !s.connectionVerified()) {
            return;
        }
        String build = midlet.attr("ClaudeS40-Build");
        final String what = code + '\n' + s.url + '\n' + build;
        synchronized (LangPack.class) {
            long now = System.currentTimeMillis();
            if (fetching || what.equals(freshFor)
                    || what.equals(tried) && now >= triedAt && now - triedAt < HOUR) {
                return;
            }
            if (fresh(code, s.url, build)) {
                freshFor = what;
                return;
            }
            fetching = true;
            tried = what;
            triedAt = now;
        }
        new Thread(new Runnable() {
            public void run() {
                try {
                    if (fetch(midlet, code) == null) {
                        synchronized (LangPack.class) {
                            freshFor = what;
                        }
                        midlet.languageArrived(code);
                    }
                } finally {
                    synchronized (LangPack.class) {
                        fetching = false;
                    }
                }
            }
        }).start();
    }
}

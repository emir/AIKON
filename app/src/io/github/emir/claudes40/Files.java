package io.github.emir.claudes40;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.util.Enumeration;
import java.util.Vector;

import javax.microedition.io.Connector;
import javax.microedition.io.file.FileConnection;

/**
 * Saved replies as .txt files (JSR 75 FileConnection). With Pim, the only
 * class that uses JSR 75 (tools/check.py): phones without it never load it,
 * callers check ClaudeS40MIDlet.hasFiles() first.
 *
 * Folder "ClaudeS40/" on the memory card if there is one, else in the
 * phone's image folder, else in the app's private folder (deleted with the
 * app). Files are UTF-8 with a BOM and CRLF line ends, so a PC shows them
 * right. Every method blocks and may make the phone ask for permission:
 * call them from a worker thread only.
 */
final class Files {

    private static final String DIR = "ClaudeS40/";
    private static final int MAX_READ = 16384;
    private static final byte[] BOM = { (byte) 0xEF, (byte) 0xBB, (byte) 0xBF };

    /** The folder found by folder(), for the rest of the session. */
    private static String folder;

    private Files() {
    }

    /** URL of the folder (ending in '/'), created if needed. */
    static synchronized String folder() throws IOException {
        if (folder != null) {
            return folder;
        }
        String[] bases = { ClaudeS40MIDlet.prop("fileconn.dir.memorycard"), ClaudeS40MIDlet.prop("fileconn.dir.photos"),
            ClaudeS40MIDlet.prop("fileconn.dir.private") };
        IOException last = null;
        for (int i = 0; i < bases.length; i++) {
            String base = bases[i];
            if (!base.startsWith("file:///")) {
                continue;
            }
            if (!base.endsWith("/")) {
                base += "/";
            }
            FileConnection fc = null;
            try {
                fc = (FileConnection) Connector.open(base + DIR, Connector.READ_WRITE);
                if (!fc.exists()) {
                    fc.mkdir();
                }
                folder = base + DIR;
                return folder;
            } catch (IOException e) {
                last = e;
            } finally {
                close(fc);
            }
        }
        throw last != null ? last : new IOException(L.s("klasör bulunamadı", "no folder found"));
    }

    /** "E:/ClaudeS40/" for people; "" if not known yet. */
    static synchronized String where() {
        if (folder == null) {
            return "";
        }
        return folder.startsWith("file:///") ? folder.substring(8) : folder;
    }

    /** Writes a new file "name.txt" (or "name-2.txt", ...); returns its file name. */
    static String save(String name, String text) throws IOException {
        String dir = folder();
        byte[] data = crlf(text).getBytes("UTF-8");
        for (int n = 1; n < 100; n++) {
            String file = name + (n == 1 ? "" : "-" + n) + ".txt";
            FileConnection fc = null;
            OutputStream out = null;
            try {
                fc = (FileConnection) Connector.open(dir + file, Connector.READ_WRITE);
                if (fc.exists()) {
                    continue;
                }
                fc.create();
                out = fc.openOutputStream();
                out.write(BOM);
                out.write(data);
                out.close();
                out = null;
                return file;
            } finally {
                if (out != null) {
                    try {
                        out.close();
                    } catch (IOException e) {
                        // ignore
                    }
                }
                close(fc);
            }
        }
        throw new IOException(L.s("dosya adı bulunamadı", "no free file name"));
    }

    /** The .txt files in the folder, newest name first (names start with the date). */
    static Vector list() throws IOException {
        Vector out = new Vector();
        FileConnection fc = null;
        try {
            fc = (FileConnection) Connector.open(folder(), Connector.READ);
            Enumeration e = fc.list("*.txt", false);
            while (e.hasMoreElements()) {
                String n = (String) e.nextElement();
                if (!n.endsWith("/")) {
                    int at = 0;
                    while (at < out.size() && ((String) out.elementAt(at)).compareTo(n) > 0) {
                        at++;
                    }
                    out.insertElementAt(n, at);
                }
            }
        } finally {
            close(fc);
        }
        return out;
    }

    /** The text of a saved file (at most MAX_READ bytes), with "\n" line ends. */
    static String read(String file) throws IOException {
        FileConnection fc = null;
        InputStream in = null;
        try {
            fc = (FileConnection) Connector.open(folder() + file, Connector.READ);
            in = fc.openInputStream();
            byte[] buf = new byte[MAX_READ];
            int n = 0;
            while (n < MAX_READ) {
                int k = in.read(buf, n, MAX_READ - n);
                if (k < 0) {
                    break;
                }
                n += k;
            }
            int start = n >= 3 && buf[0] == BOM[0] && buf[1] == BOM[1] && buf[2] == BOM[2] ? 3 : 0;
            int end = n == MAX_READ ? Net.utf8Boundary(buf, n) : n;
            String t;
            try {
                t = new String(buf, start, end - start, "UTF-8");
            } catch (UnsupportedEncodingException e) {
                t = new String(buf, start, end - start);
            }
            StringBuffer sb = new StringBuffer(t.length());
            for (int i = 0; i < t.length(); i++) {
                char ch = t.charAt(i);
                if (ch != '\r') {
                    sb.append(ch);
                }
            }
            return sb.toString();
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException e) {
                    // ignore
                }
            }
            close(fc);
        }
    }

    static void delete(String file) throws IOException {
        FileConnection fc = null;
        try {
            fc = (FileConnection) Connector.open(folder() + file, Connector.READ_WRITE);
            if (fc.exists()) {
                fc.delete();
            }
        } finally {
            close(fc);
        }
    }

    /** The setup backup (Backup) next to the saved replies; not a .txt, so "Saved" does not list it. */
    private static final String SETUP = "claude-s40-setup.dat";

    /**
     * Folder for the setup backup: one that outlives the app (memory card or
     * image folder), never the app's private folder, which goes with it.
     */
    private static String lastingFolder() throws IOException {
        String dir = folder();
        String priv = ClaudeS40MIDlet.prop("fileconn.dir.private");
        if (priv.startsWith("file:///") && dir.startsWith(priv)) {
            throw new IOException(L.s("kalıcı klasör yok", "no lasting folder"));
        }
        return dir;
    }

    /** Replaces the setup backup. */
    static synchronized void writeSetup(byte[] data) throws IOException {
        FileConnection fc = null;
        OutputStream out = null;
        try {
            fc = (FileConnection) Connector.open(lastingFolder() + SETUP, Connector.READ_WRITE);
            if (fc.exists()) {
                fc.truncate(0);
            } else {
                fc.create();
            }
            out = fc.openOutputStream();
            out.write(data);
            out.close();
            out = null;
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (IOException e) {
                    // ignore
                }
            }
            close(fc);
        }
    }

    /** The setup backup, or null if there is none. */
    static synchronized byte[] readSetup() throws IOException {
        FileConnection fc = null;
        InputStream in = null;
        try {
            fc = (FileConnection) Connector.open(lastingFolder() + SETUP, Connector.READ);
            if (!fc.exists()) {
                return null;
            }
            in = fc.openInputStream();
            byte[] buf = new byte[4096];
            int n = 0;
            while (n < buf.length) {
                int k = in.read(buf, n, buf.length - n);
                if (k < 0) {
                    break;
                }
                n += k;
            }
            byte[] out = new byte[n];
            System.arraycopy(buf, 0, out, 0, n);
            return out;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException e) {
                    // ignore
                }
            }
            close(fc);
        }
    }

    static synchronized void deleteSetup() throws IOException {
        FileConnection fc = null;
        try {
            fc = (FileConnection) Connector.open(lastingFolder() + SETUP, Connector.READ_WRITE);
            if (fc.exists()) {
                fc.delete();
            }
        } finally {
            close(fc);
        }
    }

    private static String crlf(String t) {
        StringBuffer sb = new StringBuffer(t.length() + 32);
        for (int i = 0; i < t.length(); i++) {
            char ch = t.charAt(i);
            if (ch == '\n') {
                sb.append('\r');
            }
            if (ch != '\r') {
                sb.append(ch);
            }
        }
        return sb.toString();
    }

    private static void close(FileConnection fc) {
        if (fc != null) {
            try {
                fc.close();
            } catch (IOException e) {
                // ignore
            }
        }
    }
}

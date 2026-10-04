package io.github.emir.claudes40;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import javax.microedition.rms.RecordStore;
import javax.microedition.rms.RecordStoreException;

/**
 * The free trial this phone started (RMS record store "cs40trial", one
 * record): the server address and the trial's access code. "Reset setup"
 * leaves it, so "Try for free" after a reset continues the same trial
 * account (Credits) instead of starting a new one. Removing the app
 * deletes it; the server's own limits stay the real bound.
 */
final class TrialPair {

    private static final String STORE = "cs40trial";
    private static final int FORMAT = 1;

    private TrialPair() {
    }

    /** The trial's access code on this server, or "". */
    static synchronized String token(String url) {
        RecordStore rs = null;
        try {
            rs = RecordStore.openRecordStore(STORE, false);
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(rs.getRecord(1)));
            if (in.readInt() == FORMAT && in.readUTF().equals(url)) {
                return in.readUTF();
            }
        } catch (RecordStoreException e) {
            // no trial started here
        } catch (IOException e) {
            // unreadable
        } finally {
            close(rs);
        }
        return "";
    }

    /** A trial started on this server (one kept: the newest). */
    static synchronized void save(String url, String token) {
        RecordStore rs = null;
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bo);
            out.writeInt(FORMAT);
            out.writeUTF(url);
            out.writeUTF(token);
            out.close();
            byte[] b = bo.toByteArray();
            rs = RecordStore.openRecordStore(STORE, true);
            if (rs.getNumRecords() == 0) {
                rs.addRecord(b, 0, b.length);
            } else {
                rs.setRecord(1, b, 0, b.length);
            }
        } catch (RecordStoreException e) {
            // ignore
        } catch (IOException e) {
            // ignore
        } finally {
            close(rs);
        }
    }

    /** The server no longer knows the trial's pairing (revoked). */
    static synchronized void forget() {
        try {
            RecordStore.deleteRecordStore(STORE);
        } catch (RecordStoreException e) {
            // nothing kept
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

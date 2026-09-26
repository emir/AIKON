package io.github.emir.claudes40;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Vector;

import javax.microedition.rms.RecordStore;
import javax.microedition.rms.RecordStoreException;

/**
 * The last chat kept on the phone for offline reading (RMS record store
 * "cs40chat", one record). Used only when the user turns on Settings >
 * "Keep last chat on phone"; turning it off deletes the store, and so does
 * removing the application.
 *
 * Kept: the conversation id and the user/Claude messages (newest ones, at
 * most MAX_CHARS characters). Never the access code or a draft.
 */
final class ChatStore {

    private static final String STORE = "cs40chat";
    private static final int FORMAT = 1;
    private static final int MAX_CHARS = 8000;

    private ChatStore() {
    }

    /** Conversation id of the last load() (or ""). */
    static String conversation = "";

    /** Writes the messages; an empty list deletes the store. Errors are ignored. */
    static synchronized void save(String conv, Vector entries) {
        int from = entries.size();
        int chars = 0;
        while (from > 0) {
            int len = ((ChatSession.Entry) entries.elementAt(from - 1)).text.length();
            if (chars + len > MAX_CHARS && from < entries.size()) {
                break;
            }
            chars += len;
            from--;
        }
        if (from >= entries.size()) {
            delete();
            return;
        }
        RecordStore rs = null;
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bo);
            out.writeInt(FORMAT);
            out.writeUTF(conv == null ? "" : conv);
            out.writeInt(entries.size() - from);
            for (int i = from; i < entries.size(); i++) {
                ChatSession.Entry e = (ChatSession.Entry) entries.elementAt(i);
                out.writeByte(e.kind);
                out.writeLong(e.time);
                out.writeBoolean(e.truncated);
                out.writeInt(e.searched);
                out.writeUTF(e.request == null ? "" : e.request);
                out.writeUTF(e.next == null ? "" : e.next);
                out.writeUTF(Text.clip(e.text, MAX_CHARS));
            }
            out.close();
            byte[] b = bo.toByteArray();
            rs = RecordStore.openRecordStore(STORE, true);
            if (rs.getNumRecords() == 0) {
                rs.addRecord(b, 0, b.length);
            } else {
                rs.setRecord(1, b, 0, b.length);
            }
        } catch (RecordStoreException e) {
            // full or unavailable: the chat simply is not kept
        } catch (IOException e) {
            // same
        } finally {
            close(rs);
        }
    }

    /** Returns the saved messages (possibly empty); sets `conversation`. */
    static synchronized Vector load() {
        Vector out = new Vector();
        conversation = "";
        RecordStore rs = null;
        try {
            rs = RecordStore.openRecordStore(STORE, false);
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(rs.getRecord(1)));
            if (in.readInt() != FORMAT) {
                return out;
            }
            String conv = in.readUTF();
            int n = in.readInt();
            for (int i = 0; i < n; i++) {
                int kind = in.readByte();
                long time = in.readLong();
                boolean truncated = in.readBoolean();
                int searched = in.readInt();
                String request = in.readUTF();
                String next = in.readUTF();
                String text = in.readUTF();
                out.addElement(new ChatSession.Entry(kind, text, truncated, time, searched,
                        request.length() > 0 ? request : null, next.length() > 0 ? next : null));
            }
            conversation = conv;
        } catch (RecordStoreException e) {
            // nothing saved
        } catch (IOException e) {
            out.removeAllElements(); // unreadable: ignore it
        } finally {
            close(rs);
        }
        return out;
    }

    static synchronized void delete() {
        try {
            RecordStore.deleteRecordStore(STORE);
        } catch (RecordStoreException e) {
            // not there
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

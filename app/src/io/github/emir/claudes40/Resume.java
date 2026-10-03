package io.github.emir.claudes40;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import javax.microedition.rms.RecordStore;
import javax.microedition.rms.RecordStoreException;

/**
 * Where the app was left, so the next start continues there (RMS record
 * store "cs40resume", one record): whether the chat was open, its
 * conversation id, and the unsent draft. The draft is kept only while
 * Settings > "Keep last chat on phone" is on (like ChatStore, no message
 * text is stored otherwise); never the access code. Also whether the
 * chat's one-time key card was seen (format 2). Written only when
 * something changed: on leaving or opening the chat and at exit.
 */
final class Resume {

    private static final String STORE = "cs40resume";
    private static final int FORMAT = 2;

    /** As last read or written. */
    static boolean inChat;
    static String conversation = "";
    static String draft = "";
    /** The chat's key card was shown once (ChatCanvas). */
    static boolean tipsSeen;

    private Resume() {
    }

    static synchronized void load() {
        RecordStore rs = null;
        try {
            rs = RecordStore.openRecordStore(STORE, false);
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(rs.getRecord(1)));
            int format = in.readInt();
            if (format >= 1 && format <= FORMAT) {
                inChat = in.readBoolean();
                conversation = in.readUTF();
                draft = in.readUTF();
            }
            if (format >= 2 && format <= FORMAT) {
                tipsSeen = in.readBoolean();
            }
        } catch (RecordStoreException e) {
            // nothing kept yet
        } catch (IOException e) {
            // unreadable: start at the menu
        } finally {
            close(rs);
        }
    }

    /** Keeps the state if it changed; errors are ignored (the next start opens the menu). */
    static synchronized void save(boolean chat, String conv, String unsent) {
        conv = conv == null ? "" : conv;
        unsent = unsent == null ? "" : unsent;
        if (chat == inChat && conv.equals(conversation) && unsent.equals(draft)) {
            return;
        }
        inChat = chat;
        conversation = conv;
        draft = unsent;
        write();
    }

    /** The key card was shown: never again. */
    static synchronized void tipsShown() {
        if (!tipsSeen) {
            tipsSeen = true;
            write();
        }
    }

    /** Called with the lock held. */
    private static void write() {
        RecordStore rs = null;
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bo);
            out.writeInt(FORMAT);
            out.writeBoolean(inChat);
            out.writeUTF(conversation);
            out.writeUTF(draft);
            out.writeBoolean(tipsSeen);
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

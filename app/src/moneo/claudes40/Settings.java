package moneo.claudes40;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import javax.microedition.rms.RecordStore;
import javax.microedition.rms.RecordStoreException;

/**
 * Settings kept on the phone (RMS record store "cs40cfg", one record).
 *
 * Stored: gateway URL, access code, test mode flag, the URL for which the
 * connection test last passed, and look & feel (theme, text size, sound,
 * vibration, language). No chat content is ever stored. Format 1 and 2
 * records (0.1.x, 0.2.x) are still read.
 *
 * The access code is typed on the phone by the user; it is never part of
 * the JAR/JAD. Removing the application deletes this record store.
 */
final class Settings {

    private static final String STORE = "cs40cfg";
    private static final int FORMAT = 3;

    String url = "";
    String token = "";
    boolean testMode;
    /** URL on which the connection test (health + echo) last succeeded. */
    String verifiedUrl = "";
    /** 0 = light (Gündüz), 1 = dark (Gece). */
    int theme;
    /** 0 small, 1 medium, 2 large. */
    int fontSize = 1;
    boolean sound = true;
    boolean vibrate = true;
    /** L.AUTO (follow the phone), L.TURKISH or L.ENGLISH. */
    int lang = L.AUTO;

    boolean connectionVerified() {
        return url.length() > 0 && url.equals(verifiedUrl);
    }

    boolean ready() {
        return Net.isHttps(url) && token.length() >= 16 && connectionVerified();
    }

    void load(String defaultUrl) {
        url = defaultUrl == null ? "" : defaultUrl.trim();
        RecordStore rs = null;
        try {
            rs = RecordStore.openRecordStore(STORE, false);
            byte[] b = rs.getRecord(1);
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(b));
            int format = in.readInt();
            if (format >= 1 && format <= 3) {
                url = in.readUTF();
                token = in.readUTF();
                testMode = in.readBoolean();
                verifiedUrl = in.readUTF();
            }
            if (format >= 2 && format <= 3) {
                theme = in.readByte();
                fontSize = in.readByte();
                sound = in.readBoolean();
                vibrate = in.readBoolean();
            }
            if (format == 3) {
                lang = in.readByte();
            }
        } catch (RecordStoreException e) {
            // first start: nothing stored yet
        } catch (IOException e) {
            // unreadable: keep defaults
        } finally {
            closeQuietly(rs);
        }
        if (url.length() == 0 && defaultUrl != null) {
            url = defaultUrl.trim(); // nothing saved yet: use ClaudeS40-Gateway from the JAD
        }
    }

    /** Returns null on success, or a message for the user. */
    String save() {
        RecordStore rs = null;
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bo);
            out.writeInt(FORMAT);
            out.writeUTF(url);
            out.writeUTF(token);
            out.writeBoolean(testMode);
            out.writeUTF(verifiedUrl);
            out.writeByte(theme);
            out.writeByte(fontSize);
            out.writeBoolean(sound);
            out.writeBoolean(vibrate);
            out.writeByte(lang);
            out.close();
            byte[] b = bo.toByteArray();
            rs = RecordStore.openRecordStore(STORE, true);
            if (rs.getNumRecords() == 0) {
                rs.addRecord(b, 0, b.length);
            } else {
                rs.setRecord(1, b, 0, b.length);
            }
            return null;
        } catch (RecordStoreException e) {
            return L.s("Ayarlar kaydedilemedi: ", "Could not save settings: ") + e.getMessage();
        } catch (IOException e) {
            return L.s("Ayarlar kaydedilemedi: ", "Could not save settings: ") + e.getMessage();
        } finally {
            closeQuietly(rs);
        }
    }

    private static void closeQuietly(RecordStore rs) {
        if (rs != null) {
            try {
                rs.closeRecordStore();
            } catch (RecordStoreException e) {
                // ignore
            }
        }
    }
}

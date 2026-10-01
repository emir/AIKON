package io.github.emir.claudes40;

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
 * connection test last passed, look & feel (theme, text size, sound,
 * vibration, language), web search on/off and whether the last chat is kept
 * on the phone (ChatStore; off by default), and whether the setup wizard was
 * finished or skipped, the backlight option (and the unused reading one) and the user's notes for
 * Claude (sent with every message). No chat content is stored here. The
 * setup part is also kept outside the app (Backup), so reinstalling does
 * not need the setup wizard again.
 * Format 1-5 records (0.1.x-0.6.x) are still read.
 *
 * The access code is typed on the phone by the user; it is never part of
 * the JAR/JAD. Removing the application deletes this record store.
 */
final class Settings {

    private static final String STORE = "cs40cfg";
    private static final int FORMAT = 6;
    /** Longest note for Claude (the server keeps at most 300 characters). */
    static final int MAX_INSTRUCTIONS = 300;

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
    /** Let Claude search the web (the server may still limit it). */
    boolean webSearch = true;
    /** Keep the last chat on the phone for offline reading (ChatStore). */
    boolean saveChat;
    /** The setup wizard was finished or skipped (Setup); true for settings from before 0.5.0. */
    boolean setupDone;
    /**
     * Unused since 0.10.1 (keeping the light on in reading mode blinked the
     * Nokia 6300's screen); still read and written so the record format stays.
     */
    boolean lightReading = true;
    /** Light the screen up when a reply arrives. */
    boolean lightReply = true;
    /** The user's notes for Claude ("my name is..., answer briefly"); "" if none. */
    String instructions = "";
    /** load() found saved settings; false right after installing (Backup may restore them). */
    boolean stored;

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
            stored = format >= 1 && format <= FORMAT;
            if (format >= 1 && format <= FORMAT) {
                url = in.readUTF();
                token = in.readUTF();
                testMode = in.readBoolean();
                verifiedUrl = in.readUTF();
            }
            if (format >= 2 && format <= FORMAT) {
                theme = in.readByte();
                fontSize = in.readByte();
                sound = in.readBoolean();
                vibrate = in.readBoolean();
            }
            if (format >= 3 && format <= FORMAT) {
                lang = in.readByte();
            }
            if (format >= 4 && format <= FORMAT) {
                webSearch = in.readBoolean();
                saveChat = in.readBoolean();
            }
            // settings from before 0.5.0 belong to a phone that is already set up
            setupDone = format >= 5 && format <= FORMAT ? in.readBoolean() : format >= 1 && format < 5;
            if (format >= 5 && format <= FORMAT) {
                lightReading = in.readBoolean();
                lightReply = in.readBoolean();
            }
            if (format >= 6 && format <= FORMAT) {
                instructions = in.readUTF();
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
        if (stored) {
            Backup.loaded(this);
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
            out.writeBoolean(webSearch);
            out.writeBoolean(saveChat);
            out.writeBoolean(setupDone);
            out.writeBoolean(lightReading);
            out.writeBoolean(lightReply);
            out.writeUTF(instructions);
            out.close();
            byte[] b = bo.toByteArray();
            rs = RecordStore.openRecordStore(STORE, true);
            if (rs.getNumRecords() == 0) {
                rs.addRecord(b, 0, b.length);
            } else {
                rs.setRecord(1, b, 0, b.length);
            }
            stored = true;
            Backup.changed(this);
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

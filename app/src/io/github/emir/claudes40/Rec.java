package io.github.emir.claudes40;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import javax.microedition.media.Manager;
import javax.microedition.media.MediaException;
import javax.microedition.media.Player;
import javax.microedition.media.control.RecordControl;

/**
 * Records one voice clip from the microphone into RAM (JSR 135
 * RecordControl). The only class that uses JSR 135 recording
 * (tools/check.py); callers check ClaudeS40MIDlet.hasRecording() first.
 * Blocks and may make the phone ask for permission: worker threads only.
 *
 * AMR is preferred (about 1.6 KB per second over mobile data); 8 kHz PCM
 * WAV (16 KB per second) is the fallback. The server recognises the format
 * by the first bytes, whatever the phone reports.
 */
final class Rec {

    /** Tried in this order; the phone may not know the AMR names. */
    private static final String[] AMR = { "capture://audio?encoding=amr", "capture://audio?encoding=audio/amr" };
    private static final String[] PCM = { "capture://audio?encoding=pcm&rate=8000&bits=16&channels=1",
        "capture://audio" };

    private Player player;
    private RecordControl control;
    private ByteArrayOutputStream out;
    /** Locator that worked, for the error text of a failed upload. */
    String locator = "";

    /**
     * Starts recording, at most maxBytes (if the phone supports a limit).
     * Returns null on success, or a reason for the user.
     */
    String start(int maxBytes) {
        String enc = ClaudeS40MIDlet.prop("audio.encodings").toLowerCase();
        String last = "";
        String[][] lists = enc.indexOf("amr") >= 0 ? new String[][] { AMR, PCM } : new String[][] { PCM, AMR };
        for (int l = 0; l < lists.length; l++) {
            for (int i = 0; i < lists[l].length; i++) {
                try {
                    if (open(lists[l][i], maxBytes)) {
                        return null;
                    }
                } catch (SecurityException e) {
                    close();
                    return L.t("Microphone access was denied. Answer 'Yes' when the phone asks.");
                } catch (MediaException e) {
                    last = e.getMessage();
                    close();
                } catch (IOException e) {
                    last = e.getMessage();
                    close();
                } catch (RuntimeException e) {
                    last = e.getClass().getName() + ": " + e.getMessage();
                    close();
                }
            }
        }
        return L.t("Could not start recording. ") + (last == null ? "" : last);
    }

    private boolean open(String loc, int maxBytes) throws MediaException, IOException {
        player = Manager.createPlayer(loc);
        player.realize();
        control = (RecordControl) player.getControl("RecordControl");
        if (control == null) {
            close();
            return false;
        }
        out = new ByteArrayOutputStream(16 * 1024);
        control.setRecordStream(out);
        try {
            control.setRecordSizeLimit(maxBytes);
        } catch (MediaException e) {
            // no size limit on this phone: the 30 s timer still stops it
        }
        control.startRecord();
        player.start();
        locator = loc;
        return true;
    }

    /** Stops and returns the clip; null if nothing was recorded. */
    byte[] stop() {
        byte[] b = null;
        try {
            if (control != null) {
                control.stopRecord();
                control.commit();
            }
            if (out != null && out.size() > 0) {
                b = out.toByteArray();
            }
        } catch (IOException e) {
            // nothing usable
        } catch (RuntimeException e) {
            // same
        } finally {
            close();
        }
        return b;
    }

    /** Drops the recording. */
    void cancel() {
        try {
            if (control != null) {
                control.stopRecord();
            }
        } catch (RuntimeException e) {
            // closing anyway
        }
        close();
    }

    private void close() {
        if (player != null) {
            try {
                player.close();
            } catch (RuntimeException e) {
                // ignore
            }
        }
        player = null;
        control = null;
        out = null;
    }
}

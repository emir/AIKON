package io.github.emir.claudes40;

import java.io.IOException;

import javax.microedition.media.Manager;
import javax.microedition.media.MediaException;
import javax.microedition.media.Player;
import javax.microedition.media.control.ToneControl;

/**
 * Short original tone sequences (MIDP 2.0 tone generator, no audio files):
 * a start-up jingle, a reply chime and a low error blip. Played on a
 * background thread; every failure is silent (the phone may be in a silent
 * profile or lack the tone device). Respects Ayarlar > Ses.
 */
final class Sound implements Runnable {

    // note numbers: C4 = 60 (ToneControl.C4); durations in 1/64 notes
    private static final byte C5 = 72;
    private static final byte E5 = 76;
    private static final byte G5 = 79;
    private static final byte A5 = 81;
    private static final byte C6 = 84;
    private static final byte D6 = 86;
    private static final byte E6 = 88;
    private static final byte G4 = 67;
    private static final byte C4 = ToneControl.C4;

    /** "AIKon" start-up jingle: rising arpeggio with a bright finish. */
    static final byte[] JINGLE = seq(30, new byte[] {
        G4, 4, C5, 4, E5, 4, G5, 8, ToneControl.SILENCE, 2, E5, 4, G5, 4, C6, 12,
        ToneControl.SILENCE, 2, D6, 4, E6, 20 });

    /** Reply received: two quick rising notes. */
    static final byte[] CHIME = seq(40, new byte[] { A5, 4, E6, 8 });

    /** Error: one low short note, then a lower one. */
    static final byte[] ERROR = seq(40, new byte[] { G4, 6, C4, 10 });

    private static Thread current;

    private final byte[] sequence;
    private final int millis;

    private Sound(byte[] sequence, int millis) {
        this.sequence = sequence;
        this.millis = millis;
    }

    /** Builds a ToneControl sequence: version, tempo (bpm/4), resolution 64, notes. */
    private static byte[] seq(int tempoDiv4, byte[] notes) {
        byte[] out = new byte[6 + notes.length];
        out[0] = ToneControl.VERSION;
        out[1] = 1;
        out[2] = ToneControl.TEMPO;
        out[3] = (byte) tempoDiv4;
        out[4] = ToneControl.RESOLUTION;
        out[5] = 64;
        System.arraycopy(notes, 0, out, 6, notes.length);
        return out;
    }

    /** Duration of a sequence in ms (tempo in bpm = 4 * byte, 64 units per whole note). */
    private static int duration(byte[] s) {
        int bpm = s[3] * 4;
        int units = 0;
        for (int i = 6; i + 1 < s.length; i += 2) {
            units += s[i + 1];
        }
        // one whole note = 4 beats = 240000 / bpm ms
        return units * 240000 / bpm / 64;
    }

    static void play(Settings settings, byte[] which) {
        if (!settings.sound) {
            return;
        }
        synchronized (Sound.class) {
            if (current != null && current.isAlive()) {
                return; // never overlap sounds
            }
            current = new Thread(new Sound(which, duration(which)));
            current.start();
        }
    }

    public void run() {
        Player p = null;
        try {
            p = Manager.createPlayer(Manager.TONE_DEVICE_LOCATOR);
            p.realize();
            ToneControl tc = (ToneControl) p.getControl("ToneControl");
            if (tc == null) {
                return;
            }
            tc.setSequence(sequence);
            p.start();
            Thread.sleep(millis + 250);
        } catch (MediaException e) {
            // no tone device / silent profile: stay quiet
        } catch (IOException e) {
            // same
        } catch (InterruptedException e) {
            // same
        } catch (RuntimeException e) {
            // includes SecurityException and emulator gaps
        } finally {
            if (p != null) {
                p.close();
            }
        }
    }
}

package io.github.emir.claudes40;

import javax.microedition.lcdui.Canvas;

/**
 * Key codes from QWERTY S60 phones (Nokia E63/E71/E72 layout), which pass the
 * letter of a key to a Canvas, not the digit printed on it: the digit keys
 * become Canvas.KEY_NUMx, Enter and the E63's raw centre key become the
 * centre key. The same letter map is used by other S60 MIDlets (Jimm,
 * qd-fork). Series 40 keypads never send these codes.
 */
final class Keys {

    /** The centre key as Nokia phones report it. */
    static final int SELECT = -5;

    private Keys() {
    }

    static int map(int k) {
        switch (k) {
        case 'r': case 'R': return Canvas.KEY_NUM1;
        case 't': case 'T': return Canvas.KEY_NUM2;
        case 'y': case 'Y': return Canvas.KEY_NUM3;
        case 'f': case 'F': return Canvas.KEY_NUM4;
        case 'g': case 'G': return Canvas.KEY_NUM5;
        case 'h': case 'H': return Canvas.KEY_NUM6;
        case 'v': case 'V': return Canvas.KEY_NUM7;
        case 'b': case 'B': return Canvas.KEY_NUM8;
        case 'n': case 'N': return Canvas.KEY_NUM9;
        case 'm': case 'M': return Canvas.KEY_NUM0;
        case 'u': case 'U': return Canvas.KEY_STAR;
        case 'j': case 'J': return Canvas.KEY_POUND;
        case 10: case 13: case 0xF845: return SELECT; // Enter (LF/CR); E63 raw centre key (EKeyDevice3)
        default: return k;
        }
    }

    /**
     * getGameAction() that never throws and never turns a letter into a game
     * action (S60 maps some QWERTY letters to UP/DOWN/FIRE).
     */
    static int action(Canvas c, int k) {
        if (k == SELECT) {
            return Canvas.FIRE;
        }
        if (k > 0 && (k < Canvas.KEY_NUM0 || k > Canvas.KEY_NUM9) && k != Canvas.KEY_STAR && k != Canvas.KEY_POUND) {
            return 0;
        }
        try {
            return c.getGameAction(k);
        } catch (IllegalArgumentException e) {
            return 0;
        }
    }
}

/*
 * Compile-only stub of the JSR 135 (MMAPI 1.1) GUIControl: only the members
 * Claude S40 uses. The phone provides the real interface; never packaged.
 */
package javax.microedition.media.control;

import javax.microedition.media.Control;

public interface GUIControl extends Control {
    int USE_GUI_PRIMITIVE = 0;

    Object initDisplayMode(int mode, Object arg);
}

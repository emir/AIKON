/*
 * Compile-only stub of the JSR 135 (MMAPI 1.1) VideoControl: only the
 * members AIKON uses (camera viewfinder and snapshot). The phone
 * provides the real interface; never packaged.
 */
package javax.microedition.media.control;

import javax.microedition.media.MediaException;

public interface VideoControl extends GUIControl {
    int USE_DIRECT_VIDEO = 1;

    void setDisplayLocation(int x, int y);

    void setDisplaySize(int width, int height) throws MediaException;

    void setVisible(boolean visible);

    byte[] getSnapshot(String imageType) throws MediaException;
}

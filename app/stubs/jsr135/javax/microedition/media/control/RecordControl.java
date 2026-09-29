/*
 * Compile-only stub of the JSR 135 (MMAPI 1.1) RecordControl: only the
 * members Claude S40 uses. MIDP 2.0's media subset has no recording; the
 * phone provides the real interface. Never packaged (tools/check.py fails
 * the build if a javax class ends up in the JAR).
 */
package javax.microedition.media.control;

import java.io.IOException;
import java.io.OutputStream;

import javax.microedition.media.Control;
import javax.microedition.media.MediaException;

public interface RecordControl extends Control {
    void setRecordStream(OutputStream stream);

    String getContentType();

    void startRecord();

    void stopRecord();

    void commit() throws IOException;

    int setRecordSizeLimit(int size) throws MediaException;
}

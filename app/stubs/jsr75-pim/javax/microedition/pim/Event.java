/*
 * Compile-only stub of the JSR 75 PIM Optional Package 1.0 API: only the
 * members Claude S40 uses. Never packaged (tools/check.py fails the build if
 * a javax class ends up in the JAR); the phone provides the real class.
 * Constant values are those of the PIM 1.0 specification.
 */
package javax.microedition.pim;

public interface Event extends PIMItem {
    int ALARM = 100;
    int END = 102;
    int NOTE = 104;
    int START = 106;
    int SUMMARY = 107;
}

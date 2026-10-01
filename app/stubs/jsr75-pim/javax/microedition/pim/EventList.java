/*
 * Compile-only stub of the JSR 75 PIM Optional Package 1.0 API: only the
 * members AIKon uses. Never packaged (tools/check.py fails the build if
 * a javax class ends up in the JAR); the phone provides the real class.
 * Constant values are those of the PIM 1.0 specification.
 */
package javax.microedition.pim;

public interface EventList extends PIMList {
    Event createEvent();
}

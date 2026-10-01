/*
 * Compile-only stub of the JSR 75 PIM Optional Package 1.0 API: only the
 * members AIKon uses. Never packaged (tools/check.py fails the build if
 * a javax class ends up in the JAR); the phone provides the real class.
 * Constant values are those of the PIM 1.0 specification.
 */
package javax.microedition.pim;

public abstract class PIM {
    public static final int CONTACT_LIST = 1;
    public static final int EVENT_LIST = 2;
    public static final int TODO_LIST = 3;
    public static final int READ_ONLY = 1;
    public static final int WRITE_ONLY = 2;
    public static final int READ_WRITE = 3;

    protected PIM() {
    }

    public static PIM getInstance() {
        throw new RuntimeException("stub");
    }

    public abstract PIMList openPIMList(int pimListType, int mode) throws PIMException;

    public abstract String[] listPIMLists(int pimListType);
}

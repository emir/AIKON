# ProGuard: Java ME preverification, removal of unused code and short names.
# -injars/-outjars/-libraryjars/-printmapping are passed by the Makefile.
# No optimization (it rewrites bytecode; the phone's VM is old) and no
# -overloadaggressively (same name, different return type).
# No -ignorewarnings / -dontwarn: every missing reference must be fixed.
#
# Short names: every class but the MIDlet moves to the default package as
# a, b, c, ... (lower case only: the build's class folder may sit on a
# case-insensitive disk). The mapping (build/mapping.txt) lets
# tools/check.py and the emulator harness find classes by their real names.
# The app finds nothing by name itself: Class.forName only probes the
# phone's optional APIs, resources are read by absolute paths.

-microedition
-dontoptimize
-dontusemixedcaseclassnames
-repackageclasses ''
-allowaccessmodification

-keep public class * extends javax.microedition.midlet.MIDlet

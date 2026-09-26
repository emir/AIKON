# ProGuard: Java ME preverification only.
# -injars/-outjars/-libraryjars are passed by the Makefile.
# No shrinking, optimization or obfuscation.
# No -ignorewarnings / -dontwarn: every missing reference must be fixed.

-microedition
-dontshrink
-dontoptimize
-dontobfuscate

-keep public class * extends javax.microedition.midlet.MIDlet

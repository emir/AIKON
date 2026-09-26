package moneo.claudes40;

/**
 * UI language. English or Turkish, chosen once at start-up:
 * Ayarlar > Dil / Settings > Language (Auto, English, Türkçe); "Auto" follows
 * the phone's microedition.locale ("tr..." -> Turkish, anything else ->
 * English). Strings are written side by side where they are used:
 * L.s("Türkçe", "English").
 */
final class L {

    static final int AUTO = 0;
    static final int TURKISH = 1;
    static final int ENGLISH = 2;

    private static boolean tr;

    private L() {
    }

    static void init(Settings s) {
        if (s.lang == TURKISH) {
            tr = true;
        } else if (s.lang == ENGLISH) {
            tr = false;
        } else {
            String loc = ClaudeS40MIDlet.prop("microedition.locale");
            tr = loc != null && loc.toLowerCase().startsWith("tr");
        }
    }

    static boolean turkish() {
        return tr;
    }

    static String s(String turkish, String english) {
        return tr ? turkish : english;
    }
}

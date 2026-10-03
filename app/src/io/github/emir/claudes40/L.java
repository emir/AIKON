package io.github.emir.claudes40;

import java.util.Hashtable;

/**
 * UI language, chosen at start-up (and again when Settings > Language
 * changes). The code is written in English: L.t("Save"), or L.f("{0}
 * credits", n) for texts with values. Every other language, Turkish too,
 * is written in app/lang/xx.txt (the English text, a TAB, the translation)
 * and comes from the server (LangPack, 0.18.0): the JAR carries English
 * only. Until a language has come, and for any text it lacks, the screens
 * stay English, so a half-done translation never breaks a screen.
 *
 * "Same as phone" follows microedition.locale: a language we have by its
 * two letters, anything else English (the default).
 */
final class L {

    static final int AUTO = 0;
    static final int TURKISH = 1;
    static final int ENGLISH = 2;
    /** Settings.lang of LANGS[i] is FIRST + i. */
    static final int FIRST = 3;

    /** Two-letter codes of the languages besides Turkish (Settings.lang 1), and their own names. */
    static final String[] LANGS = { "es", "pt", "fr", "de", "ru", "id" };
    static final String[] NAMES = { "Español", "Português", "Français", "Deutsch", "Русский", "Bahasa Indonesia" };

    private static String code = "en";
    private static Hashtable table;

    private L() {
    }

    static void init(Settings s) {
        String c;
        if (s.lang == TURKISH) {
            c = "tr";
        } else if (s.lang == ENGLISH) {
            c = "en";
        } else if (s.lang >= FIRST && s.lang < FIRST + LANGS.length) {
            c = LANGS[s.lang - FIRST];
        } else {
            String loc = ClaudeS40MIDlet.prop("microedition.locale");
            c = loc != null && loc.length() >= 2 ? loc.substring(0, 2).toLowerCase() : "en";
            if (!c.equals("tr") && index(c) < 0) {
                c = "en";
            }
        }
        code = c;
        table = c.equals("en") ? null : LangPack.table(c);
    }

    /** The language wanted is shown: English, or it has come from the server. */
    static boolean present() {
        return code.equals("en") || table != null;
    }

    /** The two letters of a Settings.lang value ("en", "tr", "es", ...); null for "Same as phone". */
    static String codeOf(int lang) {
        if (lang == TURKISH) {
            return "tr";
        }
        if (lang >= FIRST && lang < FIRST + LANGS.length) {
            return LANGS[lang - FIRST];
        }
        return lang == ENGLISH ? "en" : null;
    }

    /** The language wanted, as two letters ("tr", "en", "es", ...); English is shown until it has come. */
    static String code() {
        return code;
    }

    /** An English text (a key of the language files) in the language now in use. */
    static String t(String english) {
        if (table == null) {
            return english;
        }
        String v = (String) table.get(new Integer(english.hashCode()));
        return v != null ? v : english;
    }

    /** L.t with values: {0}, {1} in the text are replaced by args[0], args[1] after translating. */
    static String f(String english, String[] args) {
        String p = t(english);
        for (int i = 0; i < args.length; i++) {
            String mark = "{" + i + "}";
            int at;
            while ((at = p.indexOf(mark)) >= 0) {
                p = p.substring(0, at) + args[i] + p.substring(at + mark.length());
            }
        }
        return p;
    }

    static String f(String english, String a) {
        return f(english, new String[] { a });
    }

    static String f(String english, String a, String b) {
        return f(english, new String[] { a, b });
    }

    private static int index(String c) {
        for (int i = 0; i < LANGS.length; i++) {
            if (LANGS[i].equals(c)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Adds the lines of a part from the server (LangPack) to h: the
     * String.hashCode of the English text as 8 hex digits, a TAB, the
     * translation (backslash escapes). Malformed lines are skipped.
     */
    static void parse(String text, Hashtable h) {
        int pos = 0;
        while (pos < text.length()) {
            int nl = text.indexOf('\n', pos);
            int end = nl < 0 ? text.length() : nl;
            if (end - pos > 9 && text.charAt(pos + 8) == '\t') {
                try {
                    int hash = (int) Long.parseLong(text.substring(pos, pos + 8), 16);
                    h.put(new Integer(hash), unescape(text.substring(pos + 9, end)));
                } catch (NumberFormatException e) {
                    // skipped
                }
            }
            pos = end + 1;
        }
    }

    private static String unescape(String s) {
        if (s.indexOf('\\') < 0) {
            return s;
        }
        StringBuffer b = new StringBuffer(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '\\' && i + 1 < s.length()) {
                char nx = s.charAt(++i);
                b.append(nx == 'n' ? '\n' : nx == 't' ? '\t' : nx);
            } else {
                b.append(ch);
            }
        }
        return b.toString();
    }
}

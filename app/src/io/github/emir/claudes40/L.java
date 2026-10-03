package io.github.emir.claudes40;

import java.io.IOException;
import java.io.InputStream;
import java.util.Hashtable;

/**
 * UI language, chosen at start-up (and again when Settings > Language
 * changes). Turkish and English are written side by side where they are
 * used: L.s("Türkçe", "English"). Other languages come from /lang/xx.txt in
 * the JAR: one line per string, the English text, a TAB, the translation
 * (a backslash-n stands for a line break, backslash-t for a tab). A string
 * without a line there stays English, so a half-done translation never
 * breaks a screen. Texts with values in them use {0}, {1} (L.f).
 *
 * "Same as phone" follows microedition.locale: "tr..." Turkish, a language
 * of LANGS by its two letters, anything else English.
 */
final class L {

    static final int AUTO = 0;
    static final int TURKISH = 1;
    static final int ENGLISH = 2;
    /** Settings.lang of LANGS[i] is FIRST + i. */
    static final int FIRST = 3;

    /** Two-letter codes of the languages in /lang, and their own names. */
    static final String[] LANGS = { "es", "pt", "fr", "de", "ru", "id" };
    static final String[] NAMES = { "Español", "Português", "Français", "Deutsch", "Русский", "Bahasa Indonesia" };

    private static boolean tr;
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
        tr = c.equals("tr");
        table = tr || c.equals("en") ? null : load(c);
    }

    /** The language now in use, as two letters ("tr", "en", "es", ...). */
    static String code() {
        return code;
    }

    static boolean turkish() {
        return tr;
    }

    static String s(String turkish, String english) {
        if (tr) {
            return turkish;
        }
        return t(english);
    }

    /** An English text (a key of the language files) in the language now in use (not Turkish). */
    static String t(String english) {
        if (table == null) {
            return english;
        }
        String v = (String) table.get(english);
        return v != null ? v : english;
    }

    /** L.s with values: {0}, {1} in the patterns are replaced by args[0], args[1]. */
    static String f(String turkish, String english, String[] args) {
        String p = s(turkish, english);
        for (int i = 0; i < args.length; i++) {
            String mark = "{" + i + "}";
            int at;
            while ((at = p.indexOf(mark)) >= 0) {
                p = p.substring(0, at) + args[i] + p.substring(at + mark.length());
            }
        }
        return p;
    }

    static String f(String turkish, String english, String a) {
        return f(turkish, english, new String[] { a });
    }

    static String f(String turkish, String english, String a, String b) {
        return f(turkish, english, new String[] { a, b });
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
     * Reads /lang/keys.txt (the English texts) and /lang/xx.txt (their
     * translations, line by line in the same order; an empty line is not
     * translated). Both are made from app/lang by tools/strings.py pack.
     * Null if a file is missing or unreadable (English then).
     */
    private static Hashtable load(String c) {
        String keys = read("/lang/keys.txt");
        String vals = read("/lang/" + c + ".txt");
        if (keys == null || vals == null) {
            return null;
        }
        Hashtable h = new Hashtable(800);
        int ks = 0;
        int vs = 0;
        while (ks < keys.length() && vs < vals.length()) {
            int ke = keys.indexOf('\n', ks);
            int ve = vals.indexOf('\n', vs);
            if (ke < 0) {
                ke = keys.length();
            }
            if (ve < 0) {
                ve = vals.length();
            }
            if (ve > vs) {
                h.put(unescape(keys.substring(ks, ke)), unescape(vals.substring(vs, ve)));
            }
            ks = ke + 1;
            vs = ve + 1;
        }
        return h;
    }

    /** A UTF-8 resource of the JAR as text, or null. */
    private static String read(String name) {
        InputStream in = L.class.getResourceAsStream(name);
        if (in == null) {
            return null;
        }
        try {
            byte[] buf = new byte[4096];
            byte[] all = new byte[0];
            int n;
            while ((n = in.read(buf)) > 0) {
                byte[] next = new byte[all.length + n];
                System.arraycopy(all, 0, next, 0, all.length);
                System.arraycopy(buf, 0, next, all.length, n);
                all = next;
            }
            return new String(all, "UTF-8");
        } catch (IOException e) {
            return null;
        } finally {
            try {
                in.close();
            } catch (IOException e) {
                // nothing to do
            }
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

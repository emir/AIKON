package io.github.emir.claudes40;

import java.io.IOException;
import java.io.InputStream;
import java.util.Hashtable;

/**
 * UI language, chosen at start-up (and again when Settings > Language
 * changes). The code is written in English: L.t("Save"), or L.f("{0}
 * credits", n) for texts with values. Every other language, Turkish too,
 * comes from app/lang/xx.txt (the English text, a TAB, the translation),
 * packed into the JAR by tools/strings.py. A text without a translation
 * stays English, so a half-done translation never breaks a screen.
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

    /** Two-letter codes of the languages in /lang besides Turkish (Settings.lang 1), and their own names. */
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
        table = c.equals("en") ? null : load(c);
    }

    /** The language now in use, as two letters ("tr", "en", "es", ...). */
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
     * Reads /lang/keys.bin (the String.hashCode of each English text, 4
     * bytes each) and /lang/xx.txt (their translations, line by line in the
     * same order; an empty line is not translated). Both are made from
     * app/lang by tools/strings.py pack, which fails on two keys with one
     * hash. Null if a file is missing or unreadable (English then).
     */
    private static Hashtable load(String c) {
        byte[] keys = read("/lang/keys.bin");
        byte[] raw = read("/lang/" + c + ".txt");
        String vals;
        try {
            vals = raw != null ? new String(raw, "UTF-8") : null;
        } catch (IOException e) {
            vals = null;
        }
        if (keys == null || vals == null) {
            return null;
        }
        Hashtable h = new Hashtable(800);
        int ks = 0;
        int vs = 0;
        while (ks + 4 <= keys.length && vs < vals.length()) {
            int hash = (keys[ks] & 0xFF) << 24 | (keys[ks + 1] & 0xFF) << 16
                    | (keys[ks + 2] & 0xFF) << 8 | (keys[ks + 3] & 0xFF);
            int ve = vals.indexOf('\n', vs);
            if (ve < 0) {
                ve = vals.length();
            }
            if (ve > vs) {
                h.put(new Integer(hash), unescape(vals.substring(vs, ve)));
            }
            ks += 4;
            vs = ve + 1;
        }
        return h;
    }

    /** A resource of the JAR, or null. */
    private static byte[] read(String name) {
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
            return all;
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

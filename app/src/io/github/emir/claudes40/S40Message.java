package io.github.emir.claudes40;

import java.util.Hashtable;

/**
 * "S40/1" text format shared with the gateway (services/claude-s40-gateway,
 * src/protocol.ts):
 *
 *   S40/1\n
 *   key: value\n   (zero or more)
 *   \n
 *   free text
 */
final class S40Message {

    static final String MAGIC = "S40/1";

    final Hashtable fields = new Hashtable();
    String text = "";

    String field(String key) {
        String v = (String) fields.get(key);
        return v == null ? "" : v;
    }

    boolean flag(String key) {
        return "1".equals(fields.get(key));
    }

    /** Returns null if the text is not an S40/1 message. */
    static S40Message parse(String body) {
        if (body == null) {
            return null;
        }
        int pos = 0;
        int n = body.length();
        S40Message m = new S40Message();
        boolean first = true;
        while (pos <= n) {
            int nl = body.indexOf('\n', pos);
            int end = nl < 0 ? n : nl;
            String line = body.substring(pos, end);
            if (line.length() > 0 && line.charAt(line.length() - 1) == '\r') {
                line = line.substring(0, line.length() - 1);
            }
            pos = nl < 0 ? n + 1 : nl + 1;
            if (first) {
                if (!MAGIC.equals(line.trim())) {
                    return null;
                }
                first = false;
                continue;
            }
            if (line.length() == 0) {
                m.text = pos <= n ? body.substring(pos) : "";
                return m;
            }
            int c = line.indexOf(':');
            if (c <= 0) {
                return null;
            }
            m.fields.put(line.substring(0, c).trim().toLowerCase(), line.substring(c + 1).trim());
        }
        return first ? null : m;
    }

    static String format(String[] keys, String[] values, String text) {
        StringBuffer sb = new StringBuffer(MAGIC).append('\n');
        for (int i = 0; i < keys.length; i++) {
            sb.append(keys[i]).append(": ").append(oneLine(values[i])).append('\n');
        }
        sb.append('\n');
        if (text != null) {
            sb.append(text);
        }
        return sb.toString();
    }

    private static String oneLine(String v) {
        if (v == null) {
            return "";
        }
        return v.replace('\n', ' ').replace('\r', ' ');
    }
}

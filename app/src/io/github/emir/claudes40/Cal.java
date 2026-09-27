package io.github.emir.claudes40;

import java.util.Calendar;

/**
 * A calendar or to-do entry that Claude put on a line of its reply (the
 * server asks for this form when the phone can use its calendar):
 *
 *   EVENT: 2026-09-27 15:00 | Dentist
 *   TODO: 2026-09-28 | Buy bread
 *
 * The chat shows such a line in a readable form (shown()); the message
 * actions offer "Add to calendar", which opens CalendarForm prefilled. Nothing
 * is written to the phone's calendar before the user saves that form.
 */
final class Cal {

    final boolean todo;
    final String title;
    /** Phone-local start (event) or due date (to-do), in ms. */
    final long when;

    private Cal(boolean todo, String title, long when) {
        this.todo = todo;
        this.title = title;
        this.when = when;
    }

    /** The last entry line of a reply, or null. */
    static Cal parse(String text) {
        int[] at = find(text);
        return at == null ? null : line(text.substring(at[0], at[1]));
    }

    /** The text with its entry line (if any) in a readable form. */
    static String shown(String text) {
        int[] at = find(text);
        if (at == null) {
            return text;
        }
        Cal c = line(text.substring(at[0], at[1]));
        String nice = "» " + (c.todo ? L.s("Yapılacak: ", "To-do: ") + Text.local(c.when, false)
                : L.s("Takvim: ", "Calendar: ") + Text.local(c.when, true)) + " · " + c.title;
        return text.substring(0, at[0]) + nice + text.substring(at[1]);
    }

    /** {start, end} of the last line that is a valid entry, or null. */
    private static int[] find(String text) {
        int end = text.length();
        while (end > 0) {
            int start = text.lastIndexOf('\n', end - 1) + 1;
            if (line(text.substring(start, end)) != null) {
                return new int[] { start, end };
            }
            end = start - 1;
        }
        return null;
    }

    /** "EVENT: YYYY-MM-DD HH:MM | title" or "TODO: YYYY-MM-DD | title"; null if not exactly that. */
    private static Cal line(String l) {
        l = l.trim();
        boolean todo;
        if (l.startsWith("EVENT:")) {
            todo = false;
            l = l.substring(6).trim();
        } else if (l.startsWith("TODO:")) {
            todo = true;
            l = l.substring(5).trim();
        } else {
            return null;
        }
        int bar = l.indexOf('|');
        if (bar < 0) {
            return null;
        }
        String when = l.substring(0, bar).trim();
        String title = l.substring(bar + 1).trim();
        if (title.length() == 0 || when.length() != (todo ? 10 : 16)) {
            return null;
        }
        int y = num(when, 0, 4);
        int mo = num(when, 5, 7);
        int d = num(when, 8, 10);
        int h = todo ? 9 : num(when, 11, 13);
        int mi = todo ? 0 : num(when, 14, 16);
        if (y < 2000 || mo < 1 || mo > 12 || d < 1 || d > 31 || h < 0 || h > 23 || mi < 0 || mi > 59
                || when.charAt(4) != '-' || when.charAt(7) != '-' || (!todo && (when.charAt(10) != ' ' || when.charAt(13) != ':'))) {
            return null;
        }
        Calendar c = Calendar.getInstance();
        c.set(Calendar.YEAR, y);
        c.set(Calendar.MONTH, mo - 1);
        c.set(Calendar.DAY_OF_MONTH, d);
        c.set(Calendar.HOUR_OF_DAY, h);
        c.set(Calendar.MINUTE, mi);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return new Cal(todo, Text.clip(title, 100), c.getTime().getTime());
    }

    private static int num(String s, int from, int to) {
        int v = 0;
        for (int i = from; i < to; i++) {
            char ch = s.charAt(i);
            if (ch < '0' || ch > '9') {
                return -1;
            }
            v = v * 10 + (ch - '0');
        }
        return v;
    }
}

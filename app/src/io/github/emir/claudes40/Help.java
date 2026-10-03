package io.github.emir.claudes40;

import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.CommandListener;
import javax.microedition.lcdui.Display;
import javax.microedition.lcdui.Displayable;

/**
 * Explanations on request. Screens show one short line; the longer text
 * (why, what is stored where, what it costs) sits behind an "Info" command
 * and opens on its own page, from which Back returns to the screen.
 */
final class Help implements CommandListener {

    private final Display display;
    private final Displayable back;
    private final Command backCmd = new Command(L.t("Back"), Command.BACK, 1);

    /** The "Bilgi" / "Info" command for a screen (Command.HELP: the phone puts it under Options). */
    static Command command() {
        return new Command(L.t("Info"), Command.HELP, 8);
    }

    private Help(Display display, Displayable back) {
        this.display = display;
        this.back = back;
    }

    /** Shows `text` (paragraphs separated by blank lines) titled `title`; Back returns to `back`. */
    static void show(Display display, String title, String text, Displayable back) {
        Help h = new Help(display, back);
        TextPage f = new TextPage(title);
        int start = 0;
        while (start < text.length()) {
            int end = text.indexOf("\n\n", start);
            if (end < 0) {
                end = text.length();
            }
            f.append(null, text.substring(start, end));
            start = end + 2;
        }
        f.addCommand(h.backCmd);
        f.setCommandListener(h);
        display.setCurrent(f);
    }

    public void commandAction(Command c, Displayable d) {
        if (c == backCmd) {
            display.setCurrent(back);
        }
    }
}

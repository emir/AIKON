package io.github.emir.claudes40;

import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.CommandListener;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.Form;
import javax.microedition.lcdui.StringItem;
import javax.microedition.lcdui.TextField;

/**
 * First-run setup in three steps, "Kurulum 1/3" .. "3/3" (step indexes 1..3):
 *
 *   1 server address (https:// only)   2 connection test (ConnTest)
 *   3 pairing (Pairing, or the credit code)
 *
 * The language is not asked: it follows the phone ("Same as phone") and
 * can be changed in Settings.
 *
 * Shown after the splash while Settings.setupDone is false (a fresh install;
 * reinstalling deletes the settings) and from Settings > "Kurulum
 * sihirbazı". Every step can be left with "Kurulumu atla"; finishing or
 * skipping sets setupDone. Each step shows one short line; the longer
 * explanation is behind "Bilgi" / "Info" (Help). Nothing here is sent anywhere except by the
 * existing connection test and pairing.
 *
 * A build that names its server (ClaudeS40-Gateway, e.g. the download from
 * the server's site) still on that address asks for nothing but the pairing
 * (the credit code form on a server that sells credits): the language
 * follows the phone (it stays "Same as phone"), the address is in the build
 * and the connection test runs by itself first, shown only as "Connecting..."
 * (ConnTest.runAuto); only a failed test stays on screen. Back there leaves
 * the setup like "Skip setup".
 */
final class Setup implements CommandListener {

    /** The last step's index (and the number of steps shown). */
    static final int STEPS = 3;

    private final ClaudeS40MIDlet midlet;
    private int step;
    /** The two-step flow of a build that names its server (see above). */
    static boolean preset;
    private Form form;
    private TextField urlField;
    private Command nextCmd;
    private Command backCmd;
    private Command skipCmd;
    private Command startCmd;
    private Command finishCmd;
    private Command againCmd;
    private Command helpCmd;

    Setup(ClaudeS40MIDlet midlet) {
        this.midlet = midlet;
    }

    /** "Kurulum 2/3" / "Setup 2/3" for step index 1..3 (just "Kurulum" in the short flow). */
    static String title(int step) {
        if (preset) {
            return L.t("Setup");
        }
        return L.t("Setup ") + step + "/" + STEPS;
    }

    void start() {
        Settings s = midlet.settings;
        String gw = midlet.attr("ClaudeS40-Gateway").trim();
        preset = Net.isHttps(gw) && gw.equals(s.url);
        step = preset ? 2 : 1; // the short flow starts with the (automatic) connection test
        show();
    }

    /** Shows the current step (again). */
    void show() {
        // built each time: the language may have changed in step 1
        nextCmd = new Command(L.t("Next"), Command.OK, 1);
        backCmd = new Command(L.t("Back"), Command.BACK, 1);
        skipCmd = new Command(L.t("Skip setup"), Command.SCREEN, 5);
        startCmd = new Command(L.t("Start"), Command.OK, 1);
        finishCmd = new Command(L.t("Finish"), Command.OK, 1);
        againCmd = new Command(L.t("Pair again"), Command.SCREEN, 2);
        helpCmd = Help.command();
        Settings s = midlet.settings;
        if (step == 2) {
            if (preset) {
                new ConnTest(midlet, this).runAuto(midlet.display());
            } else {
                new ConnTest(midlet, this).show(midlet.display());
            }
            return;
        }
        if (step == 3 && preset && s.credits && s.token.length() < 16) {
            // straight to the credit code (Back returns to the language)
            Credits.pair(midlet, this, midlet.display(), null);
            return;
        }
        form = new Form(title(step));
        if (step == 1) {
            urlField = new TextField(L.t("Server address"), s.url.length() > 0 ? s.url : "https://",
                    200, TextField.URL);
            form.append(urlField);
            form.addCommand(nextCmd);
        } else {
            if (s.token.length() >= 16) {
                form.append(new StringItem(null, L.t("This phone is already paired. Setup is complete.")));
                form.addCommand(finishCmd);
                form.addCommand(againCmd);
            } else {
                form.append(new StringItem(null, s.credits
                        ? L.t("Last step: press Start and type your credit code.")
                        : L.t("Last step: press Start; the server's owner approves the code shown.")));
                form.addCommand(startCmd);
            }
            form.addCommand(backCmd);
        }
        if (step < STEPS || s.token.length() < 16) {
            form.addCommand(skipCmd);
        }
        form.addCommand(helpCmd);
        form.setCommandListener(this);
        midlet.display().setCurrent(form);
    }

    public void commandAction(Command c, Displayable d) {
        if (c == helpCmd) {
            Help.show(midlet.display(), title(step), helpText(), d);
        } else if (c == skipCmd) {
            skip();
        } else if (c == backCmd) {
            back();
        } else if (c == finishCmd) {
            finish();
        } else if (c == startCmd || c == againCmd) {
            Credits.pair(midlet, this, midlet.display(), form);
        } else if (c == nextCmd) {
            if (step == 1) {
                saveUrl();
            }
        }
    }

    /** The longer explanation of the current step. */
    private String helpText() {
        if (step == 1) {
            return L.t("Welcome! Three steps: server address, connection test, pairing. Every step can be left with "
                    + "'Skip setup'; come back later from Settings > Options > Setup wizard. The language follows the "
                    + "phone; change it in Settings.\n\nJust trying it out? Skip setup, then Settings > Test mode (fake "
                    + "replies, no network).\n\n"
                    + "The server's owner gives you its address.\n\nIt must start with https://; unencrypted "
                    + "connections are never used. The next step tests the secure connection.");
        }
        if (midlet.settings.credits) {
            return L.t("This server works with credit codes: press Start and type the 16-digit code you bought. The phone "
                    + "is paired with the code; no approval by the server's owner.\n\nThe access code is kept on this "
                    + "phone and in the setup backup on the memory card, and sent only to the https:// address.");
        }
        return L.t("Press Start and a 6-digit code appears. Once the server's owner approves it, the phone fetches its "
                + "access code by itself; nothing long to type.\n\nThe access code is kept on this phone and in the "
                + "setup backup on the memory card, and sent only to the https:// address.");
    }

    private void saveUrl() {
        Settings s = midlet.settings;
        String url = urlField.getString().trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        if (!Net.isHttps(url) || url.length() <= "https://".length()) {
            midlet.info(L.t("The address must start with https://, for example https://your-server. HTTP is not supported."),
                    form);
            return;
        }
        if (!url.equals(s.url)) {
            s.url = url;
            s.verifiedUrl = ""; // new address: connection test again
            s.credits = false;  // known again after the test
        }
        String err = s.save();
        if (err != null) {
            midlet.info(err, form);
            return;
        }
        next();
    }

    /** From ConnTest (after a passed test) and the steps above. */
    void next() {
        if (step < STEPS) {
            step++;
        }
        show();
    }

    /** The automatic connection test passed: on to the pairing. */
    void connected() {
        step = 3;
        show();
    }

    void back() {
        if (preset) {
            skip(); // nothing before the pairing in the short flow
            return;
        }
        if (step > 1) {
            step--;
        }
        show();
    }

    /** Back from the pairing or credit code screen. */
    void pairingBack() {
        if (preset && midlet.settings.credits) {
            back();
        } else {
            show();
        }
    }

    /** Pairing done, or already paired. */
    void finish() {
        Settings s = midlet.settings;
        s.setupDone = true;
        String err = s.save();
        if (err == null && s.ready()) {
            midlet.setupDone(L.t("All set!"));
            return;
        }
        midlet.setupFinished(err != null ? err
                : L.t("Setup saved. Finish the missing steps from Settings."));
    }

    private void skip() {
        Settings s = midlet.settings;
        s.setupDone = true;
        String err = s.save();
        midlet.setupSkipped(err != null ? err : L.t("Setup skipped. Settings > Options > Setup wizard."));
    }
}

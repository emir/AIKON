package io.github.emir.claudes40;

import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.CommandListener;
import javax.microedition.lcdui.Display;
import javax.microedition.lcdui.Displayable;

/**
 * Pairing, so no long access code has to be typed on the keypad:
 *
 *   phone: POST /v1/pair/start  -> shows a 6-digit code
 *   owner: server/deploy/admin.sh SERVER pair <code>   (admin approval)
 *   phone: POST /v1/pair/claim every 5 s  -> receives its access code
 *
 * Only runs after the connection test has passed for the configured URL,
 * because the pairing secret must travel over the verified HTTPS path.
 * Polling stops on success, expiry, "İptal", 10 minutes or 3 network
 * errors in a row; nothing is repeated beyond that. As the last step of the
 * setup wizard, "Geri"/"İptal" return to the wizard and success offers
 * "Bitir".
 */
final class Pairing implements CommandListener, Runnable {

    private static final long POLL_MS = 5000;
    private static final long MAX_MS = 10 * 60 * 1000;
    private static final int MAX_NET_ERRORS = 3;

    private final ClaudeS40MIDlet midlet;
    /** The wizard this pairing is a step of, or null. */
    private final Setup setup;
    private final TextPage form;
    private final int codeItem;
    private final int statusItem;
    private final Command cancelCmd = new Command(L.t("Cancel"), Command.BACK, 1);
    private final Command backCmd = new Command(L.t("Back"), Command.BACK, 1);
    private final Command finishCmd = new Command(L.t("Finish"), Command.OK, 1);
    private final Command helpCmd = Help.command();
    private volatile boolean cancelled;

    Pairing(ClaudeS40MIDlet midlet, Setup setup) {
        this.midlet = midlet;
        this.setup = setup;
        form = new TextPage(setup != null ? Setup.title(Setup.STEPS) : L.t("Pair this phone"));
        codeItem = form.append(L.t("Pairing code"), "-");
        form.setBig(codeItem);
        statusItem = form.append(L.t("Status"), "");
        form.append(null, L.t("Give this code to the server's owner."));
        form.addCommand(cancelCmd);
        form.addCommand(helpCmd);
        form.setCommandListener(this);
    }

    void start(Display d) {
        status(L.t("Requesting a code..."));
        d.setCurrent(form);
        new Thread(this).start();
    }

    public void commandAction(Command c, Displayable d) {
        if (c == helpCmd) {
            Help.show(midlet.display(), L.t("Pairing"), L.t("The server's owner approves the code on the server:\nadmin.sh SERVER pair <code>\n\nAfter "
                    + "approval the phone fetches its access code by itself; keep this screen open. The code is valid "
                    + "for 10 minutes."), form);
        } else if (c == cancelCmd || c == backCmd) {
            cancelled = true;
            if (setup != null) {
                setup.show();
            } else {
                midlet.showMenu();
            }
        } else if (c == finishCmd) {
            setup.finish(null);
        }
    }

    private void status(String s) {
        form.setText(statusItem, s);
    }

    private void finish(String s) {
        finish(s, false);
    }

    private void finish(String s, boolean paired) {
        status(s);
        form.removeCommand(cancelCmd);
        form.addCommand(backCmd);
        if (paired && setup != null) {
            form.addCommand(finishCmd);
        }
    }

    public void run() {
        Settings s = midlet.settings;
        Net.Result r = Net.request(s.url + "/v1/pair/start", "POST", null,
                S40Message.format(new String[0], new String[0], ""), midlet.userAgent(), null);
        if (!r.ok()) {
            finish(L.t("Could not start. ") + Net.explain(r));
            return;
        }
        if (r.msg == null || !"ok".equals(r.msg.field("status"))) {
            String st = r.msg == null ? "HTTP " + r.httpCode : r.msg.field("status");
            finish("pair_busy".equals(st) ? L.t("Too many pending pairings; try again in a few minutes.")
                    : L.t("Could not start (") + st + ").");
            return;
        }
        String pair = r.msg.field("pair");
        String code = r.msg.field("code");
        form.setText(codeItem, code.length() == 6 ? code.substring(0, 3) + " " + code.substring(3) : code);
        status(L.t("Waiting for approval..."));

        long deadline = System.currentTimeMillis() + MAX_MS;
        int netErrors = 0;
        while (!cancelled && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(POLL_MS);
            } catch (InterruptedException e) {
                // ignore
            }
            if (cancelled) {
                return;
            }
            Net.Result c = Net.request(s.url + "/v1/pair/claim", "POST", null,
                    S40Message.format(new String[] { "pair" }, new String[] { pair }, ""), midlet.userAgent(), null);
            if (!c.ok() || c.msg == null) {
                netErrors++;
                if (netErrors >= MAX_NET_ERRORS) {
                    finish(L.t("Connection problem, pairing stopped. ") + (c.ok() ? "" : Net.explain(c)));
                    return;
                }
                status(L.t("Connection problem, trying again (") + netErrors + "/" + MAX_NET_ERRORS + ")...");
                continue;
            }
            netErrors = 0;
            String st = c.msg.field("status");
            if ("ok".equals(st)) {
                String token = c.msg.field("token");
                s.token = token;
                String err = s.save();
                form.setText(codeItem, "OK");
                finish(err != null ? err
                        : L.t("Paired (") + c.msg.field("device")
                                + L.t("). Access code saved; chat is ready."),
                        err == null);
                return;
            }
            if ("pending".equals(st)) {
                status(L.t("Waiting for approval..."));
                continue;
            }
            finish(L.t("Pairing expired. Start again."));
            return;
        }
        if (!cancelled) {
            finish(L.t("Pairing expired. Start again."));
        }
    }
}

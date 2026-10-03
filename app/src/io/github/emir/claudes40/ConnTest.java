package io.github.emir.claudes40;

import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.CommandListener;
import javax.microedition.lcdui.Display;
import javax.microedition.lcdui.Displayable;

/**
 * Connection test: GET /health, then POST /echo with a fixed Turkish probe.
 * No access code and no chat text is sent. Reports what the phone's TLS
 * stack says about the connection; a successful connection alone is not
 * proof that its security level is current.
 *
 * Only when both steps pass is the URL marked as verified; chat is locked
 * until then. As step 3 of the setup wizard (Setup) it gets "İleri" once
 * the address is verified, and "Geri" goes to the previous step.
 *
 * The screen shows only a short checklist and the result; the technical
 * details (server version, TLS of both ends, bytes) are behind "Ayrıntılar".
 * The centre key starts the test, and in the wizard goes on once it passed.
 *
 * Started by the wizard of a build that names its server (runAuto), it is
 * quiet: only "Connecting..." with the spinner while /health is checked (the build
 * names a known server, so the UTF-8 round trip is left to this screen in
 * Settings); the checklist appears only if the server cannot be reached.
 */
final class ConnTest implements CommandListener, Runnable {

    /** Must equal echoProbe in server/main.go (a protocol constant: keeps the old name). */
    static final String PROBE = "Claude S40 UTF-8: ç ğ ı İ ö ş ü Ç Ğ Ö Ş Ü";

    private final ClaudeS40MIDlet midlet;
    /** The wizard this test is a step of, or null. */
    private final Setup setup;
    private final TextPage form;
    private final Command startCmd = new Command(L.t("Start"), Command.OK, 1);
    private final Command againCmd = new Command(L.t("Test again"), Command.SCREEN, 2);
    private final Command detailsCmd = new Command(L.t("Details"), Command.HELP, 8);
    private final Command backCmd = new Command(L.t("Back"), Command.BACK, 1);
    private final Command nextCmd = new Command(L.t("Next"), Command.OK, 1);
    private boolean running;
    /** Started by the wizard of a build that names its server: goes on by itself once it passed. */
    private boolean auto;
    /** Commands shown now (they change with the state). */
    private Command[] shown = new Command[0];
    /** The "Testing..." line with the spinner, kept last while the test runs (-1: none). */
    private int busyLine = -1;
    /** Technical details of the last run, for "Ayrıntılar". */
    private final StringBuffer details = new StringBuffer();

    ConnTest(ClaudeS40MIDlet midlet, Setup setup) {
        this.midlet = midlet;
        this.setup = setup;
        form = new TextPage(setup != null ? Setup.title(2) : L.t("Connection test"));
        form.addCommand(backCmd);
        form.setCommandListener(this);
        intro();
        commands();
    }

    /** Start (or, in the wizard once verified, Next) on the centre key; Details once there are any. */
    private synchronized void commands() {
        boolean next = setup != null && midlet.settings.connectionVerified() && !running;
        Command[] want;
        if (running) {
            want = new Command[0];
        } else if (next) {
            want = details.length() > 0 ? new Command[] { nextCmd, againCmd, detailsCmd } : new Command[] { nextCmd, againCmd };
        } else {
            want = details.length() > 0 ? new Command[] { startCmd, detailsCmd } : new Command[] { startCmd };
        }
        for (int i = 0; i < shown.length; i++) {
            form.removeCommand(shown[i]);
        }
        for (int i = 0; i < want.length; i++) {
            form.addCommand(want[i]);
        }
        shown = want;
    }

    private void intro() {
        form.deleteAll();
        String url = midlet.settings.url.length() > 0 ? midlet.settings.url : L.t("(not set)");
        form.append(L.t("Server"), url);
        if (midlet.settings.connectionVerified()) {
            line(L.t("This address was verified before."));
        } else {
            line(L.t("Press Start to check the secure connection and Turkish characters."));
        }
    }

    void show(Display d) {
        if (!running) {
            intro();
            commands();
        }
        d.setCurrent(form);
    }

    public void commandAction(Command c, Displayable d) {
        if (c == backCmd) {
            if (setup != null) {
                setup.back();
            } else {
                midlet.showMenu();
            }
        } else if (c == nextCmd) {
            setup.next();
        } else if (c == detailsCmd) {
            Help.show(midlet.display(), L.t("Details"), details.toString(), form);
        } else if ((c == startCmd || c == againCmd) && !running) {
            synchronized (this) {
                running = true;
                details.setLength(0);
            }
            form.deleteAll();
            String url = midlet.settings.url.length() > 0 ? midlet.settings.url : L.t("(not set)");
            form.append(L.t("Server"), url);
            busyLine = form.append(null, L.t("Testing..."));
            form.setBusy(busyLine, true);
            commands();
            new Thread(this).start();
        }
    }

    private void line(String text) {
        form.append(null, text);
    }

    /** One checklist row: what was checked and whether it worked; "Testing..." stays below it. */
    private void check(String what, boolean ok) {
        boolean going = busyLine >= 0;
        stopBusy();
        form.append(what, (ok ? L.t("OK") : L.t("failed")));
        if (going) {
            busyLine = form.append(null, L.t("Testing..."));
            form.setBusy(busyLine, true);
        }
    }

    /** Removes the "Testing..." line, if any. */
    private void stopBusy() {
        if (busyLine >= 0) {
            form.delete(busyLine);
            busyLine = -1;
        }
    }

    private void detail(String label, String text) {
        details.append(label).append(": ").append(text).append("\n\n");
    }

    /** Tests at once; on success the wizard goes on (Setup.connected), on failure the checklist stays. */
    void runAuto(Display d) {
        auto = true;
        synchronized (this) {
            running = true;
            details.setLength(0);
        }
        form.deleteAll();
        int big = form.append(null, L.t("Connecting") + "...");
        form.setBig(big);
        form.setBusy(big, true);
        form.append(null, L.t("AIKON is connecting to its server; this takes a few seconds. If the phone asks about "
                + "network access, allow it."));
        commands();
        d.setCurrent(form);
        new Thread(this).start();
    }

    public void run() {
        try {
            test();
        } finally {
            stopBusy();
            synchronized (this) {
                running = false;
            }
            commands();
        }
        if (auto) {
            auto = false;
            // not when the setup was left (Back) while connecting
            if (setup != null && midlet.settings.connectionVerified() && midlet.display().getCurrent() == form) {
                setup.connected();
            }
        }
    }

    private void test() {
        Settings s = midlet.settings;
        String base = s.url;
        if (!Net.isHttps(base)) {
            result(false, L.t("The address must start with https://. Set it in Settings."));
            return;
        }

        // 1. health
        Net.Result h = Net.request(base + "/health", "GET", null, null, midlet.userAgent(), null);
        String why = problem("/health", h);
        if (auto) {
            if (why != null) {
                // the quiet run failed: the plain checklist after all
                form.deleteAll();
                form.append(L.t("Server"), base);
                check(L.t("Reaching the server"), false);
                fail(why);
                return;
            }
        } else {
            check(L.t("Reaching the server"), why == null);
        }
        if (why != null) {
            fail(why);
            return;
        }
        S40Message hm = h.msg;
        Updates.fromHealth(base, hm); // the newest app and the shop, known at once
        detail(L.t("Server"), hm.field("service") + " " + hm.field("version") + " (" + hm.field("environment") + ")"
                + (hm.flag("mock") ? L.t(", TEST MODE (fake replies)") : ""));
        detail(L.t("This phone's TLS connection"), h.tls.length() > 0 ? h.tls : L.t("(no info)"));
        detail(L.t("TLS seen by the server"), hm.field("tls-version") + ", " + hm.field("tls-cipher"));
        if (hm.flag("credits")) {
            detail(L.t("Credits"), L.t("this server works with credit codes"));
        }
        if (auto) {
            verified(base, hm);
            return;
        }

        // 2. echo
        Net.Result e = Net.request(base + "/echo", "POST", null, PROBE, midlet.userAgent(), null);
        why = problem("/echo", e);
        if (why != null) {
            check(L.t("Turkish characters"), false);
            fail(why);
            return;
        }
        boolean same = PROBE.equals(e.msg.text);
        boolean serverMatch = "match".equals(e.msg.field("probe"));
        check(L.t("Turkish characters"), same && serverMatch);
        detail(L.t("UTF-8 upload"), serverMatch ? L.t("the server got it right")
                : L.t("the server got different bytes: ") + e.msg.field("hex"));
        detail(L.t("UTF-8 download"), (same ? L.t("correct: ") : L.t("different: "))
                + e.msg.text);
        detail(L.t("Rendering on this phone"), "ç ğ ı İ ö ş ü  Ç Ğ Ö Ş Ü");
        if (!same || !serverMatch) {
            fail(L.t("Character encoding error."));
            return;
        }

        String err = verified(base, hm);
        detail(L.t("Note"), L.t("A working connection alone does not prove a current security level; check the TLS version and "
                + "cipher above."));
        result(true, L.t("Ready, chat can be used.") + (err != null ? " (" + err + ")" : ""));
    }

    /** Marks the address as verified; the save error or null. */
    private String verified(String base, S40Message hm) {
        Settings s = midlet.settings;
        s.verifiedUrl = base;
        s.credits = hm.flag("credits");
        return s.save();
    }

    /** Why a step failed (also kept in the details), or null if it passed. */
    private String problem(String step, Net.Result r) {
        String why;
        if (!r.ok()) {
            why = Net.explain(r);
        } else if (r.msg == null) {
            why = "HTTP " + r.httpCode + L.t(": not this server's reply (carrier network, wrong address?).");
        } else if (r.httpCode != 200 || !"ok".equals(r.msg.field("status"))) {
            why = "HTTP " + r.httpCode + L.t(", status: ") + r.msg.field("status");
        } else {
            detail(step, L.t("OK (HTTP 200)"));
            return null;
        }
        detail(step, why);
        return why;
    }

    private void result(boolean ok, String text) {
        stopBusy();
        form.append(ok ? L.t("Result") : L.t("Failed"), text);
    }

    private void fail(String why) {
        Settings s = midlet.settings;
        if (s.connectionVerified()) {
            s.verifiedUrl = "";
            s.save();
        }
        result(false, why + L.t(" Chat is locked."));
    }
}

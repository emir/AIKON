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
 */
final class ConnTest implements CommandListener, Runnable {

    /** Must equal echoProbe in server/main.go (a protocol constant: keeps the old name). */
    static final String PROBE = "Claude S40 UTF-8: ç ğ ı İ ö ş ü Ç Ğ Ö Ş Ü";

    private final ClaudeS40MIDlet midlet;
    /** The wizard this test is a step of, or null. */
    private final Setup setup;
    private final TextPage form;
    private final Command startCmd = new Command(L.s("Başlat", "Start"), Command.OK, 1);
    private final Command againCmd = new Command(L.s("Yeniden test et", "Test again"), Command.SCREEN, 2);
    private final Command detailsCmd = new Command(L.s("Ayrıntılar", "Details"), Command.HELP, 8);
    private final Command backCmd = new Command(L.s("Geri", "Back"), Command.BACK, 1);
    private final Command nextCmd = new Command(L.s("İleri", "Next"), Command.OK, 1);
    private boolean running;
    /** Commands shown now (they change with the state). */
    private Command[] shown = new Command[0];
    /** Technical details of the last run, for "Ayrıntılar". */
    private final StringBuffer details = new StringBuffer();

    ConnTest(ClaudeS40MIDlet midlet, Setup setup) {
        this.midlet = midlet;
        this.setup = setup;
        form = new TextPage(setup != null ? Setup.title(2) : L.s("Bağlantı testi", "Connection test"));
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
        String url = midlet.settings.url.length() > 0 ? midlet.settings.url : L.s("(ayarlanmadı)", "(not set)");
        form.append(L.s("Sunucu", "Server"), url);
        if (midlet.settings.connectionVerified()) {
            line(L.s("Bu adres daha önce doğrulandı.", "This address was verified before."));
        } else {
            line(L.s("Başlat'a bas: güvenli bağlantı ve Türkçe karakterler denenir.",
                    "Press Start to check the secure connection and Turkish characters."));
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
            Help.show(midlet.display(), L.s("Ayrıntılar", "Details"), details.toString(), form);
        } else if ((c == startCmd || c == againCmd) && !running) {
            synchronized (this) {
                running = true;
                details.setLength(0);
            }
            form.deleteAll();
            String url = midlet.settings.url.length() > 0 ? midlet.settings.url : L.s("(ayarlanmadı)", "(not set)");
            form.append(L.s("Sunucu", "Server"), url);
            line(L.s("Deneniyor...", "Testing..."));
            commands();
            new Thread(this).start();
        }
    }

    private void line(String text) {
        form.append(null, text);
    }

    /** One checklist row: what was checked and whether it worked. */
    private void check(String what, boolean ok) {
        form.append(what, (ok ? L.s("tamam", "OK") : L.s("olmadı", "failed")));
    }

    private void detail(String label, String text) {
        details.append(label).append(": ").append(text).append("\n\n");
    }

    public void run() {
        try {
            test();
        } finally {
            synchronized (this) {
                running = false;
            }
            commands();
        }
    }

    private void test() {
        Settings s = midlet.settings;
        String base = s.url;
        if (form.size() > 1) {
            form.delete(form.size() - 1); // "Testing..."
        }
        if (!Net.isHttps(base)) {
            result(false, L.s("Adres https:// ile başlamalı. Ayarlar'dan girin.",
                    "The address must start with https://. Set it in Settings."));
            return;
        }

        // 1. health
        Net.Result h = Net.request(base + "/health", "GET", null, null, midlet.userAgent(), null);
        String why = problem("/health", h);
        check(L.s("Sunucuya ulaşma", "Reaching the server"), why == null);
        if (why != null) {
            fail(why);
            return;
        }
        S40Message hm = h.msg;
        detail(L.s("Sunucu", "Server"), hm.field("service") + " " + hm.field("version") + " (" + hm.field("environment") + ")"
                + (hm.flag("mock") ? L.s(", TEST MODU (sahte yanıtlar)", ", TEST MODE (fake replies)") : ""));
        detail(L.s("Telefonun TLS bağlantısı", "This phone's TLS connection"), h.tls.length() > 0 ? h.tls : L.s("(bilgi yok)", "(no info)"));
        detail(L.s("Sunucunun gördüğü TLS", "TLS seen by the server"), hm.field("tls-version") + ", " + hm.field("tls-cipher"));
        if (hm.flag("credits")) {
            detail(L.s("Kredi", "Credits"), L.s("bu sunucu kredi kodlarıyla çalışır", "this server works with credit codes"));
        }

        // 2. echo
        Net.Result e = Net.request(base + "/echo", "POST", null, PROBE, midlet.userAgent(), null);
        why = problem("/echo", e);
        if (why != null) {
            check(L.s("Türkçe karakterler", "Turkish characters"), false);
            fail(why);
            return;
        }
        boolean same = PROBE.equals(e.msg.text);
        boolean serverMatch = "match".equals(e.msg.field("probe"));
        check(L.s("Türkçe karakterler", "Turkish characters"), same && serverMatch);
        detail(L.s("UTF-8 gönderim", "UTF-8 upload"), serverMatch ? L.s("sunucu doğru aldı", "the server got it right")
                : L.s("sunucu farklı bayt aldı: ", "the server got different bytes: ") + e.msg.field("hex"));
        detail(L.s("UTF-8 alım", "UTF-8 download"), (same ? L.s("doğru: ", "correct: ") : L.s("farklı: ", "different: "))
                + e.msg.text);
        detail(L.s("Telefonda görünüm", "Rendering on this phone"), "ç ğ ı İ ö ş ü  Ç Ğ Ö Ş Ü");
        if (!same || !serverMatch) {
            fail(L.s("Karakter kodlaması hatası.", "Character encoding error."));
            return;
        }

        s.verifiedUrl = base;
        s.credits = hm.flag("credits");
        String err = s.save();
        detail(L.s("Not", "Note"), L.s("Bağlantının kurulması tek başına güncel güvenlik düzeyinin kanıtı değildir; "
                + "yukarıdaki TLS sürümüne ve şifreye bakın.",
                "A working connection alone does not prove a current security level; check the TLS version and "
                + "cipher above."));
        result(true, L.s("Hazır, sohbet kullanılabilir.", "Ready, chat can be used.") + (err != null ? " (" + err + ")" : ""));
    }

    /** Why a step failed (also kept in the details), or null if it passed. */
    private String problem(String step, Net.Result r) {
        String why;
        if (!r.ok()) {
            why = Net.explain(r);
        } else if (r.msg == null) {
            why = "HTTP " + r.httpCode + L.s(": yanıt bu sunucudan değil (operatör ağı, yanlış adres?).",
                    ": not this server's reply (carrier network, wrong address?).");
        } else if (r.httpCode != 200 || !"ok".equals(r.msg.field("status"))) {
            why = "HTTP " + r.httpCode + L.s(", durum: ", ", status: ") + r.msg.field("status");
        } else {
            detail(step, L.s("tamam (HTTP 200)", "OK (HTTP 200)"));
            return null;
        }
        detail(step, why);
        return why;
    }

    private void result(boolean ok, String text) {
        form.append(ok ? L.s("Sonuç", "Result") : L.s("Olmadı", "Failed"), text);
    }

    private void fail(String why) {
        Settings s = midlet.settings;
        if (s.connectionVerified()) {
            s.verifiedUrl = "";
            s.save();
        }
        result(false, why + L.s(" Sohbet kilitli.", " Chat is locked."));
    }
}

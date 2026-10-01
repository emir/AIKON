package io.github.emir.claudes40;

import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.CommandListener;
import javax.microedition.lcdui.Display;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.Form;
import javax.microedition.lcdui.StringItem;

/**
 * Connection test: GET /health, then POST /echo with a fixed Turkish probe.
 * No access code and no chat text is sent. Reports what the phone's TLS
 * stack says about the connection; a successful connection alone is not
 * proof that its security level is current.
 *
 * Only when both steps pass is the URL marked as verified; chat is locked
 * until then. As step 3 of the setup wizard (Setup) it gets "İleri" once
 * the address is verified, and "Geri" goes to the previous step.
 */
final class ConnTest implements CommandListener, Runnable {

    /** Must equal ECHO_PROBE in services/claude-s40-gateway/src/constants.ts. */
    static final String PROBE = "Claude S40 UTF-8: ç ğ ı İ ö ş ü Ç Ğ Ö Ş Ü";

    private final ClaudeS40MIDlet midlet;
    /** The wizard this test is a step of, or null. */
    private final Setup setup;
    private final Form form;
    private final Command startCmd = new Command(L.s("Başlat", "Start"), Command.SCREEN, 1);
    private final Command backCmd = new Command(L.s("Geri", "Back"), Command.BACK, 1);
    private final Command nextCmd = new Command(L.s("İleri", "Next"), Command.OK, 1);
    private boolean running;
    private boolean nextShown;

    ConnTest(ClaudeS40MIDlet midlet, Setup setup) {
        this.midlet = midlet;
        this.setup = setup;
        form = new Form(setup != null ? Setup.title(2) : L.s("Bağlantı testi", "Connection test"));
        form.addCommand(startCmd);
        form.addCommand(backCmd);
        form.setCommandListener(this);
        intro();
        showNext(midlet.settings.connectionVerified());
    }

    /** Wizard only: "İleri" while the address is verified. */
    private synchronized void showNext(boolean want) {
        if (setup == null || want == nextShown) {
            return;
        }
        if (want) {
            form.addCommand(nextCmd);
        } else {
            form.removeCommand(nextCmd);
        }
        nextShown = want;
    }

    private void intro() {
        form.deleteAll();
        line(null, L.s("Sunucu: ", "Server: ") + (midlet.settings.url.length() > 0 ? midlet.settings.url : L.s("(ayarlanmadı)", "(not set)")));
        line(null, L.s("İki adım: /health ve /echo (Türkçe UTF-8). Erişim kodu ve sohbet metni gönderilmez. "
                + "Telefon ağ izni isteyebilir.",
                "Two steps: /health and /echo (Turkish UTF-8 round trip). No access code and no chat text are sent. "
                + "The phone may ask for network access."));
        line(L.s("Telefonda görünüm", "Rendering on this phone"), "ç ğ ı İ ö ş ü  Ç Ğ Ö Ş Ü");
    }

    void show(Display d) {
        if (!running) {
            intro();
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
        } else if (c == startCmd && !running) {
            running = true;
            intro();
            line(null, L.s("Çalışıyor...", "Running..."));
            new Thread(this).start();
        }
    }

    private void line(String label, String text) {
        form.append(new StringItem(label, text + "\n"));
    }

    public void run() {
        try {
            test();
        } finally {
            running = false;
        }
    }

    private void test() {
        Settings s = midlet.settings;
        String base = s.url;
        if (!Net.isHttps(base)) {
            line(L.s("SONUÇ", "RESULT"), L.s("Sunucu adresi https:// ile başlamalı. Ayarlar'dan girin.", "The server address must start with https://. Set it in Settings."));
            return;
        }

        // 1. health
        Net.Result h = Net.request(base + "/health", "GET", null, null, midlet.userAgent(), null);
        if (!report("1. /health", h)) {
            fail();
            return;
        }
        S40Message hm = h.msg;
        line(L.s("Sunucu", "Server"), hm.field("service") + " " + hm.field("version") + " (" + hm.field("environment") + ")"
                + (hm.flag("mock") ? L.s(", sunucu TEST MODU (sahte yanıtlar)", ", server in TEST MODE (fake replies)") : ""));
        line(L.s("Telefonun TLS bağlantısı", "This phone's TLS connection"), h.tls.length() > 0 ? h.tls : L.s("(bilgi yok)", "(no info)"));
        line(L.s("Sunucunun gördüğü TLS", "TLS seen by the server"),
                hm.field("tls-version") + ", " + hm.field("tls-cipher"));

        // 2. echo
        Net.Result e = Net.request(base + "/echo", "POST", null, PROBE, midlet.userAgent(), null);
        if (!report("2. /echo", e)) {
            fail();
            return;
        }
        boolean same = PROBE.equals(e.msg.text);
        boolean serverMatch = "match".equals(e.msg.field("probe"));
        line(L.s("UTF-8 gönderim", "UTF-8 upload"), serverMatch ? L.s("Sunucu Türkçe metni doğru aldı.", "The server received the Turkish text correctly.")
                : L.s("Sunucu farklı bayt aldı: ", "The server received different bytes: ") + e.msg.field("hex"));
        line(L.s("UTF-8 alım", "UTF-8 download"), (same ? L.s("Telefon Türkçe metni doğru aldı: ", "The phone received the Turkish text correctly: ")
                : L.s("Farklı geldi: ", "Received something else: ")) + e.msg.text);
        if (!same || !serverMatch) {
            line(L.s("SONUÇ", "RESULT"), L.s("Karakter kodlaması hatası. Sohbet kilitli kaldı.", "Character encoding error. Chat stays locked."));
            showNext(false);
            return;
        }

        s.verifiedUrl = base;
        String err = s.save();
        showNext(true);
        line(L.s("SONUÇ", "RESULT"), L.s("Bağlantı doğrulandı; sohbet kullanılabilir.", "Connection verified; chat is ready.")
                + (err != null ? " (" + err + ")" : "")
                + L.s(" Not: bağlantının kurulması tek başına güncel güvenlik düzeyinin kanıtı değildir; "
                + "yukarıdaki TLS sürümü ve şifreyi kontrol edin.",
                " Note: a working connection alone does not prove a current security level; "
                + "check the TLS version and cipher above."));
    }

    private boolean report(String step, Net.Result r) {
        if (!r.ok()) {
            line(step, L.s("HATA: ", "ERROR: ") + Net.explain(r));
            return false;
        }
        if (r.msg == null) {
            line(step, "HTTP " + r.httpCode + L.s(", yanıt Claude S40 sunucusundan değil (operatör ağı, yanlış adres?).", ", not a Claude S40 server reply (carrier network, wrong address?)."));
            return false;
        }
        String st = r.msg.field("status");
        if (r.httpCode != 200 || !"ok".equals(st)) {
            line(step, "HTTP " + r.httpCode + L.s(", durum: ", ", status: ") + st);
            return false;
        }
        line(step, L.s("Tamam (HTTP 200)", "OK (HTTP 200)"));
        return true;
    }

    private void fail() {
        Settings s = midlet.settings;
        if (s.connectionVerified()) {
            s.verifiedUrl = "";
            s.save();
        }
        showNext(false);
        line(L.s("SONUÇ", "RESULT"), L.s("Bağlantı doğrulanamadı. Sohbet kilitli.", "Connection not verified. Chat is locked."));
    }
}

package io.github.emir.claudes40;

import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.CommandListener;
import javax.microedition.lcdui.Display;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.Font;
import javax.microedition.lcdui.Form;
import javax.microedition.lcdui.StringItem;

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
    private final Form form;
    private final StringItem codeItem = new StringItem(L.s("Eşleştirme kodu", "Pairing code"), "-");
    private final StringItem statusItem = new StringItem(L.s("Durum", "Status"), "");
    private final Command cancelCmd = new Command(L.s("İptal", "Cancel"), Command.BACK, 1);
    private final Command backCmd = new Command(L.s("Geri", "Back"), Command.BACK, 1);
    private final Command finishCmd = new Command(L.s("Bitir", "Finish"), Command.OK, 1);
    private volatile boolean cancelled;

    Pairing(ClaudeS40MIDlet midlet, Setup setup) {
        this.midlet = midlet;
        this.setup = setup;
        form = new Form(setup != null ? Setup.title(Setup.STEPS - 1) : L.s("Cihazı eşleştir", "Pair this phone"));
        codeItem.setFont(Font.getFont(Font.FACE_SYSTEM, Font.STYLE_BOLD, Font.SIZE_LARGE));
        form.append(codeItem);
        form.append(statusItem);
        form.append(new StringItem(null,
                L.s("Sunucunun sahibi bu kodu onaylar:\nadmin.sh SUNUCU pair <kod>\nOnaydan sonra telefon erişim kodunu kendisi alır.",
                        "The server's owner approves this code:\nadmin.sh SERVER pair <code>\nAfter approval the phone fetches its access code by itself.")));
        form.addCommand(cancelCmd);
        form.setCommandListener(this);
    }

    void start(Display d) {
        status(L.s("Kod isteniyor...", "Requesting a code..."));
        d.setCurrent(form);
        new Thread(this).start();
    }

    public void commandAction(Command c, Displayable d) {
        if (c == cancelCmd || c == backCmd) {
            cancelled = true;
            if (setup != null) {
                setup.show();
            } else {
                midlet.showMenu();
            }
        } else if (c == finishCmd) {
            setup.finish();
        }
    }

    private void status(String s) {
        statusItem.setText(s);
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
            finish(L.s("Başlatılamadı. ", "Could not start. ") + Net.explain(r));
            return;
        }
        if (r.msg == null || !"ok".equals(r.msg.field("status"))) {
            String st = r.msg == null ? "HTTP " + r.httpCode : r.msg.field("status");
            finish("pair_busy".equals(st) ? L.s("Bekleyen eşleştirme çok fazla; birkaç dakika sonra deneyin.",
                    "Too many pending pairings; try again in a few minutes.")
                    : L.s("Başlatılamadı (", "Could not start (") + st + ").");
            return;
        }
        String pair = r.msg.field("pair");
        String code = r.msg.field("code");
        codeItem.setText(code.length() == 6 ? code.substring(0, 3) + " " + code.substring(3) : code);
        status(L.s("Onay bekleniyor...", "Waiting for approval..."));

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
                    finish(L.s("Bağlantı sorunu, eşleştirme durdu. ", "Connection problem, pairing stopped. ") + (c.ok() ? "" : Net.explain(c)));
                    return;
                }
                status(L.s("Bağlantı sorunu, tekrar deneniyor (", "Connection problem, trying again (") + netErrors + "/" + MAX_NET_ERRORS + ")...");
                continue;
            }
            netErrors = 0;
            String st = c.msg.field("status");
            if ("ok".equals(st)) {
                String token = c.msg.field("token");
                s.token = token;
                String err = s.save();
                codeItem.setText("OK");
                finish(err != null ? err
                        : L.s("Eşleştirildi (", "Paired (") + c.msg.field("device")
                                + L.s("). Erişim kodu kaydedildi; sohbet kullanılabilir.", "). Access code saved; chat is ready."),
                        err == null);
                return;
            }
            if ("pending".equals(st)) {
                status(L.s("Onay bekleniyor...", "Waiting for approval..."));
                continue;
            }
            finish(L.s("Eşleştirme süresi doldu. Yeniden başlatın.", "Pairing expired. Start again."));
            return;
        }
        if (!cancelled) {
            finish(L.s("Eşleştirme süresi doldu. Yeniden başlatın.", "Pairing expired. Start again."));
        }
    }
}

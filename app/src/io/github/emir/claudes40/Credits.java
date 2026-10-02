package io.github.emir.claudes40;

import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.CommandListener;
import javax.microedition.lcdui.Display;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.Form;
import javax.microedition.lcdui.StringItem;
import javax.microedition.lcdui.TextField;

/**
 * Credits, on a server that sells them (/health says "credits: 1"; server
 * extension, see docs/ARCHITECTURE.md):
 *
 *   PAIR     a 16-digit code pairs this phone with a new account, without
 *            the owner's approval (POST /v1/pair/voucher)
 *   TOPUP    a code adds credits to this phone's account (POST /v1/redeem)
 *   BALANCE  the balance and the newest charges (POST /v1/balance)
 *
 * The last digit of a code is a check digit (Luhn), so a mistyped code is
 * caught before anything is sent. Networking on a worker thread, one
 * request at a time; nothing is repeated by itself.
 */
final class Credits implements CommandListener, Runnable {

    static final int PAIR = 0;
    static final int TOPUP = 1;
    static final int BALANCE = 2;

    private final ClaudeS40MIDlet midlet;
    /** The wizard this pairing is a step of, or null. */
    private final Setup setup;
    private final Displayable back;
    private int mode;
    /** The code form (a TextField) or the balance page. */
    private Displayable form;
    private TextPage page;
    private TextField codeField;
    private StringItem statusItem;
    private String code = "";
    private boolean busy;
    private final Command sendCmd = new Command(L.s("Gönder", "Send"), Command.OK, 1);
    private final Command backCmd = new Command(L.s("Geri", "Back"), Command.BACK, 1);
    private final Command finishCmd = new Command(L.s("Bitir", "Finish"), Command.OK, 1);
    private final Command topupCmd = new Command(L.s("Kredi yükle", "Add credits"), Command.SCREEN, 2);
    private final Command refreshCmd = new Command(L.s("Yenile", "Refresh"), Command.SCREEN, 3);
    private final Command helpCmd = Help.command();

    Credits(ClaudeS40MIDlet midlet, Setup setup, Displayable back) {
        this.midlet = midlet;
        this.setup = setup;
        this.back = back;
    }

    /** Pairing with a code (setup step or Settings > Pair this phone). */
    void showPair() {
        mode = PAIR;
        codeForm(setup != null ? Setup.title(Setup.STEPS - 1) : L.s("Kredi kodu", "Credit code"),
                L.s("16 haneli kredi kodunu gir.", "Type the 16-digit credit code."));
    }

    void showTopup() {
        mode = TOPUP;
        codeForm(L.s("Kredi yükle", "Add credits"), L.s("Yeni kredi kodunu gir.", "Type the new credit code."));
    }

    void showBalance() {
        mode = BALANCE;
        page = new TextPage(L.s("Kredi", "Credits"));
        form = page;
        page.append(L.s("Bakiye", "Balance"), L.s("Yükleniyor...", "Loading..."));
        page.setBig(0);
        String shop = midlet.shopLine();
        page.append(null, (shop.length() > 0 ? shop + "\n" : "")
                + L.s("Kodu Seçenekler > 'Kredi yükle' ile gir.", "Enter the code with Options > 'Add credits'."));
        form.addCommand(backCmd);
        form.addCommand(topupCmd);
        form.addCommand(refreshCmd);
        form.addCommand(helpCmd);
        form.setCommandListener(this);
        midlet.display().setCurrent(form);
        start();
    }

    private void codeForm(String title, String line) {
        Form f = new Form(title);
        form = f;
        codeField = new TextField(L.s("Kredi kodu", "Credit code"), "", 16, TextField.NUMERIC);
        statusItem = new StringItem(null, line);
        f.append(codeField);
        f.append(statusItem);
        form.addCommand(sendCmd);
        form.addCommand(backCmd);
        form.addCommand(helpCmd);
        form.setCommandListener(this);
        midlet.display().setCurrent(form);
    }

    public void commandAction(Command c, Displayable d) {
        if (c == helpCmd) {
            Help.show(midlet.display(), form.getTitle(), helpText(), form);
        } else if (c == backCmd) {
            if (setup != null) {
                setup.show();
            } else if (mode == TOPUP) {
                new Credits(midlet, null, back).showBalance();
            } else {
                midlet.display().setCurrent(back);
            }
        } else if (c == finishCmd) {
            setup.finish();
        } else if (c == topupCmd) {
            new Credits(midlet, null, back).showTopup();
        } else if (c == refreshCmd) {
            start();
        } else if (c == sendCmd) {
            String v = codeField.getString().trim();
            if (!valid(v)) {
                midlet.info(L.s("Kod 16 rakam olmalı. Bir rakam yanlış yazılmış olabilir; kodu kontrol et.",
                        "The code has 16 digits. A digit may be mistyped; check the code."), form);
                return;
            }
            code = v;
            status(L.s("Gönderiliyor...", "Sending..."));
            start();
        }
    }

    private synchronized void start() {
        if (busy) {
            return;
        }
        busy = true;
        new Thread(this).start();
    }

    private String helpText() {
        if (mode == BALANCE) {
            return L.s("Kredi, mesajın uzunluğuna ve seçilen modele göre harcanır. Başarısız ya da sonucu belirsiz "
                    + "kalan isteklerden kredi düşülmez.\n\nYeni bir kodla 'Kredi yükle'.",
                    "Credits are used according to the length of the message and the chosen model. Failed requests "
                    + "and requests with an unknown result cost nothing.\n\nAdd a new code with 'Add credits'.");
        }
        return L.s("Kodu, kredi satan sunucunun sitesinden alırsın. 16 rakamdır; son rakam bir kontrol rakamı "
                + "olduğu için yanlış yazılan kod gönderilmeden fark edilir.\n\nKod bir kez kullanılır."
                + (mode == PAIR ? " Bu telefon kodla eşleşir; sunucu sahibinin onayı gerekmez." : ""),
                "You get the code from the shop of the server that sells credits. It has 16 digits; the last one "
                + "is a check digit, so a mistyped code is caught before it is sent.\n\nA code works once."
                + (mode == PAIR ? " This phone is paired with the code; no approval by the server's owner." : ""));
    }

    /** 16 digits with a valid check digit (Luhn). */
    static boolean valid(String s) {
        if (s.length() != 16) {
            return false;
        }
        int sum = 0;
        for (int i = 0; i < 16; i++) {
            int n = s.charAt(15 - i) - '0';
            if (n < 0 || n > 9) {
                return false;
            }
            if (i % 2 == 1) {
                n *= 2;
                if (n > 9) {
                    n -= 9;
                }
            }
            sum += n;
        }
        return sum % 10 == 0;
    }

    private void status(String s) {
        if (page != null) {
            page.setText(0, s);
        } else {
            statusItem.setText(s);
        }
    }

    public void run() {
        try {
            if (mode == BALANCE) {
                fetchBalance();
            } else {
                sendCode();
            }
        } finally {
            synchronized (this) {
                busy = false;
            }
        }
    }

    private void sendCode() {
        Settings s = midlet.settings;
        String[] k = { "voucher" };
        String[] v = { code };
        Net.Result r = mode == PAIR
                ? Net.request(s.url + "/v1/pair/voucher", "POST", null, S40Message.format(k, v, ""), midlet.userAgent(), null)
                : Net.request(s.url + "/v1/redeem", "POST", s.token, S40Message.format(k, v, ""), midlet.userAgent(), null);
        if (!r.ok() || r.msg == null) {
            status(L.s("Gönderilemedi. ", "Could not send. ") + (r.ok() ? "HTTP " + r.httpCode : Net.explain(r)));
            return;
        }
        String st = r.msg.field("status");
        String bal = r.msg.field("balance");
        if ("ok".equals(st)) {
            midlet.session().setBalance(bal);
            if (mode == PAIR) {
                s.token = r.msg.field("token");
                s.credits = true;
                String err = s.save();
                if (err != null) {
                    status(err);
                    return;
                }
            }
            codeField.setString("");
            status((mode == PAIR ? L.s("Eşleştirildi. ", "Paired. ") : L.s("Yüklendi. ", "Added. "))
                    + L.s("Bakiye: ", "Balance: ") + bal + L.s(" kredi", " credits"));
            form.removeCommand(sendCmd);
            if (mode == PAIR && setup != null) {
                form.addCommand(finishCmd);
            }
        } else if ("bad_voucher".equals(st)) {
            status(L.s("Kod geçersiz ya da iptal edilmiş.", "The code is not valid or was cancelled."));
        } else if ("used".equals(st)) {
            status(L.s("Bu kod daha önce kullanılmış.", "This code was already used."));
        } else if ("slow_down".equals(st)) {
            status(L.s("Çok fazla hatalı deneme. 15 dakika sonra yeniden dene.",
                    "Too many wrong codes. Try again in 15 minutes."));
        } else if ("unauthorized".equals(st)) {
            status(L.s("Eşleştirme geçersiz. Ayarlar > Seçenekler > 'Cihazı eşleştir'.",
                    "The pairing is not valid. Settings > Options > 'Pair this phone'."));
        } else if ("not_found".equals(st)) {
            status(L.s("Bu sunucu kredi satmıyor.", "This server does not sell credits."));
        } else {
            status(L.s("Sunucu: ", "Server: ") + st);
        }
    }

    private void fetchBalance() {
        Settings s = midlet.settings;
        Net.Result r = Net.request(s.url + "/v1/balance", "POST", s.token,
                S40Message.format(new String[0], new String[0], ""), midlet.userAgent(), null);
        if (!r.ok() || r.msg == null || !"ok".equals(r.msg.field("status"))) {
            status(r.ok() ? L.s("Sunucu: ", "Server: ") + (r.msg == null ? "HTTP " + r.httpCode : r.msg.field("status"))
                    : Net.explain(r));
            return;
        }
        if (!r.msg.flag("credits")) {
            status(L.s("Bu telefon kredisiz çalışıyor.", "This phone runs without credits."));
            return;
        }
        String bal = r.msg.field("balance");
        midlet.session().setBalance(bal);
        status(bal + L.s(" kredi", " credits"));
        // keep the balance item, list the newest charges below it
        while (page.size() > 2) { // the balance and how to buy stay
            page.delete(2);
        }
        String t = r.msg.text;
        int start = 0;
        while (start < t.length()) {
            int end = t.indexOf('\n', start);
            if (end < 0) {
                end = t.length();
            }
            String line = t.substring(start, end);
            start = end + 1;
            String[] f = split(line);
            if (f == null) {
                continue;
            }
            long ts = 0;
            try {
                ts = Long.parseLong(f[0]);
            } catch (NumberFormatException e) {
                // shown without a date
            }
            String what = kind(f[1]) + (f[3].length() > 0 ? " · " + Models.name(f[3]) : "");
            page.append(Text.local(ts, true), what + ": " + f[2]);
        }
    }

    /** "ts TAB kind TAB credits TAB model" -> 4 fields, or null. */
    private static String[] split(String line) {
        String[] out = new String[4];
        int from = 0;
        for (int i = 0; i < 3; i++) {
            int tab = line.indexOf('\t', from);
            if (tab < 0) {
                return null;
            }
            out[i] = line.substring(from, tab);
            from = tab + 1;
        }
        out[3] = line.substring(from);
        return out;
    }

    private static String kind(String k) {
        if ("chat".equals(k)) {
            return L.s("Sohbet", "Chat");
        }
        if ("transcribe".equals(k)) {
            return L.s("Sesle yazma", "Voice");
        }
        if ("voucher".equals(k)) {
            return L.s("Kod", "Code");
        }
        if ("adjust".equals(k)) {
            return L.s("Düzeltme", "Adjustment");
        }
        return k;
    }

    private static boolean fetching;
    private static long triedAt;

    /**
     * For the home screen: asks for the balance (free, /v1/balance) on a
     * worker thread while it is unknown, at most once a minute.
     */
    static void maybeFetchBalance(final ClaudeS40MIDlet midlet) {
        Settings s = midlet.settings;
        if (!s.credits || s.testMode || s.token.length() < 16 || !s.connectionVerified()
                || midlet.session().balance().length() > 0) {
            return;
        }
        synchronized (Credits.class) {
            long now = System.currentTimeMillis();
            if (fetching || (now - triedAt < 60000 && now >= triedAt)) {
                return;
            }
            fetching = true;
            triedAt = now;
        }
        new Thread(new Runnable() {
            public void run() {
                try {
                    Settings s = midlet.settings;
                    Net.Result r = Net.request(s.url + "/v1/balance", "POST", s.token,
                            S40Message.format(new String[0], new String[0], ""), midlet.userAgent(), null);
                    if (r.ok() && r.msg != null && "ok".equals(r.msg.field("status")) && r.msg.flag("credits")) {
                        midlet.session().setBalance(r.msg.field("balance"));
                        midlet.balanceChanged();
                    }
                } finally {
                    synchronized (Credits.class) {
                        fetching = false;
                    }
                }
            }
        }).start();
    }

    /** Opens the right pairing for this server: a credit code or the owner's approval. */
    static void pair(ClaudeS40MIDlet midlet, Setup setup, Display display, Displayable back) {
        if (midlet.settings.credits) {
            new Credits(midlet, setup, back).showPair();
        } else {
            new Pairing(midlet, setup).start(display);
        }
    }
}

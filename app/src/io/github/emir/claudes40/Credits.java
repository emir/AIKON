package io.github.emir.claudes40;

import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.CommandListener;
import javax.microedition.lcdui.Display;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.TextBox;
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
 * caught before anything is sent. The code is typed straight on the page
 * with the number keys (the right softkey deletes while there are digits;
 * the phone's number box stays in Options) and sent once all 16 are there.
 * The free trial, where the server has one, is on the centre key; the shop
 * opens as a QR code for a smartphone (QrPage). Networking on a worker
 * thread, one request at a time; nothing is repeated by itself.
 */
final class Credits implements CommandListener, Runnable, TextPage.Digits {

    static final int PAIR = 0;
    static final int TOPUP = 1;
    static final int BALANCE = 2;
    /** Pairing with the server's free trial (no code). */
    static final int TRIAL = 3;

    private final ClaudeS40MIDlet midlet;
    /** The wizard this pairing is a step of, or null. */
    private final Setup setup;
    private final Displayable back;
    private int mode;
    /** The code page or the balance page (the same TextPage). */
    private Displayable form;
    private TextPage page;
    /** The status item of the page: under the code, or the balance itself. */
    private int statusItem;
    /** The phone's own number box for typing the code. */
    private TextBox codeBox;
    private String code = "";
    private boolean busy;
    /** The centre key with no trial, else in Options. */
    private Command enterCmd;
    private final Command okCmd = new Command(L.t("OK"), Command.OK, 1);
    /** The centre key where the server has a free trial, else null. */
    private Command trialCmd;
    private final Command backCmd = new Command(L.t("Back"), Command.BACK, 1);
    /** In place of Back while digits are typed: deletes the last one. */
    private final Command deleteCmd = new Command(L.t("Delete"), Command.BACK, 1);
    private final Command buyCmd = new Command(L.t("Buy a code"), Command.SCREEN, 3);
    private final Command topupCmd = new Command(L.t("Add credits"), Command.SCREEN, 2);
    private final Command refreshCmd = new Command(L.t("Refresh"), Command.SCREEN, 3);
    private final Command helpCmd = Help.command();

    Credits(ClaudeS40MIDlet midlet, Setup setup, Displayable back) {
        this.midlet = midlet;
        this.setup = setup;
        this.back = back;
    }

    /** Pairing with a code (setup step or Settings > Pair this phone). */
    void showPair() {
        mode = PAIR;
        boolean trial = Updates.trial(midlet.settings.url);
        codeForm(setup != null ? Setup.title(Setup.STEPS) : L.t("Credit code"), trial
                ? L.t("No code? Centre key: try for free, a few messages with one model.\n\nHave a code? Type its 16 "
                        + "digits with the number keys.")
                : L.t("Type the 16-digit credit code with the number keys."), trial);
    }

    void showTopup() {
        mode = TOPUP;
        codeForm(L.t("Add credits"), L.t("Type the new credit code with the number keys."), false);
    }

    /** The shop as a QR code can be shown (the server names a shop whose address fits one). */
    private boolean canBuy() {
        String url = Updates.shopUrl(midlet.settings.url);
        return url.length() > 0 && Qr.encode(url) != null;
    }

    /** How to get a code, under the code page's text. */
    private String buyLine() {
        if (canBuy()) {
            return "\n\n" + L.t("No code yet? Options > Buy a code: a QR code to scan with a smartphone.");
        }
        String shop = midlet.shopLine();
        return shop.length() > 0 ? "\n\n" + shop : "";
    }

    void showBalance() {
        mode = BALANCE;
        page = new TextPage(L.t("Credits"));
        form = page;
        statusItem = page.append(L.t("Balance"), "");
        page.setBig(statusItem);
        waiting(L.t("Loading..."));
        String shop = midlet.shopLine();
        page.append(null, (shop.length() > 0 ? shop + "\n" : "")
                + L.t("Enter the code with Options > 'Add credits'."));
        form.addCommand(backCmd);
        form.addCommand(topupCmd);
        if (canBuy()) {
            form.addCommand(buyCmd);
        }
        form.addCommand(refreshCmd);
        form.addCommand(helpCmd);
        form.setCommandListener(this);
        midlet.display().setCurrent(form);
        start();
    }

    /**
     * The code page: the code large in four groups (blanks until typed), a
     * status line, how to get a code. Digits are typed on the page; the
     * phone's number box (Type the code) does the same.
     */
    private void codeForm(String title, String line, boolean trial) {
        page = new TextPage(title);
        form = page;
        int c = page.append(L.t("Credit code"), grouped(""));
        page.setBig(c);
        // right under the code, where it is seen: sending, the result, a mistyped digit
        statusItem = page.append(null, "");
        page.append(null, line + buyLine());
        if (trial) {
            trialCmd = new Command(L.t("Try for free"), Command.OK, 1);
            form.addCommand(trialCmd);
        }
        enterCmd = new Command(L.t("Type the code"), trial ? Command.SCREEN : Command.OK, trial ? 2 : 1);
        form.addCommand(enterCmd);
        if (canBuy()) {
            form.addCommand(buyCmd);
        }
        form.addCommand(backCmd);
        form.addCommand(helpCmd);
        form.setCommandListener(this);
        page.setDigits(this);
        midlet.display().setCurrent(form);
    }

    /** A number key on the code page. */
    public void digit(char ch) {
        synchronized (this) {
            if (busy) {
                return;
            }
        }
        if (code.length() >= 16) {
            code = ""; // a full code that did not work: typing starts a new one
        }
        code += ch;
        typed(false);
    }

    /** Clear / Backspace, and the Delete softkey: the last digit. */
    public void clear() {
        synchronized (this) {
            if (busy || code.length() == 0) {
                return;
            }
        }
        code = code.substring(0, code.length() - 1);
        typed(false);
    }

    /** The code changed: shown, Delete or Back on the right softkey, sent once complete. */
    private void typed(boolean fromBox) {
        page.setText(0, grouped(code));
        if (code.length() > 0) {
            form.removeCommand(backCmd);
            form.addCommand(deleteCmd);
        } else {
            form.removeCommand(deleteCmd);
            form.addCommand(backCmd);
        }
        if (code.length() < 16) {
            status(fromBox && code.length() > 0 ? L.t("The code has 16 digits. Centre key to finish it.") : "");
            return;
        }
        if (!valid(code)) {
            status(L.t("A digit may be mistyped; check the code."));
            return;
        }
        if (mode == TRIAL) {
            mode = PAIR; // a code typed after a trial that did not start
        }
        waiting(L.t("Sending..."));
        start();
    }

    /** "1234 5678 9012 3456", with "_" for digits not typed yet. */
    private static String grouped(String digits) {
        StringBuffer b = new StringBuffer();
        for (int i = 0; i < 16; i++) {
            if (i > 0 && i % 4 == 0) {
                b.append(' ');
            }
            b.append(i < digits.length() ? digits.charAt(i) : '_');
        }
        return b.toString();
    }

    private void typeCode() {
        codeBox = new TextBox(L.t("Credit code"), code, 16, TextField.NUMERIC);
        codeBox.addCommand(okCmd);
        codeBox.addCommand(backCmd);
        codeBox.setCommandListener(this);
        midlet.display().setCurrent(codeBox);
    }

    public void commandAction(Command c, Displayable d) {
        if (d == codeBox && d != null) {
            String v = codeBox.getString().trim();
            midlet.display().setCurrent(form);
            if (c != okCmd) {
                return;
            }
            code = v;
            typed(true);
            return;
        }
        if (c == okCmd) {
            if (mode == TOPUP) {
                new Credits(midlet, null, back).showBalance();
            } else {
                midlet.display().setCurrent(back);
            }
        } else if (c == helpCmd) {
            Help.show(midlet.display(), form.getTitle(), helpText(), form);
        } else if (c == deleteCmd) {
            clear();
        } else if (c == buyCmd) {
            QrPage.show(midlet.display(), form, L.t("Buy a code"), Updates.shopUrl(midlet.settings.url));
        } else if (c == backCmd) {
            if (setup != null) {
                setup.pairingBack();
            } else if (mode == TOPUP) {
                new Credits(midlet, null, back).showBalance();
            } else {
                midlet.display().setCurrent(back);
            }
        } else if (c == topupCmd) {
            new Credits(midlet, null, back).showTopup();
        } else if (c == refreshCmd) {
            waiting(L.t("Loading..."));
            start();
        } else if (c == enterCmd) {
            typeCode();
        } else if (c == trialCmd) {
            mode = TRIAL;
            waiting(L.t("Starting the trial..."));
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
            return L.t("Credits are used according to the length of the message and the chosen model. Failed requests "
                    + "and requests with an unknown result cost nothing.\n\nAdd a new code with 'Add credits'.");
        }
        return L.t("You get the code from the shop of the server that sells credits. It has 16 digits; the last one "
                + "is a check digit, so a mistyped code is caught before it is sent.\n\nA code works once.")
                + (mode == PAIR ? L.t(" This phone is paired with the code; no approval by the server's owner.") : "");
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

    /** A status with the spinner: a request under way, until the next status(). */
    private void waiting(String text) {
        page.waiting(statusItem, text);
    }

    private void status(String s) {
        page.done(statusItem, s);
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
        Net.Result r = mode == TRIAL
                ? Net.request(s.url + "/v1/pair/trial", "POST", null, S40Message.format(new String[0], new String[0], ""),
                        midlet.userAgent(), null)
                : mode == PAIR
                ? Net.request(s.url + "/v1/pair/voucher", "POST", null, S40Message.format(k, v, ""), midlet.userAgent(), null)
                : Net.request(s.url + "/v1/redeem", "POST", s.token, S40Message.format(k, v, ""), midlet.userAgent(), null);
        if (!r.ok() || r.msg == null) {
            status(L.t("Could not send. ") + (r.ok() ? "HTTP " + r.httpCode : Net.explain(r)));
            return;
        }
        String st = r.msg.field("status");
        String bal = r.msg.field("balance");
        if ("ok".equals(st)) {
            midlet.doneFeedback();
            midlet.session().setBalance(bal);
            if (mode == PAIR || mode == TRIAL) {
                s.token = r.msg.field("token");
                s.credits = true;
                String err = s.save();
                if (err != null) {
                    status(err);
                    return;
                }
            }
            String model = r.msg.field("model");
            if (mode == TRIAL && model.length() > 0) {
                // the trial works with one model: new chats and the chat now open start with it
                Models.setTrial(model);
                Models.setLast(model);
                midlet.session().newChat(model);
            } else {
                Models.setTrial(""); // a code opens every model
            }
            status(mode == TRIAL ? L.f("Trial started: {0} credits. A credit code opens every model.", bal)
                    : (mode == PAIR ? L.t("Paired. ") : L.t("Added. "))
                    + L.t("Balance: ") + bal + L.t(" credits"));
            page.setDigits(null);
            form.removeCommand(enterCmd);
            if (trialCmd != null) {
                form.removeCommand(trialCmd);
            }
            form.removeCommand(buyCmd);
            form.removeCommand(deleteCmd);
            form.addCommand(backCmd);
            if ((mode == PAIR || mode == TRIAL) && setup != null) {
                // straight on to the chat, with the balance in its short note
                setup.finish(bal.length() > 0 ? L.f("Ready · {0} credits", bal) : null);
            } else {
                form.addCommand(okCmd); // the centre key goes on: the balance after a top-up, else back
            }
        } else if ("trial_closed".equals(st)) {
            status(L.t("Today's free trials are used up. Try again tomorrow, or get a credit code."));
        } else if (mode == TRIAL && "slow_down".equals(st)) {
            status(L.t("Enough trials were started from this network today. Try again tomorrow."));
        } else if (mode == TRIAL && "not_found".equals(st)) {
            status(L.t("This server has no free trial."));
        } else if ("bad_voucher".equals(st)) {
            status(L.t("The code is not valid or was cancelled."));
        } else if ("used".equals(st)) {
            status(L.t("This code was already used."));
        } else if ("slow_down".equals(st)) {
            status(L.t("Too many wrong codes. Try again in 15 minutes."));
        } else if ("unauthorized".equals(st)) {
            status(L.t("The pairing is not valid. Settings > Options > 'Pair this phone'."));
        } else if ("not_found".equals(st)) {
            status(L.t("This server does not sell credits."));
        } else {
            status(L.t("Server: ") + st);
        }
    }

    private void fetchBalance() {
        Settings s = midlet.settings;
        Net.Result r = Net.request(s.url + "/v1/balance", "POST", s.token,
                S40Message.format(new String[0], new String[0], ""), midlet.userAgent(), null);
        if (!r.ok() || r.msg == null || !"ok".equals(r.msg.field("status"))) {
            status(r.ok() ? L.t("Server: ") + (r.msg == null ? "HTTP " + r.httpCode : r.msg.field("status"))
                    : Net.explain(r));
            return;
        }
        if (!r.msg.flag("credits")) {
            status(L.t("This phone runs without credits."));
            return;
        }
        String bal = r.msg.field("balance");
        Models.setTrial(r.msg.field("trial-model"));
        midlet.session().setBalance(bal);
        status(bal + L.t(" credits"));
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
            return L.t("Chat");
        }
        if ("transcribe".equals(k)) {
            return L.t("Voice");
        }
        if ("voucher".equals(k)) {
            return L.t("Code");
        }
        if ("trial".equals(k)) {
            return L.t("Free trial");
        }
        if ("adjust".equals(k)) {
            return L.t("Adjustment");
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
                        Models.setTrial(r.msg.field("trial-model")); // "" once a code was redeemed
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

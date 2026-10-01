package io.github.emir.claudes40;

import javax.microedition.lcdui.ChoiceGroup;
import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.CommandListener;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.Form;
import javax.microedition.lcdui.StringItem;
import javax.microedition.lcdui.TextField;

/**
 * First-run setup in four steps, "Kurulum 1/4" .. "4/4":
 *
 *   1 language (applies at once)   2 server address (https:// only)
 *   3 connection test (ConnTest)   4 pairing (Pairing)
 *
 * Shown after the splash while Settings.setupDone is false (a fresh install;
 * reinstalling deletes the settings) and from Settings > "Kurulum
 * sihirbazı". Every step can be left with "Kurulumu atla"; finishing or
 * skipping sets setupDone. Each step shows one short line; the longer
 * explanation is behind "Bilgi" / "Info" (Help). Nothing here is sent anywhere except by the
 * existing connection test and pairing.
 */
final class Setup implements CommandListener {

    static final int STEPS = 4;

    private final ClaudeS40MIDlet midlet;
    private int step;
    private Form form;
    private ChoiceGroup langChoice;
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

    /** "Kurulum 2/4" / "Setup 2/4" for step index 0..3. */
    static String title(int step) {
        return L.s("Kurulum ", "Setup ") + (step + 1) + "/" + STEPS;
    }

    void start() {
        step = 0;
        show();
    }

    /** Shows the current step (again). */
    void show() {
        // built each time: the language may have changed in step 1
        nextCmd = new Command(L.s("İleri", "Next"), Command.OK, 1);
        backCmd = new Command(L.s("Geri", "Back"), Command.BACK, 1);
        skipCmd = new Command(L.s("Kurulumu atla", "Skip setup"), Command.SCREEN, 5);
        startCmd = new Command(L.s("Başlat", "Start"), Command.OK, 1);
        finishCmd = new Command(L.s("Bitir", "Finish"), Command.OK, 1);
        againCmd = new Command(L.s("Yeniden eşleştir", "Pair again"), Command.SCREEN, 2);
        helpCmd = Help.command();
        Settings s = midlet.settings;
        if (step == 2) {
            new ConnTest(midlet, this).show(midlet.display());
            return;
        }
        form = new Form(title(step));
        if (step == 0) {
            form.append(new StringItem(null, L.s("Hoş geldin! Telefonu 4 adımda sunucuya bağlayalım.",
                    "Welcome! Four steps connect this phone to your server.")));
            langChoice = new ChoiceGroup(L.s("Dil", "Language"), ChoiceGroup.EXCLUSIVE,
                    new String[] { L.s("Telefona göre", "Same as phone"), "Türkçe", "English" }, null);
            langChoice.setSelectedIndex(Math.max(0, Math.min(2, s.lang)), true);
            form.append(langChoice);
            form.addCommand(nextCmd);
        } else if (step == 1) {
            urlField = new TextField(L.s("Sunucu adresi", "Server address"), s.url.length() > 0 ? s.url : "https://",
                    200, TextField.URL);
            form.append(urlField);
            form.addCommand(nextCmd);
            form.addCommand(backCmd);
        } else {
            if (s.token.length() >= 16) {
                form.append(new StringItem(null, L.s("Bu telefon zaten eşleştirilmiş. Kurulum tamam.",
                        "This phone is already paired. Setup is complete.")));
                form.addCommand(finishCmd);
                form.addCommand(againCmd);
            } else {
                form.append(new StringItem(null, L.s("Son adım: Başlat'a bas, çıkan kodu sunucu sahibi onaylasın.",
                        "Last step: press Start; the server's owner approves the code shown.")));
                form.addCommand(startCmd);
            }
            form.addCommand(backCmd);
        }
        if (step < STEPS - 1 || s.token.length() < 16) {
            form.addCommand(skipCmd);
        }
        form.addCommand(helpCmd);
        form.setCommandListener(this);
        midlet.display().setCurrent(form);
    }

    public void commandAction(Command c, Displayable d) {
        if (c == helpCmd) {
            Help.show(midlet.display(), title(step), helpText(), form);
        } else if (c == skipCmd) {
            skip();
        } else if (c == backCmd) {
            back();
        } else if (c == finishCmd) {
            finish();
        } else if (c == startCmd || c == againCmd) {
            new Pairing(midlet, this).start(midlet.display());
        } else if (c == nextCmd) {
            if (step == 0) {
                chooseLanguage();
            } else if (step == 1) {
                saveUrl();
            }
        }
    }

    /** The longer explanation of the current step. */
    private String helpText() {
        if (step == 0) {
            return L.s("Kurulum dört adım: dil, sunucu adresi, bağlantı testi, eşleştirme. Her adımda "
                    + "'Kurulumu atla' ile çıkabilir, sonra Ayarlar > Seçenekler > Kurulum sihirbazı ile dönebilirsin."
                    + "\n\nSadece denemek için: kurulumu atla, sonra Ayarlar > Test modu (sahte yanıtlar, ağ yok).",
                    "Setup has four steps: language, server address, connection test, pairing. Every step can be "
                    + "left with 'Skip setup'; come back later from Settings > Options > Setup wizard."
                    + "\n\nJust trying it out? Skip setup, then Settings > Test mode (fake replies, no network).");
        }
        if (step == 1) {
            return L.s("Sunucu adresini sunucunun sahibi verir.\n\nAdres https:// ile başlamalı; şifresiz bağlantı "
                    + "hiç kullanılmaz. Sonraki adımda güvenli bağlantı test edilir.",
                    "The server's owner gives you its address.\n\nIt must start with https://; unencrypted "
                    + "connections are never used. The next step tests the secure connection.");
        }
        return L.s("Başlat'a basınca 6 haneli bir kod çıkar. Sunucunun sahibi kodu onaylayınca telefon erişim "
                + "kodunu kendisi alır; uzun bir kod yazmak gerekmez.\n\nErişim kodu bu telefonda ve hafıza "
                + "kartındaki kurulum yedeğinde saklanır, yalnızca https:// adresine gönderilir.",
                "Press Start and a 6-digit code appears. Once the server's owner approves it, the phone fetches its "
                + "access code by itself; nothing long to type.\n\nThe access code is kept on this phone and in the "
                + "setup backup on the memory card, and sent only to the https:// address.");
    }

    private void chooseLanguage() {
        Settings s = midlet.settings;
        int lg = langChoice.getSelectedIndex();
        if (lg >= 0 && lg <= 2 && lg != s.lang) {
            s.lang = lg;
            s.save();
            midlet.rebuildUi();
        }
        next();
    }

    private void saveUrl() {
        Settings s = midlet.settings;
        String url = urlField.getString().trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        if (!Net.isHttps(url) || url.length() <= "https://".length()) {
            midlet.info(L.s("Adres https:// ile başlamalı, örneğin https://sunucu-adresi. HTTP desteklenmez.",
                    "The address must start with https://, for example https://your-server. HTTP is not supported."),
                    form);
            return;
        }
        if (!url.equals(s.url)) {
            s.url = url;
            s.verifiedUrl = ""; // new address: connection test again
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
        if (step < STEPS - 1) {
            step++;
        }
        show();
    }

    void back() {
        if (step > 0) {
            step--;
        }
        show();
    }

    /** Pairing done, or already paired. */
    void finish() {
        Settings s = midlet.settings;
        s.setupDone = true;
        String err = s.save();
        midlet.setupFinished(err != null ? err : s.ready()
                ? L.s("Kurulum tamam. Yazmak için orta tuş.", "All set. Centre key to write.")
                : L.s("Kurulum kaydedildi. Eksik adımları Ayarlar'dan tamamlayabilirsin.",
                        "Setup saved. Finish the missing steps from Settings."));
    }

    private void skip() {
        Settings s = midlet.settings;
        s.setupDone = true;
        String err = s.save();
        midlet.setupSkipped(err != null ? err : L.s(
                "Kurulum atlandı. Ayarlar > Seçenekler > Kurulum sihirbazı.",
                "Setup skipped. Settings > Options > Setup wizard."));
    }
}

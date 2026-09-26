package io.github.emir.claudes40;

import java.util.Vector;

import javax.microedition.lcdui.Alert;
import javax.microedition.lcdui.AlertType;
import javax.microedition.lcdui.ChoiceGroup;
import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.CommandListener;
import javax.microedition.lcdui.Display;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.Form;
import javax.microedition.lcdui.List;
import javax.microedition.lcdui.StringItem;
import javax.microedition.lcdui.TextBox;
import javax.microedition.lcdui.TextField;
import javax.microedition.midlet.MIDlet;

/**
 * Claude S40: an unofficial Claude client for Nokia Series 40.
 *
 * Screens: animated splash (Splash), main menu (HomeCanvas), chat
 * (ChatCanvas), chats on the server (ChatList), quick prompts (List),
 * message editor (the phone's own TextBox), connection test (ConnTest),
 * pairing (Pairing), settings and about (Form). English or Turkish UI (L). Networking happens only on
 * worker threads.
 */
public class ClaudeS40MIDlet extends MIDlet implements CommandListener {

    /** Shown in About; the JAD/manifest vendor field is ASCII-only ("Emir Karsiyakali"). */
    static final String AUTHOR = "Emir Karşıyakalı";

    /*
     * Quick prompts for everyday use. Most end with ": " so the editor opens
     * with the cursor ready for the user's own text (the phrase to translate,
     * the message to answer, ...). Short titles in the list, full text in the
     * editor. Same order in all four arrays.
     */
    static final String[] TITLES_EN = {
        "Search the web", "Weather", "Today's news", "Exchange rates", "Translate to English", "Translate to Turkish", "Reply to a message", "Write a text for me",
        "Fix my writing", "Summarize", "Quick answer", "Explain simply", "Calculate / convert",
        "What does it mean?", "What can I cook?", "Help me decide", "How do I...?", "Roast this phone",
    };

    static final String[] PROMPTS_EN = {
        "Search the web and answer briefly: ",
        "Weather today and tomorrow in: ",
        "Search the web: the most important news today, 5 short lines.",
        "Current exchange rate, search the web, numbers first: ",
        "Translate to English: ",
        "Translate to Turkish: ",
        "Write a short, friendly reply to this message: ",
        "Write a short text message that says: ",
        "Fix the spelling and grammar, keep my tone: ",
        "Summarize in 3 short lines: ",
        "Answer in 2 sentences: ",
        "Explain simply, like I'm new to it: ",
        "Calculate or convert, result first: ",
        "What does this word or phrase mean? One example: ",
        "What can I cook with: ",
        "Help me decide, short pros and cons: ",
        "Step by step, at most 5 steps: how do I ",
        "Roast this phone. It's a Nokia 6300 from 2007 and it's talking to you.",
    };

    static final String[] TITLES_TR = {
        "Web'de ara", "Hava durumu", "Bugünün haberleri", "Döviz ve altın", "İngilizceye çevir", "Türkçeye çevir", "Mesaja cevap yaz", "Benim için SMS yaz",
        "Yazımı düzelt", "Özetle", "Kısa cevap", "Basitçe anlat", "Hesapla / çevir",
        "Bu ne demek?", "Ne pişirebilirim?", "Karar vermeme yardım et", "Nasıl yapılır?", "Bu telefonu roastla",
    };

    static final String[] PROMPTS_TR = {
        "Web'de ara ve kısaca cevapla: ",
        "Bugün ve yarın hava durumu, şehir: ",
        "Web'de ara: bugünün en önemli haberleri, 5 kısa satır.",
        "Güncel kur, web'de ara, önce rakamlar: ",
        "İngilizceye çevir: ",
        "Türkçeye çevir: ",
        "Bu mesaja kısa ve samimi bir cevap yaz: ",
        "Şunu söyleyen kısa bir SMS yaz: ",
        "Yazım ve dil bilgisini düzelt, üslubumu koru: ",
        "3 kısa satırda özetle: ",
        "2 cümleyle cevapla: ",
        "Yeni başlayan birine anlatır gibi basitçe anlat: ",
        "Hesapla ya da birim çevir, önce sonucu yaz: ",
        "Bu kelime ya da ifade ne demek? Bir örnekle: ",
        "Elimde şunlar var, ne pişirebilirim: ",
        "Karar vermeme yardım et, kısa artı ve eksiler: ",
        "En fazla 5 adımda anlat, nasıl yapılır: ",
        "Bu telefonu roastla. 2007 yapımı bir Nokia 6300 ve şu an seninle konuşuyor.",
    };

    final Settings settings = new Settings();
    private Display display;
    private ChatSession session;
    private ChatCanvas chat;
    private HomeCanvas home;
    private List prompts;
    private String[] promptTexts;
    private TextBox composer;
    private Form settingsForm;
    private TextField urlField;
    private TextField tokenField;
    private ChoiceGroup langChoice;
    private ChoiceGroup themeChoice;
    private ChoiceGroup sizeChoice;
    private ChoiceGroup feedbackChoice;
    private ChoiceGroup testChoice;
    private ChoiceGroup claudeChoice;
    private ChatList chatList;
    private ConnTest connTest;
    private Form about;

    private Command sendCmd;
    private Command composerBackCmd;
    private Command saveCmd;
    private Command formBackCmd;
    private Command pairCmd;
    private Command jingleCmd;
    private Command splashCmd;
    private Command promptsBackCmd;

    protected void startApp() {
        if (display == null) {
            display = Display.getDisplay(this);
            settings.load(getAppProperty("ClaudeS40-Gateway"));
            L.init(settings);
            Theme.apply(settings);
            sendCmd = new Command(L.s("Gönder", "Send"), Command.OK, 1);
            composerBackCmd = new Command(L.s("Geri", "Back"), Command.BACK, 1);
            saveCmd = new Command(L.s("Kaydet", "Save"), Command.OK, 1);
            formBackCmd = new Command(L.s("Geri", "Back"), Command.BACK, 1);
            pairCmd = new Command(L.s("Cihazı eşleştir", "Pair this phone"), Command.SCREEN, 2);
            jingleCmd = new Command(L.s("Melodiyi çal", "Play the jingle"), Command.SCREEN, 2);
            splashCmd = new Command(L.s("Açılışı izle", "Replay the intro"), Command.SCREEN, 3);
            promptsBackCmd = new Command(L.s("Geri", "Back"), Command.BACK, 1);
            promptTexts = L.turkish() ? PROMPTS_TR : PROMPTS_EN;
            session = new ChatSession(this);
            chat = new ChatCanvas(this, session);
            if (settings.saveChat) {
                Vector saved = ChatStore.load();
                session.restore(ChatStore.conversation, saved);
            }
            home = new HomeCanvas(this);
            display.setCurrent(new Splash(this));
            return;
        }
        display.setCurrent(home);
    }

    protected void pauseApp() {
        // a running request finishes on its own thread; nothing to stop
    }

    protected void destroyApp(boolean unconditional) {
        // nothing is stored except Settings, which are saved explicitly
    }

    void exit() {
        destroyApp(true);
        notifyDestroyed();
    }

    String attr(String key) {
        String v = getAppProperty(key);
        return v == null ? "" : v;
    }

    String userAgent() {
        return "ClaudeS40/" + attr("MIDlet-Version");
    }

    /** One line under the title on the home screen. */
    String homeStatus() {
        if (settings.testMode) {
            return L.s("Test modu: sahte yanıtlar", "Test mode: fake replies");
        }
        if (!Net.isHttps(settings.url)) {
            return L.s("Kurulum: sunucu adresi gerekli", "Setup: server address needed");
        }
        if (!settings.connectionVerified()) {
            return L.s("Kurulum: bağlantı testi gerekli", "Setup: run the connection test");
        }
        if (settings.token.length() < 16) {
            return L.s("Kurulum: cihazı eşleştir", "Setup: pair this phone");
        }
        return L.s("Hazır · Nokia 6300'de Claude", "Ready · Claude on a Nokia 6300");
    }

    /** Re-applies theme and text size after a settings change. */
    void applyLook() {
        Theme.apply(settings);
        chat.repaint();
        home.repaint();
    }

    /** Sound + vibration when a reply arrives (Settings > Sound & vibration). */
    void replyFeedback() {
        Sound.play(settings, Sound.CHIME);
        if (settings.vibrate) {
            display.vibrate(180);
        }
    }

    // ------------------------------------------------------------ navigation

    void showMenu() {
        display.setCurrent(home);
    }

    void showChat() {
        display.setCurrent(chat);
    }

    /** Opens the editor with the saved draft, or with `text` if given. */
    void showComposer(String text) {
        if (composer == null) {
            composer = new TextBox(L.s("Claude'a yaz", "Message Claude"), "", ChatSession.MAX_MESSAGE, TextField.ANY);
            composer.addCommand(sendCmd);
            composer.addCommand(composerBackCmd);
            composer.setCommandListener(this);
        }
        String value = text != null ? text : session.draft();
        try {
            composer.setString(value);
        } catch (IllegalArgumentException e) {
            composer.setString("");
        }
        display.setCurrent(composer);
    }

    /** The server's list of earlier chats. */
    void showChats() {
        if (!chatReady()) {
            return;
        }
        if (settings.testMode) {
            info(L.s("Test modunda sunucudaki sohbetler gösterilmez.", "Server chats are not shown in test mode."), home);
            return;
        }
        if (chatList == null) {
            chatList = new ChatList(this);
        }
        chatList.show(display);
    }

    /** Opens a chat from ChatList in the chat screen. */
    void openConversation(String id) {
        String err = session.open(id);
        if (err != null) {
            info(err, chat);
        } else {
            showChat();
        }
    }

    void showPrompts() {
        if (!chatReady()) {
            return;
        }
        if (prompts == null) {
            prompts = new List(L.s("Hızlı sorular", "Quick prompts"), List.IMPLICIT,
                    L.turkish() ? TITLES_TR : TITLES_EN, null);
            prompts.addCommand(promptsBackCmd);
            prompts.setCommandListener(this);
        }
        display.setCurrent(prompts);
    }

    void info(String text, Displayable next) {
        Alert a = new Alert("Claude S40", text, null, AlertType.INFO);
        a.setTimeout(Alert.FOREVER);
        display.setCurrent(a, next);
    }

    /** True if chat can be used; otherwise explains what is missing. */
    private boolean chatReady() {
        if (settings.testMode) {
            return true;
        }
        String missing = null;
        if (!Net.isHttps(settings.url)) {
            missing = L.s("Önce Ayarlar'dan sunucu adresini (https://...) girin.",
                    "First enter the server address (https://...) in Settings.");
        } else if (!settings.connectionVerified()) {
            missing = L.s("Önce 'Bağlantı testi'ni çalıştırın. Güvenli bağlantı doğrulanmadan mesaj gönderilmez.",
                    "Run the 'Connection test' first. Nothing is sent before the secure connection is verified.");
        } else if (settings.token.length() < 16) {
            missing = L.s("Önce Ayarlar > Seçenekler > 'Cihazı eşleştir' ile telefonu eşleştirin.",
                    "Pair this phone first: Settings > Options > 'Pair this phone'.");
        }
        if (missing != null) {
            info(missing, home);
            return false;
        }
        return true;
    }

    public void commandAction(Command c, Displayable d) {
        if (d == composer) {
            if (c == sendCmd) {
                String err = session.send(composer.getString());
                if (err != null) {
                    info(err, composer);
                } else {
                    showChat();
                }
            } else if (c == composerBackCmd) {
                session.setDraft(composer.getString());
                showChat();
            }
        } else if (d == prompts) {
            if (c == List.SELECT_COMMAND) {
                showComposer(promptTexts[prompts.getSelectedIndex()]);
            } else {
                showChat();
            }
        } else if (d == settingsForm) {
            if (c == saveCmd) {
                saveSettings();
            } else if (c == pairCmd) {
                startPairing();
            } else {
                showMenu();
            }
        } else if (d == about) {
            if (c == jingleCmd) {
                Sound.play(settings, Sound.JINGLE);
            } else if (c == splashCmd) {
                display.setCurrent(new Splash(this));
            } else {
                showMenu();
            }
        }
    }

    void menuSelected(int i) {
        switch (i) {
        case 0:
            if (chatReady()) {
                showChat();
            }
            break;
        case 1:
            showChats();
            break;
        case 2:
            showPrompts();
            break;
        case 3:
            String err = session.newChat();
            if (err != null) {
                info(err, home);
            } else if (chatReady()) {
                showChat();
            }
            break;
        case 4:
            if (connTest == null) {
                connTest = new ConnTest(this);
            }
            connTest.show(display);
            break;
        case 5:
            showSettings();
            break;
        case 6:
            showAbout();
            break;
        case 7:
            exit();
            break;
        default:
            break;
        }
    }

    // ------------------------------------------------------------ settings

    private void showSettings() {
        settingsForm = new Form(L.s("Ayarlar", "Settings"));
        langChoice = new ChoiceGroup(L.s("Dil (yeniden açınca)", "Language (after restart)"), ChoiceGroup.EXCLUSIVE,
                new String[] { L.s("Telefona göre", "Same as phone"), "Türkçe", "English" }, null);
        langChoice.setSelectedIndex(Math.max(0, Math.min(2, settings.lang)), true);
        themeChoice = new ChoiceGroup(L.s("Görünüm", "Look"), ChoiceGroup.EXCLUSIVE,
                new String[] { L.s("Gündüz", "Light"), L.s("Gece", "Dark") }, null);
        themeChoice.setSelectedIndex(settings.theme == 1 ? 1 : 0, true);
        sizeChoice = new ChoiceGroup(L.s("Yazı boyutu", "Text size"), ChoiceGroup.EXCLUSIVE,
                new String[] { L.s("Küçük", "Small"), L.s("Orta", "Medium"), L.s("Büyük", "Large") }, null);
        sizeChoice.setSelectedIndex(Math.max(0, Math.min(2, settings.fontSize)), true);
        feedbackChoice = new ChoiceGroup(L.s("Ses ve titreşim", "Sound & vibration"), ChoiceGroup.MULTIPLE,
                new String[] { L.s("Melodi ve bildirim sesi", "Jingle and reply sound"),
                    L.s("Yanıtta titreşim", "Vibrate on reply") }, null);
        feedbackChoice.setSelectedIndex(0, settings.sound);
        feedbackChoice.setSelectedIndex(1, settings.vibrate);
        urlField = new TextField(L.s("Sunucu adresi (https://...)", "Server address (https://...)"), settings.url,
                200, TextField.URL);
        tokenField = new TextField(L.s("Erişim kodu", "Access code"), settings.token, 64,
                TextField.ANY | TextField.SENSITIVE | TextField.NON_PREDICTIVE);
        testChoice = new ChoiceGroup(L.s("Test modu", "Test mode"), ChoiceGroup.MULTIPLE,
                new String[] { L.s("Sahte yanıt (ağ kullanılmaz)", "Fake replies (no network)") }, null);
        testChoice.setSelectedIndex(0, settings.testMode);
        claudeChoice = new ChoiceGroup("Claude", ChoiceGroup.MULTIPLE,
                new String[] { L.s("Web'de arayabilir (haber, hava, kur...)", "May search the web (news, weather...)"),
                    L.s("Son sohbeti telefonda sakla", "Keep last chat on phone") }, null);
        claudeChoice.setSelectedIndex(0, settings.webSearch);
        claudeChoice.setSelectedIndex(1, settings.saveChat);
        settingsForm.append(langChoice);
        settingsForm.append(themeChoice);
        settingsForm.append(sizeChoice);
        settingsForm.append(feedbackChoice);
        settingsForm.append(claudeChoice);
        settingsForm.append(urlField);
        settingsForm.append(tokenField);
        settingsForm.append(testChoice);
        settingsForm.append(new StringItem(null, L.s(
                "Erişim kodunu yazmak yerine Seçenekler > 'Cihazı eşleştir' kullanılabilir. "
                        + "Erişim kodu yalnızca bu telefonda saklanır ve sadece https:// adresine gönderilir. ",
                "Instead of typing the access code, use Options > 'Pair this phone'. "
                        + "The code is stored only on this phone and sent only to the https:// address. ")
                + (settings.connectionVerified()
                        ? L.s("Bu adres için bağlantı testi geçti.", "The connection test passed for this address.")
                        : L.s("Bu adres için bağlantı testi henüz geçmedi.", "No passed connection test for this address yet."))));
        settingsForm.addCommand(saveCmd);
        settingsForm.addCommand(pairCmd);
        settingsForm.addCommand(formBackCmd);
        settingsForm.setCommandListener(this);
        display.setCurrent(settingsForm);
    }

    private void saveSettings() {
        String url = urlField.getString().trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        String token = tokenField.getString().trim();
        if (url.length() > 0 && !Net.isHttps(url)) {
            info(L.s("Adres https:// ile başlamalı. HTTP desteklenmez.",
                    "The address must start with https://. HTTP is not supported."), settingsForm);
            return;
        }
        if (token.length() > 0 && !validToken(token)) {
            info(L.s("Erişim kodu 16-64 harf/rakam olmalı.", "The access code must be 16-64 letters/digits."),
                    settingsForm);
            return;
        }
        if (!url.equals(settings.url)) {
            settings.verifiedUrl = ""; // new address: connection test again
        }
        settings.url = url;
        settings.token = token;
        settings.testMode = testChoice.isSelected(0);
        int lg = langChoice.getSelectedIndex();
        boolean langChanged = lg >= 0 && lg <= 2 && lg != settings.lang;
        if (lg >= 0 && lg <= 2) {
            settings.lang = lg;
        }
        int t = themeChoice.getSelectedIndex();
        settings.theme = t == 1 ? 1 : 0;
        int fs = sizeChoice.getSelectedIndex();
        settings.fontSize = fs >= 0 && fs <= 2 ? fs : 1;
        settings.sound = feedbackChoice.isSelected(0);
        settings.vibrate = feedbackChoice.isSelected(1);
        settings.webSearch = claudeChoice.isSelected(0);
        boolean keep = claudeChoice.isSelected(1);
        if (keep != settings.saveChat) {
            settings.saveChat = keep;
            if (keep) {
                session.markUnsaved();
                session.persist();
            } else {
                ChatStore.delete();
            }
        }
        String err = settings.save();
        applyLook();
        String done = langChanged
                ? L.s("Kaydedildi. Dil, uygulama yeniden açılınca değişir.", "Saved. The language changes after a restart.")
                : L.s("Kaydedildi.", "Saved.");
        info(err != null ? err : done, home);
    }

    private void startPairing() {
        if (!Net.isHttps(settings.url)) {
            info(L.s("Önce sunucu adresini (https://...) kaydedin.", "Save the server address (https://...) first."),
                    settingsForm);
        } else if (!settings.connectionVerified()) {
            info(L.s("Önce 'Bağlantı testi'ni çalıştırın. Eşleştirme yalnızca doğrulanmış bağlantıyla yapılır.",
                    "Run the 'Connection test' first. Pairing only runs over a verified connection."), home);
        } else {
            new Pairing(this).start(display);
        }
    }

    private static boolean validToken(String t) {
        if (t.length() < 16 || t.length() > 64) {
            return false;
        }
        for (int i = 0; i < t.length(); i++) {
            char ch = t.charAt(i);
            boolean ok = (ch >= '0' && ch <= '9') || (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z');
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------ about

    private void showAbout() {
        about = new Form(L.s("Hakkında", "About"));
        about.append(new StringItem(attr("MIDlet-Name"),
                L.s("Sürüm ", "Version ") + attr("MIDlet-Version") + L.s(" (derleme ", " (build ")
                        + attr("ClaudeS40-Build") + ")"));
        about.append(new StringItem(null, L.s("Nokia Series 40 için resmî olmayan Claude istemcisi.",
                "An unofficial Claude client for Nokia Series 40.")));
        about.append(new StringItem(null, L.s(
                "2007 yapımı bir telefonda, 2026'nın yapay zekâsı. Claude telefonda çalışmaz: mesajlar "
                        + "şifreli bağlantıyla sunucumuza, oradan Claude API'ye gider.",
                "A 2007 phone, 2026 AI. Claude does not run on the phone: messages travel over an "
                        + "encrypted link to our server and on to the Claude API.")));
        about.append(new StringItem(null, L.s("Anthropic veya Nokia'nın resmî bir uygulaması değildir.",
                "Not an official Anthropic or Nokia app.")));
        about.append(new StringItem(null, L.s("Test edilen cihaz: Nokia 6300 RM-217, V06.60. "
                + "Diğer cihazlarla uyumluluk test edilmedi.",
                "Tested on: Nokia 6300 RM-217, firmware V06.60. Other phones are untested.")));
        about.append(new StringItem(null, L.s(
                "Sohbetler sunucuda son mesajdan 30 gün sonra silinir. Telefonda yalnızca Ayarlar'da "
                        + "'Son sohbeti telefonda sakla' açıksa son sohbet tutulur.",
                "The server deletes chats 30 days after the last message. The phone keeps the last chat only "
                        + "if 'Keep last chat on phone' is on in Settings.")));
        about.append(new StringItem(null, L.s(
                "Web araması Anthropic'in arama aracıyla sunucuda yapılır; telefonun tarayıcısı kullanılmaz.",
                "Web search runs on the server with Anthropic's search tool, not the phone's browser.")));
        about.append(new StringItem(null, L.s("Geliştiren: ", "Made by: ") + AUTHOR));
        about.append(new StringItem(null, "github.com/emir/claude-s40"));
        about.append(new StringItem(L.s("Platform", "Platform"), prop("microedition.platform")));
        about.addCommand(jingleCmd);
        about.addCommand(splashCmd);
        about.addCommand(formBackCmd);
        about.setCommandListener(this);
        display.setCurrent(about);
    }

    static String prop(String key) {
        try {
            String v = System.getProperty(key);
            return v == null ? "-" : v;
        } catch (SecurityException e) {
            return "-";
        }
    }
}

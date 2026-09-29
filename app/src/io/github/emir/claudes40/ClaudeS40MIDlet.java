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
 * (ChatCanvas), chats on the server (ChatList), replies saved on the phone
 * (SavedList), quick prompts (List), message editor (the phone's own
 * TextBox), voice message (Dictation), add to calendar (CalendarForm), connection test (ConnTest),
 * pairing (Pairing), first-run setup (Setup), settings, data usage,
 * shortcuts and about (Form). English or Turkish UI (L); changing the language rebuilds the
 * screens (rebuildUi), no restart needed. Networking happens only on worker
 * threads.
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
        "Add to my calendar", "Add a to-do", "Fix my writing", "Summarize", "Quick answer", "Explain simply", "Calculate / convert",
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
        "Add to my calendar: ",
        "Add to my to-do list: ",
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
        "Web'de ara", "Hava durumu", "Bugünün haberleri", "Döviz ve altın", "İngilizceye çevir", "Türkçeye çevir", "Mesaja cevap yaz", "Benim için mesaj yaz",
        "Takvimime ekle", "Yapılacak ekle", "Yazımı düzelt", "Özetle", "Kısa cevap", "Basitçe anlat", "Hesapla / çevir",
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
        "Şunu söyleyen kısa bir mesaj yaz: ",
        "Takvimime ekle: ",
        "Yapılacaklar listeme ekle: ",
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
    private Command dictateCmd;
    private Command saveCmd;
    private Command formBackCmd;
    private Command pairCmd;
    private Command jingleCmd;
    private Command splashCmd;
    private Command promptsBackCmd;
    private Command wizardCmd;
    private Form shortcuts;
    private Displayable shortcutsBack;
    private ChoiceGroup lightChoice;
    /** Message actions (ChatCanvas selection): list, what each row does, the message. */
    private List actionList;
    private int[] actionIds;
    private ChatSession.Entry actionEntry;
    private TextBox viewer;
    private Command listBackCmd;
    private TextField notesField;
    private Command dataCmd;
    private Form dataForm;
    private Command resetCmd;
    private SavedList savedList;
    private Command resetSetupCmd;
    private Command resetYesCmd;
    private Command resetNoCmd;
    private Alert resetConfirm;

    private static final int ACT_READ = 0;
    private static final int ACT_SHORTEN = 1;
    private static final int ACT_SIMPLER = 2;
    private static final int ACT_TO_TR = 3;
    private static final int ACT_TO_EN = 4;
    private static final int ACT_ASK = 5;
    private static final int ACT_RESEND = 6;
    private static final int ACT_EDITOR = 7;
    private static final int ACT_SAVE = 8;
    private static final int ACT_CALENDAR = 9;

    protected void startApp() {
        if (display == null) {
            display = Display.getDisplay(this);
            settings.load(getAppProperty("ClaudeS40-Gateway"));
            L.init(settings);
            Theme.apply(settings);
            session = new ChatSession(this);
            buildUi();
            if (settings.saveChat) {
                Vector saved = ChatStore.load();
                session.restore(ChatStore.conversation, saved);
            }
            if (!settings.stored && !settings.testMode && hasFiles()) {
                // just installed: look for the setup kept outside the app while the splash runs
                restoring = true;
                new Thread(new Runnable() {
                    public void run() {
                        restoreSetup();
                    }
                }).start();
            }
            display.setCurrent(new Splash(this));
            return;
        }
        display.setCurrent(home);
    }

    /** Creates the screens and commands in the current language; the chat itself is kept. */
    private void buildUi() {
        sendCmd = new Command(L.s("Gönder", "Send"), Command.OK, 1);
        composerBackCmd = new Command(L.s("Geri", "Back"), Command.BACK, 1);
        dictateCmd = new Command(L.s("Sesle yaz", "Dictate"), Command.SCREEN, 2);
        saveCmd = new Command(L.s("Kaydet", "Save"), Command.OK, 1);
        formBackCmd = new Command(L.s("Geri", "Back"), Command.BACK, 1);
        pairCmd = new Command(L.s("Cihazı eşleştir", "Pair this phone"), Command.SCREEN, 2);
        wizardCmd = new Command(L.s("Kurulum sihirbazı", "Setup wizard"), Command.SCREEN, 3);
        jingleCmd = new Command(L.s("Melodiyi çal", "Play the jingle"), Command.SCREEN, 2);
        splashCmd = new Command(L.s("Açılışı izle", "Replay the intro"), Command.SCREEN, 3);
        promptsBackCmd = new Command(L.s("Geri", "Back"), Command.BACK, 1);
        listBackCmd = new Command(L.s("Geri", "Back"), Command.BACK, 1);
        dataCmd = new Command(L.s("Veri kullanımı", "Data usage"), Command.SCREEN, 4);
        resetSetupCmd = new Command(L.s("Kurulumu sıfırla", "Reset setup"), Command.SCREEN, 5);
        resetYesCmd = new Command(L.s("Sıfırla", "Reset"), Command.OK, 1);
        resetNoCmd = new Command(L.s("Vazgeç", "Cancel"), Command.BACK, 1);
        resetCmd = new Command(L.s("Sıfırla", "Reset"), Command.SCREEN, 2);
        actionList = null;
        savedList = null;
        viewer = null;
        promptTexts = L.turkish() ? PROMPTS_TR : PROMPTS_EN;
        chat = new ChatCanvas(this, session);
        home = new HomeCanvas(this);
        prompts = null;
        composer = null;
        chatList = null;
        connTest = null;
        shortcuts = null;
    }

    /** After a language change: every screen again in the new language. */
    void rebuildUi() {
        L.init(settings);
        buildUi();
    }

    Display display() {
        return display;
    }

    /** Backup is being read (start-up); the splash end waits for it. */
    private boolean restoring;
    private boolean splashDone;
    private boolean restored;

    /** Worker thread at start-up: the setup kept outside the app (Backup), if any. */
    private void restoreSetup() {
        boolean ok = Backup.restore(settings);
        if (ok) {
            settings.save();
            rebuildUi(); // the language may have changed
            Theme.apply(settings);
        }
        boolean go;
        synchronized (this) {
            restoring = false;
            restored = ok;
            go = splashDone;
        }
        if (go) {
            startScreen();
        }
    }

    /** End of the splash: the setup wizard on a phone that is not set up yet, else the menu. */
    void afterSplash() {
        synchronized (this) {
            if (restoring) {
                splashDone = true; // restoreSetup() continues
                return;
            }
        }
        startScreen();
    }

    private void startScreen() {
        boolean r;
        synchronized (this) {
            r = restored;
            restored = false;
        }
        if (r) {
            info(L.s("Önceki kurulum geri yüklendi: sunucu, eşleştirme ve notların. Baştan kurmak için: "
                    + "Ayarlar > Seçenekler > Kurulumu sıfırla.",
                    "Your earlier setup was restored: server, pairing and your notes. To start over: "
                    + "Settings > Options > Reset setup."), home);
            return;
        }
        if (!settings.setupDone && !settings.ready() && !settings.testMode) {
            new Setup(this).start();
        } else {
            showMenu();
        }
    }

    void setupFinished(String message) {
        info(message, settings.ready() ? (Displayable) chat : home);
    }

    void setupSkipped(String message) {
        info(message, home);
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
        String left = session.remainingToday();
        return left.length() > 0 ? L.s("Hazır · bugün " + left + " hak kaldı", "Ready · " + left + " left today")
                : L.s("Hazır · Nokia 6300'de Claude", "Ready · Claude on a Nokia 6300");
    }

    /** Second line of a home row; the Chat row shows the draft or the last message. */
    String homeHint(int row, String fallback) {
        if (row != 0) {
            return fallback;
        }
        String d = session.draft();
        if (d.length() > 0) {
            return L.s("Taslak: ", "Draft: ") + firstLine(d);
        }
        String last = session.lastUserText();
        return last.length() > 0 ? L.s("Son: ", "Last: ") + firstLine(last) : fallback;
    }

    private static String firstLine(String t) {
        int nl = t.indexOf('\n');
        return (nl < 0 ? t : t.substring(0, nl)).trim();
    }

    /** Re-applies theme and text size after a settings change. */
    void applyLook() {
        Theme.apply(settings);
        chat.repaint();
        home.repaint();
    }

    /**
     * The light comes on for a reply only after this long without a key: the
     * screen may be dark by then. While the user is at it the light is still
     * on, and the Nokia 6300 shows flashBacklight on a lit screen as a blink.
     */
    private static final long LIGHT_IDLE_MS = 30000;
    /** Last key press or softkey in the app (userActive). */
    private long lastInput;

    /** Called on key presses and commands. */
    synchronized void userActive() {
        lastInput = System.currentTimeMillis();
    }

    private synchronized boolean idle() {
        return System.currentTimeMillis() - lastInput > LIGHT_IDLE_MS;
    }

    /** Sound + vibration when a reply arrives (Settings > Sound & vibration); light only if idle. */
    void replyFeedback() {
        Sound.play(settings, Sound.CHIME);
        if (settings.vibrate) {
            display.vibrate(180);
        }
        if (settings.lightReply && idle()) {
            display.flashBacklight(4000);
        }
    }

    /** The phone has JSR 75 FileConnection (saved replies, Files). */
    static boolean hasFiles() {
        return !"-".equals(prop("microedition.io.file.FileConnection.version"));
    }

    /** The phone has the JSR 75 PIM API (calendar and to-do list, Pim). */
    static boolean hasPim() {
        return !"-".equals(prop("microedition.pim.version"));
    }

    private static int recording = -1;

    /**
     * The phone can record audio for an app (JSR 135 RecordControl, Rec):
     * voice messages (Dictation).
     */
    static synchronized boolean hasRecording() {
        if (recording < 0) {
            boolean ok = "true".equals(prop("supports.audio.capture")) && !"false".equals(prop("supports.recording"));
            if (ok) {
                try {
                    Class.forName("javax.microedition.media.control.RecordControl");
                } catch (Throwable t) {
                    ok = false;
                }
            }
            recording = ok ? 1 : 0;
        }
        return recording == 1;
    }

    /** Records a voice message; its text then opens in the editor (dictated). */
    void showDictation(boolean fromComposer) {
        if (!chatReady()) {
            return;
        }
        if (!hasRecording()) {
            info(L.s("Bu telefon uygulamaların ses kaydetmesini desteklemiyor.",
                    "This phone does not let apps record audio."), fromComposer ? (Displayable) composer : chat);
            return;
        }
        new Dictation(this, fromComposer).start();
    }

    /** The server's text for a voice message: added to the draft, shown in the editor to check and send. */
    void dictated(String text) {
        String d = session.draft().trim();
        String t = d.length() > 0 ? d + " " + text : text;
        if (t.length() > ChatSession.MAX_MESSAGE) {
            t = t.substring(0, ChatSession.MAX_MESSAGE);
        }
        session.setDraft(t);
        showComposer(t, L.s("Kontrol edip gönderin", "Check, then send"));
    }

    /** Dictation was cancelled: back where it was opened. */
    void dictationClosed(boolean fromComposer) {
        if (fromComposer) {
            showComposer(null);
        } else {
            showChat();
        }
    }

    /** ChatList deleted a chat on the server. */
    void chatDeleted(String id) {
        session.forget(id);
    }

    /** MIDP 2.0 Display.flashBacklight; false if the phone cannot. */
    boolean flashBacklight(int ms) {
        return display.flashBacklight(ms);
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
        showComposer(text, null);
    }

    /** As above; title replaces "Message Claude" this time (a voice message to check). */
    private void showComposer(String text, String title) {
        if (composer == null) {
            composer = new TextBox(L.s("Claude'a yaz", "Message Claude"), "", ChatSession.MAX_MESSAGE, TextField.ANY);
            composer.addCommand(sendCmd);
            if (hasRecording()) {
                composer.addCommand(dictateCmd);
            }
            composer.addCommand(composerBackCmd);
            composer.setCommandListener(this);
        }
        composer.setTitle(title != null ? title : L.s("Claude'a yaz", "Message Claude"));
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

    /** Replies saved on the phone (works without the network). */
    void showSaved() {
        if (!hasFiles()) {
            info(L.s("Bu telefon uygulamaların dosya kaydetmesini desteklemiyor.",
                    "This phone does not let apps save files."), home);
            return;
        }
        if (savedList == null) {
            savedList = new SavedList(this);
        }
        savedList.show();
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

    /** Every key of the chat and reading screens; "Geri" returns to `back`. */
    void showShortcuts(Displayable back) {
        if (shortcuts == null) {
            shortcuts = new Form(L.s("Kısayollar", "Shortcuts"));
            shortcuts.append(new StringItem(L.s("Sohbet", "Chat"), L.s(
                    "Orta tuş veya 5: yaz\n"
                    + "Yukarı / aşağı: bir satır\n"
                    + "2 / 8, sol / sağ: bir sayfa\n"
                    + "1 / 3: mesaj seç; orta tuş: kısalt, çevir, düzenle...\n"
                    + "* / #: en başa / en sona\n"
                    + "0: yanıtın devamı\n"
                    + "7: okuma modu\n"
                    + "9: yazı boyutu\n"
                    + "Hatadan sonra orta tuş: tekrar dene\n",
                    "Centre key or 5: write\n"
                    + "Up / down: one line\n"
                    + "2 / 8, left / right: one page\n"
                    + "1 / 3: select a message; centre key: shorten, translate, edit...\n"
                    + "* / #: top / end\n"
                    + "0: the rest of a reply\n"
                    + "7: reading mode\n"
                    + "9: text size\n"
                    + "After an error, centre key: retry\n")));
            shortcuts.append(new StringItem(L.s("Okuma modu", "Reading mode"), L.s(
                    "Bir yanıtı tam ekran, sayfa sayfa gösterir.\n"
                    + "Orta tuş, 8 veya sağ: sonraki sayfa\n"
                    + "2 veya sol: önceki sayfa\n"
                    + "Yukarı / aşağı: bir satır\n"
                    + "1 / 3: önceki / sonraki yanıt\n"
                    + "* / #: en başa / en sona\n"
                    + "0: yanıtın devamı\n"
                    + "9: yazı boyutu\n"
                    + "7 veya Kapat: sohbete dön\n",
                    "Shows one reply page by page, full width.\n"
                    + "Centre key, 8 or right: next page\n"
                    + "2 or left: previous page\n"
                    + "Up / down: one line\n"
                    + "1 / 3: previous / next reply\n"
                    + "* / #: top / end\n"
                    + "0: the rest of a reply\n"
                    + "9: text size\n"
                    + "7 or Close: back to the chat\n")));
            shortcuts.append(new StringItem(L.s("Ana menü", "Main menu"), L.s(
                    "1-9: satırı doğrudan açar\n", "1-9: opens that row directly\n")));
            shortcuts.append(new StringItem(null, L.s(
                    "Kısalt, çevir gibi işlemler hiçbir şeyi kendiliğinden göndermez: yazma kutusu hazır metinle "
                            + "açılır, Gönder'e sen basarsın.",
                    "Actions like shorten or translate never send by themselves: the editor opens with the text "
                            + "ready and you press Send.")));
            shortcuts.append(new StringItem(null, L.s(
                    "Yanıtın devamını almak ücretsizdir: sunucudaki yanıt gelir, Claude'a tekrar sorulmaz.",
                    "Loading the rest of a reply is free: it comes from the server, Claude is not asked again.")));
            shortcuts.addCommand(formBackCmd);
            shortcuts.setCommandListener(this);
        }
        shortcutsBack = back;
        display.setCurrent(shortcuts);
    }

    /**
     * Actions for a message selected in the chat. Rewording actions open the
     * editor with a prepared request that quotes the start of the message;
     * nothing is sent (and nothing is paid) until the user presses Send.
     */
    void showActions(ChatSession.Entry e) {
        boolean reply = e.kind != ChatSession.KIND_USER;
        boolean entry = Cal.parse(e.text) != null;
        Vector v = new Vector();
        if (hasPim() && entry) {
            v.addElement(new Integer(ACT_CALENDAR)); // Claude prepared an entry: offer it first
        }
        if (reply) {
            int[] rewording = { ACT_READ, ACT_SHORTEN, ACT_SIMPLER, ACT_TO_TR, ACT_TO_EN, ACT_ASK };
            for (int i = 0; i < rewording.length; i++) {
                v.addElement(new Integer(rewording[i]));
            }
            if (hasFiles()) {
                v.addElement(new Integer(ACT_SAVE));
            }
        } else {
            v.addElement(new Integer(ACT_RESEND));
        }
        if (hasPim() && !entry) {
            v.addElement(new Integer(ACT_CALENDAR));
        }
        v.addElement(new Integer(ACT_EDITOR));
        int[] ids = new int[v.size()];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = ((Integer) v.elementAt(i)).intValue();
        }
        String[] labels = new String[ids.length];
        for (int i = 0; i < ids.length; i++) {
            labels[i] = actionLabel(ids[i]);
        }
        actionList = new List(reply ? L.s("Yanıt", "Reply") : L.s("Mesajın", "Your message"), List.IMPLICIT, labels, null);
        actionList.addCommand(listBackCmd);
        actionList.setCommandListener(this);
        actionIds = ids;
        actionEntry = e;
        display.setCurrent(actionList);
    }

    private static String actionLabel(int id) {
        switch (id) {
        case ACT_READ:
            return L.s("Okuma modunda aç", "Open in reading mode");
        case ACT_SHORTEN:
            return L.s("Kısalt", "Make it shorter");
        case ACT_SIMPLER:
            return L.s("Daha basit anlat", "Explain it more simply");
        case ACT_TO_TR:
            return L.s("Türkçeye çevir", "Translate to Turkish");
        case ACT_TO_EN:
            return L.s("İngilizceye çevir", "Translate to English");
        case ACT_ASK:
            return L.s("Bunun hakkında sor", "Ask about this");
        case ACT_RESEND:
            return L.s("Düzenleyip yeniden gönder", "Edit and send again");
        case ACT_SAVE:
            return L.s("Telefona kaydet (.txt)", "Save to phone (.txt)");
        case ACT_CALENDAR:
            return L.s("Takvime / yapılacaklara ekle", "Add to calendar / to-do");
        default:
            return L.s("Düzenleyicide aç", "Open in editor");
        }
    }

    private void runAction(int id, ChatSession.Entry e) {
        String q = "\"" + quote(e.text) + "\"";
        switch (id) {
        case ACT_READ:
            chat.read(e.uid);
            showChat();
            break;
        case ACT_SHORTEN:
            showComposer(L.s("Şu yanıtını kısalt, en fazla 3 cümle: ", "Make this reply shorter, 3 sentences at most: ") + q);
            break;
        case ACT_SIMPLER:
            showComposer(L.s("Şu yanıtını daha basit anlat: ", "Explain this reply more simply: ") + q);
            break;
        case ACT_TO_TR:
            showComposer(L.s("Şu yanıtının tamamını Türkçeye çevir: ", "Translate all of this reply to Turkish: ") + q);
            break;
        case ACT_TO_EN:
            showComposer(L.s("Şu yanıtının tamamını İngilizceye çevir: ", "Translate all of this reply to English: ") + q);
            break;
        case ACT_ASK:
            showComposer(L.s("Şu kısım hakkında: ", "About this part: ") + q + "\n");
            break;
        case ACT_RESEND:
            showComposer(Text.clip(e.text, ChatSession.MAX_MESSAGE));
            break;
        case ACT_SAVE:
            showChat();
            if (savedList == null) {
                savedList = new SavedList(this);
            }
            savedList.save(e, chat);
            break;
        case ACT_CALENDAR:
            new CalendarForm(this, e, chat).show();
            break;
        default:
            showViewer(e);
            break;
        }
    }

    /** The start of a message to quote: its first line, at most 60 characters, cut at a space. */
    static String quote(String t) {
        String s = firstLine(t);
        if (s.length() <= 60) {
            return s;
        }
        int cut = s.lastIndexOf(' ', 60);
        return s.substring(0, cut > 30 ? cut : 60) + "...";
    }

    /**
     * The whole message in the phone's own editor, where the phone's own
     * marking/copying works if it has it. Changes there are not kept.
     */
    private void showViewer(ChatSession.Entry e) {
        viewer = new TextBox(L.s("Metin", "Text"), "", Math.max(1, Math.min(e.text.length(), 8000)), TextField.ANY);
        try {
            viewer.setString(Text.clip(e.text, viewer.getMaxSize()));
        } catch (IllegalArgumentException ex) {
            viewer.setString("");
        }
        viewer.addCommand(listBackCmd);
        viewer.setCommandListener(this);
        display.setCurrent(viewer);
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
        userActive();
        if (d == composer) {
            if (c == sendCmd) {
                String err = session.send(composer.getString());
                if (err != null) {
                    info(err, composer);
                } else {
                    showChat();
                }
            } else if (c == dictateCmd) {
                session.setDraft(composer.getString());
                showDictation(true);
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
            } else if (c == wizardCmd) {
                new Setup(this).start();
            } else if (c == dataCmd) {
                showDataUsage();
            } else if (c == resetSetupCmd) {
                resetConfirm = new Alert(L.s("Kurulumu sıfırla", "Reset setup"), L.s(
                        "Sunucu adresi, eşleştirme ve notların bu telefondan ve yedek dosyasından silinsin mi? "
                                + "Sonra kurulum sihirbazı açılır.",
                        "Delete the server address, pairing and your notes from this phone and from the backup "
                                + "file? The setup wizard opens next."), null, AlertType.WARNING);
                resetConfirm.setTimeout(Alert.FOREVER);
                resetConfirm.addCommand(resetYesCmd);
                resetConfirm.addCommand(resetNoCmd);
                resetConfirm.setCommandListener(this);
                display.setCurrent(resetConfirm);
            } else {
                showMenu();
            }
        } else if (d == resetConfirm) {
            if (c == resetYesCmd) {
                resetSetup();
            } else {
                display.setCurrent(settingsForm != null ? (Displayable) settingsForm : home);
            }
        } else if (d == dataForm) {
            if (c == resetCmd) {
                DataUsage.reset();
                showDataUsage();
            } else {
                display.setCurrent(settingsForm != null ? (Displayable) settingsForm : home);
            }
        } else if (d == actionList) {
            ChatSession.Entry e = actionEntry;
            int i = actionList.getSelectedIndex();
            if (c == List.SELECT_COMMAND && e != null && i >= 0 && i < actionIds.length) {
                runAction(actionIds[i], e);
            } else {
                showChat();
            }
        } else if (d == viewer) {
            showChat();
        } else if (d == shortcuts) {
            display.setCurrent(shortcutsBack != null ? shortcutsBack : chat);
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
            showSaved();
            break;
        case 5:
            if (connTest == null) {
                connTest = new ConnTest(this, null);
            }
            connTest.show(display);
            break;
        case 6:
            showSettings();
            break;
        case 7:
            showAbout();
            break;
        case 8:
            exit();
            break;
        default:
            break;
        }
    }

    // ------------------------------------------------------------ settings

    private void showSettings() {
        settingsForm = new Form(L.s("Ayarlar", "Settings"));
        langChoice = new ChoiceGroup(L.s("Dil", "Language"), ChoiceGroup.EXCLUSIVE,
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
        lightChoice = new ChoiceGroup(L.s("Ekran ışığı", "Backlight"), ChoiceGroup.MULTIPLE,
                new String[] { L.s("Okuma modunda açık tut", "Keep on in reading mode"),
                    L.s("Yanıt gelince yak (ekran kararmışsa)", "Light up for a reply (if the screen went dark)") }, null);
        lightChoice.setSelectedIndex(0, settings.lightReading);
        lightChoice.setSelectedIndex(1, settings.lightReply);
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
        settingsForm.append(lightChoice);
        settingsForm.append(claudeChoice);
        notesField = new TextField(L.s("Claude için notların", "Your notes for Claude"), settings.instructions,
                Settings.MAX_INSTRUCTIONS, TextField.ANY);
        settingsForm.append(notesField);
        settingsForm.append(new StringItem(null, L.s(
                "Örnek: \"Adım Emir, İstanbul'dayım, kısa ve Türkçe yaz.\" Her mesajla sunucuya gönderilir.",
                "Example: \"I'm Emir, I live in Istanbul, keep it short.\" Sent to the server with every message.")));
        settingsForm.append(urlField);
        settingsForm.append(tokenField);
        settingsForm.append(testChoice);
        settingsForm.append(new StringItem(null, L.s(
                "Erişim kodunu yazmak yerine Seçenekler > 'Cihazı eşleştir' kullanılabilir. "
                        + "Erişim kodu bu telefonda ve (uygulama silinince kurulum gerekmesin diye) hafıza kartındaki "
                        + "kurulum yedeğinde saklanır, sadece https:// adresine gönderilir. ",
                "Instead of typing the access code, use Options > 'Pair this phone'. "
                        + "The code is stored on this phone and (so a reinstall needs no setup) in the setup backup on "
                        + "the memory card, and sent only to the https:// address. ")
                + (settings.connectionVerified()
                        ? L.s("Bu adres için bağlantı testi geçti.", "The connection test passed for this address.")
                        : L.s("Bu adres için bağlantı testi henüz geçmedi.", "No passed connection test for this address yet."))));
        settingsForm.addCommand(saveCmd);
        settingsForm.addCommand(pairCmd);
        settingsForm.addCommand(wizardCmd);
        settingsForm.addCommand(dataCmd);
        settingsForm.addCommand(resetSetupCmd);
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
        settings.lightReading = lightChoice.isSelected(0);
        settings.lightReply = lightChoice.isSelected(1);
        settings.webSearch = claudeChoice.isSelected(0);
        settings.instructions = notesField.getString().trim();
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
        if (langChanged) {
            rebuildUi();
        }
        info(err != null ? err : L.s("Kaydedildi.", "Saved."), home);
    }

    /**
     * Forgets the setup (here and in the Backup file) and opens the wizard.
     * The device stays paired on the server until it is revoked there.
     */
    private void resetSetup() {
        String url = getAppProperty("ClaudeS40-Gateway");
        settings.url = url == null ? "" : url.trim();
        settings.token = "";
        settings.verifiedUrl = "";
        settings.setupDone = false;
        settings.instructions = "";
        Backup.forget(settings);
        settings.save();
        new Setup(this).start();
    }

    /** Mobile data used by the app (DataUsage); "Sıfırla" starts the totals again. */
    private void showDataUsage() {
        dataForm = new Form(L.s("Veri kullanımı", "Data usage"));
        long[] t = DataUsage.total();
        dataForm.append(new StringItem(L.s("Bugün", "Today"), DataUsage.describe(DataUsage.today())));
        dataForm.append(new StringItem(L.s("Toplam, başlangıç ", "Total since ") + Text.local(t[3], false),
                DataUsage.describe(t)));
        dataForm.append(new StringItem(null, L.s(
                "Yaklaşık değerler: mesajlar tam, HTTP başlıkları tahminen sayılır. Şifreli bağlantının kurulumu "
                        + "sayılmaz, operatörün saydığı miktar daha fazladır. Test modunda veri kullanılmaz.",
                "Estimates: messages are counted exactly, HTTP headers roughly. The encrypted connection set-up "
                        + "is not counted, so your operator counts more. Test mode uses no data.")));
        dataForm.addCommand(formBackCmd);
        dataForm.addCommand(resetCmd);
        dataForm.setCommandListener(this);
        display.setCurrent(dataForm);
    }

    private void startPairing() {
        if (!Net.isHttps(settings.url)) {
            info(L.s("Önce sunucu adresini (https://...) kaydedin.", "Save the server address (https://...) first."),
                    settingsForm);
        } else if (!settings.connectionVerified()) {
            info(L.s("Önce 'Bağlantı testi'ni çalıştırın. Eşleştirme yalnızca doğrulanmış bağlantıyla yapılır.",
                    "Run the 'Connection test' first. Pairing only runs over a verified connection."), home);
        } else {
            new Pairing(this, null).start(display);
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
        about.append(new StringItem(null, L.s(
                "Sabitlenen sohbetler sunucuda, sabitleme kaldırılana kadar kalır. Telefona kaydedilen yanıtlar ve "
                        + "takvim kayıtları yalnızca telefonda durur.",
                "Pinned chats stay on the server until unpinned. Replies saved on the phone and calendar entries "
                        + "stay on the phone only.")));
        about.append(new StringItem(null, L.s("Geliştiren: ", "Made by: ") + AUTHOR));
        about.append(new StringItem(null, "github.com/emir/claude-s40"));
        about.append(new StringItem(L.s("Platform", "Platform"), prop("microedition.platform")));
        about.append(new StringItem(L.s("Ses kaydı", "Voice recording"), (hasRecording() ? L.s("var", "yes")
                : L.s("yok", "no")) + " (" + prop("audio.encodings") + ")"));
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

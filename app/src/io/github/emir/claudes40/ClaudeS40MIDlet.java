package io.github.emir.claudes40;

import java.util.Vector;

import javax.microedition.io.ConnectionNotFoundException;
import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.CommandListener;
import javax.microedition.lcdui.Display;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.List;
import javax.microedition.lcdui.TextBox;
import javax.microedition.lcdui.TextField;
import javax.microedition.midlet.MIDlet;

/**
 * AIKON (formerly Claude S40): an AI chat client for Nokia Series 40
 * and Symbian S60 (QWERTY, e.g. E63) phones.
 * The package, class and RMS names keep the old name.
 *
 * Screens: animated splash (Splash), main menu (HomeCanvas), chat
 * (ChatCanvas), chats on the server (ChatList), replies saved on the phone
 * (SavedList), quick prompts (List), message editor (the phone's own
 * TextBox), voice message (Dictation), photo (Photo, Cam, PhotoPicker), add to calendar (CalendarForm), connection test (ConnTest),
 * pairing (Pairing), first-run setup (Setup), settings, data usage,
 * shortcuts and about (TextPage). English or Turkish UI (L); changing the language rebuilds the
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
        "Roast this phone. It's an old Nokia and it's talking to you.",
    };



    final Settings settings = new Settings();
    private Display display;
    private ChatSession session;
    private ChatCanvas chat;
    private HomeCanvas home;
    private RowList prompts;
    private String[] promptTexts;
    private TextBox composer;
    private RowList settingsForm;
    /** The setting of each selectable row of the settings list (S_*), in order. */
    private int[] settingRows = new int[0];
    private TextBox editBox;
    private int editing;
    private Command changeCmd;
    private Command editOkCmd;

    private static final int S_SIZE = 0;
    private static final int S_THEME = 1;
    private static final int S_WEB = 2;
    private static final int S_KEEP = 3;
    private static final int S_NOTES = 4;
    private static final int S_SOUND = 5;
    private static final int S_VIBRATE = 6;
    private static final int S_FULL = 7;
    private static final int S_LIGHT = 8;
    private static final int S_LANG = 9;
    private static final int S_URL = 10;
    private static final int S_TOKEN = 11;
    private static final int S_CONN = 12;
    private static final int S_TEST = 13;
    private ChatList chatList;
    private ConnTest connTest;
    private TextPage about;

    private Command sendCmd;
    private Command composerBackCmd;
    private Command dictateCmd;
    private Command photoCmd;
    private Command removePhotoCmd;
    private Command formBackCmd;
    private Command pairCmd;
    private Command creditsCmd;
    private Command jingleCmd;
    private Command splashCmd;
    private Command promptsBackCmd;
    private Command wizardCmd;
    private TextPage shortcuts;
    private Displayable shortcutsBack;
    /** Message actions (ChatCanvas selection): list, what each row does, the message. */
    private RowList actionList;
    private int[] actionIds;
    private ChatSession.Entry actionEntry;
    private TextBox viewer;
    private Command listBackCmd;
    private Command dataCmd;
    private TextPage dataForm;
    private Command resetCmd;
    /** "Bilgi" / "Info" on Settings, Data usage and About (Help). */
    private Command helpCmd;
    private SavedList savedList;
    private Command resetSetupCmd;
    private Command resetYesCmd;
    private Command resetNoCmd;
    private Command updateYesCmd;
    private TextPage updateConfirm;
    private TextPage resetConfirm;

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
            Resume.load();
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
        sendCmd = new Command(L.t("Send"), Command.OK, 1);
        composerBackCmd = new Command(L.t("Back"), Command.BACK, 1);
        dictateCmd = new Command(L.t("Dictate"), Command.SCREEN, 2);
        photoCmd = new Command(L.t("Add a photo"), Command.SCREEN, 3);
        removePhotoCmd = new Command(L.t("Remove the photo"), Command.SCREEN, 3);
        changeCmd = new Command(L.t("Change"), Command.OK, 1);
        editOkCmd = new Command(L.t("OK"), Command.OK, 1);
        formBackCmd = new Command(L.t("Back"), Command.BACK, 1);
        pairCmd = new Command(L.t("Pair this phone"), Command.SCREEN, 2);
        creditsCmd = new Command(L.t("Credits"), Command.SCREEN, 2);
        wizardCmd = new Command(L.t("Setup wizard"), Command.SCREEN, 4);
        jingleCmd = new Command(L.t("Play the jingle"), Command.SCREEN, 2);
        splashCmd = new Command(L.t("Replay the intro"), Command.SCREEN, 3);
        promptsBackCmd = new Command(L.t("Back"), Command.BACK, 1);
        listBackCmd = new Command(L.t("Back"), Command.BACK, 1);
        dataCmd = new Command(L.t("Data usage"), Command.SCREEN, 3);
        resetSetupCmd = new Command(L.t("Reset setup"), Command.SCREEN, 5);
        resetYesCmd = new Command(L.t("Reset"), Command.OK, 1);
        resetNoCmd = new Command(L.t("Cancel"), Command.BACK, 1);
        updateYesCmd = new Command(L.t("Update"), Command.OK, 1);
        resetCmd = new Command(L.t("Reset"), Command.SCREEN, 2);
        helpCmd = Help.command();
        actionList = null;
        savedList = null;
        viewer = null;
        promptTexts = translated(PROMPTS_EN);
        chat = new ChatCanvas(this, session);
        home = new HomeCanvas(this);
        applyFullScreen();
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

    ChatSession session() {
        return session;
    }

    Display display() {
        return display;
    }

    /** Backup is being read (start-up); the splash end waits for it. */
    private boolean restoring;
    private boolean splashDone;
    private boolean restored;

    /** True while the setup backup is still being read at start-up (Splash shows a spinner). */
    synchronized boolean restoringSetup() {
        return restoring;
    }

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
            info(L.t("Your earlier setup was restored: server, pairing and your notes. To start over: "
                    + "Settings > Options > Reset setup."), home);
            return;
        }
        if (!settings.setupDone && !settings.ready() && !settings.testMode) {
            new Setup(this).start();
        } else {
            resumeOrMenu();
        }
    }

    /**
     * Start-up: back in the chat if the app was left there (Resume), with
     * the draft kept for it; that chat is fetched again when it is not the
     * one kept on the phone. Otherwise the menu.
     */
    private void resumeOrMenu() {
        if (!Resume.inChat || !settings.ready() && !settings.testMode) {
            showMenu();
            return;
        }
        if (settings.saveChat && Resume.draft.length() > 0 && session.draft().length() == 0) {
            session.setDraft(Resume.draft);
        }
        Updates.maybeCheck(this);
        Credits.maybeFetchBalance(this);
        String conv = Resume.conversation;
        if (conv.length() > 0 && !conv.equals(session.conversation()) && !settings.testMode) {
            openConversation(conv); // "Loading chat..." in the chat
        } else {
            showChat();
        }
    }

    /** Keeps where the app is (Resume): in the chat or not, the chat's id, the draft if chats are kept. */
    void remember(boolean inChat) {
        Resume.save(inChat, session.conversation(), settings.saveChat ? session.draft() : "");
    }

    /**
     * Setup complete: the chat, with a short note over it that goes by
     * itself; the chat's own screen says how to write.
     */
    void setupDone(String message) {
        display.setCurrent(chat);
        remember(true);
        chat.toast(message);
        Models.maybeFetch(this); // a fresh pairing has no model list yet
    }

    void setupFinished(String message) {
        info(message, settings.ready() ? (Displayable) chat : home);
    }

    void setupSkipped(String message) {
        info(message, home);
    }

    protected void pauseApp() {
        // a running request finishes on its own thread; nothing to stop
        if (session != null) {
            remember(Resume.inChat);
        }
    }

    protected void destroyApp(boolean unconditional) {
        // Settings are saved explicitly; here only where the app was left (Resume)
        if (session != null) {
            remember(Resume.inChat);
        }
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
        return "AIKON/" + attr("MIDlet-Version");
    }

    /** The home status asks for something (setup, an update, credits low or gone): shown in the accent colour. */
    boolean homeStatusUrgent() {
        if (settings.testMode) {
            return false;
        }
        if (!Net.isHttps(settings.url) || !settings.connectionVerified() || settings.token.length() < 16
                || updateVersion().length() > 0) {
            return true;
        }
        String bal = session.balance();
        return bal.length() > 0 && Text.tenths(bal) < lowCredits();
    }

    /** The home screen's status line (its footer). */
    String homeStatus() {
        if (settings.testMode) {
            return L.t("Test mode: fake replies");
        }
        if (!Net.isHttps(settings.url)) {
            return L.t("Setup: server address needed");
        }
        if (!settings.connectionVerified()) {
            return L.t("Setup: run the connection test");
        }
        if (settings.token.length() < 16) {
            return L.t("Setup: pair this phone");
        }
        String upd = updateVersion();
        String bal = session.balance();
        if (upd.length() > 0) {
            return bal.length() > 0 ? L.f("{0} credits · New version {1}", bal, upd)
                    : L.f("New version {0} · Update", upd);
        }
        if (bal.length() > 0) {
            int b = Text.tenths(bal);
            if (b <= 0) {
                return L.t("No credits left · add a code under Credits");
            }
            if (b < lowCredits()) {
                return L.f("Credits low · {0} left", bal);
            }
            return L.f("Ready · {0} credits", bal);
        }
        String left = session.remainingToday();
        return left.length() > 0 ? L.f("Ready · {0} left today", left)
                : L.t("Ready · new AI on an old Nokia");
    }

    /**
     * "Low" in tenths of a credit: about ten typical messages with the
     * chat's model (Models' cost; 2 credits a message when unknown).
     */
    private int lowCredits() {
        int cost = Text.tenths(Models.cost(session.modelId()));
        return 10 * (cost > 0 ? cost : 20);
    }

    /** Where to buy, as a line for the Credits screens; "" without a shop address. */
    String shopLine() {
        String shop = Updates.shop(settings.url);
        return shop.length() == 0 ? "" : L.t("Buy a credit code (on a computer or smartphone): ") + shop;
    }

    /** Second line of a home row; the Chat row shows the draft or the last message. */
    String homeHint(int row, String fallback) {
        if (row == Icons.CREDIT) {
            String bal = session.balance();
            String shop = Updates.shop(settings.url);
            return bal.length() > 0 ? bal + L.t(" credits") + (shop.length() > 0 ? " · " + shop : "")
                    : shop.length() > 0 ? shop : fallback;
        }
        if (row != 0) {
            return fallback;
        }
        String d = session.draft();
        if (d.length() > 0) {
            return L.t("Draft: ") + firstLine(d);
        }
        String last = session.lastUserText();
        return last.length() > 0 ? L.t("Last: ") + firstLine(last) : fallback;
    }

    private static String firstLine(String t) {
        int nl = t.indexOf('\n');
        return (nl < 0 ? t : t.substring(0, nl)).trim();
    }

    /** Re-applies theme, text size and full screen after a settings change. */
    void applyLook() {
        Theme.apply(settings);
        applyFullScreen();
        chat.repaint();
        home.repaint();
    }

    /**
     * Settings > Screen > Full screen: the menu and the chat (with reading
     * mode) draw over the phone's status bar. Their own top bar stays; the
     * softkeys are still the phone's Commands. The drawn lists (RowList)
     * follow it too; forms and text boxes cannot be full screen in MIDP.
     */
    private void applyFullScreen() {
        chat.setFullScreenMode(settings.fullScreen);
        home.setFullScreenMode(settings.fullScreen);
        RowList.fullScreen = settings.fullScreen;
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

    /**
     * A short buzz when a wait the user may have looked away from ends well
     * (pairing approved, a code accepted, a transcript or a photo ready);
     * follows Settings > "Vibrate on reply". Replies have replyFeedback().
     */
    void doneFeedback() {
        if (settings.vibrate) {
            display.vibrate(90);
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

    private static int camera = -1;

    /** The phone lets apps use the camera (JSR 135 VideoControl, Cam). */
    static synchronized boolean hasCamera() {
        if (camera < 0) {
            boolean ok = "true".equals(prop("supports.video.capture"));
            if (ok) {
                try {
                    Class.forName("javax.microedition.media.control.VideoControl");
                } catch (Throwable t) {
                    ok = false;
                }
            }
            camera = ok ? 1 : 0;
        }
        return camera == 1;
    }

    /** A photo can be taken or picked on this phone. */
    static boolean hasPhotoSource() {
        return hasCamera() || hasFiles();
    }

    /** Takes or picks a photo and uploads it; then the editor opens with it (photoAttached). */
    void showPhoto(boolean fromComposer) {
        if (!chatReady()) {
            return;
        }
        if (!hasPhotoSource()) {
            info(L.t("This phone does not let apps use the camera or files."), fromComposer ? (Displayable) composer : chat);
            return;
        }
        new Photo(this, fromComposer).start();
    }

    /** The photo is on the server: attach it to the next message and let the user write the question. */
    void photoAttached(String id, boolean fromComposer) {
        doneFeedback();
        session.setImage(id);
        String d = session.draft();
        if (d.trim().length() == 0) {
            d = L.t("What is in this photo?");
            session.setDraft(d);
        }
        showComposer(d);
    }

    /** Adding a photo was cancelled: back where it started. */
    void photoClosed(boolean fromComposer) {
        if (fromComposer) {
            showComposer(null);
        } else {
            showChat();
        }
    }

    /** Records a voice message; its text then opens in the editor (dictated). */
    void showDictation(boolean fromComposer) {
        if (!chatReady()) {
            return;
        }
        if (!hasRecording()) {
            info(L.t("This phone does not let apps record audio."), fromComposer ? (Displayable) composer : chat);
            return;
        }
        new Dictation(this, fromComposer).start();
    }

    /** The server's text for a voice message: added to the draft, shown in the editor to check and send. */
    void dictated(String text) {
        doneFeedback();
        String d = session.draft().trim();
        String t = d.length() > 0 ? d + " " + text : text;
        if (t.length() > ChatSession.MAX_MESSAGE) {
            t = t.substring(0, ChatSession.MAX_MESSAGE);
        }
        session.setDraft(t);
        showComposer(t, L.t("Check, then send"));
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


    // ------------------------------------------------------------ navigation

    void showMenu() {
        if (Theme.wantsDark(settings) != Theme.dark) {
            applyLook(); // automatic look: evening or morning came
        }
        display.setCurrent(home);
        remember(false);
        Models.maybeFetch(this);
        home.setUpdate(updateVersion().length() > 0);
        Updates.maybeCheck(this);
        Credits.maybeFetchBalance(this);
    }

    /** The newer version the server offers, or "". */
    String updateVersion() {
        return Updates.available(attr("MIDlet-Version"), settings.url);
    }

    /** From the update check (worker thread): the answer changed. */
    void updateChanged() {
        home.setUpdate(updateVersion().length() > 0);
    }

    /** From the balance request (worker thread). */
    void balanceChanged() {
        home.repaint();
    }

    /** The model list came (Models.maybeFetch): the chat and the menu name the model. */
    void modelsChanged() {
        home.repaint();
        chat.repaint();
    }

    /** "Update": what happens, then the phone's own installer. */
    void confirmUpdate() {
        String v = updateVersion();
        if (v.length() == 0) {
            return;
        }
        updateConfirm = TextPage.message(L.t("Update"), L.f("AIKON {0} will be downloaded. The phone asks to install it and AIKON closes; your settings and pairing are kept.", v));
        updateConfirm.addCommand(updateYesCmd);
        updateConfirm.addCommand(resetNoCmd);
        updateConfirm.setCommandListener(this);
        display.setCurrent(updateConfirm);
    }

    private void startUpdate() {
        String u = Updates.url();
        try {
            if (platformRequest(u)) {
                exit(); // this phone installs only after the MIDlet has ended
            } else {
                showMenu();
            }
        } catch (ConnectionNotFoundException e) {
            info(L.t("The phone could not open the link. Open this in "
                    + "the browser: ") + u, home);
        }
    }

    void showChat() {
        if (Theme.wantsDark(settings) != Theme.dark) {
            applyLook();
        }
        display.setCurrent(chat);
        remember(true);
        Models.maybeFetch(this);
    }

    /**
     * "New chat": with more than one model offered (as last fetched), the
     * model picker first; otherwise at once with the server's default. The
     * picker fetches the list when none is kept yet.
     */
    void startNewChat(Displayable back) {
        if (Models.count() > 1 || Models.count() == 0 && !settings.testMode) {
            showModels(Models.FOR_NEW, back);
            return;
        }
        String err = session.newChat(Models.startId());
        if (err != null) {
            info(err, back);
        } else {
            showChat();
        }
    }

    void showModels(int purpose, Displayable back) {
        new Models(this, purpose, back).show(display);
    }

    /** Opens the editor with the saved draft, or with `text` if given. */
    void showComposer(String text) {
        showComposer(text, null);
    }

    /** As above; title replaces "Message <model>" this time (a voice message to check). */
    private void showComposer(String text, String title) {
        if (composer == null) {
            composer = new TextBox(L.t("Message"), "", ChatSession.MAX_MESSAGE, TextField.ANY);
            composer.addCommand(sendCmd);
            if (hasRecording()) {
                composer.addCommand(dictateCmd);
            }
            composer.addCommand(composerBackCmd);
            composer.setCommandListener(this);
        }
        // "Add a photo" or, with one attached, "Remove the photo"
        boolean photo = session.hasImage();
        composer.removeCommand(photoCmd);
        composer.removeCommand(removePhotoCmd);
        if (photo) {
            composer.addCommand(removePhotoCmd);
        } else if (hasPhotoSource()) {
            composer.addCommand(photoCmd);
        }
        if (title == null) {
            title = photo ? L.t("Message with a photo")
                    : L.f("Message {0}", session.ai());
        }
        composer.setTitle(title);
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
            info(L.t("Server chats are not shown in test mode."), home);
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
            info(L.t("This phone does not let apps save files."), home);
            return;
        }
        if (savedList == null) {
            savedList = new SavedList(this);
        }
        savedList.show();
    }

    /** English texts in the language now in use (the language files). */
    private static String[] translated(String[] en) {
        String[] out = new String[en.length];
        for (int i = 0; i < en.length; i++) {
            out[i] = L.t(en[i]);
        }
        return out;
    }

    void showPrompts() {
        if (!chatReady()) {
            return;
        }
        if (prompts == null) {
            prompts = new RowList(L.t("Quick prompts"));
            String[] titles = translated(TITLES_EN);
            for (int i = 0; i < titles.length; i++) {
                String sub = promptTexts[i].trim();
                sub = sub.endsWith(":") ? sub.substring(0, sub.length() - 1) + "..." : sub;
                String head = titles[i].toLowerCase().substring(0, Math.min(8, titles[i].length()));
                // no second line when it only repeats the title
                prompts.add(titles[i], sub.toLowerCase().startsWith(head) ? null : sub, -1, 0);
            }
            prompts.addCommand(promptsBackCmd);
            prompts.setCommandListener(this);
        }
        display.setCurrent(prompts);
    }

    /** Every key of the chat and reading screens; "Geri" returns to `back`. */
    void showShortcuts(Displayable back) {
        if (shortcuts == null) {
            shortcuts = new TextPage(L.t("Shortcuts"));
            shortcuts.append(L.t("Chat"), L.t("Centre key or 5: write\n"
                    + "Up / down: one line\n"
                    + "2 / 8, left / right: one page\n"
                    + "1 / 3: select a message; centre key: shorten, translate, edit...\n"
                    + "* / #: top / end\n"
                    + "0: the rest of a reply\n"
                    + "7: reading mode\n"
                    + "9: text size\n"
                    + "After an error, centre key: retry\n"));
            shortcuts.append(L.t("Reading mode"), L.t("Shows one reply page by page, full width.\n"
                    + "Centre key, 8 or right: next page\n"
                    + "2 or left: previous page\n"
                    + "Up / down: one line\n"
                    + "1 / 3: previous / next reply\n"
                    + "* / #: top / end\n"
                    + "0: the rest of a reply\n"
                    + "9: text size\n"
                    + "7 or Close: back to the chat\n"));
            shortcuts.append(L.t("Main menu and lists"), L.t("1-9: opens that row directly (in lists the first nine rows; not in Settings)"));
            if (Keys.qwerty || back.getWidth() > back.getHeight()) { // S60 QWERTY phones only (E63: landscape)
                shortcuts.append(L.t("QWERTY keyboard"), L.t("Digits sit on letter keys: R T Y = 1 2 3, F G H = 4 5 6, V B N = 7 8 9, M = 0, U = *, J = #\n"
                        + "Enter: same as the centre key\n"
                        + "On the main menu, N only moves to Exit (does not quit)\n"));
            }
            shortcuts.append(null, L.t("Actions like shorten or translate never send by themselves: the editor opens with the text "
                            + "ready and you press Send."));
            shortcuts.append(null, L.t("Loading the rest of a reply is free: it comes from the server, the model is not asked again."));
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
            int[] rewording = { ACT_ASK, ACT_SHORTEN, ACT_SIMPLER, ACT_TO_TR, ACT_TO_EN, ACT_READ };
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
        actionList = new RowList(reply ? L.t("Reply") : L.t("Your message"));
        for (int i = 0; i < ids.length; i++) {
            actionList.add(actionLabel(ids[i]), null, actionIcon(ids[i]), 0);
        }
        actionList.addCommand(listBackCmd);
        actionList.setCommandListener(this);
        actionIds = ids;
        actionEntry = e;
        display.setCurrent(actionList);
    }

    private static int actionIcon(int id) {
        switch (id) {
        case ACT_READ:
            return Icons.BOOK;
        case ACT_SHORTEN:
            return Icons.SHORT;
        case ACT_SIMPLER:
            return Icons.BULB;
        case ACT_TO_TR:
        case ACT_TO_EN:
            return Icons.GLOBE;
        case ACT_ASK:
            return Icons.CHAT;
        case ACT_RESEND:
            return Icons.RESEND;
        case ACT_SAVE:
            return Icons.SAVED;
        case ACT_CALENDAR:
            return Icons.CALENDAR;
        default:
            return Icons.NEW_CHAT;
        }
    }

    private static String actionLabel(int id) {
        switch (id) {
        case ACT_READ:
            return L.t("Open in reading mode");
        case ACT_SHORTEN:
            return L.t("Make it shorter");
        case ACT_SIMPLER:
            return L.t("Explain it more simply");
        case ACT_TO_TR:
            return L.t("Translate to Turkish");
        case ACT_TO_EN:
            return L.t("Translate to English");
        case ACT_ASK:
            return L.t("Ask about this");
        case ACT_RESEND:
            return L.t("Edit and send again");
        case ACT_SAVE:
            return L.t("Save to phone (.txt)");
        case ACT_CALENDAR:
            return L.t("Add to calendar / to-do");
        default:
            return L.t("Open in editor");
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
            showComposer(L.t("Make this reply shorter, 3 sentences at most: ") + q);
            break;
        case ACT_SIMPLER:
            showComposer(L.t("Explain this reply more simply: ") + q);
            break;
        case ACT_TO_TR:
            showComposer(L.t("Translate all of this reply to Turkish: ") + q);
            break;
        case ACT_TO_EN:
            showComposer(L.t("Translate all of this reply to English: ") + q);
            break;
        case ACT_ASK:
            showComposer(L.t("About this part: ") + q + "\n");
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
        viewer = new TextBox(L.t("Text"), "", Math.max(1, Math.min(e.text.length(), 8000)), TextField.ANY);
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
        TextPage.notice(display, "AIKON", text, next);
    }

    /** The explanation behind a one-line note, titled with the note itself. */
    void showNote(String title, String text, Displayable next) {
        TextPage.notice(display, title, text, next);
    }

    /** True if chat can be used; otherwise explains what is missing. */
    private boolean chatReady() {
        if (settings.testMode) {
            return true;
        }
        String missing = null;
        if (!Net.isHttps(settings.url)) {
            missing = L.t("First enter the server address (https://...) in Settings.");
        } else if (!settings.connectionVerified()) {
            missing = L.t("Run the 'Connection test' first. Nothing is sent before the secure connection is verified.");
        } else if (settings.token.length() < 16) {
            missing = L.t("Pair this phone first: Settings > Options > 'Pair this phone'.");
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
            } else if (c == photoCmd) {
                session.setDraft(composer.getString());
                showPhoto(true);
            } else if (c == removePhotoCmd) {
                session.clearImage();
                session.setDraft(composer.getString());
                showComposer(null);
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
        } else if (c == helpCmd && d != null) {
            Help.show(display, d.getTitle(), helpFor(d), d);
        } else if (d == languageList && d != null) {
            if (c == List.SELECT_COMMAND) {
                int lg = languageList.getSelectedIndex();
                if (lg >= 0 && lg != settings.lang) {
                    settings.lang = lg;
                    String err = settings.save();
                    rebuildUi();
                    showSettings(); // its rows and commands in the new language
                    fillSettings(S_LANG);
                    if (err != null) {
                        info(err, settingsForm);
                    }
                    return;
                }
            }
            display.setCurrent(settingsForm);
        } else if (d == editBox) {
            if (c == editOkCmd) {
                saveEdit();
            } else {
                display.setCurrent(settingsForm);
            }
        } else if (d == settingsForm) {
            if (c == changeCmd) {
                int i = settingsForm.getSelectedItem();
                if (i >= 0 && i < settingRows.length) {
                    changeSetting(settingRows[i]);
                }
            } else if (c == pairCmd) {
                startPairing();
            } else if (c == creditsCmd) {
                new Credits(this, null, settingsForm).showBalance();
            } else if (c == wizardCmd) {
                new Setup(this).start();
            } else if (c == dataCmd) {
                showDataUsage();
            } else if (c == resetSetupCmd) {
                resetConfirm = TextPage.message(L.t("Reset setup"), L.t("Delete the server address, pairing and your notes from this phone and from the backup "
                                + "file? The setup wizard opens next."));
                resetConfirm.addCommand(resetYesCmd);
                resetConfirm.addCommand(resetNoCmd);
                resetConfirm.setCommandListener(this);
                display.setCurrent(resetConfirm);
            } else {
                showMenu();
            }
        } else if (d == updateConfirm) {
            if (c == updateYesCmd) {
                startUpdate();
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
            if (chatReady()) {
                startNewChat(home);
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
        case Icons.CREDIT:
            new Credits(this, null, home).showBalance();
            break;
        default:
            break;
        }
    }

    // ------------------------------------------------------------ settings

    /**
     * Settings as a drawn list: each row names a setting with its value
     * under it, or a switch; the centre key changes it and it is saved at
     * once. Notes, server address and access code open a text box.
     */
    private void showSettings() {
        settingsForm = new RowList(L.t("Settings"));
        settingsForm.setSelectCommand(changeCmd);
        settingsForm.setNumbers(false); // a digit would change a setting at once
        settingsForm.addCommand(helpCmd);
        if (settings.credits && settings.token.length() >= 16) {
            settingsForm.addCommand(creditsCmd);
        }
        settingsForm.addCommand(pairCmd);
        settingsForm.addCommand(dataCmd);
        settingsForm.addCommand(wizardCmd);
        settingsForm.addCommand(resetSetupCmd);
        settingsForm.addCommand(formBackCmd);
        settingsForm.setCommandListener(this);
        fillSettings(0);
        display.setCurrent(settingsForm);
    }

    /** (Re)builds the rows, keeping the selection on the row that was changed. */
    private void fillSettings(int keep) {
        RowList l = settingsForm;
        l.deleteAll();
        int[] ids = new int[16];
        int n = 0;
        int sel = -1;
        String[] sizes = { L.t("Small"), L.t("Medium"), L.t("Large") };
        String[] themes = { L.t("Light"), L.t("Dark"), L.t("Automatic: dark 19-07") };
        String notes = settings.instructions.length() > 0 ? settings.instructions : L.t("none");
        for (int k = 0; k < 14; k++) {
            if (k == S_SIZE) {
                l.section(L.t("Look"));
            } else if (k == S_WEB) {
                l.section(L.t("Replies"));
            } else if (k == S_SOUND) {
                l.section(L.t("Sound and screen"));
            } else if (k == S_LANG) {
                l.section(L.t("Language and server"));
            }
            int row;
            switch (k) {
            case S_SIZE:
                row = l.add(L.t("Text size"), sizes[Math.max(0, Math.min(2, settings.fontSize))], -1, 0);
                break;
            case S_THEME:
                row = l.add(L.t("Look"), themes[Math.max(0, Math.min(2, settings.theme))], -1, 0);
                break;
            case S_WEB:
                row = l.add(L.t("May search the web"), L.t("news, weather..."),
                        -1, sw(settings.webSearch));
                break;
            case S_KEEP:
                row = l.add(L.t("Keep last chat on phone"), null, -1, sw(settings.saveChat));
                break;
            case S_NOTES:
                row = l.add(L.t("Your notes for the AI"), notes, -1, 0);
                break;
            case S_SOUND:
                row = l.add(L.t("Jingle and reply sound"), null, -1, sw(settings.sound));
                break;
            case S_VIBRATE:
                row = l.add(L.t("Vibrate on reply"), null, -1, sw(settings.vibrate));
                break;
            case S_FULL:
                row = l.add(L.t("Full screen"), null, -1, sw(settings.fullScreen));
                break;
            case S_LIGHT:
                row = l.add(L.t("Light up for a reply"), L.t("if the screen went dark"),
                        -1, sw(settings.lightReply));
                break;
            case S_LANG:
                row = l.add(L.t("Language"), languageName(settings.lang), -1, 0);
                break;
            case S_URL:
                row = l.add(L.t("Server address"),
                        settings.url.length() > 0 ? settings.url : L.t("(not set)"), -1, 0);
                break;
            case S_TOKEN:
                row = l.add(L.t("Access code"), settings.token.length() > 0 ? "********" : L.t("none"), -1, 0);
                break;
            case S_CONN:
                row = l.add(L.t("Connection test"),
                        settings.connectionVerified() ? L.t("passed") : L.t("not passed yet"), -1, 0);
                break;
            default:
                row = l.add(L.t("Test mode"), L.t("fake replies, no network"),
                        -1, sw(settings.testMode));
                break;
            }
            ids[n++] = k;
            if (k == keep) {
                sel = row;
            }
        }
        int[] out = new int[n];
        System.arraycopy(ids, 0, out, 0, n);
        settingRows = out;
        if (sel >= 0) {
            l.setSelectedIndex(sel, true);
        }
    }

    /** The name of a Settings.lang value, each language in its own words. */
    private static String languageName(int lang) {
        if (lang == L.TURKISH) {
            return "Türkçe";
        }
        if (lang == L.ENGLISH) {
            return "English";
        }
        if (lang >= L.FIRST && lang < L.FIRST + L.LANGS.length) {
            return L.NAMES[lang - L.FIRST];
        }
        return L.t("Same as phone");
    }

    private RowList languageList;

    /** Settings > Language: every language in its own words; the centre key picks one at once. */
    private void showLanguages() {
        languageList = new RowList(L.t("Language"));
        int n = L.FIRST + L.LANGS.length;
        for (int i = 0; i < n; i++) {
            languageList.add(languageName(i), null, Icons.GLOBE, i == settings.lang ? RowList.CHECK : 0);
        }
        languageList.setSelectedIndex(Math.max(0, Math.min(n - 1, settings.lang)), true);
        languageList.addCommand(listBackCmd);
        languageList.setCommandListener(this);
        display.setCurrent(languageList);
    }

    private static int sw(boolean on) {
        return RowList.SWITCH | (on ? RowList.ON : 0);
    }

    /** Centre key on a settings row: cycles or toggles and saves, or opens what edits it. */
    private void changeSetting(int k) {
        switch (k) {
        case S_SIZE:
            settings.fontSize = (settings.fontSize + 1) % 3;
            break;
        case S_THEME:
            settings.theme = (settings.theme + 1) % 3;
            break;
        case S_WEB:
            settings.webSearch = !settings.webSearch;
            break;
        case S_KEEP:
            settings.saveChat = !settings.saveChat;
            if (settings.saveChat) {
                session.markUnsaved();
                session.persist();
            } else {
                ChatStore.delete();
                Resume.save(Resume.inChat, Resume.conversation, ""); // no draft kept either
            }
            break;
        case S_SOUND:
            settings.sound = !settings.sound;
            break;
        case S_VIBRATE:
            settings.vibrate = !settings.vibrate;
            break;
        case S_FULL:
            settings.fullScreen = !settings.fullScreen;
            break;
        case S_LIGHT:
            settings.lightReply = !settings.lightReply;
            break;
        case S_LANG:
            showLanguages();
            return;
        case S_TEST:
            settings.testMode = !settings.testMode;
            break;
        case S_CONN:
            if (connTest == null) {
                connTest = new ConnTest(this, null);
            }
            connTest.show(display);
            return;
        default:
            edit(k);
            return;
        }
        String err = settings.save();
        applyLook();
        fillSettings(k);
        if (err != null) {
            info(err, settingsForm);
        }
    }

    /** A text box for the notes, the server address or the access code. */
    private void edit(int k) {
        editing = k;
        if (k == S_NOTES) {
            editBox = new TextBox(L.t("Your notes for the AI"), settings.instructions,
                    Settings.MAX_INSTRUCTIONS, TextField.ANY);
        } else if (k == S_URL) {
            editBox = new TextBox(L.t("Server address (https://...)"), settings.url,
                    200, TextField.URL);
        } else {
            editBox = new TextBox(L.t("Access code"), settings.token, 64,
                    TextField.ANY | TextField.SENSITIVE | TextField.NON_PREDICTIVE);
        }
        editBox.addCommand(editOkCmd);
        editBox.addCommand(formBackCmd);
        editBox.setCommandListener(this);
        display.setCurrent(editBox);
    }

    private void saveEdit() {
        String v = editBox.getString().trim();
        if (editing == S_URL) {
            while (v.endsWith("/")) {
                v = v.substring(0, v.length() - 1);
            }
            if (v.length() > 0 && !Net.isHttps(v)) {
                info(L.t("The address must start with https://. HTTP is not supported."), editBox);
                return;
            }
            if (!v.equals(settings.url)) {
                settings.verifiedUrl = ""; // new address: connection test again
            }
            settings.url = v;
        } else if (editing == S_TOKEN) {
            if (v.length() > 0 && !validToken(v)) {
                info(L.t("The access code must be 16-64 letters/digits."), editBox);
                return;
            }
            settings.token = v;
        } else {
            settings.instructions = v;
        }
        String err = settings.save();
        fillSettings(editing);
        if (err != null) {
            info(err, settingsForm);
        } else {
            display.setCurrent(settingsForm);
        }
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
        Models.clear();
        Backup.forget(settings);
        settings.save();
        new Setup(this).start();
    }

    /** Mobile data used by the app (DataUsage); "Sıfırla" starts the totals again. */
    private void showDataUsage() {
        dataForm = new TextPage(L.t("Data usage"));
        long[] t = DataUsage.total();
        dataForm.append(L.t("Today"), DataUsage.describe(DataUsage.today()));
        dataForm.append(L.t("Total since ") + Text.local(t[3], false), DataUsage.describe(t));
        dataForm.addCommand(formBackCmd);
        dataForm.addCommand(helpCmd);
        dataForm.addCommand(resetCmd);
        dataForm.setCommandListener(this);
        display.setCurrent(dataForm);
    }

    private void startPairing() {
        if (!Net.isHttps(settings.url)) {
            info(L.t("Save the server address (https://...) first."),
                    settingsForm);
        } else if (!settings.connectionVerified()) {
            info(L.t("Run the 'Connection test' first. Pairing only runs over a verified connection."), home);
        } else {
            Credits.pair(this, null, display, settingsForm != null ? (Displayable) settingsForm : home);
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

    /** The "Info" page behind Settings, Data usage and About. */
    private String helpFor(Displayable d) {
        if (d == dataForm) {
            return L.t("Estimates: messages are counted exactly, HTTP headers roughly.\n\nThe encrypted connection "
                    + "set-up is not counted, so your operator counts more.\n\nTest mode uses no data.");
        }
        if (d == about) {
            return L.t("The AI does not run on the phone: messages travel over an encrypted link to the server and on to "
                    + "the chosen model's provider (Anthropic, OpenAI, Google, xAI). Not an official app of these "
                    + "providers or of Nokia."
                    + "\n\nThe server deletes chats 30 days after the last message; pinned ones stay until unpinned. "
                    + "The phone keeps the last chat only if 'Keep last chat on phone' is on; saved replies and "
                    + "calendar entries stay on the phone only."
                    + "\n\nWeb search runs on the server with the model provider's search tool, not the phone's "
                    + "browser.");
        }
        return L.t("Your notes for the AI: say briefly who you are and how you like answers, e.g. \"I'm Emir, I live "
                + "in Istanbul, keep it short.\" Sent to the server with every message."
                + "\n\nInstead of typing the access code, use Options > 'Pair this phone'. The code is stored on this "
                + "phone and (so a reinstall needs no setup) in the setup backup on the memory card, and sent only to "
                + "the https:// address."
                + "\n\nThe connection test must pass again whenever the address changes; nothing is sent before."
                + "\n\nTest mode gives fake replies and uses no network.");
    }

    private void showAbout() {
        about = new TextPage(L.t("About"));
        about.append(attr("MIDlet-Name"), L.t("Version ") + attr("MIDlet-Version") + L.t(" (build ")
                        + attr("ClaudeS40-Build") + ")");
        if (updateVersion().length() > 0) {
            about.append(L.t("New version"), updateVersion()
                    + L.t(" · Update on the home screen"));
        }
        about.append(null, L.t("AI chat for Nokia S40 and S60 phones."));
        about.append(null, L.t("Made by: ") + AUTHOR);
        about.append(null, "github.com/emir/AIKON");
        about.append(L.t("Platform"), prop("microedition.platform"));
        about.append(L.t("Voice recording"), (hasRecording() ? L.t("yes")
                : L.t("no")) + " (" + prop("audio.encodings") + ")");
        about.append(L.t("Camera"), (hasCamera() ? L.t("yes") : L.t("no"))
                + " (" + prop("video.snapshot.encodings") + ")");
        about.addCommand(helpCmd);
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

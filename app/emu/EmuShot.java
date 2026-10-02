import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Vector;

import javax.imageio.ImageIO;
import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.CommandListener;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.TextBox;

import org.recompile.mobile.Mobile;
import org.recompile.mobile.MobilePlatform;

/**
 * Host-only emulator run for AIKON (FreeJ2ME, headless). Not part of
 * the MIDlet. No network: the app runs in its own "Test modu" (local fake
 * replies, clearly labelled on screen).
 *
 * Output: shots/NN_name.png for each screen, and shots/splash/fNN.png
 * frames of the start-up animation (tools/promo.py turns them into a GIF).
 *
 * FreeJ2ME reports no microedition.locale, so the app starts in English;
 * LANG=tr switches to Turkish through Settings + rebuildUi (harness).
 *
 * FreeJ2ME gaps worked around here (harness only): Canvas softkeys go
 * through Displayable.doCommand(); ChoiceGroup is a stub, so test mode,
 * theme and text size are set on Settings by reflection; Alerts/TextBox are
 * not drawn and Forms only roughly. One reply with a further part on the
 * server cannot come from test mode, so a labelled test-mode entry with
 * "more" is added by reflection to show the "0 · the rest" row; 0 is never
 * pressed on it. Emulator success is NOT device compatibility.
 *
 * Usage: java -cp FREEJ2ME_CLASSES:. EmuShot JAR OUTDIR WIDTH HEIGHT [en|tr]
 */
public class EmuShot {

    static File out;
    static int shot;
    static Object midlet;
    static boolean tr;

    /** A long message with paragraphs and lists; the fake reply quotes it back. */
    static final String LIST_EN = "Plan for Saturday, short:\n\n"
            + "- Buy bread, olives and white cheese at the market before ten\n"
            + "- Call the plumber about the kitchen tap\n"
            + "  - ask for a price first\n\n"
            + "1. Clean the balcony\n"
            + "2. Take the old Nokia chargers to the recycling point\n"
            + "3. Dinner with the neighbours at eight, bring dessert\n\n"
            + "If it rains, move the balcony to Sunday and read a book instead. "
            + "A long line to check wrapping: the quick brown fox jumps over the lazy dog again and again.";

    static final String LIST_TR = "Cumartesi planı, kısaca:\n\n"
            + "- Saat ondan önce pazardan ekmek, zeytin ve beyaz peynir al\n"
            + "- Mutfak musluğu için tesisatçıyı ara\n"
            + "  - önce fiyat sor\n\n"
            + "1. Balkonu temizle\n"
            + "2. Eski Nokia şarj aletlerini geri dönüşüme götür\n"
            + "3. Sekizde komşularla akşam yemeği, tatlı getir\n\n"
            + "Yağmur yağarsa balkonu pazara bırak, onun yerine kitap oku. "
            + "Satır kaydırmayı denemek için uzun bir satır: çığ gibi büyüyen ölçüsüz şişkin öğüt İstanbul'da.";

    public static void main(String[] args) throws Exception {
        try {
            run(args);
        } catch (Throwable t) {
            t.printStackTrace(System.out);
            System.exit(4); // the MIDlet's threads would keep the JVM alive
        }
    }

    static void run(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        out = new File(args[1]);
        new File(out, "splash").mkdirs();
        int w = Integer.parseInt(args[2]);
        int h = Integer.parseInt(args[3]);
        tr = args.length > 4 && "tr".equals(args[4]);

        Mobile.setPlatform(new MobilePlatform(w, h));
        Mobile.getPlatform().setPainter(new Runnable() { public void run() { } });
        if (!Mobile.getPlatform().loadJar(new File(args[0]).toURI().toString())) {
            System.out.println("EMU: loadJar failed");
            System.exit(2);
        }
        Mobile.getPlatform().runJar();
        Thread.sleep(100);
        midlet = field(Mobile.getPlatform().loader, "mainInst");
        if (tr) {
            setting("lang", new Integer(1));
            call("rebuildUi");
        }

        // splash animation frames (runs ~2.4 s by itself), then the setup wizard (fresh install)
        for (int i = 0; i < 16; i++) {
            Thread.sleep(150);
            ImageIO.write(Mobile.getPlatform().getLCD(), "png", new File(out, String.format("splash/f%02d.png", i)));
        }
        Thread.sleep(1200);
        save("setup_1_language");
        select(tr ? 1 : 2);                   // centre key on the language: applies it, goes on
        save("setup_2_server");
        // set the language again for the rest of the run (harness)
        setting("lang", new Integer(tr ? 1 : 2));
        call("rebuildUi");
        setting("setupDone", Boolean.TRUE);
        call("showMenu");
        save("home");

        setting("testMode", Boolean.TRUE);

        key(Mobile.NOKIA_DOWN);
        key(Mobile.NOKIA_DOWN);
        key(Mobile.NOKIA_DOWN);
        save("home_selection");

        key(Mobile.KEY_NUM1);                 // Sohbet
        save("chat_empty");

        command(t("Quick prompts", "Hızlı sorular"));
        save("prompts");
        select(4);                            // "Translate to English" -> editor
        type(t("Translate to English: Bu telefon 2007'den kalma ama hâlâ çalışıyor.",
                "İngilizceye çevir: Bu telefon 2007'den kalma ama hâlâ çalışıyor."));
        command(t("Send", "Gönder"));
        Thread.sleep(700);
        BufferedImage typing = Mobile.getPlatform().getLCD();
        ImageIO.write(typing, "png", new File(out, String.format("%02d_%s.png", ++shot, "chat_typing")));
        Thread.sleep(2000);
        save("chat_reply1");

        command(t("Write", "Yaz"));
        type(tr ? LIST_TR : LIST_EN);
        command(t("Send", "Gönder"));
        Thread.sleep(2200);
        save("chat_reply_lists");
        key(Mobile.NOKIA_DOWN);
        key(Mobile.NOKIA_DOWN);
        key(Mobile.NOKIA_DOWN);
        save("chat_reply_lists_down");

        key(Mobile.KEY_NUM7);                 // reading mode on the reply in view
        save("reading_p1");
        key(Mobile.NOKIA_SOFT3);              // centre key: next page
        save("reading_p2");
        key(Mobile.KEY_NUM9);                 // text size: large (toast)
        Thread.sleep(200);
        BufferedImage large = Mobile.getPlatform().getLCD();
        ImageIO.write(large, "png", new File(out, String.format("%02d_%s.png", ++shot, "reading_large_toast")));
        key(Mobile.KEY_NUM9);                 // small
        key(Mobile.KEY_NUM9);                 // medium again
        Thread.sleep(1800);
        key(Mobile.KEY_NUM7);                 // back to the chat at the same place
        save("chat_after_reading");

        key(Mobile.KEY_NUM1);                 // select the message above
        key(Mobile.KEY_NUM3);                 // and back down to the reply
        save("chat_selected");
        key(Mobile.NOKIA_SOFT3);              // centre key: actions
        save("actions");
        select(1);                            // "Make it shorter" -> editor, not sent
        command(t("Back", "Geri"));           // keeps it as a draft
        command(t("Menu", "Menü"));
        save("home_draft_hint");
        key(Mobile.KEY_NUM1);

        addRetryError();
        key(Mobile.KEY_POUND);
        save("chat_retry_row");
        clearRetry();

        addMoreEntry();
        key(Mobile.KEY_POUND);
        save("chat_more_row");
        key(Mobile.KEY_NUM7);
        key(Mobile.KEY_POUND);
        save("reading_end_more");
        command(t("Close", "Kapat"));

        command(t("Shortcuts", "Kısayollar"));
        save("shortcuts");
        command(t("Back", "Geri"));

        // a test-mode reply with a calendar entry line, shown in readable form.
        // FreeJ2ME has the PIM API classes but reports no version: the harness
        // sets microedition.pim.version so the calendar actions show. Save is
        // never pressed; nothing is written anywhere.
        System.setProperty("microedition.pim.version", "1.0");
        System.out.println("EMU: microedition.pim.version = 1.0 (harness, screenshots only)");
        command(t("Write", "Yaz"));
        type(t("Add to my calendar: dentist tomorrow at 15:00", "Takvimime ekle: yarın 15:00 dişçi"));
        command(t("Send", "Gönder"));
        Thread.sleep(1700);
        save("chat_calendar_line");         // reply selected + "Centre key: add to calendar"
        key(Mobile.NOKIA_SOFT3);              // centre key: actions, "Add to calendar" first
        save("actions_calendar");
        select(0);                            // prefilled form; never saved
        save("calendar_form");
        command(t("Cancel", "Vazgeç"));

        // models (server 0.7.0): test mode cannot fetch /v1/models, so the
        // list it would return is put in by reflection (harness only)
        Class models = Class.forName("io.github.emir.claudes40.Models", true, midlet.getClass().getClassLoader());
        setStatic(models, "ids", new String[] { "claude-opus-5-5", "claude-sonnet-5-5", "gpt-6.1-sol", "gpt-6-luna",
            "gemini-3.8-flash", "grok-y" });
        setStatic(models, "names", new String[] { "Claude Opus 5.5", "Claude Sonnet 5.5", "GPT-6.1 Sol", "GPT-6 Luna",
            "Gemini 3.8 Flash", "Grok" });
        setStatic(models, "providers", new String[] { "Claude", "Claude", "OpenAI", "OpenAI", "Gemini", "Grok" });
        setStatic(models, "defaultId", "claude-opus-5-5");
        setStatic(models, "loaded", Boolean.TRUE);
        Method newChat = midlet.getClass().getDeclaredMethod("startNewChat", Displayable.class);
        newChat.setAccessible(true);
        newChat.invoke(midlet, current());
        settle();
        save("models_providers");             // step 1: Claude (default), OpenAI, Gemini, Grok
        select(1);                            // OpenAI
        save("models_openai");                // step 2: GPT-6.1 Sol, GPT-6 Luna
        command(t("Back", "Geri"));           // back to the providers
        select(3);                            // Grok
        select(0);                            // its only model
        save("chat_new_grok");
        command(t("Write", "Yaz"));
        type(t("Which phone is this?", "Bu hangi telefon?"));
        command(t("Send", "Gönder"));
        Thread.sleep(300);
        save("chat_grok_typing");             // "Grok is typing"
        Thread.sleep(1700);
        save("chat_grok_reply");              // test-mode reply naming Grok
        command(t("Model", "Model"));
        save("models_switch");                // Grok marked "(now)"
        command(t("Back", "Geri"));

        call("showDataUsage");                // Settings > Options > Data usage (test mode: no data)
        save("data_usage");
        command(t("Back", "Geri"));
        call("showChat");

        // dark theme, large text
        setting("theme", new Integer(1));
        setting("fontSize", new Integer(2));
        call("applyLook");
        ((Canvas) current()).repaint();
        save("chat_dark_large");
        key(Mobile.KEY_NUM7);
        save("reading_dark_large");
        key(Mobile.KEY_NUM7);

        command(t("Menu", "Menü"));
        save("home_dark");

        setting("fontSize", new Integer(1));
        call("applyLook");
        key(Mobile.KEY_NUM8);                 // About
        save("about");
        command(t("Back", "Geri"));

        key(Mobile.KEY_NUM9);                 // Exit
        settle();
        System.out.println("EMU: exit did not terminate the MIDlet");
        System.exit(3);
    }

    static String t(String en, String turkish) {
        return tr ? turkish : en;
    }

    /** Harness only: an error note after which "Retry" is offered (never pressed here). */
    static void addRetryError() throws Exception {
        Object session = field(midlet, "session");
        Class entry = Class.forName("io.github.emir.claudes40.ChatSession$Entry", true, session.getClass().getClassLoader());
        Constructor c = entry.getDeclaredConstructor(new Class[] { int.class, String.class, String.class });
        c.setAccessible(true);
        Object e = c.newInstance(new Object[] { new Integer(4), t("[Harness] Could not connect", "[Test düzeneği] Bağlantı yok"),
                t("Emulator, no network.", "Emülatör, ağ yok.") });
        synchronized (session) {
            ((Vector) field(session, "entries")).addElement(e);
            setField(session, "pendingId", "emu-retry");
            setField(session, "canRetry", Boolean.TRUE);
            bump(session);
        }
        System.out.println("EMU: added an error with Retry (harness)");
        ((Canvas) current()).repaint();
        settle();
    }

    static void clearRetry() throws Exception {
        Object session = field(midlet, "session");
        synchronized (session) {
            setField(session, "pendingId", null);
            setField(session, "canRetry", Boolean.FALSE);
            bump(session);
        }
    }

    static void setField(Object o, String name, Object value) throws Exception {
        Field f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(o, value);
    }

    static void bump(Object session) throws Exception {
        Field v = session.getClass().getDeclaredField("version");
        v.setAccessible(true);
        v.setInt(session, v.getInt(session) + 1);
    }

    /** Harness only: a test-mode reply that says a further part exists (never fetched here). */
    static void addMoreEntry() throws Exception {
        Object session = field(midlet, "session");
        Class entry = Class.forName("io.github.emir.claudes40.ChatSession$Entry", true, session.getClass().getClassLoader());
        Constructor c = entry.getDeclaredConstructor(new Class[] { int.class, String.class, boolean.class, long.class,
            int.class, String.class, String.class, String.class });
        c.setAccessible(true);
        String text = t("[Test mode] Harness entry: pretend this is the first part of a long reply. ",
                "[Test modu] Test düzeneği kaydı: bunu uzun bir yanıtın ilk parçası say. ");
        Object e = c.newInstance(new Object[] { new Integer(2), text + text + text, Boolean.FALSE,
            new Long(System.currentTimeMillis()), new Integer(1), "emu-request", "2000", "" });
        Vector entries = (Vector) field(session, "entries");
        synchronized (session) {
            entries.addElement(e);
            Field v = session.getClass().getDeclaredField("version");
            v.setAccessible(true);
            v.setInt(session, v.getInt(session) + 1);
        }
        System.out.println("EMU: added a test-mode entry with 'more' (harness)");
        ((Canvas) current()).repaint();
        settle();
    }

    static void setStatic(Class c, String name, Object value) throws Exception {
        Field f = c.getDeclaredField(name);
        f.setAccessible(true);
        f.set(null, value);
        System.out.println("EMU: " + c.getName() + "." + name + " set (harness)");
    }

    static Object field(Object o, String name) throws Exception {
        Field f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(o);
    }

    static void setting(String name, Object value) throws Exception {
        Object s = field(midlet, "settings");
        Field f = s.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(s, value);
        System.out.println("EMU: Settings." + name + " = " + value + " (harness)");
    }

    static void call(String name) throws Exception {
        Method m = midlet.getClass().getDeclaredMethod(name);
        m.setAccessible(true);
        m.invoke(midlet);
        settle();
    }

    static Displayable current() {
        return Mobile.getDisplay().getCurrent();
    }

    static void settle() throws InterruptedException {
        Thread.sleep(500);
    }

    static void key(int code) throws InterruptedException {
        Mobile.getPlatform().keyPressed(code);
        Thread.sleep(100);
        Mobile.getPlatform().keyReleased(code);
        Thread.sleep(250);
    }

    static void type(String s) throws InterruptedException {
        ((TextBox) current()).setString(s);
        settle();
    }

    static void select(int index) throws Exception {
        javax.microedition.lcdui.List l = (javax.microedition.lcdui.List) current();
        l.setSelectedIndex(index, true);
        Field f = Displayable.class.getDeclaredField("commandlistener");
        f.setAccessible(true);
        ((CommandListener) f.get(l)).commandAction(javax.microedition.lcdui.List.SELECT_COMMAND, l);
        settle();
    }

    static void command(String label) throws Exception {
        Displayable d = current();
        List<Command> cmds = d.getCommands();
        for (int i = 0; i < cmds.size(); i++) {
            if (label.equals(cmds.get(i).getLabel())) {
                Method m = Displayable.class.getDeclaredMethod("doCommand", int.class);
                m.setAccessible(true);
                System.out.println("EMU: command " + label);
                m.invoke(d, i);
                settle();
                return;
            }
        }
        StringBuffer have = new StringBuffer();
        for (int i = 0; i < cmds.size(); i++) {
            have.append(" [").append(cmds.get(i).getLabel()).append(']');
        }
        throw new IllegalStateException("command not found: " + label + " on " + d.getClass().getName() + ", has" + have);
    }

    static void save(String name) throws Exception {
        settle();
        BufferedImage lcd = Mobile.getPlatform().getLCD();
        File f = new File(out, String.format("%02d_%s.png", ++shot, name));
        ImageIO.write(lcd, "png", f);
        System.out.println("EMU: saved " + f.getName() + " (" + current().getClass().getSimpleName() + ")");
    }
}

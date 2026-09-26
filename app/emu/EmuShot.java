import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import javax.imageio.ImageIO;
import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.CommandListener;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.TextBox;

import org.recompile.mobile.Mobile;
import org.recompile.mobile.MobilePlatform;

/**
 * Host-only emulator run for Claude S40 (FreeJ2ME, headless). Not part of
 * the MIDlet. No network: the app runs in its own "Test modu" (local fake
 * replies, clearly labelled on screen).
 *
 * Output: shots/NN_name.png for each screen, and shots/splash/fNN.png
 * frames of the start-up animation (tools/promo.py turns them into a GIF).
 *
 * FreeJ2ME reports no microedition.locale, so the app starts in English.
 *
 * FreeJ2ME gaps worked around here (harness only): Canvas softkeys go
 * through Displayable.doCommand(); ChoiceGroup is a stub, so test mode,
 * theme and text size are set on Settings by reflection; Alerts/TextBox are
 * not drawn. Emulator success is NOT device compatibility.
 *
 * Usage: java -cp FREEJ2ME_CLASSES:. EmuShot JAR OUTDIR WIDTH HEIGHT
 */
public class EmuShot {

    static File out;
    static int shot;
    static Object midlet;

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        out = new File(args[1]);
        new File(out, "splash").mkdirs();
        int w = Integer.parseInt(args[2]);
        int h = Integer.parseInt(args[3]);

        Mobile.setPlatform(new MobilePlatform(w, h));
        Mobile.getPlatform().setPainter(new Runnable() { public void run() { } });
        if (!Mobile.getPlatform().loadJar(new File(args[0]).toURI().toString())) {
            System.out.println("EMU: loadJar failed");
            System.exit(2);
        }
        Mobile.getPlatform().runJar();

        // splash animation frames (runs ~2.4 s by itself)
        for (int i = 0; i < 16; i++) {
            Thread.sleep(150);
            ImageIO.write(Mobile.getPlatform().getLCD(), "png", new File(out, String.format("splash/f%02d.png", i)));
        }
        Thread.sleep(1200);
        save("home");

        midlet = field(Mobile.getPlatform().loader, "mainInst");
        setting("testMode", Boolean.TRUE);

        key(Mobile.NOKIA_DOWN);
        key(Mobile.NOKIA_DOWN);
        key(Mobile.NOKIA_DOWN);
        save("home_selection");

        key(Mobile.KEY_NUM1);                 // Sohbet
        save("chat_empty");

        command("Quick prompts");
        save("prompts");
        select(0);                            // "Translate to English" -> editor
        type("Translate to English: Bu telefon 2007'den kalma ama hâlâ çalışıyor.");
        command("Send");
        Thread.sleep(200);
        BufferedImage typing = Mobile.getPlatform().getLCD();
        ImageIO.write(typing, "png", new File(out, String.format("%02d_%s.png", ++shot, "chat_typing")));
        Thread.sleep(2000);
        save("chat_reply1");

        command("Write");
        type("Make it shorter.");
        command("Send");
        Thread.sleep(2200);
        save("chat_reply2");

        key(Mobile.NOKIA_UP);
        key(Mobile.NOKIA_UP);
        save("chat_scrolled");

        // dark theme, large text
        setting("theme", new Integer(1));
        setting("fontSize", new Integer(2));
        applyTheme();
        ((javax.microedition.lcdui.Canvas) current()).repaint();
        save("chat_dark_large");

        command("Menu");
        save("home_dark");

        setting("fontSize", new Integer(1));
        applyTheme();
        key(Mobile.KEY_NUM6);                 // About
        save("about");
        command("Back");

        key(Mobile.KEY_NUM7);                 // Exit
        settle();
        System.out.println("EMU: exit did not terminate the MIDlet");
        System.exit(3);
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

    static void applyTheme() throws Exception {
        Method m = midlet.getClass().getDeclaredMethod("applyLook");
        m.setAccessible(true);
        m.invoke(midlet);
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
        throw new IllegalStateException("command not found: " + label + " on " + d.getClass().getName());
    }

    static void save(String name) throws Exception {
        settle();
        BufferedImage lcd = Mobile.getPlatform().getLCD();
        File f = new File(out, String.format("%02d_%s.png", ++shot, name));
        ImageIO.write(lcd, "png", f);
        System.out.println("EMU: saved " + f.getName() + " (" + current().getClass().getSimpleName() + ")");
    }
}

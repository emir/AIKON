package io.github.emir.claudes40;

import java.io.IOException;
import java.util.Vector;

import javax.microedition.lcdui.Alert;
import javax.microedition.lcdui.AlertType;
import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.CommandListener;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.Form;
import javax.microedition.lcdui.List;
import javax.microedition.lcdui.StringItem;

/**
 * "Kaydedilenler" / "Saved": replies saved on the phone as .txt files
 * (Files), readable without the network. Also saves a reply (save()). All
 * file work runs on worker threads; the phone may ask for permission first.
 */
final class SavedList implements CommandListener, Runnable {

    private static final int JOB_LIST = 0;
    private static final int JOB_READ = 1;
    private static final int JOB_DELETE = 2;
    private static final int JOB_SAVE = 3;

    private final ClaudeS40MIDlet midlet;
    private final List list;
    private final Command openCmd = new Command(L.s("Aç", "Open"), Command.OK, 1);
    private final Command deleteCmd = new Command(L.s("Sil", "Delete"), Command.SCREEN, 2);
    private final Command backCmd = new Command(L.s("Geri", "Back"), Command.BACK, 1);
    private final Command yesCmd = new Command(L.s("Sil", "Delete"), Command.OK, 1);
    private final Command noCmd = new Command(L.s("Vazgeç", "Cancel"), Command.BACK, 1);
    private Form viewer;
    private Alert confirm;

    /** File names in list order. */
    private final Vector files = new Vector();
    private boolean busy;
    private int job;
    private String jobFile;
    private String jobText;
    private Displayable jobBack;

    SavedList(ClaudeS40MIDlet midlet) {
        this.midlet = midlet;
        list = new List(L.s("Kaydedilenler", "Saved"), List.IMPLICIT);
        list.setSelectCommand(openCmd);
        list.addCommand(deleteCmd);
        list.addCommand(backCmd);
        list.setCommandListener(this);
    }

    void show() {
        midlet.display().setCurrent(list);
        start(JOB_LIST, null);
    }

    /** Saves a message as a new file; reports the result over `back`. */
    void save(ChatSession.Entry e, Displayable back) {
        synchronized (this) {
            if (busy) {
                midlet.info(L.s("Önceki kayıt işlemi sürüyor.", "Still busy with the previous file."), back);
                return;
            }
            String t = e.time > 0 ? Text.local(e.time, true) : Text.local(System.currentTimeMillis(), true);
            jobText = "AIKON · " + t + "\n\n" + Cal.shown(e.text) + "\n";
            jobFile = Text.stamp(e.time > 0 ? e.time : System.currentTimeMillis()) + "-" + Text.slug(ClaudeS40MIDlet.quote(e.text), 24);
            jobBack = back;
        }
        start(JOB_SAVE, jobFile);
    }

    private void start(int j, String file) {
        synchronized (this) {
            if (busy) {
                return;
            }
            busy = true;
            job = j;
            jobFile = file;
        }
        if (j == JOB_LIST) {
            files.removeAllElements();
            list.deleteAll();
            list.append(L.s("Yükleniyor...", "Loading..."), null);
        }
        new Thread(this).start();
    }

    public void run() {
        int j;
        String file;
        synchronized (this) {
            j = job;
            file = jobFile;
        }
        String err = null;
        String text = null;
        Vector found = null;
        try {
            if (j == JOB_SAVE) {
                file = Files.save(file, jobText);
            } else if (j == JOB_READ) {
                text = Files.read(file);
            } else if (j == JOB_DELETE) {
                Files.delete(file);
            }
            if (j == JOB_LIST || j == JOB_DELETE) {
                found = Files.list();
            }
        } catch (IOException e) {
            err = L.s("Dosya işlemi başarısız: ", "File error: ") + e.getMessage();
        } catch (SecurityException e) {
            err = L.s("İzin verilmedi. Telefon dosya erişimini sorduğunda 'Evet' deyin.",
                    "Permission denied. Answer 'Yes' when the phone asks about file access.");
        } catch (RuntimeException e) {
            err = L.s("Dosya işlemi başarısız: ", "File error: ") + e.getClass().getName() + ": " + e.getMessage();
        }
        synchronized (this) {
            busy = false;
        }
        if (j == JOB_SAVE) {
            midlet.info(err != null ? err : L.s("Telefona kaydedildi:\n", "Saved on the phone:\n") + Files.where() + file
                    + L.s("\n\nAna menü > Kaydedilenler'den internetsiz okunur.", "\n\nRead it offline: main menu > Saved."),
                    jobBack);
            return;
        }
        if (j == JOB_READ && err == null) {
            showText(file, text);
            return;
        }
        if (found != null) {
            fill(found);
        } else if (j == JOB_LIST) {
            list.deleteAll();
        }
        if (err != null) {
            if (j == JOB_LIST) {
                list.append(L.s("Hata: ", "Error: ") + err, null);
            } else {
                midlet.info(err, list);
            }
        }
    }

    private void fill(Vector found) {
        synchronized (this) {
            files.removeAllElements();
            for (int i = 0; i < found.size(); i++) {
                files.addElement(found.elementAt(i));
            }
        }
        list.deleteAll();
        if (found.size() == 0) {
            list.append(L.s("Henüz kayıt yok. Sohbette bir yanıtı seçin (1/3), orta tuş > Telefona kaydet.",
                    "Nothing saved yet. In the chat select a reply (1/3), centre key > Save to phone."), null);
        }
        for (int i = 0; i < found.size(); i++) {
            list.append(label((String) found.elementAt(i)), null);
        }
    }

    /** "20260926-2105-ekmek-tarifi.txt" -> "26.09.2026 21:05 · ekmek tarifi". */
    static String label(String file) {
        String n = file.endsWith(".txt") ? file.substring(0, file.length() - 4) : file;
        if (n.length() < 13 || n.charAt(8) != '-' || Text.parseInt(n.substring(0, 8), -1) < 0
                || Text.parseInt(n.substring(9, 13), -1) < 0) {
            return n;
        }
        String rest = n.length() > 14 ? n.substring(14).replace('-', ' ') : "";
        return n.substring(6, 8) + "." + n.substring(4, 6) + "." + n.substring(0, 4) + " " + n.substring(9, 11) + ":"
                + n.substring(11, 13) + (rest.length() > 0 ? " · " + rest : "");
    }

    private void showText(String file, String text) {
        viewer = new Form(label(file));
        viewer.append(new StringItem(null, text));
        viewer.addCommand(backCmd);
        viewer.addCommand(deleteCmd);
        viewer.setCommandListener(this);
        synchronized (this) {
            jobFile = file;
        }
        midlet.display().setCurrent(viewer);
    }

    private synchronized String selectedFile() {
        int i = list.getSelectedIndex();
        return i >= 0 && i < files.size() ? (String) files.elementAt(i) : null;
    }

    public void commandAction(Command c, Displayable d) {
        if (d == confirm) {
            String f;
            synchronized (this) {
                f = jobFile;
            }
            midlet.display().setCurrent(list);
            if (c == yesCmd && f != null) {
                start(JOB_DELETE, f);
            }
            return;
        }
        if (c == backCmd) {
            if (d == viewer) {
                midlet.display().setCurrent(list);
            } else {
                midlet.showMenu();
            }
        } else if (c == openCmd) {
            String f = selectedFile();
            if (f != null) {
                start(JOB_READ, f);
            }
        } else if (c == deleteCmd) {
            String f;
            if (d == viewer) {
                synchronized (this) {
                    f = jobFile;
                }
            } else {
                f = selectedFile();
            }
            if (f == null) {
                return;
            }
            synchronized (this) {
                jobFile = f;
            }
            confirm = new Alert(L.s("Sil", "Delete"), L.s("Bu kayıt telefondan silinsin mi?\n", "Delete this file from the phone?\n")
                    + label(f), null, AlertType.WARNING);
            confirm.setTimeout(Alert.FOREVER);
            confirm.addCommand(yesCmd);
            confirm.addCommand(noCmd);
            confirm.setCommandListener(this);
            midlet.display().setCurrent(confirm);
        }
    }
}

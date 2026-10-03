package io.github.emir.claudes40;

import java.io.IOException;
import java.util.Vector;

import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.CommandListener;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.List;

/**
 * Picks a photo (.jpg/.jpeg/.png) from the phone's folders: starts in the
 * phone's photo folder, can go up to the drives. No thumbnails (a 2 MP
 * photo does not fit in the phone's memory as an image), just names. Every
 * file access runs on a worker thread through Files (JSR 75).
 */
final class PhotoPicker implements CommandListener, Runnable {

    private static final int JOB_LIST = 0;
    private static final int JOB_READ = 1;

    private final ClaudeS40MIDlet midlet;
    private final Photo photo;
    private final RowList list;
    private final Command backCmd = new Command(L.s("Geri", "Back"), Command.BACK, 1);
    private final Command cancelCmd = new Command(L.s("Vazgeç", "Cancel"), Command.SCREEN, 2);

    /** Folder URL shown, null for the drive list. */
    private String dir;
    /** What each row opens: a folder/drive URL, a file URL, or "" for "up". */
    private Vector targets = new Vector();
    private int job;
    private String jobUrl;
    private boolean busy;
    private boolean closed;

    PhotoPicker(ClaudeS40MIDlet midlet, Photo photo) {
        this.midlet = midlet;
        this.photo = photo;
        list = new RowList(L.s("Fotoğraf seç", "Choose a photo"));
        list.addCommand(backCmd);
        list.addCommand(cancelCmd);
        list.setCommandListener(this);
    }

    void start() {
        midlet.display().setCurrent(list);
        load(Files.photoFolder());
    }

    private synchronized void load(String url) {
        if (busy) {
            return;
        }
        busy = true;
        job = JOB_LIST;
        jobUrl = url;
        list.deleteAll();
        list.skeleton(5);
        new Thread(this).start();
    }

    public void run() {
        int j;
        String url;
        synchronized (this) {
            j = job;
            url = jobUrl;
        }
        if (j == JOB_LIST) {
            list(url);
        } else {
            read(url);
        }
    }

    private void list(String url) {
        Vector names;
        String shown = url;
        try {
            if (url == null) {
                names = Files.roots();
            } else {
                try {
                    names = Files.listPhotos(url);
                } catch (IOException e) {
                    // the photo folder may not exist (no memory card): the drives instead
                    shown = null;
                    names = Files.roots();
                }
            }
        } catch (SecurityException e) {
            done();
            photo.failed(L.s("Dosya erişimi izni verilmedi. Telefon sorduğunda 'Evet' deyin.",
                    "File access was denied. Answer 'Yes' when the phone asks."));
            return;
        } catch (RuntimeException e) {
            done();
            photo.failed(L.s("Klasör okunamadı: ", "Could not read the folder: ") + e.getMessage());
            return;
        }
        synchronized (this) {
            busy = false;
            if (closed) {
                return;
            }
            dir = shown;
            targets = new Vector();
            list.deleteAll();
            if (dir != null) {
                targets.addElement("");
                list.add(L.s(".. (üst klasör)", ".. (up)"), null, -1, 0);
            }
            int photos = 0;
            for (int i = 0; i < names.size(); i++) {
                String n = (String) names.elementAt(i);
                if (dir == null) {
                    targets.addElement(n);
                    list.add(n.substring(8), null, -1, 0); // "C:/", "E:/"
                } else {
                    targets.addElement(dir + n);
                    list.add(n, null, -1, 0);
                    if (!n.endsWith("/")) {
                        photos++;
                    }
                }
            }
            list.title(dir == null ? L.s("Sürücüler", "Drives") : title(dir) + " (" + photos + ")");
        }
    }

    private void read(String url) {
        byte[] b = null;
        String err = null;
        try {
            b = Files.readBytes(url, Photo.MAX_BYTES);
            if (b == null) {
                long size = Files.size(url);
                err = L.f("Fotoğraf çok büyük ({0} KB). En fazla {1} KB; uygulamadaki kamerayla çekin.", "The photo is too large ({0} KB). At most {1} KB; take it with the app's camera instead.", String.valueOf(size / 1024), String.valueOf(Photo.MAX_BYTES / 1024));
            }
        } catch (IOException e) {
            err = L.s("Dosya okunamadı: ", "Could not read the file: ") + e.getMessage();
        } catch (SecurityException e) {
            err = L.s("Dosya erişimi izni verilmedi.", "File access was denied.");
        } catch (OutOfMemoryError e) {
            err = L.s("Fotoğraf telefonun belleğine sığmadı. Uygulamadaki kamerayla çekin.",
                    "The photo does not fit in the phone's memory. Take it with the app's camera.");
        }
        synchronized (this) {
            busy = false;
            if (closed) {
                return;
            }
            if (b != null) {
                closed = true;
            }
        }
        if (b != null) {
            photo.chosen(b);
        } else {
            midlet.info(err, list);
        }
    }

    private synchronized void done() {
        busy = false;
        closed = true;
    }

    /** "Images" for "file:///E:/Images/". */
    private static String title(String url) {
        String u = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        return u.substring(u.lastIndexOf('/') + 1);
    }

    /** The folder above, or null (the drive list) above a drive. */
    private static String parent(String url) {
        String u = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        int i = u.lastIndexOf('/');
        String p = i < 0 ? "" : u.substring(0, i + 1);
        return p.length() <= "file:///".length() ? null : p;
    }

    public void commandAction(Command c, Displayable d) {
        midlet.userActive();
        if (c == List.SELECT_COMMAND) {
            String t;
            synchronized (this) {
                int i = list.getSelectedIndex();
                if (busy || i < 0 || i >= targets.size()) {
                    return;
                }
                t = (String) targets.elementAt(i);
            }
            if (t.length() == 0) {
                load(parent(dir));
            } else if (t.endsWith("/")) {
                load(t);
            } else {
                synchronized (this) {
                    busy = true;
                    job = JOB_READ;
                    jobUrl = t;
                }
                list.title(L.s("Okunuyor...", "Reading..."));
                new Thread(this).start();
            }
        } else if (c == backCmd && dir != null) {
            load(parent(dir));
        } else {
            synchronized (this) {
                closed = true;
            }
            photo.cancelled();
        }
    }
}

package io.github.emir.claudes40;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Vector;

import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.CommandListener;
import javax.microedition.lcdui.Display;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.List;
import javax.microedition.rms.RecordStore;
import javax.microedition.rms.RecordStoreException;

/**
 * The models the server offers (/v1/models, server 0.7.0+) and the picker
 * for them. The list is kept in RMS "cs40models" (with the model chosen last
 * for new chats), so the picker opens at once and works offline; it is only
 * fetched when the picker is opened without a list or with "Refresh", on a
 * worker thread. Choosing a model never sends anything by itself: the
 * choice goes with the next message the user sends.
 */
final class Models implements CommandListener, Runnable {

    private static final String STORE = "cs40models";
    private static final int FORMAT = 1;

    /** Picker for a new chat, or for switching the current one. */
    static final int FOR_NEW = 0;
    static final int FOR_SWITCH = 1;

    // ------------------------------------------------------------ the list

    private static String[] ids = new String[0];
    private static String[] names = new String[0];
    private static String defaultId = "";
    /** Chosen last for a new chat; "" = the server's default. */
    private static String last = "";
    private static boolean loaded;

    static synchronized int count() {
        load();
        return ids.length;
    }

    /** Display name of a model id, or "" if not in the list. */
    static synchronized String name(String id) {
        load();
        for (int i = 0; i < ids.length; i++) {
            if (ids[i].equals(id)) {
                return names[i];
            }
        }
        return "";
    }

    /** The model a new chat starts with: the last choice if still offered, else the default. */
    static synchronized String startId() {
        load();
        return name(last).length() > 0 ? last : defaultId;
    }

    private static synchronized void setLast(String id) {
        last = id;
        save();
    }

    private static void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        RecordStore rs = null;
        try {
            rs = RecordStore.openRecordStore(STORE, false);
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(rs.getRecord(1)));
            if (in.readInt() != FORMAT) {
                return;
            }
            String def = in.readUTF();
            String l = in.readUTF();
            int n = in.readInt();
            String[] i2 = new String[n];
            String[] n2 = new String[n];
            for (int i = 0; i < n; i++) {
                i2[i] = in.readUTF();
                n2[i] = in.readUTF();
            }
            ids = i2;
            names = n2;
            defaultId = def;
            last = l;
        } catch (RecordStoreException e) {
            // nothing kept yet
        } catch (IOException e) {
            // unreadable: fetched again when needed
        } finally {
            close(rs);
        }
    }

    private static void save() {
        RecordStore rs = null;
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bo);
            out.writeInt(FORMAT);
            out.writeUTF(defaultId);
            out.writeUTF(last);
            out.writeInt(ids.length);
            for (int i = 0; i < ids.length; i++) {
                out.writeUTF(ids[i]);
                out.writeUTF(names[i]);
            }
            out.close();
            byte[] b = bo.toByteArray();
            rs = RecordStore.openRecordStore(STORE, true);
            if (rs.getNumRecords() == 0) {
                rs.addRecord(b, 0, b.length);
            } else {
                rs.setRecord(1, b, 0, b.length);
            }
        } catch (RecordStoreException e) {
            // not kept: fetched again next time
        } catch (IOException e) {
            // same
        } finally {
            close(rs);
        }
    }

    /** "Reset setup": another server may offer other models. */
    static synchronized void clear() {
        ids = new String[0];
        names = new String[0];
        defaultId = "";
        last = "";
        loaded = true;
        try {
            RecordStore.deleteRecordStore(STORE);
        } catch (RecordStoreException e) {
            // not there
        }
    }

    private static void close(RecordStore rs) {
        if (rs != null) {
            try {
                rs.closeRecordStore();
            } catch (RecordStoreException e) {
                // ignore
            }
        }
    }

    /**
     * Fetches the list (worker thread only). Lines: id TAB name TAB search
     * TAB photos. Returns null, or why it failed.
     */
    private static String fetch(ClaudeS40MIDlet midlet) {
        Settings s = midlet.settings;
        Net.Result r = Net.request(s.url + "/v1/models", "POST", s.token,
                S40Message.format(new String[0], new String[0], ""), midlet.userAgent(), null);
        S40Message m = r.msg;
        if (!r.ok()) {
            return Net.explain(r);
        }
        String st = m == null ? "?" : m.field("status");
        if ("not_found".equals(st) || "method_not_allowed".equals(st)) {
            return L.s("Sunucu model seçimini bilmiyor (sunucu 0.7.0 gerekli).",
                    "The server has no model choice (needs server 0.7.0).");
        }
        if (!"ok".equals(st)) {
            return L.s("Liste alınamadı (", "Could not get the list (") + st + ").";
        }
        Vector vi = new Vector();
        Vector vn = new Vector();
        String t = m.text;
        int pos = 0;
        while (pos < t.length()) {
            int nl = t.indexOf('\n', pos);
            String line = t.substring(pos, nl < 0 ? t.length() : nl);
            pos = nl < 0 ? t.length() : nl + 1;
            int t1 = line.indexOf('\t');
            int t2 = t1 < 0 ? -1 : line.indexOf('\t', t1 + 1);
            if (t1 > 0 && t2 > t1 + 1) {
                vi.addElement(line.substring(0, t1));
                vn.addElement(line.substring(t1 + 1, t2));
            }
        }
        if (vi.size() == 0) {
            return L.s("Sunucu hiç model listelemedi.", "The server listed no models.");
        }
        synchronized (Models.class) {
            ids = new String[vi.size()];
            names = new String[vn.size()];
            vi.copyInto(ids);
            vn.copyInto(names);
            defaultId = m.field("default").length() > 0 ? m.field("default") : ids[0];
            loaded = true;
            save();
        }
        return null;
    }

    // ------------------------------------------------------------ the picker

    private final ClaudeS40MIDlet midlet;
    private final List list;
    private final Command chooseCmd = new Command(L.s("Seç", "Choose"), Command.OK, 1);
    private final Command refreshCmd = new Command(L.s("Listeyi yenile", "Refresh the list"), Command.SCREEN, 2);
    private final Command backCmd = new Command(L.s("Geri", "Back"), Command.BACK, 1);
    private final Displayable back;
    private final int purpose;
    /** Ids in list order; empty while loading or on error. */
    private String[] shown = new String[0];
    private boolean loading;

    Models(ClaudeS40MIDlet midlet, int purpose, Displayable back) {
        this.midlet = midlet;
        this.purpose = purpose;
        this.back = back;
        list = new List(purpose == FOR_NEW ? L.s("Yeni sohbet: model", "New chat: model") : L.s("Model", "Model"),
                List.IMPLICIT);
        list.setSelectCommand(chooseCmd);
        list.addCommand(refreshCmd);
        list.addCommand(backCmd);
        list.setCommandListener(this);
    }

    void show(Display display) {
        display.setCurrent(list);
        if (count() == 0 && !midlet.settings.testMode) {
            start();
        } else {
            fill(null);
        }
    }

    private void start() {
        synchronized (this) {
            if (loading) {
                return;
            }
            loading = true;
            shown = new String[0];
        }
        list.deleteAll();
        list.append(L.s("Yükleniyor...", "Loading..."), null);
        new Thread(this).start();
    }

    public void run() {
        String error = fetch(midlet);
        synchronized (this) {
            loading = false;
        }
        fill(error);
    }

    /** Shows the kept list; the current model is marked. */
    private void fill(String error) {
        String current = purpose == FOR_NEW ? startId() : midlet.session().modelId();
        String[] i2;
        String[] n2;
        String mark;
        synchronized (Models.class) {
            load();
            i2 = ids;
            n2 = names;
            mark = purpose == FOR_SWITCH ? L.s(" (şu an)", " (now)")
                    : current.equals(last) ? L.s(" (son seçim)", " (last used)") : L.s(" (varsayılan)", " (default)");
        }
        list.deleteAll();
        if (error != null) {
            list.append(L.s("Hata: ", "Error: ") + error, null);
        } else if (i2.length == 0) {
            list.append(midlet.settings.testMode
                    ? L.s("Test modunda liste sunucudan alınamaz.", "In test mode the list cannot come from the server.")
                    : L.s("Liste boş. 'Listeyi yenile'yi seçin.", "No list yet. Choose 'Refresh the list'."), null);
        }
        int sel = -1;
        for (int i = 0; i < i2.length; i++) {
            boolean now = i2[i].equals(current);
            list.append(n2[i] + (now ? mark : ""), null);
            if (now) {
                sel = list.size() - 1;
            }
        }
        if (sel >= 0) {
            list.setSelectedIndex(sel, true);
        }
        synchronized (this) {
            shown = i2; // after any error line: the kept list stays choosable
        }
    }

    public void commandAction(Command c, Displayable d) {
        midlet.userActive();
        if (c == backCmd) {
            midlet.display().setCurrent(back);
        } else if (c == refreshCmd) {
            if (midlet.settings.testMode) {
                midlet.info(L.s("Test modunda ağ kullanılmaz.", "Test mode uses no network."), list);
            } else {
                start();
            }
        } else if (c == chooseCmd || c == List.SELECT_COMMAND) {
            String id;
            synchronized (this) {
                int i = list.getSelectedIndex() - (list.size() - shown.length);
                id = i >= 0 && i < shown.length ? shown[i] : null;
            }
            if (id == null) {
                return;
            }
            String err;
            if (purpose == FOR_NEW) {
                setLast(id);
                err = midlet.session().newChat(id);
            } else {
                err = midlet.session().setModel(id);
            }
            if (err != null) {
                midlet.info(err, back);
            } else {
                midlet.showChat();
            }
        }
    }
}

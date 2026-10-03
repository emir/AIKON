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
 * for them: first the provider (Claude, OpenAI, Grok...), then one of its
 * models; with a single provider the first step is skipped. The list is kept in RMS "cs40models" (with the model chosen last
 * for new chats), so the picker opens at once and works offline; it is only
 * fetched when the picker is opened without a list or with "Refresh", on a
 * worker thread. Choosing a model never sends anything by itself: the
 * choice goes with the next message the user sends.
 */
final class Models implements CommandListener, Runnable {

    private static final String STORE = "cs40models";
    /** 3: with each model's provider and cost (older lists are fetched again). */
    private static final int FORMAT = 3;

    /** Picker for a new chat, or for switching the current one. */
    static final int FOR_NEW = 0;
    static final int FOR_SWITCH = 1;

    // ------------------------------------------------------------ the list

    private static String[] ids = new String[0];
    private static String[] names = new String[0];
    /** Provider name of each model ("Claude", "OpenAI"...; "" from a 0.7.0 server). */
    private static String[] providers = new String[0];
    /** Credits a typical message costs with each model ("" = not charged; server extension, 0.12.1). */
    private static String[] costs = new String[0];
    private static String defaultId = "";
    /** Chosen last for a new chat; "" = the server's default. */
    private static String last = "";
    private static boolean loaded;

    static synchronized int count() {
        load();
        return ids.length;
    }

    /** Display name of a model id, or "" if not in the list. */
    /** Credits a typical message costs with the model, "" if unknown or free. */
    static synchronized String cost(String id) {
        load();
        for (int i = 0; i < ids.length; i++) {
            if (ids[i].equals(id)) {
                return i < costs.length ? costs[i] : "";
            }
        }
        return "";
    }

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

    /** The model new chats start with (also set by a free trial: its model). */
    static synchronized void setLast(String id) {
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
            String[] p2 = new String[n];
            String[] c2 = new String[n];
            for (int i = 0; i < n; i++) {
                i2[i] = in.readUTF();
                n2[i] = in.readUTF();
                p2[i] = in.readUTF();
                c2[i] = in.readUTF();
            }
            ids = i2;
            names = n2;
            providers = p2;
            costs = c2;
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
                out.writeUTF(providers[i]);
                out.writeUTF(i < costs.length ? costs[i] : "");
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
        providers = new String[0];
        costs = new String[0];
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
     * TAB photos TAB provider (the provider since server 0.7.1), with
     * "costs: 1" TAB cost (servers that charge credits; others send no
     * sixth field). Returns null, or why it failed.
     */
    private static String fetch(ClaudeS40MIDlet midlet) {
        Settings s = midlet.settings;
        Net.Result r = Net.request(s.url + "/v1/models", "POST", s.token,
                S40Message.format(new String[] { "costs" }, new String[] { "1" }, ""), midlet.userAgent(), null);
        S40Message m = r.msg;
        if (!r.ok()) {
            return Net.explain(r);
        }
        String st = m == null ? "?" : m.field("status");
        if ("not_found".equals(st) || "method_not_allowed".equals(st)) {
            return L.t("The server has no model choice (needs server 0.7.0).");
        }
        if (!"ok".equals(st)) {
            return L.t("Could not get the list (") + st + ").";
        }
        Vector vi = new Vector();
        Vector vn = new Vector();
        Vector vp = new Vector();
        Vector vc = new Vector();
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
                int t3 = line.indexOf('\t', t2 + 1);
                int t4 = t3 < 0 ? -1 : line.indexOf('\t', t3 + 1);
                int t5 = t4 < 0 ? -1 : line.indexOf('\t', t4 + 1);
                vp.addElement(t4 < 0 ? "" : line.substring(t4 + 1, t5 < 0 ? line.length() : t5).trim());
                vc.addElement(t5 < 0 ? "" : line.substring(t5 + 1).trim());
            }
        }
        if (vi.size() == 0) {
            return L.t("The server listed no models.");
        }
        synchronized (Models.class) {
            ids = new String[vi.size()];
            names = new String[vn.size()];
            providers = new String[vp.size()];
            costs = new String[vc.size()];
            vi.copyInto(ids);
            vn.copyInto(names);
            vp.copyInto(providers);
            vc.copyInto(costs);
            defaultId = m.field("default").length() > 0 ? m.field("default") : ids[0];
            loaded = true;
            save();
        }
        return null;
    }

    // ------------------------------------------------------------ the picker

    private final ClaudeS40MIDlet midlet;
    private final RowList list;
    private final Command chooseCmd = new Command(L.t("Choose"), Command.OK, 1);
    private final Command refreshCmd = new Command(L.t("Refresh the list"), Command.SCREEN, 2);
    private final Command backCmd = new Command(L.t("Back"), Command.BACK, 1);
    private final Displayable back;
    private final int purpose;
    /** Model ids, or provider names on the first step, in list order. */
    private String[] shown = new String[0];
    private boolean loading;
    /** The provider whose models are shown; null on the first step. */
    private String provider;
    /** True while the providers are listed (more than one provider). */
    private boolean choosingProvider;

    Models(ClaudeS40MIDlet midlet, int purpose, Displayable back) {
        this.midlet = midlet;
        this.purpose = purpose;
        this.back = back;
        list = new RowList(title());
        list.setSelectCommand(chooseCmd);
        list.addCommand(refreshCmd);
        list.addCommand(backCmd);
        list.setCommandListener(this);
    }

    private String title() {
        String t = purpose == FOR_NEW ? L.t("New chat") : L.t("Model");
        return provider != null && provider.length() > 0 ? t + ": " + provider : t;
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
            provider = null;
        }
        list.deleteAll();
        list.skeleton(5);
        new Thread(this).start();
    }

    public void run() {
        String error = fetch(midlet);
        synchronized (this) {
            loading = false;
        }
        fill(error);
    }

    /**
     * Shows the providers, or the models of the chosen provider (all models
     * when there is only one provider); the current choice is marked.
     */
    private void fill(String error) {
        String current = purpose == FOR_NEW ? startId() : midlet.session().modelId();
        String[] i2;
        String[] n2;
        String[] p2;
        String[] c2;
        String mark;
        synchronized (Models.class) {
            load();
            i2 = ids;
            n2 = names;
            p2 = providers;
            c2 = costs;
            mark = purpose == FOR_SWITCH ? L.t("now")
                    : current.equals(last) ? L.t("last used") : L.t("default");
        }
        String currentProvider = "";
        Vector groups = new Vector();
        for (int i = 0; i < i2.length; i++) {
            if (!groups.contains(p2[i])) {
                groups.addElement(p2[i]);
            }
            if (i2[i].equals(current)) {
                currentProvider = p2[i];
            }
        }
        boolean step1;
        String prov;
        synchronized (this) {
            step1 = provider == null && groups.size() > 1;
            prov = provider;
            choosingProvider = step1;
        }
        list.title(title());
        list.deleteAll();
        if (error != null) {
            list.note(L.t("Error: ") + error);
        } else if (i2.length == 0) {
            list.note(midlet.settings.testMode
                    ? L.t("In test mode the list cannot come from the server.")
                    : L.t("No list yet. Choose 'Refresh the list'."));
        }
        Vector v = new Vector();
        int sel = -1;
        if (step1) {
            for (int i = 0; i < groups.size(); i++) {
                String g = (String) groups.elementAt(i);
                boolean now = g.equals(currentProvider);
                int count = 0;
                for (int k = 0; k < p2.length; k++) {
                    count += p2[k].equals(g) ? 1 : 0;
                }
                list.add(g.length() > 0 ? g : L.t("Other"),
                        (count == 1 ? L.t("1 model") : L.f("{0} models", String.valueOf(count)))
                                + (now ? " · " + mark : ""), -1,
                        now ? RowList.CHECK : 0);
                v.addElement(g);
                if (now) {
                    sel = list.size() - 1;
                }
            }
        } else {
            for (int i = 0; i < i2.length; i++) {
                if (prov != null && !p2[i].equals(prov)) {
                    continue;
                }
                boolean now = i2[i].equals(current);
                String cost = i < c2.length ? c2[i] : "";
                String sub = cost.length() > 0 ? L.f("~{0} credits a message", String.valueOf(cost)) : "";
                list.add(n2[i], now ? (sub.length() > 0 ? sub + " · " : "") + mark : sub, -1, now ? RowList.CHECK : 0);
                v.addElement(i2[i]);
                if (now) {
                    sel = list.size() - 1;
                }
            }
        }
        if (sel < 0 && v.size() > 0) {
            sel = list.size() - v.size(); // the first choice, after any note
        }
        if (sel >= 0) {
            list.setSelectedIndex(sel, true);
        }
        String[] out = new String[v.size()];
        v.copyInto(out);
        synchronized (this) {
            shown = out; // after any error line: the kept list stays choosable
        }
    }

    public void commandAction(Command c, Displayable d) {
        midlet.userActive();
        if (c == backCmd) {
            boolean up;
            synchronized (this) {
                up = provider != null; // from the models back to the providers
                provider = null;
            }
            if (up) {
                fill(null);
            } else {
                midlet.display().setCurrent(back);
            }
        } else if (c == refreshCmd) {
            if (midlet.settings.testMode) {
                midlet.info(L.t("Test mode uses no network."), list);
            } else {
                start();
            }
        } else if (c == chooseCmd || c == List.SELECT_COMMAND) {
            String id;
            boolean step1;
            synchronized (this) {
                int i = list.getSelectedIndex() - (list.size() - shown.length);
                id = i >= 0 && i < shown.length ? shown[i] : null;
                step1 = choosingProvider;
                if (id != null && step1) {
                    provider = id;
                }
            }
            if (id == null) {
                return;
            }
            if (step1) {
                fill(null); // the models of this provider
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

package io.github.emir.claudes40;

import java.util.Vector;

import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.CommandListener;
import javax.microedition.lcdui.Display;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.TextBox;
import javax.microedition.lcdui.TextField;

/**
 * "Sohbetler" / "Chats": the conversations kept on the server
 * (/v1/conversations), pinned ones first with a pin icon, in the phone's own
 * List. Selecting one opens it in the chat screen (/v1/history) where it can
 * be continued. Options: pin / unpin (/v1/pin; pinned chats are kept until
 * unpinned), delete after a question (/v1/delete) and search the text of all
 * chats (/v1/search). Network work runs on a worker thread; nothing here
 * calls Claude.
 */
final class ChatList implements CommandListener, Runnable {

    private static final int JOB_LIST = 0;
    private static final int JOB_SEARCH = 1;
    private static final int JOB_PIN = 2;
    private static final int JOB_DELETE = 3;

    private final ClaudeS40MIDlet midlet;
    private final RowList list;
    private final Command openCmd = new Command(L.t("Open"), Command.OK, 1);
    private final Command searchCmd = new Command(L.t("Search chats"), Command.SCREEN, 2);
    private final Command pinCmd = new Command(L.t("Pin / unpin"), Command.SCREEN, 3);
    private final Command deleteCmd = new Command(L.t("Delete"), Command.SCREEN, 5);
    private final Command allCmd = new Command(L.t("All chats"), Command.SCREEN, 2);
    private final Command refreshCmd = new Command(L.t("Refresh"), Command.SCREEN, 4);
    private final Command backCmd = new Command(L.t("Back"), Command.BACK, 1);
    private final Command findCmd = new Command(L.t("Search"), Command.OK, 1);
    private final Command yesCmd = new Command(L.t("Delete"), Command.OK, 1);
    private final Command noCmd = new Command(L.t("Cancel"), Command.BACK, 1);
    private TextBox searchBox;
    private TextPage confirm;

    /** Conversation ids and pinned flags (Boolean) in list order; empty while loading or on error. */
    private final Vector ids = new Vector();
    private final Vector pins = new Vector();
    private boolean loading;
    /** The search shown, or null for the list of chats. */
    private String query;
    private int job;
    private String jobId;
    private boolean jobPin;
    private boolean searchMode;
    /** The rows' {title, second line}, in the order of ids (for changes shown before the server answers). */
    private final Vector shown = new Vector();
    /** The action row of an empty list or an error (CTA_*), offered on the centre key. */
    private int cta;
    private static final int CTA_NEW = 1;
    private static final int CTA_SEARCH = 2;
    private static final int CTA_RETRY = 3;

    ChatList(ClaudeS40MIDlet midlet) {
        this.midlet = midlet;
        list = new RowList(L.t("Chats"));
        list.setSelectCommand(openCmd);
        list.addCommand(searchCmd);
        list.addCommand(pinCmd);
        list.addCommand(deleteCmd);
        list.addCommand(refreshCmd);
        list.addCommand(backCmd);
        list.setCommandListener(this);
    }

    void show(Display display) {
        display.setCurrent(list);
        setQuery(null);
        start(JOB_LIST, null, false);
    }

    /** Switches between the list of chats (null) and a search. */
    private void setQuery(String q) {
        boolean search = q != null;
        synchronized (this) {
            query = q;
        }
        if (search != searchMode) {
            if (search) {
                list.removeCommand(pinCmd);
                list.addCommand(allCmd);
            } else {
                list.removeCommand(allCmd);
                list.addCommand(pinCmd);
            }
            searchMode = search;
        }
        list.title(search ? L.t("Search: ") + q : L.t("Chats"));
    }

    private void start(int j, String id, boolean pin) {
        synchronized (this) {
            if (loading) {
                return;
            }
            loading = true;
            job = j;
            jobId = id;
            jobPin = pin;
            if (j == JOB_LIST || j == JOB_SEARCH) {
                ids.removeAllElements();
                pins.removeAllElements();
            }
        }
        if (j == JOB_LIST || j == JOB_SEARCH) {
            list.deleteAll();
            list.skeleton(5);
        } else {
            optimistic(j, id, pin); // shown at once; the list from the server follows (or undoes it)
            list.setBusy(true);
        }
        new Thread(this).start();
    }

    public void run() {
        int j;
        String id;
        boolean pin;
        String q;
        synchronized (this) {
            j = job;
            id = jobId;
            pin = jobPin;
            q = query;
        }
        Settings s = midlet.settings;
        String actionError = null;
        if (j == JOB_PIN || j == JOB_DELETE) {
            Net.Result r = j == JOB_PIN
                    ? Net.request(s.url + "/v1/pin", "POST", s.token, S40Message.format(new String[] { "conversation", "pinned" },
                            new String[] { id, pin ? "1" : "0" }, ""), midlet.userAgent(), null)
                    : Net.request(s.url + "/v1/delete", "POST", s.token, S40Message.format(new String[] { "conversation" },
                            new String[] { id }, ""), midlet.userAgent(), null);
            String st = r.msg == null ? "" : r.msg.field("status");
            if (!r.ok()) {
                actionError = Net.explain(r);
            } else if ("pin_limit".equals(st)) {
                actionError = L.f("At most {0} chats can be pinned. Unpin one first.", r.msg.field("max"));
            } else if (j == JOB_DELETE && ("deleted".equals(st) || "conversation_not_found".equals(st))) {
                midlet.chatDeleted(id);
            } else if (!"ok".equals(st)) {
                actionError = (j == JOB_PIN ? L.t("Could not pin (") : L.t("Could not delete ("))
                        + (st.length() > 0 ? st : "?") + ").";
            }
        }
        boolean search = q != null;
        Net.Result r = search
                ? Net.request(s.url + "/v1/search", "POST", s.token, S40Message.format(new String[0], new String[0], q),
                        midlet.userAgent(), null)
                : Net.request(s.url + "/v1/conversations", "POST", s.token, S40Message.format(new String[] { "pins", "models" },
                        new String[] { "1", "1" }, ""), midlet.userAgent(), null);
        Vector titles = new Vector();
        Vector found = new Vector();
        Vector pinned = new Vector();
        String error = null;
        S40Message m = r.msg;
        if (!r.ok()) {
            error = Net.explain(r);
        } else if (m == null || !"ok".equals(m.field("status"))) {
            String st = m == null ? "?" : m.field("status");
            error = "not_found".equals(st) || "method_not_allowed".equals(st)
                    ? L.t("The server cannot search (needs server 0.4.0).")
                    : L.t("Could not get the list (") + st + ").";
        } else {
            parse(m.text, search, found, pinned, titles);
        }
        synchronized (this) {
            loading = false;
            ids.removeAllElements();
            pins.removeAllElements();
            for (int i = 0; i < found.size(); i++) {
                ids.addElement(found.elementAt(i));
                pins.addElement(pinned.elementAt(i));
            }
        }
        render(error, search, titles, pinned);
        if (actionError != null) {
            midlet.info(actionError, list);
        }
    }

    /** The rows (and the action row of an empty list or an error); keeps them for optimistic(). */
    private void render(String error, boolean search, Vector titles, Vector pinned) {
        int act = error != null ? CTA_RETRY : titles.size() > 0 ? 0 : search ? CTA_SEARCH : CTA_NEW;
        synchronized (this) {
            cta = act;
            shown.removeAllElements();
            for (int i = 0; i < titles.size(); i++) {
                shown.addElement(titles.elementAt(i));
            }
        }
        list.setBusy(false);
        list.deleteAll();
        if (error != null) {
            list.note(L.t("Error: ") + error);
        } else if (titles.size() == 0) {
            list.note(search ? L.t("Nothing found. Plain letters are fine: 'sise' finds 'şişe'.")
                    : L.t("No chats yet. The server keeps chats for 30 days, pinned ones until unpinned."));
        }
        if (act == CTA_RETRY) {
            list.add(L.t("Try again"), null, Icons.RESEND, RowList.ACCENT);
        } else if (act == CTA_SEARCH) {
            list.add(L.t("Search again"), null, Icons.SEARCH, RowList.ACCENT);
        } else if (act == CTA_NEW) {
            list.add(L.t("Start a new chat"), null, Icons.NEW_CHAT, RowList.ACCENT);
        }
        boolean anyPinned = false;
        for (int i = 0; i < pinned.size(); i++) {
            anyPinned |= ((Boolean) pinned.elementAt(i)).booleanValue();
        }
        boolean lastPinned = false;
        for (int i = 0; i < titles.size(); i++) {
            String[] t = (String[]) titles.elementAt(i);
            boolean p = ((Boolean) pinned.elementAt(i)).booleanValue();
            if (anyPinned && !search && (i == 0 || p != lastPinned)) {
                list.section(p ? L.t("Pinned") : L.t("Recent"));
            }
            lastPinned = p;
            list.add(t[0], t[1], p ? Icons.PIN : Icons.CHAT, p ? RowList.ACCENT : 0);
        }
    }

    /**
     * A pin, unpin or delete shown before the server answers: the chat moves
     * to the top (pinned), below the pinned ones (unpinned) or goes. The
     * list fetched after the change replaces it, so a refused change comes
     * back by itself (and its reason is shown).
     */
    private void optimistic(int j, String id, boolean pin) {
        Vector t = new Vector();
        Vector p = new Vector();
        boolean search;
        synchronized (this) {
            int k = ids.indexOf(id);
            if (k < 0 || k >= shown.size()) {
                return;
            }
            Object row = shown.elementAt(k);
            ids.removeElementAt(k);
            pins.removeElementAt(k);
            shown.removeElementAt(k);
            if (j == JOB_PIN) {
                int at = 0;
                if (!pin) {
                    while (at < pins.size() && ((Boolean) pins.elementAt(at)).booleanValue()) {
                        at++;
                    }
                }
                ids.insertElementAt(id, at);
                pins.insertElementAt(pin ? Boolean.TRUE : Boolean.FALSE, at);
                shown.insertElementAt(row, at);
            }
            for (int i = 0; i < shown.size(); i++) {
                t.addElement(shown.elementAt(i));
                p.addElement(pins.elementAt(i));
            }
            search = searchMode;
        }
        render(null, search, t, p);
    }

    /**
     * List lines: pinned TAB id TAB updated-ms TAB messages TAB title (with
     * "pins: 1"), and with "models: 1" (server 0.7.0+) the model id before
     * the title (titles never contain a tab, so an older server's line
     * simply has one field less); search lines: id TAB updated-ms TAB
     * matches TAB snippet.
     */
    private static void parse(String text, boolean search, Vector ids, Vector pinned, Vector titles) {
        int pos = 0;
        int n = text.length();
        while (pos < n) {
            int nl = text.indexOf('\n', pos);
            String line = text.substring(pos, nl < 0 ? n : nl);
            pos = nl < 0 ? n : nl + 1;
            boolean pin = false;
            if (!search && line.length() > 2 && line.charAt(1) == '\t') {
                pin = line.charAt(0) == '1';
                line = line.substring(2); // a 0.3 server sends the line without the flag
            }
            int t1 = line.indexOf('\t');
            int t2 = t1 < 0 ? -1 : line.indexOf('\t', t1 + 1);
            int t3 = t2 < 0 ? -1 : line.indexOf('\t', t2 + 1);
            if (t3 < 0 || t1 != 16) {
                continue;
            }
            long updated;
            try {
                updated = Long.parseLong(line.substring(t1 + 1, t2));
            } catch (NumberFormatException e) {
                updated = 0;
            }
            String title = line.substring(t3 + 1);
            String model = "";
            int t4 = search ? -1 : title.indexOf('\t');
            if (t4 >= 0) {
                String id = title.substring(0, t4);
                title = title.substring(t4 + 1);
                model = Models.name(id).length() > 0 ? Models.name(id) : id;
            }
            ids.addElement(line.substring(0, t1));
            pinned.addElement(pin ? Boolean.TRUE : Boolean.FALSE);
            titles.addElement(new String[] { title.length() > 0 ? title : "-",
                Text.shortDate(updated) + (model.length() > 0 ? " · " + model : "") });
        }
    }

    /** {id, "1"/"0" pinned} of the selected row, or null. */
    private synchronized String[] selected() {
        int i = list.getSelectedItem();
        if (loading || i < 0 || i >= ids.size()) {
            return null;
        }
        return new String[] { (String) ids.elementAt(i), ((Boolean) pins.elementAt(i)).booleanValue() ? "1" : "0" };
    }

    public void commandAction(Command c, Displayable d) {
        Display display = midlet.display();
        if (d == searchBox) {
            if (c == findCmd) {
                String q = searchBox.getString().trim();
                if (q.length() < 2) {
                    midlet.info(L.t("Type at least 2 letters."), searchBox);
                    return;
                }
                display.setCurrent(list);
                setQuery(q);
                start(JOB_SEARCH, null, false);
            } else {
                display.setCurrent(list);
            }
            return;
        }
        if (d == confirm) {
            String id;
            synchronized (this) {
                id = jobId;
            }
            display.setCurrent(list);
            if (c == yesCmd && id != null) {
                start(JOB_DELETE, id, false);
            }
            return;
        }
        if (c == backCmd) {
            midlet.showMenu();
        } else if (c == refreshCmd) {
            start(searchMode ? JOB_SEARCH : JOB_LIST, null, false);
        } else if (c == allCmd) {
            setQuery(null);
            start(JOB_LIST, null, false);
        } else if (c == searchCmd) {
            if (searchBox == null) {
                searchBox = new TextBox(L.t("Search chats"), "", 100, TextField.ANY);
                searchBox.addCommand(findCmd);
                searchBox.addCommand(noCmd);
                searchBox.setCommandListener(this);
            }
            display.setCurrent(searchBox);
        } else if (c == pinCmd) {
            String[] sel = selected();
            if (sel != null) {
                start(JOB_PIN, sel[0], !"1".equals(sel[1]));
            }
        } else if (c == deleteCmd) {
            String[] sel = selected();
            if (sel == null) {
                return;
            }
            synchronized (this) {
                jobId = sel[0];
            }
            confirm = TextPage.message(L.t("Delete chat"), L.t("Delete this chat from the server? This cannot be undone."));
            confirm.addCommand(yesCmd);
            confirm.addCommand(noCmd);
            confirm.setCommandListener(this);
            display.setCurrent(confirm);
        } else if (c == openCmd) {
            int act;
            synchronized (this) {
                act = loading ? 0 : cta;
            }
            if (act != 0 && list.getSelectedItem() == 0) {
                if (act == CTA_NEW) {
                    midlet.startNewChat(list);
                } else if (act == CTA_SEARCH) {
                    commandAction(searchCmd, list);
                } else {
                    commandAction(refreshCmd, list);
                }
                return;
            }
            String[] sel = selected();
            if (sel != null) {
                midlet.openConversation(sel[0]);
            }
        }
    }
}

package io.github.emir.claudes40;

import java.util.Vector;

import javax.microedition.lcdui.Alert;
import javax.microedition.lcdui.AlertType;
import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.CommandListener;
import javax.microedition.lcdui.Display;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.Font;
import javax.microedition.lcdui.Image;
import javax.microedition.lcdui.List;
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
    private final List list;
    private final Command openCmd = new Command(L.s("Aç", "Open"), Command.OK, 1);
    private final Command searchCmd = new Command(L.s("Sohbetlerde ara", "Search chats"), Command.SCREEN, 2);
    private final Command pinCmd = new Command(L.s("Sabitle / kaldır", "Pin / unpin"), Command.SCREEN, 3);
    private final Command deleteCmd = new Command(L.s("Sil", "Delete"), Command.SCREEN, 4);
    private final Command allCmd = new Command(L.s("Tüm sohbetler", "All chats"), Command.SCREEN, 2);
    private final Command refreshCmd = new Command(L.s("Yenile", "Refresh"), Command.SCREEN, 5);
    private final Command backCmd = new Command(L.s("Geri", "Back"), Command.BACK, 1);
    private final Command findCmd = new Command(L.s("Ara", "Search"), Command.OK, 1);
    private final Command yesCmd = new Command(L.s("Sil", "Delete"), Command.OK, 1);
    private final Command noCmd = new Command(L.s("Vazgeç", "Cancel"), Command.BACK, 1);
    private TextBox searchBox;
    private Alert confirm;
    private Image pinIcon;
    private Image noIcon;

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

    ChatList(ClaudeS40MIDlet midlet) {
        this.midlet = midlet;
        list = new List(L.s("Sohbetler", "Chats"), List.IMPLICIT);
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
        list.setTitle(search ? L.s("Ara: ", "Search: ") + q : L.s("Sohbetler", "Chats"));
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
            list.append(j == JOB_SEARCH ? L.s("Aranıyor...", "Searching...") : L.s("Yükleniyor...", "Loading..."), null);
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
                actionError = L.s("En fazla " + r.msg.field("max") + " sohbet sabitlenebilir. Önce birini kaldırın.",
                        "At most " + r.msg.field("max") + " chats can be pinned. Unpin one first.");
            } else if (j == JOB_DELETE && ("deleted".equals(st) || "conversation_not_found".equals(st))) {
                midlet.chatDeleted(id);
            } else if (!"ok".equals(st)) {
                actionError = (j == JOB_PIN ? L.s("Sabitlenemedi (", "Could not pin (") : L.s("Silinemedi (", "Could not delete ("))
                        + (st.length() > 0 ? st : "?") + ").";
            }
            list.deleteAll();
            list.append(q != null ? L.s("Aranıyor...", "Searching...") : L.s("Yükleniyor...", "Loading..."), null);
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
                    ? L.s("Sunucu aramayı bilmiyor (sunucu 0.4.0 gerekli).", "The server cannot search (needs server 0.4.0).")
                    : L.s("Liste alınamadı (", "Could not get the list (") + st + ").";
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
        list.deleteAll();
        if (error != null) {
            list.append(L.s("Hata: ", "Error: ") + error, null);
        } else if (titles.size() == 0) {
            list.append(search ? L.s("Bulunamadı. Türkçe harf gerekmez: 'sise' de 'şişe'yi bulur.",
                    "Nothing found. Plain letters are fine: 'sise' finds 'şişe'.")
                    : L.s("Henüz sohbet yok. Sohbetler sunucuda 30 gün kalır, sabitlenenler kaldırılana kadar.",
                            "No chats yet. The server keeps chats for 30 days, pinned ones until unpinned."), null);
        }
        for (int i = 0; i < titles.size(); i++) {
            list.append((String) titles.elementAt(i), ((Boolean) pinned.elementAt(i)).booleanValue() ? pin() : blank());
        }
        if (actionError != null) {
            midlet.info(actionError, list);
        }
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
            titles.addElement(Text.shortDate(updated) + " · " + (model.length() > 0 ? model + " · " : "")
                    + (title.length() > 0 ? title : "-"));
        }
    }

    /** A small pin in the accent colour; rows without a pin get a clear image of the same size. */
    private Image pin() {
        if (pinIcon == null) {
            pinIcon = Icons.get(Icons.PIN, iconSize(), Theme.accent);
        }
        return pinIcon;
    }

    private Image blank() {
        if (noIcon == null) {
            int s = iconSize();
            noIcon = Image.createRGBImage(new int[s * s], s, s, true);
        }
        return noIcon;
    }

    private static int iconSize() {
        return Math.max(10, Math.min(16, Font.getDefaultFont().getHeight() * 3 / 4));
    }

    /** {id, "1"/"0" pinned} of the selected row, or null. */
    private synchronized String[] selected() {
        int i = list.getSelectedIndex();
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
                    midlet.info(L.s("En az 2 harf yazın.", "Type at least 2 letters."), searchBox);
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
                searchBox = new TextBox(L.s("Sohbetlerde ara", "Search chats"), "", 100, TextField.ANY);
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
            confirm = new Alert(L.s("Sohbeti sil", "Delete chat"), L.s("Bu sohbet sunucudan silinsin mi? Geri alınamaz.",
                    "Delete this chat from the server? This cannot be undone."), null, AlertType.WARNING);
            confirm.setTimeout(Alert.FOREVER);
            confirm.addCommand(yesCmd);
            confirm.addCommand(noCmd);
            confirm.setCommandListener(this);
            display.setCurrent(confirm);
        } else if (c == openCmd) {
            String[] sel = selected();
            if (sel != null) {
                midlet.openConversation(sel[0]);
            }
        }
    }
}

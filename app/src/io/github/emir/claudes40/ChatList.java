package io.github.emir.claudes40;

import java.util.Vector;

import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.CommandListener;
import javax.microedition.lcdui.Display;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.List;

/**
 * "Sohbetler" / "Chats": the newest conversations kept on the server
 * (/v1/conversations), in the phone's own List. Selecting one opens it in
 * the chat screen (/v1/history) where it can be continued. Loading runs on a
 * worker thread; nothing here calls Claude.
 */
final class ChatList implements CommandListener, Runnable {

    private final ClaudeS40MIDlet midlet;
    private final List list;
    private final Command openCmd = new Command(L.s("Aç", "Open"), Command.OK, 1);
    private final Command refreshCmd = new Command(L.s("Yenile", "Refresh"), Command.SCREEN, 2);
    private final Command backCmd = new Command(L.s("Geri", "Back"), Command.BACK, 1);
    /** Conversation ids in list order; empty while loading or on error. */
    private final Vector ids = new Vector();
    private boolean loading;

    ChatList(ClaudeS40MIDlet midlet) {
        this.midlet = midlet;
        list = new List(L.s("Sohbetler", "Chats"), List.IMPLICIT);
        list.setSelectCommand(openCmd);
        list.addCommand(refreshCmd);
        list.addCommand(backCmd);
        list.setCommandListener(this);
    }

    void show(Display display) {
        display.setCurrent(list);
        load();
    }

    private void load() {
        synchronized (this) {
            if (loading) {
                return;
            }
            loading = true;
            ids.removeAllElements();
        }
        list.deleteAll();
        list.append(L.s("Yükleniyor...", "Loading..."), null);
        new Thread(this).start();
    }

    public void run() {
        Settings s = midlet.settings;
        Net.Result r = Net.request(s.url + "/v1/conversations", "POST", s.token, S40Message.format(new String[0],
                new String[0], ""), midlet.userAgent(), null);
        Vector titles = new Vector();
        Vector found = new Vector();
        String error = null;
        S40Message m = r.msg;
        if (!r.ok()) {
            error = Net.explain(r);
        } else if (m == null || !"ok".equals(m.field("status"))) {
            error = L.s("Liste alınamadı (", "Could not get the list (") + (m == null ? "?" : m.field("status")) + ").";
        } else {
            parse(m.text, found, titles);
        }
        synchronized (this) {
            loading = false;
            ids.removeAllElements();
            for (int i = 0; i < found.size(); i++) {
                ids.addElement(found.elementAt(i));
            }
        }
        list.deleteAll();
        if (error != null) {
            list.append(L.s("Hata: ", "Error: ") + error, null);
        } else if (titles.size() == 0) {
            list.append(L.s("Henüz sohbet yok. Sohbetler sunucuda 30 gün kalır.",
                    "No chats yet. The server keeps chats for 30 days."), null);
        }
        for (int i = 0; i < titles.size(); i++) {
            list.append((String) titles.elementAt(i), null);
        }
    }

    /** Lines: id TAB updated-ms TAB messages TAB title. */
    private static void parse(String text, Vector ids, Vector titles) {
        int pos = 0;
        int n = text.length();
        while (pos < n) {
            int nl = text.indexOf('\n', pos);
            String line = text.substring(pos, nl < 0 ? n : nl);
            pos = nl < 0 ? n : nl + 1;
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
            ids.addElement(line.substring(0, t1));
            titles.addElement(Text.shortDate(updated) + " · " + (title.length() > 0 ? title : "-"));
        }
    }

    public void commandAction(Command c, Displayable d) {
        if (c == backCmd) {
            midlet.showMenu();
        } else if (c == refreshCmd) {
            load();
        } else if (c == openCmd) {
            String id = null;
            synchronized (this) {
                int i = list.getSelectedIndex();
                if (!loading && i >= 0 && i < ids.size()) {
                    id = (String) ids.elementAt(i);
                }
            }
            if (id != null) {
                midlet.openConversation(id);
            }
        }
    }
}

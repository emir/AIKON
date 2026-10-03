package io.github.emir.claudes40;

import java.util.Calendar;
import java.util.Date;
import java.util.Timer;
import java.util.TimerTask;
import java.util.Vector;

import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.CommandListener;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.Font;
import javax.microedition.lcdui.Graphics;

/**
 * Conversation view with chat bubbles: the user on the right (accent), Claude
 * on the left (surface), info/error notes centred. While a reply is on its
 * way an animated "Claude yazıyor" bubble with the elapsed seconds is shown.
 * Replies keep their paragraphs and lists (dots / numbers with a hanging
 * indent); a reply with a further part on the server ends in a "0 · the
 * rest" row.
 *
 * Reading mode (7 or "Okuma modu") shows one reply as pages: full width, no
 * bubbles, only whole lines on screen, a thin header with the page number and
 * a progress line. The reading position is kept by character offset, so it
 * survives loading the rest of the reply and changing the text size (9).
 * With Settings > Screen > Full screen (default) the canvas covers the
 * phone's status bar; its own top bar stays and the softkeys are still the
 * phone's Commands.
 * The backlight is left to the phone: Display.flashBacklight, the only MIDP
 * way to keep it on, blinks a lit screen on the Nokia 6300.
 *
 * 1/3 select a message (a ring around it); the centre key then opens its
 * actions (ClaudeS40MIDlet.showActions), after an error it retries the same
 * request, otherwise it opens the editor. Other scrolling ends a selection.
 *
 * Softkeys are standard Commands; scrolling uses getGameAction(). Number
 * keys are checked before game actions (Nokia maps 2/4/6/8/5 to both);
 * QWERTY letters are mapped to digits by Keys first. The Shortcuts screen
 * (midlet.showShortcuts) lists them. Sizes come from
 * getWidth()/getHeight() and font metrics only; single-line texts are cut
 * with "..." (Text.fit) so no string runs off the screen.
 */
final class ChatCanvas extends Canvas implements CommandListener, ChatSession.View {

    private static final int PAD = 6;
    private static final int BUBBLE_PAD = 6;
    private static final int ARC = 16;
    /** Side margin in reading mode. */
    private static final int RPAD = 8;
    private static final int TOAST_MS = 1600;
    /** After this many seconds with web search on, say that searching takes time. */
    private static final int SLOW_SECONDS = 15;

    private final ClaudeS40MIDlet midlet;
    private final ChatSession session;

    final Command writeCmd = new Command(L.t("Write"), Command.SCREEN, 1);
    final Command dictateCmd = new Command(L.t("Dictate"), Command.SCREEN, 2);
    final Command photoCmd = new Command(L.t("Add a photo"), Command.SCREEN, 2);
    final Command moreCmd = new Command(L.t("Show the rest"), Command.SCREEN, 1);
    final Command readCmd = new Command(L.t("Reading mode"), Command.SCREEN, 5);
    final Command promptsCmd = new Command(L.t("Quick prompts"), Command.SCREEN, 4);
    final Command retryCmd = new Command(L.t("Retry"), Command.SCREEN, 1);
    final Command chatsCmd = new Command(L.t("Chats"), Command.SCREEN, 4);
    final Command newCmd = new Command(L.t("New chat"), Command.SCREEN, 3);
    final Command modelCmd = new Command(L.t("Model"), Command.SCREEN, 3);
    final Command deleteCmd = new Command(L.t("Delete chat"), Command.SCREEN, 7);
    final Command keysCmd = new Command(L.t("Shortcuts"), Command.SCREEN, 6);
    final Command actionsCmd = new Command(L.t("Message actions"), Command.SCREEN, 1);
    final Command backCmd = new Command(L.t("Menu"), Command.BACK, 1);
    final Command closeCmd = new Command(L.t("Close"), Command.BACK, 1);

    /** Commands in the order they are added (the phone lists them in this order). */
    // Options order: priority, then this order (most used first, deleting last)
    private final Command[] all = { actionsCmd, writeCmd, moreCmd, retryCmd, dictateCmd, photoCmd, newCmd, modelCmd, promptsCmd,
        chatsCmd, readCmd, keysCmd, deleteCmd, backCmd, closeCmd };
    private final boolean[] shown = new boolean[all.length];

    /** One laid-out message. */
    /** Block kind of a day heading between messages (not an entry). */
    private static final int KIND_DAY = -1;

    private static final class Block {
        int kind;
        int uid;
        /** Text.Line objects. */
        Vector lines;
        int textH;
        String meta;
        /** "0 · the rest" / "reply shortened" row at the bottom, or null. */
        String footer;
        boolean truncated;
        boolean more;
        /** A note with an explanation behind it: selectable, drawn with an "i". */
        boolean detail;
        /** Top of the text relative to y. */
        int textTop;
        int y;
        int h;
        int bw;
    }

    // chat view
    private final Vector blocks = new Vector();
    private int builtVersion = -1;
    private int builtWidth = -1;
    private int builtStyle = -1;
    private int contentH;
    private int scroll;
    private boolean jumpToLast;
    private boolean followTyping;

    // reading mode
    private boolean reading;
    private int readUid;
    /** Text.Line objects of the reply being read, plus the footer line if any. */
    private final Vector rLines = new Vector();
    private Text.Line rFooter;
    private ChatSession.Entry rEntry;
    private int rTop;
    /** Character offset to restore after the next reading layout; -1 if none. */
    private int rAnchor = -1;
    private int rBuiltVersion = -1;
    private int rBuiltWidth = -1;
    private int rBuiltStyle = -1;
    private boolean newWhileReading;

    /** uid of the selected message (1/3), 0 if none. */
    private int sel;

    private Timer anim;
    private int animFrame;
    private String toast;
    private long toastUntil;

    ChatCanvas(ClaudeS40MIDlet midlet, ChatSession session) {
        this.midlet = midlet;
        this.session = session;
        setCommandListener(this);
        session.setView(this);
        updateCommands();
    }

    public void commandAction(Command c, Displayable d) {
        midlet.userActive();
        String err = null;
        if (c == writeCmd) {
            err = write();
        } else if (c == dictateCmd) {
            midlet.showDictation(false);
        } else if (c == photoCmd) {
            midlet.showPhoto(false);
        } else if (c == promptsCmd) {
            midlet.showPrompts();
        } else if (c == retryCmd) {
            err = session.retry();
        } else if (c == moreCmd) {
            err = session.more(readingUid());
        } else if (c == readCmd) {
            enterReading();
        } else if (c == keysCmd) {
            midlet.showShortcuts(this);
        } else if (c == chatsCmd) {
            midlet.showChats();
        } else if (c == newCmd) {
            midlet.startNewChat(this);
        } else if (c == modelCmd) {
            midlet.showModels(Models.FOR_SWITCH, this);
        } else if (c == deleteCmd) {
            err = session.deleteChat();
        } else if (c == closeCmd) {
            exitReading();
        } else if (c == actionsCmd) {
            actions();
        } else if (c == backCmd) {
            midlet.showMenu();
        }
        if (err != null) {
            midlet.info(err, this);
        }
    }

    /** Opens the editor (leaves reading mode). */
    private String write() {
        if (session.busy()) {
            return L.t("A request is still running.");
        }
        if (reading) {
            exitReading();
        }
        midlet.showComposer(null);
        return null;
    }

    private synchronized int readingUid() {
        return reading ? readUid : 0;
    }

    public void sessionChanged(boolean newReply) {
        boolean calendar = false;
        synchronized (this) {
            if (newReply) {
                jumpToLast = true;
                if (reading) {
                    newWhileReading = true;
                } else if (ClaudeS40MIDlet.hasPim()) {
                    // a reply with a calendar entry: select it, so the centre key offers "Add to calendar"
                    ChatSession.Entry[] es = session.snapshot();
                    ChatSession.Entry last = es.length > 0 ? es[es.length - 1] : null;
                    if (last != null && isReply(last.kind) && Cal.parse(last.text) != null) {
                        sel = last.uid;
                        calendar = true;
                    }
                }
            }
            if (session.typing()) {
                followTyping = true;
            }
            if (sel != 0 && !hasEntry(sel)) {
                sel = 0;
            }
            if (reading && !hasEntry(readUid)) {
                reading = false; // the reply left the transcript (RAM limit)
                newWhileReading = false;
            }
        }
        if (newReply) {
            midlet.replyFeedback();
        }
        updateCommands();
        updateAnimation();
        if (calendar) {
            toast(L.t("Centre key: add to calendar"));
        } else {
            repaint();
        }
    }

    private synchronized void updateCommands() {
        boolean idle = !session.busy();
        boolean replies = hasReply();
        for (int i = 0; i < all.length; i++) {
            Command c = all[i];
            boolean want;
            if (c == moreCmd) {
                want = reading ? session.canMore(readUid) : session.canMore();
            } else if (c == retryCmd) {
                want = !reading && session.canRetry();
            } else if (c == readCmd) {
                want = !reading && replies;
            } else if (c == closeCmd) {
                want = reading;
            } else if (c == actionsCmd) {
                want = !reading && sel != 0;
            } else if (c == dictateCmd) {
                want = !reading && ClaudeS40MIDlet.hasRecording();
            } else if (c == photoCmd) {
                want = !reading && ClaudeS40MIDlet.hasPhotoSource();
            } else if (c == writeCmd || c == keysCmd) {
                want = true;
            } else {
                want = !reading;
            }
            if (c == writeCmd && reading && !idle) {
                want = false;
            }
            if (want != shown[i]) {
                if (want) {
                    addCommand(c);
                } else {
                    removeCommand(c);
                }
                shown[i] = want;
            }
        }
    }

    private boolean hasEntry(int uid) {
        ChatSession.Entry[] es = session.snapshot();
        for (int i = 0; i < es.length; i++) {
            if (es[i].uid == uid) {
                return true;
            }
        }
        return false;
    }

    private boolean hasReply() {
        ChatSession.Entry[] es = session.snapshot();
        for (int i = 0; i < es.length; i++) {
            if (isReply(es[i].kind)) {
                return true;
            }
        }
        return false;
    }

    /** Timer runs only while a reply is awaited and the chat is visible. */
    private synchronized void updateAnimation() {
        boolean want = session.busy() && isShown();
        if (want && anim == null) {
            anim = new Timer();
            anim.schedule(new TimerTask() {
                public void run() {
                    tick();
                }
            }, 350, 350);
        } else if (!want && anim != null) {
            anim.cancel();
            anim = null;
        }
    }

    private void tick() {
        boolean r;
        synchronized (this) {
            animFrame++;
            r = reading;
        }
        if (r) {
            repaint(0, 0, getWidth(), readHeadH()); // only the header changes
        } else {
            repaint();
        }
    }

    protected void showNotify() {
        updateCommands();
        updateAnimation();
    }

    protected void hideNotify() {
        synchronized (this) {
            if (anim != null) {
                anim.cancel();
                anim = null;
            }
        }
    }

    protected synchronized void sizeChanged(int w, int h) {
        builtWidth = -1;
        rBuiltWidth = -1;
        repaint();
    }

    /** A short note over the bottom of the screen for TOAST_MS. */
    void toast(String text) {
        synchronized (this) {
            toast = text;
            toastUntil = System.currentTimeMillis() + TOAST_MS;
        }
        final Timer t = new Timer();
        t.schedule(new TimerTask() {
            public void run() {
                t.cancel();
                repaint();
            }
        }, TOAST_MS + 50);
        repaint();
    }

    // ------------------------------------------------------------ keys

    protected void keyPressed(int keyCode) {
        key(keyCode, false);
    }

    protected void keyRepeated(int keyCode) {
        key(keyCode, true);
    }

    private void key(int keyCode, boolean repeat) {
        keyCode = Keys.map(keyCode);
        midlet.userActive();
        String err = null;
        if (keyCode == KEY_NUM5) {
            if (!repeat && !session.busy()) {
                err = write();
            }
        } else if (keyCode == KEY_NUM0) {
            int uid = readingUid();
            if (!repeat && (uid != 0 ? session.canMore(uid) : session.canMore())) {
                err = session.more(uid);
            }
        } else if (keyCode == KEY_NUM7) {
            if (!repeat) {
                if (readingUid() != 0) {
                    exitReading();
                } else {
                    enterReading();
                }
            }
        } else if (keyCode == KEY_NUM9) {
            if (!repeat) {
                cycleTextSize();
            }
        } else if (reading ? !readingKey(keyCode) : !chatKey(keyCode)) {
            return;
        }
        if (err != null) {
            midlet.info(err, this);
        }
    }

    /** Chat view: scrolling keys. Returns false if the key is not used. */
    private boolean chatKey(int keyCode) {
        int action = Keys.action(this, keyCode);
        if (action == FIRE) {
            // a selected message: its actions; after an error: retry; else write
            if (selected() != 0) {
                actions();
            } else if (session.canRetry()) {
                String err = session.retry();
                if (err != null) {
                    midlet.info(err, this);
                }
            } else if (!session.busy()) {
                write();
            }
            return true;
        }
        if (keyCode == KEY_NUM1 || keyCode == KEY_NUM3) {
            selectMessage(keyCode == KEY_NUM3);
            updateCommands();
            repaint();
            return true;
        }
        boolean hadSel;
        synchronized (this) {
            hadSel = sel != 0;
            int line = Theme.font.getHeight();
            int page = Math.max(line, viewH() - line);
            switch (keyCode) {
            case KEY_NUM2:
                scroll -= page;
                break;
            case KEY_NUM8:
                scroll += page;
                break;
            case KEY_STAR:
                scroll = 0;
                break;
            case KEY_POUND:
                scroll = Integer.MAX_VALUE / 2; // clamped in paint()
                break;
            default:
                if (action == UP) {
                    scroll -= line;
                } else if (action == DOWN) {
                    scroll += line;
                } else if (action == LEFT) {
                    scroll -= page;
                } else if (action == RIGHT) {
                    scroll += page;
                } else {
                    return false; // unused keys (e.g. Fn) keep the selection
                }
            }
            sel = 0; // plain scrolling ends the selection
            followTyping = false;
            jumpToLast = false;
        }
        if (hadSel) {
            updateCommands();
        }
        repaint();
        return true;
    }

    /** Reading mode: paging keys. Returns false if the key is not used. */
    private boolean readingKey(int keyCode) {
        int action = Keys.action(this, keyCode);
        boolean loadMore = false;
        synchronized (this) {
            readLayout(getWidth());
            if (!reading) {
                return false;
            }
            int n = rLines.size();
            switch (keyCode) {
            case KEY_NUM2:
                rTop = pageBack(rTop);
                break;
            case KEY_NUM8:
                rTop = pageForward(rTop);
                break;
            case KEY_STAR:
                rTop = 0;
                break;
            case KEY_POUND:
                rTop = pageBack(n);
                break;
            case KEY_NUM1:
            case KEY_NUM3:
                otherReply(keyCode == KEY_NUM3);
                break;
            default:
                if (action == UP) {
                    rTop = skipGap(rTop - 1, -1);
                } else if (action == DOWN) {
                    if (visibleEnd(rTop) < n) {
                        rTop = skipGap(rTop + 1, 1);
                    }
                } else if (action == LEFT) {
                    rTop = pageBack(rTop);
                } else if (action == RIGHT) {
                    rTop = pageForward(rTop);
                } else if (action == FIRE) {
                    if (visibleEnd(rTop) >= n) {
                        loadMore = session.canMore(readUid);
                    } else {
                        rTop = pageForward(rTop);
                    }
                } else {
                    return false;
                }
            }
        }
        if (loadMore) {
            String err = session.more(readingUid());
            if (err != null) {
                midlet.info(err, this);
            }
        }
        repaint();
        return true;
    }

    private synchronized int selected() {
        return sel;
    }

    /**
     * 1/3: selects the previous / next message (bubbles, and notes with an
     * explanation) and scrolls so
     * it is on screen. Without a selection it starts from the view: 3 takes
     * the first message starting in view, 1 the last one starting above it.
     */
    private synchronized void selectMessage(boolean forward) {
        layout(getWidth());
        int cur = -1;
        for (int i = 0; i < blocks.size(); i++) {
            if (((Block) blocks.elementAt(i)).uid == sel && sel != 0) {
                cur = i;
            }
        }
        int pick = -1;
        if (cur >= 0) {
            for (int i = cur + (forward ? 1 : -1); i >= 0 && i < blocks.size() && pick < 0; i += forward ? 1 : -1) {
                if (selectable((Block) blocks.elementAt(i))) {
                    pick = i;
                }
            }
            if (pick < 0) {
                pick = cur; // first / last message: stay
            }
        } else {
            for (int i = 0; i < blocks.size(); i++) {
                Block b = (Block) blocks.elementAt(i);
                if (!selectable(b)) {
                    continue;
                }
                if (forward ? b.y >= scroll - 1 : b.y < scroll) {
                    pick = i;
                    if (forward) {
                        break;
                    }
                }
            }
            if (pick < 0) {
                for (int i = 0; i < blocks.size() && pick < 0; i++) {
                    if (selectable((Block) blocks.elementAt(i))) {
                        pick = i;
                    }
                }
            }
        }
        if (pick < 0) {
            return;
        }
        Block b = (Block) blocks.elementAt(pick);
        sel = b.uid;
        int vh = viewH();
        if (b.y - PAD < scroll || b.h + 2 * PAD > vh) {
            scroll = b.y - PAD;
        } else if (b.y + b.h + PAD > scroll + vh) {
            scroll = b.y + b.h + PAD - vh;
        }
        followTyping = false;
        jumpToLast = false;
    }

    /** Actions for the selected message (ClaudeS40MIDlet.showActions). */
    private void actions() {
        ChatSession.Entry e = session.entry(selected());
        if (e == null) {
            synchronized (this) {
                sel = 0;
            }
            updateCommands();
            repaint();
            return;
        }
        if (e.kind == ChatSession.KIND_INFO || e.kind == ChatSession.KIND_ERROR) {
            midlet.showNote(e.text, e.detail.length() > 0 ? e.detail : e.text, this);
            return;
        }
        midlet.showActions(e);
    }

    /** From the actions list: the reply with this uid in reading mode. */
    void read(int uid) {
        synchronized (this) {
            if (reading) {
                reading = false;
            }
            sel = 0;
            reading = true;
            readUid = uid;
            rAnchor = 0;
            rTop = 0;
            rBuiltVersion = -1;
            newWhileReading = false;
        }
        updateCommands();
        repaint();
    }

    /** 9: small, medium, large, small... saved like Settings > Text size; keeps the position. */
    private void cycleTextSize() {
        Settings s = midlet.settings;
        synchronized (this) {
            int[] anchor = reading ? null : chatAnchor();
            if (reading && rTop < rLines.size()) {
                rAnchor = ((Text.Line) rLines.elementAt(rTop)).off;
            }
            s.fontSize = (s.fontSize + 1) % 3;
            Theme.apply(s);
            if (anchor != null) {
                layout(getWidth());
                restoreChatAnchor(anchor[0], anchor[1]);
            }
        }
        String err = s.save();
        midlet.applyLook();
        toast(err != null ? err : L.t("Text: ") + (s.fontSize == 0 ? L.t("Small")
                : s.fontSize == 2 ? L.t("Large") : L.t("Medium")));
    }

    // ------------------------------------------------------------ chat layout

    private int barH() {
        return Theme.bold.getHeight() + 8;
    }

    private int statusH() {
        return Theme.small.getHeight() + 14;
    }

    private int viewH() {
        return getHeight() - barH() - statusH();
    }

    private static int style() {
        return Theme.bg * 31 + Theme.font.getHeight();
    }

    private void layout(int w) {
        int v = session.version();
        if (v == builtVersion && w == builtWidth && builtStyle == style()) {
            return;
        }
        builtVersion = v;
        builtWidth = w;
        builtStyle = style();
        blocks.removeAllElements();
        ChatSession.Entry[] es = session.snapshot();
        Font f = Theme.font;
        Font sm = Theme.small;
        int maxBubble = w * 84 / 100;
        int y = PAD;
        int lastDay = 0;
        int today = Text.dayKey(System.currentTimeMillis());
        for (int i = 0; i < es.length; i++) {
            ChatSession.Entry e = es[i];
            if (isBubble(e.kind) && e.time > 0) {
                // a quiet day heading where the day changes (not over a chat of today only)
                int day = Text.dayKey(e.time);
                if (day != lastDay && (lastDay != 0 || day != today)) {
                    Block d = new Block();
                    d.kind = KIND_DAY;
                    d.meta = Text.dayLabel(e.time);
                    d.lines = new Vector();
                    d.y = y;
                    d.h = sm.getHeight() + 4;
                    d.bw = w - 2 * PAD;
                    y += d.h + PAD;
                    blocks.addElement(d);
                }
                lastDay = day;
            }
            Block b = new Block();
            b.kind = e.kind;
            b.uid = e.uid;
            b.truncated = e.truncated;
            b.more = e.more();
            b.lines = new Vector();
            boolean bubble = isBubble(e.kind);
            boolean mine = e.kind == ChatSession.KIND_USER;
            if (bubble) {
                // your messages in a bubble on the right, replies full width without one
                Text.layout(Cal.shown(e.text), f, (mine ? maxBubble : w - 2 * PAD) - 2 * BUBBLE_PAD, b.lines);
            } else {
                // notes: one short line (small for info), the explanation on request
                b.detail = e.detail.length() > 0;
                Font nf = e.kind == ChatSession.KIND_ERROR ? f : sm;
                Vector plain = new Vector();
                Text.wrap(e.text, nf, w - 4 * PAD - (b.detail ? iconSize(nf) + 4 : 0), plain);
                for (int k = 0; k < plain.size(); k++) {
                    Text.Line l = new Text.Line();
                    l.s = (String) plain.elementAt(k);
                    b.lines.addElement(l);
                }
            }
            int widest = 0;
            Font lf = bubble || e.kind == ChatSession.KIND_ERROR ? f : sm;
            for (int k = 0; k < b.lines.size(); k++) {
                Text.Line l = (Text.Line) b.lines.elementAt(k);
                b.textH += Text.lineH(l, lf);
                widest = Math.max(widest, l.x + f.stringWidth(l.s));
            }
            if (bubble) {
                String who = e.kind == ChatSession.KIND_USER ? L.t("You")
                        : e.kind == ChatSession.KIND_CLAUDE ? replyName(e) : L.t("Test mode · fake");
                b.meta = mine ? (e.time > 0 ? hhmm(e.time) : "")
                        : who + (e.time > 0 ? " · " + hhmm(e.time) : "") + (e.searched > 0 ? " · web" : "");
                b.footer = e.truncated ? L.t("Reply shortened")
                        : b.more ? L.t("0 · Show the rest") : null;
                int metaW = sm.stringWidth(b.meta) + (e.kind == ChatSession.KIND_CLAUDE ? 12 : 0);
                int footW = b.footer == null ? 0 : sm.stringWidth(b.footer);
                b.bw = mine ? Math.min(Math.max(widest, Math.max(metaW, footW)) + 2 * BUBBLE_PAD, maxBubble) : w - 2 * PAD;
                b.textTop = BUBBLE_PAD + (b.meta.length() > 0 ? sm.getHeight() + 1 : 0);
                b.h = b.textTop + b.textH + (b.footer == null ? 0 : sm.getHeight() + 5) + BUBBLE_PAD;
            } else {
                b.bw = w - 2 * PAD;
                // the newest note of a request that can be retried says how
                b.footer = i == es.length - 1 && session.canRetry()
                        ? L.t("Centre key · Retry") : null;
                int pad = e.kind == ChatSession.KIND_ERROR ? BUBBLE_PAD : 2;
                b.textTop = pad;
                b.h = b.textH + 2 * pad + (b.footer == null ? 0 : sm.getHeight() + 5);
            }
            b.y = y;
            y += b.h + PAD;
            blocks.addElement(b);
        }
        contentH = y;
    }

    /** Messages, and notes with an explanation behind them. */
    private static boolean selectable(Block b) {
        return isBubble(b.kind) || b.detail;
    }

    private static boolean isBubble(int kind) {
        return kind == ChatSession.KIND_USER || isReply(kind);
    }

    private static boolean isReply(int kind) {
        return kind == ChatSession.KIND_CLAUDE || kind == ChatSession.KIND_TEST;
    }

    private static String hhmm(long ms) {
        Calendar c = Calendar.getInstance();
        c.setTime(new Date(ms));
        int hh = c.get(Calendar.HOUR_OF_DAY);
        int mm = c.get(Calendar.MINUTE);
        return (hh < 10 ? "0" : "") + hh + ":" + (mm < 10 ? "0" : "") + mm;
    }

    /** {uid, character offset} of the text at the top of the chat view, or null. */
    private int[] chatAnchor() {
        layout(getWidth());
        Font f = Theme.font;
        for (int i = 0; i < blocks.size(); i++) {
            Block b = (Block) blocks.elementAt(i);
            if (b.y + b.h <= scroll || b.kind == KIND_DAY) {
                continue;
            }
            int ly = b.y + b.textTop;
            for (int k = 0; k < b.lines.size(); k++) {
                Text.Line l = (Text.Line) b.lines.elementAt(k);
                if (ly >= scroll) {
                    return new int[] { b.uid, l.off };
                }
                ly += Text.lineH(l, f);
            }
            return new int[] { b.uid, 0 };
        }
        return null;
    }

    /** Scrolls so the line holding `off` of message `uid` is at the top. */
    private void restoreChatAnchor(int uid, int off) {
        Font f = Theme.font;
        for (int i = 0; i < blocks.size(); i++) {
            Block b = (Block) blocks.elementAt(i);
            if (b.uid != uid) {
                continue;
            }
            int ly = b.y + b.textTop;
            int best = b.y - PAD;
            for (int k = 0; k < b.lines.size() && off > 0; k++) {
                Text.Line l = (Text.Line) b.lines.elementAt(k);
                if (l.off > off) {
                    break;
                }
                best = k == 0 ? b.y - PAD : ly;
                ly += Text.lineH(l, f);
            }
            scroll = Math.max(0, best);
            jumpToLast = false;
            followTyping = false;
            return;
        }
    }

    // ------------------------------------------------------------ reading mode

    private void enterReading() {
        synchronized (this) {
            if (reading) {
                return;
            }
            layout(getWidth());
            // at the end with the last reply's start on screen: that reply;
            // otherwise the reply at the upper third of the view, or the next
            // one if it starts on screen, or the one before
            int vh = viewH();
            int maxScroll = Math.max(0, contentH + (session.typing() ? typingHeight() : 0) - vh);
            int last = -1;
            for (int i = 0; i < blocks.size(); i++) {
                if (isReply(((Block) blocks.elementAt(i)).kind)) {
                    last = i;
                }
            }
            int target = -1;
            if (last >= 0 && scroll >= maxScroll - 1) {
                Block b = (Block) blocks.elementAt(last);
                if (b.y >= scroll && b.y < scroll + vh) {
                    target = last;
                }
            }
            int y0 = scroll + vh / 3;
            int at = blocks.size() - 1;
            for (int i = 0; i < blocks.size() && target < 0; i++) {
                Block b = (Block) blocks.elementAt(i);
                if (b.y + b.h + PAD > y0) {
                    at = i;
                    break;
                }
            }
            if (target < 0 && at >= 0 && isReply(((Block) blocks.elementAt(at)).kind)) {
                target = at;
            }
            for (int i = at + 1; i < blocks.size() && target < 0; i++) {
                Block b = (Block) blocks.elementAt(i);
                if (b.y >= scroll + vh) {
                    break;
                }
                if (isReply(b.kind)) {
                    target = i;
                }
            }
            for (int i = at - 1; i >= 0 && target < 0; i--) {
                if (isReply(((Block) blocks.elementAt(i)).kind)) {
                    target = i;
                }
            }
            for (int i = 0; i < blocks.size() && target < 0; i++) {
                if (isReply(((Block) blocks.elementAt(i)).kind)) {
                    target = i;
                }
            }
            if (target >= 0) {
                Block b = (Block) blocks.elementAt(target);
                int[] anchor = chatAnchor();
                reading = true;
                readUid = b.uid;
                rAnchor = anchor != null && anchor[0] == b.uid ? anchor[1] : 0;
                rBuiltVersion = -1;
                newWhileReading = false;
                sel = 0;
            }
        }
        if (!reading) {
            toast(L.t("No reply to read yet"));
            return;
        }
        updateCommands();
        repaint();
    }

    private void exitReading() {
        synchronized (this) {
            if (!reading) {
                return;
            }
            int off = rTop < rLines.size() ? ((Text.Line) rLines.elementAt(rTop)).off : 0;
            if (rTop == 0) {
                off = 0;
            }
            reading = false;
            newWhileReading = false;
            layout(getWidth());
            restoreChatAnchor(readUid, off);
        }
        updateCommands();
        repaint();
    }

    private int readHeadH() {
        return Theme.small.getHeight() + 6 + 2;
    }

    private int readAreaH() {
        return getHeight() - readHeadH() - 2 * 4;
    }

    /** Called with the lock held; leaves reading mode if the reply is gone. */
    private void readLayout(int w) {
        int v = session.version();
        if (v == rBuiltVersion && w == rBuiltWidth && rBuiltStyle == style()) {
            return;
        }
        ChatSession.Entry[] es = session.snapshot();
        ChatSession.Entry e = null;
        for (int i = 0; i < es.length; i++) {
            if (es[i].uid == readUid) {
                e = es[i];
            }
        }
        if (e == null) {
            reading = false;
            newWhileReading = false;
            builtVersion = -1;
            return;
        }
        if (rAnchor < 0 && rTop > 0 && rTop < rLines.size()) {
            rAnchor = ((Text.Line) rLines.elementAt(rTop)).off;
        }
        rBuiltVersion = v;
        rBuiltWidth = w;
        rBuiltStyle = style();
        rEntry = e;
        rLines.removeAllElements();
        Font f = Theme.font;
        String shown = Cal.shown(e.text);
        Text.layout(shown, f, w - 2 * RPAD, rLines);
        rFooter = null;
        if (e.truncated || e.more()) {
            Text.Line gap = new Text.Line();
            gap.gap = true;
            gap.s = "";
            gap.off = shown.length();
            rLines.addElement(gap);
            rFooter = new Text.Line();
            rFooter.s = "";
            rFooter.off = shown.length();
            rLines.addElement(rFooter);
        }
        int top = 0;
        if (rAnchor > 0) {
            for (int i = 0; i < rLines.size(); i++) {
                Text.Line l = (Text.Line) rLines.elementAt(i);
                if (l.off > rAnchor) {
                    break;
                }
                if (!l.gap) {
                    top = i;
                }
            }
        }
        rAnchor = -1;
        rTop = Math.min(top, pageBack(rLines.size()));
    }

    /** Index after the last line that fits completely when `top` is the first one. */
    private int visibleEnd(int top) {
        Font f = Theme.font;
        int area = readAreaH();
        int used = 0;
        int i = top;
        while (i < rLines.size()) {
            int lh = Text.lineH((Text.Line) rLines.elementAt(i), f);
            if (used + lh > area && i > top) {
                break;
            }
            used += lh;
            i++;
        }
        return i;
    }

    private int pageForward(int top) {
        int end = visibleEnd(top);
        return end >= rLines.size() ? top : skipGap(end, 1);
    }

    /** First line of the page that ends just before `end`. */
    private int pageBack(int end) {
        Font f = Theme.font;
        int area = readAreaH();
        int used = 0;
        int i = end;
        while (i > 0) {
            int lh = Text.lineH((Text.Line) rLines.elementAt(i - 1), f);
            if (used + lh > area && i < end) {
                break;
            }
            used += lh;
            i--;
        }
        return skipGap(i, 1);
    }

    /** A page never starts with a paragraph gap. */
    private int skipGap(int i, int dir) {
        int n = rLines.size();
        i = Math.max(0, Math.min(i, n - 1));
        while (i > 0 && i < n - 1 && ((Text.Line) rLines.elementAt(i)).gap) {
            i += dir;
        }
        return Math.max(0, i);
    }

    /** 1/3 in reading mode: the previous / next reply from its beginning. */
    private void otherReply(boolean forward) {
        ChatSession.Entry[] es = session.snapshot();
        int cur = -1;
        for (int i = 0; i < es.length; i++) {
            if (es[i].uid == readUid) {
                cur = i;
            }
        }
        for (int i = cur + (forward ? 1 : -1); i >= 0 && i < es.length; i += forward ? 1 : -1) {
            if (isReply(es[i].kind)) {
                readUid = es[i].uid;
                rTop = 0;
                rAnchor = 0;
                rBuiltVersion = -1;
                boolean newest = true;
                for (int k = i + 1; k < es.length; k++) {
                    newest &= !isReply(es[k].kind);
                }
                if (newest) {
                    newWhileReading = false;
                }
                return;
            }
        }
    }

    /** Page starts from the beginning: {current page, pages}. */
    private int[] pages() {
        int n = rLines.size();
        int page = 1;
        int count = 1;
        int start = 0;
        while (true) {
            int next = pageForward(start);
            if (next == start) {
                break;
            }
            count++;
            if (next <= rTop) {
                page = count;
            }
            start = next;
        }
        if (visibleEnd(rTop) >= n) {
            page = count;
        }
        return new int[] { page, count };
    }

    // ------------------------------------------------------------ paint

    protected synchronized void paint(Graphics g) {
        int w = getWidth();
        int h = getHeight();
        if (reading) {
            readLayout(w);
        }
        if (reading) {
            paintReading(g, w, h);
        } else {
            paintChat(g, w, h);
        }
        paintToast(g, w, h - (reading ? 4 : statusH()));
    }

    private void paintChat(Graphics g, int w, int h) {
        int top = barH();
        int vh = viewH();
        layout(w);

        boolean typing = session.typing();
        int typingH = typing ? typingHeight() : 0;
        int total = contentH + typingH;
        int maxScroll = Math.max(0, total - vh);
        if (jumpToLast && blocks.size() > 0) {
            // newest message's start at the top: replies are read from the beginning
            scroll = ((Block) blocks.lastElement()).y - PAD;
            jumpToLast = false;
            followTyping = false;
        } else if (typing && followTyping) {
            scroll = maxScroll;
        }
        scroll = Math.max(0, Math.min(scroll, maxScroll));

        g.setColor(Theme.bg);
        g.fillRect(0, 0, w, h);
        g.setClip(0, top, w, vh);

        if (!typing && onlyInfo()) {
            // nothing written yet (at most a few one-line notes): the welcome below them
            int used = blocks.size() == 0 ? 0 : contentH - scroll;
            paintEmpty(g, w, top, vh, used);
        }
        for (int i = 0; i < blocks.size(); i++) {
            Block b = (Block) blocks.elementAt(i);
            int y = top + b.y - scroll;
            if (y > top + vh || y + b.h < top) {
                continue;
            }
            paintBlock(g, b, w, y, top, top + vh);
        }
        if (typing) {
            paintTyping(g, w, top + contentH - scroll);
        }
        g.setClip(0, 0, w, h);

        if (total > vh) {
            int thumb = Math.max(8, vh * vh / total);
            int ty = top + (vh - thumb) * scroll / Math.max(1, maxScroll);
            g.setColor(Theme.border);
            g.fillRoundRect(w - 3, ty, 3, thumb, 3, 3);
        }

        paintHeader(g, w);
        paintStatus(g, w, h, vh);
    }

    private void paintBlock(Graphics g, Block b, int w, int y, int clipTop, int clipBottom) {
        Font f = Theme.font;
        Font sm = Theme.small;
        if (b.kind == KIND_DAY) {
            // "Today" between two hairlines
            int tw = sm.stringWidth(b.meta);
            int cy = y + b.h / 2;
            g.setColor(Theme.border);
            g.drawLine(3 * PAD, cy, (w - tw) / 2 - 6, cy);
            g.drawLine((w + tw) / 2 + 6, cy, w - 3 * PAD, cy);
            g.setFont(sm);
            g.setColor(Theme.muted);
            g.drawString(b.meta, w / 2, y + 2, Graphics.TOP | Graphics.HCENTER);
            return;
        }
        if (isBubble(b.kind)) {
            boolean mine = b.kind == ChatSession.KIND_USER;
            int x = mine ? w - PAD - b.bw : PAD;
            int fill = mine ? Theme.accent : Theme.bg;
            int text = mine ? Theme.accentInk : Theme.ink;
            if (mine) {
                g.setColor(fill);
                g.fillRoundRect(x, y, b.bw, b.h, ARC, ARC);
            } else if (b.uid == sel && sel != 0) {
                // a selected reply: a soft panel, as in the lists
                fill = Theme.selection;
                g.setColor(fill);
                g.fillRoundRect(x, y, b.bw, b.h, 10, 10);
            }
            int ty = y + BUBBLE_PAD;
            int mx = x + BUBBLE_PAD;
            g.setFont(sm);
            g.setColor(mine ? Theme.mix(Theme.accentInk, fill, 80)
                    : b.kind == ChatSession.KIND_TEST ? Theme.testBar : Theme.muted);
            g.drawString(Text.fit(b.meta, sm, x + b.bw - BUBBLE_PAD - mx), mx, ty, Graphics.TOP | Graphics.LEFT);
            g.setFont(f);
            g.setColor(text);
            paintLines(g, b.lines, x + BUBBLE_PAD, y + b.textTop, clipTop, clipBottom);
            if (b.footer != null) {
                int fy = y + b.textTop + b.textH + 2;
                g.setColor(mine ? Theme.mix(Theme.accentInk, fill, 160) : Theme.border);
                g.drawLine(x + BUBBLE_PAD, fy, x + b.bw - BUBBLE_PAD, fy);
                g.setFont(sm);
                boolean loading = b.more && session.loadingMore() == b.uid;
                g.setColor(b.truncated ? Theme.error : loading ? Theme.muted : Theme.accent);
                String t = loading ? L.t("Loading the rest...") : b.footer;
                g.drawString(Text.fit(t, sm, b.bw - 2 * BUBBLE_PAD), x + BUBBLE_PAD, fy + 2, Graphics.TOP | Graphics.LEFT);
            }
        } else {
            boolean err = b.kind == ChatSession.KIND_ERROR;
            if (err) {
                g.setColor(Theme.errorBg);
                g.fillRoundRect(PAD, y, w - 2 * PAD, b.h, ARC, ARC);
            }
            Font nf = err ? f : Theme.small;
            g.setFont(nf);
            int color = err ? Theme.error : Theme.muted;
            g.setColor(color);
            int ty = y + b.textTop;
            int icon = b.detail ? iconSize(nf) + 4 : 0;
            for (int k = 0; k < b.lines.size(); k++) {
                String s = ((Text.Line) b.lines.elementAt(k)).s;
                int lx = (w - nf.stringWidth(s) - icon) / 2;
                g.drawString(s, lx, ty, Graphics.TOP | Graphics.LEFT);
                if (b.detail && k == b.lines.size() - 1) {
                    paintInfoIcon(g, lx + nf.stringWidth(s) + 4, ty, nf, color);
                    g.setFont(nf);
                }
                ty += Text.lineH((Text.Line) b.lines.elementAt(k), nf);
            }
            if (b.footer != null) {
                int fy = ty + 2;
                g.setColor(err ? Theme.mix(Theme.errorBg, Theme.error, 60) : Theme.border);
                g.drawLine(3 * PAD, fy, w - 3 * PAD, fy);
                g.setFont(Theme.small);
                g.setColor(Theme.accent);
                String t = Theme.small.stringWidth(b.footer) <= w - 4 * PAD ? b.footer : L.t("Retry");
                g.drawString(Text.fit(t, Theme.small, w - 4 * PAD), w / 2, fy + 2, Graphics.TOP | Graphics.HCENTER);
            }
        }
        if (b.uid == sel && sel != 0 && !isReply(b.kind)) {
            paintSelection(g, b, w, y);
        }
    }

    /** Side of the "i" mark of a note with an explanation. */
    private static int iconSize(Font f) {
        return Math.max(9, f.getHeight() - 4);
    }

    /** A small circled "i" after a note: there is more to read (select it, centre key). */
    private static void paintInfoIcon(Graphics g, int x, int y, Font f, int color) {
        int s = iconSize(f);
        g.drawImage(Icons.get(Icons.INFO, s, color), x, y + (f.getHeight() - s) / 2, Graphics.TOP | Graphics.LEFT);
    }

    /** A 2-pixel ring around the selected bubble (or note). */
    private static void paintSelection(Graphics g, Block b, int w, int y) {
        boolean mine = b.kind == ChatSession.KIND_USER;
        int x = mine ? w - PAD - b.bw : PAD;
        g.setColor(mine ? Theme.ink : Theme.accent);
        g.drawRoundRect(x - 2, y - 2, b.bw + 3, b.h + 3, ARC + 2, ARC + 2);
        g.drawRoundRect(x - 3, y - 3, b.bw + 5, b.h + 5, ARC + 4, ARC + 4);
    }

    /** Draws laid-out lines from y, skipping those outside clipTop..clipBottom. */
    private static void paintLines(Graphics g, Vector lines, int x, int y, int clipTop, int clipBottom) {
        Font f = Theme.font;
        for (int k = 0; k < lines.size() && y < clipBottom; k++) {
            Text.Line l = (Text.Line) lines.elementAt(k);
            int lh = Text.lineH(l, f);
            if (y + lh > clipTop) {
                Text.draw(g, l, f, x, y);
            }
            y += lh;
        }
    }

    /** Who wrote a reply: its model's name; replies from before server 0.7.0 were Claude's. */
    private static String replyName(ChatSession.Entry e) {
        return e.model.length() > 0 ? e.model : "Claude";
    }

    /** "Claude yazıyor · 12 sn" label (the chat's model), dots bubble and, when slow, a note. */
    private int typingHeight() {
        return Theme.small.getHeight() + 2 + Theme.font.getHeight() + 2 * BUBBLE_PAD
                + (slowNote() == null ? 0 : Theme.small.getHeight() + 3) + 2 * PAD;
    }

    private String slowNote() {
        Settings s = midlet.settings;
        return session.elapsed() >= SLOW_SECONDS && s.webSearch && !s.testMode
                ? L.t("Web searches can take a while") : null;
    }

    private String typingLabel() {
        int sec = session.elapsed();
        String label = session.state() == ChatSession.STATE_SENDING ? L.t("Sending")
                : L.f("{0} is typing", session.ai());
        return sec > 0 ? label + " · " + sec + L.t(" s") : label + "...";
    }

    private void paintTyping(Graphics g, int w, int y) {
        Font sm = Theme.small;
        g.setFont(sm);
        g.setColor(Theme.muted);
        g.drawString(Text.fit(typingLabel(), sm, w - 2 * PAD), PAD + 2, y, Graphics.TOP | Graphics.LEFT);
        int by = y + sm.getHeight() + 2;
        int bh = Theme.font.getHeight() + 2 * BUBBLE_PAD;
        for (int i = 0; i < 3; i++) {
            boolean up = animFrame % 3 == i;
            g.setColor(up ? Theme.accent : Theme.border);
            int d = up ? 8 : 6;
            g.fillArc(PAD + 14 + i * 15 - d / 2, by + bh / 2 - d / 2 - (up ? 2 : 0), d, d, 0, 360);
        }
        String note = slowNote();
        if (note != null) {
            g.setFont(sm);
            g.setColor(Theme.muted);
            g.drawString(Text.fit(note, sm, w - 2 * PAD), PAD + 2, by + bh + 3, Graphics.TOP | Graphics.LEFT);
        }
    }

    /** No messages or errors yet, only info notes (or nothing). */
    private boolean onlyInfo() {
        for (int i = 0; i < blocks.size(); i++) {
            if (((Block) blocks.elementAt(i)).kind != ChatSession.KIND_INFO) {
                return false;
            }
        }
        return true;
    }

    /**
     * The wordmark in a quiet tone, the model and the key to write, centred
     * between the header and the status line, but below the first used
     * pixels (one-line notes at the top).
     */
    private void paintEmpty(Graphics g, int w, int top, int vh, int used) {
        int cx = w / 2;
        Vector title = new Vector();
        Text.wrap(session.ai(), Theme.bold, w - 4 * PAD, title);
        Vector tips = new Vector();
        Text.wrap(L.t("Centre key to write"), Theme.small, w - 4 * PAD, tips);
        int textH = title.size() * Theme.bold.getHeight() + 4 + tips.size() * Theme.small.getHeight();
        int ww = Math.min(w * 55 / 100, (vh - used - textH - 2 * PAD) * 3);
        int wh = ww >= 24 ? Wordmark.height(ww) : 0;
        int gap = wh > 0 ? Math.max(10, wh * 2 / 3) : 0;
        int y = Math.max(top + used + PAD, top + (vh - wh - gap - textH) / 2);
        int bottom = top + vh;
        if (wh > 0) {
            int x0 = cx - ww / 2;
            g.drawImage(Wordmark.get(ww, Theme.mix(Theme.bg, Theme.ink, 48)), x0, y, Graphics.TOP | Graphics.LEFT);
            y += wh + gap;
        }
        g.setFont(Theme.bold);
        g.setColor(Theme.ink);
        for (int i = 0; i < title.size() && y + Theme.bold.getHeight() <= bottom; i++) {
            g.drawString((String) title.elementAt(i), cx, y, Graphics.TOP | Graphics.HCENTER);
            y += Theme.bold.getHeight();
        }
        y += 4;
        g.setFont(Theme.small);
        g.setColor(Theme.muted);
        for (int i = 0; i < tips.size() && y + Theme.small.getHeight() <= bottom; i++) {
            g.drawString((String) tips.elementAt(i), cx, y, Graphics.TOP | Graphics.HCENTER);
            y += Theme.small.getHeight();
        }
    }

    private void paintHeader(Graphics g, int w) {
        int bh = barH();
        boolean test = midlet.settings.testMode;
        g.setColor(Theme.chrome);
        g.fillRect(0, 0, w, bh);
        g.setColor(Theme.border);
        g.drawLine(0, bh - 1, w, bh - 1);
        int right = w - PAD;
        String rem = session.remaining();
        if (test || rem.length() > 0) {
            g.setFont(Theme.small);
            String pill = test ? "TEST" : rem + L.t(" left");
            int pw = Theme.small.stringWidth(pill) + 10;
            int ph = Theme.small.getHeight() + 2;
            g.setColor(test ? Theme.testBar : Theme.selection);
            g.fillRoundRect(w - PAD - pw, (bh - ph) / 2, pw, ph, ph, ph);
            g.setColor(test ? 0xFFFFFF : Theme.accent);
            g.drawString(pill, w - PAD - pw / 2, (bh - ph) / 2 + 1, Graphics.TOP | Graphics.HCENTER);
            right -= pw + 4;
        }
        // the chat's model, with a chevron: Options > Model changes it
        g.setFont(Theme.bold);
        g.setColor(Theme.ink);
        int tx = PAD + 4;
        int cw = 9;
        String name = Text.fit(session.ai(), Theme.bold, right - tx - cw - 4);
        g.drawString(name, tx, 4, Graphics.TOP | Graphics.LEFT);
        int cx = tx + Theme.bold.stringWidth(name) + 4;
        int cy = bh / 2;
        g.setColor(Theme.muted);
        g.fillTriangle(cx, cy - 2, cx + cw - 1, cy - 2, cx + cw / 2, cy + 3);
    }

    private void paintStatus(Graphics g, int w, int h, int vh) {
        // a quiet input field: what the centre key does, or the status
        int sh = statusH();
        g.setColor(Theme.chrome);
        g.fillRect(0, h - sh, w, sh);
        g.setColor(Theme.border);
        g.drawLine(0, h - sh, w, h - sh);
        int ph = sh - 6;
        int py = h - sh + 2;
        g.setColor(Theme.surface);
        g.fillRoundRect(PAD, py, w - 2 * PAD, ph, 8, 8);
        g.setColor(Theme.border);
        g.drawRoundRect(PAD, py, w - 2 * PAD - 1, ph - 1, 8, 8);
        String st = session.status();
        String hint = hint(vh);
        boolean ready = st.length() == 0 && hint == READY;
        g.setFont(Theme.small);
        g.setColor(st.length() > 0 ? Theme.accent : Theme.muted);
        String draft = ready ? session.draft().trim() : "";
        if (draft.length() > 0) {
            int nl = draft.indexOf('\n');
            draft = nl < 0 ? draft : draft.substring(0, nl);
            g.setColor(Theme.ink); // the unsent text itself, with a quiet label
        }
        String t = st.length() > 0 ? st : draft.length() > 0 ? L.t("Draft: ") + draft
                : ready ? L.t("Write a message") : hint;
        boolean busy = session.busy();
        int dotsW = busy ? 30 : 0; // the typing bubble's three dots, small, at the pill's right end
        g.drawString(Text.fit(t, Theme.small, w - 4 * PAD - dotsW), 2 * PAD, py + (ph - Theme.small.getHeight()) / 2,
                Graphics.TOP | Graphics.LEFT);
        if (busy) {
            for (int i = 0; i < 3; i++) {
                boolean up = animFrame % 3 == i;
                g.setColor(up ? Theme.accent : Theme.border);
                int d = up ? 6 : 4;
                g.fillArc(w - 2 * PAD - dotsW + 6 + i * 9 - d / 2, py + ph / 2 - d / 2, d, d, 0, 360);
            }
        }
    }

    /** The idle hint (a marker: the pill then shows its placeholder). */
    private static final String READY = "ready";

    /** The idle status line suggests the key that helps most right now. */
    private String hint(int vh) {
        if (sel != 0) {
            ChatSession.Entry e = session.entry(sel);
            if (e != null && !isBubble(e.kind)) {
                return L.t("Centre key: details · 1/3: select");
            }
            return L.t("Centre key: actions · 1/3: select");
        }
        if (session.canRetry()) {
            return L.t("Centre key: retry · 5: write");
        }
        if (session.canMore()) {
            return L.t("0: the rest · 7: reading mode");
        }
        for (int i = 0; i < blocks.size(); i++) {
            Block b = (Block) blocks.elementAt(i);
            if (isReply(b.kind) && b.h > vh * 3 / 4) {
                return L.t("7: reading mode · 5: write");
            }
        }
        return READY;
    }

    private void paintReading(Graphics g, int w, int h) {
        Font f = Theme.font;
        Font sm = Theme.small;
        int head = readHeadH();
        g.setColor(Theme.bg);
        g.fillRect(0, 0, w, h);

        // header: who/when (or what is going on) left, page right, a progress hairline under it
        g.setColor(Theme.chrome);
        g.fillRect(0, 0, w, head - 2);
        int[] p = pages();
        String right = p[0] + "/" + p[1];
        g.setFont(sm);
        int rw = sm.stringWidth(right);
        String left;
        int leftColor = Theme.muted;
        if (session.typing()) {
            left = typingLabel();
            leftColor = Theme.accent;
        } else if (newWhileReading) {
            left = L.t("New reply · press 3");
            leftColor = Theme.accent;
        } else {
            ChatSession.Entry e = rEntry;
            left = (e.kind == ChatSession.KIND_TEST ? L.t("Test mode · fake") : replyName(e))
                    + (e.time > 0 ? " · " + hhmm(e.time) : "") + (e.searched > 0 ? " · web" : "");
        }
        g.setColor(leftColor);
        g.drawString(Text.fit(left, sm, w - 3 * RPAD - rw), RPAD, 3, Graphics.TOP | Graphics.LEFT);
        g.setColor(Theme.muted);
        g.drawString(right, w - RPAD, 3, Graphics.TOP | Graphics.RIGHT);
        int n = rLines.size();
        int end = visibleEnd(rTop);
        g.setColor(Theme.border);
        g.fillRect(0, head - 2, w, 2);
        g.setColor(Theme.accent);
        g.fillRect(0, head - 2, n == 0 ? w : w * end / n, 2);

        // whole lines only
        int y = head + 4;
        for (int i = rTop; i < end; i++) {
            Text.Line l = (Text.Line) rLines.elementAt(i);
            if (l == rFooter) {
                paintReadFooter(g, w, y);
            } else {
                g.setFont(f);
                g.setColor(Theme.ink);
                Text.draw(g, l, f, RPAD, y);
            }
            y += Text.lineH(l, f);
        }
    }

    /** Last line of a reply that is not complete: "0 · the rest" or "shortened". */
    private void paintReadFooter(Graphics g, int w, int y) {
        Font f = Theme.font;
        String t;
        int c;
        if (rEntry.truncated) {
            t = L.t("Reply shortened");
            c = Theme.error;
        } else if (session.loadingMore() == readUid) {
            t = L.t("Loading the rest...");
            c = Theme.muted;
        } else {
            t = L.t("0 or centre key: the rest");
            if (f.stringWidth(t) > w - 2 * RPAD) {
                t = L.t("0: the rest");
            }
            c = Theme.accent;
        }
        g.setColor(Theme.border);
        g.drawLine(RPAD, y - 2, w - RPAD, y - 2);
        g.setFont(f);
        g.setColor(c);
        g.drawString(Text.fit(t, f, w - 2 * RPAD), RPAD, y + 1, Graphics.TOP | Graphics.LEFT);
    }

    private void paintToast(Graphics g, int w, int bottom) {
        String t = toast;
        if (t == null || System.currentTimeMillis() > toastUntil) {
            return;
        }
        Font sm = Theme.small;
        t = Text.fit(t, sm, w - 6 * PAD);
        int tw = sm.stringWidth(t) + 16;
        int th = sm.getHeight() + 8;
        int x = (w - tw) / 2;
        int y = bottom - th - PAD;
        g.setColor(Theme.bar);
        g.fillRoundRect(x, y, tw, th, th, th);
        g.setFont(sm);
        g.setColor(Theme.barInk);
        g.drawString(t, w / 2, y + 4, Graphics.TOP | Graphics.HCENTER);
    }
}

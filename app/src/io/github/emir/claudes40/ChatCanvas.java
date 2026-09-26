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
 * way an animated "Claude yazıyor" bubble is shown.
 *
 * Softkeys are standard Commands; scrolling uses getGameAction() (UP/DOWN one
 * line, LEFT/RIGHT one page); FIRE opens the editor. Sizes come from
 * getWidth()/getHeight() and font metrics only.
 */
final class ChatCanvas extends Canvas implements CommandListener, ChatSession.View {

    private static final int PAD = 6;
    private static final int BUBBLE_PAD = 5;
    private static final int ARC = 14;

    private final ClaudeS40MIDlet midlet;
    private final ChatSession session;

    final Command writeCmd = new Command(L.s("Yaz", "Write"), Command.SCREEN, 1);
    final Command promptsCmd = new Command(L.s("Hızlı sorular", "Quick prompts"), Command.SCREEN, 2);
    final Command retryCmd = new Command(L.s("Tekrar dene", "Retry"), Command.SCREEN, 3);
    final Command newCmd = new Command(L.s("Yeni sohbet", "New chat"), Command.SCREEN, 4);
    final Command deleteCmd = new Command(L.s("Sohbeti sil", "Delete chat"), Command.SCREEN, 5);
    final Command backCmd = new Command(L.s("Menü", "Menu"), Command.BACK, 1);

    /** One laid-out message. */
    private static final class Block {
        int kind;
        String[] lines;
        String meta;
        boolean truncated;
        int y;
        int h;
        int bw;
    }

    private final Vector blocks = new Vector();
    private int builtVersion = -1;
    private int builtWidth = -1;
    private int builtTheme = -1;
    private int contentH;
    private int scroll;
    private boolean jumpToLast;
    private boolean followTyping;
    private boolean retryShown;
    private Timer anim;
    private int animFrame;

    ChatCanvas(ClaudeS40MIDlet midlet, ChatSession session) {
        this.midlet = midlet;
        this.session = session;
        addCommand(writeCmd);
        addCommand(promptsCmd);
        addCommand(newCmd);
        addCommand(deleteCmd);
        addCommand(backCmd);
        setCommandListener(this);
        session.setView(this);
    }

    public void commandAction(Command c, Displayable d) {
        String err = null;
        if (c == writeCmd) {
            if (session.busy()) {
                err = L.s("Önceki istek sürüyor.", "A request is still running.");
            } else {
                midlet.showComposer(null);
            }
        } else if (c == promptsCmd) {
            midlet.showPrompts();
        } else if (c == retryCmd) {
            err = session.retry();
        } else if (c == newCmd) {
            err = session.newChat();
        } else if (c == deleteCmd) {
            err = session.deleteChat();
        } else if (c == backCmd) {
            midlet.showMenu();
        }
        if (err != null) {
            midlet.info(err, this);
        }
    }

    public void sessionChanged(boolean newReply) {
        synchronized (this) {
            if (newReply) {
                jumpToLast = true;
            }
            if (session.typing()) {
                followTyping = true;
            }
        }
        if (newReply) {
            midlet.replyFeedback();
        }
        updateRetryCommand();
        updateAnimation();
        repaint();
    }

    private synchronized void updateRetryCommand() {
        boolean want = session.canRetry();
        if (want != retryShown) {
            if (want) {
                addCommand(retryCmd);
            } else {
                removeCommand(retryCmd);
            }
            retryShown = want;
        }
    }

    /** Timer runs only while a reply is awaited and the chat is visible. */
    private synchronized void updateAnimation() {
        boolean want = session.typing() && isShown();
        if (want && anim == null) {
            anim = new Timer();
            anim.schedule(new TimerTask() {
                public void run() {
                    animFrame++;
                    repaint();
                }
            }, 350, 350);
        } else if (!want && anim != null) {
            anim.cancel();
            anim = null;
        }
    }

    protected void showNotify() {
        updateRetryCommand();
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
        repaint();
    }

    protected void keyPressed(int keyCode) {
        scroll(keyCode);
    }

    protected void keyRepeated(int keyCode) {
        scroll(keyCode);
    }

    private void scroll(int keyCode) {
        int action;
        try {
            action = getGameAction(keyCode);
        } catch (IllegalArgumentException e) {
            return;
        }
        if (action == FIRE) {
            if (!session.busy()) {
                midlet.showComposer(null);
            }
            return;
        }
        synchronized (this) {
            int line = Theme.font.getHeight();
            int page = Math.max(line, viewH() - line);
            followTyping = false;
            if (action == UP) {
                scroll -= line;
            } else if (action == DOWN) {
                scroll += line;
            } else if (action == LEFT) {
                scroll -= page;
            } else if (action == RIGHT) {
                scroll += page;
            } else {
                return;
            }
        }
        repaint();
    }

    private int barH() {
        return Theme.bold.getHeight() + 8;
    }

    private int statusH() {
        return Theme.small.getHeight() + 6;
    }

    private int viewH() {
        return getHeight() - barH() - statusH();
    }

    // ------------------------------------------------------------ layout

    private void layout(int w) {
        int v = session.version();
        if (v == builtVersion && w == builtWidth && builtTheme == Theme.bg + Theme.font.getHeight()) {
            return;
        }
        builtVersion = v;
        builtWidth = w;
        builtTheme = Theme.bg + Theme.font.getHeight();
        blocks.removeAllElements();
        ChatSession.Entry[] es = session.snapshot();
        Font f = Theme.font;
        int maxBubble = w * 82 / 100;
        int y = PAD;
        for (int i = 0; i < es.length; i++) {
            ChatSession.Entry e = es[i];
            Block b = new Block();
            b.kind = e.kind;
            b.truncated = e.truncated;
            boolean bubble = isBubble(e.kind);
            int textW = bubble ? maxBubble - 2 * BUBBLE_PAD : w - 4 * PAD;
            Vector lines = new Vector();
            Text.wrap(e.text, f, textW, lines);
            if (e.truncated) {
                lines.addElement(L.s("(yanıt kısaltıldı)", "(reply shortened)"));
            }
            b.lines = new String[lines.size()];
            lines.copyInto(b.lines);
            int widest = 0;
            for (int k = 0; k < b.lines.length; k++) {
                widest = Math.max(widest, f.stringWidth(b.lines[k]));
            }
            String who = e.kind == ChatSession.KIND_USER ? L.s("Sen", "You")
                    : e.kind == ChatSession.KIND_CLAUDE ? "Claude"
                    : e.kind == ChatSession.KIND_TEST ? L.s("Test modu (sahte yanıt)", "Test mode (fake reply)") : null;
            b.meta = who == null ? null : who + " · " + hhmm(e.time);
            int metaH = b.meta == null ? 0 : Theme.small.getHeight() + 1;
            if (bubble) {
                b.bw = Math.max(widest, Theme.small.stringWidth(b.meta) + (e.kind == ChatSession.KIND_USER ? 0 : 12))
                        + 2 * BUBBLE_PAD;
                b.bw = Math.min(b.bw, maxBubble);
            } else {
                b.bw = w - 2 * PAD;
            }
            b.y = y;
            b.h = metaH + b.lines.length * f.getHeight() + 2 * BUBBLE_PAD;
            y += b.h + PAD;
            blocks.addElement(b);
        }
        contentH = y;
    }

    private static boolean isBubble(int kind) {
        return kind == ChatSession.KIND_USER || kind == ChatSession.KIND_CLAUDE || kind == ChatSession.KIND_TEST;
    }

    private static String hhmm(long ms) {
        Calendar c = Calendar.getInstance();
        c.setTime(new Date(ms));
        int hh = c.get(Calendar.HOUR_OF_DAY);
        int mm = c.get(Calendar.MINUTE);
        return (hh < 10 ? "0" : "") + hh + ":" + (mm < 10 ? "0" : "") + mm;
    }

    // ------------------------------------------------------------ paint

    protected synchronized void paint(Graphics g) {
        int w = getWidth();
        int h = getHeight();
        int top = barH();
        int vh = viewH();
        layout(w);

        boolean typing = session.typing();
        int typingH = typing ? Theme.font.getHeight() + 2 * BUBBLE_PAD + Theme.small.getHeight() + 2 * PAD : 0;
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

        if (blocks.size() == 0 && !typing) {
            paintEmpty(g, w, top, vh);
        }
        for (int i = 0; i < blocks.size(); i++) {
            Block b = (Block) blocks.elementAt(i);
            int y = top + b.y - scroll;
            if (y > top + vh || y + b.h < top) {
                continue;
            }
            paintBlock(g, b, w, y);
        }
        if (typing) {
            paintTyping(g, top + contentH - scroll);
        }
        g.setClip(0, 0, w, h);

        if (total > vh) {
            int thumb = Math.max(8, vh * vh / total);
            int ty = top + (vh - thumb) * scroll / Math.max(1, maxScroll);
            g.setColor(Theme.border);
            g.fillRoundRect(w - 3, ty, 3, thumb, 3, 3);
        }

        paintHeader(g, w);
        paintStatus(g, w, h);
    }

    private void paintBlock(Graphics g, Block b, int w, int y) {
        Font f = Theme.font;
        int lh = f.getHeight();
        if (isBubble(b.kind)) {
            boolean mine = b.kind == ChatSession.KIND_USER;
            int x = mine ? w - PAD - b.bw : PAD;
            int fill = mine ? Theme.accent : Theme.surface;
            int text = mine ? Theme.accentInk : Theme.ink;
            if (!mine) {
                g.setColor(Theme.border);
                g.fillRoundRect(x - 1, y - 1, b.bw + 2, b.h + 2, ARC, ARC);
            }
            g.setColor(fill);
            g.fillRoundRect(x, y, b.bw, b.h, ARC, ARC);
            int ty = y + BUBBLE_PAD;
            int mx = x + BUBBLE_PAD;
            if (b.kind == ChatSession.KIND_CLAUDE) {
                Logo.draw(g, mx + 4, ty + Theme.small.getHeight() / 2, 10, 100, 0);
                mx += 12;
            }
            g.setFont(Theme.small);
            g.setColor(mine ? Theme.mix(Theme.accentInk, fill, 80)
                    : b.kind == ChatSession.KIND_TEST ? Theme.testBar : Theme.accent);
            g.drawString(b.meta, mx, ty, Graphics.TOP | Graphics.LEFT);
            ty += Theme.small.getHeight() + 1;
            g.setFont(f);
            for (int k = 0; k < b.lines.length; k++) {
                boolean note = b.truncated && k == b.lines.length - 1;
                g.setColor(note ? Theme.error : text);
                g.drawString(b.lines[k], x + BUBBLE_PAD, ty, Graphics.TOP | Graphics.LEFT);
                ty += lh;
            }
        } else {
            boolean err = b.kind == ChatSession.KIND_ERROR;
            if (err) {
                g.setColor(Theme.errorBg);
                g.fillRoundRect(PAD, y, w - 2 * PAD, b.h, ARC, ARC);
            }
            g.setFont(f);
            g.setColor(err ? Theme.error : Theme.muted);
            int ty = y + BUBBLE_PAD;
            for (int k = 0; k < b.lines.length; k++) {
                g.drawString(b.lines[k], w / 2, ty, Graphics.TOP | Graphics.HCENTER);
                ty += lh;
            }
        }
    }

    private void paintTyping(Graphics g, int y) {
        g.setFont(Theme.small);
        g.setColor(Theme.muted);
        String label = session.state() == ChatSession.STATE_SENDING ? L.s("Gönderiliyor...", "Sending...")
                : L.s("Claude yazıyor...", "Claude is typing...");
        g.drawString(label, PAD + 2, y, Graphics.TOP | Graphics.LEFT);
        int by = y + Theme.small.getHeight() + 2;
        int bh = Theme.font.getHeight() + 2 * BUBBLE_PAD;
        int bw = 60;
        g.setColor(Theme.border);
        g.fillRoundRect(PAD - 1, by - 1, bw + 2, bh + 2, ARC, ARC);
        g.setColor(Theme.surface);
        g.fillRoundRect(PAD, by, bw, bh, ARC, ARC);
        for (int i = 0; i < 3; i++) {
            boolean up = animFrame % 3 == i;
            g.setColor(up ? Theme.accent : Theme.border);
            int d = up ? 8 : 6;
            g.fillArc(PAD + 14 + i * 15 - d / 2, by + bh / 2 - d / 2 - (up ? 2 : 0), d, d, 0, 360);
        }
    }

    private void paintEmpty(Graphics g, int w, int top, int vh) {
        int cx = w / 2;
        int size = Math.min(w, vh) * 34 / 100;
        int cy = top + vh * 30 / 100;
        Logo.draw(g, cx, cy, size, 100, 0);
        g.setFont(Theme.bold);
        g.setColor(Theme.ink);
        int y = cy + size / 2 + 12;
        g.drawString(L.s("Merhaba! Ne sormak istersin?", "Hi! What would you like to ask?"), cx, y, Graphics.TOP | Graphics.HCENTER);
        g.setFont(Theme.small);
        g.setColor(Theme.muted);
        y += Theme.bold.getHeight() + 4;
        Vector tips = new Vector();
        Text.wrap(L.s("Orta tuş: yaz. Seçenekler > Hızlı sorular: hazır fikirler.", "Centre key: write. Options > Quick prompts: ideas to start."),
                Theme.small, w - 4 * PAD, tips);
        for (int i = 0; i < tips.size(); i++) {
            g.drawString((String) tips.elementAt(i), cx, y, Graphics.TOP | Graphics.HCENTER);
            y += Theme.small.getHeight();
        }
    }

    private void paintHeader(Graphics g, int w) {
        int bh = barH();
        boolean test = midlet.settings.testMode;
        g.setColor(test ? Theme.testBar : Theme.bar);
        g.fillRect(0, 0, w, bh);
        Logo.draw(g, PAD + bh / 2 - 2, bh / 2, bh - 6, 100, 0);
        g.setFont(Theme.bold);
        g.setColor(Theme.barInk);
        g.drawString(test ? "Claude S40 · TEST" : "Claude S40", PAD + bh + 2, 4, Graphics.TOP | Graphics.LEFT);
        String rem = session.remaining();
        if (!test && rem.length() > 0) {
            g.setFont(Theme.small);
            String pill = rem + L.s(" hak", " left");
            int pw = Theme.small.stringWidth(pill) + 10;
            int ph = Theme.small.getHeight() + 2;
            g.setColor(Theme.accent);
            g.fillRoundRect(w - PAD - pw, (bh - ph) / 2, pw, ph, ph, ph);
            g.setColor(Theme.accentInk);
            g.drawString(pill, w - PAD - pw / 2, (bh - ph) / 2 + 1, Graphics.TOP | Graphics.HCENTER);
        }
    }

    private void paintStatus(Graphics g, int w, int h) {
        int sh = statusH();
        g.setColor(Theme.surface);
        g.fillRect(0, h - sh, w, sh);
        g.setColor(Theme.border);
        g.drawLine(0, h - sh, w, h - sh);
        String st = session.status();
        g.setFont(Theme.small);
        g.setColor(st.length() > 0 ? Theme.accent : Theme.muted);
        g.drawString(st.length() > 0 ? st : L.s("Hazır · orta tuşla yaz", "Ready · centre key to write"), PAD, h - sh + 3, Graphics.TOP | Graphics.LEFT);
    }
}

package io.github.emir.claudes40;

import java.util.Vector;

/**
 * Conversation state in RAM. Written to the phone (ChatStore) only if the
 * user turned on Settings > "Keep last chat on phone".
 *
 * - At most one request in flight.
 * - The message being sent stays in `draft` until a definite answer arrives,
 *   so an error never loses what the user typed.
 * - A request keeps its request_id until it is resolved. "Tekrar dene"
 *   re-sends the SAME id: the gateway then returns the recorded result
 *   instead of calling Claude again. Nothing is re-sent automatically.
 * - If the gateway reports "uncertain", the id is dropped; sending the draft
 *   again is a new (paid) request and needs a new user action.
 * - Long replies arrive in parts. "Devamı" / "Show more" fetches the next
 *   part of the STORED reply (/v1/more); that never calls Claude again.
 * - A conversation from the server's list can be opened (/v1/history) and
 *   continued.
 */
final class ChatSession implements Runnable, Net.Listener {

    static final int KIND_USER = 0;
    static final int KIND_CLAUDE = 1;
    static final int KIND_TEST = 2;
    static final int KIND_INFO = 3;
    static final int KIND_ERROR = 4;

    static final int STATE_IDLE = 0;
    static final int STATE_SENDING = 1;
    static final int STATE_WAITING = 2;

    private static final int JOB_CHAT = 0;
    private static final int JOB_DELETE = 1;
    private static final int JOB_MORE = 2;
    private static final int JOB_HISTORY = 3;

    /** RAM limits for the on-screen transcript. */
    static final int MAX_ENTRIES = 30;
    static final int MAX_CHARS = 12000;
    static final int MAX_MESSAGE = 1000;

    /** One message; immutable (a new part replaces the entry). */
    static final class Entry {
        final int kind;
        final String text;
        /** The reply is incomplete and nothing more can be fetched. */
        final boolean truncated;
        /** 0 if unknown (loaded from the server's history). */
        final long time;
        /** Web searches Claude made for this reply. */
        final int searched;
        /** request_id of the reply, for /v1/more; null if not known. */
        final String request;
        /** Offset of the next part on the server; null when complete. */
        final String next;
        /** Stays the same when a further part replaces the entry (reading position). */
        final int uid;

        Entry(int kind, String text, boolean truncated) {
            this(kind, text, truncated, System.currentTimeMillis(), 0, null, null);
        }

        Entry(int kind, String text, boolean truncated, long time, int searched, String request, String next) {
            this(kind, text, truncated, time, searched, request, next, nextUid());
        }

        private Entry(int kind, String text, boolean truncated, long time, int searched, String request, String next,
                int uid) {
            this.uid = uid;
            this.kind = kind;
            this.text = text;
            this.truncated = truncated;
            this.time = time;
            this.searched = searched;
            this.request = request;
            this.next = next;
        }

        boolean more() {
            return request != null && next != null;
        }

        /** This reply with the next part appended; keeps uid. */
        Entry extend(String part, boolean cut, String nextOffset) {
            return new Entry(kind, text + part, cut, time, searched, request, nextOffset, uid);
        }

        /** The rest can no longer be fetched; keeps uid. */
        Entry cutOff() {
            return new Entry(kind, text, true, time, searched, null, null, uid);
        }
    }

    private static int uids;

    private static synchronized int nextUid() {
        return ++uids;
    }

    interface View {
        /** Called on any thread after the session changed. */
        void sessionChanged(boolean newReply);
    }

    private final ClaudeS40MIDlet midlet;
    private final Vector entries = new Vector();
    private View view;

    private int state = STATE_IDLE;
    private String status = "";
    private String conversation = "";
    private String draft = "";
    private int version;
    private String remaining = "";
    /** When `remaining` arrived; the server counts per UTC day. */
    private long remainingAt;
    /** When the running request started (for the elapsed time on screen). */
    private long startedAt;

    // the request being resolved (null when none)
    private String pendingId;
    private String pendingText;
    private String pendingConversation;
    private boolean canRetry;

    // work item for the worker thread
    private int job;
    /** Conversation to open (JOB_HISTORY). */
    private String jobConversation;
    /** Entry whose next part is being fetched (JOB_MORE). */
    private Entry jobEntry;
    /** Session version last written to ChatStore. */
    private int savedVersion = -1;

    ChatSession(ClaudeS40MIDlet midlet) {
        this.midlet = midlet;
    }

    void setView(View v) {
        view = v;
    }

    synchronized int version() {
        return version;
    }

    synchronized Entry[] snapshot() {
        Entry[] out = new Entry[entries.size()];
        entries.copyInto(out);
        return out;
    }

    synchronized int state() {
        return state;
    }

    synchronized String status() {
        return status;
    }

    synchronized String remaining() {
        return remaining;
    }

    /** Requests left today, or "" if not known for the current UTC day. */
    synchronized String remainingToday() {
        long day = 24L * 60 * 60 * 1000;
        return remaining.length() > 0 && remainingAt / day == System.currentTimeMillis() / day ? remaining : "";
    }

    /** Text of the newest message the user sent, or "" if none. */
    synchronized String lastUserText() {
        for (int i = entries.size() - 1; i >= 0; i--) {
            Entry e = (Entry) entries.elementAt(i);
            if (e.kind == KIND_USER) {
                return e.text;
            }
        }
        return "";
    }

    /** The entry with this uid, or null. */
    synchronized Entry entry(int uid) {
        return find(uid);
    }

    synchronized boolean busy() {
        return state != STATE_IDLE;
    }

    synchronized boolean canRetry() {
        return state == STATE_IDLE && canRetry && pendingId != null;
    }

    /** True while a chat request (not a delete or a load) is running. */
    synchronized boolean typing() {
        return state != STATE_IDLE && job == JOB_CHAT;
    }

    /** Seconds since the running request started. */
    synchronized int elapsed() {
        return state == STATE_IDLE ? 0 : (int) ((System.currentTimeMillis() - startedAt) / 1000);
    }

    /** uid of the reply whose next part is loading, or 0. */
    synchronized int loadingMore() {
        return state != STATE_IDLE && job == JOB_MORE && jobEntry != null ? jobEntry.uid : 0;
    }

    /** A reply has a further part on the server. */
    synchronized boolean canMore() {
        return state == STATE_IDLE && lastMore() != null;
    }

    /** Called with the lock held. */
    private Entry lastMore() {
        for (int i = entries.size() - 1; i >= 0; i--) {
            Entry e = (Entry) entries.elementAt(i);
            if (e.more()) {
                return e;
            }
        }
        return null;
    }

    synchronized int entryCount() {
        return entries.size();
    }

    synchronized boolean hasConversation() {
        return conversation.length() > 0;
    }

    synchronized String draft() {
        return draft;
    }

    synchronized void setDraft(String d) {
        draft = d == null ? "" : Text.clip(d, MAX_MESSAGE);
    }

    // ------------------------------------------------------------ actions

    /** Returns null if started, or a reason why not. */
    String send(String text) {
        synchronized (this) {
            if (state != STATE_IDLE) {
                return L.s("Önceki istek sürüyor.", "A request is still running.");
            }
            String t = text == null ? "" : text.trim();
            if (t.length() == 0) {
                return L.s("Mesaj boş.", "The message is empty.");
            }
            if (t.length() > MAX_MESSAGE) {
                return L.s("Mesaj en fazla " + MAX_MESSAGE + " karakter olabilir.",
                        "Messages can be at most " + MAX_MESSAGE + " characters.");
            }
            draft = t;
            pendingId = Text.requestId();
            pendingText = t;
            pendingConversation = conversation;
            canRetry = false;
            add(KIND_USER, t, false);
            begin(JOB_CHAT);
        }
        changed(false);
        return null;
    }

    /** Re-sends the unresolved request with the same request_id. */
    String retry() {
        synchronized (this) {
            if (!canRetry()) {
                return L.s("Tekrar denenecek istek yok.", "Nothing to retry.");
            }
            canRetry = false;
            begin(JOB_CHAT);
        }
        changed(false);
        return null;
    }

    /** Fetches the next part of the newest incomplete reply (no Claude call). */
    String more() {
        return more(0);
    }

    /** True if the reply with this uid has a further part and nothing is running. */
    synchronized boolean canMore(int uid) {
        Entry e = find(uid);
        return state == STATE_IDLE && e != null && e.more();
    }

    /** Called with the lock held. */
    private Entry find(int uid) {
        for (int i = entries.size() - 1; i >= 0; i--) {
            Entry e = (Entry) entries.elementAt(i);
            if (e.uid == uid) {
                return e;
            }
        }
        return null;
    }

    /** Next part of the reply with this uid (0: the newest incomplete one). */
    String more(int uid) {
        synchronized (this) {
            if (state != STATE_IDLE) {
                return L.s("Önceki istek sürüyor.", "A request is still running.");
            }
            Entry e = uid == 0 ? lastMore() : find(uid);
            if (e != null && !e.more()) {
                e = null;
            }
            if (e == null) {
                return L.s("Yanıtın devamı yok.", "There is no more to this reply.");
            }
            jobEntry = e;
            begin(JOB_MORE);
        }
        changed(false);
        return null;
    }

    /** Opens a conversation from the server's list (replaces the transcript). */
    String open(String conv) {
        synchronized (this) {
            if (state != STATE_IDLE) {
                return L.s("Önceki istek sürüyor.", "A request is still running.");
            }
            jobConversation = conv;
            begin(JOB_HISTORY);
        }
        changed(false);
        return null;
    }

    /** Puts a transcript saved on the phone back (start-up, ChatStore). */
    void restore(String conv, Vector saved) {
        synchronized (this) {
            if (saved == null || saved.size() == 0) {
                return;
            }
            resetLocal();
            // "test" (test mode) or an unexpected id: start a new conversation when writing
            conversation = conv != null && conv.length() == 16 ? conv : "";
            add(KIND_INFO, L.s("Bu telefonda kayıtlı sohbet. Ağ olmadan da okunur; yazınca kaldığı yerden sürer.",
                    "Chat saved on this phone. Readable offline; write to continue it."), false);
            for (int i = 0; i < saved.size(); i++) {
                entries.addElement(saved.elementAt(i));
            }
            trim();
            savedVersion = version;
        }
        changed(false);
    }

    /** Starts a new conversation; the old one stays on the server until it expires. */
    String newChat() {
        synchronized (this) {
            if (state != STATE_IDLE) {
                return L.s("Önceki istek sürüyor.", "A request is still running.");
            }
            resetLocal();
            add(KIND_INFO, L.s("Yeni sohbet. Önceki sohbet sunucuda süresi dolana kadar (30 gün) kalır.",
                    "New chat. The previous one stays on the server until it expires (30 days)."), false);
        }
        changed(false);
        return null;
    }

    /** Deletes the current conversation on the server, then starts a new one. */
    String deleteChat() {
        synchronized (this) {
            if (state != STATE_IDLE) {
                return L.s("Önceki istek sürüyor.", "A request is still running.");
            }
            if (conversation.length() == 0 || midlet.settings.testMode) {
                resetLocal();
                add(KIND_INFO, L.s("Sohbet temizlendi.", "Chat cleared."), false);
                changed(false);
                return null;
            }
            begin(JOB_DELETE);
        }
        changed(false);
        return null;
    }

    private void resetLocal() {
        entries.removeAllElements();
        conversation = "";
        pendingId = null;
        pendingText = null;
        canRetry = false;
        status = "";
        version++;
    }

    private void begin(int j) {
        job = j;
        state = STATE_SENDING;
        startedAt = System.currentTimeMillis();
        status = jobLabel();
        version++;
        new Thread(this).start();
    }

    private String jobLabel() {
        switch (job) {
        case JOB_DELETE:
            return L.s("Siliniyor...", "Deleting...");
        case JOB_MORE:
            return L.s("Devamı yükleniyor...", "Loading more...");
        case JOB_HISTORY:
            return L.s("Sohbet yükleniyor...", "Loading chat...");
        default:
            return L.s("Gönderiliyor...", "Sending...");
        }
    }

    // ------------------------------------------------------------ worker

    public void phase(int phase) {
        if (phase >= Net.PHASE_RESPONSE) {
            synchronized (this) {
                state = STATE_WAITING;
                status = job == JOB_CHAT ? L.s("Yanıt bekleniyor...", "Waiting for reply...") : jobLabel();
                version++;
            }
            changed(false);
        }
    }

    public void run() {
        int j;
        String id;
        String text;
        String conv;
        Entry more;
        synchronized (this) {
            j = job;
            id = pendingId;
            text = pendingText;
            conv = j == JOB_DELETE ? conversation : j == JOB_HISTORY ? jobConversation : pendingConversation;
            more = jobEntry;
        }
        Settings s = midlet.settings;
        if (j == JOB_DELETE) {
            Net.Result r = Net.request(s.url + "/v1/delete", "POST", s.token,
                    S40Message.format(new String[] { "conversation" }, new String[] { conv }, ""),
                    midlet.userAgent(), this);
            finishDelete(r);
            return;
        }
        if (j == JOB_MORE) {
            Net.Result r = Net.request(s.url + "/v1/more", "POST", s.token,
                    S40Message.format(new String[] { "request", "offset" }, new String[] { more.request, more.next }, ""),
                    midlet.userAgent(), this);
            finishMore(r, more);
            return;
        }
        if (j == JOB_HISTORY) {
            Net.Result r = Net.request(s.url + "/v1/history", "POST", s.token,
                    S40Message.format(new String[] { "conversation" }, new String[] { conv }, ""),
                    midlet.userAgent(), this);
            finishHistory(r, conv);
            return;
        }
        if (s.testMode) {
            mockReply(text, conv);
            return;
        }
        Net.Result r = Net.request(s.url + "/v1/chat", "POST", s.token,
                S40Message.format(new String[] { "request", "conversation", "search" },
                        new String[] { id, conv, s.webSearch ? "1" : "0" }, text),
                midlet.userAgent(), this);
        finishChat(r);
    }

    private void mockReply(String text, String conv) {
        phase(Net.PHASE_RESPONSE);
        try {
            Thread.sleep(1500); // long enough to see the typing animation
        } catch (InterruptedException e) {
            // ignore
        }
        int prior = 0;
        synchronized (this) {
            for (int i = 0; i < entries.size(); i++) {
                if (((Entry) entries.elementAt(i)).kind == KIND_USER) {
                    prior++;
                }
            }
            String reply = L.s("[Test modu] Bu gerçek bir Claude yanıtı değildir. Ağ kullanılmadı.\n"
                    + "Mesajın " + text.length() + " karakter; bu sohbette önceki mesaj sayısı: " + (prior - 1)
                    + ".\nAldığım metin: \"" + text + "\"",
                    "[Test mode] This is not a real Claude reply. No network was used.\n"
                    + "Your message has " + text.length() + " characters; earlier messages in this chat: "
                    + (prior - 1) + ".\nI received: \"" + text + "\"");
            conversation = conv.length() > 0 ? conv : "test";
            add(KIND_TEST, reply, false);
            resolved();
        }
        changed(true);
    }

    private void finishChat(Net.Result r) {
        boolean newReply = false;
        synchronized (this) {
            state = STATE_IDLE;
            S40Message m = r.msg;
            if (!r.ok()) {
                status = r.httpCode < 0 ? L.s("Bağlantı kurulamadı", "Could not connect") : L.s("Yanıt alınamadı", "No reply");
                add(KIND_ERROR, Net.explain(r) + L.s("\n'Tekrar dene' aynı isteği sorar; Claude'a ikinci kez gönderilmez.",
                        "\n'Retry' asks about the same request; it is not sent to Claude twice."), false);
                canRetry = true;
            } else if (m == null) {
                status = L.s("Yanıt alınamadı", "No reply");
                add(KIND_ERROR, L.s("Sunucu yanıtı tanınmadı (HTTP " + r.httpCode
                        + "). Operatör ağı veya yanlış adres olabilir.",
                        "Unrecognised server reply (HTTP " + r.httpCode
                        + "). Carrier network or wrong address?"), false);
                canRetry = true;
            } else {
                newReply = handle(m, r.bodyCut);
            }
            version++;
        }
        changed(newReply);
    }

    /** Called with the lock held. Returns true if a reply was added. */
    private boolean handle(S40Message m, boolean bodyCut) {
        String st = m.field("status");
        if (m.field("remaining").length() > 0) {
            remaining = m.field("remaining");
            remainingAt = System.currentTimeMillis();
        }
        if ("ok".equals(st)) {
            conversation = m.field("conversation");
            // "truncated" is also set while parts are left ("more")
            String next = m.flag("more") && !bodyCut && m.field("next").length() > 0 ? m.field("next") : null;
            boolean cut = (m.flag("truncated") && next == null) || bodyCut;
            if (m.flag("refused")) {
                add(KIND_INFO, L.s("Claude bu isteğe yanıt vermedi.", "Claude declined to answer this one."), false);
            } else {
                add(new Entry(m.flag("mock") ? KIND_TEST : KIND_CLAUDE, m.text, cut, System.currentTimeMillis(),
                        Text.parseInt(m.field("searched"), 0), m.field("request"), next));
            }
            resolved();
            return true;
        }
        if ("pending".equals(st)) {
            status = L.s("Yanıt bekleniyor", "Still working");
            add(KIND_INFO, L.s("İstek sunucuda hâlâ işleniyor. Biraz sonra 'Tekrar dene' seçin.",
                    "The server is still working on it. Choose 'Retry' in a moment."), false);
            canRetry = true;
            return false;
        }
        if ("busy".equals(st)) {
            status = L.s("Meşgul", "Busy");
            add(KIND_INFO, L.s("Başka bir istek sürüyor. Biraz sonra 'Tekrar dene' seçin.",
                    "Another request is running. Choose 'Retry' in a moment."), false);
            canRetry = true;
            return false;
        }
        if ("relay".equals(m.field("source"))) {
            // the TLS relay could not complete the call to the gateway; the
            // gateway may have received it, so keep the id: "Tekrar dene"
            // gets the recorded result instead of a second paid call
            status = L.s("Yanıt alınamadı", "No reply");
            add(KIND_ERROR, L.s("Aracı sunucu Claude S40 sunucusundan yanıt alamadı (" + st
                    + "). 'Tekrar dene' aynı isteği sorar; Claude'a ikinci kez gönderilmez.",
                    "The relay got no answer from the Claude S40 server (" + st
                    + "). 'Retry' asks about the same request; it is not sent to Claude twice."), false);
            canRetry = true;
            return false;
        }
        String msg;
        if ("limit".equals(st)) {
            status = L.s("Kullanım sınırı", "Limit reached");
            msg = L.s("Kullanım sınırına ulaşıldı. Yarın (UTC) tekrar deneyin.",
                    "Daily limit reached. Try again tomorrow (UTC).");
        } else if ("uncertain".equals(st)) {
            status = L.s("Sonuç belirsiz", "Unknown result");
            msg = L.s("Sonuç belirsiz: istek Claude'a ulaşmış olabilir. Otomatik tekrar yapılmadı. "
                    + "Mesaj taslakta duruyor; yeniden göndermek yeni bir ücretli istek olur.",
                    "Unknown result: the request may have reached Claude. Nothing was re-sent. "
                    + "Your draft is kept; sending it again is a new (paid) request.");
        } else if ("conversation_full".equals(st)) {
            status = L.s("Sohbet doldu", "Chat full");
            msg = L.s("Bu sohbet çok uzadı. 'Yeni sohbet' ile devam edin.",
                    "This chat got too long. Continue with 'New chat'.");
        } else if ("conversation_not_found".equals(st)) {
            status = L.s("Sohbet yok", "Chat not found");
            msg = L.s("Sohbet sunucuda bulunamadı (süresi dolmuş olabilir). 'Yeni sohbet' ile devam edin.",
                    "Chat not found on the server (it may have expired). Continue with 'New chat'.");
        } else if ("unauthorized".equals(st)) {
            status = L.s("Yetki yok", "Not authorised");
            msg = L.s("Erişim kodu geçersiz veya iptal edilmiş. Ayarlar > Cihazı eşleştir.",
                    "Access code invalid or revoked. Settings > Pair this phone.");
        } else if ("billing".equals(st)) {
            status = L.s("Kredi yok", "No credits");
            msg = L.s("Sunucunun Claude API hesabında kredi kalmamış. Sunucu sahibi kredi yükleyince tekrar gönderin.",
                    "The server's Claude API account is out of credits. Send again once it is topped up.");
        } else if ("rate_limited".equals(st) || "overloaded".equals(st)) {
            status = L.s("Claude meşgul", "Claude is busy");
            msg = L.s("Claude şu an meşgul. Birazdan yeniden gönderin.", "Claude is busy right now. Send again shortly.");
        } else if ("too_large".equals(st)) {
            status = L.s("Mesaj uzun", "Too long");
            msg = L.s("Mesaj çok uzun.", "The message is too long.");
        } else {
            status = L.s("Yanıt alınamadı", "No reply");
            msg = L.s("Yanıt alınamadı (", "No reply (") + (st.length() > 0 ? st : "?") + ").";
        }
        add(KIND_ERROR, msg, false);
        // definite answer: forget the id, keep the draft
        pendingId = null;
        pendingText = null;
        canRetry = false;
        return false;
    }

    /** Called with the lock held after a successful exchange. */
    private void resolved() {
        state = STATE_IDLE;
        status = "";
        draft = "";
        pendingId = null;
        pendingText = null;
        canRetry = false;
        version++;
    }

    private void finishMore(Net.Result r, Entry e) {
        synchronized (this) {
            state = STATE_IDLE;
            status = "";
            S40Message m = r.msg;
            int i = entries.indexOf(e);
            if (r.ok() && m != null && "ok".equals(m.field("status")) && i >= 0) {
                String next = m.flag("more") && !r.bodyCut && m.field("next").length() > 0 ? m.field("next") : null;
                boolean cut = (m.flag("truncated") && next == null) || r.bodyCut;
                entries.setElementAt(e.extend(m.text, cut, next), i);
                trim();
            } else if (i >= 0 && m != null && "not_found".equals(m.field("status"))) {
                // the server no longer has the stored reply (kept 7 days): stop offering "more"
                entries.setElementAt(e.cutOff(), i);
                add(KIND_INFO, L.s("Yanıtın devamı sunucuda artık yok.", "The rest of this reply is no longer on the server."),
                        false);
            } else if (i >= 0) {
                status = L.s("Devamı alınamadı", "Could not load more");
                add(KIND_ERROR, L.s("Yanıtın devamı yüklenemedi. 0 tuşuyla tekrar deneyin (ücretsiz; Claude'a tekrar sorulmaz). ",
                        "Could not load the rest. Press 0 to try again (free; Claude is not asked again). ")
                        + (r.ok() ? (m == null ? "?" : m.field("status")) : Net.explain(r)), false);
            }
            jobEntry = null;
            version++;
        }
        changed(false);
    }

    private void finishHistory(Net.Result r, String conv) {
        synchronized (this) {
            state = STATE_IDLE;
            status = "";
            S40Message m = r.msg;
            String st = m == null ? "" : m.field("status");
            if (r.ok() && "ok".equals(st)) {
                resetLocal();
                conversation = conv;
                if (m.flag("older")) {
                    add(KIND_INFO, L.s("Daha eski mesajlar gösterilmiyor (Claude onları hâlâ hatırlıyor).",
                            "Older messages are not shown (Claude still remembers them)."), false);
                }
                parseHistory(m.text, r.bodyCut);
                add(KIND_INFO, L.s("Sohbet açıldı. Yazarak devam edebilirsiniz.", "Chat opened. Write to continue it."), false);
            } else if ("conversation_not_found".equals(st)) {
                status = L.s("Sohbet yok", "Chat not found");
                add(KIND_ERROR, L.s("Sohbet sunucuda bulunamadı (silinmiş veya süresi dolmuş olabilir).",
                        "Chat not found on the server (deleted or expired)."), false);
            } else {
                status = L.s("Yüklenemedi", "Not loaded");
                add(KIND_ERROR, L.s("Sohbet yüklenemedi. ", "Could not load the chat. ")
                        + (r.ok() ? (st.length() > 0 ? st : "?") : Net.explain(r)), false);
            }
            jobConversation = null;
            version++;
        }
        changed(false);
    }

    /**
     * Called with the lock held. History text: per message "u N" or "a N"
     * (N = UTF-16 length), newline, the text, newline.
     */
    private void parseHistory(String t, boolean bodyCut) {
        int pos = 0;
        int n = t.length();
        while (pos < n) {
            int nl = t.indexOf('\n', pos);
            if (nl < 0 || nl - pos < 3) {
                break;
            }
            char role = t.charAt(pos);
            int len = Text.parseInt(t.substring(pos + 2, nl), -1);
            int start = nl + 1;
            if (len < 0 || start + len > n) {
                break; // cut body: drop the incomplete message
            }
            entries.addElement(new Entry(role == 'a' ? KIND_CLAUDE : KIND_USER, t.substring(start, start + len), false,
                    0, 0, null, null));
            pos = start + len + 1;
        }
        if (bodyCut) {
            add(KIND_INFO, L.s("(sohbetin sonu yüklenemedi)", "(the end of the chat did not load)"), false);
        }
        trim();
    }

    private void finishDelete(Net.Result r) {
        synchronized (this) {
            state = STATE_IDLE;
            S40Message m = r.msg;
            String st = m == null ? "" : m.field("status");
            if (r.ok() && ("deleted".equals(st) || "conversation_not_found".equals(st))) {
                resetLocal();
                ChatStore.delete();
                add(KIND_INFO, L.s("Sohbet sunucudan silindi.", "Chat deleted from the server."), false);
            } else {
                status = L.s("Silinemedi", "Not deleted");
                add(KIND_ERROR, L.s("Sohbet silinemedi. ", "Could not delete the chat. ") + (r.ok() ? st : Net.explain(r)), false);
            }
            version++;
        }
        changed(false);
    }

    // ------------------------------------------------------------ helpers

    /** Called with the lock held. Keeps the transcript within RAM limits. */
    private void add(int kind, String text, boolean truncated) {
        add(new Entry(kind, text, truncated));
    }

    private void add(Entry e) {
        entries.addElement(e);
        trim();
    }

    private void trim() {
        int chars = 0;
        for (int i = 0; i < entries.size(); i++) {
            chars += ((Entry) entries.elementAt(i)).text.length();
        }
        while (entries.size() > 1 && (entries.size() > MAX_ENTRIES || chars > MAX_CHARS)) {
            chars -= ((Entry) entries.elementAt(0)).text.length();
            entries.removeElementAt(0);
        }
        version++;
    }

    /** Snapshot of the messages worth keeping on the phone (ChatStore). */
    synchronized Vector keepable() {
        Vector out = new Vector();
        for (int i = 0; i < entries.size(); i++) {
            Entry e = (Entry) entries.elementAt(i);
            if (e.kind == KIND_USER || e.kind == KIND_CLAUDE || e.kind == KIND_TEST) {
                out.addElement(e);
            }
        }
        return out;
    }

    private void changed(boolean newReply) {
        persist();
        View v = view;
        if (v != null) {
            v.sessionChanged(newReply);
        }
    }

    /** Writes the transcript to the phone when that is turned on and it changed. */
    void persist() {
        if (!midlet.settings.saveChat) {
            return;
        }
        String conv;
        synchronized (this) {
            if (state != STATE_IDLE || version == savedVersion) {
                return;
            }
            savedVersion = version;
            conv = conversation;
        }
        ChatStore.save(conv, keepable());
    }

    /** Forces the next persist() to write (setting just turned on). */
    synchronized void markUnsaved() {
        savedVersion = -1;
    }
}

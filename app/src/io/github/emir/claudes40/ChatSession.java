package io.github.emir.claudes40;

import java.util.Vector;

/**
 * Conversation state in RAM only (nothing is written to the phone).
 *
 * - At most one request in flight.
 * - The message being sent stays in `draft` until a definite answer arrives,
 *   so an error never loses what the user typed.
 * - A request keeps its request_id until it is resolved. "Tekrar dene"
 *   re-sends the SAME id: the gateway then returns the recorded result
 *   instead of calling Claude again. Nothing is re-sent automatically.
 * - If the gateway reports "uncertain", the id is dropped; sending the draft
 *   again is a new (paid) request and needs a new user action.
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

    /** RAM limits for the on-screen transcript. */
    static final int MAX_ENTRIES = 30;
    static final int MAX_CHARS = 12000;
    static final int MAX_MESSAGE = 1000;

    static final class Entry {
        final int kind;
        final String text;
        final boolean truncated;
        final long time;

        Entry(int kind, String text, boolean truncated) {
            this.kind = kind;
            this.text = text;
            this.truncated = truncated;
            this.time = System.currentTimeMillis();
        }
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

    // the request being resolved (null when none)
    private String pendingId;
    private String pendingText;
    private String pendingConversation;
    private boolean canRetry;

    // work item for the worker thread
    private boolean deleteJob;

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

    synchronized boolean busy() {
        return state != STATE_IDLE;
    }

    synchronized boolean canRetry() {
        return state == STATE_IDLE && canRetry && pendingId != null;
    }

    /** True while a chat request (not a delete) is running. */
    synchronized boolean typing() {
        return state != STATE_IDLE && !deleteJob;
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
            begin(false);
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
            begin(false);
        }
        changed(false);
        return null;
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
            begin(true);
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

    private void begin(boolean delete) {
        deleteJob = delete;
        state = STATE_SENDING;
        status = delete ? L.s("Siliniyor...", "Deleting...") : L.s("Gönderiliyor...", "Sending...");
        version++;
        new Thread(this).start();
    }

    // ------------------------------------------------------------ worker

    public void phase(int phase) {
        if (phase >= Net.PHASE_RESPONSE) {
            synchronized (this) {
                state = STATE_WAITING;
                status = deleteJob ? L.s("Siliniyor...", "Deleting...") : L.s("Yanıt bekleniyor...", "Waiting for reply...");
                version++;
            }
            changed(false);
        }
    }

    public void run() {
        boolean delete;
        String id;
        String text;
        String conv;
        synchronized (this) {
            delete = deleteJob;
            id = pendingId;
            text = pendingText;
            conv = delete ? conversation : pendingConversation;
        }
        Settings s = midlet.settings;
        if (delete) {
            Net.Result r = Net.request(s.url + "/v1/delete", "POST", s.token,
                    S40Message.format(new String[] { "conversation" }, new String[] { conv }, ""),
                    midlet.userAgent(), this);
            finishDelete(r);
            return;
        }
        if (s.testMode) {
            mockReply(text, conv);
            return;
        }
        Net.Result r = Net.request(s.url + "/v1/chat", "POST", s.token,
                S40Message.format(new String[] { "request", "conversation" }, new String[] { id, conv }, text),
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
        }
        if ("ok".equals(st)) {
            conversation = m.field("conversation");
            boolean cut = m.flag("truncated") || bodyCut;
            if (m.flag("refused")) {
                add(KIND_INFO, L.s("Claude bu isteğe yanıt vermedi.", "Claude declined to answer this one."), false);
            } else {
                add(m.flag("mock") ? KIND_TEST : KIND_CLAUDE, m.text, cut);
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

    private void finishDelete(Net.Result r) {
        synchronized (this) {
            state = STATE_IDLE;
            S40Message m = r.msg;
            String st = m == null ? "" : m.field("status");
            if (r.ok() && ("deleted".equals(st) || "conversation_not_found".equals(st))) {
                resetLocal();
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
        entries.addElement(new Entry(kind, text, truncated));
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

    private void changed(boolean newReply) {
        View v = view;
        if (v != null) {
            v.sessionChanged(newReply);
        }
    }
}

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
 * - A photo uploaded with /v1/image (Photo) is attached to the next message
 *   ("image" field) and kept with the request until it is resolved, so
 *   "Tekrar dene" sends the same photo; it is dropped once a reply arrives.
 * - A conversation has a model (server 0.7.0+). A model chosen for a new
 *   chat or with "Model" goes with the next message ("model" field) and
 *   stays with the request until it is resolved; the server then answers
 *   with that model from this message on. Every reply is labelled with the
 *   model that wrote it.
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
        /** Name of the model that wrote a reply; "" if unknown (then it was Claude). */
        final String model;
        /**
         * Notes (info/error): the longer explanation behind the one-line
         * text, shown only when the user opens the note; "" if none.
         */
        final String detail;

        /** An info or error note: a short line, the explanation on request. */
        Entry(int kind, String text, String detail) {
            this(kind, text, false, System.currentTimeMillis(), 0, null, null, "", nextUid(), detail);
        }

        Entry(int kind, String text, boolean truncated, long time, int searched, String request, String next,
                String model) {
            this(kind, text, truncated, time, searched, request, next, model, nextUid(), "");
        }

        private Entry(int kind, String text, boolean truncated, long time, int searched, String request, String next,
                String model, int uid, String detail) {
            this.uid = uid;
            this.model = model == null ? "" : model;
            this.detail = detail == null ? "" : detail;
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
            return new Entry(kind, text + part, cut, time, searched, request, nextOffset, model, uid, detail);
        }

        /** The rest can no longer be fetched; keeps uid. */
        Entry cutOff() {
            return new Entry(kind, text, true, time, searched, null, null, model, uid, detail);
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
    /** The note of the last model switch, replaced by the next one if nothing came between. */
    private Entry modelNote;
    private String draft = "";
    private int version;
    private String remaining = "";
    /** Credits left (server extension "balance"), "" if unknown. */
    private String balance = "";
    /** When `remaining` arrived; the server counts per UTC day. */
    private long remainingAt;
    /** When the running request started (for the elapsed time on screen). */
    private long startedAt;

    /** Photo id (/v1/image) for the next message, "" if none. */
    private String image = "";
    /** Model id sent with the next message; "" = the conversation's own (the server knows it). */
    private String model = "";
    /** Name of the conversation's model, "" if not known yet. */
    private String modelName = "";

    // the request being resolved (null when none)
    private String pendingId;
    private String pendingText;
    private String pendingImage;
    private String pendingConversation;
    private String pendingModel;
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

    /** The account's balance in credits ("" = unknown or no credits on this server). */
    synchronized String balance() {
        return balance;
    }

    synchronized void setBalance(String b) {
        balance = b == null ? "" : b;
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

    /** "[Photo] " before a message that was sent with a photo. */
    static String photoMark() {
        return L.t("[Photo] ");
    }

    synchronized boolean hasImage() {
        return image.length() > 0;
    }

    /** A photo for the next message (Photo, after the upload). */
    synchronized void setImage(String id) {
        image = id == null ? "" : id;
        version++;
    }

    synchronized void clearImage() {
        image = "";
        version++;
    }

    /** The model id this chat uses, if known ("" = the server's default or not known). */
    synchronized String modelId() {
        return model;
    }

    /** Name of the model that answers in this chat: known, chosen for new chats, or "Claude". */
    synchronized String ai() {
        if (modelName.length() > 0) {
            return modelName;
        }
        String n = Models.name(Models.startId());
        return n.length() > 0 ? n : "Claude";
    }

    /** Switches this chat to another model from the next message on. */
    String setModel(String id) {
        synchronized (this) {
            if (state != STATE_IDLE) {
                return L.t("A request is still running.");
            }
            model = id;
            modelName = Models.name(id);
            boolean written = conversation.length() > 0;
            for (int i = 0; i < entries.size() && !written; i++) {
                written = ((Entry) entries.elementAt(i)).kind != KIND_INFO;
            }
            if (!written) {
                // nothing written yet: the one "New chat" note names the new model
                entries.removeAllElements();
                newChatNote();
            } else {
                // switching again replaces the last switch's note
                if (modelNote != null && entries.size() > 0 && entries.lastElement() == modelNote) {
                    entries.removeElementAt(entries.size() - 1);
                }
                modelNote = new Entry(KIND_INFO, L.f("Next replies: {0}", ai()),
                        L.f("{0} answers from your next message on and sees this chat's history too.", ai()));
                add(modelNote);
            }
        }
        changed(false);
        return null;
    }

    // ------------------------------------------------------------ actions

    /** Returns null if started, or a reason why not. */
    String send(String text) {
        synchronized (this) {
            if (state != STATE_IDLE) {
                return L.t("A request is still running.");
            }
            String t = text == null ? "" : text.trim();
            if (t.length() == 0) {
                return L.t("The message is empty.");
            }
            if (t.length() > MAX_MESSAGE) {
                return L.f("Messages can be at most {0} characters.", String.valueOf(MAX_MESSAGE));
            }
            draft = t;
            pendingId = Text.requestId();
            pendingText = t;
            pendingImage = image.length() > 0 ? image : null;
            pendingConversation = conversation;
            pendingModel = model;
            canRetry = false;
            add(new Entry(KIND_USER, pendingImage != null ? photoMark() + t : t, false, System.currentTimeMillis(), 0, null,
                    null, ""));
            begin(JOB_CHAT);
        }
        changed(false);
        return null;
    }

    /** Re-sends the unresolved request with the same request_id. */
    String retry() {
        synchronized (this) {
            if (!canRetry()) {
                return L.t("Nothing to retry.");
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
                return L.t("A request is still running.");
            }
            Entry e = uid == 0 ? lastMore() : find(uid);
            if (e != null && !e.more()) {
                e = null;
            }
            if (e == null) {
                return L.t("There is no more to this reply.");
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
                return L.t("A request is still running.");
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
            note(KIND_INFO, L.t("Saved on this phone"),
                    L.t("This chat is kept on the phone and readable offline. Write to continue it on the server."));
            for (int i = 0; i < saved.size(); i++) {
                Entry e = (Entry) saved.elementAt(i);
                entries.addElement(e);
                if (e.kind == KIND_CLAUDE && e.model.length() > 0) {
                    modelName = e.model; // the server continues with the conversation's model
                }
            }
            trim();
            savedVersion = version;
        }
        changed(false);
    }

    /**
     * Starts a new conversation with this model id ("" = the server's
     * default); the old one stays on the server until it expires.
     */
    String newChat(String modelId) {
        synchronized (this) {
            if (state != STATE_IDLE) {
                return L.t("A request is still running.");
            }
            resetLocal();
            model = modelId == null ? "" : modelId;
            modelName = Models.name(model);
            newChatNote();
        }
        changed(false);
        return null;
    }

    /** "New chat · <model>". Called with the lock held. */
    private void newChatNote() {
        String with = modelName.length() > 0 ? " · " + modelName : "";
        note(KIND_INFO, L.t("New chat") + with,
                L.t("The previous chat stays on the server for 30 days; open it again from Chats."));
    }

    /** Deletes the current conversation on the server, then starts a new one. */
    String deleteChat() {
        synchronized (this) {
            if (state != STATE_IDLE) {
                return L.t("A request is still running.");
            }
            if (conversation.length() == 0 || midlet.settings.testMode) {
                resetLocal();
                note(KIND_INFO, L.t("Chat cleared"), "");
                changed(false);
                return null;
            }
            begin(JOB_DELETE);
        }
        changed(false);
        return null;
    }

    /** The chat with this id was deleted elsewhere (ChatList): if it is open, start afresh. */
    void forget(String conv) {
        synchronized (this) {
            if (state != STATE_IDLE || !conversation.equals(conv)) {
                return;
            }
            resetLocal();
            ChatStore.delete();
            note(KIND_INFO, L.t("Chat deleted"), "");
        }
        changed(false);
    }

    private void resetLocal() {
        entries.removeAllElements();
        conversation = "";
        model = "";
        modelName = "";
        pendingId = null;
        pendingText = null;
        pendingImage = null;
        pendingModel = null;
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
            return L.t("Deleting...");
        case JOB_MORE:
            return L.t("Loading more...");
        case JOB_HISTORY:
            return L.t("Loading chat...");
        default:
            return L.t("Sending...");
        }
    }

    // ------------------------------------------------------------ worker

    public void phase(int phase) {
        if (phase >= Net.PHASE_RESPONSE) {
            synchronized (this) {
                state = STATE_WAITING;
                status = job == JOB_CHAT ? L.t("Waiting for reply...") : jobLabel();
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
        String img;
        String mdl;
        synchronized (this) {
            mdl = pendingModel;
            j = job;
            id = pendingId;
            text = pendingText;
            img = pendingImage;
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
                    S40Message.format(new String[] { "conversation", "images", "models" }, new String[] { conv, "1", "1" }, ""),
                    midlet.userAgent(), this);
            finishHistory(r, conv);
            return;
        }
        if (s.testMode) {
            mockReply(text, conv, img != null);
            return;
        }
        Net.Result r = Net.request(s.url + "/v1/chat", "POST", s.token, chatBody(s, id, conv, text, img, mdl),
                midlet.userAgent(), this);
        finishChat(r);
    }

    /**
     * The /v1/chat request. Optional fields (0.7+, ignored by older servers):
     * the user's notes for Claude, and "calendar" + the phone's clock when
     * the phone can add calendar entries (Claude then may end a reply with an
     * entry line, see Cal). 0.9+: "image" names a photo uploaded with /v1/image.
     * 0.10+: "model" when one was chosen (server 0.7.0+).
     */
    private static String chatBody(Settings s, String id, String conv, String text, String img, String mdl) {
        Vector k = new Vector();
        Vector v = new Vector();
        k.addElement("request");
        v.addElement(id);
        k.addElement("conversation");
        v.addElement(conv);
        k.addElement("search");
        v.addElement(s.webSearch ? "1" : "0");
        if (img != null && img.length() == 32) { // not the test mode's stand-in
            k.addElement("image");
            v.addElement(img);
        }
        if (mdl != null && mdl.length() > 0) {
            k.addElement("model");
            v.addElement(mdl);
        }
        if (s.instructions.trim().length() > 0) {
            k.addElement("instructions");
            v.addElement(s.instructions.trim());
        }
        if (ClaudeS40MIDlet.hasPim()) {
            k.addElement("calendar");
            v.addElement("1");
            k.addElement("local-time");
            v.addElement(Text.iso(System.currentTimeMillis()));
        }
        String[] keys = new String[k.size()];
        String[] values = new String[v.size()];
        k.copyInto(keys);
        v.copyInto(values);
        return S40Message.format(keys, values, text);
    }

    private void mockReply(String text, String conv, boolean photo) {
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
            String reply = L.f("[Test mode] This is not a real {0} reply. No network was used.\nYour message has {1} characters; earlier messages in this chat: {2}.\nI received: \"{3}\"", new String[] { ai(), String.valueOf(text.length()), String.valueOf((prior - 1)), String.valueOf(text) });
            if (photo) {
                reply += L.t("\nPhoto: attached (not sent to a server).");
            }
            String low = text.toLowerCase();
            if (low.indexOf("takvim") >= 0 || low.indexOf("calendar") >= 0 || low.indexOf("remind") >= 0) {
                // a fake entry line, to try "Add to calendar" without the network
                reply += "\nEVENT: " + Text.iso(System.currentTimeMillis() + 24L * 60 * 60 * 1000).substring(0, 10)
                        + " 15:00 | " + L.t("Test mode event");
            }
            conversation = conv.length() > 0 ? conv : "test";
            add(new Entry(KIND_TEST, reply, false, System.currentTimeMillis(), 0, null, null, ""));
            dropSentImage();
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
                status = r.httpCode < 0 ? L.t("Could not connect") : L.t("No reply");
                note(KIND_ERROR, status, Net.explain(r) + L.f("\n\n'Retry' asks about the same request; it is not sent to {0} twice.", ai()));
                canRetry = true;
            } else if (m == null) {
                status = L.t("No reply");
                note(KIND_ERROR, L.t("Unrecognised server reply"),
                        L.f("HTTP {0}. Carrier network or wrong address?", String.valueOf(r.httpCode)));
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
        if (m.field("trial-model").length() > 0 && !m.field("trial-model").equals(Models.trial())) {
            Models.setTrial(m.field("trial-model")); // a trial account (server 0.9.6+): its only model
        }
        if (m.field("balance").length() > 0) {
            balance = m.field("balance");
            if (!midlet.settings.credits) {
                midlet.settings.credits = true; // e.g. a setup restored from the backup
                midlet.settings.save();
            }
        }
        if ("ok".equals(st)) {
            conversation = m.field("conversation");
            if (m.field("model").length() > 0) {
                // server 0.7.0+: the model that answered; it also answers what comes next
                model = m.field("model");
                modelName = m.field("model-name").length() > 0 ? m.field("model-name") : Models.name(model);
            }
            // "truncated" is also set while parts are left ("more")
            String next = m.flag("more") && !bodyCut && m.field("next").length() > 0 ? m.field("next") : null;
            boolean cut = (m.flag("truncated") && next == null) || bodyCut;
            if (m.flag("refused")) {
                note(KIND_INFO, L.f("{0} declined", ai()),
                        L.f("{0} declined to answer this one. Ask differently or pick another model.", ai()));
            } else {
                add(new Entry(m.flag("mock") ? KIND_TEST : KIND_CLAUDE, m.text, cut, System.currentTimeMillis(),
                        Text.parseInt(m.field("searched"), 0), m.field("request"), next, m.field("model-name")));
            }
            dropSentImage();
            resolved();
            return true;
        }
        if ("pending".equals(st)) {
            status = L.t("Still working");
            note(KIND_INFO, L.t("Still working on it"),
                    L.t("Choose 'Retry' in a moment; it asks about the same request and is not charged again."));
            canRetry = true;
            return false;
        }
        if ("busy".equals(st)) {
            status = L.t("Busy");
            note(KIND_INFO, L.t("Another request is running"),
                    L.t("Choose 'Retry' in a moment."));
            canRetry = true;
            return false;
        }
        if ("relay".equals(m.field("source"))) {
            // the TLS relay could not complete the call to the gateway; the
            // gateway may have received it, so keep the id: "Tekrar dene"
            // gets the recorded result instead of a second paid call
            status = L.t("No reply");
            note(KIND_ERROR, status, L.f("The relay got no answer from the AIKON server ({0}). 'Retry' asks about the same request; it is not sent to {1} twice.", st, ai()));
            canRetry = true;
            return false;
        }
        String msg;
        if ("limit".equals(st)) {
            status = L.t("Limit reached");
            msg = L.t("Daily limit reached. Try again tomorrow (UTC).");
        } else if ("uncertain".equals(st)) {
            status = L.t("Unknown result");
            msg = L.f("Unknown result: the request may have reached {0}. Nothing was re-sent. Your draft is kept; sending it again is a new (paid) request.", ai());
        } else if ("conversation_full".equals(st)) {
            status = L.t("Chat full");
            msg = L.t("This chat got too long. Continue with 'New chat'.");
        } else if ("conversation_not_found".equals(st)) {
            status = L.t("Chat not found");
            msg = L.t("Chat not found on the server (it may have expired). Continue with 'New chat'.");
        } else if ("unauthorized".equals(st)) {
            status = L.t("Not authorised");
            msg = L.t("Access code invalid or revoked. Settings > Pair this phone.");
        } else if ("credit".equals(st)) {
            status = L.t("Not enough credits");
            String shop = midlet.shopLine();
            msg = (balance.length() > 0
                    ? L.f("Not enough credits for this message (balance: {0}).", balance)
                    : L.t("Not enough credits for this message."))
                    + L.t(" Add a code under main menu > Credits; your message is kept as a draft.")
                    + (shop.length() > 0 ? "\n\n" + shop : "");
        } else if ("trial_model".equals(st)) {
            status = L.t("Trial: one model");
            String trial = Models.trial();
            if (trial.length() > 0 && !trial.equals(model)) {
                // switch this chat to the trial's model; the draft waits to be sent again
                model = trial;
                modelName = Models.name(trial);
                msg = L.f("The free trial works with {0} only, so this chat now uses it. Your message is kept as a "
                        + "draft: send it again. A credit code opens every model (main menu > Credits).",
                        modelName.length() > 0 ? modelName : trial);
            } else {
                msg = L.t("The free trial works with one model only. Pick it in the Model menu; a credit code opens every "
                        + "model (main menu > Credits). Your message is kept as a draft.");
            }
        } else if ("account_disabled".equals(st)) {
            status = L.t("Account closed");
            msg = L.t("This account is closed. Write to the server's support address.");
        } else if ("billing".equals(st)) {
            status = L.t("No credits");
            msg = L.f("The server's {0} API account is out of credits. Send again once it is topped up.", ai());
        } else if ("rate_limited".equals(st) || "overloaded".equals(st)) {
            status = L.f("{0} is busy", ai());
            msg = L.f("{0} is busy right now. Send again shortly.", ai());
        } else if ("model_unavailable".equals(st)) {
            status = L.t("Model not offered");
            msg = L.t("The chosen model is no longer offered by the server. Pick another with Menu > Model; "
                    + "your message is kept as a draft.");
            model = "";
            modelName = "";
        } else if ("image_not_found".equals(st)) {
            status = L.t("Photo not found");
            msg = L.t("The photo is not on the server: unused for a day, or used in another chat. Add it again; "
                    + "your message is kept as a draft.");
            image = "";
        } else if ("too_large".equals(st)) {
            status = L.t("Too long");
            msg = L.t("The message is too long.");
        } else {
            status = L.t("No reply");
            msg = L.t("No reply (") + (st.length() > 0 ? st : "?") + ").";
        }
        note(KIND_ERROR, status, msg);
        // definite answer: forget the id, keep the draft (and the photo)
        pendingId = null;
        pendingText = null;
        pendingImage = null;
        pendingModel = null;
        canRetry = false;
        return false;
    }

    /** Called with the lock held: the photo went out with a reply, so the next message has none. */
    private void dropSentImage() {
        if (pendingImage != null && pendingImage.equals(image)) {
            image = "";
        }
    }

    /** Called with the lock held after a successful exchange. */
    private void resolved() {
        state = STATE_IDLE;
        status = "";
        draft = "";
        pendingId = null;
        pendingText = null;
        pendingImage = null;
        pendingModel = null;
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
                note(KIND_INFO, L.t("The rest is gone"),
                        L.t("The rest of this reply is no longer on the server (kept for 7 days)."));
            } else if (i >= 0) {
                status = L.t("Could not load more");
                note(KIND_ERROR, status, L.f("Press 0 to try again (free; {0} is not asked again).\n\n", ai())
                        + (r.ok() ? (m == null ? "?" : m.field("status")) : Net.explain(r)));
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
                    note(KIND_INFO, L.t("Older messages hidden"),
                            L.t("Older messages are not shown on the phone; the model still sees them."));
                }
                parseHistory(m.text, r.bodyCut);
                note(KIND_INFO, L.t("Chat opened"), "");
            } else if ("conversation_not_found".equals(st)) {
                status = L.t("Chat not found");
                note(KIND_ERROR, status, L.t("Chat not found on the server (deleted or expired)."));
            } else {
                status = L.t("Not loaded");
                note(KIND_ERROR, status, r.ok() ? (st.length() > 0 ? st : "?") : Net.explain(r));
            }
            jobConversation = null;
            version++;
        }
        changed(false);
    }

    /**
     * Called with the lock held. History text: per message "u N" or "a N"
     * (N = UTF-16 length) and space-separated marks, newline, the text,
     * newline. Marks: "i" a message sent with a photo (images: 1), "m=ID"
     * the model of a reply (models: 1); unknown marks are ignored.
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
            String head = t.substring(pos + 2, nl);
            int sp = head.indexOf(' ');
            int len = Text.parseInt(sp < 0 ? head : head.substring(0, sp), -1);
            boolean photo = false;
            String mdl = "";
            while (sp >= 0) {
                int end = head.indexOf(' ', sp + 1);
                String mark = head.substring(sp + 1, end < 0 ? head.length() : end);
                if (mark.equals("i")) {
                    photo = true;
                } else if (mark.startsWith("m=")) {
                    mdl = mark.substring(2);
                }
                sp = end;
            }
            int start = nl + 1;
            if (len < 0 || start + len > n) {
                break; // cut body: drop the incomplete message
            }
            String body = t.substring(start, start + len);
            String name = mdl.length() > 0 ? Models.name(mdl) : "";
            if (mdl.length() > 0) {
                // the newest reply's model is the conversation's model
                model = mdl;
                modelName = name.length() > 0 ? name : mdl;
            }
            entries.addElement(new Entry(role == 'a' ? KIND_CLAUDE : KIND_USER, photo ? photoMark() + body : body, false,
                    0, 0, null, null, name.length() > 0 ? name : mdl));
            pos = start + len + 1;
        }
        if (bodyCut) {
            note(KIND_INFO, L.t("The end did not load"), "");
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
                note(KIND_INFO, L.t("Chat deleted"), "");
            } else {
                status = L.t("Not deleted");
                note(KIND_ERROR, status, r.ok() ? st : Net.explain(r));
            }
            version++;
        }
        changed(false);
    }

    // ------------------------------------------------------------ helpers

    /** Called with the lock held: a one-line note, its explanation (or "") on request. */
    private void note(int kind, String text, String detail) {
        add(new Entry(kind, text, detail));
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

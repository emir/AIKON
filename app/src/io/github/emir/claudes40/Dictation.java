package io.github.emir.claudes40;

import java.util.Timer;
import java.util.TimerTask;

import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.CommandListener;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.Form;
import javax.microedition.lcdui.Gauge;
import javax.microedition.lcdui.StringItem;

/**
 * Voice message: records up to 30 seconds (Rec), sends the clip to the
 * server (/v1/transcribe) and puts the text it gets back into the message
 * editor. Nothing is sent to Claude here: the user reads the text, fixes it
 * if needed and sends it with the editor's own Send.
 *
 * The transcription is a paid call on the server, so the chat rules apply:
 * nothing is sent again on its own. "Retry" asks about the SAME request id
 * (the server answers from its record, never a second call); "Send again"
 * is a new request for the same clip and needs the user's choice. The clip
 * stays in RAM only until this screen closes.
 */
final class Dictation implements CommandListener, Runnable, Net.Listener {

    static final int MAX_SECONDS = 30;
    /** 30 s of 8 kHz 16-bit PCM plus headers; AMR needs far less. */
    private static final int MAX_BYTES = 500000;
    /** Shorter than this is a key press, not speech. */
    private static final int MIN_BYTES = 400;

    private static final int JOB_START = 0;
    private static final int JOB_STOP = 1;
    private static final int JOB_SEND = 2;

    private final ClaudeS40MIDlet midlet;
    /** Opened from the editor (Cancel goes back there) or from the chat. */
    private final boolean fromComposer;
    private final Form form;
    private final StringItem status;
    private final Gauge gauge;
    private final Command doneCmd = new Command(L.s("Bitir", "Done"), Command.OK, 1);
    private final Command retryCmd = new Command(L.s("Tekrar dene", "Retry"), Command.OK, 1);
    private final Command resendCmd = new Command(L.s("Yeniden gönder", "Send again"), Command.SCREEN, 2);
    private final Command againCmd = new Command(L.s("Yeniden kaydet", "Record again"), Command.SCREEN, 3);
    private final Command cancelCmd = new Command(L.s("Vazgeç", "Cancel"), Command.BACK, 1);
    private final Command helpCmd = Help.command();

    private Rec rec;
    private Timer timer;
    private int seconds;
    private int job;
    /** Bumped whenever the screen moves on; a worker with an older value is ignored. */
    private int generation;
    private byte[] audio;
    private String requestId;
    private boolean closed;

    Dictation(ClaudeS40MIDlet midlet, boolean fromComposer) {
        this.midlet = midlet;
        this.fromComposer = fromComposer;
        form = new Form(L.s("Sesle yaz", "Dictate"));
        status = new StringItem(null, "");
        gauge = new Gauge("", false, MAX_SECONDS, 0);
        form.append(status);
        form.append(gauge);
        form.addCommand(helpCmd);
        form.setCommandListener(this);
    }

    void start() {
        midlet.display().setCurrent(form);
        record();
    }

    // ------------------------------------------------------------ steps

    private synchronized void record() {
        generation++;
        audio = null;
        requestId = null;
        seconds = 0;
        gauge.setValue(0);
        gauge.setLabel("0 / " + MAX_SECONDS + L.s(" sn", " s"));
        show(L.s("Mikrofon açılıyor...", "Opening the microphone..."), new Command[] { cancelCmd });
        job = JOB_START;
        new Thread(this).start();
    }

    /** Recording started (worker): count up to MAX_SECONDS, then stop on our own. */
    private synchronized void recording() {
        show(L.s("Konuşun. Bitince 'Bitir'e basın.", "Speak now. Press 'Done' when you finish."),
                new Command[] { doneCmd, cancelCmd });
        timer = new Timer();
        timer.schedule(new TimerTask() {
            public void run() {
                tick();
            }
        }, 1000, 1000);
    }

    private void tick() {
        boolean stop;
        synchronized (this) {
            if (timer == null) {
                return;
            }
            seconds++;
            gauge.setValue(Math.min(seconds, MAX_SECONDS));
            gauge.setLabel(seconds + " / " + MAX_SECONDS + L.s(" sn", " s"));
            stop = seconds >= MAX_SECONDS;
        }
        if (stop) {
            finish();
        }
    }

    private synchronized void finish() {
        if (timer == null) {
            return;
        }
        timer.cancel();
        timer = null;
        show(L.s("Kayıt bitiyor...", "Finishing the recording..."), new Command[] { cancelCmd });
        job = JOB_STOP;
        new Thread(this).start();
    }

    /** Sends the clip; a new request id unless the same request is asked about again. */
    private synchronized void send(boolean sameRequest) {
        generation++;
        if (!sameRequest || requestId == null) {
            requestId = Text.requestId();
        }
        show(L.s("Gönderiliyor (", "Sending (") + (audio.length + 1023) / 1024 + " KB)...", new Command[] { cancelCmd });
        job = JOB_SEND;
        new Thread(this).start();
    }

    public void run() {
        int j;
        int gen;
        Rec r;
        synchronized (this) {
            j = job;
            gen = generation;
            r = rec;
        }
        if (j == JOB_START) {
            Rec nr = new Rec();
            String err = nr.start(MAX_BYTES);
            synchronized (this) {
                if (closed || gen != generation) {
                    nr.cancel();
                    return;
                }
                if (err != null) {
                    fail(err + deviceInfo(), false, false);
                    return;
                }
                rec = nr;
            }
            recording();
        } else if (j == JOB_STOP) {
            byte[] b = r == null ? null : r.stop();
            synchronized (this) {
                rec = null;
                if (closed || gen != generation) {
                    return;
                }
                if (b == null || b.length < MIN_BYTES) {
                    fail(L.s("Kayıt boş ya da çok kısa.", "The recording is empty or too short.") + deviceInfo(),
                            false, false);
                    return;
                }
                audio = b;
            }
            send(false);
        } else {
            upload(gen);
        }
    }

    private void upload(int gen) {
        byte[] a;
        String id;
        synchronized (this) {
            a = audio;
            id = requestId;
        }
        Settings s = midlet.settings;
        if (s.testMode) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                // ignore
            }
            done(gen, L.s("[Test modu] Bu gerçek bir yazıya dökme değil; ağ kullanılmadı. Kayıt: ",
                    "[Test mode] This is not a real transcription; no network was used. Recording: ")
                    + a.length + L.s(" bayt.", " bytes."));
            return;
        }
        String url = s.url + "/v1/transcribe?request=" + id + "&lang=" + (L.turkish() ? "tr" : "en");
        Net.Result r = Net.upload(url, s.token, a, contentType(a), midlet.userAgent(), this);
        synchronized (this) {
            if (closed || gen != generation) {
                return;
            }
            if (!r.ok()) {
                fail(Net.explain(r) + L.s("\n'Tekrar dene' aynı kaydı sorar; ikinci kez ücretlendirilmez.",
                        "\n'Retry' asks about the same recording; it is not charged twice."), true, false);
                return;
            }
            if (r.msg == null) {
                fail(L.s("Sunucu yanıtı tanınmadı (HTTP " + r.httpCode + ").",
                        "Unrecognised server reply (HTTP " + r.httpCode + ")."), true, false);
                return;
            }
        }
        handle(gen, r.msg, a.length);
    }

    private void handle(int gen, S40Message m, int bytes) {
        String st = m.field("status");
        if ("ok".equals(st) && m.text.trim().length() > 0) {
            done(gen, m.text.trim());
            return;
        }
        synchronized (this) {
            if (closed || gen != generation) {
                return;
            }
            if ("ok".equals(st) || "no_speech".equals(st)) {
                fail(L.s("Konuşma anlaşılamadı. Yeniden kaydedin.", "No speech was recognised. Record again."),
                        false, false);
            } else if ("pending".equals(st)) {
                fail(L.s("Sunucu hâlâ yazıya döküyor. Birkaç saniye sonra 'Tekrar dene'.",
                        "The server is still working on it. 'Retry' in a few seconds."), true, false);
            } else if ("busy".equals(st)) {
                fail(L.s("Başka bir ses kaydı işleniyor. Birazdan 'Tekrar dene'.",
                        "Another recording is being processed. 'Retry' in a moment."), true, false);
            } else if ("limit".equals(st)) {
                fail(L.s("Bugünkü sesle yazma hakkınız bitti. Yarın (UTC) yenilenir.",
                        "No voice messages left today. More tomorrow (UTC)."), false, false);
            } else if ("unavailable".equals(st)) {
                fail(L.s("Sunucuda sesle yazma açık değil.", "Voice messages are not turned on on the server."),
                        false, false);
            } else if ("too_long".equals(st)) {
                fail(L.s("Kayıt çok uzun. En fazla " + MAX_SECONDS + " saniye.",
                        "The recording is too long. At most " + MAX_SECONDS + " seconds."), false, false);
            } else if ("too_short".equals(st)) {
                fail(L.s("Kayıt çok kısa.", "The recording is too short."), false, false);
            } else if ("bad_audio".equals(st) || "too_large".equals(st)) {
                fail(L.s("Sunucu bu ses kaydını okuyamadı.", "The server could not read this recording.")
                        + "\n" + clipInfo(bytes) + deviceInfo(), false, false);
            } else if ("uncertain".equals(st)) {
                fail(L.s("Sonuç belirsiz: yazıya dökme servisi zamanında yanıt vermedi. 'Yeniden gönder' yeni "
                        + "(ücretli) bir istek olur.",
                        "Unknown result: the speech service did not answer in time. 'Send again' is a new "
                                + "(paid) request."), false, true);
            } else if ("rate_limited".equals(st) || "overloaded".equals(st) || "upstream_error".equals(st)) {
                fail(L.s("Yazıya dökme servisi şu an yanıt vermiyor (" + st + "). Biraz sonra 'Yeniden gönder'.",
                        "The speech service is not answering right now (" + st + "). 'Send again' later."), false,
                        true);
            } else if ("billing".equals(st) || "config_error".equals(st)) {
                fail(L.s("Sunucudaki yazıya dökme ayarında sorun var (" + st + ").",
                        "The server's speech-to-text setup has a problem (" + st + ")."), false, false);
            } else if ("credit".equals(st)) {
                fail(L.s("Kredi yetersiz. Ayarlar > Seçenekler > 'Kredi' ile yeni kod gir.",
                        "Not enough credits. Add a code in Settings > Options > 'Credits'."), false, false);
            } else if ("account_disabled".equals(st)) {
                fail(L.s("Hesap kapatılmış. Sunucunun destek adresine yaz.",
                        "The account is closed. Write to the server's support address."), false, false);
            } else if ("unauthorized".equals(st)) {
                fail(L.s("Eşleştirme geçersiz. Ayarlar > Seçenekler > 'Cihazı eşleştir'.",
                        "The pairing is not valid. Settings > Options > 'Pair this phone'."), false, false);
            } else {
                fail(L.s("Sunucu: ", "Server: ") + st, true, false);
            }
        }
    }

    private String clipInfo(int bytes) {
        return L.s("Kayıt: ", "Recording: ") + bytes + L.s(" bayt", " bytes")
                + (audio != null && audio.length >= 4 ? ", " + contentType(audio) : "");
    }

    /** Microphone details for a failed recording: what the phone says it can do. */
    private static String deviceInfo() {
        return "\n(" + ClaudeS40MIDlet.prop("audio.encodings") + ")";
    }

    static String contentType(byte[] a) {
        if (a.length >= 5 && a[0] == '#' && a[1] == '!' && a[2] == 'A' && a[3] == 'M' && a[4] == 'R') {
            return "audio/amr";
        }
        if (a.length >= 4 && a[0] == 'R' && a[1] == 'I' && a[2] == 'F' && a[3] == 'F') {
            return "audio/wav";
        }
        return "application/octet-stream";
    }

    /** Called with the lock held. retry: same request; resend: the same clip as a new request. */
    private void fail(String text, boolean retry, boolean resend) {
        generation++;
        Command[] cmds = new Command[4];
        int n = 0;
        if (retry && audio != null) {
            cmds[n++] = retryCmd;
        }
        if (resend && audio != null) {
            cmds[n++] = resendCmd;
        }
        cmds[n++] = againCmd;
        cmds[n++] = cancelCmd;
        Command[] c = new Command[n];
        System.arraycopy(cmds, 0, c, 0, n);
        show(text, c);
        Sound.play(midlet.settings, Sound.ERROR);
    }

    private void done(int gen, String text) {
        synchronized (this) {
            if (closed || gen != generation) {
                return;
            }
            closed = true;
            audio = null;
        }
        midlet.dictated(text);
    }

    // ------------------------------------------------------------ screen

    private Command[] shownCmds = new Command[0];

    private void show(String text, Command[] cmds) {
        status.setText(text);
        for (int i = 0; i < shownCmds.length; i++) {
            form.removeCommand(shownCmds[i]);
        }
        for (int i = 0; i < cmds.length; i++) {
            form.addCommand(cmds[i]);
        }
        shownCmds = cmds;
    }

    public void phase(int phase) {
        if (phase >= Net.PHASE_RESPONSE) {
            synchronized (this) {
                if (!closed && job == JOB_SEND) {
                    status.setText(L.s("Yazıya dökülüyor...", "Turning speech into text..."));
                }
            }
        }
    }

    public void commandAction(Command c, Displayable d) {
        midlet.userActive();
        if (c == helpCmd) {
            Help.show(midlet.display(), L.s("Sesle yaz", "Dictate"), L.s(
                    "En fazla 30 saniye konuş. Ses sunucuya gider ve yazıya dökülür; metin yazma kutusuna gelir, "
                    + "göndermeden önce düzeltebilirsin.\n\nKayıt sunucuda saklanmaz.",
                    "Speak for up to 30 seconds. The audio goes to the server and is turned into text; the text "
                    + "opens in the editor so you can fix it before sending.\n\nThe recording is not kept on the "
                    + "server."), form);
        } else if (c == doneCmd) {
            finish();
        } else if (c == retryCmd) {
            send(true);
        } else if (c == resendCmd) {
            send(false);
        } else if (c == againCmd) {
            record();
        } else if (c == cancelCmd) {
            close();
            midlet.dictationClosed(fromComposer);
        }
    }

    /** Leaves the screen: stops the microphone, drops the clip, ignores late answers. */
    private void close() {
        Rec r;
        synchronized (this) {
            closed = true;
            generation++;
            if (timer != null) {
                timer.cancel();
                timer = null;
            }
            r = rec;
            rec = null;
            audio = null;
        }
        if (r != null) {
            final Rec stop = r;
            new Thread(new Runnable() {
                public void run() {
                    stop.cancel();
                }
            }).start();
        }
    }
}

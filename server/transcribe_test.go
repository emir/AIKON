package main

import (
	"bytes"
	"context"
	"io"
	"math/rand"
	"mime"
	"mime/multipart"
	"net/http"
	"net/http/httptest"
	"os/exec"
	"strings"
	"sync"
	"testing"
	"time"
)

// wavClip: 8 kHz mono 16-bit PCM of the given length.
func wavClip(ms int) []byte {
	return pcmWAV(make([]byte, 8000*2*ms/1000), 8000, 1, 16)
}

// amrClip: AMR-NB 12.2 kbit/s frames with random payload (ffmpeg decodes any bits).
func amrClip(frames int, seed int64) []byte {
	r := rand.New(rand.NewSource(seed))
	b := []byte(amrMagic)
	for i := 0; i < frames; i++ {
		f := make([]byte, 32)
		r.Read(f)
		f[0] = 0x3c // frame type 7, quality bit set
		b = append(b, f...)
	}
	return b
}

func (e *tenv) transcribe(token, request, lang string, audio []byte) resp {
	e.t.Helper()
	q := "?request=" + request
	if lang != "" {
		q += "&lang=" + lang
	}
	return e.do("POST", "/v1/transcribe"+q, token, string(audio))
}

func withSTT(e *tenv, stt speechToText, limit int) {
	e.srv.transcriber = &transcribeService{st: e.srv.st, stt: stt, limit: limit}
}

func TestAudioParsing(t *testing.T) {
	if ms, err := amrDuration(amrClip(150, 1)); err != nil || ms != 3000 {
		t.Fatalf("amr: %d %v", ms, err)
	}
	// a cut last frame is ignored; no frame at all is an error
	if ms, err := amrDuration(amrClip(10, 1)[:len(amrMagic)+10*32-5]); err != nil || ms != 180 {
		t.Fatalf("cut amr: %d %v", ms, err)
	}
	if _, err := amrDuration([]byte(amrMagic)); err == nil {
		t.Fatal("empty amr accepted")
	}
	w, err := parseWAV(wavClip(500))
	if err != nil || w.format != 1 || w.rate != 8000 || w.bits != 16 || w.channels != 1 || len(w.data) != 8000 {
		t.Fatalf("wav: %+v %v", w, err)
	}
	// a recorder that never fixed the data size: the rest of the file counts
	broken := wavClip(500)
	copy(broken[40:44], []byte{0, 0, 0, 0})
	if w, err := parseWAV(broken); err != nil || len(w.data) != 8000 {
		t.Fatalf("broken wav: %d %v", len(w.data), err)
	}
	for _, bad := range [][]byte{nil, []byte("RIFF"), []byte("hello world, not audio"), []byte("RIFF\x00\x00\x00\x00WAVEdata\x00\x00\x00\x00")} {
		if _, err := decodeClip(context.Background(), nil, bad); err == nil {
			t.Fatalf("accepted %q", bad)
		}
	}
	// plain PCM WAV needs no converter; AMR does
	c, err := decodeClip(context.Background(), nil, wavClip(1500))
	if err != nil || c.ms != 1500 || c.format != "wav" || !bytes.HasPrefix(c.wav, []byte("RIFF")) {
		t.Fatalf("wav clip: %+v %v", c.ms, err)
	}
	if _, err := decodeClip(context.Background(), nil, amrClip(50, 1)); err != errNoConverter {
		t.Fatalf("amr without converter: %v", err)
	}
}

func ffmpegOrSkip(t *testing.T) audioConverter {
	p, err := exec.LookPath("ffmpeg")
	if err != nil {
		t.Skip("ffmpeg not installed")
	}
	return ffmpegConverter{p}
}

func TestAMRConversion(t *testing.T) {
	conv := ffmpegOrSkip(t)
	c, err := decodeClip(context.Background(), conv, amrClip(100, 2))
	if err != nil || c.format != "amr" || c.ms != 2000 {
		t.Fatalf("%+v %v", c.ms, err)
	}
	w, err := parseWAV(c.wav)
	if err != nil || w.rate != sttSampleRate || w.channels != 1 || w.bits != 16 || len(w.data) != 64000 {
		t.Fatalf("converted: %+v %v", w.rate, err)
	}
	// garbage after a valid header: ffmpeg fails or yields nothing -> bad audio, never a panic
	junk := append([]byte(amrMagic), bytes.Repeat([]byte{0xff}, 10)...)
	if _, err := decodeClip(context.Background(), conv, junk); err == nil {
		t.Log("ffmpeg accepted junk frames (decoded as silence)")
	}
}

func TestTranscribeFlow(t *testing.T) {
	e := newEnv(t, 10)
	tok, _ := e.pair("phone")
	id := rid()

	// off by default: nothing is read or called
	if r := e.transcribe(tok, id, "tr", wavClip(2000)); r.code != 503 || r.msg.get("status") != "unavailable" {
		t.Fatalf("off: %d %q", r.code, r.raw)
	}
	withSTT(e, mockSTT{}, 3)
	if r := e.transcribe("", id, "tr", wavClip(2000)); r.code != 401 {
		t.Fatalf("no token: %d", r.code)
	}
	for _, bad := range []string{"?request=x", "?request=" + id + "&lang=de", ""} {
		if r := e.do("POST", "/v1/transcribe"+bad, tok, string(wavClip(2000))); r.code != 400 {
			t.Fatalf("%q: %d", bad, r.code)
		}
	}
	if r := e.transcribe(tok, id, "tr", []byte("not audio at all")); r.msg.get("status") != "bad_audio" {
		t.Fatalf("bad audio: %q", r.raw)
	}
	if r := e.transcribe(tok, id, "tr", wavClip(36000)); r.msg.get("status") != "too_long" {
		t.Fatalf("too long: %q", r.raw)
	}
	if r := e.transcribe(tok, id, "tr", wavClip(100)); r.msg.get("status") != "too_short" {
		t.Fatalf("too short: %q", r.raw)
	}
	if r := e.transcribe(tok, id, "tr", make([]byte, maxAudioBytes+1)); r.code != 413 {
		t.Fatalf("too large: %d", r.code)
	}

	r := e.transcribe(tok, id, "tr", wavClip(2000))
	if r.code != 200 || r.msg.get("status") != "ok" || r.msg.get("mock") != "1" || r.msg.get("ms") != "2000" ||
		r.msg.get("remaining") != "2" || !strings.HasPrefix(r.msg.text, "[Test modu]") {
		t.Fatalf("ok: %d %q", r.code, r.raw)
	}
	// the same request again: the record, not a second call (remaining unchanged)
	r2 := e.transcribe(tok, id, "tr", wavClip(2000))
	if r2.msg.get("replayed") != "1" || r2.msg.text != r.msg.text || r2.msg.get("remaining") != "2" {
		t.Fatalf("replay: %q", r2.raw)
	}
	if r := e.transcribe(tok, id, "tr", wavClip(2500)); r.msg.get("status") != "request_mismatch" {
		t.Fatalf("mismatch: %q", r.raw)
	}
	if r := e.transcribe(tok, rid(), "en", wavClip(1500)); !strings.HasPrefix(r.msg.text, "[Test mode]") {
		t.Fatalf("english: %q", r.raw)
	}
	// the mock's "uncertain" clip: recorded, never retried
	uid := rid()
	if r := e.transcribe(tok, uid, "", wavClip(1000)); r.code != 504 || r.msg.get("status") != "uncertain" {
		t.Fatalf("uncertain: %d %q", r.code, r.raw)
	}
	if r := e.transcribe(tok, uid, "", wavClip(1000)); r.msg.get("status") != "uncertain" {
		t.Fatalf("uncertain replay: %q", r.raw)
	}
	// daily limit (3): ok, ok, uncertain used it up
	if r := e.transcribe(tok, rid(), "tr", wavClip(2000)); r.code != 429 || r.msg.get("status") != "limit" ||
		r.msg.get("remaining") != "0" {
		t.Fatalf("limit: %d %q", r.code, r.raw)
	}
	// transcripts are per device
	tok2, _ := e.pair("other")
	if r := e.transcribe(tok2, id, "tr", wavClip(2000)); r.msg.get("replayed") == "1" {
		t.Fatalf("other device saw the record: %q", r.raw)
	}
	var n, audio int
	e.srv.st.db.QueryRow(`SELECT SUM(transcripts), SUM(audio_ms) FROM usage`).Scan(&n, &audio)
	if n != 4 || audio != 2000+1500+1000+2000 {
		t.Fatalf("usage: %d %d", n, audio)
	}
}

func TestTranscribeAMRRequest(t *testing.T) {
	conv := ffmpegOrSkip(t)
	e := newEnv(t, 10)
	tok, _ := e.pair("phone")
	withSTT(e, mockSTT{}, 5)
	e.srv.converter = conv
	r := e.transcribe(tok, rid(), "en", amrClip(125, 3))
	if r.msg.get("status") != "ok" || r.msg.get("ms") != "2500" || !strings.Contains(r.msg.text, "2.5 seconds") {
		t.Fatalf("%q", r.raw)
	}
	// AMR without ffmpeg on the server: unavailable, nothing counted
	e.srv.converter = nil
	if r := e.transcribe(tok, rid(), "en", amrClip(125, 4)); r.code != 503 {
		t.Fatalf("no ffmpeg: %q", r.raw)
	}
}

// blockSTT waits until released, to test "busy" and "pending".
type blockSTT struct{ release chan struct{} }

func (b blockSTT) transcribe(ctx context.Context, wav []byte, lang string) (transcript, error) {
	<-b.release
	return transcript{text: "merhaba dünya"}, nil
}

func TestTranscribeOneAtATime(t *testing.T) {
	e := newEnv(t, 10)
	tok, _ := e.pair("phone")
	b := blockSTT{make(chan struct{})}
	withSTT(e, b, 10)
	id := rid()
	var wg sync.WaitGroup
	var first resp
	wg.Add(1)
	go func() { defer wg.Done(); first = e.transcribe(tok, id, "tr", wavClip(2000)) }()
	deadline := time.Now().Add(3 * time.Second)
	for {
		var x int
		if e.srv.st.db.QueryRow(`SELECT 1 FROM transcripts WHERE state='pending'`).Scan(&x) == nil {
			break
		}
		if time.Now().After(deadline) {
			t.Fatal("never pending")
		}
		time.Sleep(10 * time.Millisecond)
	}
	if r := e.transcribe(tok, rid(), "tr", wavClip(2000)); r.msg.get("status") != "busy" {
		t.Fatalf("busy: %q", r.raw)
	}
	if r := e.transcribe(tok, id, "tr", wavClip(2000)); r.code != 202 || r.msg.get("status") != "pending" {
		t.Fatalf("pending: %q", r.raw)
	}
	close(b.release)
	wg.Wait()
	if first.msg.get("status") != "ok" || first.msg.text != "merhaba dünya" || first.msg.get("mock") != "0" {
		t.Fatalf("first: %q", first.raw)
	}
}

// ------------------------------------------------------------ OpenAI client

type fakeSTT struct {
	mu     sync.Mutex
	calls  int
	status int
	body   string
	delay  time.Duration
	last   map[string]string
	file   []byte
}

func (f *fakeSTT) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	f.mu.Lock()
	f.calls++
	f.last = map[string]string{"path": r.URL.Path, "auth": r.Header.Get("Authorization")}
	_, params, _ := mime.ParseMediaType(r.Header.Get("Content-Type"))
	mr := multipart.NewReader(r.Body, params["boundary"])
	for {
		p, err := mr.NextPart()
		if err != nil {
			break
		}
		b, _ := io.ReadAll(p)
		if p.FormName() == "file" {
			f.file = b
			f.last["filename"] = p.FileName()
		} else {
			f.last[p.FormName()] = string(b)
		}
	}
	status, body, delay := f.status, f.body, f.delay
	f.mu.Unlock()
	time.Sleep(delay)
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("X-Request-Id", "req_test")
	w.WriteHeader(status)
	io.WriteString(w, body)
}

func (f *fakeSTT) count() int {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.calls
}

func fakeOpenAI(t *testing.T, f *fakeSTT) *openAISTT {
	ts := httptest.NewServer(f)
	t.Cleanup(ts.Close)
	o := newOpenAISTT("sk-test-key", "")
	o.baseURL = ts.URL
	o.client = &http.Client{Timeout: 500 * time.Millisecond}
	return o
}

func TestOpenAIRequestShape(t *testing.T) {
	f := &fakeSTT{status: 200, body: `{"text":"Yarın saat üçte\ntoplantı var 🙂"}`}
	o := fakeOpenAI(t, f)
	e := newEnv(t, 10)
	tok, _ := e.pair("phone")
	withSTT(e, o, 10)
	r := e.transcribe(tok, rid(), "tr", wavClip(2000))
	if r.msg.get("status") != "ok" || r.msg.get("mock") != "0" || r.msg.text != "Yarın saat üçte toplantı var" {
		t.Fatalf("%q", r.raw)
	}
	l := f.last
	if l["path"] != "/v1/audio/transcriptions" || l["auth"] != "Bearer sk-test-key" || l["model"] != defaultSTTModel ||
		l["language"] != "tr" || l["response_format"] != "json" || l["filename"] != "voice.wav" {
		t.Fatalf("request: %v", l)
	}
	if w, err := parseWAV(f.file); err != nil || w.rate != 8000 || len(w.data) != 32000 {
		t.Fatalf("file: %v", err)
	}
	// no language when the phone sends none
	e.transcribe(tok, rid(), "", wavClip(2000))
	if _, ok := f.last["language"]; ok {
		t.Fatalf("language sent: %v", f.last)
	}
}

func TestOpenAIErrorsNoRetry(t *testing.T) {
	cases := []struct {
		status int
		body   string
		want   string
	}{
		{401, `{"error":{"type":"invalid_request_error","code":"invalid_api_key","message":"bad key"}}`, "config_error"},
		{429, `{"error":{"type":"insufficient_quota","code":"insufficient_quota","message":"quota"}}`, "billing"},
		{429, `{"error":{"type":"requests","code":"rate_limit_exceeded","message":"slow down"}}`, "rate_limited"},
		{400, `{"error":{"type":"invalid_request_error","message":"Audio file might be corrupted"}}`, "bad_audio"},
		{500, `{"error":{"type":"server_error","message":"oops"}}`, "upstream_error"},
		{503, `not json`, "overloaded"},
		{200, `{"nope":1}`, "upstream_error"},
		{200, `{"text":"   "}`, "no_speech"},
	}
	for _, c := range cases {
		f := &fakeSTT{status: c.status, body: c.body}
		e := newEnv(t, 10)
		tok, _ := e.pair("phone")
		withSTT(e, fakeOpenAI(t, f), 10)
		id := rid()
		r := e.transcribe(tok, id, "en", wavClip(2000))
		if r.msg.get("status") != c.want || r.code != transcribeHTTP[c.want] {
			t.Fatalf("%d %s: got %d %q", c.status, c.body, r.code, r.raw)
		}
		// asking again reports the record; the service is called once
		if r := e.transcribe(tok, id, "en", wavClip(2000)); r.msg.get("status") != c.want || f.count() != 1 {
			t.Fatalf("replay %s: %q calls=%d", c.want, r.raw, f.count())
		}
	}
	// no answer in time: uncertain, never retried
	f := &fakeSTT{status: 200, body: `{"text":"late"}`, delay: time.Second}
	e := newEnv(t, 10)
	tok, _ := e.pair("phone")
	withSTT(e, fakeOpenAI(t, f), 10)
	id := rid()
	if r := e.transcribe(tok, id, "en", wavClip(2000)); r.msg.get("status") != "uncertain" {
		t.Fatalf("timeout: %q", r.raw)
	}
	if r := e.transcribe(tok, id, "en", wavClip(2000)); r.msg.get("status") != "uncertain" || f.count() != 1 {
		t.Fatalf("timeout replay: %q calls=%d", r.raw, f.count())
	}
}

// the phone hanging up must not lose a paid transcription
func TestTranscriptStoredAfterDisconnect(t *testing.T) {
	e := newEnv(t, 10)
	tok, dev := e.pair("phone")
	ctx, cancel := context.WithCancel(context.Background())
	withSTT(e, disconnectSTT{cancel}, 10)
	id := rid()
	clip, _ := decodeClip(ctx, nil, wavClip(2000))
	e.srv.transcriber.transcribe(ctx, dev, id, "", sha256Hex(string(wavClip(2000))), clip)
	withSTT(e, mockSTT{}, 10)
	if r := e.transcribe(tok, id, "", wavClip(2000)); r.msg.get("status") != "ok" || r.msg.get("replayed") != "1" ||
		r.msg.text != "kept" {
		t.Fatalf("%q", r.raw)
	}
}

// disconnectSTT cancels the phone's request while the paid call runs.
type disconnectSTT struct{ cancel context.CancelFunc }

func (d disconnectSTT) transcribe(ctx context.Context, wav []byte, lang string) (transcript, error) {
	d.cancel()
	if ctx.Err() != nil {
		return transcript{}, &upstreamError{"uncertain", "uncertain"}
	}
	return transcript{text: "kept"}, nil
}

func TestTranscriptRetention(t *testing.T) {
	e := newEnv(t, 10)
	tok, _ := e.pair("phone")
	withSTT(e, mockSTT{}, 10)
	e.transcribe(tok, rid(), "", wavClip(2000))
	st := e.srv.st
	st.now = func() time.Time { return time.Now().Add(transcriptTTL + time.Minute) }
	if err := st.cleanup(context.Background()); err != nil {
		t.Fatal(err)
	}
	var n int
	st.db.QueryRow(`SELECT COUNT(*) FROM transcripts`).Scan(&n)
	if n != 0 {
		t.Fatalf("%d transcripts left", n)
	}
}

func TestCleanTranscript(t *testing.T) {
	if got := cleanTranscript("  a\tb\r\nc  "); got != "a b c" {
		t.Fatalf("%q", got)
	}
	if got := cleanTranscript(strings.Repeat("ş", maxMessageChars+50)); len([]rune(got)) > maxMessageChars {
		t.Fatalf("too long: %d", len([]rune(got)))
	}
}

package main

// Voice messages (0.5.0): the phone records a short clip (AMR or WAV), the
// server turns it into text with a speech-to-text service and sends the
// text back. Nothing goes to Claude here: the phone puts the text into the
// message editor, the user checks or corrects it and sends it as a normal
// /v1/chat message.
//
// The transcription is a paid call, so it follows the chat rules:
//
//   - same request_id again -> the recorded result, never a second call
//   - one transcription in flight per device, a daily limit per device
//   - recorded as "pending" and counted BEFORE the call, no retries
//   - no HTTP answer from the service -> "uncertain", never retried
//
// The audio is only held in memory for the request; it is never written to
// disk or the database. Transcripts are kept for a day, for replays only.
// Logs carry the format, byte count and length, never audio or text.

import (
	"bytes"
	"context"
	"database/sql"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"mime/multipart"
	"net"
	"net/http"
	"os/exec"
	"strings"
	"sync"
	"time"

	"golang.org/x/text/unicode/norm"
)

const (
	maxAudioBytes   = 640 << 10 // 35 s of 8 kHz 16-bit WAV fits; AMR needs ~1.6 KB/s
	maxAudioMs      = 35000     // the phone stops at 30 s
	minAudioMs      = 300
	transcriptTTL   = 24 * time.Hour
	sttTimeout      = 60 * time.Second
	convertTimeout  = 15 * time.Second
	sttSampleRate   = 16000 // what ffmpeg makes of any input: mono s16le
	amrFrameMs      = 20
	defaultSTTModel = "gpt-4o-mini-transcribe"
)

// ------------------------------------------------------------ audio

type audioClip struct {
	format string // "amr" | "wav" (what the phone sent)
	wav    []byte // what the service gets: always a clean PCM WAV
	ms     int
}

var errBadAudio = errors.New("bad audio")

// amrFrameBytes: AMR-NB storage format (RFC 4867 section 5), frame size
// including the header byte, by frame type.
var amrFrameBytes = [16]int{13, 14, 16, 18, 20, 21, 27, 32, 6, 1, 1, 1, 1, 1, 1, 1}

const amrMagic = "#!AMR\n"

// amrDuration validates the frame sequence and returns its length in ms.
func amrDuration(b []byte) (int, error) {
	if !bytes.HasPrefix(b, []byte(amrMagic)) {
		return 0, errBadAudio
	}
	frames := 0
	for i := len(amrMagic); i < len(b); {
		ft := (b[i] >> 3) & 0x0f
		n := amrFrameBytes[ft]
		if i+n > len(b) {
			break // a last frame cut by the recorder: ignored
		}
		i += n
		frames++
	}
	if frames == 0 {
		return 0, errBadAudio
	}
	return frames * amrFrameMs, nil
}

type wavInfo struct {
	format, channels, bits int
	rate                   int
	data                   []byte
}

// parseWAV reads a RIFF/WAVE file. A data chunk whose size is 0 or runs past
// the end (recorders that never go back to fix the header) is taken as the
// rest of the file.
func parseWAV(b []byte) (wavInfo, error) {
	var w wavInfo
	if len(b) < 12 || string(b[0:4]) != "RIFF" || string(b[8:12]) != "WAVE" {
		return w, errBadAudio
	}
	fmtSeen := false
	for i := 12; i+8 <= len(b); {
		id := string(b[i : i+4])
		size := int(binary.LittleEndian.Uint32(b[i+4 : i+8]))
		body := i + 8
		switch id {
		case "fmt ":
			if size < 16 || body+16 > len(b) {
				return w, errBadAudio
			}
			f := b[body:]
			w.format = int(binary.LittleEndian.Uint16(f[0:2]))
			w.channels = int(binary.LittleEndian.Uint16(f[2:4]))
			w.rate = int(binary.LittleEndian.Uint32(f[4:8]))
			w.bits = int(binary.LittleEndian.Uint16(f[14:16]))
			fmtSeen = true
		case "data":
			if !fmtSeen {
				return w, errBadAudio
			}
			end := body + size
			if size == 0 || size < 0 || end > len(b) {
				end = len(b)
			}
			w.data = b[body:end]
			return w, nil
		}
		if size < 0 || body+size+size%2 > len(b) {
			break
		}
		i = body + size + size%2
	}
	return w, errBadAudio
}

// pcmWAV wraps little-endian PCM samples in a minimal WAV header.
func pcmWAV(pcm []byte, rate, channels, bits int) []byte {
	var h bytes.Buffer
	h.Grow(44 + len(pcm))
	blockAlign := channels * bits / 8
	le := func(v any) { binary.Write(&h, binary.LittleEndian, v) }
	h.WriteString("RIFF")
	le(uint32(36 + len(pcm)))
	h.WriteString("WAVEfmt ")
	le(uint32(16))
	le(uint16(1))
	le(uint16(channels))
	le(uint32(rate))
	le(uint32(rate * blockAlign))
	le(uint16(blockAlign))
	le(uint16(bits))
	h.WriteString("data")
	le(uint32(len(pcm)))
	h.Write(pcm)
	return h.Bytes()
}

// audioConverter turns the phone's clip into mono 16 kHz s16le PCM.
type audioConverter interface {
	toPCM(ctx context.Context, format string, in []byte) ([]byte, error)
}

// ffmpegConverter runs ffmpeg on pipes only: no temporary files (the
// container's root file system is read-only), no network, one thread.
type ffmpegConverter struct{ path string }

func (f ffmpegConverter) toPCM(ctx context.Context, format string, in []byte) ([]byte, error) {
	ctx, cancel := context.WithTimeout(ctx, convertTimeout)
	defer cancel()
	cmd := exec.CommandContext(ctx, f.path, "-hide_banner", "-loglevel", "error", "-threads", "1",
		"-f", format, "-i", "pipe:0", "-t", fmt.Sprint(maxAudioMs/1000+1),
		"-ac", "1", "-ar", fmt.Sprint(sttSampleRate), "-f", "s16le", "-acodec", "pcm_s16le", "pipe:1")
	cmd.Stdin = bytes.NewReader(in)
	var out bytes.Buffer
	cmd.Stdout = &out
	if err := cmd.Run(); err != nil {
		return nil, err
	}
	return out.Bytes(), nil
}

// decodeClip checks the clip and makes the WAV for the service. AMR (and
// any WAV that is not plain PCM) needs the converter; plain PCM WAV only
// gets a clean header.
func decodeClip(ctx context.Context, conv audioConverter, b []byte) (audioClip, error) {
	switch {
	case bytes.HasPrefix(b, []byte(amrMagic)):
		ms, err := amrDuration(b)
		if err != nil {
			return audioClip{}, err
		}
		if ms > maxAudioMs {
			return audioClip{format: "amr", ms: ms}, nil // the caller reports too_long
		}
		if conv == nil {
			return audioClip{}, errNoConverter
		}
		pcm, err := conv.toPCM(ctx, "amr", b)
		if err != nil || len(pcm) == 0 {
			return audioClip{}, errBadAudio
		}
		return audioClip{format: "amr", wav: pcmWAV(pcm, sttSampleRate, 1, 16), ms: len(pcm) * 1000 / (2 * sttSampleRate)}, nil
	case bytes.HasPrefix(b, []byte("RIFF")):
		w, err := parseWAV(b)
		if err != nil {
			return audioClip{}, err
		}
		if w.format == 1 && (w.bits == 8 || w.bits == 16) && w.channels >= 1 && w.channels <= 2 &&
			w.rate >= 8000 && w.rate <= 48000 {
			frame := w.channels * w.bits / 8
			pcm := w.data[:len(w.data)/frame*frame]
			return audioClip{format: "wav", wav: pcmWAV(pcm, w.rate, w.channels, w.bits),
				ms: int(int64(len(pcm)) * 1000 / int64(w.rate*frame))}, nil
		}
		if conv == nil {
			return audioClip{}, errNoConverter
		}
		pcm, err := conv.toPCM(ctx, "wav", b)
		if err != nil || len(pcm) == 0 {
			return audioClip{}, errBadAudio
		}
		return audioClip{format: "wav", wav: pcmWAV(pcm, sttSampleRate, 1, 16), ms: len(pcm) * 1000 / (2 * sttSampleRate)}, nil
	}
	return audioClip{}, errBadAudio
}

var errNoConverter = errors.New("no audio converter")

// ------------------------------------------------------------ speech to text

type transcript struct {
	text string
	mock bool
}

type speechToText interface {
	transcribe(ctx context.Context, wav []byte, lang string) (transcript, error)
}

// openAISTT: OpenAI's /v1/audio/transcriptions. No retries: a repeated
// request after a timeout could be a second paid call.
type openAISTT struct {
	key, model, baseURL string
	client              *http.Client
}

func newOpenAISTT(key, model string) *openAISTT {
	if model == "" {
		model = defaultSTTModel
	}
	return &openAISTT{key: key, model: model, baseURL: "https://api.openai.com",
		client: &http.Client{Timeout: sttTimeout}}
}

func (o *openAISTT) transcribe(ctx context.Context, wav []byte, lang string) (transcript, error) {
	var body bytes.Buffer
	mw := multipart.NewWriter(&body)
	mw.WriteField("model", o.model)
	mw.WriteField("response_format", "json")
	if lang != "" {
		mw.WriteField("language", lang)
	}
	fw, _ := mw.CreateFormFile("file", "voice.wav")
	fw.Write(wav)
	mw.Close()
	req, err := http.NewRequestWithContext(ctx, "POST", o.baseURL+"/v1/audio/transcriptions", &body)
	if err != nil {
		return transcript{}, &upstreamError{"definite", "config_error"}
	}
	req.Header.Set("Authorization", "Bearer "+o.key)
	req.Header.Set("Content-Type", mw.FormDataContentType())
	res, err := o.client.Do(req)
	if err != nil {
		kind := "transport"
		var ne net.Error
		if errors.As(err, &ne) && ne.Timeout() || errors.Is(err, context.DeadlineExceeded) {
			kind = "timeout"
		}
		logJSON(map[string]any{"evt": "stt_error", "kind": kind})
		return transcript{}, &upstreamError{"uncertain", "uncertain"}
	}
	defer res.Body.Close()
	raw, err := io.ReadAll(io.LimitReader(res.Body, 1<<20))
	if err != nil {
		// answered, but the body was lost: the call was made and may be billed
		logJSON(map[string]any{"evt": "stt_error", "kind": "read"})
		return transcript{}, &upstreamError{"uncertain", "uncertain"}
	}
	if res.StatusCode != 200 {
		return transcript{}, classifySTT(res.StatusCode, raw, res.Header.Get("X-Request-Id"))
	}
	var ok struct {
		Text *string `json:"text"`
	}
	if json.Unmarshal(raw, &ok) != nil || ok.Text == nil {
		logJSON(map[string]any{"evt": "stt_error", "kind": "bad_json"})
		return transcript{}, &upstreamError{"definite", "upstream_error"}
	}
	return transcript{text: *ok.Text}, nil
}

// classifySTT maps an error answer to a phone status and logs its class
// (status, type, code, message, request id: never audio, text or keys).
func classifySTT(status int, raw []byte, requestID string) *upstreamError {
	var body struct {
		Error struct {
			Type    string `json:"type"`
			Code    any    `json:"code"`
			Message string `json:"message"`
		} `json:"error"`
	}
	json.Unmarshal(raw, &body)
	code := fmt.Sprint(body.Error.Code)
	logJSON(map[string]any{"evt": "stt_error", "status": status, "type": body.Error.Type, "code": code,
		"message": truncate(body.Error.Message, 300), "request_id": requestID})
	switch {
	case code == "insufficient_quota" || body.Error.Type == "insufficient_quota" || status == 402:
		return &upstreamError{"definite", "billing"}
	case status == 429:
		return &upstreamError{"definite", "rate_limited"}
	case status == 401 || status == 403 || status == 404:
		return &upstreamError{"definite", "config_error"}
	case status == 400 || status == 413 || status == 415:
		return &upstreamError{"definite", "bad_audio"}
	case status == 503:
		return &upstreamError{"definite", "overloaded"}
	default:
		return &upstreamError{"definite", "upstream_error"}
	}
}

// mockSTT never touches the network. Its text starts with "[Test mode]" so
// it can never be mistaken for a real transcription. Test hook: a clip of
// exactly 1000 ms (50 AMR frames) fails as "uncertain".
type mockSTT struct{}

func (mockSTT) transcribe(ctx context.Context, wav []byte, lang string) (transcript, error) {
	w, err := parseWAV(wav)
	ms := 0
	if err == nil && w.rate > 0 {
		ms = len(w.data) * 1000 / (w.rate * max(1, w.channels*w.bits/8))
	}
	if ms == 1000 {
		return transcript{}, &upstreamError{"uncertain", "uncertain"}
	}
	if lang == "tr" {
		return transcript{text: fmt.Sprintf("[Test modu] Bu gerçek bir yazıya dökme değil. Ses kaydı %.1f saniye.", float64(ms)/1000), mock: true}, nil
	}
	return transcript{text: fmt.Sprintf("[Test mode] This is not a real transcription. The recording is %.1f seconds long.", float64(ms)/1000), mock: true}, nil
}

// ------------------------------------------------------------ service

var transcribeHTTP = map[string]int{
	"ok": 200, "pending": 202, "busy": 409, "limit": 429, "request_mismatch": 409, "no_speech": 422,
	"bad_audio": 400, "rate_limited": 503, "overloaded": 503, "upstream_error": 502, "config_error": 500,
	"billing": 402, "uncertain": 504,
}

type transcribeResult struct {
	status    string
	text      string
	mock      bool
	replayed  bool
	remaining int
	has       bool // remaining is set
}

type transcribeService struct {
	st          *store
	stt         speechToText
	limit       int // transcriptions per device per UTC day
	deviceLocks sync.Map
}

func (t *transcribeService) lock(device string) *sync.Mutex {
	m, _ := t.deviceLocks.LoadOrStore(device, &sync.Mutex{})
	return m.(*sync.Mutex)
}

func (t *transcribeService) left(ctx context.Context, device string) int {
	var n int
	t.st.db.QueryRowContext(ctx, `SELECT transcripts FROM usage WHERE device_id=? AND day=?`,
		device, utcDay(t.st.ms())).Scan(&n)
	return max(0, t.limit-n)
}

func (t *transcribeService) transcribe(ctx context.Context, device, requestID, lang string, audioSHA string, clip audioClip) (transcribeResult, error) {
	mu := t.lock(device)
	mu.Lock()
	db := t.st.db
	now := t.st.ms()

	// 1. repeated request_id: the record, never a second call
	var prevSha, prevState string
	var prevCreated int64
	var prevText, prevErr sql.NullString
	var prevMock int
	err := db.QueryRowContext(ctx, `SELECT audio_sha, state, created_at, text, error, mock FROM transcripts
		WHERE device_id=? AND request_id=?`, device, requestID).
		Scan(&prevSha, &prevState, &prevCreated, &prevText, &prevErr, &prevMock)
	if err == nil {
		defer mu.Unlock()
		if prevSha != audioSHA {
			return transcribeResult{status: "request_mismatch"}, nil
		}
		switch prevState {
		case "pending":
			if now-prevCreated > pendingStale.Milliseconds() {
				db.ExecContext(ctx, `UPDATE transcripts SET state='uncertain', error='uncertain' WHERE device_id=? AND request_id=?`,
					device, requestID)
				return transcribeResult{status: "uncertain"}, nil
			}
			return transcribeResult{status: "pending"}, nil
		case "done":
			return transcribeResult{status: "ok", text: prevText.String, mock: prevMock == 1, replayed: true,
				remaining: t.left(ctx, device), has: true}, nil
		case "uncertain":
			return transcribeResult{status: "uncertain"}, nil
		default:
			return transcribeResult{status: prevErr.String}, nil
		}
	}
	if !errors.Is(err, sql.ErrNoRows) {
		mu.Unlock()
		return transcribeResult{}, err
	}

	// 2. one at a time per device
	db.ExecContext(ctx, `UPDATE transcripts SET state='uncertain', error='uncertain'
		WHERE device_id=? AND state='pending' AND created_at < ?`, device, now-pendingStale.Milliseconds())
	var busy int
	if db.QueryRowContext(ctx, `SELECT 1 FROM transcripts WHERE device_id=? AND state='pending' LIMIT 1`, device).Scan(&busy) == nil {
		mu.Unlock()
		return transcribeResult{status: "busy"}, nil
	}

	// 3. daily limit
	left := t.left(ctx, device)
	if left <= 0 {
		mu.Unlock()
		return transcribeResult{status: "limit", has: true}, nil
	}

	// 4. record + count BEFORE the paid call
	tx, err := db.BeginTx(ctx, nil)
	if err != nil {
		mu.Unlock()
		return transcribeResult{}, err
	}
	_, e1 := tx.ExecContext(ctx, `INSERT INTO transcripts (device_id, request_id, audio_sha, state, created_at, audio_ms)
		VALUES (?, ?, ?, 'pending', ?, ?)`, device, requestID, audioSHA, now, clip.ms)
	_, e2 := tx.ExecContext(ctx, `INSERT INTO usage (device_id, day, transcripts, audio_ms) VALUES (?, ?, 1, ?)
		ON CONFLICT(device_id, day) DO UPDATE SET transcripts = transcripts + 1, audio_ms = audio_ms + ?`,
		device, utcDay(now), clip.ms, clip.ms)
	if e1 != nil || e2 != nil {
		tx.Rollback()
		mu.Unlock()
		return transcribeResult{}, errors.Join(e1, e2)
	}
	if err := tx.Commit(); err != nil {
		mu.Unlock()
		return transcribeResult{}, err
	}
	mu.Unlock()

	// 5. the call; a phone disconnect must not turn it into "unknown"
	ctx = context.WithoutCancel(ctx)
	tr, callErr := t.stt.transcribe(ctx, clip.wav, lang)

	mu.Lock()
	defer mu.Unlock()
	done := t.st.ms()
	if callErr != nil {
		ue, ok := callErr.(*upstreamError)
		if !ok {
			ue = &upstreamError{"uncertain", "uncertain"}
		}
		state := "failed"
		if ue.kind == "uncertain" {
			state = "uncertain"
		}
		db.ExecContext(ctx, `UPDATE transcripts SET state=?, error=?, finished_at=? WHERE device_id=? AND request_id=?`,
			state, ue.code, done, device, requestID)
		return transcribeResult{status: ue.code, remaining: left - 1, has: true}, nil
	}
	text := cleanTranscript(tr.text)
	status := "ok"
	if text == "" {
		status = "no_speech"
		db.ExecContext(ctx, `UPDATE transcripts SET state='failed', error='no_speech', finished_at=? WHERE device_id=? AND request_id=?`,
			done, device, requestID)
	} else {
		db.ExecContext(ctx, `UPDATE transcripts SET state='done', text=?, mock=?, finished_at=? WHERE device_id=? AND request_id=?`,
			text, b2i(tr.mock), done, device, requestID)
	}
	return transcribeResult{status: status, text: text, mock: tr.mock, remaining: t.left(ctx, device), has: true}, nil
}

// cleanTranscript: NFC, one paragraph (no control characters), no emoji or
// other characters the phone cannot show, at most one message long.
func cleanTranscript(s string) string {
	s = strings.Map(func(r rune) rune {
		if r < 0x20 || r == 0x7f || r >= 0x80 && r < 0xa0 {
			return ' '
		}
		return r
	}, norm.NFC.String(s))
	s = sanitizeReply(strings.Join(strings.Fields(s), " "))
	s, _ = limitChars(strings.TrimSpace(s), maxMessageChars-4) // room for " ..."
	return s
}

// ------------------------------------------------------------ handler

// transcribeHandler: POST /v1/transcribe?request=ID&lang=tr|en, body = the
// audio (AMR or WAV, recognised by its first bytes). The answer is an S40/1
// message whose text is the transcript.
func (s *server) transcribeHandler(w http.ResponseWriter, r *http.Request) {
	device, ok := s.authDevice(w, r)
	if !ok {
		return
	}
	q := r.URL.Query()
	reqID, lang := q.Get("request"), q.Get("lang")
	if !idRE.MatchString(reqID) || lang != "" && lang != "tr" && lang != "en" {
		writeS40(w, 400, []kv{{"status", "bad_request"}}, "")
		return
	}
	if s.transcriber == nil {
		writeS40(w, 503, []kv{{"status", "unavailable"}, {"request", reqID}}, "")
		return
	}
	b, err := readLimited(r, maxAudioBytes)
	if err != nil {
		writeBodyErr(w, r, err, maxAudioBytes, kv{"request", reqID})
		return
	}
	clip, err := decodeClip(r.Context(), s.converter, b)
	logJSON(map[string]any{"evt": "audio", "format": clip.format, "bytes": len(b), "ms": clip.ms, "ok": err == nil})
	switch {
	case errors.Is(err, errNoConverter):
		writeS40(w, 503, []kv{{"status", "unavailable"}, {"request", reqID}}, "")
		return
	case err != nil:
		writeS40(w, 400, []kv{{"status", "bad_audio"}, {"request", reqID}}, "")
		return
	case clip.ms > maxAudioMs:
		writeS40(w, 413, []kv{{"status", "too_long"}, {"request", reqID}, {"max-ms", maxAudioMs}}, "")
		return
	case clip.ms < minAudioMs:
		writeS40(w, 400, []kv{{"status", "too_short"}, {"request", reqID}}, "")
		return
	}
	res, err := s.transcriber.transcribe(r.Context(), device, reqID, lang, sha256Hex(string(b)), clip)
	if err != nil {
		logJSON(map[string]any{"evt": "transcribe_error"})
		writeS40(w, 500, []kv{{"status", "server_error"}, {"request", reqID}}, "")
		return
	}
	f := []kv{{"status", res.status}, {"request", reqID}}
	if res.status == "ok" {
		f = append(f, kv{"mock", res.mock}, kv{"ms", clip.ms})
		if res.replayed {
			f = append(f, kv{"replayed", true})
		}
	}
	if res.has {
		f = append(f, kv{"remaining", res.remaining})
	}
	code := transcribeHTTP[res.status]
	if code == 0 {
		code = 500
	}
	writeS40(w, code, f, res.text)
}

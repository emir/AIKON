package main

import (
	"bytes"
	"context"
	"encoding/binary"
	"errors"
	"hash/crc32"
	"image"
	"image/color"
	"image/jpeg"
	"image/png"
	"log"
	"net/http/httptest"
	"os"
	"strings"
	"testing"
	"time"
)

// photo: a JPEG of the given size with a gradient (so sizes differ per seed).
func photo(w, h, seed int) []byte {
	img := image.NewRGBA(image.Rect(0, 0, w, h))
	for y := 0; y < h; y++ {
		for x := 0; x < w; x++ {
			img.Set(x, y, color.RGBA{uint8(x + seed), uint8(y * seed), uint8(seed * 40), 255})
		}
	}
	var b bytes.Buffer
	jpeg.Encode(&b, img, &jpeg.Options{Quality: 90})
	return b.Bytes()
}

func pngPhoto(w, h int) []byte {
	var b bytes.Buffer
	png.Encode(&b, image.NewGray(image.Rect(0, 0, w, h)))
	return b.Bytes()
}

// pngBomb: a valid PNG header claiming a huge image, with no pixel data.
func pngBomb(w, h uint32) []byte {
	var b bytes.Buffer
	b.WriteString("\x89PNG\r\n\x1a\n")
	ihdr := make([]byte, 17)
	copy(ihdr, "IHDR")
	binary.BigEndian.PutUint32(ihdr[4:], w)
	binary.BigEndian.PutUint32(ihdr[8:], h)
	ihdr[12], ihdr[13] = 8, 2 // 8-bit RGB
	binary.Write(&b, binary.BigEndian, uint32(13))
	b.Write(ihdr)
	binary.Write(&b, binary.BigEndian, crc32.ChecksumIEEE(ihdr))
	return b.Bytes()
}

func (e *tenv) upload(token string, b []byte) resp {
	e.t.Helper()
	return e.do("POST", "/v1/image", token, string(b))
}

func (e *tenv) chatImage(token, request, conv, image, text string) resp {
	return e.do("POST", "/v1/chat", token, formatS40([]kv{{"request", request}, {"conversation", conv}, {"image", image}}, text))
}

func TestProcessImage(t *testing.T) {
	img, err := processImage(photo(1600, 1200, 1))
	if err != nil || img.width != 1024 || img.height != 768 {
		t.Fatalf("%d x %d %v", img.width, img.height, err)
	}
	if c, f, err := image.DecodeConfig(bytes.NewReader(img.data)); err != nil || f != "jpeg" || c.Width != 1024 {
		t.Fatalf("stored: %v %s %v", c, f, err)
	}
	// portrait, and a small PNG that is only re-encoded
	if img, _ := processImage(photo(480, 640, 2)); img.width != 480 || img.height != 640 {
		t.Fatalf("portrait %d x %d", img.width, img.height)
	}
	if img, _ := processImage(photo(1200, 1600, 2)); img.width != 768 || img.height != 1024 {
		t.Fatalf("tall %d x %d", img.width, img.height)
	}
	if img, err := processImage(pngPhoto(160, 120)); err != nil || img.width != 160 {
		t.Fatalf("png %v", err)
	}
	for name, b := range map[string][]byte{"text": []byte("hello"), "cut jpeg": photo(64, 64, 1)[:200],
		"huge header": pngBomb(30000, 30000), "gif": []byte("GIF89a\x01\x00\x01\x00")} {
		if _, err := processImage(b); err == nil {
			t.Fatalf("%s accepted", name)
		}
	}
}

func TestImageUpload(t *testing.T) {
	e := newEnv(t, 10)
	e.srv.cfg.imageLimit = 2
	tok, _ := e.pair("phone")
	if r := e.upload("", photo(64, 64, 1)); r.code != 401 {
		t.Fatalf("no token: %d", r.code)
	}
	r := e.upload(tok, photo(1600, 1200, 1))
	id := r.msg.get("image")
	if r.code != 200 || !imageIDRE.MatchString(id) || r.msg.get("width") != "1024" || r.msg.get("height") != "768" {
		t.Fatalf("%d %q", r.code, r.raw)
	}
	// the same photo again: the same id, not counted twice
	if r := e.upload(tok, photo(1600, 1200, 1)); r.msg.get("image") != id {
		t.Fatalf("dedupe: %q", r.raw)
	}
	if r := e.upload(tok, []byte("not a photo")); r.code != 400 || r.msg.get("status") != "bad_image" {
		t.Fatalf("bad: %q", r.raw)
	}
	if r := e.upload(tok, make([]byte, maxImageUpload+1)); r.code != 413 {
		t.Fatalf("large: %d", r.code)
	}
	e.upload(tok, photo(64, 64, 2))
	if r := e.upload(tok, photo(64, 64, 3)); r.code != 429 || r.msg.get("status") != "limit" {
		t.Fatalf("limit: %q", r.raw)
	}
	// another device neither sees nor shares the photo
	tok2, _ := e.pair("other")
	if r := e.chatImage(tok2, rid(), "", id, "ne var?"); r.msg.get("status") != "image_not_found" {
		t.Fatalf("other device: %q", r.raw)
	}
}

func TestChatWithImage(t *testing.T) {
	e := newEnv(t, 10)
	e.srv.cfg.imageLimit = 10
	tok, _ := e.pair("phone")
	id := e.upload(tok, photo(640, 480, 1)).msg.get("image")

	req := rid()
	r := e.chatImage(tok, req, "", id, "Bu ne?")
	conv := r.msg.get("conversation")
	if r.msg.get("status") != "ok" || !strings.Contains(r.msg.text, "Photo: attached") {
		t.Fatalf("%q", r.raw)
	}
	// the same request with another photo is a mismatch; with the same one, a replay
	other := e.upload(tok, photo(640, 480, 2)).msg.get("image")
	if r := e.chatImage(tok, req, "", other, "Bu ne?"); r.msg.get("status") != "request_mismatch" {
		t.Fatalf("mismatch: %q", r.raw)
	}
	if r := e.chatImage(tok, req, "", id, "Bu ne?"); r.msg.get("replayed") != "1" {
		t.Fatalf("replay: %q", r.raw)
	}
	// a follow-up question sees the photo again
	r = e.chat(tok, rid(), conv, "Rengi ne?")
	if !strings.Contains(r.msg.text, "Earlier photos shown again: 1") {
		t.Fatalf("follow-up: %q", r.raw)
	}
	// the photo belongs to this conversation now: not usable in another one
	if r := e.chatImage(tok, rid(), "", id, "tekrar"); r.code != 404 || r.msg.get("status") != "image_not_found" {
		t.Fatalf("reuse: %q", r.raw)
	}
	if r := e.chatImage(tok, rid(), conv, id, "yine bu"); r.msg.get("status") != "ok" {
		t.Fatalf("same conversation: %q", r.raw)
	}
	if r := e.chatImage(tok, rid(), "", "0123456789abcdef0123456789abcdef", "yok"); r.msg.get("status") != "image_not_found" {
		t.Fatalf("unknown: %q", r.raw)
	}
	if r := e.chatImage(tok, rid(), "", "nope", "x"); r.code != 400 {
		t.Fatalf("bad id: %d", r.code)
	}

	// history: 0.9+ phones see which message had a photo, older ones do not
	h := e.do("POST", "/v1/history", tok, formatS40([]kv{{"conversation", conv}, {"images", "1"}}, ""))
	if !strings.HasPrefix(h.msg.text, "u 6 i\nBu ne?\n") {
		t.Fatalf("history: %q", h.msg.text)
	}
	h = e.do("POST", "/v1/history", tok, formatS40([]kv{{"conversation", conv}}, ""))
	if !strings.HasPrefix(h.msg.text, "u 6\nBu ne?\n") {
		t.Fatalf("old history: %q", h.msg.text)
	}

	// deleting the conversation deletes its photos
	if r := e.do("POST", "/v1/delete", tok, formatS40([]kv{{"conversation", conv}}, "")); r.msg.get("status") != "deleted" {
		t.Fatalf("delete: %q", r.raw)
	}
	var n int
	e.srv.st.db.QueryRow(`SELECT COUNT(*) FROM images WHERE conversation_id != ''`).Scan(&n)
	if n != 0 {
		t.Fatalf("%d photos left", n)
	}
}

func TestImageRetention(t *testing.T) {
	e := newEnv(t, 10)
	e.srv.cfg.imageLimit = 10
	tok, _ := e.pair("phone")
	e.upload(tok, photo(64, 64, 1))                          // never used
	used := e.upload(tok, photo(64, 64, 2)).msg.get("image") // used in a chat
	r := e.chatImage(tok, rid(), "", used, "ne?")
	st := e.srv.st
	st.now = func() time.Time { return time.Now().Add(25 * time.Hour) }
	st.cleanup(context.Background())
	var unused, kept int
	st.db.QueryRow(`SELECT COUNT(*) FROM images WHERE conversation_id=''`).Scan(&unused)
	st.db.QueryRow(`SELECT COUNT(*) FROM images WHERE conversation_id=?`, r.msg.get("conversation")).Scan(&kept)
	if unused != 0 || kept != 1 {
		t.Fatalf("unused %d kept %d", unused, kept)
	}
	// the conversation expires: its photo goes with it
	st.now = func() time.Time { return time.Now().Add(conversationTTL + time.Hour) }
	st.cleanup(context.Background())
	st.db.QueryRow(`SELECT COUNT(*) FROM images`).Scan(&kept)
	if kept != 0 {
		t.Fatalf("%d photos after expiry", kept)
	}
}

// the real SDK path: photo block before the text, older photos limited
func TestClaudeImageRequest(t *testing.T) {
	f := &fakeAPI{status: 200, body: okBody("Bir kedi.", "end_turn")}
	e := newEnv(t, 20)
	e.srv.cfg.imageLimit = 10
	e.srv.chat.model = fakeModel(t, f, false, "")
	tok, _ := e.pair("phone")
	conv := ""
	for i := 1; i <= 4; i++ {
		id := e.upload(tok, photo(320, 240, i)).msg.get("image")
		r := e.chatImage(tok, rid(), conv, id, "foto "+string(rune('0'+i)))
		if r.msg.get("status") != "ok" {
			t.Fatalf("%d: %q", i, r.raw)
		}
		conv = r.msg.get("conversation")
	}
	msgs := f.bodies[len(f.bodies)-1]["messages"].([]any)
	images := 0
	for _, m := range msgs {
		for _, c := range m.(map[string]any)["content"].([]any) {
			if c.(map[string]any)["type"] == "image" {
				images++
			}
		}
	}
	if images != contextImages {
		t.Fatalf("%d photos sent, want %d", images, contextImages)
	}
	last := msgs[len(msgs)-1].(map[string]any)["content"].([]any)
	src := last[0].(map[string]any)["source"].(map[string]any)
	if last[0].(map[string]any)["type"] != "image" || src["media_type"] != "image/jpeg" || src["type"] != "base64" ||
		last[1].(map[string]any)["text"] != "foto 4" {
		t.Fatalf("last message: %v", last)
	}
	first := msgs[0].(map[string]any)["content"].([]any)
	if !strings.HasPrefix(first[0].(map[string]any)["text"].(string), "[an earlier photo, no longer shown]") {
		t.Fatalf("oldest photo: %v", first)
	}
}

func TestSmallJPEGKept(t *testing.T) {
	b := photo(480, 640, 3)
	img, err := processImage(b)
	if err != nil || !bytes.Equal(img.data, b) || img.width != 480 || img.height != 640 {
		t.Fatalf("small jpeg re-encoded: %d -> %d bytes, %v", len(b), len(img.data), err)
	}
}

func TestHandshakeLogHasNoAddresses(t *testing.T) {
	var buf bytes.Buffer
	log.SetOutput(&buf)
	defer log.SetOutput(os.Stderr)
	for _, line := range []string{
		"http: TLS handshake error from 203.0.113.9:51234: EOF",
		"http: TLS handshake error: read tcp 172.18.0.2:8443->198.51.100.47:48070: read: connection reset by peer",
		"http: TLS handshake error from [2001:db8::1]:443: EOF",
		"http: TLS handshake error: read tcp [::1]:8443->[2001:db8::7%eth0]:5000: i/o timeout",
	} {
		handshakeLog{}.Write([]byte(line))
	}
	out := buf.String()
	for _, ip := range []string{"203.0.113", "198.51.100", "172.18.0", "2001:db8", "::1"} {
		if strings.Contains(out, ip) {
			t.Fatalf("address %s logged:\n%s", ip, out)
		}
	}
	if !strings.Contains(out, "connection reset by peer") || !strings.Contains(out, "[addr]") {
		t.Fatalf("detail lost:\n%s", out)
	}
}

// dropReader returns part of a body, then fails as a dropped connection does.
type dropReader struct{ sent bool }

func (d *dropReader) Read(p []byte) (int, error) {
	if d.sent {
		return 0, errors.New("read tcp 10.0.0.1:443->10.0.0.2:5555: i/o timeout")
	}
	d.sent = true
	return copy(p, photo(64, 64, 1)[:100]), nil
}

// An upload that drops midway is bad_request, not too_large, and the log
// does not carry the error text (it holds addresses).
func TestImageUploadDropped(t *testing.T) {
	e := newEnv(t, 10)
	tok, _ := e.pair("phone")
	var logs bytes.Buffer
	log.SetOutput(&logs)
	defer log.SetOutput(os.Stderr)
	req := httptest.NewRequest("POST", "https://s40.test/v1/image", &dropReader{})
	req.ContentLength = 500000
	req.Header.Set("Authorization", "Bearer "+tok)
	w := httptest.NewRecorder()
	e.pub.ServeHTTP(w, req)
	m, _ := parseS40(w.Body.String())
	if w.Code != 400 || m.get("status") != "bad_request" {
		t.Fatalf("%d %q", w.Code, w.Body.String())
	}
	if !strings.Contains(logs.String(), `"evt":"body_read_error"`) || strings.Contains(logs.String(), "10.0.0.") {
		t.Fatalf("log: %s", logs.String())
	}
}

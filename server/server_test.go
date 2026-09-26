package main

import (
	"bytes"
	"context"
	"crypto/tls"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/anthropics/anthropic-sdk-go/option"
)

const testAdmin = "test-admin-token-0123456789abcdef0123456789"

type tenv struct {
	t     *testing.T
	srv   *server
	pub   http.Handler
	admin http.Handler
}

func newEnv(t *testing.T, reqLimit int) *tenv {
	t.Helper()
	st, err := openStore(filepath.Join(t.TempDir(), "t.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { st.close() })
	s := &server{cfg: config{environment: "test", mock: true}, st: st,
		chat: &chatService{st: st, model: mockModel{}, reqLimit: reqLimit, tokLimit: 100000}, adminToken: testAdmin}
	return &tenv{t: t, srv: s, pub: s.publicMux(), admin: s.adminMux()}
}

type resp struct {
	code int
	msg  s40Msg
	raw  string
	hdr  http.Header
}

func (e *tenv) do(method, path, token, body string) resp {
	e.t.Helper()
	req := httptest.NewRequest(method, "https://s40.test"+path, strings.NewReader(body))
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	w := httptest.NewRecorder()
	e.pub.ServeHTTP(w, req)
	raw := w.Body.String()
	m, _ := parseS40(raw)
	return resp{w.Code, m, raw, w.Header()}
}

func (e *tenv) adminCall(method, path, token string, body any) (int, map[string]any) {
	e.t.Helper()
	var r io.Reader
	if body != nil {
		b, _ := json.Marshal(body)
		r = bytes.NewReader(b)
	}
	req := httptest.NewRequest(method, "http://127.0.0.1:9090"+path, r)
	req.Header.Set("Authorization", "Bearer "+token)
	w := httptest.NewRecorder()
	e.admin.ServeHTTP(w, req)
	out := map[string]any{}
	json.Unmarshal(w.Body.Bytes(), &out)
	return w.Code, out
}

// pair runs the whole pairing flow and returns the device token.
func (e *tenv) pair(name string) (string, string) {
	e.t.Helper()
	st := e.do("POST", "/v1/pair/start", "", "S40/1\n\n")
	pairID, code := st.msg.get("pair"), st.msg.get("code")
	if st.code != 200 || len(code) != 6 {
		e.t.Fatalf("pair start: %d %q", st.code, st.raw)
	}
	if c, out := e.adminCall("POST", "/admin/pair/approve", testAdmin, map[string]string{"code": code, "name": name}); c != 200 {
		e.t.Fatalf("approve: %d %v", c, out)
	}
	cl := e.do("POST", "/v1/pair/claim", "", "S40/1\npair: "+pairID+"\n\n")
	if cl.msg.get("status") != "ok" {
		e.t.Fatalf("claim: %q", cl.raw)
	}
	return cl.msg.get("token"), cl.msg.get("device")
}

var seq int
var seqMu sync.Mutex

func rid() string {
	seqMu.Lock()
	defer seqMu.Unlock()
	seq++
	return "req-" + time.Now().Format("150405") + "-" + strings.Repeat("x", seq%5) + string(rune('a'+seq%26)) + "0000"
}

func (e *tenv) chat(token, request, conv, text string) resp {
	return e.do("POST", "/v1/chat", token, formatS40([]kv{{"request", request}, {"conversation", conv}}, text))
}

// ------------------------------------------------------------ protocol / sanitize

func TestProtocolRoundTrip(t *testing.T) {
	s := formatS40([]kv{{"status", "ok"}, {"n", 3}, {"flag", true}, {"skip", nil}}, "çğıİöşü\nikinci satır")
	m, err := parseS40(s)
	if err != nil || m.get("status") != "ok" || m.get("n") != "3" || m.get("flag") != "1" || m.text != "çğıİöşü\nikinci satır" {
		t.Fatalf("%v %+v", err, m)
	}
	if _, ok := m.fields["skip"]; ok {
		t.Fatal("nil field written")
	}
	if m, _ := parseS40("S40/1\r\nrequest: abc\r\n\r\nx"); m.get("request") != "abc" {
		t.Fatal("CRLF")
	}
	if _, err := parseS40("HTTP/1.1\n\n"); err == nil {
		t.Fatal("bad magic accepted")
	}
	if m, _ := parseS40(formatS40([]kv{{"a", "x\nstatus: evil"}}, "")); m.get("a") != "x status: evil" || m.get("status") != "" {
		t.Fatal("header injection")
	}
}

func TestSanitize(t *testing.T) {
	got := sanitizeReply("## Başlık\n**Kalın** ve `kod` 😀\n* madde “tırnak” – tire…")
	want := "Başlık\nKalın ve kod\n- madde \"tırnak\" - tire..."
	if got != want {
		t.Fatalf("got %q want %q", got, want)
	}
	out, cut := limitChars(strings.Repeat("kelime ", 100), 50)
	if !cut || len([]rune(out)) > 54 {
		t.Fatalf("%q", out)
	}
}

// ------------------------------------------------------------ health / echo

func TestHealthEcho(t *testing.T) {
	e := newEnv(t, 30)
	h := e.do("GET", "/health", "", "")
	if h.code != 200 || h.msg.get("status") != "ok" || h.msg.get("mock") != "1" || h.hdr.Get("Cache-Control") != "no-store, private" {
		t.Fatalf("%d %q", h.code, h.raw)
	}
	ec := e.do("POST", "/echo", "", echoProbe)
	if ec.msg.get("probe") != "match" || ec.msg.text != echoProbe || !strings.Contains(ec.msg.get("hex"), "c3a7") {
		t.Fatalf("%q", ec.raw)
	}
	if r := e.do("POST", "/echo", "", "cgiIosu"); r.msg.get("probe") != "differs" {
		t.Fatal("probe differs")
	}
	if r := e.do("POST", "/echo", "", string([]byte{0x63, 0xe7, 0x0a})); r.code != 400 || r.msg.get("hex") != "63e70a" {
		t.Fatalf("bad utf8: %d %q", r.code, r.raw)
	}
	if r := e.do("POST", "/echo", "", strings.Repeat("x", 513)); r.code != 413 {
		t.Fatal("echo size")
	}
	if r := e.do("GET", "/nope", "", ""); r.code != 404 {
		t.Fatal("404")
	}
	if r := e.do("POST", "/health", "", ""); r.code != 405 {
		t.Fatalf("405 got %d", r.code)
	}
	// admin routes do not exist on the public listener
	if r := e.do("GET", "/admin/devices", "", ""); r.code != 405 && r.code != 404 {
		t.Fatalf("admin on public: %d", r.code)
	}
}

// ------------------------------------------------------------ pairing / admin

func TestPairingAndRevoke(t *testing.T) {
	e := newEnv(t, 30)
	tok, dev := e.pair("Nokia 6300")
	if len(tok) != 32 || !strings.HasPrefix(dev, "dev-") {
		t.Fatalf("%q %q", tok, dev)
	}
	if r := e.chat(tok, rid(), "", "merhaba"); r.msg.get("status") != "ok" {
		t.Fatalf("%q", r.raw)
	}
	if c, out := e.adminCall("GET", "/admin/devices", testAdmin, nil); c != 200 || len(out["devices"].([]any)) != 1 {
		t.Fatalf("%d %v", c, out)
	}
	if c, _ := e.adminCall("POST", "/admin/devices/revoke", testAdmin, map[string]string{"device_id": dev}); c != 200 {
		t.Fatal("revoke")
	}
	if r := e.chat(tok, rid(), "", "merhaba"); r.code != 401 {
		t.Fatalf("revoked still works: %d", r.code)
	}
}

func TestPairingRules(t *testing.T) {
	e := newEnv(t, 30)
	st := e.do("POST", "/v1/pair/start", "", "")
	pairID, code := st.msg.get("pair"), st.msg.get("code")
	if r := e.do("POST", "/v1/pair/claim", "", "S40/1\npair: "+pairID+"\n\n"); r.msg.get("status") != "pending" {
		t.Fatal("pending")
	}
	if c, _ := e.adminCall("POST", "/admin/pair/approve", "wrong-token-wrong-token-wrong-token-xx", map[string]string{"code": code}); c != 401 {
		t.Fatal("admin without token")
	}
	if c, _ := e.adminCall("POST", "/admin/pair/approve", testAdmin, map[string]string{"code": "000000"}); c != 404 && code != "000000" {
		t.Fatal("unknown code")
	}
	e.adminCall("POST", "/admin/pair/approve", testAdmin, map[string]string{"code": code, "name": "x"})
	if r := e.do("POST", "/v1/pair/claim", "", "S40/1\npair: "+pairID+"\n\n"); r.msg.get("status") != "ok" {
		t.Fatal("claim")
	}
	if r := e.do("POST", "/v1/pair/claim", "", "S40/1\npair: "+pairID+"\n\n"); r.msg.get("status") != "expired" {
		t.Fatal("token handed out twice")
	}
	if c, _ := e.adminCall("POST", "/admin/pair/approve", testAdmin, map[string]string{"code": code}); c != 404 {
		t.Fatal("code approved twice")
	}
	if r := e.do("POST", "/v1/pair/claim", "", "S40/1\npair: nothex\n\n"); r.code != 400 {
		t.Fatal("bad pair id")
	}
	busy := false
	for i := 0; i < 5; i++ {
		if e.do("POST", "/v1/pair/start", "", "").msg.get("status") == "pair_busy" {
			busy = true
		}
	}
	if !busy {
		t.Fatal("pending pairing limit")
	}
}

// ------------------------------------------------------------ chat

func TestChatFlow(t *testing.T) {
	e := newEnv(t, 30)
	tok, _ := e.pair("p")
	if r := e.chat("", rid(), "", "x"); r.code != 401 {
		t.Fatal("no token")
	}
	if r := e.chat("wrongtoken0123456789", rid(), "", "x"); r.code != 401 {
		t.Fatal("wrong token")
	}
	first := e.chat(tok, rid(), "", "Türkiye'nin başkenti neresi? çğıİöşü")
	conv := first.msg.get("conversation")
	if first.code != 200 || first.msg.get("mock") != "1" || !strings.HasPrefix(first.msg.text, "[Test mode]") || len(conv) != 16 {
		t.Fatalf("%q", first.raw)
	}
	second := e.chat(tok, rid(), conv, "Bunu biraz kısalt")
	if second.msg.get("conversation") != conv || !strings.Contains(second.msg.text, "Earlier messages in this chat: 1") {
		t.Fatalf("context: %q", second.raw)
	}
	if fresh := e.chat(tok, rid(), "", "yeni"); fresh.msg.get("conversation") == conv {
		t.Fatal("new conversation reused id")
	}

	// replay: same id -> recorded reply, no new record
	id := rid()
	a := e.chat(tok, id, "", "tekrar")
	b := e.chat(tok, id, "", "tekrar")
	if b.msg.get("replayed") != "1" || b.msg.text != a.msg.text {
		t.Fatalf("replay: %q", b.raw)
	}
	if r := e.chat(tok, id, "", "farklı"); r.code != 409 || r.msg.get("status") != "request_mismatch" {
		t.Fatal("mismatch")
	}

	// uncertain stays uncertain; definite errors mapped
	u := rid()
	if r := e.chat(tok, u, "", "[[mock:uncertain]]"); r.code != 504 || r.msg.get("status") != "uncertain" {
		t.Fatalf("%q", r.raw)
	}
	if r := e.chat(tok, u, "", "[[mock:uncertain]]"); r.msg.get("status") != "uncertain" {
		t.Fatal("uncertain replay")
	}
	for text, want := range map[string]string{"[[mock:error]]": "upstream_error", "[[mock:overloaded]]": "overloaded",
		"[[mock:billing]]": "billing"} {
		if r := e.chat(tok, rid(), "", text); r.msg.get("status") != want {
			t.Fatalf("%s -> %q", text, r.raw)
		}
	}
	if r := e.chat(tok, rid(), "", "[[mock:long]]"); r.msg.get("truncated") != "1" || len([]rune(r.msg.text)) > 2004 {
		t.Fatal("long")
	}
	if r := e.chat(tok, rid(), "", "[[mock:cut]]"); r.msg.get("truncated") != "1" {
		t.Fatal("cut")
	}

	// input validation
	if r := e.chat(tok, rid(), "", ""); r.msg.get("status") != "empty_message" {
		t.Fatal("empty")
	}
	if r := e.chat(tok, rid(), "", strings.Repeat("x", 1001)); r.code != 413 {
		t.Fatal("too long")
	}
	if r := e.chat(tok, "bad id!", "", "x"); r.code != 400 {
		t.Fatal("bad id")
	}
	if r := e.chat(tok, rid(), "0123456789abcdef", "x"); r.msg.get("status") != "conversation_not_found" {
		t.Fatal("unknown conv")
	}

	// delete
	d := e.chat(tok, rid(), "", "silinecek").msg.get("conversation")
	if r := e.do("POST", "/v1/delete", tok, "S40/1\nconversation: "+d+"\n\n"); r.msg.get("status") != "deleted" {
		t.Fatalf("delete %q", r.raw)
	}
	if r := e.chat(tok, rid(), d, "devam"); r.msg.get("status") != "conversation_not_found" {
		t.Fatal("deleted conv still usable")
	}
}

func TestDeviceIsolation(t *testing.T) {
	e := newEnv(t, 30)
	a, _ := e.pair("a")
	b, _ := e.pair("b")
	conv := e.chat(a, rid(), "", "gizli").msg.get("conversation")
	if r := e.chat(b, rid(), conv, "oku"); r.msg.get("status") != "conversation_not_found" {
		t.Fatal("device B sees device A's conversation")
	}
	if r := e.do("POST", "/v1/delete", b, "S40/1\nconversation: "+conv+"\n\n"); r.code != 404 {
		t.Fatal("device B deletes device A's conversation")
	}
}

func TestOneRequestAtATime(t *testing.T) {
	e := newEnv(t, 30)
	tok, _ := e.pair("p")
	var wg sync.WaitGroup
	statuses := make([]string, 2)
	for i := range statuses {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			statuses[i] = e.chat(tok, rid(), "", "[[mock:slow]] "+string(rune('a'+i))).msg.get("status")
		}(i)
	}
	wg.Wait()
	if !(statuses[0] == "ok" && statuses[1] == "busy" || statuses[0] == "busy" && statuses[1] == "ok") {
		t.Fatalf("%v", statuses)
	}
}

func TestDailyLimit(t *testing.T) {
	e := newEnv(t, 3)
	tok, _ := e.pair("p")
	var last resp
	for i := 0; i < 4; i++ {
		last = e.chat(tok, rid(), "", "limit")
	}
	if last.code != 429 || last.msg.get("status") != "limit" || last.msg.get("remaining") != "0" {
		t.Fatalf("%q", last.raw)
	}
}

func TestRetentionAndRestart(t *testing.T) {
	path := filepath.Join(t.TempDir(), "r.db")
	st, _ := openStore(path)
	s := &server{cfg: config{mock: true}, st: st, chat: &chatService{st: st, model: mockModel{}, reqLimit: 30, tokLimit: 1e5}, adminToken: testAdmin}
	e := &tenv{t: t, srv: s, pub: s.publicMux(), admin: s.adminMux()}
	tok, dev := e.pair("p")
	conv := e.chat(tok, rid(), "", "eski").msg.get("conversation")
	old := time.Now().Add(-31 * 24 * time.Hour).UnixMilli()
	st.db.Exec(`UPDATE conversations SET updated_at=? WHERE id=?`, old, conv)
	st.db.Exec(`UPDATE requests SET created_at=? WHERE conversation_id=?`, old, conv)
	if err := st.cleanup(context.Background()); err != nil {
		t.Fatal(err)
	}
	if r := e.chat(tok, rid(), conv, "devam"); r.msg.get("status") != "conversation_not_found" {
		t.Fatal("expired conversation still there")
	}
	// a request left pending by a crash becomes "uncertain" on restart
	st.db.Exec(`INSERT INTO requests (device_id, request_id, conversation_id, message_sha, state, created_at)
		VALUES (?, 'crash-req-0001', 'x', ?, 'pending', ?)`, dev, sha256Hex("m"), time.Now().UnixMilli())
	st.close()
	st2, _ := openStore(path)
	defer st2.close()
	var state string
	st2.db.QueryRow(`SELECT state FROM requests WHERE request_id='crash-req-0001'`).Scan(&state)
	if state != "uncertain" {
		t.Fatalf("state after restart: %s", state)
	}
}

// ------------------------------------------------------------ real SDK path, fake Anthropic

type fakeAPI struct {
	mu     sync.Mutex
	calls  int
	bodies []map[string]any
	hdrs   []http.Header
	status int
	body   string
	drop   bool
}

func (f *fakeAPI) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	f.mu.Lock()
	f.calls++
	var b map[string]any
	json.NewDecoder(r.Body).Decode(&b)
	f.bodies = append(f.bodies, b)
	f.hdrs = append(f.hdrs, r.Header.Clone())
	status, body, drop := f.status, f.body, f.drop
	f.mu.Unlock()
	if drop {
		hj, _ := w.(http.Hijacker)
		c, _, _ := hj.Hijack()
		c.Close()
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	io.WriteString(w, body)
}

func (f *fakeAPI) count() int {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.calls
}

func okBody(text, stop string) string {
	b, _ := json.Marshal(map[string]any{"id": "msg_1", "type": "message", "role": "assistant", "model": "claude-opus-5",
		"content": []any{map[string]any{"type": "text", "text": text}}, "stop_reason": stop,
		"usage": map[string]any{"input_tokens": 12, "output_tokens": 34}})
	return string(b)
}

func errBody(typ, msg string) string {
	b, _ := json.Marshal(map[string]any{"type": "error", "error": map[string]any{"type": typ, "message": msg}})
	return string(b)
}

func fakeModel(t *testing.T, f *fakeAPI, fallbacks bool, effort string) *claudeModel {
	ts := httptest.NewServer(f)
	t.Cleanup(ts.Close)
	return newClaudeModel("sk-ant-test-not-a-real-key", "claude-opus-5", effort, fallbacks, option.WithBaseURL(ts.URL))
}

func TestClaudeRequestShape(t *testing.T) {
	f := &fakeAPI{status: 200, body: okBody("Ankara.", "end_turn")}
	m := fakeModel(t, f, true, "low")
	r, err := m.reply(context.Background(), []turn{{"user", "a"}, {"assistant", "b"}}, "c")
	if err != nil || r.text != "Ankara." || r.outputTokens != 34 {
		t.Fatalf("%v %+v", err, r)
	}
	b := f.bodies[0]
	if b["model"] != "claude-opus-5" || b["fallbacks"] != "default" {
		t.Fatalf("%v", b)
	}
	if oc, _ := b["output_config"].(map[string]any); oc["effort"] != "low" {
		t.Fatalf("effort %v", b["output_config"])
	}
	if !strings.Contains(f.hdrs[0].Get("Anthropic-Beta"), "server-side-fallback-2026-07-01") {
		t.Fatal("beta header")
	}
	if f.hdrs[0].Get("X-Api-Key") != "sk-ant-test-not-a-real-key" {
		t.Fatal("key header")
	}
	msgs := b["messages"].([]any)
	roles := []string{}
	for _, m := range msgs {
		roles = append(roles, m.(map[string]any)["role"].(string))
	}
	if strings.Join(roles, ",") != "user,assistant,user" {
		t.Fatal(roles)
	}
	raw, _ := json.Marshal(b)
	if !strings.Contains(string(raw), "Claude S40") || strings.Contains(string(raw), "sk-ant") {
		t.Fatal("system prompt / key in body")
	}
}

func TestClaudeOptionsOff(t *testing.T) {
	f := &fakeAPI{status: 200, body: okBody("x", "end_turn")}
	fakeModel(t, f, false, "").reply(context.Background(), nil, "c")
	if _, ok := f.bodies[0]["fallbacks"]; ok {
		t.Fatal("fallbacks sent")
	}
	if _, ok := f.bodies[0]["output_config"]; ok {
		t.Fatal("effort sent")
	}
}

func TestClaudeStopReasons(t *testing.T) {
	f := &fakeAPI{status: 200, body: okBody("yarım", "max_tokens")}
	if r, _ := fakeModel(t, f, true, "low").reply(context.Background(), nil, "c"); !r.cutOff {
		t.Fatal("cutOff")
	}
	f2 := &fakeAPI{status: 200, body: okBody("", "refusal")}
	if r, _ := fakeModel(t, f2, true, "low").reply(context.Background(), nil, "c"); !r.refused {
		t.Fatal("refused")
	}
}

func TestClaudeErrorsNoRetry(t *testing.T) {
	cases := []struct {
		status int
		body   string
		want   string
	}{
		{500, errBody("api_error", "x"), "upstream_error"},
		{429, errBody("rate_limit_error", "x"), "rate_limited"},
		{529, errBody("overloaded_error", "x"), "overloaded"},
		{401, errBody("authentication_error", "x"), "config_error"},
		{400, errBody("invalid_request_error", "Your credit balance is too low to access the Anthropic API."), "billing"},
	}
	for _, c := range cases {
		f := &fakeAPI{status: c.status, body: c.body}
		_, err := fakeModel(t, f, true, "low").reply(context.Background(), nil, "c")
		ue, ok := err.(*upstreamError)
		if !ok || ue.code != c.want || ue.kind != "definite" || f.count() != 1 {
			t.Fatalf("%d: %v calls=%d", c.status, err, f.count())
		}
	}
	f := &fakeAPI{drop: true}
	_, err := fakeModel(t, f, true, "low").reply(context.Background(), nil, "c")
	if ue, ok := err.(*upstreamError); !ok || ue.kind != "uncertain" || f.count() != 1 {
		t.Fatalf("dropped connection: %v calls=%d", err, f.count())
	}
}

// ------------------------------------------------------------ TLS as the Nokia sees it

func TestPhoneTLSHandshake(t *testing.T) {
	dir := t.TempDir()
	cert, key := filepath.Join(dir, "c.pem"), filepath.Join(dir, "k.pem")
	cmd := exec.Command("openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-sha1", "-days", "1",
		"-subj", "/CN=127.0.0.1", "-keyout", key, "-out", cert)
	if out, err := cmd.CombinedOutput(); err != nil {
		t.Skipf("openssl not available: %v %s", err, out)
	}
	cfg, err := phoneTLS(cert, key)
	if err != nil {
		t.Fatal(err)
	}
	ln, err := tls.Listen("tcp", "127.0.0.1:0", cfg)
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			go func() { c.(*tls.Conn).Handshake(); c.Close() }()
		}
	}()
	dial := func(suites []uint16) error {
		c, err := tls.Dial("tcp", ln.Addr().String(), &tls.Config{
			InsecureSkipVerify: true, // test only: checks the negotiation, not the CA
			MinVersion:         tls.VersionTLS10, MaxVersion: tls.VersionTLS10, CipherSuites: suites,
		})
		if err == nil {
			c.Close()
		}
		return err
	}
	// the Nokia 6300's own offer (minus RC4-MD5, which Go cannot express)
	if err := dial([]uint16{tls.TLS_RSA_WITH_RC4_128_SHA, tls.TLS_RSA_WITH_3DES_EDE_CBC_SHA,
		tls.TLS_RSA_WITH_AES_128_CBC_SHA, tls.TLS_RSA_WITH_AES_256_CBC_SHA}); err != nil {
		t.Fatalf("phone offer refused: %v", err)
	}
	if err := dial([]uint16{tls.TLS_RSA_WITH_RC4_128_SHA}); err == nil {
		t.Fatal("RC4-only client accepted")
	}
	if err := dial([]uint16{tls.TLS_RSA_WITH_3DES_EDE_CBC_SHA}); err == nil {
		t.Fatal("3DES-only client accepted")
	}
}

func TestMain(m *testing.M) {
	os.Exit(m.Run())
}

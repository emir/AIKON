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
		chat: &chatService{st: st, models: singleModel("claude-opus-5", "Claude", mockModel{}), meter: &dailyMeter{st: st, reqLimit: reqLimit, tokLimit: 100000}}, adminToken: testAdmin}
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

func TestLongReplyParts(t *testing.T) {
	e := newEnv(t, 30)
	tok, _ := e.pair("p")
	id := rid()
	first := e.chat(tok, id, "", "[[mock:long]]")
	if first.msg.get("more") != "1" || first.msg.get("truncated") != "1" || len([]rune(first.msg.text)) > partChars {
		t.Fatalf("first part: %q", first.raw[:200])
	}
	all := first.msg.text
	next := first.msg.get("next")
	for i := 0; next != "" && i < 10; i++ {
		r := e.do("POST", "/v1/more", tok, formatS40([]kv{{"request", id}, {"offset", next}}, ""))
		if r.code != 200 || len([]rune(r.msg.text)) > partChars || r.msg.text == "" {
			t.Fatalf("more: %d %q", r.code, r.raw)
		}
		all += r.msg.text
		next = r.msg.get("next")
		if r.msg.get("more") != "1" {
			if next != "" || r.msg.get("truncated") != "0" {
				t.Fatalf("last part: %q", r.raw[:80])
			}
		}
	}
	if next != "" || !strings.HasSuffix(all, strings.Repeat("Long test line çğıİöşü. ", 199)+"Long test line çğıİöşü.") {
		t.Fatalf("parts do not add up: %d lines", strings.Count(all, "Long test line"))
	}
	// a replay also starts with the first part
	if r := e.chat(tok, id, "", "[[mock:long]]"); r.msg.get("replayed") != "1" || r.msg.get("next") != first.msg.get("next") {
		t.Fatalf("replay: %q", r.raw[:120])
	}
	// the stored reply is capped; the last part says so
	h := rid()
	r := e.chat(tok, h, "", "[[mock:huge]]")
	for i := 0; r.msg.get("more") == "1" && i < 10; i++ {
		r = e.do("POST", "/v1/more", tok, formatS40([]kv{{"request", h}, {"offset", r.msg.get("next")}}, ""))
	}
	if r.msg.get("truncated") != "1" || !strings.HasSuffix(r.msg.text, "...") {
		t.Fatalf("huge last part: %q", r.raw)
	}
	// errors: other device, unknown request, bad offset
	other, _ := e.pair("q")
	if r := e.do("POST", "/v1/more", other, formatS40([]kv{{"request", id}, {"offset", "0"}}, "")); r.code != 404 {
		t.Fatal("other device reads the reply")
	}
	if r := e.do("POST", "/v1/more", tok, formatS40([]kv{{"request", id}, {"offset", "-1"}}, "")); r.code != 400 {
		t.Fatal("negative offset")
	}
	if r := e.do("POST", "/v1/more", tok, formatS40([]kv{{"request", id}, {"offset", "999999"}}, "")); r.code != 404 {
		t.Fatal("offset past the end")
	}
}

func TestConversationsAndHistory(t *testing.T) {
	e := newEnv(t, 30)
	tok, _ := e.pair("p")
	a := e.chat(tok, rid(), "", "Birinci sohbet:\tçok   uzun bir başlık olabilir, kırk sekiz karakteri kesinlikle geçer").msg.get("conversation")
	e.chat(tok, rid(), a, "ikinci mesaj")
	b := e.chat(tok, rid(), "", "İkinci sohbet").msg.get("conversation")
	e.chat(tok, rid(), "", "[[mock:error]] başarısız") // conversation without messages: not listed
	l := e.do("POST", "/v1/conversations", tok, "S40/1\n\n")
	lines := strings.Split(strings.TrimSuffix(l.msg.text, "\n"), "\n")
	if l.code != 200 || l.msg.get("count") != "2" || len(lines) != 2 {
		t.Fatalf("%q", l.raw)
	}
	f0, f1 := strings.Split(lines[0], "\t"), strings.Split(lines[1], "\t")
	if f0[0] != b || f0[2] != "2" || f0[3] != "İkinci sohbet" || f1[0] != a || f1[2] != "4" {
		t.Fatalf("%q", l.msg.text)
	}
	if len([]rune(f1[3])) > titleChars+4 || strings.Contains(f1[3], "  ") || !strings.HasPrefix(f1[3], "Birinci sohbet: çok uzun") {
		t.Fatalf("title %q", f1[3])
	}

	h := e.do("POST", "/v1/history", tok, "S40/1\nconversation: "+a+"\n\n")
	if h.code != 200 || h.msg.get("count") != "4" || h.msg.get("older") != "0" {
		t.Fatalf("%q", h.raw)
	}
	if !strings.HasPrefix(h.msg.text, "u ") || !strings.Contains(h.msg.text, "\na ") || !strings.Contains(h.msg.text, "ikinci mesaj\n") {
		t.Fatalf("%q", h.msg.text)
	}
	// long conversations: newest messages only, within the byte budget
	for i := 0; i < 6; i++ {
		e.chat(tok, rid(), a, "[[mock:long]] "+string(rune('a'+i)))
	}
	h = e.do("POST", "/v1/history", tok, "S40/1\nconversation: "+a+"\n\n")
	if h.msg.get("older") != "1" || len(h.msg.text) > historyBytes+200 {
		t.Fatalf("older=%s len=%d", h.msg.get("older"), len(h.msg.text))
	}
	other, _ := e.pair("q")
	if r := e.do("POST", "/v1/history", other, "S40/1\nconversation: "+a+"\n\n"); r.code != 404 {
		t.Fatal("other device reads history")
	}
	if r := e.do("POST", "/v1/conversations", other, "S40/1\n\n"); r.msg.get("count") != "0" {
		t.Fatal("other device lists conversations")
	}
}

func TestWebSearchBudget(t *testing.T) {
	e := newEnv(t, 30)
	e.srv.chat.search, e.srv.chat.searchLimit = true, 3
	tok, _ := e.pair("p")
	r := e.chat(tok, rid(), "", "[[mock:search]] hava")
	if r.msg.get("searched") != "2" || !strings.Contains(r.msg.text, "Web: example.com, example.org") {
		t.Fatalf("%q", r.raw)
	}
	off := e.do("POST", "/v1/chat", tok, formatS40([]kv{{"request", rid()}, {"conversation", ""}, {"search", "0"}}, "[[mock:search]] kapalı"))
	if off.msg.get("searched") != "" || !strings.Contains(off.msg.text, "Web search: off") {
		t.Fatalf("search:0 %q", off.raw)
	}
	e.chat(tok, rid(), "", "[[mock:search]] ikinci") // 4 of 3 used: budget gone
	if r := e.chat(tok, rid(), "", "[[mock:search]] üçüncü"); !strings.Contains(r.msg.text, "Web search: off") {
		t.Fatalf("budget not enforced: %q", r.raw)
	}
	e.srv.chat.search = false
	e2, _ := e.pair("q")
	if r := e.chat(e2, rid(), "", "[[mock:search]] x"); !strings.Contains(r.msg.text, "Web search: off") {
		t.Fatal("server setting off")
	}
}

func TestMigrateOldDatabase(t *testing.T) {
	path := filepath.Join(t.TempDir(), "old.db")
	st, _ := openStore(path)
	st.db.Exec(`ALTER TABLE usage DROP COLUMN searches`)
	st.db.Exec(`ALTER TABLE requests DROP COLUMN searches`)
	st.close()
	st2, err := openStore(path)
	if err != nil {
		t.Fatal(err)
	}
	defer st2.close()
	var n int
	st2.db.QueryRow(`SELECT COUNT(*) FROM pragma_table_info('usage') WHERE name='searches'`).Scan(&n)
	if n != 1 {
		t.Fatal("column not added")
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
	s := &server{cfg: config{mock: true}, st: st, chat: &chatService{st: st, models: singleModel("claude-opus-5", "Claude", mockModel{}), meter: &dailyMeter{st: st, reqLimit: 30, tokLimit: 1e5}}, adminToken: testAdmin}
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
	queue  []string // bodies served first, in order (then body)
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
	if len(f.queue) > 0 {
		body, f.queue = f.queue[0], f.queue[1:]
	}
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
	r, err := m.reply(context.Background(), []turn{{role: "user", content: "a"}, {role: "assistant", content: "b"}}, "c", replyOpts{})
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
	if !strings.Contains(string(raw), "AIKON") || strings.Contains(string(raw), "sk-ant") {
		t.Fatal("system prompt / key in body")
	}
}

func TestClaudeWebSearch(t *testing.T) {
	cited := map[string]any{"type": "text", "text": "Ankara'da bugün 21 derece.", "citations": []any{
		map[string]any{"type": "web_search_result_location", "url": "https://www.example.com/hava", "title": "t",
			"cited_text": "x", "encrypted_index": "e"}}}
	paused := map[string]any{"id": "msg_1", "type": "message", "role": "assistant", "model": "claude-opus-5",
		"content": []any{
			map[string]any{"type": "text", "text": "Bakayım."},
			map[string]any{"type": "server_tool_use", "id": "srvtoolu_1", "name": "web_search", "input": map[string]any{"query": "ankara hava"}},
		}, "stop_reason": "pause_turn",
		"usage": map[string]any{"input_tokens": 100, "output_tokens": 10, "server_tool_use": map[string]any{"web_search_requests": 1}}}
	final := map[string]any{"id": "msg_2", "type": "message", "role": "assistant", "model": "claude-opus-5",
		"content": []any{
			map[string]any{"type": "web_search_tool_result", "tool_use_id": "srvtoolu_1", "content": []any{}},
			cited,
		}, "stop_reason": "end_turn",
		"usage": map[string]any{"input_tokens": 200, "output_tokens": 20, "server_tool_use": map[string]any{"web_search_requests": 1}}}
	pb, _ := json.Marshal(paused)
	fb, _ := json.Marshal(final)
	f := &fakeAPI{status: 200, queue: []string{string(pb)}, body: string(fb)}
	m := fakeModel(t, f, true, "low")
	m.search = searchConfig{maxUses: 2, country: "TR", timezone: "Europe/Istanbul"}
	r, err := m.reply(context.Background(), nil, "Ankara hava?", replyOpts{search: true})
	if err != nil || r.text != "Ankara'da bugün 21 derece." || r.searches != 2 || r.inputTokens != 300 || r.outputTokens != 30 {
		t.Fatalf("%v %+v", err, r)
	}
	if len(r.sources) != 1 || r.sources[0] != "example.com" || r.cutOff {
		t.Fatalf("%+v", r)
	}
	tools, _ := f.bodies[0]["tools"].([]any)
	tool, _ := tools[0].(map[string]any)
	loc, _ := tool["user_location"].(map[string]any)
	if len(tools) != 1 || tool["type"] != "web_search_20260209" || tool["max_uses"] != 2.0 || loc["country"] != "TR" {
		t.Fatalf("tools %v", f.bodies[0]["tools"])
	}
	sys, _ := json.Marshal(f.bodies[0]["system"])
	if !strings.Contains(string(sys), "search the web") || !strings.Contains(string(sys), "Today's date") {
		t.Fatalf("system %s", sys)
	}
	// continuation: the paused assistant turn is sent back, no extra user message
	msgs := f.bodies[1]["messages"].([]any)
	if len(msgs) != 2 || msgs[1].(map[string]any)["role"] != "assistant" {
		t.Fatalf("continuation %v", msgs)
	}

	// search off: no tools, no search prompt
	f2 := &fakeAPI{status: 200, body: okBody("x", "end_turn")}
	fakeModel(t, f2, true, "low").reply(context.Background(), nil, "c", replyOpts{})
	if _, ok := f2.bodies[0]["tools"]; ok {
		t.Fatal("tools sent without search")
	}

	// still paused after the continuation: cut off, not an error
	f3 := &fakeAPI{status: 200, body: string(pb)}
	r3, err := fakeModel(t, f3, true, "low").reply(context.Background(), nil, "c", replyOpts{search: true})
	if err != nil || !r3.cutOff || f3.count() != 1+maxContinuations {
		t.Fatalf("%v %+v calls=%d", err, r3, f3.count())
	}
}

func TestClaudeOptionsOff(t *testing.T) {
	f := &fakeAPI{status: 200, body: okBody("x", "end_turn")}
	fakeModel(t, f, false, "").reply(context.Background(), nil, "c", replyOpts{})
	if _, ok := f.bodies[0]["fallbacks"]; ok {
		t.Fatal("fallbacks sent")
	}
	if _, ok := f.bodies[0]["output_config"]; ok {
		t.Fatal("effort sent")
	}
}

func TestClaudeStopReasons(t *testing.T) {
	f := &fakeAPI{status: 200, body: okBody("yarım", "max_tokens")}
	if r, _ := fakeModel(t, f, true, "low").reply(context.Background(), nil, "c", replyOpts{}); !r.cutOff {
		t.Fatal("cutOff")
	}
	f2 := &fakeAPI{status: 200, body: okBody("", "refusal")}
	if r, _ := fakeModel(t, f2, true, "low").reply(context.Background(), nil, "c", replyOpts{}); !r.refused {
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
		_, err := fakeModel(t, f, true, "low").reply(context.Background(), nil, "c", replyOpts{})
		ue, ok := err.(*upstreamError)
		if !ok || ue.code != c.want || ue.kind != "definite" || f.count() != 1 {
			t.Fatalf("%d: %v calls=%d", c.status, err, f.count())
		}
	}
	f := &fakeAPI{drop: true}
	_, err := fakeModel(t, f, true, "low").reply(context.Background(), nil, "c", replyOpts{})
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
	cfg, err := phoneTLS(cert, key, nil)
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

// ------------------------------------------------------------ 0.4.0: pins, search, notes, calendar

func listLines(r resp) [][]string {
	var out [][]string
	for _, l := range strings.Split(strings.TrimSuffix(r.msg.text, "\n"), "\n") {
		if l != "" {
			out = append(out, strings.Split(l, "\t"))
		}
	}
	return out
}

func TestPins(t *testing.T) {
	e := newEnv(t, 100)
	tok, _ := e.pair("p")
	a := e.chat(tok, rid(), "", "birinci").msg.get("conversation")
	b := e.chat(tok, rid(), "", "ikinci").msg.get("conversation")
	pin := func(tok, conv, v string) resp {
		return e.do("POST", "/v1/pin", tok, formatS40([]kv{{"conversation", conv}, {"pinned", v}}, ""))
	}
	if r := pin(tok, a, "1"); r.code != 200 || r.msg.get("status") != "ok" || r.msg.get("pinned") != "1" {
		t.Fatalf("%q", r.raw)
	}
	// old format (no "pins"): 4 columns, pinned first
	old := listLines(e.do("POST", "/v1/conversations", tok, "S40/1\n\n"))
	if len(old) != 2 || len(old[0]) != 4 || old[0][0] != a || old[1][0] != b {
		t.Fatalf("%q", old)
	}
	cur := listLines(e.do("POST", "/v1/conversations", tok, "S40/1\npins: 1\n\n"))
	if len(cur) != 2 || len(cur[0]) != 5 || cur[0][0] != "1" || cur[0][1] != a || cur[1][0] != "0" || cur[1][4] != "ikinci" {
		t.Fatalf("%q", cur)
	}
	if r := pin(tok, a, "0"); r.msg.get("pinned") != "0" {
		t.Fatalf("%q", r.raw)
	}
	if l := listLines(e.do("POST", "/v1/conversations", tok, "S40/1\npins: 1\n\n")); l[0][1] != b || l[1][0] != "0" {
		t.Fatalf("%q", l)
	}
	if r := pin(tok, "0123456789abcdef", "1"); r.code != 404 || r.msg.get("status") != "conversation_not_found" {
		t.Fatalf("%q", r.raw)
	}
	if r := pin(tok, a, "yes"); r.code != 400 {
		t.Fatal("bad pinned value")
	}
	other, _ := e.pair("q")
	if r := pin(other, a, "1"); r.code != 404 {
		t.Fatal("other device pins")
	}
	for i := 0; i < maxPinned; i++ {
		c := e.chat(tok, rid(), "", "sohbet").msg.get("conversation")
		if r := pin(tok, c, "1"); r.msg.get("status") != "ok" {
			t.Fatalf("pin %d: %q", i, r.raw)
		}
	}
	if r := pin(tok, a, "1"); r.code != 409 || r.msg.get("status") != "pin_limit" || r.msg.get("max") != "10" {
		t.Fatalf("%q", r.raw)
	}
}

func TestPinnedSurvivesExpiryAndTrim(t *testing.T) {
	e := newEnv(t, 200)
	st := e.srv.st
	tok, _ := e.pair("p")
	kept := e.chat(tok, rid(), "", "sabit").msg.get("conversation")
	gone := e.chat(tok, rid(), "", "eski").msg.get("conversation")
	e.do("POST", "/v1/pin", tok, formatS40([]kv{{"conversation", kept}, {"pinned", "1"}}, ""))
	old := time.Now().Add(-31 * 24 * time.Hour).UnixMilli()
	st.db.Exec(`UPDATE conversations SET updated_at=?`, old)
	if err := st.cleanup(context.Background()); err != nil {
		t.Fatal(err)
	}
	if r := e.chat(tok, rid(), gone, "devam"); r.msg.get("status") != "conversation_not_found" {
		t.Fatal("expired unpinned conversation still there")
	}
	for i := 0; i < maxConversation+2; i++ {
		e.chat(tok, rid(), "", "yeni")
	}
	var n int
	st.db.QueryRow(`SELECT COUNT(*) FROM conversations WHERE pinned=0`).Scan(&n)
	if n != maxConversation {
		t.Fatalf("unpinned conversations: %d", n)
	}
	if r := e.do("POST", "/v1/history", tok, "S40/1\nconversation: "+kept+"\n\n"); r.code != 200 {
		t.Fatalf("pinned conversation removed: %q", r.raw)
	}
}

func TestSearch(t *testing.T) {
	e := newEnv(t, 100)
	tok, _ := e.pair("p")
	a := e.chat(tok, rid(), "", "Işıklı şişe nasıl yapılır?").msg.get("conversation")
	e.chat(tok, rid(), a, "Peki ÇİĞ köfte tarifi?")
	b := e.chat(tok, rid(), "", "Ankara hava durumu").msg.get("conversation")
	search := func(tok, q string) resp { return e.do("POST", "/v1/search", tok, "S40/1\n\n"+q) }

	r := search(tok, "isikli SISE")
	l := listLines(r)
	if r.code != 200 || r.msg.get("count") != "1" || len(l) != 1 || l[0][0] != a || len(l[0]) != 4 {
		t.Fatalf("%q", r.raw)
	}
	if !strings.Contains(l[0][3], "Işıklı şişe") || strings.Contains(l[0][3], "\n") {
		t.Fatalf("snippet %q", l[0][3])
	}
	// every word must be in the conversation, in any message
	if l := listLines(search(tok, "sise cig")); len(l) != 1 || l[0][0] != a {
		t.Fatalf("%q", l)
	}
	if l := listLines(search(tok, "sise ankara")); len(l) != 0 {
		t.Fatalf("%q", l)
	}
	if l := listLines(search(tok, "hava")); len(l) != 1 || l[0][0] != b || l[0][2] != "2" { // question + quoting reply
		t.Fatalf("%q", l)
	}
	if r := search(tok, "a"); r.code != 400 || r.msg.get("status") != "bad_query" {
		t.Fatalf("%q", r.raw)
	}
	other, _ := e.pair("q")
	if r := search(other, "hava"); r.msg.get("count") != "0" {
		t.Fatal("other device finds conversations")
	}
}

func TestSnippet(t *testing.T) {
	text := []rune(strings.Repeat("önce gelen kelimeler ", 5) + "ARANAN" + strings.Repeat(" sonra gelen kelimeler", 8))
	s := snippet(text, runeIndex(foldRunes(string(text)), []rune("aranan")), 6)
	if !strings.HasPrefix(s, "...") || !strings.HasSuffix(s, "...") || !strings.Contains(s, "ARANAN") ||
		len([]rune(s)) > snippetBefore+snippetAfter+12 {
		t.Fatalf("%q", s)
	}
	if s := snippet([]rune("kısa metin"), 0, 4); s != "kısa metin" {
		t.Fatalf("%q", s)
	}
}

type recModel struct {
	mu    sync.Mutex
	last  replyOpts
	n     int
	label string
}

func (m *recModel) reply(ctx context.Context, h []turn, msg string, o replyOpts) (reply, error) {
	m.mu.Lock()
	m.last = o
	m.n++
	m.mu.Unlock()
	return mockModel{label: m.label}.reply(ctx, h, msg, o)
}

func (m *recModel) calls() int {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.n
}

func TestChatOptions(t *testing.T) {
	e := newEnv(t, 100)
	rec := &recModel{}
	e.srv.chat.models = singleModel("claude-opus-5", "Claude", rec)
	tok, _ := e.pair("p")
	notes := "Adım Emir.\u0001 İstanbul'da yaşıyorum, kısa yaz. " + strings.Repeat("uzun ", 100)
	r := e.do("POST", "/v1/chat", tok, formatS40([]kv{{"request", rid()}, {"instructions", notes}, {"calendar", "1"},
		{"local-time", "2026-09-26 21:05"}}, "[[mock:event]] yarın 15:00 dişçi"))
	if r.msg.get("status") != "ok" || !strings.Contains(r.msg.text, "EVENT: 2026-09-27 15:00 | Test mode event") {
		t.Fatalf("%q", r.raw)
	}
	o := rec.last
	if !o.calendar || o.localTime.Format("2006-01-02 15:04") != "2026-09-26 21:05" {
		t.Fatalf("%+v", o)
	}
	if !strings.HasPrefix(o.instructions, "Adım Emir. İstanbul'da") || len([]rune(o.instructions)) > maxInstructions+4 ||
		strings.ContainsRune(o.instructions, 1) {
		t.Fatalf("%q", o.instructions)
	}
	// older phones: no options; a malformed clock is ignored
	e.chat(tok, rid(), "", "merhaba")
	if o := rec.last; o.calendar || o.instructions != "" || !o.localTime.IsZero() {
		t.Fatalf("%+v", o)
	}
	e.do("POST", "/v1/chat", tok, formatS40([]kv{{"request", rid()}, {"calendar", "1"}, {"local-time", "yarın"}}, "x"))
	if o := rec.last; !o.calendar || !o.localTime.IsZero() {
		t.Fatalf("%+v", o)
	}
}

func TestSystemPromptParts(t *testing.T) {
	now := time.Date(2026, 9, 26, 18, 0, 0, 0, time.UTC)
	plain := systemPrompt("Claude", replyOpts{}, now)
	if strings.Contains(plain, "EVENT:") || strings.Contains(plain, "user_notes") || strings.Contains(plain, "search the web") {
		t.Fatal(plain)
	}
	lt := time.Date(2026, 9, 26, 21, 5, 0, 0, time.UTC)
	p := systemPrompt("Grok", replyOpts{calendar: true, localTime: lt, instructions: "Kısa yaz."}, now)
	for _, want := range []string{"EVENT: YYYY-MM-DD HH:MM | short title", "TODO: YYYY-MM-DD | short title",
		"Saturday 2026-09-26 21:05", "You are Grok, talking", "<user_notes>\nKısa yaz.\n</user_notes>"} {
		if !strings.Contains(p, want) {
			t.Fatalf("missing %q in %s", want, p)
		}
	}
}

// disconnectModel cancels the phone's request (a dropped connection) while
// the paid call is running, then answers.
type disconnectModel struct{ cancel context.CancelFunc }

func (m disconnectModel) reply(ctx context.Context, h []turn, msg string, o replyOpts) (reply, error) {
	m.cancel()
	return mockModel{}.reply(ctx, h, msg, o)
}

func TestReplyStoredAfterDisconnect(t *testing.T) {
	e := newEnv(t, 100)
	tok, dev := e.pair("p")
	ctx, cancel := context.WithCancel(context.Background())
	e.srv.chat.models = singleModel("claude-opus-5", "Claude", disconnectModel{cancel})
	req := rid()
	e.srv.chat.chat(ctx, dev, req, "", "Merhaba", replyOpts{})
	e.srv.chat.models = singleModel("claude-opus-5", "Claude", mockModel{})
	r := e.chat(tok, req, "", "Merhaba")
	if r.msg.get("status") != "ok" || r.msg.get("replayed") != "1" || !strings.HasPrefix(r.msg.text, "[Test mode]") {
		t.Fatalf("billed reply lost after disconnect: %q", r.raw)
	}
}

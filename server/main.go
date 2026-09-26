// Command claude-s40-server is the whole Claude S40 backend in one binary:
//
//	Nokia S40 phone --TLS 1.0+, RSA, own CA--> this server --HTTPS--> Claude API
//
// It replaces the Cloudflare Worker + Durable Objects and the separate TLS
// relay. Public listener (TLS, for the phone): /health, /echo, /v1/chat,
// /v1/delete, /v1/pair/start, /v1/pair/claim. Admin listener (plain HTTP,
// meant to be bound to 127.0.0.1 and reached through an SSH tunnel):
// /admin/pair/approve, /admin/devices, /admin/devices/revoke.
//
// Logs: one JSON line per handshake summary and per request (method, path,
// status, ms, TLS). Never message text, replies, tokens, keys or client IPs.
package main

import (
	"context"
	"crypto/subtle"
	"crypto/tls"
	"encoding/json"
	"errors"
	"flag"
	"log"
	"net/http"
	"os"
	"strconv"
	"strings"
	"time"

	"golang.org/x/text/unicode/norm"
)

const (
	service       = "claude-s40-server"
	version       = "0.2.0"
	echoProbe     = "Claude S40 UTF-8: ç ğ ı İ ö ş ü Ç Ğ Ö Ş Ü"
	maxRequest    = 4096
	maxEcho       = 512
	maxPairClaim  = 512
	cleanupPeriod = 6 * time.Hour
)

type config struct {
	listen, adminListen, cert, key, db string
	environment                        string
	apiKeyFile, adminTokenFile         string
	model, effort                      string
	fallbacks, mock                    bool
	reqLimit                           int
	tokLimit                           int64
}

func env(k, def string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return def
}

func envInt(k string, def int) int {
	if n, err := strconv.Atoi(os.Getenv(k)); err == nil && n >= 0 {
		return n
	}
	return def
}

func loadConfig() config {
	var c config
	flag.StringVar(&c.listen, "listen", env("LISTEN", ":8443"), "public TLS address")
	flag.StringVar(&c.adminListen, "admin-listen", env("ADMIN_LISTEN", "127.0.0.1:9090"), "admin HTTP address (keep private)")
	flag.StringVar(&c.cert, "cert", env("TLS_CERT", "/certs/server-chain.pem"), "certificate chain (PEM)")
	flag.StringVar(&c.key, "key", env("TLS_KEY", "/certs/server.key"), "private key (PEM)")
	flag.StringVar(&c.db, "db", env("DB_PATH", "/data/claude-s40.db"), "SQLite file")
	flag.Parse()
	c.environment = env("ENVIRONMENT", "production")
	c.apiKeyFile = env("ANTHROPIC_API_KEY_FILE", "/run/secrets/anthropic_api_key")
	c.adminTokenFile = env("ADMIN_TOKEN_FILE", "/run/secrets/admin_token")
	c.model = env("CLAUDE_MODEL", "claude-opus-5")
	c.effort = env("CLAUDE_EFFORT", "low")
	c.fallbacks = env("CLAUDE_FALLBACKS", "default") == "default"
	c.mock = env("MOCK_ANTHROPIC", "0") == "1"
	c.reqLimit = envInt("DAILY_REQUEST_LIMIT", 100)
	c.tokLimit = int64(envInt("DAILY_OUTPUT_TOKEN_LIMIT", 100000))
	return c
}

func readSecret(path string) string {
	b, err := os.ReadFile(path)
	if err != nil {
		return ""
	}
	return strings.TrimSpace(string(b))
}

func main() {
	log.SetFlags(0)
	c := loadConfig()
	st, err := openStore(c.db)
	if err != nil {
		log.Fatalf("db: %v", err)
	}
	var m model = mockModel{}
	if !c.mock {
		key := readSecret(c.apiKeyFile)
		if key == "" {
			log.Fatalf("no API key in %s (or set MOCK_ANTHROPIC=1)", c.apiKeyFile)
		}
		m = newClaudeModel(key, c.model, c.effort, c.fallbacks)
	}
	adminToken := readSecret(c.adminTokenFile)
	if len(adminToken) < 32 {
		log.Printf("warning: no admin token (>= 32 chars) in %s; admin API disabled", c.adminTokenFile)
	}
	srv := &server{cfg: c, st: st, chat: &chatService{st: st, model: m, reqLimit: c.reqLimit, tokLimit: c.tokLimit},
		adminToken: adminToken}

	go func() {
		for {
			if err := st.cleanup(context.Background()); err != nil {
				logJSON(map[string]any{"evt": "cleanup_error"})
			}
			time.Sleep(cleanupPeriod)
		}
	}()

	tlsCfg, err := phoneTLS(c.cert, c.key)
	if err != nil {
		log.Fatalf("tls: %v", err)
	}
	pub := &http.Server{
		Addr: c.listen, Handler: srv.publicMux(), TLSConfig: tlsCfg,
		ReadHeaderTimeout: 30 * time.Second, ReadTimeout: 60 * time.Second, WriteTimeout: 120 * time.Second,
		TLSNextProto: map[string]func(*http.Server, *tls.Conn, http.Handler){}, // HTTP/1.1 only
		ErrorLog:     log.New(handshakeLog{}, "", 0),
	}
	adm := &http.Server{Addr: c.adminListen, Handler: srv.adminMux(), ReadHeaderTimeout: 10 * time.Second}
	go func() { log.Fatal(adm.ListenAndServe()) }()
	logJSON(map[string]any{"evt": "start", "version": version, "listen": c.listen, "admin": c.adminListen,
		"model": c.model, "mock": c.mock, "env": c.environment})
	log.Fatal(pub.ListenAndServeTLS("", ""))
}

// ------------------------------------------------------------ TLS for the phone

// phoneTLS: TLS 1.0+ with RSA key exchange for the Nokia (measured offer:
// TLS 1.0, RC4-MD5/RC4-SHA/3DES/AES128/AES256-CBC-SHA, no SNI); ECDHE and
// TLS 1.2/1.3 for everything else. No RC4, no 3DES.
func phoneTLS(certFile, keyFile string) (*tls.Config, error) {
	cert, err := tls.LoadX509KeyPair(certFile, keyFile)
	if err != nil {
		return nil, err
	}
	cfg := &tls.Config{
		Certificates: []tls.Certificate{cert},
		MinVersion:   tls.VersionTLS10,
		CipherSuites: []uint16{
			tls.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,
			tls.TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384,
			tls.TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA,
			tls.TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA,
			tls.TLS_RSA_WITH_AES_128_CBC_SHA,
			tls.TLS_RSA_WITH_AES_256_CBC_SHA,
		},
	}
	cfg.GetConfigForClient = func(h *tls.ClientHelloInfo) (*tls.Config, error) {
		vers := make([]string, 0, len(h.SupportedVersions))
		for _, v := range h.SupportedVersions {
			vers = append(vers, tls.VersionName(v))
		}
		logJSON(map[string]any{"evt": "hello", "sni": h.ServerName != "", "versions": strings.Join(vers, ","),
			"suites": len(h.CipherSuites)})
		return nil, nil
	}
	return cfg, nil
}

type handshakeLog struct{}

// Write keeps net/http's handshake errors (useful for phones) without the remote address.
func (handshakeLog) Write(p []byte) (int, error) {
	s := strings.TrimSpace(string(p))
	if i := strings.Index(s, " from "); i >= 0 {
		if j := strings.Index(s[i+6:], ": "); j >= 0 {
			s = s[:i] + s[i+6+j:]
		}
	}
	logJSON(map[string]any{"evt": "http_error", "detail": truncate(s, 200)})
	return len(p), nil
}

func logJSON(v map[string]any) {
	v["t"] = time.Now().UTC().Format(time.RFC3339)
	b, _ := json.Marshal(v)
	log.Print(string(b))
}

// ------------------------------------------------------------ handlers

type server struct {
	cfg        config
	st         *store
	chat       *chatService
	adminToken string
}

type statusRecorder struct {
	http.ResponseWriter
	code int
}

func (r *statusRecorder) WriteHeader(c int) { r.code = c; r.ResponseWriter.WriteHeader(c) }

func logged(h http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		rec := &statusRecorder{ResponseWriter: w, code: 200}
		h.ServeHTTP(rec, r)
		tlsInfo := ""
		if r.TLS != nil {
			tlsInfo = tls.VersionName(r.TLS.Version) + " " + tls.CipherSuiteName(r.TLS.CipherSuite)
		}
		path := r.URL.Path
		if !knownPath(path) {
			path = "(other)"
		}
		logJSON(map[string]any{"evt": "req", "method": r.Method, "path": path, "http": rec.code,
			"ms": time.Since(start).Milliseconds(), "tls": tlsInfo})
	})
}

func knownPath(p string) bool {
	switch p {
	case "/health", "/echo", "/v1/chat", "/v1/delete", "/v1/pair/start", "/v1/pair/claim",
		"/admin/pair/approve", "/admin/devices", "/admin/devices/revoke":
		return true
	}
	return false
}

func (s *server) publicMux() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /health", s.health)
	mux.HandleFunc("POST /echo", s.echo)
	mux.HandleFunc("POST /v1/chat", s.chatHandler)
	mux.HandleFunc("POST /v1/delete", s.deleteHandler)
	mux.HandleFunc("POST /v1/pair/start", s.pairStart)
	mux.HandleFunc("POST /v1/pair/claim", s.pairClaim)
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		if knownPath(r.URL.Path) {
			writeS40(w, 405, []kv{{"status", "method_not_allowed"}}, "")
			return
		}
		writeS40(w, 404, []kv{{"status", "not_found"}}, "")
	})
	return logged(mux)
}

func tlsFields(r *http.Request) []kv {
	v, c := "-", "-"
	if r.TLS != nil {
		v, c = tls.VersionName(r.TLS.Version), tls.CipherSuiteName(r.TLS.CipherSuite)
	}
	return []kv{{"tls-version", v}, {"tls-cipher", c}, {"http-protocol", r.Proto}}
}

func (s *server) health(w http.ResponseWriter, r *http.Request) {
	f := []kv{{"status", "ok"}, {"service", service}, {"version", version}, {"environment", s.cfg.environment},
		{"mock", s.cfg.mock}, {"time", time.Now().UTC().Format(time.RFC3339)}}
	writeS40(w, 200, append(f, tlsFields(r)...), "Claude S40 server is running.")
}

func (s *server) echo(w http.ResponseWriter, r *http.Request) {
	b, ok := readLimited(r, maxEcho)
	if !ok {
		writeS40(w, 413, []kv{{"status", "too_large"}, {"max", maxEcho}}, "")
		return
	}
	text, ok := decodeUTF8(b)
	if !ok {
		writeS40(w, 400, []kv{{"status", "bad_utf8"}, {"bytes", len(b)}, {"hex", hexPrefix(b, 64)}}, "")
		return
	}
	probe := "differs"
	if norm.NFC.String(text) == echoProbe {
		probe = "match"
	}
	f := []kv{{"status", "ok"}, {"bytes", len(b)}, {"chars", len([]rune(text))}, {"utf8", "valid"},
		{"probe", probe}, {"hex", hexPrefix(b, 64)}}
	writeS40(w, 200, append(f, tlsFields(r)...), text)
}

func bearer(r *http.Request) string {
	h := strings.TrimSpace(r.Header.Get("Authorization"))
	if !strings.HasPrefix(h, "Bearer ") {
		return ""
	}
	return strings.TrimSpace(h[7:])
}

var tokenOK = func(t string) bool {
	if len(t) < 16 || len(t) > 64 {
		return false
	}
	for _, c := range t {
		if !(c >= '0' && c <= '9' || c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z') {
			return false
		}
	}
	return true
}

// authedS40 authenticates the device and parses an S40 body.
func (s *server) authedS40(w http.ResponseWriter, r *http.Request) (string, s40Msg, bool) {
	t := bearer(r)
	device := ""
	if tokenOK(t) {
		d, err := s.st.deviceForToken(r.Context(), t)
		if err != nil {
			writeS40(w, 500, []kv{{"status", "server_error"}}, "")
			return "", s40Msg{}, false
		}
		device = d
	}
	if device == "" {
		writeS40(w, 401, []kv{{"status", "unauthorized"}}, "")
		return "", s40Msg{}, false
	}
	b, ok := readLimited(r, maxRequest)
	if !ok {
		writeS40(w, 413, []kv{{"status", "too_large"}, {"max", maxRequest}}, "")
		return "", s40Msg{}, false
	}
	text, ok := decodeUTF8(b)
	if !ok {
		writeS40(w, 400, []kv{{"status", "bad_utf8"}}, "")
		return "", s40Msg{}, false
	}
	m, err := parseS40(text)
	if err != nil {
		writeS40(w, 400, []kv{{"status", "bad_request"}}, "")
		return "", s40Msg{}, false
	}
	return device, m, true
}

func (s *server) chatHandler(w http.ResponseWriter, r *http.Request) {
	device, m, ok := s.authedS40(w, r)
	if !ok {
		return
	}
	reqID, conv := m.get("request"), m.get("conversation")
	msg := strings.TrimSpace(norm.NFC.String(m.text))
	if !idRE.MatchString(reqID) || (conv != "" && !convRE.MatchString(conv)) {
		writeS40(w, 400, []kv{{"status", "bad_request"}}, "")
		return
	}
	if msg == "" {
		writeS40(w, 400, []kv{{"status", "empty_message"}, {"request", reqID}}, "")
		return
	}
	if len([]rune(msg)) > maxMessageChars {
		writeS40(w, 413, []kv{{"status", "too_large"}, {"request", reqID}, {"max", maxMessageChars}}, "")
		return
	}
	res, err := s.chat.chat(r.Context(), device, reqID, conv, msg)
	if err != nil {
		logJSON(map[string]any{"evt": "chat_error"})
		writeS40(w, 500, []kv{{"status", "server_error"}, {"request", reqID}}, "")
		return
	}
	f := []kv{{"status", res.status}, {"request", res.request}}
	if res.conversation != "" {
		f = append(f, kv{"conversation", res.conversation})
	}
	if res.status == "ok" {
		f = append(f, kv{"truncated", res.truncated}, kv{"refused", res.refused}, kv{"mock", res.mock})
		if res.replayed {
			f = append(f, kv{"replayed", true})
		}
	}
	if res.hasRemaining {
		f = append(f, kv{"remaining", res.remaining})
	}
	writeS40(w, res.http, f, res.text)
}

func (s *server) deleteHandler(w http.ResponseWriter, r *http.Request) {
	device, m, ok := s.authedS40(w, r)
	if !ok {
		return
	}
	conv := m.get("conversation")
	if !convRE.MatchString(conv) {
		writeS40(w, 400, []kv{{"status", "bad_request"}}, "")
		return
	}
	done, err := s.chat.deleteConversation(r.Context(), device, conv)
	if err != nil {
		writeS40(w, 500, []kv{{"status", "server_error"}}, "")
		return
	}
	if !done {
		writeS40(w, 404, []kv{{"status", "conversation_not_found"}, {"conversation", conv}}, "")
		return
	}
	writeS40(w, 200, []kv{{"status", "deleted"}, {"conversation", conv}}, "")
}

func (s *server) pairStart(w http.ResponseWriter, r *http.Request) {
	id, code, ttl, err := s.st.startPairing(r.Context())
	if errors.Is(err, errBusy) {
		writeS40(w, 429, []kv{{"status", "pair_busy"}}, "")
		return
	}
	if err != nil {
		writeS40(w, 500, []kv{{"status", "server_error"}}, "")
		return
	}
	writeS40(w, 200, []kv{{"status", "ok"}, {"pair", id}, {"code", code}, {"expires", int(ttl.Seconds())}}, "")
}

func (s *server) pairClaim(w http.ResponseWriter, r *http.Request) {
	b, ok := readLimited(r, maxPairClaim)
	text, ok2 := decodeUTF8(b)
	m, err := parseS40(text)
	pair := m.get("pair")
	if !ok || !ok2 || err != nil || len(pair) != 32 || strings.Trim(pair, "0123456789abcdef") != "" {
		writeS40(w, 400, []kv{{"status", "bad_request"}}, "")
		return
	}
	state, dev, tok, err := s.st.claimPairing(r.Context(), pair)
	switch {
	case err != nil:
		writeS40(w, 500, []kv{{"status", "server_error"}}, "")
	case state == "approved":
		writeS40(w, 200, []kv{{"status", "ok"}, {"device", dev}, {"token", tok}}, "")
	case state == "pending":
		writeS40(w, 202, []kv{{"status", "pending"}}, "")
	default:
		writeS40(w, 410, []kv{{"status", "expired"}}, "")
	}
}

// ------------------------------------------------------------ admin (JSON)

func (s *server) adminMux() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /admin/devices", s.adminAuth(func(w http.ResponseWriter, r *http.Request) {
		d, err := s.st.listDevices(r.Context())
		if err != nil {
			writeJSON(w, 500, map[string]any{"error": "server_error"})
			return
		}
		writeJSON(w, 200, map[string]any{"devices": d})
	}))
	mux.HandleFunc("POST /admin/pair/approve", s.adminAuth(func(w http.ResponseWriter, r *http.Request) {
		var body struct{ Code, Name string }
		if json.NewDecoder(http.MaxBytesReader(w, r.Body, 1024)).Decode(&body) != nil {
			writeJSON(w, 400, map[string]any{"error": "bad_json"})
			return
		}
		code := strings.Join(strings.Fields(body.Code), "")
		if body.Name == "" {
			body.Name = "phone"
		}
		if len(code) != 6 || strings.Trim(code, "0123456789") != "" || len(body.Name) > 40 {
			writeJSON(w, 400, map[string]any{"error": "bad_request"})
			return
		}
		id, err := s.st.approvePairing(r.Context(), code, body.Name)
		switch {
		case err != nil:
			writeJSON(w, 500, map[string]any{"error": "server_error"})
		case id == "":
			writeJSON(w, 404, map[string]any{"error": "no_such_code"})
		default:
			writeJSON(w, 200, map[string]any{"device_id": id, "name": body.Name})
		}
	}))
	mux.HandleFunc("POST /admin/devices/revoke", s.adminAuth(func(w http.ResponseWriter, r *http.Request) {
		var body struct {
			DeviceID string `json:"device_id"`
		}
		if json.NewDecoder(http.MaxBytesReader(w, r.Body, 1024)).Decode(&body) != nil {
			writeJSON(w, 400, map[string]any{"error": "bad_json"})
			return
		}
		ok, err := s.st.revokeDevice(r.Context(), body.DeviceID)
		switch {
		case err != nil:
			writeJSON(w, 500, map[string]any{"error": "server_error"})
		case !ok:
			writeJSON(w, 404, map[string]any{"error": "not_found"})
		default:
			writeJSON(w, 200, map[string]any{"revoked": body.DeviceID})
		}
	}))
	return logged(mux)
}

func (s *server) adminAuth(h http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		t := bearer(r)
		if len(s.adminToken) < 32 || subtle.ConstantTimeCompare([]byte(sha256Hex(t)), []byte(sha256Hex(s.adminToken))) != 1 {
			writeJSON(w, 401, map[string]any{"error": "unauthorized"})
			return
		}
		h(w, r)
	}
}

func writeJSON(w http.ResponseWriter, code int, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store, private")
	w.WriteHeader(code)
	json.NewEncoder(w).Encode(v)
}

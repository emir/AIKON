// Command claude-s40-server is the whole AIKon (formerly Claude S40) backend in one binary:
//
//	Nokia S40 phone --TLS 1.0+, RSA, own CA--> this server --HTTPS--> Claude API
//
// It replaces the Cloudflare Worker + Durable Objects and the separate TLS
// relay. Public listener (TLS, for the phone): /health, /echo, /v1/chat,
// /v1/more, /v1/models, /v1/conversations, /v1/history, /v1/delete, /v1/pin, /v1/search,
// /v1/transcribe (voice messages, see transcribe.go), /v1/image (photos,
// see image.go), /v1/pair/start,
// /v1/pair/claim. Admin listener (plain HTTP,
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
	"fmt"
	"log"
	"net/http"
	"os"
	"os/exec"
	"regexp"
	"strconv"
	"strings"
	"time"
	"unicode/utf16"

	"golang.org/x/text/unicode/norm"
)

const (
	service      = "claude-s40-server"
	version      = "0.8.2"
	echoProbe    = "Claude S40 UTF-8: ç ğ ı İ ö ş ü Ç Ğ Ö Ş Ü"
	maxRequest   = 6144
	maxEcho      = 512
	maxPairClaim = 512
	// the user's notes for Claude, sent by the phone with every message
	maxInstructions = 300
	maxSearchQuery  = 100
	cleanupPeriod   = 6 * time.Hour
)

type config struct {
	listen, adminListen, cert, key, db string
	environment                        string
	apiKeyFile, adminTokenFile         string
	model, effort                      string
	// MODELS: provider:model-id[=Label],... (first = default); empty means
	// only Claude (CLAUDE_MODEL), as before
	models                              string
	openAIEffort, xaiEffort, xaiKeyFile string
	geminiEffort, geminiKeyFile         string
	defaultModel                        string // DEFAULT_MODEL: a model id from MODELS, "" = the first
	fallbacks, mock                     bool
	reqLimit                            int
	tokLimit                            int64
	search                              bool
	searchLimit                         int
	searchMaxUses                       int
	searchCountry, searchCity           string
	searchTimezone                      string
	// voice messages: "off", "mock" or "openai"
	transcribe, openAIKeyFile, sttModel string
	transcribeLimit                     int
	ffmpeg                              string
	imageLimit                          int // photo uploads per device per UTC day
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
	c.model = env("CLAUDE_MODEL", "claude-opus-5-5")
	c.effort = env("CLAUDE_EFFORT", "low")
	c.fallbacks = env("CLAUDE_FALLBACKS", "default") == "default"
	c.models = env("MODELS", "anthropic:"+c.model)
	c.openAIEffort = env("OPENAI_EFFORT", "low")
	c.xaiEffort = env("XAI_EFFORT", "")
	c.xaiKeyFile = env("XAI_API_KEY_FILE", "/run/secrets/xai_api_key")
	c.geminiEffort = env("GEMINI_EFFORT", "low")
	c.defaultModel = env("DEFAULT_MODEL", "")
	c.geminiKeyFile = env("GEMINI_API_KEY_FILE", "/run/secrets/gemini_api_key")
	c.mock = env("MOCK_ANTHROPIC", "0") == "1"
	c.reqLimit = envInt("DAILY_REQUEST_LIMIT", 100)
	c.tokLimit = int64(envInt("DAILY_OUTPUT_TOKEN_LIMIT", 100000))
	c.search = env("WEB_SEARCH", "1") == "1"
	c.searchLimit = envInt("DAILY_SEARCH_LIMIT", 30)
	c.searchMaxUses = envInt("WEB_SEARCH_MAX_USES", 3)
	c.searchCountry = env("SEARCH_COUNTRY", "")
	c.searchCity = env("SEARCH_CITY", "")
	c.searchTimezone = env("SEARCH_TIMEZONE", "")
	c.transcribe = env("TRANSCRIBE", "off")
	c.openAIKeyFile = env("OPENAI_API_KEY_FILE", "/run/secrets/openai_api_key")
	c.sttModel = env("TRANSCRIBE_MODEL", defaultSTTModel)
	c.transcribeLimit = envInt("DAILY_TRANSCRIBE_LIMIT", 30)
	c.ffmpeg = env("FFMPEG", "ffmpeg")
	c.imageLimit = envInt("DAILY_IMAGE_LIMIT", 30)
	return c
}

// setupTranscribe: the speech-to-text service for voice messages, or nil
// when TRANSCRIBE=off. The converter (ffmpeg, needed for AMR) is nil if the
// binary is missing; WAV from the phone still works then.
func setupTranscribe(c config, st *store, m meter) (*transcribeService, audioConverter) {
	var conv audioConverter
	if p, err := exec.LookPath(c.ffmpeg); err == nil {
		conv = ffmpegConverter{p}
	}
	var stt speechToText
	switch c.transcribe {
	case "off", "":
		return nil, conv
	case "mock":
		stt = mockSTT{}
	case "openai":
		key := readSecret(c.openAIKeyFile)
		if key == "" {
			log.Fatalf("TRANSCRIBE=openai but no OpenAI API key in %s", c.openAIKeyFile)
		}
		stt = newOpenAISTT(key, c.sttModel)
	default:
		log.Fatalf("TRANSCRIBE must be off, mock or openai, not %q", c.transcribe)
	}
	return &transcribeService{st: st, stt: stt, meter: m}, conv
}

// buildCatalog: the models from MODELS with their keys; with MOCK_ANTHROPIC=1
// every model is a mock (no key needed, no network).
func buildCatalog(c config, secret func(string) string) (*catalog, error) {
	specs, err := parseModels(c.models)
	if err != nil {
		return nil, err
	}
	keyFiles := map[string]string{"anthropic": c.apiKeyFile, "openai": c.openAIKeyFile, "xai": c.xaiKeyFile,
		"gemini": c.geminiKeyFile}
	search := searchConfig{maxUses: int64(max(1, c.searchMaxUses)), country: c.searchCountry,
		city: c.searchCity, timezone: c.searchTimezone}
	cat := &catalog{}
	for _, sp := range specs {
		e := modelEntry{id: sp.id, label: sp.label, provider: sp.provider, search: true}
		if c.mock {
			e.m = mockModel{label: sp.label}
			cat.list = append(cat.list, e)
			continue
		}
		key := secret(keyFiles[sp.provider])
		if key == "" {
			return nil, fmt.Errorf("model %s: no %s API key in %s (or set MOCK_ANTHROPIC=1)", sp.id, sp.provider, keyFiles[sp.provider])
		}
		switch sp.provider {
		case "anthropic":
			cm := newClaudeModel(key, sp.id, c.effort, c.fallbacks)
			cm.name = sp.label
			cm.search = search
			e.m = cm
		case "openai", "xai":
			p, effort := openAIProvider, c.openAIEffort
			if sp.provider == "xai" {
				p, effort = xAIProvider, c.xaiEffort
			}
			rm := newResponsesModel(p, key, sp.id, sp.label, effort)
			rm.search = search
			e.m = rm
		case "gemini":
			e.m = newGeminiModel(key, sp.id, sp.label, c.geminiEffort)
		}
		cat.list = append(cat.list, e)
	}
	if c.defaultModel != "" {
		found := false
		for i, e := range cat.list {
			if e.id == c.defaultModel {
				cat.defIdx, found = i, true
			}
		}
		if !found {
			return nil, fmt.Errorf("DEFAULT_MODEL %q is not in MODELS", c.defaultModel)
		}
	}
	return cat, nil
}

func modelIDs(c *catalog) string {
	ids := make([]string, len(c.list))
	for i, e := range c.list {
		ids[i] = e.provider + ":" + e.id
	}
	return strings.Join(ids, ",")
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
	models, err := buildCatalog(c, readSecret)
	if err != nil {
		log.Fatal(err)
	}
	adminToken := readSecret(c.adminTokenFile)
	if len(adminToken) < 32 {
		log.Printf("warning: no admin token (>= 32 chars) in %s; admin API disabled", c.adminTokenFile)
	}
	m := meterFactory(st, c)
	srv := &server{cfg: c, st: st, chat: &chatService{st: st, models: models, meter: m,
		search: c.search, searchLimit: c.searchLimit}, adminToken: adminToken}
	srv.transcriber, srv.converter = setupTranscribe(c, st, m)

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
		// a 1 MB photo over EDGE can take a minute or two to arrive
		ReadHeaderTimeout: 30 * time.Second, ReadTimeout: 180 * time.Second, WriteTimeout: 240 * time.Second,
		TLSNextProto: map[string]func(*http.Server, *tls.Conn, http.Handler){}, // HTTP/1.1 only
		ErrorLog:     log.New(handshakeLog{}, "", 0),
	}
	adm := &http.Server{Addr: c.adminListen, Handler: srv.adminMux(), ReadHeaderTimeout: 10 * time.Second}
	go func() { log.Fatal(adm.ListenAndServe()) }()
	logJSON(map[string]any{"evt": "start", "version": version, "listen": c.listen, "admin": c.adminListen,
		"models": modelIDs(models), "default_model": models.def().id, "mock": c.mock, "env": c.environment, "web_search": c.search, "search_limit": c.searchLimit,
		"transcribe": c.transcribe, "stt_model": c.sttModel, "transcribe_limit": c.transcribeLimit,
		"ffmpeg": srv.converter != nil, "image_limit": c.imageLimit})
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

// ipRE matches IPv4 and bracketed IPv6 addresses, with an optional port.
var ipRE = regexp.MustCompile(`\b\d{1,3}(\.\d{1,3}){3}(:\d+)?\b|\[[0-9A-Fa-f:.%a-z]+\](:\d+)?`)

// Write keeps net/http's handshake errors (useful for phones) without any
// address: net/http puts the client's in "from ADDR: " and, for read
// errors, in "read tcp LOCAL->REMOTE", so every address is masked.
func (handshakeLog) Write(p []byte) (int, error) {
	s := strings.TrimSpace(string(p))
	if i := strings.Index(s, " from "); i >= 0 {
		if j := strings.Index(s[i+6:], ": "); j >= 0 {
			s = s[:i] + s[i+6+j:]
		}
	}
	s = ipRE.ReplaceAllString(s, "[addr]")
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
	// voice messages; transcriber is nil when TRANSCRIBE=off
	transcriber *transcribeService
	converter   audioConverter
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
	case "/health", "/echo", "/v1/chat", "/v1/more", "/v1/models", "/v1/conversations", "/v1/history", "/v1/delete",
		"/v1/pin", "/v1/search", "/v1/transcribe", "/v1/image", "/v1/pair/start", "/v1/pair/claim",
		"/admin/pair/approve", "/admin/devices", "/admin/devices/revoke":
		return true
	}
	return extraPaths[p]
}

func (s *server) publicMux() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /health", s.health)
	mux.HandleFunc("POST /echo", s.echo)
	mux.HandleFunc("POST /v1/chat", s.chatHandler)
	mux.HandleFunc("POST /v1/more", s.moreHandler)
	mux.HandleFunc("POST /v1/models", s.modelsHandler)
	mux.HandleFunc("POST /v1/conversations", s.conversationsHandler)
	mux.HandleFunc("POST /v1/history", s.historyHandler)
	mux.HandleFunc("POST /v1/delete", s.deleteHandler)
	mux.HandleFunc("POST /v1/pin", s.pinHandler)
	mux.HandleFunc("POST /v1/search", s.searchHandler)
	mux.HandleFunc("POST /v1/transcribe", s.transcribeHandler)
	mux.HandleFunc("POST /v1/image", s.imageHandler)
	mux.HandleFunc("POST /v1/pair/start", s.pairStart)
	mux.HandleFunc("POST /v1/pair/claim", s.pairClaim)
	for _, add := range extraRoutes {
		add(s, mux)
	}
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
		{"mock", s.cfg.mock}, {"web-search", s.cfg.search}, {"transcribe", s.transcriber != nil}, {"images", true}, {"time", time.Now().UTC().Format(time.RFC3339)}}
	for _, add := range extraHealth {
		f = append(f, add(s)...)
	}
	writeS40(w, 200, append(f, tlsFields(r)...), "AIKon server is running.")
}

func (s *server) echo(w http.ResponseWriter, r *http.Request) {
	b, err := readLimited(r, maxEcho)
	if err != nil {
		writeBodyErr(w, r, err, maxEcho)
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

// authDevice returns the device of the bearer token, or answers 401/500.
func (s *server) authDevice(w http.ResponseWriter, r *http.Request) (string, bool) {
	t := bearer(r)
	device := ""
	if tokenOK(t) {
		d, err := s.st.deviceForToken(r.Context(), t)
		if err != nil {
			writeS40(w, 500, []kv{{"status", "server_error"}}, "")
			return "", false
		}
		device = d
	}
	if device == "" {
		writeS40(w, 401, []kv{{"status", "unauthorized"}}, "")
		return "", false
	}
	return device, true
}

// authedS40 authenticates the device and parses an S40 body.
func (s *server) authedS40(w http.ResponseWriter, r *http.Request) (string, s40Msg, bool) {
	device, ok := s.authDevice(w, r)
	if !ok {
		return "", s40Msg{}, false
	}
	b, err := readLimited(r, maxRequest)
	if err != nil {
		writeBodyErr(w, r, err, maxRequest)
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
	reqID, conv, img, model := m.get("request"), m.get("conversation"), m.get("image"), m.get("model")
	msg := strings.TrimSpace(norm.NFC.String(m.text))
	if !idRE.MatchString(reqID) || (conv != "" && !convRE.MatchString(conv)) || (img != "" && !imageIDRE.MatchString(img)) ||
		(model != "" && !modelIDRE.MatchString(model)) {
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
	o := replyOpts{search: m.get("search") != "0", calendar: m.get("calendar") == "1",
		instructions: cleanInstructions(m.get("instructions")), imageID: img, model: model}
	if o.calendar {
		// the phone's own clock, for "tomorrow" and weekdays; ignored if malformed
		if t, err := time.Parse("2006-01-02 15:04", m.get("local-time")); err == nil {
			o.localTime = t
		}
	}
	res, err := s.chat.chat(r.Context(), device, reqID, conv, msg, o)
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
		if res.model != "" {
			f = append(f, kv{"model", res.model}, kv{"model-name", res.label})
		}
		if res.replayed {
			f = append(f, kv{"replayed", true})
		}
		f = append(f, partFields(res)...)
	}
	if res.hasRemaining {
		f = append(f, kv{"remaining", res.remaining})
		f = addMeterFields(r.Context(), s.chat.meter, device, f)
	}
	writeS40(w, res.http, f, res.text)
}

// cleanInstructions: the user's notes for Claude (one line from the phone),
// NFC, without control characters, at most maxInstructions characters.
func cleanInstructions(s string) string {
	s = strings.Map(func(r rune) rune {
		if r < 0x20 || r == 0x7f || r >= 0x80 && r < 0xa0 {
			return ' '
		}
		return r
	}, norm.NFC.String(s))
	s, _ = limitChars(strings.Join(strings.Fields(s), " "), maxInstructions)
	return s
}

// partFields: "more"/"next" while parts of a long reply are left; "searched"
// when web searches were made.
func partFields(res chatResult) []kv {
	var f []kv
	if res.more {
		f = append(f, kv{"more", true}, kv{"next", res.next})
	}
	if res.searches > 0 {
		f = append(f, kv{"searched", res.searches})
	}
	return f
}

// moreHandler returns the next part of a stored reply. It never calls Claude.
func (s *server) moreHandler(w http.ResponseWriter, r *http.Request) {
	device, m, ok := s.authedS40(w, r)
	if !ok {
		return
	}
	reqID := m.get("request")
	offset, err := strconv.Atoi(m.get("offset"))
	if !idRE.MatchString(reqID) || err != nil || offset < 0 {
		writeS40(w, 400, []kv{{"status", "bad_request"}}, "")
		return
	}
	res, found, err := s.chat.morePart(r.Context(), device, reqID, offset)
	if err != nil {
		writeS40(w, 500, []kv{{"status", "server_error"}, {"request", reqID}}, "")
		return
	}
	if !found {
		writeS40(w, 404, []kv{{"status", "not_found"}, {"request", reqID}}, "")
		return
	}
	f := append([]kv{{"status", "ok"}, {"request", reqID}, {"truncated", res.truncated}}, partFields(res)...)
	writeS40(w, 200, f, res.text)
}

// modelsHandler lists the models the phone may choose, default first, one
// per line: id TAB name TAB search (0/1) TAB photos (0/1) TAB provider
// (Claude, OpenAI, Grok: 0.10.2+ phones pick the provider first; older ones
// read only the first two fields). Never calls a model.
func (s *server) modelsHandler(w http.ResponseWriter, r *http.Request) {
	if _, _, ok := s.authedS40(w, r); !ok {
		return
	}
	var b strings.Builder
	for _, e := range s.chat.models.list {
		fmt.Fprintf(&b, "%s\t%s\t%d\t1\t%s\n", e.id, e.label, b2i(e.search && s.chat.search), providerNames[e.provider])
	}
	writeS40(w, 200, []kv{{"status", "ok"}, {"count", len(s.chat.models.list)}, {"default", s.chat.models.def().id}}, b.String())
}

// conversationsHandler lists the pinned, then the newest conversations, one
// per line: id TAB updated (ms) TAB messages TAB title. With "pins: 1"
// (0.7+ phones) each line starts with the pinned flag instead:
// pinned (0/1) TAB id TAB updated TAB messages TAB title. Older phones read
// everything after the third tab as the title, so they never get the flag.
// With "models: 1" the conversation's model id ("" if older than server
// 0.7.0) comes before the title: ... messages TAB model TAB title.
func (s *server) conversationsHandler(w http.ResponseWriter, r *http.Request) {
	device, m, ok := s.authedS40(w, r)
	if !ok {
		return
	}
	list, err := s.st.conversations(r.Context(), device)
	if err != nil {
		writeS40(w, 500, []kv{{"status", "server_error"}}, "")
		return
	}
	pins, models := m.get("pins") == "1", m.get("models") == "1"
	var b strings.Builder
	for _, c := range list {
		if pins {
			fmt.Fprintf(&b, "%d\t", b2i(c.pinned))
		}
		fmt.Fprintf(&b, "%s\t%d\t%d\t", c.id, c.updated, c.messages)
		if models {
			b.WriteString(c.model + "\t")
		}
		b.WriteString(c.title + "\n")
	}
	writeS40(w, 200, []kv{{"status", "ok"}, {"count", len(list)}}, b.String())
}

// pinHandler: "conversation", "pinned: 1" pins, "pinned: 0" unpins.
func (s *server) pinHandler(w http.ResponseWriter, r *http.Request) {
	device, m, ok := s.authedS40(w, r)
	if !ok {
		return
	}
	conv, pin := m.get("conversation"), m.get("pinned")
	if !convRE.MatchString(conv) || pin != "0" && pin != "1" {
		writeS40(w, 400, []kv{{"status", "bad_request"}}, "")
		return
	}
	st, err := s.st.setPinned(r.Context(), device, conv, pin == "1")
	if err != nil {
		writeS40(w, 500, []kv{{"status", "server_error"}}, "")
		return
	}
	code := map[string]int{"ok": 200, "conversation_not_found": 404, "pin_limit": 409}[st]
	f := []kv{{"status", st}, {"conversation", conv}, {"pinned", pin == "1" && st == "ok"}}
	if st == "pin_limit" {
		f = append(f, kv{"max", maxPinned})
	}
	writeS40(w, code, f, "")
}

// searchHandler: the text is the query. One line per conversation that
// contains every word: id TAB updated (ms) TAB matching messages TAB
// snippet. Never calls Claude.
func (s *server) searchHandler(w http.ResponseWriter, r *http.Request) {
	device, m, ok := s.authedS40(w, r)
	if !ok {
		return
	}
	q := strings.TrimSpace(norm.NFC.String(m.text))
	if n := len([]rune(q)); n < 2 || n > maxSearchQuery {
		writeS40(w, 400, []kv{{"status", "bad_query"}, {"min", 2}, {"max", maxSearchQuery}}, "")
		return
	}
	hits, err := s.st.search(r.Context(), device, q)
	if err != nil {
		writeS40(w, 500, []kv{{"status", "server_error"}}, "")
		return
	}
	var b strings.Builder
	for _, h := range hits {
		fmt.Fprintf(&b, "%s\t%d\t%d\t%s\n", h.id, h.updated, h.matches, h.snippet)
	}
	writeS40(w, 200, []kv{{"status", "ok"}, {"count", len(hits)}}, b.String())
}

// historyHandler returns the newest messages of a conversation, oldest
// first, each as "u N" or "a N" (N = UTF-16 length, as Java counts), a
// newline, the text and a newline. With "images: 1" (0.9+ phones) a user
// message that had a photo is "u N i"; older phones never get the "i".
func (s *server) historyHandler(w http.ResponseWriter, r *http.Request) {
	device, m, ok := s.authedS40(w, r)
	if !ok {
		return
	}
	conv := m.get("conversation")
	if !convRE.MatchString(conv) {
		writeS40(w, 400, []kv{{"status", "bad_request"}}, "")
		return
	}
	images, models := m.get("images") == "1", m.get("models") == "1"
	msgs, older, found, err := s.st.history(r.Context(), device, conv)
	if err != nil {
		writeS40(w, 500, []kv{{"status", "server_error"}}, "")
		return
	}
	if !found {
		writeS40(w, 404, []kv{{"status", "conversation_not_found"}, {"conversation", conv}}, "")
		return
	}
	var b strings.Builder
	for _, t := range msgs {
		role := "u"
		if t.role == "assistant" {
			role = "a"
		}
		mark := ""
		if images && t.imageID != "" {
			mark = " i"
		}
		if models && t.model != "" {
			mark += " m=" + t.model
		}
		fmt.Fprintf(&b, "%s %d%s\n%s\n", role, len(utf16.Encode([]rune(t.content))), mark, t.content)
	}
	writeS40(w, 200, []kv{{"status", "ok"}, {"conversation", conv}, {"count", len(msgs)}, {"older", older}}, b.String())
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
	b, rerr := readLimited(r, maxPairClaim)
	text, ok2 := decodeUTF8(b)
	m, err := parseS40(text)
	pair := m.get("pair")
	if rerr != nil || !ok2 || err != nil || len(pair) != 32 || strings.Trim(pair, "0123456789abcdef") != "" {
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
	for _, add := range extraAdminRoutes {
		add(s, mux)
	}
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

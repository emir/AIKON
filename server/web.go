package main

// The optional browser side, next to the phone API on the same port:
//
//   - PUBLIC_HOSTS (e.g. "example.com,www.example.com"): a client that names
//     one of them in SNI gets a publicly trusted certificate (ACME,
//     TLS-ALPN-01 on this port, cached in ACME_DIR), TLS 1.2+, and the web
//     mux; every other client (the Nokia sends no SNI) gets the private-CA
//     certificate and the phone API, as before. The Host header must match
//     the side the TLS handshake chose, else 421.
//   - DOWNLOAD_DIR with ca.cer, AIKON.jad, AIKON.jar: the phone's browser
//     gets a download page at https://PHONE_HOST/ and the app under /app/
//     (the JAD's MIDlet-Jar-URL is made absolute on the way out).
//   - HTTP_LISTEN: plain HTTP for one purpose only, the first step on a
//     phone that does not trust the private root yet: a landing page and
//     ca.cer. No API is reachable over it; public hosts are redirected to
//     https.

import (
	"bufio"
	"bytes"
	"crypto/sha1"
	"crypto/sha256"
	"crypto/tls"
	"fmt"
	"html"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"strings"

	"golang.org/x/crypto/acme"
	"golang.org/x/crypto/acme/autocert"
)

type webSide struct {
	hosts     map[string]bool // PUBLIC_HOSTS, lower-case
	phoneHost string          // PHONE_HOST: the phone side's DNS name ("" = the request's host)
	download  string          // DOWNLOAD_DIR ("" = no downloads)
	cert      func(*tls.ClientHelloInfo) (*tls.Certificate, error)
	caSHA1    string // fingerprints of ca.cer, shown on the landing page
	caSHA256  string
}

// extraWebRoutes add handlers to the web mux (public hosts only).
var extraWebRoutes []func(s *server, mux *http.ServeMux)

func newWebSide(c config) (*webSide, error) {
	w := &webSide{hosts: map[string]bool{}, phoneHost: strings.ToLower(c.phoneHost), download: c.downloadDir}
	for _, h := range strings.Split(c.publicHosts, ",") {
		if h = strings.ToLower(strings.TrimSpace(h)); h != "" {
			if net.ParseIP(h) != nil || !hostRE.MatchString(h) {
				return nil, fmt.Errorf("PUBLIC_HOSTS: %q is not a DNS name", h)
			}
			w.hosts[h] = true
		}
	}
	if len(w.hosts) > 0 {
		hosts := make([]string, 0, len(w.hosts))
		for h := range w.hosts {
			hosts = append(hosts, h)
		}
		m := &autocert.Manager{Prompt: autocert.AcceptTOS, Cache: autocert.DirCache(c.acmeDir),
			HostPolicy: autocert.HostWhitelist(hosts...), Email: c.acmeEmail}
		if c.acmeURL != "" {
			m.Client = &acme.Client{DirectoryURL: c.acmeURL}
		}
		w.cert = m.GetCertificate
	}
	if w.download != "" {
		if der, err := os.ReadFile(filepath.Join(w.download, "ca.cer")); err == nil {
			s1, s2 := sha1.Sum(der), sha256.Sum256(der)
			w.caSHA1, w.caSHA256 = colonHex(s1[:]), colonHex(s2[:])
		}
	}
	return w, nil
}

var hostRE = regexp.MustCompile(`^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$`)

func colonHex(b []byte) string {
	parts := make([]string, len(b))
	for i, x := range b {
		parts[i] = fmt.Sprintf("%02X", x)
	}
	return strings.Join(parts, ":")
}

// webTLS: what a browser naming a public host gets.
func (w *webSide) webTLS() *tls.Config {
	return &tls.Config{
		MinVersion:     tls.VersionTLS12,
		GetCertificate: w.cert,
		NextProtos:     []string{"http/1.1", acme.ALPNProto},
		CipherSuites: []uint16{
			tls.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256, tls.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,
			tls.TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384, tls.TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384,
			tls.TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256, tls.TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256,
		},
	}
}

// public: the client named a public host in SNI.
func (w *webSide) public(sni string) bool {
	return w != nil && w.cert != nil && w.hosts[strings.ToLower(sni)]
}

func hostOnly(h string) string {
	if host, _, err := net.SplitHostPort(h); err == nil {
		h = host
	}
	return strings.ToLower(strings.Trim(h, "[]"))
}

// rootHandler picks the phone API or the web side by the TLS handshake.
func (s *server) rootHandler() http.Handler {
	phone := s.publicMux()
	if s.web == nil || len(s.web.hosts) == 0 {
		return phone
	}
	web := s.webMux()
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		sni := ""
		if r.TLS != nil {
			sni = strings.ToLower(r.TLS.ServerName)
		}
		host := hostOnly(r.Host)
		switch {
		case s.web.public(sni) && host == sni:
			web.ServeHTTP(w, r)
		case s.web.public(sni) || s.web.hosts[host]:
			http.Error(w, "misdirected request", http.StatusMisdirectedRequest)
		default:
			phone.ServeHTTP(w, r)
		}
	})
}

// webHeaders: for every page of the web side.
func webHeaders(h http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		hd := w.Header()
		hd.Set("Strict-Transport-Security", "max-age=31536000")
		hd.Set("X-Content-Type-Options", "nosniff")
		hd.Set("Referrer-Policy", "no-referrer")
		hd.Set("X-Frame-Options", "DENY")
		hd.Set("Content-Security-Policy", "default-src 'self'; img-src 'self' data:; style-src 'self'; "+
			"form-action 'self' https:; frame-ancestors 'none'; base-uri 'none'")
		h.ServeHTTP(w, r)
	})
}

func (s *server) webMux() http.Handler {
	mux := http.NewServeMux()
	for _, add := range extraWebRoutes {
		add(s, mux)
	}
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/" {
			http.NotFound(w, r)
			return
		}
		writeHTML(w, 200, "AIKON", "<h1>AIKON</h1><p>AI chat for Nokia S40 and S60 phones.</p>")
	})
	return logged(webHeaders(mux))
}

func writeHTML(w http.ResponseWriter, code int, title, body string) {
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(code)
	fmt.Fprintf(w, "<!DOCTYPE html>\n<html><head><meta charset=\"utf-8\"><meta name=\"viewport\" "+
		"content=\"width=device-width\"><title>%s</title></head><body>%s</body></html>\n", html.EscapeString(title), body)
}

// ------------------------------------------------------------ downloads

var appFileRE = regexp.MustCompile(`^[A-Za-z0-9][A-Za-z0-9._-]*\.(jad|jar)$`)

// phoneHostFor: the phone side's name for links ("" = this request's host).
func (w *webSide) phoneHostFor(r *http.Request) string {
	if w.phoneHost != "" {
		return w.phoneHost
	}
	return hostOnly(r.Host)
}

// appFile serves AIKON.jad / AIKON.jar from DOWNLOAD_DIR on the phone side.
func (s *server) appFile(w http.ResponseWriter, r *http.Request) {
	name := r.PathValue("file")
	if s.web == nil || s.web.download == "" || !appFileRE.MatchString(name) {
		http.NotFound(w, r)
		return
	}
	b, err := os.ReadFile(filepath.Join(s.web.download, name))
	if err != nil {
		http.NotFound(w, r)
		return
	}
	ct := "application/java-archive"
	if strings.HasSuffix(name, ".jad") {
		ct = "text/vnd.sun.j2me.app-descriptor"
		b = absoluteJarURL(b, "https://"+s.web.phoneHostFor(r)+"/app/")
	}
	w.Header().Set("Content-Type", ct)
	w.Header().Set("Cache-Control", "no-cache")
	w.Write(b)
}

// absoluteJarURL makes a relative MIDlet-Jar-URL absolute (some phones
// resolve a relative one badly); everything else stays byte for byte.
func absoluteJarURL(jad []byte, base string) []byte {
	var out bytes.Buffer
	sc := bufio.NewScanner(bytes.NewReader(jad))
	for sc.Scan() {
		line := sc.Text()
		if k, v, ok := strings.Cut(line, ":"); ok && strings.TrimSpace(k) == "MIDlet-Jar-URL" {
			v = strings.TrimSpace(v)
			if !strings.Contains(v, "://") && appFileRE.MatchString(v) {
				line = "MIDlet-Jar-URL: " + base + v
			}
		}
		out.WriteString(line + "\n")
	}
	return out.Bytes()
}

// phonePage: https://PHONE_HOST/ in the phone's browser (the root is saved).
func (s *server) phonePage(w http.ResponseWriter, r *http.Request) {
	if s.web == nil || s.web.download == "" {
		writeS40(w, 404, []kv{{"status", "not_found"}}, "")
		return
	}
	host := html.EscapeString(s.web.phoneHostFor(r))
	writeHTML(w, 200, "AIKON", "<h1>AIKON</h1>"+
		"<p><a href=\"https://"+host+"/app/AIKON.jad\">AIKON'u indir / Download AIKON</a></p>"+
		"<p>Yükleme sorusuna Evet deyin. / Answer Yes when the phone asks to install.</p>")
}

// httpHandler: the plain-HTTP listener (landing page and ca.cer only).
func (s *server) httpHandler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /{$}", func(w http.ResponseWriter, r *http.Request) {
		host := html.EscapeString(s.web.phoneHostFor(r))
		fp := ""
		if s.web.caSHA1 != "" {
			fp = "<p>SHA-1: " + s.web.caSHA1 + "</p>"
		}
		writeHTML(w, 200, "AIKON", "<h1>AIKON</h1>"+
			"<p>1. <a href=\"http://"+host+"/ca.cer\">Sertifika / Certificate</a></p>"+
			"<p>Yetkili (authority) sertifikası olarak kaydedin; parmak izini sunucunun sahibinin "+
			"yayınladığıyla karşılaştırın. / Save it as an authority certificate after comparing the "+
			"fingerprint with the one the server's owner publishes.</p>"+fp+
			"<p>2. <a href=\"https://"+host+"/app/AIKON.jad\">AIKON</a></p>")
	})
	mux.HandleFunc("GET /ca.cer", func(w http.ResponseWriter, r *http.Request) {
		b, err := os.ReadFile(filepath.Join(s.web.download, "ca.cer"))
		if s.web.download == "" || err != nil {
			http.NotFound(w, r)
			return
		}
		w.Header().Set("Content-Type", "application/x-x509-ca-cert")
		w.Write(b)
	})
	inner := logged(mux)
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if host := hostOnly(r.Host); s.web.hosts[host] {
			http.Redirect(w, r, "https://"+host+r.URL.RequestURI(), http.StatusMovedPermanently)
			return
		}
		inner.ServeHTTP(w, r)
	})
}

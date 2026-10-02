package main

import (
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"io"
	"math/big"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

// selfSigned: a throwaway certificate for name (the web side's ACME stand-in).
func selfSigned(t *testing.T, name string) *tls.Certificate {
	t.Helper()
	k, _ := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	tpl := &x509.Certificate{SerialNumber: big.NewInt(1), Subject: pkix.Name{CommonName: name}, DNSNames: []string{name},
		NotBefore: time.Now().Add(-time.Hour), NotAfter: time.Now().Add(time.Hour)}
	der, err := x509.CreateCertificate(rand.Reader, tpl, tpl, &k.PublicKey, k)
	if err != nil {
		t.Fatal(err)
	}
	return &tls.Certificate{Certificate: [][]byte{der}, PrivateKey: k}
}

const testJAD = "MIDlet-Name: AIKON\nMIDlet-Jar-URL: AIKON.jar\nMIDlet-Jar-Size: 7\n"

// webEnv: a TLS listener with the phone certificate (CN=127.0.0.1), public
// hosts example.test / www.example.test and downloads for m.example.test.
func webEnv(t *testing.T) (*tenv, string) {
	t.Helper()
	dir := t.TempDir()
	cert, key := filepath.Join(dir, "c.pem"), filepath.Join(dir, "k.pem")
	if out, err := exec.Command("openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-sha1", "-days", "1",
		"-subj", "/CN=127.0.0.1", "-keyout", key, "-out", cert).CombinedOutput(); err != nil {
		t.Skipf("openssl not available: %v %s", err, out)
	}
	dl := filepath.Join(dir, "download")
	os.Mkdir(dl, 0o755)
	os.WriteFile(filepath.Join(dl, "ca.cer"), []byte("not really DER"), 0o644)
	os.WriteFile(filepath.Join(dl, "AIKON.jad"), []byte(testJAD), 0o644)
	os.WriteFile(filepath.Join(dl, "AIKON.jar"), []byte("PK-jar!"), 0o644)

	e := newEnv(t, 10)
	web, err := newWebSide(config{publicHosts: "example.test, WWW.example.test", phoneHost: "m.example.test",
		downloadDir: dl, acmeDir: filepath.Join(dir, "acme")})
	if err != nil {
		t.Fatal(err)
	}
	webCert := selfSigned(t, "example.test")
	web.cert = func(*tls.ClientHelloInfo) (*tls.Certificate, error) { return webCert, nil }
	e.srv.web = web
	cfg, err := phoneTLS(cert, key, web)
	if err != nil {
		t.Fatal(err)
	}
	ln, err := tls.Listen("tcp", "127.0.0.1:0", cfg)
	if err != nil {
		t.Fatal(err)
	}
	hs := &http.Server{Handler: e.srv.rootHandler(), TLSNextProto: map[string]func(*http.Server, *tls.Conn, http.Handler){}}
	go hs.Serve(ln)
	t.Cleanup(func() { hs.Close() })
	return e, ln.Addr().String()
}

// get: a request to addr with the given SNI ("" = none), Host and TLS versions.
func get(t *testing.T, addr, sni, host, method, path string, maxVer uint16) (*http.Response, string, *x509.Certificate, error) {
	t.Helper()
	tr := &http.Transport{
		TLSClientConfig: &tls.Config{ServerName: sni, InsecureSkipVerify: true, // test only
			MinVersion: tls.VersionTLS10, MaxVersion: maxVer},
		DialContext: func(ctx context.Context, network, _ string) (net.Conn, error) {
			return (&net.Dialer{}).DialContext(ctx, network, addr)
		},
	}
	defer tr.CloseIdleConnections()
	urlHost := sni // the client sends the URL's host as SNI unless it is an IP
	if urlHost == "" {
		urlHost = "127.0.0.1"
	}
	req, _ := http.NewRequest(method, "https://"+urlHost+path, strings.NewReader(""))
	req.Host = host
	res, err := (&http.Client{Transport: tr, CheckRedirect: func(*http.Request, []*http.Request) error {
		return http.ErrUseLastResponse
	}}).Do(req)
	if err != nil {
		return nil, "", nil, err
	}
	defer res.Body.Close()
	b, _ := io.ReadAll(res.Body)
	return res, string(b), res.TLS.PeerCertificates[0], nil
}

func TestWebSide(t *testing.T) {
	_, addr := webEnv(t)

	// the Nokia: no SNI, TLS 1.0, the IP as host -> private certificate, phone API
	res, body, cert, err := get(t, addr, "", "127.0.0.1", "GET", "/health", tls.VersionTLS10)
	if err != nil || res.StatusCode != 200 || cert.Subject.CommonName != "127.0.0.1" || !strings.HasPrefix(body, "S40/1") {
		t.Fatalf("phone: %v %v %q", err, cert, body)
	}
	// the phone's browser on the phone host -> private certificate, download page and app
	res, body, cert, err = get(t, addr, "m.example.test", "m.example.test", "GET", "/", tls.VersionTLS12)
	if err != nil || cert.Subject.CommonName != "127.0.0.1" || !strings.Contains(body, "https://m.example.test/app/AIKON.jad") {
		t.Fatalf("phone page: %v %q", err, body)
	}
	res, body, _, _ = get(t, addr, "m.example.test", "m.example.test", "GET", "/app/AIKON.jad", tls.VersionTLS12)
	if res.Header.Get("Content-Type") != "text/vnd.sun.j2me.app-descriptor" ||
		!strings.Contains(body, "MIDlet-Jar-URL: https://m.example.test/app/AIKON.jar\n") || !strings.Contains(body, "MIDlet-Jar-Size: 7\n") {
		t.Fatalf("jad: %q %q", res.Header.Get("Content-Type"), body)
	}
	res, body, _, _ = get(t, addr, "m.example.test", "m.example.test", "GET", "/app/AIKON.jar", tls.VersionTLS12)
	if res.Header.Get("Content-Type") != "application/java-archive" || body != "PK-jar!" {
		t.Fatalf("jar: %q", body)
	}
	for _, p := range []string{"/app/ca.cer", "/app/..%2fx.jar", "/app/x.jar"} {
		if res, _, _, _ := get(t, addr, "m.example.test", "m.example.test", "GET", p, tls.VersionTLS12); res.StatusCode != 404 {
			t.Fatalf("%s: %d", p, res.StatusCode)
		}
	}

	// a browser on a public host -> the public certificate and the web mux
	for _, h := range []string{"example.test", "www.example.test"} {
		res, body, cert, err = get(t, addr, h, h, "GET", "/", tls.VersionTLS13)
		if err != nil || cert.Subject.CommonName != "example.test" || !strings.Contains(body, "<h1>AIKON</h1>") ||
			res.Header.Get("Strict-Transport-Security") == "" || res.Header.Get("Content-Security-Policy") == "" {
			t.Fatalf("web %s: %v %v %q", h, err, cert, body)
		}
	}
	if res, body, _, _ := get(t, addr, "example.test", "example.test", "POST", "/v1/chat", tls.VersionTLS13); res.StatusCode != 404 ||
		strings.HasPrefix(body, "S40/1") {
		t.Fatalf("phone API on the web side: %d %q", res.StatusCode, body)
	}
	if _, _, _, err := get(t, addr, "example.test", "example.test", "GET", "/", tls.VersionTLS11); err == nil {
		t.Fatal("TLS 1.1 accepted for a public host")
	}
	// the Host must match the side the handshake chose
	if res, _, _, _ := get(t, addr, "example.test", "m.example.test", "GET", "/health", tls.VersionTLS13); res.StatusCode != 421 {
		t.Fatalf("public SNI, phone host: %d", res.StatusCode)
	}
	if res, _, _, _ := get(t, addr, "", "example.test", "GET", "/", tls.VersionTLS12); res.StatusCode != 421 {
		t.Fatalf("no SNI, public host: %d", res.StatusCode)
	}
}

func TestPlainHTTPLanding(t *testing.T) {
	e, _ := webEnv(t)
	h := e.srv.httpHandler()
	do := func(host, path string) *httptest.ResponseRecorder {
		r := httptest.NewRequest("GET", "http://"+host+path, nil)
		w := httptest.NewRecorder()
		h.ServeHTTP(w, r)
		return w
	}
	w := do("m.example.test", "/")
	if w.Code != 200 || !strings.Contains(w.Body.String(), "http://m.example.test/ca.cer") ||
		!strings.Contains(w.Body.String(), "https://m.example.test/app/AIKON.jad") || !strings.Contains(w.Body.String(), "SHA-1: ") {
		t.Fatalf("landing: %q", w.Body.String())
	}
	if w := do("m.example.test", "/ca.cer"); w.Code != 200 || w.Header().Get("Content-Type") != "application/x-x509-ca-cert" {
		t.Fatalf("ca: %d", w.Code)
	}
	for _, p := range []string{"/v1/chat", "/health", "/app/AIKON.jar"} {
		if w := do("m.example.test", p); w.Code != 404 {
			t.Fatalf("plain HTTP %s: %d", p, w.Code)
		}
	}
	if w := do("www.example.test", "/x?y=1"); w.Code != 301 || w.Header().Get("Location") != "https://www.example.test/x?y=1" {
		t.Fatalf("redirect: %d %q", w.Code, w.Header().Get("Location"))
	}
}

func TestWebSideConfig(t *testing.T) {
	for _, bad := range []string{"1.2.3.4", "local", "exa mple.com"} {
		if _, err := newWebSide(config{publicHosts: bad}); err == nil {
			t.Fatalf("accepted %q", bad)
		}
	}
	w, err := newWebSide(config{})
	if err != nil || w.public("example.test") {
		t.Fatal("no public hosts by default")
	}
	jad := string(absoluteJarURL([]byte("A: 1\nMIDlet-Jar-URL: https://x/y.jar\n"), "https://m/app/"))
	if !strings.Contains(jad, "MIDlet-Jar-URL: https://x/y.jar") {
		t.Fatalf("absolute URL changed: %q", jad)
	}
}

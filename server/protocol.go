package main

// "S40/1" wire format shared with the phone (same as the retired Worker):
//
//	S40/1\n
//	key: value\n   (zero or more)
//	\n
//	free text (UTF-8)
//
// Status travels in the body and in the HTTP code, because operator proxies
// may rewrite non-200 responses. Every response is Cache-Control: no-store.

import (
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"net/http"
	"regexp"
	"strings"
	"unicode/utf8"
)

const magic = "S40/1"

var (
	errProtocol = errors.New("bad S40 message")
	keyRE       = regexp.MustCompile(`^[a-z0-9-]{1,32}$`)
	idRE        = regexp.MustCompile(`^[A-Za-z0-9-]{8,40}$`)
	convRE      = regexp.MustCompile(`^[0-9a-f]{16}$`)
)

type s40Msg struct {
	fields map[string]string
	text   string
}

func (m s40Msg) get(k string) string { return m.fields[k] }

func parseS40(body string) (s40Msg, error) {
	norm := strings.ReplaceAll(body, "\r\n", "\n")
	first, rest, _ := strings.Cut(norm, "\n")
	if strings.TrimSpace(first) != magic {
		return s40Msg{}, errProtocol
	}
	m := s40Msg{fields: map[string]string{}}
	for {
		line, after, found := strings.Cut(rest, "\n")
		rest = after
		if line == "" {
			if !found {
				rest = ""
			}
			break
		}
		k, v, ok := strings.Cut(line, ":")
		k = strings.ToLower(strings.TrimSpace(k))
		if !ok || !keyRE.MatchString(k) {
			return s40Msg{}, errProtocol
		}
		m.fields[k] = strings.TrimSpace(v)
		if !found {
			rest = ""
			break
		}
	}
	m.text = rest
	return m, nil
}

// kv keeps header order stable in responses.
type kv struct {
	k string
	v any
}

func formatS40(fields []kv, text string) string {
	var b strings.Builder
	b.WriteString(magic + "\n")
	for _, f := range fields {
		if f.v == nil {
			continue
		}
		var s string
		switch v := f.v.(type) {
		case bool:
			if v {
				s = "1"
			} else {
				s = "0"
			}
		default:
			s = fmt.Sprint(v)
		}
		s = strings.NewReplacer("\r", " ", "\n", " ").Replace(s)
		b.WriteString(f.k + ": " + s + "\n")
	}
	b.WriteString("\n")
	b.WriteString(text)
	return b.String()
}

func writeS40(w http.ResponseWriter, code int, fields []kv, text string) {
	body := formatS40(fields, text)
	h := w.Header()
	h.Set("Content-Type", "text/plain; charset=utf-8")
	h.Set("Cache-Control", "no-store, private")
	h.Set("X-Content-Type-Options", "nosniff")
	h.Set("Content-Length", fmt.Sprint(len(body)))
	w.WriteHeader(code)
	io.WriteString(w, body)
}

// readLimited returns nil, false if the body exceeds max bytes.
func readLimited(r *http.Request, max int64) ([]byte, bool) {
	if r.ContentLength > max {
		return nil, false
	}
	b, err := io.ReadAll(io.LimitReader(r.Body, max+1))
	if err != nil || int64(len(b)) > max {
		return nil, false
	}
	return b, true
}

// decodeUTF8 is strict: invalid sequences are rejected, not replaced.
func decodeUTF8(b []byte) (string, bool) {
	if !utf8.Valid(b) {
		return "", false
	}
	return strings.TrimPrefix(string(b), "\ufeff"), true
}

func hexPrefix(b []byte, max int) string {
	if len(b) > max {
		b = b[:max]
	}
	return hex.EncodeToString(b)
}

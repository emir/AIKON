package main

import (
	"crypto/sha256"
	"embed"
	"encoding/hex"
	"fmt"
	"net/http"
	"path"
	"strconv"
	"strings"
	"unicode/utf16"
)

// UI languages for the phone (phone 0.18.0+): the app carries only English
// and downloads the others from here, GET /v1/lang?c=xx&p=N. lang/xx.txt is
// a copy of app/lang/xx.txt (make -C app langs; TestLangFilesMatchApp):
// one line per English text, the text, a TAB, the translation, escaped
// (\n, \t, \\). The phone gets "hash TAB translation" lines: the Java
// String.hashCode of the English text as 8 hex digits and the translation
// still escaped, so any phone build can use any server's files (a text the
// server lacks stays English). Public data: no pairing needed, nothing
// logged but the path.

//go:embed lang/*.txt
var langFS embed.FS

// The phone keeps at most 8192 bytes of a response (Net.MAX_BODY); a part's
// text stays well below that with the header.
const langPartBytes = 6000

type langPack struct {
	version string // first 8 hex digits of SHA-256 of all parts
	parts   []string
}

var langs = loadLangs()

func loadLangs() map[string]*langPack {
	files, err := langFS.ReadDir("lang")
	if err != nil {
		panic(err)
	}
	out := map[string]*langPack{}
	for _, f := range files {
		code := strings.TrimSuffix(f.Name(), ".txt")
		raw, err := langFS.ReadFile(path.Join("lang", f.Name()))
		if err != nil {
			panic(err)
		}
		p, err := buildLangPack(string(raw))
		if err != nil {
			panic(fmt.Sprintf("lang/%s: %v", f.Name(), err))
		}
		out[code] = p
	}
	return out
}

func buildLangPack(src string) (*langPack, error) {
	var parts []string
	var cur strings.Builder
	seen := map[uint32]string{}
	for n, line := range strings.Split(src, "\n") {
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		k, v, ok := strings.Cut(line, "\t")
		if !ok || v == "" {
			return nil, fmt.Errorf("line %d: not 'English TAB translation'", n+1)
		}
		key := langUnescape(k)
		h := javaHash(key)
		if other, dup := seen[h]; dup {
			return nil, fmt.Errorf("line %d: %q has the hash of %q", n+1, key, other)
		}
		seen[h] = key
		l := fmt.Sprintf("%08x\t%s\n", h, v)
		if cur.Len()+len(l) > langPartBytes && cur.Len() > 0 {
			parts = append(parts, cur.String())
			cur.Reset()
		}
		cur.WriteString(l)
	}
	if cur.Len() > 0 {
		parts = append(parts, cur.String())
	}
	if len(parts) == 0 {
		return nil, fmt.Errorf("no translations")
	}
	sum := sha256.Sum256([]byte(strings.Join(parts, "")))
	return &langPack{version: hex.EncodeToString(sum[:4]), parts: parts}, nil
}

// javaHash is Java's String.hashCode: over UTF-16 code units, 32-bit wrap.
func javaHash(s string) uint32 {
	var h uint32
	for _, u := range utf16.Encode([]rune(s)) {
		h = 31*h + uint32(u)
	}
	return h
}

// langUnescape undoes the files' escapes: \n, \t, and \x for any other x.
func langUnescape(s string) string {
	if !strings.Contains(s, `\`) {
		return s
	}
	var b strings.Builder
	for i := 0; i < len(s); i++ {
		if s[i] == '\\' && i+1 < len(s) {
			i++
			switch s[i] {
			case 'n':
				b.WriteByte('\n')
			case 't':
				b.WriteByte('\t')
			default:
				b.WriteByte(s[i])
			}
			continue
		}
		b.WriteByte(s[i])
	}
	return b.String()
}

func (s *server) langHandler(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	p, ok := langs[q.Get("c")]
	if !ok {
		writeS40(w, 404, []kv{{"status", "unknown_language"}}, "")
		return
	}
	part, err := strconv.Atoi(q.Get("p"))
	if err != nil || part < 0 || part >= len(p.parts) {
		writeS40(w, 400, []kv{{"status", "bad_part"}, {"parts", len(p.parts)}}, "")
		return
	}
	writeS40(w, 200, []kv{{"status", "ok"}, {"lang", q.Get("c")}, {"version", p.version},
		{"part", part}, {"parts", len(p.parts)}}, p.parts[part])
}

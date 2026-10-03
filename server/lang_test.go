package main

import (
	"bytes"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
)

func TestJavaHash(t *testing.T) {
	// values from Java's String.hashCode, as unsigned
	for s, want := range map[string]uint32{"Save": 2569629, "Ünlü ğ 😀": 253220034, "": 0} {
		if got := javaHash(s); got != want {
			t.Errorf("javaHash(%q) = %d, want %d", s, got, want)
		}
	}
}

func TestBuildLangPack(t *testing.T) {
	src := "# comment\nSave\tKaydet\n\nLine\\none\tSatır\\nbir\n"
	p, err := buildLangPack(src)
	if err != nil {
		t.Fatal(err)
	}
	want := "0027359d\tKaydet\n" + strings.Replace(p.parts[0], "0027359d\tKaydet\n", "", 1)
	if len(p.parts) != 1 || p.parts[0] != want || !strings.Contains(p.parts[0], "\tSatır\\nbir\n") {
		t.Fatalf("parts = %q", p.parts)
	}
	h := javaHash("Line\none")
	if !strings.HasPrefix(strings.Split(p.parts[0], "\n")[1], strconv.FormatUint(uint64(h), 16)) {
		t.Errorf("escaped key not hashed unescaped: %q", p.parts[0])
	}
	if _, err := buildLangPack("Save\n"); err == nil {
		t.Error("a line without TAB passed")
	}
	if _, err := buildLangPack("# only a comment\n"); err == nil {
		t.Error("an empty file passed")
	}
}

func TestLangPartsFitThePhone(t *testing.T) {
	if len(langs) == 0 {
		t.Fatal("no languages embedded")
	}
	for code, p := range langs {
		for i, part := range p.parts {
			if len(part) > langPartBytes {
				t.Errorf("%s part %d: %d bytes", code, i, len(part))
			}
			if !strings.HasSuffix(part, "\n") {
				t.Errorf("%s part %d does not end with a whole line", code, i)
			}
		}
	}
}

// server/lang is a copy of app/lang (make -C app langs); in a checkout both exist.
func TestLangFilesMatchApp(t *testing.T) {
	app, _ := filepath.Glob("../app/lang/*.txt")
	if len(app) == 0 {
		t.Skip("no ../app/lang here (server-only build)")
	}
	ours, _ := filepath.Glob("lang/*.txt")
	if len(ours) != len(app) {
		t.Fatalf("server/lang has %d files, app/lang %d: run make -C app langs", len(ours), len(app))
	}
	for _, a := range app {
		x, _ := os.ReadFile(a)
		y, err := os.ReadFile(filepath.Join("lang", filepath.Base(a)))
		if err != nil || !bytes.Equal(x, y) {
			t.Errorf("server/lang/%s differs from app/lang: run make -C app langs", filepath.Base(a))
		}
	}
}

func TestLangHandler(t *testing.T) {
	e := newEnv(t, 100)
	p := langs["tr"]
	var all strings.Builder
	for i := range p.parts {
		r := e.do("GET", "/v1/lang?c=tr&p="+strconv.Itoa(i), "", "")
		if r.code != 200 || r.msg.get("status") != "ok" || r.msg.get("version") != p.version ||
			r.msg.get("parts") != strconv.Itoa(len(p.parts)) || r.msg.get("part") != strconv.Itoa(i) {
			t.Fatalf("part %d: %d %q", i, r.code, r.raw)
		}
		if len(r.raw) > 8192 {
			t.Errorf("part %d: %d bytes, the phone keeps 8192", i, len(r.raw))
		}
		all.WriteString(r.msg.text)
	}
	if !strings.Contains(all.String(), "\tKaydet\n") {
		t.Error("Turkish 'Save' missing")
	}
	if r := e.do("GET", "/v1/lang?c=xx&p=0", "", ""); r.code != 404 || r.msg.get("status") != "unknown_language" {
		t.Errorf("unknown language: %d %q", r.code, r.raw)
	}
	if r := e.do("GET", "/v1/lang?c=tr&p=99", "", ""); r.code != 400 || r.msg.get("status") != "bad_part" {
		t.Errorf("bad part: %d %q", r.code, r.raw)
	}
	if r := e.do("POST", "/v1/lang?c=tr&p=0", "", ""); r.code != 405 {
		t.Errorf("POST: %d", r.code)
	}
}

package main

// Makes a model reply readable on a 240x320 S40 screen with the phone's
// fonts: plain text, no Markdown, no emoji, nothing outside the BMP.
// Turkish letters are kept. Port of the Worker's sanitize.ts.

import (
	"regexp"
	"strings"
	"unicode/utf8"

	"golang.org/x/text/unicode/norm"
)

var replacements = []struct {
	re *regexp.Regexp
	to string
}{
	{regexp.MustCompile(`\r\n?`), "\n"},
	{regexp.MustCompile(`[\x{2018}\x{2019}\x{201A}\x{2032}]`), "'"},
	{regexp.MustCompile(`[\x{201C}\x{201D}\x{201E}\x{2033}]`), `"`},
	{regexp.MustCompile(`[\x{2013}\x{2014}\x{2212}]`), "-"},
	{regexp.MustCompile(`\x{2026}`), "..."},
	{regexp.MustCompile(`[\x{00A0}\x{2007}\x{202F}\x{2009}\x{200A}]`), " "},
	{regexp.MustCompile(`[\x{2022}\x{25CF}\x{25AA}\x{2023}]`), "-"},
	{regexp.MustCompile(`[\x{200B}-\x{200D}\x{2060}\x{FEFF}]`), ""},
	{regexp.MustCompile("(?m)^```[^\n]*\n?"), ""},
	{regexp.MustCompile("`([^`\n]*)`"), "$1"},
	{regexp.MustCompile(`\*\*([^*\n]+)\*\*`), "$1"},
	{regexp.MustCompile(`__([^_\n]+)__`), "$1"},
	{regexp.MustCompile(`(?m)^#{1,6}\s+`), ""},
	{regexp.MustCompile(`(?m)^(\s*)[*+]\s+`), "$1- "},
	{regexp.MustCompile(`(?m)^\s*\|?(\s*:?-{3,}:?\s*\|)+\s*:?-*:?\s*$`), ""},
	{regexp.MustCompile(`[\x{2600}-\x{27BF}\x{FE00}-\x{FE0F}]`), ""},
	{regexp.MustCompile(`[\x00-\x08\x0B-\x1F\x7F-\x{9F}]`), ""},
	{regexp.MustCompile(`\t`), " "},
	{regexp.MustCompile(`[ ]{2,}`), " "},
	{regexp.MustCompile(` +\n`), "\n"},
	{regexp.MustCompile(`\n{3,}`), "\n\n"},
}

func sanitizeReply(in string) string {
	s := norm.NFC.String(in)
	// drop everything outside the Basic Multilingual Plane (emoji etc.)
	s = strings.Map(func(r rune) rune {
		if r > 0xFFFF {
			return -1
		}
		return r
	}, s)
	for _, r := range replacements {
		s = r.re.ReplaceAllString(s, r.to)
	}
	return strings.TrimSpace(s)
}

// limitChars cuts to max characters at a word boundary when possible.
func limitChars(s string, max int) (string, bool) {
	if utf8.RuneCountInString(s) <= max {
		return s, false
	}
	r := []rune(s)[:max]
	cut := string(r)
	if i := strings.LastIndexAny(cut, " \n"); i > len(cut)*8/10 {
		cut = cut[:i]
	}
	return strings.TrimRight(cut, " \n") + " ...", true
}

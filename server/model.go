package main

// What every provider shares: the conversation turns, the reply, the
// system prompt for a 240x320 screen, error classes and the mock model.
// Providers (anthropic.go, responses.go) never retry a call: a retried
// timeout could be a second paid call.

import (
	"context"
	"fmt"
	"net/url"
	"strings"
	"time"
)

const upstreamTimeout = 60 * time.Second

const basePrompt = `You are %s, talking to the user through "Claude S40", an unofficial client running on a Nokia 6300 (Series 40) phone with a 240x320 screen and a numeric keypad.
Reply in the language the user writes in.
The user may attach a photo taken with the phone's 2-megapixel camera; it can be small, dark or blurry. Describe what you can actually see and say so when something is not readable.
Keep answers short and easy to read on a small screen: normally 2-6 sentences, at most about 120 words, unless the user explicitly asks for more detail.
Use plain text only: no Markdown, no headings, no tables, no code blocks, no emoji. If a list helps, put each item on its own line starting with "- ".
When the user asks to shorten, expand or rephrase, apply it to your previous answer in this conversation.`

// searchPrompt is added when the web search tool is offered. The phone's own
// browser cannot open today's web (TLS 1.0 only), so this is the user's way
// to current information.
const searchPrompt = `
You can search the web. Use it for anything current or factual that may have changed: news, weather, exchange rates, prices, sports results, opening hours, schedules, recent events. Do not search for things you already know well.
After searching, answer directly with the facts first. Do not describe your searching. Do not paste URLs.`

// calendarPrompt is added when the phone can write to its calendar and
// to-do list (0.7+ phones send "calendar: 1"). The phone turns such a last
// line into a prepared calendar entry; nothing is written without the user.
const calendarPrompt = `
The user's phone can add entries to its calendar and to-do list. Only if the user asks you to add something to the calendar, to remind them of something, or to add a to-do: answer in one short sentence and put the entry on the last line, exactly in one of these forms, with nothing else on that line:
EVENT: YYYY-MM-DD HH:MM | short title
TODO: YYYY-MM-DD | short title
Use the phone's local date and time for words like "today", "tomorrow" or "on Monday". For an event without a time use 09:00. At most one entry per answer, title at most 60 characters. Never use these forms otherwise.`

// systemPrompt: name is how the model is introduced ("Claude", "Grok"...).
func systemPrompt(name string, o replyOpts, now time.Time) string {
	p := fmt.Sprintf(basePrompt, name) + "\nToday's date (UTC) is " + now.UTC().Format("2006-01-02") + "."
	if o.search {
		p += searchPrompt
	}
	if o.calendar {
		p += calendarPrompt
		if !o.localTime.IsZero() {
			p += "\nThe phone's local date and time: " + o.localTime.Format("Monday 2006-01-02 15:04") + "."
		}
	}
	if o.instructions != "" {
		p += "\n\nThe user wrote these notes about themselves and how they like answers. Follow them unless they conflict with the rules above:\n<user_notes>\n" +
			o.instructions + "\n</user_notes>"
	}
	return p
}

type turn struct {
	role    string // "user" | "assistant"
	content string
	imageID string // a photo sent with this user message ("" if none)
	image   []byte // its JPEG, when it is sent to Claude again (newest contextImages)
}

type reply struct {
	text         string
	cutOff       bool
	refused      bool
	inputTokens  int64
	outputTokens int64
	searches     int64
	sources      []string // host names of cited web pages, in order, no duplicates
	mock         bool
}

// replyOpts: per-request options decided by the chat service.
type replyOpts struct {
	search       bool
	calendar     bool      // the phone can add calendar / to-do entries
	localTime    time.Time // the phone's clock (calendar only); zero if unknown
	instructions string    // the user's own notes for Claude (Settings on the phone)
	imageID      string    // a photo with this message (/v1/image), "" if none
	image        []byte    // its JPEG (set by the chat service)
}

// upstreamError: kind "definite" or "uncertain"; code is the status sent to the phone.
type upstreamError struct {
	kind string
	code string
}

func (e *upstreamError) Error() string { return e.kind + ":" + e.code }

type model interface {
	reply(ctx context.Context, history []turn, message string, o replyOpts) (reply, error)
}

// hostOf returns the host name of an http(s) URL without "www.", or "".
func hostOf(raw string) string {
	u, err := url.Parse(raw)
	if err != nil || (u.Scheme != "https" && u.Scheme != "http") {
		return ""
	}
	return strings.TrimPrefix(strings.ToLower(u.Hostname()), "www.")
}

func truncate(s string, n int) string {
	if len(s) > n {
		return s[:n]
	}
	return s
}

// ------------------------------------------------------------ mock

// mockModel never touches the network. Every reply starts with "[Test mode]"
// so it can never be mistaken for a real model; label is the model it
// stands in for ("Claude" when empty). Control words (tests):
// [[mock:uncertain]] [[mock:error]] [[mock:overloaded]] [[mock:billing]]
// [[mock:long]] [[mock:cut]] [[mock:slow]] [[mock:search]] [[mock:event]]
type mockModel struct{ label string }

func (m mockModel) reply(ctx context.Context, history []turn, message string, o replyOpts) (reply, error) {
	switch {
	case strings.Contains(message, "[[mock:uncertain]]"):
		return reply{}, &upstreamError{"uncertain", "uncertain"}
	case strings.Contains(message, "[[mock:error]]"):
		return reply{}, &upstreamError{"definite", "upstream_error"}
	case strings.Contains(message, "[[mock:overloaded]]"):
		return reply{}, &upstreamError{"definite", "overloaded"}
	case strings.Contains(message, "[[mock:billing]]"):
		return reply{}, &upstreamError{"definite", "billing"}
	case strings.Contains(message, "[[mock:slow]]"):
		select {
		case <-time.After(400 * time.Millisecond):
		case <-ctx.Done():
		}
	}
	prior := 0
	for _, t := range history {
		if t.role == "user" {
			prior++
		}
	}
	label := m.label
	if label == "" {
		label = "Claude"
	}
	text := fmt.Sprintf("[Test mode] This is not a real %s reply.\nYour message has %d characters. Earlier messages in this chat: %d.\nI received: \"%s\"",
		label, len([]rune(message)), prior, truncate(message, 200))
	if strings.Contains(message, "[[mock:long]]") {
		text += "\n" + strings.Repeat("Long test line çğıİöşü. ", 200)
	}
	if strings.Contains(message, "[[mock:huge]]") {
		text += "\n" + strings.Repeat("Huge test line çğıİöşü. ", 500)
	}
	r := reply{cutOff: strings.Contains(message, "[[mock:cut]]"), mock: true}
	if strings.Contains(message, "[[mock:search]]") {
		if o.search {
			r.searches, r.sources = 2, []string{"example.com", "example.org"}
			text += "\nWeb search: on (simulated, 2 searches)."
		} else {
			text += "\nWeb search: off."
		}
	}
	if o.instructions != "" {
		text += fmt.Sprintf("\nYour notes for Claude: %d characters.", len([]rune(o.instructions)))
	}
	if len(o.image) > 0 {
		text += fmt.Sprintf("\nPhoto: attached (%d bytes).", len(o.image))
	}
	shown := 0
	for _, t := range history {
		if len(t.image) > 0 {
			shown++
		}
	}
	if shown > 0 {
		text += fmt.Sprintf("\nEarlier photos shown again: %d.", shown)
	}
	if strings.Contains(message, "[[mock:event]]") && o.calendar {
		day := time.Now()
		if !o.localTime.IsZero() {
			day = o.localTime
		}
		text += "\nEVENT: " + day.AddDate(0, 0, 1).Format("2006-01-02") + " 15:00 | Test mode event"
	}
	r.text = text
	return r, nil
}

// ------------------------------------------------------------ catalog

// modelEntry: one model the server offers. id is the provider's model id
// (also what the phone sends), label the short name shown on the phone and
// used in the system prompt.
type modelEntry struct {
	id, label, provider string
	search              bool // the provider has a web search tool
	m                   model
}

// catalog: the configured models; the first one is the default (older
// phones and new conversations without a choice).
type catalog struct{ list []modelEntry }

func (c *catalog) def() *modelEntry { return &c.list[0] }

func (c *catalog) get(id string) (*modelEntry, bool) {
	for i := range c.list {
		if c.list[i].id == id {
			return &c.list[i], true
		}
	}
	return nil, false
}

// singleModel: a catalog of one model (tests, and the plain Claude setup).
func singleModel(id, label string, m model) *catalog {
	return &catalog{[]modelEntry{{id: id, label: label, provider: "anthropic", search: true, m: m}}}
}

// modelSpec: one item of MODELS, "provider:model-id" or "provider:model-id=Label".
type modelSpec struct{ provider, id, label string }

var defaultLabels = map[string]string{"anthropic": "Claude", "openai": "ChatGPT", "xai": "Grok"}

const maxLabel = 20

func parseModels(s string) ([]modelSpec, error) {
	var out []modelSpec
	seen := map[string]bool{}
	for _, item := range strings.Split(s, ",") {
		item = strings.TrimSpace(item)
		if item == "" {
			continue
		}
		prov, rest, ok := strings.Cut(item, ":")
		id, label, _ := strings.Cut(rest, "=")
		id, label = strings.TrimSpace(id), strings.TrimSpace(label)
		if _, known := defaultLabels[prov]; !ok || !known || id == "" {
			return nil, fmt.Errorf("MODELS: %q is not provider:model-id[=Label] with provider anthropic, openai or xai", item)
		}
		if strings.ContainsAny(id, " \t\r\n") || len(id) > 64 {
			return nil, fmt.Errorf("MODELS: bad model id %q", id)
		}
		if label == "" {
			label = defaultLabels[prov]
		}
		if len([]rune(label)) > maxLabel || strings.ContainsAny(label, "\t\r\n") {
			return nil, fmt.Errorf("MODELS: label %q (at most %d characters, one line)", label, maxLabel)
		}
		if seen[id] {
			return nil, fmt.Errorf("MODELS: %q twice", id)
		}
		seen[id] = true
		out = append(out, modelSpec{prov, id, label})
	}
	if len(out) == 0 {
		return nil, fmt.Errorf("MODELS: no model")
	}
	return out, nil
}

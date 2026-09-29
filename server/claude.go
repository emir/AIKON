package main

// Claude calls through the official Go SDK. maxRetries is 0: a retried
// timeout could be a second paid call. Errors are classified as definite
// (Anthropic answered with an error; no reply was produced) or uncertain
// (the call may or may not have been processed).

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"net/url"
	"strings"
	"time"

	"github.com/anthropics/anthropic-sdk-go"
	"github.com/anthropics/anthropic-sdk-go/option"
	"github.com/anthropics/anthropic-sdk-go/shared/constant"
)

const (
	maxTokens       = 2048
	upstreamTimeout = 60 * time.Second
	// a turn with server tools can pause (stop_reason "pause_turn") after the
	// server-side loop limit; it is continued at most this many times
	maxContinuations = 1
)

const basePrompt = `You are Claude, talking to the user through "Claude S40", an unofficial client running on a Nokia 6300 (Series 40) phone with a 240x320 screen and a numeric keypad.
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

func systemPrompt(o replyOpts, now time.Time) string {
	p := basePrompt + "\nToday's date (UTC) is " + now.UTC().Format("2006-01-02") + "."
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

// ------------------------------------------------------------ Anthropic

type claudeModel struct {
	client    anthropic.Client
	model     string
	effort    string
	fallbacks bool
	search    searchConfig
	now       func() time.Time
}

// searchConfig: Anthropic's server-side web search tool. Every search is
// billed per use and its results count as input tokens.
type searchConfig struct {
	maxUses  int64
	country  string // ISO 3166-1 alpha-2, optional
	city     string // optional
	timezone string // IANA, optional
}

func newClaudeModel(apiKey, modelID, effort string, fallbacks bool, opts ...option.RequestOption) *claudeModel {
	base := []option.RequestOption{
		option.WithAPIKey(apiKey),
		option.WithMaxRetries(0),
		option.WithRequestTimeout(upstreamTimeout),
	}
	return &claudeModel{
		client:    anthropic.NewClient(append(base, opts...)...),
		model:     modelID,
		effort:    effort,
		fallbacks: fallbacks,
		search:    searchConfig{maxUses: 3},
		now:       time.Now,
	}
}

func (m *claudeModel) reply(ctx context.Context, history []turn, message string, o replyOpts) (reply, error) {
	msgs := make([]anthropic.BetaMessageParam, 0, len(history)+2)
	for _, t := range history {
		role := anthropic.BetaMessageParamRoleUser
		if t.role == "assistant" {
			role = anthropic.BetaMessageParamRoleAssistant
		}
		msgs = append(msgs, anthropic.BetaMessageParam{Role: role, Content: userContent(t.content, t.image, t.imageID)})
	}
	msgs = append(msgs, anthropic.BetaMessageParam{Role: anthropic.BetaMessageParamRoleUser,
		Content: userContent(message, o.image, o.imageID)})

	params := anthropic.BetaMessageNewParams{
		Model:     anthropic.Model(m.model),
		MaxTokens: maxTokens,
		System:    []anthropic.BetaTextBlockParam{{Text: systemPrompt(o, m.now())}},
		Messages:  msgs,
	}
	if m.effort != "" {
		params.OutputConfig = anthropic.BetaOutputConfigParam{Effort: anthropic.BetaOutputConfigEffort(m.effort)}
	}
	if m.fallbacks {
		params.Betas = []anthropic.AnthropicBeta{anthropic.AnthropicBetaServerSideFallback2026_07_01}
		params.Fallbacks = anthropic.BetaFallbacksParamUnion{OfDefault: constant.ValueOf[constant.Default]()}
	}
	if o.search {
		ws := &anthropic.BetaWebSearchTool20260209Param{MaxUses: anthropic.Int(max(1, m.search.maxUses))}
		if m.search.country != "" || m.search.city != "" || m.search.timezone != "" {
			loc := anthropic.BetaUserLocationParam{}
			if m.search.country != "" {
				loc.Country = anthropic.String(m.search.country)
			}
			if m.search.city != "" {
				loc.City = anthropic.String(m.search.city)
			}
			if m.search.timezone != "" {
				loc.Timezone = anthropic.String(m.search.timezone)
			}
			ws.UserLocation = loc
		}
		params.Tools = []anthropic.BetaToolUnionParam{{OfWebSearchTool20260209: ws}}
	}

	var out reply
	var text strings.Builder
	seen := map[string]bool{}
	for i := 0; ; i++ {
		res, err := m.client.Beta.Messages.New(ctx, params)
		if err != nil {
			if i == 0 {
				return reply{}, classify(err)
			}
			// the first part was answered (and billed); report what we have
			// as cut off instead of turning the whole exchange into an error
			classify(err)
			out.cutOff = true
			break
		}
		out.inputTokens += res.Usage.InputTokens + res.Usage.CacheReadInputTokens + res.Usage.CacheCreationInputTokens
		out.outputTokens += res.Usage.OutputTokens
		out.searches += res.Usage.ServerToolUse.WebSearchRequests
		for _, b := range res.Content {
			switch b.Type {
			case "text":
				text.WriteString(b.Text)
				for _, c := range b.Citations {
					if h := hostOf(c.URL); h != "" && !seen[h] {
						seen[h] = true
						out.sources = append(out.sources, h)
					}
				}
			case "server_tool_use", "web_search_tool_result", "web_fetch_tool_result":
				// text before or between searches is narration ("Let me
				// look that up"); keep only the answer after the last one
				text.Reset()
			}
		}
		if res.StopReason == anthropic.BetaStopReasonPauseTurn && i < maxContinuations {
			params.Messages = append(params.Messages, res.ToParam())
			continue
		}
		out.cutOff = out.cutOff || res.StopReason == anthropic.BetaStopReasonMaxTokens ||
			res.StopReason == anthropic.BetaStopReasonPauseTurn
		out.refused = res.StopReason == anthropic.BetaStopReasonRefusal
		break
	}
	out.text = text.String()
	return out, nil
}

// userContent: the photo (if it is still sent) before the text, as the
// Messages API recommends; an older photo that is no longer sent is named
// in the text so the conversation still reads right.
func userContent(text string, img []byte, imageID string) []anthropic.BetaContentBlockParamUnion {
	switch {
	case len(img) > 0:
		return []anthropic.BetaContentBlockParamUnion{
			anthropic.NewBetaImageBlock(anthropic.BetaBase64ImageSourceParam{
				Data: base64.StdEncoding.EncodeToString(img), MediaType: "image/jpeg"}),
			anthropic.NewBetaTextBlock(text),
		}
	case imageID != "":
		return []anthropic.BetaContentBlockParamUnion{anthropic.NewBetaTextBlock("[an earlier photo, no longer shown]\n" + text)}
	default:
		return []anthropic.BetaContentBlockParamUnion{anthropic.NewBetaTextBlock(text)}
	}
}

// hostOf returns the host name of an http(s) URL without "www.", or "".
func hostOf(raw string) string {
	u, err := url.Parse(raw)
	if err != nil || (u.Scheme != "https" && u.Scheme != "http") {
		return ""
	}
	return strings.TrimPrefix(strings.ToLower(u.Hostname()), "www.")
}

// classify maps SDK errors to the phone's statuses and logs the upstream
// class (status, type, message, request id: never content or keys).
func classify(err error) *upstreamError {
	var apierr *anthropic.Error
	if errors.As(err, &apierr) {
		var body struct {
			Error struct {
				Type    string `json:"type"`
				Message string `json:"message"`
			} `json:"error"`
		}
		json.Unmarshal([]byte(apierr.RawJSON()), &body)
		logJSON(map[string]any{
			"evt": "upstream_error", "status": apierr.StatusCode, "type": body.Error.Type,
			"message": truncate(body.Error.Message, 300), "request_id": apierr.RequestID,
		})
		msg := strings.ToLower(body.Error.Message)
		switch {
		case apierr.StatusCode == 402 || body.Error.Type == "billing_error" || strings.Contains(msg, "credit balance"):
			return &upstreamError{"definite", "billing"}
		case apierr.StatusCode == 429:
			return &upstreamError{"definite", "rate_limited"}
		case apierr.StatusCode == 401 || apierr.StatusCode == 403 || apierr.StatusCode == 404:
			return &upstreamError{"definite", "config_error"}
		case apierr.StatusCode == 529 || apierr.StatusCode == 503:
			return &upstreamError{"definite", "overloaded"}
		default:
			return &upstreamError{"definite", "upstream_error"}
		}
	}
	// no HTTP answer: timeout, reset, DNS... the call may have been processed
	var ne net.Error
	kind := "transport"
	if errors.As(err, &ne) && ne.Timeout() || errors.Is(err, context.DeadlineExceeded) {
		kind = "timeout"
	}
	logJSON(map[string]any{"evt": "upstream_error", "kind": kind})
	return &upstreamError{"uncertain", "uncertain"}
}

func truncate(s string, n int) string {
	if len(s) > n {
		return s[:n]
	}
	return s
}

// ------------------------------------------------------------ mock

// mockModel never touches the network. Every reply starts with "[Test mode]"
// so it can never be mistaken for Claude. Control words (tests):
// [[mock:uncertain]] [[mock:error]] [[mock:overloaded]] [[mock:billing]]
// [[mock:long]] [[mock:cut]] [[mock:slow]] [[mock:search]] [[mock:event]]
type mockModel struct{}

func (mockModel) reply(ctx context.Context, history []turn, message string, o replyOpts) (reply, error) {
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
	text := fmt.Sprintf("[Test mode] This is not a real Claude reply.\nYour message has %d characters. Earlier messages in this chat: %d.\nI received: \"%s\"",
		len([]rune(message)), prior, truncate(message, 200))
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

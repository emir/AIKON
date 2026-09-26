package main

// Claude calls through the official Go SDK. maxRetries is 0: a retried
// timeout could be a second paid call. Errors are classified as definite
// (Anthropic answered with an error; no reply was produced) or uncertain
// (the call may or may not have been processed).

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"strings"
	"time"

	"github.com/anthropics/anthropic-sdk-go"
	"github.com/anthropics/anthropic-sdk-go/option"
	"github.com/anthropics/anthropic-sdk-go/shared/constant"
)

const (
	maxTokens       = 2048
	upstreamTimeout = 60 * time.Second
)

const systemPrompt = `You are Claude, talking to the user through "Claude S40", an unofficial client running on a Nokia 6300 (Series 40) phone with a 240x320 screen and a numeric keypad.
Reply in the language the user writes in.
Keep answers short and easy to read on a small screen: normally 2-6 sentences, at most about 120 words, unless the user explicitly asks for more detail.
Use plain text only: no Markdown, no headings, no tables, no code blocks, no emoji. If a list helps, put each item on its own line starting with "- ".
When the user asks to shorten, expand or rephrase, apply it to your previous answer in this conversation.`

type turn struct {
	role    string // "user" | "assistant"
	content string
}

type reply struct {
	text         string
	cutOff       bool
	refused      bool
	inputTokens  int64
	outputTokens int64
	mock         bool
}

// upstreamError: kind "definite" or "uncertain"; code is the status sent to the phone.
type upstreamError struct {
	kind string
	code string
}

func (e *upstreamError) Error() string { return e.kind + ":" + e.code }

type model interface {
	reply(ctx context.Context, history []turn, message string) (reply, error)
}

// ------------------------------------------------------------ Anthropic

type claudeModel struct {
	client    anthropic.Client
	model     string
	effort    string
	fallbacks bool
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
	}
}

func (m *claudeModel) reply(ctx context.Context, history []turn, message string) (reply, error) {
	msgs := make([]anthropic.BetaMessageParam, 0, len(history)+1)
	for _, t := range history {
		role := anthropic.BetaMessageParamRoleUser
		if t.role == "assistant" {
			role = anthropic.BetaMessageParamRoleAssistant
		}
		msgs = append(msgs, anthropic.BetaMessageParam{
			Role:    role,
			Content: []anthropic.BetaContentBlockParamUnion{anthropic.NewBetaTextBlock(t.content)},
		})
	}
	msgs = append(msgs, anthropic.NewBetaUserMessage(anthropic.NewBetaTextBlock(message)))

	params := anthropic.BetaMessageNewParams{
		Model:     anthropic.Model(m.model),
		MaxTokens: maxTokens,
		System:    []anthropic.BetaTextBlockParam{{Text: systemPrompt}},
		Messages:  msgs,
	}
	if m.effort != "" {
		params.OutputConfig = anthropic.BetaOutputConfigParam{Effort: anthropic.BetaOutputConfigEffort(m.effort)}
	}
	if m.fallbacks {
		params.Betas = []anthropic.AnthropicBeta{anthropic.AnthropicBetaServerSideFallback2026_07_01}
		params.Fallbacks = anthropic.BetaFallbacksParamUnion{OfDefault: constant.ValueOf[constant.Default]()}
	}

	res, err := m.client.Beta.Messages.New(ctx, params)
	if err != nil {
		return reply{}, classify(err)
	}
	var text strings.Builder
	for _, b := range res.Content {
		if b.Type == "text" {
			text.WriteString(b.Text)
		}
	}
	return reply{
		text:         text.String(),
		cutOff:       res.StopReason == anthropic.BetaStopReasonMaxTokens,
		refused:      res.StopReason == anthropic.BetaStopReasonRefusal,
		inputTokens:  res.Usage.InputTokens + res.Usage.CacheReadInputTokens + res.Usage.CacheCreationInputTokens,
		outputTokens: res.Usage.OutputTokens,
	}, nil
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
// [[mock:long]] [[mock:cut]] [[mock:slow]]
type mockModel struct{}

func (mockModel) reply(ctx context.Context, history []turn, message string) (reply, error) {
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
	return reply{text: text, cutOff: strings.Contains(message, "[[mock:cut]]"), mock: true}, nil
}

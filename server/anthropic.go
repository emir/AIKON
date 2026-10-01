package main

// Anthropic: Claude calls through the official Go SDK. maxRetries is 0: a retried
// timeout could be a second paid call. Errors are classified as definite
// (Anthropic answered with an error; no reply was produced) or uncertain
// (the call may or may not have been processed).

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"net"
	"strings"
	"time"

	"github.com/anthropics/anthropic-sdk-go"
	"github.com/anthropics/anthropic-sdk-go/option"
	"github.com/anthropics/anthropic-sdk-go/shared/constant"
)

const (
	maxTokens = 2048
	// a turn with server tools can pause (stop_reason "pause_turn") after the
	// server-side loop limit; it is continued at most this many times
	maxContinuations = 1
)

// ------------------------------------------------------------ Anthropic

type claudeModel struct {
	client    anthropic.Client
	model     string
	name      string // how the model is introduced in the system prompt
	effort    string
	fallbacks bool
	// basicSearch: web_search_20250305 for models without the dynamic-filtering
	// variant (Haiku 4.5); otherwise web_search_20260209
	basicSearch bool
	search      searchConfig
	now         func() time.Time
}

// claudeFeatures: what a Claude model accepts beyond the basics. Haiku 4.5
// rejects output_config.effort, has no server-side fallback and only the
// basic web search tool; the current Opus/Sonnet/Fable models take all three.
func claudeFeatures(modelID string) (effort, fallbacks, dynamicSearch bool) {
	if strings.HasPrefix(modelID, "claude-haiku-") {
		return false, false, false
	}
	return true, true, true
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
	withEffort, withFallbacks, dynamicSearch := claudeFeatures(modelID)
	if !withEffort {
		effort = ""
	}
	return &claudeModel{
		client:      anthropic.NewClient(append(base, opts...)...),
		model:       modelID,
		name:        "Claude",
		effort:      effort,
		fallbacks:   fallbacks && withFallbacks,
		basicSearch: !dynamicSearch,
		search:      searchConfig{maxUses: 3},
		now:         time.Now,
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
		System:    []anthropic.BetaTextBlockParam{{Text: systemPrompt(m.name, o, m.now())}},
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
		var loc anthropic.BetaUserLocationParam
		if m.search.country != "" || m.search.city != "" || m.search.timezone != "" {
			if m.search.country != "" {
				loc.Country = anthropic.String(m.search.country)
			}
			if m.search.city != "" {
				loc.City = anthropic.String(m.search.city)
			}
			if m.search.timezone != "" {
				loc.Timezone = anthropic.String(m.search.timezone)
			}
		}
		uses := anthropic.Int(max(1, m.search.maxUses))
		if m.basicSearch {
			params.Tools = []anthropic.BetaToolUnionParam{{OfWebSearchTool20250305: &anthropic.BetaWebSearchTool20250305Param{
				MaxUses: uses, UserLocation: loc}}}
		} else {
			params.Tools = []anthropic.BetaToolUnionParam{{OfWebSearchTool20260209: &anthropic.BetaWebSearchTool20260209Param{
				MaxUses: uses, UserLocation: loc}}}
		}
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

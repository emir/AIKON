package main

// OpenAI and xAI (Grok) through the Responses API (POST /v1/responses),
// which both offer in the same shape: text and photo input, a server-side
// web_search tool and url_citation annotations. Plain net/http, one request
// per reply, no retries: a retried timeout could be a second paid call.
// store is false, so the provider keeps no copy of the conversation for
// later retrieval.

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"strings"
	"time"
)

const (
	// reasoning tokens count against max_output_tokens, so these models get
	// more room than Claude's maxTokens for the same short visible answer
	responsesMaxTokens = 4096
	maxResponseBody    = 1 << 20
)

// provider: what differs between the Responses APIs.
type provider struct {
	name    string // "openai" | "xai"
	baseURL string
	// how the per-message search limit is sent: OpenAI "max_tool_calls",
	// xAI "max_turns"; and whether an approximate location is understood
	maxCallsField string
	location      bool
}

var (
	openAIProvider = provider{name: "openai", baseURL: "https://api.openai.com", maxCallsField: "max_tool_calls", location: true}
	xAIProvider    = provider{name: "xai", baseURL: "https://api.x.ai", maxCallsField: "max_turns"}
)

type responsesModel struct {
	p      provider
	key    string
	model  string
	name   string // how the model is introduced in the system prompt
	effort string // reasoning.effort, "" = the model's default
	search searchConfig
	client *http.Client
	now    func() time.Time
}

func newResponsesModel(p provider, key, modelID, name, effort string) *responsesModel {
	return &responsesModel{p: p, key: key, model: modelID, name: name, effort: effort,
		search: searchConfig{maxUses: 3}, client: &http.Client{Timeout: upstreamTimeout}, now: time.Now}
}

type respContent struct {
	Type     string `json:"type"`
	Text     string `json:"text,omitempty"`
	ImageURL string `json:"image_url,omitempty"`
	Detail   string `json:"detail,omitempty"`
}

type respInput struct {
	Role    string `json:"role"`
	Content any    `json:"content"` // string, or []respContent with a photo
}

// respInputFor: the photo (if it is still sent) before the text; an older
// photo is named in the text, as for Claude.
func respInputFor(role, text string, img []byte, imageID string) respInput {
	switch {
	case len(img) > 0:
		return respInput{role, []respContent{
			{Type: "input_image", ImageURL: "data:image/jpeg;base64," + base64.StdEncoding.EncodeToString(img), Detail: "auto"},
			{Type: "input_text", Text: text},
		}}
	case imageID != "":
		return respInput{role, "[an earlier photo, no longer shown]\n" + text}
	default:
		return respInput{role, text}
	}
}

func (m *responsesModel) body(history []turn, message string, o replyOpts) map[string]any {
	input := make([]respInput, 0, len(history)+1)
	for _, t := range history {
		role := "user"
		if t.role == "assistant" {
			role = "assistant"
		}
		input = append(input, respInputFor(role, t.content, t.image, t.imageID))
	}
	input = append(input, respInputFor("user", message, o.image, o.imageID))
	b := map[string]any{
		"model":             m.model,
		"instructions":      systemPrompt(m.name, o, m.now()),
		"input":             input,
		"max_output_tokens": responsesMaxTokens,
		"store":             false,
	}
	if m.effort != "" {
		b["reasoning"] = map[string]any{"effort": m.effort}
	}
	if o.search {
		tool := map[string]any{"type": "web_search"}
		if m.p.location && (m.search.country != "" || m.search.city != "" || m.search.timezone != "") {
			loc := map[string]any{"type": "approximate"}
			for k, v := range map[string]string{"country": m.search.country, "city": m.search.city, "timezone": m.search.timezone} {
				if v != "" {
					loc[k] = v
				}
			}
			tool["user_location"] = loc
		}
		b["tools"] = []any{tool}
		b[m.p.maxCallsField] = max(1, m.search.maxUses)
	}
	return b
}

type respOutput struct {
	Status            string `json:"status"`
	IncompleteDetails *struct {
		Reason string `json:"reason"`
	} `json:"incomplete_details"`
	Output []struct {
		Type    string `json:"type"`
		Content []struct {
			Type        string `json:"type"`
			Text        string `json:"text"`
			Refusal     string `json:"refusal"`
			Annotations []struct {
				Type string `json:"type"`
				URL  string `json:"url"`
			} `json:"annotations"`
		} `json:"content"`
	} `json:"output"`
	Usage struct {
		InputTokens  int64 `json:"input_tokens"`
		OutputTokens int64 `json:"output_tokens"`
		// xAI also counts server-side tool calls here
		ServerSideToolUsageDetails struct {
			WebSearchCalls int64 `json:"web_search_calls"`
		} `json:"server_side_tool_usage_details"`
	} `json:"usage"`
}

func (m *responsesModel) reply(ctx context.Context, history []turn, message string, o replyOpts) (reply, error) {
	raw, _ := json.Marshal(m.body(history, message, o))
	req, err := http.NewRequestWithContext(ctx, "POST", m.p.baseURL+"/v1/responses", bytes.NewReader(raw))
	if err != nil {
		return reply{}, &upstreamError{"definite", "config_error"}
	}
	req.Header.Set("Authorization", "Bearer "+m.key)
	req.Header.Set("Content-Type", "application/json")
	res, err := m.client.Do(req)
	if err != nil {
		// no HTTP answer: timeout, reset, DNS... the call may have been processed
		kind := "transport"
		var ne net.Error
		if errors.As(err, &ne) && ne.Timeout() || errors.Is(err, context.DeadlineExceeded) {
			kind = "timeout"
		}
		logJSON(map[string]any{"evt": "upstream_error", "provider": m.p.name, "kind": kind})
		return reply{}, &upstreamError{"uncertain", "uncertain"}
	}
	defer res.Body.Close()
	body, err := io.ReadAll(io.LimitReader(res.Body, maxResponseBody))
	if err != nil {
		// answered, but the body was lost: the call was made and may be billed
		logJSON(map[string]any{"evt": "upstream_error", "provider": m.p.name, "kind": "read"})
		return reply{}, &upstreamError{"uncertain", "uncertain"}
	}
	if res.StatusCode != 200 {
		return reply{}, classifyResponses(m.p.name, res.StatusCode, body, res.Header.Get("X-Request-Id"))
	}
	var r respOutput
	if json.Unmarshal(body, &r) != nil {
		logJSON(map[string]any{"evt": "upstream_error", "provider": m.p.name, "kind": "bad_json"})
		return reply{}, &upstreamError{"uncertain", "uncertain"}
	}
	switch r.Status {
	case "completed", "incomplete":
	case "failed", "cancelled":
		logJSON(map[string]any{"evt": "upstream_error", "provider": m.p.name, "kind": "status", "status": r.Status})
		return reply{}, &upstreamError{"definite", "upstream_error"}
	default: // "in_progress", "queued": not expected without background mode
		logJSON(map[string]any{"evt": "upstream_error", "provider": m.p.name, "kind": "status", "status": truncate(r.Status, 20)})
		return reply{}, &upstreamError{"uncertain", "uncertain"}
	}

	out := reply{inputTokens: r.Usage.InputTokens, outputTokens: r.Usage.OutputTokens}
	var text strings.Builder
	var searches int64
	seen := map[string]bool{}
	for _, item := range r.Output {
		switch item.Type {
		case "web_search_call":
			searches++
			// text before or between searches is narration; keep the answer after the last one
			text.Reset()
		case "message":
			for _, c := range item.Content {
				switch c.Type {
				case "output_text":
					text.WriteString(c.Text)
					for _, a := range c.Annotations {
						if h := hostOf(a.URL); a.Type == "url_citation" && h != "" && !seen[h] {
							seen[h] = true
							out.sources = append(out.sources, h)
						}
					}
				case "refusal":
					out.refused = true
				}
			}
		}
	}
	out.searches = max(searches, r.Usage.ServerSideToolUsageDetails.WebSearchCalls)
	out.text = text.String()
	if r.Status == "incomplete" {
		reason := ""
		if r.IncompleteDetails != nil {
			reason = r.IncompleteDetails.Reason
		}
		if reason == "content_filter" && out.text == "" {
			out.refused = true
		} else {
			out.cutOff = true
		}
	}
	if out.refused && out.text != "" {
		// a refusal next to an answer: keep the answer
		out.refused = false
	}
	return out, nil
}

// classifyResponses maps an error answer to a phone status and logs its
// class (status, type, code, message, request id: never content or keys).
func classifyResponses(prov string, status int, raw []byte, requestID string) *upstreamError {
	var body struct {
		Error struct {
			Type    string `json:"type"`
			Code    any    `json:"code"`
			Message string `json:"message"`
		} `json:"error"`
	}
	// xAI may answer {"code": ..., "error": "text"}; then only the status counts
	json.Unmarshal(raw, &body)
	code := ""
	if body.Error.Code != nil {
		code = fmt.Sprint(body.Error.Code)
	}
	logJSON(map[string]any{"evt": "upstream_error", "provider": prov, "status": status, "type": body.Error.Type,
		"code": code, "message": truncate(body.Error.Message, 300), "request_id": requestID})
	msg := strings.ToLower(body.Error.Message)
	switch {
	case status == 402 || code == "insufficient_quota" || body.Error.Type == "insufficient_quota" ||
		strings.Contains(msg, "credit") || strings.Contains(msg, "billing"):
		return &upstreamError{"definite", "billing"}
	case status == 429:
		return &upstreamError{"definite", "rate_limited"}
	case status == 401 || status == 403 || status == 404:
		return &upstreamError{"definite", "config_error"}
	case status == 503 || status == 529:
		return &upstreamError{"definite", "overloaded"}
	default:
		return &upstreamError{"definite", "upstream_error"}
	}
}

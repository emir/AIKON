package main

// Google Gemini through the Gemini API (POST /v1beta/models/{id}:generateContent):
// text and photo input, Grounding with Google Search, sources from the
// grounding metadata. Plain net/http, one request per reply, no retries: a
// retried timeout could be a second paid call.

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"io"
	"net"
	"net/http"
	"net/url"
	"strings"
	"time"
)

type geminiModel struct {
	key     string
	model   string
	name    string // how the model is introduced in the system prompt
	effort  string // thinkingConfig.thinkingLevel, "" = the model's default
	baseURL string
	client  *http.Client
	now     func() time.Time
}

func newGeminiModel(key, modelID, name, effort string) *geminiModel {
	return &geminiModel{key: key, model: modelID, name: name, effort: effort,
		baseURL: "https://generativelanguage.googleapis.com", client: &http.Client{Timeout: upstreamTimeout}, now: time.Now}
}

type gemPart struct {
	Text       string `json:"text,omitempty"`
	InlineData *struct {
		MimeType string `json:"mime_type"`
		Data     string `json:"data"`
	} `json:"inline_data,omitempty"`
}

type gemContent struct {
	Role  string    `json:"role,omitempty"`
	Parts []gemPart `json:"parts"`
}

// gemContentFor: the photo (if it is still sent) before the text; an older
// photo is named in the text, as for the other providers.
func gemContentFor(role, text string, img []byte, imageID string) gemContent {
	switch {
	case len(img) > 0:
		p := gemPart{InlineData: &struct {
			MimeType string `json:"mime_type"`
			Data     string `json:"data"`
		}{"image/jpeg", base64.StdEncoding.EncodeToString(img)}}
		return gemContent{role, []gemPart{p, {Text: text}}}
	case imageID != "":
		return gemContent{role, []gemPart{{Text: "[an earlier photo, no longer shown]\n" + text}}}
	default:
		return gemContent{role, []gemPart{{Text: text}}}
	}
}

func (m *geminiModel) body(history []turn, message string, o replyOpts) map[string]any {
	contents := make([]gemContent, 0, len(history)+1)
	for _, t := range history {
		role := "user"
		if t.role == "assistant" {
			role = "model"
		}
		contents = append(contents, gemContentFor(role, t.content, t.image, t.imageID))
	}
	contents = append(contents, gemContentFor("user", message, o.image, o.imageID))
	gen := map[string]any{"maxOutputTokens": responsesMaxTokens}
	if m.effort != "" {
		gen["thinkingConfig"] = map[string]any{"thinkingLevel": m.effort}
	}
	b := map[string]any{
		"systemInstruction": gemContent{Parts: []gemPart{{Text: systemPrompt(m.name, o, m.now())}}},
		"contents":          contents,
		"generationConfig":  gen,
	}
	if o.search {
		// Gemini has no per-request search limit; the daily budget still applies
		b["tools"] = []any{map[string]any{"google_search": map[string]any{}}}
	}
	return b
}

type gemOutput struct {
	Candidates []struct {
		Content struct {
			Parts []struct {
				Text    string `json:"text"`
				Thought bool   `json:"thought"`
			} `json:"parts"`
		} `json:"content"`
		FinishReason      string `json:"finishReason"`
		GroundingMetadata struct {
			GroundingChunks []struct {
				Web struct {
					URI   string `json:"uri"`
					Title string `json:"title"`
				} `json:"web"`
			} `json:"groundingChunks"`
			WebSearchQueries []string `json:"webSearchQueries"`
		} `json:"groundingMetadata"`
	} `json:"candidates"`
	PromptFeedback struct {
		BlockReason string `json:"blockReason"`
	} `json:"promptFeedback"`
	UsageMetadata struct {
		PromptTokenCount     int64 `json:"promptTokenCount"`
		CandidatesTokenCount int64 `json:"candidatesTokenCount"`
		ThoughtsTokenCount   int64 `json:"thoughtsTokenCount"`
	} `json:"usageMetadata"`
}

func (m *geminiModel) reply(ctx context.Context, history []turn, message string, o replyOpts) (reply, error) {
	raw, _ := json.Marshal(m.body(history, message, o))
	u := m.baseURL + "/v1beta/models/" + url.PathEscape(m.model) + ":generateContent"
	req, err := http.NewRequestWithContext(ctx, "POST", u, bytes.NewReader(raw))
	if err != nil {
		return reply{}, &upstreamError{"definite", "config_error"}
	}
	req.Header.Set("x-goog-api-key", m.key)
	req.Header.Set("Content-Type", "application/json")
	res, err := m.client.Do(req)
	if err != nil {
		// no HTTP answer: timeout, reset, DNS... the call may have been processed
		kind := "transport"
		var ne net.Error
		if errors.As(err, &ne) && ne.Timeout() || errors.Is(err, context.DeadlineExceeded) {
			kind = "timeout"
		}
		logJSON(map[string]any{"evt": "upstream_error", "provider": "gemini", "kind": kind})
		return reply{}, &upstreamError{"uncertain", "uncertain"}
	}
	defer res.Body.Close()
	body, err := io.ReadAll(io.LimitReader(res.Body, maxResponseBody))
	if err != nil {
		logJSON(map[string]any{"evt": "upstream_error", "provider": "gemini", "kind": "read"})
		return reply{}, &upstreamError{"uncertain", "uncertain"}
	}
	if res.StatusCode != 200 {
		return reply{}, classifyGemini(res.StatusCode, body)
	}
	var r gemOutput
	if json.Unmarshal(body, &r) != nil {
		logJSON(map[string]any{"evt": "upstream_error", "provider": "gemini", "kind": "bad_json"})
		return reply{}, &upstreamError{"uncertain", "uncertain"}
	}
	out := reply{inputTokens: r.UsageMetadata.PromptTokenCount,
		outputTokens: r.UsageMetadata.CandidatesTokenCount + r.UsageMetadata.ThoughtsTokenCount}
	if r.PromptFeedback.BlockReason != "" || len(r.Candidates) == 0 {
		out.refused = r.PromptFeedback.BlockReason != ""
		return out, nil
	}
	c := r.Candidates[0]
	var text strings.Builder
	for _, p := range c.Content.Parts {
		if !p.Thought {
			text.WriteString(p.Text)
		}
	}
	out.text = text.String()
	out.searches = int64(len(c.GroundingMetadata.WebSearchQueries))
	seen := map[string]bool{}
	for _, ch := range c.GroundingMetadata.GroundingChunks {
		// the uri is a Google redirect; the title is the site's domain
		h := strings.TrimPrefix(strings.ToLower(ch.Web.Title), "www.")
		if !strings.Contains(h, ".") || strings.ContainsAny(h, " /") {
			h = hostOf(ch.Web.URI)
		}
		if h != "" && !seen[h] {
			seen[h] = true
			out.sources = append(out.sources, h)
		}
	}
	switch c.FinishReason {
	case "MAX_TOKENS":
		out.cutOff = true
	case "SAFETY", "PROHIBITED_CONTENT", "BLOCKLIST", "SPII", "RECITATION":
		if out.text == "" {
			out.refused = true
		} else {
			out.cutOff = true
		}
	}
	return out, nil
}

// classifyGemini maps an error answer to a phone status and logs its class
// (status, code, message: never content or keys).
func classifyGemini(status int, raw []byte) *upstreamError {
	var body struct {
		Error struct {
			Code    int    `json:"code"`
			Message string `json:"message"`
			Status  string `json:"status"`
		} `json:"error"`
	}
	json.Unmarshal(raw, &body)
	logJSON(map[string]any{"evt": "upstream_error", "provider": "gemini", "status": status, "code": body.Error.Status,
		"message": truncate(body.Error.Message, 300)})
	msg := strings.ToLower(body.Error.Message)
	switch {
	case status == 402 || strings.Contains(msg, "billing") || strings.Contains(msg, "credit"):
		return &upstreamError{"definite", "billing"}
	case status == 429:
		return &upstreamError{"definite", "rate_limited"}
	case status == 401 || status == 403 || status == 404 || strings.Contains(msg, "api key"):
		return &upstreamError{"definite", "config_error"}
	case status == 503 || status == 529:
		return &upstreamError{"definite", "overloaded"}
	default:
		return &upstreamError{"definite", "upstream_error"}
	}
}

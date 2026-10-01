package main

import (
	"context"
	"encoding/json"
	"net/http/httptest"
	"strings"
	"testing"
)

func fakeGemini(t *testing.T, f *fakeAPI) *geminiModel {
	ts := httptest.NewServer(f)
	t.Cleanup(ts.Close)
	m := newGeminiModel("AIza-test-not-a-real-key", "gemini-test", "Gemini Test", "low")
	m.baseURL = ts.URL
	return m
}

func gemBody(finish string, parts []any, extra map[string]any) string {
	cand := map[string]any{"content": map[string]any{"role": "model", "parts": parts}, "finishReason": finish}
	for k, v := range extra {
		cand[k] = v
	}
	b, _ := json.Marshal(map[string]any{"candidates": []any{cand},
		"usageMetadata": map[string]any{"promptTokenCount": 12, "candidatesTokenCount": 30, "thoughtsTokenCount": 4}})
	return string(b)
}

func TestGeminiRequestShape(t *testing.T) {
	f := &fakeAPI{status: 200, body: gemBody("STOP", []any{
		map[string]any{"text": "düşünce", "thought": true}, map[string]any{"text": "Ankara."}}, nil)}
	m := fakeGemini(t, f)
	h := []turn{{role: "user", content: "a", imageID: "old"}, {role: "assistant", content: "b"}}
	r, err := m.reply(context.Background(), h, "c", replyOpts{imageID: "new", image: []byte{0xff, 0xd8, 0xff}})
	if err != nil || r.text != "Ankara." || r.inputTokens != 12 || r.outputTokens != 34 || r.searches != 0 {
		t.Fatalf("%v %+v", err, r)
	}
	b := f.bodies[0]
	if f.hdrs[0].Get("X-Goog-Api-Key") != "AIza-test-not-a-real-key" {
		t.Fatal("key header")
	}
	raw, _ := json.Marshal(b)
	if strings.Contains(string(raw), "AIza") || !strings.Contains(string(raw), "You are Gemini Test, talking") {
		t.Fatal("key in body or no system prompt")
	}
	gen := b["generationConfig"].(map[string]any)
	if gen["maxOutputTokens"] != float64(responsesMaxTokens) || gen["thinkingConfig"].(map[string]any)["thinkingLevel"] != "low" {
		t.Fatalf("%v", gen)
	}
	if _, ok := b["tools"]; ok {
		t.Fatal("tools without search")
	}
	cs := b["contents"].([]any)
	roles := []string{}
	for _, c := range cs {
		roles = append(roles, c.(map[string]any)["role"].(string))
	}
	if strings.Join(roles, ",") != "user,model,user" {
		t.Fatal(roles)
	}
	first := cs[0].(map[string]any)["parts"].([]any)[0].(map[string]any)
	if first["text"] != "[an earlier photo, no longer shown]\na" {
		t.Fatal(first)
	}
	last := cs[2].(map[string]any)["parts"].([]any)
	img := last[0].(map[string]any)["inline_data"].(map[string]any)
	if img["mime_type"] != "image/jpeg" || img["data"] != "/9j/" || last[1].(map[string]any)["text"] != "c" {
		t.Fatal(last)
	}
}

func TestGeminiSearch(t *testing.T) {
	gm := map[string]any{"groundingMetadata": map[string]any{
		"webSearchQueries": []any{"ankara hava", "ankara sıcaklık"},
		"groundingChunks": []any{
			map[string]any{"web": map[string]any{"uri": "https://vertexaisearch.cloud.google.com/grounding-api-redirect/x", "title": "www.example.com"}},
			map[string]any{"web": map[string]any{"uri": "https://vertexaisearch.cloud.google.com/grounding-api-redirect/y", "title": "example.com"}},
			map[string]any{"web": map[string]any{"uri": "https://news.example.org/a", "title": "Bir haber başlığı"}},
		}}}
	f := &fakeAPI{status: 200, body: gemBody("STOP", []any{map[string]any{"text": "21 derece."}}, gm)}
	r, err := fakeGemini(t, f).reply(context.Background(), nil, "hava?", replyOpts{search: true})
	if err != nil || r.searches != 2 || strings.Join(r.sources, ",") != "example.com,news.example.org" {
		t.Fatalf("%v %+v", err, r)
	}
	tool := f.bodies[0]["tools"].([]any)[0].(map[string]any)
	if _, ok := tool["google_search"]; !ok {
		t.Fatal(tool)
	}
}

func TestGeminiFinishAndErrors(t *testing.T) {
	cases := []struct {
		body         string
		text         string
		cut, refused bool
	}{
		{gemBody("MAX_TOKENS", []any{map[string]any{"text": "yarım"}}, nil), "yarım", true, false},
		{gemBody("SAFETY", []any{}, nil), "", false, true},
		{`{"promptFeedback":{"blockReason":"PROHIBITED_CONTENT"},"usageMetadata":{"promptTokenCount":5}}`, "", false, true},
	}
	for i, c := range cases {
		r, err := fakeGemini(t, &fakeAPI{status: 200, body: c.body}).reply(context.Background(), nil, "x", replyOpts{})
		if err != nil || r.text != c.text || r.cutOff != c.cut || r.refused != c.refused {
			t.Fatalf("%d: %v %+v", i, err, r)
		}
	}
	gErr := func(code int, status, msg string) string {
		b, _ := json.Marshal(map[string]any{"error": map[string]any{"code": code, "status": status, "message": msg}})
		return string(b)
	}
	for _, c := range []struct {
		status int
		body   string
		want   string
	}{
		{400, gErr(400, "INVALID_ARGUMENT", "API key not valid. Please pass a valid API key."), "config_error"},
		{429, gErr(429, "RESOURCE_EXHAUSTED", "quota"), "rate_limited"},
		{404, gErr(404, "NOT_FOUND", "model not found"), "config_error"},
		{503, gErr(503, "UNAVAILABLE", "overloaded"), "overloaded"},
		{500, gErr(500, "INTERNAL", "x"), "upstream_error"},
		{400, gErr(400, "FAILED_PRECONDITION", "Billing is not enabled"), "billing"},
	} {
		f := &fakeAPI{status: c.status, body: c.body}
		_, err := fakeGemini(t, f).reply(context.Background(), nil, "x", replyOpts{})
		if ue, ok := err.(*upstreamError); !ok || ue.code != c.want || ue.kind != "definite" || f.count() != 1 {
			t.Fatalf("%d %s: %v", c.status, c.body, err)
		}
	}
	f := &fakeAPI{drop: true}
	if _, err := fakeGemini(t, f).reply(context.Background(), nil, "x", replyOpts{}); err.(*upstreamError).kind != "uncertain" || f.count() != 1 {
		t.Fatal(err)
	}
}

package main

import (
	"context"
	"encoding/json"
	"net/http/httptest"
	"strings"
	"testing"
)

func fakeResponses(t *testing.T, f *fakeAPI, p provider, effort string) *responsesModel {
	ts := httptest.NewServer(f)
	t.Cleanup(ts.Close)
	p.baseURL = ts.URL
	return newResponsesModel(p, "sk-test-not-a-real-key", "test-model", "Grok", effort)
}

func respBody(status string, output []any, extra map[string]any) string {
	b := map[string]any{"id": "resp_1", "object": "response", "status": status, "output": output,
		"usage": map[string]any{"input_tokens": 12, "output_tokens": 34}}
	for k, v := range extra {
		b[k] = v
	}
	raw, _ := json.Marshal(b)
	return string(raw)
}

func msgItem(parts ...any) map[string]any {
	return map[string]any{"type": "message", "role": "assistant", "status": "completed", "content": parts}
}

func outText(text string, urls ...string) map[string]any {
	ann := []any{}
	for _, u := range urls {
		ann = append(ann, map[string]any{"type": "url_citation", "url": u, "title": "t", "start_index": 0, "end_index": 1})
	}
	return map[string]any{"type": "output_text", "text": text, "annotations": ann}
}

func TestResponsesRequestShape(t *testing.T) {
	f := &fakeAPI{status: 200, body: respBody("completed", []any{
		map[string]any{"type": "reasoning", "summary": []any{}}, msgItem(outText("Ankara."))}, nil)}
	m := fakeResponses(t, f, openAIProvider, "low")
	r, err := m.reply(context.Background(), []turn{{role: "user", content: "a"}, {role: "assistant", content: "b"}}, "c", replyOpts{})
	if err != nil || r.text != "Ankara." || r.inputTokens != 12 || r.outputTokens != 34 || r.mock {
		t.Fatalf("%v %+v", err, r)
	}
	b := f.bodies[0]
	if b["model"] != "test-model" || b["store"] != false || b["max_output_tokens"] != float64(responsesMaxTokens) {
		t.Fatalf("%v", b)
	}
	if re, _ := b["reasoning"].(map[string]any); re["effort"] != "low" {
		t.Fatalf("effort %v", b["reasoning"])
	}
	if _, ok := b["tools"]; ok {
		t.Fatal("tools without search")
	}
	if !strings.Contains(b["instructions"].(string), "You are Grok, talking") {
		t.Fatal(b["instructions"])
	}
	if f.hdrs[0].Get("Authorization") != "Bearer sk-test-not-a-real-key" {
		t.Fatal("auth header")
	}
	roles := []string{}
	for _, in := range b["input"].([]any) {
		roles = append(roles, in.(map[string]any)["role"].(string))
	}
	if strings.Join(roles, ",") != "user,assistant,user" {
		t.Fatal(roles)
	}
	raw, _ := json.Marshal(b)
	if strings.Contains(string(raw), "sk-test") {
		t.Fatal("key in body")
	}

	// no effort configured: no reasoning field
	f2 := &fakeAPI{status: 200, body: respBody("completed", []any{msgItem(outText("x"))}, nil)}
	fakeResponses(t, f2, xAIProvider, "").reply(context.Background(), nil, "c", replyOpts{})
	if _, ok := f2.bodies[0]["reasoning"]; ok {
		t.Fatal("reasoning sent")
	}
}

func TestResponsesImage(t *testing.T) {
	f := &fakeAPI{status: 200, body: respBody("completed", []any{msgItem(outText("Bir kedi."))}, nil)}
	m := fakeResponses(t, f, xAIProvider, "")
	h := []turn{{role: "user", content: "eski", imageID: "old"}, {role: "assistant", content: "tamam"}}
	if _, err := m.reply(context.Background(), h, "bu ne?", replyOpts{imageID: "new", image: []byte{0xff, 0xd8, 0xff}}); err != nil {
		t.Fatal(err)
	}
	in := f.bodies[0]["input"].([]any)
	if c := in[0].(map[string]any)["content"]; c != "[an earlier photo, no longer shown]\neski" {
		t.Fatalf("%v", c)
	}
	parts := in[2].(map[string]any)["content"].([]any)
	img, txt := parts[0].(map[string]any), parts[1].(map[string]any)
	if img["type"] != "input_image" || img["image_url"] != "data:image/jpeg;base64,/9j/" ||
		txt["type"] != "input_text" || txt["text"] != "bu ne?" {
		t.Fatalf("%v", parts)
	}
}

func TestResponsesWebSearch(t *testing.T) {
	out := []any{
		msgItem(outText("Bakayım.")),
		map[string]any{"type": "web_search_call", "status": "completed", "action": map[string]any{"type": "search", "query": "ankara hava"}},
		map[string]any{"type": "web_search_call", "status": "completed"},
		msgItem(outText("Ankara'da bugün 21 derece.", "https://www.example.com/hava", "https://example.com/x", "https://news.example.org/a")),
	}
	f := &fakeAPI{status: 200, body: respBody("completed", out, nil)}
	m := fakeResponses(t, f, openAIProvider, "low")
	m.search = searchConfig{maxUses: 2, country: "TR", timezone: "Europe/Istanbul"}
	r, err := m.reply(context.Background(), nil, "Ankara hava?", replyOpts{search: true})
	if err != nil || r.text != "Ankara'da bugün 21 derece." || r.searches != 2 || r.cutOff {
		t.Fatalf("%v %+v", err, r)
	}
	if strings.Join(r.sources, ",") != "example.com,news.example.org" {
		t.Fatal(r.sources)
	}
	b := f.bodies[0]
	tool := b["tools"].([]any)[0].(map[string]any)
	loc, _ := tool["user_location"].(map[string]any)
	if tool["type"] != "web_search" || loc["type"] != "approximate" || loc["country"] != "TR" ||
		loc["timezone"] != "Europe/Istanbul" || b["max_tool_calls"] != 2.0 {
		t.Fatalf("%v", b)
	}
	if !strings.Contains(b["instructions"].(string), "search the web") {
		t.Fatal("search prompt")
	}

	// xAI: the limit is max_turns, no location; its usage count is used when higher
	f2 := &fakeAPI{status: 200, body: respBody("completed", []any{msgItem(outText("x"))},
		map[string]any{"usage": map[string]any{"input_tokens": 1, "output_tokens": 2,
			"server_side_tool_usage_details": map[string]any{"web_search_calls": 3}}})}
	m2 := fakeResponses(t, f2, xAIProvider, "")
	m2.search = searchConfig{maxUses: 3, country: "TR"}
	r2, _ := m2.reply(context.Background(), nil, "c", replyOpts{search: true})
	tool2 := f2.bodies[0]["tools"].([]any)[0].(map[string]any)
	if r2.searches != 3 || f2.bodies[0]["max_turns"] != 3.0 || tool2["user_location"] != nil || f2.bodies[0]["max_tool_calls"] != nil {
		t.Fatalf("%+v %v", r2, f2.bodies[0])
	}
}

func TestResponsesStatuses(t *testing.T) {
	cases := []struct {
		name    string
		body    string
		text    string
		cut     bool
		refused bool
	}{
		{"max tokens", respBody("incomplete", []any{msgItem(outText("yarım"))},
			map[string]any{"incomplete_details": map[string]any{"reason": "max_output_tokens"}}), "yarım", true, false},
		{"filtered, no text", respBody("incomplete", []any{},
			map[string]any{"incomplete_details": map[string]any{"reason": "content_filter"}}), "", false, true},
		{"refusal", respBody("completed", []any{msgItem(map[string]any{"type": "refusal", "refusal": "no"})}, nil), "", false, true},
	}
	for _, c := range cases {
		f := &fakeAPI{status: 200, body: c.body}
		r, err := fakeResponses(t, f, openAIProvider, "").reply(context.Background(), nil, "c", replyOpts{})
		if err != nil || r.text != c.text || r.cutOff != c.cut || r.refused != c.refused {
			t.Fatalf("%s: %v %+v", c.name, err, r)
		}
	}
	for status, kind := range map[string]string{"failed": "definite", "in_progress": "uncertain"} {
		f := &fakeAPI{status: 200, body: respBody(status, []any{}, nil)}
		_, err := fakeResponses(t, f, openAIProvider, "").reply(context.Background(), nil, "c", replyOpts{})
		if ue, ok := err.(*upstreamError); !ok || ue.kind != kind {
			t.Fatalf("%s: %v", status, err)
		}
	}
	f := &fakeAPI{status: 200, body: "{not json"}
	_, err := fakeResponses(t, f, openAIProvider, "").reply(context.Background(), nil, "c", replyOpts{})
	if ue, ok := err.(*upstreamError); !ok || ue.kind != "uncertain" {
		t.Fatalf("bad json: %v", err)
	}
}

func TestResponsesErrorsNoRetry(t *testing.T) {
	oaErr := func(typ, code, msg string) string {
		b, _ := json.Marshal(map[string]any{"error": map[string]any{"type": typ, "code": code, "message": msg}})
		return string(b)
	}
	cases := []struct {
		status int
		body   string
		want   string
	}{
		{500, oaErr("server_error", "", "x"), "upstream_error"},
		{429, oaErr("requests", "rate_limit_exceeded", "x"), "rate_limited"},
		{429, oaErr("insufficient_quota", "insufficient_quota", "You exceeded your current quota"), "billing"},
		{403, oaErr("", "", "Your team does not have any credits yet."), "billing"},
		{401, oaErr("invalid_request_error", "invalid_api_key", "x"), "config_error"},
		{404, `{"code":"Some code","error":"The model does not exist"}`, "config_error"},
		{503, oaErr("", "", "overloaded"), "overloaded"},
		{400, oaErr("invalid_request_error", "", "bad"), "upstream_error"},
	}
	for _, c := range cases {
		f := &fakeAPI{status: c.status, body: c.body}
		_, err := fakeResponses(t, f, openAIProvider, "").reply(context.Background(), nil, "c", replyOpts{})
		ue, ok := err.(*upstreamError)
		if !ok || ue.code != c.want || ue.kind != "definite" || f.count() != 1 {
			t.Fatalf("%d %s: %v calls=%d", c.status, c.body, err, f.count())
		}
	}
	f := &fakeAPI{drop: true}
	_, err := fakeResponses(t, f, xAIProvider, "").reply(context.Background(), nil, "c", replyOpts{})
	if ue, ok := err.(*upstreamError); !ok || ue.kind != "uncertain" || f.count() != 1 {
		t.Fatalf("dropped connection: %v calls=%d", err, f.count())
	}
}

func TestParseModels(t *testing.T) {
	specs, err := parseModels(" anthropic:claude-opus-5, openai:gpt-x=GPT X ,xai:grok-y")
	if err != nil || len(specs) != 3 {
		t.Fatal(err, specs)
	}
	want := []modelSpec{{"anthropic", "claude-opus-5", "Claude"}, {"openai", "gpt-x", "GPT X"}, {"xai", "grok-y", "Grok"}}
	for i := range want {
		if specs[i] != want[i] {
			t.Fatalf("%d: %+v", i, specs[i])
		}
	}
	for _, bad := range []string{"", ",", "claude-opus-5", "google:gemini", "openai:", "openai:a b",
		"openai:x=" + strings.Repeat("L", maxLabel+1), "openai:x,xai:x", "openai:x=Grök", "openai:x=a\tb"} {
		if _, err := parseModels(bad); err == nil {
			t.Fatalf("accepted %q", bad)
		}
	}
}

func TestBuildCatalog(t *testing.T) {
	keys := map[string]string{"/k/anthropic": "sk-ant-x", "/k/openai": "sk-x"}
	secret := func(p string) string { return keys[p] }
	c := config{models: "openai:gpt-x,anthropic:claude-opus-5", apiKeyFile: "/k/anthropic", openAIKeyFile: "/k/openai",
		xaiKeyFile: "/k/xai", searchMaxUses: 2}
	cat, err := buildCatalog(c, secret)
	if err != nil || cat.def().id != "gpt-x" || cat.def().label != "ChatGPT" || modelIDs(cat) != "openai:gpt-x,anthropic:claude-opus-5" {
		t.Fatal(err, cat)
	}
	if rm, ok := cat.def().m.(*responsesModel); !ok || rm.p.name != "openai" || rm.search.maxUses != 2 {
		t.Fatalf("%T", cat.def().m)
	}
	if e, ok := cat.get("claude-opus-5"); !ok || e.provider != "anthropic" {
		t.Fatal("get")
	}
	if _, ok := cat.get("nope"); ok {
		t.Fatal("unknown model found")
	}
	// DEFAULT_MODEL picks the default without changing the order
	c.defaultModel = "claude-opus-5"
	if cat, err := buildCatalog(c, secret); err != nil || cat.def().id != "claude-opus-5" || cat.list[0].id != "gpt-x" {
		t.Fatal(err)
	}
	c.defaultModel = "nope"
	if _, err := buildCatalog(c, secret); err == nil {
		t.Fatal("unknown DEFAULT_MODEL accepted")
	}
	c.defaultModel = ""
	// a model without its key: refuse to start
	c.models = "xai:grok-y"
	if _, err := buildCatalog(c, secret); err == nil || !strings.Contains(err.Error(), "xai") {
		t.Fatal(err)
	}
	// mock: no keys needed, every model is a labelled mock
	c.mock = true
	cat, err = buildCatalog(c, func(string) string { return "" })
	if err != nil {
		t.Fatal(err)
	}
	r, _ := cat.def().m.reply(context.Background(), nil, "x", replyOpts{})
	if !r.mock || !strings.HasPrefix(r.text, "[Test mode] This is not a real Grok reply.") {
		t.Fatal(r.text)
	}
}

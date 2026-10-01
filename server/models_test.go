package main

import (
	"context"
	"encoding/json"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/anthropics/anthropic-sdk-go/option"
)

// twoModels: Claude (default) and a Grok mock that records its calls.
func twoModels(e *tenv) *recModel {
	grok := &recModel{label: "Grok"}
	e.srv.chat.models = &catalog{[]modelEntry{
		{id: "claude-opus-5", label: "Claude", provider: "anthropic", search: true, m: mockModel{}},
		{id: "grok-y", label: "Grok", provider: "xai", search: false, m: grok},
	}}
	return grok
}

func (e *tenv) chatModel(token, request, conv, model, text string) resp {
	return e.do("POST", "/v1/chat", token, formatS40([]kv{{"request", request}, {"conversation", conv}, {"model", model}}, text))
}

func TestModelsList(t *testing.T) {
	e := newEnv(t, 100)
	e.srv.chat.search = true
	twoModels(e)
	tok, _ := e.pair("p")
	if r := e.do("POST", "/v1/models", "", "S40/1\n\n"); r.code != 401 {
		t.Fatalf("%q", r.raw)
	}
	r := e.do("POST", "/v1/models", tok, "S40/1\n\n")
	l := listLines(r)
	if r.msg.get("status") != "ok" || r.msg.get("default") != "claude-opus-5" || len(l) != 2 ||
		strings.Join(l[0], "|") != "claude-opus-5|Claude|1|1|Claude" || strings.Join(l[1], "|") != "grok-y|Grok|0|1|Grok" {
		t.Fatalf("%q", r.raw)
	}
	// search off on the server: no model offers it
	e.srv.chat.search = false
	if l := listLines(e.do("POST", "/v1/models", tok, "S40/1\n\n")); l[0][2] != "0" {
		t.Fatal(l)
	}
}

func TestModelPerConversation(t *testing.T) {
	e := newEnv(t, 100)
	e.srv.chat.search = true
	e.srv.chat.searchLimit = 30
	grok := twoModels(e)
	tok, _ := e.pair("p")

	// no choice: the default model; older phones get the fields too and ignore them
	r := e.chat(tok, rid(), "", "merhaba")
	conv := r.msg.get("conversation")
	if r.msg.get("model") != "claude-opus-5" || r.msg.get("model-name") != "Claude" || !strings.Contains(r.msg.text, "real Claude reply") {
		t.Fatalf("%q", r.raw)
	}
	// switch to Grok in the same conversation: it sees the history; no search tool (it has none here)
	r = e.do("POST", "/v1/chat", tok, formatS40([]kv{{"request", rid()}, {"conversation", conv}, {"model", "grok-y"}}, "[[mock:search]] devam"))
	if r.msg.get("model") != "grok-y" || r.msg.get("model-name") != "Grok" || !strings.Contains(r.msg.text, "real Grok reply") ||
		!strings.Contains(r.msg.text, "Earlier messages in this chat: 1") || grok.last.search {
		t.Fatalf("%q %+v", r.raw, grok.last)
	}
	// the conversation stays with Grok without naming it again
	if r = e.chat(tok, rid(), conv, "üçüncü"); r.msg.get("model") != "grok-y" {
		t.Fatalf("%q", r.raw)
	}
	// a new conversation can start with any model
	r = e.chatModel(tok, rid(), "", "grok-y", "yeni")
	conv2 := r.msg.get("conversation")
	if r.msg.get("model") != "grok-y" {
		t.Fatalf("%q", r.raw)
	}

	// unknown model: nothing is called or counted
	before := grok.calls()
	r = e.chatModel(tok, rid(), conv, "gpt-nope", "x")
	if r.code != 404 || r.msg.get("status") != "model_unavailable" || grok.calls() != before {
		t.Fatalf("%q", r.raw)
	}
	if r = e.chatModel(tok, rid(), conv, "bad model", "x"); r.code != 400 {
		t.Fatalf("%q", r.raw)
	}

	// replay: same model answers from the record; another model with the same id is a mismatch
	req := rid()
	e.chatModel(tok, req, conv, "claude-opus-5", "tekrar")
	if r = e.chatModel(tok, req, conv, "claude-opus-5", "tekrar"); r.msg.get("replayed") != "1" || r.msg.get("model-name") != "Claude" {
		t.Fatalf("%q", r.raw)
	}
	if r = e.chatModel(tok, req, conv, "grok-y", "tekrar"); r.msg.get("status") != "request_mismatch" {
		t.Fatalf("%q", r.raw)
	}

	// lists and history name the model for phones that ask
	l := listLines(e.do("POST", "/v1/conversations", tok, "S40/1\npins: 1\nmodels: 1\n\n"))
	if len(l) != 2 || l[0][1] != conv || l[0][4] != "claude-opus-5" || l[0][5] != "merhaba" || l[1][1] != conv2 || l[1][4] != "grok-y" {
		t.Fatalf("%q", l)
	}
	if l := listLines(e.do("POST", "/v1/conversations", tok, "S40/1\npins: 1\n\n")); len(l[0]) != 5 || l[0][4] != "merhaba" {
		t.Fatalf("old format %q", l)
	}
	h := e.do("POST", "/v1/history", tok, formatS40([]kv{{"conversation", conv}, {"models", "1"}}, ""))
	var heads []string
	for _, line := range strings.Split(h.msg.text, "\n") {
		if strings.HasPrefix(line, "u ") || strings.HasPrefix(line, "a ") {
			f := strings.Fields(line) // role, length, marks
			heads = append(heads, strings.Join(append(f[:1], f[2:]...), " "))
		}
	}
	if strings.Join(heads, ",") != "u,a m=claude-opus-5,u,a m=grok-y,u,a m=grok-y,u,a m=claude-opus-5" {
		t.Fatalf("%q", heads)
	}
	if old := e.do("POST", "/v1/history", tok, formatS40([]kv{{"conversation", conv}}, "")); strings.Contains(old.msg.text, " m=") {
		t.Fatal("model marks without models: 1")
	}

	// a model removed from MODELS: its conversation continues with the default
	e.srv.chat.models = singleModel("claude-opus-5", "Claude", mockModel{})
	if r = e.chat(tok, rid(), conv2, "hala orada mısın"); r.msg.get("status") != "ok" || r.msg.get("model") != "claude-opus-5" {
		t.Fatalf("%q", r.raw)
	}
}

func TestMigrateModelColumns(t *testing.T) {
	e := newEnv(t, 100)
	for _, tc := range []string{"conversations", "requests", "messages"} {
		var n int
		e.srv.st.db.QueryRow(`SELECT COUNT(*) FROM pragma_table_info(?) WHERE name='model'`, tc).Scan(&n)
		if n != 1 {
			t.Fatalf("%s.model missing", tc)
		}
	}
	// a conversation from before 0.7.0 ("" model) continues with the default
	tok, dev := e.pair("p")
	conv := e.chat(tok, rid(), "", "eski").msg.get("conversation")
	e.srv.st.db.Exec(`UPDATE conversations SET model='' WHERE device_id=? AND id=?`, dev, conv)
	twoModels(e)
	if r := e.chat(tok, rid(), conv, "devam"); r.msg.get("model") != "claude-opus-5" {
		t.Fatalf("%q", r.raw)
	}
}

// Haiku 4.5: no effort, no server-side fallback, the basic web search tool;
// the model is introduced by its label.
func TestClaudeHaikuRequest(t *testing.T) {
	f := &fakeAPI{status: 200, body: okBody("x", "end_turn")}
	ts := httptest.NewServer(f)
	t.Cleanup(ts.Close)
	m := newClaudeModel("sk-ant-test-not-a-real-key", "claude-haiku-4-5", "low", true, option.WithBaseURL(ts.URL))
	m.name = "Claude Haiku 4.5"
	if _, err := m.reply(context.Background(), nil, "c", replyOpts{search: true}); err != nil {
		t.Fatal(err)
	}
	b := f.bodies[0]
	if _, ok := b["output_config"]; ok {
		t.Fatal("effort sent to Haiku")
	}
	if _, ok := b["fallbacks"]; ok || f.hdrs[0].Get("Anthropic-Beta") != "" {
		t.Fatal("fallbacks sent to Haiku")
	}
	tool := b["tools"].([]any)[0].(map[string]any)
	sys, _ := json.Marshal(b["system"])
	if tool["type"] != "web_search_20250305" || !strings.Contains(string(sys), "You are Claude Haiku 4.5, talking") {
		t.Fatalf("%v %s", tool, sys)
	}

	// Opus 5.5 keeps all three
	f2 := &fakeAPI{status: 200, body: okBody("x", "end_turn")}
	ts2 := httptest.NewServer(f2)
	t.Cleanup(ts2.Close)
	newClaudeModel("sk-ant-test-not-a-real-key", "claude-opus-5-5", "low", true, option.WithBaseURL(ts2.URL)).
		reply(context.Background(), nil, "c", replyOpts{search: true})
	b2 := f2.bodies[0]
	if b2["fallbacks"] != "default" || b2["tools"].([]any)[0].(map[string]any)["type"] != "web_search_20260209" {
		t.Fatalf("%v", b2)
	}
	if oc, _ := b2["output_config"].(map[string]any); oc["effort"] != "low" {
		t.Fatalf("%v", b2["output_config"])
	}
}

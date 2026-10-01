package main

import (
	"context"
	"database/sql"
	"sync"
	"testing"
)

// fakeMeter records what the services ask; admit/reserve refuse with the
// given status when it is set.
type fakeMeter struct {
	mu                         sync.Mutex
	admitStatus, reserveStatus string
	admitted, reserved         []meterCall
	settled                    []meterCall
	uses                       []meterUse
}

func (f *fakeMeter) left(ctx context.Context, kind, device string) int { return 7 }

func (f *fakeMeter) admit(ctx context.Context, c meterCall) (string, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.admitted = append(f.admitted, c)
	return f.admitStatus, nil
}

func (f *fakeMeter) reserve(ctx context.Context, tx *sql.Tx, c meterCall) (string, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.reserved = append(f.reserved, c)
	return f.reserveStatus, nil
}

func (f *fakeMeter) settle(ctx context.Context, tx *sql.Tx, c meterCall, u meterUse) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.settled = append(f.settled, c)
	f.uses = append(f.uses, u)
	return nil
}

func (f *fakeMeter) counts() (int, int, int) {
	f.mu.Lock()
	defer f.mu.Unlock()
	return len(f.admitted), len(f.reserved), len(f.settled)
}

func TestMeterChatHooks(t *testing.T) {
	e := newEnv(t, 100)
	rec := &recModel{}
	e.srv.chat.models = singleModel("claude-opus-5", "Claude", rec)
	fm := &fakeMeter{}
	e.srv.chat.meter = fm
	tok, dev := e.pair("p")
	pending := func(id string) int {
		var n int
		e.srv.st.db.QueryRow(`SELECT COUNT(*) FROM requests WHERE device_id=? AND request_id=?`, dev, id).Scan(&n)
		return n
	}

	// refused before anything is recorded
	fm.admitStatus = "limit"
	id := rid()
	if r := e.chat(tok, id, "", "x"); r.code != 429 || r.msg.get("status") != "limit" || r.msg.get("remaining") != "7" {
		t.Fatalf("admit refusal: %q", r.raw)
	}
	if rec.calls() != 0 || pending(id) != 0 {
		t.Fatal("admit refusal called the model or recorded the request")
	}

	// refused in the pending transaction: rolled back, the same id can be sent again
	fm.admitStatus, fm.reserveStatus = "", "limit"
	if r := e.chat(tok, id, "", "x"); r.code != 429 || r.msg.get("status") != "limit" {
		t.Fatalf("reserve refusal: %q", r.raw)
	}
	if rec.calls() != 0 || pending(id) != 0 {
		t.Fatal("reserve refusal called the model or kept the record")
	}
	fm.reserveStatus = ""
	if r := e.chat(tok, id, "", "x"); r.code != 200 || r.msg.get("remaining") != "7" {
		t.Fatalf("after refusal: %q", r.raw)
	}
	_, reserved, settled := fm.counts()
	if reserved != 2 || settled != 1 {
		t.Fatalf("reserved %d settled %d", reserved, settled)
	}
	c := fm.settled[0]
	if c.kind != callChat || c.device != dev || c.request != id || c.model != "claude-opus-5" || c.started == 0 {
		t.Fatalf("settled call: %+v", c)
	}

	// a replay never reserves or settles again
	e.chat(tok, id, "", "x")
	if _, r2, s2 := fm.counts(); r2 != reserved || s2 != settled {
		t.Fatal("replay touched the meter")
	}

	// failed and uncertain calls are reserved but never settled
	for _, text := range []string{"[[mock:uncertain]]", "[[mock:error]]"} {
		e.srv.chat.models = singleModel("claude-opus-5", "Claude", mockModel{})
		e.chat(tok, rid(), "", text)
	}
	if _, r3, s3 := fm.counts(); r3 != reserved+2 || s3 != settled {
		t.Fatalf("failures: reserved %d settled %d", r3, s3)
	}

	// photos and web search reach the meter
	e.srv.chat.search, e.srv.chat.searchLimit = true, 30
	e.chat(tok, rid(), "", "y")
	if last := fm.reserved[len(fm.reserved)-1]; !last.search || last.image {
		t.Fatalf("options: %+v", last)
	}
}

type emptySTT struct{}

func (emptySTT) transcribe(ctx context.Context, wav []byte, lang string) (transcript, error) {
	return transcript{}, nil
}

func TestMeterTranscribeHooks(t *testing.T) {
	e := newEnv(t, 10)
	tok, dev := e.pair("phone")
	withSTT(e, mockSTT{}, 3)
	fm := &fakeMeter{admitStatus: "limit"}
	e.srv.transcriber.meter = fm

	if r := e.transcribe(tok, rid(), "tr", wavClip(2000)); r.code != 429 || r.msg.get("status") != "limit" {
		t.Fatalf("refusal: %d %q", r.code, r.raw)
	}
	fm.admitStatus = ""
	id := rid()
	if r := e.transcribe(tok, id, "tr", wavClip(2000)); r.code != 200 || r.msg.get("remaining") != "7" {
		t.Fatalf("ok: %d %q", r.code, r.raw)
	}
	e.transcribe(tok, id, "tr", wavClip(2000)) // replay
	if _, reserved, settled := fm.counts(); reserved != 1 || settled != 1 {
		t.Fatalf("reserved %d settled %d", reserved, settled)
	}
	if c, u := fm.settled[0], fm.uses[0]; c.kind != callTranscribe || c.device != dev || c.audioMS != 2000 || u.audioMS != 2000 {
		t.Fatalf("settled: %+v %+v", c, u)
	}

	// no speech: the service answered, so the call is settled
	e.srv.transcriber.stt = emptySTT{}
	if r := e.transcribe(tok, rid(), "tr", wavClip(2000)); r.msg.get("status") != "no_speech" {
		t.Fatalf("no speech: %q", r.raw)
	}
	if _, reserved, settled := fm.counts(); reserved != 2 || settled != 2 {
		t.Fatalf("no speech: reserved %d settled %d", reserved, settled)
	}
}

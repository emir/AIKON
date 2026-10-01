package main

// The meter decides whether a paid call (a chat message or a voice message)
// may start and books what it used. The chat and transcription services keep
// the rules that do not depend on it: the request record, replays, one call
// in flight per device, no retries, "uncertain".
//
// The default is dailyMeter, the per-device daily limits. A build can install
// another meter by setting meterFactory from an init function in a file of
// its own, without touching the shared code.

import (
	"context"
	"database/sql"
	"errors"
)

const (
	callChat       = "chat"
	callTranscribe = "transcribe"
)

// meterCall: one paid call as the meter sees it.
type meterCall struct {
	kind    string // callChat | callTranscribe
	device  string
	request string
	started int64  // when the call was recorded (ms)
	model   string // chat: the model that will answer
	search  bool   // chat: web search is offered
	image   bool   // chat: the message has a photo
	audioMS int    // transcribe: the clip's length
}

// meterUse: what an answered call used.
type meterUse struct {
	inputTokens  int64
	outputTokens int64
	searches     int64
	audioMS      int
}

// A meter is called under the device's lock. A reservation must only count
// while the call's record is "pending": a call that fails or becomes
// "uncertain" (also through a restart or a stale pending record) is never
// settled and gets no other callback.
type meter interface {
	// left: what the phone is shown as "remaining" for this kind of call.
	left(ctx context.Context, kind, device string) int
	// admit runs before anything is recorded: "" lets the call go on,
	// anything else is the status to answer (e.g. "limit").
	admit(ctx context.Context, c meterCall) (string, error)
	// reserve runs in the transaction that records the call as pending;
	// a status other than "" rolls it back and is answered instead.
	reserve(ctx context.Context, tx *sql.Tx, c meterCall) (string, error)
	// settle runs in the transaction that stores an answered call's result.
	settle(ctx context.Context, tx *sql.Tx, c meterCall, u meterUse) error
}

// meterFactory builds the meter shared by chat and voice messages.
var meterFactory = func(st *store, c config) meter {
	return &dailyMeter{st: st, reqLimit: c.reqLimit, tokLimit: c.tokLimit, transcribeLimit: c.transcribeLimit}
}

// dailyMeter: requests and output tokens (chat) and voice messages per
// device and UTC day, counted when the call starts, in the usage table.
type dailyMeter struct {
	st              *store
	reqLimit        int
	tokLimit        int64
	transcribeLimit int
}

func (d *dailyMeter) left(ctx context.Context, kind, device string) int {
	day := utcDay(d.st.ms())
	if kind == callTranscribe {
		var n int
		d.st.db.QueryRowContext(ctx, `SELECT transcripts FROM usage WHERE device_id=? AND day=?`, device, day).Scan(&n)
		return max(0, d.transcribeLimit-n)
	}
	var req int
	var out int64
	err := d.st.db.QueryRowContext(ctx, `SELECT requests, output_tokens FROM usage WHERE device_id=? AND day=?`,
		device, day).Scan(&req, &out)
	if errors.Is(err, sql.ErrNoRows) {
		return d.reqLimit
	}
	if err != nil || out >= d.tokLimit {
		return 0
	}
	return max(0, d.reqLimit-req)
}

func (d *dailyMeter) admit(ctx context.Context, c meterCall) (string, error) {
	if d.left(ctx, c.kind, c.device) <= 0 {
		return "limit", nil
	}
	return "", nil
}

func (d *dailyMeter) reserve(ctx context.Context, tx *sql.Tx, c meterCall) (string, error) {
	day := utcDay(c.started)
	var err error
	if c.kind == callTranscribe {
		_, err = tx.ExecContext(ctx, `INSERT INTO usage (device_id, day, transcripts, audio_ms) VALUES (?, ?, 1, ?)
			ON CONFLICT(device_id, day) DO UPDATE SET transcripts = transcripts + 1, audio_ms = audio_ms + ?`,
			c.device, day, c.audioMS, c.audioMS)
	} else {
		_, err = tx.ExecContext(ctx, `INSERT INTO usage (device_id, day, requests) VALUES (?, ?, 1)
			ON CONFLICT(device_id, day) DO UPDATE SET requests = requests + 1`, c.device, day)
	}
	return "", err
}

// settle adds a chat reply's tokens and searches to the day the call was
// counted on.
func (d *dailyMeter) settle(ctx context.Context, tx *sql.Tx, c meterCall, u meterUse) error {
	if c.kind == callTranscribe {
		return nil // counted with the clip's length when it started
	}
	_, err := tx.ExecContext(ctx, `UPDATE usage SET input_tokens = input_tokens + ?, output_tokens = output_tokens + ?,
		searches = searches + ? WHERE device_id=? AND day=?`, u.inputTokens, u.outputTokens, u.searches, c.device, utcDay(c.started))
	return err
}

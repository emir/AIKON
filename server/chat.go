package main

// Chat flow, ported from the Worker's ChatStore Durable Object:
//
//   - same request_id again -> answer from the record, never call again
//   - one request in flight per device
//   - daily request / output-token limits per device (UTC day)
//   - conversation ids are scoped to the device
//   - the request is recorded as "pending" and counted BEFORE the paid call
//   - uncertain upstream results are recorded and never retried

import (
	"context"
	"database/sql"
	"errors"
	"sync"
	"time"
)

const (
	maxMessageChars         = 1000
	maxReplyChars           = 2000
	contextMessages         = 16
	contextChars            = 16000
	maxConversationMessages = 40
	pendingStale            = 180 * time.Second
)

type chatResult struct {
	http         int
	status       string
	request      string
	conversation string
	text         string
	truncated    bool
	refused      bool
	replayed     bool
	mock         bool
	remaining    int
	hasRemaining bool
}

var statusHTTP = map[string]int{
	"ok": 200, "pending": 202, "busy": 409, "limit": 429, "conversation_full": 409,
	"conversation_not_found": 404, "request_mismatch": 409, "rate_limited": 503, "overloaded": 503,
	"upstream_error": 502, "config_error": 500, "billing": 402, "uncertain": 504,
}

type chatService struct {
	st          *store
	model       model
	reqLimit    int
	tokLimit    int64
	deviceLocks sync.Map // device id -> *sync.Mutex (serialises the bookkeeping, not the call)
}

func (c *chatService) lock(device string) *sync.Mutex {
	m, _ := c.deviceLocks.LoadOrStore(device, &sync.Mutex{})
	return m.(*sync.Mutex)
}

func result(status, request string) chatResult {
	return chatResult{http: statusHTTP[status], status: status, request: request}
}

func (c *chatService) remainingToday(ctx context.Context, device string) int {
	var req int
	var out int64
	err := c.st.db.QueryRowContext(ctx, `SELECT requests, output_tokens FROM usage WHERE device_id=? AND day=?`,
		device, utcDay(c.st.ms())).Scan(&req, &out)
	if errors.Is(err, sql.ErrNoRows) {
		return c.reqLimit
	}
	if err != nil || out >= c.tokLimit {
		return 0
	}
	if r := c.reqLimit - req; r > 0 {
		return r
	}
	return 0
}

func (c *chatService) chat(ctx context.Context, device, requestID, conv, message string) (chatResult, error) {
	mu := c.lock(device)
	mu.Lock()
	db := c.st.db
	now := c.st.ms()
	sha := sha256Hex(message)

	// 1. repeated request_id: report the record, never call again
	var prevConv, prevSha, prevState string
	var prevCreated int64
	var prevReply, prevErr sql.NullString
	var prevTrunc, prevRef, prevMock int
	err := db.QueryRowContext(ctx, `SELECT conversation_id, message_sha, state, created_at, reply, error, truncated, refused, mock
		FROM requests WHERE device_id=? AND request_id=?`, device, requestID).
		Scan(&prevConv, &prevSha, &prevState, &prevCreated, &prevReply, &prevErr, &prevTrunc, &prevRef, &prevMock)
	if err == nil {
		defer mu.Unlock()
		if prevSha != sha {
			return result("request_mismatch", requestID), nil
		}
		r := result(prevState, requestID)
		r.conversation = prevConv
		switch prevState {
		case "pending":
			if now-prevCreated > pendingStale.Milliseconds() {
				db.ExecContext(ctx, `UPDATE requests SET state='uncertain', error='uncertain' WHERE device_id=? AND request_id=?`,
					device, requestID)
				r = result("uncertain", requestID)
				r.conversation = prevConv
			}
			return r, nil
		case "done":
			r = result("ok", requestID)
			r.conversation, r.text, r.replayed = prevConv, prevReply.String, true
			r.truncated, r.refused, r.mock = prevTrunc == 1, prevRef == 1, prevMock == 1
			r.remaining, r.hasRemaining = c.remainingToday(ctx, device), true
			return r, nil
		case "uncertain":
			return r, nil
		default: // failed
			r = result(prevErr.String, requestID)
			r.conversation = prevConv
			return r, nil
		}
	}
	if !errors.Is(err, sql.ErrNoRows) {
		mu.Unlock()
		return chatResult{}, err
	}

	// 2. one request at a time per device
	db.ExecContext(ctx, `UPDATE requests SET state='uncertain', error='uncertain'
		WHERE device_id=? AND state='pending' AND created_at < ?`, device, now-pendingStale.Milliseconds())
	var busy int
	if db.QueryRowContext(ctx, `SELECT 1 FROM requests WHERE device_id=? AND state='pending' LIMIT 1`, device).Scan(&busy) == nil {
		mu.Unlock()
		return result("busy", requestID), nil
	}

	// 3. daily limits
	remaining := c.remainingToday(ctx, device)
	if remaining <= 0 {
		mu.Unlock()
		r := result("limit", requestID)
		r.hasRemaining = true
		return r, nil
	}

	// 4. conversation
	if conv != "" {
		var x int
		if db.QueryRowContext(ctx, `SELECT 1 FROM conversations WHERE device_id=? AND id=?`, device, conv).Scan(&x) != nil {
			mu.Unlock()
			return result("conversation_not_found", requestID), nil
		}
		var n int
		db.QueryRowContext(ctx, `SELECT COUNT(*) FROM messages WHERE device_id=? AND conversation_id=?`, device, conv).Scan(&n)
		if n+2 > maxConversationMessages {
			mu.Unlock()
			r := result("conversation_full", requestID)
			r.conversation = conv
			return r, nil
		}
	} else {
		conv = randomHex(8)
		if _, err := db.ExecContext(ctx, `INSERT INTO conversations (id, device_id, created_at, updated_at) VALUES (?, ?, ?, ?)`,
			conv, device, now, now); err != nil {
			mu.Unlock()
			return chatResult{}, err
		}
		c.st.trimConversations(ctx, device)
	}
	history, err := c.context(ctx, device, conv)
	if err != nil {
		mu.Unlock()
		return chatResult{}, err
	}

	// 5. record + count BEFORE the paid call
	tx, err := db.BeginTx(ctx, nil)
	if err != nil {
		mu.Unlock()
		return chatResult{}, err
	}
	_, e1 := tx.ExecContext(ctx, `INSERT INTO requests (device_id, request_id, conversation_id, message_sha, state, created_at)
		VALUES (?, ?, ?, ?, 'pending', ?)`, device, requestID, conv, sha, now)
	_, e2 := tx.ExecContext(ctx, `INSERT INTO usage (device_id, day, requests) VALUES (?, ?, 1)
		ON CONFLICT(device_id, day) DO UPDATE SET requests = requests + 1`, device, utcDay(now))
	if e1 != nil || e2 != nil {
		tx.Rollback()
		mu.Unlock()
		return chatResult{}, errors.Join(e1, e2)
	}
	if err := tx.Commit(); err != nil {
		mu.Unlock()
		return chatResult{}, err
	}
	mu.Unlock()

	// 6. the call (no lock held; the pending row keeps other requests out)
	callCtx := context.WithoutCancel(ctx) // a phone disconnect must not turn a paid call into "unknown"
	rep, callErr := c.model.reply(callCtx, history, message)

	mu.Lock()
	defer mu.Unlock()
	done := c.st.ms()
	if callErr != nil {
		ue, ok := callErr.(*upstreamError)
		if !ok {
			ue = &upstreamError{"uncertain", "uncertain"}
		}
		state := "failed"
		if ue.kind == "uncertain" {
			state = "uncertain"
		}
		db.ExecContext(ctx, `UPDATE requests SET state=?, error=?, finished_at=? WHERE device_id=? AND request_id=?`,
			state, ue.code, done, device, requestID)
		r := result(ue.code, requestID)
		r.conversation = conv
		r.remaining, r.hasRemaining = remaining-1, true
		return r, nil
	}

	// 7. store the completed exchange
	text, cut := limitChars(sanitizeReply(rep.text), maxReplyChars)
	truncated := cut || rep.cutOff
	if rep.refused {
		text = ""
	}
	tx, err = db.BeginTx(ctx, nil)
	if err != nil {
		return chatResult{}, err
	}
	defer tx.Rollback()
	if !rep.refused && text != "" {
		var seq int
		tx.QueryRowContext(ctx, `SELECT COALESCE(MAX(seq), 0) FROM messages WHERE device_id=? AND conversation_id=?`,
			device, conv).Scan(&seq)
		tx.ExecContext(ctx, `INSERT INTO messages (device_id, conversation_id, seq, role, content, created_at)
			VALUES (?, ?, ?, 'user', ?, ?)`, device, conv, seq+1, message, now)
		tx.ExecContext(ctx, `INSERT INTO messages (device_id, conversation_id, seq, role, content, created_at)
			VALUES (?, ?, ?, 'assistant', ?, ?)`, device, conv, seq+2, text, done)
	}
	tx.ExecContext(ctx, `UPDATE conversations SET updated_at=? WHERE device_id=? AND id=?`, done, device, conv)
	tx.ExecContext(ctx, `UPDATE requests SET state='done', reply=?, truncated=?, refused=?, mock=?,
		input_tokens=?, output_tokens=?, finished_at=? WHERE device_id=? AND request_id=?`,
		text, b2i(truncated), b2i(rep.refused), b2i(rep.mock), rep.inputTokens, rep.outputTokens, done, device, requestID)
	tx.ExecContext(ctx, `UPDATE usage SET input_tokens = input_tokens + ?, output_tokens = output_tokens + ?
		WHERE device_id=? AND day=?`, rep.inputTokens, rep.outputTokens, device, utcDay(now))
	if err := tx.Commit(); err != nil {
		return chatResult{}, err
	}
	r := result("ok", requestID)
	r.conversation, r.text, r.truncated, r.refused, r.mock = conv, text, truncated, rep.refused, rep.mock
	r.remaining, r.hasRemaining = c.remainingToday(ctx, device), true
	return r, nil
}

// context: newest completed messages within both caps, oldest first, starting with a user turn.
func (c *chatService) context(ctx context.Context, device, conv string) ([]turn, error) {
	rows, err := c.st.db.QueryContext(ctx, `SELECT role, content FROM messages
		WHERE device_id=? AND conversation_id=? ORDER BY seq DESC LIMIT ?`, device, conv, contextMessages)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var rev []turn
	chars := 0
	for rows.Next() {
		var t turn
		if err := rows.Scan(&t.role, &t.content); err != nil {
			return nil, err
		}
		chars += len([]rune(t.content))
		if chars > contextChars {
			break
		}
		rev = append(rev, t)
	}
	out := make([]turn, 0, len(rev))
	for i := len(rev) - 1; i >= 0; i-- {
		out = append(out, rev[i])
	}
	for len(out) > 0 && out[0].role != "user" {
		out = out[1:]
	}
	return out, rows.Err()
}

// deleteConversation: false if unknown or a request is still running in it.
func (c *chatService) deleteConversation(ctx context.Context, device, conv string) (bool, error) {
	mu := c.lock(device)
	mu.Lock()
	defer mu.Unlock()
	var x int
	if c.st.db.QueryRowContext(ctx, `SELECT 1 FROM conversations WHERE device_id=? AND id=?`, device, conv).Scan(&x) != nil {
		return false, nil
	}
	if c.st.db.QueryRowContext(ctx, `SELECT 1 FROM requests WHERE device_id=? AND conversation_id=? AND state='pending'`,
		device, conv).Scan(&x) == nil {
		return false, nil
	}
	return true, c.st.deleteConversation(ctx, device, conv)
}

func b2i(b bool) int {
	if b {
		return 1
	}
	return 0
}

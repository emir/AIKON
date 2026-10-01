package main

// SQLite storage (one file on the Docker volume, pure-Go driver). Replaces
// the Worker's two Durable Objects: every table that holds user data is
// scoped by device_id, so a conversation id is only meaningful for the
// device that created it.

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"errors"
	"fmt"
	"strings"
	"time"
	"unicode"

	_ "modernc.org/sqlite"
)

const schema = `
CREATE TABLE IF NOT EXISTS devices (
  device_id TEXT PRIMARY KEY, name TEXT NOT NULL, token_hash TEXT NOT NULL UNIQUE,
  created_at INTEGER NOT NULL, revoked INTEGER NOT NULL DEFAULT 0);
CREATE TABLE IF NOT EXISTS pairings (
  pair_id TEXT PRIMARY KEY, code TEXT NOT NULL, state TEXT NOT NULL,
  created_at INTEGER NOT NULL, expires_at INTEGER NOT NULL, device_id TEXT, token TEXT);
CREATE TABLE IF NOT EXISTS conversations (
  id TEXT NOT NULL, device_id TEXT NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL,
  PRIMARY KEY (device_id, id));
CREATE TABLE IF NOT EXISTS messages (
  device_id TEXT NOT NULL, conversation_id TEXT NOT NULL, seq INTEGER NOT NULL, role TEXT NOT NULL,
  content TEXT NOT NULL, created_at INTEGER NOT NULL,
  PRIMARY KEY (device_id, conversation_id, seq));
CREATE TABLE IF NOT EXISTS requests (
  device_id TEXT NOT NULL, request_id TEXT NOT NULL, conversation_id TEXT NOT NULL, message_sha TEXT NOT NULL,
  state TEXT NOT NULL, created_at INTEGER NOT NULL, finished_at INTEGER,
  reply TEXT, truncated INTEGER NOT NULL DEFAULT 0, refused INTEGER NOT NULL DEFAULT 0,
  mock INTEGER NOT NULL DEFAULT 0, error TEXT,
  input_tokens INTEGER NOT NULL DEFAULT 0, output_tokens INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (device_id, request_id));
CREATE INDEX IF NOT EXISTS requests_state ON requests (device_id, state);
CREATE TABLE IF NOT EXISTS usage (
  device_id TEXT NOT NULL, day TEXT NOT NULL, requests INTEGER NOT NULL DEFAULT 0,
  input_tokens INTEGER NOT NULL DEFAULT 0, output_tokens INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (device_id, day));
CREATE TABLE IF NOT EXISTS images (
  device_id TEXT NOT NULL, id TEXT NOT NULL, sha TEXT NOT NULL, created_at INTEGER NOT NULL,
  conversation_id TEXT NOT NULL DEFAULT '', width INTEGER NOT NULL, height INTEGER NOT NULL, data BLOB NOT NULL,
  PRIMARY KEY (device_id, id));
CREATE INDEX IF NOT EXISTS images_sha ON images (device_id, sha);
CREATE TABLE IF NOT EXISTS transcripts (
  device_id TEXT NOT NULL, request_id TEXT NOT NULL, audio_sha TEXT NOT NULL, state TEXT NOT NULL,
  created_at INTEGER NOT NULL, finished_at INTEGER, audio_ms INTEGER NOT NULL DEFAULT 0,
  text TEXT, mock INTEGER NOT NULL DEFAULT 0, error TEXT,
  PRIMARY KEY (device_id, request_id));
`

type store struct {
	db  *sql.DB
	now func() time.Time
}

func openStore(path string) (*store, error) {
	db, err := sql.Open("sqlite", path+"?_pragma=journal_mode(WAL)&_pragma=busy_timeout(5000)&_pragma=foreign_keys(1)")
	if err != nil {
		return nil, err
	}
	db.SetMaxOpenConns(1) // one writer; all access is short
	if _, err := db.Exec(schema); err != nil {
		db.Close()
		return nil, err
	}
	if err := migrate(db); err != nil {
		db.Close()
		return nil, err
	}
	s := &store{db: db, now: time.Now}
	// a request left "pending" by a restart may or may not have been billed
	for _, t := range []string{"requests", "transcripts"} {
		if _, err := db.Exec(`UPDATE ` + t + ` SET state='uncertain', error='uncertain' WHERE state='pending'`); err != nil {
			db.Close()
			return nil, err
		}
	}
	return s, nil
}

// migrate adds columns introduced after the first release (0.3.0: web
// search counts; 0.4.0: pinned conversations; 0.5.0: voice message counts;
// 0.6.0: photos in messages).
// Existing databases keep their data.
func migrate(db *sql.DB) error {
	for _, c := range []struct{ table, column, def string }{
		{"usage", "searches", "INTEGER NOT NULL DEFAULT 0"},
		{"requests", "searches", "INTEGER NOT NULL DEFAULT 0"},
		{"conversations", "pinned", "INTEGER NOT NULL DEFAULT 0"},
		{"usage", "transcripts", "INTEGER NOT NULL DEFAULT 0"},
		{"usage", "audio_ms", "INTEGER NOT NULL DEFAULT 0"},
		{"usage", "images", "INTEGER NOT NULL DEFAULT 0"},
		{"messages", "image_id", "TEXT NOT NULL DEFAULT ''"},
	} {
		var n int
		if err := db.QueryRow(`SELECT COUNT(*) FROM pragma_table_info(?) WHERE name=?`, c.table, c.column).Scan(&n); err != nil {
			return err
		}
		if n == 0 {
			if _, err := db.Exec(`ALTER TABLE ` + c.table + ` ADD COLUMN ` + c.column + ` ` + c.def); err != nil {
				return err
			}
		}
	}
	return nil
}

func (s *store) close() error { return s.db.Close() }

func (s *store) ms() int64 { return s.now().UnixMilli() }

func utcDay(ms int64) string { return time.UnixMilli(ms).UTC().Format("2006-01-02") }

func randomHex(n int) string {
	b := make([]byte, n)
	if _, err := rand.Read(b); err != nil {
		panic(err)
	}
	return hex.EncodeToString(b)
}

func sha256Hex(s string) string {
	h := sha256.Sum256([]byte(s))
	return hex.EncodeToString(h[:])
}

// ------------------------------------------------------------ devices

func (s *store) deviceForToken(ctx context.Context, token string) (string, error) {
	var id string
	err := s.db.QueryRowContext(ctx,
		`SELECT device_id FROM devices WHERE token_hash=? AND revoked=0`, sha256Hex(token)).Scan(&id)
	if errors.Is(err, sql.ErrNoRows) {
		return "", nil
	}
	return id, err
}

type deviceInfo struct {
	DeviceID  string `json:"device_id"`
	Name      string `json:"name"`
	CreatedAt int64  `json:"created_at"`
	Revoked   bool   `json:"revoked"`
}

func (s *store) listDevices(ctx context.Context) ([]deviceInfo, error) {
	rows, err := s.db.QueryContext(ctx, `SELECT device_id, name, created_at, revoked FROM devices ORDER BY created_at`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []deviceInfo{}
	for rows.Next() {
		var d deviceInfo
		var rev int
		if err := rows.Scan(&d.DeviceID, &d.Name, &d.CreatedAt, &rev); err != nil {
			return nil, err
		}
		d.Revoked = rev == 1
		out = append(out, d)
	}
	return out, rows.Err()
}

func (s *store) revokeDevice(ctx context.Context, id string) (bool, error) {
	r, err := s.db.ExecContext(ctx, `UPDATE devices SET revoked=1 WHERE device_id=? AND revoked=0`, id)
	if err != nil {
		return false, err
	}
	n, _ := r.RowsAffected()
	return n > 0, nil
}

// ------------------------------------------------------------ pairing

const (
	pairTTL    = 10 * time.Minute
	maxPending = 3
)

func (s *store) expirePairings(ctx context.Context) {
	s.db.ExecContext(ctx, `DELETE FROM pairings WHERE expires_at < ?`, s.ms())
}

func (s *store) startPairing(ctx context.Context) (pairID, code string, expires time.Duration, err error) {
	s.expirePairings(ctx)
	var n int
	if err = s.db.QueryRowContext(ctx, `SELECT COUNT(*) FROM pairings WHERE state='pending'`).Scan(&n); err != nil {
		return
	}
	if n >= maxPending {
		return "", "", 0, errBusy
	}
	for {
		code = sixDigits()
		var x int
		if s.db.QueryRowContext(ctx, `SELECT 1 FROM pairings WHERE code=?`, code).Scan(&x) != nil {
			break
		}
	}
	pairID = randomHex(16)
	now := s.ms()
	_, err = s.db.ExecContext(ctx,
		`INSERT INTO pairings (pair_id, code, state, created_at, expires_at) VALUES (?, ?, 'pending', ?, ?)`,
		pairID, code, now, now+pairTTL.Milliseconds())
	return pairID, code, pairTTL, err
}

var errBusy = errors.New("busy")

func sixDigits() string {
	b := make([]byte, 4)
	for {
		rand.Read(b)
		v := uint32(b[0])<<24 | uint32(b[1])<<16 | uint32(b[2])<<8 | uint32(b[3])
		if v < 4_294_000_000 { // unbiased 000000-999999
			return fmt.Sprintf("%06d", v%1_000_000)
		}
	}
}

// approvePairing creates the device and its token for a pending code.
func (s *store) approvePairing(ctx context.Context, code, name string) (string, error) {
	s.expirePairings(ctx)
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return "", err
	}
	defer tx.Rollback()
	var pairID string
	err = tx.QueryRowContext(ctx, `SELECT pair_id FROM pairings WHERE code=? AND state='pending'`, code).Scan(&pairID)
	if errors.Is(err, sql.ErrNoRows) {
		return "", nil
	}
	if err != nil {
		return "", err
	}
	deviceID := "dev-" + randomHex(4)
	token := randomHex(16)
	now := s.ms()
	if _, err = tx.ExecContext(ctx, `INSERT INTO devices (device_id, name, token_hash, created_at) VALUES (?, ?, ?, ?)`,
		deviceID, name, sha256Hex(token), now); err != nil {
		return "", err
	}
	if _, err = tx.ExecContext(ctx,
		`UPDATE pairings SET state='approved', device_id=?, token=?, expires_at=? WHERE pair_id=?`,
		deviceID, token, now+pairTTL.Milliseconds(), pairID); err != nil {
		return "", err
	}
	return deviceID, tx.Commit()
}

// claimPairing hands the token to the phone once, then forgets it.
func (s *store) claimPairing(ctx context.Context, pairID string) (state, deviceID, token string, err error) {
	s.expirePairings(ctx)
	var st string
	var dev, tok sql.NullString
	err = s.db.QueryRowContext(ctx, `SELECT state, device_id, token FROM pairings WHERE pair_id=?`, pairID).
		Scan(&st, &dev, &tok)
	if errors.Is(err, sql.ErrNoRows) {
		return "expired", "", "", nil
	}
	if err != nil {
		return "", "", "", err
	}
	if st != "approved" || !tok.Valid {
		return "pending", "", "", nil
	}
	if _, err = s.db.ExecContext(ctx, `DELETE FROM pairings WHERE pair_id=?`, pairID); err != nil {
		return "", "", "", err
	}
	return "approved", dev.String, tok.String, nil
}

// ------------------------------------------------------------ history

const (
	listConversations = 20
	maxPinned         = 10
	titleChars        = 48
	historyBytes      = 6000 // S40 body budget for one history answer (phone reads <= 8 KiB)
	historyMsgChars   = 1200 // longer messages are shortened in the history view
)

type convInfo struct {
	id       string
	updated  int64
	messages int
	title    string
	pinned   bool
}

// conversations: the device's pinned conversations, then the newest ones,
// with a title taken from the first user message.
func (s *store) conversations(ctx context.Context, device string) ([]convInfo, error) {
	rows, err := s.db.QueryContext(ctx, `SELECT c.id, c.updated_at, c.pinned,
		  (SELECT COUNT(*) FROM messages m WHERE m.device_id=c.device_id AND m.conversation_id=c.id),
		  COALESCE((SELECT content FROM messages m WHERE m.device_id=c.device_id AND m.conversation_id=c.id
		    AND m.role='user' ORDER BY seq LIMIT 1), '')
		FROM conversations c WHERE c.device_id=? ORDER BY c.pinned DESC, c.updated_at DESC, c.rowid DESC LIMIT ?`,
		device, listConversations)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []convInfo
	for rows.Next() {
		var c convInfo
		var pinned int
		if err := rows.Scan(&c.id, &c.updated, &pinned, &c.messages, &c.title); err != nil {
			return nil, err
		}
		c.pinned = pinned == 1
		if c.messages == 0 {
			continue // created, but the first exchange failed
		}
		c.title, _ = limitChars(strings.Join(strings.Fields(c.title), " "), titleChars)
		out = append(out, c)
	}
	return out, rows.Err()
}

// history: the newest messages of a conversation that fit in historyBytes,
// oldest first. older is true if earlier messages were left out. ok is
// false if the conversation is unknown to this device.
func (s *store) history(ctx context.Context, device, conv string) (msgs []turn, older, ok bool, err error) {
	var x int
	if s.db.QueryRowContext(ctx, `SELECT 1 FROM conversations WHERE device_id=? AND id=?`, device, conv).Scan(&x) != nil {
		return nil, false, false, nil
	}
	rows, err := s.db.QueryContext(ctx, `SELECT role, content, image_id FROM messages
		WHERE device_id=? AND conversation_id=? ORDER BY seq DESC`, device, conv)
	if err != nil {
		return nil, false, false, err
	}
	defer rows.Close()
	var rev []turn
	size := 0
	for rows.Next() {
		var t turn
		if err := rows.Scan(&t.role, &t.content, &t.imageID); err != nil {
			return nil, false, false, err
		}
		t.content, _ = limitChars(t.content, historyMsgChars)
		size += len(t.content) + 12
		if size > historyBytes {
			older = true
			break
		}
		rev = append(rev, t)
	}
	for i := len(rev) - 1; i >= 0; i-- {
		msgs = append(msgs, rev[i])
	}
	return msgs, older, true, rows.Err()
}

// setPinned pins or unpins a conversation of this device. Pinned
// conversations are listed first and are not removed by the 30-day expiry
// or the per-device limit. status: "ok", "conversation_not_found" or
// "pin_limit" (already maxPinned pinned).
func (s *store) setPinned(ctx context.Context, device, conv string, pin bool) (string, error) {
	var cur int
	err := s.db.QueryRowContext(ctx, `SELECT pinned FROM conversations WHERE device_id=? AND id=?`, device, conv).Scan(&cur)
	if errors.Is(err, sql.ErrNoRows) {
		return "conversation_not_found", nil
	}
	if err != nil {
		return "", err
	}
	if pin && cur == 0 {
		var n int
		if err := s.db.QueryRowContext(ctx, `SELECT COUNT(*) FROM conversations WHERE device_id=? AND pinned=1`,
			device).Scan(&n); err != nil {
			return "", err
		}
		if n >= maxPinned {
			return "pin_limit", nil
		}
	}
	_, err = s.db.ExecContext(ctx, `UPDATE conversations SET pinned=? WHERE device_id=? AND id=?`, b2i(pin), device, conv)
	return "ok", err
}

// ------------------------------------------------------------ search

const (
	maxSearchResults = 20
	maxSearchWords   = 5
	snippetBefore    = 25
	snippetAfter     = 50
)

type searchHit struct {
	id      string
	updated int64
	matches int // messages that contain the first word
	snippet string
}

// fold makes search forgiving for keypad typing: lower case, and Turkish
// letters match their plain Latin forms (ı/i/İ/I -> i, ş -> s, ğ -> g, ...).
// It maps every rune to exactly one rune, so positions stay the same.
func fold(r rune) rune {
	switch r {
	case 'I', 'İ', 'ı', 'î', 'Î':
		return 'i'
	case 'ş', 'Ş':
		return 's'
	case 'ğ', 'Ğ':
		return 'g'
	case 'ç', 'Ç':
		return 'c'
	case 'ö', 'Ö':
		return 'o'
	case 'ü', 'Ü', 'û', 'Û':
		return 'u'
	case 'â', 'Â':
		return 'a'
	case '\n', '\r', '\t':
		return ' '
	}
	return unicode.ToLower(r)
}

func foldRunes(s string) []rune {
	r := []rune(s)
	for i, c := range r {
		r[i] = fold(c)
	}
	return r
}

func runeIndex(hay, needle []rune) int {
	for i := 0; i+len(needle) <= len(hay); i++ {
		match := true
		for j := range needle {
			if hay[i+j] != needle[j] {
				match = false
				break
			}
		}
		if match {
			return i
		}
	}
	return -1
}

// search finds the device's conversations that contain every word of the
// query (in any of their messages), newest first, with a snippet around the
// first word. Nothing here calls Claude.
func (s *store) search(ctx context.Context, device, query string) ([]searchHit, error) {
	var words [][]rune
	for _, w := range strings.Fields(string(foldRunes(query))) {
		if len(words) < maxSearchWords {
			words = append(words, []rune(w))
		}
	}
	if len(words) == 0 {
		return nil, nil
	}
	rows, err := s.db.QueryContext(ctx, `SELECT c.id, c.updated_at, m.content FROM conversations c
		JOIN messages m ON m.device_id=c.device_id AND m.conversation_id=c.id
		WHERE c.device_id=? ORDER BY c.pinned DESC, c.updated_at DESC, c.id, m.seq`, device)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []searchHit
	var cur *searchHit
	found := make([]bool, len(words))
	flush := func() {
		if cur == nil || len(out) >= maxSearchResults {
			return
		}
		for _, f := range found {
			if !f {
				return
			}
		}
		out = append(out, *cur)
	}
	for rows.Next() {
		var id, content string
		var updated int64
		if err := rows.Scan(&id, &updated, &content); err != nil {
			return nil, err
		}
		if cur == nil || cur.id != id {
			flush()
			cur = &searchHit{id: id, updated: updated}
			for i := range found {
				found[i] = false
			}
		}
		folded := foldRunes(content)
		for i, w := range words {
			at := runeIndex(folded, w)
			if at < 0 {
				continue
			}
			found[i] = true
			if i == 0 {
				cur.matches++
				if cur.snippet == "" {
					cur.snippet = snippet([]rune(content), at, len(w))
				}
			}
		}
	}
	flush()
	return out, rows.Err()
}

// snippet: the text around [at, at+n) on one line, "..." where it was cut.
func snippet(r []rune, at, n int) string {
	start := max(0, at-snippetBefore)
	end := min(len(r), at+n+snippetAfter)
	for start > 0 && start < at && r[start-1] != ' ' && r[start-1] != '\n' {
		start++
	}
	for end < len(r) && end > at+n && r[end] != ' ' && r[end] != '\n' {
		end--
	}
	s := strings.Join(strings.Fields(string(r[start:end])), " ")
	if start > 0 {
		s = "..." + s
	}
	if end < len(r) {
		s += "..."
	}
	return s
}

// ------------------------------------------------------------ retention

const (
	conversationTTL = 30 * 24 * time.Hour
	requestTTL      = 7 * 24 * time.Hour
	usageTTL        = 90 * 24 * time.Hour
	maxConversation = 50
)

func (s *store) cleanup(ctx context.Context) error {
	now := s.ms()
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	old := now - conversationTTL.Milliseconds()
	stmts := []struct {
		q    string
		args []any
	}{
		{`DELETE FROM messages WHERE (device_id, conversation_id) IN
		    (SELECT device_id, id FROM conversations WHERE updated_at < ? AND pinned=0)`, []any{old}},
		{`DELETE FROM requests WHERE state != 'pending' AND (device_id, conversation_id) IN
		    (SELECT device_id, id FROM conversations WHERE updated_at < ? AND pinned=0)`, []any{old}},
		{`DELETE FROM conversations WHERE updated_at < ? AND pinned=0`, []any{old}},
		{`DELETE FROM requests WHERE created_at < ? AND state != 'pending'`, []any{now - requestTTL.Milliseconds()}},
		{`DELETE FROM transcripts WHERE created_at < ? AND state != 'pending'`, []any{now - transcriptTTL.Milliseconds()}},
		// photos never used in a message, and photos whose conversation is gone
		{`DELETE FROM images WHERE conversation_id='' AND created_at < ?`, []any{now - unusedImageTTL}},
		{`DELETE FROM images WHERE conversation_id != '' AND (device_id, conversation_id) NOT IN
		    (SELECT device_id, id FROM conversations)`, nil},
		{`DELETE FROM usage WHERE day < ?`, []any{utcDay(now - usageTTL.Milliseconds())}},
		{`DELETE FROM pairings WHERE expires_at < ?`, []any{now}},
	}
	for _, st := range stmts {
		if _, err := tx.ExecContext(ctx, st.q, st.args...); err != nil {
			return err
		}
	}
	return tx.Commit()
}

func (s *store) deleteConversation(ctx context.Context, device, conv string) error {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	for _, q := range []string{
		`DELETE FROM messages WHERE device_id=? AND conversation_id=?`,
		`DELETE FROM images WHERE device_id=? AND conversation_id=?`,
		`DELETE FROM requests WHERE device_id=? AND conversation_id=? AND state != 'pending'`,
		`DELETE FROM conversations WHERE device_id=? AND id=?`,
	} {
		if _, err := tx.ExecContext(ctx, q, device, conv); err != nil {
			return err
		}
	}
	return tx.Commit()
}

// trimConversations keeps the newest maxConversation unpinned conversations
// per device (pinned ones are not counted and never removed).
func (s *store) trimConversations(ctx context.Context, device string) error {
	rows, err := s.db.QueryContext(ctx,
		`SELECT id FROM conversations WHERE device_id=? AND pinned=0 ORDER BY updated_at DESC, rowid DESC LIMIT -1 OFFSET ?`,
		device, maxConversation)
	if err != nil {
		return err
	}
	var ids []string
	for rows.Next() {
		var id string
		rows.Scan(&id)
		ids = append(ids, id)
	}
	rows.Close()
	for _, id := range ids {
		if err := s.deleteConversation(ctx, device, id); err != nil {
			return err
		}
	}
	return nil
}

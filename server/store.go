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
	"time"

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
	s := &store{db: db, now: time.Now}
	// a request left "pending" by a restart may or may not have been billed
	if _, err := db.Exec(`UPDATE requests SET state='uncertain', error='uncertain' WHERE state='pending'`); err != nil {
		db.Close()
		return nil, err
	}
	return s, nil
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
		    (SELECT device_id, id FROM conversations WHERE updated_at < ?)`, []any{old}},
		{`DELETE FROM requests WHERE state != 'pending' AND (device_id, conversation_id) IN
		    (SELECT device_id, id FROM conversations WHERE updated_at < ?)`, []any{old}},
		{`DELETE FROM conversations WHERE updated_at < ?`, []any{old}},
		{`DELETE FROM requests WHERE created_at < ? AND state != 'pending'`, []any{now - requestTTL.Milliseconds()}},
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
		`DELETE FROM requests WHERE device_id=? AND conversation_id=? AND state != 'pending'`,
		`DELETE FROM conversations WHERE device_id=? AND id=?`,
	} {
		if _, err := tx.ExecContext(ctx, q, device, conv); err != nil {
			return err
		}
	}
	return tx.Commit()
}

// trimConversations keeps the newest maxConversation per device.
func (s *store) trimConversations(ctx context.Context, device string) error {
	rows, err := s.db.QueryContext(ctx,
		`SELECT id FROM conversations WHERE device_id=? ORDER BY updated_at DESC LIMIT -1 OFFSET ?`,
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

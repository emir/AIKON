package main

// Photos (0.6.0): the phone uploads a photo (camera snapshot or a file from
// the gallery) to /v1/image and gets an id back; a later /v1/chat message
// names it with "image: <id>". Uploading never calls Claude.
//
// The server decodes the photo (JPEG or PNG), scales it down to at most
// maxImageEdge pixels on the long side and stores it as JPEG. It stays with
// the conversation it is first used in, so follow-up questions still show
// it to Claude (the newest contextImages photos of a conversation), and it
// is deleted with that conversation. A photo that is never used is deleted
// after unusedImageTTL. The same photo uploaded again gets the same id.

import (
	"bytes"
	"context"
	"database/sql"
	"errors"
	"image"
	"image/jpeg"
	_ "image/png"
	"net/http"
	"regexp"

	"golang.org/x/image/draw"
)

const (
	maxImageUpload = 1 << 20      // a 2 MP phone camera JPEG is 300-700 KB
	maxImagePixels = 20 * 1000000 // decode limit (a crafted header cannot make us allocate more)
	maxImageEdge   = 1024         // long side sent to Claude (~1000 input tokens per photo)
	jpegQuality    = 85
	contextImages  = 3 // newest photos of a conversation sent with each message
	unusedImageTTL = 24 * 60 * 60 * 1000
)

var imageIDRE = regexp.MustCompile(`^[0-9a-f]{32}$`)

var errBadImage = errors.New("bad image")

type storedImage struct {
	data          []byte
	width, height int
}

// processImage decodes a JPEG or PNG, scales it down to maxImageEdge and
// returns it as JPEG. A JPEG that is already small enough is kept as it
// is (re-encoding would only make it larger).
func processImage(b []byte) (storedImage, error) {
	cfg, format, err := image.DecodeConfig(bytes.NewReader(b))
	if err != nil || (format != "jpeg" && format != "png") || cfg.Width < 1 || cfg.Height < 1 ||
		cfg.Width*cfg.Height > maxImagePixels {
		return storedImage{}, errBadImage
	}
	src, _, err := image.Decode(bytes.NewReader(b))
	if err != nil {
		return storedImage{}, errBadImage
	}
	w, h := src.Bounds().Dx(), src.Bounds().Dy()
	if format == "jpeg" && w <= maxImageEdge && h <= maxImageEdge {
		return storedImage{data: b, width: w, height: h}, nil
	}
	nw, nh := w, h
	if w > maxImageEdge || h > maxImageEdge {
		if w >= h {
			nw, nh = maxImageEdge, max(1, h*maxImageEdge/w)
		} else {
			nw, nh = max(1, w*maxImageEdge/h), maxImageEdge
		}
	}
	dst := image.NewRGBA(image.Rect(0, 0, nw, nh))
	draw.CatmullRom.Scale(dst, dst.Bounds(), src, src.Bounds(), draw.Src, nil)
	var out bytes.Buffer
	if err := jpeg.Encode(&out, dst, &jpeg.Options{Quality: jpegQuality}); err != nil {
		return storedImage{}, errBadImage
	}
	return storedImage{data: out.Bytes(), width: nw, height: nh}, nil
}

// ------------------------------------------------------------ store

// saveImage stores a processed photo; the same upload again returns the
// existing id while that photo is not part of a conversation yet. limited is true if the daily upload limit is used up.
func (s *store) saveImage(ctx context.Context, device, sha string, img storedImage, limit int) (id string, limited bool, err error) {
	if s.db.QueryRowContext(ctx, `SELECT id FROM images WHERE device_id=? AND sha=? AND conversation_id=''`, device, sha).Scan(&id) == nil {
		return id, false, nil
	}
	now := s.ms()
	var n int
	s.db.QueryRowContext(ctx, `SELECT images FROM usage WHERE device_id=? AND day=?`, device, utcDay(now)).Scan(&n)
	if n >= limit {
		return "", true, nil
	}
	id = randomHex(16)
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return "", false, err
	}
	defer tx.Rollback()
	if _, err := tx.ExecContext(ctx, `INSERT INTO images (device_id, id, sha, created_at, conversation_id, width, height, data)
		VALUES (?, ?, ?, ?, '', ?, ?, ?)`, device, id, sha, now, img.width, img.height, img.data); err != nil {
		return "", false, err
	}
	if _, err := tx.ExecContext(ctx, `INSERT INTO usage (device_id, day, images) VALUES (?, ?, 1)
		ON CONFLICT(device_id, day) DO UPDATE SET images = images + 1`, device, utcDay(now)); err != nil {
		return "", false, err
	}
	return id, false, tx.Commit()
}

// imageFor returns a photo the device may use in conversation conv: not yet
// used anywhere, or already part of conv.
func (s *store) imageFor(ctx context.Context, device, id, conv string) ([]byte, bool, error) {
	var data []byte
	var owner string
	err := s.db.QueryRowContext(ctx, `SELECT data, conversation_id FROM images WHERE device_id=? AND id=?`,
		device, id).Scan(&data, &owner)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, false, nil
	}
	if err != nil {
		return nil, false, err
	}
	if owner != "" && owner != conv {
		return nil, false, nil
	}
	return data, true, nil
}

// ------------------------------------------------------------ handler

// imageHandler: POST /v1/image, body = a JPEG or PNG. Answer: "image: <id>",
// the stored size and "remaining" (uploads left today). Never calls Claude.
func (s *server) imageHandler(w http.ResponseWriter, r *http.Request) {
	device, ok := s.authDevice(w, r)
	if !ok {
		return
	}
	b, ok := readLimited(r, maxImageUpload)
	if !ok {
		writeS40(w, 413, []kv{{"status", "too_large"}, {"max", maxImageUpload}}, "")
		return
	}
	img, err := processImage(b)
	logJSON(map[string]any{"evt": "image", "bytes": len(b), "stored": len(img.data), "w": img.width, "h": img.height,
		"ok": err == nil})
	if err != nil {
		writeS40(w, 400, []kv{{"status", "bad_image"}}, "")
		return
	}
	id, limited, err := s.st.saveImage(r.Context(), device, sha256Hex(string(b)), img, s.cfg.imageLimit)
	switch {
	case err != nil:
		writeS40(w, 500, []kv{{"status", "server_error"}}, "")
	case limited:
		writeS40(w, 429, []kv{{"status", "limit"}, {"remaining", 0}}, "")
	default:
		writeS40(w, 200, []kv{{"status", "ok"}, {"image", id}, {"width", img.width}, {"height", img.height},
			{"bytes", len(img.data)}}, "")
	}
}

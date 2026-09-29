# Architecture

## Why a private CA and our own TLS endpoint

Measured on a Nokia 6300 (RM-217, V06.60) against our server:

```
ClientHello: TLS 1.0 only, no SNI, no extensions
cipher suites: RC4-MD5, RC4-SHA, 3DES-EDE-CBC-SHA, AES128-CBC-SHA, AES256-CBC-SHA
```

and the phone's certificate store contains only VeriSign/Thawte/Equifax/
GeoTrust roots from the late 1990s and 2000s (expired, or distrusted
Symantec roots under which no public CA issues TLS certificates today).

Consequences:

- Cloudflare (tested with `*.workers.dev`) serves only ECDSA certificates to
  such a client and requires SNI → handshake failure before a certificate
  is even sent. Other CDNs/PaaS that terminate TLS behave similarly.
- No publicly obtainable certificate chains to a root the phone trusts.

So the server terminates TLS itself with an RSA-2048 certificate from a
private root CA that the user saves on the phone once. Server TLS policy:
TLS 1.0-1.3; `TLS_RSA_WITH_AES_128/256_CBC_SHA` for the phone (the phone
negotiates AES128-SHA); ECDHE-RSA suites for modern clients; no RC4, no
3DES, no plain HTTP listener. With the root saved, the 6300 verifies an
SHA-1 root + SHA-1 server certificate (SHA-256 untested). Before the root
is saved it reports "verification failed" (not "unknown issuer").

## Components

```
app/     Java ME MIDlet (CLDC 1.1, MIDP 2.0, class file 46.0)
server/  Go: phone TLS listener, chat service, SQLite store, admin API
```

### Phone app

- High-level `List`/`Form`/`TextBox` for input and settings; custom
  `Canvas` screens (splash, home, chat) sized from `getWidth()/getHeight()`,
  softkeys as `Command`s, arrows via `getGameAction()`. Replies are laid
  out by `Text.layout`: paragraphs, "- " items with a dot and "1. " items
  with their number, both with a hanging indent. Reading mode pages through
  one reply with whole lines only and keeps its place by character offset
  (across text-size changes and loading the rest) and keeps the backlight
  on with `Display.flashBacklight` until a minute without a key press.
  Message actions only prefill the editor; every paid request still needs
  the user's Send. A reply line `EVENT: YYYY-MM-DD HH:MM | title` or
  `TODO: YYYY-MM-DD | title` (see "Calendar entries") is shown in a
  readable form (`Cal`). Single-line texts are
  cut with "..." (`Text.fit`) so nothing runs off small screens.
- Networking on worker threads only, one request at a time, HTTPS only
  (`https://` enforced, no fallback), response body capped at 8 KiB. Every
  request is counted in RMS `cs40data` (`DataUsage`: requests and bytes today
  and since a reset; bodies exact, HTTP headers estimated, the TLS
  handshake not counted).
- Optional JSR 75 (0.7.0): `Files` (FileConnection) saves a reply as a UTF-8
  `.txt` in `ClaudeS40/` on the memory card, else the phone's image folder,
  else the app's private folder, and lists/reads/deletes them ("Saved",
  offline); `Pim` writes one calendar event or to-do the user confirmed in
  `CalendarForm`. Only these two classes touch JSR 75 (`tools/check.py`),
  and only after `microedition.io.file.FileConnection.version` /
  `microedition.pim.version` say the phone has it; both run on worker
  threads (the phone asks the user for permission). No JAD permission is
  needed for an unsigned MIDlet. The FileConnection API comes from the
  pinned MicroEmulator jar, the PIM API from compile-only stubs
  (`app/stubs/jsr75-pim`, constants from the PIM 1.0 specification); neither
  is packaged.
- Optional JSR 135 recording (0.8.0): `Rec` records a voice message into
  RAM with `RecordControl` (AMR if `audio.encodings` offers it, else 8 kHz
  PCM WAV), only when `supports.audio.capture` is true and the class
  exists, on a worker thread (the phone asks for microphone access).
  `Dictation` stops at 30 s, uploads the clip to `/v1/transcribe` and opens
  the returned text in the editor for the user to check and send. `Rec` is
  the only JSR 135 user (`tools/check.py`); the API comes from a
  compile-only stub (`app/stubs/jsr135-record`), not packaged.
- Stored on the phone (RMS `cs40cfg`): server URL, access token, verified
  URL, test mode, theme, text size, sound, vibration, language, web search
  on/off, "keep last chat", setup wizard done, the two backlight options,
  the user's notes for Claude (format 6; records before format 5 count as
  set up). The setup part (URL, token, verified URL, setup done, language,
  notes) is also written by `Backup` to `ClaudeS40/claude-s40-setup.dat`
  (memory card or image folder, never the app's private folder) whenever
  it changes, and restored at the first start after an install, so a new
  build needs no new pairing; "Reset setup" deletes it. Only with "keep last chat" on, RMS `cs40chat`
  holds the last conversation (id + newest user/Claude messages, ≤ 8000
  characters) for offline reading; turning it off deletes it. Deleting the
  app deletes both.
- `L.s("Türkçe", "English")`: language from `microedition.locale` or the
  Settings choice; changing it rebuilds the screens at once.
- Packaging is deterministic; `tools/check.py` verifies manifest/JAD
  consistency, class version, preverification (StackMap, no jsr/ret), every
  referenced class/member against the CLDC/MIDP (+ JSR 75/135) API, JSR 75
  only in `Files`/`Pim`, JSR 135 recording only in `Rec`, only an optional HTTPS permission, no `http://` constants, and scans all bytes for secrets.

### Server

- `main.go` wiring, TLS policy, handlers; `protocol.go` S40/1 format;
  `chat.go` chat rules; `store.go` SQLite; `claude.go` SDK call + mock;
  `transcribe.go` voice messages (audio checks, ffmpeg, speech-to-text);
  `sanitize.go` plain-text replies for a 240x320 screen.
- Admin API on a separate listener, bound to 127.0.0.1 on the host,
  protected by a token generated on the server; used through SSH
  (`deploy/admin.sh`).

## S40/1 protocol

Every request and response body:

```
S40/1\n
key: value\n        (zero or more, lower-case ASCII keys)
\n
free text (UTF-8)
```

Status is in the body (`status:`) and the HTTP code (operator proxies may
rewrite non-200 responses). Responses are `Cache-Control: no-store`.

| Route | Auth | Purpose |
|---|---|---|
| `GET /health` | – | server, version, mock flag, the TLS version/cipher of *this* connection |
| `POST /echo` | – | ≤ 512 bytes strict UTF-8, echoed; `probe: match` for the Turkish test string |
| `POST /v1/pair/start` | – | → `pair` (128-bit secret), `code` (6 digits, shown on the phone), `expires` |
| `POST /v1/pair/claim` | – | body `pair: <id>` → `pending` / `ok` + `device`, `token` (once) / `expired` |
| `POST /v1/chat` | Bearer token | `request: <id>`, `conversation: <id or empty>`, optional `search: 0` (no web search for this message), `instructions: <the user's notes>` (≤ 300 characters, added to the system prompt), `calendar: 1` + `local-time: YYYY-MM-DD HH:MM` (the phone can add calendar entries; 0.4.0), text = message |
| `POST /v1/more` | Bearer token | `request: <id>`, `offset: <next>` → the next part of a stored reply (never calls Claude) |
| `POST /v1/conversations` | Bearer token | pinned, then newest conversations (20 in all), one line each: `id TAB updated-ms TAB messages TAB title`; with `pins: 1` (0.7+ phones) each line starts with `pinned TAB` (0/1) |
| `POST /v1/history` | Bearer token | `conversation: <id>` → newest messages (≤ 6000 bytes), oldest first, each `u N` / `a N` (N = UTF-16 length), newline, text, newline; `older: 1` if earlier ones were left out |
| `POST /v1/delete` | Bearer token | `conversation: <id>` |
| `POST /v1/pin` | Bearer token | `conversation: <id>`, `pinned: 1` or `0` → `ok` / `conversation_not_found` / `pin_limit` (+ `max`) (0.4.0) |
| `POST /v1/transcribe?request=<id>&lang=tr\|en` | Bearer token | body = a voice clip (AMR or WAV, ≤ 640 KiB, ≤ 35 s), recognised by its first bytes → `ok` + `ms` (clip length), `mock`, `remaining` (voice messages left today), text = the transcript; never calls Claude (0.5.0) |
| `POST /v1/search` | Bearer token | text = query (2-100 characters) → conversations containing every word, one line each: `id TAB updated-ms TAB matching-messages TAB snippet`; case- and Turkish-letter-insensitive (`sise` finds `Şişe`); never calls Claude (0.4.0) |

Chat statuses: `ok` (fields `conversation`, `truncated`, `refused`, `mock`,
`replayed`, `remaining`, and since 0.3.0 `more` + `next` while parts of a
long reply are left, `searched` = number of web searches), `pending`, `busy`, `limit`, `conversation_full`,
`conversation_not_found`, `request_mismatch`, `rate_limited`, `overloaded`,
`billing`, `upstream_error`, `config_error`, `uncertain`, plus input errors.

### Long replies in parts (0.3.0)

The server stores up to 8000 characters of a reply and sends it in parts of
≤ 2000 characters (the phone reads at most 8 KiB). The first part comes with
`/v1/chat` (and with a replay); `more: 1` + `next: <offset>` mean another
part can be fetched with `/v1/more`. `truncated` is `1` on the first part
while parts are left, so 0.3.x phones (which ignore `more`) still say the
reply is incomplete; on later parts it means the stored reply itself was
cut (length or token limit). Phones show "shortened" only when
`truncated: 1` and no `more`.

### Web search (0.3.0)

Anthropic's server-side web search tool (`web_search_20260209`) is offered
when `WEB_SEARCH=1` (default), the phone did not send `search: 0`, and the
device has searches left today (`DAILY_SEARCH_LIMIT`, default 30, UTC day).
At most `WEB_SEARCH_MAX_USES` (default 3) searches per message; Claude
decides whether to search. Searches are billed per use and their results
count as input tokens. A `pause_turn` is continued once (a continuation of
the same turn, not a retry); if it fails, the answer so far is returned
with `truncated`. Only the text after the last search is kept (narration
like "let me look" is dropped) and up to three cited host names are
appended as `Web: a.com, b.org`. Optional approximate location for local
results: `SEARCH_COUNTRY`, `SEARCH_CITY`, `SEARCH_TIMEZONE`.

### Calendar entries (0.4.0)

A phone with the JSR 75 PIM API sends `calendar: 1` and its clock. The
system prompt then tells Claude that, only when the user asks to add
something to the calendar, a reminder or a to-do, the answer ends with one
line `EVENT: YYYY-MM-DD HH:MM | title` or `TODO: YYYY-MM-DD | title`, using
the phone's local date for "tomorrow" and weekdays. The phone shows that
line readably, selects the reply and offers "Add to calendar", which opens a
form prefilled from it (title, date and time, calendar or to-do, alarm).
Nothing is written to the phone's calendar before the user saves the form;
the message actions offer the same form for any message.

### Voice messages (0.5.0)

The phone records up to 30 s and posts the clip to `/v1/transcribe`. The
server checks it (AMR frame sequence or WAV header, length 0.3–35 s),
turns AMR (and non-PCM WAV) into 16 kHz mono PCM with ffmpeg, wraps it in
a clean WAV header and sends it to the speech-to-text service
(`TRANSCRIBE=openai`: OpenAI's `/v1/audio/transcriptions`, model
`TRANSCRIBE_MODEL`, default `gpt-4o-mini-transcribe`, language from the
phone's UI language). The text comes back as one paragraph (control
characters, emoji and non-BMP removed, ≤ 996 characters). Nothing is sent
to Claude: the phone puts the text in the editor and the user checks,
corrects and sends it as a normal message.

The image carries its own ffmpeg, built from pinned source with only the
AMR/WAV demuxers and decoders, raw PCM output and the pipe protocol (no
network, no other formats); it runs on stdin/stdout with a 15 s timeout.
The audio exists only in memory for the request. `TRANSCRIBE=mock`
answers "[Test mode] ..." without any service; `off` (default) answers
`unavailable`.

Statuses: `ok`, `pending`, `busy`, `limit`, `request_mismatch`,
`no_speech`, `bad_audio`, `too_short`, `too_long`, `too_large`,
`unavailable`, `rate_limited`, `overloaded`, `upstream_error`,
`config_error`, `billing`, `uncertain`.

## Paid calls: idempotency without "exactly once"

- The phone creates a `request` id per message and keeps it until a
  definite answer; "Retry" re-sends the **same** id.
- The server records the id as `pending` and counts it **before** calling
  Claude; a repeated id is answered from the record (`replayed: 1`) and
  never triggers a second call.
- The SDK runs with `maxRetries = 0` and a 60 s timeout. Timeouts and lost
  connections become `uncertain` and are never retried automatically; the
  phone keeps the draft, and sending it again is a new (paid) request by
  the user's choice. A request still `pending` after a restart becomes
  `uncertain`.
- The Messages API has no idempotency key, so there is no exactly-once
  guarantee.
- Voice messages follow the same rules with their own records: the phone
  keeps the clip and its `request` id while the screen is open, "Retry"
  sends the same id and clip (answered from the record, SHA-256 of the
  audio must match), "Send again" after a definite failure or `uncertain`
  is a new id by the user's choice. The speech-to-text call has no retries
  and a 60 s timeout; no HTTP answer means `uncertain`.

## Data and limits

| | |
|---|---|
| message | ≤ 1000 characters (request body ≤ 6 KiB, room for the user's notes) |
| reply | sanitised (no Markdown/emoji/non-BMP), ≤ 8000 characters stored, sent in parts of ≤ 2000, `truncated` flag |
| model | `CLAUDE_MODEL`, `max_tokens` 2048, `effort` from `CLAUDE_EFFORT`, optional server-side refusal fallback |
| context | newest 16 messages and ≤ 16000 characters of the conversation |
| voice message | ≤ 30 s on the phone, ≤ 35 s and ≤ 640 KiB on the server; audio never stored; transcript ≤ 996 characters |
| per device | 1 request and 1 voice message in flight, daily requests, output tokens, web searches and voice messages (`DAILY_TRANSCRIBE_LIMIT`, default 30; UTC day), ≤ 40 messages per conversation, ≤ 50 conversations plus ≤ 10 pinned |
| retention | conversations 30 days after the last message (pinned ones until unpinned or deleted), request records 7 days, transcripts 1 day (for replays only), usage 90 days (cleanup every 6 h) |

All user-data tables are keyed by `device_id`: a conversation id is only
visible to the device that created it.

## Logs

JSON lines: start, TLS ClientHello summary (SNI present, versions, number of
suites), each request (method, path, status, duration, negotiated TLS),
upstream errors (HTTP status, Anthropic error type/message, request id),
voice clips (format, bytes, length in ms) and speech-to-text errors (HTTP
status, error type/code/message, request id).
No message text, replies, transcripts, audio, tokens, keys or client IP
addresses.

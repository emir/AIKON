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
  softkeys as `Command`s, arrows via `getGameAction()` (through `Keys`,
  which first maps the letter codes of QWERTY S60 phones such as the Nokia
  E63 to digits and never turns a letter into a game action). Replies are laid
  out by `Text.layout`: paragraphs, "- " items with a dot and "1. " items
  with their number, both with a hanging indent. Reading mode pages through
  one reply with whole lines only and keeps its place by character offset
  (across text-size changes and loading the rest). The backlight is left
  to the phone: `Display.flashBacklight`, the only MIDP way to keep it on,
  blinks a lit Nokia 6300 screen (0.6.0-0.10.0 did that every 8 s while
  reading; removed in 0.10.1).
  Message actions only prefill the editor; every paid request still needs
  the user's Send. A reply line `EVENT: YYYY-MM-DD HH:MM | title` or
  `TODO: YYYY-MM-DD | title` (see "Calendar entries") is shown in a
  readable form (`Cal`). Single-line texts are
  cut with "..." (`Text.fit`) so nothing runs off small screens.
- Explanations on request (0.10.2): chat notes (info and errors) are one
  short line; when there is more to say a small circled "i" follows, 1/3
  selects the note and the centre key opens the explanation. Setup steps,
  Pairing, Settings, Data usage, About, Photo and Dictation show one short
  line at most; the longer text is behind "Bilgi" / "Info" (`Help`,
  `Command.HELP`).
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
  the only JSR 135 recording user (`tools/check.py`); the API comes from
  compile-only stubs (`app/stubs/jsr135`), not packaged.
- Photos (0.9.0): `Cam` shows a viewfinder (JSR 135 `VideoControl`,
  `capture://image`, which the Nokia 6300 needs for snapshots, else
  `capture://video`) and takes a 640x480 JPEG snapshot (simpler snapshot
  parameters as fallbacks), only when `supports.video.capture` is true and
  the class exists; `Cam` is the only JSR 135 camera user. `PhotoPicker`
  lists folders and .jpg/.jpeg/.png files through `Files` (starting in
  `fileconn.dir.photos`), files up to 1 MB, no thumbnails. `Photo` uploads
  the bytes to `/v1/image` and attaches the returned id to the next
  message; the editor then shows "Message with a photo" and offers "Remove
  the photo". A message sent with a photo is shown with "[Photo]".
- Stored on the phone (RMS `cs40cfg`): server URL, access token, verified
  URL, test mode, theme, text size, sound, vibration, language, web search
  on/off, "keep last chat", setup wizard done, the backlight option (light up for a reply; the old reading-mode one is kept unused),
  the user's notes for Claude (format 6; records before format 5 count as
  set up), full screen for the menu and chat (format 7, 0.10.5; on by
  default: the canvases cover the phone's status bar, the softkeys stay
  the phone's Commands). The setup part (URL, token, verified URL, setup done, language,
  notes) is also written by `Backup` to `ClaudeS40/claude-s40-setup.dat`
  (memory card or image folder, never the app's private folder) whenever
  it changes, and restored at the first start after an install, so a new
  build needs no new pairing; "Reset setup" deletes it. Only with "keep last chat" on, RMS `cs40chat`
  holds the last conversation (id + newest user/model messages with the
  model name of each reply, format 2, ≤ 8000 characters) for offline
  reading; turning it off deletes it. Deleting the app deletes both.
- Models (0.10.0, server 0.7.0+): `Models` keeps the server's model list
  (`/v1/models`) and the model chosen last for new chats in RMS
  `cs40models` (format 2 with each model's provider); it is fetched only
  when the picker opens without a list or with "Refresh the list" ("Reset
  setup" deletes it). The picker lists the providers first (Claude, OpenAI,
  Grok), then that provider's models (0.10.2; one provider: models at
  once). "New chat" opens the picker when more than one model is offered
  (or none is known yet);
  Options > Model switches the open chat from the next message on. The
  choice is sent as `model:` with the next message only (and kept with its
  request id for "Retry"); choosing never sends anything. Replies are
  labelled with the model that wrote them (`model-name`, or `m=` marks in
  `/v1/history`), replies without a name are Claude's (servers before 0.7.0).
- `L.s("Türkçe", "English")`: language from `microedition.locale` or the
  Settings choice; changing it rebuilds the screens at once.
- Packaging is deterministic; `tools/check.py` verifies manifest/JAD
  consistency, class version, preverification (StackMap, no jsr/ret), every
  referenced class/member against the CLDC/MIDP (+ JSR 75/135) API, JSR 75
  only in `Files`/`Pim`, JSR 135 recording only in `Rec`, only an optional HTTPS permission, no `http://` constants, and scans all bytes for secrets.

### Server

- `main.go` wiring, TLS policy, handlers; `protocol.go` S40/1 format;
  `chat.go` chat rules; `meter.go` admits, reserves and settles each paid
  call (default: the per-device daily limits; a reservation counts only while
  the call is pending, so failed and uncertain calls are never settled);
  `store.go` SQLite; `model.go` shared model types, system prompt, mock and the `MODELS` catalog; `anthropic.go` Claude (official SDK); `responses.go` OpenAI and xAI (Responses API, plain HTTP);
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
| `POST /v1/chat` | Bearer token | `request: <id>`, `conversation: <id or empty>`, optional `image: <id>` (a photo from `/v1/image`; 0.6.0), optional `search: 0` (no web search for this message), `instructions: <the user's notes>` (≤ 300 characters, added to the system prompt), `calendar: 1` + `local-time: YYYY-MM-DD HH:MM` (the phone can add calendar entries; 0.4.0), optional `model: <id>` (from `/v1/models`; this and the following messages of the conversation go to that model; server 0.7.0), text = message |
| `POST /v1/more` | Bearer token | `request: <id>`, `offset: <next>` → the next part of a stored reply (never calls a model) |
| `POST /v1/models` | Bearer token | the models the phone may choose, default first, one line each: `id TAB name TAB search (0/1) TAB photos (0/1) TAB provider` (provider name since 0.7.1: Claude, OpenAI, Grok); field `default: <id>`; never calls a model (server 0.7.0) |
| `POST /v1/conversations` | Bearer token | pinned, then newest conversations (20 in all), one line each: `id TAB updated-ms TAB messages TAB title`; with `pins: 1` (0.7+ phones) each line starts with `pinned TAB` (0/1); with `models: 1` the conversation's model id comes before the title (`... messages TAB model TAB title`, empty for conversations from before server 0.7.0) |
| `POST /v1/history` | Bearer token | `conversation: <id>` → newest messages (≤ 6000 bytes), oldest first, each `u N` / `a N` (N = UTF-16 length), newline, text, newline; `older: 1` if earlier ones were left out; with `images: 1` (0.9+ phones) a user message sent with a photo is `u N i`; with `models: 1` a reply is `a N m=<model id>` (server 0.7.0; marks are space-separated, unknown ones are ignored) |
| `POST /v1/image` | Bearer token | body = a JPEG or PNG (≤ 1 MiB) → `ok` + `image` (32 hex), `width`, `height`, `bytes`; `bad_image`, `too_large`, `limit`; never calls Claude (0.6.0) |
| `POST /v1/delete` | Bearer token | `conversation: <id>` |
| `POST /v1/pin` | Bearer token | `conversation: <id>`, `pinned: 1` or `0` → `ok` / `conversation_not_found` / `pin_limit` (+ `max`) (0.4.0) |
| `POST /v1/transcribe?request=<id>&lang=tr\|en` | Bearer token | body = a voice clip (AMR or WAV, ≤ 640 KiB, ≤ 35 s), recognised by its first bytes → `ok` + `ms` (clip length), `mock`, `remaining` (voice messages left today), text = the transcript; never calls Claude (0.5.0) |
| `POST /v1/search` | Bearer token | text = query (2-100 characters) → conversations containing every word, one line each: `id TAB updated-ms TAB matching-messages TAB snippet`; case- and Turkish-letter-insensitive (`sise` finds `Şişe`); never calls Claude (0.4.0) |

Chat statuses: `ok` (fields `conversation`, `truncated`, `refused`, `mock`,
`replayed`, `remaining`, and since 0.3.0 `more` + `next` while parts of a
long reply are left, `searched` = number of web searches, and since server
0.7.0 `model` + `model-name` = the model that answered), `pending`, `busy`, `limit`, `conversation_full`,
`conversation_not_found`, `image_not_found`, `model_unavailable` (the named model is not offered),
`request_mismatch`, `rate_limited`, `overloaded`,
`billing`, `upstream_error`, `config_error`, `uncertain`, plus input errors.

### Models (server 0.7.0)

`MODELS=provider:model-id[=Name],...` lists the models the server offers,
in picker order; the default is the first, or `DEFAULT_MODEL` (0.7.2).
Providers: `anthropic` (Claude, official SDK), `openai` and `xai` (Grok)
through their Responses APIs (`POST /v1/responses`, `store: false`,
photos as `input_image` data URIs, the `web_search` tool, `url_citation`
sources), and `gemini` (0.7.2) through `generateContent`
(`systemInstruction`, photos as `inline_data`, `thinkingLevel` from
`GEMINI_EFFORT`, Grounding with Google Search; sources are the grounding
chunks' site names, the search count is the number of search queries;
there is no per-message search limit). Each provider needs its own key
file (`anthropic_api_key`, `openai_api_key`, `xai_api_key`,
`gemini_api_key`); the server does not start with a model whose key is
missing. An empty
`MODELS` keeps the single `CLAUDE_MODEL` of earlier versions. Names are
ASCII, at most 20 characters; the system prompt introduces the model by
its name. `MODELS` is a curated list of current models, not everything a
provider serves. Claude models get `effort`, server-side fallbacks and
`web_search_20260209`, except Haiku 4.5, which takes none of the first
two and only `web_search_20250305`.

A conversation has a model: a new one starts with the phone's `model:`
or the default; a message with another `model:` switches the
conversation from that message on (the history is plain text, so any
model can continue it). Without `model:` the conversation's own model
answers, or the default if it was removed from `MODELS`. Each stored
reply records its model. The request fingerprint covers `model:` when it
is sent, so the same `request` id with another model is
`request_mismatch`. Daily limits (requests, output tokens, searches) are
shared by all models. Web search is offered only by models whose
provider has it, and only while `WEB_SEARCH=1`.

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

### Photos (0.6.0)

`/v1/image` decodes the upload (JPEG or PNG; the header is checked first,
at most 20 megapixels), scales it to at most 1024 pixels on the long side
(Catmull-Rom) and stores it as a JPEG (quality 85) with the device. The
same upload again returns the same id while the photo is unused. A chat
message names it with `image: <id>`; the photo must be unused or already
part of that conversation (`image_not_found` otherwise). The request's
fingerprint covers the photo id, so the same `request` id with another
photo is `request_mismatch`. After the reply the photo belongs to the
conversation: each later message sends the newest 3 photos of the
conversation again (about 1000 input tokens each), older ones become
"[an earlier photo, no longer shown]". Photos are deleted with their
conversation (delete, expiry, trim); unused ones after a day. Uploads per
device per day: `DAILY_IMAGE_LIMIT` (default 30). The system prompt says
photos come from a 2 MP phone camera and may be blurry.

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
  the model; a repeated id is answered from the record (`replayed: 1`) and
  never triggers a second call.
- Every provider runs without retries (the Claude SDK with
  `maxRetries = 0`, OpenAI/xAI with one plain HTTP request) and a 60 s
  timeout. Timeouts and lost
  connections become `uncertain` and are never retried automatically; the
  phone keeps the draft, and sending it again is a new (paid) request by
  the user's choice. A request still `pending` after a restart becomes
  `uncertain`.
- None of the APIs used has an idempotency key, so there is no exactly-once
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
| photo | ≤ 1 MiB upload, ≤ 20 MP, stored ≤ 1024 px JPEG; newest 3 photos of a conversation sent with each message |
| voice message | ≤ 30 s on the phone, ≤ 35 s and ≤ 640 KiB on the server; audio never stored; transcript ≤ 996 characters |
| per device | 1 request and 1 voice message in flight, daily requests, output tokens, web searches, voice messages (`DAILY_TRANSCRIBE_LIMIT`, default 30) and photo uploads (`DAILY_IMAGE_LIMIT`, default 30; UTC day), ≤ 40 messages per conversation, ≤ 50 conversations plus ≤ 10 pinned |
| retention | conversations 30 days after the last message (pinned ones until unpinned or deleted), request records 7 days, transcripts 1 day (for replays only), photos with their conversation (unused ones 1 day), usage 90 days (cleanup every 6 h) |

All user-data tables are keyed by `device_id`: a conversation id is only
visible to the device that created it.

## Logs

JSON lines: start, TLS ClientHello summary (SNI present, versions, number of
suites), each request (method, path, status, duration, negotiated TLS),
upstream errors (HTTP status, Anthropic error type/message, request id),
voice clips (format, bytes, length in ms), photos (bytes received and
stored, pixel size) and speech-to-text errors (HTTP
status, error type/code/message, request id).
No message text, replies, transcripts, audio, photos, tokens, keys or
client IP addresses.

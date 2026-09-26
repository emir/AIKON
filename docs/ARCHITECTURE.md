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
  softkeys as `Command`s, arrows via `getGameAction()`.
- Networking on worker threads only, one request at a time, HTTPS only
  (`https://` enforced, no fallback), response body capped at 8 KiB.
- Stored on the phone (RMS `cs40cfg`): server URL, access token, verified
  URL, test mode, theme, text size, sound, vibration, language. No chat
  history. Deleting the app deletes it.
- `L.s("Türkçe", "English")`: language from `microedition.locale` or the
  Settings choice.
- Packaging is deterministic; `tools/check.py` verifies manifest/JAD
  consistency, class version, preverification (StackMap, no jsr/ret), every
  referenced class/member against the CLDC/MIDP API, only an optional HTTPS
  permission, no `http://` constants, and scans all bytes for secrets.

### Server

- `main.go` wiring, TLS policy, handlers; `protocol.go` S40/1 format;
  `chat.go` chat rules; `store.go` SQLite; `claude.go` SDK call + mock;
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
| `POST /v1/chat` | Bearer token | `request: <id>`, `conversation: <id or empty>`, optional `search: 0` (no web search for this message), text = message |
| `POST /v1/more` | Bearer token | `request: <id>`, `offset: <next>` → the next part of a stored reply (never calls Claude) |
| `POST /v1/conversations` | Bearer token | newest 20 conversations, one line each: `id TAB updated-ms TAB messages TAB title` |
| `POST /v1/history` | Bearer token | `conversation: <id>` → newest messages (≤ 6000 bytes), oldest first, each `u N` / `a N` (N = UTF-16 length), newline, text, newline; `older: 1` if earlier ones were left out |
| `POST /v1/delete` | Bearer token | `conversation: <id>` |

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

## Data and limits

| | |
|---|---|
| message | ≤ 1000 characters (request body ≤ 4 KiB) |
| reply | sanitised (no Markdown/emoji/non-BMP), ≤ 8000 characters stored, sent in parts of ≤ 2000, `truncated` flag |
| model | `CLAUDE_MODEL`, `max_tokens` 2048, `effort` from `CLAUDE_EFFORT`, optional server-side refusal fallback |
| context | newest 16 messages and ≤ 16000 characters of the conversation |
| per device | 1 request in flight, daily requests, output tokens and web searches (UTC day), ≤ 40 messages per conversation, ≤ 50 conversations |
| retention | conversations 30 days after the last message, request records 7 days, usage 90 days (cleanup every 6 h) |

All user-data tables are keyed by `device_id`: a conversation id is only
visible to the device that created it.

## Logs

JSON lines: start, TLS ClientHello summary (SNI present, versions, number of
suites), each request (method, path, status, duration, negotiated TLS),
upstream errors (HTTP status, Anthropic error type/message, request id).
No message text, replies, tokens, keys or client IP addresses.

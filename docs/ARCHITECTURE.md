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
| `POST /v1/chat` | Bearer token | `request: <id>`, `conversation: <id or empty>`, text = message |
| `POST /v1/delete` | Bearer token | `conversation: <id>` |

Chat statuses: `ok` (fields `conversation`, `truncated`, `refused`, `mock`,
`replayed`, `remaining`), `pending`, `busy`, `limit`, `conversation_full`,
`conversation_not_found`, `request_mismatch`, `rate_limited`, `overloaded`,
`billing`, `upstream_error`, `config_error`, `uncertain`, plus input errors.

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
| reply | sanitised (no Markdown/emoji/non-BMP), ≤ 2000 characters, `truncated` flag |
| model | `CLAUDE_MODEL`, `max_tokens` 2048, `effort` from `CLAUDE_EFFORT`, optional server-side refusal fallback |
| context | newest 16 messages and ≤ 16000 characters of the conversation |
| per device | 1 request in flight, daily requests and output tokens (UTC day), ≤ 40 messages per conversation, ≤ 50 conversations |
| retention | conversations 30 days after the last message, request records 7 days, usage 90 days (cleanup every 6 h) |

All user-data tables are keyed by `device_id`: a conversation id is only
visible to the device that created it.

## Logs

JSON lines: start, TLS ClientHello summary (SNI present, versions, number of
suites), each request (method, path, status, duration, negotiated TLS),
upstream errors (HTTP status, Anthropic error type/message, request id).
No message text, replies, tokens, keys or client IP addresses.

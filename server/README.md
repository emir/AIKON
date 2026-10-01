# AIKon – server

One Go binary / Docker image: the phone-facing TLS endpoint, the chat
backend (official [anthropic-sdk-go](https://github.com/anthropics/anthropic-sdk-go)),
SQLite storage, pairing and a localhost-only admin API. Protocol, TLS
policy and chat rules: [../docs/ARCHITECTURE.md](../docs/ARCHITECTURE.md).
Step-by-step deployment: [../docs/SETUP.md](../docs/SETUP.md).

## Build and test

```
make test        # go vet + go test -race: protocol, sanitize, health/echo,
                 # pairing/admin, chat rules, device isolation, concurrency, limits,
                 # retention/restart, SDK request shape + errors via a fake API,
                 # the Nokia's TLS offer, voice messages (audio checks, AMR via
                 # ffmpeg if installed, replay/limits, speech-to-text fake API),
                 # photos (decode/scale limits, upload, chat context, retention)
make docker      # linux/amd64 image, tests run inside the build too
```

## Configuration

Environment (see `.env.example`; `deploy/push.sh` writes `.env` from `S40_*`):

| Variable | Default | |
|---|---|---|
| `MOCK_ANTHROPIC` | `1` in compose | `1` = fake "[Test mode]" replies, no API calls |
| `CLAUDE_MODEL` | `claude-opus-5` | any Messages API model id |
| `CLAUDE_EFFORT` | `low` | `""` for models without `effort` |
| `CLAUDE_FALLBACKS` | `default` | server-side refusal fallback; `off` if the model does not support it |
| `DAILY_REQUEST_LIMIT` | `100` | per device, UTC day |
| `DAILY_OUTPUT_TOKEN_LIMIT` | `100000` | per device, UTC day |
| `TRANSCRIBE` | `off` | voice messages: `off`, `mock` ("[Test mode]" text) or `openai` (paid) |
| `TRANSCRIBE_MODEL` | `gpt-4o-mini-transcribe` | OpenAI transcription model |
| `DAILY_TRANSCRIBE_LIMIT` | `30` | voice messages per device, UTC day |
| `DAILY_IMAGE_LIMIT` | `30` | photo uploads per device, UTC day |
| `PUBLIC_HOSTS` | `` | browser side: DNS names that get a Let's Encrypt certificate by SNI (docs/ARCHITECTURE.md) |
| `ACME_EMAIL` | `` | optional contact for Let's Encrypt |
| `PHONE_HOST` | `` | the phone side's DNS name, for download links |
| `HTTP_LISTEN` | `` | plain-HTTP landing page and ca.cer only (`HTTP_PORT=80` in compose to expose) |

Files on the host (`~/claude-s40-server`): `certs/server-chain.pem`,
`certs/server.key` (uid 65532, 0400), `secrets/admin_token` (generated on
the server), `secrets/anthropic_api_key` (`deploy/set-key.sh`),
`secrets/openai_api_key` (`deploy/set-key.sh HOST openai`, only for
`TRANSCRIBE=openai`). Container: distroless non-root plus a static ffmpeg
built from pinned source (AMR/WAV in, raw PCM out, pipes only), read-only root fs, `cap_drop: ALL`, no-new-privileges,
128 MB; data in the `s40data` volume.

## Scripts

| | |
|---|---|
| `scripts/pki.sh` | private root CA + server certificate (keys stay on your computer) |
| `deploy/push.sh` | plan / `--execute`: build image locally, send over SSH, start |
| `deploy/set-key.sh` | put the Claude (or, with `openai`, the speech-to-text) API key on the server (hidden input) |
| `deploy/admin.sh` | `pair <code>`, `devices`, `revoke <id>`, `logs [n]` over SSH |
| `deploy/serve-ca.sh` | serve only the root CA over HTTP for a few minutes |
| `deploy/smoke.sh` | end-to-end check as the phone sees the server |
| `deploy/do-create.sh`, `deploy/cloud-init.yaml` | optional DigitalOcean droplet + firewall |

Backup: `docker compose cp server:/data/claude-s40.db ./backup.db` (the file
holds pairings, 30 days of chats and the pinned chats).

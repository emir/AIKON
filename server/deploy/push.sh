#!/bin/sh
# Deploy claude-s40-server to a Docker host over SSH.
#
#   deploy/push.sh SSH_TARGET            plan only (default)
#   deploy/push.sh SSH_TARGET --execute  build here (linux/amd64, tests run), send image, start
#
# Env (S40_ prefix, so unrelated CLAUDE_* shell variables are never picked
# up): PKI_DIR (default ~/.config/claude-s40/pki), S40_MOCK (default 1),
# S40_MODEL, S40_EFFORT, S40_FALLBACKS, S40_REQ_LIMIT, S40_TOK_LIMIT,
# S40_ENVIRONMENT, S40_SEARCH (1/0), S40_SEARCH_MAX_USES (per message),
# S40_SEARCH_LIMIT (per device per day), S40_SEARCH_COUNTRY/_CITY/_TIMEZONE
# (optional approximate location for local search results),
# S40_TRANSCRIBE (voice messages: off (default), mock, openai),
# S40_TRANSCRIBE_MODEL, S40_TRANSCRIBE_LIMIT (per device per day),
# S40_IMAGE_LIMIT (photo uploads per device per day).
# Copies only: image, compose.yaml, .env (no secrets), server-chain.pem, server.key.
# On the server: secrets/admin_token is generated there if missing (never
# leaves the server); secrets/anthropic_api_key must be put there with
# deploy/set-key.sh (and secrets/openai_api_key with "set-key.sh TARGET
# openai" for TRANSCRIBE=openai). The CA key never leaves the Mac.
set -eu
HERE=$(cd "$(dirname "$0")/.." && pwd)
TARGET=${1:?usage: deploy/push.sh SSH_TARGET [--execute]}
EXEC=no; [ "${2:-}" = "--execute" ] && EXEC=yes
PKI=${PKI_DIR:-$HOME/.config/claude-s40/pki}
REMOTE=claude-s40-server
IMAGE=claude-s40-server:0.6.2
MOCK=${S40_MOCK:-1}
STT=${S40_TRANSCRIBE:-off}
case "$STT" in off|mock|openai) ;; *) echo "S40_TRANSCRIBE must be off, mock or openai" >&2; exit 1 ;; esac

for f in server-chain.pem server.key; do
	[ -f "$PKI/$f" ] || { echo "missing $PKI/$f" >&2; exit 1; }
done
STAGE=$(mktemp -d)
trap 'rm -rf "$STAGE"' EXIT
grep -v '^    build: \.$' "$HERE/compose.yaml" > "$STAGE/compose.yaml"
mkdir -m 755 "$STAGE/certs"
cp "$PKI/server-chain.pem" "$STAGE/certs/" && chmod 644 "$STAGE/certs/server-chain.pem"
cp "$PKI/server.key" "$STAGE/certs/" && chmod 600 "$STAGE/certs/server.key"
cat > "$STAGE/.env" <<ENV
ENVIRONMENT=${S40_ENVIRONMENT:-production}
CLAUDE_MODEL=${S40_MODEL:-claude-opus-5}
CLAUDE_EFFORT=${S40_EFFORT:-low}
CLAUDE_FALLBACKS=${S40_FALLBACKS:-default}
MOCK_ANTHROPIC=$MOCK
DAILY_REQUEST_LIMIT=${S40_REQ_LIMIT:-100}
DAILY_OUTPUT_TOKEN_LIMIT=${S40_TOK_LIMIT:-100000}
WEB_SEARCH=${S40_SEARCH:-1}
WEB_SEARCH_MAX_USES=${S40_SEARCH_MAX_USES:-3}
DAILY_SEARCH_LIMIT=${S40_SEARCH_LIMIT:-30}
SEARCH_COUNTRY=${S40_SEARCH_COUNTRY:-}
SEARCH_CITY=${S40_SEARCH_CITY:-}
SEARCH_TIMEZONE=${S40_SEARCH_TIMEZONE:-}
TRANSCRIBE=$STT
TRANSCRIBE_MODEL=${S40_TRANSCRIBE_MODEL:-gpt-4o-mini-transcribe}
DAILY_TRANSCRIBE_LIMIT=${S40_TRANSCRIBE_LIMIT:-30}
DAILY_IMAGE_LIMIT=${S40_IMAGE_LIMIT:-30}
ENV

echo "== plan"
echo "target  : $TARGET:~/$REMOTE"
echo "image   : $IMAGE (built here for linux/amd64; go vet + tests run in the build)"
echo "settings:"; sed 's/^/  /' "$STAGE/.env"
echo "cert    : $(${OPENSSL:-openssl} x509 -in "$PKI/server.pem" -noout -subject -enddate 2>/dev/null | tr '\n' ' ')"
echo "ports   : 443 -> 8443 (phone TLS), 127.0.0.1:9090 (admin, server-local only)"
echo "data    : Docker volume claude-s40-server_s40data (SQLite)"
[ "$MOCK" = 0 ] && echo "LIVE    : MOCK_ANTHROPIC=0 -> real, paid Claude calls; needs secrets/anthropic_api_key"
[ "$MOCK" = 0 ] && [ "${S40_SEARCH:-1}" = 1 ] && echo "SEARCH  : web search on -> billed per search, results count as input tokens"
[ "$STT" = openai ] && echo "VOICE   : TRANSCRIBE=openai -> paid speech-to-text calls; needs secrets/openai_api_key"
if [ "$EXEC" != yes ]; then
	echo; echo "PLAN ONLY. Run again with --execute after approval."
	exit 0
fi

echo "== build"
docker buildx build --platform linux/amd64 -t "$IMAGE" --load "$HERE" >/dev/null
echo "== send image"
docker save "$IMAGE" | gzip | ssh "$TARGET" 'S=""; [ "$(id -u)" = 0 ] || S=sudo; gunzip | $S docker load'
echo "== copy"
ssh "$TARGET" "mkdir -p ~/$REMOTE/secrets"
scp -q -r "$STAGE"/. "$TARGET:$REMOTE/"
echo "== start"
ssh "$TARGET" "MOCK=$MOCK STT=$STT sh -s" <<'REMOTE_SH'
set -eu
S=""; [ "$(id -u)" = 0 ] || S=sudo
cd ~/claude-s40-server
[ -s secrets/admin_token ] || $S sh -c 'umask 077; openssl rand -hex 32 > secrets/admin_token'
if [ "$MOCK" = 0 ] && ! $S test -s secrets/anthropic_api_key; then
	echo "refusing: MOCK_ANTHROPIC=0 but secrets/anthropic_api_key is missing (use deploy/set-key.sh)" >&2
	exit 1
fi
if [ "$STT" = openai ] && ! $S test -s secrets/openai_api_key; then
	echo "refusing: TRANSCRIBE=openai but secrets/openai_api_key is missing (use deploy/set-key.sh TARGET openai)" >&2
	exit 1
fi
$S chown 65532:65532 certs/server.key secrets secrets/*
$S chmod 400 certs/server.key secrets/*
$S chmod 500 secrets
$S docker compose up -d --force-recreate
sleep 2
$S docker compose ps
$S docker compose logs --tail 3
REMOTE_SH

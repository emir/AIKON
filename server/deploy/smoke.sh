#!/bin/sh
# End-to-end check as the Nokia sees the server: TLS 1.0, AES128-SHA, no SNI,
# trusting ONLY our CA. health -> echo (Turkish UTF-8) -> pairing -> chat ->
# same-conversation follow-up -> request_id replay -> no token -> 401.
#
#   deploy/smoke.sh HOST PORT CA_PEM APPROVE_CMD
#
# APPROVE_CMD is run with the 6-digit code as $1 and must approve it on the
# admin API (locally: curl to the admin port; on the VPS: deploy/admin.sh).
# Chat must be in mock mode unless you intend a (small) paid call.
set -eu
HOST=$1 PORT=$2 CA=$3 APPROVE=$4
O=${OPENSSL:-openssl}  # needs TLS 1.0 support (OpenSSL 3 with @SECLEVEL=0, or LibreSSL)
# OpenSSL 3 needs @SECLEVEL=0 for TLS 1.0 and can suppress SNI; LibreSSL
# (macOS default) knows neither option and needs neither.
if $O version | grep -q LibreSSL; then
	TLSOPTS="-tls1 -cipher AES128-SHA"
else
	TLSOPTS="-tls1 -noservername -cipher AES128-SHA:@SECLEVEL=0"
fi
req() {  # $1 = raw HTTP request; prints the S40 body
	printf '%b' "$1" | $O s_client -connect "$HOST:$PORT" $TLSOPTS \
		-CAfile "$CA" -verify_return_error -quiet 2>/dev/null |
		sed -n '/^S40\/1/,$p'
}
post() {  # $1 path, $2 body, $3 token (optional)
	len=$(printf '%b' "$2" | wc -c | tr -d ' ')
	auth=""; [ -n "${3:-}" ] && auth="Authorization: Bearer $3\r\n"
	req "POST $1 HTTP/1.1\r\nHost: $HOST\r\n${auth}Content-Type: text/plain; charset=utf-8\r\nContent-Length: $len\r\nConnection: close\r\n\r\n$2"
}
fail() { echo "FAIL  $1"; exit 1; }
pass() { echo "PASS  $1"; }

for i in 1 2 3 4 5 6 7 8 9 10; do
	B=$(req "GET /health HTTP/1.1\r\nHost: $HOST\r\nConnection: close\r\n\r\n") && [ -n "$B" ] && break
	sleep 2
done
echo "$B" | grep -q '^status: ok$' || fail "health: $B"
pass "health over TLS 1.0 / AES128-SHA, verified against our CA only ($(echo "$B" | sed -n 's/^tls-version: //p'))"

B=$(post /echo 'Claude S40 UTF-8: ç ğ ı İ ö ş ü Ç Ğ Ö Ş Ü')
echo "$B" | grep -q '^probe: match$' && pass "echo Turkish UTF-8" || fail "echo: $B"

B=$(post /v1/pair/start '')
PID=$(echo "$B" | sed -n 's/^pair: //p'); CODE=$(echo "$B" | sed -n 's/^code: //p')
[ ${#CODE} -eq 6 ] || fail "pair start: $B"
sh -c "$APPROVE" approve "$CODE" >/dev/null || fail "approve"
TOK=$(post /v1/pair/claim "S40/1\npair: $PID\n\n" | sed -n 's/^token: //p')
[ ${#TOK} -eq 32 ] && pass "pairing: start, approve, claim (token not printed)" || fail "claim"

B=$(post /v1/chat "S40/1\nrequest: smoke-$(date +%s)-1\nconversation: \n\nMerhaba, bu bir smoke testi." "$TOK")
echo "$B" | grep -q '^status: ok$' || fail "chat 1: $B"
CONV=$(echo "$B" | sed -n 's/^conversation: //p')
pass "chat 1 ($(echo "$B" | sed -n 's/^mock: \(.*\)/mock=\1/p'))"
RID="smoke-$(date +%s)-2"
B=$(post /v1/chat "S40/1\nrequest: $RID\nconversation: $CONV\n\nMake it shorter." "$TOK")
echo "$B" | grep -q '^status: ok$' && echo "$B" | grep -q "^conversation: $CONV$" && pass "chat 2 same conversation" || fail "chat 2: $B"
B=$(post /v1/chat "S40/1\nrequest: $RID\nconversation: $CONV\n\nMake it shorter." "$TOK")
echo "$B" | grep -q '^replayed: 1$' && pass "same request_id replayed, no new call" || fail "replay: $B"
B=$(post /v1/chat "S40/1\nrequest: smoke-$(date +%s)-3\n\nx")
echo "$B" | grep -q '^status: unauthorized$' && pass "no token -> 401" || fail "auth: $B"
echo "SMOKE_DEVICE_TOKEN_LEN=${#TOK}"

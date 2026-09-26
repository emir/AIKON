#!/bin/sh
# Admin actions, executed ON the server over SSH (the admin port is bound to
# 127.0.0.1 there and the admin token never leaves the server).
#
#   deploy/admin.sh SSH_TARGET pair <6-digit code> [name]
#   deploy/admin.sh SSH_TARGET devices
#   deploy/admin.sh SSH_TARGET revoke <dev-xxxxxxxx>
#   deploy/admin.sh SSH_TARGET logs [n]
set -eu
TARGET=${1:?usage}; CMD=${2:?usage}; shift 2
case "$CMD" in
pair)
	CODE=$(printf '%s' "${1:?code}" | tr -cd '0-9'); NAME=$(printf '%s' "${2:-phone}" | tr -cd 'A-Za-z0-9 ._-')
	[ ${#CODE} -eq 6 ] || { echo "code must be 6 digits" >&2; exit 2; }
	BODY="{\"code\":\"$CODE\",\"name\":\"$NAME\"}"; METHOD=POST; P=/admin/pair/approve ;;
devices) BODY=""; METHOD=GET; P=/admin/devices ;;
revoke)
	ID=$(printf '%s' "${1:?device id}" | tr -cd 'a-z0-9-')
	BODY="{\"device_id\":\"$ID\"}"; METHOD=POST; P=/admin/devices/revoke ;;
logs)
	exec ssh "$TARGET" "cd ~/claude-s40-server && S=''; [ \"\$(id -u)\" = 0 ] || S=sudo; \$S docker compose logs --tail ${1:-30} --no-log-prefix" ;;
*) echo "unknown command $CMD" >&2; exit 2 ;;
esac
ssh "$TARGET" "S=''; [ \"\$(id -u)\" = 0 ] || S=sudo; T=\$(\$S cat ~/claude-s40-server/secrets/admin_token); \
curl -sS -X $METHOD -H \"Authorization: Bearer \$T\" ${BODY:+-d '$BODY'} http://127.0.0.1:9090$P; echo"

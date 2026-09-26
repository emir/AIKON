#!/bin/sh
# Put the Claude API key on the server without it appearing on screen, in
# shell history, in a local file or in any log. Run it in YOUR terminal.
#
#   deploy/set-key.sh SSH_TARGET
set -eu
TARGET=${1:?usage: deploy/set-key.sh SSH_TARGET}
printf 'Claude API key (input hidden): '
stty -echo; IFS= read -r KEY; stty echo; echo
case "$KEY" in sk-ant-*) ;; *) echo "that does not look like an Anthropic API key (sk-ant-...)" >&2; exit 1 ;; esac
printf '%s' "$KEY" | ssh "$TARGET" 'S=""; [ "$(id -u)" = 0 ] || S=sudo; mkdir -p ~/claude-s40-server/secrets && cd ~/claude-s40-server/secrets && $S sh -c "umask 077; cat > anthropic_api_key" && $S chown 65532:65532 anthropic_api_key && $S chmod 400 anthropic_api_key && echo "stored: $(wc -c < anthropic_api_key) bytes"'
KEY=

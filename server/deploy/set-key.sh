#!/bin/sh
# Put an API key on the server without it appearing on screen, in shell
# history, in a local file or in any log. Run it in YOUR terminal.
#
#   deploy/set-key.sh SSH_TARGET            Claude API key (secrets/anthropic_api_key)
#   deploy/set-key.sh SSH_TARGET openai     OpenAI key (secrets/openai_api_key)
#   deploy/set-key.sh SSH_TARGET xai        xAI key (secrets/xai_api_key)
set -eu
TARGET=${1:?usage: deploy/set-key.sh SSH_TARGET [openai|xai]}
case "${2:-anthropic}" in
anthropic) FILE=anthropic_api_key NAME="Claude API key" PREFIX=sk-ant- ;;
openai) FILE=openai_api_key NAME="OpenAI API key" PREFIX=sk- ;;
xai) FILE=xai_api_key NAME="xAI API key" PREFIX=xai- ;;
*) echo "usage: deploy/set-key.sh SSH_TARGET [openai|xai]" >&2; exit 1 ;;
esac
printf '%s (input hidden): ' "$NAME"
stty -echo; IFS= read -r KEY; stty echo; echo
case "$KEY" in "$PREFIX"*) ;; *) echo "that does not look like an $NAME ($PREFIX...)" >&2; exit 1 ;; esac
[ "$FILE" = openai_api_key ] && case "$KEY" in sk-ant-*) echo "that is an Anthropic key, not an OpenAI key" >&2; exit 1 ;; esac
printf '%s' "$KEY" | ssh "$TARGET" "F=$FILE; "'S=""; [ "$(id -u)" = 0 ] || S=sudo; mkdir -p ~/claude-s40-server/secrets && cd ~/claude-s40-server/secrets && $S sh -c "umask 077; cat > $F" && $S chown 65532:65532 $F && $S chmod 400 $F && echo "stored $F: $(wc -c < $F) bytes"'
KEY=

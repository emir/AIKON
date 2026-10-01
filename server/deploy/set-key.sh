#!/bin/sh
# Put an API key on the server without it appearing on screen, in shell
# history, in a local file or in any log. Run it in YOUR terminal.
#
#   deploy/set-key.sh SSH_TARGET            Claude API key (secrets/anthropic_api_key)
#   deploy/set-key.sh SSH_TARGET openai     OpenAI key (secrets/openai_api_key)
#   deploy/set-key.sh SSH_TARGET xai        xAI key (secrets/xai_api_key)
#   deploy/set-key.sh SSH_TARGET gemini     Gemini API key (secrets/gemini_api_key)
set -eu
TARGET=${1:?usage: deploy/set-key.sh SSH_TARGET [openai|xai|gemini]}
case "${2:-anthropic}" in
anthropic) FILE=anthropic_api_key NAME="Claude API key" PREFIX=sk-ant- ;;
openai) FILE=openai_api_key NAME="OpenAI API key" PREFIX=sk- ;;
xai) FILE=xai_api_key NAME="xAI API key" PREFIX=xai- ;;
gemini) FILE=gemini_api_key NAME="Gemini API key" PREFIX=AIza ;; # or AQ. (AI Studio's newer keys), below
*) echo "usage: deploy/set-key.sh SSH_TARGET [openai|xai|gemini]" >&2; exit 1 ;;
esac
printf '%s (input hidden): ' "$NAME"
stty -echo; IFS= read -r KEY; stty echo; echo
case "$FILE:$KEY" in gemini_api_key:AQ.*) ;; *:"$PREFIX"*) ;; *) echo "that does not look like an $NAME ($PREFIX...)" >&2; exit 1 ;; esac
[ "$FILE" = openai_api_key ] && case "$KEY" in sk-ant-*) echo "that is an Anthropic key, not an OpenAI key" >&2; exit 1 ;; esac
printf '%s' "$KEY" | ssh "$TARGET" "F=$FILE; "'S=""; [ "$(id -u)" = 0 ] || S=sudo; mkdir -p ~/claude-s40-server/secrets && cd ~/claude-s40-server/secrets && $S sh -c "umask 077; cat > $F" && $S chown 65532:65532 $F && $S chmod 400 $F && echo "stored $F: $(wc -c < $F) bytes"'
KEY=

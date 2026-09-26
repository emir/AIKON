#!/bin/sh
#
# Fetch and verify pinned build dependencies (deps.lock) into DEPS_DIR.
# Existing files are verified, never replaced. A hash mismatch aborts.
#
# Usage: tools/fetch_deps.sh DEPS_DIR

set -eu

HERE=$(cd "$(dirname "$0")/.." && pwd)
DEPS=${1:?usage: fetch_deps.sh DEPS_DIR}
mkdir -p "$DEPS"

sha() { shasum -a 256 "$1" | cut -d' ' -f1; }

grep -v '^#' "$HERE/deps.lock" | while IFS='	' read -r name want license url; do
	[ -n "$name" ] || continue
	f="$DEPS/$name"

	if [ ! -f "$f" ]; then
		case "$url" in
		http*)
			echo "fetch $name"
			curl -fsSL -m 600 "$url" -o "$f.part"
			mv "$f.part" "$f"
			;;
		"extracted from proguard-7.9.1.zip")
			(cd "$DEPS" && unzip -q -o proguard-7.9.1.zip "$name")
			;;
		*)
			echo "don't know how to obtain $name" >&2
			exit 1
			;;
		esac
	fi

	got=$(sha "$f")
	if [ "$got" != "$want" ]; then
		echo "SHA-256 mismatch for $name: $got (want $want)" >&2
		exit 1
	fi
	echo "ok    $name ($license)"
done

#!/bin/sh
# Install Claude S40 on a Nokia Series 40 phone with Gammu over USB
# ("gammu nokiaaddfile Application"). This WRITES two files to the phone
# (d:/predefjava/predefcollections/ClaudeS40.jad and .jar on S40 3rd Ed).
#
#   tools/install-gammu.sh                      dry run: local checks + exact command
#   tools/install-gammu.sh --execute --i-understand-this-writes-to-the-phone
#
# Env: GAMMU (default: gammu in PATH), GAMMURC (optional config file),
#      GAMMU_LIB (optional: directory with a locally built libGammu).
# Never passes -overwrite/-overwriteall, so Gammu's delete code paths are not
# used; remove an older Claude S40 with the phone's own menu first.
# Other ways to install: Bluetooth "send file" to the phone, Nokia PC Suite,
# or serving the JAD/JAR over HTTP to the phone browser (see docs/SETUP.md).
set -eu
HERE=$(cd "$(dirname "$0")/.." && pwd)
DIST="$HERE/dist"
GAMMU=${GAMMU:-gammu}
CFG=""; [ -n "${GAMMURC:-}" ] && CFG="-c $GAMMURC"
if [ -n "${GAMMU_LIB:-}" ]; then
	# set here, not by the caller: macOS strips DYLD_* when starting /bin/sh
	export DYLD_LIBRARY_PATH="$GAMMU_LIB" LD_LIBRARY_PATH="$GAMMU_LIB"
fi
NAME=$(sed -n 's/^FILE_BASE=//p' "$HERE/app.properties")
BASE="$DIST/$NAME"
TARGET_DIR="d:/predefjava/predefcollections"

mode=dry
if [ "${1:-}" = "--execute" ]; then
	[ "${2:-}" = "--i-understand-this-writes-to-the-phone" ] || { echo "refusing: add --i-understand-this-writes-to-the-phone" >&2; exit 2; }
	mode=execute
fi

[ -f "$BASE.jar" ] && [ -f "$BASE.jad" ] || { echo "run 'make' first" >&2; exit 1; }
(cd "$DIST" && shasum -a 256 -c SHA256SUMS)
echo "will create: $TARGET_DIR/$NAME.jad and $NAME.jar (no overwrite, no delete)"
echo "command    : $GAMMU $CFG nokiaaddfile Application $BASE"
[ "$mode" = dry ] && { echo "DRY RUN: phone not touched."; exit 0; }

LOG=$(mktemp -d)/claude-s40-install
mkdir -p "$LOG"
# Checks list only the target folder: a whole-phone listing (getfilesystem
# -flatall) walks every memory card folder over the slow phone protocol and
# never ends on a damaged card.
echo "-- read-only pre-check ($TARGET_DIR)"
$GAMMU $CFG getfolderlisting "$TARGET_DIR" > "$LOG/before.txt" || { echo "refusing: could not list $TARGET_DIR" >&2; exit 1; }
if grep -i "^$TARGET_DIR/$NAME\.ja[rd];" "$LOG/before.txt"; then
	echo "refusing: $NAME already on the phone; delete it with the phone menu first" >&2
	exit 1
fi
echo "-- installing (writes to the phone)"
$GAMMU $CFG nokiaaddfile Application "$BASE"
echo "-- read-only post-check ($TARGET_DIR)"
$GAMMU $CFG getfolderlisting "$TARGET_DIR" > "$LOG/after.txt"
ok=yes
for ext in jar jad; do
	want=$(wc -c < "$BASE.$ext" | tr -d ' ')
	# a line is: path;File;"name";"date";size;attributes
	got=$(grep -i "^$TARGET_DIR/$NAME\.$ext;" "$LOG/after.txt" | awk -F';' '{print $5}')
	if [ "$got" = "$want" ]; then
		echo "on the phone: $NAME.$ext ($got bytes)"
	else
		echo "CHECK: $NAME.$ext on the phone has '${got:-nothing}' bytes, built file has $want" >&2
		ok=no
	fi
done
echo "listings: $LOG"
[ "$ok" = yes ]

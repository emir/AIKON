#!/bin/sh
# Install AIKON on a Nokia Series 40 phone with Gammu over USB
# ("gammu nokiaaddfile Application"). This WRITES two files to the phone
# (d:/predefjava/predefcollections/AIKON.jad and .jar on S40 3rd Ed).
#
#   tools/install-gammu.sh                      dry run: local checks + exact command
#   tools/install-gammu.sh --execute --i-understand-this-writes-to-the-phone
#   tools/install-gammu.sh --execute --i-understand-this-writes-to-the-phone --replace
#
# Env: GAMMU (default: gammu in PATH), GAMMURC (optional config file),
#      GAMMU_LIB (optional: directory with a locally built libGammu).
# Never passes -overwrite/-overwriteall. Without --replace an older AIKON
# must be removed with the phone's own menu first (that also deletes its
# settings and pairing). With --replace exactly the two files
# d:/predefjava/predefcollections/<FILE_BASE>.jad and .jar are deleted
# (gammu deletefiles, nothing else) before the new ones are added; the
# app's settings (RMS) are not touched, so the pairing usually stays.
# The phone keeps each app's record stores next to it as
# <FILE_BASE>_m_<store>.rms, so replacing the JAD/JAR keeps the settings.
# --replace also removes the app under its old name (ClaudeS40.jad/.jar,
# "Claude S40" before 0.11.0) and its ClaudeS40_m_cs40*.rms stores (they
# hold its access token); its settings do not carry over to AIKON.
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
replace=no
if [ "${1:-}" = "--execute" ]; then
	[ "${2:-}" = "--i-understand-this-writes-to-the-phone" ] || { echo "refusing: add --i-understand-this-writes-to-the-phone" >&2; exit 2; }
	mode=execute
	case "${3:-}" in
	"") ;;
	--replace) replace=yes ;;
	*) echo "unknown option ${3}" >&2; exit 2 ;;
	esac
fi

[ -f "$BASE.jar" ] && [ -f "$BASE.jad" ] || { echo "run 'make' first" >&2; exit 1; }
(cd "$DIST" && shasum -a 256 -c SHA256SUMS)
if [ "$replace" = yes ]; then
	echo "will replace: $TARGET_DIR/$NAME.jad and $NAME.jar (deletes only these two if present, then adds)"
else
	echo "will create: $TARGET_DIR/$NAME.jad and $NAME.jar (no overwrite, no delete)"
fi
echo "command    : $GAMMU $CFG nokiaaddfile Application $BASE"
[ "$mode" = dry ] && { echo "DRY RUN: phone not touched."; exit 0; }

LOG=$(mktemp -d)/claude-s40-install
mkdir -p "$LOG"
# Checks list only the target folder: a whole-phone listing (getfilesystem
# -flatall) walks every memory card folder over the slow phone protocol and
# never ends on a damaged card.
echo "-- read-only pre-check ($TARGET_DIR)"
$GAMMU $CFG getfolderlisting "$TARGET_DIR" > "$LOG/before.txt" || { echo "refusing: could not list $TARGET_DIR" >&2; exit 1; }
old=$(grep -i "^$TARGET_DIR/$NAME\.ja[rd];" "$LOG/before.txt" | cut -d';' -f1 || true)
LEGACY=ClaudeS40
# the old app's files, and its record stores (<base>_m_<store>.rms in the
# same folder: settings incl. the access token, the kept chat, data usage)
legacy=$(grep -iE "^$TARGET_DIR/$LEGACY(\.ja[rd]|_m_cs40[a-z]+\.rms);" "$LOG/before.txt" | cut -d';' -f1 || true)
if [ -n "$legacy" ] && [ "$replace" != yes ]; then
	echo "note: the old \"Claude S40\" app is still on the phone; remove it with the phone menu, or use --replace"
fi
[ "$replace" = yes ] && old="$old $legacy"
if [ -n "$old" ] && [ "$replace" != yes ]; then
	echo "$old"
	echo "refusing: $NAME already on the phone; delete it with the phone menu first, or add --replace" >&2
	exit 1
fi
old=$(echo $old)
if [ -n "$old" ]; then
	echo "-- deleting the old files (writes to the phone)"
	for f in $old; do
		case "$f" in
		"$TARGET_DIR/$NAME.jar" | "$TARGET_DIR/$NAME.jad" | "$TARGET_DIR/$NAME.JAR" | "$TARGET_DIR/$NAME.JAD") ;;
		"$TARGET_DIR/$LEGACY.jar" | "$TARGET_DIR/$LEGACY.jad" | "$TARGET_DIR/$LEGACY.JAR" | "$TARGET_DIR/$LEGACY.JAD") ;;
		"$TARGET_DIR/${LEGACY}_m_cs40"*".rms") ;;
		*) echo "refusing: unexpected file id $f" >&2; exit 1 ;;
		esac
		echo "   $f"
		$GAMMU $CFG deletefiles "$f"
	done
	$GAMMU $CFG getfolderlisting "$TARGET_DIR" > "$LOG/deleted.txt"
	if grep -iE "^$TARGET_DIR/($NAME\.ja[rd]|$LEGACY(\.ja[rd]|_m_cs40[a-z]+\.rms));" "$LOG/deleted.txt"; then
		echo "stopping: the old files are still there; nothing new was added" >&2
		exit 1
	fi
fi
echo "-- installing (writes to the phone)"
$GAMMU $CFG nokiaaddfile Application "$BASE"
echo "-- read-only post-check ($TARGET_DIR)"
$GAMMU $CFG getfolderlisting "$TARGET_DIR" > "$LOG/after.txt"
ok=yes
for ext in jar jad; do
	want=$(wc -c < "$BASE.$ext" | tr -d ' ')
	if [ "$ext" = jad ]; then
		# the phone keeps the JAD with CRLF line ends: one more byte per line
		want=$((want + $(wc -l < "$BASE.jad" | tr -d ' ')))
	fi
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

#!/bin/sh
# Serve ONLY the root CA certificate over plain HTTP for a few minutes, so the
# phone's browser can download and save it ("save as authority certificate").
#
#   deploy/serve-ca.sh SSH_TARGET [minutes]     (default 10)
#
# Before: allow TCP 80 in the server's firewall; afterwards close it again.
# The certificate is public; what protects you is comparing the fingerprint
# the phone shows with `scripts/pki.sh show` BEFORE saving it.
# Uses a read-only, unprivileged busybox httpd; removed when time is up.
set -eu
TARGET=${1:?usage: deploy/serve-ca.sh SSH_TARGET [minutes]}
MIN=${2:-10}
PKI=${PKI_DIR:-$HOME/.config/claude-s40/pki}
[ -f "$PKI/claude-s40-ca.cer" ] || { echo "missing $PKI/claude-s40-ca.cer" >&2; exit 1; }
scripts=$(cd "$(dirname "$0")/.." && pwd)/scripts
"$scripts/pki.sh" show "$PKI" | sed -n '1,/^== server/p' | grep -v '^== server'
ssh "$TARGET" 'mkdir -p ~/claude-s40-ca/www && chmod 755 ~/claude-s40-ca ~/claude-s40-ca/www'
scp -q "$PKI/claude-s40-ca.cer" "$TARGET:claude-s40-ca/www/ca.cer"
ssh "$TARGET" "MIN=$MIN sh -s" <<'REMOTE'
set -eu
S=""; [ "$(id -u)" = 0 ] || S=sudo
cd ~/claude-s40-ca
chmod 644 www/ca.cer
printf '.cer:application/x-x509-ca-cert\n' > httpd.conf && chmod 644 httpd.conf
$S docker rm -f s40-ca-download >/dev/null 2>&1 || true
$S docker run -d --name s40-ca-download --read-only --cap-drop ALL --security-opt no-new-privileges:true \
	--user 65534:65534 --memory 16m -p 80:8080 -v "$PWD/www:/www:ro" -v "$PWD/httpd.conf:/etc/httpd.conf:ro" \
	busybox:stable httpd -f -p 8080 -h /www -c /etc/httpd.conf >/dev/null
echo "serving http://<server>/ca.cer for $MIN minutes"
sleep $((MIN * 60))
$S docker rm -f s40-ca-download >/dev/null
rm -rf ~/claude-s40-ca
echo "stopped and removed"
REMOTE

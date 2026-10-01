#!/bin/sh
# Claude S40 private PKI: one root CA (saved on the phone as an authority
# certificate) and one server certificate for the Claude S40 server.
#
#   scripts/pki.sh ca     DIR [sha1|sha256]          create root CA (once)
#   scripts/pki.sh server DIR HOSTS [sha1|sha256]    issue the server cert for HOSTS: DNS names and/or
#                                                    IPv4 addresses, comma-separated; the first is the CN
#   scripts/pki.sh show   DIR                        fingerprints to compare on the phone
#
# DIR default for real use: ~/.config/claude-s40/pki (outside the repo,
# mode 700). Private keys never go into git. Existing files are never
# overwritten.
#
# Choices for a 2007/2008 TLS stack: RSA-2048 keys, few extensions, validity
# CA 10 y / server 2 y. Default signature hash is SHA-1: root + server cert
# with SHA-1 is the combination verified on a Nokia 6300 (V06.60); SHA-256
# is untested there. SHA-1 is acceptable here because only you issue
# certificates under this private root.
set -eu
OPENSSL=${OPENSSL:-openssl}  # OpenSSL 3 or LibreSSL
cmd=${1:?usage}; DIR=${2:?usage}
umask 077
mkdir -p "$DIR"
chmod 700 "$DIR"

refuse() { [ ! -e "$1" ] || { echo "refusing: $1 exists" >&2; exit 1; }; }

case "$cmd" in
ca)
	MD=${3:-sha1}
	refuse "$DIR/ca.key"
	$OPENSSL genrsa -out "$DIR/ca.key" 2048 2>/dev/null
	cat > "$DIR/ca.cnf" <<CNF
[req]
distinguished_name = dn
prompt = no
x509_extensions = v3
[dn]
CN = Claude S40 Private Root CA
O = Claude S40
[v3]
basicConstraints = critical, CA:TRUE
keyUsage = critical, keyCertSign, cRLSign
subjectKeyIdentifier = hash
CNF
	$OPENSSL req -new -x509 -key "$DIR/ca.key" -"$MD" -days 3650 -config "$DIR/ca.cnf" -out "$DIR/ca.pem"
	$OPENSSL x509 -in "$DIR/ca.pem" -outform DER -out "$DIR/claude-s40-ca.cer"
	echo "root CA: $DIR/ca.pem  (phone file: $DIR/claude-s40-ca.cer)"
	;;
server)
	HOST=${3:?host}; MD=${4:-sha1}
	[ -f "$DIR/ca.key" ] || { echo "create the CA first" >&2; exit 1; }
	refuse "$DIR/server.key"
	SAN=""
	for H in $(printf '%s' "$HOST" | tr ',' ' '); do
		case "$H" in
		*[!0-9.]*) SAN="${SAN:+$SAN, }DNS:$H" ;;
		*) SAN="${SAN:+$SAN, }IP:$H" ;;
		esac
	done
	HOST=${HOST%%,*}
	$OPENSSL genrsa -out "$DIR/server.key" 2048 2>/dev/null
	cat > "$DIR/server.cnf" <<CNF
[req]
distinguished_name = dn
prompt = no
[dn]
CN = $HOST
O = Claude S40
[v3]
basicConstraints = CA:FALSE
keyUsage = critical, digitalSignature, keyEncipherment
extendedKeyUsage = serverAuth
subjectAltName = $SAN
authorityKeyIdentifier = keyid
CNF
	$OPENSSL req -new -key "$DIR/server.key" -config "$DIR/server.cnf" -out "$DIR/server.csr"
	$OPENSSL x509 -req -in "$DIR/server.csr" -CA "$DIR/ca.pem" -CAkey "$DIR/ca.key" -CAcreateserial \
		-"$MD" -days 730 -extfile "$DIR/server.cnf" -extensions v3 -out "$DIR/server.pem" 2>/dev/null
	cat "$DIR/server.pem" "$DIR/ca.pem" > "$DIR/server-chain.pem"
	echo "server cert: $DIR/server-chain.pem  key: $DIR/server.key  (CN=$HOST)"
	;;
show)
	for f in ca.pem server.pem; do
		[ -f "$DIR/$f" ] || continue
		echo "== $f"
		$OPENSSL x509 -in "$DIR/$f" -noout -subject -issuer -enddate
		$OPENSSL x509 -in "$DIR/$f" -noout -text | grep -m1 'Signature Algorithm' | sed 's/^ */sig: /'
		$OPENSSL x509 -in "$DIR/$f" -noout -fingerprint -sha1
		$OPENSSL x509 -in "$DIR/$f" -noout -fingerprint -md5
	done
	;;
*) echo "usage: see header" >&2; exit 2 ;;
esac

#!/bin/sh
# Optional helper: create a small DigitalOcean droplet + its own cloud
# firewall for the Claude S40 server. Any Docker host with a public IPv4
# and TCP 443 works; this is just one way to get one.
#
#   deploy/do-create.sh              plan only (default): read-only checks, prints commands
#   deploy/do-create.sh --execute    creates the resources (costs money; needs approval)
#
# Env: DO_CONTEXT (doctl auth context, default "default"), SSH_PUBKEY
# (default ~/.ssh/id_ed25519.pub, else ~/.ssh/id_rsa.pub; must already be in
# the DigitalOcean account), SSH_FROM (CIDR allowed for SSH, default: this
# machine's current public IPv4 /32), NAME, REGION, SIZE.
# Creates ONLY: droplet $NAME and firewall $NAME (bound by tag "claude-s40").
# Refuses if either name already exists.
# Never modifies or deletes other droplets/firewalls.
set -eu
HERE=$(cd "$(dirname "$0")" && pwd)
CTX=${DO_CONTEXT:-default}
NAME=${NAME:-claude-s40}
TAG=claude-s40
REGION=${REGION:-fra1}
SIZE=${SIZE:-s-1vcpu-512mb-10gb}
IMAGE=ubuntu-24-04-x64
PUB=${SSH_PUBKEY:-}
[ -n "$PUB" ] || { [ -f "$HOME/.ssh/id_ed25519.pub" ] && PUB=$HOME/.ssh/id_ed25519.pub || PUB=$HOME/.ssh/id_rsa.pub; }
KEY_FP=$(ssh-keygen -E md5 -lf "$PUB" | awk '{print $2}' | sed 's/^MD5://')
d() { doctl --context "$CTX" "$@"; }

if [ -z "${SSH_FROM:-}" ]; then
	IP=$(dig +short myip.opendns.com @resolver1.opendns.com -4 | tail -1)
	case "$IP" in *.*.*.*) SSH_FROM="$IP/32" ;; *) echo "could not detect public IPv4; set SSH_FROM" >&2; exit 1 ;; esac
fi

echo "== read-only checks (context $CTX)"
KEY_ID=$(d compute ssh-key list --format ID,FingerPrint --no-header | awk -v fp="$KEY_FP" '$2==fp{print $1}')
[ -n "$KEY_ID" ] || { echo "ssh key $KEY_FP ($PUB) is not in the DigitalOcean account; add it first" >&2; exit 1; }
d compute droplet list --format Name --no-header | grep -qx "$NAME" && { echo "droplet $NAME exists" >&2; exit 1; }
d compute firewall list --format Name --no-header | grep -qx "$NAME" && { echo "firewall $NAME exists" >&2; exit 1; }
PRICE=$(d compute size list --format Slug,PriceMonthly --no-header | awk -v s="$SIZE" '$1==s{print $2}')
echo "ok: ssh key id $KEY_ID, names free, $SIZE = \$$PRICE/month (DigitalOcean API list price)"

IN="protocol:tcp,ports:22,address:$SSH_FROM protocol:tcp,ports:443,address:0.0.0.0/0,address:::/0"
OUT="protocol:tcp,ports:all,address:0.0.0.0/0,address:::/0 protocol:udp,ports:all,address:0.0.0.0/0,address:::/0 protocol:icmp,address:0.0.0.0/0,address:::/0"

echo
echo "== plan"
echo "doctl --context $CTX compute tag create $TAG"
echo "doctl --context $CTX compute firewall create --name $NAME --tag-names $TAG \\"
echo "    --inbound-rules \"$IN\" \\"
echo "    --outbound-rules \"<all tcp/udp/icmp>\""
echo "doctl --context $CTX compute droplet create $NAME --region $REGION --size $SIZE \\"
echo "    --image $IMAGE --ssh-keys $KEY_ID --tag-names $TAG \\"
echo "    --user-data-file deploy/cloud-init.yaml --wait"
echo
echo "inbound : 22/tcp from $SSH_FROM only, 443/tcp from anywhere"
echo "cost    : \$$PRICE/month droplet (billed hourly while it exists); firewall free; no backups, no reserved IP"
echo "undo    : doctl --context $CTX compute droplet delete $NAME ; doctl --context $CTX compute firewall delete <id>"

if [ "${1:-}" != "--execute" ]; then
	echo; echo "PLAN ONLY. Nothing created. Run with --execute after approval."
	exit 0
fi

echo; echo "== EXECUTE"
d compute tag create "$TAG" >/dev/null   # firewall binds by tag; must exist first
d compute firewall create --name "$NAME" --tag-names "$TAG" --inbound-rules "$IN" --outbound-rules "$OUT" \
	--format ID,Name,Status --no-header
d compute droplet create "$NAME" --region "$REGION" --size "$SIZE" --image "$IMAGE" \
	--ssh-keys "$KEY_ID" --tag-names "$TAG" --user-data-file "$HERE/cloud-init.yaml" --wait \
	--format ID,Name,PublicIPv4,Status --no-header

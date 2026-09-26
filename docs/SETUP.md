# Setup guide

From zero to "Claude answers on my Nokia". Allow an hour the first time.
Commands run on your computer (macOS or Linux) unless stated otherwise.

**What you need**

- A Nokia Series 40 phone with Java (CLDC 1.1 / MIDP 2.0). Verified: Nokia
  6300 RM-217, V06.60. A SIM with mobile data.
- A Docker host with a **public IPv4 address** and TCP 443 reachable from
  the internet: a small VPS is enough (512 MB RAM). Cloud platforms that
  terminate HTTPS for you (Cloudflare, App Platform, Vercel, ...) do **not**
  work, the phone cannot complete their TLS handshake.
- A Claude API key (https://console.anthropic.com). Set a spending limit.
- On your computer: Go 1.26+, JDK 11+, Python 3 with Pillow (`pip install
  pillow`), OpenSSL 3 or LibreSSL, Docker with buildx, ssh.
- A way to install a Java app on the phone: Gammu over USB, Bluetooth file
  transfer, Nokia PC Suite, or the phone browser (step 5).

Throughout: `SERVER=root@<server-ip>` and `IP=<server-ip>`.

---

## 1. Server host

Any Linux host with Docker Engine + the compose plugin. Firewall: allow
**443/tcp** from anywhere and **22/tcp** only from your own IP.

Optional DigitalOcean helper (Ubuntu 24.04 + Docker via cloud-init, own
cloud firewall, prints a plan and creates nothing unless `--execute`):

```
cd server
DO_CONTEXT=default SSH_FROM=<your-ip>/32 deploy/do-create.sh            # plan
DO_CONTEXT=default SSH_FROM=<your-ip>/32 deploy/do-create.sh --execute  # creates droplet + firewall (billed)
```

Wait until `ssh $SERVER docker --version` works (a few minutes).

## 2. Your private certificate authority

The phone will trust **only** this root. Keep `~/.config/claude-s40/pki`
private and backed up; it never goes to the server except the server
certificate and key.

```
server/scripts/pki.sh ca     ~/.config/claude-s40/pki
server/scripts/pki.sh server ~/.config/claude-s40/pki $IP        # or a DNS name you own
server/scripts/pki.sh show   ~/.config/claude-s40/pki            # note the root's SHA-1 / MD5 fingerprints
```

Defaults: RSA-2048, SHA-1 signatures (the combination verified on a Nokia
6300; SHA-256 is untested there), root valid 10 years, server cert 2 years.
If you use a DNS name, point it straight at the server (no proxy/CDN).

## 3. Deploy the server (test mode first)

```
cd server
make test                               # optional: go vet + tests
deploy/push.sh $SERVER                  # shows the plan
deploy/push.sh $SERVER --execute        # build here, send image, start on :443
```

This starts with `MOCK_ANTHROPIC=1`: replies are fake ("[Test mode]") and
cost nothing. On the server you get `~/claude-s40-server/` with
`compose.yaml`, `.env`, `certs/` and `secrets/admin_token` (generated there,
never copied off).

Check it the way the phone will talk to it (TLS 1.0, no SNI, your CA only):

```
deploy/smoke.sh $IP 443 ~/.config/claude-s40/pki/ca.pem "$PWD/deploy/admin.sh $SERVER pair \$1 smoke"
deploy/admin.sh $SERVER devices          # then: deploy/admin.sh $SERVER revoke <smoke device id>
```

## 4. Build the phone app

```
echo "GATEWAY_URL=https://$IP" > app/app.local.properties
make -C app            # build + 42 checks + reproducible rebuild
ls app/dist            # ClaudeS40.jad, ClaudeS40.jar, SHA256SUMS
```

(Without `app.local.properties` the address can be typed in the app's
Settings instead.)

## 5. Install the app on the phone

Pick one:

- **Gammu over USB** (phone in "Nokia mode"/PC Suite mode):
  `app/tools/install-gammu.sh` (dry run), then
  `app/tools/install-gammu.sh --execute --i-understand-this-writes-to-the-phone`.
  It never overwrites; delete an older Claude S40 in the phone menu first.
- **Bluetooth**: send `ClaudeS40.jar` to the phone, open it from the inbox.
- **Nokia PC Suite** (Windows): Install applications.
- **Browser (OTA)**: serve `ClaudeS40.jad` (`text/vnd.sun.j2me.app-descriptor`)
  and `.jar` (`application/java-archive`) from any web server the phone can
  open, then open the JAD URL on the phone.

On the Nokia 6300 the app appears under Menu → Applications → Collection.
The first network access asks for permission; allow it.

## 6. Put your root CA on the phone

The phone must trust your root before the connection test can pass.

1. Temporarily allow **80/tcp** on the server firewall.
2. `server/deploy/serve-ca.sh $SERVER 10` (serves only `ca.cer` for 10 minutes
   and prints the fingerprints to compare).
3. On the phone's browser open `http://<server-ip>/ca.cer`. Before saving,
   compare the fingerprint the phone shows with the printed one. **Do not
   save it if it differs.** Save it as an authority certificate and allow
   it for applications / connections if asked.
4. Close 80/tcp again.

Also make sure the phone's mobile data works (open any plain `http://` page
in its browser). On older phones select the operator's *internet* access
point, not an old WAP profile.

## 7. Connection test and pairing

On the phone, in Claude S40:

1. **Connection test → Start**: it checks `/health` and a UTF-8 round trip
   (`/echo`) and shows the TLS version, cipher and certificate. Chat stays
   locked until it passes. If the phone asks to accept an untrusted
   certificate, say **No**: something is wrong with step 6.
2. **Settings → Options → Pair this phone**: a 6-digit code appears.
3. On your computer: `server/deploy/admin.sh $SERVER pair <code> "My Nokia"`.
   The phone fetches its access token by itself within a few seconds.
4. **Chat**: in test mode you get "[Test mode]" replies. That proves the
   whole path works.

## 8. Go live

```
server/deploy/set-key.sh $SERVER                 # in YOUR terminal; hidden input
S40_MOCK=0 server/deploy/push.sh $SERVER --execute
```

Settings you can pass to `push.sh`: `S40_MODEL` (default `claude-opus-5`),
`S40_EFFORT` (`low`), `S40_FALLBACKS` (`default` = server-side refusal
fallback; `off` for models without it), `S40_REQ_LIMIT` (100/day),
`S40_TOK_LIMIT` (100000 output tokens/day). Pairings and chats survive
redeploys (Docker volume `s40data`).

Send "Translate to English: Günaydın" from the phone, then "Make it
shorter" in the same chat.

## 9. Day to day

```
server/deploy/admin.sh $SERVER devices           # paired phones
server/deploy/admin.sh $SERVER revoke dev-xxxx   # revoke one
server/deploy/admin.sh $SERVER logs 50           # method/path/status/TLS only, no content
server/deploy/push.sh $SERVER --execute          # update after a code change
```

Updating the app: delete Claude S40 in the phone menu, install the new
build, run the connection test and pair again (deleting the app deletes its
settings), then revoke the old device.

## Troubleshooting

| Symptom on the phone | Likely cause |
|---|---|
| "Could not connect ... TLS" and nothing in `admin.sh logs` | mobile data / access point; or a CDN/proxy in front of the server |
| "signature not verified" | root CA not saved on the phone (step 6), or a SHA-256 certificate on a phone that only verifies SHA-1 |
| "host name mismatch" | server certificate issued for a different name/IP than the app uses |
| HTTP code but "not a Claude S40 server reply" | operator proxy or wrong address |
| "No credits" | the Claude API account has no credit balance |
| "Daily limit reached" | raise `S40_REQ_LIMIT` / `S40_TOK_LIMIT` |
| "Access code invalid or revoked" | pair again |

# Setup guide

From zero to "AI answers on my Nokia". Allow an hour the first time.
Commands run on your computer (macOS or Linux) unless stated otherwise.

**What you need**

- A Nokia Series 40 or Symbian S60 phone with Java (CLDC 1.1 / MIDP 2.0).
  Verified: Nokia 6300 RM-217 (V06.60) and Nokia E63. A SIM with mobile
  data (or WLAN on S60).
- A Docker host with a **public IPv4 address** and TCP 443 reachable from
  the internet: a small VPS is enough (512 MB RAM). Cloud platforms that
  terminate HTTPS for you (Cloudflare, App Platform, Vercel, ...) do **not**
  work, the phone cannot complete their TLS handshake.
- An API key for at least one provider: Claude (https://console.anthropic.com),
  OpenAI (https://platform.openai.com), Gemini (https://aistudio.google.com,
  a paid-tier key) or xAI Grok (https://console.x.ai). Set a spending limit
  with each.
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
make -C app            # build + 48 checks + reproducible rebuild
ls app/dist            # AIKON.jad, AIKON.jar, SHA256SUMS
```

(Without `app.local.properties` the address can be typed in the app's
Settings instead.)

Not building it yourself: every release on GitHub (tag `vX.Y.Z`) has
`AIKON.jad`, `AIKON.jar` and `SHA256SUMS` built by GitHub Actions from that
tag, without a server address (type it in the setup wizard). The build is
reproducible, so `make -C app` at the same tag gives the same checksums.
The phone cannot download from GitHub itself (TLS 1.0 only): download on a
computer, then use one of the ways below.

## 5. Install the app on the phone

Pick one:

- **Gammu over USB** (phone in "Nokia mode"/PC Suite mode):
  `app/tools/install-gammu.sh` (dry run), then
  `app/tools/install-gammu.sh --execute --i-understand-this-writes-to-the-phone`.
  It never overwrites unless you add `--replace` (deletes only the old
  AIKON / Claude S40 JAD and JAR first); otherwise delete the older app in the phone menu first.
- **Bluetooth**: send `AIKON.jar` to the phone, open it from the inbox.
- **Nokia PC Suite** (Windows): Install applications.
- **Browser (OTA)**: serve `AIKON.jad` (`text/vnd.sun.j2me.app-descriptor`)
  and `.jar` (`application/java-archive`) from any web server the phone can
  open, then open the JAD URL on the phone.

On the Nokia 6300 the app appears under Menu → Applications → Collection.
The first network access asks for permission; allow it.

**Symbian S60 (tested: Nokia E63, S60 3rd Edition FP1).**

- **Installing:** use the browser (OTA). Files sent over Bluetooth from Windows
  opened from the Messaging inbox with "Messaging feature not supported".
  The app appears under Menu → Installat.
- **Permissions:** an unsigned app cannot get "Always allowed" network
  access on S60 3rd Edition. Go to App. mgr. → AIKON → Options →
  Open (or Settings). Set Network access to "Ask first time" (one question
  per start), and Access point to your access point (e.g. WLAN), so it is not
  asked for every request. Leave the app with the Menu key instead of Exit to
  keep the answer.
- **Keys:** the QWERTY keyboard sends letters, not digits. The app maps
  them (R T Y = 1 2 3, F G H = 4 5 6, V B N = 7 8 9, M = 0, U = \*, J = #),
  and Enter works as the centre key.

## 6. Put your root CA on the phone

The phone must trust your root before the connection test can pass.

1. Temporarily allow **80/tcp** on the server firewall.
2. `server/deploy/serve-ca.sh $SERVER 10` (serves only `ca.cer` for 10 minutes
   and prints the fingerprints to compare).
3. On the phone's browser open `http://<server-ip>/ca.cer`. Before saving,
   compare the fingerprint the phone shows with the printed one. **Do not
   save it if it differs.** Save it as an authority certificate and allow
   it for applications / connections if asked. On S60 the trust setting that
   HTTPS needs is **Internet** (Certificate management → the certificate →
   Options → Trust settings); App. installation is not needed.
4. Close 80/tcp again.

Also make sure the phone's mobile data works (open any plain `http://` page
in its browser). On older phones select the operator's *internet* access
point, not an old WAP profile.

## 7. Connection test and pairing

On the phone, in AIKON. A fresh install opens a setup wizard
(server address, connection test, pairing: "Setup 1/3" to "3/3"; the
language follows the phone and can be changed in Settings)
that walks through exactly these steps; it can be skipped and reopened from
Settings → Options → Setup wizard.
A build made with `GATEWAY_URL` (as offered on your download page) asks only
for the pairing (the credit code form on a server that sells credits): the
address is in the build and the connection test runs by itself first,
staying on screen only if it fails.

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

Put the key of each provider you want on the server, in YOUR terminal
(hidden input, never in a file or the shell history):

```
server/deploy/set-key.sh $SERVER                 # Claude   (sk-ant-...)
server/deploy/set-key.sh $SERVER openai          # OpenAI   (sk-...)
server/deploy/set-key.sh $SERVER gemini          # Gemini   (AQ. or AIza...)
server/deploy/set-key.sh $SERVER xai             # xAI Grok (xai-...)
```

Then list the models the phone may choose and deploy with real calls:

```
S40_MOCK=0 \
S40_MODELS="anthropic:claude-opus-5-5=Claude Opus 5.5,openai:gpt-6-luna=GPT-6 Luna,gemini:gemini-3.8-flash=Gemini 3.8 Flash,xai:grok-4.7=Grok 4.7" \
S40_DEFAULT_MODEL=gpt-6-luna \
server/deploy/push.sh $SERVER --execute
```

`S40_MODELS` is `provider:model-id=Name,...` with the providers
`anthropic`, `openai`, `gemini` and `xai`; the phone shows them in this
order, provider first, then its models. Names are ASCII, at most 20
characters. `S40_DEFAULT_MODEL` answers new chats where no model was
chosen (and older phones); without it the first one does. List only the
models you want to pay for: the providers' own lists are long. The plan
(`push.sh` without `--execute`) names every provider whose key must be on
the server; the server does not start with a model whose key is missing.
Without `S40_MODELS` the server offers only `S40_MODEL` (Claude). Each chat
remembers its model; Options > Model switches it from the next message on.

Other settings for `push.sh`: `S40_EFFORT` (`low`, Claude's effort),
`S40_OPENAI_EFFORT` (`low`), `S40_XAI_EFFORT` (empty = the model's
default), `S40_GEMINI_EFFORT` (`low`, Gemini's thinking level),
`S40_FALLBACKS` (`default` = Claude's server-side refusal fallback; `off`
to turn it off), `S40_REQ_LIMIT` (100/day), `S40_TOK_LIMIT` (100000
output tokens/day; both shared by all models), `S40_SEARCH` (`1` = models
may search the web with their provider's search tool, `0` = never), `S40_SEARCH_LIMIT` (30 searches/day per
phone), `S40_SEARCH_MAX_USES` (3 per message), and optionally
`S40_SEARCH_COUNTRY` (e.g. `TR`), `S40_SEARCH_CITY`, `S40_SEARCH_TIMEZONE`
(e.g. `Europe/Istanbul`) for local results. Web searches are billed per
search on top of tokens. Pairings and chats survive redeploys (Docker
volume `s40data`).

**Voice messages** (optional, off by default): the server turns a short
recording into text with OpenAI's speech-to-text (paid per use, well
under a cent per 30 s clip with the default model; check OpenAI's prices).

```
server/deploy/set-key.sh $SERVER openai          # OpenAI API key, hidden input
S40_MOCK=0 S40_TRANSCRIBE=openai server/deploy/push.sh $SERVER --execute
```

`S40_TRANSCRIBE=mock` gives "[Test mode]" texts without a key.
`S40_TRANSCRIBE_LIMIT` (30/day per phone), `S40_TRANSCRIBE_MODEL`
(`gpt-4o-mini-transcribe`). On the phone: **Dictate** in the chat's
options or in the editor, speak (up to 30 s), **Done**; the text opens in
the editor, check it and press **Send**. Nothing reaches the model before
that. The phone asks for microphone access. About shows whether the phone
can record and which formats it reports.

**Photos** (always on, no extra key): in the chat's options or in the
editor, **Add a photo** → take one with the camera or choose one from the
phone (up to 1 MB); it is uploaded, then the editor opens with "What is in
this photo?" to change and **Send**. Follow-up questions in the same chat
still show the model the photo. `S40_IMAGE_LIMIT` (30 uploads/day per phone).
Each photo adds about 1000 input tokens to every message in its chat
(the newest 3 photos are sent). About shows whether the phone lets apps
use the camera.

Send "Translate to English: Günaydın" from the phone, then "Make it
shorter" in the same chat. Then try Quick prompts → Weather: a reply
marked "searched the web" ends with "Web: <sources>".

On the phone: **Chats** lists earlier conversations (open one to continue
it); in a chat, **0** loads the rest of a long reply (free, the model is
not asked again), **2/8** page, **1/3** jump between messages, **\*/#** top and
end, **5** write, **7** reading mode (one reply page by page, full width),
**9** text size. **1/3** also select a message; the centre key then offers
shorten, translate, ask about it or open it in the editor (these only fill
the editor, nothing is sent until you press Send). After an error the
centre key retries the same request. Options → Shortcuts lists every key. **New chat** asks for the provider and
model; **Options → Model** switches the open chat. Settings → Replies: web
search on/off, keep the last chat on the phone for offline reading;
Settings → Screen: full screen. Explanations behind a short note: select
it with **1/3** and press the centre key; on settings screens, Options →
Info.

## 9. Day to day

```
server/deploy/admin.sh $SERVER devices           # paired phones
server/deploy/admin.sh $SERVER revoke dev-xxxx   # revoke one
server/deploy/admin.sh $SERVER logs 50           # method/path/status/TLS only, no content
server/deploy/push.sh $SERVER --execute          # update after a code change
```

Updating the app with Gammu: `app/tools/install-gammu.sh --execute
--i-understand-this-writes-to-the-phone --replace` deletes only the old
AIKON.jad/.jar (and those of the app under its old name, Claude S40)
before installing; otherwise delete the app in the phone menu first. The
build keeps the JAR's size off a length that stalls Gammu's USB upload
(see `app/tools/package.py`). Since 0.7.2 the app keeps its setup (server
address, access code, language, notes for the AI) in `ClaudeS40/claude-s40-setup.dat` on the
memory card (or in the phone's image folder), which survives deleting the
app: the new build restores it at its first start (the phone asks for file
access) and needs no new pairing. Without that file (older builds, no file
access), run the connection test and pair again, then revoke the old
device. Settings > Options > "Reset setup" deletes the file and opens the
setup wizard. The file holds the access code: if the memory card leaves
your hands, revoke the device.

## 10. Optional: a website and downloads for phones

With a domain (e.g. `example.com`, DNS only, no proxy in front, since a CDN
breaks the phone's TLS 1.0) the same server can also serve browsers and let
phones download the app:

1. DNS: `example.com`, `www` and a short phone name such as `m` point to the
   server. A CAA record `0 issue "letsencrypt.org"` is a good idea.
2. A phone certificate that names the phone host and the IP (same root, so
   phones keep working): move the old `server.*` files of your PKI folder
   aside, then `server/scripts/pki.sh server ~/.config/claude-s40/pki m.example.com,$IP`.
3. Deploy with `S40_PUBLIC_HOSTS=example.com,www.example.com
   S40_PHONE_HOST=m.example.com S40_HTTP_PORT=80 S40_APP_DIR=app/dist` and open
   TCP 80 in the firewall (landing page and ca.cer only).
4. On a new phone: `http://m.example.com/` → save the certificate (compare
   the fingerprint) → open the AIKON link (https, verified) → install. Set
   the server address to `https://m.example.com`.

## Troubleshooting

| Symptom on the phone | Likely cause |
|---|---|
| "Could not connect ... TLS" and nothing in `admin.sh logs` | mobile data / access point; or a CDN/proxy in front of the server |
| "signature not verified" | root CA not saved on the phone (step 6), or a SHA-256 certificate on a phone that only verifies SHA-1 |
| "host name mismatch" | server certificate issued for a different name/IP than the app uses |
| HTTP code but "not this server's reply" | operator proxy or wrong address |
| "No credits" | the API account of the chat's model has no credit balance |
| "Model not offered" | the chat's model was removed from `S40_MODELS`: Options > Model |
| "Daily limit reached" | raise `S40_REQ_LIMIT` / `S40_TOK_LIMIT` |
| The model no longer searches the web | the phone's daily search budget is used up (`S40_SEARCH_LIMIT`) or web search is off in Settings / `S40_SEARCH=0` |
| "Access code invalid or revoked" | pair again |
| No **Dictate** command | the phone does not let apps record (About → Voice recording) |
| "Voice messages are not turned on on the server" | deploy with `S40_TRANSCRIBE=openai` (or `mock`) |
| No **Add a photo** command | the phone lets apps use neither the camera nor files (About → Camera) |
| "The photo is too large" from the gallery | over 1 MB: take it with the app's camera (640x480) |
| "The server could not read this recording" | the phone's audio format; `admin.sh logs` shows the format, size and length |

# Claude S40 – development notes (public)

Unofficial Claude client for Nokia Series 40 (Java ME) + its Go server.
Maintainer: Emir Karşıyakalı (github.com/emir). This repository is the only
place the project is developed. Private operational details (server address,
devices, phone) are in `CLAUDE.local.md` (gitignored), never in tracked files.

## Layout and commands

    app/     Java ME MIDlet (CLDC 1.1 / MIDP 2.0, class 46.0), package io.github.emir.claudes40
    server/  Go server: phone TLS + chat + SQLite + admin API, Docker
    docs/    SETUP.md (user guide), ARCHITECTURE.md (protocol, TLS, rules)

    make test                     server tests (race) + app build/checks
    make -C app                   build + 44 package checks + reproducible rebuild
    make -C app emu FREEJ2ME=...  optional emulator screenshots; make -C app promo
    make -C server test | docker
    server/deploy/push.sh HOST [--execute]   plan by default
    server/deploy/admin.sh HOST pair|devices|revoke|logs

Run `make test` after changes. On macOS use Homebrew's JDK if /usr/bin/java
is only a stub: `make -C app JAVA=/opt/homebrew/opt/openjdk/bin/java`.

## Rules

- Keep tracked files free of personal data, IPs, host names, device ids and
  secrets; `check.py` scans the JAR/JAD, keep the repo equally clean.
- Ask before: deploying the server, anything that writes to the phone
  (installs are run by the user with `--execute`), paid Claude API calls
  beyond the user's own use, infrastructure/firewall changes, pushing to
  GitHub.
- Phone app: CLDC 1.1 / MIDP 2.0 API only (no StringBuilder, generics,
  String.format); every user-visible string is bilingual `L.s("Türkçe",
  "English")`; sizes from getWidth()/getHeight(), softkeys as Commands,
  arrows via getGameAction(); networking only on worker threads; HTTPS only.
  Optional JSR 75 (files, calendar/to-do) only inside Files/Pim, called
  after hasFiles()/hasPim() and from worker threads (check.py enforces the
  first part); nothing is written to the calendar without the user's Save.
  Optional JSR 135 recording only inside Rec, after hasRecording(), from
  worker threads; a transcript only fills the editor, never sends itself.
  Bump VERSION/BUILD in `app/app.properties` for every build given to a phone;
  never reuse a version. JAD/manifest values must stay ASCII.
- Protocol `S40/1` is shared by phone and server: keep changes backwards
  compatible or bump both together (docs/ARCHITECTURE.md).
- Paid calls (Claude and speech-to-text): never retry automatically; keep
  request_id replay, the pending-before-call record and `uncertain`
  semantics. No exactly-once claims.
- TLS: never add a plain-HTTP fallback, never disable certificate checks,
  never offer RC4/3DES. The phone (Nokia 6300) offers only TLS 1.0 +
  RSA/AES-CBC-SHA, no SNI; it verifies SHA-1 certificates from our root.
- Logs never contain message text, replies, tokens, keys or client IPs.
- Mock replies say "[Test mode]"; never present them, or emulator results,
  as real Claude output or device results.
- "Claude" name/logo: used with the maintainer's stated permission; keep
  "unofficial" wording everywhere (TRADEMARKS.md).

# Claude S40 – phone app

Java ME MIDlet for Nokia Series 40 (CLDC 1.1 / MIDP 2.0, class file 46.0).
English and Turkish UI (follows the phone language; Settings > Language).

Features: animated splash with an original start-up jingle (MIDP tone
sequences), a first-run setup wizard, icon home menu with number-key
shortcuts, chat bubbles with timestamps, lists and paragraphs, a typing
animation with the elapsed seconds, a reading mode that pages through one
reply (whole lines only, page number, keeps its place, backlight kept on),
message actions (shorten, translate, ask about it, open in the editor; they
fill the editor and never send by themselves; save to the phone as .txt;
add to the calendar or to-do list after checking a prefilled form), replies
saved on the phone readable offline, chats list with pin / delete / search,
your own notes for Claude, a data usage counter, a Shortcuts screen,
20 everyday quick prompts (translate,
reply to a message, summarize, fix my writing, ...), light/dark theme,
three text sizes, reply chime + vibration + backlight, pairing without typing a long
code, a connection test that reports the phone's TLS details, and a local
test mode with fake replies.

## Build

```
cp app.local.properties.example app.local.properties   # set GATEWAY_URL=https://<your server>
make            # downloads pinned tools to .deps/j2me (SHA-256 checked), builds, runs 44 checks twice
```

Output: `dist/ClaudeS40.jad`, `dist/ClaudeS40.jar` (~100 KB), `dist/SHA256SUMS`.
Version and build number live only in `app.properties`.

Pipeline: ECJ compiles against the CLDC 1.1 + MIDP 2.0 API stubs, plus the
optional JSR 75 FileConnection API (MicroEmulator jar) and PIM API
(compile-only stubs in `stubs/jsr75-pim`, used only by `Files` and `Pim`),
ProGuard `-microedition` preverifies (no shrink/obfuscate), `tools/package.py`
writes a deterministic JAR and the JAD, `tools/check.py` verifies it.

## Install

See [../docs/SETUP.md](../docs/SETUP.md#5-install-the-app-on-the-phone):
`tools/install-gammu.sh` (USB, dry run by default), Bluetooth, PC Suite or OTA.

## Emulator and share images (optional)

```
make emu FREEJ2ME=/path/to/freej2me/classes    # headless screenshots, app in test mode
make promo                                     # cover, poster, square, splash.gif, logo, jingle.wav, chime.wav
```

FreeJ2ME (GPL-3.0) is not included. Emulator success is not device
compatibility.

## Code map

| File | |
|---|---|
| `ClaudeS40MIDlet` | lifecycle, navigation, settings/about forms, quick prompts |
| `Splash`, `HomeCanvas`, `ChatCanvas` | custom screens |
| `ChatSession` | conversation state, request ids, retry rules, statuses |
| `Net` | one HTTPS request with phase-aware error reporting |
| `ConnTest`, `Pairing` | connection test, pairing flow |
| `ChatList`, `SavedList` | chats on the server (pin, delete, search); replies saved on the phone |
| `CalendarForm`, `Cal` | add to calendar / to-do; Claude's entry lines |
| `Files`, `Pim` | the only JSR 75 users: .txt files, calendar and to-do list |
| `Dictation`, `Rec` | voice message screen; `Rec` is the only JSR 135 recording user |
| `Photo`, `Cam`, `PhotoPicker` | add a photo: camera (`Cam`, the only JSR 135 camera user), file picker, upload |
| `DataUsage` | mobile data counter (RMS) |
| `Backup` | setup kept in a file outside the app, restored after a reinstall |
| `Settings` | RMS record (format 6) |
| `Theme`, `Logo`, `Sound`, `L`, `Text`, `S40Message` | look, mark, tones, language, helpers, protocol |

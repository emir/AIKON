# Claude S40 phone app: CLDC 1.1 / MIDP 2.0 MIDlet.
#
#   make           build + package checks + reproducible rebuild
#   make build     -> dist/ClaudeS40.jar, dist/ClaudeS40.jad, dist/SHA256SUMS
#   make check     checks only (after build)
#   make emu       optional FreeJ2ME screenshots (FREEJ2ME=path/to/classes;
#                  EMU_W/EMU_H screen size, EMU_LANG=en|tr)
#   make promo     share images/sounds from the emulator screenshots
#   make clean
#
# Needs: a JDK 11+ (only runs the build tools), Python 3 (+ Pillow for the
# icon and promo art), curl, unzip. Build dependencies are downloaded to
# .deps/j2me and verified against deps.lock (SHA-256).

JAVA ?= java
JAVAC ?= javac
PYTHON ?= python3

DEPS  ?= .deps/j2me
ECJ   := $(DEPS)/ecj-3.33.0.jar
CLDC  := $(DEPS)/cldcapi11-2.0.4.jar
MIDP  := $(DEPS)/midpapi20-2.0.4.jar
# JSR 75: FileConnection API from MicroEmulator, PIM API from compile-only
# stubs (stubs/jsr75-pim). Optional on the phone: only Files and Pim use them.
JSR75 := $(DEPS)/microemu-jsr-75-2.0.4.jar
PROGUARD := $(DEPS)/proguard-7.9.1/lib/proguard.jar

# class version 46.0 (as the MIDlets shipped on Series 40 3rd Edition phones)
SOURCE_LEVEL := 1.3
TARGET_LEVEL := 1.2

BUILD ?= build
DIST  ?= dist
# untracked, per installation: GATEWAY_URL=https://your-server (see docs/SETUP.md)
LOCAL ?= app.local.properties
FILE_BASE := $(shell sed -n 's/^FILE_BASE=//p' app.properties)

SRCS := $(shell find src -name '*.java' | sort)
STUB_SRCS := $(shell find stubs -name '*.java' | sort)
STUBS = $(BUILD)/stubs

.PHONY: all build check deps clean emu promo

all: build check

deps:
	@tools/fetch_deps.sh $(DEPS)

build: deps
	rm -rf $(BUILD)/classes $(BUILD)/preverified $(BUILD)/res $(STUBS) $(DIST)
	mkdir -p $(BUILD)/classes $(BUILD)/preverified $(BUILD)/res $(STUBS)
	$(PYTHON) tools/make_art.py icon $(BUILD)/res/icon.png
	$(JAVA) -jar $(ECJ) -source $(SOURCE_LEVEL) -target $(TARGET_LEVEL) \
		-bootclasspath $(CLDC) -encoding UTF-8 -g:none \
		-failOnWarning -d $(STUBS) $(STUB_SRCS)
	$(JAVA) -jar $(ECJ) -source $(SOURCE_LEVEL) -target $(TARGET_LEVEL) \
		-bootclasspath $(CLDC):$(MIDP):$(JSR75):$(STUBS) -encoding UTF-8 -g:none \
		-failOnWarning -d $(BUILD)/classes $(SRCS)
	$(JAVA) -jar $(PROGUARD) @claude-s40.pro \
		-injars $(BUILD)/classes -outjars $(BUILD)/preverified \
		-libraryjars $(CLDC) -libraryjars $(MIDP) -libraryjars $(JSR75) -libraryjars $(STUBS)
	$(PYTHON) tools/package.py app.properties $(BUILD)/preverified $(DIST) $(LOCAL) $(BUILD)/res

check:
	@test -f $(DIST)/SHA256SUMS || { echo "run 'make build' first" >&2; exit 1; }
	$(PYTHON) tools/check.py app.properties $(DIST) $(CLDC) $(MIDP) $(JSR75) $(STUBS)
	@echo "reproducibility: clean rebuild into $(BUILD)/repro"
	@rm -rf $(BUILD)/repro
	@$(MAKE) --no-print-directory BUILD=$(BUILD)/repro/build DIST=$(BUILD)/repro/dist build >$(BUILD)/repro.log 2>&1 \
		|| { cat $(BUILD)/repro.log; exit 1; }
	@cmp $(DIST)/SHA256SUMS $(BUILD)/repro/dist/SHA256SUMS \
		&& echo "PASS  reproducible: second build has identical SHA-256" \
		|| { echo "FAIL  reproducible: SHA-256 differs"; exit 1; }

# Optional: headless FreeJ2ME run in the app's test mode (no network).
# FreeJ2ME (GPL-3) is not included; build it and point FREEJ2ME at its
# compiled classes. Emulator success is not device compatibility.
FREEJ2ME ?=
EMU_W ?= 240
EMU_H ?= 320
EMU_LANG ?= en

emu:
	@test -f $(DIST)/$(FILE_BASE).jar || { echo "run 'make build' first" >&2; exit 1; }
	@test -n "$(FREEJ2ME)" -a -d "$(FREEJ2ME)/org/recompile/mobile" || { echo "set FREEJ2ME=/path/to/freej2me/classes" >&2; exit 1; }
	rm -rf $(BUILD)/emu && mkdir -p $(BUILD)/emu/classes
	$(JAVAC) --release 8 -nowarn -cp $(FREEJ2ME) -d $(BUILD)/emu/classes emu/EmuShot.java
	cd $(BUILD)/emu && $(JAVA) -Djava.awt.headless=true -cp $(abspath $(FREEJ2ME)):classes EmuShot \
		$(abspath $(DIST)/$(FILE_BASE).jar) shots $(EMU_W) $(EMU_H) $(EMU_LANG) 2>&1 | tee emu.log

promo:
	@test -d $(BUILD)/emu/shots || { echo "run 'make emu' first" >&2; exit 1; }
	$(PYTHON) tools/promo.py $(BUILD)/emu/shots $(BUILD)/promo

clean:
	rm -rf $(BUILD) $(DIST)

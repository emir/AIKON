#!/usr/bin/env python3
"""
Verify a MIDlet JAR/JAD pair against app.properties and the target platform.

  check.py app.properties DIST_DIR CLDC_JAR MIDP_JAR [OPTIONAL_API_JAR_OR_DIR...]

AIKON specifics: exact
name/file checks, only MIDlet-Permissions-Opt = Connector.https, no plain http:// URL in the classes, and a secret scan of every packaged
byte. The optional JSR 75 APIs (FileConnection, PIM) may only be used by
the classes Files and Pim, JSR 135 recording (RecordControl) only by Rec,
the JSR 135 camera (VideoControl) only by Cam, so phones without them never
load such a class.

Exit 0 only if every check passes. Checks:
  zip integrity, manifest first, attribute agreement manifest/JAD/properties,
  MIDlet class present and a MIDlet subclass, configuration/profile,
  Jar-URL/Jar-Size, class file version 46.0, Java ME preverification
  (StackMap where needed, no jsr/ret, no Java 6 StackMapTable), every
  referenced class and member resolves to the JAR or the CLDC 1.1/MIDP 2.0
  (+ JSR 75/135) API, JSR 75 only in Files/Pim, JSR 135 recording only in
  Rec and camera only in Cam, no platform classes packaged,
  no mandatory permissions / notify URLs / push, SHA256SUMS.
"""

import hashlib
import os
import struct
import sys
import zipfile

TARGET_VERSION = (46, 0)       # as the phone's own 06.60 MIDlets
FORBIDDEN_ATTR_PREFIXES = ("MIDlet-Install-Notify", "MIDlet-Delete-Notify",
                           "MIDlet-Push-")
ALLOWED_ATTRS = {"Manifest-Version", "MIDlet-Name", "MIDlet-Vendor",
                 "MIDlet-Version", "MIDlet-Description", "MIDlet-1",
                 "MicroEdition-Configuration", "MicroEdition-Profile",
                 "MIDlet-Permissions-Opt", "ClaudeS40-Build", "ClaudeS40-Gateway",
                 "MIDlet-Jar-URL", "MIDlet-Jar-Size",
                 "Nokia-MIDlet-S60-Selection-Key-Compatibility"}
EXPECTED_NAME = "AIKON"
EXPECTED_FILE_BASE = "AIKON"
ONLY_PERMISSION = "javax.microedition.io.Connector.https"
# optional APIs and the only classes allowed to reference them:
# JSR 75 (files, PIM) in Files/Pim, JSR 135 recording in Rec, camera in Cam
RECORDING = "javax/microedition/media/control/RecordControl"
CAMERA = ("javax/microedition/media/control/VideoControl", "javax/microedition/media/control/GUIControl")
OPTIONAL_PACKAGES = ("javax/microedition/io/file/", "javax/microedition/pim/", RECORDING) + CAMERA
OPTIONAL_USERS = {"Files", "Pim", "Rec", "Cam"}
# secret-looking content that must never be in the JAR/JAD
SECRET_PATTERNS = [
    (rb"sk-ant-", "Anthropic API key prefix"),
    (rb"ANTHROPIC_API_KEY", "API key variable name"),
    (rb"DEVICE_TOKENS", "token secret name"),
    (rb"(?i)cf[-_]?api[-_]?token", "Cloudflare API token"),
    (rb"(?<![0-9])[0-9]{16,}(?![0-9])", "long digit run (access code?)"),
    (rb"Bearer [A-Za-z0-9]{8,}", "embedded bearer token"),
    (rb"(?i)-----BEGIN [A-Z ]*PRIVATE KEY", "private key"),
]

# ------------------------------------------------------------ class parser

OPLEN = [1] * 256
for op in (0x10, 0x12, 0x15, 0x16, 0x17, 0x18, 0x19,
           0x36, 0x37, 0x38, 0x39, 0x3A, 0xA9, 0xBC):
    OPLEN[op] = 2
for op in [0x11, 0x13, 0x14, 0x84, 0xA7, 0xA8, 0xBB, 0xBD, 0xC0, 0xC1,
           0xC6, 0xC7] + list(range(0x99, 0xA7)) + list(range(0xB2, 0xB9)):
    OPLEN[op] = 3
OPLEN[0xC5] = 4
for op in (0xB9, 0xBA, 0xC8, 0xC9):
    OPLEN[op] = 5
BRANCHES = set(range(0x99, 0xA9)) | {0xAA, 0xAB, 0xC6, 0xC7, 0xC8, 0xC9}


def u2(b, o):
    return struct.unpack_from(">H", b, o)[0]


def u4(b, o):
    return struct.unpack_from(">I", b, o)[0]


def parse_class(b):
    if b[:4] != b"\xCA\xFE\xBA\xBE":
        raise ValueError("bad magic")
    minor, major = u2(b, 4), u2(b, 6)
    n = u2(b, 8)
    cp = [None] * n
    o, i = 10, 1
    while i < n:
        tag = b[o]
        if tag == 1:
            ln = u2(b, o + 1)
            cp[i] = (1, b[o + 3:o + 3 + ln].decode("utf-8", "replace"))
            o += 3 + ln
        elif tag in (3, 4):
            cp[i] = (tag, None)
            o += 5
        elif tag in (5, 6):
            cp[i] = (tag, None)
            o += 9
            i += 1
        elif tag in (7, 8):
            cp[i] = (tag, u2(b, o + 1))
            o += 3
        elif tag in (9, 10, 11, 12):
            cp[i] = (tag, (u2(b, o + 1), u2(b, o + 3)))
            o += 5
        else:
            raise ValueError(f"constant pool tag {tag} not valid for CLDC")
        i += 1

    def utf(idx):
        return cp[idx][1]

    access, this_i, super_i = u2(b, o), u2(b, o + 2), u2(b, o + 4)
    this = utf(cp[this_i][1])
    sup = utf(cp[super_i][1]) if super_i else None
    o += 6
    nif = u2(b, o)
    interfaces = [utf(cp[u2(b, o + 2 + 2 * k)][1]) for k in range(nif)]
    o += 2 + 2 * nif

    def members(o):
        cnt = u2(b, o)
        o += 2
        out = []
        for _ in range(cnt):
            name, desc = utf(u2(b, o + 2)), utf(u2(b, o + 4))
            na = u2(b, o + 6)
            o += 8
            attrs = []
            for _ in range(na):
                an = utf(u2(b, o))
                al = u4(b, o + 2)
                attrs.append((an, b[o + 6:o + 6 + al]))
                o += 6 + al
            out.append((name, desc, attrs))
        return out, o

    fields, o = members(o)
    methods, o = members(o)

    refs = []   # (kind, owner, name, desc)
    classes = set()
    for e in cp:
        if not e:
            continue
        if e[0] == 7:
            classes.add(utf(e[1]))
        elif e[0] in (9, 10, 11):
            owner = utf(cp[e[1][0]][1])
            nt = cp[e[1][1]][1]
            refs.append(("field" if e[0] == 9 else "method", owner,
                         utf(nt[0]), utf(nt[1])))
    return dict(version=(major, minor), this=this, super=sup, interfaces=interfaces, fields=fields,
                methods=methods, refs=refs, classes=classes,
                cp_utf=[e[1] for e in cp if e and e[0] == 1])


def types_in(desc):
    out, i = [], 0
    while i < len(desc):
        if desc[i] == "L":
            j = desc.index(";", i)
            out.append(desc[i + 1:j])
            i = j + 1
        else:
            i += 1
    return out


def code_info(code_attr):
    """-> (has_branch_or_handler, has_jsr_ret, sub-attribute names)"""
    b = code_attr
    code_len = u4(b, 4)
    code = b[8:8 + code_len]
    pc, branch, jsr = 0, False, False
    while pc < code_len:
        op = code[pc]
        if op in BRANCHES:
            branch = True
        if op in (0xA8, 0xA9, 0xC9):
            jsr = True
        if op == 0xAA:                       # tableswitch
            p = (pc + 4) & ~3
            lo, hi = struct.unpack_from(">ii", code, p + 4)
            pc = p + 12 + 4 * (hi - lo + 1)
        elif op == 0xAB:                     # lookupswitch
            p = (pc + 4) & ~3
            npairs = struct.unpack_from(">i", code, p + 4)[0]
            pc = p + 8 + 8 * npairs
        elif op == 0xC4:                     # wide
            pc += 6 if code[pc + 1] == 0x84 else 4
        else:
            pc += OPLEN[op]
    o = 8 + code_len
    exc = u2(b, o)
    if exc:
        branch = True
    o += 2 + 8 * exc
    return branch, jsr


# ------------------------------------------------------------ helpers

def parse_attrs(text):
    out = {}
    for line in text.replace("\r\n", "\n").split("\n"):
        if not line.strip():
            continue
        k, v = line.split(":", 1)
        out[k.strip()] = v.strip()
    return out


def read_props(path):
    p = {}
    for line in open(path, encoding="utf-8"):
        line = line.strip()
        if line and not line.startswith("#"):
            k, v = line.split("=", 1)
            p[k.strip()] = v.strip()
    return p


def load_api(paths):
    """Classes from API jars or class directories (compile-only stubs)."""
    api = {}
    for j in paths:
        if os.path.isdir(j):
            for dp, _, fn in os.walk(j):
                for f in fn:
                    if f.endswith(".class"):
                        c = parse_class(open(os.path.join(dp, f), "rb").read())
                        api[c["this"]] = c
            continue
        z = zipfile.ZipFile(j)
        for n in z.namelist():
            if n.endswith(".class"):
                c = parse_class(z.read(n))
                api[c["this"]] = c
    return api


def main():
    props_path, dist, cldc, midp = sys.argv[1:5]
    optional_api = sys.argv[5:]
    p = read_props(props_path)
    results = []

    def check(name, ok, detail=""):
        results.append((name, bool(ok), str(detail)))

    jar_path = os.path.join(dist, p["FILE_BASE"] + ".jar")
    jad_path = os.path.join(dist, p["FILE_BASE"] + ".jad")
    check("file names are AIKON.jar / AIKON.jad", p["FILE_BASE"] == EXPECTED_FILE_BASE)
    z = zipfile.ZipFile(jar_path)

    check("JAR/ZIP integrity (testzip)", z.testzip() is None)
    names = z.namelist()
    check("manifest is the first entry", names and names[0] == "META-INF/MANIFEST.MF", names[:1])

    mf = parse_attrs(z.read("META-INF/MANIFEST.MF").decode("utf-8"))
    jad = parse_attrs(open(jad_path, encoding="utf-8").read())

    for key, pk in (("MIDlet-Name", "NAME"), ("MIDlet-Vendor", "VENDOR"),
                    ("MIDlet-Version", "VERSION"), ("ClaudeS40-Build", "BUILD"),
                    ("MIDlet-Description", "DESCRIPTION"),
                    ("MIDlet-Permissions-Opt", "PERMISSIONS_OPT"),
                    ("MicroEdition-Configuration", "CONFIGURATION"),
                    ("MicroEdition-Profile", "PROFILE")):
        check(f"{key} manifest = JAD = app.properties",
              mf.get(key) == jad.get(key) == p[pk], f"{mf.get(key)} / {jad.get(key)} / {p[pk]}")
    check("MIDlet-Name is exactly 'AIKON'", mf.get("MIDlet-Name") == EXPECTED_NAME, mf.get("MIDlet-Name"))
    icon = p.get("ICON", "")
    check("MIDlet-1 icon field matches app.properties ICON",
          mf.get("MIDlet-1", ",,").split(",")[1].strip() == icon, mf.get("MIDlet-1"))
    if icon:
        check("MIDlet icon present in JAR", icon.lstrip("/") in z.namelist(), icon)
    check("MIDlet-1 display name is 'AIKON'",
          mf.get("MIDlet-1", "").split(",")[0] == EXPECTED_NAME, mf.get("MIDlet-1"))
    check("MicroEdition-Configuration is CLDC-1.1", mf.get("MicroEdition-Configuration") == "CLDC-1.1")
    check("MicroEdition-Profile is MIDP-2.0", mf.get("MicroEdition-Profile") == "MIDP-2.0")
    check("MIDlet-1 manifest = JAD", mf.get("MIDlet-1") == jad.get("MIDlet-1"), mf.get("MIDlet-1"))
    check("MIDlet-Version format major.minor.micro",
          all(x.isdigit() for x in mf.get("MIDlet-Version", "").split(".")) and
          len(mf.get("MIDlet-Version", "").split(".")) in (2, 3))

    main_cls = mf.get("MIDlet-1", ",,").split(",")[2].strip().replace(".", "/")
    check("MIDlet class present in JAR", main_cls + ".class" in names, main_cls)
    check("MIDlet-Jar-URL equals JAR file name",
          jad.get("MIDlet-Jar-URL") == os.path.basename(jar_path), jad.get("MIDlet-Jar-URL"))
    size = os.path.getsize(jar_path)
    check("MIDlet-Jar-Size equals real JAR size", jad.get("MIDlet-Jar-Size") == str(size),
          f"{jad.get('MIDlet-Jar-Size')} vs {size}")

    bad = [k for k in list(mf) + list(jad) if k.startswith(FORBIDDEN_ATTR_PREFIXES)]
    check("no install-delete notify / push attributes", not bad, bad)
    check("no mandatory MIDlet-Permissions",
          "MIDlet-Permissions" not in mf and "MIDlet-Permissions" not in jad)
    check("only optional permission is Connector.https",
          mf.get("MIDlet-Permissions-Opt") == jad.get("MIDlet-Permissions-Opt") == ONLY_PERMISSION,
          mf.get("MIDlet-Permissions-Opt"))
    gw = jad.get("ClaudeS40-Gateway")
    check("ClaudeS40-Gateway absent or https:// (never http)",
          gw is None or gw.startswith("https://"), gw)
    raw_jad = open(jad_path, "rb").read()
    check("JAD/manifest values are ASCII",
          raw_jad.isascii() and z.read("META-INF/MANIFEST.MF").isascii())
    extra = [k for k in list(mf) + list(jad) if k not in ALLOWED_ATTRS]
    check("no unexpected attributes", not extra, extra)
    check("manifest has no JAD-only attributes",
          "MIDlet-Jar-Size" not in mf and "MIDlet-Jar-URL" not in mf)

    # classes
    api = load_api([cldc, midp])
    optional = load_api(optional_api)
    # only the javax API from these jars (the MicroEmulator jar also has its implementation)
    optional = {n: c for n, c in optional.items() if n.startswith(OPTIONAL_PACKAGES)}
    check("JSR 75 API present for the reference check (FileConnection, PIM)",
          "javax/microedition/io/file/FileConnection" in optional and "javax/microedition/pim/PIM" in optional,
          sorted(optional)[:4])
    check("JSR 135 RecordControl / VideoControl stubs present for the reference check",
          RECORDING in optional and all(c in optional for c in CAMERA))
    api.update(optional)
    classes = {}
    for n in names:
        if n.endswith(".class"):
            c = parse_class(z.read(n))
            classes[c["this"]] = c
        elif n == p.get("ICON", "").lstrip("/") and n:
            data = z.read(n)
            ok = data[:8] == b"\x89PNG\r\n\x1a\n"
            w, h = struct.unpack(">II", data[16:24]) if ok else (0, 0)
            check("MIDlet icon is a PNG in the JAR", ok, f"{n} {w}x{h}")
            check("MIDlet icon is small (<= 64x64, <= 8 KiB)", w <= 64 and h <= 64 and len(data) <= 8192,
                  f"{w}x{h} {len(data)} B")
        elif n != "META-INF/MANIFEST.MF":
            check("only classes + manifest (+ icon) in JAR", False, n)
    pkg = main_cls.rsplit("/", 1)[0] + "/"
    check("no platform/other packages packaged",
          all(c.startswith(pkg) for c in classes), sorted(classes))

    http_consts = sorted({u for c in classes.values() for u in c["cp_utf"]
                          if "http://" in u})
    check("no plain http:// URL in class constants (no HTTP fallback)",
          not http_consts, http_consts)

    import re as _re
    hits = []
    blobs = [("JAD", raw_jad)] + [(n, z.read(n)) for n in names]
    for label, data in blobs:
        for pat, what in SECRET_PATTERNS:
            if _re.search(pat, data):
                hits.append(f"{label}: {what}")
    check("secret scan: no key/token patterns in JAR entries or JAD", not hits, hits)

    vers = {c["version"] for c in classes.values()}
    check(f"class file version {TARGET_VERSION[0]}.{TARGET_VERSION[1]} (target 1.2, as 06.60 MIDlets)",
          vers == {TARGET_VERSION}, sorted(vers))

    need_sm, has_sm, jsr, smt = 0, 0, [], []
    for c in classes.values():
        for name, desc, attrs in c["methods"]:
            for an, data in attrs:
                if an != "Code":
                    continue
                branch, j = code_info(data)
                # names of the Code attribute's own sub-attributes
                code_len = u4(data, 4)
                o = 8 + code_len
                o += 2 + 8 * u2(data, o)          # exception table
                subnames = []
                na = u2(data, o)
                o += 2
                for _ in range(na):
                    subnames.append(lookup_utf(z, c, u2(data, o)))
                    o += 6 + u4(data, o + 2)
                if j:
                    jsr.append(f"{c['this']}.{name}")
                if "StackMapTable" in subnames:
                    smt.append(f"{c['this']}.{name}")
                if branch:
                    need_sm += 1
                    if "StackMap" in subnames:
                        has_sm += 1
    check("preverified: StackMap present in every method with branches/handlers",
          need_sm == has_sm, f"{has_sm}/{need_sm}")
    check("no jsr/ret instructions", not jsr, jsr)
    check("no Java 6 StackMapTable attributes", not smt, smt)

    # reference resolution: superclasses AND superinterfaces (JVMS 5.4.3.3/4;
    # needed for e.g. HttpConnection.openInputStream from InputConnection)
    def resolve(owner, kind, name, desc):
        seen = set()
        todo = [owner]
        while todo:
            cur = todo.pop()
            if not cur or cur in seen:
                continue
            seen.add(cur)
            c = classes.get(cur) or api.get(cur)
            if not c:
                return False
            pool = c["methods"] if kind == "method" else c["fields"]
            if any(m[0] == name and m[1] == desc for m in pool):
                return True
            todo.append(c["super"])
            todo.extend(c["interfaces"])
        # Object methods on interfaces
        return kind == "method" and any(
            m[0] == name and m[1] == desc for m in api["java/lang/Object"]["methods"])

    unresolved_cls, unresolved_mem = set(), []
    for c in classes.values():
        refd = set(c["classes"])
        for kind, owner, name, desc in c["refs"]:
            refd.add(owner)
            refd.update(types_in(desc))
        for _, desc, _ in c["methods"] + c["fields"]:
            refd.update(types_in(desc))
        for r in refd:
            base = r.lstrip("[")
            if base.startswith("L"):
                base = base[1:].rstrip(";")
            if len(base) == 1:        # primitive array
                continue
            if base not in classes and base not in api:
                unresolved_cls.add(base)
        for kind, owner, name, desc in c["refs"]:
            if owner.startswith("["):
                continue
            if not resolve(owner, kind, name, desc):
                unresolved_mem.append(f"{owner}.{name}{desc}")
    check("every referenced class is in the JAR or CLDC 1.1/MIDP 2.0 (+ JSR 75/135) API",
          not unresolved_cls, sorted(unresolved_cls))
    check("every referenced field/method exists in the JAR or CLDC 1.1/MIDP 2.0 (+ JSR 75/135) API",
          not unresolved_mem, unresolved_mem)

    # a phone without JSR 75 / JSR 135 recording must never load a class that refers to it
    users, rec_users, cam_users = set(), set(), set()
    for c in classes.values():
        refd = set(c["classes"]) | {r[1] for r in c["refs"]}
        for r in c["refs"]:
            refd.update(types_in(r[3]))
        for _, desc, _ in c["methods"] + c["fields"]:
            refd.update(types_in(desc))
        name = c["this"].rsplit("/", 1)[-1].split("$")[0]
        if any(x.lstrip("[L").startswith(OPTIONAL_PACKAGES) for x in refd):
            users.add(name)
        if any(x.lstrip("[L").startswith(RECORDING) for x in refd):
            rec_users.add(name)
        if any(x.lstrip("[L").startswith(CAMERA) for x in refd):
            cam_users.add(name)
    check("optional APIs used only by Files / Pim / Rec / Cam", users <= OPTIONAL_USERS, sorted(users))
    check("JSR 135 recording used only by Rec", rec_users <= {"Rec"}, sorted(rec_users))
    check("JSR 135 camera used only by Cam", cam_users <= {"Cam"}, sorted(cam_users))

    chain, cur = [], main_cls
    while cur in classes:
        cur = classes[cur]["super"]
        chain.append(cur)
    check("MIDlet class extends javax.microedition.midlet.MIDlet",
          "javax/microedition/midlet/MIDlet" in chain, chain)

    # Gammu's 2000-byte parts: a last part whose USB frame (+20 bytes) is a
    # multiple of 64 stalls the upload (see package.py); the JAD goes over
    # with CRLF line ends
    def stalls(n):
        last = n % 2000 or 2000
        return (last + 20) % 64 == 0
    jar_n = os.path.getsize(jar_path)
    jad_raw = open(jad_path, "rb").read()
    jad_n = len(jad_raw) + jad_raw.count(b"\n")
    check("JAR/JAD sizes do not stall a Gammu upload", not stalls(jar_n) and not stalls(jad_n),
          f"jar {jar_n}, jad {jad_n}")

    sums = {}
    for line in open(os.path.join(dist, "SHA256SUMS")):
        h, n = line.split()
        sums[n] = h
    check("SHA256SUMS matches JAR and JAD", all(
        hashlib.sha256(open(os.path.join(dist, n), "rb").read()).hexdigest() == h
        for n, h in sums.items()) and len(sums) == 2)

    width = max(len(r[0]) for r in results)
    for name, ok, detail in results:
        print(f"{'PASS' if ok else 'FAIL'}  {name.ljust(width)}  {detail if not ok or detail else ''}"[:220])
    failed = [r for r in results if not r[1]]
    print(f"\n{len(results) - len(failed)}/{len(results)} checks passed")
    return 1 if failed else 0


_UTF_CACHE = {}


def lookup_utf(z, c, idx):
    # attribute names are CONSTANT_Utf8 entries of the owning class
    key = (c["this"], idx)
    if key not in _UTF_CACHE:
        b = z.read(c["this"] + ".class")
        _UTF_CACHE[key] = cp_utf_at(b, idx)
    return _UTF_CACHE[key]


def cp_utf_at(b, want):
    n = u2(b, 8)
    o, i = 10, 1
    while i < n:
        tag = b[o]
        if tag == 1:
            ln = u2(b, o + 1)
            if i == want:
                return b[o + 3:o + 3 + ln].decode("utf-8", "replace")
            o += 3 + ln
        elif tag in (3, 4):
            o += 5
        elif tag in (5, 6):
            o += 9
            i += 1
        elif tag in (7, 8):
            o += 3
        else:
            o += 5
        i += 1
    return None


if __name__ == "__main__":
    sys.exit(main())

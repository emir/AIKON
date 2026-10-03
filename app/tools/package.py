#!/usr/bin/env python3
"""
Deterministic MIDlet packaging: JAR first, then JAD from the final JAR.

  package.py app.properties CLASSES_DIR DIST_DIR [LOCAL_PROPERTIES [RES_DIR [DEFLATED_DIR]]]

AIKON differences: MIDlet-Description, one optional permission
(MIDlet-Permissions-Opt: https), and an optional ClaudeS40-Gateway URL taken
from an untracked app.local.properties. Secrets are never packaged.

- Manifest is the first entry; entries sorted; fixed timestamps; fixed
  permissions -> same inputs give the same bytes.
- Entry data: from DEFLATED_DIR (raw deflate per file, made by
  tools/ZopfliDir.java; about 5% smaller), else zlib level 9 (the manifest
  always). The ZIP is written here, since zipfile cannot take ready data.
- JAD is written after the JAR is closed; MIDlet-Jar-Size is the real size.
- Only MIDlet-Permissions-Opt (HTTPS); no install/delete notify URLs, no push.
"""

import hashlib
import os
import struct
import sys
import zlib

FIXED_TIME = (2000, 1, 1, 0, 0, 0)


def read_props(path):
    props = {}
    for line in open(path, encoding="utf-8"):
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        k, v = line.split("=", 1)
        props[k.strip()] = v.strip()
    return props


def attributes(p, local):
    # order matters only for readability; same list used for manifest and JAD
    attrs = [
        ("MIDlet-Name", p["NAME"]),
        ("MIDlet-Vendor", p["VENDOR"]),
        ("MIDlet-Version", p["VERSION"]),
        ("MIDlet-Description", p["DESCRIPTION"]),
        ("MIDlet-1", f"{p['NAME']},{p.get('ICON', '')},{p['MAIN_CLASS']}"),
        ("MicroEdition-Configuration", p["CONFIGURATION"]),
        ("MicroEdition-Profile", p["PROFILE"]),
        ("MIDlet-Permissions-Opt", p["PERMISSIONS_OPT"]),
        ("ClaudeS40-Build", p["BUILD"]),
        # S60 3rd Edition FP2 and later: deliver the centre key to
        # Canvas.keyPressed instead of opening the Options menu; ignored by
        # S60 3rd FP1 (E63) and Series 40.
        ("Nokia-MIDlet-S60-Selection-Key-Compatibility", "true"),
    ]
    url = local.get("GATEWAY_URL", "")
    if url:
        if not url.startswith("https://") or any(c in url for c in " \t\r\n"):
            sys.exit("GATEWAY_URL must be a plain https:// URL")
        attrs.append(("ClaudeS40-Gateway", url.rstrip("/")))
    for k, v in attrs:
        if not v.isascii():
            sys.exit(f"{k}: JAD/manifest values must be ASCII")
    return attrs


# Gammu sends files to Nokia phones in 2000-byte parts. A part whose USB
# frame (14 request + 6 Phonet header + data bytes) is a multiple of 64
# needs a zero-length packet that is never sent, so the phone waits until
# the transfer times out (2026-10-02: AIKON.jar stuck twice at 142000 of
# 142940 bytes: 940 + 20 = 15 x 64). Only the last part can be short.
GAMMU_PART = 2000
USB_PACKET = 64
FRAME_OVERHEAD = 20


def gammu_stalls(n):
    last = n % GAMMU_PART or GAMMU_PART
    return (last + FRAME_OVERHEAD) % USB_PACKET == 0


def dos_time(t):
    y, mo, d, h, mi, sec = t
    return (h << 11 | mi << 5 | sec // 2), ((y - 1980) << 9 | mo << 5 | d)


def write_zip(path, entries, comment):
    """entries: (name, data, deflated data); stored as deflate, DOS time FIXED_TIME,
    made by MS-DOS, external attributes 0600 << 16, no extra fields (as zipfile
    wrote them before)."""
    tm, dt = dos_time(FIXED_TIME)
    central = []
    with open(path, "wb") as f:
        for name, data, packed in entries:
            n = name.encode("ascii")
            crc = zlib.crc32(data) & 0xFFFFFFFF
            central.append(struct.pack("<4s6H3L5H2L", b"PK\x01\x02", 20, 20, 0, 8, tm, dt, crc,
                                       len(packed), len(data), len(n), 0, 0, 0, 0, 0o600 << 16, f.tell()) + n)
            f.write(struct.pack("<4s5H3L2H", b"PK\x03\x04", 20, 0, 8, tm, dt, crc,
                                len(packed), len(data), len(n), 0) + n)
            f.write(packed)
        start = f.tell()
        cd = b"".join(central)
        f.write(cd)
        f.write(struct.pack("<4s4H2LH", b"PK\x05\x06", 0, 0, len(entries), len(entries),
                            len(cd), start, len(comment)) + comment)


def zlib_deflate(data):
    c = zlib.compressobj(9, zlib.DEFLATED, -15)
    return c.compress(data) + c.flush()


def main():
    props_path, classes, dist = sys.argv[1:4]
    local_path = sys.argv[4] if len(sys.argv) > 4 else ""
    res_dir = sys.argv[5] if len(sys.argv) > 5 else ""
    deflated = sys.argv[6] if len(sys.argv) > 6 else ""
    p = read_props(props_path)
    local = read_props(local_path) if local_path and os.path.exists(local_path) else {}
    attrs = attributes(p, local)
    os.makedirs(dist, exist_ok=True)

    jar_name = p["FILE_BASE"] + ".jar"
    jad_name = p["FILE_BASE"] + ".jad"
    jar_path = os.path.join(dist, jar_name)
    jad_path = os.path.join(dist, jad_name)

    manifest = "Manifest-Version: 1.0\r\n" + "".join(
        f"{k}: {v}\r\n" for k, v in attrs) + "\r\n"

    files = []
    for dp, dn, fn in os.walk(classes):
        for f in fn:
            full = os.path.join(dp, f)
            files.append(os.path.relpath(full, classes).replace(os.sep, "/"))
    files.sort()
    resources = []
    if res_dir:
        # the icon at the top, the language files under lang/
        for dp, dn, fn in os.walk(res_dir):
            for f in fn:
                resources.append(os.path.relpath(os.path.join(dp, f), res_dir).replace(os.sep, "/"))
        resources.sort()

    def entry(base, rel):
        data = open(os.path.join(base, rel), "rb").read()
        if not deflated:
            return rel, data, zlib_deflate(data)
        packed = open(os.path.join(deflated, rel), "rb").read()
        if zlib.decompress(packed, -15) != data:
            sys.exit(f"{rel}: deflated data does not match the file")
        return rel, data, packed

    m = manifest.encode("utf-8")
    entries = [("META-INF/MANIFEST.MF", m, zlib_deflate(m))]
    entries += [entry(classes, rel) for rel in files]
    entries += [entry(res_dir, rel) for rel in resources]
    # a ZIP comment of a few spaces moves the size off a stalling length
    for pad in range(3):
        write_zip(jar_path, entries, b" " * pad)
        size = os.path.getsize(jar_path)
        if not gammu_stalls(size):
            break

    # the phone receives (and keeps) the JAD with CRLF line ends; a blank
    # last line moves a stalling length off by two bytes
    for pad in range(3):
        jad = "".join(f"{k}: {v}\n" for k, v in attrs)
        jad += f"MIDlet-Jar-URL: {jar_name}\n"
        jad += f"MIDlet-Jar-Size: {size}\n"
        jad += "\n" * pad
        if not gammu_stalls(len(jad.encode("utf-8")) + jad.count("\n")):
            break
    with open(jad_path, "w", encoding="utf-8", newline="") as f:
        f.write(jad)

    with open(os.path.join(dist, "SHA256SUMS"), "w") as f:
        for name in (jar_name, jad_name):
            h = hashlib.sha256(open(os.path.join(dist, name), "rb").read()).hexdigest()
            f.write(f"{h}  {name}\n")

    print(f"{jar_path}: {size} bytes")
    print(f"{jad_path}: {os.path.getsize(jad_path)} bytes")


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""
Deterministic MIDlet packaging: JAR first, then JAD from the final JAR.

  package.py app.properties CLASSES_DIR DIST_DIR [LOCAL_PROPERTIES]

AIKON differences: MIDlet-Description, one optional permission
(MIDlet-Permissions-Opt: https), and an optional ClaudeS40-Gateway URL taken
from an untracked app.local.properties. Secrets are never packaged.

- Manifest is the first entry; entries sorted; fixed timestamps; fixed
  permissions; deflate level 9 -> same inputs give the same bytes.
- JAD is written after the JAR is closed; MIDlet-Jar-Size is the real size.
- Only MIDlet-Permissions-Opt (HTTPS); no install/delete notify URLs, no push.
"""

import hashlib
import os
import sys
import zipfile

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


def zinfo(name):
    zi = zipfile.ZipInfo(name, date_time=FIXED_TIME)
    zi.compress_type = zipfile.ZIP_DEFLATED
    zi.create_system = 0
    zi.external_attr = 0
    return zi


def main():
    props_path, classes, dist = sys.argv[1:4]
    local_path = sys.argv[4] if len(sys.argv) > 4 else ""
    res_dir = sys.argv[5] if len(sys.argv) > 5 else ""
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

    # a ZIP comment of a few spaces moves the size off a stalling length
    for pad in range(3):
        with zipfile.ZipFile(jar_path, "w") as z:
            z.writestr(zinfo("META-INF/MANIFEST.MF"), manifest.encode("utf-8"),
                       compresslevel=9)
            for rel in files:
                with open(os.path.join(classes, rel), "rb") as f:
                    z.writestr(zinfo(rel), f.read(), compresslevel=9)
            for rel in resources:
                with open(os.path.join(res_dir, rel), "rb") as f:
                    z.writestr(zinfo(rel), f.read(), compresslevel=9)
            z.comment = b" " * pad
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

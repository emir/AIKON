#!/usr/bin/env python3
"""UI strings for the language files (app/lang/xx.txt).

  tools/strings.py keys SRC_DIR            print every English UI text (one key per line, escaped)
  tools/strings.py check SRC_DIR LANG_DIR [SERVER_LANG_DIR]
                                         check the language files; print what each one covers;
                                         with SERVER_LANG_DIR also that it holds the same files
  tools/strings.py sync LANG_DIR SERVER_LANG_DIR  copy the language files to the server (it
                                         embeds them and sends them to phones: /v1/lang)
  tools/strings.py missing SRC_DIR FILE    print the keys FILE does not translate yet

Keys are the first arguments of L.t / L.f that are string literals (or
literals joined with +; the code is written in English), and the entries of
PROMPTS_EN / TITLES_EN. A
language file has one line per key: the key, a TAB, the translation;
backslash-n is a line break, backslash-t a tab, backslash-backslash a
backslash; lines starting with # are comments. check (run by every build)
fails on a malformed line, when the {0}, {1} placeholders of a translation
differ from its key's, when a file lacks a key of the sources (every
language has every text) or keeps one the sources no longer have, and when
two keys share a Java String.hashCode (the phone looks texts up by it).
"""
import filecmp
import glob
import os
import re
import shutil
import sys

LIT = re.compile(r'"((?:[^"\\]|\\.)*)"')


def java_unescape(s):
    out, i = [], 0
    while i < len(s):
        c = s[i]
        if c == "\\" and i + 1 < len(s):
            n = s[i + 1]
            out.append({"n": "\n", "t": "\t", '"': '"', "\\": "\\", "'": "'"}.get(n, n))
            i += 2
        else:
            out.append(c)
            i += 1
    return "".join(out)


def file_escape(s):
    return s.replace("\\", "\\\\").replace("\n", "\\n").replace("\t", "\\t")


def file_unescape(s):
    out, i = [], 0
    while i < len(s):
        c = s[i]
        if c == "\\" and i + 1 < len(s):
            n = s[i + 1]
            out.append("\n" if n == "n" else "\t" if n == "t" else n)
            i += 2
        else:
            out.append(c)
            i += 1
    return "".join(out)


def split_top(s, sep):
    d, q, parts, cur, j = 0, None, [], 0, 0
    while j < len(s):
        c = s[j]
        if q:
            if c == "\\":
                j += 2
                continue
            if c == q:
                q = None
        elif c in "\"'":
            q = c
        elif c in "([{":
            d += 1
        elif c in ")]}":
            d -= 1
        elif c == sep and d == 0:
            parts.append(s[cur:j])
            cur = j + 1
        j += 1
    parts.append(s[cur:])
    return parts


def calls(src, name):
    i = 0
    while True:
        j = src.find(name + "(", i)
        if j < 0:
            return
        k, d, q = j + len(name) + 1, 1, None
        while d > 0:
            c = src[k]
            if q:
                if c == "\\":
                    k += 2
                    continue
                if c == q:
                    q = None
            elif c in "\"'":
                q = c
            elif c == "(":
                d += 1
            elif c == ")":
                d -= 1
            k += 1
        yield src[j + len(name) + 1:k - 1]
        i = k


def literal(expr):
    toks = [t.strip() for t in split_top(expr, "+")]
    if not toks or not all(LIT.fullmatch(t) for t in toks):
        return None
    return "".join(java_unescape(LIT.fullmatch(t).group(1)) for t in toks)


def strip_comments(src):
    """The source with // and /* */ comments blanked (string literals kept)."""
    out, i, q = [], 0, None
    while i < len(src):
        c = src[i]
        if q:
            out.append(c)
            if c == "\\" and i + 1 < len(src):
                out.append(src[i + 1])
                i += 2
                continue
            if c == q:
                q = None
            i += 1
        elif c in "\"'":
            q = c
            out.append(c)
            i += 1
        elif src.startswith("//", i):
            j = src.find("\n", i)
            i = len(src) if j < 0 else j
        elif src.startswith("/*", i):
            j = src.find("*/", i + 2)
            i = len(src) if j < 0 else j + 2
        else:
            out.append(c)
            i += 1
    return "".join(out)


def keys(src_dir):
    found = []
    for f in sorted(glob.glob(os.path.join(src_dir, "**", "*.java"), recursive=True)):
        src = strip_comments(open(f, encoding="utf-8").read())
        for name in ("L.t", "L.f"):
            for inner in calls(src, name):
                en = literal(split_top(inner, ",")[0])
                if en is not None:
                    found.append(en)
        for arr in ("PROMPTS_EN", "TITLES_EN"):
            m = re.search(arr + r"\s*=\s*\{(.*?)\};", src, re.S)
            if m:
                for t in LIT.finditer(m.group(1)):
                    found.append(java_unescape(t.group(1)))
    seen, out = set(), []
    for k in found:
        if k not in seen:
            seen.add(k)
            out.append(k)
    return out


def java_hash(s):
    """Java's String.hashCode (UTF-16 code units, 32-bit signed wrap), as unsigned."""
    h = 0
    b = s.encode("utf-16-be")
    for i in range(0, len(b), 2):
        h = (31 * h + (b[i] << 8 | b[i + 1])) & 0xFFFFFFFF
    return h


def read_lang(path):
    table, errors = {}, []
    for n, line in enumerate(open(path, encoding="utf-8").read().split("\n"), 1):
        if not line or line.startswith("#"):
            continue
        if "\t" not in line:
            errors.append(f"{path}:{n}: no TAB")
            continue
        k, v = line.split("\t", 1)
        k, v = file_unescape(k), file_unescape(v)
        if sorted(re.findall(r"\{\d\}", k)) != sorted(re.findall(r"\{\d\}", v)):
            errors.append(f"{path}:{n}: placeholders differ: {k!r}")
        if not v.strip():
            errors.append(f"{path}:{n}: empty translation")
        table[k] = v
    return table, errors


def main():
    mode = sys.argv[1]
    if mode == "sync":
        src, dst = sys.argv[2], sys.argv[3]
        os.makedirs(dst, exist_ok=True)
        names = {os.path.basename(p) for p in glob.glob(os.path.join(src, "*.txt"))}
        for old in glob.glob(os.path.join(dst, "*.txt")):
            if os.path.basename(old) not in names:
                os.remove(old)
        for n in sorted(names):
            shutil.copyfile(os.path.join(src, n), os.path.join(dst, n))
        print(f"{dst}: {len(names)} language files")
        return
    ks = keys(sys.argv[2])
    if mode == "keys":
        for k in ks:
            print(file_escape(k))
    elif mode == "missing":
        table, _ = read_lang(sys.argv[3])
        for k in ks:
            if k not in table:
                print(file_escape(k))
    elif mode == "check":
        bad = []
        for path in sorted(glob.glob(os.path.join(sys.argv[3], "*.txt"))):
            table, errors = read_lang(path)
            bad += errors
            name = os.path.basename(path)
            missing = [k for k in ks if k not in table]
            stale = [k for k in table if k not in set(ks)]
            print(f"lang {name}: {len(ks) - len(missing)}/{len(ks)} strings"
                  + (f", {len(stale)} no longer used" if stale else ""))
            # every language has every text: a new English text needs its line in each file
            bad += [f"{path}: missing: {file_escape(k)}" for k in missing]
            bad += [f"{path}: no longer in the code, remove: {file_escape(k)}" for k in stale]
        # the phone looks a text up by its hash: no two keys may share one
        seen = {}
        for k in ks:
            h = java_hash(k)
            if h in seen:
                bad.append(f"keys {file_escape(seen[h])!r} and {file_escape(k)!r} have the same hash; reword one")
            seen[h] = k
        if len(sys.argv) > 4:
            server = sys.argv[4]
            ours = sorted(os.path.basename(p) for p in glob.glob(os.path.join(sys.argv[3], "*.txt")))
            theirs = sorted(os.path.basename(p) for p in glob.glob(os.path.join(server, "*.txt")))
            if ours != theirs or any(not filecmp.cmp(os.path.join(sys.argv[3], n), os.path.join(server, n),
                                                     shallow=False) for n in ours):
                bad.append(f"{server} differs from {sys.argv[3]}: run make -C app langs")
        for e in bad:
            print("ERROR " + e)
        sys.exit(1 if bad else 0)


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
# Copyright (c) 2026 PJ
# SPDX-License-Identifier: MIT
"""Check the evidence marks in the platform reference docs.

Every bullet, paragraph and table after a file's first `##` heading must carry
an evidence mark, `*(kind detail)*`, as defined in docs/EVIDENCE.md, and each
mark must name a known kind with the detail that kind requires.

    scripts/check-doc-evidence.py                 # docs/hubitat-*.md
    scripts/check-doc-evidence.py docs/x.md ...   # named files
    scripts/check-doc-evidence.py --stats         # count marks by kind

Exits 1 when any check fails.
"""
from __future__ import annotations

import argparse
import re
import sys
from collections import Counter
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent

KINDS = {"regression", "probed", "source", "staff", "docs", "spec",
         "reported", "community", "inferred", "absent", "false", "unmarked"}
NEEDS_FIRMWARE = {"regression", "probed", "source", "absent", "false"}
NEEDS_LINK = {"staff", "community"}
NEEDS_DETAIL = {"spec", "reported", "inferred"}

FIRMWARE = re.compile(r"\b\d+\.\d+\.\d+(?:\.\d+)?\b")
BUILD_RANGE = re.compile(r"\b(\d+(?:\.\d+){2,3})\s*[–-]\s*(\d+(?:\.\d+){2,3})\b")
LINK = re.compile(r"https?://|\]\(")
LEGACY = re.compile(r"\((?:verified|tested|checked|observed|measured)\s+(?:on\s+)?(?:firmware\s+)?\d")
HEADING = re.compile(r"^(#{1,6})\s")
ITEM = re.compile(r"^(\s*)(?:[-*+]|\d+\.)\s+")
SECTION_MARK = re.compile(r"^<!--\s*evidence:\s*(.*?)\s*-->\s*$")


def find_marks(text: str) -> list[str]:
    """Return the contents of every `*( ... )*`, allowing nested parentheses."""
    marks, i = [], 0
    while (start := text.find("*(", i)) != -1:
        depth, j = 0, start + 1
        while j < len(text):
            c = text[j]
            if c == "(":
                depth += 1
            elif c == ")":
                depth -= 1
                if depth == 0:
                    break
            j += 1
        if j < len(text) and text[j + 1:j + 2] == "*":
            marks.append(text[start + 2:j])
            i = j + 2
        else:
            i = start + 2
    return marks


def split_parts(mark: str) -> list[str]:
    """Split a mark on top-level `;` (not inside links or parentheses)."""
    parts, depth, cur = [], 0, ""
    for c in mark:
        if c in "([":
            depth += 1
        elif c in ")]":
            depth -= 1
        if c == ";" and depth == 0:
            parts.append(cur)
            cur = ""
        else:
            cur += c
    parts.append(cur)
    return [p.strip() for p in parts if p.strip()]


def check_mark(mark: str) -> list[str]:
    problems = []
    for part in split_parts(mark):
        m = re.match(r"([a-z]+)\s*:?\s*(.*)$", part, re.S)
        kind, detail = (m.group(1), m.group(2).strip()) if m else ("", part)
        if kind not in KINDS:
            problems.append(f"unknown evidence kind in *({mark})*; see docs/EVIDENCE.md")
            continue
        if kind in NEEDS_FIRMWARE and not (FIRMWARE.search(detail)
                                           or (kind == "probed" and "firmware not recorded" in detail)):
            problems.append(f"`{kind}` needs a firmware build: *({mark})*")
        for lo, hi in BUILD_RANGE.findall(detail):
            if tuple(map(int, lo.split("."))) > tuple(map(int, hi.split("."))):
                problems.append(f"build range runs backwards ({lo}–{hi}): *({mark})*")
        if kind == "regression" and "tests/" not in detail:
            problems.append(f"`regression` needs the test path: *({mark})*")
        if kind in NEEDS_LINK and not LINK.search(detail):
            problems.append(f"`{kind}` needs a link: *({mark})*")
        if kind == "docs" and not (LINK.search(detail) or "on-hub" in detail):
            problems.append(f"`docs` needs a link or an on-hub page id: *({mark})*")
        if kind in NEEDS_DETAIL and not detail:
            problems.append(f"`{kind}` needs a detail: *({mark})*")
        if kind == "unmarked" and detail:
            problems.append(f"`unmarked` takes no detail: *({mark})*")
    return problems


def blocks(lines: list[str]):
    """Yield (kind, start_line, text, indent, section_marked) for each block
    after the first `##` heading. kind is item, para or table."""
    in_code = in_comment = False
    started = False
    section_marked: dict[int, bool] = {}   # heading level -> marked
    cur = None   # [kind, start, text, indent]
    expect_section_mark = False

    def flush():
        nonlocal cur
        if cur:
            marked = any(section_marked.values())
            out = (cur[0], cur[1], cur[2], cur[3], marked)
            cur = None
            return out
        return None

    for n, raw in enumerate(lines, 1):
        line = raw.rstrip("\n")
        if line.lstrip().startswith("```"):
            if (b := flush()):
                yield b
            in_code = not in_code
            continue
        if in_code:
            continue
        if in_comment:
            if "-->" in line:
                in_comment = False
            continue
        h = HEADING.match(line)
        if h:
            if (b := flush()):
                yield b
            level = len(h.group(1))
            if level == 2:
                started = True
            for lv in list(section_marked):
                if lv >= level:
                    del section_marked[lv]
            expect_section_mark = level
            continue
        sm = SECTION_MARK.match(line.strip())
        if sm:
            if (b := flush()):
                yield b
            if expect_section_mark:
                section_marked[expect_section_mark] = True
                yield ("section", n, f"*({sm.group(1)})*", 0, True)
            else:
                yield ("misplaced", n, line, 0, True)
            continue
        if line.strip().startswith("<!--"):
            if "-->" not in line:
                in_comment = True
            continue
        if not line.strip():
            if (b := flush()):
                yield b
            continue
        expect_section_mark = False
        if not started:
            continue
        item = ITEM.match(line)
        if item:
            if (b := flush()):
                yield b
            cur = ["item", n, line, len(item.group(1).expandtabs())]
        elif line.lstrip().startswith("|"):
            if cur and cur[0] != "table":
                if (b := flush()):
                    yield b
            if not cur:
                cur = ["table", n, "", 0]
            cur[2] += line + "\n"
        elif cur and cur[0] == "item" and raw[:1].isspace():
            cur[2] += " " + line.strip()
        elif cur and cur[0] == "para":
            cur[2] += " " + line.strip()
        else:
            if (b := flush()):
                yield b
            cur = ["para", n, line.strip(), 0]
    if (b := flush()):
        yield b


def check_file(path: Path, stats: Counter) -> list[str]:
    errors = []
    lines = path.read_text(encoding="utf-8").splitlines()
    rel = path.relative_to(REPO) if path.is_relative_to(REPO) else path
    seq = list(blocks(lines))
    parents: list[tuple[int, bool]] = []   # (indent, marked) stack for items
    for idx, (kind, n, text, indent, section_marked) in enumerate(seq):
        where = f"{rel}:{n}"
        if kind == "misplaced":
            errors.append(f"{where}: an evidence comment must be the line after a heading")
            continue
        for legacy in LEGACY.finditer(text):
            errors.append(f"{where}: old-style mark `{legacy.group(0)}`; use *(probed …)* or another kind")
        marks = find_marks(text)
        for m in marks:
            for part in split_parts(m):
                stats[part.split()[0].rstrip(":") if part.split() else "?"] += 1
            errors += [f"{where}: {p}" for p in check_mark(m)]
        if kind == "section":
            continue
        marked = bool(marks)
        if kind == "item":
            while parents and parents[-1][0] >= indent:
                parents.pop()
            inherited = bool(parents) and parents[-1][1]
            parents.append((indent, marked or inherited))
            if not (marked or inherited or section_marked):
                errors.append(f"{where}: bullet has no evidence mark")
            continue
        parents.clear()
        if kind == "para":
            if not (marked or section_marked or text.endswith(":")):
                errors.append(f"{where}: paragraph has no evidence mark")
        elif kind == "table":
            rows = [r for r in text.splitlines()[2:] if r.strip()]
            near = [seq[i] for i in (idx - 1, idx + 1)
                    if 0 <= i < len(seq) and seq[i][0] == "para"]
            if not (section_marked or any(find_marks(b[2]) for b in near)
                    or (rows and all(find_marks(r) for r in rows))):
                errors.append(f"{where}: table has no evidence mark before or after it")
    return errors


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("files", nargs="*", type=Path)
    ap.add_argument("--stats", action="store_true", help="print mark counts by kind")
    args = ap.parse_args()
    files = [f.resolve() for f in args.files] or sorted((REPO / "docs").glob("hubitat-*.md"))
    stats: Counter = Counter()
    errors = []
    for f in files:
        errors += check_file(f, stats)
    for e in errors:
        print(e)
    if args.stats:
        for kind, count in stats.most_common():
            print(f"{count:5d}  {kind}")
    if errors:
        print(f"\n{len(errors)} evidence problem(s); see docs/EVIDENCE.md", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())

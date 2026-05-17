#!/usr/bin/env python3
"""Regenerate AUTO sections inside README.md files.

Sections look like:

    <!-- AUTO:apps-table -->
    ...generated content...
    <!-- /AUTO -->

Each AUTO id maps to a generator function in this file. The generator scans
the repo (apps/*.groovy, drivers/**/*.groovy, etc.) and writes the canonical
content between the markers. Content outside the markers is never touched.

Run from the repo root:

    python3 scripts/readme-sync.py            # rewrite all AUTO blocks
    python3 scripts/readme-sync.py --check    # exit 1 if any would change

Add a new auto section by:
1. Wrapping the target prose in `<!-- AUTO:<id> -->` / `<!-- /AUTO -->`.
2. Registering an entry in `SECTIONS` below mapping `(readme, id)` -> a
   zero-arg callable that returns the generated body (no surrounding markers).

Designed for `git diff`-friendly output: deterministic ordering, no trailing
whitespace, and a single trailing newline on every block.
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "scripts"))

from lib.groovy_meta import parse as parse_groovy  # noqa: E402


# Files we never include in generated listings.
_IGNORE_NAMES = {"README.md", "__pycache__", ".DS_Store"}


def _groovy_files(d: Path) -> list[Path]:
    """Top-level *.groovy files in d, excluding editor backups."""
    out = []
    for p in sorted(d.iterdir()):
        if not p.is_file() or p.suffix != ".groovy":
            continue
        if p.name.endswith("~") or p.name.startswith("#") or p.name.startswith("."):
            continue
        out.append(p)
    return out


def _readme_subdirs(d: Path) -> list[Path]:
    """Subdirectories of d, case-insensitively sorted, excluding hidden/ignored."""
    return sorted(
        (
            p
            for p in d.iterdir()
            if p.is_dir() and not p.name.startswith(".") and p.name not in _IGNORE_NAMES
        ),
        key=lambda p: p.name.lower(),
    )


def _parse_groovy_dir(d: Path):
    """Parse every top-level Groovy file in d, sorted by display name.

    Files with no parsed name are skipped.
    """
    metas = []
    for p in _groovy_files(d):
        m = parse_groovy(p)
        if m.name:
            metas.append(m)
    metas.sort(key=lambda m: m.name.lower())
    return metas


def _group_parent_child(metas):
    """Group parent app + its child(ren) into one row.

    Returns a list of (parent_meta, [child_metas]) tuples. Apps with no
    matching parent (or parents that don't reference back) are listed as
    standalone (parent_meta only, empty child list).
    """
    by_name = {m.name: m for m in metas}
    children_of: dict[str, list] = {}
    for m in metas:
        if m.parent:
            # parent looks like "iamtrep:Some Name"; pull the name half
            pname = m.parent.split(":", 1)[-1]
            if pname in by_name:
                children_of.setdefault(pname, []).append(m)
    out = []
    seen = set()
    for m in metas:
        if m.parent and m.parent.split(":", 1)[-1] in by_name:
            continue  # listed under parent
        out.append((m, children_of.get(m.name, [])))
        seen.add(m.name)
    return out


def _md_escape(s: str) -> str:
    return s.replace("|", "\\|")


# ── Generators ───────────────────────────────────────────────────────────────


def gen_apps_table() -> str:
    """Table of standalone apps in apps/ (top-level *.groovy only)."""
    metas = _parse_groovy_dir(ROOT / "apps")
    rows = []
    for parent, children in _group_parent_child(metas):
        label = parent.name
        if children:
            label = f"{label} (parent/child)"
        desc = parent.description or ""
        rows.append(f"| **{_md_escape(label)}** | {_md_escape(desc)} |")
    if not rows:
        return "_(no standalone apps)_\n"
    return "| App | Description |\n|---|---|\n" + "\n".join(rows) + "\n"


def gen_apps_subfolders() -> str:
    """Subfolder index for apps/. Excludes VerbNav per project rule."""
    apps_dir = ROOT / "apps"
    rows = []
    for sub in _readme_subdirs(apps_dir):
        if sub.name == "VerbNav":
            continue  # private prototype, do not surface
        # Prefer the app's definition() description when there's exactly one
        # parent (non-child) Groovy in the subdir; otherwise leave blank.
        metas = _parse_groovy_dir(sub)
        parents = [m for m in metas if not m.is_child]
        desc = parents[0].description if len(parents) == 1 else ""
        rows.append(f"| [{sub.name}/](./{sub.name}/) | {_md_escape(desc)} |")
    if not rows:
        return "_(no subfolders)_\n"
    return "| Folder | Description |\n|---|---|\n" + "\n".join(rows) + "\n"


def _drivers_group_table(group_dir: Path) -> str:
    """Render a table for one drivers/<group> directory."""
    metas = _parse_groovy_dir(group_dir)
    rows = []
    for m in metas:
        desc = m.description or ""
        rows.append(f"| **{_md_escape(m.name)}** | {_md_escape(desc)} |")
    if not rows:
        return "_(none)_\n"
    return "| Driver | Description |\n|---|---|\n" + "\n".join(rows) + "\n"


def gen_drivers_standalone() -> str:
    return _drivers_group_table(ROOT / "drivers")


def gen_drivers_sinope() -> str:
    return _drivers_group_table(ROOT / "drivers" / "sinope")


def gen_drivers_stelpro() -> str:
    return _drivers_group_table(ROOT / "drivers" / "stelpro")


def gen_drivers_tests() -> str:
    return _drivers_group_table(ROOT / "drivers" / "tests")


def gen_hubdiag_version() -> str:
    m = parse_groovy(ROOT / "apps" / "HubDiagnostics" / "HubDiagnostics.groovy")
    if not m.app_version:
        return "_(version not found)_\n"
    return f"**Current version:** {m.app_version}\n"


def gen_hubdiag_files() -> str:
    """List the files in apps/HubDiagnostics/ that ship with the app."""
    d = ROOT / "apps" / "HubDiagnostics"
    rows = []
    for p in sorted(d.iterdir()):
        if p.is_dir() or p.name in _IGNORE_NAMES or p.name.startswith("."):
            continue
        if p.name.endswith("~") or p.suffix == ".pyc":
            continue
        if p.suffix in (".md", ".py") or p.name.endswith(".har"):
            continue
        purpose = _hubdiag_file_purpose(p.name)
        if not purpose:
            continue
        rows.append(f"| `{p.name}` | {purpose} |")
    if not rows:
        return "_(no shipped files)_\n"
    return "| File | Purpose |\n|---|---|\n" + "\n".join(rows) + "\n"


_HUBDIAG_PURPOSE = {
    ".groovy": "The Hubitat app (backend logic, API, data collection)",
    ".html": "The web dashboard UI (served from hub File Manager)",
}


def _hubdiag_file_purpose(name: str) -> str | None:
    for suf, purpose in _HUBDIAG_PURPOSE.items():
        if name.endswith(suf):
            return purpose
    return None


def gen_visiblair_components() -> str:
    """Components of the visiblair integration: manager app + sensor drivers."""
    d = ROOT / "integrations" / "visiblair"
    rows = []
    for m in _parse_groovy_dir(d):
        kind = "App" if "Manager" in m.path.name else "Driver"
        desc = m.description or ""
        rows.append(f"| **{_md_escape(m.name)}** | {kind} | {_md_escape(desc)} |")
    if not rows:
        return "_(no components)_\n"
    return (
        "| Component | Type | Description |\n|---|---|---|\n" + "\n".join(rows) + "\n"
    )


def _subdir_groovy_table(rel: str) -> str:
    d = ROOT / rel
    metas = _parse_groovy_dir(d)
    rows = []
    for m in metas:
        desc = m.description or ""
        label = m.name
        if m.is_child:
            label = f"{label} (child)"
        rows.append(f"| `{m.path.name}` | **{_md_escape(label)}** | {_md_escape(desc)} |")
    if not rows:
        return "_(no apps)_\n"
    return "| File | App | Description |\n|---|---|---|\n" + "\n".join(rows) + "\n"


def gen_utilities_index() -> str:
    return _subdir_groovy_table("apps/utilities")


def gen_sensors_index() -> str:
    return _subdir_groovy_table("apps/sensors")


def gen_tests_index() -> str:
    return _subdir_groovy_table("apps/tests")


def gen_wellmonitor_files() -> str:
    """List files in apps/WellMonitor/ that ship with the app."""
    d = ROOT / "apps" / "WellMonitor"
    rows = []
    for p in sorted(d.iterdir()):
        if p.is_dir() or p.name in _IGNORE_NAMES or p.name.startswith("."):
            continue
        if p.name.endswith("~"):
            continue
        purpose = _wellmon_file_purpose(p.name)
        if not purpose:
            continue
        rows.append(f"  {p.name:<28}  # {purpose}")
    body = "\n".join(rows)
    return f"```\napps/WellMonitor/\n{body}\n```\n" if body else "_(no files)_\n"


_WELLMON_PURPOSE = {
    "WellMonitor.groovy": "Hubitat app (Groovy)",
    "wellmonitor-dashboard.html": "Web dashboard (HTML/CSS/JS)",
    "README.md": "This file",
}


def _wellmon_file_purpose(name: str) -> str | None:
    return _WELLMON_PURPOSE.get(name)


_SCRIPT_DESCRIPTIONS = {
    "hubitat-app-backup.sh": "Back up installed app configurations (settings, state, subscriptions, jobs)",
    "hydroquebec_peakevent.js": "Google Apps Script — detect Hydro-Québec peak event emails and trigger a Hubitat switch",
    "install-git-hooks.sh": "Install the repo's pre-commit hook that keeps AUTO sections of READMEs in sync",
    "readme-sync.py": "Regenerate AUTO sections inside README.md files",
    "run-tests.sh": "Run the in-repo test suites against a hub",
    "ws_to_file.sh": "Stream a WebSocket to a file with optional timestamps and auto-reconnect",
    "zigbee-log-analyser.py": "Analyze Zigbee log captures — cluster activity, attribute reports, device patterns",
    "zigbee-ota-analyser.py": "Analyze Zigbee OTA update traffic from log captures",
    "perf/capture_hub_logs.py": "Stream `/logsocket` to a JSONL file with periodic memory/CPU samples injected for offline correlation",
    "perf/analyse_rm_delays.py": "Read a JSONL capture and report Trigger→Action timing stats, outlier histogram, and memory context",
}


def _scripts_table(rel_dir: str, link_prefix: str = "") -> str:
    d = ROOT / rel_dir
    rows = []
    for p in sorted(d.iterdir(), key=lambda p: p.name.lower()):
        if p.is_dir() or p.name in _IGNORE_NAMES or p.name.startswith("."):
            continue
        if p.suffix not in {".sh", ".py", ".js"}:
            continue
        if p.name.endswith("~"):
            continue
        key = f"{link_prefix}{p.name}" if link_prefix else p.name
        desc = _SCRIPT_DESCRIPTIONS.get(key, "")
        link = f"{link_prefix}{p.name}"
        rows.append(f"| [{p.name}]({link}) | {desc} |")
    if not rows:
        return "_(no scripts)_\n"
    return "| Script | Description |\n|---|---|\n" + "\n".join(rows) + "\n"


def gen_scripts_index() -> str:
    """Top-level scripts in scripts/ (excludes lib/, perf/, tests/)."""
    return _scripts_table("scripts")


def gen_scripts_perf_index() -> str:
    """Scripts in scripts/perf/, linked relative to scripts/README.md."""
    return _scripts_table("scripts/perf", link_prefix="perf/")


# ── Section registry ─────────────────────────────────────────────────────────


# Each entry: (relative README path, section id) -> generator callable.
SECTIONS: dict[tuple[str, str], "callable[[], str]"] = {
    ("apps/README.md", "apps-table"): gen_apps_table,
    ("apps/README.md", "apps-subfolders"): gen_apps_subfolders,
    ("drivers/README.md", "drivers-standalone"): gen_drivers_standalone,
    ("drivers/README.md", "drivers-sinope"): gen_drivers_sinope,
    ("drivers/README.md", "drivers-stelpro"): gen_drivers_stelpro,
    ("drivers/README.md", "drivers-tests"): gen_drivers_tests,
    ("apps/HubDiagnostics/README.md", "hubdiag-version"): gen_hubdiag_version,
    ("apps/HubDiagnostics/README.md", "hubdiag-files"): gen_hubdiag_files,
    ("apps/WellMonitor/README.md", "wellmonitor-files"): gen_wellmonitor_files,
    ("apps/utilities/README.md", "utilities-index"): gen_utilities_index,
    ("apps/sensors/README.md", "sensors-index"): gen_sensors_index,
    ("apps/tests/README.md", "tests-index"): gen_tests_index,
    ("integrations/visiblair/README.md", "visiblair-components"): gen_visiblair_components,
    ("scripts/README.md", "scripts-index"): gen_scripts_index,
    ("scripts/README.md", "scripts-perf-index"): gen_scripts_perf_index,
}


# ── Marker rewrite engine ────────────────────────────────────────────────────


_OPEN_RE = re.compile(r"<!--\s*AUTO:([\w-]+)\s*-->")
_CLOSE = "<!-- /AUTO -->"


def rewrite_file(path: Path) -> tuple[str, list[str]]:
    """Return (new_text, list_of_section_ids_rewritten).

    Raises ValueError for malformed markers (unmatched open/close, unknown id).
    """
    text = path.read_text(encoding="utf-8")
    out = []
    pos = 0
    rewritten: list[str] = []
    relpath = path.relative_to(ROOT).as_posix()
    while True:
        m = _OPEN_RE.search(text, pos)
        if not m:
            out.append(text[pos:])
            break
        out.append(text[pos:m.end()])
        out.append("\n")
        close_idx = text.find(_CLOSE, m.end())
        if close_idx == -1:
            raise ValueError(f"{relpath}: unterminated AUTO:{m.group(1)}")
        sec_id = m.group(1)
        key = (relpath, sec_id)
        if key not in SECTIONS:
            raise ValueError(f"{relpath}: unknown AUTO id '{sec_id}'")
        body = SECTIONS[key]()
        if not body.endswith("\n"):
            body += "\n"
        out.append(body)
        out.append(_CLOSE)
        pos = close_idx + len(_CLOSE)
        rewritten.append(sec_id)
    return "".join(out), rewritten


def find_readmes() -> list[Path]:
    """All READMEs registered in SECTIONS (deduped, ordered)."""
    seen = set()
    paths = []
    for (rel, _id) in SECTIONS:
        if rel in seen:
            continue
        seen.add(rel)
        paths.append(ROOT / rel)
    return paths


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument(
        "--check",
        action="store_true",
        help="Exit 1 if any README would change; do not write.",
    )
    args = ap.parse_args(argv)

    changed: list[str] = []
    for p in find_readmes():
        if not p.exists():
            print(f"missing: {p.relative_to(ROOT)}", file=sys.stderr)
            return 2
        old = p.read_text(encoding="utf-8")
        try:
            new, _ids = rewrite_file(p)
        except ValueError as e:
            print(f"error: {e}", file=sys.stderr)
            return 2
        if new != old:
            changed.append(p.relative_to(ROOT).as_posix())
            if not args.check:
                p.write_text(new, encoding="utf-8")

    if args.check:
        if changed:
            print("drift detected in:")
            for c in changed:
                print(f"  {c}")
            return 1
        return 0

    if changed:
        print("rewrote:")
        for c in changed:
            print(f"  {c}")
    else:
        print("no changes")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))

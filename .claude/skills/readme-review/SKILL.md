---
name: readme-review
description: Review the hand-written (non-AUTO) portions of repo README.md files for drift against current source and recent commits, and propose fixes
argument-hint: "[path/to/README.md] (omit to review all READMEs)"
allowed-tools: Bash, Read, Edit, Grep
---

<!--
Copyright (c) 2025-2026 PJ
SPDX-License-Identifier: MIT
-->

# README Review Skill

The deterministic listings (app/driver tables, version numbers, file lists) in this repo's READMEs are regenerated automatically by `scripts/readme-sync.py` inside `<!-- AUTO:... -->` marker blocks. **Narrative** content — the prose that explains what each app does, how it works, what its config knobs mean — is hand-written and can drift silently. This skill spots and fixes that drift.

## Scope

Look ONLY at content **outside** `<!-- AUTO:... --> ... <!-- /AUTO -->` markers. Content inside the markers is owned by `scripts/readme-sync.py` and must not be edited here.

## Constraints

These are project rules, not defaults — follow them strictly:

- **READMEs link only to other READMEs.** Do not introduce links from any README to `ARCHITECTURE.md`, `TODO.md`, design specs, plan files, or any other non-README document. (See memory `feedback_readme_links.md`.)
- **Do not surface VerbNav in top-level or `apps/` READMEs.** It is a private prototype. The `apps/VerbNav/README.md` is fine; cross-references to it from other READMEs are not.
- **Ask before adding new fields** to user-facing outputs (forum exports, dashboard sections referenced in prose, etc.). Don't volunteer new claims about features the user hasn't validated. (See memory `feedback_ask_before_adding_fields.md`.)

## Instructions

### Step 1 — Pick targets

If the user named a specific README path, review only that file. Otherwise, list every `README.md` tracked in the repo (excluding `.git`, `node_modules`, etc.) and process them one by one.

### Step 2 — Gather drift signal

For each target README, run two checks:

1. **Recent commits touching the README's directory tree** — `git log --oneline -n 25 -- <dir>` where `<dir>` is the README's parent. Read commit subjects to spot renames, removals, feature changes that prose might not yet reflect.
2. **Source files referenced by name in the prose** — `grep` the README for `*.groovy`, `*.html`, `*.py`, and other filenames, then `Read` each one to confirm it still exists and that any quoted behaviour (preference names, endpoint paths, version numbers, capability lists) matches the source.

### Step 3 — Identify drift candidates

A candidate is any narrative paragraph that meets at least one of:

- References a filename that no longer exists or has been renamed.
- States a capability, setting name, default value, or endpoint path that the source contradicts.
- Describes an algorithm or workflow contradicted by a recent commit touching the same code.
- Uses the old name of a renamed app, driver, or feature.
- Is inside a "Status" / "Tested against firmware X" line whose value disagrees with the constant in the source.

### Step 4 — Propose, then apply

For each candidate, present a single before/after diff to the user with the source citation that justifies the change (e.g., "WellMonitor.groovy:42 says `txtEnable` defaults to `true` but the README says `false`"). Wait for explicit approval before editing.

Apply approved edits with the `Edit` tool. Do not bundle approvals — one diff, one approval, one edit.

### Step 5 — Hand back

When the review is complete (or the user calls a stop), report:

- READMEs reviewed
- Drift items found, approved, and applied
- Drift items found but skipped (and why)

Do NOT touch:
- Content inside `<!-- AUTO -->` markers (run `scripts/readme-sync.py` instead).
- Files outside the requested scope.
- Source code, even if the source is the one that's actually wrong. If the source contradicts the README and the README is right, surface the conflict to the user and let them decide which side to fix.

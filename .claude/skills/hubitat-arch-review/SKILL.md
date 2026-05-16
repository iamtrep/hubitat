---
name: hubitat-arch-review
description: Audit Groovy app/driver files against the project's ARCHITECTURE.md and any per-project arch docs, reporting concrete deviations with doc citations. Read-only — never modifies files.
argument-hint: "[filepath ...] | --branch"
allowed-tools: Read, Glob, Grep, Bash
---


<!--
Copyright (c) 2025-2026 PJ
SPDX-License-Identifier: MIT
-->

# Hubitat Architecture Audit Skill

Read the project's architecture docs, then scan one or more Groovy files and report concrete deviations. The skill contains **zero architectural rules of its own** — it consumes `ARCHITECTURE.md` as the rubric, so it stays in sync as the doc evolves.

This is not a general code review. It is a focused arch-compliance audit. For broader code quality, use the `code-review` skill. For pre-merge review of a PR, use `/review` or `/ultrareview`.

## Step 1 — Determine target files

Parse `$ARGUMENTS`:

- One or more filepaths (space-separated) → audit those files.
- `--branch` or no argument → use `git diff main --name-only -- '*.groovy'` to get every Groovy file modified relative to `main` (covers both committed-on-branch and working-tree changes). If the current branch is `main`, fall back to `git status --porcelain | awk '{print $2}' | grep '\.groovy$'`.

If no Groovy files match, report and stop. Ignore non-`.groovy` arguments with a one-line warning.

## Step 2 — Discover applicable arch docs

The repo-wide doc is at `/ARCHITECTURE.md`. Project-level arch docs live alongside their code (e.g. `apps/HubDiagnostics/ARCHITECTURE.md`).

For each target file:

1. Always include `/ARCHITECTURE.md`.
2. Walk up the file's directory chain to the repo root; collect any `ARCHITECTURE.md` found along the way.
3. The union of those docs is the rubric. When a nested doc and the repo-wide doc both cover the same topic, the nested doc takes precedence (it is the project's specialization).

Use Glob (`apps/**/ARCHITECTURE.md`, `drivers/**/ARCHITECTURE.md`, `integrations/**/ARCHITECTURE.md`) once at the start to enumerate all nested docs available in the repo.

## Step 3 — Read the docs

Read `/ARCHITECTURE.md` and every applicable nested doc **in full** before scanning any file. Do not summarize them in your own words — you need to cite section headings verbatim in findings, and you need the actual text to judge whether a candidate finding is real.

If `/ARCHITECTURE_CANDIDATES.md` exists, read it too but treat it as informational only — items there are not enforceable rules yet.

## Step 4 — Scan target files

For each file, work through three layers of checks.

### 4a. Mechanical patterns

Use Grep and Bash to find candidate locations. **Always confirm each candidate against the doc** before flagging it — the doc may permit the pattern in some contexts.

Useful starting greps:

- `\bdef\s+\w+` — candidate `def` usage (filter out callback parameters like `resp`/`data`/`evt`)
- `\.getClass\(\)` — sandbox-blocked
- `state\.\w+\s*<<` — in-place state mutation
- `state\.\w+\s*\+=` on a List/Map context — same risk
- `httpGet\s*\(`, `httpPost\s*\(`, `httpPut\s*\(` — synchronous HTTP in apps that have async wrappers
- raw `log\.(debug|trace|info)` calls — check if gated by an `if (<x>Enable)` guard or by a project log helper
- `def\s+updated\s*\(\)|void\s+updated\s*\(\)` — read the body; check for both `unsubscribe()` (apps) and `unschedule()` before `initialize()`
- For drivers: any `zigbee\.(writeAttribute|readAttribute|configureReporting)\(0xF[CF]\d\d` (manufacturer-specific cluster) — check for a trailing `[mfgCode: ...]` map

### 4b. Semantic checks (require reading the file)

Read the file end-to-end and reason against the doc:

- **State tier appropriateness.** For each `state.X`, `atomicState.X`, and `@Field static X`: does the choice match the rules in *Common → State tiers*? Flag obvious mismatches (e.g. transient scan data in `state`, long-lived config in `@Field static`, plain `state` writes from a known async callback).
- **Lifecycle convergence.** Does `installed()` route through `initialize()`? Does `updated()` reset before reinitializing? Does `initialize()` carry the version-check?
- **Version constant and code-push detection.** Is there a `@Field static final` version string? Is it compared against `state.version` somewhere reachable (apps/most drivers: `initialize()`; Zigbee drivers: `parse()`)?
- **Logging discipline.** Are the three log prefs present? Are private `logTrace`/`logDebug`/`logInfo`/`logWarn`/`logError` helpers defined and gated by the corresponding pref? Is debug/trace auto-disabled with a `runIn`?
- **Async HTTP callback contract.** Each async callback should check `resp.hasError()`, then `resp.getStatus()`, before reading the body, with a `try`/`catch` around any JSON parsing.
- **Apps — settings migration.** If the file has migration logic or a schema version, does it use `app.updateSetting`/`app.removeSetting` (not just leave old keys in place) and call `state.remove(...)` for obsolete state?
- **Apps — API endpoint design.** If `mappings { }` is present, classify each route mentally: app-owned, aggregator, or pure passthrough. Flag any pure passthrough (the doc says this category should be empty).
- **Apps — `unsubscribe()` and `unschedule()` in `updated()`.** Specifically confirm both are present.
- **Drivers — Zigbee parse skeleton.** If `parse(String description)` is present, check the five outer paths (attribute report, ZDO, ZHA global, enroll, zone status/report) and the `additionalAttrs` iteration inside the attribute path.
- **Drivers — `mfgCode` on manufacturer-specific clusters.** Reconfirm at every relevant call site.
- **Parent/child patterns.** If the file declares `parent:` or calls `addChildDevice`/`getChildDevices`, check for a DNI prefix scheme and explicit-action orphan tracking (no auto-deletion of stale children).

### 4c. Judgment calls

Flag and let the human decide; do not grade:

- Cache invalidation stories that aren't explained at the cache's declaration.
- Backwards-compatibility shims and `// removed`-style dead code (the project's stance is against these).
- Code comments that explain WHAT the code does rather than WHY (the project's stance).
- Hot-path additions in HubDiagnostics (`buildSharedCache`, `getStructuredAlerts`, `apiLive`).

## Step 5 — Format output

Produce a single Markdown report. One section per file.

```
## Architecture audit: <relative path>

**Docs consulted:** /ARCHITECTURE.md[, apps/<name>/ARCHITECTURE.md]

### Errors (N)
- `<path>:<line>` — **<short violation>**. *Rule:* <doc section heading>.
- ...

### Warnings (N)
- `<path>:<line>` — **<short violation>**. *Rule:* <doc section heading>.
- ...

### Notes / judgment calls (N)
- `<path>:<line>` — <observation>.
- ...

### Clear
- <topic 1>
- <topic 2>
- ...
```

Severity rules:

- **Error** — the doc says *must* / *do not* / *always*, or the issue appears in *Patterns To Avoid*. Skipping causes the failure mode the doc names.
- **Warning** — the doc says *should* / *prefer* / *convention*. Skipping is stylistic drift.
- **Note** — a judgment call. The doc gives a rule of thumb, not a hard answer.

The **Clear** section lists topics that were checked and passed — keeps the report honest about coverage. Choose from the categories in 4a/4b (Lifecycle, Logging, State tiers, Version check, Async HTTP, Settings migration, API endpoints, Zigbee parse, Zigbee mfgCode, Parent/child, etc.) based on what's relevant for the file.

End the whole report with a single summary line:

```
**Summary:** <E> errors, <W> warnings, <N> notes across <K> file(s).
```

## What this skill must not do

- Auto-fix anything. Read-only.
- Invent rules not in the docs. Every finding must cite a doc section by its heading.
- Re-explain doc content in the body of findings. One-sentence citation per finding is enough — the user can open the doc if they want the full rule.
- Grade subjectively (method length, naming, "feels off").
- Treat `/ARCHITECTURE_CANDIDATES.md` items as enforceable rules.

If a target file falls under no applicable arch doc, report that and skip it.

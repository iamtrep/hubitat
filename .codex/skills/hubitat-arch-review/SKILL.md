---
name: hubitat-arch-review
description: "Use when the user wants a read-only architecture compliance audit of Hubitat Groovy files against this repo's ARCHITECTURE.md and any nested project architecture docs. Gather the applicable docs, inspect one or more target files or changed-branch files, and report concrete deviations with document-section citations rather than making edits."
---

# Hubitat Architecture Review

Use this skill for focused architecture-compliance review, not for general code review.

## Workflow

1. Determine target files:
   - explicit `.groovy` paths from the request
   - `--branch` or no argument: use changed `.groovy` files relative to `main`, falling back to working tree changes when already on `main`
2. Discover applicable docs for each target:
   - always include repo-root `ARCHITECTURE.md`
   - walk up the file's directory chain and include any nested `ARCHITECTURE.md`
   - treat nested docs as the more specific rubric when they overlap with the repo-wide doc
3. Read the applicable docs in full before scanning files.
4. Audit each file for documented patterns such as:
   - lifecycle convergence through `initialize()`
   - version constants and code-push detection
   - logging prefs and gating discipline
   - state tier usage
   - async HTTP callback handling
   - settings migration approach
   - app endpoint design
   - Zigbee parse skeleton and manufacturer-code handling
   - parent/child patterns
5. Use grep to find candidates, but confirm every finding semantically against the docs before reporting it.

## Reporting

For each file, produce:
- docs consulted
- errors
- warnings
- notes or judgment calls
- a short clear/passed coverage list

End with a one-line total summary across all files.

## Constraints

- Read-only. Do not edit files.
- Do not invent rules that are not in the architecture docs.
- Cite document section headings in findings.

# HubDiagnostics: Extend the SPA Pattern to Audit Report and Forum Export

**Date:** 2026-05-09
**Status:** Approved

## Problem

`HubDiagnostics.groovy` is 5,103 lines. Two features diverge from the app's
established rendering pattern:

1. `renderAuditHtml()` (334 lines) + `AUDIT_REPORT_CSS` (37 lines) — Groovy
   builds a full HTML document server-side at the end of the most resource-intensive
   operation in the app. This is the only place in the codebase where Groovy does
   HTML rendering, creating a second rendering system to maintain alongside the SPA.

2. `apiForumExport()` — 257 of its 299 lines are Markdown string formatting. The
   hub re-fetches all system data and formats it as text — work the browser can do,
   using data it frequently already has in memory from normal tab loads.

## Decision

Extend the existing SPA pattern (Groovy returns JSON, browser renders) to cover
both features. The Groovy app becomes a pure data API.

**Snapshot diff (`apiSnapshotDiff()`, 241 lines) is kept server-side.** The diff
output is ~5 KB; browser-side diffing would require fetching two full snapshot
blobs (~200 KB each). Server-side computation is the correct call here.

## Design

### Audit Report

`finalizeAudit()` serializes the xref map to JSON and saves it as
`hub_usage_audit_YYYYMMDD_HHmmss.json` in File Manager (replaces the HTML file).

The SPA detects a `?audit=<filename>` URL parameter on startup and enters
audit-report mode: it fetches the JSON via a new `/api/audit/data` endpoint,
renders the full report in the browser, and hides the normal tab UI. "View" links
in the Audit tab open the SPA in a new tab with this parameter.

No new Groovy serving routes are needed — the existing SPA infrastructure
(served at `/ui.html` with injected `access_token` and `api_base`) handles it.

### Forum Export

`apiForumExport()` is replaced by `apiForumData()` — identical data gathering,
returns raw JSON instead of Markdown. The 257-line `md << ...` block is ported
to a `buildForumMarkdown(d)` JS function in the SPA. UX is unchanged (result
appears in the same textarea for copy-paste).

### Not Changed

- `apiSnapshotDiff()` — kept server-side (bandwidth argument)
- All data-gathering methods — they aggregate hub API calls and must stay server-side
- All JSON API endpoints — already following the right pattern

## Impact

| | Groovy | SPA |
|---|---|---|
| Audit report | −371 lines | +400 lines |
| Forum export | −287 lines | +257 lines |
| **Net** | **−658 lines (−13%)** | **+657 lines** |

One rendering system instead of two. Audit data stored as JSON enables future
snapshot integration (`if (settings.includeAuditInSnapshot) snapshot.auditXref = xref`).

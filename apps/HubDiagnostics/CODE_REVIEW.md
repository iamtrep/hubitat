# HubDiagnostics — Code Review Findings & Fix Plan

Status as of 2026-05-08 (post-v5.13.0 ship).

Combined output of two independent reviews:
- **Codex** — external code-review pass (12 findings)
- **Internal** — verification + 10 additional findings (A1–A10)

All Codex findings were verified against the code; verdicts are recorded below.

---

## Findings table

Severity legend: 🔴 critical bug · 🟠 high (architecture / leverage) · 🟡 medium · ⚪ low / cosmetic

| # | Finding | Severity | Verdict | Status |
|---|---|---|---|---|
| 1 | Re-fetch in single request path; multiple `/hub2/hubData` per dashboard refresh | 🟠 | Valid & understated; the `shared` Map pattern is half-built | [ ] |
| 2 | Oversized methods (`renderAuditHtml` 363 lines, `apiSnapshotDiff` 242, `analyzeDevices` 172) | 🟡 | Partially valid — only `renderAuditHtml` is truly egregious | [ ] |
| 3 | Audit report duplicates SPA framework (own CSS / own table-sort JS) | 🟠 | Strongly valid | [ ] |
| 4 | `serveUI` runs `getUIVersion()` + sync check on every request | 🟡 | Valid; 24h-gated so production impact is modest | [ ] |
| 5 | `finalizeAudit` enrichment is serial after the async fan-out | 🟡 | Valid scaling concern; documented trade-off — defer until scale hurts | [ ] |
| 6 | `childIds` includes parent IDs — wrong navigation target | 🔴 | **Strongly valid — real defect** | [ ] |
| 7 | `disabled` flag on user apps never set due to wrong key | 🔴 | **Strongly valid — UI regression** | [ ] |
| 8 | Inline `onclick`, `document.write`, full-container innerHTML | ⚪ | Valid style observation; works fine | [ ] |
| 9 | `auditPoll` interval not tracked, can leak on tab switch | 🟡 | Valid | [ ] |
| 10 | Table filter searches raw fields, not rendered cell content | 🟡 | Valid UX surprise | [ ] |
| 11 | `MOCK_DATA` shipped in production (5,635 B / 4% of file) | ⚪ | **Overstated** — accept | n/a |
| 12 | `hubRequest` has 3 distinct return shapes for "did it work?" | 🟡 | Valid | [ ] |
| A1 | The `shared` Map plumbing is dead infrastructure — wiring it through the API entry points would directly fix #1 | 🟠 | Internal — high leverage | [ ] |
| A2 | `apiSnapshotDiff` writes diff payload to FileManager on every request | ⚪ | Internal — write amplification | [ ] |
| A3 | `detectZwaveStack` cache never invalidated | ⚪ | Internal — trivial | [ ] |
| A4 | `state.fwUpdateCache` stores entire response Map instead of just diff-relevant fields | ⚪ | Internal — trivial | [ ] |
| A5 | Inline `onclick='+r._idx+'` references stale closure index | ⚪ | Internal — only matters mid-mutation | [ ] |
| A6 | Audit dispatch state machine (CAS-bounded) is well-designed — keep intact | ✅ | Internal — positive note | n/a |
| A7 | `apiTimings` keyed by endpoint name with no reset; renamed endpoints leak forever | ⚪ | Internal — trivial | [ ] |
| A8 | No retry / backoff in `hubRequest`; single transient timeout fails an entire panel render | ⚪ | Internal — modest impact | [ ] |
| A9 | `extractAuditFields` schema has no version sentinel; pre/post-schema records indistinguishable | ⚪ | Internal — bounded by in-memory `AUDIT_SCANS` lifetime | [ ] |
| A10 | `tbl()` re-renders entire table on header click instead of just `<tbody>` rows | ⚪ | Internal — noticeable on 350-device tables | [ ] |

---

## Detailed findings

### 🔴 #6 — `childIds` includes parent IDs

**Where:** `HubDiagnostics.groovy:1527`
```groovy
parentIds: deviceStats.parentIds, childIds: deviceStats.parentIds + deviceStats.childIds,
```

**Symptom:** `hub_diagnostics_ui.html:797` — `mc('Child', dlistlink(s.childDevices, s.childIds))`. The "Child" metric link on the Devices tab navigates to a list that contains parent device IDs concatenated with child IDs, so the user sees the wrong filtered set.

**Fix:** drop the `+ deviceStats.parentIds` (was probably intended to mean "parents AND their children" but the metric label is "Child" only).

---

### 🔴 #7 — `disabled` flag on user apps always false

**Where:** `HubDiagnostics.groovy:1548`
```groovy
.collect { [id: it.id, label: it.label ?: it.name, type: it.name,
            parentId: it.parentAppId, disabled: it.state == "disabled"] }
```

But `analyzeApps` line 2783 actually stores `disabled: app.disabled ?: false` (a Boolean) — there's no `state` key. Reading `it.state == "disabled"` always returns `false`.

**Symptom:** the "Disabled" badge on the Apps tab is never set, regardless of how many apps are actually disabled.

**Phase A tests didn't catch this** because they only assert field presence, not value correctness for booleans — see TEST_PLAN.md follow-up.

**Fix:** change to `disabled: it.disabled ?: false`.

---

### 🟠 #1 + A1 — `/hub2/hubData` fetched 4× per dashboard refresh; `shared` Map plumbing exists but is dead

**Where (callers):** `getHubInfo()` (line ~3389), `fetchHubAlerts()` (1984), `fetchSecurityInfo()` (2279), `fetchFirmwareUpdate()` (uses cache but populates from same endpoint).

**Existing infrastructure:** `getDashboardData(Map shared = [:])`, `getNetworkData(Map shared = [:])`, `getHealthData(Map shared = [:])`, `getStructuredAlerts(Map shared = [:])` — all designed to accept a shared cache. None of the API entry points (`apiDashboard`, `apiHealth`, `apiNetwork`) populate or pass a `shared` Map.

**Fix sketch:** create a private `gatherShared()` that fetches `hubData`, `runtimeStats`, `network` once and returns a Map; pass it through `getDashboardData(shared)` etc. from each `apiXxx` entry point. Per-call hub traffic drops from ~5–7 to ~3.

---

### 🟠 #3 — Audit report duplicates SPA framework

**Where:** `HubDiagnostics.groovy:4030–4393` — `renderAuditHtml` ships its own:
- CSS rules (cards, tables, summary chips, badges)
- Inline `<script>` for table sort + filter
- Open/close `<details>` collapsible card pattern

The SPA's `tbl()` (hub_diagnostics_ui.html:390), `mc()`, `ni()`, `.tbl-wrap`, `.card` classes do the same job. Future styling changes have to land in two places to stay consistent.

**Fix sketch:** extract a shared CSS/JS bundle and have the audit-report HTML reference it via inline `<style>`/`<script>` blocks pulled from constants. Or simpler short-term: move the audit's CSS into a `@Field static final String` and have both the SPA `<style>` block and the audit report consume it.

---

### 🟡 #2 — Oversized methods

**Top offenders (verified via line counting):**

| Method | Lines |
|---|---|
| `renderAuditHtml` | 363 |
| `apiForumExport` | 296 |
| `apiSnapshotDiff` | 242 |
| `analyzeDevices` | 172 |
| `analyzeApps` | 154 |

`renderAuditHtml` is the only one whose split would meaningfully improve readability — it does CSS + sections + sort/filter JS in one method. Fix is naturally implied by #3 (extract reusable parts to be shared with SPA).

The rest are coherent end-to-end pipelines — splitting them would add indirection without improving clarity.

---

### 🟡 #4 — `serveUI` sync on hot path

**Where:** `HubDiagnostics.groovy:444–449`
```groovy
long lastCheck = state.lastUIUpdateCheck ?: 0
String uiVer = getUIVersion()                    // calls downloadHubFile() — file read on every request
if (now() - lastCheck > 86400000 || uiVer == "Unknown") {
    syncUI(uiVer == "Unknown")                    // GitHub fetch
}
```

The 24-hour gate makes the GitHub-fetch path uncommon, but `getUIVersion()` runs on EVERY ui.html request — and that calls `downloadHubFile()` to read the UI from FileManager (so it can extract `const UI_VERSION = "..."` from the source).

**Fix:** cache `uiVer` in `state` after a successful sync; only re-read FileManager when `state.lastUIUpdateCheck` indicates a real check. Move the once-a-day sync into `runEveryDayAt()` instead.

---

### 🟡 #5 — Audit per-device enrichment serial after async fan-out

**Where:** `HubDiagnostics.groovy:4544` and `4557` — loops over Z-Wave nodes and Hub Mesh linked devices doing one synchronous `hubRequest` per device.

**Documented trade-off** (in v5.11.0 commit message): "for an audit that runs once a week, the few-second cost is preferable to refactoring the dispatch state machine."

**Defer until scale hurts.** Worth re-evaluating if a user reports audit times exceeding ~2 minutes on a 200+ Z-Wave-device hub.

---

### 🟡 #9 — `auditPoll` lifecycle

**Where:** `hub_diagnostics_ui.html:711–741` — `pollTimer` is closure-local; nothing clears it on tab navigation away. Multiple polls can stack up if the user clicks Generate Audit again, switches tabs, comes back.

**Fix:** track active timers in a `pollTimers` registry keyed by scanId; clear all on tab switch (already a hook point in the route map at line ~1917).

---

### 🟡 #10 — Table filter searches raw fields only

**Where:** `hub_diagnostics_ui.html:428`
```javascript
filt = rows.filter(r => cols.some(c => String(r[c.f] ?? '').toLowerCase().includes(q)));
```

For columns that use a `r:` render function (e.g. Parent column shows app name OR device name OR `-`), filtering on the visible text doesn't work — only the raw `parentAppName` field is searched. Already partially mitigated by `sf:` (sort-by alternate) on some columns but no equivalent for filter.

**Fix:** add an optional `ff:` (filter-by) column property; default to `f`; allow `r:` output for full-text search if `ff: 'rendered'` is set.

---

### 🟡 #12 — `hubRequest` weak return contract

**Where:** `HubDiagnostics.groovy:1837`

Three distinct shapes:
- text success → `String` or `null` if empty
- json success → response Map, or `{}` if `result` is null
- json exception → `[error: true, message: ...]` Map

Every caller does its own `if (!resp || resp.error) return null` dance.

**Fix sketch:** return a uniform `[ok: boolean, data: Object, error: String?]` and let callers do `if (!resp.ok) return null`. Migrating all ~40 call sites is mechanical but tedious.

---

### ⚪ #8 — Inline event handlers, `document.write`, full-container `innerHTML`

**Where:** `hub_diagnostics_ui.html:91, 92, 421, 641, 831`, plus `onclick='+r._idx+'` patterns in 3+ table renderers.

**Verdict:** works correctly today, brittle to refactor. Real cost is hidden until a substantive UI rewrite is needed. Not worth fixing in isolation — would naturally fall out of any larger SPA cleanup.

---

### ⚪ A2 — `apiSnapshotDiff` writes payload to FileManager every call

**Where:** `HubDiagnostics.groovy:904` — `saveSnapshotDiffPayload(...)`.

A 50–200 KB FileManager write per snapshots-tab refresh. The payload appears unused after writing (no read path I could find). Likely leftover from a debugging or persistence experiment.

**Fix:** remove the call, OR cache in `state` if there's a real consumer.

---

### ⚪ A3, A4, A5, A7, A8, A9, A10 — small accumulated debt

Detailed in the findings table. Each is a minor cleanup. Group as a single "house cleaning" pass when convenient.

---

## Prioritized fix plan

### Phase R-1 — Critical bug fixes (S, ~30 min)

Goal: ship the two real defects immediately. Both are one-line changes.

- [ ] **#6 fix** — `getDevicesData()` line 1527: `childIds: deviceStats.childIds` (drop `+ parentIds`)
- [ ] **#7 fix** — `getAppsData()` line 1548: `disabled: it.disabled ?: false` (was `state == "disabled"`)
- [ ] **Test guard:** add value-correctness assertions to `test-hub-diagnostics-api.sh` for both — verify `childIds` size == `childDevices` count, and that any disabled app from `/hub2/appsList` shows `disabled: true` in `/api/apps`
- [ ] Bump to v5.13.1; ship via standard push + commit + mirror

**Verification:** Phase A test passes; Devices tab Child link goes to actual child devices only; if any apps are disabled (test on a hub with one), the Apps-tab badge appears.

---

### Phase R-2 — Wire up the `shared` Map (M, ~1-2 hours)

Goal: fix #1 + A1 by completing the half-built request-scoped cache. Single biggest perf win available.

- [ ] Add `private Map gatherShared()` that fetches `hubData`, `runtimeStats`, and `analyzeNetwork()` once; returns `[hubData, runtimeStats, network, hubAlerts, resources]` Map
- [ ] In `apiDashboard`, `apiHealth`, `apiNetwork`, `apiPerformance`: build `shared` once, pass to `getXxxData(shared)`
- [ ] Refactor `getHubInfo()`, `fetchHubAlerts()`, `fetchSecurityInfo()`, `fetchFirmwareUpdate()` to accept an optional pre-fetched hubData arg (don't re-fetch when caller already has it)
- [ ] Verify: `/api/dashboard` should make ~3 hub calls instead of ~6; measure via `logDebug` instrumentation or `apiTimings`
- [ ] Bump to v5.14.0 (minor — meaningful internal refactor, no UI change)

**Verification:** `test-hub-diagnostics-api.sh` still passes (no behavioral change). Manual: enable debug logging, watch `/hub/logs` for "Fetched X" lines per dashboard refresh, count drops by ~50%.

---

### Phase R-3 — De-duplicate audit-report styling (M-L, ~2-3 hours)

Goal: fix #3 (and naturally shrink #2's `renderAuditHtml`) by sharing CSS / table primitives between the SPA and the audit report.

Option A (smaller — extract CSS only):
- [ ] Move SPA's table/card/badge CSS into a `@Field static final String SHARED_CSS`
- [ ] Reference it in both the SPA's `<style>` block (via a templated include in `serveUI`) and the audit-report renderer
- [ ] Verify: visual diff of audit report before/after — should be unchanged

Option B (larger — extract sort/filter JS too):
- [ ] As above + extract the sort-on-click / filter-on-input JS into a shared inline `<script>` constant
- [ ] Audit report's `<script>` block becomes a single line that loads the shared module
- [ ] `renderAuditHtml` shrinks from 363 → ~200 lines

Recommend Option A first; Option B if/when the audit needs more interactive features and the duplication starts hurting again.

- [ ] Bump to v5.15.0 (minor)

**Verification:** generate audit on maison-pro, compare rendered HTML byte-for-byte against pre-refactor version (modulo the extracted block).

---

### Phase R-4 — Polish layer (M, batch-able)

These are independently shippable; group 2-3 per release.

- [ ] **#4** — cache `uiVer` in `state.lastInstalledUIVersion`; move 24h sync to a scheduled job
- [ ] **#9** — add `pollTimers` global registry; clear timers in tab-switch handler
- [ ] **#10** — add `ff:` (filter-by) column property to `tbl()`; update Parent column on Devices tab to use it
- [ ] **#12** — uniform `hubRequest` return shape `[ok, data, error]`; mechanical migration of all callers
- [ ] **A2** — remove unused `saveSnapshotDiffPayload` write OR document its consumer
- [ ] **A8** — single retry with backoff in `hubRequest` for transient connection errors

Bump per-release (v5.16.x patch series).

---

### Phase R-5 — Trivial cleanup (S, single PR)

Group all remaining ⚪ items into one housekeeping commit.

- [ ] **A3** — invalidate `state.zwaveStackCache` on `updated()`
- [ ] **A4** — slim `state.fwUpdateCache` to only the displayed fields
- [ ] **A5** — replace `r._idx` closure-index with explicit row identity (e.g., `data-id` attr)
- [ ] **A7** — clear `apiTimings` for endpoint names not currently registered, on `updated()`
- [ ] **A9** — add a `_schemaVersion` field to audit records
- [ ] **A10** — `tbl()` header click: only re-render `<tbody>` instead of full table

Bump to v5.17.0 (or fold into the polish layer).

---

## Explicit non-goals (accepted trade-offs)

- **#5** (`finalizeAudit` serial enrichment) — defer until a user reports slowness on a Z-Wave-rich hub. Documented trade-off from the v5.11.0 commit.
- **#11** (`MOCK_DATA` in production) — 4% of file size, used by the workbench mode; cost is negligible vs. the dev convenience.
- **#2** for methods other than `renderAuditHtml` — `analyzeDevices`/`analyzeApps`/`apiForumExport`/`apiSnapshotDiff` are coherent pipelines; splitting would add indirection without improving clarity.
- **Larger SPA architecture rewrite** (e.g., introducing a component framework, build step, state-management layer) — explicit non-goal. The "single static HTML file with no build step" model is a feature, not a bug; it's what makes the app installable in 30 seconds with no dev tooling.

---

## How to use this document

When picking up a fix:

1. Find the relevant entry above
2. Implement
3. Run `test-hub-diagnostics-api.sh` — if it passes, ship
4. Tick the checkbox in the table at the top **and** in the detailed section
5. Add the version number that shipped the fix in a note next to the checkbox

Keep this file in sync with reality — outdated review docs are worse than none.

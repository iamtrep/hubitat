<!--
Copyright (c) 2025-2026 PJ
SPDX-License-Identifier: MIT
-->

# HubDiagnostics — Code Review Findings & Fix Plan

Status as of 2026-05-10 (post-v5.27.0 + uncommitted v5.28/5.29 WIP; rounds 1-6 complete).

Combined output of eight independent passes:
- **Codex round 1** — initial external code-review pass (12 findings)
- **Internal** — verification + 10 additional findings (A1–A10)
- **Codex round 2** — external re-review after R-1..R-5 shipped (5 net-new items B1–B5; updates to prior items)
- **Gemini round 1** — third-party review (3 net-new items G1–G3; the rest overlap)
- **Codex round 3 + Gemini round 2** — post-v5.20.0 re-reviews (zero net-new actionable items; both confirmed shipped fixes verified, deferrals reaffirmed)
- **Claude round 4** — post-v5.24.0 review (10 net-new items C1–C10)
- **Gemini round 5** — post-v5.27.0 review (5 net-new items C11–C15)
- **Claude round 6** — post-v5.27.0 + uncommitted v5.28/5.29 review (8 net-new items N1–N8; verified C1–C15 still open)

All findings were verified against the code; verdicts are recorded below.

## Round 3 summary

After R-6 + R-7 shipped, both Codex and Gemini ran fresh reviews:

- **Codex r3:** no new findings. Re-flagged 7 items, all of which trace back to existing entries in this doc (deferrals, partial fixes, accepted trade-offs). Recommended "modularization" via Hubitat libraries — not viable on this platform (libraries are concatenated at compile, no real modularity).
- **Gemini r2:** verdict "production-ready and highly optimized for the Hubitat environment." Verified all 5 R-6 + R-7 fixes (B2, B3, B4, B5, G1, A3, A7). Explicitly endorsed the #5 (audit serial enrichment) and #3 (CSS mirror) deferrals as "the correct pragmatic choice" / "great middle ground." Praised this doc as a living tech-debt register.

**Net new actionable items from round 3: zero.** The work is at a natural stopping point. Remaining open items are all explicit deferrals or accepted trade-offs documented above. Future work should be feature-driven, not review-driven cleanup.

## Round 4 summary (Claude, 2026-05-09, v5.24.0)

Reviewed full source at v5.24.0. Confirmed all prior fixes. Found 10 net-new items (C1–C10):

- **C1** (🔴) — silent wrong-diff when `createSnapshot()` fails inside `apiSnapshotDiff(newer=now)`: highest-priority defect.
- **C2** (🟡) — no `logsOff` auto-disable for debug logging.
- **C3** (🟡) — `generateQuickSummary()` makes 5+ blocking HTTP calls during preferences page render.
- **C4** (🟡) — `apiGenerateReport` fetches `/hub2/appsList` 3–4 times (shared cache doesn't cover it).
- **C5** (🟡) — alert-generation logic duplicated between `getStructuredAlerts` and `analyzeSystemHealth`.
- **C6** (⚪) — `readFile()` return type is untyped `def`; several `def` variables in `apiSnapshotDiff` and elsewhere.
- **C7** (⚪) — pure-computation methods missing `@CompileStatic` (only `stripHtml` has it).
- **C8** (⚪) — `fetchHubMessages()` called unconditionally on every dashboard/health render (5s timeout in hot path).
- **C9** (⚪) — `schedule(cron, ...)` `*/N` day syntax has known Hubitat firmware quirk for multi-day auto-snapshot intervals.
- **C10** (⚪) — `autoEnableOAuth()` uses raw `httpGet`/`httpPost` without the retry-on-transient behavior from A8.

## Round 6 summary (Claude, 2026-05-10, v5.27.0 + uncommitted v5.28/5.29 WIP)

Comprehensive code + architecture review against `ARCHITECTURE.md`. All 15 prior open items (C1–C15) verified still present in current code. Found 8 net-new items (N1–N8), most introduced by the uncommitted caching changes in `getPerformanceData` and `loadCheckpoints`/`saveCheckpoints`:

- **N1** (🟠) — five new volatile caches (`cachedZwaveData/At`, `cachedZigbeeData/At`, `cachedAppsListData/At`, `cachedDevicesListData/At`, `cachedCheckpoints`) are not cleared in `updated()`, breaking the established A3/A7/B5 cache-invalidation pattern.
- **N2** (🟠) — caching strategy fragmented: `ZWAVE_DETAILS_PATH`/`ZIGBEE_DETAILS_PATH`/`APPS_LIST_PATH`/`DEVICES_LIST_PATH` are fetched at 11+ call sites, but only the `getPerformanceData` path uses the new TTL caches. Same-request concurrency may see one path fresh and another cached.
- **N3** (🟡) — TTL cache pattern duplicated 4× in `getPerformanceData` (lines 1497–1578); a 6-line helper would consolidate.
- **N4** (🟡) — `cachedCheckpoints` shares its list reference with callers in both directions; a single in-place mutation by any caller silently corrupts the cache and bypasses persistence.
- **N5** (⚪) — aggregator-rationale comments missing on `apiCode` and `apiPerformance` (the recent mappings reorg added them everywhere else).
- **N6** (⚪) — `getUIVersion` cache is a `@Field static volatile`, not `state.cachedUIVersion` as the #4 fix description in this doc claims; fix the code or the doc.
- **N7** (⚪) — `_perfInFlight` in-flight guard is local to `rPerf`; lifting dedup into `api()` would cover all tabs in one change.
- **N8** (⚪) — version drift in WIP: `APP_VERSION = 5.29.0` vs `UI_VERSION = 5.28.0`.

**Architectural notes (no code change requested):**
- The `mappings { }` reorganization into Aggregator / App-owned / Mutations / Long-running / Side-effectful sections matches ARCHITECTURE.md cleanly — preserve on every new route.
- Six caching mechanisms now coexist (request-scoped `shared`, persistent `state`, ghost-node state-gate, volatile-field with `updated()` clear, new TTL'd volatile fields, SPA in-memory). Resolving N2 with a single abstraction would slow the proliferation.
- Audit-report and forum-export move to the SPA (v5.25.0) softened the "Groovy normalizes, SPA renders" boundary; consider documenting as an accepted trade-off in ARCHITECTURE.md so future contributors don't push more parsing into the SPA.

## Round 5 summary (Gemini CLI, 2026-05-09, v5.27.0)

Reviewed full source at v5.27.0. Validated alignment with `ARCHITECTURE.md` platform constraints. Confirmed Round 4 findings (C1–C10) remain open/valid. Found 5 net-new items (C11–C15):

- **C11** (🟠) — Fragile regex-parsing of internal Hubitat HTML (`zwaveTopologyHtml`, `parseZigbeeMesh`). High risk of breakage upon platform firmware updates.
- **C12** (🟠) — Audit memory pressure: concurrent `fullJson` fetches (up to 8) for every device can cause significant JVM heap spikes on large hubs.
- **C13** (🟡) — High cyclomatic complexity in `buildForumMarkdown` (JS) and `buildCrossReference` (Groovy). Giant procedural functions that are difficult to maintain.
- **C14** (⚪) — Static version management: `APP_VERSION` and `UI_VERSION` are maintained separately in two files, increasing risk of code/UI drift.
- **C15** (🟡) — In-memory static storage of `lastAuditResult`: potentially holds MBs of data in the JVM heap until the next reboot, bypassing platform state management.

---

## Round 7 summary (Claude, 2026-05-10, hub→browser load-balancing audit)

Architectural audit triggered by the user's intuition that too much CPU was being spent on the constrained 4-core ARM A53 hub for derivations the browser could do trivially. Audit verdict: confirmed. Plan recorded at `/Users/trep/.claude/plans/i-feel-the-balance-elegant-lighthouse.md`.

**Shipped (this branch, `browser-hub-load-balancing`):**

- ARCHITECTURE.md revisions (`1a59674`) — added "the hub is the constrained side" guiding principle; tightened "aggregation" definition; added derivable-data and hot-path-blocking entries to *Patterns To Avoid* and *Architecture Regression*; rewrote "Add a new card or table" to lead with the irreducible-vs-derivable decision.
- v5.29.1 (`19f2a6a`) — `fetchSystemResources()` 10s memory cache. Benefits all 11 callers, not just `apiLive`.
- v5.30.0 (`19f2a6a`) — `getStructuredAlerts()` → `getAlertSignals()`. Hub no longer composes the alert array, runs `stripHtml()` on every refresh, or applies threshold-based severity. SPA derives alerts client-side via `composeAlerts(d)`.
- v5.31.0 (`882a0ab`) — `apiSnapshotDiff()` deleted (~240 lines); `/api/snapshot/diff` route gone. SPA `computeSnapshotDiff(older, newer)` mirrors the original Groovy logic field-for-field. Compare-button handler fetches two snapshots via `/api/snapshot/view` and computes the diff client-side.
- v5.32.0–.5 (`fecaf3c`) — `apiGenerateReport()` (~40 lines + multi-MB serialization) replaced by thin `apiSaveReport()` (POST `/api/report/save`, body = `{filename, html}`) + `apiReportTemplate()` (GET, returns raw template). SPA fetches the 9 needed aggregators in concurrent batches of 3, builds the HTML, and POSTs. Patch-level fixes resolved the rollout scars: literal `</script>` in JS strings (parser closed the script tag prematurely), hub serve-time substitution of `${access_token}` placeholders inside the SPA's own JS source, connection-pool exhaustion from unbounded `Promise.all`, and a latent `report-mode` class-add ordering bug that had silently broken `.actions` / `.live-only` hiding in offline reports for the entire history of the feature.

**Net hub-side delta:** ~−320 lines of derivation code, plus the report path turned from "8 sequential aggregator calls + multi-MB string-replace" into "thin file write".

**Open (Tier 2 + remaining Tier 3):** see LB1–LB5 in the findings table. All are explicit deferrals — Tier 1 (the highest-CPU items) is complete. Per-item value drops noticeably from here.

**Side effect on N1:** v5.29.1 added `cachedSystemResources` + `cachedSystemResourcesAt` static fields. They are NOT cleared in `updated()` either, extending N1's scope. Roll into the same fix.

**Test coverage carried as a known gap.** See `~/.claude/projects/-Users-trep-Documents-GitHub-iamtrep-hubitat/memory/project_hubdiag_test_coverage.md`. The bash API test only verifies hub-side contracts; SPA-side derivations (`composeAlerts`, `computeSnapshotDiff`, the report builder, plus anything LB-* lands) are unverified by automation. Decision recorded: option A (manual verification) for now; trigger to revisit is the start of Tier 2 work (LB1).

---

## Findings table

Severity legend: 🔴 critical bug · 🟠 high (architecture / leverage) · 🟡 medium · ⚪ low / cosmetic

| # | Finding | Severity | Verdict | Status |
|---|---|---|---|---|
| 1 | Re-fetch in single request path; multiple `/hub2/hubData` per dashboard refresh | 🟠 | **Fixed v5.14.0** | [x] |
| 2 | Oversized methods (`renderAuditHtml` 363 lines, `apiSnapshotDiff` 242, `analyzeDevices` 172) | 🟡 | **Partially fixed v5.17.0** (renderAuditHtml shrunk by 38 lines via CSS extraction); other methods accepted | [~] |
| 3 | Audit report duplicates SPA framework (own CSS / own table-sort JS) | 🟠 | **Partially fixed v5.17.0** (CSS now in 1 visible block; sort/filter JS still inline — Option B) | [~] |
| 4 | `serveUI` runs `getUIVersion()` + sync check on every request | 🟡 | **Fixed v5.15.0** | [x] |
| 5 | `finalizeAudit` enrichment is serial after the async fan-out | 🟡 | Valid scaling concern; documented trade-off — defer until scale hurts | [ ] |
| 6 | `childIds` includes parent IDs — wrong navigation target | 🔴 | **Fixed v5.13.1** | [x] |
| 7 | `disabled` flag on `apps.userApps` never set due to wrong key | 🟡 | **Fixed v5.13.1** (downgraded from 🔴 — currently-unconsumed code path; preventive) | [x] |
| 8 | Inline `onclick`, `document.write`, full-container innerHTML | ⚪ | Valid style observation; works fine | [ ] |
| 9 | `auditPoll` interval not tracked, can leak on tab switch | 🟡 | **Fixed v5.16.0** (dedupe via pollTimers Map) | [x] |
| 10 | Table filter searches raw fields, not rendered cell content | 🟡 | **Fixed v5.16.0** (`ff:` column property) | [x] |
| 11 | `MOCK_DATA` shipped in production (5,635 B / 4% of file) | ⚪ | **Overstated** — accept | n/a |
| 12 | `hubRequest` has 3 distinct return shapes for "did it work?" | 🟡 | **Fixed v5.22.0** (`hubMapRequest` + ~35 callers migrated to `[ok, data, error]`) | [x] |
| A1 | The `shared` Map plumbing is dead infrastructure — wiring it through the API entry points would directly fix #1 | 🟠 | **Fixed v5.14.0** | [x] |
| A2 | `apiSnapshotDiff` writes diff payload to FileManager on every request | ⚪ | **Fixed v5.15.0** (load fn was unused — removed entire feature) | [x] |
| A3 | `detectZwaveStack` cache never invalidated | ⚪ | **Fixed v5.18.0** (cleared in updated()) | [x] |
| A4 | `state.fwUpdateCache` stores entire response Map instead of just diff-relevant fields | ⚪ | **Already done** (cache already stored slim 6-field Map; verified v5.18.0) | [x] |
| A5 | Inline `onclick='+r._idx+'` references stale closure index | ⚪ | **Deferred** — needs API contract rethink (delete-by-id rather than delete-by-index) | [-] |
| A6 | Audit dispatch state machine (CAS-bounded) is well-designed — keep intact | ✅ | Internal — positive note | n/a |
| A7 | `apiTimings` keyed by endpoint name with no reset; renamed endpoints leak forever | ⚪ | **Fixed v5.18.0** (cleared in updated()) | [x] |
| A8 | No retry / backoff in `hubRequest`; single transient timeout fails an entire panel render | ⚪ | **Fixed v5.15.0** (single retry on SocketTimeoutException/ConnectException) | [x] |
| A9 | `extractAuditFields` schema has no version sentinel; pre/post-schema records indistinguishable | ⚪ | **Fixed v5.18.0** (`_schemaVersion: 1` field) | [x] |
| A10 | `tbl()` re-renders entire table on header click instead of just `<tbody>` rows | ⚪ | **Fixed v5.18.0** (tbody-only re-render; preserves filter focus + details state) | [x] |
| B1 | `analyzeDevices` deep-mode N+1: one sync `/device/fullJson` per uncertain device | 🟠 | **Deferred** — `state.controllerTypeCache` mitigates: cost paid only on fresh install + after `updated()` clears cache (rare); async refactor would need API-contract change (poll-based) for low frequency | [-] |
| B2 | Performance-tab UI re-fetches `api('devices')` + `api('apps')` for chart labels | 🟡 | **Fixed v5.20.0** (server-side label maps in `/api/performance`) | [x] |
| B3 | `rDevices` computes `dt` (device-types sorted) and `stale` (filtered devices) twice each | ⚪ | **Fixed v5.19.0** | [x] |
| B4 | `tbl()` filter input fires on every keystroke; no debounce; full filter+sort+`<tbody>` rewrite per char | 🟡 | **Fixed v5.19.0** (150ms debounce) | [x] |
| B5 | `state.controllerTypeCache` grows unbounded; only cleared via explicit user action | ⚪ | **Fixed v5.19.0** (cleared in updated()) | [x] |
| G1 | `apiAuditStatus` reads from `state.audit` snapshot, not the underlying `AtomicInteger` — counter can lag by 1-7 during high-concurrency scans | 🟡 | **Fixed v5.19.0** (reads AtomicInteger when in-flight) | [x] |
| G2 | `AUDIT_SCANS` in-memory; lost on JVM reload or hub reboot mid-scan; no resume | n/a | **Won't fix** — volatile storage is the deliberate design choice; user-acknowledged trade-off | [x] |
| G3 | `INTEGRATION_TABLE` / `ALERT_DISPLAY_NAMES` hardcoded; no user-configurable mappings | ⚪ | Gemini — feature request, not defect; existing driver-side extension point covers most cases | [-] |
| C1 | `createSnapshot()` failure is silent in `apiSnapshotDiff(newer=now)` — wrong diff returned, no error | 🔴 | Open | [ ] |
| C2 | No `logsOff` auto-disable guard — debug logging stays on indefinitely once enabled | 🟡 | Open | [ ] |
| C3 | `generateQuickSummary()` makes 5+ blocking HTTP calls during preferences page render | 🟡 | Open | [ ] |
| C4 | `apiGenerateReport` fetches `/hub2/appsList` 3–4× in one call (shared cache gap) | 🟡 | Open | [ ] |
| C5 | Alert threshold logic duplicated between `getStructuredAlerts` and `analyzeSystemHealth` | 🟡 | Open | [ ] |
| C6 | `readFile()` return untyped `def`; stray `def` in `apiSnapshotDiff` (lines 989–996) and elsewhere | ⚪ | Open | [ ] |
| C7 | Pure-computation methods missing `@CompileStatic` (only `stripHtml` has it) | ⚪ | Open | [ ] |
| C8 | `fetchHubMessages()` called unconditionally in hot path with 5s timeout | ⚪ | Open | [ ] |
| C9 | `schedule(cron, ...)` `*/N` day syntax has Hubitat firmware quirk for multi-day auto-snapshot | ⚪ | Open | [ ] |
| C10 | `autoEnableOAuth()` bypasses the retry-on-transient behavior from A8 | ⚪ | Open | [ ] |
| C11 | Fragile regex-parsing of internal Hubitat HTML (topology, zigbee mesh) | 🟠 | Open | [ ] |
| C12 | Audit memory pressure: concurrent `fullJson` fetches spike JVM heap | 🟠 | Open | [ ] |
| C13 | Excessive complexity in `buildForumMarkdown` and `buildCrossReference` | 🟡 | Open | [ ] |
| C14 | Version drift risk: `APP_VERSION` and `UI_VERSION` maintained separately | ⚪ | Open | [ ] |
| C15 | Static `lastAuditResult` bypasses state management and consumes heap | 🟡 | Open | [ ] |
| N1 | New volatile caches (`cachedZwaveData/At`, `cachedZigbeeData/At`, `cachedAppsListData/At`, `cachedDevicesListData/At`, `cachedCheckpoints`) not cleared in `updated()` | 🟠 | Open | [ ] |
| N2 | Caching strategy fragmented: same hub endpoints fetched at 11+ call sites; only `getPerformanceData` uses the new TTL caches | 🟠 | Open | [ ] |
| N3 | TTL cache pattern duplicated 4× in `getPerformanceData` (DRY) | 🟡 | Open | [ ] |
| N4 | `cachedCheckpoints` shares list reference with callers; in-place mutation silently corrupts cache + bypasses persistence | 🟡 | Open | [ ] |
| N5 | Missing aggregator-rationale comments on `apiCode` and `apiPerformance` (other routes have them post-reorg) | ⚪ | Open | [ ] |
| N6 | `getUIVersion` cache is `@Field static volatile`, not `state.cachedUIVersion` as #4 fix description claims | ⚪ | Open | [ ] |
| N7 | `_perfInFlight` in-flight guard is tab-local; could be lifted into `api()` to dedupe across all tabs | ⚪ | Open | [ ] |
| N8 | Version drift in WIP: `APP_VERSION=5.29.0` vs `UI_VERSION=5.28.0` | ⚪ | Open | [ ] |
| LB1 | `buildCrossReference()` (~120 lines) does sorts, indexes, mode computation hub-side; should ship raw audit array and let SPA derive | 🟡 | Open (Tier 2) | [ ] |
| LB2 | Top-N rankings & per-tab sorts done hub-side (network neighbors weak/stale, app type/name sorts, distribution sorts); SPA `tbl()` already sorts on column click | ⚪ | Open (Tier 2) | [ ] |
| LB3 | `apiForumData()` runs 15 sequential synchronous fetches in one request including full `analyzeDevices(true)` + `analyzeApps(true)`; split or stop running deep variants when only counts are needed | ⚪ | Open (Tier 2) | [ ] |
| LB4 | Z-Wave ghost recount lives inside `getAlertSignals()` hot path (cache miss = 8s blocking fetch); move detection to a periodic scheduled task and read `state.cachedZwaveGhostCount` from alerts | 🟡 | Open (Tier 3) | [ ] |
| LB5 | `enrichDevices()` fan-out cap: bound the per-request count of `/device/fullJson` lookups so a cold `state.controllerTypeCache` cannot trigger N+1 sync calls on every dashboard load | ⚪ | Open (Tier 3) | [ ] |

---

## Detailed findings

### ✅ #6 — `childIds` includes parent IDs (fixed v5.13.1)

**Where:** `HubDiagnostics.groovy:1527` (now corrected)

**Original:** `childIds: deviceStats.parentIds + deviceStats.childIds` — concatenated the two lists.

**Fixed:** `childIds: deviceStats.childIds`.

**Symptom (resolved):** `hub_diagnostics_ui.html:797` — `mc('Child', dlistlink(s.childDevices, s.childIds))`. The "Child" metric link on the Devices tab now navigates to the actual child devices only.

**Regression guard:** test-hub-diagnostics-api.sh now asserts `len(childIds) == childDevices` AND `childIds ∩ parentIds == ∅`.

---

### ✅ #7 — `disabled` flag on `apps.userApps` always false (fixed v5.13.1)

**Where:** `HubDiagnostics.groovy:1548` (now corrected)

**Original:** `disabled: it.state == "disabled"` against a Map with no `state` key → always `false`.

**Fixed:** `disabled: it.disabled ?: false` — reads the actual Boolean from `analyzeApps`.

**User-visible impact (turned out to be smaller than first thought):** the SPA's Apps-tab Disabled badge actually reads from `apps.allApps[*].disabled`, which was sourced separately from `analyzeApps` line 2799 and was always correct. The `apps.userApps` rows array is exposed in the API response but isn't currently consumed by the SPA. So the bug existed in a dead code path; the fix is preventive (keeps the API contract correct for future SPA features and external API consumers, and matches the snapshot-view path which sources `userAppsList` directly from `analyzeApps`).

**Regression guard:** test-hub-diagnostics-api.sh now asserts `apps.userApps[*].disabled` count matches the `data.user==True AND data.disabled==True` count from `/hub2/appsList` ground truth.

---

### ✅ #1 + A1 — `/hub2/hubData` fetched 2-3× per request; `shared` Map plumbing was dead (fixed v5.14.0)

**Original callers of `/hub2/hubData`:** `fetchHubAlerts()`, `fetchSecurityInfo()`, `getHubInfo()`. (CODE_REVIEW originally also listed `fetchFirmwareUpdate` — wrong; that one hits `/hub/cloud/checkForUpdate`, not hubData.)

**Fix shipped:** new `private Map buildSharedCache(boolean includeNetwork = false)` helper pre-fetches `hubData`, `resources`, `temperature`, `databaseSize`, `hubAlerts` once per request. The three hubData consumers now accept an optional `Map prefetchedHubData = null` and skip the self-fetch when one is provided. Wired through `apiDashboard`, `apiHealth`, and (minimally) `apiNetwork`.

**Per-request hubData fetches:**
- `/api/dashboard`: 2 → 1
- `/api/health`:    2 → 1
- `/api/network`:   1 → 1 (consistency only — only `fetchSecurityInfo` consumed hubData here)

Plus `fetchSystemResources` / `fetchTemperature` / `fetchDatabaseSize` de-duplicated within each request — previously called once directly and a second time by `getStructuredAlerts`'s `?:` fallback when shared was empty.

**Resilience trade-off note (one-time hub-stress event observed post-deploy):** when hubData times out (10s), pre-R-2 the two consumers (fetchHubAlerts + getHubInfo) each made independent retry attempts, sometimes one of which succeeded. Post-R-2 both share one fetch attempt. Downstream still degrades gracefully (empty alerts, fall-back hub.hardware from `location.hubs[0].type`, null cloudController) — same UI degradation, just more deterministic now. R-4's items #12 + A8 (uniform return shape + retry) will address this resilience concern properly.

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

## Round 2 detailed findings (Codex re-review post-v5.18.0)

### Codex r2 item 5 — re-flag of #3 (audit CSS mirror)

Codex's round-2 item 5 re-flags the audit-report CSS as a "manually mirrored copy" and points out that the documented "developers must keep them in sync" comment in `AUDIT_REPORT_CSS` is itself a guaranteed drift point.

**Same code, different judgment threshold.** R-3 v5.17.0 picked Option A (pragmatic) — extract the CSS to a single visible block, accept manual sync as documented discipline. Codex would prefer Option C (true single source).

**Cheapest path to true single source if we change our mind:** runtime SPA-CSS readout. In `renderAuditHtml`, fetch the SPA HTML from FileManager once per audit, regex-extract the `<style>` block, append `AUDIT_EXTRA_CSS` for audit-only bits (TOC, summary triangles, etc.). One source of truth, no build step, workbench still works (SPA standalone has CSS inline). Cost: one ~140KB FileManager read per audit — negligible vs. the 30–60s scan.

**Decision rule for picking it up:** if you find yourself updating both CSS blocks more than 2-3 times, the discipline isn't holding — switch to runtime readout. Until then, keep the documented mirror.

### 🟠 B1 — `analyzeDevices` deep-mode N+1

**Where:** `analyzeDevices()` in HubDiagnostics.groovy. After the fast `/devicesList` pass, deep mode iterates uncertain devices and fires one synchronous `/device/fullJson/{id}` per device.

**Symptom:** latency grows linearly with the number of ambiguous devices. On hubs with many cloud-integration devices that need classification disambiguation, this can add many seconds to the Devices tab load.

**Asymmetry:** only the audit pipeline got the async-fanout treatment (CAS-bounded, 8-concurrent). Device classification didn't.

**Fix sketch:** mirror the audit's dispatch pattern — async fan-out with concurrency cap, single completion callback that aggregates classifications. Or, simpler interim: only do deep mode when explicitly requested (it's already gated by an arg in `analyzeDevices(deep)`); ensure dashboard/devices-tab paths request `deep=false` and only the audit (which already has its own enrichment) requests deep.

### 🟡 B2 — Performance-tab cross-endpoint refetch

**Where:** `hub_diagnostics_ui.html:1491` and `hub_diagnostics_ui.html:1508` — the Performance tab calls `api('devices')` and `api('apps')` to build chart labels (device-name lookup for top talkers, app-name lookup for runtime detail).

**Symptom:** opening Performance triggers two extra full endpoint hits even though the user already loaded those tabs (likely cached client-side at the tab-render level but not at the cross-tab level).

**Fix sketch:** client-side cache layer on `api()` calls keyed by endpoint, with a 30-60s TTL. OR a server-side endpoint that returns just the id→name mapping needed for chart labels (small payload, can be cached aggressively). Currently the Performance tab punishes you for visiting it.

### ⚪ B3 — `rDevices` double computation

**Where:** `hub_diagnostics_ui.html:850-862` (build phase) + `:866-875` (init phase).

`dt` (sorted device-type entries) is built once for the card markup at line 852 then rebuilt at 867 to feed `tbl('dtTbl', ..., dt)`. Same for `stale` at 858/871.

**Fix:** lift both into local variables before the markup-build phase, reuse in the post-render `tbl()` call.

### 🟡 B4 — Filter input no debounce

**Where:** `hub_diagnostics_ui.html:426` — `fi.addEventListener('input', () => { ... })`.

Every keystroke triggers `rows.filter(...)` → `sf(filt, sc, sd)` (sort) → `tb.innerHTML = bldRows(filt)`. For a 350-row per-device audit table or runtime-detail table, this is noticeable.

**Fix:** wrap in 150ms `setTimeout` debounce. One-line change. Standard pattern.

### ⚪ B5 — `state.controllerTypeCache` unbounded growth

**Where:** `state.controllerTypeCache` populated by enrichment paths (HubDiagnostics.groovy:1515, 1553, 3266 per Codex). Cleared only via the user-triggered "Clear Enrichment Cache" button.

**Symptom:** cache size grows monotonically over the lifetime of an install. Each entry is small (one device → one classification string) so practical impact is bounded by the device count, but there's no automatic eviction for devices that have been removed.

**Fix:** on `updated()` (matches the R-5 A3/A7 pattern), either clear entirely or cross-reference against the current `/devicesList` and evict orphaned entries. Or add a TTL.

---

## Gemini review additions

### Note on Gemini #2 — "split into Hubitat Libraries"

Gemini recommended splitting the 5k-line file into Hubitat Libraries (the platform's `library` mechanism). **This recommendation is not viable on Hubitat:** the platform simply concatenates imported libraries into a single script at compile time — there's no namespace separation, no independent versioning, no isolation. The file size and cognitive load are identical pre/post-split; only the on-disk file count changes. The underlying "god object" concern is valid (and tracked as #2 in this doc), but the suggested fix doesn't actually help.

### 🟡 G1 — `apiAuditStatus` reads from snapshot, not AtomicInteger

**Where:** `apiAuditStatus()` returns `snap.processed` from `state.audit` (HubDiagnostics.groovy:4769). `state.audit.processed` is updated by `fullJsonCb` after each device fetches: `int processed = scan.processed.incrementAndGet(); ... state.audit = snap` (lines 4546, 4554).

**Verified race:** with up to 8 concurrent fullJsonCb instances, all reading the AtomicInteger correctly via `incrementAndGet()` then writing the snapshot value to `state.audit`, the final committed `state.audit.processed` value reflects whichever callback's method-end happened last (Hubitat's "last write wins" persistence semantics).

**Practical impact:** the AtomicInteger itself is monotonic and correct. Snapshots can only LAG the true count, never lead it. Counter visible to UI may stutter or lag by up to 7 (the in-flight depth) during scanning, but never goes backward and the final terminal value is always correct.

**Fix:** in `apiAuditStatus`, read `processed` directly from `AUDIT_SCANS[scanId].processed.get()` when the scan is still in-flight; fall back to `state.audit.processed` for completed scans (when AUDIT_SCANS entry has been removed). Eliminates the race entirely.

### G2 (won't fix) — Audit pending-queue persistence for reboot-resume

**Symptom:** if Hubitat recycles the app's JVM mid-audit (e.g., hub reboot, code update), the entire `AUDIT_SCANS` map vanishes. The watchdog timer (`AUDIT_WATCHDOG_SEC = 120`) is also tied to the JVM.

**Decision:** **volatile storage is the deliberate design choice.** Persisting per-callback would slow the audit substantially; persisting only the queue would still need careful invariants around resume-after-reboot. The audit is user-triggered and recoverable by re-trigger. User has acknowledged this trade-off.

### G3 (deferred) — User-configurable integration mappings

**Symptom:** `INTEGRATION_TABLE` (parent-app-name → integration-name mapping for the Devices tab Integration column) is hardcoded in `HubDiagnostics.groovy`. New community integrations not in the table fall through to "Other".

**Existing extension point:** drivers can call `updateDataValue("hubdiag:conn", "<type>")` to override classification per-device (documented in README). Covers driver-author additions but not end-users without driver access.

**Fix path if/when needed:** add a settings field for "Custom Integration Mappings" (JSON or key=value lines), merged with the hardcoded table at runtime. Or read from a JSON file in FileManager.

**Verdict:** feature request, not bug. Current driver-side mechanism handles the common extensibility case. Defer.

---

## Round 4 detailed findings (Claude, post-v5.24.0)

### 🔴 C1 — `createSnapshot()` failure is silent in `apiSnapshotDiff(newer=now)`

**Where:** `HubDiagnostics.groovy:907–915`

When the user diffs against "now", the handler calls `createSnapshot()` (a `void` method with no return value), then reloads the snapshot list and takes `snapshots[0]`. If `createSnapshot()` fails mid-execution (any fetch error, execution timeout, file write failure), the list doesn't grow, `snapshots[0]` is still the prior newest snapshot, and the diff is computed between two stale snapshots with no error surfaced to the user.

**Additional risk:** `createSnapshot()` calls `analyzeDevices(deep=true)`, which on a hub with many uncertain devices triggers the N+1 `fullJson` path (B1). This path is also triggered by `createSnapshot()` called from the auto-snapshot scheduler, but there the timeout is a background job (generous). In an HTTP handler (30s default), it can legitimately time out on a large hub.

**Fix:**

```groovy
// Before:
createSnapshot()
snapshots = loadSnapshots()
newer = snapshots[0]

// After:
int countBefore = loadSnapshots().size()
createSnapshot()
snapshots = loadSnapshots()
if (snapshots.size() <= countBefore) {
    return jsonResponse([error: "Failed to create live snapshot — check hub logs"])
}
newer = snapshots[0]
```

---

### 🟡 C2 — No `logsOff` auto-disable guard for debug logging

**Where:** `HubDiagnostics.groovy:482` (setting), `3714–3716` (logDebug check)

The `debugLogging` setting is a manual toggle with no auto-expiry. A user who enables debug to investigate an issue and forgets to turn it off generates verbose output indefinitely. Standard Hubitat practice is to schedule `logsOff` in `updated()`:

```groovy
// In updated():
if (settings.debugLogging) runIn(1800, 'logsOff')

void logsOff() {
    app.updateSetting("debugLogging", [type: "bool", value: false])
    logInfo "Debug logging auto-disabled after 30 minutes"
}
```

---

### 🟡 C3 — `generateQuickSummary()` makes 5+ blocking HTTP calls during preferences page render

**Where:** `HubDiagnostics.groovy:3680–3706`, called from `dashboardPage()` at line 417.

`analyzeDevices(false)` calls `buildAppLookupMap()` (→ `/hub2/appsList`), `buildCommunityDriverSet()` (→ `/hub2/userDeviceTypes`), and the main `/hub2/devicesList` fetch. `analyzeApps(false)` hits `/hub2/appsList` again. `getHubInfo()` hits `/hub2/hubData`. `fetchSystemResources()` hits the memory endpoint. That is 5–6 sequential blocking calls adding 1–3 seconds to every preferences page open.

**Fix options (in order of effort):**
1. **Cache the summary in `state` for 60–120s.** Write a new `refreshQuickSummary()` scheduled method; `generateQuickSummary()` reads from `state.cachedSummary` if fresh.
2. **Reduce to zero-HTTP.** Use only `location.hubs[0]` fields (firmware, hardware, IP) + the last `state.audit` and `apiTimings` — no network calls at all. Device/app counts would be stale (from last API request) but preferences page doesn't need live accuracy.

---

### 🟡 C4 — `apiGenerateReport` fetches `/hub2/appsList` 3–4 times in one call

**Where:** `HubDiagnostics.groovy:1186–1226`

`apiGenerateReport` builds a `shared` map for network/resources/temperature/alerts but not for the apps list. As a result:
- `getDashboardData(shared)` → `analyzeDevices(false)` → `buildAppLookupMap()` → `/hub2/appsList` (#1)
- `getDevicesData()` → `analyzeDevices()` → `buildAppLookupMap()` → `/hub2/appsList` (#2)
- `getAppsData()` → `analyzeApps()` → `/hub2/appsList` (#3)
- `getPerformanceData(shared)` → `hubMapRequest(APPS_LIST_PATH, ...)` (#4, for `appSourceById`)

**Fix:** add `shared.appsList` pre-fetch in `apiGenerateReport`, then thread it through `buildAppLookupMap(prefetchedApps)`, `analyzeApps(prefetchedApps)`, and `getPerformanceData`. Lower priority than C1–C3 since report generation is infrequent and user-triggered.

---

### 🟡 C5 — Alert threshold logic duplicated between `getStructuredAlerts` and `analyzeSystemHealth`

**Where:** `HubDiagnostics.groovy:1884–1907` and `3106–3127`

Both methods independently compute the same memory / CPU / temperature threshold comparisons from the same `settings.*` fields and emit structurally identical `[severity, name]` Maps. If a threshold label or severity mapping ever changes, it requires two edits.

**Fix:** extract to a private helper:

```groovy
private List buildThresholdAlerts(Map resources, Float temp) {
    List alerts = []
    int critMemKb   = ((settings.critMemMb   ?: DEFAULT_CRIT_MEM_MB)   as int) * 1024
    // ... (shared threshold logic)
    return alerts
}
```

Call from both `getStructuredAlerts` and `analyzeSystemHealth`.

---

### ⚪ C6 — `readFile()` return untyped `def`; stray `def` locals

**Where:** `HubDiagnostics.groovy:3997` (`readFile` signature), `989–996` (`apiSnapshotDiff`), `2957` (closure param), `3208` (`buildZwaveGhostNodes`), `3939/3958/3976` (file-read locals).

`readFile` returns either a parsed JSON `List`/`Map` or `null` — `Object` is the honest type. The `def` locals in `apiSnapshotDiff` (`olderZbCh`, `newerZbCh`, etc.) should be `Object`. Mechanical cleanup only; no behavioral impact.

---

### ⚪ C7 — Pure-computation methods missing `@CompileStatic`

**Where:** `parseUptime` (line 3804), `formatMemory` (3840), `formatDuration` (3830), `isNewer` (3850), `isVersionAtLeast` (2070), `computeMode` (4218), `parseHubitatTimestamp` (4228), `parseZWaveVersion` (2634).

Only `stripHtml` has `@CompileStatic`. These methods are all pure computation with no access to Hubitat dynamic properties. Adding the annotation catches type errors at compile time and is a safe, no-behavior-change improvement.

---

### ⚪ C8 — `fetchHubMessages()` called unconditionally in hot path with 5s timeout

**Where:** `HubDiagnostics.groovy:1925`, inside `getStructuredAlerts()`.

`fetchHubMessages()` hits `/hub/messages` with a 5-second timeout on every dashboard and health render. On a hub where this endpoint is slow (or unavailable after a firmware change), it adds up to 5s to every response. The endpoint is localhost so worst-case is rare, but a 60-second `state` cache (matching the Z-Wave ghost-node pattern already in use at line 1940) would cap the exposure.

---

### ⚪ C9 — `*/N` day cron syntax has Hubitat firmware quirk

**Where:** `HubDiagnostics.groovy:5064` — `String cron = days == 1 ? "0 0 0 * * ?" : "0 0 0 */${days} * ?"`

The `*/N` day-of-month expression means "every Nth calendar day starting from the 1st of the month", not "every N days from now". For `days=2`, this fires on the 1st, 3rd, 5th … not every 48 hours. The Quartz scheduler embedded in Hubitat handles this inconsistently across firmware versions. For `days=1` the existing `"0 0 0 * * ?"` (daily) is correct. For multi-day intervals, `runIn(days * 86400, 'createSnapshotAndReschedule')` with a self-rescheduling helper is more reliable.

**Impact:** low — most users leave `snapshotInterval` at the default (1 day), where the correct `"0 0 0 * * ?"` is already used.

---

### ⚪ C10 — `autoEnableOAuth()` bypasses the retry-on-transient behavior

**Where:** `HubDiagnostics.groovy:3870` (`getAppTypeId` uses raw `httpGet`), `3913–3931` (`autoEnableOAuth` uses raw `httpPost`).

These two calls use blocking `httpGet`/`httpPost` directly rather than `hubRequest`/`hubMapRequest`, so they don't get the single retry-on-transient error added in A8. On a freshly booted hub where the local HTTP server is briefly slow, the OAuth auto-enable can fail silently. Impact is first-install only; the workaround is manual OAuth enable. Lowest priority.

---

## Round 6 detailed findings (Claude, post-v5.27.0 + v5.28/5.29 WIP)

### 🟠 N1 — New volatile caches not cleared in `updated()`

**Where:** `HubDiagnostics.groovy:148–156` (declarations) and `:4348–4361` (`updated()` body).

The uncommitted v5.28/5.29 work added five new volatile fields:

```groovy
@Field static volatile Map  cachedZwaveData
@Field static volatile Long cachedZwaveAt
@Field static volatile Map  cachedZigbeeData
@Field static volatile Long cachedZigbeeAt
@Field static volatile Map  cachedAppsListData
@Field static volatile Long cachedAppsListAt
@Field static volatile Map  cachedDevicesListData
@Field static volatile Long cachedDevicesListAt
@Field static volatile List cachedCheckpoints
```

`updated()` clears `zwaveStackCache`, `fwUpdateCache/At`, `state.controllerTypeCache`, and `apiTimings` — but none of the five new ones. This breaks the established A3/A7/B5 invalidation pattern and the ARCHITECTURE.md "state requires an invalidation story" rule (Required Pattern #6).

**Symptom:** after a settings change, stale Performance/Apps/Devices reads persist for 60-120s. `cachedCheckpoints` has no TTL at all and persists until the next `saveCheckpoints` or `clearAllCheckpoints` call.

**Fix:** add to the existing block in `updated()`:

```groovy
cachedZwaveData = null;       cachedZwaveAt = null
cachedZigbeeData = null;      cachedZigbeeAt = null
cachedAppsListData = null;    cachedAppsListAt = null
cachedDevicesListData = null; cachedDevicesListAt = null
cachedCheckpoints = null
```

---

### 🟠 N2 — Caching strategy fragmented across the codebase

**Where:** `ZWAVE_DETAILS_PATH`, `ZIGBEE_DETAILS_PATH`, `APPS_LIST_PATH`, `DEVICES_LIST_PATH` are fetched at 11+ call sites (lines `:740, 742, 1507, 1521, 1543, 1576, 1704, 2526, 2699, 2857, 2858, 3012, 3016, 3031, 3261, 3262, 4124`). Only the four sites inside `getPerformanceData` consult the new TTL caches.

**Implication:** within a single window, two requests can see different views — one served from the 60-120s cache, the other freshly fetched. Consistency is intentional in places (audit enrichment wants live data) but the policy is now implicit.

**Architectural overlap:** the request-scoped `shared` Map (ARCHITECTURE.md Required Pattern #2) and the new session-scoped TTL caches now coexist with no documented decision rule for when to use which.

**Fix options (pick one):**
1. Document the boundary — these caches are explicitly Performance/checkpoint-only, other paths are by design uncached. Add a comment in `ARCHITECTURE.md`.
2. Extract a single `cachedFetch(name, ttlMs, closure)` helper backed by a `Map<String, [data, at]>` registry; thread *all* current cached-endpoint call sites through it.
3. Extend the request-scoped `shared` Map to cover these fields when a request would benefit.

---

### 🟡 N3 — TTL cache pattern duplicated 4× in `getPerformanceData`

**Where:** `HubDiagnostics.groovy:1497–1525` (zwave + zigbee), `:1539–1545` (apps), `:1572–1578` (devices).

Same shape repeated four times:

```groovy
long nowMs = now()
if (cachedX && cachedXAt && (nowMs - cachedXAt) < TTL_MS) {
    data = cachedX
} else {
    Map r = hubMapRequest(PATH, "label", timeout)
    data = r.ok ? r.data : null
    if (data) { cachedX = data; cachedXAt = nowMs }
}
```

**Fix:** small private helper resolves N2 and N3 together:

```groovy
private Object cachedFetch(String name, long ttlMs, Closure fetch) {
    long nowMs = now()
    Map slot = STATIC_CACHE_REGISTRY[name]
    if (slot?.data && (nowMs - slot.at) < ttlMs) return slot.data
    Object data = fetch()
    if (data) STATIC_CACHE_REGISTRY[name] = [data: data, at: nowMs]
    return data
}
```

---

### 🟡 N4 — `cachedCheckpoints` shares its list reference with callers

**Where:** `HubDiagnostics.groovy:3713–3731`.

```groovy
List loadCheckpoints() {
    if (cachedCheckpoints != null) return cachedCheckpoints  // <-- returns shared reference
    ...
}
void saveCheckpoints(List checkpoints) {
    ...
    writeFile(CHECKPOINTS_FILE, json)
    cachedCheckpoints = checkpoints   // <-- stores caller's reference
}
```

This is exactly the "in-place mutation may not persist" pitfall the ARCHITECTURE.md warns about, but applied to a memory cache. Today's callers don't mutate, but a future caller writing `loadCheckpoints() << newCp` would silently corrupt the cache and skip the file write entirely.

**Fix:** defensive copies on both sides:

```groovy
return new ArrayList<>(cachedCheckpoints)
// and:
cachedCheckpoints = new ArrayList<>(checkpoints)
```

---

### ⚪ N5 — Missing aggregator-rationale comments on two routes

**Where:** `HubDiagnostics.groovy:615` (`apiCode`) and `:677` (`apiPerformance`).

The mappings reorganization at `:275–325` added one-line rationale comments above `apiDashboard`, `apiDevices`, `apiApps`, `apiNetwork`, `apiHealth`, `apiHealthHistory`, `apiLive` — per ARCHITECTURE.md guidance. `apiCode` and `apiPerformance` were missed; both are aggregators (apiCode joins 5 hub endpoints; apiPerformance composes runtime stats with checkpoint-derived data).

**Fix:** add a one-line comment to each, mirroring the others.

---

### ⚪ N6 — `getUIVersion` cache placement disagrees with #4 fix description

**Where:** `HubDiagnostics.groovy:576–593` (impl), `:4435` (cache write), and the Phase R-4 Pack 1 description in this doc which says "cache `uiVer` in `state` after a successful sync".

Actual implementation uses `@Field static volatile String uiVersionCache`. Volatile fields are wiped on JVM reload; `state` survives. After a hub reboot, the first `serveUI` call still does a FileManager read to re-populate the cache.

**Fix:** either move to `state.cachedUIVersion` to match the doc, or update the doc to say "session-scoped cache; one FileManager read per JVM start." The latter is probably the right call — reading FileManager once per restart is fine, and `state` writes have their own cost.

---

### ⚪ N7 — `_perfInFlight` guard is tab-local

**Where:** `hub_diagnostics_ui.html:1648-1649`.

```javascript
let _perfInFlight=false;
async function rPerf(){if(_perfInFlight)return;_perfInFlight=true; ...}
```

Good defensive pattern but only applied to one tab. If a user double-clicks a tab fast or the auto-refresh races a manual click, multiple parallel `api(ep)` calls land for other tabs (Devices, Apps, Network, Health). The 120s `api()` cache helps but doesn't dedupe in-flight requests that started simultaneously (cache miss → both proceed to `fetch()`).

**Fix:** lift dedupe into `api()` itself with a per-endpoint in-flight `Promise` registry:

```javascript
const inFlight = {};
async function api(ep){
  if(WORKBENCH && MOCK_DATA[ep]) return MOCK_DATA[ep];
  if(REPORT&&REPORT[ep]) return REPORT[ep];
  const hit = cache[ep]; if(hit && Date.now()-hit.ts<CACHE_TTL_MS) return hit.d;
  if(inFlight[ep]) return inFlight[ep];
  inFlight[ep] = (async()=>{
    try {
      const r = await fetch(B+'/api/'+ep+(ep.includes('?')?'&':'?')+'access_token='+T);
      if(!r.ok) throw new Error('API '+r.status);
      const d = await r.json();
      cache[ep] = {d, ts:Date.now()};
      return d;
    } finally { delete inFlight[ep]; }
  })();
  return inFlight[ep];
}
```

One change covers all tabs and `_perfInFlight` becomes redundant.

---

### ⚪ N8 — Version drift in WIP

**Where:** `HubDiagnostics.groovy:17` (`APP_VERSION = "5.29.0"`) vs `hub_diagnostics_ui.html:135` (`UI_VERSION = "5.28.0"`).

Per the user's own memory rule "Version bump both files together." Bump UI to 5.29.0 before commit. Trivial.

---

## Round 7 detailed findings (Claude, 2026-05-10, hub→browser load-balancing)

### 🟡 LB1 — `buildCrossReference()` does heavy post-processing hub-side

**Where:** `HubDiagnostics.groovy` `buildCrossReference()` (~120 lines, called from `finalizeAudit()`).

Iterates all audited devices to compute `appsUsingCount`, `dashboards`, `unreferenced`, `meshOrphans`, `stuckJobs`, `criticalTop20`. Multiple O(n log n) sorts: `unreferenced` by timestamp, `meshOrphans`, `stuckJobs`, `criticalTop20` by total references, per-app device list, per-dashboard device list. Plus three mode computations across all devices for `spammyThreshold` / `maxStates` / `maxEvents`, then a tuned-device detection pass.

ARCHITECTURE.md gives the audit *collection* path a relaxed cost budget (per-device fan-out is unavoidable on the hub side). The Round 7 revision tightened this: the relaxation does *not* extend to post-processing. All of the sorts, indexes, modes, and cross-references are derivable from the raw per-device audit array the hub already produces.

**Fix sketch:** `apiAuditData` ships `allDevices` (already does) plus the source maps (apps subscribing per device, dashboards subscribing per device — already in the per-device records). SPA `buildAuditCrossReference(allDevices)` produces `unreferenced`, `meshOrphans`, `stuckJobs`, `criticalTop20`, `appsIndex`, `dashIndex`, and the tuned-device set on demand at audit-tab render time. Hub-side `buildCrossReference()` shrinks to a couple of lines that compute counts only.

Overlaps with C13 (Excessive complexity in `buildCrossReference`). Resolves part of C13.

---

### ⚪ LB2 — Top-N rankings & per-tab sorts done hub-side

**Where:** `analyzeNetwork` neighbor weak/stale lists (`HubDiagnostics.groovy:1448–1454`), `analyzeApps` type/name sorts, network distribution maps sorted by count, plus the audit-side top-N items (also covered by LB1).

The SPA `tbl()` helper at `hub_diagnostics_ui.html:703–785` already sorts on every column-header click. Pre-sorting on the hub is wasted work: the SPA re-sorts as soon as the user clicks anything. Per ARCHITECTURE.md regression rule "new sorting or top-N selection done in Groovy when the SPA already sorts/filters the same column via `tbl()`."

**Fix sketch:** ship arrays in arbitrary or natural-key order; remove the hub-side `.sort { }` chains. SPA's existing `tbl()` config (`sc:`, `sd:`) handles initial sort.

---

### ⚪ LB3 — `apiForumData()` runs 15 sequential fetches in one endpoint

**Where:** `HubDiagnostics.groovy` `apiForumData()` (currently around `:1205`).

Calls `getHubInfo`, `fetchSystemResources`, `fetchTemperature`, `fetchDatabaseSize`, `fetchStateCompression`, `fetchEventStateLimits`, `getAlertSignals`, `analyzeDevices(true)`, `analyzeApps(true)`, `analyzeNetwork`, plus mesh quality / ghost / Zigbee mesh / Z-Wave version / runtime stats. The deep `analyzeDevices(true)` / `analyzeApps(true)` are the heaviest individual costs; the rest are individually fine but stack up serially.

**Fix sketch (two options):**

- (a) Split into independent endpoints the SPA pulls in concurrent batches (mirroring the report-builder pattern from v5.32.0; reuse `BATCH=3` to stay under the connection pool — see `~/.claude/projects/-Users-trep-Documents-GitHub-iamtrep-hubitat/memory/project_hubdiag_spa_concurrency.md`).
- (b) Audit which fields the forum markdown actually uses; many of the deep-mode outputs probably aren't consumed. Stop calling deep variants when shallow data suffices.

(b) is smaller and safer; (a) is more aligned with the rest of the work.

---

### 🟡 LB4 — Z-Wave ghost recount lives inside the alert hot path

**Where:** `getAlertSignals()` (formerly `getStructuredAlerts()`) — current source.

When `state.cachedZwaveGhostCount` is older than 60s, the alert generator does a synchronous `hubMapRequest(ZWAVE_DETAILS_PATH, …, 8)` — up to 8 seconds blocking on `apiLive`/dashboard/health refresh. ARCHITECTURE.md flags `getStructuredAlerts()` as a hardest-change-zone. The fact that an 8-second blocking fetch is even possible there is the symptom; ghost-node detection should not own a slot on the alert hot path.

**Fix:** schedule a periodic Z-Wave-details fetch (every 5 min via `runIn`/`schedule`) that updates `state.cachedZwaveGhostCount`. `getAlertSignals()` only reads `state.cachedZwaveGhostCount`; never fetches synchronously. On a fresh install or after `updated()`, the value is null until the first scheduled run — the alert simply doesn't fire that one cycle, which is acceptable.

---

### ⚪ LB5 — `enrichDevices()` fan-out cap

**Where:** `enrichDevices()` (current source, around `:3145–3242`).

When `state.controllerTypeCache` is cold (fresh install, post-`updated()`, or large new device batch), every uncertain device triggers a synchronous `/device/fullJson/{id}` lookup in the same request thread. With 100+ uncertain devices this is 100+ synchronous HTTP calls before the dashboard renders.

Already partially mitigated by the cache. Defensive improvement: bound per-request enrichment to a small batch (e.g., `K=5`); leftover uncertain devices stay "unknown" and get enriched on subsequent loads as the cache warms.

Compounds C12 (audit `fullJson` heap pressure) but on a different code path; fix is independent.

---

## Prioritized fix plan

### Phase R-1 — Critical bug fixes (S, ~30 min) — ✅ shipped v5.13.1 (`c462801`)

Goal: ship the two real defects immediately. Both were one-line changes.

- [x] **#6 fix** — `getDevicesData()` line 1527: `childIds: deviceStats.childIds` (dropped `+ parentIds`)
- [x] **#7 fix** — `getAppsData()` line 1548: `disabled: it.disabled ?: false` (was `state == "disabled"`)
- [x] **Test guard:** value-correctness assertions added to `test-hub-diagnostics-api.sh` for both — verifies `childIds` size == `childDevices` count, `childIds ∩ parentIds == ∅`, and `/api/apps userApps[*]disabled` count matches `/hub2/appsList` ground truth
- [x] Bumped to v5.13.1; shipped via push + commit + mirror

**Verified:** 175/175 PASS on maison-pro after fixes. Note on #7's user-visible impact recorded in the detailed section above (the bug was in a code path the SPA doesn't currently consume; fix is preventive).

---

### Phase R-2 — Wire up the `shared` Map (M, ~1-2 hours) — ✅ shipped v5.14.0 (`fe7f819`)

Goal: fix #1 + A1 by completing the half-built request-scoped cache.

- [x] Added `private Map buildSharedCache(boolean includeNetwork = false)` — pre-fetches hubData, resources, temperature, databaseSize, hubAlerts; optional network/runtimeStats for heavier endpoints
- [x] Wired through `apiDashboard`, `apiHealth`, `apiNetwork` (minimal — just hubData since only `fetchSecurityInfo` consumes it there). `apiPerformance` not touched (no hubData consumer in its path).
- [x] Refactored `getHubInfo`, `fetchHubAlerts`, `fetchSecurityInfo` to accept optional `Map prefetchedHubData = null`. `fetchFirmwareUpdate` was excluded after re-verification — it hits `/hub/cloud/checkForUpdate`, not hubData.
- [x] No behavioral regression: 175/175 PASS on maison-pro after refactor.

**Trade-off note:** one timeout error observed post-deploy. Pre-R-2, two consumers each made independent fetch attempts; post-R-2 both share one. Downstream consumers still degrade gracefully — empty alerts, local fallback for hub.hardware, null cloudController. The deterministic single-attempt is the price of the call-count savings; resilience improvement (retry/backoff) is part of R-4 item #12 + A8.

---

### Phase R-3 — De-duplicate audit-report styling (M, ~1 hour) — ✅ Option A shipped v5.17.0 (`b163940`)

Picked Option A (Option A-Pragmatic, really): pull the audit's 38-line inline CSS out of `renderAuditHtml` into a single `@Field static final AUDIT_REPORT_CSS` constant near the other top-of-file `@Field`s, with a comment block flagging the SPA's `<style>` block as the parallel source of truth. Side-by-side diffability without breaking workbench / offline modes.

- [x] Audit CSS extracted to `AUDIT_REPORT_CSS` constant (38 lines)
- [x] `renderAuditHtml` shrunk from 358 → ~320 lines (also covers part of #2)
- [x] Audit report still byte-equivalent for selector content (verified key selectors round-trip)
- [x] 175/175 PASS

**Option B (extract sort/filter JS too) deliberately not taken.** Would need refactoring the audit's inline `<script>` block into a shared module; pays off only if the audit gains more interactive features. Documented as a future option in the constant's header comment.

**True single-source deduplication** (one file, both consumers) deferred — would need either (a) `serveUI` substitution that breaks workbench/offline modes, or (b) a build step (explicit non-goal). Maintenance discipline: when changing visual primitives, mirror between `AUDIT_REPORT_CSS` and the SPA `<style>` block. The constant's header comment documents this rule.

- [ ] Bump to v5.15.0 (minor)

**Verification:** generate audit on maison-pro, compare rendered HTML byte-for-byte against pre-refactor version (modulo the extracted block).

---

### Phase R-4 — Polish layer (M, batch-able)

Three batches.

**Pack 1 — resilience (v5.15.0, `a7d692c`):** ✅ shipped
- [x] **A8** — `hubRequest` retries once on SocketTimeoutException / ConnectException
- [x] **A2** — removed `saveSnapshotDiffPayload` (load fn was never called — entire feature dead)
- [x] **#4** — `getUIVersion` reads from `state.cachedUIVersion`; daily sync moved to scheduled job at 03:17

**Pack 2 — UI lifecycle (v5.16.0, `632404c`):** ✅ shipped
- [x] **#9** — `pollTimers` Map dedupes by scanId; stopPoll helper clears+removes on terminal states
- [x] **#10** — `ff:` column property on `tbl()`; Devices-tab Parent column wired so device-name search works

**Pack 3 — cleanup:**
- [x] **#12** — uniform `hubRequest` return shape `[ok, data, error]`; ~35 Map-json callers migrated to `hubMapRequest` (v5.22.0)

---

### Phase R-5 — Trivial cleanup (S) — ✅ shipped v5.18.0 (`d3c45c4`)

- [x] **A3** — invalidate `state.zwaveStackCache` (and `fwUpdateCache`) on `updated()`
- [x] **A4** — already slim from initial implementation (6-field result Map, not raw response); verified
- [-] **A5** — **deferred** — true fix needs the snapshot/checkpoint delete API to take a stable id (timestamp/filename) rather than an array index; that's API contract work, not housekeeping
- [x] **A7** — `apiTimings.clear()` on `updated()`
- [x] **A9** — `_schemaVersion: 1` added to `extractAuditFields` output for forward compat
- [x] **A10** — `tbl()` header click now re-renders `<tbody>` only; preserves filter focus + details state; faster on large tables

---

### Phase R-6 — Round-2 quick-wins pack (v5.19.0, `755bbaa`) ✅ shipped

- [x] **B3** — lifted `dt` and `stale` out of duplicate `rDevices` computations
- [x] **B4** — `tbl()` filter input debounced (150ms)
- [x] **B5** — `state.controllerTypeCache` cleared in `updated()` (matches R-5 A3/A7 pattern)
- [x] **G1** — `apiAuditStatus` reads AtomicInteger directly when scan in-flight; falls back to `state.audit` for completed scans

### Phase R-7 — Architectural plays — ✅ B2 shipped v5.20.0; B1 deferred with reasoning

- [x] **B2** — Server-side label maps in `/api/performance` (v5.20.0, `2581012`). Eliminated 2 cross-tab refetches; charts now render in 1 round trip instead of 3.
- [-] **B1** — **Deferred.** `state.controllerTypeCache` already mitigates: cache populates after first deep-mode call per device, persists across hub reboots (in `state`), only cleared by `updated()` (R-6 B5) or the explicit "Clear Enrichment Cache" button. So N+1 cost is paid:
  - First Devices/Performance/snapshot/forum-export run after install (one-time)
  - First run after settings save (rare; user-triggered)
  - Otherwise: cache hits, no `/device/fullJson` call
  
  The async refactor would require API-contract change (poll-based for `/api/devices`, `/api/performance`, snapshot creation, forum export — all currently synchronous endpoints). Complexity not justified for ~2× per-year cost. Revisit if a user reports slowness on a large hub with frequent settings saves.

### Phase R-8 — Round-4 pack (ordered by leverage)

Priority order:

- [ ] **C1** — Guard `createSnapshot()` result in `apiSnapshotDiff(newer=now)`; surface error if snapshot count didn't grow. (15 min)
- [ ] **C2** — Add `logsOff` auto-disable: `runIn(1800, 'logsOff')` in `updated()` when `debugLogging` is true. (10 min)
- [ ] **C3** — Cache `generateQuickSummary()` result in `state` for 60s OR cut to zero-HTTP (location fields only). (30 min)
- [ ] **C5** — Extract `buildThresholdAlerts()` to eliminate the alert-logic duplication between `getStructuredAlerts` and `analyzeSystemHealth`. (30 min)
- [ ] **C7** — Add `@CompileStatic` to pure-computation cluster: `parseUptime`, `formatMemory`, `formatDuration`, `isNewer`, `isVersionAtLeast`, `computeMode`, `parseHubitatTimestamp`, `parseZWaveVersion`. (15 min)
- [ ] **C6** — Type `readFile()` return as `Object`; replace `def` locals in `apiSnapshotDiff` (lines 989–996) and `buildZwaveGhostNodes` (line 3208). (15 min)
- [ ] **C8** — Add 60s state cache to `fetchHubMessages()` (matching ghost-node pattern). (15 min)
- [ ] **C9** — Validate multi-day cron behavior; switch to self-rescheduling `runIn` for `snapshotInterval > 1`. (30 min)
- [ ] **C4** — Thread pre-fetched appsList through `apiGenerateReport` shared map. (45 min, lower urgency — report generation is user-triggered and infrequent)
- [ ] **C10** — Route `autoEnableOAuth()` HTTP calls through `hubRequest`/`hubMapRequest` for retry-on-transient coverage. (20 min, lowest priority — first-install-only path)

### Phase R-9 — Round-6 pack (ordered by leverage)

Recommended order — first six are ~2-3h of work and clear one critical and three meaningful items:

- [ ] **N1** — Add the 5 new caches to the `updated()` clear list. (5 min)
- [ ] **C1** — Guard `createSnapshot()` result in `apiSnapshotDiff(newer=now)`. (15 min)
- [ ] **N4** — Defensive copy in `loadCheckpoints`/`saveCheckpoints`. (10 min)
- [ ] **C2** — Add `logsOff` auto-disable: `runIn(1800, 'logsOff')` in `updated()` when `debugLogging` is true. (10 min)
- [ ] **C5** — Extract `buildThresholdAlerts()` to deduplicate `getStructuredAlerts` ↔ `analyzeSystemHealth`. (30 min)
- [ ] **C3** — Cache `generateQuickSummary()` result in `state` for 60s OR cut to zero-HTTP. (30 min)
- [ ] **N5** — Add aggregator-rationale comments to `apiCode` and `apiPerformance`. (5 min)
- [ ] **N8** — Bump `UI_VERSION` to match `APP_VERSION` before commit. (1 min — gating any commit)
- [ ] **N7** — Lift in-flight dedupe into `api()` in the SPA; remove `_perfInFlight`. (15 min)
- [ ] **C7** — Add `@CompileStatic` to pure-computation cluster. (15 min)
- [ ] **C6** — Type `readFile()` return as `Object`; replace `def` locals. (15 min)
- [ ] **C8** — Add 60s state cache to `fetchHubMessages()`. (15 min)
- [ ] **N6** — Reconcile `getUIVersion` cache placement vs doc description (likely update doc). (10 min)
- [ ] **N2 + N3** — Design pass: pick one of the three N2 options and refactor the cache pattern. Resolves N3 if option 2 is picked. (1-2 h, design first)
- [ ] **C9** — Validate multi-day cron behavior; switch to self-rescheduling `runIn` for `snapshotInterval > 1`. (30 min)
- [ ] **C4** — Thread pre-fetched appsList through `apiGenerateReport` shared map. (45 min)
- [ ] **C10** — Route `autoEnableOAuth()` HTTP calls through `hubRequest`/`hubMapRequest`. (20 min)

Higher-effort/architectural items remain on their own track:

- **C11** (🟠) — fragile regex parsing of internal Hubitat HTML. No clean fix without an upstream contract.
- **C12** (🟠) — audit `fullJson` heap pressure. Real-world failure mode; needs concurrency-cap rethink.
- **C13** (🟡) — `buildForumMarkdown` (290 lines) and `buildCrossReference` complexity.
- **C15** (🟡) — `lastAuditResult` heap retention.
- **C14** (⚪) — `APP_VERSION`/`UI_VERSION` drift risk; partly addressed each release by N8 discipline.

### Phase R-10 — Hub→browser load-balancing remainders (Tier 2 + remaining Tier 3)

Tier 1 of the load-balancing audit shipped (see Round 7 summary). Remaining items are smaller per-item but still meaningful. Decision point recorded: revisit SPA-side test coverage strategy before starting this phase (currently option A, manual verification).

Quick wins (do in any order, each is self-contained):

- [ ] **N1 extension** — add `cachedSystemResources = null; cachedSystemResourcesAt = null` to the existing `updated()` cache-clear block. Pair with the original N1 fix. (1 min)
- [ ] **LB4** — move Z-Wave ghost detection out of `getAlertSignals()` into a 5-minute scheduled task; alerts read `state.cachedZwaveGhostCount` only. (45 min)
- [ ] **LB5** — cap per-request `enrichDevices()` fan-out to `K=5`. (15 min)

Larger items (each independently shippable, sequence per appetite):

- [ ] **LB1** — move `buildCrossReference()` post-processing to SPA `buildAuditCrossReference(allDevices)`. Largest remaining derivation move. Resolves part of C13. (~2 h, plus SPA rendering verification)
- [ ] **LB2** — strip hub-side sort/top-N chains; rely on `tbl()`. Distributed across multiple methods; safest done as a batch with one regression test pass. (1-2 h)
- [ ] **LB3** — pick (a) split forum data into independent endpoints with batched parallel fetch from SPA, or (b) audit which deep fields the forum markdown actually consumes and skip the rest hub-side. (b) is ~30 min; (a) is ~1-2 h.

Per the "diminishing returns" note in Round 7: each item here is meaningfully smaller than what's already shipped. Acceptable to leave any subset as deferred — the architecture is now correctly oriented and the worst hub-side hotspots are gone.

---

### Deferred (documented, not in any pack)

- **G2** — Audit pending-queue persistence for reboot-resume. Real edge case, low frequency. Pick up if/when reported.
- **G3** — User-configurable integration mappings. Feature request. Existing driver-side `updateDataValue("hubdiag:conn", ...)` covers driver-author case.
- **#5** — `finalizeAudit` serial enrichment. Documented trade-off; revisit if user hits Hubitat execution-time warnings on Z-Wave-rich hub.
- **Item 5 / Codex r2 #5** — true single-source CSS dedup. Discipline holding so far; switch to runtime SPA-CSS readout if found updating both blocks more than 2-3 times.

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

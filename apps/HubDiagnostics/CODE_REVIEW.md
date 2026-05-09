# HubDiagnostics — Code Review Findings & Fix Plan

Status as of 2026-05-08 (post-v5.18.0; round-2 review added).

Combined output of three independent passes:
- **Codex round 1** — external code-review pass (12 findings)
- **Internal** — verification + 10 additional findings (A1–A10)
- **Codex round 2** — external re-review after R-1..R-5 shipped (5 net-new items B1–B5; updates to prior items)
- **Gemini** — third-party review (3 net-new items G1–G3; the rest overlap)

All findings were verified against the code; verdicts are recorded below.

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
| 12 | `hubRequest` has 3 distinct return shapes for "did it work?" | 🟡 | Valid | [ ] |
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
| B1 | `analyzeDevices` deep-mode N+1: one sync `/device/fullJson` per uncertain device | 🟠 | Codex r2 — never addressed; only audit pipeline got async fan-out | [ ] |
| B2 | Performance-tab UI re-fetches `api('devices')` + `api('apps')` for chart labels | 🟡 | Codex r2 — extension of #1 across endpoint boundary | [ ] |
| B3 | `rDevices` computes `dt` (device-types sorted) and `stale` (filtered devices) twice each | ⚪ | **Fixed v5.19.0** | [x] |
| B4 | `tbl()` filter input fires on every keystroke; no debounce; full filter+sort+`<tbody>` rewrite per char | 🟡 | **Fixed v5.19.0** (150ms debounce) | [x] |
| B5 | `state.controllerTypeCache` grows unbounded; only cleared via explicit user action | ⚪ | **Fixed v5.19.0** (cleared in updated()) | [x] |
| G1 | `apiAuditStatus` reads from `state.audit` snapshot, not the underlying `AtomicInteger` — counter can lag by 1-7 during high-concurrency scans | 🟡 | **Fixed v5.19.0** (reads AtomicInteger when in-flight) | [x] |
| G2 | `AUDIT_SCANS` in-memory; lost on JVM reload or hub reboot mid-scan; no resume | n/a | **Won't fix** — volatile storage is the deliberate design choice; user-acknowledged trade-off | [x] |
| G3 | `INTEGRATION_TABLE` / `ALERT_DISPLAY_NAMES` hardcoded; no user-configurable mappings | ⚪ | Gemini — feature request, not defect; existing driver-side extension point covers most cases | [-] |

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
- [ ] **#12** — uniform `hubRequest` return shape `[ok, data, error]`; ~40 callers to migrate

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

### Phase R-7 — Architectural plays (M-L, separate releases)

Two genuine architectural items that warrant their own dedicated work:

- [ ] **B1** — `analyzeDevices` deep-mode N+1: refactor to async fan-out (mirror audit's CAS-bounded dispatch). M effort, real scaling win for large hubs.
- [ ] **B2** — Performance-tab cross-tab refetch: client-side `api()` cache layer with TTL, OR a small `/api/labels` endpoint returning just id→name maps for chart labels. S-M.

Independent releases — pick one at a time when ready.

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

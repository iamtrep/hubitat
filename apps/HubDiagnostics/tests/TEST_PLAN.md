<!--
Copyright (c) 2025-2026 PJ
SPDX-License-Identifier: MIT
-->

# HubDiagnostics — Test Coverage Plan

Status as of 2026-05-08:

| Phase | Title | Status |
|---|---|---|
| A | Refresh existing API test | ✅ shipped (`2b40427`) — 174/174 PASS on maison-pro |
| B | Audit-HTML structure validator | ⏳ planned |
| C | Snapshot capture + diff validator | ⏳ planned |
| D | SPA pure-JS unit tests | ⏳ planned |

---

## Phase B — Audit-HTML structure validator

**Goal:** End-to-end verification that the device usage audit produces a well-formed HTML report with every expected section present and structurally correct.

**Effort:** S-M (~1-2 hours)

**Deliverable:** `tests/test-audit-html.sh` — bash + embedded Python following the same pattern as `test-hub-diagnostics-api.sh`. Triggers an audit via `/api/audit/start`, polls until done, fetches the rendered HTML from FileManager, then parses and asserts on it.

**Coverage targets:**

1. **TOC entries** — verify all expected anchors are present (`#summary`, `#unref`, `#orphans`, `#stuck`, `#tuned`, `#critical`, `#rooms`, `#apps`, `#dashboards`, `#all`). Conditional entries (`#zwjs`, `#hubmesh`) only appear when their data is non-empty — assert presence iff data exists.

2. **Section anchors + tables** — for each `<details id="…">` section, verify the wrapping card structure and the `<table id="t_…">` with the right column headers in the right order:
   - `t_unref`: Device, Type, Last activity, Source
   - `t_stuck`: Device, Handler, Overdue, **Last run** (`prevRunTime` — added in v5.8.10), Status
   - `t_tuned`: Device, Spammy Threshold, Max States, Max Events
   - `t_rooms` (v5.11.0): Room, Devices, Members
   - `t_zwjs` (v5.11.0, JS hubs only): Device, Node, State, Status, Interview, RTT, RSSI, PER %, TX, RX, Last Seen
   - `t_hubmesh` (v5.11.0, populated only on consumer hubs): Device, Source Hub, Source Device ID, Status, Raw

3. **Driver-edit links** — assert that Type cells in the per-device table contain `/driver/editor/<id>` links for at least every device whose driver namespace ≠ `hubitat` (community drivers). Count should match the `userType=true` device count from `/api/devices`.

4. **Disabled-app strikethrough** — assert that `<s class="muted">` wraps disabled subscriber labels in the per-device and apps→devices tables. On hubs with no disabled apps, assert the markup absence (no false positives).

5. **Manually-tuned divergent-cell highlighting** — assert that cells diverging from the fleet mode have `class="warn"` or bold styling. Compare against a freshly-fetched `/api/devices` to know which devices to expect.

6. **Empty-state messages** — for each section that's data-driven, when its underlying count is 0, assert the muted "None — every device is …" / "No room data available." copy renders correctly. (This catches the "section card renders but is empty" failure mode.)

7. **Failed-fetch section** — when the `failed` count > 0, assert the `#failed` anchor + table render with the failed device IDs and reasons.

8. **Forum-export sanity** — fetch `/api/export/forum` and assert it's plain Markdown (no HTML tags), starts with the hub name header, and contains expected section markers (`### Devices`, `### Z-Wave`, `### Zigbee`, etc.).

**Cross-cutting:** validate the audit's `summary` Map matches the `criticalCount`, `unreferencedCount`, etc. visible in the file at `/api/audit/list` for the same scan.

---

## Phase C — Snapshot capture + diff validator

**Goal:** Verify the snapshot capture writes all expected fields and that `apiSnapshotDiff` correctly surfaces real changes while suppressing noise from old-schema snapshots.

**Effort:** M (~2 hours)

**Deliverable:** `tests/test-snapshot-diff.sh` — bash + embedded Python. Programmatic snapshot capture and diff with controlled scenarios.

**Automated coverage targets:**

1. **Capture completeness** — POST `/api/snapshot/create`, then GET `/api/snapshot/view?index=0` and assert every expected top-level field is present:
   - Pre-v5.13.0 baseline: `timestamp`, `hubInfo`, `devices`, `apps`, `network`, `storage`
   - v5.13.0+: `backups`, `security`, `ntpServer`, `loadThreshold`, `code`
   - `code.bundles[]`, `code.libraries[]`, `code.hubVariables[]` with their expected sub-shapes
   - `security.cloudController` is `"enabled"` or `"disabled"` (never null on a healthy hub)

2. **Hub-variable value omission** — already in Phase A but worth re-asserting from the snapshot side: `code.hubVariables[].value` is absent (privacy/diff-noise rule).

3. **Backwards-compat guards** — already partially in Phase A but worth a dedicated test: stash a fake "pre-v5.13.0 snapshot" (just the legacy fields) in the snapshots list, diff against a current snapshot, assert `backupsChanges`, `securityChanges`, `codeChanges` are all `null` and `networkChanges.ntpServer`/`loadThreshold` are absent. (Simulates upgrade-time diff noise that the `containsKey` guards prevent.)

4. **No-op diff** — take two snapshots back-to-back with no real changes; assert all diff sections are either absent or empty arrays; assert no false positives.

5. **App-disabled toggle** — only if a test target app is identified up front: programmatically disable an app via `/installedapp/disable?id=…&disable=true`, snapshot, re-enable, snapshot, diff → assert `appChanges.changed` lists the app with the toggle. (Guarded by env var; defaults off so unrelated runs don't mutate the hub.)

6. **Snapshot retention FIFO** — take N+1 snapshots where N = `maxSnapshots` setting; assert only the N newest survive.

**Manual scenarios** (documented in the script's comments, not automated):

7. **Bundle/library install or removal** — install a bundle via HPM → snapshot → uninstall → snapshot → diff should show added + removed entries in `codeChanges.bundles`. Same for libraries.

8. **Hub variable add/remove/type-change** — via the Hubitat Apps → Hub Variables UI: add a variable → snapshot → change its type → snapshot → delete it → snapshot. Diff should show added → typeChanged → removed entries in `codeChanges.hubVariables`.

9. **Security config changes** — toggle limited access on/off via the Hubitat Settings UI → snapshot → toggle DNS fallback → snapshot. Diff should show `securityChanges.limitedAccess` and `securityChanges.dnsFallback` entries.

10. **NTP / load threshold** — change via Hubitat advanced settings → snapshot → revert → snapshot. Diff should show the corresponding `networkChanges.ntpServer` / `networkChanges.loadThreshold` entries.

11. **Backup-related changes** — disable backups → wait a day → snapshot. Diff should show `backupsChanges.localCount` decrease.

---

## Phase D — SPA pure-JS unit tests

**Goal:** Cover the pure rendering / parsing / formatting helpers in `hub_diagnostics_ui.html` that have zero coverage today. Catch regressions in the SVG and table renderers without spinning up a browser-automation framework.

**Effort:** M (~2-3 hours)

**Deliverable:** `tests/spa/index.html` + `tests/spa/tests.js`. Open `index.html` in any browser; results display inline. No installation step.

**Approach:** the SPA is a single `.html` file. Strategy:
- Extract the script block from `hub_diagnostics_ui.html` into a sourceable form (or just `<script src="../../hub_diagnostics_ui.html">` — won't work because of the surrounding HTML; better to copy the script block into a separate file at build time, OR use a small build step that creates `tests/spa/spa.js` from the SPA HTML).
- Pragmatic alternative: each test file directly fetches the SPA HTML, extracts the `<script>` content via regex, and `eval`s it into the test page's scope.
- A tiny assert framework: `function assert(cond, msg)` + `function assertEq(a, b, msg)` writing PASS/FAIL `<li>`s into a results `<ul>`.

**Coverage targets:**

1. **Pure helpers** — `h()` (HTML escape), `fmem()` (memory formatter), `sev()` (severity color picker), `isNewer()` (semver comparator), `mc()` (metric chip builder), `ni()` (name-info row), `dlink()` / `alink()` (device/app link builders).

2. **`renderZigbeeWifiChart(zbCh, containerId, scan)`** — feed:
   - No scan data → assert SVG produced, channel markers at expected positions, no scan bars
   - Scan with 3 PANs at known channels/RSSI → assert exactly 3 `<rect>` scan bars with the right colors (red/orange/yellow) and heights proportional to RSSI
   - Scan with 2 PANs on the same channel → assert side-by-side stacking
   - Scan summary text below chart: assert "Strong on:", "Medium on:", "Weak on:", "No detections on Zigbee channels:" lines render with the right channel groupings

3. **`renderChart(pts, cid, range)`** (resource history) — feed mocked time-series; assert SVG width responds to container width; assert reference lines for warn/crit thresholds; assert legend renders.

4. **`renderHealthRes(r, d)` / `renderDashRes(r, d)`** — feed mocked resource + d objects; assert all expected chips render including v5.11.1+ Processors / Load Avg (1m) / Hub Load Threshold; assert color-coding triggers correctly for warn/crit thresholds.

5. **Snapshot diff renderer** — feed mocked diff payloads:
   - All v5.13 sections populated → assert each one's HTML rendering
   - All v5.13 sections null (pre-5.13 snapshot) → assert nothing renders for them (no empty headers or spurious markup)
   - Mixed (e.g. only `securityChanges` populated) → assert only that section renders

6. **Network-test button handlers** — extract the IPv4 validation regex from the inline button binding; feed valid + invalid inputs; assert valid passes, invalid is rejected client-side before any POST is attempted.

7. **Tab routing** — invoke the tab-switch logic with each tab id; assert the correct render function is called and the correct panel becomes active.

**Stretch goal:** wrap the same tests in a Node + jsdom runner so they can run headlessly in CI. The standalone HTML version is the quick win; the Node version is nice-to-have.

---

## Explicit non-goals

These are intentionally NOT in scope:

- **Full browser automation** (Selenium / Playwright / Cypress) — overkill for a hub-side single-user app. Maintenance cost > value.
- **Visual regression / screenshot diffs** — premature for the current maturity.
- **Performance benchmarking suite** — `/api/stats` already tracks per-endpoint median latency; that's enough until we see a real problem.
- **Mutation harness for hub-state changes from tests** — risky on a real hub; manual scenarios documented in Phase C instead.

## Recommended ordering

A → B → D → C.

Rationale:
- A (DONE) — mechanical refresh, immediately catches regressions on every push
- B — validates the most user-visible feature (the audit report) end-to-end
- D — fills the biggest current gap (SPA has zero test coverage); even basic helper tests catch a class of regressions invisible to API tests
- C — most of the snapshot logic is exercised transitively by Phase A; dedicated diff tests are nice-to-have but lower marginal value

Stop / re-evaluate after each phase. If a phase's value isn't holding up, pivot.

## Running the tests

```bash
# All API tests, fast (no Zigbee scan)
bash tests/test-hub-diagnostics-api.sh @maison-pro

# Same, with the slow Zigbee channel scan included (~30s extra)
RUN_SLOW_TESTS=1 bash tests/test-hub-diagnostics-api.sh @maison-pro

# Audit-HTML validator (Phase B, when shipped)
bash tests/test-audit-html.sh @maison-pro

# Snapshot diff validator (Phase C, when shipped)
bash tests/test-snapshot-diff.sh @maison-pro

# SPA unit tests (Phase D, when shipped) — open in any browser
open tests/spa/index.html

# Pure-JS derivation tests (extraction pattern — bound to the shipped SPA functions)
node tests/test-diffStats.js            # diffStats + zeroBaseline (startup-baseline synthesis)
node tests/test-radio-derivations.js    # topRadioTalkers (Performance top-N ranking)
node tests/test-network-derivations.js  # zwProblemNodes / zbWeakNeighbors / zbStaleNeighbors
node tests/test-forum-export.js         # assembleForumData (client-side forum-export data assembly)
node tests/test-forum-render.js         # full forum-export render (buildForumMarkdown end-to-end)
node tests/test-temp-scale.js           # temperature-scale helpers
```

> **Phase D progress:** the SPA derivations relocated from Groovy during the v5.57.0 refactor each
> landed with extraction-based pure-JS coverage (`test-radio-derivations.js`, `test-network-derivations.js`,
> and the `zeroBaseline` case in `test-diffStats.js`). The broader renderer coverage in Phase D's
> coverage-targets list (charts, tab routing, snapshot-diff renderer) is still outstanding.

All test scripts auto-discover the Hub Diagnostics installed-app instance via `/hub2/appsList`; pass an explicit instance ID as the second arg if discovery fails.

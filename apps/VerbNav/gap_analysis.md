<!--
Copyright (c) 2025-2026 PJ
SPDX-License-Identifier: MIT
-->

# VerbNav prototype — feature state & gap analysis

## Context

VerbNav is an old single-page UI prototype at `apps/VerbNav/verb_nav_prototype.html` (one ~70 KB / 1340-line file plus a `serve.py` proxy). Since it was last touched, the focus has shifted to Hub Diagnostics and several smaller apps. The five-verb information architecture (**Connect / Automate / Monitor / Maintain / Extend**) is still considered valid, but the prototype's surface needs to be re-baselined against what now exists in the repo so we can see what's still useful, what's been superseded, and which features are missing.

This document is the assessment. It does **not** propose code changes — it's an inventory + gap matrix to inform a follow-up plan.

---

## 1. Prototype state today

The prototype is more complete than memory suggested — close to feature-done across all five verbs, with no visible "coming soon" stubs. It's a pure-client SPA that talks to the hub directly and renders everything in the browser.

### Verb panels (file: `verb_nav_prototype.html`)

| Verb | Status | Endpoints | Notable features |
|---|---|---|---|
| **Connect** L493–618 | Built | `/hub2/devicesList`, `/hub/zigbeeDetails/json`, `/hub/zwaveDetails/json` | Classification by 11 radio types, activity-bucket filter (Active/Recent/Idle/Stale/Disabled), sortable table, per-device detail slide-in with cross-verb links |
| **Automate** L620–676 | Built | `/hub2/appsList`, `/hub2/userAppTypes` | System apps + user apps, per-type instance expansion, OAuth badge, modified date |
| **Monitor** L683–825 | Built | `/logsocket` (WebSocket), `/logs/eventsJson` | Live log stream w/ pause/clear/level filters/device filter, auto-reconnect (2–30 s backoff), 500-entry buffer, recent events table, live device-states grid grouped by room |
| **Maintain** L827–1095 | Built | `/hub2/hubData`, `/hub/advanced/freeOSMemory`, `/hub/cpuInfo`, `/hub/advanced/internalTempCelsius`, `/hub2/localBackups`, `/hub2/cloudBackups`, `/hub/advanced/freeOSMemoryHistory` | Health cards (memory + sparkline, CPU, temp, Zigbee, Z-Wave, network, backups), `hubData.alerts` surfacing, "Needs Attention" stale/disabled device list, 30 s auto-refresh |
| **Extend** L1097–1175 | Built | `/hub2/userDeviceTypes`, `/hub2/userAppTypes`, `/hub2/hubMeshJson` | Custom driver/app counts, Hub Mesh peer health, expand-rows linking to device/instance pages |

### Cross-cutting (already built)
- Sticky header with hub badges (free mem / CPU / temp), version banner when firmware ≠ `TESTED_VERSION = '2.5.0.107'` (L252).
- Cmd/Ctrl-K global search across devices, system apps, user apps, drivers (L1219–1286).
- 420 px right-side detail slide-in panel with cross-verb navigation links (L89–102, L565–612).
- Sortable table engine with inline filter and expandable rows (L454–490).
- Hub-IP modal persisted in `localStorage`; `detectHubBase()` uses relative URLs when served from `/local/` or a LAN IP (L255–262).

### Hosting / serving
- `serve.py` — local proxy + WebSocket relay for `/logsocket`. Forwards `/hub*`, `/device/*`, `/installedapp/*`, `/logs/*`, `/driver/*`, `/app/*`. Open proxy, single-hub, no auth pass-through.
- Alternative: drop the HTML in File Manager and load via `/local/verb_nav_prototype.html` (same-origin, no proxy). No OAuth, no version-sync.

---

## 2. What was built / hardened after VerbNav

| App | Verb overlap | Source of truth |
|---|---|---|
| **HubDiagnostics** | Maintain (heavy), Connect (mesh quality), Automate (app perf) | `apps/HubDiagnostics/HubDiagnostics.groovy` |
| **LogMonitor** | Monitor (rule-driven, not dashboard) | `apps/LogMonitor/` |
| **WellPumpMonitor** | Monitor + Maintain (purpose-built) | `apps/WellPumpMonitor/` |
| **Sensor Aggregator / Filter** | Connect / Extend | `apps/sensors/` |
| **Hub Admin Utilities** (In-Use Enumerator, Replacement Helper) | Maintain + Automate | `apps/utilities/` |
| **Hub Stress Tests** | Maintain | `apps/tests/` |
| **VisiblAir** | Connect / Monitor / Extend | `integrations/visiblair/` |

The largest overlap by far is **Hub Diagnostics ↔ VerbNav's Maintain tab** — both consume the hub-health endpoints, but Hub Diagnostics is now far deeper.

---

## 3. Gap analysis (per verb)

### Connect — small, mostly mesh-quality gaps
VerbNav already covers radio classification, room/driver/activity filtering, and detail drill-down. Gaps come from Hub Diagnostics' deeper mesh analysis.

- **Z-Wave node quality** — RSSI, PER, RTT, neighbours, route changes, ghost-node detection (4-signal: no device, FAILED, no route, unknown name), S0-on-non-security flag. `HubDiagnostics.groovy:1828–1920`, `:2372–2410`. VerbNav only shows count + region.
- **Zigbee neighbour table** — LQI distribution (avg/min/max), weak neighbours (LQI < 150), stale neighbours (age ≥ 7), child + route counts. `HubDiagnostics.groovy:1751–1806`, `:1917–2000`.
- **Matter status** — `HubDiagnostics.groovy:1408–1413`. VerbNav classifies devices as Matter but doesn't surface fabric/network status.
- **"In Use" cross-reference** — for any device, list every app that references it. Already implemented in `apps/utilities/` Device In-Use Enumerator. Useful in the device detail slide-in.

### Automate — perf stats and cross-ref are missing
VerbNav has app type + instance inventory. Hub Diagnostics adds runtime data; Hub Admin Utilities adds the inverse cross-reference.

- **App runtime stats** — execution time, busy %, exec count, avg time, state size, source badge. `HubDiagnostics.groovy:1349–1352` (`/api/performance`).
- **Parent/child hierarchy** — `HubDiagnostics.groovy:1359`. VerbNav lists instances flat.
- **Rule subscriptions / scheduled jobs** — RM 5.0 rules expose subscription / scheduled-job tables on `/installedapp/status/{id}` (or the JSON variant). Could feed a "rule health" sub-view. (The in-repo Rule Tracker that scraped this was removed; johnland's *Rule Logging and State Checker* — installed on every hub — covers the use case.)

### Monitor — strongest verb, minor gaps
VerbNav's live `/logsocket` is its standout feature; Hub Diagnostics doesn't have it (polling-only). Most Monitor capability in the rest of the repo is automation-shaped (LogMonitor sends actions on log lines, WellPumpMonitor watches a single device) and isn't a fit for a dashboard.

- **Per-device event rates** — Z-Wave/Zigbee message-count-per-second per node from `HubDiagnostics.groovy:949–950`, `:1455–1456`. Useful for spotting chatty devices.
- **Event source filter** — VerbNav already filters by device name; could add a source-type pill (DEVICE / APP / RULE / SYSTEM).
- **Aggregated sensor visibility** — Sensor Aggregator outputs (avg, motion fusion, std dev) are normal devices, so they already appear; no special handling needed.

### Maintain — biggest gap surface
VerbNav's Maintain is pretty but shallow. Hub Diagnostics covers ~10 things VerbNav doesn't.

- **Alert engine** — 25+ threshold-based alerts (memory, CPU, temp, radio crashes, backup failures, DB size, platform flags) with configurable thresholds. `HubDiagnostics.groovy` health/alerts paths; README L353–435. VerbNav only renders `hubData.alerts` verbatim.
- **Snapshot diff (config drift)** — `/api/snapshot/diff` (L221), persisted to File Manager, up to 50 retained. Tracks firmware, device add/remove, integration deltas, app toggles, network config, file manager changes.
- **Performance checkpoints** — baseline-vs-now delta on runtime stats. `/api/performance/compare` (L231).
- **Forum-ready Markdown export** with optional name obfuscation. `HubDiagnostics.groovy:918–1136`.
- **Diagnostic HTML report** — full snapshot + embedded JSON, written to File Manager. `:876–916`.
- **Java heap, DB size, file manager storage, state compression status** — VerbNav reports free OS memory and CPU only.
- **Stress tests** — async HTTP, file manager, UDP echo. `apps/tests/`.
- **Network DHCP/static + dual-NIC warning** — `HubDiagnostics.groovy:1364–1427`. VerbNav shows IPs but no warnings.
- **Scheduled snapshots/checkpoints** — auto-capture on interval. Requires a Groovy backend; client SPA can't do this alone.

### Extend — inventory only, no actions
VerbNav lists user drivers/apps and Hub Mesh peers. The repo has actionable "extend" workflows that aren't surfaced.

- **Device Replacement Helper** — three-step swap (select / preview / execute) with capability matching, per-app checkboxes, undo. `apps/utilities/`.
- **App-instance config drill-down** — VerbNav links out to the hub's config page; could embed instance settings via `/installedapp/configure/json/{id}` (already used elsewhere in the repo).
- **GitHub-sync awareness** — Hub Diagnostics auto-syncs its UI file from GitHub and version-checks on load. VerbNav has no equivalent and would silently drift.

---

## 4. Cross-cutting gaps

| Gap | What's missing | Reference |
|---|---|---|
| Auth | Open proxy / unauthenticated File Manager hosting. | Hub Diagnostics OAuth pattern at `apps/HubDiagnostics/HubDiagnostics.groovy:193`, `:3229–3260` |
| Persistence | No backend → no snapshots, no history beyond what the hub itself returns. Memory sparkline is the only history, and it comes from the hub. | Hub Diagnostics File-Manager persistence `:3089`, `:3108`, `:3121`, `:3132`, `:3168–3187` |
| Scheduling | Pure client; can't run periodic snapshots/checkpoints. | Hub Diagnostics scheduled jobs (README L325–341) |
| Version sync | No mechanism to detect a stale UI on the hub. | Hub Diagnostics UI auto-download `:3229–3260` |
| Multi-hub | Single hub IP in `localStorage`; the rest of the repo (per `MEMORY.md`) supports a 4-hub config. | `.hubitat.json`, `default_hub` |
| Caching | Every load re-fetches the full bundle in parallel. Fine for prototype, painful at scale. | n/a |
| Write actions | Read-only by design. Replacement Helper, app config edits, etc. would need writes. | n/a |

---

## 5. Headline takeaways

1. **The prototype is more complete than expected.** No verb is unimplemented; the README is accurate.
2. **Maintain is where the gap is biggest.** Hub Diagnostics has overtaken it on depth (alerts, snapshots, mesh quality, exports). The cleanest evolution is to back VerbNav by Hub Diagnostics' OAuth API rather than calling `/hub2/*` directly — most of the gap closes for free.
3. **Connect's mesh-quality gap is the second-biggest.** Same fix: pull from Hub Diagnostics' `/api/network` instead of the raw radio details endpoints.
4. **Monitor's `/logsocket` capability is unique to VerbNav** and worth preserving. Nothing else in the repo streams hub logs live.
5. **Extend needs actionable workflows** to be more than a directory listing. Device Replacement Helper is the first candidate to surface.
6. **Hosting/auth is the single largest architectural gap.** A serious version of this needs to live behind an OAuth-protected app instance, with the HTML file-managed and version-checked, the way Hub Diagnostics does it.

---

## 6. Suggested next moves

Order roughly by leverage:

1. Decide whether VerbNav becomes the **front-end to Hub Diagnostics** (consumes its OAuth API) or remains a **client-side direct-to-hub** prototype.
2. If front-end-to-HD: route Maintain + Connect-mesh through `/api/network`, `/api/health`, `/api/live`, `/api/snapshot/diff`. Keep the live `/logsocket` direct.
3. Add the "In Use" device cross-reference into the Connect detail panel using the Enumerator's logic.
4. Add app runtime stats (exec time, busy %) into the Automate panel.
5. Add a version-sync banner so a stale on-hub copy is obvious.

# Device Usage Audit — Design

**App:** Hub Diagnostics
**Date:** 2026-05-07
**Status:** Design approved, ready for implementation planning

## Context

Today, HubDiagnostics shows the device → parent app relationship (the integration that created a device) but cannot answer the broader question *"which apps and dashboards actually subscribe to a given device?"* That information lives only on `/device/fullJson/{id}`, one device at a time, under the keys `appsUsing[]`, `dashboards[]`, `parentApp`, plus a rich set of per-device diagnostic and configuration fields.

A 350-device hub × 1 fullJson call per device, throttled by Hubitat's platform-wide cap of **8 concurrent async HTTP calls per app instance**, makes this data unsuitable for an interactive view (~30–60 s scan time). It is, however, well suited to a one-time generated report — analogous to the existing HTML report and forum export — that captures usage relationships and per-device diagnostic state at a point in time, and persists to FileManager for later reference.

This design specifies the new feature: **Device Usage Audit**, a manually triggered scan that crawls every device, builds cross-reference indices, and writes a self-contained HTML report alongside existing reports.

## Goals

- Surface the complete **device → apps / dashboards** subscription map for the hub.
- Highlight three actionable categories at a glance: **unreferenced devices** (no apps, no dashboards, no parent app), **mesh orphans** (`device.orphan == true`), and **stuck scheduled jobs** (`nextRunTime` in the past).
- Surface a **critical-device ranking** (top 20 by total reference count) for risk-aware change planning.
- Persist each audit to FileManager as a standalone, self-contained HTML file the user can open, share, or archive.
- Reuse existing UI idioms (`alink()` / `dlink()` link styles, `b-builtin` / `b-community` badges, `--primary` / `--warn` / `--crit` palette, card shells) so the report feels native to HubDiagnostics.

## Non-goals (deferred)

- **Interactive cross-reference views** in existing tabs. The cost of fullJson per device rules this out at scale.
- **Driver preferences backup** (`settings[]`, `inputValues[]`) — Section D in the field reference. Future "device config snapshot" feature.
- **Reference data** (`capabilities[]`, `commands[]`, protocol blocks) — Section E.
- **Verbatim driver state** (`deviceState`, `currentStates`) — Section F. Driver-specific keys, not portable for cross-device aggregation.
- **Audit-to-audit deltas** (e.g., "+2 unreferenced since last audit"). Each audit stands alone.
- **`isOrphan` as a subscription proxy.** `isOrphan` / `device.orphan` is a mesh/radio state flag, not a subscription condition. The term **unreferenced** is reserved for "no apps + no dashboards + no parent app." See `apps/HubDiagnostics/device_fullJson.md` and the project memory entries on these semantics.

## User-facing experience

### Trigger

A new button **`Generate Device Audit`** in the existing Reports section of the **Dashboard** tab, alongside `Generate HTML Report` and `Generate Forum Export`. Sub-label notes typical scan duration (e.g., *"~ 47 s for 354 devices"*). Click initiates an asynchronous scan.

### Progress

Because a 30–60 s scan exceeds typical Hubitat endpoint timeouts, the trigger is **poll-based**:

1. Click → `POST /api/audit/start` returns `{ scanId, total }` immediately and kicks off the async crawl in the background.
2. The Reports section shows an in-progress row: `Audit in progress · scanning device 47 / 354 …` driven by polling `GET /api/audit/status?scanId=…` every 2 s.
3. On completion the row becomes a normal "View" link with summary stats; on failure it shows an error and a retry button.
4. Navigating away does not interrupt the scan; returning to the Reports section re-attaches to the in-flight scan via the persisted `scanId`.

### Report list

Past audits are listed in the Reports section like existing reports, each row showing: filename, generated timestamp, device count, scan duration, and small summary chips (`12 unreferenced · 3 orphans · 1 stuck job`). Clicking opens the HTML in a new tab. A delete action removes the file from FileManager and the list.

### Report contents

A single-page scrollable HTML, served from FileManager on the hub. Top-of-page table of contents with anchor links. Order:

1. **Header** — `Device Usage Audit — {hubName}` · generated timestamp · device count · scan duration. Hub name only (no IP, firmware, or model).
2. **Summary** — five inline metrics: Devices, Unreferenced, Mesh orphans, Stuck jobs, Critical (≥ 5 refs).
3. **Unreferenced devices** — sorted by oldest `lastActivityTime` first. Columns: Name, Type, Last activity, Source badge.
4. **Mesh orphans** — alphabetical by name.
5. **Stuck scheduled jobs** — sorted by most overdue first. Columns: Device, handler, overdue duration, status.
6. **Critical devices (top 20)** — sorted by `appsUsingCount + dashboards.length` descending. Columns: Device, Apps (named, linked), Dashboards (named, linked), Total.
7. **Apps → devices** (reverse index) — apps alphabetical; devices within each app alphabetical. Apps with zero subscribed devices listed at the bottom in a smaller subsection.
8. **Dashboards → devices** — same shape as Apps section.
9. **Per-device detail table** — all devices, inline. Columns: Name, Type, Source (Built-in / Community badge), Apps (named list with `+N more` after first 4), Dashboards (same), Parent app (linked), Last activity. Sortable client-side (default by name); filterable.

All app names render as `<a href="/installedapp/configure/{id}" target="_blank">…</a>`, all device names as `<a href="/device/edit/{id}" target="_blank">…</a>`, matching the `alink()` / `dlink()` idiom in `hub_diagnostics_ui.html` (lines 235–236). Source badges reuse the `b-builtin` / `b-community` styles; severity colors use `--warn` (#ff9800) for unreferenced and stuck, `--crit` (#d32f2f) for mesh orphans.

The HTML inlines a small CSS block at the top mirroring the app's design tokens (`--primary #1A77C9`, `--warn`, `--crit`, `b-builtin`, `b-community`, card shadow + 8 px radius). The report is fully self-contained — no external resources, no SPA template dependency.

## Architecture

```
[Dashboard › Reports] ── click ──▶ POST /api/audit/start
                                        │
                                        ├─ generates scanId
                                        ├─ creates AUDIT_SCANS[scanId] entry (in-memory, see "Throttle / concurrency" below)
                                        ├─ writes tiny state.audit snapshot { scanId, status:'scanning', processed:0, total, startedAt } for UI visibility
                                        └─ initial fan-out: 8.times { dispatchOne(scanId) }
                                                                                          │
                                              ┌───────────────────────────────────────────┘
                                              ▼
                          dispatchOne: CAS-reserve a slot (cap 8), pop next id, asynchttpGet
                                              │
                                              ▼
                          callback: extract Section A/B/C fields → AUDIT_SCANS[scanId].devices
                                    decrement inFlight, increment processed
                                    update state.audit snapshot (counters)
                                    if pending non-empty → dispatchOne (refill pipeline)
                                    if drained → finalizeAudit
                                              │
                                              ▼
                          finalizeAudit:
                            build cross-reference indices in local Groovy vars
                            render HTML with inlined CSS + data
                            writeFile('hub_usage_audit_YYYYMMDD_HHmmss.html')
                            append summary to state.auditReports[]
                            state.audit.status = 'done', state.audit.filename = ...
                            AUDIT_SCANS.remove(scanId)              // free heap

[Frontend] ── poll every 2 s ──▶ GET /api/audit/status?scanId
                                  ◀── { processed, total, status, filename? }   (reads state.audit snapshot)
```

The dispatch loop strictly maintains an in-flight pool of ≤ 8 — the limit is enforced by an `AtomicInteger.compareAndSet` predicate inside `dispatchOne` (see below), not by relying on the platform callback serialization. This makes the cap provably correct under any concurrency model, while still respecting the existing project memory note **`Hubitat async call limit`** (8 per app instance).

HubDiagnostics is already declared `singleInstance: true` (`HubDiagnostics.groovy` line 191), so the `@Field static` accumulator described next has no cross-instance contention concern.

## Backend design

### New endpoints

| Method | Path | Purpose |
|---|---|---|
| POST | `/api/audit/start` | Begin a new audit. Returns `{ scanId, total, alreadyRunning? }`. If a scan is already in progress, returns the in-flight `scanId` rather than starting a second. |
| GET | `/api/audit/status?scanId=…` | Returns `{ scanId, total, processed, status: 'scanning'\|'done'\|'error', filename?, error? }`. |
| GET | `/api/audit/list` | Returns the persisted `state.auditReports[]` (filename, generated, deviceCount, scanDuration, unreferencedCount, orphanCount, stuckJobCount, criticalCount). |
| POST | `/api/audit/delete` | Body `{ filename }`. Removes the file from FileManager and the entry from `state.auditReports[]`. |

### Data captured per device (from fullJson)

In line with the field reference at `apps/HubDiagnostics/device_fullJson.md`, sections **A + B + C only**:

- A — `appsUsing[]` (id, label, name, disabled), `appsUsingCount`, `dashboards[]` (id, name), `parentApp`
- B — `device.orphan`, `device.disabled`, `device.linkedAndDisabled`, `scheduledJobs[]` (handler, schedule, nextRunTime, prevRunTime, status), `device.spammyThreshold`, `device.maxStates`, `device.maxEvents`
- C — `device.deviceTypeName` / `Namespace` / `Id` / `ReadableType`, `device.driverType` (`usr` → Community badge, otherwise Built-in), `device.deviceTypeSingleThreaded`, `device.createTime`, `device.updateTime`, `device.parentDeviceId`, `childDevices` (key list only), `device.notes`, `device.tags`

### Persistence model

Three separate stores, each chosen for its workload:

| Store | What lives here | Why |
|---|---|---|
| **`@Field static ConcurrentHashMap AUDIT_SCANS`** (in-memory) | Per-scan state: `inFlight` (`AtomicInteger`), `processed` (`AtomicInteger`), `pending` (`ConcurrentLinkedQueue<Long>`), `devices` (`ConcurrentHashMap<Long, Map>`), `failed` (`ConcurrentHashMap<Long, String>`), `total`, `startedAt`. | True lock-free atomicity (CAS), no DB I/O per callback, no state-size pressure from the ~260 KB accumulator. Lost on hub restart / code push — that's fine; matches the spec's stale-detection behavior (any in-flight scan older than 10 min is force-cleared and the user re-triggers). HubDiagnostics is `singleInstance: true` so cross-instance contention is impossible. |
| **`state.audit`** (small snapshot) | `{ scanId, status, processed, total, startedAt, filename?, error? }` — counters and status only. | Visible on the Hubitat app status page for debugging. Drives the `GET /api/audit/status` response. Updated cheaply on each dispatch/callback (a handful of scalars). |
| **`state.auditReports[]`** | Index of completed audits: `[{ filename, generated, deviceCount, scanDuration, unreferencedCount, orphanCount, stuckJobCount, criticalCount }, …]`. | Persistent across restarts. Written once at finalize. Newest first. Trim to the most recent N (initial value 10, configurable later). |
| **FileManager** | `hub_usage_audit_YYYYMMDD_HHmmss.html` — the actual report. | Same pattern as `apiGenerateReport()` (line 911 of `HubDiagnostics.groovy`). |

### Throttle / concurrency design

The 8-call cap is enforced by an `AtomicInteger.compareAndSet` reservation in `dispatchOne` — not by relying on platform callback serialization. Pending IDs are popped from a `ConcurrentLinkedQueue.poll()` (atomic). Per-device extracted data is written to `ConcurrentHashMap` slots keyed by `deviceId` (last write wins on the rare duplicate-fetch case, which is fine because the value is deterministic per device).

```groovy
import groovy.transform.Field
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

@Field static final ConcurrentHashMap<String, ConcurrentHashMap> AUDIT_SCANS = new ConcurrentHashMap<>()
@Field static final int MAX_INFLIGHT = 8

private boolean dispatchOne(String scanId) {
    ConcurrentHashMap scan = AUDIT_SCANS[scanId]
    if (!scan) return false                              // stale or finalized
    AtomicInteger inFlight = scan.inFlight as AtomicInteger
    while (true) {                                       // CAS-reserve a slot
        int n = inFlight.get()
        if (n >= MAX_INFLIGHT) return false
        if (inFlight.compareAndSet(n, n + 1)) break
    }
    Long deviceId = (scan.pending as ConcurrentLinkedQueue).poll()
    if (deviceId == null) { inFlight.decrementAndGet(); return false }
    asynchttpGet('fullJsonCb',
        [uri: "${state.hubBase}/device/fullJson/${deviceId}"],
        [scanId: scanId, deviceId: deviceId])
    return true
}

void fullJsonCb(resp, data) {
    ConcurrentHashMap scan = AUDIT_SCANS[data.scanId]
    if (!scan) return                                    // stale callback, ignore
    if (resp?.status == 200) {
        scan.devices[data.deviceId] = extractAuditFields(resp.json, data.deviceId as Long)
    } else {
        scan.failed[data.deviceId] = "HTTP ${resp?.status}"
    }
    int processed = (scan.processed as AtomicInteger).incrementAndGet()
    int inFlight  = (scan.inFlight  as AtomicInteger).decrementAndGet()
    state.audit = (state.audit ?: [:]) + [processed: processed]   // snapshot for UI

    if (!(scan.pending as ConcurrentLinkedQueue).isEmpty()) {
        dispatchOne(data.scanId)
    } else if (inFlight == 0 && processed == (scan.total as Integer)) {
        finalizeAudit(data.scanId)
    }
}
```

**Ordering invariants enforced by this design:**

- `inFlight` is incremented (via CAS) **before** `asynchttpGet` is issued — so a callback that fires almost-immediately observes the correct count when it tries to dispatch the next.
- `inFlight` is decremented in the callback only — never in the dispatcher — so accounting stays consistent if `asynchttpGet` were ever to invoke the callback synchronously on failure.
- `processed` is incremented in the callback only.
- The slot is given back if `pending.poll()` returns null after a successful CAS — prevents deadlock if `pending` empties between the cap check and the pop.

**Error handling:** Per-device failures (non-2xx response, timeout) are recorded in `scan.failed` and the scan continues. At finalize, if `failed.size() / total > 0.10`, `state.audit.status` is set to `'error'` (with a `partial` filename pointing to whatever was written) instead of `'done'`. Otherwise `status: 'done'` and the report includes a small "Failed to fetch" footnote listing the affected devices.

**Watchdog:** `runIn(120, 'auditWatchdog', [data: [scanId: scanId]])` is scheduled at scan start. If it fires while the scan is still in-flight (`AUDIT_SCANS[scanId]` exists and not all callbacks returned), it marks the scan errored, frees the slot, and clears state — protecting against genuinely lost callbacks.

**Sandbox assumption:** `groovy.transform.Field`, `java.util.concurrent.ConcurrentHashMap`, `ConcurrentLinkedQueue`, and `AtomicInteger` are commonly used in Hubitat apps and drivers in the wild. The implementation plan should include an early smoke test confirming the imports compile in the Hubitat sandbox before any further work is built on top. If any are blocked, the fallback is `synchronized` blocks on a plain `@Field static Map`.

## UI integration

### Files modified

- `apps/HubDiagnostics/HubDiagnostics.groovy` — add the four endpoints, the crawl loop, the report rendering helper, the file naming/writing.
- `apps/HubDiagnostics/hub_diagnostics_ui.html` — add the `Generate Device Audit` button, the in-progress row rendering, the past-audits list, polling logic.
- `apps/HubDiagnostics/device_fullJson.md` — already created. No changes needed unless field scope shifts.

Functions/utilities to reuse:

- `writeFile()` — file persistence (existing, used by `apiGenerateReport`)
- `state.auditReports[]` mirrors the shape of `state.lastReportFile` tracking
- Frontend: `api()` cache pattern, `loading()` / `err()` panels, `tbl()` for the per-device table

### Versioning

Bump both `APP_VERSION` (Groovy) and `UI_VERSION` (HTML) in the same commit, per project convention.

## Edge cases

- **Empty hub** (0 devices). Show "No devices to audit" inline; no scan starts.
- **Scan already in progress.** `POST /api/audit/start` returns the existing `scanId` and `alreadyRunning: true`; UI re-attaches to the existing poll.
- **Device with empty `appsUsing` AND non-null `parentApp`.** Not unreferenced — listed normally.
- **Device with `appsUsingForDialogMore > 0`.** `appsUsing[]` may already be the full list; verify against `appsUsingCount` and refetch only if mismatched (defensive — at observed values of 13 vs 9, the `appsUsing` array contained the full list and `appsUsingForDialog` was a paginated UI variant).
- **Device with `appsUsingCount == 0`.** No app subscriptions even if dashboards or `parentApp` are present. Still flagged unreferenced only if all three are zero/null.
- **`deviceState` containing structured but driver-specific data.** Not included in the report (Section F is deferred).
- **Per-device fullJson failure.** Counted in `failedDevices`, listed in a dedicated "Failed to fetch" footnote section. Other devices proceed.
- **Hub reboot / app update during scan.** `state.audit` survives; on next app start, if `status == 'scanning'` and `startedAt > 10 min ago`, mark `status: 'error'` and clear the in-flight state so a fresh scan can start.

## Verification

Manual / end-to-end:

1. Trigger an audit on `maison-pro` (~ 128 devices). Confirm progress polls increment, scan completes, file appears in FileManager.
2. Open the generated HTML in a new tab. Verify all sections render, anchor links work, app/device links open the correct hub pages, badges match Built-in / Community styling.
3. Trigger a second audit; verify two entries in the Reports list, both openable.
4. Delete the older audit; verify file is removed from FileManager and the list updates.
5. Trigger an audit, then reload the dashboard tab mid-scan; verify the in-progress row re-appears and continues to update.
6. Confirm metrics in the report match expectations:
   - Pick a device known to have many subscribers → appears in Critical Devices with the right count
   - Pick a device known to have no automations → appears in Unreferenced
   - If any `device.orphan == true` exists, appears in Mesh orphans
7. Trigger an audit on `maison` (350+ devices, hub security). Confirm the 8-concurrent throttle does not exceed the platform limit (no `429` / `Too many requests` in `/logs`).

Code-level:

- Unit-test the unreferenced-detection predicate against the four boundary cases (empty/empty/null, present/empty/null, empty/present/null, empty/empty/non-null).
- Unit-test the criticality sort (ties broken by name asc).
- Smoke-test the report HTML in a clean browser tab (no inherited cookies from the SPA).

## Open questions deferred to implementation

- Polling interval (currently specified at 2 s) — verify this doesn't unduly burden the hub UI; may tune to 3 s based on observed CPU.
- Whether to allow user-triggered cancellation mid-scan. Likely yes (POST `/api/audit/cancel`), but not strictly required for v1.
- Sandbox-imports smoke test result — confirm `groovy.transform.Field`, `java.util.concurrent.ConcurrentHashMap`, `ConcurrentLinkedQueue`, `AtomicInteger` all compile in the Hubitat app sandbox before building atop them.

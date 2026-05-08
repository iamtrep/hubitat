# Device Usage Audit Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a manually triggered "Device Usage Audit" feature to Hub Diagnostics that crawls every device via `/device/fullJson/{id}`, builds device↔app/dashboard cross-reference indices, flags unreferenced devices / mesh orphans / stuck scheduled jobs, and persists a self-contained HTML report to FileManager — listed alongside existing reports on the Dashboard tab.

**Architecture:** Asynchronous server-side scan throttled to 8 concurrent calls via `AtomicInteger.compareAndSet` over a `@Field static ConcurrentHashMap` (per-scan in-memory state, lost on restart by design). Frontend polls `/api/audit/status` every 2 s during the scan. Final HTML is written via `writeFile()` (same path as the existing report generator) and indexed in `state.auditReports[]`.

**Tech Stack:** Hubitat Groovy (sandbox), `groovy.transform.Field`, `java.util.concurrent.{ConcurrentHashMap, ConcurrentLinkedQueue, atomic.AtomicInteger}`, vanilla JavaScript in the existing SPA, curl-based shell tests.

**Reference docs:**
- Spec: `docs/superpowers/specs/2026-05-07-device-usage-audit-design.md`
- Field reference: `apps/HubDiagnostics/device_fullJson.md`

**Target hub for development testing:** `maison-pro` (192.168.1.86, no security, ~128 devices). Scale validation against `maison` (350+ devices, hub security) deferred to Task 14.

**Project conventions (from project memory):**
- After every push to `iamtrep/hubitat`, run `/Users/trep/Documents/GitHub/hubitrep/hubitat/HubDiagnostics/update.sh`.
- Bump `APP_VERSION` (Groovy line 14) and `UI_VERSION` (HTML) together when the user asks for a publishable commit.
- Do NOT add Co-Authored-By trailers in commits.
- Do NOT `git add` untracked files without explicit permission — only stage what each task says to stage.
- Prefer the `/hubitat-push` slash command to push code; targets `maison-pro` by default for this work.

---

## File Structure

| File | Role | Modification scope |
|---|---|---|
| `apps/HubDiagnostics/HubDiagnostics.groovy` | Backend — adds: imports, constants, `AUDIT_SCANS` field, 7 audit helpers, 4 mappings, 4 endpoint methods | ~700 lines added across distinct sections |
| `apps/HubDiagnostics/hub_diagnostics_ui.html` | Frontend — adds: trigger button, status row, polling helper, past-audits list rendering in the Dashboard Reports section | ~150 lines added |
| `apps/HubDiagnostics/tests/test-audit-api.sh` | New — curl smoke test for the four new endpoints | ~80 lines, new file |
| `apps/HubDiagnostics/README.md` | Documentation — new "Device Usage Audit" section | Add a section, ~40 lines |

No new sub-directories. The audit code lives within the existing single Groovy file (Hubitat convention). Within that file, group all audit code into a single `// ===== DEVICE USAGE AUDIT =====` section placed after the existing snapshot/report code (around line 3160, before the `void writeFile` helper definitions).

---

## Task 1: Sandbox imports smoke test

The spec lists this as a prerequisite open question: confirm Hubitat's app sandbox compiles `groovy.transform.Field`, `java.util.concurrent.ConcurrentHashMap`, `java.util.concurrent.ConcurrentLinkedQueue`, and `java.util.concurrent.atomic.AtomicInteger`. We add minimal stub usage, push, confirm compile, then proceed.

**Files:**
- Modify: `apps/HubDiagnostics/HubDiagnostics.groovy:1-50` (add imports + a stub field; remove after verification)

- [ ] **Step 1: Add the four imports and a single stub field at the top of the file (just after existing imports)**

Find the existing `import` lines near the top of `HubDiagnostics.groovy` and add immediately after them:

```groovy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
```

(`groovy.transform.Field` is already imported in the file — verify with `grep -n "groovy.transform.Field" HubDiagnostics.groovy`. If absent, add it too.)

Then add a stub `@Field` field directly after `APP_VERSION` (line 14), purely to force the compiler to load the imports:

```groovy
@Field static final ConcurrentHashMap<String, ConcurrentHashMap> AUDIT_SCANS_PROBE = new ConcurrentHashMap<>()
@Field static final ConcurrentLinkedQueue<Long> AUDIT_PROBE_QUEUE = new ConcurrentLinkedQueue<>()
@Field static final AtomicInteger AUDIT_PROBE_COUNT = new AtomicInteger(0)
```

- [ ] **Step 2: Push to maison-pro**

```bash
cd /Users/trep/Documents/GitHub/iamtrep/hubitat
# Use the project's push slash command if available, otherwise:
curl -s -X POST "http://192.168.1.86/app/ajax/update" \
  --data-urlencode "id=$(jq -r '.hubs."maison-pro".app_ids."Hub Diagnostics"' .hubitat.json 2>/dev/null || echo $APP_ID)" \
  --data-urlencode "version=$(jq -r '.hubs."maison-pro".app_versions."Hub Diagnostics"' .hubitat.json 2>/dev/null || echo $APP_VERSION_NUM)" \
  --data-urlencode "source@apps/HubDiagnostics/HubDiagnostics.groovy"
```

Expected response: `{"id":...,"version":...,"status":"success"}`. If the response contains an error message about a blocked import, the sandbox does not allow that import — note which one and skip to the fallback in Step 5.

- [ ] **Step 3: Confirm app still loads in the hub UI**

Open `http://192.168.1.86/installedapp/list` in a browser and click "Hub Diagnostics". The app config page should render normally with no error banner.

- [ ] **Step 4: Remove the probe fields**

Delete the three `AUDIT_*_PROBE` field declarations from the top of the file. Keep the three new imports — they're needed for the real implementation in later tasks.

- [ ] **Step 5: Fallback if any import is blocked**

If Step 2 reported a sandbox rejection, the implementation must fall back to plain `@Field static Map` with `synchronized` blocks. Document the constraint in a comment at the top of the audit section in Task 2 and adapt later tasks accordingly. Do NOT proceed with the `ConcurrentHashMap`-based design if any required import is blocked.

- [ ] **Step 6: Commit**

```bash
git add apps/HubDiagnostics/HubDiagnostics.groovy
git commit -m "feat(hub-diag): add concurrent imports for upcoming device usage audit"
```

---

## Task 2: Add audit constants and `AUDIT_SCANS` field

Add the constants and the central in-memory store for in-flight scans. No behavior yet; just the storage primitives.

**Files:**
- Modify: `apps/HubDiagnostics/HubDiagnostics.groovy` — add constants near the top (after existing constants block ending around line 35), add the `AUDIT_SCANS` field

- [ ] **Step 1: Add audit constants in the constants block**

After line 35 (just after `ZWAVE_VERSION_PATH`), add:

```groovy
// ===== Device Usage Audit constants =====
@Field static final String FULL_JSON_PATH_PREFIX = "/device/fullJson/"
@Field static final int    AUDIT_MAX_INFLIGHT  = 8       // Hubitat platform cap on concurrent async HTTP per app
@Field static final int    AUDIT_WATCHDOG_SEC  = 120     // safety net if a callback is genuinely lost
@Field static final long   AUDIT_STALE_MS      = 600_000 // 10 min — anything older is force-cleared on app entry
@Field static final int    AUDIT_REPORTS_KEEP  = 10      // FIFO trim of state.auditReports[]
@Field static final double AUDIT_FAIL_RATIO    = 0.10    // > 10% per-device failures → mark scan errored

// Per-scan in-memory state. Each entry is itself a ConcurrentHashMap with keys:
//   total (Integer), startedAt (Long),
//   inFlight (AtomicInteger), processed (AtomicInteger),
//   pending (ConcurrentLinkedQueue<Long>),
//   devices (ConcurrentHashMap<Long, Map>),
//   failed (ConcurrentHashMap<Long, String>)
@Field static final ConcurrentHashMap<String, ConcurrentHashMap> AUDIT_SCANS = new ConcurrentHashMap<>()
```

- [ ] **Step 2: Push and confirm clean compile**

Push via the `/hubitat-push` slash command (or the curl pattern from Task 1 Step 2). Open the app config page; expect normal render.

- [ ] **Step 3: Commit**

```bash
git add apps/HubDiagnostics/HubDiagnostics.groovy
git commit -m "feat(hub-diag): add device usage audit constants and in-memory scan map"
```

---

## Task 3: Add `extractAuditFields()` helper

Pure function: takes a `/device/fullJson/{id}` response Map plus the device id, returns a slim Map containing only Section A/B/C fields per `device_fullJson.md`. No state mutation; deterministic; safe to unit-reason about.

**Files:**
- Modify: `apps/HubDiagnostics/HubDiagnostics.groovy` — add a new `// ===== DEVICE USAGE AUDIT =====` section just before line 3168 (the existing `void writeFile` helper)

- [ ] **Step 1: Add the audit section header and helper**

Just before the existing `void writeFile(String fileName, ...)` definition (line 3168), insert:

```groovy
// ===== DEVICE USAGE AUDIT =====

/**
 * Extract Section A/B/C fields from a /device/fullJson/{id} response.
 * Pure function; safe to call from async callbacks.
 *
 * @param fj   Parsed JSON response from /device/fullJson/{id}
 * @param did  The device id (passed separately because fj.device.id may be a Number type that needs casting)
 * @return     Slim Map with only the audit-scope fields, ready to accumulate
 */
private Map extractAuditFields(Map fj, Long did) {
    Map dev = (fj?.device ?: [:]) as Map

    // Section A — cross-reference core
    List appsUsing = ((fj?.appsUsing ?: []) as List).collect { Map a ->
        [id: (a.id as Long), label: a.label, name: a.name, disabled: a.disabled == true]
    }
    List dashboards = ((fj?.dashboards ?: []) as List).collect { Map d ->
        [id: (d.id as Long), name: d.name]
    }
    Map parentApp = (fj?.parentApp instanceof Map)
        ? [id: (fj.parentApp.id as Long), label: fj.parentApp.label, name: fj.parentApp.name]
        : null

    // Section B — diagnostic flags
    List scheduledJobs = ((fj?.scheduledJobs ?: []) as List).collect { Map s ->
        [handler: s.handler, schedule: s.schedule, nextRunTime: s.nextRunTime,
         prevRunTime: s.prevRunTime, status: s.status]
    }

    // Section C — identity & driver attribution
    return [
        id:                  did,
        name:                dev.name,
        label:               dev.label,
        displayName:         dev.displayName,
        deviceTypeName:      dev.deviceTypeName,
        deviceTypeNamespace: dev.deviceTypeNamespace,
        deviceTypeId:        (dev.deviceTypeId as Long),
        readableType:        dev.deviceTypeReadableType,
        driverType:          dev.driverType,                 // 'usr' or system
        singleThreaded:      dev.deviceTypeSingleThreaded == true,
        createTime:          dev.createTime,
        updateTime:          dev.updateTime,
        lastActivityTime:    dev.lastActivityTime,
        parentDeviceId:      (dev.parentDeviceId as Long),
        childDeviceIds:      ((fj?.childDevices ?: [:]) as Map).keySet()?.collect { it as Long } ?: [],
        notes:               dev.notes,
        tags:                dev.tags,

        // Section B
        orphan:              dev.orphan == true,
        disabled:            dev.disabled == true,
        linkedAndDisabled:   dev.linkedAndDisabled == true,
        spammyThreshold:     (dev.spammyThreshold as Integer),
        maxStates:           (dev.maxStates as Integer),
        maxEvents:           (dev.maxEvents as Integer),
        scheduledJobs:       scheduledJobs,

        // Section A
        appsUsing:           appsUsing,
        appsUsingCount:      (fj?.appsUsingCount as Integer) ?: appsUsing.size(),
        dashboards:          dashboards,
        parentApp:           parentApp
    ]
}
```

- [ ] **Step 2: Push and verify compile**

Push via `/hubitat-push`. Confirm app loads.

- [ ] **Step 3: Smoke-test the extractor with one real device**

Add a temporary debug endpoint to validate the extraction shape. After `extractAuditFields`, add:

```groovy
// TEMP — remove after Task 3 verification
Map apiAuditDebug() {
    Long did = (params.id ?: '120') as Long
    Map fj = (Map) hubRequest("${FULL_JSON_PATH_PREFIX}${did}", "device fullJson", "json", 15)
    return jsonResponse([extracted: extractAuditFields(fj, did)])
}
```

And register the temp mapping in the `mappings { ... }` block (line 206):

```groovy
path('/api/audit/debug')   { action: [GET: 'apiAuditDebug'] }
```

- [ ] **Step 4: Push, then run the smoke test**

Push via `/hubitat-push`. Then:

```bash
ACCESS_TOKEN=$(curl -s "http://192.168.1.86/installedapp/configure/json/$(jq -r '.hubs."maison-pro".app_ids."Hub Diagnostics"' /Users/trep/Documents/GitHub/iamtrep/hubitat/.hubitat.json)" | jq -r '.app.settings.accessToken.value // empty')
APP_ID=$(jq -r '.hubs."maison-pro".app_ids."Hub Diagnostics"' /Users/trep/Documents/GitHub/iamtrep/hubitat/.hubitat.json)
curl -s "http://192.168.1.86/apps/api/${APP_ID}/api/audit/debug?id=120&access_token=${ACCESS_TOKEN}" | jq .
```

Expected: a JSON object with the extracted fields. Validate that `appsUsingCount`, `dashboards`, `appsUsing`, and `parentApp` are populated correctly for device 120 (the Aqara FP300).

If the access-token discovery doesn't work, instead navigate to the app's UI URL once in a browser; the `?access_token=...` parameter will be embedded.

- [ ] **Step 5: Remove the temp debug endpoint and mapping**

Delete `apiAuditDebug()` and the `path('/api/audit/debug')` mapping line.

- [ ] **Step 6: Push and commit**

```bash
git add apps/HubDiagnostics/HubDiagnostics.groovy
git commit -m "feat(hub-diag): add extractAuditFields helper for device usage audit"
```

---

## Task 4: Add `buildCrossReference()` helper

Pure function: takes the accumulated `devices` map (`Long deviceId → Map extracted`) and produces all the report indices: unreferenced list, mesh orphans, stuck jobs, critical devices ranking, apps→devices, dashboards→devices.

**Files:**
- Modify: `apps/HubDiagnostics/HubDiagnostics.groovy` — add helper just after `extractAuditFields` (in the same `// ===== DEVICE USAGE AUDIT =====` section)

- [ ] **Step 1: Add the helper**

After `extractAuditFields(...)`, insert:

```groovy
/**
 * Build all cross-reference indices for the audit report from the accumulated device map.
 * Pure function. Returns a Map shaped for direct rendering by renderAuditHtml().
 */
private Map buildCrossReference(Map devices, long scanStartedMs) {
    long nowMs = now()

    List unreferenced = []          // [{id, name, type, lastActivityTime, driverType}, ...]
    List meshOrphans  = []          // [{id, name}, ...]
    List stuckJobs    = []          // [{id, name, handler, overdueMs, status}, ...]
    List allRefs      = []          // for ranking — [{id, name, appsCount, dashboardsCount, total}, ...]
    Map  appsIndex    = [:]         // Long appId → [label, devices: [[id, name], ...]]
    Map  dashIndex    = [:]         // Long dashId → [name, devices: [[id, name], ...]]

    devices.each { _did, _d ->
        Long   did   = _did as Long
        Map    d     = _d  as Map
        int    apps  = ((d.appsUsing ?: []) as List).size()
        int    dashs = ((d.dashboards ?: []) as List).size()
        boolean noParentApp = (d.parentApp == null)

        // Unreferenced
        if (apps == 0 && dashs == 0 && noParentApp) {
            unreferenced << [id: did, name: (d.label ?: d.name), type: d.deviceTypeName,
                             lastActivityTime: d.lastActivityTime, driverType: d.driverType]
        }
        // Mesh orphans
        if (d.orphan) {
            meshOrphans << [id: did, name: (d.label ?: d.name)]
        }
        // Stuck jobs (nextRunTime in the past)
        ((d.scheduledJobs ?: []) as List).each { Map s ->
            String nrt = s.nextRunTime as String
            if (nrt) {
                Long when = parseHubitatTimestamp(nrt)
                if (when != null && when < nowMs) {
                    stuckJobs << [id: did, name: (d.label ?: d.name),
                                  handler: s.handler, overdueMs: (nowMs - when), status: s.status]
                }
            }
        }
        // Reference ranking
        allRefs << [id: did, name: (d.label ?: d.name),
                    appsCount: apps, dashboardsCount: dashs, total: apps + dashs]
        // Apps → devices index
        ((d.appsUsing ?: []) as List).each { Map a ->
            Long aid = a.id as Long
            Map entry = (Map) (appsIndex[aid] ?: [label: (a.label ?: a.name), devices: []])
            (entry.devices as List) << [id: did, name: (d.label ?: d.name)]
            appsIndex[aid] = entry
        }
        // Dashboards → devices index
        ((d.dashboards ?: []) as List).each { Map dd ->
            Long ddid = dd.id as Long
            Map entry = (Map) (dashIndex[ddid] ?: [name: dd.name, devices: []])
            (entry.devices as List) << [id: did, name: (d.label ?: d.name)]
            dashIndex[ddid] = entry
        }
    }

    // Sort sections per spec
    unreferenced.sort { a, b -> (parseHubitatTimestamp(a.lastActivityTime as String) ?: 0L) <=> (parseHubitatTimestamp(b.lastActivityTime as String) ?: 0L) }
    meshOrphans.sort  { a, b -> (a.name as String) <=> (b.name as String) }
    stuckJobs.sort    { a, b -> (b.overdueMs as Long) <=> (a.overdueMs as Long) }
    List criticalTop20 = allRefs.sort { a, b ->
        int t = (b.total as Integer) <=> (a.total as Integer)
        t != 0 ? t : (a.name as String) <=> (b.name as String)
    }.take(20)

    // Apps/dashboards alphabetical by label/name; devices within each alphabetical
    List appsSorted = appsIndex.collect { id, e -> [id: id, label: e.label, devices: (e.devices as List).sort { x, y -> (x.name as String) <=> (y.name as String) }] }
        .sort { a, b -> (a.label as String) <=> (b.label as String) }
    List dashSorted = dashIndex.collect { id, e -> [id: id, name: e.name, devices: (e.devices as List).sort { x, y -> (x.name as String) <=> (y.name as String) }] }
        .sort { a, b -> (a.name as String) <=> (b.name as String) }

    return [
        deviceCount:       devices.size(),
        unreferenced:      unreferenced,
        meshOrphans:       meshOrphans,
        stuckJobs:         stuckJobs,
        criticalTop20:     criticalTop20,
        criticalThreshold: 5,                                 // for the "Critical (≥5 refs)" summary card
        criticalCount:     allRefs.count { (it.total as Integer) >= 5 },
        appsToDevices:     appsSorted,
        dashboardsToDevices: dashSorted,
        scanStartedMs:     scanStartedMs,
        scanDurationMs:    (nowMs - scanStartedMs)
    ]
}

/**
 * Parse a Hubitat ISO-8601 timestamp ("2026-05-08T00:27:27+0000") to epoch millis.
 * Returns null on parse failure (don't fail the report — just skip the value).
 */
private Long parseHubitatTimestamp(String s) {
    if (!s) return null
    try {
        return Date.parse("yyyy-MM-dd'T'HH:mm:ssZ", s).time
    } catch (Exception e) {
        return null
    }
}
```

- [ ] **Step 2: Push and verify compile**

Push via `/hubitat-push`. Confirm app loads.

- [ ] **Step 3: Commit**

```bash
git add apps/HubDiagnostics/HubDiagnostics.groovy
git commit -m "feat(hub-diag): add buildCrossReference helper and timestamp parser"
```

---

## Task 5: Add `renderAuditHtml()` helper

Pure function: takes the cross-reference Map plus hub name and returns the standalone HTML report string. Inlines a small CSS block mirroring the app's design tokens.

**Files:**
- Modify: `apps/HubDiagnostics/HubDiagnostics.groovy` — add helper just after `parseHubitatTimestamp` in the same audit section

- [ ] **Step 1: Add the renderer**

After `parseHubitatTimestamp(...)`, insert:

```groovy
/**
 * Render the audit report as a fully self-contained HTML document.
 * No external resources; CSS inlined; uses /device/edit/{id} and /installedapp/configure/{id}
 * relative URLs that work when the file is served from FileManager on the same hub.
 */
private String renderAuditHtml(Map xref, String hubName, String generatedAt, List failed) {
    StringBuilder b = new StringBuilder()
    b << "<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"UTF-8\">"
    b << "<title>Device Usage Audit — ${esc(hubName)}</title>"
    b << "<style>"
    b << ":root{--primary:#1A77C9;--ok:#388e3c;--warn:#ff9800;--crit:#d32f2f;--bg:#f5f5f5;--card:#fff;--border:#ddd;--text:#333;--muted:#777;--alt:#f9f9f9}"
    b << "*{box-sizing:border-box}body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;background:var(--bg);color:var(--text);font-size:13px;margin:0;padding:14px;line-height:1.4}"
    b << "a{color:var(--primary);text-decoration:none}a:hover{text-decoration:underline}"
    b << ".hdr{background:var(--primary);color:#fff;padding:10px 14px;border-radius:8px 8px 0 0;display:flex;align-items:center;gap:12px;flex-wrap:wrap}"
    b << ".hdr h1{margin:0;font-size:16px;font-weight:600}.hdr .meta{font-size:11px;opacity:.85;margin-left:auto}"
    b << ".toc{background:var(--card);border-radius:0 0 8px 8px;padding:14px;margin-bottom:14px;box-shadow:0 1px 3px rgba(0,0,0,.08)}"
    b << ".toc-l{color:var(--muted);font-size:11px;text-transform:uppercase;font-weight:600;margin-bottom:6px}"
    b << ".toc-grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(220px,1fr));gap:4px;font-size:12px}"
    b << ".card{background:var(--card);border-radius:8px;margin-bottom:14px;box-shadow:0 1px 3px rgba(0,0,0,.08);overflow:hidden}"
    b << ".card-h{padding:10px 14px;font-size:13px;font-weight:600;border-bottom:1px solid var(--border);background:var(--alt)}"
    b << ".card-b{padding:10px 14px}"
    b << ".sumrow{display:flex;gap:18px;flex-wrap:wrap}.sumcell{}"
    b << ".sumcell .l{color:var(--muted);font-size:11px;margin-bottom:2px}.sumcell .v{font-size:18px;font-weight:600}"
    b << "table{width:100%;font-size:12px;border-collapse:collapse}"
    b << "th{color:var(--muted);text-align:left;font-weight:500;padding:5px;border-bottom:1px solid var(--border)}"
    b << "td{padding:5px;border-bottom:1px solid #f1f5f9;vertical-align:top}"
    b << ".badge{display:inline-block;padding:1px 7px;border-radius:10px;font-size:11px;font-weight:600}"
    b << ".b-builtin{background:#e8eaf6;color:#3949ab}.b-community{background:#fce4ec;color:#c62828}"
    b << ".b-warn{background:#fff3e0;color:var(--warn)}.b-crit{background:#ffebee;color:var(--crit)}"
    b << ".muted{color:var(--muted)}.warn{color:var(--warn)}.crit{color:var(--crit)}"
    b << "</style></head><body>"

    // Header
    b << "<div class=\"hdr\"><h1>Device Usage Audit — ${esc(hubName)}</h1>"
    b << "<div class=\"meta\">Generated ${esc(generatedAt)} · ${xref.deviceCount} devices · scan ${formatDurationSec(xref.scanDurationMs as Long)}</div></div>"

    // TOC
    b << "<div class=\"toc\"><div class=\"toc-l\">Contents</div><div class=\"toc-grid\">"
    b << "<div><a href=\"#summary\">Summary</a></div>"
    b << "<div><a href=\"#unref\">Unreferenced devices (${(xref.unreferenced as List).size()})</a></div>"
    b << "<div><a href=\"#orphans\">Mesh orphans (${(xref.meshOrphans as List).size()})</a></div>"
    b << "<div><a href=\"#stuck\">Stuck scheduled jobs (${(xref.stuckJobs as List).size()})</a></div>"
    b << "<div><a href=\"#critical\">Critical devices (top 20)</a></div>"
    b << "<div><a href=\"#apps\">Apps → devices</a></div>"
    b << "<div><a href=\"#dashboards\">Dashboards → devices</a></div>"
    b << "<div><a href=\"#all\">Per-device detail table</a></div>"
    if (failed) b << "<div><a href=\"#failed\" class=\"crit\">Failed to fetch (${failed.size()})</a></div>"
    b << "</div></div>"

    // Summary
    b << "<div class=\"card\" id=\"summary\"><div class=\"card-b\"><div class=\"sumrow\">"
    b << sumcell("Devices",     xref.deviceCount as String, null)
    b << sumcell("Unreferenced", (xref.unreferenced as List).size() as String, "warn")
    b << sumcell("Mesh orphans", (xref.meshOrphans as List).size() as String, "crit")
    b << sumcell("Stuck jobs",   (xref.stuckJobs as List).size() as String, "warn")
    b << sumcell("Critical (≥${xref.criticalThreshold} refs)", xref.criticalCount as String, null)
    b << "</div></div></div>"

    // Unreferenced
    b << "<div class=\"card\" id=\"unref\"><div class=\"card-h\">⚠ Unreferenced devices (${(xref.unreferenced as List).size()})</div><div class=\"card-b\">"
    if ((xref.unreferenced as List).isEmpty()) {
        b << "<div class=\"muted\">None — every device is used by at least one app, dashboard, or parent integration.</div>"
    } else {
        b << "<div class=\"muted\" style=\"margin-bottom:6px\">No apps, no dashboards, no parent app — sorted by oldest last activity first.</div>"
        b << "<table><tr><th>Device</th><th>Type</th><th>Last activity</th><th>Source</th></tr>"
        (xref.unreferenced as List).each { Map u ->
            b << "<tr><td>${dlink(u.id as Long, u.name as String)}</td>"
            b << "<td>${esc(u.type as String)}</td>"
            b << "<td>${esc(u.lastActivityTime as String)}</td>"
            b << "<td>${driverBadge(u.driverType as String)}</td></tr>"
        }
        b << "</table>"
    }
    b << "</div></div>"

    // Mesh orphans
    b << "<div class=\"card\" id=\"orphans\"><div class=\"card-h\">⚠ Mesh orphans (${(xref.meshOrphans as List).size()})</div><div class=\"card-b\">"
    if ((xref.meshOrphans as List).isEmpty()) {
        b << "<div class=\"muted\">None — no devices report orphan radio state.</div>"
    } else {
        b << "<div class=\"muted\" style=\"margin-bottom:6px\">Hubitat reports <code>orphan: true</code> — physical radio relationship lost.</div>"
        b << "<div>" + (xref.meshOrphans as List).collect { dlink(it.id as Long, it.name as String) }.join(" · ") + "</div>"
    }
    b << "</div></div>"

    // Stuck jobs
    b << "<div class=\"card\" id=\"stuck\"><div class=\"card-h\">⚠ Stuck scheduled jobs (${(xref.stuckJobs as List).size()})</div><div class=\"card-b\">"
    if ((xref.stuckJobs as List).isEmpty()) {
        b << "<div class=\"muted\">None — all scheduled jobs have a future or null nextRunTime.</div>"
    } else {
        b << "<table><tr><th>Device</th><th>Handler</th><th>Overdue</th><th>Status</th></tr>"
        (xref.stuckJobs as List).each { Map s ->
            b << "<tr><td>${dlink(s.id as Long, s.name as String)}</td>"
            b << "<td><code>${esc(s.handler as String)}</code></td>"
            b << "<td>${formatDurationSec(s.overdueMs as Long)}</td>"
            b << "<td>${esc(s.status as String)}</td></tr>"
        }
        b << "</table>"
    }
    b << "</div></div>"

    // Critical devices
    b << "<div class=\"card\" id=\"critical\"><div class=\"card-h\">⭐ Critical devices (top 20 by reference count)</div><div class=\"card-b\">"
    if ((xref.criticalTop20 as List).isEmpty()) {
        b << "<div class=\"muted\">No devices have any apps or dashboards.</div>"
    } else {
        b << "<table><tr><th>Device</th><th>Apps</th><th>Dashboards</th><th>Total</th></tr>"
        (xref.criticalTop20 as List).each { Map c ->
            b << "<tr><td>${dlink(c.id as Long, c.name as String)}</td>"
            b << "<td>${c.appsCount}</td><td>${c.dashboardsCount}</td>"
            b << "<td><b>${c.total}</b></td></tr>"
        }
        b << "</table>"
    }
    b << "</div></div>"

    // Apps → devices
    b << "<div class=\"card\" id=\"apps\"><div class=\"card-h\">📱 Apps → devices</div><div class=\"card-b\">"
    (xref.appsToDevices as List).each { Map a ->
        b << "<div style=\"margin-bottom:6px\">"
        b << alink(a.id as Long, a.label as String) + " <span class=\"muted\">(${(a.devices as List).size()})</span>"
        b << "<div style=\"padding-left:12px\">"
        b << (a.devices as List).collect { dlink(it.id as Long, it.name as String) }.join(", ")
        b << "</div></div>"
    }
    b << "</div></div>"

    // Dashboards → devices
    b << "<div class=\"card\" id=\"dashboards\"><div class=\"card-h\">📊 Dashboards → devices</div><div class=\"card-b\">"
    (xref.dashboardsToDevices as List).each { Map d ->
        b << "<div style=\"margin-bottom:6px\">"
        b << "<b>" + esc(d.name as String) + "</b> <span class=\"muted\">(${(d.devices as List).size()})</span>"
        b << "<div style=\"padding-left:12px\">"
        b << (d.devices as List).collect { dlink(it.id as Long, it.name as String) }.join(", ")
        b << "</div></div>"
    }
    b << "</div></div>"

    // Per-device detail table
    b << "<div class=\"card\" id=\"all\"><div class=\"card-h\">📋 Per-device detail table (${xref.deviceCount})</div><div class=\"card-b\">"
    b << "<table><tr><th>Name</th><th>Type</th><th>Source</th><th>Apps</th><th>Dashboards</th><th>Parent app</th><th>Last activity</th></tr>"
    // Per-device rows are built from the same data, but renderAuditHtml is given the xref,
    // so we attach the original devices map onto the xref in finalizeAudit. Defensive: skip if missing.
    if (xref.allDevices instanceof Map) {
        ((xref.allDevices as Map).values() as List).sort { a, b -> (a.name as String) <=> (b.name as String) }.each { Map d ->
            String src = (d.driverType == 'usr') ? "<span class=\"badge b-community\">Community</span>" : "<span class=\"badge b-builtin\">Built-in</span>"
            List apps = (d.appsUsing ?: []) as List
            List dashs = (d.dashboards ?: []) as List
            String appsCell  = apps  ? apps.take(4).collect { alink(it.id as Long, (it.label ?: it.name) as String) }.join(", ") + (apps.size() > 4 ? ", <span class=\"muted\">+${apps.size() - 4}</span>" : "") : ((dashs.isEmpty() && d.parentApp == null) ? "<span class=\"warn\">⚠ unreferenced</span>" : "<span class=\"muted\">—</span>")
            String dashsCell = dashs ? dashs.take(4).collect { '<a href="/installedapp/configure/' + (it.id as Long) + '" target="_blank">' + esc(it.name as String) + '</a>' }.join(", ") : "<span class=\"muted\">—</span>"
            Map pa = d.parentApp as Map
            String paCell = pa ? alink(pa.id as Long, (pa.label ?: pa.name) as String) : "<span class=\"muted\">—</span>"
            b << "<tr><td>${dlink(d.id as Long, (d.label ?: d.name) as String)}</td>"
            b << "<td>${esc(d.deviceTypeName as String)}</td>"
            b << "<td>${src}</td>"
            b << "<td>${appsCell}</td>"
            b << "<td>${dashsCell}</td>"
            b << "<td>${paCell}</td>"
            b << "<td>${esc(d.lastActivityTime as String)}</td></tr>"
        }
    }
    b << "</table></div></div>"

    // Failed footnote
    if (failed) {
        b << "<div class=\"card\" id=\"failed\"><div class=\"card-h crit\">Failed to fetch (${failed.size()})</div><div class=\"card-b muted\">"
        b << failed.collect { Map f -> "Device ${f.id}: ${esc(f.reason as String)}" }.join("<br>")
        b << "</div></div>"
    }

    b << "</body></html>"
    return b.toString()
}

// ----- small render helpers -----

private String esc(String s) {
    if (s == null) return ""
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
private String dlink(Long id, String name) {
    return "<a href=\"/device/edit/${id}\" target=\"_blank\">${esc(name ?: ("Device " + id))}</a>"
}
private String alink(Long id, String label) {
    return "<a href=\"/installedapp/configure/${id}\" target=\"_blank\">${esc(label ?: ("App " + id))}</a>"
}
private String sumcell(String label, String value, String severity) {
    String cls = severity ? " ${severity}" : ""
    return "<div class=\"sumcell\"><div class=\"l\">${esc(label)}</div><div class=\"v${cls}\">${esc(value)}</div></div>"
}
private String driverBadge(String dt) {
    return (dt == 'usr')
        ? "<span class=\"badge b-community\">Community</span>"
        : "<span class=\"badge b-builtin\">Built-in</span>"
}
private String formatDurationSec(Long ms) {
    if (ms == null) return ""
    long sec = (ms as long) / 1000
    if (sec < 60) return "${sec}s"
    long m = sec / 60; long s = sec % 60
    return "${m}m ${s}s"
}
```

- [ ] **Step 2: Push and verify compile**

Push via `/hubitat-push`. Confirm app loads.

- [ ] **Step 3: Commit**

```bash
git add apps/HubDiagnostics/HubDiagnostics.groovy
git commit -m "feat(hub-diag): add renderAuditHtml and small render helpers"
```

---

## Task 6: Add `dispatchOne()` and `fullJsonCb()`

The throttled async dispatch loop with the CAS-bounded 8-cap. `finalizeAudit` is stubbed to log only — Task 7 fleshes it out.

**Files:**
- Modify: `apps/HubDiagnostics/HubDiagnostics.groovy` — add to the audit section after `formatDurationSec`

- [ ] **Step 1: Add the dispatcher and callback**

After `formatDurationSec`, insert:

```groovy
/**
 * CAS-bounded dispatch: reserves a slot in the in-flight pool (≤ AUDIT_MAX_INFLIGHT),
 * pops the next pending device id atomically, and issues an async fullJson fetch.
 * Returns false if the cap is reached, the queue is empty, or the scan no longer exists.
 */
private boolean dispatchOne(String scanId) {
    ConcurrentHashMap scan = AUDIT_SCANS[scanId]
    if (scan == null) return false                                  // stale or finalized

    AtomicInteger inFlight = scan.inFlight as AtomicInteger
    while (true) {                                                  // CAS-reserve a slot
        int n = inFlight.get()
        if (n >= AUDIT_MAX_INFLIGHT) return false
        if (inFlight.compareAndSet(n, n + 1)) break
    }

    Long deviceId = (scan.pending as ConcurrentLinkedQueue).poll()
    if (deviceId == null) {                                         // queue drained between cap check and pop
        inFlight.decrementAndGet()
        return false
    }

    Map params = [
        uri: "${HUB_BASE}${FULL_JSON_PATH_PREFIX}${deviceId}",
        contentType: "application/json",
        timeout: 15
    ]
    asynchttpGet('fullJsonCb', params, [scanId: scanId, deviceId: deviceId])
    return true
}

/**
 * Async callback for /device/fullJson/{id}. Extracts audit fields, decrements inFlight,
 * dispatches the next pending id (refilling the pipeline), or finalizes the scan.
 */
void fullJsonCb(resp, data) {
    String scanId = data.scanId as String
    ConcurrentHashMap scan = AUDIT_SCANS[scanId]
    if (scan == null) return                                        // callback from prior abandoned scan

    Long deviceId = data.deviceId as Long
    try {
        if (resp?.status == 200) {
            Map fj = (Map) resp.json
            (scan.devices as ConcurrentHashMap)[deviceId] = extractAuditFields(fj, deviceId)
        } else {
            (scan.failed as ConcurrentHashMap)[deviceId] = "HTTP ${resp?.status ?: 'n/a'}"
        }
    } catch (Exception e) {
        (scan.failed as ConcurrentHashMap)[deviceId] = "${getObjectClassName(e)}: ${e.message}"
    }

    int processed = (scan.processed as AtomicInteger).incrementAndGet()
    int inFlight  = (scan.inFlight  as AtomicInteger).decrementAndGet()
    Integer total = scan.total as Integer

    // Update small state snapshot for UI polling — cheap (just scalars)
    Map snap = (state.audit ?: [:]) as Map
    if (snap.scanId == scanId) {
        snap.processed = processed
        state.audit = snap
    }

    if (!(scan.pending as ConcurrentLinkedQueue).isEmpty()) {
        dispatchOne(scanId)                                         // keep pipeline full
    } else if (inFlight == 0 && processed >= total) {
        finalizeAudit(scanId)
    }
}

/**
 * Stubbed in this task; fleshed out in Task 7. Logs counts and clears the scan from memory
 * so we can verify the dispatch loop drains correctly before adding rendering.
 */
private void finalizeAudit(String scanId) {
    ConcurrentHashMap scan = AUDIT_SCANS[scanId]
    if (scan == null) return
    int total     = scan.total as Integer
    int processed = (scan.processed as AtomicInteger).get()
    int succeeded = (scan.devices as Map).size()
    int failed    = (scan.failed as Map).size()
    logInfo "[audit ${scanId}] STUB finalize — total=${total} processed=${processed} ok=${succeeded} fail=${failed}"

    Map snap = (state.audit ?: [:]) as Map
    if (snap.scanId == scanId) {
        snap.status = (failed / (double) total > AUDIT_FAIL_RATIO) ? 'error' : 'done'
        snap.processed = processed
        state.audit = snap
    }
    AUDIT_SCANS.remove(scanId)
}
```

- [ ] **Step 2: Push and verify compile**

Push via `/hubitat-push`. Confirm app loads.

- [ ] **Step 3: Commit**

```bash
git add apps/HubDiagnostics/HubDiagnostics.groovy
git commit -m "feat(hub-diag): add CAS-bounded async dispatch loop for usage audit"
```

---

## Task 7: Add real `finalizeAudit()` and `auditWatchdog()`

Replace the stub with the real finalize: build the cross-reference, render HTML, write to FileManager, append to `state.auditReports[]`. Add the watchdog `runIn` safety net.

**Files:**
- Modify: `apps/HubDiagnostics/HubDiagnostics.groovy` — replace the stub `finalizeAudit` from Task 6 and add `auditWatchdog`

- [ ] **Step 1: Replace the stub `finalizeAudit` with the full implementation**

Find the `private void finalizeAudit(String scanId)` from Task 6 and replace its body entirely:

```groovy
private void finalizeAudit(String scanId) {
    ConcurrentHashMap scan = AUDIT_SCANS[scanId]
    if (scan == null) return
    long startedAt = scan.startedAt as Long
    int total      = scan.total as Integer
    Map devices    = (scan.devices as ConcurrentHashMap) as Map
    Map failedMap  = (scan.failed  as ConcurrentHashMap) as Map
    int succeeded  = devices.size()
    int failed     = failedMap.size()

    boolean errored = (failed / (double) Math.max(total, 1)) > AUDIT_FAIL_RATIO

    // Build cross-reference & attach raw devices for the per-device table
    Map xref = buildCrossReference(devices, startedAt)
    xref.allDevices = devices

    // Render HTML
    String hubName = getHubInfo()?.name ?: "Hubitat"
    String generatedAt = new Date().format("yyyy-MM-dd HH:mm 'UTC'", TimeZone.getTimeZone("UTC"))
    List failedList = failedMap.collect { id, reason -> [id: id, reason: reason] }
    String html = renderAuditHtml(xref, hubName, generatedAt, failedList)

    // Persist to FileManager
    String filename = "hub_usage_audit_${new Date().format('yyyyMMdd_HHmmss')}.html"
    writeFile(filename, html)

    // Append to past-audits index (newest first, FIFO trim)
    Map summary = [
        filename:           filename,
        generated:          generatedAt,
        deviceCount:        total,
        scanDurationMs:     (now() - startedAt),
        unreferencedCount:  (xref.unreferenced as List).size(),
        orphanCount:        (xref.meshOrphans as List).size(),
        stuckJobCount:      (xref.stuckJobs as List).size(),
        criticalCount:      xref.criticalCount,
        failedCount:        failed,
        errored:            errored
    ]
    List reports = (state.auditReports ?: []) as List
    reports.add(0, summary)
    while (reports.size() > AUDIT_REPORTS_KEEP) {
        Map evicted = reports.remove(reports.size() - 1) as Map
        deleteFile(evicted.filename as String)
    }
    state.auditReports = reports

    // Snapshot for UI
    state.audit = [
        scanId:    scanId,
        status:    errored ? 'error' : 'done',
        processed: (scan.processed as AtomicInteger).get(),
        total:     total,
        startedAt: startedAt,
        filename:  filename
    ]

    AUDIT_SCANS.remove(scanId)
    logInfo "[audit ${scanId}] finalized — ${succeeded}/${total} devices, ${failed} failed, ${(now()-startedAt)}ms, file=${filename}"
}

/**
 * Watchdog: runIn(AUDIT_WATCHDOG_SEC, 'auditWatchdog') is scheduled at scan start.
 * If the scan is still in-flight when this fires, mark errored and clean up.
 */
void auditWatchdog(data) {
    String scanId = data?.scanId as String ?: ((state.audit as Map)?.scanId as String)
    if (!scanId) return
    ConcurrentHashMap scan = AUDIT_SCANS[scanId]
    if (scan == null) return                                        // already finalized — nothing to do
    int processed = (scan.processed as AtomicInteger).get()
    int total     = scan.total as Integer
    logWarn "[audit ${scanId}] watchdog fired — ${processed}/${total} done, marking errored"
    state.audit = [
        scanId: scanId, status: 'error', processed: processed, total: total,
        startedAt: scan.startedAt, error: "Watchdog: scan exceeded ${AUDIT_WATCHDOG_SEC}s"
    ]
    AUDIT_SCANS.remove(scanId)
}
```

- [ ] **Step 2: Push and verify compile**

Push via `/hubitat-push`. Confirm app loads.

- [ ] **Step 3: Commit**

```bash
git add apps/HubDiagnostics/HubDiagnostics.groovy
git commit -m "feat(hub-diag): real finalizeAudit + watchdog for usage audit"
```

---

## Task 8: Add `/api/audit/start` endpoint and mappings registration

Wire the four new endpoints into the `mappings { }` block and implement `/api/audit/start`. The other three are added in Task 9.

**Files:**
- Modify: `apps/HubDiagnostics/HubDiagnostics.groovy` — `mappings { }` block (line 206) + new endpoint methods in the audit section

- [ ] **Step 1: Register all four mappings (do this once, in this task)**

In the `mappings { }` block — after the existing `path('/api/cache/clear') { ... }` line (line 238) — append:

```groovy
    // Device Usage Audit
    path('/api/audit/start')   { action: [POST: 'apiAuditStart'] }
    path('/api/audit/status')  { action: [GET:  'apiAuditStatus'] }
    path('/api/audit/list')    { action: [GET:  'apiAuditList'] }
    path('/api/audit/delete')  { action: [POST: 'apiAuditDelete'] }
```

Note: Tasks 8 and 9 both add endpoint methods. The mappings stay correct as long as both tasks complete; the unimplemented endpoints between Task 8 push and Task 9 push will return Hubitat's default 500 — that's acceptable for the brief window.

- [ ] **Step 2: Implement `apiAuditStart`**

In the audit section, after `auditWatchdog`, add:

```groovy
/**
 * POST /api/audit/start — begin a new device usage audit.
 * Idempotent under concurrent triggers: if a scan is already in-flight, returns its scanId.
 */
Map apiAuditStart() {
    // Force-clear stale scan (>10 min in 'scanning' state) on entry
    Map prev = (state.audit ?: [:]) as Map
    if (prev.status == 'scanning' && prev.startedAt && (now() - (prev.startedAt as Long) > AUDIT_STALE_MS)) {
        logWarn "[audit] clearing stale scan ${prev.scanId} (started ${(now() - (prev.startedAt as Long))/1000}s ago)"
        AUDIT_SCANS.remove(prev.scanId as String)
        state.audit = [:]
    }

    // If a scan is already in-flight, return it
    if (state.audit?.status == 'scanning' && AUDIT_SCANS[state.audit.scanId]) {
        return jsonResponse([scanId: state.audit.scanId, total: state.audit.total, alreadyRunning: true])
    }

    // Build pending queue from /hub2/devicesList
    Map bulk = (Map) hubRequest(DEVICES_LIST_PATH, "devices list", "json", 30)
    if (!bulk || bulk.error) {
        return jsonResponse([error: "Failed to fetch device list", detail: bulk?.message])
    }
    List devs = flattenDeviceList((bulk.devices ?: []) as List)
    List<Long> ids = devs.collect { ((it.data ?: it) as Map).id as Long }.findAll { it != null }
    if (ids.isEmpty()) {
        return jsonResponse([error: "No devices to audit"])
    }

    // New scan — create the in-memory entry
    String scanId = "audit-${now()}-${(int)(Math.random() * 9999)}"
    ConcurrentHashMap scan = new ConcurrentHashMap()
    scan.total      = ids.size()
    scan.startedAt  = now()
    scan.inFlight   = new AtomicInteger(0)
    scan.processed  = new AtomicInteger(0)
    scan.pending    = new ConcurrentLinkedQueue<Long>(ids)
    scan.devices    = new ConcurrentHashMap<Long, Map>()
    scan.failed     = new ConcurrentHashMap<Long, String>()
    AUDIT_SCANS[scanId] = scan

    state.audit = [
        scanId: scanId, status: 'scanning', processed: 0, total: ids.size(),
        startedAt: scan.startedAt
    ]

    // Schedule the watchdog
    runIn(AUDIT_WATCHDOG_SEC, 'auditWatchdog', [data: [scanId: scanId]])

    // Initial fan-out — each call self-bounds at 8
    AUDIT_MAX_INFLIGHT.times { dispatchOne(scanId) }

    logInfo "[audit ${scanId}] started — ${ids.size()} devices to scan"
    return jsonResponse([scanId: scanId, total: ids.size(), alreadyRunning: false])
}

/**
 * Flatten the parent/child structure returned by /hub2/devicesList into a flat list of device entries.
 */
private List flattenDeviceList(List items) {
    List out = []
    items.each {
        out << it
        ((it.children ?: []) as List).each { ch -> out << ch }
    }
    return out
}
```

- [ ] **Step 3: Push and verify compile**

Push via `/hubitat-push`. Confirm app loads.

- [ ] **Step 4: Smoke-test the start endpoint**

Get the access token from the UI (open the dashboard once in a browser; copy from the URL) and run:

```bash
APP_ID=$(jq -r '.hubs."maison-pro".app_ids."Hub Diagnostics"' /Users/trep/Documents/GitHub/iamtrep/hubitat/.hubitat.json)
ACCESS_TOKEN=<paste from UI URL>

curl -s -X POST "http://192.168.1.86/apps/api/${APP_ID}/api/audit/start?access_token=${ACCESS_TOKEN}" | jq .
```

Expected: `{"scanId":"audit-<timestamp>-<rand>","total":<deviceCount>,"alreadyRunning":false}` returned in well under 1 s.

Tail the logs to verify the scan progressed and finalize logged:

```bash
# Open http://192.168.1.86/logs in a browser (filter by "audit") — should see:
# [audit audit-...-...] started — 128 devices to scan
# [audit audit-...-...] finalized — 128/128 devices, 0 failed, ~XXXXms, file=hub_usage_audit_...html
```

Confirm the file appears in FileManager: `http://192.168.1.86/hub/fileManager`. Open the HTML — all sections should render.

- [ ] **Step 5: Commit**

```bash
git add apps/HubDiagnostics/HubDiagnostics.groovy
git commit -m "feat(hub-diag): add /api/audit/start endpoint for device usage audit"
```

---

## Task 9: Add `/api/audit/status`, `/api/audit/list`, `/api/audit/delete`

The remaining three endpoints — all small.

**Files:**
- Modify: `apps/HubDiagnostics/HubDiagnostics.groovy` — append three more endpoint methods to the audit section
- Create: `apps/HubDiagnostics/tests/test-audit-api.sh` — smoke test for all four endpoints

- [ ] **Step 1: Implement the three endpoints**

After `flattenDeviceList`, add:

```groovy
/**
 * GET /api/audit/status?scanId=... — polled by frontend during a scan.
 * If scanId is omitted, returns the latest known status.
 */
Map apiAuditStatus() {
    String requested = params.scanId as String
    Map snap = (state.audit ?: [:]) as Map
    if (requested && snap.scanId != requested) {
        // Caller asked about a specific scan we don't know about
        return jsonResponse([scanId: requested, status: 'unknown'])
    }
    return jsonResponse([
        scanId:    snap.scanId,
        status:    snap.status,
        processed: snap.processed ?: 0,
        total:     snap.total ?: 0,
        startedAt: snap.startedAt,
        filename:  snap.filename,
        error:     snap.error
    ])
}

/**
 * GET /api/audit/list — returns past audit summaries, newest first.
 */
Map apiAuditList() {
    return jsonResponse([reports: (state.auditReports ?: []) as List])
}

/**
 * POST /api/audit/delete — body: { filename }. Removes file + index entry.
 */
Map apiAuditDelete() {
    String filename = (request?.JSON?.filename ?: params.filename) as String
    if (!filename) return jsonResponse([error: "filename required"])
    deleteFile(filename)
    List reports = (state.auditReports ?: []) as List
    int before = reports.size()
    reports = reports.findAll { (it.filename as String) != filename }
    state.auditReports = reports
    return jsonResponse([deleted: (before != reports.size()), filename: filename])
}
```

- [ ] **Step 2: Create the smoke test script**

```bash
mkdir -p apps/HubDiagnostics/tests
```

Create `apps/HubDiagnostics/tests/test-audit-api.sh`:

```bash
#!/usr/bin/env bash
# Smoke test for the four Device Usage Audit endpoints on maison-pro.
# Prereqs: jq installed; /Users/trep/Documents/GitHub/iamtrep/hubitat/.hubitat.json has the app id.
# Usage: ACCESS_TOKEN=<token> ./test-audit-api.sh

set -euo pipefail
HUB="${HUB:-http://192.168.1.86}"
CFG="${CFG:-/Users/trep/Documents/GitHub/iamtrep/hubitat/.hubitat.json}"
APP_ID=$(jq -r '.hubs."maison-pro".app_ids."Hub Diagnostics"' "$CFG")
: "${ACCESS_TOKEN:?ACCESS_TOKEN env var required (get from the dashboard URL once)}"
BASE="${HUB}/apps/api/${APP_ID}/api"

echo "=== POST /api/audit/start ==="
START=$(curl -sf -X POST "${BASE}/audit/start?access_token=${ACCESS_TOKEN}")
echo "$START" | jq .
SCAN_ID=$(echo "$START" | jq -r '.scanId')
TOTAL=$(echo "$START" | jq -r '.total')
[[ -n "$SCAN_ID" && "$SCAN_ID" != "null" ]] || { echo "FAIL: no scanId"; exit 1; }
[[ "$TOTAL" -gt 0 ]] || { echo "FAIL: total=$TOTAL"; exit 1; }

echo
echo "=== Polling /api/audit/status until done ==="
for i in $(seq 1 60); do
    STATUS=$(curl -sf "${BASE}/audit/status?scanId=${SCAN_ID}&access_token=${ACCESS_TOKEN}")
    PROC=$(echo "$STATUS" | jq -r '.processed')
    ST=$(echo "$STATUS"   | jq -r '.status')
    echo "  [$i] processed=$PROC/$TOTAL  status=$ST"
    [[ "$ST" == "done" || "$ST" == "error" ]] && break
    sleep 2
done
[[ "$ST" == "done" ]] || { echo "FAIL: final status=$ST"; exit 1; }

FILE=$(echo "$STATUS" | jq -r '.filename')
echo "  → report: $FILE"

echo
echo "=== GET /api/audit/list ==="
LIST=$(curl -sf "${BASE}/audit/list?access_token=${ACCESS_TOKEN}")
echo "$LIST" | jq .
COUNT=$(echo "$LIST" | jq '.reports | length')
[[ "$COUNT" -gt 0 ]] || { echo "FAIL: no reports listed"; exit 1; }

echo
echo "=== GET the rendered HTML ==="
curl -sfo /tmp/audit-test.html "${HUB}/local/${FILE}"
[[ $(wc -c < /tmp/audit-test.html) -gt 1000 ]] || { echo "FAIL: HTML too small"; exit 1; }
echo "  HTML size: $(wc -c < /tmp/audit-test.html) bytes — OK"

echo
echo "=== POST /api/audit/delete ==="
DEL=$(curl -sf -X POST "${BASE}/audit/delete?access_token=${ACCESS_TOKEN}" \
    -H "Content-Type: application/json" \
    -d "{\"filename\":\"${FILE}\"}")
echo "$DEL" | jq .
[[ $(echo "$DEL" | jq -r '.deleted') == "true" ]] || { echo "FAIL: delete returned false"; exit 1; }

echo
echo "ALL TESTS PASSED"
```

```bash
chmod +x apps/HubDiagnostics/tests/test-audit-api.sh
```

- [ ] **Step 3: Push and run the smoke test**

Push via `/hubitat-push`. Then:

```bash
ACCESS_TOKEN=<paste from UI> ./apps/HubDiagnostics/tests/test-audit-api.sh
```

Expected: all five sections of the script log successfully and the script ends with `ALL TESTS PASSED`. The intermediate poll output shows processed climbing from 0 to TOTAL.

- [ ] **Step 4: Commit**

```bash
git add apps/HubDiagnostics/HubDiagnostics.groovy apps/HubDiagnostics/tests/test-audit-api.sh
git commit -m "feat(hub-diag): add audit status/list/delete endpoints + smoke test"
```

---

## Task 10: Frontend — add the trigger button and progress row to the Reports section

Locate the existing Reports section in `rDashboard()` (around line 547 — search for the "Reports" card heading or the existing forum-export button). Add the new button + an in-progress row container.

**Files:**
- Modify: `apps/HubDiagnostics/hub_diagnostics_ui.html` — extend the Reports card body in `rDashboard()`

- [ ] **Step 1: Find the Reports section in the dashboard render**

```bash
grep -n "Forum Export\|forumExport\|generateReport\|Generate HTML Report\|api/report/generate" apps/HubDiagnostics/hub_diagnostics_ui.html
```

Identify the line where the existing buttons are appended to `o`. Note the surrounding HTML structure so the new button matches.

- [ ] **Step 2: Add the trigger button next to existing Reports buttons**

In `rDashboard()`, immediately after the existing "Forum Export" button is appended to `o`, append:

```javascript
o += '<button onclick="auditStart()" id="audit-btn" style="margin-left:6px">Generate Device Audit</button>';
o += '<div id="audit-row" style="margin-top:8px"></div>';
```

- [ ] **Step 3: Add the `auditStart` function in the script body**

After the existing report/export functions (search `forumExport` or `generateReport`), add:

```javascript
async function auditStart() {
    const btn = document.getElementById('audit-btn');
    const row = document.getElementById('audit-row');
    btn.disabled = true; btn.textContent = 'Starting…';
    try {
        const r = await fetch(B + '/api/audit/start?access_token=' + T, {method:'POST'});
        const d = await r.json();
        if (d.error) { row.innerHTML = '<span class="warn">'+h(d.error)+'</span>'; btn.disabled=false; btn.textContent='Generate Device Audit'; return; }
        auditPoll(d.scanId, d.total);
    } catch (e) {
        row.innerHTML = '<span class="warn">'+h(String(e))+'</span>';
        btn.disabled = false; btn.textContent = 'Generate Device Audit';
    }
}
```

(`auditPoll` is defined in Task 11; the button stays disabled until the poll completes.)

- [ ] **Step 4: Bump UI_VERSION**

Find the `UI_VERSION` constant at the top of the script (search `UI_VERSION =`) and increment its patch component. This forces the auto-download trigger.

- [ ] **Step 5: Push UI and run update.sh**

Push via `/hubitat-push` (it covers HTML too). Then:

```bash
/Users/trep/Documents/GitHub/hubitrep/hubitat/HubDiagnostics/update.sh
```

- [ ] **Step 6: Manual verify in browser**

Open the dashboard. The Reports card should show the new "Generate Device Audit" button. Clicking it currently breaks (Task 11 adds polling). Just confirm the button is rendered correctly and looks consistent with existing buttons.

- [ ] **Step 7: Commit**

```bash
git add apps/HubDiagnostics/hub_diagnostics_ui.html
git commit -m "feat(hub-diag-ui): add Generate Device Audit button to Reports section"
```

---

## Task 11: Frontend — polling + progress UI + view link

Implement `auditPoll()` to drive the in-progress row, then handle done/error states.

**Files:**
- Modify: `apps/HubDiagnostics/hub_diagnostics_ui.html` — add `auditPoll` and supporting render helpers

- [ ] **Step 1: Add `auditPoll`**

Immediately after `auditStart` (added in Task 10), add:

```javascript
function auditPoll(scanId, total) {
    const btn = document.getElementById('audit-btn');
    const row = document.getElementById('audit-row');
    let pollTimer = null;
    async function tick() {
        try {
            const r = await fetch(B + '/api/audit/status?scanId=' + encodeURIComponent(scanId) + '&access_token=' + T);
            const d = await r.json();
            if (d.status === 'scanning') {
                row.innerHTML = 'Audit in progress · scanning device <b>'+d.processed+'</b> / '+d.total+' …';
            } else if (d.status === 'done') {
                clearInterval(pollTimer);
                row.innerHTML = '✓ Audit complete · <a href="/local/'+encodeURIComponent(d.filename)+'" target="_blank">View report</a>';
                btn.disabled = false; btn.textContent = 'Generate Device Audit';
                drop('audit-list'); auditList();           // refresh past-audits list (Task 12)
            } else if (d.status === 'error') {
                clearInterval(pollTimer);
                row.innerHTML = '<span class="warn">⚠ Audit failed: '+h(d.error || 'see hub logs')+'</span>';
                btn.disabled = false; btn.textContent = 'Generate Device Audit';
            } else {
                clearInterval(pollTimer);
                row.innerHTML = '<span class="warn">Unknown status: '+h(d.status)+'</span>';
                btn.disabled = false; btn.textContent = 'Generate Device Audit';
            }
        } catch (e) {
            // transient; keep polling
            row.innerHTML = '<span class="muted">Polling…</span>';
        }
    }
    tick();
    pollTimer = setInterval(tick, 2000);
}
```

- [ ] **Step 2: Bump UI_VERSION**

Increment `UI_VERSION` patch.

- [ ] **Step 3: Push, run update.sh**

```bash
# /hubitat-push
/Users/trep/Documents/GitHub/hubitrep/hubitat/HubDiagnostics/update.sh
```

- [ ] **Step 4: Manual verify**

Open the dashboard, click "Generate Device Audit". The row should update every 2 s with `processed / total`, then resolve to `✓ Audit complete · View report`. Click "View report" — the standalone HTML opens in a new tab.

- [ ] **Step 5: Commit**

```bash
git add apps/HubDiagnostics/hub_diagnostics_ui.html
git commit -m "feat(hub-diag-ui): add audit progress polling and view link"
```

---

## Task 12: Frontend — past-audits list with view/delete actions

Render `state.auditReports[]` below the action buttons. Each row links to its file and exposes a delete action.

**Files:**
- Modify: `apps/HubDiagnostics/hub_diagnostics_ui.html` — add `auditList`, render container, wire to `rDashboard`

- [ ] **Step 1: Add a container for the past-audits list in `rDashboard`**

In `rDashboard()`, immediately after the `audit-row` div is appended:

```javascript
o += '<div id="audit-list-wrap" style="margin-top:10px"><div class="muted">Past audits…</div></div>';
```

- [ ] **Step 2: Add the `auditList` rendering function**

After `auditPoll`, add:

```javascript
async function auditList() {
    const wrap = document.getElementById('audit-list-wrap');
    if (!wrap) return;
    try {
        const r = await fetch(B + '/api/audit/list?access_token=' + T);
        const d = await r.json();
        const reps = d.reports || [];
        if (!reps.length) { wrap.innerHTML = '<div class="muted">No past audits yet.</div>'; return; }
        let h2 = '<table style="width:100%;font-size:12px;border-collapse:collapse"><tr style="color:var(--muted);text-align:left"><th style="padding:4px">Generated</th><th>Devices</th><th>Unref</th><th>Orphans</th><th>Stuck</th><th>Crit</th><th></th></tr>';
        reps.forEach(r => {
            h2 += '<tr style="border-top:1px solid var(--border)">'
               +  '<td style="padding:4px"><a href="/local/'+encodeURIComponent(r.filename)+'" target="_blank">'+h(r.generated)+'</a></td>'
               +  '<td>'+r.deviceCount+'</td>'
               +  '<td>'+(r.unreferencedCount||0)+'</td>'
               +  '<td>'+(r.orphanCount||0)+'</td>'
               +  '<td>'+(r.stuckJobCount||0)+'</td>'
               +  '<td>'+(r.criticalCount||0)+'</td>'
               +  '<td><a href="javascript:void(0)" onclick="auditDelete(\''+r.filename.replace(/\\/g,'\\\\').replace(/\'/g,"\\'")+'\')">delete</a></td>'
               +  '</tr>';
        });
        h2 += '</table>';
        wrap.innerHTML = h2;
    } catch (e) {
        wrap.innerHTML = '<span class="muted">Failed to load past audits: '+h(String(e))+'</span>';
    }
}
async function auditDelete(filename) {
    if (!confirm('Delete '+filename+'?')) return;
    await fetch(B + '/api/audit/delete?access_token=' + T, {
        method: 'POST',
        headers: {'Content-Type':'application/json'},
        body: JSON.stringify({filename})
    });
    drop('audit-list'); auditList();
}
```

- [ ] **Step 3: Trigger initial load when the dashboard renders**

At the end of `rDashboard()`, after the existing render code, add:

```javascript
auditList();
```

- [ ] **Step 4: Bump UI_VERSION**

Increment `UI_VERSION` patch.

- [ ] **Step 5: Push and run update.sh**

```bash
# /hubitat-push
/Users/trep/Documents/GitHub/hubitrep/hubitat/HubDiagnostics/update.sh
```

- [ ] **Step 6: Manual verify**

Open the dashboard. The Reports card now shows the past-audits table. Trigger a fresh audit; on completion the table refreshes and the new row appears at top. Click a "delete" link — confirm prompt; row disappears.

- [ ] **Step 7: Commit**

```bash
git add apps/HubDiagnostics/hub_diagnostics_ui.html
git commit -m "feat(hub-diag-ui): add past-audits list with view and delete actions"
```

---

## Task 13: Bump APP_VERSION + README documentation

The user requested per project memory that `APP_VERSION` (Groovy) and `UI_VERSION` (HTML) bump together. UI_VERSION has been bumped 3 × in the previous tasks; align Groovy now and document the feature.

**Files:**
- Modify: `apps/HubDiagnostics/HubDiagnostics.groovy` — line 14 (`APP_VERSION`)
- Modify: `apps/HubDiagnostics/README.md` — add "Device Usage Audit" section

- [ ] **Step 1: Bump APP_VERSION**

Find `@Field static final String APP_VERSION = "5.8.2"` (line 14) and increment the minor (this is a feature):

```groovy
@Field static final String APP_VERSION = "5.9.0"
```

Confirm `UI_VERSION` in the HTML is at a corresponding `5.9.0` (or matching scheme). If it isn't, reconcile them.

- [ ] **Step 2: Add README section**

Find an appropriate location in `apps/HubDiagnostics/README.md` — after the existing reports/snapshots discussion. Add:

```markdown
## Device Usage Audit

Generates a one-time, per-device cross-reference report covering:

- **Unreferenced devices** — no apps subscribe to them, no dashboards display them, no parent integration manages them; cleanup candidates.
- **Mesh orphans** — Hubitat reports `orphan: true` (radio/network state).
- **Stuck scheduled jobs** — `nextRunTime` in the past.
- **Critical devices** — top 20 by combined apps + dashboards reference count.
- **Apps → devices and Dashboards → devices reverse indices**.
- **Per-device detail table** with all subscribers as clickable links.

### How it works

The scan crawls every device via `/device/fullJson/{id}` — one call per device, throttled
to the Hubitat platform's 8-concurrent-async-call cap. On a 350-device hub this takes
~30–60 s. The output is a self-contained HTML file written to FileManager
(`hub_usage_audit_YYYYMMDD_HHmmss.html`) and indexed under "Past audits" on the Dashboard.

### Trigger

Dashboard tab → Reports section → **Generate Device Audit**. Progress is polled live; the
"View report" link appears on completion. The 10 most recent audits are kept; older entries
are auto-deleted.

### Limitations

- The scan is one-shot; subscriptions can change between audits. Re-run for fresh data.
- A single device fetch failure ratio above 10 % marks the scan as `error` instead of `done`
  (a partial report is still written).
- HubDiagnostics is `singleInstance: true`, so two audits cannot run concurrently.
```

- [ ] **Step 3: Push and run update.sh**

```bash
# /hubitat-push
/Users/trep/Documents/GitHub/hubitrep/hubitat/HubDiagnostics/update.sh
```

- [ ] **Step 4: Commit**

```bash
git add apps/HubDiagnostics/HubDiagnostics.groovy apps/HubDiagnostics/README.md
git commit -m "feat(hub-diag): bump to 5.9.0 — Device Usage Audit + README docs"
```

---

## Task 14: End-to-end validation on `maison-pro` and scale validation on `maison`

A final pass to confirm everything holds together at small scale and at real scale.

**Files:** none modified — verification only.

- [ ] **Step 1: Re-run the full smoke test on `maison-pro`**

```bash
ACCESS_TOKEN=<paste> ./apps/HubDiagnostics/tests/test-audit-api.sh
```

Expected: all five phases pass.

- [ ] **Step 2: Open the Dashboard in a browser, click Generate Device Audit, verify**

- Progress row updates live.
- View link opens the HTML.
- All 8 sections render.
- Every device link in the report opens `/device/edit/{id}` correctly.
- Every app link opens `/installedapp/configure/{id}` correctly.
- Dashboards open `/installedapp/configure/{id}` (Hubitat dashboards are installed apps).
- Past-audits list refreshes with the new entry.
- Delete a past entry — file is removed from FileManager and the row disappears.

- [ ] **Step 3: Scale validation on `maison`**

Push the same code to `maison` (350+ devices, hub security):

```bash
# Use /hubitat-push @maison or the equivalent — see project skills
```

Trigger an audit through the UI. Watch hub logs for any of:
- `[error] asynchttpGet: too many requests` — would indicate the cap was exceeded (should not happen).
- Timeout errors on individual fetches — note the `failedCount` in the final summary.

Confirm scan completes in 30–90 s and the report renders correctly. Spot-check 3 devices for accurate `appsUsing` content vs. Hubitat's own device-edit page "In Use By" section.

- [ ] **Step 4: If everything passes, the feature is shippable**

No commit needed for this task.

---

## Self-review (post-write)

**Spec coverage:**

- ✅ Trigger button in Dashboard Reports section — Task 10
- ✅ Async scan w/ 8-cap via CAS — Task 6 (`dispatchOne`)
- ✅ Polling endpoint + UI — Tasks 9 + 11
- ✅ Per-device extraction (sections A/B/C only) — Task 3
- ✅ Cross-reference indices, sorting per spec — Task 4
- ✅ Standalone HTML w/ inlined design tokens — Task 5
- ✅ Persistence: in-memory `AUDIT_SCANS`, `state.audit` snapshot, `state.auditReports[]`, FileManager — Tasks 6/7/9
- ✅ Watchdog — Task 7
- ✅ FIFO trim of past audits (10 max) — Task 7 via `AUDIT_REPORTS_KEEP`
- ✅ Stale-scan force-clear on entry (10 min) — Task 8 via `AUDIT_STALE_MS`
- ✅ Failure ratio > 10 % → status: error — Tasks 6/7
- ✅ Delete endpoint + UI action — Tasks 9 + 12
- ✅ Smoke test script for all four endpoints — Task 9
- ✅ Sandbox imports verified before any dependent code — Task 1
- ✅ README documentation — Task 13
- ✅ Version bump policy — Task 13

**Placeholder scan:** No "TBD", "TODO", "fill in", or pseudo-code in any task.

**Type consistency:**
- `extractAuditFields` returns Map with key `appsUsing` (List of Maps), `dashboards` (List of Maps), `parentApp` (Map or null), `appsUsingCount` (Integer), `orphan` (boolean), `driverType` (String), `lastActivityTime` (String), etc. — used consistently in `buildCrossReference` and `renderAuditHtml`.
- `dispatchOne` and `fullJsonCb` agree on `data: [scanId, deviceId]` shape and on `AUDIT_SCANS` field types (`AtomicInteger`, `ConcurrentLinkedQueue<Long>`, `ConcurrentHashMap<Long, Map>`).
- `state.audit` snapshot keys (`scanId, status, processed, total, startedAt, filename, error`) used consistently across `apiAuditStart`, `fullJsonCb`, `finalizeAudit`, `auditWatchdog`, `apiAuditStatus`.
- `state.auditReports[]` summary keys (`filename, generated, deviceCount, scanDurationMs, unreferencedCount, orphanCount, stuckJobCount, criticalCount, failedCount, errored`) defined in `finalizeAudit`, consumed in `apiAuditList` and `auditList()` JS rendering.

No inconsistencies found.

---

## Execution

Plan complete and saved to `docs/superpowers/plans/2026-05-07-device-usage-audit.md`. Two execution options:

**1. Subagent-Driven (recommended)** — A fresh subagent per task, two-stage review between tasks. Best when you want me to iterate on each task with full freshness and you'll spot-check the work between tasks.

**2. Inline Execution** — Execute tasks in this session with batched checkpoints. Faster turnaround, but my context fills as we go.

Which approach?

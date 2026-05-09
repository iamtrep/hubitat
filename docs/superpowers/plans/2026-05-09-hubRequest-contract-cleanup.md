# hubRequest Contract Cleanup Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Eliminate the `[error: true, message:]` sentinel from `hubRequest`'s json return channel by introducing `hubMapRequest()`, then migrate all ~35 Map-returning json callers.

**Architecture:** A new `hubMapRequest()` wraps `hubRequestInternal` and always returns `[ok: boolean, data: Map, error: String?]`. Text callers and List-returning json callers (`fetchUserAppTypes`, `fetchDeviceTypes`, `fetchBundles`, `fetchLibraries`, `fetchLocalBackups`, `fetchZigbeeChannelScan`) stay on the existing `hubRequest()` — they have no `[error:true]` ambiguity. All code is in `HubDiagnostics.groovy`.

**Tech Stack:** Groovy (Hubitat platform); test suite is `tests/test-hub-diagnostics-api.sh` against `@maison-pro`.

---

### Task 1: Add `hubMapRequest()`

**Files:**
- Modify: `apps/HubDiagnostics/HubDiagnostics.groovy` near line 1967

- [ ] **Step 1: Add the function after `hubRequest()`**

Insert immediately after the closing brace of `hubRequest()` (currently at line 1969):

```groovy
private Map hubMapRequest(String path, String name, int timeout = 30) {
    Object raw = hubRequestInternal(path, name, "json", timeout, true)
    if (raw instanceof Map && ((Map) raw).error) {
        return [ok: false, data: [:], error: (String) ((Map) raw).message]
    }
    return [ok: true, data: (Map)(raw ?: [:]), error: null]
}
```

- [ ] **Step 2: Push to hub and verify it compiles**

```bash
/push @maison-pro
```
Expected: `{"status":"success"}` — no compile errors.

- [ ] **Step 3: Commit**

```bash
git add apps/HubDiagnostics/HubDiagnostics.groovy
git commit -m "feat: add hubMapRequest() — uniform [ok, data, error] wrapper for Map-json calls"
```

---

### Task 2: Migrate `analyzeNetwork()` and update all downstream `.error` checks

`analyzeNetwork()` is the highest-leverage change: 5 calls store raw results (possibly error sentinels) in a Map, and ~12 downstream spots check `.error` on those values. After this task, the network sub-Maps are either real data or `null`, and all `.error` guards become plain null checks.

**Files:**
- Modify: `apps/HubDiagnostics/HubDiagnostics.groovy` lines ~3023-3031, ~849-855, ~1329, ~1390, ~1424, ~1676-1706, ~2677, ~2685

- [ ] **Step 1: Replace `analyzeNetwork()` body (lines 3023–3031)**

```groovy
Map analyzeNetwork() {
    return [
        network: hubMapRequest(NETWORK_CONFIG_PATH, "network configuration", 15).with { it.ok ? it.data : null },
        zwave:   hubMapRequest(ZWAVE_DETAILS_PATH, "Z-Wave details", 20).with { it.ok ? it.data : null },
        zigbee:  hubMapRequest(ZIGBEE_DETAILS_PATH, "Zigbee details", 20).with { it.ok ? it.data : null },
        matter:  hubMapRequest(MATTER_DETAILS_PATH, "Matter details", 15).with { it.ok ? it.data : null },
        hubMesh: hubMapRequest(HUB_MESH_PATH, "Hub Mesh", 15).with { it.ok ? it.data : null }
    ]
}
```

- [ ] **Step 2: Update snapshot consumer (lines ~849–855)**

The `getSnapshotsData` diff builder reads stored snapshot network data. The stored snapshots were created by earlier `analyzeNetwork()` calls — old snapshots may still have `[error:true, ...]` Maps in them. Keep the `.error` check here as a read-path guard for legacy data in `state.snapshots`.

No change needed at lines 849–855. The `.error` checks there protect against old stored state and should be left.

- [ ] **Step 3: Update `extractZwaveMessageCounts()` (line ~2677)**

```groovy
List extractZwaveMessageCounts(Map zwaveData) {
    if (!zwaveData || !zwaveData.nodes) return []
    return zwaveData.nodes.collect { Map node ->
        [id: node.nodeId, deviceId: node.deviceId, name: node.deviceName ?: "Node ${node.nodeId}",
         msgCount: (node.msgCount ?: 0) as int, routeChanges: node.routeChanges?.toString()?.isInteger() ? (node.routeChanges ?: 0) as int : -1]
    }
}
```

- [ ] **Step 4: Update `extractZigbeeMessageCounts()` (line ~2685)**

```groovy
List extractZigbeeMessageCounts(Map zigbeeData) {
    if (!zigbeeData || !zigbeeData.devices) return []
    return zigbeeData.devices.collect { Map device ->
        [id: device.id, name: device.name ?: "Device ${device.id}",
         msgCount: (device.messageCount ?: 0) as int]
    }
}
```

- [ ] **Step 5: Update `getNetworkData()` network-data guards (lines ~1676–1706)**

```groovy
        network: networkData.network ?: null,
        zwave: networkData.zwave ? [
            enabled: networkData.zwave.enabled, healthy: networkData.zwave.healthy,
            region: networkData.zwave.region, nodeCount: (networkData.zwave.zwDevices ?: [:]).size(),
            isRadioUpdateNeeded: networkData.zwave.isRadioUpdateNeeded,
            zwaveJS: networkData.zwave.zwaveJS, zwaveJSAvailable: networkData.zwave.zwaveJSAvailable,
            version: zwaveVersion,
            mesh: zwaveMesh, ghostNodes: ghostNodes, problemNodes: problemNodes,
            messageCounts: extractZwaveMessageCounts(networkData.zwave ?: [:])
        ] : null,
        zigbee: networkData.zigbee ? [
            enabled: zigbeeRaw.enabled, healthy: zigbeeRaw.healthy,
            networkState: zigbeeRaw.networkState, channel: zigbeeRaw.channel,
            panId: zigbeeRaw.panId, extendedPanId: zigbeeRaw.extendedPanId,
            deviceCount: (zigbeeRaw.devices ?: []).size(), joinMode: zigbeeRaw.inJoinMode,
            powerLevel: zigbeeRaw.powerLevel,
            messageCounts: extractZigbeeMessageCounts(networkData.zigbee ?: [:]),
            mesh: zigbeeMesh ? [
                neighbors: zigbeeMesh.neighbors?.size() ?: 0, routes: zigbeeMesh.routes?.size() ?: 0,
                avgLqi: zigbeeMesh.avgLqi, minLqi: zigbeeMesh.minLqi, maxLqi: zigbeeMesh.maxLqi,
                neighborDetails: (zigbeeMesh.neighbors ?: []).collect { Map n ->
                    Map zdev = zigbeeDeviceByShortId[n.shortId?.toUpperCase()]
                    [shortId: n.shortId, name: n.name, deviceId: zdev?.id, lqi: n.lqi, age: n.age, inCost: n.inCost, outCost: n.outCost, stale: n.stale ?: false]
                },
                weakNeighbors: (zigbeeMesh.weakNeighbors ?: []).collect { [shortId: it.shortId, lqi: it.lqi] },
                staleNeighbors: (zigbeeMesh.staleNeighbors ?: []).collect { [shortId: it.shortId, age: it.age] },
                childDevices: zigbeeMesh.childDevices?.size() ?: 0
            ] : null
        ] : null,
        matter: networkData.matter ?: null,
        hubMesh: networkData.hubMesh ? [
```

- [ ] **Step 6: Update `apiForumExport()` guards (lines ~1329, ~1390, ~1424)**

```groovy
    // line ~1329
    if (zwaveRaw) {

    // line ~1390
    if (zigbeeRaw && zigbeeRaw.enabled) {

    // line ~1424
    if (hubMeshRaw && hubMeshRaw.hubList) {
```

- [ ] **Step 7: Push and verify**

```bash
/push @maison-pro
```
Expected: `{"status":"success"}`

- [ ] **Step 8: Commit**

```bash
git add apps/HubDiagnostics/HubDiagnostics.groovy
git commit -m "refactor: migrate analyzeNetwork() to hubMapRequest; drop .error checks on network sub-maps"
```

---

### Task 3: Migrate `buildSharedCache()`, `apiNetwork()`, `apiGenerateReport()`

**Files:**
- Modify: `apps/HubDiagnostics/HubDiagnostics.groovy` lines ~566-578, ~648, ~1164-1171

- [ ] **Step 1: Update `buildSharedCache()` (lines ~566–578)**

```groovy
private Map buildSharedCache(boolean includeNetwork = false) {
    Map shared = [:]
    Map hubDataWrap = hubMapRequest(HUB_DATA_PATH, "hub data (shared)", 10)
    shared.hubData     = hubDataWrap.ok ? hubDataWrap.data : null
    shared.resources   = fetchSystemResources()
    shared.temperature = fetchTemperature()
    shared.databaseSize = fetchDatabaseSize()
    shared.hubAlerts   = fetchHubAlerts(shared.hubData as Map)
    if (includeNetwork) {
        shared.network      = analyzeNetwork()
        Map statsWrap = hubMapRequest(RUNTIME_STATS_PATH, "runtime stats (shared)")
        shared.runtimeStats = statsWrap.ok ? statsWrap.data : null
    }
    return shared
}
```

- [ ] **Step 2: Update `apiNetwork()` inline shared build (line ~648)**

```groovy
Map apiNetwork() {
    long start = now()
    Map shared = [:]
    Map hubDataWrap = hubMapRequest(HUB_DATA_PATH, "hub data (shared)", 10)
    shared.hubData = hubDataWrap.ok ? hubDataWrap.data : null
    Map data = getNetworkData(shared)
    long elapsed = now() - start
    logDebug "apiNetwork completed in ${elapsed}ms"
    recordApiTiming("network", elapsed)
    return jsonResponse(data)
}
```

- [ ] **Step 3: Update `apiGenerateReport()` inline shared build (line ~1164–1171)**

```groovy
    Map statsWrap = hubMapRequest(RUNTIME_STATS_PATH, "runtime stats")
    Map shared = [
        network:      analyzeNetwork(),
        runtimeStats: statsWrap.ok ? statsWrap.data : null,
        resources:    fetchSystemResources(),
        temperature:  fetchTemperature(),
        hubAlerts:    fetchHubAlerts(),
        databaseSize: fetchDatabaseSize()
    ]
```

- [ ] **Step 4: Update consumers of `shared.hubData` that still check `.error`**

In `fetchHubAlerts()` (line ~2130) and `fetchSecurityInfo()` (line ~2428), the parameter `prefetchedHubData` is now always a clean Map or null (not an error sentinel). Update both guards:

`fetchHubAlerts()` line ~2130:
```groovy
    if (!hubData) return [:]
```

`fetchSecurityInfo()` line ~2428:
```groovy
    Boolean cloudDisabled = hubData ? (hubData.disableCloudController == true) : null
```

- [ ] **Step 5: Push and verify**

```bash
/push @maison-pro
```
Expected: `{"status":"success"}`

- [ ] **Step 6: Commit**

```bash
git add apps/HubDiagnostics/HubDiagnostics.groovy
git commit -m "refactor: migrate buildSharedCache/apiNetwork/apiGenerateReport to hubMapRequest"
```

---

### Task 4: Migrate fallback callers in `getNetworkData()`, `getPerformanceData()`, `getStructuredAlerts()`

These functions accept an optional `shared` Map and fall back to a direct fetch if the key is absent.

**Files:**
- Modify: `apps/HubDiagnostics/HubDiagnostics.groovy` lines ~1654, ~1745–1748, ~1893, ~1904

- [ ] **Step 1: Update `getNetworkData()` fallback (line ~1654)**

```groovy
    Map statsRaw = (Map) shared.runtimeStats
    if (!statsRaw) {
        Map r = hubMapRequest(RUNTIME_STATS_PATH, "runtime stats")
        statsRaw = r.ok ? r.data : null
    }
    Map stats = statsRaw
```

(The variable `stats` is used unchanged below this point.)

- [ ] **Step 2: Update `getPerformanceData()` fallbacks (lines ~1745–1748)**

```groovy
    Map stats
    if (shared.runtimeStats) {
        stats = (Map) shared.runtimeStats
    } else {
        Map r = hubMapRequest(RUNTIME_STATS_PATH, "runtime stats")
        stats = r.ok ? r.data : null
    }
    Map resources  = (shared.resources as Map) ?: fetchSystemResources()
    Map zwaveData
    if (shared.network?.zwave) {
        zwaveData = (Map) shared.network.zwave
    } else {
        Map r = hubMapRequest(ZWAVE_DETAILS_PATH, "Z-Wave details", 20)
        zwaveData = r.ok ? r.data : null
    }
    Map zigbeeData
    if (shared.network?.zigbee) {
        zigbeeData = (Map) shared.network.zigbee
    } else {
        Map r = hubMapRequest(ZIGBEE_DETAILS_PATH, "Zigbee details", 20)
        zigbeeData = r.ok ? r.data : null
    }
```

- [ ] **Step 3: Update `getStructuredAlerts()` network-config fallback (line ~1893)**

```groovy
    Map networkConfig = (Map) shared.network?.network
    if (!networkConfig) {
        Map r = hubMapRequest(NETWORK_CONFIG_PATH, "network configuration", 15)
        networkConfig = r.ok ? r.data : null
    }
    if (networkConfig && networkConfig.hasEthernet && networkConfig.hasWiFi) {
```

- [ ] **Step 4: Update `getStructuredAlerts()` zwave ghost-check fallback (lines ~1900–1909)**

```groovy
    Map zwRaw = (Map) shared.network?.zwave
    if (!zwRaw) {
        long lastZwCheck = state.lastZwaveGhostCheckMs ?: 0
        if (now() - lastZwCheck > 60000) {
            Map r = hubMapRequest(ZWAVE_DETAILS_PATH, "Z-Wave details", 8)
            zwRaw = r.ok ? r.data : null
            if (zwRaw) {
                state.lastZwaveGhostCheckMs = now()
                state.cachedZwaveGhostCount = buildZwaveGhostNodes(zwRaw).size()
            }
        }
    }
```

- [ ] **Step 5: Update `fetchHubAlerts()` and `fetchSecurityInfo()` fallback fetches (lines ~2129, ~2424)**

`fetchHubAlerts()` (line ~2129):
```groovy
Map fetchHubAlerts(Map prefetchedHubData = null) {
    Map hubData = prefetchedHubData
    if (!hubData) {
        Map r = hubMapRequest(HUB_DATA_PATH, "hub data", 10)
        hubData = r.ok ? r.data : null
    }
    if (!hubData) return [:]
    return [
        alerts: hubData.alerts ?: [:],
        databaseSize: hubData.alerts?.databaseSize,
        spammyDevicesMessage: hubData.spammyDevicesMessage,
        devMode: hubData.baseModel?.devMode ?: false
    ]
}
```

`fetchSecurityInfo()` (line ~2424):
```groovy
    Map hubData = prefetchedHubData
    if (!hubData) {
        Map r = hubMapRequest(HUB_DATA_PATH, "hub data (cloud controller flag)", 10)
        hubData = r.ok ? r.data : null
    }
```

- [ ] **Step 6: Push and verify**

```bash
/push @maison-pro
```
Expected: `{"status":"success"}`

- [ ] **Step 7: Commit**

```bash
git add apps/HubDiagnostics/HubDiagnostics.groovy
git commit -m "refactor: migrate shared-fallback callers in getNetworkData/getPerformanceData/getStructuredAlerts/fetchHubAlerts/fetchSecurityInfo"
```

---

### Task 5: Migrate `getPerformanceData()` direct callers and `apiSnapshotDiff()`

**Files:**
- Modify: `apps/HubDiagnostics/HubDiagnostics.groovy` lines ~1760, ~1782, ~743–751

- [ ] **Step 1: Update `getPerformanceData()` apps-list fetch (lines ~1760–1806)**

`appsListResp` is used twice — at line 1761 for `visitAppEntries` and at line 1793 for `walkApps`. Keep the variable name so the second reference needs no change:

```groovy
    Map appSourceById = [:]
    Map appsListWrap = hubMapRequest(APPS_LIST_PATH, "apps list")
    Map appsListResp = appsListWrap.ok ? appsListWrap.data : [:]
    if (appsListResp.apps) {
        visitAppEntries(appsListResp.apps as List) { Map appEntry, Map app, boolean isChildLevel, List _ ->
            if (app?.id != null) appSourceById[app.id] = (app.user ? "community" : "builtin")
        }
    }
```

Lines 1793 and 1806 (`if (appsListResp?.apps)` and `walkApps(appsListResp.apps as List, null)`) are unchanged — they now read from the clean data Map.

- [ ] **Step 2: Update `getPerformanceData()` devices-list fetch (line ~1782)**

```groovy
    Map deviceTypeById = [:]
    Map devWrap = hubMapRequest(DEVICES_LIST_PATH, "devices list (B2 labels)", 15)
    if (devWrap.ok && devWrap.data.devices) {
        flattenDeviceEntries(devWrap.data.devices as List).each { Map entry ->
            Map dev = entry?.data instanceof Map ? (Map) entry.data : null
            if (dev?.id != null) deviceTypeById[dev.id] = (dev.type ?: 'Unknown') as String
        }
    }
```

- [ ] **Step 3: Update `apiSnapshotDiff()` checkpoint-stats fetches (lines ~743–751)**

```groovy
    if (checkpoint == "now") {
        Map statsWrap = hubMapRequest(RUNTIME_STATS_PATH, "runtime stats")
        checkpointStats = statsWrap.ok ? statsWrap.data : [:]
        Map currentResources = fetchSystemResources()
        checkpointStats.resources = currentResources
        Map zwWrap = hubMapRequest(ZWAVE_DETAILS_PATH, "Z-Wave details", 20)
        Map zwaveData = zwWrap.ok ? zwWrap.data : [:]
        Map zbWrap = hubMapRequest(ZIGBEE_DETAILS_PATH, "Zigbee details", 20)
        Map zigbeeData = zbWrap.ok ? zbWrap.data : [:]
        checkpointStats.radioStats = [
            zwave: extractZwaveMessageCounts(zwaveData),
            zigbee: extractZigbeeMessageCounts(zigbeeData)
        ]
        checkpointStats.timestampMs = now()
        checkpointLabel = "Now (${new Date().format('yyyy-MM-dd HH:mm:ss')})"
    }
```

- [ ] **Step 4: Push and verify**

```bash
/push @maison-pro
```
Expected: `{"status":"success"}`

- [ ] **Step 5: Commit**

```bash
git add apps/HubDiagnostics/HubDiagnostics.groovy
git commit -m "refactor: migrate getPerformanceData direct callers and apiSnapshotDiff to hubMapRequest"
```

---

### Task 6: Migrate `apiForumExport()` runtimeStats and `analyzeDevices()`

**Files:**
- Modify: `apps/HubDiagnostics/HubDiagnostics.groovy` lines ~1225, ~2695

- [ ] **Step 1: Update `apiForumExport()` runtimeStats (line ~1225)**

```groovy
    Map statsWrap = hubMapRequest(RUNTIME_STATS_PATH, "runtime stats")
    Map stats = statsWrap.ok ? statsWrap.data : null
    Integer uptimeSeconds = stats ? parseUptime(stats.uptime as String) : null
```

- [ ] **Step 2: Update `analyzeDevices()` devices-list fetch (line ~2695)**

```groovy
Map analyzeDevices(boolean deep = true) {
    Map respWrap = hubMapRequest(DEVICES_LIST_PATH, "devices list")
    if (!respWrap.ok || !respWrap.data.devices) {
        logWarn "Failed to fetch devices list"
        return getEmptyDeviceStats()
    }
    List devicesList = flattenDeviceEntries(respWrap.data.devices as List, deep)
```

- [ ] **Step 3: Push and verify**

```bash
/push @maison-pro
```
Expected: `{"status":"success"}`

- [ ] **Step 4: Commit**

```bash
git add apps/HubDiagnostics/HubDiagnostics.groovy
git commit -m "refactor: migrate apiForumExport runtimeStats and analyzeDevices to hubMapRequest"
```

---

### Task 7: Migrate simple fetch functions

**Files:**
- Modify: `apps/HubDiagnostics/HubDiagnostics.groovy` — 9 functions

- [ ] **Step 1: Update `fetchFileManagerStats()` (line ~2113)**

```groovy
Map fetchFileManagerStats() {
    Map wrap = hubMapRequest("/hub/fileManager/json", "file manager", 10)
    if (!wrap.ok) return null
    List files = (List) (wrap.data.files ?: [])
    long usedBytes = 0L
    files.each { usedBytes += (it.size?.toString()?.toLong() ?: 0L) }
    return [fileCount: files.size(), usedBytes: usedBytes, freeSpace: wrap.data.freeSpace]
}
```

- [ ] **Step 2: Update `fetchBackups()` cloud half (line ~2143)**

Replace only the cloudResp line (localResp stays as-is — it's a List caller):

```groovy
    Map cloudWrap = hubMapRequest(CLOUD_BACKUPS_PATH, "cloud backups", 15)
    Map cloudResp = cloudWrap.ok ? cloudWrap.data : [:]
```

Remove the old `if (cloudResp?.error) cloudResp = [:]` line — it's no longer needed.

- [ ] **Step 3: Update `fetchHubMessages()` (line ~2175)**

```groovy
List fetchHubMessages() {
    Map wrap = hubMapRequest(HUB_MESSAGES_PATH, "hub messages", 5)
    if (!wrap.ok) return []
    return ((wrap.data.messages as List) ?: []).collect { Object m ->
        if (m instanceof Map) return m
        return [text: m?.toString()]
    }
}
```

- [ ] **Step 4: Update `fetchZwaveJsState()` (line ~2199)**

```groovy
Map fetchZwaveJsState() {
    if (detectZwaveStack() != "js") return null
    Map wrap = hubMapRequest(ZWAVE_JS_CONTROLLER_PATH, "zwave JS controller", 10)
    if (!wrap.ok) return null
    Map ctrl = wrap.data
    Map stats = (ctrl.statistics as Map) ?: [:]
    return [
        firmwareVersion: ctrl.firmwareVersion, sdkVersion: ctrl.sdkVersion,
        homeId: ctrl.homeId, ownNodeId: ctrl.ownNodeId,
        isPrimary: ctrl.isPrimary, isSUC: ctrl.isSUC, isSISPresent: ctrl.isSISPresent,
        isRebuildingRoutes: ctrl.isRebuildingRoutes,
        rfRegion: ctrl.rfRegion, supportsLongRange: ctrl.supportsLongRange,
        statistics: [
            messagesRX: stats.messagesRX, messagesTX: stats.messagesTX,
            messagesDroppedRX: stats.messagesDroppedRX, messagesDroppedTX: stats.messagesDroppedTX,
            CAN: stats.CAN, NAK: stats.NAK,
            timeoutACK: stats.timeoutACK, timeoutCallback: stats.timeoutCallback, timeoutResponse: stats.timeoutResponse,
            backgroundRSSI: stats.backgroundRSSI
        ]
    ]
}
```

- [ ] **Step 5: Update `fetchFirmwareUpdate()` (line ~2238)**

```groovy
    Map wrap = hubMapRequest(FIRMWARE_UPDATE_PATH, "firmware update check", 15)
    if (!wrap.ok) return null
    Map resp = wrap.data
    Map result = [
        currentVersion: getHubFirmwareVersion(),
        availableVersion: resp.version,
        updateAvailable: resp.upgrade == true,
        status: resp.status,
        beta: resp.beta == true,
        releaseNotesUrl: resp.releaseNotesUrl
    ]
```

- [ ] **Step 6: Update `fetchRoomsForAudit()` (line ~2259)**

```groovy
List fetchRoomsForAudit() {
    Map wrap = hubMapRequest(ROOMS_LIST_PATH, "rooms list", 10)
    if (!wrap.ok) return []
    List nodes = (wrap.data.roomNodes as List) ?: []
```

- [ ] **Step 7: Update `fetchZwaveNodeState()` (line ~2278)**

```groovy
Map fetchZwaveNodeState(Integer nodeId) {
    if (nodeId == null) return null
    if (detectZwaveStack() != "js") return null
    Map wrap = hubMapRequest(ZWAVE_JS_NODE_STATE_PREFIX + nodeId, "zwave node ${nodeId}", 5)
    if (!wrap.ok) return null
    Map resp = wrap.data
    Map stats = (resp.statistics as Map) ?: [:]
    return [
        nodeState: resp.nodeState, status: resp.status,
        interviewStage: resp.interviewStage, ready: resp.ready == true,
        rssi: resp.rssi, rtt: resp.rtt, per: resp.per,
        route: resp.route, lastSeenLocal: resp.lastSeenLocal,
        keepAwake: resp.keepAwake == true, securityClass: resp.securityClass,
        isFrequentListening: resp.isFrequentListening == true,
        isControllerNode: resp.isControllerNode == true,
        statistics: [
            commandsTX: stats.commandsTX, commandsRX: stats.commandsRX,
            commandsDroppedTX: stats.commandsDroppedTX, commandsDroppedRX: stats.commandsDroppedRX,
            timeoutResponse: stats.timeoutResponse
        ]
    ]
}
```

- [ ] **Step 8: Update `fetchHubMeshDeviceState()` (line ~2300)**

```groovy
Map fetchHubMeshDeviceState(Long deviceId) {
    if (deviceId == null) return null
    Map wrap = hubMapRequest(HUB_MESH_LINKED_DEVICE_PREFIX + deviceId, "hubmesh dev ${deviceId}", 5)
    if (!wrap.ok) return null
    return wrap.data
}
```

- [ ] **Step 9: Update `fetchMdns()` (line ~2373)**

```groovy
Map fetchMdns() {
    Map wrap = hubMapRequest(MDNS_PATH, "mDNS devices", 15)
    if (!wrap.ok) return null
    Map resp = wrap.data
    List endpoints = []
    ((resp.serviceTypes as List) ?: []).each { Map st ->
        String svc = (st.serviceType as String) ?: ""
        ((st.endpoints as List) ?: []).each { Map ep ->
            endpoints << [
                serviceType: svc,
                name: ep.name, server: ep.server,
                ip: ep.ip4Address, port: ep.port, mac: ep.macAddress,
                model: ep.model, manufacturer: ep.manufacturer,
                lastUpdated: ep.lastUpdated
            ]
        }
    }
    return [
        totalServiceTypes: resp.totalServiceTypes,
        totalEndpoints: resp.totalEndpoints,
        endpoints: endpoints
    ]
}
```

- [ ] **Step 10: Push and verify**

```bash
/push @maison-pro
```
Expected: `{"status":"success"}`

- [ ] **Step 11: Commit**

```bash
git add apps/HubDiagnostics/HubDiagnostics.groovy
git commit -m "refactor: migrate simple fetch functions to hubMapRequest"
```

---

### Task 8: Post-migration sweep and version bump

**Files:**
- Modify: `apps/HubDiagnostics/HubDiagnostics.groovy` (APP_VERSION line ~17)
- Modify: `apps/HubDiagnostics/hub_diagnostics_ui.html` (UI_VERSION constant)

- [ ] **Step 1: Grep for remaining `hubRequest` calls with `"json"` type**

```bash
grep -n 'hubRequest.*"json"' apps/HubDiagnostics/HubDiagnostics.groovy
```

Expected: only the 6 List callers remain:
- `fetchUserAppTypes` — `USER_APP_TYPES_PATH`
- `fetchDeviceTypes` — `DEVICE_TYPES_PATH`
- `fetchBundles` — `USER_BUNDLES_PATH`
- `fetchLibraries` — `USER_LIBRARIES_PATH`
- `fetchBackups` — `LOCAL_BACKUPS_PATH` (localResp, Object-typed)
- `fetchZigbeeChannelScan` — `ZIGBEE_CHANNEL_SCAN_PATH`

If any Map-typed callers still appear, fix them before continuing.

- [ ] **Step 2: Grep for remaining `.error` checks on hubRequest results**

```bash
grep -n '\.error\b' apps/HubDiagnostics/HubDiagnostics.groovy
```

Expected survivors (legitimate — not from hubRequest sentinels):
- Lines 849–855: `snapNet.*.error` — snapshot read-path guard for legacy stored state (keep)
- Any `fetchHubVariables` error field (line ~2368) — this function builds its own error map and is unrelated to hubRequest

All others should be gone. Investigate any unexpected hits.

- [ ] **Step 3: Bump APP_VERSION (line ~17)**

```groovy
@Field static final String APP_VERSION = "5.22.0"
```

- [ ] **Step 4: Bump UI_VERSION in `hub_diagnostics_ui.html`**

Find `const UI_VERSION` and update to match:
```javascript
const UI_VERSION = "5.22.0";
```

- [ ] **Step 5: Push both files and verify**

```bash
/push @maison-pro
```
Expected: `{"status":"success"}` for both files.

- [ ] **Step 6: Run test suite**

```bash
bash tests/test-hub-diagnostics-api.sh @maison-pro
```
Expected: all tests pass (same count as before — currently 175/175 PASS).

- [ ] **Step 7: Run update.sh**

```bash
/Users/trep/Documents/GitHub/hubitrep/hubitat/HubDiagnostics/update.sh
```

- [ ] **Step 8: Final commit**

```bash
git add apps/HubDiagnostics/HubDiagnostics.groovy apps/HubDiagnostics/hub_diagnostics_ui.html
git commit -m "v5.22.0 — hubRequest contract cleanup: uniform [ok, data, error] for all Map-json callers (#12)"
```

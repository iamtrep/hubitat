/**
 * Hub Diagnostics
 *
 * Comprehensive hub diagnostics: inventory, performance tracking, network analysis,
 * snapshot comparison, and exportable reports.
 
 *
 */

import groovy.transform.Field
import groovy.transform.CompileStatic
import groovy.json.JsonOutput

@Field static final String APP_VERSION = "4.6.0"
@Field static final String STORAGE_SCHEMA_VERSION = "3.2.0"

// API endpoint paths (all relative to HUB_BASE)
@Field static final String HUB_BASE = "http://127.0.0.1:8080"
@Field static final String DEVICES_LIST_PATH = "/hub2/devicesList"
@Field static final String APPS_LIST_PATH = "/hub2/appsList"
@Field static final String APP_FULL_JSON_PATH = "/app/fullJson/"
@Field static final String NETWORK_CONFIG_PATH = "/hub2/networkConfiguration"
@Field static final String ZWAVE_DETAILS_PATH = "/hub/zwaveDetails/json"
@Field static final String ZIGBEE_DETAILS_PATH = "/hub/zigbeeDetails/json"
@Field static final String MATTER_DETAILS_PATH = "/hub/matterDetails/json"
@Field static final String HUB_DATA_PATH = "/hub2/hubData"
@Field static final String MODES_PATH = "/modes/json"
@Field static final String HUB_MESH_PATH = "/hub2/hubMeshJson"
@Field static final String STATE_COMPRESSION_PATH = "/hub/advanced/stateCompressionStatus"
@Field static final String FREE_MEMORY_PATH = "/hub/advanced/freeOSMemoryLast"
@Field static final String RUNTIME_STATS_PATH = "/logs/json"
@Field static final String DATABASE_SIZE_PATH = "/hub/advanced/databaseSize"
@Field static final String INTERNAL_TEMP_PATH = "/hub/advanced/internalTempCelsius"
@Field static final String ZIGBEE_CHILD_ROUTE_PATH = "/hub/zigbee/getChildAndRouteInfo"
@Field static final String ZWAVE_VERSION_PATH = "/hub/zwaveVersion"
@Field static final String EVENT_LIMIT_PATH = "/hub/advanced/event/limit"
@Field static final String MAX_EVENT_AGE_PATH = "/hub/advanced/maxEventAgeDays"
@Field static final String MAX_STATE_AGE_PATH = "/hub/advanced/maxDeviceStateAgeDays"
@Field static final String MEMORY_HISTORY_PATH = "/hub/advanced/freeOSMemoryHistory"

// Zigbee channels recommended to avoid WiFi interference
@Field static final List RECOMMENDED_ZIGBEE_CHANNELS = [15, 20, 25]

// Hub alert flag display names
@Field static final Map ALERT_DISPLAY_NAMES = [
    hubLoadElevated: "Hub Load Elevated",
    hubLoadSevere: "Hub Load Severe",
    hubHighLoad: "Hub High Load",
    hubLowMemory: "Hub Low Memory",
    hubZwaveCrashed: "Z-Wave Radio Crashed",
    zwaveMigrateFailed: "Z-Wave Migration Failed",
    hubLargeishDatabase: "Database Growing Large",
    hubLargeDatabase: "Database Large",
    hubHugeDatabase: "Database Very Large",
    spammyDevices: "Spammy Devices Detected",
    zwaveOffline: "Z-Wave Offline",
    zigbeeOffline: "Zigbee Offline",
    cloudDisconnected: "Cloud Disconnected",
    localBackupFailed: "Local Backup Failed",
    cloudBackupFailed: "Cloud Backup Failed",
    weakZigbee: "Weak Zigbee Channel",
    platformUpdateAvailable: "Platform Update Available"
]

// Protocol constants
@Field static final String PROTOCOL_ZIGBEE = "zigbee"
@Field static final String PROTOCOL_ZWAVE = "zwave"
@Field static final String PROTOCOL_MATTER = "matter"
@Field static final String PROTOCOL_LAN = "lan"
@Field static final String PROTOCOL_VIRTUAL = "virtual"
@Field static final String PROTOCOL_MAKER = "maker"
@Field static final String PROTOCOL_CLOUD = "cloud"
@Field static final String PROTOCOL_HUBMESH = "hubmesh"
@Field static final String PROTOCOL_OTHER = "other"

@Field static final long ONE_DAY_MS = 86400000
@Field static final int API_TIMING_WINDOW = 20

// In-memory API response time tracking (reset on hub reboot)
@Field static Map apiTimings = [:]

// Protocol display names
@Field static final Map PROTOCOL_DISPLAY = [
    "zwave": "Z-Wave",
    "zigbee": "Zigbee",
    "matter": "Matter",
    "hubmesh": "Hub Mesh",
    "lan": "LAN/IP",
    "virtual": "Virtual",
    "cloud": "Cloud",
    "maker": "Maker API",
    "other": "Other"
]

// File names for persistence
@Field static final String SNAPSHOTS_FILE = "hub_diagnostics_snapshots.json"
@Field static final String CHECKPOINTS_FILE = "hub_diagnostics_checkpoints.json"
@Field static final String PERFORMANCE_COMPARISON_FILE = "hub_diagnostics_performance_comparison.json"
@Field static final String SNAPSHOT_DIFF_FILE = "hub_diagnostics_snapshot_diff.json"

@Field static final String IMPORT_URL_APP = "https://raw.githubusercontent.com/hubitrep/hubitat/refs/heads/main/HubDiagnostics/HubDiagnostics.groovy"
@Field static final String IMPORT_URL_WEB = "https://raw.githubusercontent.com/hubitrep/hubitat/refs/heads/main/HubDiagnostics/hub_diagnostics_ui.html"

definition(
    name: "Hub Diagnostics",
    namespace: "hubitrep",
    author: "hubitrep",
    description: "Comprehensive hub diagnostics: inventory, performance tracking, network analysis, and snapshot comparison",
    category: "Utility",
    singleInstance: true,
    importUrl: IMPORT_URL_APP,
    oauth: true,
    iconUrl: "",
    iconX2Url: "",
    iconX3Url: ""
)

preferences {
    page(name: "dashboardPage")
    page(name: "settingsPage")
}

// ===== API MAPPINGS =====

mappings {
    // Frontend asset serving
    path('/ui.html') { action: [GET: 'serveUI'] }

    // Data API endpoints
    path('/api/dashboard')        { action: [GET: 'apiDashboard'] }
    path('/api/devices')          { action: [GET: 'apiDevices'] }
    path('/api/apps')             { action: [GET: 'apiApps'] }
    path('/api/network')          { action: [GET: 'apiNetwork'] }
    path('/api/health')           { action: [GET: 'apiHealth'] }
    path('/api/health/history')   { action: [GET: 'apiHealthHistory'] }
    path('/api/performance')      { action: [GET: 'apiPerformance'] }
    path('/api/snapshots')        { action: [GET: 'apiSnapshots'] }
    path('/api/snapshot/view')    { action: [GET: 'apiSnapshotView'] }
    path('/api/snapshot/diff')    { action: [GET: 'apiSnapshotDiff'] }
    path('/api/stats')            { action: [GET: 'apiStats'] }

    // Actions
    path('/api/snapshot/create')    { action: [POST: 'apiCreateSnapshot'] }
    path('/api/snapshot/delete')    { action: [POST: 'apiDeleteSnapshot'] }
    path('/api/checkpoint/create')  { action: [POST: 'apiCreateCheckpoint'] }
    path('/api/checkpoint/delete')  { action: [POST: 'apiDeleteCheckpoint'] }
    path('/api/checkpoints/clear')  { action: [POST: 'apiClearCheckpoints'] }
    path('/api/snapshots/clear')    { action: [POST: 'apiClearSnapshots'] }
    path('/api/performance/compare') { action: [POST: 'apiPerformanceCompare'] }
    path('/api/ui/sync')              { action: [POST: 'apiSyncUI'] }
    path('/api/version/check')        { action: [GET: 'apiVersionCheck'] }
    path('/api/reports')              { action: [GET: 'apiReports'] }
    path('/api/report/generate')      { action: [POST: 'apiGenerateReport'] }
    path('/api/export/forum')         { action: [GET: 'apiForumExport'] }
}

// ===== PAGE METHODS =====

Map dashboardPage() {
    boolean oauthOk = checkOAuth()
    boolean isFirstRun = (state.installed != true)

    if (!oauthOk) {
        return dynamicPage(name: "dashboardPage", title: "OAuth Required", install: true, uninstall: true) {
            section("OAuth Setup Failed") {
                paragraph "Hub Diagnostics was unable to automatically enable OAuth. Please enable it manually:"
                paragraph "1. Go to <b>Apps Code</b> and open <b>Hub Diagnostics</b>.\n" +
                          "2. Click the <b>three-dot (\u22EE) menu</b> at the top right.\n" +
                          "3. Select <b>OAuth</b>, click <b>Enable oAuth in app</b>, then <b>Update</b>.\n" +
                          "4. Return here and re-open the app."
            }
        }
    }

    if (isFirstRun) {
        return dynamicPage(name: "dashboardPage", title: "Welcome to Hub Diagnostics", install: true, uninstall: true) {
            section("Finalize Installation") {
                paragraph "Thank you for installing Hub Diagnostics! To finish the setup, please click <b>Done</b> at the bottom of this page."
                paragraph "This will initialize the database, sync the user interface, and schedule performance tracking."
                paragraph "<b>Once you click Done, re-open Hub Diagnostics from your Apps list to access the full dashboard.</b>"
            }
            section("Initial Check") {
                paragraph "<b>OAuth Status:</b> <span style='color:green; font-weight:bold;'>Enabled (OK)</span>"
                paragraph "<b>UI Component:</b> ${getUIVersion() == "Unknown" ? "Pending download..." : "Ready"}"
            }
        }
    }

    dynamicPage(name: "dashboardPage", title: "Hub Diagnostics", install: true, uninstall: true) {
        String uiVer = getUIVersion()
        boolean appUpdateNeeded = isNewer(uiVer, APP_VERSION)
        String remoteVersion = checkGithubVersion()
        boolean githubUpdateAvailable = remoteVersion && isNewer(remoteVersion, APP_VERSION)

        section("Versions") {
            if (githubUpdateAvailable) {
                String editorPath = getAppEditorPath()
                String importLink = editorPath ? "<a href='${editorPath}' target='_blank'>Open Apps Code</a> and use Import to update." : "Update via Apps Code using Import."
                paragraph "<span style='color:orange; font-weight:bold;'>\u26A0 New version available:</span> v${remoteVersion} (you have v${APP_VERSION}). ${importLink}"
            }
            if (appUpdateNeeded) {
                paragraph "<span style='color:red; font-weight:bold;'>\u26A0 Update Recommended:</span> A newer UI version (${uiVer}) is active than this App code (${APP_VERSION}). Please update the Groovy App Code in Hubitat."
            }
            paragraph "<b>App Version:</b> ${APP_VERSION}\n<b>UI Version:</b> ${uiVer}"
            if (!githubUpdateAvailable) {
                String editorPath = getAppEditorPath()
                if (editorPath) {
                    paragraph "<a href='${editorPath}' target='_blank'>Open App Code Editor</a> — update the Groovy source code via Import"
                }
            }
        }

        section("Quick Summary") {
            paragraph generateQuickSummary()
        }

        section("Dashboard") {
            String dashboardUrl = "${fullLocalApiServerUrl}/ui.html?access_token=${state.accessToken}"
            href url: dashboardUrl, title: "Open Dashboard", style: "external",
                 description: "Interactive diagnostic dashboard (opens in new tab)"
        }

        section("Settings") {
            href "settingsPage", title: "Settings", description: "Thresholds, auto-scheduling, and options"
        }

        section("Installation") {
            label title: "Assign a name", required: false
        }
    }
}

Map settingsPage() {
    dynamicPage(name: "settingsPage", title: "Settings") {
        section("Automatic Config Snapshots") {
            input "autoSnapshot", "bool", title: "Enable automatic config snapshots", defaultValue: false, submitOnChange: true
            if (autoSnapshot) {
                input "snapshotInterval", "enum", title: "Config snapshot interval",
                    options: ["1": "1 hour", "6": "6 hours", "12": "12 hours", "24": "24 hours"],
                    defaultValue: "24", required: true
            }
            input "maxSnapshots", "number", title: "Maximum config snapshots to retain", defaultValue: 10, range: "1..50", required: true
        }

        section("Automatic Perf Checkpoints") {
            input "autoCheckpoint", "bool", title: "Enable automatic perf checkpoints", defaultValue: false, submitOnChange: true
            if (autoCheckpoint) {
                input "checkpointInterval", "enum", title: "Perf checkpoint interval",
                    options: ["5": "5 minutes", "15": "15 minutes", "30": "30 minutes",
                             "60": "1 hour", "360": "6 hours", "720": "12 hours", "1440": "24 hours"],
                    defaultValue: "60", required: true
            }
            input "maxCheckpoints", "number", title: "Maximum perf checkpoints to keep", defaultValue: 10, range: "1..50", required: true
        }

        section("Device Monitoring") {
            input "inactivityDays", "number", title: "Device inactivity threshold (days)", defaultValue: 7, range: "1..90", required: true
            input "lowBatteryThreshold", "number", title: "Low battery threshold (%)", defaultValue: 20, range: "1..50", required: true
            input "chattyDeviceThreshold", "number", title: "Chatty device threshold (msgs/min)", defaultValue: 10, range: "1..1000", required: true
            paragraph "<i>Devices exceeding this message rate between perf checkpoints will be flagged as chatty.</i>"
        }

        section("Logging") {
            input "debugLogging", "bool", title: "Enable debug logging", defaultValue: false
        }

        section("Export") {
            input "reportLinkMode", "enum", title: "Full report link mode",
                options: ["relative": "Relative (recommended)", "absoluteLocal": "Absolute local IP"],
                defaultValue: "relative", required: true
            paragraph "<i>Relative links work best when opening reports from the hub's /local/ endpoint or through Remote Admin.</i>"
        }

        section("Installation") {
            label title: "Assign a name", required: false
        }
    }
}


// ===== API ENDPOINT METHODS =====

Map jsonResponse(Map data) {
    return render(status: 200, contentType: 'application/json', data: JsonOutput.toJson(data))
}

Map serveUI() {
    if (!checkOAuth()) {
        return render(status: 403, contentType: 'text/plain', data: 'OAuth is not enabled for this app. Please enable it in the Hubitat App Settings.')
    }
    
    // Background sync check (Option 5: once every 24h)
    long lastCheck = state.lastUIUpdateCheck ?: 0
    String uiVer = getUIVersion()
    if (now() - lastCheck > 86400000 || uiVer == "Unknown") {
        logDebug "Auto-syncing UI (Unknown version or >24h)..."
        syncUI(uiVer == "Unknown")
    }

    try {
        String html = loadUITemplate()
        if (!html) {
            logError "hub_diagnostics_ui.html missing from hub. Attempting emergency sync..."
            if (syncUI(true)) html = loadUITemplate()
        }
        if (!html) return render(status: 404, contentType: 'text/plain', data: 'UI file not found. Check hub logs.')

        html = html.replace('${access_token}', state.accessToken)
            .replace('${api_base}', fullLocalApiServerUrl)
        return render(status: 200, contentType: 'text/html', data: html)
    } catch (Exception e) {
        logError "Error serving UI: ${e.message}"
        return render(status: 500, contentType: 'text/plain', data: "Error serving UI: ${e.message}")
    }
}

String checkGithubVersion() {
    long lastCheck = state.lastGithubVersionCheck ?: 0
    if (now() - lastCheck < 3600000 && state.lastGithubVersion) {
        return state.lastGithubVersion
    }
    try {
        String version = null
        httpGet([uri: IMPORT_URL_APP, contentType: "text/plain", timeout: 10]) { resp ->
            String text = resp.data?.text ?: ""
            def m = text =~ /APP_VERSION\s*=\s*"([^"]+)"/
            if (m.find()) version = m.group(1)
        }
        state.lastGithubVersion = version
        state.lastGithubVersionCheck = now()
        return version
    } catch (e) {
        logDebug "GitHub version check failed: ${e.message}"
        return state.lastGithubVersion
    }
}

Map apiSyncUI() {
    logInfo "Manual UI sync requested via API..."
    boolean success = syncUI(true)
    return jsonResponse([success: success])
}

Map apiVersionCheck() {
    String latestVersion = checkGithubVersion()
    if (!latestVersion) return jsonResponse([error: "Unable to check for updates"])

    boolean updateAvailable = isNewer(latestVersion, APP_VERSION)
    return jsonResponse([
        currentVersion: APP_VERSION,
        latestVersion: latestVersion,
        updateAvailable: updateAvailable,
        editorPath: getAppEditorPath()
    ])
}

Map apiDashboard() {
    long start = now()
    Map data = getDashboardData()
    long elapsed = now() - start
    logDebug "apiDashboard completed in ${elapsed}ms"
    recordApiTiming("dashboard", elapsed)
    return jsonResponse(data)
}

String getUIVersion() {
    try {
        byte[] htmlBytes = downloadHubFile('hub_diagnostics_ui.html')
        if (htmlBytes) {
            String html = new String(htmlBytes, 'UTF-8')
            java.util.regex.Matcher m = (html =~ /const UI_VERSION = "([^"]+)"/)
            if (m.find()) return m.group(1)
        }
    } catch (Exception e) {
        logDebug "Error reading UI version: ${e.message}"
    }
    return "Unknown"
}

Map apiDevices() {
    long start = now()
    Map data = getDevicesData()
    long elapsed = now() - start
    logDebug "apiDevices completed in ${elapsed}ms"
    recordApiTiming("devices", elapsed)
    return jsonResponse(data)
}

Map apiApps() {
    long start = now()
    Map data = getAppsData()
    long elapsed = now() - start
    logDebug "apiApps completed in ${elapsed}ms"
    recordApiTiming("apps", elapsed)
    return jsonResponse(data)
}

Map apiNetwork() {
    long start = now()
    Map data = getNetworkData()
    long elapsed = now() - start
    logDebug "apiNetwork completed in ${elapsed}ms"
    recordApiTiming("network", elapsed)
    return jsonResponse(data)
}

Map apiHealth() {
    long start = now()
    Map data = getHealthData()
    long elapsed = now() - start
    logDebug "apiHealth completed in ${elapsed}ms"
    recordApiTiming("health", elapsed)
    return jsonResponse(data)
}

Map apiHealthHistory() {
    List memHistory = fetchMemoryHistory()
    return jsonResponse([dataPoints: memHistory ?: []])
}

Map apiPerformance() {
    long start = now()
    Map data = getPerformanceData()
    long elapsed = now() - start
    logDebug "apiPerformance completed in ${elapsed}ms"
    recordApiTiming("performance", elapsed)
    return jsonResponse(data)
}

Map apiPerformanceCompare() {
    String baseline = params.baseline
    String checkpoint = params.checkpoint
    if (!baseline || !checkpoint) {
        return jsonResponse([success: false, error: "Missing baseline or checkpoint parameter"])
    }

    List checkpoints = loadCheckpoints()
    Map baselineStats
    String baselineLabel
    Map checkpointStats
    String checkpointLabel

    // Resolve baseline
    if (baseline == "startup") {
        // Will build zero baseline after resolving checkpoint
        baselineLabel = "Startup (0:00:00)"
    } else {
        int bIdx = baseline.toInteger()
        if (bIdx < 0 || bIdx >= checkpoints.size()) return jsonResponse([success: false, error: "Invalid baseline index"])
        Map bCp = checkpoints[bIdx]
        baselineStats = bCp.stats
        baselineStats.resources = bCp.resources
        baselineStats.radioStats = bCp.radioStats
        baselineStats.timestampMs = bCp.timestampMs
        baselineLabel = bCp.timestamp
    }

    // Resolve checkpoint
    if (checkpoint == "now") {
        checkpointStats = (Map) hubRequest(RUNTIME_STATS_PATH, "runtime stats")
        Map currentResources = fetchSystemResources()
        checkpointStats.resources = currentResources
        Map zwaveData = (Map) hubRequest(ZWAVE_DETAILS_PATH, "Z-Wave details", "json", 20)
        Map zigbeeData = (Map) hubRequest(ZIGBEE_DETAILS_PATH, "Zigbee details", "json", 20)
        checkpointStats.radioStats = [
            zwave: extractZwaveMessageCounts(zwaveData),
            zigbee: extractZigbeeMessageCounts(zigbeeData)
        ]
        checkpointStats.timestampMs = now()
        checkpointLabel = "Now (${new Date().format('yyyy-MM-dd HH:mm:ss')})"
    } else {
        int cIdx = checkpoint.toInteger()
        if (cIdx < 0 || cIdx >= checkpoints.size()) return jsonResponse([success: false, error: "Invalid checkpoint index"])
        Map cCp = checkpoints[cIdx]
        checkpointStats = cCp.stats
        checkpointStats.resources = cCp.resources
        checkpointStats.radioStats = cCp.radioStats
        checkpointStats.timestampMs = cCp.timestampMs
        checkpointLabel = cCp.timestamp
    }

    // Build zero baseline if startup
    if (baseline == "startup") {
        baselineStats = buildZeroBaseline(checkpointStats, checkpointStats.resources)
    }

    // Save for persistence
    savePerformanceComparisonPayload([
        generatedAt: new Date().format("yyyy-MM-dd HH:mm:ss"),
        baselineLabel: baselineLabel, checkpointLabel: checkpointLabel,
        baselineStats: baselineStats ?: [:], checkpointStats: checkpointStats ?: [:]
    ])

    return jsonResponse([
        success: true,
        baselineLabel: baselineLabel,
        checkpointLabel: checkpointLabel,
        baselineStats: baselineStats,
        checkpointStats: checkpointStats
    ])
}

Map apiSnapshots() {
    return jsonResponse(getSnapshotsData())
}

Map apiSnapshotView() {
    int idx = (params.index ?: "-1").toInteger()
    List snapshots = loadSnapshots()
    if (idx < 0 || idx >= snapshots.size()) return jsonResponse([error: "Invalid snapshot index"])
    Map snap = snapshots[idx]

    return jsonResponse([
        timestamp: snap.timestamp,
        hubInfo: snap.hubInfo,
        systemHealth: snap.systemHealth ? [
            freeOSMemory: snap.systemHealth.memory?.freeOSMemory,
            cpuAvg5min: snap.systemHealth.memory?.cpuAvg5min,
            freeJavaMemory: snap.systemHealth.memory?.freeJavaMemory,
            databaseSize: snap.systemHealth.databaseSize
        ] : null,
        devices: [
            totalDevices: snap.devices?.totalDevices ?: 0,
            activeDevices: snap.devices?.activeDevices ?: 0,
            inactiveDevices: snap.devices?.inactiveDevices ?: 0,
            disabledDevices: snap.devices?.disabledDevices ?: 0,
            byProtocol: snap.devices?.byProtocol,
            allDevices: (snap.devices?.allDevices ?: []).collect { Map dev ->
                [id: dev.id, name: dev.name, type: dev.type, protocol: dev.protocol, status: dev.status]
            }
        ],
        apps: [
            totalApps: snap.apps?.totalApps ?: 0,
            builtInApps: snap.apps?.builtInApps ?: 0,
            userApps: snap.apps?.userApps ?: 0,
            byNamespace: snap.apps?.byNamespace,
            builtInInstances: snap.apps?.builtInInstances,
            userAppsList: snap.apps?.userAppsList,
            parentChildHierarchy: snap.apps?.parentChildHierarchy
        ]
    ])
}

Map apiSnapshotDiff() {
    int olderIdx = (params.older ?: "-1").toInteger()
    boolean newerIsNow = params.newer == "now"
    int newerIdx = newerIsNow ? -1 : (params.newer ?: "-1").toInteger()

    List snapshots = loadSnapshots()
    if (olderIdx < 0 || olderIdx >= snapshots.size()) return jsonResponse([error: "Invalid older snapshot index"])

    Map newer
    if (newerIsNow) {
        createSnapshot()
        snapshots = loadSnapshots()
        newer = snapshots[0]
    } else {
        if (newerIdx < 0 || newerIdx >= snapshots.size()) return jsonResponse([error: "Invalid newer snapshot index"])
        newer = snapshots[newerIdx]
    }
    Map older = snapshots[olderIdx + (newerIsNow ? 1 : 0)]

    // Ensure chronological order
    if ((older.timestampMs ?: 0) > (newer.timestampMs ?: 0)) {
        Map temp = older; older = newer; newer = temp
    }

    // Compute diff
    List olderDevices = older.devices?.allDevices ?: []
    List newerDevices = newer.devices?.allDevices ?: []
    Set olderIds = olderDevices.collect { it.id }.toSet()
    Set newerIds = newerDevices.collect { it.id }.toSet()

    List added = newerDevices.findAll { !olderIds.contains(it.id) }.collect {
        [id: it.id, name: it.name, protocol: PROTOCOL_DISPLAY[it.protocol] ?: it.protocol]
    }
    List removed = olderDevices.findAll { !newerIds.contains(it.id) }.collect {
        [id: it.id, name: it.name, protocol: PROTOCOL_DISPLAY[it.protocol] ?: it.protocol]
    }
    Map olderById = olderDevices.collectEntries { [(it.id): it] }
    List changed = newerDevices.findAll { olderIds.contains(it.id) }.findAll { Map dev ->
        Map old = olderById[dev.id]
        old && (old.status != dev.status || old.protocol != dev.protocol)
    }.collect { Map dev ->
        Map old = olderById[dev.id]
        Map change = [id: dev.id, name: dev.name, changes: []]
        if (old.status != dev.status) change.changes << [field: "status", from: old.status, to: dev.status]
        if (old.protocol != dev.protocol) change.changes << [field: "protocol", from: PROTOCOL_DISPLAY[old.protocol] ?: old.protocol, to: PROTOCOL_DISPLAY[dev.protocol] ?: dev.protocol]
        return change
    }

    // Protocol changes
    Map olderProto = older.devices?.byProtocol ?: [:]
    Map newerProto = newer.devices?.byProtocol ?: [:]
    Set allProtoKeys = (olderProto.keySet() + newerProto.keySet())
    List protocolChanges = allProtoKeys.findAll { (olderProto[it] ?: 0) != (newerProto[it] ?: 0) }.collect { String key ->
        [protocol: PROTOCOL_DISPLAY[key] ?: key, from: olderProto[key] ?: 0, to: newerProto[key] ?: 0]
    }

    // Memory delta
    Long olderMem = older.systemHealth?.memory?.freeOSMemory
    Long newerMem = newer.systemHealth?.memory?.freeOSMemory

    // Save diff for persistence
    saveSnapshotDiffPayload([generatedAt: new Date().format("yyyy-MM-dd HH:mm:ss"), older: older, newer: newer])

    return jsonResponse([
        older: [timestamp: older.timestamp, firmware: older.hubInfo?.firmware],
        newer: [timestamp: newer.timestamp, firmware: newer.hubInfo?.firmware],
        deviceChanges: [
            olderTotal: older.devices?.totalDevices ?: 0,
            newerTotal: newer.devices?.totalDevices ?: 0,
            added: added, removed: removed, changed: changed
        ],
        protocolChanges: protocolChanges,
        appChanges: [
            olderTotal: older.apps?.totalApps ?: 0,
            newerTotal: newer.apps?.totalApps ?: 0
        ],
        memoryDelta: olderMem != null && newerMem != null ? [from: olderMem, to: newerMem] : null
    ])
}

Map apiCreateSnapshot() {
    createSnapshot()
    List snapshots = loadSnapshots()
    return jsonResponse([success: true, snapshotCount: snapshots?.size() ?: 0])
}

Map apiDeleteSnapshot() {
    int idx = (params.index ?: "-1").toInteger()
    if (idx < 0) return jsonResponse([success: false, error: "Invalid index"])
    deleteSnapshot(idx)
    return jsonResponse([success: true])
}

Map apiCreateCheckpoint() {
    createCheckpoint()
    List checkpoints = loadCheckpoints()
    return jsonResponse([success: true, checkpointCount: checkpoints?.size() ?: 0])
}

Map apiDeleteCheckpoint() {
    int idx = (params.index ?: "-1").toInteger()
    if (idx < 0) return jsonResponse([success: false, error: "Invalid index"])
    deleteCheckpoint(idx)
    return jsonResponse([success: true])
}

Map apiClearCheckpoints() {
    clearAllCheckpoints()
    return jsonResponse([success: true])
}

Map apiClearSnapshots() {
    clearAllSnapshots()
    return jsonResponse([success: true])
}

Map apiReports() {
    List reportFiles = listHubFiles("hub_diagnostics_report_")
    String lastReport = safeToString(state.lastReportFile, "")
    return jsonResponse([
        lastReport: lastReport ?: null,
        reports: reportFiles.collect { Map f ->
            [name: f.name, size: f.size, date: f.date]
        }
    ])
}

Map apiGenerateReport() {
    logInfo "Generating report..."
    String timestamp = new Date().format("yyyy-MM-dd HH:mm:ss")
    List memHistory = fetchMemoryHistory()

    Map reportData = [
        _generated: timestamp,
        dashboard: getDashboardData(),
        devices: getDevicesData(),
        apps: getAppsData(),
        network: getNetworkData(),
        health: getHealthData(),
        "health/history": [dataPoints: memHistory ?: []],
        performance: getPerformanceData(),
        snapshots: getSnapshotsData(),
        reports: [lastReport: null, reports: []]
    ]

    String html = loadUITemplate()
    if (!html) return jsonResponse([success: false, error: "SPA template not found in File Manager"])

    String dataJson = JsonOutput.toJson(reportData)
    html = html.replace("</head>", "<script>window.REPORT_DATA=${dataJson}</script>\n</head>")
    html = html.replace('${access_token}', '').replace('${api_base}', '')

    String filename = "hub_diagnostics_report_${new Date().format('yyyyMMdd_HHmmss')}.html"
    writeFile(filename, html)
    state.lastReportFile = filename
    logInfo "Report generated: ${filename} (${(dataJson.length() / 1024).intValue()} KB data)"

    return jsonResponse([success: true, filename: filename])
}

Map apiForumExport() {
    long start = now()

    // Gather all data
    Map hubInfo = getHubInfo()
    Map resources = fetchSystemResources()
    Float temperature = fetchTemperature()
    Integer databaseSize = fetchDatabaseSize()
    Map stateCompression = fetchStateCompression()
    Map eventStateLimits = fetchEventStateLimits()
    List alerts = getStructuredAlerts()
    Map deviceStats = analyzeDevices(false)
    Map appStats = analyzeApps(false)
    Map networkData = analyzeNetwork()
    Map networkConfig = networkData.network ?: [:]
    Map zwaveRaw = networkData.zwave ?: [:]
    Map zigbeeRaw = networkData.zigbee ?: [:]
    Map hubMeshRaw = networkData.hubMesh ?: [:]
    Map zwaveMesh = extractZwaveMeshQuality(zwaveRaw)
    List ghostNodes = buildZwaveGhostNodes(zwaveRaw)
    Map zigbeeMesh = fetchZigbeeMeshInfo()
    String zwaveVersion = fetchZwaveVersion()
    Map stats = (Map) hubRequest(RUNTIME_STATS_PATH, "runtime stats")
    Integer uptimeSeconds = stats ? parseUptime(stats.uptime as String) : null
    float uptimeMin = uptimeSeconds ? uptimeSeconds / 60.0f : 0

    // Radio message counts with rates
    List zwaveMsgCounts = extractZwaveMessageCounts(zwaveRaw)
    List zigbeeMsgCounts = extractZigbeeMessageCounts(zigbeeRaw)
    List allRadioDevices = (zwaveMsgCounts.collect { [name: it.name, deviceId: it.deviceId, msgCount: it.msgCount, protocol: "Z-Wave"] } +
                            zigbeeMsgCounts.collect { [name: it.name, deviceId: it.id, msgCount: it.msgCount, protocol: "Zigbee"] })

    StringBuilder md = new StringBuilder()

    // ── 1. System & Health ──
    md << "### System & Health\n"
    md << "| | |\n|---|---|\n"
    md << "| Model | ${hubInfo.hardware} |\n"
    md << "| Firmware | ${hubInfo.firmware} |\n"
    md << "| Uptime | ${stats?.uptime ?: 'N/A'} |\n"
    String connType = networkConfig.hasEthernet ? "Ethernet" : (networkConfig.hasWiFi ? "WiFi" : "Unknown")
    if (networkConfig.hasEthernet && networkConfig.hasWiFi) connType = "Ethernet + WiFi active"
    if (networkConfig.hasEthernet) connType += networkConfig.usingStaticIP ? " (Static)" : " (DHCP)"
    md << "| Connection | ${connType} |\n"
    md << "| CPU Load (5m) | ${resources ? String.format('%.2f', resources.cpuAvg5min as float) : 'N/A'} |\n"
    md << "| Free OS Memory | ${resources ? formatMemory(resources.freeOSMemory as int) : 'N/A'} |\n"
    md << "| Temperature | ${temperature != null ? String.format('%.1f\u00B0C', temperature) : 'N/A'} |\n"
    md << "| Database | ${databaseSize != null ? "${databaseSize} MB" : 'N/A'} |\n"
    md << "| State Compression | ${stateCompression?.enabled ? 'Enabled' : 'Disabled'} |\n"
    if (eventStateLimits) {
        if (eventStateLimits.maxEvents) md << "| Max Events/Device | ${eventStateLimits.maxEvents} |\n"
        if (eventStateLimits.maxEventAgeDays) md << "| Max Event Age | ${eventStateLimits.maxEventAgeDays} days |\n"
        if (eventStateLimits.maxStateAgeDays) md << "| Max State Age | ${eventStateLimits.maxStateAgeDays} days |\n"
    }

    // Alerts
    if (alerts) {
        md << "\n### Alerts\n"
        alerts.each { Map a -> md << "- **${a.severity}**: ${a.name}\n" }
    } else {
        md << "\nNo active alerts.\n"
    }

    // ── 2. Device Inventory ──
    md << "\n### Devices\n"
    md << "| | |\n|---|---|\n"
    md << "| Total | ${deviceStats.totalDevices} |\n"
    if (deviceStats.disabledDevices) md << "| Disabled | ${deviceStats.disabledDevices} |\n"
    Map byProto = deviceStats.byProtocol ?: [:]
    byProto.each { String proto, int count ->
        if (count > 0) md << "| ${PROTOCOL_DISPLAY[proto] ?: proto} | ${count} |\n"
    }
    List lowBattery = (deviceStats.lowBatteryDevices ?: [])
    if (lowBattery) {
        md << "\n**Low Battery:** " + lowBattery.collect { "${it.name} (${it.battery}%)" }.join(", ") + "\n"
    }

    // ── 3. App Inventory ──
    md << "\n### Apps\n"
    md << "- **Total:** ${appStats.totalApps} (Built-in: ${appStats.builtInApps}, User: ${appStats.userApps})\n"
    // Top 5 apps by CPU from platform stats
    List appRuntimeStats = (appStats.platformApps ?: []) as List
    if (appRuntimeStats) {
        List topApps = appRuntimeStats.sort { -(it.pctTotal ?: 0) as float }.take(5)
        if (topApps.find { ((it.pctTotal ?: 0) as float) != 0 }) {
            md << "\n**Top Apps by CPU:**\n"
            md << "| App | % Busy | Exec Count | Avg (ms) |\n|---|---:|---:|---:|\n"
            topApps.each { Map a ->
                float pct = (a.pctTotal ?: 0) as float
                if (pct > 0) md << "| ${a.name} | ${String.format('%.3f', pct)}% | ${a.count ?: 0} | ${String.format('%.1f', (a.average ?: 0) as float)} |\n"
            }
        }
    }

    // ── 4. Z-Wave Network ──
    if (zwaveRaw && !zwaveRaw.error) {
        md << "\n### Z-Wave\n"
        md << "- **Enabled:** ${zwaveRaw.enabled ? 'Yes' : 'No'}, **Healthy:** ${zwaveRaw.healthy ? 'Yes' : 'No'}\n"
        md << "- **Version:** ${zwaveVersion ?: 'N/A'}, **Region:** ${zwaveRaw.region ?: 'N/A'}\n"
        md << "- **Nodes:** ${(zwaveRaw.zwDevices ?: [:]).size()}\n"
        if (zwaveMesh) {
            md << "- **Avg PER:** ${String.format('%.1f', (zwaveMesh.avgPer ?: 0) as float)}%"
            if (zwaveMesh.avgRssi != null) md << ", **Avg RSSI:** ${zwaveMesh.avgRssi} dBm"
            md << ", **Route Changes:** ${zwaveMesh.totalRouteChanges ?: 0}\n"
        }
        // Ghost nodes
        if (ghostNodes) {
            md << "\n**Ghost Nodes (${ghostNodes.size()}):**\n"
            ghostNodes.each { Map g -> md << "- Node ${g.id}: ${g.name} (${g.status})\n" }
        }
        // Problem nodes
        List problemNodes = (zwaveMesh?.nodes ?: []).findAll { Map n -> n.state != "OK" || (n.per ?: 0) > 1 }
        if (problemNodes) {
            md << "\n**Problem Nodes (${problemNodes.size()}):**\n"
            problemNodes.each { Map n ->
                List issues = []
                if (n.state != "OK") issues << "State: ${n.state}"
                if ((n.per ?: 0) > 1) issues << "PER: ${n.per}%"
                md << "- ${n.name} (Node ${n.nodeId}): ${issues.join(', ')}\n"
            }
        }
        // S0 flagged
        List s0Flagged = (zwaveMesh?.nodes ?: []).findAll { it.s0Flag }
        if (s0Flagged) {
            md << "\n**S0 on non-security devices:** ${s0Flagged.collect { it.name }.join(', ')}\n"
        }
        // Full node table
        List zwNodes = zwaveMesh?.nodes ?: []
        if (zwNodes) {
            md << "\n| Node | Name | Security | RTT | PER | RSSI | Route | Msgs/min | Driver |\n"
            md << "|---:|---|---|---:|---:|---|---|---:|---|\n"
            zwNodes.sort { it.nodeId }.each { Map n ->
                String rttStr = n.rtt != null ? "${n.rtt}ms" : "\u2014"
                String drvStr = n.driverType == "usr" ? "User" : n.driverType == "sys" ? "Built-in" : "\u2014"
                String rateStr = uptimeMin > 0 ? String.format('%.1f', n.msgCount / uptimeMin) : "\u2014"
                md << "| ${n.nodeId} | ${n.name} | ${n.security ?: 'None'} | ${rttStr} | ${n.per}% | ${n.rssiStr ?: '\u2014'} | ${n.route ?: '\u2014'} | ${rateStr} | ${drvStr} |\n"
            }
        }
    }

    // ── 5. Zigbee Network ──
    if (zigbeeRaw && !zigbeeRaw.error && zigbeeRaw.enabled) {
        int totalZb = (zigbeeRaw.devices ?: []).size()
        int responsiveZb = zigbeeRaw.devices ? (zigbeeRaw.devices as List).count { it.active == true } : 0
        md << "\n### Zigbee\n"
        md << "- **Channel:** ${zigbeeRaw.channel ?: 'N/A'}"
        if (zigbeeRaw.channel && ![15, 20, 25].contains(zigbeeRaw.channel)) md << " (not on recommended 15/20/25)"
        md << "\n"
        if (zigbeeRaw.powerLevel != null) md << "- **Power Level:** ${zigbeeRaw.powerLevel}\n"
        md << "- **Devices:** ${totalZb}, **Responsive:** ${responsiveZb}/${totalZb}\n"
        if (zigbeeMesh) {
            int staleCount = (zigbeeMesh.staleNeighbors ?: []).size()
            int weakCount = (zigbeeMesh.weakNeighbors ?: []).size()
            md << "- **Neighbors:** ${zigbeeMesh.neighbors?.size() ?: 0}"
            if (staleCount > 0) md << " (${staleCount} stale)"
            md << "\n"
            if (zigbeeMesh.avgLqi != null) md << "- **LQI:** avg ${zigbeeMesh.avgLqi}, min ${zigbeeMesh.minLqi}, max ${zigbeeMesh.maxLqi}\n"
            if (weakCount > 0) md << "- **Weak Neighbors:** ${(zigbeeMesh.weakNeighbors ?: []).collect { "${it.shortId} LQI:${it.lqi}" }.join(', ')}\n"
            if (staleCount > 0) md << "- **Stale Neighbors:** ${(zigbeeMesh.staleNeighbors ?: []).collect { it.shortId }.join(', ')}\n"
        }
        // Non-responsive devices
        List nonResponsive = zigbeeRaw.devices ? (zigbeeRaw.devices as List).findAll { it.active != true }.collect { it.name ?: "Device ${it.id}" } : []
        if (nonResponsive) {
            md << "- **Non-Responsive:** ${nonResponsive.join(', ')}\n"
        }
    }

    // ── 6. Hub Mesh ──
    if (hubMeshRaw && !hubMeshRaw.error && hubMeshRaw.hubList) {
        List peers = hubMeshRaw.hubList as List
        if (peers) {
            md << "\n### Hub Mesh\n"
            md << "| Hub | IP | Status | Devices |\n|---|---|---|---:|\n"
            peers.each { Map hub ->
                String status = hub.offline ? "Offline" : "Online"
                md << "| ${hub.name} | ${hub.ipAddress} | ${status} | ${hub.deviceIds?.size() ?: 0} |\n"
            }
        }
    }

    // ── 7. Performance ──
    md << "\n### Performance\n"
    md << "- **Uptime:** ${stats?.uptime ?: 'N/A'}\n"
    if (stats?.devicePct) md << "- **Device % Busy:** ${stats.devicePct}\n"
    if (stats?.appPct) md << "- **App % Busy:** ${stats.appPct}\n"

    // Top 5 device types by CPU
    List devRuntimeStats = (stats?.deviceStats ?: []) as List
    if (devRuntimeStats) {
        List topDevTypes = devRuntimeStats.sort { -((it.pctTotal ?: 0) as float) }.take(5)
        if (topDevTypes.find { ((it.pctTotal ?: 0) as float) != 0 }) {
            md << "\n**Top Device Types by CPU:**\n"
            md << "| Device | % Total | Exec Count | Avg (ms) |\n|---|---:|---:|---:|\n"
            topDevTypes.each { Map d ->
                float pct = (d.pctTotal ?: 0) as float
                if (pct > 0) md << "| ${d.name} | ${String.format('%.3f', pct)}% | ${d.count ?: 0} | ${String.format('%.1f', (d.average ?: 0) as float)} |\n"
            }
        }
    }

    // Top 5 app types by CPU
    List appRtStats = (stats?.appStats ?: []) as List
    if (appRtStats) {
        List topAppTypes = appRtStats.sort { -((it.pctTotal ?: 0) as float) }.take(5)
        if (topAppTypes.find { ((it.pctTotal ?: 0) as float) != 0 }) {
            md << "\n**Top App Types by CPU:**\n"
            md << "| App | % Total | Exec Count | Avg (ms) |\n|---|---:|---:|---:|\n"
            topAppTypes.each { Map a ->
                float pct = (a.pctTotal ?: 0) as float
                if (pct > 0) md << "| ${a.name} | ${String.format('%.3f', pct)}% | ${a.count ?: 0} | ${String.format('%.1f', (a.average ?: 0) as float)} |\n"
            }
        }
    }

    // Top talkers by message rate
    if (allRadioDevices && uptimeMin > 0) {
        List topTalkers = allRadioDevices.sort { -it.msgCount }.take(5)
        md << "\n**Top Talkers:**\n"
        md << "| Device | Protocol | Msgs/min | Total Msgs |\n|---|---|---:|---:|\n"
        topTalkers.each { Map t ->
            String rate = String.format('%.1f', t.msgCount / uptimeMin)
            md << "| ${t.name} | ${t.protocol} | ${rate} | ${t.msgCount} |\n"
        }
        // Spammy alerts (>= 6/min)
        List spammy = allRadioDevices.findAll { it.msgCount / uptimeMin >= 6.0 }
        if (spammy) {
            md << "\n**Elevated message rate (\u22656/min):** ${spammy.collect { "${it.name} (${String.format('%.1f', it.msgCount / uptimeMin)}/min)" }.join(', ')}\n"
        }
    }

    md << "\n---\n*Generated by Hub Diagnostics v${APP_VERSION}*\n"

    long elapsed = now() - start
    recordApiTiming("export/forum", elapsed)
    return jsonResponse([success: true, markdown: md.toString()])
}

// ===== DATA GATHERERS =====
// Each returns a plain Map suitable for both jsonResponse() and report embedding.

Map getDashboardData() {
    Map deviceStats = analyzeDevices(false)
    Map appStats = analyzeApps(false)
    Map hubInfo = getHubInfo()
    Map resources = fetchSystemResources()
    Float temperature = fetchTemperature()
    Integer databaseSize = fetchDatabaseSize()
    return [
        hub: hubInfo, appVersion: APP_VERSION, uiVersion: getUIVersion(),
        devices: [
            total: deviceStats.totalDevices, active: deviceStats.activeDevices,
            inactive: deviceStats.inactiveDevices, disabled: deviceStats.disabledDevices,
            byProtocol: deviceStats.byProtocol, idsByProtocol: deviceStats.idsByProtocol,
            idsByStatus: deviceStats.idsByStatus
        ],
        apps: [total: appStats.totalApps, builtIn: appStats.builtInApps, user: appStats.userApps],
        resources: resources, temperature: temperature, databaseSize: databaseSize,
        alerts: getStructuredAlerts(), inactivityDays: settings.inactivityDays ?: 7
    ]
}

Map getDevicesData() {
    Map deviceStats = analyzeDevices()
    List deviceRows = (deviceStats.allDevices ?: []).collect { Map dev ->
        [id: dev.id, name: dev.name, type: dev.type, protocol: dev.protocol,
         protocolDisplay: PROTOCOL_DISPLAY[dev.protocol] ?: (dev.protocol ?: "").toString().capitalize(),
         room: dev.room, status: dev.status ?: "", lastActivity: dev.lastActivity ?: "Never",
         battery: dev.battery, parentAppId: dev.parentAppId, parentAppName: dev.parentAppName,
         parentDeviceId: dev.parentDeviceId, parentDeviceName: dev.parentDeviceName,
         userType: dev.userType ?: false, deviceTypeId: dev.deviceTypeId]
    }
    List lowBattery = (deviceStats.lowBatteryDevices ?: []).collect { Map dev ->
        [id: dev.id, name: dev.name, battery: dev.battery]
    }
    return [
        summary: [totalDevices: deviceStats.totalDevices, activeDevices: deviceStats.activeDevices,
                  inactiveDevices: deviceStats.inactiveDevices, disabledDevices: deviceStats.disabledDevices,
                  parentDevices: deviceStats.parentDevices, childDevices: deviceStats.childDevices,
                  linkedDevices: deviceStats.linkedDevices, batteryDevices: deviceStats.batteryDevices,
                  parentIds: deviceStats.parentIds, childIds: deviceStats.parentIds + deviceStats.childIds,
                  linkedIds: deviceStats.linkedIds, batteryIds: deviceStats.batteryIds],
        byProtocol: deviceStats.byProtocol, idsByProtocol: deviceStats.idsByProtocol,
        byType: deviceStats.byType, idsByType: deviceStats.idsByType, idsByStatus: deviceStats.idsByStatus,
        deviceRows: deviceRows, lowBatteryDevices: lowBattery,
        inactivityDays: settings.inactivityDays ?: 7
    ]
}

Map getAppsData() {
    Map appStats = analyzeApps()
    List platformRows = (appStats.platformApps ?: []).collect { Map app ->
        [id: app.id, name: app.name, stateSize: app.stateSize as int, pctTotal: app.pctTotal,
         total: app.total, count: app.count, average: app.average,
         hubActionCount: app.hubActionCount, cloudCallCount: app.cloudCallCount]
    }
    List userAppRows = (appStats.userAppsList ?: [])
        .sort { (it.label ?: it.name ?: "").toString().toLowerCase() }
        .collect { [id: it.id, label: it.label ?: it.name, type: it.name,
                    parentId: it.parentAppId, disabled: it.state == "disabled"] }
    return [
        summary: [totalApps: appStats.totalApps, builtInApps: appStats.builtInApps, userApps: appStats.userApps,
                  parentApps: appStats.parentApps, childApps: appStats.childApps,
                  runtimeTotalApps: appStats.runtimeTotalApps],
        byNamespace: appStats.byNamespace, platformApps: platformRows,
        userApps: userAppRows, parentChildHierarchy: appStats.parentChildHierarchy
    ]
}

Map getNetworkData() {
    Map networkData = analyzeNetwork()
    Map stats = (Map) hubRequest(RUNTIME_STATS_PATH, "runtime stats")
    Integer uptimeSeconds = stats ? parseUptime(stats.uptime as String) : null
    Map zigbeeMesh = fetchZigbeeMeshInfo()
    String zwaveVersion = fetchZwaveVersion()
    Map zwaveMesh = extractZwaveMeshQuality(networkData.zwave ?: [:])
    List ghostNodes = buildZwaveGhostNodes(networkData.zwave ?: [:])
    List problemNodes = (zwaveMesh?.nodes ?: []).findAll { Map n -> n.state != "OK" || (n.per ?: 0) > 1 }.collect { Map n ->
        List issues = []
        if (n.state != "OK") issues << "State: ${n.state}"
        if ((n.per ?: 0) > 1) issues << "PER: ${n.per}%"
        [name: n.name, deviceId: n.deviceId, nodeId: n.nodeId, issues: issues.join(", ")]
    }
    Map zigbeeRaw = networkData.zigbee ?: [:]
    List nonResponsive = zigbeeRaw.devices ? zigbeeRaw.devices.findAll { it.active != true }.collect { [id: it.id, name: it.name ?: "Device ${it.id}"] } : []
    Map hubMeshRaw = networkData.hubMesh ?: [:]
    List hubMeshPeers = hubMeshRaw.hubList ? hubMeshRaw.hubList.collect { Map hub ->
        [name: hub.name, ip: hub.ipAddress, offline: hub.offline,
         deviceCount: hub.deviceIds?.size() ?: 0, varCount: hub.hubVarNames?.size() ?: 0]
    } : []
    return [
        uptimeSeconds: uptimeSeconds,
        network: networkData.network && !networkData.network.error ? networkData.network : null,
        zwave: networkData.zwave && !networkData.zwave.error ? [
            enabled: networkData.zwave.enabled, healthy: networkData.zwave.healthy,
            region: networkData.zwave.region, nodeCount: (networkData.zwave.zwDevices ?: [:]).size(),
            isRadioUpdateNeeded: networkData.zwave.isRadioUpdateNeeded,
            zwaveJs: networkData.zwave.zwaveJs, version: zwaveVersion,
            mesh: zwaveMesh, ghostNodes: ghostNodes, problemNodes: problemNodes,
            messageCounts: extractZwaveMessageCounts(networkData.zwave ?: [:])
        ] : null,
        zigbee: networkData.zigbee && !networkData.zigbee.error ? [
            enabled: zigbeeRaw.enabled, healthy: zigbeeRaw.healthy,
            networkState: zigbeeRaw.networkState, channel: zigbeeRaw.channel,
            panId: zigbeeRaw.panId, extendedPanId: zigbeeRaw.extendedPanId,
            deviceCount: (zigbeeRaw.devices ?: []).size(), joinMode: zigbeeRaw.inJoinMode,
            powerLevel: zigbeeRaw.powerLevel,
            responsiveCount: zigbeeRaw.devices ? zigbeeRaw.devices.count { it.active == true } : 0,
            totalCount: (zigbeeRaw.devices ?: []).size(), nonResponsive: nonResponsive,
            messageCounts: extractZigbeeMessageCounts(networkData.zigbee ?: [:]),
            mesh: zigbeeMesh ? [
                neighbors: zigbeeMesh.neighbors?.size() ?: 0, routes: zigbeeMesh.routes?.size() ?: 0,
                avgLqi: zigbeeMesh.avgLqi, minLqi: zigbeeMesh.minLqi, maxLqi: zigbeeMesh.maxLqi,
                neighborDetails: (zigbeeMesh.neighbors ?: []).collect { [shortId: it.shortId, lqi: it.lqi, age: it.age, inCost: it.inCost, outCost: it.outCost, stale: it.stale ?: false] },
                weakNeighbors: (zigbeeMesh.weakNeighbors ?: []).collect { [shortId: it.shortId, lqi: it.lqi] },
                staleNeighbors: (zigbeeMesh.staleNeighbors ?: []).collect { [shortId: it.shortId, age: it.age] },
                childDevices: zigbeeMesh.childDevices?.size() ?: 0
            ] : null
        ] : null,
        matter: networkData.matter && !networkData.matter.error ? networkData.matter : null,
        hubMesh: networkData.hubMesh && !networkData.hubMesh.error ? [
            enabled: hubMeshRaw.hubMeshEnabled != null ? hubMeshRaw.hubMeshEnabled : hubMeshRaw.enabled,
            sharedDevices: hubMeshRaw.sharedDevices?.size() ?: 0,
            linkedDevices: hubMeshRaw.linkedDevices?.size() ?: 0,
            sharedVars: hubMeshRaw.sharedVars?.size() ?: 0, linkedVars: hubMeshRaw.linkedVars?.size() ?: 0,
            peers: hubMeshPeers
        ] : null
    ]
}

Map getHealthData() {
    Map systemHealth = analyzeSystemHealth()
    Map hubInfo = getHubInfo()
    def hub = (location.hubs && location.hubs.size() > 0) ? location.hubs[0] : null
    Map mem = systemHealth.memory ?: [:]
    return [
        hub: [name: hubInfo.name, hubId: hub?.id, hardware: hubInfo.hardware,
              firmware: hubInfo.firmware, ip: hubInfo.ip, zigbeeId: hub?.zigbeeId,
              location: location.name, mode: location.currentMode?.toString(),
              timeZone: location.timeZone?.ID],
        resources: mem ?: null, temperature: systemHealth.temperature,
        databaseSize: systemHealth.databaseSize, stateCompression: systemHealth.stateCompression,
        eventStateLimits: systemHealth.eventStateLimits, alerts: getStructuredAlerts()
    ]
}

Map getPerformanceData() {
    Map stats = (Map) hubRequest(RUNTIME_STATS_PATH, "runtime stats")
    Map resources = fetchSystemResources()
    Map zwaveData = (Map) hubRequest(ZWAVE_DETAILS_PATH, "Z-Wave details", "json", 20)
    Map zigbeeData = (Map) hubRequest(ZIGBEE_DETAILS_PATH, "Zigbee details", "json", 20)
    List zwaveMsgCounts = extractZwaveMessageCounts(zwaveData)
    List zigbeeMsgCounts = extractZigbeeMessageCounts(zigbeeData)
    Map radioStats = [zwave: zwaveMsgCounts, zigbee: zigbeeMsgCounts]

    // Top talkers: top 3 devices by message count across both radios
    List allRadioDevices = (zwaveMsgCounts.collect { [name: it.name, deviceId: it.deviceId, msgCount: it.msgCount, protocol: "Z-Wave"] } +
                            zigbeeMsgCounts.collect { [name: it.name, deviceId: it.id, msgCount: it.msgCount, protocol: "Zigbee"] })
    List topTalkers = allRadioDevices.sort { -it.msgCount }.take(3)

    if (stats) {
        stats.radioStats = radioStats
        stats.uptimeSeconds = parseUptime(stats.uptime as String)
    }
    List checkpoints = loadCheckpoints()
    return [
        stats: stats, resources: resources,
        topTalkers: topTalkers,
        checkpointCount: checkpoints?.size() ?: 0,
        maxCheckpoints: (settings.maxCheckpoints ?: 10) as int,
        checkpoints: (checkpoints ?: []).collect { Map cp -> [
            timestamp: cp.timestamp, timestampMs: cp.timestampMs,
            stats: cp.stats, resources: cp.resources, radioStats: cp.radioStats
        ]},
        savedComparison: loadPerformanceComparisonPayload()
    ]
}

Map getSnapshotsData() {
    List snapshots = loadSnapshots()
    return [
        snapshotCount: snapshots?.size() ?: 0,
        maxSnapshots: (settings.maxSnapshots ?: 10) as int,
        snapshots: (snapshots ?: []).collect { Map snap -> [
            timestamp: snap.timestamp, hubInfo: snap.hubInfo,
            devices: [totalDevices: snap.devices?.totalDevices ?: 0, activeDevices: snap.devices?.activeDevices ?: 0,
                      inactiveDevices: snap.devices?.inactiveDevices ?: 0, disabledDevices: snap.devices?.disabledDevices ?: 0],
            apps: [totalApps: snap.apps?.totalApps ?: 0, builtInApps: snap.apps?.builtInApps ?: 0, userApps: snap.apps?.userApps ?: 0],
            memory: snap.systemHealth?.memory?.freeOSMemory
        ]}
    ]
}

List getStructuredAlerts() {
    List alerts = []
    Map resources = fetchSystemResources()
    Float temperature = fetchTemperature()
    Map hubAlerts = fetchHubAlerts()

    // Calculated alerts
    if (resources && resources.freeOSMemory < 76800) {
        alerts << [severity: "critical", name: "OS memory critically low (${formatMemory(resources.freeOSMemory)})"]
    } else if (resources && resources.freeOSMemory < 102400) {
        alerts << [severity: "warning", name: "Low OS memory (${formatMemory(resources.freeOSMemory)})"]
    }
    
    if (resources && (resources.cpuAvg5min ?: 0) > 8.0) {
        alerts << [severity: "critical", name: "Very high CPU load (${String.format('%.2f', resources.cpuAvg5min as float)})"]
    } else if (resources && (resources.cpuAvg5min ?: 0) > 4.0) {
        alerts << [severity: "warning", name: "Elevated CPU load (${String.format('%.2f', resources.cpuAvg5min as float)})"]
    }
    
    if (temperature != null && temperature > 77) {
        alerts << [severity: "critical", name: "Hub temperature very high (${String.format('%.1f', temperature)}\u00B0C)"]
    } else if (temperature != null && temperature > 50) {
        alerts << [severity: "warning", name: "Hub temperature elevated (${String.format('%.1f', temperature)}\u00B0C)"]
    }

    // Platform alerts
    if (hubAlerts?.alerts) {
        ALERT_DISPLAY_NAMES.each { String key, String displayName ->
            if (hubAlerts.alerts[key] == true) {
                String severity = (key in ["hubLoadSevere", "hubZwaveCrashed", "hubHugeDatabase", "zwaveOffline", "zigbeeOffline"]) ? "critical" : "warning"
                alerts << [key: key, name: displayName, severity: severity]
            }
        }
    }
    if (hubAlerts?.spammyDevicesMessage) {
        alerts << [key: "spammyDevices", name: "Spammy Devices", severity: "warning", message: hubAlerts.spammyDevicesMessage]
    }
    
    return alerts
}

// ===== BUTTON HANDLER =====

// ===== API TIMING =====

void recordApiTiming(String endpoint, long elapsedMs) {
    synchronized (apiTimings) {
        Map entry = apiTimings[endpoint]
        if (!entry) {
            entry = [samples: [], median: 0, count: 0]
            apiTimings[endpoint] = entry
        }
        List samples = entry.samples
        samples << elapsedMs
        if (samples.size() > API_TIMING_WINDOW) {
            samples.remove(0)
        }
        entry.count = (entry.count as int) + 1
        List sorted = samples.collect().sort()
        int mid = sorted.size() / 2
        entry.median = sorted.size() % 2 == 0 ? ((sorted[mid - 1] + sorted[mid]) / 2) as long : sorted[mid]
    }
}

Map apiStats() {
    Map stats = [:]
    synchronized (apiTimings) {
        apiTimings.each { String endpoint, Map entry ->
            stats[endpoint] = [
                median: entry.median,
                count: entry.count,
                recent: entry.samples.size(),
                lastSamples: entry.samples.collect()
            ]
        }
    }
    return jsonResponse([timings: stats])
}

// ===== DATA COLLECTION =====

/**
 * Unified HTTP request to the local hub. Replaces fetchEndpoint, fetchPlainText, etc.
 * @param path    URL path (e.g., DEVICES_LIST_PATH)
 * @param name    Human-readable label for logging
 * @param type    "json" returns parsed Map, "text" returns raw String
 * @param timeout Request timeout in seconds
 * @return For json: Map (or [error:true, message:...] on failure). For text: String (or null on failure).
 */
private Object hubRequest(String path, String name, String type = "json", int timeout = 30) {
    long start = now()
    try {
        Map params = [
            uri: HUB_BASE, path: path,
            contentType: type == "json" ? "application/json" : "text/plain",
            timeout: timeout
        ]
        Object result = null
        httpGet(params) { resp ->
            if (resp.success) {
                if (type == "json") {
                    result = resp.data
                } else {
                    result = resp.data?.text?.trim() ?: resp.data?.toString()?.trim()
                }
            }
        }
        logDebug "Fetched ${name} in ${now() - start}ms"
        return type == "json" ? (result ?: [:]) : result
    } catch (Exception e) {
        if (type == "json") {
            logError "Error fetching ${name} (${now() - start}ms): ${getObjectClassName(e)}: ${e.message}"
            return [error: true, message: e.message]
        } else {
            logDebug "Error fetching ${name}: ${e.message}"
            return null
        }
    }
}

Map fetchSystemResources() {
    String text = (String) hubRequest(FREE_MEMORY_PATH, "system resources", "text", 15)
    if (!text) return null
    try {
        String[] lines = text.split('\n')
        if (lines.size() > 1) {
            String[] values = lines[1].split(',')
            if (values.size() >= 6) {
                return [
                    timestamp: values[0].trim(),
                    freeOSMemory: values[1].trim().toInteger(),
                    cpuAvg5min: values[2].trim().toFloat(),
                    totalJavaMemory: values[3].trim().toInteger(),
                    freeJavaMemory: values[4].trim().toInteger(),
                    directJavaMemory: values[5].trim().toInteger()
                ]
            }
        }
    } catch (Exception e) {
        logError "Error parsing system resources: ${e.message}"
    }
    return null
}

List fetchMemoryHistory() {
    String text = (String) hubRequest(MEMORY_HISTORY_PATH, "memory history", "text", 30)
    if (!text) return []
    List dataPoints = []
    try {
        String[] lines = text.split('\n')
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].trim()
            if (!line) continue
            String[] parts = line.split(',')
            if (parts.length >= 6) {
                dataPoints << [
                    time: parts[0].trim(),
                    freeOS: parts[1].trim().toInteger(),
                    cpuLoad: parts[2].trim().toFloat(),
                    freeJava: parts[4].trim().toInteger(),
                    directJava: parts[5].trim().toInteger()
                ]
            }
        }
    } catch (Exception e) {
        logError "Error parsing memory history: ${e.message}"
    }
    return dataPoints
}

Map fetchStateCompression() {
    String text = (String) hubRequest(STATE_COMPRESSION_PATH, "state compression", "text", 10)
    if (!text) return [enabled: false, status: "unavailable"]
    return [enabled: text.toLowerCase() == "enabled", status: text]
}

Integer fetchDatabaseSize() {
    String text = (String) hubRequest(DATABASE_SIZE_PATH, "database size", "text")
    if (text) { try { return text.toInteger() } catch (Exception e) { /* ignore */ } }
    return null
}

Float fetchTemperature() {
    String text = (String) hubRequest(INTERNAL_TEMP_PATH, "internal temperature", "text")
    if (text) { try { return text.toFloat() } catch (Exception e) { /* ignore */ } }
    return null
}

Map fetchHubAlerts() {
    Map hubData = (Map) hubRequest(HUB_DATA_PATH, "hub data", "json", 10)
    if (!hubData || hubData.error) return [:]
    return [
        alerts: hubData.alerts ?: [:],
        databaseSize: hubData.alerts?.databaseSize,
        spammyDevicesMessage: hubData.spammyDevicesMessage,
        devMode: hubData.baseModel?.devMode ?: false
    ]
}

Map fetchEventStateLimits() {
    String eventLimit = (String) hubRequest(EVENT_LIMIT_PATH, "event limit", "text")
    String maxEventAge = (String) hubRequest(MAX_EVENT_AGE_PATH, "max event age", "text")
    String maxStateAge = (String) hubRequest(MAX_STATE_AGE_PATH, "max state age", "text")
    Map limits = [:]
    if (eventLimit) {
        java.util.regex.Matcher m = (eventLimit =~ /\[(\d+)\]/)
        if (m.find()) limits.maxEvents = m.group(1).toInteger()
    }
    if (maxEventAge) { try { limits.maxEventAgeDays = maxEventAge.toInteger() } catch (Exception e) { /* ignore */ } }
    if (maxStateAge) { try { limits.maxStateAgeDays = maxStateAge.toInteger() } catch (Exception e) { /* ignore */ } }
    return limits
}

Map fetchZigbeeMeshInfo() {
    String text = (String) hubRequest(ZIGBEE_CHILD_ROUTE_PATH, "zigbee child/route info", "text", 15)
    if (!text) return [:]

    Map result = [childDevices: [], neighbors: [], routes: [], raw: text]

    String currentSection = ""
    text.split('\n').each { String line ->
        line = line.trim()
        if (!line) return

        if (line.startsWith("Child Table")) {
            currentSection = "child"
        } else if (line.startsWith("Neighbor Table")) {
            currentSection = "neighbor"
        } else if (line.startsWith("Route Table")) {
            currentSection = "route"
        } else if (currentSection == "neighbor" && line.contains("LQI:")) {
            // Parse neighbor entries like: "0xABCD (Name) LQI: 255 age: 3 inCost: 1 outCost: 1"
            Map neighbor = [raw: line]
            java.util.regex.Matcher lqiMatch = (line =~ /LQI:\s*(\d+)/)
            java.util.regex.Matcher ageMatch = (line =~ /age:\s*(\d+)/)
            java.util.regex.Matcher idMatch = (line =~ /^(0x[0-9A-Fa-f]+)/)
            java.util.regex.Matcher inCostMatch = (line =~ /inCost:\s*(\d+)/)
            java.util.regex.Matcher outCostMatch = (line =~ /outCost:\s*(\d+)/)
            if (lqiMatch.find()) neighbor.lqi = lqiMatch.group(1).toInteger()
            if (ageMatch.find()) neighbor.age = ageMatch.group(1).toInteger()
            if (idMatch.find()) neighbor.shortId = idMatch.group(1)
            if (inCostMatch.find()) neighbor.inCost = inCostMatch.group(1).toInteger()
            if (outCostMatch.find()) neighbor.outCost = outCostMatch.group(1).toInteger()
            neighbor.stale = (neighbor.age != null && neighbor.age >= 7)
            result.neighbors << neighbor
        } else if (currentSection == "child") {
            result.childDevices << [raw: line]
        } else if (currentSection == "route") {
            result.routes << [raw: line]
        }
    }

    // Compute mesh stats
    if (result.neighbors) {
        List lqiValues = result.neighbors.findAll { it.lqi != null }.collect { it.lqi }
        if (lqiValues) {
            result.avgLqi = (lqiValues.sum() / lqiValues.size()).toInteger()
            result.minLqi = lqiValues.min()
            result.maxLqi = lqiValues.max()
            result.weakNeighbors = result.neighbors.findAll { it.lqi != null && it.lqi < 150 }
            result.staleNeighbors = result.neighbors.findAll { it.age != null && it.age > 6 }
        }
    }

    return result
}

String fetchZwaveVersion() {
    String raw = (String) hubRequest(ZWAVE_VERSION_PATH, "Z-Wave version", "text")
    return parseZWaveVersion(raw)
}

String parseZWaveVersion(String raw) {
    if (!raw || raw == "N/A" || !raw.contains("VersionReport")) return raw
    // Extract SDK version if present in targetVersions or protocol version
    // Example: VersionReport(..., zWaveProtocolVersion:7, zWaveProtocolSubVersion:23, ..., targetVersions:[[target:1, version:7, subVersion:18]])
    def mProtocol = raw =~ /zWaveProtocolVersion:(\d+), zWaveProtocolSubVersion:(\d+)/
    def mTarget = raw =~ /targetVersions:\[\[target:1, version:(\d+), subVersion:(\d+)\]\]/
    
    String protocolVer = mProtocol ? "${mProtocol[0][1]}.${mProtocol[0][2]}" : ""
    String sdkVer = mTarget ? "${mTarget[0][1]}.${mTarget[0][2]}" : ""
    
    if (sdkVer) return "${sdkVer} (Protocol ${protocolVer})"
    if (protocolVer) return protocolVer
    return raw
}

Map extractZwaveMeshQuality(Map zwaveData) {
    if (!zwaveData || zwaveData.error || !zwaveData.nodes) return [:]

    List nodes = []
    int totalPer = 0
    int nodesWithErrors = 0
    int totalRouteChanges = 0
    int rssiCount = 0
    int rssiSum = 0

    zwaveData.nodes.each { Map node ->
        int per = (node.per ?: 0) as int
        int neighborCount = (node.neighbors ?: 0) as int
        int routeChanges = (node.routeChanges ?: 0) as int
        String rssiStr = node.lwrRssi ?: ""
        Integer rssiVal = null
        if (rssiStr) {
            java.util.regex.Matcher m = (rssiStr =~ /(-?\d+)/)
            if (m.find()) {
                rssiVal = m.group(1).toInteger()
                rssiSum += rssiVal
                rssiCount++
            }
        }

        totalPer += per
        if (per > 0) nodesWithErrors++
        totalRouteChanges += routeChanges

        // averageRtt: integer ms or empty string when unavailable
        String rttRaw = (node.averageRtt != null) ? node.averageRtt.toString() : ""
        Integer rtt = (rttRaw && rttRaw.isInteger()) ? rttRaw.toInteger() : null

        // S0 flag: S0 on a device that isn't a lock or garage door is noteworthy overhead
        String security = node.security ?: ""
        boolean s0Flag = false
        if (security.toLowerCase().contains("s0")) {
            String zwType = (node.zwaveType ?: "").toUpperCase()
            boolean isSecurityDevice = zwType.contains("DOOR_LOCK") || zwType.contains("GARAGE") || zwType.contains("BARRIER")
            s0Flag = !isSecurityDevice
        }

        // driverType from zwDevices (keyed by node ID string)
        String driverType = ""
        if (zwaveData.zwDevices) {
            Map devEntry = zwaveData.zwDevices[node.nodeId.toString()]
            if (devEntry) driverType = devEntry.driverType ?: ""
        }

        nodes << [
            nodeId: node.nodeId,
            deviceId: node.deviceId,
            name: node.deviceName ?: "Node ${node.nodeId}",
            msgCount: (node.msgCount ?: 0) as int,
            rssi: rssiVal,
            rssiStr: rssiStr,
            rtt: rtt,
            per: per,
            neighbors: neighborCount,
            route: node.route ?: "",
            routeChanges: routeChanges,
            state: node.nodeState ?: "Unknown",
            lastTime: node.lastTime ?: "",
            listening: node.listening ?: false,
            security: security,
            s0Flag: s0Flag,
            driverType: driverType
        ]
    }

    return [
        nodes: nodes,
        nodeCount: nodes.size(),
        avgPer: nodes.size() > 0 ? (totalPer / nodes.size()).toFloat() : 0,
        nodesWithErrors: nodesWithErrors,
        totalRouteChanges: totalRouteChanges,
        avgRssi: rssiCount > 0 ? (rssiSum / rssiCount).toInteger() : null
    ]
}

List extractZwaveMessageCounts(Map zwaveData) {
    if (!zwaveData || zwaveData.error || !zwaveData.nodes) return []
    return zwaveData.nodes.collect { Map node ->
        [id: node.nodeId, deviceId: node.deviceId, name: node.deviceName ?: "Node ${node.nodeId}",
         msgCount: (node.msgCount ?: 0) as int, routeChanges: (node.routeChanges ?: 0) as int]
    }
}

List extractZigbeeMessageCounts(Map zigbeeData) {
    if (!zigbeeData || zigbeeData.error || !zigbeeData.devices) return []
    return zigbeeData.devices.collect { Map device ->
        [id: device.id, name: device.name ?: "Device ${device.id}",
         msgCount: (device.messageCount ?: 0) as int]
    }
}

// ===== ANALYSIS MODULES =====

Map analyzeDevices(boolean deep = true) {
    Map response = (Map) hubRequest(DEVICES_LIST_PATH, "devices list")

    if (!response || response.error || !response.devices) {
        logWarn "Failed to fetch devices list"
        return getEmptyDeviceStats()
    }

    List devicesList = flattenDeviceEntries(response.devices as List, deep)

    Map stats = getEmptyDeviceStats()

    long inactivityThresholdMs = now() - ((settings.inactivityDays ?: 7) * ONE_DAY_MS)
    Map radioProtocols = buildRadioProtocolMap()
    Map appLookup = deep ? buildAppLookupMap() : [:]

    devicesList.each { deviceEntry ->
        try {
            Map device = deviceEntry.data
            if (!device || !(device instanceof Map)) return

            stats.totalDevices++

            Long lastActivity = null
            try {
                if (device.lastActivity && !(device.lastActivity instanceof Boolean)) {
                    lastActivity = parseDate(device.lastActivity)
                }
            } catch (Exception e) { /* ignore */ }

            if (device.disabled) {
                stats.disabledDevices++
                stats.idsByStatus.disabled << device.id
                stats.inactiveDevices++
            } else if (lastActivity && lastActivity > inactivityThresholdMs) {
                stats.activeDevices++
                stats.idsByStatus.active << device.id
            } else {
                stats.inactiveDevices++
                stats.idsByStatus.inactive << device.id
            }

            // Protocol detection — strictly authoritative for radio/mesh
            String protocol = PROTOCOL_OTHER
            String nativeProtocol = safeToString(device.protocol, "").toLowerCase()
            
            if (nativeProtocol == "zigbee") protocol = PROTOCOL_ZIGBEE
            else if (nativeProtocol == "zwave") protocol = PROTOCOL_ZWAVE
            else if (nativeProtocol == "matter") protocol = PROTOCOL_MATTER
            else if (nativeProtocol == "lan") protocol = PROTOCOL_LAN
            else if (device.linked == true) protocol = PROTOCOL_HUBMESH
            else if (radioProtocols.containsKey(device.id)) protocol = radioProtocols[device.id]
            else {
                // If it's not on a radio, only check for Virtual/Cloud/LAN/Maker
                protocol = determineProtocolQuick(device)
            }
            stats.byProtocol[protocol] = (stats.byProtocol[protocol] ?: 0) + 1
            if (stats.idsByProtocol[protocol] != null) stats.idsByProtocol[protocol] << device.id

            // Deep-only: parent/child, battery, type breakdown, full device list
            if (deep) {
                if (deviceEntry.parent == true) { stats.parentDevices++; stats.parentIds << device.id }
                if (deviceEntry.child == true) { stats.childDevices++; stats.childIds << device.id }
                if (device.linked == true) { stats.linkedDevices++; stats.linkedIds << device.id }

                String typeName = safeToString(device.type, "Unknown")
                stats.byType[typeName] = (stats.byType[typeName] ?: 0) + 1
                if (!stats.idsByType.containsKey(typeName)) stats.idsByType[typeName] = []
                stats.idsByType[typeName] << device.id

                Integer batteryLevel = null
                List currentStates = device.currentStates ?: []
                Map batteryState = currentStates.find { it.key == "battery" }
                if (batteryState?.value != null) {
                    try {
                        batteryLevel = batteryState.value.toString().toFloat().toInteger()
                        stats.batteryDevices++
                        stats.batteryIds << device.id
                        if (batteryLevel <= (settings.lowBatteryThreshold ?: 20)) {
                            stats.lowBatteryDevices << [id: device.id, name: device.name ?: "Unknown", battery: batteryLevel]
                        }
                    } catch (Exception e) { /* non-numeric battery value */ }
                }

                Object parentAppId = extractParentAppId(device)
                String normalizedParentAppId = normalizeAppLookupId(parentAppId)
                String parentAppName = normalizedParentAppId ? (appLookup[normalizedParentAppId] ?: "App ${normalizedParentAppId}") : null

                stats.allDevices << [
                    id: device.id, name: device.name ?: "Unknown",
                    label: device.label ?: device.name ?: "Unknown",
                    type: typeName, userType: device.user ?: false, deviceTypeId: device.deviceTypeId,
                    protocol: protocol,
                    status: device.disabled ? "Disabled" : (lastActivity && lastActivity > inactivityThresholdMs ? "Active" : "Inactive"),
                    lastActivity: lastActivity ? new Date(lastActivity).format("yyyy-MM-dd HH:mm") : "Never",
                    battery: batteryLevel,
                    isParent: deviceEntry.parent ?: false, isChild: deviceEntry.child ?: false,
                    linked: device.linked ?: false, room: device.roomName ?: "",
                    parentAppId: normalizedParentAppId, parentAppName: parentAppName,
                    parentDeviceId: deviceEntry.parentDeviceId, parentDeviceName: deviceEntry.parentDeviceName
                ]
            }
        } catch (Exception e) {
            logWarn "Error processing device ${deviceEntry.key}: ${e.message}"
        }
    }

    return stats
}

Map analyzeApps(boolean deep = true) {
    Map response = (Map) hubRequest(APPS_LIST_PATH, "apps list")

    if (!response || response.error || !response.apps) {
        return deep ? getEmptyAppStats() : [totalApps: 0, userApps: 0, builtInApps: 0]
    }

    // Quick mode: just count apps
    if (!deep) {
        int totalApps = 0, userApps = 0, builtInApps = 0
        visitAppEntries(response.apps as List) { Map appEntry, Map app, boolean isChildLevel, List parentHierarchyList ->
            if (!app) return
            totalApps++
            if (app.user) userApps++
            else builtInApps++
        }
        return [totalApps: totalApps, userApps: userApps, builtInApps: builtInApps]
    }

    List appsList = response.apps
    Map stats = [
        totalApps: 0,
        userApps: 0,
        builtInApps: 0,
        parentApps: 0,
        childApps: 0,
        builtInInstances: [:],
        userAppsList: [],
        byNamespace: [:],
        parentChildHierarchy: [],
        runtimeTotalApps: 0
    ]

    // Dedicated recursion remains here because hierarchy generation mutates nested child lists
    Closure processAppList
    processAppList = { List entries, boolean isChildLevel, List parentHierarchyList ->
        entries.each { appEntry ->
            try {
                Map app = appEntry.data
                if (!app || !(app instanceof Map)) return

                stats.totalApps++

                boolean isUserApp = app.user ?: false
                String appType = app.type ?: "Unknown App"
                String appLabel = app.name ?: appType
                def appId = appEntry.key ?: app.id
                List children = appEntry.children ?: []

                if (isChildLevel) {
                    stats.childApps++
                }
                if (children.size() > 0) {
                    stats.parentApps++
                }

                if (isUserApp) {
                    stats.userApps++
                    stats.userAppsList << [name: appType, label: appLabel, id: appId]
                } else {
                    stats.builtInApps++
                    stats.builtInInstances[appType] = (stats.builtInInstances[appType] ?: 0) + 1
                }

                stats.byNamespace[appType] = (stats.byNamespace[appType] ?: 0) + 1

                if (children.size() > 0) {
                    Map parentInfo = [
                        id: app.id,
                        type: appType,
                        label: appLabel,
                        childCount: 0,
                        children: []
                    ]

                    processAppList(children, true, parentInfo.children)
                    parentInfo.childCount = parentInfo.children.size()
                    parentHierarchyList << parentInfo
                } else if (isChildLevel) {
                    parentHierarchyList << [
                        id: app.id,
                        type: appType,
                        name: appLabel,
                        disabled: app.disabled ?: false
                    ]
                }
            } catch (Exception e) {
                logWarn "Error processing app ${appEntry.key}: ${e.message}"
            }
        }
    }
    processAppList(appsList, false, stats.parentChildHierarchy)

    // Identify platform-only apps by comparing runtime stats against appsList
    stats.platformApps = []
    try {
        Map runtimeResponse = (Map) hubRequest(RUNTIME_STATS_PATH, "runtime stats")
        if (runtimeResponse && !runtimeResponse.error) {
            List runtimeAppStats = runtimeResponse.appStats ?: []
            stats.runtimeTotalApps = runtimeAppStats.size()

            // Collect all IDs from appsList (including nested children)
            Set apiIds = new HashSet()
            visitAppEntries(appsList) { Map appEntry, Map app, boolean isChildLevel, List parentHierarchyList ->
                if (app?.id) apiIds << app.id
            }

            // Platform apps = in runtime stats but not in appsList
            runtimeAppStats.each { Map app ->
                if (!apiIds.contains(app.id)) {
                    stats.platformApps << [
                        id: app.id,
                        name: app.name ?: "App ${app.id}",
                        stateSize: (app.stateSize ?: 0) as int,
                        largeState: app.largeState ?: false,
                        pctTotal: (app.pctTotal ?: 0) as float,
                        count: (app.count ?: 0) as int,
                        total: (app.total ?: 0) as long,
                        average: (app.average ?: 0) as float,
                        hubActionCount: (app.hubActionCount ?: 0) as int,
                        cloudCallCount: (app.cloudCallCount ?: 0) as int
                    ]
                }
            }
            stats.platformApps = stats.platformApps.sort { -(it.stateSize as int) }
        }
    } catch (Exception e) {
        logDebug "Could not fetch runtime stats for app count: ${e.message}"
    }

    stats.userAppsList = stats.userAppsList.sort { it.name }
    stats.parentChildHierarchy = stats.parentChildHierarchy.sort { it.type }

    return stats
}

Map analyzeNetwork() {
    return [
        network: (Map) hubRequest(NETWORK_CONFIG_PATH, "network configuration", "json", 15),
        zwave: (Map) hubRequest(ZWAVE_DETAILS_PATH, "Z-Wave details", "json", 20),
        zigbee: (Map) hubRequest(ZIGBEE_DETAILS_PATH, "Zigbee details", "json", 20),
        matter: (Map) hubRequest(MATTER_DETAILS_PATH, "Matter details", "json", 15),
        hubMesh: (Map) hubRequest(HUB_MESH_PATH, "Hub Mesh", "json", 15)
    ]
}

Map analyzeSystemHealth() {
    Map memory = fetchSystemResources()
    Map stateCompression = fetchStateCompression()
    Map hubAlerts = fetchHubAlerts()
    Integer databaseSize = fetchDatabaseSize()
    Float temperature = fetchTemperature()
    Map eventStateLimits = fetchEventStateLimits()

    Map health = [
        memory: memory,
        stateCompression: stateCompression,
        hubAlerts: hubAlerts,
        databaseSize: databaseSize,
        temperature: temperature,
        eventStateLimits: eventStateLimits,
        alerts: []
    ]

    // Generate structured alerts from observed data
    if (memory && memory.freeOSMemory < 76800) {
        health.alerts << [severity: "critical", name: "OS memory critically low (${formatMemory(memory.freeOSMemory)}) — hub may become unresponsive"]
    } else if (memory && memory.freeOSMemory < 102400) {
        health.alerts << [severity: "warning", name: "Low OS memory (${formatMemory(memory.freeOSMemory)})"]
    }
    if (memory && memory.cpuAvg5min > 8.0) {
        health.alerts << [severity: "critical", name: "Very high CPU load (${String.format('%.2f', memory.cpuAvg5min as float)} — 4 cores)"]
    } else if (memory && memory.cpuAvg5min > 4.0) {
        health.alerts << [severity: "warning", name: "Elevated CPU load (${String.format('%.2f', memory.cpuAvg5min as float)} — 4 cores fully saturated)"]
    }
    if (temperature != null && temperature > 77) {
        health.alerts << [severity: "critical", name: "Hub temperature very high (${String.format('%.1f', temperature)}\u00B0C)"]
    } else if (temperature != null && temperature > 50) {
        health.alerts << [severity: "warning", name: "Hub temperature elevated (${String.format('%.1f', temperature)}\u00B0C)"]
    }

    // Incorporate platform alerts
    if (hubAlerts.alerts) {
        Map platformAlerts = hubAlerts.alerts
        ALERT_DISPLAY_NAMES.each { String key, String displayName ->
            if (platformAlerts[key] == true) {
                String severity = (key in ["hubLoadSevere", "hubZwaveCrashed", "hubHugeDatabase", "zwaveOffline", "zigbeeOffline"]) ? "critical" : "warning"
                health.alerts << [severity: severity, key: key, name: displayName]
            }
        }
    }
    if (hubAlerts.spammyDevicesMessage) {
        health.alerts << [severity: "warning", key: "spammyDevices", name: "Spammy Devices", message: hubAlerts.spammyDevicesMessage]
    }

    return health
}

// ===== PROTOCOL DETECTION =====

List flattenDeviceEntries(List entries, boolean includeParentContext = false) {
    List flattened = []
    Closure visitEntries
    visitEntries = { List currentEntries, Object parentDeviceId = null, String parentDeviceName = null ->
        (currentEntries ?: []).each { Map entry ->
            if (includeParentContext) {
                flattened << [
                    data: entry.data,
                    key: entry.key,
                    parent: entry.parent,
                    child: entry.child,
                    linked: entry.linked,
                    parentDeviceId: parentDeviceId,
                    parentDeviceName: parentDeviceName
                ]
            } else {
                flattened << entry
            }

            Map entryDevice = entry?.data instanceof Map ? (Map) entry.data : null
            Object entryId = entryDevice?.id
            String entryName = entryDevice?.label ?: entryDevice?.name ?: (entryId != null ? "Device ${entryId}" : null)
            if (entry?.children) {
                visitEntries(entry.children as List, includeParentContext ? entryId : null, includeParentContext ? entryName : null)
            }
        }
    }
    visitEntries(entries ?: [])
    return flattened
}

void visitAppEntries(List entries, Closure visitor, boolean isChildLevel = false, List parentHierarchyList = []) {
    (entries ?: []).each { Map appEntry ->
        Map app = appEntry?.data instanceof Map ? (Map) appEntry.data : null
        visitor(appEntry, app, isChildLevel, parentHierarchyList)
        List children = appEntry?.children ?: []
        if (children) {
            List nextParents = parentHierarchyList
            if (app) nextParents = parentHierarchyList + [app]
            visitAppEntries(children as List, visitor, true, nextParents)
        }
    }
}

List buildZwaveGhostNodes(Map zwaveDetails) {
    List ghostNodes = []
    (zwaveDetails?.zwDevices ?: [:]).each { nodeId, nodeData ->
        if (nodeData instanceof Map) {
            boolean isFailed = nodeData.status == "FAILED" || nodeData.failed == true
            boolean noRoute = nodeData.route == null || nodeData.route == "" || nodeData.route == "No route"
            boolean noName = !nodeData.name || nodeData.name == "Unknown" || nodeData.name == ""
            if (isFailed || (noRoute && noName)) {
                ghostNodes << [
                    id: nodeId,
                    deviceId: nodeData.deviceId,
                    name: nodeData.name ?: "Unknown",
                    status: nodeData.status ?: "No route"
                ]
            }
        }
    }
    return ghostNodes
}

Map buildRadioProtocolMap() {
    Map protocols = [:]
    try {
        Map zigbeeData = (Map) hubRequest(ZIGBEE_DETAILS_PATH, "Zigbee details", "json", 20)
        if (zigbeeData && !zigbeeData.error && zigbeeData.devices) {
            zigbeeData.devices.each { Map d -> if (d.id) protocols[d.id] = PROTOCOL_ZIGBEE }
        }
        Map zwaveData = (Map) hubRequest(ZWAVE_DETAILS_PATH, "Z-Wave details", "json", 20)
        if (zwaveData && !zwaveData.error && zwaveData.nodes) {
            zwaveData.nodes.each { Map n -> if (n.deviceId) protocols[n.deviceId] = PROTOCOL_ZWAVE }
        }
        Map matterData = (Map) hubRequest(MATTER_DETAILS_PATH, "Matter details", "json", 15)
        if (matterData && !matterData.error && matterData.devices) {
            matterData.devices.each { Map d -> if (d.id) protocols[d.id] = PROTOCOL_MATTER }
        }
    } catch (Exception e) {
        logDebug "Error building radio protocol map: ${e.message}"
    }
    return protocols
}

Map buildAppLookupMap() {
    Map response = (Map) hubRequest(APPS_LIST_PATH, "apps list", "json", 20)
    if (!response || response.error || !response.apps) {
        return [:]
    }

    Map appLookup = [:]
    visitAppEntries(response.apps as List) { Map appEntry, Map app, boolean isChildLevel, List parentHierarchyList ->
        String appId = normalizeAppLookupId(appEntry?.key ?: app?.id)
        if (appId) {
            appLookup[appId] = app?.label ?: app?.name ?: "App ${appId}"
        }
    }
    return appLookup
}

String normalizeAppLookupId(Object value) {
    if (value == null) return null
    String appId = value.toString().trim()
    if (!appId) return null
    appId = appId.replaceFirst(/^APP-/, "")
    return appId
}

Object extractParentAppId(Map device) {
    if (!device) return null
    List candidateKeys = [
        "parentAppId",
        "parentApp",
        "parentInstalledAppId",
        "appId",
        "installedAppId"
    ]
    for (String key in candidateKeys) {
        Object value = device[key]
        if (value != null && safeToString(value, "")) {
            return value
        }
    }
    return null
}

String determineProtocolQuick(Map device) {
    String typeName = safeToString(device.type, "").toLowerCase()
    String label = safeToString(device.label ?: device.name, "").toLowerCase()
    String combined = "${typeName} ${label}"

    // Phase 1: Clear Platform/Virtual/LAN markers (High confidence)
    if (combined.contains("virtual")) return PROTOCOL_VIRTUAL
    if (combined.contains("maker api") || combined.contains("webhook")) return PROTOCOL_MAKER
    if (combined.contains("cloud") || combined.contains("google") ||
        combined.contains("alexa") || combined.contains("homekit")) return PROTOCOL_CLOUD
    
    // Detailed LAN/IP Keyword Check
    if (combined.contains("lan") || combined.contains("http") || combined.contains("wifi") ||
        combined.contains("ip") || combined.contains("sonos") || combined.contains("chromecast") ||
        combined.contains("bond") || combined.contains("lutron") || combined.contains("ecobee") ||
        combined.contains("kasa") || combined.contains("lifx") || combined.contains("wiz") ||
        combined.contains("yeelight") || combined.contains("rachio") || combined.contains("govee") ||
        combined.contains("shelly") || combined.contains("tplink") || combined.contains("wled")) {
        return PROTOCOL_LAN
    }

    return PROTOCOL_OTHER
}

// ===== PERFORMANCE CHECKPOINT SYSTEM =====

void createCheckpoint() {
    logInfo "Creating perf checkpoint..."

    Map stats = (Map) hubRequest(RUNTIME_STATS_PATH, "runtime stats")
    if (!stats) {
        logError "Failed to fetch current stats"
        return
    }

    Map resources = fetchSystemResources()

    // Capture radio message counts for Z-Wave and Zigbee
    Map zwaveData = (Map) hubRequest(ZWAVE_DETAILS_PATH, "Z-Wave details", "json", 20)
    Map zigbeeData = (Map) hubRequest(ZIGBEE_DETAILS_PATH, "Zigbee details", "json", 20)
    List zwaveRadio = extractZwaveMessageCounts(zwaveData)
    List zigbeeRadio = extractZigbeeMessageCounts(zigbeeData)

    Map checkpoint = [
        timestamp: new Date().format("yyyy-MM-dd HH:mm:ss"),
        timestampMs: now(),
        stats: stats,
        resources: resources,
        radioStats: [
            zwave: zwaveRadio,
            zigbee: zigbeeRadio
        ]
    ]

    List checkpoints = loadCheckpoints()
    checkpoints.add(0, checkpoint)

    int maxCp = (settings.maxCheckpoints ?: 10) as int
    if (checkpoints.size() > maxCp) {
        checkpoints = checkpoints.take(maxCp)
    }

    saveCheckpoints(checkpoints)
    logInfo "Perf checkpoint created successfully"
}

Map buildZeroBaseline(Map stats, Map resources) {
    return [
        timestampMs: 0,
        uptime: "0h 0m 0s",
        devicesUptime: "0h 0m 0s",
        appsUptime: "0h 0m 0s",
        totalDevicesRuntime: "0ms",
        totalAppsRuntime: "0ms",
        devicePct: "0.000%",
        appPct: "0.000%",
        resources: resources ? [
            timestamp: "0:00:00",
            freeOSMemory: 0,
            cpuAvg5min: 0.0,
            totalJavaMemory: 0,
            freeJavaMemory: 0,
            directJavaMemory: 0
        ] : null,
        deviceStats: (stats.deviceStats ?: []).collect { dev ->
            [id: dev.id, name: dev.name, total: 0, pct: 0, count: 0, average: 0,
             stateSize: 0, hubActionCount: 0, cloudCallCount: 0]
        },
        appStats: (stats.appStats ?: []).collect { app ->
            [id: app.id, name: app.name, total: 0, pct: 0, count: 0, average: 0,
             stateSize: 0, hubActionCount: 0, cloudCallCount: 0]
        },
        radioStats: [
            zwave: (stats.radioStats?.zwave ?: []).collect { node ->
                [id: node.id, deviceId: node.deviceId, name: node.name, msgCount: 0, routeChanges: 0]
            },
            zigbee: (stats.radioStats?.zigbee ?: []).collect { dev ->
                [id: dev.id, name: dev.name, msgCount: 0]
            }
        ]
    ]
}

void deleteCheckpoint(int index) {
    List checkpoints = loadCheckpoints()
    if (index >= 0 && index < checkpoints.size()) {
        logInfo "Deleting checkpoint at index ${index}"
        checkpoints.remove(index)
        saveCheckpoints(checkpoints)
    }
}

void clearAllCheckpoints() {
    deleteFile(CHECKPOINTS_FILE)
    deleteFile(PERFORMANCE_COMPARISON_FILE)
    logInfo "All perf checkpoints cleared"
}

// ===== SNAPSHOT SYSTEM =====

void createSnapshot() {
    logInfo "Creating config snapshot..."

    Map snapshot = [
        timestamp: new Date().format("yyyy-MM-dd HH:mm:ss"),
        timestampMs: now(),
        devices: analyzeDevices(),
        apps: analyzeApps(),
        network: analyzeNetwork(),
        systemHealth: analyzeSystemHealth(),
        hubInfo: getHubInfo()
    ]

    // Strip allDevices down to compact form for storage
    if (snapshot.devices.allDevices) {
        snapshot.devices.allDevices = snapshot.devices.allDevices.collect { Map dev ->
            [id: dev.id, name: dev.name, type: dev.type, protocol: dev.protocol, status: dev.status]
        }
    }

    List snapshots = loadSnapshots()
    snapshots.add(0, snapshot)

    int maxSnap = (settings.maxSnapshots ?: 10) as int
    if (snapshots.size() > maxSnap) {
        snapshots = snapshots.take(maxSnap)
    }

    saveSnapshots(snapshots)
    logInfo "Config snapshot created successfully (${snapshots.size()} total)"
}

void deleteSnapshot(int index) {
    List snapshots = loadSnapshots()
    if (index >= 0 && index < snapshots.size()) {
        logInfo "Deleting snapshot at index ${index}"
        snapshots.remove(index)
        saveSnapshots(snapshots)
    }
}

void clearAllSnapshots() {
    deleteFile(SNAPSHOTS_FILE)
    deleteFile(SNAPSHOT_DIFF_FILE)
    logInfo "All config snapshots cleared"
}

// ===== FILE MANAGEMENT =====

List<Map> listHubFiles(String nameContains = null) {
    try {
        List<Map<String, String>> hubFiles = getHubFiles() ?: []
        List<Map> fileList = []
        hubFiles.each { Map<String, String> rec ->
            String name = safeToString(rec.name, "")
            if (!name) return
            if (nameContains && !name.contains(nameContains)) return
            fileList << [
                name: name,
                size: rec.size,
                date: rec.date ?: rec.lastModified ?: rec.modified ?: ""
            ]
        }
        return fileList.sort { a, b -> (b.name ?: "") <=> (a.name ?: "") }
    } catch (Exception e) {
        logDebug "Unable to list hub files: ${e.message}"
        return []
    }
}

List<Map> listHubFilesByNames(List<String> names) {
    Set<String> wanted = (names ?: []).findAll { it } as Set<String>
    if (!wanted) return []
    return listHubFiles().findAll { Map rec -> wanted.contains(rec.name) }
}

// ===== FORMATTING HELPERS =====

String generateQuickSummary() {
    try {
        Map deviceStats = analyzeDevices(false)
        Map appStats = analyzeApps(false)
        Map hubInfo = getHubInfo()
        Map resources = fetchSystemResources()

        String summary = "Hub: ${hubInfo.name} | Firmware: ${hubInfo.firmware} | Hardware: ${hubInfo.hardware}\n"
        summary += "Devices: ${deviceStats.totalDevices} total (Active: ${deviceStats.activeDevices} | Inactive: ${deviceStats.inactiveDevices} | Disabled: ${deviceStats.disabledDevices})\n"
        summary += "Apps: ${appStats.totalApps} total (System: ${appStats.builtInApps} | User: ${appStats.userApps})"
        if (resources) {
            summary += "\nResources: ${formatMemory(resources.freeOSMemory ?: 0)} free | CPU: ${String.format('%.2f', (resources.cpuAvg5min ?: 0) as float)}"
        }
        synchronized (apiTimings) {
            if (apiTimings) {
                List timingParts = apiTimings.collect { String ep, Map entry ->
                    "${ep}: ${entry.median}ms"
                }.sort()
                summary += "\nAPI medians: ${timingParts.join(' | ')}"
            }
        }
        return summary
    } catch (Exception e) {
        logError "Error generating summary: ${getObjectClassName(e)}: ${e.message}"
        return "Error generating summary. Please check logs."
    }
}

// ===== UTILITY METHODS =====

private String logPrefix() {
    return app?.label ?: app?.name ?: "Hub Diagnostics"
}

private void logDebug(String message) {
    if (debugLogging) log.debug "${logPrefix()} : ${message}"
}

private void logInfo(String message) {
    log.info "${logPrefix()} : ${message}"
}

private void logWarn(String message) {
    log.warn "${logPrefix()} : ${message}"
}

private void logError(String message) {
    log.error "${logPrefix()} : ${message}"
}

Map getHubInfo() {
    Map info = [name: location.name ?: "Unknown", firmware: "Unknown", hardware: "Unknown", ip: "Unknown"]
    if (location.hubs && location.hubs.size() > 0) {
        def hub = location.hubs[0]
        info.firmware = hub.firmwareVersionString ?: "Unknown"
        info.hardware = hub.type ?: "Unknown"
        info.ip = hub.localIP ?: "Unknown"
    }
    // Fetch model from hubData for accurate hardware name (e.g. "C-7", "C-8 Pro")
    Map hubData = (Map) hubRequest(HUB_DATA_PATH, "hub data", "json", 10)
    if (hubData && !hubData.error && hubData.model) {
        info.hardware = hubData.model
    }
    return info
}

Map getEmptyDeviceStats() {
    return [
        totalDevices: 0, activeDevices: 0, inactiveDevices: 0, disabledDevices: 0,
        parentDevices: 0, childDevices: 0, linkedDevices: 0, batteryDevices: 0,
        lowBatteryDevices: [], allDevices: [],
        byType: [:],
        idsByType: [:],
        byProtocol: [(PROTOCOL_ZIGBEE): 0, (PROTOCOL_ZWAVE): 0, (PROTOCOL_MATTER): 0,
                     (PROTOCOL_LAN): 0, (PROTOCOL_VIRTUAL): 0, (PROTOCOL_MAKER): 0,
                     (PROTOCOL_CLOUD): 0, (PROTOCOL_HUBMESH): 0, (PROTOCOL_OTHER): 0],
        byStatus: [active: 0, inactive: 0, disabled: 0],
        idsByStatus: [active: [], inactive: [], disabled: []],
        idsByProtocol: [(PROTOCOL_ZIGBEE): [], (PROTOCOL_ZWAVE): [], (PROTOCOL_MATTER): [],
                        (PROTOCOL_LAN): [], (PROTOCOL_VIRTUAL): [], (PROTOCOL_MAKER): [],
                        (PROTOCOL_CLOUD): [], (PROTOCOL_HUBMESH): [], (PROTOCOL_OTHER): []],
        parentIds: [],
        childIds: [],
        linkedIds: [],
        batteryIds: []
    ]
}

Map getEmptyAppStats() {
    return [
        totalApps: 0, userApps: 0, builtInApps: 0, parentApps: 0, childApps: 0,
        builtInInstances: [:], userAppsList: [], byNamespace: [:], parentChildHierarchy: [],
        runtimeTotalApps: 0
    ]
}

String safeToString(value, String defaultValue = "") {
    if (value == null || value instanceof List) return defaultValue
    return value.toString()
}

Long parseDate(dateStr) {
    if (dateStr == null || dateStr instanceof List) return null
    String dateString = safeToString(dateStr, "")
    if (dateString.isEmpty()) return null

    try {
        return Date.parse("yyyy-MM-dd'T'HH:mm:ss.SSSZ", dateString).time
    } catch (Exception e) {
        try {
            return Date.parse("yyyy-MM-dd'T'HH:mm:ssZ", dateString).time
        } catch (Exception e2) {
            return null
        }
    }
}

int parseUptime(String uptime) {
    if (!uptime) return 0

    // Handle milliseconds format like "0ms" or "123ms"
    if (uptime.endsWith('ms')) {
        return (uptime[0..-3].toInteger() / 1000).toInteger()
    }

    int seconds = 0
    List parts = uptime.tokenize()

    parts.each { String part ->
        if (part.endsWith('d')) {
            seconds += part[0..-2].toInteger() * 86400
        } else if (part.endsWith('h')) {
            seconds += part[0..-2].toInteger() * 3600
        } else if (part.endsWith('m') && !part.endsWith('ms')) {
            seconds += part[0..-2].toInteger() * 60
        } else if (part.endsWith('s') && !part.endsWith('ms')) {
            seconds += part[0..-2].toInteger()
        }
    }

    return seconds
}

String formatDuration(int seconds) {
    int hours = (seconds / 3600).toInteger()
    int minutes = ((seconds % 3600) / 60).toInteger()
    int secs = (seconds % 60).toInteger()

    if (hours > 0) return "${hours}h ${minutes}m ${secs}s"
    if (minutes > 0) return "${minutes}m ${secs}s"
    return "${secs}s"
}

String formatMemory(int kb) {
    if (Math.abs(kb) >= 1024 * 1024) {
        return String.format("%.2f GB", kb / (1024.0 * 1024.0))
    } else if (Math.abs(kb) >= 1024) {
        return String.format("%.2f MB", kb / 1024.0)
    } else {
        return "${kb} KB"
    }
}

boolean isNewer(String v1, String v2) {
    if (!v1 || v1 == "Unknown" || !v2 || v2 == "Unknown") return false
    List v1p = v1.tokenize('.'), v2p = v2.tokenize('.')
    for (int i = 0; i < Math.max(v1p.size(), v2p.size()); i++) {
        int n1 = i < v1p.size() ? v1p[i].toInteger() : 0
        int n2 = i < v2p.size() ? v2p[i].toInteger() : 0
        if (n1 > n2) return true
        if (n1 < n2) return false
    }
    return false
}

void appButtonHandler(String btn) {
}

private String getAppTypeId() {
    String typeId = null
    try {
        httpGet([uri: HUB_BASE, path: "/hub2/userAppTypes", timeout: 15]) { resp ->
            List apps = resp.data instanceof List ? (List) resp.data : []
            Map match = apps.find { it.name == "Hub Diagnostics" }
            if (match) typeId = match.id?.toString()
        }
    } catch (e) {
        logDebug "Failed to fetch user app types: ${e.message}"
    }
    return typeId
}

private String getAppEditorPath() {
    String typeId = getAppTypeId()
    return typeId ? "/app/editor/${typeId}" : null
}

private boolean autoEnableOAuth() {
    logInfo "Attempting to auto-enable OAuth for Hub Diagnostics..."

    // 1. Find our app type ID
    String typeId = getAppTypeId()
    if (!typeId) {
        logError "Could not find Hub Diagnostics in user app types."
        return false
    }

    // 2. Get the internal version from the app code JSON endpoint
    String internalVer = null
    try {
        httpGet([uri: HUB_BASE, path: "/app/ajax/code", query: [id: typeId], timeout: 15]) { resp ->
            internalVer = resp.data?.version?.toString()
        }
    } catch (e) {
        logError "Failed to fetch app code version: ${e.message}"
        return false
    }
    if (!internalVer) {
        logError "Could not determine app code version."
        return false
    }

    // 3. POST to enable OAuth
    boolean success = false
    try {
        httpPost([
            uri: HUB_BASE,
            path: "/app/edit/update",
            requestContentType: "application/x-www-form-urlencoded",
            body: [
                id: typeId,
                version: internalVer,
                oauthEnabled: "true",
                _action_update: "Update"
            ],
            timeout: 20
        ]) { resp ->
            success = true
            logInfo "Successfully auto-enabled OAuth."
        }
    } catch (e) {
        logError "Failed to enable OAuth: ${e.message}"
    }
    return success
}

// ===== FILE I/O HELPERS =====

List loadCheckpoints() {
    try {
        def data = readFile(CHECKPOINTS_FILE)
        if (data) return data
    } catch (Exception e) {
        logDebug "No existing checkpoints: ${e.message}"
    }
    return []
}

void saveCheckpoints(List checkpoints) {
    try {
        String json = groovy.json.JsonOutput.toJson(checkpoints)
        writeFile(CHECKPOINTS_FILE, json)
    } catch (Exception e) {
        logError "Error saving checkpoints: ${e}"
    }
}

List loadSnapshots() {
    try {
        def data = readFile(SNAPSHOTS_FILE)
        if (data) return data
    } catch (Exception e) {
        logDebug "No existing snapshots: ${e.message}"
    }
    return []
}

void saveSnapshots(List snapshots) {
    try {
        String json = groovy.json.JsonOutput.toJson(snapshots)
        writeFile(SNAPSHOTS_FILE, json)
    } catch (Exception e) {
        logError "Error saving snapshots: ${e}"
    }
}

Map loadPerformanceComparisonPayload() {
    def data = readFile(PERFORMANCE_COMPARISON_FILE)
    return data instanceof Map ? (Map) data : null
}

void savePerformanceComparisonPayload(Map payload) {
    if (payload) {
        writeFile(PERFORMANCE_COMPARISON_FILE, groovy.json.JsonOutput.toJson(payload))
    }
}

Map loadSnapshotDiffPayload() {
    def data = readFile(SNAPSHOT_DIFF_FILE)
    return data instanceof Map ? (Map) data : null
}

void saveSnapshotDiffPayload(Map payload) {
    if (payload) {
        writeFile(SNAPSHOT_DIFF_FILE, groovy.json.JsonOutput.toJson(payload))
    }
}

String loadUITemplate() {
    try {
        byte[] hubFile = downloadHubFile('hub_diagnostics_ui.html')
        if (hubFile) return new String(hubFile, 'UTF-8')
    } catch (Exception e) {
        logError "Error reading hub_diagnostics_ui.html: ${e.message}"
    }
    return null
}

def readFile(String fileName) {
    try {
        byte[] fileData = downloadHubFile(fileName)
        if (fileData) {
            String jsonString = new String(fileData, "UTF-8")
            return new groovy.json.JsonSlurper().parseText(jsonString)
        }
    } catch (Exception e) {
        logDebug "File not found or error reading ${fileName}: ${e.message}"
    }
    return null
}

boolean fileExists(String fileName) {
    try {
        return downloadHubFile(fileName) != null
    } catch (Exception e) {
        logDebug "File not found or error checking ${fileName}: ${e.message}"
        return false
    }
}

void writeFile(String fileName, String data) {
    try {
        uploadHubFile(fileName, data.getBytes("UTF-8"))
    } catch (Exception e) {
        logError "Error writing file ${fileName}: ${e}"
    }
}

void deleteFile(String fileName) {
    try {
        deleteHubFile(fileName)
    } catch (Exception e) {
        logError "Error deleting file ${fileName}: ${e}"
    }
}

// ===== LIFECYCLE METHODS =====

void installed() {
    logInfo "Hub Diagnostics installed"
    state.installed = true
    if (!state.accessToken) checkOAuth()
    syncUI(true)
    initialize()
}

void updated() {
    logInfo "Hub Diagnostics updated"
    state.installed = true
    unsubscribe()
    unschedule()
    syncUI(true)
    initialize()
}

void uninstalled() {
    unschedule()
    unsubscribe()
    logInfo "Hub Diagnostics uninstalled"
}

private boolean checkOAuth() {
    if (state.accessToken) return true
    try {
        createAccessToken()
        return (state.accessToken != null)
    } catch (e) {
        logDebug "OAuth not enabled yet, attempting auto-enable..."
        if (autoEnableOAuth()) {
            try {
                createAccessToken()
                return (state.accessToken != null)
            } catch (e2) {
                logError "OAuth enabled but token creation failed: ${e2.message}"
                return false
            }
        }
        return false
    }
}

boolean syncUI(boolean force = false) {
    if (!force && state.lastInstalledVersion == APP_VERSION) {
        long lastCheck = state.lastUIUpdateCheck ?: 0
        if (now() - lastCheck < 86400000) return true
    }
    
    try {
        logInfo "Hub Diagnostics: Syncing UI from GitHub..."
        Map params = [uri: IMPORT_URL_WEB, contentType: "text/plain", timeout: 30]
        boolean success = false
        // Use synchronous httpGet by not providing a closure to params
        httpGet(params) { resp ->
            if (resp.success && resp.data) {
                String htmlText = resp.data.text ?: resp.data.toString()
                if (htmlText && htmlText.contains("Hub Diagnostics")) {
                    // Enforce version sync: downloaded HTML must contain the same version string as the App
                    if (htmlText.contains("const UI_VERSION = \"${APP_VERSION}\"")) {
                        byte[] htmlBytes = htmlText.getBytes("UTF-8")
                        uploadHubFile("hub_diagnostics_ui.html", htmlBytes)
                        state.lastInstalledVersion = APP_VERSION
                        state.lastUIUpdateCheck = now()
                        logInfo "UI updated from GitHub to match App v${APP_VERSION} (${htmlBytes.length} bytes)"
                        success = true
                    } else {
                        logWarn "Sync failed: GitHub UI version does not match App v${APP_VERSION}"
                    }
                } else {
                    logWarn "Sync failed: Downloaded content appears invalid"
                }
            }
        }
        return success
    } catch (Exception e) {
        logWarn "Failed to sync UI from GitHub: ${e.message}"
        return false
    }
}

void initialize() {
    logInfo "Hub Diagnostics initialized"
    migrateStorageIfNeeded()

    if (settings.autoSnapshot) {
        int interval = (settings.snapshotInterval ?: "24").toInteger()
        schedule("0 0 */${interval} * * ?", "createSnapshot")
        logInfo "Automatic config snapshots scheduled every ${interval} hour(s)"
    }

    if (settings.autoCheckpoint) {
        int interval = (settings.checkpointInterval ?: "60").toInteger()
        if (interval < 60) {
            schedule("0 */${interval} * * * ?", "createCheckpoint")
        } else {
            int hours = (interval / 60).toInteger()
            schedule("0 0 */${hours} * * ?", "createCheckpoint")
        }
        logInfo "Automatic perf checkpoints scheduled every ${interval} minute(s)"
    }
}

void migrateStorageIfNeeded() {
    String currentSchema = (state.storageSchemaVersion ?: "") as String
    if (currentSchema == STORAGE_SCHEMA_VERSION) {
        return
    }

    boolean migratedLegacyState = false
    if (state.lastPerformanceComparison != null) {
        state.remove("lastPerformanceComparison")
        migratedLegacyState = true
    }
    if (state.lastSnapshotDiff != null) {
        state.remove("lastSnapshotDiff")
        migratedLegacyState = true
    }

    state.storageSchemaVersion = STORAGE_SCHEMA_VERSION
    if (migratedLegacyState) {
        logInfo "Migrated legacy comparison state to file-backed storage for v${APP_VERSION}"
    }
}

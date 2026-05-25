// Copyright (c) 2025-2026 PJ
// SPDX-License-Identifier: MIT

/**
 * Hub Diagnostics
 *
 * Comprehensive hub diagnostics: inventory, performance tracking, network analysis,
 * snapshot comparison, and exportable reports.
 
 *
 */

import com.hubitat.hub.domain.Hub
import groovy.transform.Field
import groovy.transform.CompileStatic
import groovy.json.JsonOutput
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

@Field static final String APP_VERSION = "5.62.0"
@Field static final String STORAGE_SCHEMA_VERSION = "5.0.0"

// API endpoint paths (all relative to HUB_BASE)
@Field static final String HUB_BASE = "http://127.0.0.1:8080"
@Field static final String DEVICES_LIST_PATH = "/hub2/devicesList"
@Field static final String APPS_LIST_PATH = "/hub2/appsList"
@Field static final String NETWORK_CONFIG_PATH = "/hub2/networkConfiguration"
@Field static final String ZWAVE_DETAILS_PATH = "/hub/zwaveDetails/json"
@Field static final String ZIGBEE_DETAILS_PATH = "/hub/zigbeeDetails/json"
@Field static final String MATTER_DETAILS_PATH = "/hub/matterDetails/json"
@Field static final String HUB_DATA_PATH = "/hub2/hubData"
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
@Field static final String DEVICE_TYPES_PATH = "/hub2/userDeviceTypes"

// v5.9.0 — Phase 0+1+2 endpoints
@Field static final String LOCAL_BACKUPS_PATH = "/hub2/localBackups"
@Field static final String CLOUD_BACKUPS_PATH = "/hub2/cloudBackups"
@Field static final String HUB_MESSAGES_PATH = "/hub/messages"
@Field static final String ZWAVE_HEALTH_PATH = "/hub/zwave/healthStatus"
@Field static final String ZIGBEE_HEALTH_PATH = "/hub/zigbee/healthStatus"
@Field static final String ZWAVE_JS_STATUS_PATH = "/hub/zwave2/status"
@Field static final String ZWAVE_JS_CONTROLLER_PATH = "/hub/zwave2/getControllerState"
@Field static final String NTP_SERVER_PATH = "/hub/advanced/ntpServer"
@Field static final String LOAD_THRESHOLD_PATH = "/hub/advanced/getExcessiveLoadThreshold"
@Field static final String FIRMWARE_UPDATE_PATH = "/hub/cloud/checkForUpdate"
@Field static final String MDNS_PATH = "/hub/mdnsDevices/json"
@Field static final String USER_BUNDLES_PATH = "/hub2/userBundles"
@Field static final String USER_LIBRARIES_PATH = "/hub2/userLibraries"
@Field static final String USER_APP_TYPES_PATH = "/hub2/userAppTypes"
@Field static final String ROOMS_LIST_PATH = "/hub2/roomsList"
@Field static final String ZWAVE_JS_NODE_STATE_PREFIX = "/hub/zwave2/getNodeState?node="
@Field static final String HUB_MESH_LINKED_DEVICE_PREFIX = "/hubMesh/localLinkedDevice/"
@Field static final String CPU_INFO_PATH = "/hub/cpuInfo"
@Field static final String ZIPGATEWAY_VERSION_PATH = "/hub/advanced/zipgatewayVersion"
@Field static final String LIMITED_ACCESS_PATH = "/hub/advanced/getLimitedAccessAddresses"
@Field static final String ALLOW_SUBNETS_PATH = "/hub/allowSubnets"
@Field static final String DNS_FALLBACK_PATH = "/hub/advanced/getDNSFallback"
@Field static final String ZIGBEE_CHANNEL_SCAN_PATH = "/hub/zigbeeChannelScanJson"
@Field static final String ZWAVE_TOPOLOGY_PATH = "/hub/zwaveTopology"
@Field static final String HUB_EVENTS_PATH = "/hub/eventsJson"
@Field static final String MIN_FW_RADIO_HEALTH = "2.4.1.154"
// Minimum platform firmware Hub Diagnostics is developed and tested against. Older builds may
// lack endpoints/behaviours the app relies on; below this the Versions section shows a warning.
@Field static final String MIN_FW_SUPPORTED = "2.5.0"
@Field static final long   FW_UPDATE_CACHE_TTL_MS = 3600_000L

// ===== Device Usage Audit constants =====
@Field static final String FULL_JSON_PATH_PREFIX = "/device/fullJson/"
@Field static final int    AUDIT_MAX_INFLIGHT  = 8       // Hubitat platform cap on concurrent async HTTP per app
@Field static final int    AUDIT_WATCHDOG_SEC  = 120     // safety net if a callback is genuinely lost
@Field static final long   AUDIT_STALE_MS      = 600_000 // 10 min — anything older is force-cleared on app entry
@Field static final double AUDIT_FAIL_RATIO    = 0.10    // > 10% per-device failures → mark scan errored

// Per-scan in-memory state. Each entry is itself a ConcurrentHashMap with keys:
//   total (Integer), startedAt (Long),
//   inFlight (AtomicInteger), processed (AtomicInteger),
//   pending (ConcurrentLinkedQueue<Long>),
//   devices (ConcurrentHashMap<Long, Map>),
//   failed (ConcurrentHashMap<Long, String>)
@Field static final ConcurrentHashMap<String, ConcurrentHashMap> AUDIT_SCANS = new ConcurrentHashMap<>()
@Field static volatile Map lastAuditResult = null

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

// Connection type constants.
// "paired" = the hub is a controller/admin of the device via a commissioning that ENROLLED it into
// the hub (keys exchanged; the device is a member of a network/fabric the hub controls): Zigbee
// join, Z-Wave inclusion, Matter fabric commissioning (Thread OR WiFi), BLE bond, HomeKit pairing.
// NOT defined by radio (Matter-WiFi / HomeKit-LAN qualify) or exclusivity (Matter is multi-admin).
// Contrast lan_direct/lan_bridge/cloud: the hub is just a client of an autonomous IP device
// (Kasa, Shelly, Sonos, a Hue bridge, a cloud account) — nothing was commissioned into the hub.
@Field static final String CONN_PAIRED = "paired"
@Field static final String CONN_LAN_DIRECT = "lan_direct"
@Field static final String CONN_LAN_BRIDGE = "lan_bridge"
@Field static final String CONN_CLOUD = "cloud"
@Field static final String CONN_VIRTUAL = "virtual"
@Field static final String CONN_HUBMESH = "hubmesh"
@Field static final String CONN_OTHER = "other"

@Field static final long ONE_DAY_MS = 86400000
@Field static final int API_TIMING_WINDOW = 20

// System alert threshold defaults — these become the defaults for user-configurable settings
@Field static final int    DEFAULT_WARN_MEM_MB   = 100
@Field static final int    DEFAULT_CRIT_MEM_MB   = 75
@Field static final double DEFAULT_WARN_CPU_LOAD = 4.0
@Field static final double DEFAULT_CRIT_CPU_LOAD = 8.0
@Field static final int    DEFAULT_WARN_TEMP_C   = 50
@Field static final int    DEFAULT_CRIT_TEMP_C   = 77

// In-memory API response time tracking (reset on hub reboot)
@Field static Map apiTimings = [:]

// In-memory caches (survive within a JVM session; cleared on hub reboot/app reload)
@Field static volatile String  uiVersionCache
@Field static volatile String  zwaveStackCache
@Field static volatile Map     fwUpdateCache
@Field static volatile Long    fwUpdateCacheAt
@Field static volatile Map     cachedZwaveData
@Field static volatile Long    cachedZwaveAt
@Field static volatile Map     cachedZigbeeData
@Field static volatile Long    cachedZigbeeAt
@Field static volatile Map     cachedAppsListData
@Field static volatile Long    cachedAppsListAt
@Field static volatile Map     cachedDevicesListData
@Field static volatile Long    cachedDevicesListAt
@Field static volatile Map     cachedSystemResources
@Field static volatile Long    cachedSystemResourcesAt
// v5.33.0: split-file storage replaces the single-blob cachedCheckpoints. Only the
// slim index is cached in memory; per-checkpoint detail is read on demand.
@Field static volatile List    cachedCheckpointIndex
// Staging area for an in-progress async scheduled checkpoint. Safe as a single static
// because atomicState.checkpointInFlight serializes chains.
@Field static volatile Map     asyncCheckpointStaging
// v5.62.0: /logs/json (runtime stats) is the slow first leg of the async checkpoint chain
// and occasionally crosses its 30s timeout under transient hub load. Rather than discard the
// whole checkpoint (leaving a multi-interval gap in perf history), retry the leg once after a
// short breather. checkpointInFlight (300s guard) comfortably covers the retry window.
@Field static final int        RUNTIME_STATS_MAX_ATTEMPTS = 2
@Field static final int        RUNTIME_STATS_RETRY_S      = 60
@Field static final long       RADIO_CACHE_TTL_MS = 60_000L
@Field static final long       HUB_LIST_CACHE_TTL_MS = 120_000L
@Field static final long       SYSTEM_RESOURCES_CACHE_TTL_MS = 10_000L
// apiLive polls every 30s by default and fans out to 5 hub HTTP calls. The slow-changing ones
// (temperature, databaseSize, cpuInfo, loadThreshold) carry longer TTLs to spare the hub.
@Field static final long       TEMPERATURE_CACHE_TTL_MS   = 60_000L
@Field static final long       DATABASE_SIZE_CACHE_TTL_MS = 60_000L
@Field static final long       CPU_INFO_CACHE_TTL_MS      = 300_000L
@Field static final long       LOAD_THRESHOLD_CACHE_TTL_MS = 300_000L
@Field static volatile Float   cachedTemperature
@Field static volatile Long    cachedTemperatureAt
@Field static volatile Integer cachedDatabaseSize
@Field static volatile Long    cachedDatabaseSizeAt
@Field static volatile Map     cachedCpuInfo
@Field static volatile Long    cachedCpuInfoAt
@Field static volatile Integer cachedLoadThreshold
@Field static volatile Long    cachedLoadThresholdAt
@Field static volatile boolean githubVersionRefreshPending = false
@Field static final java.util.regex.Pattern HTML_TAG_RE = ~/<[^>]+>/

// Connection type display names
@Field static final Map CONN_DISPLAY = [
    "paired": "Paired",
    "lan_direct": "LAN (Direct)",
    "lan_bridge": "LAN (Bridge)",
    "cloud": "Cloud",
    "virtual": "Virtual",
    "hubmesh": "Hub Mesh",
    "other": "Other"
]

// Legacy protocol display names (for migrating old snapshots)
@Field static final Map LEGACY_PROTOCOL_DISPLAY = [
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

// Connection-type exceptions: lowercase keyword → [conn: connectionType].
// This map is NOT a roster of integrations — it holds ONLY the connection types the
// isNetwork-derivation can't infer. Everything else rides on the derivation:
//   • integration NAME always comes from cleanIntegrationName(appType);
//   • built-in vs community comes from the hub's own appInfo.user flag;
//   • cloud vs lan_direct is derived from device.isNetwork (LAN ⇒ lan_direct, else cloud).
// The four bridges report isNetwork=true (so they'd mis-derive to lan_direct) but front their
// child devices, so they're lan_bridge. AirPlay devices carry MAC-format DNIs with isNetwork=false
// (so they'd mis-derive to cloud) but are local, so lan_direct. No name overrides — let
// cleanIntegrationName handle every display name.
// User-discovered exceptions go in the File Manager config file (loaded + overlaid by
// getIntegrationOverrides()).
// Entries are ordered longest-first to avoid false positives. LinkedHashMap preserves insertion
// order, which is the iteration order used by lookupIntegration().
@Field static final Map INTEGRATION_OVERRIDES = [
    "philips hue" : [conn: "lan_bridge"],
    "hue bridge"  : [conn: "lan_bridge"],
    "airplay"     : [conn: "lan_direct"],
    "lutron"      : [conn: "lan_bridge"],
    "bond"        : [conn: "lan_bridge"],
    // HomeKit Controller commissions the accessory INTO the hub (the hub becomes its HAP controller)
    // — enrolled, not merely reached over IP — so it's "paired" despite isNetwork=true (which would
    // derive lan_direct; the enrich path derives cloud from HKC). See the CONN_PAIRED definition.
    "homekit"     : [conn: "paired"],
]

// Built-in Hubitat cloud-polling DEVICE drivers: standalone cloud clients with no parent app, no
// radio, and isNetwork=false — every derivation signal is absent, so classifyDevice would drop them
// into "Other". They're enumerated here by built-in driver type name (lowercased) → integration
// display name. Only Hubitat-bundled drivers belong here; the driverIsBuiltin guard in classifyDevice
// prevents a same-named community driver from matching. Community cloud/LAN devices are handled by the
// File Manager override file (matched on driver type name), not this table.
@Field static final Map BUILTIN_CLOUD_DRIVERS = [
    "openweathermap"     : "OpenWeather",
    "ecobee thermostat"  : "Ecobee",
    "pushover driver"    : "Pushover",
    "mobile app device"  : "Mobile App",
]


// User-customizable integration-overrides config file (optional, File Manager)
@Field static final String INTEGRATION_OVERRIDES_FILE = "hub_diagnostics_integration_overrides.json"

// Valid conn values; used to reject unknown strings from the user config file
@Field static final Set<String> VALID_CONN = [
    "paired", "lan_direct", "lan_bridge", "cloud", "virtual", "hubmesh", "other"
] as Set

// Cache for the merged (built-in + user file) integration overrides map.
// Null means "not yet loaded"; set to null in updated() to force a reload.
@Field static volatile Map integrationOverridesCache = null

// File names for persistence
@Field static final String SNAPSHOTS_FILE = "hub_diagnostics_snapshots.json"
// v5.33.0 split-file checkpoint storage:
//   index file: small list of slim records (one per checkpoint) + detailFile pointer
//   detail files: one per checkpoint, named with timestampMs, holds full content
// The legacy CHECKPOINTS_FILE name is kept only for one-shot migration detection.
@Field static final String CHECKPOINTS_FILE = "hub_diagnostics_checkpoints.json"
@Field static final String CHECKPOINT_INDEX_FILE = "hub_diagnostics_checkpoints_index.json"
@Field static final String CHECKPOINT_DETAIL_PREFIX = "hub_diagnostics_checkpoint_"
@Field static final String PERFORMANCE_COMPARISON_FILE = "hub_diagnostics_performance_comparison.json"

@Field static final String IMPORT_URL_APP = "https://raw.githubusercontent.com/iamtrep/hubitat/refs/heads/main/apps/HubDiagnostics/HubDiagnostics.groovy"
@Field static final String IMPORT_URL_WEB = "https://raw.githubusercontent.com/iamtrep/hubitat/refs/heads/main/apps/HubDiagnostics/hub_diagnostics_ui.html"


// Maps controllerType values (from device/fullJson top-level field) to connection type constants.
// Actual observed values: ZGB=Zigbee, MAT=Matter, LNK=HubMesh, HKC=HomeKit, BLE=Bluetooth.
// Used only as a last-resort fallback when parentApp is absent from fullJson.
@Field static final Map CONTROLLER_TYPE_CONN = [
    "ZGB": "paired",
    "ZWV": "paired",
    "MAT": "paired",
    "BLE": "paired",
    "HKC": "paired",
    "LNK": "hubmesh",
    "NET": "lan_direct",
    "CLO": "cloud",
    "VIR": "virtual",
]

definition(
    name: "Hub Diagnostics",
    namespace: "iamtrep",
    author: "pj",
    description: "Comprehensive hub diagnostics: inventory, performance tracking, network analysis, and snapshot comparison",
    menu: "Apps", // new in platform 2.5.0
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

    // ===== Aggregator GETs =====
    // Routes that fetch multiple hub resources, normalize them, and serve a
    // UI-specific contract. Justified by shared-cache, fail-soft behavior,
    // and normalization the SPA should not duplicate. See ARCHITECTURE.md
    // ("API Endpoint Boundaries") before adding a new route here.
    path('/api/dashboard')        { action: [GET: 'apiDashboard'] }
    path('/api/devices')          { action: [GET: 'apiDevices'] }
    path('/api/apps')             { action: [GET: 'apiApps'] }
    path('/api/network')          { action: [GET: 'apiNetwork'] }
    path('/api/health')           { action: [GET: 'apiHealth'] }
    path('/api/health/history')   { action: [GET: 'apiHealthHistory'] }
    path('/api/live')             { action: [GET: 'apiLive'] }

    // ===== App-owned GETs =====
    // Routes that read app-owned state (snapshots, checkpoints, performance
    // history, telemetry, settings) or compose data only the app can produce.
    path('/api/code')             { action: [GET: 'apiCode'] }
    path('/api/performance')      { action: [GET: 'apiPerformance'] }
    path('/api/snapshots')        { action: [GET: 'apiSnapshots'] }
    path('/api/snapshot/view')    { action: [GET: 'apiSnapshotView'] }
    path('/api/stats')            { action: [GET: 'apiStats'] }
    path('/api/version/check')    { action: [GET: 'apiVersionCheck'] }
    path('/api/reports')          { action: [GET: 'apiReports'] }

    // ===== App-owned mutations =====
    // Stateful writes and orchestration. App-owned by definition.
    path('/api/snapshot/create')     { action: [POST: 'apiCreateSnapshot'] }
    path('/api/snapshot/delete')     { action: [POST: 'apiDeleteSnapshot'] }
    path('/api/snapshots/clear')     { action: [POST: 'apiClearSnapshots'] }
    path('/api/checkpoint/create')   { action: [POST: 'apiCreateCheckpoint'] }
    path('/api/checkpoint/delete')   { action: [POST: 'apiDeleteCheckpoint'] }
    path('/api/checkpoints/clear')   { action: [POST: 'apiClearCheckpoints'] }
    path('/api/performance/compare') { action: [POST: 'apiPerformanceCompare'] }
    path('/api/ui/sync')             { action: [POST: 'apiSyncUI'] }
    path('/api/report/save')         { action: [POST: 'apiSaveReport'] }
    path('/api/report/template')     { action: [GET:  'apiReportTemplate'] }
    path('/api/settings')            { action: [GET: 'apiGetSettings', POST: 'apiUpdateSettings'] }
    path('/api/cache/clear')         { action: [POST: 'apiClearCache'] }

    // ===== Long-running orchestration =====
    // Device usage audit — async scan with app-owned state.
    path('/api/audit/start')   { action: [POST: 'apiAuditStart'] }
    path('/api/audit/status')  { action: [GET:  'apiAuditStatus'] }
    path('/api/audit/data')    { action: [GET:  'apiAuditData'] }

    // ===== Side-effectful network actions =====
    path('/api/network/zigbee/scan') { action: [POST: 'apiZigbeeScan'] }
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

        String hubFw = getHubFirmwareVersion()
        boolean firmwareUnsupported = hubFw && !isVersionAtLeast(hubFw, MIN_FW_SUPPORTED)

        section("Versions") {
            if (firmwareUnsupported) {
                paragraph "<span style='color:red; font-weight:bold;'>\u26A0 Unsupported hub firmware:</span> Hub Diagnostics is developed and tested against platform ${MIN_FW_SUPPORTED} and later \u2014 your hub runs ${hubFw}. Some features may be unavailable or behave unexpectedly. Updating the hub firmware is recommended."
            }
            if (githubUpdateAvailable) {
                String editorPath = getAppEditorPath()
                String importLink = editorPath ? "<a href='${editorPath}' target='_blank'>Open Apps Code</a> and use Import to update." : "Update via Apps Code using Import."
                paragraph "<span style='color:orange; font-weight:bold;'>\u26A0 New version available:</span> v${remoteVersion} (you have v${APP_VERSION}). ${importLink}"
            }
            if (appUpdateNeeded) {
                paragraph "<span style='color:red; font-weight:bold;'>\u26A0 Update Recommended:</span> A newer UI version (${uiVer}) is active than this App code (${APP_VERSION}). Please update the Groovy App Code in Hubitat."
            }
            paragraph "<b>App Version:</b> ${APP_VERSION}\n<b>UI Version:</b> ${uiVer}\n<b>Hub Firmware:</b> ${hubFw ?: 'Unknown'}"
            if (!githubUpdateAvailable) {
                String editorPath = getAppEditorPath()
                if (editorPath) {
                    paragraph "<a href='${editorPath}' target='_blank'>Open App Code Editor</a> — update the Groovy source code via Import"
                }
            }
        }

        section("Dashboard") {
            String dashboardUrl = "${fullLocalApiServerUrl}/ui.html?access_token=${state.accessToken}"
            href url: dashboardUrl, title: "Open Dashboard", style: "external",
                 description: "Interactive diagnostic dashboard (opens in new tab)"
        }

        section("Documentation") {
            href url: "https://github.com/iamtrep/hubitat/tree/main/apps/HubDiagnostics", title: "Documentation & README",
                 style: "external", description: "View documentation, changelog, and usage guide on GitHub"
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
        section {
            paragraph "<i>Snapshots, checkpoints, and trigger switches re-arm when you save this page. After updating the app or UI code, open Settings and click <b>Done</b>.</i>"
        }

        section("Config Snapshots") {
            input "autoSnapshot", "bool", title: "Take config snapshots on a schedule", defaultValue: false, submitOnChange: true
            if (autoSnapshot) {
                input "snapshotInterval", "number", title: "Schedule interval (days)",
                    defaultValue: 1, range: "1..30", required: true
            }
            input "maxSnapshots", "number", title: "Maximum snapshots to retain", defaultValue: 10, range: "1..50", required: true
            input "snapshotTriggerSwitch", "capability.switch", title: "On-demand trigger switch (optional)",
                description: "Turning this switch ON captures a snapshot now. Switch it OFF then ON to trigger again.", required: false
        }

        section("Perf Checkpoints") {
            input "autoCheckpoint", "bool", title: "Record perf checkpoints on a schedule", defaultValue: false, submitOnChange: true
            if (autoCheckpoint) {
                input "checkpointInterval", "enum", title: "Schedule interval",
                    options: ["5": "5 minutes", "15": "15 minutes", "30": "30 minutes",
                             "60": "1 hour", "360": "6 hours", "720": "12 hours", "1440": "24 hours"],
                    defaultValue: "60", required: true
            }
            input "maxCheckpoints", "number", title: "Maximum checkpoints to keep", defaultValue: 10, range: "1..50", required: true
            input "checkpointTriggerSwitch", "capability.switch", title: "On-demand trigger switch (optional)",
                description: "Turning this switch ON records a checkpoint now. Switch it OFF then ON to trigger again.", required: false
        }

        section("Device Monitoring") {
            input "inactivityDays", "number", title: "Device inactivity threshold (days)", defaultValue: 7, range: "1..90", required: true
            input "lowBatteryThreshold", "number", title: "Low battery threshold (%)", defaultValue: 20, range: "1..50", required: true
            input "chattyDeviceThreshold", "number", title: "Chatty device threshold (msgs/min)", defaultValue: 10, range: "1..1000", required: true
            paragraph "<i>Devices exceeding this message rate between perf checkpoints will be flagged as chatty.</i>"
        }

        section("Alert Thresholds") {
            paragraph "Adjust these to match your hub model and environment. Defaults suit most C-8/C-8 Pro setups."
            input "warnMemMb",   "number",  title: "Free memory warning (MB)",    defaultValue: DEFAULT_WARN_MEM_MB,   range: "10..2000", required: true
            input "critMemMb",   "number",  title: "Free memory critical (MB)",   defaultValue: DEFAULT_CRIT_MEM_MB,   range: "10..2000", required: true
            input "warnCpuLoad", "decimal", title: "CPU load average warning",    defaultValue: DEFAULT_WARN_CPU_LOAD, range: "0.1..32",  required: true
            input "critCpuLoad", "decimal", title: "CPU load average critical",   defaultValue: DEFAULT_CRIT_CPU_LOAD, range: "0.1..32",  required: true
            String tempScale = getTemperatureScale()
            // Re-derive the scale inputs from the canonical Celsius thresholds on first render and
            // after a hub scale change, so switching scales never reinterprets a stored value.
            // Within a scale the inputs are left untouched; updated() converts them back to °C.
            if (settings.warnTempInput == null || state.tempInputScale != tempScale) {
                app.updateSetting("warnTempInput", [type: "number", value: warnTempDisplayValue()])
                app.updateSetting("critTempInput", [type: "number", value: critTempDisplayValue()])
                state.tempInputScale = tempScale
            }
            input "warnTempInput", "number", title: "Hub temperature warning (°${tempScale})",  range: tempThresholdRange(), required: true
            input "critTempInput", "number", title: "Hub temperature critical (°${tempScale})", range: tempThresholdRange(), required: true
        }

        section("Integration Overrides") {
            paragraph "Most integrations need no setup — Hub Diagnostics derives the connection type from the " +
                "hub's own LAN flag and the display name from the parent app. To correct a connection type the hub " +
                "can't infer (e.g. a LAN bridge, or a local device the hub flags as cloud), create " +
                "<b>${INTEGRATION_OVERRIDES_FILE}</b> in File Manager. " +
                "Keys are lowercase substrings matched against the device's parent-app name, or — for a " +
                "standalone device with no parent app — its driver type name; " +
                "valid <code>conn</code> values are: paired, lan_direct, lan_bridge, cloud, virtual, hubmesh, other. " +
                "Save this page after uploading the file to apply the changes. " +
                "A documented template (<i>integration_overrides.json</i>) ships with the app to start from."
        }

        section("Logging") {
            input "debugLogging", "bool", title: "Enable debug logging", defaultValue: false
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

    // v5.15.0: removed inline sync check from the hot path. Daily UI sync runs as a scheduled
    // job (see initialize). Emergency sync still triggers below if the file is missing.

    try {
        String html = loadUITemplate()
        if (!html) {
            logError "hub_diagnostics_ui.html missing from hub. Attempting emergency sync..."
            if (syncUIBlocking()) html = loadUITemplate()
        }
        if (!html) return render(status: 404, contentType: 'text/plain', data: 'UI file not found. Check hub logs.')

        html = html.replace('${access_token}', state.accessToken)
            .replace('${api_base}', fullLocalApiServerUrl)
            .replace('${live_refresh_sec}', (settings.liveRefreshSec ?: 30).toString())
        return render(status: 200, contentType: 'text/html', data: html)
    } catch (Exception e) {
        logError "Error serving UI: ${e.message}"
        return render(status: 500, contentType: 'text/plain', data: "Error serving UI: ${e.message}")
    }
}

// Returns the last-known GitHub version immediately (stale-while-revalidate).
// State-backed so the cached value survives reboots; asynchttpGet handles the refresh.
String checkGithubVersion() {
    long lastCheck = state.lastGithubVersionCheck ?: 0
    if (now() - lastCheck >= 3600000 && !githubVersionRefreshPending) {
        githubVersionRefreshPending = true
        asynchttpGet('githubVersionCallback', [uri: IMPORT_URL_APP, contentType: "text/plain", timeout: 10])
    }
    return state.lastGithubVersion
}

void githubVersionCallback(resp, data) {
    githubVersionRefreshPending = false
    if (resp.hasError() || resp.status != 200) {
        logDebug "GitHub version check failed: HTTP ${resp.status}"
        return
    }
    try {
        String text = resp.data ?: ""
        java.util.regex.Matcher m = text =~ /APP_VERSION\s*=\s*"([^"]+)"/
        if (m.find()) {
            state.lastGithubVersion = m.group(1)
            state.lastGithubVersionCheck = now()
        }
    } catch (Exception e) {
        logDebug "GitHub version callback error: ${e.message}"
    }
}

Map apiSyncUI() {
    logInfo "Manual UI sync requested via API..."
    boolean success = syncUIBlocking()
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

/**
 * Build a request-scoped shared cache so downstream getXxxData methods reuse common datasets
 * instead of re-fetching them. Pre-fix (v5.13.x), a single /api/dashboard call hit /hub2/hubData
 * twice (getHubInfo + fetchHubAlerts via getAlertSignals) and fetched system resources twice
 * (once directly, once via getAlertSignals fallback). With the shared cache populated, both
 * are fetched once and reused.
 *
 * @param includeNetwork  set true when the caller will use network/runtimeStats (Network/Performance tabs);
 *                        defaults false because analyzeNetwork is heavier than the savings on Dashboard/Health.
 */
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

// Wrap an API aggregator: time it, log "apiX completed in Nms", record the timing, and JSON-wrap the result.
private Map timed(String name, Closure<Map> body) {
    long start = now()
    Map data = body.call()
    long elapsed = now() - start
    logDebug "api${name.capitalize()} completed in ${elapsed}ms"
    recordApiTiming(name, elapsed)
    return jsonResponse(data)
}

// Aggregator: shared cache over multiple hub resources with fail-soft fallbacks.
Map apiDashboard() { return timed("dashboard") { getDashboardData(buildSharedCache(false)) } }

String getUIVersion() {
    if (uiVersionCache) return uiVersionCache
    try {
        byte[] htmlBytes = downloadHubFile('hub_diagnostics_ui.html')
        if (htmlBytes) {
            String html = new String(htmlBytes, 'UTF-8')
            java.util.regex.Matcher m = (html =~ /const UI_VERSION = "([^"]+)"/)
            if (m.find()) {
                String ver = m.group(1)
                uiVersionCache = ver
                return ver
            }
        }
    } catch (Exception e) {
        logDebug "Error reading UI version: ${e.message}"
    }
    return "Unknown"
}

// Aggregator: device classification and fullJson enrichment join.
Map apiDevices() { return timed("devices") { getDevicesData() } }

// Aggregator: joins apps list with runtime stats; surfaces parent/child structure.
Map apiApps() { return timed("apps") { getAppsData() } }

Map apiCode() {
    return timed("code") {
        [
            appTypes: fetchUserAppTypes(),
            driverTypes: fetchUserDriverTypes(),
            bundles: fetchUserBundles(),
            libraries: fetchUserLibraries(),
            hubVariables: fetchHubVariables()
        ]
    }
}

// Aggregator: normalizes multiple hub resources; adds mesh and health derivations.
Map apiNetwork() {
    return timed("network") {
        // Network tab needs hubData (for fetchSecurityInfo's cloudController flag); rest is fetched by analyzeNetwork
        Map shared = [:]
        Map hubDataWrap = hubMapRequest(HUB_DATA_PATH, "hub data (shared)", 10)
        shared.hubData = hubDataWrap.ok ? hubDataWrap.data : null
        getNetworkData(shared)
    }
}

// Aggregator: cross-resource health summary with alert shaping.
Map apiHealth() { return timed("health") { getHealthData(buildSharedCache(false)) } }

// Aggregator: parses a hub text endpoint into a stable structured payload.
Map apiHealthHistory() {
    List memHistory = fetchMemoryHistory()
    return jsonResponse([dataPoints: memHistory ?: []])
}

// Aggregator on the hot path (polled by the SPA every few seconds):
// consolidates polling and centralizes fail-soft semantics.
Map apiLive() {
    Map res = fetchSystemResources() ?: [:]
    return jsonResponse([
        freeOSMemory    : res.freeOSMemory,
        cpuAvg5min      : res.cpuAvg5min,
        totalJavaMemory : res.totalJavaMemory,
        freeJavaMemory  : res.freeJavaMemory,
        directJavaMemory: res.directJavaMemory,
        temperature     : fetchTemperature(),
        databaseSize    : fetchDatabaseSize(),
        cpuInfo         : fetchCpuInfo(),
        loadThreshold   : fetchExcessiveLoadThreshold()
    ])
}

Map apiPerformance() { return timed("performance") { getPerformanceData() } }

Map apiZigbeeScan() {
    long start = now()
    Map result = runZigbeeChannelScan()
    long elapsed = now() - start
    logDebug "apiZigbeeScan completed in ${elapsed}ms"
    boolean ok = result.error == null
    return jsonResponse([success: ok, scan: result, elapsedMs: elapsed])
}

Map apiPerformanceCompare() {
    String baseline = params.baseline
    String checkpoint = params.checkpoint
    if (!baseline || !checkpoint) {
        return jsonResponse([success: false, error: "Missing baseline or checkpoint parameter"])
    }

    // v5.33.0: read from the slim index, then load only the detail file(s) we need.
    List idx = loadCheckpointIndex()
    Map baselineStats
    String baselineLabel
    Map checkpointStats
    String checkpointLabel

    // Resolve baseline
    if (baseline == "startup") {
        // Will build zero baseline after resolving checkpoint
        baselineLabel = "Startup (0:00:00)"
    } else {
        if (!baseline?.isInteger()) return jsonResponse([success: false, error: "Invalid baseline index"])
        int bIdx = baseline.toInteger()
        if (bIdx < 0 || bIdx >= idx.size()) return jsonResponse([success: false, error: "Invalid baseline index"])
        Map bCp = loadCheckpointDetail(idx[bIdx].detailFile as String)
        if (!bCp) return jsonResponse([success: false, error: "Baseline detail file missing"])
        baselineStats = bCp.stats + [resources: bCp.resources, radioStats: bCp.radioStats, timestampMs: bCp.timestampMs, temperature: bCp.temperature, databaseSize: bCp.databaseSize]
        baselineLabel = bCp.timestamp
    }

    // Resolve checkpoint
    if (checkpoint == "now") {
        Map statsWrap = hubMapRequest(RUNTIME_STATS_PATH, "runtime stats")
        if (!statsWrap.ok) return jsonResponse([success: false, error: "Unable to fetch current runtime stats"])
        checkpointStats = statsWrap.data
        Map currentResources = fetchSystemResources()
        checkpointStats.resources = currentResources
        // v5.32.6: cache-first radio fetch with bounded budget (8s, no retry) — same
        // pattern as scheduled createCheckpoint. Keeps Compare → Now from pinning the
        // app thread for tens of seconds on a stressed hub.
        Map zwaveData = fetchZwaveDataForCheckpoint() ?: [:]
        Map zigbeeData = fetchZigbeeDataForCheckpoint() ?: [:]
        checkpointStats.radioStats = [
            zwave: extractZwaveMessageCounts(zwaveData),
            zigbee: extractZigbeeMessageCounts(zigbeeData)
        ]
        checkpointStats.temperature = fetchTemperature()
        checkpointStats.databaseSize = fetchDatabaseSize()
        checkpointStats.timestampMs = now()
        checkpointLabel = "Now (${new Date().format('yyyy-MM-dd HH:mm:ss')})"
    } else {
        if (!checkpoint?.isInteger()) return jsonResponse([success: false, error: "Invalid checkpoint index"])
        int cIdx = checkpoint.toInteger()
        if (cIdx < 0 || cIdx >= idx.size()) return jsonResponse([success: false, error: "Invalid checkpoint index"])
        Map cCp = loadCheckpointDetail(idx[cIdx].detailFile as String)
        if (!cCp) return jsonResponse([success: false, error: "Checkpoint detail file missing"])
        checkpointStats = cCp.stats + [resources: cCp.resources, radioStats: cCp.radioStats, timestampMs: cCp.timestampMs, temperature: cCp.temperature, databaseSize: cCp.databaseSize]
        checkpointLabel = cCp.timestamp
    }

    // Ensure uptimeSeconds is present — needed by the JS diffStats elapsedMs fallback for startup comparisons
    if (!checkpointStats.uptimeSeconds && checkpointStats.uptime) {
        checkpointStats = checkpointStats + [uptimeSeconds: parseUptime(checkpointStats.uptime as String)]
    }

    // The "startup" baseline is a zeroed structural mirror of the checkpoint. The SPA now
    // synthesizes it client-side (zeroBaseline) so the hub ships no derived baseline — for a
    // startup comparison baselineStats stays null and baselineMode tells the SPA to fill it in.
    String baselineMode = (baseline == "startup") ? "startup" : "checkpoint"

    // Save for persistence
    savePerformanceComparisonPayload([
        generatedAt: new Date().format("yyyy-MM-dd HH:mm:ss"),
        baselineLabel: baselineLabel, checkpointLabel: checkpointLabel, baselineMode: baselineMode,
        baselineStats: baselineStats, checkpointStats: checkpointStats ?: [:]
    ])

    return jsonResponse([
        success: true,
        baselineLabel: baselineLabel,
        checkpointLabel: checkpointLabel,
        baselineMode: baselineMode,
        baselineStats: baselineStats,
        checkpointStats: checkpointStats
    ])
}

Map migrateSnapshotDevices(Map snapshotDevices) {
    if (!snapshotDevices?.byProtocol || snapshotDevices?.byConnectionType) return snapshotDevices
    Map protoToConn = [
        zigbee: CONN_PAIRED, zwave: CONN_PAIRED, matter: CONN_PAIRED,
        lan: CONN_LAN_DIRECT, virtual: CONN_VIRTUAL,
        cloud: CONN_CLOUD, hubmesh: CONN_HUBMESH, maker: CONN_OTHER, other: CONN_OTHER
    ]
    Map byConn = [:]
    snapshotDevices.byProtocol.each { k, v ->
        String conn = protoToConn[k] ?: CONN_OTHER
        byConn[conn] = (byConn[conn] ?: 0) + (v ?: 0)
    }
    snapshotDevices.byConnectionType = byConn
    snapshotDevices.byIntegration = snapshotDevices.byProtocol.collectEntries { k, v ->
        [(LEGACY_PROTOCOL_DISPLAY[k] ?: k): v]
    }
    snapshotDevices.allDevices = (snapshotDevices.allDevices ?: []).collect { Map dev ->
        if (dev.protocol && !dev.connectionType) {
            dev.connectionType = protoToConn[dev.protocol] ?: CONN_OTHER
            dev.integration = LEGACY_PROTOCOL_DISPLAY[dev.protocol] ?: dev.protocol
        }
        return dev
    }
    return snapshotDevices
}

Map apiSnapshots() {
    return jsonResponse(getSnapshotsData())
}

Map apiSnapshotView() {
    String idxStr = params.index ?: "-1"
    if (!idxStr.isInteger()) return jsonResponse([error: "Invalid snapshot index"])
    int idx = idxStr.toInteger()
    List snapshots = loadSnapshots()
    if (idx < 0 || idx >= snapshots.size()) return jsonResponse([error: "Invalid snapshot index"])
    Map snap = snapshots[idx]
    if (snap.devices) snap.devices = migrateSnapshotDevices(snap.devices)

    Map snapNet = snap.network ?: [:]
    return jsonResponse([
        timestamp: snap.timestamp,
        timestampMs: snap.timestampMs,
        hubInfo: snap.hubInfo,
        devices: [
            totalDevices: snap.devices?.totalDevices ?: 0,
            activeDevices: snap.devices?.activeDevices ?: 0,
            inactiveDevices: snap.devices?.inactiveDevices ?: 0,
            disabledDevices: snap.devices?.disabledDevices ?: 0,
            byConnectionType: snap.devices?.byConnectionType,
            byIntegration: snap.devices?.byIntegration,
            allDevices: (snap.devices?.allDevices ?: []).collect { Map dev ->
                [id: dev.id, name: dev.name, type: dev.type,
                 connectionType: dev.connectionType, integration: dev.integration, status: dev.status]
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
        ],
        network: snapNet ? [
            zigbee:  snapNet.zigbee  && !snapNet.zigbee.error  ? [enabled: snapNet.zigbee.enabled,  channel: snapNet.zigbee.channel]  : null,
            zwave:   snapNet.zwave   && !snapNet.zwave.error   ? [enabled: snapNet.zwave.enabled,   region: snapNet.zwave.region,   nodeCount: (snapNet.zwave.zwDevices ?: [:]).size()] : null,
            matter:  snapNet.matter  && !snapNet.matter.error  ? [enabled: snapNet.matter.enabled,  installed: snapNet.matter.installed]  : null,
            hubMesh: snapNet.hubMesh && !snapNet.hubMesh.error ? [
                enabled: snapNet.hubMesh.hubMeshEnabled != null ? snapNet.hubMesh.hubMeshEnabled : snapNet.hubMesh.enabled,
                peers:   (snapNet.hubMesh.hubList ?: []).collect { [name: it.name, ip: it.ipAddress] }
            ] : null
        ] : null,
        storage: snap.storage,
        // v5.13.0 additions
        backups: snap.backups,
        security: snap.security,
        ntpServer: snap.ntpServer,
        loadThreshold: snap.loadThreshold,
        code: snap.code
    ])
}

Map apiCreateSnapshot() {
    // C1: createSnapshot() is void and swallows save failures (writeFile catches internally), so a
    // failed live snapshot would otherwise return success and the SPA would diff against a stale
    // snapshot with no error. Detect real success by checking the newest snapshot's timestampMs
    // actually changed — robust even at the retention cap, where the total count stays flat.
    def prevNewestMs = loadSnapshots()?.getAt(0)?.timestampMs
    createSnapshot()
    List snapshots = loadSnapshots()
    def newestMs = snapshots?.getAt(0)?.timestampMs
    if (newestMs == null || newestMs == prevNewestMs) {
        logWarn "apiCreateSnapshot: live snapshot did not persist (newest timestampMs unchanged) — creation failed"
        return jsonResponse([success: false, error: "Failed to create live snapshot — check hub logs", snapshotCount: snapshots?.size() ?: 0])
    }
    return jsonResponse([success: true, snapshotCount: snapshots.size()])
}

Map apiDeleteSnapshot() {
    String idxStr = params.index ?: "-1"
    if (!idxStr.isInteger()) return jsonResponse([success: false, error: "Invalid index"])
    int idx = idxStr.toInteger()
    if (idx < 0) return jsonResponse([success: false, error: "Invalid index"])
    deleteSnapshot(idx)
    return jsonResponse([success: true])
}

Map apiCreateCheckpoint() {
    if (!createCheckpoint()) return jsonResponse([success: false, error: "Checkpoint creation failed or already in progress"])
    return jsonResponse([success: true, checkpointCount: getCheckpointIndex().size()])
}

Map apiDeleteCheckpoint() {
    String idxStr = params.index ?: "-1"
    if (!idxStr.isInteger()) return jsonResponse([success: false, error: "Invalid index"])
    int idx = idxStr.toInteger()
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

// Returns the raw SPA template (with placeholders intact). The SPA fetches this,
// strips placeholders, injects window.REPORT_DATA, and POSTs the resulting HTML
// to /api/report/save. Replaces the heavier server-side apiGenerateReport pipeline.
Map apiReportTemplate() {
    String html = loadUITemplate()
    if (!html) return render(status: 404, contentType: 'application/json', data: '{"error":"SPA template not found in File Manager"}')
    return render(contentType: 'text/html', data: html)
}

// Thin file-write endpoint. Body is JSON: {filename, html}. The SPA assembles the
// report client-side (parallel data fetches, template injection, placeholder strip)
// and just hands the finished bytes here for FileManager persistence.
Map apiSaveReport() {
    Map body = (request?.JSON instanceof Map) ? (Map) request.JSON : null
    if (!body) return jsonResponse([success: false, error: "Empty or invalid JSON body"])
    String filename = (body.filename ?: "") as String
    String html = (body.html ?: "") as String
    if (!filename || !html) return jsonResponse([success: false, error: "filename and html are required"])
    if (!filename.endsWith('.html')) return jsonResponse([success: false, error: "filename must end with .html"])
    if (filename.contains('/') || filename.contains('..')) return jsonResponse([success: false, error: "invalid filename"])

    writeFile(filename, html)
    state.lastReportFile = filename
    logInfo "Report saved: ${filename} (${(html.length() / 1024).intValue()} KB)"
    return jsonResponse([success: true, filename: filename])
}

Map apiGetSettings() {
    return jsonResponse([
        autoSnapshot:          settings.autoSnapshot ?: false,
        snapshotInterval:      settings.snapshotInterval ?: 1,
        maxSnapshots:          (settings.maxSnapshots ?: 10) as int,
        autoCheckpoint:        settings.autoCheckpoint ?: false,
        checkpointInterval:    settings.checkpointInterval ?: "60",
        maxCheckpoints:        (settings.maxCheckpoints ?: 10) as int,
        inactivityDays:        (settings.inactivityDays ?: 7) as int,
        lowBatteryThreshold:   (settings.lowBatteryThreshold ?: 20) as int,
        chattyDeviceThreshold: (settings.chattyDeviceThreshold ?: 10) as int,
        warnMemMb:             (settings.warnMemMb   ?: DEFAULT_WARN_MEM_MB)   as int,
        critMemMb:             (settings.critMemMb   ?: DEFAULT_CRIT_MEM_MB)   as int,
        warnCpuLoad:           (settings.warnCpuLoad ?: DEFAULT_WARN_CPU_LOAD) as double,
        critCpuLoad:           (settings.critCpuLoad ?: DEFAULT_CRIT_CPU_LOAD) as double,
        warnTempC:             warnTempCValue(),
        critTempC:             critTempCValue(),
        temperatureScale:      getTemperatureScale(),
        debugLogging:            settings.debugLogging ?: false,
        obfuscateForumExport:    settings.obfuscateForumExport ?: false,
        liveRefreshSec:          (settings.liveRefreshSec ?: 30) as int,
        cacheSize:               (state.controllerTypeCache ?: [:]).size()
    ])
}

Map apiUpdateSettings() {
    Map body = [:]
    String dataStr = params?.data as String
    if (dataStr) {
        try { body = (Map) new groovy.json.JsonSlurper().parseText(dataStr) } catch (Exception ignored) {}
    }
    if (!body) return jsonResponse([success: false, error: "Empty or invalid body"])

    Set boolKeys    = ["autoSnapshot", "autoCheckpoint", "debugLogging", "obfuscateForumExport"] as Set
    Set numberKeys  = ["maxSnapshots", "maxCheckpoints", "inactivityDays", "lowBatteryThreshold",
                        "chattyDeviceThreshold", "warnMemMb", "critMemMb",
                        "snapshotInterval", "liveRefreshSec"] as Set
    Set decimalKeys = ["warnCpuLoad", "critCpuLoad", "warnTempC", "critTempC"] as Set
    Set enumKeys    = ["checkpointInterval"] as Set
    boolean reschedule = false

    body.each { String key, Object value ->
        if (boolKeys.contains(key)) {
            app.updateSetting(key, [type: "bool", value: value as boolean])
            if (key in ["autoSnapshot", "autoCheckpoint"]) reschedule = true
        } else if (numberKeys.contains(key)) {
            String numStr = value.toString()
            if (!numStr.isInteger()) return
            app.updateSetting(key, [type: "number", value: numStr.toInteger()])
            if (key == "snapshotInterval") reschedule = true
        } else if (decimalKeys.contains(key)) {
            String numStr = value.toString()
            if (!numStr.isBigDecimal()) return
            app.updateSetting(key, [type: "decimal", value: numStr.toBigDecimal()])
        } else if (enumKeys.contains(key)) {
            app.updateSetting(key, [type: "enum", value: value as String])
            if (key == "checkpointInterval") reschedule = true
        }
    }
    if (reschedule) { unsubscribe(); unschedule(); initialize() }
    return jsonResponse([success: true])
}

Map apiClearCache() {
    int cleared = (state.controllerTypeCache ?: [:]).size()
    state.controllerTypeCache = [:]
    return jsonResponse([success: true, cleared: cleared])
}

private Map buildHubMap(Map hubInfo, Hub hub) {
    return [name: hubInfo.name, hubId: hub?.id, hardware: hubInfo.hardware,
            firmware: hubInfo.firmware, ip: hubInfo.ip, zigbeeId: hub?.zigbeeId,
            location: location.name, mode: location.currentMode?.toString(),
            timeZone: location.timeZone?.ID, zwaveStack: detectZwaveStack()]
}

// ===== DATA GATHERERS =====
// Each returns a plain Map suitable for both jsonResponse() and report embedding.

Map getDashboardData(Map shared = [:]) {
    Map deviceStats = analyzeDevices(false)
    Map appStats = analyzeApps(false)
    Map hubInfo = getHubInfo(shared.hubData as Map)
    Hub hub = (location.hubs && location.hubs.size() > 0) ? location.hubs[0] : null
    Map resources        = (shared.resources as Map)        ?: fetchSystemResources()
    Float temperature    = (shared.temperature as Float)    ?: fetchTemperature()
    Integer databaseSize = (shared.databaseSize as Integer) ?: fetchDatabaseSize()
    return [
        hub: buildHubMap(hubInfo, hub), appVersion: APP_VERSION, uiVersion: getUIVersion(),
        devices: [
            total: deviceStats.totalDevices, active: deviceStats.activeDevices,
            inactive: deviceStats.inactiveDevices, disabled: deviceStats.disabledDevices,
            byConnectionType: deviceStats.byConnectionType, idsByConnectionType: deviceStats.idsByConnectionType,
            byIntegration: deviceStats.byIntegration, idsByIntegration: deviceStats.idsByIntegration,
            idsByStatus: deviceStats.idsByStatus
        ],
        apps: [total: appStats.totalApps, builtIn: appStats.builtInApps, user: appStats.userApps],
        resources: resources, temperature: temperature, databaseSize: databaseSize,
        alertSignals: getAlertSignals(shared), inactivityDays: settings.inactivityDays ?: 7,
        firmwareUpdate: fetchFirmwareUpdate()
    ]
}

Map getDevicesData() {
    Map deviceStats = analyzeDevices()
    List deviceRows = (deviceStats.allDevices ?: []).collect { Map dev ->
        [id: dev.id, name: dev.name, type: dev.type,
         connectionType: dev.connectionType,
         connectionTypeDisplay: CONN_DISPLAY[dev.connectionType] ?: dev.connectionType,
         integration: dev.integration,
         room: dev.room, status: dev.status ?: "", lastActivity: dev.lastActivity ?: "Never",
         battery: dev.battery, parentAppId: dev.parentAppId, parentAppName: dev.parentAppName,
         parentDeviceId: dev.parentDeviceId, parentDeviceName: dev.parentDeviceName,
         userType: dev.userType ?: false, deviceTypeId: dev.deviceTypeId]
    }
    List lowBattery = (deviceStats.lowBatteryDevices ?: []).collect { Map dev ->
        [id: dev.id, name: dev.name, type: dev.type, battery: dev.battery]
    }
    return [
        summary: [totalDevices: deviceStats.totalDevices, activeDevices: deviceStats.activeDevices,
                  inactiveDevices: deviceStats.inactiveDevices, disabledDevices: deviceStats.disabledDevices,
                  parentDevices: deviceStats.parentDevices, childDevices: deviceStats.childDevices,
                  linkedDevices: deviceStats.linkedDevices, batteryDevices: deviceStats.batteryDevices,
                  parentIds: deviceStats.parentIds, childIds: deviceStats.childIds,
                  linkedIds: deviceStats.linkedIds, batteryIds: deviceStats.batteryIds],
        byConnectionType: deviceStats.byConnectionType, idsByConnectionType: deviceStats.idsByConnectionType,
        byIntegration: deviceStats.byIntegration, idsByIntegration: deviceStats.idsByIntegration,
        integrationSources: deviceStats.integrationSources,
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
                    parentId: it.parentAppId, disabled: it.disabled ?: false] }
    List platformEntries = (appStats.platformApps ?: []).collect { Map p ->
        [id: p.id, name: p.name, type: p.name, user: false, source: "platform",
         disabled: false, hidden: false, setting: false, menu: "", level: 0, childCount: 0, parentId: null]
    }
    List allApps = ((appStats.allApps ?: []) + platformEntries)
        .sort { Map a -> "${a.type ?: ''} ${a.name ?: ''}".toLowerCase() }
    boolean hasMenuData = allApps.any { it.menu as boolean }
    return [
        summary: [totalApps: appStats.totalApps, builtInApps: appStats.builtInApps, userApps: appStats.userApps,
                  parentApps: appStats.parentApps, childApps: appStats.childApps,
                  runtimeTotalApps: appStats.runtimeTotalApps],
        byNamespace: appStats.byNamespace,
        userApps: userAppRows, parentChildHierarchy: appStats.parentChildHierarchy,
        allApps: allApps, hasMenuData: hasMenuData,
        builtInInstances: appStats.builtInInstances
    ]
}

Map getNetworkData(Map shared = [:]) {
    Map networkData = (shared.network as Map) ?: analyzeNetwork()
    Map statsRaw = (Map) shared.runtimeStats
    if (!statsRaw) { Map r = hubMapRequest(RUNTIME_STATS_PATH, "runtime stats"); statsRaw = r.ok ? r.data : null }
    Map stats = statsRaw
    Integer uptimeSeconds = stats ? parseUptime(stats.uptime as String) : null
    Map zigbeeMesh = fetchZigbeeMeshInfo()
    String zwaveVersion = fetchZwaveVersion()
    Map zwaveMesh = extractZwaveMeshQuality(networkData.zwave ?: [:])
    List ghostNodes = buildZwaveGhostNodes(networkData.zwave ?: [:])
    Map zigbeeRaw = networkData.zigbee ?: [:]
    Map zigbeeDeviceByShortId = [:]
    (zigbeeRaw.devices ?: []).each { Map d -> if (d.shortZigbeeId) zigbeeDeviceByShortId[((String)d.shortZigbeeId).toUpperCase()] = d }
    Map hubMeshRaw = networkData.hubMesh ?: [:]
    List hubMeshPeers = hubMeshRaw.hubList ? hubMeshRaw.hubList.collect { Map hub ->
        [name: hub.name, ip: hub.ipAddress, offline: hub.offline,
         deviceCount: hub.deviceIds?.size() ?: 0, varCount: hub.hubVarNames?.size() ?: 0]
    } : []
    return [
        uptimeSeconds: uptimeSeconds,
        network: networkData.network ?: null,
        zwave: networkData.zwave ? [
            enabled: networkData.zwave.enabled, healthy: networkData.zwave.healthy,
            region: networkData.zwave.region, nodeCount: (networkData.zwave.zwDevices ?: [:]).size(),
            isRadioUpdateNeeded: networkData.zwave.isRadioUpdateNeeded,
            zwaveJS: networkData.zwave.zwaveJS, zwaveJSAvailable: networkData.zwave.zwaveJSAvailable,
            version: zwaveVersion,
            mesh: zwaveMesh, ghostNodes: ghostNodes,
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
                childDevices: zigbeeMesh.childDevices?.size() ?: 0
            ] : null
        ] : null,
        matter: networkData.matter ?: null,
        hubMesh: networkData.hubMesh ? [
            enabled: hubMeshRaw.hubMeshEnabled != null ? hubMeshRaw.hubMeshEnabled : hubMeshRaw.enabled,
            sharedDevices: hubMeshRaw.sharedDevices?.size() ?: 0,
            linkedDevices: hubMeshRaw.linkedDevices?.size() ?: 0,
            sharedVars: hubMeshRaw.sharedVars?.size() ?: 0, linkedVars: hubMeshRaw.linkedVars?.size() ?: 0,
            peers: hubMeshPeers
        ] : null,
        radioHealth: fetchRadioHealth(),
        zwaveJs: fetchZwaveJsState(),
        ntpServer: fetchNtpServer(),
        mdns: fetchMdns(),
        zipgatewayVersion: fetchZipgatewayVersion(),
        security: fetchSecurityInfo(shared.hubData as Map),
        zigbeeChannelScan: fetchCachedZigbeeScan(),
        zwaveTopologyHtml: fetchZwaveTopology()
    ]
}

Map getHealthData(Map shared = [:]) {
    Map systemHealth = analyzeSystemHealth(shared)
    Map hubInfo = getHubInfo(shared.hubData as Map)
    Hub hub = (location.hubs && location.hubs.size() > 0) ? location.hubs[0] : null
    Map mem = systemHealth.memory ?: [:]
    return [
        hub: buildHubMap(hubInfo, hub),
        resources: mem ?: null, temperature: systemHealth.temperature,
        databaseSize: systemHealth.databaseSize, stateCompression: systemHealth.stateCompression,
        eventStateLimits: systemHealth.eventStateLimits, alertSignals: getAlertSignals(shared),
        firmwareUpdate: fetchFirmwareUpdate(),
        storage: fetchFileManagerStats(),
        backups: fetchBackups(),
        loadThreshold: fetchExcessiveLoadThreshold(),
        cpuInfo: fetchCpuInfo(),
        events: fetchHubEvents()
    ]
}

Map getPerformanceData(Map shared = [:]) {
    // v5.33.1: per-phase timing instrumentation to break down cold-load wall time.
    // Each phase records (a) elapsed ms, (b) whether the fetch hit the @Field static
    // cache or made a fresh httpGet. Summary logged at end as a single info line.
    Map t = [:]
    long t1
    boolean zwaveCache = false, zigbeeCache = false, appsCache = false, devicesCache = false

    t1 = now()
    Map stats
    if (shared.runtimeStats) { stats = (Map) shared.runtimeStats } else { Map r = hubMapRequest(RUNTIME_STATS_PATH, "runtime stats"); stats = r.ok ? r.data : null }
    t.runtime = now() - t1

    t1 = now()
    Map resources  = (shared.resources as Map) ?: fetchSystemResources()
    t.resources = now() - t1

    t1 = now()
    Map zwaveData
    if (shared.network?.zwave) {
        zwaveData = (Map) shared.network.zwave
        zwaveCache = true
    } else {
        long nowMs = now()
        if (cachedZwaveData && cachedZwaveAt && (nowMs - cachedZwaveAt) < RADIO_CACHE_TTL_MS) {
            zwaveData = cachedZwaveData
            zwaveCache = true
            logDebug "Using cached Z-Wave data (age ${nowMs - cachedZwaveAt}ms)"
        } else {
            Map r = hubMapRequest(ZWAVE_DETAILS_PATH, "Z-Wave details", 20)
            zwaveData = r.ok ? r.data : null
            if (zwaveData) { cachedZwaveData = zwaveData; cachedZwaveAt = nowMs }
        }
    }
    t.zwave = now() - t1

    t1 = now()
    Map zigbeeData
    if (shared.network?.zigbee) {
        zigbeeData = (Map) shared.network.zigbee
        zigbeeCache = true
    } else {
        long nowMs = now()
        if (cachedZigbeeData && cachedZigbeeAt && (nowMs - cachedZigbeeAt) < RADIO_CACHE_TTL_MS) {
            zigbeeData = cachedZigbeeData
            zigbeeCache = true
            logDebug "Using cached Zigbee data (age ${nowMs - cachedZigbeeAt}ms)"
        } else {
            Map r = hubMapRequest(ZIGBEE_DETAILS_PATH, "Zigbee details", 20)
            zigbeeData = r.ok ? r.data : null
            if (zigbeeData) { cachedZigbeeData = zigbeeData; cachedZigbeeAt = nowMs }
        }
    }
    t.zigbee = now() - t1

    t1 = now()
    List zwaveMsgCounts = extractZwaveMessageCounts(zwaveData)
    List zigbeeMsgCounts = extractZigbeeMessageCounts(zigbeeData)
    Map radioStats = [zwave: zwaveMsgCounts, zigbee: zigbeeMsgCounts]
    // Ship the raw per-device message counts; the SPA ranks the top talkers (sort + top-N).
    t.radioCalc = now() - t1

    t1 = now()
    // Enrich appStats with source labels (community/builtin/platform)
    Map appSourceById = [:]
    long nowApps = now()
    Map appsListResp
    if (cachedAppsListData && cachedAppsListAt && (nowApps - cachedAppsListAt) < HUB_LIST_CACHE_TTL_MS) {
        logDebug "Using cached apps list (age ${nowApps - cachedAppsListAt}ms)"
        appsListResp = cachedAppsListData
        appsCache = true
    } else {
        Map appsListWrap = hubMapRequest(APPS_LIST_PATH, "apps list")
        appsListResp = appsListWrap.ok ? appsListWrap.data : [:]
        if (appsListWrap.ok) { cachedAppsListData = appsListResp; cachedAppsListAt = nowApps }
    }
    t.appsFetch = now() - t1

    t1 = now()
    if (appsListResp.apps) {
        visitAppEntries(appsListResp.apps as List) { Map appEntry, Map app, boolean isChildLevel, List _ ->
            if (app?.id != null) appSourceById[app.id] = (app.user ? "community" : "builtin")
        }
    }
    t.appsSourceWalk = now() - t1

    t1 = now()
    if (stats) {
        stats.radioStats = radioStats
        stats.uptimeSeconds = parseUptime(stats.uptime as String)
        stats.temperature = (shared.temperature as Float) ?: fetchTemperature()
        stats.databaseSize = (shared.databaseSize as Integer) ?: fetchDatabaseSize()
        if (stats.appStats) {
            stats.appStats = (stats.appStats as List).collect { Map a ->
                a + [source: (appSourceById[a.id] ?: "platform")]
            }
        }
    }
    t.statsEnrich = now() - t1

    // R-7 B2 (v5.20.0): build small id→label maps for the Performance tab's CPU charts so the
    // SPA doesn't cross-fetch /api/devices and /api/apps just to look up names. Tiny payload
    // (counts × ~20 bytes), saves 2 client round trips of much heavier endpoints.
    // Walk /hub2/devicesList directly to build id→type map. Skips analyzeDevices' enrichment overhead
    // (we only need the raw type/name from the bulk endpoint, not the cross-classification work).
    t1 = now()
    Map deviceTypeById = [:]
    long nowDev = now()
    Map devListData
    if (cachedDevicesListData && cachedDevicesListAt && (nowDev - cachedDevicesListAt) < HUB_LIST_CACHE_TTL_MS) {
        logDebug "Using cached devices list (age ${nowDev - cachedDevicesListAt}ms)"
        devListData = cachedDevicesListData
        devicesCache = true
    } else {
        Map devWrap = hubMapRequest(DEVICES_LIST_PATH, "devices list (B2 labels)", 15)
        devListData = devWrap.ok ? devWrap.data : [:]
        if (devWrap.ok) { cachedDevicesListData = devListData; cachedDevicesListAt = nowDev }
    }
    t.devicesFetch = now() - t1

    t1 = now()
    if (devListData.devices) {
        flattenDeviceEntries(devListData.devices as List).each { Map entry ->
            Map dev = entry?.data instanceof Map ? (Map) entry.data : null
            if (dev?.id != null) deviceTypeById[dev.id] = (dev.type ?: 'Unknown') as String
        }
    }
    t.devicesWalk = now() - t1

    t1 = now()
    // Walk /hub2/appsList directly for parent→child label association — reuse the response already
    // fetched above for appSourceById rather than making a second round trip.
    Map appParentTypeById = [:]
    if (appsListResp?.apps) {
        Closure walkApps
        walkApps = { List apps, String parentLabel = null ->
            (apps ?: []).each { Map entry ->
                Map data = entry?.data instanceof Map ? (Map) entry.data : null
                if (!data) return
                Object id = data.id
                String myLabel = (data.label ?: data.name ?: 'Unknown') as String
                String labelForChildren = parentLabel ?: myLabel
                if (id != null) appParentTypeById[id] = labelForChildren
                if (entry.children) walkApps(entry.children as List, labelForChildren)
            }
        }
        walkApps(appsListResp.apps as List, null)
    }
    t.appsParentWalk = now() - t1

    t1 = now()
    // v5.33.0: read the slim checkpoint index. Backed by loadCheckpointIndex which
    // reads a small per-app index file (or migrates the legacy single-blob file once).
    // The Performance tab API never touches the full per-checkpoint detail files.
    List indexEntries = getCheckpointIndex()
    t.indexRead = now() - t1

    long totalMs = (t.values().sum() ?: 0) as long
    logDebug "getPerformanceData breakdown (sum ${totalMs}ms): " +
        "runtime=${t.runtime}ms resources=${t.resources}ms " +
        "zwave=${t.zwave}ms${zwaveCache ? '(cache)' : ''} zigbee=${t.zigbee}ms${zigbeeCache ? '(cache)' : ''} radioCalc=${t.radioCalc}ms " +
        "appsFetch=${t.appsFetch}ms${appsCache ? '(cache)' : ''} appsSourceWalk=${t.appsSourceWalk}ms statsEnrich=${t.statsEnrich}ms " +
        "devicesFetch=${t.devicesFetch}ms${devicesCache ? '(cache)' : ''} devicesWalk=${t.devicesWalk}ms appsParentWalk=${t.appsParentWalk}ms " +
        "indexRead=${t.indexRead}ms"
    return [
        stats: stats, resources: resources,
        radioStats: radioStats,
        deviceTypeById: deviceTypeById,        // B2: id → driver type for CPU-by-device-type chart
        appParentTypeById: appParentTypeById,  // B2: id → parent label for CPU-by-app-type chart
        checkpointCount: indexEntries.size(),
        maxCheckpoints: (settings.maxCheckpoints ?: 10) as int,
        checkpoints: indexEntries,
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

// Raw signals for the SPA to compose alerts client-side. Threshold-based
// alerts (memory/CPU/temperature) are derived in the SPA from `resources` +
// `temperature` + the `TH` thresholds it already loads via /api/settings.
// Hub-message HTML stripping also runs in the SPA \u2014 we ship raw text.
Map getAlertSignals(Map shared = [:]) {
    Map hubAlerts = (shared.hubAlerts as Map) ?: fetchHubAlerts(shared.hubData as Map)

    List platformAlerts = []
    if (hubAlerts?.alerts) {
        ALERT_DISPLAY_NAMES.each { String key, String displayName ->
            if (hubAlerts.alerts[key] == true) {
                String severity = (key in ["hubLoadSevere", "hubZwaveCrashed", "hubHugeDatabase", "zwaveOffline", "zigbeeOffline"]) ? "critical" : "warning"
                platformAlerts << [key: key, name: displayName, severity: severity]
            }
        }
    }

    List hubMessages = fetchHubMessages().collect { Map msg ->
        (msg.text ?: msg.message ?: msg.toString()) as String
    }.findAll { it } as List

    Map networkConfig = (Map) shared.network?.network
    if (!networkConfig) {
        Map r = hubMapRequest(NETWORK_CONFIG_PATH, "network configuration", 15)
        networkConfig = r.ok ? r.data : null
    }
    boolean ethernetAndWifi = (networkConfig && networkConfig.hasEthernet && networkConfig.hasWiFi) as boolean

    // Z-Wave ghost nodes \u2014 cached 60 s to avoid an 8-second fetch on every
    // Dashboard/Health load when the shared cache was built without includeNetwork.
    Map zwRaw = (shared.network?.zwave as Map)
    if (!zwRaw) {
        long lastZwCheck = state.lastZwaveGhostCheckMs ?: 0
        if (now() - lastZwCheck > 60000) {
            Map zwWrap = hubMapRequest(ZWAVE_DETAILS_PATH, "Z-Wave details", 8)
            zwRaw = zwWrap.ok ? zwWrap.data : null
            if (zwRaw) {
                state.lastZwaveGhostCheckMs = now()
                state.cachedZwaveSignals = computeZwaveSignals(zwRaw)
            }
        }
    }
    Map zwSignals = zwRaw ? computeZwaveSignals(zwRaw) : ((state.cachedZwaveSignals as Map) ?: [:])

    return [
        platformAlerts:       platformAlerts,
        spammyDevicesMessage: hubAlerts?.spammyDevicesMessage,
        hubMessages:          hubMessages,
        ethernetAndWifi:      ethernetAndWifi,
        zwaveGhostCount:      (zwSignals.ghostCount   ?: 0) as int,
        zwaveFailedCount:     (zwSignals.failedCount  ?: 0) as int,
        zwaveProblemCount:    (zwSignals.problemCount ?: 0) as int,
        zwaveRadioUpdate:     (zwSignals.radioUpdate == true)
    ]
}

// Derive the Z-Wave alert signals the SPA rolls up, all from a single zwaveDetails
// payload — the same fetch getAlertSignals already makes for ghost-node counting:
//   ghostCount   — orphaned nodes (no Hubitat device), safe to force-remove from radio
//   failedCount  — nodes with a Hubitat device that the radio reports down
//   problemCount — mesh nodes not in "OK" state or with packet-error-rate > 1%
//   radioUpdate  — Z-Wave radio firmware update available
Map computeZwaveSignals(Map zwRaw) {
    if (!zwRaw) return [ghostCount: 0, failedCount: 0, problemCount: 0, radioUpdate: false]
    List ghostNodes = buildZwaveGhostNodes(zwRaw)
    int ghostCount   = ghostNodes.count { it.kind == "ghost" } as int
    int failedCount  = ghostNodes.count { it.kind == "failed" } as int
    List meshNodes   = (extractZwaveMeshQuality(zwRaw).nodes ?: []) as List
    int problemCount = meshNodes.count { Map n -> n.state != "OK" || ((n.per ?: 0) as int) > 1 } as int
    return [
        ghostCount:   ghostCount,
        failedCount:  failedCount,
        problemCount: problemCount,
        radioUpdate:  (zwRaw.isRadioUpdateNeeded == true)
    ]
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
    return hubRequestInternal(path, name, type, timeout, true)
}

private Map hubMapRequest(String path, String name, int timeout = 30, boolean allowRetry = true) {
    Object raw = hubRequestInternal(path, name, "json", timeout, allowRetry)
    if (raw instanceof Map && ((Map) raw).error) {
        return [ok: false, data: [:], error: (String) ((Map) raw).message]
    }
    return [ok: true, data: (Map)(raw ?: [:]), error: null]
}

/**
 * Inner helper. allowRetry=true on first call; recurse with false after a transient error
 * (SocketTimeoutException, ConnectException) so we get exactly one retry per request, no more.
 * Permanent errors (4xx, malformed responses, sandbox issues) skip retry — pointless and noisy.
 */
private Object hubRequestInternal(String path, String name, String type, int timeout, boolean allowRetry) {
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
        String exClass = getObjectClassName(e)
        boolean isTransient = (exClass == 'java.net.SocketTimeoutException' || exClass == 'java.net.ConnectException')
        if (allowRetry && isTransient) {
            logDebug "Transient error fetching ${name} (${exClass}); retrying once"
            return hubRequestInternal(path, name, type, timeout, false)
        }
        if (type == "json") {
            logError "Error fetching ${name} (${now() - start}ms): ${exClass}: ${e.message}"
            return [error: true, message: e.message]
        } else {
            logDebug "Error fetching ${name}: ${e.message}"
            return null
        }
    }
}

// ===== Version + stack detection helpers (Phase 0) =====

private String getHubFirmwareVersion() {
    if (location?.hubs && location.hubs.size() > 0) {
        return location.hubs[0].firmwareVersionString ?: ""
    }
    return ""
}

private boolean isVersionAtLeast(String actual, String required) {
    if (!actual || !required) return false
    List<Integer> a = actual.split('\\.').collect { String s ->
        try { return s.toInteger() } catch (Exception e) { return 0 }
    }
    List<Integer> r = required.split('\\.').collect { String s ->
        try { return s.toInteger() } catch (Exception e) { return 0 }
    }
    int n = Math.max(a.size(), r.size())
    for (int i = 0; i < n; i++) {
        int av = i < a.size() ? a[i] : 0
        int rv = i < r.size() ? r[i] : 0
        if (av > rv) return true
        if (av < rv) return false
    }
    return true
}

private String detectZwaveStack() {
    if (zwaveStackCache) return zwaveStackCache
    String txt = (String) hubRequest(ZWAVE_JS_STATUS_PATH, "Z-Wave JS status probe", "text", 5)
    String stack
    if (txt == null) stack = "unknown"
    else if (txt.toLowerCase().trim() == "true") stack = "js"
    else stack = "legacy"
    zwaveStackCache = stack
    return stack
}

// Parse a Hubitat CSV response (header row + N data rows) into a header list and rows of
// String values keyed by header name. Tolerant of column reordering — callers look up
// fields by name (with aliases) instead of by positional index. Hubitat has historically
// reordered columns in /hub/advanced/* endpoints without notice.
Map parseHubCsv(String text) {
    if (!text) return null
    String[] lines = text.split('\n')
    if (lines.size() < 2) return null
    List<String> header = (lines[0].split(',').collect { ((String) it).trim() }) as List<String>
    List<Map> rows = []
    for (int li = 1; li < lines.size(); li++) {
        String line = lines[li] == null ? null : ((String) lines[li]).trim()
        if (!line) continue
        String[] values = line.split(',')
        Map<String, String> row = [:]
        int cols = Math.min(header.size(), values.size())
        for (int ci = 0; ci < cols; ci++) {
            row[header[ci]] = ((String) values[ci]).trim()
        }
        rows << row
    }
    return [header: header, rows: rows]
}

// Resolve a CSV row field by trying each alias in order; returns null if no alias matches.
String csvField(Map row, List<String> aliases) {
    for (String a in aliases) {
        Object v = row?.get(a)
        if (v != null) return v.toString()
    }
    return null
}

// Extract the resource columns shared by the /freeOSMemory and memory-history CSV rows.
// Returns null when the two required columns (OS memory, 5m CPU) are absent — callers
// decide whether that fails the whole request or just skips the row.
private Map parseResourceRow(Map row) {
    String memRaw = csvField(row, ["Free OS", "Free OS Memory"])
    String cpuRaw = csvField(row, ["5m CPU avg", "CPU 5min", "5min CPU avg"])
    if (memRaw == null || cpuRaw == null) return null
    return [
        timestamp:  csvField(row, ["Date/time", "Timestamp"]),
        freeOS:     memRaw.toInteger(),
        cpu:        cpuRaw.toFloat(),
        totalJava:  (csvField(row, ["Total Java", "Total Java Memory"])   ?: "0").toInteger(),
        freeJava:   (csvField(row, ["Free Java", "Free Java Memory"])     ?: "0").toInteger(),
        directJava: (csvField(row, ["Direct Java", "Direct Java Memory"]) ?: "0").toInteger()
    ]
}

Map fetchSystemResources() {
    long nowMs = now()
    if (cachedSystemResources && cachedSystemResourcesAt && (nowMs - cachedSystemResourcesAt) < SYSTEM_RESOURCES_CACHE_TTL_MS) {
        return cachedSystemResources
    }
    String text = (String) hubRequest(FREE_MEMORY_PATH, "system resources", "text", 15)
    if (!text) return null
    try {
        Map parsed = parseHubCsv(text)
        if (!parsed || !parsed.rows) return null
        Map row = (Map) ((List) parsed.rows)[0]
        Map p = parseResourceRow(row)
        if (p == null) {
            logWarn "system resources CSV missing expected columns; header=${parsed.header}"
            return null
        }
        Map data = [
            timestamp:        p.timestamp,
            freeOSMemory:     p.freeOS,
            cpuAvg5min:       p.cpu,
            totalJavaMemory:  p.totalJava,
            freeJavaMemory:   p.freeJava,
            directJavaMemory: p.directJava
        ]
        cachedSystemResources = data
        cachedSystemResourcesAt = nowMs
        return data
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
        Map parsed = parseHubCsv(text)
        if (!parsed || !parsed.rows) return []
        boolean headerWarned = false
        ((List) parsed.rows).each { Object r ->
            Map row = (Map) r
            Map p = parseResourceRow(row)
            if (p == null) {
                if (!headerWarned) {
                    logWarn "memory history CSV missing expected columns; header=${parsed.header}"
                    headerWarned = true
                }
                return
            }
            dataPoints << [
                time:       p.timestamp,
                freeOS:     p.freeOS,
                cpuLoad:    p.cpu,
                freeJava:   p.freeJava,
                directJava: p.directJava
            ]
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
    long nowMs = now()
    if (cachedDatabaseSizeAt != null && (nowMs - cachedDatabaseSizeAt) < DATABASE_SIZE_CACHE_TTL_MS) {
        return cachedDatabaseSize
    }
    String text = (String) hubRequest(DATABASE_SIZE_PATH, "database size", "text")
    if (text) {
        try {
            Integer v = text.toInteger()
            cachedDatabaseSize = v
            cachedDatabaseSizeAt = nowMs
            return v
        } catch (Exception e) { /* ignore */ }
    }
    return null
}

Map fetchFileManagerStats() {
    Map wrap = hubMapRequest("/hub/fileManager/json", "file manager", 10)
    if (!wrap.ok) return null
    List files = (List) (wrap.data.files ?: [])
    long usedBytes = 0L
    files.each { usedBytes += (it.size?.toString()?.toLong() ?: 0L) }
    return [fileCount: files.size(), usedBytes: usedBytes, freeSpace: wrap.data.freeSpace]
}

Float fetchTemperature() {
    long nowMs = now()
    if (cachedTemperatureAt != null && (nowMs - cachedTemperatureAt) < TEMPERATURE_CACHE_TTL_MS) {
        return cachedTemperature
    }
    String text = (String) hubRequest(INTERNAL_TEMP_PATH, "internal temperature", "text")
    if (text) {
        try {
            Float v = text.toFloat()
            cachedTemperature = v
            cachedTemperatureAt = nowMs
            return v
        } catch (Exception e) { /* ignore */ }
    }
    return null
}

// ===== TEMPERATURE THRESHOLDS =====
// The internal reading and the warn/crit thresholds are ALWAYS Celsius internally
// (warnTempC/critTempC), so comparisons stay valid no matter what scale the hub is set to —
// changing the hub scale never reinterprets a stored threshold. Conversion to the hub's
// getTemperatureScale() happens only at the edges: the native settings inputs
// (warnTempInput/critTempInput, in the hub scale) and the SPA display.
private BigDecimal cToScale(Number celsius) {
    (getTemperatureScale() == "F") ? ((celsius * 9 / 5 + 32) as BigDecimal) : (celsius as BigDecimal)
}
private BigDecimal scaleToC(Number v) {
    // round canonical Celsius to 0.1° so an F→C→F round-trip stays clean and JSON stays tidy
    BigDecimal c = (getTemperatureScale() == "F") ? (((v - 32) * 5 / 9) as BigDecimal) : (v as BigDecimal)
    return (Math.round(c.doubleValue() * 10) / 10.0) as BigDecimal
}
private BigDecimal warnTempCValue() { (settings.warnTempC != null ? settings.warnTempC : DEFAULT_WARN_TEMP_C) as BigDecimal }
private BigDecimal critTempCValue() { (settings.critTempC != null ? settings.critTempC : DEFAULT_CRIT_TEMP_C) as BigDecimal }
private int warnTempDisplayValue() { Math.round(cToScale(warnTempCValue()).doubleValue()) as int }
private int critTempDisplayValue() { Math.round(cToScale(critTempCValue()).doubleValue()) as int }
private String tempThresholdRange() { (getTemperatureScale() == "F") ? "68..212" : "20..100" }

Map fetchHubAlerts(Map prefetchedHubData = null) {
    Map hubData = prefetchedHubData
    if (!hubData) { Map r = hubMapRequest(HUB_DATA_PATH, "hub data", 10); hubData = r.ok ? r.data : null }
    if (!hubData) return [:]
    return [
        alerts: hubData.alerts ?: [:],
        databaseSize: hubData.alerts?.databaseSize,
        spammyDevicesMessage: hubData.spammyDevicesMessage,
        devMode: hubData.baseModel?.devMode ?: false
    ]
}

List fetchHubEvents() {
    try {
        Object raw = hubRequest(HUB_EVENTS_PATH, "hub events", "json", 10)
        if (!(raw instanceof List)) return []
        return ((List) raw).collect { Object e ->
            Map ev = (Map) e
            long ts = 0
            try { ts = Date.parse("yyyy-MM-dd'T'HH:mm:ss.SSSZ", (String) ev.date).time } catch (Exception ignored) {}
            [id: ev.id, name: ev.name, description: ev.descriptionText, ts: ts, value: ev.value]
        }
    } catch (Exception e) {
        logDebug "fetchHubEvents failed: ${e.message}"
        return []
    }
}

// ===== Phase 1+2 fetch methods (v5.9.0) =====

Map fetchBackups() {
    Object localResp = hubRequest(LOCAL_BACKUPS_PATH, "local backups", "json", 10)
    Map cloudWrap = hubMapRequest(CLOUD_BACKUPS_PATH, "cloud backups", 15)
    Map cloudResp = cloudWrap.ok ? cloudWrap.data : [:]
    List localList = (localResp instanceof List) ? (List) localResp : []
    List cloudList = ((cloudResp?.backups as List) ?: [])
    Map latestLocal = localList ? (Map) localList[-1] : null
    List cloudThisHub = cloudList.findAll { Map b -> b.thisHub == true } as List
    Map latestCloud = cloudThisHub ? (Map) cloudThisHub[-1] : null
    return [
        local: [
            count: localList.size(),
            latestName: latestLocal?.name,
            latestCreateTime: latestLocal?.createTime,
            latestCreateTimeOrig: latestLocal?.createTimeOrig,
            latestPlatformVersion: latestLocal?.platformVersion
        ],
        cloud: [
            thisHubCount: cloudThisHub.size(),
            otherHubCount: cloudList.size() - cloudThisHub.size(),
            latestThisHubCreateTime: latestCloud?.createTime,
            latestThisHubCreateTimeOrig: latestCloud?.createTimeOrig,
            latestThisHubVersion: latestCloud?.platformVersion,
            otherHubs: cloudList.findAll { Map b -> b.thisHub != true }.collect { Map b ->
                [hubName: b.hubName, hubVersion: b.hubVersion, platformVersion: b.platformVersion,
                 createTime: b.createTime, fileSize: b.fileSize]
            },
            hasCloudBackupEntitlements: cloudResp?.hasCloudBackupEntitlements ?: false,
            hasCloudRestoreEntitlements: cloudResp?.hasCloudRestoreEntitlements ?: false
        ]
    ]
}

List fetchHubMessages() {
    Map wrap = hubMapRequest(HUB_MESSAGES_PATH, "hub messages", 5)
    if (!wrap.ok) return []
    return ((wrap.data.messages as List) ?: []).collect { Object m ->
        if (m instanceof Map) return m
        return [text: m?.toString()]
    }
}

Map fetchRadioHealth() {
    String fw = getHubFirmwareVersion()
    if (!isVersionAtLeast(fw, MIN_FW_RADIO_HEALTH)) {
        return [zwave: null, zigbee: null, supported: false, minFirmware: MIN_FW_RADIO_HEALTH, currentFirmware: fw]
    }
    String zw = (String) hubRequest(ZWAVE_HEALTH_PATH, "zwave health", "text", 5)
    String zb = (String) hubRequest(ZIGBEE_HEALTH_PATH, "zigbee health", "text", 5)
    return [
        zwave: zw == null ? null : (zw.toLowerCase().trim() == "true"),
        zigbee: zb == null ? null : (zb.toLowerCase().trim() == "true"),
        supported: true
    ]
}

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

Integer fetchExcessiveLoadThreshold() {
    long nowMs = now()
    if (cachedLoadThresholdAt != null && (nowMs - cachedLoadThresholdAt) < LOAD_THRESHOLD_CACHE_TTL_MS) {
        return cachedLoadThreshold
    }
    String txt = (String) hubRequest(LOAD_THRESHOLD_PATH, "excessive load threshold", "text", 5)
    if (!txt) return null
    try {
        Integer v = txt.trim().toInteger()
        cachedLoadThreshold = v
        cachedLoadThresholdAt = nowMs
        return v
    } catch (Exception e) { return null }
}

String fetchNtpServer() {
    String txt = (String) hubRequest(NTP_SERVER_PATH, "NTP server", "text", 5)
    if (!txt) return null
    String t = txt.trim()
    if (!t || t.equalsIgnoreCase("No value set")) return null
    return t
}

Map fetchFirmwareUpdate() {
    long nowMs = now()
    if (fwUpdateCache && fwUpdateCacheAt &&
        (nowMs - fwUpdateCacheAt) < FW_UPDATE_CACHE_TTL_MS) {
        return fwUpdateCache
    }
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
    fwUpdateCache = result
    fwUpdateCacheAt = nowMs
    return result
}

/**
 * Walks /hub2/roomsList tree and returns flat list:
 *   [{id, name, deviceCount, deviceIds[]}]
 * Devices not assigned to any room are gathered under a synthetic "(Unassigned)" room with id=null.
 */
List fetchRoomsForAudit() {
    Map wrap = hubMapRequest(ROOMS_LIST_PATH, "rooms list", 10)
    if (!wrap.ok) return []
    List nodes = (wrap.data.roomNodes as List) ?: []
    List rooms = []
    nodes.each { Map rn ->
        Map data = (rn.data as Map) ?: [:]
        if (!data.id) return
        List children = (rn.children as List) ?: []
        List devIds = children.collect { Map c -> (c.data as Map)?.id as Long }.findAll { it }
        rooms << [id: data.id, name: data.name, deviceCount: devIds.size(), deviceIds: devIds]
    }
    rooms.sort { (it.name as String)?.toLowerCase() }
    return rooms
}

/** Per-node Z-Wave JS state. Returns null when stack is not JS or fetch fails. */
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

/** Per-Hub-Mesh-linked-device state. Returns null when device is not Hub-Mesh-linked or fetch fails. */
Map fetchHubMeshDeviceState(Long deviceId) {
    if (deviceId == null) return null
    Map wrap = hubMapRequest(HUB_MESH_LINKED_DEVICE_PREFIX + deviceId, "hubmesh dev ${deviceId}", 5)
    if (!wrap.ok) return null
    return wrap.data
}

List fetchUserAppTypes() {
    Object resp = hubRequest(USER_APP_TYPES_PATH, "user app types", "json", 10)
    if (!(resp instanceof List)) return []
    return (resp as List).collect { Map a ->
        List used = (a.usedBy as List) ?: []
        [id: a.id, name: a.name, namespace: a.namespace,
         oauthEnabled: a.oauth == "enabled",
         lastModified: a.lastModified,
         usedByCount: used.size(),
         usedBy: used.collect { Map u -> [id: u.id, name: u.name] }]
    }
}

List fetchUserDriverTypes() {
    Object resp = hubRequest(DEVICE_TYPES_PATH, "user driver types", "json", 10)
    if (!(resp instanceof List)) return []
    return (resp as List).collect { Map d ->
        List used = (d.usedBy as List) ?: []
        List caps = (d.capabilities as String)?.split(',\\s*')?.findAll { it } ?: []
        [id: d.id, name: d.name, namespace: d.namespace,
         lastModified: d.lastModified,
         capabilityCount: caps.size(),
         capabilities: caps,
         usedByCount: used.size(),
         usedBy: used.collect { Map u -> [id: u.id, name: u.name] }]
    }
}

List fetchUserBundles() {
    Object resp = hubRequest(USER_BUNDLES_PATH, "user bundles", "json", 10)
    if (!(resp instanceof List)) return []
    return (resp as List).collect { Map b ->
        [id: b.id, name: b.name, namespace: b.namespace, isPrivate: b.private == true, content: b.content]
    }
}

List fetchUserLibraries() {
    Object resp = hubRequest(USER_LIBRARIES_PATH, "user libraries", "json", 10)
    if (!(resp instanceof List)) return []
    return (resp as List).collect { Map l ->
        List usedByDevices = (l.usedByDeviceTypes as String)?.split(',\\s*')?.findAll { it } ?: []
        List usedByApps = (l.usedByAppTypes as String)?.split(',\\s*')?.findAll { it } ?: []
        [id: l.id, name: l.name, namespace: l.namespace, version: l.version,
         author: l.author, category: l.category, description: l.description,
         updateTime: l.updateTime, isPrivate: l.private == true,
         usedByDeviceCount: usedByDevices.size(), usedByAppCount: usedByApps.size(),
         usedByDeviceTypes: usedByDevices, usedByAppTypes: usedByApps]
    }
}

Map fetchHubVariables() {
    try {
        Map vars = (Map) getAllGlobalVars()
        if (!vars) return [count: 0, supported: true, variables: []]
        List entries = vars.collect { String name, Object meta ->
            Map m = (meta instanceof Map) ? (Map) meta : [value: meta]
            [name: name, value: m.value, type: m.type, lastUpdated: m.lastUpdated]
        }
        return [count: entries.size(), supported: true, variables: entries.sort { it.name }]
    } catch (MissingMethodException mme) {
        return [count: 0, supported: false, variables: []]
    } catch (Exception e) {
        logDebug "fetchHubVariables: ${e.message}"
        return [count: 0, supported: true, variables: [], error: e.message]
    }
}

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

// ===== Phase 5 fetch methods (v5.11.1) =====

Map fetchCpuInfo() {
    long nowMs = now()
    if (cachedCpuInfoAt != null && (nowMs - cachedCpuInfoAt) < CPU_INFO_CACHE_TTL_MS) {
        return cachedCpuInfo
    }
    String txt = (String) hubRequest(CPU_INFO_PATH, "CPU info", "text", 5)
    if (!txt) return null
    Map result = [:]
    txt.split('\n').each { String line ->
        String trimmed = line.trim()
        if (!trimmed) return
        if (trimmed.startsWith("Processors")) {
            try { result.processors = trimmed.replaceAll(/[^\d]/, '').toInteger() } catch (Exception e) { /* skip */ }
        } else if (trimmed.startsWith("Load Average")) {
            try { result.loadAverage = trimmed.replaceAll(/[^\d.]/, '').toFloat() } catch (Exception e) { /* skip */ }
        }
    }
    if (result.isEmpty()) return null
    cachedCpuInfo = result
    cachedCpuInfoAt = nowMs
    return result
}

String fetchZipgatewayVersion() {
    String txt = (String) hubRequest(ZIPGATEWAY_VERSION_PATH, "zipgateway version", "text", 5)
    if (!txt) return null
    String t = txt.trim()
    return t ?: null
}

Map fetchSecurityInfo(Map prefetchedHubData = null) {
    String laRaw = (String) hubRequest(LIMITED_ACCESS_PATH, "limited access addresses", "text", 5)
    String subnets = (String) hubRequest(ALLOW_SUBNETS_PATH, "allowed subnets", "text", 5)
    String dnsFb = (String) hubRequest(DNS_FALLBACK_PATH, "DNS fallback", "text", 5)
    Map hubData = prefetchedHubData
    if (!hubData) { Map r = hubMapRequest(HUB_DATA_PATH, "hub data (cloud controller flag)", 10); hubData = r.ok ? r.data : null }
    // null laRaw/subnets means the fetch itself failed — distinguish from a successfully-fetched "no restriction"
    // so the UI can render "Unknown" instead of a falsely reassuring "Off".
    Map limitedAccess = null
    if (laRaw != null) {
        String laClean = laRaw.replaceAll(/<[^>]+>/, '').trim()
        boolean limitedSet = laClean && !laClean.equalsIgnoreCase("no limit set") && !laClean.isEmpty()
        limitedAccess = [
            enabled: limitedSet,
            addresses: limitedSet ? laClean.split(/[,\s]+/).findAll { it } as List : []
        ]
    }
    List allowedSubnets = subnets == null ? null : (subnets.trim().split(',').findAll { it } as List)
    Boolean cloudDisabled = hubData ? (hubData.disableCloudController == true) : null
    return [
        limitedAccess: limitedAccess,
        allowedSubnets: allowedSubnets,
        dnsFallback: dnsFb == null ? null : (dnsFb.toLowerCase().trim() == "true"),
        cloudController: cloudDisabled == null ? null : (cloudDisabled ? "disabled" : "enabled")
    ]
}

// ===== Z-Wave Topology HTML embed (Phase 6 sub-3, v5.12.0) =====

/**
 * Returns the bare <table>...</table> HTML adjacency matrix from Hubitat's Z-Wave topology page.
 * Embedded as-is in the Network tab; bgcolor cells encode pairwise connectivity.
 */
String fetchZwaveTopology() {
    String txt = (String) hubRequest(ZWAVE_TOPOLOGY_PATH, "Z-Wave topology", "text", 10)
    if (!txt) return null
    String t = txt.trim()
    if (!t) return null
    // Hub omits closing </table> on the no-nodes self-only matrix; append defensively so embedded
    // HTML can't swallow the DOM of following cards if the bug recurs with real nodes.
    if (t.toLowerCase().contains("<table") && !t.toLowerCase().contains("</table>")) t += "</table>"
    return t
}

// ===== Zigbee Channel Scan (Phase 6 sub-1, v5.12.0) =====

/** Read the cached scan result, never triggering a fresh scan. */
Map fetchCachedZigbeeScan() {
    if (!state.zigbeeScanCache) return null
    Map cache = state.zigbeeScanCache as Map
    return [
        at: cache.at,
        results: (cache.results as List) ?: []
    ]
}

/** Trigger a fresh scan (~30s, briefly impacts Zigbee join activity), update cache, return new results. */
Map runZigbeeChannelScan() {
    long start = now()
    Object resp = hubRequest(ZIGBEE_CHANNEL_SCAN_PATH, "Zigbee channel scan", "json", 90)
    if (!(resp instanceof List)) return [at: now(), results: [], error: "scan returned no data"]
    List results = (resp as List).collect { Map r ->
        [channel: r.channel, panId: r.panId, extendedPanId: r.extendedPanId,
         lastHopRssi: r.lastHopRssi, lastHopLqi: r.lastHopLqi,
         allowingJoin: r.allowingJoin == true, nwkUpdateId: r.nwkUpdateId]
    }
    Map cache = [at: now(), results: results, scanDurationMs: (now() - start)]
    state.zigbeeScanCache = cache
    return cache
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
            // Parse neighbor entries: "[Device Name, ABCD], LQI:255, age:4, inCost:1, outCost:1"
            Map neighbor = [raw: line]
            java.util.regex.Matcher bracketMatch = (line =~ /^\[([^\]]+),\s*([0-9A-Fa-f]{4})\]/)
            java.util.regex.Matcher lqiMatch = (line =~ /LQI:\s*(\d+)/)
            java.util.regex.Matcher ageMatch = (line =~ /age:\s*(\d+)/)
            java.util.regex.Matcher inCostMatch = (line =~ /inCost:\s*(\d+)/)
            java.util.regex.Matcher outCostMatch = (line =~ /outCost:\s*(\d+)/)
            if (bracketMatch.find()) {
                neighbor.name = bracketMatch.group(1).trim()
                neighbor.shortId = bracketMatch.group(2).toUpperCase()
            }
            if (lqiMatch.find()) neighbor.lqi = lqiMatch.group(1).toInteger()
            if (ageMatch.find()) neighbor.age = ageMatch.group(1).toInteger()
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
    java.util.regex.Matcher mProtocol = raw =~ /zWaveProtocolVersion:(\d+), zWaveProtocolSubVersion:(\d+)/
    java.util.regex.Matcher mTarget = raw =~ /targetVersions:\[\[target:1, version:(\d+), subVersion:(\d+)\]\]/
    
    String protocolVer = mProtocol ? "${mProtocol[0][1]}.${mProtocol[0][2]}" : ""
    String sdkVer = mTarget ? "${mTarget[0][1]}.${mTarget[0][2]}" : ""
    
    if (sdkVer) return "${sdkVer} (Protocol ${protocolVer})"
    if (protocolVer) return protocolVer
    return raw
}

Map extractZwaveMeshQuality(Map zwaveData) {
    if (!zwaveData || !zwaveData.nodes) return [:]

    List nodes = []
    int totalPer = 0
    int nodesWithErrors = 0
    int totalRouteChanges = 0
    int rssiCount = 0
    int rssiSum = 0

    zwaveData.nodes.each { Map node ->
        int per = (node.per ?: 0) as int
        int neighborCount = (node.neighbors ?: 0) as int
        int routeChanges = node.routeChanges?.toString()?.isInteger() ? (node.routeChanges ?: 0) as int : -1
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
            driverType: driverType,
            zwaveType: node.zwaveType ?: ""
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
    if (!zwaveData || !zwaveData.nodes) return []
    return zwaveData.nodes.collect { Map node ->
        [id: node.nodeId, deviceId: node.deviceId, name: node.deviceName ?: "Node ${node.nodeId}",
         msgCount: (node.msgCount ?: 0) as int, routeChanges: node.routeChanges?.toString()?.isInteger() ? (node.routeChanges ?: 0) as int : -1]
    }
}

List extractZigbeeMessageCounts(Map zigbeeData) {
    if (!zigbeeData || !zigbeeData.devices) return []
    return zigbeeData.devices.collect { Map device ->
        [id: device.id, name: device.name ?: "Device ${device.id}",
         msgCount: (device.messageCount ?: 0) as int]
    }
}

// ===== ANALYSIS MODULES =====

Map analyzeDevices(boolean deep = true) {
    Map respWrap = hubMapRequest(DEVICES_LIST_PATH, "devices list")

    if (!respWrap.ok || !respWrap.data.devices) {
        logWarn "Failed to fetch devices list"
        return getEmptyDeviceStats()
    }

    List devicesList = flattenDeviceEntries(respWrap.data.devices as List, deep)

    Map stats = getEmptyDeviceStats()

    long inactivityThresholdMs = now() - ((settings.inactivityDays ?: 7) * ONE_DAY_MS)
    Map appLookup = buildAppLookupMap()
    Set communityDrivers = buildCommunityDriverSet()
    // Set of community app type names (app.name where app.user == true) — used as fallback in enrichDevices()
    // to determine builtin status when per-device fullJson cache is stale or missing the field.
    Set communityAppTypeNames = appLookup.values()
        .findAll { (it as Map)?.user == true }
        .collect { (String)((it as Map)?.type ?: "") }
        .findAll { it } as Set
    // Collects devices needing deep enrichment (isNetwork + CONN_OTHER): deviceId → [appInfo, currentIntegration, currentConn, deviceId]
    Map uncertainDevices = [:]

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
            } else if (lastActivity && lastActivity > inactivityThresholdMs) {
                stats.activeDevices++
                stats.idsByStatus.active << device.id
            } else {
                stats.inactiveDevices++
                stats.idsByStatus.inactive << device.id
            }

            // Two-dimension classification: connectionType + integration
            Map classification = classifyDevice(device, appLookup, communityDrivers)
            String connectionType = classification.connectionType
            String integration = classification.integration

            stats.byConnectionType[connectionType] = (stats.byConnectionType[connectionType] ?: 0) + 1
            if (!stats.idsByConnectionType.containsKey(connectionType)) stats.idsByConnectionType[connectionType] = []
            stats.idsByConnectionType[connectionType] << device.id

            stats.byIntegration[integration] = (stats.byIntegration[integration] ?: 0) + 1
            if (!stats.idsByIntegration.containsKey(integration)) stats.idsByIntegration[integration] = []
            stats.idsByIntegration[integration] << device.id

            // Track whether each integration is built-in or community (first definitive value wins)
            if (classification.builtin != null && !stats.integrationSources.containsKey(integration)) {
                stats.integrationSources[integration] = classification.builtin ? "builtin" : "community"
            }

            Object parentAppId = extractParentAppId(device)
            String normalizedParentAppId = normalizeAppLookupId(parentAppId)
            Map parentAppInfo = normalizedParentAppId ? (Map) appLookup[normalizedParentAppId] : null
            // Devices whose bulk metadata cannot fully resolve classification need the per-device fullJson
            // correction pass so Dashboard and Devices summaries stay in sync.
            boolean needsEnrichment = (connectionType == CONN_OTHER) ||
                (connectionType == CONN_LAN_DIRECT && integration == "LAN Device" && device.isNetwork == true)
            if (needsEnrichment) {
                uncertainDevices[device.id.toString()] = [
                    appInfo: parentAppInfo, currentIntegration: integration, currentConn: connectionType, deviceId: device.id
                ]
            }

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
                    // Some community drivers report battery as a string ("100%", "high", "75 %") — strip
                    // anything that isn't a digit or decimal point so we don't silently lose the value.
                    String batteryRaw = batteryState.value.toString().replaceAll(/[^0-9.]/, '').trim()
                    if (batteryRaw) {
                        try {
                            batteryLevel = batteryRaw.toFloat().toInteger()
                            stats.batteryDevices++
                            stats.batteryIds << device.id
                            if (batteryLevel <= (settings.lowBatteryThreshold ?: 20)) {
                                stats.lowBatteryDevices << [id: device.id, name: device.name ?: "Unknown", battery: batteryLevel]
                            }
                        } catch (Exception e) { /* defensive guard for unexpected parse errors */ }
                    }
                }

                String parentAppName = parentAppInfo?.label ?: (normalizedParentAppId ? "App ${normalizedParentAppId}" : null)
                stats.allDevices << [
                    id: device.id, name: device.name ?: "Unknown",
                    label: device.label ?: device.name ?: "Unknown",
                    type: typeName, userType: device.user ?: false, deviceTypeId: device.deviceTypeId,
                    connectionType: connectionType, integration: integration,
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

    // Pass 2: enrich uncertain devices via device/fullJson (parentApp + controllerType).
    // This updates summary buckets for both shallow Dashboard and deep Devices calls.
    if (uncertainDevices) {
        Map enrichedAppInfos = (Map) uncertainDevices.collectEntries { k, v -> [k, ((Map)v).appInfo] }
        Map enrichments = enrichDevices(enrichedAppInfos, communityAppTypeNames)

        enrichments.each { String idStr, Map newClass ->
            Map uncertain = (Map) uncertainDevices[idStr]
            if (!uncertain) return

            Object deviceId = uncertain.deviceId
            String oldConn = (String) uncertain.currentConn
            String oldIntegration = (String) uncertain.currentIntegration
            String newConn = (String) newClass.connectionType
            String newInteg = (String) newClass.integration

            if (newConn == oldConn && newInteg == oldIntegration) return  // no change

            // Update connection type stats (rebuild lists to avoid Integer/index remove ambiguity)
            stats.idsByConnectionType[oldConn] = ((List) stats.idsByConnectionType[oldConn]).findAll { it?.toString() != idStr }
            stats.byConnectionType[oldConn] = Math.max(0, ((stats.byConnectionType[oldConn] ?: 0) as int) - 1)
            if (!stats.idsByConnectionType.containsKey(newConn)) stats.idsByConnectionType[newConn] = []
            ((List) stats.idsByConnectionType[newConn]) << deviceId
            stats.byConnectionType[newConn] = ((stats.byConnectionType[newConn] ?: 0) as int) + 1

            // Update integration stats
            stats.idsByIntegration[oldIntegration] = ((List) stats.idsByIntegration[oldIntegration]).findAll { it?.toString() != idStr }
            stats.byIntegration[oldIntegration] = Math.max(0, ((stats.byIntegration[oldIntegration] ?: 0) as int) - 1)
            if (!stats.idsByIntegration.containsKey(newInteg)) stats.idsByIntegration[newInteg] = []
            ((List) stats.idsByIntegration[newInteg]) << deviceId
            stats.byIntegration[newInteg] = ((stats.byIntegration[newInteg] ?: 0) as int) + 1

            // Update allDevices record in place
            Map deviceRecord = (Map) stats.allDevices.find { ((Map)it).id?.toString() == idStr }
            if (deviceRecord) {
                deviceRecord.connectionType = newConn
                deviceRecord.integration = newInteg
            }

            // Update integrationSources for the new integration name
            Object newBuiltin = newClass.builtin
            if (newBuiltin != null && !stats.integrationSources.containsKey(newInteg)) {
                stats.integrationSources[newInteg] = (newBuiltin == true) ? "builtin" : "community"
            }
        }
    }

    return stats
}

Map analyzeApps(boolean deep = true) {
    Map wrap = hubMapRequest(APPS_LIST_PATH, "apps list")

    if (!wrap.ok || !wrap.data.apps) {
        return deep ? getEmptyAppStats() : [totalApps: 0, userApps: 0, builtInApps: 0]
    }

    // Quick mode: just count apps
    if (!deep) {
        int totalApps = 0, userApps = 0, builtInApps = 0
        visitAppEntries(wrap.data.apps as List) { Map appEntry, Map app, boolean isChildLevel, List parentHierarchyList ->
            if (!app) return
            totalApps++
            if (app.user) userApps++
            else builtInApps++
        }
        return [totalApps: totalApps, userApps: userApps, builtInApps: builtInApps]
    }

    List appsList = wrap.data.apps
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
        allApps: [],
        runtimeTotalApps: 0
    ]

    // Dedicated recursion remains here because hierarchy generation mutates nested child lists
    Closure processAppList
    processAppList = { List entries, boolean isChildLevel, List parentHierarchyList, def currentParentId, String currentParentName ->
        entries.each { appEntry ->
            try {
                Map app = appEntry.data
                if (!app || !(app instanceof Map)) return

                stats.totalApps++

                boolean isUserApp = app.user ?: false
                String appType = app.type ?: "Unknown App"
                String appLabel = app.name ?: appType
                def appId = appEntry.key ?: app.id  // keep "APP-NNN" for snapshot diff lookups
                def numericId = app.id              // numeric ID for UI links
                List children = appEntry.children ?: []

                if (isChildLevel) {
                    stats.childApps++
                }
                if (children.size() > 0) {
                    stats.parentApps++
                }

                if (isUserApp) {
                    stats.userApps++
                    stats.userAppsList << [name: appType, label: appLabel, id: appId, disabled: app.disabled ?: false]
                } else {
                    stats.builtInApps++
                    stats.builtInInstances[appType] = (stats.builtInInstances[appType] ?: 0) + 1
                }

                stats.byNamespace[appType] = (stats.byNamespace[appType] ?: 0) + 1

                String displayName = stripHtml(app.name ?: appType)
                // Flat entry for the installed apps table
                stats.allApps << [
                    id:         numericId,
                    name:       displayName,
                    type:       appType,
                    user:       isUserApp,
                    source:     isUserApp ? "community" : "builtin",
                    disabled:   app.disabled ?: false,
                    hidden:     app.hidden ?: false,
                    setting:    app.setting ?: false,
                    menu:       app.menu ?: "",
                    level:      (app.level ?: 0) as int,
                    childCount: children.size(),
                    parentId:   currentParentId,
                    parentName: currentParentName ?: ""
                ]

                if (children.size() > 0) {
                    Map parentInfo = [
                        id: app.id,
                        type: appType,
                        label: appLabel,
                        childCount: 0,
                        children: []
                    ]

                    processAppList(children, true, parentInfo.children, numericId, displayName)
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
    processAppList(appsList, false, stats.parentChildHierarchy, null, null)

    // Identify platform-only apps by comparing runtime stats against appsList
    stats.platformApps = []
    try {
        Map runtimeWrap = hubMapRequest(RUNTIME_STATS_PATH, "runtime stats")
        if (runtimeWrap.ok) {
            List runtimeAppStats = runtimeWrap.data.appStats ?: []
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
        }
    } catch (Exception e) {
        logDebug "Could not fetch runtime stats for app count: ${e.message}"
    }

    // userAppsList / platformApps display order is left to the SPA (tbl() re-sorts; platformApps is unconsumed).
    stats.parentChildHierarchy = stats.parentChildHierarchy.sort { it.type }

    return stats
}

// Data-or-null convenience over hubMapRequest (collapses the repeated `.with { it.ok ? it.data : null }`).
private Object reqData(String path, String name, int timeout = 10) {
    Map r = hubMapRequest(path, name, timeout)
    return r.ok ? r.data : null
}

Map analyzeNetwork() {
    return [
        network: reqData(NETWORK_CONFIG_PATH, "network configuration", 15),
        zwave:   reqData(ZWAVE_DETAILS_PATH, "Z-Wave details", 20),
        zigbee:  reqData(ZIGBEE_DETAILS_PATH, "Zigbee details", 20),
        matter:  reqData(MATTER_DETAILS_PATH, "Matter details", 15),
        hubMesh: reqData(HUB_MESH_PATH, "Hub Mesh", 15)
    ]
}

Map analyzeSystemHealth(Map shared = [:]) {
    Map memory        = (shared.resources as Map)     ?: fetchSystemResources()
    Map stateCompression = fetchStateCompression()
    Map hubAlerts     = (shared.hubAlerts as Map)     ?: fetchHubAlerts(shared.hubData as Map)
    Integer databaseSize = (shared.databaseSize as Integer) ?: fetchDatabaseSize()
    Float temperature = (shared.temperature as Float) ?: fetchTemperature()
    Map eventStateLimits = fetchEventStateLimits()

    // Alert composition lives entirely in the SPA's composeAlerts() — the hub ships raw
    // signals (resources/temperature in this map, plus getAlertSignals()) and the browser
    // derives severity from the configured thresholds. We deliberately do NOT compose an
    // alert list here: a second server-side composer drifts from the client one undetected.
    Map health = [
        memory: memory,
        stateCompression: stateCompression,
        hubAlerts: hubAlerts,
        databaseSize: databaseSize,
        temperature: temperature,
        eventStateLimits: eventStateLimits
    ]

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
    Map zwTypeByNodeId = [:]
    Map deviceIdByNodeId = [:]   // built from nodes[] — reliable across firmware versions
    (zwaveDetails?.nodes ?: []).each { Map n ->
        if (n.nodeId) {
            if (n.zwaveType) zwTypeByNodeId[n.nodeId.toString()] = n.zwaveType
            // zwDevices.deviceId is absent on some firmware; nodes[].deviceId is always present
            if (n.deviceId) deviceIdByNodeId[n.nodeId.toString()] = n.deviceId
        }
    }
    (zwaveDetails?.zwDevices ?: [:]).each { nodeId, nodeData ->
        if (!(nodeData instanceof Map)) return
        String zwType = zwTypeByNodeId[nodeId.toString()] ?: ""
        // Never flag the hub's own controller node
        if (zwType.toUpperCase().contains("CONTROLLER")) return
        def deviceId = deviceIdByNodeId[nodeId.toString()]
        boolean noDeviceId = !deviceId                         // principal signal: no paired Hubitat device
        boolean isFailed   = nodeData.status == "FAILED" || nodeData.failed == true
        boolean noRoute    = nodeData.route == null || nodeData.route == "" || nodeData.route == "No route"
        boolean noName     = !nodeData.name || nodeData.name == "Unknown" || nodeData.name == ""
        if (noDeviceId || isFailed || (noRoute && noName)) {
            List signals = []
            if (noDeviceId) signals << "no device"
            if (isFailed)   signals << "FAILED"
            if (noRoute)    signals << "no route"
            if (noName)     signals << "unknown name"
            // kind=ghost  -> truly orphaned (no Hubitat device), safe to force-remove from radio
            // kind=failed -> has a Hubitat device but radio reports it down; try battery/range first
            ghostNodes << [
                id: nodeId,
                deviceId: deviceId,
                kind: noDeviceId ? "ghost" : "failed",
                name: nodeData.name ?: "Unknown",
                status: nodeData.status ?: "No route",
                type: zwType,
                signals: signals
            ]
        }
    }
    return ghostNodes
}

Map buildAppLookupMap() {
    Map wrap = hubMapRequest(APPS_LIST_PATH, "apps list", 20)
    if (!wrap.ok || !wrap.data.apps) {
        return [:]
    }

    Map appLookup = [:]
    visitAppEntries(wrap.data.apps as List) { Map appEntry, Map app, boolean isChildLevel, List parentHierarchyList ->
        String appId = normalizeAppLookupId(appEntry?.key ?: app?.id)
        if (appId) {
            appLookup[appId] = [
                label: app?.label ?: app?.name ?: "App ${appId}",
                type:  app?.name ?: "",
                user:  app?.user ?: false
            ]
        }
    }
    return appLookup
}

// Fetches community-installed device types and returns a Set of their names.
// Any device whose type field is NOT in this set uses a built-in Hubitat driver.
Set buildCommunityDriverSet() {
    List types = (List) hubRequest(DEVICE_TYPES_PATH, "device types", "json", 15)
    if (!types) return [] as Set
    return types.collect { it?.name?.toString() ?: "" }.findAll { it } as Set
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

/**
 * Returns the merged integration-overrides map: user file entries first (so user wins on
 * substring-match precedence and on key collision), then built-in entries not overridden.
 * Result is cached in integrationOverridesCache; call updated() to invalidate.
 * Any parse error falls back silently to the built-in defaults.
 */
private Map getIntegrationOverrides() {
    if (integrationOverridesCache != null) return integrationOverridesCache
    try {
        byte[] fileData = downloadHubFile(INTEGRATION_OVERRIDES_FILE)
        if (fileData) {
            String jsonString = new String(fileData, "UTF-8")
            Map userRaw = (Map) new groovy.json.JsonSlurper().parseText(jsonString)
            // Build merged map: user entries first, then built-in entries not overridden.
            Map merged = new LinkedHashMap()
            userRaw.each { rawKey, rawVal ->
                if (rawKey.toString().startsWith("_")) return   // skip documentation / commented-out keys
                String key = rawKey.toString().toLowerCase().trim()
                if (!key) return
                Map entry = [:]
                if (rawVal instanceof Map) {
                    String conn = rawVal?.conn?.toString()?.trim()
                    if (conn && VALID_CONN.contains(conn)) entry.conn = conn
                    String nm = rawVal?.name?.toString()?.trim()
                    if (nm) entry.name = nm
                }
                if (!entry.isEmpty()) merged[key] = entry
            }
            INTEGRATION_OVERRIDES.each { k, v ->
                if (!merged.containsKey(k)) merged[k] = v
            }
            int userEntryCount = userRaw.keySet().count { !it.toString().startsWith("_") } as int
            logDebug "Loaded integration overrides: ${merged.size()} entries (${userEntryCount} from user file)"
            integrationOverridesCache = merged
            return merged
        }
    } catch (Exception e) {
        logWarn "Could not load ${INTEGRATION_OVERRIDES_FILE}: ${e.message} — using built-in defaults"
    }
    integrationOverridesCache = INTEGRATION_OVERRIDES
    return INTEGRATION_OVERRIDES
}

Map lookupIntegration(String text) {
    if (!text) return null
    String lower = text.toLowerCase()
    for (Map.Entry entry : getIntegrationOverrides().entrySet()) {
        if (lower.contains((String) entry.key)) return (Map) entry.value
    }
    return null
}

// Strips common trailing app-name noise to produce a clean integration display name.
// Examples: "YoLink Device Service" → "YoLink", "Sonoff Wifi Device Manager" → "Sonoff Wifi",
//           "Ecobee Integration" → "Ecobee".  Conservative: only strips a known suffix set,
// case-insensitively, repeatedly from the tail.  Never returns empty — falls back to original.
String cleanIntegrationName(String raw) {
    if (!raw) return raw
    // Suffixes tried longest-first so multi-word phrases match before single words
    List<String> suffixes = [
        "(connect)", "connect", "device manager", "device service", "devices", "device",
        "integration", "manager", "service", "controller", "account"
    ]
    String s = raw.trim()
    boolean changed = true
    while (changed) {
        changed = false
        String lower = s.toLowerCase()
        for (String suf : suffixes) {
            if (lower.endsWith(suf)) {
                String candidate = s.substring(0, s.length() - suf.length()).trim()
                if (candidate) { s = candidate; changed = true; break }
            }
        }
    }
    return s ?: raw
}

// Returns [connectionType, integration, builtin] where builtin=true means Hubitat-bundled,
// false means community. communityDrivers is a Set<String> of user-installed driver names
// from /hub2/userDeviceTypes — any device type NOT in the set uses a built-in driver.
Map classifyDevice(Map device, Map appLookup, Set communityDrivers) {
    // 1. Boolean flags from bulk devicesList — authoritative, require no extra API calls.
    //    The protocol field is unreliable (null on many hubs); these flags are the source of truth.
    if (device.isZigbee == true)    return [connectionType: CONN_PAIRED,   integration: "Zigbee",    builtin: true]
    if (device.isZwave == true)     return [connectionType: CONN_PAIRED,   integration: "Z-Wave",    builtin: true]
    if (device.isMatter == true)    return [connectionType: CONN_PAIRED,   integration: "Matter",    builtin: true]
    if (device.isBluetooth == true) return [connectionType: CONN_PAIRED,   integration: "Bluetooth", builtin: true]
    if (device.isLinked == true || device.linked == true) {
        return [connectionType: CONN_HUBMESH, integration: "Hub Mesh", builtin: true]
    }
    if (device.isVirtual == true)   return [connectionType: CONN_VIRTUAL,  integration: "Virtual",   builtin: true]

    // 1b. Driver-name heuristic: built-in Virtual* drivers without the isVirtual flag
    boolean driverIsBuiltin = !communityDrivers.contains(safeToString(device.type, ""))
    String driverTypeLower = safeToString(device.type, "").toLowerCase()
    if (driverIsBuiltin && (driverTypeLower.startsWith("virtual ") || driverTypeLower == "virtual")) {
        return [connectionType: CONN_VIRTUAL, integration: "Virtual", builtin: true]
    }

    // 1c. Built-in cloud device drivers (OpenWeatherMap, etc.) — standalone cloud pollers with no
    //     parent app and isNetwork=false; every derivation signal is absent, so the only reliable
    //     signal is the built-in driver type name. driverIsBuiltin guards against a same-named
    //     community driver. Community cloud/LAN devices use the override file instead (branch 2b).
    if (driverIsBuiltin) {
        String cloudIntegration = (String) BUILTIN_CLOUD_DRIVERS[driverTypeLower]
        if (cloudIntegration) {
            return [connectionType: CONN_CLOUD, integration: cloudIntegration, builtin: true]
        }
    }

    // 2. Parent app lookup (parentAppId present in bulk list for some devices)
    //    Algorithm-primary: integration = cleanIntegrationName(appType), connectionType derived from
    //    device.isNetwork signal (LAN ⇒ lan_direct, else cloud).  INTEGRATION_OVERRIDES supplies a
    //    connection-type exception for the few the isNetwork signal can't derive (LAN bridges, AirPlay).
    Object parentAppIdRaw = extractParentAppId(device)
    String normalizedParentAppId = normalizeAppLookupId(parentAppIdRaw)
    if (normalizedParentAppId) {
        Map appInfo = (Map) appLookup[normalizedParentAppId]
        if (appInfo) {
            String appType  = (appInfo.type  ?: "").toString()
            String appLabel = (appInfo.label ?: "").toString()
            boolean isBuiltin = !(appInfo.user == true)
            // Check override map first (bridges, AirPlay) — falls back to algorithmic derivation
            Map ov = lookupIntegration(appType) ?: lookupIntegration(appLabel)
            String raw = appType ?: appLabel
            String integration = (ov?.name) ? (String) ov.name : cleanIntegrationName(raw)
            // Connection type: override wins; otherwise LAN signal ⇒ local, no LAN ⇒ cloud
            String connectionType
            if (ov?.conn) {
                connectionType = (String) ov.conn
            } else {
                connectionType = (device.isNetwork == true) ? CONN_LAN_DIRECT : CONN_CLOUD
            }
            return [connectionType: connectionType, integration: integration ?: raw, builtin: isBuiltin]
        }
    }

    // 2b. Override file by driver type name — the declarative path for standalone community devices
    //     (no parent app) the derivation can't place: a LAN device the hub flags isNetwork=false would
    //     mis-derive to cloud (or fall to Other), a cloud device with no signals falls to Other. Users
    //     add e.g. {"awair": {"conn": "lan_direct"}} to the File Manager override file; matched by the
    //     same substring lookup used for parent-app names, so it wins over the isNetwork derivation.
    Map typeOverride = lookupIntegration(safeToString(device.type, ""))
    if (typeOverride?.conn) {
        String typeName = safeToString(device.type, "")
        String integration = (typeOverride.name) ? (String) typeOverride.name : cleanIntegrationName(typeName)
        return [connectionType: (String) typeOverride.conn, integration: integration ?: typeName, builtin: driverIsBuiltin]
    }

    // 3. Network (LAN) flag — parentApp not available in bulk list; will be enriched via fullJson in deep mode
    if (device.isNetwork == true) return [connectionType: CONN_LAN_DIRECT, integration: "LAN Device", builtin: driverIsBuiltin]

    // 4. Final fallback — no reliable signal for connection type
    return [connectionType: CONN_OTHER, integration: "Other", builtin: driverIsBuiltin]
}

// Enriches device classification using device/fullJson for devices bulk data couldn't resolve.
// uncertainDevices: Map<String deviceId, Map appInfo> where appInfo may be null.
// Primary signal: parentApp from fullJson (appType.name) — runs the same algorithm-primary logic as
//   classifyDevice: integration = cleanIntegrationName(appType.name), connectionType derived from
//   the controllerType signal (NET/LAN ⇒ lan_direct, else cloud); INTEGRATION_OVERRIDES supplies a conn exception.
// Fallback signal: controllerType from fullJson top level (actual values: ZGB, MAT, LNK, etc.).
// Results cached in state.controllerTypeCache — keyed by device ID string, value is compact
// JSON of [parentAppTypeName, controllerType] since parentApp is also stable for a device's lifetime.
// Returns Map<String deviceId, Map [connectionType, integration]> for devices that improve.
Map enrichDevices(Map uncertainDevices, Set communityAppTypeNames = [] as Set) {
    if (!uncertainDevices) return [:]

    Map cache = (state.controllerTypeCache ?: [:]) as Map
    Map cacheUpdates = [:]
    Map result = [:]

    uncertainDevices.each { String idStr, Map appInfo ->
        String cachedJson = (String) cache[idStr]
        Map cachedEntry = null
        if (cachedJson?.startsWith("{")) {
            try { cachedEntry = (Map) new groovy.json.JsonSlurper().parseText(cachedJson) }
            catch (Exception ignored) { /* stale/invalid format — re-fetch */ }
        }

        String parentAppTypeName = cachedEntry?.parentAppTypeName
        String ct = cachedEntry?.controllerType
        String connHint = cachedEntry?.connHint  // community developer override via updateDataValue("hubdiag:conn", ...)
        Boolean isBuiltin = null  // set from parentApp.appType.user when fetched live

        if (!cachedEntry) {
            try {
                Map fullWrap = hubMapRequest("${FULL_JSON_PATH_PREFIX}${idStr}", "device ${idStr} full", 10)
                Map full = fullWrap.ok ? fullWrap.data : null
                Map parentApp = full ? (Map) full.parentApp : null
                if (parentApp) {
                    Map appTypeObj = parentApp.appType instanceof Map ? (Map) parentApp.appType : [:]
                    parentAppTypeName = safeToString(appTypeObj.name ?: parentApp.name, "")
                    isBuiltin = !(appTypeObj.user == true)
                }
                ct = safeToString(full?.controllerType, "").toUpperCase()
                // Check for community driver classification hint: updateDataValue("hubdiag:conn", "cloud|lan_direct|lan_bridge|paired")
                try {
                    String dataJson = safeToString(full?.device?.dataJson, "")
                    if (dataJson?.startsWith("{")) {
                        Map dataValues = (Map) new groovy.json.JsonSlurper().parseText(dataJson)
                        String hint = safeToString(dataValues?.get("hubdiag:conn"), "")
                        if (hint && CONN_DISPLAY.containsKey(hint)) connHint = hint
                    }
                } catch (Exception ignored) {}
                cacheUpdates[idStr] = groovy.json.JsonOutput.toJson([
                    parentAppTypeName: parentAppTypeName ?: "",
                    controllerType: ct ?: "",
                    connHint: connHint ?: "",
                    builtin: isBuiltin == null ? "" : (isBuiltin ? "true" : "false")
                ])
            } catch (Exception e) {
                logDebug "enrichDevices: could not fetch device ${idStr}: ${e.message}"
                return
            }
        } else {
            String builtinStr = (String) cachedEntry.builtin
            if (builtinStr == "true") isBuiltin = true
            else if (builtinStr == "false") isBuiltin = false
        }

        // If builtin still unknown (stale cache or missing appType.user), resolve from appsList data
        if (isBuiltin == null && parentAppTypeName) {
            isBuiltin = !communityAppTypeNames.contains(parentAppTypeName)
        }

        // Community driver hint takes top priority
        if (connHint) {
            String intName = appInfo ? ((String)(appInfo.type ?: appInfo.label) ?: "Community Device") : "Community Device"
            result[idStr] = [connectionType: connHint, integration: intName, builtin: false]
            return
        }

        // Primary: algorithm-primary parent-app classification (mirrors classifyDevice branch 2)
        if (parentAppTypeName) {
            Map ov = lookupIntegration(parentAppTypeName)
            String integration = (ov?.name) ? (String) ov.name : cleanIntegrationName(parentAppTypeName)
            // Connection type: override wins; LAN controllerType ⇒ lan_direct; else cloud
            String connectionType
            if (ov?.conn) {
                connectionType = (String) ov.conn
            } else {
                connectionType = (ct == "NET" || ct == "LAN") ? CONN_LAN_DIRECT : CONN_CLOUD
            }
            result[idStr] = [connectionType: connectionType, integration: integration ?: parentAppTypeName, builtin: isBuiltin]
            return
        }

        // Fallback: controllerType
        if (ct) {
            String connType = (String) CONTROLLER_TYPE_CONN[ct]
            if (connType && connType != CONN_OTHER) {
                String intName = appInfo ? ((String)(appInfo.type ?: appInfo.label) ?: ct) : ct.toLowerCase().capitalize()
                result[idStr] = [connectionType: connType, integration: intName, builtin: null]
            }
        }
    }

    if (cacheUpdates) {
        Map updatedCache = new LinkedHashMap(cache)
        updatedCache.putAll(cacheUpdates)
        state.controllerTypeCache = updatedCache
    }

    return result
}

// ===== PERFORMANCE CHECKPOINT SYSTEM =====

boolean createCheckpoint() {
    // v5.32.6: in-flight guard. Prevents a scheduled tick from racing a user-triggered
    // Save Checkpoint, which would stack file I/O and HTTP fetches on the app thread.
    // atomicState (not state) because state commits at method exit — too late for the race.
    Long inFlight = atomicState.checkpointInFlight as Long
    if (inFlight && (now() - inFlight) < 300_000L) {
        logInfo "createCheckpoint skipped — already in flight since ${new Date(inFlight)}"
        return false
    }
    atomicState.checkpointInFlight = now()
    try {
        return doCreateCheckpoint()
    } finally {
        atomicState.checkpointInFlight = null
    }
}

// v5.33.0: scheduled-only async entry point. The Hubitat cron handler calls this and
// returns immediately after firing the first asynchttpGet; the chain callbacks finalize
// off the app thread. Keeps user-triggered apiCreateCheckpoint sync so the HTTP caller
// gets a real success/fail response.
void scheduledCheckpoint() {
    Long inFlight = atomicState.checkpointInFlight as Long
    if (inFlight && (now() - inFlight) < 300_000L) {
        logInfo "scheduledCheckpoint skipped — already in flight since ${new Date(inFlight)}"
        return
    }
    atomicState.checkpointInFlight = now()
    fireAsyncCheckpointChain()
}

private void fireAsyncCheckpointChain() {
    logInfo "Starting async scheduled checkpoint..."
    asyncCheckpointStaging = [chainStartMs: now(), statsAttempt: 0]
    dispatchRuntimeStatsFetch()
}

// v5.62.0: factored out so the runtime-stats leg can be re-fired on a transient timeout.
private void dispatchRuntimeStatsFetch() {
    if (asyncCheckpointStaging == null) return
    asyncCheckpointStaging.statsAttempt = ((asyncCheckpointStaging.statsAttempt ?: 0) as int) + 1
    Map params = [uri: HUB_BASE, path: RUNTIME_STATS_PATH, contentType: "application/json", timeout: 30]
    try {
        asynchttpGet("asyncOnRuntimeStats", params, null)
    } catch (Exception e) {
        logError "scheduledCheckpoint: failed to dispatch runtime stats fetch: ${e.message}"
        abortAsyncChain()
    }
}

// runIn target for the single bounded retry of the runtime-stats leg.
void retryRuntimeStatsFetch() {
    if (asyncCheckpointStaging == null) {
        // Chain was aborted or staging was reset (e.g. code push) during the retry wait.
        logDebug "scheduledCheckpoint: runtime stats retry skipped — chain no longer active"
        abortAsyncChain(); return
    }
    dispatchRuntimeStatsFetch()
}

void asyncOnRuntimeStats(resp, data) {
    if (resp?.hasError() || resp?.status != 200) {
        String why = resp?.hasError() ? "error: ${resp.getErrorMessage()}" : "HTTP ${resp?.status}"
        int attempt = (asyncCheckpointStaging?.statsAttempt ?: RUNTIME_STATS_MAX_ATTEMPTS) as int
        if (attempt < RUNTIME_STATS_MAX_ATTEMPTS) {
            logWarn "scheduledCheckpoint: runtime stats ${why} (attempt ${attempt}/${RUNTIME_STATS_MAX_ATTEMPTS}); retrying in ${RUNTIME_STATS_RETRY_S}s"
            runIn(RUNTIME_STATS_RETRY_S, "retryRuntimeStatsFetch")
            return
        }
        logError "scheduledCheckpoint: runtime stats ${why} — giving up after ${attempt} attempt(s)"
        abortAsyncChain(); return
    }
    try {
        asyncCheckpointStaging.stats = resp.json
        asyncCheckpointStaging.resources = fetchSystemResources()
        asyncCheckpointStaging.temperature = fetchTemperature()
        asyncCheckpointStaging.databaseSize = fetchDatabaseSize()
        asyncCheckpointStaging.timestamp = new Date().format("yyyy-MM-dd HH:mm:ss")
        asyncCheckpointStaging.timestampMs = now()
    } catch (Exception e) {
        logError "scheduledCheckpoint: stage stats: ${e.message}"
        abortAsyncChain(); return
    }
    chainNextRadio(true)
}

private void chainNextRadio(boolean zwave) {
    long nowMs = now()
    Map cached = zwave ? cachedZwaveData : cachedZigbeeData
    Long cachedAt = zwave ? cachedZwaveAt  : cachedZigbeeAt
    String path = zwave ? ZWAVE_DETAILS_PATH : ZIGBEE_DETAILS_PATH
    String cbName = zwave ? "asyncOnZwave" : "asyncOnZigbee"
    if (cached && cachedAt && (nowMs - cachedAt) < RADIO_CACHE_TTL_MS) {
        // Cache hit — invoke callback synchronously with a cached carrier so the
        // callback shape stays uniform across cache hit and async fetch.
        this."${cbName}"(null, [cached: true, body: cached])
        return
    }
    Map params = [uri: HUB_BASE, path: path, contentType: "application/json", timeout: 8]
    try {
        asynchttpGet(cbName, params, null)
    } catch (Exception e) {
        logError "scheduledCheckpoint: dispatch ${zwave ? 'Z-Wave' : 'Zigbee'}: ${e.message}"
        // Continue chain with empty data rather than abort
        this."${cbName}"(null, [cached: true, body: [:]])
    }
}

void asyncOnZwave(resp, data) {
    Map zw = extractAsyncBody(resp, data, "Z-Wave")
    if (zw != null && !data?.cached) { cachedZwaveData = zw; cachedZwaveAt = now() }
    asyncCheckpointStaging.zwaveRaw = zw ?: [:]
    chainNextRadio(false)
}

void asyncOnZigbee(resp, data) {
    Map zb = extractAsyncBody(resp, data, "Zigbee")
    if (zb != null && !data?.cached) { cachedZigbeeData = zb; cachedZigbeeAt = now() }
    asyncCheckpointStaging.zigbeeRaw = zb ?: [:]
    finalizeAsyncCheckpoint()
}

private Map extractAsyncBody(resp, data, String name) {
    if (data?.cached) return (Map) data.body
    if (resp == null) return [:]
    if (resp.hasError()) {
        logDebug "scheduledCheckpoint: ${name} error: ${resp.getErrorMessage()}"
        return [:]
    }
    if (resp.status != 200) {
        logDebug "scheduledCheckpoint: ${name} HTTP ${resp.status}"
        return [:]
    }
    try { return resp.json instanceof Map ? (Map) resp.json : [:] }
    catch (Exception e) { logDebug "scheduledCheckpoint: ${name} parse: ${e.message}"; return [:] }
}

private void finalizeAsyncCheckpoint() {
    try {
        Map zwaveData = (Map) (asyncCheckpointStaging.zwaveRaw ?: [:])
        Map zigbeeData = (Map) (asyncCheckpointStaging.zigbeeRaw ?: [:])
        Map cp = [
            timestamp: asyncCheckpointStaging.timestamp,
            timestampMs: asyncCheckpointStaging.timestampMs,
            stats: asyncCheckpointStaging.stats,
            resources: asyncCheckpointStaging.resources,
            temperature: asyncCheckpointStaging.temperature,
            databaseSize: asyncCheckpointStaging.databaseSize,
            radioStats: [
                zwave: extractZwaveMessageCounts(zwaveData),
                zigbee: extractZigbeeMessageCounts(zigbeeData)
            ]
        ]
        persistCheckpoint(cp)
        long elapsed = now() - (asyncCheckpointStaging.chainStartMs as Long)
        logInfo "Scheduled checkpoint created (async chain, ${elapsed}ms wall)"
    } catch (Exception e) {
        logError "scheduledCheckpoint: finalize: ${e.message}"
    } finally {
        asyncCheckpointStaging = null
        atomicState.checkpointInFlight = null
    }
}

private void abortAsyncChain() {
    asyncCheckpointStaging = null
    atomicState.checkpointInFlight = null
}

// v5.32.6: radio fetch with cache-first + bounded budget. Used by checkpoint paths
// (createCheckpoint, apiPerformanceCompare 'now') where blocking the app thread for
// tens of seconds contributes to hub-wide overload. getPerformanceData has its own
// cache check inline (it returns the data directly into the response) so it keeps
// the longer 20s timeout — the user explicitly opened the tab and wants the data.
private Map fetchZwaveDataForCheckpoint() {
    long nowMs = now()
    if (cachedZwaveData && cachedZwaveAt && (nowMs - cachedZwaveAt) < RADIO_CACHE_TTL_MS) {
        logDebug "Using cached Z-Wave data for checkpoint (age ${nowMs - cachedZwaveAt}ms)"
        return cachedZwaveData
    }
    Map r = hubMapRequest(ZWAVE_DETAILS_PATH, "Z-Wave details (checkpoint)", 8, false)
    if (r.ok) { cachedZwaveData = r.data; cachedZwaveAt = nowMs; return r.data }
    return null
}

private Map fetchZigbeeDataForCheckpoint() {
    long nowMs = now()
    if (cachedZigbeeData && cachedZigbeeAt && (nowMs - cachedZigbeeAt) < RADIO_CACHE_TTL_MS) {
        logDebug "Using cached Zigbee data for checkpoint (age ${nowMs - cachedZigbeeAt}ms)"
        return cachedZigbeeData
    }
    Map r = hubMapRequest(ZIGBEE_DETAILS_PATH, "Zigbee details (checkpoint)", 8, false)
    if (r.ok) { cachedZigbeeData = r.data; cachedZigbeeAt = nowMs; return r.data }
    return null
}

private boolean doCreateCheckpoint() {
    logInfo "Creating perf checkpoint..."

    Map statsWrap = hubMapRequest(RUNTIME_STATS_PATH, "runtime stats")
    if (!statsWrap.ok) {
        logError "Failed to fetch current stats"
        return false
    }
    Map stats = statsWrap.data

    Map resources = fetchSystemResources()
    Float temperature = fetchTemperature()
    Integer databaseSize = fetchDatabaseSize()

    // v5.32.6: capture radio message counts via the same 60s @Field static cache used by
    // getPerformanceData. On a cold cache, bound the fetch to 8s with no retry — worst-case
    // per-radio blocking drops from ~40s (20s × once-retry) to 8s, halving total checkpoint
    // app-thread time. If the call still fails, store empty arrays rather than crashing.
    Map zwaveData = fetchZwaveDataForCheckpoint()
    Map zigbeeData = fetchZigbeeDataForCheckpoint()
    List zwaveRadio = extractZwaveMessageCounts(zwaveData)
    List zigbeeRadio = extractZigbeeMessageCounts(zigbeeData)

    Map checkpoint = [
        timestamp: new Date().format("yyyy-MM-dd HH:mm:ss"),
        timestampMs: now(),
        stats: stats,
        resources: resources,
        temperature: temperature,
        databaseSize: databaseSize,
        radioStats: [
            zwave: zwaveRadio,
            zigbee: zigbeeRadio
        ]
    ]

    persistCheckpoint(checkpoint)
    logInfo "Perf checkpoint created successfully"
    return true
}

void deleteCheckpoint(int index) {
    List idx = new ArrayList(loadCheckpointIndex())
    if (index >= 0 && index < idx.size()) {
        Map dropped = (Map) idx.remove(index)
        logInfo "Deleting checkpoint at index ${index} (${dropped?.timestamp})"
        deleteCheckpointDetail(dropped?.detailFile as String)
        saveCheckpointIndex(idx)
    }
}

void clearAllCheckpoints() {
    // Drop any per-checkpoint detail files lingering in File Manager (includes orphans
    // from interrupted writes — listHubFiles is the source of truth).
    List detailFiles = listHubFiles(CHECKPOINT_DETAIL_PREFIX)
    detailFiles.each { Map f -> deleteCheckpointDetail(f.name as String) }
    deleteFile(CHECKPOINT_INDEX_FILE)
    deleteFile(PERFORMANCE_COMPARISON_FILE)
    cachedCheckpointIndex = []
    state.checkpointIndex = []
    logInfo "All perf checkpoints cleared (${detailFiles.size()} detail file(s) removed)"
}

// ===== SNAPSHOT SYSTEM =====

void createSnapshot() {
    logInfo "Creating config snapshot..."

    // v5.13.0: capture additional facts surfaced by Phases 0–6.
    // Backup payload is slimmed (other-hub list dropped) to keep snapshot size bounded.
    Map backupsRaw = fetchBackups()
    Map backupsSlim = backupsRaw ? [
        local: backupsRaw.local,
        cloud: backupsRaw.cloud ? [
            thisHubCount: backupsRaw.cloud.thisHubCount,
            otherHubCount: backupsRaw.cloud.otherHubCount,
            latestThisHubCreateTime: backupsRaw.cloud.latestThisHubCreateTime,
            latestThisHubCreateTimeOrig: backupsRaw.cloud.latestThisHubCreateTimeOrig,
            latestThisHubVersion: backupsRaw.cloud.latestThisHubVersion,
            hasCloudBackupEntitlements: backupsRaw.cloud.hasCloudBackupEntitlements,
            hasCloudRestoreEntitlements: backupsRaw.cloud.hasCloudRestoreEntitlements
        ] : null
    ] : null
    // Hub variables: capture identity (name + type) only, not value — values churn from automations.
    Map hvRaw = fetchHubVariables() ?: [:]
    List hvSlim = ((hvRaw.variables as List) ?: []).collect { Map v -> [name: v.name, type: v.type] }

    Map snapshot = [
        timestamp: new Date().format("yyyy-MM-dd HH:mm:ss"),
        timestampMs: now(),
        devices: analyzeDevices(),
        apps: analyzeApps(),
        network: analyzeNetwork(),
        systemHealth: analyzeSystemHealth(),
        hubInfo: getHubInfo(),
        storage: fetchFileManagerStats(),
        backups: backupsSlim,
        security: fetchSecurityInfo(),
        ntpServer: fetchNtpServer(),
        loadThreshold: fetchExcessiveLoadThreshold(),
        code: [
            bundles:   fetchUserBundles().collect   { Map b -> [id: b.id, name: b.name, namespace: b.namespace] },
            libraries: fetchUserLibraries().collect { Map l -> [id: l.id, name: l.name, namespace: l.namespace, version: l.version] },
            hubVariables: hvSlim
        ]
    ]

    // Strip allDevices down to compact form for storage
    if (snapshot.devices.allDevices) {
        snapshot.devices.allDevices = snapshot.devices.allDevices.collect { Map dev ->
            [id: dev.id, name: dev.name, type: dev.type,
             connectionType: dev.connectionType, integration: dev.integration, status: dev.status]
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

Map getHubInfo(Map prefetchedHubData = null) {
    Map info = [name: location.name ?: "Unknown", firmware: "Unknown", hardware: "Unknown", ip: "Unknown"]
    if (location.hubs && location.hubs.size() > 0) {
        Hub hub = location.hubs[0]
        info.firmware = hub.firmwareVersionString ?: "Unknown"
        info.hardware = hub.type ?: "Unknown"
        info.ip = hub.localIP ?: "Unknown"
    }
    // Fetch model from hubData for accurate hardware name (e.g. "C-7", "C-8 Pro")
    Map hubData = prefetchedHubData
    if (!hubData) { Map r = hubMapRequest(HUB_DATA_PATH, "hub data", 10); hubData = r.ok ? r.data : null }
    if (hubData && hubData.model) {
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
        byConnectionType: [(CONN_PAIRED): 0, (CONN_LAN_DIRECT): 0, (CONN_LAN_BRIDGE): 0,
                           (CONN_CLOUD): 0, (CONN_VIRTUAL): 0, (CONN_HUBMESH): 0, (CONN_OTHER): 0],
        idsByConnectionType: [(CONN_PAIRED): [], (CONN_LAN_DIRECT): [], (CONN_LAN_BRIDGE): [],
                              (CONN_CLOUD): [], (CONN_VIRTUAL): [], (CONN_HUBMESH): [], (CONN_OTHER): []],
        byIntegration: [:],
        idsByIntegration: [:],
        integrationSources: [:],
        idsByStatus: [active: [], inactive: [], disabled: []],
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
        allApps: [], runtimeTotalApps: 0
    ]
}

String safeToString(value, String defaultValue = "") {
    if (value == null || value instanceof List) return defaultValue
    return value.toString()
}

@CompileStatic
String stripHtml(String s) {
    return s ? HTML_TAG_RE.matcher(s).replaceAll('').trim() : s
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
        String s1 = i < v1p.size() ? v1p[i] : "0"
        String s2 = i < v2p.size() ? v2p[i] : "0"
        int n1 = s1.isInteger() ? s1.toInteger() : 0
        int n2 = s2.isInteger() ? s2.toInteger() : 0
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

// ===== Checkpoint storage (v5.33.0 split-file) =====
//
// Layout in File Manager:
//   hub_diagnostics_checkpoints_index.json   — slim list, one entry per checkpoint
//   hub_diagnostics_checkpoint_{timestampMs}.json — full detail per checkpoint
//
// The hot Performance tab API reads only the index (small, fast). Compare reads
// one or two detail files on user action. Trim/delete remove detail files alongside.
// Legacy single-blob hub_diagnostics_checkpoints.json is migrated once on first read.

private Map buildCheckpointIndexEntry(Map cp, String detailFile) {
    Map s = (Map) cp?.stats
    return [
        timestamp: cp?.timestamp,
        timestampMs: cp?.timestampMs,
        stats: s ? [uptime: s.uptime, totalDevicesRuntime: s.totalDevicesRuntime, totalAppsRuntime: s.totalAppsRuntime] : null,
        resources: cp?.resources,
        temperature: cp?.temperature,
        databaseSize: cp?.databaseSize,
        detailFile: detailFile
    ]
}

private String detailFilenameFor(Object timestampMs) {
    return "${CHECKPOINT_DETAIL_PREFIX}${timestampMs}.json"
}

List loadCheckpointIndex() {
    // N4: return a defensive copy so a caller doing `loadCheckpointIndex() << x` can't mutate the
    // shared cache (which would also bypass the FileManager write in saveCheckpointIndex).
    if (cachedCheckpointIndex != null) return new ArrayList(cachedCheckpointIndex)
    try {
        List data = (List) readFile(CHECKPOINT_INDEX_FILE)
        if (data != null) {
            cachedCheckpointIndex = data
            state.checkpointIndex = cachedCheckpointIndex
            return new ArrayList(cachedCheckpointIndex)
        }
    } catch (Exception e) {
        logDebug "No existing checkpoint index: ${e.message}"
    }
    // No new index file — check for a legacy blob and migrate if found.
    List migrated = migrateLegacyCheckpointsIfPresent()
    cachedCheckpointIndex = migrated
    state.checkpointIndex = cachedCheckpointIndex
    return cachedCheckpointIndex == null ? null : new ArrayList(cachedCheckpointIndex)
}

void saveCheckpointIndex(List index) {
    try {
        String json = groovy.json.JsonOutput.toJson(index)
        writeFile(CHECKPOINT_INDEX_FILE, json)
        // N4: store our own copy so the caller's ongoing reference can't later mutate the cache.
        cachedCheckpointIndex = (index == null ? null : new ArrayList(index))
        state.checkpointIndex = cachedCheckpointIndex
    } catch (Exception e) {
        logError "Error saving checkpoint index: ${e}"
    }
}

Map loadCheckpointDetail(String filename) {
    if (!filename) return null
    try {
        def data = readFile(filename)
        return data instanceof Map ? (Map) data : null
    } catch (Exception e) {
        logError "Error reading checkpoint detail ${filename}: ${e.message}"
        return null
    }
}

String saveCheckpointDetail(Map cp) {
    String filename = detailFilenameFor(cp.timestampMs)
    try {
        String json = groovy.json.JsonOutput.toJson(cp)
        writeFile(filename, json)
        return filename
    } catch (Exception e) {
        logError "Error writing checkpoint detail ${filename}: ${e.message}"
        return null
    }
}

void deleteCheckpointDetail(String filename) {
    if (!filename) return
    try {
        deleteFile(filename)
    } catch (Exception e) {
        logDebug "Error deleting checkpoint detail ${filename}: ${e.message}"
    }
}

// One-shot legacy migration. Reads the v5.32.x single-blob file, splits it into
// per-checkpoint detail files + the new index, then deletes the legacy file.
// Idempotent: returns [] if nothing legacy is present.
private List migrateLegacyCheckpointsIfPresent() {
    List legacy
    try {
        List data = (List) readFile(CHECKPOINTS_FILE)
        if (data == null) {
            // No legacy file. First-time install — start with an empty index file
            // so subsequent reads short-circuit without a migration probe.
            saveCheckpointIndex([])
            return []
        }
        legacy = data
    } catch (Exception e) {
        logDebug "Legacy migration: no legacy file: ${e.message}"
        saveCheckpointIndex([])
        return []
    }
    if (!legacy) {
        saveCheckpointIndex([])
        deleteCheckpointDetail(CHECKPOINTS_FILE)
        return []
    }
    logInfo "Migrating ${legacy.size()} checkpoint(s) to split-file storage..."
    List newIndex = []
    legacy.each { Map cp ->
        String filename = saveCheckpointDetail(cp)
        if (filename) {
            newIndex << buildCheckpointIndexEntry(cp, filename)
        } else {
            logError "Migration: failed to write detail file for checkpoint ${cp.timestampMs}"
        }
    }
    saveCheckpointIndex(newIndex)
    deleteCheckpointDetail(CHECKPOINTS_FILE)
    logInfo "Migration complete: ${newIndex.size()} checkpoint detail file(s) + index"
    return newIndex
}

// v5.33.0: write a new checkpoint to disk. Used by both the sync (apiCreateCheckpoint)
// and async (scheduledCheckpoint) paths. Persists detail file first, then updates the
// index. Trims oldest entries beyond settings.maxCheckpoints, deleting their detail files.
void persistCheckpoint(Map cp) {
    String filename = saveCheckpointDetail(cp)
    if (!filename) {
        logError "persistCheckpoint: detail file write failed; index unchanged"
        return
    }
    Map indexEntry = buildCheckpointIndexEntry(cp, filename)
    List idx = new ArrayList(loadCheckpointIndex())
    idx.add(0, indexEntry)
    int cap = (settings.maxCheckpoints ?: 10) as int
    if (idx.size() > cap) {
        List dropped = idx.subList(cap, idx.size()) as List
        dropped.each { Map d -> deleteCheckpointDetail(d.detailFile as String) }
        idx = idx.take(cap)
    }
    saveCheckpointIndex(idx)
}

List getCheckpointIndex() {
    List idx = state.checkpointIndex as List
    // v5.33.0 upgrade-from-v5.32.6 detection: v5.32.6 populated state.checkpointIndex
    // with entries that lack the detailFile pointer. Treat as cold start so
    // loadCheckpointIndex runs migration of the legacy single-blob file.
    if (idx != null && !idx.isEmpty() && idx[0]?.detailFile == null) {
        logInfo "v5.33.0 upgrade detected: forcing migration of legacy checkpoint blob"
        state.checkpointIndex = null
        cachedCheckpointIndex = null
        idx = null
    }
    if (idx != null) return idx
    return loadCheckpointIndex()
}

List loadSnapshots() {
    try {
        List data = (List) readFile(SNAPSHOTS_FILE)
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

    // Hardware inventory (v5.37.0) — make/model/firmware from pairing-time data values.
    // dev.dataJson is a JSON *string* of the device's data-value map; keys vary by protocol and
    // driver, so read defensively (firstDataValue also skips blank values). Firmware key by
    // protocol: Zigbee → softwareBuild/application; Z-Wave → firmwareVersion; Matter → softwareVersion.
    // Zigbee exposes human-readable manufacturer/model; Z-Wave exposes them as numeric/hex IDs.
    // Virtual/cloud devices have no dataJson and yield blanks. ("make" has no distinct data value
    // on Hubitat — manufacturer is the make.)
    String manufacturer = null, model = null, firmware = null, firmwareOta = null, firmwareSource = null
    Map firmwareTargets = [:]
    try {
        String dataJson = safeToString(dev.dataJson, "")
        if (dataJson.startsWith("{")) {
            Map dv = (Map) new groovy.json.JsonSlurper().parseText(dataJson)
            manufacturer = firstDataValue(dv, ['manufacturer'])
            // Zigbee/cloud expose `model`; Z-Wave has no `model` key — it uses `deviceModel` (e.g. ZEN55).
            // When even deviceModel is absent (some Z-Wave devices), identify by the unique deviceType:deviceId
            // pair, so distinct products sharing one numeric manufacturer id (e.g. ZOOZ = 634) don't collapse
            // into a single group and get flagged as false firmware drift.
            model        = firstDataValue(dv, ['model', 'deviceModel'])
            if (!model && safeToString(fj?.controllerType, "").trim().equalsIgnoreCase("ZWV")) {
                String dt = firstDataValue(dv, ['deviceType']), di = firstDataValue(dv, ['deviceId'])
                if (dt && di) model = "${dt}:${di}"
            }
            for (String k : ['softwareBuild', 'application', 'firmwareVersion', 'softwareVersion']) {
                String v = firstDataValue(dv, [k])
                if (v) { firmware = v; firmwareSource = k; break }
            }
            String firmwareMT = firstDataValue(dv, ['firmwareMT'])
            // OTA fileVersion = last '-' segment of firmwareMT ("1233-D3A6-10013065" -> "10013065").
            // Canonical/comparable firmware id for drift detection across identical hardware, where the
            // human-readable softwareBuild can differ in representation or be absent. Display still uses `firmware`.
            firmwareOta = firmwareMT ? firmwareMT.tokenize('-')[-1] : null
            // Multi-target Z-Wave firmware (v5.51.0): some Z-Wave devices (e.g. locks) expose secondary
            // firmware chips as firmware1Version, firmware2Version, … alongside the primary firmwareVersion.
            // Collect all targets by index so identical devices can be compared across all chips.
            dv.each { kk, vv ->
                String idx = null
                if (kk == 'firmwareVersion') idx = '0'
                else { java.util.regex.Matcher fm = (kk =~ /^firmware(\d+)Version$/); if (fm.matches()) idx = fm.group(1) }
                if (idx != null) { String s = safeToString(vv, '').trim(); if (s) firmwareTargets[idx] = s }
            }
        }
    } catch (Exception ignored) { /* malformed dataJson — leave inventory fields blank */ }
    String protocol = controllerTypeLabel(safeToString(fj?.controllerType, ""))

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
        // R-5 (v5.18.0, A9): schema version sentinel for forward-compat. Bump when extractAuditFields'
        // output shape changes in a way that downstream consumers (finalizeAudit enrichment passes)
        // need to know about. AUDIT_SCANS is in-memory only, so old records
        // never persist across an app reload — but cross-restart cases or future on-disk persistence
        // benefit from being able to detect the format.
        _schemaVersion:      3,
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
        lastActivityTimeMs:  parseHubitatTimestamp(dev.lastActivityTime as String),  // epoch for SPA-side unreferenced sort
        parentDeviceId:      (dev.parentDeviceId as Long),
        childDeviceIds:      ((fj?.childDevices ?: [:]) as Map).keySet()?.collect { it as Long } ?: [],
        notes:               dev.notes,
        tags:                dev.tags,

        // Hardware inventory (Section D, promoted v5.37.0)
        manufacturer:        manufacturer,
        model:               model,
        firmware:            firmware,
        firmwareSource:      firmwareSource,
        firmwareOta:         firmwareOta,
        firmwareTargets:     (firmwareTargets.size() > 1 ? firmwareTargets : null),
        protocol:            protocol,
        // Authoritative two-dimension classification — stamped in finalizeAudit() by joining
        // analyzeDevices()'s classifyDevice result (the raw `protocol` above is null on many hubs).
        connectionType:      null,
        integration:         null,

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

/**
 * First non-blank value among `keys` in a device data-value map, or null.
 * Lets extractAuditFields read make/model/firmware defensively across protocols, since the
 * exact key differs (Zigbee firmware is application/softwareBuild; Z-Wave is firmwareVersion).
 */
private String firstDataValue(Map dv, List<String> keys) {
    for (String k : keys) {
        Object v = dv?.get(k)
        if (v != null) {
            String s = v.toString().trim()
            if (s) return s
        }
    }
    return null
}

/**
 * Map a Hubitat controllerType code to a human-readable protocol name for the inventory.
 * Unknown codes pass through verbatim; blank (virtual/cloud) yields null.
 * Keyed on the same controllerType codes as CONTROLLER_TYPE_CONN, which maps them to
 * connection-type constants instead (distinct key sets, so they are not merged).
 */
private String controllerTypeLabel(String ct) {
    if (!ct) return null
    switch (ct.trim().toUpperCase()) {
        case 'ZGB': return 'Zigbee'
        case 'ZWV': return 'Z-Wave'
        case 'MAT': return 'Matter'
        case 'LNK': return 'Linked'      // Hub Mesh device linked from another hub
        case 'LAN': return 'LAN'
        case 'BLE':
        case 'BTH': return 'Bluetooth'
        default:    return ct
    }
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
            Map record = extractAuditFields(fj, deviceId)
            (scan.devices as ConcurrentHashMap)[deviceId] = record
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
    } else if (inFlight == 0 && (scan.processed as AtomicInteger).get() >= total) {
        // Finalize trigger must read `processed` FRESH, not this callback's local increment
        // value. `processed` and `inFlight` are independent atomics: the callback that zeroes
        // inFlight is not necessarily the one whose increment hit `total`, so the local
        // `processed` can be stale (< total) on the very callback that sees inFlight == 0 —
        // leaving the scan unfinalized until the watchdog fails it. Because every callback
        // increments processed *before* decrementing inFlight, once inFlight hits 0 all
        // increments have landed, so a fresh read is guaranteed == total. inFlight.decrement
        // returns 0 for exactly one callback, so finalize still fires exactly once.
        finalizeAudit(scanId)
    }
}

/**
 * Finalize a completed scan: build cross-reference, store result in volatile memory,
 * update state.audit snapshot, free memory.
 */
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

    // Ship the raw collected device records; the SPA derives the cross-reference
    // (unreferenced / mesh orphans / critical ranking / apps↔devices / tuned-device divergence)
    // from allDevices — see buildAuditXref() in hub_diagnostics_ui.html.
    Map xref = [
        deviceCount:       succeeded,
        scanStartedMs:     startedAt,
        scanDurationMs:    (now() - startedAt),
        allDevices:        devices
    ]

    // Phase 4 enrichment: rooms, Z-Wave JS per-node, Hub Mesh per-device
    long enrichStart = now()
    xref.rooms = fetchRoomsForAudit()

    // Z-Wave JS per-node enrichment (only when Z-Wave JS stack is active)
    if (detectZwaveStack() == "js") {
        Map zwData = reqData(ZWAVE_DETAILS_PATH, "Z-Wave details (audit enrichment)", 10)
        Map zwNodeByDevId = [:]
        if (zwData) {
            ((zwData.zwDevices as Map) ?: [:]).each { Object _key, Object val ->
                if (val instanceof Map) {
                    Long devId = (val as Map).deviceId as Long
                    Integer nodeId = (val as Map).nodeId as Integer
                    if (devId && nodeId) zwNodeByDevId[devId] = nodeId
                }
            }
        }
        zwNodeByDevId.each { Object _devIdObj, Object _nodeIdObj ->
            Long devId = _devIdObj as Long
            Integer nodeId = _nodeIdObj as Integer
            Map record = devices[devId] as Map
            if (record == null) return
            Map nodeState = fetchZwaveNodeState(nodeId)
            if (nodeState) record.zwaveNode = nodeState + [nodeId: nodeId]
        }
    }

    // Hub Mesh per-device enrichment
    Map hubMeshData = reqData(HUB_MESH_PATH, "Hub Mesh (audit enrichment)", 10)
    List linkedDevices = (hubMeshData?.linkedDevices as List) ?: []
    linkedDevices.each { Map ld ->
        Long devId = ld.id as Long
        Map record = (devId != null) ? (devices[devId] as Map) : null
        if (record == null) return
        Map ldState = fetchHubMeshDeviceState(devId)
        if (ldState) record.hubMeshState = ldState
    }

    // Connection-type / integration classification — parity with the Dashboard/Devices view.
    // Audit records carry only the raw `protocol` (controllerTypeLabel), which is null on many
    // hubs for virtual/cloud/LAN/integration-owned devices. analyzeDevices() runs the authoritative
    // classifyDevice + enrichDevices passes off the bulk devicesList is* flags; join its per-device
    // result onto the audit records by id so external consumers (e.g. Multi-Hub Inventory) get the
    // same connectionType/integration the SPA shows — without duplicating the classification logic.
    try {
        List classified = (analyzeDevices(true)?.allDevices ?: []) as List
        Map classById = classified.collectEntries { Map d -> [(d.id?.toString()): d] }
        devices.each { Object devId, Object recObj ->
            Map cls = (Map) classById[devId?.toString()]
            if (cls) {
                ((Map) recObj).connectionType = cls.connectionType
                ((Map) recObj).integration    = cls.integration
            }
        }
    } catch (Exception e) {
        logWarn "[audit ${scanId}] classification enrichment failed: ${e.message}"
    }

    logDebug "Audit Phase 4 enrichment finished in ${now() - enrichStart}ms"

    // Store result in volatile memory (lost on hub restart — acceptable). The SPA formats
    // generatedMs for display (toLocaleString) and owns the critical-reference threshold.
    Map hubInfo = getHubInfo()
    xref.hubName = hubInfo?.name ?: "Hubitat"
    xref.hubModel = hubInfo?.hardware
    xref.hubFirmware = hubInfo?.firmware
    xref.generatedMs = now()
    xref.failed = failedMap.collect { id, reason -> [id: id, reason: reason] }

    lastAuditResult = xref

    // Snapshot for UI
    state.audit = [
        scanId:    scanId,
        status:    errored ? 'error' : 'done',
        processed: (scan.processed as AtomicInteger).get(),
        total:     total,
        startedAt: startedAt
    ]

    AUDIT_SCANS.remove(scanId)
    logInfo "[audit ${scanId}] finalized — ${succeeded}/${total} devices, ${failed} failed, ${(now()-startedAt)}ms"
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
    Map bulkWrap = hubMapRequest(DEVICES_LIST_PATH, "devices list", 30)
    if (!bulkWrap.ok) {
        return jsonResponse([error: "Failed to fetch device list", detail: bulkWrap.error])
    }
    List devs = flattenDeviceList((bulkWrap.data.devices ?: []) as List)
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
 * Flatten the parent/child/grandchild structure returned by /hub2/devicesList into a flat list.
 */
private List flattenDeviceList(List items) {
    List out = []
    items.each { Map item ->
        out << item
        List children = (item.children ?: []) as List
        if (children) out.addAll(flattenDeviceList(children))
    }
    return out
}

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
    // R-6 G1 (v5.19.0): when a scan is still in-flight, read processed/total directly from
    // AUDIT_SCANS' AtomicInteger. The fullJsonCb handler updates state.audit.processed from
    // up to 8 concurrent callbacks — Hubitat's "last write wins" persistence semantics mean
    // the snapshot can lag by several count units (never wrong direction, just slightly
    // behind). Reading the AtomicInteger eliminates the lag for live progress polling.
    String scanId = (snap.scanId ?: requested) as String
    ConcurrentHashMap scan = scanId ? (AUDIT_SCANS[scanId] as ConcurrentHashMap) : null
    Integer processed = scan ? (scan.processed as AtomicInteger).get() : (snap.processed as Integer)
    Integer total     = scan ? (scan.total as Integer)                  : (snap.total     as Integer)
    return jsonResponse([
        scanId:    snap.scanId,
        status:    snap.status,
        processed: processed ?: 0,
        total:     total ?: 0,
        startedAt: snap.startedAt,
        error:     snap.error
    ])
}


/**
 * GET /api/audit/data — returns the most recent audit result from volatile memory.
 * Returns 404 if no audit has been run since the last hub restart.
 */
Map apiAuditData() {
    if (lastAuditResult == null) {
        return render(status: 404, contentType: 'application/json', data: '{"error":"no audit result available"}')
    }
    return jsonResponse(lastAuditResult)
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
    runIn(1, 'syncUIForced')
    initialize()
}

void updated() {
    logInfo "Hub Diagnostics updated"
    state.installed = true
    // Convert the scale-display threshold inputs to canonical Celsius storage. Thresholds are
    // always compared in Celsius, so a later hub scale change can never reinterpret them.
    if (settings.warnTempInput != null) app.updateSetting("warnTempC", [type: "decimal", value: scaleToC(settings.warnTempInput as BigDecimal)])
    if (settings.critTempInput != null) app.updateSetting("critTempC", [type: "decimal", value: scaleToC(settings.critTempInput as BigDecimal)])
    unsubscribe()
    unschedule()
    // clear session-scoped caches so config/hardware changes take effect immediately
    zwaveStackCache  = null   // re-detect Z-Wave stack on next use (handles user switching legacy ↔ JS)
    fwUpdateCache    = null   // force fresh cloud fetch on next dashboard render
    fwUpdateCacheAt  = null
    state.remove('controllerTypeCache') // evict per-device classification cache; rebuilds on next analysis pass
    apiTimings.clear()                  // drop stats for renamed/removed endpoints; fresh measurements from now
    // N1: clear the TTL'd radio/list/resource caches too, so a settings change isn't masked by
    // stale data for up to the cache TTL. Matches the A3/A7/B5 invalidation discipline.
    cachedZwaveData = null;        cachedZwaveAt = null
    cachedZigbeeData = null;       cachedZigbeeAt = null
    cachedAppsListData = null;     cachedAppsListAt = null
    cachedDevicesListData = null;  cachedDevicesListAt = null
    cachedSystemResources = null;  cachedSystemResourcesAt = null
    cachedTemperature = null;      cachedTemperatureAt = null
    cachedDatabaseSize = null;     cachedDatabaseSizeAt = null
    cachedCpuInfo = null;          cachedCpuInfoAt = null
    cachedLoadThreshold = null;    cachedLoadThresholdAt = null
    cachedCheckpointIndex = null   // re-read the checkpoint index from FileManager on next access
    integrationOverridesCache = null  // reload user integration-overrides file on next use
    // C2: auto-disable debug logging after 30 min so it can't be left on indefinitely
    if (settings.debugLogging) runIn(1800, 'logsOff')
    runIn(1, 'syncUIForced')
    initialize()
}

void logsOff() {
    app.updateSetting("debugLogging", [type: "bool", value: false])
    logInfo "Debug logging auto-disabled after 30 minutes"
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

// Async UI sync — used by scheduled job and lifecycle paths. Fire-and-forget.
void syncUI(boolean force = false) {
    if (!force && state.lastInstalledVersion == APP_VERSION) {
        long lastCheck = state.lastUIUpdateCheck ?: 0
        if (now() - lastCheck < 86400000) return
    }
    logInfo "Hub Diagnostics: Syncing UI from GitHub (async)..."
    asynchttpGet('syncUICallback', [uri: IMPORT_URL_WEB, contentType: "text/plain", timeout: 30])
}

void syncUICallback(resp, data) {
    if (resp.hasError() || resp.status != 200) {
        logWarn "Async UI sync failed: HTTP ${resp.status}"
        return
    }
    processSyncUIResponse(resp.data ?: "")
}

// Blocking UI sync — only for emergency recovery (file missing) and explicit API endpoint.
private boolean syncUIBlocking() {
    try {
        logInfo "Hub Diagnostics: Syncing UI from GitHub (blocking)..."
        String htmlText = null
        httpGet([uri: IMPORT_URL_WEB, contentType: "text/plain", timeout: 30]) { resp ->
            if (resp.success && resp.data) htmlText = resp.data.text ?: resp.data.toString()
        }
        return processSyncUIResponse(htmlText ?: "")
    } catch (Exception e) {
        logWarn "Failed to sync UI from GitHub: ${e.message}"
        return false
    }
}

private boolean processSyncUIResponse(String htmlText) {
    if (!htmlText || !htmlText.contains("Hub Diagnostics")) {
        logWarn "Sync failed: Downloaded content appears invalid"
        return false
    }
    if (!htmlText.contains("const UI_VERSION = \"${APP_VERSION}\"")) {
        logWarn "Sync failed: GitHub UI version does not match App v${APP_VERSION}"
        return false
    }
    byte[] htmlBytes = htmlText.getBytes("UTF-8")
    uploadHubFile("hub_diagnostics_ui.html", htmlBytes)
    state.lastInstalledVersion = APP_VERSION
    state.lastUIUpdateCheck = now()
    uiVersionCache = APP_VERSION
    logInfo "UI updated from GitHub to match App v${APP_VERSION} (${htmlBytes.length} bytes)"
    return true
}

void initialize() {
    logInfo "Hub Diagnostics initialized"
    migrateStorageIfNeeded()

    // Reconcile audit state: if a scan was in-flight when the app reloaded, AUDIT_SCANS is now
    // empty and the scan can never complete — mark it failed so the UI doesn't get stuck.
    Map audit = (state.audit ?: [:]) as Map
    if (audit.status == 'scanning' && audit.scanId && !AUDIT_SCANS[audit.scanId as String]) {
        state.audit = audit + [status: 'error', error: 'Scan interrupted by hub reboot/app reload']
        logWarn "[audit] cleared orphaned scan ${audit.scanId} (in-memory state lost on reload)"
    }

    if (settings.autoSnapshot) {
        int days = (settings.snapshotInterval ?: 1).toInteger()
        String cron = days == 1 ? "0 0 0 * * ?" : "0 0 0 */${days} * ?"
        schedule(cron, "createSnapshot")
        logInfo "Automatic config snapshots scheduled every ${days} day(s)"
    }

    if (settings.autoCheckpoint) {
        int interval = (settings.checkpointInterval ?: "60").toInteger()
        int offsetSec = (state.checkpointOffsetSeconds ?: -1) as int
        if (offsetSec < 180 || offsetSec > 420) {
            offsetSec = 180 + new Random().nextInt(241)
            state.checkpointOffsetSeconds = offsetSec
        }
        int sec = offsetSec % 60
        int min = offsetSec.intdiv(60)
        String cron
        if (interval < 60) {
            cron = "${sec} ${min}/${interval} * * * ?"
        } else {
            int hours = (interval / 60).toInteger()
            cron = hours >= 24 ? "${sec} ${min} 0 * * ?" : "${sec} ${min} */${hours} * * ?"
        }
        // v5.33.0: scheduledCheckpoint fires the async chain and returns immediately,
        // so the platform scheduler is never blocked on radio/file work.
        schedule(cron, "scheduledCheckpoint")
        String mm = min.toString().padLeft(2, '0')
        String ss = sec.toString().padLeft(2, '0')
        logInfo "Automatic perf checkpoints scheduled every ${interval} minute(s) at :${mm}:${ss} past the hour"
    }

    // v5.15.0: daily UI sync moved out of serveUI hot path. 03:17 local time, off-peak.
    schedule("0 17 3 * * ?", "scheduledUISync")
    logInfo "Daily UI sync scheduled at 03:17"

    if (settings.snapshotTriggerSwitch) {
        subscribe(settings.snapshotTriggerSwitch, "switch.on", "snapshotSwitchHandler")
        logInfo "Config snapshot trigger armed on ${settings.snapshotTriggerSwitch}"
    }
    if (settings.checkpointTriggerSwitch) {
        subscribe(settings.checkpointTriggerSwitch, "switch.on", "checkpointSwitchHandler")
        logInfo "Perf checkpoint trigger armed on ${settings.checkpointTriggerSwitch}"
    }
}

// ===== SWITCH TRIGGER HANDLERS =====

void snapshotSwitchHandler(evt) {
    if (evt?.value != "on") return        // subscribed to switch.on; defensive
    // lightweight debounce: createSnapshot does heavy API work; ignore a bounce
    Long last = state.lastSnapshotTriggerMs as Long
    if (last && (now() - last) < 30_000L) {
        logInfo "snapshotSwitchHandler: ignored re-trigger within 30s"
        return
    }
    state.lastSnapshotTriggerMs = now()
    logInfo "Config snapshot triggered by switch ${evt.displayName}"
    createSnapshot()
}

void checkpointSwitchHandler(evt) {
    if (evt?.value != "on") return
    logInfo "Perf checkpoint triggered by switch ${evt.displayName}"
    scheduledCheckpoint()                 // already has a 300s in-flight guard
}

void scheduledUISync() {
    logDebug "Running scheduled UI sync"
    syncUI(false)
}

void syncUIForced() {
    syncUI(true)
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

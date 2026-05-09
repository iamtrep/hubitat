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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

@Field static final String APP_VERSION = "5.24.0"
@Field static final String STORAGE_SCHEMA_VERSION = "4.0.0"

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
@Field static final String NETWORK_TEST_PING_GATEWAY = "/hub/networkTest/ping/gateway"
@Field static final String NETWORK_TEST_PING_PREFIX = "/hub/networkTest/ping/"
@Field static final String NETWORK_TEST_SPEEDTEST = "/hub/networkTest/speedtest"
@Field static final String MIN_FW_RADIO_HEALTH = "2.4.1.154"
@Field static final long   FW_UPDATE_CACHE_TTL_MS = 3600_000L

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

// Connection type constants
@Field static final String CONN_PAIRED = "paired"
@Field static final String CONN_LAN_DIRECT = "lan_direct"
@Field static final String CONN_LAN_BRIDGE = "lan_bridge"
@Field static final String CONN_CLOUD = "cloud"
@Field static final String CONN_VIRTUAL = "virtual"
@Field static final String CONN_HUBMESH = "hubmesh"
@Field static final String CONN_OTHER = "other"

@Field static final long ONE_DAY_MS = 86400000
@Field static final int API_TIMING_WINDOW = 20

// Protocol-level constants — hardcoded, not user-configurable (industry-standard values)
@Field static final int    ZIGBEE_LQI_WARN = 200
@Field static final int    ZIGBEE_LQI_CRIT = 150
@Field static final double ZWAVE_PER_CRIT  = 1.0

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

// Integration classification table: lowercase keyword → [conn: connectionType, name: displayName]
// Keys are matched as substrings against parent app type/label.
// Entries are ordered longest-first to avoid false positives (e.g., "wiz" matching "wizard").
// LinkedHashMap preserves insertion order, which is the iteration order used by lookupIntegration().
@Field static final Map INTEGRATION_TABLE = [
    // 19
    "mobile app manager": [conn: "cloud",   name: "Mobile App"],
    // 12
    "home connect": [conn: "cloud", name: "Home Connect"],
    // 11
    "philips hue" : [conn: "lan_bridge", name: "Philips Hue"],
    // 10
    "hue bridge"  : [conn: "lan_bridge", name: "Philips Hue"],
    // 9
    "bluetooth"   : [conn: "paired", name: "Bluetooth"],
    // 8
    "icomfort"    : [conn: "cloud", name: "iComfort"],
    // 7
    "homekit"     : [conn: "paired", name: "HomeKit"],
    "samsung"     : [conn: "cloud", name: "SmartThings"],
    // 6
    "bthome"      : [conn: "paired",    name: "BTHome"],
    "shelly"      : [conn: "lan_direct", name: "Shelly"],
    "lutron"      : [conn: "lan_bridge", name: "Lutron"],
    "ecobee"      : [conn: "cloud",     name: "ecobee"],
    "google"      : [conn: "cloud",     name: "Google Home"],
    "mobile"      : [conn: "cloud",     name: "Mobile App"],
    // 5
    "govee"       : [conn: "lan_direct", name: "Govee"],
    "sonos"       : [conn: "lan_direct", name: "Sonos"],
    "alexa"       : [conn: "cloud", name: "Amazon Echo Skill"],
    // 4
    "kasa"        : [conn: "lan_direct", name: "Kasa"],
    "lifx"        : [conn: "lan_direct", name: "LIFX"],
    "wled"        : [conn: "lan_direct", name: "WLED"],
    "bond"        : [conn: "lan_bridge", name: "Bond"],
    // 3
    "wiz"         : [conn: "lan_direct", name: "WiZ"],
]


// File names for persistence
@Field static final String SNAPSHOTS_FILE = "hub_diagnostics_snapshots.json"
@Field static final String CHECKPOINTS_FILE = "hub_diagnostics_checkpoints.json"
@Field static final String PERFORMANCE_COMPARISON_FILE = "hub_diagnostics_performance_comparison.json"

@Field static final String IMPORT_URL_APP = "https://raw.githubusercontent.com/hubitrep/hubitat/refs/heads/main/HubDiagnostics/HubDiagnostics.groovy"
@Field static final String IMPORT_URL_WEB = "https://raw.githubusercontent.com/hubitrep/hubitat/refs/heads/main/HubDiagnostics/hub_diagnostics_ui.html"

// =====================================================================================
// AUDIT_REPORT_CSS — extracted from renderAuditHtml in v5.17.0 (R-3 #3).
// The audit report (a self-contained HTML file written to FileManager) needs its own
// inline CSS because it's loaded as a static document, not through serveUI. This block
// is a deliberately-curated subset of the SPA's <style> block in hub_diagnostics_ui.html
// — same color tokens, same card/metric/table/badge primitives — plus audit-only styles
// (TOC, summary.card-h disclosure triangle, .muted/.warn/.crit text helpers).
//
// SOURCE OF TRUTH: hub_diagnostics_ui.html lines 8-88 (the SPA <style> block).
// When you change visual primitives here, mirror the change in the SPA <style> block,
// and vice versa. Future R-3 work could deduplicate these by injecting via serveUI
// substitution, but that breaks workbench/offline modes — left as documented future
// option in CODE_REVIEW.md.
// =====================================================================================
@Field static final String AUDIT_REPORT_CSS = """\
:root{--primary:#1A77C9;--ok:#388e3c;--warn:#ff9800;--crit:#d32f2f;--bg:#f5f5f5;--card:#fff;--border:#ddd;--text:#333;--muted:#777;--alt:#f9f9f9;--hover:#e3f2fd}
*{box-sizing:border-box;margin:0;padding:0}
body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;background:var(--bg);color:var(--text);font-size:14px;padding:16px}
a{color:var(--primary);text-decoration:none}a:hover{text-decoration:underline}
.hdr{background:var(--primary);color:#fff;padding:10px 20px;border-radius:8px 8px 0 0;display:flex;align-items:center;gap:12px;flex-wrap:wrap}
.hdr h1{font-size:17px;font-weight:600}.hdr .meta{font-size:12px;opacity:.85;margin-left:auto}
.toc{background:var(--card);border-radius:0 0 8px 8px;padding:14px;margin-bottom:14px;box-shadow:0 1px 3px rgba(0,0,0,.08)}
.toc-l{color:var(--muted);font-size:11px;text-transform:uppercase;font-weight:600;margin-bottom:6px}
.toc-grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(220px,1fr));gap:4px;font-size:13px}
.card{background:var(--card);border-radius:8px;box-shadow:0 1px 3px rgba(0,0,0,.08);margin-bottom:14px;overflow:hidden}
.card-h{padding:10px 14px;font-size:13px;font-weight:600;border-bottom:1px solid var(--border);background:var(--alt)}
.card-b{padding:14px}
summary.card-h{cursor:pointer;user-select:none}
summary.card-h::-webkit-details-marker{color:var(--muted)}
summary.card-h::marker{color:var(--muted)}
summary.card-h:hover{background:#f0f0f0}
details:not([open])>summary.card-h{border-bottom:0}
.metrics{display:grid;grid-template-columns:repeat(auto-fill,minmax(170px,1fr));gap:10px}
.m{padding:10px;border-radius:6px;background:var(--alt)}
.m-l{font-size:11px;color:var(--muted);margin-bottom:2px}.m-v{font-size:18px;font-weight:600}
.tbl-wrap{overflow-x:auto}
table{width:100%;border-collapse:collapse;font-size:13px}
th{background:var(--primary);color:#fff;padding:7px 9px;text-align:left;white-space:nowrap;user-select:none}
td{padding:6px 9px;border-bottom:1px solid #eee;vertical-align:top}
tbody tr:nth-child(even){background:var(--alt)}
tbody tr:hover{background:var(--hover)}
table[data-sortable] th{cursor:pointer}
table[data-sortable] th:hover{background:#1565a7}
table[data-sortable] th .arr{font-size:9px;margin-left:3px;opacity:.6}
table[data-sortable] th[data-dir] .arr{opacity:1}
.filter input{padding:7px 11px;border:1px solid var(--border);border-radius:4px;font-size:13px;width:280px;max-width:100%;margin-bottom:10px}
.badge{display:inline-block;padding:1px 7px;border-radius:10px;font-size:11px;font-weight:600}
.b-builtin{background:#e8eaf6;color:#3949ab}.b-community{background:#fce4ec;color:#c62828}
.b-warn{background:#fff3e0;color:var(--warn)}.b-crit{background:#ffebee;color:var(--crit)}
.muted{color:var(--muted)}.warn{color:var(--warn)}.crit{color:var(--crit)}
"""

@Field static final String DEVICE_FULL_JSON_PATH = "/device/fullJson/"

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
    path('/api/code')             { action: [GET: 'apiCode'] }
    path('/api/network')          { action: [GET: 'apiNetwork'] }
    path('/api/health')           { action: [GET: 'apiHealth'] }
    path('/api/health/history')   { action: [GET: 'apiHealthHistory'] }
    path('/api/live')             { action: [GET: 'apiLive'] }
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
    path('/api/settings')             { action: [GET: 'apiGetSettings', POST: 'apiUpdateSettings'] }
    path('/api/cache/clear')          { action: [POST: 'apiClearCache'] }

    // Device Usage Audit
    path('/api/audit/start')   { action: [POST: 'apiAuditStart'] }
    path('/api/audit/status')  { action: [GET:  'apiAuditStatus'] }
    path('/api/audit/list')    { action: [GET:  'apiAuditList'] }
    path('/api/audit/delete')  { action: [POST: 'apiAuditDelete'] }

    // Network tests (v5.9.0)
    path('/api/network/test')  { action: [POST: 'apiNetworkTest'] }

    // Zigbee channel scan (v5.12.0)
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

        section("Documentation") {
            href url: "https://github.com/hubitrep/hubitat/tree/main/HubDiagnostics", title: "Documentation & README",
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
        section("Automatic Config Snapshots") {
            input "autoSnapshot", "bool", title: "Enable automatic config snapshots", defaultValue: false, submitOnChange: true
            if (autoSnapshot) {
                input "snapshotInterval", "number", title: "Config snapshot interval (days)",
                    defaultValue: 1, range: "1..30", required: true
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

        section("Alert Thresholds") {
            paragraph "Adjust these to match your hub model and environment. Defaults suit most C-8/C-8 Pro setups."
            input "warnMemMb",   "number",  title: "Free memory warning (MB)",    defaultValue: DEFAULT_WARN_MEM_MB,   range: "10..2000", required: true
            input "critMemMb",   "number",  title: "Free memory critical (MB)",   defaultValue: DEFAULT_CRIT_MEM_MB,   range: "10..2000", required: true
            input "warnCpuLoad", "decimal", title: "CPU load average warning",    defaultValue: DEFAULT_WARN_CPU_LOAD, range: "0.1..32",  required: true
            input "critCpuLoad", "decimal", title: "CPU load average critical",   defaultValue: DEFAULT_CRIT_CPU_LOAD, range: "0.1..32",  required: true
            input "warnTempC",   "number",  title: "Hub temperature warning (°C)", defaultValue: DEFAULT_WARN_TEMP_C,  range: "20..100",  required: true
            input "critTempC",   "number",  title: "Hub temperature critical (°C)", defaultValue: DEFAULT_CRIT_TEMP_C, range: "20..100",  required: true
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
        def m = text =~ /APP_VERSION\s*=\s*"([^"]+)"/
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
 * twice (getHubInfo + fetchHubAlerts via getStructuredAlerts) and fetched system resources twice
 * (once directly, once via getStructuredAlerts fallback). With the shared cache populated, both
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

Map apiDashboard() {
    long start = now()
    Map data = getDashboardData(buildSharedCache(false))
    long elapsed = now() - start
    logDebug "apiDashboard completed in ${elapsed}ms"
    recordApiTiming("dashboard", elapsed)
    return jsonResponse(data)
}

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

Map apiCode() {
    long start = now()
    Map data = [
        appTypes: fetchUserAppTypes(),
        driverTypes: fetchUserDriverTypes(),
        bundles: fetchUserBundles(),
        libraries: fetchUserLibraries(),
        hubVariables: fetchHubVariables()
    ]
    long elapsed = now() - start
    logDebug "apiCode completed in ${elapsed}ms"
    recordApiTiming("code", elapsed)
    return jsonResponse(data)
}

Map apiNetwork() {
    long start = now()
    // Network tab needs hubData (for fetchSecurityInfo's cloudController flag); rest is fetched by analyzeNetwork
    Map shared = [:]
    Map hubDataWrap = hubMapRequest(HUB_DATA_PATH, "hub data (shared)", 10)
    shared.hubData = hubDataWrap.ok ? hubDataWrap.data : null
    Map data = getNetworkData(shared)
    long elapsed = now() - start
    logDebug "apiNetwork completed in ${elapsed}ms"
    recordApiTiming("network", elapsed)
    return jsonResponse(data)
}

Map apiHealth() {
    long start = now()
    Map data = getHealthData(buildSharedCache(false))
    long elapsed = now() - start
    logDebug "apiHealth completed in ${elapsed}ms"
    recordApiTiming("health", elapsed)
    return jsonResponse(data)
}

Map apiHealthHistory() {
    List memHistory = fetchMemoryHistory()
    return jsonResponse([dataPoints: memHistory ?: []])
}

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

Map apiPerformance() {
    long start = now()
    Map data = getPerformanceData()
    long elapsed = now() - start
    logDebug "apiPerformance completed in ${elapsed}ms"
    recordApiTiming("performance", elapsed)
    return jsonResponse(data)
}

Map apiNetworkTest() {
    String type = params.type
    String ip = params.ip
    if (!type) return jsonResponse([success: false, error: "Missing 'type' parameter"])
    long start = now()
    String result = runNetworkTest(type, ip)
    long elapsed = now() - start
    boolean ok = result != null && !result.startsWith("Error:")
    logDebug "apiNetworkTest(${type}${ip ? ', ' + ip : ''}) completed in ${elapsed}ms"
    return jsonResponse([success: ok, type: type, ip: ip, output: result, elapsedMs: elapsed])
}

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
        if (!baseline?.isInteger()) return jsonResponse([success: false, error: "Invalid baseline index"])
        int bIdx = baseline.toInteger()
        if (bIdx < 0 || bIdx >= checkpoints.size()) return jsonResponse([success: false, error: "Invalid baseline index"])
        Map bCp = checkpoints[bIdx]
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
        Map zwWrap = hubMapRequest(ZWAVE_DETAILS_PATH, "Z-Wave details", 20)
        Map zwaveData = zwWrap.ok ? zwWrap.data : [:]
        Map zbWrap = hubMapRequest(ZIGBEE_DETAILS_PATH, "Zigbee details", 20)
        Map zigbeeData = zbWrap.ok ? zbWrap.data : [:]
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
        if (cIdx < 0 || cIdx >= checkpoints.size()) return jsonResponse([success: false, error: "Invalid checkpoint index"])
        Map cCp = checkpoints[cIdx]
        checkpointStats = cCp.stats + [resources: cCp.resources, radioStats: cCp.radioStats, timestampMs: cCp.timestampMs, temperature: cCp.temperature, databaseSize: cCp.databaseSize]
        checkpointLabel = cCp.timestamp
    }

    // Ensure uptimeSeconds is present — needed by the JS diffStats elapsedMs fallback for startup comparisons
    if (!checkpointStats.uptimeSeconds && checkpointStats.uptime) {
        checkpointStats = checkpointStats + [uptimeSeconds: parseUptime(checkpointStats.uptime as String)]
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

Map apiSnapshotDiff() {
    String olderStr = params.older ?: "-1"
    if (!olderStr.isInteger()) return jsonResponse([error: "Invalid older snapshot index"])
    int olderIdx = olderStr.toInteger()
    boolean newerIsNow = params.newer == "now"
    String newerStr = params.newer ?: "-1"
    if (!newerIsNow && !newerStr.isInteger()) return jsonResponse([error: "Invalid newer snapshot index"])
    int newerIdx = newerIsNow ? -1 : newerStr.toInteger()

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

    // Migrate old-format snapshots
    if (older.devices) older.devices = migrateSnapshotDevices(older.devices)
    if (newer.devices) newer.devices = migrateSnapshotDevices(newer.devices)

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
        [id: it.id, name: it.name, connectionType: CONN_DISPLAY[it.connectionType] ?: it.connectionType, integration: it.integration]
    }
    List removed = olderDevices.findAll { !newerIds.contains(it.id) }.collect {
        [id: it.id, name: it.name, connectionType: CONN_DISPLAY[it.connectionType] ?: it.connectionType, integration: it.integration]
    }
    Map olderById = olderDevices.collectEntries { [(it.id): it] }
    List changed = newerDevices.findAll { olderIds.contains(it.id) }.findAll { Map dev ->
        Map old = olderById[dev.id]
        old && (old.status != dev.status || old.connectionType != dev.connectionType || old.integration != dev.integration)
    }.collect { Map dev ->
        Map old = olderById[dev.id]
        Map change = [id: dev.id, name: dev.name, changes: []]
        if (old.status != dev.status) change.changes << [field: "status", from: old.status, to: dev.status]
        if (old.connectionType != dev.connectionType) change.changes << [field: "connectionType", from: CONN_DISPLAY[old.connectionType] ?: old.connectionType, to: CONN_DISPLAY[dev.connectionType] ?: dev.connectionType]
        if (old.integration != dev.integration) change.changes << [field: "integration", from: old.integration, to: dev.integration]
        return change
    }

    // Connection type distribution changes
    Map olderConn = older.devices?.byConnectionType ?: [:]
    Map newerConn = newer.devices?.byConnectionType ?: [:]
    Set allConnKeys = (olderConn.keySet() + newerConn.keySet())
    List connectionTypeChanges = allConnKeys.findAll { (olderConn[it] ?: 0) != (newerConn[it] ?: 0) }.collect { String key ->
        [connectionType: CONN_DISPLAY[key] ?: key, from: olderConn[key] ?: 0, to: newerConn[key] ?: 0]
    }

    // Integration distribution changes
    Map olderInteg = older.devices?.byIntegration ?: [:]
    Map newerInteg = newer.devices?.byIntegration ?: [:]
    Set allIntegKeys = (olderInteg.keySet() + newerInteg.keySet())
    List integrationChanges = allIntegKeys.findAll { (olderInteg[it] ?: 0) != (newerInteg[it] ?: 0) }.collect { String key ->
        [integration: key, from: olderInteg[key] ?: 0, to: newerInteg[key] ?: 0]
    }

    // App list diff
    List olderUserApps = (List) (older.apps?.userAppsList ?: [])
    List newerUserApps = (List) (newer.apps?.userAppsList ?: [])
    Set olderAppIds = olderUserApps.collect { safeToString(((Map) it).id, "") } as Set
    Set newerAppIds = newerUserApps.collect { safeToString(((Map) it).id, "") } as Set
    List appsAdded   = newerUserApps.findAll { !olderAppIds.contains(safeToString(((Map) it).id, "")) }
                                    .collect { Map a -> [id: a.id, name: a.name, label: a.label] }
    List appsRemoved = olderUserApps.findAll { !newerAppIds.contains(safeToString(((Map) it).id, "")) }
                                    .collect { Map a -> [id: a.id, name: a.name, label: a.label] }

    // App disabled status changes (only for apps present in both snapshots)
    Map olderAppsById = olderUserApps.collectEntries { Map a -> [(safeToString(a.id, "")): a] }
    List appsChanged = newerUserApps.findAll { Map a ->
        String aid = safeToString(a.id, "")
        Map old = (Map) olderAppsById[aid]
        old && ((old.disabled ?: false) != (a.disabled ?: false))
    }.collect { Map a -> [id: a.id, name: a.name, label: a.label, disabled: a.disabled ?: false] }

    // Network configuration diff
    Map olderNet = older.network ?: [:]
    Map newerNet = newer.network ?: [:]
    Map networkChanges = [:]
    def olderZbCh  = olderNet.zigbee?.channel
    def newerZbCh  = newerNet.zigbee?.channel
    if (olderZbCh != newerZbCh) networkChanges.zigbeeChannel = [from: olderZbCh, to: newerZbCh]
    def olderZwReg = olderNet.zwave?.region
    def newerZwReg = newerNet.zwave?.region
    if (olderZwReg != newerZwReg) networkChanges.zwaveRegion = [from: olderZwReg, to: newerZwReg]
    def olderMatter = olderNet.matter?.enabled
    def newerMatter = newerNet.matter?.enabled
    if (olderMatter != newerMatter) networkChanges.matterEnabled = [from: olderMatter, to: newerMatter]
    List olderPeers = (olderNet.hubMesh?.hubList ?: []).collect { it.name ?: it.ipAddress }
    List newerPeers = (newerNet.hubMesh?.hubList ?: []).collect { it.name ?: it.ipAddress }
    Set  olderPeerSet = olderPeers.toSet()
    Set  newerPeerSet = newerPeers.toSet()
    List peersAdded   = newerPeers.findAll { !olderPeerSet.contains(it) }
    List peersRemoved = olderPeers.findAll { !newerPeerSet.contains(it) }
    if (peersAdded || peersRemoved) networkChanges.hubMeshPeers = [added: peersAdded, removed: peersRemoved]

    // Storage diff
    Map olderStorage = (Map) (older.storage ?: [:])
    Map newerStorage = (Map) (newer.storage ?: [:])
    Map storageChanges = [:]
    if (olderStorage.fileCount != null && newerStorage.fileCount != null && olderStorage.fileCount != newerStorage.fileCount) {
        storageChanges.fileCount = [from: olderStorage.fileCount, to: newerStorage.fileCount]
    }
    if (olderStorage.freeSpace != null && newerStorage.freeSpace != null && olderStorage.freeSpace != newerStorage.freeSpace) {
        storageChanges.freeSpace = [from: olderStorage.freeSpace, to: newerStorage.freeSpace]
    }

    // Backups diff (v5.13.0) — only when both snapshots have backup data
    Map backupsChanges = [:]
    Map oBk = (Map) older.backups
    Map nBk = (Map) newer.backups
    if (oBk && nBk) {
        Map oLo = (Map) (oBk.local ?: [:])
        Map nLo = (Map) (nBk.local ?: [:])
        if ((oLo.count ?: 0) != (nLo.count ?: 0)) backupsChanges.localCount = [from: oLo.count ?: 0, to: nLo.count ?: 0]
        if (oLo.latestCreateTime != nLo.latestCreateTime && (oLo.latestCreateTime || nLo.latestCreateTime)) {
            backupsChanges.latestLocal = [from: oLo.latestCreateTime, to: nLo.latestCreateTime]
        }
        Map oCl = (Map) (oBk.cloud ?: [:])
        Map nCl = (Map) (nBk.cloud ?: [:])
        if ((oCl.thisHubCount ?: 0) != (nCl.thisHubCount ?: 0)) backupsChanges.cloudThisHubCount = [from: oCl.thisHubCount ?: 0, to: nCl.thisHubCount ?: 0]
        if ((oCl.hasCloudBackupEntitlements ?: false) != (nCl.hasCloudBackupEntitlements ?: false)) backupsChanges.cloudBackupEntitlement = [from: oCl.hasCloudBackupEntitlements ?: false, to: nCl.hasCloudBackupEntitlements ?: false]
        if ((oCl.hasCloudRestoreEntitlements ?: false) != (nCl.hasCloudRestoreEntitlements ?: false)) backupsChanges.cloudRestoreEntitlement = [from: oCl.hasCloudRestoreEntitlements ?: false, to: nCl.hasCloudRestoreEntitlements ?: false]
    }

    // Security diff (v5.13.0)
    Map securityChanges = [:]
    Map oSec = (Map) older.security
    Map nSec = (Map) newer.security
    if (oSec && nSec) {
        boolean oLim = (oSec.limitedAccess as Map)?.enabled == true
        boolean nLim = (nSec.limitedAccess as Map)?.enabled == true
        if (oLim != nLim) securityChanges.limitedAccess = [from: oLim, to: nLim]
        List oAddrs = ((oSec.limitedAccess as Map)?.addresses as List) ?: []
        List nAddrs = ((nSec.limitedAccess as Map)?.addresses as List) ?: []
        if (oAddrs.toSet() != nAddrs.toSet()) securityChanges.limitedAddresses = [from: oAddrs, to: nAddrs]
        List oSub = (oSec.allowedSubnets as List) ?: []
        List nSub = (nSec.allowedSubnets as List) ?: []
        if (oSub.toSet() != nSub.toSet()) securityChanges.allowedSubnets = [from: oSub, to: nSub]
        if (oSec.dnsFallback != nSec.dnsFallback) securityChanges.dnsFallback = [from: oSec.dnsFallback, to: nSec.dnsFallback]
        if (oSec.cloudController != nSec.cloudController && (oSec.cloudController != null || nSec.cloudController != null)) {
            securityChanges.cloudController = [from: oSec.cloudController, to: nSec.cloudController]
        }
    }

    // Network settings diff (v5.13.0) — only when both snapshots actually captured the field
    // (older snapshots predating v5.13.0 don't have these keys at all; we don't want to surface
    // "schema added the field" as a config change).
    if (((Map) older).containsKey('ntpServer') && ((Map) newer).containsKey('ntpServer') && older.ntpServer != newer.ntpServer) {
        if (networkChanges == null) networkChanges = [:]
        networkChanges.ntpServer = [from: older.ntpServer, to: newer.ntpServer]
    }
    if (((Map) older).containsKey('loadThreshold') && ((Map) newer).containsKey('loadThreshold') && older.loadThreshold != newer.loadThreshold) {
        if (networkChanges == null) networkChanges = [:]
        networkChanges.loadThreshold = [from: older.loadThreshold, to: newer.loadThreshold]
    }

    // Code inventory diff (v5.13.0): bundles, libraries, hub variables — by id (or name for hub vars)
    Map codeChanges = [:]
    Map oCode = (Map) older.code
    Map nCode = (Map) newer.code
    if (oCode && nCode) {
        // Bundles — keyed by id
        List oBund = (oCode.bundles as List) ?: []
        List nBund = (nCode.bundles as List) ?: []
        Set oBundIds = oBund.collect { (it as Map).id } as Set
        Set nBundIds = nBund.collect { (it as Map).id } as Set
        List bundlesAdded   = nBund.findAll { !oBundIds.contains((it as Map).id) }
        List bundlesRemoved = oBund.findAll { !nBundIds.contains((it as Map).id) }
        if (bundlesAdded || bundlesRemoved) codeChanges.bundles = [added: bundlesAdded, removed: bundlesRemoved]

        // Libraries — keyed by id; also detect version changes
        List oLib = (oCode.libraries as List) ?: []
        List nLib = (nCode.libraries as List) ?: []
        Set oLibIds = oLib.collect { (it as Map).id } as Set
        Set nLibIds = nLib.collect { (it as Map).id } as Set
        Map oLibById = oLib.collectEntries { Map l -> [(l.id): l] }
        List libsAdded   = nLib.findAll { !oLibIds.contains((it as Map).id) }
        List libsRemoved = oLib.findAll { !nLibIds.contains((it as Map).id) }
        List libsVersionChanged = nLib.findAll { Map l ->
            Map o = (Map) oLibById[l.id]
            o && o.version != l.version
        }.collect { Map l -> [id: l.id, name: l.name, namespace: l.namespace, from: ((Map) oLibById[l.id]).version, to: l.version] }
        if (libsAdded || libsRemoved || libsVersionChanged) codeChanges.libraries = [added: libsAdded, removed: libsRemoved, versionChanged: libsVersionChanged]

        // Hub variables — keyed by name (no stable id); also detect type changes
        List oHv = (oCode.hubVariables as List) ?: []
        List nHv = (nCode.hubVariables as List) ?: []
        Set oHvNames = oHv.collect { (it as Map).name } as Set
        Set nHvNames = nHv.collect { (it as Map).name } as Set
        Map oHvByName = oHv.collectEntries { Map v -> [(v.name): v] }
        List hvAdded   = nHv.findAll { !oHvNames.contains((it as Map).name) }
        List hvRemoved = oHv.findAll { !nHvNames.contains((it as Map).name) }
        List hvTypeChanged = nHv.findAll { Map v ->
            Map o = (Map) oHvByName[v.name]
            o && o.type != v.type
        }.collect { Map v -> [name: v.name, from: ((Map) oHvByName[v.name]).type, to: v.type] }
        if (hvAdded || hvRemoved || hvTypeChanged) codeChanges.hubVariables = [added: hvAdded, removed: hvRemoved, typeChanged: hvTypeChanged]
    }

    return jsonResponse([
        older: [timestamp: older.timestamp, firmware: older.hubInfo?.firmware, storage: older.storage],
        newer: [timestamp: newer.timestamp, firmware: newer.hubInfo?.firmware, storage: newer.storage],
        deviceChanges: [
            olderTotal: older.devices?.totalDevices ?: 0,
            newerTotal: newer.devices?.totalDevices ?: 0,
            added: added, removed: removed, changed: changed
        ],
        connectionTypeChanges: connectionTypeChanges,
        integrationChanges: integrationChanges,
        appChanges: [
            olderTotal: older.apps?.totalApps ?: 0,
            newerTotal: newer.apps?.totalApps ?: 0,
            added: appsAdded,
            removed: appsRemoved,
            changed: appsChanged
        ],
        networkChanges: networkChanges ?: null,
        storageChanges: storageChanges ?: null,
        backupsChanges: backupsChanges ?: null,
        securityChanges: securityChanges ?: null,
        codeChanges: codeChanges ?: null
    ])
}

Map apiCreateSnapshot() {
    createSnapshot()
    List snapshots = loadSnapshots()
    return jsonResponse([success: true, snapshotCount: snapshots?.size() ?: 0])
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
    if (!createCheckpoint()) return jsonResponse([success: false, error: "Failed to capture runtime stats"])
    List checkpoints = loadCheckpoints()
    return jsonResponse([success: true, checkpointCount: checkpoints?.size() ?: 0])
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

Map apiGenerateReport() {
    logInfo "Generating report..."
    String timestamp = new Date().format("yyyy-MM-dd HH:mm:ss")
    List memHistory = fetchMemoryHistory()

    Map statsWrap = hubMapRequest(RUNTIME_STATS_PATH, "runtime stats")
    Map shared = [
        network:      analyzeNetwork(),
        runtimeStats: statsWrap.ok ? statsWrap.data : null,
        resources:    fetchSystemResources(),
        temperature:  fetchTemperature(),
        hubAlerts:    fetchHubAlerts(),
        databaseSize: fetchDatabaseSize()
    ]

    Map reportData = [
        _generated: timestamp,
        dashboard: getDashboardData(shared),
        devices: getDevicesData(),
        apps: getAppsData(),
        network: getNetworkData(shared),
        health: getHealthData(shared),
        "health/history": [dataPoints: memHistory ?: []],
        performance: getPerformanceData(shared),
        snapshots: getSnapshotsData(),
        reports: [lastReport: null, reports: []]
    ]

    String html = loadUITemplate()
    if (!html) return jsonResponse([success: false, error: "SPA template not found in File Manager"])

    String dataJson = JsonOutput.toJson(reportData).replace("</script>", "<\\/script>")
    html = html.replace("</head>", "<script type=\"application/json\" id=\"report-data\">${dataJson}</script>\n<script>window.REPORT_DATA=JSON.parse(document.getElementById('report-data').textContent)</script>\n</head>")
    html = html.replace('${access_token}', '').replace('${api_base}', '').replace('${live_refresh_sec}', '0')

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
    Map deviceStats = analyzeDevices(true)
    Map appStats = analyzeApps(true)
    Map networkData = analyzeNetwork()
    Map networkConfig = networkData.network ?: [:]
    Map zwaveRaw = networkData.zwave ?: [:]
    Map zigbeeRaw = networkData.zigbee ?: [:]
    Map hubMeshRaw = networkData.hubMesh ?: [:]
    Map matterRaw = (Map) (networkData.matter ?: [:])
    Map zwaveMesh = extractZwaveMeshQuality(zwaveRaw)
    boolean obfuscate = settings.obfuscateForumExport ?: false
    List ghostNodes = buildZwaveGhostNodes(zwaveRaw)
    Map zigbeeMesh = fetchZigbeeMeshInfo()
    String zwaveVersion = fetchZwaveVersion()
    Map statsWrap = hubMapRequest(RUNTIME_STATS_PATH, "runtime stats")
    Map stats = statsWrap.ok ? statsWrap.data : null
    Integer uptimeSeconds = stats ? parseUptime(stats.uptime as String) : null
    float uptimeMin = uptimeSeconds ? uptimeSeconds / 60.0f : 0

    // Radio message counts with rates
    List zwaveMsgCounts = extractZwaveMessageCounts(zwaveRaw)
    List zigbeeMsgCounts = extractZigbeeMessageCounts(zigbeeRaw)
    List allRadioDevices = (zwaveMsgCounts.collect { [name: it.name, deviceId: it.deviceId, msgCount: it.msgCount, integration: "Z-Wave"] } +
                            zigbeeMsgCounts.collect { [name: it.name, deviceId: it.id, msgCount: it.msgCount, integration: "Zigbee"] })

    StringBuilder md = new StringBuilder()

    // ── 1. System & Health ──
    md << "### System & Health\n"
    md << "| | |\n|---|---|\n"
    md << "| Model | ${hubInfo.hardware} |\n"
    md << "| Firmware | ${hubInfo.firmware} |\n"
    md << "| Uptime | ${stats?.uptime ?: 'N/A'} |\n"
    if (location.currentMode) md << "| Mode | ${location.currentMode} |\n"
    boolean bothActive = networkConfig.hasEthernet && networkConfig.hasWiFi
    String connType = networkConfig.hasEthernet ? "Ethernet" : (networkConfig.hasWiFi ? "WiFi" : "Unknown")
    if (bothActive) connType = "Ethernet + WiFi active"
    if (networkConfig.hasEthernet) connType += networkConfig.usingStaticIP ? " (Static)" : " (DHCP)"
    md << "| Connection | ${connType} |\n"
    if (networkConfig.lanAddr)          md << "| IP Address | ${networkConfig.lanAddr} |\n"
    if (networkConfig.staticGateway)    md << "| Gateway    | ${networkConfig.staticGateway} |\n"
    if (networkConfig.staticSubnetMask) md << "| Subnet     | ${networkConfig.staticSubnetMask} |\n"
    List dnsList = (networkConfig.dnsServers ?: []) as List
    if (dnsList)                        md << "| DNS        | ${dnsList.join(', ')} |\n"
    if (networkConfig.hasWiFi && networkConfig.wifiNetwork) md << "| WiFi SSID  | ${networkConfig.wifiNetwork} |\n"
    if (bothActive) md << "\n**Warning:** Both Ethernet and WiFi are active. This is known to cause connectivity issues. Disable WiFi when using Ethernet.\n\n"
    md << "| CPU Load (5m) | ${resources ? String.format('%.2f', resources.cpuAvg5min as float) : 'N/A'} |\n"
    md << "| Free OS Memory | ${resources ? formatMemory(resources.freeOSMemory as int) : 'N/A'} |\n"
    if (resources?.freeJavaMemory)  md << "| Free Java Heap  | ${formatMemory(resources.freeJavaMemory as int)} |\n"
    if (resources?.totalJavaMemory) md << "| Total Java Heap | ${formatMemory(resources.totalJavaMemory as int)} |\n"
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
    if (deviceStats.activeDevices)   md << "| Active | ${deviceStats.activeDevices} |\n"
    if (deviceStats.inactiveDevices) md << "| Inactive | ${deviceStats.inactiveDevices} |\n"
    if (deviceStats.disabledDevices) md << "| Disabled | ${deviceStats.disabledDevices} |\n"
    Map byConn = deviceStats.byConnectionType ?: [:]
    byConn.each { String conn, int count ->
        if (count > 0) md << "| ${CONN_DISPLAY[conn] ?: conn} | ${count} |\n"
    }
    Map byInteg = deviceStats.byIntegration ?: [:]
    if (byInteg) {
        md << "\n**By Integration:**\n\n| Integration | Count |\n|---|---|\n"
        byInteg.sort { a, b -> b.value <=> a.value }.each { String integ, int count ->
            if (count > 0) md << "| ${integ} | ${count} |\n"
        }
    }
    List lowBattery = (deviceStats.lowBatteryDevices ?: [])
    if (lowBattery) {
        md << "\n**Low Battery:** " + lowBattery.collect { "${obfuscate ? (it.type ?: 'Device') : it.name} (${it.battery}%)" }.join(", ") + "\n"
    }
    List inactiveDevices = (deviceStats.allDevices ?: []).findAll { it.status == "Inactive" }
    if (inactiveDevices) {
        md << "\n**Inactive Devices (${inactiveDevices.size()}):** "
        md << inactiveDevices.take(15).collect { obfuscate ? (it.type ?: 'Device') : it.name }.join(", ")
        if (inactiveDevices.size() > 15) md << " … (+${inactiveDevices.size() - 15} more)"
        md << "\n"
    }

    // ── 3. App Inventory ──
    md << "\n### Apps\n"
    md << "- **Total:** ${appStats.totalApps} (Built-in: ${appStats.builtInApps}, User: ${appStats.userApps})\n"
    List userAppsList = (appStats.userAppsList ?: []) as List
    if (userAppsList) {
        // Deduplicate by type name, show count when multiple instances
        Map appCounts = [:]
        userAppsList.each { Map a -> appCounts[a.name ?: a.label ?: "Unknown"] = (appCounts[a.name ?: a.label ?: "Unknown"] ?: 0) + 1 }
        md << "\n**Installed User Apps:**\n"
        appCounts.sort { it.key }.each { String name, int count ->
            md << "- ${obfuscate ? 'User App' : name}${count > 1 ? " (×${count})" : ''}\n"
        }
    }
    Map builtInInstances = (appStats.builtInInstances ?: [:]) as Map
    if (builtInInstances) {
        md << "\n**Installed Built-in Apps:**\n"
        builtInInstances.sort { it.key }.each { String name, int count ->
            md << "- ${name}${count > 1 ? " (×${count})" : ''}\n"
        }
    }

    // ── 4. Z-Wave Network ──
    if (zwaveRaw) {
        md << "\n### Z-Wave\n"
        md << "- **Enabled:** ${zwaveRaw.enabled ? 'Yes' : 'No'}, **Healthy:** ${zwaveRaw.healthy ? 'Yes' : 'No'}\n"
        md << "- **Version:** ${zwaveVersion ?: 'N/A'}, **Region:** ${zwaveRaw.region ?: 'N/A'}\n"
        if (zwaveRaw.zwaveJS != null) md << "- **Z-Wave JS:** ${zwaveRaw.zwaveJS ? 'Yes' : 'No'}\n"
        md << "- **Nodes:** ${(zwaveRaw.zwDevices ?: [:]).size()}\n"
        if (zwaveMesh) {
            md << "- **Avg PER:** ${String.format('%.1f', (zwaveMesh.avgPer ?: 0) as float)}%"
            if (zwaveMesh.avgRssi != null) md << ", **Avg RSSI:** ${zwaveMesh.avgRssi} dBm"
            md << ", **Route Changes:** ${zwaveMesh.totalRouteChanges ?: 0}\n"
        }
        // Ghost nodes
        if (ghostNodes) {
            md << "\n**Ghost Nodes (${ghostNodes.size()}):**\n"
            ghostNodes.each { Map g ->
                String gName = obfuscate ? (g.type ?: 'Z-Wave Node') : g.name
                String gSig  = g.signals ? " [${(g.signals as List).join(', ')}]" : ""
                md << "- Node ${g.id}: ${gName}${gSig}\n"
            }
        }
        // Problem nodes
        List problemNodes = (zwaveMesh?.nodes ?: []).findAll { Map n -> n.state != "OK" || (n.per ?: 0) > 1 }
        if (problemNodes) {
            md << "\n**Problem Nodes (${problemNodes.size()}):**\n"
            problemNodes.each { Map n ->
                List issues = []
                if (n.state != "OK") issues << "State: ${n.state}"
                if ((n.per ?: 0) > 1) issues << "PER: ${n.per}%"
                md << "- ${obfuscate ? (n.zwaveType ?: 'Z-Wave Node') : n.name} (Node ${n.nodeId}): ${issues.join(', ')}\n"
            }
        }
        // S0 flagged
        List s0Flagged = (zwaveMesh?.nodes ?: []).findAll { it.s0Flag }
        if (s0Flagged) {
            md << "\n**S0 on non-security devices:** ${s0Flagged.collect { obfuscate ? (it.zwaveType ?: 'Z-Wave Node') : it.name }.join(', ')}\n"
        }
        // Isolated nodes (0 neighbors, non-failed)
        List isolatedNodes = (zwaveMesh?.nodes ?: []).findAll { Map n -> (n.neighbors ?: 0) == 0 && n.state != "FAILED" }
        if (isolatedNodes) {
            md << "\n**Isolated Nodes (0 neighbors, ${isolatedNodes.size()}):**\n"
            isolatedNodes.each { Map n ->
                String label = obfuscate ? (n.zwaveType ?: "Node ${n.nodeId}") : (n.name ?: "Node ${n.nodeId}")
                md << "- Node ${n.nodeId}: ${label}\n"
            }
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
                String nodeName = obfuscate ? (n.zwaveType ?: "Node ${n.nodeId}") : (n.name ?: "Node ${n.nodeId}")
                md << "| ${n.nodeId} | ${nodeName} | ${n.security ?: 'None'} | ${rttStr} | ${n.per}% | ${n.rssiStr ?: '\u2014'} | ${n.route ?: '\u2014'} | ${rateStr} | ${drvStr} |\n"
            }
        }
    }

    // ── 5. Zigbee Network ──
    if (zigbeeRaw && zigbeeRaw.enabled) {
        int totalZb = (zigbeeRaw.devices ?: []).size()
        md << "\n### Zigbee\n"
        md << "- **Healthy:** ${zigbeeRaw.healthy ? 'Yes' : 'No'}"
        if (zigbeeRaw.networkState) md << ", **State:** ${zigbeeRaw.networkState}"
        md << "\n"
        md << "- **Channel:** ${zigbeeRaw.channel ?: 'N/A'}"
        if (zigbeeRaw.channel && ![15, 20, 25].contains(zigbeeRaw.channel)) md << " (not on recommended 15/20/25)"
        md << "\n"
        if (zigbeeRaw.powerLevel != null) md << "- **Power Level:** ${zigbeeRaw.powerLevel}\n"
        md << "- **Devices:** ${totalZb}\n"
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
    }

    // ── 5b. Matter ──
    if (matterRaw && matterRaw.enabled) {
        md << "\n### Matter\n"
        md << "- **Enabled:** Yes, **Installed:** ${matterRaw.installed ? 'Yes' : 'No'}\n"
        int matterCount = (matterRaw.devices ?: []).size()
        if (matterCount > 0) md << "- **Devices:** ${matterCount}\n"
        if (matterRaw.networkState) md << "- **Network State:** ${matterRaw.networkState}\n"
        if (matterRaw.fabricId)     md << "- **Fabric:** ${matterRaw.fabricId}\n"
    }

    // ── 6. Hub Mesh ──
    if (hubMeshRaw && hubMeshRaw.hubList) {
        List peers = hubMeshRaw.hubList as List
        if (peers) {
            md << "\n### Hub Mesh\n"
            md << "| Hub | IP | Status | Devices | Vars |\n|---|---|---|---:|---:|\n"
            peers.each { Map hub ->
                String status = hub.offline ? "Offline" : "Online"
                md << "| ${hub.name} | ${hub.ipAddress} | ${status} | ${hub.deviceIds?.size() ?: 0} | ${hub.hubVarNames?.size() ?: 0} |\n"
            }
        }
    }

    // ── 7. Performance ──
    md << "\n### Performance\n"
    if (stats?.totalDevicesRuntime) md << "- **Device Runtime:** ${stats.totalDevicesRuntime}"
    if (stats?.devicePct) md << " (${stats.devicePct} busy)"
    if (stats?.totalDevicesRuntime) md << "\n"
    if (stats?.totalAppsRuntime) md << "- **App Runtime:** ${stats.totalAppsRuntime}"
    if (stats?.appPct) md << " (${stats.appPct} busy)"
    if (stats?.totalAppsRuntime) md << "\n"

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
                String aName = obfuscate ? (a.type ?: a.name) : a.name
                if (pct > 0) md << "| ${aName} | ${String.format('%.3f', pct)}% | ${a.count ?: 0} | ${String.format('%.1f', (a.average ?: 0) as float)} |\n"
            }
        }
    }

    // Top talkers by message rate
    if (allRadioDevices && uptimeMin > 0) {
        List topTalkers = allRadioDevices.sort { -it.msgCount }.take(5)
        md << "\n**Top Talkers:**\n"
        md << "| Device | Integration | Msgs/min | Total Msgs |\n|---|---|---:|---:|\n"
        topTalkers.each { Map t ->
            String rate = String.format('%.1f', t.msgCount / uptimeMin)
            String tName = obfuscate ? (t.type ?: t.integration ?: "Device") : t.name
            md << "| ${tName} | ${t.integration} | ${rate} | ${t.msgCount} |\n"
        }
        // Spammy alerts (>= chattyDeviceThreshold)
        int chattyT = (settings.chattyDeviceThreshold ?: 10) as int
        List spammy = allRadioDevices.findAll { it.msgCount / uptimeMin >= chattyT }
        if (spammy) {
            md << "\n**Elevated message rate (\u2265${chattyT}/min):** ${spammy.collect { "${obfuscate ? (it.type ?: it.integration ?: 'Device') : it.name} (${String.format('%.1f', it.msgCount / uptimeMin)}/min)" }.join(', ')}\n"
        }
    }

    md << "\n---\n*Generated by Hub Diagnostics v${APP_VERSION}*\n"

    long elapsed = now() - start
    recordApiTiming("export/forum", elapsed)
    return jsonResponse([success: true, markdown: md.toString()])
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
        warnTempC:             (settings.warnTempC   ?: DEFAULT_WARN_TEMP_C)   as int,
        critTempC:             (settings.critTempC   ?: DEFAULT_CRIT_TEMP_C)   as int,
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
                        "chattyDeviceThreshold", "warnMemMb", "critMemMb", "warnTempC", "critTempC",
                        "snapshotInterval", "liveRefreshSec"] as Set
    Set decimalKeys = ["warnCpuLoad", "critCpuLoad"] as Set
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
    if (reschedule) { unschedule(); initialize() }
    return jsonResponse([success: true])
}

Map apiClearCache() {
    int cleared = (state.controllerTypeCache ?: [:]).size()
    state.controllerTypeCache = [:]
    return jsonResponse([success: true, cleared: cleared])
}

// ===== DATA GATHERERS =====
// Each returns a plain Map suitable for both jsonResponse() and report embedding.

Map getDashboardData(Map shared = [:]) {
    Map deviceStats = analyzeDevices(false)
    Map appStats = analyzeApps(false)
    Map hubInfo = getHubInfo(shared.hubData as Map)
    Map resources        = (shared.resources as Map)        ?: fetchSystemResources()
    Float temperature    = (shared.temperature as Float)    ?: fetchTemperature()
    Integer databaseSize = (shared.databaseSize as Integer) ?: fetchDatabaseSize()
    return [
        hub: hubInfo, appVersion: APP_VERSION, uiVersion: getUIVersion(),
        devices: [
            total: deviceStats.totalDevices, active: deviceStats.activeDevices,
            inactive: deviceStats.inactiveDevices, disabled: deviceStats.disabledDevices,
            byConnectionType: deviceStats.byConnectionType, idsByConnectionType: deviceStats.idsByConnectionType,
            byIntegration: deviceStats.byIntegration, idsByIntegration: deviceStats.idsByIntegration,
            idsByStatus: deviceStats.idsByStatus
        ],
        apps: [total: appStats.totalApps, builtIn: appStats.builtInApps, user: appStats.userApps],
        resources: resources, temperature: temperature, databaseSize: databaseSize,
        alerts: getStructuredAlerts(shared), inactivityDays: settings.inactivityDays ?: 7,
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
        [id: dev.id, name: dev.name, battery: dev.battery]
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
        allApps: allApps, hasMenuData: hasMenuData
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
    List problemNodes = (zwaveMesh?.nodes ?: []).findAll { Map n -> n.state != "OK" || (n.per ?: 0) > 1 }.collect { Map n ->
        List issues = []
        if (n.state != "OK") issues << "State: ${n.state}"
        if ((n.per ?: 0) > 1) issues << "PER: ${n.per}%"
        [name: n.name, deviceId: n.deviceId, nodeId: n.nodeId, issues: issues.join(", ")]
    }
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
    def hub = (location.hubs && location.hubs.size() > 0) ? location.hubs[0] : null
    Map mem = systemHealth.memory ?: [:]
    return [
        hub: [name: hubInfo.name, hubId: hub?.id, hardware: hubInfo.hardware,
              firmware: hubInfo.firmware, ip: hubInfo.ip, zigbeeId: hub?.zigbeeId,
              location: location.name, mode: location.currentMode?.toString(),
              timeZone: location.timeZone?.ID],
        resources: mem ?: null, temperature: systemHealth.temperature,
        databaseSize: systemHealth.databaseSize, stateCompression: systemHealth.stateCompression,
        eventStateLimits: systemHealth.eventStateLimits, alerts: getStructuredAlerts(shared),
        storage: fetchFileManagerStats(),
        backups: fetchBackups(),
        loadThreshold: fetchExcessiveLoadThreshold(),
        cpuInfo: fetchCpuInfo()
    ]
}

Map getPerformanceData(Map shared = [:]) {
    Map stats
    if (shared.runtimeStats) { stats = (Map) shared.runtimeStats } else { Map r = hubMapRequest(RUNTIME_STATS_PATH, "runtime stats"); stats = r.ok ? r.data : null }
    Map resources  = (shared.resources as Map) ?: fetchSystemResources()
    Map zwaveData
    if (shared.network?.zwave) { zwaveData = (Map) shared.network.zwave } else { Map r = hubMapRequest(ZWAVE_DETAILS_PATH, "Z-Wave details", 20); zwaveData = r.ok ? r.data : null }
    Map zigbeeData
    if (shared.network?.zigbee) { zigbeeData = (Map) shared.network.zigbee } else { Map r = hubMapRequest(ZIGBEE_DETAILS_PATH, "Zigbee details", 20); zigbeeData = r.ok ? r.data : null }
    List zwaveMsgCounts = extractZwaveMessageCounts(zwaveData)
    List zigbeeMsgCounts = extractZigbeeMessageCounts(zigbeeData)
    Map radioStats = [zwave: zwaveMsgCounts, zigbee: zigbeeMsgCounts]

    // Top talkers: top 3 devices by message count across both radios
    List allRadioDevices = (zwaveMsgCounts.collect { [name: it.name, deviceId: it.deviceId, msgCount: it.msgCount, integration: "Z-Wave"] } +
                            zigbeeMsgCounts.collect { [name: it.name, deviceId: it.id, msgCount: it.msgCount, integration: "Zigbee"] })
    List topTalkers = allRadioDevices.sort { -it.msgCount }.take(3)

    // Enrich appStats with source labels (community/builtin/platform)
    Map appSourceById = [:]
    Map appsListWrap = hubMapRequest(APPS_LIST_PATH, "apps list")
    Map appsListResp = appsListWrap.ok ? appsListWrap.data : [:]
    if (appsListResp.apps) {
        visitAppEntries(appsListResp.apps as List) { Map appEntry, Map app, boolean isChildLevel, List _ ->
            if (app?.id != null) appSourceById[app.id] = (app.user ? "community" : "builtin")
        }
    }

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
    // R-7 B2 (v5.20.0): build small id→label maps for the Performance tab's CPU charts so the
    // SPA doesn't cross-fetch /api/devices and /api/apps just to look up names. Tiny payload
    // (counts × ~20 bytes), saves 2 client round trips of much heavier endpoints.
    // Walk /hub2/devicesList directly to build id→type map. Skips analyzeDevices' enrichment overhead
    // (we only need the raw type/name from the bulk endpoint, not the cross-classification work).
    Map deviceTypeById = [:]
    Map devWrap = hubMapRequest(DEVICES_LIST_PATH, "devices list (B2 labels)", 15)
    if (devWrap.ok && devWrap.data.devices) {
        flattenDeviceEntries(devWrap.data.devices as List).each { Map entry ->
            Map dev = entry?.data instanceof Map ? (Map) entry.data : null
            if (dev?.id != null) deviceTypeById[dev.id] = (dev.type ?: 'Unknown') as String
        }
    }
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

    List checkpoints = loadCheckpoints()
    return [
        stats: stats, resources: resources,
        topTalkers: topTalkers,
        deviceTypeById: deviceTypeById,        // B2: id → driver type for CPU-by-device-type chart
        appParentTypeById: appParentTypeById,  // B2: id → parent label for CPU-by-app-type chart
        checkpointCount: checkpoints?.size() ?: 0,
        maxCheckpoints: (settings.maxCheckpoints ?: 10) as int,
        checkpoints: (checkpoints ?: []).collect { Map cp -> [
            timestamp: cp.timestamp, timestampMs: cp.timestampMs,
            stats: cp.stats, resources: cp.resources, radioStats: cp.radioStats,
            temperature: cp.temperature, databaseSize: cp.databaseSize
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

List getStructuredAlerts(Map shared = [:]) {
    List alerts = []
    Map resources     = (shared.resources as Map)     ?: fetchSystemResources()
    Float temperature = (shared.temperature as Float) ?: fetchTemperature()
    Map hubAlerts     = (shared.hubAlerts as Map)     ?: fetchHubAlerts(shared.hubData as Map)

    // Calculated alerts
    int    critMemKb   = ((settings.critMemMb   ?: DEFAULT_CRIT_MEM_MB)   as int) * 1024
    int    warnMemKb   = ((settings.warnMemMb   ?: DEFAULT_WARN_MEM_MB)   as int) * 1024
    double critCpuLoad = (settings.critCpuLoad  ?: DEFAULT_CRIT_CPU_LOAD) as double
    double warnCpuLoad = (settings.warnCpuLoad  ?: DEFAULT_WARN_CPU_LOAD) as double
    int    critTempC   = (settings.critTempC    ?: DEFAULT_CRIT_TEMP_C)   as int
    int    warnTempC   = (settings.warnTempC    ?: DEFAULT_WARN_TEMP_C)   as int

    if (resources && resources.freeOSMemory < critMemKb) {
        alerts << [severity: "critical", name: "OS memory critically low (${formatMemory(resources.freeOSMemory)})"]
    } else if (resources && resources.freeOSMemory < warnMemKb) {
        alerts << [severity: "warning", name: "Low OS memory (${formatMemory(resources.freeOSMemory)})"]
    }

    if (resources && (resources.cpuAvg5min ?: 0) > critCpuLoad) {
        alerts << [severity: "critical", name: "Very high CPU load (${String.format('%.2f', resources.cpuAvg5min as float)})"]
    } else if (resources && (resources.cpuAvg5min ?: 0) > warnCpuLoad) {
        alerts << [severity: "warning", name: "Elevated CPU load (${String.format('%.2f', resources.cpuAvg5min as float)})"]
    }

    if (temperature != null && temperature > critTempC) {
        alerts << [severity: "critical", name: "Hub temperature very high (${String.format('%.1f', temperature)}\u00B0C)"]
    } else if (temperature != null && temperature > warnTempC) {
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

    // Hub messages from /hub/messages — admin notifications surfaced in the platform UI.
    // The hub embeds raw HTML in message text (e.g. <span> with dismiss links); strip it
    // so the UI h() escape doesn't render visible tags.
    fetchHubMessages().each { Map msg ->
        String text = stripHtml(msg.text ?: msg.message ?: msg.toString())
        if (text) alerts << [key: "hubMessage", name: text, severity: "info"]
    }

    // Network: Ethernet + WiFi both active
    Map networkConfig = (Map) shared.network?.network
    if (!networkConfig) { Map r = hubMapRequest(NETWORK_CONFIG_PATH, "network configuration", 15); networkConfig = r.ok ? r.data : null }
    if (networkConfig && networkConfig.hasEthernet && networkConfig.hasWiFi) {
        alerts << [severity: "warning", name: "Ethernet and WiFi both active \u2014 disable WiFi when using Ethernet"]
    }

    // Z-Wave ghost nodes \u2014 cached 60 s to avoid an 8-second fetch on every Dashboard/Health load
    // when the shared cache was built without includeNetwork (the common lightweight path).
    Map zwRaw = (shared.network?.zwave as Map)
    if (!zwRaw) {
        long lastZwCheck = state.lastZwaveGhostCheckMs ?: 0
        if (now() - lastZwCheck > 60000) {
            Map zwWrap = hubMapRequest(ZWAVE_DETAILS_PATH, "Z-Wave details", 8)
            zwRaw = zwWrap.ok ? zwWrap.data : null
            if (zwRaw) {
                state.lastZwaveGhostCheckMs = now()
                state.cachedZwaveGhostCount = buildZwaveGhostNodes(zwRaw).size()
            }
        }
    }
    int ghostCount = zwRaw ? buildZwaveGhostNodes(zwRaw).size() : (state.cachedZwaveGhostCount ?: 0) as int
    if (ghostCount > 0) {
        alerts << [severity: "critical", name: "${ghostCount} Z-Wave ghost node${ghostCount > 1 ? 's' : ''} detected \u2014 remove from mesh"]
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
    return hubRequestInternal(path, name, type, timeout, true)
}

private Map hubMapRequest(String path, String name, int timeout = 30) {
    Object raw = hubRequestInternal(path, name, "json", timeout, true)
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

Map fetchFileManagerStats() {
    Map wrap = hubMapRequest("/hub/fileManager/json", "file manager", 10)
    if (!wrap.ok) return null
    List files = (List) (wrap.data.files ?: [])
    long usedBytes = 0L
    files.each { usedBytes += (it.size?.toString()?.toLong() ?: 0L) }
    return [fileCount: files.size(), usedBytes: usedBytes, freeSpace: wrap.data.freeSpace]
}

Float fetchTemperature() {
    String text = (String) hubRequest(INTERNAL_TEMP_PATH, "internal temperature", "text")
    if (text) { try { return text.toFloat() } catch (Exception e) { /* ignore */ } }
    return null
}

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
    String txt = (String) hubRequest(LOAD_THRESHOLD_PATH, "excessive load threshold", "text", 5)
    if (!txt) return null
    try { return txt.trim().toInteger() } catch (Exception e) { return null }
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
    return result.isEmpty() ? null : result
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
    String laClean = laRaw ? laRaw.replaceAll(/<[^>]+>/, '').trim() : null
    boolean limitedSet = laClean && !laClean.equalsIgnoreCase("no limit set") && !laClean.isEmpty()
    List subnetList = subnets ? subnets.trim().split(',').findAll { it } as List : []
    Boolean cloudDisabled = hubData ? (hubData.disableCloudController == true) : null
    return [
        limitedAccess: [
            enabled: limitedSet,
            addresses: limitedSet ? laClean.split(/[,\s]+/).findAll { it } as List : []
        ],
        allowedSubnets: subnetList,
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
    return t ?: null
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

String runNetworkTest(String type, String ip = null) {
    String path
    int timeout
    switch (type) {
        case "ping-gateway":
            path = NETWORK_TEST_PING_GATEWAY
            timeout = 30
            break
        case "ping-ip":
            if (!ip || !(ip ==~ /^(\d{1,3}\.){3}\d{1,3}$/)) return "Error: invalid IP address"
            path = NETWORK_TEST_PING_PREFIX + ip
            timeout = 30
            break
        case "speedtest":
            path = NETWORK_TEST_SPEEDTEST
            timeout = 90
            break
        default:
            return "Error: unknown test type"
    }
    String result = (String) hubRequest(path, "network test ${type}", "text", timeout)
    return result ?: "(no output returned)"
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
            result.weakNeighbors = result.neighbors.findAll { it.lqi != null && it.lqi < ZIGBEE_LQI_CRIT }
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
                Map parentAppInfo = normalizedParentAppId ? (Map) appLookup[normalizedParentAppId] : null
                String parentAppName = parentAppInfo?.label ?: (normalizedParentAppId ? "App ${normalizedParentAppId}" : null)

                // Collect for deep-mode enrichment: isNetwork devices (parentApp not in bulk list)
                // and CONN_OTHER devices (may have parentApp in fullJson)
                boolean needsEnrichment = (connectionType == CONN_OTHER) ||
                    (connectionType == CONN_LAN_DIRECT && integration == "LAN Device" && device.isNetwork == true)
                if (needsEnrichment) {
                    uncertainDevices[device.id.toString()] = [appInfo: parentAppInfo, currentIntegration: integration, currentConn: connectionType, deviceId: device.id]
                }

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

    // Deep-mode pass 2: enrich uncertain devices via device/fullJson (parentApp + controllerType)
    if (deep && uncertainDevices) {
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
        network: hubMapRequest(NETWORK_CONFIG_PATH, "network configuration", 15).with { it.ok ? it.data : null },
        zwave:   hubMapRequest(ZWAVE_DETAILS_PATH, "Z-Wave details", 20).with { it.ok ? it.data : null },
        zigbee:  hubMapRequest(ZIGBEE_DETAILS_PATH, "Zigbee details", 20).with { it.ok ? it.data : null },
        matter:  hubMapRequest(MATTER_DETAILS_PATH, "Matter details", 15).with { it.ok ? it.data : null },
        hubMesh: hubMapRequest(HUB_MESH_PATH, "Hub Mesh", 15).with { it.ok ? it.data : null }
    ]
}

Map analyzeSystemHealth(Map shared = [:]) {
    Map memory        = (shared.resources as Map)     ?: fetchSystemResources()
    Map stateCompression = fetchStateCompression()
    Map hubAlerts     = (shared.hubAlerts as Map)     ?: fetchHubAlerts(shared.hubData as Map)
    Integer databaseSize = (shared.databaseSize as Integer) ?: fetchDatabaseSize()
    Float temperature = (shared.temperature as Float) ?: fetchTemperature()
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
    int    critMemKb   = ((settings.critMemMb   ?: DEFAULT_CRIT_MEM_MB)   as int) * 1024
    int    warnMemKb   = ((settings.warnMemMb   ?: DEFAULT_WARN_MEM_MB)   as int) * 1024
    double critCpuLoad = (settings.critCpuLoad  ?: DEFAULT_CRIT_CPU_LOAD) as double
    double warnCpuLoad = (settings.warnCpuLoad  ?: DEFAULT_WARN_CPU_LOAD) as double
    int    critTempC   = (settings.critTempC    ?: DEFAULT_CRIT_TEMP_C)   as int
    int    warnTempC   = (settings.warnTempC    ?: DEFAULT_WARN_TEMP_C)   as int

    if (memory && memory.freeOSMemory < critMemKb) {
        health.alerts << [severity: "critical", name: "OS memory critically low (${formatMemory(memory.freeOSMemory)}) — hub may become unresponsive"]
    } else if (memory && memory.freeOSMemory < warnMemKb) {
        health.alerts << [severity: "warning", name: "Low OS memory (${formatMemory(memory.freeOSMemory)})"]
    }
    if (memory && memory.cpuAvg5min > critCpuLoad) {
        health.alerts << [severity: "critical", name: "Very high CPU load (${String.format('%.2f', memory.cpuAvg5min as float)} — 4 cores)"]
    } else if (memory && memory.cpuAvg5min > warnCpuLoad) {
        health.alerts << [severity: "warning", name: "Elevated CPU load (${String.format('%.2f', memory.cpuAvg5min as float)} — 4 cores fully saturated)"]
    }
    if (temperature != null && temperature > critTempC) {
        health.alerts << [severity: "critical", name: "Hub temperature very high (${String.format('%.1f', temperature)}\u00B0C)"]
    } else if (temperature != null && temperature > warnTempC) {
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
            ghostNodes << [
                id: nodeId,
                deviceId: deviceId,
                name: nodeData.name ?: "Unknown",
                status: nodeData.status ?: "No route",
                type: zwType,
                signals: signals
            ]
        }
    }
    return ghostNodes
}

Map buildRadioProtocolMap() {
    Map protocols = [:]
    try {
        Map zigbeeData = hubMapRequest(ZIGBEE_DETAILS_PATH, "Zigbee details", 20).with { it.ok ? it.data : null }
        if (zigbeeData?.devices) {
            zigbeeData.devices.each { Map d -> if (d.id) protocols[d.id] = "zigbee" }
        }
        Map zwaveData = hubMapRequest(ZWAVE_DETAILS_PATH, "Z-Wave details", 20).with { it.ok ? it.data : null }
        if (zwaveData?.nodes) {
            zwaveData.nodes.each { Map n -> if (n.deviceId) protocols[n.deviceId] = "zwave" }
        }
        Map matterData = hubMapRequest(MATTER_DETAILS_PATH, "Matter details", 15).with { it.ok ? it.data : null }
        if (matterData?.devices) {
            matterData.devices.each { Map d -> if (d.id) protocols[d.id] = "matter" }
        }
    } catch (Exception e) {
        logDebug "Error building radio protocol map: ${e.message}"
    }
    return protocols
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

Map lookupIntegration(String text) {
    if (!text) return null
    String lower = text.toLowerCase()
    for (Map.Entry entry : INTEGRATION_TABLE.entrySet()) {
        if (lower.contains((String) entry.key)) return (Map) entry.value
    }
    return null
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

    // 2. Parent app lookup (parentAppId present in bulk list for some devices)
    Object parentAppIdRaw = extractParentAppId(device)
    String normalizedParentAppId = normalizeAppLookupId(parentAppIdRaw)
    if (normalizedParentAppId) {
        Map appInfo = (Map) appLookup[normalizedParentAppId]
        if (appInfo) {
            String appType  = (appInfo.type  ?: "").toString().toLowerCase()
            String appLabel = (appInfo.label ?: "").toString().toLowerCase()
            Map match = lookupIntegration(appType) ?: lookupIntegration(appLabel)
            if (match) return [connectionType: match.conn, integration: match.name, builtin: true]
            String intName = (appInfo.type ?: appInfo.label) ?: "Unknown"
            boolean isBuiltin = !(appInfo.user == true)
            return [connectionType: CONN_OTHER, integration: intName, builtin: isBuiltin]
        }
    }

    // 3. Network (LAN) flag — parentApp not available in bulk list; will be enriched via fullJson in deep mode
    if (device.isNetwork == true) return [connectionType: CONN_LAN_DIRECT, integration: "LAN Device", builtin: driverIsBuiltin]

    // 4. Final fallback — no reliable signal for connection type
    return [connectionType: CONN_OTHER, integration: "Other", builtin: driverIsBuiltin]
}

// Enriches device classification using device/fullJson for devices bulk data couldn't resolve.
// uncertainDevices: Map<String deviceId, Map appInfo> where appInfo may be null.
// Primary signal: parentApp from fullJson (has appType.name which matches INTEGRATION_TABLE).
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
                Map fullWrap = hubMapRequest("${DEVICE_FULL_JSON_PATH}${idStr}", "device ${idStr} full", 10)
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

        // Primary: match parent app type name against integration table
        if (parentAppTypeName) {
            Map match = lookupIntegration(parentAppTypeName)
            if (match) {
                result[idStr] = [connectionType: match.conn, integration: match.name, builtin: true]
                return
            }
            // Known parent app but not in table — use app type name, preserve user/builtin from appType.user
            result[idStr] = [connectionType: CONN_OTHER, integration: parentAppTypeName, builtin: isBuiltin]
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
    logInfo "Creating perf checkpoint..."

    Map statsWrap = hubMapRequest(RUNTIME_STATS_PATH, "runtime stats")
    if (!statsWrap.ok) {
        logError "Failed to fetch current stats"
        return false
    }
    Map stats = statsWrap.data
    stats.uptimeSeconds = parseUptime(stats.uptime as String)

    Map resources = fetchSystemResources()
    Float temperature = fetchTemperature()
    Integer databaseSize = fetchDatabaseSize()

    // Capture radio message counts for Z-Wave and Zigbee
    Map zwaveData = hubMapRequest(ZWAVE_DETAILS_PATH, "Z-Wave details", 20).with { it.ok ? it.data : null }
    Map zigbeeData = hubMapRequest(ZIGBEE_DETAILS_PATH, "Zigbee details", 20).with { it.ok ? it.data : null }
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

    List checkpoints = loadCheckpoints()
    checkpoints.add(0, checkpoint)

    int maxCp = (settings.maxCheckpoints ?: 10) as int
    if (checkpoints.size() > maxCp) {
        checkpoints = checkpoints.take(maxCp)
    }

    saveCheckpoints(checkpoints)
    logInfo "Perf checkpoint created successfully"
    return true
}

Map buildZeroBaseline(Map stats, Map resources) {
    return [
        timestampMs: 0,
        uptime: "0h 0m 0s",
        uptimeSeconds: 0,
        devicesUptime: "0h 0m 0s",
        appsUptime: "0h 0m 0s",
        totalDevicesRuntime: "0ms",
        totalAppsRuntime: "0ms",
        devicePct: "0.000%",
        appPct: "0.000%",
        temperature: null,
        databaseSize: null,
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

Map getHubInfo(Map prefetchedHubData = null) {
    Map info = [name: location.name ?: "Unknown", firmware: "Unknown", hardware: "Unknown", ip: "Unknown"]
    if (location.hubs && location.hubs.size() > 0) {
        def hub = location.hubs[0]
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
        byStatus: [active: 0, inactive: 0, disabled: 0],
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
        // R-5 (v5.18.0, A9): schema version sentinel for forward-compat. Bump when extractAuditFields'
        // output shape changes in a way that downstream consumers (renderAuditHtml, finalizeAudit
        // enrichment passes) need to know about. AUDIT_SCANS is in-memory only, so old records
        // never persist across an app reload — but cross-restart cases or future on-disk persistence
        // benefit from being able to detect the format.
        _schemaVersion:      1,
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
        // appsUsingCount is the hub's authoritative subscriber count (from fj.appsUsingCount).
        // appsUsing.size() is NOT used here — fj.dashboards contains every dashboard the device
        // is in the *allowed-devices list* for, not just dashboards with actual tiles, so it is
        // too noisy to gate "unreferenced" on. A device is unreferenced when no app subscribes
        // to it and it has no parent integration app managing it.
        int    apps  = (d.appsUsingCount as Integer) ?: 0
        int    dashs = ((d.dashboards ?: []) as List).size()
        boolean noParentApp = (d.parentApp == null)

        // Unreferenced: no app subscribers and no parent app (dashboard membership ignored)
        if (apps == 0 && noParentApp) {
            unreferenced << [id: did, name: (d.label ?: d.name), type: d.deviceTypeName,
                             lastActivityTime: d.lastActivityTime, driverType: d.driverType]
        }
        // Mesh orphans
        if (d.orphan) {
            meshOrphans << [id: did, name: (d.label ?: d.name)]
        }
        // Stuck jobs: nextRunTime in the past AT THE MOMENT WE OBSERVED THIS DEVICE.
        // Comparing against the per-device fetch timestamp (not now()) eliminates the
        // scan-duration false positives where a recurring job fires during the scan and
        // its old nextRunTime appears stale relative to finalize time.
        Long fetchedAt = (d.fetchedAtMs as Long) ?: nowMs
        ((d.scheduledJobs ?: []) as List).each { Map s ->
            String nrt = s.nextRunTime as String
            if (nrt) {
                Long when = parseHubitatTimestamp(nrt)
                if (when != null && when < fetchedAt) {
                    stuckJobs << [id: did, name: (d.label ?: d.name),
                                  handler: s.handler, overdueMs: (fetchedAt - when),
                                  status: s.status, prevRunTime: s.prevRunTime]
                }
            }
        }
        // Reference ranking
        allRefs << [id: did, name: (d.label ?: d.name),
                    appsCount: apps, dashboardsCount: dashs, total: apps + dashs]
        // Apps → devices index
        ((d.appsUsing ?: []) as List).each { Map a ->
            Long aid = a.id as Long
            Map entry = (Map) (appsIndex[aid] ?: [label: (a.label ?: a.name), disabled: (a.disabled == true), devices: []])
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
    List appsSorted = appsIndex.collect { id, e -> [id: id, label: e.label, disabled: (e.disabled == true), devices: (e.devices as List).sort { x, y -> (x.name as String) <=> (y.name as String) }] }
        .sort { a, b -> (a.label as String) <=> (b.label as String) }
    List dashSorted = dashIndex.collect { id, e -> [id: id, name: e.name, devices: (e.devices as List).sort { x, y -> (x.name as String) <=> (y.name as String) }] }
        .sort { a, b -> (a.name as String) <=> (b.name as String) }

    // Manually-tuned devices: detect divergence from the fleet mode for each tuning field.
    // Hubitat doesn't expose the platform default for these (not in /hub2/hubData), so we
    // use the fleet mode as a robust proxy — outliers fall out regardless of the actual default.
    Integer modeSt = computeMode(devices.values().collect { (it as Map).spammyThreshold as Integer })
    Integer modeMs = computeMode(devices.values().collect { (it as Map).maxStates as Integer })
    Integer modeMe = computeMode(devices.values().collect { (it as Map).maxEvents as Integer })
    List tunedDevices = []
    devices.each { _did, _d ->
        Map d = _d as Map
        Integer st = d.spammyThreshold as Integer
        Integer ms = d.maxStates as Integer
        Integer me = d.maxEvents as Integer
        boolean stOff = (st != null && modeSt != null && st != modeSt)
        boolean msOff = (ms != null && modeMs != null && ms != modeMs)
        boolean meOff = (me != null && modeMe != null && me != modeMe)
        if (stOff || msOff || meOff) {
            tunedDevices << [id: _did as Long, name: (d.label ?: d.name),
                             spammyThreshold: st, maxStates: ms, maxEvents: me,
                             spammyOff: stOff, maxStatesOff: msOff, maxEventsOff: meOff]
        }
    }
    tunedDevices.sort { a, b -> (a.name as String) <=> (b.name as String) }

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
        tunedDevices:      tunedDevices,
        tunedDefaults:     [spammyThreshold: modeSt, maxStates: modeMs, maxEvents: modeMe],
        scanStartedMs:     scanStartedMs,
        scanDurationMs:    (nowMs - scanStartedMs)
    ]
}

/** Mode of a list of integers; null entries skipped. Returns null if no values. */
private Integer computeMode(List values) {
    Map<Integer,Integer> counts = [:]
    values.each { v -> if (v != null) counts[v as Integer] = (counts[v as Integer] ?: 0) + 1 }
    return counts.isEmpty() ? null : counts.max { it.value }.key
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
 * Render the audit report as a fully self-contained HTML document.
 * No external resources; CSS inlined; uses /device/edit/{id} and /installedapp/configure/{id}
 * relative URLs that work when the file is served from FileManager on the same hub.
 */
private String renderAuditHtml(Map xref, String hubName, String generatedAt, List failed) {
    StringBuilder b = new StringBuilder()
    b << "<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"UTF-8\">"
    b << "<title>Device Usage Audit — ${esc(hubName)}</title>"
    // v5.17.0 (R-3 #3): inline CSS extracted to AUDIT_REPORT_CSS @Field constant near the top
    // of the file. Same source-of-truth duplication pattern as before — kept in sync with the
    // SPA <style> block in hub_diagnostics_ui.html — but visible as one block instead of 38
    // scattered StringBuilder appends. Future deduplication via serveUI substitution is a
    // documented option (would break workbench/offline modes).
    b << "<style>"
    b << AUDIT_REPORT_CSS
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
    b << "<div><a href=\"#tuned\">Manually-tuned devices (${(xref.tunedDevices as List).size()})</a></div>"
    b << "<div><a href=\"#critical\">Critical devices (top 20)</a></div>"
    b << "<div><a href=\"#rooms\">Devices by room (${(xref.rooms as List).size()})</a></div>"
    int zwNodeCount = (xref.allDevices as Map).values().count { (it as Map).zwaveNode != null }
    if (zwNodeCount > 0) b << "<div><a href=\"#zwjs\">Z-Wave JS mesh health (${zwNodeCount})</a></div>"
    int hmCount = (xref.allDevices as Map).values().count { (it as Map).hubMeshState != null }
    if (hmCount > 0) b << "<div><a href=\"#hubmesh\">Hub Mesh linked devices (${hmCount})</a></div>"
    b << "<div><a href=\"#apps\">Apps → devices (${(xref.appsToDevices as List).size()})</a></div>"
    b << "<div><a href=\"#dashboards\">Dashboards → devices (${(xref.dashboardsToDevices as List).size()})</a></div>"
    b << "<div><a href=\"#all\">Per-device detail table</a></div>"
    if (failed) b << "<div><a href=\"#failed\" class=\"crit\">Failed to fetch (${failed.size()})</a></div>"
    b << "</div></div>"

    // Summary (not collapsible — high-signal at-a-glance)
    b << "<div class=\"card\" id=\"summary\"><div class=\"card-h\">Summary</div><div class=\"card-b\"><div class=\"metrics\">"
    b << sumcell("Devices",     xref.deviceCount as String, null)
    b << sumcell("Unreferenced", (xref.unreferenced as List).size() as String, "warn")
    b << sumcell("Mesh orphans", (xref.meshOrphans as List).size() as String, "crit")
    b << sumcell("Stuck jobs",   (xref.stuckJobs as List).size() as String, "warn")
    b << sumcell("Critical (≥${xref.criticalThreshold} refs)", xref.criticalCount as String, null)
    b << "</div></div></div>"

    // Unreferenced
    b << openCard("unref", "⚠ Unreferenced devices (${(xref.unreferenced as List).size()})", true)
    if ((xref.unreferenced as List).isEmpty()) {
        b << "<div class=\"muted\">None — every device is used by at least one app, dashboard, or parent integration.</div>"
    } else {
        b << "<div class=\"muted\" style=\"margin-bottom:6px\">No apps, no dashboards, no parent app — default sort: oldest last activity first.</div>"
        b << "<div class=\"tbl-wrap\"><table data-sortable id=\"t_unref\"><thead><tr><th>Device</th><th>Type</th><th data-sd=\"asc\">Last activity</th><th>Source</th></tr></thead><tbody>"
        (xref.unreferenced as List).each { Map u ->
            b << "<tr><td>${dlink(u.id as Long, u.name as String)}</td>"
            b << "<td>${esc(u.type as String)}</td>"
            b << "<td>${esc(u.lastActivityTime as String)}</td>"
            b << "<td>${driverBadge(u.driverType as String)}</td></tr>"
        }
        b << "</tbody></table></div>"
    }
    b << closeCard()

    // Mesh orphans
    b << openCard("orphans", "⚠ Mesh orphans (${(xref.meshOrphans as List).size()})", true)
    if ((xref.meshOrphans as List).isEmpty()) {
        b << "<div class=\"muted\">None — no devices report orphan radio state.</div>"
    } else {
        b << "<div class=\"muted\" style=\"margin-bottom:6px\">Hubitat reports <code>orphan: true</code> — physical radio relationship lost.</div>"
        b << "<div>" + (xref.meshOrphans as List).collect { dlink(it.id as Long, it.name as String) }.join(" · ") + "</div>"
    }
    b << closeCard()

    // Stuck jobs
    b << openCard("stuck", "⚠ Stuck scheduled jobs (${(xref.stuckJobs as List).size()})", true)
    if ((xref.stuckJobs as List).isEmpty()) {
        b << "<div class=\"muted\">None — all scheduled jobs have a future or null nextRunTime.</div>"
    } else {
        b << "<div class=\"muted\" style=\"margin-bottom:6px\">A null <i>Last run</i> with a past <i>nextRunTime</i> = job was scheduled but never fired.</div>"
        b << "<div class=\"tbl-wrap\"><table data-sortable id=\"t_stuck\"><thead><tr><th>Device</th><th>Handler</th><th data-t=\"n\" data-sd=\"desc\">Overdue</th><th>Last run</th><th>Status</th></tr></thead><tbody>"
        (xref.stuckJobs as List).each { Map s ->
            Long ovMs = s.overdueMs as Long
            String prev = s.prevRunTime as String
            b << "<tr><td>${dlink(s.id as Long, s.name as String)}</td>"
            b << "<td><code>${esc(s.handler as String)}</code></td>"
            b << "<td data-sv=\"${ovMs ?: 0}\">${formatDurationSec(ovMs)}</td>"
            b << "<td>${prev ? esc(prev) : '<span class=\"muted\">never</span>'}</td>"
            b << "<td>${esc(s.status as String)}</td></tr>"
        }
        b << "</tbody></table></div>"
    }
    b << closeCard()

    // Manually-tuned devices
    b << openCard("tuned", "🛠 Manually-tuned devices (${(xref.tunedDevices as List).size()})", true)
    if ((xref.tunedDevices as List).isEmpty()) {
        b << "<div class=\"muted\">All devices share the same buffer/threshold values — none have been manually tuned.</div>"
    } else {
        Map td = (xref.tunedDefaults as Map) ?: [:]
        b << "<div class=\"muted\" style=\"margin-bottom:6px\">Devices whose buffer/threshold values diverge from the fleet mode (default: spammyThreshold=${td.spammyThreshold ?: '?'}, maxStates=${td.maxStates ?: '?'}, maxEvents=${td.maxEvents ?: '?'}). Diverging values shown in <span class=\"warn\">bold</span>.</div>"
        b << "<div class=\"tbl-wrap\"><table data-sortable id=\"t_tuned\"><thead><tr><th data-sd=\"asc\">Device</th><th data-t=\"n\">spammyThreshold</th><th data-t=\"n\">maxStates</th><th data-t=\"n\">maxEvents</th></tr></thead><tbody>"
        (xref.tunedDevices as List).each { Map t ->
            String stCell = (t.spammyThreshold == null) ? "<span class=\"muted\">—</span>" : (t.spammyOff ? "<b style=\"color:var(--warn)\">${t.spammyThreshold}</b>" : "${t.spammyThreshold}")
            String msCell = (t.maxStates == null) ? "<span class=\"muted\">—</span>" : (t.maxStatesOff ? "<b style=\"color:var(--warn)\">${t.maxStates}</b>" : "${t.maxStates}")
            String meCell = (t.maxEvents == null) ? "<span class=\"muted\">—</span>" : (t.maxEventsOff ? "<b style=\"color:var(--warn)\">${t.maxEvents}</b>" : "${t.maxEvents}")
            b << "<tr><td>${dlink(t.id as Long, t.name as String)}</td>"
            b << "<td data-sv=\"${t.spammyThreshold ?: 0}\">${stCell}</td>"
            b << "<td data-sv=\"${t.maxStates ?: 0}\">${msCell}</td>"
            b << "<td data-sv=\"${t.maxEvents ?: 0}\">${meCell}</td></tr>"
        }
        b << "</tbody></table></div>"
    }
    b << closeCard()

    // Critical devices
    b << openCard("critical", "⭐ Critical devices (top 20 by reference count)", true)
    if ((xref.criticalTop20 as List).isEmpty()) {
        b << "<div class=\"muted\">No devices have any apps or dashboards.</div>"
    } else {
        b << "<div class=\"tbl-wrap\"><table data-sortable id=\"t_crit\"><thead><tr><th>Device</th><th data-t=\"n\">Apps</th><th data-t=\"n\">Dashboards</th><th data-t=\"n\" data-sd=\"desc\">Total</th></tr></thead><tbody>"
        (xref.criticalTop20 as List).each { Map c ->
            b << "<tr><td>${dlink(c.id as Long, c.name as String)}</td>"
            b << "<td>${c.appsCount}</td><td>${c.dashboardsCount}</td>"
            b << "<td><b>${c.total}</b></td></tr>"
        }
        b << "</tbody></table></div>"
    }
    b << closeCard()

    // Devices by Room (v5.11.0)
    List rooms = (xref.rooms as List) ?: []
    b << openCard("rooms", "🏠 Devices by room (${rooms.size()})", true)
    if (rooms.isEmpty()) {
        b << "<div class=\"muted\">No room data available.</div>"
    } else {
        Map allDevs = (xref.allDevices as Map) ?: [:]
        b << "<div class=\"muted\" style=\"margin-bottom:6px\">Devices grouped by their assigned room. Empty rooms surface storage clutter; high-density rooms may warrant a split.</div>"
        b << "<div class=\"tbl-wrap\"><table data-sortable id=\"t_rooms\"><thead><tr><th>Room</th><th data-t=\"n\" data-sd=\"desc\">Devices</th><th>Members</th></tr></thead><tbody>"
        rooms.each { Map r ->
            List dids = (r.deviceIds as List) ?: []
            String members = dids.collect { Long did ->
                Map dev = allDevs[did] as Map
                String label = dev ? (dev.label ?: dev.name ?: "Device ${did}") as String : "Device ${did}"
                dlink(did, label)
            }.join(" · ")
            b << "<tr><td><b>${esc(r.name as String)}</b></td>"
            b << "<td data-sv=\"${dids.size()}\">${dids.size()}</td>"
            b << "<td>${members ?: '<span class=\"muted\">empty</span>'}</td></tr>"
        }
        b << "</tbody></table></div>"
    }
    b << closeCard()

    // Z-Wave JS Mesh Health (v5.11.0)
    List zwNodeRows = ((xref.allDevices as Map).values().findAll { (it as Map).zwaveNode != null }) as List
    if (zwNodeRows) {
        b << openCard("zwjs", "📶 Z-Wave JS mesh health (${zwNodeRows.size()})", true)
        b << "<div class=\"muted\" style=\"margin-bottom:6px\">Per-node controller statistics from Z-Wave JS. RSSI lower (more negative) is quieter; RTT lower is faster; PER (packet error rate) higher is worse.</div>"
        b << "<div class=\"tbl-wrap\"><table data-sortable id=\"t_zwjs\"><thead><tr><th>Device</th><th data-t=\"n\">Node</th><th>State</th><th>Status</th><th>Interview</th><th data-t=\"n\">RTT</th><th>RSSI</th><th data-t=\"n\" data-sd=\"desc\">PER %</th><th data-t=\"n\">TX</th><th data-t=\"n\">RX</th><th>Last Seen</th></tr></thead><tbody>"
        zwNodeRows.sort { x, y -> ((y as Map).zwaveNode?.per ?: 0) <=> ((x as Map).zwaveNode?.per ?: 0) }
        zwNodeRows.each { Object _row ->
            Map d = _row as Map
            Map z = d.zwaveNode as Map
            Map stats = (z.statistics as Map) ?: [:]
            String stateColor = (z.nodeState == "OK") ? "var(--ok)" : "var(--crit)"
            b << "<tr><td>${dlink(d.id as Long, (d.label ?: d.name) as String)}</td>"
            b << "<td>${z.nodeId ?: ''}</td>"
            b << "<td><span style=\"color:${stateColor}\">${esc(z.nodeState as String)}</span></td>"
            b << "<td>${z.status ?: ''}</td>"
            b << "<td>${esc(z.interviewStage as String)}</td>"
            b << "<td>${z.rtt ?: '<span class=\"muted\">—</span>'}</td>"
            b << "<td>${z.rssi ?: '<span class=\"muted\">—</span>'}</td>"
            b << "<td data-sv=\"${z.per ?: 0}\">${z.per ?: 0}</td>"
            b << "<td>${stats.commandsTX ?: 0}</td>"
            b << "<td>${stats.commandsRX ?: 0}</td>"
            b << "<td>${esc((z.lastSeenLocal ?: '') as String)}</td></tr>"
        }
        b << "</tbody></table></div>"
        b << closeCard()
    }

    // Hub Mesh Linked Devices (v5.11.0)
    List hmRows = ((xref.allDevices as Map).values().findAll { (it as Map).hubMeshState != null }) as List
    if (hmRows) {
        b << openCard("hubmesh", "🔗 Hub Mesh linked devices (${hmRows.size()})", true)
        b << "<div class=\"muted\" style=\"margin-bottom:6px\">Devices physically owned by another hub on this account, exposed locally via Hub Mesh.</div>"
        b << "<div class=\"tbl-wrap\"><table data-sortable id=\"t_hubmesh\"><thead><tr><th>Device</th><th>Source Hub</th><th>Source Device ID</th><th>Status</th><th>Raw</th></tr></thead><tbody>"
        hmRows.each { Object _row ->
            Map d = _row as Map
            Map hm = d.hubMeshState as Map
            String srcHub = hm.sourceHubName ?: hm.hostHubName ?: hm.hubName ?: "—"
            String srcDevId = (hm.sourceDeviceId ?: hm.hostDeviceId ?: hm.remoteDeviceId ?: "") as String
            String status = (hm.status ?: hm.linkStatus ?: hm.online ?: "") as String
            b << "<tr><td>${dlink(d.id as Long, (d.label ?: d.name) as String)}</td>"
            b << "<td>${esc(srcHub)}</td>"
            b << "<td>${esc(srcDevId)}</td>"
            b << "<td>${esc(status)}</td>"
            b << "<td><code style=\"font-size:11px\">${esc(hm.toString())}</code></td></tr>"
        }
        b << "</tbody></table></div>"
        b << closeCard()
    }

    // Apps → devices
    b << openCard("apps", "📱 Apps → devices (${(xref.appsToDevices as List).size()})", true)
    if ((xref.appsToDevices as List).isEmpty()) {
        b << "<div class=\"muted\">No apps subscribe to any devices.</div>"
    } else {
        b << "<div class=\"filter\"><input type=\"text\" data-filter=\"t_apps\" placeholder=\"Filter apps or devices…\"></div>"
        b << "<div class=\"tbl-wrap\"><table data-sortable id=\"t_apps\"><thead><tr><th data-sd=\"asc\">App</th><th data-t=\"n\"># Devices</th><th>Devices</th></tr></thead><tbody>"
        (xref.appsToDevices as List).each { Map a ->
            int n = (a.devices as List).size()
            String devs = (a.devices as List).collect { dlink(it.id as Long, it.name as String) }.join(", ")
            String appCell = a.disabled
                ? "<s class=\"muted\" title=\"App is disabled — its subscriptions are inactive\">${alink(a.id as Long, a.label as String)}</s> <span class=\"badge b-warn\">disabled</span>"
                : alink(a.id as Long, a.label as String)
            b << "<tr><td data-sv=\"${esc(a.label as String)}\">${appCell}</td>"
            b << "<td>${n}</td>"
            b << "<td>${devs}</td></tr>"
        }
        b << "</tbody></table></div>"
    }
    b << closeCard()

    // Dashboards → devices
    b << openCard("dashboards", "📊 Dashboards → devices (${(xref.dashboardsToDevices as List).size()})", true)
    if ((xref.dashboardsToDevices as List).isEmpty()) {
        b << "<div class=\"muted\">No dashboards reference any devices.</div>"
    } else {
        b << "<div class=\"filter\"><input type=\"text\" data-filter=\"t_dash\" placeholder=\"Filter dashboards or devices…\"></div>"
        b << "<div class=\"tbl-wrap\"><table data-sortable id=\"t_dash\"><thead><tr><th data-sd=\"asc\">Dashboard</th><th data-t=\"n\"># Devices</th><th>Devices</th></tr></thead><tbody>"
        (xref.dashboardsToDevices as List).each { Map d ->
            int n = (d.devices as List).size()
            String devs = (d.devices as List).collect { dlink(it.id as Long, it.name as String) }.join(", ")
            b << "<tr><td><b>${esc(d.name as String)}</b></td>"
            b << "<td>${n}</td>"
            b << "<td>${devs}</td></tr>"
        }
        b << "</tbody></table></div>"
    }
    b << closeCard()

    // Per-device detail table
    b << openCard("all", "📋 Per-device detail table (${xref.deviceCount})", true)
    b << "<div class=\"filter\"><input type=\"text\" data-filter=\"t_all\" placeholder=\"Filter devices…\"></div>"
    b << "<div class=\"tbl-wrap\"><table data-sortable id=\"t_all\"><thead><tr><th data-sd=\"asc\">Name</th><th>Type</th><th>Source</th><th data-t=\"n\">Apps</th><th data-t=\"n\">Dashboards</th><th>Parent app</th><th>Last activity</th></tr></thead><tbody>"
    if (xref.allDevices instanceof Map) {
        ((xref.allDevices as Map).values() as List).sort { x, y -> (x.name as String) <=> (y.name as String) }.each { Map d ->
            String src = (d.driverType == 'usr') ? "<span class=\"badge b-community\">Community</span>" : "<span class=\"badge b-builtin\">Built-in</span>"
            List apps = (d.appsUsing ?: []) as List
            List dashs = (d.dashboards ?: []) as List
            // Apps cell: render disabled subscribers struck-through + muted
            String appsCell
            if (apps) {
                appsCell = apps.take(4).collect { Map a ->
                    String inner = alink(a.id as Long, (a.label ?: a.name) as String)
                    a.disabled ? "<s class=\"muted\" title=\"App is disabled — subscription inactive\">${inner}</s>" : inner
                }.join(", ") + (apps.size() > 4 ? ", <span class=\"muted\">+${apps.size() - 4}</span>" : "")
            } else {
                appsCell = (d.parentApp == null) ? "<span class=\"warn\">⚠ unreferenced</span>" : "<span class=\"muted\">—</span>"
            }
            String dashsCell = dashs ? dashs.take(4).collect { '<a href="/installedapp/configure/' + (it.id as Long) + '" target="_blank">' + esc(it.name as String) + '</a>' }.join(", ") : "<span class=\"muted\">—</span>"
            Map pa = d.parentApp as Map
            String paCell = pa ? alink(pa.id as Long, (pa.label ?: pa.name) as String) : "<span class=\"muted\">—</span>"
            // Type cell: link to driver code editor for Community drivers (mirrors SPA Devices tab)
            String typeName = esc(d.deviceTypeName as String)
            String typeCell = (d.driverType == 'usr' && d.deviceTypeId)
                ? "<a href=\"/driver/editor/${d.deviceTypeId}\" target=\"_blank\">${typeName}</a>"
                : typeName
            b << "<tr><td>${dlink(d.id as Long, (d.label ?: d.name) as String)}</td>"
            b << "<td>${typeCell}</td>"
            b << "<td>${src}</td>"
            b << "<td data-sv=\"${apps.size()}\">${appsCell}</td>"
            b << "<td data-sv=\"${dashs.size()}\">${dashsCell}</td>"
            b << "<td>${paCell}</td>"
            b << "<td>${esc(d.lastActivityTime as String)}</td></tr>"
        }
    }
    b << "</tbody></table></div>"
    b << closeCard()

    // Failed footnote
    if (failed) {
        b << "<div class=\"card\" id=\"failed\"><div class=\"card-h crit\">Failed to fetch (${failed.size()})</div><div class=\"card-b muted\">"
        b << failed.collect { Map f -> "Device ${f.id}: ${esc(f.reason as String)}" }.join("<br>")
        b << "</div></div>"
    }

    // Inline sort + filter script
    b << '''<script>
(function(){
  function cmp(a,b,n){if(n){a=parseFloat(a)||0;b=parseFloat(b)||0;return a<b?-1:a>b?1:0;}a=String(a).toLowerCase();b=String(b).toLowerCase();return a<b?-1:a>b?1:0;}
  function getSv(td){return td.dataset.sv!=null?td.dataset.sv:td.textContent.trim();}
  function sortTable(tbl,col,dir){
    var th=tbl.tHead.rows[0].cells[col],n=th.dataset.t==='n';
    var tb=tbl.tBodies[0],rows=Array.prototype.slice.call(tb.rows);
    rows.sort(function(r1,r2){return cmp(getSv(r1.cells[col]),getSv(r2.cells[col]),n)*(dir==='desc'?-1:1);});
    rows.forEach(function(r){tb.appendChild(r);});
  }
  function setArrows(ths,col,dir){
    Array.prototype.forEach.call(ths,function(t,i){
      var a=t.querySelector('.arr');
      if(i===col){t.dataset.dir=dir;if(a)a.textContent=dir==='asc'?' ▲':' ▼';}
      else{delete t.dataset.dir;if(a)a.textContent=' ⇅';}
    });
  }
  document.querySelectorAll('table[data-sortable]').forEach(function(tbl){
    if(!tbl.tHead||!tbl.tBodies[0])return;
    var ths=tbl.tHead.rows[0].cells,defCol=-1,defDir='asc';
    Array.prototype.forEach.call(ths,function(th,i){
      var s=document.createElement('span');s.className='arr';s.textContent=' ⇅';th.appendChild(s);
      th.addEventListener('click',function(){
        var dir=th.dataset.dir==='asc'?'desc':(th.dataset.dir==='desc'?'asc':(th.dataset.t==='n'?'desc':'asc'));
        setArrows(ths,i,dir);sortTable(tbl,i,dir);
      });
      if(th.dataset.sd){defCol=i;defDir=th.dataset.sd;}
    });
    if(defCol>=0){setArrows(ths,defCol,defDir);sortTable(tbl,defCol,defDir);}
  });
  document.querySelectorAll('input[data-filter]').forEach(function(inp){
    var tbl=document.getElementById(inp.dataset.filter);if(!tbl||!tbl.tBodies[0])return;
    inp.addEventListener('input',function(){
      var q=inp.value.toLowerCase();
      Array.prototype.forEach.call(tbl.tBodies[0].rows,function(r){
        r.style.display=r.textContent.toLowerCase().indexOf(q)>=0?'':'none';
      });
    });
  });
})();
</script>'''

    b << "</body></html>"
    return b.toString()
}

private String openCard(String anchorId, String headerHtml, boolean openByDefault) {
    String openAttr = openByDefault ? " open" : ""
    return "<div class=\"card\" id=\"${anchorId}\"><details${openAttr}><summary class=\"card-h\">${headerHtml}</summary><div class=\"card-b\">"
}

private String closeCard() {
    return "</div></details></div>"
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
    String color = (severity == 'warn') ? 'var(--warn)' : (severity == 'crit') ? 'var(--crit)' : null
    String s = color ? " style=\"color:${color}\"" : ""
    return "<div class=\"m\"><div class=\"m-l\">${esc(label)}</div><div class=\"m-v\"${s}>${esc(value)}</div></div>"
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
    long fetchedAtMs = now()                                        // observe as close to response arrival as possible
    String scanId = data.scanId as String
    ConcurrentHashMap scan = AUDIT_SCANS[scanId]
    if (scan == null) return                                        // callback from prior abandoned scan

    Long deviceId = data.deviceId as Long
    try {
        if (resp?.status == 200) {
            Map fj = (Map) resp.json
            Map record = extractAuditFields(fj, deviceId)
            record.fetchedAtMs = fetchedAtMs                        // anchor for stuck-job timing (see buildCrossReference)
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
    } else if (inFlight == 0 && processed >= total) {
        finalizeAudit(scanId)
    }
}

/**
 * Finalize a completed scan: build cross-reference, render HTML, write to FileManager,
 * append to state.auditReports[] (FIFO trim), update state.audit snapshot, free memory.
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

    // Build cross-reference & attach raw devices for the per-device table
    Map xref = buildCrossReference(devices, startedAt)
    xref.allDevices = devices

    // Phase 4 enrichment: rooms, Z-Wave JS per-node, Hub Mesh per-device
    long enrichStart = now()
    xref.rooms = fetchRoomsForAudit()

    // Z-Wave JS per-node enrichment (only when Z-Wave JS stack is active)
    if (detectZwaveStack() == "js") {
        Map zwData = hubMapRequest(ZWAVE_DETAILS_PATH, "Z-Wave details (audit enrichment)", 10).with { it.ok ? it.data : null }
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
    Map hubMeshData = hubMapRequest(HUB_MESH_PATH, "Hub Mesh (audit enrichment)", 10).with { it.ok ? it.data : null }
    List linkedDevices = (hubMeshData?.linkedDevices as List) ?: []
    linkedDevices.each { Map ld ->
        Long devId = ld.id as Long
        Map record = (devId != null) ? (devices[devId] as Map) : null
        if (record == null) return
        Map ldState = fetchHubMeshDeviceState(devId)
        if (ldState) record.hubMeshState = ldState
    }
    logDebug "Audit Phase 4 enrichment finished in ${now() - enrichStart}ms"

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
    unsubscribe()
    unschedule()
    // clear session-scoped caches so config/hardware changes take effect immediately
    zwaveStackCache  = null   // re-detect Z-Wave stack on next use (handles user switching legacy ↔ JS)
    fwUpdateCache    = null   // force fresh cloud fetch on next dashboard render
    fwUpdateCacheAt  = null
    state.remove('controllerTypeCache') // evict per-device classification cache; rebuilds on next analysis pass
    apiTimings.clear()                  // drop stats for renamed/removed endpoints; fresh measurements from now
    runIn(1, 'syncUIForced')
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
        String cron
        if (interval < 60) {
            cron = "0 */${interval} * * * ?"
        } else {
            int hours = (interval / 60).toInteger()
            cron = hours >= 24 ? "0 0 0 * * ?" : "0 0 */${hours} * * ?"
        }
        schedule(cron, "createCheckpoint")
        logInfo "Automatic perf checkpoints scheduled every ${interval} minute(s)"
    }

    // v5.15.0: daily UI sync moved out of serveUI hot path. 03:17 local time, off-peak.
    schedule("0 17 3 * * ?", "scheduledUISync")
    logInfo "Daily UI sync scheduled at 03:17"
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

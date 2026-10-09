// Copyright (c) 2025-2026 PJ
// SPDX-License-Identifier: MIT

/**
 * Hub Inspector
 *
 * Comprehensive hub diagnostics: inventory, performance tracking, network analysis,
 * snapshot comparison, and exportable reports.
 
 *
 */

import com.hubitat.hub.domain.Hub
import groovy.transform.Field
import groovy.json.JsonOutput
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

@Field static final String CODE_VERSION = "6.1.7"

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
@Field static final String CLOUD_CALLS_PATH = "/logs/cloudCalls/json"   // 2.5.2.129+, 404 before
@Field static final String CLOUD_CALLS_MIN_FW = "2.5.2.129"
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
// Diagnostic Tool (port 8081): separate process that serves platform versions kept on the hub.
// These two reads answer without the tool's MAC login.
@Field static final String DIAG_TOOL_BASE = "http://127.0.0.1:8081"
@Field static final String DIAG_TOOL_VERSIONS_PATH = "/api/versions"
@Field static final String DIAG_TOOL_HUB_INFO_PATH = "/api/hubInfo"
@Field static final String MDNS_PATH = "/hub/mdnsDevices/json"
@Field static final String USER_BUNDLES_PATH = "/hub2/userBundles"
@Field static final String USER_LIBRARIES_PATH = "/hub2/userLibraries"
@Field static final String USER_APP_TYPES_PATH = "/hub2/userAppTypes"
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
// Minimum platform firmware Hub Inspector is developed and tested against. Older builds may
// lack endpoints/behaviours the app relies on; below this the Versions section shows a warning.
@Field static final String MIN_FW_SUPPORTED = "2.5.0"
@Field static final long   FW_UPDATE_CACHE_TTL_MS = 3600_000L

// ===== Device Usage Audit constants =====
@Field static final String FULL_JSON_PATH_PREFIX = "/device/fullJson/"
@Field static final int    AUDIT_MAX_INFLIGHT  = 8       // Hubitat platform cap on concurrent async HTTP per app
@Field static final int    AUDIT_WATCHDOG_SEC  = 120     // safety net if a callback is genuinely lost
@Field static final long   AUDIT_STALE_MS      = 600_000 // 10 min — anything older is force-cleared on app entry
@Field static final double AUDIT_FAIL_RATIO    = 0.10    // > 10% per-device failures → mark scan errored
@Field static final int    AUDIT_ATTEMPT_CAP   = 2       // one retry per device, then record it failed
@Field static final int    AUDIT_REAP_INTERVAL_SEC = 10  // how often the claim reaper runs while a scan is live
@Field static final long   AUDIT_REAP_DEADLINE_MS  = 25_000 // > the 15s request timeout, so a slow-but-live fetch is never reaped

// Per-scan in-memory state. Each entry is itself a ConcurrentHashMap with keys:
//   total (Integer), startedAt (Long),
//   inFlight (AtomicInteger), processed (AtomicInteger),
//   pending (ConcurrentLinkedQueue — Long id, or [id:, attemptCount:] for a requeued retry),
//   devices (ConcurrentHashMap<Long, Map>),
//   failed (ConcurrentHashMap<Long, String>),
//   claims (ConcurrentHashMap<Long, Map> — one per dispatched-but-unresolved attempt),
//   tokenSeq (AtomicInteger), finalizeGuard (AtomicInteger)
@Field static final ConcurrentHashMap<String, ConcurrentHashMap> AUDIT_SCANS = new ConcurrentHashMap<>()
@Field static volatile Map lastAuditResult = null
// [scanId, status, processed] of the last finalized scan. finalizeAudit drops the scan from
// AUDIT_SCANS before its state.audit write commits; this bridges that gap for status reads.
@Field static volatile Map lastFinalizedAudit = null

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

// Connection type constants — how the hub reaches a device. A device commissioned INTO the hub (keys
// exchanged; a member of a network/fabric the hub controls) reports its specific mechanism: the radio
// for Zigbee/Z-Wave/Matter/BLE, or HomeKit for HAP-commissioned accessories. Contrast
// lan_direct/lan_bridge/cloud, where the hub is just a client of an autonomous IP device (Kasa,
// Shelly, a Hue bridge, a cloud account) — nothing was commissioned into the hub.
@Field static final String CONN_ZIGBEE = "zigbee"
@Field static final String CONN_ZWAVE = "zwave"
@Field static final String CONN_MATTER = "matter"
@Field static final String CONN_BLUETOOTH = "bluetooth"
@Field static final String CONN_HOMEKIT = "homekit"
@Field static final String CONN_LAN_DIRECT = "lan_direct"
@Field static final String CONN_LAN_BRIDGE = "lan_bridge"
@Field static final String CONN_CLOUD = "cloud"
@Field static final String CONN_VIRTUAL = "virtual"
@Field static final String CONN_HUBMESH = "hubmesh"
@Field static final String CONN_OTHER = "other"

@Field static final long ONE_DAY_MS = 86400000
@Field static final int API_TIMING_WINDOW = 20

// System alert threshold defaults — these become the defaults for user-configurable settings.
// Temperature: Amlogic's A113X reference kernel starts CPU throttling at 70 °C and holds 80 °C.
@Field static final int    DEFAULT_WARN_MEM_MB   = 100
@Field static final int    DEFAULT_WARN_MEM_MB_C8PRO = 200   // 2 GB RAM, normally about 1 GB free
@Field static final int    DEFAULT_CRIT_MEM_MB   = 75
@Field static final double DEFAULT_WARN_CPU_LOAD = 4.0
@Field static final double DEFAULT_CRIT_CPU_LOAD = 8.0
@Field static final int    DEFAULT_WARN_TEMP_C   = 65
@Field static final int    DEFAULT_CRIT_TEMP_C   = 80

// In-memory API response time tracking (reset on hub reboot). Concurrent endpoints read and
// write it, so the reference is final and every access, including the clear in updated(), holds
// its monitor: each entry's sample list is mutated in place, which the map alone can't protect.
@Field static final ConcurrentHashMap<String, Map> apiTimings = new ConcurrentHashMap<>()

// In-memory caches (survive within a JVM session; cleared on hub reboot/app reload)
@Field static volatile String  uiVersionCache
@Field static volatile String  zwaveStackCache
@Field static volatile String  hubModelCache   // no TTL: the hub model can't change while the app runs
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
@Field static final long       HUB_DATA_CACHE_TTL_MS = 30_000L
@Field static final long       SYSTEM_RESOURCES_CACHE_TTL_MS = 10_000L
// apiLive polls every 30s by default and fans out to 5 hub HTTP calls. The slow-changing ones
// (temperature, databaseSize, cpuInfo, loadThreshold) carry longer TTLs to spare the hub.
// cpuInfo also carries the 1-minute load average, so it stays on the 60 s tier.
@Field static final long       TEMPERATURE_CACHE_TTL_MS   = 60_000L
@Field static final long       DATABASE_SIZE_CACHE_TTL_MS = 60_000L
@Field static final long       CPU_INFO_CACHE_TTL_MS      = 60_000L
@Field static final long       CLOUD_CALLS_CACHE_TTL_MS   = 60_000L
@Field static final long       LOAD_THRESHOLD_CACHE_TTL_MS = 300_000L
// /hub2/networkConfiguration as read by getAlertSignals(): the alert fields (hasEthernet, hasWiFi,
// restartBonjourOnSchedule) change only when the user edits Network Setup. A Network tab load
// refreshes the slot with its own uncached read.
@Field static final long       NETWORK_CONFIG_CACHE_TTL_MS = 300_000L
// Optional integration-overrides file: re-read at most this often so an uploaded/edited file is
// picked up without a full Done. updated()/apiClearCache() reset it for an immediate reload.
@Field static final long       INTEGRATION_OVERRIDES_CACHE_TTL_MS = 300_000L

// Unified session-scoped TTL cache (Tier 2 — see ARCHITECTURE.md "Caching Strategy").
// Key → [data: Object, at: Long]. Wiped on reboot/code push; cleared wholesale in updated().
// Fetch closures MUST return null (not an empty map) on failure so misses are never cached.
@Field static final ConcurrentHashMap<String, Map> TTL_CACHE = new ConcurrentHashMap<>()

private Object cachedFetch(String key, long ttlMs, Closure fetch) {
    Object hit = cachePeek(key, ttlMs)
    if (hit != null) return hit
    Object data = fetch()
    if (data != null) TTL_CACHE[key] = [data: data, at: now()]
    return data
}

private Object cachePeek(String key, long ttlMs) {
    Map slot = TTL_CACHE[key]
    return (slot != null && (now() - (slot.at as long)) < ttlMs) ? slot.data : null
}

private void cachePut(String key, Object data) {
    if (data != null) TTL_CACHE[key] = [data: data, at: now()]
}

// Start time (epoch ms) of the in-flight GitHub version request, 0 when none. The request counts
// as pending only for GITHUB_VERSION_PENDING_MS (2x its timeout), so a callback that never arrives
// can't block version checks until the next push or reboot.
@Field static volatile long githubVersionRefreshStartedMs = 0L
@Field static final int    GITHUB_VERSION_TIMEOUT_S = 10
@Field static final long   GITHUB_VERSION_PENDING_MS = 20_000L   // 2x GITHUB_VERSION_TIMEOUT_S
// Green badge appended to the app label (visible in the Apps list) when a newer release is
// published on GitHub. UPDATE_BADGE_RE strips any prior badge so re-applying is idempotent. The
// span tags are optional and the match repeats so it also clears remnants when the "Assign a name"
// input on Done saves the label with its HTML stripped to bare "update available" text — otherwise
// each refresh would stack a fresh badge on the plain remnant ("... update available update available").
@Field static final String UPDATE_AVAILABLE_BADGE = ' <span style="color:green; font-weight:bold;">update available</span>'
@Field static final java.util.regex.Pattern UPDATE_BADGE_RE = ~/(?i)(?:\s*(?:<span\b[^>]*>)?\s*update available\s*(?:<\/span>)?)+\s*$/

// Built-in integration overrides: lowercase keyword → [conn, name]. These are Hubitat-native
// parent-managed integrations whose devices arrive without a parentAppId in the bulk list (so the
// parent-app derivation can't see them and the deep-enrich pass never runs on them). Each entry
// supplies both the connection type the isNetwork derivation can't infer (the bridges report
// isNetwork=true but front their children → lan_bridge; AirPlay is isNetwork=false but local →
// lan_direct) AND the integration name that keeps the devices grouped. built-in vs community still
// comes from the hub's own appInfo.user flag.
// User-discovered exceptions go in the File Manager config file (loaded + overlaid by
// getIntegrationOverrides()).
// Entries are ordered longest-first to avoid false positives. LinkedHashMap preserves insertion
// order, which is the iteration order used by lookupIntegration().
// Each of these is a genuine parent-managed integration whose devices arrive in the bulk devicesList
// with no parentAppId, so they classify via branch 2b. The name keeps them grouped as one integration
// (without it branch 2b would treat them as standalone and drop them from the integration breakdown);
// the conn corrects the connection type the isNetwork derivation can't infer. HomeKit's connection is
// "homekit" (HAP-commissioned) and it's also the HomeKit Controller integration.
@Field static final Map INTEGRATION_OVERRIDES = [
    "philips hue" : [conn: "lan_bridge", name: "Philips Hue"],
    "hue bridge"  : [conn: "lan_bridge", name: "Philips Hue"],
    "airplay"     : [conn: "lan_direct", name: "AirPlay"],
    "lutron"      : [conn: "lan_bridge", name: "Lutron"],
    "bond"        : [conn: "lan_bridge", name: "Bond"],
    "homekit"     : [conn: "homekit",    name: "HomeKit"],
]

// Built-in Hubitat cloud-polling DEVICE drivers: standalone cloud clients with no parent app, no
// radio, and isNetwork=false — every derivation signal is absent, so classifyDevice would drop them
// into "Other". They're enumerated here by built-in driver type name (lowercased) → integration
// display name. Only Hubitat-bundled drivers belong here; the driverIsBuiltin guard in classifyDevice
// prevents a same-named community driver from matching. Community cloud/LAN devices are handled by the
// File Manager override file (matched on driver type name), not this table.
@Field static final Set<String> BUILTIN_CLOUD_DRIVERS = [
    "openweathermap",
    "ecobee thermostat",
    "pushover driver",
    "mobile app device",
]


// User-customizable integration-overrides config file (optional, File Manager)
@Field static final String INTEGRATION_OVERRIDES_FILE = "hub_inspector_integration_overrides.json"
// Pre-6.1.2 name, still read when the new file is absent
@Field static final String LEGACY_INTEGRATION_OVERRIDES_FILE = "hub_diagnostics_integration_overrides.json"

// Valid conn values; used to reject unknown strings from the user config file
@Field static final Set<String> VALID_CONN = [
    "zigbee", "zwave", "matter", "bluetooth", "homekit",
    "lan_direct", "lan_bridge", "cloud", "virtual", "hubmesh", "other"
] as Set

// File names for persistence
@Field static final String SNAPSHOTS_FILE = "hub_diagnostics_snapshots.json"
// v5.33.0 split-file checkpoint storage:
//   index file: small list of slim records (one per checkpoint) + detailFile pointer
//   detail files: one per checkpoint, named with timestampMs, holds full content
@Field static final String CHECKPOINT_INDEX_FILE = "hub_diagnostics_checkpoints_index.json"
// Hourly rollups: [hourStartMs, tempMinC, tempAvgC, tempMaxC, tempSamples, databaseMB, cloudCalls],
// newest last, 30 days kept. Temperature fields are null for an hour with no samples; databaseMB is
// read once per rollup and set on the last completed hour only. cloudCalls is {appId: count} from
// the hub's since-boot buckets, null for an hour that ended before boot, absent on older rows.
@Field static final String HOURLY_FILE = "hub_diagnostics_hourly.json"
@Field static final int    HOURLY_KEEP = 720
@Field static final int    TEMP_SAMPLE_CAP  = 8640   // 30 days of 5-minute samples
// In-memory 5-minute temperature samples [ms, tempC] per app instance. Lost on hub reboot or
// code push; the SPA falls back to HOURLY_FILE averages for hours before the first sample.
// Copy-on-write: the sampler replaces the list.
@Field static final ConcurrentHashMap<Long, List> TEMP_SAMPLES = new ConcurrentHashMap<>()

// Device enrichment cache (see enrichDevices()), working copy: app.id -> deviceId -> entry.
// /api/dashboard, /api/devices, snapshots and audit finalize can run enrichDevices() at the same
// time (the app is not singleThreaded), and a read-modify-write of state.controllerTypeCache lost
// the other pass's entries. Each pass now puts its entries into this concurrent map, then writes
// state.controllerTypeCache as one whole-value snapshot of it. state stays the durable copy: the
// map is loaded from it on first use after a reboot or push. If two passes' state commits land in
// the wrong order, the persisted copy can miss a few entries; the working copy keeps them, and
// after a reboot they cost one fullJson fetch each. apiClearCache() and updated() clear both.
@Field static final ConcurrentHashMap<Long, ConcurrentHashMap<String, Object>> CONTROLLER_TYPE_CACHE = new ConcurrentHashMap<>()
@Field static final String CHECKPOINT_DETAIL_PREFIX = "hub_diagnostics_checkpoint_"
@Field static final String PERFORMANCE_COMPARISON_FILE = "hub_diagnostics_performance_comparison.json"
// Scheduled snapshots and checkpoints: cron fires JITTER_BASE_MIN past the slot, then a
// random 1..JITTER_MAX_S delay, so each run lands between :03 and :07.
@Field static final int    JITTER_BASE_MIN = 3
@Field static final int    JITTER_MAX_S    = 240

@Field static final String IMPORT_URL_APP = "https://raw.githubusercontent.com/iamtrep/hubitat/refs/heads/main/apps/HubInspector/HubInspector.groovy"
@Field static final String IMPORT_URL_WEB = "https://raw.githubusercontent.com/iamtrep/hubitat/refs/heads/main/apps/HubInspector/hub_inspector_ui.html"
// File Manager names keep the pre-rename "hub_diagnostics_" prefix so existing installs keep their files.
@Field static final String UI_FILE = "hub_diagnostics_ui.html"
@Field static final String LEGACY_APP_NAME = "Hub Diagnostics"
@Field static final String APP_NAME = "Hub Inspector"


// Maps controllerType values (from the device/fullJson `device.controllerType` field) to connection type constants.
// Actual observed values: ZGB=Zigbee, MAT=Matter, LNK=HubMesh, HKC=HomeKit, BLE=Bluetooth.
// Used only as a last-resort fallback when parentApp is absent from fullJson.
@Field static final Map CONTROLLER_TYPE_CONN = [
    "ZGB": "zigbee",
    "ZWV": "zwave",
    "MAT": "matter",
    "BLE": "bluetooth",
    "HKC": "homekit",
    "LNK": "hubmesh",
    "NET": "lan_direct",
    "CLO": "cloud",
    "VIR": "virtual",
]

definition(
    name: "Hub Inspector",
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
    path('/api/code')             { action: [GET: 'apiCode'] }

    // ===== App-owned GETs =====
    // Routes that read app-owned state (snapshots, checkpoints, performance
    // history, telemetry, settings) or compose data only the app can produce.
    path('/api/performance')      { action: [GET: 'apiPerformance'] }
    path('/api/snapshots')        { action: [GET: 'apiSnapshots'] }
    path('/api/health/ranges')    { action: [GET: 'apiHealthRanges'] }
    path('/api/snapshot/view')    { action: [GET: 'apiSnapshotView'] }
    path('/api/stats')            { action: [GET: 'apiStats'] }
    path('/api/version/check')    { action: [GET: 'apiVersionCheck'] }
    path('/api/reports')          { action: [GET: 'apiReports'] }

    // ===== App-owned mutations =====
    // Stateful writes and orchestration. App-owned by definition.
    path('/api/snapshot/create')     { action: [POST: 'apiCreateSnapshot'] }
    path('/api/snapshot/delete')     { action: [POST: 'apiDeleteSnapshot'] }
    path('/api/snapshots/clear')     { action: [POST: 'apiClearSnapshots'] }
    path('/api/firmware/refresh')    { action: [POST: 'apiFirmwareRefresh'] }
    path('/api/checkpoint/create')   { action: [POST: 'apiCreateCheckpoint'] }
    path('/api/checkpoint/delete')   { action: [POST: 'apiDeleteCheckpoint'] }
    path('/api/checkpoints/clear')   { action: [POST: 'apiClearCheckpoints'] }
    path('/api/performance/compare') { action: [POST: 'apiPerformanceCompare'] }
    path('/api/ui/sync')             { action: [POST: 'apiSyncUI'] }
    path('/api/report/save')         { action: [POST: 'apiSaveReport'] }
    path('/api/report/template')     { action: [GET:  'apiReportTemplate'] }
    path('/api/settings')            { action: [GET: 'apiGetSettings', POST: 'apiUpdateSettings'] }
    path('/api/cache/clear')         { action: [POST: 'apiClearCache'] }
    path('/api/reinit')              { action: [POST: 'apiReinit'] }

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
                paragraph "Hub Inspector was unable to automatically enable OAuth. Please enable it manually:"
                paragraph "1. Go to <b>Apps Code</b> and open <b>Hub Inspector</b>.\n" +
                          "2. Click the <b>three-dot (\u22EE) menu</b> at the top right.\n" +
                          "3. Select <b>OAuth</b>, click <b>Enable oAuth in app</b>, then <b>Update</b>.\n" +
                          "4. Return here and re-open the app."
            }
        }
    }

    if (isFirstRun) {
        return dynamicPage(name: "dashboardPage", title: "Welcome to Hub Inspector", install: true, uninstall: true) {
            section("Finalize Installation") {
                paragraph "Thank you for installing Hub Inspector! To finish the setup, please click <b>Done</b> at the bottom of this page."
                paragraph "This will initialize the database, sync the user interface, and schedule performance tracking."
                paragraph "<b>Once you click Done, re-open Hub Inspector from your Apps list to access the full dashboard.</b>"
            }
            section("Initial Check") {
                paragraph "<b>OAuth Status:</b> <span style='color:green; font-weight:bold;'>Enabled (OK)</span>"
                paragraph "<b>UI Component:</b> ${getUIVersion() == "Unknown" ? "Pending download..." : "Ready"}"
            }
        }
    }

    dynamicPage(name: "dashboardPage", title: "Hub Inspector", install: true, uninstall: true) {
        String uiVer = getUIVersion()
        boolean appUpdateNeeded = isNewer(uiVer, CODE_VERSION)
        String remoteVersion = checkGithubVersion()
        boolean githubUpdateAvailable = remoteVersion && isNewer(remoteVersion, CODE_VERSION)

        String hubFw = getHubFirmwareVersion()
        boolean firmwareUnsupported = hubFw && !isVersionAtLeast(hubFw, MIN_FW_SUPPORTED)

        section("Versions") {
            if (firmwareUnsupported) {
                paragraph "<span style='color:red; font-weight:bold;'>\u26A0 Unsupported hub firmware:</span> Hub Inspector is developed and tested against platform ${MIN_FW_SUPPORTED} and later \u2014 your hub runs ${hubFw}. Some features may be unavailable or behave unexpectedly. Updating the hub firmware is recommended."
            }
            if (githubUpdateAvailable) {
                String editorPath = getAppEditorPath()
                String importLink = editorPath ? "<a href='${editorPath}' target='_blank'>Open Apps Code</a> and use Import to update." : "Update via Apps Code using Import."
                paragraph "<span style='color:orange; font-weight:bold;'>\u26A0 New version available:</span> v${remoteVersion} (you have v${CODE_VERSION}). ${importLink}"
            }
            if (appUpdateNeeded) {
                paragraph "<span style='color:red; font-weight:bold;'>\u26A0 Update Recommended:</span> A newer UI version (${uiVer}) is active than this App code (${CODE_VERSION}). Please update the Groovy App Code in Hubitat."
            }
            paragraph "<b>App Version:</b> ${CODE_VERSION}\n<b>UI Version:</b> ${uiVer}\n<b>Hub Firmware:</b> ${hubFw ?: 'Unknown'}"
            if (!githubUpdateAvailable) {
                String editorPath = getAppEditorPath()
                if (editorPath) {
                    paragraph "<a href='${editorPath}' target='_blank'>Open App Code Editor</a> — update the Groovy source code via Import"
                }
            }
        }

        section("Dashboard") {
            String dashboardUrl = "${fullLocalApiServerUrl}/ui.html?access_token=${state.accessToken}"
            // Rendered as a raw anchor, not href(): on 2.5.1.147 `style: "external"` compiles to
            // onClick="openWindow(this)" with a hardcoded width=800 feature string — a cramped,
            // popup-blockable window, never a tab. Same technique as the App Code Editor link above.
            paragraph "<a href='${dashboardUrl}' target='_blank'>Open Dashboard</a> — interactive diagnostic dashboard (opens in a new tab)"
        }

        section("Documentation") {
            href url: "https://github.com/iamtrep/hubitat/tree/main/apps/HubInspector", title: "Documentation & README",
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
            paragraph "Free memory and temperature vary from hub to hub. <b>Show observed ranges</b> on the dashboard's Settings tab lists this hub's normal readings; set the warnings just outside them. CPU load is a load average: 4.0 means all four cores are busy."
            input "warnMemMb",   "number",  title: "Free memory warning (MB)",    defaultValue: defaultWarnMemMb(),    range: "10..2000", required: true
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
            paragraph "Most integrations need no setup — Hub Inspector derives the connection type from the " +
                "hub's own LAN flag and the display name from the parent app. To correct a connection type the hub " +
                "can't infer (e.g. a LAN bridge, or a local device the hub flags as cloud), create " +
                "<b>${INTEGRATION_OVERRIDES_FILE}</b> in File Manager. " +
                "Keys are substrings matched case-insensitively against the device's parent-app name, or — for a " +
                "standalone device with no parent app — its driver type name; " +
                "valid <code>conn</code> values are: homekit, lan_direct, lan_bridge, cloud, virtual, hubmesh, other " +
                "(the radios zigbee/zwave/matter/bluetooth are auto-detected). " +
                "Add <code>conn</code> alone to fix only the connection type (the device stays standalone — " +
                "omitted from the integration breakdown); add a <code>name</code> too only when the device belongs " +
                "to an integration you want grouped and labeled. " +
                "Save this page after uploading the file to apply the changes. " +
                "The file format and examples are in the " +
                "<a href='https://github.com/hubitrep/hubitat/blob/main/HubInspector/README.md#customizing-classification-with-the-override-file' target='_blank'>README</a>."
        }

        section("Logging") {
            input name: "txtEnable", type: "bool", title: "Enable info logging", defaultValue: true
            input name: "debugLogging", type: "bool", title: "Enable debug logging", defaultValue: false, submitOnChange: true
            if (debugLogging) {
                input name: "traceEnable", type: "bool", title: "Enable trace logging", defaultValue: false
            }
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
    boolean newAppVersion = state.version != CODE_VERSION
    checkVersion()
    if (!checkOAuth()) {
        return render(status: 403, contentType: 'text/plain', data: 'OAuth is not enabled for this app. Please enable it in the Hubitat App Settings.')
    }

    // v5.15.0: removed inline sync check from the hot path. Daily UI sync runs as a scheduled
    // job (see initialize). Emergency sync still triggers below if the file is missing.

    try {
        String html = loadUITemplate()
        if (!html) {
            logError "${UI_FILE} missing from hub. Attempting emergency sync..."
            if (syncUIBlocking()) html = loadUITemplate()
        } else if (newAppVersion && !html.contains("const CODE_VERSION = \"${CODE_VERSION}\"")) {
            // First page load after a code update: fetch the matching UI now so this load
            // doesn't serve the previous version. On failure the old UI is served as before.
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
    long t = now()
    if (t - lastCheck >= 3600000 && t - githubVersionRefreshStartedMs >= GITHUB_VERSION_PENDING_MS) {
        githubVersionRefreshStartedMs = t
        asynchttpGet('githubVersionCallback', [uri: IMPORT_URL_APP, contentType: "text/plain", timeout: GITHUB_VERSION_TIMEOUT_S])
    }
    return state.lastGithubVersion
}

void githubVersionCallback(resp, data) {
    githubVersionRefreshStartedMs = 0L
    if (resp.hasError() || resp.status != 200) {
        logNet "GitHub version check failed: HTTP ${resp.status}"
        return
    }
    try {
        String text = resp.data ?: ""
        java.util.regex.Matcher m = text =~ /CODE_VERSION\s*=\s*"([^"]+)"/
        if (m.find()) {
            state.lastGithubVersion = m.group(1)
            state.lastGithubVersionCheck = now()
            refreshUpdateLabel()
        }
    } catch (Exception e) {
        logNet "GitHub version callback error: ${e.message}"
    }
}

Map apiSyncUI() {
    checkVersion()
    logInfo "Manual UI sync requested via API..."
    boolean success = syncUIBlocking()
    return jsonResponse([success: success])
}

// Re-run the lifecycle as if the user had clicked Done — arms schedules, re-subscribes, runs
// settings migrations, and refreshes the update-label badge. A code push alone does NOT fire
// updated()/initialize(), so the deploy chain calls this per hub after pushing new code.
Map apiReinit() {
    logCfg "Reinitialize requested via API (running updated())"
    updated()
    return jsonResponse([success: true, version: CODE_VERSION])
}

Map apiVersionCheck() {
    checkVersion()
    String latestVersion = checkGithubVersion()
    if (!latestVersion) return jsonResponse([error: "Unable to check for updates"])

    boolean updateAvailable = isNewer(latestVersion, CODE_VERSION)
    return jsonResponse([
        currentVersion: CODE_VERSION,
        latestVersion: latestVersion,
        updateAvailable: updateAvailable,
        editorPath: getAppEditorPath()
    ])
}

// Reflect GitHub update status in the app label so the Apps list shows a green "update available"
// badge without opening the app. Idempotent: strips any prior badge before deciding whether to
// re-append, so it never stacks and clears itself once the installed code catches up to GitHub.
private void refreshUpdateLabel() {
    try {
        String current = (app.getLabel() ?: "") as String
        String base = stripUpdateBadge(current)
        String remote = state.lastGithubVersion as String
        boolean outdated = remote && isNewer(remote, CODE_VERSION)
        String desired = outdated ? (base + UPDATE_AVAILABLE_BADGE) : base
        if (current != desired) {
            app.updateLabel(desired)
            logDebug "App label ${outdated ? "badged 'update available' (remote v${remote})" : 'badge cleared'}"
        }
    } catch (Exception e) {
        logDebug "refreshUpdateLabel error: ${e.message}"
    }
}

private String stripUpdateBadge(String label) {
    return label ? UPDATE_BADGE_RE.matcher(label).replaceAll('') : label
}

// Renamed from Hub Diagnostics in 6.0.0: carry the default label over, keeping any update badge.
// A label the user chose is left alone.
private void migrateLegacyLabel() {
    String current = (app.getLabel() ?: "") as String
    if (stripUpdateBadge(current) != LEGACY_APP_NAME) return
    app.updateLabel(APP_NAME + current.substring(LEGACY_APP_NAME.length()))
    logCfg "App label renamed from ${LEGACY_APP_NAME}"
}

// /hub2/hubData (hub alerts, model, cloud-controller flag) takes about a second to build and
// several endpoints read it, so one page load would fetch it repeatedly without this.
Map fetchHubData() {
    return (Map) cachedFetch('hubData', HUB_DATA_CACHE_TTL_MS) {
        Map r = hubMapRequest(HUB_DATA_PATH, "hub data", 10)
        return r.ok ? r.data : null
    }
}

/**
 * Build a request-scoped shared cache so downstream getXxxData methods reuse common datasets
 * instead of re-fetching them. Pre-fix (v5.13.x), a single /api/dashboard call hit /hub2/hubData
 * twice (getHubInfo + fetchHubAlerts via getAlertSignals) and fetched system resources twice
 * (once directly, once via getAlertSignals fallback). With the shared cache populated, both
 * are fetched once and reused. Radio and runtime data stay out: their readers go through the
 * cross-request TTL cache instead.
 */
private Map buildSharedCache() {
    Map shared = [:]
    shared.hubData     = fetchHubData()
    shared.resources   = fetchSystemResources()
    shared.temperature = fetchTemperature()
    shared.databaseSize = fetchDatabaseSize()
    shared.hubAlerts   = fetchHubAlerts(shared.hubData as Map)
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
Map apiDashboard() {
    checkVersion()
    return timed("dashboard") { getDashboardData(buildSharedCache()) }
}

String getUIVersion() {
    if (uiVersionCache) return uiVersionCache
    try {
        byte[] htmlBytes = downloadHubFile(UI_FILE)
        if (htmlBytes) {
            String html = new String(htmlBytes, 'UTF-8')
            java.util.regex.Matcher m = (html =~ /const CODE_VERSION = "([^"]+)"/)
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
Map apiDevices() {
    checkVersion()
    return timed("devices") { getDevicesData() }
}

// Aggregator: joins apps list with runtime stats; surfaces parent/child structure.
Map apiApps() {
    checkVersion()
    return timed("apps") { getAppsData() }
}

// Aggregator: one response for the Code tab from four hub listings (app, driver, bundle and
// library types) plus the hub variables, each fail-soft on its own.
Map apiCode() {
    checkVersion()
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

// Aggregator: normalizes the radio, mesh, Matter and Hub Mesh payloads into one response.
Map apiNetwork() {
    checkVersion()
    return timed("network") {
        // Network tab needs hubData (for fetchSecurityInfo's cloudController flag); rest is fetched by analyzeNetwork
        Map shared = [:]
        shared.hubData = fetchHubData()
        getNetworkData(shared)
    }
}

// Aggregator: cross-resource health summary with alert shaping.
Map apiHealth() {
    checkVersion()
    return timed("health") { getHealthData(buildSharedCache()) }
}

// Aggregator: parses a hub text endpoint into a stable structured payload.
Map apiHealthHistory() {
    checkVersion()
    List memHistory = fetchMemoryHistory()
    return jsonResponse([dataPoints: memHistory ?: [], temperature: tempSamples(), hourly: loadHourly()])
}

// Memory/CPU/temperature samples for the Settings "observed ranges" panel: the hub's since-boot
// history plus the resource fields of stored checkpoints and snapshots. The SPA computes the ranges.
Map apiHealthRanges() {
    checkVersion()
    return timed("healthRanges") {
        [
            history:     (fetchMemoryHistory() ?: []).collect { Map p -> [time: p.time, timeMs: p.timeMs, freeOS: p.freeOS, cpu: p.cpuLoad] },
            temperature:       tempSamples(),
            hourly:            loadHourly(),
            checkpoints: loadCheckpointIndex().collect { Map c ->
                [ts: c.timestampMs, freeOS: c.resources?.freeOSMemory, cpu: c.resources?.cpuAvg5min, temperature: c.temperature,
                 interval: c.interval] },
            snapshots:   loadSnapshots().collect { Map sn ->
                [ts: sn.timestampMs, freeOS: sn.systemHealth?.memory?.freeOSMemory, cpu: sn.systemHealth?.memory?.cpuAvg5min,
                 temperature: sn.systemHealth?.temperature] }
        ]
    }
}

// Aggregator on the hot path (polled by the SPA every few seconds):
// consolidates polling and centralizes fail-soft semantics.
Map apiLive() {
    checkVersion()
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
    checkVersion()
    return timed("performance") { getPerformanceData() }
}

Map apiZigbeeScan() {
    checkVersion()
    long start = now()
    Map result = runZigbeeChannelScan()
    long elapsed = now() - start
    logDebug "apiZigbeeScan completed in ${elapsed}ms"
    recordApiTiming("zigbeeScan", elapsed)
    boolean ok = result.error == null
    return jsonResponse([success: ok, scan: result, elapsedMs: elapsed])
}

Map apiPerformanceCompare() {
    checkVersion()
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
        Map zwaveData = fetchRadioForCheckpoint(true) ?: [:]
        Map zigbeeData = fetchRadioForCheckpoint(false) ?: [:]
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

Map apiSnapshots() {
    checkVersion()
    return jsonResponse(getSnapshotsData())
}

Map apiSnapshotView() {
    checkVersion()
    String idxStr = params.index ?: "-1"
    if (!idxStr.isInteger()) return jsonResponse([error: "Invalid snapshot index"])
    int idx = idxStr.toInteger()
    List snapshots = loadSnapshots()
    if (idx < 0 || idx >= snapshots.size()) return jsonResponse([error: "Invalid snapshot index"])
    Map snap = snapshots[idx]

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
            zwave:   snapNet.zwave   && !snapNet.zwave.error   ? [enabled: snapNet.zwave.enabled,   region: snapNet.zwave.region,   nodeCount: (snapNet.zwave.zwDevices ?: [:]).size(), zwaveJSVersion: snapNet.zwave.zwaveJSVersion] : null,
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
    checkVersion()
    // C1: createSnapshot() is void and swallows save failures (writeFile catches internally), so a
    // failed live snapshot would otherwise return success and the SPA would diff against a stale
    // snapshot with no error. Detect real success by checking the newest snapshot's timestampMs
    // actually changed — robust even at the retention cap, where the total count stays flat.
    Long prevNewestMs = loadSnapshots()?.getAt(0)?.timestampMs as Long
    createSnapshot()
    List snapshots = loadSnapshots()
    Long newestMs = snapshots?.getAt(0)?.timestampMs as Long
    if (newestMs == null || newestMs == prevNewestMs) {
        logWarn "apiCreateSnapshot: live snapshot did not persist (newest timestampMs unchanged) — creation failed"
        return jsonResponse([success: false, error: "Failed to create live snapshot — check hub logs", snapshotCount: snapshots?.size() ?: 0])
    }
    return jsonResponse([success: true, snapshotCount: snapshots.size()])
}

Map apiDeleteSnapshot() {
    checkVersion()
    String idxStr = params.index ?: "-1"
    if (!idxStr.isInteger()) return jsonResponse([success: false, error: "Invalid index"])
    int idx = idxStr.toInteger()
    if (idx < 0) return jsonResponse([success: false, error: "Invalid index"])
    deleteSnapshot(idx)
    return jsonResponse([success: true])
}

Map apiCreateCheckpoint() {
    checkVersion()
    if (!createCheckpoint()) return jsonResponse([success: false, error: "Checkpoint creation failed or already in progress"])
    return jsonResponse([success: true, checkpointCount: loadCheckpointIndex().size()])
}

Map apiDeleteCheckpoint() {
    checkVersion()
    String idxStr = params.index ?: "-1"
    if (!idxStr.isInteger()) return jsonResponse([success: false, error: "Invalid index"])
    int idx = idxStr.toInteger()
    if (idx < 0) return jsonResponse([success: false, error: "Invalid index"])
    deleteCheckpoint(idx)
    return jsonResponse([success: true])
}

Map apiClearCheckpoints() {
    checkVersion()
    clearAllCheckpoints()
    return jsonResponse([success: true])
}

Map apiClearSnapshots() {
    checkVersion()
    clearAllSnapshots()
    return jsonResponse([success: true])
}

// Drops the cached Diagnostic Tool versions so the next read shows a platform download
// the SPA just made.
Map apiFirmwareRefresh() {
    checkVersion()
    TTL_CACHE.remove('diagToolVersions')
    return jsonResponse([success: true])
}

Map apiReports() {
    checkVersion()
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
    checkVersion()
    String html = loadUITemplate()
    if (!html) return render(status: 404, contentType: 'application/json', data: '{"error":"SPA template not found in File Manager"}')
    return render(contentType: 'text/html', data: html)
}

// Thin file-write endpoint. Body is JSON: {filename, html}. The SPA assembles the
// report client-side (parallel data fetches, template injection, placeholder strip)
// and just hands the finished bytes here for FileManager persistence.
Map apiSaveReport() {
    checkVersion()
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
    checkVersion()
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
        warnMemMb:             (settings.warnMemMb   ?: defaultWarnMemMb())    as int,
        critMemMb:             (settings.critMemMb   ?: DEFAULT_CRIT_MEM_MB)   as int,
        warnCpuLoad:           (settings.warnCpuLoad ?: DEFAULT_WARN_CPU_LOAD) as double,
        critCpuLoad:           (settings.critCpuLoad ?: DEFAULT_CRIT_CPU_LOAD) as double,
        warnTempC:             warnTempCValue(),
        critTempC:             critTempCValue(),
        temperatureScale:      getTemperatureScale(),
        debugLogging:            settings.debugLogging ?: false,
        obfuscateForumExport:    settings.obfuscateForumExport ?: false,
        liveRefreshSec:          (settings.liveRefreshSec ?: 30) as int,
        cacheSize:               controllerTypeCache().size()
    ])
}

Map apiUpdateSettings() {
    checkVersion()
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
            updateDecimalSetting(key, numStr.toBigDecimal())
        } else if (enumKeys.contains(key)) {
            app.updateSetting(key, [type: "enum", value: value as String])
            if (key == "checkpointInterval") reschedule = true
        }
    }
    // Keep the native settings page's scale inputs in step, or updated() would copy them back.
    if (body.containsKey("warnTempC")) app.updateSetting("warnTempInput", [type: "number", value: warnTempDisplayValue()])
    if (body.containsKey("critTempC")) app.updateSetting("critTempInput", [type: "number", value: critTempDisplayValue()])
    if (reschedule) { unsubscribe(); unschedule(); initialize() }
    if (body.containsKey("debugLogging") || reschedule) {
        boolean dbg = body.containsKey("debugLogging") ? (body.debugLogging as boolean) : (settings.debugLogging as boolean)
        if (dbg || settings.traceEnable) runIn(1800, 'logsOff')
    }
    return jsonResponse([success: true])
}

Map apiClearCache() {
    checkVersion()
    int cleared = controllerTypeCache().size()
    clearControllerTypeCache()
    // Also drop the integration-overrides cache so this re-reads the File Manager config on next
    // use — the intuitive "apply my override edits" action, no full Done required.
    TTL_CACHE.remove('integrationOverrides')
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
        hub: buildHubMap(hubInfo, hub), appVersion: CODE_VERSION, uiVersion: getUIVersion(),
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
        [id: dev.id, name: dev.name, label: dev.label, type: dev.type,
         connectionType: dev.connectionType,
         integration: dev.integration,
         room: dev.room, status: dev.status ?: "", lastActivityMs: dev.lastActivityMs,
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
    // Display order is left to the SPA: its tables sort these columns.
    List userAppRows = (appStats.userAppsList ?: [])
        .collect { [id: it.id, label: it.label ?: it.name, type: it.name,
                    parentId: it.parentAppId, disabled: it.disabled ?: false] }
    List platformEntries = (appStats.platformApps ?: []).collect { Map p ->
        [id: p.id, name: p.name, type: p.name, user: false, source: "platform",
         disabled: false, hidden: false, setting: false, menu: "", level: 0, childCount: 0, parentId: null]
    }
    List allApps = (appStats.allApps ?: []) + platformEntries
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
    Map networkData = analyzeNetwork()
    Map statsWrap = hubMapRequest(RUNTIME_STATS_PATH, "runtime stats")
    Map statsRaw = statsWrap.ok ? statsWrap.data : null
    Map stats = statsRaw
    Integer uptimeSeconds = stats ? parseUptime(stats.uptime as String) : null
    Map zigbeeMesh = fetchZigbeeMeshInfo()
    String zwaveVersion = fetchZwaveVersion()
    Map zwaveMesh = extractZwaveMeshQuality(networkData.zwave ?: [:])
    List ghostNodes = buildZwaveGhostNodes(networkData.zwave ?: [:])
    Map zigbeeRaw = networkData.zigbee ?: [:]
    Map hubMeshRaw = networkData.hubMesh ?: [:]
    List hubMeshPeers = hubMeshRaw.hubList ? hubMeshRaw.hubList.collect { Map hub ->
        [name: hub.name, ip: hub.ipAddress, offline: hub.offline, hubId: hub.hubId,
         deviceCount: hub.deviceIds?.size() ?: 0, varCount: hub.hubVarNames?.size() ?: 0]
    } : []
    List sharedDeviceList = (hubMeshRaw.sharedDevices ?: []).collect { Map sd ->
        [id: sd.id, name: sd.name, appsUsing: sd.appsUsing ?: [], childCount: sd.childCount ?: 0]
    }
    List linkedDeviceList = (hubMeshRaw.localLinkedDevices ?: []).collect { Map ld ->
        [id: ld.id, name: ld.name, appsUsing: ld.appsUsing ?: [],
         childCount: ld.childCount ?: 0, sourceHubId: ld.sourceHubId]
    }
    List sharedVarList = (hubMeshRaw.sharedHubVariables ?: []).collect { Map sv ->
        [name: sv.name, type: sv.type]
    }
    List linkedVarList = (hubMeshRaw.localLinkedHubVariables ?: []).collect { Map lv ->
        [name: lv.name, type: lv.type,
         sourceHubName: lv.sourceHubName, sourceVarName: lv.sourceVarName,
         inUseByApps: lv.inUseByApps, hubAvailable: lv.hubAvailable]
    }
    return [
        uptimeSeconds: uptimeSeconds,
        network: networkData.network ?: null,
        zwave: networkData.zwave ? [
            enabled: networkData.zwave.enabled, healthy: networkData.zwave.healthy,
            region: networkData.zwave.region, nodeCount: (networkData.zwave.zwDevices ?: [:]).size(),
            isRadioUpdateNeeded: networkData.zwave.isRadioUpdateNeeded,
            zwaveJS: networkData.zwave.zwaveJS, zwaveJSAvailable: networkData.zwave.zwaveJSAvailable,
            version: zwaveVersion, zwaveJSVersion: networkData.zwave.zwaveJSVersion,
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
            // Raw device identity list — the SPA joins neighbors/routes to devices by shortId
            // (both payloads ride this same response, so the join is a pure client derivation).
            devicesSlim: (zigbeeRaw.devices ?: []).collect { Map d ->
                [id: d.id, name: d.name, shortZigbeeId: d.shortZigbeeId]
            },
            mesh: zigbeeMesh ? [
                neighbors: zigbeeMesh.neighbors?.size() ?: 0, routes: zigbeeMesh.routes?.size() ?: 0,
                neighborList: (zigbeeMesh.neighbors ?: []).collect { Map n ->
                    [shortId: n.shortId, name: n.name, lqi: n.lqi, age: n.age,
                     inCost: n.inCost, outCost: n.outCost]
                },
                routeList: (zigbeeMesh.routes ?: []).findAll { Map r -> r.destinationShortId }.collect { Map r ->
                    [status: r.status, age: r.age, concentratorType: r.concentratorType,
                     destinationName: r.destinationName, destinationShortId: r.destinationShortId,
                     viaName: r.viaName, viaShortId: r.viaShortId, direct: r.direct ?: false]
                },
                childDevices: zigbeeMesh.childDevices ?: 0
            ] : null
        ] : null,
        matter: networkData.matter ?: null,
        hubMesh: networkData.hubMesh ? [
            enabled: hubMeshRaw.hubMeshEnabled != null ? hubMeshRaw.hubMeshEnabled : hubMeshRaw.enabled,
            sharedDevices: sharedDeviceList.size(),
            linkedDevices: linkedDeviceList.size(),
            sharedVars: sharedVarList.size(),
            linkedVars: linkedVarList.size(),
            peers: hubMeshPeers,
            sharedDeviceList: sharedDeviceList,
            linkedDeviceList: linkedDeviceList,
            sharedVarList: sharedVarList,
            linkedVarList: linkedVarList
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
    Map statsWrap = hubMapRequest(RUNTIME_STATS_PATH, "runtime stats")
    Map stats = statsWrap.ok ? statsWrap.data : null

    Map resources = (shared.resources as Map) ?: fetchSystemResources()

    Map zwaveData = (Map) cachedFetch('zwaveDetails', RADIO_CACHE_TTL_MS) {
        Map r = hubMapRequest(ZWAVE_DETAILS_PATH, "Z-Wave details", 20)
        return (r.ok && r.data) ? r.data : null
    }
    Map zigbeeData = (Map) cachedFetch('zigbeeDetails', RADIO_CACHE_TTL_MS) {
        Map r = hubMapRequest(ZIGBEE_DETAILS_PATH, "Zigbee details", 20)
        return (r.ok && r.data) ? r.data : null
    }
    // Ship the raw per-device message counts; the SPA ranks the top talkers (sort + top-N).
    Map radioStats = [zwave: extractZwaveMessageCounts(zwaveData), zigbee: extractZigbeeMessageCounts(zigbeeData)]

    Map appsListResp = fetchAppsList() ?: [:]

    // R-7 B2: id → source / root-parent-label maps so the SPA doesn't cross-fetch /api/devices
    // and /api/apps just to label the Performance tab's CPU charts. One walk builds both.
    Map appSourceById = [:]
    Map appParentTypeById = [:]
    if (appsListResp.apps) {
        visitAppEntries(appsListResp.apps as List) { Map appEntry, Map app, boolean isChildLevel, List parents ->
            if (app?.id == null) return
            appSourceById[app.id] = (app.user ? "community" : "builtin")
            Map root = parents ? (Map) parents[0] : app
            appParentTypeById[app.id] = (root.label ?: root.name ?: 'Unknown') as String
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

    Map devListData = ((Map) cachedFetch('devicesList', HUB_LIST_CACHE_TTL_MS) {
        Map w = hubMapRequest(DEVICES_LIST_PATH, "devices list (B2 labels)", 15)
        return (w.ok && w.data) ? w.data : null
    }) ?: [:]
    Map deviceTypeById = [:]
    if (devListData.devices) {
        flattenDeviceEntries(devListData.devices as List).each { Map entry ->
            Map dev = entry?.data instanceof Map ? (Map) entry.data : null
            if (dev?.id != null) deviceTypeById[dev.id] = (dev.type ?: 'Unknown') as String
        }
    }

    List indexEntries = loadCheckpointIndex()
    return [
        stats: stats, resources: resources,
        radioStats: radioStats,
        deviceTypeById: deviceTypeById,        // B2: id → driver type for CPU-by-device-type chart
        appParentTypeById: appParentTypeById,  // B2: id → parent label for CPU-by-app-type chart
        checkpointCount: indexEntries.size(),
        maxCheckpoints: (settings.maxCheckpoints ?: 10) as int,
        checkpoints: indexEntries,
        savedComparison: loadPerformanceComparisonPayload(),
        cloudCalls: fetchCloudCalls(),
        cloudHourly: loadHourly().findAll { List r -> r.size() > 6 }.collect { List r -> [r[0], r[6]] }
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

    Map networkConfig = (Map) cachedFetch('networkConfig', NETWORK_CONFIG_CACHE_TTL_MS) {
        (Map) reqData(NETWORK_CONFIG_PATH, "network configuration", 15) ?: null
    }
    boolean ethernetAndWifi = (networkConfig && networkConfig.hasEthernet && networkConfig.hasWiFi) as boolean
    // Periodic Bonjour restarts (Network Setup → Bonjour options) cause LAN multicast spikes;
    // the platform recommends leaving it off. containsKey guard: legacy hubs omit the field —
    // treat absent as "not applicable" (no alert), never a misread false.
    boolean bonjourRestartsScheduled = (networkConfig?.containsKey('restartBonjourOnSchedule') && networkConfig.restartBonjourOnSchedule) as boolean

    // Z-Wave ghost/failed/problem signals \u2014 served from the shared 60s radio cache so
    // Dashboard/Health loads never pay a cold 8s fetch more than once per window.
    // state.cachedZwaveSignals persists the last computed signals across reboots.
    Map zwRaw = (Map) cachedFetch('zwaveDetails', RADIO_CACHE_TTL_MS) {
        Map zwWrap = hubMapRequest(ZWAVE_DETAILS_PATH, "Z-Wave details", 8)
        return (zwWrap.ok && zwWrap.data) ? zwWrap.data : null
    }
    Map zwSignals = zwRaw ? computeZwaveSignals(zwRaw) : ((state.cachedZwaveSignals as Map) ?: [:])
    if (zwRaw) state.cachedZwaveSignals = zwSignals

    // Cloud calls to apps that no longer exist, with their hourly buckets; the SPA decides recency.
    Map cc = fetchCloudCalls()
    List deletedCloud = (cc?.apps ?: []).findAll { Map a -> a.installed == false }.collect { Map a ->
        a + [hours: (cc.hours ?: []).findAll { Map b -> b.appId == a.id }]
    }

    return [
        platformAlerts:       platformAlerts,
        spammyDevicesMessage: hubAlerts?.spammyDevicesMessage,
        hubMessages:          hubMessages,
        ethernetAndWifi:      ethernetAndWifi,
        bonjourRestartsScheduled: bonjourRestartsScheduled,
        zwaveGhostCount:      (zwSignals.ghostCount   ?: 0) as int,
        zwaveFailedCount:     (zwSignals.failedCount  ?: 0) as int,
        zwaveProblemCount:    (zwSignals.problemCount ?: 0) as int,
        zwaveRadioUpdate:     (zwSignals.radioUpdate == true),
        deletedAppCloudCalls: deletedCloud
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

// ===== API TIMING =====

void recordApiTiming(String endpoint, long elapsedMs) {
    synchronized (apiTimings) {
        Map entry = apiTimings[endpoint]
        if (!entry) { entry = [samples: [], count: 0]; apiTimings[endpoint] = entry }
        List samples = entry.samples
        samples << elapsedMs
        if (samples.size() > API_TIMING_WINDOW) samples.remove(0)
        entry.count = (entry.count as int) + 1
    }
}

Map apiStats() {
    checkVersion()
    Map stats = [:]
    synchronized (apiTimings) {
        apiTimings.each { String endpoint, Map entry ->
            stats[endpoint] = [count: entry.count, recent: entry.samples.size(), lastSamples: entry.samples.collect()]
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
        logNet "Fetched ${name} in ${now() - start}ms"
        return type == "json" ? (result ?: [:]) : result
    } catch (Exception e) {
        String exClass = getObjectClassName(e)
        boolean isTransient = (exClass == 'java.net.SocketTimeoutException' || exClass == 'java.net.ConnectException')
        if (allowRetry && isTransient) {
            logNet "Transient error fetching ${name} (${exClass}); retrying once"
            return hubRequestInternal(path, name, type, timeout, false)
        }
        if (type == "json") {
            // A bare `null`/scalar body (e.g. Z-Wave JS getControllerState when no controller
            // state is available) is valid JSON but fails Hubitat's object/array parse with
            // JsonException. That's a no-data outcome, not a fault — log quietly; callers see ok:false.
            if (exClass == 'groovy.json.JsonException') {
                logNet "No JSON body for ${name} (${now() - start}ms): ${e.message}"
            } else {
                logError "Error fetching ${name} (${now() - start}ms): ${exClass}: ${e.message}"
            }
            return [error: true, message: e.message]
        } else {
            logNet "Error fetching ${name}: ${e.message}"
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
    return compareVersions(actual, required) >= 0
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
    return (Map) cachedFetch('systemResources', SYSTEM_RESOURCES_CACHE_TTL_MS) {
        String text = (String) hubRequest(FREE_MEMORY_PATH, "system resources", "text", 15)
        if (!text) return null
        try {
            Map parsed = parseHubCsv(text)
            if (!parsed || !parsed.rows) return null
            Map p = parseResourceRow((Map) ((List) parsed.rows)[0])
            if (p == null) { logWarn "system resources CSV missing expected columns; header=${parsed.header}"; return null }
            return [timestamp: p.timestamp, freeOSMemory: p.freeOS, cpuAvg5min: p.cpu,
                    totalJavaMemory: p.totalJava, freeJavaMemory: p.freeJava, directJavaMemory: p.directJava]
        } catch (Exception e) {
            logError "Error parsing system resources: ${e.message}"
            return null
        }
    }
}

List fetchMemoryHistory() {
    String text = (String) hubRequest(MEMORY_HISTORY_PATH, "memory history", "text", 30)
    if (!text) return []
    List dataPoints = []
    try {
        Map parsed = parseHubCsv(text)
        if (!parsed || !parsed.rows) return []
        boolean headerWarned = false
        Map hourCache = [:]
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
                timeMs:     hubStampToMs(p.timestamp as String, hourCache),
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
    return (Integer) cachedFetch('databaseSize', DATABASE_SIZE_CACHE_TTL_MS) {
        String text = (String) hubRequest(DATABASE_SIZE_PATH, "database size", "text")
        try { return text?.toInteger() } catch (Exception e) { return null }
    }
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
    return (Float) cachedFetch('temperature', TEMPERATURE_CACHE_TTL_MS) {
        String text = (String) hubRequest(INTERNAL_TEMP_PATH, "internal temperature", "text")
        try { return text?.toFloat() } catch (Exception e) { return null }
    }
}

// ===== TEMPERATURE HISTORY =====
// Sampled every 5 minutes (always on). Samples stay in memory; each completed hour is rolled
// up to min/avg/max in HOURLY_FILE, with the database size, so a longer view survives reboots
// and code pushes.

// Every 5 minutes at a per-install second offset. Also armed from the daily scheduledUISync
// when the offset is missing.
void armTemperatureSampling() {
    schedule("${installOffset('tempSampleOffsetSec', 60)} 0/5 * * * ?", "sampleTemperature")
}

// Random per-install schedule offset in [0, bound), kept in state so it is stable across saves.
private int installOffset(String key, int bound) {
    if (state[key] == null) state[key] = new Random().nextInt(bound)
    return state[key] as int
}

List tempSamples() {
    return (List) (TEMP_SAMPLES.get(app.id as Long) ?: [])
}

List loadHourly() {
    Object data = readFile(HOURLY_FILE)
    return (data instanceof List) ? (List) data : []
}

void sampleTemperature() {
    checkVersion()
    asynchttpGet('onTempSample', [uri: HUB_BASE, path: INTERNAL_TEMP_PATH, contentType: "text/plain", timeout: 5])
}

// Text body of an async hub response per the callback contract (hasError, then status); null on failure.
private String asyncText(resp, String name) {
    if (resp.hasError()) { logNet "${name}: ${resp.getErrorMessage()}"; return null }
    if (resp.status != 200) { logNet "${name}: HTTP ${resp.status}"; return null }
    return resp.data?.toString()?.trim()
}

void onTempSample(resp, data) {
    Float t = null
    try { t = asyncText(resp, "temperature sample")?.toFloat() } catch (Exception e) { }
    if (t == null) return
    long ms = now()
    List next = new ArrayList(tempSamples())
    next << [ms, t]
    if (next.size() > TEMP_SAMPLE_CAP) next = new ArrayList(next.subList(next.size() - TEMP_SAMPLE_CAP, next.size()))
    TEMP_SAMPLES.put(app.id as Long, next)
    rollupHours(next, ms)
}

// Append rows for completed hours not yet written. Hours whose samples were lost to a reboot
// or push get no temperature; a partly sampled hour carries its real sample count.
private void rollupHours(List samples, long nowMs) {
    long hourStart = nowMs.intdiv(3_600_000L) * 3_600_000L
    long lastHour = hourStart - 3_600_000L
    Long done = state.hourlyDoneMs as Long           // start of the last hour written
    long fromMs = done != null ? done + 3_600_000L : 0L
    if (fromMs >= hourStart) return
    Map<Long, List> byHour = [:]
    samples.each { List smp ->
        long ts = smp[0] as long
        if (ts >= fromMs && ts < hourStart) {
            long h = ts.intdiv(3_600_000L) * 3_600_000L
            if (!byHour[h]) byHour[h] = []
            byHour[h] << (smp[1] as BigDecimal)
        }
    }
    state.hourlyDoneMs = lastHour
    if (!byHour.containsKey(lastHour)) byHour[lastHour] = []
    List rows = byHour.keySet().sort().collect { Long h ->
        List v = byHour[h]
        v ? [h, v.min(), (v.sum() / v.size()).setScale(1, BigDecimal.ROUND_HALF_UP), v.max(), v.size(), null]
          : [h, null, null, null, 0, null]
    }
    asynchttpGet('onHourlyDbSize', [uri: HUB_BASE, path: DATABASE_SIZE_PATH, contentType: "text/plain", timeout: 10], [rows: rows])
}

// The database size goes on the last (just completed) hour's row.
void onHourlyDbSize(resp, data) {
    List rows = (List) data.rows
    try { rows[-1][5] = asyncText(resp, "database size")?.toInteger() } catch (Exception e) { }
    addCloudCalls(rows)
    List all = new ArrayList(loadHourly()) + rows
    if (all.size() > HOURLY_KEEP) all = all.subList(all.size() - HOURLY_KEEP, all.size())
    writeFile(HOURLY_FILE, groovy.json.JsonOutput.toJson(all))
}

// The hub's cloud-call buckets reset at boot, so each completed hour's counts are copied into
// its rollup row. Uncached: a cached read can predate the hour's last calls.
private void addCloudCalls(List rows) {
    Map cc = fetchCloudCalls(0L)
    if (cc == null) return
    long boot = (cc.startedAt ?: 0L) as long
    rows.each { List r ->
        long h = r[0] as long
        if (h + 3_600_000L <= boot) { r << null; return }
        Map counts = [:]
        (cc.hours ?: []).each { Map b ->
            long bs = b.hourStart as long
            if (bs >= h && bs < h + 3_600_000L) counts[b.appId as String] = ((counts[b.appId as String] ?: 0) as int) + (b.count as int)
        }
        r << counts
    }
}

// /logs/cloudCalls/json: inbound cloud-relay requests per app, hourly since boot. null before 2.5.2.129.
Map fetchCloudCalls(long ttlMs = CLOUD_CALLS_CACHE_TTL_MS) {
    if (!isVersionAtLeast(getHubFirmwareVersion(), CLOUD_CALLS_MIN_FW)) return null
    return (Map) cachedFetch('cloudCalls', ttlMs) {
        Map w = hubMapRequest(CLOUD_CALLS_PATH, "cloud calls", 10)
        return (w.ok && w.data) ? w.data : null
    }
}

// Hub resource CSV stamps are "MM-dd HH:mm:ss" in hub local time with no year. Parses the
// hour once per distinct hour (cached in hourCache) and assumes the current year, or the
// previous one when that lands in the future (history spanning New Year).
Long hubStampToMs(String stamp, Map hourCache) {
    if (!stamp || stamp.length() < 14) return null
    try {
        String hourKey = stamp.substring(0, 8)
        Long hourMs = hourCache[hourKey] as Long
        if (hourMs == null) {
            int year = new Date().format("yyyy", location.timeZone).toInteger()
            hourMs = Date.parse("yyyy-MM-dd HH", "${year}-${hourKey}", location.timeZone).time
            if (hourMs > now() + 86_400_000L) hourMs = Date.parse("yyyy-MM-dd HH", "${year - 1}-${hourKey}", location.timeZone).time
            hourCache[hourKey] = hourMs
        }
        return hourMs + stamp.substring(9, 11).toInteger() * 60_000L + stamp.substring(12, 14).toInteger() * 1000L
    } catch (Exception e) {
        return null
    }
}

// Free memory, CPU load and temperature over the interval since the previous checkpoint
// (or since the earliest sample available): [fromMs, toMs, freeOS:{min,avg,max,n}, cpu:…, temperature:…].
private Map buildIntervalSummary(long toMs) {
    List idx = loadCheckpointIndex()
    Long fromMs = idx ? (idx[0].timestampMs as Long) : null
    List mem = [], cpu = [], temp = []
    Long firstMs = null
    (fetchMemoryHistory() ?: []).each { Map p ->
        Long ms = p.timeMs as Long
        if (ms == null || ms > toMs || (fromMs != null && ms <= fromMs)) return
        if (firstMs == null || ms < firstMs) firstMs = ms
        mem << (p.freeOS as BigDecimal); cpu << (p.cpuLoad as BigDecimal)
    }
    tempSamples().each { List smp ->
        long ms = smp[0] as long
        if (ms > toMs || (fromMs != null && ms <= fromMs)) return
        if (firstMs == null || ms < firstMs) firstMs = ms
        temp << (smp[1] as BigDecimal)
    }
    return [fromMs: fromMs ?: firstMs, toMs: toMs,
            freeOS: summarizeValues(mem, 0), cpu: summarizeValues(cpu, 2), temperature: summarizeValues(temp, 1)]
}

private Map summarizeValues(List vals, int scale) {
    if (!vals) return null
    BigDecimal avg = ((BigDecimal) vals.sum() / vals.size()).setScale(scale, BigDecimal.ROUND_HALF_UP)
    return [min: vals.min(), avg: avg, max: vals.max(), n: vals.size()]
}

// Model-dependent default for the free-memory warning; the hub model is looked up once per code load.
private int defaultWarnMemMb() {
    if (hubModelCache == null) {
        Map hd = fetchHubData()
        if (hd != null) hubModelCache = (hd.model ?: "") as String
    }
    return hubModelCache == "C-8 Pro" ? DEFAULT_WARN_MEM_MB_C8PRO : DEFAULT_WARN_MEM_MB
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
// updateSetting leaves a setting unchanged when it was first stored under another type; older
// installs hold warnTempC/critTempC as "number". Removing it first lets the decimal write land.
private void updateDecimalSetting(String key, BigDecimal value) {
    app.removeSetting(key)
    app.updateSetting(key, [type: "decimal", value: value.setScale(1, BigDecimal.ROUND_HALF_UP)])
}

private BigDecimal warnTempCValue() { (settings.warnTempC != null ? settings.warnTempC : DEFAULT_WARN_TEMP_C) as BigDecimal }
private BigDecimal critTempCValue() { (settings.critTempC != null ? settings.critTempC : DEFAULT_CRIT_TEMP_C) as BigDecimal }
private int warnTempDisplayValue() { Math.round(cToScale(warnTempCValue()).doubleValue()) as int }
private int critTempDisplayValue() { Math.round(cToScale(critTempCValue()).doubleValue()) as int }
private String tempThresholdRange() { (getTemperatureScale() == "F") ? "68..212" : "20..100" }

Map fetchHubAlerts(Map prefetchedHubData = null) {
    Map hubData = prefetchedHubData
    if (!hubData) hubData = fetchHubData()
    if (!hubData) return [:]
    return [
        alerts: hubData.alerts ?: [:],
        databaseSize: hubData.alerts?.databaseSize,
        spammyDevicesMessage: hubData.spammyDevicesMessage,
        devMode: hubData.baseModel?.devMode ?: false
    ]
}

// Newest backup entry by createTimeOrig; the last entry when none parse.
private Map latestBackup(List backups) {
    if (!backups) return null
    Map best = null
    long bestMs = Long.MIN_VALUE
    backups.each { Object b ->
        Long ms = (b instanceof Map) ? parseDate(((Map) b).createTimeOrig) : null
        if (ms != null && ms > bestMs) { bestMs = ms; best = (Map) b }
    }
    return best ?: (Map) backups[-1]
}

List fetchHubEvents() {
    try {
        Object raw = hubRequest(HUB_EVENTS_PATH, "hub events", "json", 10)
        if (!(raw instanceof List)) return []
        return ((List) raw).collect { Object e ->
            Map ev = (Map) e
            Long ts = parseDate(ev.date) ?: 0L
            [id: ev.id, name: ev.name, description: ev.descriptionText, ts: ts, value: ev.value]
        }
    } catch (Exception e) {
        logNet "fetchHubEvents failed: ${e.message}"
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
    // Newest by creation time: the cloud list is not in date order. Lists without parseable
    // times fall back to the last entry, the previous behavior.
    Map latestLocal = latestBackup(localList)
    List cloudThisHub = cloudList.findAll { Map b -> b.thisHub == true } as List
    Map latestCloud = latestBackup(cloudThisHub)
    return [
        local: [
            count: localList.size(),
            latestName: latestLocal?.name,
            latestCreateTime: latestLocal?.createTime,
            latestCreateTimeMs: parseDate(latestLocal?.createTimeOrig),
            latestPlatformVersion: latestLocal?.platformVersion
        ],
        cloud: [
            thisHubCount: cloudThisHub.size(),
            otherHubCount: cloudList.size() - cloudThisHub.size(),
            latestThisHubCreateTime: latestCloud?.createTime,
            latestThisHubCreateTimeMs: parseDate(latestCloud?.createTimeOrig),
            latestThisHubVersion: latestCloud?.platformVersion,
            otherHubs: cloudList.findAll { Map b -> b.thisHub != true }.collect { Map b ->
                [hubName: b.hubName, hubVersion: b.hubVersion, platformVersion: b.platformVersion,
                 createTime: b.createTime, createTimeMs: parseDate(b.createTimeOrig), fileSize: b.fileSize]
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
    // Z-Wave JS is enabled but getControllerState returned no usable data (a bare `null` body).
    // Observed on hubs with a healthy multi-node mesh, so this is NOT a dead radio — the cause is
    // hub-side and unconfirmed. Flag it so the SPA notes the missing controller stats; node/mesh
    // data (from zwaveDetails) is unaffected.
    if (!wrap.ok) return [unavailable: true]
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
    return (Integer) cachedFetch('loadThreshold', LOAD_THRESHOLD_CACHE_TTL_MS) {
        String txt = (String) hubRequest(LOAD_THRESHOLD_PATH, "excessive load threshold", "text", 5)
        try { return txt?.trim()?.toInteger() } catch (Exception e) { return null }
    }
}

String fetchNtpServer() {
    String txt = (String) hubRequest(NTP_SERVER_PATH, "NTP server", "text", 5)
    if (!txt) return null
    String t = txt.trim()
    if (!t || t.equalsIgnoreCase("No value set")) return null
    return t
}

Map fetchFirmwareUpdate() {
    Map fu = (Map) cachedFetch('fwUpdate', FW_UPDATE_CACHE_TTL_MS) {
        Map wrap = hubMapRequest(FIRMWARE_UPDATE_PATH, "firmware update check", 15)
        if (!wrap.ok) return null
        Map resp = wrap.data
        return [currentVersion: getHubFirmwareVersion(), availableVersion: resp.version,
                updateAvailable: resp.upgrade == true, status: resp.status,
                beta: resp.beta == true, releaseNotesUrl: resp.releaseNotesUrl]
    }
    Map dt = fetchDiagToolVersions()
    if (!fu && !dt) return null
    return (fu ?: [currentVersion: getHubFirmwareVersion()]) + dt
}

/**
 * Platform versions the Diagnostic Tool can restore, plus its stable (fallback) version.
 * Returns [:] when the tool doesn't answer; the empty map is cached so a down tool
 * doesn't cost a timeout on every page load.
 */
Map fetchDiagToolVersions() {
    return (Map) cachedFetch('diagToolVersions', FW_UPDATE_CACHE_TTL_MS) {
        Map versions = diagToolPost(DIAG_TOOL_VERSIONS_PATH)
        if (versions?.success != true) return [:]
        Map info = diagToolPost(DIAG_TOOL_HUB_INFO_PATH)
        List restorable = ((versions.hubList as List) ?: []).collect { stripHubPrefix(it as String) }
        return [restorableVersions: restorable, stableVersion: stripHubPrefix(info?.stableVersion as String)]
    }
}

private Map diagToolPost(String path) {
    Map result = null
    try {
        httpPost([uri: DIAG_TOOL_BASE, path: path, contentType: "application/json", timeout: 5]) { resp ->
            if (resp.success && resp.data instanceof Map) result = (Map) resp.data
        }
    } catch (Exception e) {
        logNet "Diagnostic Tool ${path} unavailable: ${e.message}"
    }
    return result
}

private static String stripHubPrefix(String v) {
    return v?.startsWith("hub-") ? v.substring(4) : v
}

/**
 * Rooms from getRooms(): [{id, name, deviceCount, deviceIds[]}], child devices included.
 * Unlike /hub2/roomsList it has no "Unassigned" room and can list ids of deleted devices;
 * the SPA reconciles both against allDevices (buildAuditRooms() in hub_inspector_ui.html).
 */
List fetchRoomsForAudit() {
    List rooms
    try {
        rooms = (getRooms() ?: []) as List
    } catch (Exception e) {
        logWarn "rooms: getRooms() failed: ${e.message}"
        return []
    }
    return rooms.collect { Map r ->
        List<Long> ids = ((r.deviceIds as List) ?: []).collect { it as Long }
        [id: r.id, name: r.name, deviceCount: ids.size(), deviceIds: ids]
    }   // the SPA orders rooms (buildAuditRooms)
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
        // false, or the wake-up interval ("250ms"/"1000ms") for a FLiRS node
        isFrequentListening: resp.isFrequentListening == true || (resp.isFrequentListening instanceof String && resp.isFrequentListening.endsWith("ms")),
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
         lastModifiedMs: parseDate(a.lastModified),
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
         lastModifiedMs: parseDate(d.lastModified),
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
         updateTimeMs: parseDate(l.updateTime), isPrivate: l.private == true,
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
        return [count: entries.size(), supported: true, variables: entries]
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
    return (Map) cachedFetch('cpuInfo', CPU_INFO_CACHE_TTL_MS) {
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
    if (!hubData) hubData = fetchHubData()
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

    Map result = [childDevices: 0, neighbors: [], routes: []]

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
            Map neighbor = [:]
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
            result.neighbors << neighbor
        } else if (currentSection == "child") {
            result.childDevices = (result.childDevices as int) + 1
        } else if (currentSection == "route" && line.contains("status:")) {
            // Parse route entries: "status:Active, age:64, routeRecordState:0, concentratorType:None, [Dest Name, ABCD] via [Via Name, EF01]"
            Map route = [:]
            java.util.regex.Matcher statusMatch = (line =~ /status:\s*(\w+)/)
            java.util.regex.Matcher ageMatch = (line =~ /age:\s*(\d+|null)/)
            java.util.regex.Matcher rrsMatch = (line =~ /routeRecordState:\s*(\d+|null)/)
            java.util.regex.Matcher ctMatch = (line =~ /concentratorType:\s*(\w+)/)
            if (statusMatch.find()) route.status = statusMatch.group(1)
            if (ageMatch.find())    route.age = (ageMatch.group(1) == 'null') ? null : ageMatch.group(1).toInteger()
            if (rrsMatch.find())    route.routeRecordState = (rrsMatch.group(1) == 'null') ? null : rrsMatch.group(1).toInteger()
            if (ctMatch.find())     route.concentratorType = ctMatch.group(1)
            // Two [name, shortId] groups: destination then via
            java.util.regex.Matcher bracketMatch = (line =~ /\[([^\]]+),\s*([^\]]+)\]/)
            List bracketHits = []
            while (bracketMatch.find()) {
                bracketHits << [bracketMatch.group(1).trim(), bracketMatch.group(2).trim()]
            }
            if (bracketHits.size() >= 1) {
                String name = bracketHits[0][0]
                String sid = bracketHits[0][1]
                route.destinationName = (name == 'Unknown') ? null : name
                route.destinationShortId = (sid == 'null') ? null : sid.toUpperCase()
            }
            if (bracketHits.size() >= 2) {
                String name = bracketHits[1][0]
                String sid = bracketHits[1][1]
                route.viaName = (name == 'Unknown') ? null : name
                route.viaShortId = (sid == 'null') ? null : sid.toUpperCase()
            }
            // Direct route convention: destination == via means hub talks straight to the device
            route.direct = (route.destinationShortId && route.destinationShortId == route.viaShortId)
            result.routes << route
        } else if (currentSection == "route") {
            result.routes << [raw: line]
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
    zwaveData.nodes.each { Map node ->
        int per = (node.per ?: 0) as int
        int neighborCount = (node.neighbors ?: 0) as int
        // null (not a sentinel int) when the hub reports no route-change count:
        // Z-Wave Long Range nodes (star topology, no mesh routing) and nodes with
        // no accumulated traffic yet both surface as non-numeric here.
        Integer routeChanges = node.routeChanges?.toString()?.isInteger() ? (node.routeChanges ?: 0) as int : null
        String rssiStr = node.lwrRssi ?: ""
        Integer rssiVal = null
        if (rssiStr) {
            java.util.regex.Matcher m = (rssiStr =~ /(-?\d+)/)
            if (m.find()) rssiVal = m.group(1).toInteger()
        }

        // averageRtt: ms, an integer on Z/IP and a decimal string ("41.7") on Z-Wave JS; empty when unavailable
        String rttRaw = (node.averageRtt != null) ? node.averageRtt.toString() : ""
        BigDecimal rtt = (rttRaw && rttRaw.isNumber()) ? new BigDecimal(rttRaw) : null

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
            security: node.security ?: "",
            driverType: driverType,
            zwaveType: node.zwaveType ?: ""
        ]
    }

    // Mesh rollups (node count, PER, RSSI, route changes) and the S0 flag are derived in the SPA.
    return [nodes: nodes]
}

List extractZwaveMessageCounts(Map zwaveData) {
    if (!zwaveData || !zwaveData.nodes) return []
    return zwaveData.nodes.collect { Map node ->
        [id: node.nodeId, deviceId: node.deviceId, name: node.deviceName ?: "Node ${node.nodeId}",
         msgCount: (node.msgCount ?: 0) as int, routeChanges: node.routeChanges?.toString()?.isInteger() ? (node.routeChanges ?: 0) as int : null]
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

Map analyzeDevices(boolean deep = true, Map prefetchedDevices = null) {
    Map respWrap = (prefetchedDevices != null) ? [ok: true, data: prefetchedDevices, error: null]
                                               : hubMapRequest(DEVICES_LIST_PATH, "devices list")

    if (!respWrap.ok || !respWrap.data.devices) {
        logWarn "Failed to fetch devices list"
        return getEmptyDeviceStats()
    }

    // includeParentContext=true (not just deep) so child devices carry their parentDeviceId in
    // both passes — needed for parent-device inheritance below.
    List devicesList = flattenDeviceEntries(respWrap.data.devices as List, true)

    long inactivityThresholdMs = now() - ((settings.inactivityDays ?: 7) * ONE_DAY_MS)
    Map appLookup = buildAppLookupMap()
    Set communityDrivers = buildCommunityDriverSet()
    // Community app type names (app.name where app.user == true) — fallback in enrichDevices()
    // when the per-device fullJson cache is stale or missing the builtin field.
    Set communityAppTypeNames = appLookup.values()
        .findAll { (it as Map)?.user == true }
        .collect { (String)((it as Map)?.type ?: "") }
        .findAll { it } as Set

    // ---- Pass 1: classify every device. No aggregation yet — buckets are built exactly once,
    // after enrichment, so there is no decrement/patch step that can drift out of sync.
    Map classByDeviceId = [:]   // id(String) → [connectionType, integration, builtin(, tentative)]
    Map uncertainDevices = [:]  // id(String) → [appInfo, deviceId] — needs the fullJson pass
    devicesList.each { deviceEntry ->
        try {
            Map device = deviceEntry.data instanceof Map ? (Map) deviceEntry.data : null
            if (!device) return
            Map classification = classifyDevice(device, appLookup, communityDrivers)
            classByDeviceId[device.id?.toString()] = classification
            if (classification.connectionType == CONN_OTHER || classification.tentative == true) {
                String normalizedParentAppId = normalizeAppLookupId(extractParentAppId(device))
                uncertainDevices[device.id.toString()] = [
                    appInfo: normalizedParentAppId ? (Map) appLookup[normalizedParentAppId] : null,
                    deviceId: device.id
                ]
            }
        } catch (Exception e) {
            logWarn "Error classifying device ${deviceEntry.key}: ${e.message}"
        }
    }

    // ---- Pass 2: parent-device inheritance, then fullJson enrichment for the rest.
    if (uncertainDevices) {
        // A child device the bulk pass couldn't place inherits its parent DEVICE's classification
        // (e.g. a Blink/Lutron parent fronting child cameras/dimmers). Inherited children skip
        // the per-device fullJson fetch entirely.
        Map inherited = [:]
        devicesList.each { deviceEntry ->
            Object pid = deviceEntry.parentDeviceId
            Object cid = (deviceEntry.data instanceof Map) ? ((Map) deviceEntry.data).id : null
            if (pid == null || cid == null) return
            String cidStr = cid.toString()
            if (!uncertainDevices.containsKey(cidStr)) return
            Map parentClass = (Map) classByDeviceId[pid.toString()]
            if (parentClass && parentClass.integration && parentClass.integration != "Other") {
                inherited[cidStr] = parentClass
            }
        }

        Map enrichedAppInfos = (Map) uncertainDevices
            .findAll { k, v -> !inherited.containsKey(k) }
            .collectEntries { k, v -> [k, ((Map) v).appInfo] }
        Map enrichments = enrichDevices(enrichedAppInfos, communityAppTypeNames)
        if (inherited) enrichments.putAll(inherited)   // inheritance wins over the (empty) fullJson result

        enrichments.each { String idStr, Map newClass ->
            Map cur = (Map) classByDeviceId[idStr]
            if (cur == null) return
            classByDeviceId[idStr] = [
                connectionType: (newClass.connectionType ?: cur.connectionType),
                integration:    newClass.integration,
                // builtin taken verbatim (may be null): its only consumer is the
                // integrationSources guard below, and a null must stay null — the
                // controllerType-fallback path intentionally sets no source entry.
                builtin:        newClass.builtin
            ]
        }
    }

    // ---- Pass 3: single aggregation over the final classifications.
    Map stats = getEmptyDeviceStats()
    devicesList.each { deviceEntry ->
        try {
            Map device = deviceEntry.data instanceof Map ? (Map) deviceEntry.data : null
            if (!device) return

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

            Map cls = (Map) classByDeviceId[device.id?.toString()] ?: [connectionType: CONN_OTHER, integration: null, builtin: null]
            String connectionType = cls.connectionType
            String integration = cls.integration

            stats.byConnectionType[connectionType] = (stats.byConnectionType[connectionType] ?: 0) + 1
            if (!stats.idsByConnectionType.containsKey(connectionType)) stats.idsByConnectionType[connectionType] = []
            stats.idsByConnectionType[connectionType] << device.id

            // integration is null for standalone devices: counted in the connection-type breakdown,
            // intentionally omitted from the integration breakdown.
            if (integration) {
                stats.byIntegration[integration] = (stats.byIntegration[integration] ?: 0) + 1
                if (!stats.idsByIntegration.containsKey(integration)) stats.idsByIntegration[integration] = []
                stats.idsByIntegration[integration] << device.id
                if (cls.builtin != null && !stats.integrationSources.containsKey(integration)) {
                    stats.integrationSources[integration] = cls.builtin ? "builtin" : "community"
                }
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

                String normalizedParentAppId = normalizeAppLookupId(extractParentAppId(device))
                Map parentAppInfo = normalizedParentAppId ? (Map) appLookup[normalizedParentAppId] : null
                String parentAppName = parentAppInfo?.label ?: (normalizedParentAppId ? "App ${normalizedParentAppId}" : null)
                stats.allDevices << [
                    id: device.id, name: device.name ?: "Unknown",
                    label: device.label ?: device.name ?: "Unknown",
                    type: typeName, userType: device.user ?: false, deviceTypeId: device.deviceTypeId,
                    connectionType: connectionType, integration: integration,
                    status: device.disabled ? "Disabled" : (lastActivity && lastActivity > inactivityThresholdMs ? "Active" : "Inactive"),
                    lastActivityMs: lastActivity,
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

// /hub2/appsList through the shared 2-minute list cache; null on failure.
private Map fetchAppsList() {
    return (Map) cachedFetch('appsList', HUB_LIST_CACHE_TTL_MS) { (Map) reqData(APPS_LIST_PATH, "apps list", 20) ?: null }
}

Map analyzeApps(boolean deep = true) {
    List appsList = (fetchAppsList()?.apps ?: []) as List

    if (!appsList) {
        return deep ? getEmptyAppStats() : [totalApps: 0, userApps: 0, builtInApps: 0]
    }

    // Quick mode: just count apps
    if (!deep) {
        int totalApps = 0, userApps = 0, builtInApps = 0
        visitAppEntries(appsList) { Map appEntry, Map app, boolean isChildLevel, List parentHierarchyList ->
            if (!app) return
            totalApps++
            if (app.user) userApps++
            else builtInApps++
        }
        return [totalApps: totalApps, userApps: userApps, builtInApps: builtInApps]
    }

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
    processAppList = { List entries, boolean isChildLevel, List parentHierarchyList, Long currentParentId, String currentParentName ->
        entries.each { appEntry ->
            try {
                Map app = appEntry.data
                if (!app || !(app instanceof Map)) return

                stats.totalApps++

                boolean isUserApp = app.user ?: false
                String appType = app.type ?: "Unknown App"
                String appLabel = app.name ?: appType
                def appId = appEntry.key ?: app.id  // keep "APP-NNN" for snapshot diff lookups
                Long numericId = app.id as Long     // numeric ID for UI links
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

                // Flat entry for the installed apps table
                stats.allApps << [
                    id:         numericId,
                    name:       appLabel,
                    type:       appType,
                    typeId:     isUserApp ? app.appTypeId : null,
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

                    processAppList(children, true, parentInfo.children, numericId, appLabel)
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
        logNet "Could not fetch runtime stats for app count: ${e.message}"
    }

    // Display order of userAppsList, platformApps and parentChildHierarchy is left to the SPA.

    return stats
}

// Data-or-null convenience over hubMapRequest (collapses the repeated `.with { it.ok ? it.data : null }`).
private Object reqData(String path, String name, int timeout = 10) {
    Map r = hubMapRequest(path, name, timeout)
    return r.ok ? r.data : null
}

// Radio details go through the shared radio TTL cache, like getPerformanceData. fresh=true (config
// snapshot) reads past it for a point-in-time record, and refreshes it.
Map analyzeNetwork(boolean fresh = false) {
    Map network = (Map) reqData(NETWORK_CONFIG_PATH, "network configuration", 15)
    cachePut('networkConfig', network)   // keeps getAlertSignals()' copy as fresh as this read
    return [
        network: network,
        zwave:   radioDetails('zwaveDetails', ZWAVE_DETAILS_PATH, "Z-Wave details", fresh),
        zigbee:  radioDetails('zigbeeDetails', ZIGBEE_DETAILS_PATH, "Zigbee details", fresh),
        matter:  reqData(MATTER_DETAILS_PATH, "Matter details", 15),
        hubMesh: reqData(HUB_MESH_PATH, "Hub Mesh", 15)
    ]
}

private Map radioDetails(String key, String path, String name, boolean fresh) {
    if (!fresh) return (Map) cachedFetch(key, RADIO_CACHE_TTL_MS) { (Map) reqData(path, name, 20) ?: null }
    Map data = (Map) reqData(path, name, 20) ?: null
    cachePut(key, data)
    return data
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

// zwaveDetails carries two lists: nodes[] (the radio's nodes, with route/state/RSSI) and
// zwDevices (Hubitat device records keyed by node id; each value's id is the device id).
//   kind=ghost     -> radio node with no Hubitat device, safe to force-remove from radio
//   kind=failed    -> radio reports the node FAILED but it has a device; try battery/range first
//   kind=longRange -> device with no radio entry, node id >= 256: the Z/IP stack leaves
//                     Long Range nodes out of nodes[], so the hub gives no signal details
//   kind=unlisted  -> device with no radio entry below 256
List buildZwaveGhostNodes(Map zwaveDetails) {
    List ghostNodes = []
    Map zwDevices = (zwaveDetails?.zwDevices ?: [:]) as Map
    Set radioNodeIds = [] as Set
    (zwaveDetails?.nodes ?: []).each { Map n ->
        if (n.nodeId == null) return
        String nodeId = n.nodeId.toString()
        radioNodeIds << nodeId
        String zwType = (n.zwaveType ?: "") as String
        Long deviceId = (n.deviceId ?: (zwDevices[nodeId] instanceof Map ? zwDevices[nodeId].id : null)) as Long
        boolean isFailed = n.nodeState == "FAILED"
        boolean noRoute  = !n.route || n.route == "No route"
        // Never flag a deviceless controller node (the hub's own, or a secondary controller)
        if (!deviceId && zwType.toUpperCase().contains("CONTROLLER")) return
        if (!deviceId || isFailed) {
            List signals = []
            if (!deviceId) signals << "no device"
            if (isFailed)  signals << "FAILED"
            if (noRoute)   signals << "no route"
            ghostNodes << [
                id: n.nodeId,
                deviceId: deviceId,
                kind: deviceId ? "failed" : "ghost",
                name: n.deviceName ?: "Unknown",
                status: n.nodeState ?: "",
                type: zwType,
                signals: signals
            ]
        }
    }
    zwDevices.each { nodeId, dev ->
        if (!(dev instanceof Map) || radioNodeIds.contains(nodeId.toString())) return
        boolean longRange = nodeId.toString().isInteger() && nodeId.toString().toInteger() >= 256
        ghostNodes << [
            id: nodeId,
            deviceId: dev.id as Long,
            kind: longRange ? "longRange" : "unlisted",
            name: dev.label ?: dev.name ?: "Unknown",
            status: "",
            type: "",
            signals: [longRange ? "Long Range" : "not in radio list"]
        ]
    }
    return ghostNodes
}

Map buildAppLookupMap() {
    List apps = (fetchAppsList()?.apps ?: []) as List
    Map appLookup = [:]
    visitAppEntries(apps) { Map appEntry, Map app, boolean isChildLevel, List parentHierarchyList ->
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
    Object resp = hubRequest(DEVICE_TYPES_PATH, "device types", "json", 15)
    if (!(resp instanceof List)) return [] as Set
    List types = (List) resp
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
 * Result is cached in TTL_CACHE['integrationOverrides'] for INTEGRATION_OVERRIDES_CACHE_TTL_MS so a
 * file uploaded/edited after first load is picked up without a full Done; updated() and
 * apiClearCache() reset it for an immediate reload. A parse error falls back to the built-in defaults.
 */
private Map getIntegrationOverrides() {
    return (Map) cachedFetch('integrationOverrides', INTEGRATION_OVERRIDES_CACHE_TTL_MS) {
        // The override file is OPTIONAL. downloadHubFile throws when it's absent — the normal
        // case on most hubs — so log that at debug. A WARN is reserved for a file that IS
        // present but can't be parsed (a real misconfiguration).
        byte[] fileData = null
        String fileName = null
        for (String name in [INTEGRATION_OVERRIDES_FILE, LEGACY_INTEGRATION_OVERRIDES_FILE]) {
            try {
                fileData = downloadHubFile(name)
                fileName = name
                break
            } catch (Exception e) {
                logDebug "No ${name} in File Manager"
            }
        }
        if (fileData) {
            try {
                Map userRaw = (Map) new groovy.json.JsonSlurper().parseText(new String(fileData, "UTF-8"))
                Map merged = new LinkedHashMap()
                userRaw.each { rawKey, rawVal ->
                    if (rawKey.toString().startsWith("_")) return   // skip documentation keys
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
                INTEGRATION_OVERRIDES.each { k, v -> if (!merged.containsKey(k)) merged[k] = v }
                logDebug "Loaded integration overrides from ${fileName}: ${merged.size()} entries"
                return merged
            } catch (Exception e) {
                logWarn "Found ${fileName} but could not parse it (${e.message}) — using built-in defaults"
            }
        }
        return INTEGRATION_OVERRIDES
    }
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

// Shared parent-app classification: override wins, else cleaned app name + LAN-signal-derived
// connection. Used by classifyDevice (bulk pass, isLan = device.isNetwork) and enrichDevices
// (fullJson pass, isLan = controllerType NET/LAN) so the two passes cannot drift.
private Map classifyFromParentApp(String appType, String appLabel, boolean isLan, Boolean builtin) {
    Map ov = lookupIntegration(appType) ?: (appLabel ? lookupIntegration(appLabel) : null)
    String raw = appType ?: appLabel
    String integration = (ov?.name) ? (String) ov.name : cleanIntegrationName(raw)
    String connectionType = ov?.conn ? (String) ov.conn : (isLan ? CONN_LAN_DIRECT : CONN_CLOUD)
    return [connectionType: connectionType, integration: integration ?: raw, builtin: builtin]
}

// Returns [connectionType, integration, builtin]. Two orthogonal axes:
//   connectionType = how the hub reaches the device (the specific radio for commissioned devices,
//                    homekit for HAP, else lan_direct/lan_bridge/cloud/virtual/hubmesh/other);
//   integration    = the PARENT-managed group only (parent-app name, parent-device-inherited, or an
//                    explicit override name) — null for everything parentless (radio, virtual,
//                    hub-mesh, standalone cloud/LAN). builtin=true means Hubitat-bundled.
// communityDrivers is a Set<String> of user driver names from /hub2/userDeviceTypes.
Map classifyDevice(Map device, Map appLookup, Set communityDrivers) {
    // 1. Boolean flags from bulk devicesList — authoritative, require no extra API calls. A radio-paired
    //    device's connection type IS its radio (the hub is its controller); it has no integration.
    if (device.isZigbee == true)    return [connectionType: CONN_ZIGBEE,    integration: null, builtin: true]
    if (device.isZwave == true)     return [connectionType: CONN_ZWAVE,     integration: null, builtin: true]
    if (device.isMatter == true)    return [connectionType: CONN_MATTER,    integration: null, builtin: true]
    if (device.isBluetooth == true) return [connectionType: CONN_BLUETOOTH, integration: null, builtin: true]
    if (device.isLinked == true || device.linked == true) {
        return [connectionType: CONN_HUBMESH, integration: null, builtin: true]
    }
    if (device.isVirtual == true)   return [connectionType: CONN_VIRTUAL, integration: null, builtin: true]

    // 1b. Driver-name heuristic: built-in Virtual* drivers without the isVirtual flag
    boolean driverIsBuiltin = !communityDrivers.contains(safeToString(device.type, ""))
    String driverTypeLower = safeToString(device.type, "").toLowerCase()
    if (driverIsBuiltin && (driverTypeLower.startsWith("virtual ") || driverTypeLower == "virtual")) {
        return [connectionType: CONN_VIRTUAL, integration: null, builtin: true]
    }

    // 1c. Built-in cloud device drivers (OpenWeatherMap, Pushover, etc.) — standalone cloud pollers
    //     with no parent app and isNetwork=false. They aren't an integration; the built-in driver name
    //     is just the only signal that they're cloud-connected, so set CONN_CLOUD, integration null.
    if (driverIsBuiltin && BUILTIN_CLOUD_DRIVERS.contains(driverTypeLower)) {
        return [connectionType: CONN_CLOUD, integration: null, builtin: true]
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
            return classifyFromParentApp((appInfo.type ?: "").toString(), (appInfo.label ?: "").toString(),
                                         device.isNetwork == true, !(appInfo.user == true))
        }
    }

    // 2b. Override file by driver type name — the declarative path for standalone community devices
    //     (no parent app) the derivation can't place: a LAN device the hub flags isNetwork=false would
    //     mis-derive to cloud (or fall to Other), a cloud device with no signals falls to Other. Users
    //     add e.g. {"awair": {"conn": "lan_direct"}} to the File Manager override file; matched by the
    //     same substring lookup used for parent-app names, so it wins over the isNetwork derivation.
    Map typeOverride = lookupIntegration(safeToString(device.type, ""))
    if (typeOverride?.conn) {
        // A `name` means "this is an integration" — group + label the device under it. No `name` means
        // a connection-type-only override: the device is standalone (not part of any integration), so
        // return a null integration. analyzeDevices counts it in the connection-type breakdown but
        // omits it from the integration breakdown (rather than fabricating a per-driver integration).
        String integration = (typeOverride.name) ? (String) typeOverride.name : null
        return [connectionType: (String) typeOverride.conn, integration: integration, builtin: driverIsBuiltin]
    }

    // 3. Network (LAN) flag — parentApp not available in bulk list. Standalone for now (integration
    //    null); tentative=true asks the deep pass to fullJson-enrich it in case it has a parent app.
    if (device.isNetwork == true) return [connectionType: CONN_LAN_DIRECT, integration: null, builtin: driverIsBuiltin, tentative: true]

    // 4. Final fallback — no reliable signal for connection type; standalone, no integration
    return [connectionType: CONN_OTHER, integration: null, builtin: driverIsBuiltin]
}

// Enriches device classification using device/fullJson for devices bulk data couldn't resolve.
// uncertainDevices: Map<String deviceId, Map appInfo> where appInfo may be null.
// Primary signal: parentApp from fullJson (appType.name) — runs the same algorithm-primary logic as
//   classifyDevice: integration = cleanIntegrationName(appType.name), connectionType derived from
//   the controllerType signal (NET/LAN ⇒ lan_direct, else cloud); INTEGRATION_OVERRIDES supplies a conn exception.
// Fallback signal: controllerType from fullJson.device (actual values: ZGB, MAT, LNK, etc.).
// Results cached in CONTROLLER_TYPE_CACHE, persisted to state.controllerTypeCache — keyed by device
// ID string, value is [parentAppTypeName, controllerType, ...] since parentApp is also stable for a
// device's lifetime.
// Returns Map<String deviceId, Map [connectionType, integration]> for devices that improve.
Map enrichDevices(Map uncertainDevices, Set communityAppTypeNames = [] as Set) {
    if (!uncertainDevices) return [:]

    ConcurrentHashMap<String, Object> cache = controllerTypeCache()
    Map cacheUpdates = [:]
    Map result = [:]

    uncertainDevices.each { String idStr, Map appInfo ->
        // v5.77.0: entries are plain maps (state is JSON-serialized anyway — the old JSON-string
        // encoding double-parsed on every pass). Legacy string entries survive a code push
        // (updated() doesn't fire), so read both formats; unreadable ones re-fetch.
        Object cachedVal = cache[idStr]
        Map cachedEntry = null
        if (cachedVal instanceof Map) {
            cachedEntry = (Map) cachedVal
        } else if (cachedVal instanceof String && ((String) cachedVal).startsWith("{")) {
            try { cachedEntry = (Map) new groovy.json.JsonSlurper().parseText((String) cachedVal) }
            catch (Exception ignored) { /* stale/invalid format — re-fetch */ }
        }
        // Entries written before controllerType was read from fullJson.device hold a blank value; re-fetch.
        if (cachedEntry != null && cachedEntry.ctSrc != "device") cachedEntry = null

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
                ct = fullJsonControllerType(full)
                // Check for community driver classification hint: updateDataValue("hubdiag:conn", "cloud|lan_direct|lan_bridge|homekit")
                try {
                    String dataJson = safeToString(full?.device?.dataJson, "")
                    if (dataJson?.startsWith("{")) {
                        Map dataValues = (Map) new groovy.json.JsonSlurper().parseText(dataJson)
                        String hint = safeToString(dataValues?.get("hubdiag:conn"), "")
                        if (hint && VALID_CONN.contains(hint)) connHint = hint
                    }
                } catch (Exception ignored) {}
                cacheUpdates[idStr] = [
                    parentAppTypeName: parentAppTypeName ?: "",
                    controllerType: ct ?: "",
                    ctSrc: "device",
                    connHint: connHint ?: "",
                    builtin: isBuiltin == null ? "" : (isBuiltin ? "true" : "false")
                ]
            } catch (Exception e) {
                logNet "enrichDevices: could not fetch device ${idStr}: ${e.message}"
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

        // Primary: parent-app classification — same rules as classifyDevice branch 2.
        if (parentAppTypeName) {
            result[idStr] = classifyFromParentApp(parentAppTypeName, null, (ct == "NET" || ct == "LAN"), isBuiltin)
            return
        }

        // Fallback: controllerType gives the connection type. Integration only if a real parent app is
        // known — a parentless device is standalone (no integration), never the raw controllerType code.
        if (ct) {
            String connType = (String) CONTROLLER_TYPE_CONN[ct]
            if (connType && connType != CONN_OTHER) {
                String intName = appInfo ? (cleanIntegrationName(safeToString(appInfo.type ?: appInfo.label, "")) ?: null) : null
                result[idStr] = [connectionType: connType, integration: intName, builtin: null]
            }
        }
    }

    if (cacheUpdates) {
        cache.putAll(cacheUpdates)   // atomic per entry; never replaces the shared map
        state.controllerTypeCache = new LinkedHashMap(cache)   // whole-value write of a snapshot
    }

    return result
}

// This instance's enrichment working copy, loaded from state.controllerTypeCache on first use.
private ConcurrentHashMap<String, Object> controllerTypeCache() {
    Long key = app.id as Long
    ConcurrentHashMap<String, Object> m = CONTROLLER_TYPE_CACHE.get(key)
    if (m != null) return m
    ConcurrentHashMap<String, Object> loaded = new ConcurrentHashMap<>()
    Map persisted = state.controllerTypeCache instanceof Map ? (Map) state.controllerTypeCache : [:]
    persisted.each { k, v -> if (k != null && v != null) loaded.put(k.toString(), v) }
    ConcurrentHashMap<String, Object> prior = CONTROLLER_TYPE_CACHE.putIfAbsent(key, loaded)
    return prior != null ? prior : loaded
}

// Clears in place, so a pass still holding the map can't write the evicted entries back to state.
private void clearControllerTypeCache() {
    CONTROLLER_TYPE_CACHE.get(app.id as Long)?.clear()
    state.remove('controllerTypeCache')
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

void checkpointTick() {
    checkVersion()
    runIn(jitterDelaySec(), "scheduledCheckpoint")
}

// v5.33.0: scheduled-only async entry point. checkpointTick() schedules this and it
// returns immediately after firing the first asynchttpGet; the chain callbacks finalize
// off the app thread. Keeps user-triggered apiCreateCheckpoint sync so the HTTP caller
// gets a real success/fail response.
void scheduledCheckpoint() {
    checkVersion()
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
    checkVersion()
    if (asyncCheckpointStaging == null) {
        // Chain was aborted or staging was reset (e.g. code push) during the retry wait.
        logSched "scheduledCheckpoint: runtime stats retry skipped — chain no longer active"
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
    String key = zwave ? 'zwaveDetails' : 'zigbeeDetails'
    String path = zwave ? ZWAVE_DETAILS_PATH : ZIGBEE_DETAILS_PATH
    String cbName = zwave ? "asyncOnZwave" : "asyncOnZigbee"
    Map cached = (Map) cachePeek(key, RADIO_CACHE_TTL_MS)
    if (cached != null) {
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
        this."${cbName}"(null, [cached: true, body: [:]])
    }
}

void asyncOnZwave(resp, data) {
    Map zw = extractAsyncBody(resp, data, "Z-Wave")
    if (zw && !data?.cached) cachePut('zwaveDetails', zw)
    asyncCheckpointStaging.zwaveRaw = zw ?: [:]
    chainNextRadio(false)
}

void asyncOnZigbee(resp, data) {
    Map zb = extractAsyncBody(resp, data, "Zigbee")
    if (zb && !data?.cached) cachePut('zigbeeDetails', zb)
    asyncCheckpointStaging.zigbeeRaw = zb ?: [:]
    finalizeAsyncCheckpoint()
}

private Map extractAsyncBody(resp, data, String name) {
    if (data?.cached) return (Map) data.body
    if (resp == null) return [:]
    if (resp.hasError()) {
        logNet "scheduledCheckpoint: ${name} error: ${resp.getErrorMessage()}"
        return [:]
    }
    if (resp.status != 200) {
        logNet "scheduledCheckpoint: ${name} HTTP ${resp.status}"
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

// Radio fetch with cache-first + bounded budget (8s, no retry) for checkpoint paths, where
// blocking the app thread for tens of seconds contributes to hub-wide overload.
// getPerformanceData keeps its 20s timeout — the user explicitly opened the tab.
private Map fetchRadioForCheckpoint(boolean zwave) {
    return (Map) cachedFetch(zwave ? 'zwaveDetails' : 'zigbeeDetails', RADIO_CACHE_TTL_MS) {
        Map r = hubMapRequest(zwave ? ZWAVE_DETAILS_PATH : ZIGBEE_DETAILS_PATH,
                              "${zwave ? 'Z-Wave' : 'Zigbee'} details (checkpoint)", 8, false)
        return (r.ok && r.data) ? r.data : null
    }
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

    // v5.32.6: capture radio message counts via the same 60s TTL cache used by
    // getPerformanceData. On a cold cache, bound the fetch to 8s with no retry — worst-case
    // per-radio blocking drops from ~40s (20s × once-retry) to 8s, halving total checkpoint
    // app-thread time. If the call still fails, store empty arrays rather than crashing.
    Map zwaveData = fetchRadioForCheckpoint(true)
    Map zigbeeData = fetchRadioForCheckpoint(false)
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
    logInfo "All perf checkpoints cleared (${detailFiles.size()} detail file(s) removed)"
}

// ===== SNAPSHOT SYSTEM =====

// Daily cron entry point; skips until snapshotInterval days have passed. One hour of
// slack so a run that fires slightly early is not pushed back a whole day.
void scheduledSnapshot() {
    checkVersion()
    int days = (settings.snapshotInterval ?: 1).toInteger()
    Long last = state.lastScheduledSnapshotMs as Long
    if (last != null && now() - last < days * 86400000L - 3600000L) return
    state.lastScheduledSnapshotMs = now()
    runIn(jitterDelaySec(), "createSnapshot")
}

// Scheduled jobs fire on a fixed cron slot, then wait a fresh random delay each run
// so they stay off the :00 jobs and do not land on the same second every time.
private int jitterDelaySec() {
    return 1 + new Random().nextInt(JITTER_MAX_S)
}

void createSnapshot() {
    checkVersion()
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
            latestThisHubCreateTimeMs: backupsRaw.cloud.latestThisHubCreateTimeMs,
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
        network: analyzeNetwork(true),
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
        return fileList   // callers that display it order it themselves
    } catch (Exception e) {
        logDebug "Unable to list hub files: ${e.message}"
        return []
    }
}

// ===== UTILITY METHODS =====

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
    if (!hubData) hubData = fetchHubData()
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
        byConnectionType: [(CONN_ZIGBEE): 0, (CONN_ZWAVE): 0, (CONN_MATTER): 0, (CONN_BLUETOOTH): 0,
                           (CONN_HOMEKIT): 0, (CONN_LAN_DIRECT): 0, (CONN_LAN_BRIDGE): 0,
                           (CONN_CLOUD): 0, (CONN_VIRTUAL): 0, (CONN_HUBMESH): 0, (CONN_OTHER): 0],
        idsByConnectionType: [(CONN_ZIGBEE): [], (CONN_ZWAVE): [], (CONN_MATTER): [], (CONN_BLUETOOTH): [],
                              (CONN_HOMEKIT): [], (CONN_LAN_DIRECT): [], (CONN_LAN_BRIDGE): [],
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

// Numeric dotted-version compare; non-numeric or missing segments count as 0.
private int compareVersions(String a, String b) {
    List ap = a.tokenize('.'), bp = b.tokenize('.')
    int n = Math.max(ap.size(), bp.size())
    for (int i = 0; i < n; i++) {
        int av = (i < ap.size() && ((String) ap[i]).isInteger()) ? ((String) ap[i]).toInteger() : 0
        int bv = (i < bp.size() && ((String) bp[i]).isInteger()) ? ((String) bp[i]).toInteger() : 0
        if (av != bv) return (av < bv) ? -1 : 1
    }
    return 0
}

boolean isNewer(String v1, String v2) {
    if (!v1 || v1 == "Unknown" || !v2 || v2 == "Unknown") return false
    return compareVersions(v1, v2) > 0
}

private String getAppTypeId() {
    return app.getAppTypeId()?.toString()
}

private String getAppEditorPath() {
    String typeId = getAppTypeId()
    return typeId ? "/app/editor/${typeId}" : null
}

private boolean autoEnableOAuth() {
    logCfg "Attempting to auto-enable OAuth for Hub Inspector..."

    // 1. Find our app type ID
    String typeId = getAppTypeId()
    if (!typeId) {
        logError "Could not determine this app's type id."
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
            logCfg "Successfully auto-enabled OAuth."
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
        interval: cp?.interval,
        detailFile: detailFile
    ]
}

private String detailFilenameFor(Object timestampMs) {
    return "${CHECKPOINT_DETAIL_PREFIX}${timestampMs}.json"
}

List loadCheckpointIndex() {
    // Defensive copies both ways (N4): callers can never mutate the shared cache.
    if (cachedCheckpointIndex != null) return new ArrayList(cachedCheckpointIndex)
    try {
        List data = (List) readFile(CHECKPOINT_INDEX_FILE)
        if (data != null) {
            cachedCheckpointIndex = data
            return new ArrayList(cachedCheckpointIndex)
        }
    } catch (Exception e) {
        logDebug "No existing checkpoint index: ${e.message}"
    }
    // First run or index missing: start an empty index file so later reads short-circuit.
    saveCheckpointIndex([])
    return []
}

void saveCheckpointIndex(List index) {
    try {
        writeFile(CHECKPOINT_INDEX_FILE, groovy.json.JsonOutput.toJson(index))
        cachedCheckpointIndex = (index == null ? null : new ArrayList(index))
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

// v5.33.0: write a new checkpoint to disk. Used by both the sync (apiCreateCheckpoint)
// and async (scheduledCheckpoint) paths. Persists detail file first, then updates the
// index. Trims oldest entries beyond settings.maxCheckpoints, deleting their detail files.
void persistCheckpoint(Map cp) {
    try {
        cp.interval = buildIntervalSummary((cp.timestampMs ?: now()) as long)
    } catch (Exception e) {
        logWarn "persistCheckpoint: interval summary skipped: ${e.message}"
    }
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
        byte[] hubFile = downloadHubFile(UI_FILE)
        if (hubFile) return new String(hubFile, 'UTF-8')
    } catch (Exception e) {
        logError "Error reading ${UI_FILE}: ${e.message}"
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
 * id -> Hub Mesh linkage fields, read off the bulk /hub2/devicesList. remoteDeviceUrl / isLinked /
 * hubMesh live on each entry's `data`; onOffState + hubMeshDisabled come from `data.currentStates`.
 * These aren't in fullJson (or read null there), so the bulk list is the source of truth.
 */
private Map<Long, Map> buildMeshFieldsMap(Map prefetchedDevices = null) {
    Map<Long, Map> out = [:]
    Map wrap = (prefetchedDevices != null) ? [ok: true, data: prefetchedDevices, error: null]
                                           : hubMapRequest(DEVICES_LIST_PATH, "devices list (mesh enrichment)", 30)
    if (!wrap.ok) { logWarn "mesh enrichment: device list fetch failed: ${wrap.error}"; return out }
    List entries = flattenDeviceEntries((wrap.data?.devices ?: []) as List)
    entries.each { Object e ->
        Map d = ((e instanceof Map && ((Map) e).data instanceof Map) ? ((Map) e).data : e) as Map
        Long id = d.id as Long
        if (id == null) return
        List cs = (d.currentStates ?: []) as List
        Map swState  = cs.find { ((Map) it).key == 'switch' } as Map
        Map hmdState = cs.find { ((Map) it).key == 'hubMeshDisabled' } as Map
        String sw = (swState != null) ? safeToString(swState.value, null) : null
        out[id] = [
            remoteDeviceUrl: safeToString(d.remoteDeviceUrl, null),
            isLinked:        (d.isLinked == true) || (d.linked == true),
            hubMeshShared:   (d.hubMesh == true),
            onOffState:      (sw == 'on' || sw == 'off') ? sw : null,
            hubMeshDisabled: (hmdState != null) && (safeToString(hmdState.value, 'false') == 'true')
        ]
    }
    return out
}

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
            if (!model && fullJsonControllerType(fj) == "ZWV") {
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
    String protocol = controllerTypeLabel(fullJsonControllerType(fj))

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
        _schemaVersion:      4,
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
        createTimeMs:        parseDate(dev.createTime),
        updateTimeMs:        parseDate(dev.updateTime),
        lastActivityTimeMs:  parseDate(dev.lastActivityTime),
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

        // Hub Mesh linkage (v5.69.0) — filled in finalizeAudit() from the bulk /hub2/devicesList.
        // fullJson carries remoteDeviceUrl but null isLinked/hubMesh/currentStates, so the bulk list
        // is authoritative. Cross-hub consumers (Multi-Hub Inventory) stitch the source↔remote graph
        // from remoteDeviceUrl (http://<sourceIP>:<port>/device/edit/<sourceDeviceId>).
        remoteDeviceUrl:     null,
        isLinked:            false,
        hubMeshShared:       false,
        onOffState:          null,
        hubMeshDisabled:     false,

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
 * controllerType from a /device/fullJson response. It lives under `device` (ZGB, ZWV, MAT, LNK…;
 * null for LAN, cloud, and virtual devices); the top-level key is kept as a fallback.
 */
private String fullJsonControllerType(Map fj) {
    Map dev = fj?.device instanceof Map ? (Map) fj.device : null
    return safeToString(dev?.controllerType ?: fj?.controllerType, "").trim().toUpperCase()
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
 * Refill the pipeline until the in-flight cap is reached or the queue drains.
 * Iterative, not recursive: dispatchOne() returns true whenever it made progress —
 * a successful dispatch, or a rollback after a synchronous throw — so a run of
 * consecutive synchronous failures is bounded by the queue, not by the call stack.
 */
private void refillAuditPipeline(String scanId) {
    while (dispatchOne(scanId)) { /* keep the pipeline full */ }
}

/**
 * CAS-bounded dispatch: reserves a slot in the in-flight pool (≤ AUDIT_MAX_INFLIGHT),
 * pops the next pending device id atomically, records a claim before the request goes
 * out, and issues an async fullJson fetch.
 * Returns false only when no progress was made (cap reached, queue empty, or the scan
 * no longer exists); a handled synchronous throw counts as progress and returns true.
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

    Object raw = (scan.pending as ConcurrentLinkedQueue).poll()
    if (raw == null) {                                              // queue drained between cap check and pop
        inFlight.decrementAndGet()
        return false
    }
    // A requeued retry carries its prior attempt count; a first attempt is a bare id.
    Long deviceId    = (raw instanceof Map) ? ((raw as Map).id as Long) : (raw as Long)
    int attemptCount = (((raw instanceof Map) ? ((raw as Map).attemptCount ?: 0) : 0) as Integer) + 1

    // Claim recorded BEFORE the request is issued, so a request that is accepted and then
    // never calls back is still visible to the reaper. The token makes the claim specific
    // to this attempt, so a late callback from a reaped attempt can identify itself as stale.
    String attemptToken = "tok-${(scan.tokenSeq as AtomicInteger).incrementAndGet()}"
    Map myClaim = [attemptToken: attemptToken, dispatchedAt: now(), attemptCount: attemptCount]
    (scan.claims as ConcurrentHashMap)[deviceId] = myClaim

    Map params = [
        uri: "${HUB_BASE}${FULL_JSON_PATH_PREFIX}${deviceId}",
        contentType: "application/json",
        timeout: 15
    ]
    try {
        asynchttpGet('fullJsonCb', params, [scanId: scanId, deviceId: deviceId, attemptToken: attemptToken])
        return true
    } catch (Exception e) {
        // The request was never accepted, so no callback is coming and the reserved slot
        // would leak permanently — eight such throws would hang the scan at inFlight == 8
        // with nothing logged. Roll the reservation back here instead.
        logWarn "[audit ${scanId}] device ${deviceId} dispatch threw: ${e.message}"
        retireAuditClaim(scan, deviceId, myClaim, "dispatch threw: ${getObjectClassName(e)}: ${e.message}")
        maybeFinalizeAudit(scanId)                                  // the last device may have just failed terminally
        return true                                                 // progress made — the refill loop tries the next id
    }
}

/**
 * Retire one dispatched-but-unresolved attempt: release its claim and its in-flight slot,
 * then either requeue the device for one more try or record it as failed.
 *
 * Shared by dispatchOne's synchronous-throw path and the reaper so both retire identically.
 * Ownership is proven by conditional removal — remove(id, thatExactClaim) — so a callback
 * and the reaper racing over the same attempt can never both release the slot for it.
 * Returns true if this execution owned the claim and retired it.
 *
 * Write ordering mirrors fullJsonCb's: the completion signal (requeue / processed++) is
 * published BEFORE inFlight drops, so the finalize predicate can never observe an empty
 * queue and a zero in-flight count while this device is still owed.
 */
private boolean retireAuditClaim(ConcurrentHashMap scan, Long deviceId, Map claim, String reason) {
    if (!(scan.claims as ConcurrentHashMap).remove(deviceId, claim)) return false
    int attemptCount = claim.attemptCount as Integer
    if (attemptCount < AUDIT_ATTEMPT_CAP) {
        (scan.pending as ConcurrentLinkedQueue) << [id: deviceId, attemptCount: attemptCount]
    } else {
        (scan.failed as ConcurrentHashMap)[deviceId] = reason
        (scan.processed as AtomicInteger).incrementAndGet()
    }
    (scan.inFlight as AtomicInteger).decrementAndGet()
    return true
}

/**
 * Finalize if — and only if — the scan is genuinely complete: nothing queued, nothing in
 * flight, no outstanding claims, and every device accounted for. Every count is read fresh
 * here rather than passed in from a caller's local, because processed and inFlight are
 * independent atomics: the execution that zeroes inFlight is not necessarily the one whose
 * increment hit total. finalizeAudit() is itself exactly-once via its CAS guard, so calling
 * this from several paths (callback, reaper, throw rollback) is safe.
 */
private void maybeFinalizeAudit(String scanId) {
    ConcurrentHashMap scan = AUDIT_SCANS[scanId]
    if (scan == null) return
    if (!(scan.pending as ConcurrentLinkedQueue).isEmpty()) return
    if ((scan.inFlight as AtomicInteger).get() != 0) return
    if (!(scan.claims as ConcurrentHashMap).isEmpty()) return
    int processed = (scan.processed as AtomicInteger).get()
    int total     = scan.total as Integer
    if (processed > total) {
        logWarn "[audit ${scanId}] invariant violation — processed=${processed} exceeds total=${total}"
        return
    }
    if (processed < total) return
    finalizeAudit(scanId)
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
    String attemptToken = data.attemptToken as String

    // Claim ownership. A callback whose claim is gone or has been replaced belongs to an
    // attempt the reaper already retired — it must not touch processed/inFlight, which that
    // retirement already accounted for.
    Map claim = (scan.claims as ConcurrentHashMap)[deviceId] as Map
    if (claim == null || claim.attemptToken != attemptToken) return
    if (!(scan.claims as ConcurrentHashMap).remove(deviceId, claim)) return   // lost the race with the reaper

    try {
        if (resp?.hasError()) {
            (scan.failed as ConcurrentHashMap)[deviceId] = (resp.getErrorMessage() ?: "request error") as String
        } else if (resp?.status != 200) {
            (scan.failed as ConcurrentHashMap)[deviceId] = "HTTP ${resp?.status ?: 'n/a'}"
        } else {
            (scan.devices as ConcurrentHashMap)[deviceId] = extractAuditFields((Map) resp.json, deviceId)
        }
    } catch (Exception e) {
        (scan.failed as ConcurrentHashMap)[deviceId] = "${getObjectClassName(e)}: ${e.message}"
    }

    // Ordering is load-bearing: the completion signal rises before the in-progress signal
    // drops, so the finalize predicate never sees this device as neither in flight nor done.
    int processed = (scan.processed as AtomicInteger).incrementAndGet()
    (scan.inFlight as AtomicInteger).decrementAndGet()

    // No state.audit write here: concurrent callbacks rewriting one state map lose entries,
    // and apiAuditStatus reads live progress from scan.processed instead.
    refillAuditPipeline(scanId)                                     // keep the pipeline full
    // maybeFinalizeAudit reads every count fresh — see its comment for why this callback's
    // local `processed`/`inFlight` values are not a safe basis for the finalize decision.
    maybeFinalizeAudit(scanId)
}

/**
 * Reaper: retires claims for attempts that were accepted but never called back at all —
 * platform-level silence, not a per-device HTTP failure (those already resolve in one
 * callback). Without it, inFlight never returns to 0, the finalize predicate never holds,
 * and the scan hangs with the progress bar stopped and nothing logged.
 *
 * Self-reschedules every AUDIT_REAP_INTERVAL_SEC, but only while the scan is still live and
 * unfinalized. Both terminal conditions are checked at the end: a scan that can never
 * complete is failed by the watchdog, which CASes finalizeGuard and unschedules this — so
 * the reaper stops rather than cycling forever on a scan that will never finalize.
 */
void auditClaimReaper(data) {
    checkVersion()
    String scanId = data?.scanId as String
    ConcurrentHashMap scan = scanId ? AUDIT_SCANS[scanId] : null
    if (scan == null) return                                        // finalized or cleared — terminal, stop rescheduling
    if ((scan.finalizeGuard as AtomicInteger).get() == 1) return     // finalize owned elsewhere — terminal

    long nowMs = now()
    List<Map> stale = []
    (scan.claims as ConcurrentHashMap).each { k, v ->
        Map c = v as Map
        if (nowMs - (c.dispatchedAt as Long) >= AUDIT_REAP_DEADLINE_MS) {
            stale << [deviceId: k as Long, claim: c]
        }
    }

    int reaped = 0
    stale.each { Map cand ->
        Long deviceId = cand.deviceId as Long
        Map claim = cand.claim as Map
        // Re-check the age against the claim we snapshotted: the slot may have been retired
        // and re-dispatched since the sweep above, and the conditional remove inside
        // retireAuditClaim is what keeps this attempt-specific.
        if (now() - (claim.dispatchedAt as Long) < AUDIT_REAP_DEADLINE_MS) return
        if (retireAuditClaim(scan, deviceId, claim, "no callback within ${AUDIT_REAP_DEADLINE_MS}ms")) {
            reaped++
            logWarn "[audit ${scanId}] device ${deviceId} reaped — no callback (attempt ${claim.attemptCount})"
        }
    }

    if (reaped > 0) {
        refillAuditPipeline(scanId)
        maybeFinalizeAudit(scanId)
    }

    ConcurrentHashMap live = AUDIT_SCANS[scanId]
    if (live != null && (live.finalizeGuard as AtomicInteger).get() != 1) {
        runIn(AUDIT_REAP_INTERVAL_SEC, 'auditClaimReaper', [data: [scanId: scanId]])
    }
}

/**
 * Finalize a completed scan: build cross-reference, store result in volatile memory,
 * update state.audit snapshot, free memory.
 */
private void finalizeAudit(String scanId) {
    ConcurrentHashMap scan = AUDIT_SCANS[scanId]
    if (scan == null) return
    // Exactly-once publish guard. Finalize is now reachable from a callback, the reaper and
    // dispatchOne's rollback path; this CAS is what keeps them from publishing twice. It is
    // also the reaper's terminal signal, so take it before doing any work.
    if (!(scan.finalizeGuard as AtomicInteger).compareAndSet(0, 1)) return
    unschedule('auditClaimReaper')
    long startedAt = scan.startedAt as Long
    int total      = scan.total as Integer
    Map devices    = (scan.devices as ConcurrentHashMap) as Map
    Map failedMap  = (scan.failed  as ConcurrentHashMap) as Map
    int succeeded  = devices.size()
    int failed     = failedMap.size()

    boolean errored = (failed / (double) Math.max(total, 1)) > AUDIT_FAIL_RATIO

    // Ship the raw collected device records; the SPA derives the cross-reference
    // (unreferenced / mesh orphans / critical ranking / apps↔devices / tuned-device divergence)
    // from allDevices — see buildAuditXref() in hub_inspector_ui.html.
    Map xref = [
        deviceCount:       succeeded,
        scanStartedMs:     startedAt,
        scanDurationMs:    (now() - startedAt),
        allDevices:        devices
    ]

    // Phase 4 enrichment: rooms, Z-Wave JS per-node, Hub Mesh per-device
    long enrichStart = now()
    xref.rooms = fetchRoomsForAudit()

    // One fresh devicesList for the whole finalize phase — shared request-scoped (NOT via the
    // TTL cache: buildMeshFieldsMap extracts live switch state that must not be minutes old).
    Map finalizeDevWrap = hubMapRequest(DEVICES_LIST_PATH, "devices list (audit finalize)", 30)
    Map finalizeDevList = finalizeDevWrap.ok ? finalizeDevWrap.data : null

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
        List classified = (analyzeDevices(true, finalizeDevList)?.allDevices ?: []) as List
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

    // Hub Mesh linkage enrichment (v5.69.0) — join remoteDeviceUrl / isLinked / hubMeshShared /
    // switch state / hubMeshDisabled off the bulk /hub2/devicesList so cross-hub consumers can
    // reconstruct the source↔remote graph. Keyed by device id, same shape as the join above.
    try {
        Map<Long, Map> meshById = buildMeshFieldsMap(finalizeDevList)
        devices.each { Object devId, Object recObj ->
            Map mf = (Map) meshById[devId as Long]
            if (mf) {
                Map rec = (Map) recObj
                rec.remoteDeviceUrl = mf.remoteDeviceUrl
                rec.isLinked        = mf.isLinked
                rec.hubMeshShared   = mf.hubMeshShared
                rec.onOffState      = mf.onOffState
                rec.hubMeshDisabled = mf.hubMeshDisabled
            }
        }
    } catch (Exception e) {
        logWarn "[audit ${scanId}] hub mesh enrichment failed: ${e.message}"
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
    int finalProcessed = (scan.processed as AtomicInteger).get()
    String finalStatus = errored ? 'error' : 'done'
    lastFinalizedAudit = [scanId: scanId, status: finalStatus, processed: finalProcessed]
    state.audit = [
        scanId:    scanId,
        status:    finalStatus,
        processed: finalProcessed,
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
    checkVersion()
    String scanId = data?.scanId as String ?: ((state.audit as Map)?.scanId as String)
    if (!scanId) return
    ConcurrentHashMap scan = AUDIT_SCANS[scanId]
    if (scan == null) return                                        // already finalized — nothing to do
    // CAS the same guard finalizeAudit takes, before writing anything: a legitimate finalize
    // landing at the same moment must win rather than have its result overwritten by this
    // failure path. Setting it also stops the reaper rescheduling — a scan that can never
    // complete ends here instead of cycling the reaper forever.
    if (!(scan.finalizeGuard as AtomicInteger).compareAndSet(0, 1)) return
    unschedule('auditClaimReaper')
    int processed = (scan.processed as AtomicInteger).get()
    int total     = scan.total as Integer
    int outstanding = (scan.claims as ConcurrentHashMap).size()
    logWarn "[audit ${scanId}] watchdog fired — ${processed}/${total} done, ${outstanding} claim(s) outstanding, marking errored"
    state.audit = [
        scanId: scanId, status: 'error', processed: processed, total: total,
        startedAt: scan.startedAt, error: "Watchdog: scan exceeded ${AUDIT_WATCHDOG_SEC}s"
    ]
    AUDIT_SCANS.remove(scanId)
}

/**
 * state.audit as it should read now. A 'scanning' snapshot whose scan is no longer in
 * AUDIT_SCANS was either just finalized (state write not yet committed) or lost to a reboot,
 * code push or reload. Re-deriving on every read means an orphan never waits on one exit.
 */
private Map currentAuditSnapshot() {
    Map snap = (state.audit ?: [:]) as Map
    String scanId = snap.scanId as String
    if (snap.status != 'scanning' || !scanId || AUDIT_SCANS[scanId]) return snap
    Map fin = lastFinalizedAudit
    if (fin?.scanId == scanId) return snap + [status: fin.status, processed: fin.processed]
    Map healed = snap + [status: 'error', error: 'Scan interrupted by hub reboot, code push or app reload']
    state.audit = healed
    logWarn "[audit] cleared orphaned scan ${scanId} (in-memory state lost)"
    return healed
}

/**
 * POST /api/audit/start — begin a new device usage audit.
 * Idempotent under concurrent triggers: if a scan is already in-flight, returns its scanId.
 */
Map apiAuditStart() {
    checkVersion()
    // Force-clear stale scan (>10 min in 'scanning' state) on entry
    Map prev = currentAuditSnapshot()
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
    List devs = flattenDeviceEntries((bulkWrap.data.devices ?: []) as List)
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
    scan.claims     = new ConcurrentHashMap<Long, Map>()
    scan.tokenSeq   = new AtomicInteger(0)
    scan.finalizeGuard = new AtomicInteger(0)
    AUDIT_SCANS[scanId] = scan

    state.audit = [
        scanId: scanId, status: 'scanning', processed: 0, total: ids.size(),
        startedAt: scan.startedAt
    ]

    // Schedule the watchdog and the missing-callback reaper. runIn() replaces rather than
    // stacks a prior job of the same handler name, so a previous scan's jobs cannot linger.
    runIn(AUDIT_WATCHDOG_SEC, 'auditWatchdog', [data: [scanId: scanId]])
    runIn(AUDIT_REAP_INTERVAL_SEC, 'auditClaimReaper', [data: [scanId: scanId]])

    // Initial fan-out — self-bounds at AUDIT_MAX_INFLIGHT, and re-tries the next id if a
    // dispatch throws, so a throwing device cannot consume one of the initial slots.
    refillAuditPipeline(scanId)

    logInfo "[audit ${scanId}] started — ${ids.size()} devices to scan"
    return jsonResponse([scanId: scanId, total: ids.size(), alreadyRunning: false])
}

/**
 * GET /api/audit/status?scanId=... — polled by frontend during a scan.
 * If scanId is omitted, returns the latest known status.
 */
Map apiAuditStatus() {
    checkVersion()
    String requested = params.scanId as String
    Map snap = currentAuditSnapshot()
    if (requested && snap.scanId != requested) {
        // Caller asked about a specific scan we don't know about
        return jsonResponse([scanId: requested, status: 'unknown'])
    }
    // While a scan is in flight, progress lives only in AUDIT_SCANS' AtomicInteger;
    // state.audit.processed is written at start and at finalize.
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
    checkVersion()
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
    logCfg "Hub Inspector installed"
    state.installed = true
    if (!state.accessToken) checkOAuth()
    runIn(1, 'syncUIForced')
    initialize()
}

void updated() {
    logCfg "Hub Inspector updated"
    state.installed = true
    // Convert the scale-display threshold inputs to canonical Celsius storage. Thresholds are
    // always compared in Celsius, so a later hub scale change can never reinterpret them.
    if (settings.warnTempInput != null) updateDecimalSetting("warnTempC", scaleToC(settings.warnTempInput as BigDecimal))
    if (settings.critTempInput != null) updateDecimalSetting("critTempC", scaleToC(settings.critTempInput as BigDecimal))
    unsubscribe()
    unschedule()
    // clear session-scoped caches so config/hardware changes take effect immediately
    zwaveStackCache  = null   // re-detect Z-Wave stack on next use (handles user switching legacy ↔ JS)
    clearControllerTypeCache()   // evict per-device classification cache; rebuilds on next analysis pass
    synchronized (apiTimings) { apiTimings.clear() } // drop stats for renamed/removed endpoints
    // N1: clear the TTL'd radio/list/resource/fwUpdate/integrationOverrides caches too, so a
    // settings change isn't masked by stale data for up to the cache TTL.
    TTL_CACHE.clear()   // N1: one wholesale clear — a new cache can never be missed here again
    cachedCheckpointIndex = null   // re-read the checkpoint index from FileManager on next access
    // C2: auto-disable debug logging after 30 min so it can't be left on indefinitely
    if (settings.debugLogging || settings.traceEnable) runIn(1800, 'logsOff')
    runIn(1, 'syncUIForced')
    initialize()
}

void logsOff() {
    checkVersion()
    app.updateSetting("debugLogging", [type: "bool", value: false])
    app.updateSetting("traceEnable", [type: "bool", value: false])
    logWarn "Debug/trace logging auto-disabled after 30 minutes"
}

void uninstalled() {
    unschedule()
    unsubscribe()
    logCfg "Hub Inspector uninstalled"
}

private boolean checkOAuth() {
    if (state.accessToken) return true
    try {
        createAccessToken()
        return (state.accessToken != null)
    } catch (e) {
        logNet "OAuth not enabled yet, attempting auto-enable..."
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
    if (!force && state.lastInstalledVersion == CODE_VERSION) {
        long lastCheck = state.lastUIUpdateCheck ?: 0
        if (now() - lastCheck < 86400000) return
    }
    logInfo "Syncing UI from GitHub (async)..."
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
        logInfo "Syncing UI from GitHub (blocking)..."
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
    if (!htmlText) {
        logWarn "Sync failed: Downloaded content appears invalid"
        return false
    }
    if (!htmlText.contains("const CODE_VERSION = \"${CODE_VERSION}\"")) {
        logWarn "Sync failed: GitHub UI version does not match App v${CODE_VERSION}"
        return false
    }
    byte[] htmlBytes = htmlText.getBytes("UTF-8")
    uploadHubFile(UI_FILE, htmlBytes)
    state.lastInstalledVersion = CODE_VERSION
    state.lastUIUpdateCheck = now()
    uiVersionCache = CODE_VERSION
    logVer "UI updated from GitHub to match App v${CODE_VERSION} (${htmlBytes.length} bytes)"
    return true
}

private void checkVersion(boolean reinit = true) {
    if (state.version == CODE_VERSION) return
    logVer "New version: ${CODE_VERSION} (was: ${state.version})"
    state.version = CODE_VERSION
    if (reinit) runIn(1, "updated")
}

void initialize() {
    checkVersion(false)
    logCfg "Hub Inspector initialized"
    migrateLegacyLabel()
    ['snapshotOffsetSeconds', 'checkpointOffsetSeconds', 'checkpointIndex', 'storageSchemaVersion',
     'lastZwaveGhostCheckMs'].each { state.remove(it) }    // retired keys

    currentAuditSnapshot()      // mark a scan orphaned by a reload as failed

    if (settings.autoSnapshot) {
        int days = (settings.snapshotInterval ?: 1).toInteger()
        // A day-of-month step (*/N) restarts on the 1st, so it only means "every N days"
        // when N divides the month. Run daily and let scheduledSnapshot() count the days.
        schedule("0 ${JITTER_BASE_MIN} 0 * * ?", "scheduledSnapshot")
        logInfo "Automatic config snapshots scheduled every ${days} day(s), 00:0${JITTER_BASE_MIN}–00:0${JITTER_BASE_MIN + 4}"
    }

    if (settings.autoCheckpoint) {
        int interval = (settings.checkpointInterval ?: "60").toInteger()
        String cron
        if (interval < 60) {
            cron = "0 ${JITTER_BASE_MIN}/${interval} * * * ?"
        } else {
            int hours = (interval / 60).toInteger()
            cron = hours >= 24 ? "0 ${JITTER_BASE_MIN} 0 * * ?" : "0 ${JITTER_BASE_MIN} */${hours} * * ?"
        }
        schedule(cron, "checkpointTick")
        logInfo "Automatic perf checkpoints scheduled every ${interval} minute(s), :0${JITTER_BASE_MIN}–:0${JITTER_BASE_MIN + 4} past the slot"
    }

    armTemperatureSampling()

    // Daily GitHub polls (UI sync 03:xx, release check 04:xx) at a per-install minute and second,
    // so hubs don't hit GitHub in step. The release check keeps the Apps-list "update available"
    // badge current when the config page is never opened; reconciling the label now clears it
    // right after the user updates the code.
    int gh = installOffset('githubPollOffsetSec', 3600)
    String ghMin = "${gh % 60} ${gh.intdiv(60)}"
    schedule("${ghMin} 3 * * ?", "scheduledUISync")
    schedule("${ghMin} 4 * * ?", "scheduledVersionCheck")
    logInfo "Daily UI sync and release check scheduled at minute ${gh.intdiv(60)} past 03:00 and 04:00"
    refreshUpdateLabel()

    if (settings.snapshotTriggerSwitch) {
        subscribe(settings.snapshotTriggerSwitch, "switch.on", "snapshotSwitchHandler")
        logCfg "Config snapshot trigger armed on ${settings.snapshotTriggerSwitch}"
    }
    if (settings.checkpointTriggerSwitch) {
        subscribe(settings.checkpointTriggerSwitch, "switch.on", "checkpointSwitchHandler")
        logCfg "Perf checkpoint trigger armed on ${settings.checkpointTriggerSwitch}"
    }
}

// ===== SWITCH TRIGGER HANDLERS =====

void snapshotSwitchHandler(evt) {
    checkVersion()
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
    checkVersion()
    if (evt?.value != "on") return
    logInfo "Perf checkpoint triggered by switch ${evt.displayName}"
    scheduledCheckpoint()                 // already has a 300s in-flight guard
}

void scheduledUISync() {
    checkVersion()
    logSched "Running scheduled UI sync"
    if (state.tempSampleOffsetSec == null) armTemperatureSampling()
    syncUI(false)
}

void scheduledVersionCheck() {
    checkVersion()
    logSched "Running scheduled GitHub version check"
    checkGithubVersion()   // stale-while-revalidate; the async callback refreshes the label
    refreshUpdateLabel()   // also reconcile the label against the already-cached version
}

void syncUIForced() {
    checkVersion()
    syncUI(true)
}

private String logPrefix() {
    return stripUpdateBadge((app?.label ?: app?.name ?: APP_NAME) as String)
}

// ── Logging (app) ─────────────────────────────────────────────────────
//   ⬇️ Evt  ⬆️ Cmd  🔧 Cfg  🌐 Net  ⏰ Sched  🏷️ Ver  ·  ⚠️ Warn  🛑 Error  🔬 Trace
private String logp(String e) { "${e} ${logPrefix()}: " }

void logEvt  (String m) { if (debugLogging) log.debug logp('⬇️') + m }
void logCmd  (String m) { if (txtEnable != false) log.info  logp('⬆️') + m }
void logCfg  (String m) { if (txtEnable != false) log.info  logp('🔧') + m }
void logNet  (String m) { if (debugLogging) log.debug logp('🌐') + m }
void logSched(String m) { if (debugLogging) log.debug logp('⏰') + m }
void logVer  (String m) { log.warn  logp('🏷️') + m }

void logWarn (String m) { log.warn  logp('⚠️') + m }
void logError(String m) { log.error logp('🛑') + m }
void logTrace(String m) { if (traceEnable) log.trace logp('🔬') + m }
void logInfo (String m) { if (txtEnable != false) log.info  "${logPrefix()}: ${m}" }
void logDebug(String m) { if (debugLogging) log.debug "${logPrefix()}: ${m}" }

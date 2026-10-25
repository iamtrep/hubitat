// Copyright (c) 2025-2026 PJ
// SPDX-License-Identifier: MIT

/**
 * Log Monitor — Hub Log Monitoring App
 *
 * Creates bridge child devices for WebSocket connectivity to one or more
 * hub logsockets. Supports multiple independent filter configurations,
 * each with its own output actions (notification, log file, HTTP POST).
 */

import com.hubitat.app.ChildDeviceWrapper
import groovy.transform.CompileStatic
import groovy.transform.Field
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.Matcher
import java.util.regex.Pattern

@Field static final String CODE_VERSION = "1.2.0"
@Field static final int MAX_BRIDGES = 5
@Field static final int MAX_FILTERS = 10
@Field static final int MAX_DEDUPE_HISTORY = 100
@Field static final int HTTP_MAX_BACKOFF_MIN = 60
@Field static final List<Integer> HTTP_STOP_STATUSES = [401, 403, 404, 410]

// Per-filter dedupe window and per-minute count, keyed by filterKey() (app id + filter id).
// They only matter within a dedupe window or a minute, so losing them on a push or reboot
// costs nothing, and keeping them in state.filters rewrote it on every matching log line.
@Field static final ConcurrentHashMap<String, ConcurrentHashMap<String, Long>> RECENT_EVENTS = new ConcurrentHashMap<>()
@Field static final ConcurrentHashMap<String, AtomicInteger> EVENTS_THIS_MINUTE = new ConcurrentHashMap<>()
// Decrypted POST URLs, kept in memory only: a webhook URL usually embeds its key, and decrypt()
// costs ~350 ms on a C-8 Pro, ~830 ms on a C-7. Keyed by app id + the stored ciphertext.
@Field static final ConcurrentHashMap<String, String> URL_CACHE = new ConcurrentHashMap<>()
// Registered names, longest first, for aliasNames(). Keyed by app id; rebuilt when the
// number of names in state.nameAliases changes.
@Field static final ConcurrentHashMap<String, List<String>> NAME_ORDER = new ConcurrentHashMap<>()
@Field static final int MAX_NAMES = 2000

definition(
    name: "Log Monitor",
    namespace: "iamtrep",
    author: "pj",
    description: "Monitor hub logs with multiple filters and output actions",
    menu: "Apps", // new in platform 2.5.0
    category: "Utility",
    singleInstance: true,
    singleThreaded: true,   // bridges call processLogEntry concurrently; each call rewrites state.filters
    importUrl: "https://raw.githubusercontent.com/iamtrep/hubitat/main/apps/LogMonitor/LogMonitor.groovy",
    documentationLink: "https://github.com/iamtrep/hubitat/tree/main/apps/LogMonitor",
    iconUrl: "",
    iconX2Url: ""
)

preferences {
    page(name: "mainPage")
    page(name: "bridgePage")
    page(name: "deleteBridgePage")
    page(name: "filterPage")
    page(name: "deleteFilterPage")
}

// ============================================================================
// Pages
// ============================================================================

Map mainPage() {
    savePendingBridge()
    savePendingFilter()

    dynamicPage(name: "mainPage", install: true, uninstall: true) {
        section("Bridges") {
            List<Map> bridges = state.bridges ?: []
            if (bridges.size() == 0) {
                paragraph "<i>No bridges configured. A local bridge will be created on install.</i>"
            } else {
                bridges.eachWithIndex { Map br, int i ->
                    ChildDeviceWrapper dev = getChildDevice(br.dni as String)
                    String status = dev?.currentValue("connectionStatus") ?: "unknown"
                    String ip = br.hubAddress ?: "local"
                    long received = dev?.getLogsReceivedCount() ?: 0
                    href(name: "editBridge${i}", page: "bridgePage",
                        title: "<b>${br.label}</b>",
                        description: "${ip} — <b>${status}</b> (${received} logs)",
                        params: [bridgeIndex: i])
                }
            }
            if (bridges.size() < MAX_BRIDGES) {
                href(name: "addBridge", page: "bridgePage",
                    title: "Add Bridge",
                    description: "Connect to another hub's logsocket",
                    params: [bridgeIndex: -1])
            }
        }
        section("Filters") {
            List<Map> filters = state.filters ?: []
            if (filters.size() == 0) {
                paragraph "<i>No filters configured yet.</i>"
            } else {
                filters.eachWithIndex { Map filter, int i ->
                    String levels = (filter.levels ?: []).join(", ")
                    String types = buildTypesSummary(filter)
                    String outputs = buildOutputsSummary(filter)
                    String source = filter.bridgeDni ? bridgeLabelForDni(filter.bridgeDni) : "all bridges"
                    href(name: "editFilter${i}", page: "filterPage",
                        title: "<b>${filter.label ?: "Filter ${i + 1}"}</b>",
                        description: "${source} | ${types} | ${levels}\n${outputs}",
                        params: [filterIndex: i])
                    href(name: "deleteFilter${i}", page: "deleteFilterPage",
                        title: "Delete ${filter.label ?: "Filter ${i + 1}"}",
                        description: "",
                        params: [filterIndex: i])
                }
            }
            if (filters.size() < MAX_FILTERS) {
                href(name: "addFilter", page: "filterPage",
                    title: "Add New Filter",
                    description: "Create a new log filter",
                    params: [filterIndex: -1])
            }
        }
        section("Logging") {
            input name: "txtEnable", type: "bool",
                title: "Enable info logging", defaultValue: true
            input name: "enableDebug", type: "bool",
                title: "Enable debug logging", defaultValue: false, submitOnChange: true
            if (enableDebug) {
                input name: "traceEnable", type: "bool",
                    title: "Enable trace logging", defaultValue: false
            }
        }
        section {
            paragraph "Version ${CODE_VERSION}"
        }
    }
}

// --- Bridge page ---

Map bridgePage(Map params) {
    if (state.editingBridgeIndex == null) {
        int idx = params?.bridgeIndex != null ? params.bridgeIndex as int : -1
        state.editingBridgeIndex = idx
        List<Map> bridges = state.bridges ?: []
        Map br = (idx >= 0 && idx < bridges.size()) ? bridges[idx] : [:]
        app.updateSetting("bLabel", [type: "text", value: br.label ?: ""])
        app.updateSetting("bHubAddress", [type: "text", value: br.hubAddress ?: ""])
    }

    int idx = state.editingBridgeIndex as int
    boolean isNew = (idx == -1)

    dynamicPage(name: "bridgePage", title: isNew ? "New Bridge" : "Edit Bridge", install: false, uninstall: false, nextPage: "mainPage") {
        section("Bridge Settings") {
            input name: "bLabel", type: "text", title: "Bridge name", required: true,
                description: "e.g., home, lab, cottage"
            input name: "bHubAddress", type: "text", title: "Hub IP address (leave blank for local hub)",
                required: false, description: "e.g., 192.0.2.10"
        }
        if (!isNew) {
            List<Map> bridges = state.bridges ?: []
            Map br = bridges[idx]
            ChildDeviceWrapper dev = getChildDevice(br?.dni as String)
            if (dev) {
                section("Status") {
                    String status = dev.currentValue("connectionStatus") ?: "unknown"
                    paragraph "Connection: <b>${status}</b>"
                    paragraph "<a href='/device/edit/${dev.id}' target='_blank'>Open bridge device</a>"
                }
            }
            section {
                href(name: "deleteBridge", page: "deleteBridgePage",
                    title: "Delete this bridge",
                    description: "Remove bridge and its device",
                    params: [bridgeIndex: idx])
            }
        }
    }
}

Map deleteBridgePage(Map params) {
    int idx = params?.bridgeIndex != null ? params.bridgeIndex as int : (state.editingBridgeIndex != null ? state.editingBridgeIndex as int : -1)
    List<Map> bridges = state.bridges ?: []
    String label = "Unknown"

    if (idx >= 0 && idx < bridges.size()) {
        Map br = bridges[idx]
        label = br.label ?: "Bridge ${idx}"
        try {
            deleteChildDevice(br.dni)
        } catch (Exception e) {
            logDebug "Error deleting bridge device: ${e.message}"
        }
        bridges.remove(idx)
        state.bridges = bridges
    }

    state.remove("editingBridgeIndex")
    clearBridgeSettings()

    dynamicPage(name: "deleteBridgePage", title: "Bridge Deleted", install: false, uninstall: false, nextPage: "mainPage") {
        section {
            paragraph "<b>${label}</b> has been deleted."
        }
    }
}

// --- Filter page ---

Map filterPage(Map params) {
    if (state.editingFilterIndex == null) {
        int idx = params?.filterIndex != null ? params.filterIndex as int : -1
        state.editingFilterIndex = idx
        List<Map> filters = state.filters ?: []
        Map filter = (idx >= 0 && idx < filters.size()) ? filters[idx] : [:]
        populateSettingsFromFilter(filter)
    }

    int idx = state.editingFilterIndex as int
    boolean isNew = (idx == -1)
    String pageTitle = isNew ? "New Filter" : "Edit Filter"

    // Build bridge options for source selector
    List<Map> bridges = state.bridges ?: []
    Map bridgeOptions = ["": "All bridges"]
    bridges.each { Map br ->
        String ip = br.hubAddress ?: "local"
        bridgeOptions[br.dni] = "${br.label} (${ip})"
    }

    dynamicPage(name: "filterPage", title: pageTitle, install: false, uninstall: false, nextPage: "mainPage") {
        section("Filter Settings") {
            input name: "fLabel", type: "text", title: "Filter name", required: true
            if (bridges.size() > 1) {
                input name: "fBridgeDni", type: "enum", title: "Source bridge",
                    options: bridgeOptions, defaultValue: "", required: false
            }
        }
        section("Log Types") {
            input name: "fMonitorDev", type: "bool", title: "Device logs", defaultValue: true
            input name: "fMonitorApp", type: "bool", title: "App logs", defaultValue: true
            input name: "fMonitorSys", type: "bool", title: "System logs", defaultValue: true
        }
        section("Log Levels") {
            input name: "fLevels", type: "enum",
                title: "Levels to monitor",
                options: ["trace", "debug", "info", "warn", "error"],
                multiple: true, required: true, defaultValue: ["error"]
        }
        section("ID Filters (optional)") {
            input name: "fDeviceIds", type: "text",
                title: "Device IDs (comma-separated)", required: false,
                description: "e.g., 123,456,789"
            input name: "fAppIds", type: "text",
                title: "App IDs (comma-separated)", required: false,
                description: "e.g., 42,87,154"
        }
        section("Pattern Filters (optional)") {
            input name: "fIncludePattern", type: "text",
                title: "Include pattern (regex)", required: false,
                description: "e.g., (?i)offline|battery|failed"
            input name: "fExcludePattern", type: "text",
                title: "Exclude pattern (regex)", required: false,
                description: "e.g., (?i)heartbeat|poll"
        }
        section("Rate Limiting") {
            input name: "fDedupeWindow", type: "number",
                title: "Dedupe window (seconds)", defaultValue: 5, range: "0..300"
            input name: "fMaxPerMinute", type: "number",
                title: "Max events per minute", defaultValue: 30, range: "1..60"
        }
        section("Output: Notification") {
            input name: "fOutputNotify", type: "bool",
                title: "Send to notification device(s)", defaultValue: false,
                submitOnChange: true
            if (settings.fOutputNotify) {
                int settingsIdx = (idx >= 0) ? idx : ((state.filters ?: []).size())
                input name: "notifyDevices_${settingsIdx}", type: "capability.notification",
                    title: "Notification devices", multiple: true, required: true
            }
        }
        section("Output: Log File") {
            input name: "fOutputLog", type: "bool",
                title: "Append to hub log file", defaultValue: false,
                submitOnChange: true
            if (settings.fOutputLog) {
                input name: "fLogFile", type: "text",
                    title: "File name", required: true,
                    description: "e.g., logmonitor.csv"
            }
        }
        section("Output: HTTP POST") {
            input name: "fOutputHttp", type: "bool",
                title: "POST to URL", defaultValue: false,
                submitOnChange: true
            if (settings.fOutputHttp) {
                List<Map> saved = state.filters ?: []
                boolean stored = idx >= 0 && idx < saved.size() && saved[idx].httpUrlEnc
                input name: "fHttpUrl", type: "text",
                    title: stored ? "URL (stored: ${saved[idx].httpUrlShown ?: 'encrypted'}; enter only to change it)" : "URL", required: !stored,
                    description: "e.g., https://example.com/webhook"
            }
        }
        section("Privacy") {
            paragraph "Passwords, tokens, keys and webhook keys in a log line are always masked, in every output and in this app's own logs."
            input name: "fHidePrivate", type: "bool",
                title: "Hide private details: replace device, app, room and hub names with aliases, and mask IP and MAC addresses, emails, phone numbers, coordinates and serial numbers",
                defaultValue: false
        }
        if (!isNew) {
            section {
                List<Map> filters = state.filters ?: []
                int matched = (idx >= 0 && idx < filters.size()) ? (filters[idx].eventsMatched ?: 0) : 0
                paragraph "Events matched: ${matched}"
                href(name: "deleteFilter", page: "deleteFilterPage",
                    title: "Delete this filter",
                    description: "Remove this filter permanently",
                    params: [filterIndex: idx])
            }
        }
    }
}

Map deleteFilterPage(Map params) {
    int idx = params?.filterIndex != null ? params.filterIndex as int : (state.editingFilterIndex != null ? state.editingFilterIndex as int : -1)
    List<Map> filters = state.filters ?: []
    String label = "Unknown"

    if (idx >= 0 && idx < filters.size()) {
        label = filters[idx]?.label ?: "Filter ${idx + 1}"
        clearFilterRuntime(filters[idx])
        app.removeSetting("notifyDevices_${idx}")
        filters.remove(idx)
        // Re-key notify settings for filters that shifted down
        for (int i = idx; i < filters.size(); i++) {
            if (filters[i].notifySettingsKey) {
                String oldKey = "notifyDevices_${i + 1}"
                String newKey = "notifyDevices_${i}"
                List devs = settings[oldKey] as List
                if (devs) {
                    app.updateSetting(newKey, devs)
                }
                app.removeSetting(oldKey)
                filters[i].notifySettingsKey = newKey
            }
        }
        state.filters = filters
        Set<String> urls = filters.findAll { it.outputHttp && it.httpUrlEnc }.collect { it.httpUrlEnc as String } as Set
        state.httpBackoff = ((state.httpBackoff ?: [:]) as Map).findAll { k, v -> k in urls }
    }

    state.remove("editingFilterIndex")
    clearFilterSettings()

    dynamicPage(name: "deleteFilterPage", title: "Filter Deleted", install: false, uninstall: false, nextPage: "mainPage") {
        section {
            paragraph "<b>${label}</b> has been deleted."
        }
    }
}

// ============================================================================
// Lifecycle
// ============================================================================

void installed() {
    logDebug "installed()"
    initialize()
}

void updated() {
    logDebug "updated()"
    savePendingBridge()
    savePendingFilter()
    initialize()
    if (enableDebug || traceEnable) runIn(1800, "logsOff")
}

void logsOff() {
    app.updateSetting("enableDebug", [value: "false", type: "bool"])
    app.updateSetting("traceEnable", [value: "false", type: "bool"])
    logWarn "Debug/trace logging auto-disabled"
}

private void initialize() {
    checkVersion(false)
    state.bridges = state.bridges ?: []
    state.filters = state.filters ?: []
    migrateFilters()

    // Create a default local bridge if none exist
    if ((state.bridges as List).size() == 0) {
        String dni = "logmon-bridge-0"
        addChildDevice("iamtrep", "Log Monitor Bridge", dni,
            [name: "Log Monitor Bridge", label: "Local Hub", isComponent: false])
        state.bridges = [[label: "Local Hub", dni: dni, hubAddress: ""]]
    }

    unschedule("resetRateLimitCounters")
    runEvery1Minute("resetRateLimitCounters")

    refreshNotifyDeviceIds()

    unschedule("seedNames")
    if ((state.filters as List).any { it.hidePrivate }) {
        runIn(5, "seedNames")
        schedule("0 ${new Random().nextInt(60)} 4 * * ?", "seedNames")
    } else {
        // The salt stays, so most aliases come back the same if a filter hides names again.
        state.remove("nameAliases")
    }
}

private void checkVersion(boolean reinit = true) {
    if (state.version == CODE_VERSION) return
    logVer "New version: ${CODE_VERSION} (was: ${state.version})"
    state.version = CODE_VERSION
    migrateFilters()
    if (reinit) runIn(1, "updated")
}

// Filters get a stable id because their in-memory dedupe and rate data must not follow a
// list index that shifts when a filter is deleted. processedEvents and eventsThisMinute
// moved out of state.filters in 1.1.6. The POST URL is kept encrypted from 1.1.9.
private void migrateFilters() {
    List<Map> filters = state.filters ?: []
    boolean plainUrls = false
    filters.each { Map f ->
        if (f.id == null) f.id = nextFilterId(filters)
        f.remove("processedEvents")
        f.remove("eventsThisMinute")
        if (f.httpUrl) {
            f.httpUrlEnc = encrypt(f.httpUrl as String)
            plainUrls = true
        }
        f.remove("httpUrl")
        // 1.1.10 shows the URL masked; filters saved before have no masked copy yet.
        if (f.httpUrlEnc && !f.httpUrlShown) {
            String plain = filterHttpUrl(f)
            if (plain) f.httpUrlShown = maskUrl(plain)
        }
    }
    state.filters = filters
    // Its keys were the plain URLs.
    if (plainUrls) state.remove("httpBackoff")
}

private int nextFilterId(List<Map> filters) {
    int maxUsed = (filters.collect { it.id != null ? it.id as int : 0 }.max() ?: 0) as int
    int next = Math.max((state.nextFilterId ?: 0) as int, maxUsed) + 1
    state.nextFilterId = next
    return next
}

private String filterKey(Map filter) {
    return "${app.id}:${filter.id}"
}

private void clearFilterRuntime(Map filter) {
    String key = filterKey(filter)
    RECENT_EVENTS.remove(key)
    EVENTS_THIS_MINUTE.remove(key)
}

// IDs of every device used as a notification target, for the loop guard in
// executeOutputs. Rebuilt on updated(), and lazily if a code push (which does
// not run initialize()) left the cache empty.
private List refreshNotifyDeviceIds() {
    Set<String> notifyIds = [] as Set
    (state.filters as List ?: []).each { Map f ->
        if (f.outputNotify && f.notifySettingsKey) {
            (settings[f.notifySettingsKey as String] ?: []).each { dev ->
                if (dev?.id != null) notifyIds << dev.id.toString()
            }
        }
    }
    List result = notifyIds as List
    state.notifyDeviceIds = result
    return result
}

private List currentNotifyDeviceIds() {
    List ids = state.notifyDeviceIds
    return ids != null ? ids : refreshNotifyDeviceIds()
}

void uninstalled() {
    logDebug "uninstalled()"
    (state.bridges ?: []).each { Map br ->
        try { deleteChildDevice(br.dni) } catch (Exception e) {}
    }
    URL_CACHE.keySet().removeIf { it.startsWith("${app.id}:") }
    NAME_ORDER.keySet().removeIf { it.startsWith("${app.id}:") }
}

// ============================================================================
// Bridge Persistence
// ============================================================================

private void savePendingBridge() {
    if (!settings.containsKey("bLabel") || !settings.bLabel) {
        // A page left without a name must not leave its index behind: the next edit link would open a new form.
        state.remove("editingBridgeIndex")
        clearBridgeSettings()
        return
    }

    int idx = state.editingBridgeIndex != null ? state.editingBridgeIndex as int : -1
    List<Map> bridges = state.bridges ?: []
    String label = settings.bLabel
    String hubAddress = settings.bHubAddress ?: ""

    if (idx >= 0 && idx < bridges.size()) {
        // Edit existing bridge
        Map br = bridges[idx]
        br.label = label
        br.hubAddress = hubAddress
        bridges[idx] = br

        // Update device label and IP setting
        ChildDeviceWrapper dev = getChildDevice(br.dni as String)
        if (dev) {
            dev.setLabel(label)
            dev.updateSetting("hubAddress", [type: "text", value: hubAddress])
        }
    } else {
        // New bridge
        int nextIdx = bridges.size()
        String dni = "logmon-bridge-${nextIdx}"

        // Ensure unique DNI
        while (getChildDevice(dni)) {
            nextIdx++
            dni = "logmon-bridge-${nextIdx}"
        }

        addChildDevice("iamtrep", "Log Monitor Bridge", dni,
            [name: "Log Monitor Bridge", label: label, isComponent: false])

        // Set IP on the new device
        ChildDeviceWrapper dev = getChildDevice(dni)
        if (dev && hubAddress) {
            dev.updateSetting("hubAddress", [type: "text", value: hubAddress])
        }

        bridges.add([label: label, dni: dni, hubAddress: hubAddress])
    }

    state.bridges = bridges
    state.remove("editingBridgeIndex")
    clearBridgeSettings()
}

private void clearBridgeSettings() {
    ["bLabel", "bHubAddress"].each { app.removeSetting(it) }
}

// ============================================================================
// Filter Persistence
// ============================================================================

private void populateSettingsFromFilter(Map filter) {
    app.updateSetting("fLabel", [type: "text", value: filter.label ?: ""])
    app.updateSetting("fBridgeDni", [type: "enum", value: filter.bridgeDni ?: ""])
    app.updateSetting("fMonitorDev", [type: "bool", value: filter.monitorDev != false])
    app.updateSetting("fMonitorApp", [type: "bool", value: filter.monitorApp != false])
    app.updateSetting("fMonitorSys", [type: "bool", value: filter.monitorSys != false])
    app.updateSetting("fLevels", [type: "enum", value: filter.levels ?: ["error"]])
    app.updateSetting("fDeviceIds", [type: "text", value: filter.deviceIds ?: ""])
    app.updateSetting("fAppIds", [type: "text", value: filter.appIds ?: ""])
    app.updateSetting("fIncludePattern", [type: "text", value: filter.includePattern ?: ""])
    app.updateSetting("fExcludePattern", [type: "text", value: filter.excludePattern ?: ""])
    app.updateSetting("fDedupeWindow", [type: "number", value: filter.dedupeWindow != null ? filter.dedupeWindow : 5])
    app.updateSetting("fMaxPerMinute", [type: "number", value: filter.maxPerMinute != null ? filter.maxPerMinute : 30])
    app.updateSetting("fOutputNotify", [type: "bool", value: filter.outputNotify ?: false])
    app.updateSetting("fOutputLog", [type: "bool", value: filter.outputLog ?: false])
    app.updateSetting("fLogFile", [type: "text", value: filter.logFile ?: ""])
    app.updateSetting("fOutputHttp", [type: "bool", value: filter.outputHttp ?: false])
    app.updateSetting("fHidePrivate", [type: "bool", value: filter.hidePrivate ?: false])
    // The stored URL stays encrypted; an empty field on save keeps it.
    app.removeSetting("fHttpUrl")
}

private void savePendingFilter() {
    if (!settings.containsKey("fLabel") || !settings.fLabel) {
        // A page left without a name must not leave its index behind: the next edit link would open a new form.
        state.remove("editingFilterIndex")
        clearFilterSettings()
        return
    }

    int idx = state.editingFilterIndex != null ? state.editingFilterIndex as int : -1
    List<Map> filters = state.filters ?: []

    int actualIdx = (idx >= 0) ? idx : filters.size()
    Map filter = buildFilterFromSettings(actualIdx)
    if (settings.fHttpUrl) {
        filter.httpUrlEnc = encrypt(settings.fHttpUrl as String)
        filter.httpUrlShown = maskUrl(settings.fHttpUrl as String)
    } else if (idx >= 0 && idx < filters.size() && filters[idx].httpUrlEnc) {
        filter.httpUrlEnc = filters[idx].httpUrlEnc
        filter.httpUrlShown = filters[idx].httpUrlShown
    }

    if (idx >= 0 && idx < filters.size()) {
        filter.id = filters[idx].id != null ? filters[idx].id : nextFilterId(filters)
        filter.eventsMatched = filters[idx].eventsMatched ?: 0
        filters[idx] = filter
    } else {
        filter.id = nextFilterId(filters)
        filter.eventsMatched = 0
        filters.add(filter)
    }

    state.filters = filters
    // Saving a filter retries its URL now; drop backoff for URLs no filter uses any more.
    // Backoff is keyed by the ciphertext: encrypt() is deterministic, so equal URLs share a key.
    Set<String> urls = filters.findAll { it.outputHttp && it.httpUrlEnc }.collect { it.httpUrlEnc as String } as Set
    state.httpBackoff = ((state.httpBackoff ?: [:]) as Map).findAll { k, v -> k in urls && k != filter.httpUrlEnc }
    state.remove("editingFilterIndex")
    clearFilterSettings()
}

private Map buildFilterFromSettings(int filterIdx) {
    return [
        label:              settings.fLabel,
        bridgeDni:          settings.fBridgeDni ?: "",
        monitorDev:         settings.fMonitorDev != false,
        monitorApp:         settings.fMonitorApp != false,
        monitorSys:         settings.fMonitorSys != false,
        levels:             settings.fLevels ?: ["error"],
        deviceIds:          settings.fDeviceIds ?: "",
        appIds:             settings.fAppIds ?: "",
        includePattern:     settings.fIncludePattern ?: "",
        excludePattern:     settings.fExcludePattern ?: "",
        dedupeWindow:       settings.fDedupeWindow != null ? settings.fDedupeWindow as int : 5,
        maxPerMinute:       settings.fMaxPerMinute != null ? settings.fMaxPerMinute as int : 30,
        outputNotify:       settings.fOutputNotify ?: false,
        notifySettingsKey:  "notifyDevices_${filterIdx}",
        outputLog:          settings.fOutputLog ?: false,
        logFile:            settings.fLogFile ?: "",
        outputHttp:         settings.fOutputHttp ?: false,
        hidePrivate:        settings.fHidePrivate ?: false
    ]
}

private void clearFilterSettings() {
    ["fLabel", "fBridgeDni", "fMonitorDev", "fMonitorApp", "fMonitorSys", "fLevels",
     "fDeviceIds", "fAppIds", "fIncludePattern", "fExcludePattern",
     "fDedupeWindow", "fMaxPerMinute", "fOutputNotify",
     "fOutputLog", "fLogFile", "fOutputHttp", "fHttpUrl", "fHidePrivate"].each {
        app.removeSetting(it)
    }
}

// ============================================================================
// Log Entry Processing (called by bridge driver via parent.processLogEntry)
// ============================================================================

void processLogEntry(String bridgeDni, Map logEntry) {
    checkVersion()
    // Self-monitoring guard: skip own app logs
    if (logEntry.type == "app" && logEntry.id?.toString() == app.id.toString()) return

    List<Map> filters = state.filters
    if (!filters) return

    int size = filters.size()
    boolean filtersModified = false
    Map views = [:]   // masked forms of this entry, built once for all the filters that match

    for (int i = 0; i < size; i++) {
        Map filter = filters[i]

        // Check bridge source filter
        String filterBridge = filter.bridgeDni as String
        if (filterBridge && filterBridge != bridgeDni) continue

        if (matchesFilter(logEntry, filter)) {
            def matchedBefore = filter.eventsMatched
            filter = executeOutputs(logEntry, filter, bridgeDni, views)
            filters[i] = filter
            // Duplicates and rate-limited lines leave the filter unchanged: skip the write
            if (filter.eventsMatched != matchedBefore) filtersModified = true
        }
    }

    if (filtersModified) {
        state.filters = filters
    }
}

// ============================================================================
// Filter Matching
// ============================================================================

private static boolean matchesFilter(Map logEntry, Map filter) {
    String type = logEntry.type as String
    if (type == "dev" && !(filter.monitorDev as boolean)) return false
    if (type == "app" && !(filter.monitorApp as boolean)) return false
    if (type == "sys" && !(filter.monitorSys as boolean)) return false

    List<String> levels = filter.levels as List<String>
    if (!levels?.contains(logEntry.level as String)) return false

    String deviceIds = filter.deviceIds as String
    String appIds = filter.appIds as String
    if (deviceIds || appIds) {
        boolean idMatch = false

        if (deviceIds && type == "dev") {
            List<String> ids = deviceIds.split(',').collect { it.trim() }
            if (ids.contains(logEntry.id?.toString())) idMatch = true
        }
        if (appIds && type == "app") {
            List<String> ids = appIds.split(',').collect { it.trim() }
            if (ids.contains(logEntry.id?.toString())) idMatch = true
        }

        if (!idMatch) return false
    }

    String includePattern = filter.includePattern as String
    if (includePattern) {
        String msg = logEntry.msg as String ?: ""
        if (!(msg =~ includePattern)) return false
    }

    String excludePattern = filter.excludePattern as String
    if (excludePattern) {
        String msg = logEntry.msg as String ?: ""
        if (msg =~ excludePattern) return false
    }

    return true
}

private boolean isDuplicate(Map logEntry, Map filter) {
    int window = (filter.dedupeWindow as int) ?: 5
    if (window == 0) return false

    long ts = now()
    long windowMs = window * 1000L
    String signature = "${logEntry.type}:${logEntry.level}:${logEntry.name}:${logEntry.msg}"

    ConcurrentHashMap<String, Long> seen = RECENT_EVENTS.computeIfAbsent(filterKey(filter),
        { String k -> new ConcurrentHashMap<String, Long>() } as java.util.function.Function)

    // Atomic check-and-insert, so the map stays correct without relying on singleThreaded.
    Long prev = seen.putIfAbsent(signature, ts)
    if (prev != null) {
        if ((ts - prev) < windowMs) return true
        if (!seen.replace(signature, prev, ts)) return true
    }

    // Drop expired entries, then the oldest beyond the cap. remove(key, value) leaves an
    // entry alone if it was refreshed meanwhile.
    seen.each { String sig, Long seenAt ->
        if ((ts - seenAt) >= windowMs) seen.remove(sig, seenAt)
    }
    int excess = seen.size() - MAX_DEDUPE_HISTORY
    if (excess > 0) {
        (seen.entrySet() as List).sort { it.value }.take(excess).each { seen.remove(it.key, it.value) }
    }
    return false
}

private boolean isRateLimited(Map filter) {
    int limit = (filter.maxPerMinute as int) ?: 30
    int thisMinute = EVENTS_THIS_MINUTE.computeIfAbsent(filterKey(filter),
        { String k -> new AtomicInteger() } as java.util.function.Function).incrementAndGet()

    if (thisMinute > limit) {
        logDebug "Rate limit exceeded for ${filter.label} (${limit}/min)"
        return true
    }
    return false
}

// ============================================================================
// Output Actions
// ============================================================================

// The entry as outputs see it: secrets masked always, and with hidePrivate also names aliased
// and identifiers masked. Matching and dedupe use the raw entry.
private Map entryView(Map logEntry, String bridgeDni, boolean hidePrivate, Map views) {
    String key = hidePrivate ? "private" : "secrets"
    if (views[key]) return views[key] as Map
    String bridge = bridgeLabelForDni(bridgeDni)
    String name = logEntry.name as String ?: ""
    String msg = maskSecrets(logEntry.msg as String ?: "")
    if (hidePrivate) {
        Map<String, String> aliases = nameAliases()
        boolean learn = logEntry.type == "dev" || logEntry.type == "app"
        if (learn && name) registerNames(aliases, [name])
        registerNames(aliases, [bridge])
        List<String> order = nameOrder(aliases)
        bridge = aliases[bridge] ?: bridge
        if (learn) name = aliases[name] ?: name
        msg = maskIdentifiers(aliasNames(msg, order, aliases))
    }
    Map v = [bridge: bridge, name: name, msg: msg]
    views[key] = v
    return v
}

private Map executeOutputs(Map logEntry, Map filter, String bridgeDni, Map views) {
    if (isDuplicate(logEntry, filter)) return filter
    if (isRateLimited(filter)) return filter

    filter.eventsMatched = ((filter.eventsMatched as int) ?: 0) + 1

    Map own = entryView(logEntry, bridgeDni, false, views)
    logInfo "${filter.label}: [${own.bridge}/${logEntry.type}/${logEntry.level}] ${own.name}: ${own.msg}"
    Map v = filter.hidePrivate ? entryView(logEntry, bridgeDni, true, views) : own
    String bridgeLabel = v.bridge
    String formatted = "[${bridgeLabel}/${logEntry.type}/${logEntry.level}] ${v.name}: ${v.msg}"

    // Notification — but never notify about a log line that came from a
    // notification device. A failed send (e.g. the notifier is offline) logs an
    // error, which would match and re-notify, feeding itself into a storm. The
    // log/HTTP outputs below don't loop, so they still run for such entries.
    boolean fromNotifier = logEntry.type == "dev" &&
        currentNotifyDeviceIds().contains(logEntry.id?.toString())
    if (filter.outputNotify && filter.notifySettingsKey && !fromNotifier) {
        try {
            List devices = settings[filter.notifySettingsKey] ?: []
            devices.each { dev ->
                dev.deviceNotification("${filter.label}: ${formatted}")
            }
        } catch (Exception e) {
            logDebug "Notification error for ${filter.label}: ${maskSecrets(e.message)}"
        }
    }

    // Log file
    if (filter.outputLog && filter.logFile) {
        try {
            String timestamp = new Date().format("yyyy-MM-dd HH:mm:ss")
            String escapedName = (v.name as String).replace('"', '""')
            String escapedMsg = (v.msg as String).replace('"', '""')
            String line = "${timestamp},${bridgeLabel},${logEntry.type},${logEntry.level},\"${escapedName}\",\"${escapedMsg}\"\n"
            appendToFile(filter.logFile as String, line)
        } catch (Exception e) {
            logDebug "Log file error for ${filter.label}: ${maskSecrets(e.message)}"
        }
    }

    // HTTP POST
    String httpUrl = filter.outputHttp ? filterHttpUrl(filter) : null
    if (httpUrl && httpPaused(filter.httpUrlEnc as String)) {
        logNet "HTTP POST for ${filter.label} skipped: URL paused after failures"
    } else if (httpUrl) {
        try {
            Map postParams = [
                uri: httpUrl,
                contentType: "application/json",
                body: [
                    filter: filter.label,
                    bridge: bridgeLabel,
                    type:   logEntry.type,
                    level:  logEntry.level,
                    name:   v.name,
                    msg:    v.msg,
                    id:     logEntry.id,
                    time:   logEntry.time
                ],
                timeout: 5
            ]
            asynchttpPost("httpPostCallback", postParams, [url: filter.httpUrlEnc, shown: filter.httpUrlShown ?: maskUrl(httpUrl)])
        } catch (Exception e) {
            // A URISyntaxException quotes the whole URL.
            logNet "HTTP POST error for ${filter.label}: ${maskSecrets(e.message)}"
        }
    }

    return filter
}

// Failed POSTs back off per URL so a dead or overloaded endpoint (e.g. a cloud
// relay URL) isn't hit at the full per-filter rate. 401/403/404/410 won't fix
// themselves: POSTs stop until a filter using the URL is saved again.
void httpPostCallback(resp, data) {
    String url = data?.url as String    // the ciphertext, which keys state.httpBackoff
    String shown = data?.shown as String
    int status = resp.getStatus()
    if (!resp.hasError() && status >= 200 && status < 300) {
        if (url && state.httpBackoff?.containsKey(url)) {
            Map all = state.httpBackoff as Map
            all.remove(url)
            state.httpBackoff = all
        }
        return
    }
    String reason = resp.hasError() ? "HTTP ${status}: ${maskSecrets(resp.getErrorMessage())}" : "HTTP ${status}"
    if (!url) {
        logWarn "HTTP POST failed: ${reason}"
        return
    }
    Map all = (state.httpBackoff ?: [:]) as Map
    Map b = (all[url] ?: [failures: 0]) as Map
    b.failures = (b.failures as int) + 1
    if (status in HTTP_STOP_STATUSES) {
        b.stopped = true
        logWarn "HTTP POST to ${shown} failed (${reason}); POSTs to this URL stopped until a filter using it is saved again"
    } else {
        long delayMs = Math.min(1L << Math.min((b.failures as int) - 1, 6), (long) HTTP_MAX_BACKOFF_MIN) * 60000L
        Long retryAfter = retryAfterSeconds(resp)
        if (retryAfter) delayMs = Math.max(delayMs, retryAfter * 1000L)
        b.until = now() + delayMs
        logWarn "HTTP POST to ${shown} failed (${reason}); pausing POSTs to this URL for ${delayMs.intdiv(1000L)} s (failure ${b.failures})"
    }
    all[url] = b
    state.httpBackoff = all
}

private boolean httpPaused(String url) {
    Map b = (state.httpBackoff ?: [:])[url] as Map
    if (!b) return false
    return b.stopped || now() < ((b.until ?: 0) as long)
}

private Long retryAfterSeconds(resp) {
    try {
        String v = resp.getHeaders()?.find { k, val -> (k as String)?.equalsIgnoreCase("Retry-After") }?.value as String
        return v?.trim()?.isLong() ? v.trim().toLong() : null
    } catch (Exception e) {
        return null
    }
}

// ============================================================================
// Masking
// ============================================================================
// Everything from here to "End of masking" is static, so tests/test-masking.groovy runs it off
// the hub. Shown forms follow ARCHITECTURE.md "Secrets: storage and display": a token or key
// keeps its last 4 characters after bullets; a password, PIN or Basic credential is bullets only.

@Field static final String DOT = "•"
@Field static final String BULLETS = "••••••••"

// A key whose value is a secret, and keys that only describe one (tokenExpiry, authUrl).
@Field static final Pattern SECRET_KEY = ~/(?i)token|passw|passphrase|secret|credential|cookie|bearer|auth(?!or(?!ization))|api_?key|private_?key|signature|[a-z_]key$|^(?:pin|pass|pwd|key|sig)$/
@Field static final Pattern META_KEY = ~/(?i)(?:error|errors|message|msg|status|url|uri|enabled|failed|expiry|expires|expires_?in|type|count|time|date|name|label|title|description|hint|scope|\.(?:py|sh|md|json|groovy|html|js|txt))$/
@Field static final Pattern PASSWORD_KEY = ~/(?i)passw|passphrase|pwd|^pin$|pin_?code|^pass$/
// Source code next to a secret-looking key, not a value: a call or a dotted property path.
@Field static final Pattern CODE_REF = ~/^[A-Za-z_][\w.]*\(|^[A-Za-z_]+(?:\.[A-Za-z_]+)+$/
@Field static final List<String> AUTH_SCHEMES = ["basic", "bearer", "digest", "token", "negotiate"]

@Field static final Pattern PEM_BEGIN = ~/-----BEGIN (?:[A-Z]+ )?PRIVATE KEY-----/
@Field static final Pattern PEM_END = ~/-----END (?:[A-Z]+ )?PRIVATE KEY-----/
@Field static final Pattern PEM_BODY = ~/[A-Za-z0-9+\/=]{16,}/
@Field static final Pattern AUTH_SCHEME = ~/(?i)\b(?:proxy-)?authorization["']?\s*[=:]\s*["']?(basic|bearer|digest|token|negotiate)\s+([^\s"',;)\]}<>]+)/
@Field static final Pattern AUTH_BARE = ~/(?i)\b(?:proxy-)?authorization["']?\s*[=:]\s*["']?(?!(?:basic|bearer|digest|token|negotiate)\b)([^\s&"',;)\]}<>]{4,})/
@Field static final Pattern BEARER = ~/(?i)\bbearer\s+([\w.~+\/=-]{8,})/
@Field static final Pattern URL_IN_TEXT = ~/(?i)\b[a-z][a-z0-9+.-]*:\/\/[^\s<>"'`)\]}]+/
@Field static final Pattern JWT = ~/\beyJ[\w-]{8,}\.[\w-]{8,}\.[\w-]{8,}/
// Hubitat's access tokens are UUIDs, so a bare UUID is treated as one.
@Field static final Pattern UUID_IN_TEXT = ~/(?<![\w•-])[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}(?![\w-])/
// [pattern, key group, value group, quoted]: "key": "value", key: 'value', key=value / key: value.
@Field static final List<List> KEY_VALUE = [
    [~/(["'])([A-Za-z_][A-Za-z0-9_.-]*)\1\s*:\s*(["'])((?:\\.|(?!\3).)*)\3/, 2, 4, true],
    [~/(?<![A-Za-z0-9_"'])([A-Za-z_][A-Za-z0-9_.-]*)\s*[:=]\s*(["'])((?:\\.|(?!\2).)*)\2/, 1, 3, true],
    [~/(?<![A-Za-z0-9_"'])([A-Za-z_][A-Za-z0-9_.-]*)(?:=|:[ \t]*)([^\s"',;&\]\)}<>]+)/, 1, 2, false]
]

// Identifiers, masked only for a filter that hides private details.
@Field static final Pattern EMAIL = ~/\b[\w.+-]+@[\w-]+(?:\.[\w-]+)+\b/
@Field static final Pattern MAC = ~/(?<![0-9A-Fa-f])(?<![0-9A-Fa-f]{2}[:-])(?:[0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}(?![0-9A-Fa-f]|[:-][0-9A-Fa-f])/
@Field static final Pattern MAC_KEYED = ~/(?i)\bmac(?:\s*address)?["']?\s*[=:]\s*["']?([0-9a-f]{12})\b/
@Field static final Pattern EUI64 = ~/(?<![0-9A-Fa-f])(?<![0-9A-Fa-f]{2}:)(?:[0-9A-Fa-f]{2}:){7}[0-9A-Fa-f]{2}(?![0-9A-Fa-f]|:[0-9A-Fa-f])/
@Field static final Pattern EUI64_PACKED = ~/(?<![0-9A-Za-z])[0-9A-F]{16}(?![0-9A-Za-z])/
@Field static final Pattern IPV6 = ~/(?<![\w:.])(?:[0-9a-fA-F]{0,4}:){2,7}[0-9a-fA-F]{0,4}(?![\w:.])/
@Field static final Pattern IPV4_HEX = ~/(?i)\bip["']?\s*[=:]\s*["']?([0-9a-f]{8})\b/
@Field static final Pattern IPV4 = ~/(?<![\d.])(?:25[0-5]|2[0-4]\d|1?\d?\d)(?:\.(?:25[0-5]|2[0-4]\d|1?\d?\d)){3}(?![\d.])/
@Field static final Pattern PHONE = ~/(?<![\w+])(?:\+?1[ .-]?)?\(?[2-9]\d{2}\)?[ .-]\d{3}[ .-]\d{4}(?!\d)/
@Field static final Pattern GEO_KEYED = ~/(?i)\b(?:lat(?:itude)?|lon(?:gitude)?|lng)["']?\s*[=:]\s*["']?(-?\d{1,3}\.\d{2,})/
@Field static final Pattern GEO_PAIR = ~/(?<![\d.])-?\d{1,2}\.\d{3,},\s*-?\d{1,3}\.\d{3,}(?![\d.])/
@Field static final Pattern SERIAL = ~/(?i)\b(?:serial(?:\s*(?:number|no))?|s\/n|sn)["']?\s*[=:]\s*["']?([\w-]{4,})/
@Field static final Pattern SSID = ~/(?i)\b(?:ssid|wifi\s*name|network\s*name)["']?\s*[=:]\s*["']?([^\s,;"'<>]{1,32})/
@Field static final Pattern ZWAVE_HOME_ID = ~/(?i)\bhome\s*id["']?\s*[=:]\s*["']?((?:0x)?[0-9a-f]{8})\b/
@Field static final Pattern MDNS_HOST = ~/(?i)\b(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+local\b/

// Name aliases, as Hub Inspector makes them.
@Field static final List<String> ADJECTIVES = ['amber','azure','brave','brisk','bronze','calm','clever','coral','crimson','dapper','eager','fierce','frosty','gentle','glossy','golden','grand','hardy','hidden','humble','icy','indigo','jolly','keen','lively','lucky','merry','misty','mossy','noble','olive','plucky','quiet','rapid','rosy','rustic','sandy','silent','silver','sleek','smooth','snowy','solid','spry','stormy','sturdy','sunny','swift','tame','tidy','vivid','warm','wild','witty','zesty']
@Field static final List<String> NOUNS = ['acorn','badger','beacon','beetle','bramble','canyon','cedar','cliff','comet','cricket','dahlia','delta','ember','falcon','ferret','finch','garnet','geode','glade','harbor','heron','iris','jade','kestrel','lagoon','lichen','lotus','marble','meadow','mesa','moss','newt','onyx','opal','otter','pebble','plume','quartz','quill','raven','reef','river','sage','shoal','silt','slate','spark','spruce','sumac','thistle','tundra','vale','vine','weave','willow','yarrow']

static String tokenMask(String v) {
    return v.length() >= 16 ? BULLETS + v.substring(v.length() - 4) : BULLETS
}

static boolean isSecretName(String key) {
    return key && SECRET_KEY.matcher(key).find() && !META_KEY.matcher(key).find()
}

// Each span is [start, end, replacement]; one that overlaps an earlier span is dropped.
static boolean addSpan(List<List> spans, int start, int end, String shown) {
    if (start >= end || spans.any { List sp -> start < (sp[1] as int) && (sp[0] as int) < end }) return false
    spans << [start, end, shown]
    return true
}

static String applySpans(String s, List<List> spans) {
    if (!spans) return s
    StringBuilder out = new StringBuilder()
    int i = 0
    spans.sort { List sp -> sp[0] as int }.each { List sp ->
        out.append(s, i, sp[0] as int).append(sp[2] as String)
        i = sp[1] as int
    }
    return out.append(s, i, s.length()).toString()
}

static boolean secretValue(String key, String val, boolean quoted) {
    if (!isSecretName(key) || val == null || val.contains(DOT)) return false
    if (val.length() < (PASSWORD_KEY.matcher(key).find() ? 3 : 6)) return false
    String low = val.trim().toLowerCase()
    if (low in ["true", "false", "null", "none"] || low in AUTH_SCHEMES || val.contains('${')) return false
    return quoted || !CODE_REF.matcher(val.replaceAll(/[`'.,:;!?]+$/, "")).find()
}

// Secrets in free text: private key bodies, Authorization credentials, URL credentials and
// webhook keys (maskUrl), JWTs, values of secret-named keys, and bare UUIDs.
static String maskSecrets(String s) {
    if (!s) return s
    List<List> spans = []
    Matcher m = PEM_BEGIN.matcher(s)
    while (m.find()) {
        Matcher end = PEM_END.matcher(s)
        Matcher body = PEM_BODY.matcher(s).region(m.end(), end.find(m.end()) ? end.start() : s.length())
        while (body.find()) addSpan(spans, body.start(), body.end(), BULLETS)
    }
    m = AUTH_SCHEME.matcher(s)
    while (m.find()) {
        boolean token = m.group(1).toLowerCase() in ["bearer", "token"]
        addSpan(spans, m.start(2), m.end(2), token ? tokenMask(m.group(2)) : BULLETS)
    }
    m = AUTH_BARE.matcher(s)
    while (m.find()) addSpan(spans, m.start(1), m.end(1), BULLETS)
    m = BEARER.matcher(s)
    while (m.find()) addSpan(spans, m.start(1), m.end(1), tokenMask(m.group(1)))
    m = URL_IN_TEXT.matcher(s)
    while (m.find()) {
        String url = m.group().replaceAll(/[.,;:!?]+$/, "")
        String shown = maskUrl(url)
        if (shown != url) addSpan(spans, m.start(), m.start() + url.length(), shown)
    }
    m = JWT.matcher(s)
    while (m.find()) addSpan(spans, m.start(), m.end(), tokenMask(m.group()))
    KEY_VALUE.each { List kv ->
        Pattern p = kv[0] as Pattern
        int kg = kv[1] as int, vg = kv[2] as int
        Matcher mm = p.matcher(s)
        int pos = 0
        // A match that isn't a secret is searched again from inside its value: a URL first
        // matches as key "https" and would step over its ?access_token=.
        while (pos < s.length() && mm.find(pos)) {
            String key = mm.group(kg), val = mm.group(vg)
            if (secretValue(key, val, kv[3] as boolean) &&
                    addSpan(spans, mm.start(vg), mm.end(vg), PASSWORD_KEY.matcher(key).find() ? BULLETS : tokenMask(val))) {
                pos = mm.end()
            } else {
                pos = Math.max(mm.start() + 1, mm.start(vg))
            }
        }
    }
    m = UUID_IN_TEXT.matcher(s)
    while (m.find()) addSpan(spans, m.start(), m.end(), tokenMask(m.group()))
    return applySpans(s, spans)
}

// The URL as pages and logs show it: the password in user:pass@ as bullets, the value of a
// query or fragment parameter whose name reads as a credential, and a path segment that looks
// generated (16+ letters, digits, - or _, mixing letters and digits), which is where Slack,
// IFTTT and Home Assistant webhooks keep their key. The rest stays readable.
static String maskUrl(String url) {
    String rest = url ?: ""
    String fragment = null
    int hash = rest.indexOf('#')
    if (hash >= 0) { fragment = rest.substring(hash + 1); rest = rest.substring(0, hash) }
    String query = null
    int q = rest.indexOf('?')
    if (q >= 0) { query = rest.substring(q + 1); rest = rest.substring(0, q) }
    Matcher m = rest =~ /^([A-Za-z][A-Za-z0-9+.-]*:\/\/)(?:([^\/@:]*):[^\/@]*@)?([^\/]*)(.*)$/
    if (!m.find()) return "(URL)"
    String path = m.group(4).split("/", -1).collect { String seg ->
        (seg.length() >= 16 && seg ==~ /[A-Za-z0-9_-]+/ && seg ==~ /.*[A-Za-z].*/ && seg ==~ /.*[0-9].*/) ? tokenMask(seg) : seg
    }.join("/")
    String out = m.group(1) + (m.group(2) != null ? m.group(2) + ":" + BULLETS + "@" : "") + m.group(3) + path
    if (query != null) out += "?" + maskParams(query)
    if (fragment != null) out += "#" + (fragment.contains("=") ? maskParams(fragment) : fragment)
    return out
}

static String maskParams(String params) {
    return params.split("&", -1).collect { String pair ->
        int eq = pair.indexOf('=')
        String name = eq >= 0 ? pair.substring(0, eq) : pair
        String value = eq >= 0 ? pair.substring(eq + 1) : ""
        if (!value || !isSecretName(name) || value.contains(DOT) || value ==~ /(?i)true|false|yes|no|on|off|\d{1,3}/) return pair
        return name + "=" + (PASSWORD_KEY.matcher(name).find() ? BULLETS : tokenMask(value))
    }.join("&")
}

// Identifiers, in the forms the log-scan masking uses: the part that still helps
// troubleshooting stays (an IP's first and last octet, a MAC's vendor prefix).
static String maskIdentifiers(String s) {
    if (!s) return s
    List<List> spans = []
    Closure<Void> scan = { Pattern p, int g, Closure<String> shown ->
        Matcher m = p.matcher(s)
        while (m.find()) {
            int a = m.start(g), b = m.end(g)
            if ((a > 0 && s.charAt(a - 1) == DOT as char) || (b < s.length() && s.charAt(b) == DOT as char)) continue
            String r = shown(m.group(g))
            if (r != null) addSpan(spans, a, b, r)
        }
    }
    scan(EMAIL, 0) { String v -> int at = v.indexOf('@'); v.substring(0, 1) + DOT * 3 + v.substring(at) }
    scan(MAC, 0) { String v -> maskHexId(v) }
    scan(MAC_KEYED, 1) { String v -> maskHexId(v) }
    scan(EUI64, 0) { String v -> maskHexId(v) }
    scan(EUI64_PACKED, 0) { String v -> maskHexId(v) }
    scan(IPV6, 0) { String v -> maskIpv6(v) }
    scan(IPV4_HEX, 1) { String v -> maskIpv4((0..3).collect { Integer.parseInt(v.substring(it * 2, it * 2 + 2), 16) }.join(".")) }
    scan(IPV4, 0) { String v -> maskIpv4(v) }
    scan(PHONE, 0) { String v -> String d = v.replaceAll(/\D/, ""); DOT * (d.length() - 2) + d.substring(d.length() - 2) }
    scan(GEO_KEYED, 1) { String v -> roundCoordinates(v) }
    scan(GEO_PAIR, 0) { String v -> roundCoordinates(v) }
    scan(SERIAL, 1) { String v -> DOT * 4 + v.substring(v.length() - (v.length() >= 12 ? 4 : 2)) }
    scan(SSID, 1) { String v -> BULLETS }
    scan(ZWAVE_HOME_ID, 1) { String v -> BULLETS }
    scan(MDNS_HOST, 0) { String v -> DOT * 4 + ".local" }
    return applySpans(s, spans)
}

// Vendor prefix and last byte: 34:E1:D1:••:••:18.
static String maskHexId(String v) {
    String h = v.replaceAll(/[^0-9A-Fa-f]/, "").toUpperCase()
    List<String> b = (0..<(h.length().intdiv(2))).collect { int i -> h.substring(i * 2, i * 2 + 2) }
    return (b.take(3) + [DOT * 2] * (b.size() - 4) + [b[-1]]).join(":")
}

// Private: first and last octet. Public: first octet. Loopback, link-local, multicast,
// documentation and reserved addresses, and firmware builds (2.5.3.107), stay as they are.
static String maskIpv4(String ip) {
    if (ip ==~ /2\.\d\.\d\.\d+/) return null
    List<Integer> o = ip.tokenize(".").collect { it as int }
    int a = o[0], b = o[1]
    if (a == 0 || a == 127 || a >= 224 || (a == 169 && b == 254) ||
            ip.startsWith("192.0.2.") || ip.startsWith("198.51.100.") || ip.startsWith("203.0.113.")) return null
    boolean priv = a == 10 || (a == 172 && b >= 16 && b <= 31) || (a == 192 && b == 168) || (a == 100 && b >= 64 && b <= 127)
    return priv ? "${a}.${DOT}.${DOT}.${o[3]}" : "${a}.${DOT}.${DOT}.${DOT}"
}

static String maskIpv6(String v) {
    String low = v.toLowerCase()
    if (!(low =~ /[0-9a-f]{3,}/) || low.indexOf("::") != low.lastIndexOf("::")) return null
    List<String> groups = low.split(":", -1) as List
    if (groups.any { it.length() > 4 } || (!low.contains("::") && groups.size() != 8)) return null
    String first = groups.find { it } ?: ""
    if (low.startsWith("fe8") || low.startsWith("fe9") || low.startsWith("fea") || low.startsWith("feb") ||
            low.startsWith("ff") || low.startsWith("2001:db8:") || low in ["::1", "::"]) return null
    if (low.startsWith("fc") || low.startsWith("fd")) return "${first}:${DOT}${DOT}${DOT}:${groups[-1]}"
    return "${first}:${DOT}${DOT}${DOT}"
}

// One decimal, about 10 km.
static String roundCoordinates(String v) {
    return v.replaceAll(/-?\d+\.\d+/) { String n -> String.valueOf(Math.round((n as double) * 10) / 10.0d) }
}

// Replaces each registered name, longest first, where it stands as a whole word.
static String aliasNames(String s, List<String> order, Map<String, String> aliases) {
    if (!s || !order) return s
    List<List> spans = []
    order.each { String n ->
        int i = s.indexOf(n)
        while (i >= 0) {
            int e = i + n.length()
            if ((i == 0 || !Character.isLetterOrDigit(s.charAt(i - 1))) &&
                    (e == s.length() || !Character.isLetterOrDigit(s.charAt(e)))) {
                addSpan(spans, i, e, aliases[n])
            }
            i = s.indexOf(n, i + 1)
        }
    }
    return applySpans(s, spans)
}

// Names that get replaced in text, longest first. A name mapped to itself is never replaced,
// nor is one under 3 characters.
static List<String> orderNames(Map<String, String> aliases) {
    return aliases.findAll { k, v -> k.length() >= 3 && k != v }.keySet().sort { a, b -> b.length() <=> a.length() ?: a <=> b }
}

// A stable adjective-noun alias, mixed with the install's salt so it can't be worked out from a
// guessed name. Same hash and collision walk as Hub Inspector.
static String newAlias(String name, String salt, Set<String> used) {
    String s = salt ? salt + "\u0000" + name : name
    int hv = (int) 2166136261L
    for (char c : s.toCharArray()) {
        hv ^= (int) c
        hv *= 16777619
    }
    long u = hv & 0xffffffffL
    int ai = (int) (u % ADJECTIVES.size()), ni = (int) ((u >>> 8) % NOUNS.size())
    String a = null
    for (int t = 0; t < ADJECTIVES.size() * NOUNS.size(); t++) {
        a = ADJECTIVES[ai] + "-" + NOUNS[ni]
        if (!used.contains(a)) return a
        ni++
        if (ni >= NOUNS.size()) { ni = 0; ai = (ai + 1) % ADJECTIVES.size() }
    }
    return a + "-" + used.size()
}

// End of masking

// ============================================================================
// Name aliases
// ============================================================================

// Generic names that would only make messages harder to read if replaced.
@Field static final List<String> GENERIC_NAMES = ["Home", "Hubitat", "Hub", "Local Hub"]

private Map<String, String> nameAliases() {
    if (!state.aliasSalt) state.aliasSalt = UUID.randomUUID().toString()
    return (state.nameAliases ?: [:]) as Map<String, String>
}

// Adds the names not known yet. A name in skip (a driver or app type name, a mode) maps to
// itself and is never replaced.
private void registerNames(Map<String, String> aliases, Collection names, Collection skip = []) {
    Set<String> used = null
    boolean added = false
    names.each { n ->
        String k = n as String
        if (!k || aliases.containsKey(k) || aliases.size() >= MAX_NAMES) return
        if (k in skip || k in GENERIC_NAMES) {
            aliases[k] = k
        } else {
            if (used == null) used = new HashSet<String>(aliases.values())
            String a = newAlias(k, state.aliasSalt as String, used)
            used << a
            aliases[k] = a
        }
        added = true
    }
    if (added) state.nameAliases = aliases
}

private List<String> nameOrder(Map<String, String> aliases) {
    String key = "${app.id}:${aliases.size()}"
    List<String> order = NAME_ORDER.get(key)
    if (order == null) {
        NAME_ORDER.keySet().removeIf { it.startsWith("${app.id}:") }
        order = orderNames(aliases)
        NAME_ORDER.put(key, order)
    }
    return order
}

// Names come from the log entries themselves, and from the local hub's device and app lists so
// a name is replaced in another app's message before its own device or app has logged.
void seedNames() {
    Map<String, String> aliases = nameAliases()
    registerNames(aliases, location.modes*.name, location.modes*.name)
    registerNames(aliases, [location.name, location.hub.name] + (state.bridges ?: []).collect { it.label })
    asynchttpGet("seedDevicesCallback", [uri: "http://127.0.0.1:8080", path: "/hub2/devicesList", timeout: 30])
    asynchttpGet("seedAppsCallback", [uri: "http://127.0.0.1:8080", path: "/hub2/appsList", timeout: 30])
}

void seedDevicesCallback(resp, data) {
    if (resp.hasError() || resp.getStatus() != 200) {
        logWarn "Device list for name aliases unavailable: HTTP ${resp.getStatus()}"
        return
    }
    List names = [], skip = []
    collectDeviceNames((resp.getJson()?.devices ?: []) as List, names, skip)
    registerNames(nameAliases(), skip + names, skip)
    logDebug "Name aliases: ${names.size()} device and room names read"
}

private void collectDeviceNames(List nodes, List names, List skip) {
    nodes.each { Map node ->
        Map d = (node.data ?: [:]) as Map
        [d.name, d.secondaryName].each { String n -> if (n) (n == d.type ? skip : names) << n }
        if (d.roomName) names << d.roomName
        collectDeviceNames((node.children ?: []) as List, names, skip)
    }
}

void seedAppsCallback(resp, data) {
    if (resp.hasError() || resp.getStatus() != 200) {
        logWarn "App list for name aliases unavailable: HTTP ${resp.getStatus()}"
        return
    }
    Map json = resp.getJson() as Map
    Map<String, String> typeNames = [:]
    ((json.systemAppTypes ?: []) + (json.userAppTypes ?: [])).each { Map t -> typeNames[t.id as String] = t.name as String }
    List names = [], skip = typeNames.values() as List
    collectAppNames((json.apps ?: []) as List, typeNames, names, skip)
    registerNames(nameAliases(), skip + names, skip)
    logDebug "Name aliases: ${names.size()} app names read"
}

// An app label can carry HTML, such as a paused badge; the log shows the text.
private void collectAppNames(List nodes, Map<String, String> typeNames, List names, List skip) {
    nodes.each { Map node ->
        Map d = (node.data ?: [:]) as Map
        String n = (d.name as String ?: "").replaceAll(/<[^>]*>/, "").trim()
        if (n) (n == typeNames[d.appTypeId as String] ? skip : names) << n
        collectAppNames((node.children ?: []) as List, typeNames, names, skip)
    }
}

// The filter's POST URL, decrypted once per stored ciphertext.
private String filterHttpUrl(Map filter) {
    String enc = filter.httpUrlEnc as String
    if (!enc) return null
    String key = "${app.id}:${enc}"
    String hit = URL_CACHE.get(key)
    if (hit != null) return hit
    try {
        String plain = decrypt(enc)
        URL_CACHE.put(key, plain)
        return plain
    } catch (Exception e) {
        logWarn "${filter.label}: the stored POST URL can't be read (${e.message}), as after a hub restore; enter it again"
        return null
    }
}

private void appendToFile(String fileName, String data) {
    String existing = ""
    try {
        // A missing file throws up to 2.5.2 and returns null from 2.5.3.
        byte[] bytes = downloadHubFile(fileName)
        if (bytes != null) existing = new String(bytes)
    } catch (Exception e) {
        // File doesn't exist yet
    }
    uploadHubFile(fileName, (existing + data).bytes)
}

// ============================================================================
// Rate Limit Reset
// ============================================================================

void resetRateLimitCounters() {
    checkVersion()
    (state.filters ?: []).each { Map filter ->
        EVENTS_THIS_MINUTE.get(filterKey(filter))?.set(0)
    }
}

// ============================================================================
// UI Helpers
// ============================================================================

private String bridgeLabelForDni(String dni) {
    if (!dni) return "all"
    Map br = (state.bridges ?: []).find { it.dni == dni }
    return br?.label ?: dni
}

@CompileStatic
private static String buildTypesSummary(Map filter) {
    List<String> types = []
    if (filter.monitorDev as boolean) types.add("dev")
    if (filter.monitorApp as boolean) types.add("app")
    if (filter.monitorSys as boolean) types.add("sys")
    return types.join("+")
}

@CompileStatic
private static String buildOutputsSummary(Map filter) {
    List<String> outputs = []
    if (filter.outputNotify) outputs.add("notify")
    if (filter.outputLog) outputs.add("file: " + (filter.logFile as String))
    if (filter.outputHttp) outputs.add("POST" + (filter.httpUrlShown ? " " + (filter.httpUrlShown as String) : ""))
    String hidden = filter.hidePrivate ? " (private details hidden)" : ""
    return outputs.size() > 0 ? "-> " + outputs.join(", ") + hidden : "-> (no outputs)"
}

// ── Logging (app) ─────────────────────────────────────────────────────
//   ⬇️ Evt  ⬆️ Cmd  🔧 Cfg  🌐 Net  ⏰ Sched  🏷️ Ver  ·  ⚠️ Warn  🛑 Error  🔬 Trace
private String logp(String e) { "${e} ${app.getLabel()}: " }

void logEvt  (String m) { if (enableDebug) log.debug logp('⬇️') + m }
void logCmd  (String m) { if (txtEnable != false) log.info  logp('⬆️') + m }
void logCfg  (String m) { if (txtEnable != false) log.info  logp('🔧') + m }
void logNet  (String m) { if (enableDebug) log.debug logp('🌐') + m }
void logSched(String m) { if (enableDebug) log.debug logp('⏰') + m }
void logVer  (String m) { log.warn  logp('🏷️') + m }

void logWarn (String m) { log.warn  logp('⚠️') + m }
void logError(String m) { log.error logp('🛑') + m }
void logTrace(String m) { if (traceEnable) log.trace logp('🔬') + m }
void logInfo (String m) { if (txtEnable != false) log.info  "${app.getLabel()}: ${m}" }
void logDebug(String m) { if (enableDebug) log.debug "${app.getLabel()}: ${m}" }

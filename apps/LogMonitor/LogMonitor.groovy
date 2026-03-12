/**
 * Log Monitor — Hub Log Monitoring App
 *
 * Creates bridge child devices for WebSocket connectivity to one or more
 * hub logsockets. Supports multiple independent filter configurations,
 * each with its own output actions (notification, log file, HTTP POST).
 *
 *	Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *	in compliance with the License. You may obtain a copy of the License at:
 *
 *		http://www.apache.org/licenses/LICENSE-2.0
 *
 *	Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *	on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *	for the specific language governing permissions and limitations under the License.
 *
 */

import groovy.transform.CompileStatic
import groovy.transform.Field

@Field static final String APP_VERSION = "1.1.0"
@Field static final int MAX_BRIDGES = 5
@Field static final int MAX_FILTERS = 10

definition(
    name: "Log Monitor",
    namespace: "iamtrep",
    author: "pj",
    description: "Monitor hub logs with multiple filters and output actions",
    category: "Utility",
    singleInstance: true,
    importUrl: "https://raw.githubusercontent.com/iamtrep/hubitat/main/apps/LogMonitor/LogMonitor.groovy",
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

    dynamicPage(name: "mainPage", title: "Log Monitor", install: true, uninstall: true) {
        section("Bridges") {
            List<Map> bridges = state.bridges ?: []
            if (bridges.size() == 0) {
                paragraph "<i>No bridges configured. A local bridge will be created on install.</i>"
            } else {
                bridges.eachWithIndex { Map br, int i ->
                    def dev = getChildDevice(br.dni)
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
            input name: "enableDebug", type: "bool",
                title: "Enable debug logging", defaultValue: false
        }
        section {
            paragraph "Version ${APP_VERSION}"
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
                description: "e.g., maison, chalet, local"
            input name: "bHubAddress", type: "text", title: "Hub IP address (leave blank for local hub)",
                required: false, description: "e.g., 192.168.1.213"
        }
        if (!isNew) {
            List<Map> bridges = state.bridges ?: []
            Map br = bridges[idx]
            def dev = getChildDevice(br?.dni)
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
                input name: "fHttpUrl", type: "text",
                    title: "URL", required: true,
                    description: "e.g., https://example.com/webhook"
            }
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
        app.removeSetting("notifyDevices_${idx}")
        filters.remove(idx)
        // Re-key notify settings for filters that shifted down
        for (int i = idx; i < filters.size(); i++) {
            if (filters[i].notifySettingsKey) {
                String oldKey = "notifyDevices_${i + 1}"
                String newKey = "notifyDevices_${i}"
                def devs = settings[oldKey]
                if (devs) {
                    app.updateSetting(newKey, devs)
                }
                app.removeSetting(oldKey)
                filters[i].notifySettingsKey = newKey
            }
        }
        state.filters = filters
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
}

private void initialize() {
    state.bridges = state.bridges ?: []
    state.filters = state.filters ?: []

    // Create a default local bridge if none exist
    if ((state.bridges as List).size() == 0) {
        String dni = "logmon-bridge-0"
        addChildDevice("iamtrep", "Log Monitor Bridge", dni,
            [name: "Log Monitor Bridge", label: "Local Hub", isComponent: false])
        state.bridges = [[label: "Local Hub", dni: dni, hubAddress: ""]]
    }

    unschedule("resetRateLimitCounters")
    runEvery1Minute("resetRateLimitCounters")
}

void uninstalled() {
    logDebug "uninstalled()"
    (state.bridges ?: []).each { Map br ->
        try { deleteChildDevice(br.dni) } catch (Exception e) {}
    }
}

// ============================================================================
// Bridge Persistence
// ============================================================================

private void savePendingBridge() {
    if (!settings.containsKey("bLabel") || !settings.bLabel) {
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
        def dev = getChildDevice(br.dni)
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
        def dev = getChildDevice(dni)
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
    app.updateSetting("fHttpUrl", [type: "text", value: filter.httpUrl ?: ""])
}

private void savePendingFilter() {
    if (!settings.containsKey("fLabel") || !settings.fLabel) {
        clearFilterSettings()
        return
    }

    int idx = state.editingFilterIndex != null ? state.editingFilterIndex as int : -1
    List<Map> filters = state.filters ?: []

    int actualIdx = (idx >= 0) ? idx : filters.size()
    Map filter = buildFilterFromSettings(actualIdx)

    if (idx >= 0 && idx < filters.size()) {
        filter.eventsMatched = filters[idx].eventsMatched ?: 0
        filter.eventsThisMinute = filters[idx].eventsThisMinute ?: 0
        filter.processedEvents = filters[idx].processedEvents ?: []
        filters[idx] = filter
    } else {
        filter.eventsMatched = 0
        filter.eventsThisMinute = 0
        filter.processedEvents = []
        filters.add(filter)
    }

    state.filters = filters
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
        httpUrl:            settings.fHttpUrl ?: ""
    ]
}

private void clearFilterSettings() {
    ["fLabel", "fBridgeDni", "fMonitorDev", "fMonitorApp", "fMonitorSys", "fLevels",
     "fDeviceIds", "fAppIds", "fIncludePattern", "fExcludePattern",
     "fDedupeWindow", "fMaxPerMinute", "fOutputNotify",
     "fOutputLog", "fLogFile", "fOutputHttp", "fHttpUrl"].each {
        app.removeSetting(it)
    }
}

// ============================================================================
// Log Entry Processing (called by bridge driver via parent.processLogEntry)
// ============================================================================

void processLogEntry(String bridgeDni, Map logEntry) {
    // Self-monitoring guard: skip own app logs
    if (logEntry.type == "app" && logEntry.id?.toString() == app.id.toString()) return

    List<Map> filters = state.filters
    if (!filters) return

    int size = filters.size()
    boolean filtersModified = false

    for (int i = 0; i < size; i++) {
        Map filter = filters[i]

        // Check bridge source filter
        String filterBridge = filter.bridgeDni as String
        if (filterBridge && filterBridge != bridgeDni) continue

        if (matchesFilter(logEntry, filter)) {
            filter = executeOutputs(logEntry, filter, bridgeDni)
            filters[i] = filter
            filtersModified = true
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

    List events = (filter.processedEvents as List) ?: []
    events = events.findAll { (ts - (it.timestamp as long)) < windowMs }
    boolean found = events.any { it.signature == signature }

    if (!found) {
        events = events + [[signature: signature, timestamp: ts]]
        if (events.size() > 100) {
            events = events.drop(events.size() - 100)
        }
    }

    filter.processedEvents = events
    return found
}

private boolean isRateLimited(Map filter) {
    int limit = (filter.maxPerMinute as int) ?: 30
    int thisMinute = ((filter.eventsThisMinute as int) ?: 0) + 1
    filter.eventsThisMinute = thisMinute

    if (thisMinute > limit) {
        logDebug "Rate limit exceeded for ${filter.label} (${limit}/min)"
        return true
    }
    return false
}

// ============================================================================
// Output Actions
// ============================================================================

private Map executeOutputs(Map logEntry, Map filter, String bridgeDni) {
    if (isDuplicate(logEntry, filter)) return filter
    if (isRateLimited(filter)) return filter

    filter.eventsMatched = ((filter.eventsMatched as int) ?: 0) + 1

    String bridgeLabel = bridgeLabelForDni(bridgeDni)
    String formatted = "[${bridgeLabel}/${logEntry.type}/${logEntry.level}] ${logEntry.name}: ${logEntry.msg}"
    logInfo "${filter.label}: ${formatted}"

    // Notification
    if (filter.outputNotify && filter.notifySettingsKey) {
        try {
            List devices = settings[filter.notifySettingsKey] ?: []
            devices.each { dev ->
                dev.deviceNotification("${filter.label}: ${formatted}")
            }
        } catch (Exception e) {
            logDebug "Notification error for ${filter.label}: ${e.message}"
        }
    }

    // Log file
    if (filter.outputLog && filter.logFile) {
        try {
            String timestamp = new Date().format("yyyy-MM-dd HH:mm:ss")
            String escapedName = (logEntry.name as String ?: "").replace('"', '""')
            String escapedMsg = (logEntry.msg as String ?: "").replace('"', '""')
            String line = "${timestamp},${bridgeLabel},${logEntry.type},${logEntry.level},\"${escapedName}\",\"${escapedMsg}\"\n"
            appendToFile(filter.logFile as String, line)
        } catch (Exception e) {
            logDebug "Log file error for ${filter.label}: ${e.message}"
        }
    }

    // HTTP POST
    if (filter.outputHttp && filter.httpUrl) {
        try {
            Map postParams = [
                uri: filter.httpUrl,
                contentType: "application/json",
                body: [
                    filter: filter.label,
                    bridge: bridgeLabel,
                    type:   logEntry.type,
                    level:  logEntry.level,
                    name:   logEntry.name,
                    msg:    logEntry.msg,
                    id:     logEntry.id,
                    time:   logEntry.time
                ],
                timeout: 5
            ]
            asynchttpPost("httpPostCallback", postParams)
        } catch (Exception e) {
            logDebug "HTTP POST error for ${filter.label}: ${e.message}"
        }
    }

    return filter
}

void httpPostCallback(resp, data) {
    if (resp.hasError()) {
        logDebug "HTTP POST failed: ${resp.getErrorMessage()}"
    }
}

private void appendToFile(String fileName, String data) {
    String existing = ""
    try {
        byte[] bytes = downloadHubFile(fileName)
        existing = new String(bytes)
    } catch (Exception e) {
        // File doesn't exist yet
    }
    uploadHubFile(fileName, (existing + data).bytes)
}

// ============================================================================
// Rate Limit Reset
// ============================================================================

void resetRateLimitCounters() {
    List<Map> filters = state.filters
    if (!filters) return

    boolean modified = false
    filters.eachWithIndex { Map filter, int i ->
        if ((filter.eventsThisMinute as int) > 0) {
            filter.eventsThisMinute = 0
            modified = true
        }
    }
    if (modified) {
        state.filters = filters
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
    if (filter.outputHttp) outputs.add("POST")
    return outputs.size() > 0 ? "-> " + outputs.join(", ") : "-> (no outputs)"
}

// ============================================================================
// Logging
// ============================================================================

private void logDebug(String msg) {
    if (enableDebug) log.debug "LogMonitor: ${msg}"
}

private void logInfo(String msg) {
    log.info "LogMonitor: ${msg}"
}

private void logWarn(String msg) {
    log.warn "LogMonitor: ${msg}"
}

private void logError(String msg) {
    log.error "LogMonitor: ${msg}"
}

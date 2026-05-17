// Copyright (c) 2025-2026 PJ
// SPDX-License-Identifier: MIT

/*
 *  Rule Machine Logging Checker
 *
 *  Scans Rule Machine and Button Controller child apps and reports which rules appear to have
 *  Actions, Events, and/or Triggers logging enabled.
 *
 *  Notes:
 *  - Uses Hubitat local/internal JSON endpoints:
 *      /hub2/appsList
 *      /installedapp/statusJson/{appId}
 *      /installedapp/configure/json/{appId}   (logging toggle feature)
 *      /installedapp/update/json              (logging toggle feature)
 *  - These endpoints are not a formal public API and could change in a
 *    future Hubitat platform release.
 *
 *  Changelog:
 *  1.5.6 - Fix "Last Run" column sort: strip hyphens before parseFloat so dates within the same
 *          year sort correctly (was truncating to year-only, making within-year order random)
 *  1.5.5 - Codex review fixes: unschedule before runIn for both timeout handlers; @Field statics
 *          reset in initialize(); tighten RM type detection to "rule machine" only; drop
 *          state.reportHtml (render table on demand from state.allScanRows); depth-cap telemetry;
 *          remove stale atomicState cleanup from initialize()
 *  1.5.4 - Move transient scan/turn-off state to @Field; eliminate all atomicState usage;
 *          ~900 state writes removed per scan
 *  1.5.3 - Async turnOffAllLogging; refactored buildReportHtml; removed redundant server-side
 *          filters; fixed pageBreadcrumbs double-encoding; hardened capability input handling;
 *          click-to-toggle for Disabled and Paused cells; miscellaneous clarity fixes;
 *          fixed rmToggleLogging data-sort update; removed dead rescanSingleRule /
 *          getInstalledAppStatusJson; buildJavaScript converted to readable heredoc; minor cleanup;
 *          fixed Button Controller rule discovery (grandchild depth)
 *  1.5.2 - Clickable A/E/T cells with in-place AJAX toggle; "Modify Rule Logging" section removed (hubitrep)
 *  1.5.1 - Paused column, Last Run column, column/row JS toggle buttons, renderNameHtml (merged from v1.17)
 *  1.5 - Async HTTP fan-out for faster scanning; logging toggle feature (hubitrep)
 *  1.4 - Original release (johnland / ChatGPT)
 */

import groovy.transform.CompileStatic
import groovy.transform.Field

@Field static final String RM_BASE_URL           = "http://127.0.0.1:8080"

@Field static final String PATH_APPS_LIST        = "/hub2/appsList"
@Field static final String PATH_STATUS_JSON      = "/installedapp/statusJson/"
@Field static final String PATH_CONFIGURE_JSON   = "/installedapp/configure/json/"
@Field static final String PATH_UPDATE_JSON      = "/installedapp/update/json"
@Field static final String PATH_INSTALLED_LIST   = "/installedapp/list"
@Field static final String PATH_CONFIGURE        = "/installedapp/configure/"

@Field static final int HTTP_TIMEOUT_SECS        = 30
@Field static final int SCAN_TIMEOUT_SECS        = 300
@Field static final int TURN_OFF_TIMEOUT_SECS    = 180

@Field static final String DEFAULT_PAGE_NAME     = "mainPage"
@Field static final String TABLE_ID              = "rmlog_table"

// Transient scan state — static so it survives across per-call script instances.
// Hubitat does not reload the class on code push, so static fields persist until hub reboot.
// singleInstance:true means there is only ever one logical app, so no cross-tenant leakage.
@Field static String       currentScanId      = null
@Field static Long         scanStartMs        = 0L
@Field static List<Map>    scanRuleQueue      = null
@Field static Map          scanPartialResults = null

@Field static boolean      turnOffActive  = false
@Field static List<Map>    turnOffQueue   = null
@Field static List<String> turnOffErrors  = null

definition(
    name: "Rule Logging Manager",
    namespace: "johnland",
    author: "John Land & ChatGPT",
    description: "Reports Rule Machine and Button Controller rules that have Actions, Events, or Triggers logging selected.",
    menu: "Apps", // new in platform 2.5.0
    category: "Utility",
    singleInstance: true,
    installOnOpen: true,
    iconUrl: '',
    iconX2Url: ''
)

preferences {
    page(name: "mainPage")
}

void installed() {
    initialize()
    runIn(10, "findLoggingRules")
}

void updated() {
    initialize()
}

void initialize() {
    if (currentScanId != null) {
        log.warn "initialize: aborting in-progress scan (scanId: ${currentScanId}) — previous scan results remain visible; re-scan when ready"
    }
    currentScanId      = null
    scanStartMs        = 0L
    scanRuleQueue      = null
    scanPartialResults = null
    turnOffActive      = false
    turnOffQueue       = null
    turnOffErrors      = null

    if (debugEnable) {
        runIn(1800, logsOff)
    }
}

void logsOff() {
    app.updateSetting("debugEnable", [value: "false", type: "bool"])
}

def mainPage() {
    int pollInterval = (currentScanId || turnOffActive) ? 5 : 0
    dynamicPage(name: "mainPage", title: "", install: true, uninstall: true, refreshInterval: pollInterval) {

        section("Scan") {
            input "btnScan", "button", title: "Scan RM / BC Rules"
            input "btnTurnOffAll", "button", title: "Turn OFF All Logging (All Rules)"
            input "debugEnable", "bool",
                title: "Enable debug logging",
                defaultValue: false,
                submitOnChange: true
        }

        section("Results") {
            if (state.lastScan) {
                String summaryHtml = "<div style='margin:0;padding:0;line-height:1.15;'>" +
                    "<b>Last scan:</b> ${state.lastScan} (Scan duration: ${state.scanDuration ?: '00:00'})<br>" +
                    "<b>Rules scanned:</b> ${state.scannedCount ?: 0}; " +
                    "<b>Any logging ON:</b> ${state.anyLoggingOnCount ?: 0}; " +
                    "<b>Actions:</b> ${state.actionsOnCount ?: 0}; " +
                    "<b>Events:</b> ${state.eventsOnCount ?: 0}; " +
                    "<b>Triggers:</b> ${state.triggersOnCount ?: 0}" +
                    "</div>"
                paragraph summaryHtml
            } else {
                paragraph "No scan has been run yet."
            }

            if (state.lastError) {
                paragraph "<span style='color:red'><b>Last error:</b> ${htmlEncode(state.lastError.toString())}</span>"
            }

            if (currentScanId != null) {
                paragraph "<p><i>Scan in progress…</i></p>"
            } else if (turnOffActive) {
                paragraph "<p><i>Turning off logging…</i></p>"
            } else if (state.allScanRows != null) {
                paragraph buildReportHtml((state.allScanRows ?: []) as List<Map>)
            } else {
                paragraph "Click <b>Scan RM / BC Rules</b> to begin."
            }
        }

        section("Notes", hideable: true, hidden: true) {
            paragraph """
                This app scans Rule Machine (<b>RM</b>) and Button Controller (<b>BC</b>) child apps and
                checks their status JSON for logging settings that appear to correspond to
                <b>Actions</b>, <b>Events</b>, and <b>Triggers</b> logging. A table cell marked
                <b>ON</b> means that the corresponding logging option appears to be enabled for that rule.
                <br><br>
                Basic Button Controller is intentionally not included because it appears to expose only
                a single broad logging toggle rather than separate Actions, Events, and Triggers controls.
                <br><br>
                Click any table header to sort the displayed results by that column; clicking the same header again
                reverses the sort direction. The default display sort is by <b>Rule</b>.
                <br><br>
                Click any <b>Actions</b>, <b>Events</b>, or <b>Triggers</b> cell in the table to toggle
                that rule's logging setting in-place. The cell will update immediately if successful.
                <br><br>
                Use <b>Turn OFF All Logging (All Rules)</b> to disable all logging across all rules in one shot,
                then click Scan again to verify.
                <br><br>
                This app uses Hubitat local/internal JSON endpoints. Those endpoints and Rule Machine /
                Button Controller internal setting names are not a formal public API, so the detection
                logic may need to be adjusted if Hubitat changes the JSON format in a future platform update.
            """
        }
    }
}

void appButtonHandler(String btn) {
    switch (btn) {
        case "btnScan":
            findLoggingRules()
            break
        case "btnTurnOffAll":
            turnOffAllLogging()
            break
        default:
            log.warn "Unknown button: ${btn}"
            break
    }
}

// ============================================================
// Scanning — async sequential chain
// ============================================================

void findLoggingRules() {
    if (turnOffActive) {
        log.warn "findLoggingRules: turn-off pass in progress, ignoring"
        return
    }
    state.lastError = null

    List<Map> ruleApps = getRuleMachineRuleApps()

    if (ruleApps.isEmpty()) {
        state.scannedCount = 0
        state.actionsOnCount = 0
        state.eventsOnCount = 0
        state.triggersOnCount = 0
        state.anyLoggingOnCount = 0
        state.lastScan = new Date().format("yyyy-MM-dd HH:mm:ss", location.timeZone)
        state.scanDuration = "00:00"
        state.allScanRows = []
        return
    }

    scanStartMs = now()
    String scanId = scanStartMs.toString()

    // Scan state stored in @Field — no state serialization on each callback.
    // Safe because Hubitat no longer reloads the class on code push and callbacks
    // dispatch to the same instance. Resets on hub reboot (scan timeout cleans up gracefully).
    List<Map> queue = ruleApps.collect { Map r ->
        [id: r.id as String, name: r.name as String,
         appType: (r.appType ?: "RM") as String, disabled: r.disabled as Boolean,
         paused: r.paused as Boolean]
    }

    scanRuleQueue      = queue
    scanPartialResults = [:]
    currentScanId      = scanId

    // Cancel any prior timeout before scheduling a new one so a stale timer can never
    // fire against a different in-flight scan.
    unschedule("finalizeScanTimeout")
    runIn(SCAN_TIMEOUT_SECS, "finalizeScanTimeout")

    log.info "Scan started — ${queue.size()} rules (scanId: ${scanId})"

    // Sequential async chain: one request at a time to avoid overloading the hub.
    // Each callback fires the next rule or finalizes the scan.
    Map first = queue[0]
    asynchttpGet("handleStatusResponse",
        [uri: RM_BASE_URL, path: "${PATH_STATUS_JSON}${first.id}", timeout: HTTP_TIMEOUT_SECS],
        [scanId: scanId, ruleId: first.id, ruleName: first.name,
         appType: first.appType, disabled: first.disabled, paused: first.paused, nextIdx: 1, totalRules: queue.size()]
    )
}

void handleStatusResponse(resp, data) {
    String scanId = data.scanId as String
    if (currentScanId != scanId) return

    String ruleId = data.ruleId as String

    try {
        Map status = [:]
        try {
            int httpStatus = resp.getStatus() as int
            if (httpStatus == 200) {
                // resp.data may be an already-parsed Map or a raw JSON String depending on hub version
                Object raw = resp.getData()
                if (raw instanceof Map) {
                    status = raw as Map
                } else if (raw != null) {
                    status = new groovy.json.JsonSlurper().parseText(raw.toString()) as Map ?: [:]
                }
            } else {
                log.warn "HTTP ${httpStatus} for rule ${ruleId}"
            }
        } catch (Exception e) {
            log.warn "Error parsing statusJson for rule ${ruleId}: ${e.message}"
        }

        Map logging = detectRuleLogging(status)
        Boolean actionsOn  = logging.actionsOn  as Boolean
        Boolean eventsOn   = logging.eventsOn   as Boolean
        Boolean triggersOn = logging.triggersOn as Boolean
        Boolean anyOn = actionsOn || eventsOn || triggersOn

        if (anyOn) {
            log.info "Logging ON: ${data.ruleName} (${ruleId}, ${data.appType}) Actions=${actionsOn}, Events=${eventsOn}, Triggers=${triggersOn}"
        } else if (debugEnable) {
            log.debug "Logging off: ${data.ruleName} (${ruleId})"
        }

        if (scanPartialResults == null) scanPartialResults = [:]
        scanPartialResults[ruleId] = [
            id             : ruleId,
            name           : data.ruleName,
            appType        : data.appType,
            disabled       : data.disabled,
            paused         : data.paused,
            actionsOn      : actionsOn,
            eventsOn       : eventsOn,
            triggersOn     : triggersOn,
            anyOn          : anyOn,
            actionsField   : logging.actionsField,
            eventsField    : logging.eventsField,
            triggersField  : logging.triggersField,
            allLoggingField: logging.allLoggingField,
            lastRun        : extractLastRun(status)
        ]

    } catch (Exception e) {
        log.warn "handleStatusResponse error for rule ${ruleId}: ${e.message}"
    } finally {
        // Sequential chain: fire the next rule, or finalize if this was the last one
        if (currentScanId != (data.scanId as String)) return  // stale callback from a cancelled scan

        int nextIdx    = ((data.nextIdx    ?: 0) as int)
        int totalRules = ((data.totalRules ?: 0) as int)

        if (debugEnable) log.debug "Rule ${nextIdx}/${totalRules}: ${data.ruleName} (${ruleId})"

        if (nextIdx < totalRules) {
            Map nextRule = scanRuleQueue[nextIdx]
            asynchttpGet("handleStatusResponse",
                [uri: RM_BASE_URL, path: "${PATH_STATUS_JSON}${nextRule.id}", timeout: HTTP_TIMEOUT_SECS],
                [scanId: currentScanId, ruleId: nextRule.id as String, ruleName: nextRule.name as String,
                 appType: (nextRule.appType ?: "RM") as String, disabled: nextRule.disabled as Boolean,
                 paused: nextRule.paused as Boolean, nextIdx: nextIdx + 1, totalRules: totalRules]
            )
        } else {
            finalizeScan()
        }
    }
}

void finalizeScan() {
    unschedule("finalizeScanTimeout")
    List<Map> asyncRules     = scanRuleQueue     ?: []
    Map       partialResults = scanPartialResults ?: [:]
    List<Map> allRows = []

    asyncRules.each { Map rule ->
        Map row = partialResults[rule.id as String] as Map
        if (row) {
            allRows << row
        } else {
            // No response received for this rule (timeout or unrecoverable error)
            allRows << [
                id: rule.id as String, name: rule.name as String,
                appType: (rule.appType ?: "RM") as String, disabled: rule.disabled as Boolean,
                paused: rule.paused as Boolean,
                actionsOn: false, eventsOn: false, triggersOn: false, anyOn: false,
                actionsField: null, eventsField: null, triggersField: null, allLoggingField: null, lastRun: ""
            ]
        }
    }

    Integer actionsOnCount    = allRows.count { it.actionsOn  } as Integer
    Integer eventsOnCount     = allRows.count { it.eventsOn   } as Integer
    Integer triggersOnCount   = allRows.count { it.triggersOn } as Integer
    Integer anyLoggingOnCount = allRows.count { it.anyOn      } as Integer

    state.allScanRows       = allRows
    state.scannedCount      = allRows.size()
    state.actionsOnCount    = actionsOnCount
    state.eventsOnCount     = eventsOnCount
    state.triggersOnCount   = triggersOnCount
    state.anyLoggingOnCount = anyLoggingOnCount
    state.lastScan          = new Date().format("yyyy-MM-dd HH:mm:ss", location.timeZone)
    state.scanDuration      = formatScanDuration((now() as Long) - (scanStartMs ?: (now() as Long)))
    currentScanId      = null   // mark complete so late callbacks are discarded
    scanPartialResults = null   // release memory
    scanRuleQueue      = null   // release memory

    log.info "Scan complete: ${allRows.size()} rules scanned in ${state.scanDuration} — any logging ON: ${anyLoggingOnCount} (Actions: ${actionsOnCount}, Events: ${eventsOnCount}, Triggers: ${triggersOnCount})"
}

void finalizeScanTimeout() {
    if (currentScanId != null) {
        log.warn "Scan timeout: finalizing with partial results"
        finalizeScan()
    }
}

// ============================================================
// Rule discovery
// ============================================================

List<Map> getRuleMachineRuleApps() {
    List<Map> rules = []
    Set<String> seenIds = [] as Set

    try {
        httpGet([uri: RM_BASE_URL, path: PATH_APPS_LIST, contentType: "application/json"]) { resp ->
            resp.data?.apps?.each { parentApp ->
                def pd = parentApp?.data
                String parentType  = pd?.type?.toString()  ?: ""
                String parentName  = pd?.name?.toString()  ?: ""
                String parentLabel = pd?.label?.toString() ?: ""
                String appType = getSupportedAutomationAppType(parentType, parentName, parentLabel)

                if (appType) {
                    parentApp?.children?.each { child ->
                        // Button Controller adds an extra level: direct children are device-specific
                        // groups; the actual button rules are their grandchildren. If a child has
                        // its own children, process those instead.
                        List grandchildren = (child?.children ?: []) as List
                        (grandchildren.isEmpty() ? [child] : grandchildren).each { node ->
                            def d = node?.data
                            if (d?.id && d?.name) {
                                String id = d.id.toString()
                                if (!seenIds.contains(id)) {
                                    String childType         = d?.type?.toString()    ?: ""
                                    String childAppName      = d?.appName?.toString() ?: ""
                                    String childDetectedType = getSupportedAutomationAppType(childType, childAppName)
                                    String finalAppType      = (appType == "BC" || childDetectedType == "BC") ? "BC" : (childDetectedType ?: appType)

                                    seenIds << id
                                    String ruleName = d.name.toString()
                                    rules << [
                                        id      : id,
                                        name    : ruleName,
                                        appType : finalAppType,
                                        disabled: asBooleanLoose(d.disabled),
                                        paused  : ruleName.contains("(Paused)")  // Hubitat appends "(Paused)" to the label; no structured field in appsList
                                    ]
                                }
                            }
                        }
                    }
                }
            }
        }
    } catch (Exception e) {
        state.lastError = "Unable to read ${PATH_APPS_LIST}. This may be temporary; try Scan again. Error: ${e.message}"
        log.warn state.lastError
    }

    return rules.sort { it.name?.toLowerCase() ?: "" }
}

@CompileStatic
String getSupportedAutomationAppType(String type, String name, String label = "") {
    String combined = [type, name, label].findAll { it }.join(" ").toLowerCase()
    if (!combined) return null
    if (combined.contains("basic button controller") || combined.contains("basicbuttoncontroller")) return null
    if (combined.contains("button controller") || combined.contains("buttoncontroller")) return "BC"
    if (combined.contains("rule machine")) return "RM"
    return null
}


// ============================================================
// Logging detection
// ============================================================

Map detectRuleLogging(Map status) {
    List<Map> candidates = []
    collectCandidatesFromObject("appSettings", status?.appSettings, candidates)
    collectCandidatesFromObject("settings",    status?.settings,    candidates)
    collectCandidatesFromObject("state",       status?.state,       candidates)

    Map allResult      = detectAllLogging(candidates)
    Boolean allLoggingOn = allResult.detected as Boolean

    Map actionsResult  = detectSpecificLogging(candidates, "actions",  ["action",  "actions"])
    Map eventsResult   = detectSpecificLogging(candidates, "events",   ["event",   "events"])
    Map triggersResult = detectSpecificLogging(candidates, "triggers", ["trigger", "triggers"])

    Map result = [
        actionsOn      : allLoggingOn || (actionsResult.matched  as Boolean),
        eventsOn       : allLoggingOn || (eventsResult.matched   as Boolean),
        triggersOn     : allLoggingOn || (triggersResult.matched as Boolean),
        actionsField   : actionsResult.fieldName  as String,
        eventsField    : eventsResult.fieldName   as String,
        triggersField  : triggersResult.fieldName as String,
        allLoggingField: allResult.fieldName      as String
    ]

    // When all field names are null, log candidates so we can diagnose unrecognised field names
    if (!result.actionsField && !result.eventsField && !result.triggersField && !result.allLoggingField) {
        if (debugEnable) {
            List<String> logCandidates = candidates
                .findAll { String k = it.name?.toString()?.toLowerCase() ?: ""; k.contains("log") || k.contains("debug") }
                .collect { "${it.source}/${it.name}=${it.value}" }
            if (logCandidates) {
                log.debug "detectRuleLogging: no logging fields found — log-related candidates: ${logCandidates}"
            } else {
                log.debug "detectRuleLogging: no logging fields found — all candidates: ${candidates.collect { "${it.source}/${it.name}=${it.value}" }}"
            }
        }
    }

    return result
}

@CompileStatic
Map detectAllLogging(List<Map> candidates) {
    Set<String> exactMatches = ["alllogging", "logall", "logsall"] as Set
    String disabledFieldName = null
    for (Map c : candidates) {
        String key = c.name?.toString() ?: ""
        String k = key.toLowerCase()
        if (k in exactMatches || (k.contains("log") && k.contains("all"))) {
            String fieldName = key.contains(".") ? key.tokenize(".").last() : key
            if (valueLooksEnabled(c.value)) return [detected: true, fieldName: fieldName]
            if (!disabledFieldName) disabledFieldName = fieldName  // field exists but is off
        }
    }
    return [detected: false, fieldName: disabledFieldName]
}

Map detectSpecificLogging(List<Map> candidates, String canonicalName, List<String> needles) {
    Set<String> exactKeys    = ["log${canonicalName}", "${canonicalName}log", "${canonicalName}logging", "logging${canonicalName}"] as Set
    Set<String> generalKeys  = ["logging", "logs", "log", "logoptions", "loggingoptions", "logsettings", "logsetting"] as Set
    String disabledFieldName = null

    for (Map c : candidates) {
        String key = c.name?.toString() ?: ""
        String k   = key.toLowerCase()
        String v   = c.value?.toString()?.toLowerCase() ?: ""

        Boolean keyNamesThisLogging = (k in exactKeys) || needles.any { String n ->
            (k.contains("log") && k.contains(n)) || (k.contains(n) && k.contains("logging"))
        }

        if (keyNamesThisLogging) {
            String fieldName = key.contains(".") ? key.tokenize(".").last() : key
            if (valueLooksEnabled(c.value)) return [matched: true, fieldName: fieldName]
            if (!disabledFieldName) disabledFieldName = fieldName  // field exists but is off — remember it
        }

        if (k in generalKeys) {
            String fieldName = key.contains(".") ? key.tokenize(".").last() : key
            // Always track the fieldName — even if this type isn't currently in the selection,
            // we need it so toggle can add the option later
            if (!disabledFieldName) disabledFieldName = fieldName
            if (needles.any { String n -> v.contains(n) } && !valueLooksDisabled(c.value)) {
                return [matched: true, fieldName: fieldName]
            }
        }
    }

    return [matched: false, fieldName: disabledFieldName]
}

void collectCandidatesFromObject(String source, Object obj, List<Map> candidates) {
    collectCandidatesFromObject(source, obj, candidates, "", 0)
}

void collectCandidatesFromObject(String source, Object obj, List<Map> candidates, String prefix, int depth) {
    // Depth cap: RM/BC statusJson nesting is typically ≤ 3 levels; 4 is a safe ceiling.
    // If logging fields are ever missed after a Hubitat update, check this log message first.
    if (obj == null) return
    if (depth > 4) {
        if (debugEnable) log.debug "collectCandidatesFromObject: depth cap reached at '${prefix}' — fields nested deeper than 4 levels will not be scanned"
        return
    }

    if (obj instanceof Map) {
        obj.each { k, v ->
            String name = prefix ? "${prefix}.${k?.toString()}" : k?.toString()
            candidates << [source: source, name: name, value: v]
            if (v instanceof Map || v instanceof Collection) {
                collectCandidatesFromObject(source, v, candidates, name, depth + 1)
            }
        }
        return
    }

    if (obj instanceof Collection) {
        Integer idx = 0
        obj.each { row ->
            String name = prefix ? "${prefix}[${idx}]" : "[${idx}]"
            if (row instanceof Map) {
                Object rowName = row.name ?: row.key ?: row.id ?: row.label ?: name
                candidates << [source: source, name: rowName?.toString(), value: row.value]
                collectCandidatesFromObject(source, row, candidates, name, depth + 1)
            } else {
                candidates << [source: source, name: name, value: row]
            }
            idx++
        }
    }
}

@CompileStatic
Boolean valueLooksEnabled(Object value) {
    if (value == null) return false
    if (value instanceof Boolean) return value
    if (value instanceof Collection) return !(value as Collection).isEmpty()
    String v = value.toString().trim().toLowerCase()
    return v in ["true", "on", "yes", "enabled", "enable", "1"]
}

@CompileStatic
Boolean valueLooksDisabled(Object value) {
    if (value == null) return true
    if (value instanceof Boolean) return !value
    if (value instanceof Collection) return (value as Collection).isEmpty()
    String v = value.toString().trim().toLowerCase()
    return v in ["false", "off", "no", "disabled", "disable", "0", "null", ""]
}

@CompileStatic
Boolean asBooleanLoose(Object value) {
    if (value == null) return false
    if (value instanceof Boolean) return value
    return value.toString().equalsIgnoreCase("true")
}

// ============================================================
// Logging toggle feature
// ============================================================

String buildPostBody(String ruleId, Map configData, String fieldName, Boolean newValue, String enumOption = null) {
    Map   appInfo    = (configData.app        ?: [:]) as Map
    Map   configPage = (configData.configPage ?: [:]) as Map
    Map   settings   = (configData.settings   ?: [:]) as Map
    String pageName   = (configPage.name    ?: DEFAULT_PAGE_NAME) as String
    String appVersion = (appInfo.version    ?: "1") as String
    String appLabel   = (appInfo.label      ?: "") as String

    List sections = (configPage.sections ?: []) as List

    List<List<String>> fields = []

    fields << ["_action_update", "Done"]
    fields << ["formAction", "update"]
    fields << ["id", ruleId]
    fields << ["version", appVersion]
    fields << ["appTypeId", ""]
    fields << ["appTypeName", ""]
    fields << ["currentPage", pageName]
    fields << ["pageBreadcrumbs", "[]"]   // raw value; URLEncoder in the body loop encodes this to %5B%5D

    // Label inputs from body elements
    sections.each { sec ->
        ((sec as Map).body ?: []).each { elem ->
            Map e = elem as Map
            if (e.element == "label") {
                String labelName = (e.name ?: "label") as String
                fields << ["${labelName}.type", "text"]
                fields << [labelName, appLabel]
            }
        }
    }

    // Regular inputs — echo all settings, flipping only the target field
    sections.each { sec ->
        ((sec as Map).input ?: []).each { inp ->
            Map input    = inp as Map
            String name  = input.name as String
            String type  = (input.type ?: "") as String
            boolean multiple = input.multiple as boolean

            fields << ["${name}.type", type]
            fields << ["${name}.multiple", multiple.toString()]

            if (type == "bool") {
                String val
                if (name == fieldName) {
                    val = newValue.toString()
                } else {
                    Object cur = settings[name]
                    // Null means unchecked (false) — do not use settingToString which returns "[]" for null
                    val = (cur == null) ? "false" : cur.toString()
                }
                // Only emit checkbox=on when true — HTML checkbox semantics: absent = false
                if (val == "true") fields << ["checkbox[${name}]", "on"]
                fields << ["settings[${name}]", val]
            } else if (type.startsWith("capability.")) {
                // Hubitat may return device settings as Map<id,info>, Collection, or String — handle all shapes
                Object devSetting = settings[name]
                String ids
                if (devSetting instanceof Map) {
                    ids = (devSetting as Map).keySet().collect { it.toString() }.join(",")
                } else if (devSetting instanceof Collection) {
                    ids = (devSetting as Collection).collect { it.toString() }.join(",")
                } else if (devSetting != null) {
                    log.warn "buildPostBody: unexpected device setting shape for '${name}': ${getObjectClassName(devSetting)} — passing as-is"
                    ids = devSetting.toString()
                } else {
                    ids = ""
                }
                fields << ["settings[${name}]", ids]
                // Hubitat's form handler uses a repeated "deviceList" key to track which inputs
                // are device pickers; the trailing empty pair is a sentinel that terminates the
                // device list in the multipart form body. Both are required by the update endpoint.
                fields << ["deviceList", name]
                fields << ["", ""]
            } else if (name == fieldName && type == "enum" && multiple) {
                // enum-multiple target field: compute the new selection list
                // Format must be a JSON array string: ["Events","Actions"] — not comma-separated
                List<String> opts = ((input.options ?: []) as List).collect { it.toString() }
                if (enumOption) {
                    // Per-type toggle: add or remove just this option from the current list
                    Object curVal = settings[name]
                    List<String> currentList = []
                    if (curVal instanceof Collection) {
                        currentList = (curVal as Collection).collect { it.toString() }
                    } else if (curVal instanceof String && (curVal as String) && curVal != "[]") {
                        String curStr = curVal as String
                        try {
                            // May already be a JSON array string like ["Events","Actions"]
                            def parsed = new groovy.json.JsonSlurper().parseText(curStr)
                            currentList = (parsed instanceof Collection) ? (parsed as Collection).collect { it.toString() } : [curStr]
                        } catch (Exception ignored) {
                            currentList = [curStr]  // bare single value
                        }
                    }
                    List<String> newList = new ArrayList<String>(currentList)
                    if (newValue) { if (!newList.contains(enumOption)) newList << enumOption }
                    else { newList.remove(enumOption) }
                    fields << ["settings[${name}]", groovy.json.JsonOutput.toJson(newList)]
                } else {
                    // All-or-nothing toggle (allField fallback): enable all options or clear
                    fields << ["settings[${name}]", newValue ? groovy.json.JsonOutput.toJson(opts) : "[]"]
                }
            } else {
                fields << ["settings[${name}]", settingToString(settings[name])]
            }
        }
    }

    fields << ["referrer", "${RM_BASE_URL}${PATH_INSTALLED_LIST}"]
    fields << ["url", "${RM_BASE_URL}${PATH_CONFIGURE}${ruleId}/${pageName}"]
    fields << ["_cancellable", "false"]

    return fields.collect { List<String> pair ->
        URLEncoder.encode(pair[0], "UTF-8") + "=" + URLEncoder.encode(pair[1], "UTF-8")
    }.join("&")
}

@CompileStatic
String settingToString(Object value) {
    if (value == null) return ""
    if (value instanceof Map) return (value as Map).keySet().collect { it.toString() }.join(",")
    return value.toString()
}

// ============================================================
// Turn off all logging — async sequential chain
// ============================================================

void turnOffAllLogging() {
    if (turnOffActive || currentScanId) {
        log.warn "turnOffAllLogging: ${currentScanId ? 'scan' : 'turn-off'} already in progress, ignoring"
        return
    }
    state.lastError = null
    List<Map> allRows = (state.allScanRows ?: []) as List<Map>

    // Build a queue of (ruleId, fieldName, newValue=false, enumOption) items to process
    List<Map> queue = []
    allRows.each { Map row ->
        boolean anyOn = (row.actionsOn || row.eventsOn || row.triggersOn) as boolean
        if (!anyOn) return

        // If a catch-all field covers all logging, turn it off with one call
        if (row.allLoggingField && !row.actionsField && !row.eventsField && !row.triggersField) {
            queue << [ruleId: row.id as String, fieldName: row.allLoggingField as String, newValue: false, enumOption: null as String]
        } else {
            if ((row.actionsOn as Boolean) && row.actionsField)
                queue << [ruleId: row.id as String, fieldName: row.actionsField as String, newValue: false, enumOption: null as String]
            if ((row.eventsOn as Boolean) && row.eventsField)
                queue << [ruleId: row.id as String, fieldName: row.eventsField as String, newValue: false, enumOption: null as String]
            if ((row.triggersOn as Boolean) && row.triggersField)
                queue << [ruleId: row.id as String, fieldName: row.triggersField as String, newValue: false, enumOption: null as String]
        }
    }

    if (queue.isEmpty()) {
        log.info "turnOffAllLogging: no logging ON rules to process"
        findLoggingRules()
        return
    }

    turnOffQueue  = queue
    turnOffErrors = []
    turnOffActive = true
    unschedule("finalizeTurnOffTimeout")
    runIn(TURN_OFF_TIMEOUT_SECS, "finalizeTurnOffTimeout")
    log.info "turnOffAllLogging: starting async chain for ${queue.size()} operations"
    processTurnOffQueue()
}

void processTurnOffQueue() {
    List<Map> queue = turnOffQueue ?: []
    if (queue.isEmpty()) {
        finalizeTurnOff()
        return
    }
    Map item     = queue[0]
    turnOffQueue = queue.drop(1)

    asynchttpGet("handleTurnOffConfigResp",
        [uri: RM_BASE_URL, path: "${PATH_CONFIGURE_JSON}${item.ruleId}", timeout: HTTP_TIMEOUT_SECS],
        [ruleId: item.ruleId as String, fieldName: item.fieldName as String,
         newValue: item.newValue as Boolean, enumOption: item.enumOption as String]
    )
}

void handleTurnOffConfigResp(resp, data) {
    if (!turnOffActive) return
    String ruleId     = data.ruleId    as String
    String fieldName  = data.fieldName as String
    Boolean newValue  = data.newValue  as Boolean
    String enumOption = data.enumOption as String

    try {
        Map configData = [:]
        try {
            int httpStatus = resp.getStatus() as int
            if (httpStatus == 200) {
                Object raw = resp.getData()
                if (raw instanceof Map) configData = raw as Map
                else if (raw != null) configData = new groovy.json.JsonSlurper().parseText(raw.toString()) as Map ?: [:]
            } else {
                appendTurnOffError("HTTP ${httpStatus} fetching configure/json for rule ${ruleId}")
                processTurnOffQueue()
                return
            }
        } catch (Exception e) {
            appendTurnOffError("Error parsing configure/json for rule ${ruleId}: ${e.message}")
            processTurnOffQueue()
            return
        }

        Map configPage = (configData.configPage ?: [:]) as Map
        String pageName = (configPage.name ?: DEFAULT_PAGE_NAME) as String
        List sections = (configPage.sections ?: []) as List
        Boolean fieldFound = sections.any { sec ->
            ((sec as Map).input ?: []).any { inp -> (inp as Map).name == fieldName }
        }
        if (!fieldFound) {
            appendTurnOffError("Field '${fieldName}' not found on page '${pageName}' of rule ${ruleId}")
            processTurnOffQueue()
            return
        }

        String body = buildPostBody(ruleId, configData, fieldName, newValue, enumOption)
        asynchttpPost("handleTurnOffUpdateResp",
            [uri: RM_BASE_URL, path: PATH_UPDATE_JSON,
             requestContentType: "application/x-www-form-urlencoded",
             body: body, timeout: HTTP_TIMEOUT_SECS],
            [ruleId: ruleId, fieldName: fieldName]
        )
    } catch (Exception e) {
        appendTurnOffError("handleTurnOffConfigResp error for rule ${ruleId}: ${e.message}")
        processTurnOffQueue()
    }
}

void handleTurnOffUpdateResp(resp, data) {
    if (!turnOffActive) return
    String ruleId = data.ruleId as String
    try {
        int httpStatus = resp.getStatus() as int
        if (httpStatus == 200) {
            Object raw = resp.getData()
            boolean success = false
            if (raw instanceof Map) {
                success = (raw as Map).status == "success"
            } else if (raw != null) {
                String respText = raw.toString()
                try {
                    Map parsed = new groovy.json.JsonSlurper().parseText(respText) as Map
                    success = parsed?.status == "success"
                } catch (Exception ignored) {
                    success = respText.contains('"success"')
                }
            }
            if (!success) appendTurnOffError("Unexpected response for rule ${ruleId}: ${raw?.toString()?.take(200) ?: 'empty'}")
        } else {
            appendTurnOffError("HTTP ${httpStatus} updating rule ${ruleId}")
        }
    } catch (Exception e) {
        appendTurnOffError("handleTurnOffUpdateResp error for rule ${ruleId}: ${e.message}")
    } finally {
        processTurnOffQueue()
    }
}

void finalizeTurnOff() {
    unschedule("finalizeTurnOffTimeout")
    List<String> errors = turnOffErrors ?: []
    turnOffActive = false
    turnOffQueue  = null
    turnOffErrors = null
    if (errors) {
        state.lastError = "Some toggles failed: ${errors.join('; ')}"
        log.warn state.lastError
    } else {
        log.info "Turned off all logging for all rules"
    }
    findLoggingRules()
}

void finalizeTurnOffTimeout() {
    if (turnOffActive) {
        log.warn "turnOffAllLogging: timeout — finalizing with partial results"
        finalizeTurnOff()
    }
}

void appendTurnOffError(String msg) {
    if (turnOffErrors == null) turnOffErrors = []
    turnOffErrors << msg
    log.warn "turnOffAllLogging: ${msg}"
}


// ============================================================
// Report building
// ============================================================

// Reads lastEvtDate / lastEvtTime / timeFormat / dateFormat from a rule's appState,
// normalises to yyyy-MM-dd HH:mm so all entries sort correctly.
// BC rules don't expose these fields so their Last Run will always be blank.
String extractLastRun(Map status) {
    String lastEvtDate = ""
    String lastEvtTime = ""
    String timeFormat  = ""
    String dateFormat  = ""

    status?.appState?.each { item ->
        String n = item?.name?.toString() ?: ""
        if (n == "lastEvtDate") lastEvtDate = item?.value?.toString() ?: ""
        if (n == "lastEvtTime") lastEvtTime = item?.value?.toString() ?: ""
        if (n == "timeFormat")  timeFormat  = item?.value?.toString() ?: ""
        if (n == "dateFormat")  dateFormat  = item?.value?.toString() ?: ""
    }

    if (!lastEvtDate) return ""

    String normalizedDate = normalizeDateString(lastEvtDate, dateFormat)

    if (!lastEvtTime) return normalizedDate

    if (timeFormat) {
        try {
            java.text.SimpleDateFormat inFmt  = new java.text.SimpleDateFormat(timeFormat)
            java.text.SimpleDateFormat outFmt = new java.text.SimpleDateFormat("HH:mm")
            return "${normalizedDate} ${outFmt.format(inFmt.parse(lastEvtTime))}"
        } catch (Exception e) {
            if (debugEnable) log.debug "extractLastRun: could not parse '${lastEvtTime}' with format '${timeFormat}': ${e.message}"
        }
    }
    return "${normalizedDate} ${normalizeTimeString(lastEvtTime)}"
}

@CompileStatic
private String normalizeTimeString(String timeStr) {
    List<String> formats = ["HH:mm:ss", "HH:mm", "h:mm:ss a", "h:mm a", "hh:mm:ss a", "hh:mm a"]
    for (String fmt : formats) {
        try {
            return new java.text.SimpleDateFormat("HH:mm").format(
                new java.text.SimpleDateFormat(fmt).parse(timeStr)
            )
        } catch (Exception ignored) {}
    }
    return timeStr
}

// Tries dateFormat hint first, then common hub date patterns; always returns yyyy-MM-dd for correct lexicographic sort.
@CompileStatic
private String normalizeDateString(String dateStr, String dateFormat) {
    List<String> formats = []
    if (dateFormat) formats << dateFormat
    formats += ["yyyy-MM-dd", "dd-MMM-yyyy", "MM/dd/yyyy", "dd/MM/yyyy", "M/d/yyyy", "d-MMM-yyyy"]
    for (String fmt : formats) {
        try {
            return new java.text.SimpleDateFormat("yyyy-MM-dd").format(
                new java.text.SimpleDateFormat(fmt).parse(dateStr)
            )
        } catch (Exception ignored) {}
    }
    return dateStr
}

// Encode all HTML, then selectively restore safe color spans so names like
// "<span style='color:red'>TEXT</span>" render as coloured text.
// Colour values are restricted to [a-zA-Z#0-9]+ to prevent injection.
@CompileStatic
String renderNameHtml(Object value) {
    if (value == null) return ""
    String encoded = htmlEncode(value)
    return encoded.replaceAll(
        /&lt;span style=(?:&#39;|&quot;)color:([a-zA-Z#0-9]+)(?:&#39;|&quot;)&gt;(.*?)&lt;\/span&gt;/,
        "<span style='color:\$1'>\$2</span>"
    )
}

@CompileStatic
private String buildCss() {
    return "<style>" +
        "table.rmlogcheck{border-collapse:collapse;width:100%;}" +
        "table.rmlogcheck th,table.rmlogcheck td{border:1px solid #ccc;padding:4px 7px;text-align:left;vertical-align:middle;}" +
        "table.rmlogcheck th{background-color:#FFD700;color:#000;cursor:pointer;font-weight:bold;user-select:none;white-space:nowrap;}" +
        "table.rmlogcheck th:hover{background-color:#FFC700;}" +
        "table.rmlogcheck th.sort-asc::after{content:' ▲';font-size:0.8em;}" +
        "table.rmlogcheck th.sort-desc::after{content:' ▼';font-size:0.8em;}" +
        "table.rmlogcheck td.center,table.rmlogcheck th.center{text-align:center;}" +
        "table.rmlogcheck td.rmcol-lastrun{white-space:nowrap;}" +
        ".rmcol-toggle-bar{margin-bottom:8px;font-size:0.9em;}" +
        ".rmcol-btn{display:inline-block;cursor:pointer;padding:2px 8px;margin-right:6px;" +
        "border:1px solid #aaa;border-radius:3px;background:#e8e8e8;user-select:none;}" +
        ".rmcol-btn.hidden-col{text-decoration:line-through;opacity:0.45;background:#ccc;}" +
        "table.rmlogcheck td.rmlog-clickable{cursor:pointer;}" +
        "table.rmlogcheck td.rmlog-clickable:hover{filter:brightness(0.82);}" +
        "table.rmlogcheck td.rmlog-toggling{opacity:0.45;cursor:wait;pointer-events:none;}" +
        "</style>"
}

@CompileStatic
private String buildJavaScript() {
    return '''<script>
function sortRmLogTable(tableId, columnIndex) {
    const table = document.getElementById(tableId);
    if (!table) return;
    const tbody = table.querySelector('tbody');
    if (!tbody) return;
    const rows = Array.from(tbody.querySelectorAll('tr'));
    const headers = table.querySelectorAll('th');
    if (!window.rmLogTableSorts) window.rmLogTableSorts = {};
    if (!window.rmLogTableSorts[tableId]) window.rmLogTableSorts[tableId] = {};
    const currentDirection = window.rmLogTableSorts[tableId][columnIndex] || 'asc';
    const newDirection = currentDirection === 'asc' ? 'desc' : 'asc';
    window.rmLogTableSorts[tableId][columnIndex] = newDirection;
    headers.forEach(header => { header.classList.remove('sort-asc', 'sort-desc'); });
    if (headers[columnIndex]) headers[columnIndex].classList.add('sort-' + newDirection);
    rows.sort((a, b) => {
        const aCell = a.querySelectorAll('td')[columnIndex];
        const bCell = b.querySelectorAll('td')[columnIndex];
        let aText = aCell ? (aCell.getAttribute('data-sort') || aCell.textContent || '').trim() : '';
        let bText = bCell ? (bCell.getAttribute('data-sort') || bCell.textContent || '').trim() : '';
        const aNum = parseFloat(aText.replace(/[^0-9.]/g, ''));
        const bNum = parseFloat(bText.replace(/[^0-9.]/g, ''));
        let comparison = 0;
        if (aText !== '' && bText !== '' && !isNaN(aNum) && !isNaN(bNum)) {
            comparison = aNum - bNum;
        } else {
            comparison = aText.toLowerCase().localeCompare(bText.toLowerCase());
        }
        return newDirection === 'asc' ? comparison : -comparison;
    });
    rows.forEach(row => tbody.appendChild(row));
}

function toggleRmCol(cls, btn) {
    var hiding = btn.className.indexOf('hidden-col') === -1;
    document.querySelectorAll('.' + cls).forEach(function(el) { el.style.display = hiding ? 'none' : ''; });
    btn.className = hiding ? 'rmcol-btn hidden-col' : 'rmcol-btn';
}

async function rmToggleLogging(td) {
    if (td.dataset.toggling) return;
    td.dataset.toggling = '1';
    td.classList.remove('rmlog-clickable');
    td.classList.add('rmlog-toggling');
    var ruleId = td.dataset.ruleId, fieldName = td.dataset.field, fieldType = td.dataset.fieldType,
        enumOption = td.dataset.enumOption, currentOn = td.dataset.on === 'true', newOn = !currentOn;
    try {
        var cfgResp = await fetch('/installedapp/configure/json/' + ruleId);
        if (!cfgResp.ok) throw new Error('configure/json HTTP ' + cfgResp.status);
        var config = await cfgResp.json();
        var appInfo = config.app || {}, configPage = config.configPage || {}, settings = config.settings || {};
        var pageName = configPage.name || 'mainPage', appLabel = appInfo.label || '';
        var sections = configPage.sections || [];
        var fd = new URLSearchParams();
        fd.set('_action_update', 'Done');
        fd.set('formAction', 'update');
        fd.set('id', ruleId);
        fd.set('version', String(appInfo.version || '1'));
        fd.set('appTypeId', '');
        fd.set('appTypeName', '');
        fd.set('currentPage', pageName);
        fd.set('pageBreadcrumbs', '[]');
        sections.forEach(function(sec) {
            (sec.body || []).forEach(function(elem) {
                if (elem.element === 'label') {
                    var ln = elem.name || 'label';
                    fd.set(ln + '.type', 'text');
                    fd.set(ln, appLabel);
                }
            });
        });
        sections.forEach(function(sec) {
            (sec.input || []).forEach(function(inp) {
                var name = inp.name, type = inp.type || '', multiple = !!inp.multiple;
                fd.set(name + '.type', type);
                fd.set(name + '.multiple', String(multiple));
                if (name === fieldName) {
                    if (fieldType === 'bool') {
                        if (newOn) fd.set('checkbox[' + name + ']', 'on');
                        fd.set('settings[' + name + ']', String(newOn));
                    } else {
                        var arr = settings[name];
                        if (typeof arr === 'string') { try { arr = JSON.parse(arr); } catch(e) { arr = []; } }
                        if (!Array.isArray(arr)) arr = arr ? [String(arr)] : [];
                        if (newOn) { if (!arr.includes(enumOption)) arr.push(enumOption); }
                        else { arr = arr.filter(function(x) { return x !== enumOption; }); }
                        fd.set('settings[' + name + ']', JSON.stringify(arr));
                    }
                } else {
                    var cur = settings[name];
                    if (type === 'bool') {
                        var bv = cur === true || cur === 'true';
                        if (bv) fd.set('checkbox[' + name + ']', 'on');
                        fd.set('settings[' + name + ']', String(bv));
                    } else if (type.startsWith('capability.')) {
                        var ids = (cur && typeof cur === 'object' && !Array.isArray(cur))
                            ? Object.keys(cur).join(',')
                            : (cur != null ? String(cur) : '');
                        fd.set('settings[' + name + ']', ids);
                        fd.append('deviceList', name);
                        fd.append('', '');
                    } else {
                        var sv = cur == null ? '' : (typeof cur === 'object' ? JSON.stringify(cur) : String(cur));
                        fd.set('settings[' + name + ']', sv);
                    }
                }
            });
        });
        fd.set('referrer', window.location.origin + '/installedapp/list');
        fd.set('url', window.location.origin + '/installedapp/configure/' + ruleId + '/' + pageName);
        fd.set('_cancellable', 'false');
        var postResp = await fetch('/installedapp/update/json', {
            method: 'POST',
            headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
            body: fd.toString()
        });
        if (!postResp.ok) throw new Error('update/json HTTP ' + postResp.status);
        var result = await postResp.json();
        if (result.status !== 'success') throw new Error(result.message || JSON.stringify(result));
        td.dataset.on = String(newOn);
        td.setAttribute('data-sort', newOn ? '1' : '0');
        td.innerHTML = newOn ? "<span style='color:red;font-weight:bold;'>ON</span>"
                             : "<span style='color:green;font-weight:bold;'>OFF</span>";
    } catch(e) {
        alert('Toggle failed: ' + e.message);
    } finally {
        delete td.dataset.toggling;
        td.classList.remove('rmlog-toggling');
        td.classList.add('rmlog-clickable');
    }
}

async function rmToggleDisabled(td) {
    if (td.dataset.toggling) return;
    td.dataset.toggling = '1';
    td.classList.remove('rmlog-clickable');
    td.classList.add('rmlog-toggling');
    var ruleId = td.dataset.ruleId, newOn = td.dataset.on !== 'true';
    try {
        var resp = await fetch('/installedapp/disable', {
            method: 'POST',
            headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
            body: new URLSearchParams({ id: ruleId, disable: String(newOn) }).toString()
        });
        if (!resp.ok) throw new Error('HTTP ' + resp.status);
        var result = await resp.json();
        if (result.result !== newOn) throw new Error(JSON.stringify(result));
        td.dataset.on = String(newOn);
        td.setAttribute('data-sort', newOn ? '1' : '0');
        td.innerHTML = newOn ? "<span style='color:red;font-weight:bold;'>Yes</span>"
                             : "<span style='color:green;font-weight:bold;'>No</span>";
        var tr = td.closest('tr');
        if (newOn) tr.classList.add('rmrow-disabled'); else tr.classList.remove('rmrow-disabled');
    } catch(e) {
        alert('Toggle disabled failed: ' + e.message);
    } finally {
        delete td.dataset.toggling;
        td.classList.remove('rmlog-toggling');
        td.classList.add('rmlog-clickable');
    }
}

async function rmTogglePaused(td) {
    if (td.dataset.toggling) return;
    td.dataset.toggling = '1';
    td.classList.remove('rmlog-clickable');
    td.classList.add('rmlog-toggling');
    var ruleId = td.dataset.ruleId, newOn = td.dataset.on !== 'true';
    try {
        var fd = new URLSearchParams();
        fd.set('id', ruleId);
        fd.set('name', 'pausRule');
        fd.set('settings[pausRule]', 'clicked');
        fd.set('pausRule.type', 'button');
        var resp = await fetch('/installedapp/btn', {
            method: 'POST',
            headers: { 'Content-Type': 'application/x-www-form-urlencoded', 'X-Requested-With': 'XMLHttpRequest' },
            body: fd.toString()
        });
        if (!resp.ok) throw new Error('HTTP ' + resp.status);
        var result = await resp.json();
        if (result.status !== 'success') throw new Error(result.message || JSON.stringify(result));
        td.dataset.on = String(newOn);
        td.setAttribute('data-sort', newOn ? '1' : '0');
        td.innerHTML = newOn ? "<span style='color:red;font-weight:bold;'>Yes</span>"
                             : "<span style='color:green;font-weight:bold;'>No</span>";
        var tr = td.closest('tr');
        if (newOn) tr.classList.add('rmrow-paused'); else tr.classList.remove('rmrow-paused');
    } catch(e) {
        alert('Toggle paused failed: ' + e.message);
    } finally {
        delete td.dataset.toggling;
        td.classList.remove('rmlog-toggling');
        td.classList.add('rmlog-clickable');
    }
}
</script>'''
}

String buildReportHtml(List<Map> rows) {
    if (!rows) return "<p>No Rule Machine or Button Controller rules found to display.</p>"

    String hubIp = location.hub.localIP

    StringBuilder sb = new StringBuilder()
    sb << buildCss()
    sb << buildJavaScript()

    sb << "<div class='rmcol-toggle-bar'>"
    sb << "<b>Hide rows:</b>&nbsp;"
    sb << "<span class='rmcol-btn' onclick=\"toggleRmCol('rmrow-disabled',this)\">Disabled rules</span>"
    sb << "<span class='rmcol-btn' onclick=\"toggleRmCol('rmrow-paused',this)\">Paused rules</span>"
    sb << "<span class='rmcol-btn hidden-col' onclick=\"toggleRmCol('rmrow-logoff',this)\">No logging ON</span>"
    sb << "&nbsp;&nbsp;<b>Hide columns:</b>&nbsp;"
    sb << "<span class='rmcol-btn' onclick=\"toggleRmCol('rmcol-disabled',this)\">Disabled</span>"
    sb << "<span class='rmcol-btn' onclick=\"toggleRmCol('rmcol-paused',this)\">Paused</span>"
    sb << "<span class='rmcol-btn' onclick=\"toggleRmCol('rmcol-events',this)\">Events</span>"
    sb << "<span class='rmcol-btn' onclick=\"toggleRmCol('rmcol-triggers',this)\">Triggers</span>"
    sb << "<span class='rmcol-btn' onclick=\"toggleRmCol('rmcol-actions',this)\">Actions</span>"
    sb << "<span class='rmcol-btn' onclick=\"toggleRmCol('rmcol-lastrun',this)\">Last Run</span>"
    sb << "</div>"

    sb << "<table id='${TABLE_ID}' class='rmlogcheck'><thead><tr>"
    sb << "<th onclick=\"sortRmLogTable('${TABLE_ID}',0)\" class='center'>Rule ID</th>"
    sb << "<th onclick=\"sortRmLogTable('${TABLE_ID}',1)\" class='sort-asc'>Rule</th>"
    sb << "<th onclick=\"sortRmLogTable('${TABLE_ID}',2)\" class='center'>App Type</th>"
    sb << "<th onclick=\"sortRmLogTable('${TABLE_ID}',3)\" class='center rmcol-disabled'>Disabled</th>"
    sb << "<th onclick=\"sortRmLogTable('${TABLE_ID}',4)\" class='center rmcol-paused'>Paused</th>"
    sb << "<th onclick=\"sortRmLogTable('${TABLE_ID}',5)\" class='center rmcol-events'>Events</th>"
    sb << "<th onclick=\"sortRmLogTable('${TABLE_ID}',6)\" class='center rmcol-triggers'>Triggers</th>"
    sb << "<th onclick=\"sortRmLogTable('${TABLE_ID}',7)\" class='center rmcol-actions'>Actions</th>"
    sb << "<th onclick=\"sortRmLogTable('${TABLE_ID}',8)\" class='center rmcol-lastrun'>Last Run</th>"
    sb << "</tr></thead><tbody>"

    rows.each { Map r ->
        String id        = htmlEncode(r.id)
        String nameSort  = htmlEncode(r.name?.toString()?.replaceAll(/<[^>]+>/, '') ?: "")
        String nameHtml  = renderNameHtml(r.name)
        String appType   = htmlEncode(r.appType ?: "RM")
        String disabled  = formatYesNo(r.disabled as Boolean)
        String paused    = formatYesNo(r.paused   as Boolean)
        String actions   = formatOnOff(r.actionsOn  as Boolean)
        String events    = formatOnOff(r.eventsOn   as Boolean)
        String triggers  = formatOnOff(r.triggersOn as Boolean)
        String lastRun   = htmlEncode(r.lastRun ?: "")
        String disabledSort = r.disabled   ? "1" : "0"
        String pausedSort   = r.paused     ? "1" : "0"
        String actionsSort  = r.actionsOn  ? "1" : "0"
        String eventsSort   = r.eventsOn   ? "1" : "0"
        String triggersSort = r.triggersOn ? "1" : "0"

        Boolean anyOn = (r.actionsOn as Boolean) || (r.eventsOn as Boolean) || (r.triggersOn as Boolean)
        List<String> trClasses = []
        if (r.disabled as Boolean) trClasses << "rmrow-disabled"
        if (r.paused   as Boolean) trClasses << "rmrow-paused"
        if (!anyOn)                trClasses << "rmrow-logoff"
        String trStyle = (!anyOn) ? " style='display:none'" : ""
        String trAttr  = trClasses ? " class='${trClasses.join(' ')}'" : ""

        // When all three share the same field name it's an enum-multiple (e.g. a "logging" field
        // with options Events/Triggers/Actions) — JS adds/removes the specific option rather than flipping the whole field.
        boolean sharedField = r.actionsField && r.actionsField == r.eventsField && r.actionsField == r.triggersField
        String actionsFieldType  = sharedField ? "enum-multiple" : "bool"
        String eventsFieldType   = sharedField ? "enum-multiple" : "bool"
        String triggersFieldType = sharedField ? "enum-multiple" : "bool"

        // Returns a clickable <td> when the field name is known; plain <td> otherwise
        Closure<String> clickableTd = { String colClass, String field, String fType, String enumOpt, Boolean isOn, String sortVal, String displayHtml ->
            if (!field) {
                return "<td class='center ${colClass}' data-sort='${sortVal}'>${displayHtml}</td>"
            }
            String escapedField = htmlEncode(field)
            String escapedOpt   = htmlEncode(enumOpt)
            return "<td class='center ${colClass} rmlog-clickable' data-sort='${sortVal}'" +
                " data-rule-id='${id}' data-field='${escapedField}' data-field-type='${fType}'" +
                " data-enum-option='${escapedOpt}' data-on='${isOn}'" +
                " onclick='rmToggleLogging(this)'>${displayHtml}</td>"
        }

        sb << "<tr${trAttr}${trStyle}>"
        sb << "<td class='center' data-sort='${id}'>${id}</td>"
        sb << "<td data-sort='${nameSort}'><a href='http://${hubIp}${PATH_CONFIGURE}${id}' target='_blank'>${nameHtml}</a></td>"
        sb << "<td class='center' data-sort='${appType}'>${appType}</td>"
        sb << "<td class='center rmcol-disabled rmlog-clickable' data-sort='${disabledSort}' data-rule-id='${id}' data-on='${r.disabled as Boolean}' onclick='rmToggleDisabled(this)'>${disabled}</td>"
        String rawAppType = (r.appType ?: "RM") as String
        if (rawAppType == "RM") {
            sb << "<td class='center rmcol-paused rmlog-clickable' data-sort='${pausedSort}' data-rule-id='${id}' data-on='${r.paused as Boolean}' onclick='rmTogglePaused(this)'>${paused}</td>"
        } else {
            sb << "<td class='center rmcol-paused' data-sort='${pausedSort}'>${paused}</td>"
        }
        sb << clickableTd("rmcol-events",   r.eventsField   as String, eventsFieldType,   "Events",   r.eventsOn   as Boolean, eventsSort,   events)
        sb << clickableTd("rmcol-triggers", r.triggersField as String, triggersFieldType, "Triggers", r.triggersOn as Boolean, triggersSort, triggers)
        sb << clickableTd("rmcol-actions",  r.actionsField  as String, actionsFieldType,  "Actions",  r.actionsOn  as Boolean, actionsSort,  actions)
        sb << "<td class='center rmcol-lastrun'  data-sort='${lastRun}'>${lastRun}</td>"
        sb << "</tr>"
    }

    sb << "</tbody></table>"
    return sb.toString()
}

@CompileStatic
String formatOnOff(Boolean value) {
    return value ? "<span style='color:red;font-weight:bold;'>ON</span>" : "<span style='color:green;font-weight:bold;'>OFF</span>"
}

@CompileStatic
String formatYesNo(Boolean value) {
    return value ? "<span style='color:red;font-weight:bold;'>Yes</span>" : "<span style='color:green;font-weight:bold;'>No</span>"
}

@CompileStatic
String formatScanDuration(Long elapsedMs) {
    Long safeMs = elapsedMs ?: 0L
    if (safeMs < 0L) safeMs = 0L
    Long totalSeconds = Math.round(safeMs / 1000.0D) as Long
    Long minutes = Math.floor(totalSeconds / 60.0D) as Long
    Long seconds = totalSeconds % 60L
    return String.format("%02d:%02d", minutes, seconds)
}

@CompileStatic
String htmlEncode(Object value) {
    if (value == null) return ""
    return value.toString()
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace('"', "&quot;")
        .replace("'", "&#39;")
}

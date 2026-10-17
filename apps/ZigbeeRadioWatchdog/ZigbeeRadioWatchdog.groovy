// Copyright (c) 2026 PJ
// SPDX-License-Identifier: MIT

/*
 Zigbee Radio Watchdog

 When the Zigbee radio goes offline, saves the hub's diagnostic state to File Manager and
 writes JVM thread dumps to the engineering logs. If the radio stays offline, reboots the
 radio, then the hub.

 Escalation: offline -> capture + thread dumps -> wait -> radio reboot -> wait -> final
 capture + thread dump -> hub reboot. Every step is re-derived from timestamps in state, so a
 lost callback or a code push resumes on the next check.
 */

import groovy.transform.Field
import groovy.json.JsonOutput
import java.nio.file.NoSuchFileException

@Field static final String CODE_VERSION = "0.2.0"
@Field static final String BASE_URL = "http://127.0.0.1:8080"
@Field static final String FILE_PREFIX = "zigbee-incident-"

// [name, path]. Read with textParser so nothing is parsed or re-serialized. The log buffer is
// largest, so it goes last: the file stays small while it is rewritten after each response.
@Field static final List<List<String>> FULL_CAPTURE = [
    ["zigbee-details",  "/hub/zigbeeDetails/json"],
    ["zigbee-tables",   "/hub/zigbee/getChildAndRouteInfoJson"],
    ["zigbee-health",   "/hub/zigbee/healthStatus"],
    ["runtime-stats",   "/logs/json"],
    ["memory-history",  "/hub/advanced/freeOSMemoryHistory"],
    ["backup-status",   "/hub/backup/statusJson"],
    ["local-backups",   "/hub2/localBackups"],
    ["hub-data",        "/hub2/hubData"],
    ["temperature",     "/hub/advanced/internalTempCelsius"],
    ["db-size",         "/hub/advanced/databaseSize"],
    ["hub-events",      "/hub/eventsJson"],
    ["location-events", "/logs/eventsJson"],
    ["past-logs",       "/logs/past/json"],
]
// Before a hub reboot: what the reboot wipes, plus the radio's latest view.
@Field static final List<List<String>> FINAL_CAPTURE = [
    ["zigbee-details",  "/hub/zigbeeDetails/json"],
    ["runtime-stats",   "/logs/json"],
    ["memory-history",  "/hub/advanced/freeOSMemoryHistory"],
    ["backup-status",   "/hub/backup/statusJson"],
]

definition(
    name: "Zigbee Radio Watchdog",
    namespace: "iamtrep",
    author: "pj",
    description: "Saves diagnostics when the Zigbee radio goes offline, then reboots the radio and, if needed, the hub",
    menu: "Automations",
    category: "Utility",
    iconUrl: "",
    iconX2Url: "",
    importUrl: "https://raw.githubusercontent.com/iamtrep/hubitat/refs/heads/main/apps/ZigbeeRadioWatchdog/ZigbeeRadioWatchdog.groovy",
    singleInstance: true,
    singleThreaded: true
)

preferences {
    page(name: "mainPage")
}

Map mainPage() {
    dynamicPage(name: "mainPage", title: "Zigbee Radio Watchdog", install: true, uninstall: true) {
        section("Status") {
            paragraph statusHtml()
            input name: "btnCapture", type: "button", title: "Capture diagnostics now"
        }
        section("Recovery") {
            input name: "offlineWaitMin", type: "number", title: "Minutes to wait for the radio to recover on its own", defaultValue: 5, range: "1..120", required: true
            input name: "radioRebootEnable", type: "bool", title: "Reboot the Zigbee radio if it stays offline", defaultValue: true
            input name: "radioWaitMin", type: "number", title: "Minutes to wait after a radio reboot", defaultValue: 3, range: "1..60", required: true
            input name: "hubRebootEnable", type: "bool", title: "Reboot the hub if the radio is still offline", defaultValue: true
            input name: "minRebootGapHrs", type: "number", title: "Never reboot the hub more often than every N hours", defaultValue: 6, range: "1..168", required: true
        }
        section("Diagnostics") {
            input name: "threadDumps", type: "number", title: "Thread dumps at detection (30 s apart)", defaultValue: 3, range: "0..5", required: true
            input name: "maxIncidents", type: "number", title: "Incidents to keep in File Manager", defaultValue: 5, range: "1..50", required: true
        }
        section("Notifications") {
            input name: "notifyDevices", type: "capability.notification", title: "Notify these devices", multiple: true, required: false
        }
        section("Logging") {
            input name: "txtEnable", type: "bool", title: "Enable info logging", defaultValue: true
            input name: "debugEnable", type: "bool", title: "Enable debug logging", defaultValue: false, submitOnChange: true
            if (settings.debugEnable) {
                input name: "traceEnable", type: "bool", title: "Enable trace logging", defaultValue: false
            }
        }
    }
}

String statusHtml() {
    Map inc = state.incident
    List<String> lines = []
    lines << (inc ? "<b>Incident ${inc.id} open</b>, step: ${inc.step}" : "No open incident.")
    List<Map> past = (state.history ?: []) as List<Map>
    past.reverse().each { Map h ->
        String files = (h.files ?: []).collect { String f -> "<a href='/local/${f}' target='_blank'>${f}</a>" }.join(", ")
        lines << "${h.id}: ${h.outcome ?: 'open'}${files ? ' &mdash; ' + files : ''}"
    }
    return lines.join("<br>")
}

// ── Lifecycle ─────────────────────────────────────────────────────────

void installed() {
    initialize()
}

void updated() {
    unsubscribe()
    unschedule()
    initialize()
}

void initialize() {
    checkVersion(false)
    if (state.history == null) state.history = []
    subscribe(location, "zigbeeOff", "zigbeeOffHandler")
    subscribe(location, "zigbeeStatus", "zigbeeStatusHandler")
    subscribe(location, "systemStart", "systemStartHandler")
    // The offline event is the trigger; this poll catches a missed event and resumes a stalled step.
    schedule("${new Random().nextInt(60)} */2 * ? * *", "periodicCheck")
    if (debugEnable || traceEnable) runIn(1800, "logsOff")
    logCfg "initialized (v${CODE_VERSION})"
}

void uninstalled() {
    unschedule()
}

void checkVersion(boolean reinit = true) {
    if (state.version == CODE_VERSION) return
    logVer "version ${CODE_VERSION} (was ${state.version})"
    state.version = CODE_VERSION
    if (reinit) runIn(1, "updated")
}

void logsOff() {
    app.updateSetting("debugEnable", [value: "false", type: "bool"])
    app.updateSetting("traceEnable", [value: "false", type: "bool"])
    logWarn "debug and trace logging disabled"
}

void appButtonHandler(String btn) {
    checkVersion()
    if (btn == "btnCapture") {
        if (state.incident) { logWarn "an incident is open; not starting a manual capture"; return }
        String id = newIncidentId()
        addHistory([id: id, outcome: "manual capture", files: []])
        startCapture(id, "full", null)
    }
}

// ── Detection ─────────────────────────────────────────────────────────

void zigbeeOffHandler(evt) {
    checkVersion()
    logEvt "location event ${evt.name}: ${evt.descriptionText}"
    radioOffline("location event ${evt.name}")
}

void zigbeeStatusHandler(evt) {
    checkVersion()
    logEvt "location event ${evt.name} = ${evt.value}"
    if (evt.value == "offline") radioOffline("zigbeeStatus offline")
}

void systemStartHandler(evt) {
    checkVersion()
    Map inc = state.incident
    if (inc?.step == "hubReboot") {
        logInfo "hub restarted during incident ${inc.id}; checking the radio"
        inc.step = "afterHubReboot"
        inc.stepAt = now()
        state.incident = inc
    }
    runIn(30, "periodicCheck")
}

void periodicCheck() {
    checkVersion()
    asynchttpGet("radioStateCallback", [uri: BASE_URL, path: "/hub/zigbeeDetails/json", timeout: 30], [:])
}

void radioStateCallback(resp, data) {
    Boolean online = null
    if (resp.hasError()) {
        logWarn "radio state read failed: ${resp.getErrorMessage()}"
    } else if (resp.getStatus() != 200) {
        logWarn "radio state read returned HTTP ${resp.getStatus()}"
    } else {
        try {
            Map z = resp.getJson() as Map
            online = z.networkState == "ONLINE" && z.healthy != false
            // showReboot is false on a C-7 and earlier, and also while the radio is disabled, so
            // only a reading taken while the radio is up says whether the hub can reboot the radio.
            if (online) state.radioRebootSupported = (z.showReboot == true)
            logTrace "radio networkState=${z.networkState} healthy=${z.healthy} showReboot=${z.showReboot}"
        } catch (Exception e) {
            logWarn "radio state unreadable: ${e.message}"
        }
    }
    if (online == null) return
    if (!online && !state.incident) radioOffline("networkState not ONLINE")
    else if (state.incident) serviceIncident(online)
}

void radioOffline(String reason) {
    if (state.incident) { logDebug "already handling incident ${state.incident.id}"; return }
    String id = newIncidentId()
    logWarn "Zigbee radio offline (${reason}); incident ${id}"
    notify("Zigbee radio offline (${reason}). Saving diagnostics.")
    state.incident = [id: id, startMs: now(), step: "waitOffline", stepAt: now(), reason: reason]
    addHistory([id: id, outcome: null, files: []])
    startCapture(id, "full", null)
    startThreadDumps((settings.threadDumps ?: 3) as int)
    runIn(60, "periodicCheck")
}

// ── Escalation ────────────────────────────────────────────────────────

void serviceIncident(boolean online) {
    Map inc = state.incident
    if (online) {
        String how = [radioReboot: "after a radio reboot", afterHubReboot: "after a hub reboot"][inc.step as String] ?: "on its own"
        closeIncident("radio recovered ${how}")
        return
    }
    long elapsedMin = (now() - (inc.stepAt as long)).intdiv(60000L)
    switch (inc.step) {
        case "waitOffline":
            if (elapsedMin < ((settings.offlineWaitMin ?: 5) as int)) break
            if (settings.radioRebootEnable != false && state.radioRebootSupported) {
                setStep("radioReboot")
                logCmd "rebooting the Zigbee radio"
                notify("Zigbee radio still offline. Rebooting the radio.")
                asynchttpGet("radioRebootCallback", [uri: BASE_URL, path: "/hub/rebootZigbeeRadio", textParser: true, timeout: 60], [:])
            } else {
                logInfo "skipping the radio reboot (${state.radioRebootSupported ? 'disabled in settings' : 'this hub does not offer it'})"
                prepareHubReboot()
            }
            break
        case "radioReboot":
            if (elapsedMin < ((settings.radioWaitMin ?: 3) as int)) break
            prepareHubReboot()
            break
        case "finalCapture":
            // The final capture chain normally moves on to hubReboot; resume it if the chain was lost.
            if (elapsedMin >= 5) doHubReboot()
            break
        case "hubReboot":
            // The reboot request did not take, or the hub came back without a systemStart event.
            if (elapsedMin >= 15) closeIncident("radio still offline; hub reboot did not happen")
            break
        case "afterHubReboot":
            if (elapsedMin >= 10) closeIncident("radio still offline after a hub reboot")
            break
    }
    if (state.incident) runIn(30, "periodicCheck")
}

void radioRebootCallback(resp, data) {
    if (resp.hasError()) logError "radio reboot failed: ${resp.getErrorMessage()}"
    else logInfo "radio reboot returned HTTP ${resp.getStatus()}: ${resp.getData()?.toString()?.take(40)}"
    runIn(20, "periodicCheck")
}

void prepareHubReboot() {
    if (settings.hubRebootEnable == false) {
        closeIncident("radio still offline; hub reboot disabled")
        return
    }
    long gapMs = ((settings.minRebootGapHrs ?: 6) as long) * 3600000L
    if (state.lastHubRebootMs && now() - (state.lastHubRebootMs as long) < gapMs) {
        closeIncident("radio still offline; hub rebooted less than ${settings.minRebootGapHrs ?: 6} h ago, not rebooting again")
        return
    }
    setStep("finalCapture")
    startThreadDumps(1)
    startCapture(state.incident.id as String, "final", "doHubReboot")
}

void doHubReboot() {
    Map inc = state.incident
    if (inc?.step != "finalCapture") return
    setStep("hubReboot")
    state.lastHubRebootMs = now()
    logWarn "rebooting the hub (incident ${inc.id})"
    notify("Zigbee radio still offline. Rebooting the hub.")
    // purgeLogs false keeps the logs that the thread dumps were written to.
    asynchttpPost("hubRebootCallback", [uri: BASE_URL, path: "/hub/reboot", contentType: "application/json", requestContentType: "application/json",
        body: JsonOutput.toJson([rebuildDatabase: false, purgeLogs: false]), textParser: true, timeout: 30], [:])
}

void hubRebootCallback(resp, data) {
    if (resp.hasError()) logError "hub reboot request failed: ${resp.getErrorMessage()}"
    else logInfo "hub reboot request returned HTTP ${resp.getStatus()}"
}

void setStep(String step) {
    Map inc = state.incident
    inc.step = step
    inc.stepAt = now()
    state.incident = inc
}

void closeIncident(String outcome) {
    Map inc = state.incident
    long mins = (now() - (inc.startMs as long)).intdiv(60000L)
    logInfo "incident ${inc.id} closed after ${mins} min: ${outcome}"
    notify("Zigbee incident closed after ${mins} min: ${outcome}")
    updateHistory(inc.id as String) { Map h -> h.outcome = outcome }
    state.remove("incident")
}

// ── Capture ───────────────────────────────────────────────────────────
// One text file per capture. Each response is appended under a header line as the hub sent it,
// and the file is rewritten after every response, so a capture cut short keeps what it got.

void startCapture(String id, String set, String then) {
    String fileName = "${FILE_PREFIX}${id}${set == 'final' ? '-final' : ''}.txt"
    logInfo "saving ${set} diagnostics for incident ${id} to ${fileName}"
    String head = "Zigbee Radio Watchdog ${CODE_VERSION}, incident ${id}, ${set} capture, " +
        "${location.hub.name}, firmware ${location.hub.firmwareVersionString}, started ${timestamp()}\n"
    if (!saveCapture(fileName, head)) return
    updateHistory(id) { Map h -> if (!(fileName in h.files)) h.files << fileName }
    captureNext([id: id, set: set, idx: 0, then: then, file: fileName])
}

void captureNext(Map ctx) {
    List<List<String>> list = ctx.set == "final" ? FINAL_CAPTURE : FULL_CAPTURE
    int idx = ctx.idx as int
    if (idx >= list.size()) {
        logInfo "diagnostics saved for incident ${ctx.id} (${ctx.set})"
        if (ctx.then) runIn(10, ctx.then as String)
        return
    }
    List<String> item = list[idx]
    asynchttpGet("captureCallback", [uri: BASE_URL, path: item[1], textParser: true, timeout: 90],
        ctx + [name: item[0], path: item[1]])
}

void captureCallback(resp, data) {
    String status
    String body
    if (resp.hasError()) {
        status = "error"
        body = resp.getErrorMessage() ?: ""
    } else {
        status = "HTTP ${resp.getStatus()}"
        body = resp.getData()?.toString() ?: ""
    }
    String section = "\n===== ${data.name}  GET ${data.path}  ${status}  ${timestamp()} =====\n${body}\n"
    String existing = ""
    try {
        // A missing file throws up to 2.5.2 and returns null from 2.5.3: either way, start it.
        byte[] bytes = downloadHubFile(data.file as String)
        if (bytes != null) existing = new String(bytes, "UTF-8")
    } catch (NoSuchFileException ignored) {
    } catch (Exception e) {
        logWarn "could not read ${data.file} back (${e.message}); starting it again"
    }
    if (saveCapture(data.file as String, existing + section)) logDebug "added ${data.name} to ${data.file} (${body.length()} chars)"
    captureNext(data + [idx: (data.idx as int) + 1])
}

boolean saveCapture(String fileName, String content) {
    try {
        uploadHubFile(fileName, content.getBytes("UTF-8"))
        return true
    } catch (Exception e) {
        logError "could not save ${fileName}: ${e.message}"
        return false
    }
}

String timestamp() {
    return new Date().format("yyyy-MM-dd HH:mm:ss", location.timeZone)
}

void startThreadDumps(int count) {
    if (count <= 0) return
    asynchttpGet("threadDumpCallback", [uri: BASE_URL, path: "/hub/advanced/dumpThreadsToLog", textParser: true, timeout: 60], [left: count - 1])
}

void threadDumpCallback(resp, data) {
    if (resp.hasError()) logError "thread dump failed: ${resp.getErrorMessage()}"
    else logInfo "thread dump written to the engineering logs"
    if ((data.left as int) > 0) runIn(30, "nextThreadDump", [data: [left: data.left]])
}

void nextThreadDump(Map data) {
    startThreadDumps((data.left as int))
}

// ── History and retention ─────────────────────────────────────────────

String newIncidentId() {
    return new Date().format("yyyyMMdd-HHmmss", location.timeZone)
}

void addHistory(Map entry) {
    List<Map> hist = (state.history ?: []) as List<Map>
    hist << entry
    int keep = (settings.maxIncidents ?: 5) as int
    while (hist.size() > keep) {
        Map old = hist.remove(0)
        (old.files ?: []).each { String f ->
            try { deleteHubFile(f) } catch (Exception e) { logWarn "could not delete ${f}: ${e.message}" }
        }
    }
    state.history = hist
}

void updateHistory(String id, Closure change) {
    List<Map> hist = (state.history ?: []) as List<Map>
    Map h = hist.find { it.id == id }
    if (h == null) return
    if (h.files == null) h.files = []
    change(h)
    state.history = hist
}

void notify(String msg) {
    settings.notifyDevices?.each { it.deviceNotification("${location.name}: ${msg}") }
}

// ── Logging (app) ─────────────────────────────────────────────────────
//   ⬇️ Evt  ⬆️ Cmd  🔧 Cfg  🌐 Net  ⏰ Sched  🏷️ Ver  ·  ⚠️ Warn  🛑 Error  🔬 Trace
private String logp(String e) { "${e} ${app.getLabel()}: " }

void logEvt  (String m) { if (debugEnable) log.debug logp('⬇️') + m }
void logCmd  (String m) { if (txtEnable != false) log.info  logp('⬆️') + m }
void logCfg  (String m) { if (txtEnable != false) log.info  logp('🔧') + m }
void logNet  (String m) { if (debugEnable) log.debug logp('🌐') + m }
void logSched(String m) { if (debugEnable) log.debug logp('⏰') + m }
void logVer  (String m) { log.warn  logp('🏷️') + m }

void logWarn (String m) { log.warn  logp('⚠️') + m }
void logError(String m) { log.error logp('🛑') + m }
void logTrace(String m) { if (traceEnable) log.trace logp('🔬') + m }
void logInfo (String m) { if (txtEnable != false) log.info  "${app.getLabel()}: ${m}" }
void logDebug(String m) { if (debugEnable) log.debug "${app.getLabel()}: ${m}" }

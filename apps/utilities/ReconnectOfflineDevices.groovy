// Copyright (c) 2026 PJ
// SPDX-License-Identifier: MIT

/*
 Reconnect Offline Devices

 Runs initialize() on a device whose driver reports networkStatus offline and
 has stayed offline for a set number of minutes, then again at that interval
 until it is back online. Meant for Matter devices: the hub usually reconnects
 a dropped node within minutes, but sometimes stops trying, and only
 initialize() brings the node back.

 Every networkStatus event and every initialize() is appended to a log file in
 File Manager, one per instance.
*/

import groovy.transform.Field

@Field static final String APP_NAME = "Reconnect Offline Devices"
@Field static final String CODE_VERSION = "0.1.0"
@Field static final int LOG_MAX_BYTES = 262144   // past this, the oldest half of the file is dropped

definition(
    name: APP_NAME,
    namespace: "iamtrep",
    author: "pj",
    description: "Runs initialize() on devices that stay offline (networkStatus) longer than a set time.",
    menu: "Automations",
    category: "Convenience",
    singleInstance: false,
    singleThreaded: true, // one writer per instance, so log appends never overlap
    importUrl: "https://raw.githubusercontent.com/iamtrep/hubitat/refs/heads/main/apps/utilities/ReconnectOfflineDevices.groovy",
    documentationLink: "https://github.com/iamtrep/hubitat/tree/main/apps/utilities",
    iconUrl: "",
    iconX2Url: "",
    iconX3Url: ""
)

preferences {
    page(name: "mainPage")
}

Map mainPage() {
    dynamicPage(name: "mainPage", title: "${APP_NAME} v${CODE_VERSION}", install: true, uninstall: true) {
        section("Devices") {
            input name: "devices", type: "capability.initialize", title: "Devices to reconnect",
                  multiple: true, required: true, submitOnChange: true
            input name: "offlineMinutes", type: "number", title: "Run initialize() after this many minutes offline, and again at this interval",
                  range: "5..1440", defaultValue: 30, required: true, submitOnChange: true
        }
        section("Status") {
            paragraph statusReport()
            if (app.id) {
                String err = state.fileLogError ? "<br><span style='color:var(--hubitat-warning-color, #8a5a00)'>Last write failed: ${state.fileLogError}</span>" : ""
                paragraph "<a href='/local/${logFileName()}' target='_blank'>Log file</a> (File Manager: ${logFileName()})${err}"
            }
        }
        section("Notifications") {
            input name: "notificationDevice", type: "capability.notification",
                  title: "Notify on each initialize() and on reconnection", multiple: false, required: false
        }
        section("Options") {
            label title: "App name", required: false
            input name: "txtEnable", type: "bool", title: "Enable info logging", defaultValue: true
            input name: "debugLogging", type: "bool", title: "Enable debug logging", defaultValue: false, submitOnChange: true
            if (settings.debugLogging) {
                input name: "traceEnable", type: "bool", title: "Enable trace logging", defaultValue: false
            }
        }
    }
}

private String statusReport() {
    if (!settings.devices) return "No device selected."
    Map offline = state.offline ?: [:]
    List<String> lines = []
    settings.devices.sort { it.displayName }.each { dev ->
        if (!dev.hasAttribute("networkStatus")) {
            lines << "• ${dev.displayName}: <b>no networkStatus attribute</b>, never reconnected by this app"
            return
        }
        String line = "• ${dev.displayName}: ${dev.currentValue('networkStatus') ?: 'unknown'}"
        Map e = offline[dev.id as String]
        if (e) {
            line += ", offline ${formatAge(e.since as long)}"
            if (e.attempts) line += ", initialize() ×${e.attempts}, last ${formatAge(e.lastTry as long)}"
        }
        lines << line
    }
    lines.join("<br>")
}

private String formatAge(long t) {
    long min = (long) ((now() - t) / 60000L)
    if (min < 60) return "${min} min ago"
    long h = (long) (min / 60)
    if (h < 48) return "${h} h ago"
    "${(long) (h / 24)} d ago"
}

private String formatDuration(long ms) {
    long min = (long) (ms / 60000L)
    if (min < 60) return "${min} min"
    long h = (long) (min / 60)
    if (h < 48) return "${h} h ${min % 60} min"
    "${(long) (h / 24)} d ${h % 24} h"
}

private long delayMs() {
    (settings.offlineMinutes != null ? settings.offlineMinutes as long : 30L) * 60000L
}

void installed() {
    initialize()
}

void updated() {
    unsubscribe()
    unschedule()
    initialize()
    if (settings.debugLogging || settings.traceEnable) runIn(1800, "logsOff")
}

void initialize() {
    checkVersion(false)
    Map offline = state.offline ?: [:]
    Map kept = [:]
    settings.devices?.each { dev ->
        if (!dev.hasAttribute("networkStatus")) {
            logWarn "${dev.displayName} has no networkStatus attribute; ignored"
            return
        }
        String id = dev.id as String
        def cur = dev.currentState("networkStatus")
        if (cur?.value == "offline") {
            kept[id] = offline[id] ?: [since: cur.date.time, attempts: 0, lastTry: null]
        }
    }
    state.offline = kept
    if (settings.devices) subscribe(settings.devices, "networkStatus", "networkStatusHandler")
    logCfg "watching ${settings.devices?.size() ?: 0} device(s), ${kept.size()} offline now"
    List<String> lines = ["config | watching ${settings.devices?.size() ?: 0} device(s), initialize() after ${settings.offlineMinutes ?: 30} min offline"]
    kept.each { String id, Map e ->
        def dev = settings.devices.find { (it.id as String) == id }
        lines << "${devRef(dev)} | offline at setup | since ${formatTs(e.since as long)}"
    }
    fileLog(lines)
    scheduleCheck()
}

private void checkVersion(boolean reinit = true) {
    if (state.version == CODE_VERSION) return
    logVer "New version: ${CODE_VERSION} (was: ${state.version})"
    state.version = CODE_VERSION
    if (reinit) runIn(1, "updated")
}

void networkStatusHandler(evt) {
    checkVersion()
    String id = evt.deviceId as String
    Map offline = state.offline ?: [:]
    logEvt "${evt.device.displayName} networkStatus ${evt.value}"
    fileLog(["${devRef(evt.device)} | networkStatus ${evt.value} | event ${formatTs(evt.date.time)}${offline[id] && evt.value == 'offline' ? ', already offline' : ''}"])
    if (evt.value == "offline") {
        if (offline[id]) return
        offline[id] = [since: evt.date.time, attempts: 0, lastTry: null]
    } else if (evt.value == "online") {
        Map e = offline.remove(id)
        if (!e) return
        String msg = "${evt.device.displayName} back online after ${formatDuration(evt.date.time - (e.since as long))}"
        if (e.attempts) msg += ", ${e.attempts} initialize()"
        logInfo msg
        fileLog(["${devRef(evt.device)} | back online | after ${formatDuration(evt.date.time - (e.since as long))}, ${e.attempts ?: 0} initialize()"])
        if (e.attempts) notify(msg)
    } else {
        return
    }
    state.offline = offline
    scheduleCheck()
}

// Schedules checkOffline() for the earliest device that is due, or clears it when none is offline.
private void scheduleCheck() {
    Map offline = state.offline ?: [:]
    if (!offline) {
        unschedule("checkOffline")
        return
    }
    long due = offline.values().collect { ((it.lastTry ?: it.since) as long) + delayMs() }.min()
    long secs = Math.max(1L, (long) ((due - now()) / 1000L) + 1L)
    logSched "next check in ${secs} s"
    runIn(secs, "checkOffline")
}

void checkOffline() {
    checkVersion()
    Map offline = state.offline ?: [:]
    long t = now()
    settings.devices?.each { dev ->
        String id = dev.id as String
        Map e = offline[id]
        if (!e) return
        if (dev.currentValue("networkStatus") != "offline") {
            offline.remove(id)
            return
        }
        if (((e.lastTry ?: e.since) as long) + delayMs() > t) return
        e.attempts = (e.attempts ?: 0) + 1
        e.lastTry = t
        String msg = "${dev.displayName} offline for ${formatDuration(t - (e.since as long))}: initialize() #${e.attempts}"
        logCmd msg
        notify(msg)
        String outcome = "sent"
        try {
            dev.initialize()
        } catch (Exception ex) {
            logError "initialize() on ${dev.displayName} failed: ${ex.message}"
            outcome = "failed: ${ex.message}"
        }
        fileLog(["${devRef(dev)} | initialize() #${e.attempts} | offline ${formatDuration(t - (e.since as long))}, ${outcome}"])
    }
    // Drop entries for devices no longer selected.
    Set<String> selected = (settings.devices?.collect { it.id as String } ?: []) as Set
    offline.keySet().retainAll(selected)
    state.offline = offline
    scheduleCheck()
}

// ---- File log ----
// File Manager has no append, so the file is read and written back whole. A missing file is
// started fresh; any other read failure skips the write, so a log that can't be read is never
// replaced by a partial one.

private String logFileName() {
    "reconnect_offline_devices_${app.id}.txt"
}

private String devRef(dev) {
    dev ? "${dev.displayName} (${dev.id})" : "unknown device"
}

private String formatTs(long t) {
    new Date(t).format("yyyy-MM-dd HH:mm:ss", location.timeZone)
}

private void fileLog(List<String> lines) {
    if (!lines) return
    String name = logFileName()
    String ts = formatTs(now())
    String existing = ""
    try {
        byte[] bytes = downloadHubFile(name)
        if (bytes) existing = new String(bytes, "UTF-8")
    } catch (Exception e) {
        if (!getObjectClassName(e).endsWith(".NoSuchFileException")) {   // getObjectClassName returns the FQN
            logWarn "log file not written: could not read ${name}: ${e}"
            state.fileLogError = "could not read ${name} (${e.message})"
            return
        }
    }
    if (existing.length() > LOG_MAX_BYTES) {
        int cut = existing.indexOf("\n", (int) (existing.length() / 2))
        existing = cut >= 0 ? existing.substring(cut + 1) : ""
    }
    try {
        uploadHubFile(name, (existing + lines.collect { "${ts} | ${it}" }.join("\n") + "\n").getBytes("UTF-8"))
        state.remove("fileLogError")
    } catch (Exception e) {
        logWarn "log file not written: ${e}"
        state.fileLogError = e.message ?: e.toString()
    }
}

private void notify(String msg) {
    settings.notificationDevice?.deviceNotification("${app.getLabel()}: ${msg}")
}

void logsOff() {
    app.updateSetting("debugLogging", [value: "false", type: "bool"])
    app.updateSetting("traceEnable", [value: "false", type: "bool"])
    logWarn "debug and trace logging disabled"
}

// ── Logging (app) ─────────────────────────────────────────────────────
//   ⬇️ Evt  ⬆️ Cmd  🔧 Cfg  🌐 Net  ⏰ Sched  🏷️ Ver  ·  ⚠️ Warn  🛑 Error  🔬 Trace
private String logp(String e) { "${e} ${app.getLabel()}: " }

void logEvt  (String m) { if (settings.debugLogging) log.debug logp('⬇️') + m }
void logCmd  (String m) { if (txtEnable != false) log.info  logp('⬆️') + m }
void logCfg  (String m) { if (txtEnable != false) log.info  logp('🔧') + m }
void logNet  (String m) { if (settings.debugLogging) log.debug logp('🌐') + m }
void logSched(String m) { if (settings.debugLogging) log.debug logp('⏰') + m }
void logVer  (String m) { log.warn  logp('🏷️') + m }

void logWarn (String m) { log.warn  logp('⚠️') + m }
void logError(String m) { log.error logp('🛑') + m }
void logTrace(String m) { if (settings.traceEnable) log.trace logp('🔬') + m }
void logInfo (String m) { if (txtEnable != false) log.info  "${app.getLabel()}: ${m}" }
void logDebug(String m) { if (settings.debugLogging) log.debug "${app.getLabel()}: ${m}" }

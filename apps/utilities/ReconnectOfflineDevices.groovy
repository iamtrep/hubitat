// Copyright (c) 2026 PJ
// SPDX-License-Identifier: MIT

/*
 Reconnect Offline Devices

 Sends a chosen command (initialize() by default) to a device whose watched
 attribute (networkStatus by default) has held a chosen value (offline by
 default) for a set number of minutes, then, unless set to one attempt, again
 at that interval until the attribute changes. Meant for Matter devices: the
 hub usually reconnects a dropped node within minutes, but sometimes stops
 trying, and only initialize() brings the node back.

 Every event of the watched attribute and every command sent is appended to a
 log file in File Manager, one per instance.
*/

import groovy.transform.Field

@Field static final String APP_NAME = "Reconnect Offline Devices"
@Field static final String CODE_VERSION = "0.2.0"   // 0.2.0 re-subscribes under the new handler name
@Field static final int LOG_MAX_BYTES = 262144   // past this, the oldest half of the file is dropped

definition(
    name: APP_NAME,
    namespace: "iamtrep",
    author: "pj",
    description: "Sends a command, initialize() by default, to devices that keep an attribute value, networkStatus offline by default, longer than a set time.",
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
            input name: "devices", type: "capability.*", title: "Devices to watch",
                  multiple: true, required: true, submitOnChange: true
        }
        section("Condition") {
            List<String> attrs = commonAttributes()
            if (settings.devices && !attrs) {
                paragraph "The selected devices have no attribute in common."
            }
            input name: "watchAttribute", type: "enum", title: "Attribute to watch",
                  options: attrs, defaultValue: attrs.contains("networkStatus") ? "networkStatus" : null,
                  required: true, submitOnChange: true
            List<String> values = attributeValues(watchAttr())
            if (values && settings.watchValue != null && !values.contains(settings.watchValue as String)) {
                app.removeSetting("watchValue")   // left over from another attribute
            }
            String valueDefault = watchAttr() == "networkStatus" ? "offline" : null
            if (values) {
                input name: "watchValue", type: "enum", title: "Value that needs a revival",
                      options: values, defaultValue: values.contains(valueDefault) ? valueDefault : null,
                      required: true, submitOnChange: true
            } else {
                input name: "watchValue", type: "text", title: "Value that needs a revival (exact match)",
                      defaultValue: valueDefault, required: true, submitOnChange: true
            }
            input name: "offlineMinutes", type: "number", title: "Send the command after this many minutes at that value",
                  range: "5..1440", defaultValue: 30, required: true, submitOnChange: true
        }
        section("Revival") {
            List<String> cmds = commonCommands()
            if (settings.devices && !cmds) {
                paragraph "The selected devices have no command without parameters in common."
            }
            input name: "reviveCommand", type: "enum", title: "Command to send",
                  options: cmds, defaultValue: cmds.contains("initialize") ? "initialize" : null,
                  required: true, submitOnChange: true
            input name: "retry", type: "bool", title: "Send it again at this interval until the value changes (off: once per occurrence)",
                  defaultValue: true, submitOnChange: true
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
                  title: "Notify on each command sent and on recovery", multiple: false, required: false
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
    String attr = watchAttr()
    List<String> lines = []
    settings.devices.sort { it.displayName }.each { dev ->
        if (!dev.hasAttribute(attr)) {
            lines << "• ${dev.displayName}: <b>no ${attr} attribute</b>, never revived by this app"
            return
        }
        String line = "• ${dev.displayName}: ${attr} ${dev.currentState(attr)?.value ?: 'unknown'}"
        Map e = offline[dev.id as String]
        if (e) {
            line += " since ${formatAge(e.since as long)}"
            if (e.attempts) line += ", ${reviveCommand()}() ×${e.attempts}, last ${formatAge(e.lastTry as long)}"
            if (dueAt(e) == null) line += ", no more attempts until the value changes"
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

private String watchAttr() {
    settings.watchAttribute ?: "networkStatus"
}

// The value that needs a revival; null when none is set for an attribute other than networkStatus.
private String watchValue() {
    if (settings.watchValue != null) return settings.watchValue as String
    watchAttr() == "networkStatus" ? "offline" : null
}

private String reviveCommand() {
    settings.reviveCommand ?: "initialize"
}

private boolean retryEnabled() {
    settings.retry != false
}

// When the next command is due for a watched entry, or null when one-shot and already sent.
private Long dueAt(Map e) {
    if (!retryEnabled() && (e.attempts ?: 0) >= 1) return null
    ((e.lastTry ?: e.since) as long) + delayMs()
}

// Names of the commands without parameters that every selected device supports.
private List<String> commonCommands() {
    List<Set<String>> perDevice = settings.devices?.collect { dev ->
        dev.supportedCommands.findAll { !it.arguments }*.name as Set<String>
    } ?: []
    if (!perDevice) return []
    perDevice.inject(perDevice[0]) { Set<String> acc, Set<String> s -> acc.intersect(s) as Set<String> }.sort()
}

// Names of the attributes every selected device has.
private List<String> commonAttributes() {
    List<Set<String>> perDevice = settings.devices?.collect { dev -> dev.supportedAttributes*.name as Set<String> } ?: []
    if (!perDevice) return []
    perDevice.inject(perDevice[0]) { Set<String> acc, Set<String> s -> acc.intersect(s) as Set<String> }.sort()
}

// The enum values of an attribute shared by every selected device, or [] when any of them has
// no fixed list (number, string) for it.
private List<String> attributeValues(String attr) {
    List<Set<String>> perDevice = settings.devices?.collect { dev ->
        def a = dev.supportedAttributes.find { it.name == attr }
        (a?.values ?: []) as Set<String>
    } ?: []
    if (!perDevice || perDevice.any { !it }) return []
    perDevice.inject(perDevice[0]) { Set<String> acc, Set<String> s -> acc.intersect(s) as Set<String> }.sort()
}

private boolean supportsCommand(dev, String cmd) {
    dev.supportedCommands.any { it.name == cmd && !it.arguments }
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
    String attr = watchAttr()
    String match = watchValue()
    if (match == null) logWarn "no value set for ${attr}; nothing is watched"
    settings.devices?.each { dev ->
        if (!dev.hasAttribute(attr)) {
            logWarn "${dev.displayName} has no ${attr} attribute; ignored"
            return
        }
        if (!supportsCommand(dev, reviveCommand())) logWarn "${dev.displayName} has no ${reviveCommand()}() command without parameters"
        String id = dev.id as String
        def cur = dev.currentState(attr)
        if (match != null && cur?.value == match) {
            kept[id] = offline[id] ?: [since: cur.date.time, attempts: 0, lastTry: null]
        }
    }
    state.offline = kept
    if (settings.devices && match != null) subscribe(settings.devices, attr, "watchHandler")
    logCfg "watching ${settings.devices?.size() ?: 0} device(s) for ${attr} ${match}, ${kept.size()} at it now"
    String when = retryEnabled() ? "every ${settings.offlineMinutes ?: 30} min" : "once, after ${settings.offlineMinutes ?: 30} min"
    List<String> lines = ["config | watching ${settings.devices?.size() ?: 0} device(s), ${reviveCommand()}() ${when} at ${attr} ${match}"]
    kept.each { String id, Map e ->
        def dev = settings.devices.find { (it.id as String) == id }
        lines << "${devRef(dev)} | ${attr} ${match} at setup | since ${formatTs(e.since as long)}"
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

// Subscriptions made before 0.2.0 call this name; its version check re-subscribes them.
void networkStatusHandler(evt) {
    watchHandler(evt)
}

void watchHandler(evt) {
    checkVersion()
    if (evt.name != watchAttr()) return
    String id = evt.deviceId as String
    Map offline = state.offline ?: [:]
    boolean matches = evt.value == watchValue()
    logEvt "${evt.device.displayName} ${evt.name} ${evt.value}"
    fileLog(["${devRef(evt.device)} | ${evt.name} ${evt.value} | event ${formatTs(evt.date.time)}${offline[id] && matches ? ', already at it' : ''}"])
    if (matches) {
        if (offline[id]) return
        offline[id] = [since: evt.date.time, attempts: 0, lastTry: null]
    } else {
        Map e = offline.remove(id)
        if (!e) return
        String msg = "${evt.device.displayName} ${evt.name} ${evt.value} after ${formatDuration(evt.date.time - (e.since as long))} at ${watchValue()}"
        if (e.attempts) msg += ", ${e.attempts} × ${reviveCommand()}()"
        logInfo msg
        fileLog(["${devRef(evt.device)} | recovered | ${evt.name} ${evt.value} after ${formatDuration(evt.date.time - (e.since as long))}, ${e.attempts ?: 0} × ${reviveCommand()}()"])
        if (e.attempts) notify(msg)
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
    List<Long> dues = offline.values().collect { dueAt(it as Map) }.findAll { it != null }
    if (!dues) {
        unschedule("checkOffline")
        return
    }
    long due = dues.min()
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
        if (dev.currentState(watchAttr())?.value != watchValue()) {
            offline.remove(id)
            return
        }
        Long due = dueAt(e)
        if (due == null || due > t) return
        String cmd = reviveCommand()
        e.attempts = (e.attempts ?: 0) + 1
        e.lastTry = t
        String msg = "${dev.displayName} ${watchAttr()} ${watchValue()} for ${formatDuration(t - (e.since as long))}: ${cmd}() #${e.attempts}"
        logCmd msg
        notify(msg)
        String outcome = "sent"
        if (!supportsCommand(dev, cmd)) {
            logWarn "${dev.displayName} has no ${cmd}() command without parameters"
            outcome = "not sent: no such command"
        } else {
            try {
                dev."${cmd}"()
            } catch (Exception ex) {
                logError "${cmd}() on ${dev.displayName} failed: ${ex.message}"
                outcome = "failed: ${ex.message}"
            }
        }
        fileLog(["${devRef(dev)} | ${cmd}() #${e.attempts} | ${watchAttr()} ${watchValue()} for ${formatDuration(t - (e.since as long))}, ${outcome}"])
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

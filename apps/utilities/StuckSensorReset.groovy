// Copyright (c) 2026 PJ
// SPDX-License-Identifier: MIT

/*
 Stuck Sensor Reset

 Returns motion and contact sensors that are stuck active or open (typically
 after a battery died mid-event) to inactive or closed, so they stop blocking
 automations. A sensor counts as stuck only when it is active/open and has been
 silent longer than the configured threshold, or its driver reports healthStatus
 offline. A sensor that is open or active but still reporting is left alone: the
 door may really be open.

 The reset injects an event via DeviceWrapper.sendEvent with type "digital", so
 subscribers can tell it from a real report. No radio traffic is sent. An
 optional daily check notifies about stuck sensors without resetting them, so a
 dead battery is not hidden.
*/

import groovy.transform.Field

@Field static final String APP_NAME = "Stuck Sensor Reset"
@Field static final String CODE_VERSION = "1.0.0"

@Field static final Map<String, Map> SENSOR_TYPES = [
    motion : [setting: "motionSensors",  attr: "motion",  stuck: "active", reset: "inactive"],
    contact: [setting: "contactSensors", attr: "contact", stuck: "open",   reset: "closed"],
]

definition(
    name: APP_NAME,
    namespace: "iamtrep",
    author: "pj",
    description: "Resets motion and contact sensors stuck active or open after going silent, e.g. on a dead battery.",
    menu: "Automations",
    category: "Convenience",
    singleInstance: false,
    importUrl: "https://raw.githubusercontent.com/iamtrep/hubitat/refs/heads/main/apps/utilities/StuckSensorReset.groovy",
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
        section("Sensors") {
            input name: "motionSensors", type: "capability.motionSensor", title: "Motion sensors",
                  multiple: true, required: false, submitOnChange: true
            input name: "contactSensors", type: "capability.contactSensor", title: "Contact sensors",
                  multiple: true, required: false, submitOnChange: true
            input name: "staleHours", type: "number", title: "Stuck after this many hours without activity",
                  range: "1..720", defaultValue: 6, required: true, submitOnChange: true
        }
        section("Status") {
            paragraph statusReport()
            input name: "btnReset", type: "button", title: "Reset stuck sensors now"
        }
        section("Triggers") {
            input name: "triggerSwitch", type: "capability.switch", title: "Switch that triggers a reset (turned back off)",
                  multiple: false, required: false
            input name: "notificationDevice", type: "capability.notification", title: "Notification device",
                  multiple: false, required: false, submitOnChange: true
            if (settings.notificationDevice) {
                input name: "checkTime", type: "time", title: "Daily stuck-sensor check (notifies, never resets)",
                      required: false
            }
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
    List<Map> all = activeSensors()
    if (!all) return "No selected sensor is active or open."
    List<Map> stuck = all.findAll { it.stuck }
    List<Map> live = all.findAll { !it.stuck }
    List<String> lines = []
    if (stuck) {
        lines << "<b>Stuck (will be reset):</b>"
        stuck.each { lines << "• ${describe(it)}" }
    }
    if (live) {
        lines << "<b>Active or open but still reporting (left alone):</b>"
        live.each { lines << "• ${describe(it)}" }
    }
    lines.join("<br>")
}

private String describe(Map s) {
    String why = s.offline ? ", offline" : ""
    "${s.dev.displayName} (${s.attr} ${s.value}, last activity ${formatAge(s.lastActivity)}${why})"
}

// Every selected sensor in its stuck value, with whether it qualifies as stuck.
private List<Map> activeSensors() {
    long hours = settings.staleHours != null ? settings.staleHours as long : 6L
    long cutoff = now() - hours * 3600000L
    List<Map> result = []
    SENSOR_TYPES.each { String kind, Map t ->
        settings[t.setting]?.each { dev ->
            String value = dev.currentValue(t.attr)
            if (value != t.stuck) return
            Date last = dev.getLastActivity()
            boolean offline = dev.currentValue("healthStatus") == "offline"
            boolean stale = last == null || last.time < cutoff
            result << [dev: dev, attr: t.attr, value: value, reset: t.reset,
                       lastActivity: last, offline: offline, stuck: offline || stale]
        }
    }
    result
}

private String formatAge(Date d) {
    if (d == null) return "never"
    long min = (long) ((new Date().time - d.time) / 60000L)
    if (min < 60) return "${min} min ago"
    long h = (long) (min / 60)
    if (h < 48) return "${h} h ago"
    "${(long) (h / 24)} d ago"
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
    if (!settings.motionSensors && !settings.contactSensors) logWarn "no sensors selected"
    if (settings.triggerSwitch) subscribe(settings.triggerSwitch, "switch.on", "switchOnHandler")
    if (settings.notificationDevice && settings.checkTime) {
        schedule(toDateTime(settings.checkTime), "dailyCheck")
        logCfg "daily check at ${settings.checkTime}"
    }
}

private void checkVersion(boolean reinit = true) {
    if (state.version == CODE_VERSION) return
    logVer "New version: ${CODE_VERSION} (was: ${state.version})"
    state.version = CODE_VERSION
    if (reinit) runIn(1, "updated")
}

void appButtonHandler(String btn) {
    checkVersion()
    switch (btn) {
        case "btnReset": logCmd "manual reset"; resetStuck(); break
        default: logWarn "unknown button '${btn}'"
    }
}

void switchOnHandler(evt) {
    checkVersion()
    logEvt "reset triggered by ${evt.device.displayName}"
    resetStuck()
    runIn(1, "switchOff")
}

void switchOff() {
    settings.triggerSwitch?.off()
}

void dailyCheck() {
    checkVersion()
    List<Map> stuck = activeSensors().findAll { it.stuck }
    logSched "daily check: ${stuck.size()} stuck"
    if (stuck) notify("${app.getLabel()}: ${stuck.size()} sensor(s) look stuck\n" + stuck.collect { describe(it) }.join("\n"))
}

private void resetStuck() {
    List<String> done = []
    activeSensors().findAll { it.stuck }.each { Map s ->
        try {
            s.dev.sendEvent(name: s.attr, value: s.reset, type: "digital",
                            descriptionText: "${s.dev.displayName} ${s.attr} reset to ${s.reset} by ${app.getLabel()}")
            done << describe(s)
            logCmd "${s.dev.displayName} ${s.attr} -> ${s.reset}"
        } catch (Exception e) {
            logError "failed to reset ${s.dev.displayName}: ${e.message}"
        }
    }
    logInfo "reset ${done.size()} sensor(s)"
    if (done) notify("${app.getLabel()}: reset ${done.size()} sensor(s)\n" + done.join("\n"))
}

private void notify(String msg) {
    settings.notificationDevice?.deviceNotification(msg)
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

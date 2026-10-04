// Copyright (c) 2026 PJ
// SPDX-License-Identifier: MIT

/*
 HVAC Interlock

 Gates heating and cooling equipment by season and by open windows. Holds the Season
 device and the alert notification setting; one child app per equipment group.
 Not single-threaded: groups call back into it while it forwards a season change.
*/

import groovy.transform.Field

@Field static final String CODE_VERSION = "0.1.0"

definition(
    name: "HVAC Interlock",
    namespace: "iamtrep",
    author: "pj",
    description: "Gates heating and cooling equipment by season and open windows",
    menu: "Automations", // new in platform 2.5.0
    category: "Convenience",
    importUrl: "https://raw.githubusercontent.com/iamtrep/hubitat/main/apps/HvacInterlock/HvacInterlock.groovy",
    iconUrl: "", iconX2Url: ""
)

preferences {
    page(name: "mainPage")
}

String esc(Object s) { s == null ? '' : s.toString().replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;').replace("'", '&#39;') }

Map mainPage() {
    dynamicPage(name: "mainPage", title: "HVAC Interlock", install: true, uninstall: true) {
        section {
            input "seasonDevice", "capability.actuator", title: "Season device (the one Season Manager created)", required: true, submitOnChange: true
            if (seasonDevice && !seasonDevice.hasAttribute("season"))
                paragraph "<div class='p-message p-message-error p-3 border-round'>${esc(seasonDevice.displayName)} has no season attribute. Pick the device Season Manager created.</div>"
            else if (seasonDevice) paragraph "Season now: ${esc(seasonDevice.currentValue('season') ?: 'not set')}"
        }
        section("Groups") {
            app(name: "groups", appName: "HVAC Interlock Group", namespace: "iamtrep", title: "Add a group", multiple: true)
        }
        section("Openings alerts") {
            input "notifyDevices", "capability.notification", title: "Send to", multiple: true, required: false
            input "speechDevices", "capability.speechSynthesis", title: "Speak on", multiple: true, required: false
            paragraph "Sent when a group raises its openings alert (\"<group>: <open contacts> open\") and when it clears."
        }
        section("Logging") {
            input "txtEnable", "bool", title: "Enable info logging", defaultValue: true
            input "debugEnable", "bool", title: "Enable debug logging (turns off after 30 minutes)", defaultValue: false, submitOnChange: true
        }
        section { label title: "App name", required: false }
    }
}

void installed() { checkVersion(false); initialize() }

void updated() { checkVersion(false); unsubscribe(); unschedule(); initialize() }

void initialize() {
    if (seasonDevice) subscribe(seasonDevice, "season", "seasonHandler")
    if (debugEnable) runIn(1800, "logsOff")
    String s = currentSeason()
    if (s) getChildApps().each { it.seasonChanged(s) }
}

void logsOff() { checkVersion(); app.updateSetting("debugEnable", false); logWarn "debug logging disabled" }

void checkVersion(boolean reinit = true) {
    if (state.version == CODE_VERSION) return
    logVer "version ${CODE_VERSION} (was ${state.version})"
    state.version = CODE_VERSION
    if (reinit) runIn(1, "updated")
}

String currentSeason() { return seasonDevice?.currentValue("season") as String }

void seasonHandler(evt) {
    checkVersion()
    logEvt "season ${evt.value}"
    getChildApps().each { it.seasonChanged(evt.value as String) }
}

void notifyAlert(String text) {
    notifyDevices?.each { it.deviceNotification(text) }
    speechDevices?.each { it.speak(text) }
    if (notifyDevices || speechDevices) logCmd "sent: ${text}"
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

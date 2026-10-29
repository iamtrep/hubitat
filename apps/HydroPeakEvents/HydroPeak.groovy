// Copyright (c) 2026 PJ
// SPDX-License-Identifier: MIT

/*
 Hydro Peak

 Component device of Hydro-Québec Peak Period Manager. Publishes the next peak event's phase,
 window and times, and forwards skip, unskip, abort and refresh to the app.
*/

import groovy.transform.Field

@Field static final String CODE_VERSION = "0.4.0"

metadata {
    definition(name: "Hydro Peak", namespace: "iamtrep", author: "pj", component: true,
               importUrl: "https://raw.githubusercontent.com/iamtrep/hubitat/main/apps/HydroPeakEvents/HydroPeak.groovy") {
        capability "Actuator"
        capability "Refresh"
        attribute "phase", "enum", ["none", "announced", "anchor", "gap", "active", "skipped", "aborted"]
        attribute "window", "enum", ["AM", "PM", "none"]
        attribute "eventDate", "string"
        attribute "eventStart", "string"
        attribute "eventEnd", "string"
        attribute "anchorStart", "string"
        attribute "anchorEnd", "string"
        attribute "offer", "string"
        command "skip"
        command "unskip"
        command "abort"
    }
    preferences {
        input name: "txtEnable", type: "bool", title: "Enable info logging", defaultValue: true
        input name: "debugEnable", type: "bool", title: "Enable debug logging (turns off after 30 minutes)", defaultValue: false
    }
}

void installed() { logCfg "installed ${CODE_VERSION}" }
void updated() {
    logCfg "updated"
    if (debugEnable) runIn(1800, "logsOff")
}

void logsOff() {
    device.updateSetting("debugEnable", [value: "false", type: "bool"])
    logWarn "debug logging disabled"
}

void skip() { forward([command: 'skip']) }
void unskip() { forward([command: 'unskip']) }
void abort() { forward([command: 'abort']) }
void refresh() { forward([command: 'refresh']) }

private void forward(Map req) {
    Map res = parent.peakCommand(req) as Map
    if (res?.ok == false) logWarn "${req.command}: ${res.error}"
    else logCmd "${req.command}"
}

void updateStatus(Map attrs) {
    if (state.version != CODE_VERSION) {
        logVer "version ${CODE_VERSION} (was ${state.version})"
        state.version = CODE_VERSION
    }
    attrs.each { String k, v ->
        if (v == null || device.currentValue(k) == v) return
        sendEvent(name: k, value: v, descriptionText: k == "phase" ? "${device.displayName} phase is ${v}" : null)
        if (k == "phase") logInfo "phase is ${v}"
    }
}

// ── Logging ───────────────────────────────────────────────────────────
//   ⬇️ Rx  ⬆️ Cmd  🔧 Cfg  🌐 Net  ⏰ Sched  📦 Ota  🏷️ Ver  ·  ⚠️ Warn  🛑 Error  🔬 Trace
private String logp(String e) { "${e} ${device.displayName}: " }

void logCmd  (String m) { if (txtEnable != false) log.info  logp('⬆️') + m }
void logCfg  (String m) { if (txtEnable != false) log.info  logp('🔧') + m }
void logVer  (String m) { log.warn  logp('🏷️') + m }

void logWarn (String m) { log.warn  logp('⚠️') + m }
void logInfo (String m) { if (txtEnable != false) log.info  "${device.displayName}: ${m}" }
void logDebug(String m) { if (debugEnable) log.debug "${device.displayName}: ${m}" }

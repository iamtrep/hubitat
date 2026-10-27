// Copyright (c) 2026 PJ
// SPDX-License-Identifier: MIT

/*
 Power State

 Component device of Power State Monitor. Publishes the load's operating state and,
 while it is in the state a switch commands, whether that switch is on.
*/

import groovy.transform.Field

@Field static final String CODE_VERSION = "0.1.0"

metadata {
    definition(name: "Power State", namespace: "iamtrep", author: "pj", component: true,
               importUrl: "https://raw.githubusercontent.com/iamtrep/hubitat/main/apps/PowerStateMonitor/PowerStateMonitorState.groovy") {
        capability "Sensor"
        attribute "powerState", "string"
        attribute "stateSource", "enum", ["switch", "other", "none"]
    }
    preferences {
        input name: "txtEnable", type: "bool", title: "Enable info logging", defaultValue: true
        input name: "debugEnable", type: "bool", title: "Enable debug logging (turns off after 30 minutes)", defaultValue: false
        if (debugEnable) {
            input name: "traceEnable", type: "bool", title: "Enable trace logging", defaultValue: false
        }
    }
}

void installed() { logCfg "installed ${CODE_VERSION}" }
void updated() {
    logCfg "updated"
    if (debugEnable || traceEnable) runIn(1800, "logsOff")
}

void logsOff() {
    device.updateSetting("debugEnable", [value: "false", type: "bool"])
    device.updateSetting("traceEnable", [value: "false", type: "bool"])
    logWarn "debug and trace logging disabled"
}
void deviceTypeUpdated() { logDebug "driver change detected" }

void updateStatus(Map attrs) {
    if (state.version != CODE_VERSION) {
        logVer "version ${CODE_VERSION} (was ${state.version})"
        state.version = CODE_VERSION
    }
    attrs.each { String k, v -> if (v != null) sendEvent(name: k, value: v) }
}

// ── Logging ───────────────────────────────────────────────────────────
//   ⬇️ Rx  ⬆️ Cmd  🔧 Cfg  🌐 Net  ⏰ Sched  📦 Ota  🏷️ Ver  ·  ⚠️ Warn  🛑 Error  🔬 Trace
private String logp(String e) { "${e} ${device.displayName}: " }

void logRx   (String m) { if (debugEnable) log.debug logp('⬇️') + m }
void logCmd  (String m) { if (txtEnable != false) log.info  logp('⬆️') + m }
void logCfg  (String m) { if (txtEnable != false) log.info  logp('🔧') + m }
void logNet  (String m) { if (debugEnable) log.debug logp('🌐') + m }
void logSched(String m) { if (debugEnable) log.debug logp('⏰') + m }
void logOta  (String m) { if (txtEnable != false) log.info  logp('📦') + m }
void logVer  (String m) { log.warn  logp('🏷️') + m }

void logWarn (String m) { log.warn  logp('⚠️') + m }
void logError(String m) { log.error logp('🛑') + m }
void logTrace(String m) { if (traceEnable) log.trace logp('🔬') + m }
void logInfo (String m) { if (txtEnable != false) log.info  "${device.displayName}: ${m}" }
void logDebug(String m) { if (debugEnable) log.debug "${device.displayName}: ${m}" }

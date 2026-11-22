// Copyright (c) 2026 PJ
// SPDX-License-Identifier: MIT

/*
 Load Shedder Status

 Component device of a Load Shedder instance. switch: on while the instance sheds (from the
 first reason until the last load is restored). contact: open while a load does not obey.
 reasons: what holds. shedLoads: the loads the instance holds off. The app is the only writer.
*/

import groovy.transform.Field

@Field static final String CODE_VERSION = "0.1.0"

metadata {
    definition(name: "Load Shedder Status", namespace: "iamtrep", author: "pj", component: true,
               importUrl: "https://raw.githubusercontent.com/iamtrep/hubitat/main/apps/LoadShedder/LoadShedderStatus.groovy") {
        capability "Actuator"
        capability "Switch"
        capability "ContactSensor"
        attribute "reasons", "string"
        attribute "shedLoads", "string"
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

void deviceTypeUpdated() { logDebug "driver change detected" }

void on() { logWarn "the app sets this switch; change its reasons instead" }
void off() { logWarn "the app sets this switch; change its reasons instead" }

void updateStatus(Map attrs) {
    if (state.version != CODE_VERSION) {
        logVer "version ${CODE_VERSION} (was ${state.version})"
        state.version = CODE_VERSION
    }
    attrs.each { String k, v -> if (v != null) sendEvent(name: k, value: v) }
}

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

void logsOff() {
    device.updateSetting("debugEnable", [value: "false", type: "bool"])
    device.updateSetting("traceEnable", [value: "false", type: "bool"])
    logWarn "debug and trace logging disabled"
}

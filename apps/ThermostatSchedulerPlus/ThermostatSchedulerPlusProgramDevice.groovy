// Copyright (c) 2026 PJ
// SPDX-License-Identifier: MIT

/*
 Thermostat Scheduler+ Program Device

 Component device of a Thermostat Scheduler+ program. Publishes what the program is
 doing and forwards commands to it. switch: on = running, off = paused.
*/

import groovy.transform.Field

@Field static final String CODE_VERSION = "0.3.2"

metadata {
    definition(name: "Thermostat Scheduler+ Program Device", namespace: "iamtrep", author: "pj", component: true,
               importUrl: "https://raw.githubusercontent.com/iamtrep/hubitat/main/apps/ThermostatSchedulerPlus/ThermostatSchedulerPlusProgramDevice.groovy") {
        capability "Actuator"
        capability "Switch"
        capability "Refresh"
        attribute "status", "string"
        attribute "schedule", "string"
        attribute "profile", "string"
        attribute "heatingTarget", "number"
        attribute "coolingTarget", "number"
        attribute "holdEnd", "string"
        attribute "nextTransition", "string"
        attribute "nextProfile", "string"
        attribute "eco", "enum", ["on", "off"]
        attribute "ecoOffset", "number"
        attribute "lastApply", "string"
        command "resume"
        command "applyNow"
        command "advance"
        command "holdProfile", [[name: "profile*", type: "STRING"], [name: "end", type: "STRING", description: "next, indefinite, minutes or ISO time"]]
        command "holdSetpoints", [[name: "heating", type: "NUMBER"], [name: "cooling", type: "NUMBER"], [name: "end", type: "STRING", description: "next, indefinite, minutes or ISO time"]]
        command "setSchedule", [[name: "schedule*", type: "STRING"]]
        command "setEco", [[name: "state*", type: "ENUM", constraints: ["on", "off"]]]
        command "setEcoOffset", [[name: "offset*", type: "NUMBER"]]
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

void on()                      { forward([command: 'on']) }
void off()                     { forward([command: 'off']) }
void refresh()                 { forward([command: 'refresh']) }
void resume()                  { forward([command: 'resume']) }
void applyNow()                { forward([command: 'applyNow']) }
void advance()                 { forward([command: 'advance']) }
void holdProfile(String profile, String end = null)               { forward([command: 'holdProfile', profile: profile, end: end]) }
void holdSetpoints(heating = null, cooling = null, String end = null) { forward([command: 'holdSetpoints', heating: heating, cooling: cooling, end: end]) }
void setSchedule(String schedule) { forward([command: 'setSchedule', schedule: schedule]) }
void setEco(String state)         { forward([command: 'setEco', state: state]) }
void setEcoOffset(offset)         { forward([command: 'setEcoOffset', offset: offset]) }

private void forward(Map req) {
    Map res = parent.deviceCommand(req) as Map
    if (res?.ok == false) logWarn "${req.command}: ${res.error}"
    else logCmd "${req.command}"
}

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

// Copyright (c) 2026 PJ
// SPDX-License-Identifier: MIT

/*
 Indoor Air

 Component device of Indoor Air Quality Controller. Publishes CO2, humidity, the
 ventilation stage and both advisories. Its switch turns the app on and off.
*/

import groovy.transform.Field

@Field static final String CODE_VERSION = "0.1.2"
@Field static final Map UNITS = [carbonDioxide: "ppm", humidity: "%"]

metadata {
    definition(name: "Indoor Air", namespace: "iamtrep", author: "pj", component: true,
               importUrl: "https://raw.githubusercontent.com/iamtrep/hubitat/main/apps/IndoorAirQualityController/IndoorAirQualityControllerAir.groovy") {
        capability "Actuator"
        capability "Sensor"
        capability "Switch"
        capability "CarbonDioxideMeasurement"
        capability "RelativeHumidityMeasurement"
        attribute "ventilationStage", "number"
        attribute "windowAdvisory", "enum", ["active", "inactive"]
        attribute "lowHumidityAdvisory", "enum", ["active", "inactive"]
    }
    preferences {
        input name: "txtEnable", type: "bool", title: "Enable info logging", defaultValue: true
    }
}

void installed() { logCfg "installed ${CODE_VERSION}" }
void updated() { logCfg "updated" }
void deviceTypeUpdated() { logDebug "driver change detected" }

void on() { forward("on") }
void off() { forward("off") }

private void forward(String cmd) {
    Map res = parent.airCommand([command: cmd]) as Map
    if (res?.ok == false) logWarn "${cmd}: ${res.error}"
    else logCmd cmd
}

void updateStatus(Map attrs) {
    if (state.version != CODE_VERSION) {
        logVer "version ${CODE_VERSION} (was ${state.version})"
        state.version = CODE_VERSION
    }
    attrs.each { String k, v -> if (v != null) sendEvent(name: k, value: v, unit: UNITS[k]) }
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

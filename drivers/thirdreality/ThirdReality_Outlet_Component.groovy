// Copyright (c) 2026 PJ
// SPDX-License-Identifier: MIT

/*
 *  Third Reality Outlet (Component) — child driver for one outlet of the
 *  Third Reality Dual Smart Plug (3RDP01072Z). The parent owns all Zigbee I/O;
 *  this child only relays commands up and displays events pushed down via parse().
 *
 *  Unlike the stock "Generic Component Metering Switch", this carries CurrentMeter and a
 *  powerFactor attribute for the parent's per-outlet current and power factor.
 *  Voltage and frequency are shared line measurements and live on the parent.
 */

metadata {
    definition(
        name: "Third Reality Outlet (Component)",
        namespace: "iamtrep",
        author: "pj",
        description: "Component child for one outlet of the Third Reality dual plug",
        importUrl: "https://raw.githubusercontent.com/iamtrep/hubitat/refs/heads/main/drivers/thirdreality/ThirdReality_Outlet_Component.groovy"
    ) {
        capability "Actuator"
        capability "Switch"
        capability "Outlet"
        capability "PowerMeter"
        capability "EnergyMeter"
        capability "CurrentMeter"
        capability "Refresh"

        attribute "powerFactor", "number"
    }
    preferences {
        input(name: "txtEnable", type: "bool", title: "<b>Enable info logging</b>", defaultValue: true)
        input(name: "debugEnable", type: "bool", title: "<b>Enable debug logging</b>", defaultValue: false, submitOnChange: true)
        if (debugEnable) {
            input(name: "traceEnable", type: "bool", title: "<b>Enable trace logging</b>", defaultValue: false)
        }
    }
}

void installed() {
    sendEvent(name: "switch", value: "off")
}

void updated() {
    logCfg "updated"
    if (debugEnable || traceEnable) runIn(1800, "logsOff")
}

void logsOff() {
    logWarn "debug and trace logging disabled"
    device.updateSetting("debugEnable", [value: "false", type: "bool"])
    device.updateSetting("traceEnable", [value: "false", type: "bool"])
}

// Events arrive from the parent as a list of attribute maps.
void parse(List<Map> events) {
    events.each { Map evt ->
        if (evt == null) return
        logTrace "parse: ${evt}"
        if (txtEnable && evt.descriptionText) log.info "${evt.descriptionText}"
        sendEvent(evt)
    }
}

void on()      { parent?.componentOn(this.device) }
void off()     { parent?.componentOff(this.device) }
void refresh() { parent?.componentRefresh(this.device) }

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

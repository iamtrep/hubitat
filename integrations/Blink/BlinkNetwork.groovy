// Copyright (c) 2025-2026 PJ
// SPDX-License-Identifier: MIT

/*
 * Blink Network — Child Driver
 *
 * One device per Blink network (≈ one per sync module). on()=arm, off()=disarm.
 * All API calls go through the parent Blink Manager app.
 */

import groovy.transform.Field

@Field static final String CODE_VERSION = "0.2.0"

metadata {
    definition(
        name: "Blink Network",
        namespace: "iamtrep",
        author: "pj",
        description: "Arm/disarm a Blink network. Reports sync-module online + firmware.",
        importUrl: "https://raw.githubusercontent.com/iamtrep/hubitat/refs/heads/main/integrations/Blink/BlinkNetwork.groovy"
    ) {
        capability "Switch"
        capability "Refresh"

        attribute "online", "string"
        attribute "healthStatus", "enum", ["online", "offline"]
        attribute "firmwareVersion", "string"
        attribute "syncModuleSerial", "string"
        attribute "cameraCount", "number"
    }
}

@Field static final int DEBUG_LOG_TIMEOUT = 1800

preferences {
    section("Logging") {
        input name: "txtEnable", type: "bool", title: "Enable info logging", defaultValue: true
        input name: "debugEnable", type: "bool", title: "Enable debug logging", defaultValue: false, submitOnChange: true
        if (debugEnable) {
            input name: "traceEnable", type: "bool", title: "Enable trace logging", defaultValue: false
        }
    }
}

void installed() {
    logDebug "installed"
}

void deviceTypeUpdated() {
    logDebug "driver change detected"
}

void updated() {
    if (debugEnable || traceEnable) runIn(DEBUG_LOG_TIMEOUT, "turnOffDebugLogging")
}

void on() {
    String networkId = device.getDataValue("networkId")
    if (!networkId) {
        logError "no networkId on this device"
        return
    }
    parent.arm(networkId)
}

void off() {
    String networkId = device.getDataValue("networkId")
    if (!networkId) {
        logError "no networkId on this device"
        return
    }
    parent.disarm(networkId)
}

void refresh() {
    String networkId = device.getDataValue("networkId")
    if (networkId) parent.refreshNetwork(networkId)
}

// Parent-fed child: nothing to reconfigure, so a push is only recorded.
private void checkVersion() {
    if (state.version == CODE_VERSION) return
    logVer "New version: ${CODE_VERSION} (was: ${state.version})"
    state.version = CODE_VERSION
}

// Called by the parent after each poll: offline while the Blink cloud is
// unreachable or Blink reports the device offline. The reason goes in the event description.
void updateHealth(String status, String reason = null) {
    String prev = device.currentValue("healthStatus")
    String why = reason ? ": ${reason}" : ""
    sendEvent(name: "healthStatus", value: status, descriptionText: "${device.displayName} is ${status}${why}")
    if (prev != null && prev != status) {
        if (status == "offline") logWarn "offline${why}"
        else logInfo "back online"
    }
}

// Called by the parent on each poll with the latest network + sync module state.
void handleNetworkUpdate(Map data) {
    checkVersion()
    if (!data) return
    logTrace "handleNetworkUpdate: ${data}"

    if (data.containsKey("armed")) {
        boolean armed = data.armed as boolean
        String value = armed ? "on" : "off"
        sendEvent(name: "switch", value: value, descriptionText: "Network is ${armed ? 'armed' : 'disarmed'}")
        if (txtEnable) logInfo "${armed ? 'armed' : 'disarmed'}"
    }
    if (data.containsKey("online")) {
        sendEvent(name: "online", value: data.online?.toString() ?: "unknown")
    }
    if (data.firmwareVersion) {
        sendEvent(name: "firmwareVersion", value: data.firmwareVersion.toString())
    }
    if (data.syncModuleSerial) {
        sendEvent(name: "syncModuleSerial", value: data.syncModuleSerial.toString())
    }
    if (data.containsKey("cameraCount")) {
        sendEvent(name: "cameraCount", value: data.cameraCount as int)
    }
}

void turnOffDebugLogging() {
    logWarn "debug logging disabled"
    device.updateSetting("debugEnable", [value: "false", type: "bool"])
    device.updateSetting("traceEnable", [value: "false", type: "bool"])
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

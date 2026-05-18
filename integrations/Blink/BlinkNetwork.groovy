// Copyright (c) 2025-2026 PJ
// SPDX-License-Identifier: MIT

/*
 * Blink Network — Child Driver
 *
 * One device per Blink network (≈ one per sync module). on()=arm, off()=disarm.
 * All API calls go through the parent Blink Manager app.
 */

import groovy.transform.Field

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
        attribute "firmwareVersion", "string"
        attribute "syncModuleSerial", "string"
        attribute "cameraCount", "number"
    }
}

@Field static final int DEBUG_LOG_TIMEOUT = 1800

preferences {
    section("Logging") {
        input name: "txtEnable", type: "bool", title: "Enable descriptionText logging", defaultValue: true
        input name: "debugEnable", type: "bool", title: "Enable debug logging", defaultValue: false, submitOnChange: true
        if (debugEnable) {
            input name: "traceEnable", type: "bool", title: "Enable trace logging", defaultValue: false
        }
    }
}

void installed() {
    logDebug "installed"
}

void updated() {
    if (debugEnable) runIn(DEBUG_LOG_TIMEOUT, "turnOffDebugLogging")
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

// Called by the parent on each poll with the latest network + sync module state.
void handleNetworkUpdate(Map data) {
    if (!data) return
    logTrace "handleNetworkUpdate: ${data}"

    if (data.containsKey("armed")) {
        boolean armed = data.armed as boolean
        String value = armed ? "on" : "off"
        sendEvent(name: "switch", value: value, descriptionText: "Network is ${armed ? 'armed' : 'disarmed'}")
        logInfo "${armed ? 'armed' : 'disarmed'}"
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

private void logTrace(String message) {
    if (traceEnable) log.trace "${device} : ${message}"
}

private void logDebug(String message) {
    if (debugEnable) log.debug "${device} : ${message}"
}

private void logInfo(String message) {
    if (txtEnable) log.info "${device} : ${message}"
}

private void logWarn(String message) {
    log.warn "${device} : ${message}"
}

private void logError(String message) {
    log.error "${device} : ${message}"
}

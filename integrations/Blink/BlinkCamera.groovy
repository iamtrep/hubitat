// Copyright (c) 2025-2026 PJ
// SPDX-License-Identifier: MIT

/*
 * Blink Camera — Child Driver
 *
 * One device per Blink camera (regardless of variant: standard, mini/owl,
 * doorbell, floodlight). For MVP all variants share this driver; per-variant
 * features (doorbell ring, floodlight light, owl-specific motion-enable) are
 * not exposed yet — the cameraType data value lets the parent app dispatch.
 *
 * on()/off() = enable/disable motion detection. Only standard cameras
 * (cameraType=camera) currently support motion-enable through this path; the
 * parent app logs a warning for other variants.
 */

import groovy.transform.Field

metadata {
    definition(
        name: "Blink Camera",
        namespace: "iamtrep",
        author: "pj",
        description: "Blink camera: motion sensor, per-camera motion-enable, battery, temperature, clip metadata, diagnostics.",
        importUrl: "https://raw.githubusercontent.com/iamtrep/hubitat/refs/heads/main/integrations/Blink/BlinkCamera.groovy"
    ) {
        capability "MotionSensor"
        capability "Switch"
        capability "Battery"
        capability "TemperatureMeasurement"
        capability "Refresh"

        attribute "lastClipUrl", "string"
        attribute "lastClipTime", "string"
        attribute "wifiSignal", "number"
        attribute "online", "string"
        attribute "firmwareVersion", "string"
        attribute "batteryState", "string"

        command "snapThumbnail"
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
    String cameraId = device.getDataValue("cameraId")
    if (!cameraId) { logError "no cameraId on this device"; return }
    parent.enableMotion(cameraId)
}

void off() {
    String cameraId = device.getDataValue("cameraId")
    if (!cameraId) { logError "no cameraId on this device"; return }
    parent.disableMotion(cameraId)
}

void refresh() {
    String cameraId = device.getDataValue("cameraId")
    if (cameraId) parent.refreshCamera(cameraId)
}

void snapThumbnail() {
    String cameraId = device.getDataValue("cameraId")
    if (cameraId) parent.snapThumbnail(cameraId)
}

// Called by the parent on each poll with the latest camera state.
void handleCameraUpdate(Map data) {
    if (!data) return
    logTrace "handleCameraUpdate: ${data}"

    if (data.motion != null) {
        String motion = data.motion as String
        sendEvent(name: "motion", value: motion, descriptionText: "Motion is ${motion}")
        if (motion == "active") logInfo "motion detected"
    }
    if (data.containsKey("motionEnabled")) {
        boolean enabled = data.motionEnabled as boolean
        sendEvent(name: "switch", value: enabled ? "on" : "off",
                  descriptionText: "Motion detection ${enabled ? 'enabled' : 'disabled'}")
    }
    if (data.battery != null) {
        sendEvent(name: "battery", value: data.battery as int, unit: "%",
                  descriptionText: "Battery is ${data.battery}%")
    }
    if (data.batteryState) {
        sendEvent(name: "batteryState", value: data.batteryState as String)
    }
    if (data.temperature != null) {
        Number raw = data.temperature as Number
        String temp = convertTemperatureIfNeeded(raw, "F", 1)
        String unit = "°${location.temperatureScale}"
        sendEvent(name: "temperature", value: temp, unit: unit,
                  descriptionText: "Temperature is ${temp}${unit}")
    }
    if (data.wifiSignal != null) {
        sendEvent(name: "wifiSignal", value: data.wifiSignal as int, unit: "dBm")
    }
    if (data.online) {
        sendEvent(name: "online", value: data.online as String)
    }
    if (data.firmwareVersion) {
        sendEvent(name: "firmwareVersion", value: data.firmwareVersion as String)
    }
    if (data.lastClipUrl) {
        sendEvent(name: "lastClipUrl", value: data.lastClipUrl as String)
    }
    if (data.lastClipTime) {
        sendEvent(name: "lastClipTime", value: data.lastClipTime as String)
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

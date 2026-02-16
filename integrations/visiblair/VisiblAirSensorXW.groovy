/*
 * VisiblAir Sensor XW — Child Driver (Wind: speed, direction)
 *
 * For model X-WIND. Part of the VisiblAir Manager integration.
 * All communication goes through the parent app.
 *
 * Licensed under the Apache License, Version 2.0
 */

import groovy.transform.CompileStatic
import groovy.transform.Field

metadata {
    definition(
        name: "VisiblAir Sensor XW",
        namespace: "iamtrep",
        author: "pj"
    ) {
        capability "Sensor"
        capability "Refresh"

        attribute "timestamp", "date"
        attribute "calibration", "date"
        attribute "windSpeed", "number"
        attribute "windDirection", "number"
        attribute "windDirectionName", "string"

        command "reboot"
        command "calibrate"
        command "updateFirmware"
    }
}

@Field static final int DEBUG_LOG_TIMEOUT = 1800

@Field static final List<String> COMPASS_POINTS = [
    "N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
    "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW"
]

preferences {
    section("Logging") {
        input name: "txtEnable", type: "bool", title: "Enable descriptionText logging", defaultValue: false
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
    if (debugEnable) runIn(DEBUG_LOG_TIMEOUT, turnOffDebugLogging)
}

void refresh() {
    parent.refreshSensor(device.deviceNetworkId)
}

void reboot() {
    String uuid = device.getDataValue("uuid")
    if (uuid) parent.sendFirmwareCommand(uuid, "flagRebootRequested")
}

void calibrate() {
    String uuid = device.getDataValue("uuid")
    if (uuid) parent.sendFirmwareCommand(uuid, "flagCalibrationRequested")
}

void updateFirmware() {
    String uuid = device.getDataValue("uuid")
    if (uuid) parent.sendFirmwareCommand(uuid, "flagFirmwareUpdate")
}

void updateSensorData(Map data) {
    if (!data) return
    logTrace "updateSensorData: ${data}"

    data.each { String key, value ->
        switch (key) {
            case "lastSampleWindSpeed":
                Number speed = unwrapNumeric(value)
                if (speed != null) {
                    sendEvent(name: "windSpeed", value: speed, unit: "km/h", descriptionText: "Wind speed is ${speed} km/h")
                    logInfo "Wind speed is ${speed} km/h"
                }
                break
            case "lastSampleWindDirection":
                Number degrees = unwrapNumeric(value)
                if (degrees != null) {
                    String compass = degreesToCompass(degrees as double)
                    sendEvent(name: "windDirection", value: degrees, unit: "\u00B0", descriptionText: "Wind direction is ${degrees}\u00B0 (${compass})")
                    sendEvent(name: "windDirectionName", value: compass)
                    logInfo "Wind direction is ${degrees}\u00B0 (${compass})"
                }
                break
            case "lastSampleTimeStamp":
                sendEvent(name: "timestamp", value: value)
                break
            case "lastCalibration":
                sendEvent(name: "calibration", value: value)
                break
            case "firmwareVersion":
                state.firmwareVersion = value
                break
            case "model":
                state.model = value
                break
            case "modelVersion":
                state.modelVersion = value
                break
            case "sampleRate":
                state.sampleRate = value
                break
            default:
                logTrace "unhandled field: ${key}=${value}"
                break
        }
    }
}

// --- Helpers ---

@CompileStatic
static String degreesToCompass(double degrees) {
    int index = (int) Math.round(((degrees % 360) / 22.5d)) % 16
    return COMPASS_POINTS[index]
}

@CompileStatic
static Number unwrapNumeric(Object value) {
    if (value instanceof Map) {
        Map m = (Map) value
        if (m.containsKey("Float64")) return m.get("Float64") as Number
        if (m.containsKey("Int64")) return m.get("Int64") as Number
    }
    if (value instanceof Number) return (Number) value
    String s = value.toString().trim()
    if (s.isEmpty()) return null
    try {
        return new BigDecimal(s)
    } catch (NumberFormatException ignored) {
        return null
    }
}

void turnOffDebugLogging() {
    logWarn "debug logging disabled"
    device.updateSetting("debugEnable", [value: "false", type: "bool"])
    device.updateSetting("traceEnable", [value: "false", type: "bool"])
}

// --- Logging ---

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

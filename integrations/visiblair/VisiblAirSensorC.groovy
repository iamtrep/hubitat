/*
 * VisiblAir Sensor C — Child Driver (Basic: CO2, Temperature, Humidity)
 *
 * Part of the VisiblAir Manager integration. Do not configure API credentials here;
 * all communication goes through the parent app.
 *
 * Licensed under the Apache License, Version 2.0
 */

import groovy.transform.CompileStatic
import groovy.transform.Field

metadata {
    definition(
        name: "VisiblAir Sensor C",
        namespace: "iamtrep",
        author: "pj"
    ) {
        capability "CarbonDioxideMeasurement"
        capability "TemperatureMeasurement"
        capability "RelativeHumidityMeasurement"
        capability "Sensor"
        capability "Refresh"

        attribute "timestamp", "date"
        attribute "calibration", "date"

        command "reboot"
        command "calibrate"
        command "updateFirmware"
    }
}

@Field static final int DEBUG_LOG_TIMEOUT = 1800

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
            case "lastSampleCo2":
                Number co2 = unwrapNumeric(value)
                if (co2 != null) {
                    sendEvent(name: "carbonDioxide", value: co2, unit: "ppm", descriptionText: "CO2 is ${co2} ppm")
                    logInfo "CO2 is ${co2} ppm"
                }
                break
            case "lastSampleTemperature":
                Number rawTemp = unwrapNumeric(value)
                if (rawTemp != null) {
                    String temp = convertTemperatureIfNeeded(rawTemp, "c", 1)
                    String unit = "\u00B0${location.temperatureScale}"
                    sendEvent(name: "temperature", value: temp, unit: unit, descriptionText: "Temperature is ${temp}${unit}")
                    logInfo "Temperature is ${temp}${unit}"
                }
                break
            case "lastSampleHumidity":
                Number humidity = unwrapNumeric(value)
                if (humidity != null) {
                    sendEvent(name: "humidity", value: humidity, unit: "%", descriptionText: "Humidity is ${humidity}%")
                    logInfo "Humidity is ${humidity}%"
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

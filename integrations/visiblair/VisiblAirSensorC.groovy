// Copyright (c) 2025-2026 PJ
// SPDX-License-Identifier: MIT

/*
 * VisiblAir Sensor C — Child Driver (Basic: CO2, Temperature, Humidity)
 *
 * Part of the VisiblAir Manager integration. Do not configure API credentials here;
 * all communication goes through the parent app.
 */

import groovy.transform.CompileStatic
import groovy.transform.Field

@Field static final String CODE_VERSION = "0.2.1"

metadata {
    definition(
        name: "VisiblAir Sensor C",
        namespace: "iamtrep",
        author: "pj",
        description: "CO₂, temperature, humidity",
        importUrl: "https://raw.githubusercontent.com/iamtrep/hubitat/refs/heads/main/integrations/visiblair/VisiblAirSensorC.groovy"
    ) {
        capability "CarbonDioxideMeasurement"
        capability "TemperatureMeasurement"
        capability "RelativeHumidityMeasurement"
        capability "Sensor"
        capability "Refresh"

        attribute "timestamp", "date"
        attribute "calibration", "date"
        attribute "lastSeen", "date"
        attribute "healthStatus", "enum", ["online", "offline"]
        attribute "firmwareUpdateAvailable", "enum", ["true", "false"]

        command "reboot"
        command "calibrate"
        command "updateFirmware"
    }
}

@Field static final int DEBUG_LOG_TIMEOUT = 1800

preferences {
    section("Sensor Settings") {
        input name: "sensorDescription", type: "text", title: "Sensor description"
        input name: "co2Offset", type: "number", title: "CO2 offset (ppm)"
        input name: "temperatureOffset", type: "decimal", title: "Temperature offset (\u00B0C)"
        input name: "humidityOffset", type: "number", title: "Humidity offset (%)"
        input name: "calibrationCO2Level", type: "number", title: "CO2 calibration baseline (ppm)"
        input name: "sampleRatePref", type: "number", title: "Sample rate (seconds)", range: "60..3600"
        input name: "audibleAlertLevel", type: "number", title: "Audible alert CO2 level (0 = off)"
        input name: "displayRefresh", type: "number", title: "Display refresh (seconds)"
        input name: "displaySleepTimeout", type: "number", title: "Display sleep timeout (0 = always on)"
        input name: "temperatureUnit", type: "enum", title: "Temperature unit on sensor display", options: ["C", "F"]
    }
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
    if (debugEnable || traceEnable) runIn(DEBUG_LOG_TIMEOUT, turnOffDebugLogging)
    pushConfigChanges()
}

private void pushConfigChanges() {
    String uuid = device.getDataValue("uuid")
    if (!uuid) return

    Map overrides = [:]
    if (sensorDescription != null) overrides.description = sensorDescription
    if (co2Offset != null) overrides.co2Offset = co2Offset
    if (temperatureOffset != null) overrides.temperatureOffset = temperatureOffset
    if (humidityOffset != null) overrides.humidityOffset = humidityOffset
    if (calibrationCO2Level != null) overrides.calibrationCO2Level = calibrationCO2Level
    if (sampleRatePref != null) overrides.sampleRate = sampleRatePref
    if (audibleAlertLevel != null) overrides.audibleAlertLevel = audibleAlertLevel
    if (displayRefresh != null) overrides.displayRefresh = displayRefresh
    if (displaySleepTimeout != null) overrides.displaySleepTimeout = displaySleepTimeout
    if (temperatureUnit != null) overrides.temperatureUnit = temperatureUnit

    if (overrides.size() > 0) {
        logDebug "pushing config changes: ${overrides}"
        parent.updateSensorConfig(uuid, overrides)
    }
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

// Parent-fed child: nothing to reconfigure, so a push is only recorded.
private void checkVersion() {
    if (state.version == CODE_VERSION) return
    logVer "New version: ${CODE_VERSION} (was: ${state.version})"
    state.version = CODE_VERSION
}

// Called by the parent after each poll: offline while the API is unreachable or samples are stale.
void setHealthStatus(String status, String reason) {
    String previous = device.currentValue("healthStatus") as String
    sendEvent(name: "healthStatus", value: status, descriptionText: "${device.displayName} is ${status}: ${reason}")
    if (status == previous) return
    if (status == "offline") logWarn "offline: ${reason}"
    else logInfo "online"
}

void updateSensorData(Map data) {
    checkVersion()
    if (!data) return
    logTrace "updateSensorData: ${data}"

    data.each { String key, value ->
        switch (key) {
            // --- Measurement data ---
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

            // --- Timestamps ---
            case "lastSampleTimeStamp":
                sendEvent(name: "timestamp", value: value)
                break
            case "lastCalibration":
                sendEvent(name: "calibration", value: value)
                break
            case "lastSeenTimeStamp":
                sendEvent(name: "lastSeen", value: value)
                break

            // --- Device info ---
            case "firmwareVersion":
                setMetadata("firmwareVersion", value)
                break
            case "latestFirmwareVersion":
                setMetadata("latestFirmwareVersion", value)
                String current = device.getDataValue("firmwareVersion") ?: ""
                String latest = (value ?: "") as String
                boolean updateAvail = latest != "" && latest != current
                sendEvent(name: "firmwareUpdateAvailable", value: updateAvail ? "true" : "false")
                break
            case "model":
                setMetadata("model", value)
                break
            case "modelVersion":
                setMetadata("modelVersion", value)
                break

            // --- Config sync to preferences ---
            case "sampleRate":
                state.sampleRate = value
                device.updateSetting("sampleRatePref", [value: value as int, type: "number"])
                break
            case "co2Offset":
                device.updateSetting("co2Offset", [value: value as int, type: "number"])
                break
            case "temperatureOffset":
                device.updateSetting("temperatureOffset", [value: value as BigDecimal, type: "decimal"])
                break
            case "humidityOffset":
                device.updateSetting("humidityOffset", [value: value as int, type: "number"])
                break
            case "description":
                device.updateSetting("sensorDescription", [value: value as String, type: "text"])
                break
            case "calibrationCO2Level":
                device.updateSetting("calibrationCO2Level", [value: value as int, type: "number"])
                break
            case "audibleAlertLevel":
                device.updateSetting("audibleAlertLevel", [value: value as int, type: "number"])
                break
            case "displayRefresh":
                device.updateSetting("displayRefresh", [value: value as int, type: "number"])
                break
            case "displaySleepTimeout":
                device.updateSetting("displaySleepTimeout", [value: value as int, type: "number"])
                break
            case "temperatureUnit":
                device.updateSetting("temperatureUnit", [value: value as String, type: "enum"])
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

// Firmware and model are device metadata: data values survive a driver switch, which clears
// state. Also drops the state key earlier versions wrote.
private void setMetadata(String key, Object value) {
    state.remove(key)
    if (value != null) device.updateDataValue(key, value.toString())
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

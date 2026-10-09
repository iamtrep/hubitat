// Copyright (c) 2025-2026 PJ
// SPDX-License-Identifier: MIT

/*
 * VisiblAir Sensor XW — Child Driver (Wind: speed, direction)
 *
 * For model X-WIND. Part of the VisiblAir Manager integration.
 * All communication goes through the parent app.
 */

import groovy.transform.CompileStatic
import groovy.transform.Field

@Field static final String CODE_VERSION = "0.2.1"

metadata {
    definition(
        name: "VisiblAir Sensor XW",
        namespace: "iamtrep",
        author: "pj",
        description: "Wind speed, direction, compass heading",
        importUrl: "https://raw.githubusercontent.com/iamtrep/hubitat/refs/heads/main/integrations/visiblair/VisiblAirSensorXW.groovy"
    ) {
        capability "Sensor"
        capability "Refresh"

        attribute "timestamp", "date"
        attribute "calibration", "date"
        attribute "windSpeed", "number"
        attribute "windDirection", "number"
        attribute "windDirectionName", "string"
        attribute "lastSeen", "date"
        attribute "healthStatus", "enum", ["online", "offline"]
        attribute "firmwareUpdateAvailable", "enum", ["true", "false"]

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
    section("Sensor Settings") {
        input name: "sensorDescription", type: "text", title: "Sensor description"
        input name: "sampleRatePref", type: "number", title: "Sample rate (seconds)", range: "60..3600"
        input name: "audibleAlertLevel", type: "number", title: "Audible alert CO2 level (0 = off)"
        input name: "displayRefresh", type: "number", title: "Display refresh (seconds)"
        input name: "displaySleepTimeout", type: "number", title: "Display sleep timeout (0 = always on)"
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
    if (sampleRatePref != null) overrides.sampleRate = sampleRatePref
    if (audibleAlertLevel != null) overrides.audibleAlertLevel = audibleAlertLevel
    if (displayRefresh != null) overrides.displayRefresh = displayRefresh
    if (displaySleepTimeout != null) overrides.displaySleepTimeout = displaySleepTimeout

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
            case "description":
                device.updateSetting("sensorDescription", [value: value as String, type: "text"])
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

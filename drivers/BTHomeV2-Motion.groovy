// Copyright (c) 2025-2026 PJ
// SPDX-License-Identifier: MIT

import groovy.transform.Field

@Field static final String CODE_VERSION = "0.1.0"
@Field static final long RSSI_MIN_INTERVAL_MS = 10000

metadata {
    definition (
        name: "Bluetooth Home v2 Motion/Occupancy Sensor",
        namespace: "hubitat",
        author: "Victor U.",
        description: "BLE motion/occupancy sensor via BTHome v2",
        importUrl: "https://raw.githubusercontent.com/iamtrep/hubitat/refs/heads/main/drivers/BTHomeV2-Motion.groovy",
        singleThreaded: true
    ) {
        capability "Battery"
        capability "Illuminance Measurement"
        capability "Motion Sensor"

        attribute "rssi", "number"
    }
    preferences {
        input name: "txtEnable", type: "bool", title: "Enable info logging", defaultValue: true
        input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: true, submitOnChange: true
        if (logEnable) {
            input name: "traceEnable", type: "bool", title: "Enable trace logging", defaultValue: false
        }
    }
}

void parse(Map data) {
    checkVersion()
    parseBatteryAndRSSI(data)
    if (hasBinaryValue(data, "motion")) {
        processBinaryValue(data, "motion", "motion", "active", "inactive")
    } else if (hasBinaryValue(data, "occupancy")) {
        processBinaryValue(data, "occupancy", "motion", "active", "inactive")
    }
    processDoubleValue(data, "illuminance", "illuminance", "lux")
}

void installed() {
    if (logEnable || traceEnable) runIn(1800, "logsOff")
}

void updated() {
    if (logEnable || traceEnable) runIn(1800, "logsOff")
}

void deviceTypeUpdated() {
    logDebug "driver change detected"
}

void uninstalled() {
    // nothing for now
}

void initialize() {
    checkVersion()
}

// BLE advertisements need no device-side configuration, so a push is only recorded.
private void checkVersion() {
    if (state.version == CODE_VERSION) return
    logVer "New version: ${CODE_VERSION} (was: ${state.version})"
    state.version = CODE_VERSION
}

void logsOff() {
    logWarn "debug and trace logging disabled"
    device.updateSetting("logEnable", [value: "false", type: "bool"])
    device.updateSetting("traceEnable", [value: "false", type: "bool"])
}

Map getSensorData(Map data, String sensorType) {
    if (data.sensors && (data.sensors instanceof List)) {
        for (sensor in data.sensors) {
            if (sensor.device == sensorType) {
                return sensor
            }
        }
    }
    return null
}

Map getEventData(Map data, String sensorType) {
    if (data.events && (data.events instanceof List)) {
        for (sensor in data.events) {
            if (sensor.device == sensorType) {
                return sensor
            }
        }
    }
    return null
}

boolean getBinaryValue(Map data, String valueName) {
    if (data.binary_values && (data.binary_values instanceof List)) {
        for (binary_value in data.binary_values) {
            if (binary_value.device == valueName) {
                return binary_value.value as boolean
            }
        }
    }
    return false
}

boolean hasBinaryValue(Map data, String valueName) {
    if (data.binary_values && (data.binary_values instanceof List)) {
        for (binary_value in data.binary_values) {
            if (binary_value.device == valueName) {
                return true
            }
        }
    }
    return false
}

void processBinaryValue(Map data, String valueName, String attributeName, String trueState, String falseState) {
    boolean value = getBinaryValue(data, valueName)
    String hubitatState = value ? trueState : falseState
    if (txtEnable && device.currentValue(attributeName) != hubitatState) logInfo "${attributeName} is now ${hubitatState}"
    sendEvent(name: attributeName, value: hubitatState, descriptionText: "${device.displayName} is now ${hubitatState}")
}

void processDoubleValue(Map data, String sensorName, String attributeName, String unit = null) {
    Map sensorData = getSensorData(data, sensorName)
    if (sensorData && isDouble(sensorData.value)) {
        double sensorValue = Math.round(sensorData.value * 100) / 100.0
        sendEvent(name: attributeName, value: sensorValue,
                descriptionText: "${device.displayName} ${attributeName} is now ${sensorValue}${unit ?: ''}",
                unit: unit)
    }
}

void processIntegerValue(Map data, String sensorName, String attributeName, String unit = null) {
    Map sensorData = getSensorData(data, sensorName)
    if (sensorData && isInteger(sensorData.value)) {
        int sensorValue = sensorData.value as int
        sendEvent(name: attributeName, value: sensorValue,
                descriptionText: "${device.displayName} ${attributeName} is now ${sensorValue}${unit ?: ''}",
                unit: unit)
    }
}

void processTemperatureValue(Map data) {
    Map temperatureData = getSensorData(data, "temperature")
    if (temperatureData && isDouble(temperatureData.value)) {
        // BTHomeV2 always reports temperature in Celsius, according to https://bthome.io/format/
        double temperature = temperatureData.value as double
        if (location.temperatureScale == "F")
            temperature = celsiusToFahrenheit(temperature)

        sendEvent(name: "temperature", value: temperature,
                descriptionText: "${device.displayName} temperature is now ${temperature} °${location.temperatureScale}",
                unit: location.temperatureScale)
    }
}

boolean isInteger(obj) {
    try {
        Integer.parseInt(obj.toString())
        return true
    } catch (Exception e) {
        return false
    }
}

boolean isDouble(obj) {
    try {
        Double.parseDouble(obj.toString())
        return true
    } catch (Exception e) {
        return false
    }
}

void parseBatteryAndRSSI(Map data) {
    logRx "parse: ${data}"

    processIntegerValue(data, "battery", "battery", "%")

    long now = now()
    long lastRssi = state.lastRssiReport ?: 0
    if (now - lastRssi >= RSSI_MIN_INTERVAL_MS) {
        processIntegerValue(data, "signal_strength", "rssi", "dBm")
        state.lastRssiReport = now
    }
}

// ── Logging ───────────────────────────────────────────────────────────
//   ⬇️ Rx  ⬆️ Cmd  🔧 Cfg  🌐 Net  ⏰ Sched  📦 Ota  🏷️ Ver  ·  ⚠️ Warn  🛑 Error  🔬 Trace
private String logp(String e) { "${e} ${device.displayName}: " }

void logRx   (String m) { if (logEnable) log.debug logp('⬇️') + m }
void logCmd  (String m) { if (txtEnable != false) log.info  logp('⬆️') + m }
void logCfg  (String m) { if (txtEnable != false) log.info  logp('🔧') + m }
void logNet  (String m) { if (logEnable) log.debug logp('🌐') + m }
void logSched(String m) { if (logEnable) log.debug logp('⏰') + m }
void logOta  (String m) { if (txtEnable != false) log.info  logp('📦') + m }
void logVer  (String m) { log.warn  logp('🏷️') + m }

void logWarn (String m) { log.warn  logp('⚠️') + m }
void logError(String m) { log.error logp('🛑') + m }
void logTrace(String m) { if (traceEnable) log.trace logp('🔬') + m }
void logInfo (String m) { if (txtEnable != false) log.info  "${device.displayName}: ${m}" }
void logDebug(String m) { if (logEnable) log.debug "${device.displayName}: ${m}" }

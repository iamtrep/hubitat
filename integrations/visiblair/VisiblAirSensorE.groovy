/*
 * VisiblAir Sensor E — Child Driver (Air Quality: CO2, T, H, VOC, Pressure, PM, Battery, AQI)
 *
 * For models E and E-Lite. Part of the VisiblAir Manager integration.
 * All communication goes through the parent app.
 *
 * Licensed under the Apache License, Version 2.0
 */

import groovy.transform.CompileStatic
import groovy.transform.Field

metadata {
    definition(
        name: "VisiblAir Sensor E",
        namespace: "iamtrep",
        author: "pj"
    ) {
        capability "AirQuality"
        capability "Battery"
        capability "CarbonDioxideMeasurement"
        capability "PressureMeasurement"
        capability "Refresh"
        capability "RelativeHumidityMeasurement"
        capability "Sensor"
        capability "TemperatureMeasurement"

        attribute "timestamp", "date"
        attribute "calibration", "date"
        attribute "vocIndex", "number"
        attribute "pm01", "number"
        attribute "pm05", "number"
        attribute "pm10", "number"
        attribute "pm25", "number"
        attribute "pm40", "number"
        attribute "pm50", "number"
        attribute "pm100", "number"

        command "reboot"
        command "calibrate"
        command "updateFirmware"
    }
}

@Field static final int DEBUG_LOG_TIMEOUT = 1800
@Field static final int MAX_PM25_READINGS = 5

// AQI PM2.5 breakpoints (EPA)
@Field static final List<Map<String, Object>> AQI_BREAKPOINTS = [
    [bpLow: 0.0d, bpHigh: 12.0d, aqiLow: 0, aqiHigh: 50],
    [bpLow: 12.1d, bpHigh: 35.4d, aqiLow: 51, aqiHigh: 100],
    [bpLow: 35.5d, bpHigh: 55.4d, aqiLow: 101, aqiHigh: 150],
    [bpLow: 55.5d, bpHigh: 150.4d, aqiLow: 151, aqiHigh: 200],
    [bpLow: 150.5d, bpHigh: 250.4d, aqiLow: 201, aqiHigh: 300],
    [bpLow: 250.5d, bpHigh: 350.4d, aqiLow: 301, aqiHigh: 400],
    [bpLow: 350.5d, bpHigh: 500.4d, aqiLow: 401, aqiHigh: 500],
    [bpLow: 500.5d, bpHigh: 99999.9d, aqiLow: 501, aqiHigh: 999]
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
    state.pm25readings = []
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
            case "lastSamplePressure":
                Number pressure = unwrapNumeric(value)
                if (pressure != null) {
                    sendEvent(name: "pressure", value: pressure, unit: "mBar", descriptionText: "Pressure is ${pressure} mBar")
                    logInfo "Pressure is ${pressure} mBar"
                }
                break
            case "lastSampleBattPct":
                Number battery = unwrapNumeric(value)
                if (battery != null) {
                    sendEvent(name: "battery", value: battery, unit: "%", descriptionText: "Battery is ${battery}%")
                    logInfo "Battery is ${battery}%"
                }
                break
            case "lastSampleVocIndex":
                Number voc = unwrapNumeric(value)
                if (voc != null) {
                    sendEvent(name: "vocIndex", value: voc, descriptionText: "VOC index is ${voc}")
                    logInfo "VOC index is ${voc}"
                }
                break
            case "lastSamplePm01":
                Number pm01 = unwrapNumeric(value)
                if (pm01 != null) sendEvent(name: "pm01", value: pm01, unit: "ug/m3")
                break
            case "lastSamplePm05":
                Number pm05 = unwrapNumeric(value)
                if (pm05 != null) sendEvent(name: "pm05", value: pm05, unit: "ug/m3")
                break
            case "lastSamplePm10":
                Number pm10 = unwrapNumeric(value)
                if (pm10 != null) sendEvent(name: "pm10", value: pm10, unit: "ug/m3")
                break
            case "lastSamplePm25":
                Number pm25 = unwrapNumeric(value)
                if (pm25 != null) {
                    sendEvent(name: "pm25", value: pm25, unit: "ug/m3")
                    updateAqi(pm25)
                }
                break
            case "lastSamplePm40":
                Number pm40 = unwrapNumeric(value)
                if (pm40 != null) sendEvent(name: "pm40", value: pm40, unit: "ug/m3")
                break
            case "lastSamplePm50":
                Number pm50 = unwrapNumeric(value)
                if (pm50 != null) sendEvent(name: "pm50", value: pm50, unit: "ug/m3")
                break
            case "lastSamplePm100":
                Number pm100 = unwrapNumeric(value)
                if (pm100 != null) sendEvent(name: "pm100", value: pm100, unit: "ug/m3")
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

// --- AQI ---

private void updateAqi(Number pm25Value) {
    List readings = (state.pm25readings ?: []) as List
    readings << pm25Value
    while (readings.size() > MAX_PM25_READINGS) {
        readings.removeAt(0)
    }
    state.pm25readings = readings

    if (readings.isEmpty()) return

    double totalPM25 = 0.0d
    for (Object reading : readings) {
        totalPM25 += (reading as double)
    }
    double avgPM25 = totalPM25 / readings.size()

    Map<String, Object> tier = findAqiTier(avgPM25)
    int aqi = Math.round(calculateRawAqi(tier, avgPM25)) as int

    sendEvent(name: "airQualityIndex", value: aqi, descriptionText: "AQI is ${aqi}")
    logInfo "AQI is ${aqi}"
}

@CompileStatic
private static Map<String, Object> findAqiTier(double avgPM25) {
    for (int i = AQI_BREAKPOINTS.size() - 1; i >= 0; i--) {
        if (avgPM25 >= (AQI_BREAKPOINTS[i]['bpLow'] as double)) {
            return AQI_BREAKPOINTS[i]
        }
    }
    return AQI_BREAKPOINTS.first()
}

@CompileStatic
private static double calculateRawAqi(Map<String, Object> tier, double avgPM25) {
    int aqiHigh = tier['aqiHigh'] as int
    int aqiLow = tier['aqiLow'] as int
    double bpHigh = tier['bpHigh'] as double
    double bpLow = tier['bpLow'] as double
    return ((aqiHigh - aqiLow) / (bpHigh - bpLow)) * (avgPM25 - bpLow) + aqiLow
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

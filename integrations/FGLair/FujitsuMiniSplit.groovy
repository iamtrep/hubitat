// Copyright (c) 2026 PJ
// SPDX-License-Identifier: MIT

/*
 * Fujitsu Mini-Split — Child Driver
 *
 * One device per Fujitsu indoor unit.
 *
 * Capability strategy: the standard Thermostat capability is constrained to the
 * canonical Hubitat enums (modes: off/heat/cool/auto[/emergency heat]; fan modes:
 * auto/circulate/on). setSupportedThermostatModes / setSupportedThermostatFanModes
 * narrow that canonical set — they cannot extend it. The Fujitsu-specific values
 * (dry / fan_only modes; quiet/low/medium/high fan speeds) live on a parallel
 * custom surface: fujitsuMode + fanSpeed attributes, setFujitsuMode + setFanSpeed
 * commands. Dashboards and Alexa/Google use the canonical Thermostat surface for
 * the 80% case; automations needing dry/fan_only or specific fan speeds use the
 * custom surface.
 *
 * Receives state via parent.updateState(Map); writes via
 * parent.sendCommand(dni, name, intValue).
 */

import groovy.json.JsonOutput
import groovy.transform.Field

metadata {
    definition(
        name: "Fujitsu Mini-Split",
        namespace: "iamtrep",
        author: "pj",
        importUrl: "https://raw.githubusercontent.com/iamtrep/hubitat/refs/heads/main/integrations/FGLair/FujitsuMiniSplit.groovy"
    ) {
        capability "Thermostat"
        capability "TemperatureMeasurement"
        capability "Refresh"
        capability "Sensor"
        capability "Actuator"

        attribute "supportedThermostatFanModes", "JSON_OBJECT"
        attribute "supportedThermostatModes",    "JSON_OBJECT"
        attribute "outdoorTemperature",          "number"
        attribute "fujitsuMode",                 "string"
        attribute "fanSpeed",                    "string"

        command "setSupportedThermostatFanModes", ["JSON_OBJECT"]
        command "setSupportedThermostatModes",    ["JSON_OBJECT"]
        command "setFujitsuMode", [[name: "mode*", type: "ENUM",
                                    description: "Fujitsu operation mode",
                                    constraints: ["off","heat","cool","auto","dry","fan_only"]]]
        command "setFanSpeed",    [[name: "speed*", type: "ENUM",
                                    description: "Fujitsu fan speed",
                                    constraints: ["auto","quiet","low","medium","high"]]]
    }

    preferences {
        input name: "txtEnable",   type: "bool", title: "Enable descriptionText logging", defaultValue: true
        input name: "debugEnable", type: "bool", title: "Enable debug logging",           defaultValue: false, submitOnChange: true
        if (debugEnable) {
            input name: "traceEnable", type: "bool", title: "Enable trace logging", defaultValue: false
        }
    }
}

@Field static final String DRIVER_VERSION = "0.1.0"

@Field static final List<String> SUPPORTED_STD_MODES = ["\"off\"", "\"heat\"", "\"cool\"", "\"auto\""]
@Field static final List<String> SUPPORTED_STD_FAN_MODES = ["\"auto\""]
@Field static final List<String> FUJITSU_MODES = ["off","heat","cool","auto","dry","fan_only"]
@Field static final List<String> FUJITSU_FAN_SPEEDS = ["auto","quiet","low","medium","high"]

void installed() { logDebug "installed"; state.version = DRIVER_VERSION; initialize() }
void updated()   { logDebug "updated"; unschedule(); initialize() }
void initialize() {
    logDebug "initialize"
    if (state.version != DRIVER_VERSION) {
        logWarn "new version: ${DRIVER_VERSION} (was: ${state.version})"
        state.version = DRIVER_VERSION
    }
    sendEvent(name: "supportedThermostatModes",    value: SUPPORTED_STD_MODES)
    sendEvent(name: "supportedThermostatFanModes", value: SUPPORTED_STD_FAN_MODES)
    emitBounds()
}

void refresh() {
    logDebug "refresh"
    parent?.refreshUnit(device.deviceNetworkId)
}

private void emitBounds() {
    String scale = getTemperatureScale()
    sendEvent(name: 'minHeatingSetpoint', value: convertFromC(16), unit: scale)
    sendEvent(name: 'maxHeatingSetpoint', value: convertFromC(30), unit: scale)
    sendEvent(name: 'minCoolingSetpoint', value: convertFromC(18), unit: scale)
    sendEvent(name: 'maxCoolingSetpoint', value: convertFromC(30), unit: scale)
}

private BigDecimal convertFromC(BigDecimal celsius) {
    if (getTemperatureScale() == 'F') {
        return (celsius * 9 / 5 + 32).setScale(1, java.math.RoundingMode.HALF_UP)
    }
    return celsius.setScale(1, java.math.RoundingMode.HALF_UP)
}

// --- Command stubs ---
// Standard Thermostat / Thermostat Fan Mode commands are defined regardless of
// the supported list. They land with real bodies in Task 7. Custom commands
// (setFujitsuMode / setFanSpeed) also get real bodies in Task 7.

void setThermostatMode(String mode)       { logWarn "setThermostatMode(${mode}) — not implemented yet" }
void setHeatingSetpoint(BigDecimal t)     { logWarn "setHeatingSetpoint(${t}) — not implemented yet" }
void setCoolingSetpoint(BigDecimal t)     { logWarn "setCoolingSetpoint(${t}) — not implemented yet" }
void setThermostatFanMode(String fanMode) { logWarn "setThermostatFanMode(${fanMode}) — not implemented yet" }
void setFujitsuMode(String mode)          { logWarn "setFujitsuMode(${mode}) — not implemented yet" }
void setFanSpeed(String speed)            { logWarn "setFanSpeed(${speed}) — not implemented yet" }

void auto()           { setThermostatMode("auto") }
void cool()           { setThermostatMode("cool") }
void heat()           { setThermostatMode("heat") }
void off()            { setThermostatMode("off") }
void emergencyHeat()  { logWarn "emergencyHeat() not supported on Fujitsu mini-splits — routing to heat"; setThermostatMode("heat") }
void fanAuto()        { setThermostatFanMode("auto") }
void fanOn()          { logWarn "fanOn() not a standard Fujitsu fan setting — routing to setFanSpeed(\"high\")"; setFanSpeed("high") }
void fanCirculate()   { logWarn "fanCirculate() not a standard Fujitsu fan setting — routing to setFanSpeed(\"low\")"; setFanSpeed("low") }

// --- Inbound state from parent ---

@Field static final Map<Integer, String> OP_MODE = [
    0: "off", 1: null, 2: "auto", 3: "cool", 4: "dry", 5: "fan_only", 6: "heat"
]
@Field static final Map<Integer, String> FAN_MODE = [
    0: "quiet", 1: "low", 2: "medium", 3: "high", 4: "auto"
]
@Field static final List<String> CANONICAL_MODES = ["off", "heat", "cool", "auto"]

void updateState(Map data) {
    logTrace "updateState(${data})"
    String fujMode = null
    if (data.opMode != null) {
        fujMode = OP_MODE[(int) data.opMode]
        if (fujMode != null) {
            sendEvent(name: "fujitsuMode", value: fujMode,
                      descriptionText: "${device} fujitsuMode is ${fujMode}")
            if (fujMode in CANONICAL_MODES) {
                sendEvent(name: "thermostatMode", value: fujMode,
                          descriptionText: "${device} mode is ${fujMode}")
            }
            sendEvent(name: "thermostatOperatingState",
                      value: deriveOperatingState(fujMode, data.displayTemp, data.adjustTemp))
        }
    }
    if (data.fanSpeed != null) {
        String speed = FAN_MODE[(int) data.fanSpeed]
        if (speed != null) {
            sendEvent(name: "fanSpeed", value: speed,
                      descriptionText: "${device} fanSpeed is ${speed}")
            if (speed == "auto") {
                sendEvent(name: "thermostatFanMode", value: "auto",
                          descriptionText: "${device} thermostatFanMode is auto")
            }
        }
    }
    if (data.displayTemp != null) {
        BigDecimal t = aylaSensorToScale(data.displayTemp)
        sendEvent(name: "temperature", value: t, unit: getTemperatureScale(),
                  descriptionText: "${device} temperature is ${t}${getTemperatureScale()}")
    }
    if (data.adjustTemp != null) {
        BigDecimal sp = aylaSetpointToScale(data.adjustTemp)
        ["thermostatSetpoint", "heatingSetpoint", "coolingSetpoint"].each { String name ->
            sendEvent(name: name, value: sp, unit: getTemperatureScale(),
                      descriptionText: "${device} ${name} is ${sp}${getTemperatureScale()}")
        }
    }
    if (data.outdoorTemp != null) {
        BigDecimal ot = aylaSensorToScale(data.outdoorTemp)
        sendEvent(name: "outdoorTemperature", value: ot, unit: getTemperatureScale(),
                  descriptionText: "${device} outdoor temperature is ${ot}${getTemperatureScale()}")
    }
}

private String deriveOperatingState(String mode, Object displayTemp, Object adjustTemp) {
    switch (mode) {
        case "off":      return "idle"
        case "heat":     return "heating"
        case "cool":     return "cooling"
        case "fan_only": return "fan only"
        case "dry":      return "idle"
        case "auto":
            if (displayTemp == null || adjustTemp == null) return "idle"
            BigDecimal sp = aylaSetpointToScale(adjustTemp)
            BigDecimal dt = aylaSensorToScale(displayTemp)
            if (sp > dt) return "heating"
            if (sp < dt) return "cooling"
            return "idle"
        default: return "idle"
    }
}

// Sensor readings (display_temperature, outdoor_temperature): hundredths of °F.
// Empirically verified 2026-05-18: raw 7000 = 70.00°F, raw 5500 = 55.00°F.
// Independent of the hub's temperature scale; we always convert to it.
// (If we ever see a unit reporting sensors in °C, this assumption needs a
// per-unit override setting on the manager.)
private BigDecimal aylaSensorToScale(Object raw) {
    BigDecimal fahrenheit = (raw as BigDecimal) / 100
    if (getTemperatureScale() == 'C') {
        BigDecimal celsius = (fahrenheit - 32) * 5 / 9
        return celsius.setScale(1, java.math.RoundingMode.HALF_UP)
    }
    return fahrenheit.setScale(1, java.math.RoundingMode.HALF_UP)
}

// Setpoint (adjust_temperature): tenths of °C, regardless of the unit's
// display scale. Empirically verified 2026-05-18: raw 180 = 18.0°C.
private BigDecimal aylaSetpointToScale(Object raw) {
    BigDecimal celsius = (raw as BigDecimal) / 10
    if (getTemperatureScale() == 'F') {
        return (celsius * 9 / 5 + 32).setScale(1, java.math.RoundingMode.HALF_UP)
    }
    return celsius.setScale(1, java.math.RoundingMode.HALF_UP)
}

private BigDecimal scaleToAylaSetpoint(BigDecimal scaleValue) {
    BigDecimal celsius = scaleValue
    if (getTemperatureScale() == 'F') {
        celsius = (scaleValue - 32) * 5 / 9
    }
    return (celsius * 10).setScale(0, java.math.RoundingMode.HALF_UP)
}

private void logTrace(String msg) { if (settings.traceEnable) log.trace "${device} ${msg}" }
private void logDebug(String msg) { if (settings.debugEnable) log.debug "${device} ${msg}" }
private void logInfo(String msg)  { if (settings.txtEnable)   log.info  "${device} ${msg}" }
private void logWarn(String msg)  { log.warn  "${device} ${msg}" }
private void logError(String msg) { log.error "${device} ${msg}" }

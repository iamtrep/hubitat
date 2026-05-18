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

        command "setFujitsuMode", [[name: "mode*", type: "ENUM",
                                    description: "Fujitsu operation mode",
                                    constraints: ["off","heat","cool","auto","dry","fan_only"]]]
        command "setFanSpeed",    [[name: "speed*", type: "ENUM",
                                    description: "Fujitsu fan speed",
                                    constraints: ["auto","quiet","low","medium","high"]]]
    }

    preferences {
        input name: "optimisticUpdates", type: "bool",
              title: "Optimistic attribute updates on write",
              description: "When on, attributes reflect the requested value immediately on command. When off, attributes only update on the next poll cycle (truthful cloud state).",
              defaultValue: true
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

// --- Write commands ---

@Field static final Map<String, Integer> OP_MODE_INV = [
    "off": 0, "auto": 2, "cool": 3, "dry": 4, "fan_only": 5, "heat": 6
]
@Field static final Map<String, Integer> FAN_MODE_INV = [
    "quiet": 0, "low": 1, "medium": 2, "high": 3, "auto": 4
]
@Field static final List<String> FUJITSU_ALL_MODES = ["off","heat","cool","auto","dry","fan_only"]
@Field static final List<String> FUJITSU_ALL_FAN_SPEEDS = ["auto","quiet","low","medium","high"]

void setThermostatMode(String mode) {
    if (!(mode in CANONICAL_MODES)) {
        logWarn "setThermostatMode(${mode}): not in canonical set — use setFujitsuMode for dry/fan_only"
        return
    }
    writeMode(mode)
}

void setFujitsuMode(String mode) {
    if (!(mode in FUJITSU_ALL_MODES)) {
        logWarn "setFujitsuMode(${mode}): not supported"
        return
    }
    writeMode(mode)
}

private void writeMode(String mode) {
    Integer code = OP_MODE_INV[mode]
    if (code == null) { logWarn "writeMode(${mode}): no int code"; return }
    String prevMode = device.currentValue("fujitsuMode")
    logInfo "setting operation_mode -> ${mode} (${code})"
    parent?.sendCommand(device.deviceNetworkId, "operation_mode", code)

    // Auto-push the stored mode-specific setpoint when transitioning into heat or cool.
    BigDecimal preset = null
    if (mode == "heat" && prevMode != "heat") {
        preset = device.currentValue("heatingSetpoint") as BigDecimal
    } else if (mode == "cool" && prevMode != "cool") {
        preset = device.currentValue("coolingSetpoint") as BigDecimal
    }
    if (preset != null) {
        logInfo "mode change ${prevMode} -> ${mode}: pushing stored ${mode}ingSetpoint ${preset}${getTemperatureScale()} to unit"
        pushSetpointToUnit(preset)
    }

    if (!isOptimistic()) return
    sendEvent(name: "fujitsuMode", value: mode,
              descriptionText: "${device} fujitsuMode is ${mode}")
    if (mode in CANONICAL_MODES) {
        sendEvent(name: "thermostatMode", value: mode,
                  descriptionText: "${device} mode is ${mode}")
    }
    String optState = optimisticOperatingState(mode)
    if (optState != null) {
        sendEvent(name: "thermostatOperatingState", value: optState,
                  descriptionText: "${device} operating state is ${optState}")
    }
}

private boolean isOptimistic() {
    return settings.optimisticUpdates == null ? true : (settings.optimisticUpdates as Boolean)
}

private String optimisticOperatingState(String mode) {
    switch (mode) {
        case "off":      return "idle"
        case "heat":     return "heating"
        case "cool":     return "cooling"
        case "fan_only": return "fan only"
        case "dry":      return "idle"
        default:         return null  // auto — let next poll derive from sensor vs setpoint
    }
}

void setThermostatFanMode(String fanMode) {
    if (fanMode != "auto") {
        logWarn "setThermostatFanMode(${fanMode}): only 'auto' is canonical — use setFanSpeed for quiet/low/medium/high"
        return
    }
    writeFanSpeed("auto")
}

void setFanSpeed(String speed) {
    if (!(speed in FUJITSU_ALL_FAN_SPEEDS)) {
        logWarn "setFanSpeed(${speed}): not supported"
        return
    }
    writeFanSpeed(speed)
}

private void writeFanSpeed(String speed) {
    Integer code = FAN_MODE_INV[speed]
    if (code == null) { logWarn "writeFanSpeed(${speed}): no int code"; return }
    logInfo "setting fan_speed -> ${speed} (${code})"
    parent?.sendCommand(device.deviceNetworkId, "fan_speed", code)
    if (!isOptimistic()) return
    sendEvent(name: "fanSpeed", value: speed,
              descriptionText: "${device} fanSpeed is ${speed}")
    if (speed == "auto") {
        sendEvent(name: "thermostatFanMode", value: "auto",
                  descriptionText: "${device} thermostatFanMode is auto")
    }
}

void setHeatingSetpoint(BigDecimal t) { handleSetSetpoint("heat", t) }
void setCoolingSetpoint(BigDecimal t) { handleSetSetpoint("cool", t) }

private void handleSetSetpoint(String role, BigDecimal t) {
    if (t == null) { logWarn "set${role.capitalize()}Setpoint(null) — ignored"; return }
    BigDecimal clamped = clampSetpoint(t)
    String attrName = "${role}ingSetpoint"
    sendEvent(name: attrName, value: clamped, unit: getTemperatureScale(),
              descriptionText: "${device} ${attrName} is ${clamped}${getTemperatureScale()}")

    String mode = device.currentValue("fujitsuMode")
    boolean writeToUnit = (mode == role) || (mode in ["auto", "dry", "off", null])
    if (!writeToUnit) {
        logInfo "${attrName} stored as preset; unit currently in ${mode} mode, not pushing to unit"
        return
    }
    pushSetpointToUnit(clamped)
}

private BigDecimal clampSetpoint(BigDecimal t) {
    BigDecimal lo = getTemperatureScale() == 'F' ? 61 : 16
    BigDecimal hi = getTemperatureScale() == 'F' ? 86 : 30
    BigDecimal clamped = t
    if (clamped < lo) { logWarn "setpoint ${t} below min ${lo} — clamped"; clamped = lo }
    if (clamped > hi) { logWarn "setpoint ${t} above max ${hi} — clamped"; clamped = hi }
    return clamped
}

private void pushSetpointToUnit(BigDecimal clamped) {
    BigDecimal aylaValue = scaleToAylaSetpoint(clamped)
    logInfo "setting adjust_temperature -> ${clamped}${getTemperatureScale()} (raw ${aylaValue})"
    parent?.sendCommand(device.deviceNetworkId, "adjust_temperature", aylaValue.toInteger())
    // thermostatSetpoint is the device-confirmed value — updated only by the next
    // poll, mirroring the built-in Ecobee integration model. heatingSetpoint /
    // coolingSetpoint are user-intent presets and update immediately at the call
    // site, regardless of the optimisticUpdates preference.
}

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
        sendEvent(name: "thermostatSetpoint", value: sp, unit: getTemperatureScale(),
                  descriptionText: "${device} thermostatSetpoint is ${sp}${getTemperatureScale()}")
        // Mirror to mode-specific slot. Bootstrap empty heat/cool attributes on first observation.
        String modeNow = fujMode ?: device.currentValue("fujitsuMode")
        if (modeNow == "heat" || device.currentValue("heatingSetpoint") == null) {
            sendEvent(name: "heatingSetpoint", value: sp, unit: getTemperatureScale(),
                      descriptionText: "${device} heatingSetpoint is ${sp}${getTemperatureScale()}")
        }
        if (modeNow == "cool" || device.currentValue("coolingSetpoint") == null) {
            sendEvent(name: "coolingSetpoint", value: sp, unit: getTemperatureScale(),
                      descriptionText: "${device} coolingSetpoint is ${sp}${getTemperatureScale()}")
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

// Sensor readings (display_temperature, outdoor_temperature) are in hundredths
// of °F on this unit. Empirically verified 2026-05-18 against ambient
// readings: raw 7000 = 70.0°F = 21.1°C (indoor display), raw 5500 = 55.0°F =
// 12.8°C (outdoor, matched ambient). Independent of the hub's temperature
// scale; we always convert to it.
//
// Note: ayla-iot-unofficial (the HA dependency) uses a linear range-map
// formula instead — raw [4000, 9500] -> [-10, +45]°C. Applying that to this
// unit produces values ~8°C off on outdoor. The Python lib's constants are
// presumably for a different model/firmware variant. If a future unit
// reports values outside the [3200, 11200] hundredths-of-°F range (-18°C to
// +49°C, well past mini-split sensor limits), the lib formula may need to
// be re-considered with a per-unit override.
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

// Copyright (c) 2026 PJ
// SPDX-License-Identifier: MIT

/*
 * Fujitsu Mini-Split — Child Driver
 *
 * One device per Fujitsu indoor unit. Standard Thermostat + TemperatureMeasurement
 * capabilities, plus a custom outdoorTemperature attribute. Receives state via
 * parent.updateState(Map); writes via parent.sendCommand(dni, name, intValue).
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

        attribute "outdoorTemperature", "number"
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

void installed() { logDebug "installed"; state.version = DRIVER_VERSION; initialize() }
void updated()   { logDebug "updated"; unschedule(); initialize() }
void initialize() {
    logDebug "initialize"
    if (state.version != DRIVER_VERSION) {
        logWarn "new version: ${DRIVER_VERSION} (was: ${state.version})"
        state.version = DRIVER_VERSION
    }
    setSupportedThermostatModes(JsonOutput.toJson(["off", "heat", "cool", "auto", "dry", "fan_only"]))
    setSupportedThermostatFanModes(JsonOutput.toJson(["auto", "quiet", "low", "medium", "high"]))
    emitBounds()
}

void refresh() {
    logDebug "refresh"
    parent?.refreshUnit(device.deviceNetworkId)
}

private void emitBounds() {
    String scale = location.temperatureScale
    sendEvent(name: 'minHeatingSetpoint', value: convertFromC(16), unit: scale)
    sendEvent(name: 'maxHeatingSetpoint', value: convertFromC(30), unit: scale)
    sendEvent(name: 'minCoolingSetpoint', value: convertFromC(18), unit: scale)
    sendEvent(name: 'maxCoolingSetpoint', value: convertFromC(30), unit: scale)
}

private BigDecimal convertFromC(BigDecimal celsius) {
    if (location.temperatureScale == 'F') {
        return (celsius * 9 / 5 + 32).setScale(1, java.math.RoundingMode.HALF_UP)
    }
    return celsius.setScale(1, java.math.RoundingMode.HALF_UP)
}

// Stubs to be implemented in later tasks. Hubitat platform requires all
// Thermostat / Thermostat Fan Mode capability commands to be defined on the
// driver; bodies land in Task 7.
void setThermostatMode(String mode)        { logWarn "setThermostatMode(${mode}) — not implemented yet" }
void setHeatingSetpoint(BigDecimal t)      { logWarn "setHeatingSetpoint(${t}) — not implemented yet" }
void setCoolingSetpoint(BigDecimal t)      { logWarn "setCoolingSetpoint(${t}) — not implemented yet" }
void setThermostatFanMode(String fanMode)  { logWarn "setThermostatFanMode(${fanMode}) — not implemented yet" }
void auto()           { setThermostatMode("auto") }
void cool()           { setThermostatMode("cool") }
void heat()           { setThermostatMode("heat") }
void off()            { setThermostatMode("off") }
void emergencyHeat()  { logWarn "emergencyHeat() not supported on Fujitsu mini-splits — routing to heat"; setThermostatMode("heat") }
void fanAuto()        { setThermostatFanMode("auto") }
void fanOn()          { logWarn "fanOn() not in Fujitsu fan set — routing to high"; setThermostatFanMode("high") }
void fanCirculate()   { logWarn "fanCirculate() not in Fujitsu fan set — routing to low"; setThermostatFanMode("low") }

// updateState lands in Task 6.
void updateState(Map data) { logTrace "updateState(${data}) — not implemented yet" }

private void logTrace(String msg) { if (settings.traceEnable) log.trace "${device} ${msg}" }
private void logDebug(String msg) { if (settings.debugEnable) log.debug "${device} ${msg}" }
private void logInfo(String msg)  { if (settings.txtEnable)   log.info  "${device} ${msg}" }
private void logWarn(String msg)  { log.warn  "${device} ${msg}" }
private void logError(String msg) { log.error "${device} ${msg}" }

// Copyright (c) 2026 PJ
// SPDX-License-Identifier: MIT

/*
 Stubborn Thermostat (test driver)

 A virtual thermostat that ignores the first N setpoint commands after setIgnore(N),
 for testing apps that check whether their writes took effect.
*/

metadata {
    definition(name: "Stubborn Thermostat", namespace: "iamtrep", author: "pj") {
        capability "Thermostat"
        capability "Actuator"
        command "setIgnore", [[name: "count*", type: "NUMBER"]]
        attribute "ignored", "number"
    }
}

void installed() { setThermostatMode("heat"); sendEvent(name: "heatingSetpoint", value: 20); sendEvent(name: "coolingSetpoint", value: 25); setIgnore(0) }
void setIgnore(count) { state.ignore = count as int; sendEvent(name: "ignored", value: 0) }
private boolean swallow() {
    if ((state.ignore ?: 0) > 0) { state.ignore = (state.ignore as int) - 1; sendEvent(name: "ignored", value: (device.currentValue("ignored") ?: 0) + 1); return true }
    return false
}
void setHeatingSetpoint(v) { if (!swallow()) sendEvent(name: "heatingSetpoint", value: v) }
void setCoolingSetpoint(v) { if (!swallow()) sendEvent(name: "coolingSetpoint", value: v) }
void setThermostatMode(String m) { sendEvent(name: "thermostatMode", value: m) }
void setThermostatFanMode(String m) { sendEvent(name: "thermostatFanMode", value: m) }
void setSchedule(s) { }
void heat() { setThermostatMode("heat") }
void cool() { setThermostatMode("cool") }
void auto() { setThermostatMode("auto") }
void off() { setThermostatMode("off") }
void emergencyHeat() { setThermostatMode("emergency heat") }
void fanOn() { setThermostatFanMode("on") }
void fanAuto() { setThermostatFanMode("auto") }
void fanCirculate() { setThermostatFanMode("circulate") }

// Copyright (c) 2026 PJ
// SPDX-License-Identifier: MIT

/*
 Virtual Heater Plug (Test)

 Test driver. A switch with a simulated power meter: on() draws the rated power,
 off() draws nothing. setStuck("true") makes on() and off() do nothing, as an
 unresponsive plug would; setUnplugged("true") makes it draw nothing while on, as a
 heater pulled from its plug would; setPower() reports any draw, as a welded relay
 would while off. commandCount counts on() and off() calls that took effect, for
 tests of commands that do not change the state.
*/

metadata {
    definition(name: "Virtual Heater Plug (Test)", namespace: "iamtrep", author: "pj",
               importUrl: "https://raw.githubusercontent.com/iamtrep/hubitat/main/drivers/tests/VirtualHeaterPlug.groovy") {
        capability "Actuator"
        capability "Switch"
        capability "PowerMeter"
        attribute "stuck", "enum", ["true", "false"]
        attribute "unplugged", "enum", ["true", "false"]
        attribute "commandCount", "number"
        command "setStuck", [[name: "stuck*", type: "ENUM", constraints: ["true", "false"]]]
        command "setUnplugged", [[name: "unplugged*", type: "ENUM", constraints: ["true", "false"]]]
        command "setPower", [[name: "watts*", type: "NUMBER"]]
        command "resetCommandCount"
    }
    preferences {
        input name: "ratedW", type: "number", title: "Draw while on (W)", defaultValue: 1000
    }
}

void installed() {
    sendEvent(name: "stuck", value: "false")
    sendEvent(name: "unplugged", value: "false")
    resetCommandCount()
    off()
}

void on() {
    if (stuck()) return
    countCommand()
    sendEvent(name: "switch", value: "on", descriptionText: "${device.displayName} was turned on")
    sendEvent(name: "power", value: drawWhenOn(), unit: "W")
}

void off() {
    if (stuck()) return
    countCommand()
    sendEvent(name: "switch", value: "off", descriptionText: "${device.displayName} was turned off")
    sendEvent(name: "power", value: 0, unit: "W")
}

void setStuck(String v) { sendEvent(name: "stuck", value: v, descriptionText: "${device.displayName} stuck ${v}") }

void setUnplugged(String v) {
    sendEvent(name: "unplugged", value: v, descriptionText: "${device.displayName} unplugged ${v}")
    if (device.currentValue("switch") == "on") sendEvent(name: "power", value: drawWhenOn(), unit: "W")
}

void setPower(BigDecimal w) { sendEvent(name: "power", value: w, unit: "W") }

void resetCommandCount() { sendEvent(name: "commandCount", value: 0) }

private void countCommand() { sendEvent(name: "commandCount", value: ((device.currentValue("commandCount") ?: 0) as Integer) + 1) }

private boolean stuck() { device.currentValue("stuck") == "true" }

private Integer drawWhenOn() { device.currentValue("unplugged") == "true" ? 0 : ((ratedW ?: 1000) as Integer) }

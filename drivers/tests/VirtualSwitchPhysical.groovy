// Copyright (c) 2026 PJ
// SPDX-License-Identifier: MIT

/*
 Virtual Switch with Physical Events

 Test driver. on() and off() send digital events, like a built-in Virtual Switch;
 physicalOn() and physicalOff() send the same events marked physical, as a paddle
 or button press would. For behavior tests of apps that treat a hand at the wall
 differently from an automation.
*/

metadata {
    definition(name: "Virtual Switch with Physical Events", namespace: "iamtrep", author: "pj",
               importUrl: "https://raw.githubusercontent.com/iamtrep/hubitat/main/drivers/tests/VirtualSwitchPhysical.groovy") {
        capability "Actuator"
        capability "Switch"
        command "physicalOn"
        command "physicalOff"
    }
}

void installed() { off() }

void on() { sendEvent(name: "switch", value: "on", type: "digital", descriptionText: "${device.displayName} was turned on") }
void off() { sendEvent(name: "switch", value: "off", type: "digital", descriptionText: "${device.displayName} was turned off") }
void physicalOn() { sendEvent(name: "switch", value: "on", type: "physical", descriptionText: "${device.displayName} was turned on [physical]") }
void physicalOff() { sendEvent(name: "switch", value: "off", type: "physical", descriptionText: "${device.displayName} was turned off [physical]") }

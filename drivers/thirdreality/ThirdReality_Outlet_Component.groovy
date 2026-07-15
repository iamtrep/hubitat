// Copyright (c) 2026 PJ
// SPDX-License-Identifier: MIT

/*
 *  Third Reality Outlet (Component) — child driver for one outlet of the
 *  Third Reality Dual Smart Plug (3RDP01072Z). The parent owns all Zigbee I/O;
 *  this child only relays commands up and displays events pushed down via parse().
 *
 *  Unlike the stock "Generic Component Metering Switch", this carries CurrentMeter and a
 *  powerFactor attribute for the parent's per-outlet current and power factor.
 *  Voltage and frequency are shared line measurements and live on the parent.
 */

metadata {
    definition(
        name: "Third Reality Outlet (Component)",
        namespace: "iamtrep",
        author: "pj",
        description: "Component child for one outlet of the Third Reality dual plug",
        importUrl: "https://raw.githubusercontent.com/iamtrep/hubitat/refs/heads/main/drivers/thirdreality/ThirdReality_Outlet_Component.groovy"
    ) {
        capability "Actuator"
        capability "Switch"
        capability "Outlet"
        capability "PowerMeter"
        capability "EnergyMeter"
        capability "CurrentMeter"
        capability "Refresh"

        attribute "powerFactor", "number"
    }
    preferences {
        input(name: "txtEnable", type: "bool", title: "<b>Enable descriptionText logging</b>", defaultValue: true)
    }
}

void installed() {
    sendEvent(name: "switch", value: "off")
}

// Events arrive from the parent as a list of attribute maps.
void parse(List<Map> events) {
    events.each { Map evt ->
        if (evt == null) return
        if (txtEnable && evt.descriptionText) log.info "${evt.descriptionText}"
        sendEvent(evt)
    }
}

void on()      { parent?.componentOn(this.device) }
void off()     { parent?.componentOff(this.device) }
void refresh() { parent?.componentRefresh(this.device) }

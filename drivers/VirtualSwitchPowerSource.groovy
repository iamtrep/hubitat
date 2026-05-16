// Copyright (c) 2025-2026 PJ
// SPDX-License-Identifier: MIT

/*
 Virtual Switch + PowerSource

 Virtual device that exposes both Switch and PowerSource capabilities with
 synchronized state. Designed for testing the Always-On Switch Monitor app's
 power outage detection via either capability.

 Sync logic:
   on()                      → switch=on,  powerSource=battery
   off()                     → switch=off, powerSource=mains
   setPowerSource("mains")   → switch=off, powerSource=mains
   setPowerSource(other)     → switch=on,  powerSource={value}
 */
metadata {
    definition(name: 'Virtual Switch + PowerSource', namespace: 'iamtrep', author: 'pj') {
        capability 'Switch'
        capability 'PowerSource'

        command 'setPowerSource', [[name: 'source', type: 'ENUM', constraints: ['mains', 'battery', 'dc', 'unknown'], description: 'Power source type']]
    }

    preferences {
        input name: 'txtEnable', type: 'bool', title: 'Enable descriptionText logging', defaultValue: true
    }
}

void installed() {
    sendEvent(name: 'switch', value: 'off', descriptionText: 'Initialized to off')
    sendEvent(name: 'powerSource', value: 'mains', descriptionText: 'Initialized to mains')
}

void updated() {
}

void parse(String description) {
}

void on() {
    String descriptionText = "${device.displayName} was turned on"
    sendEvent(name: 'switch', value: 'on', descriptionText: descriptionText)
    sendEvent(name: 'powerSource', value: 'battery', descriptionText: "${device.displayName} power source set to battery")
    if (txtEnable) { log.info descriptionText }
}

void off() {
    String descriptionText = "${device.displayName} was turned off"
    sendEvent(name: 'switch', value: 'off', descriptionText: descriptionText)
    sendEvent(name: 'powerSource', value: 'mains', descriptionText: "${device.displayName} power source set to mains")
    if (txtEnable) { log.info descriptionText }
}

void setPowerSource(String source) {
    String descriptionText = "${device.displayName} power source set to ${source}"
    sendEvent(name: 'powerSource', value: source, descriptionText: descriptionText)
    String switchValue = (source == 'mains') ? 'off' : 'on'
    sendEvent(name: 'switch', value: switchValue, descriptionText: "${device.displayName} was turned ${switchValue}")
    if (txtEnable) { log.info descriptionText }
}

// Copyright (c) 2025-2026 PJ
// SPDX-License-Identifier: MIT

/**
 * Virtual Well Pump Switch (Test Driver)
 *
 * Test fixture for apps/WellMonitor/WellMonitor.groovy. WellMonitor
 * subscribes to `power` events and also calls `pumpSwitch.off()` during
 * an emergency shutoff (WellMonitor.groovy:629), so the driver must
 * expose BOTH Power Meter and Switch capabilities. The stock
 * `Virtual Omni Sensor` has Power Meter but no Switch; the stock
 * `Virtual Switch` has Switch but no Power Meter. This combines them.
 *
 * Test commands:
 *   setPower(Number)  — drive the `power` attribute directly to simulate
 *                       a metered pump cycle. Does NOT modify `switch`.
 *   on() / off()      — toggle the `switch` attribute. `off()` also
 *                       drives `power` to 0 so the app's powerHandler
 *                       sees a real transition (mirrors what a real
 *                       smart plug would do when commanded off).
 *
 * Conventions match drivers/tests/VirtualMmwavePirSensor.groovy.
 */

metadata {
    definition(
        name: "Virtual Well Pump Switch (Test)",
        namespace: "iamtrep",
        author: "pj"
    ) {
        capability "Actuator"
        capability "Sensor"
        capability "Switch"
        capability "PowerMeter"

        command "setPower", [[name: "watts", type: "NUMBER"]]
    }
}

void installed() {
    sendEvent(name: "switch", value: "off")
    sendEvent(name: "power",  value: 0)
}

void on() {
    sendEvent(
        name: "switch",
        value: "on",
        descriptionText: "${device.displayName} switch is on",
        isStateChange: true
    )
}

void off() {
    sendEvent(
        name: "switch",
        value: "off",
        descriptionText: "${device.displayName} switch is off",
        isStateChange: true
    )
    // Mirror real metered-plug behavior: power drops to 0 when commanded off.
    // WellMonitor's emergency path calls pumpSwitch.off(); the follow-up
    // power=0 event is what trips handlePumpStopped (clears state.pumpRunning,
    // turns pumpActiveSwitch off, etc.).
    sendEvent(
        name: "power",
        value: 0,
        descriptionText: "${device.displayName} power is 0W",
        isStateChange: true
    )
}

void setPower(Number watts) {
    sendEvent(
        name: "power",
        value: watts,
        unit: "W",
        descriptionText: "${device.displayName} power is ${watts}W",
        isStateChange: true
    )
}

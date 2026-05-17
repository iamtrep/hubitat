// Copyright (c) 2025-2026 PJ
// SPDX-License-Identifier: MIT

/**
 * Virtual Flow Meter (Test Driver)
 *
 * Test fixture for apps/WellMonitor/WellMonitor.groovy. Mirrors the
 * attribute shape of the real driver (drivers/sinope/Sinope_VA422xZB)
 * so the app sees the same surface in tests as in production:
 *   - capability "LiquidFlowRate" → `rate` attribute (LPM)
 *   - non-standard `volume` attribute (Number, litres) — Sinope has no
 *     standard capability for cumulative volume, so the real driver
 *     declares it as a free attribute too (Sinope_VA422xZB.groovy:55).
 *
 * Test commands:
 *   setRate(Number)   — drive `rate` directly to start/stop a flow event
 *                       (WellMonitor's rateHandler triggers on 0↔non-0
 *                       transitions; powerOnThreshold/Off do NOT apply).
 *   setVolume(Number) — drive cumulative `volume` to simulate water
 *                       delivered during a pump cycle or flow event.
 */

metadata {
    definition(
        name: "Virtual Flow Meter (Test)",
        namespace: "iamtrep",
        author: "pj"
    ) {
        capability "Actuator"
        capability "Sensor"
        capability "LiquidFlowRate"

        attribute "volume", "number"

        command "setRate",   [[name: "lpm",    type: "NUMBER"]]
        command "setVolume", [[name: "litres", type: "NUMBER"]]
    }
}

void installed() {
    sendEvent(name: "rate",   value: 0, unit: "LPM")
    sendEvent(name: "volume", value: 0, unit: "L")
}

void setRate(Number lpm) {
    sendEvent(
        name: "rate",
        value: lpm,
        unit: "LPM",
        descriptionText: "${device.displayName} rate is ${lpm} LPM",
        isStateChange: true
    )
}

void setVolume(Number litres) {
    sendEvent(
        name: "volume",
        value: litres,
        unit: "L",
        descriptionText: "${device.displayName} volume is ${litres} L",
        isStateChange: true
    )
}

// Copyright (c) 2025-2026 PJ
// SPDX-License-Identifier: MIT

// Probe driver for the platform claims in ARCHITECTURE.md. Driven by
// apps/tests/test-architecture-claims.sh, which installs a second copy under
// another name so a device can be switched between the two.

import groovy.transform.Field

@Field static final String CODE_VERSION = "1.0.0"

metadata {
    definition(name: "Architecture Claims Probe Driver", namespace: "tests", author: "PJ") {
        capability "Actuator"
        attribute "probe", "string"
        command "emit", [[name: "value", type: "STRING"]]
        command "emitForced", [[name: "value", type: "STRING"]]
        command "markData", [[name: "value", type: "STRING"]]
        command "markState", [[name: "value", type: "STRING"]]
    }
}

void installed() { }
void updated() { }

void emit(String value) { sendEvent(name: "probe", value: value) }
void emitForced(String value) { sendEvent(name: "probe", value: value, isStateChange: true) }
void markData(String value) { device.updateDataValue("probeData", value) }
void markState(String value) { state.probeState = value }

void deviceTypeUpdated() {
    device.updateDataValue("probeTypeUpdated", now().toString())
}

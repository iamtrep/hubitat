// Copyright (c) 2025-2026 PJ
// SPDX-License-Identifier: MIT

// Child device for apps/tests/test-singlethreaded.sh. Its parent is either the
// Single Threaded Probe app or a Single Threaded Probe Driver device.

metadata {
    definition(name: "Single Threaded Probe Child", namespace: "tests", author: "PJ") {
        capability "Actuator"
        attribute "probe", "string"
        command "callParent", [[name: "tag", type: "STRING"], [name: "ms", type: "NUMBER"]]
        command "emit", [[name: "value", type: "STRING"]]
    }
}

void installed() { }
void updated() { }

void callParent(String tag, BigDecimal ms) { parent.childWork(tag, (ms ?: 0) as Integer, "childDev") }
void emit(String value) { sendEvent(name: "probe", value: value, isStateChange: true) }

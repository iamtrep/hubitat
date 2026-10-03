// Copyright (c) 2025-2026 PJ
// SPDX-License-Identifier: MIT

// Probe driver for the platform claims in ARCHITECTURE.md. Driven by
// apps/tests/test-architecture-claims.sh, which installs a second copy under
// another name so a device can be switched between the two.

import groovy.json.JsonOutput
import groovy.transform.Field

@Field static final String CODE_VERSION = "1.1.0"

metadata {
    definition(name: "Architecture Claims Probe Driver", namespace: "tests", author: "PJ") {
        capability "Actuator"
        attribute "probe", "string"
        command "emit", [[name: "value", type: "STRING"]]
        command "emitForced", [[name: "value", type: "STRING"]]
        command "markData", [[name: "value", type: "STRING"]]
        command "markState", [[name: "value", type: "STRING"]]
        command "setProbeDevice", [[name: "deviceId", type: "STRING"]]
        command "probeLimits", [[name: "tag", type: "STRING"]]
    }
    preferences {
        input name: "probeDevices", type: "capability.actuator", title: "Devices", multiple: true, required: false
    }
}

void installed() { }
void updated() { }

void emit(String value) { sendEvent(name: "probe", value: value) }
void emitForced(String value) { sendEvent(name: "probe", value: value, isStateChange: true) }
void markData(String value) { device.updateDataValue("probeData", value) }
void markState(String value) { state.probeState = value }

void setProbeDevice(String deviceId) {
    device.updateSetting("probeDevices", [type: "capability.actuator", value: [deviceId as Long]])
}

// App-only methods, called from a driver; results go to a data value so a driver switch can't clear them
void probeLimits(String tag) {
    Map r = [tag: tag, deviceSettingClass: settings.probeDevices == null ? null : getObjectClassName(settings.probeDevices)]
    try { subscribe(location, "mode", "noop"); r.subscribe = "ok" } catch (e) { r.subscribe = e.class.simpleName }
    try { getGlobalVar("probe"); r.getGlobalVar = "ok" } catch (e) { r.getGlobalVar = e.class.simpleName }
    device.updateDataValue("probeLimits", JsonOutput.toJson(r))
}

void noop(evt) { }

void deviceTypeUpdated() {
    device.updateDataValue("probeTypeUpdated", now().toString())
}

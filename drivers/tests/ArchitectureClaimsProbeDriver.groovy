// Copyright (c) 2025-2026 PJ
// SPDX-License-Identifier: MIT

// Probe driver for the platform claims in ARCHITECTURE.md. Driven by
// apps/tests/test-architecture-claims.sh, which installs a second copy under
// another name so a device can be switched between the two.

import groovy.json.JsonOutput
import groovy.transform.Field

@Field static final String CODE_VERSION = "1.2.0"

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
        command "clearLifecycle"
        command "probeZigbee", [[name: "tag", type: "STRING"]]
    }
    preferences {
        input name: "probeDevices", type: "capability.actuator", title: "Devices", multiple: true, required: false
    }
}

// Lifecycle marks are data values, which survive a driver switch while state does not
void installed() { device.updateDataValue("probeInstalled", now().toString()) }
void updated() { device.updateDataValue("probeUpdated", now().toString()) }
void configure() { device.updateDataValue("probeConfigured", now().toString()) }

void clearLifecycle() {
    ["probeInstalled", "probeUpdated", "probeConfigured", "probeTypeUpdated"].each { device.removeDataValue(it) }
}

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

// What the zigbee helper builds; nothing is sent
void probeZigbee(String tag) {
    Map r = [tag: tag]
    Closure<String> name = { Closure c -> try { def v = c(); return v == null ? "null" : v.toString() } catch (e) { return "EXC " + getObjectClassName(e) } }
    r.on = name { zigbee.on() }
    r.readAttribute = name { zigbee.readAttribute(0x0006, 0x0000) }
    r.swapOctets8 = name { zigbee.swapOctets("12345678") }
    r.clusterByHex = name { zigbee.clusterLookup("0x0006") }
    r.clusterByName = name { zigbee.clusterLookup("ON_OFF") }
    r.bindConstant = name { zigbee.BIND_MANAGEMENT_CLUSTER }
    r.propertyCount = name { zigbee.properties.size() }
    Map desc = zigbee.parseDescriptionAsMap("read attr - raw: 0B6301000608000010010000, dni: 0B63, endpoint: 01, cluster: 0006, size: 08, attrId: 0000, encoding: 10, command: 01, value: 01")
    r.descKeys = (desc?.keySet() ?: []).collect { it.toString() }.sort()
    device.updateDataValue("probeZigbee", JsonOutput.toJson(r))
}

void deviceTypeUpdated() {
    device.updateDataValue("probeTypeUpdated", now().toString())
}

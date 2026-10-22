// Copyright (c) 2025-2026 PJ
// SPDX-License-Identifier: MIT

/*
 *  Zigbee Workbench Monitor: optional child device of the Zigbee Workbench app, one per monitored
 *  Zigbee device.
 *
 *  Output only: publishes a few values the app computes (health, neighbour, child and route
 *  counts, parent, lowest LQI, time of the last poll) for dashboards and rules. The app page shows
 *  all of them and more; this device has no inspection commands beyond Refresh.
 */

import groovy.transform.Field

@Field static final String APP_NAME = "Zigbee Workbench"
@Field static final String CODE_VERSION = "0.1.0"

@Field static final List<String> VALUE_NAMES = ['healthStatus', 'neighborCount', 'childCount', 'routeCount', 'parent', 'lowestLqi', 'lastPoll']

metadata {
    definition(name: "Zigbee Workbench Monitor", namespace: "iamtrep", author: "pj",
               importUrl: "https://raw.githubusercontent.com/iamtrep/hubitat/main/apps/ZigbeeWorkbench/ZigbeeWorkbenchMonitor.groovy") {
        capability "Sensor"
        capability "Refresh"

        // healthStatus is a convention, not a capability
        attribute "healthStatus", "enum", ["online", "offline", "unknown"]
        attribute "neighborCount", "number"
        attribute "childCount", "number"
        attribute "routeCount", "number"
        attribute "parent", "string"
        attribute "lowestLqi", "string"
        attribute "lastPoll", "string"
    }

    preferences {
        input name: "txtEnable", type: "bool", title: "<b>Enable info logging</b>", defaultValue: true
        input name: "debugEnable", type: "bool", title: "<b>Enable debug logging</b>",
              description: "Auto-disables after 30 minutes.", defaultValue: false
    }
}

// LIFECYCLE

void installed() {
    logCfg "installed v${CODE_VERSION}"
    state.version = CODE_VERSION
    sendEvent(name: "healthStatus", value: "unknown")
}

void updated() {
    logCfg "updated"
    unschedule()
    if (state.version != CODE_VERSION) {
        logVer "New version: ${CODE_VERSION} (was: ${state.version})"
        state.version = CODE_VERSION
    }
    if (debugEnable) runIn(1800, "logsOff")
}

void logsOff() {
    logWarn "debug logging disabled"
    device.updateSetting("debugEnable", [value: "false", type: "bool"])
}

// FROM THE APP

// Sends the values that changed; unknown names and null values are ignored. Called by the app,
// so it never calls the app back.
void updateValues(Map v) {
    (v ?: [:]).each { k, val ->
        String name = k.toString()
        if (val == null || !(name in VALUE_NAMES)) return
        Object cur = device.currentValue(name, true)
        if (cur != null && sameValue(cur, val)) return
        sendEvent(name: name, value: val, descriptionText: "${device.displayName} ${name} is ${val}")
        if (name == 'healthStatus') logEvt "${name} is ${val}"
        else logDebug "${name} is ${val}"
    }
}

private static boolean sameValue(Object a, Object b) {
    if (a instanceof Number && b instanceof Number) return new BigDecimal(a.toString()) == new BigDecimal(b.toString())
    return a.toString() == b.toString()
}

// COMMANDS

// Asks the app to poll this device now. Runs in this device's own execution.
void refresh() {
    logCmd "refresh"
    parent?.monitorRefresh(device.deviceNetworkId)
}

// LOGGING

private String logp(String e) { "${e} ${device.displayName}: " }
void logEvt(String m)   { if (txtEnable != false) log.info logp("⬇️") + m }
void logCmd(String m)   { if (txtEnable != false) log.info logp("⬆️") + m }
void logCfg(String m)   { if (txtEnable != false) log.info logp("🔧") + m }
void logVer(String m)   { log.warn logp("🏷️") + m }
void logWarn(String m)  { log.warn logp("⚠️") + m }
void logDebug(String m) { if (debugEnable) log.debug "${device.displayName}: ${m}" }

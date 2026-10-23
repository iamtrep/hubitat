// Copyright (c) 2026 PJ
// SPDX-License-Identifier: MIT

/*
 * Hilo Winter Credit — Child Driver
 *
 * One device per Hilo location, created by Hilo Manager when its winter credit targets are on.
 * Shows the target of the next Hydro-Québec peak event (or, without one, of tomorrow morning as
 * if it were one), the plan for the anchor window before it, and the anchor window's energy so
 * far, so rules can act on them. The parent computes everything; the device holds no logic.
 */

import groovy.transform.Field

metadata {
    definition(
        name: "Hilo Winter Credit",
        namespace: "iamtrep",
        author: "pj",
        importUrl: "https://raw.githubusercontent.com/iamtrep/hubitat/refs/heads/main/integrations/Hilo/HiloWinterCredit.groovy"
    ) {
        capability "Refresh"
        capability "Sensor"

        attribute "eventDate",           "string"
        attribute "window",              "enum", ["AM", "PM"]
        attribute "announced",           "enum", ["true", "false"]
        attribute "base",                "number"
        attribute "anchorReference",     "number"
        attribute "cappedTarget",        "number"
        attribute "anchorPlan",          "enum", ["at most", "at least"]
        attribute "anchorTarget",        "number"
        attribute "anchorUsed",          "number"
        attribute "anchorWindow",        "string"
        attribute "expectedCurtailment", "number"
        attribute "target",              "number"
        attribute "advice",              "enum", ["pending", "cut", "keep"]
        attribute "keepUse",             "number"
        attribute "referenceDays",       "string"
        attribute "summary",             "string"
    }

    preferences {
        input name: "txtEnable",   type: "bool", title: "Enable info logging", defaultValue: true
        input name: "debugEnable", type: "bool", title: "Enable debug logging", defaultValue: false
    }
}

@Field static final String CODE_VERSION = "0.1.0"
@Field static final int DEBUG_LOG_TIMEOUT = 1800
@Field static final List<String> KWH_ATTRS = ["base", "anchorReference", "cappedTarget", "anchorTarget", "anchorUsed",
                                              "expectedCurtailment", "target", "keepUse"]

void installed() { logDebug "installed"; state.version = CODE_VERSION }

void updated() {
    logDebug "updated"
    if (settings.debugEnable) runIn(DEBUG_LOG_TIMEOUT, "logsOff")
}

void refresh() { parent?.peakRefresh() }

// ── Parent interface ──────────────────────────────────────────────────

// Only the keys present are sent; a key present with a null value clears that attribute.
void updatePeak(Map m) {
    state.version = CODE_VERSION
    String before = device.currentValue("summary") as String
    m.each { String k, v ->
        if (v == null) { device.deleteCurrentState(k); return }
        if (KWH_ATTRS.contains(k)) sendEvent(name: k, value: v, unit: "kWh")
        else sendEvent(name: k, value: v)
    }
    if (m.summary && m.summary != before) logInfo m.summary as String
}

void logsOff() {
    logWarn "debug logging disabled"
    device.updateSetting("debugEnable", [value: "false", type: "bool"])
}

// ── Logging (driver) ──────────────────────────────────────────────────
//   ⚠️ Warn
private String logp(String e) { "${e} ${device.displayName}: " }

void logWarn (String m) { log.warn  logp('⚠️') + m }
void logInfo (String m) { if (txtEnable != false) log.info  "${device.displayName}: ${m}" }
void logDebug(String m) { if (settings.debugEnable) log.debug "${device.displayName}: ${m}" }

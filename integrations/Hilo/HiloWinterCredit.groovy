// Copyright (c) 2026 PJ
// SPDX-License-Identifier: MIT

/*
 * Hilo Winter Credit — Child Driver
 *
 * One device per Hilo location, created by Hilo Manager when its winter credit targets are on.
 * Shows the target of the next Hydro-Québec peak event (or, without one, of the current or next
 * window as if it were one), the plan for the anchor window before it, the energy so far of the
 * anchor window and the event, and the last finished window's result, so rules can act on them.
 * The parent computes everything; the device holds no logic.
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
        attribute "eventUsed",           "number"
        attribute "expectedCurtailment", "number"
        attribute "target",              "number"
        attribute "advice",              "enum", ["pending", "cut", "keep"]
        attribute "keepUse",             "number"
        attribute "referenceDays",       "string"
        attribute "summary",             "string"
        // The last finished window: "YYYY-MM-DD AM" or PM, whether it was announced, its result in
        // kWh and $, and whether Hilo's hourly values are all in (else the live count).
        attribute "lastWindow",          "string"
        attribute "lastAnnounced",       "enum", ["true", "false"]
        attribute "lastConsumed",        "number"
        attribute "lastCurtailed",       "number"
        attribute "lastCredit",          "number"
        attribute "lastFinal",           "enum", ["true", "false"]
    }

    preferences {
        input name: "txtEnable",   type: "bool", title: tr("log.info"), defaultValue: true
        input name: "debugEnable", type: "bool", title: tr("log.debug"), defaultValue: false
    }
}

@Field static final String CODE_VERSION = "0.1.0"

// Preference titles, in the language the parent app sets. Logs stay in English.
@Field static final Map<String, Map<String, String>> I18N = [
    en: [
        "log.info" : "Enable info logging",
        "log.debug": "Enable debug logging",
    ],
    fr: [
        "log.info" : "Activer les journaux d'information",
        "log.debug": "Activer les journaux de débogage",
    ],
] // end I18N
@Field static final int DEBUG_LOG_TIMEOUT = 1800
@Field static final List<String> KWH_ATTRS = ["base", "anchorReference", "cappedTarget", "anchorTarget", "anchorUsed",
                                              "eventUsed", "expectedCurtailment", "target", "keepUse", "lastConsumed", "lastCurtailed"]

void installed() { logDebug "installed"; state.version = CODE_VERSION }

void updated() {
    logDebug "updated"
    if (settings.debugEnable) runIn(DEBUG_LOG_TIMEOUT, "logsOff")
}

void refresh() { parent?.peakRefresh() }

// Called by the parent with its page's language.
void setLanguage(String lang) { state.uiLang = lang }

// state is empty while preferences are saved; the titles then don't show.
private String tr(String key) {
    return I18N[(state?.uiLang ?: "fr") as String]?.get(key) ?: I18N.en.get(key) ?: key
}

// ── Parent interface ──────────────────────────────────────────────────

// Only the keys present are sent; a key present with a null value clears that attribute.
// summaryLog, not an attribute, is the summary in English for the log.
void updatePeak(Map m) {
    state.version = CODE_VERSION
    String before = device.currentValue("summary") as String
    String summaryLog = m.summaryLog as String
    m.findAll { k, v -> k != "summaryLog" }.each { String k, v ->
        if (v == null) { device.deleteCurrentState(k); return }
        if (KWH_ATTRS.contains(k)) sendEvent(name: k, value: v, unit: "kWh")
        else if (k == "lastCredit") sendEvent(name: k, value: v, unit: "\$")
        else sendEvent(name: k, value: v)
    }
    if (m.summary && m.summary != before) logInfo((summaryLog ?: m.summary) as String)
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

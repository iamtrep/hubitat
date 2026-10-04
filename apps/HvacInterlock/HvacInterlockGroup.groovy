// Copyright (c) 2026 PJ
// SPDX-License-Identifier: MIT

/*
 HVAC Interlock Group

 One group of equipment that shares a season policy and the same openings. A permit
 group drives a status switch that its schedulers pause on; a direct group sets the
 thermostat mode itself. Publishes its state on a "<group> status" device.
*/

import com.hubitat.app.ChildDeviceWrapper
import groovy.transform.Field

@Field static final String CODE_VERSION = "0.1.0"

definition(
    name: "HVAC Interlock Group",
    namespace: "iamtrep",
    author: "pj",
    description: "One HVAC Interlock group: season policy and openings for a set of equipment",
    category: "Convenience",
    parent: "iamtrep:HVAC Interlock",
    singleThreaded: true,
    importUrl: "https://raw.githubusercontent.com/iamtrep/hubitat/main/apps/HvacInterlock/HvacInterlockGroup.groovy",
    iconUrl: "", iconX2Url: ""
)

preferences {
    page(name: "mainPage")
}

Map mainPage() {
    dynamicPage(name: "mainPage", install: true, uninstall: true) {
        section { label title: "Group name", required: true }
    }
}

void installed() { initialize() }
void updated() { unsubscribe(); unschedule(); initialize() }
void initialize() { }

// ── Core (pure) ───────────────────────────────────────────────────────
// Self-contained: arguments in, values out. No settings, state, devices or logs.
// tests/test_core.groovy parses and runs this block off-hub.

List<String> seasonList() { return ['winter', 'spring', 'summer', 'fall'] }

BigDecimal numOrNull(Object o) {
    if (o instanceof Number) return new BigDecimal(o.toString())
    if (o instanceof String && ((String) o).trim().isBigDecimal()) return new BigDecimal(((String) o).trim())
    return null
}

// Per-season policy from the settings map: booleans for permit, modes for direct.
Map policyFrom(String style, Map s) {
    Map out = [:]
    Map dflt = [winter: true, spring: true, summer: false, fall: true]
    seasonList().each { String k ->
        if (style == 'permit') {
            Object v = s["allow${k.capitalize()}".toString()]
            out[k] = v == null ? dflt[k] : (v == true || v == 'true')
        } else {
            out[k] = (s["mode${k.capitalize()}".toString()] ?: 'off') as String
        }
    }
    return out
}

// What the group wants this season: 'on'/'off' for permit, a mode or 'off' for direct.
// Null when the season is unknown: the caller then changes nothing.
String wantedFor(String style, Map policy, String season) {
    if (!seasonList().contains(season)) return null
    Object p = policy?.get(season)
    if (style == 'permit') return (p == true || p == 'true') ? 'on' : 'off'
    return ['heat', 'cool', 'auto'].contains(p) ? (p as String) : 'off'
}

Map effective(String wanted, boolean raised, String response) {
    if (wanted == 'off') return [state: 'off', blockReason: 'season']
    if (raised && response != 'warn') return [state: 'off', blockReason: 'openings']
    return [state: wanted, blockReason: 'none']
}

// ── End core ──────────────────────────────────────────────────────────

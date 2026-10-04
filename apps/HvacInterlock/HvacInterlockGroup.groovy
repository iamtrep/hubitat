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

// Raise when an opening has been open for the open delay while the group wants to run;
// clear when everything has been closed for the close delay, or when the group no longer
// wants to run. nextCheck is when the answer can next change without a new event.
Map alertStep(Map a) {
    boolean raised = a.raised == true
    long now = a.now as long
    Long openSince = a.openSince as Long
    Long closedSince = a.allClosedSince as Long
    if (a.wanted == 'off') return [raised: false, nextCheck: null]
    if (!raised) {
        if (openSince == null) return [raised: false, nextCheck: null]
        long due = openSince + (a.openDelayMs as long)
        return now >= due ? [raised: true, nextCheck: null] : [raised: false, nextCheck: due]
    }
    if (closedSince == null) return [raised: true, nextCheck: null]
    long due = closedSince + (a.closeDelayMs as long)
    return now >= due ? [raised: false, nextCheck: null] : [raised: true, nextCheck: due]
}

Long earliestOpen(List<Map> contacts) {
    List<Long> t = contacts.findAll { it.open }.collect { it.since as Long }.findAll { it != null }
    return t ? t.min() : null
}

String openList(List<Map> contacts) { return contacts.findAll { it.open }.collect { it.label as String }.sort().join(', ') }

String alertText(String group, String open) { return "${group}: ${open} open".toString() }

String clearText(String group) { return "${group}: alert cleared".toString() }

List<String> modeWrites(List<Map> thermostats, String target) {
    return thermostats.findAll { it.mode != target }.collect { it.id as String }
}

// supportedThermostatModes is a JSON-like list string; missing or empty means unknown.
boolean supportsMode(Object supported, String mode) {
    if (supported == null) return true
    List<String> modes = supported.toString().replaceAll(/[\[\]"\s]/, '').split(',').findAll { it }.collect { it as String }
    return modes.isEmpty() || modes.contains(mode)
}

long openDelayMs(Object minutes) {
    BigDecimal n = numOrNull(minutes)
    return n == null ? 600000L : (n * 60000).longValue()
}

long closeDelayMs(boolean debug, Object testSeconds) {
    BigDecimal n = debug ? numOrNull(testSeconds) : null
    return n == null ? 300000L : (n * 1000).longValue()
}

// ── End core ──────────────────────────────────────────────────────────

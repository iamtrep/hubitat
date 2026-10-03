// Copyright (c) 2026 PJ
// SPDX-License-Identifier: MIT

/*
 Thermostat Scheduler+ Program

 One zone: schedules thermostats from named profiles, with holds, mode overrides,
 an eco offset and pause handling. Publishes its state on a component device.
 Spec: docs/thermostat-scheduler-replacement-spec.md.
*/

import groovy.transform.Field

@Field static final String CODE_VERSION = "0.1.0"

definition(
    name: "Thermostat Scheduler+ Program",
    namespace: "iamtrep",
    author: "pj",
    description: "One Thermostat Scheduler+ program",
    category: "Convenience",
    parent: "iamtrep:Thermostat Scheduler+",
    importUrl: "https://raw.githubusercontent.com/iamtrep/hubitat/main/apps/ThermostatSchedulerPlus/ThermostatSchedulerPlusProgram.groovy",
    iconUrl: "", iconX2Url: ""
)

preferences {
    page(name: "mainPage")
}

Map mainPage() {
    dynamicPage(name: "mainPage", install: true, uninstall: true) {
        section { label title: "Program name", required: true }
    }
}

void installed() { initialize() }
void updated() { unsubscribe(); unschedule(); initialize() }
void initialize() { }

// ── Core (pure) ───────────────────────────────────────────────────────
// Self-contained: arguments in, values out. No settings, state, devices or logs.
// tests/test-core.groovy parses and runs this block off-hub.

Calendar calFor(String iso, TimeZone tz) {
    Calendar c = Calendar.getInstance(tz)
    c.clear()
    String[] p = iso.split('-')
    c.set(p[0] as int, (p[1] as int) - 1, p[2] as int, 0, 0, 0)
    return c
}

String isoDate(long t, TimeZone tz) {
    java.text.SimpleDateFormat f = new java.text.SimpleDateFormat('yyyy-MM-dd')
    f.setTimeZone(tz)
    return f.format(new Date(t))
}

String hhmmOf(long t, TimeZone tz) {
    java.text.SimpleDateFormat f = new java.text.SimpleDateFormat('HH:mm')
    f.setTimeZone(tz)
    return f.format(new Date(t))
}

String addDays(String iso, int n, TimeZone tz) {
    Calendar c = calFor(iso, tz)
    c.add(Calendar.DAY_OF_MONTH, n)
    return isoDate(c.getTimeInMillis(), tz)
}

int isoDow(String iso, TimeZone tz) {
    int d = calFor(iso, tz).get(Calendar.DAY_OF_WEEK)
    return d == Calendar.SUNDAY ? 7 : d - 1
}

long atLocal(String iso, String hhmm, TimeZone tz) {
    Calendar c = calFor(iso, tz)
    String[] p = hhmm.split(':')
    c.set(Calendar.HOUR_OF_DAY, p[0] as int)
    c.set(Calendar.MINUTE, p[1] as int)
    return c.getTimeInMillis()
}

BigDecimal numOrNull(Object o) {
    if (o instanceof Number) return new BigDecimal(o.toString())
    if (o instanceof String && ((String) o).trim().isBigDecimal()) return new BigDecimal(((String) o).trim())
    return null
}

// ── End core ──────────────────────────────────────────────────────────

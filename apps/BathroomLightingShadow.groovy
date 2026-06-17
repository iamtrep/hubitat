// Copyright (c) 2026 PJ
// SPDX-License-Identifier: MIT

import groovy.transform.Field

@Field static final String CODE_VERSION = "0.2.1"
@Field static final Integer SCORING_SCHEMA_VERSION = 1
@Field static final Integer RESOLVER_MAX_PER_TICK = 10

@Field static final List<Map> POLICIES = [
    [key: "hueOnly",     label: "Hue PIR only (baseline)",        onMethod: "policyHueOnly",     offHandler: "offCheckHueOnly",     defaultHoldSec: 300],
    [key: "fp300Hybrid", label: "FP300 motion OR presence",       onMethod: "policyFp300Hybrid", offHandler: "offCheckFp300Hybrid", defaultHoldSec: 600],
    [key: "fp300Mm",     label: "FP300 mmWave-only (presence)",   onMethod: "policyFp300Mm",     offHandler: "offCheckFp300Mm",     defaultHoldSec: 600],
    [key: "anyMotion",   label: "Any sensor fires",               onMethod: "policyAnyMotion",   offHandler: "offCheckAnyMotion",   defaultHoldSec: 600],
    [key: "composite",   label: "Composite + HFC hold",           onMethod: "policyComposite",   offHandler: "offCheckComposite",   defaultHoldSec: 1800],
]

definition(
    name: "Bathroom Lighting Shadow",
    namespace: "iamtrep",
    author: "pj",
    singleThreaded: true,
    description: "Runs multiple lighting-control policies in parallel against shared sensors, drives an auto-created virtual switch per policy, and scores each policy without touching real lights.",
    category: "Utility",
    importUrl: "https://raw.githubusercontent.com/iamtrep/hubitat/refs/heads/main/apps/BathroomLightingShadow.groovy",
    iconUrl: "",
    iconX2Url: ""
)

preferences {
    page(name: "mainPage")
    page(name: "recentEventsPage")
}

Map mainPage() {
    dynamicPage(name: "mainPage", title: "Bathroom Lighting Shadow v${CODE_VERSION}", install: true, uninstall: true) {
        section("Sensors") {
            input "hueMotion",      "capability.motionSensor",  title: "Hue PIR (over door)",                  required: false
            input "fp300Motion",    "capability.motionSensor",  title: "FP300 PIR channel",                    required: false
            input "fp300Presence",  "capability.motionSensor",  title: "FP300 mmWave channel (motion attr)",   required: false
            input "wallSwitch",     "capability.switch",        title: "Bathroom wall switch (ground truth)",  required: true
            input "doorContact",    "capability.contactSensor", title: "Bathroom door contact (optional)",     required: false
            input "hfcActive",      "capability.switch",        title: "HFC active indicator (optional)",      required: false
        }
        section("Policy hold seconds") {
            POLICIES.each { Map p ->
                input "policy_${p.key}_holdSec", "number", title: "${p.label}", defaultValue: p.defaultHoldSec
            }
        }
        section("Tunables") {
            input "wOn",     "number", title: "ON match window W_on (seconds)",            defaultValue: 60
            input "tQuiet",  "number", title: "Sensor-quiet threshold T_quiet (seconds)",  defaultValue: 60
            input "tForgot", "number", title: "User-forgot threshold T_forgot (seconds)",  defaultValue: 600
            input "tManualOverride", "number", title: "Manual-override threshold (seconds) — wall event N seconds after last motion classifies as manual override", defaultValue: 60
        }
        section("Results") {
            paragraph buildScoreTable()
            input "btnReset", "button", title: "Reset counters"
            href "recentEventsPage", title: "Recent events", description: "Last 100 entries in the ring buffer"
        }
        section("Diagnostics") {
            input "debugEnable", "bool", title: "Enable debug logging", defaultValue: false
        }
    }
}

Map recentEventsPage() {
    dynamicPage(name: "recentEventsPage", title: "Recent events") {
        section {
            paragraph buildRecentTable()
        }
    }
}

void installed()   { logDebug "installed()"; initialize() }
void updated()     { logDebug "updated()"; unsubscribe(); unschedule(); initialize() }
void uninstalled() {
    logDebug "uninstalled()"
    getChildDevices().each { deleteChildDevice(it.deviceNetworkId) }
}

void initialize() {
    logDebug "initialize()"
    checkVersion()
    ensurePolicyChildren()

    if (state.observingSince == null) state.observingSince = now()
    if (state.scores == null)         state.scores = [:]
    if (state.recent == null)         state.recent = []
    if (state.policyState == null)    state.policyState = [:]
    if (state.sensorState == null)    state.sensorState = [:]
    if (state.tLastActivity == null)  state.tLastActivity = null
    if (state.tQuietSince == null)    state.tQuietSince = null
    if (state.userForgotOff == null)  state.userForgotOff = [count: 0, lapseSecSum: 0L]

    POLICIES.each { Map p ->
        if (state.scores[p.key] == null) {
            state.scores[p.key] = [
                correctOn: 0, missedOn: 0, falseOn: 0,
                prematureOff: 0, correctOff_quietConfirmed: 0, correctOff_anticipatedUser: 0,
                overHold: 0,
                latencyMsSum: 0L, latencySamples: 0
            ]
        }
        if (state.policyState[p.key] == null) {
            state.policyState[p.key] = [decision: "off", lastTransitionTs: null, lastOnClass: null, transitions: []]
        }
    }

    if (hueMotion)     subscribe(hueMotion, "motion", "sensorHandler")
    if (fp300Motion)   subscribe(fp300Motion, "motion", "sensorHandler")
    if (fp300Presence) subscribe(fp300Presence, "motion", "sensorHandler")
    if (wallSwitch)    subscribe(wallSwitch, "switch", "wallSwitchHandler")
    if (doorContact)   subscribe(doorContact, "contact", "doorHandler")
    if (hfcActive)     subscribe(hfcActive, "switch", "hfcHandler")

    runIn((settings.wOn ?: 60) as Integer, "resolveUnresolvedOns")
}

private String childDni(String key) { "${app.id}-bls-${key}" }
private String childLabel(String key) { "${app.label} ${key}" }

private void ensurePolicyChildren() {
    POLICIES.each { Map p ->
        String dni = childDni(p.key)
        if (getChildDevice(dni) == null) {
            addChildDevice("hubitat", "Virtual Switch", dni,
                [name: childLabel(p.key), label: childLabel(p.key), isComponent: true])
            logInfo "created child ${childLabel(p.key)} (${dni})"
        }
    }
}

private void checkVersion() {
    if (state.version != CODE_VERSION) {
        logInfo "version ${state.version} -> ${CODE_VERSION}"
        state.version = CODE_VERSION
    }
    if (state.scoringSchemaVersion != SCORING_SCHEMA_VERSION) {
        logInfo "scoring schema ${state.scoringSchemaVersion} -> ${SCORING_SCHEMA_VERSION} — resetting scores"
        state.scores = [:]
        state.scoringSchemaVersion = SCORING_SCHEMA_VERSION
    }
}

private void logDebug(String msg) { if (debugEnable) log.debug msg }
private void logInfo(String msg)  { log.info msg }

void sensorHandler(evt) {
    state.sensorState[evt.device.id as String] = [name: evt.name, value: evt.value, ts: now()]
    recordEvent("sensor", evt.device.displayName, "${evt.name}=${evt.value}")
    refreshDerivedTimestamps()
    evaluatePolicies()
}

void wallSwitchHandler(evt) {
    recordEvent("wallSwitch", evt.device.displayName, evt.value)

    Long lastAct = state.tLastActivity as Long
    Long thresholdMs = ((settings.tManualOverride ?: 60) as Long) * 1000L
    if (lastAct == null || (now() - lastAct) > thresholdMs) {
        recordEvent("manualOverride", evt.device.displayName, "wallSwitch=${evt.value} (no recent sensor activity)")
        logInfo "manualOverride: wallSwitch ${evt.value} with no sensor activity in last ${thresholdMs}ms"
    }

    if (evt.value == "on") {
        scoreOn(now())
    } else if (evt.value == "off") {
        Long tQuietSince = state.tQuietSince as Long
        Long tForgotMs = ((settings.tForgot ?: 600) as Long) * 1000L
        if (tQuietSince != null && (now() - tQuietSince) >= tForgotMs) {
            if (state.userForgotOff == null) state.userForgotOff = [count: 0, lapseSecSum: 0L]
            Long lapseSec = ((now() - tQuietSince) / 1000L) as Long
            state.userForgotOff.count = (state.userForgotOff.count ?: 0) + 1
            state.userForgotOff.lapseSecSum = (state.userForgotOff.lapseSecSum ?: 0L) + lapseSec
            logInfo "userForgotOff: count=${state.userForgotOff.count} lapseSec=${lapseSec}"
        }
    }
}

private void scoreOn(Long wallOnTs) {
    Long windowMs = (settings.wOn ?: 60) * 1000L
    POLICIES.each { Map p ->
        Map ps = state.policyState[p.key]
        Map matchOn = ps.transitions?.reverse()?.find {
            it.edge == "on" && Math.abs((it.ts as Long) - wallOnTs) <= windowMs
        }
        Map s = state.scores[p.key]
        if (matchOn != null) {
            s.correctOn = (s.correctOn ?: 0) + 1
            Long lat = (matchOn.ts as Long) - wallOnTs
            // Latency: shadow earlier than wall = negative; positive = shadow late
            s.latencyMsSum = (s.latencyMsSum ?: 0L) + lat
            s.latencySamples = (s.latencySamples ?: 0) + 1
            ps.lastOnClass = "correctOn"
        } else {
            s.missedOn = (s.missedOn ?: 0) + 1
        }
    }
    logInfo "scoreOn: tallies updated; per-policy: ${state.scores.collectEntries { k, v -> [k, [correctOn: v.correctOn, missedOn: v.missedOn]] }}"
}

void doorHandler(evt) {
    recordEvent("door", evt.device.displayName, evt.value)
}

void hfcHandler(evt) {
    state.sensorState["hfc"] = [value: evt.value, ts: now()]
    recordEvent("hfc", evt.device.displayName, evt.value)
    evaluatePolicies()
}

private void recordEvent(String source, String device, String detail) {
    state.recent = ((state.recent ?: []) + [[ts: now(), source: source, device: device, detail: detail]]).takeRight(100)
}

private Map snapshot() {
    [
        hueMotion:     hueMotion?.currentValue("motion"),
        fp300Motion:   fp300Motion?.currentValue("motion"),
        fp300Presence: fp300Presence?.currentValue("motion"),
        wallSwitch:    wallSwitch?.currentValue("switch"),
        doorContact:   doorContact?.currentValue("contact"),
        hfcActive:     hfcActive?.currentValue("switch"),
    ]
}

Boolean policyHueOnly(Map snap) {
    return snap.hueMotion == "active"
}

Boolean policyFp300Hybrid(Map snap) {
    return snap.fp300Motion == "active" || snap.fp300Presence == "active"
}

Boolean policyFp300Mm(Map snap) {
    return snap.fp300Presence == "active"
}

Boolean policyAnyMotion(Map snap) {
    return snap.hueMotion == "active" || snap.fp300Motion == "active" || snap.fp300Presence == "active"
}

Boolean policyComposite(Map snap) {
    boolean anyActive = (snap.hueMotion == "active" || snap.fp300Motion == "active" || snap.fp300Presence == "active")
    if (anyActive) return true
    if (snap.hfcActive == "on") return true
    return false
}

private void evaluatePolicies() {
    Map snap = snapshot()
    POLICIES.each { Map p ->
        // Don't use `as Boolean` — it coerces null to false and breaks the abstain contract.
        Boolean wantOn = (Boolean) this."${p.onMethod}"(snap)
        Map ps = state.policyState[p.key]
        // Re-sync decision tracker with the child switch's actual state. The
        // child is the source of truth; ps.decision mirrors it. Handles drift
        // from test setups, manual overrides, and code pushes.
        def child = getChildDevice(childDni(p.key))
        String swState = child?.currentValue("switch")
        if (swState != null && swState != ps.decision) {
            ps.decision = swState
        }
        String current = ps.decision
        if (wantOn == null) return  // abstain
        if (wantOn && current == "off") {
            drivePolicy(p.key, "on")
        } else if (!wantOn && current == "on") {
            // Same-handler-name reschedule auto-cancels the previous runIn.
            Integer holdSec = (settings."policy_${p.key}_holdSec" ?: p.defaultHoldSec) as Integer
            runIn(holdSec, p.offHandler as String)
        }
    }
}

private void drivePolicy(String key, String edge) {
    def child = getChildDevice(childDni(key))
    if (child == null) return
    if (edge == "on") child.on() else child.off()
    Map ps = state.policyState[key]
    String prev = ps.decision
    ps.decision = edge
    ps.lastTransitionTs = now()
    ps.transitions = ((ps.transitions ?: []) + [[ts: now(), edge: edge]]).takeRight(50)
    logInfo "policy ${key} -> ${edge}"
    if (edge == "off" && prev == "on") {
        classifyOff(key, now() as Long)
    } else if (edge == "on") {
        ps.lastOnClass = null  // pending — resolver or scoreOn will set it
    }
}

// NOTE: prematureOff is unreachable for all currently-defined policies because each policy's
// on-condition is sensor-driven; the off-check re-evaluates and stays ON if sensors are still firing.
// The counter exists to catch future policies (e.g., time-of-day) that might decide OFF
// without consulting presence.
private void classifyOff(String key, Long tOff) {
    Map ps = state.policyState[key]
    if (ps.lastOnClass == "falseOn") return  // bookkeeping-only — don't score

    Long tQuietMs = (settings.tQuiet ?: 60) * 1000L
    Long lastAct = state.tLastActivity as Long
    boolean prematureCondition = lastAct != null && (tOff - lastAct) < tQuietMs

    Map s = state.scores[key]
    String offClass
    if (prematureCondition) {
        s.prematureOff = (s.prematureOff ?: 0) + 1
        offClass = "prematureOff"
    } else {
        String wallState = wallSwitch?.currentValue("switch")
        if (wallState == "off") {
            s.correctOff_quietConfirmed = (s.correctOff_quietConfirmed ?: 0) + 1
            offClass = "correctOff_quietConfirmed"
        } else {
            s.correctOff_anticipatedUser = (s.correctOff_anticipatedUser ?: 0) + 1
            offClass = "correctOff_anticipatedUser"
        }
    }
    ps.lastOffClass = offClass
    logInfo "classifyOff ${key} class=${offClass}"
}

void offCheckHueOnly()     { reevaluateOff("hueOnly") }
void offCheckFp300Hybrid() { reevaluateOff("fp300Hybrid") }
void offCheckFp300Mm()     { reevaluateOff("fp300Mm") }
void offCheckAnyMotion()   { reevaluateOff("anyMotion") }
void offCheckComposite()   { reevaluateOff("composite") }

private void reevaluateOff(String key) {
    Map p = POLICIES.find { it.key == key }
    Map snap = snapshot()
    Boolean wantOn = (Boolean) this."${p.onMethod}"(snap)
    if (wantOn == null) return
    Map ps = state.policyState[key]
    if (!wantOn && ps.decision == "on") {
        drivePolicy(key, "off")
    } else if (wantOn && ps.decision == "on") {
        logDebug "policy ${key}: off-check fired but policy still wants ON"
    }
}

private boolean anyPresenceActive() {
    return (hueMotion?.currentValue("motion") == "active") ||
           (fp300Motion?.currentValue("motion") == "active") ||
           (fp300Presence?.currentValue("motion") == "active")
}

private void refreshDerivedTimestamps() {
    if (anyPresenceActive()) {
        state.tLastActivity = now()
        state.tQuietSince = null
    } else if (state.tQuietSince == null) {
        state.tQuietSince = now()
    }
}

void resolveUnresolvedOns() {
    Long windowMs = (settings.wOn ?: 60) * 1000L
    Long cutoff = now() - windowMs
    int processed = 0
    POLICIES.each { Map p ->
        if (processed >= RESOLVER_MAX_PER_TICK) return
        Map ps = state.policyState[p.key]
        boolean falseOnChanged = false
        ps.transitions?.each { Map t ->
            if (processed >= RESOLVER_MAX_PER_TICK) return
            if (t.edge != "on") return
            if ((t.ts as Long) > cutoff) return  // window not yet closed
            if (t.resolved == true) return
            if (ps.lastOnClass != "correctOn" || ps.lastTransitionTs != t.ts) {
                Map s = state.scores[p.key]
                s.falseOn = (s.falseOn ?: 0) + 1
                if (ps.decision == "on" && ps.lastTransitionTs == t.ts) {
                    ps.lastOnClass = "falseOn"
                }
                falseOnChanged = true
            }
            t.resolved = true
            processed++
        }
        if (falseOnChanged) {
            logInfo "falseOn classified for policy ${p.key} (count=${state.scores[p.key].falseOn})"
        }

        // overHold: policy is ON, wall switch is OFF, room has been quiet > holdSec.
        // Latch via overHoldFlagged so we only count once per ON-cycle.
        if (ps.decision == "on") {
            String wallState = wallSwitch?.currentValue("switch")
            Long tQuietSince = state.tQuietSince as Long
            if (wallState == "off" && tQuietSince != null) {
                Integer holdSec = (settings."policy_${p.key}_holdSec" ?: p.defaultHoldSec) as Integer
                if ((now() - tQuietSince) > (holdSec * 1000L)) {
                    if (ps.overHoldFlagged != true) {
                        Map s = state.scores[p.key]
                        s.overHold = (s.overHold ?: 0) + 1
                        ps.overHoldFlagged = true
                        logInfo "overHold classified for policy ${p.key} (count=${s.overHold})"
                    }
                }
            }
        } else {
            ps.overHoldFlagged = false
        }
    }
    runIn((settings.wOn ?: 60) as Integer, "resolveUnresolvedOns")
}

private String buildScoreTable() {
    Long count = (state.userForgotOff?.count ?: 0) as Long
    Long avgLapse = count > 0 ? Math.round((state.userForgotOff.lapseSecSum / count) / 60.0) : 0
    StringBuilder sb = new StringBuilder()

    sb << "<p><b>userForgotOff:</b> count=${count}, avgLapseMin=${avgLapse}</p>"
    sb << "<table border='1' cellpadding='4' style='border-collapse:collapse;'>"
    sb << "<tr><th>policy</th><th>correctOn</th><th style='background:#fdd'>missedOn</th><th>falseOn</th>"
    sb << "<th style='background:#fdd'>prematureOff</th><th>correctOff_qC</th><th style='background:#dfd'>correctOff_aU</th>"
    sb << "<th>overHold</th><th>avgLatencyMs</th></tr>"
    POLICIES.each { Map p ->
        if (state.scores == null) return
        Map s = state.scores[p.key]
        if (s == null) return
        Long avgLat = s.latencySamples > 0 ? Math.round(s.latencyMsSum / s.latencySamples) : 0L
        sb << "<tr><td>${p.label}</td><td>${s.correctOn}</td><td style='background:#fdd'>${s.missedOn}</td><td>${s.falseOn}</td>"
        sb << "<td style='background:#fdd'>${s.prematureOff}</td><td>${s.correctOff_quietConfirmed}</td><td style='background:#dfd'>${s.correctOff_anticipatedUser}</td>"
        sb << "<td>${s.overHold}</td><td>${avgLat}</td></tr>"
    }
    sb << "</table>"
    Long since = state.observingSince as Long
    if (since != null) {
        sb << "<p><i>Observing since ${new Date(since)}</i></p>"
    }
    return sb.toString()
}

private String buildRecentTable() {
    StringBuilder sb = new StringBuilder()
    sb << "<table border='1' cellpadding='4' style='border-collapse:collapse;'>"
    sb << "<tr><th>ts</th><th>source</th><th>device</th><th>detail</th></tr>"
    (state.recent ?: []).reverse().each { Map e ->
        sb << "<tr><td>${new Date(e.ts as Long)}</td><td>${e.source}</td><td>${e.device}</td><td>${e.detail}</td></tr>"
    }
    sb << "</table>"
    return sb.toString()
}

void appButtonHandler(String btn) {
    if (btn == "btnReset") {
        logInfo "reset counters"
        POLICIES.each { Map p ->
            state.scores[p.key] = [
                correctOn: 0, missedOn: 0, falseOn: 0,
                prematureOff: 0, correctOff_quietConfirmed: 0, correctOff_anticipatedUser: 0,
                overHold: 0,
                latencyMsSum: 0L, latencySamples: 0
            ]
        }
        state.userForgotOff = [count: 0, lapseSecSum: 0L]
        state.observingSince = now()
    }
}

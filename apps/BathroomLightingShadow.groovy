// Copyright (c) 2026 PJ
// SPDX-License-Identifier: MIT

import groovy.transform.Field
import groovy.transform.CompileStatic

@Field static final String CODE_VERSION = "0.1.0"
@Field static final Integer SCORING_SCHEMA_VERSION = 1

@Field static final List<Map> POLICIES = [
    [key: "hueOnly",     label: "Hue PIR only (baseline)",        onMethod: "policyHueOnly",     offHandler: "offCheckHueOnly",     defaultHoldSec: 300],
    [key: "fp300Hybrid", label: "FP300 motion OR presence",       onMethod: "policyFp300Hybrid", offHandler: "offCheckFp300Hybrid", defaultHoldSec: 600],
    [key: "fp300Mm",     label: "FP300 mmWave-only (presence)",   onMethod: "policyFp300Mm",     offHandler: "offCheckFp300Mm",     defaultHoldSec: 600],
    [key: "anyMotion",   label: "Any sensor fires",               onMethod: "policyAnyMotion",   offHandler: "offCheckAnyMotion",   defaultHoldSec: 600],
    [key: "composite",   label: "Composite + HFC/humidity hold",  onMethod: "policyComposite",   offHandler: "offCheckComposite",   defaultHoldSec: 1800],
]

definition(
    name: "Bathroom Lighting Shadow",
    namespace: "iamtrep",
    author: "pj",
    singleThreaded: true,
    description: "Runs multiple lighting-control policies in parallel against shared sensors, drives a virtual switch per policy, and scores each policy without touching real lights.",
    category: "Utility",
    importUrl: "https://raw.githubusercontent.com/iamtrep/hubitat/refs/heads/main/apps/BathroomLightingShadow.groovy",
    iconUrl: "",
    iconX2Url: ""
)

preferences {
    page(name: "mainPage")
}

Map mainPage() {
    dynamicPage(name: "mainPage", title: "Bathroom Lighting Shadow v${CODE_VERSION}", install: true, uninstall: true) {
        section("Sensors") {
            input "hueMotion",      "capability.motionSensor",                title: "Hue PIR (over door)",              required: false
            input "fp300Motion",    "capability.motionSensor",                title: "FP300 PIR channel",                required: false
            input "fp300Presence",  "capability.motionSensor",                title: "FP300 mmWave channel (motion attr)", required: false
            input "wallSwitch",     "capability.switch",                      title: "Bathroom wall switch (ground truth)", required: true
            input "doorContact",    "capability.contactSensor",               title: "Bathroom door contact (optional)", required: false
            input "humiditySensor", "capability.relativeHumidityMeasurement", title: "Bathroom humidity sensor (optional)", required: false
            input "hfcActive",      "capability.switch",                      title: "HFC active indicator (optional)",  required: false
        }
        section("Policies") {
            POLICIES.each { Map p ->
                input "policy_${p.key}_enabled",    "bool",            title: "${p.label}: enabled", defaultValue: false
                input "policy_${p.key}_outputSwitch", "capability.switch", title: "${p.label}: output virtual switch (pick a System Virtual Switch)", required: false
                input "policy_${p.key}_holdSec",    "number",          title: "${p.label}: hold seconds", defaultValue: p.defaultHoldSec
            }
        }
        section("Tunables") {
            input "wOn",     "number", title: "ON match window W_on (seconds)",         defaultValue: 60
            input "tQuiet",  "number", title: "Sensor-quiet threshold T_quiet (seconds)", defaultValue: 60
            input "tForgot", "number", title: "User-forgot threshold T_forgot (seconds)", defaultValue: 600
        }
        section("Diagnostics") {
            input "debugEnable", "bool", title: "Enable debug logging", defaultValue: false
        }
    }
}

void installed() { logDebug "installed()"; initialize() }
void updated()   { logDebug "updated()"; unsubscribe(); unschedule(); initialize() }
void uninstalled() { logDebug "uninstalled()" }

void initialize() {
    logDebug "initialize()"
    checkVersion()

    if (state.observingSince == null) state.observingSince = now()
    if (state.scores == null)         state.scores = [:]
    if (state.recent == null)         state.recent = []
    if (state.policyState == null)    state.policyState = [:]
    if (state.sensorState == null)    state.sensorState = [:]
    if (state.tLastActivity == null)  state.tLastActivity = null
    if (state.tQuietSince == null)    state.tQuietSince = null
    if (state.humidityBaseline == null) state.humidityBaseline = null  // null until first reading

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
    if (humiditySensor) subscribe(humiditySensor, "humidity", "humidityHandler")
    if (hfcActive)     subscribe(hfcActive, "switch", "hfcHandler")

    // resolveUnresolvedOns kickoff disabled — flooded sendEvent with backlogged
    // transitions and hit LimitExceededException. Will re-enable with throttling.
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
    if (evt.value == "on") {
        scoreOn(now())
    }
    // OFF scoring is folded into the policy-OFF path (Task 10); the wall switch state at
    // policy-OFF time disambiguates correctOff_quietConfirmed vs correctOff_anticipatedUser.
    // The wall-switch OFF edge is also consulted by userForgotOff (Task 13).
}

private void scoreOn(Long wallOnTs) {
    Long windowMs = (settings.wOn ?: 60) * 1000L
    POLICIES.each { Map p ->
        if (!policyEnabled(p.key)) return
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
        emitScore(p.key)
    }
    logInfo "scoreOn: tallies updated; per-policy: ${state.scores.collectEntries { k, v -> [k, [correctOn: v.correctOn, missedOn: v.missedOn]] }}"
}

private void emitScore(String key) {
    Map s = state.scores[key]
    Map ps = state.policyState[key]
    sendEvent(name: "policy_${key}_correctOn",    value: s.correctOn)
    sendEvent(name: "policy_${key}_missedOn",     value: s.missedOn)
    sendEvent(name: "policy_${key}_falseOn",      value: s.falseOn)
    sendEvent(name: "policy_${key}_prematureOff", value: s.prematureOff)
    sendEvent(name: "policy_${key}_correctOff_quietConfirmed",  value: s.correctOff_quietConfirmed)
    sendEvent(name: "policy_${key}_correctOff_anticipatedUser", value: s.correctOff_anticipatedUser)
    sendEvent(name: "policy_${key}_overHold", value: s.overHold)
    Long avg = s.latencySamples > 0 ? Math.round(s.latencyMsSum / s.latencySamples) : 0L
    sendEvent(name: "policy_${key}_avgLatencyMs", value: avg)
    sendEvent(name: "policy_${key}_lastDecision", value: "${ps.decision}@${ps.lastTransitionTs ?: 0}")
}

void doorHandler(evt) {
    recordEvent("door", evt.device.displayName, evt.value)
}

void humidityHandler(evt) {
    BigDecimal h = evt.value as BigDecimal
    state.sensorState["humidity"] = [value: h, ts: now()]
    BigDecimal prev = state.humidityBaseline as BigDecimal
    // EMA alpha = 0.05 — slow baseline (~20 readings to track a step change)
    state.humidityBaseline = (prev == null) ? h : (prev * 0.95 + h * 0.05)
    recordEvent("humidity", evt.device.displayName, evt.value)
    evaluatePolicies()
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
        humidity:      (humiditySensor?.currentValue("humidity") as BigDecimal),
        hfcActive:     hfcActive?.currentValue("switch"),
        humidityBaseline: (state.humidityBaseline as BigDecimal),
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

private boolean policyEnabled(String key) {
    return settings."policy_${key}_enabled" == true && settings."policy_${key}_outputSwitch" != null
}

private void evaluatePolicies() {
    Map snap = snapshot()
    POLICIES.each { Map p ->
        if (!policyEnabled(p.key)) return
        // Don't use `as Boolean` here — it coerces null to false and silently
        // breaks the abstain contract that the next branch relies on.
        Boolean wantOn = (Boolean) this."${p.onMethod}"(snap)
        Map ps = state.policyState[p.key]
        // Re-sync the decision tracker with the actual output switch state.
        // The output switch is the source of truth; ps.decision mirrors it.
        // Handles drift from test setups, manual overrides, and code pushes.
        String swState = settings."policy_${p.key}_outputSwitch"?.currentValue("switch")
        if (swState != null && swState != ps.decision) {
            ps.decision = swState
        }
        String current = ps.decision
        if (wantOn == null) return  // abstain
        if (wantOn && current == "off") {
            drivePolicy(p.key, "on")
        } else if (!wantOn && current == "on") {
            // Schedule the off-check; same-handler-name overwrite cancels-and-rearms for free.
            Integer holdSec = (settings."policy_${p.key}_holdSec" ?: p.defaultHoldSec) as Integer
            runIn(holdSec, p.offHandler as String)
        }
    }
}

private void drivePolicy(String key, String edge) {
    def sw = settings."policy_${key}_outputSwitch"
    if (sw == null) return
    if (edge == "on") sw.on() else sw.off()
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
    // Surface the latest OFF classification as its own attribute — both a stable
    // observable for behavior tests and a useful operator-visible signal.
    sendEvent(name: "policy_${key}_lastOffClass", value: offClass)
    emitScore(key)
    logInfo "classifyOff ${key}: prematureOff=${s.prematureOff} correctOff_quietConfirmed=${s.correctOff_quietConfirmed} correctOff_anticipatedUser=${s.correctOff_anticipatedUser}"
}

void offCheckHueOnly()     { reevaluateOff("hueOnly") }
void offCheckFp300Hybrid() { reevaluateOff("fp300Hybrid") }
void offCheckFp300Mm()     { reevaluateOff("fp300Mm") }
void offCheckAnyMotion()   { reevaluateOff("anyMotion") }
void offCheckComposite()   { reevaluateOff("composite") }

private void reevaluateOff(String key) {
    if (!policyEnabled(key)) return
    Map p = POLICIES.find { it.key == key }
    Map snap = snapshot()
    Boolean wantOn = (Boolean) this."${p.onMethod}"(snap)
    if (wantOn == null) return
    Map ps = state.policyState[key]
    if (!wantOn && ps.decision == "on") {
        drivePolicy(key, "off")
    } else if (wantOn && ps.decision == "on") {
        // Sensor became active again before hold expired — stay on, no action.
        logDebug "policy ${key}: off-check fired but policy still wants ON"
    }
}

@Field static final BigDecimal HUMIDITY_OVER_BASELINE = 5.0  // %RH above baseline = "shower active"

Boolean policyComposite(Map snap) {
    boolean anyActive = (snap.hueMotion == "active" || snap.fp300Motion == "active" || snap.fp300Presence == "active")
    if (anyActive) return true
    if (snap.hfcActive == "on") return true
    BigDecimal h = snap.humidity as BigDecimal
    BigDecimal baseline = snap.humidityBaseline as BigDecimal
    if (h != null && baseline != null && h > baseline + HUMIDITY_OVER_BASELINE) return true
    return false
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

// DISABLED: this method flooded sendEvent and hit LimitExceededException.
// Kept as a method body that breaks the runIn self-rescheduling chain on next firing.
// Will be re-enabled with throttling + transition-resolved-marker hygiene in a follow-up.
void resolveUnresolvedOns() {
    unschedule("resolveUnresolvedOns")
    logInfo "resolveUnresolvedOns disabled — exiting without rescheduling"
}

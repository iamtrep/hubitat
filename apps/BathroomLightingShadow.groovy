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
}

void wallSwitchHandler(evt) {
    recordEvent("wallSwitch", evt.device.displayName, evt.value)
}

void doorHandler(evt) {
    recordEvent("door", evt.device.displayName, evt.value)
}

void humidityHandler(evt) {
    state.sensorState["humidity"] = [value: (evt.value as BigDecimal), ts: now()]
    recordEvent("humidity", evt.device.displayName, evt.value)
}

void hfcHandler(evt) {
    state.sensorState["hfc"] = [value: evt.value, ts: now()]
    recordEvent("hfc", evt.device.displayName, evt.value)
}

private void recordEvent(String source, String device, String detail) {
    state.recent = ((state.recent ?: []) + [[ts: now(), source: source, device: device, detail: detail]]).takeRight(100)
}

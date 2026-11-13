// Copyright (c) 2025-2026 PJ
// SPDX-License-Identifier: MIT

/*
 Motion Fusion Child

 Combines PIR and mmWave motion inputs from a dual-sensor device (e.g. Aqara FP300)
 into a single motion output using configurable fusion algorithms.
 */
definition(
    name: "Motion Fusion Child",
    namespace: "iamtrep",
    parent: "iamtrep:Sensor Aggregator",
    author: "pj",
    description: "Combine PIR and mmWave inputs into a single motion output using configurable fusion algorithms",
    menu: "Automations", // new in platform 2.5.0
    category: "Convenience",
    // PIR and mmWave events arrive together and race the window timers on
    // currentOutput and the pending* keys; serialize so each sees the other's write.
    singleThreaded: true,
    iconUrl: "",
    iconX2Url: "",
    importUrl: "https://raw.githubusercontent.com/iamtrep/hubitat/refs/heads/main/apps/sensors/MotionFusionChild.groovy",
    documentationLink: "https://github.com/iamtrep/hubitat/tree/main/apps/sensors"
)

import groovy.transform.Field
import com.hubitat.hub.domain.Event

@Field static final String CODE_VERSION = "0.1.5"

@Field static final Map<String, String> FUSION_MODES = [
    "pirOnly"              : "PIR Only",
    "mmwaveOnly"           : "mmWave Only",
    "either"               : "Either (OR)",
    "both"                 : "Both (AND)",
    "pirGated"             : "PIR-Gated mmWave",
    "pirConfirmedMmwave"   : "PIR-Confirmed mmWave",
    "pirQuickMmwaveHold"   : "PIR-Quick + mmWave Hold"
]

preferences {
    page(name: "mainPage")
}

Map mainPage() {
    dynamicPage(name: "mainPage", title: " ", install: true, uninstall: true) {
        section("Configuration") {
            input "appName", "text", title: "Name this motion fusion app", submitOnChange: true
            if (appName) app.updateLabel("$appName")
        }
        section("Devices") {
            input name: "sourceDevice", type: "capability.*", title: "Source device (with PIR and mmWave)", required: true, submitOnChange: true
            if (sourceDevice) {
                List<String> attrs = sourceDevice.getSupportedAttributes().collect { it.name }
                Boolean hasPir = attrs.contains("pirDetection")
                Boolean hasMmwave = attrs.contains("roomState")
                if (!hasPir || !hasMmwave) {
                    List<String> missing = []
                    if (!hasPir) missing << "pirDetection"
                    if (!hasMmwave) missing << "roomState"
                    paragraph "<span style='color:var(--hubitat-warning-color, #8a5a00)'>Warning: selected device is missing attributes: ${missing.join(', ')}. Fusion modes requiring these inputs will not work correctly.</span>"
                }
            }
            input name: "outputDevice", type: "capability.motionSensor", title: "Output virtual motion sensor", required: true
            paragraph "<a href='/device/addDevice' target='_blank'>Create a new virtual device</a>"
        }
        section("Fusion Mode") {
            input name: "fusionMode", type: "enum", options: FUSION_MODES, title: "Fusion algorithm", defaultValue: "pirQuickMmwaveHold", required: true, submitOnChange: true
            paragraph getFusionModeDescription()
        }
        if (fusionMode) {
            section("Mode Settings") {
                switch (fusionMode) {
                    case "mmwaveOnly":
                        input name: "inactiveDelay", type: "number", title: "Inactive delay (seconds)", description: "Debounce before reporting inactive (0 = immediate)", defaultValue: 0, range: "0..600", required: true
                        break
                    case "both":
                        input name: "confirmationWindow", type: "number", title: "Confirmation window (seconds)", description: "Both sensors must agree within this window", defaultValue: 5, range: "1..60", required: true
                        break
                    case "pirGated":
                        input name: "confirmationWindow", type: "number", title: "mmWave confirmation window (seconds)", description: "mmWave must confirm PIR detection within this window", defaultValue: 5, range: "1..60", required: true
                        break
                    case "pirConfirmedMmwave":
                        input name: "confirmationWindow", type: "number", title: "PIR confirmation window (seconds)", description: "PIR must confirm mmWave detection within this window", defaultValue: 5, range: "1..60", required: true
                        break
                    case "pirQuickMmwaveHold":
                        input name: "cooldownTime", type: "number", title: "Cooldown time (seconds)", description: "Hold active after mmWave clears before going inactive", defaultValue: 30, range: "1..300", required: true
                        break
                }
            }
        }
        section("Status") {
            if (sourceDevice && outputDevice) {
                paragraph getStatusText()
            }
            paragraph "<small><b>Note:</b> The FP300's device-side absence delay (10–300s) fires before events reach this app. " +
                       "Your effective inactive delay is the device setting plus any app-side delay configured above.</small>"
        }
        section("Logging") {
            input name: "txtEnable", type: "bool", title: "Enable info logging", defaultValue: true
            input name: "debugEnable", type: "bool", title: "Enable debug logging", defaultValue: false, submitOnChange: true
            if (debugEnable) {
                input name: "traceEnable", type: "bool", title: "Enable trace logging", defaultValue: false
            }
            paragraph "<small>Motion Fusion v${CODE_VERSION}</small>"
        }
    }
}

// ==================== Lifecycle ====================

void installed() {
    logDebug "installed()"
    initialize()
}

void updated() {
    logDebug "updated()"
    unsubscribe()
    unschedule()
    initialize()
    if (debugEnable || traceEnable) runIn(1800, "logsOff")
}

void logsOff() {
    checkVersion()
    app.updateSetting("debugEnable", [value: "false", type: "bool"])
    app.updateSetting("traceEnable", [value: "false", type: "bool"])
    logWarn "debug and trace logging disabled"
}

void initialize() {
    checkVersion(false)
    app.removeSetting("logLevel")
    logDebug "initialize()"

    if (!sourceDevice || !outputDevice) {
        logWarn "Source or output device not configured"
        return
    }

    // Initialize state
    if (state.currentOutput == null) state.currentOutput = "inactive"

    // Read current sensor values
    state.lastPirValue = sourceDevice.currentValue("pirDetection") ?: "inactive"
    state.lastMmwaveValue = sourceDevice.currentValue("roomState") ?: "unoccupied"
    state.lastPirTime = now()
    state.lastMmwaveTime = now()

    // Subscribe to sensor events
    subscribe(sourceDevice, "pirDetection", pirEventHandler)
    subscribe(sourceDevice, "roomState", mmwaveEventHandler)

    // Re-arm (or complete) a pending window whose timer the unschedule() in updated() killed
    servicePending()

    logCfg "Initialized: mode=${FUSION_MODES[fusionMode]}, PIR=${state.lastPirValue}, mmWave=${state.lastMmwaveValue}, output=${state.currentOutput}"
}

void checkVersion(boolean reinit = true) {
    if (state.version == CODE_VERSION) return
    logVer "New version: ${CODE_VERSION} (was: ${state.version})"
    state.version = CODE_VERSION
    if (reinit) runIn(1, "updated")
}

void uninstalled() {
    logDebug "uninstalled()"
}

// ==================== Event Handlers ====================

void pirEventHandler(Event evt) {
    checkVersion()
    logEvt "PIR event: ${evt.value}"
    state.lastPirValue = evt.value
    state.lastPirTime = now()
    evaluateFusion("pir")
}

void mmwaveEventHandler(Event evt) {
    checkVersion()
    logEvt "mmWave event: ${evt.value}"
    state.lastMmwaveValue = evt.value
    state.lastMmwaveTime = now()
    evaluateFusion("mmwave")
}

// ==================== Sensor State Helpers ====================

private Boolean isPirActive() {
    return state.lastPirValue == "active"
}

private Boolean isMmwaveOccupied() {
    return state.lastMmwaveValue == "occupied"
}

// ==================== Fusion Dispatcher ====================

// The decisions live in the pure core below; this side reads the inputs and carries out the plan.
private void evaluateFusion(String trigger) {
    servicePending()
    Map plan = fusionStep(fusionMode as String, trigger, isPirActive(), isMmwaveOccupied(),
                          state.currentOutput as String, state.pendingInactive as boolean, modeSeconds())
    if (plan.unknown) {
        logWarn "Unknown fusion mode: ${fusionMode}"
        return
    }
    applyPlan(plan)
}

// The mode's own timing setting: the inactive delay, the cooldown or the confirmation window.
private Integer modeSeconds() {
    switch (fusionMode) {
        case "mmwaveOnly":         return (inactiveDelay ?: 0) as Integer
        case "pirQuickMmwaveHold": return (cooldownTime ?: 30) as Integer
        default:                   return (confirmationWindow ?: 5) as Integer
    }
}

private void applyPlan(Map plan) {
    if (plan.note) logSched(plan.note as String)
    if (plan.cancel) unschedule(plan.cancel as String)
    if (plan.clear) clearPending()
    if (plan.debug) logDebug(plan.debug as String)
    if (plan.arm) armPending(plan.arm as String, plan.armSeconds as Integer)
    if (plan.output) setOutputState(plan.output as String)
}

// ==================== Window Timers ====================

void delayedInactive()           { runTimeout("delayedInactive") }
void confirmationTimeout()       { runTimeout("confirmationTimeout") }
void mmwaveConfirmationTimeout() { runTimeout("mmwaveConfirmationTimeout") }
void pirConfirmationTimeout()    { runTimeout("pirConfirmationTimeout") }
void cooldownExpired()           { runTimeout("cooldownExpired") }

private void runTimeout(String handler) {
    checkVersion()
    clearPending()
    applyPlan(timeoutStep(handler, isPirActive(), isMmwaveOccupied(), state.currentOutput as String))
}

// ==================== Pending Windows ====================

// Each pending window persists its handler and deadline, so a lost runIn completes or
// re-arms on the next sensor event or initialize() instead of leaving pendingInactive
// stuck true, which would block every later window.
private void armPending(String handler, Integer seconds) {
    state.pendingInactive = true
    state.pendingHandler = handler
    state.pendingDueAt = now() + seconds * 1000L
    runIn(seconds, handler)
}

private void clearPending() {
    state.pendingInactive = false
    state.remove("pendingHandler")
    state.remove("pendingDueAt")
}

private void servicePending() {
    if (!state.pendingInactive) return
    String handler = state.pendingHandler as String
    Long dueAt = state.pendingDueAt as Long
    if (handler == null || dueAt == null) {     // no deadline recorded: drop the stale flag
        clearPending()
        return
    }
    long remainingMs = dueAt - now()
    if (remainingMs > 0) {
        runIn((remainingMs.intdiv(1000) as int) + 1, handler)
        return
    }
    unschedule(handler)
    switch (handler) {
        case "delayedInactive":            delayedInactive(); break
        case "confirmationTimeout":        confirmationTimeout(); break
        case "mmwaveConfirmationTimeout":  mmwaveConfirmationTimeout(); break
        case "pirConfirmationTimeout":     pirConfirmationTimeout(); break
        case "cooldownExpired":            cooldownExpired(); break
        default:                           clearPending()
    }
}

// ==================== Output ====================

private void setOutputState(String motionState) {
    if (state.currentOutput == motionState) {
        return
    }

    state.currentOutput = motionState
    logCmd "Output → ${motionState} (mode: ${FUSION_MODES[fusionMode]}, PIR: ${state.lastPirValue}, mmWave: ${state.lastMmwaveValue})"

    outputDevice.sendEvent(
        name: "motion",
        value: motionState,
        descriptionText: "${outputDevice.displayName} is ${motionState}",
        isStateChange: true
    )
}

// ==================== Status Display ====================

private String getStatusText() {
    StringBuilder sb = new StringBuilder()

    String output = state.currentOutput ?: "inactive"
    String color = output == "active" ? "green" : "gray"
    sb.append("<b style='color:${color}'>Output: ${output.toUpperCase()}</b><br/>")
    sb.append("PIR: ${state.lastPirValue ?: 'unknown'}<br/>")
    sb.append("mmWave: ${state.lastMmwaveValue ?: 'unknown'}<br/>")
    sb.append("Mode: ${FUSION_MODES[fusionMode] ?: fusionMode}<br/>")

    if (state.pendingInactive) {
        sb.append("<span style='color:var(--hubitat-warning-color, #8a5a00)'>Timer pending...</span><br/>")
    }

    return sb.toString()
}

private String getFusionModeDescription() {
    switch (fusionMode) {
        case "pirOnly":
            return "<small>Output mirrors PIR detection directly. Fast response, may have false positives.</small>"
        case "mmwaveOnly":
            return "<small>Output mirrors mmWave room state. Reliable presence, slower response. Optional debounce on inactive.</small>"
        case "either":
            return "<small>Active if either PIR or mmWave detects. Fastest response, most false positives.</small>"
        case "both":
            return "<small>Active only when both sensors agree within a time window. Fewest false positives, slower response.</small>"
        case "pirGated":
            return "<small>PIR must fire first, then mmWave must confirm within a time window. mmWave going unoccupied ends motion immediately. Mirror of PIR-Confirmed — best for false-positive reduction.</small>"
        case "pirConfirmedMmwave":
            return "<small>mmWave starts detection, PIR must confirm within a time window. Good for alarm/security scenarios.</small>"
        case "pirQuickMmwaveHold":
            return "<small>PIR triggers immediately for fast light-on. mmWave sustains presence. Cooldown prevents flicker on release. Best for lighting.</small>"
        default:
            return ""
    }
}

// ── Core (pure) ───────────────────────────────────────────────────────
// No settings, state, devices, logging or @Field here: tests/test_motion_fusion_core.groovy runs this block off-hub.
//
// A step returns a plan that applyPlan() carries out in this order: cancel (a timer to
// unschedule), clear (drop the pending window), arm (a timer to start for armSeconds),
// output ('active' or 'inactive'; absent means no change). note and debug are log lines.

// One sensor event. seconds is the mode's timing setting (inactive delay, confirmation
// window or cooldown); pending is true while a window timer is armed.
Map fusionStep(String mode, String trigger, boolean pir, boolean mmwave, String output, boolean pending, Integer seconds) {
    switch (mode) {
        case "pirOnly":
            return [output: pir ? "active" : "inactive"]

        case "mmwaveOnly":
            if (mmwave) return [cancel: "delayedInactive", clear: true, output: "active"]
            if (seconds > 0) {
                if (pending) return [:]
                return [arm: "delayedInactive", armSeconds: seconds, note: "mmWave unoccupied — scheduling inactive in ${seconds}s"]
            }
            return [output: "inactive"]

        case "either":
            return [output: (pir || mmwave) ? "active" : "inactive"]

        case "both":
            if (pir && mmwave) return [cancel: "confirmationTimeout", output: "active"]
            if (pir || mmwave) {
                // One sensor active: start the confirmation window if not already waiting
                if (output != "active" && !pending) {
                    return [arm: "confirmationTimeout", armSeconds: seconds, note: "One sensor active — waiting ${seconds}s for confirmation"]
                }
                // If currently active and one drops out, go inactive immediately
                return output == "active" ? [output: "inactive"] : [:]
            }
            return [cancel: "confirmationTimeout", clear: true, output: "inactive"]

        case "pirGated":
            // mmWave unoccupied: immediate inactive, regardless of PIR
            if (!mmwave) return [cancel: "mmwaveConfirmationTimeout", clear: true, output: "inactive"]
            if (pir && mmwave) return [cancel: "mmwaveConfirmationTimeout", clear: true, output: "active"]
            if (trigger == "pir" && pir && output != "active") {
                return [arm: "mmwaveConfirmationTimeout", armSeconds: seconds, note: "PIR active — waiting ${seconds}s for mmWave confirmation"]
            }
            // If already active and PIR drops but mmWave still occupied, stay active
            return [:]

        case "pirConfirmedMmwave":
            if (!mmwave) return [cancel: "pirConfirmationTimeout", clear: true, output: "inactive"]
            if (pir) return [cancel: "pirConfirmationTimeout", clear: true, output: "active"]
            if (trigger == "mmwave" && output != "active") {
                return [arm: "pirConfirmationTimeout", armSeconds: seconds, note: "mmWave occupied — waiting ${seconds}s for PIR confirmation"]
            }
            // If already active and PIR goes inactive but mmWave still occupied, stay active
            return [:]

        case "pirQuickMmwaveHold":
            if (pir) return [cancel: "cooldownExpired", clear: true, output: "active"]
            // mmWave sustains an active output but never starts one without a prior PIR trigger
            if (mmwave) return [cancel: "cooldownExpired", clear: true, debug: output == "active" ? "mmWave sustaining active state" : null]
            // Both inactive: start the cooldown if currently active
            if (output == "active" && !pending) {
                return [arm: "cooldownExpired", armSeconds: seconds, note: "Both sensors inactive — starting ${seconds}s cooldown"]
            }
            return [:]

        default:
            return [unknown: true]
    }
}

// A window timer that fired; the pending window is already cleared.
Map timeoutStep(String handler, boolean pir, boolean mmwave, String output) {
    switch (handler) {
        case "delayedInactive":
            if (mmwave) return [note: "delayedInactive: mmWave re-occupied, staying active"]
            return [output: "inactive"]
        case "confirmationTimeout":
            if (pir && mmwave) return [output: "active"]
            return [note: "confirmationTimeout: sensors did not agree within window", output: "inactive"]
        case "mmwaveConfirmationTimeout":
            if (pir && mmwave) return [output: "active"]
            return [note: "mmwaveConfirmationTimeout: mmWave did not confirm within window", output: output != "active" ? "inactive" : null]
        case "pirConfirmationTimeout":
            if (pir && mmwave) return [output: "active"]
            // Stay in current state: don't go inactive if already active (mmWave still holding)
            return [note: "pirConfirmationTimeout: PIR did not confirm within window", output: output != "active" ? "inactive" : null]
        case "cooldownExpired":
            if (pir || mmwave) return [note: "cooldownExpired: sensor re-activated, staying active"]
            return [output: "inactive"]
        default:
            return [:]
    }
}

// ── End core ──────────────────────────────────────────────────────────

// ── Logging (app) ─────────────────────────────────────────────────────
//   ⬇️ Evt  ⬆️ Cmd  🔧 Cfg  🌐 Net  ⏰ Sched  🏷️ Ver  ·  ⚠️ Warn  🛑 Error  🔬 Trace
private String logp(String e) { "${e} ${app.getLabel()}: " }

void logEvt  (String m) { if (debugEnable) log.debug logp('⬇️') + m }
void logCmd  (String m) { if (txtEnable != false) log.info  logp('⬆️') + m }
void logCfg  (String m) { if (txtEnable != false) log.info  logp('🔧') + m }
void logNet  (String m) { if (debugEnable) log.debug logp('🌐') + m }
void logSched(String m) { if (debugEnable) log.debug logp('⏰') + m }
void logVer  (String m) { log.warn  logp('🏷️') + m }

void logWarn (String m) { log.warn  logp('⚠️') + m }
void logError(String m) { log.error logp('🛑') + m }
void logTrace(String m) { if (traceEnable) log.trace logp('🔬') + m }
void logInfo (String m) { if (txtEnable != false) log.info  "${app.getLabel()}: ${m}" }
void logDebug(String m) { if (debugEnable) log.debug "${app.getLabel()}: ${m}" }

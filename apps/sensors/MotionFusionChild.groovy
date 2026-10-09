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
    importUrl: "https://raw.githubusercontent.com/iamtrep/hubitat/refs/heads/main/apps/sensors/MotionFusionChild.groovy"
)

import groovy.transform.Field
import com.hubitat.hub.domain.Event

@Field static final String CODE_VERSION = "0.1.4"

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
                    paragraph "<span style='color:orange'>Warning: selected device is missing attributes: ${missing.join(', ')}. Fusion modes requiring these inputs will not work correctly.</span>"
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

private void checkVersion(boolean reinit = true) {
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

private void evaluateFusion(String trigger) {
    servicePending()
    switch (fusionMode) {
        case "pirOnly":
            evaluatePirOnly()
            break
        case "mmwaveOnly":
            evaluateMmwaveOnly(trigger)
            break
        case "either":
            evaluateEither()
            break
        case "both":
            evaluateBoth(trigger)
            break
        case "pirGated":
            evaluatePirGated(trigger)
            break
        case "pirConfirmedMmwave":
            evaluatePirConfirmedMmwave(trigger)
            break
        case "pirQuickMmwaveHold":
            evaluatePirQuickMmwaveHold(trigger)
            break
        default:
            logWarn "Unknown fusion mode: ${fusionMode}"
    }
}

// ==================== Mode: PIR Only ====================

private void evaluatePirOnly() {
    String output = isPirActive() ? "active" : "inactive"
    setOutputState(output)
}

// ==================== Mode: mmWave Only ====================

private void evaluateMmwaveOnly(String trigger) {
    if (isMmwaveOccupied()) {
        unschedule("delayedInactive")
        clearPending()
        setOutputState("active")
    } else {
        Integer delay = (inactiveDelay ?: 0) as Integer
        if (delay > 0) {
            if (!state.pendingInactive) {
                logSched "mmWave unoccupied — scheduling inactive in ${delay}s"
                armPending("delayedInactive", delay)
            }
        } else {
            setOutputState("inactive")
        }
    }
}

void delayedInactive() {
    checkVersion()
    clearPending()
    if (isMmwaveOccupied()) {
        logSched "delayedInactive: mmWave re-occupied, staying active"
        return
    }
    setOutputState("inactive")
}

// ==================== Mode: Either (OR) ====================

private void evaluateEither() {
    String output = (isPirActive() || isMmwaveOccupied()) ? "active" : "inactive"
    setOutputState(output)
}

// ==================== Mode: Both (AND) ====================

private void evaluateBoth(String trigger) {
    if (isPirActive() && isMmwaveOccupied()) {
        unschedule("confirmationTimeout")
        setOutputState("active")
    } else if (isPirActive() || isMmwaveOccupied()) {
        // One sensor active — start confirmation window if not already waiting
        if (state.currentOutput != "active" && !state.pendingInactive) {
            Integer window = (confirmationWindow ?: 5) as Integer
            logSched "One sensor active — waiting ${window}s for confirmation"
            armPending("confirmationTimeout", window)
        }
        // If currently active and one drops out, go inactive immediately
        if (state.currentOutput == "active") {
            setOutputState("inactive")
        }
    } else {
        // Both inactive
        unschedule("confirmationTimeout")
        clearPending()
        setOutputState("inactive")
    }
}

void confirmationTimeout() {
    checkVersion()
    clearPending()
    if (isPirActive() && isMmwaveOccupied()) {
        setOutputState("active")
    } else {
        logSched "confirmationTimeout: sensors did not agree within window"
        setOutputState("inactive")
    }
}

// ==================== Mode: PIR-Gated mmWave ====================

private void evaluatePirGated(String trigger) {
    if (!isMmwaveOccupied()) {
        // mmWave unoccupied — immediate inactive, regardless of PIR
        unschedule("mmwaveConfirmationTimeout")
        clearPending()
        setOutputState("inactive")
        return
    }

    // mmWave occupied
    if (isPirActive() && isMmwaveOccupied()) {
        // Both active — confirmed
        unschedule("mmwaveConfirmationTimeout")
        clearPending()
        setOutputState("active")
    } else if (trigger == "pir" && isPirActive() && state.currentOutput != "active") {
        // PIR just fired, mmWave not yet occupied — start confirmation window
        Integer window = (confirmationWindow ?: 5) as Integer
        logSched "PIR active — waiting ${window}s for mmWave confirmation"
        armPending("mmwaveConfirmationTimeout", window)
    }
    // If already active and PIR drops but mmWave still occupied, stay active
}

void mmwaveConfirmationTimeout() {
    checkVersion()
    clearPending()
    if (isPirActive() && isMmwaveOccupied()) {
        setOutputState("active")
    } else {
        logSched "mmwaveConfirmationTimeout: mmWave did not confirm within window"
        if (state.currentOutput != "active") {
            setOutputState("inactive")
        }
    }
}

// ==================== Mode: PIR-Confirmed mmWave ====================

private void evaluatePirConfirmedMmwave(String trigger) {
    if (!isMmwaveOccupied()) {
        // mmWave unoccupied — immediate inactive
        unschedule("pirConfirmationTimeout")
        clearPending()
        setOutputState("inactive")
        return
    }

    // mmWave occupied
    if (isPirActive()) {
        // PIR confirms — active
        unschedule("pirConfirmationTimeout")
        clearPending()
        setOutputState("active")
    } else if (trigger == "mmwave" && state.currentOutput != "active") {
        // mmWave just went occupied, PIR not yet active — start confirmation window
        Integer window = (confirmationWindow ?: 5) as Integer
        logSched "mmWave occupied — waiting ${window}s for PIR confirmation"
        armPending("pirConfirmationTimeout", window)
    }
    // If already active and PIR goes inactive but mmWave still occupied, stay active
}

void pirConfirmationTimeout() {
    checkVersion()
    clearPending()
    if (isPirActive() && isMmwaveOccupied()) {
        setOutputState("active")
    } else {
        logSched "pirConfirmationTimeout: PIR did not confirm within window"
        // Stay in current state — don't go inactive if already active (mmWave still holding)
        if (state.currentOutput != "active") {
            setOutputState("inactive")
        }
    }
}

// ==================== Mode: PIR-Quick + mmWave Hold ====================

private void evaluatePirQuickMmwaveHold(String trigger) {
    if (isPirActive()) {
        // PIR active — immediate active
        unschedule("cooldownExpired")
        clearPending()
        setOutputState("active")
        return
    }

    if (isMmwaveOccupied()) {
        // mmWave occupied — cancel cooldown, stay active
        unschedule("cooldownExpired")
        clearPending()
        if (state.currentOutput == "active") {
            // Already active, mmWave sustains it
            logDebug "mmWave sustaining active state"
        }
        // Don't go active on mmWave alone without prior PIR trigger
        return
    }

    // Both inactive — start cooldown if currently active
    if (state.currentOutput == "active" && !state.pendingInactive) {
        Integer cooldown = (cooldownTime ?: 30) as Integer
        logSched "Both sensors inactive — starting ${cooldown}s cooldown"
        armPending("cooldownExpired", cooldown)
    }
}

void cooldownExpired() {
    checkVersion()
    clearPending()
    if (isPirActive() || isMmwaveOccupied()) {
        logSched "cooldownExpired: sensor re-activated, staying active"
        return
    }
    setOutputState("inactive")
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
        runIn((remainingMs / 1000).toInteger() + 1, handler)
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
        sb.append("<span style='color:orange'>Timer pending...</span><br/>")
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

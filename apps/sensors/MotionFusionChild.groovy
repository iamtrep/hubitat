/*

 MIT License

 Copyright (c) 2025 pj

 Permission is hereby granted, free of charge, to any person obtaining a copy
 of this software and associated documentation files (the "Software"), to deal
 in the Software without restriction, including without limitation the rights
 to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 copies of the Software, and to permit persons to whom the Software is
 furnished to do so, subject to the following conditions:

 The above copyright notice and this permission notice shall be included in all
 copies or substantial portions of the Software.

 THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 SOFTWARE.

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
    category: "Convenience",
    iconUrl: "",
    iconX2Url: "",
    importUrl: "https://raw.githubusercontent.com/iamtrep/hubitat/refs/heads/main/apps/sensors/MotionFusionChild.groovy"
)

import groovy.transform.Field
import com.hubitat.hub.domain.Event

@Field static final String APP_VERSION = "0.1.0"

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
            input name: "logLevel", type: "enum", options: ["warn", "info", "debug"], title: "Log level", defaultValue: "info", required: true
            paragraph "<small>Motion Fusion v${APP_VERSION}</small>"
        }
    }
}

// ==================== Lifecycle ====================

void installed() {
    logDebug "installed()"
}

void updated() {
    logDebug "updated()"
    unsubscribe()
    unschedule()
    initialize()
}

void initialize() {
    logDebug "initialize()"

    if (!sourceDevice || !outputDevice) {
        logWarn "Source or output device not configured"
        return
    }

    // Initialize state
    if (state.currentOutput == null) state.currentOutput = "inactive"
    if (state.pendingInactive == null) state.pendingInactive = false

    // Read current sensor values
    state.lastPirValue = sourceDevice.currentValue("pirDetection") ?: "inactive"
    state.lastMmwaveValue = sourceDevice.currentValue("roomState") ?: "unoccupied"
    state.lastPirTime = now()
    state.lastMmwaveTime = now()

    // Subscribe to sensor events
    subscribe(sourceDevice, "pirDetection", pirEventHandler)
    subscribe(sourceDevice, "roomState", mmwaveEventHandler)

    logInfo "Initialized: mode=${FUSION_MODES[fusionMode]}, PIR=${state.lastPirValue}, mmWave=${state.lastMmwaveValue}, output=${state.currentOutput}"
}

void uninstalled() {
    logDebug "uninstalled()"
}

// ==================== Event Handlers ====================

void pirEventHandler(Event evt) {
    logDebug "PIR event: ${evt.value}"
    state.lastPirValue = evt.value
    state.lastPirTime = now()
    evaluateFusion("pir")
}

void mmwaveEventHandler(Event evt) {
    logDebug "mmWave event: ${evt.value}"
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
        state.pendingInactive = false
        setOutputState("active")
    } else {
        Integer delay = (inactiveDelay ?: 0) as Integer
        if (delay > 0) {
            if (!state.pendingInactive) {
                logDebug "mmWave unoccupied — scheduling inactive in ${delay}s"
                state.pendingInactive = true
                runIn(delay, "delayedInactive")
            }
        } else {
            setOutputState("inactive")
        }
    }
}

void delayedInactive() {
    state.pendingInactive = false
    if (isMmwaveOccupied()) {
        logDebug "delayedInactive: mmWave re-occupied, staying active"
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
            logDebug "One sensor active — waiting ${window}s for confirmation"
            state.pendingInactive = true
            runIn(window, "confirmationTimeout")
        }
        // If currently active and one drops out, go inactive immediately
        if (state.currentOutput == "active") {
            setOutputState("inactive")
        }
    } else {
        // Both inactive
        unschedule("confirmationTimeout")
        state.pendingInactive = false
        setOutputState("inactive")
    }
}

void confirmationTimeout() {
    state.pendingInactive = false
    if (isPirActive() && isMmwaveOccupied()) {
        setOutputState("active")
    } else {
        logDebug "confirmationTimeout: sensors did not agree within window"
        setOutputState("inactive")
    }
}

// ==================== Mode: PIR-Gated mmWave ====================

private void evaluatePirGated(String trigger) {
    if (!isMmwaveOccupied()) {
        // mmWave unoccupied — immediate inactive, regardless of PIR
        unschedule("mmwaveConfirmationTimeout")
        state.pendingInactive = false
        setOutputState("inactive")
        return
    }

    // mmWave occupied
    if (isPirActive() && isMmwaveOccupied()) {
        // Both active — confirmed
        unschedule("mmwaveConfirmationTimeout")
        state.pendingInactive = false
        setOutputState("active")
    } else if (trigger == "pir" && isPirActive() && state.currentOutput != "active") {
        // PIR just fired, mmWave not yet occupied — start confirmation window
        Integer window = (confirmationWindow ?: 5) as Integer
        logDebug "PIR active — waiting ${window}s for mmWave confirmation"
        state.pendingInactive = true
        runIn(window, "mmwaveConfirmationTimeout")
    }
    // If already active and PIR drops but mmWave still occupied, stay active
}

void mmwaveConfirmationTimeout() {
    state.pendingInactive = false
    if (isPirActive() && isMmwaveOccupied()) {
        setOutputState("active")
    } else {
        logDebug "mmwaveConfirmationTimeout: mmWave did not confirm within window"
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
        state.pendingInactive = false
        setOutputState("inactive")
        return
    }

    // mmWave occupied
    if (isPirActive()) {
        // PIR confirms — active
        unschedule("pirConfirmationTimeout")
        state.pendingInactive = false
        setOutputState("active")
    } else if (trigger == "mmwave" && state.currentOutput != "active") {
        // mmWave just went occupied, PIR not yet active — start confirmation window
        Integer window = (confirmationWindow ?: 5) as Integer
        logDebug "mmWave occupied — waiting ${window}s for PIR confirmation"
        state.pendingInactive = true
        runIn(window, "pirConfirmationTimeout")
    }
    // If already active and PIR goes inactive but mmWave still occupied, stay active
}

void pirConfirmationTimeout() {
    state.pendingInactive = false
    if (isPirActive() && isMmwaveOccupied()) {
        setOutputState("active")
    } else {
        logDebug "pirConfirmationTimeout: PIR did not confirm within window"
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
        state.pendingInactive = false
        setOutputState("active")
        return
    }

    if (isMmwaveOccupied()) {
        // mmWave occupied — cancel cooldown, stay active
        unschedule("cooldownExpired")
        state.pendingInactive = false
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
        logDebug "Both sensors inactive — starting ${cooldown}s cooldown"
        state.pendingInactive = true
        runIn(cooldown, "cooldownExpired")
    }
}

void cooldownExpired() {
    state.pendingInactive = false
    if (isPirActive() || isMmwaveOccupied()) {
        logDebug "cooldownExpired: sensor re-activated, staying active"
        return
    }
    setOutputState("inactive")
}

// ==================== Output ====================

private void setOutputState(String motionState) {
    if (state.currentOutput == motionState) {
        return
    }

    state.currentOutput = motionState
    logInfo "Output → ${motionState} (mode: ${FUSION_MODES[fusionMode]}, PIR: ${state.lastPirValue}, mmWave: ${state.lastMmwaveValue})"

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

// ==================== Logging ====================

private void logError(String msg) {
    log.error(app.getLabel() + ': ' + msg)
}

private void logWarn(String msg) {
    log.warn(app.getLabel() + ': ' + msg)
}

private void logInfo(String msg) {
    if (logLevel == null || logLevel in ["info", "debug"]) log.info(app.getLabel() + ': ' + msg)
}

private void logDebug(String msg) {
    if (logLevel == null || logLevel in ["debug"]) log.debug(app.getLabel() + ': ' + msg)
}

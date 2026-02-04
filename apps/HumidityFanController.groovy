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

 Humidity-Based Fan Controller

 Controls a bathroom extractor fan based on humidity levels compared to a reference sensor.
 Multiple sensors per role are supported; the median reading is used.

 == Architecture ==

 Two subsystems:

 1. HUMIDITY STATE MACHINE (tracks sensor readings with debounced transitions)

    States: NORMAL, PENDING_HIGH, HIGH, PENDING_NORMAL

    Activation threshold:
      bathroom >= absoluteLow + tolerance
      AND (bathroom > reference + highOffset OR bathroom > absoluteHigh)

    Deactivation threshold:
      bathroom < absoluteLow - tolerance
      OR bathroom < snapshot + normalOffset
      (snapshot = reference humidity captured at activation time)

                  above activation                    activation delay
                    threshold                           expires
    NORMAL ─────────────────────────> PENDING_HIGH ──────────────────> HIGH
      ▲                                    │                            │
      │          drops below               │                            │
      │       activation threshold         │                            │
      └────────────────────────────────────┘                            │
      │                                                                 │
      │           deactivation delay                below deactivation  │
      │               expires                           threshold       │
      └──────────────────────────── PENDING_NORMAL <────────────────────┘
                                         │      ▲
                                         │      │ rises above
                                         │      │ deactivation threshold
                                         └──────┘

    Additional forced transition:
    - Max fan run timer expiry → any active state resets to NORMAL

 2. FAN CONTROL (reacts to events, not a state machine)

    Inputs:
    - Humidity state transitions (HIGH → turn on, NORMAL → turn off if app controls fan)
    - Restriction changes (restricted → turn off if we turned it on, unrestricted → turn on if HIGH)
    - External fan manipulation (someone else turned it off → clear our flag)
    - Switch verification results (failed → notify, succeeded → confirm control flag)
    - Max fan run timer expiry (no bathroom humidity event in maxFanRunTime → turn off and notify)

    State:
    - fanTurnedOnByApp: Boolean - did we turn the fan on? (set after verification, not on command)
    - pendingCommand: "on" / "off" / null - unverified command in flight
    - lastHumidityEventTime: Long - timestamp of last bathroom humidity event (resets max run timer)

 3. HIGH HUMIDITY SWITCH (optional virtual switch synced to humidity state)

    Turned ON when humidity state is HIGH or PENDING_NORMAL.
    Turned OFF when humidity state is NORMAL or PENDING_HIGH.

 */

import groovy.transform.Field

@Field static final String APP_NAME = "Humidity-Based Fan Controller"
@Field static final String APP_VERSION = "0.6.0"

// Humidity state machine states
@Field static final String HUMIDITY_NORMAL = "NORMAL"
@Field static final String HUMIDITY_PENDING_HIGH = "PENDING_HIGH"
@Field static final String HUMIDITY_HIGH = "HIGH"
@Field static final String HUMIDITY_PENDING_NORMAL = "PENDING_NORMAL"

// Default values
@Field static final Integer DEFAULT_HIGH_HUMIDITY_OFFSET = 20
@Field static final Integer DEFAULT_NORMAL_HUMIDITY_OFFSET = 15
@Field static final Integer DEFAULT_ABSOLUTE_HIGH_THRESHOLD = 90
@Field static final Integer DEFAULT_ABSOLUTE_LOW_THRESHOLD = 60
@Field static final Integer DEFAULT_ABSOLUTE_LOW_TOLERANCE = 2
@Field static final Integer DEFAULT_ACTIVATION_DELAY_SECONDS = 60
@Field static final Integer DEFAULT_DEACTIVATION_DELAY_SECONDS = 120
@Field static final Integer DEFAULT_SWITCH_VERIFICATION_TIMEOUT_SECONDS = 30
@Field static final Integer DEFAULT_SENSOR_INACTIVITY_TIMEOUT_MINUTES = 60
@Field static final Integer DEFAULT_MAX_FAN_RUN_TIME_MINUTES = 120

definition(
    name: APP_NAME,
    namespace: "iamtrep",
    author: "pj",
    description: "Controls a bathroom extractor fan based on humidity levels compared to a reference sensor",
    category: "Convenience",
    singleInstance: false,
    importUrl: "https://raw.githubusercontent.com/iamtrep/hubitat/refs/heads/main/apps/HumidityFanController.groovy",
    iconUrl: "",
    iconX2Url: "",
    iconX3Url: ""
)

preferences {
    page(name: "mainPage")
}

Map mainPage() {
    dynamicPage(name: "mainPage", title: "", install: true, uninstall: true) {
        section("Current Status") {
            paragraph getStatusText()
        }

        section("App Label") {
            label title: "Name this instance", required: false
        }

        section("Devices") {
            input "fanSwitch", "capability.switch", title: "Fan Switch", required: true
            input "bathroomHumiditySensors", "capability.relativeHumidityMeasurement", title: "Bathroom Humidity Sensor(s)", description: "Select one or more sensors - median value will be used", multiple: true, required: true
            input "referenceHumiditySensors", "capability.relativeHumidityMeasurement", title: "Reference Humidity Sensor(s)", description: "Select one or more sensors - median value will be used", multiple: true, required: true
            input "highHumiditySwitch", "capability.switch", title: "High Humidity State Switch (optional)", description: "Virtual switch to reflect high humidity state - ON when humidity is elevated", required: false
            input "notificationDevice", "capability.notification", title: "Notification Device (optional)", required: false
        }

        section("Thresholds", hideable: true, hidden: true) {
            input "highHumidityOffset", "number", title: "High Humidity Threshold Offset", description: "Turn ON when bathroom > reference + this value", defaultValue: DEFAULT_HIGH_HUMIDITY_OFFSET, required: true
            input "normalHumidityOffset", "number", title: "Normal Humidity Threshold Offset", description: "Turn OFF when bathroom < reference + this value", defaultValue: DEFAULT_NORMAL_HUMIDITY_OFFSET, required: true
            input "absoluteHighThreshold", "number", title: "Absolute High Threshold", description: "Turn ON if humidity exceeds this regardless of reference", defaultValue: DEFAULT_ABSOLUTE_HIGH_THRESHOLD, required: true
            input "absoluteLowThreshold", "number", title: "Absolute Low Threshold", description: "Center of the absolute low threshold band", defaultValue: DEFAULT_ABSOLUTE_LOW_THRESHOLD, required: true
            input "absoluteLowTolerance", "number", title: "Absolute Low Tolerance", description: "Tolerance band around absolute low (activation blocked below threshold+tolerance, deactivation triggered below threshold-tolerance)", defaultValue: DEFAULT_ABSOLUTE_LOW_TOLERANCE, required: true
        }

        section("Timing", hideable: true, hidden: true) {
            input "activationDelay", "number", title: "Activation Delay (seconds)", description: "How long humidity must stay high before activating", defaultValue: DEFAULT_ACTIVATION_DELAY_SECONDS, required: true
            input "deactivationDelay", "number", title: "Deactivation Delay (seconds)", description: "How long humidity must stay normal before deactivating", defaultValue: DEFAULT_DEACTIVATION_DELAY_SECONDS, required: true
            input "switchVerificationTimeout", "number", title: "Switch Verification Timeout (seconds)", description: "Timeout for verifying switch state change", defaultValue: DEFAULT_SWITCH_VERIFICATION_TIMEOUT_SECONDS, required: true
            input "sensorInactivityTimeout", "number", title: "Sensor Inactivity Timeout (minutes)", description: "Exclude sensors that haven't reported in this time", defaultValue: DEFAULT_SENSOR_INACTIVITY_TIMEOUT_MINUTES, required: true
            input "maxFanRunTime", "number", title: "Maximum Fan Run Time (minutes)", description: "Turn off fan after this time if no humidity events received (0 to disable)", defaultValue: DEFAULT_MAX_FAN_RUN_TIME_MINUTES, required: true
        }

        section("Restrictions", hideable: true, hidden: true) {
            input "restrictionSwitchesMustBeOff", "capability.switch", title: "Restriction Switches (Must Be OFF)", description: "Fan automation paused if ANY of these is ON", multiple: true, required: false
            input "restrictionSwitchesMustBeOn", "capability.switch", title: "Restriction Switches (Must Be ON)", description: "Fan automation paused if ANY of these is OFF", multiple: true, required: false
        }

        section("Logging", hideable: true, hidden: true) {
            input "enableDebug", "bool", title: "Enable debug logging", defaultValue: false
        }

        section("") {
            paragraph "<small>${APP_NAME} v${APP_VERSION}</small>"
        }
    }
}

void installed() {
    logDebug("Installed with settings: ${settings}")
    initialize()
}

void updated() {
    logDebug("Updated with settings: ${settings}")
    unsubscribe()
    unschedule()
    initialize()
}

void initialize() {
    logDebug("Initializing...")

    // Initialize humidity state machine (only if not already set)
    if (state.humidityState == null) {
        state.humidityState = HUMIDITY_NORMAL
    }

    // Initialize fan control state (only if not already set)
    if (state.fanTurnedOnByApp == null) {
        state.fanTurnedOnByApp = false
    }

    // Sync high humidity switch with current state
    syncHighHumiditySwitch()

    // Subscribe to humidity changes from all sensors
    subscribe(bathroomHumiditySensors, "humidity", bathroomHumidityHandler)
    subscribe(referenceHumiditySensors, "humidity", referenceHumidityHandler)

    // Subscribe to fan switch changes (for external manipulation detection)
    subscribe(fanSwitch, "switch", fanSwitchHandler)

    // Subscribe to restriction switches
    if (restrictionSwitchesMustBeOff) {
        subscribe(restrictionSwitchesMustBeOff, "switch", restrictionSwitchHandler)
    }
    if (restrictionSwitchesMustBeOn) {
        subscribe(restrictionSwitchesMustBeOn, "switch", restrictionSwitchHandler)
    }

    // Reschedule pending timers if we're in a pending state (timers were cleared by unschedule())
    reschedulePendingTimers()

    // Consistency check: if in HIGH/PENDING_NORMAL but snapshot is missing, reset to NORMAL
    if ((state.humidityState == HUMIDITY_HIGH || state.humidityState == HUMIDITY_PENDING_NORMAL)
            && state.referenceHumiditySnapshot == null) {
        log.warn("Inconsistent state: ${state.humidityState} with no reference snapshot. Resetting to NORMAL.")
        state.humidityState = HUMIDITY_NORMAL
        state.pendingStateSince = null
    }

    // Consistency check: if humidity is NORMAL but we think we control the fan, turn it off
    if (state.humidityState == HUMIDITY_NORMAL && state.fanTurnedOnByApp) {
        log.warn("Inconsistent state detected: humidity NORMAL but fanTurnedOnByApp=true. Turning off fan.")
        turnOffFan()
    }

    // Consistency check: if humidity is HIGH and we should control the fan but don't, turn it on
    if (isHumidityHigh() && !state.fanTurnedOnByApp && !isRestricted()) {
        String fanState = fanSwitch.currentValue("switch")
        if (fanState != "on") {
            log.warn("Inconsistent state detected: humidity HIGH, not restricted, but fan not on. Turning on fan.")
            turnOnFan()
        }
    }

    // Reschedule max fan run timer if fan is on and controlled by app
    if (state.fanTurnedOnByApp && state.lastHumidityEventTime) {
        rescheduleMaxFanRunTimer()
    }

    log.info("${APP_NAME} initialized. Humidity: ${state.humidityState}, Fan controlled by app: ${state.fanTurnedOnByApp}")
}

// ==================== Event Handlers ====================

void bathroomHumidityHandler(evt) {
    logDebug("Bathroom humidity event from ${evt.device}: ${evt.value}%")
    state.lastHumidityEventTime = now()

    // Reset max fan run timer on every humidity event if fan is running
    if (state.fanTurnedOnByApp) {
        resetMaxFanRunTimer()
    }

    evaluateHumidityStateMachine()
}

void referenceHumidityHandler(evt) {
    logDebug("Reference humidity event from ${evt.device}: ${evt.value}%")
    // Reference sensor events don't reset the max fan timer, but we still evaluate
    evaluateHumidityStateMachine()
}

void fanSwitchHandler(evt) {
    logDebug("Fan switch changed to ${evt.value}")

    if (evt.value == "off" && state.fanTurnedOnByApp) {
        if (state.pendingCommand != "off") {
            // We didn't send this off command - someone else turned it off
            log.info("Fan turned off externally - clearing app control flag")
            state.fanTurnedOnByApp = false
        }
    }

    // Clear pending command if it matches what happened
    if (state.pendingCommand == evt.value) {
        state.pendingCommand = null
    }
}

void restrictionSwitchHandler(evt) {
    logDebug("Restriction switch ${evt.device} changed to ${evt.value}")

    Boolean nowRestricted = isRestricted()

    if (nowRestricted && state.fanTurnedOnByApp) {
        // Restriction activated and we have the fan on - turn it off
        log.info("Restriction activated - turning off fan")
        turnOffFan()
    } else if (!nowRestricted && isHumidityHigh()) {
        // Restriction lifted and humidity is high - turn fan on
        log.info("Restriction lifted while humidity is high - turning on fan")
        turnOnFan()
    }
}

// ==================== Humidity State Machine ====================

private void evaluateHumidityStateMachine() {
    BigDecimal bathroomHumidity = computeMedianHumidity(bathroomHumiditySensors)
    BigDecimal referenceHumidity = computeMedianHumidity(referenceHumiditySensors)

    if (bathroomHumidity == null) {
        log.warn("No active bathroom sensors - skipping humidity evaluation")
        return
    }

    if (referenceHumidity == null) {
        log.warn("No active reference sensors - skipping humidity evaluation")
        return
    }

    String oldState = state.humidityState
    logDebug("Humidity state machine: state=${oldState}, bathroom=${bathroomHumidity}% (median), reference=${referenceHumidity}% (median)")

    switch (state.humidityState) {
        case HUMIDITY_NORMAL:
            evaluateNormalState(bathroomHumidity, referenceHumidity)
            break
        case HUMIDITY_PENDING_HIGH:
            evaluatePendingHighState(bathroomHumidity, referenceHumidity)
            break
        case HUMIDITY_HIGH:
            evaluateHighState(bathroomHumidity)
            break
        case HUMIDITY_PENDING_NORMAL:
            evaluatePendingNormalState(bathroomHumidity)
            break
        default:
            log.warn("Unknown humidity state: ${state.humidityState}, resetting to NORMAL")
            transitionHumidityState(HUMIDITY_NORMAL)
    }
}

private void evaluateNormalState(BigDecimal bathroomHumidity, BigDecimal referenceHumidity) {
    if (isAboveActivationThreshold(bathroomHumidity, referenceHumidity)) {
        String reason = getActivationReason(bathroomHumidity, referenceHumidity)
        log.info("Humidity above activation threshold: ${reason} - starting ${activationDelay}s delay")

        // Snapshot the reference humidity at activation time
        state.referenceHumiditySnapshot = referenceHumidity
        state.pendingStateSince = now()

        transitionHumidityState(HUMIDITY_PENDING_HIGH)

        // Schedule the delayed transition to HIGH
        runIn(activationDelay as Integer, "delayedTransitionToHigh")
    }
}

private void evaluatePendingHighState(BigDecimal bathroomHumidity, BigDecimal referenceHumidity) {
    if (!isAboveActivationThreshold(bathroomHumidity, referenceHumidity)) {
        // Humidity dropped - cancel pending activation
        log.info("Humidity dropped during activation delay - returning to NORMAL")
        unschedule("delayedTransitionToHigh")
        state.pendingStateSince = null
        state.referenceHumiditySnapshot = null
        transitionHumidityState(HUMIDITY_NORMAL)
        return
    }

    // Still above threshold - wait for timer
    Long elapsedSeconds = (now() - (state.pendingStateSince as Long)) / 1000
    Long remainingSeconds = (activationDelay as Integer) - elapsedSeconds
    logDebug("Humidity still above threshold, waiting for activation delay (${remainingSeconds}s remaining)")
}

void delayedTransitionToHigh() {
    if (state.humidityState != HUMIDITY_PENDING_HIGH) {
        logDebug("delayedTransitionToHigh called but state is ${state.humidityState}, ignoring")
        return
    }

    // Trust the event-driven pattern: if we're still PENDING_HIGH, no event cancelled us,
    // which means conditions remained above threshold throughout the delay period.
    log.info("Activation delay completed - transitioning to HIGH")

    state.pendingStateSince = null
    transitionHumidityState(HUMIDITY_HIGH)

    // React to state change: turn on fan if not restricted
    onHumidityBecameHigh()
}

private void evaluateHighState(BigDecimal bathroomHumidity) {
    if (isBelowDeactivationThreshold(bathroomHumidity)) {
        String reason = getDeactivationReason(bathroomHumidity)
        log.info("Humidity below deactivation threshold: ${reason} - starting ${deactivationDelay}s delay")

        state.pendingStateSince = now()
        transitionHumidityState(HUMIDITY_PENDING_NORMAL)

        // Schedule the delayed transition to NORMAL
        runIn(deactivationDelay as Integer, "delayedTransitionToNormal")
    }
}

private void evaluatePendingNormalState(BigDecimal bathroomHumidity) {
    if (!isBelowDeactivationThreshold(bathroomHumidity)) {
        // Humidity rose - cancel pending deactivation
        log.info("Humidity rose during deactivation delay - returning to HIGH")
        unschedule("delayedTransitionToNormal")
        state.pendingStateSince = null
        transitionHumidityState(HUMIDITY_HIGH)
        return
    }

    // Still below threshold - wait for timer
    Long elapsedSeconds = (now() - (state.pendingStateSince as Long)) / 1000
    Long remainingSeconds = (deactivationDelay as Integer) - elapsedSeconds
    logDebug("Humidity still below threshold, waiting for deactivation delay (${remainingSeconds}s remaining)")
}

void delayedTransitionToNormal() {
    if (state.humidityState != HUMIDITY_PENDING_NORMAL) {
        logDebug("delayedTransitionToNormal called but state is ${state.humidityState}, ignoring")
        return
    }

    // Trust the event-driven pattern: if we're still PENDING_NORMAL, no event cancelled us,
    // which means conditions remained below threshold throughout the delay period.
    log.info("Deactivation delay completed - transitioning to NORMAL")

    state.pendingStateSince = null
    state.referenceHumiditySnapshot = null
    transitionHumidityState(HUMIDITY_NORMAL)

    // React to state change: turn off fan if we turned it on
    onHumidityBecameNormal()
}

private void transitionHumidityState(String newState) {
    String oldState = state.humidityState
    state.humidityState = newState

    if (oldState != newState) {
        log.info("Humidity state: ${oldState} -> ${newState}")
        syncHighHumiditySwitch()
    }
}

private void reschedulePendingTimers() {
    if (state.pendingStateSince == null) {
        return
    }

    Long pendingSince = state.pendingStateSince as Long
    Long elapsedMs = now() - pendingSince

    switch (state.humidityState) {
        case HUMIDITY_PENDING_HIGH:
            Long activationDelayMs = (activationDelay as Integer) * 1000
            Long remainingMs = activationDelayMs - elapsedMs
            if (remainingMs > 0) {
                Integer remainingSeconds = (remainingMs / 1000).toInteger() + 1  // Round up
                log.info("Rescheduling activation timer: ${remainingSeconds}s remaining")
                runIn(remainingSeconds, "delayedTransitionToHigh")
            } else {
                // Timer should have already fired - trigger it now
                log.info("Activation timer expired during reconfiguration - triggering now")
                runIn(1, "delayedTransitionToHigh")
            }
            break

        case HUMIDITY_PENDING_NORMAL:
            Long deactivationDelayMs = (deactivationDelay as Integer) * 1000
            Long remainingMsDeact = deactivationDelayMs - elapsedMs
            if (remainingMsDeact > 0) {
                Integer remainingSeconds = (remainingMsDeact / 1000).toInteger() + 1  // Round up
                log.info("Rescheduling deactivation timer: ${remainingSeconds}s remaining")
                runIn(remainingSeconds, "delayedTransitionToNormal")
            } else {
                // Timer should have already fired - trigger it now
                log.info("Deactivation timer expired during reconfiguration - triggering now")
                runIn(1, "delayedTransitionToNormal")
            }
            break
    }
}

// ==================== Humidity Threshold Logic ====================

private Boolean isAboveActivationThreshold(BigDecimal bathroomHumidity, BigDecimal referenceHumidity) {
    // Never activate if below absolute low threshold + tolerance
    BigDecimal activationFloor = absoluteLowThreshold + absoluteLowTolerance
    if (bathroomHumidity < activationFloor) {
        logDebug("Below activation floor (${activationFloor}%) - not activating")
        return false
    }

    // Activate if above absolute high threshold
    if (bathroomHumidity > absoluteHighThreshold) {
        return true
    }

    // Activate if bathroom > reference + high offset
    BigDecimal highThreshold = referenceHumidity + highHumidityOffset
    return bathroomHumidity > highThreshold
}

private Boolean isBelowDeactivationThreshold(BigDecimal bathroomHumidity) {
    // Use the snapshot reference for deactivation calculation
    BigDecimal effectiveReference = state.referenceHumiditySnapshot as BigDecimal
    BigDecimal normalThreshold = effectiveReference + normalHumidityOffset

    // Deactivate if below absolute low threshold - tolerance
    BigDecimal deactivationFloor = absoluteLowThreshold - absoluteLowTolerance
    if (bathroomHumidity < deactivationFloor) {
        return true
    }

    // Deactivate if bathroom < snapshot reference + normal offset
    return bathroomHumidity < normalThreshold
}

private String getActivationReason(BigDecimal bathroomHumidity, BigDecimal referenceHumidity) {
    if (bathroomHumidity > absoluteHighThreshold) {
        return "bathroom ${bathroomHumidity}% > absolute high ${absoluteHighThreshold}%"
    }
    BigDecimal highThreshold = referenceHumidity + highHumidityOffset
    return "bathroom ${bathroomHumidity}% > (reference ${referenceHumidity}% + ${highHumidityOffset}%) = ${highThreshold}%"
}

private String getDeactivationReason(BigDecimal bathroomHumidity) {
    BigDecimal deactivationFloor = absoluteLowThreshold - absoluteLowTolerance
    if (bathroomHumidity < deactivationFloor) {
        return "bathroom ${bathroomHumidity}% < deactivation floor ${deactivationFloor}%"
    }
    BigDecimal effectiveReference = state.referenceHumiditySnapshot as BigDecimal
    BigDecimal normalThreshold = effectiveReference + normalHumidityOffset
    return "bathroom ${bathroomHumidity}% < (snapshot ${effectiveReference}% + ${normalHumidityOffset}%) = ${normalThreshold}%"
}

private Boolean isHumidityHigh() {
    return state.humidityState == HUMIDITY_HIGH || state.humidityState == HUMIDITY_PENDING_NORMAL
}

// ==================== Fan Control (Reactions to Events) ====================

private void onHumidityBecameHigh() {
    if (isRestricted()) {
        logDebug("Humidity became HIGH but restricted - not turning on fan")
        return
    }

    turnOnFan()
}

private void onHumidityBecameNormal() {
    if (state.fanTurnedOnByApp) {
        turnOffFan()
    } else {
        logDebug("Humidity became NORMAL but fan was not turned on by app - leaving it alone")
    }
}

private void turnOnFan() {
    log.info("Turning on fan")

    state.fanTurnedOnByApp = true
    state.pendingCommand = "on"
    fanSwitch.on()

    runIn(switchVerificationTimeout as Integer, "verifyFanOn")

    // Start max fan run timer
    scheduleMaxFanRunTimer()
}

void verifyFanOn() {
    String switchState = fanSwitch.currentValue("switch")

    if (switchState == "on") {
        log.info("Fan verified ON")
        state.pendingCommand = null
    } else {
        log.error("Fan failed to turn on after ${switchVerificationTimeout} seconds")
        state.fanTurnedOnByApp = false
        sendNotification("ERROR: ${fanSwitch.displayName} failed to turn on!")
        state.pendingCommand = null
    }
}

private void turnOffFan() {
    log.info("Turning off fan")

    state.pendingCommand = "off"
    fanSwitch.off()

    // Cancel max fan run timer
    unschedule("maxFanRunTimeExpired")

    runIn(switchVerificationTimeout as Integer, "verifyFanOff")
}

void verifyFanOff() {
    String switchState = fanSwitch.currentValue("switch")

    if (switchState == "off") {
        log.info("Fan verified OFF")
        state.fanTurnedOnByApp = false
        state.pendingCommand = null
    } else {
        log.error("Fan failed to turn off after ${switchVerificationTimeout} seconds")
        sendNotification("ERROR: ${fanSwitch.displayName} failed to turn off!")
        state.pendingCommand = null
    }
}

// ==================== Max Fan Run Time ====================

private void scheduleMaxFanRunTimer() {
    if (!maxFanRunTime || (maxFanRunTime as Integer) <= 0) {
        return
    }

    Integer delaySeconds = (maxFanRunTime as Integer) * 60
    runIn(delaySeconds, "maxFanRunTimeExpired")
    logDebug("Max fan run timer scheduled for ${maxFanRunTime} minutes")
}

private void resetMaxFanRunTimer() {
    if (!maxFanRunTime || (maxFanRunTime as Integer) <= 0) {
        return
    }

    unschedule("maxFanRunTimeExpired")
    scheduleMaxFanRunTimer()
    logDebug("Max fan run timer reset")
}

private void rescheduleMaxFanRunTimer() {
    if (!maxFanRunTime || (maxFanRunTime as Integer) <= 0) {
        return
    }

    if (!state.lastHumidityEventTime) {
        scheduleMaxFanRunTimer()
        return
    }

    Long elapsedMs = now() - (state.lastHumidityEventTime as Long)
    Long maxRunMs = (maxFanRunTime as Integer) * 60 * 1000
    Long remainingMs = maxRunMs - elapsedMs

    if (remainingMs > 0) {
        Integer remainingSeconds = (remainingMs / 1000).toInteger() + 1
        runIn(remainingSeconds, "maxFanRunTimeExpired")
        logDebug("Max fan run timer rescheduled: ${remainingSeconds}s remaining")
    } else {
        // Should have already expired - trigger now
        runIn(1, "maxFanRunTimeExpired")
    }
}

void maxFanRunTimeExpired() {
    if (!state.fanTurnedOnByApp) {
        logDebug("Max fan run time expired but fan not controlled by app - ignoring")
        return
    }

    log.warn("Max fan run time (${maxFanRunTime} min) expired without humidity events - turning off fan")
    sendNotification("WARNING: Fan turned off after ${maxFanRunTime} minutes - check humidity sensors")

    // Transition humidity state to NORMAL since we're giving up
    state.pendingStateSince = null
    state.referenceHumiditySnapshot = null
    transitionHumidityState(HUMIDITY_NORMAL)

    turnOffFan()
}

// ==================== Restrictions ====================

private Boolean isRestricted() {
    if (restrictionSwitchesMustBeOff) {
        Boolean anyOn = restrictionSwitchesMustBeOff.any { it.currentValue("switch") == "on" }
        if (anyOn) {
            logDebug("Restriction: one or more 'must be off' switches are on")
            return true
        }
    }

    if (restrictionSwitchesMustBeOn) {
        Boolean anyOff = restrictionSwitchesMustBeOn.any { it.currentValue("switch") == "off" }
        if (anyOff) {
            logDebug("Restriction: one or more 'must be on' switches are off")
            return true
        }
    }

    return false
}

// ==================== High Humidity Switch ====================

private void syncHighHumiditySwitch() {
    if (!highHumiditySwitch) {
        return
    }

    Boolean shouldBeOn = isHumidityHigh()
    String currentState = highHumiditySwitch.currentValue("switch")
    String targetState = shouldBeOn ? "on" : "off"

    if (currentState != targetState) {
        if (shouldBeOn) {
            highHumiditySwitch.on()
        } else {
            highHumiditySwitch.off()
        }
        logDebug("High humidity switch set to ${targetState}")
    }
}

// ==================== Status Display ====================

private String getStatusText() {
    StringBuilder status = new StringBuilder()

    // Humidity state
    String humidityState = state.humidityState ?: HUMIDITY_NORMAL
    switch (humidityState) {
        case HUMIDITY_NORMAL:
            status.append("<b style='color:green'>Humidity: NORMAL</b><br/>")
            break
        case HUMIDITY_PENDING_HIGH:
            status.append("<b style='color:orange'>Humidity: PENDING HIGH</b><br/>")
            if (state.pendingStateSince) {
                Long elapsed = (now() - (state.pendingStateSince as Long)) / 1000
                Long remaining = (activationDelay as Integer) - elapsed
                if (remaining > 0) {
                    status.append("Activation in: ${remaining}s<br/>")
                }
            }
            break
        case HUMIDITY_HIGH:
            status.append("<b style='color:red'>Humidity: HIGH</b><br/>")
            break
        case HUMIDITY_PENDING_NORMAL:
            status.append("<b style='color:orange'>Humidity: PENDING NORMAL</b><br/>")
            if (state.pendingStateSince) {
                Long elapsed = (now() - (state.pendingStateSince as Long)) / 1000
                Long remaining = (deactivationDelay as Integer) - elapsed
                if (remaining > 0) {
                    status.append("Deactivation in: ${remaining}s<br/>")
                }
            }
            break
    }

    // Current readings with sensor health
    Map bathroomStatus = getSensorStatus(bathroomHumiditySensors)
    Map referenceStatus = getSensorStatus(referenceHumiditySensors)

    if (bathroomStatus.median != null) {
        status.append("Bathroom: ${bathroomStatus.median}%")
        if (bathroomStatus.total > 1) {
            status.append(" <small>(${bathroomStatus.active}/${bathroomStatus.total} sensors)</small>")
        }
        status.append("<br/>")
    }
    if (bathroomStatus.excluded.size() > 0) {
        status.append("<span style='color:orange'><small>Inactive: ${bathroomStatus.excluded.join(', ')}</small></span><br/>")
    }

    if (referenceStatus.median != null) {
        status.append("Reference: ${referenceStatus.median}%")
        if (referenceStatus.total > 1) {
            status.append(" <small>(${referenceStatus.active}/${referenceStatus.total} sensors)</small>")
        }
        status.append("<br/>")
    }
    if (referenceStatus.excluded.size() > 0) {
        status.append("<span style='color:orange'><small>Inactive: ${referenceStatus.excluded.join(', ')}</small></span><br/>")
    }

    if (state.referenceHumiditySnapshot != null) {
        status.append("Snapshot: ${state.referenceHumiditySnapshot}%<br/>")
    }

    // Fan state
    status.append("<br/>")
    String fanState = fanSwitch?.currentValue("switch") ?: "unknown"
    status.append("Fan: ${fanState.toUpperCase()}")
    if (state.fanTurnedOnByApp) {
        status.append(" (controlled by app)")
        // Show max run timer status
        if (maxFanRunTime && (maxFanRunTime as Integer) > 0 && state.lastHumidityEventTime) {
            Long elapsedMin = (now() - (state.lastHumidityEventTime as Long)) / 60000
            Long remainingMin = (maxFanRunTime as Integer) - elapsedMin
            if (remainingMin > 0) {
                status.append(" <small>[auto-off in ${remainingMin}min]</small>")
            }
        }
    }
    status.append("<br/>")

    // Restrictions
    if (isRestricted()) {
        status.append("<span style='color:orange'>⚠ Restrictions active</span><br/>")
    }

    // Sensor warnings
    if (bathroomStatus.active == 0) {
        status.append("<span style='color:red'>⚠ No active bathroom sensors!</span><br/>")
    }
    if (referenceStatus.active == 0) {
        status.append("<span style='color:red'>⚠ No active reference sensors!</span><br/>")
    }

    return status.toString()
}

// ==================== Sensor Aggregation ====================

private List getActiveSensors(List sensors) {
    if (!sensors) {
        return []
    }

    Date now = new Date()
    Date cutoff = new Date(now.time - (sensorInactivityTimeout as Integer) * 60 * 1000)

    List activeSensors = []
    List inactiveSensors = []

    sensors.each { sensor ->
        Date lastActivity = sensor.getLastActivity()
        if (lastActivity && lastActivity > cutoff) {
            if (sensor.currentValue("humidity") != null) {
                activeSensors << sensor
            }
        } else {
            inactiveSensors << sensor
        }
    }

    if (inactiveSensors.size() > 0) {
        logDebug("Inactive sensors (no activity in ${sensorInactivityTimeout} min): ${inactiveSensors.collect { it.displayName }}")
    }

    return activeSensors
}

private BigDecimal computeMedianHumidity(List sensors) {
    List activeSensors = getActiveSensors(sensors)

    if (activeSensors.size() == 0) {
        return null
    }

    List<BigDecimal> values = activeSensors.collect { it.currentValue("humidity") as BigDecimal }
    values.sort()

    Integer n = values.size()
    if (n % 2 == 0) {
        return (values[(n / 2 - 1) as int] + values[(n / 2) as int]) / 2.0
    } else {
        return values[(n / 2) as int]
    }
}

private Map getSensorStatus(List sensors) {
    if (!sensors) {
        return [active: 0, total: 0, excluded: [], median: null]
    }

    List activeSensors = getActiveSensors(sensors)
    List excludedNames = sensors.findAll { !activeSensors.contains(it) }.collect { it.displayName }

    BigDecimal median = null
    if (activeSensors.size() > 0) {
        List<BigDecimal> values = activeSensors.collect { it.currentValue("humidity") as BigDecimal }
        values.sort()
        Integer n = values.size()
        if (n % 2 == 0) {
            median = (values[(n / 2 - 1) as int] + values[(n / 2) as int]) / 2.0
        } else {
            median = values[(n / 2) as int]
        }
    }

    return [
        active: activeSensors.size(),
        total: sensors.size(),
        excluded: excludedNames,
        median: median
    ]
}

// ==================== Utilities ====================

private void sendNotification(String message) {
    if (notificationDevice) {
        notificationDevice.deviceNotification(message)
        log.info("Notification sent: ${message}")
    } else {
        log.warn("No notification device configured. Message: ${message}")
    }
}

private void logDebug(String message) {
    if (enableDebug) {
        log.debug(message)
    }
}

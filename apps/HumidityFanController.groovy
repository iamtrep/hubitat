// Copyright (c) 2025-2026 PJ
// SPDX-License-Identifier: MIT

/*
 Humidity-Based Fan Controller

 Controls a bathroom extractor fan based on humidity levels compared to a reference sensor.
 Multiple sensors per role are supported; the median reading is used.

 == Architecture ==

 Two subsystems:

 1. HUMIDITY STATE MACHINE (tracks sensor readings with debounced transitions)

    States: NORMAL, PENDING_HIGH, HIGH, PENDING_NORMAL

    Activation threshold:
      bathroom >= absoluteLow + tolerance
      AND (bathroom > reference + highOffset
           OR bathroom > absoluteHigh
           OR fast-climb)

    Activation-delay bypasses (NORMAL → HIGH or PENDING_HIGH → HIGH
    directly, all evaluated AFTER the snapshot is captured):
      - fast-climb (rate-based, multi-event):
          >= burstEventCount events within burstWindowSeconds AND
          slope across the last burstEventCount samples (within
          slopeWindowSeconds) >= slopeActivationThreshold %RH/min
      - excessive-change (delta-based, single-event):
          current bathroom sample - previous sample >= excessiveChangeThreshold
          Complements fast-climb for slow sensors that report one big
          jump rather than a burst. Disabled by default (threshold = 0).
      - absolute-high (mid-pending only): bathroom > absoluteHigh while
          already in PENDING_HIGH.

    Occupancy gate (optional): when occupancySensors is configured, the
    fast-climb bypass requires motion to be currently active OR to have
    been active within occupancyWindowSeconds. With no sensors configured
    the gate is dormant.

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
import groovy.transform.CompileStatic
import groovy.transform.Field

@Field static final String APP_NAME = "Humidity-Based Fan Controller"
@Field static final String CODE_VERSION = "0.9.1"

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
@Field static final Integer DEFAULT_PHYSICAL_RUN_TIMER_MINUTES = 30
@Field static final BigDecimal DEFAULT_SLOPE_ACTIVATION_THRESHOLD = 2.5G
@Field static final Integer DEFAULT_BURST_EVENT_COUNT = 3
@Field static final Integer DEFAULT_BURST_WINDOW_SECONDS = 60
@Field static final Integer DEFAULT_SLOPE_WINDOW_SECONDS = 180
@Field static final BigDecimal DEFAULT_EXCESSIVE_CHANGE_THRESHOLD = 0G
@Field static final Integer SAMPLE_BUFFER_CAP = 20
@Field static final Integer DEFAULT_OCCUPANCY_WINDOW_SECONDS = 600

definition(
    name: APP_NAME,
    namespace: "iamtrep",
    author: "pj",
    description: "Controls a bathroom extractor fan based on humidity levels compared to a reference sensor",
    menu: "Automations", // new in platform 2.5.0
    category: "Convenience",
    singleInstance: false,
    singleThreaded: true,
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

        section("Single-Event Bypass", hideable: true, hidden: true) {
            paragraph "Bypass the activation delay when a single bathroom humidity reading jumps by at least this much from the previous reading. Complements fast-climb (which needs ≥3 events) for slow-reporting sensors. Set to 0 to disable."
            input "excessiveChangeThreshold", "decimal", title: "Excessive Change Threshold (%RH)", description: "Single-event Δ that bypasses activation delay; 0 disables", defaultValue: DEFAULT_EXCESSIVE_CHANGE_THRESHOLD, required: true
        }

        section("Fast-Climb Detection", hideable: true, hidden: true) {
            paragraph "Rate-based activation: trigger HIGH (skipping activation delay) when the bathroom sensor reports a burst of rising humidity events. Both conditions must hold."
            input "slopeActivationThreshold", "decimal", title: "Minimum Slope (%RH/min)", description: "Set to 0 to disable rate-based activation", defaultValue: DEFAULT_SLOPE_ACTIVATION_THRESHOLD, required: true
            input "burstEventCount", "number", title: "Burst Event Count", description: "Minimum bathroom events in the burst window", defaultValue: DEFAULT_BURST_EVENT_COUNT, required: true
            input "burstWindowSeconds", "number", title: "Burst Window (seconds)", description: "Lookback window for the burst count", defaultValue: DEFAULT_BURST_WINDOW_SECONDS, required: true
            input "slopeWindowSeconds", "number", title: "Slope Window (seconds)", description: "Lookback window for the slope computation", defaultValue: DEFAULT_SLOPE_WINDOW_SECONDS, required: true
        }

        section("Occupancy Gating (optional)", hideable: true, hidden: true) {
            paragraph "Restrict the fast-climb bypass to periods when motion has been detected, suppressing FPs from cooking steam or HVAC humidity drift. Leave the sensor list empty to disable the gate (fast-climb fires unconditionally)."
            input "occupancySensors", "capability.motionSensor", title: "Occupancy Motion Sensors", multiple: true, required: false
            input "occupancyWindowSeconds", "number", title: "Occupancy Window (seconds)", description: "Bypass remains armed for this long after the last motion-active event", defaultValue: DEFAULT_OCCUPANCY_WINDOW_SECONDS, required: true
        }

        section("Timing", hideable: true, hidden: true) {
            input "activationDelay", "number", title: "Activation Delay (seconds)", description: "How long humidity must stay high before activating", defaultValue: DEFAULT_ACTIVATION_DELAY_SECONDS, required: true
            input "deactivationDelay", "number", title: "Deactivation Delay (seconds)", description: "How long humidity must stay normal before deactivating", defaultValue: DEFAULT_DEACTIVATION_DELAY_SECONDS, required: true
            input "switchVerificationTimeout", "number", title: "Switch Verification Timeout (seconds)", description: "Timeout for verifying switch state change", defaultValue: DEFAULT_SWITCH_VERIFICATION_TIMEOUT_SECONDS, required: true
            input "sensorInactivityTimeout", "number", title: "Sensor Inactivity Timeout (minutes)", description: "Exclude sensors that haven't reported in this time", defaultValue: DEFAULT_SENSOR_INACTIVITY_TIMEOUT_MINUTES, required: true
            input "maxFanRunTime", "number", title: "Maximum Fan Run Time (minutes)", description: "Turn off fan after this time if no humidity events received (0 to disable)", defaultValue: DEFAULT_MAX_FAN_RUN_TIME_MINUTES, required: true
        }

        section("Manual Activation", hideable: true, hidden: true) {
            paragraph "When the fan is turned on with a physical paddle press (not by this app, not by another automation), schedule an automatic off after this many minutes. The timer acts as a <b>minimum run floor</b>: humidity-driven and restriction-driven off requests are deferred until the floor elapses. The human paddle press still cancels it immediately. The hub-side Max Fan Run Time still applies as the upper bound."
            input "physicalRunTimerEnabled", "bool", title: "Auto-off after physical activation", defaultValue: false, submitOnChange: true
            if (physicalRunTimerEnabled) {
                input "physicalRunTimerMinutes", "number", title: "Run time after physical activation (minutes)", defaultValue: DEFAULT_PHYSICAL_RUN_TIMER_MINUTES, range: "1..240", required: true
            }
        }

        section("Restrictions", hideable: true, hidden: true) {
            input "restrictionSwitchesMustBeOff", "capability.switch", title: "Restriction Switches (Must Be OFF)", description: "Fan automation paused if ANY of these is ON", multiple: true, required: false
            input "restrictionSwitchesMustBeOn", "capability.switch", title: "Restriction Switches (Must Be ON)", description: "Fan automation paused if ANY of these is OFF", multiple: true, required: false
        }

        section("Logging", hideable: true, hidden: true) {
            input name: "txtEnable", type: "bool", title: "Enable descriptionText logging", defaultValue: true
            input name: "debugEnable", type: "bool", title: "Enable debug logging", defaultValue: false, submitOnChange: true
            if (debugEnable) {
                input name: "traceEnable", type: "bool", title: "Enable trace logging", defaultValue: false
            }
        }

        section("") {
            paragraph "<small>${APP_NAME} v${CODE_VERSION}</small>"
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

    // Migrate legacy `enableDebug` pref to the standard `debugEnable` name
    if (settings.enableDebug != null) {
        app.updateSetting("debugEnable", [value: settings.enableDebug as String, type: "bool"])
        app.removeSetting("enableDebug")
    }

    if (debugEnable || traceEnable) runIn(1800, "logsOff")

    // Initialize humidity state machine (only if not already set)
    if (state.humidityState == null) {
        state.humidityState = HUMIDITY_NORMAL
    }

    // Initialize fan control state (only if not already set)
    if (state.fanTurnedOnByApp == null) {
        state.fanTurnedOnByApp = false
    }

    // Clear stale pending command (verification timers were killed by unschedule())
    state.pendingCommand = null

    // Clear stale physical-run-floor state (the runIn timer was killed by unschedule())
    state.physicalRunStartedAt = null
    state.deferredOffReason = null

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

    // Subscribe to occupancy motion (gates the fast-climb bypass)
    if (occupancySensors) {
        subscribe(occupancySensors, "motion.active", occupancyHandler)
    }

    // Reschedule pending timers if we're in a pending state (timers were cleared by unschedule())
    reschedulePendingTimers()

    // Consistency check: if in HIGH/PENDING_NORMAL but snapshot is missing, reset to NORMAL
    if ((state.humidityState == HUMIDITY_HIGH || state.humidityState == HUMIDITY_PENDING_NORMAL)
            && state.referenceHumiditySnapshot == null) {
        logWarn("Inconsistent state: ${state.humidityState} with no reference snapshot. Resetting to NORMAL.")
        state.humidityState = HUMIDITY_NORMAL
        state.pendingStateSince = null
    }

    // Consistency check: if humidity is NORMAL but we think we control the fan, turn it off
    if (state.humidityState == HUMIDITY_NORMAL && state.fanTurnedOnByApp) {
        logWarn("Inconsistent state detected: humidity NORMAL but fanTurnedOnByApp=true. Turning off fan.")
        turnOffFan()
    }

    // Consistency check: if humidity is HIGH and we should control the fan but don't
    if (isHumidityHigh() && !state.fanTurnedOnByApp && !isRestricted()) {
        String fanState = fanSwitch.currentValue("switch")
        if (fanState == "on") {
            // Fan is already on (e.g. turned on manually or by another app) - adopt control
            logInfo("Humidity is HIGH and fan is already ON - adopting control of fan")
            state.fanTurnedOnByApp = true
            if (!state.lastHumidityEventTime) state.lastHumidityEventTime = now()
        } else {
            logWarn("Inconsistent state detected: humidity HIGH, not restricted, but fan not on. Turning on fan.")
            turnOnFan()
        }
    }

    // Reschedule max fan run timer if fan is on and controlled by app
    if (state.fanTurnedOnByApp && state.lastHumidityEventTime) {
        rescheduleMaxFanRunTimer()
    }

    // Initial evaluation to sync state
    evaluateHumidityStateMachine()

    logInfo("${APP_NAME} initialized. Humidity: ${state.humidityState}, Fan controlled by app: ${state.fanTurnedOnByApp}")
}

// ==================== Event Handlers ====================

void bathroomHumidityHandler(evt) {
    logDebug("Bathroom humidity event from ${evt.device}: ${evt.value}%")
    state.lastHumidityEventTime = now()
    recordBathroomSample(evt.value as BigDecimal)

    // Reset max fan run timer on every humidity event if fan is running
    if (state.fanTurnedOnByApp) {
        resetMaxFanRunTimer()
    }

    evaluateHumidityStateMachine(evt.device)
}

private void recordBathroomSample(BigDecimal value) {
    Integer slopeWin = (slopeWindowSeconds != null ? slopeWindowSeconds : DEFAULT_SLOPE_WINDOW_SECONDS) as Integer
    Integer burstWin = (burstWindowSeconds != null ? burstWindowSeconds : DEFAULT_BURST_WINDOW_SECONDS) as Integer
    state.bathroomSamples = appendSample(
        (state.bathroomSamples ?: []) as List,
        now(),
        value,
        Math.max(slopeWin, burstWin) * 1000L,
        SAMPLE_BUFFER_CAP
    )
}

// Pure: prune samples older than (nowMs - maxWindowMs), append (nowMs, value),
// then cap the buffer. Used by the dynamic recordBathroomSample wrapper.
@CompileStatic
private static List appendSample(List samples, long nowMs, BigDecimal value, long maxWindowMs, int cap) {
    long cutoff = nowMs - maxWindowMs
    List pruned = []
    for (Object s : samples) {
        List entry = (List) s
        if (((Number) entry[0]).longValue() >= cutoff) {
            pruned.add(entry)
        }
    }
    pruned.add([nowMs, value])
    int n = pruned.size()
    if (n > cap) {
        return pruned.subList(n - cap, n)
    }
    return pruned
}

void referenceHumidityHandler(evt) {
    logDebug("Reference humidity event from ${evt.device}: ${evt.value}%")
    // Reference sensor events don't reset the max fan timer, but we still evaluate
    evaluateHumidityStateMachine(evt.device)
}

void fanSwitchHandler(evt) {
    logDebug("Fan switch changed to ${evt.value}")

    // Any switch event resolves a pending verification — the verifyFan*
    // timer is only a fallback for missed events. Unschedule both timers
    // on every event: a no-op when nothing was scheduled, defensive when
    // the device emitted the opposite of the pending command.
    unschedule("verifyFanOn")
    unschedule("verifyFanOff")

    if (evt.value == "on") {
        if (state.pendingCommand == "on") {
            logInfo("Fan verified ON")
            state.pendingCommand = null
        } else if (state.pendingCommand == "off") {
            // We requested OFF but the switch went ON — externally countermanded.
            logInfo("Pending OFF countermanded by external ON event - clearing")
            state.pendingCommand = null
        } else if (evt.type == "physical") {
            // Human paddle press with no app activity in flight — arm the run floor.
            armPhysicalRunFloor()
        }
        // else: external (non-physical) on with no pending command — leave state alone
    } else if (evt.value == "off") {
        // Any off event clears the physical floor and any deferred automation off,
        // regardless of source. A human off is honored immediately; an automation off
        // arrived via requestFanOff()/turnOffFan() so the floor is already satisfied
        // (or this is the deferred off being executed now).
        cancelPhysicalRunFloor("fan went off")

        if (state.pendingCommand == "off") {
            logInfo("Fan verified OFF")
            state.fanTurnedOnByApp = false
            state.pendingCommand = null
        } else if (state.pendingCommand == "on") {
            // We requested ON but the switch went OFF — externally countermanded.
            logInfo("Pending ON countermanded by external OFF event - clearing")
            state.fanTurnedOnByApp = false
            state.pendingCommand = null
        } else if (state.fanTurnedOnByApp) {
            // External off while we thought we controlled the fan
            logInfo("Fan turned off externally - clearing app control flag")
            state.fanTurnedOnByApp = false
        }
    }
}

void occupancyHandler(evt) {
    logDebug("Occupancy motion event from ${evt.device}: ${evt.value}")
    state.lastMotionActiveTime = now()
}

void restrictionSwitchHandler(evt) {
    logDebug("Restriction switch ${evt.device} changed to ${evt.value}")

    Boolean nowRestricted = isRestricted()

    if (nowRestricted && state.fanTurnedOnByApp) {
        // Restriction activated and we have the fan on - turn it off
        logInfo("Restriction activated - turning off fan")
        requestFanOff("restriction activated")
    } else if (!nowRestricted && isHumidityHigh()) {
        // Restriction lifted and humidity is high - turn fan on
        logInfo("Restriction lifted while humidity is high - turning on fan")
        turnOnFan()
    }
}

// ==================== Humidity State Machine ====================

private void evaluateHumidityStateMachine(reportingDevice = null) {
    BigDecimal bathroomHumidity = computeMedianHumidity(bathroomHumiditySensors, reportingDevice)
    BigDecimal referenceHumidity = computeMedianHumidity(referenceHumiditySensors, reportingDevice)

    if (bathroomHumidity == null) {
        logWarn("No active bathroom sensors - skipping humidity evaluation")
        return
    }

    String oldState = state.humidityState
    logDebug("Humidity state machine: state=${oldState}, bathroom=${bathroomHumidity}% (median), reference=${referenceHumidity}% (median)")

    switch (state.humidityState) {
        case HUMIDITY_NORMAL:
            if (referenceHumidity == null && bathroomHumidity <= absoluteHighThreshold) {
                logWarn("No active reference sensors - cannot evaluate activation threshold")
                return
            }
            evaluateNormalState(bathroomHumidity, referenceHumidity ?: absoluteLowThreshold)
            break
        case HUMIDITY_PENDING_HIGH:
            if (referenceHumidity == null && bathroomHumidity <= absoluteHighThreshold) {
                logInfo("Reference sensors lost during activation delay - returning to NORMAL")
                unschedule("delayedTransitionToHigh")
                state.pendingStateSince = null
                state.referenceHumiditySnapshot = null
                transitionHumidityState(HUMIDITY_NORMAL)
                return
            }
            evaluatePendingHighState(bathroomHumidity, referenceHumidity ?: absoluteLowThreshold)
            break
        case HUMIDITY_HIGH:
            evaluateHighState(bathroomHumidity)
            break
        case HUMIDITY_PENDING_NORMAL:
            evaluatePendingNormalState(bathroomHumidity)
            break
        default:
            logWarn("Unknown humidity state: ${state.humidityState}, resetting to NORMAL")
            transitionHumidityState(HUMIDITY_NORMAL)
    }
}

private void evaluateNormalState(BigDecimal bathroomHumidity, BigDecimal referenceHumidity) {
    if (isAboveActivationThreshold(bathroomHumidity, referenceHumidity)) {
        // Snapshot the reference humidity at activation time
        state.referenceHumiditySnapshot = referenceHumidity

        // Excessive-change bypass: single-event Δ ≥ threshold skips the
        // activation delay. Covers slow-sensor one-shot spikes that
        // fast-climb (which needs ≥burstEventCount samples) cannot detect.
        if (isExcessiveChange(bathroomHumidity)) {
            BigDecimal prevValue = previousBathroomSample()
            logInfo("Excessive humidity change (bath=${bathroomHumidity}%, Δ=${bathroomHumidity - prevValue}%) - bypassing activation delay")
            transitionHumidityState(HUMIDITY_HIGH)
            onHumidityBecameHigh()
            return
        }

        // Fast-climb bypass: rapid rising burst skips the activation delay.
        if (isFastClimb()) {
            logInfo("Fast humidity climb detected (bath=${bathroomHumidity}%) - bypassing activation delay")
            transitionHumidityState(HUMIDITY_HIGH)
            onHumidityBecameHigh()
            return
        }

        String reason = getActivationReason(bathroomHumidity, referenceHumidity)
        logInfo("Humidity above activation threshold: ${reason} - starting ${activationDelay}s delay")
        state.pendingStateSince = now()

        transitionHumidityState(HUMIDITY_PENDING_HIGH)

        // Schedule the delayed transition to HIGH
        runIn(activationDelay as Integer, "delayedTransitionToHigh")
    }
}

private void evaluatePendingHighState(BigDecimal bathroomHumidity, BigDecimal referenceHumidity) {
    if (!isAboveActivationThreshold(bathroomHumidity, referenceHumidity)) {
        // Humidity dropped - cancel pending activation
        logInfo("Humidity dropped during activation delay - returning to NORMAL")
        unschedule("delayedTransitionToHigh")
        state.pendingStateSince = null
        state.referenceHumiditySnapshot = null
        transitionHumidityState(HUMIDITY_NORMAL)
        return
    }

    // Above absolute high - bypass activation delay, go straight to HIGH
    if (bathroomHumidity > absoluteHighThreshold) {
        logInfo("Humidity ${bathroomHumidity}% above absolute high ${absoluteHighThreshold}% - bypassing activation delay")
        unschedule("delayedTransitionToHigh")
        state.pendingStateSince = null
        transitionHumidityState(HUMIDITY_HIGH)
        onHumidityBecameHigh()
        return
    }

    // Excessive-change bypass mid-pending - single-event Δ ≥ threshold
    if (isExcessiveChange(bathroomHumidity)) {
        BigDecimal prevValue = previousBathroomSample()
        logInfo("Excessive humidity change (bath=${bathroomHumidity}%, Δ=${bathroomHumidity - prevValue}%) - bypassing activation delay")
        unschedule("delayedTransitionToHigh")
        state.pendingStateSince = null
        transitionHumidityState(HUMIDITY_HIGH)
        onHumidityBecameHigh()
        return
    }

    // Fast humidity climb detected mid-pending - bypass activation delay
    if (isFastClimb()) {
        logInfo("Fast humidity climb detected (bath=${bathroomHumidity}%) - bypassing activation delay")
        unschedule("delayedTransitionToHigh")
        state.pendingStateSince = null
        transitionHumidityState(HUMIDITY_HIGH)
        onHumidityBecameHigh()
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
    logInfo("Activation delay completed - transitioning to HIGH")

    state.pendingStateSince = null
    transitionHumidityState(HUMIDITY_HIGH)

    // React to state change: turn on fan if not restricted
    onHumidityBecameHigh()
}

private void evaluateHighState(BigDecimal bathroomHumidity) {
    if (isBelowDeactivationThreshold(bathroomHumidity)) {
        String reason = getDeactivationReason(bathroomHumidity)
        logInfo("Humidity below deactivation threshold: ${reason} - starting ${deactivationDelay}s delay")

        state.pendingStateSince = now()
        transitionHumidityState(HUMIDITY_PENDING_NORMAL)

        // Schedule the delayed transition to NORMAL
        runIn(deactivationDelay as Integer, "delayedTransitionToNormal")
    }
}

private void evaluatePendingNormalState(BigDecimal bathroomHumidity) {
    if (!isBelowDeactivationThreshold(bathroomHumidity)) {
        // Humidity rose - cancel pending deactivation
        logInfo("Humidity rose during deactivation delay - returning to HIGH")
        unschedule("delayedTransitionToNormal")
        state.pendingStateSince = null
        transitionHumidityState(HUMIDITY_HIGH)

        // React to state change: turn on fan if not restricted - no delayed activation
        onHumidityBecameHigh()
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
    logInfo("Deactivation delay completed - transitioning to NORMAL")

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
        logInfo("Humidity state: ${oldState} -> ${newState}")
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
                logInfo("Rescheduling activation timer: ${remainingSeconds}s remaining")
                runIn(remainingSeconds, "delayedTransitionToHigh")
            } else {
                // Timer should have already fired - trigger it now
                logInfo("Activation timer expired during reconfiguration - triggering now")
                runIn(1, "delayedTransitionToHigh")
            }
            break

        case HUMIDITY_PENDING_NORMAL:
            Long deactivationDelayMs = (deactivationDelay as Integer) * 1000
            Long remainingMsDeact = deactivationDelayMs - elapsedMs
            if (remainingMsDeact > 0) {
                Integer remainingSeconds = (remainingMsDeact / 1000).toInteger() + 1  // Round up
                logInfo("Rescheduling deactivation timer: ${remainingSeconds}s remaining")
                runIn(remainingSeconds, "delayedTransitionToNormal")
            } else {
                // Timer should have already fired - trigger it now
                logInfo("Deactivation timer expired during reconfiguration - triggering now")
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
    if (bathroomHumidity > highThreshold) {
        return true
    }

    // Activate on fast humidity climb (rate-of-change)
    return isFastClimb()
}

// Returns the bathroom sample BEFORE the most recent one, or null if
// fewer than 2 samples exist. recordBathroomSample appends the current
// reading before the state machine evaluates, so [-2] is the prior one.
private BigDecimal previousBathroomSample() {
    List samples = (state.bathroomSamples ?: []) as List
    if (samples.size() < 2) return null
    List prev = (List) samples[samples.size() - 2]
    return ((Number) prev[1]) as BigDecimal
}

// Single-event bypass: returns true when the current reading exceeds the
// previous bathroom sample by at least excessiveChangeThreshold. Returns
// false when threshold is 0 (disabled) or no prior sample exists.
private Boolean isExcessiveChange(BigDecimal currentValue) {
    BigDecimal threshold = (excessiveChangeThreshold ?: DEFAULT_EXCESSIVE_CHANGE_THRESHOLD) as BigDecimal
    if (threshold <= 0G) return false
    BigDecimal prevValue = previousBathroomSample()
    if (prevValue == null) return false
    return (currentValue - prevValue) >= threshold
}

// Burst + slope detector: returns true only when BOTH conditions hold —
// (a) at least burstEventCount bathroom events in the last burstWindowSeconds,
// (b) slope across the last burstEventCount samples (within slopeWindowSeconds)
// is ≥ slopeActivationThreshold %RH/min. Combined-AND keeps false-positive
// rate low while catching the fast-onset shower bursts (~75% of episodes in
// 30-day data).
private Boolean isFastClimb() {
    // Occupancy gate: if any sensors are configured, block the bypass when
    // none has been active within the window. No sensors = always armed.
    if (occupancySensors && !isOccupiedRecently()) {
        logDebug("Fast-climb blocked by occupancy gate")
        return false
    }

    // Treat unset prefs as the defined defaults; only an explicit 0 disables.
    BigDecimal slopeThresh = (slopeActivationThreshold != null ? slopeActivationThreshold : DEFAULT_SLOPE_ACTIVATION_THRESHOLD) as BigDecimal
    Integer burstCount = (burstEventCount != null ? burstEventCount : DEFAULT_BURST_EVENT_COUNT) as Integer
    Integer burstWin = (burstWindowSeconds != null ? burstWindowSeconds : DEFAULT_BURST_WINDOW_SECONDS) as Integer
    Integer slopeWin = (slopeWindowSeconds != null ? slopeWindowSeconds : DEFAULT_SLOPE_WINDOW_SECONDS) as Integer
    return computeFastClimb(
        (state.bathroomSamples ?: []) as List,
        now(),
        slopeThresh,
        burstCount,
        burstWin,
        slopeWin
    )
}

private Boolean isOccupiedRecently() {
    // Currently-active sensor counts immediately, even if we never received
    // a motion.active event (e.g. first run after install).
    if (occupancySensors.any { it.currentValue("motion") == "active" }) return true
    Long lastMotion = (state.lastMotionActiveTime ?: 0L) as Long
    if (lastMotion <= 0L) return false
    Integer winSec = (occupancyWindowSeconds != null ? occupancyWindowSeconds : DEFAULT_OCCUPANCY_WINDOW_SECONDS) as Integer
    return (now() - lastMotion) <= winSec * 1000L
}

// Pure: combined burst+slope detector. Returns true iff at least
// burstCount samples lie within burstWin seconds of nowMs AND the slope
// across the last burstCount samples (within slopeWin seconds) is
// >= slopeThresh %RH/min. Caller passes settings + state snapshot.
@CompileStatic
private static boolean computeFastClimb(List samples, long nowMs,
                                        BigDecimal slopeThresh, int burstCount,
                                        int burstWin, int slopeWin) {
    if (slopeThresh <= 0G) return false
    if (burstCount < 2) return false
    if (samples.size() < burstCount) return false

    long burstCutoff = nowMs - burstWin * 1000L
    int recent = 0
    for (Object s : samples) {
        List entry = (List) s
        if (((Number) entry[0]).longValue() >= burstCutoff) recent++
    }
    if (recent < burstCount) return false

    long slopeCutoff = nowMs - slopeWin * 1000L
    List slopeSamples = []
    for (Object s : samples) {
        List entry = (List) s
        if (((Number) entry[0]).longValue() >= slopeCutoff) slopeSamples.add(entry)
    }
    int n = slopeSamples.size()
    if (n < burstCount) return false

    List firstEntry = (List) slopeSamples[n - burstCount]
    List lastEntry = (List) slopeSamples[n - 1]
    long spanMs = ((Number) lastEntry[0]).longValue() - ((Number) firstEntry[0]).longValue()
    if (spanMs <= 0L) return false

    BigDecimal spanMin = (spanMs as BigDecimal) / 60000.0G
    BigDecimal slope = (((Number) lastEntry[1]) as BigDecimal) - (((Number) firstEntry[1]) as BigDecimal)
    slope = slope / spanMin
    return slope >= slopeThresh
}

private BigDecimal getEffectiveReferenceSnapshot() {
    return (state.referenceHumiditySnapshot ?: absoluteLowThreshold) as BigDecimal
}

private Boolean isBelowDeactivationThreshold(BigDecimal bathroomHumidity) {
    // Never deactivate if above absolute high threshold
    if (bathroomHumidity > absoluteHighThreshold) {
        return false
    }

    // Use the snapshot reference for deactivation calculation
    BigDecimal effectiveReference = getEffectiveReferenceSnapshot()
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
    BigDecimal effectiveReference = getEffectiveReferenceSnapshot()
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
        requestFanOff("humidity normalized")
    } else {
        logDebug("Humidity became NORMAL but fan was not turned on by app - leaving it alone")
    }
}

private void turnOnFan() {
    logInfo("Turning on fan")

    state.fanTurnedOnByApp = true
    state.pendingCommand = "on"
    fanSwitch.on()

    // Cancel any pending verification for the opposite state
    unschedule("verifyFanOff")
    runIn(switchVerificationTimeout as Integer, "verifyFanOn")

    // Start max fan run timer
    scheduleMaxFanRunTimer()
}

void verifyFanOn() {
    // Fires only when no switch event arrived within the verification
    // window (fanSwitchHandler unschedules this on any event). If the
    // device's current value is "on" anyway, the event was missed or
    // the switch was already on (sendEvent dedup) — treat as verified.
    String switchState = fanSwitch.currentValue("switch")

    if (switchState == "on") {
        logInfo("Fan verified ON (timeout check)")
        state.pendingCommand = null
    } else {
        logError("Fan failed to turn on after ${switchVerificationTimeout} seconds (no event received)")
        state.fanTurnedOnByApp = false
        sendNotification("ERROR: ${fanSwitch.displayName} failed to turn on!")
        state.pendingCommand = null
    }
}

private void turnOffFan() {
    logInfo("Turning off fan")

    state.pendingCommand = "off"
    fanSwitch.off()

    // Cancel max fan run timer and any pending verification for the opposite state
    unschedule("maxFanRunTimeExpired")
    unschedule("verifyFanOn")

    runIn(switchVerificationTimeout as Integer, "verifyFanOff")
}

void verifyFanOff() {
    // Same pattern as verifyFanOn — only fires when no switch event arrived
    // within the verification window.
    String switchState = fanSwitch.currentValue("switch")

    if (switchState == "off") {
        logInfo("Fan verified OFF (timeout check)")
        state.fanTurnedOnByApp = false
        state.pendingCommand = null
    } else {
        logError("Fan failed to turn off after ${switchVerificationTimeout} seconds (no event received)")
        sendNotification("ERROR: ${fanSwitch.displayName} failed to turn off!")
        state.pendingCommand = null
    }
}

// ==================== Max Fan Run Time ====================

private Boolean isMaxFanRunTimeEnabled() {
    return maxFanRunTime && (maxFanRunTime as Integer) > 0
}

private void scheduleMaxFanRunTimer() {
    if (!isMaxFanRunTimeEnabled()) return

    Integer delaySeconds = (maxFanRunTime as Integer) * 60
    runIn(delaySeconds, "maxFanRunTimeExpired")
    logDebug("Max fan run timer scheduled for ${maxFanRunTime} minutes")
}

private void resetMaxFanRunTimer() {
    if (!isMaxFanRunTimeEnabled()) return

    unschedule("maxFanRunTimeExpired")
    scheduleMaxFanRunTimer()
    logDebug("Max fan run timer reset")
}

private void rescheduleMaxFanRunTimer() {
    if (!isMaxFanRunTimeEnabled()) return

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

    logWarn("Max fan run time (${maxFanRunTime} min) expired without humidity events - turning off fan")
    sendNotification("WARNING: Fan turned off after ${maxFanRunTime} minutes - check humidity sensors")

    // Transition humidity state to NORMAL since we're giving up
    state.pendingStateSince = null
    state.referenceHumiditySnapshot = null
    transitionHumidityState(HUMIDITY_NORMAL)

    // Direct turnOffFan() — Max Fan Run Time is the safety ceiling and overrides
    // the physical-run floor. The fanSwitchHandler will clean up any pending
    // floor state when the off event lands.
    turnOffFan()
}

// ==================== Physical-Run Floor ====================
//
// When the fan is turned on with a physical paddle press (evt.type == 'physical'),
// guarantee a minimum run time before any automation-driven off can take effect.
// The human paddle press still cancels immediately (fanSwitchHandler 'off' branch
// clears the floor unconditionally). The Max Fan Run Time safety ceiling
// (maxFanRunTimeExpired) bypasses the floor.
//
// Flow:
//   - armPhysicalRunFloor()   : called from fanSwitchHandler on physical-on
//   - cancelPhysicalRunFloor(): called from fanSwitchHandler on any off
//   - requestFanOff(reason)   : wrapper used by automation-side off paths
//                               (humidity-normalize, restriction-enforce). If
//                               the floor is active, defers; the deferred off
//                               fires when physicalRunFloorReached() runs.
//   - physicalRunFloorReached(): runIn callback — honors deferred off if set,
//                                else if no app ownership and fan still on,
//                                turns it off.

private Boolean isPhysicalRunFloorEnabled() {
    return settings.physicalRunTimerEnabled
}

private Boolean isPhysicalRunFloorActive() {
    return state.physicalRunStartedAt != null
}

private void armPhysicalRunFloor() {
    if (!isPhysicalRunFloorEnabled()) {
        logDebug("Physical activation detected but auto-off feature is disabled")
        return
    }
    Integer mins = (settings.physicalRunTimerMinutes ?: DEFAULT_PHYSICAL_RUN_TIMER_MINUTES) as Integer
    state.physicalRunStartedAt = now()
    state.deferredOffReason = null
    unschedule("physicalRunFloorReached")
    runIn(mins * 60, "physicalRunFloorReached")
    logInfo("Physical activation detected — auto-off in ${mins} min (minimum run floor)")
}

private void cancelPhysicalRunFloor(String reason) {
    if (!isPhysicalRunFloorActive() && !state.deferredOffReason) return
    unschedule("physicalRunFloorReached")
    String deferred = state.deferredOffReason
    state.physicalRunStartedAt = null
    state.deferredOffReason = null
    if (deferred) {
        logInfo("Physical-run floor cleared (${reason}); deferred off (${deferred}) abandoned")
    } else {
        logDebug("Physical-run floor cleared (${reason})")
    }
}

// Wrapper for automation-side fan-off requests. When the physical-run floor
// is active, the off is deferred and recorded as state.deferredOffReason;
// physicalRunFloorReached() will fire it when the floor elapses. When no
// floor is active, this is just a passthrough to turnOffFan().
private void requestFanOff(String reason) {
    if (isPhysicalRunFloorActive()) {
        Long startedAt = state.physicalRunStartedAt as Long
        Integer mins = (settings.physicalRunTimerMinutes ?: DEFAULT_PHYSICAL_RUN_TIMER_MINUTES) as Integer
        Long remainingMs = (startedAt + mins * 60000L) - now()
        Long remainingSec = Math.max(0L, remainingMs / 1000L) as Long
        logInfo("Off request from '${reason}' deferred — physical-run floor has ~${remainingSec}s remaining")
        state.deferredOffReason = reason
        return
    }
    turnOffFan()
}

void physicalRunFloorReached() {
    String deferred = state.deferredOffReason
    state.physicalRunStartedAt = null
    state.deferredOffReason = null

    if (deferred) {
        // An automation wanted off while the floor was active — honor it now.
        logInfo("Physical-run floor reached; executing deferred OFF (was: ${deferred})")
        turnOffFan()
        return
    }
    if (state.fanTurnedOnByApp) {
        // HFC took ownership during the physical run (humidity went HIGH) and
        // humidity is still managing the fan. Defer to the humidity state
        // machine — when humidity normalizes, onHumidityBecameNormal() will
        // request the off through the normal path (no floor in the way now).
        logDebug("Physical-run floor reached; HFC owns fan, leaving humidity in control")
        return
    }
    // No automation owns the fan, no deferred off pending. This is the original
    // "physical activation, no humidity event ever fired" case — auto-off.
    if (fanSwitch.currentValue("switch") == "on") {
        logInfo("Physical-run floor reached (${settings.physicalRunTimerMinutes ?: DEFAULT_PHYSICAL_RUN_TIMER_MINUTES} min); turning off fan")
        turnOffFan()
    } else {
        logDebug("Physical-run floor reached but fan is already off")
    }
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
        if (isMaxFanRunTimeEnabled() && state.lastHumidityEventTime) {
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

private List getActiveSensors(List sensors, reportingDevice = null) {
    if (!sensors) {
        return []
    }

    Date now = new Date()
    Date cutoff = new Date(now.time - (sensorInactivityTimeout as Integer) * 60 * 1000)

    List activeSensors = []
    List inactiveSensors = []

    sensors.each { sensor ->
        // If this sensor just reported, it's definitely active
        if (reportingDevice && sensor.id == reportingDevice.id) {
            if (sensor.currentValue("humidity") != null) {
                activeSensors << sensor
                return
            }
        }

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

private BigDecimal computeMedianHumidity(List sensors, reportingDevice = null) {
    List activeSensors = getActiveSensors(sensors, reportingDevice)

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

    return [
        active: activeSensors.size(),
        total: sensors.size(),
        excluded: excludedNames,
        median: computeMedianHumidity(sensors)
    ]
}

// ==================== Utilities ====================

private void sendNotification(String message) {
    if (notificationDevice) {
        notificationDevice.deviceNotification(message)
        logInfo("Notification sent: ${message}")
    } else {
        logWarn("No notification device configured. Message: ${message}")
    }
}

private void logTrace(String message) { if (traceEnable) log.trace(message) }
private void logDebug(String message) { if (debugEnable) log.debug(message) }
private void logInfo(String message)  { if (txtEnable)   log.info(message) }
private void logWarn(String message)  { log.warn(message) }
private void logError(String message) { log.error(message) }

void logsOff() {
    log.warn "Debug/trace logging auto-disabled"
    app.updateSetting("debugEnable", [value: "false", type: "bool"])
    app.updateSetting("traceEnable", [value: "false", type: "bool"])
}

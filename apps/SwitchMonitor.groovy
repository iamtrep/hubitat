/*
 MIT License

 Copyright (c) 2026 pj

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

 Switch Monitor

 Monitors switches that must remain on (or off) at all times. If any monitored
 switch deviates from its target state and stays that way for a configurable
 grace period, the app automatically corrects it. It retries in a loop until
 the switch responds or the maximum number of attempts is reached, and
 optionally notifies the user.

 For must-stay-on switches with power metering, the app can also monitor the
 connected load and notify the user if power drops below a per-device minimum
 watt threshold (e.g., a freezer compressor failing).

 == Flow (must-stay-on) ==

 Event-driven (normal operation):
 1. A monitored switch turns off
 2. Wait the grace period (default 5 minutes)
 3. If still off: notify "turned off — turning back on", send on() command
 4. Wait verification delay (default 10 seconds), then check:
    - If on: recovery complete
    - If still off: notify "did not turn back on!", wait retry interval,
      then go back to step 3
 5. Give up after max retries (or loop forever if set to 0)

 == Flow (must-stay-off) ==

 Same as above but inverted: if a switch turns on, wait the grace period,
 then send off() command with the same retry/verify logic.

 == Load monitoring (must-stay-on only) ==

 1. A power event arrives below the per-device minimum watt threshold
 2. Wait the load grace period (default 2 minutes)
 3. If still below threshold and switch is on: notify user
 4. Re-notify at the configured reminder interval until power recovers

 == State-based (startup / preferences saved) ==

 1. Refresh all switches that support it (startup only)
 2. For each switch in the wrong state, look up event history timestamp
 3. If wrong longer than the grace period (or no event found): recover immediately
 4. If wrong for less than the grace period: schedule a delayed check

 == Power outage awareness (optional) ==

 - A switch (ON = outage) or PowerSource device (not mains = outage) signals an outage
 - Active recovery is cancelled during the outage for must-stay-on switches
 - Load monitoring alerts are cleared during outage
 - When power is restored: refresh all monitored switches, then re-evaluate

 */

import groovy.transform.CompileStatic
import groovy.transform.Field

@Field static final String APP_NAME = "Switch Monitor"
@Field static final String APP_VERSION = "2.0.0"

@Field static final Integer DEFAULT_GRACE_MINUTES = 5
@Field static final Integer DEFAULT_RETRY_INTERVAL_SECONDS = 30
@Field static final Integer DEFAULT_VERIFY_DELAY_SECONDS = 10
@Field static final Integer DEFAULT_MAX_RETRIES = 10
@Field static final Integer DEFAULT_LOAD_GRACE_MINUTES = 2
@Field static final Integer DEFAULT_LOAD_REMINDER_MINUTES = 30
@Field static final Integer STARTUP_REFRESH_DELAY_SECONDS = 5
@Field static final Integer POST_OUTAGE_REFRESH_DELAY_SECONDS = 5

definition(
    name: APP_NAME,
    namespace: "iamtrep",
    author: "pj",
    description: "Monitors switches that must remain on or off. Automatically corrects deviations with configurable retry logic, optional notifications, and load monitoring for power-reporting devices.",
    category: "Convenience",
    singleInstance: false,
    importUrl: "https://raw.githubusercontent.com/iamtrep/hubitat/refs/heads/main/apps/AlwaysOnSwitchMonitor.groovy",
    iconUrl: "",
    iconX2Url: "",
    iconX3Url: ""
)

preferences {
    page(name: "mainPage")
    page(name: "checkNowPage")
}

Map mainPage() {
    dynamicPage(name: "mainPage", title: "", install: true, uninstall: true) {
        section("Current Status") {
            paragraph getStatusText()
            href "checkNowPage", title: "Check all devices now", description: "Run a full evaluation of all switches and load levels"
        }

        section("App Label") {
            label title: "Name this instance", required: false
        }

        section("Switches") {
            if (switches && !switchesOn) {
                paragraph "<b>Note:</b> This app was upgraded from v1. Please re-select your switches below — the previous selection ('switches') is no longer used."
            }
            input "switchesOn", "capability.switch", title: "Switches that must stay ON", multiple: true, required: false, submitOnChange: true
            input "switchesOff", "capability.switch", title: "Switches that must stay OFF", multiple: true, required: false
        }

        section("Notifications") {
            input "notifyDevices", "capability.notification", title: "Notification devices", multiple: true, required: false
            input "notifyOnRecovery", "bool", title: "Notify when correcting a switch", defaultValue: true
            input "notifyOnFailure", "bool", title: "Notify when a switch fails to recover", defaultValue: true
            input "notifyOnLowLoad", "bool", title: "Notify when load drops below minimum", defaultValue: true
        }

        section("Load Monitoring", hideable: true, hidden: true) {
            paragraph "Monitor connected load on must-stay-on switches that report power. Set a minimum watt threshold per device. If the load drops below the threshold while the switch is on, you will be notified."
            input "enableLoadMonitoring", "bool", title: "Enable load monitoring", defaultValue: false, submitOnChange: true
            if (enableLoadMonitoring && switchesOn) {
                List powerDevices = switchesOn.findAll { it.hasCapability("PowerMeter") }
                if (powerDevices) {
                    powerDevices.each { dev ->
                        input "minLoadWatts_${dev.id}", "decimal", title: "${dev.displayName} — minimum load (watts)", defaultValue: 0, required: false, range: "0..10000"
                    }
                    input "loadGraceMinutes", "number", title: "Minutes to wait before alerting on low load", defaultValue: DEFAULT_LOAD_GRACE_MINUTES, required: true, range: "1..60"
                    input "loadReminderMinutes", "number", title: "Minutes between repeat notifications", defaultValue: DEFAULT_LOAD_REMINDER_MINUTES, required: true, range: "5..1440"
                } else {
                    paragraph "None of the selected must-stay-on switches support power reporting."
                }
            }
        }

        section("Power Outage", hideable: true, hidden: true) {
            paragraph "Optionally pause recovery during a power outage. Choose a switch (ON = outage) or a device with the PowerSource capability (outage when not on mains). When power is restored, all monitored switches are refreshed and re-evaluated."
            input "outageIndicatorSwitch", "capability.switch", title: "Outage indicator switch (ON = outage)", required: false
            input "outageIndicatorPower", "capability.powerSource", title: "Power source device (outage when not on mains)", required: false
        }

        section("Timing", hideable: true, hidden: true) {
            input "graceMinutes", "number", title: "Minutes to wait before taking action", defaultValue: DEFAULT_GRACE_MINUTES, required: true, range: "0..60"
            input "retryInterval", "number", title: "Seconds between retries", defaultValue: DEFAULT_RETRY_INTERVAL_SECONDS, required: true, range: "5..300"
            input "verifyDelay", "number", title: "Seconds to wait before verifying switch state", defaultValue: DEFAULT_VERIFY_DELAY_SECONDS, required: true, range: "1..60"
            input "maxRetries", "number", title: "Maximum retry attempts (0 = unlimited)", defaultValue: DEFAULT_MAX_RETRIES, required: true, range: "0..100"
        }

        section("Logging", hideable: true, hidden: true) {
            input "enableDebug", "bool", title: "Enable debug logging", defaultValue: false
        }
    }
}

Map checkNowPage() {
    // Refresh all devices that support it
    List allSwitches = getAllMonitoredSwitches()
    List refreshable = allSwitches.findAll { it.hasCommand("refresh") }
    if (refreshable) {
        refreshable.each { it.refresh() }
    }

    // Run switch evaluation
    evaluateSwitches("Manual check")

    // Run load evaluation
    if (enableLoadMonitoring && switchesOn && !state.powerOutage) {
        evaluateCurrentLoad()
    }

    dynamicPage(name: "checkNowPage", title: "Check All Devices", install: false, uninstall: false) {
        section {
            paragraph getStatusText()
            paragraph "<i>Refreshed ${refreshable.size()} device(s) and triggered evaluation. Tap Done to return.</i>"
        }
    }
}

// ── Lifecycle ────────────────────────────────────────────────────────────────

void installed() {
    initialize()
}

void updated() {
    unsubscribe()
    unschedule()
    initialize()
}

void uninstalled() {
    unschedule()
}

void initialize() {
    state.recoveryActiveOn = false
    state.retryCountOn = 0
    state.recoveryActiveOff = false
    state.retryCountOff = 0
    state.powerOutage = isOutageActive()

    // Preserve low-load tracking across preference saves; prune devices no longer monitored or with threshold 0
    Map lowLoadDevices = (Map)(state.lowLoadDevices ?: [:])
    if (enableLoadMonitoring && switchesOn) {
        Set activeIds = switchesOn.findAll { it.hasCapability("PowerMeter") }
            .findAll { getDecimalSetting("minLoadWatts_${it.id}", 0) > 0 }
            .collect { it.id.toString() } as Set
        lowLoadDevices.keySet().retainAll(activeIds)
    } else {
        lowLoadDevices.clear()
    }
    state.lowLoadDevices = lowLoadDevices

    if (switchesOn) {
        subscribe(switchesOn, "switch.off", mustStayOnTurnedOffHandler)
        subscribe(switchesOn, "switch.on", mustStayOnTurnedOnHandler)
    }
    if (switchesOff) {
        subscribe(switchesOff, "switch.on", mustStayOffTurnedOnHandler)
        subscribe(switchesOff, "switch.off", mustStayOffTurnedOffHandler)
    }
    subscribe(location, "systemStart", systemStartHandler)

    if (outageIndicatorSwitch) {
        subscribe(outageIndicatorSwitch, "switch", outageIndicatorSwitchHandler)
    }
    if (outageIndicatorPower) {
        subscribe(outageIndicatorPower, "powerSource", outageIndicatorPowerHandler)
    }

    // Load monitoring subscriptions
    if (enableLoadMonitoring && switchesOn) {
        List powerDevices = switchesOn.findAll { it.hasCapability("PowerMeter") }
        powerDevices.each { dev ->
            BigDecimal threshold = getDecimalSetting("minLoadWatts_${dev.id}", 0)
            if (threshold > 0) {
                subscribe(dev, "power", powerHandler)
            }
        }
    }

    // Evaluate current load and re-schedule if needed (unschedule() in updated() kills timers)
    if (enableLoadMonitoring && switchesOn && !state.powerOutage) {
        evaluateCurrentLoad()
    }

    int totalCount = (switchesOn?.size() ?: 0) + (switchesOff?.size() ?: 0)
    logDebug "Initialized — monitoring ${totalCount} switch(es)${state.powerOutage ? ' (power outage active)' : ''}"
    evaluateSwitches("Preferences saved")
}

// ── Event Handlers ───────────────────────────────────────────────────────────

void systemStartHandler(evt) {
    log.info "Hub startup detected — refreshing monitored switches"
    List allSwitches = getAllMonitoredSwitches()
    List refreshable = allSwitches.findAll { it.hasCommand("refresh") }
    if (refreshable) {
        logDebug "Refreshing ${refreshable.size()} switch(es): ${refreshable.collect { it.displayName }.join(', ')}"
        refreshable.each { it.refresh() }
    }
    runIn(STARTUP_REFRESH_DELAY_SECONDS, "startupCheck")
}

void startupCheck() {
    evaluateSwitches("Startup check")
}

// ── Must-Stay-On Handlers ────────────────────────────────────────────────────

void mustStayOnTurnedOffHandler(evt) {
    log.info "${evt.displayName} turned off (must stay on)"
    if (state.powerOutage) {
        logDebug "Power outage active — skipping recovery scheduling"
        return
    }
    int minutes = getIntSetting("graceMinutes", DEFAULT_GRACE_MINUTES)
    int delaySecs = Math.max(minutes * 60, 1)
    logDebug "Scheduling stay-on recovery check in ${minutes} minute(s)"
    runIn(delaySecs, "startRecoveryOn", [overwrite: false])
}

void mustStayOnTurnedOnHandler(evt) {
    log.warn "${evt.displayName} turned back on"
    List actionable = getActionableSwitches(switchesOn, "on")
    if (!actionable.isEmpty()) return

    if (state.recoveryActiveOn) {
        log.info "All must-stay-on switches are back on — recovery complete"
    }
    unschedule("startRecoveryOn")
    unschedule("attemptRecoveryOn")
    unschedule("verifyRecoveryOn")
    state.recoveryActiveOn = false
    state.retryCountOn = 0
}

// ── Must-Stay-Off Handlers ───────────────────────────────────────────────────

void mustStayOffTurnedOnHandler(evt) {
    log.info "${evt.displayName} turned on (must stay off)"
    int minutes = getIntSetting("graceMinutes", DEFAULT_GRACE_MINUTES)
    int delaySecs = Math.max(minutes * 60, 1)
    logDebug "Scheduling stay-off recovery check in ${minutes} minute(s)"
    runIn(delaySecs, "startRecoveryOff", [overwrite: false])
}

void mustStayOffTurnedOffHandler(evt) {
    log.warn "${evt.displayName} turned back off"
    List actionable = getActionableSwitches(switchesOff, "off")
    if (!actionable.isEmpty()) return

    if (state.recoveryActiveOff) {
        log.info "All must-stay-off switches are back off — recovery complete"
    }
    unschedule("startRecoveryOff")
    unschedule("attemptRecoveryOff")
    unschedule("verifyRecoveryOff")
    state.recoveryActiveOff = false
    state.retryCountOff = 0
}

// ── Power Outage Handlers ────────────────────────────────────────────────────

void outageIndicatorSwitchHandler(evt) {
    if (evt.value == "on") {
        powerOutageStarted()
    } else {
        powerOutageEnded()
    }
}

void outageIndicatorPowerHandler(evt) {
    if (evt.value != "mains") {
        powerOutageStarted()
    } else {
        powerOutageEnded()
    }
}

// ── Power Outage Logic ───────────────────────────────────────────────────────

private void powerOutageStarted() {
    if (state.powerOutage) return
    state.powerOutage = true
    log.warn "Power outage detected — pausing stay-on recovery and load monitoring"

    // Cancel stay-on recovery (stay-off is unaffected — switches are already off during outage)
    if (state.recoveryActiveOn) {
        unschedule("startRecoveryOn")
        unschedule("attemptRecoveryOn")
        unschedule("verifyRecoveryOn")
        state.recoveryActiveOn = false
        state.retryCountOn = 0
    }

    // Clear load monitoring state
    state.lowLoadDevices = [:]
    unschedule("checkLowLoad")
}

private void powerOutageEnded() {
    if (!state.powerOutage) return
    state.powerOutage = false
    log.warn "Power restored — refreshing and re-evaluating monitored switches"

    List allSwitches = getAllMonitoredSwitches()
    List refreshable = allSwitches.findAll { it.hasCommand("refresh") }
    if (refreshable) {
        logDebug "Refreshing ${refreshable.size()} switch(es)"
        refreshable.each { it.refresh() }
    }
    runIn(POST_OUTAGE_REFRESH_DELAY_SECONDS, "postOutageCheck")
}

void postOutageCheck() {
    evaluateSwitches("Power restored")
}

// ── Recovery Logic (Must-Stay-On) ────────────────────────────────────────────

void startRecoveryOn() {
    if (state.powerOutage) {
        logDebug "Power outage active — deferring stay-on recovery"
        return
    }
    List actionable = getActionableSwitches(switchesOn, "on")
    if (actionable.isEmpty()) {
        logDebug "All must-stay-on switches are on — no recovery needed"
        state.recoveryActiveOn = false
        state.retryCountOn = 0
        return
    }

    String names = actionable.collect { it.displayName }.join(", ")
    int minutes = getIntSetting("graceMinutes", DEFAULT_GRACE_MINUTES)
    log.info "${names} stayed off for ${minutes} minute(s) — starting recovery"
    if (notifyOnRecovery) {
        sendNotification("${names} turned off — turning back on")
    }

    state.recoveryActiveOn = true
    state.retryCountOn = 0
    attemptRecoveryOn()
}

void attemptRecoveryOn() {
    if (state.powerOutage) {
        logDebug "Power outage active — suspending stay-on recovery"
        state.recoveryActiveOn = false
        state.retryCountOn = 0
        return
    }
    List actionable = getActionableSwitches(switchesOn, "on")
    if (actionable.isEmpty()) {
        log.info "All must-stay-on switches are on — recovery complete"
        state.recoveryActiveOn = false
        state.retryCountOn = 0
        return
    }

    int count = ((int)(state.retryCountOn ?: 0)) + 1
    state.retryCountOn = count
    int max = getIntSetting("maxRetries", DEFAULT_MAX_RETRIES)

    if (max > 0 && count > max) {
        String names = actionable.collect { it.displayName }.join(", ")
        String msg = "Giving up on turning on ${names} after ${max} attempt(s)"
        log.warn msg
        if (notifyOnFailure) sendNotification(msg)
        state.recoveryActiveOn = false
        state.retryCountOn = 0
        return
    }

    String names = actionable.collect { it.displayName }.join(", ")
    String countStr = max > 0 ? "${count}/${max}" : "${count}"
    logDebug "Turning on: ${names} (attempt ${countStr})"
    actionable.each { it.on() }

    int delay = getIntSetting("verifyDelay", DEFAULT_VERIFY_DELAY_SECONDS)
    runIn(delay, "verifyRecoveryOn")
}

void verifyRecoveryOn() {
    List actionable = getActionableSwitches(switchesOn, "on")
    if (actionable.isEmpty()) {
        log.info "All must-stay-on switches verified on — recovery complete"
        state.recoveryActiveOn = false
        state.retryCountOn = 0
        return
    }

    String names = actionable.collect { it.displayName }.join(", ")
    log.warn "${names} did not turn back on (attempt ${state.retryCountOn})"
    if (notifyOnFailure) {
        sendNotification("${names} did not turn back on!")
    }

    int interval = getIntSetting("retryInterval", DEFAULT_RETRY_INTERVAL_SECONDS)
    runIn(interval, "attemptRecoveryOn")
}

// ── Recovery Logic (Must-Stay-Off) ───────────────────────────────────────────

void startRecoveryOff() {
    List actionable = getActionableSwitches(switchesOff, "off")
    if (actionable.isEmpty()) {
        logDebug "All must-stay-off switches are off — no recovery needed"
        state.recoveryActiveOff = false
        state.retryCountOff = 0
        return
    }

    String names = actionable.collect { it.displayName }.join(", ")
    int minutes = getIntSetting("graceMinutes", DEFAULT_GRACE_MINUTES)
    log.info "${names} stayed on for ${minutes} minute(s) — starting recovery"
    if (notifyOnRecovery) {
        sendNotification("${names} turned on — turning back off")
    }

    state.recoveryActiveOff = true
    state.retryCountOff = 0
    attemptRecoveryOff()
}

void attemptRecoveryOff() {
    List actionable = getActionableSwitches(switchesOff, "off")
    if (actionable.isEmpty()) {
        log.info "All must-stay-off switches are off — recovery complete"
        state.recoveryActiveOff = false
        state.retryCountOff = 0
        return
    }

    int count = ((int)(state.retryCountOff ?: 0)) + 1
    state.retryCountOff = count
    int max = getIntSetting("maxRetries", DEFAULT_MAX_RETRIES)

    if (max > 0 && count > max) {
        String names = actionable.collect { it.displayName }.join(", ")
        String msg = "Giving up on turning off ${names} after ${max} attempt(s)"
        log.warn msg
        if (notifyOnFailure) sendNotification(msg)
        state.recoveryActiveOff = false
        state.retryCountOff = 0
        return
    }

    String names = actionable.collect { it.displayName }.join(", ")
    String countStr = max > 0 ? "${count}/${max}" : "${count}"
    logDebug "Turning off: ${names} (attempt ${countStr})"
    actionable.each { it.off() }

    int delay = getIntSetting("verifyDelay", DEFAULT_VERIFY_DELAY_SECONDS)
    runIn(delay, "verifyRecoveryOff")
}

void verifyRecoveryOff() {
    List actionable = getActionableSwitches(switchesOff, "off")
    if (actionable.isEmpty()) {
        log.info "All must-stay-off switches verified off — recovery complete"
        state.recoveryActiveOff = false
        state.retryCountOff = 0
        return
    }

    String names = actionable.collect { it.displayName }.join(", ")
    log.warn "${names} did not turn back off (attempt ${state.retryCountOff})"
    if (notifyOnFailure) {
        sendNotification("${names} did not turn back off!")
    }

    int interval = getIntSetting("retryInterval", DEFAULT_RETRY_INTERVAL_SECONDS)
    runIn(interval, "attemptRecoveryOff")
}

// ── Load Monitoring ──────────────────────────────────────────────────────────

void powerHandler(evt) {
    if (state.powerOutage) return
    if (!enableLoadMonitoring) return

    String devId = evt.device.id.toString()
    BigDecimal threshold = getDecimalSetting("minLoadWatts_${devId}", 0)
    if (threshold <= 0) return

    // Ignore if switch is currently off — power is naturally 0
    if (evt.device.currentSwitch == "off") return

    BigDecimal power = evt.numberValue
    Map lowLoadDevices = (Map)(state.lowLoadDevices ?: [:])

    if (power < threshold) {
        if (!lowLoadDevices.containsKey(devId)) {
            logDebug "${evt.displayName} load ${power}W below threshold ${threshold}W — starting grace period"
            lowLoadDevices[devId] = now()
            state.lowLoadDevices = lowLoadDevices
            int graceMinutes = getIntSetting("loadGraceMinutes", DEFAULT_LOAD_GRACE_MINUTES)
            runIn(graceMinutes * 60, "checkLowLoad", [overwrite: false])
        }
    } else {
        if (lowLoadDevices.containsKey(devId)) {
            logDebug "${evt.displayName} load recovered to ${power}W (threshold ${threshold}W)"
            lowLoadDevices.remove(devId)
            state.lowLoadDevices = lowLoadDevices
            if (lowLoadDevices.isEmpty()) {
                unschedule("checkLowLoad")
            }
        }
    }
}

/**
 * Scan current power levels for all load-monitored devices. Seed or update
 * state.lowLoadDevices and schedule checkLowLoad if any are below threshold.
 */
private void evaluateCurrentLoad() {
    Map lowLoad = (Map)(state.lowLoadDevices ?: [:])
    long nowMs = now()
    switchesOn.findAll { it.hasCapability("PowerMeter") }.each { dev ->
        String devId = dev.id.toString()
        BigDecimal threshold = getDecimalSetting("minLoadWatts_${devId}", 0)
        if (threshold <= 0) return
        if (dev.currentSwitch == "off") return
        BigDecimal currentPower = dev.currentValue("power") as BigDecimal
        if (currentPower != null && currentPower < threshold) {
            if (!lowLoad.containsKey(devId)) {
                logDebug "${dev.displayName} load ${currentPower}W below threshold ${threshold}W"
                lowLoad[devId] = nowMs
            }
        } else {
            lowLoad.remove(devId)
        }
    }
    state.lowLoadDevices = lowLoad

    if (!lowLoad.isEmpty()) {
        int delaySecs = getIntSetting("loadGraceMinutes", DEFAULT_LOAD_GRACE_MINUTES) * 60
        logDebug "Scheduling low-load check — ${lowLoad.size()} device(s) tracked"
        runIn(delaySecs, "checkLowLoad")
    }
}

void checkLowLoad() {
    if (state.powerOutage) {
        state.lowLoadDevices = [:]
        return
    }
    if (!enableLoadMonitoring) return

    Map lowLoadDevices = (Map)(state.lowLoadDevices ?: [:])
    if (lowLoadDevices.isEmpty()) return

    List lowNames = []
    List recovered = []

    lowLoadDevices.each { String devId, timestamp ->
        def dev = switchesOn?.find { it.id.toString() == devId }
        if (!dev) {
            recovered << devId
            return
        }

        // If switch is off, don't alert about load
        if (dev.currentSwitch == "off") {
            recovered << devId
            return
        }

        BigDecimal threshold = getDecimalSetting("minLoadWatts_${devId}", 0)
        if (threshold <= 0) {
            recovered << devId
            return
        }

        BigDecimal currentPower = dev.currentValue("power") as BigDecimal
        if (currentPower != null && currentPower < threshold) {
            lowNames << "${dev.displayName} (${currentPower}W < ${threshold}W)"
        } else {
            logDebug "${dev.displayName} load recovered to ${currentPower}W"
            recovered << devId
        }
    }

    recovered.each { lowLoadDevices.remove(it) }
    state.lowLoadDevices = lowLoadDevices

    if (lowNames) {
        String msg = "Low load detected: ${lowNames.join(', ')}"
        log.warn msg
        if (notifyOnLowLoad) sendNotification(msg)

        int reminderMinutes = getIntSetting("loadReminderMinutes", DEFAULT_LOAD_REMINDER_MINUTES)
        runIn(reminderMinutes * 60, "checkLowLoad")
    } else if (lowLoadDevices.isEmpty()) {
        logDebug "All loads recovered — load monitoring clear"
        unschedule("checkLowLoad")
    }
}

// ── Helpers ──────────────────────────────────────────────────────────────────

/**
 * Returns switches that are NOT in the desired target state.
 * For must-stay-on: targetState="on" returns switches that are currently off.
 * For must-stay-off: targetState="off" returns switches that are currently on.
 */
private List getActionableSwitches(List devices, String targetState) {
    if (!devices) return []
    String wrongState = (targetState == "on") ? "off" : "on"
    return devices.findAll { it.currentSwitch == wrongState }
}

/**
 * Evaluate all switches using event history to respect the grace period.
 * Handles both must-stay-on and must-stay-off sets.
 */
private void evaluateSwitches(String context) {
    boolean anyActionable = false

    if (switchesOn) {
        anyActionable |= evaluateSwitchSet(switchesOn, "on", "startRecoveryOn",
            "recoveryActiveOn", "retryCountOn", context)
    }
    if (switchesOff) {
        anyActionable |= evaluateSwitchSet(switchesOff, "off", "startRecoveryOff",
            "recoveryActiveOff", "retryCountOff", context)
    }

    if (!anyActionable) {
        log.info "${context}: all monitored switches are in their target state"
    }
}

/**
 * Evaluate a set of switches against their target state. Returns true if any
 * switches were found in the wrong state.
 */
private boolean evaluateSwitchSet(List devices, String targetState, String recoveryMethod,
                                  String recoveryActiveKey, String retryCountKey, String context) {
    List actionable = getActionableSwitches(devices, targetState)
    if (actionable.isEmpty()) return false

    String wrongState = (targetState == "on") ? "off" : "on"
    int graceMinutes = getIntSetting("graceMinutes", DEFAULT_GRACE_MINUTES)
    long gracePeriodMs = graceMinutes * 60 * 1000L
    long nowMs = now()

    List immediate = []
    int soonestDelaySecs = Integer.MAX_VALUE

    actionable.each { dev ->
        Long sinceMs = dev.currentState("switch")?.date?.time
        if (sinceMs) {
            long durationMs = nowMs - sinceMs
            long remainingMs = gracePeriodMs - durationMs
            if (remainingMs <= 0) {
                immediate << dev
            } else {
                int remainingSecs = (int) Math.ceil(remainingMs / 1000.0)
                logDebug "${context}: ${dev.displayName} turned ${wrongState} ${(int)(durationMs / 1000)}s ago — scheduling check in ${remainingSecs}s"
                if (remainingSecs < soonestDelaySecs) {
                    soonestDelaySecs = remainingSecs
                }
            }
        } else {
            immediate << dev
        }
    }

    if (immediate) {
        String names = immediate.collect { it.displayName }.join(", ")
        String action = (targetState == "on") ? "turning back on" : "turning back off"
        log.warn "${context}: ${names} ${wrongState} past grace period — starting recovery"
        if (notifyOnRecovery) {
            sendNotification("${names} found ${wrongState} — ${action}")
        }
        state[recoveryActiveKey] = true
        state[retryCountKey] = 0
        if (targetState == "on") {
            attemptRecoveryOn()
        } else {
            attemptRecoveryOff()
        }
    }

    if (soonestDelaySecs < Integer.MAX_VALUE) {
        logDebug "${context}: scheduling delayed check in ${soonestDelaySecs}s for recently changed switches"
        runIn(soonestDelaySecs, recoveryMethod, [overwrite: false])
    }

    return true
}

private List getAllMonitoredSwitches() {
    List all = []
    if (switchesOn) all.addAll(switchesOn)
    if (switchesOff) all.addAll(switchesOff)
    return all
}

private boolean isOutageActive() {
    if (outageIndicatorSwitch?.currentSwitch == "on") return true
    if (outageIndicatorPower && outageIndicatorPower.currentValue("powerSource") != "mains") return true
    return false
}

private int getIntSetting(String name, int defaultValue) {
    Object val = settings[name]
    return val != null ? val as int : defaultValue
}

private BigDecimal getDecimalSetting(String name, BigDecimal defaultValue) {
    Object val = settings[name]
    return val != null ? val as BigDecimal : defaultValue
}

private void sendNotification(String msg) {
    if (notifyDevices) {
        notifyDevices.each { it.deviceNotification(msg) }
    }
}

private String getStatusText() {
    int onCount = switchesOn?.size() ?: 0
    int offCount = switchesOff?.size() ?: 0
    if (onCount == 0 && offCount == 0) return "No switches configured"

    Map lowLoadDevices = (Map)(state.lowLoadDevices ?: [:])
    StringBuilder sb = new StringBuilder()

    // Summary line
    if (state.powerOutage) {
        sb.append("<b>Power outage active</b> — stay-on recovery paused<br/><br/>")
    }
    if (state.recoveryActiveOn) {
        sb.append("Stay-on recovery in progress (attempt ${state.retryCountOn})<br/>")
    }
    if (state.recoveryActiveOff) {
        sb.append("Stay-off recovery in progress (attempt ${state.retryCountOff})<br/>")
    }

    // Device status table
    long nowMs = now()
    sb.append('<table style="width:100%;border-collapse:collapse;font-size:14px">')
    sb.append('<tr style="border-bottom:2px solid #ccc;text-align:left">')
    sb.append('<th style="padding:4px 8px">Device</th>')
    sb.append('<th style="padding:4px 8px">Target</th>')
    sb.append('<th style="padding:4px 8px">State</th>')
    sb.append('<th style="padding:4px 8px">Min Load</th>')
    sb.append('<th style="padding:4px 8px">Load</th>')
    sb.append('<th style="padding:4px 8px">Since</th>')
    sb.append('</tr>')

    if (switchesOn) {
        switchesOn.each { dev ->
            String devId = dev.id.toString()
            boolean isWrong = (dev.currentSwitch == "off")
            boolean hasPower = dev.hasCapability("PowerMeter")
            BigDecimal threshold = enableLoadMonitoring ? getDecimalSetting("minLoadWatts_${devId}", 0) : 0
            BigDecimal power = hasPower ? dev.currentValue("power") as BigDecimal : null
            boolean isLowLoad = lowLoadDevices.containsKey(devId)

            String stateCell = isWrong ? '<span style="color:red"><b>OFF</b></span>' : '<span style="color:green">on</span>'

            String loadCell = ""
            if (hasPower && power != null) {
                loadCell = isLowLoad ? "<span style=\"color:red\"><b>${power}W</b></span>" : "${power}W"
            } else if (hasPower) {
                loadCell = "—"
            }
            String minLoadCell = (threshold > 0) ? "${threshold}W" : ""

            // Duration in wrong state (switch or load)
            String sinceCell = ""
            if (isWrong) {
                Long sinceMs = dev.currentState("switch")?.date?.time
                if (sinceMs) sinceCell = "<span style=\"color:red\">${formatDuration(nowMs - sinceMs)}</span>"
            } else if (isLowLoad) {
                Long sinceMs = lowLoadDevices[devId] as Long
                if (sinceMs) sinceCell = "<span style=\"color:red\">${formatDuration(nowMs - sinceMs)}</span>"
            }

            String rowStyle = (isWrong || isLowLoad) ? ' style="background:#fff0f0"' : ''
            sb.append("<tr${rowStyle}>")
            sb.append("<td style=\"padding:4px 8px\"><a href=\"/device/edit/${dev.id}\" target=\"_blank\">${dev.displayName}</a></td>")
            sb.append('<td style="padding:4px 8px">ON</td>')
            sb.append("<td style=\"padding:4px 8px\">${stateCell}</td>")
            sb.append("<td style=\"padding:4px 8px\">${minLoadCell}</td>")
            sb.append("<td style=\"padding:4px 8px\">${loadCell}</td>")
            sb.append("<td style=\"padding:4px 8px\">${sinceCell}</td>")
            sb.append('</tr>')
        }
    }

    if (switchesOff) {
        switchesOff.each { dev ->
            boolean isWrong = (dev.currentSwitch == "on")
            String stateCell = isWrong ? '<span style="color:red"><b>ON</b></span>' : '<span style="color:green">off</span>'

            String sinceCell = ""
            if (isWrong) {
                Long sinceMs = dev.currentState("switch")?.date?.time
                if (sinceMs) sinceCell = "<span style=\"color:red\">${formatDuration(nowMs - sinceMs)}</span>"
            }

            String rowStyle = isWrong ? ' style="background:#fff0f0"' : ''
            sb.append("<tr${rowStyle}>")
            sb.append("<td style=\"padding:4px 8px\"><a href=\"/device/edit/${dev.id}\" target=\"_blank\">${dev.displayName}</a></td>")
            sb.append('<td style="padding:4px 8px">OFF</td>')
            sb.append("<td style=\"padding:4px 8px\">${stateCell}</td>")
            sb.append('<td style="padding:4px 8px"></td>')
            sb.append('<td style="padding:4px 8px"></td>')
            sb.append("<td style=\"padding:4px 8px\">${sinceCell}</td>")
            sb.append('</tr>')
        }
    }

    sb.append('</table>')
    sb.append("<br/><i style=\"font-size:12px\">Last updated: ${new Date().format('yyyy-MM-dd HH:mm:ss')}</i>")
    return sb.toString()
}

@CompileStatic
private static String formatDuration(long ms) {
    long totalSecs = (long)(ms / 1000)
    if (totalSecs < 60) return "${totalSecs}s"
    long totalMins = (long)(totalSecs / 60)
    if (totalMins < 60) return "${totalMins}m ${totalSecs % 60}s"
    long hours = (long)(totalMins / 60)
    if (hours < 24) return "${hours}h ${totalMins % 60}m"
    long days = (long)(hours / 24)
    return "${days}d ${hours % 24}h"
}

private void logDebug(String msg) {
    if (enableDebug) log.debug msg
}

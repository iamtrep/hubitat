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

 Always-On Switch Monitor

 Monitors switches that must remain on at all times. If any monitored switch
 turns off and stays off for a configurable grace period, the app automatically
 turns it back on. It retries in a loop until the switch responds or the
 maximum number of attempts is reached, and optionally notifies the user.

 == Flow ==

 1. A monitored switch turns off
 2. Wait the grace period (default 5 minutes)
 3. If still off: notify "turned off — turning back on", send on() command
 4. Wait verification delay (default 10 seconds), then check:
    - If on: recovery complete
    - If still off: notify "did not turn back on!", wait retry interval,
      then go back to step 3
 5. Give up after max retries (or loop forever if set to 0)

 */

import groovy.transform.Field

@Field static final String APP_NAME = "Always-On Switch Monitor"
@Field static final String APP_VERSION = "1.0.0"

@Field static final Integer DEFAULT_STAYS_OFF_MINUTES = 5
@Field static final Integer DEFAULT_RETRY_INTERVAL_SECONDS = 30
@Field static final Integer DEFAULT_VERIFY_DELAY_SECONDS = 10
@Field static final Integer DEFAULT_MAX_RETRIES = 10
@Field static final Integer STARTUP_REFRESH_DELAY_SECONDS = 5

definition(
    name: APP_NAME,
    namespace: "iamtrep",
    author: "pj",
    description: "Monitors switches that must remain on. Automatically turns them back on with configurable retry logic and optional notifications.",
    category: "Convenience",
    singleInstance: false,
    importUrl: "https://raw.githubusercontent.com/iamtrep/hubitat/refs/heads/main/apps/AlwaysOnSwitchMonitor.groovy",
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

        section("Switches") {
            input "switches", "capability.switch", title: "Switches that must stay on", multiple: true, required: true
        }

        section("Notifications") {
            input "notifyDevices", "capability.notification", title: "Notification devices", multiple: true, required: false
            input "notifyOnTurnOn", "bool", title: "Notify when turning a switch back on", defaultValue: true
            input "notifyOnFailure", "bool", title: "Notify when a switch fails to turn back on", defaultValue: true
        }

        section("Timing", hideable: true, hidden: true) {
            input "staysOffMinutes", "number", title: "Minutes to wait before taking action", defaultValue: DEFAULT_STAYS_OFF_MINUTES, required: true, range: "0..60"
            input "retryInterval", "number", title: "Seconds between retries", defaultValue: DEFAULT_RETRY_INTERVAL_SECONDS, required: true, range: "5..300"
            input "verifyDelay", "number", title: "Seconds to wait before verifying switch state", defaultValue: DEFAULT_VERIFY_DELAY_SECONDS, required: true, range: "1..60"
            input "maxRetries", "number", title: "Maximum retry attempts (0 = unlimited)", defaultValue: DEFAULT_MAX_RETRIES, required: true, range: "0..100"
        }

        section("Logging", hideable: true, hidden: true) {
            input "enableDebug", "bool", title: "Enable debug logging", defaultValue: false
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
    state.recoveryActive = false
    state.retryCount = 0
    subscribe(switches, "switch.off", switchOffHandler)
    subscribe(switches, "switch.on", switchOnHandler)
    subscribe(location, "systemStart", systemStartHandler)
    logDebug "Initialized — monitoring ${switches.size()} switch(es)"

    // Check for any switches currently off
    List offSwitches = switches.findAll { it.currentSwitch == "off" }
    if (offSwitches) {
        String names = offSwitches.collect { it.displayName }.join(", ")
        log.warn "Found ${names} currently off — starting recovery"
        if (notifyOnTurnOn) {
            sendNotification("${names} found off — turning back on")
        }
        state.recoveryActive = true
        attemptRecovery()
    }
}

// ── Event Handlers ───────────────────────────────────────────────────────────

void systemStartHandler(evt) {
    log.info "Hub startup detected — refreshing monitored switches"
    List refreshable = switches.findAll { it.hasCommand("refresh") }
    if (refreshable) {
        logDebug "Refreshing ${refreshable.size()} switch(es): ${refreshable.collect { it.displayName }.join(', ')}"
        refreshable.each { it.refresh() }
    }
    runIn(STARTUP_REFRESH_DELAY_SECONDS, "startupCheck")
}

void startupCheck() {
    List offSwitches = switches.findAll { it.currentSwitch == "off" }
    if (offSwitches.isEmpty()) {
        log.info "Startup check: all monitored switches are on"
        return
    }
    String names = offSwitches.collect { it.displayName }.join(", ")
    log.warn "Startup check: ${names} found off after hub restart — starting recovery"
    if (notifyOnTurnOn) {
        sendNotification("${names} found off after hub restart — turning back on")
    }
    state.recoveryActive = true
    state.retryCount = 0
    attemptRecovery()
}

void switchOffHandler(evt) {
    log.info "${evt.displayName} turned off"
    if (state.recoveryActive) {
        logDebug "Recovery already active — will be handled in current loop"
        return
    }
    int minutes = getIntSetting("staysOffMinutes", DEFAULT_STAYS_OFF_MINUTES)
    int delaySecs = Math.max(minutes * 60, 1)
    logDebug "Scheduling recovery check in ${minutes} minute(s)"
    runIn(delaySecs, "startRecovery", [overwrite: false])
}

void switchOnHandler(evt) {
    log.warn "${evt.displayName} turned back on"
    List offSwitches = switches.findAll { it.currentSwitch == "off" }
    if (!offSwitches.isEmpty()) return

    // All switches are back on — cancel everything
    if (state.recoveryActive) {
        log.info "All monitored switches are back on — recovery complete"
    }
    unschedule("startRecovery")
    unschedule("attemptRecovery")
    unschedule("verifyRecovery")
    state.recoveryActive = false
    state.retryCount = 0
}

// ── Recovery Logic ───────────────────────────────────────────────────────────

void startRecovery() {
    List offSwitches = switches.findAll { it.currentSwitch == "off" }
    if (offSwitches.isEmpty()) {
        logDebug "All switches are on — no recovery needed"
        return
    }
    if (state.recoveryActive) {
        logDebug "Recovery already in progress"
        return
    }

    String names = offSwitches.collect { it.displayName }.join(", ")
    int minutes = getIntSetting("staysOffMinutes", DEFAULT_STAYS_OFF_MINUTES)
    log.info "${names} stayed off for ${minutes} minute(s) — starting recovery"
    if (notifyOnTurnOn) {
        sendNotification("${names} turned off — turning back on")
    }

    state.recoveryActive = true
    state.retryCount = 0
    attemptRecovery()
}

void attemptRecovery() {
    List offSwitches = switches.findAll { it.currentSwitch == "off" }
    if (offSwitches.isEmpty()) {
        log.info "All switches are on — recovery complete"
        state.recoveryActive = false
        state.retryCount = 0
        return
    }

    int count = ((int)(state.retryCount ?: 0)) + 1
    state.retryCount = count
    int max = getIntSetting("maxRetries", DEFAULT_MAX_RETRIES)

    if (max > 0 && count > max) {
        String names = offSwitches.collect { it.displayName }.join(", ")
        String msg = "Giving up on ${names} after ${max} attempt(s)"
        log.warn msg
        if (notifyOnFailure) sendNotification(msg)
        state.recoveryActive = false
        state.retryCount = 0
        return
    }

    String names = offSwitches.collect { it.displayName }.join(", ")
    String countStr = max > 0 ? "${count}/${max}" : "${count}"
    logDebug "Turning on: ${names} (attempt ${countStr})"
    offSwitches.each { it.on() }

    int delay = getIntSetting("verifyDelay", DEFAULT_VERIFY_DELAY_SECONDS)
    runIn(delay, "verifyRecovery")
}

void verifyRecovery() {
    List offSwitches = switches.findAll { it.currentSwitch == "off" }
    if (offSwitches.isEmpty()) {
        log.info "All switches verified on — recovery complete"
        state.recoveryActive = false
        state.retryCount = 0
        return
    }

    String names = offSwitches.collect { it.displayName }.join(", ")
    log.warn "${names} did not turn back on (attempt ${state.retryCount})"
    if (notifyOnFailure) {
        sendNotification("${names} did not turn back on!")
    }

    int interval = getIntSetting("retryInterval", DEFAULT_RETRY_INTERVAL_SECONDS)
    runIn(interval, "attemptRecovery")
}

// ── Helpers ──────────────────────────────────────────────────────────────────

private int getIntSetting(String name, int defaultValue) {
    Object val = settings[name]
    return val != null ? val as int : defaultValue
}

private void sendNotification(String msg) {
    if (notifyDevices) {
        notifyDevices.each { it.deviceNotification(msg) }
    }
}

private String getStatusText() {
    if (!switches) return "No switches configured"

    List offSwitches = switches.findAll { it.currentSwitch == "off" }
    StringBuilder sb = new StringBuilder()
    sb.append("Monitoring ${switches.size()} switch(es)")
    if (offSwitches.isEmpty()) {
        sb.append(" — all on")
    } else {
        sb.append(" — <b>${offSwitches.size()} OFF</b>: ${offSwitches.collect { it.displayName }.join(', ')}")
    }
    if (state.recoveryActive) {
        sb.append("<br/>Recovery in progress (attempt ${state.retryCount})")
    }
    return sb.toString()
}

private void logDebug(String msg) {
    if (enableDebug) log.debug msg
}

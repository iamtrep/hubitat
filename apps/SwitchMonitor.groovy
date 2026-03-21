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

 Monitors switches that must remain on (or off) at all times. Switches are
 organized into groups, each with its own target state, devices, timing,
 notification, and load monitoring configuration.

 If any monitored switch deviates from its group's target state and stays
 that way for a configurable grace period, the app automatically corrects it.
 It retries in a loop until the switch responds or the maximum number of
 attempts is reached, and optionally notifies the user.

 For must-stay-on groups with power metering, the app can also monitor the
 connected load and notify the user if power drops below a per-device minimum
 watt threshold (e.g., a freezer compressor failing).

 == Flow (per group) ==

 Event-driven (normal operation):
 1. A monitored switch deviates from its target state
 2. Wait the grace period (configurable per group)
 3. If still wrong: notify and send corrective command
 4. Wait verification delay, then check:
    - If correct: recovery complete
    - If still wrong: notify, wait retry interval, go back to step 3
 5. Give up after max retries (or loop forever if set to 0)

 == Load monitoring (must-stay-on groups only) ==

 1. A power event arrives below the per-device minimum watt threshold
 2. Wait the load grace period
 3. If still below threshold and switch is on: notify user
 4. Re-notify at the configured reminder interval until power recovers

 == State-based (startup / preferences saved) ==

 1. Refresh all switches that support it (startup only)
 2. For each group, find switches in the wrong state
 3. If wrong longer than the grace period: recover immediately
 4. If wrong for less than the grace period: schedule a delayed check

 == Power outage awareness (optional, global) ==

 - A switch (ON = outage) or PowerSource device (not mains = outage) signals an outage
 - Active recovery is cancelled during the outage for must-stay-on groups
 - Load monitoring alerts are cleared during outage
 - When power is restored: refresh all monitored switches, then re-evaluate

 */

import groovy.transform.CompileStatic
import groovy.transform.Field

@Field static final String APP_NAME = "Switch Monitor"
@Field static final String APP_VERSION = "3.0.0"

@Field static final Integer DEFAULT_GRACE_MINUTES = 5
@Field static final Integer DEFAULT_GRACE_SECONDS = 0
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
    description: "Monitors switches that must remain on or off, organized in groups with independent timing, notifications, and load monitoring.",
    category: "Convenience",
    singleInstance: false,
    importUrl: "https://raw.githubusercontent.com/iamtrep/hubitat/refs/heads/main/apps/SwitchMonitor.groovy",
    iconUrl: "",
    iconX2Url: "",
    iconX3Url: ""
)

preferences {
    page(name: "mainPage")
    page(name: "groupPage")
    page(name: "removeGroupPage")
    page(name: "checkNowPage")
}

// ── Pages ────────────────────────────────────────────────────────────────────

Map mainPage() {
    // Process pending group deletion
    if (state.removeSettingsForGroupNumber) {
        Integer groupNum = state.removeSettingsForGroupNumber as Integer
        state.remove("removeSettingsForGroupNumber")
        removeSettingsForGroup(groupNum)
        List<Integer> groups = (List<Integer>)(state.groups ?: [])
        groups.remove((Integer) groupNum)
        state.groups = groups
        Map allGroupState = (Map)(state.groupState ?: [:])
        allGroupState.remove(groupNum.toString())
        state.groupState = allGroupState
    }

    dynamicPage(name: "mainPage", title: "", install: true, uninstall: true) {
        section("Current Status") {
            paragraph getStatusText()
            href "checkNowPage", title: "Check all devices now", description: "Run a full evaluation of all switches and load levels"
        }

        section("App Label") {
            label title: "Name this instance", required: false
        }

        section("Device Groups") {
            List<Integer> groups = (List<Integer>)(state.groups ?: [])
            if (groups.isEmpty()) {
                paragraph "No groups configured. Add a group to start monitoring switches."
            } else {
                groups.eachWithIndex { Integer groupNum, int idx ->
                    String groupLabel = getGroupLabel(groupNum)
                    String targetState = getGroupTargetState(groupNum).toUpperCase()
                    List devices = getGroupDevices(groupNum)
                    int devCount = devices?.size() ?: 0
                    String summary = "Must stay ${targetState}, ${devCount} device(s)"
                    href "groupPage", title: groupLabel, description: summary,
                        params: [groupNum: groupNum]
                }
            }
            input "btnNewGroup", "button", title: "Add new group"
        }

        section("Power Outage", hideable: true, hidden: true) {
            paragraph "Optionally pause recovery during a power outage. Choose a switch (ON = outage) or a device with the PowerSource capability (outage when not on mains). When power is restored, all monitored switches are refreshed and re-evaluated."
            input "outageIndicatorSwitch", "capability.switch", title: "Outage indicator switch (ON = outage)", required: false
            input "outageIndicatorPower", "capability.powerSource", title: "Power source device (outage when not on mains)", required: false
        }

        section("Logging", hideable: true, hidden: true) {
            input "enableDebug", "bool", title: "Enable debug logging", defaultValue: false
        }
    }
}

Map groupPage(params) {
    Integer groupNum
    if (params?.groupNum) {
        state.currGroupNum = params.groupNum as Integer
        groupNum = params.groupNum as Integer
    } else {
        groupNum = state.currGroupNum as Integer
    }
    String g = "group${groupNum}"
    String groupLabel = getGroupLabel(groupNum)

    dynamicPage(name: "groupPage", title: groupLabel, nextPage: "mainPage") {
        section("Target State") {
            input "${g}.targetState", "enum", title: "Switches must stay", options: ["on": "ON", "off": "OFF"],
                defaultValue: "on", required: true, submitOnChange: true
        }

        section("Devices") {
            input "${g}.devices", "capability.switch", title: "Switches to monitor", multiple: true, required: false, submitOnChange: true
        }

        section("Group Label") {
            input "${g}.label", "text", title: "Name this group", required: false
        }

        section("Notifications") {
            input "${g}.notifyDevices", "capability.notification", title: "Notification devices", multiple: true, required: false
            input "${g}.notifyOnRecovery", "bool", title: "Notify when correcting a switch", defaultValue: true
            input "${g}.notifyOnFailure", "bool", title: "Notify when a switch fails to recover", defaultValue: true
            input "${g}.notifyOnLowLoad", "bool", title: "Notify when load drops below minimum", defaultValue: true
        }

        String targetState = (String)(settings["${g}.targetState"] ?: "on")
        List devices = settings["${g}.devices"]

        if (targetState == "on" && devices) {
            section("Load Monitoring", hideable: true, hidden: true) {
                paragraph "Monitor connected load on switches that report power. Set a minimum watt threshold per device."
                input "${g}.enableLoadMonitoring", "bool", title: "Enable load monitoring", defaultValue: false, submitOnChange: true
                if (settings["${g}.enableLoadMonitoring"]) {
                    List powerDevices = devices.findAll { it.hasCapability("PowerMeter") }
                    if (powerDevices) {
                        powerDevices.each { dev ->
                            input "${g}.minLoadWatts_${dev.id}", "decimal",
                                title: "${dev.displayName} — minimum load (watts)",
                                defaultValue: 0, required: false, range: "0..10000"
                        }
                        input "${g}.loadGraceMinutes", "number", title: "Minutes to wait before alerting on low load",
                            defaultValue: DEFAULT_LOAD_GRACE_MINUTES, required: true, range: "1..60"
                        input "${g}.loadReminderMinutes", "number", title: "Minutes between repeat notifications",
                            defaultValue: DEFAULT_LOAD_REMINDER_MINUTES, required: true, range: "5..1440"
                    } else {
                        paragraph "None of the selected devices support power reporting."
                    }
                }
            }
        }

        section("Timing", hideable: true, hidden: true) {
            input "${g}.graceMinutes", "number", title: "Minutes to wait before taking action",
                defaultValue: DEFAULT_GRACE_MINUTES, required: true, range: "0..60"
            input "${g}.graceSeconds", "number", title: "Additional seconds to wait",
                defaultValue: DEFAULT_GRACE_SECONDS, required: true, range: "0..59"
            input "${g}.retryInterval", "number", title: "Seconds between retries",
                defaultValue: DEFAULT_RETRY_INTERVAL_SECONDS, required: true, range: "5..300"
            input "${g}.verifyDelay", "number", title: "Seconds to wait before verifying switch state",
                defaultValue: DEFAULT_VERIFY_DELAY_SECONDS, required: true, range: "1..60"
            input "${g}.maxRetries", "number", title: "Maximum retry attempts (0 = unlimited)",
                defaultValue: DEFAULT_MAX_RETRIES, required: true, range: "0..100"
        }

        section("") {
            href "removeGroupPage", title: "Delete this group", description: "Remove this group and all its settings",
                params: [groupNum: groupNum]
        }
    }
}

Map removeGroupPage(params) {
    Integer groupNum
    if (params?.groupNum) {
        state.currGroupNum = params.groupNum as Integer
        groupNum = params.groupNum as Integer
    } else {
        groupNum = state.currGroupNum as Integer
    }
    String groupLabel = getGroupLabel(groupNum)

    dynamicPage(name: "removeGroupPage", title: "Delete ${groupLabel}?", nextPage: "mainPage") {
        section {
            paragraph "Are you sure you want to delete <b>${groupLabel}</b> and all its settings?"
            input "btnConfirmDelete_${groupNum}", "button", title: "Yes, delete this group"
            href "groupPage", title: "Cancel — go back", params: [groupNum: groupNum]
        }
    }
}

void appButtonHandler(String btn) {
    if (btn == "btnNewGroup") {
        List<Integer> groups = (List<Integer>)(state.groups ?: [])
        Integer newNum = groups ? (groups.max() + 1) : 1
        groups << newNum
        state.groups = groups
    } else if (btn.startsWith("btnConfirmDelete_")) {
        int groupNum = (btn - "btnConfirmDelete_") as int
        state.removeSettingsForGroupNumber = groupNum
    }
}

Map checkNowPage() {
    List allSwitches = getAllMonitoredSwitches()
    List refreshable = allSwitches.findAll { it.hasCommand("refresh") }
    if (refreshable) {
        refreshable.each { it.refresh() }
    }

    evaluateGroups("Manual check")

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
    migrateFromV2IfNeeded()

    List<Integer> groups = (List<Integer>)(state.groups ?: [])
    state.powerOutage = isOutageActive()

    // Initialize per-group state and subscriptions
    Map allGroupState = (Map)(state.groupState ?: [:])
    groups.each { Integer groupNum ->
        String key = groupNum.toString()
        if (!allGroupState.containsKey(key)) {
            allGroupState[key] = [recoveryActive: false, retryCount: 0, lowLoadDevices: [:]]
        } else {
            Map gs = (Map) allGroupState[key]
            gs.recoveryActive = false
            gs.retryCount = 0
            // Prune low-load devices no longer monitored
            String targetState = getGroupTargetState(groupNum)
            if (targetState == "on" && getBoolGroupSetting(groupNum, "enableLoadMonitoring")) {
                List devices = getGroupDevices(groupNum)
                Set activeIds = (devices ?: []).findAll { it.hasCapability("PowerMeter") }
                    .findAll { getDecimalGroupSetting(groupNum, "minLoadWatts_${it.id}", 0) > 0 }
                    .collect { it.id.toString() } as Set
                Map lowLoad = (Map)(gs.lowLoadDevices ?: [:])
                lowLoad.keySet().retainAll(activeIds)
                gs.lowLoadDevices = lowLoad
            } else {
                gs.lowLoadDevices = [:]
            }
            allGroupState[key] = gs
        }

        List devices = getGroupDevices(groupNum)
        if (!devices) return
        String targetState = getGroupTargetState(groupNum)

        if (targetState == "on") {
            subscribe(devices, "switch.off", switchDeviatedHandler)
            subscribe(devices, "switch.on", switchRestoredHandler)
        } else {
            subscribe(devices, "switch.on", switchDeviatedHandler)
            subscribe(devices, "switch.off", switchRestoredHandler)
        }

        // Load monitoring subscriptions
        if (targetState == "on" && getBoolGroupSetting(groupNum, "enableLoadMonitoring")) {
            devices.findAll { it.hasCapability("PowerMeter") }.each { dev ->
                BigDecimal threshold = getDecimalGroupSetting(groupNum, "minLoadWatts_${dev.id}", 0)
                if (threshold > 0) {
                    subscribe(dev, "power", powerHandler)
                }
            }
        }
    }
    state.groupState = allGroupState

    subscribe(location, "systemStart", systemStartHandler)

    if (outageIndicatorSwitch) {
        subscribe(outageIndicatorSwitch, "switch", outageIndicatorSwitchHandler)
    }
    if (outageIndicatorPower) {
        subscribe(outageIndicatorPower, "powerSource", outageIndicatorPowerHandler)
    }

    // Evaluate current load for all groups
    if (!state.powerOutage) {
        groups.each { Integer groupNum ->
            if (getGroupTargetState(groupNum) == "on" && getBoolGroupSetting(groupNum, "enableLoadMonitoring")) {
                evaluateGroupLoad(groupNum)
            }
        }
    }

    int totalDevices = groups.sum { Integer gn -> getGroupDevices(gn)?.size() ?: 0 } ?: 0
    logDebug "Initialized — ${groups.size()} group(s), ${totalDevices} device(s)${state.powerOutage ? ' (power outage active)' : ''}"
    evaluateGroups("Preferences saved")
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
    evaluateGroups("Startup check")
}

void switchDeviatedHandler(evt) {
    String devId = evt.device.id.toString()
    // Determine which target state this event deviates FROM
    String deviatedTarget = (evt.value == "off") ? "on" : "off"
    List<Integer> groups = findGroupsForDevice(devId, deviatedTarget)

    groups.each { Integer groupNum ->
        String groupLabel = getGroupLabel(groupNum)
        String targetState = getGroupTargetState(groupNum)
        log.info "[${groupLabel}] ${evt.displayName} turned ${evt.value} (must stay ${targetState})"

        if (state.powerOutage && targetState == "on") {
            logDebug "[${groupLabel}] Power outage active — skipping recovery scheduling"
            return
        }

        int delaySecs = getGroupGracePeriodSeconds(groupNum)
        logDebug "[${groupLabel}] Scheduling recovery check in ${getGroupGracePeriodLabel(groupNum)}"
        runIn(delaySecs, "startRecovery", [data: [groupNum: groupNum], overwrite: false])
    }
}

void switchRestoredHandler(evt) {
    String devId = evt.device.id.toString()
    // Determine which target state this event restores TO
    String restoredTarget = evt.value
    List<Integer> groups = findGroupsForDevice(devId, restoredTarget)

    groups.each { Integer groupNum ->
        String groupLabel = getGroupLabel(groupNum)
        log.warn "[${groupLabel}] ${evt.displayName} turned back ${evt.value}"

        List devices = getGroupDevices(groupNum)
        String targetState = getGroupTargetState(groupNum)
        List actionable = getActionableSwitches(devices, targetState)
        if (!actionable.isEmpty()) return

        Map gs = getGroupState(groupNum)
        if (gs.recoveryActive) {
            log.info "[${groupLabel}] All switches are back ${targetState} — recovery complete"
        }
        gs.recoveryActive = false
        gs.retryCount = 0
        saveGroupState(groupNum, gs)
    }
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

    // Mark all must-stay-on groups as not recovering and clear load state
    List<Integer> groups = (List<Integer>)(state.groups ?: [])
    Map allGroupState = (Map)(state.groupState ?: [:])
    groups.each { Integer groupNum ->
        if (getGroupTargetState(groupNum) == "on") {
            String key = groupNum.toString()
            Map gs = (Map)(allGroupState[key] ?: [:])
            gs.recoveryActive = false
            gs.retryCount = 0
            gs.lowLoadDevices = [:]
            allGroupState[key] = gs
        }
    }
    state.groupState = allGroupState
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
    evaluateGroups("Power restored")
}

// ── Recovery Logic (Unified) ─────────────────────────────────────────────────

void startRecovery(Map data) {
    int groupNum = data.groupNum as int
    String targetState = getGroupTargetState(groupNum)
    String groupLabel = getGroupLabel(groupNum)

    if (state.powerOutage && targetState == "on") {
        logDebug "[${groupLabel}] Power outage active — deferring recovery"
        return
    }

    List devices = getGroupDevices(groupNum)
    List actionable = getActionableSwitches(devices, targetState)
    if (actionable.isEmpty()) {
        logDebug "[${groupLabel}] All switches are ${targetState} — no recovery needed"
        Map gs = getGroupState(groupNum)
        gs.recoveryActive = false
        gs.retryCount = 0
        saveGroupState(groupNum, gs)
        return
    }

    String names = actionable.collect { it.displayName }.join(", ")
    log.info "[${groupLabel}] ${names} stayed ${targetState == 'on' ? 'off' : 'on'} for ${getGroupGracePeriodLabel(groupNum)} — starting recovery"
    if (getBoolGroupSetting(groupNum, "notifyOnRecovery", true)) {
        sendGroupNotification(groupNum, "${names} turned ${targetState == 'on' ? 'off' : 'on'} — turning back ${targetState}")
    }

    Map gs = getGroupState(groupNum)
    gs.recoveryActive = true
    gs.retryCount = 0
    saveGroupState(groupNum, gs)
    attemptRecovery(data)
}

void attemptRecovery(Map data) {
    int groupNum = data.groupNum as int
    String targetState = getGroupTargetState(groupNum)
    String groupLabel = getGroupLabel(groupNum)

    if (state.powerOutage && targetState == "on") {
        logDebug "[${groupLabel}] Power outage active — suspending recovery"
        Map gs = getGroupState(groupNum)
        gs.recoveryActive = false
        gs.retryCount = 0
        saveGroupState(groupNum, gs)
        return
    }

    List devices = getGroupDevices(groupNum)
    List actionable = getActionableSwitches(devices, targetState)
    if (actionable.isEmpty()) {
        log.info "[${groupLabel}] All switches are ${targetState} — recovery complete"
        Map gs = getGroupState(groupNum)
        gs.recoveryActive = false
        gs.retryCount = 0
        saveGroupState(groupNum, gs)
        return
    }

    Map gs = getGroupState(groupNum)
    int count = ((int)(gs.retryCount ?: 0)) + 1
    gs.retryCount = count
    saveGroupState(groupNum, gs)

    int max = getIntGroupSetting(groupNum, "maxRetries", DEFAULT_MAX_RETRIES)

    if (max > 0 && count > max) {
        String names = actionable.collect { it.displayName }.join(", ")
        String msg = "Giving up on turning ${targetState} ${names} after ${max} attempt(s)"
        log.warn "[${groupLabel}] ${msg}"
        if (getBoolGroupSetting(groupNum, "notifyOnFailure", true)) {
            sendGroupNotification(groupNum, msg)
        }
        gs.recoveryActive = false
        gs.retryCount = 0
        saveGroupState(groupNum, gs)
        return
    }

    String names = actionable.collect { it.displayName }.join(", ")
    String countStr = max > 0 ? "${count}/${max}" : "${count}"
    logDebug "[${groupLabel}] Turning ${targetState}: ${names} (attempt ${countStr})"
    actionable.each { targetState == "on" ? it.on() : it.off() }

    int delay = getIntGroupSetting(groupNum, "verifyDelay", DEFAULT_VERIFY_DELAY_SECONDS)
    runIn(delay, "verifyRecovery", [data: data, overwrite: false])
}

void verifyRecovery(Map data) {
    int groupNum = data.groupNum as int
    String targetState = getGroupTargetState(groupNum)
    String groupLabel = getGroupLabel(groupNum)

    List devices = getGroupDevices(groupNum)
    List actionable = getActionableSwitches(devices, targetState)
    if (actionable.isEmpty()) {
        log.info "[${groupLabel}] All switches verified ${targetState} — recovery complete"
        Map gs = getGroupState(groupNum)
        gs.recoveryActive = false
        gs.retryCount = 0
        saveGroupState(groupNum, gs)
        return
    }

    Map gs = getGroupState(groupNum)
    String names = actionable.collect { it.displayName }.join(", ")
    log.warn "[${groupLabel}] ${names} did not turn back ${targetState} (attempt ${gs.retryCount})"
    if (getBoolGroupSetting(groupNum, "notifyOnFailure", true)) {
        sendGroupNotification(groupNum, "${names} did not turn back ${targetState}!")
    }

    int interval = getIntGroupSetting(groupNum, "retryInterval", DEFAULT_RETRY_INTERVAL_SECONDS)
    runIn(interval, "attemptRecovery", [data: data, overwrite: false])
}

// ── Load Monitoring ──────────────────────────────────────────────────────────

void powerHandler(evt) {
    if (state.powerOutage) return

    String devId = evt.device.id.toString()
    List<Integer> groups = findLoadMonitoringGroupsForDevice(devId)

    groups.each { Integer groupNum ->
        String groupLabel = getGroupLabel(groupNum)
        BigDecimal threshold = getDecimalGroupSetting(groupNum, "minLoadWatts_${devId}", 0)
        if (threshold <= 0) return

        if (evt.device.currentSwitch == "off") return

        BigDecimal power = evt.numberValue
        Map gs = getGroupState(groupNum)
        Map lowLoad = (Map)(gs.lowLoadDevices ?: [:])

        if (power < threshold) {
            if (!lowLoad.containsKey(devId)) {
                logDebug "[${groupLabel}] ${evt.displayName} load ${power}W below threshold ${threshold}W — starting grace period"
                lowLoad[devId] = now()
                gs.lowLoadDevices = lowLoad
                saveGroupState(groupNum, gs)
                int graceMinutes = getIntGroupSetting(groupNum, "loadGraceMinutes", DEFAULT_LOAD_GRACE_MINUTES)
                runIn(graceMinutes * 60, "checkLowLoad", [data: [groupNum: groupNum], overwrite: false])
            }
        } else {
            if (lowLoad.containsKey(devId)) {
                logDebug "[${groupLabel}] ${evt.displayName} load recovered to ${power}W (threshold ${threshold}W)"
                lowLoad.remove(devId)
                gs.lowLoadDevices = lowLoad
                saveGroupState(groupNum, gs)
            }
        }
    }
}

private void evaluateGroupLoad(int groupNum) {
    List devices = getGroupDevices(groupNum)
    if (!devices) return

    Map gs = getGroupState(groupNum)
    Map lowLoad = (Map)(gs.lowLoadDevices ?: [:])
    long nowMs = now()

    devices.findAll { it.hasCapability("PowerMeter") }.each { dev ->
        String devId = dev.id.toString()
        BigDecimal threshold = getDecimalGroupSetting(groupNum, "minLoadWatts_${devId}", 0)
        if (threshold <= 0) return
        if (dev.currentSwitch == "off") return
        BigDecimal currentPower = dev.currentValue("power") as BigDecimal
        if (currentPower != null && currentPower < threshold) {
            if (!lowLoad.containsKey(devId)) {
                logDebug "[${getGroupLabel(groupNum)}] ${dev.displayName} load ${currentPower}W below threshold ${threshold}W"
                lowLoad[devId] = nowMs
            }
        } else {
            lowLoad.remove(devId)
        }
    }
    gs.lowLoadDevices = lowLoad
    saveGroupState(groupNum, gs)

    if (!lowLoad.isEmpty()) {
        int delaySecs = getIntGroupSetting(groupNum, "loadGraceMinutes", DEFAULT_LOAD_GRACE_MINUTES) * 60
        logDebug "[${getGroupLabel(groupNum)}] Scheduling low-load check — ${lowLoad.size()} device(s) tracked"
        runIn(delaySecs, "checkLowLoad", [data: [groupNum: groupNum], overwrite: false])
    }
}

void checkLowLoad(Map data) {
    int groupNum = data.groupNum as int
    String groupLabel = getGroupLabel(groupNum)

    if (state.powerOutage) {
        Map gs = getGroupState(groupNum)
        gs.lowLoadDevices = [:]
        saveGroupState(groupNum, gs)
        return
    }
    if (!getBoolGroupSetting(groupNum, "enableLoadMonitoring")) return

    Map gs = getGroupState(groupNum)
    Map lowLoadDevices = (Map)(gs.lowLoadDevices ?: [:])
    if (lowLoadDevices.isEmpty()) return

    List devices = getGroupDevices(groupNum)
    List lowNames = []
    List recovered = []

    lowLoadDevices.each { String devId, timestamp ->
        def dev = devices?.find { it.id.toString() == devId }
        if (!dev) {
            recovered << devId
            return
        }

        if (dev.currentSwitch == "off") {
            recovered << devId
            return
        }

        BigDecimal threshold = getDecimalGroupSetting(groupNum, "minLoadWatts_${devId}", 0)
        if (threshold <= 0) {
            recovered << devId
            return
        }

        BigDecimal currentPower = dev.currentValue("power") as BigDecimal
        if (currentPower != null && currentPower < threshold) {
            lowNames << "${dev.displayName} (${currentPower}W < ${threshold}W)"
        } else {
            logDebug "[${groupLabel}] ${dev.displayName} load recovered to ${currentPower}W"
            recovered << devId
        }
    }

    recovered.each { lowLoadDevices.remove(it) }
    gs.lowLoadDevices = lowLoadDevices
    saveGroupState(groupNum, gs)

    if (lowNames) {
        String msg = "Low load detected: ${lowNames.join(', ')}"
        log.warn "[${groupLabel}] ${msg}"
        if (getBoolGroupSetting(groupNum, "notifyOnLowLoad", true)) {
            sendGroupNotification(groupNum, msg)
        }
        int reminderMinutes = getIntGroupSetting(groupNum, "loadReminderMinutes", DEFAULT_LOAD_REMINDER_MINUTES)
        runIn(reminderMinutes * 60, "checkLowLoad", [data: [groupNum: groupNum], overwrite: false])
    } else if (lowLoadDevices.isEmpty()) {
        logDebug "[${groupLabel}] All loads recovered — load monitoring clear"
    }
}

// ── Evaluation ───────────────────────────────────────────────────────────────

private void evaluateGroups(String context) {
    List<Integer> groups = (List<Integer>)(state.groups ?: [])
    boolean anyActionable = false

    groups.each { Integer groupNum ->
        List devices = getGroupDevices(groupNum)
        if (!devices) return
        String targetState = getGroupTargetState(groupNum)
        anyActionable |= evaluateGroupSwitches(groupNum, devices, targetState, context)
    }

    // Evaluate load for all applicable groups
    if (!state.powerOutage) {
        groups.each { Integer groupNum ->
            if (getGroupTargetState(groupNum) == "on" && getBoolGroupSetting(groupNum, "enableLoadMonitoring")) {
                evaluateGroupLoad(groupNum)
            }
        }
    }

    if (!anyActionable) {
        log.info "${context}: all monitored switches are in their target state"
    }
}

private boolean evaluateGroupSwitches(int groupNum, List devices, String targetState, String context) {
    List actionable = getActionableSwitches(devices, targetState)
    if (actionable.isEmpty()) return false

    String groupLabel = getGroupLabel(groupNum)
    String wrongState = (targetState == "on") ? "off" : "on"
    long gracePeriodMs = getGroupGracePeriodSeconds(groupNum) * 1000L
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
                logDebug "[${groupLabel}] ${context}: ${dev.displayName} turned ${wrongState} ${(int)(durationMs / 1000)}s ago — scheduling check in ${remainingSecs}s"
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
        log.warn "[${groupLabel}] ${context}: ${names} ${wrongState} past grace period — starting recovery"
        if (getBoolGroupSetting(groupNum, "notifyOnRecovery", true)) {
            sendGroupNotification(groupNum, "${names} found ${wrongState} — ${action}")
        }
        Map gs = getGroupState(groupNum)
        gs.recoveryActive = true
        gs.retryCount = 0
        saveGroupState(groupNum, gs)
        attemptRecovery([groupNum: groupNum])
    }

    if (soonestDelaySecs < Integer.MAX_VALUE) {
        logDebug "[${groupLabel}] ${context}: scheduling delayed check in ${soonestDelaySecs}s for recently changed switches"
        runIn(soonestDelaySecs, "startRecovery", [data: [groupNum: groupNum], overwrite: false])
    }

    return true
}

// ── Migration ────────────────────────────────────────────────────────────────

private void migrateFromV2IfNeeded() {
    if (state.groups != null) return
    if (!settings.switchesOn && !settings.switchesOff && !settings.switches) {
        state.groups = []
        state.groupState = [:]
        return
    }

    log.info "Migrating from v2 to v3 multi-group format"
    List<Integer> groups = []
    int nextGroup = 1

    if (settings.switchesOn) {
        int g = nextGroup
        groups << g
        app.updateSetting("group${g}.targetState", [type: "enum", value: "on"])
        app.updateSetting("group${g}.devices", [type: "capability.switch", value: settings.switchesOn])
        app.updateSetting("group${g}.label", [type: "text", value: "Must Stay On"])
        migrateTimingSettings(g)
        migrateNotificationSettings(g)
        migrateLoadMonitoringSettings(g, settings.switchesOn)
        nextGroup++
    }

    if (settings.switchesOff) {
        int g = nextGroup
        groups << g
        app.updateSetting("group${g}.targetState", [type: "enum", value: "off"])
        app.updateSetting("group${g}.devices", [type: "capability.switch", value: settings.switchesOff])
        app.updateSetting("group${g}.label", [type: "text", value: "Must Stay Off"])
        migrateTimingSettings(g)
        migrateNotificationSettings(g)
        nextGroup++
    }

    state.groups = groups
    state.groupState = [:]

    // Clean up old flat settings
    ["switchesOn", "switchesOff", "switches", "graceMinutes", "graceSeconds", "retryInterval",
     "verifyDelay", "maxRetries", "notifyDevices", "notifyOnRecovery", "notifyOnFailure",
     "notifyOnLowLoad", "enableLoadMonitoring", "loadGraceMinutes", "loadReminderMinutes"].each {
        app.removeSetting(it)
    }
    settings.keySet().findAll { ((String) it).startsWith("minLoadWatts_") }.each {
        app.removeSetting(it)
    }

    // Clean old state
    state.remove("recoveryActiveOn")
    state.remove("retryCountOn")
    state.remove("recoveryActiveOff")
    state.remove("retryCountOff")
    state.remove("lowLoadDevices")

    log.info "Migration complete: created ${groups.size()} group(s)"
}

private void migrateTimingSettings(int g) {
    String prefix = "group${g}"
    if (settings.graceMinutes != null) app.updateSetting("${prefix}.graceMinutes", [type: "number", value: settings.graceMinutes])
    if (settings.graceSeconds != null) app.updateSetting("${prefix}.graceSeconds", [type: "number", value: settings.graceSeconds])
    if (settings.retryInterval != null) app.updateSetting("${prefix}.retryInterval", [type: "number", value: settings.retryInterval])
    if (settings.verifyDelay != null) app.updateSetting("${prefix}.verifyDelay", [type: "number", value: settings.verifyDelay])
    if (settings.maxRetries != null) app.updateSetting("${prefix}.maxRetries", [type: "number", value: settings.maxRetries])
}

private void migrateNotificationSettings(int g) {
    String prefix = "group${g}"
    if (settings.notifyDevices != null) app.updateSetting("${prefix}.notifyDevices", [type: "capability.notification", value: settings.notifyDevices])
    if (settings.notifyOnRecovery != null) app.updateSetting("${prefix}.notifyOnRecovery", [type: "bool", value: settings.notifyOnRecovery])
    if (settings.notifyOnFailure != null) app.updateSetting("${prefix}.notifyOnFailure", [type: "bool", value: settings.notifyOnFailure])
    if (settings.notifyOnLowLoad != null) app.updateSetting("${prefix}.notifyOnLowLoad", [type: "bool", value: settings.notifyOnLowLoad])
}

private void migrateLoadMonitoringSettings(int g, List devices) {
    String prefix = "group${g}"
    if (settings.enableLoadMonitoring != null) app.updateSetting("${prefix}.enableLoadMonitoring", [type: "bool", value: settings.enableLoadMonitoring])
    if (settings.loadGraceMinutes != null) app.updateSetting("${prefix}.loadGraceMinutes", [type: "number", value: settings.loadGraceMinutes])
    if (settings.loadReminderMinutes != null) app.updateSetting("${prefix}.loadReminderMinutes", [type: "number", value: settings.loadReminderMinutes])
    devices.each { dev ->
        String key = "minLoadWatts_${dev.id}"
        if (settings[key] != null) {
            app.updateSetting("${prefix}.${key}", [type: "decimal", value: settings[key]])
        }
    }
}

// ── Helpers ──────────────────────────────────────────────────────────────────

private List getActionableSwitches(List devices, String targetState) {
    if (!devices) return []
    String wrongState = (targetState == "on") ? "off" : "on"
    return devices.findAll { it.currentSwitch == wrongState }
}

private List<Integer> findGroupsForDevice(String deviceId, String targetState) {
    List<Integer> groups = (List<Integer>)(state.groups ?: [])
    return groups.findAll { Integer groupNum ->
        if (getGroupTargetState(groupNum) != targetState) return false
        List devices = getGroupDevices(groupNum)
        return devices?.any { it.id.toString() == deviceId }
    }
}

private List<Integer> findLoadMonitoringGroupsForDevice(String deviceId) {
    List<Integer> groups = (List<Integer>)(state.groups ?: [])
    return groups.findAll { Integer groupNum ->
        if (getGroupTargetState(groupNum) != "on") return false
        if (!getBoolGroupSetting(groupNum, "enableLoadMonitoring")) return false
        List devices = getGroupDevices(groupNum)
        return devices?.any { it.id.toString() == deviceId }
    }
}

private List getAllMonitoredSwitches() {
    List<Integer> groups = (List<Integer>)(state.groups ?: [])
    Set seen = [] as Set
    List all = []
    groups.each { Integer groupNum ->
        List devices = getGroupDevices(groupNum)
        devices?.each { dev ->
            if (seen.add(dev.id)) all << dev
        }
    }
    return all
}

private boolean isOutageActive() {
    if (outageIndicatorSwitch?.currentSwitch == "on") return true
    if (outageIndicatorPower && outageIndicatorPower.currentValue("powerSource") != "mains") return true
    return false
}

private void removeSettingsForGroup(int groupNum) {
    String prefix = "group${groupNum}."
    List<String> toRemove = settings.keySet().findAll { ((String) it).startsWith(prefix) }
    logDebug "Removing ${toRemove.size()} settings for group ${groupNum}"
    toRemove.each { app.removeSetting(it) }
}

// ── Group Setting Accessors ──────────────────────────────────────────────────

private List getGroupDevices(int groupNum) {
    return (List) settings["group${groupNum}.devices"]
}

private String getGroupTargetState(int groupNum) {
    return (String)(settings["group${groupNum}.targetState"] ?: "on")
}

private String getGroupLabel(int groupNum) {
    String label = settings["group${groupNum}.label"]
    return label ?: "Group ${groupNum}"
}

private int getIntGroupSetting(int groupNum, String name, int defaultValue) {
    Object val = settings["group${groupNum}.${name}"]
    return val != null ? val as int : defaultValue
}

private BigDecimal getDecimalGroupSetting(int groupNum, String name, BigDecimal defaultValue) {
    Object val = settings["group${groupNum}.${name}"]
    return val != null ? val as BigDecimal : defaultValue
}

private boolean getBoolGroupSetting(int groupNum, String name, boolean defaultValue = false) {
    Object val = settings["group${groupNum}.${name}"]
    return val != null ? val as boolean : defaultValue
}

private int getGroupGracePeriodSeconds(int groupNum) {
    int minutes = getIntGroupSetting(groupNum, "graceMinutes", DEFAULT_GRACE_MINUTES)
    int seconds = getIntGroupSetting(groupNum, "graceSeconds", DEFAULT_GRACE_SECONDS)
    return Math.max(minutes * 60 + seconds, 1)
}

private String getGroupGracePeriodLabel(int groupNum) {
    int minutes = getIntGroupSetting(groupNum, "graceMinutes", DEFAULT_GRACE_MINUTES)
    int seconds = getIntGroupSetting(groupNum, "graceSeconds", DEFAULT_GRACE_SECONDS)
    if (minutes > 0 && seconds > 0) return "${minutes}m ${seconds}s"
    if (minutes > 0) return "${minutes} minute(s)"
    return "${seconds} second(s)"
}

// ── Group State Accessors ────────────────────────────────────────────────────

private Map getGroupState(int groupNum) {
    Map allGroupState = (Map)(state.groupState ?: [:])
    String key = groupNum.toString()
    if (!allGroupState.containsKey(key)) {
        allGroupState[key] = [recoveryActive: false, retryCount: 0, lowLoadDevices: [:]]
        state.groupState = allGroupState
    }
    return (Map) allGroupState[key]
}

private void saveGroupState(int groupNum, Map gs) {
    Map allGroupState = (Map)(state.groupState ?: [:])
    allGroupState[groupNum.toString()] = gs
    state.groupState = allGroupState
}

// ── Notification ─────────────────────────────────────────────────────────────

private void sendGroupNotification(int groupNum, String msg) {
    List notifyDevices = (List) settings["group${groupNum}.notifyDevices"]
    if (notifyDevices) {
        String prefix = getGroupLabel(groupNum)
        notifyDevices.each { it.deviceNotification("[${prefix}] ${msg}") }
    }
}

// ── Status Display ───────────────────────────────────────────────────────────

private String getStatusText() {
    List<Integer> groups = (List<Integer>)(state.groups ?: [])
    if (groups.isEmpty()) return "No groups configured"

    int totalDevices = groups.sum { Integer gn -> getGroupDevices(gn)?.size() ?: 0 } ?: 0
    if (totalDevices == 0) return "No devices configured in any group"

    StringBuilder sb = new StringBuilder()
    long nowMs = now()

    if (state.powerOutage) {
        sb.append("<b>Power outage active</b> — stay-on recovery paused<br/><br/>")
    }

    groups.each { Integer groupNum ->
        List devices = getGroupDevices(groupNum)
        if (!devices) return

        String groupLabel = getGroupLabel(groupNum)
        String targetState = getGroupTargetState(groupNum)
        Map gs = getGroupState(groupNum)
        boolean loadMonitoring = (targetState == "on" && getBoolGroupSetting(groupNum, "enableLoadMonitoring"))

        sb.append("<b>${groupLabel}</b> — must stay ${targetState.toUpperCase()}")
        if (gs.recoveryActive) {
            sb.append(" <span style=\"color:red\">(recovery in progress, attempt ${gs.retryCount})</span>")
        }
        sb.append("<br/>")

        sb.append('<table style="width:100%;border-collapse:collapse;font-size:14px">')
        sb.append('<tr style="border-bottom:2px solid #ccc;text-align:left">')
        sb.append('<th style="padding:4px 8px">Device</th>')
        sb.append('<th style="padding:4px 8px">State</th>')
        if (loadMonitoring) {
            sb.append('<th style="padding:4px 8px">Min Load</th>')
            sb.append('<th style="padding:4px 8px">Load</th>')
        }
        sb.append('<th style="padding:4px 8px">Since</th>')
        sb.append('</tr>')

        Map lowLoadDevices = (Map)(gs.lowLoadDevices ?: [:])

        devices.each { dev ->
            String devId = dev.id.toString()
            boolean isWrong = (dev.currentSwitch != targetState)
            boolean hasPower = dev.hasCapability("PowerMeter")
            BigDecimal threshold = loadMonitoring ? getDecimalGroupSetting(groupNum, "minLoadWatts_${devId}", 0) : 0
            BigDecimal power = hasPower ? dev.currentValue("power") as BigDecimal : null
            boolean isLowLoad = lowLoadDevices.containsKey(devId)

            String stateCell = isWrong ?
                "<span style=\"color:red\"><b>${dev.currentSwitch.toUpperCase()}</b></span>" :
                "<span style=\"color:green\">${dev.currentSwitch}</span>"

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
            sb.append("<td style=\"padding:4px 8px\">${stateCell}</td>")
            if (loadMonitoring) {
                String minLoadCell = (threshold > 0) ? "${threshold}W" : ""
                String loadCell = ""
                if (hasPower && power != null) {
                    loadCell = isLowLoad ? "<span style=\"color:red\"><b>${power}W</b></span>" : "${power}W"
                } else if (hasPower) {
                    loadCell = "\u2014"
                }
                sb.append("<td style=\"padding:4px 8px\">${minLoadCell}</td>")
                sb.append("<td style=\"padding:4px 8px\">${loadCell}</td>")
            }
            sb.append("<td style=\"padding:4px 8px\">${sinceCell}</td>")
            sb.append('</tr>')
        }

        sb.append('</table><br/>')
    }

    sb.append("<i style=\"font-size:12px\">Last updated: ${new Date().format('yyyy-MM-dd HH:mm:ss')}</i>")
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

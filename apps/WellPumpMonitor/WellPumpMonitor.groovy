// Copyright (c) 2025-2026 PJ
// SPDX-License-Identifier: MIT

/*
 Well Pump Monitor

 Monitors a well pump via power metering on its switch, tracks water consumption
 using a flow meter, logs pump cycles to CSV, and provides emergency shutoff
 protection. Replaces a complex Rule Machine automation with a configurable app.
 */
import groovy.transform.CompileStatic
import groovy.transform.Field
import groovy.json.JsonOutput
import com.hubitat.app.DeviceWrapper
import com.hubitat.hub.domain.Event
import java.nio.file.AccessDeniedException

@Field static final String APP_NAME = "Well Pump Monitor"
@Field static final String APP_VERSION = "0.4.1"
@Field static final String DASHBOARD_FILE = "wellpump-dashboard.html"
@Field static final String CHARTJS_FILE = "wellpump-chart.min.js"

definition(
    name: APP_NAME,
    namespace: "iamtrep",
    author: "pj",
    description: "Monitors well pump cycles, tracks water consumption, and provides emergency shutoff protection",
    category: "Utility",
    iconUrl: "",
    iconX2Url: "",
    importUrl: "https://raw.githubusercontent.com/iamtrep/hubitat/refs/heads/main/apps/WellPumpMonitor.groovy",
    oauth: true,
    singleThreaded: true
)

mappings {
    path('/dashboard')  { action: [GET: 'getDashboardHtml'] }
    path('/chart.js')   { action: [GET: 'getChartJs'] }
    path('/api/status') { action: [GET: 'getStatusJson'] }
    path('/api/cycles') { action: [GET: 'getCyclesJson'] }
    path('/api/flow')   { action: [GET: 'getFlowJson'] }
    path('/api/stats')  { action: [GET: 'getStatsJson'] }
    path('/csv/cycles') { action: [GET: 'getCyclesCsv'] }
    path('/csv/flow')   { action: [GET: 'getFlowCsv'] }
}

preferences {
    page(name: "mainPage")
    page(name: "historyPage")
    page(name: "flowHistoryPage")
}

// ==================== Preferences Pages ====================

Map mainPage() {
    dynamicPage(name: "mainPage", title: "", install: true, uninstall: true) {
        section("Current Status") {
            paragraph getStatusText()
        }

        section("Dashboard") {
            if (state.accessToken) {
                String dashboardUrl = "${getFullLocalApiServerUrl()}/dashboard?access_token=${state.accessToken}"
                paragraph "<a href='${dashboardUrl}' target='_blank' rel='noopener' style='font-size:1.1em;font-weight:500'>Open Dashboard &#8599;</a><br><small style='color:#888'>Charts, statistics, and full history</small>"
            } else {
                paragraph "<i>Dashboard not available. Enable OAuth in the app code editor and click Done to reinitialize.</i>"
            }
        }

        section("App Name", hideable: true, hidden: true) {
            label title: "Set App Label", required: false
        }

        section("Devices", hideable: true, hidden: false) {
            input "pumpSwitch", "capability.powerMeter",
                title: "Pump Switch (with power metering)",
                required: true
            input "waterMeter", "capability.liquidFlowRate",
                title: "Water Meter Device",
                description: "Device with a volume attribute (e.g., Sinope VA4220ZB)",
                required: true, submitOnChange: true
            if (waterMeter) {
                input "waterMeterAttribute", "enum",
                    title: "Volume attribute",
                    options: getDeviceAttributes(waterMeter),
                    defaultValue: "volume", required: true
            }
            input "pumpActiveSwitch", "capability.switch",
                title: "Virtual Pump Active Switch (optional)",
                description: "Turned ON/OFF to reflect pump state",
                required: false
            input "emergencyLeakSensor", "capability.waterSensor",
                title: "Emergency Leak Sensor (optional)",
                description: "Virtual water sensor activated on emergency shutoff",
                required: false
            input "flowIndicatorSwitch", "capability.switch",
                title: "Virtual Water Flow Indicator (optional)",
                description: "Turned ON/OFF to reflect water flow state",
                required: false
            input "notificationDevices", "capability.notification",
                title: "Notification Devices (optional)",
                required: false, multiple: true
        }

        section("Power Thresholds", hideable: true, hidden: true) {
            input "powerOnThreshold", "number",
                title: "Power ON threshold (watts)",
                description: "Power above this means pump is running",
                defaultValue: 100, required: true
            input "powerOffThreshold", "number",
                title: "Power OFF threshold (watts)",
                description: "Power below this means pump is off",
                defaultValue: 10, required: true
            if (powerOffThreshold != null && powerOnThreshold != null &&
                    (powerOffThreshold as int) >= (powerOnThreshold as int)) {
                paragraph "<span style='color:red'>Warning: OFF threshold must be less than ON threshold for reliable detection.</span>"
            }
        }

        section("Emergency Shutoff", hideable: true, hidden: true) {
            input "enableEmergencyShutoff", "bool",
                title: "Enable emergency shutoff",
                description: "Automatically turn off pump if it runs too long",
                defaultValue: true, required: false
            input "emergencyTimeoutSeconds", "number",
                title: "Emergency shutoff timeout (seconds)",
                defaultValue: 300, required: true, range: "10..3600"
        }

        section("Water Flow Tracking", hideable: true, hidden: true) {
            input "enableFlowTracking", "bool",
                title: "Enable water flow tracking",
                description: "Track individual water flow events via the water meter's rate attribute",
                defaultValue: true, required: false
            input "flowCsvFileName", "text",
                title: "Flow CSV file name",
                defaultValue: "waterFlow.csv", required: true
        }

        section("CSV Logging", hideable: true, hidden: true) {
            input "enableCsvLogging", "bool",
                title: "Enable CSV file logging",
                defaultValue: true, required: false
            input "csvFileName", "text",
                title: "CSV file name",
                defaultValue: "pumpCycles.csv", required: true
        }

        section("Logging", hideable: true, hidden: true) {
            input "logLevel", "enum",
                title: "Log level",
                options: ["warn", "info", "debug"],
                defaultValue: "info", required: true
        }

        section("Pump Cycle History", hideable: true, hidden: false) {
            List<Map> cycleHistory = state.cycleHistory
            if (cycleHistory) {
                String td = "style='border:1px solid #999;padding:4px 8px'"
                String tdR = "style='border:1px solid #999;padding:4px 8px;text-align:right'"
                String table = "<table style='border-collapse:collapse;width:100%'>" +
                    "<thead><tr style='background:#ddd'>" +
                    "<th ${td}>Date/Time</th><th ${tdR}>Duration</th>" +
                    "<th ${tdR}>Coincident Flow</th><th ${tdR}>Coincident Rate</th><th ${tdR}>Tank Usage</th>" +
                    "</tr></thead><tbody>"
                int count = Math.min(cycleHistory.size(), 10)
                for (int i = 0; i < count; i++) {
                    Map entry = cycleHistory[i] as Map
                    String tankUsage = deriveTankUsage(entry, i + 1 < cycleHistory.size() ? cycleHistory[i + 1] as Map : null)
                    table += "<tr>" +
                        "<td ${td}>${entry.date}</td>" +
                        "<td ${tdR}>${fmtDec(entry.durationS)}s</td>" +
                        "<td ${tdR}>${fmtVol(entry.coincidentFlowL)}L</td>" +
                        "<td ${tdR}>${fmtDec(entry.coincidentLpm)} LPM</td>" +
                        "<td ${tdR}>${tankUsage}</td>" +
                        "</tr>"
                }
                table += "</tbody></table>"
                paragraph table
            } else {
                paragraph "No pump cycles recorded yet."
            }
            href "historyPage", title: "View full history & statistics", description: "All recorded pump cycles with min/max stats"
        }

        section("Water Flow History", hideable: true, hidden: false) {
            List<Map> flowHistory = state.flowHistory
            if (flowHistory) {
                String td = "style='border:1px solid #999;padding:4px 8px'"
                String tdR = "style='border:1px solid #999;padding:4px 8px;text-align:right'"
                String table = "<table style='border-collapse:collapse;width:100%'>" +
                    "<thead><tr style='background:#ddd'>" +
                    "<th ${td}>Date/Time</th><th ${tdR}>Duration</th>" +
                    "<th ${tdR}>Volume</th>" +
                    "</tr></thead><tbody>"
                int count = Math.min(flowHistory.size(), 10)
                for (int i = 0; i < count; i++) {
                    Map entry = flowHistory[i] as Map
                    table += "<tr>" +
                        "<td ${td}>${entry.date}</td>" +
                        "<td ${tdR}>${fmtDec(entry.durationS)}s</td>" +
                        "<td ${tdR}>${fmtVol(entry.volumeL)}L</td>" +
                        "</tr>"
                }
                table += "</tbody></table>"
                paragraph table
            } else {
                paragraph "No water flow events recorded yet."
            }
            href "flowHistoryPage", title: "View full flow history", description: "All recorded water flow events"
        }

        section("") {
            paragraph "<small>${APP_NAME} v${APP_VERSION}</small>"
        }
    }
}

Map historyPage() {
    dynamicPage(name: "historyPage", title: "Pump Cycle History") {
        List<Map> cycleHistory = state.cycleHistory
        if (!cycleHistory) {
            section { paragraph "No pump cycles recorded yet." }
            return
        }

        section("Statistics") {
            StringBuilder stats = new StringBuilder()

            // All-time stats from running counters
            Map allTime = computeAllTimeStats()
            if (allTime) {
                stats.append("Total cycles: <b>${allTime.totalCycles}</b>")
                if (allTime.cyclesPerDay) {
                    stats.append(" (${fmtDec(allTime.cyclesPerDay)}/day)")
                }
                stats.append("<br/>")
                if (allTime.totalFlowVolumeL && (allTime.totalFlowVolumeL as BigDecimal) > 0) {
                    stats.append("Total consumption (flow events): <b>${fmtVol(allTime.totalFlowVolumeL)}L</b><br/>")
                }
                if (allTime.totalTankUsageL && (allTime.totalTankUsageL as BigDecimal) > 0) {
                    stats.append("Total tank usage (between cycles): <b>${fmtVol(allTime.totalTankUsageL)}L</b><br/>")
                }
                stats.append("Duration: mean <b>${fmtDec(allTime.meanDurationS)}s</b> \u00b1 ${fmtDec(allTime.stddevDurationS)}s<br/>")
                stats.append("Coincident flow / cycle: mean <b>${fmtVol(allTime.meanCoincidentFlowL)}L</b> \u00b1 ${fmtVol(allTime.stddevCoincidentFlowL)}L<br/>")
                if (allTime.longestRunS) {
                    stats.append("Longest: <b>${fmtDec(allTime.longestRunS)}s</b> (${allTime.longestRunDate})<br/>")
                }
                if (allTime.shortestRunS) {
                    stats.append("Shortest: <b>${fmtDec(allTime.shortestRunS)}s</b> (${allTime.shortestRunDate})<br/>")
                }
            }

            // Recent window stats (median, etc.)
            Map recent = computeCycleStats()
            if (recent) {
                stats.append("<br/><b>Recent ${recent.recentCount} cycles:</b><br/>")
                stats.append("Duration: median <b>${fmtDec(recent.medianDurationS)}s</b>, mean ${fmtDec(recent.meanDurationS)}s \u00b1 ${fmtDec(recent.stddevDurationS)}s<br/>")
                stats.append("Coincident flow: median <b>${fmtVol(recent.medianCoincidentFlowL)}L</b>, mean ${fmtVol(recent.meanCoincidentFlowL)}L \u00b1 ${fmtVol(recent.stddevCoincidentFlowL)}L<br/>")
            }

            if (allTime.firstCycleDate) {
                stats.append("<br/><small>First recorded cycle: ${allTime.firstCycleDate}</small>")
            }

            paragraph stats.toString()
        }

        section("") {
            paragraph "View the <b>Dashboard</b> for full history, charts, and detailed analysis."
        }
    }
}

Map flowHistoryPage() {
    dynamicPage(name: "flowHistoryPage", title: "Water Flow History") {
        List<Map> flowHistory = state.flowHistory
        if (!flowHistory) {
            section { paragraph "No water flow events recorded yet." }
            return
        }

        section("Statistics") {
            StringBuilder stats = new StringBuilder()
            stats.append("Total flow events: <b>${(state.totalFlowEventCount ?: flowHistory.size())}</b><br/>")
            if (state.totalFlowVolume) {
                stats.append("Total volume: <b>${fmtVol(state.totalFlowVolume)}L</b><br/>")
            }
            paragraph stats.toString()
        }

        section("") {
            paragraph "View the <b>Dashboard</b> for full flow history and charts."
        }
    }
}

// ==================== Lifecycle ====================

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

    if (state.version != APP_VERSION) {
        log.warn "${APP_NAME}: version ${APP_VERSION} (was: ${state.version})"
        state.version = APP_VERSION
        // Version-aware reconfigure hook: add per-version migrations here when needed.
    }

    // Initialize state variables if not set
    if (state.previousPower == null) state.previousPower = 0
    if (state.pumpBegin == null) state.pumpBegin = 0
    if (state.volumeBegin == null) state.volumeBegin = 0.0
    if (state.pumpRunning == null) state.pumpRunning = false
    if (state.pumpCurrentDuration == null) state.pumpCurrentDuration = 0
    if (state.lastRunDuration == null) state.lastRunDuration = 0
    // lastRunCoincidentFlow / lastRunCoincidentLpm intentionally left unset until first cycle;
    // reads use `?: 0`, and defaulting here would pre-empt the v3 migration's rename copy.
    if (state.pumpTimeMax == null) state.pumpTimeMax = 0
    if (state.pumpTimeMaxDate == null) state.pumpTimeMaxDate = ""
    if (state.pumpTimeMin == null) state.pumpTimeMin = 0
    if (state.pumpTimeMinDate == null) state.pumpTimeMinDate = ""
    if (state.totalTankUsage == null) state.totalTankUsage = 0.0
    // state.lastCycleVolumeAtEnd: raw meter reading at end of previous cycle (null until first cycle completes)
    if (state.cycleHistory == null) state.cycleHistory = []

    // Running counters for aggregate statistics
    // totalCoincidentFlow / totalCoincidentFlowSq intentionally left unset — see note above.
    if (state.totalCycleCount == null) state.totalCycleCount = 0
    if (state.totalPumpDurationS == null) state.totalPumpDurationS = 0.0
    if (state.totalPumpDurationSqS == null) state.totalPumpDurationSqS = 0.0
    if (state.firstCycleDate == null) state.firstCycleDate = ""
    if (state.firstCycleTimestamp == null) state.firstCycleTimestamp = 0

    // Flow tracking state
    if (state.flowActive == null) state.flowActive = false
    if (state.flowBeginTime == null) state.flowBeginTime = 0
    if (state.flowVolumeBegin == null) state.flowVolumeBegin = 0.0
    if (state.lastFlowDuration == null) state.lastFlowDuration = 0.0
    if (state.lastFlowVolume == null) state.lastFlowVolume = 0.0
    if (state.flowHistory == null) state.flowHistory = []
    if (state.totalFlowVolume == null) state.totalFlowVolume = 0.0
    if (state.totalFlowEventCount == null) state.totalFlowEventCount = 0

    // Create access token for dashboard API endpoints
    if (!state.accessToken) {
        try {
            createAccessToken()
            logInfo("Access token created for dashboard API")
        } catch (Exception e) {
            logError("Failed to create access token: ${e.message}. Enable OAuth in the app code editor.")
        }
    }

    // One-time migration of running counters from existing history
    migrateRunningCounters()

    // Subscribe to power events from pump switch
    subscribe(pumpSwitch, "power", powerHandler)

    // Subscribe to water meter events
    if (waterMeter) {
        subscribe(waterMeter, waterMeterAttribute ?: "volume", volumeHandler)
        if (enableFlowTracking != false) {
            subscribe(waterMeter, "rate", rateHandler)
        }
    }

    // Hub-restart recovery — initialize() is not called on reboot.
    subscribe(location, "systemStart", systemStartHandler)

    recoverPumpState()

    log.info("${APP_NAME} initialized. Pump running: ${state.pumpRunning}")
}

void systemStartHandler(evt) {
    logInfo("Hub started — re-evaluating pump state")
    recoverPumpState()
}

private void recoverPumpState() {
    if (!state.pumpRunning) return

    Object rawPower = pumpSwitch?.currentValue("power")
    if (rawPower == null) {
        logWarn("Recovery: pump power unavailable — leaving state.pumpRunning as-is")
        return
    }
    BigDecimal currentPower = rawPower as BigDecimal
    int onThreshold = (powerOnThreshold ?: 100) as int

    if (currentPower > onThreshold) {
        // Pump still running — reschedule emergency timer for the remaining window.
        if (enableEmergencyShutoff != false) {
            long elapsed = now() - (state.pumpBegin as long)
            int timeoutMs = ((emergencyTimeoutSeconds ?: 300) as int) * 1000
            long remaining = timeoutMs - elapsed
            if (remaining > 0) {
                int remainingSeconds = Math.round(remaining / 1000.0) as int
                runIn(remainingSeconds, "emergencyShutoff")
                logInfo("Recovered running pump state, emergency timer rescheduled (${remainingSeconds}s remaining)")
            } else {
                logWarn("Recovered running pump state but emergency timeout already exceeded - triggering shutoff")
                emergencyShutoff()
            }
        }
    } else {
        // Pump stopped while we weren't watching — we missed the stop event.
        logWarn("Pump was running before restart but is now off (${currentPower}W). Cycle data lost.")
        state.pumpRunning = false
        if (pumpActiveSwitch) pumpActiveSwitch.off()
    }
}

// ==================== Event Handler ====================

void powerHandler(Event evt) {
    BigDecimal currentPower = 0
    try {
        currentPower = evt.value as BigDecimal
    } catch (Exception e) {
        logWarn("Could not parse power value '${evt.value}': ${e.message}")
        return
    }

    long currentTime = now()
    BigDecimal prevPower = (state.previousPower ?: 0) as BigDecimal
    int offThreshold = (powerOffThreshold ?: 10) as int
    int onThreshold = (powerOnThreshold ?: 100) as int

    logDebug("Power event: ${currentPower}W (previous: ${prevPower}W)")

    if (currentPower < offThreshold && prevPower > onThreshold) {
        handlePumpStopped(currentTime)
    } else if (currentPower > onThreshold) {
        if (prevPower < offThreshold) {
            handlePumpStarted(currentTime)
        } else {
            handlePumpRunning(currentTime)
        }
    }
    // else: no transition (intermediate power or same state)

    state.previousPower = currentPower
}

// ==================== Pump State Logic ====================

private void handlePumpStarted(long currentTime) {
    state.pumpRunning = true
    state.pumpBegin = currentTime
    state.volumeBegin = readVolume()
    state.pumpCurrentDuration = 0

    // Compute tank usage since last cycle ended
    if (state.lastCycleVolumeAtEnd != null) {
        BigDecimal tankUsage = (state.volumeBegin as BigDecimal) - (state.lastCycleVolumeAtEnd as BigDecimal)
        if (tankUsage >= 0) {
            state.totalTankUsage = ((state.totalTankUsage ?: 0.0) as BigDecimal) + tankUsage
            logInfo("Pump started (tank usage since last cycle: ${fmtVol(tankUsage)}L)")
        } else {
            logInfo("Pump started")
        }
    } else {
        logInfo("Pump started")
    }

    if (pumpActiveSwitch) pumpActiveSwitch.on()

    // Schedule emergency shutoff
    if (enableEmergencyShutoff != false) {
        int timeout = (emergencyTimeoutSeconds ?: 300) as int
        runIn(timeout, "emergencyShutoff")
        logDebug("Emergency shutoff scheduled in ${timeout}s")
    }
}

private void handlePumpStopped(long currentTime) {
    state.pumpRunning = false

    // Cancel emergency timer
    unschedule("emergencyShutoff")

    if (pumpActiveSwitch) pumpActiveSwitch.off()

    // Calculate cycle metrics
    long pumpBegin = (state.pumpBegin ?: currentTime) as long
    long durationMs = currentTime - pumpBegin
    BigDecimal durationSeconds = durationMs / 1000.0

    BigDecimal volumeEnd = readVolume()
    BigDecimal volumeBegin = (state.volumeBegin ?: 0.0) as BigDecimal
    BigDecimal coincidentFlow = volumeEnd - volumeBegin
    if (coincidentFlow < 0) coincidentFlow = 0.0

    BigDecimal coincidentLpm = 0.0
    if (durationMs > 0 && coincidentFlow > 0) {
        coincidentLpm = (coincidentFlow * 60000.0) / durationMs
    }

    // Store last run stats (raw values - format at display time only).
    // coincidentFlow = downstream draw measured during this pump's ON window;
    // it influences cycle duration (pump must offset both deficit and ongoing draw),
    // and is NOT a measurement of pump output.
    state.lastRunDuration = durationSeconds
    state.lastRunCoincidentFlow = coincidentFlow
    state.lastRunCoincidentLpm = coincidentLpm
    state.totalCoincidentFlow = ((state.totalCoincidentFlow ?: 0.0) as BigDecimal) + coincidentFlow
    state.lastCycleVolumeAtEnd = volumeEnd

    // Update running counters for aggregate statistics
    state.totalCycleCount = ((state.totalCycleCount ?: 0) as int) + 1
    state.totalPumpDurationS = ((state.totalPumpDurationS ?: 0.0) as BigDecimal) + durationSeconds
    state.totalPumpDurationSqS = ((state.totalPumpDurationSqS ?: 0.0) as BigDecimal) + (durationSeconds * durationSeconds)
    state.totalCoincidentFlowSq = ((state.totalCoincidentFlowSq ?: 0.0) as BigDecimal) + (coincidentFlow * coincidentFlow)
    if (!state.firstCycleDate) {
        state.firstCycleDate = formatDate(currentTime)
        state.firstCycleTimestamp = currentTime
    }

    logInfo("Pump stopped: ${fmtDec(durationSeconds)}s, coincident flow ${fmtVol(coincidentFlow)}L @ ${fmtDec(coincidentLpm)} LPM")

    // Append to CSV log
    if (enableCsvLogging != false) {
        appendToCsvLog(currentTime, durationSeconds, coincidentFlow, coincidentLpm, volumeBegin, volumeEnd)
    }

    // Add to cycle history (newest first, capped at 100)
    String dateStr = formatDate(currentTime)
    List<Map> history = (state.cycleHistory ?: []) as List<Map>
    history.add(0, [
        ts: currentTime,
        date: dateStr,
        durationS: durationSeconds,
        coincidentFlowL: coincidentFlow,
        coincidentLpm: coincidentLpm,
        volumeAtStart: volumeBegin,
        volumeAtEnd: volumeEnd
    ])
    if (history.size() > 100) {
        history = history.subList(0, 100)
    }
    state.cycleHistory = history

    // Update min/max statistics
    updateStatistics(durationMs, currentTime)
}

private void handlePumpRunning(long currentTime) {
    long pumpBegin = (state.pumpBegin ?: currentTime) as long
    long durationMs = currentTime - pumpBegin
    state.pumpCurrentDuration = durationMs

    BigDecimal durationSeconds = durationMs / 1000.0
    logDebug("Pump running: ${fmtDec(durationSeconds)}s")

    // Redundant emergency check (defense-in-depth alongside scheduled callback)
    if (enableEmergencyShutoff != false) {
        int timeout = (emergencyTimeoutSeconds ?: 300) as int
        if (durationSeconds > timeout) {
            performEmergencyShutoff(durationSeconds)
        }
    }
}

void emergencyShutoff() {
    if (!state.pumpRunning) {
        logDebug("emergencyShutoff() called but pump is not running, ignoring")
        return
    }

    long durationMs = now() - (state.pumpBegin as long)
    BigDecimal durationSeconds = durationMs / 1000.0
    performEmergencyShutoff(durationSeconds)
}

private void performEmergencyShutoff(BigDecimal durationSeconds) {
    int timeout = (emergencyTimeoutSeconds ?: 300) as int

    logError("EMERGENCY SHUTOFF: Pump has run for ${fmtDec(durationSeconds)}s (limit: ${timeout}s)")

    String msg = "EMERGENCY STOP: Well pump has run for ${fmtDec(durationSeconds)}s, exceeding ${timeout}s limit"
    sendNotification(msg)

    if (emergencyLeakSensor) {
        emergencyLeakSensor.wet()
        logInfo("Emergency leak sensor activated")
    }

    pumpSwitch.off()
    if (pumpActiveSwitch) pumpActiveSwitch.off()

    state.pumpRunning = false
    unschedule("emergencyShutoff")
}

// ==================== Volume Tracking ====================

void volumeHandler(Event evt) {
    logDebug("Volume event: ${evt.value}L")
}

// ==================== Water Flow Tracking ====================

void rateHandler(Event evt) {
    BigDecimal rate = 0
    try {
        rate = evt.value as BigDecimal
    } catch (Exception e) {
        logWarn("Could not parse rate value '${evt.value}': ${e.message}")
        return
    }

    logDebug("Rate event: ${rate} LPM (flowActive: ${state.flowActive})")

    if (rate > 0 && !state.flowActive) {
        // Flow just started
        state.flowActive = true
        state.flowBeginTime = now()
        state.flowVolumeBegin = readVolume()
        if (flowIndicatorSwitch) flowIndicatorSwitch.on()
        logInfo("Water flow started")

    } else if (rate == 0 && state.flowActive) {
        // Flow just stopped
        state.flowActive = false
        if (flowIndicatorSwitch) flowIndicatorSwitch.off()

        long flowEnd = now()
        BigDecimal volumeEnd = readVolume()
        BigDecimal volumeBegin = (state.flowVolumeBegin ?: 0.0) as BigDecimal
        BigDecimal volumeDelivered = volumeEnd - volumeBegin
        if (volumeDelivered < 0) volumeDelivered = 0.0

        long durationMs = flowEnd - ((state.flowBeginTime ?: flowEnd) as long)
        BigDecimal durationSeconds = durationMs / 1000.0

        // Store last flow stats (raw values - format at display time only)
        state.lastFlowDuration = durationSeconds
        state.lastFlowVolume = volumeDelivered
        state.totalFlowVolume = ((state.totalFlowVolume ?: 0.0) as BigDecimal) + volumeDelivered
        state.totalFlowEventCount = ((state.totalFlowEventCount ?: 0) as int) + 1

        logInfo("Water flow stopped: ${fmtVol(volumeDelivered)}L in ${fmtDec(durationSeconds)}s")

        // Append to flow CSV
        if (enableCsvLogging != false) {
            appendToFlowCsvLog(flowEnd, durationSeconds, volumeDelivered)
        }

        // Add to flow history (newest first, capped at 100)
        String dateStr = formatDate(flowEnd)
        List<Map> history = (state.flowHistory ?: []) as List<Map>
        history.add(0, [
            ts: flowEnd,
            date: dateStr,
            durationS: durationSeconds,
            volumeL: volumeDelivered
        ])
        if (history.size() > 100) {
            history = history.subList(0, 100)
        }
        state.flowHistory = history
    }
}

private void appendToFlowCsvLog(long timestamp, BigDecimal durationSeconds, BigDecimal volume) {
    String fileName = flowCsvFileName ?: "waterFlow.csv"
    String dateStr = formatDate(timestamp)
    String csvLine = "${dateStr},${durationSeconds},${volume}\n"

    String existingData = null
    try {
        byte[] byteArray = safeDownloadHubFile(fileName)
        if (byteArray) existingData = new String(byteArray, 'UTF-8')
    } catch (Exception e) {
        logWarn("Could not read existing flow CSV data: ${e.message}")
    }

    if (existingData == null) {
        existingData = "datetime,duration_s,volume_L\n"
    }

    String newData = existingData + csvLine
    safeUploadHubFile(fileName, newData.getBytes('UTF-8'))
    logDebug("Appended flow event to ${fileName}")
}

// ==================== Volume and Flow ====================

private BigDecimal readVolume() {
    if (!waterMeter || !waterMeterAttribute) return 0.0
    Object value = waterMeter.currentValue(waterMeterAttribute)
    if (value == null) {
        logWarn("Could not read '${waterMeterAttribute}' from ${waterMeter.displayName}")
        return 0.0
    }
    return value as BigDecimal
}

// ==================== Statistics ====================

private void updateStatistics(long durationMs, long currentTime) {
    String dateStr = formatDate(currentTime)

    if ((state.pumpTimeMax as long) == 0 || durationMs > (state.pumpTimeMax as long)) {
        state.pumpTimeMax = durationMs
        state.pumpTimeMaxDate = dateStr
        logInfo("New maximum pump run time: ${fmtDec(durationMs / 1000.0)}s")
    }

    if ((state.pumpTimeMin as long) == 0 || durationMs < (state.pumpTimeMin as long)) {
        state.pumpTimeMin = durationMs
        state.pumpTimeMinDate = dateStr
        logInfo("New minimum pump run time: ${fmtDec(durationMs / 1000.0)}s")
    }
}

/**
 * Compute statistics from the recent 100-entry cycle history window.
 * Returns median/mean/stddev for duration and volume.
 */
private Map computeCycleStats() {
    List<Map> history = (state.cycleHistory ?: []) as List<Map>
    int n = history.size()
    if (n == 0) return [:]

    List<BigDecimal> durations = history.collect { ((it as Map).durationS ?: 0) as BigDecimal }
    List<BigDecimal> coincidentFlows = history.collect { ((it as Map).coincidentFlowL ?: 0) as BigDecimal }

    // Median duration (SensorAggregatorChild pattern)
    List<BigDecimal> sortedDurations = new ArrayList<BigDecimal>(durations)
    Collections.sort(sortedDurations)
    BigDecimal medianDuration
    if (n % 2 == 0) {
        medianDuration = (sortedDurations[(n / 2 - 1) as int] + sortedDurations[(n / 2) as int]) / 2.0
    } else {
        medianDuration = sortedDurations[(n / 2) as int]
    }

    // Median coincident flow
    List<BigDecimal> sortedCoincident = new ArrayList<BigDecimal>(coincidentFlows)
    Collections.sort(sortedCoincident)
    BigDecimal medianCoincident
    if (n % 2 == 0) {
        medianCoincident = (sortedCoincident[(n / 2 - 1) as int] + sortedCoincident[(n / 2) as int]) / 2.0
    } else {
        medianCoincident = sortedCoincident[(n / 2) as int]
    }

    // Mean and stddev for duration
    BigDecimal sumDuration = 0.0
    durations.each { sumDuration += it }
    BigDecimal meanDuration = sumDuration / n
    BigDecimal varianceDuration = 0.0
    durations.each { varianceDuration += (it - meanDuration) * (it - meanDuration) }
    varianceDuration = varianceDuration / n
    BigDecimal stddevDuration = Math.sqrt(varianceDuration as double) as BigDecimal

    // Mean and stddev for coincident flow
    BigDecimal sumCoincident = 0.0
    coincidentFlows.each { sumCoincident += it }
    BigDecimal meanCoincident = sumCoincident / n
    BigDecimal varianceCoincident = 0.0
    coincidentFlows.each { varianceCoincident += (it - meanCoincident) * (it - meanCoincident) }
    varianceCoincident = varianceCoincident / n
    BigDecimal stddevCoincident = Math.sqrt(varianceCoincident as double) as BigDecimal

    return [
        recentCount: n,
        medianDurationS: medianDuration,
        meanDurationS: meanDuration,
        stddevDurationS: stddevDuration,
        medianCoincidentFlowL: medianCoincident,
        meanCoincidentFlowL: meanCoincident,
        stddevCoincidentFlowL: stddevCoincident,
    ]
}

/**
 * Compute all-time statistics from running counters.
 */
private Map computeAllTimeStats() {
    int totalCycles = (state.totalCycleCount ?: 0) as int
    if (totalCycles == 0) return [:]

    BigDecimal totalDuration = (state.totalPumpDurationS ?: 0.0) as BigDecimal
    BigDecimal totalDurationSq = (state.totalPumpDurationSqS ?: 0.0) as BigDecimal
    BigDecimal totalCoincident = (state.totalCoincidentFlow ?: 0.0) as BigDecimal
    BigDecimal totalCoincidentSq = (state.totalCoincidentFlowSq ?: 0.0) as BigDecimal

    BigDecimal meanDuration = totalDuration / totalCycles
    BigDecimal varianceDuration = (totalDurationSq / totalCycles) - (meanDuration * meanDuration)
    if (varianceDuration < 0) varianceDuration = 0.0
    BigDecimal stddevDuration = Math.sqrt(varianceDuration as double) as BigDecimal

    BigDecimal meanCoincident = totalCoincident / totalCycles
    BigDecimal varianceCoincident = (totalCoincidentSq / totalCycles) - (meanCoincident * meanCoincident)
    if (varianceCoincident < 0) varianceCoincident = 0.0
    BigDecimal stddevCoincident = Math.sqrt(varianceCoincident as double) as BigDecimal

    BigDecimal cyclesPerDay = 0.0
    if (state.firstCycleTimestamp && (state.firstCycleTimestamp as long) > 0) {
        long elapsed = (now() - (state.firstCycleTimestamp as long)) / 86400000 as long
        long daysSinceFirst = elapsed > 1 ? elapsed : 1
        cyclesPerDay = (totalCycles as BigDecimal) / (daysSinceFirst as BigDecimal)
    }

    return [
        totalCycles: totalCycles,
        totalFlowEvents: (state.totalFlowEventCount ?: 0) as int,
        totalCoincidentFlowL: totalCoincident,
        totalTankUsageL: (state.totalTankUsage ?: 0.0) as BigDecimal,
        totalFlowVolumeL: (state.totalFlowVolume ?: 0.0) as BigDecimal,
        totalPumpDurationS: totalDuration,
        meanDurationS: meanDuration,
        stddevDurationS: stddevDuration,
        meanCoincidentFlowL: meanCoincident,
        stddevCoincidentFlowL: stddevCoincident,
        cyclesPerDay: cyclesPerDay,
        longestRunS: ((state.pumpTimeMax ?: 0) as long) / 1000.0 as BigDecimal,
        longestRunDate: state.pumpTimeMaxDate ?: "",
        shortestRunS: ((state.pumpTimeMin ?: 0) as long) / 1000.0 as BigDecimal,
        shortestRunDate: state.pumpTimeMinDate ?: "",
        firstCycleDate: state.firstCycleDate ?: "",
        firstCycleTs: (state.firstCycleTimestamp ?: 0) as long,
    ]
}

/**
 * Group history entries by day for daily summaries.
 * Keys by tz-local yyyy-MM-dd derived from each entry's `ts`; emits `ts` (start-of-day epoch ms)
 * so consumers can format the label themselves without parsing date strings.
 */
private List<Map> computeDailySummaries(List<Map> history, String volumeKey) {
    if (!history) return []
    Map<String, Map> byDay = [:]
    history.each { Map entry ->
        Long entryTs = entry.ts as Long
        if (entryTs == null) return
        String day = new Date(entryTs).format("yyyy-MM-dd", location.timeZone)
        Map daySummary = byDay.get(day)
        if (!daySummary) {
            // Use this entry's ts as a representative timestamp within the day — sufficient for chart labels;
            // consumers should format with the hub's tz (or accept locale browser tz).
            daySummary = [ts: entryTs, date: day, count: 0, totalVolumeL: 0.0 as BigDecimal, totalDurationS: 0.0 as BigDecimal]
            byDay[day] = daySummary
        }
        daySummary.count = (daySummary.count as int) + 1
        daySummary.totalVolumeL = (daySummary.totalVolumeL as BigDecimal) + ((entry[volumeKey] ?: 0) as BigDecimal)
        daySummary.totalDurationS = (daySummary.totalDurationS as BigDecimal) + ((entry.durationS ?: 0) as BigDecimal)
    }
    return byDay.values().toList().sort { a, b -> (b as Map).ts <=> (a as Map).ts }
}

/**
 * Bucket pump cycles by hour-of-day (0-23) for peak usage analysis.
 */
private Map<Integer, Integer> computeHourlyDistribution() {
    List<Map> history = (state.cycleHistory ?: []) as List<Map>
    Map<Integer, Integer> hourCounts = [:]
    for (int h = 0; h < 24; h++) { hourCounts[h] = 0 }
    history.each { Map entry ->
        Long entryTs = entry.ts as Long
        if (entryTs == null) return
        int hour = (new Date(entryTs).format("HH", location.timeZone)) as int
        hourCounts[hour] = (hourCounts[hour] ?: 0) + 1
    }
    return hourCounts
}

/**
 * Schema migrations, idempotent per version gate.
 *   v1 — seed running counters from pre-existing history.
 *   v2 — backfill epoch-ms `ts` on history entries (previously only `date` strings).
 */
private void migrateRunningCounters() {
    int currentVersion = (state.statsVersion ?: 0) as int

    if (currentVersion < 1) {
        List<Map> history = (state.cycleHistory ?: []) as List<Map>
        if (history) {
            int count = history.size()
            BigDecimal totalDur = 0.0
            BigDecimal totalDurSq = 0.0
            BigDecimal totalVolSq = 0.0

            history.each { Map entry ->
                BigDecimal dur = (entry.durationS ?: 0) as BigDecimal
                BigDecimal vol = (entry.volumeL ?: 0) as BigDecimal
                totalDur += dur
                totalDurSq += (dur * dur)
                totalVolSq += (vol * vol)
            }

            if ((state.totalCycleCount ?: 0) as int == 0) {
                state.totalCycleCount = count
                state.totalPumpDurationS = totalDur
                state.totalPumpDurationSqS = totalDurSq
                state.totalVolumeSq = totalVolSq
            }

            if (!state.firstCycleDate && history.size() > 0) {
                Map oldest = history.last() as Map
                state.firstCycleDate = oldest.date as String
                try {
                    state.firstCycleTimestamp = Date.parse("yyyy-MM-dd HH:mm:ss", oldest.date as String).getTime()
                } catch (Exception e) {
                    state.firstCycleTimestamp = 0
                }
            }
        }

        List<Map> flowHistory = (state.flowHistory ?: []) as List<Map>
        if (flowHistory && ((state.totalFlowEventCount ?: 0) as int) == 0) {
            state.totalFlowEventCount = flowHistory.size()
        }

        state.statsVersion = 1
        logInfo("Running counters migrated from history (${(state.cycleHistory ?: []).size()} cycles, ${(state.flowHistory ?: []).size()} flow events)")
    }

    // v2 timestamp backfill — idempotent, runs on every initialize() until no work remains.
    // Detects entries with `date` but no `ts`; the loop becomes a no-op once all entries carry ts.
    backfillHistoryTimestamps()

    // v3 field rename: `volumeL`/`avgLpm` → `coincidentFlowL`/`coincidentLpm` on cycle entries.
    // Also migrates `state.totalVolume`/`state.totalVolumeSq`/`state.lastRunVolume`/`state.lastRunAvgLpm`
    // to their `Coincident…` counterparts, and drops settings for the removed flow-alert feature.
    renameCoincidentFlowFields()
}

/**
 * Backfill `ts` (epoch ms) on history entries that only carry `date` strings.
 * Idempotent: skip-if-already-present per entry, log a single summary line each call.
 */
private void backfillHistoryTimestamps() {
    List<Map> cycleHistory = (state.cycleHistory ?: []) as List<Map>
    int cycleNeed = cycleHistory.count { Map e -> e.ts == null && e.date } as int
    int flowNeed = 0
    List<Map> flowHistory = (state.flowHistory ?: []) as List<Map>
    flowNeed = flowHistory.count { Map e -> e.ts == null && e.date } as int

    if (cycleNeed == 0 && flowNeed == 0) return

    int cycleDone = 0
    int cycleFail = 0
    List<Map> migratedCycles = []
    for (Map entry : cycleHistory) {
        if (entry.ts != null || !entry.date) {
            migratedCycles.add(entry)
            continue
        }
        long ts = parseHistoryDate(entry.date as String)
        if (ts > 0) {
            cycleDone++
            migratedCycles.add(entry + [ts: ts])
        } else {
            cycleFail++
            migratedCycles.add(entry)
        }
    }
    state.cycleHistory = migratedCycles

    int flowDone = 0
    int flowFail = 0
    List<Map> migratedFlow = []
    for (Map entry : flowHistory) {
        if (entry.ts != null || !entry.date) {
            migratedFlow.add(entry)
            continue
        }
        long ts = parseHistoryDate(entry.date as String)
        if (ts > 0) {
            flowDone++
            migratedFlow.add(entry + [ts: ts])
        } else {
            flowFail++
            migratedFlow.add(entry)
        }
    }
    state.flowHistory = migratedFlow

    log.warn "${APP_NAME}: ts backfill — cycles ${cycleDone}/${cycleNeed} (failed ${cycleFail}), flow ${flowDone}/${flowNeed} (failed ${flowFail})"
}

private long parseHistoryDate(String s) {
    if (!s) return 0L
    try {
        return Date.parse("yyyy-MM-dd HH:mm:ss", s).getTime()
    } catch (Exception e) {
        log.warn "${APP_NAME}: failed to parse history date '${s}': ${e.message}"
        return 0L
    }
}

/**
 * v3 migration: rename pump-cycle "volume" fields to "coincident flow" since the meter
 * measures downstream draw, not pump output. Idempotent.
 */
private void renameCoincidentFlowFields() {
    boolean changed = false

    // Running counters — copy each old key (if still present) into the new key, then drop the old.
    // Idempotent: after first successful run the old keys are gone and subsequent runs no-op.
    if (state.totalVolume != null) {
        state.totalCoincidentFlow = state.totalVolume
        state.remove('totalVolume')
        changed = true
    }
    if (state.totalVolumeSq != null) {
        state.totalCoincidentFlowSq = state.totalVolumeSq
        state.remove('totalVolumeSq')
        changed = true
    }
    if (state.lastRunVolume != null) {
        state.lastRunCoincidentFlow = state.lastRunVolume
        state.remove('lastRunVolume')
        changed = true
    }
    if (state.lastRunAvgLpm != null) {
        state.lastRunCoincidentLpm = state.lastRunAvgLpm
        state.remove('lastRunAvgLpm')
        changed = true
    }

    // Cycle history entries
    List<Map> cycleHistory = (state.cycleHistory ?: []) as List<Map>
    int renamed = 0
    List<Map> migrated = []
    for (Map entry : cycleHistory) {
        boolean hasOld = entry.volumeL != null || entry.avgLpm != null
        boolean hasNew = entry.coincidentFlowL != null
        if (hasOld && !hasNew) {
            Map newEntry = new LinkedHashMap(entry)
            newEntry.coincidentFlowL = entry.volumeL
            newEntry.coincidentLpm = entry.avgLpm
            newEntry.remove('volumeL')
            newEntry.remove('avgLpm')
            migrated.add(newEntry)
            renamed++
        } else {
            migrated.add(entry)
        }
    }
    if (renamed > 0) {
        state.cycleHistory = migrated
        changed = true
    }

    // Drop obsolete flow-alert settings (the feature was removed in 0.4.0).
    ['enableFlowAlerts', 'baseAlertTimeSeconds', 'flowThreshold1', 'flowThreshold2', 'flowThreshold3', 'flowBonusSeconds'].each { String name ->
        if (settings[name] != null) {
            app.removeSetting(name)
            changed = true
        }
    }

    if (changed) {
        log.warn "${APP_NAME}: v3 rename — coincident-flow fields migrated (${renamed} cycle entries)"
    }
}

// ==================== API Endpoints ====================

Map getDashboardHtml() {
    String html
    try {
        byte[] bytes = safeDownloadHubFile(DASHBOARD_FILE)
        html = bytes ? new String(bytes, 'UTF-8') : '<html><body>Dashboard file not found. Upload <code>wellpump-dashboard.html</code> to the hub File Manager.</body></html>'
    } catch (Exception e) {
        html = "<html><body>Error loading dashboard: ${e.message}</body></html>"
    }
    html = html.replaceAll('\\$\\{access_token\\}', state.accessToken ?: '')
    return render(status: 200, contentType: 'text/html', data: html)
}

Map getChartJs() {
    try {
        byte[] bytes = safeDownloadHubFile(CHARTJS_FILE)
        if (bytes) {
            return render(status: 200, contentType: 'application/javascript', data: new String(bytes, 'UTF-8'))
        }
    } catch (Exception ignored) {}
    return render(status: 404, contentType: 'text/plain', data: "Chart.js not found. Upload ${CHARTJS_FILE} to File Manager.")
}

Map getStatusJson() {
    Map status = [
        pumpRunning: state.pumpRunning ?: false,
        flowActive: state.flowActive ?: false,
        currentPowerW: pumpSwitch?.currentValue("power"),
        currentVolumeL: waterMeter?.currentValue(waterMeterAttribute ?: "volume"),
        pumpBeginTimestamp: state.pumpRunning ? (state.pumpBegin as long) : null,
        lastRunDurationS: state.lastRunDuration,
        lastRunCoincidentFlowL: state.lastRunCoincidentFlow,
        lastRunCoincidentLpm: state.lastRunCoincidentLpm,
        lastFlowDurationS: state.lastFlowDuration,
        lastFlowVolumeL: state.lastFlowVolume,
        pumpDeviceName: pumpSwitch?.displayName ?: "",
        meterDeviceName: waterMeter?.displayName ?: "",
        flowTrackingEnabled: enableFlowTracking != false,
        appVersion: APP_VERSION,
        timestamp: now()
    ]
    return render(status: 200, contentType: 'application/json', data: JsonOutput.toJson(status))
}

Map getCyclesJson() {
    List<Map> history = (state.cycleHistory ?: []) as List<Map>
    List<Map> enriched = []
    for (int i = 0; i < history.size(); i++) {
        Map entry = history[i] as Map
        Map nextEntry = (i + 1 < history.size()) ? history[i + 1] as Map : null
        BigDecimal tankUsage = null
        if (nextEntry != null && entry.volumeAtStart != null && nextEntry.volumeAtEnd != null) {
            BigDecimal gap = (entry.volumeAtStart as BigDecimal) - (nextEntry.volumeAtEnd as BigDecimal)
            if (gap >= 0) tankUsage = gap
        }
        enriched.add(entry + [tankUsageL: tankUsage])
    }
    return render(status: 200, contentType: 'application/json', data: JsonOutput.toJson(enriched))
}

Map getFlowJson() {
    return render(status: 200, contentType: 'application/json',
        data: JsonOutput.toJson(state.flowHistory ?: []))
}

Map getStatsJson() {
    Map allTime = computeAllTimeStats()
    Map recentCycle = computeCycleStats()
    List<Map> dailyCycles = computeDailySummaries((state.cycleHistory ?: []) as List<Map>, 'coincidentFlowL')
    List<Map> dailyFlow = computeDailySummaries((state.flowHistory ?: []) as List<Map>, 'volumeL')
    Map<Integer, Integer> hourly = computeHourlyDistribution()

    Map stats = [
        allTime: allTime,
        recentWindow: recentCycle,
        dailyCycleSummaries: dailyCycles,
        dailyFlowSummaries: dailyFlow,
        hourlyDistribution: hourly
    ]
    return render(status: 200, contentType: 'application/json', data: JsonOutput.toJson(stats))
}

Map getCyclesCsv() {
    String fileName = csvFileName ?: "pumpCycles.csv"
    try {
        byte[] bytes = safeDownloadHubFile(fileName)
        String csv = bytes ? new String(bytes, 'UTF-8') : "datetime,duration_s,coincident_flow_L,coincident_lpm,vol_at_start,vol_at_end\n"
        return render(status: 200, contentType: 'text/csv', data: csv)
    } catch (Exception e) {
        return render(status: 500, contentType: 'text/plain', data: "Error: ${e.message}")
    }
}

Map getFlowCsv() {
    String fileName = flowCsvFileName ?: "waterFlow.csv"
    try {
        byte[] bytes = safeDownloadHubFile(fileName)
        String csv = bytes ? new String(bytes, 'UTF-8') : "datetime,duration_s,volume_L\n"
        return render(status: 200, contentType: 'text/csv', data: csv)
    } catch (Exception e) {
        return render(status: 500, contentType: 'text/plain', data: "Error: ${e.message}")
    }
}

// ==================== CSV Logging ====================

private void appendToCsvLog(long timestamp, BigDecimal durationSeconds, BigDecimal coincidentFlow, BigDecimal coincidentLpm,
                             BigDecimal volumeAtStart, BigDecimal volumeAtEnd) {
    String fileName = csvFileName ?: "pumpCycles.csv"
    String dateStr = formatDate(timestamp)
    String csvLine = "${dateStr},${durationSeconds},${coincidentFlow},${coincidentLpm},${volumeAtStart},${volumeAtEnd}\n"

    String existingData = null
    try {
        byte[] byteArray = safeDownloadHubFile(fileName)
        if (byteArray) existingData = new String(byteArray, 'UTF-8')
    } catch (Exception e) {
        logWarn("Could not read existing CSV data: ${e.message}")
    }

    if (existingData == null) {
        existingData = "datetime,duration_s,coincident_flow_L,coincident_lpm,vol_at_start,vol_at_end\n"
    }

    String newData = existingData + csvLine
    safeUploadHubFile(fileName, newData.getBytes('UTF-8'))
    logDebug("Appended pump cycle to ${fileName}")
}

private byte[] safeDownloadHubFile(String fileName) {
    for (int i = 1; i <= 3; i++) {
        try {
            return downloadHubFile(fileName)
        } catch (AccessDeniedException ex) {
            log.warn "Failed to download ${fileName}: ${ex.message}. Retrying (${i} / 3) ..."
            pauseExecution(500)
        }
    }
    log.error "Failed to download ${fileName} after 3 attempts"
    return null
}

private void safeUploadHubFile(String fileName, byte[] bytes) {
    for (int i = 1; i <= 3; i++) {
        try {
            uploadHubFile(fileName, bytes)
            return
        } catch (AccessDeniedException ex) {
            log.warn "Failed to upload ${fileName}: ${ex.message}. Retrying (${i} / 3) ..."
            pauseExecution(500)
        }
    }
    log.error "Failed to upload ${fileName} after 3 attempts - possible data loss"
}

// ==================== Status Display ====================

private String getStatusText() {
    StringBuilder status = new StringBuilder()

    // Pump state
    if (state.pumpRunning) {
        status.append("<b style='color:red'>PUMP RUNNING</b>")
        if (state.pumpBegin) {
            BigDecimal elapsed = (now() - (state.pumpBegin as long)) / 1000.0
            status.append(" (${fmtDec(elapsed)}s)")
        }
        status.append("<br/>")
    } else {
        status.append("<b style='color:green'>PUMP IDLE</b><br/>")
    }

    // Flow state
    if (enableFlowTracking != false) {
        if (state.flowActive) {
            status.append("<b style='color:blue'>WATER FLOWING</b><br/>")
        } else {
            status.append("<b style='color:gray'>NO FLOW</b><br/>")
        }
    }

    // Last pump run details
    if (state.lastRunDuration && (state.lastRunDuration as BigDecimal) > 0) {
        List<Map> history = state.cycleHistory as List<Map>
        String tankInfo = ""
        if (history?.size() >= 2) {
            String tu = deriveTankUsage(history[0] as Map, history[1] as Map)
            if (tu != "—") tankInfo = " (tank: ${tu})"
        }
        status.append("<br/><b>Last Pump Run:</b> ${fmtDec(state.lastRunDuration)}s, coincident flow ${fmtVol(state.lastRunCoincidentFlow)}L @ ${fmtDec(state.lastRunCoincidentLpm)} LPM${tankInfo}<br/>")
    }

    // Last flow event details
    if (enableFlowTracking && state.lastFlowVolume && (state.lastFlowVolume as BigDecimal) > 0) {
        status.append("<b>Last Flow:</b> ${fmtVol(state.lastFlowVolume)}L in ${fmtDec(state.lastFlowDuration)}s<br/>")
    }

    // Statistics
    if (state.pumpTimeMax && (state.pumpTimeMax as long) > 0) {
        status.append("<br/><b>Statistics:</b><br/>")
        status.append("Longest: ${fmtDec((state.pumpTimeMax as long) / 1000.0)}s (${state.pumpTimeMaxDate})<br/>")
        status.append("Shortest: ${fmtDec((state.pumpTimeMin as long) / 1000.0)}s (${state.pumpTimeMinDate})<br/>")
        if (state.totalFlowVolume) {
            status.append("Total consumption: ${fmtVol(state.totalFlowVolume)}L<br/>")
        }
        if (state.totalTankUsage && (state.totalTankUsage as BigDecimal) > 0) {
            status.append("Total tank usage: ${fmtVol(state.totalTankUsage)}L<br/>")
        }
        if (state.totalCoincidentFlow && (state.totalCoincidentFlow as BigDecimal) > 0) {
            status.append("<small>Total coincident flow (during pump cycles): ${fmtVol(state.totalCoincidentFlow)}L</small><br/>")
        }
    }

    // Device info
    if (pumpSwitch) {
        Object rawPower = pumpSwitch.currentValue("power")
        String powerLabel = rawPower != null ? "${rawPower as BigDecimal}W" : "—"
        status.append("<br/><small>Pump: ${pumpSwitch.displayName} (${powerLabel})</small><br/>")
    }
    if (waterMeter) {
        Object currentVolume = waterMeter.currentValue(waterMeterAttribute ?: "volume")
        status.append("<small>Meter: ${waterMeter.displayName} (${currentVolume}L)</small><br/>")
    }

    return status.toString()
}

// ==================== Device Helpers ====================

private List<String> getDeviceAttributes(DeviceWrapper device) {
    if (!device) return []
    List<String> attributes = device.getSupportedAttributes().collect { it.name }
    return attributes.unique().sort() ?: ["No supported attributes found"]
}

// ==================== Notifications ====================

private void sendNotification(String message) {
    if (notificationDevices) {
        notificationDevices.each { device ->
            device.deviceNotification(message)
        }
        logInfo("Notification sent: ${message}")
    } else {
        logWarn("No notification device configured. Message: ${message}")
    }
}

// ==================== Utilities ====================

/**
 * Derive tank usage (between-cycle consumption) from adjacent history entries.
 * History is newest-first, so prevEntry is the chronologically earlier cycle.
 * Returns formatted string or "—" if not computable.
 */
private String deriveTankUsage(Map currentEntry, Map prevEntry) {
    if (prevEntry == null || currentEntry.volumeAtStart == null || prevEntry.volumeAtEnd == null) {
        return "—"
    }
    BigDecimal gap = (currentEntry.volumeAtStart as BigDecimal) - (prevEntry.volumeAtEnd as BigDecimal)
    return gap >= 0 ? "${fmtVol(gap)}L" : "—"
}

private String formatDate(long timestamp) {
    return new Date(timestamp).format("yyyy-MM-dd HH:mm:ss", location.timeZone)
}

@CompileStatic
private String fmtVol(Number value) {
    return ((value ?: 0) as BigDecimal).setScale(1, BigDecimal.ROUND_HALF_UP).toString()
}

@CompileStatic
private String fmtDec(Number value) {
    return ((value ?: 0) as BigDecimal).setScale(1, BigDecimal.ROUND_HALF_UP).toString()
}

// ==================== Logging ====================

private void logDebug(String msg) {
    if (logLevel == "debug") log.debug "${app.getLabel() ?: APP_NAME}: ${msg}"
}

private void logInfo(String msg) {
    if (logLevel in ["info", "debug"]) log.info "${app.getLabel() ?: APP_NAME}: ${msg}"
}

private void logWarn(String msg) {
    log.warn "${app.getLabel() ?: APP_NAME}: ${msg}"
}

private void logError(String msg) {
    log.error "${app.getLabel() ?: APP_NAME}: ${msg}"
}

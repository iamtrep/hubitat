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

 Well Pump Monitor

 Monitors a well pump via power metering on its switch, tracks water consumption
 using a flow meter, logs pump cycles to CSV, and provides emergency shutoff
 protection. Replaces a complex Rule Machine automation with a configurable app.

 */

import groovy.transform.CompileStatic
import groovy.transform.Field
import com.hubitat.app.DeviceWrapper
import com.hubitat.hub.domain.Event
import java.nio.file.AccessDeniedException

@Field static final String APP_NAME = "Well Pump Monitor"
@Field static final String APP_VERSION = "0.1.0"

definition(
    name: APP_NAME,
    namespace: "iamtrep",
    author: "pj",
    description: "Monitors well pump cycles, tracks water consumption, and provides emergency shutoff protection",
    category: "Utility",
    iconUrl: "",
    iconX2Url: "",
    importUrl: "https://raw.githubusercontent.com/iamtrep/hubitat/refs/heads/main/apps/WellPumpMonitor.groovy",
    singleThreaded: true
)

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

        section("Flow Alerts", hideable: true, hidden: true) {
            input "enableFlowAlerts", "bool",
                title: "Enable no-flow/low-flow alerts",
                description: "Notify when pump runs longer than expected for the observed flow rate",
                defaultValue: true, required: false
            input "baseAlertTimeSeconds", "number",
                title: "Base alert time (seconds)",
                description: "Minimum pump run time before alerting when no flow is detected",
                defaultValue: 40, required: true, range: "5..600"
            input "flowThreshold1", "number",
                title: "Flow threshold 1 (LPM)",
                defaultValue: 10, required: true
            input "flowThreshold2", "number",
                title: "Flow threshold 2 (LPM)",
                defaultValue: 20, required: true
            input "flowThreshold3", "number",
                title: "Flow threshold 3 (LPM)",
                defaultValue: 30, required: true
            input "flowBonusSeconds", "number",
                title: "Seconds added per threshold exceeded",
                description: "For each flow threshold exceeded, add this many seconds to the alert time",
                defaultValue: 10, required: true, range: "1..120"
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
                    "<th ${tdR}>Volume</th><th ${tdR}>Avg Flow</th><th ${tdR}>Tank Usage</th>" +
                    "</tr></thead><tbody>"
                int count = Math.min(cycleHistory.size(), 10)
                for (int i = 0; i < count; i++) {
                    Map entry = cycleHistory[i] as Map
                    String tankUsage = deriveTankUsage(entry, i + 1 < cycleHistory.size() ? cycleHistory[i + 1] as Map : null)
                    table += "<tr>" +
                        "<td ${td}>${entry.date}</td>" +
                        "<td ${tdR}>${fmtDec(entry.durationS)}s</td>" +
                        "<td ${tdR}>${fmtVol(entry.volumeL)}L</td>" +
                        "<td ${tdR}>${fmtDec(entry.avgLpm)} LPM</td>" +
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
            if (state.pumpTimeMax && (state.pumpTimeMax as long) > 0) {
                stats.append("Longest run: <b>${fmtDec((state.pumpTimeMax as long) / 1000.0)}s</b> on ${state.pumpTimeMaxDate}<br/>")
            }
            if (state.pumpTimeMin && (state.pumpTimeMin as long) > 0) {
                stats.append("Shortest run: <b>${fmtDec((state.pumpTimeMin as long) / 1000.0)}s</b> on ${state.pumpTimeMinDate}<br/>")
            }
            if (state.totalVolume) {
                stats.append("Total volume tracked: <b>${fmtVol(state.totalVolume)}L</b><br/>")
            }
            if (state.totalTankUsage && (state.totalTankUsage as BigDecimal) > 0) {
                stats.append("Total tank usage: <b>${fmtVol(state.totalTankUsage)}L</b><br/>")
            }
            stats.append("Total cycles recorded: <b>${cycleHistory.size()}</b>")
            paragraph stats.toString()
        }

        section("All Cycles") {
            String td = "style='border:1px solid #999;padding:4px 8px'"
            String tdR = "style='border:1px solid #999;padding:4px 8px;text-align:right'"
            String table = "<table style='border-collapse:collapse;width:100%'>" +
                "<thead><tr style='background:#ddd'>" +
                "<th ${td}>Date/Time</th><th ${tdR}>Duration</th>" +
                "<th ${tdR}>Volume</th><th ${tdR}>Avg Flow</th><th ${tdR}>Tank Usage</th>" +
                "</tr></thead><tbody>"
            for (int i = 0; i < cycleHistory.size(); i++) {
                Map e = cycleHistory[i] as Map
                String tankUsage = deriveTankUsage(e, i + 1 < cycleHistory.size() ? cycleHistory[i + 1] as Map : null)
                table += "<tr>" +
                    "<td ${td}>${e.date}</td>" +
                    "<td ${tdR}>${fmtDec(e.durationS)}s</td>" +
                    "<td ${tdR}>${fmtVol(e.volumeL)}L</td>" +
                    "<td ${tdR}>${fmtDec(e.avgLpm)} LPM</td>" +
                    "<td ${tdR}>${tankUsage}</td>" +
                    "</tr>"
            }
            table += "</tbody></table>"
            paragraph table
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
            if (state.totalFlowVolume) {
                stats.append("Total volume tracked: <b>${fmtVol(state.totalFlowVolume)}L</b><br/>")
            }
            stats.append("Total flow events recorded: <b>${flowHistory.size()}</b>")
            paragraph stats.toString()
        }

        section("All Flow Events") {
            String td = "style='border:1px solid #999;padding:4px 8px'"
            String tdR = "style='border:1px solid #999;padding:4px 8px;text-align:right'"
            String table = "<table style='border-collapse:collapse;width:100%'>" +
                "<thead><tr style='background:#ddd'>" +
                "<th ${td}>Date/Time</th><th ${tdR}>Duration</th>" +
                "<th ${tdR}>Volume</th>" +
                "</tr></thead><tbody>"
            flowHistory.each { entry ->
                Map e = entry as Map
                table += "<tr>" +
                    "<td ${td}>${e.date}</td>" +
                    "<td ${tdR}>${fmtDec(e.durationS)}s</td>" +
                    "<td ${tdR}>${fmtVol(e.volumeL)}L</td>" +
                    "</tr>"
            }
            table += "</tbody></table>"
            paragraph table
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

    // Initialize state variables if not set
    if (state.previousPower == null) state.previousPower = 0
    if (state.pumpBegin == null) state.pumpBegin = 0
    if (state.volumeBegin == null) state.volumeBegin = 0.0
    if (state.pumpRunning == null) state.pumpRunning = false
    if (state.pumpCurrentDuration == null) state.pumpCurrentDuration = 0
    if (state.lastRunDuration == null) state.lastRunDuration = 0
    if (state.lastRunVolume == null) state.lastRunVolume = 0.0
    if (state.lastRunAvgLpm == null) state.lastRunAvgLpm = 0.0
    if (state.pumpTimeMax == null) state.pumpTimeMax = 0
    if (state.pumpTimeMaxDate == null) state.pumpTimeMaxDate = ""
    if (state.pumpTimeMin == null) state.pumpTimeMin = 0
    if (state.pumpTimeMinDate == null) state.pumpTimeMinDate = ""
    if (state.totalVolume == null) state.totalVolume = 0.0
    if (state.totalTankUsage == null) state.totalTankUsage = 0.0
    // state.lastCycleVolumeAtEnd: raw meter reading at end of previous cycle (null until first cycle completes)
    if (state.cycleHistory == null) state.cycleHistory = []

    // Flow tracking state
    if (state.flowActive == null) state.flowActive = false
    if (state.flowBeginTime == null) state.flowBeginTime = 0
    if (state.flowVolumeBegin == null) state.flowVolumeBegin = 0.0
    if (state.lastFlowDuration == null) state.lastFlowDuration = 0.0
    if (state.lastFlowVolume == null) state.lastFlowVolume = 0.0
    if (state.flowHistory == null) state.flowHistory = []
    if (state.totalFlowVolume == null) state.totalFlowVolume = 0.0

    // Subscribe to power events from pump switch
    subscribe(pumpSwitch, "power", powerHandler)

    // Subscribe to water meter events
    if (waterMeter) {
        subscribe(waterMeter, waterMeterAttribute ?: "volume", volumeHandler)
        if (enableFlowTracking != false) {
            subscribe(waterMeter, "rate", rateHandler)
        }
    }

    // Hub reboot recovery: if pump was running, check current state
    if (state.pumpRunning) {
        BigDecimal currentPower = pumpSwitch.currentValue("power") as BigDecimal
        int onThreshold = (powerOnThreshold ?: 100) as int
        if (currentPower > onThreshold) {
            // Pump is still running - reschedule emergency timer for remaining time
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
                    return
                }
            }
        } else {
            // Pump stopped while hub was down - we missed the stop event
            logWarn("Pump was running before restart but is now off (${currentPower}W). Cycle data lost.")
            state.pumpRunning = false
            if (pumpActiveSwitch) pumpActiveSwitch.off()
        }
    }

    log.info("${APP_NAME} initialized. Pump running: ${state.pumpRunning}")
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
    BigDecimal volumeDuringRun = volumeEnd - volumeBegin
    if (volumeDuringRun < 0) volumeDuringRun = 0.0

    BigDecimal avgLpm = 0.0
    if (durationMs > 0 && volumeDuringRun > 0) {
        avgLpm = (volumeDuringRun * 60000.0) / durationMs
    }

    // Store last run stats (raw values - format at display time only)
    state.lastRunDuration = durationSeconds
    state.lastRunVolume = volumeDuringRun
    state.lastRunAvgLpm = avgLpm
    state.totalVolume = ((state.totalVolume ?: 0.0) as BigDecimal) + volumeDuringRun
    state.lastCycleVolumeAtEnd = volumeEnd

    logInfo("Pump stopped: ${fmtDec(durationSeconds)}s, ${fmtVol(volumeDuringRun)}L, ${fmtDec(avgLpm)} LPM")

    // Append to CSV log
    if (enableCsvLogging != false) {
        appendToCsvLog(currentTime, durationSeconds, volumeDuringRun, avgLpm, volumeBegin, volumeEnd)
    }

    // Add to cycle history (newest first, capped at 100)
    String dateStr = formatDate(currentTime)
    List<Map> history = (state.cycleHistory ?: []) as List<Map>
    history.add(0, [
        date: dateStr,
        durationS: durationSeconds,
        volumeL: volumeDuringRun,
        avgLpm: avgLpm,
        volumeAtStart: volumeBegin,
        volumeAtEnd: volumeEnd
    ])
    if (history.size() > 100) {
        history = history.subList(0, 100)
    }
    state.cycleHistory = history

    // Evaluate flow alert
    evaluateFlowAlert(durationMs, avgLpm)

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

        logInfo("Water flow stopped: ${fmtVol(volumeDelivered)}L in ${fmtDec(durationSeconds)}s")

        // Append to flow CSV
        if (enableCsvLogging != false) {
            appendToFlowCsvLog(flowEnd, durationSeconds, volumeDelivered)
        }

        // Add to flow history (newest first, capped at 100)
        String dateStr = formatDate(flowEnd)
        List<Map> history = (state.flowHistory ?: []) as List<Map>
        history.add(0, [
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
        if (byteArray) existingData = new String(byteArray)
    } catch (Exception e) {
        logWarn("Could not read existing flow CSV data: ${e.message}")
    }

    if (existingData == null) {
        existingData = "datetime,duration_s,volume_L\n"
    }

    String newData = existingData + csvLine
    safeUploadHubFile(fileName, newData.bytes)
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

private void evaluateFlowAlert(long durationMs, BigDecimal avgLpm) {
    if (!enableFlowAlerts) return

    int baseMs = ((baseAlertTimeSeconds ?: 40) as int) * 1000
    int bonusMs = ((flowBonusSeconds ?: 10) as int) * 1000

    int dynamicLimitMs = baseMs
    if (avgLpm > ((flowThreshold1 ?: 10) as BigDecimal)) dynamicLimitMs += bonusMs
    if (avgLpm > ((flowThreshold2 ?: 20) as BigDecimal)) dynamicLimitMs += bonusMs
    if (avgLpm > ((flowThreshold3 ?: 30) as BigDecimal)) dynamicLimitMs += bonusMs

    if (durationMs > dynamicLimitMs) {
        BigDecimal limitSeconds = dynamicLimitMs / 1000.0
        BigDecimal durationSeconds = durationMs / 1000.0
        String msg = "Well pump ran for ${fmtDec(durationSeconds)}s (limit: ${fmtDec(limitSeconds)}s) with avg flow ${fmtDec(avgLpm)} LPM - possible no-flow or low-flow condition"
        logWarn(msg)
        sendNotification(msg)
    }
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

// ==================== CSV Logging ====================

private void appendToCsvLog(long timestamp, BigDecimal durationSeconds, BigDecimal volume, BigDecimal avgLpm,
                             BigDecimal volumeAtStart, BigDecimal volumeAtEnd) {
    String fileName = csvFileName ?: "pumpCycles.csv"
    String dateStr = formatDate(timestamp)
    String csvLine = "${dateStr},${durationSeconds},${volume},${avgLpm},${volumeAtStart},${volumeAtEnd}\n"

    String existingData = null
    try {
        byte[] byteArray = safeDownloadHubFile(fileName)
        if (byteArray) existingData = new String(byteArray)
    } catch (Exception e) {
        logWarn("Could not read existing CSV data: ${e.message}")
    }

    if (existingData == null) {
        existingData = "datetime,duration_s,volume_L,avg_lpm,vol_at_start,vol_at_end\n"
    }

    String newData = existingData + csvLine
    safeUploadHubFile(fileName, newData.bytes)
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
        status.append("<br/><b>Last Pump Run:</b> ${fmtDec(state.lastRunDuration)}s, ${fmtVol(state.lastRunVolume)}L, ${fmtDec(state.lastRunAvgLpm)} LPM${tankInfo}<br/>")
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
        if (state.totalVolume) {
            status.append("Total volume: ${fmtVol(state.totalVolume)}L<br/>")
        }
        if (state.totalTankUsage && (state.totalTankUsage as BigDecimal) > 0) {
            status.append("Total tank usage: ${fmtVol(state.totalTankUsage)}L<br/>")
        }
    }

    // Device info
    if (pumpSwitch) {
        BigDecimal currentPower = pumpSwitch.currentValue("power") as BigDecimal
        status.append("<br/><small>Pump: ${pumpSwitch.displayName} (${currentPower}W)</small><br/>")
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
private String fmtVol(Object value) {
    return ((value ?: 0) as BigDecimal).setScale(1, BigDecimal.ROUND_HALF_UP).toString()
}

@CompileStatic
private String fmtDec(Object value) {
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

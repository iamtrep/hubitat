/**
 * Log Event Monitor Driver
 * Monitors system logs via WebSocket and exposes events for automations
 *
 *	Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *	in compliance with the License. You may obtain a copy of the License at:
 *
 *		http://www.apache.org/licenses/LICENSE-2.0
 *
 *	Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *	on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *	for the specific language governing permissions and limitations under the License.
 *
 */

import groovy.json.JsonSlurper
import groovy.transform.Field

@Field static final String DRIVER_VERSION = "1.9.0"
@Field static final int STARTUP_DELAY_SECS = 60
@Field static final JsonSlurper JSON_SLURPER = new JsonSlurper()
@Field static final Map<String, Long> totalLogsReceived = [:].asSynchronized()

metadata {
    definition(
        name: "Log Event Monitor",
        namespace: "iamtrep",
        author: "pj",
        importUrl: "https://raw.githubusercontent.com/iamtrep/hubitat/main/drivers/LogEventMonitor.groovy"
    ) {
        capability "Actuator"
        capability "Initialize"
        capability "Sensor"

        // Main event attribute - fires whenever a matching log entry is found
        attribute "logEvent", "string"
        attribute "connectionStatus", "string"

        // Commands
        command "connect"
        command "disconnect"
        command "reconnect"
        command "clearStats"
    }

    preferences {
        // Connection Settings
        input name: "hubAddress", type: "text",
            title: "Hub IP address (leave blank for local hub)",
            required: false, description: "e.g., 192.168.1.100"
        input name: "autoReconnect", type: "bool", title: "Auto-reconnect on disconnect",
            defaultValue: true
        input name: "pingInterval", type: "number", title: "WebSocket ping interval (seconds)",
            defaultValue: 30, range: "10..300"

        // Log Types to Monitor
        input name: "monitorDevLogs", type: "bool", title: "Device logs", defaultValue: true
        input name: "monitorAppLogs", type: "bool", title: "App logs", defaultValue: true
        input name: "monitorSysLogs", type: "bool", title: "System logs", defaultValue: true

        // Log Levels to Monitor
        input name: "monitorTrace", type: "bool", title: "Trace", defaultValue: false
        input name: "monitorDebug", type: "bool", title: "Debug", defaultValue: false
        input name: "monitorInfo", type: "bool", title: "Info", defaultValue: false
        input name: "monitorWarn", type: "bool", title: "Warning", defaultValue: false
        input name: "monitorError", type: "bool", title: "Error", defaultValue: true

        // Additional Filters
        input name: "monitoredDeviceIds", type: "text",
            title: "Monitor specific device IDs (comma-separated, optional)",
            required: false, description: "e.g., 123,456,789"
        input name: "monitoredAppIds", type: "text",
            title: "Monitor specific app IDs (comma-separated, optional)",
            required: false, description: "e.g., 42,87,154"
        input name: "includePattern", type: "text",
            title: "Include Pattern (regex, optional)",
            required: false, description: "e.g., (?i)offline|battery|failed"
        input name: "excludePattern", type: "text",
            title: "Exclude Pattern (regex, optional)",
            required: false, description: "e.g., (?i)heartbeat|poll"
        input name: "dedupeWindow", type: "number",
            title: "Dedupe window (seconds) - ignore identical messages within this time",
            defaultValue: 5, range: "0..300"

        // Advanced Settings
        input name: "maxEventsPerMinute", type: "number",
            title: "Max events per minute",
            defaultValue: 30, range: "1..60"
        input name: "maxEventHistory", type: "number",
            title: "Max events to remember (for deduplication)",
            defaultValue: 100, range: "10..1000"
        input name: "enableDebug", type: "bool",
            title: "Enable Debug Logging", defaultValue: false
        input name: "enableTrace", type: "bool",
            title: "Enable Trace Logging (very verbose)", defaultValue: false
    }
}

void installed() {
    logDebug "installed()"
    initialize()
}

void updated() {
    logDebug "updated()"
    disconnect()
    unschedule()
    initialize()
}

void uninstalled() {
    logDebug "uninstalled()"
    disconnect()
}

void initialize() {
    logDebug "initialize()"

    state.codeVersion = DRIVER_VERSION

    // Initialize state
    atomicState.intentionalDisconnect = false
    state.processedEvents = state.processedEvents ?: []
    state.reconnectAttempts = 0
    state.eventsMatched = 0
    state.eventsThisMinute = 0
    state.lastMinuteReset = now()
    state.rateLimitWarningShown = false

    // Update connection status
    sendEvent(name: "connectionStatus", value: "initializing")

    // Connect to WebSocket
    runIn(location.hub.uptime < STARTUP_DELAY_SECS ? STARTUP_DELAY_SECS : 2, "connect")

    // Health check every 5 minutes
    runEvery5Minutes("healthCheck")

    // Reset rate limit counter every minute
    runEvery1Minute("resetRateLimitCounter")
}

// ============================================================================
// WebSocket Connection Management
// ============================================================================

void connect() {
    logDebug "Connecting to log event WebSocket..."

    // Cancel any pending scheduled reconnect to avoid duplicate connections
    unschedule("connect")

    try {
        atomicState.intentionalDisconnect = false
        sendEvent(name: "connectionStatus", value: "connecting")

        String host = hubAddress ? "${hubAddress}" : "127.0.0.1:8080"
        String uri = "ws://${host}/logsocket"
        interfaces.webSocket.connect(
            uri,
            pingInterval: (pingInterval ?: 30).toInteger()
        )

        // Connection is async — webSocketStatus() will set connected state
        logDebug "WebSocket connect initiated"
    } catch (Exception e) {
        state.wsConnected = false
        sendEvent(name: "connectionStatus", value: "error")
        logError "WebSocket connection failed: ${e.message}"

        if (autoReconnect) {
            scheduleReconnect()
        }
    }
}

void disconnect() {
    logDebug "Disconnecting WebSocket..."

    atomicState.intentionalDisconnect = true
    unschedule("connect")

    try {
        interfaces.webSocket.close()
        state.wsConnected = false
        sendEvent(name: "connectionStatus", value: "disconnected")
        logInfo "WebSocket disconnected"
    } catch (Exception e) {
        logError "Error disconnecting WebSocket: ${e.message}"
    }
}

void reconnect() {
    logDebug "Manual reconnect triggered"
    disconnect()
    runIn(2, "connect")
}

void scheduleReconnect() {
    int attempts = (state.reconnectAttempts ?: 0) + 1
    state.reconnectAttempts = attempts

    // Exponential backoff: 5s, 10s, 20s, 40s, max 60s
    int delay = Math.min(60, 5 * (2 ** Math.min(attempts - 1, 3)))

    logInfo "Scheduling reconnect in ${delay}s (attempt ${attempts})"
    sendEvent(name: "connectionStatus", value: "reconnecting")

    runIn(delay, "connect")
}

void healthCheck() {
    if (!state.wsConnected && autoReconnect && !atomicState.intentionalDisconnect) {
        logWarn "WebSocket disconnected, attempting reconnect"
        scheduleReconnect()
    }
}

void resetRateLimitCounter() {
    int oldCount = state.eventsThisMinute ?: 0
    state.eventsThisMinute = 0
    state.lastMinuteReset = now()
    state.rateLimitWarningShown = false

    if (oldCount > 0) {
        logDebug "Rate limit counter reset. Previous minute: ${oldCount} events"
    }

    // Snapshot in-memory counter to state for visibility in device UI
    state.totalLogsReceived = totalLogsReceived[device.id.toString()] ?: 0L
}

// ============================================================================
// WebSocket Event Handlers
// ============================================================================

void webSocketStatus(String message) {
    logTrace "WebSocket status: ${message}"

    if (message.contains("failure") || message.contains("error")) {
        state.wsConnected = false
        sendEvent(name: "connectionStatus", value: "error")
        logWarn "WebSocket error: ${message}"

        if (autoReconnect && !atomicState.intentionalDisconnect) {
            scheduleReconnect()
        }
    } else if (message.contains("status: open")) {
        state.wsConnected = true
        state.reconnectAttempts = 0
        state.lastConnectionTime = now()
        sendEvent(name: "connectionStatus", value: "connected")
        logInfo "WebSocket connected"
    } else if (message.contains("status: closing") || message.contains("status: closed")) {
        state.wsConnected = false
        sendEvent(name: "connectionStatus", value: "disconnected")

        if (autoReconnect && !atomicState.intentionalDisconnect) {
            scheduleReconnect()
        }
    }
}

void parse(String message) {
    String devId = device.id.toString()
    totalLogsReceived[devId] = (totalLogsReceived[devId] ?: 0L) + 1

    try {
        Map logEntry = JSON_SLURPER.parseText(message)

        // Validate we got a proper log entry with required fields
        if (!logEntry || !logEntry.type) {
            logDebug "Skipping invalid log entry: ${message?.take(100)}"
            return
        }

        // Check for self-monitoring to prevent infinite loops
        if (logEntry.type == "dev" && logEntry.id?.toString() == devId) {
            return
        }

        logTrace "Rcv: [${logEntry.type}/${logEntry.level}] ${logEntry.name}"

        try {
            processLogEntry(logEntry)
        } catch (Exception e) {
            logDebug "Error in processLogEntry: ${e.class.simpleName}: ${e.message}"
        }
    } catch (NullPointerException e) {
        logDebug "NPE in parse: ${e.message} | Stack: ${e.stackTrace?.take(3)}"
    } catch (Exception e) {
        logDebug "Parse error (${e.class.simpleName}): ${e.message} | Msg: ${message?.take(150)}"
    }
}

// ============================================================================
// Log Processing
// ============================================================================

void processLogEntry(Map logEntry) {
    logTrace "Processing: [${logEntry.type}/${logEntry.level}] ${logEntry.name}"

    // Check log type filter (dev/app/sys)
    boolean typeAllowed = false
    if (logEntry.type == "dev" && monitorDevLogs) typeAllowed = true
    if (logEntry.type == "app" && monitorAppLogs) typeAllowed = true
    if (logEntry.type == "sys" && monitorSysLogs) typeAllowed = true

    if (!typeAllowed) {
        logTrace "Filtered out: type '${logEntry.type}' not enabled"
        return
    }

    // Check log level filter
    boolean levelAllowed = false
    if (logEntry.level == "trace" && monitorTrace) levelAllowed = true
    if (logEntry.level == "debug" && monitorDebug) levelAllowed = true
    if (logEntry.level == "info" && monitorInfo) levelAllowed = true
    if (logEntry.level == "warn" && monitorWarn) levelAllowed = true
    if (logEntry.level == "error" && monitorError) levelAllowed = true

    if (!levelAllowed) {
        logTrace "Filtered out: level '${logEntry.level}' not enabled"
        return
    }

    // Check device/app ID filter (if either is specified, at least one must match)
    if (monitoredDeviceIds || monitoredAppIds) {
        boolean idMatch = false

        // Check device IDs (only if this is a device log)
        if (monitoredDeviceIds && logEntry.type == "dev") {
            List<String> deviceIdList = monitoredDeviceIds.split(',').collect { it.trim() }
            if (deviceIdList.contains(logEntry.id?.toString())) {
                idMatch = true
            }
        }

        // Check app IDs (only if this is an app log)
        if (monitoredAppIds && logEntry.type == "app") {
            List<String> appIdList = monitoredAppIds.split(',').collect { it.trim() }
            if (appIdList.contains(logEntry.id?.toString())) {
                idMatch = true
            }
        }

        if (!idMatch) {
            logTrace "Filtered out: ID '${logEntry.id}' not in monitored device/app lists"
            return
        }
    }

    // Check include pattern
    if (includePattern) {
        try {
            if (!(logEntry.msg =~ includePattern)) {
                logTrace "Filtered out: doesn't match include pattern"
                return
            }
        } catch (Exception e) {
            logError "Invalid include pattern: ${e.message}"
            return
        }
    }

    // Check exclude pattern
    if (excludePattern) {
        try {
            if (logEntry.msg =~ excludePattern) {
                logTrace "Filtered out: matches exclude pattern"
                return
            }
        } catch (Exception e) {
            logError "Invalid exclude pattern: ${e.message}"
            return
        }
    }

    // Check for duplicate within deduplication window
    if (isDuplicate(logEntry)) {
        logTrace "Filtered out: duplicate event"
        return
    }

    // This log entry matches - fire event!
    triggerLogEvent(logEntry)
}

boolean isDuplicate(Map logEntry) {
    if (!dedupeWindow || dedupeWindow == 0) {
        return false
    }

    long ts = now()
    long windowMs = (dedupeWindow ?: 5) * 1000
    String signature = "${logEntry.type}:${logEntry.level}:${logEntry.name}:${logEntry.msg}"

    // Clean old entries
    state.processedEvents = state.processedEvents?.findAll {
        (ts - it.timestamp) < windowMs
    } ?: []

    // Check if we've seen this recently
    Map duplicate = state.processedEvents?.find { it.signature == signature }

    if (!duplicate) {
        // Add to processed list
        state.processedEvents = state.processedEvents + [[
            signature: signature,
            timestamp: ts
        ]]

        // Trim to max size
        int maxHistory = maxEventHistory ?: 100
        if (state.processedEvents.size() > maxHistory) {
            state.processedEvents = state.processedEvents.drop(
                state.processedEvents.size() - maxHistory
            )
        }
    }

    return duplicate != null
}

void triggerLogEvent(Map logEntry) {
    // Check rate limiting
    state.eventsThisMinute = (state.eventsThisMinute ?: 0) + 1

    // Warn at 80% of limit
    int warningThreshold = (maxEventsPerMinute * 0.8).toInteger()
    if (state.eventsThisMinute == warningThreshold && !state.rateLimitWarningShown) {
        logWarn "Approaching event rate limit: ${state.eventsThisMinute}/${maxEventsPerMinute} events this minute. Consider refining filters."
        state.rateLimitWarningShown = true
    }

    // Enforce limit
    if (state.eventsThisMinute > maxEventsPerMinute) {
        logWarn "Event rate limit exceeded (${maxEventsPerMinute}/min). Event suppressed: [${logEntry.type}/${logEntry.level}] ${logEntry.name}"
        return
    }

    state.eventsMatched = (state.eventsMatched ?: 0) + 1

    logInfo "Log event matched [${state.eventsMatched}]: [${logEntry.type}/${logEntry.level}] ${logEntry.name}: ${logEntry.msg}"

    // Send the main event
    sendEvent(
        name: "logEvent",
        value: logEntry.id?.toString() ?: "unknown",
        unit: logEntry.type,
        descriptionText: "${logEntry.name}: ${logEntry.msg}",
        isStateChange: true
    )
}

// ============================================================================
// Commands
// ============================================================================

void clearStats() {
    state.eventsMatched = 0
    state.processedEvents = []
    state.eventsThisMinute = 0
    state.rateLimitWarningShown = false
    totalLogsReceived[device.id.toString()] = 0L
    logInfo "Statistics cleared"
}

// ============================================================================
// Logging
// ============================================================================

void logDebug(String msg) {
    if (enableDebug) {
        log.debug "${device.displayName}: ${msg}"
    }
}

void logTrace(String msg) {
    if (enableTrace) {
        log.trace "${device.displayName}: ${msg}"
    }
}

void logInfo(String msg) {
    log.info "${device.displayName}: ${msg}"
}

void logWarn(String msg) {
    log.warn "${device.displayName}: ${msg}"
}

void logError(String msg) {
    log.error "${device.displayName}: ${msg}"
}

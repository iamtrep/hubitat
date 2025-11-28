/**
 * Log Event Monitor Driver
 * Monitors system logs via WebSocket and exposes events for automations
 *
 * Author: PJ
 * Version: 1.6.0
 */

import groovy.json.JsonSlurper

metadata {
    definition(
        name: "Log Event Monitor",
        namespace: "pj",
        author: "PJ"
    ) {
        capability "Actuator"
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
        input name: "monitorWarn", type: "bool", title: "Warning", defaultValue: true
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
        input name: "preventSelfMonitoring", type: "bool",
            title: "Prevent monitoring own log messages (recommended)",
            defaultValue: true

        // Advanced Settings
        input name: "maxEventHistory", type: "number",
            title: "Max events to remember (for deduplication)",
            defaultValue: 100, range: "10..1000"
        input name: "enableDebug", type: "bool",
            title: "Enable Debug Logging", defaultValue: false
        input name: "enableTrace", type: "bool",
            title: "Enable Trace Logging (very verbose)", defaultValue: false
    }
}

def installed() {
    logDebug "installed()"
    initialize()
}

def updated() {
    logDebug "updated()"
    disconnect()
    unschedule()
    initialize()
}

def uninstalled() {
    logDebug "uninstalled()"
    disconnect()
}

def initialize() {
    logDebug "initialize()"

    // Initialize state
    state.processedEvents = state.processedEvents ?: []
    state.reconnectAttempts = 0
    state.eventsMatched = 0
    state.totalLogsReceived = 0

    // Update connection status
    sendEvent(name: "connectionStatus", value: "initializing")

    // Connect to WebSocket
    runIn(2, "connect")

    // Health check every 5 minutes
    runEvery5Minutes("healthCheck")
}

// ============================================================================
// WebSocket Connection Management
// ============================================================================

def connect() {
    logDebug "Connecting to log event WebSocket..."

    try {
        def uri = "ws://127.0.0.1:8080/logsocket"
        interfaces.webSocket.connect(
            uri,
            pingInterval: (pingInterval ?: 30).toInteger()
        )

        state.wsConnected = true
        state.reconnectAttempts = 0
        state.lastConnectionTime = now()

        sendEvent(name: "connectionStatus", value: "connected")
        logInfo "WebSocket connected successfully"
    } catch (e) {
        state.wsConnected = false
        sendEvent(name: "connectionStatus", value: "error")
        logError "WebSocket connection failed: ${e.message}"

        if (autoReconnect) {
            scheduleReconnect()
        }
    }
}

def disconnect() {
    logDebug "Disconnecting WebSocket..."

    try {
        interfaces.webSocket.close()
        state.wsConnected = false
        sendEvent(name: "connectionStatus", value: "disconnected")
        logInfo "WebSocket disconnected"
    } catch (e) {
        logError "Error disconnecting WebSocket: ${e.message}"
    }
}

def reconnect() {
    logDebug "Manual reconnect triggered"
    disconnect()
    runIn(2, "connect")
}

def scheduleReconnect() {
    state.reconnectAttempts = (state.reconnectAttempts ?: 0) + 1

    // Exponential backoff: 5s, 10s, 20s, 40s, max 60s
    def delay = Math.min(60, 5 * (2 ** Math.min(state.reconnectAttempts - 1, 3)))

    logInfo "Scheduling reconnect in ${delay}s (attempt ${state.reconnectAttempts})"
    sendEvent(name: "connectionStatus", value: "reconnecting")

    runIn(delay, "connect")
}

def healthCheck() {
    if (!state.wsConnected && autoReconnect) {
        logWarn "WebSocket disconnected, attempting reconnect"
        connect()
    }
}

// ============================================================================
// WebSocket Event Handlers
// ============================================================================

def webSocketStatus(String message) {
    logTrace "WebSocket status: ${message}"

    if (message.contains("failure") || message.contains("error")) {
        state.wsConnected = false
        sendEvent(name: "connectionStatus", value: "error")
        logWarn "WebSocket error: ${message}"

        if (autoReconnect) {
            scheduleReconnect()
        }
    } else if (message.contains("status: open")) {
        state.wsConnected = true
        state.reconnectAttempts = 0
        sendEvent(name: "connectionStatus", value: "connected")
        logInfo "WebSocket opened"
    } else if (message.contains("status: closing") || message.contains("status: closed")) {
        state.wsConnected = false
        sendEvent(name: "connectionStatus", value: "disconnected")

        if (autoReconnect) {
            scheduleReconnect()
        }
    }
}

def parse(String message) {
    // Called when WebSocket receives data

    // Increment total logs received counter (silently, state only)
    state.totalLogsReceived = (state.totalLogsReceived ?: 0) + 1

    try {
        // Use JsonSlurper instead of parseJson
        def slurper = new JsonSlurper()
        def logEntry = slurper.parseText(message)

        // Validate we got a proper log entry with required fields
        if (!logEntry || !logEntry.type) {
            logDebug "Skipping invalid log entry: ${message?.take(100)}"
            return
        }

        // FIRST: Check for self-monitoring BEFORE any logging to prevent infinite loops
        // Compare device ID and type (only filter our own device logs, not apps with same name)
        if (preventSelfMonitoring && logEntry.type == "dev") {
            if (logEntry.id?.toString() == device.id?.toString()) {
                return  // Exit silently, this is from us
            }
        }

        // NOW safe to log (but only trace, and truncated to prevent loops)
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

def processLogEntry(Map logEntry) {
    // logEntry structure: [id, time, level, type, name, msg]
    // Note: self-monitoring check already done in parse() before this is called

    logTrace "Processing: [${logEntry.type}/${logEntry.level}] ${logEntry.name}"

    // Check log type filter (dev/app/sys)
    def typeAllowed = false
    if (logEntry.type == "dev" && monitorDevLogs) typeAllowed = true
    if (logEntry.type == "app" && monitorAppLogs) typeAllowed = true
    if (logEntry.type == "sys" && monitorSysLogs) typeAllowed = true

    if (!typeAllowed) {
        logTrace "Filtered out: type '${logEntry.type}' not enabled"
        return
    }

    // Check log level filter
    def levelAllowed = false
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
        def idMatch = false

        // Check device IDs (only if this is a device log)
        if (monitoredDeviceIds && logEntry.type == "dev") {
            def deviceIdList = monitoredDeviceIds.split(',').collect { it.trim() }
            if (deviceIdList.contains(logEntry.id?.toString())) {
                idMatch = true
            }
        }

        // Check app IDs (only if this is an app log)
        if (monitoredAppIds && logEntry.type == "app") {
            def appIdList = monitoredAppIds.split(',').collect { it.trim() }
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
        } catch (e) {
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
        } catch (e) {
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

def isDuplicate(Map logEntry) {
    if (!dedupeWindow || dedupeWindow == 0) {
        return false
    }

    def now = now()
    def windowMs = (dedupeWindow ?: 5) * 1000
    def signature = "${logEntry.type}:${logEntry.level}:${logEntry.name}:${logEntry.msg}"

    // Clean old entries
    state.processedEvents = state.processedEvents?.findAll {
        (now - it.timestamp) < windowMs
    } ?: []

    // Check if we've seen this recently
    def duplicate = state.processedEvents?.find { it.signature == signature }

    if (!duplicate) {
        // Add to processed list
        state.processedEvents << [
            signature: signature,
            timestamp: now
        ]

        // Trim to max size
        if (state.processedEvents.size() > (maxEventHistory ?: 100)) {
            state.processedEvents = state.processedEvents.drop(
                state.processedEvents.size() - maxEventHistory
            )
        }
    }

    return duplicate != null
}

def triggerLogEvent(Map logEntry) {
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

def clearStats() {
    state.eventsMatched = 0
    state.totalLogsReceived = 0
    state.processedEvents = []
    logInfo "Statistics cleared"
}

// ============================================================================
// Logging
// ============================================================================

def logDebug(msg) {
    if (enableDebug) {
        log.debug "${device.displayName}: ${msg}"
    }
}

def logTrace(msg) {
    if (enableTrace) {
        log.trace "${device.displayName}: ${msg}"
    }
}

def logInfo(msg) {
    log.info "${device.displayName}: ${msg}"
}

def logWarn(msg) {
    log.warn "${device.displayName}: ${msg}"
}

def logError(msg) {
    log.error "${device.displayName}: ${msg}"
}

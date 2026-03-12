/**
 * Log Monitor Bridge — WebSocket Bridge Driver
 *
 * Connects to the hub's logsocket and forwards every log entry to its
 * parent app (Log Monitor) via parent.processLogEntry(). No filtering
 * or processing — purely a transport bridge.
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

@Field static final String DRIVER_VERSION = "1.0.0"
@Field static final int STARTUP_DELAY_SECS = 60
@Field static final JsonSlurper JSON_SLURPER = new JsonSlurper()

metadata {
    definition(
        name: "Log Monitor Bridge",
        namespace: "iamtrep",
        author: "pj",
        importUrl: "https://raw.githubusercontent.com/iamtrep/hubitat/main/apps/LogMonitor/LogMonitorBridge.groovy"
    ) {
        capability "Actuator"
        capability "Initialize"

        attribute "connectionStatus", "string"

        command "connect"
        command "disconnect"
        command "reconnect"
        command "getLogsReceivedCount"
    }

    preferences {
        input name: "hubAddress", type: "text",
            title: "Hub IP address (leave blank for local hub)",
            required: false, description: "e.g., 192.168.1.100"
        input name: "autoReconnect", type: "bool",
            title: "Auto-reconnect on disconnect", defaultValue: true
        input name: "pingInterval", type: "number",
            title: "WebSocket ping interval (seconds)",
            defaultValue: 30, range: "10..300"
        input name: "enableDebug", type: "bool",
            title: "Enable debug logging", defaultValue: false
        input name: "enableTrace", type: "bool",
            title: "Enable trace logging (very verbose)", defaultValue: false
    }
}

// ============================================================================
// Lifecycle
// ============================================================================

void installed() {
    logDebug "installed()"
    state.codeVersion = DRIVER_VERSION
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

    atomicState.intentionalDisconnect = false
    state.reconnectAttempts = 0
    atomicState.logsReceived = 0

    sendEvent(name: "connectionStatus", value: "initializing")

    runIn(location.hub.uptime < STARTUP_DELAY_SECS ? STARTUP_DELAY_SECS : 2, "connect")
}

// ============================================================================
// WebSocket Connection Management
// ============================================================================

void connect() {
    logDebug "Connecting to logsocket..."
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

private void scheduleReconnect() {
    int attempts = (state.reconnectAttempts ?: 0) + 1
    state.reconnectAttempts = attempts

    int delay = Math.min(60, 5 * (2 ** Math.min(attempts - 1, 3)))
    logInfo "Scheduling reconnect in ${delay}s (attempt ${attempts})"
    sendEvent(name: "connectionStatus", value: "reconnecting")
    runIn(delay, "connect")
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
    atomicState.logsReceived = (atomicState.logsReceived ?: 0) + 1

    try {
        Map logEntry = JSON_SLURPER.parseText(message)

        if (!logEntry?.type) return

        // Self-monitoring guard: skip own device logs
        if (logEntry.type == "dev" && logEntry.id?.toString() == device.id.toString()) return

        // Unescape HTML entities at the source — logsocket sends HTML-encoded text.
        // Remote hub connections via port 80 often double-encode entities (e.g. &amp;quot;).
        if (logEntry.msg) {
            String msg = org.apache.commons.lang3.StringEscapeUtils.unescapeHtml4(logEntry.msg as String)
            if (msg.contains("&")) {
                msg = org.apache.commons.lang3.StringEscapeUtils.unescapeHtml4(msg)
            }
            logEntry.msg = msg
        }
        if (logEntry.name) {
            String name = org.apache.commons.lang3.StringEscapeUtils.unescapeHtml4(logEntry.name as String)
            if (name.contains("&")) {
                name = org.apache.commons.lang3.StringEscapeUtils.unescapeHtml4(name)
            }
            logEntry.name = name
        }

        logTrace "Rcv: [${logEntry.type}/${logEntry.level}] ${logEntry.name}"

        parent?.processLogEntry(device.deviceNetworkId, logEntry)
    } catch (Exception e) {
        logDebug "Parse error: ${e.message}"
    }
}

/**
 * Returns the total count of logs received by this bridge.
 * Can be called by the parent app to display status without attribute overhead.
 */
long getLogsReceivedCount() {
    return (atomicState.logsReceived ?: 0) as long
}

// ============================================================================
// Logging
// ============================================================================

private void logDebug(String msg) {
    if (enableDebug) log.debug "${device.displayName}: ${msg}"
}

private void logTrace(String msg) {
    if (enableTrace) log.trace "${device.displayName}: ${msg}"
}

private void logInfo(String msg) {
    log.info "${device.displayName}: ${msg}"
}

private void logWarn(String msg) {
    log.warn "${device.displayName}: ${msg}"
}

private void logError(String msg) {
    log.error "${device.displayName}: ${msg}"
}

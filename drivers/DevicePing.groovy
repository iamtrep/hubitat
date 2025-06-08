/*

 MIT License

 Copyright (c) 2025

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

 Hubitat Elevation driver to ping devices

 */

metadata {
    definition (
        name: "Device Ping",
        namespace: "iamtrep",
        author: "pj",
        importUrl: "https://raw.githubusercontent.com/iamtrep/hubitat/refs/heads/main/drivers/DevicePing.groovy"
    )
    {
        capability "ContactSensor"
        capability "Initialize"
        capability "Refresh"
        capability "Sensor"

        command "ping"
        command "resetRetryCount"
        command "setRetryThreshold", ["number"]

        attribute "status", "enum", ["online", "offline"]
        attribute "pingStatus", "enum", ["success", "failed"]
        attribute "httpStatus", "enum", ["success", "failed"]
    }

    preferences {
        input "deviceIP", "string", title: "Device IP Address (Optional)", description: "Enter the IP address of the device to monitor (leave blank to use HTTP GET only)", required: false
        input "httpURL", "string", title: "HTTP URL (Optional)", description: "Enter a full URL for HTTP GET (leave blank to use ICMP ping only)", required: false
        input "pingInterval", "number", title: "Ping Interval (minutes)", description: "How often to check if device is online", defaultValue: 5, required: true
        input "retryInterval", "number", title: "Initial Retry Interval (seconds)", description: "How long to wait before first retry after a failed ping", defaultValue: 30, required: true
        input "maxRetries", "number", title: "Maximum Retry Count", description: "Maximum number of retry attempts before backing off to regular schedule", defaultValue: 5, required: true
        input "maxBackoffFactor", "number", title: "Maximum Backoff Multiplier", description: "Maximum multiplier for retry interval (prevents extremely long waits)", defaultValue: 10, required: true
        input "retryThreshold", "number", title: "Retry Threshold for Status Update", description: "Number of retries before updating status to offline", defaultValue: 3, required: true

        input name: "txtEnable", type: "bool", title: "Enable descriptionText logging", defaultValue: false
        input name: "debugEnable", type: "bool", title: "Enable debug logging info", defaultValue: false, required: true, submitOnChange: true
        if (debugEnable) {
            input name: "traceEnable", type: "bool", title: "Enable trace logging info (for development purposes)", defaultValue: false
        }
    }
}

import hubitat.helper.NetworkUtils
import groovy.transform.Field

@Field static final String driver_version = "0.0.1"

void installed() {
    logDebug "Installed with settings: ${settings}"
    state.isPinging = false
    state.currentRetryCount = 0
    state.lastCheckin = null
    state.version = driver_version
    state.retryThreshold = settings.retryThreshold ?: 3
    initialize()
}

void updated() {
    logDebug "Updated with settings: ${settings}"
    if (atomicState.isPinging == null) atomicState.isPinging = false
    if (state.currentRetryCount == null) state.currentRetryCount = 0
    if (state.lastCheckin == null) state.lastCheckin = null
    if (state.retryThreshold == null) state.retryThreshold = settings.retryThreshold ?: 3
    initialize()
}

void initialize() {
    logDebug("initialize()")
    if (state.version != driver_version) {
        log.warn("New driver version installed ${driver_version} (previous: ${state.version})")
        state.version = driver_version
    }

    reschedulePing(true)

    if (debugEnable) runIn(1800, "logsOff")
}

void refresh() {
    logDebug "refresh() - manually refreshing status"
    ping()
}

void reschedulePing(init = false) {
    unschedule("ping")

    if (deviceIP || httpURL) {
        // Schedule regular ping based on user preference
        if (pingInterval) {
            schedule("0 */${pingInterval} * ? * *", "ping")
        }

        // Do an initial ping
        if (init) runIn(2, "ping")
    } else {
        log.warn "No device IP or HTTP URL specified. Pings will not be scheduled."
    }
}

void logsOff() {
    logDebug("Debug logging turned off")
    device.updateSetting("debugEnable", false)
    device.updateSetting("traceEnable", false)
}

def parse(String description) {
    logDebug "parse: ${description}"
}

void ping() {
    // Prevent multiple concurrent pings
    if (atomicState.isPinging) {
        logDebug "Ping already in progress, skipping"
        return
    }

    // Ensure at least one of deviceIP or httpURL is set before attempting a ping
    if (!deviceIP && !httpURL) {
        log.warn "No device IP or HTTP URL specified. Ping aborted."
        return
    }

    atomicState.isPinging = true

    def pingSuccess = true
    def httpSuccess = true

    if (deviceIP) {
        pingSuccess = sendPingRequest()
        sendEvent(name: "pingStatus", value: pingSuccess ? "success" : "failed", descriptionText: "Ping ${deviceIP} ${pingSuccess}")
    }

    if (httpURL) {
        httpSuccess = sendHttpRequest()
        sendEvent(name: "httpStatus", value: httpSuccess ? "success" : "failed", descriptionText: "HTTP GET ${httpURL} ${httpSuccess}")
    }

    updateDeviceStatus(pingSuccess && httpSuccess)
}

void sendPingRequest() {
    try {
        def timeBefore = now()
        def pingData = NetworkUtils.ping(deviceIP, 1)
        def timeAfter = now()
        logDebug "Ping $deviceIP result: ${pingData.packetLoss == 100 ? 'Failed' : 'Success'} in ${timeAfter-timeBefore} ms"
        return pingData.packetLoss != 100
    } catch (Exception e) {
        log.error "Error during ping: ${e.message}"
        return false
    } finally {
        atomicState.isPinging = false
    }
}

boolean sendHttpRequest() {
    try {
        def params = [
            uri: httpURL,
            timeout: 15
        ]
        httpGet(params) { response ->
            if (response.status == 200) {
                logDebug "HTTP GET $httpURL successful"
                return true
            } else {
                logInfo "HTTP GET $httpURL failed with status ${response.status}"
                return false
            }
        }
    } catch (Exception e) {
        log.error "Error sending HTTP request: ${e.message}"
        return false
    } finally {
        atomicState.isPinging = false
    }
}

void updateDeviceStatus(boolean online) {
    def currentStatus = device.currentValue("status")
    def newStatus = online ? "online" : "offline"
    def contactValue = online ? "closed" : "open"

    // Update timestamp
    state.lastCheckin = new Date().format("yyyy-MM-dd HH:mm:ss")

    if (currentStatus != newStatus) {
        logInfo "Device ${device.getLabel()} - tracking state change to ${newStatus}"

        if (online || state.currentRetryCount >= state.retryThreshold) {
            String newStatusDescription = "${device.getLabel()} status is ${newStatus}"
            sendEvent(name: "status", value: newStatus, descriptionText: newStatusDescription)
            sendEvent(name: "contact", value: contactValue, descriptionText: newStatusDescription)
            logInfo "Device ${device.getLabel()} status changed from ${currentStatus} to ${newStatus}"

            // Reset retry count if device comes back online
            if (online) {
                resetRetryCount()
            }
        }
    }

    // Handle retry logic for offline devices
    if (!online) {
        handleRetry()
    }
}

void handleRetry() {
    // Increment retry counter
    state.currentRetryCount++

    // Check if we should continue retrying
    if (state.currentRetryCount <= maxRetries) {
        // Calculate backoff time using exponential backoff strategy
        def backoffFactor = Math.min(Math.pow(2, state.currentRetryCount - 1), maxBackoffFactor).toInteger()
        def nextRetryInterval = retryInterval * backoffFactor

        logDebug "Scheduling retry ${state.currentRetryCount}/${maxRetries} in ${nextRetryInterval} seconds (backoff factor: ${backoffFactor})"

        // Cancel any existing retry and schedule a new one
        unschedule("ping")
        runIn(nextRetryInterval, "ping")
    } else {
        logDebug "Maximum retry count (${maxRetries}) reached. Falling back to regular schedule."
        // We'll let the regular schedule take over now
        reschedulePing()
    }
}

void resetRetryCount() {
    state.currentRetryCount = 0
    logDebug "Reset retry count to 0"
}

void setRetryThreshold(threshold) {
    state.retryThreshold = threshold
    logDebug "Set retry threshold to ${threshold}"
}

// Logging helpers

private logTrace(message) {
    if (traceEnable) log.trace("${device} : ${message}")
}

private logDebug(message) {
    if (debugEnable) log.debug("${device} : ${message}")
}

private logInfo(message) {
    if (txtEnable) log.info("${device} : ${message}")
}

private logWarn(message) {
    log.warn("${device} : ${message}")
}

private logError(message) {
    log.error("${device} : ${message}")
}

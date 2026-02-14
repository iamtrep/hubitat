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
        importUrl: "https://raw.githubusercontent.com/iamtrep/hubitat/refs/heads/main/drivers/DevicePing.groovy",
        singleThreaded: true
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
        attribute "lastPingResponseTime", "number"
        attribute "lastHttpResponseTime", "number"
        attribute "lastResponseTime", "number"
    }

    preferences {
        input "deviceIP", "string", title: "Device IP Address (Optional)", description: "Enter the IP address of the device to monitor (leave blank to use HTTP GET only)", required: false
        input "httpURL", "string", title: "HTTP URL (Optional)", description: "Enter a full URL for HTTP GET (leave blank to use ICMP ping only)", required: false
        input "pingInterval", "number", title: "Ping Interval (minutes)", description: "How often to check if device is online", defaultValue: 5, range: "1..*", required: true
        input "retryInterval", "number", title: "Initial Retry Interval (seconds)", description: "How long to wait before first retry after a failed ping", defaultValue: 30, range: "1..*", required: true
        input "maxRetries", "number", title: "Maximum Retry Count", description: "Maximum number of retry attempts before backing off to regular schedule", defaultValue: 5, range: "0..*", required: true
        input "maxBackoffFactor", "number", title: "Maximum Backoff Multiplier", description: "Maximum multiplier for retry interval (prevents extremely long waits)", defaultValue: 10, range: "1..*", required: true
        input "retryThreshold", "number", title: "Retry Threshold for Status Update", description: "Number of retries before updating status to offline", defaultValue: DEFAULT_RETRY_THRESHOLD, range: "0..*", required: true
        input "httpTimeout", "number", title: "HTTP Timeout (seconds)", description: "How long to wait for an HTTP response", defaultValue: DEFAULT_HTTP_TIMEOUT, range: "1..60", required: true
        input "slowThreshold", "number", title: "Slow Response Threshold (ms)", description: "Log a warning when response time exceeds this value (0 = disabled)", defaultValue: 0, range: "0..*", required: false

        input name: "txtEnable", type: "bool", title: "Enable descriptionText logging", defaultValue: false
        input name: "debugEnable", type: "bool", title: "Enable debug logging info", defaultValue: false, required: true, submitOnChange: true
        if (debugEnable) {
            input name: "traceEnable", type: "bool", title: "Enable trace logging info (for development purposes)", defaultValue: false
        }
    }
}

import hubitat.helper.NetworkUtils
import groovy.transform.Field
import groovy.transform.CompileStatic

@Field static final String driver_version = "0.0.6"
@Field static final int RESPONSE_HISTORY_SIZE = 21
@Field static final int DEBUG_LOG_TIMEOUT = 1800
@Field static final int INITIAL_PING_DELAY = 2
@Field static final int DEFAULT_HTTP_TIMEOUT = 15
@Field static final int DEFAULT_RETRY_THRESHOLD = 3

void installed() {
    logDebug "Installed with settings: ${settings}"
    state.clear()
    initialize()
}

void updated() {
    logDebug "Updated with settings: ${settings}"
    initialize()
}

void initialize() {
    logDebug("initialize()")

    initState()
    state.remove('isPinging')

    // Checks if firmware version supports 3-parameter ping (adjust version as needed)
    state.supportsPingTimeout = supportsPingTimeout(location.hub.firmwareVersionString)

    reschedulePing()

    if (debugEnable) runIn(DEBUG_LOG_TIMEOUT, "logsOff")
}

private void initState() {
    if (state.version != driver_version) {
        log.warn("New driver version detected: ${driver_version} (previous: ${state.version})")
        unschedule("ping")
        state.version = driver_version
    }
    if (state.currentRetryCount == null) state.currentRetryCount = 0
    if (state.retryThreshold == null) state.retryThreshold = settings.retryThreshold ?: DEFAULT_RETRY_THRESHOLD
    if (state.pingHistory == null) state.pingHistory = []
    if (state.httpHistory == null) state.httpHistory = []
}

void refresh() {
    logDebug "refresh() - manually refreshing status"
    ping()
}

void reschedulePing() {
    unschedule("ping")

    if (deviceIP || httpURL) {
        runIn(INITIAL_PING_DELAY, "ping")
    } else {
        log.warn "No device IP or HTTP URL specified. Pings will not be scheduled."
    }
}

void logsOff() {
    logDebug("Debug logging turned off")
    device.updateSetting("debugEnable", false)
    device.updateSetting("traceEnable", false)
}

void parse(String description) {
    logDebug "parse: ${description}"
}

void ping() {
    initState()
    // Ensure at least one of deviceIP or httpURL is set before attempting a ping
    if (!deviceIP && !httpURL) {
        log.warn "No device IP or HTTP URL specified. Ping aborted."
        return
    }

    try {
        long pingRT = -1

        if (deviceIP) {
            pingRT = sendPingRequest()
            sendEvent(name: "pingStatus", value: pingRT >= 0 ? "success" : "failed", descriptionText: "Ping ${deviceIP} ${pingRT >= 0}")
        }

        long httpRT = -1

        if (httpURL) {
            httpRT = sendHttpRequest()
            sendEvent(name: "httpStatus", value: httpRT >= 0 ? "success" : "failed", descriptionText: "HTTP GET ${httpURL} ${httpRT >= 0}")
        }

        updateDeviceStatus((deviceIP ? pingRT >= 0 : true) && (httpURL ? httpRT >= 0 : true))
        updateLastResponseTime(pingRT, httpRT)
    } catch (Exception e) {
        log.error "Error during ping: ${e.message}"
    } finally {
        scheduleNextPing()
    }
}

long sendPingRequest() {
    try {
        NetworkUtils.PingData pingData = state.supportsPingTimeout ? NetworkUtils.ping(deviceIP,1,1) : NetworkUtils.ping(deviceIP, 1)
        boolean success = pingData.packetLoss != 100
        logDebug "Ping $deviceIP result: ${success ? 'Success' : 'Failed'} rttAvg: ${pingData.rttAvg} ms"
        if (success) {
            long elapsed = Math.round(pingData.rttAvg) as long
            recordResponseTime("ping", elapsed)
            return elapsed
        }
        return -1
    } catch (Exception e) {
        log.error "Error during ping: ${e.message}"
        return -1
    }
}

long sendHttpRequest() {
    long result = -1
    try {
        Map params = [
            uri: httpURL,
            timeout: httpTimeout ?: DEFAULT_HTTP_TIMEOUT
        ]
        long timeBefore = now()
        httpGet(params) { response ->
            if (response.status == 200) {
                long elapsed = now() - timeBefore
                logDebug "HTTP GET $httpURL successful in ${elapsed} ms"
                recordResponseTime("http", elapsed)
                result = elapsed
            } else {
                logInfo "HTTP GET $httpURL failed with status ${response.status}"
            }
        }
        return result
    } catch (Exception e) {
        log.error "Error sending HTTP request: ${e.message}"
        return -1
    }
}

void updateDeviceStatus(boolean online) {
    String currentStatus = device.currentValue("status")
    String newStatus = online ? "online" : "offline"
    String contactValue = online ? "closed" : "open"

    // Update timestamp
    state.lastCheckin = new Date().format("yyyy-MM-dd HH:mm:ss")

    if (currentStatus != newStatus) {
        logInfo "Device ${device.getLabel()} - tracking state change to ${newStatus}"

        if (online || state.currentRetryCount >= state.retryThreshold) {
            String newStatusDescription = "${device.getLabel()} status is ${newStatus}"
            sendEvent(name: "status", value: newStatus, descriptionText: newStatusDescription)
            sendEvent(name: "contact", value: contactValue, descriptionText: newStatusDescription)
            logInfo "Device ${device.getLabel()} status changed from ${currentStatus} to ${newStatus}"

            // Reset retry count so scheduleNextPing() uses normal interval
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

private void scheduleNextPing() {
    int delay
    if (state.currentRetryCount > 0 && state.currentRetryCount <= maxRetries) {
        // Retry mode: exponential backoff
        def backoffFactor = Math.min(Math.pow(2, state.currentRetryCount - 1), maxBackoffFactor).toInteger()
        delay = retryInterval * backoffFactor
        logDebug "Scheduling retry ${state.currentRetryCount}/${maxRetries} in ${delay} seconds (backoff factor: ${backoffFactor})"
    } else {
        // Normal mode: pingInterval with ±7s jitter to desynchronize devices
        int intervalSecs = (pingInterval ?: 5) * 60
        delay = intervalSecs - 7 + new Random().nextInt(15)
        logDebug "Scheduling next ping in ${delay} seconds"
    }
    runIn(delay, "ping")
}

void handleRetry() {
    state.currentRetryCount++
    logDebug "Retry count incremented to ${state.currentRetryCount}/${maxRetries}"
}

void resetRetryCount() {
    state.currentRetryCount = 0
    logDebug "Reset retry count to 0"
}

void setRetryThreshold(threshold) {
    state.retryThreshold = threshold
    logDebug "Set retry threshold to ${threshold}"
}

@Field static List<Integer> constNewPingVersion = [2, 4, 3, 149]

@CompileStatic
private boolean supportsPingTimeout(String versionString) {
    List<Integer> v = versionString.tokenize('.').collect { it as Integer }

    for (int i = 0; i < 4; i++) {
        if (v[i] != constNewPingVersion[i]) {
            return v[i] > constNewPingVersion[i]
        }
    }
    return false
}


// Response time tracking

private void updateLastResponseTime(long pingRT, long httpRT) {
    Long maxRT = [pingRT, httpRT].findAll { it >= 0 }.max()
    if (maxRT != null) {
        sendEvent(name: "lastResponseTime", value: maxRT, unit: "ms")
    }
}

private void recordResponseTime(String name, long elapsed) {
    String capitalName = name.capitalize()
    sendEvent(name: "last${capitalName}ResponseTime", value: elapsed, unit: "ms")

    String historyKey = "${name}History"
    List history = state[historyKey] ?: []
    history.add(elapsed)
    if (history.size() > RESPONSE_HISTORY_SIZE) {
        history = history.drop(history.size() - RESPONSE_HISTORY_SIZE)
    }
    state[historyKey] = history

    long median = computeMedian(history)
    state["${name}MedianResponseTime"] = median

    if (slowThreshold && (slowThreshold as int) > 0 && elapsed > (slowThreshold as int)) {
        log.warn "${device} : Slow ${name} response: ${elapsed} ms (threshold: ${slowThreshold} ms, median: ${median} ms)"
    }
}

@CompileStatic
private long computeMedian(List<Long> values) {
    if (!values) return 0
    List<Long> sorted = values.collect().sort()
    int mid = sorted.size().intdiv(2)
    if (sorted.size() % 2 == 0) {
        return ((sorted[mid - 1] + sorted[mid]) / 2) as long
    }
    return sorted[mid] as long
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

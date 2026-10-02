// Copyright (c) 2025-2026 PJ
// SPDX-License-Identifier: MIT

/*
 Hubitat Elevation driver to ping devices
 */

metadata {
    definition (
        name: "Device Ping",
        namespace: "iamtrep",
        author: "pj",
        description: "Pings a device and reports connectivity as a contact sensor",
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

        input name: "txtEnable", type: "bool", title: "Enable info logging", defaultValue: true
        input name: "debugEnable", type: "bool", title: "Enable debug logging info", defaultValue: false, required: true, submitOnChange: true
        if (debugEnable) {
            input name: "traceEnable", type: "bool", title: "Enable trace logging info (for development purposes)", defaultValue: false
        }
    }
}

import hubitat.helper.NetworkUtils
import groovy.transform.Field
import groovy.transform.CompileStatic

@Field static final String CODE_VERSION = "0.0.9"
@Field static final int RESPONSE_HISTORY_SIZE = 21
@Field static final int DEBUG_LOG_TIMEOUT = 1800
@Field static final int INITIAL_PING_DELAY = 2
@Field static final int DEFAULT_HTTP_TIMEOUT = 15
@Field static final int DEFAULT_RETRY_THRESHOLD = 3
// Statuses a retry can't fix: the HTTP check stops until preferences are saved or Initialize runs.
@Field static final List<Integer> HTTP_STOP_STATUSES = [401, 403, 404, 410]

void installed() {
    logDebug "Installed with settings: ${redactSettings()}"
    state.clear()
    initialize()
}

void updated() {
    logDebug "Updated with settings: ${redactSettings()}"
    initialize()
}

void deviceTypeUpdated() {
    logDebug "driver change detected"
}

void initialize() {
    logDebug("initialize()")

    initState()
    state.remove('isPinging')
    state.remove('httpStopped')
    state.remove('httpRetryAfter')

    // Checks if firmware version supports 3-parameter ping (adjust version as needed)
    state.supportsPingTimeout = supportsPingTimeout(location.hub.firmwareVersionString)

    reschedulePing()

    if (debugEnable || traceEnable) runIn(DEBUG_LOG_TIMEOUT, "logsOff")
}

private void initState() {
    if (state.version != CODE_VERSION) {
        logVer "New driver version detected: ${CODE_VERSION} (previous: ${state.version})"
        unschedule("ping")
        state.version = CODE_VERSION
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
        logWarn "No device IP or HTTP URL specified. Pings will not be scheduled."
    }
}

void logsOff() {
    logWarn "Debug logging turned off"
    device.updateSetting("debugEnable", [value: "false", type: "bool"])
    device.updateSetting("traceEnable", [value: "false", type: "bool"])
}

void parse(String description) {
    logRx "parse: ${description}"
}

void ping() {
    initState()
    // Ensure at least one of deviceIP or httpURL is set before attempting a ping
    if (!deviceIP && !httpURL) {
        logWarn "No device IP or HTTP URL specified. Ping aborted."
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
            if (state.httpStopped) {
                logDebug "HTTP check stopped after HTTP ${state.httpStopped}; save preferences or run Initialize to resume"
            } else {
                httpRT = sendHttpRequest()
            }
            sendEvent(name: "httpStatus", value: httpRT >= 0 ? "success" : "failed", descriptionText: "HTTP GET ${redactUrl(httpURL)} ${httpRT >= 0}")
        }

        updateDeviceStatus((deviceIP ? pingRT >= 0 : true) && (httpURL ? httpRT >= 0 : true))
        updateLastResponseTime(pingRT, httpRT)
    } catch (Exception e) {
        logError "Error during ping: ${e}"
    } finally {
        scheduleNextPing()
    }
}

long sendPingRequest() {
    try {
        NetworkUtils.PingData pingData = state.supportsPingTimeout ? NetworkUtils.ping(deviceIP,1,1) : NetworkUtils.ping(deviceIP, 1)
        boolean success = pingData.packetLoss != 100
        logNet "Ping $deviceIP result: ${success ? 'Success' : 'Failed'} rttAvg: ${pingData.rttAvg} ms"
        if (success) {
            long elapsed = Math.round(pingData.rttAvg) as long
            recordResponseTime("ping", elapsed)
            return elapsed
        }
        return -1
    } catch (Exception e) {
        logError "Error during ping: ${e}"
        return -1
    }
}

long sendHttpRequest() {
    long result = -1
    try {
        Map params = [
            timeout: httpTimeout ?: DEFAULT_HTTP_TIMEOUT,
            ignoreSSLIssues: true
        ] + splitQuery(httpURL)
        long timeBefore = now()
        httpGet(params) { response ->
            if (response.status >= 200 && response.status < 300) {
                long elapsed = now() - timeBefore
                logNet "HTTP GET ${redactUrl(httpURL)} successful in ${elapsed} ms"
                recordResponseTime("http", elapsed)
                result = elapsed
            } else {
                logWarn "HTTP GET ${redactUrl(httpURL)} failed with status ${response.status}"
            }
        }
        return result
    } catch (groovyx.net.http.HttpResponseException e) {
        // httpGet throws on any non-2xx status, so status handling lives here.
        int status = e.statusCode
        if (status in HTTP_STOP_STATUSES) {
            state.httpStopped = status
            logError "HTTP GET ${redactUrl(httpURL)} returned HTTP ${status}; HTTP checks stopped until preferences are saved or Initialize runs"
        } else {
            Long retryAfter = retryAfterSeconds(e)
            if (retryAfter) state.httpRetryAfter = retryAfter
            logWarn "HTTP GET ${redactUrl(httpURL)} failed with HTTP ${status}${retryAfter ? " (Retry-After ${retryAfter} s)" : ''}"
        }
        return -1
    } catch (Exception e) {
        logWarn "Error sending HTTP request: ${e}"
        return -1
    }
}

private Long retryAfterSeconds(groovyx.net.http.HttpResponseException e) {
    try {
        String v = e.response?.headers?.'Retry-After'?.value as String
        return v?.trim()?.isLong() ? v.trim().toLong() : null
    } catch (Exception ignored) {
        return null
    }
}

private Map redactSettings() {
    return settings.collectEntries { k, v -> [(k): k == 'httpURL' ? redactUrl(v as String) : v] }
}

@CompileStatic
private static String redactUrl(String url) {
    return url?.replaceAll(/access_token=[^&]+/, 'access_token=REDACTED')
}

// Firmware 2.5.1.x (Apache HttpClient 5.x) drops a query string embedded in the
// uri, so split it into a query: map, which is sent correctly. Values pass through
// as-is — clean for Hubitat cloud tokens (UUIDs) and other reserved-char-free values.
private Map splitQuery(String url) {
    int q = url.indexOf('?')
    if (q < 0) { return [uri: url] }
    Map qs = [:]
    url.substring(q + 1).tokenize('&').each { pair ->
        int eq = pair.indexOf('=')
        if (eq >= 0) { qs[pair.substring(0, eq)] = pair.substring(eq + 1) }
        else         { qs[pair] = '' }
    }
    return [uri: url.substring(0, q), query: qs]
}

void updateDeviceStatus(boolean online) {
    String currentStatus = device.currentValue("status")
    String newStatus = online ? "online" : "offline"
    String contactValue = online ? "closed" : "open"

    // Update timestamp
    state.lastCheckin = new Date().format("yyyy-MM-dd HH:mm:ss")

    if (currentStatus != newStatus) {
        if (txtEnable) logInfo "tracking state change to ${newStatus}"

        // A stopped HTTP check never recovers on its own, so report offline without waiting for retries.
        if (online || state.currentRetryCount >= state.retryThreshold || state.httpStopped) {
            String newStatusDescription = "${device.getLabel()} status is ${newStatus}"
            sendEvent(name: "status", value: newStatus, descriptionText: newStatusDescription)
            sendEvent(name: "contact", value: contactValue, descriptionText: newStatusDescription)
            if (txtEnable) logInfo "status changed from ${currentStatus} to ${newStatus}"
        }
    }

    // Any success ends retry mode, including a failure that never reached the threshold;
    // otherwise scheduleNextPing() keeps polling at the retry interval.
    if (online && state.currentRetryCount > 0) {
        resetRetryCount()
    }

    // Handle retry logic for offline devices
    if (!online) {
        handleRetry()
    }
}

private void scheduleNextPing() {
    if (state.httpStopped && !deviceIP) {
        logSched "HTTP check stopped and no device IP set; not scheduling further pings"
        return
    }
    int delay
    if (state.currentRetryCount > 0 && state.currentRetryCount <= maxRetries) {
        // Retry mode: exponential backoff
        Integer backoffFactor = Math.min(Math.pow(2, state.currentRetryCount - 1), maxBackoffFactor).toInteger()
        delay = retryInterval * backoffFactor
        logSched "Scheduling retry ${state.currentRetryCount}/${maxRetries} in ${delay} seconds (backoff factor: ${backoffFactor})"
    } else {
        // Normal mode: pingInterval with ±7s jitter to desynchronize devices
        int intervalSecs = (pingInterval ?: 5) * 60
        delay = intervalSecs - 7 + new Random().nextInt(15)
        logSched "Scheduling next ping in ${delay} seconds"
    }
    Integer retryAfter = state.remove('httpRetryAfter') as Integer
    if (retryAfter && retryAfter > delay) {
        delay = retryAfter
        logSched "Server asked to retry after ${delay} seconds"
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
        logWarn "Slow ${name} response: ${elapsed} ms (threshold: ${slowThreshold} ms, median: ${median} ms)"
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


// ── Logging ───────────────────────────────────────────────────────────
//   ⬇️ Rx  ⬆️ Cmd  🔧 Cfg  🌐 Net  ⏰ Sched  📦 Ota  🏷️ Ver  ·  ⚠️ Warn  🛑 Error  🔬 Trace
private String logp(String e) { "${e} ${device.displayName}: " }

void logRx   (String m) { if (debugEnable) log.debug logp('⬇️') + m }
void logCmd  (String m) { if (txtEnable != false) log.info  logp('⬆️') + m }
void logCfg  (String m) { if (txtEnable != false) log.info  logp('🔧') + m }
void logNet  (String m) { if (debugEnable) log.debug logp('🌐') + m }
void logSched(String m) { if (debugEnable) log.debug logp('⏰') + m }
void logOta  (String m) { if (txtEnable != false) log.info  logp('📦') + m }
void logVer  (String m) { log.warn  logp('🏷️') + m }

void logWarn (String m) { log.warn  logp('⚠️') + m }
void logError(String m) { log.error logp('🛑') + m }
void logTrace(String m) { if (traceEnable) log.trace logp('🔬') + m }
void logInfo (String m) { if (txtEnable != false) log.info  "${device.displayName}: ${m}" }
void logDebug(String m) { if (debugEnable) log.debug "${device.displayName}: ${m}" }

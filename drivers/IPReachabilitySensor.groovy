// Copyright (c) 2025-2026 PJ
// SPDX-License-Identifier: MIT

/*
 Hubitat Elevation driver that checks a host by ICMP ping and/or HTTP GET
 */

metadata {
    definition (
        name: "IP Reachability Sensor",
        namespace: "iamtrep",
        author: "pj",
        description: "Checks whether a host is reachable by ICMP ping and/or HTTP GET and reports it as a contact sensor",
        importUrl: "https://raw.githubusercontent.com/iamtrep/hubitat/refs/heads/main/drivers/IPReachabilitySensor.groovy",
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
        input "httpURL", "string", title: "HTTP URL (Optional)",
              description: "Enter a full URL for HTTP GET (leave blank to use ICMP ping only). Secret values (a password in user:pass@, a query parameter such as access_token) are stored encrypted and shown masked, e.g. access_token=${BULLETS}a1b2; leave a masked value as it is to keep it", required: false
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
import java.util.concurrent.ConcurrentHashMap

@Field static final String CODE_VERSION = "0.1.7"
@Field static final int RESPONSE_HISTORY_SIZE = 21
@Field static final int DEBUG_LOG_TIMEOUT = 1800
@Field static final int INITIAL_PING_DELAY = 2
@Field static final int DEFAULT_HTTP_TIMEOUT = 15
@Field static final int DEFAULT_RETRY_THRESHOLD = 3
// Statuses a retry can't fix: the HTTP check stops until preferences are saved or Initialize runs.
@Field static final List<Integer> HTTP_STOP_STATUSES = [401, 403, 404, 410]
// The URL stays readable in its preference except its secret values, which are kept encrypted in
// state.httpUrlSecretsEnc and masked the way WordPress masks API keys: a token shows bullets and
// its last 4 characters, so it can be matched against the issuing app's token list; a password
// shows bullets only. Secret values are the password in user:pass@ and the value of a query
// parameter whose name matches SECRET_PARAM. Hosts, paths, usernames and other parameters stay
// readable. See ARCHITECTURE.md, "Secrets: storage and display".
@Field static final String BULLETS = "••••••••"
@Field static final String USERINFO_PASSWORD = "@password"
// A whole word, alone or after _ or -, in any case (access_token, X-Amz-Signature, apikey), or a
// camelCase suffix (accessToken, apiKey); "monkey" or "keyword" don't match.
@Field static final String SECRET_PARAM = /^(?i:(?:.*[_-])?(?:access_?token|token|api_?key|key|secret|password|passwd|pwd|pin|sig|signature|auth|credentials?))$|^.*(?:Token|Key|Secret|Password|Signature)$/
// Parameters whose value is a password: bullets only, no last 4 characters.
@Field static final String PASSWORD_PARAM = /(?i).*(passw|pwd|pin).*/
// A flag or small number is not a secret even in a credential-named parameter (auth=1).
@Field static final String NOT_SECRET_VALUE = /(?i)true|false|yes|no|on|off|\d{1,3}/
// How 0.1.6 showed a stored value, and its key for the whole user-info part.
@Field static final String STORED_016 = "<stored>"
@Field static final String USERINFO_016 = "@userinfo"
// Decrypted secret values as JSON, kept in memory only: decrypt() costs ~350 ms on a C-8 Pro,
// ~830 ms on a C-7. Keyed by device id; value [ciphertext, plaintext].
@Field static final ConcurrentHashMap<String, List<String>> URL_CACHE = new ConcurrentHashMap<>()
// The preference value storeHttpUrl() last wrote, by device id. Preferred over the setting: the
// execution that writes a setting may still read the old one, and an upgrade runs inside ping().
@Field static final ConcurrentHashMap<String, String> SAVED_URL = new ConcurrentHashMap<>()

void installed() {
    logDebug "Installed with settings: ${redactSettings()}"
    state.clear()
    initialize()
}

void updated() {
    storeHttpUrl()
    logDebug "Updated with settings: ${redactSettings()}"
    initialize()
}

void uninstalled() {
    URL_CACHE.remove(device.id.toString())
    SAVED_URL.remove(device.id.toString())
}

// Moves the secret values of the typed URL into encrypted state and writes the masked URL back
// to the preference. Runs on every save and once after an upgrade, and takes each earlier
// layout: 0.1.4 and earlier kept the whole URL in the preference, 0.1.5 kept it only encrypted
// (state.httpUrlEnc) with the preference removed, 0.1.6 showed stored values as <stored>.
private void storeHttpUrl() {
    String typed = settings.httpURL as String
    if (!typed && state.httpUrlEnc) typed = decryptOrNull(state.httpUrlEnc as String, "0.1.5 URL")
    Map split = typed ? splitSecrets(typed, urlSecrets()) : null
    if (split?.missing) logWarn "No stored value matches ${(split.missing as List).join(', ')}; enter the full value in the HTTP URL"
    Map secrets = (split?.secrets ?: [:]) as Map
    if (secrets) state.httpUrlSecretsEnc = encrypt(groovy.json.JsonOutput.toJson(secrets))
    else state.remove("httpUrlSecretsEnc")
    if (split && split.masked != settings.httpURL) device.updateSetting("httpURL", [value: split.masked, type: "string"])
    ["httpUrlEnc", "httpUrlShown"].each { state.remove(it) }
    device.removeSetting("clearHttpUrl")    // a 0.1.5 preference
    URL_CACHE.remove(device.id.toString())
    if (split) SAVED_URL.put(device.id.toString(), split.masked as String)
    else SAVED_URL.remove(device.id.toString())
}

// The URL as the preference shows it, with secret values masked. Null when none is set.
private String maskedUrl() {
    return SAVED_URL.get(device.id.toString()) ?: (settings.httpURL as String)
}

// The URL to request: the masked URL with its stored values put back.
private String checkUrl() {
    String masked = maskedUrl()
    return masked ? joinSecrets(masked, urlSecrets()) : null
}

private Map urlSecrets() {
    String enc = state.httpUrlSecretsEnc as String
    if (!enc) return [:]
    String key = device.id.toString()
    List<String> hit = URL_CACHE.get(key)
    String json = (hit && hit[0] == enc) ? hit[1] : decryptOrNull(enc, "stored URL values")
    if (json == null) return [:]
    URL_CACHE.put(key, [enc, json])
    return new groovy.json.JsonSlurper().parseText(json) as Map
}

private String decryptOrNull(String enc, String what) {
    try {
        return decrypt(enc)
    } catch (Exception e) {
        logError "The ${what} can't be read (${e.message}), as after a hub restore; enter the HTTP URL again"
        return null
    }
}

// The stored values' names as a person reads them, for error messages.
private String secretNames() {
    return urlSecrets().keySet().collect { it == USERINFO_PASSWORD ? "password" : it }.join(", ")
}

@CompileStatic
static String maskSecret(String name, String value) {
    boolean passwordLike = name == USERINFO_PASSWORD || name ==~ PASSWORD_PARAM
    return (passwordLike || value.length() < 16) ? BULLETS : BULLETS + value.substring(value.length() - 4)
}

// A value the user left masked: bullets with up to 4 trailing characters, or 0.1.6's <stored>.
// Returns the visible suffix ("" when none), or null when the value is not masked.
@CompileStatic
static String maskedSuffix(String value) {
    if (value == STORED_016) return ""
    java.util.regex.Matcher m = value =~ /^•+([^•]{0,4})$/
    return m.find() ? m.group(1) : null
}

// Splits a typed or stored URL into [masked: the URL to show, secrets: name to value,
// missing: names of masked values with no matching stored one]. A masked value keeps the stored
// value of the same name if that ends with the shown characters; anything else counts as typed.
@CompileStatic
static Map splitSecrets(String url, Map kept) {
    String secretParam = SECRET_PARAM, notSecret = NOT_SECRET_VALUE, pwKey = USERINFO_PASSWORD
    Map<String, String> secrets = [:]
    List<String> missing = []
    String rest = url
    String fragment = ""
    int hash = rest.indexOf('#')
    if (hash >= 0) { fragment = rest.substring(hash); rest = rest.substring(0, hash) }
    java.util.regex.Matcher m = rest =~ /^([A-Za-z][A-Za-z0-9+.-]*:\/\/)([^\/?#@]*)@/
    if (m.find()) {
        String info = m.group(2)
        if (info == STORED_016 && kept[USERINFO_016] != null) info = kept[USERINFO_016] as String
        int colon = info.indexOf(':')
        if (colon >= 0) {
            String user = info.substring(0, colon)
            String pass = info.substring(colon + 1)
            String suffix = maskedSuffix(pass)
            if (suffix != null) {
                String old = kept[pwKey] as String
                if (old != null && old.endsWith(suffix)) pass = old
                else { missing << "password"; pass = null }
            }
            if (pass != null) secrets[pwKey] = pass
            info = user + ":" + (pass != null ? maskSecret(pwKey, pass) : BULLETS)
        }
        rest = m.group(1) + info + "@" + rest.substring(m.end())
    }
    int q = rest.indexOf('?')
    if (q >= 0) {
        List<String> pairs = []
        rest.substring(q + 1).tokenize('&').each { String pair ->
            int eq = pair.indexOf('=')
            String name = eq >= 0 ? pair.substring(0, eq) : pair
            String value = eq >= 0 ? pair.substring(eq + 1) : ""
            if (!value || !(name ==~ secretParam) || value ==~ notSecret) { pairs << pair; return }
            String suffix = maskedSuffix(value)
            if (suffix != null) {
                String old = kept[name] as String
                if (old != null && old.endsWith(suffix)) value = old
                else { missing << name; pairs << pair; return }
            }
            secrets[name] = value
            pairs << (name + "=" + maskSecret(name, value))
        }
        rest = rest.substring(0, q + 1) + pairs.join('&')
    }
    return [masked: rest + fragment, secrets: secrets, missing: missing]
}

// The masked URL with each stored value put back where its mask is.
@CompileStatic
static String joinSecrets(String masked, Map secrets) {
    String pwKey = USERINFO_PASSWORD
    String url = masked
    String pass = secrets[pwKey] as String
    if (pass != null) {
        String shown = ":" + maskSecret(pwKey, pass) + "@"
        int at = url.indexOf(shown)
        if (at >= 0) url = url.substring(0, at) + ":" + pass + "@" + url.substring(at + shown.length())
    }
    secrets.each { k, v ->
        if (k != pwKey) url = url.replace("${k}=${maskSecret(k as String, v as String)}".toString(), "${k}=${v}".toString())
    }
    return url
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
    // Drops the callback of a check still in flight, which may be for the old URL.
    state.checkSeq = ((state.checkSeq ?: 0) as int) + 1

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
        storeHttpUrl()
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

    if (deviceIP || checkUrl()) {
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
    // Ensure at least one of deviceIP or an HTTP URL is set before attempting a ping
    if (!deviceIP && !checkUrl()) {
        logWarn "No device IP or HTTP URL specified. Ping aborted."
        return
    }

    // A newer check (manual refresh, initialize) makes an in-flight HTTP callback stale.
    int seq = ((state.checkSeq ?: 0) as int) + 1
    state.checkSeq = seq

    long pingRT = -1
    try {
        if (deviceIP) {
            pingRT = sendPingRequest()
            sendEvent(name: "pingStatus", value: pingRT >= 0 ? "success" : "failed", descriptionText: "Ping ${deviceIP} ${pingRT >= 0}")
        }
        if (checkUrl()) {
            if (state.httpStopped) {
                logDebug "HTTP check stopped after HTTP ${state.httpStopped}; save preferences or run Initialize to resume"
            } else if (sendHttpRequest(seq, pingRT)) {
                return  // httpResponse() finishes the check
            }
        }
    } catch (Exception e) {
        noteCheckError("check", "Error during check: ${e}")
        scheduleNextPing()
        return
    }
    finishCheck(pingRT, -1)
}

private void finishCheck(long pingRT, long httpRT) {
    try {
        if (checkUrl()) {
            sendEvent(name: "httpStatus", value: httpRT >= 0 ? "success" : "failed", descriptionText: "HTTP GET ${shownUrl()} ${httpRT >= 0}")
        }
        updateDeviceStatus((deviceIP ? pingRT >= 0 : true) && (checkUrl() ? httpRT >= 0 : true))
        updateLastResponseTime(pingRT, httpRT)
        clearCheckError("check")
    } catch (Exception e) {
        noteCheckError("check", "Error during check: ${e}")
    } finally {
        scheduleNextPing()
    }
}

long sendPingRequest() {
    try {
        NetworkUtils.PingData pingData = state.supportsPingTimeout ? NetworkUtils.ping(deviceIP,1,1) : NetworkUtils.ping(deviceIP, 1)
        clearCheckError("ping")
        boolean success = pingData.packetLoss != 100
        logNet "Ping $deviceIP result: ${success ? 'Success' : 'Failed'} rttAvg: ${pingData.rttAvg} ms"
        if (success) {
            long elapsed = Math.round(pingData.rttAvg) as long
            recordResponseTime("ping", elapsed)
            return elapsed
        }
        return -1
    } catch (Exception e) {
        noteCheckError("ping", "Error during ping: ${e}")
        return -1
    }
}

// An exception that recurs on every check logs at error once per distinct message,
// then at debug; clearCheckError() logs when it stops.
private void noteCheckError(String key, String msg) {
    Map errs = (state.checkErrors ?: [:]) as Map
    if (errs[key] != msg) logError msg
    else logDebug msg
    errs[key] = msg
    state.checkErrors = errs
}

private void clearCheckError(String key) {
    Map errs = (state.checkErrors ?: [:]) as Map
    if (!errs.containsKey(key)) return
    logInfo "${key} no longer failing (was: ${errs[key]})"
    errs.remove(key)
    state.checkErrors = errs
}

// Async so a slow host doesn't hold this singleThreaded driver for up to httpTimeout.
// Returns false when the request could not be sent; the check then counts it as failed.
private boolean sendHttpRequest(int seq, long pingRT) {
    try {
        int timeout = (httpTimeout ?: DEFAULT_HTTP_TIMEOUT) as int
        Map params = [
            timeout: timeout,
            ignoreSSLIssues: true
        ] + splitQuery(checkUrl())
        // A lost callback would end the ping chain; scheduleNextPing() replaces this runIn.
        runIn(timeout + 30, "ping")
        asynchttpGet("httpResponse", params, [seq: seq, pingRT: pingRT, start: now()])
        return true
    } catch (Exception e) {
        logWarn "Error sending HTTP request: ${e}"
        return false
    }
}

void httpResponse(resp, Map data) {
    if ((data.seq as int) != (state.checkSeq as int)) {
        logDebug "ignoring HTTP result of a superseded check"
        return
    }
    // Includes any wait behind other executions of this driver before the callback ran.
    long elapsed = now() - (data.start as long)
    long httpRT = -1
    try {
        if (resp.hasError()) {
            // hasError() covers every non-2xx status; timeouts and connection failures
            // report 408 or a status below 100.
            int status = resp.getStatus()
            if (status in HTTP_STOP_STATUSES) {
                state.httpStopped = status
                String rejected = (status in [401, 403] && secretNames()) ? "; the stored ${secretNames()} was rejected, enter the current value" : ""
                logError "HTTP GET ${shownUrl()} returned HTTP ${status}${rejected}; HTTP checks stopped until preferences are saved or Initialize runs"
            } else {
                Long retryAfter = retryAfterSeconds(resp)
                if (retryAfter) state.httpRetryAfter = retryAfter
                logWarn "HTTP GET ${shownUrl()} failed with HTTP ${status}: ${resp.getErrorMessage()}${retryAfter ? " (Retry-After ${retryAfter} s)" : ''}"
            }
        } else if (resp.getStatus() >= 200 && resp.getStatus() < 300) {
            logNet "HTTP GET ${shownUrl()} successful in ${elapsed} ms"
            recordResponseTime("http", elapsed)
            httpRT = elapsed
        } else {
            logWarn "HTTP GET ${shownUrl()} failed with status ${resp.getStatus()}"
        }
    } catch (Exception e) {
        logWarn "Error handling HTTP response: ${e}"
    }
    finishCheck(data.pingRT as long, httpRT)
}

private Long retryAfterSeconds(resp) {
    try {
        String v = resp.getHeaders()?.find { k, val -> (k as String)?.equalsIgnoreCase("Retry-After") }?.value as String
        return v?.trim()?.isLong() ? v.trim().toLong() : null
    } catch (Exception ignored) {
        return null
    }
}

private Map redactSettings() {
    return settings.collectEntries { k, v -> [(k): k == 'httpURL' && v ? splitSecrets(v as String, [:]).masked : v] }
}

// The URL as logs and events show it. Masked again in case the preference still holds a value
// typed in full (a save not yet processed by storeHttpUrl).
private String shownUrl() {
    String u = maskedUrl()
    return u ? splitSecrets(u, [:]).masked as String : "(none)"
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
        logInfo "tracking state change to ${newStatus}"

        // A stopped HTTP check never recovers on its own, so report offline without waiting for retries.
        if (online || state.currentRetryCount >= state.retryThreshold || state.httpStopped) {
            String newStatusDescription = "${device.getLabel()} status is ${newStatus}"
            sendEvent(name: "status", value: newStatus, descriptionText: newStatusDescription)
            sendEvent(name: "contact", value: contactValue, descriptionText: newStatusDescription)
            logInfo "status changed from ${currentStatus} to ${newStatus}"
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

void setRetryThreshold(Number threshold) {
    state.retryThreshold = threshold
    logDebug "Set retry threshold to ${threshold}"
}

@Field static final List<Integer> constNewPingVersion = [2, 4, 3, 149]

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
        return (sorted[mid - 1] + sorted[mid]).intdiv(2) as long
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

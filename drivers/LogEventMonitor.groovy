// Copyright (c) 2025-2026 PJ
// SPDX-License-Identifier: MIT

/**
 * Log Event Monitor Driver
 * Monitors system logs via WebSocket and exposes events for automations
 */

import groovy.json.JsonSlurper
import groovy.transform.Field
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

@Field static final String CODE_VERSION = "1.9.2"
@Field static final int STARTUP_DELAY_SECS = 60
@Field static final JsonSlurper JSON_SLURPER = new JsonSlurper()
// Set by disconnect() so the socket-closed callback doesn't reconnect. Not atomicState: in a
// driver, a state write-back at method exit overwrites atomicState keys written after the
// method's first state access. False (the default) after a reboot is correct.
@Field static final ConcurrentHashMap<String, Boolean> INTENTIONAL_DISCONNECT = new ConcurrentHashMap<>()

// Per-message dedupe window and counters, keyed by device id. parse() runs concurrently,
// so these are updated only through atomic methods. They are in memory because they are
// per-minute or per-dedupe-window data: losing them on a push or reboot costs nothing, and
// keeping them in state would write the database on every log line.
@Field static final ConcurrentHashMap<String, ConcurrentHashMap<String, Long>> RECENT_EVENTS = new ConcurrentHashMap<>()
@Field static final ConcurrentHashMap<String, AtomicInteger> EVENTS_THIS_MINUTE = new ConcurrentHashMap<>()
@Field static final ConcurrentHashMap<String, AtomicInteger> EVENTS_MATCHED = new ConcurrentHashMap<>()
@Field static final ConcurrentHashMap<String, AtomicInteger> LOGS_RECEIVED = new ConcurrentHashMap<>()
// Reset only once a connection has stayed open STABLE_SECS: a hub that closes right after the
// handshake (a secured hub refusing an unauthenticated logsocket) must still back off.
@Field static final ConcurrentHashMap<String, AtomicInteger> RECONNECT_ATTEMPTS = new ConcurrentHashMap<>()
@Field static final int STABLE_SECS = 30
// Decrypted hub password, kept in memory only: decrypt() costs 350–830 ms. Keyed by device id;
// value [ciphertext, plaintext].
@Field static final ConcurrentHashMap<String, List<String>> PASSWORD_CACHE = new ConcurrentHashMap<>()
// Time of the last message received, keyed by device id. A socket can stay "open" and deliver
// nothing (seen for 36 h on a remote connection), so a long silence triggers a reconnect.
@Field static final ConcurrentHashMap<String, Long> LAST_MESSAGE = new ConcurrentHashMap<>()
@Field static final int DEFAULT_SILENCE_MINS = 30

metadata {
    definition(
        name: "Log Event Monitor",
        namespace: "iamtrep",
        author: "pj",
        description: "Monitors the hub log stream and fires events on pattern matches",
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
        input name: "hubUsername", type: "text",
            title: "Hub username (only for a hub with hub security; clear it to remove the stored password)",
            required: false
        input name: "hubPassword", type: "password",
            title: state.hubPasswordEnc ? "Hub password (stored, enter only to change it)" : "Hub password",
            required: false
        input name: "autoReconnect", type: "bool", title: "Auto-reconnect on disconnect",
            defaultValue: true
        input name: "silenceTimeout", type: "number",
            title: "Reconnect after this many minutes without a log line (0 = never)",
            defaultValue: DEFAULT_SILENCE_MINS, range: "0..1440"
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
        input name: "txtEnable", type: "bool",
            title: "Enable info logging", defaultValue: true
        input name: "enableDebug", type: "bool",
            title: "Enable Debug Logging", defaultValue: false, submitOnChange: true
        if (enableDebug) {
            input name: "enableTrace", type: "bool",
                title: "Enable Trace Logging (very verbose)", defaultValue: false
        }
    }
}

void installed() {
    logDebug "installed()"
    initialize()
}

void updated() {
    logDebug "updated()"
    storePassword()
    disconnect()
    unschedule()
    initialize()
    if (enableDebug || enableTrace) runIn(1800, "logsOff")
}

void deviceTypeUpdated() {
    logDebug "driver change detected"
}

void uninstalled() {
    logDebug "uninstalled()"
    disconnect()
    RECONNECT_ATTEMPTS.remove(device.id as String)
    PASSWORD_CACHE.remove(device.id as String)
    LAST_MESSAGE.remove(device.id as String)
}

void initialize() {
    checkVersion(false)
    logDebug "initialize()"

    // Initialize state
    // Close any open socket first: connecting over an open one makes the old socket's close
    // callback schedule a reconnect, which closes the new one, and so on. The flag stays set
    // until connect() so that late callback is ignored.
    INTENTIONAL_DISCONNECT.put(device.id.toString(), true)
    try { interfaces.webSocket.close() } catch (Exception ignored) { }
    devCounter(RECONNECT_ATTEMPTS).set(0)
    devCounter(EVENTS_MATCHED).set(0)
    devCounter(EVENTS_THIS_MINUTE).set(0)
    state.eventsMatched = 0
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

// A push leaves the old socket and schedules running, so re-initialize once.
private void checkVersion(boolean reinit = true) {
    if (state.version == CODE_VERSION) return
    logVer "New version: ${CODE_VERSION} (was: ${state.version})"
    state.version = CODE_VERSION
    state.remove("intentionalDisconnect")
    state.remove('codeVersion')
    // Moved to @Field static in 1.9.2
    state.remove('processedEvents')
    state.remove('eventsThisMinute')
    state.remove('reconnectAttempts')
    if (reinit) runIn(1, "updated")
}

// ============================================================================
// WebSocket Connection Management
// ============================================================================

void connect() {
    logNet "Connecting to log event WebSocket..."

    // Cancel any pending scheduled reconnect to avoid duplicate connections
    unschedule("connect")

    try {
        INTENTIONAL_DISCONNECT.put(device.id.toString(), false)
        sendEvent(name: "connectionStatus", value: "connecting")

        String host = hubAddress ? "${hubAddress}" : "127.0.0.1:8080"
        String uri = "ws://${host}/logsocket"
        Map opts = [pingInterval: (pingInterval ?: 30).toInteger()]
        // A hub with hub security closes an unauthenticated logsocket right after the handshake
        // (2.5.3.107), loopback included. Log in on every connect so an expired session heals
        // on the next reconnect.
        if (hubUsername) {
            String cookie = login(host)
            if (cookie == null) {
                state.wsConnected = false
                sendEvent(name: "connectionStatus", value: "login failed")
                if (autoReconnect) scheduleReconnect()
                return
            }
            if (cookie) opts.headers = [Cookie: cookie]
        }
        interfaces.webSocket.connect(opts, uri)

        // Connection is async — webSocketStatus() will set connected state
        logNet "WebSocket connect initiated"
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
    logNet "Disconnecting WebSocket..."

    INTENTIONAL_DISCONNECT.put(device.id.toString(), true)
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
    int attempts = devCounter(RECONNECT_ATTEMPTS).incrementAndGet()

    // Exponential backoff: 5 s doubling to 160 s, then 300 s
    int delay = Math.min(300, 5 * (2 ** Math.min(attempts - 1, 6)))

    logInfo "Scheduling reconnect in ${delay}s (attempt ${attempts})"
    sendEvent(name: "connectionStatus", value: "reconnecting")

    runIn(delay, "connect")
}

void healthCheck() {
    if (!state.wsConnected && autoReconnect && !INTENTIONAL_DISCONNECT.get(device.id.toString())) {
        logWarn "WebSocket disconnected, attempting reconnect"
        scheduleReconnect()
        return
    }
    checkSilence()
}

private void checkSilence() {
    int mins = (silenceTimeout != null ? silenceTimeout : DEFAULT_SILENCE_MINS) as int
    String key = device.id as String
    if (mins <= 0 || !state.wsConnected || INTENTIONAL_DISCONNECT.get(key)) return
    Long last = LAST_MESSAGE.get(key)
    if (last == null) {
        // In-memory times are lost on a code push or reboot: start counting now.
        LAST_MESSAGE.put(key, now())
        return
    }
    long idle = (now() - last) / 60000L
    if (idle >= mins) {
        logInfo "No log line for ${idle} min; reconnecting"
        LAST_MESSAGE.put(key, now())
        reconnect()
    }
}

void resetRateLimitCounter() {
    int oldCount = devCounter(EVENTS_THIS_MINUTE).getAndSet(0)
    state.lastMinuteReset = now()
    state.rateLimitWarningShown = false

    if (oldCount > 0) {
        logDebug "Rate limit counter reset. Previous minute: ${oldCount} events"
    }

    // Snapshot in-memory counters to state for visibility in device UI
    state.totalLogsReceived = devCounter(LOGS_RECEIVED).get()
    state.eventsMatched = devCounter(EVENTS_MATCHED).get()
}

// ============================================================================
// WebSocket Event Handlers
// ============================================================================

// ============================================================================
// Hub Login
// ============================================================================

// The password is typed into a preference, then kept only encrypted. Clearing the username
// removes it.
private void storePassword() {
    String key = device.id as String
    if (!hubUsername) {
        state.remove("hubPasswordEnc")
        PASSWORD_CACHE.remove(key)
        device.removeSetting("hubPassword")
        return
    }
    if (!settings.hubPassword) return
    state.hubPasswordEnc = encrypt(settings.hubPassword as String)
    PASSWORD_CACHE.remove(key)
    device.removeSetting("hubPassword")
}

private String passwordValue() {
    if (settings.hubPassword) return settings.hubPassword as String
    String enc = state.hubPasswordEnc as String
    if (!enc) return null
    String key = device.id as String
    List<String> hit = PASSWORD_CACHE.get(key)
    if (hit && hit[0] == enc) return hit[1]
    try {
        String plain = decrypt(enc)
        PASSWORD_CACHE.put(key, [enc, plain])
        return plain
    } catch (Exception e) {
        logError "The stored hub password can't be read (${e.message}), as after a hub restore; enter it again"
        state.remove("hubPasswordEnc")
        PASSWORD_CACHE.remove(key)
        return null
    }
}

// Returns the HUBSESSION cookie, "" when the hub sets none (no hub security), or null when the
// login failed. Never logs the cookie or the password.
private String login(String host) {
    String pw = passwordValue()
    if (!pw) {
        logWarn "A hub username is set but no password is stored; enter the hub password"
        return null
    }
    String cookie = ""
    String location = null
    int status = 0
    try {
        httpPost([
            uri: "http://${host}",
            path: "/login",
            requestContentType: "application/x-www-form-urlencoded",
            body: [username: hubUsername, password: pw],
            textParser: true,
            timeout: 10
        ]) { resp ->
            status = resp.status
            resp.headers.each { h ->
                if (h.name.equalsIgnoreCase("Set-Cookie") && h.value.startsWith("HUBSESSION=")) {
                    cookie = h.value.tokenize(";")[0]
                } else if (h.name.equalsIgnoreCase("Location")) {
                    location = h.value
                }
            }
        }
    } catch (Exception e) {
        logWarn "Hub login to ${host} failed: ${e.message}"
        return null
    }
    logNet "Hub login: HTTP ${status}, redirect ${location}, session cookie ${cookie ? 'received' : 'not set'}"
    // Success is a redirect away from /login (302 to /, 2.5.3.107). A rejected login answers
    // 200 with the login page again, and still sets an unauthenticated session cookie.
    if (status < 300 || status >= 400 || location?.contains("/login")) {
        logWarn "Hub login to ${host} was rejected (HTTP ${status}); check the hub username and password, or clear the username if that hub has no hub security"
        return null
    }
    return cookie
}

void connectionStable() {
    devCounter(RECONNECT_ATTEMPTS).set(0)
    logNet "Connection stable for ${STABLE_SECS}s; reconnect backoff reset"
}

void webSocketStatus(String message) {
    checkVersion()
    logTrace "WebSocket status: ${message}"

    if (message.contains("failure") || message.contains("error")) {
        state.wsConnected = false
        unschedule("connectionStable")
        sendEvent(name: "connectionStatus", value: "error")
        logWarn "WebSocket error: ${message}"

        if (autoReconnect && !INTENTIONAL_DISCONNECT.get(device.id.toString())) {
            scheduleReconnect()
        }
    } else if (message.contains("status: open")) {
        state.wsConnected = true
        state.lastConnectionTime = now()
        LAST_MESSAGE.put(device.id as String, now())
        runIn(STABLE_SECS, "connectionStable")
        sendEvent(name: "connectionStatus", value: "connected")
        logInfo "WebSocket connected"
    } else if (message.contains("status: closing") || message.contains("status: closed")) {
        state.wsConnected = false
        unschedule("connectionStable")
        sendEvent(name: "connectionStatus", value: "disconnected")

        if (autoReconnect && !INTENTIONAL_DISCONNECT.get(device.id.toString())) {
            scheduleReconnect()
        }
    }
}

void parse(String message) {
    checkVersion()
    String devId = device.id.toString()
    devCounter(LOGS_RECEIVED).incrementAndGet()
    LAST_MESSAGE.put(device.id as String, now())

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

    ConcurrentHashMap<String, Long> seen = RECENT_EVENTS.computeIfAbsent(device.id.toString(),
        { String k -> new ConcurrentHashMap<String, Long>() } as java.util.function.Function)

    // Atomic check-and-insert: of two concurrent identical messages, exactly one wins
    // the putIfAbsent (or the replace of an expired entry) and the other is a duplicate.
    Long prev = seen.putIfAbsent(signature, ts)
    if (prev != null) {
        if ((ts - prev) < windowMs) return true
        if (!seen.replace(signature, prev, ts)) return true
    }

    pruneRecentEvents(seen, ts, windowMs)
    return false
}

// Drops expired entries, then the oldest ones beyond maxEventHistory. remove(key, value)
// leaves an entry alone if another thread has refreshed it meanwhile.
private void pruneRecentEvents(ConcurrentHashMap<String, Long> seen, long ts, long windowMs) {
    seen.each { String sig, Long seenAt ->
        if ((ts - seenAt) >= windowMs) seen.remove(sig, seenAt)
    }
    int maxHistory = maxEventHistory ?: 100
    int excess = seen.size() - maxHistory
    if (excess <= 0) return
    (seen.entrySet() as List).sort { it.value }.take(excess).each { seen.remove(it.key, it.value) }
}

private AtomicInteger devCounter(ConcurrentHashMap<String, AtomicInteger> counters) {
    return counters.computeIfAbsent(device.id.toString(),
        { String k -> new AtomicInteger() } as java.util.function.Function)
}

void triggerLogEvent(Map logEntry) {
    // Check rate limiting
    int eventsThisMinute = devCounter(EVENTS_THIS_MINUTE).incrementAndGet()

    // Warn at 80% of limit
    int warningThreshold = (maxEventsPerMinute * 0.8).toInteger()
    if (eventsThisMinute == warningThreshold && !state.rateLimitWarningShown) {
        logWarn "Approaching event rate limit: ${eventsThisMinute}/${maxEventsPerMinute} events this minute. Consider refining filters."
        state.rateLimitWarningShown = true
    }

    // Enforce limit
    if (eventsThisMinute > maxEventsPerMinute) {
        logWarn "Event rate limit exceeded (${maxEventsPerMinute}/min). Event suppressed: [${logEntry.type}/${logEntry.level}] ${logEntry.name}"
        return
    }

    int eventsMatched = devCounter(EVENTS_MATCHED).incrementAndGet()

    if (txtEnable) logInfo "Log event matched [${eventsMatched}]: [${logEntry.type}/${logEntry.level}] ${logEntry.name}: ${logEntry.msg}"

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
    String devId = device.id.toString()
    devCounter(EVENTS_MATCHED).set(0)
    devCounter(EVENTS_THIS_MINUTE).set(0)
    devCounter(LOGS_RECEIVED).set(0)
    RECENT_EVENTS.remove(devId)
    state.eventsMatched = 0
    state.totalLogsReceived = 0
    state.rateLimitWarningShown = false
    logCmd "Statistics cleared"
}

void logsOff() {
    logWarn "debug and trace logging disabled"
    device.updateSetting("enableDebug", [value: "false", type: "bool"])
    device.updateSetting("enableTrace", [value: "false", type: "bool"])
}

// ── Logging ───────────────────────────────────────────────────────────
//   ⬇️ Rx  ⬆️ Cmd  🔧 Cfg  🌐 Net  ⏰ Sched  📦 Ota  🏷️ Ver  ·  ⚠️ Warn  🛑 Error  🔬 Trace
private String logp(String e) { "${e} ${device.displayName}: " }

void logRx   (String m) { if (enableDebug) log.debug logp('⬇️') + m }
void logCmd  (String m) { if (txtEnable != false) log.info  logp('⬆️') + m }
void logCfg  (String m) { if (txtEnable != false) log.info  logp('🔧') + m }
void logNet  (String m) { if (enableDebug) log.debug logp('🌐') + m }
void logSched(String m) { if (enableDebug) log.debug logp('⏰') + m }
void logOta  (String m) { if (txtEnable != false) log.info  logp('📦') + m }
void logVer  (String m) { log.warn  logp('🏷️') + m }

void logWarn (String m) { log.warn  logp('⚠️') + m }
void logError(String m) { log.error logp('🛑') + m }
void logTrace(String m) { if (enableTrace) log.trace logp('🔬') + m }
void logInfo (String m) { if (txtEnable != false) log.info  "${device.displayName}: ${m}" }
void logDebug(String m) { if (enableDebug) log.debug "${device.displayName}: ${m}" }

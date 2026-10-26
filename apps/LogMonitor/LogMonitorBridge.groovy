// Copyright (c) 2025-2026 PJ
// SPDX-License-Identifier: MIT

/**
 * Log Monitor Bridge — WebSocket Bridge Driver
 *
 * Connects to the hub's logsocket and forwards every log entry to its
 * parent app (Log Monitor) via parent.processLogEntry(). No filtering
 * or processing — purely a transport bridge.
 */

import groovy.json.JsonSlurper
import groovy.transform.Field
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

@Field static final String CODE_VERSION = "1.0.5"
@Field static final int STARTUP_DELAY_SECS = 60
// Set by disconnect() so the socket-closed callback doesn't reconnect. Not atomicState: in a
// driver, a state write-back at method exit overwrites atomicState keys written after the
// method's first state access. False (the default) after a reboot is correct.
@Field static final ConcurrentHashMap<String, Boolean> INTENTIONAL_DISCONNECT = new ConcurrentHashMap<>()
// Keyed by device id: @Field static is shared by every bridge device of this type.
@Field static final ConcurrentHashMap<String, AtomicInteger> LOGS_RECEIVED = new ConcurrentHashMap<>()
// Reset only once a connection has stayed open STABLE_SECS: a hub that closes right after the
// handshake (a secured hub refusing an unauthenticated logsocket) must still back off.
@Field static final ConcurrentHashMap<String, AtomicInteger> RECONNECT_ATTEMPTS = new ConcurrentHashMap<>()
@Field static final int STABLE_SECS = 30
// Time of the last message received, keyed by device id. A socket can stay "open" and deliver
// nothing (seen for 36 h on a remote bridge), so a long silence triggers a reconnect.
@Field static final ConcurrentHashMap<String, Long> LAST_MESSAGE = new ConcurrentHashMap<>()
@Field static final int DEFAULT_SILENCE_MINS = 30
// Decrypted hub password, kept in memory only: decrypt() costs 350–830 ms. Keyed by device id;
// value [ciphertext, plaintext].
@Field static final ConcurrentHashMap<String, List<String>> PASSWORD_CACHE = new ConcurrentHashMap<>()

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
    }

    preferences {
        input name: "hubAddress", type: "text",
            title: "Hub IP address (leave blank for local hub)",
            required: false, description: "e.g., 192.168.1.100"
        input name: "hubUsername", type: "text",
            title: "Hub username (only for a hub with hub security; clear it to remove the stored password)",
            required: false
        input name: "hubPassword", type: "password",
            title: state.hubPasswordEnc ? "Hub password (stored, enter only to change it)" : "Hub password",
            required: false
        input name: "autoReconnect", type: "bool",
            title: "Auto-reconnect on disconnect", defaultValue: true
        input name: "silenceTimeout", type: "number",
            title: "Reconnect after this many minutes without a log line (0 = never)",
            defaultValue: DEFAULT_SILENCE_MINS, range: "0..1440"
        input name: "pingInterval", type: "number",
            title: "WebSocket ping interval (seconds)",
            defaultValue: 30, range: "10..300"
        input name: "txtEnable", type: "bool",
            title: "Enable info logging", defaultValue: true
        input name: "enableDebug", type: "bool",
            title: "Enable debug logging", defaultValue: false, submitOnChange: true
        input name: "enableTrace", type: "bool",
            title: "Enable trace logging (very verbose)", defaultValue: false
    }
}

// ============================================================================
// Lifecycle
// ============================================================================

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

void logsOff() {
    device.updateSetting("enableDebug", [value: "false", type: "bool"])
    device.updateSetting("enableTrace", [value: "false", type: "bool"])
    logWarn "Debug/trace logging auto-disabled"
}

void uninstalled() {
    logDebug "uninstalled()"
    disconnect()
    RECONNECT_ATTEMPTS.remove(device.id as String)
    PASSWORD_CACHE.remove(device.id as String)
    LAST_MESSAGE.remove(device.id as String)
}

void deviceTypeUpdated() {
    logDebug "driver change detected"
}

void initialize() {
    checkVersion(false)
    logDebug "initialize()"

    // Close any open socket first: connecting over an open one makes the old socket's close
    // callback schedule a reconnect, which closes the new one, and so on. The flag stays set
    // until connect() so that late callback is ignored.
    INTENTIONAL_DISCONNECT.put(device.id.toString(), true)
    try { interfaces.webSocket.close() } catch (Exception ignored) { }
    reconnectAttempts().set(0)
    logsReceived().set(0)

    atomicState.remove("logsReceived")
    state.remove("logsReceived")

    sendEvent(name: "connectionStatus", value: "initializing")

    runIn(location.hub.uptime < STARTUP_DELAY_SECS ? STARTUP_DELAY_SECS : 2, "connect")
    runEvery5Minutes("checkSilence")
}

private void checkVersion(boolean reinit = true) {
    if (state.version == CODE_VERSION) return
    logVer "New version: ${CODE_VERSION} (was: ${state.version})"
    state.version = CODE_VERSION
    state.remove("intentionalDisconnect")
    state.remove('codeVersion')
    state.remove("reconnectAttempts")
    if (reinit) runIn(1, "updated")
}

// ============================================================================
// WebSocket Connection Management
// ============================================================================

void connect() {
    logNet "Connecting to logsocket..."
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
    logNet "Manual reconnect triggered"
    disconnect()
    runIn(2, "connect")
}

private void scheduleReconnect() {
    int attempts = reconnectAttempts().incrementAndGet()

    int delay = Math.min(300, 5 * (2 ** Math.min(attempts - 1, 6)))
    logInfo "Scheduling reconnect in ${delay}s (attempt ${attempts})"
    sendEvent(name: "connectionStatus", value: "reconnecting")
    runIn(delay, "connect")
}

// ============================================================================
// WebSocket Event Handlers
// ============================================================================

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
    logsReceived().incrementAndGet()
    LAST_MESSAGE.put(device.id as String, now())

    try {
        Map logEntry = new JsonSlurper().parseText(message)

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
// Not a command: a parent calling a method that is also declared as a command gets null back.
int getLogsReceivedCount() {
    return logsReceived().get()
}

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

void checkSilence() {
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

void connectionStable() {
    reconnectAttempts().set(0)
    logNet "Connection stable for ${STABLE_SECS}s; reconnect backoff reset"
}

private AtomicInteger reconnectAttempts() {
    String key = device.id as String
    RECONNECT_ATTEMPTS.putIfAbsent(key, new AtomicInteger())
    return RECONNECT_ATTEMPTS.get(key)
}

private AtomicInteger logsReceived() {
    String key = device.id as String
    LOGS_RECEIVED.putIfAbsent(key, new AtomicInteger())
    return LOGS_RECEIVED.get(key)
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

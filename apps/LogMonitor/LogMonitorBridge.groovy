// Copyright (c) 2025-2026 PJ
// SPDX-License-Identifier: MIT

/**
 * Log Monitor Bridge — WebSocket Bridge Driver
 *
 * Connects to the hub's logsocket and forwards the log entries its parent app (Log Monitor)
 * can use via parent.processLogEntry(). A transport bridge: parse() only counts, drops frames
 * no filter wants (a substring check) and queues the rest; drain() does the JSON work once a
 * second, a bounded batch at a time.
 *
 * Why: /logsocket is one stream shared by every client, and a client that reads slowly
 * stalls it for all of them. parse() must therefore return at once.
 */

import groovy.json.JsonSlurper
import groovy.transform.Field
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

@Field static final String CODE_VERSION = "1.0.8"
@Field static final int STARTUP_DELAY_SECS = 60
// Set by disconnect() so the socket-closed callback doesn't reconnect. Not atomicState: in a
// driver, a state write-back at method exit overwrites atomicState keys written after the
// method's first state access. False (the default) after a reboot is correct.
@Field static final ConcurrentHashMap<String, Boolean> INTENTIONAL_DISCONNECT = new ConcurrentHashMap<>()
// When this driver last closed its own socket, keyed by device id. The close callback can arrive
// after connect() has cleared INTENTIONAL_DISCONNECT; treated as a drop, it schedules a connect
// that closes the new socket, whose callback schedules another, and so on (seen for hours).
@Field static final ConcurrentHashMap<String, Long> OWN_CLOSE_AT = new ConcurrentHashMap<>()
@Field static final long OWN_CLOSE_WINDOW_MS = 5000L
// Keyed by device id: @Field static is shared by every bridge device of this type.
@Field static final ConcurrentHashMap<String, AtomicInteger> LOGS_RECEIVED = new ConcurrentHashMap<>()
// Reset only once a connection has stayed open STABLE_SECS: a hub that closes right after the
// handshake (a secured hub refusing an unauthenticated logsocket) must still back off.
@Field static final ConcurrentHashMap<String, AtomicInteger> RECONNECT_ATTEMPTS = new ConcurrentHashMap<>()
@Field static final int STABLE_SECS = 30
@Field static final long QUICK_CLOSE_MS = 2000L   // a hub close this soon after open: likely auth
// Time of the last message received, keyed by device id. A socket can stay "open" and deliver
// nothing (seen for 36 h on a remote bridge), so a long silence triggers a reconnect.
@Field static final ConcurrentHashMap<String, Long> LAST_MESSAGE = new ConcurrentHashMap<>()
@Field static final int DEFAULT_SILENCE_MINS = 30
// Frames waiting for drain(), keyed by device id, and their count (ConcurrentLinkedQueue.size()
// walks the list, so parse() keeps its own counter).
@Field static final ConcurrentHashMap<String, ConcurrentLinkedQueue<String>> FRAMES = new ConcurrentHashMap<>()
@Field static final ConcurrentHashMap<String, AtomicInteger> FRAMES_QUEUED = new ConcurrentHashMap<>()
// Frames dropped because the queue was full, keyed by device id.
@Field static final ConcurrentHashMap<String, AtomicInteger> LOGS_DROPPED = new ConcurrentHashMap<>()
// "type/level" pairs the parent's filters want from this bridge, keyed by device id. Absent
// (after a push or reboot) means keep every frame until drain() has fetched it from the parent.
@Field static final ConcurrentHashMap<String, Set<String>> WANTED = new ConcurrentHashMap<>()
// When the queue first stood at the cap without leaving it, keyed by device id.
@Field static final ConcurrentHashMap<String, Long> FULL_SINCE = new ConcurrentHashMap<>()
// When drain() last ran, so the periodic check can restart a chain that died.
@Field static final ConcurrentHashMap<String, Long> DRAIN_LAST = new ConcurrentHashMap<>()
// The last "status: closing" seen, keyed by device id, to confirm disconnect() closed the socket.
@Field static final ConcurrentHashMap<String, Long> CLOSING_SEEN = new ConcurrentHashMap<>()
// Device ids whose code version has been checked since the last push: parse() runs per frame and
// reading state there costs a deserialization.
@Field static final ConcurrentHashMap<String, String> VERSION_CHECKED = new ConcurrentHashMap<>()
@Field static final int DRAIN_INTERVAL_MS = 1000
@Field static final int DRAIN_BATCH = 200            // frames handed to the parent per drain()
@Field static final long DRAIN_BUDGET_MS = 800L      // a drain() stops starting frames after this
@Field static final int QUEUE_CAP = 2000             // frames beyond this are dropped and counted
@Field static final long BACKLOG_LIMIT_MS = 30000L   // queue at the cap this long: reconnect
@Field static final int CLOSE_CONFIRM_SECS = 5
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
    unschedule()      // before disconnect(), which schedules its own close check
    disconnect()
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
    OWN_CLOSE_AT.remove(device.id as String)
    PASSWORD_CACHE.remove(device.id as String)
    LAST_MESSAGE.remove(device.id as String)
    [FRAMES, FRAMES_QUEUED, LOGS_DROPPED, WANTED, FULL_SINCE, DRAIN_LAST, CLOSING_SEEN, VERSION_CHECKED].each { it.remove(device.id as String) }
}

void deviceTypeUpdated() {
    logDebug "driver change detected"
}

void initialize() {
    checkVersion(false)
    logDebug "initialize()"

    // Close any open socket first: connecting over an open one makes the old socket's close
    // callback schedule a reconnect, which closes the new one, and so on. The flag stays set
    // until connect(), and OWN_CLOSE_AT covers a callback that comes after it.
    INTENTIONAL_DISCONNECT.put(device.id.toString(), true)
    OWN_CLOSE_AT.put(device.id.toString(), now())
    try { interfaces.webSocket.close() } catch (Exception ignored) { }
    state.wsConnected = false
    reconnectAttempts().set(0)
    logsReceived().set(0)
    logsDropped().set(0)
    clearFrames()
    WANTED.remove(device.id as String)   // fetched again from the parent by the next drain()

    atomicState.remove("logsReceived")
    state.remove("logsReceived")

    sendEvent(name: "connectionStatus", value: "initializing")

    runIn(location.hub.uptime < STARTUP_DELAY_SECS ? STARTUP_DELAY_SECS : 2, "connect")
    // Every minute, not every 5: the check also restarts a drain chain that died.
    runEvery1Minute("checkSilence")
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
    // A connect left over from before the socket opened: connecting again would close it.
    // reconnect() and initialize() clear wsConnected when they want a fresh socket.
    if (state.wsConnected) {
        logNet "Already connected; skipping the connect"
        return
    }

    try {
        INTENTIONAL_DISCONNECT.put(device.id.toString(), false)
        sendEvent(name: "connectionStatus", value: "connecting")

        String host = hubAddress ? "${hubAddress}" : "127.0.0.1:8080"
        String uri = "ws://${host}/logsocket"
        Map opts = [pingInterval: (pingInterval ?: 30).toInteger()]
        // A hub with hub security may close an unauthenticated logsocket right after the
        // handshake, loopback included. Log in on every connect so an expired session heals on
        // the next reconnect.
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
    OWN_CLOSE_AT.put(device.id.toString(), now())
    unschedule("connect")

    unschedule("drain")
    clearFrames()
    String key = device.id as String
    boolean wasConnected = state.wsConnected as boolean
    CLOSING_SEEN.remove(key)
    try {
        interfaces.webSocket.close()
        state.wsConnected = false
        sendEvent(name: "connectionStatus", value: "disconnected")
        logInfo "WebSocket disconnected"
        // A close that never completes keeps the hub's stream held for every client. The
        // platform reports "status: closing" after our own close(); no report is worth a warning.
        if (wasConnected) runIn(CLOSE_CONFIRM_SECS, "closeCheck")
    } catch (Exception e) {
        logError "Error disconnecting WebSocket: ${e.message}"
    }
}

void closeCheck() {
    if (CLOSING_SEEN.get(device.id as String) == null) {
        logWarn "No 'status: closing' ${CLOSE_CONFIRM_SECS} s after close(); the socket may still be open on the hub"
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
    if (message.contains("status: closing") || message.contains("status: closed")) {
        CLOSING_SEEN.put(device.id as String, now())
    }

    if (message.contains("failure") || message.contains("error")) {
        state.wsConnected = false
        unschedule("connectionStable")
        sendEvent(name: "connectionStatus", value: "error")
        logWarn "WebSocket error: ${message}"

        if (autoReconnect && !INTENTIONAL_DISCONNECT.get(device.id.toString())) {
            scheduleReconnect()
        }
    } else if (message.contains("status: open")) {
        // The old socket's close is reported before the new one opens; a close from here on
        // is this socket's (a secured hub can close right after the handshake).
        OWN_CLOSE_AT.remove(device.id.toString())
        unschedule("connect")
        state.wsConnected = true
        state.lastConnectionTime = now()
        LAST_MESSAGE.put(device.id as String, now())
        FULL_SINCE.remove(device.id as String)
        runInMillis(DRAIN_INTERVAL_MS, "drain")
        runIn(STABLE_SECS, "connectionStable")
        sendEvent(name: "connectionStatus", value: "connected")
        logInfo "WebSocket connected"
    } else if (message.contains("status: closing") || message.contains("status: closed")) {
        Long ownClose = OWN_CLOSE_AT.get(device.id.toString())
        if (ownClose != null && now() - ownClose < OWN_CLOSE_WINDOW_MS) {
            logNet "Ignoring the report of our own close (${message})"
            return
        }
        boolean wasOpen = state.wsConnected
        Long openedAt = state.lastConnectionTime as Long
        state.wsConnected = false
        unschedule("connectionStable")
        sendEvent(name: "connectionStatus", value: "disconnected")
        // A secured hub closes an unauthenticated logsocket right after the handshake; without
        // this the log shows only "connected" and a reconnect. Warn on the first close and
        // about hourly at the longest backoff.
        if (wasOpen && !hubUsername && openedAt != null && now() - openedAt < QUICK_CLOSE_MS
                && reconnectAttempts().get() % 12 == 0) {
            logWarn "The hub closed the log socket right after connecting; if it has hub security, set Hub username and Hub password"
        }

        if (autoReconnect && !INTENTIONAL_DISCONNECT.get(device.id.toString())) {
            scheduleReconnect()
        }
    }
}

// Minimum work: this runs on the thread that reads the socket, and a slow reader stalls the hub's
// stream for every client. No JSON, no unescaping, no call to the parent.
void parse(String message) {
    String key = device.id as String
    if (VERSION_CHECKED.get(key) != CODE_VERSION) {
        VERSION_CHECKED.put(key, CODE_VERSION)
        checkVersion()
    }
    logsReceived().incrementAndGet()
    LAST_MESSAGE.put(key, now())

    if (!wantedFrame(message, WANTED.get(key))) return
    if (!offerCapped(frames(), framesQueued(), QUEUE_CAP, message)) logsDropped().incrementAndGet()
}

// Called once a second while connected, by a runInMillis chain started when the socket opens.
// Takes a bounded batch off the queue and does what parse() used to do for each frame.
void drain() {
    String key = device.id as String
    try {
        DRAIN_LAST.put(key, now())
        if (WANTED.get(key) == null) fetchWanted()
        checkBacklog(key)
        long start = now()
        List<String> batch = takeBatch(frames(), framesQueued(), DRAIN_BATCH)
        for (int i = 0; i < batch.size(); i++) {
            processFrame(batch[i])
            if (now() - start > DRAIN_BUDGET_MS) {
                // Out of time: the rest goes back on the queue for the next turn.
                requeue(batch.subList(i + 1, batch.size()))
                break
            }
        }
    } catch (Exception e) {
        logDebug "Drain error: ${e.message}"
    } finally {
        // The chain runs while the socket is up; disconnect() and the close callbacks end it.
        if (state.wsConnected && !INTENTIONAL_DISCONNECT.get(key)) runInMillis(DRAIN_INTERVAL_MS, "drain")
    }
}

private void requeue(List<String> rest) {
    // Order across a rare overrun is not worth a deque: the rest goes behind what arrived meanwhile.
    rest.each { String f -> offerCapped(frames(), framesQueued(), QUEUE_CAP, f) }
}

private void processFrame(String message) {
    try {
        Map logEntry = new JsonSlurper().parseText(message)

        if (!logEntry?.type) return

        // Self-monitoring guard: skip own device logs. Ids repeat across hubs, so only a bridge on
        // the local hub (no hubAddress) can be reading its own device.
        if (logEntry.type == "dev" && logEntry.id?.toString() == device.id.toString() && !settings.hubAddress) return

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

// A queue that stays at the cap means the parent can't keep up with what the filters want.
// Reconnecting empties it and starts the stream over.
private void checkBacklog(String key) {
    Long since = FULL_SINCE.get(key)
    boolean full = framesQueued().get() >= QUEUE_CAP
    if (!full) {
        if (since != null) FULL_SINCE.remove(key)
        return
    }
    if (since == null) {
        FULL_SINCE.put(key, now())
    } else if (backlogExceeded(since, now(), BACKLOG_LIMIT_MS)) {
        FULL_SINCE.remove(key)
        logWarn "Backlog at ${QUEUE_CAP} frames for over ${(BACKLOG_LIMIT_MS / 1000) as int} s (${logsDropped().get()} dropped); reconnecting"
        reconnect()
    }
}

// The parent's wanted set for this bridge, fetched when the cache is empty. The parent also
// pushes a new one through setWanted() whenever its filters change.
private void fetchWanted() {
    try {
        List<String> w = parent?.bridgeWanted(device.deviceNetworkId) as List<String>
        if (w != null) WANTED.put(device.id as String, new HashSet<String>(w))
    } catch (Exception e) {
        logDebug "Could not fetch the wanted set: ${e.message}"
    }
}

// Called by the parent (not a command). null clears the cache; drain() fetches it again.
void setWanted(List<String> pairs) {
    if (pairs == null) WANTED.remove(device.id as String)
    else WANTED.put(device.id as String, new HashSet<String>(pairs))
}

private void clearFrames() {
    frames().clear()
    framesQueued().set(0)
    FULL_SINCE.remove(device.id as String)
}

// ============================================================================
// Pure helpers (extracted by tests/test-bridge-queue.groovy: no platform calls, no state)
// ============================================================================

// The value of a string field in a logsocket frame, without parsing it. A quote inside a value
// is escaped (\") or HTML-escaped (&quot;), so a '"key":"' in a message can't be mistaken for
// the field.
static String frameField(String frame, String key) {
    String marker = '"' + key + '":"'
    int i = frame.indexOf(marker)
    if (i < 0) return null
    int from = i + marker.length()
    int to = frame.indexOf('"', from)
    return to < 0 ? null : frame.substring(from, to)
}

// Whether a frame can match a filter: its "type/level" pair is in the wanted set. No set yet,
// a frame without both fields, or one of another type, is kept: drain() judges it.
static boolean wantedFrame(String frame, Set<String> wanted) {
    if (wanted == null) return true
    String type = frameField(frame, "type")
    String level = frameField(frame, "level")
    if (type == null || level == null) return true
    // A type the parent's filters have no switch for is judged by drain(), as matchesFilter does.
    if (type != "dev" && type != "app" && type != "sys") return true
    return wanted.contains(type + "/" + level)
}

// Adds a frame unless the queue holds cap of them; false when it was dropped. The counter is
// approximate under concurrent callers (one parse() and one drain() at a time in practice).
static boolean offerCapped(ConcurrentLinkedQueue<String> q, AtomicInteger depth, int cap, String frame) {
    if (depth.get() >= cap) return false
    q.add(frame)
    depth.incrementAndGet()
    return true
}

// Up to n frames from the head of the queue, oldest first.
static List<String> takeBatch(ConcurrentLinkedQueue<String> q, AtomicInteger depth, int n) {
    List<String> out = new ArrayList<String>()
    String f
    while (out.size() < n && (f = q.poll()) != null) {
        out.add(f)
        depth.decrementAndGet()
    }
    return out
}

static boolean backlogExceeded(Long fullSince, long nowMs, long limitMs) {
    return fullSince != null && nowMs - fullSince > limitMs
}

// End of pure helpers

/**
 * Returns the total count of logs received by this bridge.
 * Can be called by the parent app to display status without attribute overhead.
 */
// Not a command: a parent calling a method that is also declared as a command gets null back.
int getLogsReceivedCount() {
    return logsReceived().get()
}

// Frames dropped because the queue was full; the parent shows it next to the received count.
int getLogsDroppedCount() {
    return logsDropped().get()
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
    // Success is a redirect away from /login (302 to /). A rejected login answers
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
    if (!state.wsConnected || INTENTIONAL_DISCONNECT.get(key)) return
    // The drain chain is a runInMillis chain; if an execution was lost, start it again.
    Long drained = DRAIN_LAST.get(key)
    if (drained == null || now() - drained > 10 * DRAIN_INTERVAL_MS) {
        logInfo "Drain was not running; restarting it"
        runInMillis(DRAIN_INTERVAL_MS, "drain")
    }
    if (mins <= 0) return
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

private ConcurrentLinkedQueue<String> frames() {
    String key = device.id as String
    FRAMES.putIfAbsent(key, new ConcurrentLinkedQueue<String>())
    return FRAMES.get(key)
}

private AtomicInteger framesQueued() {
    String key = device.id as String
    FRAMES_QUEUED.putIfAbsent(key, new AtomicInteger())
    return FRAMES_QUEUED.get(key)
}

private AtomicInteger logsDropped() {
    String key = device.id as String
    LOGS_DROPPED.putIfAbsent(key, new AtomicInteger())
    return LOGS_DROPPED.get(key)
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

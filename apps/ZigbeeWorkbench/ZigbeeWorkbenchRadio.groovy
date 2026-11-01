// Copyright (c) 2025-2026 PJ
// SPDX-License-Identifier: MIT

/*
 *  Zigbee Workbench Radio: child driver of the Zigbee Workbench app.
 *
 *  Sends the `he raw` frames the app hands it, reads the hub's /zigbeeLogsocket over loopback
 *  and relays inbound frames from the addresses the app is waiting on. The socket is open only
 *  during a session: startSession opens it, a request from the app starts a session when none is
 *  running, and it closes when the session ends. Nothing connects at boot.
 */

import groovy.transform.CompileStatic
import groovy.transform.Field
import java.util.concurrent.ConcurrentHashMap

@Field static final String APP_NAME = "Zigbee Workbench"
@Field static final String CODE_VERSION = "0.1.0"

// Watch lists, keyed by device id. The driver is not single-threaded (see definition), so parse()
// reads the list from memory and never touches `state`: a driver method that reads `state` writes
// the whole store back when it ends, which could undo a concurrent setWatch. state.watch keeps a
// copy for after a code push or reboot.
@Field static final ConcurrentHashMap<String, List<String>> watchLists = new ConcurrentHashMap<>()

// Socket status, keyed by device id, set before each connect and in each status callback. The
// wsStatus attribute mirrors it for display, but its events can be stored out of order: a
// "connecting" event sent inside the app's setWatch call was stored after the "connected" event
// the socket callback sent 43 ms later, and stayed the current value while the socket was open.
@Field static final ConcurrentHashMap<String, String> socketStates = new ConcurrentHashMap<>()

// Device ids whose stored version and watch list have been checked since the last code push.
@Field static final ConcurrentHashMap<String, Boolean> versionChecked = new ConcurrentHashMap<>()

// Failed connects in a row, keyed by device id. Memory only, since parse() never touches state;
// a reboot or code push starts the count again.
@Field static final ConcurrentHashMap<String, Integer> dropCounts = new ConcurrentHashMap<>()

// Time of the radio's own last close, keyed by device id. The status report of a close can come
// after the next connect has started, so a drop reported within OWN_CLOSE_WINDOW_MS of our own
// close is that close, not a drop.
@Field static final ConcurrentHashMap<String, Long> ownCloseAt = new ConcurrentHashMap<>()

// When the session ends (epoch ms), keyed by device id; absent with no session. state.sessionEnds
// keeps a copy for after a code push, which clears this map and can leave the socket open.
@Field static final ConcurrentHashMap<String, Long> sessionEnds = new ConcurrentHashMap<>()

metadata {
    // Not singleThreaded: parse() calls parent.radioFrame(), which waits for the single-threaded
    // app, while the app calls sendFrames()/setWatch() here. With both gated, each waits for the
    // other until the platform gives up after 120 s.
    definition(name: "Zigbee Workbench Radio", namespace: "iamtrep", author: "pj",
               importUrl: "https://raw.githubusercontent.com/iamtrep/hubitat/main/apps/ZigbeeWorkbench/ZigbeeWorkbenchRadio.groovy") {
        capability "Initialize"

        command "startSession"
        command "endSession"

        attribute "wsStatus", "enum", ["disconnected", "connecting", "connected", "failed"]
        attribute "sessionEnds", "string"
    }

    preferences {
        input name: "sessionMinutes", type: "number", title: "<b>Session length (minutes)</b>",
              description: "The Zigbee log connection closes this long after the session starts.", range: "1..240", defaultValue: 30
        input name: "txtEnable", type: "bool", title: "<b>Enable info logging</b>", defaultValue: true
        input name: "debugEnable", type: "bool", title: "<b>Enable debug logging</b>",
              description: "Auto-disables after 30 minutes.", defaultValue: false
    }
}

// ==== Helpers begin ====

// Maps one /zigbeeLogsocket message to the frame the app wants; null unless it is a zigbeeRx
// frame with a payload from a watched address.
@CompileStatic
static Map toFrame(Map msg, Set<String> watch) {
    if (msg == null || watch == null || watch.isEmpty()) return null
    if (msg.type?.toString() != "zigbeeRx") return null
    if (!(msg.payload instanceof List)) return null
    if (!(msg.id instanceof Number)) return null
    String addr = String.format("%04X", ((Number) msg.id).intValue() & 0xFFFF)
    if (!watch.contains(addr)) return null
    return [addr: addr, clusterId: msg.clusterId, profileId: msg.profileId,
            srcEp: msg.sourceEndpoint, dstEp: msg.destinationEndpoint,
            payload: msg.payload, lqi: msg.lastHopLqi, rssi: msg.lastHopRssi, time: msg.time]
}

// The hub address of a /zigbeeLogsocket message, read without parsing the JSON, or null when the
// message does not have the expected form (the caller then parses it). The hub writes
// {"name":"...","id":49669,...}; `deviceId` does not match, its I is upper case.
@CompileStatic
static String frameAddr(String json) {
    if (json == null) return null
    int i = json.indexOf('"id":')
    if (i < 0) return null
    int p = i + 5
    int end = p
    while (end < json.length() && Character.isDigit(json.charAt(end))) end++
    if (end == p || end - p > 5 || end >= json.length() || json.charAt(end) != (char) ',') return null
    return String.format("%04X", Integer.parseInt(json.substring(p, end)) & 0xFFFF)
}

// Whether a message needs parsing. Most messages on a busy hub come from unwatched addresses, and
// the JSON parse is the driver's largest cost per message. A null watch list (not restored yet)
// parses everything.
@CompileStatic
static boolean wantsMessage(String json, Set<String> watch) {
    if (watch == null) return true
    if (watch.isEmpty()) return false
    String addr = frameAddr(json)
    return addr == null || watch.contains(addr)
}

// Session length in minutes: the preference within 1..240, 30 otherwise.
@Field static final int SESSION_MINUTES = 30

@CompileStatic
static int sessionLength(Object setting) {
    String v = setting?.toString()?.trim()
    if (!v || !v.isInteger()) return SESSION_MINUTES
    int m = v.toInteger()
    return m >= 1 && m <= 240 ? m : SESSION_MINUTES
}

@CompileStatic
static boolean sessionActive(Long endsAt, long now) {
    return endsAt != null && endsAt.longValue() > now
}

// Milliseconds to wait before a connect, so that it follows the radio's own last close by at least
// CONNECT_GAP_MS. A connect soon after a close once left a socket that reported open and delivered
// nothing, and a late report of the close would be taken for a drop of the new socket.
@Field static final long CONNECT_GAP_MS = 5000L

@CompileStatic
static long connectDelayMs(Long closedAt, long now) {
    if (closedAt == null) return 100L
    return Math.max(100L, closedAt.longValue() + CONNECT_GAP_MS - now)
}

// What a session's end does: "close", or "hold" while the app still waits on replies, up to
// MAX_HOLDS checks HOLD_S apart. A null watch list is unknown after a code push: it does not hold.
@Field static final int HOLD_S = 30
@Field static final int MAX_HOLDS = 30

@CompileStatic
static String sessionEndDecision(Collection<String> watch, int holds) {
    return watch && holds < MAX_HOLDS ? "hold" : "close"
}

// Seconds before the next connect: RECONNECT_S after a first drop, doubling with each failed
// connect in a row up to RECONNECT_MAX_S. The count resets on the first message after a connect,
// not on open: a socket that opens and drops at once still backs off.
@Field static final int RECONNECT_S = 10
@Field static final int RECONNECT_MAX_S = 300
// The drop in a row that logs a warning; the others log at debug level.
@Field static final int DROPS_BEFORE_WARN = 3

@CompileStatic
static int reconnectDelay(int drops) {
    if (drops <= 1) return RECONNECT_S
    long d = (long) RECONNECT_S << Math.min(drops - 1, 20)
    return (int) Math.min(d, (long) RECONNECT_MAX_S)
}

// What a webSocketStatus string means: "open", "drop" (failure or closed: reconnect during a
// session, whether or not anything is watched) or "none".
@CompileStatic
static String socketDecision(String status) {
    String s = status?.trim()?.toLowerCase()
    if (!s) return "none"
    if (s == "status: open" || s == "open") return "open"
    if (s.startsWith("failure") || s == "status: closed" || s == "closed") return "drop"
    return "none"
}

@Field static final long OWN_CLOSE_WINDOW_MS = 5000L

// Whether a status report is the late report of the radio's own close.
@CompileStatic
static boolean isOwnClose(String status, Long closedAt, long now) {
    return socketDecision(status) == "drop" && closedAt != null && now - closedAt.longValue() < OWN_CLOSE_WINDOW_MS
}

// ==== Helpers end ====

// LIFECYCLE

void installed() {
    logCfg "installed v${CODE_VERSION}"
    state.watch = []
    initialize()
}

void updated() {
    logCfg "updated"
    unschedule()
    Long ends = sessionEnds[device.id.toString()]
    disconnectSocket()
    if (debugEnable) runIn(1800, "logsOff", [overwrite: true])
    if (sessionActive(ends, now())) {
        scheduleSessionEnd(ends)
        queueConnect()
    }
}

void initialize() {
    logCfg "initialize v${CODE_VERSION}"
    if (state.version != CODE_VERSION) {
        logVer "New version: ${CODE_VERSION} (was: ${state.version})"
        state.version = CODE_VERSION
    }
    if (settings?.debugEnable) runIn(1800, "logsOff", [overwrite: true])
    // Runs at boot through the Initialize capability: no socket survives a reboot, and none opens
    // until the next session.
    String id = device.id.toString()
    dropCounts.remove(id)
    unschedule("connectSocket")
    unschedule("sessionTimeout")
    sessionEnds.remove(id)
    state.remove("sessionEnds")
    setStatus("disconnected")
    sendEvent(name: "sessionEnds", value: "none")
}

void uninstalled() {
    disconnectSocket()
    String id = device.id.toString()
    [watchLists, versionChecked, socketStates, ownCloseAt, dropCounts, sessionEnds].each { it.remove(id) }
}

void logsOff() {
    logWarn "debug logging disabled"
    device.updateSetting("debugEnable", [value: "false", type: "bool"])
}

// SESSION

// Starts a session, or extends a running one to a full length from now.
void startSession() {
    String id = device.id.toString()
    long ends = now() + sessionLength(settings?.sessionMinutes) * 60000L
    sessionEnds[id] = ends
    state.sessionEnds = ends
    scheduleSessionEnd(ends)
    logCfg "session runs until ${new Date(ends)}"
    String st = socketStates[id]
    if (st == "connected" || st == "connecting") return
    // unknown after a code push, when a socket may still be open: never connect over it
    if (st == null) disconnectSocket()
    dropCounts.remove(id)
    setStatus("connecting")
    queueConnect()
}

// For a request from the app: starts a session when none is running, and leaves a running one alone.
void ensureSession() {
    if (!sessionActive(sessionEnds[device.id.toString()], now())) startSession()
}

// In the radio's own run, since it tells the app about requests still waiting.
void endSession() {
    runInMillis(10, "endSessionNow", [overwrite: true])
}

void endSessionNow() {
    closeSession("Session ended")
}

// When the session ends, epoch ms, or null. For the app's pages.
Long sessionEnd() {
    Long ends = sessionEnds[device.id.toString()]
    return sessionActive(ends, now()) ? ends : null
}

private void scheduleSessionEnd(long ends) {
    runIn(Math.max(1L, (ends - now()).intdiv(1000L)) as int, "sessionTimeout", [overwrite: true])
    sendEvent(name: "sessionEnds", value: new Date(ends).format("yyyy-MM-dd'T'HH:mm:ssXXX", location.timeZone ?: TimeZone.getTimeZone("UTC")))
}

void sessionTimeout(Map data = null) {
    // an extension moved the end and rescheduled this job; a job already running sees that here
    if (sessionActive(sessionEnds[device.id.toString()], now() + 1000L)) return
    int holds = ((data?.holds ?: 0) as int)
    if (sessionEndDecision(watchLists[device.id.toString()], holds) == "hold") {
        logNet "session over, requests still waiting (check ${holds + 1})"
        runIn(HOLD_S, "sessionTimeout", [overwrite: true, data: [holds: holds + 1]])
        return
    }
    closeSession("Session timed out")
}

private void closeSession(String reason) {
    String id = device.id.toString()
    unschedule("sessionTimeout")
    sessionEnds.remove(id)
    state.remove("sessionEnds")
    disconnectSocket()
    sendEvent(name: "sessionEnds", value: "none")
    logCfg reason.toLowerCase()
    Set<String> w = watchSet()
    if (w != null && w.isEmpty()) return
    try {
        parent?.radioDown(reason)
    } catch (Exception e) {
        logWarn "radioDown failed: ${e.message}"
    }
}

// COMMANDS FROM THE APP

void sendFrames(List<String> cmds) {
    if (!cmds) return
    logCmd "sending ${cmds.size()} frame(s)"
    logDebug "frames: ${cmds}"
    sendHubCommand(new hubitat.device.HubMultiAction(cmds, hubitat.device.Protocol.ZIGBEE))
}

void setWatch(List<String> addrs) {
    List<String> list = (addrs ?: []).collect { it.toString().trim().toUpperCase() }.findAll { it }.unique()
    watchLists[device.id.toString()] = list
    state.watch = list
    logCfg "watching ${list}"
}

// SOCKET

// The socket status the app reads before sending (see socketStates).
String socketStatus() {
    return socketStates[device.id.toString()] ?: device.currentValue("wsStatus")?.toString()
}

private void setStatus(String v) {
    socketStates[device.id.toString()] = v
    sendEvent(name: "wsStatus", value: v)
}

private void queueConnect() {
    runInMillis(connectDelayMs(ownCloseAt[device.id.toString()], now()), "connectSocket", [overwrite: true])
}

void connectSocket() {
    if (!sessionActive(sessionEnds[device.id.toString()], now())) {
        if (socketStates[device.id.toString()] != "disconnected") setStatus("disconnected")
        return
    }
    setStatus("connecting")
    logNet "connecting to ws://127.0.0.1:8080/zigbeeLogsocket"
    try {
        interfaces.webSocket.connect("ws://127.0.0.1:8080/zigbeeLogsocket")
    } catch (Exception e) {
        setStatus("failed")
        handleDrop("Lost the Zigbee log connection", "connect failed: ${e.message}")
    }
}

void disconnectSocket() {
    unschedule("connectSocket")
    ownCloseAt[device.id.toString()] = now()
    if (socketStatus() != "disconnected") setStatus("disconnected")
    try {
        interfaces.webSocket.close()
    } catch (Exception e) {
        logNet "close exception: ${e.message}"
    }
}

void webSocketStatus(String status) {
    logNet "webSocketStatus: ${status}"
    String action = socketDecision(status)
    if (action == "open") {
        setStatus("connected")
        unschedule("connectSocket")
        // the app holds requests until the socket opens
        Set<String> watching = watchSet()
        if (watching) {
            try {
                parent?.radioUp()
            } catch (Exception e) {
                logWarn "radioUp failed: ${e.message}"
            }
        }
        return
    }
    if (action == "none") return
    if (isOwnClose(status, ownCloseAt[device.id.toString()], now())) {
        logNet "ignoring the report of our own close (${status})"
        return
    }
    if (!sessionActive(sessionEnds[device.id.toString()], now())) {
        setStatus("disconnected")
        return
    }
    setStatus("failed")
    handleDrop("Lost the Zigbee log connection", "socket dropped (${status})")
}

// Reconnects after reconnectDelay(), within a session. The app hears of it only when it is waiting on replies. Runs in the
// radio's own executions (socket callbacks, connectSocket from initialize or a scheduled retry),
// never inside an app call: setWatch and sendFrames no longer touch the socket.
private void handleDrop(String reason, String detail) {
    String id = device.id.toString()
    int drops = ((dropCounts[id] ?: 0) as int) + 1
    dropCounts[id] = drops
    int delay = reconnectDelay(drops)
    String m = "${detail}; reconnecting in ${delay} s (attempt ${drops})"
    if (drops == DROPS_BEFORE_WARN) logWarn m + "; later attempts log at debug level"
    else if (drops < DROPS_BEFORE_WARN) logCfg m
    else logNet m
    runIn(delay, "connectSocket", [overwrite: true])
    // unknown after a code push: tell the app anyway, which does nothing when nothing is pending
    Set<String> w = watchSet()
    if (w != null && w.isEmpty()) return
    try {
        parent?.radioDown(reason)
    } catch (Exception e) {
        logWarn "radioDown failed: ${e.message}"
    }
}

void parse(String description) {
    // parse() never reads state (see watchLists): the stored version and watch list are checked
    // once, in a run of their own
    if (versionChecked.putIfAbsent(device.id.toString(), true) == null) runInMillis(50, "afterReload")
    // The first message after a connect ends the backoff.
    Integer drops = dropCounts.remove(device.id.toString())
    if (drops != null && drops >= DROPS_BEFORE_WARN) logCfg "reconnected after ${drops} failed attempts"
    if (!wantsMessage(description, watchSet())) return
    String trimmed = description?.trim()
    if (!trimmed?.startsWith("{")) return
    Map msg
    try {
        msg = parseJson(trimmed) as Map
    } catch (Exception e) {
        logDebug "parse: JSON error: ${e.message}"
        return
    }
    Map frame = toFrame(msg, watchSet() ?: ([] as Set<String>))
    if (frame == null) return
    logRx "frame from ${frame.addr} cluster ${frame.clusterId}"
    parent?.radioFrame(frame)
}

// The watch list from memory; null until afterReload() has restored it after a code push.
private Set<String> watchSet() {
    List<String> list = watchLists[device.id.toString()]
    return list == null ? null : (list as Set<String>)
}

// After a reboot or code push: restore the watch list from state unless the app has set one since
// (then store that one, so this run's write of state carries it), and rerun updated() after a
// version change (it closes the open socket and reopens it during a session). This run happens only
// when the socket delivers a message, so it is also where a socket left open past its session closes.
void afterReload() {
    String id = device.id.toString()
    if (watchLists[id] == null) watchLists[id] = ((state.watch ?: []) as List).collect { it.toString() }
    else state.watch = watchLists[id]
    Long stored = state.sessionEnds as Long
    if (sessionEnds[id] == null && sessionActive(stored, now())) sessionEnds[id] = stored
    if (!sessionActive(sessionEnds[id], now())) {
        logCfg "no session is running: closing the socket"
        closeSession("Session ended")
        return
    }
    if (state.version != CODE_VERSION) updated()
}

// LOGGING

private String logp(String e) { "${e} ${device.displayName}: " }
void logRx(String m)    { if (debugEnable) log.debug logp("⬇️") + m }
void logCmd(String m)   { if (txtEnable != false) log.info logp("⬆️") + m }
void logCfg(String m)   { if (txtEnable != false) log.info logp("🔧") + m }
void logNet(String m)   { if (debugEnable) log.debug logp("🌐") + m }
void logVer(String m)   { log.warn logp("🏷️") + m }
void logWarn(String m)  { log.warn logp("⚠️") + m }
void logDebug(String m) { if (debugEnable) log.debug "${device.displayName}: ${m}" }

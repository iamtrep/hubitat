// Copyright (c) 2025-2026 PJ
// SPDX-License-Identifier: MIT

/*
 *  Zigbee Workbench Radio: child driver of the Zigbee Workbench app.
 *
 *  Sends the `he raw` frames the app hands it, reads the hub's /zigbeeLogsocket over loopback
 *  and relays inbound frames from the addresses the app is waiting on. The socket stays open:
 *  it connects in initialize() and reconnects after any drop; the watch list is only a filter.
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

// Time of the radio's own last close, keyed by device id. The status report of a close can come
// after the next connect has started, so a drop reported within OWN_CLOSE_WINDOW_MS of our own
// close is that close, not a drop.
@Field static final ConcurrentHashMap<String, Long> ownCloseAt = new ConcurrentHashMap<>()

metadata {
    // Not singleThreaded: parse() calls parent.radioFrame(), which waits for the single-threaded
    // app, while the app calls sendFrames()/setWatch() here. With both gated, each waits for the
    // other until the platform gives up after 120 s.
    definition(name: "Zigbee Workbench Radio", namespace: "iamtrep", author: "pj",
               importUrl: "https://raw.githubusercontent.com/iamtrep/hubitat/main/apps/ZigbeeWorkbench/ZigbeeWorkbenchRadio.groovy") {
        capability "Initialize"

        attribute "wsStatus", "enum", ["disconnected", "connecting", "connected", "failed"]
    }

    preferences {
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

// Seconds between a drop and the next connect.
@Field static final int RECONNECT_S = 10

// What a webSocketStatus string means: "open", "drop" (failure or closed: reconnect, whether or not
// anything is watched) or "none".
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
    disconnectSocket()
    initialize()
}

void initialize() {
    logCfg "initialize v${CODE_VERSION}"
    if (state.version != CODE_VERSION) {
        logVer "New version: ${CODE_VERSION} (was: ${state.version})"
        state.version = CODE_VERSION
    }
    if (settings?.debugEnable) runIn(1800, "logsOff", [overwrite: true])
    // Idempotent: a socket that is open or about to connect is left alone. Two connects close
    // together once left the status "connected" on a socket that delivered no frames. The memory
    // map is empty after a reboot or code push, so the socket is opened then. updated() closes
    // first, so it always reconnects.
    String st = socketStates[device.id.toString()]
    if (st == "connected" || st == "connecting") return
    setStatus("connecting")
    runIn(2, "connectSocket", [overwrite: true])
}

void uninstalled() {
    disconnectSocket()
    String id = device.id.toString()
    [watchLists, versionChecked, socketStates, ownCloseAt].each { it.remove(id) }
}

void logsOff() {
    logWarn "debug logging disabled"
    device.updateSetting("debugEnable", [value: "false", type: "bool"])
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

// The status from memory only: null after a reboot or code push, when only the attribute is left.
String socketKnownStatus() {
    return socketStates[device.id.toString()]
}

// Closes and reopens the socket in the radio's own run, for the app to call when the status it
// reads may be stale. Closing first means no second connect over a socket that is still open.
void reopen() {
    runInMillis(10, "reopenNow", [overwrite: true])
}

void reopenNow() {
    disconnectSocket()
    initialize()
}

private void setStatus(String v) {
    socketStates[device.id.toString()] = v
    sendEvent(name: "wsStatus", value: v)
}

void connectSocket() {
    setStatus("connecting")
    logNet "connecting to ws://127.0.0.1:8080/zigbeeLogsocket"
    try {
        interfaces.webSocket.connect("ws://127.0.0.1:8080/zigbeeLogsocket")
    } catch (Exception e) {
        setStatus("failed")
        logWarn "connect failed: ${e.message}"
        handleDrop("Lost the Zigbee log connection")
    }
}

// Only updated() and uninstalled() close the socket; updated() reopens it through initialize().
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
        return
    }
    if (action == "none") return
    if (isOwnClose(status, ownCloseAt[device.id.toString()], now())) {
        logNet "ignoring the report of our own close (${status})"
        return
    }
    setStatus("failed")
    logWarn "socket dropped (${status}); reconnecting in ${RECONNECT_S} s"
    handleDrop("Lost the Zigbee log connection")
}

// Reconnects after RECONNECT_S. The app hears of it only when it is waiting on replies. Runs in the
// radio's own executions (socket callbacks, connectSocket from initialize or a scheduled retry),
// never inside an app call: setWatch and sendFrames no longer touch the socket.
private void handleDrop(String reason) {
    runIn(RECONNECT_S, "connectSocket", [overwrite: true])
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
// version change (it closes the open socket before initialize() reopens it).
void afterReload() {
    String id = device.id.toString()
    if (watchLists[id] == null) watchLists[id] = ((state.watch ?: []) as List).collect { it.toString() }
    else state.watch = watchLists[id]
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

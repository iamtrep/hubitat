// Copyright (c) 2026 PJ
// SPDX-License-Identifier: MIT

/*
 Hubitat Elevation driver for Google TV / Android TV over the LAN, using the Android TV Remote
 protocol v2 (TLS on 6467 for pairing, 6466 for the remote session). No bridge.

 Pairing: Start Pairing, read the code shown on the TV, enter it with Submit Pairing Code.
 Protocol reference: https://github.com/tronikos/androidtvremote2
 */

metadata {
    definition (
        name: "Android TV Remote",
        namespace: "iamtrep",
        author: "pj",
        description: "Google TV / Android TV remote control over the LAN (Android TV Remote protocol v2)",
        importUrl: "https://raw.githubusercontent.com/iamtrep/hubitat/refs/heads/main/drivers/androidtv/AndroidTVRemote.groovy",
        singleThreaded: true
    )
    {
        capability "Actuator"
        capability "AudioVolume"
        capability "Initialize"
        capability "MediaTransport"
        capability "Switch"

        command "startPairing"
        command "submitPairingCode", [[name: "code", type: "STRING", description: "6 characters shown on the TV"]]
        command "forgetPairing"
        command "findTvs"
        command "connect"
        command "disconnect"
        command "sendKey", [[name: "key", type: "STRING", description: "Key name (HOME, BACK, DPAD_UP, MEDIA_PLAY_PAUSE...) or Android key code number"]]
        command "launchApp", [[name: "appLink", type: "STRING", description: "A link the app handles, such as https://www.youtube.com. Package names no longer launch apps on most TVs"]]
        command "sendText", [[name: "text", type: "STRING", description: "Text for the text field that has focus on the TV, such as a search box"]]

        attribute "networkStatus", "enum", ["online", "offline"]
        attribute "pairing", "enum", ["unpaired", "pairing", "awaiting code", "paired"]
        attribute "currentApp", "string"
    }

    preferences {
        input "tvIP", "string", title: "TV IP address (leave blank to find the TV on the network)", required: false
        if ((state?.foundTvs as Map)?.size() > 1) {
            input name: "tvChoice", type: "enum", title: "TV", options: state.foundTvs
        }
        input name: "imeEnable", type: "bool", title: "Use the TV keyboard link (current app and Send Text)",
            description: "Turn off if the TV shows \"use your mobile device's keyboard\" instead of its on-screen keyboard", defaultValue: true
        input name: "txtEnable", type: "bool", title: "Enable info logging", defaultValue: true
        input name: "debugEnable", type: "bool", title: "Enable debug logging", defaultValue: false
        if (debugEnable) {
            input name: "traceEnable", type: "bool", title: "Enable trace logging (decoded protocol messages)", defaultValue: false
        }
    }
}

import groovy.transform.Field
import hubitat.helper.HexUtils
import hubitat.helper.NetworkUtils
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.cert.CertificateFactory
import java.util.concurrent.ConcurrentHashMap

@Field static final String CODE_VERSION = "0.4.0"
@Field static final int DEBUG_LOG_TIMEOUT = 1800
@Field static final int PAIRING_PORT = 6467
@Field static final String MDNS_SERVICE = "_androidtvremote2._tcp"
@Field static final int SEARCH_TIMEOUT_MS = 5000
@Field static final long REPAIR_SEARCH_MS = 300000L     // at most one search per 5 minutes while the TV is unreachable
@Field static final long ID_SEARCH_MS = 86400000L       // at most one search a day to learn the TV of a typed address
@Field static final int REMOTE_PORT = 6466
@Field static final int WATCHDOG_SECONDS = 30
@Field static final long IDLE_LIMIT_MS = 20000      // the TV pings every 5 s
@Field static final int MAX_BACKOFF_SECONDS = 300
@Field static final int CODE_TIMEOUT_SECONDS = 300
@Field static final long VOLUME_ANSWER_MS = 3000      // a volume message this soon after a volume key answers it

// Feature bits exchanged in remote_configure / remote_set_active
@Field static final int FEATURE_PING = 1
@Field static final int FEATURE_KEY = 2
@Field static final int FEATURE_IME = 4
@Field static final int FEATURE_POWER = 32
@Field static final int FEATURE_VOLUME = 64
@Field static final int FEATURE_APP_LINK = 512
@Field static final int ACTIVE_FEATURES = 1 | 2 | 4 | 32 | 64 | 512     // PING, KEY, IME, POWER, VOLUME, APP_LINK

@Field static final int DIRECTION_SHORT = 3

@Field static final Map<String, Integer> KEYCODES = [
    HOME: 3, BACK: 4, DPAD_UP: 19, DPAD_DOWN: 20, DPAD_LEFT: 21, DPAD_RIGHT: 22, DPAD_CENTER: 23,
    VOLUME_UP: 24, VOLUME_DOWN: 25, POWER: 26, ENTER: 66, MENU: 82, SEARCH: 84,
    MEDIA_PLAY_PAUSE: 85, MEDIA_STOP: 86, MEDIA_NEXT: 87, MEDIA_PREVIOUS: 88, MEDIA_REWIND: 89,
    MEDIA_FAST_FORWARD: 90, MEDIA_PLAY: 126, MEDIA_PAUSE: 127, VOLUME_MUTE: 164, INFO: 165,
    CHANNEL_UP: 166, CHANNEL_DOWN: 167, GUIDE: 172, CAPTIONS: 175, SETTINGS: 176, TV_INPUT: 178,
    SLEEP: 223, WAKEUP: 224
]

// Per-device connection state. Kept out of `state`: parse() can run before a command's state
// writes are committed, and the receive buffer changes on every chunk.
@Field static final Map<Long, Map> RUNTIME = new ConcurrentHashMap<Long, Map>()
// supported: false once rawSocket.getPeerCertificate() is found missing (firmware before 2.5.3).
@Field static final Map<String, Boolean> PEER_CERT = new ConcurrentHashMap<String, Boolean>()

Map rt() {
    Map r = RUNTIME.get(device.idAsLong)
    if (r == null) {
        // fresh: no connect or pairing since this entry was made. New driver code empties RUNTIME,
        // so a fresh entry seen by a running watchdog means an update dropped the session.
        r = [mode: null, buf: "", lastRx: 0L, closing: false, gotMessage: false, fresh: true]
        RUNTIME.put(device.idAsLong, r)
    }
    return r
}


// ── Lifecycle ─────────────────────────────────────────────────────────

void installed() {
    logDebug "Installed"
    sendEvent(name: "pairing", value: "unpaired")
    sendEvent(name: "networkStatus", value: "offline")
}

void updated() {
    logCfg "Preferences saved (version ${CODE_VERSION})"
    if (debugEnable || traceEnable) runIn(DEBUG_LOG_TIMEOUT, "logsOff")
    // Before the TV settings: a pick schedules its own connect, which disconnect() would cancel
    disconnect()
    applyTvSettings()
    initialize()
}

// A TV picked from the list, a typed IP, or a blank IP (search).
private void applyTvSettings() {
    if (tvChoice) {
        Map e = (state.foundTvEntries as List)?.find { it.id == tvChoice }
        device.removeSetting("tvChoice")
        clearFoundTvs()
        if (!e) { logWarn "That TV is no longer in the list; run Find TVs"; return }
        String address = addressFor(e, tvIP as String)
        logInfo "Using the TV at ${address}"
        takeTv(e, address, false)
        return
    }
    if (tvIP) {
        clearFoundTvs()
        // A typed address may be another TV: learn which one again on the next connection
        if (tvIP != state.lastTvIP) {
            state.remove("tvId")
            state.remove("tvName")
            state.lastTvIP = tvIP
        }
    } else {
        runIn(1, "runSetupSearch", [data: [pair: false]])
    }
}

// Points the device at this TV. A paired device that switches to another TV forgets the pairing.
// connect reads tvIP in its own execution, after the setting written here is saved.
private void takeTv(Map e, String address, boolean pair) {
    if (state.paired && differentTv(e, state.tvId as String, tvIP as String)) {
        forgetPairing()
        logInfo "This is a different TV; pair it with Start Pairing"
    }
    applyTv(e, address)
    if (state.paired) runIn(2, "connect")
    else if (pair) runIn(1, "startPairing")
}

void initialize() {
    device.updateDataValue("driverVersion", CODE_VERSION)
    state.retry = 0
    if (state.paired && tvIP) runIn(2, "connect")
}

void logsOff() {
    logWarn "Debug and trace logging turned off"
    device.updateSetting("debugEnable", [value: "false", type: "bool"])
    device.updateSetting("traceEnable", [value: "false", type: "bool"])
}


// ── TV discovery ──────────────────────────────────────────────────────

// One TV from an mDNS result: its id is the `bt` TXT record, which stays the same when the IP changes.
// Addresses come as a List (getMDNSServiceResults) or a comma-separated String (getMDNSEntries).
Map tvEntry(Map r) {
    String id = (r?.textRecords instanceof Map ? r.textRecords.bt : null)?.toString()?.trim()?.toUpperCase()
    def v4 = r?.ip4Addresses
    List<String> ips = (v4 instanceof Collection ? v4 : v4?.toString()?.split(",") as List)
        ?.collect { it?.toString()?.trim() }?.findAll { it } ?: []
    if (!id || !ips) return null
    String name = r.name?.toString() ?: id
    return [id: id, name: name, ips: ips, label: "${name} (${ips[0]})".toString()]
}

List<Map> tvEntries(List results) {
    Map<String, Map> byId = [:]
    (results ?: []).each { r ->
        Map e = tvEntry(r as Map)
        if (!e) return
        Map seen = byId[e.id]
        if (seen) seen.ips = (seen.ips + e.ips).unique()
        else byId[e.id] = e
    }
    return byId.values() as List
}

Map entryWithId(List<Map> entries, String id) {
    entries?.find { it.id == id?.toUpperCase() }
}

// The one TV advertising this address; null when none does, or several do (some TVs advertise others' addresses).
Map entryWithAddress(List<Map> entries, String ip) {
    List<Map> m = entries?.findAll { ip in it.ips } ?: []
    m.size() == 1 ? m[0] : null
}

String addressFor(Map entry, String current) {
    current in entry.ips ? current : entry.ips[0]
}

// The TV's new address, or null when it isn't found or still advertises the current one.
String repairAddress(List<Map> entries, String id, String current) {
    Map e = entryWithId(entries, id)
    if (!e || current in e.ips) return null
    return addressFor(e, current)
}

// A session is up, or starting, while the remote socket is open and the TV spoke within the idle limit.
boolean sessionUp(String mode, long lastRx, long nowMs, long limitMs) {
    return mode == "remote" && nowMs - lastRx <= limitMs
}

// send: the session is ready. hold: it is starting. full: it is starting and 20 commands already wait.
String commandRoute(String mode, boolean ready, int held) {
    if (mode != "remote") return "drop"
    if (ready) return "send"
    return held < 20 ? "hold" : "full"
}

boolean searchDue(Long last, long nowMs, long minMs) {
    last == null || nowMs - last >= minMs
}

// Whether this entry is another TV than the device's: by id when known, else by the current address.
boolean differentTv(Map entry, String tvId, String current) {
    if (tvId) return entry.id != tvId.toUpperCase()
    return current ? !(current in entry.ips) : false
}

// Blocks for SEARCH_TIMEOUT_MS: call it from a scheduled method, never from a command or updated().
List<Map> searchTvs() {
    state.lastSearch = now()
    long t0 = now()
    List results
    try {
        results = NetworkUtils.getMDNSServiceResults(MDNS_SERVICE, SEARCH_TIMEOUT_MS)
    } catch (e) {
        logDebug "mDNS search failed: ${e.class.simpleName}: ${e.message}"
        return []
    }
    List<Map> found = tvEntries(results)
    logDebug "mDNS search took ${now() - t0} ms: ${found.size()} TVs"
    return found
}

private void applyTv(Map e, String address) {
    device.updateSetting("tvIP", [type: "string", value: address])
    state.lastTvIP = address
    state.tvId = e.id
    state.tvName = e.name
}

private void clearFoundTvs() {
    state.remove("foundTvs")
    state.remove("foundTvEntries")
}

void findTvs() {
    runIn(1, "runSetupSearch", [data: [pair: false]])
}

// Takes the only TV found when no address is set, or lists several under TV.
void runSetupSearch(Map data) {
    boolean pair = data?.pair as boolean
    List<Map> found = searchTvs()
    if (!found) {
        logWarn "No Android TV or Google TV found. Check that the TV is on and on the hub's network " +
                "(mDNS doesn't cross VLANs or subnets), or enter its IP address"
        return
    }
    if (found.size() == 1) {
        Map e = found[0]
        clearFoundTvs()
        logInfo "Found a TV at ${e.ips[0]}" +
                (tvIP && differentTv(e, state.tvId as String, tvIP as String) ? "; to use it, clear the IP address and save" : "")
        if (!tvIP) takeTv(e, e.ips[0], pair)
        return
    }
    state.foundTvs = found.collectEntries { [(it.id): it.label] }
    state.foundTvEntries = found
    logInfo "Found ${found.size()} TVs: choose one under TV and save" + (pair ? ", then Start Pairing" : "")
}


// ── Pairing ───────────────────────────────────────────────────────────

void startPairing() {
    if (!tvIP) { runIn(1, "runSetupSearch", [data: [pair: true]]); return }
    disconnect()
    if (!state.cert || !(state.keyEnc || state.key)) generateIdentity()
    sendEvent(name: "pairing", value: "pairing")
    rt().fresh = false
    rt().certRead = false
    // The pairing hash needs the TV's public key: the pairing socket gives the TV's certificate on
    // firmware with getPeerCertificate(), otherwise readTvCertificate() reads it first.
    if (PEER_CERT.supported == false) readTvCertificate()
    else openPairingSession()
}

void readTvCertificateNow() { readTvCertificate() }

// Firmware without getPeerCertificate() (before 2.5.3): the certificate comes from a TLS 1.2 handshake
// on a plain socket, where it is sent in the clear. A TV that only allows TLS 1.3 can't pair this way.
private void readTvCertificate() {
    Map r = rt()
    r.mode = "hello"
    r.buf = ""
    r.closing = false
    logNet "Reading the TV certificate from ${tvIP}:${PAIRING_PORT}"
    if (!pairingConnect([byteInterface: true])) return
    interfaces.rawSocket.sendMessage(clientHelloHex())
    runIn(10, "pairingTimeout")
}

// Opens the pairing port. A TV that doesn't answer makes connect throw (after about 30 s when
// nothing answers); end the attempt then, or `pairing` would stay at "pairing".
private boolean pairingConnect(Map opts) {
    long t0 = now()
    try {
        interfaces.rawSocket.connect(opts, tvIP, PAIRING_PORT)
        return true
    } catch (e) {
        logError "Pairing failed: no connection to the TV on port ${PAIRING_PORT} after ${now() - t0} ms (${e.class.simpleName}: ${e.message})"
        endPairing()
        return false
    }
}

void pairingTimeout() {
    if (rt().mode in ["hello", "pair"] && device.currentValue("pairing") == "pairing") {
        logError "No pairing response from the TV"
        endPairing()
    }
}

void codeTimeout() {
    if (rt().mode == "pair" && device.currentValue("pairing") == "awaiting code") {
        logWarn "No pairing code entered in ${CODE_TIMEOUT_SECONDS.intdiv(60)} minutes; pairing cancelled"
        endPairing()
    }
}

// A pairing attempt that ends without success: back to the earlier pairing's session, if there is one.
private void endPairing() {
    unschedule("pairingTimeout")
    unschedule("codeTimeout")
    closeSocketLater()
    rt().mode = null
    sendEvent(name: "pairing", value: state.paired ? "paired" : "unpaired")
    if (state.paired) runIn(2, "connect")
}

void openPairingSession() {
    Map r = rt()
    r.mode = "pair"
    r.buf = ""
    r.closing = false
    logNet "Opening the pairing session"
    Map opts = tlsOptions()
    if (opts == null) { endPairing(); return }
    if (!pairingConnect(opts)) return
    if (!r.certRead) {
        String cert = peerCertificate()
        if (cert == null) {
            closeSocketLater()
            runInMillis(500, "readTvCertificateNow")
            return
        }
        state.tvCert = cert
        logNet "Got the TV certificate from the pairing socket"
        logTrace "TV certificate: ${cert.replace('\n', '|')}"
    }
    r.certRead = false
    sendFrame(outer(pMsg(10, pStr(1, "atvremote") + pStr(2, "Hubitat"))))
    runIn(10, "pairingTimeout")
}

// The TV's certificate as PEM, or null when the firmware can't give it.
private String peerCertificate() {
    try {
        String cert = interfaces.rawSocket.getPeerCertificate()
        if (cert) return cert
        logDebug "The pairing socket gave no TV certificate; reading it from a TLS 1.2 handshake"
    } catch (MissingMethodException ignored) {
        PEER_CERT.supported = false
        logDebug "This firmware can't read the TV certificate from the pairing socket; reading it from a TLS 1.2 handshake"
    } catch (e) {
        logWarn "Reading the TV certificate from the pairing socket failed (${e.class.simpleName}: ${e.message}); reading it from a TLS 1.2 handshake"
    }
    return null
}

void submitPairingCode(String code) {
    code = code?.trim()?.toUpperCase()
    if (!(code ==~ /[0-9A-F]{6}/)) { logWarn "The code must be 6 hex characters"; return }
    if (rt().mode != "pair" || device.currentValue("pairing") != "awaiting code") {
        logWarn "No pairing in progress; run Start Pairing first"
        return
    }
    def cf = CertificateFactory.getInstance("X.509")
    def client = cf.generateCertificate(new ByteArrayInputStream(state.cert.getBytes("US-ASCII"))).publicKey
    def server = cf.generateCertificate(new ByteArrayInputStream(state.tvCert.getBytes("US-ASCII"))).publicKey
    MessageDigest md = MessageDigest.getInstance("SHA-256")
    [client.modulus, client.publicExponent, server.modulus, server.publicExponent].each { BigInteger v ->
        md.update(HexUtils.hexStringToByteArray(evenHex(v.toString(16))))
    }
    md.update(HexUtils.hexStringToByteArray(code.substring(2)))
    byte[] secret = md.digest()
    logTrace "pairing hash: client modulus ${client.modulus.bitLength()} bits ${client.modulus.toString(16).take(16)}…, exponent ${client.publicExponent}; " +
             "TV modulus ${server.modulus.bitLength()} bits ${server.modulus.toString(16).take(16)}…, exponent ${server.publicExponent}; " +
             "digest[0]=${String.format('%02X', secret[0] & 0xFF)} code[0..1]=${code.substring(0, 2)}"
    if ((secret[0] & 0xFF) != Integer.parseInt(code.substring(0, 2), 16)) {
        logWarn "Code ${code} doesn't match; check it and try again"
        return
    }
    logCmd "Sending pairing secret"
    sendFrame(outer(pMsg(40, pBytes(1, hexOf(secret)))))
}

void forgetPairing() {
    disconnect()
    state.remove("paired")
    state.remove("tvCert")
    sendEvent(name: "pairing", value: "unpaired")
    logCfg "Pairing forgotten; the client certificate is kept"
}

private void handlePairingMessage(String hex) {
    logTrace "pair rx ${pDump(hex)}"
    Map<Integer, List> m = pDecode(hex)
    Long status = pFirst(m, 2) as Long
    if (status != 200L) {
        logError "Pairing failed with status ${status}"
        endPairing()
        return
    }
    if (m.containsKey(11)) {
        logRx "pairing_request_ack"
        String encoding = pMsg(1, pInt(1, 3) + pInt(2, 6))     // hexadecimal, 6 symbols
        sendFrame(outer(pMsg(20, encoding + pInt(3, 1))))        // preferred role: input
    } else if (m.containsKey(20)) {
        logRx "options"
        sendFrame(outer(pMsg(30, pMsg(1, pInt(1, 3) + pInt(2, 6)) + pInt(2, 1))))
    } else if (m.containsKey(31)) {
        logRx "configuration_ack"
        unschedule("pairingTimeout")
        sendEvent(name: "pairing", value: "awaiting code")
        runIn(CODE_TIMEOUT_SECONDS, "codeTimeout")
        logInfo "Enter the code shown on the TV with Submit Pairing Code"
    } else if (m.containsKey(41)) {
        logInfo "Paired"
        unschedule("codeTimeout")
        state.paired = true
        sendEvent(name: "pairing", value: "paired")
        closeSocketLater()
        runIn(2, "connect")
    } else {
        logDebug "Unhandled pairing message ${hex}"
    }
}

private String outer(String body) { pInt(1, 2) + pInt(2, 200) + body }


// ── Remote session ────────────────────────────────────────────────────

void connect() {
    if (!state.paired || !tvIP) { logWarn "Not paired"; return }
    Map r = rt()
    // A second session replaces the first, and the box closes it during setup.
    if (sessionUp(r.mode as String, (r.lastRx ?: 0L) as long, now(), IDLE_LIMIT_MS)) { logInfo "Already connected"; return }
    if (r.pending) logWarn "Dropped ${(r.pending as List).size()} command(s) sent before the last session was ready"
    r.fresh = false
    r.mode = "remote"
    r.ready = false
    r.textField = null
    r.pending = []
    r.buf = ""
    r.closing = false
    r.gotMessage = false
    r.lastRx = now()
    r.lastPingAt = 0L
    r.pingCount = 0
    logNet "Connecting to ${tvIP}:${REMOTE_PORT}"
    long t0 = now()
    try {
        Map opts = tlsOptions()
        if (opts == null) { r.mode = null; return }
        interfaces.rawSocket.connect(opts, tvIP, REMOTE_PORT)
        logTrace "remote connect returned after ${now() - t0} ms"
    } catch (java.net.SocketException e) {
        logWarn "The TV refused the connection after ${now() - t0} ms (${e.message}); it may need pairing again"
        r.mode = null   // else the retry sees a live session and gives up
        scheduleReconnect()
        return
    } catch (e) {
        logWarn "Connect failed after ${now() - t0} ms: ${e.class.simpleName}: ${e.message}"
        r.mode = null
        scheduleReconnect()
        return
    }
    runIn(WATCHDOG_SECONDS, "watchdog")
}

void disconnect() {
    unschedule("watchdog")
    unschedule("connect")
    unschedule("repairSearch")
    closeSocket()
    rt().mode = null
    if (device.currentValue("networkStatus") != "offline") sendEvent(name: "networkStatus", value: "offline")
}

void watchdog() {
    Map r = rt()
    if (r.mode == null && r.fresh && state.paired && tvIP) {
        // Only connect() schedules the watchdog and disconnect() cancels it, so the session was meant to be open.
        // runIn replaces a connect already scheduled, such as initialize()'s after a reboot.
        logNet "The driver code changed; reconnecting"
        sendEvent(name: "networkStatus", value: "offline")
        runIn(1, "connect")
        return
    }
    if (r.mode != "remote") return
    if (now() - (r.lastRx as long) > IDLE_LIMIT_MS) {
        logWarn "No message from the TV in ${IDLE_LIMIT_MS / 1000 as int} s; reconnecting"
        closeSocket()
        r.mode = null
        scheduleReconnect()
        return
    }
    runIn(WATCHDOG_SECONDS, "watchdog")
}

private void scheduleReconnect() {
    sendEvent(name: "networkStatus", value: "offline")
    int n = (state.retry ?: 0) as int
    int delay = Math.min(MAX_BACKOFF_SECONDS, 5 * (1 << Math.min(n, 6)))
    state.retry = n + 1
    logSched "Reconnecting in ${delay} s"
    if (state.paired && state.tvId && searchDue(state.lastSearch as Long, now(), REPAIR_SEARCH_MS)) {
        // The search schedules the connect: a connect queued behind a running search would run twice
        runIn(1, "repairSearch", [data: [delay: delay]])
    } else {
        runIn(delay, "connect")
    }
}

// The TV may have a new address: find it by its id and connect there, else retry after the backoff.
void repairSearch(Map data) {
    List<Map> found = searchTvs()
    String ip = repairAddress(found, state.tvId as String, tvIP as String)
    if (!ip) {
        runIn((data?.delay ?: 5) as int, "connect")
        return
    }
    logInfo "${state.tvName ?: 'The TV'} moved from ${tvIP} to ${ip}"
    applyTv(entryWithId(found, state.tvId as String), ip)
    state.retry = 0
    runIn(1, "connect")
}

// A device set up with a typed address: find which TV answers there.
void learnTvId() {
    state.lastIdSearch = now()
    Map e = entryWithAddress(searchTvs(), tvIP as String)
    if (!e) return
    state.tvId = e.id
    state.tvName = e.name
    state.lastTvIP = tvIP
    logDebug "Identified the TV at ${tvIP}"
}

private void handleRemoteMessage(String hex) {
    Map<Integer, List> m = pDecode(hex)
    Map r = rt()
    if (!r.gotMessage) {
        r.gotMessage = true
        state.retry = 0
        sendEvent(name: "networkStatus", value: "online")
        if (!state.tvId && searchDue(state.lastIdSearch as Long, now(), ID_SEARCH_MS)) runIn(5, "learnTvId")
    }
    if (m.containsKey(8)) {                                     // remote_ping_request
        Long val1 = pFirst(pDecode(pFirst(m, 8) as String), 1) as Long
        long t = now()
        long gap = r.lastPingAt ? t - (r.lastPingAt as long) : 0L
        r.lastPingAt = t
        r.pingCount = ((r.pingCount ?: 0) as int) + 1
        // The first ping and any unusual gap only: a steady once-a-minute line would fill the log while nothing happens.
        if (r.pingCount == 1 || gap > 7000 || (gap > 0 && gap < 3000)) {
            logTrace "ping #${r.pingCount} val1=${val1} gap=${gap} ms ${pDump(hex)}"
        }
        sendFrame(pMsg(9, pInt(1, val1 ?: 0L)), false)
        return
    }
    logTrace "rx ${pDump(hex)}"
    logRx "message fields ${m.keySet()}"
    if (m.containsKey(1)) {                                     // remote_configure
        Map<Integer, List> cfg = pDecode(pFirst(m, 1) as String)
        int supported = ((pFirst(cfg, 1) ?: 0L) as Long).intValue()
        Map<Integer, List> info = cfg.containsKey(2) ? pDecode(pFirst(cfg, 2) as String) : [:]
        String vendor = utf8(pFirst(info, 2) as String)
        String model = utf8(pFirst(info, 1) as String)
        if (vendor) device.updateDataValue("manufacturer", vendor)
        if (model) device.updateDataValue("model", model)
        device.updateDataValue("driverVersion", CODE_VERSION)
        // Some TVs hide their on-screen keyboard while a remote with IME is connected
        r.features = (imeEnable == false ? ACTIVE_FEATURES & ~FEATURE_IME : ACTIVE_FEATURES) & supported
        logCfg "TV ${vendor} ${model} supports features ${supported}; using ${r.features}"
        String devInfo = pInt(3, 1) + pStr(4, "1") + pStr(5, "atvremote") + pStr(6, "1.0.0")
        sendFrame(pMsg(1, pInt(1, r.features as long) + pMsg(2, devInfo)))
    } else if (m.containsKey(2)) {                              // remote_set_active
        sendFrame(pMsg(2, pInt(1, (r.features ?: ACTIVE_FEATURES) as long)))
    } else if (m.containsKey(3)) {                              // remote_error: code, then the rejected message
        Map<Integer, List> err = pDecode(pFirst(m, 3) as String)
        String rejected = err.containsKey(2) ? pDump(pFirst(err, 2) as String) : ""
        logWarn "TV rejected ${rejected} (error ${pFirst(err, 1)}); reconnecting"
        if (rejected.startsWith("90:")) {
            logWarn "No app on the TV opens this link. Package names stopped launching apps after a Google Play update " +
                    "in August 2026; use a link the app handles, such as https://www.youtube.com"
        }
        // The session went quiet or closed within 45 s of every error seen, so start a fresh one now.
        closeSocketLater()
        r.mode = null
        runIn(1, "connect")
    } else if (m.containsKey(20)) {                             // remote_ime_key_inject: app, and the text field with focus
        Map<Integer, List> ime = pDecode(pFirst(m, 20) as String)
        if (ime.containsKey(1)) {
            String app = utf8(pFirst(pDecode(pFirst(ime, 1) as String), 12) as String)
            if (app) sendEvent(name: "currentApp", value: app)
        }
        noteTextField(r, ime.containsKey(2) ? pDecode(pFirst(ime, 2) as String) : null)
    } else if (m.containsKey(21)) {                             // remote_ime_batch_edit: the keyboard opened (1) or closed (0)
        Map<Integer, List> edit = pDecode(pFirst(m, 21) as String)
        r.imeCounter = (pFirst(edit, 1) ?: 0L) as Long
    } else if (m.containsKey(22)) {                             // remote_ime_show_request: the text field after an edit
        Map<Integer, List> show = pDecode(pFirst(m, 22) as String)
        if (show.containsKey(2)) noteTextField(r, pDecode(pFirst(show, 2) as String))
    } else if (m.containsKey(23)) {                             // cursor moved; carries no field counter
    } else if (m.containsKey(40)) {                             // remote_start
        boolean on = ((pFirst(pDecode(pFirst(m, 40) as String), 1) ?: 0L) as Long) != 0L
        sendEvent(name: "switch", value: on ? "on" : "off")
        // The session is ready at the first remote_start, as androidtvremote2 has it: send what was held.
        if (!r.ready) {
            r.ready = true
            List held = (r.pending ?: []) as List
            r.pending = []
            held.each { Map c -> sendRemote(c.hex as String, c.volume == true) }
        }
    } else if (m.containsKey(50)) {                             // remote_set_volume_level
        Map<Integer, List> v = pDecode(pFirst(m, 50) as String)
        long max = (pFirst(v, 6) ?: 0L) as Long
        if (max <= 0) return                                    // no level: a box that sends volume to the TV over CEC
        long level = (pFirst(v, 7) ?: 0L) as Long
        boolean muted = ((pFirst(v, 8) ?: 0L) as Long) != 0L
        // Trust volume only from a TV that answers volume keys. A box that passes volume to the TV
        // over HDMI-CEC reports its own volume around power changes, which says nothing about the TV's.
        if (!state.volumeTrusted) {
            if (now() - ((r.lastVolumeKeyAt ?: 0L) as long) > VOLUME_ANSWER_MS) {
                logTrace "volume ${level}/${max} not in answer to a volume key; ignored"
                return
            }
            state.volumeTrusted = true
            logCfg "The TV reports its volume; setVolume enabled"
        }
        state.volumeMax = max
        state.volumeLevel = level
        sendEvent(name: "volume", value: Math.round(level * 100.0 / max) as int, unit: "%")
        sendEvent(name: "mute", value: muted ? "muted" : "unmuted")
    } else {
        logDebug "Unhandled message ${hex}"
    }
}


// ── Commands ──────────────────────────────────────────────────────────

void on() {
    if (device.currentValue("switch") != "on") sendKeyCode(KEYCODES.POWER)
}

void off() {
    if (device.currentValue("switch") != "off") sendKeyCode(KEYCODES.POWER)
}

void volumeUp() { sendKeyCode(KEYCODES.VOLUME_UP) }

void volumeDown() { sendKeyCode(KEYCODES.VOLUME_DOWN) }

void mute() {
    if (device.currentValue("mute") != "muted") sendKeyCode(KEYCODES.VOLUME_MUTE)
}

void unmute() {
    if (device.currentValue("mute") != "unmuted") sendKeyCode(KEYCODES.VOLUME_MUTE)
}

// The TV doesn't report playback state, so transportStatus is never set.
void play() { sendKeyCode(KEYCODES.MEDIA_PLAY) }

void pause() { sendKeyCode(KEYCODES.MEDIA_PAUSE) }

void stop() { sendKeyCode(KEYCODES.MEDIA_STOP) }

// The protocol has no absolute volume command: step with volume keys from the last reported level.
void setVolume(volumelevel) {
    if (!state.volumeTrusted) {
        logWarn "This TV doesn't report its volume, so setVolume can't set it; use volume up or down"
        return
    }
    long max = (state.volumeMax ?: 0L) as long
    if (max <= 0) { logWarn "Volume range not known yet"; return }
    long target = Math.round((volumelevel as BigDecimal) * max / 100.0)
    long steps = target - ((state.volumeLevel ?: 0L) as long)
    int key = steps > 0 ? KEYCODES.VOLUME_UP : KEYCODES.VOLUME_DOWN
    Math.min(Math.abs(steps), 50L).times { sendKeyCode(key) }
}

// Untyped: a rule or a script may pass the key code as a number.
void sendKey(keyArg) {
    String key = keyArg?.toString()?.trim()?.toUpperCase()?.replaceFirst(/^KEYCODE_/, "")
    Integer code = KEYCODES[key] ?: (key ==~ /\d+/ ? key as Integer : null)
    if (code == null) { logWarn "Unknown key ${key}"; return }
    sendKeyCode(code)
}

void launchApp(String appLink) {
    appLink = appLink?.trim()
    if (!appLink) return
    // The TV opens a link only when an installed app handles it. A bare package name is sent as a
    // market:// launch link, the form that worked before a Google Play update in August 2026.
    if (!(appLink ==~ /^[A-Za-z][A-Za-z0-9+.-]*:.*/)) appLink = "market://launch?id=${appLink}"
    logCmd "Launch ${appLink}"
    sendRemote(pMsg(90, pStr(1, appLink)))
}

// The edit carries the counters of the TV's last text-field message, or 0 before any, as androidtvremote2 does.
// The box drops an edit whose field counter is ahead of its own or far behind it, without an error. It
// takes the counter it last reported (a cursor move or two behind is fine), and it takes 0/0 at any time.
// The counters in the box's message 21 (1/1 while the keyboard is up) are not the field's and get dropped.
void sendText(textArg) {
    String text = textArg?.toString()
    if (!text) return
    if (imeEnable == false) { logWarn "Send Text needs the TV keyboard link; turn it on in Preferences"; return }
    Map r = rt()
    logCmd "Text (${text.length()} characters)"     // the text may be a password: only trace shows it
    long end = text.length() - 1
    String field = pInt(1, end) + pInt(2, end) + pStr(3, text)
    Map tf = r.textField as Map
    long ime = tf ? (r.imeCounter ?: 0L) as long : 0L
    long counter = tf ? tf.counter as long : 0L
    sendRemote(pMsg(21, pInt(1, ime) + pInt(2, counter) + pMsg(3, pInt(1, 1) + pMsg(2, field))))
}

// Field 2 of messages 20 and 22: counter, value, cursor start and end. Null when no text field has focus.
private void noteTextField(Map r, Map<Integer, List> status) {
    if (status == null) {
        r.textField = null
        return
    }
    String value = utf8(pFirst(status, 2) as String) ?: ""
    r.textField = [counter: (pFirst(status, 1) ?: 0L) as Long, value: value]
    logTrace "Text field: \"${value}\""
}

private void sendKeyCode(int code) {
    logCmd "Key ${code}"
    sendRemote(pMsg(10, pInt(1, code) + pInt(2, DIRECTION_SHORT)), code in [KEYCODES.VOLUME_UP, KEYCODES.VOLUME_DOWN, KEYCODES.VOLUME_MUTE])
}

// A command sent before the session is ready waits for it: the box closes the session, or refuses the
// command with remote_error 2, when one arrives before its setup messages are answered.
private void sendRemote(String msgHex, boolean volumeKey = false) {
    Map r = rt()
    switch (commandRoute(r.mode as String, r.ready == true, ((r.pending ?: []) as List).size())) {
        case "send":
            if (volumeKey) r.lastVolumeKeyAt = now()
            sendFrame(msgHex)
            break
        case "hold":
            r.pending = ((r.pending ?: []) as List) + [[hex: msgHex, volume: volumeKey]]
            logDebug "The session isn't ready yet; the command waits for it"
            break
        case "full":
            logWarn "Too many commands while the session starts; dropped one"
            break
        default:
            logWarn "Not connected"
    }
}


// ── Socket ────────────────────────────────────────────────────────────

void parse(String hex) {
    Map r = rt()
    r.lastRx = now()
    if (traceEnable && r.mode != "remote") logTrace "chunk ${hex.length() / 2 as int} bytes, mode ${r.mode}, buffered ${((r.buf ?: '') as String).length() / 2 as int} bytes"
    if (r.mode == "hello") {
        r.buf = (r.buf as String) + hex
        String certHex = certificateFromHandshake(r.buf as String)
        if (certHex) {
            r.mode = null
            unschedule("pairingTimeout")
            state.tvCert = pem("CERTIFICATE", HexUtils.hexStringToByteArray(certHex))
            logNet "Got the TV certificate (${certHex.length() / 2 as int} bytes DER)"
            logTrace "TV certificate: ${state.tvCert.replace('\n', '|')}"
            r.certRead = true
            closeSocketLater()
            runInMillis(500, "openPairingSession")
        }
        return
    }
    r.buf = (r.buf as String) + hex
    while (true) {
        List lenPos = readVarint(r.buf as String, 0)
        if (lenPos == null) break
        int len = (lenPos[0] as Long).intValue()
        int start = lenPos[1] as int
        if ((r.buf as String).length() < start + len * 2) break
        String msg = (r.buf as String).substring(start, start + len * 2)
        r.buf = (r.buf as String).substring(start + len * 2)
        if (r.mode == "pair") handlePairingMessage(msg)
        else if (r.mode == "remote") handleRemoteMessage(msg)
    }
}

void socketStatus(String message) {
    Map r = rt()
    if (r.closing) return
    r.closing = true                       // the platform repeats this call until close()
    logWarn "Socket (${r.mode}): ${message}"
    // Work after close() here threw InterruptedException, so close and recover on another thread.
    r.droppedMode = r.mode
    r.mode = null
    runInMillis(100, "socketDropped")
}

void socketDropped() {
    Map r = rt()
    try { interfaces.rawSocket.close() } catch (ignored) { }
    if (r.droppedMode == "remote") scheduleReconnect()
    else if (r.droppedMode == "pair") endPairing()
    r.droppedMode = null
}

private void closeSocket() {
    rt().closing = true
    try { interfaces.rawSocket.close() } catch (ignored) { }
}

// For closes decided inside parse() (or code it calls): closing the remote socket from there makes the
// platform throw InterruptedException around socketStatus, outside the driver's code; a scheduled close
// doesn't. Closing the pairing socket after a rejected code still logs it, whatever the delay.
private void closeSocketLater() {
    rt().closing = true
    runInMillis(20, "closeSocketNow")
}

void closeSocketNow() {
    try { interfaces.rawSocket.close() } catch (ignored) { }
}

private void sendFrame(String msgHex, boolean trace = true) {
    if (trace) logTrace "tx ${rt().mode == 'pair' ? 'pair ' : ''}${pDump(msgHex)}"
    interfaces.rawSocket.sendMessage(pvarint(msgHex.length() / 2 as long) + msgHex)
}

// Null when the client key can't be read; the caller stops.
private Map tlsOptions() {
    String key = clientKey()
    if (key == null) return null
    // Before 2.5.3, caCertificate is required alongside certificate/privateKey; any certificate works with ignoreSSLIssues.
    return [secureSocket: true, ignoreSSLIssues: true, byteInterface: true,
            certificate: state.cert, privateKey: key, caCertificate: state.cert]
}

// The private key is kept encrypted with the hub's key, so the device page's state doesn't show it.
// A plain key from an earlier version is converted on first use.
private String clientKey() {
    if (state.key) {
        state.keyEnc = encrypt(state.key as String)
        state.remove("key")
    }
    if (!state.keyEnc) return null
    try {
        return decrypt(state.keyEnc as String)
    } catch (e) {
        logError "The client key can't be read (${e.message}), as after a hub restore or migration; pair again with Start Pairing"
        ["keyEnc", "cert", "tvCert", "paired"].each { state.remove(it) }
        sendEvent(name: "pairing", value: "unpaired")
        return null
    }
}


// ── TLS 1.2 handshake (certificate only) ──────────────────────────────

private String clientHelloHex() {
    String exts = "000A00060004001D0017" +              // supported_groups: x25519, secp256r1
                  "000B00020100" +                      // ec_point_formats: uncompressed
                  "000D000A00080401080404010201" +      // signature_algorithms
                  "FF01000100"                          // renegotiation_info
    String suites = "C02FC030009C009D002F0035"
    String body = "0303" + randomHex(32) + "00" + hex16(suites.length() / 2 as int) + suites + "0100" +
                  hex16(exts.length() / 2 as int) + exts
    String hs = "01" + String.format("%06X", body.length() / 2 as int) + body
    return "160301" + hex16(hs.length() / 2 as int) + hs
}

// Returns the first certificate (DER hex) of the Certificate handshake message, or null if incomplete.
private String certificateFromHandshake(String hex) {
    StringBuilder hs = new StringBuilder()
    int i = 0
    while (i + 10 <= hex.length()) {
        int type = Integer.parseInt(hex.substring(i, i + 2), 16)
        int len = Integer.parseInt(hex.substring(i + 6, i + 10), 16)
        if (i + 10 + len * 2 > hex.length()) break
        if (type == 0x16) hs.append(hex.substring(i + 10, i + 10 + len * 2))
        i += 10 + len * 2
    }
    String h = hs.toString()
    int j = 0
    while (j + 8 <= h.length()) {
        int type = Integer.parseInt(h.substring(j, j + 2), 16)
        int len = Integer.parseInt(h.substring(j + 2, j + 8), 16)
        if (j + 8 + len * 2 > h.length()) return null
        if (type == 0x0B) {
            int certLen = Integer.parseInt(h.substring(j + 14, j + 20), 16)
            return h.substring(j + 20, j + 20 + certLen * 2)
        }
        j += 8 + len * 2
    }
    return null
}


// ── Client identity (self-signed RSA certificate) ─────────────────────

private void generateIdentity() {
    long t0 = now()
    def kpg = KeyPairGenerator.getInstance("RSA")
    kpg.initialize(2048)
    def kp = kpg.generateKeyPair()
    List sigAlg = derSeq(derOid("1.2.840.113549.1.1.11"), [0x05, 0x00])
    List name = derSeq(derTlv(0x31, derSeq(derOid("2.5.4.3"), derUtf8("atvremote-hubitat"))))
    List tbs = derSeq(derTlv(0xA0, derInt(2G)), derInt(new BigInteger(64, new Random())), sigAlg, name,
                      derSeq(derGenTime("20260101000000Z"), derGenTime("20501231235959Z")), name, bytesOf(kp.public.encoded))
    def signer = Signature.getInstance("SHA256withRSA")
    signer.initSign(kp.private)
    signer.update(toBytes(tbs))
    byte[] certDer = toBytes(derSeq(tbs, sigAlg, derTlv(0x03, [0] + bytesOf(signer.sign()))))
    state.cert = pem("CERTIFICATE", certDer)
    state.keyEnc = encrypt(pem("PRIVATE KEY", kp.private.encoded))
    logCfg "Generated the client certificate in ${now() - t0} ms"
}

private List derLen(int n) {
    if (n < 0x80) return [n]
    List b = []
    while (n > 0) { b.add(0, n & 0xFF); n = n >> 8 }
    return [0x80 | b.size()] + b
}
private List derTlv(int tag, List body) { [tag] + derLen(body.size()) + body }
private List derSeq(List... parts) { derTlv(0x30, parts.flatten()) }
private List derInt(BigInteger v) { derTlv(0x02, bytesOf(v.toByteArray())) }
private List derUtf8(String s) { derTlv(0x0C, bytesOf(s.getBytes("UTF-8"))) }
private List derGenTime(String s) { derTlv(0x18, bytesOf(s.getBytes("US-ASCII"))) }
private List derOid(String dotted) {
    List p = dotted.tokenize('.').collect { it as long }
    List out = [(int) (p[0] * 40 + p[1])]
    p.drop(2).each { long v ->
        List enc = [(int) (v & 0x7F)]
        v = v >> 7
        while (v > 0) { enc.add(0, (int) ((v & 0x7F) | 0x80)); v = v >> 7 }
        out.addAll(enc)
    }
    return derTlv(0x06, out)
}
private List bytesOf(byte[] a) { a.collect { it & 0xFF } }
private byte[] toBytes(List l) { l.collect { (byte) it } as byte[] }
private String pem(String label, byte[] der) {
    String b64 = der.encodeBase64().toString()
    return "-----BEGIN ${label}-----\n" + b64.toList().collate(64)*.join().join("\n") + "\n-----END ${label}-----"
}


// ── Protobuf (hex strings) ────────────────────────────────────────────

private String pvarint(long v) {
    StringBuilder s = new StringBuilder()
    while (v >= 0x80 || v < 0) {
        s.append(String.format("%02X", (int) ((v & 0x7F) | 0x80)))
        v = v >>> 7
    }
    s.append(String.format("%02X", (int) v))
    return s.toString()
}
private String pKey(int field, int wireType) { pvarint(((long) field << 3) | wireType) }
private String pInt(int field, long v) { pKey(field, 0) + pvarint(v) }
private String pBytes(int field, String hex) { pKey(field, 2) + pvarint(hex.length() / 2 as long) + hex }
private String pStr(int field, String s) { pBytes(field, hexOf(s.getBytes("UTF-8"))) }
private String pMsg(int field, String bodyHex) { pBytes(field, bodyHex) }

// [value, nextIndex] or null when the varint is incomplete
private List readVarint(String hex, int i) {
    long v = 0L
    int shift = 0
    while (i + 2 <= hex.length()) {
        int b = Integer.parseInt(hex.substring(i, i + 2), 16)
        i += 2
        v |= ((long) (b & 0x7F)) << shift
        if ((b & 0x80) == 0) return [v, i]
        shift += 7
    }
    return null
}

// field number -> values: Long for varints, hex String for length-delimited fields
private Map<Integer, List> pDecode(String hex) {
    Map<Integer, List> out = [:]
    if (!hex) return out
    int i = 0
    while (i < hex.length()) {
        List kp = readVarint(hex, i)
        if (kp == null) break
        long key = kp[0] as Long
        i = kp[1] as int
        int field = (int) (key >>> 3)
        int wt = (int) (key & 7)
        def value
        if (wt == 0) {
            List vp = readVarint(hex, i)
            if (vp == null) break
            value = vp[0]
            i = vp[1] as int
        } else if (wt == 2) {
            List lp = readVarint(hex, i)
            if (lp == null) break
            int len = (lp[0] as Long).intValue()
            i = lp[1] as int
            value = hex.substring(i, Math.min(hex.length(), i + len * 2))
            i += len * 2
        } else if (wt == 5) {
            value = hex.substring(i, Math.min(hex.length(), i + 8)); i += 8
        } else if (wt == 1) {
            value = hex.substring(i, Math.min(hex.length(), i + 16)); i += 16
        } else {
            break
        }
        if (!out.containsKey(field)) out[field] = []
        out[field] << value
    }
    return out
}
private def pFirst(Map<Integer, List> m, int field) { m[field] ? m[field][0] : null }

// Readable protobuf dump for trace logs: nested messages in {}, printable strings quoted, other bytes as hex.
private String pDump(String hex, int depth = 0) {
    Map<Integer, List> m = pDecodeStrict(hex)
    if (m == null) return "0x${hex}"
    List parts = []
    m.each { Integer f, List vals ->
        vals.each { v ->
            if (v instanceof String) {
                String s = (v as String)
                // A nested message's tag and length bytes can both be printable, such as "b$" before a
                // 36-character package name, so a message made only of text and messages wins over text.
                boolean nested = depth < 6 && looksNested(s, depth)
                String text = nested ? null : printable(s)
                Map<Integer, List> inner = (text == null && !nested && depth < 6) ? pDecodeStrict(s) : null
                if (nested || inner != null) parts << "${f}:{${pDump(s, depth + 1)}}"
                else if (text != null) parts << "${f}:\"${text}\""
                else parts << "${f}:0x${s.length() > 64 ? s.take(64) + '…(' + (s.length() / 2 as int) + ' bytes)' : s}"
            } else {
                parts << "${f}:${v}"
            }
        }
    }
    return parts.join(" ")
}

// True when the bytes parse whole as length-delimited fields only, each non-empty printable text or itself nested
private boolean looksNested(String hex, int depth) {
    if (!hex || hex.length() % 2) return false
    int i = 0
    while (i < hex.length()) {
        List kp = readVarint(hex, i)
        if (kp == null) return false
        long key = kp[0] as Long
        if ((key & 7) != 2 || (key >>> 3) == 0) return false
        List lp = readVarint(hex, kp[1] as int)
        if (lp == null) return false
        int start = lp[1] as int
        int end = start + (lp[0] as Long).intValue() * 2
        if (end > hex.length() || end <= start) return false
        String s = hex.substring(start, end)
        if (printable(s) == null && !(depth < 5 && looksNested(s, depth + 1))) return false
        i = end
    }
    return true
}

// pDecode that returns null unless the whole buffer parses as well-formed fields
private Map<Integer, List> pDecodeStrict(String hex) {
    if (!hex || hex.length() % 2) return null
    int i = 0
    while (i < hex.length()) {
        List kp = readVarint(hex, i)
        if (kp == null) return null
        long key = kp[0] as Long
        int field = (int) (key >>> 3)
        int wt = (int) (key & 7)
        if (field == 0) return null
        i = kp[1] as int
        if (wt == 0) {
            List vp = readVarint(hex, i); if (vp == null) return null; i = vp[1] as int
        } else if (wt == 2) {
            List lp = readVarint(hex, i); if (lp == null) return null
            i = (lp[1] as int) + ((lp[0] as Long).intValue() * 2)
        } else if (wt == 5) { i += 8 } else if (wt == 1) { i += 16 } else return null
        if (i > hex.length()) return null
    }
    return pDecode(hex)
}

private String printable(String hex) {
    if (!hex) return ""
    byte[] b = HexUtils.hexStringToByteArray(hex)
    for (byte x : b) { int c = x & 0xFF; if (c < 0x20 || c > 0x7E) return null }
    return new String(b, "US-ASCII")
}

private String hexOf(byte[] b) { b.encodeHex().toString().toUpperCase() }
private String utf8(String hex) { hex ? new String(HexUtils.hexStringToByteArray(hex), "UTF-8") : null }
private String evenHex(String h) { h.length() % 2 ? "0" + h : h }
private String hex16(int n) { String.format("%04X", n) }
private String randomHex(int n) { Random r = new Random(); (1..n).collect { String.format("%02X", r.nextInt(256)) }.join() }


// ── Logging ───────────────────────────────────────────────────────────
//   ⬇️ Rx  ⬆️ Cmd  🔧 Cfg  🌐 Net  ⏰ Sched  ·  ⚠️ Warn  🛑 Error  🔬 Trace
private String logp(String e) { "${e} ${device.displayName}: " }

void logRx   (String m) { if (debugEnable) log.debug logp('⬇️') + m }
void logCmd  (String m) { if (txtEnable != false) log.info  logp('⬆️') + m }
void logCfg  (String m) { if (txtEnable != false) log.info  logp('🔧') + m }
void logNet  (String m) { if (debugEnable) log.debug logp('🌐') + m }
void logSched(String m) { if (debugEnable) log.debug logp('⏰') + m }

void logWarn (String m) { log.warn  logp('⚠️') + m }
void logError(String m) { log.error logp('🛑') + m }
void logTrace(String m) { if (traceEnable) log.trace logp('🔬') + m }
void logInfo (String m) { if (txtEnable != false) log.info  "${device.displayName}: ${m}" }
void logDebug(String m) { if (debugEnable) log.debug "${device.displayName}: ${m}" }

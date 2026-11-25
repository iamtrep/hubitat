// Copyright (c) 2026 PJ
// SPDX-License-Identifier: MIT

/*
 * Hilo Energy Meter — Child Driver
 *
 * One device per Hilo location. The parent pushes hourly consumption through
 * updateEnergy(Map). This driver holds Hilo's GraphQL subscription open as an
 * event stream and publishes the utility meter's power, which Hilo pushes about
 * once a minute.
 *
 * Hilo's stream goes silent near the access token's one-hour expiry without
 * closing, so the parent pushes a fresh token every 50 min (streamToken) and the
 * driver reopens the stream with it. The driver never calls the parent for a token.
 * Hilo sends a keepalive comment every 12 s. The stream is opened with rawStream so
 * that keepalives reach parse(): the connection is judged by what it delivers, not by
 * which STOP statuses the hub reports.
 */

import groovy.json.JsonOutput
import groovy.transform.Field
import java.util.concurrent.ConcurrentHashMap

metadata {
    definition(
        name: "Hilo Energy Meter",
        namespace: "iamtrep",
        author: "pj",
        importUrl: "https://raw.githubusercontent.com/iamtrep/hubitat/refs/heads/main/integrations/Hilo/HiloEnergyMeter.groovy"
    ) {
        capability "EnergyMeter"
        capability "PowerMeter"
        capability "Refresh"
        capability "Initialize"
        capability "Sensor"

        attribute "energyYesterday", "number"
        attribute "energyLastHour",  "number"
        attribute "lastHourStart",   "string"
        attribute "healthStatus",    "enum", ["online", "offline"]
    }

    preferences {
        input name: "liveEnable",  type: "bool", title: tr("pref.live"), defaultValue: true, description: tr("pref.liveDesc")
        input name: "txtEnable",   type: "bool", title: tr("log.info"), defaultValue: true
        input name: "debugEnable", type: "bool", title: tr("log.debug"), defaultValue: false, submitOnChange: true
        if (debugEnable) {
            input name: "traceEnable", type: "bool", title: tr("log.trace"), defaultValue: false
        }
    }
}

@Field static final String CODE_VERSION = "0.1.5"

// Event descriptions and preference titles, in the language the parent app sets. Logs stay in
// English and never come from here.
@Field static final Map<String, Map<String, String>> I18N = [
    en: [
        "pref.live"     : "Live power from Hilo",
        "pref.liveDesc" : "Keeps a connection open to Hilo, which pushes the meter's power about once a minute.",
        "log.info"      : "Enable info logging",
        "log.debug"     : "Enable debug logging",
        "log.trace"     : "Enable trace logging",
        "ev.lastHour"   : "{name} used {kwh} kWh in the hour from {start}",
        "ev.online"     : "{name} is online",
        "ev.onlineWhy"  : "{name} is online: {why}",
        "ev.offline"    : "{name} is offline",
        "ev.offlineWhy" : "{name} is offline: {why}",
        "why.streamDown": "live power stream down",
        "why.streamBack": "live power back",
    ],
    fr: [
        "pref.live"     : "Puissance en direct de Hilo",
        "pref.liveDesc" : "Garde une connexion ouverte avec Hilo, qui envoie la puissance du compteur environ une fois par minute.",
        "log.info"      : "Activer les journaux d'information",
        "log.debug"     : "Activer les journaux de débogage",
        "log.trace"     : "Activer les journaux de trace",
        "ev.lastHour"   : "{name} a consommé {kwh} kWh dans l'heure de {start}",
        "ev.online"     : "{name} est en ligne",
        "ev.onlineWhy"  : "{name} est en ligne : {why}",
        "ev.offline"    : "{name} est hors ligne",
        "ev.offlineWhy" : "{name} est hors ligne : {why}",
        "why.streamDown": "flux de puissance en direct interrompu",
        "why.streamBack": "puissance en direct rétablie",
    ],
] // end I18N

@Field static final String GRAPHQL_URL = "https://platform.hiloenergie.com/api/digital-twin/v3/graphql"
@Field static final String SUBSCRIPTION = 'subscription onAnyDeviceUpdated($locationHiloId: String!) { onAnyDeviceUpdated(locationHiloId: $locationHiloId) { deviceType transmissionTime device { ... on BasicSmartMeter { power { value kind } } } } }'
// Three missed keepalives (12 s apart) end the stream with an ERROR status, then STOP.
@Field static final int STREAM_READ_TIMEOUT = 40
@Field static final int BACKOFF_MIN_SECS = 5
@Field static final int BACKOFF_MAX_SECS = 300
// A live stream delivers a block (a keepalive or a power update) at least every 24 s: Hilo skips the
// keepalive in a 12 s slot that carried an update. Checked this long after each connect() and each
// STOP: nothing heard since then means the current stream is down. A STOP from a stream the driver
// closed is then harmless, however late it comes, because the new stream has been heard.
@Field static final int STREAM_CHECK_SECS = 30
// Nothing heard for this long on a connection thought to be up: reconnect. readTimeout normally
// ends such a stream first, and its STOP leads to the same reconnect.
@Field static final long SILENT_AFTER_MS = 60_000L
// Nothing heard for this long, with reconnects failing: report the device offline.
@Field static final long OFFLINE_AFTER_MS = 300_000L
@Field static final int DEBUG_LOG_TIMEOUT = 1800
// Per device, in memory (a driver's atomicState write after the first state access is lost):
// the time of the last block of any kind from the stream, of the last connect(), of the last STOP,
// and the last time heard before the current run of failures.
@Field static final ConcurrentHashMap<String, Long> LAST_HEARD = new ConcurrentHashMap<>()
@Field static final ConcurrentHashMap<String, Long> CONNECT_AT = new ConcurrentHashMap<>()
@Field static final ConcurrentHashMap<String, Long> STOP_AT = new ConcurrentHashMap<>()
@Field static final ConcurrentHashMap<String, Long> DOWN_SINCE = new ConcurrentHashMap<>()
// Per device: the access token the manager last pushed. Empty after a boot or code push until the
// manager pushes the next one.
@Field static final ConcurrentHashMap<String, String> TOKENS = new ConcurrentHashMap<>()

void installed() { logDebug "installed"; state.version = CODE_VERSION }

void updated() {
    // 0.1.4 and earlier stamped the time of each hourly read in an attribute.
    device.deleteCurrentState("lastUpdate")
    logDebug "updated"
    unschedule()   // safety-lint:ok unschedule-kills-timers: initialize() reconnects, and each connect re-arms streamCheck
    closeStream()
    initialize()
    if (settings.debugEnable || settings.traceEnable) runIn(DEBUG_LOG_TIMEOUT, "logsOff")
}

void uninstalled() { closeStream() }

// Runs at hub start too (capability Initialize).
void initialize() {
    logDebug "initialize"
    checkVersion(false)
    state.remove("reconnectDelay")
    state.remove("streamFailures")
    DOWN_SINCE.remove(device.id.toString())
    if (liveEnabled()) {
        // After a boot there is no token yet: the manager's push connects.
        if (TOKENS.get(device.id.toString())) runIn(2, "connectStream")
        schedule("${new Random().nextInt(60)} */5 * ? * *", "checkStream")
    }
}

void deviceTypeUpdated() { logDebug "driver change detected" }

void refresh() {
    checkVersion()
    parent?.refreshNow()
    if (liveEnabled() && silentFor() > SILENT_AFTER_MS) connectStream()
}

void checkVersion(boolean reinit = true) {
    if (state.version == CODE_VERSION) return
    logVer "New version: ${CODE_VERSION} (was: ${state.version})"
    state.version = CODE_VERSION
    if (reinit) runIn(1, "updated")
}

private boolean liveEnabled() { return settings.liveEnable != false }

// ── Interface language ────────────────────────────────────────────────

// Called by the parent with its page's language.
void setLanguage(String lang) { state.uiLang = lang }

// state is empty while preferences are saved; the titles then don't show.
private String uiLang() { return (state?.uiLang ?: "fr") as String }

private String tr(String key, Map args = [:], String lang = null) {
    String s = I18N[lang ?: uiLang()]?.get(key) ?: I18N.en.get(key) ?: key
    args.each { k, v -> s = s.replace("{${k}}".toString(), v == null ? "" : v.toString()) }
    return s
}

// ── Parent interface ──────────────────────────────────────────────────

// Hourly consumption from the parent's history read; only the keys present are sent.
void updateEnergy(Map m) {
    checkVersion()
    if (m.energy != null)          sendEvent(name: "energy", value: m.energy, unit: "kWh")
    if (m.energyYesterday != null) sendEvent(name: "energyYesterday", value: m.energyYesterday, unit: "kWh")
    if (m.energyLastHour != null) {
        sendEvent(name: "energyLastHour", value: m.energyLastHour, unit: "kWh",
                  descriptionText: tr("ev.lastHour", [name: device.displayName, start: m.lastHourStart,
                                       kwh: uiLang() == "fr" ? m.energyLastHour.toString().replace(".", ",") : m.energyLastHour]))
        sendEvent(name: "lastHourStart", value: m.lastHourStart)
    }
    if (device.currentValue("healthStatus") == null) updateHealth("online")
}

// Called by the parent with "offline" when Hilo or the login is lost, and "online"
// with the recovery line. The reason goes in the log in English (reason) and in the
// event description in the parent's language (reasonUi).
void updateHealth(String status, String reason = null, String reasonUi = null) {
    String prev = device.currentValue("healthStatus")
    String why = reason ? ": ${reason}" : ""
    String key = status == "online" ? "ev.online" : "ev.offline"
    sendEvent(name: "healthStatus", value: status, descriptionText: reason ?
        tr(key + "Why", [name: device.displayName, why: reasonUi ?: reason]) : tr(key, [name: device.displayName]))
    if (prev != null && prev != status) {
        if (status == "offline") logWarn "offline${why}"
        else logInfo "back online${why}"
    }
}

// From the manager, with a fresh access token: reopens the stream with it, in a run of its own
// so the manager's call returns at once.
void streamToken(String token) {
    checkVersion()
    TOKENS.put(device.id.toString(), token)
    if (liveEnabled()) runIn(1, "connectStream")
}

void stopLiveStream() {
    unschedule("connectStream")
    TOKENS.remove(device.id.toString())
    closeStream()
}

// ── Event stream ──────────────────────────────────────────────────────

void connectStream() {
    if (!liveEnabled()) return
    String hiloId = device.getDataValue("locationHiloId")
    if (!hiloId) { streamFailed("no Hilo location"); return }
    // Never asks the manager (see streamToken); a token past its life fails the connect, and the
    // backoff retries until the next push.
    String id = device.id.toString()
    String token = TOKENS.get(id)
    if (!token) { logDebug "waiting for an access token from the manager"; return }
    closeStream()
    String url = GRAPHQL_URL + "?query=" + enc(SUBSCRIPTION) + "&variables=" + enc(JsonOutput.toJson([locationHiloId: hiloId]))
    logNet "connecting to Hilo"
    CONNECT_AT.put(id, now())
    interfaces.eventStream.connect(url, [
        headers    : ["Authorization": "Bearer ${token}", "Accept": "text/event-stream"],
        readTimeout: STREAM_READ_TIMEOUT,
        rawStream  : true
    ])
    runIn(STREAM_CHECK_SECS, "streamCheck")
}

// STREAM_CHECK_SECS after the last connect() or STOP, whichever is later: the stream is up if it
// has delivered anything since. A closed stream delivers nothing after close() returns.
void streamCheck() {
    String id = device.id.toString()
    if (!liveEnabled() || !TOKENS.get(id)) return
    Long connectedAt = CONNECT_AT.get(id)
    if (connectedAt == null) return
    Long stoppedAt = STOP_AT.get(id)
    long since = Math.max(connectedAt as long, (stoppedAt ?: 0L) as long)
    Long heard = LAST_HEARD.get(id)
    if (heard != null && heard > since) {
        if (stoppedAt != null && stoppedAt > connectedAt) logDebug "stream still up after STOP"
        streamUp()
    } else {
        streamFailed("nothing heard ${(now() - since).intdiv(1000L)} s after ${since == connectedAt ? 'connect' : 'STOP'}")
    }
}

private void streamUp() {
    DOWN_SINCE.remove(device.id.toString())
    if (state.streamFailures) {
        state.remove("streamFailures")
        state.remove("reconnectDelay")
    }
    if (state.streamDown) {
        state.remove("streamDown")
        updateHealth("online", tr("why.streamBack", [:], "en"), tr("why.streamBack"))
    }
}

private void closeStream() {
    try { interfaces.eventStream.close() } catch (Exception e) { logDebug "close: ${e.message}" }
}

// A STOP is not sorted into ours or a failure: the check after it asks whether the current stream
// still delivers. runIn replaces a pending check.
void eventStreamStatus(String s) {
    logNet "stream status: ${s}"
    if (s.startsWith("STOP")) {
        STOP_AT.put(device.id.toString(), now())
        runIn(STREAM_CHECK_SECS, "streamCheck")
    } else if (s.startsWith("ERROR")) {
        logDebug "stream error: ${s}"
    }
}

// Every 5 min: reports offline when nothing has been heard for OFFLINE_AFTER_MS, and reconnects a
// connection thought to be up that has gone silent without a STOP.
void checkStream() {
    if (!liveEnabled()) return
    checkOffline()
    String id = device.id.toString()
    if (DOWN_SINCE.get(id) == null && TOKENS.get(id) && silentFor() > SILENT_AFTER_MS)
        streamFailed("nothing heard for ${silentFor().intdiv(1000L)} s")
}

private void checkOffline() {
    Long since = DOWN_SINCE.get(device.id.toString())
    if (since == null || now() - since < OFFLINE_AFTER_MS || state.streamDown) return
    state.streamDown = true
    logWarn "live power lost (nothing from Hilo for ${(now() - since).intdiv(1000L)} s); retrying with backoff up to ${BACKOFF_MAX_SECS} s"
    updateHealth("offline", tr("why.streamDown", [:], "en"), tr("why.streamDown"))
}

// After a code save the in-memory times are empty: start the clock instead of counting from 1970.
private long silentFor() {
    String id = device.id.toString()
    Long t = LAST_HEARD.get(id)
    if (t == null) { t = now(); LAST_HEARD.put(id, t) }
    return now() - (t as long)
}

// Retries with a delay that doubles from BACKOFF_MIN_SECS to BACKOFF_MAX_SECS. The outage counts
// from the last block heard.
private void streamFailed(String why) {
    String id = device.id.toString()
    DOWN_SINCE.putIfAbsent(id, now() - silentFor())
    int n = ((state.streamFailures ?: 0) as int) + 1
    int delay = (state.reconnectDelay ?: BACKOFF_MIN_SECS) as int
    state.streamFailures = n
    state.reconnectDelay = Math.min(delay * 2, BACKOFF_MAX_SECS)
    logDebug "${why}; reconnect ${n} in ${delay} s"
    checkOffline()
    runIn(delay, "connectStream")
}

// rawStream delivery: parse() gets each SSE block whole, keepalive comments included. Kept cheap:
// it runs every 12 s, and touches state only when the stream was down.
void parse(String s) {
    String id = device.id.toString()
    long t = now()
    Long prev = LAST_HEARD.put(id, t)
    if (DOWN_SINCE.containsKey(id)) streamUp()
    List<String> lines = s.readLines()
    List<String> data = lines.findAll { it.startsWith("data:") }.collect { it.substring(5).trim() }
    if (data.isEmpty()) {
        if (lines.every { it.isEmpty() || it.startsWith(":") }) { logTrace "keepalive (+${prev == null ? '-' : (t - prev)} ms)"; return }
        // A stream opened by an older version, before its reconnect, delivers the bare payload.
        if (s.trim().startsWith("{")) data = [s.trim()]
        else { logDebug "stream block without data: ${s.take(120)}"; return }
    }
    logTrace "stream: ${data.join('\n')}"
    handleData(data.join("\n"))
}

private void handleData(String s) {
    Map msg
    try { msg = parseJson(s.trim()) as Map } catch (Exception e) { logDebug "unreadable stream data: ${s.take(120)}"; return }
    if (msg?.errors) { logWarn "Hilo stream error: ${msg.errors}"; return }
    Map upd = msg?.data?.onAnyDeviceUpdated as Map
    Map power = upd?.device?.power as Map
    if (!power || power.value == null) return
    // Hilo sends "KILOWATT"; anything else but watts is taken as kW.
    String kind = "${power.kind}".toUpperCase()
    BigDecimal kw = (power.value as BigDecimal) * (kind in ["W", "WATT", "WATTS"] ? 0.001 : 1)
    int watts = (kw * 1000).setScale(0, BigDecimal.ROUND_HALF_UP).intValue()
    sendEvent(name: "power", value: watts, unit: "W")
}

private static String enc(String s) { return URLEncoder.encode(s, "UTF-8").replace("+", "%20") }

void logsOff() {
    device.updateSetting("debugEnable", [value: "false", type: "bool"])
    device.updateSetting("traceEnable", [value: "false", type: "bool"])
    logWarn "debug and trace logging disabled"
}

// ── Logging (driver) ──────────────────────────────────────────────────
//   ⬇️ Rx  ⬆️ Cmd  🔧 Cfg  🌐 Net  ⏰ Sched  📦 Ota  🏷️ Ver  ·  ⚠️ Warn  🛑 Error  🔬 Trace
private String logp(String e) { "${e} ${device.displayName}: " }

void logNet  (String m) { if (debugEnable) log.debug logp('🌐') + m }
void logVer  (String m) { log.warn  logp('🏷️') + m }

void logWarn (String m) { log.warn  logp('⚠️') + m }
void logError(String m) { log.error logp('🛑') + m }
void logTrace(String m) { if (traceEnable) log.trace logp('🔬') + m }
void logInfo (String m) { if (txtEnable != false) log.info  "${device.displayName}: ${m}" }
void logDebug(String m) { if (debugEnable) log.debug "${device.displayName}: ${m}" }
void logRx   (String m) { if (debugEnable) log.debug logp('⬇️') + m }
void logCmd  (String m) { if (txtEnable != false) log.info  logp('⬆️') + m }
void logCfg  (String m) { if (txtEnable != false) log.info  logp('🔧') + m }
void logSched(String m) { if (debugEnable) log.debug logp('⏰') + m }
void logOta  (String m) { if (txtEnable != false) log.info  logp('📦') + m }

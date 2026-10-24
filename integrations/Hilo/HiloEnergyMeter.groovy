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
 * closing, so the driver reopens it with a fresh token every STREAM_ROTATE_SECS.
 * Hilo sends a keepalive comment every 12 s; readTimeout ends a stream that
 * stops sending anything.
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
        attribute "lastUpdate",      "string"
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

@Field static final String CODE_VERSION = "0.1.0"

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
// Reopen before the stream goes silent, about 57 min after it opens (one sample).
@Field static final int STREAM_ROTATE_SECS = 3000
// Three missed keepalives (12 s apart) end the stream with an ERROR status.
@Field static final int STREAM_READ_TIMEOUT = 40
@Field static final int BACKOFF_MIN_SECS = 5
@Field static final int BACKOFF_MAX_SECS = 300
// Failed reconnects in a row before the device reports offline.
@Field static final int STREAM_OFFLINE_AFTER = 3
// No power update for this long while the stream should be open: reconnect.
@Field static final long STALE_DATA_MS = 300_000L
@Field static final int DEBUG_LOG_TIMEOUT = 1800
// Per device: time of an intentional close, so the closed stream's STOP is not taken for a
// failure. The next START clears it: a STOP after that belongs to the new stream. In memory:
// a driver's atomicState write after the first state access is lost.
@Field static final ConcurrentHashMap<String, Long> CLOSING = new ConcurrentHashMap<>()
// Per device: time of the last stream data, kept off the database.
@Field static final ConcurrentHashMap<String, Long> LAST_DATA = new ConcurrentHashMap<>()

void installed() { logDebug "installed"; state.version = CODE_VERSION }

void updated() {
    logDebug "updated"
    unschedule()
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
    if (liveEnabled()) {
        runIn(2, "connectStream")
        schedule("${new Random().nextInt(60)} */5 * ? * *", "checkStream")
    }
}

void deviceTypeUpdated() { logDebug "driver change detected" }

void refresh() {
    checkVersion()
    parent?.refreshNow()
    if (liveEnabled() && streamStale()) connectStream()
}

private void checkVersion(boolean reinit = true) {
    if (state.version == CODE_VERSION) return
    logVer "new version: ${CODE_VERSION} (was: ${state.version})"
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
    sendEvent(name: "lastUpdate", value: new Date().format("yyyy-MM-dd HH:mm:ss", location.timeZone))
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

void startLiveStream() { if (liveEnabled()) runIn(2, "connectStream") }

void stopLiveStream() {
    unschedule("connectStream")
    unschedule("rotateStream")
    closeStream()
}

// ── Event stream ──────────────────────────────────────────────────────

void connectStream() {
    if (!liveEnabled()) return
    String hiloId = device.getDataValue("locationHiloId")
    String token = parent?.hiloAccessToken()
    if (!hiloId || !token) { streamFailed(hiloId ? "no access token" : "no Hilo location"); return }
    closeStream()
    String url = GRAPHQL_URL + "?query=" + enc(SUBSCRIPTION) + "&variables=" + enc(JsonOutput.toJson([locationHiloId: hiloId]))
    logNet "connecting to Hilo"
    LAST_DATA.put(device.id.toString(), now())
    interfaces.eventStream.connect(url, [
        headers    : ["Authorization": "Bearer ${token}", "Accept": "text/event-stream"],
        readTimeout: STREAM_READ_TIMEOUT
    ])
    runIn(STREAM_ROTATE_SECS, "rotateStream")
}

// Reopens the stream with a fresh token before Hilo lets it go silent.
void rotateStream() {
    logNet "reopening the stream with a fresh token"
    connectStream()
}

private void closeStream() {
    CLOSING.put(device.id.toString(), now())
    try { interfaces.eventStream.close() } catch (Exception e) { logDebug "close: ${e.message}" }
}

void eventStreamStatus(String s) {
    logNet "stream status: ${s}"
    if (s.startsWith("START")) { CLOSING.remove(device.id.toString()); return }
    if (s.startsWith("STOP")) {
        Long closedAt = CLOSING.remove(device.id.toString())
        if (closedAt != null && now() - closedAt < 10_000L) return
        streamFailed("stream stopped")
    } else if (s.startsWith("ERROR")) {
        logDebug "stream error: ${s}"
    }
}

// Backstop for a stream that ended without a status the driver acted on.
void checkStream() {
    if (liveEnabled() && streamStale()) {
        logDebug "no stream data for ${(now() - lastData()).intdiv(1000L)} s"
        streamFailed("no data from Hilo")
    }
}

private long lastData() { return (LAST_DATA.get(device.id.toString()) ?: 0L) as long }

private boolean streamStale() { return now() - lastData() > STALE_DATA_MS }

// Retries with a delay that doubles from BACKOFF_MIN_SECS to BACKOFF_MAX_SECS.
private void streamFailed(String why) {
    unschedule("rotateStream")
    int n = ((state.streamFailures ?: 0) as int) + 1
    int delay = (state.reconnectDelay ?: BACKOFF_MIN_SECS) as int
    state.streamFailures = n
    state.reconnectDelay = Math.min(delay * 2, BACKOFF_MAX_SECS)
    if (n < STREAM_OFFLINE_AFTER) logDebug "${why}; reconnecting in ${delay} s"
    else if (n == STREAM_OFFLINE_AFTER) { logWarn "live power lost (${why}); retrying every ${delay}–${BACKOFF_MAX_SECS} s"; updateHealth("offline", tr("why.streamDown", [:], "en"), tr("why.streamDown")) }
    else logDebug "${why}; reconnect ${n} in ${delay} s"
    runIn(delay, "connectStream")
}

// Default delivery: parse() gets the data of each `event: next`, nothing for keepalives.
void parse(String s) {
    logTrace "stream: ${s}"
    Map msg
    try { msg = parseJson(s.trim()) as Map } catch (Exception e) { logDebug "unreadable stream data: ${s.take(120)}"; return }
    LAST_DATA.put(device.id.toString(), now())
    if (msg?.errors) { logWarn "Hilo stream error: ${msg.errors}"; return }
    Map upd = msg?.data?.onAnyDeviceUpdated as Map
    Map power = upd?.device?.power as Map
    if (!power || power.value == null) return
    // Hilo sends "KILOWATT"; anything else but watts is taken as kW.
    String kind = "${power.kind}".toUpperCase()
    BigDecimal kw = (power.value as BigDecimal) * (kind in ["W", "WATT", "WATTS"] ? 0.001 : 1)
    int watts = (kw * 1000).setScale(0, BigDecimal.ROUND_HALF_UP).intValue()
    sendEvent(name: "power", value: watts, unit: "W")
    if (state.streamFailures) {
        boolean wasOffline = (state.streamFailures as int) >= STREAM_OFFLINE_AFTER
        state.remove("streamFailures")
        state.remove("reconnectDelay")
        if (wasOffline) updateHealth("online", tr("why.streamBack", [:], "en"), tr("why.streamBack"))
    }
}

private static String enc(String s) { return URLEncoder.encode(s, "UTF-8").replace("+", "%20") }

void logsOff() {
    logWarn "debug and trace logging disabled"
    device.updateSetting("debugEnable", [value: "false", type: "bool"])
    device.updateSetting("traceEnable", [value: "false", type: "bool"])
}

// ── Logging (driver) ──────────────────────────────────────────────────
//   🌐 Net  🏷️ Ver  ·  ⚠️ Warn  🛑 Error  🔬 Trace
private String logp(String e) { "${e} ${device.displayName}: " }

void logNet  (String m) { if (settings.debugEnable) log.debug logp('🌐') + m }
void logVer  (String m) { log.warn  logp('🏷️') + m }

void logWarn (String m) { log.warn  logp('⚠️') + m }
void logError(String m) { log.error logp('🛑') + m }
void logTrace(String m) { if (settings.traceEnable) log.trace logp('🔬') + m }
void logInfo (String m) { if (txtEnable != false) log.info  "${device.displayName}: ${m}" }
void logDebug(String m) { if (settings.debugEnable) log.debug "${device.displayName}: ${m}" }

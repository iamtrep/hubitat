// Copyright (c) 2026 PJ
// SPDX-License-Identifier: MIT

/*
 * Québec Air Quality Index (IQA) Driver for Hubitat
 *
 * Reports the hourly IQA observed at the nearest Québec monitoring station. Two feeds:
 * the provincial network (RSQAQ, ArcGIS REST, excludes the island of Montréal) and the
 * City of Montréal network (RSQA, open-data datastore). Environment Canada does not publish
 * AQHI observations for Québec; use the Environment Canada AQHI driver for forecasts.
 */

import groovy.transform.CompileStatic
import groovy.transform.Field

@Field static final String CODE_VERSION = "0.2.3"
@Field static final String RSQAQ_URL = "https://services3.arcgis.com/0lL78GhXbg1Po7WO/arcgis/rest/services/IQA_resultat_REST/FeatureServer/0/query"
// CKAN datastore; GET ignores filters, so queries are POSTed
@Field static final String MTL_DATASTORE_URL = "https://donnees.montreal.ca/api/3/action/datastore_search"
@Field static final String MTL_STATIONS_RESOURCE = "6554355e-63d1-4a01-a268-91e0763c3606"
@Field static final String MTL_DETAILS_RESOURCE = "f4eca3bf-5ded-4d3c-a8dc-ed42486498f3"
@Field static final String MTL_PREFIX = "MTL-"
@Field static final int HTTP_TIMEOUT = 15
// Failed polls log at warn until this many in a row, then once at error, then at
// warn every OUTAGE_REMINDER_MS until a poll succeeds.
@Field static final int FAILURE_ERROR_THRESHOLD = 3
@Field static final long OUTAGE_REMINDER_MS = 3_600_000L

// Sub-index fields: RSQAQ column / Montréal pollutant label -> attribute
@Field static final Map<String, String> RSQAQ_POLLUTANTS = [PM25: "pm25", O3: "o3", NO2: "no2", SO2: "so2", CO: "co"]
@Field static final Map<String, String> MTL_POLLUTANTS = ["PM2.5": "pm25", O3: "o3", NO2: "no2", SO2: "so2", CO: "co"]
@Field static final Map<String, String> POLLUTANT_LABELS = [pm25: "PM2.5", o3: "O3", no2: "NO2", so2: "SO2", co: "CO"]

metadata {
    definition(
        name: "Québec Air Quality Index (IQA)",
        namespace: "iamtrep",
        author: "pj",
        singleThreaded: true,
        description: "Hourly Québec air quality index (IQA) from the nearest provincial or Montréal monitoring station",
        importUrl: "https://raw.githubusercontent.com/iamtrep/hubitat/refs/heads/main/drivers/QuebecIQA.groovy"
    ) {
        capability "AirQuality"
        capability "Initialize"
        capability "Refresh"
        capability "Sensor"

        attribute "iqa", "number"
        attribute "iqaCategory", "enum", ["Good", "Acceptable", "Poor"]
        attribute "dominantPollutant", "string"

        // Per-pollutant sub-indices (IQA units); the IQA is the highest of them
        attribute "pm25", "number"
        attribute "o3", "number"
        attribute "no2", "number"
        attribute "so2", "number"
        attribute "co", "number"

        attribute "stationName", "string"
        attribute "observationTime", "string"
        attribute "lastUpdated", "string"
        attribute "healthStatus", "enum", ["online", "offline"]

        command "findNearestStation"
    }
}

preferences {
    section("Station") {
        input "stationId", "text", title: "Station ID", description: "Leave blank to auto-detect from hub location. Provincial stations are 5 digits (e.g. 06205 for Laval), Montréal stations are MTL-<id> (e.g. MTL-80)", required: false
        input("pollRate", "number", title: "Polling interval (minutes)\nZero for no polling:", defaultValue: 60, range: "0..*")
        input("staleThreshold", "number", title: "Staleness threshold (hours)\nWarn when the station has not reported for this long:", defaultValue: 6, range: "1..168")
    }
    section("Logging") {
        input name: "txtEnable", type: "bool", title: "Enable info logging", defaultValue: true
        input name: "debugEnable", type: "bool", title: "Enable debug logging", defaultValue: false, submitOnChange: true
        if (debugEnable) {
            input name: "traceEnable", type: "bool", title: "Enable trace logging", defaultValue: false
        }
    }
}

// Lifecycle

void installed() {
    state.version = CODE_VERSION
    initialize()
}

void uninstalled() {
    unschedule()
}

void updated() {
    unschedule()
    initialize()
}

void deviceTypeUpdated() {
    logDebug "driver change detected"
}

void initialize() {
    if (state.version != CODE_VERSION) {
        logVer "New driver version: ${CODE_VERSION} (was: ${state.version})"
        state.version = CODE_VERSION
    }

    if (debugEnable || traceEnable) {
        runIn(1800, "turnOffDebugLogging")
    }

    if (!resolveStationId()) {
        logCfg "No station configured — auto-detecting nearest station from hub location"
        findNearestStation()
        return
    }

    schedulePoll()
    runIn(1, "refresh")
}

private String resolveStationId() {
    return stationId?.trim() ?: state.autoStationId
}

void schedulePoll() {
    int rate = (pollRate != null) ? pollRate as int : 60
    if (rate <= 0) return
    Random rng = new Random()
    String cron
    if (rate < 60) {
        cron = "${rng.nextInt(60)} */${rate} * ? * *"
    } else {
        // Both feeds publish the previous hour 10+ minutes past the hour
        int hours = Math.max(1, rate.intdiv(60))
        cron = "${rng.nextInt(60)} ${15 + rng.nextInt(30)} */${hours} ? * *"
    }
    schedule(cron, "refresh")
    logSched "Scheduled polling every ${rate} minutes"
}

// Main refresh

void refresh() {
    if (state.version != CODE_VERSION) {
        updated()
        return
    }
    String sid = resolveStationId()
    if (!sid) {
        logWarn "No station configured — use Find Nearest Station or set Station ID in preferences"
        return
    }
    if (isMontreal(sid)) {
        fetchMontreal(normalizeMtlId(sid.substring(MTL_PREFIX.length())))
    } else {
        fetchProvincial(sid)
    }
}

@CompileStatic
static boolean isMontreal(String sid) {
    return sid.toUpperCase().startsWith(MTL_PREFIX)
}

// The Montréal feed writes the same station as "03" and "3"
@CompileStatic
static String normalizeMtlId(String id) {
    String s = id.trim().replaceFirst(/^0+/, "")
    return s ?: "0"
}

// ---------- Provincial network (RSQAQ) ----------

void fetchProvincial(String sid) {
    logNet "Fetching RSQAQ observation for ${sid}"
    Map params = [
        uri: RSQAQ_URL,
        query: [where: "NO_STATION='${sid}'", outFields: "*", returnGeometry: "false", f: "json"],
        timeout: HTTP_TIMEOUT
    ]
    asynchttpGet("provincialResponse", params, [sid: sid])
}

void provincialResponse(resp, Map data) {
    if (resp.hasError()) {
        pollFailed("RSQAQ: ${resp.getErrorMessage()}")
        return
    }
    if (resp.getStatus() != 200) {
        pollFailed("RSQAQ: HTTP ${resp.getStatus()}")
        return
    }
    try {
        Map json = parseJson(resp.getData() as String) as Map
        if (json?.error) {
            pollFailed("RSQAQ query error: ${(json.error as Map)?.message}")
            return
        }
        List features = json?.features as List
        Map a = features ? ((features[0] as Map)?.attributes as Map) : null
        if (!a) {
            pollFailed("RSQAQ has no station ${data.sid}")
            return
        }
        pollSucceeded()
        // -99 marks a pollutant the station does not measure
        Map<String, Integer> subs = [:]
        RSQAQ_POLLUTANTS.each { String col, String attr ->
            BigDecimal v = a[col] as BigDecimal
            if (v != null && v >= 0) subs[attr] = Math.round(v) as int
        }
        int iqa = (a.IQA ?: 0) as int
        Long obsMillis = a.DATE_HEURE as Long
        publishObservation(iqa, subs, a.NOM_STATION as String, obsMillis)
    } catch (Exception e) {
        pollFailed("RSQAQ parse: ${e.message}")
    }
}

// ---------- Montréal network (RSQA) ----------

void fetchMontreal(String id) {
    logNet "Fetching Montréal RSQA observation for station ${id}"
    // Newest rows first; 30 covers one hour of every pollutant
    Map params = [
        uri: MTL_DATASTORE_URL,
        requestContentType: "application/json",
        contentType: "application/json",
        body: [resource_id: MTL_DETAILS_RESOURCE, filters: [stationId: mtlIdVariants(id)], sort: "_id desc", limit: 30],
        timeout: HTTP_TIMEOUT
    ]
    asynchttpPost("montrealResponse", params, [id: id])
}

void montrealResponse(resp, Map data) {
    if (resp.hasError()) {
        pollFailed("Montréal open data: ${resp.getErrorMessage()}")
        return
    }
    if (resp.getStatus() != 200) {
        pollFailed("Montréal open data: HTTP ${resp.getStatus()}")
        return
    }
    try {
        Map json = parseJson(resp.getData() as String) as Map
        if (!json?.success) {
            pollFailed("Montréal datastore error: ${json?.error}")
            return
        }
        pollSucceeded()
        Map obs = latestMontrealHour((json.result as Map)?.records as List<Map>, data.id as String)
        if (!obs) {
            checkStaleness()
            return
        }
        Map<String, Integer> subs = obs.subs as Map<String, Integer>
        int iqa = subs ? (subs.values().max() as int) : 0
        String name = state.autoStationName && state.autoStationId == (MTL_PREFIX + data.id) ? state.autoStationName as String : "Montréal station ${data.id}"
        publishObservation(iqa, subs, name, obs.millis as Long)
    } catch (Exception e) {
        pollFailed("Montréal parse: ${e.message}")
    }
}

// The feed spells single-digit station IDs both padded and unpadded
@CompileStatic
static List<String> mtlIdVariants(String id) {
    return id.length() == 1 ? [id, "0" + id] : [id]
}

// Records: stationId, pollutant, valeur, date, heure (all strings); hours are EST year-round
@CompileStatic
static Map latestMontrealHour(List<Map> rows, String id) {
    String bestKey = null
    Map<String, Integer> subs = [:]
    if (!rows) return null
    for (Map r : rows) {
        if (normalizeMtlId((r.stationId ?: "") as String) != id) continue
        String attr = MTL_POLLUTANTS[r.pollutant as String]
        String valeur = (r.valeur ?: "") as String
        String heure = (r.heure ?: "") as String
        if (!attr || !valeur.isInteger() || !heure.isInteger()) continue
        String key = "${r.date} ${String.format('%02d', Integer.parseInt(heure))}"
        if (bestKey == null || key > bestKey) {
            bestKey = key
            subs = [:]
        }
        if (key == bestKey) subs[attr] = Integer.parseInt(valeur)
    }
    if (bestKey == null) return null
    java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH")
    sdf.setTimeZone(TimeZone.getTimeZone("GMT-05:00"))
    return [subs: subs, millis: sdf.parse(bestKey).getTime()]
}

// ---------- Events ----------

private void publishObservation(int iqa, Map<String, Integer> subs, String name, Long obsMillis) {
    if (iqa <= 0) {
        logDebug "Station reported no IQA this hour"
        checkStaleness()
        return
    }
    String dominant = subs ? subs.max { it.value }.key : null
    String category = iqaCategory(iqa)

    sendEvent(name: "iqa", value: iqa, unit: "IQA")
    sendEvent(name: "airQualityIndex", value: iqaToAQI(iqa))
    sendEvent(name: "iqaCategory", value: category)
    if (dominant) sendEvent(name: "dominantPollutant", value: POLLUTANT_LABELS[dominant])
    subs.each { String attr, Integer v -> sendEvent(name: attr, value: v, unit: "IQA") }
    if (name) sendEvent(name: "stationName", value: name)

    String obsText = ""
    if (obsMillis) {
        state.lastObservationMillis = obsMillis
        obsText = new Date(obsMillis).format("yyyy-MM-dd HH:mm", location.timeZone)
        sendEvent(name: "observationTime", value: obsText)
    }
    sendEvent(name: "lastUpdated", value: new Date().format("yyyy-MM-dd HH:mm:ss"))
    logInfo "IQA ${iqa} (${category}${dominant ? ', ' + POLLUTANT_LABELS[dominant] : ''}) observed at ${obsText}"
    checkStaleness()
}

// Runs after every answered poll and sets healthStatus from the station's last report.
private void checkStaleness() {
    String online = (state.remove("recoveryNote") ?: "reporting") as String
    Long last = state.lastObservationMillis as Long
    if (!last) {
        setHealth("online", online)
        return
    }
    int thresholdHours = (staleThreshold != null) ? staleThreshold as int : 6
    long ageHours = (now() - last).intdiv(3600000)
    boolean stale = ageHours >= thresholdHours
    if (stale && !state.stale) {
        logWarn "Station has not reported for ${ageHours}h (threshold: ${thresholdHours}h)"
    } else if (!stale && state.stale) {
        logInfo "Station reporting again"
    }
    state.stale = stale
    if (stale) setHealth("offline", "station has not reported for ${ageHours} h")
    else setHealth("online", online)
}

// --- Outage handling ---

// Warn below FAILURE_ERROR_THRESHOLD, one error at it, then a warn every OUTAGE_REMINDER_MS.
private void pollFailed(String msg) {
    long t = now()
    int n = ((state.pollFailures ?: 0) as int) + 1
    state.pollFailures = n
    if (n == 1) state.pollFailingSince = t
    long since = state.pollFailingSince as long
    if (n < FAILURE_ERROR_THRESHOLD) {
        logWarn "poll failed (${n}/${FAILURE_ERROR_THRESHOLD}): ${msg}"
    } else if (n == FAILURE_ERROR_THRESHOLD) {
        logError "IQA service unreachable since ${formatClock(since)}: ${msg}"
        state.lastOutageReminder = t
        setHealth("offline", "IQA service unreachable")
    } else if (t - ((state.lastOutageReminder ?: 0L) as long) >= OUTAGE_REMINDER_MS) {
        logWarn "IQA service still unreachable after ${formatDuration(t - since)} (${n} polls failed): ${msg}"
        state.lastOutageReminder = t
    } else {
        logDebug "poll failed (${n}): ${msg}"
    }
}

// The caller then runs checkStaleness(), which sets healthStatus and uses state.recoveryNote.
private void pollSucceeded() {
    int n = (state.pollFailures ?: 0) as int
    if (n >= FAILURE_ERROR_THRESHOLD) {
        String recovery = "IQA service back after ${formatDuration(now() - (state.pollFailingSince as long))} (${n} polls failed)"
        logInfo recovery
        state.recoveryNote = recovery
    } else if (n > 0) {
        logDebug "poll recovered after ${n} failed"
    }
    state.remove("pollFailures")
    state.remove("pollFailingSince")
    state.remove("lastOutageReminder")
}

private void setHealth(String status, String reason) {
    String prev = device.currentValue("healthStatus")
    sendEvent(name: "healthStatus", value: status, descriptionText: "${device.displayName} is ${status}: ${reason}")
    if (prev != null && prev != status) {
        if (status == "offline") logWarn "offline: ${reason}"
        else logInfo "back online"
    }
}

private String formatClock(long t) {
    return new Date(t).format("yyyy-MM-dd HH:mm", location.timeZone)
}

private static String formatDuration(long ms) {
    long minutes = ms.intdiv(60000L)
    if (minutes < 60) return "${minutes} min"
    return "${minutes.intdiv(60L)} h ${minutes % 60} min"
}

// ---------- Find nearest station ----------

void findNearestStation() {
    BigDecimal hubLat = location.latitude as BigDecimal
    BigDecimal hubLon = location.longitude as BigDecimal
    if (!hubLat || !hubLon) {
        logWarn "Hub location not configured — set latitude/longitude in hub settings first"
        return
    }
    logInfo "Finding nearest IQA station to hub location (${hubLat}, ${hubLon})..."

    // Async because initialize() calls this on hub start and save, which must not block.
    // The two lookups are chained so the second callback sees the first one's stations.
    asynchttpGet("rsqaqStationsResponse",
        [uri: RSQAQ_URL, query: [where: "1=1", outFields: "NO_STATION,NOM_STATION,LATITUDE,LONGITUDE,IQA", returnGeometry: "false", f: "json"],
         timeout: HTTP_TIMEOUT],
        [lat: hubLat.toString(), lon: hubLon.toString()])
}

// Station lists ride in the callback data as [id, name, lat, lon]
void rsqaqStationsResponse(resp, Map data) {
    List<List> stations = []
    if (resp.hasError()) {
        logWarn "Error fetching RSQAQ stations: ${resp.getErrorMessage()}"
    } else if (resp.getStatus() != 200) {
        logWarn "Error fetching RSQAQ stations: HTTP ${resp.getStatus()}"
    } else {
        try {
            Map json = parseJson(resp.getData() as String) as Map
            for (Map f : (json?.features as List ?: [])) {
                Map a = f.attributes as Map
                // Stations with no current IQA (all pollutants -99) are skipped
                if (a?.LATITUDE == null || ((a.IQA ?: 0) as int) <= 0) continue
                stations << [a.NO_STATION as String, a.NOM_STATION as String, a.LATITUDE as double, a.LONGITUDE as double]
            }
        } catch (Exception e) {
            logWarn "Error parsing RSQAQ stations: ${e.message}"
        }
    }
    asynchttpPost("montrealStationsResponse",
        [uri: MTL_DATASTORE_URL, requestContentType: "application/json", contentType: "application/json",
         body: [resource_id: MTL_STATIONS_RESOURCE, fields: ["stationId", "address", "latitude", "longitude"], distinct: true, limit: 100],
         timeout: HTTP_TIMEOUT],
        [lat: data.lat, lon: data.lon, stations: stations])
}

void montrealStationsResponse(resp, Map data) {
    List<List> stations = ((data.stations ?: []) as List<List>).collect()
    if (resp.hasError()) {
        logWarn "Error fetching Montréal stations: ${resp.getErrorMessage()}"
    } else if (resp.getStatus() != 200) {
        logWarn "Error fetching Montréal stations: HTTP ${resp.getStatus()}"
    } else {
        try {
            Map json = parseJson(resp.getData() as String) as Map
            Set<String> seen = [] as Set
            for (Map r : ((json?.result as Map)?.records as List<Map> ?: [])) {
                String id = normalizeMtlId((r.stationId ?: "") as String)
                if (!r.latitude || !r.longitude || !seen.add(id)) continue
                stations << ["${MTL_PREFIX}${id}" as String, "Montréal – ${r.address}" as String, r.latitude as double, r.longitude as double]
            }
        } catch (Exception e) {
            logWarn "Error parsing Montréal stations: ${e.message}"
        }
    }
    selectNearestStation(stations, (data.lat as String) as double, (data.lon as String) as double)
}

private void selectNearestStation(List<List> stations, double hubLat, double hubLon) {
    if (stations.isEmpty()) {
        logWarn "No IQA stations found"
        return
    }
    List nearest = stations.min { List s -> haversine(hubLat, hubLon, s[2] as double, s[3] as double) }
    int km = Math.round(haversine(hubLat, hubLon, nearest[2] as double, nearest[3] as double)) as int

    state.autoStationId = nearest[0]
    state.autoStationName = nearest[1]
    state.autoStationDistance = km
    logInfo "Nearest station: ${nearest[1]} (${nearest[0]}), ${km} km away"
    sendEvent(name: "stationName", value: nearest[1] as String)

    schedulePoll()
    runIn(2, "refresh")
}

@CompileStatic
static double haversine(double lat1, double lon1, double lat2, double lon2) {
    double R = 6371.0
    double dLat = Math.toRadians(lat2 - lat1)
    double dLon = Math.toRadians(lon2 - lon1)
    double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
               Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
               Math.sin(dLon / 2) * Math.sin(dLon / 2)
    return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
}

// Québec IQA categories: Good 1–25, Acceptable 26–50, Poor above 50
@CompileStatic
static String iqaCategory(int value) {
    if (value <= 25) return "Good"
    if (value <= 50) return "Acceptable"
    return "Poor"
}

// Category-aligned approximation: Good 1–25 -> AQI 0–50, Acceptable 26–50 -> 51–100, linear above
@CompileStatic
static int iqaToAQI(int iqa) {
    return Math.min(500, Math.max(0, iqa * 2))
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

void turnOffDebugLogging() {
    logWarn "Debug logging disabled"
    device.updateSetting("debugEnable", [value: "false", type: "bool"])
    device.updateSetting("traceEnable", [value: "false", type: "bool"])
}

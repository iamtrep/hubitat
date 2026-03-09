/*
 * Environment Canada AQHI (Air Quality Health Index) Driver for Hubitat
 *
 * Retrieves current AQHI observations, hourly forecasts, and alerts from the
 * Environment Canada GeoMet OGC API (api.weather.gc.ca).
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 * for the specific language governing permissions and limitations under the License.
 *
 */

import groovy.transform.CompileStatic
import groovy.transform.Field

@Field static final String DRIVER_VERSION = "0.2.0"
@Field static final String API_BASE = "https://api.weather.gc.ca/collections"
@Field static final String ALERT_API_BASE = "https://weather.gc.ca/api/app/v3"
@Field static final int HTTP_TIMEOUT = 15

// Station ID → Alert Zone Code mapping (Environment Canada, March 2026)
@Field static final Map<String, String> STATION_TO_ZONE = [
    // Newfoundland & Labrador
    "ABEFS": "NLAQ-001", "AAEOU": "NLAQ-002", "ABYRK": "NLAQ-003", "AADCE": "NLAQ-004", "ABFQA": "NLAQ-005",
    // Prince Edward Island
    "BAEMV": "PEAQ-001", "BADSZ": "PEAQ-002", "BAARG": "PEAQ-003",
    // Nova Scotia
    "CBUCG": "NSAQ-001", "CASWE": "NSAQ-002", "CBLGX": "NSAQ-003", "CBELL": "NSAQ-005",
    "CBDPK": "NSAQ-006", "CAPHL": "NSAQ-007", "CATHI": "NSAQ-008",
    // New Brunswick
    "DAEGW": "NBAQ-001", "DAFMJ": "NBAQ-002", "DADHJ": "NBAQ-003", "DAFQX": "NBAQ-004",
    "DBEDJ": "NBAQ-005", "DALZZ": "NBAQ-006", "DAHEI": "NBAQ-007", "DAEBC": "NBAQ-008",
    // Quebec
    "EHHUN": "QCAQ-001", "EHTWR": "QCAQ-002", "EGLTT": "QCAQ-003",
    // Ontario
    "FEUZB": "ONAQ-001", "FALIF": "ONAQ-002", "FAMXK": "ONAQ-003", "FEAKO": "ONAQ-004",
    "FDGED": "ONAQ-005", "FCGKZ": "ONAQ-006", "FDMOP": "ONAQ-007", "FEVNT": "ONAQ-008",
    "FEVNS": "ONAQ-009", "FDEGT": "ONAQ-010", "FCAEN": "ONAQ-011", "FEVJR": "ONAQ-012",
    "FDGEJ": "ONAQ-013", "FDZCP": "ONAQ-014", "FDJFN": "ONAQ-015", "FAFFD": "ONAQ-016",
    "FAZKI": "ONAQ-017", "FCWYG": "ONAQ-018", "FDQBU": "ONAQ-019", "FDQBX": "ONAQ-020",
    "FCKTB": "ONAQ-021", "FDATE": "ONAQ-022", "FCNJT": "ONAQ-023", "FEUTC": "ONAQ-024",
    "FEARV": "ONAQ-025", "FCIBD": "ONAQ-026", "FAYJG": "ONAQ-027", "FCWOV": "ONAQ-028",
    "FALJI": "ONAQ-029", "FEBWC": "ONAQ-030", "FBKKK": "ONAQ-031", "FBLJL": "ONAQ-032",
    "FBLKS": "ONAQ-033", "FDCHU": "ONAQ-034", "FDSUS": "ONAQ-035", "FCCOT": "ONAQ-036",
    "FCFUU": "ONAQ-040", "FCTOV": "ONAQ-041", "FCWFX": "ONAQ-042", "FEVJS": "ONAQ-043",
    "FDGEM": "ONAQ-044",
    // Manitoba
    "GBEIN": "MBAQ-001", "GADMZ": "MBAQ-002", "GAILW": "MBAQ-003",
    // Saskatchewan
    "HAIMP": "SKAQ-001", "HAHJJ": "SKAQ-002", "HAPNV": "SKAQ-003",
    "HAPKT": "SKAQ-004", "HAUQM": "SKAQ-005", "HASIZ": "SKAQ-006",
    // Alberta
    "IACMP": "ABAQ-001", "IAKID": "ABAQ-002", "IAEJS": "ABAQ-003", "IAFFI": "ABAQ-004",
    "IAFFF": "ABAQ-005", "IADGP": "ABAQ-006", "IAEFD": "ABAQ-007", "IAIUC": "ABAQ-008",
    "IAHMM": "ABAQ-009", "IABOA": "ABAQ-010", "IAFEW": "ABAQ-011", "IAFFM": "ABAQ-012",
    "IAFYX": "ABAQ-013", "IAQFR": "ABAQ-014", "IANHQ": "ABAQ-015", "IAASX": "ABAQ-016",
    "IACGW": "ABAQ-017", "IACIQ": "ABAQ-018", "IAJWU": "ABAQ-019", "IACMW": "ABAQ-020",
    "IAGHN": "ABAQ-021", "IAIDN": "ABAQ-022",
    // British Columbia
    "JBRIK": "BCAQ-001", "JABNO": "BCAQ-002", "JCVCC": "BCAQ-003", "JBXHZ": "BCAQ-004",
    "JAZBU": "BCAQ-005", "JBJFI": "BCAQ-006", "JAFNW": "BCAQ-007", "JAFUV": "BCAQ-008",
    "JBLVS": "BCAQ-009", "JBOBQ": "BCAQ-010", "JAQAL": "BCAQ-011", "JBOAP": "BCAQ-012",
    "JBHJG": "BCAQ-013", "JBNTS": "BCAQ-014", "JCLMX": "BCAQ-015", "JCJHI": "BCAQ-016",
    "JBTMB": "BCAQ-017", "JBBWA": "BCAQ-018", "JCVCJ": "BCAQ-020", "JCAAC": "BCAQ-021",
    "JAHJY": "BCAQ-022", "JAGPB": "BCAQ-023", "JBXCK": "BCAQ-024", "JBMQO": "BCAQ-025",
    "JCMDB": "BCAQ-026", "JAIQY": "BCAQ-027", "JBMPJ": "BCAQ-028",
    // Northwest Territories
    "LBAMG": "NTAQ-001", "LALNA": "NTAQ-002", "LAILN": "NTAQ-003", "LARCU": "NTAQ-004", "LAILM": "NTAQ-005",
    // Nunavut
    "OAUDO": "NUAQ-001", "OATSD": "NUAQ-002", "OATRP": "NUAQ-003",
    // Yukon
    "KAHFT": "YTAQ-001"
]

@Field static final List<String> RISK_COLORS = [
    "#00ccff", // 1 - Low
    "#0099cc", // 2 - Low
    "#006699", // 3 - Low
    "#ffff00", // 4 - Moderate
    "#ffcc00", // 5 - Moderate
    "#ff9933", // 6 - Moderate
    "#ff6666", // 7 - High
    "#ff0000", // 8 - High
    "#cc0000", // 9 - High
    "#990000", // 10 - High
    "#660000"  // 10+ - Very High
]

metadata {
    definition(
        name: "Environment Canada AQHI",
        namespace: "iamtrep",
        author: "pj",
        importUrl: "https://raw.githubusercontent.com/iamtrep/hubitat/refs/heads/main/drivers/EnvironmentCanada_AQHI.groovy"
    ) {
        capability "AirQuality"
        capability "Initialize"
        capability "Refresh"
        capability "Sensor"

        // Current observation
        attribute "aqhi", "number"
        attribute "aqhiRisk", "string"
        attribute "observationTime", "string"

        // Forecast at specific horizons
        attribute "forecast3h", "number"
        attribute "forecast6h", "number"
        attribute "forecast12h", "number"
        attribute "forecast24h", "number"
        attribute "forecastMax", "number"
        attribute "forecastMaxRisk", "string"

        // Trend
        attribute "trend", "enum", ["improving", "stable", "worsening"]

        // Alerts
        attribute "alertCount", "number"
        attribute "alertMessage", "string"

        // Dashboard HTML tile
        attribute "forecastHtml", "string"

        // Meta
        attribute "stationName", "string"
        attribute "lastUpdated", "string"

        command "findNearestStation"
    }
}

preferences {
    section("Station") {
        input "stationId", "text", title: "Station ID", description: "Leave blank to auto-detect from hub location (e.g. EHHUN for Montréal)", required: false
        input "alertZoneCode", "text", title: "Alert Zone Code", description: "Auto-detected from station; override here if needed (e.g. QCAQ-001)", required: false
        input("pollRate", "number", title: "Polling interval (minutes)\nZero for no polling:", defaultValue: 60, range: "0..*")
    }
    section("Logging") {
        input name: "txtEnable", type: "bool", title: "Enable descriptionText logging", defaultValue: true
        input name: "debugEnable", type: "bool", title: "Enable debug logging", defaultValue: false, submitOnChange: true
    }
}

// Lifecycle

void installed() {
    state.version = DRIVER_VERSION
    initialize()
}

void uninstalled() {
    unschedule()
}

void updated() {
    unschedule()
    initialize()
}

void initialize() {
    if (state.version != DRIVER_VERSION) {
        logWarn "New driver version: ${DRIVER_VERSION} (was: ${state.version})"
        state.version = DRIVER_VERSION
    }

    if (debugEnable) {
        runIn(1800, "turnOffDebugLogging")
    }

    String sid = resolveStationId()
    if (!sid) {
        logInfo "No station configured — auto-detecting nearest station from hub location"
        findNearestStation()
        return
    }

    schedulePoll()
    runIn(1, "refresh")
}

private String resolveStationId() {
    return stationId ?: state.autoStationId
}

private String resolveAlertZone() {
    if (alertZoneCode) return alertZoneCode
    String sid = resolveStationId()
    if (sid) return STATION_TO_ZONE[sid]
    return null
}

void schedulePoll() {
    int rate = (pollRate != null) ? pollRate as int : 60
    if (rate > 0) {
        Random rng = new Random()
        String cron
        if (rate < 60) {
            cron = "${rng.nextInt(60)} */${rate} * ? * *"
        } else {
            int hours = Math.max(1, (int)(rate / 60))
            cron = "${rng.nextInt(60)} ${rng.nextInt(60)} */${hours} ? * *"
        }
        schedule(cron, "refresh")
        logDebug "Scheduled polling every ${rate} minutes"
    }
}

// Main refresh

void refresh() {
    String sid = resolveStationId()
    if (!sid) {
        logWarn "No station configured — use Find Nearest Station or set Station ID in preferences"
        return
    }
    logDebug "Refreshing AQHI data for station ${sid}"
    fetchObservation(sid)
    fetchForecast(sid)
    String zone = resolveAlertZone()
    if (zone) {
        fetchAlerts(zone)
    }
}

// ---------- Observation ----------

void fetchObservation(String sid) {
    String url = "${API_BASE}/aqhi-observations-realtime/items?f=json&location_id=${sid}&latest=true&limit=1"
    logDebug "Fetching observation: ${url}"

    Map params = [
        uri: url,
        contentType: "application/json",
        timeout: HTTP_TIMEOUT
    ]

    try {
        httpGet(params) { resp ->
            parseObservationResponse(resp)
        }
    } catch (Exception e) {
        logWarn "Error fetching observation: ${e.message}"
    }
}

void parseObservationResponse(resp) {
    if (resp.status != 200) {
        logWarn "Observation API returned status ${resp.status}"
        return
    }

    Map data = resp.data as Map
    List features = data?.features as List
    if (!features || features.isEmpty()) {
        logDebug "No current observation available"
        return
    }

    Map props = (features[0] as Map)?.properties as Map
    if (!props) return

    BigDecimal aqhi = props.aqhi as BigDecimal
    String locationName = props.location_name_en as String
    String obsTime = props.observation_datetime_text_en as String

    int rounded = Math.round(aqhi) as int
    String risk = riskCategory(rounded)

    sendEvent(name: "aqhi", value: aqhi, unit: "AQHI")
    sendEvent(name: "airQualityIndex", value: aqhiToAQI(rounded))
    sendEvent(name: "aqhiRisk", value: risk)
    sendEvent(name: "observationTime", value: obsTime ?: "")
    if (locationName) {
        sendEvent(name: "stationName", value: locationName)
    }
    sendEvent(name: "lastUpdated", value: new Date().format("yyyy-MM-dd HH:mm:ss"))

    if (txtEnable) {
        logInfo "AQHI ${aqhi} (${risk}) observed at ${obsTime}"
    }
}

// ---------- Forecast ----------

void fetchForecast(String sid) {
    String url = "${API_BASE}/aqhi-forecasts-realtime/items?f=json&location_id=${sid}&limit=48"
    logDebug "Fetching forecast: ${url}"

    Map params = [
        uri: url,
        contentType: "application/json",
        timeout: HTTP_TIMEOUT
    ]

    try {
        httpGet(params) { resp ->
            parseForecastResponse(resp)
        }
    } catch (Exception e) {
        logWarn "Error fetching forecast: ${e.message}"
    }
}

void parseForecastResponse(resp) {
    if (resp.status != 200) {
        logWarn "Forecast API returned status ${resp.status}"
        return
    }

    Map data = resp.data as Map
    List features = data?.features as List
    if (!features || features.isEmpty()) {
        logDebug "No forecast data available"
        return
    }

    // Build a sorted list of future forecasts
    long nowMillis = now()
    List<Map> futureForecasts = []
    String locationName = ""

    for (Object f : features) {
        Map props = (f as Map)?.properties as Map
        if (!props) continue

        String forecastDt = props.forecast_datetime as String
        if (!forecastDt) continue

        long forecastMillis = parseISO8601(forecastDt)
        if (forecastMillis <= nowMillis) continue

        int hoursAhead = (int)((forecastMillis - nowMillis) / 3600000)
        BigDecimal aqhi = props.aqhi as BigDecimal

        futureForecasts << [
            hours: hoursAhead,
            aqhi: aqhi,
            time: props.forecast_datetime_text_en as String ?: "",
            dt: forecastDt
        ]

        if (!locationName) {
            locationName = props.location_name_en as String
        }
    }

    futureForecasts.sort { Map a, Map b -> (a.hours as int) <=> (b.hours as int) }

    if (futureForecasts.isEmpty()) {
        logDebug "No future forecast data"
        return
    }

    // Time-horizon attributes: find closest forecast to each target hour
    Map<Integer, BigDecimal> horizons = [3: null, 6: null, 12: null, 24: null]
    for (Map fc : futureForecasts) {
        int h = fc.hours as int
        for (int target : horizons.keySet()) {
            if (horizons[target] == null && h >= target - 1) {
                horizons[target] = fc.aqhi as BigDecimal
            }
        }
    }

    if (horizons[3] != null) sendEvent(name: "forecast3h", value: horizons[3], unit: "AQHI")
    if (horizons[6] != null) sendEvent(name: "forecast6h", value: horizons[6], unit: "AQHI")
    if (horizons[12] != null) sendEvent(name: "forecast12h", value: horizons[12], unit: "AQHI")
    if (horizons[24] != null) sendEvent(name: "forecast24h", value: horizons[24], unit: "AQHI")

    // Forecast max
    BigDecimal maxAqhi = 0
    String maxTime = ""
    for (Map fc : futureForecasts) {
        BigDecimal val = fc.aqhi as BigDecimal
        if (val > maxAqhi) {
            maxAqhi = val
            maxTime = fc.time as String
        }
    }

    if (maxAqhi > 0) {
        int rounded = Math.round(maxAqhi) as int
        sendEvent(name: "forecastMax", value: maxAqhi, unit: "AQHI")
        sendEvent(name: "forecastMaxRisk", value: riskCategory(rounded))
        logDebug "Forecast max AQHI: ${maxAqhi} (${riskCategory(rounded)})"
    }

    // Trend: compare current AQHI to average of next 3 hours
    BigDecimal currentAqhi = device.currentValue("aqhi") as BigDecimal
    if (currentAqhi != null && futureForecasts.size() >= 3) {
        BigDecimal sum = 0
        int count = 0
        for (Map fc : futureForecasts) {
            if (count >= 3) break
            sum += fc.aqhi as BigDecimal
            count++
        }
        BigDecimal avg = sum / count
        BigDecimal diff = avg - currentAqhi
        String trend
        if (diff > 0.5) {
            trend = "worsening"
        } else if (diff < -0.5) {
            trend = "improving"
        } else {
            trend = "stable"
        }
        sendEvent(name: "trend", value: trend)
    }

    // HTML tile
    buildForecastHtml(futureForecasts)

    // If no current observation, use first forecast as primary value
    if (device.currentValue("aqhi") == null && !futureForecasts.isEmpty()) {
        BigDecimal aqhi = futureForecasts[0].aqhi as BigDecimal
        int rounded = Math.round(aqhi) as int
        sendEvent(name: "aqhi", value: aqhi, unit: "AQHI")
        sendEvent(name: "airQualityIndex", value: aqhiToAQI(rounded))
        sendEvent(name: "aqhiRisk", value: riskCategory(rounded))
        if (locationName) {
            sendEvent(name: "stationName", value: locationName)
        }
        sendEvent(name: "lastUpdated", value: new Date().format("yyyy-MM-dd HH:mm:ss"))
    }
}

void buildForecastHtml(List<Map> forecasts) {
    // Show up to 24 hours, one bar per hour
    int maxHours = Math.min(forecasts.size(), 24) as int

    StringBuilder sb = new StringBuilder()
    sb.append('<style>')
    sb.append('.aqf{font-family:sans-serif;font-size:11px}')
    sb.append('.aqf td{padding:1px 3px;text-align:center}')
    sb.append('.bar{display:inline-block;width:14px;border-radius:2px}')
    sb.append('</style>')
    sb.append('<table class="aqf"><tr>')

    // Bar row
    for (int i = 0; i < maxHours; i++) {
        int val = Math.round(forecasts[i].aqhi as BigDecimal) as int
        int barHeight = Math.max(8, val * 6) as int
        String color = riskColor(val)
        sb.append("<td><div class=\"bar\" style=\"height:${barHeight}px;background:${color}\"></div></td>")
    }
    sb.append('</tr><tr>')

    // Value row
    for (int i = 0; i < maxHours; i++) {
        int val = Math.round(forecasts[i].aqhi as BigDecimal) as int
        sb.append("<td>${val}</td>")
    }
    sb.append('</tr><tr>')

    // Hour labels (every 3rd)
    for (int i = 0; i < maxHours; i++) {
        int h = forecasts[i].hours as int
        if (i % 3 == 0) {
            sb.append("<td style=\"color:#888\">${h}h</td>")
        } else {
            sb.append('<td></td>')
        }
    }
    sb.append('</tr></table>')

    sendEvent(name: "forecastHtml", value: sb.toString())
}

// ---------- Alerts ----------

void fetchAlerts(String zoneCode) {
    String url = "${ALERT_API_BASE}/en/AQHIAlert/${zoneCode}"
    logDebug "Fetching alerts: ${url}"

    Map params = [
        uri: url,
        contentType: "application/json",
        timeout: HTTP_TIMEOUT
    ]

    try {
        httpGet(params) { resp ->
            parseAlertResponse(resp)
        }
    } catch (Exception e) {
        logWarn "Error fetching alerts: ${e.message}"
    }
}

void parseAlertResponse(resp) {
    if (resp.status != 200) {
        logWarn "Alert API returned status ${resp.status}"
        return
    }

    Map data = resp.data as Map
    List alerts = data?.alerts as List ?: []
    int count = alerts.size()

    sendEvent(name: "alertCount", value: count)

    if (count > 0) {
        // Concatenate alert descriptions
        List<String> messages = []
        for (Object a : alerts) {
            Map alert = a as Map
            String desc = alert?.description as String ?: alert?.title as String ?: "AQHI Alert"
            messages << desc
        }
        String msg = messages.join("; ")
        sendEvent(name: "alertMessage", value: msg)
        if (txtEnable) {
            logInfo "AQHI alert(s): ${msg}"
        }
    } else {
        sendEvent(name: "alertMessage", value: "none")
    }
}

// ---------- Find nearest station ----------

void findNearestStation() {
    BigDecimal hubLat = location.latitude as BigDecimal
    BigDecimal hubLon = location.longitude as BigDecimal

    if (!hubLat || !hubLon) {
        logWarn "Hub location not configured — set latitude/longitude in hub settings first"
        return
    }

    logInfo "Finding nearest AQHI station to hub location (${hubLat}, ${hubLon})..."

    String url = "${API_BASE}/aqhi-stations/items?f=json&limit=200"
    Map params = [
        uri: url,
        contentType: "application/json",
        timeout: HTTP_TIMEOUT
    ]

    try {
        httpGet(params) { resp ->
            parseStationsResponse(resp, hubLat, hubLon)
        }
    } catch (Exception e) {
        logWarn "Error fetching stations: ${e.message}"
    }
}

void parseStationsResponse(resp, BigDecimal hubLat, BigDecimal hubLon) {
    if (resp.status != 200) {
        logWarn "Stations API returned status ${resp.status}"
        return
    }

    Map data = resp.data as Map
    List features = data?.features as List
    if (!features || features.isEmpty()) {
        logWarn "No AQHI stations found"
        return
    }

    String nearestId = ""
    String nearestName = ""
    double nearestDist = Double.MAX_VALUE

    for (Object f : features) {
        Map feature = f as Map
        Map props = feature?.properties as Map
        Map geometry = feature?.geometry as Map
        if (!props || !geometry) continue

        List coords = geometry.coordinates as List
        if (!coords || coords.size() < 2) continue

        double sLon = coords[0] as double
        double sLat = coords[1] as double
        double dist = haversine(hubLat as double, hubLon as double, sLat, sLon)

        if (dist < nearestDist) {
            nearestDist = dist
            nearestId = props.location_id as String
            nearestName = props.location_name_en as String
        }
    }

    if (nearestId) {
        state.autoStationId = nearestId
        state.autoStationName = nearestName
        state.autoStationDistance = Math.round(nearestDist) as int
        logInfo "Nearest station: ${nearestName} (${nearestId}), ${Math.round(nearestDist)} km away"
        sendEvent(name: "stationName", value: nearestName)

        schedulePoll()
        runIn(2, "refresh")
    } else {
        logWarn "Could not determine nearest station"
    }
}

@CompileStatic
static double haversine(double lat1, double lon1, double lat2, double lon2) {
    double R = 6371.0
    double dLat = Math.toRadians(lat2 - lat1)
    double dLon = Math.toRadians(lon2 - lon1)
    double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
               Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
               Math.sin(dLon / 2) * Math.sin(dLon / 2)
    double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    return R * c
}

// ---------- Helpers ----------

@CompileStatic
static String riskCategory(int value) {
    if (value <= 0) return "Unknown"
    if (value <= 3) return "Low Risk"
    if (value <= 6) return "Moderate Risk"
    if (value <= 10) return "High Risk"
    return "Very High Risk"
}

@CompileStatic
static String riskColor(int value) {
    if (value <= 0) return "#cccccc"
    int idx = Math.min(value, 11) - 1
    return RISK_COLORS[idx]
}

@CompileStatic
static int aqhiToAQI(int aqhi) {
    if (aqhi <= 0) return 0
    if (aqhi <= 3) return aqhi * 17
    if (aqhi <= 6) return 51 + (aqhi - 3) * 33
    if (aqhi <= 10) return 151 + (aqhi - 7) * 37
    return 301
}

long parseISO8601(String dt) {
    try {
        return Date.parse("yyyy-MM-dd'T'HH:mm:ss'Z'", dt).getTime()
    } catch (Exception e) {
        return 0
    }
}

// Logging

void logInfo(String msg) { log.info "${device.displayName}: ${msg}" }
void logWarn(String msg) { log.warn "${device.displayName}: ${msg}" }
void logDebug(String msg) { if (debugEnable) log.debug "${device.displayName}: ${msg}" }

void turnOffDebugLogging() {
    logWarn "Debug logging disabled"
    device.updateSetting("debugEnable", [value: "false", type: "bool"])
}

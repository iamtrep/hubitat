/*
 * Environment Canada AQHI (Air Quality Health Index) Driver for Hubitat
 *
 * Retrieves current AQHI observations and forecasts from the Environment Canada
 * GeoMet OGC API (api.weather.gc.ca) for a configurable station.
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

@Field static final String DRIVER_VERSION = "0.1.0"
@Field static final String API_BASE = "https://api.weather.gc.ca/collections"
@Field static final int HTTP_TIMEOUT = 15

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

        attribute "aqhi", "number"
        attribute "aqhiRisk", "string"
        attribute "observationTime", "string"
        attribute "forecastMax", "number"
        attribute "forecastMaxRisk", "string"
        attribute "forecastMaxTime", "string"
        attribute "stationName", "string"
        attribute "lastUpdated", "string"

        command "findNearestStation"
    }
}

preferences {
    section("Station") {
        input "stationId", "text", title: "Station ID", description: "Leave blank and use the Find Nearest Station button, or enter manually (e.g. EHHUN for Montréal)", required: false
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

    String sid = getStationId()
    if (!sid) {
        logInfo "No station configured — auto-detecting nearest station from hub location"
        findNearestStation()
        return  // findNearestStation will trigger refresh and schedulePoll on success
    }

    schedulePoll()
    runIn(1, "refresh")
}

private String getStationId() {
    return stationId ?: state.autoStationId
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
    String sid = getStationId()
    if (!sid) {
        logWarn "No station configured — use Find Nearest Station or set Station ID in preferences"
        return
    }
    logDebug "Refreshing AQHI data for station ${sid}"
    fetchObservation(sid)
    fetchForecast(sid)
}

// Fetch latest observation

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

// Fetch forecast — get the max value from upcoming hours

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

    // Find the future forecasts and the max AQHI among them
    String now = new Date().format("yyyy-MM-dd'T'HH:mm:ss'Z'", TimeZone.getTimeZone("UTC"))
    BigDecimal maxAqhi = 0
    String maxTime = ""
    String locationName = ""

    for (Object f : features) {
        Map props = (f as Map)?.properties as Map
        if (!props) continue

        String forecastDt = props.forecast_datetime as String
        if (!forecastDt || forecastDt < now) continue

        BigDecimal aqhi = props.aqhi as BigDecimal
        if (aqhi > maxAqhi) {
            maxAqhi = aqhi
            maxTime = props.forecast_datetime_text_en as String ?: ""
        }
        if (!locationName) {
            locationName = props.location_name_en as String
        }
    }

    if (maxAqhi > 0) {
        int rounded = Math.round(maxAqhi) as int
        String risk = riskCategory(rounded)

        sendEvent(name: "forecastMax", value: maxAqhi, unit: "AQHI")
        sendEvent(name: "forecastMaxRisk", value: risk)
        sendEvent(name: "forecastMaxTime", value: maxTime)

        logDebug "Forecast max AQHI: ${maxAqhi} (${risk}) at ${maxTime}"
    }

    // If no current observation, use latest forecast as primary value
    if (device.currentValue("aqhi") == null && features.size() > 0) {
        Map firstProps = (features[0] as Map)?.properties as Map
        if (firstProps) {
            BigDecimal aqhi = firstProps.aqhi as BigDecimal
            int rounded = Math.round(aqhi) as int
            sendEvent(name: "aqhi", value: aqhi, unit: "AQHI")
            sendEvent(name: "airQualityIndex", value: aqhiToAQI(rounded))
            sendEvent(name: "aqhiRisk", value: riskCategory(rounded))
        }
        if (locationName) {
            sendEvent(name: "stationName", value: locationName)
        }
        sendEvent(name: "lastUpdated", value: new Date().format("yyyy-MM-dd HH:mm:ss"))
    }
}

// Find nearest station from hub location

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

        // Start polling and refresh with the new station
        schedulePoll()
        runIn(2, "refresh")
    } else {
        logWarn "Could not determine nearest station"
    }
}

@CompileStatic
static double haversine(double lat1, double lon1, double lat2, double lon2) {
    double R = 6371.0 // km
    double dLat = Math.toRadians(lat2 - lat1)
    double dLon = Math.toRadians(lon2 - lon1)
    double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
               Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
               Math.sin(dLon / 2) * Math.sin(dLon / 2)
    double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    return R * c
}

// Helpers

@CompileStatic
static String riskCategory(int value) {
    if (value <= 0) return "Unknown"
    if (value <= 3) return "Low Risk"
    if (value <= 6) return "Moderate Risk"
    if (value <= 10) return "High Risk"
    return "Very High Risk"
}

// Map AQHI (1-11) to AQI-like scale (0-500) for the AirQuality capability
@CompileStatic
static int aqhiToAQI(int aqhi) {
    if (aqhi <= 0) return 0
    if (aqhi <= 3) return aqhi * 17         // 1-3 -> 17-51 (Good)
    if (aqhi <= 6) return 51 + (aqhi - 3) * 33   // 4-6 -> 84-150
    if (aqhi <= 10) return 151 + (aqhi - 7) * 37  // 7-10 -> 151-262
    return 301  // 10+
}

// Logging

void logInfo(String msg) { log.info "${device.displayName}: ${msg}" }
void logWarn(String msg) { log.warn "${device.displayName}: ${msg}" }
void logDebug(String msg) { if (debugEnable) log.debug "${device.displayName}: ${msg}" }

void turnOffDebugLogging() {
    logWarn "Debug logging disabled"
    device.updateSetting("debugEnable", [value: "false", type: "bool"])
}

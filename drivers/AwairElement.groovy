/*
 *  Hubitat Driver for the Awair Element
 *
 *  Using Awair Element's local API feature. See this article for details:
 *    https://support.getawair.com/hc/en-us/articles/360049221014-Awair-Element-Local-API-Feature
 *
 *  License: CC-0 public domain
 *
 *  Originally refactored from this code: https://raw.githubusercontent.com/srvrguy/Hubitat-AwAir/master/AwAir_Driver.groovy
 *
 */

import groovy.transform.Field

metadata {
    definition(name: "Awair Element",
               namespace: "iamtrep",
               author: "pj",
               importUrl: "https://raw.githubusercontent.com/iamtrep/hubitat/refs/heads/main/drivers/AwairElement.groovy"
    ) {
        capability "AirQuality"
        capability "CarbonDioxideMeasurement"
        capability "Configuration"
        capability "Initialize"
        capability "Refresh"
        capability "RelativeHumidityMeasurement"
        capability "Sensor"
        capability "TemperatureMeasurement"

        attribute "pm10", "number"
        attribute "pm25", "number"
        attribute "voc", "number"
        attribute "airQuality", "number"

        attribute "aiq_desc", "ENUM", ["unknown", "poor", "fair", "good"]
        attribute "pm10_desc", "ENUM", ["unknown", "hazardous", "bad", "poor", "fair", "good"]
        attribute "pm25_desc", "ENUM", ["unknown", "hazardous", "bad", "poor", "fair", "good"]
        attribute "co2_desc", "ENUM", ["unknown", "hazardous", "bad", "poor", "fair", "good"]
        attribute "voc_desc", "ENUM", ["unknown", "hazardous", "bad", "poor", "fair", "good"]
    }

    preferences {
        input name: "ip", type: "text", title: "IP Address", description: "IP of Awair Device", required: true, defaultValue: "192.168.1.3"
        input name: "pollingInterval", type: "number", title: "Time (seconds) between status checks", defaultValue: 300

        input name: "txtEnable", type: "bool", title: "Enable descriptionText logging", defaultValue: false
        input name: "debugEnable", type: "bool", title: "Enable debug logging info", defaultValue: false, required: true, submitOnChange: true
        if (debugEnable) {
            input name: "traceEnable", type: "bool", title: "Enable trace logging info (for development purposes)", defaultValue: false
        }
    }
}

@Field static final String constLocalPathToAirData = "/air-data/latest"
@Field static final String constLocalPathToConfig = "/settings/config/data"

// Runs when the driver is installed
void installed() {
    logDebug "installed..."
    resetAttributes()
    runIn(2, "poll")
}

// Runs when the driver preferences are saved/updated
void updated() {
    if (pollingInterval != null && (pollingInterval as int) < 10) {
        logWarn "pollingInterval too low, using 10 seconds"
        device.updateSetting("pollingInterval", [type: "number", value: 10])
    }
    configure()
    runIn(2, "poll")
}

void deviceTypeUpdated() {
    logWarn "driver change detected"
    configure()
    runIn(2, "poll")
}

// Runs when the hub starts up
void initialize() {
    runIn(2, "poll")
}

void refresh() {
    poll()
}

void uninstalled() {
    unschedule()
}

void resetAttributes() {
    //Clear and initialize any state variables
    state.clear()
    state.pm25readings = []

    //Set some initial values
    processEvent("voc", -1, "ppb", "voc is ${-1} ppb")
    processEvent("pm10", -1, "ug/m3", "pm10 is ${-1} ug/m3")
    processEvent("pm25", -1, "ug/m3", "pm25 is ${-1} ug/m3")
    processEvent("airQuality", -1, "", "airQuality is ${-1}")
    processEvent("temperature", -1, "°${location.temperatureScale}", "Temperature is ${-1}°${location.temperatureScale}")
    processEvent("carbonDioxide", -1, "ppm", "carbonDioxide is ${-1} ppm")
    processEvent("humidity", -1, "%", "humidity is ${-1}")
    processEvent("airQualityIndex", -1, "", "Current calculated AQI is -1")

    processEvent("aiq_desc", "unknown")
    processEvent("voc_desc", "unknown")
    processEvent("co2_desc", "unknown")
    processEvent("pm10_desc", "unknown")
    processEvent("pm25_desc", "unknown")
}

void configure() {
    unschedule("poll")
    try {
        def httpParams = [
                uri        : "http://${ip}",
                path       : constLocalPathToConfig,
                contentType: "application/json"
        ]

        asynchttpGet('parseConfig', httpParams)
    } catch (Exception e) {
        logError "configure(): ${e}"
    }

    runIn((pollingInterval ?: 300) as int, "poll")
}

void poll() {
    try {
        def httpParams = [
                uri        : "http://${ip}",
                path       : constLocalPathToAirData,
                contentType: "application/json"
        ]

        asynchttpGet('processAwairData', httpParams)
    } catch (Exception e) {
        logError "error occurred in poll(): ${e}"
    }

    runIn((pollingInterval ?: 300) as int, "poll")
}


// private methods

private void parseConfig(response, data) {
    if (response.hasError()) {
        logError "HTTP error in parseConfig(): ${response.getErrorMessage()}"
        return
    }
    if (response.getStatus() == 200 || response.getStatus() == 207) {
        def awairConfig = parseJson(response.data)
        logTrace "parseConfig(): ${awairConfig}"

        //Awair UUID
        updateDataValue("Device UUID", awairConfig.device_uuid)

        //Awair MAC
        updateDataValue("MAC Address", awairConfig.wifi_mac)

        //Awair Firmware Version
        updateDataValue("Firmware", awairConfig.fw_version)

    } else {
        logError "HTTP response error in parseConfig() - STATUS ${response.getStatus()}"
    }
}


private void processEvent(name, value, unit = null, description = null) {
    def evt = [
        name : name,
        value: value
    ]

    if (unit != null) {
        evt.unit = unit
    }

    if (description != null) {
        evt.descriptionText = description
        logInfo evt.descriptionText
    }

    sendEvent(evt)
    logDebug "event: " + evt
}

// Air Quality Thresholds
@Field static final Map<Integer, String> VOC_THRESHOLDS = [8332: "hazardous", 3333: "bad", 1000: "poor", 333: "fair"]
@Field static final Map<Integer, String> PM10_THRESHOLDS = [75: "hazardous", 55: "bad", 35: "poor", 15: "fair"]
@Field static final Map<Integer, String> PM25_THRESHOLDS = [75: "hazardous", 55: "bad", 35: "poor", 15: "fair"]
@Field static final Map<Integer, String> CO2_THRESHOLDS = [2500: "hazardous", 1500: "bad", 1000: "poor", 600: "fair"]
@Field static final Map<Integer, String> AIQ_THRESHOLDS = [80: "good", 60: "fair"]

private void processAwairData(response, data) {
    if (response.hasError()) {
        logError "HTTP error in processAwairData(): ${response.getErrorMessage()}"
        return
    }
    if (response.getStatus() == 200 || response.getStatus() == 207) {
        def awairData = parseJson(response.data)
        logTrace "processAwairData(): ${awairData}"

        // VOC
        processAirQualityMetric("voc", awairData.voc, "ppb", VOC_THRESHOLDS, "good", "voc_desc")

        // PM 10
        processAirQualityMetric("pm10", awairData.pm10_est, "ug/m3", PM10_THRESHOLDS, "good", "pm10_desc")

        // PM 2.5
        processAirQualityMetric("pm25", awairData.pm25, "ug/m3", PM25_THRESHOLDS, "good", "pm25_desc")

        // EPA AQI calculation
        def readings = (state.pm25readings ?: []) as List
        readings << awairData.pm25
        state.pm25readings = readings
        def currAqi = calculateAqi()
        processEvent("airQualityIndex", currAqi, "", "Current calculated AQI is ${currAqi}")

        // AIQ Score - https://support.getawair.com/hc/en-us/articles/19504367520023-Understanding-Awair-Score-and-Air-Quality-Factors-Measured-By-Awair-Element
        processAirQualityMetric("airQuality", awairData.score, "", AIQ_THRESHOLDS, "poor", "aiq_desc")

        // Temperature
        def temperature = convertTemperatureIfNeeded(awairData.temp, "c", 1)
        processEvent("temperature", temperature, "°${location.temperatureScale}", "Temperature is ${temperature}°${location.temperatureScale}")

        // CO2
        processAirQualityMetric("carbonDioxide", awairData.co2, "ppm", CO2_THRESHOLDS, "good", "co2_desc")

        // Humidity
        processEvent("humidity", Math.round(awairData.humid as double) as int, "%", "humidity is ${awairData.humid}")

    } else {
        logError "HTTP response error in processAwairData() - STATUS ${response.getStatus()}"
    }
}

private void processAirQualityMetric(String metricName, def level, String unit,
                                     Map<Integer, String> thresholds, String defaultDesc, String descAttribute) {
    processEvent(metricName, level, unit, "${metricName} is ${level} ${unit}")

    String newDesc = thresholds.find { threshold, desc ->
        level > threshold
    }?.value ?: defaultDesc

    processEvent(descAttribute, newDesc)
}

// AQI calculatons

// AQI PM2.5 Breakpoints
// From data at https://aqs.epa.gov/aqsweb/documents/codetables/aqi_breakpoints.html
// Using "PM2.5 - Local Conditions" data
// Updated 2021 July 19
@Field static final List<Map<String, Object>> AQI_BREAKPOINTS = [
    [bpLow: 0.0d, bpHigh: 12.0d, aqiLow: 0, aqiHigh: 50, category: "Good"],
    [bpLow: 12.1d, bpHigh: 35.4d, aqiLow: 51, aqiHigh: 100, category: "Moderate"],
    [bpLow: 35.5d, bpHigh: 55.4d, aqiLow: 101, aqiHigh: 150, category: "Unhealthy for Sensitive Groups"],
    [bpLow: 55.5d, bpHigh: 150.4d, aqiLow: 151, aqiHigh: 200, category: "Unhealthy"],
    [bpLow: 150.5d, bpHigh: 250.4d, aqiLow: 201, aqiHigh: 300, category: "Very Unhealthy"],
    [bpLow: 250.5d, bpHigh: 350.4d, aqiLow: 301, aqiHigh: 400, category: "Hazardous"],
    [bpLow: 350.5d, bpHigh: 500.4d, aqiLow: 401, aqiHigh: 500, category: "Hazardous"],
    [bpLow: 500.5d, bpHigh: 99999.9d, aqiLow: 501, aqiHigh: 999, category: "Hazardous"]
]

@Field static final int MAX_PM25_READINGS = 5

// Calculate the AQI based on the stored PM2.5 Values
private int calculateAqi() {
    def readings = (state.pm25readings ?: []) as List

    // Maintain rolling window of PM2.5 readings
    while (readings.size() > MAX_PM25_READINGS) {
        readings.removeAt(0)
    }
    state.pm25readings = readings

    if (readings.isEmpty()) return 0

    // Calculate average PM2.5
    double totalPM25 = 0.0d
    for (Double reading : readings) {
        totalPM25 += reading
    }
    double avgPM25 = totalPM25 / readings.size()
    state.avgPM25 = avgPM25

    // Find appropriate AQI breakpoint tier
    Map<String, Object> aqiTier = findAqiTier(avgPM25)
    state.aqiBreakpoint = aqiTier

    // Apply AQI formula: ((AQI_high - AQI_low) / (BP_high - BP_low)) * (PM2.5 - BP_low) + AQI_low
    double rawAqi = calculateRawAqi(aqiTier, avgPM25)
    state.rawAqi = rawAqi

    return Math.round(rawAqi) as int
}

private Map<String, Object> findAqiTier(double avgPM25) {
    for (int i = AQI_BREAKPOINTS.size() - 1; i >= 0; i--) {
        if (avgPM25 >= (AQI_BREAKPOINTS[i].bpLow as double)) {
            return AQI_BREAKPOINTS[i]
        }
    }
    return AQI_BREAKPOINTS.first()
}

private double calculateRawAqi(Map<String, Object> aqiTier, double avgPM25) {
    int aqiHigh = aqiTier.aqiHigh as int
    int aqiLow = aqiTier.aqiLow as int
    double bpHigh = aqiTier.bpHigh as double
    double bpLow = aqiTier.bpLow as double

    return ((aqiHigh - aqiLow) / (bpHigh - bpLow)) * (avgPM25 - bpLow) + aqiLow
}


// Logging helpers

private logTrace(message) {
    if (traceEnable) log.trace("${device} : ${message}")
}

private logDebug(message) {
    if (debugEnable) log.debug("${device} : ${message}")
}

private logInfo(message) {
    if (txtEnable) log.info("${device} : ${message}")
}

private logWarn(message) {
    log.warn("${device} : ${message}")
}

private logError(message) {
    log.error("${device} : ${message}")
}

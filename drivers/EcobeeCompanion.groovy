/**
 *  Ecobee Companion Driver
 *
 *  Advanced Ecobee thermostat control via API - companion to Hubitat's built-in integration
 *
 *  Provides features not available in the built-in integration:
 *  - Comfort setting temperature management
 *  - Vacation creation/deletion
 *  - Schedule time manipulation
 *  - Current state and sensor data
 *  - Weather forecasts
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */

import java.math.RoundingMode
import groovy.transform.CompileStatic
import groovy.transform.Field
import groovy.json.JsonOutput

metadata {
    definition(
        name: "Ecobee Companion",
        namespace: "iamtrep",
        author: "pj",
        importUrl: "https://raw.githubusercontent.com/iamtrep/hubitat/refs/heads/main/drivers/EcobeeCompanion.groovy"
    ) {
        capability "Actuator"
        capability "Configuration"
        capability "Refresh"
        capability "TemperatureMeasurement"
        capability "RelativeHumidityMeasurement"
        capability "Thermostat"

        command "connect", [[name: "Initiate OAuth connection"]]
        command "authorize", [[name: "Complete OAuth after authorizing PIN on ecobee.com"]]
        command "listThermostats", [[name: "List all thermostats on account"]]
        command "listComfortSettings", [[name: "List all comfort settings"]]
        command "setComfortTemperature", [
            [name: "comfortName", type: "STRING", description: "Comfort setting name (e.g., Home, Away, Sleep)"],
            [name: "heatTemp", type: "NUMBER", description: "Heat setpoint in °C"],
            [name: "coolTemp", type: "NUMBER", description: "Cool setpoint in °C (optional)"]
        ]
        command "listVacations", [[name: "List all scheduled vacations"]]
        command "createVacation", [
            [name: "name", type: "STRING", description: "Vacation name"],
            [name: "startDateTime", type: "STRING", description: "Start date/time (yyyy-MM-dd HH:mm)"],
            [name: "endDateTime", type: "STRING", description: "End date/time (yyyy-MM-dd HH:mm)"],
            [name: "heatTemp", type: "NUMBER", description: "Heat setpoint in °C"],
            [name: "coolTemp", type: "NUMBER", description: "Cool setpoint in °C"],
            [name: "fanMode", type: "STRING", description: "Fan mode: auto or on (optional, default: auto)"]
        ]
        command "deleteVacation", [
            [name: "name", type: "STRING", description: "Vacation name to delete"]
        ]
        command "listThermostatSchedule", [
            [name: "day", type: "ENUM", constraints: ["monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday"], description: "Day of week"]
        ]
        command "setThermostatScheduleTime", [
            [name: "day", type: "ENUM", constraints: ["monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday"], description: "Day of week"],
            [name: "comfortName", type: "STRING", description: "Comfort setting name (e.g., Home, Away, Sleep)"],
            [name: "currentTime", type: "ENUM", constraints: ["00:00", "00:30", "01:00", "01:30", "02:00", "02:30", "03:00", "03:30", "04:00", "04:30", "05:00", "05:30", "06:00", "06:30", "07:00", "07:30", "08:00", "08:30", "09:00", "09:30", "10:00", "10:30", "11:00", "11:30", "12:00", "12:30", "13:00", "13:30", "14:00", "14:30", "15:00", "15:30", "16:00", "16:30", "17:00", "17:30", "18:00", "18:30", "19:00", "19:30", "20:00", "20:30", "21:00", "21:30", "22:00", "22:30", "23:00", "23:30"], description: "Current time"],
            [name: "newTime", type: "ENUM", constraints: ["00:00", "00:30", "01:00", "01:30", "02:00", "02:30", "03:00", "03:30", "04:00", "04:30", "05:00", "05:30", "06:00", "06:30", "07:00", "07:30", "08:00", "08:30", "09:00", "09:30", "10:00", "10:30", "11:00", "11:30", "12:00", "12:30", "13:00", "13:30", "14:00", "14:30", "15:00", "15:30", "16:00", "16:30", "17:00", "17:30", "18:00", "18:30", "19:00", "19:30", "20:00", "20:30", "21:00", "21:30", "22:00", "22:30", "23:00", "23:30"], description: "New time"]
        ]
        command "getCurrentState", [[name: "Get current temperature, humidity, and HVAC state"]]
        command "getWeatherForecast", [[name: "Get weather forecast"]]
        command "listSensors", [[name: "List all sensors with current readings"]]
        command "refreshToken", [[name: "Manually refresh OAuth token"]]

        // Custom attributes (not provided by standard capabilities)
        attribute "connectionStatus", "string"
        attribute "currentProgram", "string"
        attribute "holdStatus", "string"
        attribute "holdClimate", "string"
        attribute "holdEndTime", "string"
        attribute "outdoorTemperature", "number"
        attribute "weatherCondition", "string"
        attribute "hvacMode", "string"
    }

    preferences {
        input name: "apiKey", type: "text", title: "Ecobee API Key", required: true
        input name: "thermostatId", type: "text", title: "Thermostat Identifier", required: true
        input name: "pollInterval", type: "number", title: "Polling interval (minutes)", description: "How often to refresh thermostat state (1-30 minutes)", range: "1..30", defaultValue: 5, required: true
        input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: true
    }
}

@Field static final String version = "0.0.2"

// OAuth and API endpoints
@Field static final String constEcobeeApiBase= "https://api.ecobee.com"

// Static mappings
@Field static final Map<String, Integer> DAY_NAME_TO_INDEX = [
    monday: 0,
    tuesday: 1,
    wednesday: 2,
    thursday: 3,
    friday: 4,
    saturday: 5,
    sunday: 6
].asImmutable()

@Field static final int BLOCKS_PER_DAY = 48
@Field static final int MINUTES_PER_BLOCK = 30

// Token and timeout configuration
@Field static final long TOKEN_REFRESH_BUFFER_MS = 300000L  // Refresh 5 minutes before expiry
@Field static final int DEBUG_LOG_TIMEOUT_SECONDS = 3600    // Auto-disable debug logging after 1 hour


@Field static final Map constValidFanModes = [
    auto: "auto",
    on: "on",
    circulate: "circulate"
]

@Field static final Map constValidThermostatModes = [
    heat: "heat",
    cool: "cool",
    auto: "auto",
    off: "off",
    auxHeatOnly: "auxHeatOnly"
]

@Field static final List<String> constScheduleDays = [
    "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday"
]

// ========================================
// Lifecycle Methods
// ========================================

void installed() {
    if (state.version != version) state.version = version
    sendEvent(name: "connectionStatus", value: "Not Connected")
    configure()
}

void updated() {
    if (state.version != version) state.version = version
    unschedule()
    if (logEnable) runIn(DEBUG_LOG_TIMEOUT_SECONDS, logsOff)
    schedulePolling()
}

void configure() {
    sendEvent(name: "supportedThermostatFanModes", value: JsonOutput.toJson(constValidFanModes.values()))
    sendEvent(name: "supportedThermostatModes", value: JsonOutput.toJson(constValidThermostatModes.values()))
}

void refresh() {
    if (state.version != version) state.version = version
    getCurrentState()
}

// ========================================
// OAuth Flow
// ========================================

void connect() {
    if (!apiKey) {
        log.error "API Key not configured"
        sendEvent(name: "connectionStatus", value: "Error: No API Key")
        return
    }

    Map params = [
        uri: "${constEcobeeApiBase}/authorize",
        query: [
            response_type: "ecobeePin",
            client_id: apiKey,
            scope: "smartWrite"
        ],
        contentType: "application/json"
    ]

    try {
        httpGet(params) { response ->
            if (response.status == 200) {
                Map data = response.data
                state.ecobeeAuthToken = data.code
                state.interval = data.interval
                state.pinExpires = now() + (data.expires_in * 1000)

                log.info "PIN: ${data.ecobeePin} (expires in ${data.expires_in / 60} min)"
                log.info "Authorize at ecobee.com, then run authorize() to complete setup"

                sendEvent(name: "connectionStatus", value: "Waiting for PIN authorization: ${data.ecobeePin}")
            }
        }
    } catch (e) {
        log.error "Error initiating OAuth: ${e.message}"
        sendEvent(name: "connectionStatus", value: "Error: ${e.message}")
    }
}

void authorize() {
    if (!state.ecobeeAuthToken) {
        log.error "No authorization token. Call connect() first"
        return
    }

    if (now() > state.pinExpires) {
        log.error "PIN expired. Call connect() again"
        sendEvent(name: "connectionStatus", value: "Error: PIN expired")
        return
    }

    Map params = [
        uri: "${constEcobeeApiBase}/token",
        query: [
            grant_type: "ecobeePin",
            code: state.ecobeeAuthToken,
            client_id: apiKey
        ],
        contentType: "application/json"
    ]

    try {
        httpPost(params) { response ->
            if (response.status == 200) {
                Map data = response.data
                state.accessToken = data.access_token
                state.refreshToken = data.refresh_token
                state.tokenExpiry = now() + (data.expires_in * 1000)

                state.remove('ecobeeAuthToken')
                state.remove('interval')
                state.remove('pinExpires')

                log.info "Authorization successful"
                sendEvent(name: "connectionStatus", value: "Connected")
            }
        }
    } catch (e) {
        log.error "Authorization error: ${e.message}"
        sendEvent(name: "connectionStatus", value: "Error: ${e.message}")
    }
}

Boolean refreshToken() {
    if (!state.refreshToken) {
        log.error "No refresh token available"
        sendEvent(name: "connectionStatus", value: "Error: Not authorized")
        return false
    }

    Map params = [
        uri: "${constEcobeeApiBase}/token",
        query: [
            grant_type: "refresh_token",
            refresh_token: state.refreshToken,
            client_id: apiKey
        ],
        contentType: "application/json"
    ]

    try {
        httpPost(params) { response ->
            if (response.status == 200) {
                Map data = response.data
                state.accessToken = data.access_token
                state.refreshToken = data.refresh_token
                state.tokenExpiry = now() + (data.expires_in * 1000)

                logDebug "Token refreshed"
                sendEvent(name: "connectionStatus", value: "Connected")
                return true
            }
        }
    } catch (e) {
        log.error "Token refresh error: ${e.message}"
        sendEvent(name: "connectionStatus", value: "Error: Token refresh failed")
    }
    return false
}

Boolean checkAndRefreshToken() {
    if (!state.tokenExpiry || now() >= ((state.tokenExpiry as Long) - TOKEN_REFRESH_BUFFER_MS)) {
        return refreshToken()
    }
    return true
}

// ========================================
// Discovery Methods
// ========================================

List<Map> listThermostats() {
    Map queryData = [
        json: groovy.json.JsonOutput.toJson([
            selection: [
                selectionType: "registered",
                selectionMatch: "",
                includeProgram: false
            ]
        ])
    ]

    Map data = callEcobeeApi("GET", "/1/thermostat", queryData)
    if (!data?.thermostatList) {
        log.error "Could not retrieve thermostat list"
        return []
    }

    List<Map> thermostats = data.thermostatList
    log.info "Found ${thermostats.size()} thermostat(s)"
    thermostats.each { Map t ->
        log.info "  ${t.name} (ID: ${t.identifier}, Model: ${t.modelNumber})"
    }

    return thermostats
}

List<Map> listComfortSettings() {
    Map thermostat = getThermostat()
    if (!thermostat) return []

    List<Map> climates = thermostat.program.climates
    log.info "Found ${climates.size()} comfort setting(s)"
    climates.each { Map c ->
        BigDecimal heatC = ecobeeToCelsius(c.heatTemp)
        BigDecimal coolC = ecobeeToCelsius(c.coolTemp)
        log.info "  ${c.name}: Heat ${heatC}°C, Cool ${coolC}°C (${c.climateRef})"
    }

    return climates
}

// ========================================
// Ecobee API Methods
// ========================================

private Map callEcobeeApi(String method, String path, Map queryParams = null, Map bodyData = null) {
    if (!checkAndRefreshToken()) {
        log.error "Cannot call API: token refresh failed"
        return null
    }

    Map params = [
        uri: "${constEcobeeApiBase}${path}",
        headers: [
            Authorization: "Bearer ${state.accessToken}",
            "Content-Type": "application/json"
        ]
    ]

    if (queryParams) params.query = queryParams
    if (bodyData) params.body = groovy.json.JsonOutput.toJson(bodyData)

    try {
        Map result = null
        if (method == "GET") {
            httpGet(params) { response ->
                if (response.status == 200) result = response.data
            }
        } else if (method == "POST") {
            httpPost(params) { response ->
                if (response.status == 200) result = response.data
            }
        }
        return result
    } catch (e) {
        if (e.response?.status == 401) {
            log.warn "Token invalid, refreshing..."
            if (refreshToken()) {
                return callEcobeeApi(method, path, queryParams, bodyData) // Retry
            }
        }
        log.error "Error calling Ecobee API: ${e.message}"
        return null
    }
}

Map getThermostat() {
    Map thermostat = fetchThermostatData()
    if (!thermostat) {
        log.error "Could not retrieve thermostat data"
    }
    return thermostat
}

Boolean updateThermostat(Map thermostat) {
    Map body = [
        selection: [
            selectionType: "thermostats",
            selectionMatch: thermostatId
        ],
        thermostat: thermostat
    ]

    return callEcobeeApi("POST", "/1/thermostat", null, body) != null
}

// ========================================
// Comfort Setting Management
// ========================================

void setComfortTemperature(String comfortName, heatTemp, coolTemp = null) {
    if (heatTemp == null) {
        log.error "Heat temperature is required"
        return
    }

    if (coolTemp != null) {
        BigDecimal heatC = (heatTemp instanceof String) ? heatTemp.toBigDecimal() : (heatTemp as BigDecimal)
        BigDecimal coolC = (coolTemp instanceof String) ? coolTemp.toBigDecimal() : (coolTemp as BigDecimal)
        if (heatC >= coolC) {
            log.error "Heat temp must be less than cool temp"
            return
        }
    }

    Map thermostat = getThermostat()
    if (!thermostat) return

    Map climate = thermostat.program.climates.find { it.name == comfortName }
    if (!climate) {
        log.error "Comfort setting '${comfortName}' not found"
        return
    }

    climate.heatTemp = celsiusToEcobee(heatTemp)
    if (coolTemp != null) {
        climate.coolTemp = celsiusToEcobee(coolTemp)
    }

    if (updateThermostat([program: thermostat.program])) {
        log.info "Updated ${comfortName}: Heat ${heatTemp}°C" + (coolTemp ? ", Cool ${coolTemp}°C" : "")
        state.lastUpdate = new Date().format("yyyy-MM-dd HH:mm:ss")
    } else {
        log.error "Failed to update ${comfortName}"
    }
}

// ========================================
// Vacation Management
// ========================================

List<Map> listVacations() {
    Map thermostat = getThermostat()
    if (!thermostat) return []

    List<Map> vacations = (thermostat.events ?: []).findAll { it.type == "vacation" }

    if (vacations.size() == 0) {
        log.info "No vacations scheduled"
        return []
    }

    log.info "Found ${vacations.size()} vacation(s)"
    vacations.each { Map v ->
        BigDecimal heatC = ecobeeToCelsius(v.heatHoldTemp)
        BigDecimal coolC = ecobeeToCelsius(v.coolHoldTemp)
        log.info "  ${v.name}: ${v.startDate} ${v.startTime} to ${v.endDate} ${v.endTime}, Heat ${heatC}°C, Cool ${coolC}°C, Fan ${v.fan}"
    }

    return vacations
}

void createVacation(String name, String startDateTime, String endDateTime, heatTemp, coolTemp, String fanMode = "auto") {
    Map<String, String> startParts = parseDateTime(startDateTime)
    Map<String, String> endParts = parseDateTime(endDateTime)

    if (!startParts || !endParts) {
        log.error "Invalid date/time format. Use: yyyy-MM-dd HH:mm"
        return
    }

    try {
        Date startDate = Date.parse("yyyy-MM-dd HH:mm:ss", "${startParts.date} ${startParts.time}")
        Date endDate = Date.parse("yyyy-MM-dd HH:mm:ss", "${endParts.date} ${endParts.time}")
        if (endDate <= startDate) {
            log.error "End date/time must be after start date/time"
            return
        }
    } catch (Exception e) {
        log.error "Error validating dates: ${e.message}"
        return
    }

    if (heatTemp == null || coolTemp == null) {
        log.error "Both heat and cool temperatures are required"
        return
    }

    BigDecimal heatC = (heatTemp instanceof String) ? heatTemp.toBigDecimal() : (heatTemp as BigDecimal)
    BigDecimal coolC = (coolTemp instanceof String) ? coolTemp.toBigDecimal() : (coolTemp as BigDecimal)
    if (heatC >= coolC) {
        log.error "Heat temp must be less than cool temp"
        return
    }

    Map thermostat = getThermostat()
    if (!thermostat) return

    if (thermostat.events?.find { it.type == "vacation" && it.name == name }) {
        log.error "Vacation '${name}' already exists"
        return
    }

    Map vacation = [
        name: name,
        type: "vacation",
        running: false,
        startDate: startParts.date,
        startTime: startParts.time,
        endDate: endParts.date,
        endTime: endParts.time,
        isOccupied: false,
        isCoolOff: false,
        isHeatOff: false,
        coolHoldTemp: celsiusToEcobee(coolTemp),
        heatHoldTemp: celsiusToEcobee(heatTemp),
        fan: fanMode
    ]

    if (sendFunction([type: "createVacation", params: vacation])) {
        log.info "Created vacation '${name}'"
        state.lastUpdate = new Date().format("yyyy-MM-dd HH:mm:ss")
    } else {
        log.error "Failed to create vacation '${name}'"
    }
}

void deleteVacation(String name) {
    Map thermostat = getThermostat()
    if (!thermostat) return

    if (!thermostat.events?.find { it.type == "vacation" && it.name == name }) {
        log.error "Vacation '${name}' not found"
        return
    }

    if (sendFunction([type: "deleteVacation", params: [name: name]])) {
        log.info "Deleted vacation '${name}'"
        state.lastUpdate = new Date().format("yyyy-MM-dd HH:mm:ss")
    } else {
        log.error "Failed to delete vacation '${name}'"
    }
}

// ========================================
// Schedule Management
// ========================================

List<Map> listThermostatSchedule(String day) {
    if (!validateDayParameter(day)) return []

    Map thermostat = getThermostat()
    if (!thermostat) return []

    Integer dayIndex = dayNameToIndex(day)
    List scheduleBlocks = thermostat.program.schedule[dayIndex]
    if (!scheduleBlocks) {
        log.error "No schedule found for ${day}"
        return []
    }

    Map<String, String> climates = thermostat.program.climates.collectEntries { [(it.climateRef): it.name] }

    log.info "Schedule for ${day}"
    List<Map> transitions = []
    String lastClimate = null
    for (int i = 0; i < scheduleBlocks.size(); i++) {
        String climate = scheduleBlocks[i]
        if (climate != lastClimate) {
            Integer hours = Math.floor(i / 2) as Integer
            Integer minutes = (i % 2) * 30
            String timeStr = String.format("%02d:%02d", hours, minutes)
            String climateName = climates[climate] ?: climate
            log.info "  ${timeStr}: ${climateName}"

            transitions << [
                time: timeStr,
                climateRef: climate,
                climateName: climateName
            ]
            lastClimate = climate
        }
    }

    return transitions
}

void setThermostatScheduleTime(String day, String comfortName, String currentTime, String newTime) {
    if (!validateDayParameter(day)) return

    Map thermostat = getThermostat()
    if (!thermostat) return

    Map program = thermostat.program
    Map climate = program.climates.find { it.name == comfortName }
    if (!climate) {
        log.error "Comfort setting '${comfortName}' not found"
        return
    }
    String climateRef = climate.climateRef

    Integer currentBlock = timeToBlockIndex(currentTime)
    Integer newBlock = timeToBlockIndex(newTime)

    if (currentBlock == null || newBlock == null) {
        log.error "Invalid time format. Use HH:mm on 30-minute boundaries (XX:00 or XX:30)"
        return
    }

    if (currentBlock == newBlock) {
        log.info "Time is already ${currentTime}, no change needed"
        return
    }

    Integer dayIndex = dayNameToIndex(day)
    List scheduleBlocks = program.schedule[dayIndex]

    if (scheduleBlocks[currentBlock] != climateRef) {
        log.error "No ${comfortName} schedule entry at ${currentTime} on ${day}"
        return
    }

    if (currentBlock > 0 && scheduleBlocks[currentBlock - 1] == climateRef) {
        log.error "${comfortName} doesn't start at ${currentTime}"
        return
    }

    String previousClimate = (currentBlock > 0) ? scheduleBlocks[currentBlock - 1] : null
    List updatedBlocks = adjustScheduleBlocks(scheduleBlocks, currentBlock, newBlock, climateRef, previousClimate)

    if (updatedBlocks == null) return

    Map updateData = [program: program]
    if (updateThermostat(updateData)) {
        log.info "Moved ${comfortName} on ${day} from ${currentTime} to ${newTime}"
        state.lastUpdate = new Date().format("yyyy-MM-dd HH:mm:ss")
    } else {
        log.error "Failed to update schedule"
    }
}

// ========================================
// Current State & Sensors
// ========================================

void getCurrentState() {
    Map selection = [
        selectionType: "thermostats",
        selectionMatch: thermostatId,
        includeRuntime: true,
        includeWeather: true,
        includeEquipmentStatus: true,
        includeSettings: true,
        includeProgram: true,
        includeEvents: true
    ]

    Map thermostat = fetchThermostatData("/1/thermostat", selection)
    if (!thermostat) return

    Map runtime = thermostat.runtime
    Map weather = thermostat.weather

    // Update temperature and humidity
    BigDecimal tempC = ecobeeToCelsius(runtime.actualTemperature)
    sendEvent(name: "temperature", value: tempC, unit: "°C")
    sendEvent(name: "humidity", value: runtime.actualHumidity, unit: "%")

    // Update setpoints
    BigDecimal heatC = ecobeeToCelsius(runtime.desiredHeat)
    BigDecimal coolC = ecobeeToCelsius(runtime.desiredCool)
    sendEvent(name: "heatingSetpoint", value: heatC, unit: "°C")
    sendEvent(name: "coolingSetpoint", value: coolC, unit: "°C")

    // Update HVAC mode
    String hvacModeValue = thermostat.settings?.hvacMode?.toString() ?: "unknown"
    sendEvent(name: "hvacMode", value: hvacModeValue)

    // Update current program
    sendEvent(name: "currentProgram", value: thermostat.program?.currentClimateRef?.toString() ?: "unknown")

    // Determine operating state
    String operatingState = "idle"
    if (thermostat.equipmentStatus) {
        String status = thermostat.equipmentStatus.toLowerCase()
        if (status.contains("heat")) {
            operatingState = status.contains("aux") ? "pending heat" : "heating"
        } else if (status.contains("cool")) {
            operatingState = "cooling"
        } else if (status.contains("fan")) {
            operatingState = "fan only"
        }
    }
    sendEvent(name: "thermostatOperatingState", value: operatingState)

    // Update weather
    if (weather?.forecasts?.size() > 0) {
        Map current = weather.forecasts[0]
        BigDecimal outdoorTempC = ecobeeToCelsius(current.temperature)
        sendEvent(name: "outdoorTemperature", value: outdoorTempC, unit: "°C")
        sendEvent(name: "weatherCondition", value: current.condition?.toString() ?: "")
    }

    // Update hold status
    Map activeHold = thermostat.events?.find { it.type == "hold" && it.running == true }
    if (activeHold) {
        sendEvent(name: "holdStatus", value: "active")
        sendEvent(name: "holdClimate", value: activeHold.holdClimateRef?.toString() ?: "temperature")
        sendEvent(name: "holdEndTime", value: (activeHold.endDate && activeHold.endTime) ?
            "${activeHold.endDate} ${activeHold.endTime}".toString() : "indefinite")
    } else {
        sendEvent(name: "holdStatus", value: "none")
        sendEvent(name: "holdClimate", value: "")
        sendEvent(name: "holdEndTime", value: "")
    }

    // Concise log summary
    log.info "State: ${tempC}°C, ${runtime.actualHumidity}%, Heat ${heatC}°C, Cool ${coolC}°C, ${operatingState}, ${activeHold ? 'Hold active' : 'No hold'}"

    state.lastUpdate = new Date().format("yyyy-MM-dd HH:mm:ss")
}

// ========================================
// Thermostat Capability Methods
// ========================================

void setCoolingSetpoint(BigDecimal temperature) {
    log.info "Setting cooling setpoint to ${temperature}°C"
    setHoldTemperature(null, temperature, "nextTransition")
}

void setHeatingSetpoint(BigDecimal temperature) {
    log.info "Setting heating setpoint to ${temperature}°C"
    setHoldTemperature(temperature, null, "nextTransition")
}

void setThermostatMode(String mode) {
    log.info "Setting thermostat mode to ${mode}"

    if (!constValidThermostatModes.containsKey(mode)) {
        log.error "Invalid thermostat mode: ${mode}. Valid modes: ${constValidThermostatModes.keySet()}"
        return
    }

    Map bodyData = [
        selection: [
            selectionType: "thermostats",
            selectionMatch: thermostatId
        ],
        thermostat: [
            settings: [
                hvacMode: mode
            ]
        ]
    ]

    Map data = callEcobeeApi("POST", "/1/thermostat", null, bodyData)
    if (data) {
        sendEvent(name: "thermostatMode", value: mode)
        log.info "Successfully set thermostat mode to ${mode}"
    } else {
        log.error "Failed to set thermostat mode"
    }
}

void auto() {
    setThermostatMode("auto")
}

void cool() {
    setThermostatMode("cool")
}

void emergencyHeat() {
    setThermostatMode("auxHeatOnly")
}

void heat() {
    setThermostatMode("heat")
}

void off() {
    setThermostatMode("off")
}

void setThermostatSchedule(String jsonSchedule) {
    log.warn "setThermostatSchedule() not implemented - use setThermostatScheduleTime() instead"
}

void setThermostatFanMode(String fanMode) {
    log.info "Setting fan mode to ${fanMode}"

    if (!constValidFanModes.containsKey(fanMode)) {
        log.error "Invalid fan mode: ${fanMode}. Valid modes: ${constValidFanModes.keySet()}"
        return
    }

    Map function = [
        type: "setHold",
        params: [
            holdType: "nextTransition",
            fan: fanMode
        ]
    ]

    if (sendFunction(function)) {
        sendEvent(name: "thermostatFanMode", value: fanMode)
        log.info "Successfully set fan mode to ${fanMode}"
    } else {
        log.error "Failed to set fan mode"
    }
}

void fanAuto() {
    setThermostatFanMode("auto")
}

void fanCirculate() {
    setThermostatFanMode("circulate")
}

void fanOn() {
    setThermostatFanMode("on")
}

// Helper method for setting temperature holds
private void setHoldTemperature(BigDecimal heatTemp, BigDecimal coolTemp, String holdType = "nextTransition") {
    Map params = [holdType: holdType]

    if (heatTemp != null) {
        params.heatHoldTemp = celsiusToEcobee(heatTemp)
    }
    if (coolTemp != null) {
        params.coolHoldTemp = celsiusToEcobee(coolTemp)
    }

    Map function = [
        type: "setHold",
        params: params
    ]

    if (sendFunction(function)) {
        if (heatTemp != null) {
            sendEvent(name: "heatingSetpoint", value: heatTemp, unit: "°C")
        }
        if (coolTemp != null) {
            sendEvent(name: "coolingSetpoint", value: coolTemp, unit: "°C")
        }
        log.info "Successfully set temperature hold"
    } else {
        log.error "Failed to set temperature hold"
    }
}

void getWeatherForecast() {
    Map selection = [selectionType: "thermostats", selectionMatch: thermostatId, includeWeather: true]
    Map thermostat = fetchThermostatData("/1/thermostat", selection)
    if (!thermostat) return

    Map weather = thermostat.weather
    if (!weather?.forecasts) {
        log.error "No weather forecast available"
        return
    }

    log.info "Weather: ${weather.weatherStation}"
    weather.forecasts.take(5).eachWithIndex { forecast, index ->
        String day = index == 0 ? "Today" : "Day ${index}"
        BigDecimal high = ecobeeToCelsius(forecast.tempHigh)
        BigDecimal low = ecobeeToCelsius(forecast.tempLow)
        String precip = forecast.pop > 0 ? ", ${forecast.pop}% precip" : ""
        log.info "  ${day}: ${forecast.condition}, ${high}°C/${low}°C, ${forecast.relativeHumidity}%${precip}"
    }
}

List<Map> listSensors() {
    Map selection = [selectionType: "thermostats", selectionMatch: thermostatId, includeRuntime: true, includeSensors: true]
    Map thermostat = fetchThermostatData("/1/thermostat", selection)
    if (!thermostat) return []

    List<Map> sensors = thermostat.remoteSensors
    if (!sensors || sensors.size() == 0) {
        log.info "No remote sensors found"
        return []
    }

    log.info "Found ${sensors.size()} sensor(s)"
    sensors.each { Map sensor ->
        List<String> readings = []
        sensor.capability.each { Map cap ->
            if (cap.type == "temperature" && cap.value && cap.value != "unknown") {
                try {
                    BigDecimal tempC = ecobeeToCelsius(cap.value.toBigDecimal())
                    readings << "${tempC}°C"
                } catch (Exception e) {
                    // Skip unparseable temperature
                }
            } else if (cap.type == "occupancy" && cap.value) {
                readings << "Occ: ${cap.value}"
            } else if (cap.type == "humidity" && cap.value && cap.value != "unknown") {
                readings << "${cap.value}% RH"
            }
        }
        log.info "  ${sensor.name} (${sensor.type}): ${readings.join(', ')}"
    }

    return sensors
}

// ========================================
// Helper Methods
// ========================================

private static BigDecimal roundTemp(BigDecimal temp) {
    return ((temp * 10).setScale(0, RoundingMode.HALF_UP) / 10).setScale(1, RoundingMode.HALF_UP)
}

private boolean validateToken() {
    if (!state.accessToken) {
        log.warn "Not authorized - run connect() first"
        return false
    }
    return true
}

private boolean validateDayParameter(String day) {
    if (!constScheduleDays.contains(day?.toLowerCase())) {
        log.error "Invalid day: ${day}. Must be one of: ${constScheduleDays}"
        return false
    }
    return true
}

private Map fetchThermostatData(String apiPath = "/1/thermostat", Map customSelection = null) {
    if (!validateToken()) return null

    Map selection = customSelection != null ? customSelection : [
        selectionType: "thermostats",
        selectionMatch: thermostatId,
        includeProgram: true,
        includeEvents: true
    ]

    Map queryData = [json: groovy.json.JsonOutput.toJson([selection: selection])]
    Map data = callEcobeeApi("GET", apiPath, queryData)

    if (data?.status?.code != null && data.status.code != 0) {
        log.error "API error: ${data.status.message}"
        return null
    }

    return data?.thermostatList ? data.thermostatList[0] : null
}

private List adjustScheduleBlocks(List blocks, Integer fromBlock, Integer toBlock, String climateRef, String previousClimate) {
    if (toBlock < fromBlock) {
        // Moving earlier: extend this climate backward
        for (int i = toBlock; i < fromBlock; i++) {
            blocks[i] = climateRef
        }
    } else if (toBlock > fromBlock) {
        // Moving later: previous climate extends into old space
        if (previousClimate == null) {
            log.error "Cannot move first schedule entry later"
            return null
        }
        for (int i = fromBlock; i < toBlock; i++) {
            blocks[i] = previousClimate
        }
    }
    return blocks
}

// ========================================
// Utility Methods
// ========================================

Boolean sendFunction(Map function) {
    Map body = [
        selection: [
            selectionType: "thermostats",
            selectionMatch: thermostatId
        ],
        functions: [function]
    ]

    Map data = callEcobeeApi("POST", "/1/thermostat", [format: "json"], body)
    if (data?.status?.code == 0) {
        return true
    }
    if (data?.status) {
        log.error "Function failed: ${data.status.message}"
    }
    return false
}

@CompileStatic
Map<String, String> parseDateTime(String dateTimeStr) {
    // Expected format: "yyyy-MM-dd HH:mm"
    try {
        String[] parts = dateTimeStr.split(" ")
        if (parts.size() != 2) return null

        String[] dateParts = parts[0].split("-")
        String[] timeParts = parts[1].split(":")

        if (dateParts.size() != 3 || timeParts.size() != 2) return null

        String date = "${dateParts[0]}-${dateParts[1]}-${dateParts[2]}"  // yyyy-MM-dd
        String time = "${timeParts[0]}:${timeParts[1]}:00"  // HH:mm:ss

        return [date: date, time: time]
    } catch (Exception e) {
        return null
    }
}

@CompileStatic
Integer timeToMinutes(String timeStr) {
    // Expected format: "HH:mm"
    try {
        String[] parts = timeStr.split(":")
        if (parts.size() != 2) return null

        int hours = parts[0].toInteger()
        int minutes = parts[1].toInteger()

        if (hours < 0 || hours > 23 || minutes < 0 || minutes > 59) return null

        return (hours * 60) + minutes
    } catch (Exception e) {
        return null
    }
}

@CompileStatic
Integer timeToBlockIndex(String timeStr) {
    // Expected format: "HH:mm" where minutes must be 00 or 30
    // Returns block index 0-47 (48 half-hour blocks per day)
    try {
        String[] parts = timeStr.split(":")
        if (parts.size() != 2) return null

        int hours = parts[0].toInteger()
        int minutes = parts[1].toInteger()

        if (hours < 0 || hours > 23) return null
        if (minutes != 0 && minutes != 30) return null

        return (hours * 2) + (minutes == 30 ? 1 : 0)
    } catch (Exception e) {
        return null
    }
}

@CompileStatic
String minutesToTime(Integer minutes) {
    int hours = (minutes / 60) as Integer
    int mins = (minutes % 60) as Integer
    return String.format("%02d:%02d", hours, mins)
}

@CompileStatic
Integer dayNameToIndex(String day) {
    return DAY_NAME_TO_INDEX[day.toLowerCase()]
}

@CompileStatic
Integer celsiusToEcobee(def temp) {
    // Ecobee uses Fahrenheit * 10 internally
    if (!temp) return null
    BigDecimal celsius = (temp instanceof String) ? temp.toBigDecimal() : (temp as BigDecimal)
    BigDecimal fahrenheit = celsius * 9 / 5 + 32
    return (fahrenheit * 10).setScale(0, BigDecimal.ROUND_HALF_UP).intValue()
}

@CompileStatic
BigDecimal ecobeeToCelsius(def temp) {
    if (!temp) return null
    BigDecimal fahrenheitTimes10 = (temp instanceof String) ? temp.toBigDecimal() : (temp as BigDecimal)
    BigDecimal celsius = (fahrenheitTimes10 / 10 - 32) * 5 / 9
    return roundTemp(celsius)
}

private void schedulePolling() {
    Integer interval = pollInterval as Integer
    if (!interval || interval < 1 || interval > 30) return

    String cronExpression = "0 0/${interval} * ? * *"
    schedule(cronExpression, "refresh")
    logDebug "Polling: every ${interval} min"
}

void logDebug(String msg) {
    if (logEnable) log.debug msg
}

void logsOff() {
    log.warn "Debug logging disabled"
    device.updateSetting("logEnable", [value: "false", type: "bool"])
}

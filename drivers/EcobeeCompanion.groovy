// Copyright (c) 2025-2026 PJ
// SPDX-License-Identifier: MIT

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
        description: "Advanced Ecobee thermostat control via OAuth API",
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
        if (state.thermostats && !state.thermostats.isEmpty()) {
            input name: "thermostatId", type: "enum", title: "Thermostat",
                  options: state.thermostats.collectEntries { id, info -> [(id): info.name] },
                  required: true
        }
        input name: "pollInterval", type: "number", title: "Polling interval (minutes)", description: "How often to refresh thermostat state (1-30 minutes)", range: "1..30", defaultValue: 5, required: true
        input name: "txtEnable", type: "bool", title: "Enable descriptionText logging", defaultValue: true
        input name: "debugEnable", type: "bool", title: "Enable debug logging", defaultValue: false, submitOnChange: true
        if (debugEnable) {
            input name: "traceEnable", type: "bool", title: "Enable trace logging", defaultValue: false
        }
    }
}

@Field static final String DRIVER_VERSION = "0.0.6"

// OAuth and API endpoints
@Field static final String ECOBEE_API_BASE= "https://api.ecobee.com"

// Static mappings
@Field static final Map<String, Integer> SCHEDULE_DAYS_INDEX = [
    monday: 0,
    tuesday: 1,
    wednesday: 2,
    thursday: 3,
    friday: 4,
    saturday: 5,
    sunday: 6
].asImmutable()

@Field static final int SCHEDULE_BLOCKS_PER_DAY = 48
@Field static final int SCHEDULE_MINUTES_PER_BLOCK = 30

// Token and timeout configuration
@Field static final long TOKEN_REFRESH_BUFFER_MS = 325782L  // Refresh 5.5 minutes before expiry
@Field static final int DEBUG_LOG_TIMEOUT_SECONDS = 3600    // Auto-disable debug logging after 1 hour


@Field static final Map VALID_FAN_MODES = [
    auto: "auto",
    on: "on",
    circulate: "circulate"
]

// HE-canonical thermostatMode -> Ecobee hvacMode wire value.
// Ecobee uses "auxHeatOnly"; HE's Thermostat capability uses "emergency heat".
@Field static final Map HE_TO_ECOBEE_MODE = [
    "auto": "auto",
    "cool": "cool",
    "heat": "heat",
    "off": "off",
    "emergency heat": "auxHeatOnly"
]

// ========================================
// Lifecycle Methods
// ========================================

void installed() {
    checkVersion()
    sendEvent(name: "connectionStatus", value: "disconnected", descriptionText: "${device.displayName} is disconnected")
    configure()
}

void updated() {
    checkVersion()
    unschedule()
    if (debugEnable) runIn(DEBUG_LOG_TIMEOUT_SECONDS, logsOff)
    schedulePolling()
}

void deviceTypeUpdated() {
    logWarn "driver change detected"
    configure()
}

void configure() {
    sendEvent(name: "supportedThermostatFanModes", value: JsonOutput.toJson(VALID_FAN_MODES.values()))
    sendEvent(name: "supportedThermostatModes", value: JsonOutput.toJson(HE_TO_ECOBEE_MODE.keySet().toList()))
}

void refresh() {
    checkVersion()
    getCurrentState()
}

private void checkVersion() {
    if (state.version != DRIVER_VERSION) {
        logWarn "New version: ${DRIVER_VERSION} (was: ${state.version})"
        state.version = DRIVER_VERSION
    }
}

// ========================================
// OAuth Flow
// ========================================

void connect() {
    if (!apiKey) {
        logError "API Key not configured"
        sendEvent(name: "connectionStatus", value: "error", descriptionText: "${device.displayName} Error: No API Key")
        return
    }

    // Wipe any prior OAuth state so a fresh PIN flow doesn't collide with stale tokens/selection.
    state.remove('accessToken')
    state.remove('refreshToken')
    state.remove('tokenExpiry')
    state.remove('thermostats')

    Map params = [
        uri: "${ECOBEE_API_BASE}/authorize",
        query: [
            response_type: "ecobeePin",
            client_id: apiKey,
            scope: "smartWrite"
        ],
        contentType: "application/json",
        timeout: 15
    ]

    try {
        httpGet(params) { response ->
            int status = response.status
            if (status < 200 || status >= 300) {
                logWarn "connect() non-success status=${status} body=${response.data}"
                return
            }
            Map data = response.data
            state.ecobeeAuthToken = data.code
            state.pinExpires = now() + (data.expires_in * 1000)

            logInfo "PIN: ${data.ecobeePin} (expires in ${data.expires_in / 60} min)"
            logInfo "Authorize at ecobee.com, then run authorize() to complete setup"

            sendEvent(name: "connectionStatus", value: "pending", descriptionText: "${device.displayName} Waiting for PIN authorization: ${data.ecobeePin}")
        }
    } catch (e) {
        Integer status = e.response?.status as Integer
        String oauthError = e.response?.data?.error?.toString()
        if (status != null && status >= 400 && status < 500) {
            logError "connect() client error status=${status} oauth=${oauthError} msg=${e.message}"
            sendEvent(name: "connectionStatus", value: "error", descriptionText: "${device.displayName} Error: ${oauthError ?: e.message}")
        } else {
            logWarn "connect() transient failure status=${status} msg=${e.message}"
        }
    }
}

void authorize() {
    if (!state.ecobeeAuthToken) {
        logError "No authorization token. Call connect() first"
        return
    }

    if (!state.pinExpires || now() > state.pinExpires) {
        logError "PIN expired. Call connect() again"
        sendEvent(name: "connectionStatus", value: "error", descriptionText: "${device.displayName} Error: PIN expired")
        return
    }

    Map params = [
        uri: "${ECOBEE_API_BASE}/token",
        query: [
            grant_type: "ecobeePin",
            code: state.ecobeeAuthToken,
            client_id: apiKey
        ],
        contentType: "application/json",
        timeout: 15
    ]

    try {
        httpPost(params) { response ->
            int status = response.status
            if (status < 200 || status >= 300) {
                logWarn "authorize() non-success status=${status} body=${response.data}"
                return
            }
            Map data = response.data
            state.accessToken = data.access_token
            state.refreshToken = data.refresh_token
            state.tokenExpiry = now() + (data.expires_in * 1000)

            state.remove('ecobeeAuthToken')
            state.remove('interval')
            state.remove('pinExpires')

            logInfo "Authorization successful"
            sendEvent(name: "connectionStatus", value: "connected")
            listThermostats()
        }
    } catch (e) {
        Integer status = e.response?.status as Integer
        String oauthError = e.response?.data?.error?.toString()
        if (status != null && status >= 400 && status < 500) {
            switch (oauthError) {
                case "authorization_pending":
                    logInfo "authorize() still waiting for PIN entry at ecobee.com"
                    break
                case "slow_down":
                    logWarn "authorize() polling too fast — back off before retrying"
                    break
                case "invalid_grant":
                case "expired_token":
                    logError "authorize() PIN expired (${oauthError}) — call connect() to get a new PIN"
                    sendEvent(name: "connectionStatus", value: "error", descriptionText: "${device.displayName} Error: PIN expired (${oauthError})")
                    break
                default:
                    logError "authorize() client error status=${status} oauth=${oauthError} msg=${e.message}"
                    sendEvent(name: "connectionStatus", value: "error", descriptionText: "${device.displayName} Error: ${oauthError ?: e.message}")
            }
        } else {
            logWarn "authorize() transient failure status=${status} msg=${e.message}"
        }
    }
}

Boolean refreshToken() {
    if (!state.refreshToken) {
        logWarn "Not authorized — run connect() to start OAuth"
        return false
    }

    Map params = [
        uri: "${ECOBEE_API_BASE}/token",
        query: [
            grant_type: "refresh_token",
            refresh_token: state.refreshToken,
            client_id: apiKey
        ],
        contentType: "application/json",
        timeout: 15
    ]

    Boolean success = false
    try {
        httpPost(params) { response ->
            int status = response.status
            if (status < 200 || status >= 300) {
                logWarn "Token refresh non-success status=${status} body=${response.data}"
                return
            }
            Map data = response.data
            state.accessToken = data.access_token
            state.refreshToken = data.refresh_token
            state.tokenExpiry = now() + (data.expires_in * 1000)

            logDebug "Token refreshed"
            sendEvent(name: "connectionStatus", value: "connected", descriptionText: "${device.displayName} is connected")
            success = true
        }
    } catch (e) {
        Integer status = e.response?.status as Integer
        String oauthError = e.response?.data?.error?.toString()
        if (status != null && status >= 400 && status < 500 && oauthError in ["invalid_grant", "invalid_client"]) {
            logError "Token refresh auth failure status=${status} oauth=${oauthError} — re-authorization required"
            sendEvent(name: "connectionStatus", value: "error", descriptionText: "${device.displayName} re-authorization required (${oauthError})")
        } else {
            logWarn "Token refresh transient failure status=${status} oauth=${oauthError} msg=${e.message}"
        }
    }
    return success
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
        json: JsonOutput.toJson([
            selection: [
                selectionType: "registered",
                selectionMatch: "",
                includeProgram: false
            ]
        ])
    ]

    Map data = callEcobeeApi("GET", "/1/thermostat", queryData)
    if (!data?.thermostatList) {
        logError "Could not retrieve thermostat list"
        return []
    }

    List<Map> thermostats = data.thermostatList
    logInfo "Found ${thermostats.size()} thermostat(s)"

    Map<String, Map> discovered = [:]
    thermostats.each { Map t ->
        String id = t.identifier?.toString()
        discovered[id] = [name: t.name?.toString(), model: t.modelNumber?.toString()]
        logInfo "  ${t.name} (${t.modelNumber}) — ID: ${id}"
    }
    state.thermostats = discovered

    if (thermostats.size() == 1) {
        String onlyId = thermostats[0].identifier?.toString()
        device.updateSetting("thermostatId", [type: "enum", value: onlyId])
        logInfo "Auto-selected the only thermostat: ${discovered[onlyId].name}"
    }

    return thermostats
}

List<Map> listComfortSettings() {
    Map thermostat = getThermostat()
    if (!thermostat) return []

    List<Map> climates = thermostat.program.climates
    logInfo "Found ${climates.size()} comfort setting(s)"
    climates.each { Map c ->
        BigDecimal heatC = ecobeeToCelsius(c.heatTemp)
        BigDecimal coolC = ecobeeToCelsius(c.coolTemp)
        logInfo "  ${c.name}: Heat ${heatC}°C, Cool ${coolC}°C (${c.climateRef})"
    }

    return climates
}

// ========================================
// Ecobee API Methods
// ========================================

private Map callEcobeeApi(String method, String path, Map queryParams = null, Map bodyData = null, int attempt = 0) {
    if (!checkAndRefreshToken()) {
        logError "Cannot call API: token refresh failed"
        return null
    }

    Map headers = [Authorization: "Bearer ${state.accessToken}"]
    if (bodyData) headers["Content-Type"] = "application/json"

    Map params = [
        uri: "${ECOBEE_API_BASE}${path}",
        headers: headers,
        contentType: "application/json",
        timeout: 15
    ]

    if (queryParams) params.query = queryParams
    if (bodyData) params.body = JsonOutput.toJson(bodyData)

    try {
        Map result = null
        Closure handler = { response ->
            int status = response.status
            if (status < 200 || status >= 300) {
                logWarn "Ecobee API non-success [${method} ${path}] status=${status} body=${response.data}"
                return
            }
            def data = response.data
            Integer ecobeeCode = data?.status?.code as Integer
            if (ecobeeCode != null && ecobeeCode != 0) {
                logWarn "Ecobee app-level error [${method} ${path}] code=${ecobeeCode} msg=${data?.status?.message}"
                return
            }
            result = data
        }
        if (method == "GET") {
            httpGet(params, handler)
        } else if (method == "POST") {
            httpPost(params, handler)
        }
        return result
    } catch (e) {
        Integer status = e.response?.status as Integer
        if (status == 401 && attempt == 0) {
            logWarn "Token invalid, refreshing..."
            if (refreshToken()) {
                return callEcobeeApi(method, path, queryParams, bodyData, attempt + 1)
            }
        }
        logError "Ecobee API failed [${method} ${path}] status=${status} msg=${e.message}"
        return null
    }
}

Map getThermostat() {
    Map thermostat = fetchThermostatData()
    if (!thermostat) {
        logError "Could not retrieve thermostat data"
    }
    return thermostat
}

Boolean updateThermostat(Map thermostat) {
    if (!requireThermostatId()) return false

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
        logError "Heat temperature is required"
        return
    }

    if (coolTemp != null) {
        BigDecimal heatC = (heatTemp instanceof String) ? heatTemp.toBigDecimal() : (heatTemp as BigDecimal)
        BigDecimal coolC = (coolTemp instanceof String) ? coolTemp.toBigDecimal() : (coolTemp as BigDecimal)
        if (heatC >= coolC) {
            logError "Heat temp must be less than cool temp"
            return
        }
    }

    Map thermostat = getThermostat()
    if (!thermostat) return

    Map climate = thermostat.program.climates.find { it.name == comfortName }
    if (!climate) {
        logError "Comfort setting '${comfortName}' not found"
        return
    }

    climate.heatTemp = celsiusToEcobee(heatTemp)
    if (coolTemp != null) {
        climate.coolTemp = celsiusToEcobee(coolTemp)
    }

    if (updateThermostat([program: thermostat.program])) {
        logInfo "Updated ${comfortName}: Heat ${heatTemp}°C" + (coolTemp ? ", Cool ${coolTemp}°C" : "")
        state.lastUpdate = new Date().format("yyyy-MM-dd HH:mm:ss")
    } else {
        logError "Failed to update ${comfortName}"
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
        logInfo "No vacations scheduled"
        return []
    }

    logInfo "Found ${vacations.size()} vacation(s)"
    vacations.each { Map v ->
        BigDecimal heatC = ecobeeToCelsius(v.heatHoldTemp)
        BigDecimal coolC = ecobeeToCelsius(v.coolHoldTemp)
        logInfo "  ${v.name}: ${v.startDate} ${v.startTime} to ${v.endDate} ${v.endTime}, Heat ${heatC}°C, Cool ${coolC}°C, Fan ${v.fan}"
    }

    return vacations
}

void createVacation(String name, String startDateTime, String endDateTime, heatTemp, coolTemp, String fanMode = "auto") {
    Map<String, String> startParts = parseDateTime(startDateTime)
    Map<String, String> endParts = parseDateTime(endDateTime)

    if (!startParts || !endParts) {
        logError "Invalid date/time format. Use: yyyy-MM-dd HH:mm"
        return
    }

    try {
        Date startDate = Date.parse("yyyy-MM-dd HH:mm:ss", "${startParts.date} ${startParts.time}")
        Date endDate = Date.parse("yyyy-MM-dd HH:mm:ss", "${endParts.date} ${endParts.time}")
        if (endDate <= startDate) {
            logError "End date/time must be after start date/time"
            return
        }
    } catch (Exception e) {
        logError "Error validating dates: ${e.message}"
        return
    }

    if (heatTemp == null || coolTemp == null) {
        logError "Both heat and cool temperatures are required"
        return
    }

    BigDecimal heatC = (heatTemp instanceof String) ? heatTemp.toBigDecimal() : (heatTemp as BigDecimal)
    BigDecimal coolC = (coolTemp instanceof String) ? coolTemp.toBigDecimal() : (coolTemp as BigDecimal)
    if (heatC >= coolC) {
        logError "Heat temp must be less than cool temp"
        return
    }

    Map thermostat = getThermostat()
    if (!thermostat) return

    if (thermostat.events?.find { it.type == "vacation" && it.name == name }) {
        logError "Vacation '${name}' already exists"
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
        logInfo "Created vacation '${name}'"
        state.lastUpdate = new Date().format("yyyy-MM-dd HH:mm:ss")
    } else {
        logError "Failed to create vacation '${name}'"
    }
}

void deleteVacation(String name) {
    Map thermostat = getThermostat()
    if (!thermostat) return

    if (!thermostat.events?.find { it.type == "vacation" && it.name == name }) {
        logError "Vacation '${name}' not found"
        return
    }

    if (sendFunction([type: "deleteVacation", params: [name: name]])) {
        logInfo "Deleted vacation '${name}'"
        state.lastUpdate = new Date().format("yyyy-MM-dd HH:mm:ss")
    } else {
        logError "Failed to delete vacation '${name}'"
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
        logError "No schedule found for ${day}"
        return []
    }

    Map<String, String> climates = thermostat.program.climates.collectEntries { [(it.climateRef): it.name] }

    logInfo "Schedule for ${day}"
    List<Map> transitions = []
    String lastClimate = null
    for (int i = 0; i < scheduleBlocks.size(); i++) {
        String climate = scheduleBlocks[i]
        if (climate != lastClimate) {
            Integer hours = Math.floor(i / 2) as Integer
            Integer minutes = (i % 2) * 30
            String timeStr = String.format("%02d:%02d", hours, minutes)
            String climateName = climates[climate] ?: climate
            logInfo "  ${timeStr}: ${climateName}"

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
        logError "Comfort setting '${comfortName}' not found"
        return
    }
    String climateRef = climate.climateRef

    Integer currentBlock = timeToBlockIndex(currentTime)
    Integer newBlock = timeToBlockIndex(newTime)

    if (currentBlock == null || newBlock == null) {
        logError "Invalid time format. Use HH:mm on 30-minute boundaries (XX:00 or XX:30)"
        return
    }

    if (currentBlock == newBlock) {
        logInfo "Time is already ${currentTime}, no change needed"
        return
    }

    Integer dayIndex = dayNameToIndex(day)
    List scheduleBlocks = program.schedule[dayIndex]

    if (scheduleBlocks[currentBlock] != climateRef) {
        logError "No ${comfortName} schedule entry at ${currentTime} on ${day}"
        return
    }

    if (currentBlock > 0 && scheduleBlocks[currentBlock - 1] == climateRef) {
        logError "${comfortName} doesn't start at ${currentTime}"
        return
    }

    String previousClimate = (currentBlock > 0) ? scheduleBlocks[currentBlock - 1] : null
    List updatedBlocks = adjustScheduleBlocks(scheduleBlocks, currentBlock, newBlock, climateRef, previousClimate)

    if (updatedBlocks == null) return

    Map updateData = [program: program]
    if (updateThermostat(updateData)) {
        logInfo "Moved ${comfortName} on ${day} from ${currentTime} to ${newTime}"
        state.lastUpdate = new Date().format("yyyy-MM-dd HH:mm:ss")
    } else {
        logError "Failed to update schedule"
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
    state.thermostatRuntime = runtime
    Map weather = thermostat.weather
    state.thermostatWeather = weather

    // Update temperature and humidity
    BigDecimal tempC = ecobeeToCelsius(runtime.actualTemperature)
    sendEvent(name: "temperature", value: tempC, unit: "°C", descriptionText: "${device.displayName} temperature is ${tempC}°C")
    sendEvent(name: "humidity", value: runtime.actualHumidity, unit: "%", descriptionText: "${device.displayName} humidity is ${runtime.actualHumidity}%")

    // Update setpoints
    BigDecimal heatC = ecobeeToCelsius(runtime.desiredHeat)
    BigDecimal coolC = ecobeeToCelsius(runtime.desiredCool)
    sendEvent(name: "heatingSetpoint", value: heatC, unit: "°C", descriptionText: "${device.displayName} heating setpoint is ${heatC}°C")
    sendEvent(name: "coolingSetpoint", value: coolC, unit: "°C", descriptionText: "${device.displayName} cooling setpoint is ${coolC}°C")

    // Update HVAC mode: custom hvacMode carries the Ecobee wire value as-is;
    // thermostatMode is translated to the HE-canonical value (auxHeatOnly -> "emergency heat").
    String hvacModeValue = thermostat.settings?.hvacMode?.toString() ?: "unknown"
    String thermostatModeValue = HE_TO_ECOBEE_MODE.find { k, v -> v == hvacModeValue }?.key ?: hvacModeValue
    sendEvent(name: "hvacMode", value: hvacModeValue, descriptionText: "${device.displayName} HVAC mode is set to ${hvacModeValue}")
    sendEvent(name: "thermostatMode", value: thermostatModeValue, descriptionText: "${device.displayName} thermostat mode is ${thermostatModeValue}")

    // Update current program
    String currentProgramValue = thermostat.program?.currentClimateRef?.toString() ?: "unknown"
    sendEvent(name: "currentProgram", value: currentProgramValue, descriptionText: "${device.displayName} current program is ${currentProgramValue}")

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
    sendEvent(name: "thermostatOperatingState", value: operatingState, descriptionText: "${device.displayName} thermostat operating state is ${operatingState}")

    // Update weather
    if (weather?.forecasts?.size() > 0) {
        Map current = weather.forecasts[0]
        BigDecimal outdoorTempC = ecobeeToCelsius(current.temperature)
        sendEvent(name: "outdoorTemperature", value: outdoorTempC, unit: "°C", descriptionText: "${device.displayName} outdoor temperature is ${outdoorTempC}°C")
        String weatherValue = current.condition?.toString() ?: ""
        sendEvent(name: "weatherCondition", value: weatherValue, descriptionText: "${device.displayName} weather is ${weatherValue}")
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
    logInfo "State: ${tempC}°C, ${runtime.actualHumidity}%, Heat ${heatC}°C, Cool ${coolC}°C, ${operatingState}, ${activeHold ? 'Hold active' : 'No hold'}"

    state.lastUpdate = new Date().format("yyyy-MM-dd HH:mm:ss")
}

// ========================================
// Thermostat Capability Methods
// ========================================

void setCoolingSetpoint(BigDecimal temperature) {
    logInfo "Setting cooling setpoint to ${temperature}°C"
    setHoldTemperature(null, temperature)
}

void setHeatingSetpoint(BigDecimal temperature) {
    logInfo "Setting heating setpoint to ${temperature}°C"
    setHoldTemperature(temperature, null)
}

void setThermostatMode(String mode) {
    logInfo "Setting thermostat mode to ${mode}"

    if (!HE_TO_ECOBEE_MODE.containsKey(mode)) {
        logError "Invalid thermostat mode: ${mode}. Valid modes: ${HE_TO_ECOBEE_MODE.keySet()}"
        return
    }

    if (!requireThermostatId()) return

    String ecobeeMode = HE_TO_ECOBEE_MODE[mode]
    Map bodyData = [
        selection: [
            selectionType: "thermostats",
            selectionMatch: thermostatId
        ],
        thermostat: [
            settings: [
                hvacMode: ecobeeMode
            ]
        ]
    ]

    Map data = callEcobeeApi("POST", "/1/thermostat", null, bodyData)
    if (data) {
        sendEvent(name: "thermostatMode", value: mode)
        sendEvent(name: "hvacMode", value: ecobeeMode)
        logInfo "Successfully set thermostat mode to ${mode}"
    } else {
        logError "Failed to set thermostat mode"
    }
}

void auto() {
    setThermostatMode("auto")
}

void cool() {
    setThermostatMode("cool")
}

void emergencyHeat() {
    setThermostatMode("emergency heat")
}

void heat() {
    setThermostatMode("heat")
}

void off() {
    setThermostatMode("off")
}

void setThermostatFanMode(String fanMode) {
    logInfo "Setting fan mode to ${fanMode}"

    if (!VALID_FAN_MODES.containsKey(fanMode)) {
        logError "Invalid fan mode: ${fanMode}. Valid modes: ${VALID_FAN_MODES.keySet()}"
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
        logInfo "Successfully set fan mode to ${fanMode}"
    } else {
        logError "Failed to set fan mode"
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
        logInfo "Successfully set temperature hold"
    } else {
        logError "Failed to set temperature hold"
    }
}

void getWeatherForecast() {
    Map selection = [selectionType: "thermostats", selectionMatch: thermostatId, includeWeather: true]
    Map thermostat = fetchThermostatData("/1/thermostat", selection)
    if (!thermostat) return

    Map weather = thermostat.weather
    if (!weather?.forecasts) {
        logError "No weather forecast available"
        return
    }

    logInfo "Weather: ${weather.weatherStation}"
    weather.forecasts.take(5).eachWithIndex { forecast, index ->
        String day = index == 0 ? "Today" : "Day ${index}"
        BigDecimal high = ecobeeToCelsius(forecast.tempHigh)
        BigDecimal low = ecobeeToCelsius(forecast.tempLow)
        String precip = forecast.pop > 0 ? ", ${forecast.pop}% precip" : ""
        logInfo "  ${day}: ${forecast.condition}, ${high}°C/${low}°C, ${forecast.relativeHumidity}%${precip}"
    }
}

List<Map> listSensors() {
    Map selection = [selectionType: "thermostats", selectionMatch: thermostatId, includeRuntime: true, includeSensors: true]
    Map thermostat = fetchThermostatData("/1/thermostat", selection)
    if (!thermostat) return []

    List<Map> sensors = thermostat.remoteSensors
    if (!sensors || sensors.size() == 0) {
        logInfo "No remote sensors found"
        return []
    }

    logInfo "Found ${sensors.size()} sensor(s)"
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
        logInfo "  ${sensor.name} (${sensor.type}): ${readings.join(', ')}"
    }

    return sensors
}

// ========================================
// Helper Methods
// ========================================

private static BigDecimal roundTemp(BigDecimal temp) {
    return temp.setScale(1, RoundingMode.HALF_UP)
}

private boolean requireThermostatId() {
    if (!thermostatId) {
        logError "No thermostat selected — run listThermostats() to discover, then save preferences"
        return false
    }
    return true
}

private boolean validateDayParameter(String day) {
    if (!SCHEDULE_DAYS_INDEX.keySet().contains(day?.toLowerCase())) {
        logError "Invalid day: ${day}. Must be one of: ${SCHEDULE_DAYS_INDEX.keySet()}"
        return false
    }
    return true
}

private Map fetchThermostatData(String apiPath = "/1/thermostat", Map customSelection = null) {
    if (!requireThermostatId()) return null

    Map selection = customSelection != null ? customSelection : [
        selectionType: "thermostats",
        selectionMatch: thermostatId,
        includeProgram: true,
        includeEvents: true
    ]

    Map queryData = [json: JsonOutput.toJson([selection: selection])]
    Map data = callEcobeeApi("GET", apiPath, queryData)
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
            logError "Cannot move first schedule entry later"
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
    if (!requireThermostatId()) return false

    Map body = [
        selection: [
            selectionType: "thermostats",
            selectionMatch: thermostatId
        ],
        functions: [function]
    ]

    return callEcobeeApi("POST", "/1/thermostat", [format: "json"], body) != null
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
    return SCHEDULE_DAYS_INDEX[day.toLowerCase()]
}

Integer celsiusToEcobee(def temp) {
    // Ecobee uses Fahrenheit * 10 internally
    if (temp == null) return null
    BigDecimal celsius = (temp instanceof String) ? temp.toBigDecimal() : (temp as BigDecimal)
    BigDecimal fahrenheit = celsius * 9 / 5 + 32
    return (fahrenheit * 10).setScale(0, RoundingMode.HALF_UP).intValue()
}

BigDecimal ecobeeToCelsius(def temp) {
    if (temp == null) return null
    BigDecimal fahrenheitTimes10 = (temp instanceof String) ? temp.toBigDecimal() : (temp as BigDecimal)
    BigDecimal celsius = (fahrenheitTimes10 / 10 - 32) * 5 / 9
    return roundTemp(celsius)
}

private void schedulePolling() {
    Integer interval = pollInterval as Integer
    Integer effective = interval

    if (!interval || interval < 1 || interval > 30) {
        logWarn "Polling interval ${interval} out of range (1..30) — falling back to 5 min"
        effective = 5
    } else if ((60 % interval) != 0) {
        logWarn "Polling interval ${interval} does not divide 60 evenly (cron would be irregular) — falling back to 5 min"
        effective = 5
    }

    String cronExpression = "0 0/${effective} * ? * *"
    schedule(cronExpression, "refresh")
    logDebug "Polling: every ${effective} min"
}

private void logTrace(String message) {
    if (traceEnable) log.trace("${device.displayName} : ${message}")
}

private void logDebug(String message) {
    if (debugEnable) log.debug("${device.displayName} : ${message}")
}

private void logInfo(String message) {
    if (txtEnable) log.info("${device.displayName} : ${message}")
}

private void logWarn(String message) {
    log.warn("${device.displayName} : ${message}")
}

private void logError(String message) {
    log.error("${device.displayName} : ${message}")
}

void logsOff() {
    logWarn "Debug/trace logging disabled"
    device.updateSetting("debugEnable", [value: false, type: "bool"])
    device.updateSetting("traceEnable", [value: false, type: "bool"])
}

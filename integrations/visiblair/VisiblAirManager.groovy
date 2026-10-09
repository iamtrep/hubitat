// Copyright (c) 2025-2026 PJ
// SPDX-License-Identifier: MIT

/*
 * VisiblAir Manager — Parent Integration App
 *
 * Discovers sensors via the VisiblAir cloud API and creates child devices
 * using model-specific drivers. Handles bulk polling and firmware commands.
 */

import com.hubitat.app.ChildDeviceWrapper
import groovy.json.JsonOutput
import groovy.transform.CompileStatic
import groovy.transform.Field

definition(
    name: "VisiblAir Manager",
    namespace: "iamtrep",
    author: "pj",
    description: "Auto-discovers VisiblAir sensors and creates child devices with model-specific drivers",
    menu: "Integrations", // new in platform 2.5.0
    category: "Convenience",
    singleInstance: true,
    // Polls, config fetches and child config saves all rewrite state.sensorConfigs; no handler blocks.
    singleThreaded: true,
    importUrl: "https://raw.githubusercontent.com/iamtrep/hubitat/refs/heads/main/integrations/visiblair/VisiblAirManager.groovy",
    iconUrl: "",
    iconX2Url: ""
)

@Field static final String CODE_VERSION = "2.1.6"
@Field static final String VISIBLAIR_API = "https://api.visiblair.com/api/v1"
@Field static final int HTTP_TIMEOUT = 15
@Field static final String DNI_PREFIX = "visiblair-"
// Consecutive failed polls before the outage is logged as an error and children go offline.
@Field static final int POLL_FAILURES_BEFORE_ALARM = 3
@Field static final long OUTAGE_REMINDER_MS = 3600000L
// A sensor is offline when its last sample is older than this many sample periods.
@Field static final int STALE_SAMPLE_PERIODS = 3

preferences {
    page(name: "mainPage")
}

Map mainPage() {
    dynamicPage(name: "mainPage", title: "VisiblAir Manager", install: true, uninstall: true) {
        section("API Configuration") {
            input "apiEmail", "text", title: "VisiblAir Email", required: true
            input "apiPassword", "password", title: state.passwordEnc ? "VisiblAir Password (stored, enter only to change it)" : "VisiblAir Password",
                  required: !state.passwordEnc
            input "pollRate", "number", title: "Poll rate (minutes)", defaultValue: 5, range: "1..60"
        }
        section("Discovered Sensors") {
            List<Map> sensors = state.discoveredSensors ?: []
            if (sensors.size() == 0) {
                paragraph "No sensors discovered yet. Click Re-discover to query the API."
            } else {
                sensors.each { Map sensor ->
                    String uuid = sensor.uuid
                    String model = sensor.model ?: "?"
                    String variant = sensor.modelVariant ?: ""
                    String desc = sensor.description ?: uuid
                    String driverName = resolveDriverName(model, variant)
                    String dni = "${DNI_PREFIX}${uuid}"
                    ChildDeviceWrapper child = getChildDevice(dni)
                    if (child) {
                        paragraph "<a href='/device/edit/${child.id}' target='_blank'><b>${desc}</b></a> (${model}${variant ? '/' + variant : ''}) — ${driverName}"
                    } else {
                        paragraph "<b>${desc}</b> (${model}${variant ? '/' + variant : ''}) — ${driverName} [pending creation]"
                    }
                }
            }
            input name: "btnRediscover", type: "button", title: "Re-discover sensors"

            List<Map> orphans = state.orphanedDevices ?: []
            if (orphans.size() > 0) {
                paragraph "<span style='color:orange'><b>Orphaned devices (${orphans.size()}):</b></span>"
                orphans.each { Map orphan ->
                    paragraph "<a href='/device/edit/${orphan.id}' target='_blank'>${orphan.label}</a> (${orphan.dni})"
                }
                input name: "btnRemoveOrphans", type: "button", title: "Remove orphaned devices"
            }
        }
        section("Logging") {
            input name: "txtEnable", type: "bool", title: "Enable info logging", defaultValue: true
            input name: "debugEnable", type: "bool", title: "Enable debug logging", defaultValue: false, submitOnChange: true
            if (debugEnable) {
                input name: "traceEnable", type: "bool", title: "Enable trace logging", defaultValue: false
            }
        }
    }
}

void installed() {
    logDebug "installed"
    updated()
}

// No initialize(): updated() is the convergence point, so it records the version.
void updated() {
    checkVersion(false)
    logDebug "updated"
    unschedule()
    if (debugEnable || traceEnable) runIn(1800, turnOffDebugLogging)
    encryptPassword()

    if (!apiEmail || !apiPasswordValue()) {
        logWarn "Email/password not configured"
        return
    }

    pollSensors()

    int rate = (pollRate ?: 5) as int
    Random rng = new Random()
    schedule("${rng.nextInt(60)} */${rate} * ? * *", pollSensors)
    logSched "scheduled polling every ${rate} minutes"
}

private void checkVersion(boolean reinit = true) {
    if (state.version == CODE_VERSION) return
    logVer "New version: ${CODE_VERSION} (was: ${state.version})"
    state.version = CODE_VERSION
    if (reinit) runIn(1, "updated")
}

void uninstalled() {
    unschedule()
}

void appButtonHandler(String buttonName) {
    checkVersion()
    switch (buttonName) {
        case "btnRediscover":
            logInfo "re-discovery requested"
            pollSensors()
            break
        case "btnRemoveOrphans":
            List<Map> orphans = state.orphanedDevices ?: []
            orphans.each { Map orphan ->
                String dni = orphan.dni as String
                logWarn "removing orphaned device: ${dni} (${orphan.label})"
                try {
                    deleteChildDevice(dni)
                } catch (Exception e) {
                    logError "failed to remove ${dni}: ${e.message}"
                }
            }
            state.orphanedDevices = []
            logCfg "removed ${orphans.size()} orphaned devices"
            break
    }
}

// --- Authentication ---
// The password is kept encrypted with the hub's key, so the app's state and settings pages
// don't show it. A hub migration loses the key: enter the password again after one.

private void encryptPassword() {
    if (!settings.apiPassword) return
    state.passwordEnc = encrypt(settings.apiPassword as String)
    app.removeSetting("apiPassword")
}

private String apiPasswordValue() {
    if (settings.apiPassword) return settings.apiPassword
    if (!state.passwordEnc) return null
    try {
        return decrypt(state.passwordEnc as String)
    } catch (Exception e) {
        logWarn "could not decrypt the stored password (${e.message}); enter it again"
        state.remove("passwordEnc")
        return null
    }
}

// Logs in, then runs the request named by data.next with the fresh token.
private void loginThen(String next, String failMsg, Map ctx = [:]) {
    Map loginParams = [
        uri: "${VISIBLAIR_API}/auth/login",
        requestContentType: "application/json",
        contentType: "application/json",
        body: JsonOutput.toJson([email: apiEmail, password: apiPasswordValue()]),
        timeout: HTTP_TIMEOUT
    ]
    asynchttpPost("handleLoginResponse", loginParams, ctx + [next: next, failMsg: failMsg])
}

void handleLoginResponse(resp, data) {
    String token = null
    String err = null
    if (resp.hasError()) {
        err = resp.getErrorMessage()
    } else if (resp.getStatus() != 200) {
        err = "HTTP ${resp.getStatus()}"
    } else {
        try {
            token = resp.json?.accessToken as String
            if (!token) err = "no access token in response"
        } catch (Exception e) {
            err = e.message
        }
    }
    if (!token) {
        if (data.next == "poll") {
            pollFailed("login: ${err}")
        } else {
            logError "${data.failMsg}: login: ${err}"
        }
        return
    }
    logNet "login successful"
    switch (data.next) {
        case "poll":        requestSensors(token); break
        case "firmware":    requestFirmwareCommand(token, data.uuid as String, data.cmd as String); break
        case "fetchConfig": requestSensorsForConfig(token, data.uuid as String, data.overrides as Map); break
        case "assign":      requestConfigUpdate(token, data.uuid as String, data.config as Map, data.overrides as Map); break
    }
}

// --- Discovery & Polling ---

void pollSensors() {
    checkVersion()
    if (!apiEmail || !apiPasswordValue()) return

    loginThen("poll", "cannot poll sensors")
}

private Map sensorsRequest(String token) {
    return [
        uri: "${VISIBLAIR_API}/sensors/getForUser",
        headers: [Authorization: "Bearer ${token}"],
        requestContentType: "application/json",
        contentType: "application/json",
        timeout: HTTP_TIMEOUT
    ]
}

private void requestSensors(String token) {
    asynchttpGet("handlePollResponse", sensorsRequest(token))
}

void handlePollResponse(resp, data) {
    if (resp.hasError()) {
        pollFailed("sensors: ${resp.getErrorMessage()}")
        return
    }
    int status = resp.getStatus()
    if (status != 200 && status != 207) {
        pollFailed("sensors: HTTP ${status}")
        return
    }
    def json
    try {
        json = resp.json
    } catch (Exception e) {
        pollFailed("sensors: ${e.message}")
        return
    }
    handlePollData(json)
}

private void handlePollData(data) {
    try {
        if (!data) {
            pollFailed("sensors: empty response")
            return
        }

        List jsonList = data as List
        logNet "received ${jsonList.size()} entries from API"

        List realSensors = jsonList.findAll { Map sensor -> isRealSensor(sensor) }
        logDebug "filtered to ${realSensors.size()} real sensors"

        List<Map> discovered = []
        realSensors.each { Map sensor ->
            discovered << [
                uuid: sensor.uuid,
                model: sensor.model,
                modelVariant: sensor.modelVariant,
                modelVersion: sensor.modelVersion,
                description: sensor.description,
                viewToken: sensor.viewToken
            ]
        }
        state.discoveredSensors = discovered

        syncChildDevices(discovered)
        storeSensorConfigs(realSensors)
        dispatchSensorData(realSensors)
    } catch (Exception e) {
        pollFailed("handlePollData: ${e.message}")
        return
    }
    String recovery = pollSucceeded()
    try {
        updateSensorHealth(data as List, recovery)
    } catch (Exception e) {
        logError "updateSensorHealth: ${e.message}"
    }
}

// --- API Health ---

// Warns on each failed poll until POLL_FAILURES_BEFORE_ALARM, logs the outage once
// at error, then reminds hourly at warn; failures between reminders log at debug.
private void pollFailed(String reason) {
    long t = now()
    int failures = ((state.pollFailures ?: 0) as int) + 1
    state.pollFailures = failures
    if (failures == 1) state.pollFailingSince = t

    if (failures < POLL_FAILURES_BEFORE_ALARM) {
        logWarn "poll failed (${failures}/${POLL_FAILURES_BEFORE_ALARM}): ${reason}"
    } else if (failures == POLL_FAILURES_BEFORE_ALARM) {
        logError "VisiblAir API unreachable since ${formatClock(state.pollFailingSince as long)}: ${reason}"
        state.lastOutageReminder = t
        setAllChildrenHealth("offline", "VisiblAir API unreachable")
    } else if (t - ((state.lastOutageReminder ?: 0L) as long) >= OUTAGE_REMINDER_MS) {
        logWarn "VisiblAir API still unreachable after ${formatDuration(t - (state.pollFailingSince as long))} (${failures} polls failed): ${reason}"
        state.lastOutageReminder = t
    } else {
        logDebug "poll failed (${failures}): ${reason}"
    }
}

// Returns the recovery line when this poll ends a declared outage, else null.
private String pollSucceeded() {
    int failures = (state.pollFailures ?: 0) as int
    String recovery = null
    if (failures >= POLL_FAILURES_BEFORE_ALARM) {
        recovery = "VisiblAir API back after ${formatDuration(now() - (state.pollFailingSince as long))} (${failures} polls failed)"
        logInfo recovery
    } else if (failures > 0) {
        logDebug "poll recovered after ${failures} failed"
    }
    state.remove("pollFailures")
    state.remove("pollFailingSince")
    state.remove("lastOutageReminder")
    return recovery
}

// Marks each sensor offline when its last sample is older than STALE_SAMPLE_PERIODS sample periods.
// recovery: the outage recovery line, used as the online reason on the poll that ends an outage.
private void updateSensorHealth(List sensorList, String recovery = null) {
    long t = now()
    sensorList.findAll { Map sensor -> isRealSensor(sensor) }.each { Map sensor ->
        ChildDeviceWrapper child = getChildDevice("${DNI_PREFIX}${sensor.uuid}")
        if (!child) return
        Long sampledAt = parseSampleTime(sensor.lastSampleTimeStamp as String, sensor.tz as String)
        String rate = sensor.sampleRate as String
        int period = rate?.isInteger() ? rate.toInteger() : 900
        if (sampledAt == null) {
            child.setHealthStatus("offline", "last sample time unreadable")
        } else if (t - sampledAt > STALE_SAMPLE_PERIODS * period * 1000L) {
            child.setHealthStatus("offline", "no sample for ${formatDuration(t - sampledAt)}")
        } else {
            child.setHealthStatus("online", recovery ?: "reporting")
        }
    }
}

private void setAllChildrenHealth(String status, String reason) {
    getChildDevices().each { child -> child.setHealthStatus(status, reason) }
}

// The API reports sample times as local wall-clock time in the sensor's configured zone.
private Long parseSampleTime(String ts, String tz) {
    if (!ts) return null
    try {
        TimeZone zone = tz ? TimeZone.getTimeZone(tz) : location.timeZone
        return Date.parse("yyyy-MM-dd HH:mm:ss", ts, zone).time
    } catch (Exception e) {
        logDebug "cannot parse sample time '${ts}': ${e.message}"
        return null
    }
}

private String formatClock(long t) {
    return new Date(t).format("yyyy-MM-dd HH:mm", location.timeZone)
}

@CompileStatic
static String formatDuration(long ms) {
    long minutes = ms.intdiv(60000L)
    if (minutes < 60) return "${minutes} min"
    return "${minutes.intdiv(60L)} h ${minutes % 60} min"
}

// --- Child Device Lifecycle ---

private void syncChildDevices(List<Map> sensors) {
    Set<String> activeDnis = [] as Set

    sensors.each { Map sensor ->
        String uuid = sensor.uuid as String
        String dni = "${DNI_PREFIX}${uuid}"
        activeDnis << dni

        String model = (sensor.model ?: "") as String
        String variant = (sensor.modelVariant ?: "") as String
        String driverName = resolveDriverName(model, variant)
        String description = (sensor.description ?: uuid) as String
        String viewToken = (sensor.viewToken ?: "") as String

        ChildDeviceWrapper child = getChildDevice(dni)
        if (!child) {
            logCfg "creating child device: ${description} (${driverName})"
            try {
                child = addChildDevice("iamtrep", driverName, dni, [
                    name: "${driverName} - ${description}",
                    label: description
                ])
            } catch (Exception e) {
                logError "failed to create child device for ${uuid}: ${e.message}"
                return
            }
        }

        child.updateDataValue("uuid", uuid)
        child.updateDataValue("viewToken", viewToken)
        child.updateDataValue("model", model)
        child.updateDataValue("modelVariant", variant)
    }

    List<Map> orphans = []
    List<ChildDeviceWrapper> children = getChildDevices()
    children.each { child ->
        if (!activeDnis.contains(child.deviceNetworkId)) {
            orphans << [dni: child.deviceNetworkId, label: child.label ?: child.deviceNetworkId, id: child.id]
            logWarn "orphaned child device: ${child.deviceNetworkId} (${child.label})"
        }
    }
    state.orphanedDevices = orphans
}

private void dispatchSensorData(List sensorList) {
    sensorList.each { Map sensorData ->
        String uuid = sensorData.uuid as String
        String dni = "${DNI_PREFIX}${uuid}"
        ChildDeviceWrapper child = getChildDevice(dni)
        if (child) {
            logTrace "dispatching data to ${dni}"
            child.updateSensorData(sensorData)
        }
    }
}

// --- Sensor Filtering ---

@CompileStatic
static boolean isRealSensor(Map sensor) {
    String model = sensor.get("model") as String ?: ""
    if (model.isEmpty()) return false

    String ts = sensor.get("lastSampleTimeStamp") as String ?: ""
    if (ts.isEmpty() || ts.startsWith("0000")) return false

    return true
}

// --- Model-to-Driver Mapping ---

@CompileStatic
static String resolveDriverName(String model, String modelVariant) {
    String variant = modelVariant ?: ""
    if (variant.toUpperCase().contains("WIND")) return "VisiblAir Sensor XW"
    if (variant.toUpperCase().contains("SMOKE-VAPE")) return "VisiblAir Sensor O"
    String m = (model ?: "").toUpperCase()
    if (m == "X") return "VisiblAir Sensor X"
    if (m == "E" || m == "E-LITE") return "VisiblAir Sensor E"
    return "VisiblAir Sensor C"
}

// --- Firmware Command Relay ---

void sendFirmwareCommand(String uuid, String command) {
    logDebug "firmware command '${command}' for ${uuid}"

    loginThen("firmware", "cannot send firmware command", [cmd: command, uuid: uuid])
}

private void requestFirmwareCommand(String token, String uuid, String command) {
    Map requestParams = [
        uri: "${VISIBLAIR_API}/firmware/${command}",
        query: [uuid: uuid],
        headers: [Authorization: "Bearer ${token}"],
        requestContentType: "application/json",
        contentType: "application/json",
        body: "",
        timeout: HTTP_TIMEOUT
    ]

    asynchttpPut("handleFirmwareResponse", requestParams, [cmd: command, uuid: uuid])
}

void handleFirmwareResponse(resp, data) {
    try {
        if (resp.hasError()) {
            logError "firmware command '${data.cmd}' failed: ${resp.getErrorMessage()}"
            return
        }
        if (resp.getStatus() == 200) {
            logCmd "firmware command '${data.cmd}' successful for ${data.uuid}"
        } else {
            logWarn "firmware command '${data.cmd}' returned HTTP ${resp.getStatus()}"
        }
    } catch (Exception e) {
        logError "handleFirmwareResponse: ${e.message}"
    }
}

// --- On-Demand Refresh ---

void refreshSensor(String dni) {
    logDebug "refresh requested by ${dni}, triggering bulk poll"
    pollSensors()
}

// --- Sensor Configuration ---

@Field static final Map<String, String> CONFIG_FIELD_MAP = [
    "description": "description", "co2Offset": "co2Offset",
    "temperatureOffset": "temperatureOffset", "humidityOffset": "humidityOffset",
    "sampleRate": "sampleRate", "displayRefresh": "displayRefresh",
    "audibleAlertLevel": "audibleAlertLevel", "calibrationCO2Level": "calibrationCO2Level",
    "displaySleepTimeout": "displaySleepTimeout", "temperatureUnit": "temperatureUnit",
    "publicOnMap": "publicOnMap", "latitude": "latitude", "longitude": "longitude",
    "location": "location", "publicViewLinkOnMap": "publicViewLinkOnMap",
    "publicMapDescription": "publicMapDescription", "tz": "tz",
    "MQTTEndpoint": "mqttenpoint", "MQTTPort": "mqttport",
    "MQTTUsername": "mqttusername", "MQTTPassword": "mqttpassword",
    "MQTTCert": "mqttcert", "MQTTTopic": "mqtttopic",
    "alertThresholds": "alertThresholds", "config": "config"
]

@Field static final Map PORTAL_VIEW_DEFAULT = [CO2: "on", T: "on", H: "on", AQI_METHOD: "us"]

private void storeSensorConfigs(List sensorList) {
    Map<String, Map> configs = (state.sensorConfigs ?: [:]) as Map
    sensorList.each { Map sensor ->
        String uuid = sensor.uuid as String
        Map config = [:]
        CONFIG_FIELD_MAP.each { String apiField, String putParam ->
            if (sensor.containsKey(apiField)) {
                config[putParam] = sensor[apiField]
            }
        }
        if (!config.containsKey("portalView")) {
            config.portalView = PORTAL_VIEW_DEFAULT
        }
        configs[uuid] = config
    }
    state.sensorConfigs = configs
}

void updateSensorConfig(String uuid, Map overrides) {
    if (!uuid || !overrides) return

    Map<String, Map> configs = (state.sensorConfigs ?: [:]) as Map
    Map config = (configs[uuid] ?: [:]) as Map
    overrides.each { String key, value ->
        config[key] = value
    }
    configs[uuid] = config
    state.sensorConfigs = configs

    if (!config.containsKey("mqttenpoint")) {
        logNet "stored config incomplete for ${uuid}, fetching full config first"
        fetchAndUpdateConfig(uuid, overrides)
        return
    }

    sendConfigUpdate(uuid, config, overrides)
}

private void fetchAndUpdateConfig(String uuid, Map overrides) {
    loginThen("fetchConfig", "cannot fetch config", [uuid: uuid, overrides: overrides])
}

private void requestSensorsForConfig(String token, String uuid, Map overrides) {
    asynchttpGet("handleConfigFetchResponse", sensorsRequest(token), [token: token, uuid: uuid, overrides: overrides])
}

void handleConfigFetchResponse(resp, data) {
    String uuid = data.uuid as String
    if (resp.hasError()) {
        logError "fetchAndUpdateConfig: ${resp.getErrorMessage()}"
        return
    }
    if (resp.getStatus() != 200) {
        logError "failed to fetch config for ${uuid}: HTTP ${resp.getStatus()}"
        return
    }
    try {
        List jsonList = resp.json as List
        List realSensors = jsonList.findAll { Map sensor -> isRealSensor(sensor) }
        storeSensorConfigs(realSensors)
        Map<String, Map> configs = (state.sensorConfigs ?: [:]) as Map
        Map config = (configs[uuid] ?: [:]) as Map
        Map overrides = data.overrides as Map
        overrides.each { String key, value ->
            config[key] = value
        }
        configs[uuid] = config
        state.sensorConfigs = configs
        requestConfigUpdate(data.token as String, uuid, config, overrides)
    } catch (Exception e) {
        logError "fetchAndUpdateConfig: ${e.message}"
    }
}

private void sendConfigUpdate(String uuid, Map config, Map overrides) {
    loginThen("assign", "cannot update config", [uuid: uuid, config: config, overrides: overrides])
}

private void requestConfigUpdate(String token, String uuid, Map config, Map overrides) {
    Map<String, String> query = [uuid: uuid]
    config.each { String key, value ->
        query[key] = (value instanceof Map || value instanceof List) ? JsonOutput.toJson(value) : (value?.toString() ?: "")
    }

    logDebug "updating sensor config for ${uuid}: ${overrides} (${config.size()} fields)"

    Map requestParams = [
        uri: "${VISIBLAIR_API}/sensors/assign",
        query: query,
        headers: [Authorization: "Bearer ${token}"],
        requestContentType: "application/json",
        contentType: "application/json",
        timeout: HTTP_TIMEOUT
    ]

    asynchttpPut("handleConfigResponse", requestParams, [uuid: uuid, overrides: overrides])
}

void handleConfigResponse(resp, data) {
    try {
        if (resp.hasError()) {
            logError "config update for ${data.uuid} failed: ${resp.getErrorMessage()}"
            return
        }
        if (resp.getStatus() == 200) {
            logCmd "config updated for ${data.uuid}: ${data.overrides}"
        } else {
            logWarn "config update for ${data.uuid} returned HTTP ${resp.getStatus()}"
        }
    } catch (Exception e) {
        logError "handleConfigResponse: ${e.message}"
    }
}

// --- Logging ---

void turnOffDebugLogging() {
    logWarn "debug logging disabled"
    app.updateSetting("debugEnable", [value: "false", type: "bool"])
    app.updateSetting("traceEnable", [value: "false", type: "bool"])
}

// ── Logging (app) ─────────────────────────────────────────────────────
//   ⬇️ Evt  ⬆️ Cmd  🔧 Cfg  🌐 Net  ⏰ Sched  🏷️ Ver  ·  ⚠️ Warn  🛑 Error  🔬 Trace
private String logp(String e) { "${e} ${app.getLabel()}: " }

void logEvt  (String m) { if (debugEnable) log.debug logp('⬇️') + m }
void logCmd  (String m) { if (txtEnable != false) log.info  logp('⬆️') + m }
void logCfg  (String m) { if (txtEnable != false) log.info  logp('🔧') + m }
void logNet  (String m) { if (debugEnable) log.debug logp('🌐') + m }
void logSched(String m) { if (debugEnable) log.debug logp('⏰') + m }
void logVer  (String m) { log.warn  logp('🏷️') + m }

void logWarn (String m) { log.warn  logp('⚠️') + m }
void logError(String m) { log.error logp('🛑') + m }
void logTrace(String m) { if (traceEnable) log.trace logp('🔬') + m }
void logInfo (String m) { if (txtEnable != false) log.info  "${app.getLabel()}: ${m}" }
void logDebug(String m) { if (debugEnable) log.debug "${app.getLabel()}: ${m}" }

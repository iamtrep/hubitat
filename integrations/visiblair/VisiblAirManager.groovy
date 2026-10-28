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
import java.util.concurrent.ConcurrentHashMap

definition(
    name: "VisiblAir Manager",
    namespace: "iamtrep",
    author: "pj",
    description: "Auto-discovers VisiblAir sensors and creates child devices with model-specific drivers",
    menu: "Integrations", // new in platform 2.5.0
    category: "Convenience",
    singleInstance: true,
    // Polls, config fetches and child config saves all rewrite state.sensorConfigs; no handler blocks.
    importUrl: "https://raw.githubusercontent.com/iamtrep/hubitat/refs/heads/main/integrations/visiblair/VisiblAirManager.groovy",
    iconUrl: "",
    iconX2Url: ""
)

@Field static final String CODE_VERSION = "2.1.9"
@Field static final String VISIBLAIR_API = "https://api.visiblair.com/api/v1"
@Field static final int HTTP_TIMEOUT = 15
// Decrypted password, kept in memory only: decrypt() costs ~350 ms on a C-8 Pro, ~830 ms on a C-7.
// Keyed by app id; the stored ciphertext rides along so a changed password misses.
@Field static final ConcurrentHashMap<String, List<String>> SECRET_CACHE = new ConcurrentHashMap<>()
@Field static final String DNI_PREFIX = "visiblair-"
// The last lastSampleTimeStamp sent to each child, keyed "appId:uuid". A poll between two samples
// (the API samples every 15 min, the default poll is 5) skips the child. Empty after a reboot or code
// push, so the first poll sends everything.
@Field static final ConcurrentHashMap<String, String> LAST_SAMPLE = new ConcurrentHashMap<>()
// App ids whose child devices were synced since the last reboot or code push.
@Field static final ConcurrentHashMap<String, Boolean> CHILDREN_SYNCED = new ConcurrentHashMap<>()
// Start of the last scheduled poll, by app id. After a reboot a cron schedule runs a catch-up about
// 10 s after its first run; a scheduled poll within MIN_POLL_GAP_MS of the last is skipped.
@Field static final ConcurrentHashMap<String, Long> LAST_POLL = new ConcurrentHashMap<>()
@Field static final long MIN_POLL_GAP_MS = 60_000L
// The API's bearer token, by app id, reused until the API refuses it (see reloginIfRefused).
// Memory only: a reboot or code push costs one login.
@Field static final ConcurrentHashMap<String, String> API_TOKEN = new ConcurrentHashMap<>()
// Consecutive failed polls before the outage is logged as an error and children go offline.
@Field static final int POLL_FAILURES_BEFORE_ALARM = 3
@Field static final long OUTAGE_REMINDER_MS = 3600000L
// A sensor is offline when its last sample is older than this many sample periods.
@Field static final int STALE_SAMPLE_PERIODS = 3

preferences {
    page(name: "mainPage")
}

Map mainPage() {
    dynamicPage(name: "mainPage", install: true, uninstall: true) {
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
                paragraph "<span style='color:var(--hubitat-warning-color, #8a5a00)'><b>Orphaned devices (${orphans.size()}):</b></span>"
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
    initialize()
}

void updated() {
    logDebug "updated"
    unsubscribe()
    unschedule()
    initialize()
}

// The poll is a cron schedule, which survives a reboot, so no systemStart handler is needed.
void initialize() {
    checkVersion(false)
    // A changed email or password takes effect on Done.
    API_TOKEN.remove(app.id.toString())
    if (debugEnable || traceEnable) runIn(1800, turnOffDebugLogging)
    encryptPassword()

    if (!apiEmail || !hasApiPassword()) {
        logWarn "Email/password not configured"
        return
    }

    pollSensors(true)

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
    SECRET_CACHE.remove(app.id.toString())
    SENSOR_SECRETS.keySet().removeIf { it.startsWith("${app.id}:") }
}

void appButtonHandler(String buttonName) {
    checkVersion()
    switch (buttonName) {
        case "btnRediscover":
            logInfo "re-discovery requested"
            pollSensors(true)
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
    SECRET_CACHE.remove(app.id.toString())
    app.removeSetting("apiPassword")
}

// Presence check that mirrors apiPasswordValue() without decrypting.
private boolean hasApiPassword() {
    return settings.apiPassword || state.passwordEnc
}

private String apiPasswordValue() {
    if (settings.apiPassword) return settings.apiPassword
    String enc = state.passwordEnc as String
    if (!enc) return null
    String key = app.id.toString()
    List<String> hit = SECRET_CACHE.get(key)
    if (hit != null && hit[0] == enc) return hit[1]
    try {
        String plain = decrypt(enc)
        SECRET_CACHE.put(key, [enc, plain])
        return plain
    } catch (Exception e) {
        logWarn "could not decrypt the stored password (${e.message}); enter it again"
        state.remove("passwordEnc")
        SECRET_CACHE.remove(key)
        return null
    }
}

// Runs the request named by next with the token in memory, or logs in first when there is none.
private void loginThen(String next, String failMsg, Map ctx = [:]) {
    Map req = ctx + [next: next, failMsg: failMsg]
    String token = API_TOKEN.get(app.id.toString())
    if (token) {
        runRequest(token, req + [cached: true])
        return
    }
    Map loginParams = [
        uri: "${VISIBLAIR_API}/auth/login",
        requestContentType: "application/json",
        contentType: "application/json",
        body: JsonOutput.toJson([email: apiEmail, password: apiPasswordValue()]),
        timeout: HTTP_TIMEOUT
    ]
    asynchttpPost("handleLoginResponse", loginParams, req)
}

private void runRequest(String token, Map req) {
    switch (req.next) {
        case "poll":        requestSensors(token, req); break
        case "firmware":    requestFirmwareCommand(token, req); break
        case "fetchConfig": requestSensorsForConfig(token, req); break
        case "assign":      requestConfigUpdate(token, req.config as Map, req); break
    }
}

// A reused token the API refused, or an empty sensor list on a reused token: drops the token
// and sends the same request again after a fresh login, once. True when it did.
private boolean reloginIfRefused(resp, Map data, boolean emptyAnswer = false) {
    if (!data.cached) return false
    int status = resp.getStatus()
    if (!(status in [401, 403]) && !emptyAnswer) return false
    API_TOKEN.remove(app.id.toString())
    logNet "token refused (${emptyAnswer ? 'empty answer' : "HTTP ${status}"}); logging in again"
    Map ctx = data.findAll { k, v -> !(k in ["cached", "next", "failMsg"]) }
    loginThen(data.next as String, data.failMsg as String, ctx)
    return true
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
    API_TOKEN.put(app.id.toString(), token)
    runRequest(token, data.findAll { k, v -> k != "cached" })
}

// --- Discovery & Polling ---

// force: from initialize, a button or a child's refresh; sends every sensor's data even when the
// sample is unchanged. The cron schedule calls it without arguments.
void pollSensors(boolean force = false) {
    checkVersion()
    if (!apiEmail || !hasApiPassword()) return
    String appKey = app.id.toString()
    long t = now()
    Long last = LAST_POLL.put(appKey, t)
    if (!force && last != null && t - last < MIN_POLL_GAP_MS) {
        logDebug "skipping a poll ${(t - last).intdiv(1000L)} s after the last one"
        return
    }
    if (force) {
        LAST_SAMPLE.keySet().findAll { String k -> k.startsWith(appKey + ":") }.each { LAST_SAMPLE.remove(it) }
        CHILDREN_SYNCED.remove(appKey)
    }

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

private void requestSensors(String token, Map req) {
    asynchttpGet("handlePollResponse", sensorsRequest(token), req)
}

void handlePollResponse(resp, data) {
    if (reloginIfRefused(resp, data)) return
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
    if (!json && reloginIfRefused(resp, data, true)) return
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
                description: sensor.description
            ]
        }
        // Child data values and state change only when the sensor list or its configs do.
        String appKey = app.id.toString()
        boolean listChanged = discovered != (state.discoveredSensors ?: [])
        boolean childMissing = discovered.any { Map d -> !getChildDevice("${DNI_PREFIX}${d.uuid}") }
        if (listChanged || childMissing || !CHILDREN_SYNCED.containsKey(appKey)) {
            if (listChanged) state.discoveredSensors = discovered
            syncChildDevices(discovered)
            CHILDREN_SYNCED.put(appKey, true)
        }
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
        } else if (recovery || child.currentValue("healthStatus") != "online") {
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
        // Versions before 2.1.8 stored the sensor's view token here in plain text.
        if (child.getDataValue("viewToken") != null) child.removeDataValue("viewToken")
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
        if (!child) return
        String key = "${app.id}:${uuid}".toString()
        String ts = sensorData.lastSampleTimeStamp as String
        if (ts && LAST_SAMPLE.get(key) == ts) {
            logTrace "no new sample for ${dni} since ${ts}"
            return
        }
        logTrace "dispatching data to ${dni}"
        child.updateSensorData(sensorData.findAll { k, v -> !SENSOR_SECRET_FIELDS.contains(k) })
        if (ts) LAST_SAMPLE.put(key, ts)
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

private void requestFirmwareCommand(String token, Map req) {
    String uuid = req.uuid as String
    String command = req.cmd as String
    Map requestParams = [
        uri: "${VISIBLAIR_API}/firmware/${command}",
        query: [uuid: uuid],
        headers: [Authorization: "Bearer ${token}"],
        requestContentType: "application/json",
        contentType: "application/json",
        body: "",
        timeout: HTTP_TIMEOUT
    ]

    asynchttpPut("handleFirmwareResponse", requestParams, req)
}

void handleFirmwareResponse(resp, data) {
    if (reloginIfRefused(resp, data)) return
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
    pollSensors(true)
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

// API fields that are credentials: kept out of state, child devices and logs. The MQTT ones
// live in memory only, keyed by app id and sensor uuid; every poll refreshes them, and a
// config update before the first poll after a reboot fetches them first. The MQTT username is
// an identifier and stays in state. The certificate stays in memory: the API doesn't say
// whether it is a CA certificate or a client certificate with its key.
@Field static final List<String> SENSOR_SECRET_FIELDS = ["viewToken", "MQTTPassword", "MQTTCert"]
@Field static final ConcurrentHashMap<String, Map> SENSOR_SECRETS = new ConcurrentHashMap<>()

private String sensorSecretKey(String uuid) { "${app.id}:${uuid}" }

// Rebuilt from scratch on each poll, so copies older versions kept (MQTT credentials
// included) and entries of removed sensors drop out.
private void storeSensorConfigs(List sensorList) {
    Map<String, Map> configs = [:]
    sensorList.each { Map sensor ->
        String uuid = sensor.uuid as String
        Map config = [:]
        Map secrets = [:]
        CONFIG_FIELD_MAP.each { String apiField, String putParam ->
            if (sensor.containsKey(apiField)) {
                if (SENSOR_SECRET_FIELDS.contains(apiField)) secrets[putParam] = sensor[apiField]
                else config[putParam] = sensor[apiField]
            }
        }
        SENSOR_SECRETS.put(sensorSecretKey(uuid), secrets)
        if (!config.containsKey("portalView")) {
            config.portalView = PORTAL_VIEW_DEFAULT
        }
        configs[uuid] = config
    }
    if (configs != state.sensorConfigs) state.sensorConfigs = configs
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

    if (!config.containsKey("mqttenpoint") || !SENSOR_SECRETS.containsKey(sensorSecretKey(uuid))) {
        logNet "stored config incomplete for ${uuid}, fetching full config first"
        fetchAndUpdateConfig(uuid, overrides)
        return
    }

    sendConfigUpdate(uuid, config, overrides)
}

private void fetchAndUpdateConfig(String uuid, Map overrides) {
    loginThen("fetchConfig", "cannot fetch config", [uuid: uuid, overrides: overrides])
}

private void requestSensorsForConfig(String token, Map req) {
    asynchttpGet("handleConfigFetchResponse", sensorsRequest(token), req)
}

void handleConfigFetchResponse(resp, data) {
    if (reloginIfRefused(resp, data)) return
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
        sendConfigUpdate(uuid, config, overrides)
    } catch (Exception e) {
        logError "fetchAndUpdateConfig: ${e.message}"
    }
}

private void sendConfigUpdate(String uuid, Map config, Map overrides) {
    loginThen("assign", "cannot update config", [uuid: uuid, config: config, overrides: overrides])
}

private void requestConfigUpdate(String token, Map config, Map req) {
    String uuid = req.uuid as String
    Map overrides = req.overrides as Map
    Map<String, String> query = [uuid: uuid]
    (config + (SENSOR_SECRETS.get(sensorSecretKey(uuid)) ?: [:])).each { String key, value ->
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

    // The config is in the query already; the retry rebuilds it from the stored config.
    asynchttpPut("handleConfigResponse", requestParams, req.findAll { k, v -> k != "config" } + [config: config])
}

void handleConfigResponse(resp, data) {
    if (reloginIfRefused(resp, data)) return
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

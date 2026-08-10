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
    importUrl: "https://raw.githubusercontent.com/iamtrep/hubitat/refs/heads/main/integrations/visiblair/VisiblAirManager.groovy",
    iconUrl: "",
    iconX2Url: ""
)

@Field static final String CODE_VERSION = "2.0.0"
@Field static final String VISIBLAIR_API = "https://api.visiblair.com/api/v1"
@Field static final int HTTP_TIMEOUT = 15
@Field static final String DNI_PREFIX = "visiblair-"

preferences {
    page(name: "mainPage")
}

Map mainPage() {
    dynamicPage(name: "mainPage", title: "VisiblAir Manager", install: true, uninstall: true) {
        section("API Configuration") {
            input "apiEmail", "text", title: "VisiblAir Email", required: true
            input "apiPassword", "password", title: "VisiblAir Password", required: true
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
            input name: "txtEnable", type: "bool", title: "Enable descriptionText logging", defaultValue: false
            input name: "debugEnable", type: "bool", title: "Enable debug logging", defaultValue: false, submitOnChange: true
            if (debugEnable) {
                input name: "traceEnable", type: "bool", title: "Enable trace logging", defaultValue: false
            }
        }
    }
}

void installed() {
    logDebug "installed"
    state.version = CODE_VERSION
    updated()
}

void updated() {
    logDebug "updated"
    unschedule()

    if (!apiEmail || !apiPassword) {
        logWarn "Email/password not configured"
        return
    }

    if (debugEnable) runIn(1800, turnOffDebugLogging)

    pollSensors()

    int rate = (pollRate ?: 5) as int
    schedule("0 */${rate} * ? * *", pollSensors)
    logDebug "scheduled polling every ${rate} minutes"
}

void uninstalled() {
    unschedule()
}

void appButtonHandler(String buttonName) {
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
            logInfo "removed ${orphans.size()} orphaned devices"
            break
    }
}

// --- Authentication ---

private String login() {
    String token = null
    Map loginParams = [
        uri: "${VISIBLAIR_API}/auth/login",
        requestContentType: "application/json",
        contentType: "application/json",
        body: JsonOutput.toJson([email: apiEmail, password: apiPassword]),
        timeout: HTTP_TIMEOUT
    ]
    try {
        httpPost(loginParams) { resp ->
            if (resp.status == 200 && resp.data) {
                token = resp.data.accessToken as String
                logDebug "login successful"
            } else {
                logError "login failed: HTTP ${resp.status}"
            }
        }
    } catch (Exception e) {
        logError "login: ${e.message}"
    }
    return token
}

// --- Discovery & Polling ---

void pollSensors() {
    if (!apiEmail || !apiPassword) return

    String token = login()
    if (!token) {
        logError "cannot poll sensors: login failed"
        return
    }

    Map requestParams = [
        uri: "${VISIBLAIR_API}/sensors/getForUser",
        headers: [Authorization: "Bearer ${token}"],
        requestContentType: "application/json",
        contentType: "application/json",
        timeout: HTTP_TIMEOUT
    ]

    try {
        httpGet(requestParams) { resp ->
            handlePollData(resp.status, resp.data)
        }
    } catch (Exception e) {
        logError "pollSensors: ${e.message}"
    }
}

private void handlePollData(int status, data) {
    try {
        if (status != 200 && status != 207) {
            logWarn "API returned HTTP ${status}"
            return
        }

        if (!data) {
            logWarn "empty response from API"
            return
        }

        List jsonList = data as List
        logDebug "received ${jsonList.size()} entries from API"

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
        logError "handlePollData: ${e.message}"
    }
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
            logInfo "creating child device: ${description} (${driverName})"
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

    String token = login()
    if (!token) {
        logError "cannot send firmware command: login failed"
        return
    }

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
            logInfo "firmware command '${data.cmd}' successful for ${data.uuid}"
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
        logDebug "stored config incomplete for ${uuid}, fetching full config first"
        fetchAndUpdateConfig(uuid, overrides)
        return
    }

    sendConfigUpdate(uuid, config, overrides)
}

private void fetchAndUpdateConfig(String uuid, Map overrides) {
    String token = login()
    if (!token) {
        logError "cannot fetch config: login failed"
        return
    }

    Map requestParams = [
        uri: "${VISIBLAIR_API}/sensors/getForUser",
        headers: [Authorization: "Bearer ${token}"],
        requestContentType: "application/json",
        contentType: "application/json",
        timeout: HTTP_TIMEOUT
    ]
    try {
        httpGet(requestParams) { resp ->
            if (resp.status == 200) {
                List jsonList = resp.data as List
                List realSensors = jsonList.findAll { Map sensor -> isRealSensor(sensor) }
                storeSensorConfigs(realSensors)
                Map<String, Map> configs = (state.sensorConfigs ?: [:]) as Map
                Map config = (configs[uuid] ?: [:]) as Map
                overrides.each { String key, value ->
                    config[key] = value
                }
                configs[uuid] = config
                state.sensorConfigs = configs
                sendConfigUpdate(uuid, config, overrides)
            } else {
                logError "failed to fetch config for ${uuid}: HTTP ${resp.status}"
            }
        }
    } catch (Exception e) {
        logError "fetchAndUpdateConfig: ${e.message}"
    }
}

private void sendConfigUpdate(String uuid, Map config, Map overrides) {
    String token = login()
    if (!token) {
        logError "cannot update config: login failed"
        return
    }

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
            logInfo "config updated for ${data.uuid}: ${data.overrides}"
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

private void logTrace(String message) {
    if (traceEnable) log.trace "${app.label} : ${message}"
}

private void logDebug(String message) {
    if (debugEnable) log.debug "${app.label} : ${message}"
}

private void logInfo(String message) {
    if (txtEnable) log.info "${app.label} : ${message}"
}

private void logWarn(String message) {
    log.warn "${app.label} : ${message}"
}

private void logError(String message) {
    log.error "${app.label} : ${message}"
}

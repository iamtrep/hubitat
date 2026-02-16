/*
 * VisiblAir Manager — Parent Integration App
 *
 * Discovers sensors via the VisiblAir cloud API and creates child devices
 * using model-specific drivers. Handles bulk polling and firmware commands.
 *
 * Licensed under the Apache License, Version 2.0
 */

import groovy.transform.CompileStatic
import groovy.transform.Field

definition(
    name: "VisiblAir Manager",
    namespace: "iamtrep",
    author: "pj",
    description: "Auto-discovers VisiblAir sensors and creates child devices with model-specific drivers",
    category: "Convenience",
    singleInstance: true,
    iconUrl: "",
    iconX2Url: ""
)

@Field static final String APP_VERSION = "1.0.0"
@Field static final String VISIBLAIR_API = "https://api.visiblair.com:11000/api/v1"
@Field static final int HTTP_TIMEOUT = 15
@Field static final String DNI_PREFIX = "visiblair-"

preferences {
    page(name: "mainPage")
}

Map mainPage() {
    dynamicPage(name: "mainPage", title: "VisiblAir Manager", install: true, uninstall: true) {
        section("API Configuration") {
            input "userId", "text", title: "VisiblAir User ID", required: true
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
                    def child = getChildDevice(dni)
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
    state.version = APP_VERSION
    updated()
}

void updated() {
    logDebug "updated"
    unschedule()

    if (!userId) {
        logWarn "User ID not configured"
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

// --- Discovery & Polling ---

void pollSensors() {
    if (!userId) return

    Map requestParams = [
        uri: "${VISIBLAIR_API}/sensors/getForUser?enc=true&userID=${userId}",
        headers: [
            requestContentType: "application/json",
            contentType: "application/json"
        ],
        timeout: HTTP_TIMEOUT
    ]

    asynchttpGet("handlePollResponse", requestParams)
}

void handlePollResponse(resp, data) {
    try {
        if (resp.hasError()) {
            logError "API error: ${resp.getErrorMessage()}"
            return
        }

        if (resp.getStatus() != 200 && resp.getStatus() != 207) {
            logWarn "API returned HTTP ${resp.getStatus()}"
            return
        }

        if (!resp.data) {
            logWarn "empty response from API"
            return
        }

        List jsonList = resp.json as List
        logDebug "received ${jsonList.size()} entries from API"

        // Filter out template/placeholder sensors
        List realSensors = jsonList.findAll { Map sensor -> isRealSensor(sensor) }
        logDebug "filtered to ${realSensors.size()} real sensors"

        // Store discovered sensors (explicit reassignment for state persistence)
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
        logError "handlePollResponse: ${e.message}"
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

        def child = getChildDevice(dni)
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

        // Update data values (viewToken may rotate)
        child.updateDataValue("uuid", uuid)
        child.updateDataValue("viewToken", viewToken)
        child.updateDataValue("model", model)
        child.updateDataValue("modelVariant", variant)
    }

    // Track orphaned children in state for UI display
    List<Map> orphans = []
    List<com.hubitat.app.ChildDeviceWrapper> children = getChildDevices()
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
        def child = getChildDevice(dni)
        if (child) {
            logTrace "dispatching data to ${dni}"
            child.updateSensorData(sensorData)
        }
    }
}

// --- Sensor Filtering ---

@CompileStatic
static boolean isRealSensor(Map sensor) {
    // Must have a non-empty model
    String model = sensor.get("model") as String ?: ""
    if (model.isEmpty()) return false

    // Must have reported data at least once
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

    Map requestParams = [
        uri: "${VISIBLAIR_API}/firmware/${command}?uuid=${uuid}",
        headers: [
            requestContentType: "application/json",
            contentType: "application/json"
        ],
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

// Fields from the API that are configurable via PUT /sensors/assign
@Field static final List<String> CONFIG_FIELDS = [
    "description", "co2Offset", "temperatureOffset", "humidityOffset",
    "sampleRate", "displayRefresh", "audibleAlertLevel", "calibrationCO2Level",
    "displaySleepTimeout", "temperatureUnit"
]

private void storeSensorConfigs(List sensorList) {
    Map<String, Map> configs = (state.sensorConfigs ?: [:]) as Map
    sensorList.each { Map sensor ->
        String uuid = sensor.uuid as String
        Map config = [:]
        CONFIG_FIELDS.each { String field ->
            if (sensor.containsKey(field)) {
                config[field] = sensor[field]
            }
        }
        configs[uuid] = config
    }
    state.sensorConfigs = configs
}

void updateSensorConfig(String uuid, Map overrides) {
    if (!uuid || !overrides) return

    // Merge overrides into stored config
    Map<String, Map> configs = (state.sensorConfigs ?: [:]) as Map
    Map config = (configs[uuid] ?: [:]) as Map
    overrides.each { String key, value ->
        config[key] = value
    }
    configs[uuid] = config
    state.sensorConfigs = configs

    // Build query string
    StringBuilder query = new StringBuilder()
    query.append("enc=true&uuid=${URLEncoder.encode(uuid, 'UTF-8')}")
    query.append("&associatedUserID=${userId}")
    config.each { String key, value ->
        String encoded = URLEncoder.encode(value?.toString() ?: "", "UTF-8")
        query.append("&${key}=${encoded}")
    }

    logDebug "updating sensor config for ${uuid}: ${overrides}"

    Map requestParams = [
        uri: "${VISIBLAIR_API}/sensors/assign?${query}",
        headers: [
            requestContentType: "application/json",
            contentType: "application/json"
        ],
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

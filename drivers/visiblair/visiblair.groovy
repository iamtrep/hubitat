/*
 * Basic driver for VisiblAir sensor (formerly CO2.click) - https://visiblair.com/
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
 * NOTE: this driver requires instantiating one device per sensor, by inputting user id, device id
 * and access token in the settings page.
 *
 */
metadata {
    definition(
        name: "VisiblAir Sensor",
        namespace: "iamtrep",
        author: "pj",
        importUrl: "https://raw.githubusercontent.com/iamtrep/hubitat/refs/heads/main/drivers/visiblair/visiblair.groovy"
    ) {
        capability "Battery"
        capability "CarbonDioxideMeasurement"
        capability "Initialize"
        capability "PressureMeasurement"
        capability "Refresh"
        capability "RelativeHumidityMeasurement"
        capability "Sensor"
        capability "TemperatureMeasurement"

        attribute "timestamp", "date" // lastSampleTimeStamp
        attribute "calibration", "date" // lastCalibration

        command "reboot"
        command "calibrate"
        command "updateFirmware"
        //command "resetWifiSettings"   // after this command, sensor will be unreachable until a WiFi network is joined.

        //command "setBatteryLevel", ["number"] // for debug purposes
        //command "arbitraryGet", ["string"] // for debug purposes
    }
}

import groovy.transform.CompileStatic
import groovy.transform.Field

@Field static final String driver_version = "0.1.0"
@Field static final String constCO2ClickURL = 'https://environment-monitor-01.co2.click:11000/api/v1'
@Field static final String constVisiblairURL = 'https://api.visiblair.com:11000/api/v1'
@Field static final int DEBUG_LOG_TIMEOUT = 1800
@Field static final int HTTP_TIMEOUT = 15

preferences {
    section("API parameters") {
        input "userid", "text", title: "CO2.Click User ID", required: true
        input "token", "text", title: "Access Token", required: true
        /* TODO: enumerate sensors and add child devices */
        input "uuid", "text", title: "Sensor UUID", required: true
        input("pollRate", "number", title: "Sensor Polling Rate (minutes)\nZero for no polling:", defaultValue:0, range: "0..*")
    }
    section("Logging") {
        input name: "txtEnable", type: "bool", title: "Enable descriptionText logging", defaultValue: false
        input name: "debugEnable", type: "bool", title: "Enable debug logging info", defaultValue: false, required: true, submitOnChange: true
        if (debugEnable) {
            input name: "traceEnable", type: "bool", title: "Enable trace logging info (for development purposes)", defaultValue: false
        }
    }
}

// Utility functions

void turnOffDebugLogging() {
    logWarn "debug logging disabled..."
    device.updateSetting("debugEnable", [value: "false", type: "bool"])
    device.updateSetting("traceEnable", [value: "false", type: "bool"])
}

private void updateDeviceAttribute(String aKey, aValue, String aUnit = "", String aDescription = "") {
    sendEvent(name: aKey, value: aValue, unit: aUnit, descriptionText: aDescription)
    if (aDescription != "") logInfo(aDescription)
}

// driver methods

void installed() {
    logDebug "installed..."
    state.version = driver_version
    initialize()
}

void uninstalled() {
    unschedule()
}

void initialize() {
    if (state.version != driver_version) {
        logWarn "New driver version detected: ${driver_version} (previous: ${state.version})"
        state.version = driver_version
    }
    updateDeviceAttribute("battery", 100, "%")
    updated()
}

void updated() {
    if (debugEnable) runIn(DEBUG_LOG_TIMEOUT, turnOffDebugLogging)

    if (pollRate == null)
        device.updateSetting("pollRate", [value: 0, type: "number"])

    unschedule("refresh")
    if (pollRate > 0)
        runIn(pollRate * 60, "refresh")

    refresh()
}

void poll() {
    refresh()
}

void refresh() {
    if (!uuid || !token) {
        logWarn "Sensor UUID and Access Token must be configured before polling"
        return
    }

    getDeviceValuesFromAPI("sensor?uuid=${uuid}&viewToken=${token}")

    unschedule("refresh")
    if (pollRate > 0)
        runIn(pollRate * 60, "refresh")
}

// for debug purposes only
void setBatteryLevel(Number level) {
    logDebug "setBatteryLevel(${level}) was called"
    updateDeviceAttribute("battery", level)
}

// translate JSON keys to device attributes
void refreshSensorData(Map retData) {
    if (!retData) return

    retData.each {
        switch (it.key) {
            case "firmwareVersion":
                state.firmwareVersion = it.value
                break
            case "lastSampleTemperature":
                String temp = convertTemperatureIfNeeded(it.value, "c", 1)
                String unit = "°${location.temperatureScale}"
                updateDeviceAttribute("temperature", temp, unit, "Temperature is ${temp}${unit}")
                break
            case "lastSampleHumidity":
                updateDeviceAttribute("humidity", it.value, "%", "Humidity is ${it.value}%")
                break
            case "lastSampleCo2":
                updateDeviceAttribute("carbonDioxide", it.value, "ppm", "CO2 is ${it.value}ppm")
                break
            case "lastSamplePressure":
                Number pressure = (it.value instanceof Map) ? it.value.Float64 : it.value
                updateDeviceAttribute("pressure", pressure, "mBar", "Pressure is ${pressure}mBar")
                break
            case "lastSampleBattPct":
                Number battery = (it.value instanceof Map) ? it.value.Float64 : it.value
                updateDeviceAttribute("battery", battery, "%", "Battery level is ${battery}%")
                break
            case "lastSampleTimeStamp":
                updateDeviceAttribute("timestamp", it.value, "", "Timestamp of last sample is ${it.value}")
                break
            case "lastCalibration":
                updateDeviceAttribute("calibration", it.value, "")
                break
            case "model":
                state.model = it.value
                break
            case "modelVersion":
                state.modelVersion = it.value
                break
            case "modelVariant":
                state.modelVariant = it.value
                break
            case "sampleRate":
                state.sampleRate = it.value // in s
                break
            case "temperatureUnit":
                state.temperatureUnit = it.value
                break
            default:
                logDebug "attribute not handled: ${it.key}=${it.value}"
                break
        }
    }
}


void getDeviceValuesFromAPI(String command) {
    Map requestParams = [
        uri: "${constVisiblairURL}/${command}",
        headers: [
            requestContentType: 'application/json',
            contentType: 'application/json'
        ],
        timeout: HTTP_TIMEOUT
    ]

    asynchttpGet("getDeviceValuesFromAPI_async", requestParams, [cmd: command])
}

void getDeviceValuesFromAPI_async(resp, data) {
    try {
        if (resp.hasError()) {
            logError "HTTP error: ${resp.getErrorMessage()}"
            return
        }

        logDebug "${resp.properties} - ${data.cmd} - ${resp.getStatus()}"

        if (resp.getStatus() == 200 || resp.getStatus() == 207) {
            if (resp.data) {
                String cmd = data.cmd
                int end = cmd.indexOf('?')
                if (end >= 0) {
                    cmd = data.cmd.substring(0, end)
                }
                if (cmd == "sensor") {
                    Map jsonData = (HashMap) resp.json
                    refreshSensorData(jsonData)
                } else if (cmd == "sensors/getForUser") {
                    int nSensors = resp.json.size
                    logDebug "found ${nSensors} sensors for this user"
                    for (sensorJson in resp.json) {
                        Map sensorData = (HashMap) sensorJson
                        logTrace sensorData
                        refreshSensorData(sensorData)
                    }
                } else {
                    logWarn "Unhandled Command: '${data.cmd}'"
                }
            }
        } else if (resp.getStatus() == 401) {
            logError "HTTP 401 Unauthorized for '${data.cmd}' - check access token"
        } else {
            logWarn "HTTP ${resp.getStatus()} for '${data.cmd}'"
        }
    } catch (Exception e) {
        logError "getDeviceValuesFromAPI_async - ${e.message}"
    }
}


void arbitraryGet(String command) {
    getDeviceValuesFromAPI(command)
}


void processPutRequest_async(response, data) {
    try {
        if (response.hasError()) {
            logError "HTTP error in PUT: ${response.getErrorMessage()}"
            return
        }

        logTrace "${response.properties} - ${data} - ${response.getStatus()}"

        if (response.getStatus() == 200) {
            logDebug "Command successful: '${data}'"
        } else {
            logWarn "PUT command '${data.cmd}' returned HTTP ${response.getStatus()}"
        }
    } catch (Exception e) {
        logError "processPutRequest_async - ${e.message}"
    }
}

void callPutRequest(String command) {
    String target = "firmware/${command}?uuid=${uuid}"  // no security!  (no token required)

    Map requestParams = [
        uri: "${constVisiblairURL}/${target}",
        headers: [
            requestContentType: 'application/json',
            contentType: 'application/json'
        ],
        timeout: HTTP_TIMEOUT
    ]

    asynchttpPut("processPutRequest_async", requestParams, [cmd: command])
}

void reboot() {
    logDebug "Reboot requested"
    callPutRequest("flagRebootRequested")
}

void calibrate() {
    logDebug "Calibration requested"
    callPutRequest("flagCalibrationRequested")
}

void updateFirmware() {
    logDebug "Firmware update check requested"
    callPutRequest("flagFirmwareUpdate")
}

void resetWifiSettings() {
    logDebug "Factory reset requested"
    callPutRequest("flagFactoryReset")
}


// Logging helpers

private void logTrace(String message) {
    if (traceEnable) log.trace("${device} : ${message}")
}

private void logDebug(String message) {
    if (debugEnable) log.debug("${device} : ${message}")
}

private void logInfo(String message) {
    if (txtEnable) log.info("${device} : ${message}")
}

private void logWarn(String message) {
    log.warn("${device} : ${message}")
}

private void logError(String message) {
    log.error("${device} : ${message}")
}



/*
 * Sample response JSON
 *
 * {"uuid":"XX:XX:XX:XX:XX:XX",
    "lastSeenTimeStamp":"2022-12-11 11:59:15",
    "associatedUserID":000,
    "description":"PJ bureau",
    "tags":"",
    "tagsBadges":"",
    "readOnly":false,
    "lastSampleTimeStamp":"2022-12-11 11:59:15",
    "lastSampleCo2":"644",
    "lastSampleHumidity":"41",
    "lastSampleTemperature":"17.6",
    "lastSampleCcsEco2":"0",
    "lastSampleCcsTvoc":"0",
    "lastSampleCcsTemp":"0",
    "viewToken":"0f1bd080",
    "rebootRequested":false,
    "firmwareUpgradeRequested":false,
    "forcedFirmwareUpgradeRequested":false,
    "firmwareVersion":"1.10.0",
    "latestFirmwareVersion":"",
    "co2Offset":"0",
    "temperatureOffset":"0",
    "humidityOffset":"0",
    "calibrationRequested":false,
    "calibrationCO2Level":"415",
    "lastCalibration":"2022-06-10 11:51:27",
    "factoryResetRequested":false,
    "email":{"String":"","Valid":false},
    "tz":"America/New_York",
    "language":"fr",
    "model":"C",
    "modelVersion":"1",
    "delegateAccounts":"",
    "audibleAlertLevel":"0",
    "displayRefresh":"4",
    "sampleRate":"900",
    "temperatureUnit":"C",
    "lowCO2LimitEnabled":false
 *  }
 */

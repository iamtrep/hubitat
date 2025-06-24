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
        author: "PJ Tremblay",
        importUrl: "https://raw.githubusercontent.com/iamtrep/hubitat/refs/heads/main/drivers/visiblair/visiblair.groovy"
    ) {
        capability "Battery"
        capability "CarbonDioxideMeasurement"
        capability "Initialize"
        capability "Polling"
        capability "RelativeHumidityMeasurement"
        capability "Sensor"
        capability "TemperatureMeasurement"

        attribute "timestamp", "date" // lastSampleTimeStamp
        attribute "calibration", "date" // lastCalibration

        command "refresh"
        command "reboot"
        command "calibrate"
        command "updateFirmware"
        //command "resetWifiSettings"   // after this command, sensor will be unreachable until a WiFi network is joined.

        //command "setBatteryLevel", ["number"] // for debug purposes
        //command "arbitraryGet", ["string"] // for debug purposes
    }
}

import groovy.transform.Field

@Field static final String constCO2ClickURL = 'https://environment-monitor-01.co2.click:11000/api/v1'
@Field static final String constVisiblairURL = 'https://api.visiblair.com:11000/api/v1'

preferences {
    section("API parameters") {
        input "userid", "text", title: "CO2.Click User ID", required: true
        input "token", "text", title: "Access Token", required: true
        /* TODO: enumerate sensors and add child devices */
        input "uuid", "text", title: "Sensor UUID", required: true
        input("pollRate", "number", title: "Sensor Polling Rate (minutes)\nZero for no polling:", defaultValue:0)
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

def turnOffDebugLogging() {
    logWarn "debug logging disabled..."
    device.updateSetting("debugEnable", [value: "false", type: "bool"])
    device.updateSetting("traceEnable", [value: "false", type: "bool"])
}

void updateDeviceAttribute(String aKey, aValue, String aUnit = "", String aDescription = ""){
    sendEvent(name:aKey, value:aValue, unit:aUnit, descriptionText:aDescription)
    if (aDescription != "") logInfo(aDescription)
}

// driver methods

def initialize(){
    updateDeviceAttribute("battery", "100", "%")
    updated()
}

def updated() {
    if (debugEnable) runIn(1800, turnOffDebugLogging)

    if(pollRate == null)
        device.updateSetting("pollRate",[value:0,type:"number"])

    unschedule("refresh")
    if(pollRate > 0)
        runIn(pollRate*60,"refresh")

    refresh()

    //log.warn "debug logging is: ${debugEnable == true}"
}

void poll() {
    refresh()
}

void refresh() {
    getDeviceValuesFromAPI("sensor?uuid=${uuid}&viewToken=${token}")

    unschedule("refresh")
    if(pollRate > 0)
        runIn(pollRate*60,"refresh")
}

// for debug purposes only
def setBatteryLevel(level) {
	logDebug "setBatteryLevel(${level}) was called"
    updateDeviceAttribute("battery", level)
}

// translate JSON keys to device attributes
void refreshSensorData(retData){
    retData.each{
        unit=""

        switch (it.key){
            case "firmwareVersion":
                state.firmwareVersion = it.value
                break
            case "lastSampleTemperature":
                unit="¡C"
                /* if(useFahrenheit){
                    it.value = celsiusToFahrenheit(it.value)
                    unit = "¡F"
                } */
                updateDeviceAttribute("temperature", it.value, unit, "Temperature is ${it.value}${unit}")
                break
            case "lastSampleHumidity":
                unit="%"
                updateDeviceAttribute("humidity", it.value, unit, "Humidity is ${it.value}${unit}")
                break
            case "lastSampleCo2":
                unit="ppm"
                updateDeviceAttribute("carbonDioxide", it.value, unit, "CO2 is ${it.value}${unit}")
                break
            case "lastSamplePressure":
                unit="mBar"
                updateDeviceAttribute("pressure", it.value.Float64, unit, "Pressure is ${it.value.Float64}${unit}")
                break
            case "lastSampleBattPct":
                unit="%"
                updateDeviceAttribute("battery", it.value.Float64, unit, "Battery level is ${it.value.Float64}${unit}")
                break
            case "lastSampleTimeStamp":
                unit=""
                updateDeviceAttribute("timestamp", it.value, unit, "Timestamp of last sample is ${it.value}")
		        break
            case "lastCalibration":
                unit=""
                updateDeviceAttribute("calibration", it.value, unit)
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
                logDebug "attribute not handled : ${it.key}=${it.value}${unit}"
                break
        }
    }
}


def getDeviceValuesFromAPI (command){
 	Map requestParams =
	[
        uri: "$constVisiblairURL/$command",
        headers: [
            requestContentType: 'application/json',
		    contentType: 'application/json'
        ]
	]

    asynchttpGet("getDeviceValuesFromAPI_async", requestParams, [cmd:"${command}"])
}

def getDeviceValuesFromAPI_async(resp, data){
    try {
        logDebug "$resp.properties - $data.cmd - ${resp.getStatus()}"

        if(resp.getStatus() == 200 || resp.getStatus() == 207){
            if(resp.data) {
                cmd = data.cmd
                end = cmd.indexOf('?')
                if (end >= 0) {
                    cmd = data.cmd.substring(0,end)
                }
                if(cmd == "sensor") {
                    jsonData = (HashMap) resp.json
                    //cd = getChildDevice("${app.id}-$devId")
                    //cd.refreshSensorData(jsonData)
                    refreshSensorData(jsonData)
                } else if (cmd == "sensors/getForUser") {
                    nSensors = resp.json.size
                    logDebug "found ${nSensors} sensors for this user"
                    for (sensorJson in resp.json) {
                        sensorData = (HashMap) sensorJson
                        logTrace sensorData
                        refreshSensorData(sensorData)
                    }
                } else {
                    logWarn "Unhandled Command: '${data.cmd}'"
                }
            }
        } else if(resp.getStatus() == 401) {
            getDeviceValuesFromAPI("${data.cmd}")
        }
    } catch (Exception e) {
        logError "getDeviceValuesFromAPI_async - ${e.message}"
    }
}


def arbitraryGet(command) {
    getDeviceValuesFromAPI(command)
}


def processPutRequest_async(response, data) {
    try {
        logTrace "${response.properties} - ${data} - ${response.getStatus()}"

        if(response.getStatus() == 200){
            logDebug "Command successful: '${data}'"
        }
    } catch (Exception e) {
        logError "processPutRequest_async - ${e.message}"
    }
}

def callPutRequest(command, callback) {

    target = "firmware/${command}?uuid=${uuid}"  // no security!  (no token required)

 	Map requestParams =
	[
        uri: "$constVisiblairURL/$target",
        headers: [
            requestContentType: 'application/json',
		    contentType: 'application/json'
        ]
	]

    asynchttpPut( "processPutRequest_async", requestParams, [ cmd: "$command" ] )
}

void reboot() {
    logDebug "Reboot requested"

    callPutRequest("flagRebootRequested","processPutRequest_async")
}

void calibrate() {
    logDebug "Calibration requested"

    callPutRequest("flagCalibrationRequested","processPutRequest_async")
}

void updateFirmware() {
    logDebug "Firmware update check requested"

    callPutRequest("flagFirmwareUpdate","processPutRequest_async")
}

void resetWifiSettings() {
    logDebug "Factory reset requested"

    callPutRequest("flagFactoryReset","processPutRequest_async")
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

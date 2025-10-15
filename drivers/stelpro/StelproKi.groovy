/*
 *  Stelpro Ki ZigBee Thermostat Driver for Hubitat Elevation
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 *
 *  Forked from : https://github.com/Philippe-Charette/Hubitat-Stelpro-Ki-ZigBee-Thermostat/
 *
 *  Notice from the original author:
 *
 *     This file is a modified version of the SmartThings Device Hander, found in this repository:
 *              https://github.com/stelpro/Ki-ZigBee-Thermostat
 *
 *     Copyright 2019 Philippe Charette
 *     Copyright 2017 Stelpro
 *
 *     Author: Philippe Charette
 *     Author: Stelpro
 *
 *  Notice from the current author:
 *
 *      Updates/refactoring by iamtrep
 *      Operating state bug fix code from https://github.com/SmartThingsCommunity/SmartThingsPublic (author unknown)
 *
 */

import groovy.transform.Field

@Field static final String constCodeVersion = "0.0.5"

metadata {
    definition (
        name: "Stelpro Ki ZigBee Thermostat",
        namespace: "PhilC",
        author: "PhilC",
        importUrl: "https://raw.githubusercontent.com/iamtrep/hubitat/refs/heads/main/drivers/stelpro/StelproKi.groovy"
    ) {
        capability "Configuration"
        capability "Refresh"
        capability "TemperatureMeasurement"
        capability "Thermostat"

        command "eco"

        fingerprint profileId: "0104", endpointId: "19", inClusters: "0000, 0003, 0004, 0201, 0204", outClusters: "0402",
            manufacturer: "Stelpro", model: "STZB402+", deviceJoinName: "Stelpro Ki ZigBee Thermostat"
        fingerprint profileId: "0104", endpointId: "19", inClusters: "0000, 0003, 0004, 0201, 0204", outClusters: "0402",
            manufacturer: "Stelpro", model: "ST218", deviceJoinName: "Stelpro ORLÉANS Convector"
    }

    preferences {
        input name: "prefKeypadLockout", type: "enum", title: "Do you want to lock your thermostat's physical keypad?",
            options: ["No", "Yes"], defaultValue: "No", required: true
        input name: "txtEnable", type: "bool", title: "Enable descriptionText logging", defaultValue: true
        input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: false, required: true
        if (logEnable) {
            input name: "traceEnable", type: "bool", title: "Enable trace logging info (for development purposes)", defaultValue: false
        }
    }
}


// Constants

@Field static final constSupportedFanModes = ["\"auto\""]
@Field static final constSupportedThermostatModes = ["\"emergency heat\"", "\"heat\"", "\"off\""]

@Field static final Map constModeMap = [ "00": "off", "04": "heat", "05": "eco" ]
@Field static final Map constKeypadLockoutModes = [ "Yes": 0x01, "No": 0x00 ]

@Field static final int constReadbackDelay = 500


// Device installation

void installed() {
    // called when device is first created with this driver
}

void uninstalled() {
}

void updated() {
    // called when preferences are saved.
    Integer lockmode = constKeypadLockoutModes[prefKeypadLockout]
    if (lockmode != null) {
        logDebug("setting keypad lock mode to $lockmode (current value is ${device.currentValue("keypadLockout")})")
        List<String> cmds = []
        cmds+= zigbee.writeAttribute(0x204, 0x01, 0x30, lockmode, [:], constReadbackDelay)   // Write Lock Mode
        cmds+= zigbee.readAttribute(0x204, 0x01)
        sendZigbeeCommands(cmds)
    } else {
        logWarn "invalid lock mode ${prefKeypadLockout}"
    }

    //runIn(1800, logsOff, [overwrite: true, misfire: "ignore"])
}

void deviceTypeUpdated() {
    logWarn("device type change detected")
    configure()
}

// Capabilities

void configure() {
    logTrace("configure()")

    state.clear()
    state.lastTx = 0
    state.lastRx = 0
    state.codeVersion = constCodeVersion

    // automatically turn off debug logs after 30 minutes
    //runIn(1800,logsOff, [overwrite: true, misfire: "ignore"])

    // Set supported modes
    sendEvent(name: "supportedThermostatFanModes", value: constSupportedFanModes)
    sendEvent(name: "supportedThermostatModes", value: constSupportedThermostatModes)

    // Set unused default values (for Google Home Integration)
    sendEvent(name: "coolingSetpoint", value:getTemperature("0BB8")) // 0x0BB8 =  30 Celsius
    sendEvent(name: "thermostatFanMode", value:"auto")
    updateDataValue("lastRunningMode", "heat") // heat is the only compatible mode for this device

     //reporting: cluster, attribute, DataType, minReportInterval (s), maxReportInterval (s), minChange (int)
    List<String> cmds = []

    // From original ST driver. Binding to Thermostat cluster with endpoints reversed.   Not clear why this is needed,
    // but the bind response is successful so let's leave it in.
    cmds += "zdo bind 0x${device.deviceNetworkId} 0x01 0x19 0x201 {${device.zigbeeId}} {}"
    cmds += "delay 200"

    // Thermostat cluster
    cmds += zigbee.configureReporting(0x201, 0x0000, DataType.INT16, 10, 600, 50)                  // local temperature
    cmds += zigbee.configureReporting(0x201, 0x0008, DataType.UINT8, 10, 900, 5)                   // PI heating demand
    cmds += zigbee.configureReporting(0x201, 0x0012, DataType.INT16, 1, 0, 50)                     // occupied heat setpoint
    cmds += zigbee.configureReporting(0x201, 0x001C, DataType.ENUM8, 1, 0)                      // system mode
    cmds += zigbee.configureReporting(0x201, 0x401C, DataType.ENUM8, 1, 0, null, [mfgCode: "0x1185"]) // manufacturer specific setpoint mode

    // Thermostat UI Config cluster
    cmds += zigbee.configureReporting(0x204, 0x0000, DataType.ENUM8, 1, 0)                         // temperature display mode
    cmds += zigbee.configureReporting(0x204, 0x0001, DataType.ENUM8, 1, 0)                         // keypad lockout

    logTrace("${cmds}")
    sendZigbeeCommands(cmds) // Submit zigbee commands

    refresh()
}

void refresh() {
    logDebug "refresh()"

    List<String> cmds = []

    cmds += zigbee.readAttribute(0x201, 0x0000)                      // Local Temperature
    cmds += zigbee.readAttribute(0x201, 0x0008)                      // PI Heating State
    cmds += zigbee.readAttribute(0x201, 0x0012)                      // Heat Setpoint
    cmds += zigbee.readAttribute(0x201, 0x001C)                      // System Mode
    cmds += zigbee.readAttribute(0x201, 0x401C, [mfgCode: "0x1185"]) // Manufacturer-specific System Mode

    cmds += zigbee.readAttribute(0x204, 0x0000)                      // Temperature Display Mode
    cmds += zigbee.readAttribute(0x204, 0x0001)                      // Keypad Lockout

    sendZigbeeCommands(cmds) // Submit zigbee commands
}

void off() {
    logDebug("thermostat commanded off (current value is ${device.currentValue("thermostatMode")})")
    List<String> cmds = []
    cmds += zigbee.writeAttribute(0x201, 0x001C, 0x30, 0, [:], constReadbackDelay)
    cmds += zigbee.readAttribute(0x201, 0x001C)
    sendZigbeeCommands(cmds)
}

void heat() {
    logDebug("thermostat commanded to heat (current value is ${device.currentValue("thermostatMode")})")
    List<String> cmds = []
    cmds += zigbee.writeAttribute(0x201, 0x001C, 0x30, 04, [:], 1000) // MODE
    cmds += zigbee.writeAttribute(0x201, 0x401C, 0x30, 04, [mfgCode: "0x1185"], constReadbackDelay) // SETPOINT MODE
    cmds += zigbee.readAttribute(0x201, 0x001C, [:], constReadbackDelay)
    cmds += zigbee.readAttribute(0x201, 0x401C, [mfgCode: "0x1185"])
    sendZigbeeCommands(cmds)
}

void eco() {
    logDebug("thermostat commanded to eco (current value is ${device.currentValue("thermostatMode")})")
    List<String> cmds = []
    cmds += zigbee.writeAttribute(0x201, 0x001C, 0x30, 04, [:], 1000) // MODE
    cmds += zigbee.writeAttribute(0x201, 0x401C, 0x30, 05, [mfgCode: "0x1185"], constReadbackDelay) // SETPOINT MODE
    cmds += zigbee.readAttribute(0x201, 0x001C, [:], constReadbackDelay)
    cmds += zigbee.readAttribute(0x201, 0x401C, [mfgCode: "0x1185"])
    sendZigbeeCommands(cmds)
}

void cool() {
    logWarn "cool mode is not available for this device. => Defaulting to off mode instead."
    off()
}

void auto() {
    logWarn "auto mode is not available for this device. => Defaulting to heat mode instead."
    heat()
}

void emergencyHeat() {
    eco()
}

void fanAuto() {
    logWarn "fanAuto mode is not available for this device"
}

void fanCirculate(){
    logWarn "fanCirculate mode is not available for this device"
}

void fanOn(){
    logWarn "fanOn mode is not available for this device"
}

void setSchedule(JSON_OBJECT){
    logWarn "setSchedule is not available for this device"
}

void setThermostatFanMode(fanmode){
    logWarn "setThermostatFanMode is not available for this device"
}

void setHeatingSetpoint(Integer preciseDegrees) {
    setHeatingSetpoint(new BigDecimal(preciseDegrees))
}

void setHeatingSetpoint(BigDecimal preciseDegrees) {
    if (preciseDegrees == null) {
        logError("setHeatingSetpoint() called with null value")
        return
    }

    logDebug "setHeatingSetpoint(${preciseDegrees})"
    String temperatureScale = getTemperatureScale()
    BigDecimal degrees = preciseDegrees.setScale(1, BigDecimal.ROUND_HALF_UP)

    logDebug "setHeatingSetpoint(${degrees} ${temperatureScale}) - current value is ${device.currentValue("heatingSetpoint")}"

    Float celsius = (temperatureScale == "C") ? degrees as Float : (fahrenheitToCelsius(degrees) as Float).round(2)
    int celsius100 = Math.round(celsius * 100)

    List<String> cmds = []
    cmds += zigbee.writeAttribute(0x201, 0x0012, 0x29, celsius100, [:], constReadbackDelay) // Write Heat Setpoint
    cmds += zigbee.readAttribute(0x201, 0x0012)
    sendZigbeeCommands(cmds)
}

void setCoolingSetpoint(degrees) {
    logWarn "setCoolingSetpoint is not available for this device"
}

void setThermostatMode(String value) {
    switch (value) {
        case "heat":
        case "auto":
            heat()
            break

        case "emergency heat":
        case "eco":
            eco()
            break

        case "cool":
        case "off":
        default:
            off()
            break
    }
}

// Event parsing

List parse(String description) {
    if (state.codeVersion != constCodeVersion) {
        state.codeVersion = constCodeVersion
        runInMillis 1500, 'autoConfigure'
    }

    state.lastRx = now()

    Map descMap = zigbee.parseDescriptionAsMap(description)
    logTrace("parse() - description = ${descMap}")

    List result = []

    if (descMap.attrId != null) {
        // device attribute report
        result += parseAttributeReport(descMap)
        if (descMap.additionalAttrs) {
            def mapAdditionnalAttrs = descMap.additionalAttrs
            mapAdditionnalAttrs.each{add ->
                add.cluster = descMap.cluster
                result += parseAttributeReport(add)
            }
        }
    } else if (descMap.profileId == "0000") {
        // ZigBee Device Object (ZDO) command
        logTrace("Unhandled ZDO command: cluster=${descMap.clusterId} command=${descMap.command} value=${descMap.value} data=${descMap.data}")
    } else if (descMap.profileId == "0104" && descMap.clusterId != null) {
        // ZigBee Home Automation (ZHA) global command
        if (descMap.command == "04" || descMap.command == "07") {
            parseResponse(descMap)
        } else {
            logTrace("Unhandled ZHA global command: cluster=${descMap.clusterId} command=${descMap.command} value=${descMap.value} data=${descMap.data}")
        }
    } else if (description?.startsWith('enroll request')) {
        logDebug "Received enroll request"
    } else if (description?.startsWith('zone status')  || description?.startsWith('zone report')) {
        logDebug "Zone status: $description"
    } else {
        logWarn("Unhandled unknown command: cluster=${descMap.clusterId} command=${descMap.command} value=${descMap.value} data=${descMap.data}")
    }

    return result
}

private Map parseAttributeReport(Map descMap) {
    Map map = [:]

    // Main switch over all available cluster IDs
    //
    // fingerprint : inClusters: "0000, 0003, 0004, 0201, 0204"
    //
    switch (descMap.cluster) {
        case "0000": // Basic cluster
        case "0003": // Identify cluster
        case "0004": // Groups cluster
			break

        case "0201":
            // Thermostat cluster
            switch (descMap.attrId) {
                case "0000":
                    map.name = "temperature"
                    switch (descMap.value) {
                        case "7FFD":
                            map.value = "low"
                            break
                        case "7FFF":
                            map.value = "high"
                            break
                        case "8000":
                            map.value = "--"
                            break
                        default:
                            if (map.value > "8000") {
                                map.value = -(Math.round(2*(655.36 - map.value))/2)
                            } else {
                                map.value = getTemperature(descMap.value)
                                state.rawTemp = map.value
                            }
                            break
                    }
                    map.unit = getTemperatureScale()
                    map.descriptionText = "Temperature is ${map.value}${map.unit}"
                    handleOperatingStateBugFix()
                    break

                case "0008": // PI heat demand
                    map.name = "thermostatOperatingState"
                    map.value = constModeMap[descMap.value]
                    if (descMap.value < "10") {
                        map.value = "idle"
                    }
                    else {
                        map.value = "heating"
                    }
                    map = validateOperatingStateBugFix(map)
				    // Check to see if this was changed, if so make sure we have the correct heating setpoint
				    if (map.data?.correctedValue) {
                        List<String> cmds = []
                        cmds += zigbee.readAttribute(0x0201, 0x0012)
					    sendZigbeeCommands(cmds)
				    }
                    map.descriptionText = "Operating state set to ${map.value}"
                    break

                case "0012": // heating setpoint
                    map.name = "heatingSetpoint"
                    map.value = getTemperature(descMap.value)
                    state.rawSetpoint = map.value
                    if (descMap.value == "8000") {      //0x8000
                        map.value = getTemperature("01F4")  // 5 Celsius (minimum setpoint)
                    }
                    map.unit = getTemperatureScale()
                    map.descriptionText = "Heating setpoint is ${map.value}${map.unit}"
                    sendEvent(name:"thermostatSetpoint", value:map.value, unit:map.unit, descriptionText:map.descriptionText)
                    handleOperatingStateBugFix()
                    break

                case "001C": // mode
                    if (descMap.value == "04") {
                        logDebug "descMap.value == \"04\". Ignore and wait for SETPOINT MODE"
                        //return null // TODO why?
                    }
                    map.name = "thermostatMode"
                    map.value = constModeMap[descMap.value]
                    map.descriptionText = "Thermostat mode is set to ${map.value}"
                    break

                case "401C": // setpoint mode
                    if (descMap.value == "00") {
                        logDebug "descMap.value == \"00\". Ignore and wait for MODE"
                        //return null // TODO why?
                    }
                    map.name = "thermostatMode"
                    map.value = constModeMap[descMap.value]
                    map.descriptionText = "Thermostat mode is set to ${map.value}"
                    break

                default:
                    logTrace "Unhandled attribute ${descMap.attrId} with value ${descMap.value}"
                    break
            }
            break

        case "0204": // Thermostat UI config cluster
            switch (descMap.attrId) {
                case "0000":  // TemperatureDisplayMode
                    map.name = "temperatureDisplayMode"
                    map.value = descMap.attrId  // don't know the mapping, sorry
                    map.descriptionText = "Temperature display mode is set to ${map.value}"
                    break

                case "0001":  // KeypadLockout
                    map.name = "keypadLockout"
                    map.value = descMap.value == "00" ? "unlocked" : "locked"
                    map.descriptionText = "Thermostat keypad lockout set to ${map.value}"
                    break

                default:
                    logTrace "Unhandled thermostat UI config attribute ${descMap.attrId} with value ${descMap.value}"
                    break
            }
            break

        default:
            logTrace "Unhandled attribute report : cluster=${descMap.cluster} attrId=${descMap.attrId} value=${descMap.value}"
            break
    }

    Map result = null

    if (map) {
        result = createEvent(map)
        if (map.descriptionText && txtEnable) logInfo("${map.descriptionText}")
        logDebug("event created: ${result}")
    } else {
        logTrace("Unhandled attribute report - cluster ${descMap.cluster} attribute ${descMap.attrId} value ${descMap.value}")
    }

    return result
}

void parseResponse(Map descMap) {
    switch (descMap.clusterInt) {
        case 0x0201:
        	logDebug("Received response for Thermostat cluster (${descMap.data}) ${descMap}")
            break

        case 0x0204:
        	logDebug("Received response for Thermostat UI cluster (${descMap.data}) ${descMap}")
            break

        default:
            logTrace("Unhandled response: ${descMap}")
            break
    }
}

// Callback helpers

void logsOff() {
    logInfo "debug logging disabled..."
    device.updateSetting("logEnable",[value:"false",type:"bool"])
    device.updateSetting("traceEnable",[value:"false",type:"bool"])
}

// Private methods

private void autoConfigure() {
    logWarn "Detected driver version change"
    configure()
}

private void sendZigbeeCommands(List<String> cmds) {
    logTrace "Sending Zigbee messages ➡️ device: ${send}"
    state.lastTx = now()
    sendHubCommand(new hubitat.device.HubMultiAction(cmds, hubitat.device.Protocol.ZIGBEE))
}

private BigDecimal getTemperature(String value) {
    try {
        BigDecimal celsius = new BigDecimal(Integer.parseInt(value, 16)) / 100

        if (getTemperatureScale() == "C") {
            return celsius.setScale(1, BigDecimal.ROUND_HALF_UP)
        }

        BigDecimal fahrenheit = celsiusToFahrenheit(celsius)
        return fahrenheit.setScale(1, BigDecimal.ROUND_HALF_UP)
    } catch (Exception e) {
        log.error "getTemperature: Cannot parse '${value}' as hex", e
        return null
    }
}

// Due to a bug in this model's firmware, sometimes we don't get
// an updated operating state; so we need some special logic to verify the accuracy.
// TODO: Add firmware version check when change versions are known
// The logic between these two functions works as follows:
//   In temperature and heatingSetpoint events check to see if we might need to request
//   the current operating state and request it with handleOperatingStateBugfix.
//
//   In operatingState events validate the data we received from the thermostat with
//   the current environment, adjust as needed. If we had to make an adjustment, then ask
//   for the setpoint again just to make sure we didn't miss data somewhere.
//
// There is a risk of false positives where we receive a new valid operating state before the
// new setpoint, so we basically toss it. When we come to receiving the setpoint or temperature
// (temperature roughly every minute) then we should catch the problem and request an update.
// I think this is a little easier than outright managing the operating state ourselves.
// All comparisons are made using the raw integer from the thermostat (unrounded Celsius decimal * 100)
// that is stored in temperature and setpoint events.

/**
 * Check if we should request the operating state, and request it if so
 */
private void handleOperatingStateBugFix() {
    if (state.rawSetpoint == null || state.rawTemp == null || device.currentValue("thermostatMode") == "off")
        return

    String currOpState = device.currentValue("thermostatOperatingState")
    List<String> cmds = []
    cmds += zigbee.readAttribute(0x0204, 0x0008) // PI heat demand

    if (state.rawSetpoint <= state.rawTemp) {
        if (currOpState != "idle") {
            //logWarn "handleOperatingStateBugFix sending readAttribute command on 0x0204"
            sendZigbeeCommands(cmds)
        }
    } else {  // state.rawSetpoint > state.rawTemp
        if (currOpState != "heating") {
            //logWarn "handleOperatingStateBugFix sending readAttribute command on 0x0204"
            sendZigbeeCommands(cmds)
        }
    }
}

/**
 * Given an operating state event, check its validity against the current environment
 * @param map An operating state to validate
 * @return The passed map if valid, or a corrected map and a new param data.correctedValue if invalid
 */
private Map validateOperatingStateBugFix(Map map) {
    // If we don't have historical data, we will take the value we get,
    // otherwise validate if the difference is > 1
    if (state.rawSetpoint != null && state.rawTemp != null) {
        String oldVal = map.value

        if (state.rawSetpoint <= state.rawTemp || device.currentValue("thermostatMode") == "off") {
            map.value = "idle"
        } else {
            map.value = "heating"
        }

        // Indicate that we have made a change
        if (map.value != oldVal) {
            map.data = [correctedValue: true]
        }
    }

    return map
}

// Logging helpers

private void logTrace(String message) {
    // No trace facility.  Use debug.
    if (traceEnable) log.debug("${device} : ${message}")
}

private void logDebug(String message) {
    if (logEnable) log.debug("${device} : ${message}")
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

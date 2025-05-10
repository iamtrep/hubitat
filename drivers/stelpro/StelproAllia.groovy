/**
 *  Copyright 2022 Maxime Boissonneault
 *  Copyright 2020 Philippe Charette
 *  Copyright 2018 Stelpro
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
 *  Stelpro Allia Thermostat driver for Hubitat Elevtion
 *
 *  Notice: The code in this driver was initially derived from Maxime Boissonault's code found here:
 *          https://github.com/mboisson/Hubitat-Stelpro-Allia-Thermostat
 *
 *          which is itself a derivative of:
 *             https://github.com/Philippe-Charette/Hubitat-Stelpro-Maestro-Thermostat
 *          which itself was derived from:
 *             https://github.com/stelpro/maestro-thermostat
 *
 *  Author: Maxime Boissonneault
 *  Author: Philippe Charette
 *  Author: Stelpro
 *
 */

metadata {
    definition (name: "Stelpro Allia Zigbee Thermostat",
                namespace: "iamtrep",
                author: "pj",
                importUrl: "https://raw.githubusercontent.com/iamtrep/hubitat/refs/heads/main/drivers/stelpro/StelproAllia.groovy"
    ) {
        capability 'Actuator'
        capability 'Configuration'
        capability 'EnergyMeter'
        capability 'Initialize'
        capability 'Refresh'
        capability 'PowerMeter'
        capability 'TemperatureMeasurement'
        capability 'Thermostat'

        attribute "temperature_display_mode", "string"
        attribute "keypad_lockout", "string"
        attribute "outdoor_temperature", "number"

        command 'setKeypadLockoutMode', [ [name: "lockoutMode*",
                                           type: "ENUM",
                                           description: "Lock/unlock the thermostat's keypad",
                                           constraints:["lock","unlock"]] ]
        command "setOutdoorTemperature", [[name:"Temperature", type:"NUMBER", description: "Set the outdoor temperature display to this value"]]

        command "increaseHeatSetpoint"
        command "decreaseHeatSetpoint"

        fingerprint profileId: "0104", endpointId: "19", inClusters: "0000,0003,0004,0201,0204", outClusters: "0003,000A,0402", manufacturer: 'Stello', model: 'HT402', deviceJoinName: '(Stelpro Allia) Hilo HT402 Thermostat'
    }

    preferences {
        input name: "tempChange", type: "number", title: "Temperature change", description: "Minimum temperature reading change to trigger report in Celsius/100, 10..200", range: "10..200", defaultValue: 100
        input name: "powerReport", type: "number", title: "Power change", description: "Minimum wattage difference to trigger power report (1..*)",  range: "1..*", defaultValue: 30
        input name: "reportingSeconds", type: "enum", title: "Status reporting", description: "Maximum time between status reports, even when no change", options:[0: "never", 60: "1 minute", 600:"10 minutes", 3600:"1 hour", 21600:"6 hours", 43200:"12 hours", 86400:"24 hours"], defaultValue: 21600, multiple: false, required: true
        input name: 'refreshScheduleHeating', type: 'enum', title: '<b>Refresh Interval while heating</b>', options: constRefreshIntervalOpts.options, defaultValue: constRefreshIntervalOpts.defaultValueHeating, description:\
            '<i>Changes how often the hub calls a refresh while the thermostat is in heating mode.</i>'
        input name: 'refreshScheduleIdle', type: 'enum', title: '<b>Refresh Interval while idle</b>', options: constRefreshIntervalOpts.options, defaultValue: constRefreshIntervalOpts.defaultValueIdle, description:\
            '<i>Changes how often the hub calls a refresh while the thermostat is in idle mode.</i>'

        input name: 'infoEnable', type: 'bool', title: 'Enable info level logging', defaultValue: true
        input name: 'debugEnable', type: 'bool', title: 'Enable debug level logging', defaultValue: true //false
        input name: 'traceEnable', type: 'bool', title: 'Enable trace level logging', description: "For driver development", defaultValue: false
    }
}

import groovy.transform.Field
import groovy.json.JsonOutput

@Field static final Map constRefreshIntervalOpts = [
    defaultValueIdle: 00,
    defaultValueHeating: 00,
    options: [ 1: 'Every 1 Min', 5: 'Every 5 Mins', 10: 'Every 10 Mins', 15: 'Every 15 Mins', 59: 'Every Hour', 00: 'Disabled' ]
]


@Field static final Map constThermostatModes = [ "00":"off", "04":"heat", "05":"eco" ]
@Field static final Map constFanModes = [ "00":"off" ]
@Field static final Map constTempDisplayModes = [ "00":"C", "01":"F" ]
@Field static final Map constKeypadLockoutMap = [ "00":"unlocked", "01":"locked" ]

@Field static final Integer constHeatOffSetpoint = 5


// Install/Configure/Refresh

def installed() {
    logInfo('installed()')
    configure()
    refresh() // TODO
}

def initialize() {
    logInfo('initialize()')
    refresh()
}

def updated() {
    logInfo('updated()')
    configure()
}

def uninstalled() {
    logInfo('uninstalled()')
}

def configure(){
    log.warn "configure..."
    unschedule()
    runIn(1800,debugLogsOff)

    // Configure Default values if null
    if (tempChange == null)
        tempChange = 100 as int
    if (powerReport == null)
        powerReport = 30 as int
    if (reportingSeconds == null)
        reportingSeconds = "60"


    // Set unused default values (for Google Home Integration)
    sendEvent(name: "coolingSetpoint", value:getTemperature("0BB8")) // 0x0BB8 =  30 Celsius
    sendEvent(name: "thermostatFanMode", value:"auto")
	sendEvent(name: "supportedThermostatFanModes", value: JsonOutput.toJson(["auto"]), descriptionText: getDescriptionText("supportedThermostatFanModes set to ${fanModes}"))
	sendEvent(name: "supportedThermostatModes", value: JsonOutput.toJson(["heat", "off"]), descriptionText: getDescriptionText("supportedThermostatModes set to ${modes}"))
    setThermostatMode("heat")

    def cmds = []

    //bindings
    cmds += "zdo bind 0x${device.deviceNetworkId} 1 0x019 0x201 {${device.zigbeeId}} {}" // TODO: why is this needed?
    cmds += "delay 200"

    //reporting
    cmds += zigbee.configureReporting(0x201, 0x0000, 0x29, 0, (int)reportingSeconds, (int) tempChange)   //Attribute ID 0x0000 = local temperature, Data Type: S16BIT
    cmds += zigbee.configureReporting(0x201, 0x0008, 0x20, 0, (int)reportingSeconds, 5)   //Attribute ID 0x0008 = pi heating demand, Data Type: U8BIT
    cmds += zigbee.configureReporting(0x201, 0x0012, 0x29, 0, (int)reportingSeconds, 1)     //Attribute ID 0x0012 = occupied heat setpoint, Data Type: S16BIT
    cmds += zigbee.configureReporting(0x201, 0x4008, 0x29, 0, (int)reportingSeconds, (int) powerReport)     //Attribute ID 0x4008 = power usage, Data Type: S16BIT
    cmds += zigbee.configureReporting(0x201, 0x4009, 0x29, 0, (int)reportingSeconds, 10)     //Attribute ID 0x4009 = energy usage, Data Type: S16BIT

    cmds += zigbee.configureReporting(0x204, 0x0000, 0x30, 0, (int)reportingSeconds)         //Attribute ID 0x0000 = temperature display mode, Data Type: 8 bits enum
    cmds += zigbee.configureReporting(0x204, 0x0001, 0x30, 0, (int)reportingSeconds)         //Attribute ID 0x0001 = keypad lockout, Data Type: 8 bits enum

    logTrace "cmds:${cmds}"
    return cmds + refresh()
}

def refresh() {
    log.info("refresh")
    def cmds = []

    cmds += zigbee.readAttribute(0x201, 0x0000) // Local Temperature
    cmds += zigbee.readAttribute(0x201, 0x0008) // PI Heating State
    cmds += zigbee.readAttribute(0x201, 0x0012) // Heat Setpoint


    // missing a few vendor-specific attributes (see https://www.zigbee2mqtt.io/devices/HT402.html)
    cmds += zigbee.readAttribute(0x201, 0x4001) // Outdoor temperature
//    cmds += zigbee.readAttribute(0x201, 0x4002) // unknown vendor-specific
//    cmds += zigbee.readAttribute(0x201, 0x4004) // unknown vendor-specific
    cmds += zigbee.readAttribute(0x201, 0x4008) // power
    cmds += zigbee.readAttribute(0x201, 0x4009) // energy

    cmds += zigbee.readAttribute(0x204, 0x0000) // Temperature Display Mode
    cmds += zigbee.readAttribute(0x204, 0x0001) // Keypad Lockout

    logTrace "refresh cmds:${cmds}"
    return cmds
}

// Capabilities

def auto() {
    logWarn('auto(): mode is not available for this device. => Defaulting to heat mode instead.')
    //heat()
}

def cool() {
    logWarn('cool(): mode is not available for this device. => Defaulting to heat mode instead.')
    //heat()
}

def emergencyHeat() {
    logWarn('emergencyHeat(): mode is not available for this device. => Defaulting to heat mode instead.')
    //heat()
}

def fanAuto() {
    logWarn('fanAuto(): mode is not available for this device')
}

def fanCirculate() {
    logWarn('fanCirculate(): mode is not available for this device')
}

def fanOn() {
    logWarn('fanOn(): mode is not available for this device')
}

def setCoolingSetpoint(degrees) {
    logWarn("setCoolingSetpoint(${degrees}): is not available for this device")
}


def heat() {
    // This model does not appear to honor cluster 0x0201 attribute 0x001C for setting the system mode.
    setThermostatMode("heat")
    Integer setpoint = state.lastHeatingSetpoint ?: device.currentValue("temperature") as int
    logDebug("heat() setpoint will be ${setpoint}")
    setHeatingSetpoint(setpoint)
}

def eco() {
    logWarn("eco mode is not available for this device")
}

def off() {
    // This model does not appear to honor cluster 0x0201 attribute 0x001C for setting the system mode.
    setThermostatMode("off")
    setHeatingSetpoint(constHeatOffSetpoint)
}


def setThermostatMode(String thermostatMode) {
    switch (thermostatMode) {
        case "heat":
        case "off":
            sendEvent(name:"thermostatMode", value:thermostatMode, descriptionText: "${device.displayName} thermostat mode set to ${thermostatMode}")
            break

        default:
            logWarn("invalid thermostat mode requested (${thermostatMode})")
            break
    }
}

def setHeatingSetpoint(preciseDegrees) {
    if (preciseDegrees != null) {

        def temperatureScale = getTemperatureScale()
        def degrees = new BigDecimal(preciseDegrees).setScale(1, BigDecimal.ROUND_HALF_UP)

        logDebug "setHeatingSetpoint(${degrees} ${temperatureScale})"

        def celsius = (temperatureScale == "C") ? degrees as Float : (fahrenheitToCelsius(degrees) as Float).round(2)

        if (device.currentValue("thermostatMode") == "heat") {
            cmds = []
            cmds += zigbee.writeAttribute(0x201, 0x0012, 0x29, Math.round(celsius * 100) as int)
            sendZigbeeCommands(cmds)
        } else {
            // remember it, will be used when heat mode is turned back on.
            state.lastHeatingSetpoint = celsius
        }
    }
}

// Custom commands

def increaseHeatSetpoint() {
    def currentSetpoint = device.currentValue("heatingSetpoint")
    def locationScale = getTemperatureScale()
    def maxSetpoint
    def step

    if (locationScale == "C") {
        maxSetpoint = 30;
        step = 0.5
    }
    else {
        maxSetpoint = 86
        step = 1
    }

    if (currentSetpoint < maxSetpoint) {
        currentSetpoint = currentSetpoint + step
        setHeatingSetpoint(currentSetpoint)
    }
}

def decreaseHeatSetpoint() {
    def currentSetpoint = device.currentValue("heatingSetpoint")
    def locationScale = getTemperatureScale()
    def minSetpoint
    def step

    if (locationScale == "C") {
        minSetpoint = 5;
        step = 0.5
    }
    else {
        minSetpoint = 41
        step = 1
    }

    if (currentSetpoint > minSetpoint) {
        currentSetpoint = currentSetpoint - step
        setHeatingSetpoint(currentSetpoint)
    }
}


def setKeypadLockoutMode(lockoutMode) {
    def cmds = []

    switch (lockoutMode) {
        case 'lock':
            cmds += zigbee.writeAttribute(0x0204, 0x0001, 0x30, 0x01)
            break

        case 'unlock':
            cmds += zigbee.writeAttribute(0x0204, 0x0001, 0x30, 0x00)
            break

        default:
            logError("Invalid keypad lockout request ${lockoutMode}")
            return
    }

    sendZigbeeCommands(cmds)
}



def setOutdoorTemperature(preciseDegrees) {
    if (preciseDegrees != null) {
        def temperatureScale = getTemperatureScale()
        def degrees = new BigDecimal(preciseDegrees).setScale(1, BigDecimal.ROUND_HALF_UP)

        logDebug "setOutdoorTemperature(${degrees} ${temperatureScale})"

        def celsius = (temperatureScale == "C") ? degrees as Float : (fahrenheitToCelsius(degrees) as Float).round(2)
        int celsius100 = Math.round(celsius * 100)

        cmds = []
        cmds += zigbee.writeAttribute(0x201, 0x4001, 0x29, celsius100) //, ["mfgCode": "0x1185"])
        sendZigbeeCommands(cmds)
    }
}

// Zigbee message parsing

def parse(String description) {
    def descMap = zigbee.parseDescriptionAsMap(description)
    logTrace("parse() - description = ${descMap}")

    def result = []

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
        logTrace("Unhandled ZHA global command: cluster=${descMap.clusterId} command=${descMap.command} value=${descMap.value} data=${descMap.data}")
    } else if (description?.startsWith('enroll request')) {
        logDebug "Received enroll request"
    } else if (description?.startsWith('zone status')  || description?.startsWith('zone report')) {
        logDebug "Zone status: $description"
    } else {
        logWarn("Unhandled unknown command ($description): cluster=${descMap.clusterId} command=${descMap.command} value=${descMap.value} data=${descMap.data}")
    }

    return result
}

private parseAttributeReport(descMap) {
    def map = [:]

    // inClusters: "0000,0003,0004,0201,0204"

    switch (descMap.cluster) {
        case 0x0000: // Basic cluster
        case 0x0003: // Identify cluster
        case 0x0004: // Groups cluster
            break

        case "0201":
            switch (descMap.attrId) {
                case "0000":
                    map.name = "temperature"
                    map.value = getTemperature(descMap.value)
                    if (descMap.value == "7FFD") {       //0x7FFD
                        map.value = "low"
                    }
                    else if (descMap.value == "7FFF") {  //0x7FFF
                        map.value = "high"
                    }
                    else if (descMap.value == "8000") {  //0x8000
                        map.value = "--"
                    }
                    else if (descMap.value > "8000") {
                        map.value = -(Math.round(2*(655.36 - map.value))/2)
                    }
                    map.unit = getTemperatureScale()
                    map.descriptionText = "${device.displayName} temperature is ${map.value}${map.unit}"
                    break

                case "0008":
                    map.name = "thermostatOperatingState"
                    //map.value = constThermostatModes[descMap.value]
                    if (descMap.value < "10") {
                        map.value = "idle"
                        final int interval = (settings.refreshScheduleIdle as Integer) ?: 0
                        if (interval > 0 && map.value != device.currentValue("thermostatOperatingState")) {
                            log.info "${device} scheduling refresh every ${interval} minutes"
                            scheduleRefresh(interval)
                            runIn(5, 'refresh')
                        }
                    } else {
                        map.value = "heating"
                        final int interval = (settings.refreshScheduleHeating as Integer) ?: 0
                        if (interval > 0 && map.value != device.currentValue("thermostatOperatingState")) {
                            log.info "${device} scheduling refresh every ${interval} minutes"
                            scheduleRefresh(interval)
                            runIn(5, 'refresh')
                        }
                    }
                    map.descriptionText = "${device.displayName} operating state is ${map.value}"
                    break

                case "0012":
                    if (getTemperature(descMap.value) != constHeatOffSetpoint) {
                        map.name = "heatingSetpoint"
                        if (descMap.value == "8000") {        //0x8000  TODO
                            map.value = getTemperature("01F4")  // 5 Celsius (minimum setpoint)
                        } else {
                            map.value = getTemperature(descMap.value)
                        }
                        map.unit = getTemperatureScale()
                        map.descriptionText = "${device.displayName} heating setpoint is ${map.value}${map.unit}"

                        // also send separate thermostatSetpoint event
                        sendEvent(name:"thermostatSetpoint", value:map.value, unit:map.unit, descriptionText: map.descriptionText)

                        // remember last heating setpoint
                        state.lastHeatingSetpoint = map.value
                    }
                    break

                case "001C": // system mode - not used on this model
                    break

                case "4001":
                    map.name = "outdoor_temperature"
                    map.value = getTemperature(descMap.value)
                    if (map.value > 100) {
                        map.value = map.value - 655.36 // handle negative temperatures
                    }
                    map.unit = getTemperatureScale()
                    map.descriptionText = "${device.displayName} outdoor temperature is ${map.value}${map.unit}"
                    break

                case "4002":
                case "4004":
                case "4006":
                    logDebug "Unknown vendor-specific attribute report 0x4004: ${descMap}"
                    break

                case "4008":
                    map.name = "power"
                    map.value = getPower(descMap.value)
                    map.unit = 'W'
                    map.descriptionText = "${device.displayName} power is ${map.value}${map.unit}"
                    break

                case "4009":
                    map.name = "energy"
                    map.value = getEnergy(descMap.value)
                    map.unit = "Wh"
                    map.descriptionText = "${device.displayName} energy delivered is ${map.value}${map.unit}"
                    break

                case "401C": // vendor-specific system mode - not used on this unit
                    break

                default:
                    logTrace("Unhandled thermostat cluster attribute report - attribute ${descMap.attrId} value ${descMap.value}")
                    break
            }
            break

        case "0204":
            switch (descMap.attrId) {
                case "0000":
                    map.name = "temperature_display_mode"
                    map.value = constTempDisplayModes[descMap.value]
                    map.descriptionText = "${device.displayName} temperature display mode is ${map.value}"
                    break

                case "0001":
                    map.name = "keypad_lockout"
                    map.value = constKeypadLockoutMap[descMap.value]
                    map.descriptionText = "${device.displayName} keypad lockout state is ${map.value}"
                    break

                default:
                    logTrace("Unhandled thermostat control attribute report - attribute ${descMap.attrId} value ${descMap.value}")
                    break
            }
            break

        default:
            logTrace("Unhandled attribute report - cluster ${descMap.cluster} attribute ${descMap.attrId} value ${descMap.value}")
            break
    }

    def result = null

    if (map) {
        if (map.descriptionText) log.info("${map.descriptionText}")
        result = createEvent(map)
    } else {
        logDebug("Unhandled attribute report - cluster ${descMap.cluster} attribute ${descMap.attrId} value ${descMap.value}")
        logTrace(descMap)
    }

    return result
}

// private methods

private String getDescriptionText(String msg) {
    logInfo(msg)
    return "${device.displayName} : ${msg}"
}

/**
 * Schedule a refresh
 * @param intervalMin interval in minutes
 */
private void scheduleRefresh(final int intervalMin) {
    final Random rnd = new Random()
    unschedule('refresh')
    log.info "${rnd.nextInt(59)} ${rnd.nextInt(intervalMin)}-59/${intervalMin} * ? * * *"
    schedule("${rnd.nextInt(59)} ${rnd.nextInt(intervalMin)}-59/${intervalMin} * ? * * *", 'refresh')
}

private Integer getPower(String value)
{
    if (value != null) {
        logTrace("getPower: value $value")
        return Integer.parseInt(value, 16)
    }
}

private Integer getEnergy(String value)
{
    if (value != null) {
        logTrace("getEnergy: value $value")
        return Integer.parseInt(value, 16)/1000
    }
}

private Integer getTemperature(String value) {
    if (value != null) {
        logTrace("getTemperature: value $value")
        def celsius = Integer.parseInt(value, 16) / 100
        if (getTemperatureScale() == "C") {
            return celsius
        }

        return Math.round(celsiusToFahrenheit(celsius))
    }
}

private void sendZigbeeCommands(cmds) {
    def hubAction = new hubitat.device.HubMultiAction(cmds, hubitat.device.Protocol.ZIGBEE)
    sendHubCommand(hubAction)
}

// logging helpers

private void logTrace(message) {
    if (traceEnable) log.trace("${device} : ${message}")
}

private logDebug(message) {
    if (debugEnable) log.debug("${device.displayName} : ${message}")
}

private logInfo(message) {
    if (infoEnable) log.info("${device.displayName} : ${message}")
}

private logWarn(message) {
    log.warn("${device.displayName} : ${message}")
}

private logError(message) {
    log.error("${device.displayName} : ${message}")
}

void debugLogsOff() {
    if (debugEnable) {
        log.debug "debug logging disabled..."
        device.updateSetting("debugEnable",[value:"false",type:"bool"])
    }
    if (traceEnable) {
        log.debug "trace logging disabled..."
        device.updateSetting("traceEnable",[value:"false",type:"bool"])
    }
}

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
 *  Stelpro Allia/Stello Hilo HT402 Thermostat driver for Hubitat Elevation
 *
 *  https://www.stelpro.com/wp-content/uploads/produits/documents/INS/INS_SAT402ZB_EN.pdf
 *
 *  Notice: some of the code in this driver was initially derived from code found here:
 *             https://github.com/mboisson/Hubitat-Stelpro-Allia-Thermostat
 *             Author: Maxime Boissonneault
 *          which is itself a derivative of:
 *             https://github.com/Philippe-Charette/Hubitat-Stelpro-Maestro-Thermostat
 *             Author: Philippe Charette
 *          which itself was derived from:
 *             https://github.com/stelpro/maestro-thermostat
 *             Author: Stelpro
 *
 */

import java.math.RoundingMode
import groovy.transform.CompileStatic
import groovy.transform.Field
import groovy.json.JsonOutput

@Field static final String constDriverVersion = "0.0.2"

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

        attribute "temperatureScale", "string"
        attribute "keypadLockout", "string"
        attribute "outdoorTemperature", "number"

        command 'setKeypadLockoutMode', [ [name: "lockoutMode*",
                                           type: "ENUM",
                                           description: "Lock/unlock the thermostat's keypad",
                                           constraints:["lock","unlock"]] ]
        command "setOutdoorTemperature", [[name:"Temperature", type:"NUMBER", description: "Set the outdoor temperature display to this value"]]

        command "increaseHeatSetpoint"
        command "decreaseHeatSetpoint"

        fingerprint profileId: "0104", endpointId: "19", inClusters: "0000,0003,0004,0201,0204", outClusters: "0003,000A,0402", manufacturer: 'Stello', model: 'HT402', deviceJoinName: '(Stelpro/Hilo) Stello HT402 Thermostat'
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

@Field static final Map<String,Integer> constZigbeeManufacturerCodes = [ "Stelpro": 0x1185, "Stello": 0x1297 ]

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

List<String> installed() {
    logInfo('installed()')
    configure()
}

List<String> initialize() {
    logInfo('initialize()')
    if (state.driverVersion != constDriverVersion) {
        logWarn "New/different driver installed since last configure()"
    }
    refresh()
}

List<String> updated() {
    logInfo('updated()')
    configure()
}

void uninstalled() {
    logInfo('uninstalled()')
}

List<String> configure(){
    logWarn "configure..."
    state.driverVersion = constDriverVersion

    unschedule()
    runIn(1800,debugLogsOff)

    // Configure Default values if null
    if (tempChange == null)
        tempChange = 100 as int
    if (powerReport == null)
        powerReport = 30 as int
    if (reportingSeconds == null)
        reportingSeconds = "60"
    if (state.thermostatIsOn == null)
        state.thermostatIsOn = true


    // Set unused default values (for Google Home Integration)
    sendEvent(name: "coolingSetpoint", value:getTemperature("0BB8")) // 0x0BB8 =  30 Celsius
    sendEvent(name: "thermostatFanMode", value:"auto")
	sendEvent(name: "supportedThermostatFanModes", value: JsonOutput.toJson(["auto"]))
	sendEvent(name: "supportedThermostatModes", value: JsonOutput.toJson(["heat", "off"]))
    //setThermostatMode("heat")

    List<String> cmds = []

    //bindings
    cmds += "zdo bind 0x${device.deviceNetworkId} 1 0x019 0x201 {${device.zigbeeId}} {}" // TODO: why is this needed?
    cmds += "delay 200"

    //reporting
    cmds += zigbee.configureReporting(0x201, 0x0000, 0x29, 0, reportingSeconds as int, tempChange as int)   //Attribute ID 0x0000 = local temperature, Data Type: S16BIT
    cmds += zigbee.configureReporting(0x201, 0x0008, 0x20, 0, reportingSeconds as int, 5)   //Attribute ID 0x0008 = pi heating demand, Data Type: U8BIT
    cmds += zigbee.configureReporting(0x201, 0x0012, 0x29, 0, reportingSeconds as int, 1)     //Attribute ID 0x0012 = occupied heat setpoint, Data Type: S16BIT
    cmds += zigbee.configureReporting(0x201, 0x4008, 0x29, 0, reportingSeconds as int, powerReport as int)     //Attribute ID 0x4008 = power usage, Data Type: S16BIT
    cmds += zigbee.configureReporting(0x201, 0x4009, 0x29, 0, reportingSeconds as int, 10)     //Attribute ID 0x4009 = energy usage, Data Type: S16BIT

    cmds += zigbee.configureReporting(0x204, 0x0000, 0x30, 0, reportingSeconds as int)         //Attribute ID 0x0000 = temperature display mode, Data Type: 8 bits enum
    cmds += zigbee.configureReporting(0x204, 0x0001, 0x30, 0, reportingSeconds as int)         //Attribute ID 0x0001 = keypad lockout, Data Type: 8 bits enum

    logTrace "cmds:${cmds}"
    return cmds + refresh()
}

def refresh() {
    logInfo("refresh")
    List<String> cmds = []

    cmds += zigbee.readAttribute(0x201, 0x0000) // Local Temperature
    cmds += zigbee.readAttribute(0x201, 0x0008) // PI Heating State
    cmds += zigbee.readAttribute(0x201, 0x0012) // Heat Setpoint


    // missing a few vendor-specific attributes (see https://www.zigbee2mqtt.io/devices/HT402.html)
    cmds += zigbee.readAttribute(0x201, 0x4001) // Outdoor temperature
    cmds += zigbee.readAttribute(0x201, 0x4008) // power
    cmds += zigbee.readAttribute(0x201, 0x4009) // energy

    cmds += zigbee.readAttribute(0x204, 0x0000) // Temperature Display Mode
    cmds += zigbee.readAttribute(0x204, 0x0001) // Keypad Lockout

    logTrace "refresh cmds:${cmds}"
    return cmds
}

// Capabilities

void auto() {
    logWarn('auto(): mode is not available for this device. => Defaulting to heat mode instead.')
}

void cool() {
    logWarn('cool(): mode is not available for this device. => Defaulting to heat mode instead.')
}

void emergencyHeat() {
    logWarn('emergencyHeat(): mode is not available for this device. => Defaulting to heat mode instead.')
}

void fanAuto() {
    logWarn('fanAuto(): mode is not available for this device')
}

void fanCirculate() {
    logWarn('fanCirculate(): mode is not available for this device')
}

void fanOn() {
    logWarn('fanOn(): mode is not available for this device')
}

void setCoolingSetpoint(degrees) {
    logWarn("setCoolingSetpoint(${degrees}): is not available for this device")
}

void heat() {
    setThermostatMode("heat")
}

void eco() {
    logWarn("eco mode is not available for this device")
}

void off() {
    setThermostatMode("off")
}


void setThermostatMode(String thermostatMode) {
    // This thermostat model does not honor cluster 0x0201 attribute 0x001C (or vendor-specific 0x401C) for setting the system mode,
    // therefore the off state is faked by setting the thermostat's setpoint to constHeatOffSetpoint (5 degrees C)
    // The driver's setpoint attributes remain unchanged while the thermostat is "off", so when turned back to "heat" mode,
    // the thermostat device will be set back to its last known heating setpoint, mimicking the typical thermostat behaviour.

    switch (thermostatMode) {
        case "heat":
            if (state.thermostatIsOn == false) {
                state.thermostatIsOn = true

                Integer setpoint = state.lastHeatingSetpoint ?: device.currentValue("temperature") as int
                logDebug("heat() setpoint will be ${setpoint}")
                setHeatingSetpoint(setpoint)

                sendEvent(name:"thermostatMode", value:thermostatMode, descriptionText: "${device.displayName} thermostat mode set to ${thermostatMode}")

            }
            break

        case "off":
            // This model does not appear to honor cluster 0x0201 attribute 0x001C for setting the system mode.
            if (state.thermostatIsOn) {
                logDebug("off() setpoint will be ${constHeatOffSetpoint}")
                setHeatingSetpoint(constHeatOffSetpoint)

                state.thermostatIsOn = false
                sendEvent(name:"thermostatMode", value:thermostatMode, descriptionText: "${device.displayName} thermostat mode set to ${thermostatMode}")
            }
            break

        default:
            logWarn("invalid thermostat mode requested (${thermostatMode})")
            break
    }
}

void setHeatingSetpoint(BigDecimal preciseDegrees) {
    if (preciseDegrees != null) {
        BigDecimal degrees = preciseDegrees.setScale(1, BigDecimal.ROUND_HALF_UP)

        logDebug "setHeatingSetpoint(${degrees} ${location.temperatureScale})"

        Float celsius = temperatureScaleIsCelsius() ? degrees as Float : (fahrenheitToCelsius(degrees) as Float).round(2)

        if (state.thermostatIsOn) {
            // Thermostat is "on".  Update the thermostat device's setpoint.
            List<String> cmds = []
            cmds += zigbee.writeAttribute(0x201, 0x0012, 0x29, Math.round(celsius * 100) as int)
            cmds += zigbee.readAttribute(0x201, 0x0012)
            sendZigbeeCommands(cmds)
        } else {
            // Thermostat is "off".  Remember requested setpoint, will be used when heat mode is turned back on.
            state.lastHeatingSetpoint = celsius
        }
    }
}

// Custom commands

void increaseHeatSetpoint() {
    BigDecimal currentSetpoint = device.currentValue("heatingSetpoint")

    if (currentSetpoint < (temperatureScaleIsCelsius() ? 30 : 86)) {
        currentSetpoint = currentSetpoint + (temperatureScaleIsCelsius() ? 0.5 : 1)
        setHeatingSetpoint(currentSetpoint)
    }
}

void decreaseHeatSetpoint() {
    BigDecimal currentSetpoint = device.currentValue("heatingSetpoint")

    if (currentSetpoint > (temperatureScaleIsCelsius() ? 5 : 41)) {
        currentSetpoint = currentSetpoint - (temperatureScaleIsCelsius() ? 0.5 : 1)
        setHeatingSetpoint(currentSetpoint)
    }
}


void setKeypadLockoutMode(String lockoutMode) {
    List<String> cmds = []

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

    cmds += zigbee.readAttribute(0x0204, 0x0001)
    sendZigbeeCommands(cmds)
}


void setOutdoorTemperature(BigDecimal preciseDegrees) {
    if (preciseDegrees != null) {
        BigDecimal degrees = preciseDegrees.setScale(1, BigDecimal.ROUND_HALF_UP)

        logDebug "setOutdoorTemperature(${degrees} ${location.temperatureScale})"

        Float celsius = temperatureScaleIsCelsius() ? degrees as Float : (fahrenheitToCelsius(degrees) as Float).round(2)
        int celsiusHundredths = Math.round(celsius * 100)

        List<String> cmds = []
        cmds += zigbee.writeAttribute(0x201, 0x4001, 0x29, celsiusHundredths)
        cmds += zigbee.readAttribute(0x201, 0x4001)
        sendZigbeeCommands(cmds)
    }
}

// Zigbee message parsing

def parse(String description) {
    if (state.driverVersion != constDriverVersion) {
        state.driverVersion = constDriverVersion
        runInMillis 1500, 'autoConfigure'
    }

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
        case "0000": // Basic cluster
        case "0003": // Identify cluster
        case "0004": // Groups cluster
            break

        case "0201":
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
                            if (descMap.value > "8000") {
                                map.value = -(Math.round(2*(655.36 - Integer.parseInt(descMap.value, 16)))/2)
                            } else {
                                map.value = getTemperature(descMap.value)
                            }
                            break
                    }
                    map.unit = location.temperatureScale
                    map.descriptionText = "${device.displayName} temperature is ${map.value}${map.unit}"
                    break

                case "0008":
                    map.name = "thermostatOperatingState"
                    //map.value = constThermostatModes[descMap.value]
                    if (descMap.value < "10") {
                        map.value = "idle"
                        final int interval = (settings.refreshScheduleIdle as Integer) ?: 0
                        if (interval > 0 && map.value != device.currentValue("thermostatOperatingState")) {
                            logInfo "${device} scheduling refresh every ${interval} minutes"
                            scheduleRefresh(interval)
                            runIn(5, 'refresh')
                        }
                    } else {
                        map.value = "heating"
                        final int interval = (settings.refreshScheduleHeating as Integer) ?: 0
                        if (interval > 0 && map.value != device.currentValue("thermostatOperatingState")) {
                            logInfo "${device} scheduling refresh every ${interval} minutes"
                            scheduleRefresh(interval)
                            runIn(5, 'refresh')
                        }
                    }
                    map.descriptionText = "${device.displayName} operating state is ${map.value}"
                    break

                case "0012":
                    map.name = "heatingSetpoint"
                    map.value = getTemperature(descMap.value)
                    if (descMap.value == "8000") {        //0x8000  TODO - why?
                        map.value = getTemperature("01F4")  // 5 Celsius (minimum setpoint)
                    }
                    map.unit = location.temperatureScale
                    map.descriptionText = "${device.displayName} heating setpoint is ${map.value}${map.unit}"

                    // also send separate thermostatSetpoint event
                    sendEvent(name:"thermostatSetpoint", value:map.value, unit:map.unit, descriptionText: map.descriptionText)

                    // remember last heating setpoint
                    if (descMap.value != "01F4") {
                        state.lastHeatingSetpoint = map.value
                    }
                    break

                case "001C": // system mode - not used on this model
                    break

                case "4001":
                    map.name = "outdoorTemperature"
                    map.value = getTemperature(descMap.value)
                    if (map.value > 100) {
                        map.value = map.value - 655.36 // handle negative temperatures
                    }
                    map.unit = location.temperatureScale
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
                    map.unit = "kWh"
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
                    map.name = "temperatureScale"
                    map.value = constTempDisplayModes[descMap.value]
                    map.descriptionText = "${device.displayName} temperature display mode is ${map.value}"
                    break

                case "0001":
                    map.name = "keypadLockout"
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
        if (map.descriptionText) logInfo("${map.descriptionText}")
        result = createEvent(map)
    } else {
        logDebug("Unhandled attribute report - cluster ${descMap.cluster} attribute ${descMap.attrId} value ${descMap.value}")
        logTrace("descMap: ${descMap}")
    }

    return result
}

// private methods

private void autoConfigure() {
    logWarn "Detected driver version change"
    configure()
}

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
    logInfo "${rnd.nextInt(59)} ${rnd.nextInt(intervalMin)}-59/${intervalMin} * ? * * *"
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

//@CompileStatic
private boolean temperatureScaleIsCelsius() {
    return location.temperatureScale == "C"
}

private BigDecimal getTemperature(String value) {
    if (value != null) {
        logTrace("getTemperature: value $value")
        BigDecimal celsius = new BigDecimal(Integer.parseInt(value, 16)) / 100

        if (temperatureScaleIsCelsius()) {
            return celsius.setScale(1, BigDecimal.ROUND_HALF_UP)
        }

        BigDecimal fahrenheit = celsiusToFahrenheit(celsius)
        return fahrenheit.setScale(1, BigDecimal.ROUND_HALF_UP)
    }
}

private void sendZigbeeCommands(cmds) {
    sendHubCommand(new hubitat.device.HubMultiAction(cmds, hubitat.device.Protocol.ZIGBEE))
}

// logging helpers

private void logTrace(String message) {
    if (traceEnable) log.trace("${device} : ${message}")
}

private void logDebug(String message) {
    if (debugEnable) log.debug("${device.displayName} : ${message}")
}

private void logInfo(String message) {
    if (infoEnable) log.info("${device.displayName} : ${message}")
}

private void logWarn(String message) {
    log.warn("${device.displayName} : ${message}")
}

private void logError(String message) {
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


/*

 ================================================================================================
Node Descriptor
------------------------------------------------------------------------------------------------
▸ Logical Type                              = Zigbee Router
▸ Complex Descriptor Available              = No
▸ User Descriptor Available                 = No
▸ Fragmentation Supported (R23)             = No
▸ Frequency Band                            = Reserved
▸ Alternate PAN Coordinator                 = No
▸ Device Type                               = Full Function Device (FFD)
▸ Mains Power Source                        = Yes
▸ Receiver On When Idle                     = Yes (always on)
▸ Security Capability                       = No
▸ Allocate Address                          = Yes
▸ Manufacturer Code                         = 0x1297
▸ Maximum Buffer Size                       = 82 bytes
▸ Maximum Incoming Transfer Size            = 82 bytes
▸ Primary Trust Center                      = No
▸ Backup Trust Center                       = No
▸ Primary Binding Table Cache               = Yes
▸ Backup Binding Table Cache                = No
▸ Primary Discovery Cache                   = Yes
▸ Backup Discovery Cache                    = Yes
▸ Network Manager                           = Yes
▸ Maximum Outgoing Transfer Size            = 82 bytes
▸ Extended Active Endpoint List Available   = No
▸ Extended Simple Descriptor List Available = No
================================================================================================
Power Descriptor
------------------------------------------------------------------------------------------------
▸ Current Power Mode         = Same as "Receiver On When Idle" from "Node Descriptor" section above
▸ Available Power Sources    = [Constant (mains) power]
▸ Current Power Sources      = [Constant (mains) power]
▸ Current Power Source Level = 100%
================================================================================================
Endpoint 0x19
================================================================================================
Out Cluster: 0x0003 (Identify Cluster)
------------------------------------------------------------------------------------------------
▸ 0x00 | Identify | req
================================================================================================
Out Cluster: 0x000A (Time Cluster)
------------------------------------------------------------------------------------------------
▸ No generated commands
================================================================================================
Out Cluster: 0x0402 (Temperature Measurement Cluster)
------------------------------------------------------------------------------------------------
▸ No generated commands
================================================================================================
In Cluster: 0x0000 (Basic Cluster)
------------------------------------------------------------------------------------------------
▸ 0x0000 | ZCL Version          | req | r-- | uint8  | 03 = 3          | --
▸ 0x0001 | Application Version  | opt | r-- | uint8  | 21 = 33         | --
▸ 0x0002 | Stack Version        | opt | r-- | uint8  | 22 = 34         | --
▸ 0x0003 | HW Version           | opt | r-- | uint8  | 01 = 1          | --
▸ 0x0004 | Manufacturer Name    | opt | r-- | string | Stello          | --
▸ 0x0005 | Model Identifier     | opt | r-- | string | HT402           | --
▸ 0x0006 | Date Code            | req | r-- | string | 20000000 00000 | --
▸ 0x0007 | Power Source         | opt | r-- | enum8  | 00 = Unknown    | --
▸ 0x0010 | Location Description | opt | rw- | string | Thermostat      | --
▸ 0x0011 | Physical Environment | opt | rw- | enum8  | 00              | --
▸ 0xFFFD | Cluster Revision     | req | r-- | uint16 | 0001 = 1        | --
------------------------------------------------------------------------------------------------
▸ No received commands
================================================================================================
In Cluster: 0x0003 (Identify Cluster)
------------------------------------------------------------------------------------------------
▸ 0x0000 | Identify Time    | req | rw- | uint16 | 0000 = 0 seconds | --
▸ 0xFFFD | Cluster Revision | req | r-- | uint16 | 0001 = 1         | --
------------------------------------------------------------------------------------------------
▸ 0x00 | Identify       | req
▸ 0x01 | Identify Query | req
================================================================================================
In Cluster: 0x0004 (Groups Cluster)
------------------------------------------------------------------------------------------------
▸ 0x0000 | Name Support     | req | r-- | map8   | 00 = 00000000 | --
▸ 0xFFFD | Cluster Revision | req | r-- | uint16 | 0001 = 1      | --
------------------------------------------------------------------------------------------------
▸ 0x00 | Add Group                | req
▸ 0x01 | View Group               | req
▸ 0x02 | Get Group Membership     | req
▸ 0x03 | Remove Group             | req
▸ 0x04 | Remove All Groups        | req
▸ 0x05 | Add Group If Identifying | req
================================================================================================
In Cluster: 0x0201 (Thermostat Cluster)
------------------------------------------------------------------------------------------------
▸ 0x0000 | Local Temperature              | req | r-p | int16 | 0866 = 21.5°C | 0..21600
▸ 0x0001 | Outdoor Temperature            | opt | r-- | int16 | 8000 = 0°C    | --
▸ 0x0002 | Occupancy                      | opt | r-- | map8  | 00 = 00000000 | --
▸ 0x0003 | Abs Min Heat Setpoint Limit    | opt | r-- | int16 | 01F4 = 5°C    | --
▸ 0x0004 | Abs Max Heat Setpoint Limit    | opt | r-- | int16 | 0BB8 = 30°C   | --
▸ 0x0005 | Abs Min Cool Setpoint Limit    | opt | r-- | int16 | 02BC = 7°C    | --
▸ 0x0006 | Abs Max Cool Setpoint Limit    | opt | r-- | int16 | 1194 = 45°C   | --
▸ 0x0008 | PI Heating Demand              | opt | r-p | uint8 | 00 = 0        | 0..21600
▸ 0x0009 | HVAC System Type Configuration | opt | rw- | map8  | 00 = 00000000 | --
▸ 0x0010 | Local Temperature Calibration  | opt | rw- | int8  | 00 = Infinity | --
▸ 0x0011 | Occupied Cooling Setpoint      | req | rw- | int16 | 1194 = 45°C   | --
▸ 0x0012 | Occupied Heating Setpoint      | req | rws | int16 | 07D0 = 20°C   | --
▸ 0x0013 | Unoccupied Cooling Setpoint    | opt | rw- | int16 | 1194 = 45°C   | --
▸ 0x0014 | Unoccupied Heating Setpoint    | opt | rw- | int16 | 06A4 = 17°C   | --
▸ 0x0015 | Min Heat Setpoint Limit        | opt | rw- | int16 | 01F4 = 5°C    | --
▸ 0x0016 | Max Heat Setpoint Limit        | opt | rw- | int16 | 0BB8 = 30°C   | --
▸ 0x0017 | Min Cool Setpoint Limit        | opt | rw- | int16 | 02BC = 7°C    | --
▸ 0x0018 | Max Cool Setpoint Limit        | opt | rw- | int16 | 1194 = 45°C   | --
▸ 0x0019 | Min Setpoint Dead Band         | opt | rw- | int8  | 19 = 25       | --
------------------------------------------------------------------------------------------------
▸ 0x00 | Setpoint Raise/Lower | req
================================================================================================
In Cluster: 0x0204 (Thermostat User Interface Configuration Cluster)
------------------------------------------------------------------------------------------------
▸ 0x0000 | Temperature Display Mode | req | r-- | enum8  | 00       | --
▸ 0x0001 | Keypad Lockout           | req | rw- | enum8  | 00       | --
▸ 0xFFFD | Cluster Revision         | req | r-- | uint16 | 0001 = 1 | --
------------------------------------------------------------------------------------------------
▸ No received commands
================================================================================================
Endpoint 0xF2
================================================================================================
Out Cluster: 0x0021 (Green Power Cluster)
------------------------------------------------------------------------------------------------
▸ No generated commands
================================================================================================

 */

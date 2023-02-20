/*
 *  Sinope Water Valve VA422xZB Hubitat Device Driver - with support for FS422x Flow Sensor
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
 *  Specs for this device : https://support.sinopetech.com/en/1.5.7.3/
 *
 *  Source: https://github.com/iamtrep/hubitat/blob/main/drivers/sinope/Sinope_VA422xZB.groovy
 *
 *  Portions derived/inspired from:
 *    (sacua) Sinope drivers (https://github.com/sacua/SinopeDriverHubitat)
 *    (kkossev) Hubitat zigbee drivers (https://github.com/kkossev/Hubitat)
 *    (thebearmay) Hubitat zigbee drivers (https://github.com/thebearmay/hubitat)
 *
 * TODO
 * - validate the volumes and flow rates are OK (pipe size? check for any difference between 0x0702 attr 0x0000 and attr 0x0400?)
 * - figure out proper/standard way of reporting battery alarm states
 * - (maybe) keep volume as an integer internally
 * - Keep VoltageMeasurement or move to custom batteryVoltage attribute ?
 *
 * v0.0.1 Initial version
 *
 */

metadata {
    definition(
        name: "Sinope Water Valve (VA422xZB)",
        namespace: "iamtrep",
        author: "PJ Tremblay",
        importUrl: "https://raw.githubusercontent.com/iamtrep/hubitat/main/drivers/sinope/Sinope_VA422xZB.groovy"
    ) {
        capability "Configuration"
        capability "Initialize"
        capability "Refresh"

        capability "Valve"
        capability "LiquidFlowRate"  // when using the optional FS422x flow sensor
        capability "Battery"
        capability "PowerSource"
        capability "VoltageMeasurement"
        capability "TemperatureMeasurement"

        attribute "volume", "number"  // no standard capability for volume measurement

        // for development purposes - do not use
        attribute "batteryAlt", "number"
        attribute "batteryAlarmState", "string"
        attribute "rateAlt", "number"

        command "testMeteringConfig"

        preferences {
            input(name: "prefPowerSourceSchedule", type: "number", title: "Power source poll rate (in minutes)", required: true, defaultValue: 5)
            input(name: "prefBatteryAlarmSchedule", type: "number", title: "Battery alarm state poll rate (in hours)", required: true, defaultValue: 1)
            input(name: "prefEnableFlowSensor", type: "bool", title: "Flow rate sensor", description: "Enable Sinope FS422x flow rate sensor", defaultValue: false, required: true, submitOnChange: true)
            if (prefEnableFlowSensor) {
                input(name: "prefMinVolumeChange", type: "number", title: "Flow", description: "Minimum water volume delivery to trigger flow auto reporting", defaultValue: 1)
            }
            input(name: "txtEnable", type: "bool", title: "Enable descriptionText logging", defaultValue: true)
            input(name: "debugEnable", type: "bool", title: "Enable debug logging info", defaultValue: false, required: true, submitOnChange: true)
            if (debugEnable) {
                input(name: "traceEnable", type: "bool", title: "Enable trace logging info (for development purposes)", defaultValue: false)
            }
        }

        // VA4220ZB is the 3/4 inch valve
        fingerprint profileId: "0104", endpointId:"01", inClusters: "0000,0001,0003,0004,0005,0006,0008,0402,0500,0702,0B05,FF01", outClusters: "0003,0006,0019", manufacturer: "Sinope Technologies", model: "VA4220ZB", deviceJoinName: "Sinope Water Valve VA4220ZB"

        // VA4221ZB is the 1 inch valve (seemingly identical to VA4220ZB other than pipe diameter)
        fingerprint profileId: "0104", endpointId:"01", inClusters: "0000,0001,0003,0004,0005,0006,0008,0402,0500,0702,0B05,FF01", outClusters: "0003,0006,0019", manufacturer: "Sinope Technologies", model: "VA4221ZB", deviceJoinName: "Sinope Water Valve VA4221ZB"

    }
}

// Constants

import groovy.transform.Field

// Battery pack is 4x Lithium AAA batteries - rated from 1.8V to 1.4V on small loads (https://data.energizer.com/pdfs/l92.pdf)
@Field static final float constBatteryVoltageMin = 4.0f * 1.4f
@Field static final float constBatteryVoltageMax = 4.0f * 1.8f
@Field static final Map constBatteryAlarmValues = [ "00000000": "clear", "0000000F": "alarmed"]  // TBC

@Field static final Map constValveValues =  [ "00": "closed", "01": "open" ]

@Field static final Map constWaterSensorValues =  [ "0030": "dry", "0031": "wet" ]

// See ZCL 3.2.2.2.8 - bit 7 (0x80) is set when battery backup is present.  Handling this the lazy way.
@Field static final Map constPowerSources = ["00": "unknown", "01": "mains", "02": "mains", "03": "battery", "04": "dc", "05": "emergency", "06": "emergency",
                                             "80": "unknown", "81": "mains", "82": "mains", "83": "battery", "84": "dc", "85": "emergency", "86": "emergency" ]

// Some constants to avoid divide by zero, etc. tbc.
@Field static final float constMinVolumeDiff = 0.0001f
@Field static final float constMinSampleTimeDiff = 0.0001f

// Driver installation

def installed() {
    // called when device is first created with this driver
    initialize()
}

def updated() {
    // called when preferences are saved.
    configure()
}

def uninstalled() {
    // called when device is removed
    try {
        unschedule()
    }
    catch (e)
    {
        logError "unschedule() threw an exception ${e}"
    }
}

// Capabilities

def configure() {
    logTrace("configure()")

    try
    {
        unschedule()
    }
    catch (e)
    {
        logError("unschedule() threw an exception ${e}")
    }

    // Configure device attribute self-reporting
    def cmds = []
    cmds += zigbee.configureReporting(0x0000, 0x0007, DataType.ENUM8, 3600, 7200)                         // power source
    cmds += zigbee.configureReporting(0x0001, 0x0020, DataType.UINT8, 300, 3600, 1)                       // battery voltage
    cmds += zigbee.configureReporting(0x0001, 0x0021, DataType.UINT8, 300, 3600, 1)                       // battery level (apparently not supported by device, always returns 0)
    cmds += zigbee.configureReporting(0x0001, 0x003E, DataType.BITMAP32, 3600, 7200, 1)                   // battery alarm states (TODO)
    cmds += zigbee.configureReporting(0x0006, 0x0000, DataType.BOOLEAN, 0, 3600)                          // valve state
    cmds += zigbee.configureReporting(0x0402, 0x0000, DataType.INT16, 0, 3600, 1)                         // (device) temperature (in 1/100ths C)

    if (prefEnableFlowSensor) {
        // Support for FS422x (if attached)
        //
        // The built-in Hubitat driver appears to compute flow from volume (cluster 0x0702, attribute 0x0000)
        // The built-in Hubitat driver however has a setting for the pipe size (3/4" or 1"), which this driver doesn't have.
        //
        //cmds += zigbee.configureReporting(0x0702, 0x0000, DataType.UINT48, 59, 1799, (int) prefMinVolumeChange)  // volume delivered (in ml?)
        cmds += zigbee.configureReporting(0x0702, 0x0400, DataType.INT24, 0, 300, 1)                      // flow rate via InstantaneousDemand - ZCL 10.4.2.2.5
    } else {
        // turn off reporting?
    }

    sendZigbeeCommands(cmds)

    // Schedule refresh requests for power source and battery alarm, since the self-reporting appears not to work.
    runIn(prefPowerSourceSchedule*60, requestPowerSourceReport, [overwrite: true, misfire: "ignore"])
    runIn(prefBatteryAlarmSchedule*3600, requestBatteryAlarmReport, [overwrite: true, misfire: "ignore"])
}

def initialize() {
    logTrace("initialize()")

    // state.clear()
    state.switchTypeDigital = false
    state.volumeSinceLastEvent = 0f
    state.remove("lastVolumeRecorded")
    state.remove("lastVolumeRecordedTime")
    state.remove("volumeSinceLastEvent")

    configure()
    refresh()
}

def refresh() {
    def cmds = []

    cmds += zigbee.readAttribute(0x0000, 0x0007) // power source
    cmds += zigbee.readAttribute(0x0001, 0x0020) // battery voltage
    cmds += zigbee.readAttribute(0x0001, 0x0021) // battery level (apparently not supported by device, appears to always return 0)
    cmds += zigbee.readAttribute(0x0001, 0x003E) // battery alarm state
    cmds += zigbee.readAttribute(0x0006, 0x0000) // valve state
    cmds += zigbee.readAttribute(0x0402, 0x0000) // (device) temperature

    if (prefEnableFlowSensor) {
        cmds += zigbee.readAttribute(0x0702, 0x0000) // volume delivered
        cmds += zigbee.readAttribute(0x0702, 0x0400) // flow rate
    }

    sendZigbeeCommands(cmds)
}

def open() {
    def cmds = []
    cmds += zigbee.command(0x0006, 0x01)
    sendZigbeeCommands(cmds)
    state.switchTypeDigital = true
}

def close() {
    def cmds = []
    cmds += zigbee.command(0x0006, 0x00)
    sendZigbeeCommands(cmds)
    state.switchTypeDigital = true
}

// Custom commands

def testMeteringConfig() {
    def cmds = []
    if (prefEnableFlowSensor) {
        // For driver test/development purposes (enable debug logs to use).
        //
        // Metering cluster includes a Formatting attribute set, among which:
        //   0x0300 UnitofMeasure (enum8) (MANDATORY)
        //   0x0301 Multiplier
        //   0x0302 Divisor
        //   0x0303 SummationFormatting (map8) (see 10.4.2.2.4.4) (MANDATORY)
        //   0x0304 DemandFormatting (map8)
        //   0x0305 HistoricalConsumptionFormatting (map8)
        //   0x0306 MeteringDeviceType (map8) (MANDATORY)
        //
        // See "Table 10-71. Formatting Attribute Set" in ZCL for details
        cmds += zigbee.readAttribute(0x0702, 0x0300) // "07" (L and L/h)
        cmds += zigbee.readAttribute(0x0702, 0x0301) // "000001"
        cmds += zigbee.readAttribute(0x0702, 0x0302) // "0003E8" (1000, meaning summation is in mL, not L)
        cmds += zigbee.readAttribute(0x0702, 0x0303) // "F8" (low 3 bits are clear - no decimal, all digits = volume reported in mL)
        cmds += zigbee.readAttribute(0x0702, 0x0304) // "FB" (low 2 bits are set = flow report in 1/100 of mL)
        cmds += zigbee.readAttribute(0x0702, 0x0305) // no response
        cmds += zigbee.readAttribute(0x0702, 0x0306) // "02" (water)
    }
    sendZigbeeCommands(cmds)
}

// Device Event Parsing

def parse(String description) {
    def descMap = zigbee.parseDescriptionAsMap(description)
    logTrace("parse() - description = ${descMap}")

    def result = []

    if (descMap.attrId != null) {
        // device attribute report
        result += createCustomMap(descMap)
        if (descMap.additionalAttrs) {
            def mapAdditionnalAttrs = descMap.additionalAttrs
            mapAdditionnalAttrs.each{add ->
                add.cluster = descMap.cluster
                result += createCustomMap(add)
            }
        }
    } else if (descMap.profileId == "0000") {
        // ZigBee Device Object (ZDO) command
        //logTrace("Unhandled ZDO command: clusterId=${descMap.clusterId} attrId=${descMap.attrId} command=${descMap.command} value=${descMap.value} data=${descMap.data}")
    } else if (descMap.profileId == "0104" && descMap.clusterId != null) {
        // ZigBee Home Automation (ZHA) global command
        //logTrace("Unhandled ZHA global command: clusterId=${descMap.clusterId} attrId=${descMap.attrId} command=${descMap.command} value=${descMap.value} data=${descMap.data}")
    } else {
        logWarn("Unhandled unknown command: clusterId=${descMap.clusterId} attrId=${descMap.attrId} command=${descMap.command} value=${descMap.value} data=${descMap.data}")
    }

    return result
}

private createCustomMap(descMap){
    def map = [: ]

    // Main switch over all available cluster IDs
    //
    // fingerprint : inClusters: "0000,0001,0003,0004,0005,0006,0008,0402,0500,0702,0B05,FF01"
    //
    switch (descMap.cluster) {
        case "0000":  // Basic cluster
            if (descMap.attrId == "0007") {
                map.name = "powerSource"
                map.value = getPowerSource(descMap.value)
                map.descriptionText = "Power source is ${map.value}"
            }
            break

        case "0001":  // Power configuration cluster
            switch (descMap.attrId) {
                case "0020":
                    map.name = "voltage"
                    map.value = getBatteryVoltage(descMap.value)
                    map.unit = "V"
                    map.descriptionText = "Battery voltage is ${map.value} ${map.unit}"
                    runIn(2,computeBatteryLevel) // update battery level from this report
                    break

                case "0021":
                    // TODO - ignore for now.  Will get trace-logged at end of this function.
                    map.name = "batteryAlt"
                    map.value = getBatteryLevel(descMap.value)
                    map.unit = "%"
                    map.descriptionText = "Battery (alt) percentage remaining is ${map.value} ${map.unit}"
                    break

                case "003E":
                    // TODO figure out how alarms normally report
                    //
                    // - 0x00000000 : battery is good.
                    // - 0x0000000F : reported when pulling one of the four batteries
                    //
                    // other states TBD (e.g. battery low)
                    map.name = "batteryAlarmState"
                    map.value = constBatteryAlarmValues[descMap.value]
                    if (map.value == null) {
                        logDebug("Unknown battery alarm value ${descMap.value}")
                        map.value = Integer.parseInt(descMap.value, 32) > 0 ? "alarmed" : "clear"
                    }
                    map.descriptionText = "Battery alarm state is ${map.value}"
                    logTrace("Battery alarm raw attribute report is ${descMap.value}")  // log the raw report while we fully figure this out
                    break

                default:
                    break
            }
            break

        case "0003": // Identify cluster
        case "0004": // Groups cluster
        case "0005": // Scenes cluster
            break

        case "0006": // On/Off cluster
            if (descMap.attrId == "0000") {
                map.name = "valve"
                map.value = constValveValues[descMap.value]
                map.type = state.switchTypeDigital ? "digital" : "physical"
                state.switchTypeDigital = false
                map.descriptionText = "Valve is ${map.value} [${map.type}]"
            }
            break

        case "0008": // Level Control cluster
            // According to ZCL : "Attributes and commands for controlling a characteristic of devices that can be set to a level between fully ‘On’ and fully ‘Off’."
            // Unknown use for this device.  However valve has LED indicators for progress from open to close...
            break

        case "0402": // Temperature measurement cluster
            // Appears to be a temperature probe on the device (a priori it's not the water temp)
            if (descMap.attrId == "0000") {
                map.name = "temperature"
                map.value = getTemperature(descMap.value)
                map.unit = getTemperatureScale()
                map.descriptionText = "Temperature is ${map.value} ${map.unit}"
            }
            break

        case "0500": // IAS Zone cluster
            // Could be for a water leak alarm. Not supported.
            break

        case "0702": // Metering cluster
            switch (descMap.attrId) {
                case "0000":
                    map.name = "volume"
                    map.value = getVolume(descMap.value)
                    map.unit = "L"
                    map.descriptionText = "Cumulative water volume delivered is ${map.value} ${map.unit}"
                    computeFlowRate(map.value)
                    break

                case "0400":
                map.name = "rateAlt"
                    map.value = getFlowRate(descMap.value)
                    map.unit = "LPM"
                    map.descriptionText = "Water flow (alt) rate is ${map.value} ${map.unit}"
                    break

                default:
                    logDebug("Unhandled Metering cluster attribute report - attribute ${descMap.attrId} value ${descMap.value}")
                    break
            }
            break

        case "0B05": // Diagnostics cluster
        case "FF01": // Manufacturer-specific cluster
        default:
            break
    }

    def result = null

    if (map) {
        if (map.descriptionText) logInfo("${map.descriptionText}")
        result = createEvent(map)
    } else {
        logDebug("Unhandled attribute report - cluster ${descMap.cluster} attribute ${descMap.attrId} value ${descMap.value}")
    }

    return result
}

def computeBatteryLevel() {
    def computedLevel = getBatteryLevelFromVoltage()
    def eventDescriptionText = "Battery percentage remaining is ${computedLevel} %"
    sendEvent(name: "battery", value: computedLevel, unit: "%", descriptionText: eventDescriptionText)
    logInfo(eventDescriptionText)
}

def computeFlowRate(currentVolume) {
    def volumeDiff = 0
    def sampleTimeDiff = 0
    def sampleTimeNow = new Date().time  // TODO: find out if there a way to get a timestamp from the zigbee attribute report

    if (state.lastVolumeRecorded) {
        volumeDiff = currentVolume - state.lastVolumeRecorded
    }
    state.volumeSinceLastEvent = volumeDiff  // keep track for now.

    // Compute flow
    // TODO : validate for correctness
    def computedFlowRate = 0f
    if (volumeDiff > constMinVolumeDiff) {
        if (state.lastVolumeRecordedTime) {
            sampleTimeDiff = (sampleTimeNow - state.lastVolumeRecordedTime)
        }

        if (sampleTimeDiff > constMinSampleTimeDiff) {
            // We have a volume difference in L, and a time difference in ms
            // We want flow rate in LPM, two decimal places (probably should be 1 decimal place, tbd)
            computedFlowRate = Math.round( 100 * (volumeDiff * 60f) / (sampleTimeDiff / 1000)) / 100
        } else {
            logDebug("positive but instantaneous volume change ?!?")
            return
        }
    }

    def eventDescriptionText = "Water flow rate is ${computedFlowRate} LPM"
    sendEvent(name: "rate", value: computedFlowRate, unit: "LPM", descriptionText: eventDescriptionText)
    logInfo(eventDescriptionText)

    // cleanup
    state.lastVolumeRecorded = currentVolume
    state.lastVolumeRecordedTime = sampleTimeNow
}

// Scheduled callbacks

def requestPowerSourceReport() {
    def cmds = []
    cmds += zigbee.readAttribute(0x0000, 0x0007)
    sendZigbeeCommands(cmds)
    runIn(prefPowerSourceSchedule*60, requestPowerSourceReport, [overwrite: true, misfire: "ignore"])
}

def requestBatteryAlarmReport() {
    def cmds = []
    cmds += zigbee.readAttribute(0x0001, 0x003E)
    sendZigbeeCommands(cmds)
    runIn(prefBatteryAlarmSchedule*3600, requestBatteryAlarmReport, [overwrite: true, misfire: "ignore"])
}

// Private methods

private void sendZigbeeCommands(cmds) {
    def hubAction = new hubitat.device.HubMultiAction(cmds, hubitat.device.Protocol.ZIGBEE)
    sendHubCommand(hubAction)
}

private getPowerSource(value) {
    source = constPowerSources[value]
    return source ? source : "unknown"
}

private getFlowRate(value) {
    if (value != null) {
        // Capability unit is LPM, device apparently reports in hundredths of ml
        // Round to two decimal places
        return Math.round(100 * Integer.parseInt(value, 16) / 100000) / 100
    }
}

private getBatteryVoltage(value) {
    if (value != null) {
        // Capability units are V, device reports in tenths of V
        return Integer.parseInt(value, 16) / 10
    }
}

private getBatteryLevel(value) {
    if (value != null) {
        // from the ZCL: 0x00 = 0%, 0x64 (100) = 50%, 0xC8 (200) = 100%, 0xFF (255) = invalid/unknown
        def battLevel = Integer.parseInt(value, 16)

        if (battLevel == 255)
            return "unknown"

        return Math.round(battLevel / 2)
    }
}

private getBatteryLevelFromVoltage() {
    def voltage = device.currentValue("voltage")
    if(voltage <= constBatteryVoltageMin)
        return 0

    output = 100 * (voltage - constBatteryVoltageMin) / (constBatteryVoltageMax - constBatteryVoltageMin) as int
    return output < 100 ? output : 100
}

private getVolume(value) {
    if (value != null) {
        // TODO : check volume factor
        def volume = new BigInteger(value,16)
        return volume / 1000 // capability is in L
    }
}

private getTemperature(value) {
    if (value != null) {
        // ZCL spec says temperature is in hundredths of C
        def celsius = Integer.parseInt(value, 16) / 100
        if (getTemperatureScale() == "C") {
            return celsius
        } else {
            return Math.round(celsiusToFahrenheit(celsius))
        }
    }
}

// Logging helpers

private logTrace(message) {
    // No trace facility.  Use debug.
    if (traceEnable) log.debug("${device} : ${message}")
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

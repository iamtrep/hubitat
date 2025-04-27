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
 * - battery volage as VoltageMeasurement capability OK, or move to custom batteryVoltage attribute ?
 *
 * v0.0.1 Initial version
 * v0.0.2 Added IAS Zone enrollment to enable leak detection
 *
 */

import groovy.transform.Field

@Field static final String version = "0.0.2"


metadata {
    definition(
        name: "Sinope Water Valve (VA422xZB)",
        namespace: "iamtrep",
        author: "pj",
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
        capability "WaterSensor"

        attribute "volume", "number"  // no standard capability for volume measurement
        attribute "batteryAlarm", "enum", ["clear", "detected"]

        // for development purposes - do not use
        attribute "rateFromVolume", "number"

        preferences {
            input(name: "prefPowerSourceSchedule", type: "number", title: "Power source poll rate (in minutes)", required: true, defaultValue: 5)
            input(name: "prefBatteryAlarmSchedule", type: "number", title: "Battery alarm state poll rate (in hours)", required: true, defaultValue: 1)
            input(name: "prefFlowSensorType", type: "enum", title: "Flow Sensor Configuration (FS422X)", options: ["off": "Disabled (default)", "3/4": "3/4 inch (FS4220)", "1": "1 inch (FS4221)"], defaultValue: "off", required: true, submitOnChange: true)
            if (prefFlowSensorType != "off") {
                input(name: "prefMinVolumeChange", type: "number", title: "Volume", description: "Minimum change (in mL) to trigger water volume auto reporting", defaultValue: 100, range: "0..1000")
                input(name: "prefMinRateChange", type: "number", title: "Flow Rate", description: "Minimum change (in mL/h) to trigger flow rate auto reporting", defaultValue: 1, range: "0..1000")
                input(name: "prefAbnormalFlowAction", type: "enum", title: "Abnormal Flow Action", options: ["off": "No action", "alert": "Send alert", "close": "Close valve and send alert"], defaultValue: "off", required: false)
                input(name: "prefAbnormalFlowDuration", type: "number", title: "Abnormal Flow Duration (s)", range: "900..86400", default: 3600)
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

// Battery pack is 4x Lithium AAA batteries - rated from 1.8V to 1.4V on small loads (https://data.energizer.com/pdfs/l92.pdf)
@Field static final float constBatteryVoltageMin = 4.0f * 1.4f
@Field static final float constBatteryVoltageMax = 4.0f * 1.8f
@Field static final Map constBatteryAlarmValues = [ "00000000": "clear", "0000000F": "detected"]  // TBC

@Field static final Map constValveValues =  [ "00": "closed", "01": "open" ]

@Field static final Map constWaterSensorValues =  [ "0030": "dry", "0031": "wet" ]

// See ZCL 3.2.2.2.8 - bit 7 (0x80) is set when battery backup is present.  Handling this the lazy way.
@Field static final Map constPowerSources = ["00": "unknown", "01": "mains", "02": "mains", "03": "battery", "04": "dc", "05": "emergency", "06": "emergency",
                                             "80": "unknown", "81": "mains", "82": "mains", "83": "battery", "84": "dc", "85": "emergency", "86": "emergency" ]

@Field static final Map constAbnormalFlowActions = [ "0000": "off", "0001": "alert", "0003": "close", "off": "0000", "alert": "0001", "close": "0003"]

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

    state.codeVersion = version
    state.debugMode = debugMode

    try
    {
        unschedule()
    }
    catch (e)
    {
        logError("unschedule() threw an exception ${e}")
    }

    def cmds = []

    // Configure device attribute self-reporting
    cmds += zigbee.configureReporting(0x0000, 0x0007, DataType.ENUM8, 0, 7200)               // power source
    cmds += zigbee.configureReporting(0x0001, 0x0020, DataType.UINT8, 300, 3600)             // battery voltage
    cmds += zigbee.configureReporting(0x0001, 0x0021, DataType.UINT8, 300, 3600)             // battery level (apparently not supported by device, always returns 0)
    cmds += zigbee.configureReporting(0x0001, 0x003E, DataType.BITMAP32, 0, 7200)            // battery alarm states (tbc)
    cmds += zigbee.configureReporting(0x0006, 0x0000, DataType.BOOLEAN, 0, 3600)             // valve state
    cmds += zigbee.configureReporting(0x0402, 0x0000, DataType.INT16, 0, 3600, 1)            // (device) temperature (in 1/100ths C)

    cmds += zigbee.configureReporting(0xFF01, 0x0200, DataType.BITMAP32, 3600, 7200, 1, [mfgCode: "0x119C"])      // status/alarm

    cmds += configureFlowSensor(prefFlowSensorType)

    sendZigbeeCommands(cmds)

    // Schedule refresh requests for power source and battery alarm, since the self-reporting appears not to work.
    runIn(prefPowerSourceSchedule*60, requestPowerSourceReport, [overwrite: true, misfire: "ignore"])
    runIn(prefBatteryAlarmSchedule*3600, requestBatteryAlarmReport, [overwrite: true, misfire: "ignore"])
}

def initialize() {
    logTrace("initialize()")

    // state.clear()
    state.switchTypeDigital = false
    state.remove("lastVolumeRecorded")     // built-in driver uses lastValue
    state.remove("lastVolumeRecordedTime") // built-in driver uses lastSample
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

    if (isFlowSensorEnabled()) {
        cmds += zigbee.readAttribute(0x0702, 0x0000) // total volume delivered in mL
        cmds += zigbee.readAttribute(0x0702, 0x0400) // flow rate in mL/h
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

// Device Event Parsing

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
        //logTrace("Unhandled ZDO command: cluster=${descMap.clusterId} command=${descMap.command} value=${descMap.value} data=${descMap.data}")
    } else if (descMap.profileId == "0104" && descMap.clusterId != null) {
        // ZigBee Home Automation (ZHA) global command
        //logTrace("Unhandled ZHA global command: cluster=${descMap.clusterId} command=${descMap.command} value=${descMap.value} data=${descMap.data}")
    } else if (description?.startsWith('enroll request')) {
        logDebug "Received enroll request"
        //def cmds = zigbee.enrollResponse() + zigbee.readAttribute(0x0500, 0x0000)
        //logDebug "enroll response: ${cmds}"
        //sendZigbeeCommands(cmds)
    } else if (description?.startsWith('zone status')  || description?.startsWith('zone report')) {
        logDebug "Zone status: $description"
        parseIASMessage(description)
    } else {
        logWarn("Unhandled unknown command ($description): cluster=${descMap.clusterId} command=${descMap.command} value=${descMap.value} data=${descMap.data}")
    }

    return result
}


private parseIASMessage(String description) {
    Map zs = zigbee.parseZoneStatusChange(description)
    logDebug("parseIASMessage zs = $zs")
    if (zs.alarm1Set) {
        sendEvent(name: "water", value: 'wet', descriptionText: "Flow sensor leak detection: detected")
    } else {
        sendEvent(name: "water", value: 'dry', descriptionText: "Flow sensor leak detection: clear")
    }
    if (zs.batterySet) {
        sendEvent(name: "batteryAlarm", value : 'detected', descriptionText: "Battery alarm state: detected")
    } else {
        sendEvent(name: "batteryAlarm", value : 'clear', descriptionText: "Battery alarm state: clear")
    }
}


private parseAttributeReport(descMap){
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
                    // Battery percentage remaining
                    // TODO - ignore for now, as the report is always zero.  Will get trace-logged at end of this function.
                    //map.name = "batteryAlt"
                    //map.value = getBatteryLevel(descMap.value)
                    //map.unit = "%"
                    //map.descriptionText = "Battery (alt) percentage remaining is ${map.value} ${map.unit}"
                    break

                case "003E":
                    // Alarm states reported as a 32-bit bitfield
                    // - 0x00000000 : battery is good.
                    // - 0x0000000F : reported when pulling one of the four batteries
                    // other states TBC (e.g. battery low)
                    map.name = "batteryAlarm"
                    map.value = constBatteryAlarmValues[descMap.value]
                    if (map.value == null) {
                        logDebug("Unknown battery alarm value ${descMap.value}")
                        map.value = Integer.parseInt(descMap.value, 16) > 0 ? "detected" : "clear"
                    }
                    map.descriptionText = "Battery alarm state is ${map.value}"
                    logTrace("Battery alarm raw attribute report is ${descMap.value}")
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
            switch (descMap.attrId) {
                case "0000":
                    // Current level
                    map.name = "level"
                    map.value = (descMap.value.toDouble() * 100.0 / 255.0).round()
                    map.unit = "%"
                    map.descriptionText = "Valve is ${map.value}% open"
                   break
                case "0011":
                    // "On" level (can be configured ?)
                    break
            }

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
            // Water leak detection.  The valve detects continuous flow over a (configurable) period of time as a leak.
            switch (descMap.attrId) {
                case "0000":
                    // IAS enroll response
                    def enrolled = descMap.value == "01" ? true : false
                    logDebug("IAS Zone cluster enrolled = $enrolled")
                    return null

                case "0002":
                    def status = descMap.value
                    if (status == "01") {
                        map.name = "water"
                        map.value = "wet"
                    } else if (status == "00") {
                        map.name = "water"
                        map.value = "dry"
                    }
                    break

                default:
                    break
            }
            break

        case "0702": // Metering cluster
            switch (descMap.attrId) {
                case "0000":
                    map.name = "volume"
                    map.value = getVolume(descMap.value)
                    map.unit = "L"
                    map.descriptionText = "Cumulative water volume delivered is ${map.value} ${map.unit}"
                    computeFlowRate(descMap.value)
                    break

                case "0400":
                    map.name = "rate"
                    map.value = getFlowRate(descMap.value)
                    if (map.value > 0) {
                        map.isStateChange = true  // force state change when there is flow, so RM rules are triggered, etc.
                    }
                    map.unit = "LPM"
                    map.descriptionText = "Water flow rate is ${map.value} ${map.unit}"
                    break

                default:
                    break
            }
            break

        case "0B05": // Diagnostics cluster
            break

        case "FF01": // Manufacturer-specific cluster
            switch (descMap.attrId) {
                case "0200": // status/alert
                case "0230": // alarm flow threshold
                case "0231": // alarm options
                case "0241": // valve countdown
                case "0250": // power source
                case "0251": // emergency power source
                case "0252": // abnormal flow duration
                case "0253": // abnormal flow action
                    logDebug("Manufacturer specific attribute report: ${descMap}")
                    break

                case "0240":
                    logDebug("Pipe diameter attribute - ${descMap}")
                    break

                default:
                    logDebug("Unknown manufacturer specific attribute report: ${descMap}")
                    break
            }
            return null
            break

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

def computeFlowRate(volumeAttr) {
    def volumeDiff = 0
    def sampleTimeDiff = 0
    def sampleTimeNow = new Date().time  // TODO: find out if there a way to get a timestamp from the zigbee attribute report

    def currentVolume = new BigInteger(volumeAttr,16)
    if (state.lastVolumeRecorded) {
        volumeDiff = currentVolume - state.lastVolumeRecorded
    }
    state.volumeSinceLastEvent = volumeDiff  // keep track for now.

    // Compute flow
    def computedFlowRate = 0f
    if (volumeDiff > constMinVolumeDiff) {
        if (state.lastVolumeRecordedTime) {
            sampleTimeDiff = (sampleTimeNow - state.lastVolumeRecordedTime)
        }

        if (sampleTimeDiff > constMinSampleTimeDiff) {
            // We have a volume difference in mL, and a time difference in ms
            // We want flow rate in LPM, two decimal places (probably should be 1 decimal place, tbd)
            computedFlowRate = Math.round( 100 * (volumeDiff * 60f) / sampleTimeDiff )  / 100
        } else {
            logDebug("positive but instantaneous volume change ?!?")
            return
        }
    }

    def eventDescriptionText = "Water flow rate avg since last volume event is ${computedFlowRate} LPM"
    sendEvent(name: "rateFromVolume", value: computedFlowRate, unit: "LPM", descriptionText: eventDescriptionText)
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
        // Capability unit is LPM, device reports in ml/hour
        // Convert and round to two decimal places
        return Math.round(100 * Integer.parseInt(value, 16) / (60 * 1000)) / 100
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

private isFlowSensorEnabled() {
    return prefFlowSensorType != "off"
}


// Flow sensor configuration as byte arrays.
//
// Encoding 0x48 = Array
//
// 200C00C21100008877000001000000
// 20 = array element type uint8
//   0C00 be = 00 0C le = length of array is 12
//       C2110000 be = 000011C2 le = 4546
//               88770000 be = 00007788 le = 30600
//                       01000000 be = 00000001 le = 1
//
// 200C009F2600004C55010001000000
// 20 = array element type uint8
//   0C00 be = 00 0C le = length of array is 12
//       9F260000 be = 0000269F le = 9887
//               4C550100 be = 1050C504 le = 87372
//                       01000000 be = 00000001 le = 1
//
@Field static final Map constFlowSensorConfigs = ["3/4" : "200C00C21100008877000001000000", //  [ "multiplier": 4546, "offset": 30600, "divisor" : 1 ]
                                                    "1" : "200C009F2600004C55010001000000", //  [ "multiplier": 9887, "offset": 87372, "divisor" : 1 ]
                                                  "off" : "200C00000000000000000001000000"] //  [ "multiplier": 0,    "offset": 0,     "divisor" : 1 ]


private configureFlowSensor(String flowSensorDiameter) {
    def cmds = []

    // Support for FS422x (if attached)
    def flowSensorConfigMsg = constFlowSensorConfigs[flowSensorDiameter]
    if (flowSensorConfigMsg == null) {
        logError("Invalid Flow Sensor selection - ${flowSensorDiameter}")
        return
    }

    def testCmd = zigbee.writeAttribute(0xFF01, 0x0240, DataType.ARRAY, flowSensorConfigMsg, [mfgCode: "0x119C"])
    //logDebug("Flow Sensor diameter config - ${testCmd}")
    cmds += testCmd

    if (flowSensorDiameter != "off") {
        // The built-in Hubitat driver appears to compute flow from volume (cluster 0x0702, attribute 0x0000)
        // This driver acquires both directly from the device.
        cmds += zigbee.configureReporting(0x0702, 0x0000, DataType.UINT48, 0, 1800, (int)prefMinVolumeChange) // volume delivered (in ml)
        cmds += zigbee.configureReporting(0x0702, 0x0400, DataType.INT24, 5, 300, (int)prefMinRateChange)     // flow rate in mL/h (see InstantaneousDemand - ZCL 10.4.2.2.5)

        // Abnormal flow detection setup
        cmds += zigbee.writeAttribute(0xFF01, 0x0252, DataType.UINT32, (int)prefAbnormalFlowDuration, [mfgCode: "0x119C"])
        cmds += zigbee.writeAttribute(0xFF01, 0x0253, DataType.BITMAP16, zigbee.swapOctets(constAbnormalFlowActions[prefAbnormalFlowAction]), [mfgCode: "0x119C"])
        //cmds += zigbee.readAttribute(0xFF01, 0x0200, [mfgCode: "0x119C"])
        //cmds += zigbee.readAttribute(0xFF01, 0x0230, [mfgCode: "0x119C"])
        //cmds += zigbee.readAttribute(0xFF01, 0x0231, [mfgCode: "0x119C"])

        // IAS zone setup for water leak detection
        cmds += zigbee.enrollResponse()
        //cmds += zigbee.configureReporting(0x0500, 0x0002, DataType.BITMAP16, 0, 10800, 0) // zone status every 3 hours
        cmds += zigbee.readAttribute(0x0500, 0x0000)
    } else {
        // turn off reporting?
    }

    return cmds
}

// Reverses order of bytes in hex string
private reverseHexString(hexString) {
	def reversed = ""
	for (int i = hexString.length(); i > 0; i -= 2) {
		reversed += hexString.substring(i - 2, i )
	}
	return reversed
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

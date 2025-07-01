/**
 *
 *  IKEA Window Blind driver for Hubitat Elevation
 *
 *  Inspired from driver found here:
 *    https://github.com/a4refillpad/hubitat-IKEA-window-blinds/blob/master/IKEA-window-blind-driver-code
 *    code copyright Wayne Man
 *
 *	Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *	in compliance with the License. You may obtain a copy of the License at:
 *
 *		http://www.apache.org/licenses/LICENSE-2.0
 *
 *	Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *	on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *	for the specific language governing permissions and limitations under the License.
 *
 */

import groovy.transform.CompileStatic
import groovy.transform.Field
import hubitat.zigbee.zcl.DataType
import com.hubitat.hub.domain.Event

@Field static final String version = "0.0.1"

metadata {
    definition(
        name: "IKEA Window Blinds",
        namespace: "iamtrep",
        author: "pj",
        importUrl: "https://raw.githubusercontent.com/iamtrep/hubitat/main/drivers/IKEA-Blinds.groovy"
    ) {
        capability "Actuator"
        capability "Battery"
        capability "Configuration"
        capability "Refresh"
	    capability "Switch"
	    capability "Switch Level"
        capability "Window Shade"

        command "fullyOpen" // see openLimit preference
        command "fullyClose" // see closeLimit preference

        command 'updateFirmware'

        fingerprint profileId:'0104', endpointId:'01', inClusters: "0000,0001,0003,0004,0005,0020,0102", outClusters: "0019",
            manufacturer: "IKEA of Sweden", model: "FYRTUR block-out roller blind", deviceJoinName: "IKEA FYRTUR Roller Blind E1757", controllerType:'ZGB'

        fingerprint profileId: "0104", inClusters: "0000,0001,0003,0004,0005,0020,0102", outClusters: "0019",
            manufacturer: "IKEA of Sweden", model: "KADRILJ roller blind", deviceJoinName: "IKEA KADRILJ Roller Blind E1926", controllerType:'ZGB'

        fingerprint profileId:'0104', endpointId:'01', inClusters: "0000,0001,0003,0004,0005,0020,0102,1000,FC7C", outClusters: "0019,1000",
            manufacturer: "IKEA of Sweden", model: "PRAKTLYSING cellular blind", deviceJoinName: "IKEA PRAKTLYSING Cellular Shade E2102", controllerType:'ZGB'

        fingerprint profileId:'0104', endpointId:'01', inClusters: "0000,0001,0003,0004,0005,0020,0102", outClusters: "0019",
            manufacturer: "IKEA of Sweden", model: "TREDANSEN block-out cellul blind", deviceJoinName: "IKEA TREDANSEN Cellular Shade E2103", controllerType:'ZGB'

    }

    preferences {
        input name: "openThreshold", type: "number", defaultValue: 97, range: "95..100", title: "Shade Open Threshold",
            description: "Threshold beyond which shade is considered open (%)"
        input name: "openLimit", type: "number", defaultValue: 100, range: "0..100", title: "Max open level",
            description: "Max percentage open when Open function is called\n(delete or set value to 100 to disable)"
        input name: "closedThreshold", type: "number", defaultValue: 3, range: "0..5", title: "Shade Closed Threshold",
            description: "Threshold beyond which shade is considered closed (%)"
        input name: "closeLimit", type: "number", defaultValue: 0, range: "0..100", title: "Min close level",
            description: "Min percentage closed when Close function is called\n(delete or set value to 0 to disable)"

        input name: "txtEnable", type: "bool", title: "Enable descriptionText logging", defaultValue: false
        input name: "debugEnable", type: "bool", title: "Enable debug logging info", defaultValue: false, required: true, submitOnChange: true
        if (debugEnable) {
            input name: "traceEnable", type: "bool", title: "Enable trace logging info (for development purposes)", defaultValue: false
       }
    }

}

@Field static final Integer WINDOW_COVERING_CLUSTER = 0x0102
@Field static final Integer LIFT_POSITION_ATTR = 0x0008
@Field static final Integer CONFIG_STATUS_ATTR = 0x0007
@Field static final Integer WC_OPEN_COMMAND = 0x00
@Field static final Integer WC_CLOSE_COMMAND = 0x01
@Field static final Integer WC_STOP_COMMAND = 0x02
@Field static final Integer WC_SET_POSITION_COMMAND = 0x05


@Field static final Integer POWER_CONFIG_CLUSTER = 0x0001
@Field static final Integer BATTERY_PERCENTAGE_ATTR = 0x0021

// capabilities

List<String> configure() {
    state.codeVersion = version

    state.batteryDivisor = 1.0
    if (getDataValue("softwareBuild") > "23079631") {
        state.batteryDivisor = 2.0
    }

    logDebug "Configuring Reporting and Bindings."

    List<String> cmds = []
    cmds += zigbee.configureReporting(POWER_CONFIG_CLUSTER, BATTERY_PERCENTAGE_ATTR, DataType.UINT8, 600, 21600, 1) // battery level
    cmds += zigbee.configureReporting(WINDOW_COVERING_CLUSTER, LIFT_POSITION_ATTR, DataType.UINT8, 2, 600, 1)       // window covering lift position
    cmds += zigbee.readAttribute(WINDOW_COVERING_CLUSTER, CONFIG_STATUS_ATTR)                                       // window covering config/status

    return refresh() + cmds
}

List<String> refresh() {
    logDebug "refresh()"

    List<String> cmds = []
    cmds += zigbee.readAttribute(POWER_CONFIG_CLUSTER, BATTERY_PERCENTAGE_ATTR) // battery level
    cmds += zigbee.readAttribute(WINDOW_COVERING_CLUSTER, LIFT_POSITION_ATTR) // window covering lift position

    return cmds
}

void updated() {
	unschedule()
	if (debugEable || traceEnable) runIn(1800,"logsOff")
}

List<String> open() {
    logDebug "open()"
    if (openLimit < 100) {
        return setLevel(openLimit)
    }

    return fullyOpen()
}

List<String> fullyOpen() {
    logDebug "fullyOpen()"
    return zigbee.command(WINDOW_COVERING_CLUSTER, WC_OPEN_COMMAND)
}

List<String> on() {
    return open()
}

List<String> close() {
    logDebug "close()"
    if (closeLimit > 0) {
        return setLevel(closeLimit)
    }
    return fullyClose()
}

List<String> fullyClose() {
    logDebug "fullyClose()"
    return zigbee.command(WINDOW_COVERING_CLUSTER, WC_CLOSE_COMMAND)
}

List<String> off() {
    return close()
}

List<String> setLevel(BigDecimal level, Integer rate = 0xFFFF) {
    return setLevel(level as Integer, rate)
}

List<String> setLevel(Integer level, Integer rate = 0xFFFF) {
    logTrace "setLevel(${level})"
    return zigbee.command(WINDOW_COVERING_CLUSTER, WC_SET_POSITION_COMMAND, zigbee.convertToHexString(100 - (level), 2))
}

List<String> setPosition(BigDecimal value){
	return setLevel(value)
}

List<String> setPosition(Integer value){
	return setLevel(value)
}

List<String> startPositionChange(String direction) {
    logTrace "startPositionChange(${direction})"
    switch (direction) {
        case "open":
            return open()

        case "close":
            return close()

        default:
            logError "invalid position change direction ${direction}"
            break
    }
}

List<String> stopPositionChange() {
    logDebug "stopPositionChange()"
    return zigbee.command(WINDOW_COVERING_CLUSTER, WC_STOP_COMMAND)
}

List<String> setTiltLevel(BigDecimal tilt) {
    logWarn("setTiltLevel(${tilt}) unsupported on this device")
}

List<String> updateFirmware() {
    logInfo 'Looking for firmware updates ...'
    logWarn '[IMPORTANT] Click the "Update Firmware" button immediately after pushing any button on the device in order to first wake it up!'
    return zigbee.updateFirmware()
}

// device message parsing

List parse(String description) {
    state.lastCheckin = now()

    Map descMap = zigbee.parseDescriptionAsMap(description)
    logTrace("parse() - description = ${descMap}")

    List result = []

    if (descMap.attrId != null) {
        // device attribute report
        result += parseAttributeReport(descMap)
        if (descMap.additionalAttrs) {
            Map mapAdditionnalAttrs = descMap.additionalAttrs
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

private Map parseAttributeReport(Map descMap){
    Map map = [: ]

    // Main switch over all available cluster IDs
    //
    // fingerprint : inClusters: "0000,0001,0003,0004,0005,0020,0102,1000,FC7C"
    //
    switch (descMap.cluster) {
        case "0000":  // Basic cluster
            break

        case "0001": // Power Configuration cluster
            map.name = "battery"
            map.value = roundToDecimalPlaces(Integer.parseInt(descMap.value, 16) / state.batteryDivisor, 1)
            map.unit = "%"
            map.descriptionText = "battery is ${map.value}${map.unit}"
            break

        case "0003": // Identify cluster
        case "0004": // Groups cluster
        case "0005": // Scenes cluster
            break

        case "0020": // Poll Control cluster
            break

        case "0102": // Window Covering cluster
            switch (descMap.attrId) {
                case "0007":
                    handleConfigStatus(descMap)
                    return null

                case "0008":
                    handleLevelEvent(descMap)
                    return null

                default:
                    break
            }
            break

        case "1000": // Touchlink cluster
        case "FC7C": // Manufacturer-specific cluster
        default:
            break
    }

    Map result = null

    if (map) {
        if (map.descriptionText) logInfo("${map.descriptionText}")
        result = createEvent(map)
    } else {
        logDebug("Unhandled attribute report - cluster ${descMap.cluster} attribute ${descMap.attrId} value ${descMap.value}")
    }

    return result
}

private void handleLevelEvent(Map descMap) {
    int currentLevel = 100 - zigbee.convertHexToInt(descMap.value)
    int lastLevel = device.currentValue("level") as int

    logDebug "levelEventHandle - currentLevel: ${currentLevel} lastLevel: ${lastLevel}"

    if (lastLevel == "undefined" || lastLevel == null || currentLevel == lastLevel) {
        runIn(3, "updateFinalState", [overwrite: true])
        return
    }

    updateDeviceAttribute(name: "level", value: currentLevel)
    updateDeviceAttribute(name: "position", value: currentLevel)

    if (currentLevel > openThreshold) {
        updateDeviceAttribute(name: "windowShade", value: "open")
        updateDeviceAttribute(name: "switch", value: "on")
    } else if (currentLevel < closedThreshold) {
        updateDeviceAttribute(name: "windowShade", value: "closed")
        updateDeviceAttribute(name: "switch", value: "off")
    } else {
        String direction = (lastLevel < currentLevel) ? "opening" : "closing"
        updateDeviceAttribute(name: "windowShade", value: direction)
    }

    logTrace "newlevel: ${currentLevel} currentlevel: ${currentLevel} lastlevel: ${lastLevel}"
    runIn(5, "refresh")
}

private void updateFinalState() {
    int level = device.currentValue("level") as int
    logDebug "updateFinalState: ${level}"

    // windowShade - ENUM ["opening", "partially open", "closed", "open", "closing", "unknown"]

    if (level == "unknown") {
        // TODO
        logWarn "Shade level unknown"
    } else if (level > openThreshold) {
        // open
        updateDeviceAttribute(name: "windowShade", value: "open")
        updateDeviceAttribute(name: "switch", value: "on")
    } else if (level < closedThreshold) {
        // closed
        updateDeviceAttribute(name: "windowShade", value: "closed")
        updateDeviceAttribute(name: "switch", value: "off")
    } else {
        // partially open
        updateDeviceAttribute(name: "windowShade", value: "partially open")
        updateDeviceAttribute(name: "switch", value: "off")
    }
}

private void updateDeviceAttribute(Map evt) {
    if (evt.descriptionText == null) {
        String descText = "${evt.name} is ${evt.value}"
        if (evt.unit != null) descText += "${evt.unit}"
        evt.descriptionText = descText
    }
    sendEvent(evt)
    logInfo(evt.descriptionText)
}

private void handleConfigStatus(Map descMap) {
    if (descMap.value) {
        Integer configStatus = Integer.parseInt(descMap.value, 16)
        logDebug "ConfigStatus: 0x${descMap.value} (${configStatus})"

        // Parse common ConfigStatus bits (vendor-specific implementation may vary)
        // Bit 0: Operational (0=Not Operational, 1=Operational)
        Boolean operational = (configStatus & 0x01) != 0

        // Bit 1: OnLine (0=Not Online, 1=Online)
        Boolean online = (configStatus & 0x02) != 0

        // Bit 2: Commands Reversed (0=Normal, 1=Reversed)
        Boolean commandsReversed = (configStatus & 0x04) != 0

        // Bit 3: Lift control is closed loop (0=Open Loop, 1=Closed Loop)
        Boolean liftClosedLoop = (configStatus & 0x08) != 0

        // Bit 4: Tilt control is closed loop (0=Open Loop, 1=Closed Loop)
        Boolean tiltClosedLoop = (configStatus & 0x10) != 0

        // Bit 5: Lift encoder controlled (0=Timer, 1=Encoder)
        Boolean liftEncoderControlled = (configStatus & 0x20) != 0

        // Bit 6: Tilt encoder controlled (0=Timer, 1=Encoder)
        Boolean tiltEncoderControlled = (configStatus & 0x40) != 0

        logDebug "ConfigStatus decoded - Operational: ${operational}, Online: ${online}, CommandsReversed: ${commandsReversed}, LiftClosedLoop: ${liftClosedLoop}"

        // Update shade state based on operational status
        if (!operational) {
            //updateDeviceAttribute(name: "shadeState", value: "unknown")
            logWarn "Shade reports as not operational"
        }

        device.updateDataValue("operational", operational.toString())
        device.updateDataValue("commandsReversed", commandsReversed.toString())
        device.updateDataValue("liftClosedLoop", liftClosedLoop.toString())
    }
}

@CompileStatic
private double roundToDecimalPlaces(double decimalNumber, int decimalPlaces = 2) {
    double scale = Math.pow(10, decimalPlaces)
    return (Math.round(decimalNumber * scale) as double) / scale
}

// Logging helpers

void logsOff(){
	logWarn "debug logging disabled..."
	device.updateSetting("debugEnable",[value:"false",type:"bool"])
	device.updateSetting("traceEnable",[value:"false",type:"bool"])
}

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

 ZCL reference info for clusters used by this device driver

 "0001": // Power Config

 Id Name Type Range Acc Def M/O
 0x0020 BatteryVoltage uint8 0x00 to 0xff R non O
 0x0021 BatteryPercentageRemaining uint8 0x00 to 0xff RP 0 O

 "0020": // Poll Control cluster

 Id Name Type Range Acc Default M/O
 0x0000 Check-inInterval uint32 0x0 to 0x6E0000 RW 0x3840 (1 hr.) M
 0x0001 LongPoll Interval uint32 0x04 to 0x6E0000 R 0x14 (5 sec) M
 0x0002 ShortPollInterval uint16 0x01 to 0xffff R 0x02 (2 qs) M
 0x0003 FastPollTimeout uint16 0x01 to 0xffff RW 0x28 (10 sec.) M
 0x0004 Check-inIntervalMin uint32 - R 0 O
 0x0005 LongPollIntervalMin uint32 - R 0 O
 0x0006 FastPollTimeoutMax uint16 - R 0 O

 "0102": // Window Covering cluster

 Id Name Type Range Acc Default M/O
 0x0000 WindowCoveringType enum8 desc R 0 M
 0x0001 PhysicalClosedLimit – Lift uint16 0x0000 – 0xffff R 0 O
 0x0002 PhysicalClosedLimit – Tilt uint16 0x0000 – 0xffff R 0 O
 0x0003 CurrentPosition – Lift uint16 0x0000 – 0xffff R 0 O
 0x0004 Current Position – Tilt uint16 0x0000 – 0xffff R 0 O
 0x0005 Number of Actuations – Lift uint16 0x0000 – 0xffff R 0 O
 0x0006 Number of Actuations – Tilt uint16 0x0000 – 0xffff R 0 O
 0x0007 Config/Status map8 desc R desc M
 0x0008 Current Position Lift Percentage uint8 0-100 RSP FF136 M*
 0x0009 Current Position Tilt Percentage uint8 0-100 RSP FF M*

 */

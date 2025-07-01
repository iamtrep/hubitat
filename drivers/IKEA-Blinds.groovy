/**
 *
 *  IKEA Window Blinds driver
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
 *
 *
 */

import groovy.transform.Field
import hubitat.zigbee.zcl.DataType

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
        input name: "openLimit", type: "number", defaultValue: 0, range: "0..100", title: "Max open level",
            description: "Max percentage open when Open function is called\n" +
            "(delete or set value to 0 to disable this)"
        input name: "closeLimit", type: "number", defaultValue: 100, range: "0..100", title: "Max close level",
            description: "Max percentage closed when Close function is called\n" +
            "(delete or set value to 100 to disable this)"

        input name: "txtEnable", type: "bool", title: "Enable descriptionText logging", defaultValue: false
        input name: "debugEnable", type: "bool", title: "Enable debug logging info", defaultValue: false, required: true, submitOnChange: true
        if (debugEnable) {
            input name: "traceEnable", type: "bool", title: "Enable trace logging info (for development purposes)", defaultValue: false
       }
    }

}

// capabilities

def configure() {
    state.codeVersion = version

    logDebug "Configuring Reporting and Bindings."

    List<String> cmds = []
    cmds += zigbee.configureReporting(0x0001, 0x0021, DataType.UINT8, 600, 21600, 1) // battery level
    cmds += zigbee.configureReporting(0x0102, 0x0008, DataType.UINT8, 2, 600, 1)     // window covering lift position
    cmds += zigbee.readAttribute(0x0102, 0x0007)                                     // window covering config/status

    return refresh() + cmds
}

def refresh() {
    logDebug "refresh()"

    def cmds = []
    cmds += zigbee.readAttribute(0x0001, 0x0021) // battery level
    cmds += zigbee.readAttribute(0x0102, 0x0008) // window covering lift position

    return cmds
}

def updated() {
	unschedule()
	if (debugEable || traceEnable) runIn(1800,logsOff)
}

def close() {
    logDebug "close()"
    if (closeLimit < 100) {
        setLevel(closeLimit)
    } else {
        fullyClose()
    }
}

def fullyClose() {
    logDebug "fullyClose()"
    zigbee.command(0x0102, 0x01)
}

def open() {
    logDebug "open()"
    if (openLimit) {
        setLevel(openLimit)
    } else {
        fullyOpen()
    }
}

def fullyOpen() {
    logDebug "fullyOpen()"
    zigbee.command(0x0102, 0x00)

}

def on() {
    open()
}

def off() {
    close()
}

def setLevel(data, rate = null) {
    logDebug "setLevel()"
    data = data.toInteger()
    if (data == 100) {
        open()
    } else {
        def cmd
        cmd = zigbee.command(0x0102, 0x05, zigbee.convertToHexString(100 - data, 2))
        return cmd
    }
}

def setPosition(value){
	setLevel(value)
}

def startPositionChange(direction) {
    // direction required (ENUM) - Direction for position change request ["open", "close"]
    logDebug "startPositionChange()"
    switch (direction) {
        case "open":
            open()
            break
        case "closed":
            close()
            break
        default:
            logError "invalide position change direction ${direction}"
            break
    }
}

def stopPositionChange() {
    logDebug "stopPositionChange()"
    zigbee.command(0x0102, 0x02)
}

def updateFirmware() {
    logInfo 'Looking for firmware updates ...'
    logWarn '[IMPORTANT] Click the "Update Firmware" button immediately after pushing any button on the device in order to first wake it up!'
    return zigbee.updateFirmware()
}


// device message parsing


def parse(String description) {
    state.lastCheckin = now()

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

private parseAttributeReport(descMap){
    def map = [: ]

    // Main switch over all available cluster IDs
    //
    // fingerprint : inClusters: "0000,0001,0003,0004,0005,0020,0102,1000,FC7C"
    //
    switch (descMap.cluster) {
        case "0000":  // Basic cluster
            break

        case "0001": // Power Configuration cluster
            logTrace "attr: ${descMap?.attrInt}, value: ${descMap?.value}, descValue: ${Integer.parseInt(descMap.value, 16)}"
            map.name = "battery"
            map.value = Integer.parseInt(descMap.value, 16)
            map.unit = "%"
            map.descriptionText = "battery is ${map.value}${map.unit}"
            break

        case "0003": // Identify cluster
        case "0004": // Groups cluster
        case "0005": // Scenes cluster
            break

        case "0020": // Poll Control cluster
            /*
         0x0000 Check-inInterval uint32 0x0 to 0x6E0000 RW 0x3840 (1 hr.) M
         0x0001 LongPoll Interval uint32 0x04 to 0x6E0000 R 0x14 (5 sec) M
         0x0002 ShortPollInterval uint16 0x01 to 0xffff R 0x02 (2 qs) M
         0x0003 FastPollTimeout uint16 0x01 to 0xffff RW 0x28 (10 sec.) M
         0x0004 Check-inIntervalMin uint32 - R 0 O
         0x0005 LongPollIntervalMin uint32 - R 0 O
         0x0006 FastPollTimeoutMax uint16 - R 0 O
         */
            break

        case "0102": // Window Covering cluster
            /*
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
            switch (descMap.attrId) {
                case "0007":
                    handleConfigStatus(descMap)
                    return

                case "0008":
                    // TODO - rewrite
                    handleLevelEvent(descMap)
                    return

                default:
                    break
            }
            break

        case "1000": // Touchlink cluster
        case "FC7C": // Manufacturer-specific cluster
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


// TODO - rewrite
def handleLevelEvent(descMap) {
    def currentLevel = 100 - zigbee.convertHexToInt(descMap.value)
    def lastLevel = device.currentValue("level")

    logDebug "levelEventHandle - currentLevel: ${currentLevel} lastLevel: ${lastLevel}"

    if (lastLevel == "undefined" || currentLevel == lastLevel) { //Ignore invalid reports
        logDebug "undefined lastLevel"
        runIn(3, "updateFinalState", [overwrite:true])
    } else {
        sendEvent(name: "level", value: currentLevel)
        sendEvent(name: "position", value: currentLevel)
        if (currentLevel < 3 || currentLevel > 97) {
            sendEvent(name: "windowShade", value: currentLevel == 0 ? "closed" : "open")
            sendEvent(name: "switch", value: level == 0 ? "off" : "on", displayed: false)
        } else {
            if (lastLevel < currentLevel) {
                sendEvent([name:"windowShade", value: "opening"])
            } else if (lastLevel > currentLevel) {
                sendEvent([name:"windowShade", value: "closing"])
            }
        }
    }

    if (lastLevel != currentLevel) {
        logDebug "newlevel: ${newLevel} currentlevel: ${currentLevel} lastlevel: ${lastLevel}"
        // why is refresh() needed if we have configureReporting on that attr?
        runIn(5, refresh)
    }
}


// TODO - rewrite
def updateFinalState() {
    def level = device.currentValue("level")
    logDebug "updateFinalState: ${level}"
    sendEvent(name: "windowShade", value: level == 0 ? "closed" : "open")
    sendEvent(name: "switch", value: level == 0 ? "off" : "on", displayed: false)
}


def handleConfigStatus(Map descMap) {
    if (descMap.value) {
        Integer configStatus = Integer.parseInt(descMap.value, 16)
        logInfo "ConfigStatus: 0x${descMap.value} (${configStatus})"

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
            //sendEvent(name: "shadeState", value: "unknown")
            logWarn "Shade reports as not operational"
        }

        // You can store these flags as device data for use in other methods
        device.updateDataValue("operational", operational.toString())
        device.updateDataValue("commandsReversed", commandsReversed.toString())
        device.updateDataValue("liftClosedLoop", liftClosedLoop.toString())
    }
}


// Logging helpers

private void logsOff(){
	logWarn "debug logging disabled..."
	device.updateSetting("debugEnable",[value:"false",type:"bool"])
	device.updateSetting("traceEnable",[value:"false",type:"bool"])
}

private void logTrace(message) {
    if (traceEnable) log.trace("${device} : ${message}")
}

private void logDebug(message) {
    if (debugEnable) log.debug("${device} : ${message}")
}

private void logInfo(message) {
    if (txtEnable) log.info("${device} : ${message}")
}

private void logWarn(message) {
    log.warn("${device} : ${message}")
}

private void logError(message) {
    log.error("${device} : ${message}")
}

/**
 *  Xfinity Contact Sensor driver
 *
 *  Some code inspired from community driver found here:
 *    https://raw.githubusercontent.com/goug76/Home-Automation/refs/heads/master/Hubitat/Drivers/Xfinity%20ZigBee%20Contact%20Sensor.src/Xfinity%20ZigBee%20Contact%20Sensor.groovy
 *    code copyright John Goughenour
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
import hubitat.device.Protocol
import hubitat.zigbee.zcl.DataType
import hubitat.zigbee.clusters.iaszone.ZoneStatus
import com.hubitat.hub.domain.Event

@Field static final String version = "0.1.0"

metadata {
	definition (
        name: "Universal Electronics / Visonic / Xfinity Contact Sensor",
        namespace: "iamtrep",
        author: "pj",
        importUrl: "https://raw.githubusercontent.com/iamtrep/hubitat/main/drivers/XfinityContactSensor.groovy"
    ) {
        capability "Configuration"
        capability "ContactSensor"
        capability "Battery"
        capability "Sensor"
        capability "TamperAlert"
        capability "TemperatureMeasurement"
        capability "Refresh"

        attribute "lowBattery", "enum", ["true","false"]

        command "setBatteryReplacementDate", [[name: "Date Changed", type: "DATE", description: "Enter the date the battery was last changed. If blank will use current date."]]

        fingerprint profileId:"0104", endpointId:"01", inClusters:"0000,0001,0003,0020,0402,0500,0B05,FD50", outClusters:"0019",
            model:"LDHD2AZW", manufacturer:"Leedarson"
        fingerprint profileId:"0104", endpointId:"01", inClusters:"0000,0001,0003,0020,0402,0500,0B05", outClusters:"0019",
            model:"URC4460BC0-X-R", manufacturer:"Universal Electronics Inc"
        fingerprint profileId:"0104", endpointId:"01", inClusters:"0000,0001,0003,0020,0402,0500,0B05", outClusters:"0019",
            model:"MCT-350 SMA", manufacturer:"Visonic"
	}

    preferences {
        input(name: "batteryInterval", type: "number", title: "<b>Battery Reporting Interval</b>", defaultValue: 12,
              description: "Set battery reporting interval by this many <b>hours</b>.</br>Default: 12 hours", required: false)
        input(name: "tempInterval", type: "number", title: "<b>Temperature Reporting Interval</b>", defaultValue: 720,
              description: "Set temperature reporting interval by this many <b>minutes</b>. </br>Default: 720 (12 hours)", required: false)
        input name: "tempOffset", title: "<b>Temperature Calibration</b>", type: "number", range: "-128..127", defaultValue: 0, required: true,
            description: "Adjust temperature by this many degrees.</br>Range: -128 thru 127</br>Default: 0"
        input name: "txtEnable", type: "bool", title: "Enable descriptionText logging", defaultValue: false
        input name: "debugEnable", type: "bool", title: "Enable debug logging info", defaultValue: false, required: true, submitOnChange: true
        if (debugEnable) {
            input name: "traceEnable", type: "bool", title: "Enable trace logging info (for development purposes)", defaultValue: false
       }
  }
}

@Field static Double constMinBatteryVoltage = 2.3
@Field static Double constMaxBatteryVoltage = 3.0
@Field static Integer constDefaultDelay = 333


void installed(){
	logDebug "installed()"
    configure()
}

void updated(){
	logDebug "updated()"
    configure()
}

void uninstalled() {
    logDebug "uninstalled()"
}

void deviceTypeUpdated() {
    logDebug "driver change detected"
    configure()
}

List<String> refresh() {
	logDebug "refresh()"
    List<String> cmds = []
    cmds += zigbee.readAttribute(0x0402, 0x0000, [:], constDefaultDelay) // temperature
    cmds += zigbee.readAttribute(zigbee.POWER_CONFIGURATION_CLUSTER, 0x0020, [:], constDefaultDelay)
    return cmds
}

void configure() {
	logDebug "configure()"

    state.clear()
    state.lastRx = 0
    state.codeVersion = version

    int reportInterval = (batteryInterval == null ? 12 : batteryInterval).toInteger() * 60 * 60
    List<String> cmds = []
    cmds += "zdo bind 0x${device.deviceNetworkId} 1 1 0x0500 {${device.zigbeeId}} {}"  // IAS Zone
    cmds += "delay $constDefaultDelay"
    cmds += zigbee.enrollResponse(1200) // Enroll in IAS Zone
    cmds += zigbee.configureReporting(zigbee.POWER_CONFIGURATION_CLUSTER, 0x0020, DataType.UINT8, 0, reportInterval, 1, [:], constDefaultDelay) //Battery Voltage Reporting
    cmds += zigbee.temperatureConfig(3600,((tempInterval != null ? tempInterval : 12).toInteger() * 60)) // Temperature Reporting
    cmds += refresh()

    sendZigbeeCommands(cmds)
}

void setBatteryReplacementDate(Date date) {
    if (date == null) date = new Date()
    String dateStr = date.format('yyyy-MM-dd')
	device.updateDataValue("batteryReplacementDate", dateStr)
    logDebug "setting Battery Last Replaced Date to $dateStr"
}

List parse(String description) {
    state.lastRx = now()
    logTrace "parsing message: ${description}"

    // Auto-Configure device: configure() was not called for this driver version
    if (state.codeVersion != version) {
        state.codeVersion = version
        runInMillis 1500, 'autoConfigure'
    }

    Map descMap = zigbee.parseDescriptionAsMap(description)
    logTrace "Receiving Zigbee message️ ⬅️ device: ${descMap}"

    List result = []

    if (descMap.attrId != null) {
        // device attribute report
        //result += parseAttributeReport(descMap)
        //parseAttributeReport(descMap)
        parseAttributeReport(description)
        //if (descMap.additionalAttrs) {
        //    Map mapAdditionnalAttrs = descMap.additionalAttrs
        //    mapAdditionnalAttrs.each{add ->
        //        add.cluster = descMap.cluster
        //        result += parseAttributeReport(add)
        //        //parseAttributeReport(add)
        //    }
        //}
    } else if (descMap.profileId == "0000") {
        // ZigBee Device Object (ZDO) command
        logTrace("Unhandled ZDO command: cluster=${descMap.clusterId} command=${descMap.command} value=${descMap.value} data=${descMap.data}")
    } else if (descMap.profileId == "0104" && descMap.clusterId != null) {
        // ZigBee Home Automation (ZHA) global command
        logTrace("Unhandled ZHA global command: cluster=${descMap.clusterId} command=${descMap.command} value=${descMap.value} data=${descMap.data}")
    } else if (description?.startsWith('enroll request')) {
        logDebug "Received enroll request"
        List<String> cmds = []
        cmds += zigbee.enrollResponse(1200)
        sendZigbeeCommands(cmds)
    } else if (description?.startsWith('zone status')  || description?.startsWith('zone report')) {
        //result += parseIasMessage(description)
        parseIasMessage(description)
    } else {
        logWarn("Unhandled unknown command ($description): cluster=${descMap.clusterId} command=${descMap.command} value=${descMap.value} data=${descMap.data}")
    }

    return result
}

private void parseIasMessage(String description) {
    ZoneStatus zs = zigbee.parseZoneStatus(description)
    logTrace "Zone Status Change Notification: alarm1=${zs.alarm1Set} alarm2=${zs.alarm2Set} tamper=${zs.tamperSet} lowBattery=${zs.batterySet} supervisionReports=${zs.supervisionReportsSet} restoreReports=${zs.restoreReportsSet} trouble=${zs.troubleSet} mainsFault=${zs.acSet} testMode=${zs.testSet} batteryDefect=${zs.batteryDefectSet}"

    if (zs.tamper) {
        if(device.currentValue('tamper') != "detected") {
            logInfo "tamper detected"
            sendEvent(name:'tamper', value: 'detected', descriptionText: "${device.displayName} tamper detected.", type: 'physical')
        }
    } else {
        if(device.currentValue('tamper') != "clear") {
            logInfo "tamper cleared"
            sendEvent(name:'tamper', value: 'clear', descriptionText: "${device.displayName} tamper is cleared.", type: 'physical')
        }
    }

    if (zs.alarm1) {
        if(device.currentValue('contact') != "open") {
            logInfo "contact opened"
            sendEvent(name:'contact', value: 'open', descriptionText: "${device.displayName} is open.", type: 'physical')
        }
    } else {
        if(device.currentValue('contact') != "closed") {
            logInfo "contact closed"
            sendEvent(name:'contact', value: 'closed', descriptionText: "${device.displayName} is closed.", type: 'physical')
        }
    }

    if (zs.batterySet) {
        if (device.currentValue('lowBattery') != "true") {
            logInfo "battery low"
            sendEvent(name:'lowBattery', value: "true", descriptionText: "${device.displayName} battery low", type: 'physical')
        }
    } else {
        if (device.currentValue('lowBattery') != "false") {
            logInfo "battery ok"
            sendEvent(name:'lowBattery', value: "false", descriptionText: "${device.displayName} battery ok", type: 'physical')
            state.lastBatteryOk = now()
        }
    }

    if (zs.batteryDefectSet) {
        // TODO
        logWarn("received battery defective zone alarm")
    }
}

private void parseAttributeReport(String description) {
    Map event = zigbee.getEvent(description)
    logDebug "event message: ${event}"
    if(event) {
        switch(event.name) {
            case 'batteryVoltage':
                def pct = (event.value - constMinBatteryVoltage) / (constMaxBatteryVoltage - constMinBatteryVoltage)
                def roundedPct = Math.round(pct * 100)
                if (roundedPct <= 0) roundedPct = 1
                def descriptionText = "${device.displayName} battery was ${Math.min(100, roundedPct)}%"
                logInfo "${descriptionText}"
                sendEvent(name: 'battery', value: Math.min(100, roundedPct), unit: "%", descriptionText: descriptionText, type: 'physical')
                state.lastBatteryVoltage = event.value
                state.lastBatteryDate = (new Date()).format('yyyy-MM-dd')
                break
            case 'temperature':
                logInfo "${event.descriptionText}"
                sendEvent(name: event.name, value: event.value, unit: "°${event.unit}", descriptionText: event.descriptionText, type: 'physical')
                break
            default:
                logDebug "unexpected attribute report ${event.name} ${event.descriptionText}"
                break
        }
    }
}

private void autoConfigure() {
    logWarn "Detected driver version change"
    configure()
}

private void sendZigbeeCommands(List<String> cmds) {
    if (cmds.empty) return
    List<String> send = delayBetween(cmds.findAll { !it.startsWith('delay') }, constDefaultDelay)
    logTrace "Sending Zigbee messages ➡️ device: ${send}"
    sendHubCommand(new hubitat.device.HubMultiAction(send, hubitat.device.Protocol.ZIGBEE))
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

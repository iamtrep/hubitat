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

@Field static final String CODE_VERSION = "0.1.5"

metadata {
	definition (
        name: "Universal Electronics / Visonic / Xfinity Contact Sensor",
        namespace: "iamtrep",
        author: "pj",
        description: "Zigbee contact sensor with battery, tamper, and temperature",
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
        attribute "batteryDefect", "enum", ["detected","clear"]
        attribute "batteryVoltage", "number"

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

@Field static final Integer constDefaultDelay = 333

// CR2032/CR2450 discharge curve, Z2M "3V_2100" preset (knot voltages in V, percent at knot).
// Piecewise linear; clamped at the endpoints. Verified against the Energizer CR2450 datasheet
// (low-drain Bkgnd curve at 7.5 kΩ continuous) — fits within ~10pp at the knee.
// https://github.com/Koenkk/zigbee-herdsman-converters/blob/master/src/lib/utils.ts
@Field static final double[] constBatteryCurveV   = [2.10d, 2.44d, 2.74d, 2.90d, 3.00d] as double[]
@Field static final double[] constBatteryCurvePct = [ 0.0d,  6.0d, 18.0d, 42.0d, 100.0d] as double[]

// Monotonic EMA on battery voltage. The smoothed value never rises (cells discharge,
// they don't charge) except on a big jump (>= constBatteryBigJumpV) which we treat as a
// battery replacement and snap the smoothed value to the new raw voltage. Small apparent
// upward moves are rejected as ADC dither noise (the radio reports voltage at 100 mV
// resolution, and a cell parked near a knot boundary alternates between two grid steps
// based on instantaneous load / temperature). α=0.30 converges in ~5 reports.
// Big-jump threshold 0.15V is the natural boundary between one-grid-step dither (0.1V)
// and a genuine 2+ grid step move; validated against the maison fleet — zero false snaps
// across 14 devices over ~28 days.
@Field static final double constBatteryEmaAlpha = 0.30d
@Field static final double constBatteryBigJumpV = 0.15d

@CompileStatic
private static int batteryPctFromVoltage(double voltage, double[] curveV, double[] curvePct) {
    int n = curveV.length
    if (voltage <= curveV[0]) return 0
    if (voltage >= curveV[n - 1]) return 100
    for (int i = 1; i < n; i++) {
        if (voltage <= curveV[i]) {
            double vLo = curveV[i - 1]
            double vHi = curveV[i]
            double pLo = curvePct[i - 1]
            double pHi = curvePct[i]
            double pct = pLo + (voltage - vLo) * (pHi - pLo) / (vHi - vLo)
            return (int) Math.round(pct)
        }
    }
    return 100
}

// Decode a hex string into a signed int16 (Zigbee temperature/pressure values
// arrive as unsigned hex but are signed two's complement on the wire).
@CompileStatic
private static int hexToSignedInt16(String hex) {
    int v = Integer.parseInt(hex, 16)
    return v > 0x7FFF ? v - 0x10000 : v
}


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

// User-facing capability. Sleepy end-devices are usually asleep when the UI button
// is clicked, so we defer the reads until the device next transmits — parse() drains
// the flag inside the awake window.
void refresh() {
    logDebug "refresh() requested — will issue on next device wake"
    state.refreshRequested = true
}

private void issueAttributeReads() {
    logDebug "issuing attribute reads (temperature, battery voltage, IAS zone status)"
    List<String> cmds = []
    cmds += zigbee.readAttribute(0x0402, 0x0000, [:], constDefaultDelay)                          // temperature
    cmds += zigbee.readAttribute(zigbee.POWER_CONFIGURATION_CLUSTER, 0x0020, [:], constDefaultDelay) // battery voltage
    cmds += zigbee.readAttribute(0x0500, 0x0002, [:], constDefaultDelay)                          // IAS ZoneStatus bitmap
    sendZigbeeCommands(cmds)
}

void configure() {
	logDebug "configure()"

    state.clear()
    state.lastRx = 0
    state.codeVersion = CODE_VERSION

    int reportInterval = (batteryInterval == null ? 12 : batteryInterval).toInteger() * 60 * 60
    List<String> cmds = []

    // IAS Zone binding and enrollment
    cmds += "zdo bind 0x${device.deviceNetworkId} 1 1 0x0500 {${device.zigbeeId}} {}"  // IAS Zone
    cmds += "delay $constDefaultDelay"
    cmds += zigbee.enrollResponse(1200) // Enroll in IAS Zone

    // Poll Control binding and configuration
    cmds += "zdo bind 0x${device.deviceNetworkId} 1 1 0x0020 {${device.zigbeeId}} {}"  // Poll Control
    cmds += "delay $constDefaultDelay"
    cmds += zigbee.writeAttribute(0x0020, 0x0000, DataType.UINT32, 14400, [:], constDefaultDelay)

    // Configure attribute reports
    cmds += zigbee.configureReporting(zigbee.POWER_CONFIGURATION_CLUSTER, 0x0020, DataType.UINT8, 0, reportInterval, 1, [:], constDefaultDelay) //Battery Voltage Reporting
    cmds += zigbee.temperatureConfig(3600,((tempInterval != null ? tempInterval : 12).toInteger() * 60)) // Temperature Reporting

    sendZigbeeCommands(cmds)
    issueAttributeReads()
}

void setBatteryReplacementDate(Date date = null) {
    if (date == null) date = new Date()
    String dateStr = date.format('yyyy-MM-dd')
	device.updateDataValue("batteryReplacementDate", dateStr)
    logDebug "setting Battery Last Replaced Date to $dateStr"
}

void parse(String description) {
    state.lastRx = now()
    logTrace "parsing message: ${description}"

    // Drain pending refresh request inside the device's post-transmit awake window
    if (state.refreshRequested) {
        state.refreshRequested = false
        issueAttributeReads()
    }

    // Auto-Configure device: configure() was not called for this driver version
    if (state.codeVersion != CODE_VERSION) {
        state.codeVersion = CODE_VERSION
        runInMillis 1500, 'autoConfigure'
    }

    Map descMap = zigbee.parseDescriptionAsMap(description)
    logTrace "Receiving Zigbee message️ ⬅️ device: ${descMap}"

    if (descMap.attrId != null) {
        // device attribute report
        parseAttributeReport(descMap)
        descMap.additionalAttrs?.each { add ->
            add.cluster = descMap.cluster
            parseAttributeReport(add)
        }
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
        parseIasMessage(description)
    } else {
        logWarn("Unhandled unknown command ($description): cluster=${descMap.clusterId} command=${descMap.command} value=${descMap.value} data=${descMap.data}")
    }
}

@Field static final Map ATTRIBUTE_CONFIG = [
    'tamper': [
        trueValue: 'detected',
        falseValue: 'clear',
        trueDesc: 'tamper detected',
        falseDesc: 'tamper cleared'
    ],
    'contact': [
        trueValue: 'open',
        falseValue: 'closed',
        trueDesc: 'contact opened',
        falseDesc: 'contact closed'
    ],
    'lowBattery': [
        trueValue: 'true',
        falseValue: 'false',
        trueDesc: 'battery low',
        falseDesc: 'battery ok'
    ],
    'batteryDefect': [
        trueValue: 'detected',
        falseValue: 'clear',
        trueDesc: 'battery defect detected',
        falseDesc: 'battery defect cleared'
    ]
]

private void parseIasMessage(String description) {
    ZoneStatus zs = zigbee.parseZoneStatus(description)
    //logTrace "Zone Status: ${zs.properties}"

    updateZoneStatusIfChanged('contact', zs.alarm1Set)
    updateZoneStatusIfChanged('tamper', zs.tamperSet) {
        state.lastTamperClear = now()
    }
    updateZoneStatusIfChanged('lowBattery', zs.batterySet) {
        state.lastBatteryOk = now()
    }
    updateZoneStatusIfChanged('batteryDefect', zs.batteryDefectSet) {
        state.lastBatteryDefectClear = now()
    }
}

private void emitZoneStatusEvent(String attribute, Boolean isTrue, Closure onClear = null) {
    Map config = ATTRIBUTE_CONFIG[attribute]
    String descriptionText = "${device.displayName} ${isTrue ? config.trueDesc : config.falseDesc}"
    logInfo descriptionText
    sendEvent(
        name: attribute,
        value: isTrue ? config.trueValue : config.falseValue,
        descriptionText: descriptionText,
        type: 'physical'
    )
    if (!isTrue && onClear) {
        onClear()
    }
}

private void updateZoneStatusIfChanged(String attribute, Boolean isTrue, Closure onClear = null) {
    Map config = ATTRIBUTE_CONFIG[attribute]
    String newValue = isTrue ? config.trueValue : config.falseValue
    if (device.currentValue(attribute) != newValue) {
        emitZoneStatusEvent(attribute, isTrue, onClear)
    }
}

private void parseAttributeReport(Map descMap) {
    Map map = [:]

    switch (descMap.cluster) {
        case "0001": // Power Configuration cluster
            if (descMap.attrId == "0020") {
                // Battery voltage in 100mV units (UINT8 from ZCL 0x0001/0x0020)
                double voltage = Integer.parseInt(descMap.value, 16) / 10.0d

                // Monotonic EMA: smoothed only ever goes down. A big-jump UP is treated as
                // battery replacement (snap to new V, persist the event). Downward moves —
                // whether small or large — are smoothed via EMA, never snapped, so a single
                // transient low ADC sample only nudges smoothed instead of locking it down.
                Double prevSmoothed = (state.smoothedBatteryVoltage instanceof Number) ?
                    ((Number) state.smoothedBatteryVoltage).doubleValue() : null
                double smoothed
                String emaAction
                if (prevSmoothed == null) {
                    smoothed = voltage
                    emaAction = 'init'
                } else if (voltage - prevSmoothed >= constBatteryBigJumpV) {
                    smoothed = voltage
                    emaAction = 'snap-up'
                    Date now = new Date()
                    setBatteryReplacementDate(now)
                    device.updateDataValue('batteryReplacementDetected',
                        "auto: V_prev=${String.format('%.2f', prevSmoothed)} → V_new=${String.format('%.2f', voltage)} @ ${now.format('yyyy-MM-dd HH:mm:ss zzz')}")
                    logInfo "battery replacement detected: ${String.format('%.2f', prevSmoothed)}V → ${String.format('%.2f', voltage)}V"
                } else if (voltage < prevSmoothed) {
                    smoothed = constBatteryEmaAlpha * voltage + (1.0d - constBatteryEmaAlpha) * prevSmoothed
                    emaAction = 'down'
                } else {
                    smoothed = prevSmoothed
                    emaAction = 'hold'
                }
                state.smoothedBatteryVoltage = smoothed

                int smoothedPct = batteryPctFromVoltage(smoothed, constBatteryCurveV, constBatteryCurvePct)

                sendEvent(name: 'batteryVoltage', value: voltage, unit: 'V',
                    descriptionText: "${device.displayName} battery voltage is ${voltage}V")

                map.name = 'battery'
                map.value = smoothedPct
                map.unit = '%'
                map.descriptionText = "${device.displayName} battery is ${smoothedPct}%"

                logTrace "battery EMA: v=${voltage}V action=${emaAction} smoothed=${String.format('%.3f', smoothed)}V pct=${smoothedPct}%"

                state.lastBatteryVoltage = voltage
                state.lastBatteryDate = (new Date()).format('yyyy-MM-dd HH:mm:ss zzz')
            }
            break

        case "0402": // Temperature Measurement cluster
            if (descMap.attrId == "0000") {
                // Temperature in hundredths of degrees Celsius (signed int16)
                double tempC = hexToSignedInt16(descMap.value) / 100.0d
                Double tempValue = (getTemperatureScale() == "C") ? tempC : celsiusToFahrenheit(tempC)
                tempValue = Math.round(tempValue * 10) / 10.0  // round to 1 decimal

                // Apply calibration offset
                if (tempOffset) {
                    tempValue = tempValue + tempOffset
                }

                map.name = 'temperature'
                map.value = tempValue
                map.unit = getTemperatureScale()
                map.descriptionText = "${device.displayName} temperature is ${tempValue}°${map.unit}"
            }
            break

        case "0500": // IAS Zone cluster
            // Unsolicited Status Change Notifications go through parseIasMessage from
            // parse() directly (and are deduped via updateZoneStatusIfChanged). This branch
            // handles solicited reads of the ZoneStatus bitmap (attrId 0x0002) issued by
            // issueAttributeReads(); we asked for the answer, so emit unconditionally.
            // Primary attribute (contact) rides the map for the trailing sendEvent path;
            // the bitmap's other three bits go through emitZoneStatusEvent as side channels.
            if (descMap.attrId == "0002") {
                int zs = Integer.parseInt(descMap.value, 16)
                emitZoneStatusEvent('tamper', (zs & 0x0004) != 0) {
                    state.lastTamperClear = now()
                }
                emitZoneStatusEvent('lowBattery', (zs & 0x0008) != 0) {
                    state.lastBatteryOk = now()
                }
                emitZoneStatusEvent('batteryDefect', (zs & 0x0200) != 0) {
                    state.lastBatteryDefectClear = now()
                }

                Map cfg = ATTRIBUTE_CONFIG['contact']
                boolean isOpen = (zs & 0x0001) != 0
                map.name = 'contact'
                map.value = isOpen ? cfg.trueValue : cfg.falseValue
                map.descriptionText = "${device.displayName} ${isOpen ? cfg.trueDesc : cfg.falseDesc}"
                map.type = 'physical'
            }
            break

        default:
            logDebug "Unhandled cluster ${descMap.cluster} attrId ${descMap.attrId} value ${descMap.value}"
            break
    }

    if (map.name) {
        logInfo "${map.descriptionText}"
        sendEvent(map)
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
    state.lastTx = now()
}


// Logging helpers

void logsOff(){
	logWarn "debug logging disabled..."
	device.updateSetting("debugEnable",[value:"false",type:"bool"])
	device.updateSetting("traceEnable",[value:"false",type:"bool"])
}

private void logTrace(String message) {
    if (traceEnable) log.trace("${device.displayName} : ${message}")
}

private void logDebug(String message) {
    if (debugEnable) log.debug("${device.displayName} : ${message}")
}

private void logInfo(String message) {
    if (txtEnable) log.info("${device.displayName} : ${message}")
}

private void logWarn(String message) {
    log.warn("${device.displayName} : ${message}")
}

private void logError(String message) {
    log.error("${device.displayName} : ${message}")
}



/*

 Coding references

 hubitat.zigbee.clusters.iaszone.ZoneStatus properties map:

 [
 supervisionReportsSet:false, alarm1Set:false, alarm1:0, testSet:false, batteryDefect:0, restoreReports:1,
 acSet:false, ac:0, batterySet:false, troubleSet:false, tamperSet:false, tamper:0, test:0, supervisionReports:0,
 alarm2Set:false, class:class hubitat.zigbee.clusters.iaszone.ZoneStatus, battery:0, restoreReportsSet:true,
 alarm2:0, batteryDefectSet:false, trouble:0
 ]

 */

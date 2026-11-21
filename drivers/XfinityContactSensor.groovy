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
import hubitat.device.HubMultiAction
import hubitat.device.Protocol
import hubitat.zigbee.zcl.DataType
import hubitat.zigbee.clusters.iaszone.ZoneStatus

@Field static final String CODE_VERSION = "0.1.9"

metadata {
	definition (
        name: "Universal Electronics / Visonic / Xfinity Contact Sensor",
        namespace: "iamtrep",
        author: "pj",
        description: "Zigbee contact sensor with battery, tamper, and temperature",
        importUrl: "https://raw.githubusercontent.com/iamtrep/hubitat/main/drivers/XfinityContactSensor.groovy",
        // parse() reads and writes state on most messages, and a configuration brings a burst of
        // replies; in a driver each execution writes the whole state store back when it ends, so
        // overlapping parses would undo each other's writes. Every handler here takes milliseconds.
        singleThreaded: true
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
        input(name: "batteryInterval", type: "number", range: "1..18", title: "<b>Battery Reporting Interval</b>", defaultValue: 12,
              description: "Set battery reporting interval by this many <b>hours</b>.</br>Range: 1 thru 18</br>Default: 12 hours", required: false)
        input(name: "tempInterval", type: "number", range: "60..1092", title: "<b>Temperature Reporting Interval</b>", defaultValue: 720,
              description: "Set temperature reporting interval by this many <b>minutes</b>. </br>Range: 60 thru 1092</br>Default: 720 (12 hours)", required: false)
        input name: "tempOffset", title: "<b>Temperature Calibration</b>", type: "number", range: "-128..127", defaultValue: 0, required: true,
            description: "Adjust temperature by this many degrees.</br>Range: -128 thru 127</br>Default: 0"
        input name: "txtEnable", type: "bool", title: "Enable info logging", defaultValue: true
        input name: "debugEnable", type: "bool", title: "Enable debug logging info", defaultValue: false, required: true, submitOnChange: true
        if (debugEnable) {
            input name: "traceEnable", type: "bool", title: "Enable trace logging info (for development purposes)", defaultValue: false
       }
  }
}

// Spacing between the Zigbee commands of one send
@Field static final int constCommandSpacingMs = 333

// CR2032/CR2450 discharge curve, Z2M "3V_2100" preset (knot voltages in V, percent at knot).
// Piecewise linear; clamped at the endpoints. Checked against the Energizer CR2450 datasheet
// (low-drain curve at 7.5 kΩ continuous): within ~10 points at the knee.
// https://github.com/Koenkk/zigbee-herdsman-converters/blob/master/src/lib/utils.ts
@Field static final double[] constBatteryCurveV   = [2.10d, 2.44d, 2.74d, 2.90d, 3.00d] as double[]
@Field static final double[] constBatteryCurvePct = [ 0.0d,  6.0d, 18.0d, 42.0d, 100.0d] as double[]

// Battery voltage arrives in 0.1 V steps, and a cell near a step boundary flips between two steps
// with load and temperature. The smoothed voltage therefore never rises (cells don't recharge),
// except on a jump of at least constBatteryBigJumpV, two steps, which means a new battery.
// Drops are averaged in (alpha 0.30, about 5 reports to converge), so one low sample only nudges it.
@Field static final double constBatteryEmaAlpha = 0.30d
@Field static final double constBatteryBigJumpV = 0.15d

// ZCL "invalid" values: battery voltage 0xFF, temperature 0x8000
@Field static final int constInvalidBatteryVoltage = 0xFF
@Field static final int constInvalidTemperature = 0x8000

// Poll Control (cluster 0x0020), in quarter-seconds. The sensor ships polling its parent every
// 8 qs (2 s); 24 qs (6 s) is what Zigbee2MQTT and ZHA set, and stays under the ~7.7 s a parent
// holds a message for a sleeping child, so small sends (Refresh) reach it at any time.
@Field static final int constCheckInIntervalQs = 14400   // 1 h
@Field static final int constLongPollIntervalQs = 24     // 6 s

// A sensor whose last check-in is older than this has stopped checking in (Poll Control binding
// lost), so a due configuration goes out on its next message instead.
@Field static final long constCheckInStaleMs = 9000L * 1000   // 2.5 check-in intervals

// Self-repair: a sensor can lose its reporting setup. When no battery report has come for this
// many report intervals, the configuration is sent again, at most once per constRepairMinGapMs.
@Field static final int constStaleReportIntervals = 2
@Field static final long constRepairMinGapMs = 24L * 60 * 60 * 1000

// Check-in Response payload: startFastPolling (bool) + fastPollTimeout (uint16 LE). A timeout of 0
// keeps the sensor's own FastPollTimeout (10 s), twice what a configuration with its reads takes.
@Field static final String constCheckInDecline = "000000"
@Field static final String constCheckInFastPoll = "010000"

// clearedKey: the state key that records when the bit last cleared
@Field static final Map ZONE_ATTRIBUTES = [
    'tamper':        [trueValue: 'detected', falseValue: 'clear',  trueDesc: 'tamper detected',         falseDesc: 'tamper cleared',         clearedKey: 'lastTamperClear'],
    'contact':       [trueValue: 'open',     falseValue: 'closed', trueDesc: 'contact opened',          falseDesc: 'contact closed'],
    'lowBattery':    [trueValue: 'true',     falseValue: 'false',  trueDesc: 'battery low',             falseDesc: 'battery ok',             clearedKey: 'lastBatteryOk'],
    'batteryDefect': [trueValue: 'detected', falseValue: 'clear',  trueDesc: 'battery defect detected', falseDesc: 'battery defect cleared', clearedKey: 'lastBatteryDefectClear']
]

// A uint32 as little-endian hex, the byte order of a ZCL payload (24 -> "18000000").
@CompileStatic
static String uint32LeHex(long v) {
    String be = String.format('%08X', v & 0xFFFFFFFFL)
    return be.substring(6, 8) + be.substring(4, 6) + be.substring(2, 4) + be.substring(0, 2)
}

// Whether the configuration should be sent again, from the last battery report, the last
// configuration sent and the last repair (epoch ms, null when never), the battery report
// interval (s) and the time now.
@CompileStatic
static boolean needsRepair(Long lastBatteryRx, Long lastConfigured, Long lastRepair, int reportIntervalS, long nowMs) {
    if (lastRepair != null && nowMs - lastRepair < constRepairMinGapMs) return false
    long since = Math.max(lastBatteryRx ?: 0L, lastConfigured ?: 0L)
    if (since == 0L) return true
    return nowMs - since > (long) constStaleReportIntervals * reportIntervalS * 1000L
}

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

// Zigbee temperatures are signed two's complement int16 sent as unsigned hex
@CompileStatic
private static int hexToSignedInt16(String hex) {
    int v = Integer.parseInt(hex, 16)
    return v > 0x7FFF ? v - 0x10000 : v
}

// ── Lifecycle and commands ───────────────────────────────────────────

// A device that has just joined is awake and has no Poll Control binding yet, so no check-in
// would come: configure it at once.
void installed() {
	logDebug "installed()"
    resetState()
    sendConfiguration()
}

void updated() {
	logDebug "updated()"
    unschedule()
    queueConfiguration('preferences saved')
    if (debugEnable || traceEnable) runIn(1800, "logsOff")
}

void uninstalled() {
    logDebug "uninstalled()"
}

// After a driver change the Poll Control binding may be missing, so send at once; the check-in
// (once bound) sends it again inside a fast-poll window.
void deviceTypeUpdated() {
    logDebug "driver change detected"
    resetState()
    sendConfiguration()
    state.repairPending = 'driver change'
}

// The hub calls configure() when a known device pairs again. The sensor is awake then and,
// after a reset, has no Poll Control binding, so send at once; the next check-in sends it again
// inside a fast-poll window in case part of this burst is lost.
void configure() {
	logDebug "configure()"
    Long lastCheckIn = state.lastCheckIn as Long
    resetState()
    state.lastCheckIn = lastCheckIn
    sendConfiguration()
    state.repairPending = 'configure'
}

// Three reads fit in the parent's hold at the 6 s long poll, so they go out on the next message
void refresh() {
    logDebug "refresh() requested — will issue on the next message from the device"
    state.refreshRequested = true
}

void setBatteryReplacementDate(Date date = null) {
    if (date == null) date = new Date()
    String dateStr = date.format('yyyy-MM-dd')
	device.updateDataValue("batteryReplacementDate", dateStr)
    logDebug "setting Battery Last Replaced Date to $dateStr"
}

// ── Configuration ────────────────────────────────────────────────────

private void resetState() {
    state.clear()
    state.lastRx = 0
    state.codeVersion = CODE_VERSION
}

// The configuration goes out inside the fast-poll window that follows a check-in: a burst sent
// while the sensor sleeps can be lost in part. A sensor not checking in (never seen, or its Poll
// Control binding lost) gets it at once, since no check-in would come.
private void queueConfiguration(String reason) {
    if (checkInsStale()) {
        logCfg "sending the configuration now (${reason}; no recent check-in)"
        sendConfiguration()
        return
    }
    state.repairPending = reason
    logCfg "configuration queued for the next check-in (${reason})"
}

private boolean checkInsStale() {
    Long lastCheckIn = state.lastCheckIn as Long
    return lastCheckIn == null || now() - lastCheckIn > constCheckInStaleMs
}

private boolean repairDue() {
    return state.repairPending != null ||
        needsRepair(state.lastBatteryRx as Long, state.lastConfigured as Long, state.lastRepair as Long, batteryReportIntervalS(), now())
}

private int batteryReportIntervalS() {
    int hours = Math.max(1, Math.min(18, (batteryInterval == null ? 12 : batteryInterval).toInteger()))
    return hours * 60 * 60
}

private void sendConfiguration() {
    markConfigured()
    sendZigbeeCommands(configurationCommands())
}

private void markConfigured() {
    state.lastConfigured = now()
    state.repairPending = null
}

// Bindings, Poll Control settings and report setup, then the attribute reads
private List<String> configurationCommands() {
    String dni = device.deviceNetworkId
    String ieee = device.zigbeeId
    // the maximum must not be below the 3600 s minimum and must fit the 16-bit field
    int tempMaxSeconds = Math.min(Math.max((tempInterval != null ? tempInterval : 720).toInteger() * 60, 3600), 65535)
    List<String> cmds = []
    cmds += "zdo bind 0x${dni} 1 1 0x0500 {${ieee}} {}"
    cmds += zigbee.enrollResponse()
    cmds += "zdo bind 0x${dni} 1 1 0x0020 {${ieee}} {}"
    cmds += zigbee.writeAttribute(0x0020, 0x0000, DataType.UINT32, constCheckInIntervalQs)
    // LongPollInterval is read-only; Set Long Poll Interval (command 0x02, uint32 LE) changes it
    cmds += zigbee.command(0x0020, 0x02, uint32LeHex(constLongPollIntervalQs))
    cmds += zigbee.configureReporting(zigbee.POWER_CONFIGURATION_CLUSTER, 0x0020, DataType.UINT8, 0, batteryReportIntervalS(), 1)
    cmds += zigbee.temperatureConfig(3600, tempMaxSeconds)
    cmds += attributeReadCommands()
    return cmds
}

private List<String> attributeReadCommands() {
    logDebug "issuing attribute reads (temperature, battery voltage, IAS zone status, long poll)"
    List<String> cmds = []
    cmds += zigbee.readAttribute(0x0402, 0x0000)                             // temperature
    cmds += zigbee.readAttribute(zigbee.POWER_CONFIGURATION_CLUSTER, 0x0020) // battery voltage
    cmds += zigbee.readAttribute(0x0500, 0x0002)                             // IAS ZoneStatus bitmap
    cmds += zigbee.readAttribute(0x0020, 0x0001)                             // LongPollInterval
    return cmds
}

// Poll Control check-in (hourly). The sensor fast-polls until it gets a Check-in Response or its
// FastPollTimeout runs out, so always answer: decline fast polling when nothing is due, or accept
// it, send the configuration, and end the window with Fast Poll Stop. Returns whether the
// configuration (which includes the attribute reads) was sent.
private boolean handleCheckIn() {
    state.lastCheckIn = now()
    if (!repairDue()) {
        logRx "check-in"
        sendZigbeeCommands(zigbee.command(0x0020, 0x00, constCheckInDecline))
        return false
    }
    logCfg "check-in: sending the configuration (${state.repairPending ?: 'no battery report lately'})"
    state.lastRepair = now()
    markConfigured()
    List<String> cmds = []
    cmds += zigbee.command(0x0020, 0x00, constCheckInFastPoll)
    cmds += configurationCommands()
    cmds += zigbee.command(0x0020, 0x01) // Fast Poll Stop
    sendZigbeeCommands(cmds)
    return true
}

// For a sensor that has stopped checking in, any message is the only chance to send a due
// configuration. Counted from the last check-in, or from the last configuration sent (which binds
// Poll Control); with neither known, the next check-in or Configure handles it.
private boolean repairWithoutCheckIn() {
    long since = Math.max((state.lastCheckIn ?: 0L) as long, (state.lastConfigured ?: 0L) as long)
    if (since == 0L || now() - since <= constCheckInStaleMs || !repairDue()) return false
    logCfg "sending the configuration (${state.repairPending ?: 'no battery report lately'}; no recent check-in)"
    state.lastRepair = now()
    sendConfiguration()
    return true
}

private void sendZigbeeCommands(List<String> cmds) {
    if (cmds.empty) return
    List<String> send = delayBetween(cmds.findAll { !it.startsWith('delay') }, constCommandSpacingMs)
    logTrace "Sending Zigbee messages: ${send}"
    sendHubCommand(new HubMultiAction(send, Protocol.ZIGBEE))
    state.lastTx = now()
}

// ── Parsing ──────────────────────────────────────────────────────────

void parse(String description) {
    state.lastRx = now()
    logTrace "parsing message: ${description}"

    Map descMap = zigbee.parseDescriptionAsMap(description)
    logTrace "Receiving Zigbee message: ${descMap}"
    boolean checkIn = descMap.clusterId == "0020" && descMap.command == "00" && descMap.isClusterSpecific

    // The configuration was not sent for this driver version
    if (state.codeVersion != CODE_VERSION) {
        state.codeVersion = CODE_VERSION
        logVer "Detected driver version change"
        state.repairPending = 'driver update'
    }

    boolean configured = checkIn ? handleCheckIn() : repairWithoutCheckIn()
    if (state.refreshRequested) {
        state.refreshRequested = false
        if (!configured) sendZigbeeCommands(attributeReadCommands())
    }

    // Order matters: a read or report has an attrId; catchalls are told apart by profile;
    // IAS descriptions ('enroll request', 'zone status') have no map
    if (checkIn) {
        return
    } else if (descMap.attrId != null) {
        parseAttributeReport(descMap)
        descMap.additionalAttrs?.each { add ->
            add.cluster = descMap.cluster
            parseAttributeReport(add)
        }
    } else if (descMap.profileId == "0000") {
        logTrace("Unhandled ZDO command: cluster=${descMap.clusterId} command=${descMap.command} value=${descMap.value} data=${descMap.data}")
    } else if (descMap.profileId == "0104" && descMap.clusterId != null) {
        logTrace("Unhandled ZHA global command: cluster=${descMap.clusterId} command=${descMap.command} value=${descMap.value} data=${descMap.data}")
    } else if (description?.startsWith('enroll request')) {
        logRx "Received enroll request"
        sendZigbeeCommands(zigbee.enrollResponse())
    } else if (description?.startsWith('zone status') || description?.startsWith('zone report')) {
        parseIasMessage(description)
    } else {
        logTrace("Unhandled description ($description): cluster=${descMap.clusterId} command=${descMap.command} value=${descMap.value} data=${descMap.data}")
    }
}

// Unsolicited Zone Status Change Notification: emit only what changed
private void parseIasMessage(String description) {
    ZoneStatus zs = zigbee.parseZoneStatus(description)
    updateZoneStatusIfChanged('contact', zs.alarm1Set)
    updateZoneStatusIfChanged('tamper', zs.tamperSet)
    updateZoneStatusIfChanged('lowBattery', zs.batterySet)
    updateZoneStatusIfChanged('batteryDefect', zs.batteryDefectSet)
}

private void updateZoneStatusIfChanged(String attribute, boolean isTrue) {
    Map config = ZONE_ATTRIBUTES[attribute]
    if (device.currentValue(attribute) != (isTrue ? config.trueValue : config.falseValue)) {
        emitZoneStatusEvent(attribute, isTrue)
    }
}

private void emitZoneStatusEvent(String attribute, boolean isTrue) {
    Map config = ZONE_ATTRIBUTES[attribute]
    String desc = isTrue ? config.trueDesc : config.falseDesc
    logInfo desc
    sendEvent(name: attribute, value: isTrue ? config.trueValue : config.falseValue,
        descriptionText: "${device.displayName} ${desc}", type: 'physical')
    if (!isTrue && config.clearedKey) state[(String) config.clearedKey] = now()
}

private void parseAttributeReport(Map descMap) {
    if (descMap.value == null) {
        logDebug "no value for cluster ${descMap.cluster} attrId ${descMap.attrId} (read failed)"
        return
    }
    int raw = Integer.parseInt(descMap.value as String, 16)

    switch (descMap.cluster) {
        case "0001": // Power Configuration: battery voltage in 0.1 V steps
            if (descMap.attrId == "0020" && raw != constInvalidBatteryVoltage) {
                parseBatteryVoltage(raw / 10.0d)
            }
            break

        case "0402": // Temperature Measurement: hundredths of a degree Celsius, signed
            if (descMap.attrId == "0000" && raw != constInvalidTemperature) {
                double tempC = hexToSignedInt16(descMap.value as String) / 100.0d
                double temp = (getTemperatureScale() == "C") ? tempC : celsiusToFahrenheit(tempC)
                if (tempOffset) temp += tempOffset
                temp = Math.round(temp * 10) / 10.0d
                String unit = getTemperatureScale()
                logInfo "temperature is ${temp}°${unit}"
                sendEvent(name: 'temperature', value: temp, unit: unit, descriptionText: "${device.displayName} temperature is ${temp}°${unit}")
            }
            break

        case "0500": // IAS Zone: a ZoneStatus read we asked for, so emit every bit
            if (descMap.attrId == "0002") {
                emitZoneStatusEvent('contact', (raw & 0x0001) != 0)
                emitZoneStatusEvent('tamper', (raw & 0x0004) != 0)
                emitZoneStatusEvent('lowBattery', (raw & 0x0008) != 0)
                emitZoneStatusEvent('batteryDefect', (raw & 0x0200) != 0)
            }
            break

        case "0020": // Poll Control: the long poll read back after each configuration
            if (descMap.attrId == "0001") {
                state.longPollQs = raw
                if (raw != constLongPollIntervalQs) {
                    logWarn "long poll interval is ${raw} qs, not ${constLongPollIntervalQs}: the configuration did not take"
                }
            }
            break

        default:
            logDebug "Unhandled cluster ${descMap.cluster} attrId ${descMap.attrId} value ${descMap.value}"
            break
    }
}

private void parseBatteryVoltage(double voltage) {
    double smoothed = smoothBatteryVoltage(voltage)
    int pct = batteryPctFromVoltage(smoothed, constBatteryCurveV, constBatteryCurvePct)
    state.lastBatteryVoltage = voltage
    state.lastBatteryRx = now()
    state.lastBatteryDate = new Date().format('yyyy-MM-dd HH:mm:ss zzz')
    sendEvent(name: 'batteryVoltage', value: voltage, unit: 'V', descriptionText: "${device.displayName} battery voltage is ${voltage}V")
    logInfo "battery is ${pct}%"
    sendEvent(name: 'battery', value: pct, unit: '%', descriptionText: "${device.displayName} battery is ${pct}%")
}

// Returns the new smoothed voltage (see constBatteryEmaAlpha) and records a battery replacement
private double smoothBatteryVoltage(double voltage) {
    Double prev = (state.smoothedBatteryVoltage instanceof Number) ? ((Number) state.smoothedBatteryVoltage).doubleValue() : null
    double smoothed
    String action
    if (prev == null) {
        smoothed = voltage
        action = 'init'
    } else if (voltage - prev >= constBatteryBigJumpV) {
        smoothed = voltage
        action = 'replaced'
        Date changed = new Date()
        setBatteryReplacementDate(changed)
        device.updateDataValue('batteryReplacementDetected',
            "auto: V_prev=${String.format('%.2f', prev)} → V_new=${String.format('%.2f', voltage)} @ ${changed.format('yyyy-MM-dd HH:mm:ss zzz')}")
        logInfo "battery replacement detected: ${String.format('%.2f', prev)}V → ${String.format('%.2f', voltage)}V"
        state.repairPending = 'battery replaced' // a battery change can drop the sensor's setup
    } else if (voltage < prev) {
        smoothed = constBatteryEmaAlpha * voltage + (1.0d - constBatteryEmaAlpha) * prev
        action = 'down'
    } else {
        smoothed = prev
        action = 'hold'
    }
    state.smoothedBatteryVoltage = smoothed
    logTrace "battery EMA: v=${voltage}V action=${action} smoothed=${String.format('%.3f', smoothed)}V"
    return smoothed
}

void logsOff() {
    device.updateSetting("debugEnable", [value: "false", type: "bool"])
    device.updateSetting("traceEnable", [value: "false", type: "bool"])
    logWarn "debug and trace logging disabled"
}

// ── Logging ───────────────────────────────────────────────────────────
//   ⬇️ Rx  ⬆️ Cmd  🔧 Cfg  🌐 Net  ⏰ Sched  📦 Ota  🏷️ Ver  ·  ⚠️ Warn  🛑 Error  🔬 Trace
private String logp(String e) { "${e} ${device.displayName}: " }

void logRx   (String m) { if (debugEnable) log.debug logp('⬇️') + m }
void logCmd  (String m) { if (txtEnable != false) log.info  logp('⬆️') + m }
void logCfg  (String m) { if (txtEnable != false) log.info  logp('🔧') + m }
void logNet  (String m) { if (debugEnable) log.debug logp('🌐') + m }
void logSched(String m) { if (debugEnable) log.debug logp('⏰') + m }
void logOta  (String m) { if (txtEnable != false) log.info  logp('📦') + m }
void logVer  (String m) { log.warn  logp('🏷️') + m }

void logWarn (String m) { log.warn  logp('⚠️') + m }
void logError(String m) { log.error logp('🛑') + m }
void logTrace(String m) { if (traceEnable) log.trace logp('🔬') + m }
void logInfo (String m) { if (txtEnable != false) log.info  "${device.displayName}: ${m}" }
void logDebug(String m) { if (debugEnable) log.debug "${device.displayName}: ${m}" }


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

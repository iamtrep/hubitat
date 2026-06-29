// Copyright (c) 2026 PJ
// SPDX-License-Identifier: MIT

/*
 *  Third Reality Smart Plug (3RSP02028BZ) Device Driver for Hubitat Elevation
 *
 *  ZCL clusters used:
 *    0x0006  On/Off
 *    0x0B04  Electrical Measurement (rms voltage/current, active power, frequency, power factor)
 *    0x0702  Metering (energy summation)
 *    0xFF03  Third Reality proprietary special cluster — mfg code is generation-keyed:
 *            Gen2 = 0x1233, Gen3 = 0x1407. Writing FF03 with the wrong mfg code is
 *            rejected as Unsupported Attribute. Gen3 also moved LED brightness out of
 *            FF03 to genBasic (0x0000) attr 0xFF01. See mfgCode()/isGen3().
 */

import groovy.transform.Field
import groovy.transform.CompileStatic
import com.hubitat.app.DeviceWrapper
import com.hubitat.hub.domain.Event
import hubitat.zigbee.zcl.DataType
import java.math.RoundingMode

@Field static final String CODE_VERSION = "0.0.7"

// Third Reality proprietary cluster (no Hubitat constant). The mfg code differs by
// generation; resolve per-device via mfgCode() rather than a single constant.
@Field static final int CLUSTER_MFG                 = 0xFF03
@Field static final String MFG_CODE_GEN2            = "0x1233"
@Field static final String MFG_CODE_GEN3            = "0x1407"

// Gen3 SKUs (Z2M 3rPlugGen3Specialcluster grouping). Everything else is treated as Gen2.
@Field static final List<String> GEN3_MODELS        = [
    "3RSP02064Z", "3RSPE02065Z", "3RSPU01080Z", "3RSP0186Z", "3RSPJ0187Z"
]

// ZCL attribute IDs (standard clusters use zigbee.{BASIC,ON_OFF,ELECTRICAL_MEASUREMENT,METERING}_CLUSTER)
@Field static final int ATTR_FW_VERSION             = 0x4000
@Field static final int ATTR_ON_OFF                 = 0x0000
@Field static final int ATTR_POWER_RESTORE          = 0x4003
@Field static final int ATTR_AC_FREQUENCY           = 0x0300
@Field static final int ATTR_RMS_VOLTAGE            = 0x0505
@Field static final int ATTR_RMS_CURRENT            = 0x0508
@Field static final int ATTR_ACTIVE_POWER           = 0x050B
@Field static final int ATTR_POWER_FACTOR           = 0x0510
@Field static final int ATTR_SUMMATION              = 0x0000

// Mfg proprietary attributes (Gen2)
@Field static final int ATTR_MFG_RESET_ENERGY       = 0x0000
@Field static final int ATTR_MFG_ON_TO_OFF_DELAY    = 0x0001
@Field static final int ATTR_MFG_OFF_TO_ON_DELAY    = 0x0002
@Field static final int ATTR_MFG_LED_BRIGHTNESS     = 0x0010      // Gen2: on FF03
@Field static final int ATTR_MFG_ALLOW_BIND         = 0x0020
// Gen3 relocated LED brightness to genBasic (0x0000) attr 0xFF01, mfg 0x1407
@Field static final int ATTR_BASIC_LED_BRIGHTNESS_GEN3 = 0xFF01

// Device-reported divisors are unreliable on this hardware; baked in.
@Field static final int POWER_DIVISOR               = 10        // W
@Field static final int CURRENT_DIVISOR             = 1000      // A
@Field static final int VOLTAGE_DIVISOR             = 10        // V
@Field static final long ENERGY_DIVISOR             = 3600000L  // kWh from joules

// Sentinel for invalid UINT16 (ZCL "no measurement")
@Field static final int INVALID_UINT16              = 0xFFFF

// Health/presence
@Field static final int PRESENT_THRESHOLD           = 3

// ZCL global command codes we recognize as routine acks
@Field static final Map<String, String> constZclAckCmdNames = [
    "04": "Write Attributes Response",
    "07": "Configure Reporting Response",
    "09": "Read Reporting Configuration Response",
    "0B": "Default Response"
]

// ZCL status codes (subset relevant to attribute/reporting responses)
@Field static final Map<String, String> constZclStatusNames = [
    "00": "Success",
    "01": "Failure",
    "7E": "Not Authorized",
    "80": "Malformed Command",
    "81": "Unsupported Cluster Command",
    "82": "Unsupported General Command",
    "85": "Invalid Field",
    "86": "Unsupported Attribute",
    "87": "Invalid Value",
    "88": "Read Only",
    "89": "Insufficient Space",
    "8B": "Not Found",
    "8C": "Unreportable Attribute",
    "8D": "Invalid Data Type",
    "94": "Time Out",
    "C3": "Unsupported Cluster"
]

@Field static final Map<Integer, String> constPowerRestoreOpts = [
    0x00: "Off",
    0x01: "On",
    0xFF: "Last State"
]

@Field static final Map<Integer, String> constHealthCheckIntervalOpts = [
    0:  "Disabled",
    10: "Every 10 minutes",
    15: "Every 15 minutes",
    30: "Every 30 minutes",
    60: "Every hour"
]

@Field static final Map<Integer, String> constReportingMinOpts = [
    0:   "On any change",
    5:   "5 seconds",
    10:  "10 seconds",
    30:  "30 seconds",
    60:  "1 minute"
]

@Field static final Map<Integer, String> constReportingMaxOpts = [
    300:   "5 minutes",
    600:   "10 minutes",
    1800:  "30 minutes",
    3600:  "1 hour",
    10800: "3 hours",
    21600: "6 hours",
    43200: "12 hours"
]

metadata {
    definition(
        name: "Third Reality Smart Plug (3RSP02028BZ)",
        namespace: "iamtrep",
        author: "pj",
        description: "Zigbee on/off plug with power monitoring",
        singleThreaded: true,
        importUrl: "https://raw.githubusercontent.com/iamtrep/hubitat/refs/heads/main/drivers/thirdreality/ThirdReality_3RSP02028BZ.groovy"
    ) {
        capability "Actuator"
        capability "Configuration"
        capability "CurrentMeter"
        capability "EnergyMeter"
        capability "Outlet"
        capability "PowerMeter"
        capability "Refresh"
        capability "Sensor"
        capability "Switch"
        capability "VoltageMeasurement"

        attribute "frequency",    "number"
        attribute "powerFactor",  "number"
        attribute "healthStatus", "enum", ["unknown", "offline", "online"]

        command "toggle"
        command "resetEnergy"
        command "startBind"
        command "updateFirmware"

        // 3RSP02028BZ and behavior-compatible Gen2 single-outlet SKUs (per Z2M grouping)
        fingerprint profileId: "0104", endpointId: "01", inClusters: "0000,0003,0004,0005,0006,1000,0B04,0702", outClusters: "0019", manufacturer: "Third Reality, Inc", model: "3RSP02028BZ", deviceJoinName: "Third Reality Smart Plug"
        fingerprint profileId: "0104", endpointId: "01", inClusters: "0000,0003,0004,0005,0006,1000,0B04,0702", outClusters: "0019", manufacturer: "Third Reality, Inc", model: "3RSPE01044BZ", deviceJoinName: "Third Reality Smart Plug"
        fingerprint profileId: "0104", endpointId: "01", inClusters: "0000,0003,0004,0005,0006,1000,0B04,0702", outClusters: "0019", manufacturer: "Third Reality, Inc", model: "3RSPU01080Z",  deviceJoinName: "Third Reality Smart Plug"
        fingerprint profileId: "0104", endpointId: "01", inClusters: "0000,0003,0004,0005,0006,1000,0B04,0702", outClusters: "0019", manufacturer: "Third Reality, Inc", model: "3RSP02064Z",   deviceJoinName: "Third Reality Smart Plug"
        fingerprint profileId: "0104", endpointId: "01", inClusters: "0000,0003,0004,0005,0006,1000,0B04,0702", outClusters: "0019", manufacturer: "Third Reality, Inc", model: "3RSPE02065Z",  deviceJoinName: "Third Reality Smart Plug"

        preferences {
            input(name: "prefPowerRestore", type: "enum", title: "<b>Power Restore Mode</b>",
                  options: constPowerRestoreOpts, defaultValue: 0xFF,
                  description: "State the outlet returns to when power is restored.")

            input(name: "prefOnToOffDelay", type: "number", title: "<b>On-to-Off Delay (seconds)</b>",
                  range: "0..7200", defaultValue: 0,
                  description: "When commanded off, wait this many seconds before opening the relay. 0 = disabled.")

            input(name: "prefOffToOnDelay", type: "number", title: "<b>Off-to-On Delay (seconds)</b>",
                  range: "0..7200", defaultValue: 0,
                  description: "When commanded on, wait this many seconds before closing the relay. 0 = disabled.")

            input(name: "prefLedBrightness", type: "number", title: "<b>LED Brightness</b>",
                  range: "0..100", defaultValue: 100,
                  description: "Indicator LED brightness (0-100%).")

            input(name: "prefDisableOnOff", type: "bool", title: "<b>Disable Power Commands</b>",
                  defaultValue: false,
                  description: "When true, on()/off()/toggle() become no-ops. Safety lockout.")

            input(name: "prefPowerDelta", type: "number", title: "<b>Power Minimum Change (W)</b>",
                  range: "1..1500", defaultValue: 1.0,
                  description: "Minimum power change in watts to emit an event. Firmware enforces a hard floor of >1W AND >1% regardless of this setting.")

            input(name: "prefCurrentDelta", type: "number", title: "<b>Current Minimum Change (A)</b>",
                  range: "0.05..15", defaultValue: 0.05,
                  description: "Minimum current change in amps to emit an event.")

            input(name: "prefVoltageDelta", type: "number", title: "<b>Voltage Minimum Change (V)</b>",
                  range: "0.5..50", defaultValue: 0.5,
                  description: "Minimum voltage change in volts to emit an event.")

            input(name: "prefEnergyDelta", type: "number", title: "<b>Energy Minimum Change (kWh)</b>",
                  range: "0.001..10", defaultValue: 0.01,
                  description: "Minimum energy change in kWh to emit an event.")

            input(name: "prefReportingMin", type: "enum", title: "<b>Reporting Minimum Interval</b>",
                  options: constReportingMinOpts, defaultValue: 10)

            input(name: "prefReportingMax", type: "enum", title: "<b>Reporting Maximum Interval</b>",
                  options: constReportingMaxOpts, defaultValue: 3600)

            input(name: "prefHealthCheckInterval", type: "enum", title: "<b>Health Check Interval</b>",
                  options: constHealthCheckIntervalOpts, defaultValue: 0,
                  description: "How often to check whether the device is still talking. Disabled by default — mains-powered routers rarely go silent.")

            input(name: "txtEnable", type: "bool", title: "<b>Enable descriptionText logging</b>",
                  defaultValue: true)
            input(name: "debugEnable", type: "bool", title: "<b>Enable debug logging</b>",
                  defaultValue: false, submitOnChange: true)
            if (debugEnable) {
                input(name: "traceEnable", type: "bool", title: "<b>Enable trace logging</b>",
                      defaultValue: false,
                      description: "For driver development/troubleshooting purposes.")
            }
        }
    }
}

// Driver installation

void installed() {
    logInfo "installed"
    // state.attributes: diagnostic cache of device-reported divisors, overwritten on each refresh.
    state.attributes = [:]
    state.codeVersion = CODE_VERSION
    state.lastRx = 0
    state.lastTx = 0

    sendEvent(name: "switch",       value: "off")
    sendEvent(name: "power",        value: 0,   unit: "W")
    sendEvent(name: "voltage",      value: 0,   unit: "V")
    sendEvent(name: "amperage",     value: 0,   unit: "A")
    sendEvent(name: "energy",       value: 0,   unit: "kWh")
    sendEvent(name: "frequency",    value: 0,   unit: "Hz")
    sendEvent(name: "powerFactor",  value: 0)
    sendEvent(name: "healthStatus", value: "unknown")

    updated()
}

void updated() {
    logInfo "updated"
    unschedule()

    if (debugEnable) {
        logDebug "settings: ${settings}"
        runIn(1800, "logsOff")
    }

    int healthInterval = intSetting(settings.prefHealthCheckInterval, 0)
    if (healthInterval > 0) {
        scheduleDeviceHealthCheck(healthInterval)
    }

    List<String> cmds = []
    int powerRestore = intSetting(settings.prefPowerRestore, 0xFF)
    cmds += zigbee.writeAttribute(zigbee.ON_OFF_CLUSTER, ATTR_POWER_RESTORE, DataType.ENUM8, powerRestore)

    String mfg = mfgCode()
    int onToOff = intSetting(settings.prefOnToOffDelay, 0)
    cmds += zigbee.writeAttribute(CLUSTER_MFG, ATTR_MFG_ON_TO_OFF_DELAY, DataType.UINT16, onToOff, [mfgCode: mfg])

    int offToOn = intSetting(settings.prefOffToOnDelay, 0)
    cmds += zigbee.writeAttribute(CLUSTER_MFG, ATTR_MFG_OFF_TO_ON_DELAY, DataType.UINT16, offToOn, [mfgCode: mfg])

    int ledBrightness = intSetting(settings.prefLedBrightness, 100)
    cmds += ledBrightnessWrite(ledBrightness)

    sendZigbeeCommands(cmds)
    runInMillis(1000, "configure")
}

void uninstalled() {
    logInfo "uninstalled"
    unschedule()
}

void deviceTypeUpdated() {
    logDebug "driver change detected"
    configure()
}

void logsOff() {
    logWarn "debug logging disabled"
    device.updateSetting("debugEnable", [value: "false", type: "bool"])
    device.updateSetting("traceEnable", [value: "false", type: "bool"])
}

// Capabilities

void configure() {
    logTrace "configure()"
    state.codeVersion = CODE_VERSION

    List<String> cmds = []

    int minInterval = intSetting(settings.prefReportingMin, 10)
    int maxInterval = intSetting(settings.prefReportingMax, 3600)

    BigDecimal powerDelta   = decSetting(settings.prefPowerDelta,   1.0)
    BigDecimal currentDelta = decSetting(settings.prefCurrentDelta, 0.05)
    BigDecimal voltageDelta = decSetting(settings.prefVoltageDelta, 0.5)
    BigDecimal energyDelta  = decSetting(settings.prefEnergyDelta,  0.01)

    int powerDeltaRaw   = Math.max(1, (powerDelta   * POWER_DIVISOR).intValue())
    int currentDeltaRaw = Math.max(1, (currentDelta * CURRENT_DIVISOR).intValue())
    int voltageDeltaRaw = Math.max(1, (voltageDelta * VOLTAGE_DIVISOR).intValue())
    // configureReporting reportableChange is Integer-only; 10 kWh * 3.6M < 2^31
    int energyDeltaRaw  = Math.max(1, (energyDelta  * ENERGY_DIVISOR).intValue())

    cmds += zigbee.configureReporting(zigbee.ON_OFF_CLUSTER,          ATTR_ON_OFF,        DataType.BOOLEAN, 0,           600)
    cmds += zigbee.configureReporting(zigbee.ELECTRICAL_MEASUREMENT_CLUSTER, ATTR_ACTIVE_POWER,  DataType.INT16,   minInterval, maxInterval, powerDeltaRaw)
    cmds += zigbee.configureReporting(zigbee.ELECTRICAL_MEASUREMENT_CLUSTER, ATTR_RMS_CURRENT,   DataType.UINT16,  minInterval, maxInterval, currentDeltaRaw)
    cmds += zigbee.configureReporting(zigbee.ELECTRICAL_MEASUREMENT_CLUSTER, ATTR_RMS_VOLTAGE,   DataType.UINT16,  minInterval, maxInterval, voltageDeltaRaw)
    cmds += zigbee.configureReporting(zigbee.METERING_CLUSTER,        ATTR_SUMMATION,     DataType.UINT48,  minInterval, maxInterval, energyDeltaRaw)

    cmds += zigbee.reportingConfiguration(zigbee.ON_OFF_CLUSTER,          ATTR_ON_OFF)
    cmds += zigbee.reportingConfiguration(zigbee.ELECTRICAL_MEASUREMENT_CLUSTER, ATTR_ACTIVE_POWER)
    cmds += zigbee.reportingConfiguration(zigbee.ELECTRICAL_MEASUREMENT_CLUSTER, ATTR_RMS_CURRENT)
    cmds += zigbee.reportingConfiguration(zigbee.ELECTRICAL_MEASUREMENT_CLUSTER, ATTR_RMS_VOLTAGE)
    cmds += zigbee.reportingConfiguration(zigbee.METERING_CLUSTER,        ATTR_SUMMATION)

    // Diagnostic read: device-reported V/I/P multipliers and divisors
    cmds += zigbee.readAttribute(zigbee.ELECTRICAL_MEASUREMENT_CLUSTER, [0x0600, 0x0601, 0x0602, 0x0603, 0x0604, 0x0605])
    cmds += zigbee.readAttribute(zigbee.METERING_CLUSTER, [0x0300, 0x0301, 0x0302])

    // State refresh
    cmds += zigbee.readAttribute(zigbee.ON_OFF_CLUSTER, ATTR_ON_OFF)
    cmds += zigbee.readAttribute(zigbee.ON_OFF_CLUSTER, ATTR_POWER_RESTORE)
    cmds += zigbee.readAttribute(zigbee.ELECTRICAL_MEASUREMENT_CLUSTER, [ATTR_AC_FREQUENCY, ATTR_RMS_VOLTAGE, ATTR_RMS_CURRENT, ATTR_ACTIVE_POWER, ATTR_POWER_FACTOR])
    cmds += zigbee.readAttribute(zigbee.METERING_CLUSTER, ATTR_SUMMATION)
    cmds += zigbee.readAttribute(zigbee.BASIC_CLUSTER, ATTR_FW_VERSION)

    sendZigbeeCommands(cmds)
    runIn(5, "configureApply")
}

void configureApply() {
    logInfo "configured (version ${CODE_VERSION})"
}

List<String> refresh() {
    logTrace "refresh()"
    List<String> cmds = []
    cmds += zigbee.readAttribute(zigbee.ON_OFF_CLUSTER, ATTR_ON_OFF)
    cmds += zigbee.readAttribute(zigbee.ELECTRICAL_MEASUREMENT_CLUSTER, [ATTR_AC_FREQUENCY, ATTR_RMS_VOLTAGE, ATTR_RMS_CURRENT, ATTR_ACTIVE_POWER, ATTR_POWER_FACTOR])
    cmds += zigbee.readAttribute(zigbee.METERING_CLUSTER, ATTR_SUMMATION)
    return cmds
}

// Chain an explicit readAttribute(0x0006, 0x0000) after the on/off/toggle
// command. Without it, a no-op command (device already in target state)
// emits a Default Response but no on-change attribute report — and the
// platform's command-retry watchdog gives up after 5 retries. The read is
// a directed query the device must answer regardless of state transition.
List<String> on() {
    if (settings.prefDisableOnOff) {
        logInfo "on() ignored (Disable Power Commands is on)"
        return []
    }
    logInfo "on"
    markPendingDigital()
    List<String> cmds = []
    cmds += zigbee.on()
    cmds += zigbee.readAttribute(zigbee.ON_OFF_CLUSTER, ATTR_ON_OFF)
    return cmds
}

List<String> off() {
    if (settings.prefDisableOnOff) {
        logInfo "off() ignored (Disable Power Commands is on)"
        return []
    }
    logInfo "off"
    markPendingDigital()
    List<String> cmds = []
    cmds += zigbee.off()
    cmds += zigbee.readAttribute(zigbee.ON_OFF_CLUSTER, ATTR_ON_OFF)
    return cmds
}

List<String> toggle() {
    if (settings.prefDisableOnOff) {
        logInfo "toggle() ignored (Disable Power Commands is on)"
        return []
    }
    logInfo "toggle"
    markPendingDigital()
    List<String> cmds = []
    cmds += zigbee.command(zigbee.ON_OFF_CLUSTER, 0x02)
    cmds += zigbee.readAttribute(zigbee.ON_OFF_CLUSTER, ATTR_ON_OFF)
    return cmds
}

// Safety-net clears state.isDigital if no state report follows
// (e.g. command was a no-op because device was already in target state).
private void markPendingDigital() {
    state.isDigital = true
    runInMillis(5000, "clearIsDigital")
}

void clearIsDigital() {
    state.remove("isDigital")
}

List<String> updateFirmware() {
    logInfo "checking for firmware updates"
    return zigbee.updateFirmware()
}

// Custom commands

void resetEnergy() {
    logInfo "resetEnergy"
    List<String> cmds = []
    // Device-side reset (older Gen1 firmware silently ignores)
    cmds += zigbee.writeAttribute(CLUSTER_MFG, ATTR_MFG_RESET_ENERGY, DataType.UINT8, 1, [mfgCode: mfgCode()])
    // Driver-side offset
    cmds += zigbee.readAttribute(zigbee.METERING_CLUSTER, ATTR_SUMMATION)
    state.captureEnergyOffset = true
    sendZigbeeCommands(cmds)
}

void startBind() {
    logInfo "startBind: writing 0xFF03/0x0020 = 1"
    sendZigbeeCommands(zigbee.writeAttribute(CLUSTER_MFG, ATTR_MFG_ALLOW_BIND, DataType.UINT8, 1, [mfgCode: mfgCode()]))
}

// Device Event Parsing

void parse(String description) {
    if (state.codeVersion != CODE_VERSION) {
        state.codeVersion = CODE_VERSION
        runInMillis(1500, "autoConfigure")
    }

    Map descMap = zigbee.parseDescriptionAsMap(description)
    if (descMap == null) {
        logDebug "parse() got null descMap from description: ${description}"
        return
    }
    logTrace "parse() - descMap = ${descMap}"

    setPresent()

    if (descMap.attrId != null) {
        parseAttributeReport(descMap)
        descMap.additionalAttrs?.each { Map add ->
            add.cluster = descMap.cluster
            parseAttributeReport(add)
        }
    } else if (descMap.profileId == "0000") {
        logTrace "ZDO command: cluster=${descMap.clusterId} command=${descMap.command} data=${descMap.data}"
    } else if (descMap.profileId == "0104" && descMap.clusterId != null) {
        parseZhaGlobalCommand(descMap)
    } else if (description?.startsWith("enroll request")) {
        logTrace "enroll request: ${description}"
    } else if (description?.startsWith("zone status") || description?.startsWith("zone report")) {
        logTrace "zone status/report: ${description}"
    } else {
        logTrace "Unhandled message: ${descMap}"
    }
}

private void parseZhaGlobalCommand(Map descMap) {
    switch (descMap.command) {
        case "04": parseWriteAttributeResponse(descMap);     return
        case "07": parseConfigureReportingResponse(descMap); return
        case "09": parseReadReportingConfigResponse(descMap); return
        case "0B": parseDefaultResponse(descMap);            return
        default:
            String ackName = constZclAckCmdNames[descMap.command]
            if (ackName) {
                logTrace "${ackName} for cluster ${descMap.clusterId}: data=${descMap.data}"
            } else {
                logTrace "Unhandled ZHA global command: cluster=${descMap.clusterId} command=${descMap.command} value=${descMap.value} data=${descMap.data}"
            }
            return
    }
}

// ZCL Configure Reporting Response: single status byte on full success, else
// per-attr records {status, direction, attrId_lo, attrId_hi}.
private void parseConfigureReportingResponse(Map descMap) {
    List data = descMap.data as List
    if (!data) {
        logDebug "Configure Reporting Response cluster=${descMap.clusterId}: empty data"
        return
    }
    String statusHex = data[0]
    if (statusHex == "00") {
        logDebug "Configure Reporting Response cluster=${descMap.clusterId}: Success"
        return
    }
    String statusName = constZclStatusNames[statusHex] ?: "Unknown"
    if (data.size() >= 4) {
        String attrId = "${data[3]}${data[2]}"
        logWarn "Configure Reporting Response cluster=${descMap.clusterId} attr=0x${attrId}: ${statusName} (0x${statusHex})"
    } else {
        logWarn "Configure Reporting Response cluster=${descMap.clusterId}: ${statusName} (0x${statusHex})"
    }
}

// ZCL Read Reporting Configuration Response record layout:
//   status (1B), direction (1B), attrId_lo, attrId_hi,
//   [on success+direction=0:] dataType (1B), minInterval_lo, minInterval_hi,
//   maxInterval_lo, maxInterval_hi, reportableChange (variable, non-discrete only)
private void parseReadReportingConfigResponse(Map descMap) {
    List data = descMap.data as List
    if (!data || data.size() < 4) {
        logDebug "Read Reporting Config Response cluster=${descMap.clusterId}: short data ${data}"
        return
    }
    String statusHex = data[0]
    String attrId = "${data[3]}${data[2]}"
    if (statusHex != "00") {
        String statusName = constZclStatusNames[statusHex] ?: "Unknown"
        logWarn "Read Reporting Config cluster=${descMap.clusterId} attr=0x${attrId}: ${statusName} (0x${statusHex})"
        return
    }
    if (data.size() < 9) {
        logInfo "Reporting config cluster=${descMap.clusterId} attr=0x${attrId}: status=Success (no min/max/change in record)"
        return
    }
    int minInterval = Integer.parseInt("${data[6]}${data[5]}", 16)
    int maxInterval = Integer.parseInt("${data[8]}${data[7]}", 16)
    String change = (data.size() > 9) ? data[9..-1].reverse().join() : "(discrete)"
    logInfo "Reporting config cluster=${descMap.clusterId} attr=0x${attrId} min=${minInterval}s max=${maxInterval}s change=0x${change}"
}

private void parseWriteAttributeResponse(Map descMap) {
    List data = descMap.data as List
    if (!data) return
    String statusHex = data[0]
    if (statusHex == "00") {
        logTrace "Write Attribute Response cluster=${descMap.clusterId}: Success"
        return
    }
    String statusName = constZclStatusNames[statusHex] ?: "Unknown"
    logWarn "Write Attribute Response cluster=${descMap.clusterId}: ${statusName} (0x${statusHex})"
}

private void parseDefaultResponse(Map descMap) {
    List data = descMap.data as List
    if (!data || data.size() < 2) return
    String ackedCmd = data[0]
    String statusHex = data[1]
    if (statusHex == "00") {
        logTrace "Default Response cluster=${descMap.clusterId} cmd=0x${ackedCmd}: Success"
        return
    }
    String statusName = constZclStatusNames[statusHex] ?: "Unknown"
    logWarn "Default Response cluster=${descMap.clusterId} cmd=0x${ackedCmd}: ${statusName} (0x${statusHex})"
}

private void parseAttributeReport(Map descMap) {
    String cluster = descMap.cluster
    String attrId  = descMap.attrId

    switch (cluster) {
        case "0000": // Basic
            if (attrId == "4000") {
                String version = descMap.value ?: "unknown"
                logInfo "firmware version: ${version}"
                updateDataValue("softwareBuild", version)
            } else if (attrId == "FF01" && descMap.value != null) { // Gen3 LED brightness
                int raw = Integer.parseInt(descMap.value, 16)
                device.updateSetting("prefLedBrightness", [value: raw, type: "number"])
                logDebug "led brightness is ${raw}%"
            }
            return

        case "0006": // On/Off
            handleOnOffCluster(descMap)
            return

        case "0B04": // Electrical Measurement
            handleElectricalCluster(descMap)
            return

        case "0702": // Metering
            handleMeteringCluster(descMap)
            return

        case "FF03": // Mfg-specific
            handleMfgCluster(descMap)
            return

        default:
            logDebug "Unhandled cluster ${cluster} attr ${attrId} value ${descMap.value}"
            return
    }
}

private void handleOnOffCluster(Map descMap) {
    switch (descMap.attrId) {
        case "0000": // on/off state
            String newVal = descMap.value == "00" ? "off" : "on"
            String type = state.isDigital ? "digital" : "physical"
            state.remove("isDigital")
            String curVal = device.currentValue("switch")
            String desc = (curVal != newVal)
                ? "Switch was turned ${newVal} [${type}]"
                : "Switch is ${newVal}"
            if (curVal != newVal) logInfo desc
            sendEvent(name: "switch", value: newVal, type: type, descriptionText: desc)
            return

        case "4003": // power restore
            int raw = Integer.parseInt(descMap.value, 16)
            String label = constPowerRestoreOpts[raw] ?: "0x${descMap.value}"
            logInfo "power restore mode is ${label}"
            device.updateSetting("prefPowerRestore", [value: "${raw}", type: "enum"])
            return

        default:
            logDebug "Unhandled on/off attr ${descMap.attrId} value ${descMap.value}"
            return
    }
}

private void handleElectricalCluster(Map descMap) {
    if (descMap.value == null) return

    switch (descMap.attrId) {
        case "0300": // AC frequency (Hz)
            long raw = Long.parseLong(descMap.value, 16)
            if (raw == INVALID_UINT16) return
            sendEvent(name: "frequency", value: raw, unit: "Hz")
            return

        case "0505": // RMS voltage (UINT16)
            long raw = Long.parseLong(descMap.value, 16)
            if (raw == INVALID_UINT16) return
            applyScaledReport("voltage", new BigDecimal(raw), VOLTAGE_DIVISOR, 0, "V")
            return

        case "0508": // RMS current (UINT16)
            long raw = Long.parseLong(descMap.value, 16)
            if (raw == INVALID_UINT16) return
            applyScaledReport("amperage", new BigDecimal(raw), CURRENT_DIVISOR, 3, "A")
            return

        case "050B": // Active power (INT16 signed)
            if (descMap.value == "8000") return  // ZCL int16s "invalid" sentinel
            int raw = parseSignedHex16(descMap.value)
            applyScaledReport("power", new BigDecimal(raw), POWER_DIVISOR, 1, "W")
            return

        case "0510": // Power factor (INT8 signed, hundredths)
            int pfRaw = parseSignedHex8(descMap.value)
            if (pfRaw < -100 || pfRaw > 100) return  // outside valid PF range (incl. 0x80 = -128)
            BigDecimal pf = new BigDecimal(pfRaw).divide(new BigDecimal(100), 2, RoundingMode.HALF_UP)
            sendEvent(name: "powerFactor", value: pf)
            return

        case "0600": case "0601": case "0602": case "0603": case "0604": case "0605":
            // Diagnostic only
            recordReportedDivisor(descMap.attrId, descMap.value)
            return

        default:
            logDebug "Unhandled electrical attr ${descMap.attrId} value ${descMap.value}"
            return
    }
}

private void handleMeteringCluster(Map descMap) {
    if (descMap.value == null) return

    switch (descMap.attrId) {
        case "0000": // Summation delivered (UINT48)
            long raw = Long.parseLong(descMap.value, 16)
            if (state.captureEnergyOffset) {
                state.energyOffset = raw
                state.remove("captureEnergyOffset")
                logInfo "energy offset captured: ${raw}"
            }
            long counter = raw - ((state.energyOffset ?: 0L) as long)
            if (counter < 0) counter = 0
            BigDecimal kwh = new BigDecimal(counter).divide(new BigDecimal(ENERGY_DIVISOR), 3, RoundingMode.HALF_UP)
            applyScaledReport("energy", kwh, 1, 3, "kWh")
            return

        case "0300": case "0301": case "0302":
            recordReportedDivisor(descMap.attrId, descMap.value)
            return

        default:
            logDebug "Unhandled metering attr ${descMap.attrId} value ${descMap.value}"
            return
    }
}

private void handleMfgCluster(Map descMap) {
    switch (descMap.attrId) {
        case "0001": // on-to-off delay
            int raw = Integer.parseInt(descMap.value, 16)
            device.updateSetting("prefOnToOffDelay", [value: raw, type: "number"])
            logDebug "on-to-off delay is ${raw}s"
            return

        case "0002": // off-to-on delay
            int raw = Integer.parseInt(descMap.value, 16)
            device.updateSetting("prefOffToOnDelay", [value: raw, type: "number"])
            logDebug "off-to-on delay is ${raw}s"
            return

        case "0010": // led brightness
            int raw = Integer.parseInt(descMap.value, 16)
            device.updateSetting("prefLedBrightness", [value: raw, type: "number"])
            logDebug "led brightness is ${raw}%"
            return

        default:
            logDebug "Unhandled mfg attr ${descMap.attrId} value ${descMap.value}"
            return
    }
}

private void applyScaledReport(String name, BigDecimal raw, int divisor, int decimals, String unit) {
    BigDecimal value = (divisor == 1) ? raw : raw.divide(new BigDecimal(divisor), decimals, RoundingMode.HALF_UP)
    value = value.setScale(decimals, RoundingMode.HALF_UP)
    String desc = "${name} is ${value}${unit ? ' ' + unit : ''}"
    if (txtEnable) logInfo desc
    sendEvent(name: name, value: value, unit: unit, type: "physical", descriptionText: desc)
}

private void recordReportedDivisor(String attrId, String hexValue) {
    long value = Long.parseLong(hexValue, 16)
    Map attrs = (state.attributes ?: [:]) as Map
    Long prev = attrs[attrId] as Long
    attrs[attrId] = value
    state.attributes = attrs
    if (prev == null || prev != value) {
        logDebug "device-reported scaling attribute ${attrId} = ${value} (driver uses hardcoded values)"
    }
}

// Health / presence

private void setPresent() {
    state.lastRx = now()
    String current = device.currentValue("healthStatus")
    if (current != "online") {
        sendEvent(name: "healthStatus", value: "online", descriptionText: "${device.displayName} is online")
        if (current != null) logInfo "back online"
    }
}

void checkPresence() {
    long lastRx = (state.lastRx ?: 0) as long
    if (lastRx == 0) return  // no parse yet --- preserve initial healthStatus
    int intervalMin = intSetting(settings.prefHealthCheckInterval, 0)
    long thresholdMs = (intervalMin as long) * 60000L * PRESENT_THRESHOLD
    long silenceMs = now() - lastRx
    if (silenceMs > thresholdMs && device.currentValue("healthStatus") != "offline") {
        sendEvent(name: "healthStatus", value: "offline", descriptionText: "${device.displayName} is offline")
        logWarn "marked offline after ${(silenceMs / 60000) as int} min of silence"
    }
}

private void scheduleDeviceHealthCheck(int intervalMin) {
    int second = (new Random()).nextInt(60)
    // Quartz rejects step values >= 60 in the minutes field, so >=60 routes through the hours field.
    String cron = intervalMin < 60
        ? "${second} */${intervalMin} * ? * * *"
        : "${second} 0 */${(intervalMin / 60) as int} ? * * *"
    schedule(cron, "checkPresence")
    logInfo "health check scheduled every ${intervalMin} minute(s)"
}

// Private methods

private void autoConfigure() {
    logWarn "driver version change detected"
    configure()
}

// Gen3 SKUs register their proprietary attributes under mfg code 0x1407; Gen1/Gen2 use 0x1233.
private boolean isGen3() {
    GEN3_MODELS.contains(device.getDataValue("model"))
}

private String mfgCode() {
    isGen3() ? MFG_CODE_GEN3 : MFG_CODE_GEN2
}

// LED brightness lives on different cluster/attr per generation (see header).
private List<String> ledBrightnessWrite(int brightness) {
    if (isGen3()) {
        return zigbee.writeAttribute(zigbee.BASIC_CLUSTER, ATTR_BASIC_LED_BRIGHTNESS_GEN3,
                                     DataType.UINT8, brightness, [mfgCode: MFG_CODE_GEN3])
    }
    return zigbee.writeAttribute(CLUSTER_MFG, ATTR_MFG_LED_BRIGHTNESS,
                                 DataType.UINT8, brightness, [mfgCode: MFG_CODE_GEN2])
}

private void sendZigbeeCommands(List cmds) {
    if (!cmds) return
    state.lastTx = now()
    hubitat.device.HubMultiAction action = new hubitat.device.HubMultiAction(cmds, hubitat.device.Protocol.ZIGBEE)
    sendHubCommand(action)
}

@CompileStatic
private static int parseSignedHex16(String hex) {
    int raw = Integer.parseInt(hex, 16) & 0xFFFF
    return (raw >= 0x8000) ? raw - 0x10000 : raw
}

@CompileStatic
private static int parseSignedHex8(String hex) {
    int raw = Integer.parseInt(hex, 16) & 0xFF
    return (raw >= 0x80) ? raw - 0x100 : raw
}

// Null-safe alternative to `?:` --- Groovy's Elvis treats 0 as falsy.
private static int intSetting(Object value, int defaultValue) {
    value != null ? (value as Integer) : defaultValue
}

private static BigDecimal decSetting(Object value, BigDecimal defaultValue) {
    value != null ? (value as BigDecimal) : defaultValue
}

// Logging helpers

private void logTrace(String message) {
    if (traceEnable) log.trace "${device} : ${message}"
}

private void logDebug(String message) {
    if (debugEnable) log.debug "${device} : ${message}"
}

private void logInfo(String message) {
    if (txtEnable) log.info "${device} : ${message}"
}

private void logWarn(String message) {
    log.warn "${device} : ${message}"
}

private void logError(String message) {
    log.error "${device} : ${message}"
}

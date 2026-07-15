// Copyright (c) 2026 PJ
// SPDX-License-Identifier: MIT

/*
 *  Third Reality Dual Smart Plug (3RDP01072Z) Device Driver for Hubitat Elevation
 *
 *  Two independently-switched, independently-metered outlets on endpoints 01 and 02.
 *  This driver is the coordinator: it owns all Zigbee I/O and exposes each outlet as a
 *  "Third Reality Outlet (Component)" child carrying switch/power/current/energy/powerFactor.
 *  Shared line measurements (voltage, frequency) live on the parent, not per outlet.
 *  Switching to this type from the stock "Third Reality dual plug" type replaces the stock
 *  component children with the component type automatically (configure() -> ensureChildren()).
 *
 *  configure() sets a per-endpoint reporting profile (min 10 s, wide deltas, frequency
 *  reporting off, voltage on EP01 only) so idle reports stay off the mesh.
 *
 *  ZCL clusters (identical on both endpoints):
 *    0x0006  On/Off
 *    0x0B04  Electrical Measurement (rms voltage/current, active power, frequency, power factor)
 *    0x0702  Metering (energy summation)
 *    0xFF03  Third Reality proprietary (mfg code 0x1407 on this Gen3 hardware):
 *            attr 0x0000 reset-energy, 0x0001 countdown-to-off, 0x0002 countdown-to-on.
 *            LED brightness lives on genBasic (0x0000) attr 0xFF01, mfg 0x1407.
 */

import groovy.transform.Field
import groovy.transform.CompileStatic
import com.hubitat.app.DeviceWrapper
import hubitat.zigbee.zcl.DataType
import java.math.RoundingMode

@Field static final String CODE_VERSION = "0.0.1"

// Both outlets. Kept as strings to match descMap.endpoint / child DNI suffixes.
@Field static final List<String> ENDPOINTS = ["01", "02"]

// Component child driver type (carries voltage/current/powerFactor the stock one lacks).
@Field static final String CHILD_TYPE = "Third Reality Outlet (Component)"

// Third Reality proprietary cluster. This SKU is Gen3 (firmwareMT 1407-*), mfg code 0x1407.
@Field static final int CLUSTER_MFG                 = 0xFF03
@Field static final String MFG_CODE                 = "0x1407"

// ZCL attribute IDs
@Field static final int ATTR_FW_VERSION             = 0x4000
@Field static final int ATTR_ON_OFF                 = 0x0000
@Field static final int ATTR_POWER_RESTORE          = 0x4003
@Field static final int ATTR_AC_FREQUENCY           = 0x0300
@Field static final int ATTR_RMS_VOLTAGE            = 0x0505
@Field static final int ATTR_RMS_CURRENT            = 0x0508
@Field static final int ATTR_ACTIVE_POWER           = 0x050B
@Field static final int ATTR_POWER_FACTOR           = 0x0510
@Field static final int ATTR_SUMMATION              = 0x0000

// Mfg proprietary attributes (0xFF03)
@Field static final int ATTR_MFG_RESET_ENERGY       = 0x0000
@Field static final int ATTR_MFG_ON_TO_OFF_DELAY    = 0x0001
@Field static final int ATTR_MFG_OFF_TO_ON_DELAY    = 0x0002
// Gen3 LED brightness: genBasic (0x0000) attr 0xFF01, mfg 0x1407 (device-wide)
@Field static final int ATTR_BASIC_LED_BRIGHTNESS   = 0xFF01

// Scaling divisors. Device-reported divisors on this vendor are unreliable, so fixed here.
// Energy summation is in Wh: kWh = raw / 1000.
@Field static final int POWER_DIVISOR               = 10        // W
@Field static final int CURRENT_DIVISOR             = 1000      // A
@Field static final int VOLTAGE_DIVISOR             = 10        // V
@Field static final long ENERGY_DIVISOR             = 1000L     // kWh from Wh

// Sentinel for invalid UINT16 (ZCL "no measurement")
@Field static final int INVALID_UINT16              = 0xFFFF

// Below this real-power draw, power factor is undefined (idle load); report 0.
@Field static final BigDecimal IDLE_POWER_W         = 1.0

// Health/presence
@Field static final int PRESENT_THRESHOLD           = 3

@Field static final Map<String, String> constZclAckCmdNames = [
    "04": "Write Attributes Response",
    "07": "Configure Reporting Response",
    "09": "Read Reporting Configuration Response",
    "0B": "Default Response"
]

@Field static final Map<String, String> constZclStatusNames = [
    "00": "Success", "01": "Failure", "7E": "Not Authorized", "80": "Malformed Command",
    "81": "Unsupported Cluster Command", "82": "Unsupported General Command",
    "85": "Invalid Field", "86": "Unsupported Attribute", "87": "Invalid Value",
    "88": "Read Only", "89": "Insufficient Space", "8B": "Not Found",
    "8C": "Unreportable Attribute", "8D": "Invalid Data Type", "94": "Time Out",
    "C3": "Unsupported Cluster"
]

@Field static final Map<Integer, String> constPowerRestoreOpts = [
    0x00: "Off", 0x01: "On", 0xFF: "Last State"
]

@Field static final Map<Integer, String> constHealthCheckIntervalOpts = [
    0: "Disabled", 10: "Every 10 minutes", 15: "Every 15 minutes",
    30: "Every 30 minutes", 60: "Every hour"
]

@Field static final Map<Integer, String> constReportingMinOpts = [
    0: "On any change", 5: "5 seconds", 10: "10 seconds", 30: "30 seconds", 60: "1 minute"
]

@Field static final Map<Integer, String> constReportingMaxOpts = [
    300: "5 minutes", 600: "10 minutes", 1800: "30 minutes", 3600: "1 hour",
    10800: "3 hours", 21600: "6 hours", 43200: "12 hours"
]

metadata {
    definition(
        name: "Third Reality Dual Smart Plug (3RDP01072Z)",
        namespace: "iamtrep",
        author: "pj",
        description: "Zigbee dual-outlet plug with per-outlet power monitoring",
        singleThreaded: true,
        importUrl: "https://raw.githubusercontent.com/iamtrep/hubitat/refs/heads/main/drivers/thirdreality/ThirdReality_3RDP01072Z.groovy"
    ) {
        capability "Actuator"
        capability "Configuration"
        capability "Refresh"
        capability "Sensor"
        capability "VoltageMeasurement"   // shared line voltage — both outlets read identical

        attribute "frequency",    "number"
        attribute "healthStatus", "enum", ["unknown", "offline", "online"]

        command "resetEnergy"
        command "updateFirmware"

        // 3RDP01072Z Smart Dual Plug ZP1 and white-label sibling 3RWP01073Z Smart Wall Plug ZW1.
        fingerprint profileId: "0104", endpointId: "01", inClusters: "0000,0003,0004,0005,0006,0702,0B04,FF03", outClusters: "0019", manufacturer: "Third Reality, Inc", model: "3RDP01072Z", deviceJoinName: "Third Reality Dual Smart Plug"
        fingerprint profileId: "0104", endpointId: "01", inClusters: "0000,0003,0004,0005,0006,0702,0B04,FF03", outClusters: "0019", manufacturer: "Third Reality, Inc", model: "3RWP01073Z", deviceJoinName: "Third Reality Dual Smart Plug"

        preferences {
            input(name: "prefPowerRestore", type: "enum", title: "<b>Power Restore Mode</b>",
                  options: constPowerRestoreOpts, defaultValue: 0xFF,
                  description: "State both outlets return to when power is restored.")

            input(name: "prefLedBrightness", type: "number", title: "<b>LED Brightness</b>",
                  range: "0..100", defaultValue: 100,
                  description: "Indicator LED brightness (0-100%). Device-wide.")

            input(name: "prefDisableOnOff", type: "bool", title: "<b>Disable Power Commands</b>",
                  defaultValue: false,
                  description: "When true, on()/off() on either outlet become no-ops. Safety lockout.")

            input(name: "prefPowerDelta", type: "number", title: "<b>Power Minimum Change (W)</b>",
                  range: "1..1500", defaultValue: 1.0,
                  description: "Minimum power change in watts to emit a report, per outlet.")

            input(name: "prefCurrentDelta", type: "number", title: "<b>Current Minimum Change (A)</b>",
                  range: "0.05..15", defaultValue: 0.05,
                  description: "Minimum current change in amps to emit a report, per outlet.")

            input(name: "prefVoltageDelta", type: "number", title: "<b>Voltage Minimum Change (V)</b>",
                  range: "0.5..50", defaultValue: 5.0,
                  description: "Minimum voltage change in volts to emit a report. Default 5 V — line jitter below this is noise.")

            input(name: "prefEnergyDelta", type: "number", title: "<b>Energy Minimum Change (kWh)</b>",
                  range: "0.001..10", defaultValue: 0.01,
                  description: "Minimum energy change in kWh to emit a report, per outlet.")

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
    state.attributes = [:]   // diagnostic cache of device-reported divisors
    state.codeVersion = CODE_VERSION
    state.lastRx = 0
    state.lastTx = 0
    sendEvent(name: "healthStatus", value: "unknown")
    ensureChildren()
    updated()
}

void updated() {
    logInfo "updated"
    unschedule()
    ensureChildren()

    if (debugEnable) {
        logDebug "settings: ${settings}"
        runIn(1800, "logsOff")
    }

    int healthInterval = intSetting(settings.prefHealthCheckInterval, 0)
    if (healthInterval > 0) scheduleDeviceHealthCheck(healthInterval)

    List<String> cmds = []
    int powerRestore = intSetting(settings.prefPowerRestore, 0xFF)
    // Power restore is per-endpoint on the On/Off cluster.
    ENDPOINTS.each { String ep ->
        cmds += zigbee.writeAttribute(zigbee.ON_OFF_CLUSTER, ATTR_POWER_RESTORE, DataType.ENUM8, powerRestore, [destEndpoint: epInt(ep)])
    }
    // LED brightness is device-wide (endpoint 01).
    int ledBrightness = intSetting(settings.prefLedBrightness, 100)
    cmds += zigbee.writeAttribute(zigbee.BASIC_CLUSTER, ATTR_BASIC_LED_BRIGHTNESS, DataType.UINT8, ledBrightness, [mfgCode: MFG_CODE, destEndpoint: 1])

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

// Child device management

private void ensureChildren() {
    ENDPOINTS.each { String ep ->
        String dni = childDni(ep)
        DeviceWrapper existing = getChildDevice(dni)
        // Replace a stock/mismatched child (e.g. the system driver's "Generic Component
        // Metering Switch", which can't hold voltage/current/powerFactor) with the component type.
        if (existing != null && existing.typeName != CHILD_TYPE) {
            logInfo "replacing child for endpoint ${ep} (was ${existing.typeName})"
            deleteChildDevice(dni)
            existing = null
        }
        if (existing == null) {
            logInfo "creating child for endpoint ${ep}"
            DeviceWrapper cd = addChildDevice("iamtrep", CHILD_TYPE, dni,
                [name: "${device.displayName} EP${ep}", label: "${device.displayName} EP${ep}", isComponent: false])
            cd?.sendEvent(name: "switch", value: "off")
        }
    }
}

private String childDni(String ep) { "${device.id}-${ep}" }

private DeviceWrapper childForEndpoint(String ep) { getChildDevice(childDni(ep)) }

// "160-02" -> "02"
private String endpointFromChild(DeviceWrapper cd) {
    String dni = cd.deviceNetworkId
    int dash = dni.lastIndexOf('-')
    return (dash >= 0) ? dni.substring(dash + 1) : "01"
}

// Configuration

void configure() {
    logTrace "configure()"
    state.codeVersion = CODE_VERSION
    ensureChildren()

    int minInterval = intSetting(settings.prefReportingMin, 10)
    int maxInterval = intSetting(settings.prefReportingMax, 3600)

    BigDecimal powerDelta   = decSetting(settings.prefPowerDelta,   1.0)
    BigDecimal currentDelta = decSetting(settings.prefCurrentDelta, 0.05)
    BigDecimal voltageDelta = decSetting(settings.prefVoltageDelta, 5.0)
    BigDecimal energyDelta  = decSetting(settings.prefEnergyDelta,  0.01)

    int powerDeltaRaw   = Math.max(1, (powerDelta   * POWER_DIVISOR).intValue())
    int currentDeltaRaw = Math.max(1, (currentDelta * CURRENT_DIVISOR).intValue())
    int voltageDeltaRaw = Math.max(1, (voltageDelta * VOLTAGE_DIVISOR).intValue())
    int energyDeltaRaw  = Math.max(1, (energyDelta  * ENERGY_DIVISOR).intValue())

    List<String> cmds = []
    ENDPOINTS.each { String ep ->
        int e = epInt(ep)
        // Bind each metered cluster on this endpoint to the hub so reports flow.
        cmds += "zdo bind 0x${device.deviceNetworkId} 0x${ep} 0x01 0x0006 {${device.zigbeeId}} {}"
        cmds += "zdo bind 0x${device.deviceNetworkId} 0x${ep} 0x01 0x0B04 {${device.zigbeeId}} {}"
        cmds += "zdo bind 0x${device.deviceNetworkId} 0x${ep} 0x01 0x0702 {${device.zigbeeId}} {}"

        // On/off: report immediately on change; hourly heartbeat re-affirms unchanged state.
        cmds += zigbee.configureReporting(zigbee.ON_OFF_CLUSTER,          ATTR_ON_OFF,       DataType.BOOLEAN, 0,           3600,        null, [destEndpoint: e])
        cmds += zigbee.configureReporting(zigbee.ELECTRICAL_MEASUREMENT_CLUSTER, ATTR_ACTIVE_POWER, DataType.INT16,  minInterval, maxInterval, powerDeltaRaw,   [destEndpoint: e])
        cmds += zigbee.configureReporting(zigbee.ELECTRICAL_MEASUREMENT_CLUSTER, ATTR_RMS_CURRENT,  DataType.UINT16, minInterval, maxInterval, currentDeltaRaw, [destEndpoint: e])
        // Voltage is shared across both outlets — report from EP01 only, on the parent.
        if (ep == "01") {
            cmds += zigbee.configureReporting(zigbee.ELECTRICAL_MEASUREMENT_CLUSTER, ATTR_RMS_VOLTAGE, DataType.UINT16, minInterval, maxInterval, voltageDeltaRaw, [destEndpoint: e])
        }
        // PF: wide reportable change so idle-load jitter is silenced but real transitions report.
        cmds += zigbee.configureReporting(zigbee.ELECTRICAL_MEASUREMENT_CLUSTER, ATTR_POWER_FACTOR, DataType.INT8,   minInterval, maxInterval, 25,              [destEndpoint: e])
        // Frequency (0x0300) not configured for reporting — constant 60 Hz. Read once below.
        cmds += zigbee.configureReporting(zigbee.METERING_CLUSTER,        ATTR_SUMMATION,    DataType.UINT48, minInterval, maxInterval, energyDeltaRaw,  [destEndpoint: e])

        // Diagnostic read: device-reported divisors/multipliers.
        cmds += zigbee.readAttribute(zigbee.ELECTRICAL_MEASUREMENT_CLUSTER, [0x0600, 0x0601, 0x0602, 0x0603, 0x0604, 0x0605], [destEndpoint: e])
        cmds += zigbee.readAttribute(zigbee.METERING_CLUSTER, [0x0300, 0x0301, 0x0302], [destEndpoint: e])

        // State refresh for this endpoint.
        cmds += zigbee.readAttribute(zigbee.ON_OFF_CLUSTER, ATTR_ON_OFF, [destEndpoint: e])
        cmds += zigbee.readAttribute(zigbee.ELECTRICAL_MEASUREMENT_CLUSTER, [ATTR_AC_FREQUENCY, ATTR_RMS_VOLTAGE, ATTR_RMS_CURRENT, ATTR_ACTIVE_POWER, ATTR_POWER_FACTOR], [destEndpoint: e])
        cmds += zigbee.readAttribute(zigbee.METERING_CLUSTER, ATTR_SUMMATION, [destEndpoint: e])
    }
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
    ENDPOINTS.each { String ep ->
        int e = epInt(ep)
        cmds += zigbee.readAttribute(zigbee.ON_OFF_CLUSTER, ATTR_ON_OFF, [destEndpoint: e])
        cmds += zigbee.readAttribute(zigbee.ELECTRICAL_MEASUREMENT_CLUSTER, [ATTR_AC_FREQUENCY, ATTR_RMS_VOLTAGE, ATTR_RMS_CURRENT, ATTR_ACTIVE_POWER, ATTR_POWER_FACTOR], [destEndpoint: e])
        cmds += zigbee.readAttribute(zigbee.METERING_CLUSTER, ATTR_SUMMATION, [destEndpoint: e])
    }
    return cmds
}

// Component (child) command handlers

void componentOn(DeviceWrapper cd)      { switchEndpoint(endpointFromChild(cd), "on") }
void componentOff(DeviceWrapper cd)     { switchEndpoint(endpointFromChild(cd), "off") }
void componentRefresh(DeviceWrapper cd) {
    String ep = endpointFromChild(cd)
    logTrace "componentRefresh(${ep})"
    int e = epInt(ep)
    List<String> cmds = []
    cmds += zigbee.readAttribute(zigbee.ON_OFF_CLUSTER, ATTR_ON_OFF, [destEndpoint: e])
    cmds += zigbee.readAttribute(zigbee.ELECTRICAL_MEASUREMENT_CLUSTER, [ATTR_RMS_VOLTAGE, ATTR_RMS_CURRENT, ATTR_ACTIVE_POWER], [destEndpoint: e])
    cmds += zigbee.readAttribute(zigbee.METERING_CLUSTER, ATTR_SUMMATION, [destEndpoint: e])
    sendZigbeeCommands(cmds)
}

// Chain an explicit read after the command: a no-op (already in target state) emits only
// a Default Response, no attribute report, and the command-retry watchdog gives up.
private void switchEndpoint(String ep, String target) {
    if (settings.prefDisableOnOff) {
        logInfo "${target}() on EP${ep} ignored (Disable Power Commands is on)"
        return
    }
    logInfo "EP${ep} ${target}"
    markPendingDigital(ep)
    int e = epInt(ep)
    int cmd = (target == "on") ? 0x01 : 0x00
    List<String> cmds = []
    cmds += zigbee.command(zigbee.ON_OFF_CLUSTER, cmd, [destEndpoint: e])
    cmds += zigbee.readAttribute(zigbee.ON_OFF_CLUSTER, ATTR_ON_OFF, [destEndpoint: e])
    sendZigbeeCommands(cmds)
}

private void markPendingDigital(String ep) {
    Map pending = (state.pendingDigital ?: [:]) as Map
    pending[ep] = true
    state.pendingDigital = pending
    runInMillis(5000, "clearIsDigital")
}

void clearIsDigital() { state.remove("pendingDigital") }

private boolean consumePendingDigital(String ep) {
    Map pending = (state.pendingDigital ?: [:]) as Map
    boolean was = pending[ep] ? true : false
    if (was) { pending.remove(ep); state.pendingDigital = pending }
    return was
}

// Custom commands

void resetEnergy() {
    logInfo "resetEnergy (both outlets)"
    List<String> cmds = []
    Map offsets = (state.energyOffset ?: [:]) as Map
    ENDPOINTS.each { String ep ->
        int e = epInt(ep)
        cmds += zigbee.writeAttribute(CLUSTER_MFG, ATTR_MFG_RESET_ENERGY, DataType.UINT8, 1, [mfgCode: MFG_CODE, destEndpoint: e])
        cmds += zigbee.readAttribute(zigbee.METERING_CLUSTER, ATTR_SUMMATION, [destEndpoint: e])
    }
    state.captureEnergyOffset = true
    sendZigbeeCommands(cmds)
}

List<String> updateFirmware() {
    logInfo "checking for firmware updates"
    return zigbee.updateFirmware()
}

// Device event parsing

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
            add.endpoint = descMap.endpoint
            parseAttributeReport(add)
        }
    } else if (descMap.profileId == "0000") {
        logTrace "ZDO command: cluster=${descMap.clusterId} command=${descMap.command} data=${descMap.data}"
    } else if (descMap.profileId == "0104" && descMap.clusterId != null) {
        parseZhaGlobalCommand(descMap)
    } else {
        logTrace "Unhandled message: ${descMap}"
    }
}

private void parseZhaGlobalCommand(Map descMap) {
    switch (descMap.command) {
        case "04": parseWriteAttributeResponse(descMap);      return
        case "07": parseConfigureReportingResponse(descMap);  return
        case "09": parseReadReportingConfigResponse(descMap); return
        case "0B": parseDefaultResponse(descMap);             return
        default:
            String ackName = constZclAckCmdNames[descMap.command]
            if (ackName) logTrace "${ackName} for cluster ${descMap.clusterId}: data=${descMap.data}"
            else logTrace "Unhandled ZHA global command: cluster=${descMap.clusterId} command=${descMap.command} data=${descMap.data}"
            return
    }
}

private void parseConfigureReportingResponse(Map descMap) {
    List data = descMap.data as List
    if (!data) return
    String statusHex = data[0]
    if (statusHex == "00") {
        logDebug "Configure Reporting Response cluster=${descMap.clusterId} ep=${descMap.endpoint}: Success"
        return
    }
    String statusName = constZclStatusNames[statusHex] ?: "Unknown"
    if (data.size() >= 4) {
        String attrId = "${data[3]}${data[2]}"
        logWarn "Configure Reporting Response cluster=${descMap.clusterId} ep=${descMap.endpoint} attr=0x${attrId}: ${statusName} (0x${statusHex})"
    } else {
        logWarn "Configure Reporting Response cluster=${descMap.clusterId} ep=${descMap.endpoint}: ${statusName} (0x${statusHex})"
    }
}

private void parseReadReportingConfigResponse(Map descMap) {
    List data = descMap.data as List
    if (!data || data.size() < 4) return
    String statusHex = data[0]
    String attrId = "${data[3]}${data[2]}"
    if (statusHex != "00") {
        String statusName = constZclStatusNames[statusHex] ?: "Unknown"
        logWarn "Read Reporting Config cluster=${descMap.clusterId} attr=0x${attrId}: ${statusName} (0x${statusHex})"
        return
    }
    if (data.size() < 9) {
        logInfo "Reporting config cluster=${descMap.clusterId} attr=0x${attrId}: Success (no min/max/change in record)"
        return
    }
    int minInterval = Integer.parseInt("${data[6]}${data[5]}", 16)
    int maxInterval = Integer.parseInt("${data[8]}${data[7]}", 16)
    String change = (data.size() > 9) ? data[9..-1].reverse().join() : "(discrete)"
    logInfo "Reporting config cluster=${descMap.clusterId} ep=${descMap.endpoint} attr=0x${attrId} min=${minInterval}s max=${maxInterval}s change=0x${change}"
}

private void parseWriteAttributeResponse(Map descMap) {
    List data = descMap.data as List
    if (!data) return
    String statusHex = data[0]
    if (statusHex == "00") { logTrace "Write Attribute Response cluster=${descMap.clusterId}: Success"; return }
    String statusName = constZclStatusNames[statusHex] ?: "Unknown"
    logWarn "Write Attribute Response cluster=${descMap.clusterId}: ${statusName} (0x${statusHex})"
}

private void parseDefaultResponse(Map descMap) {
    List data = descMap.data as List
    if (!data || data.size() < 2) return
    String ackedCmd = data[0]
    String statusHex = data[1]
    if (statusHex == "00") { logTrace "Default Response cluster=${descMap.clusterId} cmd=0x${ackedCmd}: Success"; return }
    String statusName = constZclStatusNames[statusHex] ?: "Unknown"
    logWarn "Default Response cluster=${descMap.clusterId} cmd=0x${ackedCmd}: ${statusName} (0x${statusHex})"
}

private void parseAttributeReport(Map descMap) {
    String cluster = descMap.cluster
    String ep = descMap.endpoint ?: "01"

    switch (cluster) {
        case "0000": // Basic
            if (descMap.attrId == "4000") {
                String version = descMap.value ?: "unknown"
                logInfo "firmware version: ${version}"
                updateDataValue("softwareBuild", version)
            } else if (descMap.attrId == "FF01" && descMap.value != null) {
                int raw = Integer.parseInt(descMap.value, 16)
                device.updateSetting("prefLedBrightness", [value: raw, type: "number"])
                logDebug "led brightness is ${raw}%"
            }
            return
        case "0006": handleOnOffCluster(ep, descMap);      return
        case "0B04": handleElectricalCluster(ep, descMap); return
        case "0702": handleMeteringCluster(ep, descMap);   return
        default:
            logDebug "Unhandled cluster ${cluster} ep ${ep} attr ${descMap.attrId} value ${descMap.value}"
            return
    }
}

private void handleOnOffCluster(String ep, Map descMap) {
    switch (descMap.attrId) {
        case "0000":
            String newVal = descMap.value == "00" ? "off" : "on"
            String type = consumePendingDigital(ep) ? "digital" : "physical"
            DeviceWrapper cd = childForEndpoint(ep)
            if (cd == null) { logWarn "no child for endpoint ${ep}"; return }
            String curVal = cd.currentValue("switch")
            String desc = (curVal != newVal) ? "was turned ${newVal} [${type}]" : "is ${newVal}"
            sendToChild(cd, [name: "switch", value: newVal, type: type, descriptionText: "${cd.displayName} ${desc}"])
            return
        case "4003":
            int raw = Integer.parseInt(descMap.value, 16)
            String label = constPowerRestoreOpts[raw] ?: "0x${descMap.value}"
            logInfo "power restore mode (EP${ep}) is ${label}"
            device.updateSetting("prefPowerRestore", [value: "${raw}", type: "enum"])
            return
        default:
            logDebug "Unhandled on/off ep ${ep} attr ${descMap.attrId} value ${descMap.value}"
            return
    }
}

private void handleElectricalCluster(String ep, Map descMap) {
    if (descMap.value == null) return
    DeviceWrapper cd = childForEndpoint(ep)

    switch (descMap.attrId) {
        case "0300": // AC frequency — device-wide, constant. Reported on parent, refresh-only.
            long raw = Long.parseLong(descMap.value, 16)
            if (raw == INVALID_UINT16) return
            sendEvent(name: "frequency", value: raw, unit: "Hz")
            return
        case "0505": // RMS voltage (UINT16) — shared line, reported on the parent (see configure()).
            long raw = Long.parseLong(descMap.value, 16)
            if (raw == INVALID_UINT16) return
            BigDecimal v = new BigDecimal(raw).divide(new BigDecimal(VOLTAGE_DIVISOR), 0, RoundingMode.HALF_UP)
            sendEvent(name: "voltage", value: v, unit: "V", descriptionText: "${device.displayName} voltage is ${v} V")
            return
        case "0508": // RMS current (UINT16)
            long raw = Long.parseLong(descMap.value, 16)
            if (raw == INVALID_UINT16) return
            sendScaledToChild(cd, "amperage", new BigDecimal(raw), CURRENT_DIVISOR, 3, "A")
            return
        case "050B": // Active power (INT16 signed)
            if (descMap.value == "8000") return
            int raw = parseSignedHex16(descMap.value)
            sendScaledToChild(cd, "power", new BigDecimal(raw), POWER_DIVISOR, 1, "W")
            return
        case "0510": // Power factor (INT8 signed, hundredths)
            int pfRaw = parseSignedHex8(descMap.value)
            if (pfRaw < -100 || pfRaw > 100) return
            if (cd == null) return
            BigDecimal power = (cd.currentValue("power") ?: 0) as BigDecimal
            BigDecimal pf = (power < IDLE_POWER_W) ? 0 : new BigDecimal(pfRaw).divide(new BigDecimal(100), 2, RoundingMode.HALF_UP)
            sendToChild(cd, [name: "powerFactor", value: pf])
            return
        case "0600": case "0601": case "0602": case "0603": case "0604": case "0605":
            recordReportedDivisor("0B04-${ep}-${descMap.attrId}", descMap.value)
            return
        default:
            logDebug "Unhandled electrical ep ${ep} attr ${descMap.attrId} value ${descMap.value}"
            return
    }
}

private void handleMeteringCluster(String ep, Map descMap) {
    if (descMap.value == null) return
    DeviceWrapper cd = childForEndpoint(ep)

    switch (descMap.attrId) {
        case "0000": // Summation delivered (UINT48)
            long raw = Long.parseLong(descMap.value, 16)
            Map offsets = (state.energyOffset ?: [:]) as Map
            if (state.captureEnergyOffset) {
                offsets[ep] = raw
                logInfo "energy offset captured (EP${ep}): ${raw}"
                // clear the capture flag only once both endpoints have reported
                if (offsets.size() >= ENDPOINTS.size()) state.remove("captureEnergyOffset")
            }
            state.energyOffset = offsets
            long counter = raw - ((offsets[ep] ?: 0L) as long)
            if (counter < 0) counter = 0
            BigDecimal kwh = new BigDecimal(counter).divide(new BigDecimal(ENERGY_DIVISOR), 3, RoundingMode.HALF_UP)
            sendScaledToChild(cd, "energy", kwh, 1, 3, "kWh")
            return
        case "0300": case "0301": case "0302":
            recordReportedDivisor("0702-${ep}-${descMap.attrId}", descMap.value)
            return
        default:
            logDebug "Unhandled metering ep ${ep} attr ${descMap.attrId} value ${descMap.value}"
            return
    }
}

// Child event helpers

private void sendScaledToChild(DeviceWrapper cd, String name, BigDecimal raw, int divisor, int decimals, String unit) {
    if (cd == null) return
    BigDecimal value = (divisor == 1) ? raw : raw.divide(new BigDecimal(divisor), decimals, RoundingMode.HALF_UP)
    value = value.setScale(decimals, RoundingMode.HALF_UP)
    sendToChild(cd, [name: name, value: value, unit: unit, type: "physical",
                     descriptionText: "${cd.displayName} ${name} is ${value} ${unit}"])
}

private void sendToChild(DeviceWrapper cd, Map event) {
    if (cd == null) return
    cd.parse([event])
}

private void recordReportedDivisor(String key, String hexValue) {
    long value = Long.parseLong(hexValue, 16)
    Map attrs = (state.attributes ?: [:]) as Map
    Long prev = attrs[key] as Long
    attrs[key] = value
    state.attributes = attrs
    if (prev == null || prev != value) {
        logInfo "device-reported scaling ${key} = ${value} (driver uses hardcoded values)"
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
    if (lastRx == 0) return
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
    String cron = intervalMin < 60
        ? "${second} */${intervalMin} * ? * * *"
        : "${second} 0 */${(intervalMin / 60) as int} ? * * *"
    schedule(cron, "checkPresence")
    logInfo "health check scheduled every ${intervalMin} minute(s)"
}

// Private helpers

private void autoConfigure() {
    logWarn "driver version change detected"
    configure()
}

private void sendZigbeeCommands(List cmds) {
    if (!cmds) return
    state.lastTx = now()
    hubitat.device.HubMultiAction action = new hubitat.device.HubMultiAction(cmds, hubitat.device.Protocol.ZIGBEE)
    sendHubCommand(action)
}

@CompileStatic
private static int epInt(String ep) { Integer.parseInt(ep, 16) }

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

private static int intSetting(Object value, int defaultValue) {
    value != null ? (value as Integer) : defaultValue
}

private static BigDecimal decSetting(Object value, BigDecimal defaultValue) {
    value != null ? (value as BigDecimal) : defaultValue
}

// Logging helpers

private void logTrace(String message) { if (traceEnable) log.trace "${device} : ${message}" }
private void logDebug(String message) { if (debugEnable) log.debug "${device} : ${message}" }
private void logInfo(String message)  { if (txtEnable)  log.info  "${device} : ${message}" }
private void logWarn(String message)  { log.warn  "${device} : ${message}" }
private void logError(String message) { log.error "${device} : ${message}" }

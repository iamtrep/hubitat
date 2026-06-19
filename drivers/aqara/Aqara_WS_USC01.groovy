// Copyright (c) 2026 PJ
// SPDX-License-Identifier: MIT

/*
 *  Aqara Wall Switch WS-USC01 / WS-EUK01 (H1, no-neutral, single rocker)
 *
 *  Zigbee models: lumi.switch.b1laus01 (US, WS-USC01), lumi.switch.l1aeu1 (EU, WS-EUK01).
 *  Manufacturer LUMI, manufacturer-specific code 0x115F.
 *
 *  Purpose-built because the no-neutral H1 needs Aqara-specific setup that the
 *  stock "Generic Zigbee Switch" driver does not perform: the manufacturer
 *  "event mode" write (cluster 0xFCC0, attribute 0x0009 = 1) that switches the
 *  device into standard ZCL reporting on a non-Aqara hub. Without it the relay
 *  may actuate but never report state back, so the hub's command-retry watchdog
 *  never sees confirmation and reports the command as failed.
 *
 *  Concept attribution (no code lifted): the 0xFCC0 attribute map
 *  (operation_mode 0x0200, power_outage_memory 0x0201, flip_indicator 0x00F0,
 *  mode 0x0009) is documented in zigbee-herdsman-converters (src/lib/lumi.ts).
 *  The need for the event-mode write and the decoupled-mode semantics are
 *  community knowledge (Z2M, ZHA, kkossev's Hubitat work). All code here is
 *  original to this repository.
 */

import groovy.transform.CompileStatic
import groovy.transform.Field

metadata {
    definition (
        name: "Aqara Wall Switch WS-USC01 (No Neutral)",
        description: "Aqara H1 no-neutral single-rocker wall switch (WS-USC01 / WS-EUK01)",
        namespace: "iamtrep",
        author: "pj",
        singleThreaded: true,
        importUrl: "https://raw.githubusercontent.com/iamtrep/hubitat/main/drivers/aqara/Aqara_WS_USC01.groovy"
    ) {
        capability "Switch"
        capability "Configuration"
        capability "Refresh"
        capability "PushableButton"

        attribute "deviceTemperature", "number"   // internal chip temperature, °C (ZCL DeviceTemperature 0x0002)
        attribute "operationMode", "enum", ["control_relay", "decoupled"]

        fingerprint profileId: "0104", inClusters: "0000,0002,0003,0004,0005,0006,0009", outClusters: "000A,0019",
            manufacturer: "LUMI", model: "lumi.switch.b1laus01", deviceJoinName: "Aqara Wall Switch WS-USC01"
        fingerprint profileId: "0104", inClusters: "0000,0002,0003,0004,0005,0006,0009", outClusters: "000A,0019",
            manufacturer: "LUMI", model: "lumi.switch.l1aeu1", deviceJoinName: "Aqara Wall Switch WS-EUK01"
    }

    preferences {
        input name: "txtEnable",   type: "bool", title: "Enable descriptionText logging", defaultValue: true
        input name: "debugEnable", type: "bool", title: "Enable debug logging",           defaultValue: false, submitOnChange: true
        if (debugEnable) {
            input name: "traceEnable", type: "bool", title: "Enable trace logging",       defaultValue: false
        }

        input name: "operationMode", type: "enum", title: "Rocker mode",
            options: ["control_relay": "Control relay (rocker switches the load)", "decoupled": "Decoupled (rocker is a scene button)"],
            defaultValue: "control_relay",
            description: "Decoupled detaches the rocker from the internal relay so it can drive automations instead."
        input name: "powerOutageMemory", type: "bool", title: "Restore last on/off state after a power outage", defaultValue: true
        input name: "ledIndicatorInverted", type: "bool", title: "Invert the LED indicator", defaultValue: false
    }
}

@Field static final String CODE_VERSION = "1.0.1"

@Field static final String MFG_CODE = "0x115F"

@Field static final int CLUSTER_ON_OFF      = 0x0006
@Field static final int CLUSTER_DEVICE_TEMP = 0x0002
@Field static final int CLUSTER_MULTISTATE  = 0x0012
@Field static final int CLUSTER_LUMI        = 0xFCC0

@Field static final int ATTR_ON_OFF              = 0x0000
@Field static final int ATTR_MEASURED_VALUE      = 0x0000
@Field static final int ATTR_PRESENT_VALUE       = 0x0055
@Field static final int LUMI_ATTR_MODE           = 0x0009   // event-mode init (write 1)
@Field static final int LUMI_ATTR_FLIP_INDICATOR = 0x00F0   // boolean
@Field static final int LUMI_ATTR_OPERATION_MODE = 0x0200   // 0 = decoupled, 1 = control_relay
@Field static final int LUMI_ATTR_POWER_OUTAGE   = 0x0201   // boolean


// ─── Lifecycle ──────────────────────────────────────────────────────────────
//
// Pure local-radio driver: no persistent runtime state or startup work, so
// configure() is the single convergence point (no initialize()). installed()
// defers to it off the install transaction; updated() and deviceTypeUpdated()
// route through it; parse() reconfigures after a code push via runVersionCheck().

void installed() {
    logInfo "Installed"
    runInMillis(1500, "configure")
}

void updated() {
    logInfo "Preferences updated"
    unschedule()
    if (debugEnable || traceEnable) runIn(1800, "logsOff")
    configure()
}

void deviceTypeUpdated() {
    logWarn "Driver type changed — reconfiguring"
    configure()
}

void configure() {
    logInfo "Configuring (operationMode=${operationMode ?: 'control_relay'}, version ${CODE_VERSION})"
    sendEvent(name: "numberOfButtons", value: 1, isStateChange: false)
    state.version = CODE_VERSION

    int opMode  = (operationMode == "decoupled") ? 0x00 : 0x01
    int outage  = powerOutageMemory == false ? 0x00 : 0x01
    int ledFlip = ledIndicatorInverted ? 0x01 : 0x00

    List<String> cmds = []
    // Aqara "event mode" — the key step that makes the device speak standard ZCL
    // (and report on/off) on a non-Aqara hub.
    cmds += zigbee.writeAttribute(CLUSTER_LUMI, LUMI_ATTR_MODE, DataType.UINT8, 0x01, [mfgCode: MFG_CODE])
    // Bind + configure on/off reporting (min 0s, max 600s, report all changes).
    cmds += zigbee.onOffConfig(0, 600)
    // Device-side settings.
    cmds += zigbee.writeAttribute(CLUSTER_LUMI, LUMI_ATTR_OPERATION_MODE, DataType.UINT8,   opMode,  [mfgCode: MFG_CODE])
    cmds += zigbee.writeAttribute(CLUSTER_LUMI, LUMI_ATTR_POWER_OUTAGE,   DataType.BOOLEAN, outage,  [mfgCode: MFG_CODE])
    cmds += zigbee.writeAttribute(CLUSTER_LUMI, LUMI_ATTR_FLIP_INDICATOR, DataType.BOOLEAN, ledFlip, [mfgCode: MFG_CODE])
    // Read back current state so the UI reflects reality.
    cmds += zigbee.readAttribute(CLUSTER_ON_OFF, ATTR_ON_OFF)
    cmds += zigbee.readAttribute(CLUSTER_DEVICE_TEMP, ATTR_MEASURED_VALUE)
    cmds += zigbee.readAttribute(CLUSTER_LUMI, LUMI_ATTR_OPERATION_MODE, [mfgCode: MFG_CODE])

    sendZigbeeCommands(cmds)
}

void refresh() {
    logInfo "Refreshing"
    List<String> cmds = []
    cmds += zigbee.readAttribute(CLUSTER_ON_OFF, ATTR_ON_OFF)
    cmds += zigbee.readAttribute(CLUSTER_DEVICE_TEMP, ATTR_MEASURED_VALUE)
    cmds += zigbee.readAttribute(CLUSTER_LUMI, LUMI_ATTR_OPERATION_MODE, [mfgCode: MFG_CODE])
    sendZigbeeCommands(cmds)
}

// ─── Capability commands ──────────────────────────────────────────────────────

void on() {
    logDebug "on()"
    // Send the command, then read 0x0006 back: zigbee.on() emits only the cluster
    // command, so the explicit read forces a state report that satisfies the hub's
    // command-retry watchdog (no spurious "failed after N retries").
    sendZigbeeCommands(zigbee.on() + zigbee.readAttribute(CLUSTER_ON_OFF, ATTR_ON_OFF))
}

void off() {
    logDebug "off()"
    sendZigbeeCommands(zigbee.off() + zigbee.readAttribute(CLUSTER_ON_OFF, ATTR_ON_OFF))
}

void push(Integer buttonId = 1) {
    sendEvent(name: "pushed", value: buttonId, isStateChange: true)
}

// ─── Zigbee message pipeline ──────────────────────────────────────────────────

void parse(String description) {
    if (!description) return
    runVersionCheck()

    Map descMap = zigbee.parseDescriptionAsMap(description)
    if (!descMap) {
        logWarn "Parse: could not interpret description: ${description}"
        return
    }
    logDebug "Parse: ${descMap}"

    // ZDO command (profile 0x0000) — bind/mgmt responses.
    if (descMap.profileId == "0000") {
        logTrace "ZDO command: cluster=${descMap.clusterId} command=${descMap.command} data=${descMap.data}"
        return
    }
    // ZHA global command with no attribute (Configure Reporting Response, Default Response, ...).
    if (descMap.profileId == "0104" && descMap.attrId == null) {
        logTrace "ZHA global command: cluster=${descMap.clusterId} command=${descMap.command} data=${descMap.data}"
        return
    }
    // Attribute report — primary plus every batched entry.
    if (descMap.attrId != null) {
        parseAttributeReport(descMap)
        descMap.additionalAttrs?.each { Map extra -> parseAttributeReport(descMap + extra) }
        return
    }

    logTrace "Unhandled message: ${descMap}"
}

private void parseAttributeReport(Map descMap) {
    logTrace "parseAttributeReport: ${descMap}"
    Integer clusterInt = descMap.clusterInt
    Integer attrInt    = descMap.attrInt
    String value       = descMap.value

    switch (clusterInt) {
        case CLUSTER_ON_OFF:
            if (attrInt == ATTR_ON_OFF) reportSwitch(value)
            else logTrace "On/Off cluster: unhandled attr ${descMap.attrId} = ${value}"
            return

        case CLUSTER_DEVICE_TEMP:
            if (attrInt == ATTR_MEASURED_VALUE) reportDeviceTemperature(value)
            else logTrace "DeviceTemp cluster: unhandled attr ${descMap.attrId} = ${value}"
            return

        case CLUSTER_MULTISTATE:
            // Sent only in decoupled mode: the rocker as a stateless button.
            if (attrInt == ATTR_PRESENT_VALUE) reportButton(value)
            else logTrace "Multistate cluster: unhandled attr ${descMap.attrId} = ${value}"
            return

        case CLUSTER_LUMI:
            parseLumiAttribute(attrInt, value)
            return

        case 0x0000:
            logTrace "Basic cluster: attr ${descMap.attrId} = ${value}"
            return
    }
    logTrace "Unhandled attribute report: cluster=${descMap.cluster} attr=${descMap.attrId} value=${value}"
}

private void reportSwitch(String value) {
    if (value == null) return
    String sw = (value == "01") ? "on" : "off"
    // The on()/off() read-back and a change report can both surface the same
    // value (the read-back makes no-op commands safe for the retry watchdog).
    // sendEvent dedups the duplicate; log at info only on a real change so the
    // confirmation read doesn't double the log line.
    if (device.currentValue("switch") != sw) {
        logInfo "Switch: ${sw}"
    } else {
        logDebug "Switch: ${sw} (confirmation)"
    }
    sendEvent(name: "switch", value: sw, descriptionText: "${device.displayName} was turned ${sw}")
}

private void reportDeviceTemperature(String value) {
    if (!value) return
    // ZCL DeviceTemperature MeasuredValue is INT16 in °C. parseDescriptionAsMap
    // already presents the value big-endian, so a straight signed parse is correct.
    int celsius = signedInt16(value)
    logDebug "Device temperature: ${celsius} °C"
    sendEvent(name: "deviceTemperature", value: celsius, unit: "°C")
}

private void reportButton(String value) {
    // 0xFF (255) is the idle/no-press value; any other value is an action.
    if (value == null || value == "00FF" || value == "FF") return
    logInfo "Button: pressed (decoupled, multistate value ${value})"
    sendEvent(name: "pushed", value: 1, isStateChange: true)
}

private void parseLumiAttribute(Integer attrInt, String value) {
    if (attrInt == null) return
    switch (attrInt) {
        case LUMI_ATTR_OPERATION_MODE:
            String mode = (value == "00") ? "decoupled" : "control_relay"
            logInfo "Operation mode: ${mode}"
            sendEvent(name: "operationMode", value: mode)
            // Keep the preferences UI in sync with the device (bidirectional sync).
            if ((operationMode ?: "control_relay") != mode) {
                device.updateSetting("operationMode", [value: mode, type: "enum"])
            }
            return
        case LUMI_ATTR_POWER_OUTAGE:
            logDebug "Power-outage memory: ${value}"
            return
        case LUMI_ATTR_FLIP_INDICATOR:
            logDebug "LED indicator inverted: ${value}"
            return
        default:
            logTrace "Lumi cluster: unhandled attr 0x${Integer.toHexString(attrInt)} = ${value}"
    }
}

private void runVersionCheck() {
    // publishCode pushes don't fire updated(); reconfigure on the first frame
    // after a code change. Guarded so a burst of frames only schedules once.
    if (state.version == CODE_VERSION || state.reconfigurePending) return
    state.reconfigurePending = true
    runInMillis(100, "runVersionReconfigure")
}

void runVersionReconfigure() {
    logWarn "Driver upgraded to ${CODE_VERSION} (was ${state.version}), reconfiguring"
    state.remove("reconfigurePending")
    configure()
}

// ─── Primitives ───────────────────────────────────────────────────────────────

private void sendZigbeeCommands(List<String> cmds) {
    logTrace "sendZigbeeCommands: ${cmds}"
    sendHubCommand(new hubitat.device.HubMultiAction(cmds, hubitat.device.Protocol.ZIGBEE))
}

@CompileStatic
private static int signedInt16(String hex) {
    int raw = Integer.parseInt(hex, 16)
    return (raw >= 0x8000) ? raw - 0x10000 : raw
}

// ─── Logging ────────────────────────────────────────────────────────────────

private void logTrace(String message) {
    if (debugEnable && traceEnable) log.trace "${device.displayName}: ${message}"
}

private void logDebug(String message) {
    if (debugEnable) log.debug "${device.displayName}: ${message}"
}

private void logInfo(String message) {
    if (txtEnable) log.info "${device.displayName}: ${message}"
}

private void logWarn(String message) {
    log.warn "${device.displayName}: ${message}"
}

private void logError(String message) {
    log.error "${device.displayName}: ${message}"
}

void logsOff() {
    logWarn "Auto-disabling debug + trace logging"
    device.updateSetting("debugEnable", [value: "false", type: "bool"])
    device.updateSetting("traceEnable", [value: "false", type: "bool"])
}

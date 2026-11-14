// Copyright (c) 2026 PJ
// SPDX-License-Identifier: MIT

/*
 *  Sinope Smart Plug SP2600ZB / SP2610ZB Device Driver for Hubitat Elevation
 *
 *  Prototype: written from the device's published cluster list, not yet run against a device.
 *  Specs for this device : https://support.sinopetech.com/en/1.2.2.6/
 */

import groovy.transform.Field
import java.util.concurrent.ConcurrentHashMap
import groovy.transform.CompileStatic
import java.math.RoundingMode

@Field static final String CODE_VERSION = "0.0.1"
// device.id -> deadline (ms) until which a state report counts as digital. Static rather than
// state: a driver's state is written back whole at method exit, so a command and a parse() on
// another thread can undo each other's flag, and a deadline expires on its own when the device
// was already in the target state and sends no report.
@Field static final ConcurrentHashMap<String, Long> SWITCH_DIGITAL_UNTIL = new ConcurrentHashMap<>()
@Field static final long DIGITAL_WINDOW_MS = 5000L

metadata {
    definition(
        name: "Sinope Smart Plug (SP2600ZB)",
        namespace: "iamtrep",
        author: "pj",
        description: "Zigbee smart plug with power and energy metering (prototype)",
        singleThreaded: true,
        importUrl: "https://raw.githubusercontent.com/iamtrep/hubitat/refs/heads/main/drivers/sinope/Sinope_SP2600ZB.groovy"
    ) {
        capability "Actuator"
        capability "Configuration"
        capability "Initialize"
        capability "Refresh"

        capability "EnergyMeter"
        capability "Outlet"
        capability "PowerMeter"
        capability "Switch"

        preferences {
            input(name: "prefMinPowerChange", type: "number", title: "Power change", description: "Minimum change in power (W) to trigger reporting, 1..100", range: "1..100", defaultValue: 10)
            input(name: "prefMinEnergyChange", type: "number", title: "Energy increment", description: "Minimum increment of the energy meter in Wh to trigger energy reporting, 10..500", range: "10..500", defaultValue: 10)

            input(name: "txtEnable", type: "bool", title: "Enable info logging", defaultValue: true)
            input(name: "debugEnable", type: "bool", title: "Enable debug logging info", defaultValue: false, required: true, submitOnChange: true)
            if (debugEnable) {
                input(name: "traceEnable", type: "bool", title: "Enable trace logging info", defaultValue: false,
                      description: "For driver development/troubleshooting purposes")
            }
        }

        // Cluster lists from the device's simple descriptor; later firmware adds Touchlink (0x1000).
        fingerprint profileId: "0104", endpointId: "01", inClusters: "0000,0003,0006,0702,0B04,FF01", outClusters: "0019", manufacturer: "Sinope Technologies", model: "SP2600ZB", deviceJoinName: "Sinope Smart Plug SP2600ZB"
        fingerprint profileId: "0104", endpointId: "01", inClusters: "0000,0003,0006,0702,0B04,1000,FF01", outClusters: "0019,1000", manufacturer: "Sinope Technologies", model: "SP2600ZB", deviceJoinName: "Sinope Smart Plug SP2600ZB"
        fingerprint profileId: "0104", endpointId: "01", inClusters: "0000,0003,0006,0702,0B04,FF01", outClusters: "0019", manufacturer: "Sinope Technologies", model: "SP2610ZB", deviceJoinName: "Sinope Smart Plug SP2610ZB"
        fingerprint profileId: "0104", endpointId: "01", inClusters: "0000,0003,0006,0702,0B04,1000,FF01", outClusters: "0019,1000", manufacturer: "Sinope Technologies", model: "SP2610ZB", deviceJoinName: "Sinope Smart Plug SP2610ZB"
    }
}

// Constants

// ZCL global command codes the device sends back as routine acks to outbound writes/configures.
// Recognized so parse() can log them concisely (with status) rather than as "Unhandled".
@Field static final Map<String, String> constZclAckCmdNames = [
    "04": "Write Attributes Response",
    "07": "Configure Reporting Response",
    "09": "Read Reporting Configuration Response",
    "0B": "Default Response"
]

// Electrical Measurement scale attributes (0x0B04): the value is raw * multiplier / divisor.
// The plug reports active power only; its published divisor is 10.
@Field static final Map<String, String> constElectricalScaleAttrs = [ "0604": "acPowerMultiplier", "0605": "acPowerDivisor" ]
@Field static final List<Integer> constElectricalScaleAttrIds = [0x0604, 0x0605]

// Driver installation

void installed() {
    // called when device is first created with this driver
    initialize()
    configure()
}

void updated() {
    // called when preferences are saved.
    if (!state.updatedLastRanAt || now() >= state.updatedLastRanAt + 5000) {
        state.updatedLastRanAt = now()
        configure()
    }
}

void uninstalled() {
    // called when device is removed
}

void deviceTypeUpdated() {
    logDebug "driver change detected"
    configure()
}

// Capabilities

void configure() {
    logCfg("configure()")

    state.codeVersion = CODE_VERSION

    unschedule()

    constElectricalScaleAttrs.values().each { state.remove(it) }  // electrical scale: refresh() reads it again
    if (debugEnable || traceEnable) runIn(1800, "logsOff")

    List<String> cmds = []

    // Configure device attribute self-reporting
    cmds += zigbee.configureReporting(0x0006, 0x0000, DataType.BOOLEAN, 0, 43200)   // switch state
    cmds += zigbee.configureReporting(0x0702, 0x0000, DataType.UINT48, 60, 1800, (int) (prefMinEnergyChange ?: 10))  // energy (Wh)
    cmds += zigbee.configureReporting(0x0B04, 0x050B, DataType.INT16, 30, 600, (int) (prefMinPowerChange ?: 10))  // active power (raw units)

    sendZigbeeCommands(cmds)

    // Read some attributes right away
    refresh()
}

void initialize() {
    // Convergence body — install + hub-restart route here. configure() is
    // NOT called from initialize(): re-binding + reconfiguring on every hub
    // startup wastes radio bandwidth (ARCHITECTURE.md "Driver lifecycle").
    logTrace("initialize()")

    SWITCH_DIGITAL_UNTIL.remove(device.id as String)
}

void refresh() {
    List<String> cmds = []

    cmds += zigbee.readAttribute(0x0006, 0x0000) // switch state
    cmds += zigbee.readAttribute(0x0702, 0x0000) // energy (Wh)
    if (state.acPowerDivisor == null) {
        cmds += zigbee.readAttribute(0x0B04, constElectricalScaleAttrIds)
    }
    cmds += zigbee.readAttribute(0x0B04, 0x050B) // active power

    sendZigbeeCommands(cmds)
}

// Chain an explicit readAttribute(0x0006, 0x0000) after the on/off command.
// Without it, a no-op command (device already in target state) emits a
// Default Response but no on-change attribute report — and the platform's
// command-retry watchdog gives up after 5 retries.
void on() {
    List<String> cmds = []
    cmds += zigbee.on()
    cmds += zigbee.readAttribute(0x0006, 0x0000)
    sendZigbeeCommands(cmds)
    markPendingDigitalSwitchChange()
}

void off() {
    List<String> cmds = []
    cmds += zigbee.off()
    cmds += zigbee.readAttribute(0x0006, 0x0000)
    sendZigbeeCommands(cmds)
    markPendingDigitalSwitchChange()
}

// A digital command marks the next state report as digital for DIGITAL_WINDOW_MS. A command the
// device ignores (already in the target state) sends no report, so the mark expires on its own.
private void markPendingDigitalSwitchChange() {
    SWITCH_DIGITAL_UNTIL.put(device.id as String, now() + DIGITAL_WINDOW_MS)
}

private boolean consumeSwitchDigital() {
    Long until = SWITCH_DIGITAL_UNTIL.remove(device.id as String)
    return until != null && now() < until
}

// Device Event Parsing

void parse(String description) {
    if (state.codeVersion != CODE_VERSION) {
        state.codeVersion = CODE_VERSION
        runInMillis 1500, 'autoConfigure'
    }

    Map descMap = zigbee.parseDescriptionAsMap(description)
    logTrace("parse() - description = ${descMap}")

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
        String ackName = constZclAckCmdNames[descMap.command]
        if (ackName) {
            // Default Response (0x0B) payload is [cmd-being-acked, status]; everything else (04/07/09) starts with status
            String status
            if (descMap.command == "0B" && descMap.data?.size() >= 2) {
                status = "${descMap.data[1]} (ack of cmd=${descMap.data[0]})"
            } else {
                status = descMap.data ? descMap.data[0] : "?"
            }
            logTrace("${ackName} for cluster ${descMap.clusterId} status=${status}")
        } else {
            logTrace("Unhandled ZHA global command: cluster=${descMap.clusterId} command=${descMap.command} value=${descMap.value} data=${descMap.data}")
        }
    } else {
        logWarn("Unhandled unknown command ($description): cluster=${descMap.clusterId} command=${descMap.command} value=${descMap.value} data=${descMap.data}")
    }
}

private void parseAttributeReport(Map descMap) {
    Map map = [:]

    // Main switch over all available cluster IDs
    //
    // fingerprint : inClusters: "0000,0003,0006,0702,0B04,FF01" (later firmware adds 1000)
    //
    switch (descMap.cluster) {
        case "0000":  // Basic cluster
        case "0003":  // Identify cluster
            break

        case "0006": // On/Off cluster
            if (descMap.attrId == "0000") {
                String newVal = descMap.value == "00" ? "off" : "on"
                String curVal = device.currentValue("switch")
                boolean changed = (curVal != newVal)
                map.name = "switch"
                map.value = newVal
                // Only a change carries a type: a confirming read with the same value is not a
                // physical action, and must not use up the mark the real change needs.
                if (changed) map.type = consumeSwitchDigital() ? "digital" : "physical"
                map.descriptionText = changed
                    ? "Switch was turned ${newVal} [${map.type}]"
                    : "Switch is ${newVal}"
            }
            break

        case "0702": // Metering cluster
            if (descMap.attrId == "0000") {
                map.name = "energy"
                map.value = getEnergy(descMap.value)
                map.unit = "kWh"
                map.descriptionText = "Cumulative energy delivered is ${map.value} ${map.unit}"
            }
            break

        case "0B04": // Electrical Measurement cluster
            String scaleKey = constElectricalScaleAttrs[descMap.attrId]
            if (scaleKey != null) {
                int scale = Integer.parseInt(descMap.value, 16)
                logTrace("Electrical ${scaleKey} is ${scale}")
                if (scale > 0) {
                    state[scaleKey] = scale
                }
                return
            }
            if (descMap.attrId == "050B") {
                Double watts = scaleElectrical(hexToSignedInt16(descMap.value), "acPower")
                if (watts == null) return
                map.name = "power"
                map.value = Math.round(watts) as Integer
                map.unit = "W"
                map.descriptionText = "Active power is ${map.value} ${map.unit}"
            } else {
                logRx("Unhandled electrical measurement attribute report: ${descMap}")
                return
            }
            break

        case "FF01": // Manufacturer-specific cluster: nothing a user needs is published for the plug
            logRx("Manufacturer-specific attribute report: ${descMap}")
            return

        default:
            break
    }

    if (map) {
        if (map.descriptionText) logInfo("${map.descriptionText}")
        sendEvent(map)
    } else {
        logRx("Unhandled attribute report - cluster ${descMap.cluster} attribute ${descMap.attrId} value ${descMap.value}")
    }
}

// Private methods

private void autoConfigure() {
    logVer "Detected driver version change"
    configure()
}

private void sendZigbeeCommands(List cmds) {
    hubitat.device.HubMultiAction hubAction = new hubitat.device.HubMultiAction(cmds, hubitat.device.Protocol.ZIGBEE)
    sendHubCommand(hubAction)
}

// Electrical values are raw * multiplier / divisor, both read from the device; null until the divisor is known.
private Double scaleElectrical(Object raw, String prefix) {
    if (raw == null || state."${prefix}Divisor" == null) {
        return null
    }
    int value = (raw instanceof String) ? Integer.parseInt(raw as String, 16) : (raw as int)
    int multiplier = (state."${prefix}Multiplier" ?: 1) as int
    return value * multiplier / ((state."${prefix}Divisor") as Double)
}

@CompileStatic
private static Double getEnergy(String value) {
    if (value == null) {
        return null
    }
    // Wh (UnitOfMeasure kWh, Divisor 1000)
    BigInteger energyWh = new BigInteger(value, 16)
    return new BigDecimal(energyWh).divide(new BigDecimal(1000), 3, RoundingMode.HALF_UP).doubleValue()
}

@CompileStatic
private static int hexToSignedInt16(String hex) {
    int v = Integer.parseInt(hex, 16)
    return v > 0x7FFF ? v - 0x10000 : v
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
void logCfg  (String m) { if (txtEnable != false) log.info  logp('🔧') + m }
void logVer  (String m) { log.warn  logp('🏷️') + m }

void logWarn (String m) { log.warn  logp('⚠️') + m }
void logError(String m) { log.error logp('🛑') + m }
void logTrace(String m) { if (traceEnable) log.trace logp('🔬') + m }
void logInfo (String m) { if (txtEnable != false) log.info  "${device.displayName}: ${m}" }
void logDebug(String m) { if (debugEnable) log.debug "${device.displayName}: ${m}" }
void logCmd  (String m) { if (txtEnable != false) log.info  logp('⬆️') + m }
void logNet  (String m) { if (debugEnable) log.debug logp('🌐') + m }
void logSched(String m) { if (debugEnable) log.debug logp('⏰') + m }
void logOta  (String m) { if (txtEnable != false) log.info  logp('📦') + m }

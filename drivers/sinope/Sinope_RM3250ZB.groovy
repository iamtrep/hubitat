// Copyright (c) 2026 PJ
// SPDX-License-Identifier: MIT

/*
 *  Sinope Load Controller RM3250ZB Device Driver for Hubitat Elevation
 *
 *  Specs for this device : https://support.sinopetech.com/en/1.2.2.3/
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
        name: "Sinope Load Controller (RM3250ZB)",
        namespace: "iamtrep",
        author: "pj",
        description: "Zigbee 50A load controller with energy meter",
        singleThreaded: true,
        importUrl: "https://raw.githubusercontent.com/iamtrep/hubitat/refs/heads/main/drivers/sinope/Sinope_RM3250ZB.groovy"
    ) {
        capability "Actuator"
        capability "Configuration"
        capability "Initialize"
        capability "Refresh"

        capability "CurrentMeter"
        capability "EnergyMeter"
        capability "PowerMeter"
        capability "Switch"
        capability "VoltageMeasurement"

        attribute "deviceTemperature", "number"
        attribute "connectedLoad", "number"
        attribute "keypad", "enum", ["locked", "unlocked"]

        command "keypadLock", [[name: "Disable the device's button so it no longer operates the load"]]
        command "keypadUnlock", [[name: "Enable the device's button"]]

        preferences {
            input(name: "prefKeypadLock", title: "Disable the device's button", type: "bool",
                  description: "When true, the button on the device no longer turns the load on or off. Left unset, the device keeps its own setting.")
            input(name: "prefMinPowerChange", type: "number", title: "Power change", description: "Minimum change in power (W) to trigger reporting, 1..100", range: "1..100", defaultValue: 10)
            input(name: "prefMinEnergyChange", type: "number", title: "Energy increment", description: "Minimum increment of the energy meter in Wh to trigger energy reporting, 10..500", range: "10..500", defaultValue: 10)

            input(name: "txtEnable", type: "bool", title: "Enable info logging", defaultValue: true)
            input(name: "debugEnable", type: "bool", title: "Enable debug logging info", defaultValue: false, required: true, submitOnChange: true)
            if (debugEnable) {
                input(name: "traceEnable", type: "bool", title: "Enable trace logging info", defaultValue: false,
                      description: "For driver development/troubleshooting purposes")
            }
        }

        fingerprint profileId: "0104", endpointId: "01", inClusters: "0000,0002,0003,0004,0005,0006,0702,0B04,0B05,FF01", outClusters: "0003,0004,0019", manufacturer: "Sinope Technologies", model: "RM3250ZB", deviceJoinName: "Sinope Load Controller RM3250ZB"
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

// Electrical Measurement scale attributes (0x0B04): the value is raw * multiplier / divisor
@Field static final Map<String, String> constElectricalScaleAttrs = [ "0600": "acVoltageMultiplier", "0601": "acVoltageDivisor",
                                                                      "0602": "acCurrentMultiplier", "0603": "acCurrentDivisor",
                                                                      "0604": "acPowerMultiplier",   "0605": "acPowerDivisor" ]
@Field static final List<Integer> constElectricalScaleAttrIds = [0x0600, 0x0601, 0x0602, 0x0603, 0x0604, 0x0605]

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
    // Device temperature (0x0002) is not configured: the device answers UNREPORTABLE_ATTRIBUTE (0x8C); refresh() reads it
    cmds += zigbee.configureReporting(0x0006, 0x0000, DataType.BOOLEAN, 0, 43200)   // switch state
    cmds += zigbee.configureReporting(0x0702, 0x0000, DataType.UINT48, 60, 1800, (int) (prefMinEnergyChange ?: 10))  // energy (Wh)
    cmds += zigbee.configureReporting(0x0B04, 0x0505, DataType.UINT16, 30, 600, 50)  // voltage (raw units)
    cmds += zigbee.configureReporting(0x0B04, 0x0508, DataType.UINT16, 30, 600, 50)  // current (raw units)
    cmds += zigbee.configureReporting(0x0B04, 0x050B, DataType.INT16, 30, 600, (int) (prefMinPowerChange ?: 10))  // active power


    if (prefKeypadLock != null) {
        cmds += zigbee.writeAttribute(0xFF01, 0x0002, DataType.ENUM8, prefKeypadLock ? 1 : 0, [mfgCode: "0x119C"])
    }

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

    cmds += zigbee.readAttribute(0x0002, 0x0000) // device temperature
    cmds += zigbee.readAttribute(0x0006, 0x0000) // switch state
    cmds += zigbee.readAttribute(0x0702, 0x0000) // energy (Wh)
    if (state.acPowerDivisor == null || state.acVoltageDivisor == null || state.acCurrentDivisor == null) {
        cmds += zigbee.readAttribute(0x0B04, constElectricalScaleAttrIds)
    }
    cmds += zigbee.readAttribute(0x0B04, 0x0505) // voltage
    cmds += zigbee.readAttribute(0x0B04, 0x0508) // current
    cmds += zigbee.readAttribute(0x0B04, 0x050B) // active power

    cmds += zigbee.readAttribute(0xFF01, 0x0002, [mfgCode: "0x119C"]) // keypad lock
    cmds += zigbee.readAttribute(0xFF01, 0x0060, [mfgCode: "0x119C"]) // connected load

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

// Custom commands

void keypadLock() {
    List<String> cmds = []
    cmds += zigbee.writeAttribute(0xFF01, 0x0002, DataType.ENUM8, 1, [mfgCode: "0x119C"])
    cmds += zigbee.readAttribute(0xFF01, 0x0002, [mfgCode: "0x119C"])
    sendZigbeeCommands(cmds)
}

void keypadUnlock() {
    List<String> cmds = []
    cmds += zigbee.writeAttribute(0xFF01, 0x0002, DataType.ENUM8, 0, [mfgCode: "0x119C"])
    cmds += zigbee.readAttribute(0xFF01, 0x0002, [mfgCode: "0x119C"])
    sendZigbeeCommands(cmds)
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
    } else if (description?.startsWith('enroll request')) {
        logRx "Received enroll request"
    } else if (description?.startsWith('zone status')  || description?.startsWith('zone report')) {
        logRx "Zone status: $description"
    } else {
        logWarn("Unhandled unknown command ($description): cluster=${descMap.clusterId} command=${descMap.command} value=${descMap.value} data=${descMap.data}")
    }
}

private void parseAttributeReport(Map descMap) {
    Map map = [:]

    // Main switch over all available cluster IDs
    //
    // fingerprint : inClusters: "0000,0002,0003,0004,0005,0006,0702,0B04,0B05,FF01"
    //
    switch (descMap.cluster) {
        case "0000":  // Basic cluster
            break

        case "0002": // Device Temperature Configuration cluster (whole °C)
            if (descMap.attrId == "0000") {
                map.name = "deviceTemperature"
                map.value = getDeviceTemperature(descMap.value)
                map.unit = getTemperatureScale()
                map.descriptionText = "Device temperature is ${map.value}${map.unit}"
            }
            break

        case "0003": // Identify cluster
        case "0004": // Groups cluster
        case "0005": // Scenes cluster
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
            switch (descMap.attrId) {
                case "0505":
                    Double volts = scaleElectrical(descMap.value, "acVoltage")
                    if (volts == null) return
                    map.name = "voltage"
                    map.value = roundTo(volts, 1)
                    map.unit = "V"
                    map.descriptionText = "Voltage is ${map.value} ${map.unit}"
                    break

                case "0508":
                    Double amps = scaleElectrical(descMap.value, "acCurrent")
                    if (amps == null) return
                    map.name = "amperage"
                    map.value = roundTo(amps, 3)
                    map.unit = "A"
                    map.descriptionText = "Current is ${map.value} ${map.unit}"
                    break

                case "050B":
                    Double watts = scaleElectrical(hexToSignedInt16(descMap.value), "acPower")
                    if (watts == null) return
                    map.name = "power"
                    map.value = Math.round(watts) as Integer
                    map.unit = "W"
                    map.descriptionText = "Active power is ${map.value} ${map.unit}"
                    break

                default:
                    logRx("Unhandled electrical measurement attribute report: ${descMap}")
                    return
            }
            break

        case "0B05": // Diagnostics cluster
            break

        case "FF01": // Manufacturer-specific cluster
            switch (descMap.attrId) {
                case "0002": // keypad lock
                    boolean locked = (descMap.value != "00")
                    map.name = "keypad"
                    map.value = locked ? "locked" : "unlocked"
                    map.descriptionText = "Keypad is ${map.value}"
                    if (prefKeypadLock != null && (prefKeypadLock == true) != locked) {
                        device.updateSetting('prefKeypadLock', [value: locked.toString(), type: 'bool'])
                    }
                    break

                case "0060": // connected load (W), read-only
                    map.name = "connectedLoad"
                    map.value = Integer.parseInt(descMap.value, 16)
                    map.unit = "W"
                    map.descriptionText = "Connected load is ${map.value} ${map.unit}"
                    break

                default:
                    logRx("Unknown manufacturer-specific attribute report: ${descMap}")
                    return
            }
            break

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
private static Double roundTo(Double value, int decimals) {
    return new BigDecimal(value).setScale(decimals, RoundingMode.HALF_UP).doubleValue()
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

private Long getDeviceTemperature(String value) {
    if (value == null) {
        return null
    }
    int celsius = hexToSignedInt16(value)
    if (getTemperatureScale() == "C") {
        return (long) celsius
    } else {
        return Math.round(celsiusToFahrenheit(celsius))
    }
}

@CompileStatic
private static int hexToSignedInt16(String hex) {
    int v = Integer.parseInt(hex, 16)
    return v > 0x7FFF ? v - 0x10000 : v
}

void logsOff() {
    logWarn "debug and trace logging disabled"
    device.updateSetting("debugEnable", [value: "false", type: "bool"])
    device.updateSetting("traceEnable", [value: "false", type: "bool"])
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

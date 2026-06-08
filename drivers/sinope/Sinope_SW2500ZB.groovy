// Copyright (c) 2025-2026 PJ
// SPDX-License-Identifier: MIT

/*
 *  Sinope Switch SW2500ZB Device Driver for Hubitat Elevation
 *
 *  Specs for this device : https://support.sinopetech.com/en/1.2.2.5/
 */

import groovy.transform.Field
import groovy.transform.CompileStatic
import com.hubitat.app.DeviceWrapper
import com.hubitat.app.ChildDeviceWrapper
import com.hubitat.hub.domain.Event
import java.math.RoundingMode

@Field static final String CODE_VERSION = "0.0.20"

metadata {
    definition(
        name: "Sinope Switch (SW2500ZB)",
        namespace: "iamtrep",
        author: "pj",
        description: "Zigbee on/off switch",
        importUrl: "https://raw.githubusercontent.com/iamtrep/hubitat/refs/heads/main/drivers/sinope/Sinope_SW2500ZB.groovy"
    ) {
		capability "Actuator"
		capability "Configuration"
        capability "Initialize"
        capability "Refresh"

        capability "DoubleTapableButton"
        capability "EnergyMeter"
        capability "HoldableButton"
        capability "Light"
        capability "PushableButton"
        capability "ReleasableButton"
        capability "Switch"
        capability "TemperatureMeasurement"

        command "keypadLock", [[name: "Disconnect paddle from relay so it no longer operates the load (a.k.a. smart bulb mode - button events are still sent when the paddle is operated)"]]
        command "keypadUnlock", [[name: "Connect paddle to relay so that the paddle will operate the load."]]

        command "setOnLedIntensity", [[name: "onLedIntensity", type: "NUMBER", description: "LED intensity when switch is ON", constraints: ["NUMBER"]]]
        command "setOnLedColor", [[name:"On LED Color*", type:"ENUM", description:"Color of the LED when the device is off", constraints:["Amber","Fuchsia","Lime","Pearl","Blue"]]]
        command "setOffLedIntensity", [[name: "offLedIntensity", type: "NUMBER", description: "LED intensity when switch is OFF", constraints: ["NUMBER"]]]
        command "setOffLedColor", [[name:"Off LED Color*", type:"ENUM", description:"Color of the LED when the device is off", constraints:["Amber","Fuchsia","Lime","Pearl","Blue"]]]


        preferences {
            input(name: "prefKeypadLock", title: "Disconnect paddle from relay", type: "bool", defaultValue: false,
                  description: "When true, the switch paddle will no longer control the load but button events will be sent (a.k.a. smart bulb mode).")

            input(name: "prefAutoOffTimer", title: "Auto-off timer", type: "enum", defaultValue: 0, options: constTimerPrefMap,
                  description: "Set the dimmer to turn off automatically after specified amount of time")

            input(name: "prefOnLedColor", title: "LED color when ON", type: "enum", defaultValue: 4, options: constLedColorPrefMap)
            input(name: "prefOnLedIntensity", title: "LED intensity when ON", type: "number", defaultValue: 48, range: "0..100")

            input(name: "prefOffLedColor", title: "LED color when OFF", type: "enum", defaultValue: 1, options: constLedColorPrefMap)
            input(name: "prefOffLedIntensity", title: "LED intensity when OFF", type: "number", defaultValue: 48, range: "0..100")

            input(name: "txtEnable", type: "bool", title: "Enable descriptionText logging", defaultValue: true)
            input(name: "debugEnable", type: "bool", title: "Enable debug logging info", defaultValue: false, required: true, submitOnChange: true)
            if (debugEnable) {
                input(name: "traceEnable", type: "bool", title: "Enable trace logging info", defaultValue: false,
                      description: "For driver development/troubleshooting purposes")
            }
        }

        fingerprint profileId: "0104", endpointId:"01", inClusters: "0000,0002,0003,0004,0005,0006,0702,0B05,FF01", outClusters: "0003,0004,0019", manufacturer: "Sinope Technologies", model: "SW2500ZB", deviceJoinName: "Sinope Dimmer SW2500ZB"

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

@Field static final Map constLedColorMap = ["0AFFDC": "Lime", "Lime": "0AFFDC",
                                            "000A4B": "Amber", "Amber" : "000A4B",
                                            "0100A5": "Fuchsia", "Fuchsia": "0100A5",
                                            "64FFFF": "Pearl", "Pearl": "64FFFF",
                                            "FFFF00": "Blue", "Blue": "FFFF00"]

@Field static final Map constLedColorPrefMap = [0: "Lime", "Lime": 0,
                                                1: "Amber", "Amber": 1,
                                                2: "Fuchsia", "Fuchsia": 2,
                                                3: "Pearl", "Pearl": 3,
                                                4: "Blue", "Blue": 4]


@Field static final Map constTimerPrefMap = [0: "disabled",
                                             1: "1 min",
                                             2: "2 min",
                                             3: "5 min",
                                             4: "10 min",
                                             5: "15 min",
                                             6: "30 min",
                                             7: "1h",
                                             8: "2h",
                                             9: "3h"]

@Field static final Map constTimerValueMap = [0: 0,
                                              1: 60,
                                              2: 120,
                                              3: 300,
                                              4: 600,
                                              5: 900,
                                              6: 1800,
                                              7: 3600,
                                              8: 7200,
                                              9: 10800]

// Driver installation

void installed() {
    // called when device is first created with this driver
    initialize()
    configure()
}

void updated() {
    // called when preferences are saved.
    try {
        if (settings.prefKeypadLock != null) {
            settings.prefKeypadLock ? keypadLock() : keypadUnlock()
        }

        if (settings.prefAutoOffTimer != null) {
            setAutoOffTimer(constTimerValueMap[settings.prefAutoOffTimer as int])
        }

        if (settings.prefOnLedColor != null) {
            setOnLedColor(constLedColorPrefMap[settings.prefOnLedColor as int])
        }

        if (settings.prefOffLedColor != null) {
            setOffLedColor(constLedColorPrefMap[settings.prefOffLedColor as int])
        }

        if (settings.prefOnLedIntensity != null) {
            setOnLedIntensity(settings.prefOnLedIntensity as int)
        }

        if (settings.prefOffLedIntensity != null) {
            setOffLedIntensity(settings.prefOffLedIntensity as int)
        }
    } catch (Throwable t) {
        log.error("updated() failed: ${t.message}", t)
    }
}

void uninstalled() {
    // called when device is removed
}

void deviceTypeUpdated() {
    logWarn "driver change detected"
    configure()
}

// Capabilities

void configure() {
    logTrace("configure()")

    state.codeVersion = CODE_VERSION
    state.remove('energyDelivered')

    try
    {
        unschedule()
    }
    catch (e)
    {
        logError("unschedule() threw an exception ${e}")
    }

    List<String> cmds = []

    // Configure device attribute self-reporting
    cmds += zigbee.configureReporting(0x0002, 0x0000, DataType.INT16, 0, 43200)    // device temperature
    cmds += zigbee.configureReporting(0x0006, 0x0000, DataType.BOOLEAN, 0, 43200)  // switch state
    cmds += zigbee.configureReporting(0x0702, 0x0000, DataType.UINT48, 0, 1800)     // energy consumed
    cmds += zigbee.configureReporting(0xFF01, 0x0054, DataType.ENUM8, 0, 0, null, [mfgCode: "0x119C"])  // button action report
    // device rejects this report config with UNSUPPORTED_ATTRIBUTE (status 0x85 in the Configure
    // Reporting Response); kept so the request is on record and so a future firmware that lifts
    // the restriction starts honoring it without a driver change
    cmds += zigbee.configureReporting(0xFF01, 0x0090, DataType.UINT32, 0, 1800, null, [mfgCode: "0x119C"])  // energy

    sendZigbeeCommands(cmds)

    unschedule("refreshEnergyReport")
    runIn(1800, "refreshEnergyReport")

    // Read some attributes right away
    refresh()
}

void initialize() {
    // Convergence body — install + hub-restart route here. configure() is
    // NOT called from initialize(): re-binding + reconfiguring on every hub
    // startup wastes radio bandwidth (ARCHITECTURE.md "Driver lifecycle").
    logTrace("initialize()")

    state.switchTypeDigital = false
    sendEvent(name:"numberOfButtons", value: 2, isStateChange: true)
}

void refresh() {
    List<String> cmds = []

    cmds += zigbee.readAttribute(0x0002, 0x0000) // device temperature
    cmds += zigbee.readAttribute(0x0006, 0x0000) // switch state
    cmds += zigbee.readAttribute(0x0702, 0x0000) // energy

    cmds += zigbee.readAttribute(0xFF01, 0x0050, [mfgCode: "0x119C"])
    cmds += zigbee.readAttribute(0xFF01, 0x0051, [mfgCode: "0x119C"])
    cmds += zigbee.readAttribute(0xFF01, 0x0052, [mfgCode: "0x119C"])
    cmds += zigbee.readAttribute(0xFF01, 0x0053, [mfgCode: "0x119C"])

    cmds += zigbee.readAttribute(0xFF01, 0x0090, [mfgCode: "0x119C"]) // energy delivered

    sendZigbeeCommands(cmds)
}

void refreshEnergyReport() {
    List<String> cmds = []
    cmds += zigbee.readAttribute(0xFF01, 0x0090, [mfgCode: "0x119C"]) // energy delivered
    sendZigbeeCommands(cmds)
    runIn(1800, "refreshEnergyReport")
}

// Chain an explicit readAttribute(0x0006, 0x0000) after the on/off command.
// Without it, a no-op command (device already in target state) emits a
// Default Response but no on-change attribute report — and the platform's
// command-retry watchdog gives up after 5 retries. The read is a directed
// query the device must answer regardless of state transition.
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

// Set switchTypeDigital and arm a safety-net clear. The flag is normally
// cleared by parseAttributeReport on the next 0006/0000 state report — but a
// digital command issued when the device is already in the target state is a
// no-op at the device, so no state report follows and the flag would otherwise
// sit true until the next real state change (which then gets mislabeled as
// digital). The 5s runIn covers any normal Zigbee round-trip; the clear is
// idempotent so the fast path (state report arrives before the timer fires)
// keeps working unchanged.
private void markPendingDigitalSwitchChange() {
    state.switchTypeDigital = true
    runInMillis 5000, 'clearSwitchTypeDigital'
}

private void clearSwitchTypeDigital() {
    state.switchTypeDigital = false
}

void push(Integer buttonNumber) {
    String buttonName = buttonNumber == 0 ? "Up" : "Down"
    String desc = "$buttonName was pushed"
	sendEvent(name:"pushed", value: buttonNumber, type: "digital", descriptionText: desc, isStateChange: true)
}

void hold(Integer buttonNumber) {
    String buttonName = buttonNumber == 0 ? "Up" : "Down"
    String desc = "$buttonName was held"
	sendEvent(name:"held", value: buttonNumber, type: "digital", descriptionText: desc, isStateChange: true)
}

void release(Integer buttonNumber) {
    String buttonName = buttonNumber == 0 ? "Up" : "Down"
    String desc = "$buttonName was released"
	sendEvent(name:"released", value: buttonNumber, type: "digital", descriptionText: desc, isStateChange: true)
}

void doubleTap(Integer buttonNumber) {
    String buttonName = buttonNumber == 0 ? "Up" : "Down"
    String desc = "$buttonName was double-tapped"
	sendEvent(name:"doubleTapped", value: buttonNumber, type: "digital", descriptionText: desc, isStateChange: true)
}

// Custom commands

void keypadLock() {
    List<String> cmds = []
    cmds += zigbee.writeAttribute(0xFF01, 0x0002, DataType.ENUM8, 1, [mfgCode: "0x119C"])
    sendZigbeeCommands(cmds)
}

void keypadUnlock() {
    List<String> cmds = []
    cmds += zigbee.writeAttribute(0xFF01, 0x0002, DataType.ENUM8, 0, [mfgCode: "0x119C"])
    sendZigbeeCommands(cmds)
}

void setAutoOffTimer(Integer duration) {
    List<String> cmds = []
    cmds += zigbee.writeAttribute(0xFF01, 0x00A0, DataType.UINT32, duration, [mfgCode: "0x119C"])
    logTrace("setAutoOffTimer($duration) => $cmds")
    sendZigbeeCommands(cmds)
}

void setOnLedColor(String color) {
    String attrHex = constLedColorMap[color]
    logDebug("Setting ON led color to $color ($attrHex)")
    List<String> cmds = []
    cmds += zigbee.writeAttribute(0xFF01, 0x0050, DataType.UINT24, attrHex, [mfgCode: "0x119C"])
    sendZigbeeCommands(cmds)
}

void setOnLedIntensity(Integer intensity) {
    String hexIntensity = percentToHex(intensity)
    List<String> cmds = []
    cmds += zigbee.writeAttribute(0xFF01, 0x0052, DataType.UINT8, hexIntensity, [mfgCode: "0x119C"])
    sendZigbeeCommands(cmds)
}

void setOffLedColor(String color) {
    String attrHex = constLedColorMap[color]
    logDebug("Setting OFF led color to $color ($attrHex)")
    List<String> cmds = []
    cmds += zigbee.writeAttribute(0xFF01, 0x0051, DataType.UINT24, attrHex, [mfgCode: "0x119C"])
    sendZigbeeCommands(cmds)
}

void setOffLedIntensity(Integer intensity) {
    String hexIntensity = percentToHex(intensity)
    List<String> cmds = []
    cmds += zigbee.writeAttribute(0xFF01, 0x0053, DataType.UINT8, hexIntensity, [mfgCode: "0x119C"])
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
        logDebug "Received enroll request"
    } else if (description?.startsWith('zone status')  || description?.startsWith('zone report')) {
        logDebug "Zone status: $description"
    } else {
        logWarn("Unhandled unknown command ($description): cluster=${descMap.clusterId} command=${descMap.command} value=${descMap.value} data=${descMap.data}")
    }
}


@Field static final Map<String, Map<String, Object>> buttonActionMap = [
    "01": [buttonEvent: "pushed", buttonIndex: 0, description: "Up was pushed"],
    "02": [buttonEvent: "released", buttonIndex: 0, description: "Up was released"],
    "03": [buttonEvent: "held", buttonIndex: 0, description: "Up was held"],
    "04": [buttonEvent: "doubleTapped", buttonIndex: 0, description: "Up was double-tapped"],
    "11": [buttonEvent: "pushed", buttonIndex: 1, description: "Down was pushed"],
    "12": [buttonEvent: "released", buttonIndex: 1, description: "Down was released"],
    "13": [buttonEvent: "held", buttonIndex: 1, description: "Down was held"],
    "14": [buttonEvent: "doubleTapped", buttonIndex: 1, description: "Down was double-tapped"]
]

private void parseAttributeReport(Map descMap) {
    Map map = [:]

    // Main switch over all available cluster IDs
    //
    // fingerprint : inClusters: "0000,0002,0003,0004,0005,0006,0008,0702,0B05,FF01"
    //
    switch (descMap.cluster) {
        case "0000":  // Basic cluster
            break

        case "0002": // Device Temperature Configuration cluster
            if (descMap.attrId == "0000") {
                map.name = "temperature"
                map.value = getTemperature(descMap.value)
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
                map.type = state.switchTypeDigital ? "digital" : "physical"
                state.switchTypeDigital = false
                // "was turned" only when this report represents a real state change vs the
                // platform's current value; otherwise it's a status/scheduled report and the
                // digital-vs-physical label doesn't apply (no source action triggered it).
                map.descriptionText = changed
                    ? "Switch was turned ${newVal} [${map.type}]"
                    : "Switch is ${newVal}"
            }
            break

        case "0702": // Metering cluster
            switch (descMap.attrId) {
                case "0000":
                    // Energy report is in manufacturer-specific cluster/attr (0xFF01/0x0090), ignore standard metering
                    return

                default:
                    break
            }
            break

        case "0B05": // Diagnostics cluster
            break

        case "FF01": // Manufacturer-specific cluster
            switch (descMap.attrId) {
                case "0002": // keypad lock
                    boolean locked = descMap.value > 0
                    map.name = "keypadLock"
                    map.value = locked
                    map.descriptionText = "Keypad ${descMap.commandInt == 0x0A ? 'is' : 'was'} ${locked ? 'locked' : 'unlocked'}"
                    break

                case "0050": // on LED color
                    String color = constLedColorMap[descMap.value]
                    device.updateSetting('prefOnLedColor', [value: "${constLedColorPrefMap[color]}", type: 'enum'])
                    logDebug("On LED color was set to $color => ${constLedColorPrefMap[color]} (${descMap.value})")
                    return

                case "0051": // off LED color
                    String color = constLedColorMap[descMap.value]
                    device.updateSetting('prefOffLedColor', [value: "${constLedColorPrefMap[color]}", type: 'enum'])
                    logDebug("Off LED color was set to $color => ${constLedColorPrefMap[color]} (${descMap.value})")
                    return

                case "0052": // on LED intensity
                    Integer ledIntensity = scaleHexValue(descMap.value)
                    device.updateSetting('prefOnLedIntensity', [value: ledIntensity, type: 'number'])
                    logDebug("On LED intensity was set to $ledIntensity% (0x${descMap.value})")
                    return

                case "0053": // off LED intensity
                    Integer ledIntensity = scaleHexValue(descMap.value)
                    device.updateSetting('prefOffLedIntensity', [value: ledIntensity, type: 'number'])
                    logDebug("Off LED intensity was set to $ledIntensity% (0x${descMap.value})")
                    return

                case "0054": // action report (pushed/released/double tapped)
                    Map<String, Object> action = buttonActionMap[descMap.value]
                    if (action) {
                        map.name = action.buttonEvent
                        map.value = action.buttonIndex
                        map.descriptionText = action.description
                        map.type = "physical"
                        map.isStateChange = true
                    } else {
                        logDebug("Unknown button action report ${descMap}")
                    }
                    break

                case "0090": // watt-hours delivered
                    map.name = "energy"
                    map.value = getEnergy(descMap.value)
                    map.unit = "kWh"
                    map.descriptionText = "Cumulative energy delivered is ${map.value} ${map.unit}"
                    break

                    // TODO
                case "0010": // on intensity
                case "0055": // minimum intensity (0 - 3000)
                case "0058": // double-up = full (0=off, 1=on)
                case "00A0": // auto-off timer setting
                case "00A1": // current remaining timer seconds
                case "0119": // connected load (in watts, always zero)
                case "0200": // status (always zero)
                    logDebug("Unhandled manufacturer-specific attribute report: ${descMap}")
                    return

                default:
                    logDebug("Unknown manufacturer-specific attribute report: ${descMap}")
                    break
            }
            break

        default:
            break
    }

    if (map) {
        if (map.descriptionText) logInfo("${map.descriptionText}")
        sendEvent(map)
    } else {
        logDebug("Unhandled attribute report - cluster ${descMap.cluster} attribute ${descMap.attrId} value ${descMap.value}")
    }
}

// Private methods

private void autoConfigure() {
    logWarn "Detected driver version change"
    configure()
}

private void sendZigbeeCommands(List cmds) {
    hubitat.device.HubMultiAction hubAction = new hubitat.device.HubMultiAction(cmds, hubitat.device.Protocol.ZIGBEE)
    sendHubCommand(hubAction)
}

@CompileStatic
private double getEnergy(String value) {
    if (value != null) {
        Integer energy = Integer.parseInt(value, 16)
        BigDecimal kWh = new BigDecimal(energy.toDouble() / 1000.0 as double)
        kWh = kWh.setScale(3, RoundingMode.HALF_UP)
        logTrace("getEnergy($value) = $energy => $kWh")
        return kWh.doubleValue()
    }
    return 0
}

private Long getTemperature(String value) {
    if (value == null) {
        return null
    }
    int celsius = Integer.parseInt(value, 16)
    if (getTemperatureScale() == "C") {
        return (long) celsius
    } else {
        return Math.round(celsiusToFahrenheit(celsius))
    }
}

@CompileStatic
private String reverseHexString(String hexString) {
	String reversed = ""
	for (int i = hexString.length(); i > 0; i -= 2) {
		reversed += hexString.substring(i - 2, i )
	}
	return reversed
}

@CompileStatic
private String percentToHex(Integer percentValue) {
	BigDecimal hexValue = (percentValue * 255) / 100
	hexValue = hexValue < 0 ? 0 : hexValue
	hexValue = hexValue > 255 ? 255 : hexValue
	return Integer.toHexString(hexValue.intValue())
}

@CompileStatic
private Integer scaleHexValue(String hexValue, double scale = 100.0, double max = 255.0) {
    return Math.round(Integer.parseInt(hexValue, 16).toDouble() * scale / max) as Integer
}

// Logging helpers

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

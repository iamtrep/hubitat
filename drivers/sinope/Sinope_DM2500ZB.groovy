/*
 *  Sinope Dimmer DM2500ZB Device Driver for Hubitat Elevation
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 *  Specs for this device : https://support.sinopetech.com/en/1.2.3.3/
 *
 */

import groovy.transform.Field
import groovy.transform.CompileStatic
import com.hubitat.app.DeviceWrapper
import com.hubitat.app.ChildDeviceWrapper
import com.hubitat.hub.domain.Event
import java.math.RoundingMode

@Field static final String version = "0.0.6"

metadata {
    definition(
        name: "Sinope Dimmer (DM2500ZB)",
        namespace: "iamtrep",
        author: "pj",
        importUrl: "https://raw.githubusercontent.com/iamtrep/hubitat/main/drivers/sinope/Sinope_DM2500ZB.groovy"
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
        capability "SwitchLevel"
        capability "TemperatureMeasurement"

        command "keypadLock", [[name: "Disconnect paddle from relay so it no longer operates the load (a.k.a. smart bulb mode - button events are still sent when the paddle is operated)"]]
        command "keypadUnlock", [[name: "Connect paddle to relay so that the paddle will operate the load."]]

        command "setOnIntensity", [[name: "onIntensity", type: "NUMBER", description: "Dimmer intensity when switch is turned ON", constraints: ["NUMBER"]]]

        command "setOnLedIntensity", [[name: "onLedIntensity", type: "NUMBER", description: "LED intensity when switch is ON", constraints: ["NUMBER"]]]
        command "setOnLedColor", [[name:"On LED Color*", type:"ENUM", description:"Color of the LED when the device is off", constraints:["Amber","Fuchsia","Lime","Pearl","Blue"]]]
        command "setOffLedIntensity", [[name: "offLedIntensity", type: "NUMBER", description: "LED intensity when switch is OFF", constraints: ["NUMBER"]]]
        command "setOffLedColor", [[name:"Off LED Color*", type:"ENUM", description:"Color of the LED when the device is off", constraints:["Amber","Fuchsia","Lime","Pearl","Blue"]]]

        preferences {
            input(name: "prefKeypadLock", title: "Disconnect paddle from relay", type: "bool", defaultValue: false,
                  description: "When true, the dimmer paddle will no longer control the load (a.k.a. smart bulb mode)")

            input(name: "prefAutoOffTimer", title: "Auto-off timer", type: "enum", defaultValue: 0, options: constTimerPrefMap,
                  description: "Set the dimmer to turn off automatically after specified amount of time")

            input(name: "prefOnLedColor", title: "On LED Color", type: "enum", defaultValue: 4, options: constLedColorPrefMap)
            input(name: "prefOnLedIntensity", title: "LED intensity when ON", type: "number", defaultValue: 48, range: "0..100")

            input(name: "prefOffLedColor", title: "Off LED Color", type: "enum", defaultValue: 1, options: constLedColorPrefMap)
            input(name: "prefOffLedIntensity", title: "LED intensity when OFF", type: "number", defaultValue: 48, range: "0..100")

            input(name: "txtEnable", type: "bool", title: "Enable descriptionText logging", defaultValue: true)
            input(name: "debugEnable", type: "bool", title: "Enable debug logging info", defaultValue: false, required: true, submitOnChange: true)
            if (debugEnable) {
                input(name: "traceEnable", type: "bool", title: "Enable trace logging info", defaultValue: false,
                      description: "For driver development/troubleshooting purposes")
            }
        }

        fingerprint profileId: "0104", endpointId:"01", inClusters: "0000,0002,0003,0004,0005,0006,0008,0702,0B05,FF01", outClusters: "0003,0004,0019", manufacturer: "Sinope Technologies", model: "DM2500ZB", deviceJoinName: "Sinope Dimmer DM2500ZB"

    }
}

// Constants
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

// Driver installation, device config & update

void installed() {
    // called when device is first created with this driver
    initialize()
}

void updated() {
    // called when preferences are saved.
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
        setOnLedIntensity(settings.prefOnLedIntensity)
    }

    if (settings.prefOffLedIntensity != null) {
        setOffLedIntensity(settings.prefOffLedIntensity)
    }

    //configure()
}

void uninstalled() {
    // called when device is removed
}

// Capabilities

void configure() {
    logTrace("configure()")

    state.codeVersion = version
    state.debugMode = debugMode

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
    cmds += zigbee.configureReporting(0x0002, 0x0000, DataType.INT16, 0, 43200)   // device temperature
    cmds += zigbee.configureReporting(0x0006, 0x0000, DataType.BOOLEAN, 0, 43200) // switch state
    cmds += zigbee.configureReporting(0x0008, 0x0000, DataType.UINT8, 0, 43200)   // switch level
    cmds += zigbee.configureReporting(0x0702, 0x0000, DataType.UINT48, 0, 1800)   // energy
    cmds += zigbee.configureReporting(0xFF01, 0x0054, DataType.ENUM8, 0, 43200, null, [mfgCode: "0x119C"])  // button action report
    cmds += zigbee.configureReporting(0xFF01, 0x0090, DataType.UINT32, 0, 1800, null, [mfgCode: "0x119C"])  // energy

    sendZigbeeCommands(cmds)

    unschedule("refreshEnergyReport")
    runIn(1800, "refreshEnergyReport")

    // Read some attributes right away
    refresh()
}

void initialize() {
    logTrace("initialize()")

    // state.clear()
    state.switchTypeDigital = false
    state.levelTypeDigital = false

    sendEvent(name:"numberOfButtons", value: 2, isStateChange: true)

    configure()
}

void refresh() {
    List<String> cmds = []

    cmds += zigbee.readAttribute(0x0002, 0x0000) // device temperature
    cmds += zigbee.readAttribute(0x0006, 0x0000) // switch state
    cmds += zigbee.readAttribute(0x0008, 0x0000) // switch level
    cmds += zigbee.readAttribute(0x0702, 0x0000) // energy

    cmds += zigbee.readAttribute(0xFF01, 0x0050, [mfgCode: "0x119C"]) // LED on colour
    cmds += zigbee.readAttribute(0xFF01, 0x0051, [mfgCode: "0x119C"]) // LED off colour
    cmds += zigbee.readAttribute(0xFF01, 0x0052, [mfgCode: "0x119C"]) // LED on intensity
    cmds += zigbee.readAttribute(0xFF01, 0x0053, [mfgCode: "0x119C"]) // LED off intensity

    cmds += zigbee.readAttribute(0xFF01, 0x0090, [mfgCode: "0x119C"]) // energy delivered

    sendZigbeeCommands(cmds)
}

void refreshEnergyReport() {
    cmds += zigbee.readAttribute(0xFF01, 0x0090, [mfgCode: "0x119C"]) // energy delivered
    sendZigbeeCommands(cmds)
    runIn(1800, "refreshEnergyReport")
}

void on() {
    List<String> cmds = []
    cmds += zigbee.command(0x0006, 0x01)
    sendZigbeeCommands(cmds)
    state.switchTypeDigital = true
}

void off() {
    List<String> cmds = []
    cmds += zigbee.command(0x0006, 0x00)
    sendZigbeeCommands(cmds)
    state.switchTypeDigital = true
}

void setLevel(String level, String duration = '0') {
    setLevel(Integer.parseInt(level), Integer.parseInt(duration))
}

void setLevel(BigDecimal level, BigDecimal duration = 0) {
    state.levelTypeDigital = true
    List<String> cmds = []
    cmds += zigbee.setLevel(level, duration)
    //logTrace("zigbee.setLevel($level,$duration) = $cmds")
    sendZigbeeCommands(cmds)
}

void push(buttonNumber) {
    String buttonName = buttonNumber == 0 ? "Up" : "Down"
    String desc = "$buttonName was pushed"
	sendEvent(name:"pushed", value: buttonNumber, type: "digital", descriptionText: desc) //, isStateChange:true)
}

void hold(buttonNumber) {
    String buttonName = buttonNumber == 0 ? "Up" : "Down"
    String desc = "$buttonName was held"
	sendEvent(name:"held", value: buttonNumber, type: "digital", descriptionText: desc) //, isStateChange:true)
}

void release(buttonNumber) {
    String buttonName = buttonNumber == 0 ? "Up" : "Down"
    String desc = "$buttonName was released"
	sendEvent(name:"released", value: buttonNumber, type: "digital", descriptionText: desc) //, isStateChange:true)
}

void doubleTap(buttonNumber) {
    String buttonName = buttonNumber == 0 ? "Up" : "Down"
    String desc = "$buttonName was double-tapped"
	sendEvent(name:"doubleTapped", value: buttonNumber, type: "digital", descriptionText: desc) //, isStateChange:true)
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

void setAutoOffTimer(duration) {
    List<String> cmds = []
    cmds += zigbee.writeAttribute(0xFF01, 0x00A0, DataType.UINT32, duration as int, [mfgCode: "0x119C"])
    logTrace("setAutoOffTimer($duration) => $cmds")
    sendZigbeeCommands(cmds)
}

void setOnIntensity(intensity) {
    // todo
}

void setOnLedColor(String color) {
    String attrHex = constLedColorMap[color]
    logDebug("Setting ON led color to $color ($attrHex)")
    List<String> cmds = []
    cmds += zigbee.writeAttribute(0xFF01, 0x0050, DataType.UINT24, attrHex, [mfgCode: "0x119C"])
    sendZigbeeCommands(cmds)
}

void setOnLedIntensity(intensity) {
    String hexIntensity = percentToHex(intensity as Integer)
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

void setOffLedIntensity(intensity) {
    String hexIntensity = percentToHex(intensity as Integer)
    List<String> cmds = []
    cmds += zigbee.writeAttribute(0xFF01, 0x0053, DataType.UINT8, hexIntensity, [mfgCode: "0x119C"])
    sendZigbeeCommands(cmds)
}

// Device Event Parsing

List parse(String description) {
    Map descMap = zigbee.parseDescriptionAsMap(description)
    logTrace("parse() - description = ${descMap}")

    List result = []

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

private Map parseAttributeReport(descMap){
    Map map = [: ]

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
                map.name = "switch"
                map.value = descMap.value == "00" ? "off" : "on"
                map.type = state.switchTypeDigital ? "digital" : "physical"
                state.switchTypeDigital = false
                map.descriptionText = "Switch is ${map.value} [${map.type}]"
            }
            break

        case "0008": // Level Control cluster
            switch (descMap.attrId) {
                case "0000":
                    // Current level (0-255)
                    Integer dimmerLevel = scaleHexValue(descMap.value)
                    map.name = "level"
                    map.value = dimmerLevel
                    map.unit = "%"
                    map.descriptionText = "Dimmer level was set to ${map.value}${map.unit}"
                    map.type = state.levelTypeDigital ? "digital" : "physical"
                    state.levelTypeDigital = false
                    break

                case "0011":
                    // "On" level (preset) - TODO
                    Integer dimmerLevel = scaleHexValue(descMap.value)
                    logDebug("On level preset = $dimmerLevel - IGNORED")
                    break

                default:
                    break
            }
            break

        case "0702": // Metering cluster
            switch (descMap.attrId) {
                case "0000":
                    return null // energy report is in mfg-specific cluster/attr
                    map.name = "energy"
                    map.value = getEnergy(descMap.value)
                    map.unit = "kWh"
                    map.descriptionText = "Cumulative energy consumed is ${map.value} ${map.unit}"
                    break

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
                    map.descriptionText = locked ? "Keypad was locked" : "Keypad was unlocked"
                    break

                case "0050": // on LED color
                    String color = constLedColorMap[descMap.value]
                    device.updateSetting('prefOnLedColor', [value: "${constLedColorPrefMap[color]}", type: 'enum'])
                    logDebug("On LED color was set to $color => ${constLedColorPrefMap[color]} (${descMap.value})")
                    return null // return directly, no event to generate

                case "0051": // off LED color
                    String color = constLedColorMap[descMap.value]
                    device.updateSetting('prefOffLedColor', [value: "${constLedColorPrefMap[color]}", type: 'enum'])
                    logDebug("Off LED color was set to $color => ${constLedColorPrefMap[color]} (${descMap.value})")
                    return null // return directly, no event to generate

                case "0052": // on LED intensity
                    Integer ledIntensity = scaleHexValue(descMap.value)
                    device.updateSetting('prefOnLedIntensity', ledIntensity.toString())
                    logDebug("On LED intensity was set to $ledIntensity% (0x${descMap.value})")
                    return null // return directly, no event to generate

                case "0053": // off LED intensity
                    Integer ledIntensity = scaleHexValue(descMap.value)
                    device.updateSetting('prefOffLedIntensity', ledIntensity.toString())
                    logDebug("Off LED intensity was set to $ledIntensity% (0x${descMap.value})")
                    return null // return directly, no event to generate

                case "0054": // action report (pushed/released/double tapped)
                    Map<String, Object> action = buttonActionMap[descMap.value]
                    if (action) {
                        map.name = action.buttonEvent
                        map.value = action.buttonIndex
                        map.descriptionText = action.description
                        map.type = "physical"
                    } else {
                        logDebug("Unknown button action report ${descMap}")
                    }
                    break

                case "0090": // watt-hours delivered
                    state.energyDelivered = getEnergy(descMap.value)
                    //logInfo("Energy report: ${state.energyDelivered}")
                    //return null // return directly, no event to generate
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
                    break

                default:
                    logDebug("Unknown manufacturer-specific attribute report: ${descMap}")
                    break
            }
            break

        default:
            break
    }

    Map result = null

    if (map) {
        if (map.descriptionText) logInfo("${map.descriptionText}")
        result = createEvent(map)
    } else {
        logDebug("Unhandled attribute report - cluster ${descMap.cluster} attribute ${descMap.attrId} value ${descMap.value}")
    }

    return result
}

// Private methods

private void sendZigbeeCommands(cmds) {
    def hubAction = new hubitat.device.HubMultiAction(cmds, hubitat.device.Protocol.ZIGBEE)
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

private long getTemperature(value) {
    if (value != null) {
        def celsius = Integer.parseInt(value, 16)
        if (getTemperatureScale() == "C") {
            return celsius
        } else {
            return Math.round(celsiusToFahrenheit(celsius))
        }
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

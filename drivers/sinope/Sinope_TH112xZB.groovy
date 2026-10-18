/*
 *  Sinope TH112xZB Line-Voltage Thermostat Device Driver for Hubitat
 *
 *
 *  Code derived from Sinope's SmartThing thermostat for their Zigbee protocol requirements, from the driver of erilaj and from the TH13X0ZB driver
 *  Source: https://github.com/sacua/SinopeDriverHubitat/blob/main/drivers/TH112xZB_Sinope_Hubitat.groovy
 *  Source: https://github.com/erilaj/hubitat/blob/main/drivers/Sinope/erilaj-Sinope-TH1300ZB.groovy
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
 * v0.0.1 - Initial version
 *
 */

import groovy.transform.Field
import groovy.transform.CompileStatic
import java.math.RoundingMode

@Field static final String CODE_VERSION = "0.0.1"

@Field static final List<String> SUPPORTED_THERMOSTAT_MODES     = ['"off"', '"heat"']
@Field static final List<String> SUPPORTED_THERMOSTAT_FAN_MODES = ['"auto"']

metadata
{
    definition(
        name: 'Sinope Thermostat (TH112xZB)',
        namespace: 'iamtrep',
        author: 'pj',
        description: 'Sinope Zigbee Line-Voltage Thermostat (TH1123ZB, TH1124ZB and their G2 versions)',
        singleThreaded: true,
        importUrl: "https://raw.githubusercontent.com/iamtrep/hubitat/refs/heads/main/drivers/sinope/Sinope_TH112xZB.groovy"
    ) {
        capability 'Actuator'
        capability 'Configuration'
        capability 'Initialize'
        capability 'Refresh'
        capability 'CurrentMeter'
        capability 'PowerMeter'
        capability 'EnergyMeter'
        capability 'TemperatureMeasurement'
        capability 'Thermostat'
        capability 'VoltageMeasurement'

        attribute 'outdoorTemperature', 'number'

        attribute 'keypad', 'enum', ['locked', 'unlocked']

        attribute 'heatingDemand', 'number'
        attribute 'maxPower', 'number'
        attribute 'connectedLoad', 'number'

        command 'setClockTime'
        command 'setKeypadLockoutMode', [ [name: "lockoutMode*",
                                           type: "ENUM",
                                           description: "Lock/unlock the thermostat's keypad",
                                           constraints:["lock","unlock"]] ]
        command 'setDynamicRatingMode', [ [name: "drMode*",
                                           type: "ENUM",
                                           description: "Control the flashing DR icon on the thermostat display",
                                           constraints:["on","off"]] ]
        command 'setBacklightMode', [ [name: "backlightMode*",
                                       type: "ENUM",
                                       description: "Set the thermostat display backlight mode",
                                       constraints:["adaptive","on","off"]] ]
        command 'setOutdoorTemperature', [ [name: "outdoorTemperature*",
                                            type: "NUMBER",
                                            description: "Outdoor temperature in ${getTemperatureScale()}"]]

        fingerprint profileId: '0104', endpointId: '01', inClusters: '0000,0003,0004,0005,0201,0204,0402,0702,0B04,0B05,FF01', outClusters: '000A,FF01,0019', manufacturer: 'Sinope Technologies', model: 'TH1123ZB', deviceJoinName: 'Sinope Thermostat TH1123ZB'
        fingerprint profileId: '0104', endpointId: '01', inClusters: '0000,0003,0004,0005,0201,0204,0402,0702,0B04,0B05,FF01', outClusters: '000A,FF01,0019', manufacturer: 'Sinope Technologies', model: 'TH1124ZB', deviceJoinName: 'Sinope Thermostat TH1124ZB'
        fingerprint profileId: '0104', endpointId: '01', inClusters: '0000,0003,0004,0005,0201,0204,0402,0702,0B04,0B05,FF01', outClusters: '0003,000A,0019', manufacturer: 'Sinope Technologies', model: 'TH1123ZB-G2', deviceJoinName: 'Sinope Thermostat TH1123ZB-G2'
        fingerprint profileId: '0104', endpointId: '01', inClusters: '0000,0003,0004,0005,0201,0204,0402,0702,0B04,0B05,FF01', outClusters: '0003,000A,0019', manufacturer: 'Sinope Technologies', model: 'TH1124ZB-G2', deviceJoinName: 'Sinope Thermostat TH1124ZB-G2'
    }

    preferences {
        input name: 'prefBacklightMode', type: 'enum', title: 'Display backlight', options: ['off': 'On Demand', 'adaptive': 'Adaptive (default)', 'on': 'Always On'], defaultValue: 'adaptive', required: true
        input name: 'prefDisplayOutdoorTemp', type: 'bool', title: 'Display outdoor temperature', defaultValue: true
        input name: 'prefSecondTempDisplay', type: 'enum', title: 'Secondary Temp. Display', options:['auto': 'Auto (default)', 'setpoint': 'Setpoint', 'outdoor': 'Outdoor'], defaultValue: 'auto', required: true
        input name: 'prefTimeFormatParam', type: 'enum', title: 'Time Format', options:['24h', '12h AM/PM'], defaultValue: '24h', multiple: false, required: true
        input name: 'prefMinHeatingSetpoint', type: 'number', title: 'Min heat setpoint (5C to 30C / 41F to 86F)', description: 'Clamps the lowest heat setpoint the device will accept.', range: '5..86', required: false
        input name: 'prefMaxHeatingSetpoint', type: 'number', title: 'Max heat setpoint (5C to 30C / 41F to 86F)', description: 'Clamps the highest heat setpoint the device will accept.', range: '5..86', required: false
        input name: 'prefCycleLength', type: 'enum', title: 'Heating cycle length', options: ['short': 'Short (15s)', 'long': 'Long (15 min, fan heaters)'], required: false
        input name: 'prefDynamicRatingPI', type: 'enum', title: 'Limit PI heating', description: 'Limit PI heating when DR Icon is on', options:[255: '100 (default)', 75: '75', 50: '50', 25: '25'], defaultValue: '255', required: true

        input name: 'prefMinTempChange', type: 'number', title: 'Temperature change', description: 'Minumum change of temperature reading to trigger report in Celsius/100, 5..50', range: '5..50', defaultValue: 50
        input name: 'prefMinPIChange', type: 'number', title: 'Heating change', description: 'Minimum change in the PI heating in % to trigger power and PI heating reporting, 1..25', range: '1..25', defaultValue: 5
        input name: 'prefMinEnergyChange', type: 'number', title: 'Energy increment', description: 'Minimum increment of the energy meter in Wh to trigger energy reporting, 10..', range: '10..', defaultValue: 10
        input name: 'txtEnable',   type: 'bool', title: 'Enable info logging', defaultValue: true
        input name: 'debugEnable', type: 'bool', title: 'Enable debug level logging', defaultValue: false, submitOnChange: true
        if (debugEnable) {
            input name: 'traceEnable', type: 'bool', title: 'Enable trace level logging', description: 'For driver development', defaultValue: false
        }
    }
}

// First generation: Simplebacklight, 1 = adaptive ("sensing"); 'on' has no value of its own and maps to it.
@Field static final Map constBacklightModes = [ 'off': 0x0, 'adaptive': 0x1, 'on': 0x1 ]
// G2: 0 on demand, 1 always on, 2 adaptive (claudegel Backlight, sacua 2589409; Z2M's backlight_auto_dim 1 "off" = no dimming)
@Field static final Map constBacklightModesG2 = [ 'off': 0x0, 'on': 0x1, 'adaptive': 0x2 ]
@Field static final Map constSecondTempDisplayModes =  [ 0x0 : 'auto', 0x01: 'setpoint', 0x02: 'outdoor',
                                                        'auto': 0x0, 'setpoint': 0x1, 'outdoor': 0x2 ]
@Field static final Map constThermostatCycles = [ 'short': 0x000F, 'long': 0x0384,
                                                 0x000F: 'short', 0x0384: 'long']

@Field static final Map constThermostatModes = [ '00': 'off', '04': 'heat' ]

@Field static final Map constKeypadLockoutMap = [ '00': 'unlocked', '01': 'locked' ]

// Electrical Measurement scale attributes (0x0B04): the value is raw * multiplier / divisor
@Field static final Map constElectricalScaleAttrs = [ 0x0600: 'acVoltageMultiplier', 0x0601: 'acVoltageDivisor',
                                                      0x0602: 'acCurrentMultiplier', 0x0603: 'acCurrentDivisor',
                                                      0x0604: 'acPowerMultiplier',   0x0605: 'acPowerDivisor' ]


//-- Capabilities -----------------------------------------------------------------------------------------

void configure() {
    logCfg('configure()')
    state.version = CODE_VERSION  // installed()/updated() route here; keep parse() from re-firing configure()

    // Set unused default values
    sendEvent(name: 'coolingSetpoint', value:getTemperature('0BB8')) // 0x0BB8 =  30 Celsius
    sendEvent(name: 'thermostatFanMode', value:'auto') // We dont have a fan, so auto it is
    sendEvent(name: 'supportedThermostatFanModes', value: SUPPORTED_THERMOSTAT_FAN_MODES)
    sendEvent(name: 'supportedThermostatModes',    value: SUPPORTED_THERMOSTAT_MODES)

    unschedule()


    runIn(10, 'refreshClockTime')
    runIn(12, 'refreshElectrical')

    // A G2 holds at most 8 reporting entries (Z2M) and answers INSUFFICIENT_SPACE past that; this list has 7.
    // Voltage and current are not reported: refreshElectrical() reads them every hour.
    List<String> cmds = []
    cmds += zigbee.configureReporting(0x0201, 0x0000, 0x29, 30, 580, (int) (prefMinTempChange ?: 50))   // local temperature
    cmds += zigbee.configureReporting(0x0201, 0x0008, 0x20, 59, 590, (int) (prefMinPIChange ?: 5))      // PI heating demand
    cmds += zigbee.configureReporting(0x0201, 0x0012, 0x29, 15, 302, 40)                                // occupied heating setpoint
    cmds += zigbee.configureReporting(0x0204, 0x0000, 0x30, 1, 0)                                       // temperature display mode (change-of-value only, 1s debounce)
    cmds += zigbee.configureReporting(0x0204, 0x0001, 0x30, 1, 0)                                       // keypad lockout (change-of-value only, 1s debounce)
    cmds += zigbee.configureReporting(0x0702, 0x0000, 0x25, 59, 1799, (int) (prefMinEnergyChange ?: 10)) // Energy reading
    cmds += zigbee.configureReporting(0x0B04, 0x050B, 0x29, 10, 600, 5)                                 // measured active power (W)

    // Electrical scale: configure() can run without refresh() (driver change, code push), and power and voltage wait for it
    cmds += zigbee.readAttribute(0x0B04, constElectricalScaleAttrs.keySet() as List<Integer>)
    cmds += zigbee.readAttribute(0xFF01, 0x0003, [mfgCode: '0x119C'])  // firmware number
    cmds += zigbee.readAttribute(0xFF01, 0x0004, [mfgCode: '0x119C'])  // firmware version

    // Configure displayed scale
    if (getTemperatureScale() == 'C') {
        cmds += zigbee.writeAttribute(0x0204, 0x0000, 0x30, 0)    // Wr °C on thermostat display
    } else {
        cmds += zigbee.writeAttribute(0x0204, 0x0000, 0x30, 1)    // Wr °F on thermostat display
    }

    // Configure display mode
    runIn(1, 'setBacklightMode')

    // Configure secondary display
    runIn(1, 'setSecondTempDisplay')

    //Configure Clock Format
    if (prefTimeFormatParam == '12h AM/PM') { //12h AM/PM "24h"
        logCfg('Set to 12h AM/PM')
        cmds += zigbee.writeAttribute(0xFF01, 0x0114, 0x30, 0x0001, [mfgCode: '0x119C'])
    } else { //24h
        logCfg('Set to 24h')
        cmds += zigbee.writeAttribute(0xFF01, 0x0114, 0x30, 0x0000, [mfgCode: '0x119C'])
    }

    // Configure outdoor temperature display timeout
    if (prefDisplayOutdoorTemp != false) {
        cmds += zigbee.writeAttribute(0xFF01, 0x0011, 0x21, 10800, [mfgCode: '0x119C'])  // 3 hour timeout
    } else {
        // 12s is the Sinopé "disabled" sentinel (below the 3600..18000s active range).
        cmds += zigbee.writeAttribute(0xFF01, 0x0011, 0x21, 12, [mfgCode: '0x119C'])
    }

    if (prefMinHeatingSetpoint) {
        cmds += zigbee.writeAttribute(0x0201, 0x0015, 0x29, setpointLimitCelsius100(prefMinHeatingSetpoint as BigDecimal))
    }
    if (prefMaxHeatingSetpoint) {
        cmds += zigbee.writeAttribute(0x0201, 0x0016, 0x29, setpointLimitCelsius100(prefMaxHeatingSetpoint as BigDecimal))
    }

    if (prefCycleLength) {
        Integer cycleAttr = constThermostatCycles[prefCycleLength] as Integer
        if (cycleAttr != null) {
            cmds += zigbee.writeAttribute(0x0201, 0x0401, 0x21, cycleAttr, [mfgCode: '0x119C'])
        }
    }

    if (debugEnable || traceEnable) {
        runIn(1800, 'logsOff')
    }

    sendZigbeeCommands(cmds) // Submit zigbee commands
}

void refresh() {
    logCmd('refresh()')

    List<String> cmds = []
    cmds += zigbee.readAttribute(0x0201, 0x0000)    // Read Local Temperature
    cmds += zigbee.readAttribute(0x0201, 0x0008)    // Read PI Heating State
    cmds += zigbee.readAttribute(0x0201, 0x0012)    // Read Heat Setpoint
    cmds += zigbee.readAttribute(0x0201, 0x001C)    // Read System Mode
    cmds += zigbee.readAttribute(0x0204, 0x0000)    // Read Temperature Display Mode
    cmds += zigbee.readAttribute(0x0204, 0x0001)    // Read Keypad Lockout
    cmds += zigbee.readAttribute(0x0702, 0x0000)    // Read energy delivered
    if (state.acPowerDivisor == null || state.acVoltageDivisor == null) {
        cmds += zigbee.readAttribute(0x0B04, constElectricalScaleAttrs.keySet() as List<Integer>)
    }
    cmds += zigbee.readAttribute(0x0B04, 0x050D)    // Read highest power delivered
    cmds += zigbee.readAttribute(0x0B04, 0x050B)    // Read thermostat Active power
    cmds += zigbee.readAttribute(0x0B04, 0x0505)    // Read voltage
    cmds += zigbee.readAttribute(0x0B04, 0x0508)    // Read current
    cmds += zigbee.readAttribute(0xFF01, 0x0119, [mfgCode: '0x119C'])    // Read connected load

    sendZigbeeCommands(cmds) // Submit zigbee commands
}

void installed() {
    logCfg('installed()')
    configure()
    refresh()
}

void initialize() {
    // refresh() not configure() — Zigbee reconfigure on every hub startup wastes radio bandwidth
    logCfg('initialize()')
    refresh()
    // One-shot runIn chains: a run that fell due while the hub was down is dropped, ending the chain
    runIn(10, 'refreshClockTime')
    runIn(12, 'refreshElectrical')
}

void updated() {
    logCfg('updated()')

    if (!state.updatedLastRanAt || now() >= state.updatedLastRanAt + 5000) {
        state.updatedLastRanAt = now()
        configure()
        refresh()
    }
}

void uninstalled() {
    logCfg('uninstalled()')
}

void deviceTypeUpdated() {
    logDebug "driver change detected"
    configure()
}


void heat() {
    logCmd('heat(): mode set')

    List<String> cmds = []
    cmds += zigbee.writeAttribute(0x0201, 0x001C, 0x30, 04)
    cmds += zigbee.readAttribute(0x0201, 0x001C)

    sendZigbeeCommands(cmds)
}

void off() {
    logCmd('off(): mode set')

    List<String> cmds = []
    cmds += zigbee.writeAttribute(0x0201, 0x001C, 0x30, 0)
    cmds += zigbee.readAttribute(0x0201, 0x001C)

    sendZigbeeCommands(cmds)
}

void auto() {
    logWarn('auto(): mode is not available for this device. => Defaulting to heat mode instead.')
    heat()
}

void cool() {
    logWarn('cool(): mode is not available for this device. => Defaulting to heat mode instead.')
    heat()
}

void emergencyHeat() {
    logWarn('emergencyHeat(): mode is not available for this device. => Defaulting to heat mode instead.')
    heat()
}

void fanAuto() {
    logWarn('fanAuto(): mode is not available for this device')
}

void fanCirculate() {
    logWarn('fanCirculate(): mode is not available for this device')
}

void fanOn() {
    logWarn('fanOn(): mode is not available for this device')
}

void setCoolingSetpoint(BigDecimal degrees) {
    logWarn("setCoolingSetpoint(${degrees}): is not available for this device")
}

void setHeatingSetpoint(BigDecimal preciseDegrees) {
    if (preciseDegrees != null) {
        unschedule('applyHeatingSetpoint')
        state.setPoint = preciseDegrees
        runInMillis(500, 'applyHeatingSetpoint')
    }
}

private void applyHeatingSetpoint() {
    if (state.setPoint != device.currentValue('heatingSetpoint')) {
        String temperatureScale = getTemperatureScale()
        BigDecimal degrees = state.setPoint as BigDecimal

        logCmd("setHeatingSetpoint(${degrees}:${temperatureScale})")
        state.lastDigitalSetpointAt = now()

        Float celsius = (temperatureScale == 'C') ? degrees.floatValue() : (fahrenheitToCelsius(degrees) as Float).round(2)
        int celsius100 = Math.round(celsius * 100)

        // Chained readAttribute forces a Read Response on no-op writes
        // (same setpoint) — otherwise the command-retry watchdog times out.
        List<String> cmds = []
        cmds += zigbee.writeAttribute(0x0201, 0x0012, 0x29, celsius100)
        cmds += zigbee.readAttribute(0x0201, 0x0012)
        sendZigbeeCommands(cmds)
    }
}

void setThermostatFanMode(String fanmode) {
    logWarn("setThermostatFanMode(${fanmode}): is not available for this device")
}

void setThermostatMode(String value) {
    logCmd("setThermostatMode(${value})")

    switch (value) {
        case 'heat':
        case 'emergency heat':
        case 'auto':
        case 'cool':
            heat()
            break

        case 'off':
            off()
            break
    }
}


// Zigbee message parsing

void parse(String description) {
    if (state.version != CODE_VERSION) {
        logVer("new version: ${CODE_VERSION} (was: ${state.version})")
        state.version = CODE_VERSION
        runInMillis(1, 'configure')
    }

    Map descMap = zigbee.parseDescriptionAsMap(description)
    logTrace("parse() - description = ${descMap}")

    if (descMap.attrId != null) {
        // device attribute report
        parseAttributeReport(descMap)
        descMap.additionalAttrs?.each { add ->
            add.cluster = descMap.cluster
            add.clusterInt = descMap.clusterInt
            parseAttributeReport(add)
        }
    } else if (descMap.profileId == "0000") {
        // ZigBee Device Object (ZDO) command
        logTrace("Unhandled ZDO command: cluster=${descMap.clusterId} command=${descMap.command} value=${descMap.value} data=${descMap.data}")
    } else if (descMap.profileId == "0104" && descMap.clusterId != null) {
        // ZigBee Home Automation (ZHA) global command
        logTrace("Unhandled ZHA global command: cluster=${descMap.clusterId} command=${descMap.command} value=${descMap.value} data=${descMap.data}")
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

    logTrace("Parsing attribute report: cluster ${descMap.cluster} attribute ${descMap.attrId} value ${descMap.value}")

    // Main switch over all available cluster IDs inClusters: '0000,0003,0004,0005,0201,0204,0402,0702,0B04,0B05,FF01'
    //
    switch (descMap.clusterInt) {
        case 0x0000: // Basic cluster
        case 0x0003: // Identify cluster
        case 0x0004: // Groups cluster
        case 0x0005: // Scenes cluster
            break

        case 0x0201: // Thermostat cluster
            switch (descMap.attrInt) {
                case 0x0000:
                    if (isSensorError(descMap.value)) {
                        logWarn("Sensor error on cluster ${descMap.cluster}")
                        break
                    }
                    map.name = 'temperature'
                    map.value = getTemperature(descMap.value)
                    map.unit = getTemperatureScale()
                    map.descriptionText = "Temperature of ${device.displayName} is at ${map.value}${map.unit}"
                    break

                case 0x0008:
                    map.name = 'thermostatOperatingState'
                    map.value = getHeatingDemand(descMap.value)
                    map.descriptionText = "${device.displayName} is at ${map.value}% heating demand"
                    sendEvent(name: 'heatingDemand', value: map.value, unit: '%', descriptionText: "${device.displayName} is heating at ${map.value}% of its capacity")
                    map.value = (map.value < 5) ? 'idle' : 'heating'
                    break

                case 0x0012:
                    if (isSensorError(descMap.value)) {
                        logWarn("Invalid heating setpoint: ${descMap.value}")
                        break
                    }
                    map.name = 'heatingSetpoint'
                    map.value = getTemperature(descMap.value)
                    map.unit = getTemperatureScale()
                    // 5s digital window — chained write+read produces two echoes
                    long sincedigital = now() - ((state.lastDigitalSetpointAt ?: 0L) as long)
                    map.type = (sincedigital < 5000L) ? 'digital' : 'physical'
                    map.descriptionText = "${device.displayName} heating setpoint is ${map.value}${map.unit} [${map.type}]"
                    sendEvent(name: 'thermostatSetpoint', value: map.value, unit: getTemperatureScale()) // For interoperability with SharpTools
                    break

                case 0x001C:
                    String mode = constThermostatModes[descMap.value]
                    if (mode == null) {
                        logWarn("Unknown system mode: ${descMap.value}")
                        break
                    }
                    map.name = 'thermostatMode'
                    map.value = mode
                    map.descriptionText = "${device.displayName} mode is set to ${map.value}"
                    break

                case 0x0401: // thermostat cycle
                    String cycleLabel = constThermostatCycles[Integer.parseInt(descMap.value, 16)]
                    logTrace("Thermostat cycle length is ${cycleLabel}")
                    if (cycleLabel != null) {
                        device.updateSetting('prefCycleLength', [value: cycleLabel, type: 'enum'])
                    }
                    break

                case 0x0402: // display backlight echo — no updateSetting; 'adaptive'/'on' collide on 0x1 for first-gen
                    logTrace("Display backlight echo: ${descMap.value}")
                    break

                default:
                    logTrace("Unhandled thermostat cluster attribute report - cluster ${descMap.cluster} attribute ${descMap.attrId} value ${descMap.value}")
                    break
            }
            break

        case 0x0204: // thermostat user interface config
            switch (descMap.attrInt) {
                case 0x0000: // temperature display mode echo — no event, no setting drift to sync
                    logTrace("Temperature display mode echo: ${descMap.value}")
                    break
                case 0x0001:
                    String keypad = constKeypadLockoutMap[descMap.value]
                    if (keypad == null) {
                        logWarn("Unknown keypad lockout mode: ${descMap.value}")
                        break
                    }
                    map.name = 'keypad'
                    map.value = keypad
                    map.descriptionText = "${device.displayName} is ${map.value}"
                    break
                default:
                    logTrace("Unhandled thermostat UI cluster attribute - cluster ${descMap.cluster} attribute ${descMap.attrId} value ${descMap.value}")
                    break
            }
            break

        case 0x0402: // Temperature measurement cluster — same sensor as 0x0201/0x0000, which carries the event
            logTrace("Temperature measurement echo: ${descMap.value}")
            break

        case 0x0702: // Metering cluster
            if (descMap.attrInt == 0x0000) {
                map.name = 'energy'
                map.value = getEnergy(descMap.value)
                map.unit = "kWh"
                map.descriptionText = "${device.displayName} cumulative energy consumed is ${map.value} ${map.unit}"
            }
            break

        case 0x0B04: // Electrical cluster
            String scaleKey = constElectricalScaleAttrs[descMap.attrInt]
            if (scaleKey != null) {
                int scale = Integer.parseInt(descMap.value, 16)
                logTrace("Electrical ${scaleKey} is ${scale}")
                if (scale > 0) {
                    state[scaleKey] = scale
                }
                break
            }
            switch (descMap.attrInt) {
                case 0x0505:
                    Double volts = getRMSVoltage(descMap.value)
                    if (volts == null) break
                    map.name = 'voltage'
                    map.value = volts
                    map.unit = 'V'
                    map.descriptionText = "Voltage of ${device.displayName} is ${map.value} ${map.unit}"
                    break

                case 0x0508:
                    Double amps = getRMSCurrent(descMap.value)
                    if (amps == null) break
                    map.name = 'amperage'
                    map.value = amps
                    map.unit = 'A'
                    map.descriptionText = "Current of ${device.displayName} is ${map.value} ${map.unit}"
                    break

                case 0x050B:
                    Integer watts = getActivePower(descMap.value)
                    if (watts == null) break
                    map.name = 'power'
                    map.value = watts
                    map.unit = 'W'
                    map.descriptionText = "${device.displayName} is delivering ${map.value}${map.unit}"
                    break

                case 0x050D:
                    Integer maxWatts = getActivePower(descMap.value)
                    if (maxWatts == null) break
                    map.name = 'maxPower'
                    map.value = maxWatts
                    map.unit = 'W'
                    map.descriptionText = "The max heating power of ${device.displayName} is ${map.value}${map.unit}"
                    break

                default:
                    logRx("unhandled electrical measurement attribute report - cluster ${descMap.cluster} attribute ${descMap.attrId} value ${descMap.value}")
                    break
            }
            break

        case 0x0B05: // Diagnostics cluster
            break

        case 0xFF01: // Sinope custom cluster
            switch (descMap.attrInt) {
                case 0x0003: // firmware number (uint16)
                    String fwNumber = Integer.parseInt(descMap.value, 16).toString()
                    logTrace("Firmware number: ${fwNumber}")
                    device.updateDataValue('firmwareNumber', fwNumber)
                    break

                case 0x0004: // firmware version (CharacterString)
                    String fwVersion = decodeZigbeeCharString(descMap.value)
                    logTrace("Firmware version: ${fwVersion} (raw=${descMap.value})")
                    if (fwVersion != null && !fwVersion.isEmpty()) {
                        device.updateDataValue('firmwareVersion', fwVersion)
                    }
                    break

                case 0x0010: // outdoor temperature update
                    logTrace("Outdoor temperature updated to ${descMap.value}")
                    // no event needed
                    break

                case 0x0012: // secondary temperature display update
                    String secondTempMode = constSecondTempDisplayModes[Integer.parseInt(descMap.value, 16)]
                    logTrace("Secondary temp display mode is ${secondTempMode}")
                    if (secondTempMode != null) {
                        device.updateSetting('prefSecondTempDisplay', [value: secondTempMode, type: 'enum'])
                    }
                    // no event needed
                    break

                case 0x0020: // clock time update
                    logTrace("Clock time is ${descMap.value}")
                    break

                case 0x0119: // connected load (uint16, W)
                    map.name = 'connectedLoad'
                    map.value = Integer.parseInt(descMap.value, 16)
                    map.unit = 'W'
                    map.descriptionText = "${device.displayName} connected load is ${map.value}${map.unit}"
                    break

                default:
                    logRx("unhandled custom attribute report - cluster ${descMap.cluster} attribute ${descMap.attrId} value ${descMap.value}")
                    break
            }
            break

        default:
            logRx("Unhandled attribute report - cluster ${descMap.cluster} attribute ${descMap.attrId} value ${descMap.value}")
            break
    }

    if (map) {
        // descriptionText already includes the device label — bypass logInfo() to avoid doubling
        if (map.descriptionText && txtEnable != false) log.info(map.descriptionText)
        sendEvent(map)
    }
}

// Custom commands

void setClockTime() {
    Date thermostatDate = new Date()
    long thermostatTimeSec = thermostatDate.getTime().intdiv(1000)
    int thermostatTimezoneOffsetSec = thermostatDate.getTimezoneOffset() * 60
    int currentTimeToDisplay = Math.round(thermostatTimeSec - thermostatTimezoneOffsetSec - 946684800) //time from 2000-01-01 00:00

    List<String> cmds = []
    cmds += zigbee.writeAttribute(0xFF01, 0x0020, 0x23, currentTimeToDisplay, [mfgCode: '0x119C'])
    sendZigbeeCommands(cmds)
}

void setDynamicRatingMode(String drState) {
    List<String> cmds = []

    switch (drState) {
        case 'on':
            cmds += zigbee.writeAttribute(0xFF01, 0x0071, 0x28, (int) 0, [mfgCode: '0x119C'])
            cmds += zigbee.writeAttribute(0xFF01, 0x0072, 0x20, (int) Integer.parseInt((prefDynamicRatingPI ?: '255') as String), [mfgCode: '0x119C'])
            break
        case 'off':
            cmds += zigbee.writeAttribute(0xFF01, 0x0071, 0x28, (int) -128, [mfgCode: '0x119C'])
            cmds += zigbee.writeAttribute(0xFF01, 0x0072, 0x20, (int) 255, [mfgCode: '0x119C'])
            cmds += zigbee.writeAttribute(0xFF01, 0x0073, 0x20, (int) 255, [mfgCode: '0x119C'])
            break
        default:
            logError("Invalid DR state ${drState}")
            return
    }

    sendZigbeeCommands(cmds)
}

void setKeypadLockoutMode(String lockoutMode) {
    List<String> cmds = []

    switch (lockoutMode) {
        case 'lock':
            cmds += zigbee.writeAttribute(0x0204, 0x0001, 0x30, 0x01)
            break

        case 'unlock':
            cmds += zigbee.writeAttribute(0x0204, 0x0001, 0x30, 0x00)
            break

        default:
            logError("Invalid keypad lockout request ${lockoutMode}")
            return
    }

    sendZigbeeCommands(cmds)
}

void setOutdoorTemperature(BigDecimal outdoorTemperature) {
    if (outdoorTemperature == null) {
        return
    }

    double outdoorTemp = outdoorTemperature.toDouble()
    String tempScale = getTemperatureScale()
    logCmd("Received outdoor weather report : ${outdoorTemp} ${tempScale}")

    sendEvent(name: 'outdoorTemperature', value: outdoorTemp, unit: tempScale, descriptionText: "${device.displayName} outdoor temperature set to ${outdoorTemp}${tempScale}")

    // the value sent to the thermostat must be in C
    if (tempScale == 'F') {
        outdoorTemp = fahrenheitToCelsius(outdoorTemp).toDouble()
    }

    int outdoorTempDevice = (int)(outdoorTemp * 100)  // device expects hundredths
    List<String> cmds = []
    cmds += zigbee.writeAttribute(0xFF01, 0x0010, 0x29, outdoorTempDevice, [mfgCode: '0x119C'])
    sendZigbeeCommands(cmds)
}

void setBacklightMode(String mode = (prefBacklightMode ?: 'adaptive')) {
    Integer backlightModeAttr = (isG2Model() ? constBacklightModesG2[mode] : constBacklightModes[mode]) as Integer

    if (backlightModeAttr == null) {
        logWarn("invalid display mode ${mode}")
        return
    }

    logDebug("setting display backlight to ${mode} (${backlightModeAttr})")

    List<String> cmds = []
    cmds += zigbee.writeAttribute(0x0201, 0x0402, 0x30, backlightModeAttr, [mfgCode: '0x119C'])
    sendZigbeeCommands(cmds)
}


// Private methods

private void refreshClockTime() {
    setClockTime()
    runIn(3*3600, 'refreshClockTime')
}

private void refreshElectrical() {
    List<String> cmds = []
    cmds += zigbee.readAttribute(0x0B04, 0x0505)  // voltage
    cmds += zigbee.readAttribute(0x0B04, 0x0508)  // current
    cmds += zigbee.readAttribute(0x0B04, 0x050D)  // highest power delivered
    sendZigbeeCommands(cmds)
    runIn(3600, 'refreshElectrical')
}

private void setSecondTempDisplay(String mode = (prefSecondTempDisplay ?: 'auto')) {
    Integer secondDisplaySetting = constSecondTempDisplayModes[mode] as Integer

    if (secondDisplaySetting != null) {
        logDebug("setting secondary temperature display to ${mode} (${secondDisplaySetting})")

        List<String> cmds = []
        cmds += zigbee.writeAttribute(0xFF01, 0x0012, 0x30, secondDisplaySetting, [mfgCode: '0x119C'])
        sendZigbeeCommands(cmds)
    } else {
        logWarn("invalid secondary temperature display mode ${mode}")
    }
}

private boolean isG2Model() {
    return device.getDataValue('model')?.contains('-G2') ?: false
}

private int setpointLimitCelsius100(BigDecimal degrees) {
    Integer celsius = (getTemperatureScale() == 'F') ? fahrenheitToCelsius(degrees).toInteger() : degrees.toInteger()
    return Math.min(Math.max(5, celsius), 30) * 100
}

@CompileStatic
private static boolean isSensorError(String value) {
    int raw = hexToSignedInt16(value)
    return raw == 0x7FFD || raw == 0x7FFF || raw == -32768
}

private Double getTemperature(String value) {
    if (value == null) {
        return null
    }
    // Cluster 0x0201 LocalTemperature: signed int16, hundredths of °C
    double celsius = hexToSignedInt16(value) / 100.0d
    if (getTemperatureScale() == 'C') {
        return celsius
    } else {
        return roundToTwoDecimalPlaces(celsiusToFahrenheit(celsius) as Double)
    }
}

@CompileStatic
private static String decodeZigbeeCharString(String raw) {
    if (raw == null || raw.isEmpty()) return null
    if (!raw.matches('[0-9A-Fa-f]+')) return raw
    if (raw.length() < 2) return null
    int length = Integer.parseInt(raw.substring(0, 2), 16)
    if (raw.length() < 2 + length * 2) return null
    StringBuilder sb = new StringBuilder(length)
    for (int i = 0; i < length; i++) {
        int code = Integer.parseInt(raw.substring(2 + i * 2, 4 + i * 2), 16)
        sb.append((char) code)
    }
    return sb.toString()
}

@CompileStatic
private static int hexToSignedInt16(String hex) {
    int v = Integer.parseInt(hex, 16)
    return v > 0x7FFF ? v - 0x10000 : v
}

// Electrical values are raw * multiplier / divisor, both read from the device; null until the divisor is known.
private Double scaleElectrical(String value, String prefix) {
    if (value == null || state."${prefix}Divisor" == null) {
        return null
    }
    int multiplier = (state."${prefix}Multiplier" ?: 1) as int
    return Integer.parseInt(value, 16) * multiplier / ((state."${prefix}Divisor") as Double)
}

private Integer getActivePower(String value) {
    Double watts = scaleElectrical(value, 'acPower')
    return (watts == null) ? null : Math.round(watts) as Integer
}

private Double getRMSVoltage(String value) {
    Double volts = scaleElectrical(value, 'acVoltage')
    return (volts == null) ? null : roundToTwoDecimalPlaces(volts)
}

private Double getRMSCurrent(String value) {
    Double amps = scaleElectrical(value, 'acCurrent')
    return (amps == null) ? null : Math.round(amps * 1000) / 1000.0d
}

@CompileStatic
private Double getEnergy(String value) {
    if (value == null) {
        return null
    }
    BigInteger energyWh = new BigInteger(value, 16)
    BigDecimal kWh = new BigDecimal(energyWh).divide(new BigDecimal(1000), 3, RoundingMode.HALF_UP)
    return kWh.doubleValue()
}

@CompileStatic
private Integer getHeatingDemand(String value) {
    if (value == null) {
        return null
    }
    return Integer.parseInt(value, 16)
}

private void sendZigbeeCommands(List cmds) {
    hubitat.device.HubMultiAction hubAction = new hubitat.device.HubMultiAction(cmds, hubitat.device.Protocol.ZIGBEE)
    sendHubCommand(hubAction)
}

@CompileStatic
private Double roundToTwoDecimalPlaces(Double val) {
    return Math.round(val * 100) / 100.0d
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
void logCmd  (String m) { if (txtEnable != false) log.info  logp('⬆️') + m }
void logCfg  (String m) { if (txtEnable != false) log.info  logp('🔧') + m }
void logVer  (String m) { log.warn  logp('🏷️') + m }

void logWarn (String m) { log.warn  logp('⚠️') + m }
void logError(String m) { log.error logp('🛑') + m }
void logTrace(String m) { if (traceEnable) log.trace logp('🔬') + m }
void logDebug(String m) { if (debugEnable) log.debug "${device.displayName}: ${m}" }

/*
 *  Sinope TH1300ZB Thermostat Device Driver for Hubitat
 *
 *
 *  Code derived from Sinope's SmartThing thermostat for their Zigbee protocol requirements, from the driver of erilaj and from the TH112xZB driver
 *  Source: https://www.sinopetech.com/wp-content/uploads/2019/03/Sinope-Technologies-TH1300ZB-V.1.0.5-SVN-503.txt
 *  Source: https://github.com/sacua/SinopeDriverHubitat/blob/main/drivers/TH112xZB_Sinope_Hubitat.groovy
 *  Source: https://github.com/erilaj/hubitat/blob/main/drivers/Sinope/erilaj-Sinope-TH1300ZB.groovy
 *  Source: https://github.com/sacua/SinopeDriverHubitat/blob/main/drivers/mergeDrivers/TH1300ZB_Sinope_Hubitat.groovy
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

@Field static final String CODE_VERSION = "0.0.11"

@Field static final List<String> SUPPORTED_THERMOSTAT_MODES     = ['"off"', '"heat"']
@Field static final List<String> SUPPORTED_THERMOSTAT_FAN_MODES = ['"auto"']

metadata
{
    definition(
        name: 'Sinope Floor Thermostat (TH13X0ZB)',
        namespace: 'iamtrep',
        author: 'pj',
        description: 'Zigbee thermostat',
        importUrl: "https://raw.githubusercontent.com/iamtrep/hubitat/refs/heads/main/drivers/sinope/Sinope_TH1300ZB.groovy"
    ) {
        capability 'Actuator'
        capability 'Configuration'
        capability 'Initialize'
        capability 'Refresh'
        capability 'PowerMeter'
        capability 'EnergyMeter'
        capability 'TemperatureMeasurement'
        capability 'Thermostat'
        capability 'VoltageMeasurement'

        attribute 'floorTemperature', 'number'
        attribute 'roomTemperature', 'number'
        attribute 'outdoorTemperature', 'number'

        attribute 'keypad', 'enum', ['locked', 'unlocked']

        attribute 'heatingDemand', 'number'
        attribute 'maxPower', 'number'
        attribute 'gfciStatus', 'enum', ['OK', 'error']
        attribute 'floorSensorStatus', 'enum', ['OK', 'error']
        attribute 'tempSensorStatus', 'enum', ['OK', 'error']
        attribute 'floorLimitStatus', 'enum', ['OK', 'floorLimitLowReached', 'floorLimitMaxReached', 'floorAirLimitMaxReached']

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


        fingerprint profileId: '0104', endpointId: '01', inClusters: '0000,0003,0004,0005,0201,0204,0402,0702,0B04,0B05,FF01', outClusters: '000A,0019,FF01', manufacturer: 'Sinope Technologies', model: 'TH1300ZB', deviceJoinName: 'Sinope Thermostat TH1300ZB'
        fingerprint profileId: '0104', endpointId: '01', inClusters: '0000,0003,0004,0005,0201,0204,0402,0702,0B04,0B05,FF01', outClusters: '000A,0019,FF01', manufacturer: 'Sinope Technologies', model: 'TH1320ZB-04', deviceJoinName: 'Sinope Thermostat TH1320ZB-04'
    }

    preferences {
        input name: 'prefBacklightMode', type: 'enum', title: 'Display backlight', options: ['off': 'On Demand', 'adaptive': 'Adaptive (default)', 'on': 'Always On'], defaultValue: 'adaptive', required: true
        input name: 'prefDisplayOutdoorTemp', type: 'bool', title: 'Display outdoor temperature', defaultValue: true
        input name: 'prefSecondTempDisplay', type: 'enum', title: 'Secondary Temp. Display', options:['auto': 'Auto (default)', 'setpoint': 'Setpoint', 'outdoor': 'Outdoor', 'roomTemperature': 'Room'], defaultValue: 'auto', required: true
        input name: 'prefTimeFormatParam', type: 'enum', title: 'Time Format', options:['24h', '12h AM/PM'], defaultValue: '24h', multiple: false, required: true
        input name: 'prefAirFloorModeParam', type: 'enum', title: 'Control mode (Floor or Ambient temperature)', options: ['Ambient', 'Floor'], defaultValue: 'Floor', multiple: false, required: true
        input name: 'prefFloorSensorTypeParam', type: 'enum', title: 'Probe type (Default: 10k)', options: ['10k', '12k'], defaultValue: '10k', multiple: false, required: true
        input name: 'prefMaxAirTemperature', type: 'number', title:'Ambient high limit (5C to 36C / 41F to 97F)', description: 'The maximum ambient temperature limit when in floor control mode.', range: '5..97', required: false
        input name: 'prefMinFloorTemperature', type: 'number', title:'Floor low limit (5C to 36C / 41F to 97F)', description: 'The minimum temperature limit of the floor when in ambient control mode.', range:'5..97', required: false
        input name: 'prefMaxFloorTemperature', type: 'number', title:'Floor high limit (5C to 36C / 41F to 97F)', description: 'The maximum temperature limit of the floor when in ambient control mode.', range:'5..97', required: false
        input name: 'prefMinHeatingSetpoint', type: 'number', title: 'Min heat setpoint (5C to 36C / 41F to 97F)', description: 'Clamps the lowest heat setpoint the device will accept.', range: '5..97', required: false
        input name: 'prefMaxHeatingSetpoint', type: 'number', title: 'Max heat setpoint (5C to 36C / 41F to 97F)', description: 'Clamps the highest heat setpoint the device will accept.', range: '5..97', required: false
        input name: 'prefCycleLength', type: 'enum', title: 'Heating cycle length', options: ['short': 'Short (15s)', 'long': 'Long (15 min)'], required: false
        input name: 'prefTempCalibration', type: 'number', title: 'Local temperature calibration', description: 'Offset applied to the local temperature reading (±2.5 °C / ±4.5 °F).', range: '-5..5', required: false
        input name: 'prefDynamicRatingPI', type: 'enum', title: 'Limit PI heating', description: 'Limit PI heating when DR Icon is on', options:[255: '100 (default)', 75: '75', 50: '50', 25: '25'], defaultValue: '255', required: true

        input name: 'prefMinTempChange', type: 'number', title: 'Temperature change', description: 'Minumum change of temperature reading to trigger report in Celsius/100, 5..50', range: '5..50', defaultValue: 50
        input name: 'prefMinPIChange', type: 'number', title: 'Heating change', description: 'Minimum change in the PI heating in % to trigger power and PI heating reporting, 1..25', range: '1..25', defaultValue: 5
        input name: 'prefMinEnergyChange', type: 'number', title: 'Energy increment', description: 'Minimum increment of the energy meter in Wh to trigger energy reporting, 10..', range: '10..', defaultValue: 10
        input name: 'txtEnable',   type: 'bool', title: 'Enable descriptionText logging', defaultValue: true
        input name: 'debugEnable', type: 'bool', title: 'Enable debug level logging', defaultValue: false
        input name: 'traceEnable', type: 'bool', title: 'Enable trace level logging', description: 'For driver development', defaultValue: false
    }
}

// TH1300ZB/TH1320ZB hardware exposes only On-Demand (0x0) and Always-On (0x1) via
// the Sinopé Simplebacklight enum. 'adaptive' is retained as a sacua-migration alias
// for 'on' so existing configs keep working.
@Field static final Map constBacklightModes = [ 'off': 0x0, 'adaptive': 0x1, 'on': 0x1,
                                                0x0: 'off', 0x1: 'on' ]
@Field static final Map constSecondTempDisplayModes =  [ 0x0 : 'auto', 0x01: 'setpoint', 0x02: 'outdoor', 0x04: 'roomTemperature',
                                                        'auto': 0x0, 'setpoint': 0x1, 'outdoor': 0x2, 'roomTemperature': 0x4 ]
@Field static final Map constThermostatCycles = [ 'short': 0x000F, 'long': 0x0384,
                                                 0x000F: 'short', 0x0384: 'long']

@Field static final Map constThermostatModes = [ '00': 'off', '04': 'heat' ]

@Field static final Map constFloorLimitStatus = [ 'OK': 0, 'floorLimitLowReached': 1, 'floorLimitMaxReached': 2, 'floorAirLimitMaxReached': 3,
                                                 0: 'OK', 1: 'floorLimitLowReached', 2: 'floorLimitMaxReached', 3: 'floorAirLimitMaxReached']

@Field static final Map constKeypadLockoutMap = [ '00': 'unlocked ', '01': 'locked ' ]


//-- Capabilities -----------------------------------------------------------------------------------------

void configure() {
    logInfo('configure()')

    // Set unused default values
    sendEvent(name: 'coolingSetpoint', value:getTemperature('0BB8')) // 0x0BB8 =  30 Celsius
    sendEvent(name: 'thermostatFanMode', value:'auto') // We dont have a fan, so auto it is
    sendEvent(name: 'supportedThermostatFanModes', value: SUPPORTED_THERMOSTAT_FAN_MODES)
    sendEvent(name: 'supportedThermostatModes',    value: SUPPORTED_THERMOSTAT_MODES)

    unschedule()

    state.setTemperatureTypeDigital = false
    state.voltageDivider = 10 as Float
    state.powerDivider = 1 as Integer

    runIn(10, 'refreshClockTime')
    runIn(12, 'refreshMaxPower')

    List<String> cmds = []
    cmds += zigbee.configureReporting(0x0201, 0x0000, 0x29, 30, 580, (int) (prefMinTempChange ?: 50))   // local temperature
    cmds += zigbee.configureReporting(0x0201, 0x0008, 0x20, 59, 590, (int) (prefMinPIChange ?: 5))      // PI heating demand
    cmds += zigbee.configureReporting(0x0201, 0x0012, 0x29, 15, 302, 40)                                // occupied heating setpoint
    cmds += zigbee.configureReporting(0x0204, 0x0000, 0x30, 1, 0)                                       // temperature display mode (change-of-value only, 1s debounce)
    cmds += zigbee.configureReporting(0x0204, 0x0001, 0x30, 1, 0)                                       // keypad lockout (change-of-value only, 1s debounce)
    cmds += zigbee.configureReporting(0x0702, 0x0000, 0x25, 59, 1799, (int) (prefMinEnergyChange ?: 10)) // Energy reading
    cmds += zigbee.configureReporting(0x0B04, 0x0505, 0x29, 30, 600, 1)                                 // Voltage
    cmds += zigbee.configureReporting(0xFF01, 0x0115, 0x30, 10, 3600, 1, [mfgCode: '0x119C'])           // gfci status
    cmds += zigbee.configureReporting(0xFF01, 0x010C, 0x30, 10, 3600, 1, [mfgCode: '0x119C'])           // floor limit status
    cmds += zigbee.configureReporting(0xFF01, 0x0200, 0x1b, 10, 3600, 0x60, [mfgCode: '0x119C'])        // device status bitmap — watch bits 5 (floor) + 6 (temp)

    // Pull firmware identifiers (stored as device data values for visibility in device edit page)
    cmds += zigbee.readAttribute(0xFF01, 0x0003, [mfgCode: '0x119C'])
    cmds += zigbee.readAttribute(0xFF01, 0x0004, [mfgCode: '0x119C'])

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
        logInfo('Set to 12h AM/PM')
        cmds += zigbee.writeAttribute(0xFF01, 0x0114, 0x30, 0x0001, [mfgCode: '0x119C'])
    } else { //24h
        logInfo('Set to 24h')
        cmds += zigbee.writeAttribute(0xFF01, 0x0114, 0x30, 0x0000, [mfgCode: '0x119C'])
    }

    // Configure outdoor temperature display timeout
    if (prefDisplayOutdoorTemp) {
        cmds += zigbee.writeAttribute(0xFF01, 0x0011, 0x21, 10800, [mfgCode: '0x119C'])  // 3 hour timeout
    } else {
        // 12s is the Sinopé "disabled" sentinel (below the 3600..18000s active range).
        cmds += zigbee.writeAttribute(0xFF01, 0x0011, 0x21, 12, [mfgCode: '0x119C'])
    }

    //Set the control heating mode
    if (prefAirFloorModeParam == 'Ambient') { //Air mode
        logInfo('Set to Ambient mode')
        cmds += zigbee.writeAttribute(0xFF01, 0x0105, 0x30, 0x0001, [mfgCode: '0x119C'])
    } else { //Floor mode
        logInfo('Set to Floor mode')
        cmds += zigbee.writeAttribute(0xFF01, 0x0105, 0x30, 0x0002, [mfgCode: '0x119C'])
    }

    //set the type of sensor
    if (prefFloorSensorTypeParam == '12k') { //sensor type = 12k
        logInfo('Sensor type is 12k')
        cmds += zigbee.writeAttribute(0xFF01, 0x010B, 0x30, 0x0001, [mfgCode: '0x119C'])
    } else { //sensor type = 10k
        logInfo('Sensor type is 10k')
        cmds += zigbee.writeAttribute(0xFF01, 0x010B, 0x30, 0x0000, [mfgCode: '0x119C'])
    }

    //Set temperature limit for floor or air
    if (prefMaxAirTemperature) {
        Integer maxAirTemperatureValue
        if (getTemperatureScale() == 'F') {
            maxAirTemperatureValue = fahrenheitToCelsius(prefMaxAirTemperature).toInteger()
        } else { // getTemperatureScale() == 'C'
            maxAirTemperatureValue = prefMaxAirTemperature.toInteger()
        }

        maxAirTemperatureValue = Math.min(Math.max(5, maxAirTemperatureValue), 36) //We make sure that it is within the limit
        maxAirTemperatureValue =  maxAirTemperatureValue * 100
        cmds += zigbee.writeAttribute(0xFF01, 0x0108, 0x29, maxAirTemperatureValue, [mfgCode: '0x119C'])
    }
    else {
        cmds += zigbee.writeAttribute(0xFF01, 0x0108, 0x29, 0x8000, [mfgCode: '0x119C'])
    }

    if (prefMinFloorTemperature) {
        Integer floorLimitMinValue
        if (getTemperatureScale() == 'F') {
            floorLimitMinValue = fahrenheitToCelsius(prefMinFloorTemperature).toInteger()
        } else { // getTemperatureScale() == 'C'
            floorLimitMinValue = prefMinFloorTemperature.toInteger()
        }

        floorLimitMinValue = Math.min(Math.max(5, floorLimitMinValue), 36) //We make sure that it is within the limit
        floorLimitMinValue =  floorLimitMinValue * 100
        cmds += zigbee.writeAttribute(0xFF01, 0x0109, 0x29, floorLimitMinValue, [mfgCode: '0x119C'])
    } else {
        cmds += zigbee.writeAttribute(0xFF01, 0x0109, 0x29, 0x8000, [mfgCode: '0x119C'])
    }

    if (prefMaxFloorTemperature) {
        Integer floorLimitMaxValue
        if (getTemperatureScale() == 'F') {
            floorLimitMaxValue = fahrenheitToCelsius(prefMaxFloorTemperature).toInteger()
        } else { //getTemperatureScale() == 'C'
            floorLimitMaxValue = prefMaxFloorTemperature.toInteger()
        }

        floorLimitMaxValue = Math.min(Math.max(5, floorLimitMaxValue), 36) //We make sure that it is within the limit
        floorLimitMaxValue =  floorLimitMaxValue * 100
        cmds += zigbee.writeAttribute(0xFF01, 0x010A, 0x29, floorLimitMaxValue, [mfgCode: '0x119C'])
    }
    else {
        cmds += zigbee.writeAttribute(0xFF01, 0x010A, 0x29, 0x8000, [mfgCode: '0x119C'])
    }

    // Min/max heat setpoint clamp on the standard ZCL Thermostat cluster.
    if (prefMinHeatingSetpoint) {
        Integer minSetpoint = (getTemperatureScale() == 'F')
            ? fahrenheitToCelsius(prefMinHeatingSetpoint).toInteger()
            : prefMinHeatingSetpoint.toInteger()
        minSetpoint = Math.min(Math.max(5, minSetpoint), 36) * 100
        cmds += zigbee.writeAttribute(0x0201, 0x0015, 0x29, minSetpoint)
    }
    if (prefMaxHeatingSetpoint) {
        Integer maxSetpoint = (getTemperatureScale() == 'F')
            ? fahrenheitToCelsius(prefMaxHeatingSetpoint).toInteger()
            : prefMaxHeatingSetpoint.toInteger()
        maxSetpoint = Math.min(Math.max(5, maxSetpoint), 36) * 100
        cmds += zigbee.writeAttribute(0x0201, 0x0016, 0x29, maxSetpoint)
    }

    // Heating cycle length (short/long)
    if (prefCycleLength) {
        Integer cycleAttr = constThermostatCycles[prefCycleLength] as Integer
        if (cycleAttr != null) {
            cmds += zigbee.writeAttribute(0x0201, 0x0401, 0x21, cycleAttr, [mfgCode: '0x119C'])
        }
    }

    // Local temperature calibration — signed int8, tenths of °C, valid ±25 (±2.5 °C).
    // Calibration is a delta, NOT an absolute temperature, so scale linearly without the °F offset.
    if (prefTempCalibration != null) {
        Double calibCelsius = (getTemperatureScale() == 'F')
            ? (prefTempCalibration as Double) * 5.0d / 9.0d
            : (prefTempCalibration as Double)
        int calibTenths = Math.round(calibCelsius * 10) as int
        calibTenths = Math.min(Math.max(-25, calibTenths), 25)
        cmds += zigbee.writeAttribute(0x0201, 0x0010, 0x28, calibTenths)
    }

    if (debugEnable || traceEnable) {
        runIn(1800, 'logsOff')
    }

    sendZigbeeCommands(cmds) // Submit zigbee commands
}

void refresh() {
    logInfo('refresh()')

    List<String> cmds = []
    cmds += zigbee.readAttribute(0x0201, 0x0000)    // Read Local Temperature
    cmds += zigbee.readAttribute(0x0201, 0x0008)    // Read PI Heating State
    cmds += zigbee.readAttribute(0x0201, 0x0012)    // Read Heat Setpoint
    cmds += zigbee.readAttribute(0x0201, 0x001C)    // Read System Mode
    cmds += zigbee.readAttribute(0x0204, 0x0000)    // Read Temperature Display Mode
    cmds += zigbee.readAttribute(0x0204, 0x0001)    // Read Keypad Lockout
    cmds += zigbee.readAttribute(0x0702, 0x0000)    // Read energy delivered
    cmds += zigbee.readAttribute(0x0B04, 0x050D)    // Read highest power delivered
    cmds += zigbee.readAttribute(0x0B04, 0x050B)    // Read thermostat Active power
    cmds += zigbee.readAttribute(0x0B04, 0x0505)    // Read voltage
    cmds += zigbee.readAttribute(0x0201, 0x0010)    // Read local temperature calibration
    cmds += zigbee.readAttribute(0xFF01, 0x0107, [mfgCode: '0x119C'])    // Read floor temperature
    cmds += zigbee.readAttribute(0xFF01, 0x0200, [mfgCode: '0x119C'])    // Read device status bitmap

    sendZigbeeCommands(cmds) // Submit zigbee commands
}

void installed() {
    logInfo('installed()')
    configure()
    refresh()
}

void initialize() {
    // initialize() runs on hub startup — refresh() only, not configure().
    // Reconfiguring on every restart wastes radio bandwidth.
    logInfo('initialize()')
    refresh()
}

void updated() {
    logInfo('updated()')

    // preferences have changed.
    if (!state.updatedLastRanAt || now() >= state.updatedLastRanAt + 5000) {
        state.updatedLastRanAt = now()
        configure()
        refresh()
    }
}

void uninstalled() {
    logInfo('uninstalled()')
}

void deviceTypeUpdated() {
    logWarn "driver change detected"
    configure()
}


void heat() {
    logInfo('heat(): mode set')

    List<String> cmds = []
    cmds += zigbee.writeAttribute(0x0201, 0x001C, 0x30, 04, [:], 1000)
    cmds += zigbee.readAttribute(0x0201, 0x001C)

    sendZigbeeCommands(cmds)
}

void off() {
    logInfo('off(): mode set')

    List<String> cmds = []
    cmds += zigbee.writeAttribute(0x0201, 0x001C, 0x30, 0)
    //cmds += zigbee.readAttribute(0x0201, 0x0008)
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
        unschedule('setThermostatSetpoint')
        state.setPoint = preciseDegrees
        runInMillis(500, 'setThermostatSetpoint')
    }
}

void setThermostatSetpoint() {
    if (state.setPoint != device.currentValue('heatingSetpoint')) {
        String temperatureScale = getTemperatureScale()
        BigDecimal degrees = state.setPoint as BigDecimal

        logInfo("setHeatingSetpoint(${degrees}:${temperatureScale})")
        state.setTemperatureTypeDigital = true

        Float celsius = (temperatureScale == 'C') ? degrees.floatValue() : (fahrenheitToCelsius(degrees) as Float).round(2)
        int celsius100 = Math.round(celsius * 100)

        // Chain readAttribute(0x0201, 0x0012) so a no-op write (setpoint already
        // at target) still produces a Read Response — without it, the platform's
        // command-retry watchdog gives up after 5 retries. Replaces the previous
        // 30s self-rescheduling runIn, which was a workaround for this same gap.
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
    logInfo("setThermostatMode(${value})")

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
        logWarn("new version: ${CODE_VERSION} (was: ${state.version})")
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
        logDebug "Received enroll request"
    } else if (description?.startsWith('zone status')  || description?.startsWith('zone report')) {
        logDebug "Zone status: $description"
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
                    // Detect sensor-error sentinels on the raw int16, before scaling.
                    // Sinopé reports 0x7FFD on a faulted probe; the ZCL "invalid" sentinel is 0x8000.
                    int raw0000 = hexToSignedInt16(descMap.value)
                    if (raw0000 == 0x7FFD || raw0000 == 0x7FFF || raw0000 == -32768) {
                        logWarn("Sensor error on cluster ${descMap.cluster}")
                        map = [:]
                        break
                    }
                    map.name = 'temperature'
                    map.value = getTemperature(descMap.value)
                    map.unit = getTemperatureScale()
                    map.descriptionText = "Temperature of ${device.displayName} is at ${map.value}${map.unit}"
                    if (prefAirFloorModeParam != null) { // If floor heating device, refresh secondary temperature
                        runIn(1, 'refreshSecondTemp')
                    }
                    break

                case 0x0008:
                    map.name = 'thermostatOperatingState'
                    map.value = getHeatingDemand(descMap.value)
                    map.descriptionText = "${device.displayName} is at ${map.value}% heating demand"
                    if (device.currentValue('maxPower') != null) {
                        Integer maxPowerValue = device.currentValue('maxPower').toInteger()
                        long powerValue = Math.round(maxPowerValue * (map.value as Number).doubleValue() / 100)
                        sendEvent(name: 'power', value: powerValue, unit: 'W', descriptionText: "${device.displayName} is heating at ${powerValue}W")
                    }
                    sendEvent(name: 'heatingDemand', value: map.value, unit: '%', descriptionText: "${device.displayName} is heating at ${map.value}% of its capacity")
                    map.value = (map.value < 5) ? 'idle' : 'heating'
                    break

                case 0x0012:
                    map.name = 'heatingSetpoint'
                    map.value = getTemperature(descMap.value)
                    map.unit = getTemperatureScale()
                    map.type = state.setTemperatureTypeDigital ? 'digital' : 'physical'
                    state.setTemperatureTypeDigital = false
                    map.descriptionText = "${device.displayName} heating setpoint is ${map.value}${map.unit} [${map.type}]"
                    sendEvent(name: 'thermostatSetpoint', value: map.value, unit: getTemperatureScale()) // For interoperability with SharpTools
                    break

                case 0x001C:
                    map.name = 'thermostatMode'
                    map.value = constThermostatModes[descMap.value]
                    map.descriptionText = "${device.displayName} mode is set to ${map.value}"
                    break

                case 0x0010: // local temperature calibration
                    Double offset = getTemperatureOffset(descMap.value)
                    logTrace("Local temperature calibration is ${offset}")
                    if (offset != null) {
                        device.updateSetting('prefTempCalibration', [value: offset, type: 'number'])
                    }
                    break

                case 0x0401: // thermostat cycle
                    String cycleLabel = constThermostatCycles[Integer.parseInt(descMap.value, 16)]
                    logTrace("Thermostat cycle length is ${cycleLabel}")
                    if (cycleLabel != null) {
                        device.updateSetting('prefCycleLength', [value: cycleLabel, type: 'enum'])
                    }
                    break

                case 0x0402: // display backlight
                    String backlightLabel = constBacklightModes[Integer.parseInt(descMap.value, 16)]
                    logTrace("Display backlight set to ${backlightLabel}")
                    if (backlightLabel != null) {
                        device.updateSetting('prefBacklightMode', [value: backlightLabel, type: 'enum'])
                    }
                    // no event needed
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
                    map.name = 'keypad'
                    map.value = constKeypadLockoutMap[descMap.value]
                    map.descriptionText = "${device.displayName} is ${map.value}"
                    break
                default:
                    logTrace("Unhandled thermostat UI cluster attribute - cluster ${descMap.cluster} attribute ${descMap.attrId} value ${descMap.value}")
                    break
            }
            break

        case 0x0402: // Temperature measurement cluster
            if (descMap.attrInt == 0x0000) {
                int raw0402 = hexToSignedInt16(descMap.value)
                if (raw0402 == 0x7FFD || raw0402 == 0x7FFF || raw0402 == -32768) {
                    logWarn("Sensor error on cluster ${descMap.cluster}")
                    map = [:]
                    break
                }
                map.name = 'temperature' // TODO - already sending a temperature event on cluster 0x0201 attr 0x0000
                map.value = getTemperature(descMap.value)
                map.unit = getTemperatureScale()
                map.descriptionText = "${device.displayName} temperature is ${map.value}${map.unit}"
                break
            }
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
            switch (descMap.attrInt) {
                case 0x0505:
                    map.name = 'voltage'
                    map.value = getRMSVoltage(descMap.value)
                    map.unit = 'V'
                    map.descriptionText = "Voltage of ${device.displayName} is ${map.value} ${map.unit}"
                    break

                case 0x0508:
                    map.name = 'amperage'
                    map.value = getRMSCurrent(descMap.value)
                    map.unit = 'A'
                    map.descriptionText = "Current of ${device.displayName} is ${map.value} ${map.unit}"
                    break

                case 0x050B:
                    map.name = 'power'
                    map.value = getActivePower(descMap.value)
                    map.unit = 'W'
                    map.descriptionText = "${device.displayName} is delivering ${map.value}${map.unit}"
                    break

                case 0x050D:
                    map.name = 'maxPower'
                    map.value = getActivePower(descMap.value)
                    map.unit = 'W'
                    map.descriptionText = "The max heating power of ${device.displayName} is ${map.value}${map.unit}"
                    break

                default:
                    logDebug("unhandled electrical measurement attribute report - cluster ${descMap.cluster} attribute ${descMap.attrId} value ${descMap.value}")
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

                case 0x010C: // Floor limit status
                    map.name = 'floorLimitStatus'
                    map.value = constFloorLimitStatus[Integer.parseInt(descMap.value, 16)]
                    map.descriptionText = "${device.displayName} floor limit status is ${map.value}"
                    break

                case 0x010D:
                    map.name = 'roomTemperature'
                    map.value = getTemperature(descMap.value)
                    map.unit = getTemperatureScale()
                    map.descriptionText = "${device.displayName} room temperature is ${map.value}${map.unit}"
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

                case 0x0107:
                    map.name = 'floorTemperature'
                    map.value = getTemperature(descMap.value)
                    map.unit = getTemperatureScale()
                    map.descriptionText = "${device.displayName} floor temperature is ${map.value}${map.unit}"
                    break

                case 0x0115:
                    map.name = 'gfciStatus'
                    map.value = (Integer.parseInt(descMap.value, 16) == 0) ? 'OK' : 'error'
                    map.descriptionText = "${device.displayName} GFCI status is ${map.value}"
                    break

                case 0x0200: // device status bitmap32 — bit 5 = floor sensor, bit 6 = temp sensor (bit set = error)
                    long status = Long.parseLong(descMap.value, 16)
                    String floorState = ((status & 0x20L) != 0L) ? 'error' : 'OK'
                    String tempState  = ((status & 0x40L) != 0L) ? 'error' : 'OK'
                    logTrace("device status bitmap: 0x${descMap.value} (floor=${floorState}, temp=${tempState})")
                    sendEvent(name: 'floorSensorStatus', value: floorState, descriptionText: "${device.displayName} floor sensor is ${floorState}")
                    sendEvent(name: 'tempSensorStatus',  value: tempState,  descriptionText: "${device.displayName} temperature sensor is ${tempState}")
                    break

                default:
                    logDebug("unhandled custom attribute report - cluster ${descMap.cluster} attribute ${descMap.attrId} value ${descMap.value}")
                    break
            }
            break

        default:
            logDebug("Unhandled attribute report - cluster ${descMap.cluster} attribute ${descMap.attrId} value ${descMap.value}")
            break
    }

    if (map) {
        if (map.descriptionText) logInfo("${map.descriptionText}")
        sendEvent(map)
    }
    // Empty `map` is normal for cases that side-effect via sendEvent/updateDataValue/
    // updateSetting (firmware data, device status, calibration, etc.). Inner switch
    // defaults already log genuine fall-throughs at trace level.
}

// Custom commands

void setClockTime() {
    Date thermostatDate = new Date()
    long thermostatTimeSec = thermostatDate.getTime() / 1000
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
    logInfo("Received outdoor weather report : ${outdoorTemp} ${tempScale}")

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

void setBacklightMode(String mode = prefBacklightMode) {
    Integer backlightModeAttr = constBacklightModes[mode] as Integer

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

private void refreshSecondTemp() {
    List<String> cmds = []
    cmds += zigbee.readAttribute(0xFF01, 0x0107, [mfgCode: '0x119C'])  // Read Floor Temperature
    cmds += zigbee.readAttribute(0xFF01, 0x010D, [mfgCode: '0x119C'])  // Read Room Temperature
    sendZigbeeCommands(cmds)
}

private void refreshMaxPower() {
    List<String> cmds = []
    cmds += zigbee.readAttribute(0x0B04, 0x050D)  //Read highest power delivered
    sendZigbeeCommands(cmds)
    runIn(12*3600, 'refreshMaxPower')
}

private void setSecondTempDisplay(String mode = prefSecondTempDisplay) {
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

private void setThermostatCycle(String cycle = prefCycleLength) {
    Integer shortCycleAttr = constThermostatCycles[cycle] as Integer

    if (shortCycleAttr != null) {
        logDebug("setting thermostat cycle to ${cycle} (${shortCycleAttr})")

        List<String> cmds = []
        cmds += zigbee.writeAttribute(0x0201, 0x0401, 0x21, shortCycleAttr, [mfgCode: '0x119C'])
        sendZigbeeCommands(cmds)
    }
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

private Double getTemperatureOffset(String value) {
    if (value == null) {
        return null
    }
    // Cluster 0x0201 LocalTemperatureCalibration: signed int8, tenths of °C.
    // Calibration is a delta, NOT an absolute temperature, so the C↔F conversion
    // is the linear scale factor only — never apply the 32° offset to a delta.
    double celsius = hexToSignedInt8(value) / 10.0d
    return (getTemperatureScale() == 'C') ? celsius : roundToTwoDecimalPlaces(celsius * 9.0d / 5.0d)
}

@CompileStatic
private static String decodeZigbeeCharString(String raw) {
    if (raw == null || raw.isEmpty()) return null
    if (!raw.matches('[0-9A-Fa-f]+')) return raw       // already decoded by the platform
    if (raw.length() < 2) return null
    int length = Integer.parseInt(raw.substring(0, 2), 16)
    if (raw.length() < 2 + length * 2) return raw      // malformed; surface raw for debugging
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

@CompileStatic
private static int hexToSignedInt8(String hex) {
    int v = Integer.parseInt(hex, 16)
    return v > 0x7F ? v - 0x100 : v
}

private Integer getActivePower(String value) {
    if (value == null) {
        return null
    }
    if (state.powerDivider == null) {
        state.powerDivider = 1 as Integer
    }
    return Integer.parseInt(value, 16) / (state.powerDivider as Integer)
}

private Double getRMSVoltage(String attributeReportValue) {
    if (attributeReportValue == null) {
        return null
    }
    if (state.voltageDivider == null) {
        state.voltageDivider = 1 as Integer
    }
    return Integer.parseInt(attributeReportValue, 16) / (state.voltageDivider as Double)
}

@CompileStatic
private Double getRMSCurrent(String attributeReportValue) {
    // attribute report is in mA
    if (attributeReportValue == null) {
        return null
    }
    return Integer.parseInt(attributeReportValue, 16) / 1000.0d
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

// Logging helpers

void logsOff() {
    log.warn "${device} : disabling debug/trace logging"
    if (debugEnable) device.updateSetting('debugEnable', [value: false, type: 'bool'])
    if (traceEnable) device.updateSetting('traceEnable', [value: false, type: 'bool'])
}

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

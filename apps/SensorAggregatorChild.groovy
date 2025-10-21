/*

 MIT License

 Copyright (c) 2025 pj

 Permission is hereby granted, free of charge, to any person obtaining a copy
 of this software and associated documentation files (the "Software"), to deal
 in the Software without restriction, including without limitation the rights
 to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 copies of the Software, and to permit persons to whom the Software is
 furnished to do so, subject to the following conditions:

 The above copyright notice and this permission notice shall be included in all
 copies or substantial portions of the Software.

 THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 SOFTWARE.

 Sensor Aggregator Child

 An app that allows aggregating sensor values and saving the result to a virtual device

 TODO:
 - More device types
 - Figure out Generic Component devices for when child devices are selected

*/


definition(
    name: "Sensor Aggregator Child",
    namespace: "iamtrep",
    parent: "iamtrep:Sensor Aggregator",
    author: "pj",
    description: "Aggregate sensor values and save to a single virtual device",
    category: "Convenience",
    iconUrl: "",
    iconX2Url: "",
    importUrl: "https://raw.githubusercontent.com/iamtrep/hubitat/refs/heads/main/apps/SensorAggregatorChild.groovy"
)

import groovy.transform.Field
import groovy.transform.CompileStatic
import com.hubitat.app.DeviceWrapper
import com.hubitat.app.ChildDeviceWrapper
import com.hubitat.hub.domain.Event

@Field static final String child_app_version = "0.3.0"

@Field static final Map<String, String> CAPABILITY_ATTRIBUTES = [
    "capability.carbonDioxideMeasurement"   : "carbonDioxide",
    "capability.illuminanceMeasurement"     : "illuminance",
    "capability.relativeHumidityMeasurement": "humidity",
    "capability.temperatureMeasurement"     : "temperature"
]

@Field static final Map<String, String> CAPABILITY_DRIVERS = [
    "capability.carbonDioxideMeasurement"   : "Virtual Omni Sensor",
    "capability.illuminanceMeasurement"     : "Virtual Illuminance Sensor",
    "capability.relativeHumidityMeasurement": "Virtual Humidity Sensor",
    "capability.temperatureMeasurement"     : "Virtual Temperature Sensor"
]


preferences {
	page(name: "mainPage")
}

Map mainPage() {
	dynamicPage(name: "mainPage", title: " ", install: true, uninstall: true) {
        section("Configuration") {
            input "appName", "text", title: "Name this sensor aggregator app", submitOnChange: true
		    if(appName) app.updateLabel("$appName")
        }
        section("Sensors") {
            input name: "selectedSensorCapability", type: "enum", options: CAPABILITY_ATTRIBUTES.keySet(), title: "Select sensor capability to aggregate", required: true, submitOnChange:true
            if (selectedSensorCapability) {
                input name: "inputSensors", type: selectedSensorCapability, title: "Sensors to aggregate", multiple:true, required: true, showFilter: true, submitOnChange: true
                input name: "outputSensor", type: selectedSensorCapability, title: "Virtual sensor to set as aggregation output", multiple: false, required: false, submitOnChange: true
                if (!outputSensor) {
                    input "createChildSensorDevice", "bool", title: "Create Child Device if no Output Device selected", defaultValue: state.createChild, required: true, submitOnChange: true
                    state.createChild = createChildSensorDevice
                }
            }
        }
        section("Aggregation") {
            input name: "aggregationMethod", type: "enum", options: ["average", "median", "min", "max"], title: "Select aggregation method", defaultValue: "average", required: true, submitOnChange: true
            input name: "excludeAfter", type: "number", title: "Exclude sensor when inactive for this many minutes:", defaultValue: 60, range: "0..1440", submitOnchange: true
        }
        section("Operation") {
            input name: "forceUpdate", type: "button", title: "Force update aggregate value"
            if(inputSensors && state.aggregateValue != null) {
                def possibleValues = CAPABILITY_ATTRIBUTES[selectedSensorCapability]?.values
                paragraph "Current aggregate value: <b>${state.aggregateValue}</b>"
                paragraph "Included sensors: ${state.includedSensors?.size() ?: 0} of ${inputSensors.size()}"
                if (state.excludedSensors?.size() > 0) {
                    // Build links to excluded sensor device pages
                    List<String> excludedLinks = []
                    inputSensors.each { sensor ->
                        if (state.excludedSensors?.contains(sensor.getLabel())) {
                            excludedLinks << "<a href='/device/edit/${sensor.id}' target='_blank'>${sensor.getLabel()}</a>"
                        }
                    }
                    paragraph "<span style='color:orange'><b>Excluded sensors:</b> ${excludedLinks.join(', ')}</span>"
                }
            }
            input name: "logLevel", type: "enum", options: ["warn","info","debug","trace"], title: "Enable logging?", defaultValue: "info", required: true, submitOnChange: true
            log.info("${logLevel} logging enabled")
        }
    }
}

void installed() {
    logDebug "installed()"
}

void updated() {
    logDebug "updated()"
    unsubscribe()

    if (state.includedSensors == null) { state.includedSensors = [] }
    if (state.excludedSensors == null) { state.excludedSensors = [] }

    if (state.aggregateValue == null) { state.aggregateValue = 0 }
    if (state.avgSensorValue == null) { state.avgSensorValue = 0 }
    if (state.minSensorValue == null) { state.minSensorValue = 0 }
    if (state.maxSensorValue == null) { state.maxSensorValue = 0 }
    if (state.medianSensorValue == null) { state.medianSensorValue = 0 }
    if (state.createChild == null) { state.createChild = false }

    if (!outputSensor && state.createChild) {
        fetchChildDevice()
    }

    if (inputSensors && selectedSensorCapability) {
        String attributeName = CAPABILITY_ATTRIBUTES[selectedSensorCapability]
        if (attributeName) {
            subscribe(inputSensors, attributeName, sensorEventHandler)
            logTrace "Subscribed to ${attributeName} events for ${inputSensors.collect { it.displayName}}."
        }
    }

    sensorEventHandler()
}

void initialize() {
    logDebug "initialize()"
}

void uninstalled() {
    logDebug "uninstalled()"
}

void appButtonHandler(String buttonName) {
    switch (buttonName) {
        case "forceUpdate":
        default:
            //sensorEventHandler()
            updated()
            break
    }
}

void sensorEventHandler(Event evt=null) {
    if (evt != null) logTrace "sensorEventHandler() called: ${evt?.name} ${evt?.getDevice().getLabel()} ${evt?.value} ${evt?.descriptionText}"

	if (computeAggregateSensorValue()) {
        DeviceWrapper sensorDevice = outputSensor
        if (!sensorDevice) {
            sensorDevice = fetchChildDevice()
        }
        if (!sensorDevice) {
            logError("No output device to update")
            return
        }
        sensorDevice.sendEvent(name: CAPABILITY_ATTRIBUTES[selectedSensorCapability],
                               value: state.aggregateValue,
                               unit: getAttributeUnits(selectedSensorCapability),
                               descriptionText:"${sensorDevice.displayName} was set to ${state.aggregateValue}${getAttributeUnits(selectedSensorCapability)}"
                               /* , isStateChange: true // let platform filter this event as needed */)
    }
}

private String getAttributeUnits(String capability) {
    switch ( capability) {
        case "capability.carbonDioxideMeasurement":
        	return "ppm"
    case "capability.illuminanceMeasurement":
        return "lux"
    case "capability.relativeHumidityMeasurement":
        return "%"
        case "capability.temperatureMeasurement":
        return getTemperatureScale()
        default:
            break
    }
    return null
}

private ChildDeviceWrapper fetchChildDevice() {
    String driverName = CAPABILITY_DRIVERS[selectedSensorCapability]
    if (!driverName) {
        logError "No driver found for capability: ${selectedSensorCapability}"
        return null
    }
    String deviceName = "${app.id}-${driverName}"
    ChildDeviceWrapper cd = getChildDevice(deviceName)
    if (!cd) {
        cd = addChildDevice("hubitat", driverName, deviceName, [name: "${app.label} ${driverName}"])
        if (cd) logDebug("Child device ${cd.id} created with driver: ${driverName}.") else logError("could not create child device")
        app.updateSetting("outputSensor", [type: selectedSensorCapability, value: cd.id])
    }
    return cd
}

private boolean computeAggregateSensorValue() {
    def now = new Date()
    def timeAgo = new Date(now.time - excludeAfter * 60 * 1000)
    String attributeName = CAPABILITY_ATTRIBUTES[selectedSensorCapability]

    List includedSensors = []
    List excludedSensors = []

    inputSensors.each {
        Date lastActivity = it.getLastActivity()
        if (lastActivity > timeAgo) {
            if (it.currentValue(attributeName) != null) {
                includedSensors << it
                logTrace("Including sensor ${it.getLabel()} (${it.currentValue(attributeName)}) - last activity ${lastActivity}")
            }
        } else {
            excludedSensors << it
            logTrace("Excluding sensor ${it.getLabel()} (${it.currentValue(attributeName)}) - no activity since $timeAgo (last active ${lastActivity})")
        }
    }

    Integer n = includedSensors.size()

    if (n<1) {
        // For now, simply don't update the app state
        logError "No sensors available for agregation... aggregate value not updated (${state.aggregateValue})"
        return false
    }

    List sensorValues = includedSensors.collect { it.currentValue(attributeName) }
    state.minSensorValue = roundToDecimalPlaces(sensorValues.min())
    state.maxSensorValue = roundToDecimalPlaces(sensorValues.max())

    def sum = sensorValues.sum()
    state.avgSensorValue = roundToDecimalPlaces(sum / sensorValues.size(),1)

    def variance = sensorValues.collect { (it - state.avgSensorValue) ** 2 }.sum() / n
    state.standardDeviation = roundToDecimalPlaces(Math.sqrt(variance),1)

    sensorValues.sort()
    logDebug "sorted values: $sensorValues"
    if (n % 2 == 0) {
        // Even number of elements, average the two middle values
        state.medianSensorValue = (sensorValues[(n / 2 - 1) as int] + sensorValues[(n / 2) as int]) / 2.0
    } else {
        // Odd number of elements, take the middle value
        state.medianSensorValue = sensorValues[(n / 2) as int]
    }

    //aggregationMethod", type: "enum", options: ["average", "median", "min", "max"
    switch (aggregationMethod) {
        case "min":
            state.aggregateValue = state.minSensorValue
            break

        case "max":
            state.aggregateValue = state.maxSensorValue
            break

        case "median":
            state.aggregateValue = state.medianSensorValue
            break

        case "average":
        default:
            state.aggregateValue = state.avgSensorValue
            break
    }

    state.includedSensors = includedSensors.collect { it.getLabel() }
    state.excludedSensors = excludedSensors.collect { it.getLabel() }

    logStatistics()
    return true
}

private void logStatistics() {
    logInfo("${CAPABILITY_ATTRIBUTES[selectedSensorCapability]} ${aggregationMethod} (${state.includedSensors.size()}/${inputSensors.size()}): ${state.aggregateValue} ${getAttributeUnits(selectedSensorCapability)}")
    logInfo("Avg: ${state.avgSensorValue} Stdev: ${state.standardDeviation} Min: ${state.minSensorValue} Max: ${state.maxSensorValue} Median: ${state.medianSensorValue}")
    if (state.includedSensors.size() > 0) {
        logDebug("Aggregated sensors (${state.includedSensors})")
    } else {
        logDebug("No aggregated sensors!")
    }
    if (state.excludedSensors.size() > 0) logDebug("Rejected sensors with last update older than $excludeAfter minutes: ${state.excludedSensors} }")
}


@CompileStatic
private double roundToDecimalPlaces(double decimalNumber, int decimalPlaces = 2) {
    double scale = Math.pow(10, decimalPlaces)
    return (Math.round(decimalNumber * scale) as double) / scale
}

// logging helpers

private void logError(String msg)
{
    //if (logLevel in ["info","debug","trace"])
    log.error(app.getLabel() + ': ' + msg)
}

private void logWarn(String msg)
{
    //if (logLevel in ["warn", "info","debug","trace"])
    log.warn(app.getLabel() + ': ' + msg)
}

private void logInfo(String msg)
{
    if (logLevel == null || logLevel in ["info","debug","trace"]) log.info(app.getLabel() + ': ' + msg)
}

private void logDebug(String msg)
{
    if (logLevel == null || logLevel in ["debug","trace"]) log.debug(app.getLabel() + ': ' + msg)
}

private void logTrace(String msg)
{
    if (logLevel == null || logLevel in ["trace"]) log.trace(app.getLabel() + ': ' + msg)
}

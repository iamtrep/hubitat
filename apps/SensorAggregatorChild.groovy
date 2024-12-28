/*

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
    importUrl: ""
)

import groovy.transform.Field
import groovy.transform.CompileStatic

@Field static final String child_app_version = "0.0.5"

@Field static final Map<String, String> CAPABILITY_ATTRIBUTES = [
    "capability.carbonDioxideMeasurement"   : "carbonDioxide",
    "capability.illuminanceMeasurement"     : "illuminance",
    "capability.relativeHumidityMeasurement": "humidity",
    "capability.temperatureMeasurement"     : "temperature"
]

@Field static final Map<String, String> CAPABILITY_ATTRIBUTE_UNITS = [
    "capability.carbonDioxideMeasurement"   : "ppm",
    "capability.illuminanceMeasurement"     : "lux",
    "capability.relativeHumidityMeasurement": "%",
    "capability.temperatureMeasurement"     : null,
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

def mainPage() {
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
            input name: "excludeAfter", type: "number", title: "Exclude sensor value when sensor is inactive for this many minutes:", defaultValue: 60, range: "0..3600"
        }
        section("Operation") {
            input name: "forceUpdate", type: "button", title: "Force update aggregate value"
            if(inputSensors) {
                paragraph "Current $aggregationMethod value is ${state.aggregateValue} ${CAPABILITY_ATTRIBUTE_UNITS[selectedSensorCapability]}"
            }
            input name: "logLevel", type: "enum", options: ["warn","info","debug","trace"], title: "Enable logging?", defaultValue: "info", required: true, submitOnChange: true
            log.info("${logLevel} logging enabled")
        }
    }
}

def installed() {
    logDebug "installed()"
}

def updated() {
    logDebug "updated()"
    unsubscribe()
    initialize()
}

def initialize() {
    if (state.includedSensors == null) { state.includedSensors = [] }
    if (state.excludedSensors == null) { state.excludedSensors = [] }

    if (state.aggregateValue == null) { state.aggregateValue = 0 }
    if (state.avgSensorValue == null) { state.avgSensorValue = 0 }
    if (state.minSensorValue == null) { state.minSensorValue = 0 }
    if (state.maxSensorValue == null) { state.maxSensorValue = 0 }
    if (state.medianSensorValue == null) { state.medianSensorValue = 0 }
    if (state.createChild == null) { state.createChild = false }

    if (CAPABILITY_ATTRIBUTE_UNITS["capability.temperatureMeasurement"] == null) CAPABILITY_ATTRIBUTE_UNITS["capability.temperatureMeasurement"] = getTemperatureScale()

    if (!outputSensor && state.createChild) {
        fetchChildDevice()
    }

    if (inputSensors && selectedSensorCapability) {
        def attributeName = CAPABILITY_ATTRIBUTES[selectedSensorCapability]
        if (attributeName) {
            subscribe(inputSensors, attributeName, sensorEventHandler)
            logTrace "Subscribed to ${attributeName} events for ${inputSensors.collect { it.displayName}}."
        }
    }

    sensorEventHandler()
}

def uninstalled() {
}


def fetchChildDevice() {
    def driverName = CAPABILITY_DRIVERS[selectedSensorCapability]
    if (!driverName) {
        logError "No driver found for capability: ${selectedSensorCapability}"
        return null
    }
    String deviceName = "${app.id}-${driverName}"
    def cd = getChildDevice(deviceName)
    if (!cd) {
        cd = addChildDevice("hubitat", driverName, deviceName, [name: "${app.label} ${driverName}"])
        if (cd) logDebug("Child device ${cd.id} created with driver: ${driverName}.") else logError("could not create child device")
        app.updateSetting("outputSensor", [type: selectedSensorCapability, value: cd.id])
    }
    return cd
}

def sensorEventHandler(evt=null) {
    if (evt != null) logTrace "sensorEventHandler() called: ${evt?.name} ${evt?.getDevice().getLabel()} ${evt?.value} ${evt?.descriptionText}"

	if (computeAggregateSensorValue()) {
        def sensorDevice = outputSensor
        if (!sensorDevice) {
            sensorDevice = fetchChildDevice()
        }
        if (!sensorDevice) {
            logError("No output device to update")
            return
        }
        sensorDevice.sendEvent(name: CAPABILITY_ATTRIBUTES[selectedSensorCapability],
                               value: state.aggregateValue,
                               unit: CAPABILITY_ATTRIBUTE_UNITS[selectedSensorCapability],
                               descriptionText:"${sensorDevice.displayName} was set to ${state.aggregateValue}${CAPABILITY_ATTRIBUTE_UNITS[selectedSensorCapability]}"
                               /* , isStateChange: true // let platform filter this event as needed */)
    }
}


def computeAggregateSensorValue() {
    state.includedSensors.clear()
    state.excludedSensors.clear()

    def now = new Date()
    def timeAgo = new Date(now.time - excludeAfter * 60 * 1000)
    def attributeName = CAPABILITY_ATTRIBUTES[selectedSensorCapability]

    inputSensors.each {
        def events = it.eventsSince(timeAgo, [max:1])
        if (events.size() > 0) {
            if (it.currentValue(attributeName) != null) {
                state.includedSensors << it
                logTrace("Including sensor ${it.getLabel()} (${it.currentValue(attributeName)}) - last event ${events[0].date}")
            }
        } else {
            state.excludedSensors << it
            logTrace("Excluding sensor ${it.getLabel()} (${it.currentValue(attributeName)}) - last event ${it.events([max:1])[0].date}")
        }
    }

    def n = state.includedSensors.size()
    if (n<1) {
        // For now, simply don't update the app state
        logError "No sensors available for agregation... aggregate value not updated (${state.aggregateValue})"
        return false
    }

    def sensorValues = state.includedSensors.collect { it.currentValue(attributeName) }
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

    logStatistics()
    return true
}

def appButtonHandler(String buttonName) {
    switch (buttonName) {
        case "forceUpdate":
        default:
            //sensorEventHandler()
            updated()
            break
    }
}

def logStatistics() {
    logInfo("${CAPABILITY_ATTRIBUTES[selectedSensorCapability]} ${aggregationMethod} (${state.includedSensors.size()}/${inputSensors.size()}): ${state.aggregateValue} ${CAPABILITY_ATTRIBUTE_UNITS[selectedSensorCapability]}")
    logInfo("Avg: ${state.avgSensorValue} Stdev: ${state.standardDeviation} Min: ${state.minSensorValue} Max: ${state.maxSensorValue} Median: ${state.medianSensorValue}")
    if (state.includedSensors.size() > 0) {
        logDebug("Aggregated sensors (${state.includedSensors})")
    } else {
        logDebug("No aggregated sensors!")
    }
    if (state.excludedSensors.size() > 0) logDebug("Rejected sensors with last update older than $excludeAfter minutes: ${state.excludedSensors.collect { it.getLabel()} }")
}


@CompileStatic
private double roundToDecimalPlaces(double decimalNumber, int decimalPlaces = 2) {
    double scale = Math.pow(10, decimalPlaces)
    return (Math.round(decimalNumber * scale) as double) / scale
}

private void logError(Object... args)
{
    //if (logLevel in ["info","debug","trace"])
    log.error(args)
}

private void logWarn(Object... args)
{
    //if (logLevel in ["warn", "info","debug","trace"])
    log.warn(args)
}

private void logInfo(Object... args)
{
    if (logLevel in ["info","debug","trace"]) log.info(args)
}

private void logDebug(Object... args)
{
    if (logLevel in ["debug","trace"]) log.debug(args)
}

private void logTrace(Object... args)
{
    if (logLevel in ["trace"]) log.trace(args)
}

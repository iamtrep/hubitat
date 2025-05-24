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
    name: "Sensor Aggregator Discrete Child",
    namespace: "iamtrep",
    parent: "iamtrep:Sensor Aggregator",
    author: "pj",
    description: "Aggregate discrete sensor values (contact, motion, tilt, etc) and save to a single virtual device",
    category: "Convenience",
    iconUrl: "",
    iconX2Url: "",
    importUrl: ""
)

import groovy.transform.Field
import groovy.transform.CompileStatic
import com.hubitat.app.DeviceWrapper
import com.hubitat.app.ChildDeviceWrapper
import com.hubitat.hub.domain.Event

@Field static final String child_app_version = "0.0.1"

@Field static final Map<String, String> CAPABILITY_ATTRIBUTES = [
    "capability.accelerationSensor"  : [ attribute: "acceleration", values: ["inactive", "active"], driver: "Virtual Acceleration Sensor" ],
    "capability.contactSensor"       : [ attribute: "contact", values: ["open","closed"], driver: "Virtual Contact Sensor" ],
    "capability.motionSensor"        : [ attribute: "motion", values: ["inactive","active"], driver: "Virtual Motion Sensor" ],
    "capability.presenceSensor"      : [ attribute: "presence", values: ["not present","present"], driver: "Virtual Presence Sensor" ],
    "capability.shockSensor"         : [ attribute: "shock", values: ["clear", "detected"], driver: "Virtual Shock Sensor" ],
    "capability.waterSensor"         : [ attribute: "water", values: ["wet", "dry"], driver: "Virtual Moisture Sensor" ]
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
                if (inputSensors) {
                    def attributePOssibleValues = getAttributePossibleValues(inputSensors[0], selectedSensorCapability)
                    input name: "attributeValue", type: "enum", options: attributePOssibleValues, title: "Attribute value", multiple:false, required:true
                }
                input name: "outputSensor", type: selectedSensorCapability, title: "Virtual sensor to set as aggregation output", multiple: false, required: false, submitOnChange: true
                if (!outputSensor) {
                    input "createChildSensorDevice", "bool", title: "Create Child Device if no Output Device selected", defaultValue: state.createChild, required: true, submitOnChange: true
                    state.createChild = createChildSensorDevice
                }
            }
        }
        section("Aggregation") {
            input name: "aggregationMethod", type: "enum", options: ["any", "all"], title: "Select aggregation method", defaultValue: "any", required: true, submitOnChange: true
            input name: "excludeAfter", type: "number", title: "Exclude sensor value when sensor has no updates for this many minutes:", defaultValue: 60, range: "0..1440"
        }
        section("Operation") {
            input name: "forceUpdate", type: "button", title: "Force update aggregate value"
            if(inputSensors) {
                paragraph "Current $aggregationMethod value is ${state.aggregateValue}"  // TODO units ?!
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
    initialize()
}

void uninstalled() {
    logDebug "uninstalled()"
}

void initialize() {
    if (state.includedSensors == null) { state.includedSensors = [] }
    if (state.excludedSensors == null) { state.excludedSensors = [] }

    if (state.anyValue == null) { state.anyValue = "" }
    if (state.allValue == null) { state.allValue = "" }

    if (state.createChild == null) { state.createChild = false }

    if (!outputSensor && state.createChild) {
        fetchChildDevice()
    }

    if (inputSensors && selectedSensorCapability) {
        String attributeName = CAPABILITY_ATTRIBUTES[selectedSensorCapability]?.attribute
        if (attributeName) {
            subscribe(inputSensors, attributeName, sensorEventHandler)
            logTrace "Subscribed to ${attributeName} events for ${inputSensors.collect { it.displayName}}."
        }
    }

    sensorEventHandler()
}

ChildDeviceWrapper fetchChildDevice() {
    String driverName = CAPABILITY_ATTRIBUTES[selectedSensorCapability]?.driver
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
        sensorDevice.sendEvent(name: CAPABILITY_ATTRIBUTES[selectedSensorCapability].attribute,
                               value: state.aggregateValue,
                               descriptionText:"${sensorDevice.displayName} was set to ${state.aggregateValue}"
                               /* , isStateChange: true // let platform filter this event as needed */)
    }
}


boolean computeAggregateSensorValue() {
    Date now = new Date()
    Date timeAgo = new Date(now.time - excludeAfter * 60 * 1000)
    String attributeName = CAPABILITY_ATTRIBUTES[selectedSensorCapability]?.attribute

    List<DeviceWrapper> includedSensors = []
    List<DeviceWrapper> excludedSensors = []

    inputSensors.each {
        List<Event> events = it.eventsSince(timeAgo, [max:1])
        if (events.size() > 0) {
            if (it.currentValue(attributeName) != null) {
                includedSensors << it
                logTrace("Including sensor ${it.getLabel()} (${it.currentValue(attributeName)}) - last event ${events[0].date}")
            }
        } else {
            excludedSensors << it
            logTrace("Excluding sensor ${it.getLabel()} (${it.currentValue(attributeName)}) - last event ${it.events([max:1])[0].date}")
        }
    }

    int n = includedSensors.size()
    if (n<1) {
        // For now, simply don't update the app state
        logError "No sensors available for agregation... aggregate value not updated (${state.aggregateValue})"
        return false
    }

    def sensorValues = includedSensors.collect { it.currentValue(attributeName) }
    def targetValue = attributeValue

    //aggregationMethod", type: "enum", options: ["any", "all"]
    switch (aggregationMethod) {
        case "all":
            state.aggregateValue = sensorValues.every { it == targetValue } ? targetValue : ""
            break

        case "any":
        default:
            state.aggregateValue = sensorValues.any { it == targetValue } ? targetValue : ""
            break
    }

    state.includedSensors = includedSensors.collect { it.getLabel() }
    state.excludedSensors = excludedSensors.collect { it.getLabel() }

    logStatistics()
    return true
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

void logStatistics() {
    logInfo("${CAPABILITY_ATTRIBUTES[selectedSensorCapability].attribute} ${aggregationMethod} (${state.includedSensors.size()}/${inputSensors.size()}): ${state.aggregateValue}")

    if (state.includedSensors.size() > 0) {
        logDebug("Aggregated sensors (${state.includedSensors})")
    } else {
        logDebug("No aggregated sensors!")
    }
    if (state.excludedSensors.size() > 0) logDebug("Rejected sensors with last update older than $excludeAfter minutes: ${state.excludedSensors} }")
}

private List<String> getAttributePossibleValues(deviceWrapper, capability) {
    List capabilities = deviceWrapper.getCapabilities()
    def targetCapability = capabilities.find { it.name.toLowerCase() == capability.substring("capability.".length()).toLowerCase() }
    def targetAttribute = deviceWrapper.getSupportedAttributes().find { it.name == targetCapability.attributes[0].name }
    //logObjectProperties(targetCapability)
    //logObjectProperties(targetAttribute)
    return targetAttribute.getValues()
}

private void logObjectProperties(obj) {
    if (obj == null) return
    log.debug "${getObjectClassName(obj)} BEGIN"
    obj.properties.each { property, value ->
        log.debug "${property}=${value}"
    }
    log.debug "${getObjectClassName(obj)} END"
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

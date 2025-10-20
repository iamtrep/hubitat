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
    importUrl: "https://raw.githubusercontent.com/iamtrep/hubitat/refs/heads/main/apps/SensorAggregatorDiscreteChild.groovy"
)

import groovy.transform.Field
import groovy.transform.CompileStatic
import com.hubitat.app.DeviceWrapper
import com.hubitat.app.ChildDeviceWrapper
import com.hubitat.hub.domain.Event

@Field static final String child_app_version = "0.1.0"

@Field static final Map<String, String> CAPABILITY_ATTRIBUTES = [
    "capability.accelerationSensor"  : [ attribute: "acceleration", values: ["inactive", "active"], driver: "Virtual Acceleration Sensor" ],
    "capability.contactSensor"       : [ attribute: "contact", values: ["closed", "open"], driver: "Virtual Contact Sensor" ],
    "capability.motionSensor"        : [ attribute: "motion", values: ["inactive","active"], driver: "Virtual Motion Sensor" ],
    "capability.presenceSensor"      : [ attribute: "presence", values: ["not present","present"], driver: "Virtual Presence Sensor" ],
    "capability.shockSensor"         : [ attribute: "shock", values: ["clear", "detected"], driver: "Virtual Shock Sensor" ],
    "capability.waterSensor"         : [ attribute: "water", values: ["dry", "wet"], driver: "Virtual Moisture Sensor" ]
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
                    def attributePossibleValues = getAttributePossibleValues(inputSensors[0], selectedSensorCapability)
                    input name: "attributeValue", type: "enum", options: attributePossibleValues, title: "Attribute value to monitor", multiple:false, required:true
                }
                input name: "outputSensor", type: selectedSensorCapability, title: "Virtual sensor to set as aggregation output", multiple: false, required: false, submitOnChange: true
                if (!outputSensor) {
                    input "createChildSensorDevice", "bool", title: "Create Child Device if no Output Device selected", defaultValue: state.createChild, required: true, submitOnChange: true
                    state.createChild = createChildSensorDevice
                }
            }
        }
        section("Aggregation") {
            input name: "aggregationMethod", type: "enum", options: ["any", "all", "majority", "threshold"], title: "Select aggregation method", defaultValue: "any", required: true, submitOnChange: true
            if (aggregationMethod == "threshold") {
                input name: "thresholdPercent", type: "number", title: "Threshold percentage (1-100)", defaultValue: 50, range: "1..100", required: true
            }
            input name: "excludeAfter", type: "number", title: "Exclude sensor value when sensor has no updates for this many minutes:", defaultValue: 60, range: "0..1440"
        }
        section("Notifications") {
            input name: "notificationDevice", type: "capability.notification", title: "Send notifications to:", multiple: false, required: false
            input name: "notifyOnAllExcluded", type: "bool", title: "Notify when all sensors are excluded", defaultValue: true
            input name: "notifyOnFirstExcluded", type: "bool", title: "Notify when any sensor is excluded", defaultValue: false
            input name: "notifyOnValueChange", type: "bool", title: "Notify when aggregate value changes", defaultValue: false
        }
        section("Operation") {
            input name: "forceUpdate", type: "button", title: "Force update aggregate value"
            if(inputSensors && state.aggregateValue != null) {
                def possibleValues = CAPABILITY_ATTRIBUTES[selectedSensorCapability]?.values
                paragraph "Current aggregate value: <b>${state.aggregateValue}</b>"
                paragraph "Included sensors: ${state.includedSensors?.size() ?: 0} of ${inputSensors.size()}"
                if (state.excludedSensors?.size() > 0) {
                    paragraph "<span style='color:orange'>Excluded sensors: ${state.excludedSensors.join(', ')}</span>"
                }
            }
            input name: "logLevel", type: "enum", options: ["warn","info","debug","trace"], title: "Enable logging?", defaultValue: "info", required: true, submitOnChange: true
            log.info("${logLevel} logging enabled")
        }
    }
}

void installed() {
    logDebug "installed()"
    initialize()
}

void updated() {
    logDebug "updated()"

    // Validate settings
    if (!selectedSensorCapability) {
        logError("No sensor capability selected")
        return
    }
    if (!inputSensors || inputSensors.size() == 0) {
        logError("No input sensors selected")
        return
    }
    if (!attributeValue) {
        logError("No attribute value selected")
        return
    }

    unsubscribe()
    initialize()
}

void uninstalled() {
    logDebug "uninstalled()"
    // Clean up child devices
    getChildDevices()?.each {
        try {
            deleteChildDevice(it.deviceNetworkId)
            logDebug("Deleted child device: ${it.deviceNetworkId}")
        } catch (Exception e) {
            logError("Failed to delete child device ${it.deviceNetworkId}: ${e.message}")
        }
    }
}

void initialize() {
    if (state.includedSensors == null) { state.includedSensors = [] }
    if (state.excludedSensors == null) { state.excludedSensors = [] }
    if (state.aggregateValue == null) { state.aggregateValue = "" }
    if (state.createChild == null) { state.createChild = false }
    if (state.previousExcludedSensors == null) { state.previousExcludedSensors = [] }

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

    // Perform initial calculation
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
        try {
            cd = addChildDevice("hubitat", driverName, deviceName, [name: "${app.label} ${driverName}"])
            if (cd) {
                logDebug("Child device ${cd.id} created with driver: ${driverName}.")
                app.updateSetting("outputSensor", [type: selectedSensorCapability, value: cd.id])
            } else {
                logError("Could not create child device")
            }
        } catch (Exception e) {
            logError("Failed to create child device: ${e.message}")
        }
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

        String attributeName = CAPABILITY_ATTRIBUTES[selectedSensorCapability].attribute
        sensorDevice.sendEvent(
            name: attributeName,
            value: state.aggregateValue,
            descriptionText: "${sensorDevice.displayName} was set to ${state.aggregateValue}"
        )
        logDebug("Updated ${sensorDevice.displayName} ${attributeName} to ${state.aggregateValue}")
    }
}


boolean computeAggregateSensorValue() {
    Date now = new Date()
    Date timeAgo = new Date(now.time - excludeAfter * 60 * 1000)
    String attributeName = CAPABILITY_ATTRIBUTES[selectedSensorCapability]?.attribute
    def possibleValues = CAPABILITY_ATTRIBUTES[selectedSensorCapability]?.values

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
            logTrace("Excluding sensor ${it.getLabel()} (${it.currentValue(attributeName)}) - no events since $timeAgo")
        }
    }

    int n = includedSensors.size()

    // Store previous values for comparison
    String previousValue = state.aggregateValue
    List<String> previouslyExcludedLabels = state.excludedSensors ?: []
    List<String> currentlyExcludedLabels = excludedSensors.collect { it.getLabel() }

    // Check for newly excluded sensors
    List<String> newlyExcluded = currentlyExcludedLabels - previouslyExcludedLabels
    if (newlyExcluded.size() > 0 && notificationDevice && notifyOnFirstExcluded) {
        String message = "Sensor Aggregator '${app.label}': Sensors excluded due to inactivity: ${newlyExcluded.join(', ')}"
        notificationDevice.deviceNotification(message)
        logWarn(message)
    }

    if (n < 1) {
        if (notificationDevice && notifyOnAllExcluded && previouslyExcludedLabels.size() < currentlyExcludedLabels.size()) {
            String message = "Sensor Aggregator '${app.label}': All sensors excluded due to inactivity"
            notificationDevice.deviceNotification(message)
            logWarn(message)
        }
        logError "No sensors available for aggregation... aggregate value not updated (${state.aggregateValue})"
        state.excludedSensors = currentlyExcludedLabels
        return false
    }

    def sensorValues = includedSensors.collect { it.currentValue(attributeName) }
    def targetValue = attributeValue
    def oppositeValue = possibleValues.find { it != targetValue }

    // Compute aggregate value based on method
    switch (aggregationMethod) {
        case "all":
            state.aggregateValue = sensorValues.every { it == targetValue } ? targetValue : oppositeValue
            break

        case "majority":
            int countTarget = sensorValues.count { it == targetValue }
            state.aggregateValue = (countTarget > n / 2) ? targetValue : oppositeValue
            break

        case "threshold":
            int countTarget = sensorValues.count { it == targetValue }
            double percentage = (countTarget / (double)n) * 100.0
            state.aggregateValue = (percentage >= thresholdPercent) ? targetValue : oppositeValue
            logDebug("Threshold check: ${countTarget}/${n} = ${roundToDecimalPlaces(percentage, 1)}% (threshold: ${thresholdPercent}%)")
            break

        case "any":
        default:
            state.aggregateValue = sensorValues.any { it == targetValue } ? targetValue : oppositeValue
            break
    }

    state.includedSensors = includedSensors.collect { it.getLabel() }
    state.excludedSensors = currentlyExcludedLabels

    // Check if value changed
    boolean valueChanged = (previousValue != state.aggregateValue)
    if (valueChanged) {
        logInfo("Aggregate value changed: ${previousValue} → ${state.aggregateValue}")
        if (notificationDevice && notifyOnValueChange) {
            String message = "Sensor Aggregator '${app.label}': Value changed from ${previousValue} to ${state.aggregateValue}"
            notificationDevice.deviceNotification(message)
        }
    }

    logStatistics()
    return valueChanged
}

void appButtonHandler(String buttonName) {
    switch (buttonName) {
        case "forceUpdate":
        default:
            logInfo("Force update triggered")
            updated()
            break
    }
}

void logStatistics() {
    String attributeName = CAPABILITY_ATTRIBUTES[selectedSensorCapability].attribute
    int includedCount = state.includedSensors?.size() ?: 0
    int totalCount = inputSensors?.size() ?: 0

    String methodDisplay = aggregationMethod
    if (aggregationMethod == "threshold") {
        methodDisplay = "${aggregationMethod} (${thresholdPercent}%)"
    }

    logInfo("${attributeName} ${methodDisplay} (${includedCount}/${totalCount}): ${state.aggregateValue}")

    if (includedCount > 0) {
        logDebug("Aggregated sensors: ${state.includedSensors.join(', ')}")
    } else {
        logDebug("No aggregated sensors!")
    }

    if (state.excludedSensors?.size() > 0) {
        logDebug("Excluded sensors (no updates for ${excludeAfter} min): ${state.excludedSensors.join(', ')}")
    }
}

private List<String> getAttributePossibleValues(deviceWrapper, capability) {
    List capabilities = deviceWrapper.getCapabilities()
    def targetCapability = capabilities.find { it.name.toLowerCase() == capability.substring("capability.".length()).toLowerCase() }
    def targetAttribute = deviceWrapper.getSupportedAttributes().find { it.name == targetCapability.attributes[0].name }
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

private void logError(Object... args) {
    log.error(args)
}

private void logWarn(Object... args) {
    log.warn(args)
}

private void logInfo(Object... args) {
    if (logLevel in ["info","debug","trace"]) log.info(args)
}

private void logDebug(Object... args) {
    if (logLevel in ["debug","trace"]) log.debug(args)
}

private void logTrace(Object... args) {
    if (logLevel in ["trace"]) log.trace(args)
}

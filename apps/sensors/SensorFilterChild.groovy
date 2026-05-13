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

 An app that applies filtering to a sensor output and sets a virtual sensor to the filtered value

 */

import groovy.transform.Field
import groovy.transform.CompileStatic

@Field static final String CHILD_APP_VERSION = "0.0.2"

definition(
    name: "Sensor Filter Child",
    namespace: "iamtrep",
    author: "pj",
    description: "Apply moving average or median filter to sensor data",
    category: "Utility",
    iconUrl: "",
    iconX2Url: "",
    importUrl: "https://raw.githubusercontent.com/iamtrep/hubitat/refs/heads/main/apps/sensors/SensorFilterChild.groovy",
    parent: "iamtrep:Sensor Filters"
)

preferences {
    page(name: "mainPage")
}

Map mainPage() {
    Map<String, String> windowSizes = [:]
    (3..25).step(2) { windowSizes["$it"] = "$it samples" }

    dynamicPage(name: "mainPage", title: "Filter Configuration", install: true, uninstall: true) {
        section("Filter Name") {
            label title: "Enter a name for this filter:", required: true
        }

        section("Select Devices") {
            input "sourceDevice", "capability.*",
                  title: "Source Device",
                  required: true,
                  multiple: false,
                  submitOnChange: true

            if (sourceDevice) {
                List<String> attributes = getDeviceAttributes(sourceDevice)
                input "attributeToFilter", "enum",
                      title: "Select Attribute to Filter",
                      options: attributes,
                      required: true,
                      submitOnChange: true
            }

            input "targetDevice", "capability.*",
                  title: "Target Device (Virtual Sensor)",
                  required: true,
                  multiple: false,
                  submitOnChange: true

            if (targetDevice && attributeToFilter) {
                List<String> targetAttributes = getDeviceAttributes(targetDevice)
                if (!targetAttributes.contains(attributeToFilter)) {
                    paragraph "<span style='color:red'>Warning: Target device does not support the '${attributeToFilter}' attribute. " +
                             "You may need to modify your virtual device's driver to support this attribute.</span>"
                }
            }
        }

        section("Filter Configuration") {
            input "filterType", "enum",
                  title: "Filter Type",
                  options: ["median": "Median Filter", "average": "Moving Average"],
                  required: true,
                  defaultValue: "average"

            input "windowSize", "enum",
                  title: "Window Size",
                  options: windowSizes,
                  required: true,
                  defaultValue: "5"

            input "decay", "number",
                  title: "Window decay in minutes",
                  defaultValue: 1
        }

        section("Logging") {
            input "txtEnable", "bool",
                  title: "Enable descriptionText logging",
                  defaultValue: true

            input "debugEnable", "bool",
                  title: "Enable debug logging",
                  defaultValue: false,
                  submitOnChange: true

            if (debugEnable) {
                input "traceEnable", "bool",
                      title: "Enable trace logging",
                      defaultValue: false
            }

            input "logRetention", "number",
                  title: "Days to retain debug/trace logging when enabled",
                  required: true,
                  defaultValue: 7,
                  range: "1..30"
        }
    }
}

private List<String> getDeviceAttributes(dev) {
    List<String> attributes = []
    try {
        attributes = dev.supportedAttributes.collect { it.name }.sort()
        dev.currentStates.each { st ->
            if (!attributes.contains(st.name)) {
                attributes.add(st.name)
            }
        }
    } catch (e) {
        logWarn "Error getting attributes for device ${dev}: ${e}"
        attributes = ["temperature", "humidity", "illuminance", "motion", "contact", "switch", "level", "battery"]
    }
    return attributes.unique()
}

void installed() {
    initialize()
    if (debugEnable || traceEnable) {
        runIn(logRetention * 86400, "logsOff")
    }
}

void updated() {
    unsubscribe()
    initialize()
    if (debugEnable || traceEnable) {
        runIn(logRetention * 86400, "logsOff")
    }
}

void initialize() {
    if (state.version != CHILD_APP_VERSION) {
        logWarn "New version: ${CHILD_APP_VERSION} (was: ${state.version})"
        state.version = CHILD_APP_VERSION
    }

    if (state.valueWindow == null) state.valueWindow = []
    logDebug "Initializing with settings: ${settings} and window ${state.valueWindow}"

    if (sourceDevice && attributeToFilter) {
        subscribe(sourceDevice, attributeToFilter, handleNewValue)
        logDebug "Subscribed to ${sourceDevice.displayName}.${attributeToFilter}"
    }
}

void handleNewValue(evt) {
    def value = evt.value  // polymorphic: String, Integer, or BigDecimal after coercion below

    if (isInteger(value)) {
        value = value.toInteger()
    } else if (isDecimal(value)) {
        value = value.toBigDecimal()
    } else {
        logDebug "Value ${value} is not numeric, keeping as string"
    }

    int windowSizeInt = settings.windowSize.toInteger()

    List newWindow = (state.valueWindow ?: []) + [value]
    logTrace "Added value ${value} of type ${getObjectClassName(value)} to window ${newWindow}"

    while (newWindow.size() > windowSizeInt) {
        logTrace "Removing oldest value ${newWindow[0]} from window"
        newWindow = newWindow.drop(1)
    }

    state.valueWindow = newWindow
    logTrace "Window: ${state.valueWindow}"

    Number filteredValue = updateFilteredValue()
    logDebug "Raw value: ${value}, Filtered value: ${filteredValue}"

    runIn(settings.decay * 60, "decayWindow")
}

Number updateFilteredValue() {
    Number filteredValue

    if (settings.filterType == "median") {
        filteredValue = calculateMedian(state.valueWindow)
        logTrace "Calculated median: ${filteredValue}"
    } else {
        filteredValue = calculateAverage(state.valueWindow)
        logTrace "Calculated average: ${filteredValue}"
    }

    try {
        targetDevice.sendEvent(name: attributeToFilter, value: filteredValue, unit: sourceDevice.currentState(attributeToFilter)?.unit)
        logTrace "Updated target device ${targetDevice.displayName} with value ${filteredValue}"
    } catch (Exception e) {
        logWarn "Error updating target device: ${e}"
    }

    return filteredValue
}

void decayWindow() {
    List window = state.valueWindow ?: []
    if (window.size() > 1) {
        logTrace "Removing oldest value ${window[0]} from window"
        state.valueWindow = window.drop(1)
        updateFilteredValue()
    }

    if (state.valueWindow.size() > 1) runIn(settings.decay * 60, "decayWindow")
}

Number calculateMedian(List values) {
    boolean allInteger = values.every { it instanceof Integer }
    List sortedValues = values.clone().sort()
    logTrace "Sorted values: ${sortedValues} (isInteger=$allInteger)"

    int size = sortedValues.size()
    if (size == 0) return null

    int midpoint = (int)(size / 2)
    BigDecimal medianValue

    if (size % 2 == 0) {
        medianValue = (sortedValues[midpoint - 1] + sortedValues[midpoint]) / 2.0
    } else {
        medianValue = sortedValues[midpoint]
    }

    if (allInteger) {
        return medianValue.setScale(0, BigDecimal.ROUND_HALF_UP).intValue()
    } else {
        return medianValue.setScale(2, BigDecimal.ROUND_HALF_UP)
    }
}

Number calculateAverage(List values) {
    logTrace "Values: ${values}"

    BigDecimal sum = 0
    int count = values.size()
    boolean allInteger = values.every { it instanceof Integer }

    values.each { value ->
        if (value instanceof BigDecimal) {
            sum += value
        } else if (value instanceof Integer) {
            sum += value.toBigDecimal()
        } else {
            sum += value.toString().toBigDecimal()
        }
    }

    BigDecimal average = sum / count

    if (allInteger) {
        return average.setScale(0, BigDecimal.ROUND_HALF_UP).intValue()
    } else {
        return average.setScale(2, BigDecimal.ROUND_HALF_UP)
    }
}

void logsOff() {
    log.warn "${app.label}: disabling debug/trace logging"
    app.updateSetting("debugEnable", [value: "false", type: "bool"])
    app.updateSetting("traceEnable", [value: "false", type: "bool"])
}

boolean isNumeric(String value) {
    return value ==~ /^-?\d+(\.\d+)?$/
}

boolean isInteger(String value) {
    return value ==~ /^-?\d+$/
}

boolean isDecimal(String value) {
    return value ==~ /^-?\d*\.\d+$/
}

// logging helpers

private void logError(String msg) { log.error "${app.label}: ${msg}" }
private void logWarn (String msg) { log.warn  "${app.label}: ${msg}" }
private void logInfo (String msg) { if (txtEnable)   log.info  "${app.label}: ${msg}" }
private void logDebug(String msg) { if (debugEnable) log.debug "${app.label}: ${msg}" }
private void logTrace(String msg) { if (traceEnable) log.trace "${app.label}: ${msg}" }

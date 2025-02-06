definition(
    name: "Sensor Filter Child",
    namespace: "iamtrep",
    author: "pj",
    description: "Apply moving average or median filter to sensor data",
    category: "Utility",
    iconUrl: "",
    iconX2Url: "",
    importUrl: "https://raw.githubusercontent.com/iamtrep/hubitat/refs/heads/main/apps/SensorFilterChild.groovy",
    parent: "iamtrep:Sensor Filter Manager"
)

preferences {
    page(name: "mainPage")
}

def mainPage() {
    def windowSizes = [:]
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
                def attributes = getDeviceAttributes(sourceDevice)
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
                def targetAttributes = getDeviceAttributes(targetDevice)
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
            input "logEnable", "bool",
                  title: "Enable debug logging",
                  defaultValue: false

            input "traceEnable", "bool",
                  title: "Enable trace logging",
                  defaultValue: false

            input "logRetention", "number",
                  title: "Days to retain logging",
                  required: true,
                  defaultValue: 7,
                  range: "1..30"
        }
    }
}

private getDeviceAttributes(dev) {
    def attributes = []
    try {
        attributes = dev.supportedAttributes.collect { it.name }.sort()
        dev.currentStates.each { state ->
            if (!attributes.contains(state.name)) {
                attributes.add(state.name)
            }
        }
    } catch (e) {
        log.warn "Error getting attributes for device ${dev}: ${e}"
        attributes = ["temperature", "humidity", "illuminance", "motion", "contact", "switch", "level", "battery"]
    }
    return attributes.unique()
}

def installed() {
    initialize()
    if (logEnable || traceEnable) {
        runIn(logRetention * 86400, disableLogging)
    }
}

def updated() {
    unsubscribe()
    initialize()
    if (logEnable || traceEnable) {
        runIn(logRetention * 86400, disableLogging)
    }
}

def initialize() {
    if (state.valueWindow == null) state.valueWindow = []
    if (logEnable) log.debug "Initializing with settings: ${settings} and window ${state.valueWindow}"

    if (sourceDevice && attributeToFilter) {
        subscribe(sourceDevice, attributeToFilter, handleNewValue)
        if (logEnable) log.debug "Subscribed to ${sourceDevice.displayName}.${attributeToFilter}"
    }
}

def handleNewValue(evt) {
    def value = evt.value

    if (isInteger(value)) {
        value = value.toInteger()
    } else if (isDecimal(value)) {
        value = value.toBigDecimal()
    } else {
        if (logEnable) log.debug "Value ${value} is not numeric, keeping as string"
    }

    state.valueWindow.add(value)
    if (traceEnable) log.trace "Added value ${value} of type ${getObjectClassName(value)} to window ${state.valueWindow}"

    def windowSizeInt = settings.windowSize.toInteger()

    while (state.valueWindow.size() > windowSizeInt) {
        if (traceEnable) log.trace "Removing oldest value ${state.valueWindow[0]} from window"
        state.valueWindow.remove(0)
    }

    if (traceEnable) log.trace "Window: ${state.valueWindow}"
    def filteredValue = updateFilteredValue()

    if (logEnable) {
        log.debug "Raw value: ${value}, Filtered value: ${filteredValue}"
    }

    runIn(settings.decay*60, "decayWindow")
}

def updateFilteredValue() {
    def filteredValue

    if (settings.filterType == "median") {
        filteredValue = calculateMedian(state.valueWindow)
        if (traceEnable) log.trace "Calculated median: ${filteredValue}"
    } else {
        filteredValue = calculateAverage(state.valueWindow)
        if (traceEnable) log.trace "Calculated average: ${filteredValue}"
    }

    try {
        targetDevice.sendEvent(name: attributeToFilter, value: filteredValue, unit: sourceDevice.currentState(attributeToFilter)?.unit)
        if (traceEnable) log.trace "Updated target device ${targetDevice.displayName} with value ${filteredValue}"
    } catch (Exception e) {
        log.warn "Error updating target device: ${e}"
    }

    return filteredValue
}

def decayWindow() {
    if (state.valueWindow.size() > 1) {
        if (traceEnable) log.trace "Removing oldest value ${state.valueWindow[0]} from window"
        state.valueWindow.remove(0)
        updateFilteredValue()
    }

    if (state.valueWindow.size() > 1) runIn(settings.decay*60, "decayWindow")
}

def calculateMedian(values) {
    def isInteger = values.every { it instanceof Integer }
    def sortedValues = values.clone().sort()
    if (traceEnable) log.trace "Sorted values: ${sortedValues} (isInteger=$isInteger)"

    def size = sortedValues.size()
    if (size == 0) return null

    def midpoint = (int)(size / 2)
    BigDecimal medianValue

    if (size % 2 == 0) {
        // Even number of elements - average the middle two
        medianValue = (sortedValues[midpoint - 1] + sortedValues[midpoint]) / 2.0
    } else {
        // Odd number of elements - return middle value
        medianValue = sortedValues[midpoint]
    }

    if (isInteger) {
        return medianValue.setScale(0, BigDecimal.ROUND_HALF_UP).intValue()
    } else {
        return medianValue.setScale(2, BigDecimal.ROUND_HALF_UP)
    }
}

def calculateAverage(values) {
    if (traceEnable) log.trace "Values: ${values}"

    def sum = 0
    def count = values.size()
    def isInteger = values.every { it instanceof Integer }

    values.each { value ->
        if (value instanceof BigDecimal) {
            sum += value
        } else if (value instanceof Integer) {
            sum += value.toBigDecimal()
        } else {
            sum += value.toString().toBigDecimal()
        }
    }

    def average = sum / count

    if (isInteger) {
        return average.setScale(0, BigDecimal.ROUND_HALF_UP).intValue()
    } else {
        return average.setScale(2, BigDecimal.ROUND_HALF_UP)
    }
}

def disableLogging() {
    log.warn "Disabling logging"
    app.updateSetting("logEnable", [value: "false", type: "bool"])
    app.updateSetting("traceEnable", [value: "false", type: "bool"])
}

def isNumeric(value) {
    return value ==~ /^-?\d+(\.\d+)?$/
}

def isInteger(value) {
    return value ==~ /^-?\d+$/
}

def isDecimal(value) {
    return value ==~ /^-?\d*\.\d+$/
}

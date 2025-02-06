definition(
    name: "Sensor Filter Child",
    namespace: "iamtrep",
    author: "pj",
    description: "Apply moving average or median filter to sensor data",
    category: "Utility",
    iconUrl: "",
    iconX2Url: "",
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
    if (logEnable) log.debug "Initializing with settings: ${settings}"
    state.valueWindow = []

    if (sourceDevice && attributeToFilter) {
        subscribe(sourceDevice, attributeToFilter, handleNewValue)
        if (logEnable) log.debug "Subscribed to ${sourceDevice.displayName}.${attributeToFilter}"
    }
}

def handleNewValue(evt) {
    def value = evt.value

    try {
        value = value.toBigDecimal()
    } catch (Exception e) {
        if (logEnable) log.debug "Value ${value} is not numeric, keeping as string"
    }

    if (traceEnable) log.trace "Adding value ${value} to window ${state.valueWindow}"
    state.valueWindow.add(value)

    def windowSizeInt = settings.windowSize.toInteger()

    while (state.valueWindow.size() > windowSizeInt) {
        if (traceEnable) log.trace "Removing oldest value ${state.valueWindow[0]} from window"
        state.valueWindow.remove(0)
    }

    if (state.valueWindow.size() == windowSizeInt) {
        if (traceEnable) log.trace "Window is full: ${state.valueWindow}"
        def filteredValue

        if (value instanceof BigDecimal) {
            if (settings.filterType == "median") {
                filteredValue = calculateMedian(state.valueWindow)
                if (traceEnable) log.trace "Calculated median: ${filteredValue}"
            } else {
                filteredValue = calculateAverage(state.valueWindow)
                if (traceEnable) log.trace "Calculated average: ${filteredValue}"
            }
        } else {
            filteredValue = calculateMedian(state.valueWindow)
            if (traceEnable) log.trace "Calculated median for non-numeric: ${filteredValue}"
        }

        if (logEnable) {
            log.debug "Raw value: ${value}, Filtered value: ${filteredValue}"
        }

        try {
            targetDevice.parse([[name: attributeToFilter, value: filteredValue, unit: evt.unit]])
            if (traceEnable) log.trace "Updated target device ${targetDevice.displayName} with value ${filteredValue}"
        } catch (Exception e) {
            log.warn "Error updating target device: ${e}"
        }
    }
}

def calculateMedian(values) {
    def sortedValues = values.sort()
    def middle = (int)(sortedValues.size() / 2)
    return sortedValues[middle]
}

def calculateAverage(values) {
    if (values[0] instanceof BigDecimal) {
        return (values.sum() / values.size()).setScale(2, BigDecimal.ROUND_HALF_UP)
    }
    return values[values.size() - 1]
}

def disableLogging() {
    log.warn "Disabling logging"
    app.updateSetting("logEnable", [value: "false", type: "bool"])
    app.updateSetting("traceEnable", [value: "false", type: "bool"])
}

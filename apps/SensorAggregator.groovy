/*

 Sensor Aggregator

 An app that allows aggregating sensor values and saving the result to a virtual device

 */


definition(
    name: "Sensor Aggregator",
    namespace: "iamtrep",
    author: "PJ",
    description: "Aggregate sensor values and save to a single virtual device",
    category: "",
    iconUrl: "",
    iconX2Url: "",
    importUrl: ""
)

import groovy.transform.Field

@Field static final String app_version = "0.0.1"

preferences {
	page(name: "mainPage")
}

def mainPage() {
	dynamicPage(name: "mainPage", title: " ", install: true, uninstall: true) {
        section("Sensors") {
		    input "appName", "text", title: "Name this humidity sensor aggregator", submitOnChange: true
		    if(appName) app.updateLabel("$appName")
            input name: "humiditySensors", type: "capability.relativeHumidityMeasurement", title: "Humidity sensors to aggregate", multiple:true, required: true, showFilter: true, submitOnChange: true
            input name: "outputSensor", type: "capability.relativeHumidityMeasurement", title: "Virtual Humidity sensor to update", required: true, submitOnChange: true
            if (outputSensor) {
                def commands = outputSensor.getSupportedCommands().collect { it.name }
                input name: "outputSensorUpdateCommand", type: "enum", options: commands, title: "Select command to update virtual device", required: true
            }
        }
        section("Aggregation") {
            input name: "aggregationMethod", type: "enum", options: ["average", "median", "min", "max"], title: "Select aggregation method", defaultValue: "average", required: true, submitOnChange: true
            input name: "excludeAfter", type: "number", title: "Exclude sensor value when sensor is inactive for this many minutes:", defaultValue: 60, range: "0..3600"
        }
        section("Operation") {
            input name: "forceUpdate", type: "button", title: "Force update all sensors"
            if(humiditySensors) paragraph "Current $aggregationMethod value is ${state.averageHumidity}%" // (included: ${state.includedSensors.collect { it.getLabel() }} excluded: ${state.excludedSensors.collect {it.getLabel()}})"
//            if(humiditySensors) paragraph "Current $aggregationMethod value is ${state.averageHumidity}% (included: ${state.includedSensors.collect { it.getLabel() }}" excluded: ${state.excludedSensors.collect { it.getLabel() }})"
        }
        section("Logging") {
            input name: "logEnable", type: "bool", title: "Enable logging?", defaultValue: false, required: true, submitOnChange: true
        }
    }
}

def installed() {
    log.debug "installed()"
}

def updated() {
    log.debug "updated()"
    unsubscribe()
    subscribe(humiditySensors, "humidity", "humidityHandler")

    if (state.includedSensors == null) { state.includedSensors = [] }
    if (state.excludedSensors == null) { state.excludedSensors = [] }
    if (state.averageHumidity == null) { state.averageHumidity = 0 }
    if (state.minHumidity == null) { state.minHumidity = 0 }
    if (state.maxHumidity == null) { state.maxHumidity = 0 }
    if (state.medianHumidity == null) { state.medianHumidity = 0 }

    humidityHandler()
}

def uninstalled() {
}


def humidityHandler(evt=null) {
    if (logEnable && evt != null) log.debug "humidityHandler() called: ${evt?.name} ${evt?.source} ${evt?.value} ${evt?.descriptionText}"
	if (computeAggregateHumidity()) {
        outputSensor."${outputSensorUpdateCommand}"(state.averageHumidity)
    }
}


def computeAggregateHumidity() {
    state.includedSensors.clear()
    state.excludedSensors.clear()

    def now = new Date()
    def timeAgo = new Date(now.time - excludeAfter * 60 * 1000)

    humiditySensors.each {
        def events = it.eventsSince(timeAgo, [max:1])
        if (events.size() > 0) {
            state.includedSensors << it
            if (logEnable) log.debug("Including sensor ${it.getLabel()} (${it.currentHumidity}%) - last event ${events[0].date}) to aggregate computation")
        } else {
            state.excludedSensors << it
            if (logEnable) log.debug("Excluding sensor ${it.getLabel()} (${it.currentHumidity}%) - last event ${it.events([max:1])[0].date}) to aggregate computation")
        }
    }

    def n = state.includedSensors.size()
    if (n<1) {
        // For now, simply don't update the app state
        log.error "No sensors available for agregation... aggregate humidity not updated (${state.averageHumidity})"
        return false
    }

    def sensorValues = state.includedSensors.collect { it.currentValue("humidity") }
    state.minHumidity = sensorValues.min()
    state.maxHumidity = sensorValues.max()
    def sum = sensorValues.sum()
    state.averageHumidity = ((sum * 10 / sensorValues.size()).toDouble().round(1) ) / 10

    def variance = sensorValues.collect { (it - state.averageHumidity) ** 2 }.sum() / n
    state.standardDeviation = (Math.sqrt(variance) * 10).round(1) / 10

    sensorValues.sort()
    if (logEnable) log.debug "sorted values: $sensorValues"
    if (n % 2 == 0) {
        // Even number of elements, average the two middle values
        state.medianHumidity = (sensorValues[(n / 2 - 1) as int] + sensorValues[(n / 2) as int]) / 2.0
    } else {
        // Odd number of elements, take the middle value
        state.medianHumidity = sensorValues[(n / 2) as int]
    }

    log.info("Average humidity across $n/${humiditySensors.size()} sensors (${state.includedSensors}) : ${state.averageHumidity}%")
    if (logEnable) log.debug("Stdev: ${state.standardDeviation}")
    if (logEnable) log.debug("Min: ${state.minHumidity}% Max: ${state.maxHumidity}%")
    if (logEnable) log.debug("Median: ${state.medianHumidity}%")
    if (logEnable) log.debug("Rejected sensors with last update older than $excludeAfter minutes: ${state.excludedSensors}")
    return true
}

def appButtonHandler(String buttonName) {
    switch (buttonName) {
        case "forceUpdate":
        default:
            humidityHandler()
            break
    }
}

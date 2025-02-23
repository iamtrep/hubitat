/*

 Sticky Motion test app

 */


definition(
    name: "Sticky Motion Sensor Aggregation"
    namespace: "iamtrep",
    //parent: "iamtrep:Sensor Aggregator",
    author: "pj",
    description: "TBD",
    category: "Convenience",
    iconUrl: "",
    iconX2Url: "",
    importUrl: ""
)

import groovy.transform.Field
import groovy.transform.CompileStatic

@Field static final String child_app_version = "0.0.1"

preferences {
	page(name: "mainPage")
}

def mainPage() {
	dynamicPage(name: "mainPage", title: " ", install: true, uninstall: true) {
    section("Select Motion Sensors") {
        input "motionSensors", "capability.motionSensor", title: "Motion Sensors", multiple: true, required: true
    }
    section("Specify Duration (in seconds)") {
        input "sensorDurations", "number", title: "Duration for each sensor (comma-separated)", required: true
    }
}

def installed() {
    initialize()
}

def updated() {
    unsubscribe()
    initialize()
}

def initialize() {
    state.sensorDurations = sensorDurations.split(",").collect { it.toInteger() }
    motionSensors.eachWithIndex { sensor, index ->
        subscribe(sensor, "motion.active", motionActiveHandler)
        subscribe(sensor, "motion.inactive", motionInactiveHandler)
    }
}

def motionActiveHandler(evt) {
    def sensor = evt.device
    def index = motionSensors.indexOf(sensor)
    def duration = state.sensorDurations[index]

    // Cancel any previously scheduled checks for this sensor
    unschedule("checkMotionState_${index}")

    // Schedule a new check
    runIn(duration, "checkMotionState_${index}", [data: [sensorId: sensor.id]])
}

def motionInactiveHandler(evt) {
    def sensor = evt.device
    def index = motionSensors.indexOf(sensor)

    // Cancel the scheduled check if motion becomes inactive
    unschedule("checkMotionState_${index}")
}

def checkMotionState_0(data) { checkMotionState(data) }
def checkMotionState_1(data) { checkMotionState(data) }
def checkMotionState_2(data) { checkMotionState(data) }
def checkMotionState_3(data) { checkMotionState(data) }
def checkMotionState_4(data) { checkMotionState(data) }
def checkMotionState_5(data) { checkMotionState(data) }
def checkMotionState_6(data) { checkMotionState(data) }
def checkMotionState_7(data) { checkMotionState(data) }
def checkMotionState_8(data) { checkMotionState(data) }
def checkMotionState_9(data) { checkMotionState(data) }
// Repeat for as many handlers as needed

def checkMotionState(data) {
    def sensorId = data.sensorId
    def sensor = motionSensors.find { it.id == sensorId }
    if (sensor?.currentMotion == "active") {
        sendEvent(name: "motionConditionMet", value: sensor.displayName)
        log.debug "Condition met for ${sensor.displayName}"
    }
}

import groovy.transform.Field

metadata {
    definition (
        name: "Location Event Transmitter"
        namespace: "iamtrep",
        author: "PJ",
        importUrl:"https://raw.githubusercontent.com/iamtrep/hubitat/main/LocationEventDriver.groovy"
    ) {
        capability "Actuator"

        attribute "btnDescription", "string"
        attribute "zwaveStatus", "string"
        attribute "zigbeeStatus", "string"

    }
}

preferences {
    input("debugEnable", "bool", title: "Enable debug logging?", width:4)
}

def configure(){
}

def installed() {
    initialize()
}

def uninstalled(){
}

def initialize() {
}

def updated(){
}

def subscribeLocationEvents() {
}

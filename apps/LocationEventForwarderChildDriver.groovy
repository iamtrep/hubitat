metadata {
    definition (
        name: "Location Event Forwarder Driver"
        namespace: "iamtrep",
        author: "PJ",
        importUrl:""
    ) {
        capability "Actuator"
    }
}

import groovy.transform.Field

preferences {
    //input("debugEnable", "bool", title: "Enable debug logging?", width:4)
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

def parse(evt) {
    log.debug("in parse() : $evt")
}

def handleHubVariableEvent(evt) {
    log.debug("in handleHubVariableEvent() : $evt")
}

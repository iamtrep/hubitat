definition(
    name: "Location Event Forwarder",
    namespace: "iamtrep",
    author: "PJ",
    description: "Forwards location events to a child driver for use in RM",
    category: "Convenience",
    iconUrl: "",
    iconX2Url: "")

preferences {
}

def installed() {
    log.debug "installed()"

    setupChildDriver()
    subscribe(location,"forwardLocationEvent")
}

def updated() {
    log.debug "updated()"
}

def uninstalled() {
}

// ******************

def forwardLocationEvent(evt) {
    log.debug(evt)
}

def setupChildDriver() {
    addChildDevice("iamtrep", "Location Event Forwarder Driver", "${app.id}-1", [isComponent: true])
}

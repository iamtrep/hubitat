definition(
    name: "Sample Application",
    namespace: "Example",
    author: "Hubitat Example",
    description: "A skeleton sample app for HE",
    category: "Convenience",
    iconUrl: "",
    iconX2Url: "")

preferences {
}

def installed() {
    log.debug "installed()"
}

def updated() {
    log.debug "updated()"
}

def uninstalled() {}

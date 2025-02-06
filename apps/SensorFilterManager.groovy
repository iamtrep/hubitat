definition(
    name: "Sensor Filter Manager",
    namespace: "iamtrep",
    author: "pj",
    description: "Manages multiple sensor filter instances",
    category: "Utility",
    iconUrl: "",
    iconX2Url: "",
    importUrl: "",
    singleInstance: true
)

preferences {
    page(name: "mainPage")
}

def mainPage() {
    dynamicPage(name: "mainPage", title: "Sensor Filter Manager", install: true, uninstall: true) {
        section {
            paragraph "Manage your sensor filters here"
            app(name: "sensorFilters",
                appName: "Sensor Filter Child",
                namespace: "iamtrep",
                title: "Create New Filter",
                multiple: true)
        }
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
    log.debug "Initializing Sensor Filter Manager"
}

/*

 Sensor Aggregator Main app

 For creating & grouping Sensor Aggregator apps.  These apps aggregate sensor values and save the result to a virtual device

 */

definition(
    name: "Sensor Aggregator",
    namespace: "iamtrep",
    author: "PJ",
    singleInstance: true,
    description: "Manage sensor aggregators - apps that aggregate sensor values and save the result to a single virtual device",
    category: "Convenience",
    importUrl: "",
    iconUrl: "",
    iconX2Url: ""
)


import groovy.transform.Field
import groovy.transform.CompileStatic

@Field static final String child_app_version = "0.0.5"


preferences {
    page(name: "mainPage")
}

def mainPage(){
    dynamicPage(name: "mainPage", title: " ", install: true, uninstall: true) {
        section ("Set up or manage Sensor Aggregator instances"){
            app(name: "saChildApps", appName: "Sensor Aggregator Child", namespace: "iamtrep", title: "Create New Sensor Aggregator", submitOnChange: true, multiple: true)
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
    log.debug "there are ${saChildApps.size()} sensor aggregators : ${saChildApps.collect { it.label } }"
}

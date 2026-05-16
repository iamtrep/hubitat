// Copyright (c) 2025-2026 PJ
// SPDX-License-Identifier: MIT

/*
 Sensor Aggregator Main app

 For creating & grouping Sensor Aggregator apps.  These apps aggregate sensor values and save the result to a virtual device
 */
definition(
    name: "Sensor Aggregator",
    namespace: "iamtrep",
    author: "pj",
    singleInstance: true,
    description: "Manage sensor aggregators - apps that aggregate sensor values and save the result to a single virtual device",
    category: "Convenience",
    importUrl: "https://raw.githubusercontent.com/iamtrep/hubitat/refs/heads/main/apps/sensors/SensorAggregator.groovy",
    iconUrl: "",
    iconX2Url: ""
)


import groovy.transform.Field
import groovy.transform.CompileStatic

@Field static final String child_app_version = "0.0.5"


preferences {
    page(name: "mainPage")
}

Map mainPage(){
    dynamicPage(name: "mainPage", title: " ", install: true, uninstall: true) {
        section ("Set up or manage Sensor Aggregator instances"){
            app(name: "saChildApps",
                appName: "Sensor Aggregator Child",
                namespace: "iamtrep",
                title: "Create New Continuous Sensor Aggregator",
                description: "Continuous sensors include humidity, temperature, power, etc.",
                submitOnChange: true,
                multiple: true
            )
            app(name: "saDiscreteChildApps",
                appName: "Sensor Aggregator Discrete Child",
                namespace: "iamtrep",
                title: "Create New Discrete Sensor Aggregator",
                description: "Discrete sensors include switch, contact, motion, etc.",
                submitOnChange: true,
                multiple: true
            )
            app(name: "mfChildApps",
                appName: "Motion Fusion Child",
                namespace: "iamtrep",
                title: "Create New Motion Fusion",
                description: "Combine PIR and mmWave inputs into a single motion output using configurable fusion algorithms",
                submitOnChange: true,
                multiple: true
            )
        }
    }
}

void installed() {
    initialize()
}

void updated() {
    log.debug "there are ${getChildApps().size()} sensor aggregators : ${getChildApps().collect { it.label } }"
}

void initialize() {
}

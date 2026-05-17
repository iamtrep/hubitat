// Copyright (c) 2025-2026 PJ
// SPDX-License-Identifier: MIT

/*
 Location Event Mapper - Main app

 For creating & grouping Location Event Mapper apps.  These apps set virtual contact sensor states based on location event triggers.
 */
definition(
    name: "Location Event Mapper",
    namespace: "iamtrep",
    author: "pj",
    singleInstance: true,
    description: "TBD",
    menu: "Automations", // new in platform 2.5.0
    category: "Convenience",
    importUrl: "",
    iconUrl: "",
    iconX2Url: ""
)


import groovy.transform.Field
import groovy.transform.CompileStatic

@Field static final String app_version = "0.0.1"


preferences {
    page(name: "mainPage")
}

Map mainPage(){
    dynamicPage(name: "mainPage", title: " ", install: true, uninstall: true) {
        section ("Set up or manage Sensor Aggregator instances"){
            app(name: "lemChildApps", appName: "Location Event Mapper Child", namespace: "iamtrep", title: "Create New Location Event Mapper", submitOnChange: true, multiple: true)
        }
    }
}

void installed() {
    initialize()
}

void updated() {
    unsubscribe()
    initialize()
}

void initialize() {
    log.debug "there are ${getChildApps().size()} location event mappers : ${getChildApps().collect { it.label } }"
}

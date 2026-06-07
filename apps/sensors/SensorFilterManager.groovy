// Copyright (c) 2025-2026 PJ
// SPDX-License-Identifier: MIT

/*
 An app to manager Sensor Filter Child app instances.
 */
import groovy.transform.Field
import groovy.transform.CompileStatic

@Field static final String CODE_VERSION = "0.0.1"

definition(
    name: "Sensor Filters",
    namespace: "iamtrep",
    author: "pj",
    description: "Manages multiple sensor filter instances",
    menu: "Automations", // new in platform 2.5.0
    category: "Utility",
    iconUrl: "",
    iconX2Url: "",
    importUrl: "https://raw.githubusercontent.com/iamtrep/hubitat/refs/heads/main/apps/sensors/SensorFilterManager.groovy",
    singleInstance: true
)

preferences {
    page(name: "mainPage")
}

Map mainPage() {
    dynamicPage(name: "mainPage", title: "", install: true, uninstall: true) {
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

void installed() {
}

void updated() {
}

void uninstalled() {
}

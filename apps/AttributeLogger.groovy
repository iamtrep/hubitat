// Copyright (c) 2025-2026 PJ
// SPDX-License-Identifier: MIT

import groovy.transform.Field
import groovy.transform.CompileStatic

@Field static final String CODE_VERSION = "0.0.1"

definition(
    name: "Attribute Logger",
    namespace: "iamtrep",
    author: "pj",
    description: "Manages multiple Attribute Logger app instances",
    menu: "Apps", // new in platform 2.5.0
    category: "Utility",
    iconUrl: "",
    iconX2Url: "",
    importUrl: "https://raw.githubusercontent.com/iamtrep/hubitat/refs/heads/main/apps/AttributeLogger.groovy",
    singleInstance: true
)

preferences {
    page(name: "mainPage")
}

Map mainPage() {
    dynamicPage(name: "mainPage", title: "", install: true, uninstall: true) {
        section {
            paragraph "Manage your device attribute loggers here"
            app(name: "attributeLoggerChild",
                appName: "Attribute Logger Child",
                namespace: "iamtrep",
                title: "Create New Logger",
                multiple: true)
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
    log.debug "Initializing Attribute Logger"
}

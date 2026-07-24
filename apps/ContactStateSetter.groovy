// Copyright (c) 2026 PJ
// SPDX-License-Identifier: MIT

/*
 Contact State Setter

 Forces selected contact sensors to "open" or "closed" by injecting a contact
 event onto the device via DeviceWrapper.sendEvent — no radio traffic, no
 physical actuation. Useful for driving automations under test.

 sendEvent here writes directly to the device's event stream, so any app or
 rule subscribed to the sensor's "contact" attribute fires exactly as it would
 for a real open/close.
*/

import groovy.transform.Field

@Field static final String APP_NAME = "Contact State Setter"
@Field static final String CODE_VERSION = "1.0.0"

definition(
    name: APP_NAME,
    namespace: "iamtrep",
    author: "pj",
    description: "Sets selected contact sensors open or closed by injecting a contact event via sendEvent.",
    menu: "Automations",
    category: "Convenience",
    singleInstance: false,
    singleThreaded: true,
    importUrl: "https://raw.githubusercontent.com/iamtrep/hubitat/refs/heads/main/apps/ContactStateSetter.groovy",
    iconUrl: "",
    iconX2Url: "",
    iconX3Url: ""
)

preferences {
    page(name: "mainPage")
}

def mainPage() {
    dynamicPage(name: "mainPage", title: "${APP_NAME} v${CODE_VERSION}", install: true, uninstall: true) {
        section("Contact sensors") {
            input name: "contacts", type: "capability.contactSensor", title: "Sensors to control",
                  multiple: true, required: true, submitOnChange: true
        }
        section("Set state") {
            input name: "setOpen", type: "button", title: "Set Open"
            input name: "setClosed", type: "button", title: "Set Closed"
            if (settings.contacts) {
                paragraph currentStates()
            }
        }
        section("Options") {
            label title: "App name", required: false
            input name: "debugLogging", type: "bool", title: "Enable debug logging", defaultValue: false
        }
    }
}

private String currentStates() {
    settings.contacts.collect { dev ->
        "${dev.displayName}: ${dev.currentValue("contact") ?: "—"}"
    }.join("<br>")
}

def installed() {
    initialize()
}

def updated() {
    initialize()
}

def initialize() {
    // Stateless: all work happens on button press. Nothing to subscribe or schedule.
}

void appButtonHandler(String btn) {
    switch (btn) {
        case "setOpen":   setContact("open");   break
        case "setClosed": setContact("closed"); break
        default: log.warn "${APP_NAME}: unknown button '${btn}'"
    }
}

private void setContact(String value) {
    settings.contacts?.each { dev ->
        dev.sendEvent(name: "contact", value: value,
                      descriptionText: "${dev.displayName} contact set to ${value} by ${APP_NAME}")
        if (settings.debugLogging) log.debug "${APP_NAME}: ${dev.displayName} contact -> ${value}"
    }
}

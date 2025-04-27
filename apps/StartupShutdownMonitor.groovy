/**

 MIT License

Copyright (c) 2025 pj

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.

 *  Startup and Shutdown Monitor
 *
 *  Description: This app monitors system events and controls a virtual contact sensor.
 *  - Opens contact on: manualReboot, manualShutdown, update
 *  - Closes contact on: systemStart
 *
 *  Mostly AI generated. Copy & distribute.
 */

import groovy.transform.Field
import groovy.transform.CompileStatic

@Field static final String app_version = "0.0.1"

definition(
    name: "Startup and Shutdown Monitor",
    namespace: "iamtrep",
    author: "pj",
    description: "Controls a virtual contact sensor based on system events related to startup, shutdown and reboot",
    category: "Utility",
    iconUrl: "",
    iconX2Url: "",
    importUrl: "https://raw.githubusercontent.com/iamtrep/hubitat/refs/heads/main/apps/StartupShutdownMonitor.groovy"
)

preferences {
    page(name: "mainPage")
}

def mainPage() {
    dynamicPage(name: "mainPage", title: "Setup", install: true, uninstall: true) {
        section("Select Virtual Contact Sensor") {
            input "contactSensor", "capability.contactSensor", title: "Contact Sensor", required: true
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
    subscribe(location, "manualReboot", eventHandler)
    subscribe(location, "manualShutdown", eventHandler)
    subscribe(location, "update", eventHandler)
    subscribe(location, "systemStart", eventHandler)

    log.debug "${app.getLabel()} initialized"
}

def eventHandler(evt) {
    log.debug "System event detected: ${evt.name}"

    switch(evt.name) {
        case "manualReboot":
            openContact("Manual reboot detected")
            break
        case "manualShutdown":
            openContact("Manual shutdown detected")
            break
        case "update":
            openContact("System update detected")
            break
        case "systemStart":
            closeContact("System start detected")
            break
        default:
            log.debug "Unhandled event: ${evt.name}"
    }
}

def openContact(message) {
    log.debug "${message} - Opening contact"
    contactSensor?.open()
}

def closeContact(message) {
    log.debug "${message} - Closing contact"
    contactSensor?.close()
}

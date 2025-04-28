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

Startup and Shutdown Monitor

Description: This app monitors system events to detect when the hub is shutting down/starting up.
 - Opens the selected virtual contact sensor on: manualReboot, manualShutdown, update
 - Closes the selected virtual contact sensor on: systemStart

The virtual contact sensor can be used in automations (e.g. Rule Machine - can be
used as a Required Expression or condtion for triggers).

 */

import groovy.transform.Field

import com.hubitat.hub.domain.Event

@Field static final String app_version = "0.0.1"

definition(
    name: "Startup and Shutdown Monitor",
    namespace: "iamtrep",
    author: "pj",
    description: "Controls a virtual contact sensor based on system events related to startup, shutdown and reboot",
    category: "Utility",
    iconUrl: "",
    iconX2Url: "",
    importUrl: "https://raw.githubusercontent.com/iamtrep/hubitat/refs/heads/main/apps/StartupShutdownMonitor.groovy",
    singleInstance: true
)

preferences {
    page(name: "mainPage")
}

Map mainPage() {
    dynamicPage(name: "mainPage", title: "${app.getLabel()} Setup", install: true, uninstall: true) {
        section("Select Virtual Contact Sensor") {
            input("contactSensor", "capability.contactSensor", title: "Virtual Contact Sensor", multiple:false, required:true, showFilter:true)
            paragraph("<a href='/device/addDevice' target='_blank'>Click here</a> to create a new Virtual Contact Sensor for use with this app")
        }
        section("Logging") {
            input name: "logLevel", type: "enum", options: ["warn","info","debug","trace"], title: "Enable logging?", defaultValue: "info", required: true, submitOnChange: true
            if (logLevel != null) logInfo("${app.getLabel()}: ${logLevel} logging enabled")
        }
    }
}

// runs when the app is first installed
void installed() {
    initialize()
    logDebug "${app.getLabel()} installed"
}

// runs whenever app preferences are saved (click Done on app config page)
void updated() {
    unsubscribe()
    initialize()
    logDebug "${app.getLabel()} updated"
}

void initialize() {
    subscribe(location, "manualReboot", "eventHandler")
    subscribe(location, "manualShutdown", "eventHandler")
    subscribe(location, "update", "eventHandler")
    subscribe(location, "systemStart", "eventHandler")

    logDebug "${app.getLabel()} initialized"
    logDebug "${app.getLabel()} ${contactSensor?.getDisplayName()} ${contactSensor?.currentValue('contact')}"
}

void eventHandler(Event evt) {
    logDebug "System event detected: ${evt.name}"

    switch(evt.name) {
        case "manualReboot":
        case "manualShutdown":
        case "update":
            openContact(evt.descriptionText)
            break
        case "systemStart":
            closeContact(evt.descriptionText)
            break
        default:
            logWarn "Unhandled event: ${evt.name}"
            break
    }
}

void openContact(String message) {
    if (contactSensor?.currentValue('contact') == 'open') {
        logWarn("${message} - ${contactSensor?.getDisplayName()} already open - missed systemStart event?")
    }

    logInfo "${message} - Opening contact"
    contactSensor?.open()
}

void closeContact(String message) {
    if (contactSensor?.currentValue('contact') == 'closed') {
        logWarn("${message} - ${contactSensor?.getDisplayName()} already closed - missed shutdown/reboot event?")
    }

    logInfo "${message} - Closing contact"
    contactSensor?.close()
}


private void logError(Object... args)
{
    //if (logLevel in ["info","debug","trace"])
    log.error(args)
}

private void logWarn(Object... args)
{
    //if (logLevel in ["warn", "info","debug","trace"])
    log.warn(args)
}

private void logInfo(Object... args)
{
    if (loglevel == null || logLevel in ["info","debug","trace"]) log.info(args)
}

private void logDebug(Object... args)
{
    if (logLevel == null || logLevel in ["debug","trace"]) log.debug(args)
}

private void logTrace(Object... args)
{
    if (logLevel == null || logLevel in ["trace"]) log.trace(args)
}

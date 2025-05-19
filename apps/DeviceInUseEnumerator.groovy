/*
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
*/

import groovy.transform.Field
import groovy.transform.CompileStatic

@Field static final String app_version = "0.0.1"

definition(
    name: "Device \"in use by\" Enumerator",
    namespace: "iamtrep",
    author: "pj",
    description: "Enumerates the apps using each device",
    category: "Convenience",
    iconUrl: "",
    iconX2Url: ""
)

preferences {
    page(name: "mainPage")
}

def mainPage() {
    dynamicPage(name: "mainPage", title: "", install: true, uninstall: true) {
        section("Settings", hideable: true, hidden: true) {
            input "debugEnabled", "bool", title: "Enable Debug Logs", defaultValue: false, submitOnChange: true
            input "appName", "text", title: "Rename this app", defaultValue: app.getLabel(), multiple: false, required: false, submitOnChange: true
            if (appName != app.getLabel()) app.updateLabel(appName)
        }
        section("(Optional) Report only on these devices") {
            input "devices", "capability.*", title: "Select Devices", multiple: true, required: true, submitOnChange: true
        }
        section("Options") {
            input "onlyChildDevices", "bool", title: "Process and output only child devices?", defaultValue: false, submitOnChange: true
        }
        section("") {
            input "generateReport", "button", title: "Generate Report", submitOnChange: true
            if (state.generateReport) {
                state.generateReport = false
                def report = generateReport()
                paragraph report
            } else {
                paragraph "No report generated yet."
            }
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
    log.debug "Initializing..."
    state.reportOutput = null
}

def appButtonHandler(evt) {
    if (evt == "generateReport") {
        state.generateReport = true
    }
}

def generateReport() {
    log.debug "Generating report"
    def deviceAppMap = [:]
    def appMap = [:]

    def devicesList = getDevicesList()

    devicesList.each { deviceId ->
        def deviceInfo = getDeviceInfo(deviceId, appMap)
        if (!onlyChildDevices || deviceInfo.isChild) {
            deviceAppMap[deviceId] = [name: deviceInfo.name, info: deviceInfo]
        }
    }

    def sortedDevices = deviceAppMap.sort { -it.value.info.apps.size() }
    def reportHtml = "<table><tr><th>Device</th><th>Total Apps</th><th>Apps</th><th>Parent</th></tr>"

    sortedDevices.each { deviceId, deviceData ->
        def deviceUrl = getDeviceDetailsUrl(deviceId)
        def appLinks = deviceData.info.apps.collect { app -> "<a href='${getAppConfigUrl(app.id)}' target='_blank'>${app.label}</a>" }
        def parentLink = deviceData.info.parent ? "<a href='${deviceData.info.parent.url}' target='_blank'>${deviceData.info.parent.label}</a>" : "N/A"
        reportHtml += "<tr><td><a href='${deviceUrl}' target='_blank'>${deviceData.name}</a></td><td>${deviceData.info.apps.size()}</td><td>${appLinks.join(', ')}</td><td>${parentLink}</td></tr>"
    }

    reportHtml += "</table>"
    log.debug "Report generated"
    return reportHtml
}

def getDevicesList() {
    if (devices?.size() > 0) {
        log.info("Generating report for specified devices only")
        return devices.collect { device -> device.id }
    }

    log.info("Generating report for all devices")
    def url = "http://127.0.0.1:8080/hub2/devicesList"
    try {
        httpGet(url) { response ->
            if (response.status == 200) {
                return response.data.devices?.collect { device -> device.data?.id }?.findAll { it != null } ?: []
            } else {
                log.error "Failed to retrieve data for device ${device.displayName}. HTTP status: ${response.status}"
            }
        }
    } catch (Exception e) {
        log.error "Error making HTTP request: ${e.message}"
    }
}

def getDeviceInfo(device_id, appMap) {
    def displayName = ""
    def apps = []
    def isChildDevice = false
    def parent = null
    def url = "http://127.0.0.1:8080/device/fullJson/${device_id}"
    try {
        httpGet(url) { response ->
            if (response.status == 200) {
                def json = response.data
                displayName = json.device.displayName
                apps = json.appsUsing.findAll { it.id != app.id }.collect { [id: it.id, label: it.label] }
                isChildDevice = json.device.parentAppId != null || json.device.parentDeviceId != null
                if (json.device.parentDeviceId) {
                    parent = getParentDeviceInfo(json.device.parentDeviceId)
                } else if (json.device.parentAppId) {
                    parent = appMap[json.device.parentAppId] ?: getParentAppInfo(json.device.parentAppId, appMap)
                }
            } else {
                log.error "Failed to retrieve data for device id ${device_id}. HTTP status: ${response.status}"
            }
        }
    } catch (Exception e) {
        log.error "Error making HTTP request: ${e.message}"
    }
    return [name: displayName, apps: apps, isChild: isChildDevice, parent: parent]
}

def getParentDeviceInfo(parentDeviceId) {
    def parent = null
    def url = "http://127.0.0.1:8080/device/fullJson/${parentDeviceId}"
    try {
        httpGet(url) { response ->
            if (response.status == 200) {
                def json = response.data
                def label = json.device.label ?: json.device.name
                parent = [label: label, url: getDeviceDetailsUrl(parentDeviceId)]
            } else {
                log.error "Failed to retrieve data for parent device. HTTP status: ${response.status}"
            }
        }
    } catch (Exception e) {
        log.error "Error making HTTP request: ${e.message}"
    }
    return parent
}

def getParentAppInfo(parentAppId, appMap) {
    def parent = null
    def url = "http://127.0.0.1:8080/installedapp/statusJson/${parentAppId}"
    try {
        httpGet(url) { response ->
            if (response.status == 200) {
                def json = response.data
                def label = json.installedApp.label ?: json.installedApp.name
                parent = [label: label, url: getAppConfigUrl(parentAppId)]
                appMap[parentAppId] = parent
            } else {
                log.error "Failed to retrieve data for parent app. HTTP status: ${response.status}"
            }
        }
    } catch (Exception e) {
        log.error "Error making HTTP request: ${e.message}"
    }
    return parent
}

def getHubBaseLocalUrl() {
    return "http://${location.hubs[0].getDataValue("localIP")}"
}

def getDeviceDetailsUrl(id) {
    return "${getHubBaseLocalUrl()}/device/edit/$id"
}

def getAppConfigUrl(id) {
    return "${getHubBaseLocalUrl()}/installedapp/configure/$id"
}

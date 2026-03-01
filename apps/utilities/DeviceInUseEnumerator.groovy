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

 An app that tracks down which apps use given devices, with special attention to child devices.

*/

import groovy.transform.Field
import groovy.transform.CompileStatic
import com.hubitat.app.DeviceWrapper

@Field static final String app_version = "0.0.1"

definition(
    name: "Device \"in use by\" Enumerator",
    namespace: "iamtrep",
    author: "pj",
    description: "For each device, enumerates the apps referencing them",
    category: "Utility",
    importUrl: "https://raw.githubusercontent.com/iamtrep/hubitat/refs/heads/main/apps/DeviceInUseEnumerator.groovy",
    iconUrl: "",
    iconX2Url: ""
)

preferences {
    page(name: "mainPage")
}

@Field static final constDevicesListURL = "http://127.0.0.1:8080/hub2/devicesList"
@Field static final constDeviceFullJsonURL = "http://127.0.0.1:8080/device/fullJson/"
@Field static final constAppStatusURL = "http://127.0.0.1:8080/installedapp/statusJson/"


Map mainPage() {
    dynamicPage(name: "mainPage", title: "", install: true, uninstall: true) {
        section("Settings", hideable: true, hidden: true) {
            input name: "logLevel", type: "enum", options: ["warn","info","debug","trace"], title: "Enable logging?", defaultValue: "info", required: true, submitOnChange: true
            if (logLevel != null) logInfo("${logLevel} logging enabled")
            input "appName", "text", title: "Rename this app", defaultValue: app.getLabel(), multiple: false, required: false, submitOnChange: true
            if (appName != app.getLabel()) app.updateLabel(appName)
        }
        section("Options") {
            input "devices", "capability.*", title: "Only report on these specific devices", multiple: true, required: false, submitOnChange: true
            input "onlyChildDevices", "bool", title: "Process and output only child devices?", defaultValue: false, submitOnChange: true
        }
        section("") {
            input "generateReport", "button", title: "Generate Report", submitOnChange: true
            if (state.generateReport) {
                state.generateReport = false
                paragraph generateReport()
            } else {
                paragraph "No report generated yet."
            }
        }
    }
}

void installed() {
    logTrace("installed()")
    initialize()
}

void updated() {
    logTrace("updated()")
    unsubscribe()
    initialize()
}

void initialize() {
    logTrace "initialize()"
    state.reportOutput = null
}

void uninstalled() {
    logTrace("uninstalled()")
}

void appButtonHandler(evt) {
    if (evt == "generateReport") {
        state.generateReport = true
    }
}

String generateReport() {
    logInfo "Generating report"
    Map deviceAppMap = [:]
    Map appMap = [:]

    List devicesList = getDevicesList()

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
    logInfo "Report generated"
    return reportHtml
}

private List getDevicesList() {
    if (devices?.size() > 0) {
        logInfo("Generating report for specified devices only")
        return devices.collect { device -> device.id.toInteger() }
    }

    logInfo("Generating report for all devices")
    try {
        httpGet(constDevicesListURL) { response ->
            if (response.status == 200) {
                return response.data.devices?.collect { device -> device.data?.id }?.findAll { it != null } ?: []
            } else {
                logError "Failed to retrieve data for device ${device.displayName}. HTTP status: ${response.status}"
            }
        }
    } catch (Exception e) {
        logError "Error making HTTP request for devices list: ${e.message}"
    }
}

private Map getDeviceInfo(Integer device_id, Map appMap) {
    String displayName = ""
    List apps = []
    boolean isChildDevice = false
    Map parent = null

    try {
        httpGet(constDeviceFullJsonURL + device_id) { response ->
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
                logError "Failed to retrieve data for device id ${device_id}. HTTP status: ${response.status}"
            }
        }
    } catch (Exception e) {
        logError "Error making HTTP request for device id ${device_id}: ${e.message}"
    }
    return [name: displayName, apps: apps, isChild: isChildDevice, parent: parent]
}

private Map getParentDeviceInfo(Integer parentDeviceId) {
    Map parent = null
    String url = constDeviceFullJsonURL + parentDeviceId
    try {
        httpGet(url) { response ->
            if (response.status == 200) {
                def json = response.data
                def label = json.device.label ?: json.device.name
                parent = [label: label, url: getDeviceDetailsUrl(parentDeviceId)]
            } else {
                logError "Failed to retrieve data for parent device id ${parentDeviceId}. HTTP status: ${response.status}"
            }
        }
    } catch (Exception e) {
        logError "Error making HTTP request for parent device id ${parentDeviceid}: ${e.message}"
    }
    return parent
}

private Map getParentAppInfo(Integer parentAppId, Map appMap) {
    Map parent = null
    String url = constAppStatusURL + parentAppId
    try {
        httpGet(url) { response ->
            if (response.status == 200) {
                def json = response.data
                String label = json.installedApp.label ?: json.installedApp.name
                parent = [label: label, url: getAppConfigUrl(parentAppId)]
                appMap[parentAppId] = parent
            } else {
                logError "Failed to retrieve data for parent app id ${parentAppId}. HTTP status: ${response.status}"
            }
        }
    } catch (Exception e) {
        logError "Error making HTTP request for parent app id ${parentAppid}: ${e.message}"
    }
    return parent
}

private DeviceWrapper getDeviceById(Integer deviceId) {
    app.updateSetting("tempDeviceWrapper", [value:deviceId, type:"capability.*"])
    return tempDeviceWrapper
}

private String getHubBaseLocalUrl() {
    return "http://${location.hubs[0].getDataValue("localIP")}"
}

private String getDeviceDetailsUrl(Integer deviceId) {
    return "${getHubBaseLocalUrl()}/device/edit/$deviceId"
}

private String getAppConfigUrl(Integer appId) {
    return "${getHubBaseLocalUrl()}/installedapp/configure/$appId"
}

// logging helpers

private void logError(String msg)
{
    //if (logLevel in ["info","debug","trace"])
    log.error(app.getLabel() + ': ' + msg)
}

private void logWarn(String msg)
{
    //if (logLevel in ["warn", "info","debug","trace"])
    log.warn(app.getLabel() + ': ' + msg)
}

private void logInfo(String msg)
{
    if (logLevel == null || logLevel in ["info","debug","trace"]) log.info(app.getLabel() + ': ' + msg)
}

private void logDebug(String msg)
{
    if (logLevel == null || logLevel in ["debug","trace"]) log.debug(app.getLabel() + ': ' + msg)
}

private void logTrace(String msg)
{
    if (logLevel == null || logLevel in ["trace"]) log.trace(app.getLabel() + ': ' + msg)
}

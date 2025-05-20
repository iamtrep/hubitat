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

 An app to log device attributes to a file

 Can be used with Watchtower app.

 */

import groovy.transform.Field
import groovy.transform.CompileStatic
import com.hubitat.app.DeviceWrapper
import com.hubitat.hub.domain.Event

@Field static final String child_app_version = "0.0.2"

definition(
    name: "Attribute Logger Child",
    namespace: "iamtrep",
    author: "pj",
    description: "Logs selected attributes to a CSV file",
    category: "Convenience",
    iconUrl: "",
    iconX2Url: "",
    importUrl: "https://raw.githubusercontent.com/iamtrep/hubitat/refs/heads/main/apps/AttributeLoggerChild.groovy",
    parent: "iamtrep:Attribute Logger",
    singleThreaded: true  // to avoid concurrent file access
)

preferences {
    page(name: "mainPage")
}

Map mainPage() {
    dynamicPage(name: "mainPage", title: "Attribute Logger", install: true, uninstall: true) {
        section("App Settings", hideable: true, hidden: false) {
            label title: "Set App Label", required: false
            input "logFileName", "text", title: "Log File Name", description: "Enter the name of the log file (e.g., log.csv)", defaultValue: "log.csv", required: true
            input name: "logLevel", type: "enum", options: ["warn","info","debug","trace"], title: "Enable logging?", defaultValue: "info", required: true, submitOnChange: true
            if (logLevel != null) logInfo("${logLevel} logging enabled")
        }
        section("Select Device and Attributes") {
            input "selectedDevice", "capability.*", title: "Select Device", multiple: false, required: true, submitOnChange: true
            if (selectedDevice) {
                input "selectedAttributes", "enum", title: "Select Attributes", multiple: true, required: true, submitOnChange: true, options: getDeviceAttributes(selectedDevice)
            }
            if (state.previousDeviceId != selectedDevice?.id || state.previousAttributes != selectedAttributes) {
                state.pendingChanges = true
                paragraph "Warning: Changing the device or attributes will result in the loss of existing data."
                input "confirmChanges", "bool", title: "Confirm Changes", required: true, submitOnChange: true
            } else {
                state.pendingChanges = false
            }
        }
        section("") {
            paragraph "Version ${child_app_version}"
        }
    }
}

void installed() {
    state.previousDeviceId = selectedDevice?.id
    state.previousAttributes = selectedAttributes
    initialize()
}

void updated() {
    if (state.pendingChanges && confirmChanges) {
        state.pendingChanges = false
        state.previousDeviceId = selectedDevice?.id
        state.previousAttributes = selectedAttributes
    	String header = "timestamp," + selectedAttributes.join(',') + "\n"
	    //uploadHubFile(logFileName, header.bytes)
    }
    initialize()
}

void uninstalled() {
}

void initialize() {
    unsubscribe()
    selectedAttributes.each { attribute ->
        subscribe(selectedDevice, attribute, handleEvent)
    }
}

void handleEvent(Event evt) {
    Integer timestamp = new Date().getTime() / 1000
    List attributeValues = selectedAttributes.collect { attribute ->
        selectedDevice.currentValue(attribute)
    }
    String csvLine = "${timestamp},${attributeValues.join(',')}\n"
    logTrace("new data ${csvLine}")
    writeFile(csvLine)
}

void writeFile(String data) {
    String existingData = null
    try {
        byte[] byteArray = safeDownloadHubFile(logFileName)
        existingData = new String(byteArray)
    } catch (Exception e) {
        logWarn "Could not read existing data: ${e.message}"
    }
    if (existingData == null) {
        existingData = "timestamp," + selectedAttributes.join(',') + "\n"
    }
    String newData = existingData + data
    safeUploadHubFile(logFileName, newData.bytes)
}


byte[] safeDownloadHubFile(String fileName) {
    int maxRetries = 3

    for (int retryCount in 0..<maxRetries) {
        try {
            return downloadHubFile(fileName)
        } catch (Exception e) {
            if (retryCount < maxRetries) {
                logDebug "Could not read from $fileName: ${e.message}"
                logWarn "Retrying download of $fileName... Attempt ${retryCount}"
                pauseExecution(500)
            } else {
                logError "Failed to download $fileName after ${maxRetries} attempts"
                throw e
            }
        }
    }

    return byteArray
}


void safeUploadHubFile(String fileName, byte[] bytes) {
    int maxRetries = 3

    for (int retryCount in 0..<maxRetries) {
        try {
            uploadHubFile(fileName, bytes)
            return
        } catch (Exception e) {
            if (retryCount < maxRetries) {
                logDebug "Could not write to $fileName: ${e.message}"
                logWarn "Retrying upload of $fileName... Attempt ${retryCount}"
                pauseExecution(500)
            } else {
                logError "Failed to upload $fileName after ${maxRetries} attempts - possible data loss"
                throw e
            }
        }
    }
}

List getDeviceAttributes(DeviceWrapper device) {
    if (!device) {
        logWarn "No device selected"
        return []
    }
    List attributes = []
    device.getCapabilities().each { capability ->
        capability.attributes.each { attribute ->
            attributes << attribute.name
            logDebug "Capability: ${capability.name}, Attribute: ${attribute.name}"
        }
    }
    List uniqueAttributes = attributes.unique()
    logDebug "Unique attributes: ${uniqueAttributes}"
    return uniqueAttributes ?: ["No supported attributes found"]
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

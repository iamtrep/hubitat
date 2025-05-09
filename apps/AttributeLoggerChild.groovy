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

@Field static final String child_app_version = "0.0.1"

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
    singleThreaded: true
)

preferences {
    page(name: "mainPage")
}

def mainPage() {
    dynamicPage(name: "mainPage", title: "Attribute Logger", install: true, uninstall: true) {
        section("App Settings") {
            label title: "Set App Label", required: false
            input "logFileName", "text", title: "Log File Name", description: "Enter the name of the log file (e.g., log.csv)", defaultValue: "log.csv", required: true
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
        section("Logging") {
            input "debugEnable", "bool", title: "Enable Debug Logging", defaultValue: true, required: false
        }
    }
}

def installed() {
    state.previousDeviceId = selectedDevice?.id
    state.previousAttributes = selectedAttributes
    initialize()
}

def updated() {
    if (state.pendingChanges && confirmChanges) {
        state.pendingChanges = false
        state.previousDeviceId = selectedDevice?.id
        state.previousAttributes = selectedAttributes
    	def header = "timestamp," + selectedAttributes.join(',') + "\n"
	    //uploadHubFile(logFileName, header.bytes)
    }
    unsubscribe()
    initialize()
}

def initialize() {
    selectedAttributes.each { attribute ->
        subscribe(selectedDevice, attribute, handleEvent)
    }
}

def handleEvent(evt) {
    def timestamp = new Date().getTime() / 1000
    def attributeValues = selectedAttributes.collect { attribute ->
        selectedDevice.currentValue(attribute)
    }
    def csvLine = "${timestamp},${attributeValues.join(',')}\n"
    writeFile(csvLine)
}

def writeFile(data) {
    def existingData = ""
    try {
        def byteArray = safeDownloadHubFile(logFileName)
        existingData = new String(byteArray)
    } catch (Exception e) {
        log.warn "Could not read existing data: ${e.message}"
    }
    if (existingData == "") {
        existingData = "timestamp," + selectedAttributes.join(',') + "\n"
    }
    def newData = existingData + data
    safeUploadHubFile(logFileName, newData.bytes)
}


def safeDownloadHubFile(fileName) {
    int maxRetries = 3
    int retryCount = 0
    boolean success = false

    byte[] byteArray

    while (retryCount < maxRetries && !success) {
        try {
            byteArray = downloadHubFile(fileName)
            success = true
        } catch (Exception e) {
            retryCount++
            if (retryCount < maxRetries) {
                if (debugEnable) log.debug "Could not read from $fileName: ${e.message}"
                log.warn "Retrying download of $fileName... Attempt ${retryCount}"
                pauseExecution(500)
            } else {
                log.error "Failed to download $fileName after ${maxRetries} attempts"
                throw e
            }
        }
    }

    return byteArray
}


def safeUploadHubFile(fileName, bytes) {
    int maxRetries = 3
    int retryCount = 0
    boolean success = false

    while (retryCount < maxRetries && !success) {
        try {
            uploadHubFile(fileName, bytes)
            success = true
        } catch (Exception e) {
            retryCount++
            if (retryCount < maxRetries) {
                if (debugEnable) log.debug "Could not write to $fileName: ${e.message}"
                log.warn "Retrying upload of $fileName... Attempt ${retryCount}"
                pauseExecution(500)
            } else {
                log.error "Failed to upload $fileName after ${maxRetries} attempts - possible data loss"
                throw e
            }
        }
    }
}

def getDeviceAttributes(device) {
    if (!device) {
        log.warn "No device selected"
        return []
    }
    def attributes = []
    device.getCapabilities().each { capability ->
        capability.attributes.each { attribute ->
            attributes << attribute.name
            log.debug "Capability: ${capability.name}, Attribute: ${attribute.name}"
        }
    }
    def uniqueAttributes = attributes.unique()
    log.debug "Unique attributes: ${uniqueAttributes}"
    return uniqueAttributes ?: ["No supported attributes found"]
}

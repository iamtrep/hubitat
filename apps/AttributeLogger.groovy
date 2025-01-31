definition(
    name: "Attribute Logger",
    namespace: "iamtrep",
    author: "pj",
    description: "Logs selected attributes to a CSV file",
    category: "Convenience",
    iconUrl: "",
    iconX2Url: "",
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
        createNewFileWithHeader()
    }
    unsubscribe()
    initialize()
}

def initialize() {
    selectedAttributes.each { attribute ->
        subscribe(selectedDevice, attribute, handleEvent)
    }
}

def createNewFileWithHeader() {
    def header = "timestamp," + selectedAttributes.join(',') + "\n"
    uploadHubFile(logFileName, header.bytes)
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
        def byteArray = downloadHubFile(logFileName)
        existingData = new String(byteArray)
    } catch (Exception e) {
        log.warn "Could not read existing data: ${e.message}"
    }
    def newData = existingData + data
    uploadHubFile(logFileName, newData.bytes)
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

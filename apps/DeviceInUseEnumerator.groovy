
definition(
    name: "Device and App Enumerator",
    namespace: "iamtrep",
    author: "pj",
    description: "Enumerates the apps using each device",
    category: "Convenience",
    iconUrl: "",
    iconX2Url: ""
)

preferences {
    section("Select Devices") {
        input "devices", "capability.*", title: "Select Devices", multiple: true, required: true
    }
    section("Options") {
        input "onlyChildDevices", "bool", title: "Process and output only child devices?", defaultValue: false, submitOnChange: true
    }
    section("Actions") {
        input "generateReport", "button", title: "Generate Report"
    }
    section("Report") {
        paragraph state.reportOutput ?: "No report generated yet."
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
}

def appButtonHandler(evt) {
    if (evt == "generateReport") {
    	state.reportOutput = "Generating report..."
        runIn(1, "generateReport")
        //generateReport()
    }
}

def generateReport() {
    def deviceAppMap = [:]

    devices.each { device ->
        def deviceInfo = getDeviceInfo(device)
        if (!onlyChildDevices || deviceInfo.isChild) {
            deviceAppMap[device.displayName] = deviceInfo
        }
    }

    def sortedDevices = deviceAppMap.sort { -it.value.apps.size() }
    def reportHtml = "<table><tr><th>Device</th><th>Total Apps</th><th>Apps</th><th>Is Child Device</th></tr>"

    sortedDevices.each { device, info ->
        reportHtml += "<tr><td>${device}</td><td>${info.apps.size()}</td><td>${info.apps.join(', ')}</td><td>${info.isChild}</td></tr>"
    }

    reportHtml += "</table>"
    state.reportOutput = reportHtml
    log.debug "Report generated"
}

def getDeviceInfo(device) {
    def apps = []
    def isChildDevice = false
    def url = "http://127.0.0.1:8080/device/fullJson/${device.id}"
    try {
        httpGet(url) { response ->
            if (response.status == 200) {
                def json = response.data
                apps = json.appsUsing.findAll { it.id != app.id }.collect { it.label }
                isChildDevice = json.device.parentAppId != null || json.device.parentDeviceId != null
            } else {
                log.error "Failed to retrieve data for device ${device.displayName}. HTTP status: ${response.status}"
            }
        }
    } catch (Exception e) {
        log.error "Error making HTTP request: ${e.message}"
    }
    return [apps: apps, isChild: isChildDevice]
}

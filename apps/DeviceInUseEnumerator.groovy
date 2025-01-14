definition(
    name: "Device and App Enumerator",
    namespace: "iamtrep",
    author: "pj",
    description: "Enumerates the apps using each selected device",
    category: "Convenience",
    iconUrl: "",
    iconX2Url: ""
)

preferences {
    section("Select Devices") {
        input "devices", "capability.*", title: "Select Devices", multiple: true, required: true
    }
    section("Options") {
        input "onlyChildDevices", "bool", title: "Process and output only child devices?", defaultValue: false
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
    listDevicesAndApps()
}

def listDevicesAndApps() {
    def deviceAppMap = [:]

    devices.each { device ->
        def deviceInfo = getDeviceInfo(device)
        if (!onlyChildDevices || deviceInfo.isChild) {
            deviceAppMap[device.displayName] = deviceInfo
        }
    }

    def sortedDevices = deviceAppMap.sort { -it.value.apps.size() }
    sortedDevices.each { device, info ->
        log.debug "Device: ${device}, Total Apps: ${info.apps.size()}, Apps: ${info.apps}, Is Child Device: ${info.isChild}"
    }
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

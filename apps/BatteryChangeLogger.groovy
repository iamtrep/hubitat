definition(
    name: "Battery Change Logger",
    namespace: "dragons",
    author: "slayer",
    description: "Monitors battery levels and appends a timestamped note to device notes when a replacement is detected",
    category: "Utility",
    iconUrl: "",
    iconX2Url: ""
)

preferences {
    page(name: "mainPage")
}

Map mainPage() {
    dynamicPage(name: "mainPage", title: "Battery Change Logger", install: true, uninstall: true) {
        section("Devices") {
            input "batteryDevices", "capability.battery",
                title: "Devices to monitor",
                required: true, multiple: true
        }
        section("Settings") {
            input "threshold", "number",
                title: "Battery increase threshold (%)",
                description: "Treat a battery level increase of at least this amount as a replacement",
                required: true, defaultValue: 20, range: "1..99"
        }
        section("Logging") {
            input "logLevel", "enum",
                title: "Log level",
                options: ["warn", "info", "debug"],
                defaultValue: "info", required: true
        }
    }
}

void installed() {
    logDebug "installed()"
    initialize()
}

void updated() {
    logDebug "updated()"
    unsubscribe()
    initialize()
}

void uninstalled() {
    logDebug "uninstalled()"
}

void initialize() {
    logDebug "initialize()"
    if (state.batteryLevels == null) {
        state.batteryLevels = [:]
    }
    subscribe(batteryDevices, "battery", "batteryHandler")
    seedInitialLevels()
}

// Seed current battery level for any device not yet tracked.
// Called on every initialize() so newly-added devices are picked up
// without resetting levels for devices already being tracked.
void seedInitialLevels() {
    Map levels = state.batteryLevels ?: [:]
    boolean changed = false
    batteryDevices.each { device ->
        String deviceId = device.id.toString()
        if (!levels.containsKey(deviceId)) {
            String currentVal = device.currentValue("battery")?.toString()
            if (currentVal != null) {
                levels[deviceId] = currentVal.toInteger()
                changed = true
                logDebug "Seeded ${device.displayName} at ${currentVal}%"
            }
        }
    }
    if (changed) {
        state.batteryLevels = levels
    }
}

void batteryHandler(evt) {
    String deviceId = evt.deviceId.toString()
    String deviceLabel = evt.displayName
    int newLevel = evt.value.toInteger()

    Map levels = state.batteryLevels ?: [:]
    Integer lastLevel = levels[deviceId] != null ? (levels[deviceId] as Integer) : null

    if (lastLevel != null) {
        int delta = newLevel - lastLevel
        logDebug "${deviceLabel}: ${lastLevel}% -> ${newLevel}% (delta: ${delta}%)"
        if (delta >= (settings.threshold as Integer)) {
            logInfo "Battery replacement detected on ${deviceLabel}: ${lastLevel}% -> ${newLevel}%"
            fetchDeviceAndLog(deviceId, deviceLabel, lastLevel, newLevel)
        }
    } else {
        logDebug "${deviceLabel}: first reading, seeding at ${newLevel}%"
    }

    // Always update stored level so the next event has an accurate baseline
    levels[deviceId] = newLevel
    state.batteryLevels = levels
}

// Step 1: fetch the current device JSON so we can echo all fields back in the POST
void fetchDeviceAndLog(String deviceId, String deviceLabel, int oldLevel, int newLevel) {
    Map params = [
        uri        : "http://127.0.0.1:8080",
        path       : "/device/fullJson/${deviceId}",
        contentType: "application/json"
    ]
    Map callbackData = [deviceId: deviceId, deviceLabel: deviceLabel, oldLevel: oldLevel, newLevel: newLevel]
    try {
        asynchttpGet("handleFullJsonResponse", params, callbackData)
    } catch (Exception e) {
        logError "Error fetching device data for ${deviceLabel}: ${e.message}"
    }
}

// Step 2: append the note entry and POST all device fields back
void handleFullJsonResponse(resp, data) {
    if (resp.hasError()) {
        logError "Error fetching device data for ${data.deviceLabel}: ${resp.getErrorMessage()}"
        return
    }
    if (resp.status != 200) {
        logError "HTTP ${resp.status} fetching device data for ${data.deviceLabel}"
        return
    }

    try {
        Map json = resp.json
        Map device = json.device

        String timestamp = new Date().format("yyyy-MM-dd HH:mm", location.timeZone)
        String newEntry = "[${timestamp}] Battery replaced: ${data.oldLevel}% -> ${data.newLevel}%"
        String existingNotes = ((device.notes ?: "") as String).trim()
        String updatedNotes = existingNotes ? "${existingNotes}\n${newEntry}" : newEntry

        // /device/update expects selected dashboard IDs as a comma-separated string
        List dashboards = (json.dashboards ?: []) as List
        String dashboardIds = dashboards.findAll { it.selected }.collect { it.id.toString() }.join(",")

        // Echo all device fields back; only notes changes.
        // Booleans must be "on" (true) or "false" (false) — standard HTML checkbox encoding.
        Map postFields = [
            id                    : device.id.toString(),
            version               : device.version.toString(),
            name                  : (device.name ?: "") as String,
            label                 : (device.label ?: "") as String,
            zigbeeId              : (device.zigbeeId ?: "") as String,
            maxEvents             : device.maxEvents.toString(),
            maxStates             : device.maxStates.toString(),
            spammyThreshold       : device.spammyThreshold.toString(),
            deviceNetworkId       : (device.deviceNetworkId ?: "") as String,
            deviceTypeId          : device.deviceTypeId.toString(),
            deviceTypeReadableType: (device.deviceTypeReadableType ?: "") as String,
            roomId                : (device.roomId ?: 0).toString(),
            meshEnabled           : device.meshEnabled ? "on" : "false",
            retryEnabled          : device.retryEnabled ? "on" : "false",
            meshFullSync          : device.meshFullSync ? "on" : "false",
            homeKitEnabled        : (json.homeKitEnabled ?: false) ? "on" : "false",
            locationId            : device.locationId.toString(),
            hubId                 : device.hubId.toString(),
            groupId               : (device.groupId ?: 0).toString(),
            dashboardIds          : dashboardIds,
            defaultIcon           : (device.defaultIcon ?: "") as String,
            tags                  : (device.tags ?: "") as String,
            notes                 : updatedNotes,
            controllerType        : (device.controllerType ?: "") as String
        ]

        // Manually URL-encode the body so special characters (%, newlines, brackets, etc.)
        // in field values (especially notes) are safely transmitted.
        // requestContentType registers the correct encoder so the string is passed through as-is.
        String body = postFields.collect { k, v ->
            URLEncoder.encode(k as String, "UTF-8") + "=" + URLEncoder.encode(v as String, "UTF-8")
        }.join("&")

        Map postParams = [
            uri               : "http://127.0.0.1:8080",
            path              : "/device/update",
            requestContentType: "application/x-www-form-urlencoded",
            body              : body,
            textParser        : true   // don't try to JSON-parse the redirect response
        ]
        asynchttpPost("handleUpdateResponse", postParams, [deviceLabel: data.deviceLabel, notes: updatedNotes])
    } catch (Exception e) {
        logError "Error processing device data for ${data.deviceLabel}: ${e.message}"
    }
}

// Step 3: confirm the update succeeded
// POST /device/update returns 302 (redirect to device edit page) on success;
// the async HTTP client may follow it and deliver 200, so accept both.
void handleUpdateResponse(resp, data) {
    int status = resp.status
    if (status == 200 || status == 302) {
        logInfo "Battery replacement logged for ${data.deviceLabel}"
        logDebug "Notes: ${data.notes}"
    } else {
        logError "Failed to update device notes for ${data.deviceLabel}: HTTP ${status}"
        if (resp.hasError()) logError resp.getErrorMessage()
    }
}

// ---- Logging helpers ----

private void logDebug(String msg) {
    if (logLevel == "debug") log.debug "${app.getLabel()}: ${msg}"
}

private void logInfo(String msg) {
    if (logLevel in ["info", "debug"]) log.info "${app.getLabel()}: ${msg}"
}

private void logWarn(String msg) {
    log.warn "${app.getLabel()}: ${msg}"
}

private void logError(String msg) {
    log.error "${app.getLabel()}: ${msg}"
}

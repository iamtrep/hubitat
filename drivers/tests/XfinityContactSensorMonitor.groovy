// Copyright (c) 2025-2026 PJ
// SPDX-License-Identifier: MIT

import groovy.transform.Field
import com.hubitat.hub.domain.Event
import java.nio.file.AccessDeniedException

@Field static final String CODE_VERSION = "0.0.3"

@Field static final List<String> MONITORED_ATTRIBUTES = ["battery", "batteryVoltage", "lowBattery", "batteryDefect"]
@Field static final String CSV_HEADER = "timestamp,deviceId,deviceLabel,attribute,value"

definition(
    name: "Xfinity Contact Sensor Monitor",
    namespace: "iamtrep",
    author: "pj",
    description: "Logs battery, batteryVoltage, lowBattery and batteryDefect events for Xfinity contact sensors and optionally notifies",
    menu: "Apps",
    category: "Utility",
    iconUrl: "",
    iconX2Url: "",
    importUrl: "https://raw.githubusercontent.com/iamtrep/hubitat/refs/heads/main/drivers/tests/XfinityContactSensorMonitor.groovy",
    singleThreaded: true  // serialize file read-modify-write across concurrent events
)

preferences {
    page(name: "mainPage")
}

Map mainPage() {
    dynamicPage(name: "mainPage", title: "", install: true, uninstall: true) {
        section("App Name", hideable: true, hidden: true) {
            label title: "Set App Label", required: false
        }
        section("Devices") {
            input "sensors", "capability.contactSensor",
                title: "Contact sensors to monitor",
                description: "Pick devices using the Xfinity / Universal Electronics / Visonic contact sensor driver. Other contact sensors are tolerated but will only fire 'battery' events.",
                required: true, multiple: true
        }
        section("Log File") {
            input "logFileName", "text",
                title: "Log file name (on File Manager)",
                defaultValue: "xfinity-contact-sensor-monitor.csv",
                required: true
        }
        section("Notifications") {
            input "notifyDevice", "capability.notification",
                title: "Notification device (optional)",
                required: false, multiple: false, submitOnChange: true
            if (notifyDevice) {
                input "notifyOnBattery", "bool",
                    title: "Notify on battery % events",
                    defaultValue: true, required: false
                input "notifyOnLowBattery", "bool",
                    title: "Notify on lowBattery changes",
                    defaultValue: true, required: false
                input "notifyOnBatteryDefect", "bool",
                    title: "Notify on batteryDefect changes",
                    defaultValue: true, required: false
            }
        }
        section("Logging", hideable: true, hidden: true) {
            input "logLevel", "enum",
                title: "Log level",
                options: ["warn", "info", "debug", "trace"],
                defaultValue: "info", required: true
        }
        section("") {
            paragraph "Version ${CODE_VERSION}"
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
    sensors?.each { device ->
        MONITORED_ATTRIBUTES.each { String attr ->
            subscribe(device, attr, "attributeHandler")
        }
    }
    logInfo "Monitoring ${MONITORED_ATTRIBUTES.size()} attributes on ${sensors?.size() ?: 0} device(s)"
}

void attributeHandler(Event evt) {
    String timestamp = new Date(evt.unixTime).format("yyyy-MM-dd HH:mm:ss", location.timeZone)
    String attribute = evt.name
    String value = evt.value
    String deviceId = evt.deviceId?.toString() ?: ""
    String deviceLabel = evt.displayName ?: ""

    String row = [timestamp, deviceId, csvEscape(deviceLabel), attribute, csvEscape(value)].join(",") + "\n"
    logDebug "logging: ${row.trim()}"
    appendLogFile(row)

    if (notifyDevice && shouldNotify(attribute)) {
        String msg = "${deviceLabel}: ${attribute} = ${value}"
        notifyDevice.deviceNotification(msg)
        logInfo "notified: ${msg}"
    }
}

private boolean shouldNotify(String attribute) {
    switch (attribute) {
        case "battery":       return settings.notifyOnBattery       != false
        case "lowBattery":    return settings.notifyOnLowBattery    != false
        case "batteryDefect": return settings.notifyOnBatteryDefect != false
        default:              return false
    }
}

private String csvEscape(String s) {
    if (s == null) return ""
    if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
        return "\"" + s.replace("\"", "\"\"") + "\""
    }
    return s
}

private void appendLogFile(String row) {
    Map result = readLogFile(logFileName)
    String existing
    if (result.status == "content") {
        existing = new String(result.bytes as byte[], "UTF-8")
    } else if (result.status == "absent") {
        existing = CSV_HEADER + "\n"
    } else {
        // The file may exist: a write now would replace the history with one row.
        logError "Skipping write to ${logFileName}, could not read it (${result.reason}). Row lost: ${row.trim()}"
        return
    }
    String combined = existing + row
    safeUploadHubFile(logFileName, combined.getBytes("UTF-8"))
}

// Classifies a file read as content, absent (nothing to lose, start a new file) or failed (the
// file may exist, so nothing may be written over it). errorClass is the exception's toString() (class name first),
// null when the call returned. A missing file throws NoSuchFileException or returns null
// depending on firmware; a busy file throws AccessDeniedException.
static String classifyFileRead(String errorClass, byte[] bytes) {
    if (errorClass == null) return (bytes == null || bytes.length == 0) ? "absent" : "content"
    return errorClass.startsWith("java.nio.file.NoSuchFileException") ? "absent" : "failed"
}

// Returns [status: "content"|"absent"|"failed", bytes: byte[], reason: String].
private Map readLogFile(String fileName) {
    String errorClass = null
    String errorMsg = null
    for (int i = 1; i <= 3; i++) {
        try {
            byte[] bytes = downloadHubFile(fileName)
            return [status: classifyFileRead(null, bytes), bytes: bytes]
        } catch (AccessDeniedException ex) {
            logWarn "download ${fileName} contended: ${ex.message}. Retry ${i}/3"
            errorClass = ex.toString()
            errorMsg = ex.message
            if (i < 3) pauseExecution(500)
        } catch (Exception e) {
            errorClass = e.toString()
            return [status: classifyFileRead(errorClass, null), reason: e.message ?: errorClass]
        }
    }
    return [status: classifyFileRead(errorClass, null), reason: "${errorClass}: ${errorMsg}"]
}

private void safeUploadHubFile(String fileName, byte[] bytes) {
    for (int i = 1; i <= 3; i++) {
        try {
            uploadHubFile(fileName, bytes)
            return
        } catch (AccessDeniedException ex) {
            logWarn "upload ${fileName} contended: ${ex.message}. Retry ${i}/3"
            pauseExecution(500)
        }
    }
    logError "upload ${fileName} failed after 3 attempts — possible data loss"
}

// Logging helpers

private void logDebug(String msg) {
    if (settings.logLevel in ["debug", "trace"]) log.debug "${app.getLabel()}: ${msg}"
}

private void logInfo(String msg) {
    if (settings.logLevel == null || settings.logLevel in ["info", "debug", "trace"]) log.info "${app.getLabel()}: ${msg}"
}

private void logWarn(String msg) {
    log.warn "${app.getLabel()}: ${msg}"
}

private void logError(String msg) {
    log.error "${app.getLabel()}: ${msg}"
}

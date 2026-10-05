// Copyright (c) 2025-2026 PJ
// SPDX-License-Identifier: MIT

/*
 An app to log device attributes to a file

 Can be used with Watchtower app.
 */
import groovy.transform.Field
import groovy.transform.CompileStatic
import com.hubitat.app.DeviceWrapper
import com.hubitat.hub.domain.Event
import java.nio.file.NoSuchFileException

@Field static final String CODE_VERSION = "0.0.5"

definition(
    name: "Attribute Logger Child",
    namespace: "iamtrep",
    author: "pj",
    description: "Logs selected attributes to a CSV file",
    menu: "Apps", // new in platform 2.5.0
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
            input name: "txtEnable", type: "bool", title: "Enable info logging", defaultValue: true
            input name: "debugEnable", type: "bool", title: "Enable debug logging", defaultValue: false, submitOnChange: true
            if (settings.debugEnable) {
                input name: "traceEnable", type: "bool", title: "Enable trace logging", defaultValue: false
            }
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
            paragraph "Version ${CODE_VERSION}"
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
    if (settings.debugEnable || settings.traceEnable) runIn(1800, "logsOff")
}

void uninstalled() {
}

void initialize() {
    checkVersion(false)
    app.removeSetting("logLevel")
    unsubscribe()
    selectedAttributes.each { attribute ->
        subscribe(selectedDevice, attribute, handleEvent)
    }
}

private void checkVersion(boolean reinit = true) {
    if (state.version == CODE_VERSION) return
    logVer "New version: ${CODE_VERSION} (was: ${state.version})"
    state.version = CODE_VERSION
    if (reinit) runIn(1, "updated")
}

void handleEvent(Event evt) {
    checkVersion()
    Integer timestamp = new Date().getTime() / 1000
    List attributeValues = selectedAttributes.collect { attribute ->
        selectedDevice.currentValue(attribute)
    }
    String csvLine = "${timestamp},${attributeValues.join(',')}\n"
    logTrace("new data ${csvLine}")
    writeFile(csvLine)
}

void writeFile(String data) {
    String existingData
    try {
        byte[] byteArray = safeDownloadHubFile(logFileName)
        // A failed read must not fall through to a fresh file: the upload would overwrite the history.
        if (byteArray == null) {
            logError "Skipping write to ${logFileName}, could not read it. Row lost: ${data.trim()}"
            return
        }
        existingData = new String(byteArray)
    } catch (NoSuchFileException ignored) {
        existingData = "timestamp," + selectedAttributes.join(',') + "\n"
    }
    String newData = existingData + data
    safeUploadHubFile(logFileName, newData.bytes)
}

// Epoch seconds at the start of the row beginning at index start, or null.
Long rowSeconds(String content, int start) {
    if (start < 0 || start >= content.length()) return null
    int comma = content.indexOf(",", start)
    int newline = content.indexOf("\n", start)
    int end = comma < 0 ? newline : (newline < 0 ? comma : Math.min(comma, newline))
    if (end < 0) end = content.length()
    try {
        return new BigDecimal(content.substring(start, end).trim()).longValue()
    } catch (NumberFormatException ignored) {
        return null
    }
}

// Index of the first data row: after the header line, or 0 when the file has no header.
int firstRowIndex(String content) {
    if (!content.startsWith("timestamp")) return 0
    int newline = content.indexOf("\n")
    return newline < 0 ? content.length() : newline + 1
}

// Line start of the first row with a timestamp >= cutoff, or content.length() when none.
// Rows are in time order, so this binary-searches byte offsets and snaps to line starts.
// An unparsable row counts as old. Whatever it returns is a line start, so the rows
// before and after it always add up to the whole file.
int findCut(String content, int firstRow, long cutoff) {
    int lo = firstRow
    int hi = content.length()
    while (lo < hi) {
        int mid = (lo + hi).intdiv(2)
        int start = Math.max(firstRow, content.lastIndexOf("\n", mid - 1) + 1)
        int newline = content.indexOf("\n", mid)
        int next = newline < 0 ? content.length() : newline + 1
        Long seconds = rowSeconds(content, start)
        if (seconds != null && seconds >= cutoff) {
            hi = start
        } else {
            lo = next
        }
    }
    return lo
}

// <base>_<firstDay>_<lastDay><ext>, adding -2, -3... when the name is taken.
String archiveName(String logFileName, String firstDay, String lastDay, Collection<String> existing) {
    int dot = logFileName.lastIndexOf('.')
    String base = dot > 0 ? logFileName.substring(0, dot) : logFileName
    String ext = dot > 0 ? logFileName.substring(dot) : ""
    String stem = "${base}_${firstDay}_${lastDay}"
    String name = stem + ext
    int n = 2
    while (existing.contains(name)) {
        name = "${stem}-${n}${ext}"
        n++
    }
    return name
}

String dayStamp(long seconds, TimeZone tz) {
    return new Date(seconds * 1000L).format("yyyyMMdd", tz)
}


byte[] safeDownloadHubFile(String fileName) {
    for (int i = 1; i <= 3; i++) {
        try {
            return downloadHubFile(fileName)
        } catch (NoSuchFileException ex) {
            throw ex
        } catch (Exception ex) {
            logWarn "Failed to download ${fileName}: ${ex.message}. Retrying (${i} / 3) ..."
            pauseExecution(500)
        }
    }

    logError "Failed to download ${fileName} after 3 attempts"
    return null
}


void safeUploadHubFile(String fileName, byte[] bytes) {
    for (int i = 1; i <= 3; i++) {
        try {
            uploadHubFile(fileName, bytes)
            return
        } catch (Exception ex) {
            logWarn "Failed to upload ${fileName}: ${ex.message}. Retrying (${i} / 3) ..."
            pauseExecution(500)
        }
    }

    logError "Failed to upload ${fileName} after 3 attempts - possible data loss"
}

void safeDeleteHubFile(String fileName) {
    for (int i = 1; i <= 3; i++) {
        try {
            deleteHubFile(fileName)
            return
        } catch (Exception ex) {
            logWarn "Failed to delete ${fileName}: ${ex.message}. Retrying (${i} / 3) ..."
            pauseExecution(500)
        }
    }

    logError "Failed to delete ${fileName} after 3 attempts"
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

void logsOff() {
    app.updateSetting("debugEnable", [value: "false", type: "bool"])
    app.updateSetting("traceEnable", [value: "false", type: "bool"])
    logWarn "debug and trace logging disabled"
}

// ── Logging (app) ─────────────────────────────────────────────────────
//   ⬇️ Evt  ⬆️ Cmd  🔧 Cfg  🌐 Net  ⏰ Sched  🏷️ Ver  ·  ⚠️ Warn  🛑 Error  🔬 Trace
private String logp(String e) { "${e} ${app.getLabel()}: " }

void logEvt  (String m) { if (settings.debugEnable) log.debug logp('⬇️') + m }
void logCmd  (String m) { if (txtEnable != false) log.info  logp('⬆️') + m }
void logCfg  (String m) { if (txtEnable != false) log.info  logp('🔧') + m }
void logNet  (String m) { if (settings.debugEnable) log.debug logp('🌐') + m }
void logSched(String m) { if (settings.debugEnable) log.debug logp('⏰') + m }
void logVer  (String m) { log.warn  logp('🏷️') + m }

void logWarn (String m) { log.warn  logp('⚠️') + m }
void logError(String m) { log.error logp('🛑') + m }
void logTrace(String m) { if (settings.traceEnable) log.trace logp('🔬') + m }
void logInfo (String m) { if (txtEnable != false) log.info  "${app.getLabel()}: ${m}" }
void logDebug(String m) { if (settings.debugEnable) log.debug "${app.getLabel()}: ${m}" }

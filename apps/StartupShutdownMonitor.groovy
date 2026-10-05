// Copyright (c) 2025-2026 PJ
// SPDX-License-Identifier: MIT

/*
 Startup and Shutdown Monitor

 Description: This app monitors system events to detect when the hub is shutting down/starting up.
 - Opens the selected virtual contact sensor on: manualReboot, manualShutdown, update
 - Closes the selected virtual contact sensor on: systemStart
 - Optionally notifies when the hub starts without a prior shutdown event (unplanned restart)

 The virtual contact sensor can be used in automations (e.g. Rule Machine - can be
 used as a Required Expression or condtion for triggers).
 */
import groovy.transform.Field
import com.hubitat.hub.domain.Event

@Field static final String CODE_VERSION = "0.1.1"

@Field static final List<String> DEFAULT_OPEN_EVENTS = ["manualReboot", "manualShutdown", "update"]
@Field static final List<String> DEFAULT_CLOSE_EVENTS = ["systemStart"]

definition(
    name: "Startup and Shutdown Monitor",
    namespace: "iamtrep",
    author: "pj",
    description: "Controls a virtual contact sensor based on system events related to startup, shutdown and reboot",
    menu: "Automations", // new in platform 2.5.0
    category: "Utility",
    iconUrl: "",
    iconX2Url: "",
    importUrl: "https://raw.githubusercontent.com/iamtrep/hubitat/refs/heads/main/apps/StartupShutdownMonitor.groovy",
    singleInstance: true
)

preferences {
    page(name: "mainPage")
}


@Field static final List<String> constLocationEvents = [
    "cloudBackup",
    "deviceJoin",
    "lowMemory",
    "manualReboot",
    "manualShutdown",
    "schedulerFailed",
    "severeLoad",
    "sunrise",
    "sunriseSunsetUpdated",
    "sunriseTime",
    "sunset",
    "sunsetTime",
    "systemStart",
    "update",
    "zigbeeOn",
    "zigbeeOff",
    "zigbeeStatus",
    "zwaveCrashed",
    "zwaveStatus"
]

Map mainPage() {
    dynamicPage(name: "mainPage", title: "${app.getLabel()} Setup", install: true, uninstall: true) {
        section("Select Virtual Contact Sensor") {
            input name: "contactSensor", type: "capability.contactSensor", title: "Virtual Contact Sensor", multiple:false, required:true, showFilter:true
            paragraph "<a href='/device/addDevice' target='_blank'>Click here</a> to create a new Virtual Contact Sensor for use with this app"
        }
        section("Settings") {
            input name: "startupDelay", title: "Wait this many seconds after systemStartup event to close the contact sensor", type: "number", defaultValue: 0, range: "0..3600", required: true
        }
        section("Unplanned Restart Notification") {
            input name: "notifyDevices", type: "capability.notification", title: "Notify these devices when the hub starts without a prior shutdown event", multiple: true, required: false
        }
        section("Advanced", hideable: true, hidden: true) {
             input "triggerEventsOpen", "enum", title: "Events to OPEN the device",
                options: constLocationEvents, required: false, multiple: true, defaultValue: DEFAULT_OPEN_EVENTS

             input "triggerEventsClose", "enum", title: "Events to CLOSE the device",
                options: constLocationEvents, required: false, multiple: true, defaultValue: DEFAULT_CLOSE_EVENTS

        }
        section("Logging") {
            input name: "txtEnable", type: "bool", title: "Enable info logging", defaultValue: true
            input name: "debugEnable", type: "bool", title: "Enable debug logging", defaultValue: false, submitOnChange: true
            if (settings.debugEnable) {
                input name: "traceEnable", type: "bool", title: "Enable trace logging", defaultValue: false
            }
        }
    }
}

// runs when the app is first installed
void installed() {
    initialize()
    logDebug "installed()"
}

// runs whenever app preferences are saved (click Done on app config page)
void updated() {
    logDebug "updated()"
    unsubscribe()
    unschedule()
    initialize()
    if (settings.debugEnable || settings.traceEnable) runIn(1800, "logsOff")
}

void initialize() {
    checkVersion(false)
    app.removeSetting("logLevel")
    logDebug "initialize()"
    subscribe(location, "eventHandler")
    servicePendingClose()
    logDebug "${contactSensor?.getDisplayName()} ${contactSensor?.currentValue('contact')}"
}

private void checkVersion(boolean reinit = true) {
    if (state.version == CODE_VERSION) return
    logVer "New version: ${CODE_VERSION} (was: ${state.version})"
    state.version = CODE_VERSION
    if (reinit) runIn(1, "updated")
}

void eventHandler(Event evt) {
    checkVersion()
    logEvt "System event detected: ${evt.name}"
    servicePendingClose()

    // Unset lists fall back to the defaults: an instance whose settings were never saved
    // (or were lost) would otherwise ignore every event.
    if (evt.name in (settings.triggerEventsOpen ?: DEFAULT_OPEN_EVENTS)) {
        cancelPendingClose()
        openContact(evt.descriptionText)
    }

    // Still closed at startup = no shutdown event since the last start.
    if (evt.name == "systemStart" && contactSensor?.currentValue('contact') == 'closed') {
        notifyUnplannedRestart(evt)
    }

    if (evt.name in (settings.triggerEventsClose ?: DEFAULT_CLOSE_EVENTS)) {
        // The startup delay only applies to systemStart -- the rationale (hub
        // is busy coming back up) doesn't generalize to other close events the
        // user might pick (zigbeeOn, sunrise, ...).
        Integer delay = (evt.name == "systemStart") ? ((settings.startupDelay ?: 0) as Integer) : 0
        if (delay > 0) {
            logInfo "${evt.descriptionText} - Deferring close by ${delay}s"
            state.closeDueAt = now() + delay * 1000L
            state.closeMessage = evt.descriptionText
            runIn(delay, "closeContactDelayed", [data: [message: evt.descriptionText]])
        } else {
            closeContact(evt.descriptionText)
        }
    }

    logTrace("Unhandled location event: ${evt.name}")
}

void closeContactDelayed(Map data) {
    checkVersion()
    state.remove("closeDueAt")
    state.remove("closeMessage")
    closeContact((data?.message as String) ?: "delayed close")
}

// A deferred close keeps its due time in state, so a lost runIn (crash mid-handler, or the
// unschedule() in updated()) completes or re-arms on the next location event or initialize().
private void servicePendingClose() {
    Long dueAt = state.closeDueAt as Long
    if (dueAt == null) return
    String message = state.closeMessage as String
    long remainingMs = dueAt - now()
    if (remainingMs <= 0) {
        closeContactDelayed([message: message])
    } else {
        runIn((long) Math.ceil(remainingMs / 1000d), "closeContactDelayed", [data: [message: message]])
    }
}

private void cancelPendingClose() {
    if (state.closeDueAt == null) return
    unschedule("closeContactDelayed")
    state.remove("closeDueAt")
    state.remove("closeMessage")
}

private void notifyUnplannedRestart(Event evt) {
    String message = "${location.name}: unplanned restart (no shutdown event before ${evt.descriptionText})"
    logWarn message
    notifyDevices?.each { it.deviceNotification(message) }
}

void openContact(String message) {
    if (contactSensor?.currentValue('contact') == 'open') {
        logWarn("${message} - ${contactSensor?.getDisplayName()} already open - missed systemStart event?")
    }

    logCmd "${message} - Opening contact"
    contactSensor?.open()
}

void closeContact(String message) {
    if (contactSensor?.currentValue('contact') == 'closed') {
        logWarn("${message} - ${contactSensor?.getDisplayName()} already closed - missed shutdown/reboot event?")
    }

    logCmd "${message} - Closing contact"
    contactSensor?.close()
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

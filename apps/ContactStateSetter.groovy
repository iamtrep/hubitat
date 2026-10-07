// Copyright (c) 2026 PJ
// SPDX-License-Identifier: MIT

/*
 Contact State Setter

 Forces selected contact sensors to "open" or "closed" by injecting a contact
 event onto the device via DeviceWrapper.sendEvent — no radio traffic, no
 physical actuation. Useful for driving automations under test. The selection is
 cleared on Done, so each visit starts with no sensor selected.

 sendEvent here writes directly to the device's event stream, so any app or
 rule subscribed to the sensor's "contact" attribute fires exactly as it would
 for a real open/close.
*/

import groovy.transform.Field

@Field static final String APP_NAME = "Contact State Setter"
@Field static final String CODE_VERSION = "1.0.2"

definition(
    name: APP_NAME,
    namespace: "iamtrep",
    author: "pj",
    description: "Sets selected contact sensors open or closed by injecting a contact event via sendEvent.",
    menu: "Automations",
    category: "Convenience",
    singleInstance: false,
    singleThreaded: true,
    importUrl: "https://raw.githubusercontent.com/iamtrep/hubitat/refs/heads/main/apps/ContactStateSetter.groovy",
    iconUrl: "",
    iconX2Url: "",
    iconX3Url: ""
)

preferences {
    page(name: "mainPage")
}

Map mainPage() {
    dynamicPage(name: "mainPage", title: "${APP_NAME} v${CODE_VERSION}", install: true, uninstall: true) {
        section("Contact sensors") {
            input name: "contacts", type: "capability.contactSensor", title: "Sensors to control",
                  multiple: true, required: false, submitOnChange: true
        }
        section("Set state") {
            input name: "setOpen", type: "button", title: "Set Open"
            input name: "setClosed", type: "button", title: "Set Closed"
            if (settings.contacts) {
                paragraph currentStates()
            }
        }
        section("Options") {
            label title: "App name", required: false
            input name: "txtEnable", type: "bool", title: "Enable info logging", defaultValue: true
            input name: "debugLogging", type: "bool", title: "Enable debug logging", defaultValue: false, submitOnChange: true
            if (settings.debugLogging) {
                input name: "traceEnable", type: "bool", title: "Enable trace logging", defaultValue: false
            }
        }
    }
}

private String currentStates() {
    settings.contacts.collect { dev ->
        "${dev.displayName}: ${dev.currentValue("contact") ?: "—"}"
    }.join("<br>")
}

void installed() {
    initialize()
}

void updated() {
    app.removeSetting("contacts")
    initialize()
    if (settings.debugLogging || settings.traceEnable) runIn(1800, "logsOff")
}

void initialize() {
    checkVersion(false)
    // Stateless: all work happens on button press. Nothing to subscribe or schedule.
}

private void checkVersion(boolean reinit = true) {
    if (state.version == CODE_VERSION) return
    logVer "New version: ${CODE_VERSION} (was: ${state.version})"
    state.version = CODE_VERSION
    if (reinit) runIn(1, "updated")
}

void appButtonHandler(String btn) {
    checkVersion()
    switch (btn) {
        case "setOpen":   setContact("open");   break
        case "setClosed": setContact("closed"); break
        default: logWarn "unknown button '${btn}'"
    }
}

private void setContact(String value) {
    settings.contacts?.each { dev ->
        dev.sendEvent(name: "contact", value: value,
                      descriptionText: "${dev.displayName} contact set to ${value} by ${APP_NAME}")
        logDebug "${dev.displayName} contact -> ${value}"
    }
}

void logsOff() {
    app.updateSetting("debugLogging", [value: "false", type: "bool"])
    app.updateSetting("traceEnable", [value: "false", type: "bool"])
    logWarn "debug and trace logging disabled"
}

// ── Logging (app) ─────────────────────────────────────────────────────
//   ⬇️ Evt  ⬆️ Cmd  🔧 Cfg  🌐 Net  ⏰ Sched  🏷️ Ver  ·  ⚠️ Warn  🛑 Error  🔬 Trace
private String logp(String e) { "${e} ${app.getLabel()}: " }

void logEvt  (String m) { if (settings.debugLogging) log.debug logp('⬇️') + m }
void logCmd  (String m) { if (txtEnable != false) log.info  logp('⬆️') + m }
void logCfg  (String m) { if (txtEnable != false) log.info  logp('🔧') + m }
void logNet  (String m) { if (settings.debugLogging) log.debug logp('🌐') + m }
void logSched(String m) { if (settings.debugLogging) log.debug logp('⏰') + m }
void logVer  (String m) { log.warn  logp('🏷️') + m }

void logWarn (String m) { log.warn  logp('⚠️') + m }
void logError(String m) { log.error logp('🛑') + m }
void logTrace(String m) { if (settings.traceEnable) log.trace logp('🔬') + m }
void logInfo (String m) { if (txtEnable != false) log.info  "${app.getLabel()}: ${m}" }
void logDebug(String m) { if (settings.debugLogging) log.debug "${app.getLabel()}: ${m}" }

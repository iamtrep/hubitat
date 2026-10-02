// Copyright (c) 2025-2026 PJ
// SPDX-License-Identifier: MIT

/*
 Location Event Mapper - Main app

 For creating & grouping Location Event Mapper apps.  These apps set virtual contact sensor states based on location event triggers.
 */
definition(
    name: "Location Event Mapper",
    namespace: "iamtrep",
    author: "pj",
    singleInstance: true,
    description: "TBD",
    menu: "Automations", // new in platform 2.5.0
    category: "Convenience",
    importUrl: "",
    iconUrl: "",
    iconX2Url: ""
)


import groovy.transform.Field
import groovy.transform.CompileStatic

@Field static final String CODE_VERSION = "0.0.2"


preferences {
    page(name: "mainPage")
}

Map mainPage(){
    dynamicPage(name: "mainPage", title: " ", install: true, uninstall: true) {
        section ("Set up or manage Sensor Aggregator instances"){
            app(name: "lemChildApps", appName: "Location Event Mapper Child", namespace: "iamtrep", title: "Create New Location Event Mapper", submitOnChange: true, multiple: true)
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

void installed() {
    initialize()
}

void updated() {
    unsubscribe()
    initialize()
    if (settings.debugEnable || settings.traceEnable) runIn(1800, "logsOff")
}

void initialize() {
    logDebug "there are ${getChildApps().size()} location event mappers : ${getChildApps().collect { it.label } }"
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

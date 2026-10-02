// Copyright (c) 2025-2026 PJ
// SPDX-License-Identifier: MIT

/*
 Sensor Aggregator Main app

 For creating & grouping Sensor Aggregator apps.  These apps aggregate sensor values and save the result to a virtual device
 */
definition(
    name: "Sensor Aggregator",
    namespace: "iamtrep",
    author: "pj",
    singleInstance: true,
    description: "Manage sensor aggregators - apps that aggregate sensor values and save the result to a single virtual device",
    menu: "Automations", // new in platform 2.5.0
    category: "Convenience",
    importUrl: "https://raw.githubusercontent.com/iamtrep/hubitat/refs/heads/main/apps/sensors/SensorAggregator.groovy",
    iconUrl: "",
    iconX2Url: ""
)


import groovy.transform.Field
import groovy.transform.CompileStatic

@Field static final String CODE_VERSION = "0.0.6"


preferences {
    page(name: "mainPage")
}

Map mainPage(){
    dynamicPage(name: "mainPage", title: " ", install: true, uninstall: true) {
        section ("Set up or manage Sensor Aggregator instances"){
            app(name: "saChildApps",
                appName: "Sensor Aggregator Child",
                namespace: "iamtrep",
                title: "Create New Continuous Sensor Aggregator",
                description: "Continuous sensors include humidity, temperature, power, etc.",
                submitOnChange: true,
                multiple: true
            )
            app(name: "saDiscreteChildApps",
                appName: "Sensor Aggregator Discrete Child",
                namespace: "iamtrep",
                title: "Create New Discrete Sensor Aggregator",
                description: "Discrete sensors include switch, contact, motion, etc.",
                submitOnChange: true,
                multiple: true
            )
            app(name: "mfChildApps",
                appName: "Motion Fusion Child",
                namespace: "iamtrep",
                title: "Create New Motion Fusion",
                description: "Combine PIR and mmWave inputs into a single motion output using configurable fusion algorithms",
                submitOnChange: true,
                multiple: true
            )
        }
        section("Logging") {
            input name: "txtEnable", type: "bool", title: "Enable info logging", defaultValue: true
            input name: "debugEnable", type: "bool", title: "Enable debug logging", defaultValue: false, submitOnChange: true
            if (debugEnable) {
                input name: "traceEnable", type: "bool", title: "Enable trace logging", defaultValue: false
            }
        }
    }
}

void installed() {
    initialize()
}

void updated() {
    logDebug "there are ${getChildApps().size()} sensor aggregators : ${getChildApps().collect { it.label } }"
    initialize()
}

void initialize() {
    checkVersion()
    if (debugEnable || traceEnable) runIn(1800, "logsOff")
}

private void checkVersion() {
    if (state.version == CODE_VERSION) return
    logVer "New version: ${CODE_VERSION} (was: ${state.version})"
    state.version = CODE_VERSION
}

void logsOff() {
    app.updateSetting("debugEnable", [value: "false", type: "bool"])
    app.updateSetting("traceEnable", [value: "false", type: "bool"])
    logWarn "debug and trace logging disabled"
}

// ── Logging (app) ─────────────────────────────────────────────────────
//   ⬇️ Evt  ⬆️ Cmd  🔧 Cfg  🌐 Net  ⏰ Sched  🏷️ Ver  ·  ⚠️ Warn  🛑 Error  🔬 Trace
private String logp(String e) { "${e} ${app.getLabel()}: " }

void logEvt  (String m) { if (debugEnable) log.debug logp('⬇️') + m }
void logCmd  (String m) { if (txtEnable != false) log.info  logp('⬆️') + m }
void logCfg  (String m) { if (txtEnable != false) log.info  logp('🔧') + m }
void logNet  (String m) { if (debugEnable) log.debug logp('🌐') + m }
void logSched(String m) { if (debugEnable) log.debug logp('⏰') + m }
void logVer  (String m) { log.warn  logp('🏷️') + m }

void logWarn (String m) { log.warn  logp('⚠️') + m }
void logError(String m) { log.error logp('🛑') + m }
void logTrace(String m) { if (traceEnable) log.trace logp('🔬') + m }
void logInfo (String m) { if (txtEnable != false) log.info  "${app.getLabel()}: ${m}" }
void logDebug(String m) { if (debugEnable) log.debug "${app.getLabel()}: ${m}" }

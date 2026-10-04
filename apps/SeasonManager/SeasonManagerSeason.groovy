// Copyright (c) 2026 PJ
// SPDX-License-Identifier: MIT

/*
 Season Manager Season

 Component device of Season Manager. Publishes the season and the winter credit flag,
 and forwards setSeason and resumeAuto to the app.
*/

import groovy.transform.Field

@Field static final String CODE_VERSION = "0.2.0"

metadata {
    definition(name: "Season Manager Season", namespace: "iamtrep", author: "pj", component: true,
               importUrl: "https://raw.githubusercontent.com/iamtrep/hubitat/main/apps/SeasonManager/SeasonManagerSeason.groovy") {
        capability "Actuator"
        attribute "season", "enum", ["winter", "spring", "summer", "fall"]
        attribute "winterCredit", "enum", ["on", "off"]
        command "setSeason", [[name: "season*", type: "ENUM", constraints: ["winter", "spring", "summer", "fall"]],
                              [name: "holdDays", type: "NUMBER", description: "Days without automatic changes (default 3, 0 = none)"]]
        command "resumeAuto"
    }
    preferences {
        input name: "txtEnable", type: "bool", title: "Enable info logging", defaultValue: true
    }
}

void installed() { logCfg "installed ${CODE_VERSION}" }
void updated() { logCfg "updated" }

void setSeason(String season, holdDays = null) { forward([command: 'setSeason', season: season, holdDays: holdDays]) }
void resumeAuto() { forward([command: 'resumeAuto']) }

private void forward(Map req) {
    Map res = parent.seasonCommand(req) as Map
    if (res?.ok == false) logWarn "${req.command}: ${res.error}"
    else logCmd "${req.command}"
}

void updateStatus(Map attrs) {
    if (state.version != CODE_VERSION) {
        logVer "version ${CODE_VERSION} (was ${state.version})"
        state.version = CODE_VERSION
    }
    attrs.each { String k, v -> if (v != null) sendEvent(name: k, value: v) }
}

// ── Logging ───────────────────────────────────────────────────────────
//   ⬇️ Rx  ⬆️ Cmd  🔧 Cfg  🌐 Net  ⏰ Sched  📦 Ota  🏷️ Ver  ·  ⚠️ Warn  🛑 Error  🔬 Trace
private String logp(String e) { "${e} ${device.displayName}: " }

void logRx   (String m) { if (debugEnable) log.debug logp('⬇️') + m }
void logCmd  (String m) { if (txtEnable != false) log.info  logp('⬆️') + m }
void logCfg  (String m) { if (txtEnable != false) log.info  logp('🔧') + m }
void logNet  (String m) { if (debugEnable) log.debug logp('🌐') + m }
void logSched(String m) { if (debugEnable) log.debug logp('⏰') + m }
void logOta  (String m) { if (txtEnable != false) log.info  logp('📦') + m }
void logVer  (String m) { log.warn  logp('🏷️') + m }

void logWarn (String m) { log.warn  logp('⚠️') + m }
void logError(String m) { log.error logp('🛑') + m }
void logTrace(String m) { if (traceEnable) log.trace logp('🔬') + m }
void logInfo (String m) { if (txtEnable != false) log.info  "${device.displayName}: ${m}" }
void logDebug(String m) { if (debugEnable) log.debug "${device.displayName}: ${m}" }

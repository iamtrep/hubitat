// Copyright (c) 2026 PJ
// SPDX-License-Identifier: MIT

/*
 Mirror Switch

 Keeps a group of on/off devices in lockstep. Whichever member changes state
 drives every other member to match.

 Ping-pong is impossible by construction: a member already at the target value
 is never commanded, so the echo events from commanded devices find everyone
 matching and issue no further commands — the cascade stops on its own.
*/
import com.hubitat.app.DeviceWrapper
import groovy.transform.Field

@Field static final String APP_NAME = "Mirror Switch"
@Field static final String CODE_VERSION = "1.0.1"
@Field static final Integer DEBUG_AUTO_OFF_MINUTES = 30

definition(
    name: APP_NAME,
    namespace: "iamtrep",
    author: "pj",
    description: "Keeps a group of on/off devices in sync; any member changing drives the rest to match.",
    menu: "Automations",
    category: "Convenience",
    singleInstance: false,
    singleThreaded: true,
    importUrl: "https://raw.githubusercontent.com/iamtrep/hubitat/refs/heads/main/apps/MirrorSwitch.groovy",
    iconUrl: "",
    iconX2Url: "",
    iconX3Url: ""
)

preferences {
    page(name: "mainPage")
}

Map mainPage() {
    dynamicPage(name: "mainPage", title: "${APP_NAME} v${CODE_VERSION}", install: true, uninstall: true) {
        section("Mirror group") {
            input name: "switches", type: "capability.switch", title: "Devices to keep in sync",
                  multiple: true, required: true, submitOnChange: true
            if (settings.switches && settings.switches.size() < 2) {
                paragraph "&#9888; Select at least two devices for mirroring to take effect."
            }
        }
        section("Options") {
            label title: "App name", required: false
            input name: "txtEnable", type: "bool", title: "Enable info logging",
                  defaultValue: true
            input name: "debugLogging", type: "bool", title: "Enable debug logging",
                  defaultValue: false, submitOnChange: true
            if (settings.debugLogging) {
                input name: "traceEnable", type: "bool", title: "Enable trace logging",
                      defaultValue: false
                paragraph "Debug logging turns off automatically after ${DEBUG_AUTO_OFF_MINUTES} minutes."
            }
        }
    }
}

void installed() {
    initialize()
}

void updated() {
    unsubscribe()
    unschedule()
    initialize()
}

void initialize() {
    checkVersion(false)
    if (settings.debugLogging || settings.traceEnable) {
        runIn(DEBUG_AUTO_OFF_MINUTES * 60, "logsOff")
    }
    if (!settings.switches || settings.switches.size() < 2) {
        logWarn "fewer than two devices selected; mirroring inactive."
        return
    }
    settings.switches.each { dev ->
        subscribe(dev, "switch", "switchHandler")
    }
    reconcile()
}

private void checkVersion(boolean reinit = true) {
    if (state.version == CODE_VERSION) return
    logVer "New version: ${CODE_VERSION} (was: ${state.version})"
    state.version = CODE_VERSION
    if (reinit) runIn(1, "updated")
}

void logsOff() {
    app.updateSetting("debugLogging", [value: "false", type: "bool"])
    app.updateSetting("traceEnable", [value: "false", type: "bool"])
    logWarn "debug and trace logging disabled"
}

void switchHandler(evt) {
    checkVersion()
    String target = evt.value
    if (target != "on" && target != "off") return
    logEvt "${evt.displayName} -> ${target}; propagating."
    propagate(target, evt.deviceId?.toString())
}

private void propagate(String target, String sourceId) {
    settings.switches?.each { dev ->
        if (dev.id?.toString() == sourceId) return
        if (dev.currentValue("switch") == target) return
        if (target == "on") dev.on() else dev.off()
        logDebug "corrected ${dev.displayName} -> ${target}"
    }
}

private void reconcile() {
    List<DeviceWrapper> members = settings.switches
    if (!members || members.size() < 2) return
    DeviceWrapper mostRecent = null
    Long bestTime = -1L
    members.each { dev ->
        def st = dev.currentState("switch")
        Long t = (st?.date?.time ?: 0L) as Long
        if (t > bestTime) {
            bestTime = t
            mostRecent = dev
        }
    }
    if (mostRecent == null) return
    String target = mostRecent.currentValue("switch")
    if (target != "on" && target != "off") return
    logDebug "reconcile target ${target} from ${mostRecent.displayName}"
    propagate(target, mostRecent.id?.toString())
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

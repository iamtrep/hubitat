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
@Field static final String CODE_VERSION = "1.0.0"
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

def mainPage() {
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
            input name: "debugLogging", type: "bool", title: "Enable debug logging",
                  defaultValue: false, submitOnChange: true
            if (settings.debugLogging) {
                paragraph "Debug logging turns off automatically after ${DEBUG_AUTO_OFF_MINUTES} minutes."
            }
        }
    }
}

def installed() {
    initialize()
}

def updated() {
    unsubscribe()
    unschedule()
    initialize()
}

def initialize() {
    if (!settings.switches || settings.switches.size() < 2) {
        log.warn "${APP_NAME}: fewer than two devices selected; mirroring inactive."
        return
    }
    settings.switches.each { dev ->
        subscribe(dev, "switch", "switchHandler")
    }
    if (settings.debugLogging) {
        runIn(DEBUG_AUTO_OFF_MINUTES * 60, "disableDebugLogging")
    }
    reconcile()
}

def disableDebugLogging() {
    log.info "${APP_NAME}: disabling debug logging."
    app.updateSetting("debugLogging", [value: "false", type: "bool"])
}

void switchHandler(evt) {
    String target = evt.value
    if (target != "on" && target != "off") return
    if (settings.debugLogging) log.debug "${APP_NAME}: ${evt.displayName} -> ${target}; propagating."
    propagate(target, evt.deviceId?.toString())
}

private void propagate(String target, String sourceId) {
    settings.switches?.each { dev ->
        if (dev.id?.toString() == sourceId) return
        if (dev.currentValue("switch") == target) return
        if (target == "on") dev.on() else dev.off()
        if (settings.debugLogging) log.debug "${APP_NAME}: corrected ${dev.displayName} -> ${target}"
    }
}

private void reconcile() {
    def members = settings.switches
    if (!members || members.size() < 2) return
    def mostRecent = null
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
    if (settings.debugLogging) log.debug "${APP_NAME}: reconcile target ${target} from ${mostRecent.displayName}"
    propagate(target, mostRecent.id?.toString())
}

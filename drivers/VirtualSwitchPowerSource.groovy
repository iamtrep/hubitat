// Copyright (c) 2025-2026 PJ
// SPDX-License-Identifier: MIT

/*
 Virtual Switch + PowerSource

 Virtual device that exposes both Switch and PowerSource capabilities with
 synchronized state. Designed for testing the Always-On Switch Monitor app's
 power outage detection via either capability.

 Sync logic:
   on()                      → switch=on,  powerSource=battery
   off()                     → switch=off, powerSource=mains
   setPowerSource("mains")   → switch=off, powerSource=mains
   setPowerSource(other)     → switch=on,  powerSource={value}
 */

import groovy.transform.Field

@Field static final String CODE_VERSION = "0.1.1"

metadata {
    definition(name: 'Virtual Switch + PowerSource', namespace: 'iamtrep', author: 'pj',
               description: 'Virtual device with synced Switch and PowerSource capabilities for testing power outage detection',
               importUrl: 'https://raw.githubusercontent.com/iamtrep/hubitat/refs/heads/main/drivers/VirtualSwitchPowerSource.groovy') {
        capability 'Switch'
        capability 'PowerSource'

        command 'setPowerSource', [[name: 'source', type: 'ENUM', constraints: ['mains', 'battery', 'dc', 'unknown'], description: 'Power source type']]
    }

    preferences {
        input name: 'txtEnable', type: 'bool', title: 'Enable info logging', defaultValue: true
        input name: 'debugEnable', type: 'bool', title: 'Enable debug logging', defaultValue: false, submitOnChange: true
        if (debugEnable) {
            input name: 'traceEnable', type: 'bool', title: 'Enable trace logging', defaultValue: false
        }
    }
}

void installed() {
    sendEvent(name: 'switch', value: 'off', descriptionText: 'Initialized to off')
    sendEvent(name: 'powerSource', value: 'mains', descriptionText: 'Initialized to mains')
    initialize()
}

void updated() {
    initialize()
}

// Shared install/save path. A virtual device has nothing to connect or configure.
void initialize() {
    checkVersion()
    if (debugEnable || traceEnable) runIn(1800, 'logsOff')
}

void deviceTypeUpdated() {
    logDebug "driver change detected"
}

void parse(String description) {
}

void on() {
    checkVersion()
    String descriptionText = "${device.displayName} was turned on"
    sendEvent(name: 'switch', value: 'on', descriptionText: descriptionText)
    sendEvent(name: 'powerSource', value: 'battery', descriptionText: "${device.displayName} power source set to battery")
    if (txtEnable) { logInfo 'was turned on' }
}

void off() {
    checkVersion()
    String descriptionText = "${device.displayName} was turned off"
    sendEvent(name: 'switch', value: 'off', descriptionText: descriptionText)
    sendEvent(name: 'powerSource', value: 'mains', descriptionText: "${device.displayName} power source set to mains")
    if (txtEnable) { logInfo 'was turned off' }
}

void setPowerSource(String source) {
    checkVersion()
    String descriptionText = "${device.displayName} power source set to ${source}"
    sendEvent(name: 'powerSource', value: source, descriptionText: descriptionText)
    String switchValue = (source == 'mains') ? 'off' : 'on'
    sendEvent(name: 'switch', value: switchValue, descriptionText: "${device.displayName} was turned ${switchValue}")
    if (txtEnable) { logInfo "power source set to ${source}" }
}

// Virtual device: nothing to reconfigure, so a push is only recorded.
private void checkVersion() {
    if (state.version == CODE_VERSION) return
    logVer "New version: ${CODE_VERSION} (was: ${state.version})"
    state.version = CODE_VERSION
}

void logsOff() {
    logWarn 'debug and trace logging disabled'
    device.updateSetting('debugEnable', [value: 'false', type: 'bool'])
    device.updateSetting('traceEnable', [value: 'false', type: 'bool'])
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

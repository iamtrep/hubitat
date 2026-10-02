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
metadata {
    definition(name: 'Virtual Switch + PowerSource', namespace: 'iamtrep', author: 'pj',
               description: 'Virtual device with synced Switch and PowerSource capabilities for testing power outage detection') {
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
}

void updated() {
    if (debugEnable || traceEnable) runIn(1800, 'logsOff')
}

void deviceTypeUpdated() {
    logDebug "driver change detected"
}

void parse(String description) {
}

void on() {
    String descriptionText = "${device.displayName} was turned on"
    sendEvent(name: 'switch', value: 'on', descriptionText: descriptionText)
    sendEvent(name: 'powerSource', value: 'battery', descriptionText: "${device.displayName} power source set to battery")
    if (txtEnable) { logInfo 'was turned on' }
}

void off() {
    String descriptionText = "${device.displayName} was turned off"
    sendEvent(name: 'switch', value: 'off', descriptionText: descriptionText)
    sendEvent(name: 'powerSource', value: 'mains', descriptionText: "${device.displayName} power source set to mains")
    if (txtEnable) { logInfo 'was turned off' }
}

void setPowerSource(String source) {
    String descriptionText = "${device.displayName} power source set to ${source}"
    sendEvent(name: 'powerSource', value: source, descriptionText: descriptionText)
    String switchValue = (source == 'mains') ? 'off' : 'on'
    sendEvent(name: 'switch', value: switchValue, descriptionText: "${device.displayName} was turned ${switchValue}")
    if (txtEnable) { logInfo "power source set to ${source}" }
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

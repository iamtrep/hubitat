// Copyright (c) 2026 PJ
// SPDX-License-Identifier: MIT

/*
 * Fujitsu Mini-Split — Child Driver
 *
 * One device per Fujitsu indoor unit.
 *
 * Capability strategy: the standard Thermostat capability is constrained to the
 * canonical Hubitat enums (modes: off/heat/cool/auto[/emergency heat]; fan modes:
 * auto/circulate/on). setSupportedThermostatModes / setSupportedThermostatFanModes
 * narrow that canonical set — they cannot extend it. The Fujitsu-specific values
 * (dry / fan_only modes; quiet/low/medium/high fan speeds) live on a parallel
 * custom surface: fujitsuMode + fanSpeed attributes, setFujitsuMode + setFanSpeed
 * commands. Dashboards and Alexa/Google use the canonical Thermostat surface for
 * the 80% case; automations needing dry/fan_only or specific fan speeds use the
 * custom surface.
 *
 * Receives state via parent.updateState(Map); writes via
 * parent.sendCommand(dni, name, intValue).
 */

import groovy.transform.Field

metadata {
    definition(
        name: "Fujitsu Mini-Split",
        namespace: "iamtrep",
        author: "pj",
        importUrl: "https://raw.githubusercontent.com/iamtrep/hubitat/refs/heads/main/integrations/FGLair/FujitsuMiniSplit.groovy"
    ) {
        capability "Thermostat"
        capability "TemperatureMeasurement"
        capability "Refresh"
        capability "Sensor"
        capability "Actuator"

        attribute "supportedThermostatFanModes", "JSON_OBJECT"
        attribute "supportedThermostatModes",    "JSON_OBJECT"
        attribute "outdoorTemperature",          "number"
        attribute "fujitsuMode",                 "string"
        attribute "fanSpeed",                    "string"
        attribute "errorCode",                   "number"
        attribute "opStatus",                    "number"
        attribute "healthStatus",                "enum", ["online", "offline"]
        attribute "commandStatus",               "enum", ["ok", "failed"]
        attribute "minHeatingSetpoint",          "number"
        attribute "maxHeatingSetpoint",          "number"
        attribute "minCoolingSetpoint",          "number"
        attribute "maxCoolingSetpoint",          "number"

        command "setFujitsuMode", [[name: "mode*", type: "ENUM",
                                    description: "Fujitsu operation mode",
                                    constraints: ["off","heat","cool","auto","dry","fan_only"]]]
        command "setFanSpeed",    [[name: "speed*", type: "ENUM",
                                    description: "Fujitsu fan speed",
                                    constraints: ["auto","quiet","low","medium","high"]]]
    }

    preferences {
        input name: "optimisticUpdates", type: "bool",
              title: "Optimistic attribute updates on write",
              description: "When on, attributes reflect the requested value immediately on command. When off, attributes only update on the next poll cycle (truthful cloud state).",
              defaultValue: true
        input name: "txtEnable",   type: "bool", title: "Enable info logging", defaultValue: true
        input name: "debugEnable", type: "bool", title: "Enable debug logging",           defaultValue: false, submitOnChange: true
        if (debugEnable) {
            input name: "traceEnable", type: "bool", title: "Enable trace logging", defaultValue: false
        }
    }
}

@Field static final String CODE_VERSION = "0.3.0"
// A held low setpoint is dropped if the unit hasn't reported heat by then.
@Field static final long HELD_SETPOINT_MS = 600_000L
// A poll can land before a write reaches the cloud and still report the old value.
// For this long after a write, a polled value that disagrees with it is ignored.
@Field static final long WRITE_SETTLE_MS = 30_000L
@Field static final int DEBUG_LOG_TIMEOUT = 1800

@Field static final List<String> SUPPORTED_STD_MODES = ["\"off\"", "\"heat\"", "\"cool\"", "\"auto\""]
@Field static final List<String> SUPPORTED_STD_FAN_MODES = ["\"auto\"", "\"on\""]
// Setpoint range in °C, per role.
@Field static final Map<String, BigDecimal> SETPOINT_MIN_C = ["heat": 16, "cool": 18]
@Field static final BigDecimal SETPOINT_MAX_C = 30

void installed() { logDebug "installed"; state.version = CODE_VERSION; initialize() }
void updated() {
    logDebug "updated"
    unschedule()
    initialize()
    if (settings.debugEnable || settings.traceEnable) runIn(DEBUG_LOG_TIMEOUT, "logsOff")
}
// Re-seeds the supported-mode lists and setpoint bounds, which a driver swap leaves unset.
void deviceTypeUpdated() { logDebug "driver change detected"; initialize() }
void initialize() {
    logDebug "initialize"
    if (state.version != CODE_VERSION) {
        logVer "new version: ${CODE_VERSION} (was: ${state.version})"
        state.version = CODE_VERSION
    }
    sendEvent(name: "supportedThermostatModes",    value: SUPPORTED_STD_MODES)
    sendEvent(name: "supportedThermostatFanModes", value: SUPPORTED_STD_FAN_MODES)
    emitBounds()
}

void refresh() {
    logDebug "refresh"
    parent?.refreshUnit(device.deviceNetworkId)
}

private void emitBounds() {
    String scale = getTemperatureScale()
    sendEvent(name: 'minHeatingSetpoint', value: convertFromC(SETPOINT_MIN_C.heat), unit: scale)
    sendEvent(name: 'maxHeatingSetpoint', value: convertFromC(SETPOINT_MAX_C), unit: scale)
    sendEvent(name: 'minCoolingSetpoint', value: convertFromC(SETPOINT_MIN_C.cool), unit: scale)
    sendEvent(name: 'maxCoolingSetpoint', value: convertFromC(SETPOINT_MAX_C), unit: scale)
}

private BigDecimal convertFromC(BigDecimal celsius) {
    if (getTemperatureScale() == 'F') {
        return (celsius * 9 / 5 + 32).setScale(1, java.math.RoundingMode.HALF_UP)
    }
    return celsius.setScale(1, java.math.RoundingMode.HALF_UP)
}

// --- Write commands ---

@Field static final Map<String, Integer> OP_MODE_INV = [
    "off": 0, "auto": 2, "cool": 3, "dry": 4, "fan_only": 5, "heat": 6
]
@Field static final Map<String, Integer> FAN_MODE_INV = [
    "quiet": 0, "low": 1, "medium": 2, "high": 3, "auto": 4
]
@Field static final List<String> FUJITSU_ALL_MODES = ["off","heat","cool","auto","dry","fan_only"]
@Field static final List<String> FUJITSU_ALL_FAN_SPEEDS = ["auto","quiet","low","medium","high"]

void setThermostatMode(String mode) {
    if (!(mode in CANONICAL_MODES)) {
        logWarn "setThermostatMode(${mode}): not in canonical set — use setFujitsuMode for dry/fan_only"
        return
    }
    writeMode(mode)
}

void setFujitsuMode(String mode) {
    if (!(mode in FUJITSU_ALL_MODES)) {
        logWarn "setFujitsuMode(${mode}): not supported"
        return
    }
    writeMode(mode)
}

private void writeMode(String mode) {
    Integer code = OP_MODE_INV[mode]
    if (code == null) { logWarn "writeMode(${mode}): no int code"; return }
    if (mode != "heat") state.remove("heldSetpoint")
    String prevMode = device.currentValue("fujitsuMode")
    logCmd "setting operation_mode -> ${mode} (${code})"
    writeToUnit("operation_mode", code)

    // Auto-push the stored mode-specific setpoint when transitioning into heat or cool.
    BigDecimal preset = null
    if (mode == "heat" && prevMode != "heat") {
        preset = device.currentValue("heatingSetpoint") as BigDecimal
    } else if (mode == "cool" && prevMode != "cool") {
        preset = device.currentValue("coolingSetpoint") as BigDecimal
    }
    if (preset != null) {
        logInfo "mode change ${prevMode} -> ${mode}: stored ${mode}ingSetpoint is ${preset}${getTemperatureScale()}"
        pushSetpointToUnit(preset)
    }

    if (!isOptimistic()) return
    BigDecimal sp = preset ?: (device.currentValue("thermostatSetpoint") as BigDecimal)
    emitMode(mode, device.currentValue("temperature") as BigDecimal, sp)
}

private void emitMode(String fujMode, BigDecimal temp, BigDecimal sp) {
    sendEvent(name: "fujitsuMode", value: fujMode,
              descriptionText: "${device} fujitsuMode is ${fujMode}")
    String tMode = THERMOSTAT_MODE_FOR[fujMode]
    sendEvent(name: "thermostatMode", value: tMode,
              descriptionText: "${device} mode is ${tMode}")
    String opState = deriveOperatingState(fujMode, temp, sp,
                                          device.currentValue("thermostatOperatingState") as String)
    sendEvent(name: "thermostatOperatingState", value: opState,
              descriptionText: "${device} operating state is ${opState}")
}

private boolean isOptimistic() {
    return settings.optimisticUpdates == null ? true : (settings.optimisticUpdates as Boolean)
}

void setThermostatFanMode(String fanMode) {
    switch (fanMode) {
        case "auto":      writeFanSpeed("auto"); break
        case "on":        fanOn(); break
        case "circulate": fanCirculate(); break
        default: logWarn "setThermostatFanMode(${fanMode}): not supported — use setFanSpeed for quiet/low/medium/high"
    }
}

void setFanSpeed(String speed) {
    if (!(speed in FUJITSU_ALL_FAN_SPEEDS)) {
        logWarn "setFanSpeed(${speed}): not supported"
        return
    }
    writeFanSpeed(speed)
}

private void writeFanSpeed(String speed) {
    Integer code = FAN_MODE_INV[speed]
    if (code == null) { logWarn "writeFanSpeed(${speed}): no int code"; return }
    logCmd "setting fan_speed -> ${speed} (${code})"
    writeToUnit("fan_speed", code)
    if (!isOptimistic()) return
    emitFanSpeed(speed)
}

// Any fixed speed is a continuously running fan, which the canonical enum calls "on".
private void emitFanSpeed(String speed) {
    sendEvent(name: "fanSpeed", value: speed,
              descriptionText: "${device} fanSpeed is ${speed}")
    String fanMode = speed == "auto" ? "auto" : "on"
    sendEvent(name: "thermostatFanMode", value: fanMode,
              descriptionText: "${device} thermostatFanMode is ${fanMode}")
}

// Callers pass numbers or strings, depending on the app.
void setHeatingSetpoint(temp) { handleSetSetpoint("heat", temp) }
void setCoolingSetpoint(temp) { handleSetSetpoint("cool", temp) }

private void handleSetSetpoint(String role, def raw) {
    BigDecimal t = toDecimal(raw)
    if (t == null) { logWarn "set${role.capitalize()}Setpoint(${raw}): not a number — ignored"; return }
    BigDecimal clamped = clampSetpoint(role, t)
    String attrName = "${role}ingSetpoint"
    sendEvent(name: attrName, value: clamped, unit: getTemperatureScale(),
              descriptionText: "${device} ${attrName} is ${clamped}${getTemperatureScale()}")

    String mode = device.currentValue("fujitsuMode")
    boolean writeToUnit = (mode == role) || (mode in ["auto", "dry", "off", null])
    if (!writeToUnit) {
        logInfo "${attrName} stored as preset; unit currently in ${mode} mode, not pushing to unit"
        return
    }
    pushSetpointToUnit(clamped)
}

private BigDecimal toDecimal(def raw) {
    if (raw == null) return null
    try { return new BigDecimal(raw.toString().trim()) } catch (NumberFormatException e) { return null }
}

private BigDecimal clampSetpoint(String role, BigDecimal t) {
    BigDecimal lo = convertFromC(SETPOINT_MIN_C[role])
    BigDecimal hi = convertFromC(SETPOINT_MAX_C)
    BigDecimal clamped = t
    if (clamped < lo) { logWarn "setpoint ${t} below min ${lo} — clamped"; clamped = lo }
    if (clamped > hi) { logWarn "setpoint ${t} above max ${hi} — clamped"; clamped = hi }
    return clamped
}

private void pushSetpointToUnit(BigDecimal clamped) {
    // Below the cool minimum a setpoint is valid only in heat. Written while the
    // unit is in any other mode, it is a value the unit can't honor, the kind that
    // jams the cloud write queue. Hold it until a poll confirms heat.
    if (clamped < convertFromC(SETPOINT_MIN_C.cool) && state.unitMode != "heat") {
        state.heldSetpoint = [value: clamped, at: now()]
        logInfo "holding setpoint ${clamped}${getTemperatureScale()} until the unit reports heat mode"
        return
    }
    state.remove("heldSetpoint")
    BigDecimal aylaValue = scaleToAylaSetpoint(clamped)
    logCmd "setting adjust_temperature -> ${clamped}${getTemperatureScale()} (raw ${aylaValue})"
    writeToUnit("adjust_temperature", aylaValue.toInteger())
    // thermostatSetpoint is the device-confirmed value — updated only by the next
    // poll, mirroring the built-in Ecobee integration model. heatingSetpoint /
    // coolingSetpoint are user-intent presets and update immediately at the call
    // site, regardless of the optimisticUpdates preference.
}

private void writeToUnit(String property, Integer value) {
    Map recent = (state.recentWrites ?: [:]) as Map
    recent[property] = [value: value, at: now()]
    state.recentWrites = recent
    parent?.sendCommand(device.deviceNetworkId, property, value)
}

// The polled value, or null while it still disagrees with a recent write.
private def settled(String property, Object polled) {
    Map recent = (state.recentWrites ?: [:]) as Map
    Map w = recent[property] as Map
    if (w == null || polled == null) return polled
    if (now() - (w.at as long) < WRITE_SETTLE_MS && (polled as BigDecimal) != (w.value as BigDecimal)) {
        logRx "${property}: poll still reports ${polled}, ${w.value} was just written — ignored"
        return null
    }
    recent.remove(property)
    state.recentWrites = recent
    return polled
}

void auto()           { setThermostatMode("auto") }
void cool()           { setThermostatMode("cool") }
void heat()           { setThermostatMode("heat") }
void off()            { setThermostatMode("off") }
void emergencyHeat()  { logWarn "emergencyHeat() not supported on Fujitsu mini-splits — routing to heat"; setThermostatMode("heat") }
void fanAuto()        { setThermostatFanMode("auto") }
void fanOn()          { logCmd "fanOn() routes to setFanSpeed(\"high\")"; setFanSpeed("high") }
void fanCirculate()   { logWarn "fanCirculate() not a standard Fujitsu fan setting — routing to setFanSpeed(\"low\")"; setFanSpeed("low") }

// --- Inbound state from parent ---

@Field static final Map<Integer, String> OP_MODE = [
    0: "off", 1: null, 2: "auto", 3: "cool", 4: "dry", 5: "fan_only", 6: "heat"
]
@Field static final Map<Integer, String> FAN_MODE = [
    0: "quiet", 1: "low", 2: "medium", 3: "high", 4: "auto"
]
@Field static final List<String> CANONICAL_MODES = ["off", "heat", "cool", "auto"]
// thermostatMode only holds canonical values. Dry runs the compressor on its
// cooling cycle; fan_only runs no compressor at all.
@Field static final Map<String, String> THERMOSTAT_MODE_FOR = [
    "off": "off", "heat": "heat", "cool": "cool", "auto": "auto", "dry": "cool", "fan_only": "off"
]

void updateState(Map data) {
    logTrace "updateState(${data})"
    // A code push doesn't run updated(); the parent calls this every poll.
    if (state.version != CODE_VERSION) initialize()
    def opMode = settled("operation_mode", data.opMode)
    def adjustTemp = settled("adjust_temperature", data.adjustTemp)
    def fanSpeed = settled("fan_speed", data.fanSpeed)
    String fujMode = opMode != null ? OP_MODE[(int) opMode] : null
    BigDecimal temp = data.displayTemp != null ? aylaSensorToScale(data.displayTemp) : null
    BigDecimal sp = adjustTemp != null ? aylaSetpointToScale(adjustTemp) : null
    // Temperature and setpoint go out before the mode and operating state, so a
    // subscriber to thermostatOperatingState reads the values it was derived from.
    if (temp != null) {
        sendEvent(name: "temperature", value: temp, unit: getTemperatureScale(),
                  descriptionText: "${device} temperature is ${temp}${getTemperatureScale()}")
    }
    if (sp != null) {
        sendEvent(name: "thermostatSetpoint", value: sp, unit: getTemperatureScale(),
                  descriptionText: "${device} thermostatSetpoint is ${sp}${getTemperatureScale()}")
        // Mirror to mode-specific slot. Bootstrap empty heat/cool attributes on first observation.
        String modeNow = fujMode ?: device.currentValue("fujitsuMode")
        // A held setpoint is the user's newer intent; don't overwrite it with the unit's old value.
        boolean holding = state.heldSetpoint != null
        if ((modeNow == "heat" && !holding) || device.currentValue("heatingSetpoint") == null) {
            sendEvent(name: "heatingSetpoint", value: sp, unit: getTemperatureScale(),
                      descriptionText: "${device} heatingSetpoint is ${sp}${getTemperatureScale()}")
        }
        if (modeNow == "cool" || device.currentValue("coolingSetpoint") == null) {
            sendEvent(name: "coolingSetpoint", value: sp, unit: getTemperatureScale(),
                      descriptionText: "${device} coolingSetpoint is ${sp}${getTemperatureScale()}")
        }
    }
    if (fujMode != null) {
        state.unitMode = fujMode  // poll-confirmed, unlike the optimistic fujitsuMode attribute
        emitMode(fujMode, temp != null ? temp : device.currentValue("temperature") as BigDecimal,
                 sp != null ? sp : device.currentValue("thermostatSetpoint") as BigDecimal)
    }
    sendHeldSetpoint()
    if (fanSpeed != null) {
        String speed = FAN_MODE[(int) fanSpeed]
        if (speed != null) emitFanSpeed(speed)
    }
    if (data.outdoorTemp != null) {
        BigDecimal ot = aylaSensorToScale(data.outdoorTemp)
        sendEvent(name: "outdoorTemperature", value: ot, unit: getTemperatureScale(),
                  descriptionText: "${device} outdoor temperature is ${ot}${getTemperatureScale()}")
    }
    if (data.errorCode != null) {
        Integer prev = device.currentValue("errorCode") as Integer
        Integer code = data.errorCode as Integer
        sendEvent(name: "errorCode", value: code,
                  descriptionText: "${device} errorCode is ${code}")
        if (prev != null && prev == 0 && code != 0) {
            logWarn "errorCode set to ${code}"
        } else if (prev != null && prev != 0 && code == 0) {
            logInfo "errorCode cleared (was ${prev})"
        }
    }
    if (data.opStatus != null) {
        Integer prev = device.currentValue("opStatus") as Integer
        Integer st = data.opStatus as Integer
        sendEvent(name: "opStatus", value: st,
                  descriptionText: "${device} opStatus is ${st}")
        if (prev != null && prev != st) {
            logInfo "opStatus changed: ${prev} -> ${st}"
        }
    }
    // Device metadata — visible on the device edit page's Data section.
    [modelName: "modelName", firmwareVersion: "firmwareVersion",
     deviceName: "deviceName", commVersion: "commVersion"].each { String key, String dataKey ->
        def v = data[key]
        if (v != null && v.toString() != "" && device.getDataValue(dataKey) != v.toString()) {
            device.updateDataValue(dataKey, v.toString())
        }
    }
}

private void sendHeldSetpoint() {
    Map held = state.heldSetpoint as Map
    if (!held) return
    if (now() - (held.at as long) > HELD_SETPOINT_MS) {
        state.remove("heldSetpoint")
        logInfo "held setpoint ${held.value} not sent: the unit didn't report heat mode"
        return
    }
    // From its own handler: this runs inside the manager's poll callback, and
    // calling back into the manager from there may wait on its singleThreaded lock.
    if (state.unitMode == "heat") runIn(1, "pushHeldSetpoint")
}

void pushHeldSetpoint() {
    Map held = state.heldSetpoint as Map
    if (held && state.unitMode == "heat") pushSetpointToUnit(held.value as BigDecimal)
}

// Called by the parent with the unit's cloud link state, or "offline" when the
// cloud itself is unreachable. The reason goes in the event description.
void updateHealth(String status, String reason = null) {
    String prev = device.currentValue("healthStatus")
    String why = reason ? ": ${reason}" : ""
    sendEvent(name: "healthStatus", value: status,
              descriptionText: "${device} is ${status}${why}")
    if (prev != null && prev != status) {
        if (status == "offline") logWarn "unit is offline${why}"
        else logInfo "unit is back online"
    }
}

// Called by the parent with the outcome of each property write. A failed write
// is not retried; the next poll restores the attributes to the unit's real state.
void commandResult(String property, boolean ok) {
    if (!ok) {
        // The next poll reports the unit's real value.
        Map recent = (state.recentWrites ?: [:]) as Map
        if (recent.remove(property) != null) state.recentWrites = recent
    }
    String status = ok ? "ok" : "failed"
    sendEvent(name: "commandStatus", value: status,
              descriptionText: "${device} ${property} write ${status}")
    if (!ok) logWarn "${property} write failed"
}

// The cloud doesn't report whether the compressor is running (op_status stays 0),
// so this is a thermostat-style estimate: start heating once the room is a band
// below the setpoint, keep heating until it reaches the setpoint; cooling mirrors it.
private String deriveOperatingState(String mode, BigDecimal temp, BigDecimal sp, String prev) {
    if (mode == "fan_only") return "fan only"
    if (!(mode in ["heat", "cool", "auto"]) || temp == null || sp == null) return "idle"
    BigDecimal band = getTemperatureScale() == 'F' ? 0.9 : 0.5
    if (mode != "cool") {
        if (temp <= sp - band) return "heating"
        if (prev == "heating" && temp < sp) return "heating"
    }
    if (mode != "heat") {
        if (temp >= sp + band) return "cooling"
        if (prev == "cooling" && temp > sp) return "cooling"
    }
    return "idle"
}

// Sensor readings (display_temperature, outdoor_temperature) are in hundredths
// of °F on this unit. Empirically verified 2026-05-18 against ambient
// readings: raw 7000 = 70.0°F = 21.1°C (indoor display), raw 5500 = 55.0°F =
// 12.8°C (outdoor, matched ambient). Independent of the hub's temperature
// scale; we always convert to it.
//
// Note: ayla-iot-unofficial (the HA dependency) uses a linear range-map
// formula instead — raw [4000, 9500] -> [-10, +45]°C. Applying that to this
// unit produces values ~8°C off on outdoor. The Python lib's constants are
// presumably for a different model/firmware variant. If a future unit
// reports values outside the [3200, 11200] hundredths-of-°F range (-18°C to
// +49°C, well past mini-split sensor limits), the lib formula may need to
// be re-considered with a per-unit override.
private BigDecimal aylaSensorToScale(def raw) {
    BigDecimal fahrenheit = (raw as BigDecimal) / 100
    if (getTemperatureScale() == 'C') {
        BigDecimal celsius = (fahrenheit - 32) * 5 / 9
        return celsius.setScale(1, java.math.RoundingMode.HALF_UP)
    }
    return fahrenheit.setScale(1, java.math.RoundingMode.HALF_UP)
}

// Setpoint (adjust_temperature): tenths of °C, regardless of the unit's
// display scale. Empirically verified 2026-05-18: raw 180 = 18.0°C.
private BigDecimal aylaSetpointToScale(def raw) {
    BigDecimal celsius = (raw as BigDecimal) / 10
    if (getTemperatureScale() == 'F') {
        return (celsius * 9 / 5 + 32).setScale(1, java.math.RoundingMode.HALF_UP)
    }
    return celsius.setScale(1, java.math.RoundingMode.HALF_UP)
}

// Fujitsu units accept setpoints only in 0.5°C increments. Writing a finer
// value (e.g. 228 = 22.8°C from a 73°F hub setting) causes the Ayla cloud to
// queue the datapoint as pending-to-unit indefinitely, blocking every later
// setpoint write — including from the FGLair app — until the unit's account
// binding is reset or the queue drains (a few minutes after writes stop).
// Snap to the nearest 0.5°C before scaling to tenths.
private BigDecimal scaleToAylaSetpoint(BigDecimal scaleValue) {
    BigDecimal celsius = scaleValue
    if (getTemperatureScale() == 'F') {
        celsius = (scaleValue - 32) * 5 / 9
    }
    return (celsius * 2).setScale(0, java.math.RoundingMode.HALF_UP) * 5
}

void logsOff() {
    logWarn "debug and trace logging disabled"
    device.updateSetting("debugEnable", [value: "false", type: "bool"])
    device.updateSetting("traceEnable", [value: "false", type: "bool"])
}

// ── Logging ───────────────────────────────────────────────────────────
//   ⬇️ Rx  ⬆️ Cmd  🔧 Cfg  🌐 Net  ⏰ Sched  📦 Ota  🏷️ Ver  ·  ⚠️ Warn  🛑 Error  🔬 Trace
private String logp(String e) { "${e} ${device.displayName}: " }

void logRx   (String m) { if (settings.debugEnable) log.debug logp('⬇️') + m }
void logCmd  (String m) { if (txtEnable != false) log.info  logp('⬆️') + m }
void logCfg  (String m) { if (txtEnable != false) log.info  logp('🔧') + m }
void logNet  (String m) { if (settings.debugEnable) log.debug logp('🌐') + m }
void logSched(String m) { if (settings.debugEnable) log.debug logp('⏰') + m }
void logOta  (String m) { if (txtEnable != false) log.info  logp('📦') + m }
void logVer  (String m) { log.warn  logp('🏷️') + m }

void logWarn (String m) { log.warn  logp('⚠️') + m }
void logError(String m) { log.error logp('🛑') + m }
void logTrace(String m) { if (settings.traceEnable) log.trace logp('🔬') + m }
void logInfo (String m) { if (txtEnable != false) log.info  "${device.displayName}: ${m}" }
void logDebug(String m) { if (settings.debugEnable) log.debug "${device.displayName}: ${m}" }

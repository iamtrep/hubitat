// Copyright (c) 2025-2026 PJ
// SPDX-License-Identifier: MIT

/*
 * Blink Camera — Child Driver
 *
 * One device per Blink camera (regardless of variant: standard, mini/owl,
 * doorbell, floodlight). For MVP all variants share this driver; per-variant
 * features (doorbell ring, floodlight light, owl-specific motion-enable) are
 * not exposed yet — the cameraType data value lets the parent app dispatch.
 *
 * on()/off() = enable/disable motion detection. Only standard cameras
 * (cameraType=camera) currently support motion-enable through this path; the
 * parent app logs a warning for other variants.
 */

import groovy.transform.Field

@Field static final String CODE_VERSION = "0.2.1"

metadata {
    definition(
        name: "Blink Camera",
        namespace: "iamtrep",
        author: "pj",
        description: "Blink camera: motion sensor, per-camera motion-enable, battery, temperature, clip metadata, diagnostics.",
        importUrl: "https://raw.githubusercontent.com/iamtrep/hubitat/refs/heads/main/integrations/Blink/BlinkCamera.groovy"
    ) {
        capability "MotionSensor"
        capability "Switch"
        capability "Battery"
        capability "TemperatureMeasurement"
        capability "Refresh"

        attribute "lastThumbnailUrl", "string"
        attribute "lastUpdated", "string"
        attribute "lastClipUrl", "string"
        attribute "lastClipTime", "string"
        attribute "wifiSignal", "number"
        attribute "lfrSignal", "number"
        attribute "batteryBars", "number"
        attribute "online", "string"
        attribute "healthStatus", "enum", ["online", "offline"]
        attribute "firmwareVersion", "string"
        attribute "batteryState", "string"
        attribute "batteryVoltage", "number"
        attribute "acPower", "string"

        command "snapThumbnail"
        command "recordClip"
    }
}

@Field static final int DEBUG_LOG_TIMEOUT = 1800

// Battery: two lithium AA cells (Energizer L91), voltage per cell as Blink reports it under load.
// Linear from 1.30 V (0 %) to 1.75 V (100 %): fresh cells read 1.69-1.72 V, and Blink flagged a
// camera low at 1.26 V while calling 1.36 V ok (eight cameras, 2026-11-21). The datasheet's
// 1.4-1.8 V range would put working cameras near 0 %. More knots can be added as data comes in.
@Field static final double[] BATTERY_CURVE_V   = [1.30d, 1.75d] as double[]
@Field static final double[] BATTERY_CURVE_PCT = [ 0.0d, 100.0d] as double[]
// Lithium voltage is flat and sags in the cold, so the smoothed voltage never rises except on a
// jump of BATTERY_BIG_JUMP_V (new cells); drops are averaged in (EMA, about 5 daily reads to converge).
@Field static final double BATTERY_EMA_ALPHA = 0.30d
@Field static final double BATTERY_BIG_JUMP_V = 0.15d
// While Blink reports the battery low, the percentage is held at or below this.
@Field static final int BATTERY_LOW_MAX_PCT = 10

preferences {
    section("Logging") {
        input name: "txtEnable", type: "bool", title: "Enable info logging", defaultValue: true
        input name: "debugEnable", type: "bool", title: "Enable debug logging", defaultValue: false, submitOnChange: true
        if (debugEnable) {
            input name: "traceEnable", type: "bool", title: "Enable trace logging", defaultValue: false
        }
    }
}

void installed() {
    logDebug "installed"
}

void deviceTypeUpdated() {
    logDebug "driver change detected"
}

void updated() {
    if (debugEnable || traceEnable) runIn(DEBUG_LOG_TIMEOUT, "turnOffDebugLogging")
}

void on() {
    String cameraId = device.getDataValue("cameraId")
    if (!cameraId) { logError "no cameraId on this device"; return }
    parent.enableMotion(cameraId)
}

void off() {
    String cameraId = device.getDataValue("cameraId")
    if (!cameraId) { logError "no cameraId on this device"; return }
    parent.disableMotion(cameraId)
}

void refresh() {
    String cameraId = device.getDataValue("cameraId")
    if (cameraId) parent.refreshCamera(cameraId)
}

void snapThumbnail() {
    String cameraId = device.getDataValue("cameraId")
    if (cameraId) parent.snapThumbnail(cameraId)
}

void recordClip() {
    String cameraId = device.getDataValue("cameraId")
    if (cameraId) parent.recordClip(cameraId)
}

// Parent-fed child: nothing to reconfigure, so a push is only recorded.
private void checkVersion() {
    if (state.version == CODE_VERSION) return
    logVer "New version: ${CODE_VERSION} (was: ${state.version})"
    state.version = CODE_VERSION
}

// Called by the parent after each poll: offline while the Blink cloud is
// unreachable or Blink reports the device offline. The reason goes in the event description.
void updateHealth(String status, String reason = null) {
    String prev = device.currentValue("healthStatus")
    String why = reason ? ": ${reason}" : ""
    sendEvent(name: "healthStatus", value: status, descriptionText: "${device.displayName} is ${status}${why}")
    if (prev != null && prev != status) {
        if (status == "offline") logWarn "offline${why}"
        else logInfo "back online"
    }
}

private void publishBattery(double voltage, boolean blinkSaysLow) {
    if (voltage <= 0.0d) return
    double smoothed = smoothBatteryVoltage(voltage)
    int pct = batteryPctFromVoltage(smoothed, BATTERY_CURVE_V, BATTERY_CURVE_PCT)
    if (blinkSaysLow) pct = Math.min(pct, BATTERY_LOW_MAX_PCT)
    sendEvent(name: "batteryVoltage", value: voltage, unit: "V", descriptionText: "Battery voltage is ${voltage} V")
    sendEvent(name: "battery", value: pct, unit: "%", descriptionText: "Battery is ${pct}%")
}

// Returns the new smoothed voltage (see BATTERY_EMA_ALPHA); a jump of BATTERY_BIG_JUMP_V means new cells.
private double smoothBatteryVoltage(double voltage) {
    Double prev = (state.smoothedBatteryVoltage instanceof Number) ? ((Number) state.smoothedBatteryVoltage).doubleValue() : null
    double smoothed
    if (prev == null) {
        smoothed = voltage
    } else if (voltage - prev >= BATTERY_BIG_JUMP_V) {
        smoothed = voltage
        logInfo "battery replacement detected: ${String.format('%.2f', prev)} V → ${String.format('%.2f', voltage)} V"
    } else if (voltage < prev) {
        smoothed = BATTERY_EMA_ALPHA * voltage + (1.0d - BATTERY_EMA_ALPHA) * prev
    } else {
        smoothed = prev
    }
    state.smoothedBatteryVoltage = smoothed
    logTrace "battery: v=${voltage} V smoothed=${String.format('%.3f', smoothed)} V"
    return smoothed
}

// Piecewise linear over the curve knots, clamped at the ends.
private static int batteryPctFromVoltage(double voltage, double[] curveV, double[] curvePct) {
    int n = curveV.length
    if (voltage <= curveV[0]) return 0
    if (voltage >= curveV[n - 1]) return 100
    for (int i = 1; i < n; i++) {
        if (voltage <= curveV[i]) {
            double pct = curvePct[i - 1] + (voltage - curveV[i - 1]) * (curvePct[i] - curvePct[i - 1]) / (curveV[i] - curveV[i - 1])
            return (int) Math.round(pct)
        }
    }
    return 100
}

// Called by the parent on each poll with the latest camera state.
void handleCameraUpdate(Map data) {
    checkVersion()
    if (!data) return
    logTrace "handleCameraUpdate: ${data}"

    if (data.motion != null) {
        String motion = data.motion as String
        sendEvent(name: "motion", value: motion, descriptionText: "Motion is ${motion}")
        if (motion == "active") logInfo "motion detected"
    }
    if (data.containsKey("motionEnabled")) {
        boolean enabled = data.motionEnabled as boolean
        sendEvent(name: "switch", value: enabled ? "on" : "off",
                  descriptionText: "Motion detection ${enabled ? 'enabled' : 'disabled'}")
    }
    if (data.batteryState) {
        sendEvent(name: "batteryState", value: data.batteryState as String)
    }
    if (data.batteryVoltage instanceof Number) {
        String batState = (data.batteryState ?: device.currentValue("batteryState")) as String
        publishBattery(((Number) data.batteryVoltage).doubleValue() / 100.0d, "low".equalsIgnoreCase(batState))
    }
    if (data.temperature != null) {
        Number raw = data.temperature as Number
        String temp = convertTemperatureIfNeeded(raw, "F", 1)
        String unit = "°${location.temperatureScale}"
        sendEvent(name: "temperature", value: temp, unit: unit,
                  descriptionText: "Temperature is ${temp}${unit}")
    }
    if (data.wifiSignal != null) {
        sendEvent(name: "wifiSignal", value: data.wifiSignal as int, unit: "dBm")
    }
    if (data.lfrSignal != null) {
        sendEvent(name: "lfrSignal", value: data.lfrSignal as int)
    }
    if (data.batteryBars != null) {
        sendEvent(name: "batteryBars", value: data.batteryBars as int)
    }
    if (data.online) {
        sendEvent(name: "online", value: data.online as String)
    }
    if (data.firmwareVersion) {
        sendEvent(name: "firmwareVersion", value: data.firmwareVersion as String)
    }
    if (data.acPower != null) {
        sendEvent(name: "acPower", value: data.acPower as String)
    }
    if (data.lastThumbnailUrl) {
        sendEvent(name: "lastThumbnailUrl", value: data.lastThumbnailUrl as String)
    }
    if (data.lastUpdated) {
        sendEvent(name: "lastUpdated", value: data.lastUpdated as String)
    }
    if (data.lastClipUrl) {
        sendEvent(name: "lastClipUrl", value: data.lastClipUrl as String)
    }
    if (data.lastClipTime) {
        sendEvent(name: "lastClipTime", value: data.lastClipTime as String)
    }
}

void turnOffDebugLogging() {
    logWarn "debug logging disabled"
    device.updateSetting("debugEnable", [value: "false", type: "bool"])
    device.updateSetting("traceEnable", [value: "false", type: "bool"])
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

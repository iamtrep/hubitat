// Copyright (c) 2022 Andrew Davison           (BirdsLikeWires, GPL-3.0)
// Copyright (c)      veeceeoh                 (check-in decoder, Apache-2.0)
// Copyright (c) 2022-2026 PJ                  (local modifications, monolithic build)
// SPDX-License-Identifier: GPL-3.0-only

/*
 *  Aqara Contact Sensor MCCGQ11LM Driver (monolithic build)
 *
 *  Sibling of the Aqara Weather Sensor WSDCGQ11LM driver in this repository
 *  (drivers/aqara/Aqara_WSDCGQ11LM.groovy), itself a derivative of Andrew
 *  Davison's BirdsLikeWires Xiaomi Aqara Temperature and Humidity Sensor
 *  driver (GPL-3.0):
 *    https://github.com/birdslikewires/hubitat
 *
 *  The check-in payload decoder (reverseHexString and the FF01 TLV walker in
 *  parseCheckin) traces to veeceeoh's Xiaomi/Aqara Hubitat drivers (Apache-2.0):
 *    https://github.com/veeceeoh/xiaomi-hubitat
 *  Inline `// Adapted from ...` attribution comments are preserved where
 *  applicable.
 *
 *  Licensed under GPL-3.0-only (combined-work license, inherited from the
 *  BirdsLikeWires upstream). This per-file notice overrides the
 *  repo's MIT default. Full license texts:
 *    GPL-3.0:    https://www.gnu.org/licenses/gpl-3.0.html
 *    Apache-2.0: https://www.apache.org/licenses/LICENSE-2.0
 */


metadata {
    definition (
        name: "Aqara Contact Sensor MCCGQ11LM",
        description: "Aqara Zigbee Door/Window Contact Sensor Driver - MCCGQ11LM",
        namespace: "iamtrep",
        author: "pj",
        singleThreaded: true,
        importUrl: "https://raw.githubusercontent.com/iamtrep/hubitat/main/drivers/aqara/Aqara_MCCGQ11LM.groovy"
    ) {
        capability "Battery"
        capability "Configuration"
        attribute "healthStatus", "enum", ["online", "offline"]
        capability "Sensor"
        capability "ContactSensor"
        capability "PushableButton"

        attribute "batteryVoltage", "number"
        attribute "notPresentCounter", "number"
        attribute "restoredCounter", "number"

        command "resetMeshCounters"
        command "setBatteryReplacementDate"

        fingerprint profileId: "0104", inClusters: "0000,0003,FFFF,0019", outClusters: "0000,0004,0005,0006,0008,0019", manufacturer: "LUMI", model: "lumi.sensor_magnet.aq2", deviceJoinName: "MCCGQ11LM", application: "03"
    }

    preferences {
        input name: "txtEnable",   type: "bool", title: "Enable descriptionText logging", defaultValue: true
        input name: "debugEnable", type: "bool", title: "Enable debug logging",           defaultValue: false, submitOnChange: true
        if (debugEnable) {
            input name: "traceEnable", type: "bool", title: "Enable trace logging",       defaultValue: false
        }
    }
}

import groovy.transform.CompileStatic
import groovy.transform.Field
import java.math.RoundingMode

@Field static final String CODE_VERSION = "1.0.0"

@Field static final int REPORT_INTERVAL_MINUTES = 60
@Field static final int CHECK_EVERY_MINUTES = 10

// CR1632 cell, Z2M voltageToPercentage {min:2850, max:3000} — linear, clamped.
@Field static final double[] constBatteryCurveV   = [2.85d, 3.00d] as double[]
@Field static final double[] constBatteryCurvePct = [ 0.0d, 100.0d] as double[]

// Monotonic EMA on battery voltage. The smoothed value never rises (cells discharge,
// they don't charge) except on a big jump (>= constBatteryBigJumpV) which we treat as a
// battery replacement and snap the smoothed value to the new raw voltage.
@Field static final double constBatteryEmaAlpha = 0.30d
@Field static final double constBatteryBigJumpV = 0.15d

// checkHealth() treats the device as offline if its last message is older
// than 2 report intervals + a 20-minute slack window (covers one missed
// report plus jitter). After a hub reboot we wait HUB_REBOOT_ALLOWANCE_MINUTES
// for the device to re-announce before declaring it offline.
@Field static final int HEALTH_TIMEOUT_SLACK_MINUTES = 20
@Field static final int HUB_REBOOT_ALLOWANCE_MINUTES = 20

@Field static final Random RANDOM = new Random()


// ─── Lifecycle ─────────────────────────────────────────────────────────────

void installed() {
    // Runs once at pairing/install. Route through initialize() so install
    // and updated paths converge.
    logInfo "Installed"
    state.clear()
    initialize()
}

void initialize() {
    // Idempotent setup — entered from installed(), updated(), and runInMillis on
    // version-change. Does NOT issue device-side Zigbee reporting (that's configure()).

    unschedule()

    // Seed to install/upgrade time so the normal "overdue" grace window
    // applies cleanly even if the device has never spoken — without it,
    // checkHealth() can never flip offline.
    if (!state.lastMessageMillis) state.lastMessageMillis = new Date().time

    // Counters survive code pushes — only seed them when they don't already exist.
    if (device.currentValue("notPresentCounter") == null) sendEvent(name: "notPresentCounter", value: 0, isStateChange: false)
    if (device.currentValue("restoredCounter")  == null) sendEvent(name: "restoredCounter",  value: 0, isStateChange: false)
    if (device.currentValue("healthStatus") != "online") sendEvent(name: "healthStatus", value: "online", isStateChange: false)

    // Schedule health checking with random jitter so multiple devices don't stampede.
    int randomSixty = RANDOM.nextInt(60)
    schedule("${randomSixty} 0/${CHECK_EVERY_MINUTES} * * * ? *", "checkHealth")

    updateDataValue("driver", CODE_VERSION)
    sendEvent(name: "numberOfButtons", value: 1, isStateChange: false)

    state.remove("reconfigurePending")

    logInfo "Initialized."
}

void updated() {
    // Runs when preferences are saved. Re-converge, then arm log-off.
    logInfo "Preferences Updated"
    logInfo "Info Logging:  ${txtEnable}"
    logInfo "Debug Logging: ${debugEnable}"
    logInfo "Trace Logging: ${traceEnable}"

    initialize()
    if (debugEnable || traceEnable) runIn(1800, "logsOff")
}

void deviceTypeUpdated() {
    logDebug "driver change detected"
    configure()
}

void configure() {
    // Required by the Configuration capability. MCCGQ11LM sets up its own
    // reporting at pairing time — no zigbee.configureReporting needed here.
    logInfo "Configuring."
    initialize()
}

void runVersionReconfigure() {
    // runInMillis target — lets parse() return immediately so the reconfigure
    // runs on a fresh dispatch instead of inside the inbound frame's handler.
    logWarn "Driver upgraded from ${getDeviceDataByName('driver')} to ${CODE_VERSION}, reconfiguring."
    initialize()
}

// ─── Capability commands ───────────────────────────────────────────────────

void push(Integer buttonId) {
    sendEvent(name: "pushed", value: buttonId, isStateChange: true)
}

void resetMeshCounters() {
    sendEvent(name: "notPresentCounter", value: 0)
    sendEvent(name: "restoredCounter", value: 0)
    logInfo("Mesh counters reset")
}

void setBatteryReplacementDate(Date date = null) {
    if (date == null) date = new Date()
    String dateStr = date.format('yyyy-MM-dd')
    device.updateDataValue("batteryReplacementDate", dateStr)
    logInfo("Battery replacement date set to ${dateStr}")
}

// ─── Zigbee command primitives ─────────────────────────────────────────────

private void sendZigbeeCommands(List<String> cmds) {
    // sendHubCommand dispatches immediately, unlike returning a List from a
    // command handler (which the platform queues and flushes later).

    logTrace("sendZigbeeCommands received : ${cmds}")
    sendHubCommand(new hubitat.device.HubMultiAction(cmds, hubitat.device.Protocol.ZIGBEE))
}

// ─── Pure-computation utilities ────────────────────────────────────────────

@CompileStatic
private String reverseHexString(String hexString) {
    // Byte-swap a big/little-endian hex string (even-length, byte pairs).
    StringBuilder reversed = new StringBuilder(hexString.length())
    for (int i = hexString.length(); i > 0; i -= 2) {
        reversed.append(hexString.substring(i - 2, i))
    }
    return reversed.toString()
}

// Decode a ZCL Character String hex payload to ASCII text. Skips non-printable
// bytes — including the leading 1-byte length prefix that ZCL prepends to
// character strings — so the result is the readable content only.
@CompileStatic
private String hexToText(String hex) {
    if (!hex) return ""
    // Char-string attrs sometimes arrive already decoded (e.g. "lumi.sensor_magnet.aq2")
    // and other times as length-prefixed hex — return the input untouched if
    // it isn't pure hex.
    if (!(hex ==~ /[0-9a-fA-F]+/)) return hex
    StringBuilder out = new StringBuilder()
    for (int i = 0; i + 1 < hex.length(); i += 2) {
        int c = Integer.parseInt(hex.substring(i, i + 2), 16)
        if (c >= 0x20 && c < 0x7F) out.append((char) c)
    }
    return out.toString()
}

// Type-aware integer parse for Xiaomi check-in TLV payloads.
// The dataPayload hex string has already been byte-reversed to big-endian.
// ZCL unsigned types (0x20–0x27): parse as unsigned.
// ZCL signed types   (0x28–0x2F): parse as signed (two's complement).
@CompileStatic
private long parseCheckinInt(String dataPayload, int dataType) {
    long raw = Long.parseLong(dataPayload, 16)
    // Signed types: 0x28 (INT8), 0x29 (INT16), 0x2A (INT24), 0x2B (INT32), ...
    if (dataType >= 0x28 && dataType <= 0x2F) {
        int bits = dataPayload.length() * 4
        if (raw >= (1L << (bits - 1))) {
            raw -= (1L << bits)
        }
    }
    return raw
}

@CompileStatic
private static int batteryPctFromVoltage(double voltage, double[] curveV, double[] curvePct) {
    int n = curveV.length
    if (voltage <= curveV[0]) return 0
    if (voltage >= curveV[n - 1]) return 100
    for (int i = 1; i < n; i++) {
        if (voltage <= curveV[i]) {
            double vLo = curveV[i - 1]
            double vHi = curveV[i]
            double pLo = curvePct[i - 1]
            double pHi = curvePct[i]
            double pct = pLo + (voltage - vLo) * (pHi - pLo) / (vHi - vLo)
            return (int) Math.round(pct)
        }
    }
    return 100
}

// ─── Logging ───────────────────────────────────────────────────────────────

private void logTrace(String message) {
    if (debugEnable && traceEnable) log.trace "${device.displayName}: ${message}"
}

private void logDebug(String message) {
    if (debugEnable) log.debug "${device.displayName}: ${message}"
}

private void logInfo(String message) {
    if (txtEnable) log.info "${device.displayName}: ${message}"
}

private void logWarn(String message) {
    log.warn "${device.displayName}: ${message}"
}

private void logError(String message) {
    log.error "${device.displayName}: ${message}"
}

void logsOff() {
    logWarn("Auto-disabling debug + trace logging")
    device.updateSetting("debugEnable", [value: "false", type: "bool"])
    device.updateSetting("traceEnable", [value: "false", type: "bool"])
}

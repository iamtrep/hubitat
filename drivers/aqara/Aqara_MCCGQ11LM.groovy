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

// ─── Health monitoring ─────────────────────────────────────────────────────

void checkHealth() {
    long millisElapsed = new Date().time - state.lastMessageMillis
    long timeoutMillis = (REPORT_INTERVAL_MINUTES * 2 + HEALTH_TIMEOUT_SLACK_MINUTES) * 60000L
    long secondsElapsed = millisElapsed / 1000
    long hubUptime = location.hub.uptime

    if (millisElapsed <= timeoutMillis) {
        // Device is reporting on schedule. updateHealthStatus() in parse() has
        // already flipped healthStatus to "online", so nothing to do here.
        logDebug("Health : Last message ${secondsElapsed} seconds ago.")
        logTrace("checkHealth() : elapsed=${millisElapsed}ms, timeout=${timeoutMillis}ms")
        return
    }

    if (hubUptime <= HUB_REBOOT_ALLOWANCE_MINUTES * 60) {
        logDebug("Health : Ignoring overdue reports for ${HUB_REBOOT_ALLOWANCE_MINUTES} minutes after hub reboot (uptime ${hubUptime}s).")
        return
    }

    if (device.currentValue("healthStatus") != "offline") {
        sendEvent(name: "healthStatus", value: "offline")
        int npc = (device.currentValue("notPresentCounter") ?: 0) + 1
        sendEvent(name: "notPresentCounter", value: npc)
    }
    logWarn("Health : Offline. Last message ${secondsElapsed} seconds ago.")
    logTrace("checkHealth() : elapsed=${millisElapsed}ms, timeout=${timeoutMillis}ms")
}

private void updateHealthStatus() {
    long millisNow = new Date().time
    state.lastMessageMillis = millisNow
    if (device.currentValue("healthStatus") != "online") {
        sendEvent(name: "healthStatus", value: "online")
        int rc = (device.currentValue("restoredCounter") ?: 0) + 1
        sendEvent(name: "restoredCounter", value: rc)
        logInfo("Health : Online (${rc} total recoveries)")
    }
}

// ─── Zigbee message pipeline ───────────────────────────────────────────────

void parse(String description) {
    updateHealthStatus()
    runVersionCheck()

    // --- Xiaomi check-in (cluster 0x0000 attr 0xFF01) is delivered in a
    // non-ZCL description format. Slice it and hand off to the check-in decoder.
    if (description?.contains("attrId: FF01")) {
        Map xiaomiMap = description.split(', ').collectEntries { String entry ->
            String[] pair = entry.split(': ')
            [(pair.first()): pair.last()]
        }
        logDebug "Parse (Xiaomi check-in): ${xiaomiMap}"
        parseCheckin(xiaomiMap)

        Date now = new Date()
        String updateTime = now.toLocaleString()
        state.lastCheckinMillis = now.time
        state.lastCheckin = updateTime
        state.lastUpdate  = updateTime
        return
    }

    // --- Standard ZCL paths
    Map descMap = zigbee.parseDescriptionAsMap(description)
    if (!descMap) {
        logError "Parse : Failed to interpret description: ${description}"
        return
    }

    logDebug "Parse: ${descMap}"

    // ZDO command (profile 0x0000) — bind responses, mgmt responses, etc.
    if (descMap.profileId == "0000") {
        logTrace "Unhandled ZDO command: cluster=${descMap.clusterId} command=${descMap.command} value=${descMap.value} data=${descMap.data}"
        return
    }

    // ZHA global command (profile 0x0104, no attrId) — Configure Reporting Response etc.
    if (descMap.profileId == "0104" && descMap.attrId == null) {
        logTrace "Unhandled ZHA global command: cluster=${descMap.clusterId} command=${descMap.command} value=${descMap.value} data=${descMap.data}"
        return
    }

    // Attribute report — primary + every entry in additionalAttrs.
    if (descMap.attrId != null) {
        parseAttributeReport(descMap)
        descMap.additionalAttrs?.each { Map extra ->
            parseAttributeReport(descMap + extra)
        }
        state.lastUpdate = new Date().toLocaleString()
        return
    }

    logTrace "Unhandled message: ${descMap}"
}

private void runVersionCheck() {
    // Auto-reconfigure after a publishCode push (which doesn't fire updated()
    // on the receiving hub). Guarded so a burst of frames only schedules once.
    if (state.reconfigurePending) return
    if (getDeviceDataByName('driver') != CODE_VERSION) {
        state.reconfigurePending = true
        runInMillis(100, "runVersionReconfigure")
    }
}

/*
 * Xiaomi/Aqara 0xFF01 check-in payload (cluster 0x0000 attribute 0xFF01)
 * ─────────────────────────────────────────────────────────────────────
 * The hex value carried by the FF01 attribute is a length-prefixed sequence
 * of TLV records:
 *
 *     [tag][type][value(little-endian)][tag][type][value]...
 *      1B   1B    DataType.getLength(type) bytes
 *
 *   NOTE: unlike WSDCGQ11LM's FF01 payload, MCCGQ11LM's raw value carries no
 *   leading length-count byte — the TLV records start at the first hex
 *   character. Confirmed empirically: the captured check-in string
 *   0121170C0328230421A81305217C00062404000300000A210000641000 only decodes
 *   to the expected battery/chip/power-outage/parentDNI/contact values (and
 *   consumes exactly to end-of-string) when the walker starts at position 0.
 *
 *   • tag     — 1-byte Xiaomi-proprietary attribute id (see table below).
 *   • type    — 1-byte ZCL data type; DataType.getLength() returns the value
 *               width in bytes (null for variable-length, which we abort on).
 *   • value   — little-endian; reverseHexString() flips to big-endian for parse.
 *
 * Two delivery paths bundle this payload on MCCGQ11LM:
 *   - Hourly check-in: FF01 is the *primary* attribute (cluster 0000, attrId
 *     FF01) and arrives via parse()'s non-ZCL string branch -> parseCheckin().
 *   - Button press: FF01 rides as an *additionalAttr* alongside a primary
 *     0x0005 (ModelIdentifier) mfr-specific report -> parseBasic() routes it
 *     to parseCheckinFromMap(), which normalizes into the same parseCheckin().
 *
 * Tag table for lumi.sensor_magnet.aq2 (MCCGQ11LM). Tags 0x64+ are reused
 * across the lumi.* family for different sensors — do not copy this table
 * verbatim into a sibling driver; cross-check Z2M's lumi.ts
 * numericAttributes2Payload first.
 *
 *   0x01  battery voltage (mV, UINT16)
 *   0x03  device chip temperature (°C, INT8; internal NCP, not the sensor)
 *   0x04  unknown (present in every payload — purpose undocumented)
 *   0x05  power_outage_count (UINT16) — community drivers mislabel as RSSI dB
 *   0x06  Xiaomi proprietary cumulative counter (UINT40, model-specific) —
 *         community drivers mislabel as LQI
 *   0x07–0x09, 0x0B–0x0C  unknown
 *   0x0A  Zigbee parent DNI (network identifier)
 *   0x64  contact state (0 = closed, 1 = open)
 *
 * Authoritative reference for the corrected 0x05 / 0x06 semantics:
 *   zigbee-herdsman-converters/src/lib/lumi.ts (numericAttributes2Payload).
 */
private void parseCheckin(Map map) {
    String hexString = map.value
    int strLength = hexString.size()

    logInfo("Check-in message.")
    logDebug("parseCheckin : ${strLength}-char payload")

    if (strLength <= 20) {
        logDebug("parseCheckin : payload too short to carry sensor data")
        return
    }

    int strPosition = 0  // No length-prefix byte on this device's FF01 payload — see doc comment above.

    while (strPosition < strLength) {
        int dataTag  = Integer.parseInt(hexString.substring(strPosition,     strPosition + 2), 16)
        int dataType = Integer.parseInt(hexString.substring(strPosition + 2, strPosition + 4), 16)
        strPosition += 4

        Integer dataLength = DataType.getLength(dataType)
        if (dataLength == null || dataLength == -1 || dataLength == 0) {
            logDebug("Unsupported dataType 0x${Integer.toHexString(dataType)} for tag 0x${Integer.toHexString(dataTag)} (length=${dataLength})")
            return
        }

        int payloadEnd = strPosition + dataLength * 2
        if (payloadEnd > strLength) {
            logDebug("Ran out of bytes mid-record at tag 0x${Integer.toHexString(dataTag)}")
            return
        }

        String dataPayload = reverseHexString(hexString.substring(strPosition, payloadEnd))
        strPosition = payloadEnd

        String tagDebug = "tag 0x${Integer.toHexString(dataTag)} type 0x${Integer.toHexString(dataType)} payload ${dataPayload}"

        switch (dataTag) {
            case 0x01:
                logTrace("$tagDebug (battery voltage)")
                parseBattery(dataPayload, 1000)
                break
            case 0x03:
                long chipTemp = parseCheckinInt(dataPayload, dataType)
                logDebug("$tagDebug (chip temperature ${chipTemp}°C)")
                state.chipTemperature = chipTemp
                break
            case 0x05:
                long powerOutageCount = parseCheckinInt(dataPayload, dataType)
                logTrace("$tagDebug (power_outage_count ${powerOutageCount})")
                state.powerOutageCount = powerOutageCount
                break
            case 0x06:
                long tag06 = parseCheckinInt(dataPayload, dataType)
                logTrace("$tagDebug (proprietary counter ${tag06})")
                state.proprietaryCounter06 = tag06
                break
            case 0x0A:
                logTrace("$tagDebug (Zigbee parent DNI)")
                state.zigbeeParentDNI = dataPayload
                break
            case 0x64:
                String contact = (parseCheckinInt(dataPayload, dataType) == 1) ? "open" : "closed"
                logInfo("Contact (check-in) : ${contact}")
                sendEvent(name: "contact", value: contact)
                break
            case 0x04: case 0x07: case 0x08: case 0x09: case 0x0B: case 0x0C:
                logTrace("$tagDebug (known unhandled)")
                break
            default:
                logDebug("$tagDebug (unexpected tag)")
        }
    }
}

private void parseCheckinFromMap(Map map) {
    // The FF01 that rides bundled with a button-press's 0x0005 frame reaches
    // here as an additionalAttr (map.value = the raw TLV hex). Normalize into
    // the shape parseCheckin() expects (it only reads map.value).
    String hex = map.value
    if (!hex) { logDebug("FF01(map): empty value"); return }
    logDebug("FF01 via additionalAttr (button frame): ${hex}")
    parseCheckin([value: hex])
}

private void parseAttributeReport(Map map) {
    logTrace("parseAttributeReport() : ${map}")
    switch (map.cluster) {
        case "0006":                       // On/Off — contact state
            parseContact(map); return
        case "0000":
            parseBasic(map); return        // Basic cluster; FF01 (attrId) handled inside parseBasic
    }
    logUnhandledMessage(map)
}

private void parseBasic(Map map) {
    // ZCL Basic cluster (0x0000) attribute reports. attrId 0xFF01 carries the
    // Xiaomi check-in payload when it rides bundled with a mfr-specific
    // report (see parseCheckinFromMap). attrId 0x0005 (ModelIdentifier) is
    // discriminated for the button-press quirk below.

    String value = map.value
    String encoding = map.encoding
    switch (map.attrId) {
        case "0001":  // ApplicationVersion (UINT8)
            int appVersion = Integer.parseInt(value, 16)
            updateDataValue("applicationVersion", appVersion.toString())
            logDebug("ApplicationVersion : ${appVersion}")
            break
        case "0004":  // ManufacturerName (Character String)
            String mfg = hexToText(value)
            if (mfg) {
                updateDataValue("manufacturer", mfg)
                logDebug("ManufacturerName : ${mfg}")
            }
            break
        case "0005":  // ModelIdentifier + Xiaomi mfr-specific button-press quirk.
            if (encoding == "42") {
                String model = hexToText(value)
                if (model) {
                    updateDataValue("model", model)
                    logDebug("ModelIdentifier : ${model}")
                }
            }
            // The button/reset-press frame is a mfr-specific (Lumi 0x115F)
            // Report Attributes bundling 0x0005 + an FF01 additionalAttr; the
            // join announce instead bundles 0x0005 + 0x0001 (ApplicationVersion).
            // Only the FF01-bundled variant is a press — the FF01 itself is
            // still separately routed to parseCheckinFromMap via parse()'s
            // additionalAttrs iteration, so diagnostics decode regardless.
            boolean hasFF01 = map.additionalAttrs?.any { it.attrId == "FF01" }
            if (hasFF01) {
                logInfo("Trigger : Button pressed (0x0005 + FF01)")
                sendEvent(name: "pushed", value: 1, isStateChange: true)
            } else {
                logDebug("Basic 0x0005 announce (no FF01 bundled) — no button event")
            }
            break
        case "4000":  // SWBuildID (Character String)
            String sw = hexToText(value)
            if (sw) {
                updateDataValue("softwareBuildId", sw)
                logDebug("SWBuildID : ${sw}")
            }
            break
        case "FF01":  // Bundled check-in TLV (see the parseCheckin doc comment above)
            parseCheckinFromMap(map)
            break
        default:
            logDebug("Basic cluster : unhandled attrId=${map.attrId} encoding=${encoding} value=${value}")
    }
}

private void logUnhandledMessage(Map map) {
    // Fallthrough logger: messages that didn't match a handled cluster above.
    // Known ZDO/ZHA admin responses are logged at debug; anything else is a
    // genuinely-unknown frame and gets a warn that asks the user to report it.

    if (map.cluster == null && map.clusterId == null) {
        logDebug("Skipped : Empty Message")
        return
    }

    switch (map.clusterId) {
        case "0001":
            logDebug("Skipped : Power Configuration Response"); return
        case "0006":
            logDebug("Skipped : Match Descriptor Request"); return
        case "0013":
            logDebug("Skipped : Device Announce Broadcast"); return
        case "0400":
            logDebug("Skipped : Illuminance Response"); return
        case "8004":
            logDebug("Skipped : Simple Descriptor Response"); return
        case "8005":
            logDebug("Skipped : Active End Point Response"); return
        case "8021":
            logDebug("Skipped : Bind Response"); return
    }

    String dataCount = (map.data != null) ? "${map.data.size()} bytes of " : ""
    logWarn("UNKNOWN DATA - Please report these messages to the developer.")
    logWarn("Received : endpoint: ${map.endpoint}, cluster: ${map.cluster}, clusterId: ${map.clusterId}, attrId: ${map.attrId}, command: ${map.command} with value: ${map.value} and ${dataCount}data: ${map.data}")
    logTrace("Full message map : ${map}")
}

// ─── Sensor value processors ───────────────────────────────────────────────

private void parseContact(Map map) {
    // On/Off cluster 0x0006 attr 0x0000: 0 = closed, 1 = open. No dedup —
    // Hubitat coalesces same-value events; the ZHA "open/close/open" phantom
    // is not present on this app:03 firmware.
    if (map.attrId != "0000") { logDebug("Contact: ignoring 0006 attr ${map.attrId}"); return }
    String contact = (map.value == "01") ? "open" : "closed"
    logInfo("Contact : ${contact}")
    sendEvent(name: "contact", value: contact)
}

private void parseBattery(String batteryVoltageHex, int batteryVoltageDivisor) {
    logTrace("batteryVoltageHex : ${batteryVoltageHex}")

    int rawMv = zigbee.convertHexToInt(batteryVoltageHex)
    logDebug("batteryVoltage raw value : ${rawMv}")

    double voltage = ((double) rawMv) / batteryVoltageDivisor
    BigDecimal voltageRounded = BigDecimal.valueOf(voltage).setScale(2, RoundingMode.HALF_UP)
    sendEvent(name: "batteryVoltage", value: voltageRounded, unit: "V")
    logDebug("batteryVoltage : ${voltageRounded}")

    Double prevSmoothed = (state.smoothedBatteryVoltage instanceof Number) ?
        ((Number) state.smoothedBatteryVoltage).doubleValue() : null
    double smoothed
    String emaAction
    if (prevSmoothed == null) {
        smoothed = voltage
        emaAction = "init"
    } else if (voltage - prevSmoothed >= constBatteryBigJumpV) {
        smoothed = voltage
        emaAction = "snap-up"
        Date now = new Date()
        setBatteryReplacementDate(now)
        device.updateDataValue("batteryReplacementDetected",
            "auto: V_prev=${String.format('%.2f', prevSmoothed)} → V_new=${String.format('%.2f', voltage)} @ ${now.format('yyyy-MM-dd HH:mm:ss zzz')}")
        logInfo("Battery replacement detected: ${String.format('%.2f', prevSmoothed)}V → ${String.format('%.2f', voltage)}V")
    } else if (voltage < prevSmoothed) {
        smoothed = constBatteryEmaAlpha * voltage + (1.0d - constBatteryEmaAlpha) * prevSmoothed
        emaAction = "down"
    } else {
        smoothed = prevSmoothed
        emaAction = "hold"
    }
    state.smoothedBatteryVoltage = smoothed

    int batteryPct = batteryPctFromVoltage(smoothed, constBatteryCurveV, constBatteryCurvePct)

    String desc = "$batteryPct% (${voltageRounded}V, smoothed ${String.format('%.3f', smoothed)}V, EMA ${emaAction})"
    if (batteryPct > 20) {
        logInfo("Battery : ${desc}")
    } else {
        logWarn("Battery : ${desc}")
    }

    sendEvent(name: "battery", value: batteryPct, unit: "%")
    state.batteryStatus = batteryPct > 0 ? "discharging" : "exhausted"
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

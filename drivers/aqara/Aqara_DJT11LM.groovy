// Copyright (c) 2022 Andrew Davison           (BirdsLikeWires, GPL-3.0)
// Copyright (c)      veeceeoh                 (check-in decoder, Apache-2.0)
// Copyright (c) 2022-2026 PJ                  (local modifications, monolithic build)
// SPDX-License-Identifier: GPL-3.0-only

/*
 *  Aqara Vibration Sensor DJT11LM Driver (monolithic build)
 *
 *  Derived from the Aqara Contact Sensor MCCGQ11LM driver in this repository
 *  (drivers/aqara/Aqara_MCCGQ11LM.groovy), itself a derivative of Andrew
 *  Davison's BirdsLikeWires Xiaomi Aqara Temperature and Humidity Sensor
 *  driver (GPL-3.0):
 *    https://github.com/birdslikewires/hubitat
 *
 *  The check-in payload decoder (reverseHexString and the FF01 TLV walker in
 *  parseCheckin) traces to veeceeoh's Xiaomi/Aqara Hubitat drivers (Apache-2.0):
 *    https://github.com/veeceeoh/xiaomi-hubitat
 *
 *  The cluster 0x0101 attribute meanings, the strength decode, the angle
 *  formulas and the sensitivity write follow Zigbee2MQTT's converter for this
 *  model (zigbee-herdsman-converters, lumi_vibration_analog and
 *  lumi_vibration_sensitivity).
 *
 *  Licensed under GPL-3.0-only (combined-work license, inherited from the
 *  BirdsLikeWires upstream). This per-file notice overrides the
 *  repo's MIT default. Full license texts:
 *    GPL-3.0:    https://www.gnu.org/licenses/gpl-3.0.html
 *    Apache-2.0: https://www.apache.org/licenses/LICENSE-2.0
 */


metadata {
    definition (
        name: "Aqara Vibration Sensor DJT11LM",
        description: "Aqara Zigbee Vibration Sensor Driver - DJT11LM",
        namespace: "iamtrep",
        author: "pj",
        singleThreaded: true,
        importUrl: "https://raw.githubusercontent.com/iamtrep/hubitat/main/drivers/aqara/Aqara_DJT11LM.groovy"
    ) {
        capability "Battery"
        capability "Configuration"
        attribute "healthStatus", "enum", ["online", "offline"]
        capability "Sensor"
        capability "AccelerationSensor"
        capability "ThreeAxis"
        capability "PushableButton"

        attribute "lastEvent", "enum", ["vibration", "tilt", "drop"]
        attribute "strength", "number"
        attribute "tiltAngle", "number"
        attribute "angleX", "number"
        attribute "angleY", "number"
        attribute "angleZ", "number"
        attribute "sensitivity", "enum", ["high", "medium", "low"]
        attribute "batteryVoltage", "number"
        attribute "notPresentCounter", "number"
        attribute "restoredCounter", "number"
        attribute "rejoinCount", "number"

        command "resetMeshCounters"
        command "setBatteryReplacementDate"

        fingerprint profileId: "0104", inClusters: "0000,0003,0019,0101", outClusters: "0000,0004,0003,0005,0019,0101", manufacturer: "LUMI", model: "lumi.vibration.aq1", deviceJoinName: "DJT11LM"
    }

    preferences {
        input name: "vibrationTimeout", type: "number", title: "Seconds until acceleration returns to inactive", description: "The sensor reports when vibration starts and never when it stops.", defaultValue: 90, range: "1..3600"
        input name: "sensitivity", type: "enum", title: "Sensitivity", description: "Written to the sensor the next time it wakes (an event, a button press or the hourly check-in). Leave unset to keep the sensor's current level.", options: ["1": "High", "11": "Medium", "21": "Low"]
        input name: "txtEnable",   type: "bool", title: "Enable info logging", defaultValue: true
        input name: "debugEnable", type: "bool", title: "Enable debug logging",           defaultValue: false, submitOnChange: true
        if (debugEnable) {
            input name: "traceEnable", type: "bool", title: "Enable trace logging",       defaultValue: false
        }
    }
}

import groovy.transform.CompileStatic
import groovy.transform.Field
import java.math.RoundingMode

@Field static final String CODE_VERSION = "0.1.2"

// A pending version reconfigure older than this is treated as lost and re-armed.
@Field static final long RECONFIGURE_RETRY_MS = 60000L

@Field static final int REPORT_INTERVAL_MINUTES = 60
@Field static final int CHECK_EVERY_MINUTES = 10

// Buttons: 1 = the reset button, 2 = tilt, 3 = drop.
@Field static final int BUTTON_RESET = 1
@Field static final int BUTTON_TILT = 2
@Field static final int BUTTON_DROP = 3

// Sensitivity is Basic-cluster attribute 0xFF0D (uint8, Lumi manufacturer code):
// 1 = highest, 21 = lowest. The sensor sleeps, so a write is repeated when it
// is heard from, at most every SENSITIVITY_RETRY_MS, SENSITIVITY_MAX_ATTEMPTS times.
@Field static final Map<String, String> SENSITIVITY_LABELS = ["1": "high", "11": "medium", "21": "low"]
@Field static final long SENSITIVITY_RETRY_MS = 10000L
@Field static final int SENSITIVITY_MAX_ATTEMPTS = 20

// CR2032 cell, Z2M voltageToPercentage {min:2850, max:3000} — linear, clamped.
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
    logCfg "Installed"
    state.clear()
    initialize()
}

void initialize() {
    // Idempotent setup — entered from installed(), updated(), and runInMillis on
    // version-change. Does NOT issue device-side Zigbee reporting (that's configure()).

    unschedule()
    if (debugEnable || traceEnable) runIn(1800, "logsOff")
    // unschedule() also cancels a pending accelerationInactive; re-arm it so
    // acceleration can't stay active until the next vibration.
    if (device.currentValue("acceleration") == "active") {
        runIn((settings.vibrationTimeout ?: 90) as int, "accelerationInactive")
    }

    // Seed to install/upgrade time so the normal "overdue" grace window
    // applies cleanly even if the device has never spoken — without it,
    // checkHealth() can never flip offline.
    if (!state.lastMessageMillis) state.lastMessageMillis = new Date().time

    // Counters survive code pushes — only seed them when they don't already exist.
    if (device.currentValue("notPresentCounter") == null) sendEvent(name: "notPresentCounter", value: 0, isStateChange: false)
    if (device.currentValue("restoredCounter")  == null) sendEvent(name: "restoredCounter",  value: 0, isStateChange: false)
    if (device.currentValue("rejoinCount")      == null) sendEvent(name: "rejoinCount",      value: 0, isStateChange: false)
    if (device.currentValue("healthStatus") != "online") sendEvent(name: "healthStatus", value: "online", isStateChange: false)

    // Schedule health checking with random jitter so multiple devices don't stampede.
    int randomSixty = RANDOM.nextInt(60)
    schedule("${randomSixty} 0/${CHECK_EVERY_MINUTES} * * * ? *", "checkHealth")

    updateDataValue("driver", CODE_VERSION)
    sendEvent(name: "numberOfButtons", value: 3, isStateChange: false)
    if (device.currentValue("acceleration") == null) sendEvent(name: "acceleration", value: "inactive", isStateChange: false)

    state.remove("reconfigurePending")
    state.remove("reconfigurePendingAt")

    logCfg "Initialized."
}

void updated() {
    // Runs when preferences are saved. initialize() re-converges and arms log-off.
    logCfg "Preferences Updated"
    logCfg "Info Logging:  ${txtEnable}"
    logCfg "Debug Logging: ${debugEnable}"
    logCfg "Trace Logging: ${traceEnable}"

    initialize()
    requestSensitivity()
}

void deviceTypeUpdated() {
    logDebug "driver change detected"
    configure()
}

void configure() {
    // Required by the Configuration capability. The sensor sets up its own
    // reporting at pairing time — no zigbee.configureReporting needed here.
    logCfg "Configuring."
    initialize()
}

void runVersionReconfigure() {
    // runInMillis target — lets parse() return immediately so the reconfigure
    // runs on a fresh dispatch instead of inside the inbound frame's handler.
    logVer "Driver upgraded from ${getDeviceDataByName('driver')} to ${CODE_VERSION}, reconfiguring."
    initialize()
}

// ─── Capability commands ───────────────────────────────────────────────────

void push(Integer buttonId) {
    sendEvent(name: "pushed", value: buttonId, isStateChange: true)
}

void resetMeshCounters() {
    sendEvent(name: "notPresentCounter", value: 0)
    sendEvent(name: "restoredCounter", value: 0)
    sendEvent(name: "rejoinCount", value: 0)
    logCmd("Mesh counters reset")
}

void setBatteryReplacementDate(Date date = null) {
    if (date == null) date = new Date()
    String dateStr = date.format('yyyy-MM-dd')
    device.updateDataValue("batteryReplacementDate", dateStr)
    logCmd("Battery replacement date set to ${dateStr}")
}

// ─── Health monitoring ─────────────────────────────────────────────────────

void checkHealth() {
    long millisElapsed = new Date().time - state.lastMessageMillis
    long timeoutMillis = (REPORT_INTERVAL_MINUTES * 2 + HEALTH_TIMEOUT_SLACK_MINUTES) * 60000L
    long secondsElapsed = millisElapsed.intdiv(1000)
    long hubUptime = location.hub.uptime

    if (millisElapsed <= timeoutMillis) {
        // Device is reporting on schedule. updateHealthStatus() in parse() has
        // already flipped healthStatus to "online", so nothing to do here.
        logSched("Health : Last message ${secondsElapsed} seconds ago.")
        logTrace("checkHealth() : elapsed=${millisElapsed}ms, timeout=${timeoutMillis}ms")
        return
    }

    if (hubUptime <= HUB_REBOOT_ALLOWANCE_MINUTES * 60) {
        logSched("Health : Ignoring overdue reports for ${HUB_REBOOT_ALLOWANCE_MINUTES} minutes after hub reboot (uptime ${hubUptime}s).")
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
    handleFrame(description)
    sendPendingSensitivity()   // the sensor is awake right after it transmits; after the frame, which may be the confirmation
}

private void handleFrame(String description) {

    // --- Xiaomi check-in (cluster 0x0000 attr 0xFF01) is delivered in a
    // non-ZCL description format. Slice it and hand off to the check-in decoder.
    if (description?.contains("attrId: FF01")) {
        Map xiaomiMap = description.split(', ').collectEntries { String entry ->
            String[] pair = entry.split(': ')
            [(pair.first()): pair.last()]
        }
        logRx "Parse (Xiaomi check-in): ${xiaomiMap}"
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

    logRx "Parse: ${descMap}"

    // ZDO command (profile 0x0000) — bind responses, mgmt responses, etc.
    if (descMap.profileId == "0000") {
        // Device_annce (cluster 0x0013): broadcast by the device on each
        // (re)join; Hubitat routes this device's own announce here. Use it as a
        // rejoin marker — the payload carries the new NWK address, not the new
        // parent (that arrives in the next FF01 check-in, tag 0x0A). No refresh:
        // this sleepy end device does not usefully answer reads.
        if (descMap.clusterId == "0013") {
            int count = (device.currentValue("rejoinCount") ?: 0) + 1
            sendEvent(name: "rejoinCount", value: count)
            state.lastRejoin = new Date().toLocaleString()
            logInfo "Device announce — rejoined the mesh (rejoin #${count}), address ${device.deviceNetworkId}"
            return
        }
        logTrace "Unhandled ZDO command: cluster=${descMap.clusterId} command=${descMap.command} value=${descMap.value} data=${descMap.data}"
        return
    }

    // ZHA global command (profile 0x0104, no attrId) — Configure Reporting Response etc.
    if (descMap.profileId == "0104" && descMap.attrId == null) {
        // Write Attributes Response on the Basic cluster: the answer to a sensitivity write.
        if (descMap.clusterId == "0000" && descMap.command == "04") {
            parseSensitivityResponse(descMap)
            return
        }
        logTrace "Unhandled ZHA global command: cluster=${descMap.clusterId} command=${descMap.command} value=${descMap.value} data=${descMap.data}"
        return
    }

    // IAS Zone — DJT11LM does not use IAS, log if any frame appears.
    if (descMap.clusterId == "0500") {
        logRx "IAS Zone message (unexpected for this device): ${descMap}"
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
    // on the receiving hub). The pending timestamp keeps a burst of frames from
    // scheduling twice, and expires so a lost callback re-arms on a later frame.
    if (getDeviceDataByName('driver') == CODE_VERSION) return
    Long pendingAt = state.reconfigurePendingAt as Long
    if (pendingAt != null && now() - pendingAt < RECONFIGURE_RETRY_MS) return
    state.reconfigurePendingAt = now()
    runInMillis(100, "runVersionReconfigure")
}

/*
 * Xiaomi/Aqara 0xFF01 check-in payload (cluster 0x0000 attribute 0xFF01)
 * ─────────────────────────────────────────────────────────────────────
 * The hex value carried by the FF01 attribute is a length-prefixed sequence
 * of TLV records:
 *
 *     [len][tag][type][value(little-endian)][tag][type][value]...
 *      1B   1B   1B    DataType.getLength(type) bytes
 *
 *   The length-prefix byte is present on the wire, but this driver receives
 *   the payload via two different paths that don't agree on whether it's
 *   still there by the time parseCheckin() sees it:
 *   - Hourly check-in: FF01 is the *primary* attribute (cluster 0000, attrId
 *     FF01) and arrives via parse()'s non-ZCL string branch, where the raw
 *     `value:` field still carries the length prefix -> parseCheckin() skips
 *     it (strPosition = 2), same as the sibling WSDCGQ11LM driver.
 *   - Button press: FF01 rides as an *additionalAttr* alongside a primary
 *     0x0005 (ModelIdentifier) report; descMap.additionalAttrs
 *     carries it as text (one character per byte) with the length prefix
 *     stripped, so parseCheckinFromMap() converts it back to hex and re-adds
 *     a one-byte "00" placeholder before delegating to parseCheckin(),
 *     keeping both paths aligned on the same strPosition = 2 skip.
 *
 *   • len     — UINT8 byte count of everything that follows. Skipped.
 *   • tag     — 1-byte Xiaomi-proprietary attribute id (see table below).
 *   • type    — 1-byte ZCL data type; DataType.getLength() returns the value
 *               width in bytes (null for variable-length, which we abort on).
 *   • value   — little-endian; reverseHexString() flips to big-endian for parse.
 *
 * Two delivery paths bundle this payload on DJT11LM:
 *   - Hourly check-in: FF01 is the *primary* attribute (cluster 0000, attrId
 *     FF01) and arrives via parse()'s non-ZCL string branch -> parseCheckin().
 *   - Button press: FF01 rides as an *additionalAttr* alongside a primary
 *     0x0005 (ModelIdentifier) report -> parseBasic() routes it to
 *     parseCheckinFromMap(), which normalizes into the same parseCheckin().
 *
 * Tag table for lumi.vibration.aq1 (DJT11LM), from one reported press frame.
 * Tags 0x64+ are reused across the lumi.* family for different sensors — do
 * not copy this table verbatim into a sibling driver; cross-check Z2M's
 * lumi.ts numericAttributes2Payload first.
 *
 *   0x01  battery voltage (mV, UINT16)
 *   0x03  device chip temperature (°C, INT8; internal NCP, not a room reading)
 *   0x04  unknown (present in every payload — purpose undocumented)
 *   0x05  power_outage_count (UINT16)
 *   0x06  Xiaomi proprietary cumulative counter (UINT40, model-specific)
 *   0x08  unknown
 *   0x0A  Zigbee parent DNI (network identifier)
 *   0x98, 0x99  unknown (UINT16; Z2M: values like 30, 50 that don't seem to change)
 *   0x9A  UINT48 that reads as the three axes, like cluster 0x0101 attribute
 *         0x0508 (inferred from one frame: -29, 1022, 265). Kept in state
 *         only; the threeAxis attribute follows 0x0508 reports.
 */
private void parseCheckin(Map map) {
    String hexString = map.value
    int strLength = hexString.size()

    logInfo("Check-in message.")
    logRx("parseCheckin : ${strLength}-char payload")

    if (strLength <= 20) {
        logRx("parseCheckin : payload too short to carry sensor data")
        return
    }

    int strPosition = 2  // Skip the length-prefix byte.

    while (strPosition < strLength) {
        int dataTag  = Integer.parseInt(hexString.substring(strPosition,     strPosition + 2), 16)
        int dataType = Integer.parseInt(hexString.substring(strPosition + 2, strPosition + 4), 16)
        strPosition += 4

        Integer dataLength = DataType.getLength(dataType)
        if (dataLength == null || dataLength == -1 || dataLength == 0) {
            logRx("Unsupported dataType 0x${Integer.toHexString(dataType)} for tag 0x${Integer.toHexString(dataTag)} (length=${dataLength})")
            return
        }

        int payloadEnd = strPosition + dataLength * 2
        if (payloadEnd > strLength) {
            logRx("Ran out of bytes mid-record at tag 0x${Integer.toHexString(dataTag)}")
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
                logRx("$tagDebug (chip temperature ${chipTemp}°C)")
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
            case 0x98: case 0x99:
                logTrace("$tagDebug (unknown, kept in state)")
                state["checkinTag${Integer.toHexString(dataTag).toUpperCase()}"] = parseCheckinInt(dataPayload, dataType)
                break
            case 0x9A:
                Map axes = decodeAxes(dataPayload)
                logTrace("$tagDebug (axes ${axes})")
                if (axes) state.checkinAxes = "[x:${axes.x},y:${axes.y},z:${axes.z}]".toString()
                break
            case 0x04: case 0x07: case 0x08: case 0x09: case 0x0B: case 0x0C:
                logTrace("$tagDebug (known unhandled)")
                break
            default:
                logRx("$tagDebug (unexpected tag)")
        }
    }
}

private void parseCheckinFromMap(Map map) {
    // FF01 bundled with a button-press 0x0005 frame. zigbee.parseDescriptionAsMap()
    // hands a char-string additionalAttr over as text, one Latin-1 character per
    // byte, with the length prefix stripped. Turn it back into hex (a value that
    // is already hex is kept) and prepend a placeholder byte for parseCheckin()'s
    // strPosition = 2 skip.
    String value = map.value
    if (!value) { logRx("FF01(map): empty value"); return }
    String hex = (value ==~ /[0-9a-fA-F]+/) ? value : value.collect { String ch -> String.format("%02X", (int) ch.charAt(0)) }.join("")
    logRx("FF01 via additionalAttr (button frame): ${hex}")
    parseCheckin([value: "00" + hex])
}

private void parseAttributeReport(Map map) {
    logTrace("parseAttributeReport() : ${map}")
    switch (map.cluster) {
        case "0101":                       // Door Lock cluster, reused for vibration, tilt and drop
            parseMotion(map); return
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
            logRx("ApplicationVersion : ${appVersion}")
            break
        case "0004":  // ManufacturerName (Character String)
            String mfg = hexToText(value)
            if (mfg) {
                updateDataValue("manufacturer", mfg)
                logRx("ManufacturerName : ${mfg}")
            }
            break
        case "0005":  // ModelIdentifier + Xiaomi mfr-specific button-press quirk.
            if (encoding == "42") {
                String model = hexToText(value)
                if (model) {
                    updateDataValue("model", model)
                    logRx("ModelIdentifier : ${model}")
                }
            }
            // The button/reset-press frame is a Report Attributes bundling
            // 0x0005 + an FF01 additionalAttr. Only the FF01-bundled variant is
            // a press — the FF01 itself is still separately routed to
            // parseCheckinFromMap via parse()'s additionalAttrs iteration, so
            // diagnostics decode regardless.
            boolean hasFF01 = map.additionalAttrs?.any { it.attrId == "FF01" }
            if (hasFF01) {
                logInfo("Trigger : Button pressed (0x0005 + FF01)")
                sendEvent(name: "pushed", value: BUTTON_RESET, isStateChange: true)
            } else {
                logRx("Basic 0x0005 announce (no FF01 bundled) — no button event")
            }
            break
        case "4000":  // SWBuildID (Character String)
            String sw = hexToText(value)
            if (sw) {
                updateDataValue("softwareBuildId", sw)
                logRx("SWBuildID : ${sw}")
            }
            break
        case "FF01":  // Bundled check-in TLV (see the parseCheckin doc comment above)
            parseCheckinFromMap(map)
            break
        case "FF0D":  // Sensitivity level, read back after a write
            parseSensitivityLevel(map)
            break
        default:
            logRx("Basic cluster : unhandled attrId=${map.attrId} encoding=${encoding} value=${value}")
    }
}

private void logUnhandledMessage(Map map) {
    // Fallthrough logger: messages that didn't match a handled cluster above.
    // Known ZDO/ZHA admin responses are logged at debug; anything else is a
    // genuinely-unknown frame and gets a warn that asks the user to report it.

    if (map.cluster == null && map.clusterId == null) {
        logRx("Skipped : Empty Message")
        return
    }

    switch (map.clusterId) {
        case "0001":
            logRx("Skipped : Power Configuration Response"); return
        case "0006":
            logRx("Skipped : Match Descriptor Request"); return
        case "0013":
            logRx("Skipped : Device Announce Broadcast"); return
        case "0400":
            logRx("Skipped : Illuminance Response"); return
        case "8004":
            logRx("Skipped : Simple Descriptor Response"); return
        case "8005":
            logRx("Skipped : Active End Point Response"); return
        case "8021":
            logRx("Skipped : Bind Response"); return
    }

    String dataCount = (map.data != null) ? "${map.data.size()} bytes of " : ""
    logWarn("UNKNOWN DATA - Please report these messages to the developer.")
    logWarn("Received : endpoint: ${map.endpoint}, cluster: ${map.cluster}, clusterId: ${map.clusterId}, attrId: ${map.attrId}, command: ${map.command} with value: ${map.value} and ${dataCount}data: ${map.data}")
    logTrace("Full message map : ${map}")
}

// ─── Sensor value processors ───────────────────────────────────────────────

private void parseMotion(Map map) {
    // Cluster 0x0101 attribute reports. The hub has already put multi-byte
    // values in big-endian order (zigbee.parseDescriptionAsMap).
    String value = map.value
    switch (map.attrId) {
        case "0055":  // Event: 1 vibration, 2 tilt, 3 drop
            int code = Integer.parseInt(value, 16)
            switch (code) {
                case 1:
                    int seconds = (settings.vibrationTimeout ?: 90) as int
                    logInfo("Vibration : active for ${seconds} s")
                    sendEvent(name: "lastEvent", value: "vibration", isStateChange: true)
                    sendEvent(name: "acceleration", value: "active")
                    runIn(seconds, "accelerationInactive")
                    break
                case 2:
                    logInfo("Tilt")
                    sendEvent(name: "lastEvent", value: "tilt", isStateChange: true)
                    sendEvent(name: "pushed", value: BUTTON_TILT, isStateChange: true)
                    break
                case 3:
                    logInfo("Drop")
                    sendEvent(name: "lastEvent", value: "drop", isStateChange: true)
                    sendEvent(name: "pushed", value: BUTTON_DROP, isStateChange: true)
                    break
                default:
                    logRx("Event attribute 0x0055 : unknown code ${code} (value ${value})")
            }
            break
        case "0503":  // Tilt angle, degrees
            int angle = Integer.parseInt(value, 16)
            logInfo("Tilt angle : ${angle}°")
            sendEvent(name: "tiltAngle", value: angle, unit: "°", isStateChange: true)
            break
        case "0505":  // Strength
            Integer strength = decodeStrength(value)
            if (strength == null) {
                logRx("Strength 0x0505 : unexpected value ${value} (encoding ${map.encoding})")
            } else {
                logInfo("Strength : ${strength}")
                logRx("Strength 0x0505 raw value : ${value}")
                sendEvent(name: "strength", value: strength)
            }
            break
        case "0508":  // Accelerometer axes
            Map axes = decodeAxes(value)
            if (!axes) {
                logRx("Axes 0x0508 : unexpected value ${value} (encoding ${map.encoding})")
            } else {
                Map angles = axisAngles(axes.x as int, axes.y as int, axes.z as int)
                logInfo("Axes : x ${axes.x}, y ${axes.y}, z ${axes.z} (angles ${angles.x}°, ${angles.y}°, ${angles.z}°)")
                sendEvent(name: "threeAxis", value: "[x:${axes.x},y:${axes.y},z:${axes.z}]".toString())
                sendEvent(name: "angleX", value: angles.x, unit: "°")
                sendEvent(name: "angleY", value: angles.y, unit: "°")
                sendEvent(name: "angleZ", value: angles.z, unit: "°")
            }
            break
        default:
            logRx("Cluster 0x0101 : unhandled attrId=${map.attrId} encoding=${map.encoding} value=${value}")
    }
}

void accelerationInactive() {
    logInfo("Vibration : inactive")
    sendEvent(name: "acceleration", value: "inactive")
}

// Strength from attribute 0x0505 (UINT32). With the wire bytes numbered b0..b3,
// Z2M takes (b1 << 8) | b2; the value here is big-endian, b3 b2 b1 b0.
@CompileStatic
private static Integer decodeStrength(String hexBigEndian) {
    if (hexBigEndian == null || hexBigEndian.length() != 8) return null
    return Integer.parseInt(hexBigEndian.substring(4, 6) + hexBigEndian.substring(2, 4), 16)
}

// Axes from a UINT48 holding x, y, z as signed 16-bit values, x first on the
// wire; the value here is big-endian, so it reads z, y, x.
@CompileStatic
private static Map<String, Integer> decodeAxes(String hexBigEndian) {
    if (hexBigEndian == null || hexBigEndian.length() != 12) return null
    int z = (int) (short) Integer.parseInt(hexBigEndian.substring(0, 4), 16)
    int y = (int) (short) Integer.parseInt(hexBigEndian.substring(4, 8), 16)
    int x = (int) (short) Integer.parseInt(hexBigEndian.substring(8, 12), 16)
    return [x: x, y: y, z: z]
}

// Angle of each axis against the plane of the other two, whole degrees.
@CompileStatic
private static Map<String, Integer> axisAngles(int x, int y, int z) {
    double dx = x, dy = y, dz = z
    int ax = (int) Math.round(Math.toDegrees(Math.atan2(dx, Math.sqrt(dy * dy + dz * dz))))
    int ay = (int) Math.round(Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz))))
    int az = (int) Math.round(Math.toDegrees(Math.atan2(dz, Math.sqrt(dx * dx + dy * dy))))
    return [x: ax, y: ay, z: az]
}

// ─── Sensitivity ───────────────────────────────────────────────────────────

private void requestSensitivity() {
    // From updated(): queue a write when the preference differs from the level
    // the sensor last confirmed.
    String wanted = settings.sensitivity
    if (wanted == null || !SENSITIVITY_LABELS.containsKey(wanted)) return
    if (wanted == getDataValue("sensitivityLevel") && !state.sensitivityPending) return
    state.sensitivityPending = wanted
    state.sensitivityAttempts = 0
    state.remove("sensitivityLastAttempt")
    logCfg("Sensitivity : ${SENSITIVITY_LABELS[wanted]} queued; it is written when the sensor next wakes")
    sendPendingSensitivity()
}

private void sendPendingSensitivity() {
    String pending = state.sensitivityPending
    if (!pending) return
    long last = (state.sensitivityLastAttempt ?: 0L) as long
    if (now() - last < SENSITIVITY_RETRY_MS) return
    int attempts = ((state.sensitivityAttempts ?: 0) as int) + 1
    if (attempts > SENSITIVITY_MAX_ATTEMPTS) {
        logWarn("Sensitivity : no answer after ${SENSITIVITY_MAX_ATTEMPTS} writes, giving up. Save the preferences to try again.")
        clearSensitivityRequest()
        return
    }
    state.sensitivityAttempts = attempts
    state.sensitivityLastAttempt = now()
    logCmd("Sensitivity : writing ${SENSITIVITY_LABELS[pending]} (attempt ${attempts})")
    // Write, then read the level back: either answer confirms it.
    sendZigbeeCommands(zigbee.writeAttribute(0x0000, 0xFF0D, 0x20, pending as int, [mfgCode: "0x115F"]) +
                       zigbee.readAttribute(0x0000, 0xFF0D, [mfgCode: "0x115F"]))
}

private void parseSensitivityResponse(Map descMap) {
    String pending = state.sensitivityPending
    String status = descMap.data ? descMap.data[0] : null
    if (!pending) {
        logRx("Write Attributes Response on Basic with no write pending : ${descMap.data}")
        return
    }
    if (status != "00") {
        logWarn("Sensitivity : the sensor refused the write (status ${status}, data ${descMap.data})")
        clearSensitivityRequest()
        return
    }
    confirmSensitivity(pending)
}

private void parseSensitivityLevel(Map map) {
    // Attribute 0xFF0D read back from the sensor: the level it holds now.
    String level = map.value ? Integer.parseInt(map.value as String, 16).toString() : null
    if (!SENSITIVITY_LABELS.containsKey(level)) {
        logRx("Sensitivity 0xFF0D : level ${map.value} is not one of high, medium, low")
        updateDataValue("sensitivityLevel", level ?: "unknown")
        return
    }
    String pending = state.sensitivityPending
    if (pending && pending != level) {
        // The read answered before the write took effect, or the write was lost: keep trying.
        logRx("Sensitivity 0xFF0D : sensor holds ${SENSITIVITY_LABELS[level]}, ${SENSITIVITY_LABELS[pending]} still pending")
        updateDataValue("sensitivityLevel", level)
        sendEvent(name: "sensitivity", value: SENSITIVITY_LABELS[level])
        return
    }
    confirmSensitivity(level)
}

private void confirmSensitivity(String level) {
    String label = SENSITIVITY_LABELS[level]
    updateDataValue("sensitivityLevel", level)
    sendEvent(name: "sensitivity", value: label)
    if (state.sensitivityPending) logCfg("Sensitivity : ${label} confirmed by the sensor")
    clearSensitivityRequest()
}

private void clearSensitivityRequest() {
    state.remove("sensitivityPending")
    state.remove("sensitivityAttempts")
    state.remove("sensitivityLastAttempt")
}

// ─── Zigbee command primitives ─────────────────────────────────────────────

private void sendZigbeeCommands(List<String> cmds) {
    // sendHubCommand dispatches immediately, unlike returning a List from a
    // command handler (which the platform queues and flushes later).

    logTrace("sendZigbeeCommands received : ${cmds}")
    sendHubCommand(new hubitat.device.HubMultiAction(cmds, hubitat.device.Protocol.ZIGBEE))
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

private void parseBattery(String batteryVoltageHex, int batteryVoltageDivisor) {
    logTrace("batteryVoltageHex : ${batteryVoltageHex}")

    int rawMv = zigbee.convertHexToInt(batteryVoltageHex)
    logRx("batteryVoltage raw value : ${rawMv}")

    double voltage = ((double) rawMv) / batteryVoltageDivisor
    BigDecimal voltageRounded = BigDecimal.valueOf(voltage).setScale(2, RoundingMode.HALF_UP)
    sendEvent(name: "batteryVoltage", value: voltageRounded, unit: "V")
    logRx("batteryVoltage : ${voltageRounded}")

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

void logsOff() {
    device.updateSetting("debugEnable", [value: "false", type: "bool"])
    device.updateSetting("traceEnable", [value: "false", type: "bool"])
    logWarn "debug and trace logging disabled"
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

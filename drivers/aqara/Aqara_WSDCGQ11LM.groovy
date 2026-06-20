// Copyright (c) 2022 Andrew Davison           (BirdsLikeWires, GPL-3.0)
// Copyright (c)      veeceeoh                 (check-in decoder, Apache-2.0)
// Copyright (c) 2022-2026 PJ                  (local modifications, monolithic build)
// SPDX-License-Identifier: GPL-3.0-only

/*
 *  Aqara Weather Sensor WSDCGQ11LM Driver (monolithic build)
 *
 *  Derivative of Andrew Davison's BirdsLikeWires Xiaomi Aqara Temperature and
 *  Humidity Sensor WSDCGQ11LM driver (GPL-3.0):
 *    https://github.com/birdslikewires/hubitat
 *
 *  Built from a locally-modified copy of the upstream sources; the two
 *  BirdsLikeWires libraries the upstream driver depended on are inlined
 *  below — no Hubitat library types required at install time:
 *    - BirdsLikeWires.library v1.17 (8th November 2022)
 *    - BirdsLikeWires.xiaomi  v1.12 (8th November 2022)
 *
 *  The check-in payload decoder (reverseHexString and the FF01 TLV walker in
 *  parseCheckin) was originally incorporated from veeceeoh's WSDCGQ11LM driver (Apache-2.0):
 *    https://github.com/veeceeoh/xiaomi-hubitat
 *  Inline `// Adapted from ...` attribution comments are preserved where
 *  applicable. 
 *
 *  The mesh-recovery pattern (re-bind reporting clusters on disconnect, then
 *  periodically poll readAttribute(0x0000, 0x0004) until the device returns)
 *  was adapted from kkossev's Aqara/Zigbee driver work in the Hubitat
 *  community. Code was rewritten locally — this is idea attribution, not
 *  code derivation.
 *
 *  Licensed under GPL-3.0-only (combined-work license, inherited from the
 *  BirdsLikeWires upstream). This per-file notice overrides the
 *  repo's MIT default. Full license texts:
 *    GPL-3.0:    https://www.gnu.org/licenses/gpl-3.0.html
 *    Apache-2.0: https://www.apache.org/licenses/LICENSE-2.0
 */


metadata {
    definition (
        name: "Aqara Weather Sensor WSDCGQ11LM",
        description: "Aqara Zigbee Temperature/Humidity/Pressure Environmental Sensor Driver - WSDCGQ11LM",
        namespace: "iamtrep",
        author: "pj",
        singleThreaded: true,
        importUrl: "https://raw.githubusercontent.com/iamtrep/hubitat/main/drivers/aqara/Aqara_WSDCGQ11LM.groovy"
    ) {
        capability "Battery"
        capability "Configuration"
        attribute "healthStatus", "enum", ["online", "offline"]
        capability "PressureMeasurement"
        capability "RelativeHumidityMeasurement"
        capability "Sensor"
        capability "TemperatureMeasurement"
        capability "PushableButton"

        attribute "absoluteHumidity", "number"
        attribute "batteryVoltage", "number"
        attribute "pressureDirection", "string"
        attribute "notPresentCounter", "number"
        attribute "restoredCounter", "number"

        command "startRecovery"
        command "stopRecovery"
        command "resetMeshCounters"
        command "setBatteryReplacementDate"

        fingerprint profileId: "0104", inClusters: "0000,0003,FFFF,0402,0403,0405", outClusters: "0000,0004,FFFF", manufacturer: "LUMI", model: "lumi.weather", deviceJoinName: "WSDCGQ11LM", application: "05"
    }

    preferences {
        input name: "txtEnable",   type: "bool", title: "Enable descriptionText logging", defaultValue: true
        input name: "debugEnable", type: "bool", title: "Enable debug logging",           defaultValue: false, submitOnChange: true
        if (debugEnable) {
            input name: "traceEnable", type: "bool", title: "Enable trace logging",       defaultValue: false
        }

        input name: "tempOffset", type: "decimal", title: "Temperature offset", description: "Adjustment in display units (°C or °F)", defaultValue: 0
        input name: "humidityOffset", type: "decimal", title: "Humidity offset", description: "Adjustment in %", defaultValue: 0
        input name: "pressureOffset", type: "decimal", title: "Pressure offset", description: "Adjustment in display units", defaultValue: 0
        input name: "pressureUnits", type: "enum", title: "Pressure units", options: ["kPa", "mbar", "inHg", "mmHg"], defaultValue: "kPa"

        input name: "recoveryMode", type: "enum", title: "Mesh recovery mode", options: ["Disabled", "Slow", "Normal", "Aggressive"], defaultValue: "Normal", description: "How aggressively to probe when check-ins are missed"
    }
}

import groovy.transform.CompileStatic
import groovy.transform.Field
import java.math.RoundingMode

@Field static final String CODE_VERSION = "2.15.1"

@Field static final int REPORT_INTERVAL_MINUTES = 60
@Field static final int CHECK_EVERY_MINUTES = 10
@Field static final int RECOVERY_PROBE_INTERVAL_SECONDS = 120

// CR2032 discharge curve, Z2M "3V_2100" preset (knot voltages in V, percent at knot).
// Piecewise linear; clamped at endpoints. Same curve as XfinityContactSensor.
@Field static final double[] constBatteryCurveV   = [2.10d, 2.44d, 2.74d, 2.90d, 3.00d] as double[]
@Field static final double[] constBatteryCurvePct = [ 0.0d,  6.0d, 18.0d, 42.0d, 100.0d] as double[]

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
    state.recoveryActive = false

    // Seed to install/upgrade time so the normal "overdue" grace window
    // applies cleanly even if the device has never spoken — without it,
    // checkHealth() can never flip offline and recovery never engages.
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

    // Drop legacy state keys — FF01 tags 0x05/0x06 were misnamed RSSI/LQI in
    // an earlier driver version (see parseCheckin header for the corrected
    // semantics).
    state.remove("RSSI")
    state.remove("LQI")

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

void configure() {
    // Required by the Configuration capability. WSDCGQ11LM sets up its own
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
    tryAutoRecovery()
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

    if (state.recoveryActive) {
        unschedule("recoveryProbe")
        state.recoveryActive = false
        logInfo("Recovery : Device returned, stopping probes")
    }
}

// ─── Mesh recovery ─────────────────────────────────────────────────────────

private void tryAutoRecovery() {
    // Automatic path from checkHealth() — respects the user's recoveryMode
    // preference and won't double-start an already-active probe loop.
    String mode = recoveryMode ?: "Normal"
    if (mode == "Disabled" || state.recoveryActive) return

    int intervalSeconds = recoveryIntervalForMode()

    state.recoveryActive = true
    logInfo("Recovery : Starting ${mode} mode (every ${intervalSeconds}s)")
    rebindClusters()
    runIn(intervalSeconds, "recoveryProbe")
}

void startRecovery() {
    // Manual command — bypasses the Disabled-mode and recoveryActive guards
    // so the user can kick a stuck sensor regardless of preference state.
    unschedule("recoveryProbe")
    int intervalSeconds = (recoveryMode ?: "Normal") == "Disabled" ? RECOVERY_PROBE_INTERVAL_SECONDS : recoveryIntervalForMode()
    state.recoveryActive = true
    logInfo("Recovery : Manual start (probing every ${intervalSeconds}s)")
    rebindClusters()
    sendZigbeeCommands(zigbee.readAttribute(0x0000, 0x0004))
    runIn(intervalSeconds, "recoveryProbe")
}

void stopRecovery() {
    unschedule("recoveryProbe")
    state.recoveryActive = false
    logInfo("Recovery : Stopped")
}

void recoveryProbe() {
    // No early-exit check here — updateHealthStatus() unschedules this when a
    // frame arrives. Using healthStatus as a stop signal is unreliable for
    // devices that were silent at install (it stays at the seeded "online").
    logInfo("Recovery : Sending probe (readAttribute 0x0000/0x0004)")
    sendZigbeeCommands(zigbee.readAttribute(0x0000, 0x0004))
    runIn(recoveryIntervalForMode(), "recoveryProbe")
}

private int recoveryIntervalForMode() {
    switch (recoveryMode ?: "Normal") {
        case "Slow":       return 180
        case "Aggressive": return 30
        default:           return RECOVERY_PROBE_INTERVAL_SECONDS  // Normal
    }
}

private void rebindClusters() {
    logInfo("Recovery : Re-binding reporting clusters")
    List<String> cmds = []
    cmds += "zdo bind 0x${device.deviceNetworkId} 0x01 0x01 0x0000 {${device.zigbeeId}} {}"
    cmds += "zdo bind 0x${device.deviceNetworkId} 0x01 0x01 0x0402 {${device.zigbeeId}} {}"
    cmds += "zdo bind 0x${device.deviceNetworkId} 0x01 0x01 0x0403 {${device.zigbeeId}} {}"
    cmds += "zdo bind 0x${device.deviceNetworkId} 0x01 0x01 0x0405 {${device.zigbeeId}} {}"
    sendZigbeeCommands(cmds)
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

        // Re-bind if previous check-in was overdue (>90 min gap)
        if (state.lastCheckinMillis) {
            long millisSinceLastCheckin = now.time - state.lastCheckinMillis
            if (millisSinceLastCheckin > 90 * 60 * 1000) {
                logInfo "Recovery : Check-in was ${(millisSinceLastCheckin / 60000).intValue()} min overdue, re-binding clusters"
                rebindClusters()
            }
        }
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

    // IAS Zone — WSDCGQ11LM does not use IAS, log if any frame appears.
    if (descMap.clusterId == "0500") {
        logDebug "IAS Zone message (unexpected for this device): ${descMap}"
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
 *     [len][tag][type][value(little-endian)][tag][type][value]...
 *      1B   1B   1B    DataType.getLength(type) bytes
 *
 *   • len     — UINT8 byte count of everything that follows. Skipped.
 *   • tag     — 1-byte Xiaomi-proprietary attribute id (see table below).
 *   • type    — 1-byte ZCL data type; DataType.getLength() returns the value
 *               width in bytes (null for variable-length, which we abort on).
 *   • value   — little-endian; reverseHexString() flips to big-endian for parse.
 *
 * Tag table for lumi.weather (WSDCGQ11LM). Tags 0x64+ are reused across the
 * lumi.* family for different sensors — do not copy this table verbatim into
 * a sibling driver; cross-check Z2M's lumi.ts numericAttributes2Payload first.
 *
 *   0x01  battery voltage (mV, UINT16)
 *   0x03  device chip temperature (°C, INT8; internal NCP, not the sensor)
 *   0x04  unknown (present in every payload — purpose undocumented)
 *   0x05  power_outage_count (UINT16) — community drivers mislabel as RSSI dB
 *   0x06  Xiaomi proprietary cumulative counter (UINT40, model-specific) —
 *         Z2M calls this trigger_count on some models, unconfirmed for
 *         lumi.weather; community drivers mislabel as LQI
 *   0x07–0x09, 0x0B–0x0C  unknown
 *   0x0A  Zigbee parent DNI (network identifier)
 *   0x64  temperature (°C ×100)
 *   0x65  relative humidity (% ×100)
 *   0x66  atmospheric pressure (Pa)
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

    int strPosition = 2  // Skip the length-prefix byte.

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
                logTrace("$tagDebug (temperature)")
                parseTemperature(dataPayload)
                break
            case 0x65:
                logTrace("$tagDebug (humidity)")
                parseHumidity(dataPayload)
                break
            case 0x66:
                logTrace("$tagDebug (pressure)")
                parsePressure(dataPayload, true)
                break
            case 0x04: case 0x07: case 0x08: case 0x09: case 0x0B: case 0x0C:
                logTrace("$tagDebug (known unhandled)")
                break
            default:
                logDebug("$tagDebug (unexpected tag)")
        }
    }
}

private void parseAttributeReport(Map map) {
    logTrace("parseAttributeReport() : ${map}")

    // For the temp/pressure/humidity clusters only attr 0x0000 (MeasuredValue)
    // carries the sensor reading. WSDCGQ11LM also emits the pressure-cluster
    // metadata attrs 0x0010 (ScaledValue, INT16) and 0x0014 (Scale, INT8 = "FE"),
    // and the INT8 width would crash the 4-hex byte-flip with IndexOutOfBounds.
    if ((map.cluster == "0402" || map.cluster == "0403" || map.cluster == "0405") && map.attrId != "0000") {
        logDebug("parseAttributeReport() : ignoring cluster ${map.cluster} metadata attrId=${map.attrId} value=${map.value}")
        return
    }

    // descMap.value from parseDescriptionAsMap is already big-endian — the
    // platform flips the LE wire bytes for us. Pass straight through.
    switch (map.cluster) {
        case "0402":
            parseTemperature(map.value)
            return
        case "0403":
            parsePressure(map.value)
            return
        case "0405":
            parseHumidity(map.value)
            return
        case "0000":
            parseBasic(map)
            return
    }
    logUnhandledMessage(map)
}

private void parseBasic(Map map) {
    // ZCL Basic cluster (0x0000) attribute reports. attrId 0x0005 is overloaded:
    // it is the standard ZCL ModelIdentifier, but pressing the physical reset
    // button on the WSDCGQ11LM also surfaces as a frame on this attribute. Every
    // such frame fires a `pushed` event; model capture is additive when the
    // value is string-typed (encoding 0x42).

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
        case "0005":  // ModelIdentifier + Xiaomi button-press quirk — see comment above.
            logInfo("Trigger : Button Pressed (basic 0x0005, encoding=${encoding}, value=${value})")
            sendEvent(name: "pushed", value: 1, isStateChange: true)
            if (encoding == "42") {
                String model = hexToText(value)
                if (model) {
                    updateDataValue("model", model)
                    logDebug("ModelIdentifier : ${model}")
                }
            }
            break
        case "4000":  // SWBuildID (Character String)
            String sw = hexToText(value)
            if (sw) {
                updateDataValue("softwareBuildId", sw)
                logDebug("SWBuildID : ${sw}")
            }
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

private void parseTemperature(String temperatureFlippedHex) {
    BigDecimal temperature = BigDecimal.valueOf(hexStrToSignedInt(temperatureFlippedHex)) / 100

    logTrace("temperature : ${temperature} from hex value ${temperatureFlippedHex}")

    String temperatureScale = location.temperatureScale
    if (temperatureScale == "F") {
        temperature = (temperature * 1.8) + 32
    }

    if (tempOffset) {
        temperature = temperature + tempOffset
    }

    if (temperature > 200 || temperature < -200) {
        logWarn("Temperature : Value of ${temperature}°${temperatureScale} is unusual. Watch out for batteries failing on this device.")
    } else {
        BigDecimal rounded = temperature.setScale(1, RoundingMode.HALF_UP)
        logInfo("Temperature : ${rounded} °${temperatureScale}")
        sendEvent(name: "temperature", value: rounded, unit: "${temperatureScale}")
    }
}

private void parseHumidity(String humidityFlippedHex) {
    BigDecimal humidity = BigDecimal.valueOf(hexStrToSignedInt(humidityFlippedHex)) / 100

    if (humidityOffset) humidity = humidity + humidityOffset

    logTrace("humidity : ${humidity} from hex value ${humidityFlippedHex}")

    if (humidity > 100 || humidity < 0) {
        logWarn("Humidity : Value of ${humidity} is out of bounds. Watch out for batteries failing on this device.")
        return
    }

    BigDecimal humidityRounded = humidity.setScale(1, RoundingMode.HALF_UP)
    logInfo("Humidity (Relative) : ${humidityRounded} %")
    sendEvent(name: "humidity", value: humidityRounded, unit: "%")

    BigDecimal lastTemperature = device.currentState("temperature")?.value?.toBigDecimal()
    if (lastTemperature == null) {
        // First check-in or first humidity report before any temperature has
        // landed — skip absoluteHumidity rather than emit a 0°C-based value.
        logDebug("Skipping absoluteHumidity (no temperature reading yet)")
        return
    }

    String temperatureScale = location.temperatureScale
    if (temperatureScale == "F") {
        lastTemperature = (lastTemperature - 32) / 1.8
    }

    // Magnus-Tetens approximation for absolute humidity (g/m³); constants from
    // Sonntag (1990). RH is in %, lastTemperature in °C.
    BigDecimal numerator = (6.112 * Math.exp((17.67 * lastTemperature) / (lastTemperature + 243.5)) * humidity * 2.1674)
    BigDecimal denominator = lastTemperature + 273.15
    BigDecimal absoluteHumidity = (numerator / denominator).setScale(1, RoundingMode.HALF_UP)

    String cubedChar = String.valueOf((char)(179))
    logInfo("Humidity (Absolute) : ${absoluteHumidity} g/m${cubedChar}")
    sendEvent(name: "absoluteHumidity", value: absoluteHumidity, unit: "g/m${cubedChar}")
}

private void parsePressure(String pressureFlippedHex, boolean checkin = false) {
    BigDecimal pressurePa = hexStrToSignedInt(pressureFlippedHex)
    if (!checkin) {
        // Cluster 0x0403 value is in tenths of hPa → convert to Pa.
        // Check-in blob value is already in Pa.
        pressurePa = pressurePa * 10
    }

    // Convert Pa to display unit
    String unit = pressureUnits ?: "kPa"
    BigDecimal pressure
    switch (unit) {
        case "mbar":
            pressure = (pressurePa / 100).setScale(1, RoundingMode.HALF_UP)
            break
        case "inHg":
            pressure = ((pressurePa / 100) * 0.0295300).setScale(2, RoundingMode.HALF_UP)
            break
        case "mmHg":
            pressure = ((pressurePa / 100) * 0.750062).setScale(1, RoundingMode.HALF_UP)
            break
        default: // kPa
            unit = "kPa"
            pressure = (pressurePa / 1000).setScale(1, RoundingMode.HALF_UP)
            break
    }

    if (pressureOffset) {
        pressure = pressure + pressureOffset
    }

    BigDecimal lastPressure = device.currentState("pressure")?.value?.toBigDecimal()
    String pressureDirection = lastPressure == null
        ? "steady"
        : ["falling", "steady", "rising"][(pressure <=> lastPressure) + 1]

    logTrace("pressure : ${pressure} from hex value ${pressureFlippedHex}")
    logInfo("Pressure : ${pressure} ${unit} (${pressureDirection})")
    sendEvent(name: "pressure", value: pressure, unit: unit)
    sendEvent(name: "pressureDirection", value: pressureDirection)
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
    // Char-string attrs sometimes arrive already decoded (e.g. "lumi.weather")
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

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
 *  parseCheckin) was incorporated by PJ from veeceeoh's WSDCGQ11LM driver (Apache-2.0):
 *    https://github.com/veeceeoh/xiaomi-hubitat
 *  Inline `// Adapted from ...` attribution comments are preserved where
 *  applicable. Apache-2.0 is one-way GPL-3.0 compatible; the combined work
 *  is distributed under GPL-3.0-only while the veeceeoh-derived portions
 *  retain their original Apache-2.0 attribution requirements.
 *
 *  The mesh-recovery pattern (re-bind reporting clusters on disconnect, then
 *  periodically poll readAttribute(0x0000, 0x0004) until the device returns)
 *  was adapted from kkossev's Aqara/Zigbee driver work in the Hubitat
 *  community. Code was rewritten locally — this is idea attribution, not
 *  code derivation, and carries no additional license obligation.
 *
 *  Licensed under GPL-3.0-only (combined-work license, inherited from the
 *  BirdsLikeWires upstream). This per-file notice overrides the iamtrep
 *  repo's MIT default. Full license texts:
 *    GPL-3.0:    https://www.gnu.org/licenses/gpl-3.0.html
 *    Apache-2.0: https://www.apache.org/licenses/LICENSE-2.0
 */


import groovy.transform.Field
import groovy.transform.CompileStatic
import java.math.RoundingMode

@Field static final String DRIVER_VERSION = "v2.10 (4th June 2026)"

@Field static final int REPORT_INTERVAL_MINUTES = 60
@Field static final int CHECK_EVERY_MINUTES = 10
@Field static final int RECOVERY_PROBE_INTERVAL_SECONDS = 120

@Field static final Random RANDOM = new Random()


metadata {
    definition (
        name: "Aqara Weather Sensor WSDCGQ11LM",
        namespace: "iamtrep",
        author: "pj",
        singleThreaded: true,
        importUrl: "https://raw.githubusercontent.com/iamtrep/hubitat/main/drivers/Aqara_WSDCGQ11LM.groovy"
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

    // Migrate from PresenceSensor to healthStatus (one-time, idempotent on re-runs).
    if (state.presenceUpdated != null) {
        state.lastMessageMillis = state.presenceUpdated
        state.remove("presenceUpdated")
    }
    device.deleteCurrentState("presence")

    if (state.lastMessageMillis == null) state.lastMessageMillis = 0

    // Counters survive code pushes — only seed them when they don't already exist.
    if (device.currentValue("notPresentCounter") == null) sendEvent(name: "notPresentCounter", value: 0, isStateChange: false)
    if (device.currentValue("restoredCounter")  == null) sendEvent(name: "restoredCounter",  value: 0, isStateChange: false)
    sendEvent(name: "healthStatus", value: "online", isStateChange: false)

    // Schedule health checking with random jitter so multiple devices don't stampede.
    int randomSixty = RANDOM.nextInt(60)
    schedule("${randomSixty} 0/${CHECK_EVERY_MINUTES} * * * ? *", "checkHealth")

    // Record driver provenance + device-specific data.
    updateDataValue("driver", DRIVER_VERSION)
    updateDataValue("encoding", "Xiaomi")
    sendEvent(name: "numberOfButtons", value: 1, isStateChange: false)

    sendEvent(name: "configuration", value: "complete", isStateChange: false)
    state.remove("reconfigurePending")

    // Shed mislabelled keys from pre-v2.5 (RSSI/LQI were always power_outage_count
    // and a proprietary Xiaomi counter, not radio signal metrics).
    state.remove("RSSI")
    state.remove("LQI")

    logInfo "Initialized."
}

void updated() {
    // Runs when preferences are saved. Re-converge, then arm log-off.
    logInfo "Preferences Updated"
    logInfo "Info Logging:  ${txtEnable == true}"
    logInfo "Debug Logging: ${debugEnable == true}"
    logInfo "Trace Logging: ${traceEnable == true}"

    initialize()
    if (debugEnable || traceEnable) runIn(1800, "logsOff")
}

void configure() {
    // Exposed by the Configuration capability. WSDCGQ11LM relies on the pairing-time
    // reporting setup the device performs autonomously — no zigbee.configureReporting
    // is required here today. Future device-side setup belongs in this method.
    logInfo "Configuring."
    initialize()
}

void runVersionReconfigure() {
    // runInMillis target — keeps the reconfigure off the parser thread.
    logWarn "Driver upgraded from ${getDeviceDataByName('driver')} to ${DRIVER_VERSION}, reconfiguring."
    initialize()
}

// ─── Capability commands ───────────────────────────────────────────────────

void push(buttonId) {
    sendEvent(name:"pushed", value: buttonId, isStateChange:true)
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
    long millisNow = new Date().time
    int uptimeAllowanceMinutes = 20

    if (state.lastMessageMillis > 0) {
        long millisElapsed = millisNow - state.lastMessageMillis
        long timeoutMillis = ((REPORT_INTERVAL_MINUTES * 2) + 20) * 60000L
        long secondsElapsed = millisElapsed / 1000
        long hubUptime = location.hub.uptime

        if (millisElapsed > timeoutMillis) {
            if (hubUptime > uptimeAllowanceMinutes * 60) {
                if (device.currentValue("healthStatus") != "offline") {
                    sendEvent(name: "healthStatus", value: "offline")
                    int npc = (device.currentValue("notPresentCounter") ?: 0) + 1
                    sendEvent(name: "notPresentCounter", value: npc)
                }
                logWarn("Health : Offline. Last message ${secondsElapsed} seconds ago.")
                startRecovery()
            } else {
                logDebug("Health : Ignoring overdue reports for ${uptimeAllowanceMinutes} minutes after hub reboot (uptime ${hubUptime}s).")
            }
        } else {
            if (device.currentValue("healthStatus") != "online") {
                sendEvent(name: "healthStatus", value: "online")
            }
            logDebug("Health : Last message ${secondsElapsed} seconds ago.")
        }

        logTrace("checkHealth() : elapsed=${millisElapsed}ms, timeout=${timeoutMillis}ms")
    } else {
        logWarn("Health : Waiting for first message from device.")
    }
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

private void startRecovery() {
    String mode = recoveryMode ?: "Normal"
    if (mode == "Disabled" || state.recoveryActive) return

    int intervalSeconds = recoveryIntervalForMode()

    state.recoveryActive = true
    logInfo("Recovery : Starting ${mode} mode (every ${intervalSeconds}s)")
    rebindClusters()
    runIn(intervalSeconds, "recoveryProbe")
}

void recoveryProbe() {
    if (device.currentValue("healthStatus") == "online") {
        logDebug("Recovery : Device is present, stopping probes")
        state.recoveryActive = false
        return
    }
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

        // Re-bind if previous check-in was overdue (>90 min gap)
        if (state.lastCheckinMillis) {
            long millisSinceLastCheckin = new Date().time - state.lastCheckinMillis
            if (millisSinceLastCheckin > 90 * 60 * 1000) {
                logInfo "Recovery : Check-in was ${(millisSinceLastCheckin / 60000).intValue()} min overdue, re-binding clusters"
                rebindClusters()
            }
        }
        String updateTime = new Date().toLocaleString()
        state.lastCheckinMillis = new Date().time
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

    // IAS Zone enroll request — WSDCGQ11LM does not use IAS, log if it appears.
    if (descMap.clusterId == "0500" && descMap.command == "01") {
        logDebug "Received enroll request (unexpected for this device): ${descMap}"
        return
    }

    // IAS Zone status change notification — same: log if it appears.
    if (descMap.clusterId == "0500" && (descMap.command == "00" || descMap.attrId == "0002")) {
        logDebug "Zone status (unexpected for this device): ${descMap}"
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
    if (getDeviceDataByName('driver') != DRIVER_VERSION) {
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
                parseBattery(dataPayload, 1000, 2.8, 3.0)
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

    String receivedValue = map.value

    // For the temp/pressure/humidity clusters only attr 0x0000 (MeasuredValue)
    // carries the sensor reading. WSDCGQ11LM also emits the pressure-cluster
    // metadata attrs 0x0010 (ScaledValue, INT16) and 0x0014 (Scale, INT8 = "FE"),
    // and the INT8 width would crash the 4-hex byte-flip with IndexOutOfBounds.
    if ((map.cluster == "0402" || map.cluster == "0403" || map.cluster == "0405") && map.attrId != "0000") {
        logDebug("parseAttributeReport() : ignoring cluster ${map.cluster} metadata attrId=${map.attrId} value=${receivedValue}")
        return
    }

    if (map.cluster == "0402") {
        String temperatureFlippedHex = receivedValue[2..3] + receivedValue[0..1]
        logTrace("parseAttributeReport() : temperature ${temperatureFlippedHex}")
        parseTemperature(temperatureFlippedHex)
    } else if (map.cluster == "0403") {
        String pressureFlippedHex = receivedValue[2..3] + receivedValue[0..1]
        logTrace("parseAttributeReport() : pressure ${pressureFlippedHex}")
        parsePressure(pressureFlippedHex)
    } else if (map.cluster == "0405") {
        String humidityFlippedHex = receivedValue[2..3] + receivedValue[0..1]
        logTrace("parseAttributeReport() : humidity ${humidityFlippedHex}")
        parseHumidity(humidityFlippedHex)
    } else if (map.cluster == "0000") {
        parseBasic(map)
    } else {
        logUnhandledMessage(map)
    }
}

private void parseBasic(Map map) {
    // ZCL Basic cluster (0x0000) attribute reports. The Xiaomi 0xFF01 check-in
    // is handled earlier in parse() before parseDescriptionAsMap, so it never
    // reaches here.
    //
    // attrId 0x0005 is overloaded. It is the standard ZCL ModelIdentifier, but
    // pressing the physical reset button on the WSDCGQ11LM appears to cause
    // the device to re-announce its Basic cluster — and that re-announcement
    // surfaces as a frame on attr 0x0005. The original BLW dev noticed this
    // and made the button useful by emitting a `pushed` event on every such
    // frame. We preserve that behaviour (no encoding discriminator — we don't
    // have evidence that the button frame uses a different encoding than a
    // genuine model-read response, and silently breaking the button if we
    // guess wrong is the worst failure mode). Model capture below is purely
    // additive: a string-typed value updates the model data value too, with
    // the button event firing either way.

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

    String dataCount = (map.data != null) ? "${map.data.length} bits of " : ""
    logWarn("UNKNOWN DATA - Please report these messages to the developer.")
    logWarn("Received : endpoint: ${map.endpoint}, cluster: ${map.cluster}, clusterId: ${map.clusterId}, attrId: ${map.attrId}, command: ${map.command} with value: ${map.value} and ${dataCount}data: ${map.data}")
    logTrace("Full message map : ${map}")
}

// ─── Sensor value processors ───────────────────────────────────────────────

private void parseTemperature(String temperatureFlippedHex) {
    BigDecimal temperature = hexStrToSignedInt(temperatureFlippedHex)
    temperature = temperature.setScale(2, BigDecimal.ROUND_HALF_UP) / 100

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
        logInfo("Temperature : ${temperature} °${temperatureScale}")
        sendEvent(name: "temperature", value: temperature.setScale(2, BigDecimal.ROUND_HALF_UP), unit: "${temperatureScale}")
    }
}

private void parseHumidity(String humidityFlippedHex) {
    BigDecimal humidity = hexStrToSignedInt(humidityFlippedHex)
    humidity = humidity.setScale(2, BigDecimal.ROUND_HALF_UP) / 100

    if (humidityOffset) humidity = humidity + humidityOffset

    logTrace("humidity : ${humidity} from hex value ${humidityFlippedHex}")

    if (humidity > 100 || humidity < 0) {
        logWarn("Humidity : Value of ${humidity} is out of bounds. Watch out for batteries failing on this device.")
        return
    }

    logInfo("Humidity (Relative) : ${humidity} %")
    sendEvent(name: "humidity", value: humidity, unit: "%")

    def tempState = device.currentState("temperature")
    if (tempState == null) {
        // First check-in or first humidity report before any temperature has
        // landed — skip absoluteHumidity rather than emit a 0°C-based value.
        logDebug("Skipping absoluteHumidity (no temperature reading yet)")
        return
    }

    BigDecimal lastTemperature = tempState.value.toBigDecimal()
    String temperatureScale = location.temperatureScale
    if (temperatureScale == "F") {
        lastTemperature = (lastTemperature - 32) / 1.8
    }

    // Magnus-Tetens approximation for absolute humidity (g/m³); constants from
    // Sonntag (1990). RH is in %, lastTemperature in °C.
    BigDecimal numerator = (6.112 * Math.exp((17.67 * lastTemperature) / (lastTemperature + 243.5)) * humidity * 2.1674)
    BigDecimal denominator = lastTemperature + 273.15
    BigDecimal absoluteHumidity = (numerator / denominator).setScale(1, BigDecimal.ROUND_HALF_UP)

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
            pressure = (pressurePa / 100).setScale(1, BigDecimal.ROUND_HALF_UP)
            break
        case "inHg":
            pressure = ((pressurePa / 100) * 0.0295300).setScale(2, BigDecimal.ROUND_HALF_UP)
            break
        case "mmHg":
            pressure = ((pressurePa / 100) * 0.750062).setScale(1, BigDecimal.ROUND_HALF_UP)
            break
        default: // kPa
            unit = "kPa"
            pressure = (pressurePa / 1000).setScale(2, BigDecimal.ROUND_HALF_UP)
            break
    }

    if (pressureOffset) {
        pressure = pressure + pressureOffset
    }

    BigDecimal lastPressure = device.currentState("pressure")?.value?.toBigDecimal()
    String pressureDirection
    if (lastPressure == null) {
        pressureDirection = "steady"
    } else if (pressure > lastPressure) {
        pressureDirection = "rising"
    } else if (pressure < lastPressure) {
        pressureDirection = "falling"
    } else {
        pressureDirection = "steady"
    }

    logTrace("pressure : ${pressure} from hex value ${pressureFlippedHex}")
    logInfo("Pressure : ${pressure} ${unit} (${pressureDirection})")
    sendEvent(name: "pressure", value: pressure, unit: unit)
    sendEvent(name: "pressureDirection", value: pressureDirection)
}

private void parseBattery(String batteryVoltageHex, int batteryVoltageDivisor, BigDecimal batteryVoltageScaleMin, BigDecimal batteryVoltageScaleMax) {
    // Report the battery voltage and calculated percentage.
    logTrace("batteryVoltageHex : ${batteryVoltageHex}")

    int rawMv = zigbee.convertHexToInt(batteryVoltageHex)
    logDebug("batteryVoltage raw value : ${rawMv}")

    BigDecimal batteryVoltage = BigDecimal.valueOf(rawMv).divide(BigDecimal.valueOf(batteryVoltageDivisor), 2, RoundingMode.HALF_UP)

    logDebug("batteryVoltage : ${batteryVoltage}")
    sendEvent(name: "batteryVoltage", value: batteryVoltage, unit: "V")

    if (batteryVoltage >= batteryVoltageScaleMin) {
        BigDecimal batteryPercentage = ((batteryVoltage - batteryVoltageScaleMin) / (batteryVoltageScaleMax - batteryVoltageScaleMin)) * 100.0
        batteryPercentage = batteryPercentage.setScale(0, BigDecimal.ROUND_HALF_UP)
        if (batteryPercentage > 100) batteryPercentage = 100
        if (batteryPercentage < 0)   batteryPercentage = 0

        if (batteryPercentage > 20) {
            logInfo("Battery : $batteryPercentage% ($batteryVoltage V)")
        } else {
            logWarn("Battery : $batteryPercentage% ($batteryVoltage V)")
        }

        sendEvent(name: "battery", value: batteryPercentage, unit: "%")
        state.batteryStatus = "discharging"
    } else {
        // Very low voltages indicate an exhausted battery which requires replacement.

        logWarn("Battery : Exhausted battery requires replacement.")
        logWarn("Battery : 0% ($batteryVoltage V)")
        sendEvent(name: "battery", value: 0, unit: "%")
        state.batteryStatus = "exhausted"
    }
}

// ─── Zigbee command primitives ─────────────────────────────────────────────

private void sendZigbeeCommands(List<String> cmds) {
    // All hub commands go through here for immediate transmission and to avoid some method() weirdness.

    logTrace("sendZigbeeCommands received : ${cmds}")
    sendHubCommand(new hubitat.device.HubMultiAction(cmds, hubitat.device.Protocol.ZIGBEE))
}

// ─── Pure-computation utilities ────────────────────────────────────────────

@CompileStatic
private String reverseHexString(String hexString) {
    String reversed = ""
    for (int i = hexString.length(); i > 0; i -= 2) {
        reversed += hexString.substring(i - 2, i)
    }
    return reversed
}

// Decode a ZCL Character String hex payload to ASCII text. Skips non-printable
// bytes — including the leading 1-byte length prefix that ZCL prepends to
// character strings — so the result is the readable content only.
@CompileStatic
private String hexToText(String hex) {
    if (!hex) return ""
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
    log.warn "${device} : Auto-disabling debug + trace logging"
    device.updateSetting("debugEnable", [value:"false", type:"bool"])
    device.updateSetting("traceEnable", [value:"false", type:"bool"])
}

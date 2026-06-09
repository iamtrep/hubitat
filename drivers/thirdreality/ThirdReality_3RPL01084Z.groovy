// Copyright (c) 2025-2026 PJ
// SPDX-License-Identifier: MIT

/*
 *  ThirdReality Presence Sensor R3 (3RPL01084Z) Driver for Hubitat Elevation
 *
 *  60 GHz mmWave presence sensor with RGB night light, illuminance sensor, and TVOC air quality sensor.
 *  Zigbee 3.0 (profile 0x0104), single endpoint, router device.
 *
 *  Reference implementations:
 *    - ThirdReality ZHA quirk (manufacturer): thirdreality/homeassistant/ZHA(quirks v2)/Multi-Function_Smart_Presence_Sensor_R3.py
 *    - Zigbee2MQTT: zigbee-herdsman-converters/src/devices/third_reality.ts
 *    - ZHA community: zha-device-handlers/zhaquirks/thirdreality/60g_radar.py
 */

import groovy.transform.Field
import groovy.transform.CompileStatic

@Field static final String CODE_VERSION = "0.2.7"

// Custom cluster for radar config and TVOC
@Field static final int CLUSTER_RADAR = 0x042E
@Field static final String MFG_CODE = "0x1407"

// Manufacturer-specific attribute IDs on CLUSTER_RADAR
@Field static final int ATTR_TVOC               = 0x0000  // IEEE 754 single, ppb
@Field static final int ATTR_TVOC_CALIBRATE     = 0xF001  // uint8 write-1 to recalibrate baseline
@Field static final int ATTR_DETECT_DISTANCE    = 0xF002  // uint8 1..6 (meters)
@Field static final int ATTR_TVOC_THRESHOLD     = 0xF003  // uint16 3000..50000 ppb
@Field static final int ATTR_MOTION_SENS        = 0xF004  // uint8 0..20
@Field static final int ATTR_PRESENCE_SENS      = 0xF005  // uint8 0..20
@Field static final int ATTR_HOLD_TIME          = 0xF006  // uint8 1..6 (per TR firmware 1.00.35)
@Field static final int ATTR_TVOC_ALERT_ENABLE  = 0xF007  // uint8 0/1

// Standard ZCL attributes used for bulb features
@Field static final int ATTR_ONOFF_TRANSITION   = 0x0010  // cluster 0x0008, UINT16 tenths
@Field static final int ATTR_ON_LEVEL           = 0x0011  // cluster 0x0008, UINT8 1..254 (FF = previous)
@Field static final int ATTR_STARTUP_LEVEL      = 0x4000  // cluster 0x0008, UINT8 (FF = previous)
@Field static final int ATTR_COLOR_TEMP_MIREDS  = 0x0007  // cluster 0x0300, UINT16 mireds
@Field static final int ATTR_COLOR_MODE         = 0x0008  // cluster 0x0300, ENUM8 0=HS 1=xy 2=CT
@Field static final int ATTR_ENHANCED_MODE      = 0x4001  // cluster 0x0300, ENUM8 (incl. enhanced HS)
@Field static final int ATTR_STARTUP_CT_MIREDS  = 0x4010  // cluster 0x0300, UINT16 (FFFF = previous)

// Color-temperature range advertised by the device (per Z2M)
@Field static final int CT_MIN_MIREDS = 154  // ≈ 6500K
@Field static final int CT_MAX_MIREDS = 500  // ≈ 2000K
@Field static final int CT_MIN_KELVIN = 2000
@Field static final int CT_MAX_KELVIN = 6500

// Hue value (0-100) to color name mapping
@Field static final Map colorRGBName = [
    4: "Red", 13: "Orange", 21: "Yellow", 29: "Chartreuse",
    38: "Green", 46: "Spring", 54: "Cyan", 63: "Azure",
    71: "Blue", 79: "Violet", 88: "Magenta", 96: "Rose", 101: "Red"
]

metadata {
    definition(
        name: "ThirdReality Presence Sensor R3 (3RPL01084Z)",
        namespace: "iamtrep",
        author: "pj",
        description: "60 GHz mmWave presence sensor with RGB night light, illuminance, and TVOC air quality (Zigbee 3.0)",
        singleThreaded: true,
        importUrl: "https://raw.githubusercontent.com/iamtrep/hubitat/refs/heads/main/drivers/thirdreality/ThirdReality_3RPL01084Z.groovy"
    ) {
        capability "Configuration"
        capability "Refresh"

        capability "MotionSensor"
        capability "IlluminanceMeasurement"

        capability "Light"
        capability "Bulb"
        capability "Switch"
        capability "SwitchLevel"
        capability "ChangeLevel"
        capability "ColorControl"
        capability "ColorTemperature"
        capability "ColorMode"

        attribute "colorName", "string"

        attribute "tvoc", "number"

        command "resetTVOCCalibration", [[name: "Reset the TVOC sensor calibration baseline"]]

        fingerprint profileId: "0104", endpointId: "01",
            inClusters: "0000,0003,0004,0005,0006,0008,0300,0400,0406,042E,1000",
            outClusters: "0019",
            manufacturer: "Third Reality, Inc", model: "3RPL01084Z",
            deviceJoinName: "ThirdReality Presence Sensor R3"
    }

    preferences {
        input name: "detectDistance", type: "enum", title: "Detection Distance (m)",
            options: ["1": "1 m", "2": "2 m", "3": "3 m", "4": "4 m (default)", "5": "5 m", "6": "6 m"],
            defaultValue: "4", description: "mmWave radar effective detection range (1-6 meters)"

        input name: "presenceSensitivity", type: "number", title: "Presence Sensitivity",
            defaultValue: 10, range: "0..20",
            description: "Presence detection sensitivity (0-20, higher = more sensitive)"

        input name: "motionSensitivity", type: "number", title: "Motion Sensitivity",
            defaultValue: 10, range: "0..20",
            description: "Motion detection sensitivity (0-20, higher = more sensitive)"

        // ZHA/Z2M references advertise 1-4; ThirdReality firmware 1.00.35 release notes
        // document 1-6 ("Presence exit time"). Trusting the manufacturer's spec.
        input name: "presenceHoldTime", type: "enum", title: "Presence Exit Time",
            options: ["1": "1 (shortest)", "2": "2", "3": "3", "4": "4", "5": "5", "6": "6 (longest)"],
            defaultValue: "2", description: "How long presence stays active after motion stops"

        input name: "airQualityThreshold", type: "number", title: "TVOC Alarm Threshold (ppb)",
            defaultValue: 6000, range: "3000..50000",
            description: "TVOC level above which the on-device alarm LED is triggered (3000-50000 ppb)"

        input name: "tvocAlertEnable", type: "bool", title: "TVOC Alarm LED",
            defaultValue: true, description: "Enable the red on-device LED that blinks when TVOC exceeds the alarm threshold"

        input name: "powerOnBehavior", type: "enum", title: "Light Power-On Behavior",
            options: ["0": "Off", "1": "On", "2": "Toggle", "255": "Previous"],
            defaultValue: "0", description: "RGB light state after power is restored"

        input name: "onLevel", type: "number", title: "Power-on Level (%)",
            range: "1..100", required: false,
            description: "Level when turned on from off (blank = device default / previous level)"

        input name: "startUpLevel", type: "number", title: "Start-up Level (%) after power restore",
            range: "0..100", required: false,
            description: "0 = minimum, blank = previous level"

        input name: "onTransitionTime", type: "number", title: "On/Off Transition Time (seconds)",
            range: "0..30", defaultValue: 0,
            description: "Default fade time for on/off and level changes"

        input name: "startUpColorTempK", type: "number", title: "Start-up Color Temperature (K)",
            range: "${CT_MIN_KELVIN}..${CT_MAX_KELVIN}", required: false,
            description: "${CT_MIN_KELVIN}-${CT_MAX_KELVIN} K, blank = previous color"

        input name: "txtEnable", type: "bool", title: "Enable descriptionText logging", defaultValue: true
        input name: "debugEnable", type: "bool", title: "Enable debug logging info", defaultValue: false, required: true, submitOnChange: true
        if (debugEnable) {
            input name: "traceEnable", type: "bool", title: "Enable trace logging info (for development purposes)", defaultValue: false
        }
    }
}

// Lifecycle

void installed() {
    logDebug "installed()"
    state.codeVersion = CODE_VERSION
    updated()
}

void updated() {
    logDebug "updated()"
    unschedule()

    if (debugEnable) runIn(1800, "logsOff")

    List<String> cmds = buildPreferenceWrites()
    if (cmds) sendZigbeeCommands(cmds)

    runInMillis(1000, "configure")
}

void deviceTypeUpdated() {
    logWarn "driver change detected"
    updated()
}

void logsOff() {
    logWarn "debug logging disabled"
    device.updateSetting("debugEnable", [value: "false", type: "bool"])
    device.updateSetting("traceEnable", [value: "false", type: "bool"])
}

private List<String> buildPreferenceWrites() {
    List<String> cmds = []

    if (settings.detectDistance != null) {
        cmds += zigbee.writeAttribute(CLUSTER_RADAR, ATTR_DETECT_DISTANCE, DataType.UINT8,
            settings.detectDistance as int, [mfgCode: MFG_CODE])
    }
    if (settings.presenceSensitivity != null) {
        cmds += zigbee.writeAttribute(CLUSTER_RADAR, ATTR_PRESENCE_SENS, DataType.UINT8,
            settings.presenceSensitivity as int, [mfgCode: MFG_CODE])
    }
    if (settings.motionSensitivity != null) {
        cmds += zigbee.writeAttribute(CLUSTER_RADAR, ATTR_MOTION_SENS, DataType.UINT8,
            settings.motionSensitivity as int, [mfgCode: MFG_CODE])
    }
    if (settings.presenceHoldTime != null) {
        cmds += zigbee.writeAttribute(CLUSTER_RADAR, ATTR_HOLD_TIME, DataType.UINT8,
            settings.presenceHoldTime as int, [mfgCode: MFG_CODE])
    }
    if (settings.airQualityThreshold != null) {
        cmds += zigbee.writeAttribute(CLUSTER_RADAR, ATTR_TVOC_THRESHOLD, DataType.UINT16,
            settings.airQualityThreshold as int, [mfgCode: MFG_CODE])
    }
    if (settings.tvocAlertEnable != null) {
        cmds += zigbee.writeAttribute(CLUSTER_RADAR, ATTR_TVOC_ALERT_ENABLE, DataType.UINT8,
            settings.tvocAlertEnable ? 1 : 0, [mfgCode: MFG_CODE])
    }
    if (settings.powerOnBehavior != null) {
        cmds += zigbee.writeAttribute(0x0006, 0x4003, DataType.ENUM8, settings.powerOnBehavior as int)
    }

    if (settings.onLevel != null) {
        int pct = Math.max(1, Math.min(100, settings.onLevel as int))
        int raw = Math.max(1, Math.min(254, Math.round(pct * 2.54) as int))
        cmds += zigbee.writeAttribute(0x0008, ATTR_ON_LEVEL, DataType.UINT8, raw)
    }

    if (settings.startUpLevel != null) {
        int pct = Math.max(0, Math.min(100, settings.startUpLevel as int))
        int raw = pct == 0 ? 0 : Math.min(254, Math.round(pct * 2.54) as int)
        cmds += zigbee.writeAttribute(0x0008, ATTR_STARTUP_LEVEL, DataType.UINT8, raw)
    }

    if (settings.onTransitionTime != null) {
        int tenths = Math.max(0, Math.min(300, (settings.onTransitionTime as int) * 10))
        cmds += zigbee.writeAttribute(0x0008, ATTR_ONOFF_TRANSITION, DataType.UINT16, tenths)
    }

    if (settings.startUpColorTempK != null) {
        int kelvin = Math.max(CT_MIN_KELVIN, Math.min(CT_MAX_KELVIN, settings.startUpColorTempK as int))
        int mireds = kelvinToMireds(kelvin)
        cmds += zigbee.writeAttribute(0x0300, ATTR_STARTUP_CT_MIREDS, DataType.UINT16, mireds)
    }

    return cmds
}

void configure() {
    logTrace "configure()"

    state.codeVersion = CODE_VERSION

    List<String> cmds = []

    // Bind clusters
    cmds += "zdo bind 0x${device.deviceNetworkId} 1 1 0x0406 {${device.zigbeeId}} {}"  // Occupancy
    cmds += "zdo bind 0x${device.deviceNetworkId} 1 1 0x0400 {${device.zigbeeId}} {}"  // Illuminance
    cmds += "zdo bind 0x${device.deviceNetworkId} 1 1 0x0006 {${device.zigbeeId}} {}"  // OnOff
    cmds += "zdo bind 0x${device.deviceNetworkId} 1 1 0x0008 {${device.zigbeeId}} {}"  // Level
    cmds += "zdo bind 0x${device.deviceNetworkId} 1 1 0x0300 {${device.zigbeeId}} {}"  // Color
    cmds += "zdo bind 0x${device.deviceNetworkId} 1 1 0x${Integer.toHexString(CLUSTER_RADAR)} {${device.zigbeeId}} {}"  // Radar

    // Configure reporting
    cmds += zigbee.configureReporting(0x0406, 0x0000, DataType.BITMAP8, 0, 3600, 1)    // Occupancy
    cmds += zigbee.configureReporting(0x0400, 0x0000, DataType.UINT16, 10, 3600, 100)  // Illuminance
    cmds += zigbee.configureReporting(0x0006, 0x0000, DataType.BOOLEAN, 0, 3600)       // OnOff
    cmds += zigbee.configureReporting(0x0008, 0x0000, DataType.UINT8, 1, 3600, 1)      // Level
    cmds += zigbee.configureReporting(0x0300, ATTR_COLOR_TEMP_MIREDS, DataType.UINT16, 1, 3600, 1)
    cmds += zigbee.configureReporting(0x0300, ATTR_COLOR_MODE, DataType.ENUM8, 0, 3600)

    sendZigbeeCommands(cmds)

    runIn(2, "refresh")
}

void refresh() {
    logDebug "refresh()"

    List<String> cmds = []

    cmds += zigbee.readAttribute(0x0406, 0x0000)  // Occupancy
    cmds += zigbee.readAttribute(0x0400, 0x0000)  // Illuminance
    cmds += zigbee.readAttribute(0x0006, 0x0000)  // OnOff
    cmds += zigbee.readAttribute(0x0008, [0x0000, ATTR_ON_LEVEL, ATTR_STARTUP_LEVEL, ATTR_ONOFF_TRANSITION])
    cmds += zigbee.readAttribute(0x0300, [0x0000, 0x0001, ATTR_COLOR_TEMP_MIREDS, ATTR_COLOR_MODE, ATTR_ENHANCED_MODE, ATTR_STARTUP_CT_MIREDS])
    cmds += zigbee.readAttribute(CLUSTER_RADAR,
        [ATTR_TVOC, ATTR_DETECT_DISTANCE, ATTR_TVOC_THRESHOLD, ATTR_MOTION_SENS, ATTR_PRESENCE_SENS, ATTR_HOLD_TIME, ATTR_TVOC_ALERT_ENABLE],
        [mfgCode: MFG_CODE])

    sendZigbeeCommands(cmds)
}

// Parse

void parse(String description) {
    if (state.codeVersion != CODE_VERSION) {
        state.codeVersion = CODE_VERSION
        runInMillis(1500, "autoConfigure")
    }

    Map descMap = zigbee.parseDescriptionAsMap(description)
    logTrace "parse() - ${descMap}"

    if (descMap.attrId != null) {
        parseAttributeReport(descMap)
        descMap.additionalAttrs?.each { add ->
            add.cluster = descMap.cluster
            parseAttributeReport(add)
        }
    } else if (descMap.profileId == "0000") {
        logTrace "Unhandled ZDO command: cluster=${descMap.clusterId} command=${descMap.command}"
    } else if (descMap.profileId == "0104" && descMap.clusterId != null) {
        logTrace "Unhandled ZHA global command: cluster=${descMap.clusterId} command=${descMap.command}"
    } else if (description?.startsWith("enroll request")) {
        logTrace "enroll request: ${description}"
    } else if (description?.startsWith("zone status") || description?.startsWith("zone report")) {
        logTrace "zone status/report: ${description}"
    } else {
        logDebug "Unhandled message: ${descMap}"
    }
}

private void parseAttributeReport(Map descMap) {
    // ZCL Read Attributes Response with status != SUCCESS (e.g. 0x86 unsupported
    // attribute on pre-1.00.35 firmware for F004-F007) arrives with attrId set
    // but value null. Bail before any case tries to parse it.
    if (descMap.value == null) {
        logDebug "No value: cluster=${descMap.cluster} attrId=${descMap.attrId} status=${descMap.status}"
        return
    }

    Map map = [:]

    switch (descMap.cluster) {
        case "0000":  // Basic cluster
            break

        case "0006":  // OnOff
            if (descMap.attrId == "0000") {
                map.name = "switch"
                map.value = descMap.value == "00" ? "off" : "on"
                map.descriptionText = "${device.displayName} light is ${map.value}"
            }
            break

        case "0008":  // Level Control
            switch (descMap.attrId) {
                case "0000":
                    int rawLevel = Integer.parseInt(descMap.value, 16)
                    int level = Math.round(rawLevel / 2.55) as int
                    level = Math.max(0, Math.min(100, level))
                    map.name = "level"
                    map.value = level
                    map.unit = "%"
                    map.descriptionText = "${device.displayName} light level is ${level}%"
                    break
                case "0010":  // OnOffTransitionTime readback (tenths)
                    int tenths = Integer.parseInt(descMap.value, 16)
                    int seconds = Math.round(tenths / 10.0d) as int
                    logDebug "OnOff transition readback: ${tenths / 10.0d}s → setting ${seconds}s"
                    device.updateSetting("onTransitionTime", [value: seconds, type: "number"])
                    break
                case "0011":  // OnLevel readback (1..254 or FF)
                    int raw = Integer.parseInt(descMap.value, 16)
                    if (raw != 0xFF) {
                        int pct = Math.max(1, Math.min(100, Math.round(raw / 2.54) as int))
                        logDebug "OnLevel readback: ${pct}% (raw=${raw})"
                        device.updateSetting("onLevel", [value: pct, type: "number"])
                    } else {
                        logDebug "OnLevel readback: previous-level mode (FF)"
                    }
                    break
                case "4000":  // StartUpCurrentLevel readback
                    int raw = Integer.parseInt(descMap.value, 16)
                    if (raw != 0xFF) {
                        int pct = raw == 0 ? 0 : Math.max(1, Math.min(100, Math.round(raw / 2.54) as int))
                        logDebug "Start-up level readback: ${pct}% (raw=${raw})"
                        device.updateSetting("startUpLevel", [value: pct, type: "number"])
                    } else {
                        logDebug "Start-up level readback: previous-level mode (FF)"
                    }
                    break
            }
            break

        case "0300":  // Color Control
            map = parseColorAttribute(descMap)
            break

        case "0400":  // Illuminance Measurement
            if (descMap.attrId == "0000") {
                int rawValue = Integer.parseInt(descMap.value, 16)
                int lux = rawValue > 0 ? Math.round(Math.pow(10, (rawValue - 1) / 10000.0)) as int : 0
                map.name = "illuminance"
                map.value = lux
                map.unit = "lux"
                map.descriptionText = "${device.displayName} illuminance is ${lux} lux"
            }
            break

        case "0406":  // Occupancy Sensing
            if (descMap.attrId == "0000") {
                int occupancy = Integer.parseInt(descMap.value, 16)
                String motion = (occupancy & 0x01) ? "active" : "inactive"
                map.name = "motion"
                map.value = motion
                map.descriptionText = "${device.displayName} motion is ${motion}"
            }
            break

        case "042E":  // Custom radar cluster
            map = parseRadarCluster(descMap)
            break

        default:
            logDebug "Unhandled cluster ${descMap.cluster} attrId ${descMap.attrId} value ${descMap.value}"
            break
    }

    if (map?.name) {
        logInfo "${map.descriptionText}"
        sendEvent(map)
    }
}

private Map parseColorAttribute(Map descMap) {
    Map map = [:]
    int rawValue = Integer.parseInt(descMap.value, 16)

    switch (descMap.attrId) {
        case "0000":  // Current Hue (0-254 → 0-100)
            int hue = Math.round(rawValue / 254 * 100) as int
            map.name = "hue"
            map.value = hue
            map.unit = "%"
            map.descriptionText = "${device.displayName} hue is ${hue}%"
            sendColorNameEvent(hue)
            sendColorEvent(hue, null)
            break

        case "0001":  // Current Saturation (0-254 → 0-100)
            int saturation = Math.round(rawValue / 254 * 100) as int
            map.name = "saturation"
            map.value = saturation
            map.unit = "%"
            map.descriptionText = "${device.displayName} saturation is ${saturation}%"
            sendColorNameEvent(null, saturation)
            sendColorEvent(null, saturation)
            break

        case "0007":  // ColorTemperatureMireds — 0x0000 and 0xFFFF are ZCL "undefined" sentinels
            if (rawValue <= 0 || rawValue == 0xFFFF) {
                logDebug "ColorTemperatureMireds reads as sentinel ${rawValue} — device has no CT set"
                break
            }
            int kelvin = miredsToKelvin(rawValue)
            map.name = "colorTemperature"
            map.value = kelvin
            map.unit = "K"
            map.descriptionText = "${device.displayName} color temperature is ${kelvin}K"
            sendColorTempName(kelvin)
            break

        case "0008":   // ColorMode (0=HS, 1=xy, 2=CT)
        case "4001":   // EnhancedColorMode (adds 3=enhancedHS)
            String mode = (rawValue == 2) ? "CT" : "RGB"
            if (device.currentValue("colorMode") != mode) {
                sendEvent(name: "colorMode", value: mode,
                          descriptionText: "${device.displayName} color mode is ${mode}")
            }
            break

        case "4010":  // StartUpColorTemperatureMireds — silent readback
            logDebug "Start-up color temperature mireds readback: ${rawValue}"
            break
    }

    return map
}

private void sendColorNameEvent(Integer hue, Integer sat = null) {
    String colorName
    if (sat == 0 || (sat == null && device.currentValue("saturation") == 0)) {
        colorName = "White"
    } else if (hue == null) {
        return
    } else {
        colorName = colorRGBName.find { k, v -> hue < k }?.value ?: "Red"
    }
    if (colorName == device.currentValue("colorName")) return
    String descriptionText = "${device.displayName} color is ${colorName}"
    logInfo descriptionText
    sendEvent(name: "colorName", value: colorName, descriptionText: descriptionText)
}

private Map parseRadarCluster(Map descMap) {
    Map map = [:]

    switch (descMap.attrId) {
        case "0000":  // TVOC — IEEE 754 single (ppb), per ThirdReality ZHA quirk
            Integer tvocInt = decodeTvocFloat(descMap.value)
            if (tvocInt == null) {
                logWarn "Could not parse TVOC value: ${descMap.value}"
                return map
            }
            map.name = "tvoc"
            map.value = tvocInt
            map.unit = "ppb"
            map.descriptionText = "${device.displayName} TVOC is ${tvocInt} ppb"
            break

        case "F002":  // Detection distance readback (1-6)
            int distance = Integer.parseInt(descMap.value, 16)
            logDebug "Detection distance readback: ${distance}"
            device.updateSetting("detectDistance", [value: "${distance}", type: "enum"])
            break

        case "F003":  // Air quality threshold readback
            int threshold = Integer.parseInt(descMap.value, 16)
            logDebug "Air quality threshold readback: ${threshold}"
            device.updateSetting("airQualityThreshold", [value: threshold, type: "number"])
            break

        case "F004":  // Motion sensitivity readback (0-20)
            int sens = Integer.parseInt(descMap.value, 16)
            logDebug "Motion sensitivity readback: ${sens}"
            device.updateSetting("motionSensitivity", [value: sens, type: "number"])
            break

        case "F005":  // Presence sensitivity readback (0-20)
            int sens = Integer.parseInt(descMap.value, 16)
            logDebug "Presence sensitivity readback: ${sens}"
            device.updateSetting("presenceSensitivity", [value: sens, type: "number"])
            break

        case "F006":  // Presence hold time readback (1-4)
            int hold = Integer.parseInt(descMap.value, 16)
            logDebug "Presence hold time readback: ${hold}"
            device.updateSetting("presenceHoldTime", [value: "${hold}", type: "enum"])
            break

        case "F007":  // TVOC alert enable readback
            int enabled = Integer.parseInt(descMap.value, 16)
            logDebug "TVOC alert enable readback: ${enabled}"
            device.updateSetting("tvocAlertEnable", [value: enabled != 0, type: "bool"])
            break

        default:
            logDebug "Unknown radar cluster attribute ${descMap.attrId} value ${descMap.value}"
            break
    }

    return map
}

private Integer decodeTvocFloat(String hex) {
    if (hex == null || hex.length() != 8) return null
    try {
        int bits = (int) Long.parseLong(hex, 16)
        float v = Float.intBitsToFloat(bits)
        if (Float.isNaN(v) || Float.isInfinite(v)) return null
        return Math.max(0, Math.round(v)) as Integer
    } catch (NumberFormatException e) {
        return null
    }
}

// Light Commands

// Chain an explicit readAttribute after the on/off/setLevel command. Without
// it, a no-op command (device already in target state/level) emits a Default
// Response but no on-change attribute report — and the platform's
// command-retry watchdog gives up after 5 retries. The read is a directed
// query the device must answer regardless of state transition.
void on() {
    logDebug "on()"
    List<String> cmds = []
    cmds += zigbee.on()
    cmds += zigbee.readAttribute(0x0006, 0x0000)
    sendZigbeeCommands(cmds)
}

void off() {
    logDebug "off()"
    List<String> cmds = []
    cmds += zigbee.off()
    cmds += zigbee.readAttribute(0x0006, 0x0000)
    sendZigbeeCommands(cmds)
}

void setLevel(BigDecimal level, BigDecimal duration = 0) {
    logDebug "setLevel(${level}, ${duration})"
    List<String> cmds = []
    cmds += zigbee.setLevel(level.intValue(), duration.intValue())
    cmds += zigbee.readAttribute(0x0008, 0x0000)
    cmds += zigbee.readAttribute(0x0006, 0x0000)  // setLevel(0) turns off; setLevel(>0) from off turns on
    sendZigbeeCommands(cmds)
}

void setLevel(Number level, Number duration = 0) {
    setLevel(level as BigDecimal, duration as BigDecimal)
}

void startLevelChange(String direction) {
    logDebug "startLevelChange(${direction})"
    int upDown = direction == "down" ? 1 : 0
    // Move command (0x01): direction + rate (units per second)
    sendZigbeeCommands(zigbee.command(0x0008, 0x01, zigbee.convertToHexString(upDown, 2) + zigbee.convertToHexString(100, 2)))
}

void stopLevelChange() {
    logDebug "stopLevelChange()"
    sendZigbeeCommands(zigbee.command(0x0008, 0x03, ""))
}

void setColor(Map value) {
    logDebug "setColor(${value})"
    if (value.hue == null || value.saturation == null) return

    String hexHue = pctToZigbeeHex(value.hue as Number)
    String hexSat = pctToZigbeeHex(value.saturation as Number)

    // moveToHueAndSaturation (command 0x06): hue (1 byte) + saturation (1 byte) + transition time (UINT16 LE tenths)
    List<String> cmds = []
    cmds += zigbee.command(0x0300, 0x06, hexHue + hexSat + uint16LE(0))

    if (value.level) {
        cmds += zigbee.setLevel(value.level.toInteger(), 0)
        cmds += zigbee.readAttribute(0x0008, 0x0000)
    }

    cmds += zigbee.readAttribute(0x0300, 0x0000)  // Hue
    cmds += zigbee.readAttribute(0x0300, 0x0001)  // Saturation
    cmds += zigbee.readAttribute(0x0300, ATTR_COLOR_MODE)

    markColorMode("RGB")
    sendZigbeeCommands(cmds)
}

void setHue(Number value) {
    logDebug "setHue(${value})"
    Number satPct = (device.currentValue("saturation") as Number) ?: 100
    String hexHue = pctToZigbeeHex(value)
    String hexSat = pctToZigbeeHex(satPct)

    List<String> cmds = []
    cmds += zigbee.command(0x0300, 0x06, hexHue + hexSat + uint16LE(0))
    cmds += zigbee.readAttribute(0x0300, 0x0000)

    markColorMode("RGB")
    sendZigbeeCommands(cmds)
}

void setSaturation(Number value) {
    logDebug "setSaturation(${value})"
    Number huePct = (device.currentValue("hue") as Number) ?: 0
    String hexHue = pctToZigbeeHex(huePct)
    String hexSat = pctToZigbeeHex(value)

    List<String> cmds = []
    cmds += zigbee.command(0x0300, 0x06, hexHue + hexSat + uint16LE(0))
    cmds += zigbee.readAttribute(0x0300, 0x0001)

    markColorMode("RGB")
    sendZigbeeCommands(cmds)
}

void setColorTemperature(BigDecimal temperature, BigDecimal level = null, BigDecimal duration = null) {
    int kelvin = Math.max(CT_MIN_KELVIN, Math.min(CT_MAX_KELVIN, temperature.intValue()))
    int mireds = kelvinToMireds(kelvin)
    int transitionTenths = duration != null ? Math.max(0, duration.intValue() * 10) : 0
    logDebug "setColorTemperature(${temperature}K → ${mireds} mireds, level=${level}, duration=${duration}s)"

    List<String> cmds = []
    // Move to Color Temperature (command 0x0A): UINT16 LE mireds + UINT16 LE transition tenths
    cmds += zigbee.command(0x0300, 0x0A, uint16LE(mireds) + uint16LE(transitionTenths))

    if (level != null) {
        cmds += zigbee.setLevel(level.intValue(), 0)
        cmds += zigbee.readAttribute(0x0008, 0x0000)
    }

    cmds += zigbee.readAttribute(0x0300, ATTR_COLOR_TEMP_MIREDS)
    cmds += zigbee.readAttribute(0x0300, ATTR_COLOR_MODE)

    markColorMode("CT")
    sendZigbeeCommands(cmds)
}

void setColorTemperature(Number temperature, Number level = null, Number duration = null) {
    setColorTemperature(
        temperature as BigDecimal,
        level == null ? (BigDecimal) null : (level as BigDecimal),
        duration == null ? (BigDecimal) null : (duration as BigDecimal))
}

// Custom Commands

void resetTVOCCalibration() {
    logInfo "Resetting TVOC calibration baseline"
    List<String> cmds = zigbee.writeAttribute(CLUSTER_RADAR, ATTR_TVOC_CALIBRATE, DataType.UINT8, 1, [mfgCode: MFG_CODE])
    sendZigbeeCommands(cmds)
}

// Helpers

private void autoConfigure() {
    logWarn "Detected driver version change"
    configure()
}

private void sendZigbeeCommands(List<String> cmds) {
    if (!cmds) return
    logTrace "Sending Zigbee commands: ${cmds}"
    hubitat.device.HubMultiAction hubAction = new hubitat.device.HubMultiAction(cmds, hubitat.device.Protocol.ZIGBEE)
    sendHubCommand(hubAction)
}

private static String uint16LE(int value) {
    int v = value & 0xFFFF
    return String.format("%02X%02X", v & 0xFF, (v >> 8) & 0xFF)
}

private static String pctToZigbeeHex(Number pct) {
    int clamped = Math.max(0, Math.min(100, pct.intValue()))
    int raw = Math.round(clamped * 254 / 100.0d) as int
    return String.format("%02X", raw & 0xFF)
}

private static int kelvinToMireds(int kelvin) {
    int mireds = (kelvin > 0) ? Math.round(1_000_000.0d / kelvin) as int : CT_MAX_MIREDS
    return Math.max(CT_MIN_MIREDS, Math.min(CT_MAX_MIREDS, mireds))
}

private static int miredsToKelvin(int mireds) {
    if (mireds <= 0) return CT_MAX_KELVIN
    return Math.round(1_000_000.0d / mireds) as int
}

private void markColorMode(String mode) {
    if (device.currentValue("colorMode") != mode) {
        sendEvent(name: "colorMode", value: mode,
                  descriptionText: "${device.displayName} color mode is ${mode}")
    }
}

private void sendColorEvent(Integer newHue, Integer newSat) {
    int hue = newHue != null ? newHue : ((device.currentValue("hue") as Number ?: 0) as int)
    int sat = newSat != null ? newSat : ((device.currentValue("saturation") as Number ?: 0) as int)
    int level = (device.currentValue("level") as Number ?: 100) as int
    List<Integer> rgb = hubitat.helper.ColorUtils.hsvToRGB([hue, sat, level])
    String hex = String.format("#%02X%02X%02X", rgb[0] & 0xFF, rgb[1] & 0xFF, rgb[2] & 0xFF)
    if (hex == device.currentValue("color")) return
    sendEvent(name: "color", value: hex, descriptionText: "${device.displayName} color is ${hex}")
}

private void sendColorTempName(int kelvin) {
    String name = (kelvin < 2700) ? "Soft White"
                : (kelvin < 3500) ? "Warm White"
                : (kelvin < 4500) ? "Neutral White"
                : (kelvin < 5500) ? "Cool White"
                : "Daylight"
    if (name == device.currentValue("colorName")) return
    sendEvent(name: "colorName", value: name,
              descriptionText: "${device.displayName} color is ${name}")
}

// Logging helpers

private void logTrace(String message) {
    if (traceEnable) log.trace("${device.displayName} : ${message}")
}

private void logDebug(String message) {
    if (debugEnable) log.debug("${device.displayName} : ${message}")
}

private void logInfo(String message) {
    if (txtEnable) log.info("${device.displayName} : ${message}")
}

private void logWarn(String message) {
    log.warn("${device.displayName} : ${message}")
}

private void logError(String message) {
    log.error("${device.displayName} : ${message}")
}

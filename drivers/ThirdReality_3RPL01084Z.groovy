/*
 *  ThirdReality Presence Sensor R3 (3RPL01084Z) Driver for Hubitat Elevation
 *
 *  60 GHz mmWave presence sensor with RGB night light, illuminance sensor, and TVOC air quality sensor.
 *  Zigbee 3.0 (profile 0x0104), single endpoint, router device.
 *
 *  Reference implementations:
 *    - Zigbee2MQTT: zigbee-herdsman-converters/src/devices/third_reality.ts
 *    - ZHA: zha-device-handlers/zhaquirks/thirdreality/60g_radar.py
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 */

import groovy.transform.Field
import groovy.transform.CompileStatic

@Field static final String version = "0.0.1"

// Custom cluster for radar config and TVOC
@Field static final int CLUSTER_RADAR = 0x042E
@Field static final String MFG_CODE = "0x1407"

// Air quality thresholds (ppb) for deriving airQuality enum
@Field static final int AQ_GOOD_MAX = 500
@Field static final int AQ_MODERATE_MAX = 1500

metadata {
    definition(
        name: "ThirdReality Presence Sensor R3 (3RPL01084Z)",
        namespace: "iamtrep",
        author: "pj",
        importUrl: "https://raw.githubusercontent.com/iamtrep/hubitat/main/drivers/ThirdReality_3RPL01084Z.groovy"
    ) {
        capability "Configuration"
        capability "Refresh"

        capability "PresenceSensor"
        capability "IlluminanceMeasurement"
        capability "Switch"
        capability "SwitchLevel"
        capability "ColorControl"
        capability "ChangeLevel"

        attribute "tvoc", "number"
        attribute "airQuality", "enum", ["good", "moderate", "unhealthy"]

        command "resetTVOCCalibration", [[name: "Reset the TVOC sensor calibration baseline"]]

        fingerprint profileId: "0104", endpointId: "01",
            inClusters: "0000,0003,0004,0005,0006,0008,0300,0400,0406,042E,1000",
            outClusters: "0019",
            manufacturer: "Third Reality, Inc", model: "3RPL01084Z",
            deviceJoinName: "ThirdReality Presence Sensor R3"
    }

    preferences {
        input name: "presenceSensitivity", type: "enum", title: "Presence Sensitivity",
            options: ["1": "1 (lowest)", "2": "2", "3": "3", "4": "4 (default)", "5": "5", "6": "6 (highest)"],
            defaultValue: "4", description: "mmWave radar detection sensitivity (1-6)"

        input name: "airQualityThreshold", type: "number", title: "Air Quality Threshold (ppb)",
            defaultValue: 10000, range: "3000..50000",
            description: "TVOC threshold for device-side air quality alerting (3000-50000 ppb)"

        input name: "powerOnBehavior", type: "enum", title: "Light Power-On Behavior",
            options: ["0": "Off", "1": "On", "2": "Toggle", "255": "Previous"],
            defaultValue: "0", description: "RGB light state after power is restored"

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
    initialize()
}

void updated() {
    logDebug "updated()"

    // Write preference values to device
    List<String> cmds = []

    if (settings.presenceSensitivity != null) {
        int sensitivity = settings.presenceSensitivity as int
        cmds += zigbee.writeAttribute(CLUSTER_RADAR, 0xF002, DataType.UINT8, sensitivity, [mfgCode: MFG_CODE])
    }

    if (settings.airQualityThreshold != null) {
        int threshold = settings.airQualityThreshold as int
        cmds += zigbee.writeAttribute(CLUSTER_RADAR, 0xF003, DataType.UINT16, threshold, [mfgCode: MFG_CODE])
    }

    if (settings.powerOnBehavior != null) {
        int behavior = settings.powerOnBehavior as int
        cmds += zigbee.writeAttribute(0x0006, 0x4003, DataType.ENUM8, behavior)
    }

    if (cmds) sendZigbeeCommands(cmds)
}

void initialize() {
    logDebug "initialize()"
    configure()
}

void configure() {
    logTrace "configure()"

    state.codeVersion = version

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
    cmds += zigbee.configureReporting(0x0006, 0x0000, DataType.BOOLEAN, 0, 3600)        // OnOff
    cmds += zigbee.configureReporting(0x0008, 0x0000, DataType.UINT8, 1, 3600, 1)      // Level

    // Write preference values
    if (settings.presenceSensitivity != null) {
        int sensitivity = settings.presenceSensitivity as int
        cmds += zigbee.writeAttribute(CLUSTER_RADAR, 0xF002, DataType.UINT8, sensitivity, [mfgCode: MFG_CODE])
    }
    if (settings.airQualityThreshold != null) {
        int threshold = settings.airQualityThreshold as int
        cmds += zigbee.writeAttribute(CLUSTER_RADAR, 0xF003, DataType.UINT16, threshold, [mfgCode: MFG_CODE])
    }

    sendZigbeeCommands(cmds)

    runIn(2, "refresh")
}

void refresh() {
    logDebug "refresh()"

    List<String> cmds = []

    cmds += zigbee.readAttribute(0x0406, 0x0000)  // Occupancy
    cmds += zigbee.readAttribute(0x0400, 0x0000)  // Illuminance
    cmds += zigbee.readAttribute(0x0006, 0x0000)  // OnOff
    cmds += zigbee.readAttribute(0x0008, 0x0000)  // Level
    cmds += zigbee.readAttribute(0x0300, 0x0003)  // Color X
    cmds += zigbee.readAttribute(0x0300, 0x0004)  // Color Y
    cmds += zigbee.readAttribute(0x0300, 0x0000)  // Current Hue
    cmds += zigbee.readAttribute(0x0300, 0x0001)  // Current Saturation
    cmds += zigbee.readAttribute(CLUSTER_RADAR, 0x0000, [mfgCode: MFG_CODE])  // TVOC
    cmds += zigbee.readAttribute(CLUSTER_RADAR, 0xF002, [mfgCode: MFG_CODE])  // Sensitivity readback
    cmds += zigbee.readAttribute(CLUSTER_RADAR, 0xF003, [mfgCode: MFG_CODE])  // Threshold readback

    sendZigbeeCommands(cmds)
}

// Parse

List parse(String description) {
    if (state.codeVersion != version) {
        state.codeVersion = version
        runInMillis(1500, "autoConfigure")
    }

    Map descMap = zigbee.parseDescriptionAsMap(description)
    logTrace "parse() - ${descMap}"

    List result = []

    if (descMap.attrId != null) {
        Map event = parseAttributeReport(descMap)
        if (event) result << event
        descMap.additionalAttrs?.each { add ->
            add.cluster = descMap.cluster
            Map addEvent = parseAttributeReport(add)
            if (addEvent) result << addEvent
        }
    } else if (descMap.profileId == "0000") {
        logTrace "Unhandled ZDO command: cluster=${descMap.clusterId} command=${descMap.command}"
    } else if (descMap.profileId == "0104" && descMap.clusterId != null) {
        logTrace "Unhandled ZHA global command: cluster=${descMap.clusterId} command=${descMap.command}"
    } else {
        logDebug "Unhandled message: ${descMap}"
    }

    return result
}

private Map parseAttributeReport(Map descMap) {
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
            if (descMap.attrId == "0000") {
                int rawLevel = Integer.parseInt(descMap.value, 16)
                int level = Math.round(rawLevel * 100.0 / 254.0) as int
                level = Math.max(0, Math.min(100, level))
                map.name = "level"
                map.value = level
                map.unit = "%"
                map.descriptionText = "${device.displayName} light level is ${level}%"
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
                String presence = (occupancy & 0x01) ? "present" : "not present"
                map.name = "presence"
                map.value = presence
                map.descriptionText = "${device.displayName} is ${presence}"
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
        return createEvent(map)
    }

    return null
}

private Map parseColorAttribute(Map descMap) {
    Map map = [:]

    switch (descMap.attrId) {
        case "0000":  // Current Hue (0-254 → 0-100)
            int rawHue = Integer.parseInt(descMap.value, 16)
            int hue = Math.round(rawHue * 100.0 / 254.0) as int
            map.name = "hue"
            map.value = hue
            map.descriptionText = "${device.displayName} hue is ${hue}"
            break

        case "0001":  // Current Saturation (0-254 → 0-100)
            int rawSat = Integer.parseInt(descMap.value, 16)
            int saturation = Math.round(rawSat * 100.0 / 254.0) as int
            map.name = "saturation"
            map.value = saturation
            map.descriptionText = "${device.displayName} saturation is ${saturation}"
            break

        case "0003":  // CurrentX
            state.colorX = Integer.parseInt(descMap.value, 16)
            updateColorFromXY()
            break

        case "0004":  // CurrentY
            state.colorY = Integer.parseInt(descMap.value, 16)
            updateColorFromXY()
            break

        case "0007":  // Color Temperature (if reported)
            break

        case "0008":  // Color Mode
            break
    }

    return map
}

private void updateColorFromXY() {
    if (state.colorX == null || state.colorY == null) return

    // CIE 1931 XY to approximate RGB name — just store the raw XY for now
    // Hubitat's color control primarily uses hue/saturation which come from attrs 0x0000/0x0001
    double x = (state.colorX as int) / 65535.0
    double y = (state.colorY as int) / 65535.0
    logDebug "Color XY updated: x=${x}, y=${y}"
}

private Map parseRadarCluster(Map descMap) {
    Map map = [:]

    switch (descMap.attrId) {
        case "0000":  // TVOC (ppb)
            // Z2M says UINT32, ZHA uses float — try integer parse first
            long tvocValue
            try {
                tvocValue = Long.parseLong(descMap.value, 16)
            } catch (NumberFormatException e) {
                logWarn "Could not parse TVOC value: ${descMap.value}"
                return map
            }

            map.name = "tvoc"
            map.value = tvocValue
            map.unit = "ppb"
            map.descriptionText = "${device.displayName} TVOC is ${tvocValue} ppb"

            // Also derive air quality enum
            String quality
            if (tvocValue <= AQ_GOOD_MAX) {
                quality = "good"
            } else if (tvocValue <= AQ_MODERATE_MAX) {
                quality = "moderate"
            } else {
                quality = "unhealthy"
            }
            sendEvent(name: "airQuality", value: quality,
                      descriptionText: "${device.displayName} air quality is ${quality}")
            break

        case "F002":  // Sensitivity readback
            int sensitivity = Integer.parseInt(descMap.value, 16)
            logDebug "Presence sensitivity readback: ${sensitivity}"
            device.updateSetting("presenceSensitivity", [value: "${sensitivity}", type: "enum"])
            break

        case "F003":  // Air quality threshold readback
            int threshold = Integer.parseInt(descMap.value, 16)
            logDebug "Air quality threshold readback: ${threshold}"
            device.updateSetting("airQualityThreshold", [value: threshold, type: "number"])
            break

        default:
            logDebug "Unknown radar cluster attribute ${descMap.attrId} value ${descMap.value}"
            break
    }

    return map
}

// Light Commands

void on() {
    logDebug "on()"
    sendZigbeeCommands(zigbee.on())
}

void off() {
    logDebug "off()"
    sendZigbeeCommands(zigbee.off())
}

void setLevel(BigDecimal level, BigDecimal duration = 0) {
    logDebug "setLevel(${level}, ${duration})"
    sendZigbeeCommands(zigbee.setLevel(level as int, duration as int))
}

void setLevel(Number level, Number duration = 0) {
    setLevel(level as BigDecimal, duration as BigDecimal)
}

void startLevelChange(String direction) {
    logDebug "startLevelChange(${direction})"
    int moveMode = direction == "up" ? 0x00 : 0x01
    sendZigbeeCommands(zigbee.command(0x0008, 0x01, "0x${zigbee.convertToHexString(moveMode, 2)}${zigbee.convertToHexString(50, 2)}"))
}

void stopLevelChange() {
    logDebug "stopLevelChange()"
    sendZigbeeCommands(zigbee.command(0x0008, 0x03, ""))
}

void setColor(Map colorMap) {
    logDebug "setColor(${colorMap})"

    int hue = colorMap.hue != null ? colorMap.hue as int : device.currentValue("hue") as int ?: 0
    int saturation = colorMap.saturation != null ? colorMap.saturation as int : device.currentValue("saturation") as int ?: 100
    int level = colorMap.level != null ? colorMap.level as int : device.currentValue("level") as int ?: 100

    // Hubitat hue is 0-100, Zigbee enhanced hue is 0-65535
    int zigbeeHue = Math.round(hue * 254.0 / 100.0) as int
    int zigbeeSat = Math.round(saturation * 254.0 / 100.0) as int

    List<String> cmds = []
    cmds += zigbee.command(0x0300, 0x06, zigbee.swapOctets(zigbee.convertToHexString(zigbeeHue * 256, 4)) + zigbee.convertToHexString(zigbeeSat, 2) + "0000")
    if (level != null) {
        cmds += zigbee.setLevel(level, 0)
    }

    sendZigbeeCommands(cmds)
}

void setHue(Number hue) {
    logDebug "setHue(${hue})"
    setColor([hue: hue, saturation: device.currentValue("saturation") ?: 100])
}

void setSaturation(Number saturation) {
    logDebug "setSaturation(${saturation})"
    setColor([hue: device.currentValue("hue") ?: 0, saturation: saturation])
}

// Custom Commands

void resetTVOCCalibration() {
    logInfo "Resetting TVOC calibration baseline"
    List<String> cmds = zigbee.writeAttribute(CLUSTER_RADAR, 0xF001, DataType.UINT8, 1, [mfgCode: MFG_CODE])
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

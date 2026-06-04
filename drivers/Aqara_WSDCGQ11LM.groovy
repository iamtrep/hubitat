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
 *  The check-in payload decoder (reverseHexString, parseCheckinMessageSpecifics)
 *  was incorporated by PJ from veeceeoh's WSDCGQ11LM driver (Apache-2.0):
 *    https://github.com/veeceeoh/xiaomi-hubitat
 *  Inline `// Adapted from ...` attribution comments are preserved at each
 *  function definition. Apache-2.0 is one-way GPL-3.0 compatible; the
 *  combined work is distributed under GPL-3.0-only while the veeceeoh-derived
 *  portions retain their original Apache-2.0 attribution requirements.
 *
 *  Licensed under GPL-3.0-only (combined-work license, inherited from the
 *  BirdsLikeWires upstream). This per-file notice overrides the iamtrep
 *  repo's MIT default. Full license texts:
 *    GPL-3.0:    https://www.gnu.org/licenses/gpl-3.0.html
 *    Apache-2.0: https://www.apache.org/licenses/LICENSE-2.0
 */


import groovy.transform.Field
import groovy.transform.CompileStatic

@Field static final String DRIVER_VERSION = "v2.0 (3rd June 2026)"

@Field static final int REPORT_INTERVAL_MINUTES = 60
@Field static final int CHECK_EVERY_MINUTES = 10
@Field static final int RECOVERY_PROBE_INTERVAL_SECONDS = 120


metadata {

	definition (name: "Aqara Weather Sensor WSDCGQ11LM", namespace: "iamtrep", author: "pj",
	            singleThreaded: true,
	            importUrl: "https://raw.githubusercontent.com/iamtrep/hubitat/main/drivers/Aqara_WSDCGQ11LM.groovy") {

		capability "Battery"
		capability "Configuration"
		capability "PresenceSensor"
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

		fingerprint profileId: "0104", inClusters: "0000,0003,FFFF,0402,0403,0405", outClusters: "0000,0004,FFFF", manufacturer: "LUMI", model: "lumi.weather", deviceJoinName: "WSDCGQ11LM", application: "05"

	}

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


void processTemperature(String temperatureFlippedHex) {

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


void processPressure(String pressureFlippedHex, boolean checkin = false) {

    BigDecimal pressurePa = hexStrToSignedInt(pressureFlippedHex)
    if (checkin) {
        // Check-in blob value is in Pa (already)
    } else {
        // Cluster 0x0403 value is in tenths of hPa → convert to Pa
        pressurePa = pressurePa * 10
    }

    // Convert Pa to display unit
    String unit = pressureUnits ?: "kPa"
    BigDecimal pressure
    switch (unit) {
        case "mbar":
            pressure = pressurePa / 100
            pressure = pressure.setScale(1, BigDecimal.ROUND_HALF_UP)
            break
        case "inHg":
            pressure = (pressurePa / 100) * 0.0295300
            pressure = pressure.setScale(2, BigDecimal.ROUND_HALF_UP)
            break
        case "mmHg":
            pressure = (pressurePa / 100) * 0.750062
            pressure = pressure.setScale(1, BigDecimal.ROUND_HALF_UP)
            break
        default: // kPa
            unit = "kPa"
            pressure = pressurePa / 1000
            pressure = pressure.setScale(2, BigDecimal.ROUND_HALF_UP)
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


void processHumidity(String humidityFlippedHex) {

    BigDecimal humidity = hexStrToSignedInt(humidityFlippedHex)
    humidity = humidity.setScale(2, BigDecimal.ROUND_HALF_UP) / 100

    if (humidityOffset) {
        humidity = humidity + humidityOffset
    }

    logTrace("humidity : ${humidity} from hex value ${humidityFlippedHex}")

    BigDecimal lastTemperature = device.currentState("temperature") ? device.currentState("temperature").value.toBigDecimal() : 0

    String temperatureScale = location.temperatureScale
    if (temperatureScale == "F") {
        lastTemperature = (lastTemperature - 32) / 1.8
    }

    BigDecimal numerator = (6.112 * Math.exp((17.67 * lastTemperature) / (lastTemperature + 243.5)) * humidity * 2.1674)
    BigDecimal denominator = lastTemperature + 273.15
    BigDecimal absoluteHumidity = numerator / denominator
    absoluteHumidity = absoluteHumidity.setScale(1, BigDecimal.ROUND_HALF_UP)

    String cubedChar = String.valueOf((char)(179))

    if (humidity > 100 || humidity < 0) {

        logWarn("Humidity : Value of ${humidity} is out of bounds. Watch out for batteries failing on this device.")

    } else {

        logInfo("Humidity (Relative) : ${humidity} %")
        logInfo("Humidity (Absolute) : ${absoluteHumidity} g/m${cubedChar}")
        sendEvent(name: "humidity", value: humidity, unit: "%")
        sendEvent(name: "absoluteHumidity", value: absoluteHumidity, unit: "g/m${cubedChar}")

    }
}


void processMap(Map map) {

	logTrace("processMap() : ${map}")

	String[] receivedValue = map.value

	if (map.cluster == "0402") {

		// Received temperature data.
        String[] temperatureHex = receivedValue[2..3] + receivedValue[0..1]
        String temperatureFlippedHex = temperatureHex.join()
        logTrace("processMap() : temperature ${temperatureFlippedHex}")
        processTemperature(temperatureFlippedHex)

	} else if (map.cluster == "0403") {

		// Received pressure data.
        String[] pressureHex = receivedValue[2..3] + receivedValue[0..1]
        String pressureFlippedHex = pressureHex.join()
        logTrace("processMap() : pressure ${pressureFlippedHex}")
        processPressure(pressureFlippedHex)

	} else if (map.cluster == "0405") {

		// Received humidity data.
        String[] humidityHex = receivedValue[2..3] + receivedValue[0..1]
        String humidityFlippedHex = humidityHex.join()
        logTrace("processMap() : humidity ${humidityFlippedHex}")
        processHumidity(humidityFlippedHex)

	} else if (map.cluster == "0000") {

		if (map.attrId == "0005") {

			// Scrounge more value! We can capture a short press of the reset button and make it useful.
			logInfo("Trigger : Button Pressed")
			sendEvent(name: "pushed", value: 1, isStateChange: true)

		} else {

			filterThis(map)

		}

	} else {

		filterThis(map)

	}

}


// Adapted from WSDCGQ11LM driver from veeceeoh (https://raw.githubusercontent.com/veeceeoh/xiaomi-hubitat/master/devicedrivers/xiaomi-temperature-humidity-sensor-hubitat.src/xiaomi-temperature-humidity-sensor-hubitat.groovy)
//
// Reverses order of bytes in hex string.
@CompileStatic
String reverseHexString(String hexString) {
	String reversed = ""
	for (int i = hexString.length(); i > 0; i -= 2) {
		reversed += hexString.substring(i - 2, i)
	}
	return reversed
}


// Type-aware integer parse for Xiaomi check-in TLV payloads.
// The dataPayload hex string has already been byte-reversed to big-endian.
// ZCL unsigned types (0x20–0x27): parse as unsigned.
// ZCL signed types   (0x28–0x2F): parse as signed (two's complement).
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


// Adapted from WSDCGQ11LM driver from veeceeoh (https://raw.githubusercontent.com/veeceeoh/xiaomi-hubitat/master/devicedrivers/xiaomi-temperature-humidity-sensor-hubitat.src/xiaomi-temperature-humidity-sensor-hubitat.groovy)
//
// Parse checkin message from lumi.weather device (WSDCGQ11LM) which contains
// a full set of sensor readings.
void parseCheckinMessageSpecifics(String hexString) {

	logDebug("Received check-in message")
	// First byte of hexString is UINT8 of payload length in bytes, so it is skipped
	int strPosition = 2
	int strLength = hexString.size()
	while (strPosition < strLength) {
		int dataTag = Integer.parseInt(hexString[strPosition++..strPosition++], 16)  // Each attribute of the check-in message payload is preceded by a unique 1-byte tag value
		int dataType = Integer.parseInt(hexString[strPosition++..strPosition++], 16)  // After each attribute tag, the following byte gives the data type of the attribute data
		Integer dataLength = DataType.getLength(dataType)  // platform helper returns null for variable-length types
		String dataPayload  // collected per-attribute below
		if (dataLength == null || dataLength == -1 || dataLength == 0) {  // A length of null or -1 means the data type is probably variable-length, and 0 length is invalid
			logDebug("Check-in message contains unsupported dataType 0x${Integer.toHexString(dataType)} for dataTag 0x${Integer.toHexString(dataTag)} with dataLength $dataLength")
			return
		} else {
			if (strPosition > (strLength - dataLength)) {
				logDebug("Ran out of data before finishing parse of check-in message")
				return
			}
			dataPayload = hexString[strPosition++..(strPosition+=(dataLength * 2) - 1)-1]  // Collect attribute tag payload according to data length of its data type
			dataPayload = reverseHexString(dataPayload)  // Reverse order of bytes for big endian payload
			String dataDebug1 = "Check-in message: Found dataTag 0x${Integer.toHexString(dataTag)}"
			String dataDebug2 = "dataType 0x${Integer.toHexString(dataType)}, dataLength $dataLength, dataPayload $dataPayload"
			switch (dataTag) {
				case 0x01:  // Battery voltage
					logTrace("$dataDebug1 (battery), $dataDebug2")
                    //reportBattery(dataPayload, 1000, 2.8, 3.0) // already done in parent call processCheckin()
					break
				case 0x03:  // Device chip temperature (°C, internal NCP — not the external sensor)
					long chipTemp = parseCheckinInt(dataPayload, dataType)
					logDebug("$dataDebug1 (chip temperature), $dataDebug2 (${chipTemp}°C)")
					state.chipTemperature = chipTemp
					break
				case 0x05:  // RSSI dB
					long rssi = parseCheckinInt(dataPayload, dataType)
					logTrace("$dataDebug1 (RSSI dB), $dataDebug2 ($rssi)")
					state.RSSI = rssi
					break
				case 0x06:  // LQI
					long lqi = parseCheckinInt(dataPayload, dataType)
					logTrace("$dataDebug1 (LQI), $dataDebug2 ($lqi)")
					state.LQI = lqi
					break
				case 0x64:  // Temperature in Celcius
					logTrace("$dataDebug1 (temperature), $dataDebug2")
					processTemperature(dataPayload)
					break
				case 0x65:  // Relative humidity
					logTrace("$dataDebug1 (humidity), $dataDebug2")
					processHumidity(dataPayload)
					break
				case 0x66:  // Atmospheric pressure
					logTrace("$dataDebug1 (pressure), $dataDebug2")
					processPressure(dataPayload,true)
					break
				case 0x0A:  // ZigBee parent DNI (device network identifier)
					logTrace("$dataDebug1 (ZigBee parent DNI), $dataDebug2")
					state.zigbeeParentDNI = dataPayload
					break
				case 0x04:  // Unknown (appears consistently in WSDCGQ11LM payloads)
				case 0x07:  // Unknown
				case 0x08:  // Unknown
				case 0x09:  // Unknown
				case 0x0B:  // Unknown
				case 0x0C:  // Unknown
					logTrace("$dataDebug1 (known unhandled), $dataDebug2")
					break
				default:
					logDebug("$dataDebug1 (unexpected), $dataDebug2")
			}
		}
	}
}


// ════════════════════════════════════════════════════════════════════
// Inlined from BirdsLikeWires.library v1.17 (8th November 2022)
// ════════════════════════════════════════════════════════════════════

/*
 * 
 *  BirdsLikeWires Library v1.17 (8th November 2022)
 *	
 */




void sendZigbeeCommands(List<String> cmds) {
	// All hub commands go through here for immediate transmission and to avoid some method() weirdness.

    logTrace("sendZigbeeCommands received : ${cmds}")
    sendHubCommand(new hubitat.device.HubMultiAction(cmds, hubitat.device.Protocol.ZIGBEE))

}


void push(buttonId) {
	
	sendEvent(name:"pushed", value: buttonId, isStateChange:true)
	
}


void updatePresence() {

	long millisNow = new Date().time
	state.presenceUpdated = millisNow
	if (device.currentValue("presence") != "present") {
		sendEvent(name: "presence", value: "present")
		int rc = (device.currentValue("restoredCounter") ?: 0) + 1
		sendEvent(name: "restoredCounter", value: rc)
		logInfo("Presence : Restored (${rc} total recoveries)")
	}

	if (state.recoveryActive) {
		unschedule("recoveryProbe")
		state.recoveryActive = false
		logInfo("Recovery : Device returned, stopping probes")
	}

}


void checkPresence() {
	// Check how long ago the presence state was updated.

	long millisNow = new Date().time
	int uptimeAllowanceMinutes = 20			// The hub takes a while to settle after a reboot.

	if (state.presenceUpdated > 0) {

		long millisElapsed = millisNow - state.presenceUpdated
		long presenceTimeoutMillis = ((REPORT_INTERVAL_MINUTES * 2) + 20) * 60000
		long reportIntervalMillis = REPORT_INTERVAL_MINUTES * 60000
		BigInteger secondsElapsed = BigDecimal.valueOf(millisElapsed / 1000)
		BigInteger hubUptime = location.hub.uptime

		if (millisElapsed > presenceTimeoutMillis) {

			if (hubUptime > uptimeAllowanceMinutes * 60) {

                if (device.currentValue("presence") != "not present") {
                    // only send event if there is a change, otherwise lastActivity will update...
					sendEvent(name: "presence", value: "not present")
                    int npc = (device.currentValue("notPresentCounter") ?: 0) + 1
                    sendEvent(name: "notPresentCounter", value: npc)
                }
                logWarn("Presence : Not Present! Last report received ${secondsElapsed} seconds ago.")
                startRecovery()

			} else {

				logDebug("Presence : Ignoring overdue presence reports for ${uptimeAllowanceMinutes} minutes. The hub was rebooted ${hubUptime} seconds ago.")

			}

		} else {
            if (device.currentValue("presence") != "present") {
                // only send event if there is a change, otherwise lastActivity will update...
                sendEvent(name: "presence", value: "present")
            }
			logDebug("Presence : Last presence report ${secondsElapsed} seconds ago.")

		}

		logTrace("checkPresence() : ${millisNow} - ${state.presenceUpdated} = ${millisElapsed}")
		logTrace("checkPresence() : Report interval is ${reportIntervalMillis} ms, timeout is ${presenceTimeoutMillis} ms.")

	} else {

		logWarn("Presence : Waiting for first presence report.")

	}

}


void resetMeshCounters() {
	sendEvent(name: "notPresentCounter", value: 0)
	sendEvent(name: "restoredCounter", value: 0)
	logInfo("Mesh counters reset")
}


void rebindClusters() {
	logInfo("Recovery : Re-binding reporting clusters")
	List<String> cmds = []
	cmds += "zdo bind 0x${device.deviceNetworkId} 0x01 0x01 0x0000 {${device.zigbeeId}} {}"
	cmds += "zdo bind 0x${device.deviceNetworkId} 0x01 0x01 0x0402 {${device.zigbeeId}} {}"
	cmds += "zdo bind 0x${device.deviceNetworkId} 0x01 0x01 0x0403 {${device.zigbeeId}} {}"
	cmds += "zdo bind 0x${device.deviceNetworkId} 0x01 0x01 0x0405 {${device.zigbeeId}} {}"
	sendZigbeeCommands(cmds)
}


void recoveryProbe() {
	if (device.currentValue("presence") == "present") {
		logDebug("Recovery : Device is present, stopping probes")
		unschedule("recoveryProbe")
		state.recoveryActive = false
		return
	}
	logInfo("Recovery : Sending probe (readAttribute 0x0000/0x0004)")
	sendZigbeeCommands(zigbee.readAttribute(0x0000, 0x0004))
}


void startRecovery() {
	String mode = recoveryMode ?: "Normal"
	if (mode == "Disabled" || state.recoveryActive) return

	int intervalSeconds
	switch (mode) {
		case "Slow":       intervalSeconds = 180; break
		case "Aggressive": intervalSeconds = 30;  break
		default:           intervalSeconds = RECOVERY_PROBE_INTERVAL_SECONDS; break  // Normal
	}

	state.recoveryActive = true
	logInfo("Recovery : Starting ${mode} mode (every ${intervalSeconds}s)")
	rebindClusters()
	schedule("0/${intervalSeconds} * * * * ?", "recoveryProbe")
}


void reportBattery(String batteryVoltageHex, int batteryVoltageDivisor, BigDecimal batteryVoltageScaleMin, BigDecimal batteryVoltageScaleMax) {

	// Report the battery voltage and calculated percentage.
	BigDecimal batteryVoltage = 0

	logTrace("batteryVoltageHex : ${batteryVoltageHex}")

	batteryVoltage = zigbee.convertHexToInt(batteryVoltageHex)
	logDebug("batteryVoltage raw value : ${batteryVoltage}")

	batteryVoltage = batteryVoltage.setScale(2, BigDecimal.ROUND_HALF_UP) / batteryVoltageDivisor

	logDebug("batteryVoltage : ${batteryVoltage}")
	sendEvent(name: "batteryVoltage", value: batteryVoltage, unit: "V")

	BigDecimal batteryPercentage = 0

	if (batteryVoltage >= batteryVoltageScaleMin) {

		batteryPercentage = ((batteryVoltage - batteryVoltageScaleMin) / (batteryVoltageScaleMax - batteryVoltageScaleMin)) * 100.0
		batteryPercentage = batteryPercentage.setScale(0, BigDecimal.ROUND_HALF_UP)
		batteryPercentage = batteryPercentage > 100 ? 100 : batteryPercentage
		batteryPercentage = batteryPercentage < 0 ? 0 : batteryPercentage

		if (batteryPercentage > 20) {
			logInfo("Battery : $batteryPercentage% ($batteryVoltage V)")
		} else {
			logWarn("Battery : $batteryPercentage% ($batteryVoltage V)")
		}

		sendEvent(name: "battery", value:batteryPercentage, unit: "%")
		state.battery = "discharging"

	} else {

		// Very low voltages indicate an exhausted battery which requires replacement.

		batteryPercentage = 0

		logWarn("Battery : Exhausted battery requires replacement.")
		logWarn("Battery : $batteryPercentage% ($batteryVoltage V)")
		sendEvent(name: "battery", value:batteryPercentage, unit: "%")
		state.battery = "exhausted"

	}

}


@CompileStatic
private BigDecimal hexToBigDecimal(String hex) {
	int d = Integer.parseInt(hex, 16) << 21 >> 21
	return BigDecimal.valueOf(d)
}


void logsOff() {

	log.warn "${device} : Auto-disabling debug + trace logging"
	device.updateSetting("debugEnable", [value:"false", type:"bool"])
	device.updateSetting("traceEnable", [value:"false", type:"bool"])

}


private void logTrace(String message) {
	if (traceEnable) log.trace "${device.displayName}: ${message}"
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


void filterThis(Map map) {
	// Everything that hasn't been caught or rejected ends up in this filter.

	if (map.clusterId == "0001") {

		logDebug("Skipped : Power Configuration Response")

	} else if (map.clusterId == "0006") {

		logDebug("Skipped : Match Descriptor Request")

	} else if (map.clusterId == "0013") {

		logDebug("Skipped : Device Announce Broadcast")

	} else if (map.clusterId == "0400") {

		logDebug("Skipped : Illuminance Response")

	} else if (map.clusterId == "8004") {

		logDebug("Skipped : Simple Descriptor Response")

	} else if (map.clusterId == "8005") {

		logDebug("Skipped : Active End Point Response")

	} else if (map.clusterId == "8021") {

		logDebug("Skipped : Bind Response")

	} else if (map.cluster == null && map.clusterId == null) {

		logDebug("Skipped : Empty Message")

	} else {

		String dataCount = (map.data != null) ? "${map.data.length} bits of " : ""
		logWarn("UNKNOWN DATA! Please report these messages to the developer.")
		logWarn("Received : endpoint: ${map.endpoint}, cluster: ${map.cluster}, clusterId: ${map.clusterId}, attrId: ${map.attrId}, command: ${map.command} with value: ${map.value} and ${dataCount}data: ${map.data}")
		logTrace("Splurge! : ${map}")

	}

}




// ════════════════════════════════════════════════════════════════════
// Inlined from BirdsLikeWires.xiaomi v1.12 (8th November 2022)
// ════════════════════════════════════════════════════════════════════

/*
 *
 *  BirdsLikeWires Xiaomi Library v1.12 (8th November 2022)
 *
 */




void runVersionReconfigure() {
	// runInMillis target — keeps the reconfigure off the parser thread.
	logWarn "Driver upgraded from ${getDeviceDataByName('driver')} to ${DRIVER_VERSION}, reconfiguring."
	initialize()
}


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
	if (state.presenceUpdated == null) state.presenceUpdated = 0

	// Counters survive code pushes — only seed them when they don't already exist.
	if (device.currentValue("notPresentCounter") == null) sendEvent(name: "notPresentCounter", value: 0, isStateChange: false)
	if (device.currentValue("restoredCounter")  == null) sendEvent(name: "restoredCounter",  value: 0, isStateChange: false)
	sendEvent(name: "presence", value: "present", isStateChange: false)

	// Schedule presence checking with random jitter so multiple devices don't stampede.
	int randomSixty = Math.abs(new Random().nextInt() % 60)
	schedule("${randomSixty} 0/${CHECK_EVERY_MINUTES} * * * ? *", "checkPresence")

	// Record driver provenance + device-specific data.
	updateDataValue("driver", DRIVER_VERSION)
	updateDataValue("encoding", "Xiaomi")
	sendEvent(name: "numberOfButtons", value: 1, isStateChange: false)

	sendEvent(name: "configuration", value: "complete", isStateChange: false)
	logInfo "Initialized."
}


void configure() {
	// Exposed by the Configuration capability. WSDCGQ11LM relies on the pairing-time
	// reporting setup the device performs autonomously — no zigbee.configureReporting
	// is required here today. Future device-side setup belongs in this method.
	logInfo "Configuring."
	initialize()
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


void refresh() {

	logInfo("Refreshing")

}


private void parseAttributeReport(Map descMap) {
	// Builds a one-row description, the same shape processMap() expects today.
	Map row = [
		cluster: descMap.cluster,
		attrId:  descMap.attrId,
		value:   descMap.value
	]
	processMap(row)
}


void parse(String description) {

	updatePresence()

	// --- Xiaomi check-in (cluster 0x0000 attr 0xFF01) is delivered in a
	// non-ZCL description format. Slice it and hand off to the check-in decoder.
	if (description?.contains("attrId: FF01")) {
		Map xiaomiMap = description.split(', ').collectEntries { String entry ->
			String[] pair = entry.split(': ')
			[(pair.first()): pair.last()]
		}
		logDebug "Parse (Xiaomi check-in): ${xiaomiMap}"
		processCheckin(xiaomiMap)

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

		runVersionCheck()
		return
	}

	// --- Standard ZCL paths
	Map descMap = zigbee.parseDescriptionAsMap(description)
	if (!descMap) {
		logError "Parse : Failed to interpret description: ${description}"
		runVersionCheck()
		return
	}

	logDebug "Parse: ${descMap}"

	// ZDO command (profile 0x0000) — bind responses, mgmt responses, etc.
	if (descMap.profileId == "0000") {
		logTrace "Unhandled ZDO command: cluster=${descMap.clusterId} command=${descMap.command} value=${descMap.value} data=${descMap.data}"
		runVersionCheck()
		return
	}

	// ZHA global command (profile 0x0104, no attrId) — Configure Reporting Response etc.
	if (descMap.profileId == "0104" && descMap.attrId == null) {
		logTrace "Unhandled ZHA global command: cluster=${descMap.clusterId} command=${descMap.command} value=${descMap.value} data=${descMap.data}"
		runVersionCheck()
		return
	}

	// IAS Zone enroll request — WSDCGQ11LM does not use IAS, log if it appears.
	if (descMap.clusterId == "0500" && descMap.command == "01") {
		logDebug "Received enroll request (unexpected for this device): ${descMap}"
		runVersionCheck()
		return
	}

	// IAS Zone status change notification — same: log if it appears.
	if (descMap.clusterId == "0500" && (descMap.command == "00" || descMap.attrId == "0002")) {
		logDebug "Zone status (unexpected for this device): ${descMap}"
		runVersionCheck()
		return
	}

	// Attribute report — primary + every entry in additionalAttrs.
	if (descMap.attrId != null) {
		parseAttributeReport(descMap)
		descMap.additionalAttrs?.each { Map extra ->
			parseAttributeReport(descMap + extra)
		}
		String updateTime = new Date().toLocaleString()
		state.lastUpdate = updateTime
		runVersionCheck()
		return
	}

	logTrace "Unhandled message: ${descMap}"
	runVersionCheck()
}


private void runVersionCheck() {
	if (getDeviceDataByName('driver') != DRIVER_VERSION) {
		runInMillis(100, "runVersionReconfigure")
	}
}


void processCheckin(Map map) {

	int dataSize = map.value.size()

	logInfo("Check-in message.")
	logDebug("processCheckin : Received $dataSize character message.")

	if (dataSize <= 20) {
		logDebug("processCheckin : No device information in this $dataSize character message.")
		return
	}

	// WSDCGQ11LM mushes battery voltage into the status data on attrId FF01 of cluster 0000.
	String batteryVoltageHex = map.value[8..9] + map.value[6..7]
	reportBattery(batteryVoltageHex, 1000, 2.8, 3.0)

	try {
		parseCheckinMessageSpecifics(map.value)
	} catch (Exception e) {
		return
	}
}

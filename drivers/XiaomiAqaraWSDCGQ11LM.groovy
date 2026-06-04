/*
 *
 *  Aqara Weather Sensor WSDCGQ11LM Driver (monolithic build)
 *
 *  Derivative of Andrew Davison's BirdsLikeWires Xiaomi Aqara Temperature and
 *  Humidity Sensor WSDCGQ11LM driver. The two BirdsLikeWires libraries this
 *  driver depended on are inlined below — no Hubitat library types required.
 *    - BirdsLikeWires.library v1.17 (8th November 2022)
 *    - BirdsLikeWires.xiaomi  v1.12 (8th November 2022)
 *
 */


@Field String driverVersion = "v1.11 (12th October 2022)"


import groovy.transform.Field

@Field boolean debugMode = false
@Field int reportIntervalMinutes = 60
@Field int checkEveryMinutes = 10


metadata {

	definition (name: "Aqara Weather Sensor WSDCGQ11LM", namespace: "iamtrep", author: "Andrew Davison") {

		capability "Battery"
		capability "Configuration"
		capability "PresenceSensor"
		capability "PressureMeasurement"
		capability "RelativeHumidityMeasurement"
		capability "Sensor"
		capability "TemperatureMeasurement"
		capability "VoltageMeasurement"
		capability "PushableButton"

		attribute "absoluteHumidity", "number"
		attribute "pressureDirection", "string"
		//attribute "pressurePrevious", "string"

		if (debugMode) {
			command "checkPresence"
			command "testCommand"
		}

		//fingerprint profileId: "0104", inClusters: "0000,0003,FFFF,0402,0403,0405", outClusters: "0000,0004,FFFF", manufacturer: "LUMI", model: "lumi.weather", deviceJoinName: "WSDCGQ11LM", application: "05"

	}

}


preferences {

	input name: "infoLogging", type: "bool", title: "Enable logging", defaultValue: true
	input name: "debugLogging", type: "bool", title: "Enable debug logging", defaultValue: false
	input name: "traceLogging", type: "bool", title: "Enable trace logging", defaultValue: false

}


void testCommand() {

	logging("${device} : Test Command", "info")

}


void configureSpecifics() {
	// Called by main configure() method in BirdsLikeWires.xiaomi

	updateDataValue("encoding", "Xiaomi")
	device.name = "Xiaomi Aqara Temperature and Humidity Sensor WSDCGQ11LM"
	sendEvent(name: "numberOfButtons", value: 1, isStateChange: false)

}


void processTemperature(temperatureFlippedHex) {

    BigDecimal temperature = hexStrToSignedInt(temperatureFlippedHex)
    temperature = temperature.setScale(2, BigDecimal.ROUND_HALF_UP) / 100

    logging("${device} : temperature : ${temperature} from hex value ${temperatureFlippedHex}", "trace")

    String temperatureScale = location.temperatureScale
    if (temperatureScale == "F") {
        temperature = (temperature * 1.8) + 32
    }

    if (temperature > 200 || temperature < -200) {

        logging("${device} : Temperature : Value of ${temperature}°${temperatureScale} is unusual. Watch out for batteries failing on this device.", "warn")

    } else {

        logging("${device} : Temperature : ${temperature} °${temperatureScale}", "info")
        sendEvent(name: "temperature", value: temperature, unit: "${temperatureScale}")

    }
}


void processPressure(pressureFlippedHex,checkin=false) {

    BigDecimal pressure = hexStrToSignedInt(pressureFlippedHex)
    if (checkin) {
        pressure = pressure / 100
    }
    pressure = pressure.setScale(1, BigDecimal.ROUND_HALF_UP) / 10
    BigDecimal lastPressure = device.currentState("pressure") ? device.currentState("pressure").value.toBigDecimal() : 0

    ////////// WORK TO DO - RECORD PREVIOUS PRESSURE AS LASTPRESSURE IF PRESSURE HAS CHANGED OR SOMETHING - TOO TIRED!

    // BigDecimal pressurePrevious = device.currentState("pressurePrevious").value.toBigDecimal()
    // if (pressurePrevious != null && pressure != lastPressure) {
    // 	endEvent(name: "pressurePrevious", value: lastPressure, unit: "kPa")
    // } else if

    String pressureDirection = pressure > lastPressure ? "rising" : "falling"

    logging("${device} : pressure : ${pressure} from hex value ${pressureFlippedHex}", "trace")
    logging("${device} : Pressure : ${pressure} kPa", "info")
    sendEvent(name: "pressure", value: pressure, unit: "kPa")
    sendEvent(name: "pressureDirection", value: "${pressureDirection}")
}


void processHumidity(humidityFlippedHex) {

    BigDecimal humidity = hexStrToSignedInt(humidityFlippedHex)
    humidity = humidity.setScale(2, BigDecimal.ROUND_HALF_UP) / 100

    logging("${device} : humidity : ${humidity} from hex value ${humidityFlippedHex}", "trace")

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

        logging("${device} : Humidity : Value of ${humidity} is out of bounds. Watch out for batteries failing on this device.", "warn")

    } else {

        logging("${device} : Humidity (Relative) : ${humidity} %", "info")
        logging("${device} : Humidity (Absolute) : ${absoluteHumidity} g/m${cubedChar}", "info")
        sendEvent(name: "humidity", value: humidity, unit: "%")
        sendEvent(name: "absoluteHumidity", value: absoluteHumidity, unit: "g/m${cubedChar}")

    }
}


void processMap(Map map) {

	logging("${device} : processMap() : ${map}", "trace")

	String[] receivedValue = map.value

	if (map.cluster == "0402") {

		// Received temperature data.
        String[] temperatureHex = receivedValue[2..3] + receivedValue[0..1]
        String temperatureFlippedHex = temperatureHex.join()
        logging("${device} : processMap() : temperature ${temperatureFlippedHex}", "trace")
        processTemperature(temperatureFlippedHex)

	} else if (map.cluster == "0403") {

		// Received pressure data.
        String[] pressureHex = receivedValue[2..3] + receivedValue[0..1]
        String pressureFlippedHex = pressureHex.join()
        logging("${device} : processMap() : pressure ${pressureFlippedHex}", "trace")
        processPressure(pressureFlippedHex)

	} else if (map.cluster == "0405") {

		// Received humidity data.
        String[] humidityHex = receivedValue[2..3] + receivedValue[0..1]
        String humidityFlippedHex = humidityHex.join()
        logging("${device} : processMap() : humidity ${humidityFlippedHex}", "trace")
        processHumidity(humidityFlippedHex)

	} else if (map.cluster == "0000") {

		if (map.attrId == "0005") {

			// Scrounge more value! We can capture a short press of the reset button and make it useful.
			logging("${device} : Trigger : Button Pressed", "info")
			sendEvent(name: "pushed", value: 1, isStateChange: true)

		} else {

			// processBasic(map)
			filterThis(map)

		}

	} else {

		filterThis(map)

	}

}


// Adapted from WSDCGQ11LM driver from veeceeoh (https://raw.githubusercontent.com/veeceeoh/xiaomi-hubitat/master/devicedrivers/xiaomi-temperature-humidity-sensor-hubitat.src/xiaomi-temperature-humidity-sensor-hubitat.groovy)
//
// Reverses order of bytes in hex string
def reverseHexString(hexString) {
	def reversed = ""
	for (int i = hexString.length(); i > 0; i -= 2) {
		reversed += hexString.substring(i - 2, i )
	}
	return reversed
}


// Adapted from WSDCGQ11LM driver from veeceeoh (https://raw.githubusercontent.com/veeceeoh/xiaomi-hubitat/master/devicedrivers/xiaomi-temperature-humidity-sensor-hubitat.src/xiaomi-temperature-humidity-sensor-hubitat.groovy)
//
// Parse checkin message from lumi.weather device (WSDCGQ11LM) which contains
// a full set of sensor readings.
def parseCheckinMessageSpecifics(hexString) {

	logging("Received check-in message","debug")
	def result
	// First byte of hexString is UINT8 of payload length in bytes, so it is skipped
	def strPosition = 2
	def strLength = hexString.size() - 2
	while (strPosition < strLength) {
		def dataTag = Integer.parseInt(hexString[strPosition++..strPosition++], 16)  // Each attribute of the check-in message payload is preceded by a unique 1-byte tag value
		def dataType = Integer.parseInt(hexString[strPosition++..strPosition++], 16)  // After each attribute tag, the following byte gives the data type of the attribute data
		def dataLength = DataType.getLength(dataType)  // This looks up the length of data for the determined data type
		def dataPayload  // This is used to collect the payload data of each check-in message attribute
		if (dataLength == null || dataLength == -1 || dataLength == 0) {  // A length of null or -1 means the data type is probably variable-length, and 0 length is invalid
			logging("Check-in message contains unsupported dataType 0x${Integer.toHexString(dataType)} for dataTag 0x${Integer.toHexString(dataTag)} with dataLength $dataLength","debug")
			return
		} else {
			if (strPosition > (strLength - dataLength)) {
				logging("Ran out of data before finishing parse of check-in message","debug")
				return
			}
			dataPayload = hexString[strPosition++..(strPosition+=(dataLength * 2) - 1)-1]  // Collect attribute tag payload according to data length of its data type
			dataPayload = reverseHexString(dataPayload)  // Reverse order of bytes for big endian payload
			def dataDebug1 = "Check-in message: Found dataTag 0x${Integer.toHexString(dataTag)}"
			def dataDebug2 = "dataType 0x${Integer.toHexString(dataType)}, dataLength $dataLength, dataPayload $dataPayload"
			switch (dataTag) {
				case 0x01:  // Battery voltage
					logging("$dataDebug1 (battery), $dataDebug2","trace")
                    //reportBattery(dataPayload, 1000, 2.8, 3.0) // already done in parent call xiaomiDeviceStatus()
					break
				case 0x05:  // RSSI dB
					def convertedPayload = Integer.parseInt(dataPayload,16)
					logging("$dataDebug1 (RSSI dB), $dataDebug2 ($convertedPayload)","trace")
					state.RSSI = convertedPayload
					break
				case 0x06:  // LQI
					def convertedPayload = Integer.parseInt(dataPayload,16)
					logging("$dataDebug1 (LQI), $dataDebug2 ($convertedPayload)","trace")
					state.LQI = convertedPayload
					break
				case 0x64:  // Temperature in Celcius
					logging("$dataDebug1 (temperature), $dataDebug2","trace")
					processTemperature(dataPayload)
					break
				case 0x65:  // Relative humidity
					logging("$dataDebug1 (humidity), $dataDebug2","trace")
					processHumidity(dataPayload)
					break
				case 0x66:  // Atmospheric pressure
					logging("$dataDebug1 (pressure), $dataDebug2","trace")
					processPressure(dataPayload,true)
					break
				case 0x0A:  // ZigBee parent DNI (device network identifier)
					logging("$dataDebug1 (ZigBee parent DNI), $dataDebug2","trace")
					state.zigbeeParentDNI = dataPayload
					break
				default:
					logging("$dataDebug1 (unknown), $dataDebug2","trace")
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

    logging("${device} : sendZigbeeCommands received : ${cmds}", "trace")
    sendHubCommand(new hubitat.device.HubMultiAction(cmds, hubitat.device.Protocol.ZIGBEE))

}


void push(buttonId) {
	
	sendEvent(name:"pushed", value: buttonId, isStateChange:true)
	
}


void doubleTap(buttonId) {
	
	sendEvent(name:"doubleTapped", value: buttonId, isStateChange:true)
	
}


void hold(buttonId) {
	
	sendEvent(name:"held", value: buttonId, isStateChange:true)
	
}


void release(buttonId) {
	
	sendEvent(name:"released", value: buttonId, isStateChange:true)
	
}

//////////////// So, this may well be different for different lamps and dimmers, so... possibly not for here, 

// void setLevel(BigDecimal level) {

// 	setLevel(level,1)

// }


// void setLevel(BigDecimal level, BigDecimal duration) {

// 	BigDecimal safeLevel = level <= 100 ? level : 100
// 	safeLevel = safeLevel < 0 ? 0 : safeLevel

// 	String hexLevel = percentageToHex(safeLevel.intValue())

// 	BigDecimal safeDuration = duration <= 25 ? (duration*10) : 255
// 	String hexDuration = Integer.toHexString(safeDuration.intValue())

// 	String pluralisor = duration == 1 ? "" : "s"
// 	logging("${device} : setLevel : Got level request of '${level}' (${safeLevel}%) [${hexLevel}] changing over '${duration}' second${pluralisor} (${safeDuration} deciseconds) [${hexDuration}].", "debug")

// 	sendEvent(name: "level", value: "${safeLevel}")

// }


void updatePresence() {

	long millisNow = new Date().time
	state.presenceUpdated = millisNow
	sendEvent(name: "presence", value: "present")

}


void checkPresence() {
	// Check how long ago the presence state was updated.

	long millisNow = new Date().time
	int uptimeAllowanceMinutes = 20			// The hub takes a while to settle after a reboot.

	if (state.presenceUpdated > 0) {

		long millisElapsed = millisNow - state.presenceUpdated
		long presenceTimeoutMillis = ((reportIntervalMinutes * 2) + 20) * 60000
		long reportIntervalMillis = reportIntervalMinutes * 60000
		BigInteger secondsElapsed = BigDecimal.valueOf(millisElapsed / 1000)
		BigInteger hubUptime = location.hub.uptime

		if (millisElapsed > presenceTimeoutMillis) {

			if (hubUptime > uptimeAllowanceMinutes * 60) {

                if (device.currentValue("presence") != "not present") {
                    // only send event if there is a change, otherwise lastActivity will update...
					sendEvent(name: "presence", value: "not present")
                }
                logging("${device} : Presence : Not Present! Last report received ${secondsElapsed} seconds ago.", "warn")

			} else {

				logging("${device} : Presence : Ignoring overdue presence reports for ${uptimeAllowanceMinutes} minutes. The hub was rebooted ${hubUptime} seconds ago.", "debug")

			}

		} else {
            if (device.currentValue("presence") != "present") {
                // only send event if there is a change, otherwise lastActivity will update...
                sendEvent(name: "presence", value: "present")
            }
			logging("${device} : Presence : Last presence report ${secondsElapsed} seconds ago.", "debug")

		}

		logging("${device} : checkPresence() : ${millisNow} - ${state.presenceUpdated} = ${millisElapsed}", "trace")
		logging("${device} : checkPresence() : Report interval is ${reportIntervalMillis} ms, timeout is ${presenceTimeoutMillis} ms.", "trace")

	} else {

		logging("${device} : Presence : Waiting for first presence report.", "warn")

	}

}


void processBasic(Map map) {
	// Process the basic descriptors normally received from Zigbee Cluster 0000 into device data values.

	if (map.attrId == "0001") {

		updateDataValue("application", "${map.value}")
		logging("${device} : Application : ${map.value}", "debug")

	} else if (map.attrId == "0004") {

		updateDataValue("manufacturer", map.value)
		logging("${device} : Manufacturer : ${map.value}", "debug")

	} else if (map.attrId == "0005") {

		updateDataValue("model", map.value)
		logging("${device} : Model : ${map.value}", "debug")

	} else if (map.attrId == "4000") {

		updateDataValue("softwareBuild", "${map.value}")
		logging("${device} : Firmware : ${map.value}", "debug")

	}

}


void processConfigurationResponse(Map map) {

	if (map.command == "07") {

		if (map.data[0] == "00") {

			sendEvent(name: "configuration", value: "received", isStateChange: false)
			logging("${device} : Configuration : Received by device.", "info")

		} else {

			logging("${device} : Configuration : Device may not have processed configuration correctly.", "warn")

		}

	} else {

		reportToDev(map)

	}

}


void processDescriptors(Map map) {
	// Process the simple descriptors normally received from Zigbee Cluster 8004 into device data values.

	if (map.data[1] == "00") {
		// Received simple descriptor data.

		//updateDataValue("endpointId", map.data[5])						// can lead to a weird duplicate
		updateDataValue("profileId", map.data[6..7].reverse().join())

		Integer inClusterNum = Integer.parseInt(map.data[11], 16)
		Integer position = 12
		Integer positionCounter = null
		String inClusters = ""
		if (inClusterNum > 0) {
			(1..inClusterNum).each() {b->
				positionCounter = position+((b-1)*2)
				inClusters += map.data[positionCounter..positionCounter+1].reverse().join()
				if (b < inClusterNum) {
					inClusters += ","
				}
			}
		}
		position += inClusterNum*2
		Integer outClusterNum = Integer.parseInt(map.data[position], 16)
		position += 1
		String outClusters = ""
		if (outClusterNum > 0) {
			(1..outClusterNum).each() {b->
				positionCounter = position+((b-1)*2)
				outClusters += map.data[positionCounter..positionCounter+1].reverse().join()
				if (b < outClusterNum) {
					outClusters += ","
				}
			}
		}

		updateDataValue("inClusters", inClusters)
		updateDataValue("outClusters", outClusters)

		logging("${device} : Received $inClusterNum inClusters : $inClusters", "debug")
		logging("${device} : Received $outClusterNum outClusters : $outClusters", "debug")

	} else {

		reportToDev(map)

	}

}


void reportBattery(String batteryVoltageHex, int batteryVoltageDivisor, BigDecimal batteryVoltageScaleMin, BigDecimal batteryVoltageScaleMax) {

	// Report the battery voltage and calculated percentage.
	BigDecimal batteryVoltage = 0

	logging("${device} : batteryVoltageHex : ${batteryVoltageHex}", "trace")

	batteryVoltage = zigbee.convertHexToInt(batteryVoltageHex)
	logging("${device} : batteryVoltage raw value : ${batteryVoltage}", "debug")

	batteryVoltage = batteryVoltage.setScale(2, BigDecimal.ROUND_HALF_UP) / batteryVoltageDivisor

	logging("${device} : batteryVoltage : ${batteryVoltage}", "debug")
	sendEvent(name: "voltage", value: batteryVoltage, unit: "V")

	BigDecimal batteryPercentage = 0

	if (batteryVoltage >= batteryVoltageScaleMin) {

		batteryPercentage = ((batteryVoltage - batteryVoltageScaleMin) / (batteryVoltageScaleMax - batteryVoltageScaleMin)) * 100.0
		batteryPercentage = batteryPercentage.setScale(0, BigDecimal.ROUND_HALF_UP)
		batteryPercentage = batteryPercentage > 100 ? 100 : batteryPercentage
		batteryPercentage = batteryPercentage < 0 ? 0 : batteryPercentage

		if (batteryPercentage > 20) {
			logging("${device} : Battery : $batteryPercentage% ($batteryVoltage V)", "info")
		} else {
			logging("${device} : Battery : $batteryPercentage% ($batteryVoltage V)", "warn")
		}

		sendEvent(name: "battery", value:batteryPercentage, unit: "%")
		state.battery = "discharging"

	} else {

		// Very low voltages indicate an exhausted battery which requires replacement.

		batteryPercentage = 0

		logging("${device} : Battery : Exhausted battery requires replacement.", "warn")
		logging("${device} : Battery : $batteryPercentage% ($batteryVoltage V)", "warn")
		sendEvent(name: "battery", value:batteryPercentage, unit: "%")
		state.battery = "exhausted"

	}

}


void reportToDev(map) {

	def dataCount = ""
	if (map.data != null) {
		dataCount = "${map.data.length} bits of "
	}

	logging("${device} : UNKNOWN DATA! Please report these messages to the developer.", "warn")
	logging("${device} : Received : endpoint: ${map.endpoint}, cluster: ${map.cluster}, clusterId: ${map.clusterId}, attrId: ${map.attrId}, command: ${map.command} with value: ${map.value} and ${receivedDataCount}data: ${map.data}", "warn")
	logging("${device} : Splurge! : ${map}", "trace")

}


@Field static Boolean debouncingParentState = false
void debounceParentState(String attribute, String state, String message, String level, Integer duration) {

	if (debouncingParentState) return
	debouncingParentState = true

	sendEvent(name: "$attribute", value: "$state")
	logging("${device} : $message", "$level")

	pauseExecution duration
	debouncingParentState = false

}


def fetchChild(String namespace, String type, String endpoint) {
	// Creates and retrieves child devices matched to endpoints.

	// Namespace is required for custom child drivers. Use "hubitat" for system drivers.
	// Type will determine the driver to use.
	// Endpoint is any identifier unique to the parent.

	def childDevice = getChildDevice("${device.id}-${endpoint}")

	if (endpoint != "null") {

		if (!childDevice) {

			logging("${device} : Creating child device $device.id-$endpoint", "debug")

			childDevice = addChildDevice("${namespace}", "${type}", "${device.id}-${endpoint}", [name: "${type}", label: "${endpoint}", isComponent: false])

			if (type.indexOf('Switch') >= 0) {

				// Presume a switch to be off until told otherwise.
				childDevice.parse([[name: "switch", value: 'off']])

			}

			//childDevice.updateSetting("txtEnable", false)

		}

		logging("${device} : Retrieved child device $device.id-$endpoint", "debug")

	} else {

		logging("${device} : Received null endpoint for device $device.id", "error")

	}

	return childDevice

}


def fetchChildStates(String state, String requestor) {
	// Retrieves requested states of child devices.

	logging("${device} : fetchChildStates() : Called by $requestor", "debug")

	def childStates = []
	def children = getChildDevices()

	children.each {child->

		// Give things a chance!
		pauseExecution(100)
	
		// Grabs the requested state from the child device.
		String childState = child.currentValue("${state}")

		if ("${child.id}" != "${requestor}") {
			// Don't include the requestor's state in the results, as we're likely in the process of updating it.
			childStates.add("${childState}")
			logging("${device} : fetchChildStates() : Found $child.id is '$childState'", "debug")
		}

	}

	return childStates

}


void deleteChildren() {
	// Deletes children we may have created.

	logging("${device} : deleteChildren() : Deleting rogue children.", "debug")

	def children = getChildDevices()
    children.each {child->
  		deleteChildDevice(child.deviceNetworkId)
    }

}


void componentRefresh(com.hubitat.app.DeviceWrapper childDevice) {

	logging("componentRefresh() from $childDevice.deviceNetworkId", "debug")
	sendZigbeeCommands(["he rattr 0x${device.deviceNetworkId} 0x${childDevice.deviceNetworkId.split("-")[1]} 0x0006 0x00 {}"])

}


void componentOn(com.hubitat.app.DeviceWrapper childDevice) {

	logging("componentOn() from $childDevice.deviceNetworkId", "debug")
	sendZigbeeCommands(["he cmd 0x${device.deviceNetworkId} 0x${childDevice.deviceNetworkId.split("-")[1]} 0x0006 0x01 {}"])

}


void componentOff(com.hubitat.app.DeviceWrapper childDevice) {

	logging("componentOff() from $childDevice.deviceNetworkId", "debug")
	sendZigbeeCommands(["he cmd 0x${device.deviceNetworkId} 0x${childDevice.deviceNetworkId.split("-")[1]} 0x0006 0x00 {}"])

}


private String flipLittleEndian(Map map, String attribute) {

	String bigEndianAttribute = ""
	for (int v = map."${attribute}".length(); v > 0; v -= 2) {
		bigEndianAttribute += map."${attribute}".substring(v - 2, v)
	}
	return bigEndianAttribute

}


private String[] millisToDhms(BigInteger millisToParse) {

	long secondsToParse = millisToParse / 1000

	def dhms = []
	dhms.add(secondsToParse % 60)
	secondsToParse = secondsToParse / 60
	dhms.add(secondsToParse % 60)
	secondsToParse = secondsToParse / 60
	dhms.add(secondsToParse % 24)
	secondsToParse = secondsToParse / 24
	dhms.add(secondsToParse % 365)
	return dhms

}


private String percentageToHex(Integer pc) {

	BigDecimal safePc = pc > 0 ? (pc*2.55) : 0
	safePc = safePc > 255 ? 255 : safePc
	return Integer.toHexString(safePc.intValue())

}


private BigDecimal hexToBigDecimal(String hex) {

    int d = Integer.parseInt(hex, 16) << 21 >> 21
    return BigDecimal.valueOf(d)

}


private String hexToBinary(String thisByte, Integer size = 8) {

	String binaryValue = new BigInteger(thisByte, 16).toString(2);
	return String.format("%${size}s", binaryValue).replace(' ', '0')
	
}


private Integer hexToPercentage(String hex) {

	String safeHex = hex.take(2)
    Integer pc = Integer.parseInt(safeHex, 16) << 21 >> 21
	return pc / 2.55

}


private String hexToText(String hex) {

	String text = ""
	int pos = 0
	while(pos < hex.length() - 1) {
		text = text + (char)Integer.parseInt(hex.substring(pos, pos + 2), 16)
		pos += 2
	}
	return text.trim()

}


void loggingStatus() {

	log.info  "${device} :  Info Logging : ${infoLogging == true}"
	log.debug "${device} : Debug Logging : ${debugLogging == true}"
	log.trace "${device} : Trace Logging : ${traceLogging == true}"

}


void traceLogOff(){
	
	log.trace "${device} : Trace Logging : Automatically Disabled"
	device.updateSetting("traceLogging",[value:"false",type:"bool"])

}

void debugLogOff(){
	
	log.debug "${device} : Debug Logging : Automatically Disabled"
	device.updateSetting("debugLogging",[value:"false",type:"bool"])

}


void infoLogOff(){
	
	log.info "${device} : Info  Logging : Automatically Disabled"
	device.updateSetting("infoLogging",[value:"false",type:"bool"])

}


private boolean logging(String message, String level) {

	boolean didLog = false

	if (level == "error") {
		log.error "$message"
		didLog = true
	}

	if (level == "warn") {
		log.warn "$message"
		didLog = true
	}

	if (traceLogging && level == "trace") {
		log.trace "$message"
		didLog = true
	}

	if (debugLogging && level == "debug") {
		log.debug "$message"
		didLog = true
	}

	if (infoLogging && level == "info") {
		log.info "$message"
		didLog = true
	}

	return didLog

}


void filterThis(Map map) {
	// Everything that hasn't been caught or rejected ends up in this filter.

	if (map.clusterId == "0001") {

		processConfigurationResponse(map)

	} else if (map.clusterId == "0006") {

		logging("${device} : Skipped : Match Descriptor Request", "debug")

	} else if (map.clusterId == "0013") {

		logging("${device} : Skipped : Device Announce Broadcast", "debug")

	} else if (map.clusterId == "0400") {

		processConfigurationResponse(map)

	} else if (map.clusterId == "8004") {
		
		processDescriptors(map)

	} else if (map.clusterId == "8005") {

		logging("${device} : Skipped : Active End Point Response", "debug")

	} else if (map.clusterId == "8021") {

		logging("${device} : Skipped : Bind Response", "debug")

	} else if (map.cluster == null && map.clusterId == null) {

		logging("${device} : Skipped : Empty Message", "debug")

	} else {

		reportToDev(map)

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




void installed() {

	// Runs after first installation.
	logging("${device} : Installed", "info")
	configure()

}


void configure() {

	int randomSixty

	// Tidy up.
	unschedule()
	state.clear()
	state.presenceUpdated = 0
	sendEvent(name: "presence", value: "present", isStateChange: false)

	// Schedule presence checking.
	randomSixty = Math.abs(new Random().nextInt() % 60)
	schedule("${randomSixty} 0/${checkEveryMinutes} * * * ? *", checkPresence)

	// Set device specifics.
	updateDataValue("driver", "$driverVersion")
	configureSpecifics()

	// Notify.
	sendEvent(name: "configuration", value: "complete", isStateChange: false)
	logging("${device} : Configuration complete.", "info")

	updated()

}


void updated() {
	// Runs when preferences are saved.

	unschedule(infoLogOff)
	unschedule(debugLogOff)
	unschedule(traceLogOff)

	if (!debugMode) {
		runIn(2400,debugLogOff)
		runIn(1200,traceLogOff)
	}

	logging("${device} : Preferences Updated", "info")

	loggingStatus()

}


void refresh() {

	logging("${device} : Refreshing", "info")

}


void parse(String description) {

	updatePresence()

	String encodingCheck = "unknown"
	encodingCheck = "${getDeviceDataByName('encoding')}"

	Map descriptionMap = null

	if (encodingCheck == "Xiaomi") {

		// Most Xiaomi devices don't follow the spec, so we slice-and-dice the string we receive.
		descriptionMap = description.split(', ').collectEntries {
			entry -> def pair = entry.split(': ')
			[(pair.first()): pair.last()]
		}

	} else if (encodingCheck == "Zigbee") {

		// These devices appear to follow the Zigbee Cluster Library Specification
		descriptionMap = zigbee.parseDescriptionAsMap(description)

	} else {

		logging("${device} : Parse : Cannot parse message, encoding type is $encodingCheck.", "error")
		logging("${device} : Parse : Attempting to configure device.", "info")
		configure()
		return

	}

	logging("${device} : Parse : Interpreting against $encodingCheck cluster specification.", "debug")
    updateTime = new Date().toLocaleString()

	if (descriptionMap) {

		logging("${device} : Parse : ${descriptionMap}", "debug")

		if (descriptionMap.cluster == "0000" && descriptionMap.attrId == "FF01") {

			// Device Status Cluster
			xiaomiDeviceStatus(descriptionMap)
            state.lastCheckin = updateTime

		} else {

			// Hand back to the driver for processing.
			processMap(descriptionMap)
		}

        state.lastUpdate = updateTime


	} else {

		logging("${device} : Parse : Failed to parse $encodingCheck cluster specification data. Please report these messages to the developer.", "error")
		logging("${device} : Parse : ${description}", "error")

	}

	String versionCheck = "unknown"
	versionCheck = "${getDeviceDataByName('driver')}"

	if ("$versionCheck" != "$driverVersion") {

		logging("${device} : Driver : Updating configuration from $versionCheck to $driverVersion.", "info")
		configure()

	}

}


void xiaomiDeviceStatus(Map map) {

	int batteryDivisor = 1
	String batteryVoltageHex = "undefined"
	String modelCheck = "${getDeviceDataByName('model')}"
	def dataSize = map.value.size()

        logging("${device} check-in message.", "info")
	logging("${device} : xiaomiDeviceStatus : Received $dataSize character message.", "debug")

	if (modelCheck == "lumi.sen_ill.mgl01") {
		// The Mijia Smart Light Sensor neatly reports its battery hex values on attrId 0020 of cluster 0001.

		batteryVoltageHex = map.value
		batteryDivisor = 10

	} else {
		// Everything else mushes it into the status data on attrId FF01 of cluster 0000.

		if (dataSize > 20) {

			batteryVoltageHex = map.value[8..9] + map.value[6..7]
			batteryDivisor = 1000

		} else {

			logging("${device} : xiaomiDeviceStatus : No device information in this $dataSize character message.", "debug")
			return

		}

	}

	reportBattery(batteryVoltageHex, batteryDivisor, 2.8, 3.0)

    try {

        if (modelCheck == "lumi.weather") {
            // decode sensor values, which are part of the checkin message
            parseCheckinMessageSpecifics(map.value)
        } else {
            // On some devices (buttons for one) there's a wildly inaccurate temperature sensor.
            // We may as well throw this out in the log for comedy value as it's rarely reported.
            // Who knows. We may learn something.

            String temperatureValue = "undefined"
            temperatureValue = map.value[14..15]
            BigDecimal temperatureCelsius = hexToBigDecimal(temperatureValue)

            logging("${device} : temperatureValue : ${temperatureValue}", "trace")
            logging("${device} : temperatureCelsius sensor value : ${temperatureCelsius}", "trace")

            logging("${device} : Inaccurate Temperature : $temperatureCelsius °C", "info")
            // sendEvent(name: "temperature", value: temperatureCelsius, unit: "C")			// No, don't do that. That would be silly.
        }
    } catch (Exception e) {

        return

    }
}

import groovy.transform.Field

@Field static final long RSSI_MIN_INTERVAL_MS = 10000

metadata {
    definition (
        name: "Bluetooth Home v2 Motion/Occupancy Sensor",
        namespace: "hubitat",
        author: "Victor U.",
        singleThreaded: true
    ) {
        capability "Battery"
        capability "Illuminance Measurement"
        capability "Motion Sensor"

        attribute "rssi", "number"
    }
    preferences {
        input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: true
    }
}

void parse(Map data) {
    parseBatteryAndRSSI(data)
    if (hasBinaryValue(data, "motion")) {
        processBinaryValue(data, "motion", "motion", "active", "inactive")
    } else if (hasBinaryValue(data, "occupancy")) {
        processBinaryValue(data, "occupancy", "motion", "active", "inactive")
    }
    processDoubleValue(data, "illuminance", "illuminance", "lux")
}

void installed() {
    // runIn(1800, logsOff)
}

void updated() {
    // runIn(1800, logsOff)
}

void uninstalled() {
    // nothing for now
}

void initialize() {
    // nothing for now
}

void logsOff(){
    log.warn "debug logging disabled..."
    device.updateSetting("logEnable",[value:"false",type:"bool"])
}

Map getSensorData(Map data, String sensorType) {
    if (data.sensors && (data.sensors instanceof List)) {
        for (sensor in data.sensors) {
            if (sensor.device == sensorType) {
                return sensor
            }
        }
    }
    return null
}

Map getEventData(Map data, String sensorType) {
    if (data.events && (data.events instanceof List)) {
        for (sensor in data.events) {
            if (sensor.device == sensorType) {
                return sensor
            }
        }
    }
    return null
}

boolean getBinaryValue(Map data, String valueName) {
    if (data.binary_values && (data.binary_values instanceof List)) {
        for (binary_value in data.binary_values) {
            if (binary_value.device == valueName) {
                return binary_value.value as boolean
            }
        }
    }
    return false
}

boolean hasBinaryValue(Map data, String valueName) {
    if (data.binary_values && (data.binary_values instanceof List)) {
        for (binary_value in data.binary_values) {
            if (binary_value.device == valueName) {
                return true
            }
        }
    }
    return false
}

void processBinaryValue(Map data, String valueName, String attributeName, String trueState, String falseState) {
    boolean value = getBinaryValue(data, valueName)
    String hubitatState = value ? trueState : falseState
    sendEvent(name: attributeName, value: hubitatState, descriptionText: "${device.displayName} is now ${hubitatState}")
}

void processDoubleValue(Map data, String sensorName, String attributeName, String unit = null) {
    Map sensorData = getSensorData(data, sensorName)
    if (sensorData && isDouble(sensorData.value)) {
        double sensorValue = Math.round(sensorData.value * 100) / 100.0
        sendEvent(name: attributeName, value: sensorValue,
                descriptionText: "${device.displayName} ${attributeName} is now ${sensorValue}${unit ?: ''}",
                unit: unit)
    }
}

void processIntegerValue(Map data, String sensorName, String attributeName, String unit = null) {
    Map sensorData = getSensorData(data, sensorName)
    if (sensorData && isInteger(sensorData.value)) {
        int sensorValue = sensorData.value as int
        sendEvent(name: attributeName, value: sensorValue,
                descriptionText: "${device.displayName} ${attributeName} is now ${sensorValue}${unit ?: ''}",
                unit: unit)
    }
}

void processTemperatureValue(Map data) {
    Map temperatureData = getSensorData(data, "temperature")
    if (temperatureData && isDouble(temperatureData.value)) {
        // BTHomeV2 always reports temperature in Celsius, according to https://bthome.io/format/
        double temperature = temperatureData.value as double
        if (location.temperatureScale == "F")
            temperature = celsiusToFahrenheit(temperature)

        sendEvent(name: "temperature", value: temperature,
                descriptionText: "${device.displayName} temperature is now ${temperature} °${location.temperatureScale}",
                unit: location.temperatureScale)
    }
}

boolean isInteger(obj) {
    try {
        Integer.parseInt(obj.toString())
        return true
    } catch (Exception e) {
        return false
    }
}

boolean isDouble(obj) {
    try {
        Double.parseDouble(obj.toString())
        return true
    } catch (Exception e) {
        return false
    }
}

void parseBatteryAndRSSI(Map data) {
    if (logEnable)
        log.debug "parse: ${data}"

    processIntegerValue(data, "battery", "battery", "%")

    long now = now()
    long lastRssi = state.lastRssiReport ?: 0
    if (now - lastRssi >= RSSI_MIN_INTERVAL_MS) {
        processIntegerValue(data, "signal_strength", "rssi", "dBm")
        state.lastRssiReport = now
    }
}

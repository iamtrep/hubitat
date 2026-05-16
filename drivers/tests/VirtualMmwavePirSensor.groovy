/**
 * Virtual mmWave + PIR Sensor (Test Driver)
 *
 * Pairs with apps/sensors/MotionFusionChild.groovy — exposes the same
 * non-standard attributes that MotionFusionChild expects on its source
 * device (Aqara FP300 family): `pirDetection` and `roomState`. Lets
 * Mode 1 tests drive both inputs deterministically via Maker API
 * without needing a real Zigbee endpoint.
 *
 * Companion to drivers/tests/LogEventMonitorTest.groovy — follows the
 * same "test-only driver in drivers/tests/" convention.
 */

metadata {
    definition(
        name: "Virtual mmWave PIR Sensor",
        namespace: "iamtrep",
        author: "pj"
    ) {
        capability "Actuator"
        capability "Sensor"

        // Same attribute names + value sets as the Aqara FP300 driver
        // surface that MotionFusionChild reads.
        attribute "pirDetection", "ENUM", ["active", "inactive"]
        attribute "roomState",    "ENUM", ["occupied", "unoccupied"]

        command "setPirDetection", [
            [name: "value", type: "ENUM", constraints: ["active", "inactive"]]
        ]
        command "setRoomState", [
            [name: "value", type: "ENUM", constraints: ["occupied", "unoccupied"]]
        ]
    }
}

void setPirDetection(String value) {
    sendEvent(
        name: "pirDetection",
        value: value,
        descriptionText: "${device.displayName} PIR is ${value}",
        isStateChange: true
    )
}

void setRoomState(String value) {
    sendEvent(
        name: "roomState",
        value: value,
        descriptionText: "${device.displayName} room is ${value}",
        isStateChange: true
    )
}

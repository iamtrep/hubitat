// Copyright (c) 2026 PJ
// SPDX-License-Identifier: MIT

/*
 Virtual Health Sensor (Test)

 Test driver. A virtual motion and contact sensor that also reports
 healthStatus, set with setHealthStatus(online|offline), which the built-in
 virtual sensors lack. Used by the Stuck Sensor Reset behavior test to cover
 a sensor that is offline but recently heard from.
*/

metadata {
    definition(name: "Virtual Health Sensor (Test)", namespace: "iamtrep", author: "pj",
               importUrl: "https://raw.githubusercontent.com/iamtrep/hubitat/main/drivers/tests/VirtualHealthSensor.groovy") {
        capability "Sensor"
        capability "MotionSensor"
        capability "ContactSensor"
        attribute "healthStatus", "enum", ["online", "offline"]
        command "active"
        command "inactive"
        command "open"
        command "close"
        command "setHealthStatus", [[name: "status*", type: "ENUM", constraints: ["online", "offline"]]]
    }
}

void active()   { sendEvent(name: "motion", value: "active", descriptionText: "${device.displayName} is active") }
void inactive() { sendEvent(name: "motion", value: "inactive", descriptionText: "${device.displayName} is inactive") }
void open()     { sendEvent(name: "contact", value: "open", descriptionText: "${device.displayName} is open") }
void close()    { sendEvent(name: "contact", value: "closed", descriptionText: "${device.displayName} is closed") }

void setHealthStatus(String status) {
    sendEvent(name: "healthStatus", value: status, descriptionText: "${device.displayName} is ${status}")
}

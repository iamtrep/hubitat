// Copyright (c) 2026 PJ
// SPDX-License-Identifier: MIT

/*
 Virtual CO2 Sensor (Test)

 Test driver. The built-in Virtual Carbon Dioxide Sensor only reports clear or
 detected; this one reports a ppm value set with setCarbonDioxide(ppm), for
 behavior tests of apps that read CarbonDioxideMeasurement.
*/

metadata {
    definition(name: "Virtual CO2 Sensor (Test)", namespace: "iamtrep", author: "pj",
               importUrl: "https://raw.githubusercontent.com/iamtrep/hubitat/main/drivers/tests/VirtualCO2Sensor.groovy") {
        capability "Sensor"
        capability "CarbonDioxideMeasurement"
        command "setCarbonDioxide", [[name: "ppm*", type: "NUMBER"]]
    }
}

void setCarbonDioxide(BigDecimal ppm) {
    sendEvent(name: "carbonDioxide", value: ppm, unit: "ppm", descriptionText: "${device.displayName} CO2 is ${ppm} ppm")
}

// Copyright (c) 2026 PJ
// SPDX-License-Identifier: MIT

/*
 Notification Capture (Test)

 Records the last notification or spoken text as an attribute, so a behavior test can
 assert on what an app sent.
*/

import groovy.transform.Field

@Field static final String CODE_VERSION = "0.1.0"

metadata {
    definition(name: "Notification Capture (Test)", namespace: "iamtrep", author: "pj",
               importUrl: "https://raw.githubusercontent.com/iamtrep/hubitat/main/drivers/tests/NotificationCapture.groovy") {
        capability "Actuator"
        capability "Notification"
        capability "SpeechSynthesis"
        attribute "lastMessage", "string"
        attribute "messageCount", "number"
    }
}

void installed() { sendEvent(name: "messageCount", value: 0) }
void updated() { }

void deviceNotification(String text) { record(text) }
void speak(String text, Number volume = null, String voice = null) { record(text) }

private void record(String text) {
    sendEvent(name: "lastMessage", value: text, isStateChange: true)
    sendEvent(name: "messageCount", value: ((device.currentValue("messageCount") ?: 0) as int) + 1)
}

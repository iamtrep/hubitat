// Copyright (c) 2026 PJ
// SPDX-License-Identifier: MIT
//
// Shared block: send a polled value only when it differs from the attribute's current value,
// and report health on change. For drivers fed by a poll of a cloud or LAN service; a driver
// that relays device reports calls sendEvent on every report (ARCHITECTURE.md).
// See shared/README.md.
// block-version: 1
// requires: logWarn, logInfo

// Returns true when the event was sent. Values compare as text, the way the hub stores them.
// An attribute the metadata does not declare has no current value and is sent every time.
boolean sendChanged(Map evt) {
    if (evt.value == null) return false
    if (device.currentState(evt.name as String)?.value == evt.value.toString()) return false
    sendEvent(evt)
    return true
}

// Called by the parent with the service's own evidence: offline while the service is
// unreachable or reports the device offline. The reason goes in the event description.
void updateHealth(String status, String reason = null) {
    String prev = device.currentValue("healthStatus")
    String why = reason ? ": ${reason}" : ""
    if (!sendChanged(name: "healthStatus", value: status, descriptionText: "${device.displayName} is ${status}${why}")) return
    if (prev == null) return
    if (status == "offline") logWarn "offline${why}"
    else logInfo "back online"
}

// Copyright (c) 2026 PJ
// SPDX-License-Identifier: MIT
//
// Shared block: send a polled value only when it differs from the attribute's current value,
// and report health on change. For drivers fed by a poll of a service that gives no time for
// the device's own report; where it gives one, a new time is a device report and goes out with
// sendEvent (ARCHITECTURE.md, Cloud and LAN polling).
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

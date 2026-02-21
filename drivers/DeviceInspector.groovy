/*

 MIT License

 Copyright (c) 2026

 Permission is hereby granted, free of charge, to any person obtaining a copy
 of this software and associated documentation files (the "Software"), to deal
 in the Software without restriction, including without limitation the rights
 to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 copies of the Software, and to permit persons to whom the Software is
 furnished to do so, subject to the following conditions:

 The above copyright notice and this permission notice shall be included in all
 copies or substantial portions of the Software.

 THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 SOFTWARE.

 Device Inspector — exposes all DeviceWrapper / Device properties and methods.

 Install this driver on any virtual device, then run "Inspect" (or Refresh) to
 dump every available property to the device log and update the corresponding
 attributes.  Use the individual "test*" commands to exercise specific API calls
 interactively.

*/

import groovy.json.JsonOutput

metadata {
    definition(
        name: "Device Inspector",
        namespace: "iamtrep",
        author: "iamtrep",
        importUrl: "https://raw.githubusercontent.com/iamtrep/hubitat/refs/heads/main/drivers/DeviceInspector.groovy"
    ) {
        capability "Refresh"

        // ── Identity ──────────────────────────────────────────────────────────
        attribute "deviceId",        "number"
        attribute "deviceName",      "string"
        attribute "deviceLabel",     "string"
        attribute "displayName",     "string"
        attribute "typeName",        "string"
        attribute "typeId",          "number"
        attribute "deviceNetworkId", "string"
        attribute "zigbeeId",        "string"
        attribute "endpointId",      "string"

        // ── Hub ───────────────────────────────────────────────────────────────
        attribute "hubId",       "number"
        attribute "hubName",     "string"
        attribute "hubLocalIP",  "string"
        attribute "hubPort",     "number"
        attribute "hubFirmware", "string"
        attribute "hubHardware", "string"
        attribute "hubType",     "string"
        attribute "hubZigbeeId", "string"

        // ── Location ──────────────────────────────────────────────────────────
        attribute "roomId",   "number"
        attribute "roomName", "string"

        // ── API surfaces (JSON lists) ─────────────────────────────────────────
        attribute "capabilities",        "string"
        attribute "supportedAttributes", "string"
        attribute "supportedCommands",   "string"
        attribute "currentStates",       "string"
        attribute "deviceData",          "string"

        // ── Housekeeping ──────────────────────────────────────────────────────
        attribute "lastInspection", "string"

        // ── Bulk inspection ───────────────────────────────────────────────────
        command "inspect"
        command "inspectIdentity"
        command "inspectHub"
        command "inspectCapabilities"
        command "inspectAttributes"
        command "inspectCommands"
        command "inspectCurrentStates"
        command "inspectData"
        command "inspectSettings"
        command "inspectEvents", [
            [name: "maxEvents", type: "NUMBER", description: "Max events to retrieve (default 10)"]
        ]

        // ── Targeted query commands ───────────────────────────────────────────
        command "testHasAttribute", [
            [name: "attributeName", type: "STRING", description: "Attribute name to test"]
        ]
        command "testHasCommand", [
            [name: "commandName", type: "STRING", description: "Command name to test"]
        ]
        command "testHasCapability", [
            [name: "capabilityName", type: "STRING", description: "Capability name to test"]
        ]
        command "testCurrentValue", [
            [name: "attributeName", type: "STRING", description: "Attribute name to query"]
        ]
        command "testStatesSince", [
            [name: "attributeName", type: "STRING",  description: "Attribute name"],
            [name: "hoursBack",     type: "NUMBER",  description: "Hours to look back (default 24)"]
        ]
        command "testEventsSince", [
            [name: "hoursBack", type: "NUMBER", description: "Hours to look back (default 24)"]
        ]
        command "testGetDataValue", [
            [name: "key", type: "STRING", description: "Data key to retrieve"]
        ]
        command "testUpdateDataValue", [
            [name: "key",   type: "STRING", description: "Data key"],
            [name: "value", type: "STRING", description: "Data value"]
        ]
        command "testRemoveDataValue", [
            [name: "key", type: "STRING", description: "Data key to remove"]
        ]
    }

    preferences {
        input name: "logEnable",  type: "bool", title: "Enable debug logging", defaultValue: true
        input name: "txtEnable",  type: "bool", title: "Enable description text logging", defaultValue: true
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Lifecycle
// ─────────────────────────────────────────────────────────────────────────────

void installed() {
    logDebug "installed()"
    inspect()
}

void updated() {
    logDebug "updated()"
}

void refresh() {
    inspect()
}

// ─────────────────────────────────────────────────────────────────────────────
// Bulk inspection commands
// ─────────────────────────────────────────────────────────────────────────────

void inspect() {
    log.info "${device.displayName}: ════ Device Inspector — Full Inspection ════"
    inspectIdentity()
    inspectHub()
    inspectCapabilities()
    inspectAttributes()
    inspectCommands()
    inspectCurrentStates()
    inspectData()
    inspectSettings()
    String ts = new Date().format("yyyy-MM-dd HH:mm:ss z")
    sendEvent(name: "lastInspection", value: ts, descriptionText: "Inspection completed at ${ts}")
    log.info "${device.displayName}: ════ Inspection complete at ${ts} ════"
}

// ── Identity ──────────────────────────────────────────────────────────────────

void inspectIdentity() {
    log.info "${device.displayName}: ── Identity ──────────────────────────────────"
    logProp "device.id",              device.id
    logProp "device.name",            device.name
    logProp "device.label",           device.label
    logProp "device.displayName",     device.displayName
    logProp "device.typeName",        device.typeName
    logProp "device.typeId",          device.typeId
    logProp "device.deviceNetworkId", device.deviceNetworkId
    logProp "device.zigbeeId",        device.zigbeeId
    logProp "device.endpointId",      device.endpointId

    sendEvent(name: "deviceId",        value: device.id)
    sendEvent(name: "deviceName",      value: device.name      ?: "")
    sendEvent(name: "deviceLabel",     value: device.label     ?: "")
    sendEvent(name: "displayName",     value: device.displayName ?: "")
    sendEvent(name: "typeName",        value: device.typeName  ?: "")
    sendEvent(name: "typeId",          value: device.typeId)
    sendEvent(name: "deviceNetworkId", value: device.deviceNetworkId ?: "")
    sendEvent(name: "zigbeeId",        value: device.zigbeeId  ?: "")
    sendEvent(name: "endpointId",      value: device.endpointId ?: "")
}

// ── Hub ───────────────────────────────────────────────────────────────────────

void inspectHub() {
    log.info "${device.displayName}: ── Hub ───────────────────────────────────────"
    def hub = device.hub
    logProp "device.hubId",                          device.hubId
    logProp "device.hub",                            hub
    logProp "device.hub.id",                         hub?.id
    logProp "device.hub.name",                       hub?.name
    logProp "device.hub.localIP",                    hub?.localIP
    logProp "device.hub.localSrvPortTCP",            hub?.localSrvPortTCP
    logProp "device.hub.firmwareVersionString",      hub?.firmwareVersionString
    logProp "device.hub.hardwareID",                 hub?.hardwareID
    logProp "device.hub.type",                       hub?.type
    logProp "device.hub.zigbeeId",                   hub?.zigbeeId
    logProp "device.hub.zigbeeEui",                  hub?.zigbeeEui

    log.info "${device.displayName}: ── Location ──────────────────────────────────"
    logProp "device.roomId", device.roomId
    logProp "device.room",   device.room

    sendEvent(name: "hubId",       value: hub?.id)
    sendEvent(name: "hubName",     value: hub?.name                    ?: "")
    sendEvent(name: "hubLocalIP",  value: hub?.localIP                 ?: "")
    sendEvent(name: "hubPort",     value: hub?.localSrvPortTCP)
    sendEvent(name: "hubFirmware", value: hub?.firmwareVersionString   ?: "")
    sendEvent(name: "hubHardware", value: hub?.hardwareID?.toString()  ?: "")
    sendEvent(name: "hubType",     value: hub?.type?.toString()        ?: "")
    sendEvent(name: "hubZigbeeId", value: hub?.zigbeeId                ?: "")
    sendEvent(name: "roomId",      value: device.roomId)
    sendEvent(name: "roomName",    value: device.room                  ?: "")
}

// ── Capabilities ──────────────────────────────────────────────────────────────

void inspectCapabilities() {
    log.info "${device.displayName}: ── Capabilities ──────────────────────────────"
    List caps = device.getCapabilities()
    log.info "${device.displayName}:   count = ${caps?.size()}"
    List<String> capNames = []
    caps?.each { cap ->
        log.info "${device.displayName}:     ${cap.name}"
        capNames << cap.name.toString()
    }
    sendEvent(name: "capabilities", value: JsonOutput.toJson(capNames))
}

// ── Supported Attributes ──────────────────────────────────────────────────────

void inspectAttributes() {
    log.info "${device.displayName}: ── Supported Attributes ──────────────────────"
    List attrs = device.getSupportedAttributes()
    log.info "${device.displayName}:   count = ${attrs?.size()}"
    List<Map> attrMaps = []
    attrs?.each { attr ->
        Map m = [
            name:           attr.name,
            dataType:       attr.dataType,
            possibleValues: attr.possibleValues
        ]
        attrMaps << m
        log.info "${device.displayName}:     name=${attr.name}  type=${attr.dataType}  possibleValues=${attr.possibleValues}"
    }
    sendEvent(name: "supportedAttributes", value: JsonOutput.toJson(attrMaps))
}

// ── Supported Commands ────────────────────────────────────────────────────────

void inspectCommands() {
    log.info "${device.displayName}: ── Supported Commands ────────────────────────"
    List cmds = device.getSupportedCommands()
    log.info "${device.displayName}:   count = ${cmds?.size()}"
    List<Map> cmdMaps = []
    cmds?.each { cmd ->
        List<Map> params = cmd.parameters?.collect { p ->
            [name: p.name, type: p.type, constraints: p.constraints]
        } ?: []
        Map m = [name: cmd.name, parameters: params]
        cmdMaps << m
        log.info "${device.displayName}:     name=${cmd.name}  parameters=${params}"
    }
    sendEvent(name: "supportedCommands", value: JsonOutput.toJson(cmdMaps))
}

// ── Current States ────────────────────────────────────────────────────────────

void inspectCurrentStates() {
    log.info "${device.displayName}: ── Current States ────────────────────────────"
    List attrs = device.getSupportedAttributes()
    List<Map> stateMaps = []
    attrs?.each { attr ->
        String attrName = attr.name.toString()
        def val  = device.currentValue(attrName)
        def st   = device.currentState(attrName)
        Map m = [
            attribute: attrName,
            value:     val,
            unit:      st?.unit,
            date:      st?.date?.toString(),
            dataType:  st?.dataType
        ]
        stateMaps << m
        log.info "${device.displayName}:     ${attrName}: value=${val}  unit=${st?.unit}  date=${st?.date}  dataType=${st?.dataType}"
    }
    sendEvent(name: "currentStates", value: JsonOutput.toJson(stateMaps))
}

// ── Device Data ───────────────────────────────────────────────────────────────

void inspectData() {
    log.info "${device.displayName}: ── Device Data (getData) ─────────────────────"
    Map data = device.getData() ?: [:]
    log.info "${device.displayName}:   ${data}"
    sendEvent(name: "deviceData", value: JsonOutput.toJson(data))
}

// ── Settings ──────────────────────────────────────────────────────────────────

void inspectSettings() {
    log.info "${device.displayName}: ── Settings (preferences) ────────────────────"
    settings?.each { String k, v ->
        log.info "${device.displayName}:     ${k} = ${v} (${v?.class?.simpleName})"
    }
}

// ── Recent Events ─────────────────────────────────────────────────────────────

void inspectEvents(Number maxEvents = 10) {
    int max = (maxEvents ?: 10).toInteger()
    log.info "${device.displayName}: ── Events (max ${max}) ───────────────────────"
    List evts = device.events([max: max])
    log.info "${device.displayName}:   count = ${evts?.size()}"
    evts?.each { evt ->
        log.info "${device.displayName}:     name=${evt.name}  value=${evt.value}  date=${evt.date}  source=${evt.source}  type=${evt.type}"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Targeted test commands
// ─────────────────────────────────────────────────────────────────────────────

// ── Boolean queries ───────────────────────────────────────────────────────────

void testHasAttribute(String attributeName) {
    boolean result = device.hasAttribute(attributeName)
    log.info "${device.displayName}: device.hasAttribute('${attributeName}') = ${result}"
}

void testHasCommand(String commandName) {
    boolean result = device.hasCommand(commandName)
    log.info "${device.displayName}: device.hasCommand('${commandName}') = ${result}"
}

void testHasCapability(String capabilityName) {
    boolean result = device.hasCapability(capabilityName)
    log.info "${device.displayName}: device.hasCapability('${capabilityName}') = ${result}"
}

// ── Value / State retrieval ───────────────────────────────────────────────────

void testCurrentValue(String attributeName) {
    log.info "${device.displayName}: ── currentValue / currentState / latestValue / latestState ──"

    // currentValue — two overloads (with and without skipCache)
    def val          = device.currentValue(attributeName)
    def valNoCache   = device.currentValue(attributeName, true)
    log.info "${device.displayName}:   device.currentValue('${attributeName}')             = ${val} (${val?.class?.simpleName})"
    log.info "${device.displayName}:   device.currentValue('${attributeName}', true)       = ${valNoCache}"

    // latestValue — alias for currentValue, included for completeness
    def latVal = device.latestValue(attributeName)
    log.info "${device.displayName}:   device.latestValue('${attributeName}')              = ${latVal}"

    // currentState — two overloads
    def st          = device.currentState(attributeName)
    def stNoCache   = device.currentState(attributeName, true)
    log.info "${device.displayName}:   device.currentState('${attributeName}')             → name=${st?.name}  value=${st?.value}  unit=${st?.unit}  date=${st?.date}  dataType=${st?.dataType}"
    log.info "${device.displayName}:   device.currentState('${attributeName}', true)       → name=${stNoCache?.name}  value=${stNoCache?.value}"

    // latestState — alias for currentState
    def latSt = device.latestState(attributeName)
    log.info "${device.displayName}:   device.latestState('${attributeName}')              → name=${latSt?.name}  value=${latSt?.value}"
}

// ── Historical State ──────────────────────────────────────────────────────────

void testStatesSince(String attributeName, Number hoursBack = 24) {
    double hours = (hoursBack ?: 24).toDouble()
    Date since   = new Date(now() - (long)(hours * 3600000))
    log.info "${device.displayName}: ── statesSince('${attributeName}', ${since}) ──────"
    List states = device.statesSince(attributeName, since, [max: 20])
    log.info "${device.displayName}:   count = ${states?.size()}"
    states?.each { s ->
        log.info "${device.displayName}:     value=${s.value}  unit=${s.unit}  date=${s.date}  dataType=${s.dataType}"
    }
}

void testEventsSince(Number hoursBack = 24) {
    double hours = (hoursBack ?: 24).toDouble()
    Date since   = new Date(now() - (long)(hours * 3600000))
    log.info "${device.displayName}: ── eventsSince(${since}) ──────────────────────"
    List evts = device.eventsSince(since, [max: 20])
    log.info "${device.displayName}:   count = ${evts?.size()}"
    evts?.each { evt ->
        log.info "${device.displayName}:     name=${evt.name}  value=${evt.value}  date=${evt.date}  source=${evt.source}"
    }
}

// ── Device Data manipulation ──────────────────────────────────────────────────

void testGetDataValue(String key) {
    String val = device.getDataValue(key)
    log.info "${device.displayName}: device.getDataValue('${key}') = ${val}"
}

void testUpdateDataValue(String key, String value) {
    log.info "${device.displayName}: device.updateDataValue('${key}', '${value}')"
    device.updateDataValue(key, value)
    log.info "${device.displayName}:   confirmed → getDataValue('${key}') = ${device.getDataValue(key)}"
    inspectData()
}

void testRemoveDataValue(String key) {
    log.info "${device.displayName}: device.removeDataValue('${key}')"
    device.removeDataValue(key)
    log.info "${device.displayName}:   confirmed → getData() = ${device.getData()}"
    inspectData()
}

// ─────────────────────────────────────────────────────────────────────────────
// Helpers
// ─────────────────────────────────────────────────────────────────────────────

private String formatProp(String name, Object value) {
    String typeName = value == null ? "null" : getObjectClassName(value)
    return "  ${name.padRight(42)} = ${value}  (${typeName})"
}

private void logProp(String name, Object value) {
    log.info "${device.displayName}:${formatProp(name, value)}"
}

private void logDebug(String message) {
    if (logEnable) log.debug "${device.displayName}: ${message}"
}

private void logInfo(String message) {
    if (txtEnable) log.info "${device.displayName}: ${message}"
}

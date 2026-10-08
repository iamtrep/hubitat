// Copyright (c) 2025-2026 PJ
// SPDX-License-Identifier: MIT
//
// Mode 4 (extraction variant) unit test for Z-Wave ghost-node classification.
//
// zwaveDetails carries two lists: nodes[] (the radio's nodes, with route/state/RSSI) and
// zwDevices (Hubitat device records keyed by node id). A ghost is a radio node with no
// device. A device with no radio entry is not a ghost: on the Z/IP stack, Z-Wave Long Range
// nodes (id >= 256) are absent from nodes[] while their devices work normally.
//
// This BRACE-EXTRACTS buildZwaveGhostNodes from the shipped HubInspector.groovy, so it stays
// bound to the shipped code; no live hub or Long Range hardware required.
//
// Run: groovy apps/HubInspector/tests/test-zwave-ghost-nodes.groovy

File scriptFile = new File(getClass().protectionDomain.codeSource.location.toURI())
File groovySrc = new File(scriptFile.parentFile.parentFile, 'HubInspector.groovy')
assert groovySrc.exists() : "source not found: ${groovySrc}"
String src = groovySrc.text

String extract(String src, String signature) {
    int start = src.indexOf(signature)
    assert start >= 0 : "signature not found: ${signature}"
    int open = src.indexOf('{', start)
    int depth = 0
    for (int i = open; i < src.length(); i++) {
        if (src[i] == '{') depth++
        else if (src[i] == '}') { depth--; if (depth == 0) return src.substring(start, i + 1) }
    }
    throw new IllegalStateException("unbalanced braces for: ${signature}")
}

def build = new GroovyShell().evaluate(
    extract(src, 'List buildZwaveGhostNodes(Map zwaveDetails) {') + "\nthis.&buildZwaveGhostNodes")

// Fixture shaped like a real Z/IP USLR hub: two healthy mesh nodes, one radio node with no
// device, one FAILED node, two Long Range devices missing from nodes[], and one ordinary
// device missing from nodes[].
Map fixture = [
    nodes: [
        [nodeId: 12, deviceId: 300, deviceName: 'Fan',    nodeState: 'OK',     route: '01 -> 0C 100kbps', zwaveType: 'SPECIFIC_TYPE FAN_SWITCH'],
        [nodeId: 13, deviceId: 309, deviceName: 'Door',   nodeState: 'OK',     route: '01 -> 10 -> 0D 40kbps', zwaveType: 'SPECIFIC_TYPE ROUTING_SENSOR_NOTIFICATION'],
        [nodeId: 20, deviceId: null, deviceName: '',      nodeState: 'OK',     route: '',                 zwaveType: 'SPECIFIC_TYPE NOT_USED'],
        [nodeId: 21, deviceId: 320, deviceName: 'Lock',   nodeState: 'FAILED', route: '',                 zwaveType: 'SPECIFIC_TYPE SECURE_KEYPAD_DOOR_LOCK'],
        [nodeId: 22, deviceId: null, deviceName: '',      nodeState: 'OK',     route: '',                 zwaveType: 'GENERIC_TYPE STATIC_CONTROLLER'],
    ],
    zwDevices: [
        '12':  [id: 300,  name: 'Generic Z-Wave Fan',                    label: 'Fan',                      deviceNetworkId: '0C'],
        '13':  [id: 309,  name: 'Ring Alarm Contact Sensor G2',          label: 'Door',                     deviceNetworkId: '0D'],
        '21':  [id: 320,  name: 'Generic Z-Wave Lock',                   label: 'Lock',                     deviceNetworkId: '15'],
        '270': [id: 900, name: 'Generic Z-Wave Plus Button Controller', label: 'LR remote 1', deviceNetworkId: '010E'],
        '271': [id: 901, name: 'Generic Z-Wave DT Central Scene',       label: 'LR remote 2', deviceNetworkId: '010F'],
        '30':  [id: 400,  name: 'Generic Z-Wave Switch',                 label: 'Porch',                    deviceNetworkId: '1E'],
    ]
]

List out = build(fixture)
Map byId = out.collectEntries { [(it.id.toString()): it] }

int failures = 0
def check = { String label, boolean ok ->
    println("${ok ? 'PASS' : 'FAIL'}  ${label}")
    if (!ok) failures++
}

check('healthy nodes are not reported',            !byId.containsKey('12') && !byId.containsKey('13'))
check('radio node with no device is a ghost',      byId['20']?.kind == 'ghost' && byId['20']?.deviceId == null)
check('FAILED node with a device is failed',       byId['21']?.kind == 'failed' && byId['21']?.deviceId == 320L)
check('deviceless controller node is skipped',     !byId.containsKey('22'))
check('LR node 270 is longRange, not ghost',       byId['270']?.kind == 'longRange' && byId['270']?.deviceId == 900L)
check('LR node 271 is longRange, not ghost',       byId['271']?.kind == 'longRange' && byId['271']?.deviceId == 901L)
check('LR row shows the device label',             byId['270']?.name == 'LR remote 1')
check('non-LR device missing from radio: unlisted', byId['30']?.kind == 'unlisted' && byId['30']?.deviceId == 400L)
check('exactly one ghost',                         out.count { it.kind == 'ghost' } == 1)
check('empty input yields empty list',             build([:]) == [] && build(null) == [])

println(failures ? "\n${failures} FAILED" : "\nall passed")
System.exit(failures ? 1 : 0)

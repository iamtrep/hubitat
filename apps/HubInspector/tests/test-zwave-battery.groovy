// Copyright (c) 2025-2026 PJ
// SPDX-License-Identifier: MIT
//
// Mode 4 (extraction variant) unit test for Z-Wave battery detection.
//
// Some built-in Z-Wave drivers (e.g. Aeon Minimote) lack the Battery capability, so the device
// never gets a `battery` value. zwaveBatteryDeviceIds() falls back on the radio's `listening`
// flag: sleeping nodes have no `listening` key on the legacy stack and `listening: false` on
// Z-Wave JS, where FLiRS locks are also false. Mains nodes are `listening: true` on both.
//
// This BRACE-EXTRACTS zwaveBatteryDeviceIds from the shipped HubInspector.groovy, so it stays
// bound to the shipped code; no live hub required.
//
// Run: groovy apps/HubInspector/tests/test-zwave-battery.groovy

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

int fetches = 0
Map zwaveFixture = null
String harness = """
@groovy.transform.Field static final long RADIO_CACHE_TTL_MS = 60000L
@groovy.transform.Field static final String ZWAVE_DETAILS_PATH = "/hub/zwaveDetails/json"
${extract(src, 'Set<String> zwaveBatteryDeviceIds(List devicesList) {')}
this
"""
def app = new GroovyShell().evaluate(harness)
app.metaClass.cachedFetch = { String key, long ttl, Closure c -> fetches++; c() }
app.metaClass.hubMapRequest = { String path, String label, int timeout -> [ok: zwaveFixture != null, data: zwaveFixture] }

List zwaveDevices = [[data: [id: 101, isZwave: true]], [data: [id: 200, isZwave: false]]]

// Legacy stack: sleeping sensors omit `listening`; mains switch and FLiRS lock report true.
zwaveFixture = [nodes: [
    [nodeId: 1,  deviceId: null],                     // controller
    [nodeId: 12, deviceId: 101, listening: true],     // mains switch
    [nodeId: 13, deviceId: 102],                      // sleeping contact sensor
    [nodeId: 14, deviceId: 103, listening: true],     // FLiRS lock (reports `battery` itself)
    [nodeId: 15, deviceId: 104],                      // sleeping key fob
]]
assert app.zwaveBatteryDeviceIds(zwaveDevices) == ['102', '104'] as Set

// Z-Wave JS: FLiRS locks are listening=false.
zwaveFixture = [nodes: [
    [nodeId: 12, deviceId: 101, listening: true],
    [nodeId: 13, deviceId: 105, listening: false, batteryPercent: 91],
]]
assert app.zwaveBatteryDeviceIds(zwaveDevices) == ['105'] as Set

// No Z-Wave devices: no fetch at all.
fetches = 0
assert app.zwaveBatteryDeviceIds([[data: [id: 200, isZwave: false]]]).isEmpty()
assert fetches == 0

// Fetch failure: empty, no exception.
zwaveFixture = null
assert app.zwaveBatteryDeviceIds(zwaveDevices).isEmpty()

println "test-zwave-battery: all assertions passed"

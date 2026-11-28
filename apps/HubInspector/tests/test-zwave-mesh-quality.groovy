// Copyright (c) 2025-2026 PJ
// SPDX-License-Identifier: MIT
//
// Mode 4 (extraction variant) unit test for the Z-Wave route-change normalization.
//
// extractZwaveMeshQuality / extractZwaveMessageCounts must map a non-numeric hub
// routeChanges to null — not a "-1" sentinel. The SPA's zwMeshStats sums the mesh-wide total
// and skips those nulls (covered by test-network-derivations.js). The non-numeric case is what
// a Z-Wave Long Range node (no mesh routing) and a node with no accumulated traffic both
// surface as. It also covers the problem-node rule of computeZwaveSignals (PER failure rate on
// Z/IP, never on Z-Wave JS).
//
// Unlike a Python mirror (Mode 4), this BRACE-EXTRACTS the two methods from the shipped
// HubInspector.groovy and runs the real Groovy semantics — so it stays bound to the
// shipped code, no live hub or Long Range hardware required.
//
// Run: groovy apps/HubInspector/tests/test-zwave-mesh-quality.groovy

// Locate HubInspector.groovy relative to this script (CWD-independent).
File scriptFile = new File(getClass().protectionDomain.codeSource.location.toURI())
File groovySrc = new File(scriptFile.parentFile.parentFile, 'HubInspector.groovy')
assert groovySrc.exists() : "source not found: ${groovySrc}"
String src = groovySrc.text

// Extract a `<ReturnType> NAME(...) { ... }` method by brace matching.
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

String mesh = extract(src, 'Map extractZwaveMeshQuality(Map zwaveData) {')
String msgs = extract(src, 'List extractZwaveMessageCounts(Map zwaveData) {')
String rate = extract(src, 'BigDecimal zwavePerRate(int per, int msgCount) {')
String signals = extract(src, 'Map computeZwaveSignals(Map zwRaw) {')
java.util.regex.Matcher critM = (src =~ /@Field static final BigDecimal ZWAVE_PER_RATE_CRIT = ([0-9.]+G)/)
assert critM.find() : 'ZWAVE_PER_RATE_CRIT not found'

// Compile the extracted methods and hand back callable references. Ghost-node detection is
// stubbed out: computeZwaveSignals is tested here for its problem-node rule only.
def harness = new GroovyShell().evaluate(
    "ZWAVE_PER_RATE_CRIT = ${critM.group(1)}\nList buildZwaveGhostNodes(Map z) { [] }\n" +
    mesh + "\n" + msgs + "\n" + rate + "\n" + signals +
    "\n[mesh: this.&extractZwaveMeshQuality, msgs: this.&extractZwaveMessageCounts, signals: this.&computeZwaveSignals]")

// Fixture: the node shapes the hub actually produces. No hardware needed.
Map fixture = [nodes: [
    [nodeId: 2, deviceName: 'Mesh node',     routeChanges: 3,         per: 0, neighbors: 4],
    [nodeId: 3, deviceName: 'LR (no field)',                          per: 0, neighbors: 0], // Long Range: field absent
    [nodeId: 4, deviceName: 'Hub gave null', routeChanges: null,      per: 0, neighbors: 0],
    [nodeId: 5, deviceName: 'Non-numeric',   routeChanges: 'unknown', per: 0, neighbors: 0],
    [nodeId: 6, deviceName: 'Legit zero',    routeChanges: 0,         per: 0, neighbors: 0],
]]

int pass = 0, fail = 0
def check = { String name, boolean cond ->
    if (cond) { pass++; println "  [PASS] ${name}" } else { fail++; println "  [FAIL] ${name}" }
}

println 'extractZwaveMeshQuality / extractZwaveMessageCounts (Z-Wave route-change normalization)'

Map q = harness.mesh(fixture)
Map byId = q.nodes.collectEntries { [(it.nodeId): it] }
check('mesh node keeps its count (3)',            byId[2].routeChanges == 3)
check('LR node with absent field -> null',        byId[3].routeChanges == null)
check('hub-reported null -> null',                byId[4].routeChanges == null)
check('non-numeric -> null',                      byId[5].routeChanges == null)
check('legit zero preserved (0, not null)',       byId[6].routeChanges == 0)

List m = harness.msgs(fixture)
Map mById = m.collectEntries { [(it.name): it] }
check('msg-count: mesh node keeps 3',             mById['Mesh node'].routeChanges == 3)
check('msg-count: LR absent field -> null',       mById['LR (no field)'].routeChanges == null)
check('msg-count: non-numeric -> null',           mById['Non-numeric'].routeChanges == null)

// Problem nodes: state != OK, or on Z/IP a failure rate per / (msgCount + per) above 1%.
// per counts undelivered messages, msgCount delivered ones. On Z-Wave JS per never flags.
println '\ncomputeZwaveSignals (problem-node rule)'
Map zip = [zwaveJS: false, nodes: [
    [nodeId: 2, deviceName: 'Quiet errors', nodeState: 'OK', per: 2,  msgCount: 10000],   // 0.02%
    [nodeId: 3, deviceName: 'Lossy',        nodeState: 'OK', per: 10, msgCount: 122],     // 7.6%
    [nodeId: 4, deviceName: 'No traffic',   nodeState: 'OK', per: 0,  msgCount: 0],
]]
Map zipQ = harness.mesh(zip).nodes.collectEntries { [(it.nodeId): it] }
check('Z/IP perRate per=2,msgCount=10000 -> 0.0',   zipQ[2].perRate == 0.0G)
check('Z/IP perRate per=10,msgCount=122 -> 7.6',    zipQ[3].perRate == 7.6G)
check('Z/IP perRate per=0,msgCount=0 -> 0',         zipQ[4].perRate == 0G)
check('Z/IP: only the lossy node is a problem node', harness.signals(zip).problemCount == 1)
check('Z/IP: per=2,msgCount=10000 alone is not a problem node',
      harness.signals([zwaveJS: false, nodes: [zip.nodes[0]]]).problemCount == 0)
check('Z/IP: per=10,msgCount=122 alone is a problem node',
      harness.signals([zwaveJS: false, nodes: [zip.nodes[1]]]).problemCount == 1)
Map js = [zwaveJS: true, nodes: [
    [nodeId: 5, deviceName: 'JS node',   nodeState: 'OK',   per: 50, msgCount: 10],
    [nodeId: 6, deviceName: 'JS failed', nodeState: 'FAILED', per: 0, msgCount: 0],
]]
check('Z-Wave JS: perRate is null',                 harness.mesh(js).nodes.every { it.perRate == null })
check('Z-Wave JS: per=50 not flagged, state still flags', harness.signals(js).problemCount == 1)

println "\n=== ${pass}/${pass + fail} passed ==="
System.exit(fail == 0 ? 0 : 1)

// Copyright (c) 2025-2026 PJ
// SPDX-License-Identifier: MIT
//
// Mode 4 (extraction variant) unit test for the Z-Wave route-change normalization.
//
// extractZwaveMeshQuality / extractZwaveMessageCounts must map a non-numeric hub
// routeChanges to null — not a "-1" sentinel — and exclude those null nodes from the
// mesh-wide route-change total. The non-numeric case is what a Z-Wave Long Range node
// (no mesh routing) and a node with no accumulated traffic both surface as.
//
// Unlike a Python mirror (Mode 4), this BRACE-EXTRACTS the two methods from the shipped
// HubDiagnostics.groovy and runs the real Groovy semantics — so it stays bound to the
// shipped code, no live hub or Long Range hardware required.
//
// Run: groovy apps/HubDiagnostics/tests/test-zwave-mesh-quality.groovy

// Locate HubDiagnostics.groovy relative to this script (CWD-independent).
File scriptFile = new File(getClass().protectionDomain.codeSource.location.toURI())
File groovySrc = new File(scriptFile.parentFile.parentFile, 'HubDiagnostics.groovy')
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

// Compile the extracted methods and hand back callable references.
def harness = new GroovyShell().evaluate(
    mesh + "\n" + msgs + "\n[mesh: this.&extractZwaveMeshQuality, msgs: this.&extractZwaveMessageCounts]")

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
check('total excludes no-data nodes (== 3)',      q.totalRouteChanges == 3)

List m = harness.msgs(fixture)
Map mById = m.collectEntries { [(it.name): it] }
check('msg-count: mesh node keeps 3',             mById['Mesh node'].routeChanges == 3)
check('msg-count: LR absent field -> null',       mById['LR (no field)'].routeChanges == null)
check('msg-count: non-numeric -> null',           mById['Non-numeric'].routeChanges == null)

println "\n=== ${pass}/${pass + fail} passed ==="
System.exit(fail == 0 ? 0 : 1)

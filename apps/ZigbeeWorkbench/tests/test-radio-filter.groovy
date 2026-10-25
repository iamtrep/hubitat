// Copyright (c) 2025-2026 PJ
// SPDX-License-Identifier: MIT
//
// Extraction test for the Zigbee Workbench Radio driver's frame filter and socket-status helpers.
// Brace-extracts the region between the helper markers from the shipped driver; no live hub required.
//
// Run: groovy apps/ZigbeeWorkbench/tests/test-radio-filter.groovy

File scriptFile = new File(getClass().protectionDomain.codeSource.location.toURI())
File groovySrc = new File(scriptFile.parentFile.parentFile, 'ZigbeeWorkbenchRadio.groovy')
assert groovySrc.exists() : "source not found: ${groovySrc}"
String src = groovySrc.text
String BEGIN = '// ==== Helpers begin ===='
String END = '// ==== Helpers end ===='
int cb = src.indexOf(BEGIN)
int ce = src.indexOf(END)
assert cb >= 0 && ce > cb : "signature not found: helper region markers"

def drv = new GroovyShell().evaluate("""
import groovy.transform.CompileStatic
import groovy.transform.Field
${src.substring(cb, ce)}
this
""")

int passed = 0
int failed = 0
def check = { String name, Closure c ->
    boolean ok
    String why = ''
    try { ok = c() } catch (Throwable t) { ok = false; why = " (${t.class.simpleName}: ${t.message})" }
    if (ok) { passed++; println "[PASS] ${name}" } else { failed++; println "[FAIL] ${name}${why}" }
}

Map rx = [type: 'zigbeeRx', id: 0x1A2B, profileId: '0104', clusterId: '0006', sourceEndpoint: '01',
          destinationEndpoint: '01', lastHopLqi: 200, lastHopRssi: -60, time: 123L, payload: ['18', '01', '0A']]
Set w = ['1A2B'] as Set

check('unwatched id -> null') { drv.toFrame(rx + [id: 0x1111], w) == null }
check('wrong type -> null') { drv.toFrame(rx + [type: 'zigbeeTx'], w) == null }
check('no payload -> null') { drv.toFrame(rx.findAll { k, v -> k != 'payload' }, w) == null }
check('null msg -> null') { drv.toFrame(null, w) == null }
check('empty watch -> null') { drv.toFrame(rx, [] as Set) == null }
Map f = drv.toFrame(rx, w)
check('watched frame addr') { f.addr == '1A2B' }
check('payload unchanged') { f.payload == ['18', '01', '0A'] }
check('frame keys') { f.keySet() as Set == ['addr', 'clusterId', 'profileId', 'srcEp', 'dstEp', 'payload', 'lqi', 'rssi', 'time'] as Set }
check('frame fields') { f.clusterId == '0006' && f.profileId == '0104' && f.srcEp == '01' && f.dstEp == '01' && f.lqi == 200 && f.rssi == -60 && f.time == 123L }
check('low address padded upper-case') { drv.toFrame(rx + [id: 0x0A0B], ['0A0B'] as Set)?.addr == '0A0B' && drv.toFrame(rx + [id: 0x00AB], ['00AB'] as Set)?.addr == '00AB' }

// socketDecision: the socket stays open for good, so any drop reconnects, watched addresses or not
check('status: open -> open') { drv.socketDecision('status: open') == 'open' && drv.socketDecision('open') == 'open' }
check('failure -> drop') { drv.socketDecision('failure: null') == 'drop' && drv.socketDecision('failure: Connection refused') == 'drop' }
check('closed -> drop') { drv.socketDecision('status: closed') == 'drop' && drv.socketDecision('closed') == 'drop' }
check('closing and null -> none') { drv.socketDecision('status: closing') == 'none' && drv.socketDecision(null) == 'none' && drv.socketDecision('  ') == 'none' }
check('RECONNECT_S is 10') { drv.RECONNECT_S == 10 }
check('reconnect delay doubles from 10 s to a 300 s cap') {
    [0, 1, 2, 3, 4, 5, 6, 7, 50].collect { drv.reconnectDelay(it) } == [10, 10, 20, 40, 80, 160, 300, 300, 300]
}

check('the late report of our own close is ignored for 5 s; a real drop is not') {
    long t = 1_000_000L
    drv.isOwnClose('status: closed', t - 1000L, t) && drv.isOwnClose('failure: x', t - 4999L, t) &&
        !drv.isOwnClose('status: closed', t - 5001L, t) && !drv.isOwnClose('status: closed', null, t) && !drv.isOwnClose('status: open', t, t)
}

println "\n${passed} passed, ${failed} failed"
System.exit(failed ? 1 : 0)

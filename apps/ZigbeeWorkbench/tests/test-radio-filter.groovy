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

// socketDecision: during a session any drop reconnects, watched addresses or not
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

// frameAddr and wantsMessage: unwatched messages are dropped without a JSON parse
String wire = '{"name":"Th Salle TV SS","id":24492,"profileId":"0104","time":"2026-11-01 12:33:31.462","type":"zigbeeRx","deviceId":229,"payload":["18"]}'
check('frameAddr reads the id of a hub message, not deviceId') { drv.frameAddr(wire) == '5FAC' }
check('frameAddr pads and masks') { drv.frameAddr('{"id":26,"x":1}') == '001A' && drv.frameAddr('{"id":65535,"x":1}') == 'FFFF' }
check('frameAddr gives null for any other form') {
    ['{"name":"x"}', '{"id":-5,"x":1}', '{"id":"12","x":1}', '{"id":12}', '{"id":1234567,"x":1}', '', null].every { drv.frameAddr(it) == null }
}
check('an empty watch list parses nothing') { !drv.wantsMessage(wire, [] as Set) }
check('a null watch list (not restored yet) parses everything') { drv.wantsMessage(wire, null) }
check('only a watched address is parsed') { drv.wantsMessage(wire, ['5FAC'] as Set) && !drv.wantsMessage(wire, ['1234'] as Set) }
check('a message of another form is parsed when anything is watched') { drv.wantsMessage('{"odd":true}', ['1234'] as Set) }
check('the filter agrees with toFrame on the address') {
    Map m = [type: 'zigbeeRx', payload: ['18'], id: 24492]
    drv.toFrame(m, ['5FAC'] as Set)?.addr == drv.frameAddr(wire)
}

// session rules
check('session length: the preference within 1..240, else 30') {
    [null, '', 'x', 0, 241, -5].every { drv.sessionLength(it) == 30 } && drv.sessionLength(1) == 1 && drv.sessionLength('240') == 240 && drv.sessionLength(45.0G.toInteger()) == 45
}
check('a session is active until its end') { drv.sessionActive(11L, 10L) && !drv.sessionActive(10L, 10L) && !drv.sessionActive(null, 10L) }
check('a connect follows the last close by 5 s; no close, no wait') {
    long t = 1_000_000L
    drv.connectDelayMs(null, t) == 100L && drv.connectDelayMs(t, t) == 5000L && drv.connectDelayMs(t - 4000L, t) == 1000L && drv.connectDelayMs(t - 60000L, t) == 100L
}
check('a session end holds while replies are awaited, up to the limit') {
    drv.sessionEndDecision(['1A01'], 0) == 'hold' && drv.sessionEndDecision(['1A01'], drv.MAX_HOLDS - 1) == 'hold' &&
        drv.sessionEndDecision(['1A01'], drv.MAX_HOLDS) == 'close' && drv.sessionEndDecision([], 0) == 'close' && drv.sessionEndDecision(null, 0) == 'close'
}

println "\n${passed} passed, ${failed} failed"
System.exit(failed ? 1 : 0)

// Copyright (c) 2025-2026 PJ
// SPDX-License-Identifier: MIT
//
// Extraction test for the Zigbee Responder's frame pre-filter and connect decision.
// Brace-extracts the region between the helper markers from the shipped driver; no hub.
//
// Run: groovy drivers/zigbee_helpers/tests/test-responder-filter.groovy

File scriptFile = new File(getClass().protectionDomain.codeSource.location.toURI())
File groovySrc = new File(scriptFile.parentFile.parentFile, 'ZigbeeResponder.groovy')
assert groovySrc.exists() : "source not found: ${groovySrc}"
String src = groovySrc.text
int cb = src.indexOf('// ==== Frame filter begin ====')
int ce = src.indexOf('// ==== Frame filter end ====')
assert cb >= 0 && ce > cb : "signature not found: Frame filter region markers"

def drv = new GroovyShell().evaluate("""
import groovy.transform.CompileStatic
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

String frame(String cluster) {
    return '{"name":"Plug","id":49669,"profileId":"0104","clusterId":"' + cluster + '","sourceEndpoint":"01","type":"zigbeeRx","deviceId":"12","payload":["18","01","00"]}'
}

check('Time cluster frame is wanted') { drv.wantsFrame(frame('000A')) }
check('OTA cluster frame is wanted') { drv.wantsFrame(frame('0019')) }
check('lower case and spaced value is wanted') { drv.wantsFrame('{"clusterId": "000a","x":1}') }
check('other clusters are skipped') { ['0006', '0000', '0B04', '0300'].every { !drv.wantsFrame(frame(it)) } }
check('a 000A elsewhere in the frame does not count') {
    !drv.wantsFrame('{"clusterId":"0006","sourceEndpoint":"01","deviceId":"000A","payload":["19","00","0A"]}')
}
check('no clusterId, null and empty are skipped') { !drv.wantsFrame('{"id":1}') && !drv.wantsFrame(null) && !drv.wantsFrame('') }

check('connect: both responders off, or on with empty lists, does not connect') {
    !drv.responderActive(false, ['12'], false, ['13']) && !drv.responderActive(true, [], true, []) && !drv.responderActive(true, null, true, null)
}
check('connect: one responder on with a device listed connects') {
    drv.responderActive(true, ['12'], false, []) && drv.responderActive(false, ['12'], true, ['13']) && !drv.responderActive(false, ['12'], true, [])
}

println "${passed} passed, ${failed} failed"
System.exit(failed ? 1 : 0)

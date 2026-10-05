// Copyright (c) 2026 PJ
// SPDX-License-Identifier: MIT
//
// Unit test for Device Swap Helper's mobile-dashboard advice. The hub's mobile dashboards
// list devices by room, so the advice depends only on the source and target rooms. This
// BRACE-EXTRACTS dashboardAdvice from the shipped DeviceSwapHelper.groovy.
//
// Run: groovy apps/utilities/tests/test-device-swap-helper-dashboards.groovy

File scriptFile = new File(getClass().protectionDomain.codeSource.location.toURI())
File groovySrc = new File(scriptFile.parentFile.parentFile, 'DeviceSwapHelper.groovy')
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

def advice = new GroovyShell().evaluate(
    extract(src, 'String dashboardAdvice(String sourceRoom, String targetRoom, String targetName, Long targetId) {') +
    "\nthis.&dashboardAdvice")

int pass = 0, fail = 0
def check = { String name, boolean cond ->
    if (cond) { pass++; println "  [PASS] ${name}" } else { fail++; println "  [FAIL] ${name}" }
}

println 'Device Swap Helper: mobile dashboard advice'

String moveFromNone = advice('Kitchen', null, 'New plug', 42L)
check 'target without a room: put it in the source room, linked',
    moveFromNone.contains("<a href='/device/edit/42' target='_blank'>New plug</a> in room <b>Kitchen</b> so they show it") &&
    !moveFromNone.contains('it is in')
check 'target in another room: says where it is now', advice('Kitchen', 'Garage', 'New plug', 42L).contains('(it is in <b>Garage</b> now)')
check 'same room: nothing to do', advice('Kitchen', 'Kitchen', 'New plug', 42L).startsWith('Nothing to do')
check 'source without a room: nothing to do', advice(null, 'Garage', 'New plug', 42L).startsWith('Nothing to do')

println "\n${pass}/${pass + fail} passed${fail ? ", ${fail} failed" : ''}"
System.exit(fail ? 1 : 0)

// Copyright (c) 2026 PJ
// SPDX-License-Identifier: MIT
//
// Core unit tests for Humidity-Based Fan Controller. Parses the block between the
// "Core (pure)" and "End core" markers of the shipped app file and runs it, so the
// tests bind to shipped code. Run under the pinned Groovy 2.4.21 jar:
//   java -cp groovy-all-2.4.21.jar groovy.ui.GroovyMain <this file>

File here = new File(getClass().protectionDomain.codeSource.location.toURI()).parentFile
String src = new File(here.parentFile, 'HumidityFanController.groovy').text
int a = src.indexOf('// ── Core (pure)'), b = src.indexOf('// ── End core')
if (a < 0 || b < a) { println '[FAIL] core markers not found'; System.exit(2) }
core = new GroovyShell().parse('import groovy.transform.CompileStatic; ' + src.substring(a, b))   // the core's @CompileStatic needs the file's import; same line keeps line numbers

passed = 0; failed = 0   // binding variables: script methods can't see typed locals
void check(String name, Object got, Object want) {
    if (got == want) { passed++; println "[PASS] ${name}" }
    else { failed++; println "[FAIL] ${name}: expected ${want}, got ${got}" }
}

GAP = 300000L
// fanOffAction(physical, wanted, high, restricted, lastReassertAt, now, gap)
check('digital off while high and wanted: reassert', core.fanOffAction(false, true, true, false, null, 1000L, GAP), 'reassert')
check('physical off is always honored', core.fanOffAction(true, true, true, false, null, 1000L, GAP), 'release')
check('physical off honored even within the gap', core.fanOffAction(true, true, true, false, 900L, 1000L, GAP), 'release')
check('not wanted (a human turned it off earlier): release', core.fanOffAction(false, false, true, false, null, 1000L, GAP), 'release')
check('humidity not high: release', core.fanOffAction(false, true, false, false, null, 1000L, GAP), 'release')
check('restricted: release', core.fanOffAction(false, true, true, true, null, 1000L, GAP), 'release')
check('second off within the gap: throttled', core.fanOffAction(false, true, true, false, 0L, GAP - 1L, GAP), 'throttled')
check('off at the gap: reassert again', core.fanOffAction(false, true, true, false, 0L, GAP, GAP), 'reassert')
check('off after the gap: reassert again', core.fanOffAction(false, true, true, false, 0L, GAP + 60000L, GAP), 'reassert')
check('restricted wins over the gap', core.fanOffAction(false, true, true, true, 0L, 1000L, GAP), 'release')

println "\n${passed} passed, ${failed} failed"
System.exit(failed ? 1 : 0)

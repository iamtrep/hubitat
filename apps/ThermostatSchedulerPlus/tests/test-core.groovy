// Copyright (c) 2026 PJ
// SPDX-License-Identifier: MIT
//
// Core unit tests for Thermostat Scheduler+. Parses the block between the
// "Core (pure)" and "End core" markers of the shipped program file and runs it,
// so the tests bind to shipped code. Run under the pinned Groovy 2.4.21 jar:
//   java -cp groovy-all-2.4.21.jar groovy.ui.GroovyMain <this file>

File here = new File(getClass().protectionDomain.codeSource.location.toURI()).parentFile
String src = new File(here.parentFile, 'ThermostatSchedulerPlusProgram.groovy').text
int a = src.indexOf('// ── Core (pure)'), b = src.indexOf('// ── End core')
if (a < 0 || b < a) { println '[FAIL] core markers not found'; System.exit(2) }
core = new GroovyShell().parse(src.substring(a, b))

passed = 0; failed = 0   // binding variables: script methods can't see typed locals; every shared fixture below is untyped for the same reason
void check(String name, Object got, Object want) {
    if (got == want || (got instanceof Number && want instanceof Number && (got as BigDecimal) == (want as BigDecimal))) {
        passed++; println "[PASS] ${name}"
    } else { failed++; println "[FAIL] ${name}: expected ${want}, got ${got}" }
}
TZ = TimeZone.getTimeZone('America/Toronto')

// ── date helpers ──
check('isoDow Saturday', core.isoDow('2026-10-03', TZ), 6)
check('isoDow Monday', core.isoDow('2026-10-05', TZ), 1)
check('addDays across month', core.addDays('2026-10-31', 1, TZ), '2026-11-01')
check('addDays back across DST', core.addDays('2026-11-02', -1, TZ), '2026-11-01')
check('atLocal round trip', core.hhmmOf(core.atLocal('2026-10-03', '06:30', TZ), TZ), '06:30')
check('spring-forward gap moves 02:30 to 03:30', core.hhmmOf(core.atLocal('2026-03-08', '02:30', TZ), TZ), '03:30')
check('fall-back 01:30 exists once', core.hhmmOf(core.atLocal('2026-11-01', '01:30', TZ), TZ), '01:30')
check('numOrNull string', core.numOrNull('18'), 18)
check('numOrNull blank', core.numOrNull(''), null)

// ══ later tasks append cases above this line ══
println "${passed} passed, ${failed} failed"
System.exit(failed ? 1 : 0)

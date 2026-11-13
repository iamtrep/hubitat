// Copyright (c) 2026 PJ
// SPDX-License-Identifier: MIT
//
// Unit tests for the discrete aggregator's overdue sticky commits. Parses the shipped
// "Discrete core" block of SensorAggregatorDiscreteChild.groovy and runs it, so the tests bind to shipped code.
//   java -cp groovy-all-2.4.21.jar groovy.ui.GroovyMain <this file>

File here = new File(getClass().protectionDomain.codeSource.location.toURI()).parentFile
passed = 0; failed = 0
void check(String name, Object got, Object want) {
    if (got == want) { passed++; println "[PASS] ${name}" }
    else { failed++; println "[FAIL] ${name}: expected ${want}, got ${got}" }
}
String src = new File(here.parentFile, 'SensorAggregatorDiscreteChild.groovy').text
int a = src.indexOf('// ── Discrete core (pure)'), b = src.indexOf('// ── End discrete core')
if (a < 0 || b < a) { println '[FAIL] discrete core markers not found'; System.exit(2) }
dc = new GroovyShell().parse(src.substring(a, b))

long T = 1_000_000L
Map pending = ['7': [seq: 2, value: 'open', dueAt: T]]
check('a commit past its due time is applied', dc.overdueCommits(pending, ['7': 2], T + 60_000L), [[sensorId: '7', seq: 2, value: 'open']])
check('a commit not yet due waits', dc.overdueCommits(pending, ['7': 2], T - 1000L), [])
check('the real timer gets a few seconds of slack', dc.overdueCommits(pending, ['7': 2], T + 2000L), [])
check('a superseded commit is dropped', dc.overdueCommits(pending, ['7': 3], T + 60_000L), [])
check('a re-init clears the commits', dc.overdueCommits([:], ['7': 3], T + 60_000L), [])
check('only the overdue sensor is applied', dc.overdueCommits(['7': [seq: 1, value: 'open', dueAt: T], '8': [seq: 1, value: 'closed', dueAt: T + 600_000L]], ['7': 1, '8': 1], T + 60_000L), [[sensorId: '7', seq: 1, value: 'open']])
check('null maps are empty', dc.overdueCommits(null, null, T), [])

println "${passed} passed, ${failed} failed"
System.exit(failed ? 1 : 0)

// Copyright (c) 2026 PJ
// SPDX-License-Identifier: MIT
//
// Core unit tests for the Sensor Aggregator children. Parses the block between the
// "Core (pure)" and "End core" markers of each shipped child file and runs it, so the
// tests bind to shipped code. Run under the pinned Groovy 2.4.21 jar:
//   java -cp groovy-all-2.4.21.jar groovy.ui.GroovyMain <this file>

File here = new File(getClass().protectionDomain.codeSource.location.toURI()).parentFile
passed = 0; failed = 0   // binding variables: script methods can't see typed locals
void check(String name, Object got, Object want) {
    if (got == want) { passed++; println "[PASS] ${name}" }
    else { failed++; println "[FAIL] ${name}: expected ${want}, got ${got}" }
}

String coreOf(File f) {
    String src = f.text
    int a = src.indexOf('// ── Core (pure)'), b = src.indexOf('// ── End core')
    return (a < 0 || b < a) ? null : src.substring(a, b)
}

List<String> files = ['SensorAggregatorChild.groovy', 'SensorAggregatorDiscreteChild.groovy']
Map<String, String> cores = [:]
for (String n : files) {
    File f = new File(here.parentFile, n)
    if (!f.exists()) { println "[FAIL] ${n} not found"; System.exit(2) }
    String c = coreOf(f)
    if (c == null) { println "[FAIL] ${n}: core markers not found"; System.exit(2) }
    cores[n] = c
}
check('both children ship the same core', cores.values().toSet().size(), 1)

core = new GroovyShell().parse(cores[files[0]])
MIN = 60000L

// ── activity ──
check('first activity', core.recordActivity(null, 1000L, 48), [last: 1000L, gaps: []])
check('gap recorded', core.recordActivity([last: 1000L, gaps: []], 4000L, 48), [last: 4000L, gaps: [3000L]])
check('older activity ignored', core.recordActivity([last: 4000L, gaps: [3000L]], 2000L, 48), [last: 4000L, gaps: [3000L]])
check('same activity ignored', core.recordActivity([last: 4000L, gaps: [3000L]], 4000L, 48), [last: 4000L, gaps: [3000L]])
check('null activity ignored', core.recordActivity([last: 4000L, gaps: [3000L]], null, 48), [last: 4000L, gaps: [3000L]])
check('keeps the newest gaps', core.recordActivity([last: 10L, gaps: [1L, 2L, 3L]], 14L, 3), [last: 14L, gaps: [2L, 3L, 4L]])

// ── learned limit ──
check('setting applies with too few gaps', core.quietLimitMs([10 * MIN, 10 * MIN], 60 * MIN, 30 * MIN, 5), 60 * MIN)
check('setting applies with no gaps', core.quietLimitMs(null, 60 * MIN, 30 * MIN, 5), 60 * MIN)
check('twice the second-longest gap', core.quietLimitMs([20 * MIN, 25 * MIN, 22 * MIN, 21 * MIN, 24 * MIN], 30 * MIN, 30 * MIN, 5), 48 * MIN)
check('one outage does not stretch it', core.quietLimitMs([20 * MIN, 600 * MIN, 22 * MIN, 21 * MIN, 24 * MIN], 30 * MIN, 30 * MIN, 5), 48 * MIN)
check('setting is the minimum', core.quietLimitMs([20 * MIN, 25 * MIN, 22 * MIN, 21 * MIN, 24 * MIN], 60 * MIN, 30 * MIN, 5), 60 * MIN)
check('door bursts keep the setting', core.quietLimitMs([619 * MIN, 12000L, 191666L, 246214L, 495787L, 16640L], 360 * MIN, 30 * MIN, 5), 360 * MIN)
check('floor under a low setting', core.quietLimitMs([1 * MIN, 1 * MIN, 1 * MIN, 1 * MIN, 1 * MIN], 10 * MIN, 30 * MIN, 5), 30 * MIN)
check('learned limit can exceed the setting', core.quietLimitMs([180 * MIN, 180 * MIN, 180 * MIN, 180 * MIN, 180 * MIN], 60 * MIN, 30 * MIN, 5), 360 * MIN)

// ── quiet ──
check('never seen is quiet', core.isQuiet(null, 100 * MIN, 60 * MIN), true)
check('within the limit', core.isQuiet([last: 50 * MIN], 100 * MIN, 60 * MIN), false)
check('at the limit is not quiet', core.isQuiet([last: 40 * MIN], 100 * MIN, 60 * MIN), false)
check('past the limit', core.isQuiet([last: 39 * MIN], 100 * MIN, 60 * MIN), true)

// ── all-quiet transitions ──
check('enters all quiet', core.noSensorChange(false, true), 'entered')
check('stays all quiet: silent', core.noSensorChange(true, true), null)
check('leaves all quiet', core.noSensorChange(true, false), 'left')
check('stays reporting: silent', core.noSensorChange(false, false), null)

println "\n${passed} passed, ${failed} failed"
System.exit(failed ? 1 : 0)

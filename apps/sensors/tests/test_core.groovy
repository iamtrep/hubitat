// Copyright (c) 2026 PJ
// SPDX-License-Identifier: MIT
//
// Core unit tests for the Sensor Aggregator children. Parses the block between the
// "Core (pure)" and "End core" markers of each shipped child file, and the discrete
// child's "Discrete core" block, and runs them, so the tests bind to shipped code. Run under the pinned Groovy 2.4.21 jar:
//   java -cp groovy-all-2.4.21.jar groovy.ui.GroovyMain <this file>

File here = new File(getClass().protectionDomain.codeSource.location.toURI()).parentFile
passed = 0; failed = 0   // binding variables: script methods can't see typed locals
void check(String name, Object got, Object want) {
    if (got == want) { passed++; println "[PASS] ${name}" }
    else { failed++; println "[FAIL] ${name}: expected ${want}, got ${got}" }
}

String blockOf(File f, String start, String end) {
    String src = f.text
    int a = src.indexOf(start), b = src.indexOf(end)
    return (a < 0 || b < a) ? null : src.substring(a, b)
}
String coreOf(File f) { blockOf(f, '// ── Core (pure)', '// ── End core') }

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
String discrete = blockOf(new File(here.parentFile, files[1]), '// ── Discrete core (pure)', '// ── End discrete core')
if (discrete == null) { println "[FAIL] ${files[1]}: discrete core markers not found"; System.exit(2) }
dc = new GroovyShell().parse(discrete)
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

// ── discrete aggregation (contact, target open) ──
List<String> v(int open, int closed) { (['open'] * open) + (['closed'] * closed) }
String agg(List<String> vals, String method, Number pct = null) { dc.aggregateOf(vals, method, 'open', 'closed', pct) }
check('any: all closed', agg(v(0, 5), 'any'), 'closed')
check('any: one open', agg(v(1, 4), 'any'), 'open')
check('unknown method acts as any', agg(v(1, 4), null), 'open')
check('all: four of five', agg(v(4, 1), 'all'), 'closed')
check('all: every sensor', agg(v(5, 0), 'all'), 'open')
check('majority: 3 of 5', agg(v(3, 2), 'majority'), 'open')
check('majority: 2 of 5', agg(v(2, 3), 'majority'), 'closed')
check('majority: a tie is not a majority', agg(v(2, 2), 'majority'), 'closed')
check('threshold: at 60%', agg(v(3, 2), 'threshold', 60), 'open')
check('threshold: 75% under 80%', agg(v(3, 1), 'threshold', 80), 'closed')
check('threshold: 4 of 5 at 80%', agg(v(4, 1), 'threshold', 80), 'open')
check('threshold: unset means 50%', agg(v(1, 1), 'threshold'), 'open')
check('inverted target: any closed', dc.aggregateOf(v(5, 0), 'any', 'closed', 'open', null), 'open')
check('inverted target: one closed', dc.aggregateOf(v(4, 1), 'any', 'closed', 'open', null), 'closed')

// ── "and stays" sequence numbers ──
check('first event takes 1', dc.withNextSeq(null, '7'), ['7': 1])
check('next event takes the next number', dc.withNextSeq(['7': 1, '8': 4], '7'), ['7': 2, '8': 4])
check('input map unchanged', { Map m = ['7': 1]; dc.withNextSeq(m, '7'); m }(), ['7': 1])
check('commit with the latest number applies', dc.commitApplies(['7': 2], '7', 2), true)
check('flicker: the superseded commit is dropped', dc.commitApplies(dc.withNextSeq(['7': 1], '7'), '7', 1), false)
// rapid fire open, close, open: only the third commit applies
Map rapid = dc.withNextSeq(dc.withNextSeq(dc.withNextSeq(null, '7'), '7'), '7')
check('rapid fire: earlier commits dropped', [1, 2].collect { dc.commitApplies(rapid, '7', it) }, [false, false])
check('rapid fire: the latest commits', dc.commitApplies(rapid, '7', 3), true)
check('sensors keep their own numbers', dc.commitApplies(dc.withNextSeq(['7': 1, '8': 1], '8'), '7', 1), true)
check('unknown sensor never applies', dc.commitApplies(['7': 1], '9', 1), false)
check('null number never applies', dc.commitApplies(['7': 1], '7', null), false)
check('re-init advances every sensor', dc.advanceAllSeqs(['7': 2, '8': 5]), ['7': 3, '8': 6])
check('re-init drops a pending commit', dc.commitApplies(dc.advanceAllSeqs(['7': 2]), '7', 2), false)
// a new event after re-init must not reuse a number from before it
check('re-init: no number reused', dc.withNextSeq(dc.advanceAllSeqs(['7': 2]), '7')['7'], 4)
check('re-init with nothing pending', dc.advanceAllSeqs(null), [:])

// ── "and stays" window ──
check('default applies without an override', dc.staysSecondsFor(null, 3), 3)
check('override beats the default', dc.staysSecondsFor(5, 2), 5)
check('override 0 disables it for that sensor', dc.staysSecondsFor(0, 5), 0)
check('override with default 0', dc.staysSecondsFor(4, 0), 4)
check('nothing set means immediate', dc.staysSecondsFor(null, null), 0)

println "\n${passed} passed, ${failed} failed"
System.exit(failed ? 1 : 0)

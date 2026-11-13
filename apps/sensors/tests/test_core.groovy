// Copyright (c) 2026 PJ
// SPDX-License-Identifier: MIT
//
// Core unit tests for the Sensor Aggregator children. Parses the block between the
// "Core (pure)" and "End core" markers of each shipped child file, the continuous
// child's "Continuous core" block and the discrete child's "Discrete core" block, and runs them, so the tests bind to shipped code. Run under the pinned Groovy 2.4.21 jar:
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
String continuous = blockOf(new File(here.parentFile, files[0]), '// ── Continuous core (pure)', '// ── End continuous core')
if (continuous == null) { println "[FAIL] ${files[0]}: continuous core markers not found"; System.exit(2) }
cc = new GroovyShell().parse(cores[files[0]] + continuous)   // inclusionOf calls the core's isQuiet
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

// ── continuous statistics (SensorAggregatorChild) ──
// Values compared as printed, so a change of scale or type (22 vs 22.0) fails too.
Map stats(List vals) { cc.statsOf(vals)?.collectEntries { k, v -> [k, v?.toString()] } }
check('stats: one value', stats([20.0]), [min: '20.0', max: '20.0', avg: '20.0', stdev: '0.0', median: '20.0'])
check('stats: one integer', stats([5]), [min: '5.0', max: '5.0', avg: '5.0', stdev: '0.0', median: '5'])
check('stats: odd count takes the middle value', stats([30.0, 20.0, 20.0]), [min: '20.0', max: '30.0', avg: '23.3', stdev: '4.7', median: '20.0'])
check('stats: even count averages the two middle values', stats([20.0, 21.0]), [min: '20.0', max: '21.0', avg: '20.5', stdev: '0.5', median: '20.5'])
check('stats: even count, unsorted integers', stats([30, 21, 23, 20]), [min: '20.0', max: '30.0', avg: '23.5', stdev: '3.9', median: '22'])
check('stats: mixed Integer and BigDecimal, odd', stats([20, 21.5, 22]), [min: '20.0', max: '22.0', avg: '21.2', stdev: '0.9', median: '21.5'])
check('stats: mixed Integer and BigDecimal, even', stats([21.5, 20]), [min: '20.0', max: '21.5', avg: '20.8', stdev: '0.8', median: '20.75'])
check('stats: min and max round to 2 places, the median is not rounded', stats([22.456, 22.454]), [min: '22.45', max: '22.46', avg: '22.5', stdev: '0.0', median: '22.455'])
// Around the exact mean (0.033) the deviation would round to 0.0.
check('stats: deviation is taken around the rounded average', stats([0, 0, 0.1]).stdev, '0.1')
check('stats: min, max, average and deviation are Doubles', cc.statsOf([20, 21.5]).subMap(['min', 'max', 'avg', 'stdev']).values()*.getClass().unique(), [Double])
check('stats: input list unchanged', { List l = [3, 1, 2]; cc.statsOf(l); l }(), [3, 1, 2])
check('stats: no values', cc.statsOf([]), null)
check('stats: null list', cc.statsOf(null), null)

Map s3 = cc.statsOf([10.0, 20.0, 60.0])
check('method average', cc.aggregateFor(s3, 'average').toString(), '30.0')
check('method median', cc.aggregateFor(s3, 'median').toString(), '20.0')
check('method min', cc.aggregateFor(s3, 'min').toString(), '10.0')
check('method max', cc.aggregateFor(s3, 'max').toString(), '60.0')
check('unset method acts as average', cc.aggregateFor(s3, null).toString(), '30.0')

// ── exclusion of quiet sensors ──
check('recent with a value is included', cc.inclusionOf([last: 50 * MIN], 100 * MIN, 60 * MIN, 21.0), 'included')
check('quiet is excluded, value or not', cc.inclusionOf([last: 39 * MIN], 100 * MIN, 60 * MIN, 21.0), 'excluded')
check('never seen is excluded', cc.inclusionOf(null, 100 * MIN, 60 * MIN, 21.0), 'excluded')
check('recent without a value is neither', cc.inclusionOf([last: 50 * MIN], 100 * MIN, 60 * MIN, null), null)
check('a zero reading is included', cc.inclusionOf([last: 50 * MIN], 100 * MIN, 60 * MIN, 0), 'included')
// Three sensors, the quiet one stuck at 100: only the two reporting ones reach the statistics.
List<Map> readings = [[a: [last: 99 * MIN], v: 20.0], [a: [last: 98 * MIN], v: 22.0], [a: [last: 10 * MIN], v: 100.0]]
List kept = readings.findAll { cc.inclusionOf(it.a, 100 * MIN, 60 * MIN, it.v) == 'included' }*.v
check('a quiet sensor does not move the average', cc.statsOf(kept).avg.toString(), '21.0')
check('a quiet sensor does not move the median of an even count', cc.statsOf(kept).median, 21.0)

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

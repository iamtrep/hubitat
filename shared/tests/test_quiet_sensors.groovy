// Copyright (c) 2026 PJ
// SPDX-License-Identifier: MIT
//
// Unit tests for shared/quiet-sensors.groovy, run against the shared source itself.
// The apps' own core tests check their copies; scripts/shared_sync.groovy --check keeps
// those copies identical to this source.
//   groovy shared/tests/test_quiet_sensors.groovy

File here = new File(getClass().protectionDomain.codeSource.location.toURI()).parentFile
core = new GroovyShell().parse(new File(here.parentFile, 'quiet-sensors.groovy'))
passed = 0; failed = 0
void check(String name, Object got, Object want) {
    if (got == want) { passed++; println "[PASS] ${name}" }
    else { failed++; println "[FAIL] ${name}: expected ${want}, got ${got}" }
}
MIN = 60000L

// ── recordActivity ──
check('first activity', core.recordActivity(null, 1000L, 48), [last: 1000L, gaps: []])
check('gap recorded', core.recordActivity([last: 1000L, gaps: []], 4000L, 48), [last: 4000L, gaps: [3000L]])
check('older activity ignored', core.recordActivity([last: 4000L, gaps: [3000L]], 2000L, 48), [last: 4000L, gaps: [3000L]])
check('same activity ignored', core.recordActivity([last: 4000L, gaps: [3000L]], 4000L, 48), [last: 4000L, gaps: [3000L]])
check('null activity ignored', core.recordActivity([last: 4000L, gaps: [3000L]], null, 48), [last: 4000L, gaps: [3000L]])
check('keeps the newest gaps', core.recordActivity([last: 10L, gaps: [1L, 2L, 3L]], 14L, 3), [last: 14L, gaps: [2L, 3L, 4L]])
check('gaps read back from state as Integers', core.recordActivity([last: 10, gaps: [1, 2]], 15L, 48), [last: 15L, gaps: [1L, 2L, 5L]])

// ── quietLimitMs ──
check('setting applies with too few gaps', core.quietLimitMs([10 * MIN, 10 * MIN], 60 * MIN, 30 * MIN, 5), 60 * MIN)
check('setting applies with no gaps', core.quietLimitMs(null, 60 * MIN, 30 * MIN, 5), 60 * MIN)
check('floor applies while learning', core.quietLimitMs([], 10 * MIN, 30 * MIN, 5), 30 * MIN)
check('twice the second-longest gap', core.quietLimitMs([20 * MIN, 25 * MIN, 22 * MIN, 21 * MIN, 24 * MIN], 30 * MIN, 30 * MIN, 5), 48 * MIN)
check('one outage does not stretch it', core.quietLimitMs([20 * MIN, 600 * MIN, 22 * MIN, 21 * MIN, 24 * MIN], 30 * MIN, 30 * MIN, 5), 48 * MIN)
check('setting is the minimum', core.quietLimitMs([20 * MIN, 25 * MIN, 22 * MIN, 21 * MIN, 24 * MIN], 60 * MIN, 30 * MIN, 5), 60 * MIN)
check('door bursts keep the setting', core.quietLimitMs([619 * MIN, 12000L, 191666L, 246214L, 495787L, 16640L], 360 * MIN, 30 * MIN, 5), 360 * MIN)
check('floor under a low setting', core.quietLimitMs([1 * MIN, 1 * MIN, 1 * MIN, 1 * MIN, 1 * MIN], 10 * MIN, 30 * MIN, 5), 30 * MIN)
check('learned limit can exceed the setting', core.quietLimitMs([180 * MIN, 180 * MIN, 180 * MIN, 180 * MIN, 180 * MIN], 60 * MIN, 30 * MIN, 5), 360 * MIN)

// ── isQuiet ──
check('never seen is quiet', core.isQuiet(null, 100 * MIN, 60 * MIN), true)
check('within the limit', core.isQuiet([last: 50 * MIN], 100 * MIN, 60 * MIN), false)
check('at the limit is not quiet', core.isQuiet([last: 40 * MIN], 100 * MIN, 60 * MIN), false)
check('past the limit', core.isQuiet([last: 39 * MIN], 100 * MIN, 60 * MIN), true)

println "\n${passed} passed, ${failed} failed"
System.exit(failed ? 1 : 0)

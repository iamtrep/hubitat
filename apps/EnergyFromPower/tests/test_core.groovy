// Copyright (c) 2026 PJ
// SPDX-License-Identifier: MIT
//
// Core unit tests for Energy From Power. Parses the block between the "Core (pure)" and
// "End core" markers of the shipped app file and runs it, so the tests bind to shipped code.
// Run under the pinned Groovy 2.4.21 jar:
//   java -cp groovy-all-2.4.21.jar groovy.ui.GroovyMain <this file>

File here = new File(getClass().protectionDomain.codeSource.location.toURI()).parentFile
String src = new File(here.parentFile, 'EnergyFromPower.groovy').text
int a = src.indexOf('// ── Core (pure)'), b = src.indexOf('// ── End core')
if (a < 0 || b < a) { println '[FAIL] core markers not found'; System.exit(2) }
core = new GroovyShell().parse('import groovy.transform.CompileStatic; import java.math.RoundingMode; ' + src.substring(a, b))   // same line keeps line numbers

passed = 0; failed = 0   // binding variables: script methods can't see typed locals
void check(String name, Object got, Object want) {
    if (got == want || (got instanceof Number && want instanceof Number && (got as BigDecimal) == (want as BigDecimal))) {
        passed++; println "[PASS] ${name}"
    } else { failed++; println "[FAIL] ${name}: expected ${want}, got ${got}" }
}

S = 1000L
H = 3600000L

// Feeds [seconds, watts] readings through takeReading as the app does and returns the Wh total
// at endS.
BigDecimal replayWh(List<List> readings, long endS, long maxHoldMs = 0L) {
    Map acc = [wh: 0.0G, p: 0.0G, t: 0L]
    readings.each { List r -> acc = core.takeReading(acc, r[1] as BigDecimal, (r[0] as long) * S, maxHoldMs) }
    return core.integrate(acc, endS * S, maxHoldMs).wh as BigDecimal
}

// ── helpers ──
check('numOrNull string', core.numOrNull('4500'), 4500)
check('numOrNull junk', core.numOrNull('x'), null)
check('toWatts W', core.toWatts(4500.0G, 'W'), 4500)
check('toWatts kW', core.toWatts(4.5G, 'kW'), 4500)
check('toWatts no unit', core.toWatts(12.0G, null), 12)
check('whToKwh rounds half up', core.whToKwh(1234.5G, 3), 1.235G)
check('whToKwh keeps the scale', core.fmtKwh(core.whToKwh(2000.0G, 3)), '2.000')

// ── integration ──
check('4500 W for an hour', core.integrate([wh: 0.0G, p: 4500.0G, t: 0L], H, 0L).wh, 4500)
check('nothing at 0 W', core.integrate([wh: 7.0G, p: 0.0G, t: 0L], H, 0L).wh, 7)
check('hold limit caps a long gap', core.integrate([wh: 0.0G, p: 600.0G, t: 0L], 3L * H, 30L * 60000L).wh, 300)
check('time before the hold adds nothing', core.integrate([wh: 5.0G, p: 600.0G, t: H], 0L, 0L).wh, 5)
check('out-of-order keeps the held time', core.integrate([wh: 5.0G, p: 600.0G, t: H], 0L, 0L).t, H)

// Bringing the total up to date (an hourly publish, a step timer) must not restart the hold.
Map held = core.takeReading([wh: 0.0G, p: 0.0G, t: 0L], 600.0G, 0L, 30L * 60000L)
held = core.integrate(held, H, 30L * 60000L)
check('hold counts from the reading, not the last update', core.integrate(held, 3L * H, 30L * 60000L).wh, 300)
check('no step past the hold limit', core.nextStepAt([wh: 0.0G, p: 600.0G, t: 0L, r: 0L], 0.0G, 0.1G, 5L * 60000L), null)
check('a step inside the hold limit', core.nextStepAt([wh: 0.0G, p: 600.0G, t: 0L, r: 0L], 0.0G, 0.1G, 30L * 60000L), 600000L)

// A water heater element: off, on 4500 W from 10 min to 40 min, off again. 30 min at 4.5 kW.
check('one heating cycle', replayWh([[0, 0], [600, 4500], [2400, 0]], 3600L), 2250)
// Hybrid heat pump: compressor 450 W for 2 h, then the element 4500 W for 20 min, then standby 5 W.
check('heat pump, element, standby', replayWh([[0, 450], [7200, 4500], [8400, 5]], 12000L), 900 + 1500 + 5)
// Repeats of the same reading change nothing.
check('repeats change nothing', replayWh([[0, 4500], [600, 4500], [1200, 4500]], 1800L), 2250)
check('negative reading counts as 0', replayWh([[0, -3], [3600, 0]], 7200L), 0)
check('a late reading is not held back in time', replayWh([[600, 4500], [0, 0]], 1200L), 750)

// A reading handled after the total was brought up to date past its time (it queued behind
// another execution): the overlap moves from the old power to the new one.
Map late = core.integrate([wh: 0.0G, p: 4500.0G, t: 0L, r: 0L], 10L * S, 0L)    // counted to 10 s: 12.5 Wh
late = core.takeReading(late, 0.0G, 4L * S, 0L)                                   // 0 W from 4 s, handled at 10 s
check('late reading corrects the overlap', late.wh, 5)
check('late reading is held', late.p, 0)
check('late reading keeps the counted time', late.t, 10L * S)
check('then nothing more at 0 W', core.integrate(late, H, 0L).wh, 5)
Map late2 = core.takeReading(core.integrate([wh: 0.0G, p: 0.0G, t: 0L, r: 0L], 10L * S, 0L), 3600.0G, 4L * S, 0L)
check('late start of a load counts from its reading', core.integrate(late2, 14L * S, 0L).wh, 10)

// The record carries more than the core uses (what was published, the step timer): kept as is.
Map extra = [wh: 0.0G, p: 600.0G, t: 0L, r: 0L, pub: 1.5G, wakeAt: 99L]
check('integrate keeps other keys', core.integrate(extra, H, 0L).subMap(['pub', 'wakeAt']), [pub: 1.5G, wakeAt: 99L])
check('takeReading keeps other keys', core.takeReading(extra, 0.0G, H, 0L).subMap(['pub', 'wakeAt']), [pub: 1.5G, wakeAt: 99L])
check('a late reading keeps other keys', core.takeReading(core.integrate(extra, H, 0L), 0.0G, 10L, 0L).pub, 1.5G)
check('integrate copies', core.integrate(extra, H, 0L).is(extra), false)

// ── publishing ──
check('next step at 4500 W, 0.1 kWh', core.nextStepAt([wh: 0.0G, p: 4500.0G, t: 0L], 0.0G, 0.1G, 0L), 80000L)
check('next step counts what is already in', core.nextStepAt([wh: 50.0G, p: 4500.0G, t: 0L], 0.0G, 0.1G, 0L), 40000L)
check('no next step at 0 W', core.nextStepAt([wh: 0.0G, p: 0.0G, t: 0L], 0.0G, 0.1G, 0L), null)
check('step already passed wakes now', core.nextStepAt([wh: 150.0G, p: 10.0G, t: 5L], 0.0G, 0.1G, 0L), 5L)

// ── foreign totals on the energy meter ──
check('own echo', core.isForeignTotal(12.345G, 12.345G, 0.1G), false)
check('coarser rounding of the echo', core.isForeignTotal(12.35G, 12.345G, 0.001G), false)
check('reset to 0', core.isForeignTotal(0.0G, 12.345G, 0.1G), true)
check('set to a meter reading', core.isForeignTotal(4021.0G, 12.345G, 0.1G), true)
check('nothing published yet', core.isForeignTotal(3.0G, null, 0.1G), true)

println "\n${passed} passed, ${failed} failed"
System.exit(failed ? 1 : 0)

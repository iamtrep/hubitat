// Copyright (c) 2026 PJ
// SPDX-License-Identifier: MIT
//
// Core unit tests for Indoor Air Quality Controller. Parses the block between the
// "Core (pure)" and "End core" markers of the shipped app file and runs it, so the
// tests bind to shipped code. Run under the pinned Groovy 2.4.21 jar:
//   java -cp groovy-all-2.4.21.jar groovy.ui.GroovyMain <this file>

File here = new File(getClass().protectionDomain.codeSource.location.toURI()).parentFile
String src = new File(here.parentFile, 'IndoorAirQualityController.groovy').text
int a = src.indexOf('// ── Core (pure)'), b = src.indexOf('// ── End core')
if (a < 0 || b < a) { println '[FAIL] core markers not found'; System.exit(2) }
core = new GroovyShell().parse(src.substring(a, b))

passed = 0; failed = 0   // binding variables: script methods can't see typed locals
void check(String name, Object got, Object want) {
    if (got == want || (got instanceof Number && want instanceof Number && (got as BigDecimal) == (want as BigDecimal))) {
        passed++; println "[PASS] ${name}"
    } else { failed++; println "[FAIL] ${name}: expected ${want}, got ${got}" }
}

M = 60000L   // one minute
Map cfg(Map over = [:]) {
    Map c = [stages: [[on: 625, off: 575, dwell: 5, switches: ['1']],
                      [on: 1150, off: 1050, dwell: 6, switches: ['2']],
                      [on: 1250, off: 1150, dwell: 6, switches: ['3']]],
             offset: 200, holdMin: 60,
             adv: [ppm: 1400, minutes: 20, repeatHours: 3, allClear: false],
             rh: [pct: 30, hours: 12], unitMs: 60000L]
    c.putAll(over)
    return c
}
Map cfgStages(Closure edit) { Map c = cfg(); edit(c.stages); return c }

// ── helpers ──
check('numOrNull string', core.numOrNull('650'), 650)
check('numOrNull blank', core.numOrNull(''), null)
check('numOrNull junk', core.numOrNull('abc'), null)
check('fmtInt rounds', core.fmtInt(1523.5G), '1524')
check('fmtInt null', core.fmtInt(null), '')

List<Map> rd = [[v: 600, t: 0L], [v: '700', t: 0L], [v: 650, t: 0L]]
check('highest', core.combineReadings(rd, 'highest', 1000L, 7200000L), 700)
check('average rounded', core.combineReadings([[v: 600, t: 0L], [v: 651, t: 0L]], 'average', 1000L, 7200000L), 626)
check('lowest', core.combineReadings(rd, 'lowest', 1000L, 7200000L), 600)
check('stale reading dropped', core.combineReadings([[v: 900, t: 0L], [v: 500, t: 7000000L]], 'highest', 7300000L, 7200000L), 500)
check('all stale gives null', core.combineReadings([[v: 900, t: 0L]], 'highest', 7300000L, 7200000L), null)
check('junk value ignored', core.combineReadings([[v: 'x', t: 0L], [v: 500, t: 0L]], 'highest', 0L, 7200000L), 500)
check('no readings gives null', core.combineReadings([], 'highest', 0L, 7200000L), null)

check('any on', core.anySwitchCondition(['off', 'on'], []), true)
check('any off', core.anySwitchCondition([], ['on', 'off']), true)
check('none', core.anySwitchCondition(['off'], ['on']), false)
check('null lists', core.anySwitchCondition(null, null), false)

check('Away inactive by default', core.modeActive('Away', []), false)
check('Home active by default', core.modeActive('Home', []), true)
check('chosen excludes', core.modeActive('Night', ['Home']), false)
check('chosen includes', core.modeActive('Home', ['Home']), true)

check('safety first', core.stopReason(true, false, false, true), 'safety')
check('disabled before mode', core.stopReason(false, false, false, true), 'disabled')
check('mode before pause', core.stopReason(false, true, false, true), 'mode')
check('pause', core.stopReason(false, true, true, true), 'pause')
check('running', core.stopReason(false, true, true, false), null)

check('earliest one null', core.earliest(null, 5L), 5)
check('earliest both', core.earliest(3L, 5L), 3)
check('earliest none', core.earliest(null, null), null)

check('resized pads', core.resized([[s: 'engaged']], 3).collect { it.s }, ['engaged', 'off', 'off'])
check('resized cuts', core.resized([[s: 'engaged'], [s: 'off'], [s: 'held']], 2).size(), 2)
check('resized null', core.resized(null, 1).collect { it.s }, ['off'])

// ── validation ──
check('defaults valid', core.validateCfg(cfg()), [])
check('off not below on', core.validateCfg(cfgStages { it[0].off = 625 }), ['Stage 1: the off threshold must be below the on threshold'])
check('on not increasing', core.validateCfg(cfgStages { it[1].on = 600; it[1].off = 500 }), ["Stage 2: the on threshold must be above stage 1's"])
check('missing on', core.validateCfg(cfgStages { it[0].on = '' }), ['Stage 1: enter the on threshold'])
check('dwell zero', core.validateCfg(cfgStages { it[0].dwell = 0 }), ['Stage 1: enter a time of at least 1 minute'])
check('no switch', core.validateCfg(cfgStages { it[0].switches = [] }), ['Stage 1: select at least one switch'])
check('switch in two stages', core.validateCfg(cfgStages { it[1].switches = ['1'] }), ['A switch is selected in stages 1 and 2'])
check('five stages', core.validateCfg(cfgStages { it << [:] << [:] }), ['Choose 1 to 4 stages'])
check('offset blank', core.validateCfg(cfg(offset: '')), ['Threshold offset: enter a number'])

// ── stage ladder ──
Map step(List cur, Object co2, long now, boolean offsetOn = false, Map c = cfg()) {
    core.stepStages(cur, co2 == null ? null : new BigDecimal(co2.toString()), now, c, offsetOn)
}
List states(Map r) { r.stages.collect { it.s } }

Map r1 = step([], 700, 0L)
check('first reading starts the dwell', r1.stages[0].since, 0)
check('first reading no action', r1.actions, [])
check('wake at end of dwell', r1.wakeAt, 5 * M)
check('engages after dwell', step([[s: 'off', since: 0L]], 700, 5 * M).actions, [[stage: 0, cmd: 'on']])
check('not before dwell', states(step([[s: 'off', since: 0L]], 700, 4 * M)), ['off', 'off', 'off'])
check('dwell restarts when value crosses back', step([[s: 'off', since: 0L]], 600, M).stages[0].since, null)
check('on threshold is strict', states(step([[s: 'off', since: 0L]], 625, 10 * M)), ['off', 'off', 'off'])
check('stage 1 then stage 2', states(step([[s: 'off', since: 0L], [s: 'off', since: 0L]], 1200, 6 * M)), ['engaged', 'engaged', 'off'])
Map blocked = step([[s: 'off', since: 2 * M], [s: 'off', since: 0L]], 1200, 390000L)   // 6.5 min
check('stage 2 waits for stage 1', states(blocked), ['off', 'off', 'off'])
check('blocked stage does not wake in the past', blocked.wakeAt, 7 * M)
check('held stage counts for the stage above', states(step([[s: 'held', heldUntil: 999 * M], [s: 'off', since: 0L]], 1200, 6 * M)), ['held', 'engaged', 'off'])
Map down = step([[s: 'engaged', since: 0L], [s: 'engaged', since: 0L]], 400, 6 * M)
check('release top down', down.actions, [[stage: 1, cmd: 'off'], [stage: 0, cmd: 'off']])
check('lower stage waits for upper', states(step([[s: 'engaged', since: 0L], [s: 'engaged', since: M]], 400, 5 * M)), ['engaged', 'engaged', 'off'])
Map band = step([[s: 'engaged']], 600, 10 * M)
check('hysteresis band keeps stage', states(band), ['engaged', 'off', 'off'])
check('hysteresis band no timer', band.stages[0].since, null)
check('offset raises thresholds', states(step([[s: 'off', since: 0L]], 700, 5 * M, true)), ['off', 'off', 'off'])
check('offset removed engages', states(step([[s: 'off', since: 0L]], 700, 5 * M, false)), ['engaged', 'off', 'off'])
check('offset still engages above', states(step([[s: 'off', since: 0L]], 900, 5 * M, true)), ['engaged', 'off', 'off'])
Map oa = step([[s: 'engaged']], 700, 0L, true)
check('offset applied while engaged starts release', oa.stages[0].since, 0)
check('offset applied while engaged releases after dwell', states(step(oa.stages, 700, 5 * M, true)), ['off', 'off', 'off'])
Map none = step([[s: 'engaged', since: 0L]], null, 999 * M)
check('no reading holds stages', states(none), ['engaged', 'off', 'off'])
check('no reading no action', none.actions, [])
check('hold ends, CO2 high', step([[s: 'held', heldUntil: 1000L]], 700, 1000L).actions, [[stage: 0, cmd: 'on']])
check('hold ends, CO2 low', step([[s: 'held', heldUntil: 1000L]], 500, 1000L).actions, [[stage: 0, cmd: 'off']])
check('hold ends with stage above running', states(step([[s: 'held', heldUntil: 1000L], [s: 'engaged']], 600, 1000L)), ['engaged', 'engaged', 'off'])
check('hold not over', states(step([[s: 'held', heldUntil: 5000L]], 400, 1000L)), ['held', 'off', 'off'])
check('hold wakes at its end', step([[s: 'held', heldUntil: 5000L]], 400, 1000L).wakeAt, 5000)
Map rs = step([[s: 'engaged', since: null]], 500, 0L)
check('restart keeps engaged stage', states(rs), ['engaged', 'off', 'off'])
check('restart starts the release timer', rs.stages[0].since, 0)
check('test unit', core.dwellMs([dwell: 5], 1000L), 5000)
check('stageLevel', core.stageLevel([[s: 'engaged'], [s: 'held'], [s: 'off']]), 2)
check('stageLevel none', core.stageLevel([[s: 'off']]), 0)
check('allRunning', core.allRunning([[s: 'engaged'], [s: 'held']]), true)
check('allRunning not', core.allRunning([[s: 'engaged'], [s: 'off']]), false)

// ══ later tasks append cases above this line ══
println "${passed} passed, ${failed} failed"
System.exit(failed ? 1 : 0)

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

// ══ later tasks append cases above this line ══
println "${passed} passed, ${failed} failed"
System.exit(failed ? 1 : 0)

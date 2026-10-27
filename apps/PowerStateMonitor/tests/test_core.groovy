// Copyright (c) 2026 PJ
// SPDX-License-Identifier: MIT
//
// Core unit tests for Power State Monitor. Parses the block between the "Core (pure)" and
// "End core" markers of the shipped app file and runs it, so the tests bind to shipped code.
// Run under the pinned Groovy 2.4.21 jar:
//   java -cp groovy-all-2.4.21.jar groovy.ui.GroovyMain <this file>

File here = new File(getClass().protectionDomain.codeSource.location.toURI()).parentFile
String src = new File(here.parentFile, 'PowerStateMonitor.groovy').text
int a = src.indexOf('// ── Core (pure)'), b = src.indexOf('// ── End core')
if (a < 0 || b < a) { println '[FAIL] core markers not found'; System.exit(2) }
core = new GroovyShell().parse('import groovy.transform.CompileStatic; ' + src.substring(a, b))   // the core's @CompileStatic needs the file's import; same line keeps line numbers

passed = 0; failed = 0   // binding variables: script methods can't see typed locals
void check(String name, Object got, Object want) {
    if (got == want || (got instanceof Number && want instanceof Number && (got as BigDecimal) == (want as BigDecimal))) {
        passed++; println "[PASS] ${name}"
    } else { failed++; println "[FAIL] ${name}: expected ${want}, got ${got}" }
}

S = 1000L
// An HRV on a plug: off, normal about 47 W, turbo about 75 W, start-up surges to 135 W.
Map cfg(Map over = [:]) {
    Map c = [states: [[name: 'off', from: 0G], [name: 'normal', from: 20G], [name: 'turbo', from: 60G]],
             ceiling: 90G, hyst: 5G, settleMs: 20000L, unknownMs: 120000L, sourceState: 'turbo']
    c.putAll(over)
    return c
}

// Feeds [seconds, watts] reports through takeReading and stepState as the app does, firing each
// wake time that falls before the next report. Returns [[seconds, state]] for every change.
List<List> replay(List<List> reports, Map c, String start = null, long endS = -1L) {
    String cur = start
    Map last = null
    Long wake = null
    List<List> out = []
    Closure step = { long nowMs ->
        Map r = core.stepState(cur, last, c, nowMs)
        if (r.cur != cur) { out << [(nowMs / 1000L) as long, r.cur]; cur = r.cur as String }
        wake = r.wakeAt as Long
    }
    reports.each { List rp ->
        long t = (rp[0] as long) * S
        while (wake != null && wake <= t) step(wake)
        last = core.takeReading(last, rp[1] as BigDecimal, t)
        step(t)
    }
    long end = endS >= 0 ? endS * S : ((reports[-1][0] as long) + 3600L) * S
    while (wake != null && wake <= end) step(wake)
    return out
}

// ── helpers ──
check('numOrNull string', core.numOrNull('47.7'), 47.7)
check('numOrNull junk', core.numOrNull('x'), null)
check('fmtW trims zeros', core.fmtW(76.30G), '76.3')

// ── validation ──
check('valid config', core.validateCfg(cfg()), [])
check('edges must rise', core.validateCfg(cfg(states: [[name: 'off', from: 0G], [name: 'on', from: 20G], [name: 'hi', from: 20G]], sourceState: null)),
      ['State 3 must start above state 2'])
check('names differ', core.validateCfg(cfg(states: [[name: 'on', from: 0G], [name: 'on', from: 20G]], sourceState: null)),
      ['State names must differ'])
check('unknown reserved', core.validateCfg(cfg(states: [[name: 'off', from: 0G], [name: 'unknown', from: 20G]], sourceState: null)),
      ['"unknown" is reserved for a surge that holds'])
check('ceiling above top', core.validateCfg(cfg(ceiling: 60G)), ['The surge ceiling must be above the top state'])
check('source state exists', core.validateCfg(cfg(sourceState: 'boost')), ['The state the switch commands must be one of the states'])
check('blank name', core.validateCfg(cfg(states: [[name: '', from: 0G], [name: 'on', from: 20G]], sourceState: null)),
      ['Every state needs a name'])

// ── classify ──
List st = cfg().states
check('off', core.classify(0G, st, 90G, 5G, null), 'off')
check('normal', core.classify(47.7G, st, 90G, 5G, null), 'normal')
check('turbo', core.classify(76.3G, st, 90G, 5G, null), 'turbo')
check('above ceiling is a surge', core.classify(134.6G, st, 90G, 5G, 'normal'), null)
check('no ceiling: top state', core.classify(134.6G, st, null, 5G, 'normal'), 'turbo')
check('edge belongs to the upper state', core.classify(60G, st, 90G, 0G, null), 'turbo')
check('hysteresis keeps normal above its edge', core.classify(63G, st, 90G, 5G, 'normal'), 'normal')
check('past the hysteresis leaves normal', core.classify(65G, st, 90G, 5G, 'normal'), 'turbo')
check('hysteresis keeps turbo below its edge', core.classify(56G, st, 90G, 5G, 'turbo'), 'turbo')
check('past the hysteresis leaves turbo', core.classify(54.9G, st, 90G, 5G, 'turbo'), 'normal')
check('unknown current state uses the bands', core.classify(47G, st, 90G, 5G, 'unknown'), 'normal')

// ── takeReading ──
Map l0 = [v: 47.7G, t: 1000L]
check('repeat keeps the hold', core.takeReading(l0, 47.7G, 9000L), l0)
check('new value restarts', core.takeReading(l0, 50G, 9000L), [v: 50G, t: 9000L])
check('first reading', core.takeReading(null, 0G, 5L), [v: 0G, t: 5L])

// ── stepState ──
check('no reading yet', core.stepState(null, null, cfg(), 0L), [cur: null, wakeAt: null])
check('not settled: wake at settle', core.stepState('off', [v: 47G, t: 0L], cfg(), 5000L), [cur: 'off', wakeAt: 20000L])
check('settled', core.stepState('off', [v: 47G, t: 0L], cfg(), 20000L), [cur: 'normal', wakeAt: null])
check('surge waits for unknown', core.stepState('normal', [v: 130G, t: 0L], cfg(), 20000L), [cur: 'normal', wakeAt: 120000L])
check('surge that holds is unknown', core.stepState('normal', [v: 130G, t: 0L], cfg(), 120000L), [cur: 'unknown', wakeAt: null])

// ── replays of the HRV plug's reports (seconds from the first report, watts) ──
// Start from off: 06:22:25 59.8, :26 134.4, :28 115.5, :29 47.7
check('start-up surge settles to normal once',
      replay([[0, 59.8], [1, 134.4], [3, 115.5], [4, 47.7]], cfg(), 'off'), [[24L, 'normal']])
// Normal to turbo: 18:21:33 104.6, :35 89.6, :36 76.3
check('ramp to turbo shows turbo only',
      replay([[0, 104.6], [2, 89.6], [3, 76.3]], cfg(), 'normal'), [[23L, 'turbo']])
// Turbo to normal: 14:42:25 28.7, :30 39.7
check('ramp down shows normal only', replay([[0, 28.7], [5, 39.7]], cfg(), 'turbo'), [[25L, 'normal']])
// Normal to off: 09:02:05 6.5, :07 0.0
check('stop shows off only', replay([[0, 6.5], [2, 0.0]], cfg(), 'normal'), [[22L, 'off']])
// Silence holds the state: one report, then nothing for 5 h.
check('silence keeps turbo', replay([[0, 76.3]], cfg(), 'turbo', 18000L), [])
// A surge that never ends.
check('held surge goes unknown, then back', replay([[0, 130.0], [600, 47.0]], cfg(), 'normal'),
      [[120L, 'unknown'], [620L, 'normal']])
// Without a settle time every report is a state: the filter is what removes the surge.
check('settle 0 passes the surge through',
      replay([[0, 59.8], [1, 134.4], [3, 115.5], [4, 47.7]], cfg(settleMs: 0L, ceiling: null), 'off'),
      [[0L, 'normal'], [1L, 'turbo'], [4L, 'normal']])

// ── source ──
check('turbo with switch on', core.sourceOf('turbo', 'turbo', 'on'), 'switch')
check('turbo with switch off', core.sourceOf('turbo', 'turbo', 'off'), 'other')
check('other state', core.sourceOf('normal', 'turbo', 'on'), 'none')
check('no source switch', core.sourceOf('turbo', null, null), 'none')

println "\n${passed} passed, ${failed} failed"
System.exit(failed ? 1 : 0)

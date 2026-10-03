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

// ── transitions ──
long T(String iso, String hhmm) { core.atLocal(iso, hhmm, TZ) }
normal = [name: 'Normal', type: 'time', groups: [
  [name: 'Weekdays', days: [1, 2, 3, 4, 5], periods: [
    [name: 'Wake', start: [kind: 'time', at: '06:30'], profile: 'Home'],
    [name: 'Leave', start: [kind: 'time', at: '08:15'], profile: 'Out'],
    [name: 'Night', start: [kind: 'time', at: '22:00'], profile: 'Sleep']]],
  [name: 'Weekend', days: [6, 7], periods: [
    [name: 'Wake', start: [kind: 'sunrise', offset: -30], profile: 'Home'],
    [name: 'Night', start: [kind: 'var', name: 'nightTime'], profile: 'Sleep']]]]]
Map sunFor(String iso) { [(iso): [rise: T(iso, '07:02'), set: T(iso, '18:30')]] }
Map ctxAt(String iso, String hhmm, Map extra = [:]) {
  Map sun = [:]; [-1, 0, 1].each { sun += sunFor(core.addDays(iso, it, TZ)) }
  [now: T(iso, hhmm), tz: TZ, modeId: 1L, sun: sun, vars: [nightTime: T('2026-01-01', '23:00')]] + extra
}
check('weekday current', core.currentTransition(normal, ctxAt('2026-10-05', '09:00')).period, 'Leave')
check('weekday next', core.nextTransitionOf(normal, ctxAt('2026-10-05', '09:00')).period, 'Night')
check('exact start is current', core.currentTransition(normal, ctxAt('2026-10-05', '06:30')).period, 'Wake')
check('before first period uses previous day', core.currentTransition(normal, ctxAt('2026-10-06', '05:00')).key, '2026-10-05|Weekdays|Night')
check('sunrise offset', core.transitionsForDay(normal, '2026-10-03', ctxAt('2026-10-03', '12:00'))[0].at, T('2026-10-03', '06:32'))
check('var time of day used', core.transitionsForDay(normal, '2026-10-03', ctxAt('2026-10-03', '12:00'))[1].at, T('2026-10-03', '23:00'))
check('missing var skips period', core.transitionsForDay(normal, '2026-10-03', ctxAt('2026-10-03', '12:00', [vars: [:]])).size(), 1)
check('Friday night to Saturday wake', core.nextTransitionOf(normal, ctxAt('2026-10-09', '23:00')).key, '2026-10-10|Weekend|Wake')
dst = [name: 'D', type: 'time', groups: [[name: 'All', days: [1,2,3,4,5,6,7], periods: [
  [name: 'Early', start: [kind: 'time', at: '02:30'], profile: 'Home']]]]]
check('DST gap period still fires once', core.transitionsForDay(dst, '2026-03-08', ctxAt('2026-03-08', '12:00')).size(), 1)
check('DST repeat period fires once', core.transitionsForDay(dst, '2026-11-01', ctxAt('2026-11-01', '12:00')).size(), 1)
check('no group for day gives none', core.transitionsForDay([name: 'X', type: 'time', groups: []], '2026-10-05', ctxAt('2026-10-05', '12:00')), [])

// ── resolver ──
cfgBase = [profiles: [
    [name: 'Home', heat: 21.0, cool: 24.0, fan: 'auto'], [name: 'Out', heat: 19.0, cool: 27.0],
    [name: 'Sleep', heat: 18.0, cool: 26.0], [name: 'Away', heat: 16.0, cool: 29.0],
    [name: 'Var', heatVar: 'comfort']],
  schedules: [normal, [name: 'Cottage', type: 'mode', rows: [[modes: [1, 2], profile: 'Home'], [modes: [3], profile: 'Sleep']]]],
  active: 'Normal', overrides: [[modeId: 4, profile: 'Away']],
  eco: [offset: 2.0, onOverrides: true], options: [separation: 2.0]]
rt0 = [paused: false, eco: false, hold: null]
at9 = ctxAt('2026-10-05', '09:00')
Map r(Map cfg = cfgBase, Map rt = rt0, Map ctx = at9) { core.resolveTarget(cfg, rt, ctx) }

check('schedule layer', r().layer, 'schedule')
check('schedule profile', r().profile, 'Out')
check('paused wins', r(cfgBase, rt0 + [paused: true]).layer, 'paused')
check('restricted pauses', r(cfgBase, rt0, at9 + [restricted: true]).layer, 'paused')
check('hold beats override', r(cfgBase, rt0 + [hold: [kind: 'profile', profile: 'Sleep', end: 'indefinite']], at9 + [modeId: 4L]).profile, 'Sleep')
check('override beats schedule', r(cfgBase, rt0, at9 + [modeId: 4L]).profile, 'Away')
check('override matched by id', r(cfgBase, rt0, at9 + [modeId: 4L]).overrideModeId, 4L)
check('eco lowers heat', r(cfgBase, rt0 + [eco: true]).heat, 17.0)
check('eco raises cool', r(cfgBase, rt0 + [eco: true]).cool, 29.0)
check('eco on override by default', r(cfgBase, rt0 + [eco: true], at9 + [modeId: 4L]).heat, 14.0)
check('eco off override when disabled', r(cfgBase + [eco: [offset: 2.0, onOverrides: false]], rt0 + [eco: true], at9 + [modeId: 4L]).heat, 16.0)
check('eco offset change does not stack', r(cfgBase + [eco: [offset: 3.0, onOverrides: true]], rt0 + [eco: true]).heat, 16.0)
check('eco off during override returns override value', r(cfgBase, rt0, at9 + [modeId: 4L]).heat, 16.0)
check('mode schedule row', r(cfgBase + [active: 'Cottage'], rt0, at9 + [modeId: 3L]).profile, 'Sleep')
check('mode schedule unlisted mode', r(cfgBase + [active: 'Cottage'], rt0, at9 + [modeId: 5L]).layer, 'none')
check('mode schedule key is the mode', r(cfgBase + [active: 'Cottage'], rt0, at9 + [modeId: 3L]).transitionKey, 'mode|3')
check('variable setpoint', core.profileValues(cfgBase, 'Var', at9 + [vars: [comfort: 22.5]]).heat, 22.5)
check('missing profile resolves to none', r(cfgBase + [overrides: [[modeId: 4, profile: 'Gone']]], rt0, at9 + [modeId: 4L]).layer, 'none')
check('setpoint hold', r(cfgBase, rt0 + [hold: [kind: 'setpoints', heat: 23.0, end: 'next']]).heat, 23.0)
// hold expiry
tgt = r()
check('next hold lives within period', core.holdExpired([end: 'next', transitionKey: tgt.transitionKey, overrideModeId: null], tgt, at9.now as long), false)
check('next hold ends at transition', core.holdExpired([end: 'next', transitionKey: 'old', overrideModeId: null], tgt, at9.now as long), true)
check('next hold ends when override starts', core.holdExpired([end: 'next', transitionKey: tgt.transitionKey, overrideModeId: null], r(cfgBase, rt0, at9 + [modeId: 4L]), at9.now as long), true)
check('timed hold before end', core.holdExpired([end: 'at', until: (at9.now as long) + 1000], tgt, at9.now as long), false)
check('timed hold after end', core.holdExpired([end: 'at', until: (at9.now as long) - 1], tgt, at9.now as long), true)
check('indefinite hold', core.holdExpired([end: 'indefinite'], tgt, at9.now as long), false)

// ══ later tasks append cases above this line ══
println "${passed} passed, ${failed} failed"
System.exit(failed ? 1 : 0)

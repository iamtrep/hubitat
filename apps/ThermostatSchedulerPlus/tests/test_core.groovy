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
check('DST gap period lands at 03:30', core.transitionsForDay(dst, '2026-03-08', ctxAt('2026-03-08', '12:00'))[0].at, T('2026-03-08', '03:30'))
check('DST gap: before it, previous day is current', core.currentTransition(dst, ctxAt('2026-03-08', '03:10')).key, '2026-03-07|All|Early')
dst2 = [name: 'D2', type: 'time', groups: [[name: 'All', days: [1,2,3,4,5,6,7], periods: [
  [name: 'Rep', start: [kind: 'time', at: '01:30'], profile: 'Home']]]]]
check('DST repeat period fires once', core.transitionsForDay(dst2, '2026-11-01', ctxAt('2026-11-01', '12:00')).size(), 1)
repAt = core.transitionsForDay(dst2, '2026-11-01', ctxAt('2026-11-01', '12:00'))[0].at as long
check('DST repeat does not fire twice', core.nextTransitionOf(dst2, ctxAt('2026-11-01', '12:00') + [now: repAt + 3600000L]).key, '2026-11-02|All|Rep')
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
pausedTgt = r(cfgBase, rt0 + [paused: true])
check('next hold survives pause', core.holdExpired([end: 'next', transitionKey: tgt.transitionKey, overrideModeId: null], pausedTgt, at9.now as long), false)
restrictedTgt = r(cfgBase, rt0, at9 + [restricted: true])
check('next hold survives restriction', core.holdExpired([end: 'next', transitionKey: tgt.transitionKey, overrideModeId: null], restrictedTgt, at9.now as long), false)

// ── planWrites ──
Map th(String mode, Object heat = 20.0, Object cool = 25.0, String fan = 'auto') { [id: '1', mode: mode, heat: heat, cool: cool, fan: fan] }
List cmds(Map t, Map th, Map o = [separation: 2.0]) { core.planWrites(t, [th], o).collect { "${it.command}=${it.value}".toString() } }
tg = [layer: 'schedule', heat: 21.0, cool: 24.0]
check('heat mode writes heat only', cmds(tg, th('heat')), ['setHeatingSetpoint=21.0'])
check('cool mode writes cool only', cmds(tg, th('cool')), ['setCoolingSetpoint=24.0'])
check('off writes nothing', cmds(tg, th('off')), [])
check('equal value not written', cmds([layer: 'schedule', heat: 20.0], th('heat')), [])
check('force rewrites equal value', cmds([layer: 'schedule', heat: 20.0], th('heat'), [separation: 2.0, force: true]), ['setHeatingSetpoint=20.0'])
check('auto both, separation raises cool', cmds([layer: 'schedule', heat: 23.0, cool: 24.0], th('auto', 20.0, 24.0)), ['setHeatingSetpoint=23.0', 'setCoolingSetpoint=25.0'])
check('auto heat only, lowered under current cool', cmds([layer: 'schedule', heat: 24.0], th('auto', 20.0, 25.0)), ['setHeatingSetpoint=23.0'])
check('auto cool only, raised over current heat', cmds([layer: 'schedule', cool: 21.0], th('auto', 20.0, 25.0)), ['setCoolingSetpoint=22.0'])
check('mode change then setpoint for new mode', cmds([layer: 'schedule', heat: 21.0, cool: 24.0, mode: 'cool'], th('heat')), ['setThermostatMode=cool', 'setCoolingSetpoint=24.0'])
check('fan written when different', cmds([layer: 'schedule', fan: 'on'], th('heat')), ['setThermostatFanMode=on'])
check('paused writes nothing', cmds([layer: 'paused'], th('heat')), [])
check('blank values leave thermostat alone', cmds([layer: 'schedule'], th('heat')), [])
pair = [th('off') + [id: '1'], th('off') + [id: '2']]
nm = core.planWrites([layer: 'schedule', heat: 21.0, mode: 'heat'], pair, [separation: 2.0, noModeIds: ['1']])
check('noModeIds: listed off thermostat gets no writes', nm.findAll { it.id == '1' }, [])
check('noModeIds: unlisted thermostat gets mode then setpoint', nm.findAll { it.id == '2' }.collect { "${it.command}=${it.value}".toString() }, ['setThermostatMode=heat', 'setHeatingSetpoint=21.0'])

// ── restrictions and wake ──
check('no restrictions', core.restrictedNow(null, at9), false)
check('day restriction', core.restrictedNow([days: [6, 7]], at9), true)
check('mode restriction', core.restrictedNow([modeIds: [2]], at9), true)
check('inside time window', core.restrictedNow([from: [kind: 'time', at: '08:00'], to: [kind: 'time', at: '17:00']], at9), false)
check('outside time window', core.restrictedNow([from: [kind: 'time', at: '10:00'], to: [kind: 'time', at: '17:00']], at9), true)
check('overnight window', core.restrictedNow([from: [kind: 'time', at: '22:00'], to: [kind: 'time', at: '06:00']], ctxAt('2026-10-05', '23:00')), false)
check('wake at next transition', core.nextWake(cfgBase, rt0, at9), T('2026-10-05', '22:00'))
check('wake at hold end when sooner', core.nextWake(cfgBase, rt0 + [hold: [end: 'at', until: T('2026-10-05', '10:00')]], at9), T('2026-10-05', '10:00'))
check('wake at midnight at the latest', core.nextWake(cfgBase + [active: 'Cottage'], rt0, at9), T('2026-10-06', '00:00'))
check('nextWake never in the past', core.nextWake(cfgBase, rt0 + [hold: [end: 'at', until: (at9.now as long) - 60000]], at9) > (at9.now as long), true)

// ── validation and commands ──
env = [scale: 'C', vars: ['comfort', 'nightTime'], modeIds: [1L, 2L, 3L, 4L]]
check('default config is valid', core.validateConfig(core.defaultConfig(), env), [])
check('cfgBase is valid', core.validateConfig(cfgBase + [v: 1], env), [])
check('unknown profile', core.validateConfig(cfgBase + [overrides: [[modeId: 4, profile: 'Gone']]], env).size(), 1)
check('duplicate profile names', core.validateConfig(cfgBase + [profiles: cfgBase.profiles + [[name: 'Home', heat: 20.0]]], env).size(), 1)
check('day in two groups', core.validateConfig(cfgBase + [schedules: [[name: 'N', type: 'time', groups: [[name: 'A', days: [1,2,3,4,5,6,7], periods: []], [name: 'B', days: [1], periods: []]]]], active: 'N'], env).isEmpty(), false)
check('day in no group', core.validateConfig(cfgBase + [schedules: [[name: 'N', type: 'time', groups: [[name: 'A', days: [1], periods: []]]]], active: 'N'], env).isEmpty(), false)
check('same time twice in a group', core.validateConfig(cfgBase + [schedules: [[name: 'N', type: 'time', groups: [[name: 'A', days: [1,2,3,4,5,6,7], periods: [
    [name: 'X', start: [kind: 'time', at: '06:00'], profile: 'Home'], [name: 'Y', start: [kind: 'time', at: '06:00'], profile: 'Home']]]]]], active: 'N'], env).isEmpty(), false)
check('out of range setpoint', core.validateConfig(cfgBase + [profiles: cfgBase.profiles + [[name: 'Hot', heat: 60.0]]], env).size(), 1)
fenv = env + [scale: 'F']
fProfiles = [[name: 'Home', heat: 70.0, cool: 75.0], [name: 'Sleep', heat: 65.0, cool: 78.0]]
check('F heat 31 rejected', core.validateConfig(core.defaultConfig() + [profiles: fProfiles + [[name: 'F1', heat: 31.0]]], fenv).size(), 1)
check('F heat 32 accepted', core.validateConfig(core.defaultConfig() + [profiles: fProfiles + [[name: 'F2', heat: 32.0]]], fenv), [])
check('F heat 104 accepted', core.validateConfig(core.defaultConfig() + [profiles: fProfiles + [[name: 'F3', heat: 104.0]]], fenv), [])
check('unknown variable', core.validateConfig(cfgBase + [profiles: cfgBase.profiles + [[name: 'V', heatVar: 'nope']]], env).size(), 1)
check('unknown active schedule', core.validateConfig(cfgBase + [active: 'Nope'], env).isEmpty(), false)
check('end default next', core.parseEnd(null).end, 'next')
check('end minutes from string', core.parseEnd('30').minutes, 30)
check('end iso', core.parseEnd('2026-10-05T22:00:00-04:00').end, 'at')
check('huge minutes is an error not a throw', core.parseCommand([command: 'holdProfile', profile: 'Sleep', end: '99999999999']).ok, false)
check('end garbage', core.parseEnd('soon').error != null, true)
check('hold profile', core.parseCommand([command: 'holdProfile', profile: 'Sleep', end: '']).args.end, 'next')
check('hold profile needs profile', core.parseCommand([command: 'holdProfile']).ok, false)
check('hold setpoints from strings', core.parseCommand([command: 'holdSetpoints', heating: '18', end: 'indefinite']).args.heat, 18)
check('hold setpoints needs a value', core.parseCommand([command: 'holdSetpoints', heating: '', cooling: '']).ok, false)
check('setEco needs on or off', core.parseCommand([command: 'setEco', state: 'maybe']).ok, false)
check('setEcoOffset from string', core.parseCommand([command: 'setEcoOffset', offset: '2.5']).args.offset, 2.5)
check('setSchedule needs name', core.parseCommand([command: 'setSchedule', schedule: '']).ok, false)
check('unknown command', core.parseCommand([command: 'explode']).ok, false)
['on', 'off', 'resume', 'applyNow', 'advance', 'refresh'].each { check("bare ${it}", core.parseCommand([command: it]).ok, true) }

// ── write check outcome ──
wbatch = [[id: '1', command: 'setHeatingSetpoint', value: 18.0], [id: '1', command: 'setCoolingSetpoint', value: 26.0],
          [id: '2', command: 'setHeatingSetpoint', value: 18.0]]
check('every thermostat confirmed is ok', core.applyOutcome(wbatch, ['1', '2']), 'ok')
check('some thermostats confirmed is partial', core.applyOutcome(wbatch, ['2']), 'partial')
check('no thermostat confirmed is failed', core.applyOutcome(wbatch, []), 'failed')
check('confirmed ids outside the batch do not count', core.applyOutcome(wbatch, ['3']), 'failed')
check('empty batch is ok', core.applyOutcome([], []), 'ok')

// ══ later tasks append cases above this line ══
println "${passed} passed, ${failed} failed"
System.exit(failed ? 1 : 0)

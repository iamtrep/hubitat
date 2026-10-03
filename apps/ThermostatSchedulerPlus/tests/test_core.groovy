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

// ── new program seeding ──
seedModes = [[id: 1L, name: 'Day'], [id: 4L, name: 'Away']]
check('new program maps Away mode to Away profile', core.seedOverrides(core.defaultConfig(), seedModes).overrides, [[modeId: 4L, profile: 'Away']])
check('Away matched by name, any case', core.seedOverrides(core.defaultConfig(), [[id: 7L, name: 'away']]).overrides, [[modeId: 7L, profile: 'Away']])
check('no Away mode leaves overrides empty', core.seedOverrides(core.defaultConfig(), [[id: 1L, name: 'Day']]).overrides, [])
check('no Away profile leaves overrides empty', core.seedOverrides(core.defaultConfig() + [profiles: [[name: 'Home', heat: 21.0]]], seedModes).overrides, [])
check('existing overrides are kept', core.seedOverrides(core.defaultConfig() + [overrides: [[modeId: 1L, profile: 'Home']]], seedModes).overrides, [[modeId: 1L, profile: 'Home']])
check('seeded default config validates', core.validateConfig(core.seedOverrides(core.defaultConfig(), seedModes), [scale: 'C', vars: [], modeIds: [1L, 4L]]), [])

// ── overdue write check ──
check('no pending check is not overdue', core.verifyOverdue(null, 100000L), false)
check('check 30 s old is not overdue', core.verifyOverdue([writes: [], at: 70000L], 100000L), false)
check('check 60 s old is not overdue', core.verifyOverdue([writes: [], at: 40000L], 100000L), false)
check('check 61 s old is overdue', core.verifyOverdue([writes: [], at: 39000L], 100000L), true)
check('check without a time is overdue', core.verifyOverdue([writes: []], 100000L), true)

check('check 61 s after the first write but 20 s after the latest is not overdue', core.verifyOverdue([writes: [], at: 39000L, last: 80000L], 100000L), false)
check('check 61 s after the latest write is overdue', core.verifyOverdue([writes: [], at: 10000L, last: 39000L], 100000L), true)

// ── merged write checks ──
pend = [[id: '1', command: 'setHeatingSetpoint', value: 21.0], [id: '2', command: 'setHeatingSetpoint', value: 21.0]]
mrg = core.mergeWrites(pend, [[id: '2', command: 'setHeatingSetpoint', value: 18.0], [id: '2', command: 'setCoolingSetpoint', value: 26.0]])
check('merge keeps writes the new batch does not touch', mrg.findAll { it.id == '1' }.size(), 1)
check('merge replaces the same thermostat and command', mrg.findAll { it.id == '2' && it.command == 'setHeatingSetpoint' }*.value, [18.0])
check('merge adds new commands', mrg.size(), 3)
check('merge into nothing is the batch', core.mergeWrites(null, pend), pend)
check('merged batch with an unconfirmed earlier write is partial', core.applyOutcome(mrg, ['2']), 'partial')

// ── hub variable rename ──
vcfg = [profiles: [[name: 'V', heatVar: 'comfort', coolVar: 'comfort'], [name: 'H', heat: 20.0]],
        schedules: [[name: 'N', type: 'time', groups: [[name: 'A', days: [1,2,3,4,5,6,7], periods: [
            [name: 'Night', start: [kind: 'var', name: 'comfort'], profile: 'H'],
            [name: 'Day', start: [kind: 'time', at: '06:00'], profile: 'H']]]]]]]
check('rename reports a change', core.renameVarRefs(vcfg, 'comfort', 'cosy'), true)
check('rename rewrites profile variables', [vcfg.profiles[0].heatVar, vcfg.profiles[0].coolVar], ['cosy', 'cosy'])
check('rename rewrites period starts', vcfg.schedules[0].groups[0].periods[0].start.name, 'cosy')
check('rename leaves other periods alone', vcfg.schedules[0].groups[0].periods[1].start, [kind: 'time', at: '06:00'])
check('rename of an unused variable changes nothing', core.renameVarRefs(vcfg, 'other', 'x'), false)
check('rename on an empty config changes nothing', core.renameVarRefs([:], 'a', 'b'), false)

// ── hold setpoint range ──
check('hold setpoints in range (C)', core.setpointRangeError(21.0, 24.0, 'C'), null)
check('hold heating over 40 C', core.setpointRangeError(41.0, null, 'C') != null, true)
check('hold cooling under 0 C', core.setpointRangeError(null, -1.0, 'C') != null, true)
check('hold 70 F in range', core.setpointRangeError(70.0, 75.0, 'F'), null)
check('hold 31 F out of range', core.setpointRangeError(31.0, null, 'F') != null, true)
check('hold 104 F in range', core.setpointRangeError(null, 104.0, 'F'), null)
check('hold 70 is out of range in C', core.setpointRangeError(70.0, null, 'C') != null, true)

// ── fan writes and off thermostats ──
check('no fan write to an off thermostat', cmds([layer: 'schedule', fan: 'on'], th('off')), [])
check('no fan write when the target turns the thermostat off', cmds([layer: 'schedule', fan: 'on', mode: 'off'], th('heat')), ['setThermostatMode=off'])
check('fan write when the target turns an off thermostat on', cmds([layer: 'schedule', fan: 'on', mode: 'heat'], th('off')), ['setThermostatMode=heat', 'setThermostatFanMode=on'])
check('no fan write to an off thermostat kept off by noModeIds', core.planWrites([layer: 'schedule', fan: 'on', mode: 'heat'], [th('off')], [separation: 2.0, noModeIds: ['1']]), [])

// ── strict end parsing ──
check('ISO with trailing junk is an error', core.parseEnd('2026-10-05T22:00:00-04:00junk').error != null, true)
check('ISO minutes form with trailing junk is an error', core.parseEnd('2026-10-05T22:00-04:00x').error != null, true)
check('ISO minutes form parses', core.parseEnd('2026-10-05T22:00-04:00').end, 'at')
isoNow = core.parseEnd('2026-10-05T22:00:00-04:00').until as long
check('ISO time in the past is an error', core.parseEnd('2026-10-05T22:00:00-04:00', isoNow + 1000L).error != null, true)
check('ISO time now is an error', core.parseEnd('2026-10-05T22:00:00-04:00', isoNow).error != null, true)
check('ISO time in the future is accepted', core.parseEnd('2026-10-05T22:00:00-04:00', isoNow - 1000L).end, 'at')
check('past ISO end makes the command fail', core.parseCommand([command: 'holdProfile', profile: 'Sleep', end: '2026-10-05T22:00:00-04:00'], isoNow + 1000L).ok, false)

// ── PUT ──
envC = [scale: 'C', vars: ['comfort'], modeIds: [1L, 2L, 3L, 4L]]
check('revision is stable', core.revisionOf(cfgBase), core.revisionOf(core.parseJsonLike(cfgBase)))
check('revision changes with a value', core.revisionOf(cfgBase) != core.revisionOf(cfgBase + [active: 'Cottage']), true)
check('shape: complete document passes', core.putShapeErrors([profiles: [], schedules: [], active: 'N', overrides: []]), [])
check('shape: missing profiles', core.putShapeErrors([schedules: [], active: 'N', overrides: []]), ['config.profiles must be a list'])
check('shape: active must be text', core.putShapeErrors([profiles: [], schedules: [], active: 3, overrides: []]), ['config.active must be text'])
check('shape: overrides may be absent', core.putShapeErrors([profiles: [], schedules: [], active: 'N']), [])
check('document keeps only document fields', core.putDocument(cfgBase).keySet() as List, ['v', 'profiles', 'schedules', 'active', 'overrides'])
check('document defaults overrides', core.putDocument([profiles: [], schedules: [], active: 'N']).overrides, [])
check('options: none present is valid', core.validateOptions([:], envC), [])
check('options: eco offset range', core.validateOptions([eco: [offset: 11]], envC), ['eco.offset must be a number from -10 to 10'])
check('options: whilePaused value', core.validateOptions([options: [whilePaused: 'bogus']], envC), ['options.whilePaused must be leave or off'])
check('options: onResume value', core.validateOptions([options: [onResume: 'x']], envC), ['options.onResume must be restore or leaveOff'])
check('options: separation range', core.validateOptions([options: [separation: -1]], envC), ['options.separation must be a number from 0 to 10'])
check('options: day out of range', core.validateOptions([restrictions: [days: [0]]], envC), ['restrictions.days must hold days 1 to 7'])
check('options: unknown mode', core.validateOptions([restrictions: [modeIds: [99]]], envC), ['restrictions.modeIds: unknown mode 99'])
check('options: bad window start', core.validateOptions([restrictions: [from: [kind: 'time', at: '25:00']]], envC), ['restrictions.from is not a valid start'])
check('options: sunset window start', core.validateOptions([restrictions: [from: [kind: 'sunset', offset: -15]]], envC), [])
check('settings: eco group', core.optionSettings([eco: [offset: 3, onOverrides: false]]),
      [[name: 'ecoOffset', type: 'decimal', value: 3], [name: 'ecoOnOverrides', type: 'bool', value: false]])
check('settings: absent groups write nothing', core.optionSettings([:]), [])
check('settings: window cleared', core.optionSettings([restrictions: [from: null]]).findAll { it.name.startsWith('restrictFrom') },
      [[name: 'restrictFrom', type: 'enum', value: 'any'], [name: 'restrictFromAt', type: 'time', value: null], [name: 'restrictFromOffset', type: 'number', value: null]])
check('settings: window at a time', core.optionSettings([restrictions: [to: [kind: 'time', at: '07:15']]]).findAll { it.name.startsWith('restrictTo') },
      [[name: 'restrictTo', type: 'enum', value: 'time'], [name: 'restrictToAt', type: 'time', value: '07:15'], [name: 'restrictToOffset', type: 'number', value: null]])
check('settings: days and modes as text', core.optionSettings([restrictions: [days: [1, 7], modeIds: [4]]]).findAll { it.name in ['restrictDays', 'restrictModes'] },
      [[name: 'restrictDays', type: 'enum', value: ['1', '7']], [name: 'restrictModes', type: 'mode', value: ['4']]])

// ══ later tasks append cases above this line ══
println "${passed} passed, ${failed} failed"
System.exit(failed ? 1 : 0)

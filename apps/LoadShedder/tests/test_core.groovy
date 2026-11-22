// Copyright (c) 2026 PJ
// SPDX-License-Identifier: MIT
//
// Core unit tests for Load Shedder. Parses the block between the "Core (pure)" and
// "End core" markers of the shipped app file and runs it, so the tests bind to shipped code.
// Run under the pinned Groovy 2.4.21 jar:
//   java -cp groovy-all-2.4.21.jar groovy.ui.GroovyMain <this file>

File here = new File(getClass().protectionDomain.codeSource.location.toURI()).parentFile
String src = new File(here.parentFile, 'LoadShedder.groovy').text
int a = src.indexOf('// ── Core (pure)'), b = src.indexOf('// ── End core')
if (a < 0 || b < a) { println '[FAIL] core markers not found'; System.exit(2) }
core = new GroovyShell().parse('import groovy.transform.CompileStatic; ' + src.substring(a, b))   // same line keeps line numbers

passed = 0; failed = 0   // binding variables: script methods can't see typed locals
void check(String name, Object got, Object want) {
    if (got == want || (got instanceof Number && want instanceof Number && (got as BigDecimal) == (want as BigDecimal))) {
        passed++; println "[PASS] ${name}"
    } else { failed++; println "[FAIL] ${name}: expected ${want}, got ${got}" }
}

M = 60000L
IDS = ['1', '2', '3']
Map cfg(Map over = [:]) { [manualOn: 'respect', delayMs: 0L, staggerMs: 0L, gapMs: 300000L] + over }
// One evaluation; vals: switch values by id.
Map run(Map mem, boolean shed, Map vals, Map over = [:], long t = 0L, Map busy = [:], boolean restart = false, List ids = IDS) {
    core.step(mem, shed, vals, ids, busy, cfg(over), t, restart)
}
// Applies the actions to the values, as obedient switches would.
Map apply(Map vals, Map r) { Map v = new LinkedHashMap(vals); r.actions.each { v[it.id] = it.cmd }; v }
List cmds(Map r) { r.actions.collect { "${it.id}:${it.cmd}".toString() } }

// ── reasons ──
check('switch on is a reason', core.activeReasons([[name: 'Peak', value: 'on']], [], 'Home', '1', []), ['Peak on'])
check('switch off is a reason', core.activeReasons([], [[name: 'Gate', value: 'off']], 'Home', '1', []), ['Gate off'])
check('mode by id', core.activeReasons([], [], 'Away', '4', ['4']), ['mode Away'])
check('mode by id as number', core.activeReasons([], [], 'Away', '4', [4L]), ['mode Away'])
check('mode by name', core.activeReasons([], [], 'Away', '4', ['Away']), ['mode Away'])
check('every reason listed', core.activeReasons([[name: 'Peak', value: 'on'], [name: 'X', value: 'off']], [[name: 'Gate', value: 'off']], 'Away', '4', ['4']),
      ['Peak on', 'Gate off', 'mode Away'])
check('nothing holds', core.activeReasons([[name: 'Peak', value: 'off']], [[name: 'Gate', value: 'on']], 'Home', '1', ['4']), [])
check('null lists', core.activeReasons(null, null, 'Home', '1', null), [])
check('null value is no reason', core.activeReasons([[name: 'P', value: null]], [[name: 'G', value: null]], null, null, []), [])

// ── validation ──
check('valid', core.validateCfg(['1'], ['9'], [], []), [])
check('mode alone is enough', core.validateCfg(['1'], [], [], ['4']), [])
check('no loads', core.validateCfg([], ['9'], [], []), ['Choose at least one switch to shed'])
check('no reasons', core.validateCfg(['1'], [], [], []), ['Choose at least one reason: a switch or a mode'])
check('load as reason', core.validateCfg(['1'], [], ['1'], []), ['A switch to shed cannot also be a reason'])

// ── helpers ──
check('busy merges attempts over failures', core.busyCommands([2: [want: 'on']], ['1': [want: 'off'], '2': [want: 'off']]), ['1': 'off', '2': 'on'])
check('verify ok', core.verifyOutcome('off', 'off', 1, 2), 'ok')
check('verify retry', core.verifyOutcome('off', 'on', 1, 2), 'retry')
check('verify fault', core.verifyOutcome('off', 'on', 2, 2), 'fault')
check('verify null reading', core.verifyOutcome('on', null, 2, 2), 'fault')
check('off wanted while held', core.commandWanted('off', '1', [phase: 'shed', owned: ['1']]), true)
check('off not wanted once restoring', core.commandWanted('off', '1', [phase: 'restore', owned: ['1']]), false)
check('off not wanted for a released load', core.commandWanted('off', '1', [phase: 'shed', owned: []]), false)
check('on wanted when idle', core.commandWanted('on', '1', [phase: 'idle', owned: []]), true)
check('on not wanted once held again', core.commandWanted('on', '1', [phase: 'shed', owned: ['1']]), false)

// ── a shed: start, hold, restore ──
Map v0 = ['1': 'on', '2': 'off', '3': 'on']
Map r1 = run(null, true, v0)
check('start: on loads turned off', cmds(r1), ['1:off', '3:off'])
check('start: already-off load is not ours', r1.mem.owned, ['1', '3'])
check('start: phase', r1.mem.phase, 'shed')
Map v1 = apply(v0, r1)
Map r2 = run(r1.mem, true, v1)
check('hold: nothing to do', cmds(r2), [])
check('hold: same owners', r2.mem.owned, ['1', '3'])
Map r3 = run(r2.mem, false, v1)
check('restore: only ours back on', cmds(r3), ['1:on', '3:on'])
check('restore: idle and forgotten', [r3.mem.phase, r3.mem.owned, r3.mem.known], ['idle', [], []])
check('restore: no timer', r3.wakeAt, null)
Map r4 = run(r3.mem, false, apply(v1, r3))
check('idle stays idle', [cmds(r4), r4.mem.phase], [[], 'idle'])
check('nothing on at start: nothing to restore', run(run(null, true, ['1': 'off', '2': 'off', '3': 'off']).mem, false, ['1': 'off', '2': 'off', '3': 'off']).actions, [])
check('nothing on at start: phase still shed', run(null, true, ['1': 'off', '2': 'off', '3': 'off']).mem.phase, 'shed')

// ── overlapping reasons: the decision only sees "any reason holds" ──
// peak starts, Away starts, peak ends (still shed), Away ends (restored once)
Map o1 = run(null, true, v0); Map ov = apply(v0, o1)
Map o2 = run(o1.mem, true, ov)       // second reason
Map o3 = run(o2.mem, true, ov)       // first reason clears, one still holds
check('overlap: no command while any reason holds', cmds(o2) + cmds(o3), [])
Map o4 = run(o3.mem, false, ov)
check('overlap: restored once at the last reason', cmds(o4), ['1:on', '3:on'])
Map o5 = run(o4.mem, false, apply(ov, o4))
check('overlap: not restored twice', cmds(o5), [])

// ── manual on during the shed ──
Map mv = v1 + ['1': 'on']
Map m1 = run(r2.mem, true, mv)
check('respect: left on', cmds(m1), [])
check('respect: no longer ours', [m1.mem.owned, m1.mem.released], [['3'], ['1']])
Map m2 = run(m1.mem, true, mv)
check('respect: stays left alone', cmds(m2), [])
Map m3 = run(m2.mem, false, mv)
check('respect: restore skips it, restores the rest', cmds(m3), ['3:on'])
Map mOff = run(m1.mem, true, mv + ['1': 'off'])
check('respect: turned off again by hand, not ours', [cmds(run(mOff.mem, false, mv + ['1': 'off'])), mOff.mem.owned], [['3:on'], ['3']])

Map e1 = run(r2.mem, true, mv, [manualOn: 'enforce'], 10 * M)
check('enforce: turned off again', cmds(e1), ['1:off'])
check('enforce: still ours', e1.mem.owned, ['1', '3'])
Map e2 = run(e1.mem, true, mv, [manualOn: 'enforce'], 11 * M)
check('enforce: throttled within 5 minutes', cmds(e2), [])
check('enforce: wakes when the throttle ends', e2.wakeAt, 15 * M)
Map e3 = run(e2.mem, true, mv, [manualOn: 'enforce'], 15 * M)
check('enforce: again after 5 minutes', cmds(e3), ['1:off'])
check('enforce: restored at the end', cmds(run(e3.mem, false, v1, [manualOn: 'enforce'], 20 * M)), ['1:on', '3:on'])

// a load that was off at the start (not ours) turned on during the shed
Map n2 = v1 + ['2': 'on']
check('respect: not-ours load turned on is left on', [cmds(run(r2.mem, true, n2)), run(r2.mem, true, n2).mem.released], [[], ['2']])
Map ne = run(r2.mem, true, n2, [manualOn: 'enforce'])
check('enforce: not-ours load turned on is shed and becomes ours', [cmds(ne), ne.mem.owned], [['2:off'], ['1', '3', '2']])

// ── restart mid-shed ──
check('restart: a held load back on with the power is turned off, not released',
      [cmds(run(r2.mem, true, mv, [:], 0L, [:], true)), run(r2.mem, true, mv, [:], 0L, [:], true).mem.owned], [['1:off'], ['1', '3']])
check('restart: reasons gone while down: restored at start', cmds(run(r2.mem, false, v1, [:], 0L, [:], true)), ['1:on', '3:on'])
// a missed start: the hub came up after the reason began, nothing shed yet
check('restart: missed start is applied', cmds(run([phase: 'idle'], true, v0, [:], 0L, [:], true)), ['1:off', '3:off'])

check('came back: long silence', core.cameBack(0L, 12 * M, 11 * M), true)
check('came back: normal cadence', core.cameBack(0L, 5 * M, 11 * M), false)
check('came back: first evaluation ever', core.cameBack(null, 5 * M, 11 * M), false)

// ── commands that do not land ──
Map f1 = run(r2.mem, true, mv, [:], 0L, ['1': 'off'])
check('pending or failed off: not taken for a manual on', [cmds(f1), f1.mem.owned, f1.mem.released], [[], ['1', '3'], []])
check('failed off, reasons clear: load reads on, nothing to restore for it', cmds(run(r2.mem, false, mv, [:], 0L, ['1': 'off'])), ['3:on'])
// the restore of load 1 did not land (it reads off, an "on" is failed), then a new shed starts
Map g1 = run([phase: 'idle'], true, ['1': 'off', '2': 'off', '3': 'on'], [:], 0L, ['1': 'on'])
check('failed restore: the load still counts as ours in the next shed', g1.mem.owned, ['1', '3'])
check('failed restore: no off sent to a load already off', cmds(g1), ['3:off'])
check('failed restore: restored at the next end', cmds(run(g1.mem, false, ['1': 'off', '2': 'off', '3': 'off'])), ['1:on', '3:on'])

// ── restore delay and stagger ──
Map d1 = run(r2.mem, false, v1, [delayMs: 10 * M], 0L)
check('delay: nothing yet', [cmds(d1), d1.mem.phase, d1.wakeAt], [[], 'restore', 10 * M])
check('delay: still waiting', cmds(run(d1.mem, false, v1, [delayMs: 10 * M], 5 * M)), [])
Map d2 = run(d1.mem, false, v1, [delayMs: 10 * M], 10 * M)
check('delay: restored when due', [cmds(d2), d2.mem.phase], [['1:on', '3:on'], 'idle'])
Map d3 = run(d1.mem, true, v1, [delayMs: 10 * M], 5 * M)
check('delay: a reason during the wait resumes the shed, owners kept', [cmds(d3), d3.mem.phase, d3.mem.owned, d3.wakeAt], [[], 'shed', ['1', '3'], null])
check('delay: then restored once', cmds(run(run(d3.mem, false, v1, [delayMs: 0L], 6 * M).mem, false, v1, [:], 6 * M)), [])
check('delay: zero restores at once after resume', cmds(run(d3.mem, false, v1, [:], 6 * M)), ['1:on', '3:on'])
Map d4 = run(d1.mem, false, v1 + ['1': 'on'], [delayMs: 10 * M], 5 * M)
check('delay: a load turned on during the wait is dropped', d4.mem.owned, ['3'])

Map s1 = run(r2.mem, false, v1, [staggerMs: 30000L], 0L)
check('stagger: first load', [cmds(s1), s1.wakeAt, s1.mem.phase], [['1:on'], 30000L, 'restore'])
check('stagger: not before its time', cmds(run(s1.mem, false, apply(v1, s1), [staggerMs: 30000L], 10000L)), [])
Map s2 = run(s1.mem, false, apply(v1, s1), [staggerMs: 30000L], 30000L)
check('stagger: second load', [cmds(s2), s2.mem.phase], [['3:on'], 'idle'])
Map s3 = run(s1.mem, true, apply(v1, s1), [staggerMs: 30000L], 10000L)
check('stagger: a reason mid-restore sheds the restored load again and keeps the rest',
      [cmds(s3), s3.mem.owned], [['1:off'], ['3', '1']])

// ── loads added or removed while shedding ──
Map a1 = run(r2.mem, true, v1 + ['4': 'on'], [:], 0L, [:], false, ['1', '2', '3', '4'])
check('added load that is on is shed', [cmds(a1), a1.mem.owned], [['4:off'], ['1', '3', '4']])
Map rm = run(r2.mem, true, ['1': 'off', '2': 'off'], [:], 0L, [:], false, ['1', '2'])
check('removed load drops out, with a note', [rm.mem.owned, rm.notes.any { it.contains('{3}') }], [['1'], true])

// ── reasons in every order, end to end: on, off and mode each start and end a shed ──
['peak', 'gate', 'mode'].permutations().each { List order ->
    Map mem = null; Map vals = ['1': 'on', '2': 'off', '3': 'on']; Set held = [] as Set; List all = []
    (order + order).each { String which ->     // each reason starts in turn, then each clears in the same order
        if (held.contains(which)) held.remove(which) else held << which
        Map r = run(mem, !held.isEmpty(), vals)
        mem = r.mem; vals = apply(vals, r); all.addAll(cmds(r))
    }
    check("order ${order.join(' > ')}: one off and one on per owned load", all, ['1:off', '3:off', '1:on', '3:on'])
}

// ══ later tasks append cases above this line ══
println "${passed} passed, ${failed} failed"
System.exit(failed ? 1 : 0)

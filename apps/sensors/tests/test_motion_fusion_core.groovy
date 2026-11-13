// Copyright (c) 2026 PJ
// SPDX-License-Identifier: MIT
//
// Unit tests for the Motion Fusion decisions. Parses the block between the "Core (pure)" and
// "End core" markers of MotionFusionChild.groovy and runs it, so the tests bind to shipped code.
//   java -cp groovy-all-2.4.21.jar groovy.ui.GroovyMain <this file>
//
// Assertions marked "current behaviour, see H-1" pin what the confirmation window does today in
// the both, pirGated and pirConfirmedMmwave modes (nothing): the window's intended meaning is
// an open decision, so revise those checks when it is made.

File here = new File(getClass().protectionDomain.codeSource.location.toURI()).parentFile
passed = 0; failed = 0
void check(String name, Object got, Object want) {
    if (got == want) { passed++; println "[PASS] ${name}" }
    else { failed++; println "[FAIL] ${name}: expected ${want}, got ${got}" }
}
String src = new File(here.parentFile, 'MotionFusionChild.groovy').text
int a = src.indexOf('// ── Core (pure)'), b = src.indexOf('// ── End core')
if (a < 0 || b < a) { println '[FAIL] core markers not found'; System.exit(2) }
core = new GroovyShell().parse(src.substring(a, b))

// The plan without its log lines.
Map step(String mode, String trigger, boolean pir, boolean mm, String out = 'inactive', boolean pending = false, Integer seconds = 5) {
    core.fusionStep(mode, trigger, pir, mm, out, pending, seconds).findAll { k, v -> !(k in ['note', 'debug']) }
}
Map timeout(String handler, boolean pir, boolean mm, String out) {
    core.timeoutStep(handler, pir, mm, out).findAll { k, v -> !(k in ['note', 'debug']) }
}

// A small replay of applyPlan() for event sequences: tracks the output, the pending flag and
// the one armed timer. fire() stands for that timer coming due with no event in between.
Map sim(String mode, Integer seconds = 5) { [mode: mode, seconds: seconds, pir: false, mm: false, out: 'inactive', pending: false, timer: null] }
void apply(Map s, Map p) {
    if (p.cancel && s.timer == p.cancel) s.timer = null
    if (p.clear) s.pending = false
    if (p.arm) { s.pending = true; s.timer = p.arm }
    if (p.output) s.out = p.output
}
Map ev(Map s, String trigger, boolean value) {
    if (trigger == 'pir') s.pir = value else s.mm = value
    apply(s, core.fusionStep(s.mode, trigger, s.pir, s.mm, s.out, s.pending, s.seconds))
    return s
}
Map fire(Map s) {
    if (s.timer) { String h = s.timer; s.timer = null; s.pending = false; apply(s, core.timeoutStep(h, s.pir, s.mm, s.out)) }
    return s
}
TF = [true, false]   // binding variable, as below: script methods can't see typed locals

// ── pirOnly ──
check('pirOnly: PIR active is active', step('pirOnly', 'pir', true, false), [output: 'active'])
check('pirOnly: PIR inactive is inactive, mmWave ignored', step('pirOnly', 'mmwave', false, true, 'active'), [output: 'inactive'])
check('pirOnly: never arms a timer', TF.collectMany { p -> TF.collect { m -> step('pirOnly', 'pir', p, m).arm } }.unique(), [null])

// ── mmwaveOnly ──
check('mmwaveOnly: occupied is active and cancels the delay', step('mmwaveOnly', 'mmwave', false, true, 'inactive', true, 4), [cancel: 'delayedInactive', clear: true, output: 'active'])
check('mmwaveOnly: unoccupied with no delay is inactive', step('mmwaveOnly', 'mmwave', true, false, 'active', false, 0), [output: 'inactive'])
check('mmwaveOnly: unoccupied with a delay arms it', step('mmwaveOnly', 'mmwave', false, false, 'active', false, 4), [arm: 'delayedInactive', armSeconds: 4])
check('mmwaveOnly: an armed delay is not re-armed', step('mmwaveOnly', 'pir', false, false, 'active', true, 4), [:])
check('mmwaveOnly: PIR is ignored', step('mmwaveOnly', 'pir', true, false, 'inactive', false, 0), [output: 'inactive'])
check('mmwaveOnly: delay expiry goes inactive', timeout('delayedInactive', false, false, 'active'), [output: 'inactive'])
check('mmwaveOnly: delay expiry with mmWave back stays', timeout('delayedInactive', false, true, 'active'), [:])
Map mo = sim('mmwaveOnly', 4)
ev(mo, 'mmwave', true); check('mmwaveOnly sequence: occupied', mo.out, 'active')
ev(mo, 'mmwave', false); check('mmwaveOnly sequence: held during the delay', [mo.out, mo.timer], ['active', 'delayedInactive'])
ev(mo, 'mmwave', true); check('mmwaveOnly sequence: re-occupied cancels the delay', [mo.out, mo.timer, mo.pending], ['active', null, false])
ev(mo, 'mmwave', false); fire(mo); check('mmwaveOnly sequence: inactive after the delay', mo.out, 'inactive')

// ── either ──
check('either: truth table', TF.collectMany { p -> TF.collect { m -> step('either', 'pir', p, m).output } }, ['active', 'active', 'active', 'inactive'])

// ── both ──
check('both: both on is active, the window timer cancelled', step('both', 'pir', true, true), [cancel: 'confirmationTimeout', output: 'active'])
check('both: mmWave completing the pair is active too', step('both', 'mmwave', true, true), [cancel: 'confirmationTimeout', output: 'active'])
check('both: one on from inactive arms the window', step('both', 'mmwave', false, true), [arm: 'confirmationTimeout', armSeconds: 5])
check('both: one on while waiting does not re-arm', step('both', 'pir', true, false, 'inactive', true), [:])
check('both: one drops while active goes inactive at once', step('both', 'pir', false, true, 'active'), [output: 'inactive'])
check('both: both off is inactive and clears the window', step('both', 'mmwave', false, false, 'active', true), [cancel: 'confirmationTimeout', clear: true, output: 'inactive'])
check('both: window expiry with both on is active', timeout('confirmationTimeout', true, true, 'inactive'), [output: 'active'])
check('both: window expiry without agreement is inactive', timeout('confirmationTimeout', true, false, 'inactive'), [output: 'inactive'])
// mmWave at t=0, the 5 s window runs out, PIR at t=20 s
Map bo = sim('both'); ev(bo, 'mmwave', true); fire(bo); ev(bo, 'pir', true)
check('both: a second sensor after the window still turns it active (current behaviour, see H-1)', bo.out, 'active')

// ── pirGated ──
check('pirGated: mmWave unoccupied is inactive, regardless of PIR', step('pirGated', 'pir', true, false, 'active', true), [cancel: 'mmwaveConfirmationTimeout', clear: true, output: 'inactive'])
check('pirGated: both on is active', step('pirGated', 'mmwave', true, true), [cancel: 'mmwaveConfirmationTimeout', clear: true, output: 'active'])
check('pirGated: PIR drops while mmWave holds stays active', step('pirGated', 'pir', false, true, 'active'), [:])
check('pirGated: mmWave alone does nothing', step('pirGated', 'mmwave', false, true), [:])
check('pirGated: the window is never armed (current behaviour, see H-1)',
      ['pir', 'mmwave'].collectMany { t -> TF.collectMany { p -> TF.collectMany { m -> ['active', 'inactive'].collectMany { o -> TF.collect { pe -> step('pirGated', t, p, m, o, pe).arm } } } } }.unique(), [null])
Map pg = sim('pirGated'); ev(pg, 'mmwave', true); fire(pg); ev(pg, 'pir', true)
check('pirGated: PIR 20 s after mmWave still turns it active (current behaviour, see H-1)', pg.out, 'active')
Map pg2 = sim('pirGated'); ev(pg2, 'pir', true); ev(pg2, 'pir', false); ev(pg2, 'mmwave', true)
check('pirGated: PIR, PIR clears, then mmWave within the window stays inactive (current behaviour, see H-1)', pg2.out, 'inactive')
check('pirGated: window expiry with both on is active', timeout('mmwaveConfirmationTimeout', true, true, 'inactive'), [output: 'active'])
check('pirGated: window expiry keeps an active output', timeout('mmwaveConfirmationTimeout', false, true, 'active'), [output: null])
check('pirGated: window expiry from inactive is inactive', timeout('mmwaveConfirmationTimeout', true, false, 'inactive'), [output: 'inactive'])

// ── pirConfirmedMmwave ──
check('pirConfirmedMmwave: mmWave unoccupied is inactive', step('pirConfirmedMmwave', 'mmwave', true, false, 'active', true), [cancel: 'pirConfirmationTimeout', clear: true, output: 'inactive'])
check('pirConfirmedMmwave: PIR with mmWave is active', step('pirConfirmedMmwave', 'pir', true, true), [cancel: 'pirConfirmationTimeout', clear: true, output: 'active'])
check('pirConfirmedMmwave: mmWave from inactive arms the window', step('pirConfirmedMmwave', 'mmwave', false, true), [arm: 'pirConfirmationTimeout', armSeconds: 5])
check('pirConfirmedMmwave: a PIR event without PIR arms nothing', step('pirConfirmedMmwave', 'pir', false, true), [:])
check('pirConfirmedMmwave: PIR drops while mmWave holds stays active', step('pirConfirmedMmwave', 'pir', false, true, 'active'), [:])
check('pirConfirmedMmwave: window expiry keeps an active output', timeout('pirConfirmationTimeout', false, true, 'active'), [output: null])
check('pirConfirmedMmwave: window expiry without PIR changes nothing from inactive (current behaviour, see H-1)', timeout('pirConfirmationTimeout', false, true, 'inactive'), [output: 'inactive'])
Map pc = sim('pirConfirmedMmwave'); ev(pc, 'mmwave', true); fire(pc); ev(pc, 'pir', true)
check('pirConfirmedMmwave: PIR 20 s after mmWave still turns it active (current behaviour, see H-1)', pc.out, 'active')

// pirGated and pirConfirmedMmwave give the same outputs on every sequence here.
seqs = [[['mmwave', true], ['pir', true]], [['pir', true], ['mmwave', true]], [['pir', true], ['pir', false], ['mmwave', true]],
                   [['mmwave', true], ['pir', true], ['pir', false]], [['mmwave', true], ['pir', true], ['mmwave', false]],
                   [['mmwave', true], 'fire', ['pir', true]], [['pir', true], 'fire', ['mmwave', true], 'fire']]
List<String> outs(String mode) {
    seqs.collect { List seq -> Map s = sim(mode); seq.collect { e -> e == 'fire' ? fire(s).out : ev(s, e[0], e[1]).out }.join(' ') }
}
check('pirGated and pirConfirmedMmwave are the same mode (current behaviour, see H-1)', outs('pirGated'), outs('pirConfirmedMmwave'))

// ── pirQuickMmwaveHold ──
check('pirQuick: PIR is active at once and cancels the cooldown', step('pirQuickMmwaveHold', 'pir', true, false, 'inactive', true, 30), [cancel: 'cooldownExpired', clear: true, output: 'active'])
check('pirQuick: mmWave alone does not start motion', step('pirQuickMmwaveHold', 'mmwave', false, true, 'inactive', false, 30), [cancel: 'cooldownExpired', clear: true])
check('pirQuick: mmWave sustains an active output', step('pirQuickMmwaveHold', 'pir', false, true, 'active', true, 30), [cancel: 'cooldownExpired', clear: true])
check('pirQuick: both off while active starts the cooldown', step('pirQuickMmwaveHold', 'mmwave', false, false, 'active', false, 30), [arm: 'cooldownExpired', armSeconds: 30])
check('pirQuick: a running cooldown is not re-armed', step('pirQuickMmwaveHold', 'mmwave', false, false, 'active', true, 30), [:])
check('pirQuick: both off while inactive does nothing', step('pirQuickMmwaveHold', 'mmwave', false, false, 'inactive', false, 30), [:])
check('pirQuick: cooldown expiry is inactive', timeout('cooldownExpired', false, false, 'active'), [output: 'inactive'])
check('pirQuick: cooldown expiry with a sensor back stays', timeout('cooldownExpired', false, true, 'active'), [:])

// ── unknown ──
check('unknown mode is flagged', core.fusionStep('nope', 'pir', true, true, 'inactive', false, 5), [unknown: true])
check('unknown timer does nothing', core.timeoutStep('nope', true, true, 'inactive'), [:])

println "\n${passed} passed, ${failed} failed"
System.exit(failed ? 1 : 0)

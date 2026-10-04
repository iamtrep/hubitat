// Copyright (c) 2026 PJ
// SPDX-License-Identifier: MIT
//
// Core unit tests for HVAC Interlock. Parses the block between the "Core (pure)"
// and "End core" markers of the shipped group file and runs it, so the tests bind
// to shipped code. Run under the pinned Groovy 2.4.21 jar:
//   java -cp groovy-all-2.4.21.jar groovy.ui.GroovyMain <this file>

File here = new File(getClass().protectionDomain.codeSource.location.toURI()).parentFile
File app = new File(here.parentFile, 'HvacInterlockGroup.groovy')
if (!app.exists()) { println '[FAIL] HvacInterlockGroup.groovy not found'; System.exit(2) }
String src = app.text
int a = src.indexOf('// ── Core (pure)'), b = src.indexOf('// ── End core')
if (a < 0 || b < a) { println '[FAIL] core markers not found'; System.exit(2) }
core = new GroovyShell().parse(src.substring(a, b))

passed = 0; failed = 0   // binding variables: script methods can't see typed locals
void check(String name, Object got, Object want) {
    if (got == want || (got instanceof Number && want instanceof Number && (got as BigDecimal) == (want as BigDecimal))) {
        passed++; println "[PASS] ${name}"
    } else { failed++; println "[FAIL] ${name}: expected ${want}, got ${got}" }
}

// ── season policy ──
check('permit allowed', core.wantedFor('permit', [winter: true], 'winter'), 'on')
check('permit not allowed', core.wantedFor('permit', [summer: false], 'summer'), 'off')
check('permit string true', core.wantedFor('permit', [winter: 'true'], 'winter'), 'on')
check('direct mode', core.wantedFor('direct', [summer: 'cool'], 'summer'), 'cool')
check('direct unknown mode is off', core.wantedFor('direct', [summer: 'dry'], 'summer'), 'off')
check('unknown season gives null', core.wantedFor('permit', [winter: true], null), null)
check('bad season gives null', core.wantedFor('permit', [winter: true], 'monsoon'), null)
check('permit defaults', core.policyFrom('permit', [:]), [winter: true, spring: true, summer: false, fall: true])
check('permit settings', core.policyFrom('permit', [allowSummer: true, allowWinter: false]), [winter: false, spring: true, summer: true, fall: true])
check('direct defaults off', core.policyFrom('direct', [:]), [winter: 'off', spring: 'off', summer: 'off', fall: 'off'])
check('direct settings', core.policyFrom('direct', [modeSummer: 'cool', modeWinter: 'heat']), [winter: 'heat', spring: 'off', summer: 'cool', fall: 'off'])
check('season blocks', core.effective('off', true, 'block'), [state: 'off', blockReason: 'season'])
check('openings block', core.effective('heat', true, 'block'), [state: 'off', blockReason: 'openings'])
check('warn does not block', core.effective('heat', true, 'warn'), [state: 'heat', blockReason: 'none'])
check('runs', core.effective('on', false, 'block'), [state: 'on', blockReason: 'none'])
check('numOrNull string', core.numOrNull('10'), 10)
check('numOrNull junk', core.numOrNull('none'), null)

// ── alert timing ──
base = [raised: false, wanted: 'heat', openSince: null, allClosedSince: null, now: 1000000L, openDelayMs: 600000L, closeDelayMs: 300000L]
Map step(Map over) { core.alertStep(base + over) }
check('nothing open', step([:]), [raised: false, nextCheck: null])
check('open, not long enough', step([openSince: 900000L]), [raised: false, nextCheck: 1500000L])
check('open long enough raises', step([openSince: 400000L]), [raised: true, nextCheck: null])
check('zero delay raises at once', step([openSince: 1000000L, openDelayMs: 0L]), [raised: true, nextCheck: null])
check('open in an off season stays quiet', step([wanted: 'off', openSince: 0L]), [raised: false, nextCheck: null])
check('raised, still open, stays', step([raised: true, openSince: 0L]), [raised: true, nextCheck: null])
check('raised, closed recently, waits', step([raised: true, allClosedSince: 900000L]), [raised: true, nextCheck: 1200000L])
check('raised, closed long enough, clears', step([raised: true, allClosedSince: 600000L]), [raised: false, nextCheck: null])
check('raised, season turns off, clears', step([raised: true, wanted: 'off', openSince: 0L]), [raised: false, nextCheck: null])

// ── contacts, messages, mode writes ──
contacts = [[id: '1', label: 'Window B', open: true, since: 500L], [id: '2', label: 'Door', open: false, since: null], [id: '3', label: 'Window A', open: true, since: 300L]]
check('openList sorted', core.openList(contacts), 'Window A, Window B')
check('openList none open', core.openList([[id: '2', label: 'Door', open: false, since: null]]), '')
check('earliestOpen', core.earliestOpen(contacts), 300L)
check('earliestOpen none', core.earliestOpen([[id: '2', label: 'Door', open: false, since: null]]), null)
check('alertText', core.alertText('Véranda', 'Window A'), 'Véranda: Window A open')
check('clearText', core.clearText('Véranda'), 'Véranda: alert cleared')
check('modeWrites only differing', core.modeWrites([[id: '1', mode: 'heat'], [id: '2', mode: 'off']], 'heat'), ['2'])
check('modeWrites none', core.modeWrites([[id: '1', mode: 'cool']], 'cool'), [])
check('supportsMode listed', core.supportsMode('["heat","cool","off"]', 'cool'), true)
check('supportsMode not listed', core.supportsMode('[heat, off]', 'auto'), false)
check('supportsMode unknown list', core.supportsMode(null, 'auto'), true)
check('supportsMode empty list', core.supportsMode('[]', 'heat'), true)
check('openDelayMs default', core.openDelayMs(null), 600000L)
check('openDelayMs zero', core.openDelayMs(0), 0L)
check('openDelayMs string', core.openDelayMs('3'), 180000L)
check('closeDelay default', core.closeDelayMs(false, 3), 300000L)
check('closeDelay test', core.closeDelayMs(true, '3'), 3000L)
check('closeDelay test not set', core.closeDelayMs(true, 'none'), 300000L)

// ══ later tasks append cases above this line ══
println "${passed} passed, ${failed} failed"
System.exit(failed ? 1 : 0)

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

// ══ later tasks append cases above this line ══
println "${passed} passed, ${failed} failed"
System.exit(failed ? 1 : 0)

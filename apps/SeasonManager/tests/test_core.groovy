// Copyright (c) 2026 PJ
// SPDX-License-Identifier: MIT
//
// Core unit tests for Season Manager. Parses the block between the "Core (pure)"
// and "End core" markers of the shipped app file and runs it, so the tests bind
// to shipped code. Run under the pinned Groovy 2.4.21 jar:
//   java -cp groovy-all-2.4.21.jar groovy.ui.GroovyMain <this file>

File here = new File(getClass().protectionDomain.codeSource.location.toURI()).parentFile
String src = new File(here.parentFile, 'SeasonManager.groovy').text
int a = src.indexOf('// ── Core (pure)'), b = src.indexOf('// ── End core')
if (a < 0 || b < a) { println '[FAIL] core markers not found'; System.exit(2) }
core = new GroovyShell().parse(src.substring(a, b))

passed = 0; failed = 0   // binding variables: script methods can't see typed locals
void check(String name, Object got, Object want) {
    if (got == want || (got instanceof Number && want instanceof Number && (got as BigDecimal) == (want as BigDecimal))) {
        passed++; println "[PASS] ${name}"
    } else { failed++; println "[FAIL] ${name}: expected ${want}, got ${got}" }
}

// ── date helpers ──
check('validMd ok', core.validMd('03-11'), true)
check('validMd bad month', core.validMd('13-01'), false)
check('validMd Feb 30', core.validMd('02-30'), false)
check('validMd Feb 29', core.validMd('02-29'), true)
check('validMd wrong shape', core.validMd('3-11'), false)
check('validMd non-string', core.validMd(311), false)
check('validIso', core.validIso('2026-10-03'), true)
check('validIso bad', core.validIso('2026-1-03'), false)
check('doyOf Jan 1', core.doyOf('01-01'), 1)
check('doyOf Dec 31', core.doyOf('12-31'), 365)
check('doyOf Feb 29 as Feb 28', core.doyOf('02-29'), 59)
check('inMd inside', core.inMd('2026-03-20', '03-11', '04-08'), true)
check('inMd first day', core.inMd('2026-03-11', '03-11', '04-08'), true)
check('inMd last day', core.inMd('2026-04-08', '03-11', '04-08'), true)
check('inMd after', core.inMd('2026-04-09', '03-11', '04-08'), false)
check('inMd wraps new year', core.inMd('2027-01-15', '12-01', '03-31'), true)
check('inMd wraps leap day', core.inMd('2028-02-29', '12-01', '03-31'), true)
check('inMd wraps outside', core.inMd('2026-04-01', '12-01', '03-31'), false)
check('inSpan excludes last day', core.inSpan('2026-09-14', '05-10', '09-14'), false)
check('inSpan includes day before', core.inSpan('2026-09-13', '05-10', '09-14'), true)
check('cyclicOrdered defaults', core.cyclicOrdered(['03-11', '04-08', '05-10', '08-15', '09-14', '10-26', '11-11']), true)
check('cyclicOrdered swapped', core.cyclicOrdered(['03-11', '04-08', '05-10', '09-14', '08-15', '10-26', '11-11']), false)
check('cyclicOrdered rotated start', core.cyclicOrdered(['10-26', '11-11', '03-11', '04-08', '05-10', '08-15', '09-14']), true)
check('cyclicOrdered all same', core.cyclicOrdered(['03-11', '03-11']), false)
check('addDays across year', core.addDays('2026-12-31', 1), '2027-01-01')
check('addDays back across leap day', core.addDays('2028-03-01', -1), '2028-02-29')
check('numOrNull string', core.numOrNull('21.5'), 21.5)
check('numOrNull blank', core.numOrNull(''), null)
check('numOrNull junk', core.numOrNull('abc'), null)
check('fmtNum', core.fmtNum(17), '17.0')

// ══ later tasks append cases above this line ══
println "${passed} passed, ${failed} failed"
System.exit(failed ? 1 : 0)

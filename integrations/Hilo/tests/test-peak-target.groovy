// Copyright (c) 2026 PJ
// SPDX-License-Identifier: MIT
//
// Mode 4 (extraction variant) unit test for the winter-credit target in HiloManager.groovy:
// reference-day choice, the trimmed means, the prorated adjustment, the 40 kWh cap and the
// anchor plan. Brace-extracts easterSunday, noPeakDays and peakTarget from the shipped source and
// runs them on synthetic hourly data; no hub needed.
//
// Run: groovy integrations/Hilo/tests/test-peak-target.groovy

import java.time.LocalDate

File scriptFile = new File(getClass().protectionDomain.codeSource.location.toURI())
File groovySrc = new File(scriptFile.parentFile.parentFile, 'HiloManager.groovy')
assert groovySrc.exists() : "source not found: ${groovySrc}"
String src = groovySrc.text

String extract(String src, String signature) {
    int start = src.indexOf(signature)
    assert start >= 0 : "signature not found: ${signature}"
    int open = src.indexOf('{', start)
    int depth = 0
    for (int i = open; i < src.length(); i++) {
        if (src[i] == '{') depth++
        else if (src[i] == '}') { depth--; if (depth == 0) return src.substring(start, i + 1) }
    }
    throw new IllegalStateException("unbalanced braces for: ${signature}")
}

// The constants the methods use, as plain statics of a holder class.
String fields = src.readLines().findAll { it.startsWith('@Field static final') && (it =~ /CAP_KWH|EVENT_HOURS|_CENTS/) }
    .collect { it.replace('@Field static final', 'static final') }.join('\n')
String code = 'import java.time.LocalDate\nimport groovy.transform.CompileStatic\nclass PeakTarget {\n' + fields + '\n' +
    ['static LocalDate easterSunday(int year) {', 'static Set<String> noPeakDays(LocalDate day) {', 'static Map peakTarget(']
        .collect { '@CompileStatic\n' + extract(src, it) }.join('\n') + '\n}\nPeakTarget'
Class PT = new GroovyShell().evaluate(code)

int pass = 0, fail = 0
def check = { String name, boolean cond ->
    if (cond) { pass++; println "  [PASS] ${name}" } else { fail++; println "  [FAIL] ${name}" }
}
def near = { a, b -> a != null && Math.abs((a as BigDecimal) - (b as BigDecimal)) < 0.001 }

// Every day from Dec 15 to Jan 10 gets the same shape, so each reference hour holds five values
// and the trimmed mean drops the lowest and highest. Per day i (0-4 cycling): anchor hours
// 1-3 and 12-13 use anchorVals[i], event hours 6-9 and 16-19 use eventVals[i].
Map<String, Map<Integer, BigDecimal>> fill(List anchorVals, List eventVals) {
    Map out = [:]
    LocalDate d = LocalDate.of(2025, 12, 15)
    int i = 0
    while (d <= LocalDate.of(2026, 1, 10)) {
        Map<Integer, BigDecimal> h = [:]
        [1, 2, 3, 12, 13].each { h[it] = anchorVals[i % 5] as BigDecimal }
        [6, 7, 8, 9, 16, 17, 18, 19].each { h[it] = eventVals[i % 5] as BigDecimal }
        out[d.toString()] = h
        d = d.plusDays(1); i++
    }
    return out
}

println 'noPeakDays / easterSunday'
check('Easter 2026 is April 5', PT.easterSunday(2026) == LocalDate.of(2026, 4, 5))
Set np = PT.noPeakDays(LocalDate.of(2026, 1, 7))
check('holidays of winter 2025-26', np.containsAll(['2025-12-24', '2025-12-25', '2025-12-26', '2025-12-31', '2026-01-01', '2026-01-02']))
check('Good Friday and Easter Monday', np.containsAll(['2026-04-03', '2026-04-06']) && np.size() == 8)
check('a December day belongs to the winter starting that year', PT.noPeakDays(LocalDate.of(2025, 12, 10)).contains('2026-04-03'))

println 'reference days'
Map hours = fill([1, 2, 3, 4, 100], [5, 6, 7, 8, 50])
Map r = PT.peakTarget(hours, '2026-01-07', 6, ['2026-01-05'] as Set, 3.0, null)
check('weekday: holidays and event days skipped', r.refDays == ['2026-01-06', '2025-12-30', '2025-12-29', '2025-12-23', '2025-12-22'])
check('skipped days carry the reason',
      r.skipped.contains(['2026-01-05', 'event']) && r.skipped.contains(['2026-01-02', 'no peak hours']) && r.skipped.contains(['2025-12-24', 'no peak hours']))
Map we = PT.peakTarget(hours, '2026-01-10', 6, [] as Set, 3.0, null)
check('weekend: weekend days only', we.refDays == ['2026-01-04', '2026-01-03', '2025-12-28', '2025-12-27', '2025-12-21'])

println 'target, morning, cap would not cost'
// Without an event day the references are Jan 6, 5, Dec 30, 29 and 23: one of each of the five
// values, so the trimmed means are 3 and 7.
r = PT.peakTarget(hours, '2026-01-07', 6, [] as Set, 3.0, null)
check('anchor reference 3 h x 3 = 9', near(r.anchorRef, 9))
check('base 4 h x 7 = 28', near(r.base, 28))
check('proration 4/3', near(r.k, 4 / 3))
check('capped target 28 + 4/3 x 2 x 9 = 52', near(r.cappedTarget, 52))
check('plan: at least 3 x anchor reference', r.anchorPlan == 'at least' && near(r.anchorTarget, 27))
check('expected curtailment 52 - 3', near(r.expectedCut, 49))
check('each row drops one low and one high value', r.rows.every { it.low != null && it.high != null && it.low != it.high })
Map r2 = PT.peakTarget(hours, '2026-01-07', 6, [] as Set, 3.0, 45.0)
check('target 28 + 4/3 x (45 - 9) = 76', near(r2.target, 76))
check('cut deep: the cap leaves 52 - 3 = 49', near(r2.cut, 49) && r2.advice == 'cut' && r2.keepUse == null)

println 'target, morning, cap would cost'
Map low = fill([0, 1, 1, 1, 9], [5, 6, 7, 8, 50])
Map r3 = PT.peakTarget(low, '2026-01-07', 6, [] as Set, 3.0, null)
check('anchor reference 3, capped target 36', near(r3.anchorRef, 3) && near(r3.cappedTarget, 36))
check('plan: at most (40 + 3 - 28) x 3/4 + 3 = 14.25', r3.anchorPlan == 'at most' && near(r3.anchorTarget, 14.25))
check('expected curtailment 40', near(r3.expectedCut, 40))
Map r4 = PT.peakTarget(low, '2026-01-07', 6, [] as Set, 3.0, 30.0)
check('anchor over plan: target 28 + 4/3 x 27 = 64', near(r4.target, 64))
check('keep 24 kWh beats a capped 33', r4.advice == 'keep' && near(r4.keepUse, 24) && near(r4.cut, 33))
Map r5 = PT.peakTarget(low, '2026-01-07', 6, [] as Set, 3.0, 14.25)
check('anchor on plan: curtailment 40, no cap', near(r5.target, 43) && near(r5.cut, 40) && r5.advice == 'cut')

println 'target, evening'
Map pm = PT.peakTarget(hours, '2026-01-07', 16, [] as Set, 3.0, 20.0)
check('anchor hours 12 and 13', pm.anchorHours == [12, 13] && near(pm.anchorRef, 6))
check('proration 2', near(pm.k, 2))
check('target 28 + 2 x (20 - 6) = 56', near(pm.target, 56))

println 'missing values'
Map gap = fill([1, 2, 3, 4, 100], [5, 6, 7, 8, 50])
gap['2026-01-06'].remove(7)
Map r6 = PT.peakTarget(gap, '2026-01-07', 6, [] as Set, 3.0, null)
check('the hour without a value is listed', r6.missing == ['2026-01-06 7:00'])

println "\n=== ${pass}/${pass + fail} passed ==="
System.exit(fail == 0 ? 0 : 1)

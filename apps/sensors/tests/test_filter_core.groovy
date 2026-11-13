// Copyright (c) 2026 PJ
// SPDX-License-Identifier: MIT
//
// Unit tests for the Sensor Filter maths. Parses the block between the "Core (pure)" and
// "End core" markers of SensorFilterChild.groovy and runs it, so the tests bind to shipped code.
//   java -cp groovy-all-2.4.21.jar groovy.ui.GroovyMain <this file>

File here = new File(getClass().protectionDomain.codeSource.location.toURI()).parentFile
passed = 0; failed = 0
void check(String name, Object got, Object want) {
    if (got == want) { passed++; println "[PASS] ${name}" }
    else { failed++; println "[FAIL] ${name}: expected ${want}, got ${got}" }
}
String src = new File(here.parentFile, 'SensorFilterChild.groovy').text
int a = src.indexOf('// ── Core (pure)'), b = src.indexOf('// ── End core')
if (a < 0 || b < a) { println '[FAIL] core markers not found'; System.exit(2) }
core = new GroovyShell().parse(src.substring(a, b))

// Printed form and type, so a change of scale or of Integer to BigDecimal fails too.
String avg(List v) { Number r = core.calculateAverage(v); "${r} ${r?.getClass()?.simpleName}" }
String med(List v) { Number r = core.calculateMedian(v); "${r} ${r?.getClass()?.simpleName}" }

// ── moving average ──
check('average: integers stay Integer', avg([10, 12, 14]), '12 Integer')
check('average: integers round half up', avg([10, 11]), '11 Integer')
check('average: integers round to nearest', avg([10, 12, 13]), '12 Integer')
check('average: negative half rounds away from zero', avg([-1, -2]), '-2 Integer')
check('average: one value', avg([7]), '7 Integer')
check('average: decimals keep 2 places', avg([20.1, 20.2]), '20.15 BigDecimal')
check('average: decimals round half up to 2 places', avg([20.0, 20.0, 20.1]), '20.03 BigDecimal')
check('average: mixed Integer and BigDecimal', avg([20, 20.5]), '20.25 BigDecimal')
check('average: whole decimals print 2 places', avg([20.0, 22.0]), '21.00 BigDecimal')

// ── median ──
check('median: odd integers take the middle', med([14, 10, 12]), '12 Integer')
check('median: even integers round half up', med([10, 11]), '11 Integer')
check('median: even integers average the middle pair', med([10, 12, 30, 2]), '11 Integer')
check('median: one value', med([7]), '7 Integer')
check('median: even decimals', med([20.4, 20.1]), '20.25 BigDecimal')
check('median: odd decimals round to 2 places', med([20.789, 20.123, 20.456]), '20.46 BigDecimal')
check('median: mixed Integer and BigDecimal', med([21, 20, 20.5]), '20.50 BigDecimal')
check('median: an outlier does not move it', med([20, 21, 95]), '21 Integer')
check('median: empty window', core.calculateMedian([]), null)
check('median: window unchanged', { List l = [3, 1, 2]; core.calculateMedian(l); l }(), [3, 1, 2])

println "\n${passed} passed, ${failed} failed"
System.exit(failed ? 1 : 0)

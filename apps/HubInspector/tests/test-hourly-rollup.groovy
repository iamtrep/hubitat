// Copyright (c) 2025-2026 PJ
// SPDX-License-Identifier: MIT
// Extraction test: loads rollupDue() and rowsAfter() from the shipped HubInspector.groovy.
//  - rollupDue: an hour counts as written only once its file write landed, so an unwritten hour
//    is attempted again, once per hour.
//  - rowsAfter: rows already in the file are not appended twice.
// Run: groovy apps/HubInspector/tests/test-hourly-rollup.groovy      (no hub)
import groovy.transform.Field
@Field int pass = 0
@Field int fail = 0
File src = new File(new File(getClass().protectionDomain.codeSource.location.path).parentFile, '../HubInspector.groovy')
String text = src.text
StringBuilder body = new StringBuilder()
['static boolean rollupDue', 'static List rowsAfter'].each { String sig ->
    int from = text.indexOf(sig)
    assert from > 0 : sig + ' not found in ' + src
    int to = text.indexOf('\n}\n', from)
    body.append(text.substring(from, to + 3)).append('\n')
}
def C = new GroovyShell().parse("class C {\n${body}\n}\nreturn C").run()

void t(String name, Closure c) { try { c(); pass++; println "  ok   ${name}" } catch (Throwable e) { fail++; println "  FAIL ${name}\n         ${e.message}" } }

long H = 3_600_000L
long now = 100 * H + 5 * 60_000L      // five minutes into hour 100

t('first run is due') { assert C.rollupDue(null, null, now) }
t('last hour written: not due') { assert !C.rollupDue(99 * H, 99 * H + 60_000L, now) }
t('last hour unwritten, no attempt this hour: due') { assert C.rollupDue(98 * H, 99 * H + 60_000L, now) }
t('attempt already made this hour: not due again') { assert !C.rollupDue(98 * H, 100 * H + 60_000L, now) }
t('the failed hour is due again at the next hour') { assert C.rollupDue(98 * H, 100 * H + 60_000L, now + H) }
t('never written, attempt this hour: not due') { assert !C.rollupDue(null, 100 * H, now) }

List rows = [[98 * H, 1], [99 * H, 2]]
t('nothing written yet keeps every row') { assert C.rowsAfter(rows, null) == rows }
t('rows up to doneMs are dropped') { assert C.rowsAfter(rows, 98 * H) == [[99 * H, 2]] }
t('a late callback for written hours appends nothing') { assert C.rowsAfter(rows, 99 * H) == [] }
t('null rows') { assert C.rowsAfter(null, 5L) == [] }

println "\n${pass} passed, ${fail} failed"
System.exit(fail ? 1 : 0)

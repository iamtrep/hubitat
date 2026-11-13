// Copyright (c) 2025-2026 PJ
// SPDX-License-Identifier: MIT
// Extraction test: loads findEntryIndexByKey() and classifyFileRead() from the shipped
// HubInspector.groovy and runs them.
//  - findEntryIndexByKey: snapshots and checkpoints are addressed by timestampMs, never by list
//    position (both lists are prepended to, so a position goes stale). Unknown key -> -1.
//  - classifyFileRead: content / absent / failed for a File Manager read. Only content and absent
//    may be written over; a failed read must not be replaced by a list built from nothing.
// Run: groovy apps/HubInspector/tests/test-entry-key-and-fileread.groovy      (no hub)
import groovy.transform.Field
@Field int pass = 0
@Field int fail = 0
File src = new File(new File(getClass().protectionDomain.codeSource.location.path).parentFile, '../HubInspector.groovy')
String text = src.text
StringBuilder body = new StringBuilder()
['static int findEntryIndexByKey', 'static String classifyFileRead'].each { String sig ->
    int from = text.indexOf(sig)
    assert from > 0 : sig + ' not found in ' + src
    int to = text.indexOf('\n}\n', from)
    body.append(text.substring(from, to + 3)).append('\n')
}
def C = new GroovyShell().parse("class C {\n${body}\n}\nreturn C").run()

void t(String name, Closure c) { try { c(); pass++; println "  ok   ${name}" } catch (Throwable e) { fail++; println "  FAIL ${name}\n         ${e.message}" } }

// Newest first, as the app stores them.
List list = [[timestampMs: 3000L], [timestampMs: 2000L], [timestampMs: 1000L]]

t('key finds its entry') { assert C.findEntryIndexByKey(list, '2000') == 1 }
t('key as Long') { assert C.findEntryIndexByKey(list, 1000L) == 2 }
t('entry stored as Integer still matches') { assert C.findEntryIndexByKey([[timestampMs: 5]], '5') == 0 }
t('a prepended entry moves the position, not the match') {
    List grown = [[timestampMs: 4000L]] + list
    assert C.findEntryIndexByKey(list, '2000') == 1
    assert C.findEntryIndexByKey(grown, '2000') == 2
    assert grown[C.findEntryIndexByKey(grown, '2000')].timestampMs == 2000L
}
t('unknown key -> -1') { assert C.findEntryIndexByKey(list, '9999') == -1 }
t('a small number is not read as a position') { assert C.findEntryIndexByKey(list, '1') == -1 }
t('old-style index 0 -> -1') { assert C.findEntryIndexByKey(list, '0') == -1 }
t('null key -> -1') { assert C.findEntryIndexByKey(list, null) == -1 }
t('blank key -> -1') { assert C.findEntryIndexByKey(list, ' ') == -1 }
t('non-numeric key -> -1') { assert C.findEntryIndexByKey(list, 'startup') == -1 }
t('negative key -> -1') { assert C.findEntryIndexByKey(list, '-2000') == -1 }
t('decimal key -> -1') { assert C.findEntryIndexByKey(list, '2000.0') == -1 }
t('null list -> -1') { assert C.findEntryIndexByKey(null, '2000') == -1 }
t('entry without timestampMs is never matched') {
    assert C.findEntryIndexByKey([[timestamp: 'x'], [timestampMs: 7L]], '7') == 1
    assert C.findEntryIndexByKey([[timestamp: 'x']], 'null') == -1
}
t('non-map entry is skipped') { assert C.findEntryIndexByKey(['junk', [timestampMs: 7L]], '7') == 1 }

t('bytes -> content') { assert C.classifyFileRead(null, '[]'.bytes) == 'content' }
t('null return -> absent (2.5.3.102 form)') { assert C.classifyFileRead(null, null) == 'absent' }
t('empty bytes -> absent') { assert C.classifyFileRead(null, new byte[0]) == 'absent' }
t('NoSuchFileException -> absent') { assert C.classifyFileRead('java.nio.file.NoSuchFileException: x.json', null) == 'absent' }
t('AccessDeniedException -> failed') { assert C.classifyFileRead('java.nio.file.AccessDeniedException', null) == 'failed' }
t('other exception -> failed') { assert C.classifyFileRead('java.io.IOException', null) == 'failed' }
t('an exception wins over stray bytes') { assert C.classifyFileRead('java.io.IOException', 'x'.bytes) == 'failed' }

println "${pass} passed, ${fail} failed"
System.exit(fail ? 1 : 0)

// Copyright (c) 2025-2026 PJ
// SPDX-License-Identifier: MIT
// Extraction test: loads classifyFileRead() from the shipped source and checks the three outcomes
// of a File Manager read (content, absent, failed). Only content and absent may be written over.
// Run: groovy apps/tests/test-battery-change-logger-fileread.groovy      (no hub)
import groovy.transform.Field
@Field int pass = 0
@Field int fail = 0
File src = new File(new File(getClass().protectionDomain.codeSource.location.path).parentFile, '../BatteryChangeLogger.groovy')
String text = src.text
int from = text.indexOf('static String classifyFileRead')
assert from > 0 : 'classifyFileRead not found in ' + src
int to = text.indexOf('\n}\n', from)
def C = new GroovyShell().parse("class C {\n${text.substring(from, to + 3)}\n}\nreturn C").run()

void t(String name, Closure c) { try { c(); pass++; println "  ok   ${name}" } catch (Throwable e) { fail++; println "  FAIL ${name}\n         ${e.message}" } }

t('bytes -> content') { assert C.classifyFileRead(null, 'a,b\n1,2\n'.bytes) == 'content' }
t('null return -> absent (2.5.3.102 form)') { assert C.classifyFileRead(null, null) == 'absent' }
t('empty bytes -> absent') { assert C.classifyFileRead(null, new byte[0]) == 'absent' }
t('NoSuchFileException -> absent') { assert C.classifyFileRead('java.nio.file.NoSuchFileException', null) == 'absent' }
t('AccessDeniedException -> failed') { assert C.classifyFileRead('java.nio.file.AccessDeniedException', null) == 'failed' }
t('IllegalArgumentException -> failed') { assert C.classifyFileRead('java.lang.IllegalArgumentException', null) == 'failed' }
t('other exception -> failed') { assert C.classifyFileRead('java.io.IOException', null) == 'failed' }
t('an exception wins over stray bytes') { assert C.classifyFileRead('java.io.IOException', 'x'.bytes) == 'failed' }

println "${pass} passed, ${fail} failed"
System.exit(fail ? 1 : 0)

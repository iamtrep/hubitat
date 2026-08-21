// Copyright (c) 2025-2026 PJ
// SPDX-License-Identifier: MIT
// Mode 4 extraction test for source normalization/hashing in CodeManager.groovy (DESIGN.md §4.1, §2.2).
// Run: groovy apps/CodeManager/tests/test-source-hash.groovy
import groovy.transform.Field
@Field int pass = 0
@Field int fail = 0
@Field String src = new File(new File(getClass().protectionDomain.codeSource.location.path).parentFile, '../CodeManager.groovy').text

String extract(String name) {
    java.util.regex.Matcher m = src =~ /(?m)^(?:@\w+\s+)?static\s+[^\n(]*\b${name}\(/
    assert m.find() : "method not found: ${name}"
    int start = m.start()
    int open = src.indexOf('{', start); int depth = 0; int i = open
    for (; i < src.length(); i++) { char c = src.charAt(i); if (c == '{') depth++; else if (c == '}') { depth--; if (depth == 0) { i++; break } } }
    return src.substring(start, i)
}
String code = ['normalizeSource', 'sha256Hex', 'sourceHash', 'digest16', 'libraryImportUrl'].collect { extract(it) }.join('\n')
def H = new GroovyShell().parse("import groovy.transform.CompileStatic\nimport java.security.MessageDigest\nclass H {\n${code}\n}\nreturn H").run()

void t(String name, Closure c) { try { c(); pass++; println "  ok   ${name}" } catch (Throwable e) { fail++; println "  FAIL ${name}\n         ${e.message}" } }

t('CRLF and trailing whitespace normalize to the same text', { assert H.normalizeSource("a \r\nb\t\r\n") == "a\nb\n" })
t('BOM stripped', { assert H.normalizeSource("﻿x") == "x\n" })
t('exactly one trailing newline', { assert H.normalizeSource("x\n\n\n") == "x\n" && H.normalizeSource("x") == "x\n" })
t('empty', { assert H.normalizeSource("") == "" && H.normalizeSource(null) == "" })
t('sha256 known vector', { assert H.sha256Hex("abc") == "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad" })
t('sourceHash is normalization-invariant', { assert H.sourceHash("a\r\nb") == H.sourceHash("a\nb\n") })
t('digest16 length and stability', { assert H.digest16("url:https://x").length() == 16 && H.digest16("k") == H.digest16("k") })
t('library importUrl parsed from header', { assert H.libraryImportUrl("library(\n  name: 'l', importUrl: 'https://raw.githubusercontent.com/a/b/l.groovy',\n  version: '1')") == 'https://raw.githubusercontent.com/a/b/l.groovy' })
t('library importUrl absent', { assert H.libraryImportUrl("library(name: 'l')") == null })

println "\n${pass} passed, ${fail} failed, 0 warnings"
System.exit(fail ? 1 : 0)

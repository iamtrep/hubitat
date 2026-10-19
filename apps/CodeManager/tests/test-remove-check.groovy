// Copyright (c) 2025-2026 PJ
// SPDX-License-Identifier: MIT
// Mode 4 extraction test for the remove-time child check in CodeManager.groovy (DESIGN.md §3.3).
// Run: groovy apps/CodeManager/tests/test-remove-check.groovy
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
String code = ['sourceMayCreate', 'removeCheckCandidate', 'removeCheckVerdict'].collect { extract(it) }.join('\n')
def H = new GroovyShell().parse("import groovy.transform.CompileStatic\nclass H {\n${code}\n}\nreturn H").run()

void t(String name, Closure c) { try { c(); pass++; println "  ok   ${name}" } catch (Throwable e) { fail++; println "  FAIL ${name}\n         ${e.message}" } }

// sourceMayCreate
t('driver created by name in a child call', { assert H.sourceMayCreate('addChildDevice("ns", "Child Switch", dni)', 'driver', 'Child Switch') })
t('driver created through a quoted constant', { assert H.sourceMayCreate("@Field static final String T = 'Child Switch'\naddChildDevice('ns', T, dni)", 'driver', 'Child Switch') })
t('name quoted but no child call', { assert !H.sourceMayCreate('def n = "Child Switch"', 'driver', 'Child Switch') })
t('child call, name unquoted', { assert !H.sourceMayCreate('addChildDevice(ns, Child Switch, dni)', 'driver', 'Child Switch') })
t('child call, name only as a prefix', { assert !H.sourceMayCreate('addChildDevice("ns", "Child Switches", dni)', 'driver', 'Child Switch') })
t('an app needs addChildApp', { assert !H.sourceMayCreate('addChildDevice("Child App")', 'app', 'Child App') })
t('app created by addChildApp', { assert H.sourceMayCreate("addChildApp('ns', 'Child App', 'label')", 'app', 'Child App') })
t('empty source or name', { assert !H.sourceMayCreate('', 'driver', 'X') && !H.sourceMayCreate(null, 'driver', 'X') && !H.sourceMayCreate('addChildDevice("")', 'driver', '') })

// removeCheckCandidate: which items the check must read
t('valid facts, no child call: skipped', { assert !H.removeCheckCandidate([k: 'app', id: 1, ch: null], 'valid', 'driver') })
t('valid facts, child call: read', { assert H.removeCheckCandidate([k: 'app', id: 1, ch: true], 'valid', 'driver') })
t('provisional counts as usable', { assert !H.removeCheckCandidate([k: 'driver', id: 1], 'provisional', 'driver') })
t('stale or unread: read, large included', { assert H.removeCheckCandidate([k: 'driver', id: 1, sz: 900000], 'stale', 'driver') && H.removeCheckCandidate([k: 'library', id: 1], 'unread', 'driver') })
t('a driver cannot create an app', { assert !H.removeCheckCandidate([k: 'driver', id: 1, ch: true], 'valid', 'app') })
t('apps and libraries can create an app', { assert H.removeCheckCandidate([k: 'app', id: 1, ch: true], 'valid', 'app') && H.removeCheckCandidate([k: 'library', id: 1], 'unread', 'app') })
t('no hub id or no source kind: skipped', { assert !H.removeCheckCandidate([k: 'app', id: null], 'unread', 'driver') && !H.removeCheckCandidate([k: 'file', id: 3], 'unread', 'driver') })

// removeCheckVerdict
t('a creator wins over unread items', { assert H.removeCheckVerdict(1, 2) == 'may-be-created' })
t('nothing found, something unread', { assert H.removeCheckVerdict(0, 1) == 'incomplete' })
t('nothing found, all read', { assert H.removeCheckVerdict(0, 0) == 'no-creator' })

println "\n${pass} passed, ${fail} failed, 0 warnings"
System.exit(fail ? 1 : 0)

// Copyright (c) 2026 PJ
// SPDX-License-Identifier: MIT
// Mode 4 extraction test for CodeManager.groovy: which jobs updated() fails, and that importHpm() runs under the reconcile gate.
// Run: groovy apps/CodeManager/tests/test-reinit-jobs.groovy      (no hub; ~2 s)
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
String body(String signature) {
    int start = src.indexOf(signature)
    assert start >= 0 : signature
    int open = src.indexOf('{', start); int depth = 0; int i = open
    for (; i < src.length(); i++) { char c = src.charAt(i); if (c == '{') depth++; else if (c == '}') { depth--; if (depth == 0) { i++; break } } }
    return src.substring(start, i)
}
def H = new GroovyShell().parse("import groovy.transform.CompileStatic\nclass H {\n${extract('jobLostOnReinit')}\n}\nreturn H").run()

void t(String name, Closure c) { try { c(); pass++; println "  ok   ${name}" } catch (Throwable e) { fail++; println "  FAIL ${name}\n         ${e.message}" } }

t('a running library refresh is lost on re-init (its runIn is dropped by unschedule)') { assert H.jobLostOnReinit('libraries', 'running') }
t('the step-driven jobs are lost on re-init') {
    ['facts', 'drift', 'removecheck'].each { assert H.jobLostOnReinit(it, 'running') : it }
}
t('a finished job is left alone') {
    ['libraries', 'facts', 'drift', 'removecheck'].each { k -> ['done', 'failed'].each { s -> assert !H.jobLostOnReinit(k, s) : "${k} ${s}" } }
}
t('other kinds are left alone') { assert !H.jobLostOnReinit('other', 'running') && !H.jobLostOnReinit(null, 'running') }
t('updated() uses that rule and clears the library job id') {
    String u = body('void updated() {')
    assert u.contains('jobLostOnReinit(') && u.contains('LIB_JOB_ID = null')
}
// Structural: the race itself (a reconcile committing a stale state.pkgs over the import) needs a hub to reproduce.
t('importHpm takes the reconcile gate around the registry rewrite') {
    String w = body('Map importHpm(Map h = null) {')
    assert w.contains('gateFor("reconcile")') && w.contains('importHpmLocked(')
    assert body('private Map importHpmLocked(Map h) {').contains('state.pkgs = next')
}

println "${pass} passed, ${fail} failed"
if (fail) System.exit(1)

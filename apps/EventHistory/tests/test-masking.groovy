// Copyright (c) 2026 PJ
// SPDX-License-Identifier: MIT
// Mode 4 extraction test for EventHistory.groovy: its masking block must equal Log Monitor's, whose
// tests cover it, and the event-specific rules are tested here.
// Run: groovy apps/EventHistory/tests/test-masking.groovy      (no hub; ~2 s)
import groovy.transform.Field
@Field int pass = 0
@Field int fail = 0
File here = new File(getClass().protectionDomain.codeSource.location.path).parentFile
String src = new File(here, '../EventHistory.groovy').text
String other = new File(here, '../../LogMonitor/LogMonitor.groovy').text

String block(String s) {
    int from = s.indexOf('// Masking\n')
    int to = s.indexOf('// End of masking')
    assert from > 0 && to > from : 'masking section not found'
    return s.substring(from, to)
}
String method(String s, String start) {
    int from = s.indexOf(start)
    assert from > 0 : start
    int end = s.indexOf('\n}\n', from)
    return s.substring(from, end + 3)
}

void t(String name, Closure c) { try { c(); pass++; println "  ok   ${name}" } catch (Throwable e) { fail++; println "  FAIL ${name}: ${e.message}" } }

String code = block(src).replaceAll(/(?m)^@Field /, '') + method(src, 'static Map maskEvent(') + method(src, 'static boolean isExcluded(') +
        'static final int MAX_FIELD = 1000\n' + method(src, 'static String clip(') + method(src, 'static boolean hasDarkMode(')
def M = new GroovyShell().parse("import java.util.regex.Matcher\nimport java.util.regex.Pattern\nclass M {\n${code}\n}\nreturn M").run()

String B = '•' * 8
String KEY = 'k9Xq2LmP7vR4tY8wZ3nB'   // test value, not a real key

t('the masking block is Log Monitor\'s') { assert block(src) == block(other) }

t('a secret-named variable is masked in its value and description') {
    Map r = M.maskEvent('variable:apiKey', KEY, null, "Variable 'apiKey' set to ${KEY}".toString())
    assert r.v == "${B}Z3nB".toString()
    assert r.d == "Variable 'apiKey' set to ${B}Z3nB".toString()
}
t('a password-named value shows no end characters') {
    Map r = M.maskEvent('variable:gate_password', 'hunter2', null, 'Set to hunter2')
    assert r.v == B && r.d == "Set to ${B}".toString()
}
t('a plain variable stays readable') {
    Map r = M.maskEvent('variable:season', 'winter', null, "Variable 'season' set to winter")
    assert r.v == 'winter' && r.d == "Variable 'season' set to winter"
}
t('a token inside text in any field is masked') {
    String url = "http://example.com/x?access_token=${KEY}".toString()
    Map r = M.maskEvent('zz', url, null, "called ${url}".toString())
    assert !r.v.contains(KEY) && !r.d.contains(KEY)
}
t('a key=value in an event name is masked') {
    assert !(M.maskEvent("probe password=${KEY}".toString(), 'x', null, null).n as String).contains(KEY)
}
t('empty fields pass through') {
    Map r = M.maskEvent('sunriseSunsetUpdated', '', null, null)
    assert r.v == '' && r.u == null && r.d == null
}
t('masking before clipping leaves no part of a token') {
    String text = ('x' * 990) + " access_token=${KEY}"
    String stored = M.clip(M.maskEvent('zz', text, null, null).v as String)
    assert !stored.contains(KEY.substring(0, 6))
}
t('exclude list: exact, prefix, separators, none') {
    String list = 'sunset, variable:hydro_*\nzz custom'
    assert M.isExcluded('sunset', list) && M.isExcluded('variable:hydro_next', list) && M.isExcluded('zz custom', list)
    assert !M.isExcluded('sunsetTime', list) && !M.isExcluded('variable:season', list) && !M.isExcluded('sunset', null)
}

t('dark mode by firmware') {
    ['2.5.3.111': true, '2.5.3.100': true, '2.6.0.1': true, '3.0.0.1': true, '2.5.2.139': false, '2.4.9.1': false, '': false].each { v, want ->
        assert M.hasDarkMode(v) == want : v
    }
    assert !M.hasDarkMode(null)
}

println "${pass} passed, ${fail} failed"
if (fail) System.exit(1)

// Copyright (c) 2025-2026 PJ
// SPDX-License-Identifier: MIT
// Offline extraction test for VisiblAirManager: a rejected login stops polling instead of retrying.
// Loads handleLoginResponse from the shipped source and runs it on a stub.
// Run: groovy integrations/visiblair/tests/test-login-rejected.groovy      (no hub)
// SRC overrides the source file (used to run the test against an older copy).
import groovy.transform.Field
@Field int pass = 0
@Field int fail = 0
@Field String src = new File(System.getenv('SRC') ?: new File(new File(getClass().protectionDomain.codeSource.location.path).parentFile, '../VisiblAirManager.groovy').path).text

String extract(String name) {
    java.util.regex.Matcher m = src =~ /(?m)^(?:private\s+)?void\s+${name}\(/
    assert m.find() : "function not found: ${name}"
    int start = m.start(); int open = src.indexOf('{', start); int depth = 0; int i = open
    for (; i < src.length(); i++) { char c = src.charAt(i); if (c == '{') depth++; else if (c == '}') { depth--; if (depth == 0) { i++; break } } }
    return src.substring(start, i)
}
String harness = """
class H {
    Map state = [:]; List calls = []; Map API_TOKEN = [:]; Map app = [id: 7]
    String apiEmail = 'a@b.c'
    boolean hasApiPassword() { true }
    void logNet(String m) {}; void logDebug(String m) {}; void logError(String m) { calls << 'error' }
    void unschedule() { calls << 'unschedule' }
    void setAllChildrenHealth(String s, String r) { calls << 'offline' }
    void pollFailed(String r) { calls << 'pollFailed' }
    void runRequest(String t, Map d) { calls << 'runRequest' }
    void checkVersion() {}
${extract('handleLoginResponse')}
}
return H
"""
Class Hc = new GroovyShell().parse(harness).run()

class Resp {
    int status; boolean error = false; Object json = null
    int getStatus() { status }
    boolean hasError() { error || status >= 300 }
    String getErrorMessage() { 'err' }
    Object getJson() { json }
}
void t(String name, Closure c) { try { c(); pass++; println "  ok   ${name}" } catch (Throwable e) { fail++; println "  FAIL ${name}\n         ${e.message}" } }

[401, 403, 400].each { int code ->
    t("HTTP ${code} on a poll login stops polling and is not an outage", {
        def h = Hc.newInstance()
        h.handleLoginResponse(new Resp(status: code), [next: 'poll'])
        assert h.calls.contains('unschedule') && h.calls.contains('offline') && h.calls.contains('error')
        assert !h.calls.contains('pollFailed')
        assert h.state.loginRejected == true
    })
}
t('HTTP 500 stays an outage', {
    def h = Hc.newInstance()
    h.handleLoginResponse(new Resp(status: 500), [next: 'poll'])
    assert h.calls == ['pollFailed']
})
t('a timeout stays an outage', {
    def h = Hc.newInstance()
    h.handleLoginResponse(new Resp(status: 408, error: true), [next: 'poll'])
    assert h.calls == ['pollFailed']
})
t('a good login runs the request', {
    def h = Hc.newInstance()
    h.handleLoginResponse(new Resp(status: 200, json: [accessToken: 'x']), [next: 'poll'])
    assert h.calls == ['runRequest']
})

// pollSensors and initialize honour the flag
String poll = extract('pollSensors')
t('pollSensors returns before any request while the login is rejected', {
    assert poll.contains('state.loginRejected')
})
t('initialize clears the flag', { assert extract('initialize').contains('state.remove("loginRejected")') })

println "\n${pass} passed, ${fail} failed"
System.exit(fail ? 1 : 0)

// Copyright (c) 2025-2026 PJ
// SPDX-License-Identifier: MIT
// Extraction test: loads POLICIES, snapshot(), rearmOffHolds() and remainingHoldSec() from the shipped
// BathroomLightingShadow source. A policy that is on while its condition says off needs its off-hold timer
// re-armed after updated() or a reboot; one that is off, or still wanted on, does not.
// Run: groovy apps/tests/test-bathroom-lighting-shadow-rearm.groovy      (no hub)
import groovy.transform.Field
@Field int pass = 0
@Field int fail = 0
@Field String text = new File(new File(getClass().protectionDomain.codeSource.location.path).parentFile, '../BathroomLightingShadow.groovy').text
String grab(String head, String end = '\n}\n') {
    int from = text.indexOf(head)
    if (from < 0) return ''    // a helper the shipped source lacks: the tests that call it fail
    return text.substring(from, text.indexOf(end, from) + end.length())
}
String policies = grab('@Field static final List<Map> POLICIES', '\n]\n').replace('@Field ', '')
String body = [policies, grab('static Integer remainingHoldSec'), grab('private void rearmOffHolds'), grab('private Map snapshot'),
               grab('Boolean policyHueOnly'), grab('Boolean policyFp300Hybrid'), grab('Boolean policyFp300Mm'),
               grab('Boolean policyAnyMotion'), grab('Boolean policyComposite')].join('\n')
String stubs = '''
    Map state = [:]
    Map settings = [:]
    long clock = 5_000_000_000L
    List calls = []
    Map decisions = [:]
    def hueMotion = null
    def fp300Motion = null
    def fp300Presence = null
    def wallSwitch = null
    def doorContact = null
    def hfcActive = null
    long now() { clock }
    void logSched(String m) {}
    void runIn(int secs, String h) { calls << "${h}:${secs}".toString() }
    Map syncDecision(String key) { [decision: decisions[key] ?: "off"] }
    static Map dev(String v) { [currentValue: { String a -> v }] }
'''
Class C = new GroovyShell().parse("class C {\n${stubs}\n${body}\n}\nreturn C").run()
void t(String name, Closure c) { try { c(); pass++; println "  ok   ${name}" } catch (Throwable e) { fail++; println "  FAIL ${name}\n         ${e.message}" } }

t('remainingHoldSec: counts from the quiet time, never below 1 or above the hold') {
    assert C.remainingHoldSec(600, null, 1_000_000L) == 600
    assert C.remainingHoldSec(600, 1_000_000L - 200_000L, 1_000_000L) == 400
    assert C.remainingHoldSec(600, 1_000_000L - 900_000L, 1_000_000L) == 1
}
t('a policy that is on with all sensors quiet gets its off-check re-armed') {
    def a = C.newInstance()
    a.hueMotion = C.dev("inactive"); a.fp300Motion = C.dev("inactive"); a.fp300Presence = C.dev("inactive"); a.hfcActive = C.dev("off")
    a.decisions = [hueOnly: "on"]
    a.state.tQuietSince = a.clock - 100_000L
    a.rearmOffHolds()
    assert a.calls == ["offCheckHueOnly:200"] : a.calls.toString()
}
t('a policy that is off is left alone') {
    def a = C.newInstance()
    a.hueMotion = C.dev("inactive"); a.fp300Motion = C.dev("inactive"); a.fp300Presence = C.dev("inactive"); a.hfcActive = C.dev("off")
    a.rearmOffHolds()
    assert a.calls == []
}
t('a policy whose sensor is still active is left alone') {
    def a = C.newInstance()
    a.hueMotion = C.dev("active"); a.fp300Motion = C.dev("inactive"); a.fp300Presence = C.dev("inactive"); a.hfcActive = C.dev("off")
    a.decisions = [hueOnly: "on"]
    a.rearmOffHolds()
    assert !a.calls.contains("offCheckHueOnly:300") && !a.calls.any { it.startsWith("offCheckHueOnly") } : a.calls.toString()
}
println "${pass} passed, ${fail} failed"
System.exit(fail ? 1 : 0)

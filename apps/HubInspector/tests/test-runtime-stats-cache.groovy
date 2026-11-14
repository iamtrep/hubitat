// Copyright (c) 2025-2026 PJ
// SPDX-License-Identifier: MIT
// Extraction test: loads cachePeek(), cachePut() and fetchRuntimeStats() from the shipped
// HubInspector.groovy and runs them against a stubbed hubMapRequest and clock.
//  - /logs/json is read once per TTL window for tab loads
//  - fresh = true always reads, and refreshes the slot
//  - a failed or empty read is never cached
//  - a caller adding keys to the returned map does not change the cached copy
// Run: groovy apps/HubInspector/tests/test-runtime-stats-cache.groovy      (no hub)
import groovy.transform.Field
@Field int pass = 0
@Field int fail = 0
File src = new File(new File(getClass().protectionDomain.codeSource.location.path).parentFile, '../HubInspector.groovy')
String text = src.text
StringBuilder body = new StringBuilder()
['private Object cachePeek', 'private void cachePut', 'private Map fetchRuntimeStats'].each { String sig ->
    int from = text.indexOf(sig)
    assert from > 0 : sig + ' not found in ' + src
    int to = text.indexOf('\n}\n', from)
    body.append(text.substring(from, to + 3)).append('\n')
}
def m = (text =~ /RUNTIME_STATS_CACHE_TTL_MS\s*=\s*([0-9_]+)L/)
assert m.find() : 'RUNTIME_STATS_CACHE_TTL_MS not found'
String ttl = m.group(1)
String cls = """
import java.util.concurrent.ConcurrentHashMap
class C {
    static final long RUNTIME_STATS_CACHE_TTL_MS = ${ttl}L
    static final String RUNTIME_STATS_PATH = '/logs/json'
    final ConcurrentHashMap<String, Map> TTL_CACHE = new ConcurrentHashMap<>()
    long clock = 1000L
    int reads = 0
    Map nextWrap = [ok: true, data: [uptime: '1 day', appStats: [[id: 1]]], error: null]
    long now() { clock }
    Map hubMapRequest(String path, String name) { reads++; assert path == RUNTIME_STATS_PATH; return nextWrap }
${body}
}
return C
"""
def C = new GroovyShell().parse(cls).run()
long TTL = Long.parseLong(ttl.replace('_', ''))

void t(String name, Closure c) { try { c(); pass++; println "  ok   ${name}" } catch (Throwable e) { fail++; println "  FAIL ${name}\n         ${e.message}" } }

t('second read inside the TTL does not hit the hub') {
    def c = C.newInstance()
    assert c.fetchRuntimeStats().data.uptime == '1 day'
    c.clock += TTL - 1
    assert c.fetchRuntimeStats().data.uptime == '1 day'
    assert c.reads == 1
}
t('read after the TTL goes back to the hub') {
    def c = C.newInstance()
    c.fetchRuntimeStats(); c.clock += TTL; c.fetchRuntimeStats()
    assert c.reads == 2
}
t('fresh always reads and refreshes the slot') {
    def c = C.newInstance()
    c.fetchRuntimeStats()
    c.nextWrap = [ok: true, data: [uptime: '2 days'], error: null]
    assert c.fetchRuntimeStats(true).data.uptime == '2 days'
    assert c.reads == 2
    assert c.fetchRuntimeStats().data.uptime == '2 days'
    assert c.reads == 2
}
t('a failed read is not cached') {
    def c = C.newInstance()
    c.nextWrap = [ok: false, data: [:], error: 'timeout']
    assert !c.fetchRuntimeStats().ok
    c.nextWrap = [ok: true, data: [uptime: '3 days'], error: null]
    assert c.fetchRuntimeStats().data.uptime == '3 days'
    assert c.reads == 2
}
t('an empty payload is not cached') {
    def c = C.newInstance()
    c.nextWrap = [ok: true, data: [:], error: null]
    c.fetchRuntimeStats(); c.fetchRuntimeStats()
    assert c.reads == 2
}
t('keys a caller adds do not reach the cache') {
    def c = C.newInstance()
    Map first = c.fetchRuntimeStats().data
    first.radioStats = [x: 1]
    assert !c.fetchRuntimeStats().data.containsKey('radioStats')
    Map second = c.fetchRuntimeStats().data
    second.temperature = 40
    assert !c.fetchRuntimeStats().data.containsKey('temperature')
    assert c.reads == 1
}

t('a longer max age accepts a copy the default TTL would refuse') {
    def c = C.newInstance()
    c.fetchRuntimeStats(); c.clock += TTL * 10
    assert c.fetchRuntimeStats(false, TTL * 20).data.uptime == '1 day'
    assert c.reads == 1
    c.fetchRuntimeStats()
    assert c.reads == 2
}
t('a copy older than the max age is read again') {
    def c = C.newInstance()
    c.fetchRuntimeStats(); c.clock += TTL * 20
    c.fetchRuntimeStats(false, TTL * 20)
    assert c.reads == 2
}
t('the scheduled checkpoint shares its read and the scheduled snapshot takes it') {
    assert text.contains("cachePut('runtimeStats', new LinkedHashMap((Map) resp.json))")
    assert text.contains('createSnapshot(SNAPSHOT_STATS_MAX_AGE_MS)')
    assert text.contains('runIn(jitterDelaySec(), "createScheduledSnapshot")')
}

println "\n${pass} passed, ${fail} failed"
System.exit(fail ? 1 : 0)

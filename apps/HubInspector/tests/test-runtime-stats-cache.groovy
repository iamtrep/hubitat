// Copyright (c) 2025-2026 PJ
// SPDX-License-Identifier: MIT
// Extraction test: loads cachePeek(), cachePut() and fetchRuntimeStats() from the shipped
// HubInspector.groovy and runs them against a stubbed hubMapRequest and clock.
//  - /logs/json is read once per TTL window for tab loads
//  - fresh = true always reads, and refreshes the slot
//  - a failed or empty read is never cached
//  - a caller adding keys to the returned map does not change the cached copy
//  - the Performance tab's peek never reads on the request thread: it returns the slot at any age and
//    starts one background read (single flight, guard lapses) when the slot is empty or stale
// Run: groovy apps/HubInspector/tests/test-runtime-stats-cache.groovy      (no hub)
import groovy.transform.Field
@Field int pass = 0
@Field int fail = 0
File src = new File(new File(getClass().protectionDomain.codeSource.location.path).parentFile, '../HubInspector.groovy')
String text = src.text
StringBuilder body = new StringBuilder()
['private Object cachePeek', 'private void cachePut', 'private Map fetchRuntimeStats',
 'private Map peekRuntimeStatsOrWarm', 'private void startRuntimeStatsWarmup', 'void onRuntimeStatsWarm'].each { String sig ->
    int from = text.indexOf(sig)
    assert from > 0 : sig + ' not found in ' + src
    int to = text.indexOf('\n}\n', from)
    body.append(text.substring(from, to + 3)).append('\n')
}
def m = (text =~ /RUNTIME_STATS_CACHE_TTL_MS\s*=\s*([0-9_]+)L/)
assert m.find() : 'RUNTIME_STATS_CACHE_TTL_MS not found'
String ttl = m.group(1)
def g = (text =~ /RUNTIME_WARMUP_GUARD_MS\s*=\s*([0-9_]+)L/)
assert g.find() : 'RUNTIME_WARMUP_GUARD_MS not found'
String guard = g.group(1)
String cls = """
import java.util.concurrent.ConcurrentHashMap
class C {
    static final long RUNTIME_STATS_CACHE_TTL_MS = ${ttl}L
    static final String RUNTIME_STATS_PATH = '/logs/json'
    static final long RUNTIME_WARMUP_GUARD_MS = ${guard}L
    static final int RUNTIME_STATS_TIMEOUT_S = 60
    static final String HUB_BASE = 'http://127.0.0.1:8080'
    final ConcurrentHashMap<String, Long> RUNTIME_WARMUP = new ConcurrentHashMap<>()
    List asyncCalls = []
    void asynchttpGet(String cb, Map params, Map data) { asyncCalls << [cb: cb, params: params, data: data] }
    void logWarn(String s) {}
    void logNet(String s) {}
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
class FakeResp {
    boolean err = false; int status = 200; Object json
    boolean hasError() { err }
    String getErrorMessage() { 'timeout' }
}
t('peek on an empty slot returns null, reads nothing synchronously, starts one async read') {
    def c = C.newInstance()
    assert c.peekRuntimeStatsOrWarm() == null
    assert c.reads == 0
    assert c.asyncCalls.size() == 1
    assert c.asyncCalls[0].cb == 'onRuntimeStatsWarm' && c.asyncCalls[0].params.path == '/logs/json'
    c.peekRuntimeStatsOrWarm()
    assert c.asyncCalls.size() == 1 : 'second peek while in flight must not start another read'
}
t('the callback fills the slot and frees the guard; peek then returns it with its age') {
    def c = C.newInstance()
    c.peekRuntimeStatsOrWarm()
    c.clock += 5000
    c.onRuntimeStatsWarm(new FakeResp(json: [uptime: '2 h', appStats: [[id: 7]]]), c.asyncCalls[0].data)
    assert c.RUNTIME_WARMUP.isEmpty()
    c.clock += 2000
    Map hit = c.peekRuntimeStatsOrWarm()
    assert hit.data.uptime == '2 h' && hit.ageMs == 2000L
    assert c.asyncCalls.size() == 1 : 'a fresh slot starts no read'
    hit.data.extra = 1
    assert !c.peekRuntimeStatsOrWarm().data.containsKey('extra')
}
t('a stale slot is still returned, and starts a background refresh') {
    def c = C.newInstance()
    c.fetchRuntimeStats(); c.clock += TTL * 100
    Map hit = c.peekRuntimeStatsOrWarm()
    assert hit.data.uptime == '1 day' && hit.ageMs == TTL * 100
    assert c.reads == 1 && c.asyncCalls.size() == 1
}
t('a failed read caches nothing and frees the guard') {
    def c = C.newInstance()
    c.peekRuntimeStatsOrWarm()
    c.onRuntimeStatsWarm(new FakeResp(err: true), c.asyncCalls[0].data)
    assert c.TTL_CACHE.isEmpty() && c.RUNTIME_WARMUP.isEmpty()
    c.peekRuntimeStatsOrWarm()
    assert c.asyncCalls.size() == 2
}
t('a lost callback blocks new reads only until the guard lapses; its late arrival keeps the newer mark') {
    def c = C.newInstance()
    c.peekRuntimeStatsOrWarm()
    c.clock += Long.parseLong(guard.replace("_", "")) - 1; c.peekRuntimeStatsOrWarm()
    assert c.asyncCalls.size() == 1
    c.clock += 1; c.peekRuntimeStatsOrWarm()
    assert c.asyncCalls.size() == 2
    c.onRuntimeStatsWarm(new FakeResp(err: true), c.asyncCalls[0].data)
    assert c.RUNTIME_WARMUP['startedMs'] == c.clock
}

t('the scheduled checkpoint shares its read and the scheduled snapshot takes it') {
    assert text.contains("cachePut('runtimeStats', new LinkedHashMap((Map) resp.json))")
    assert text.contains('createSnapshot(SNAPSHOT_STATS_MAX_AGE_MS)')
    assert text.contains('runIn(jitterDelaySec(), "createScheduledSnapshot")')
}

println "\n${pass} passed, ${fail} failed"
System.exit(fail ? 1 : 0)

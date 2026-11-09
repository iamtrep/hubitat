// Copyright (c) 2025-2026 PJ
// SPDX-License-Identifier: MIT
// Mode 4 extraction test for the pure helpers of LogMonitorBridge.groovy (pre-filter, capped queue,
// batch take, backlog timing) and LogMonitor.groovy's wantedPairs().
// Run: groovy apps/LogMonitor/tests/test-bridge-queue.groovy      (no hub; ~2 s)
import groovy.transform.Field
@Field int pass = 0
@Field int fail = 0
File dir = new File(new File(getClass().protectionDomain.codeSource.location.path).parentFile, '..')
String bsrc = new File(dir, 'LogMonitorBridge.groovy').text
String psrc = new File(dir, 'LogMonitor.groovy').text

int from = bsrc.indexOf('// Pure helpers')
int to = bsrc.indexOf('// End of pure helpers')
assert from > 0 && to > from : 'pure helpers section not found'
def B = new GroovyShell().parse("import java.util.concurrent.ConcurrentLinkedQueue\nimport java.util.concurrent.atomic.AtomicInteger\nclass B {\n${bsrc.substring(from, to)}\n}\nreturn B").run()

int pf = psrc.indexOf('static List<String> wantedPairs')
int pt = psrc.indexOf('// Tells each bridge its wanted set')
assert pf > 0 && pt > pf : 'wantedPairs not found'
def P = new GroovyShell().parse("class P {\n${psrc.substring(pf, pt)}\n}\nreturn P").run()

void t(String name, Closure c) { try { c(); pass++; println "  ok   ${name}" } catch (Throwable e) { fail++; println "  FAIL ${name}\n         ${e.message}" } }

String frame(String type, String level, String msg = 'hello') {
    '{"name":"Dev","msg":"' + msg + '","id":12,"time":"2026-11-09 10:00:00.000","type":"' + type + '","level":"' + level + '"}'
}

// --- frameField / wantedFrame
t('frameField reads type and level') {
    assert B.frameField(frame('dev', 'warn'), 'type') == 'dev'
    assert B.frameField(frame('dev', 'warn'), 'level') == 'warn'
    assert B.frameField('{"x":1}', 'level') == null
}
t('a quoted "level":"error" inside msg does not fool the field read') {
    String f = frame('app', 'info', 'said \\"level\\":\\"error\\" &quot;level&quot;:&quot;error&quot;')
    assert B.frameField(f, 'level') == 'info'
}
t('wanted set keeps wanted pairs, drops the rest') {
    Set w = ['dev/error', 'app/warn'] as Set
    assert B.wantedFrame(frame('dev', 'error'), w)
    assert B.wantedFrame(frame('app', 'warn'), w)
    assert !B.wantedFrame(frame('dev', 'warn'), w)
    assert !B.wantedFrame(frame('app', 'error'), w)
    assert !B.wantedFrame(frame('sys', 'error'), w)
}
t('empty wanted set drops every known type') { assert !B.wantedFrame(frame('dev', 'error'), [] as Set) }
t('no wanted set (not fetched yet) keeps everything') { assert B.wantedFrame(frame('dev', 'trace'), null) }
t('frame without type or level, or of another type, is kept for drain()') {
    Set w = ['dev/error'] as Set
    assert B.wantedFrame('{"msg":"x"}', w)
    assert B.wantedFrame('not json', w)
    assert B.wantedFrame(frame('hub', 'info'), w)
}

// --- capped queue
t('offerCapped accepts up to the cap, then refuses and leaves the queue alone') {
    def q = new java.util.concurrent.ConcurrentLinkedQueue<String>()
    def d = new java.util.concurrent.atomic.AtomicInteger()
    int dropped = 0
    (1..2500).each { if (!B.offerCapped(q, d, 2000, "f${it}")) dropped++ }
    assert d.get() == 2000 && q.size() == 2000 && dropped == 500
    assert q.peek() == 'f1'          // the newest are dropped, the oldest kept
}
t('takeBatch takes up to n oldest first and keeps the depth counter right') {
    def q = new java.util.concurrent.ConcurrentLinkedQueue<String>()
    def d = new java.util.concurrent.atomic.AtomicInteger()
    (1..450).each { B.offerCapped(q, d, 2000, "f${it}") }
    List b = B.takeBatch(q, d, 200)
    assert b.size() == 200 && b[0] == 'f1' && b[199] == 'f200' && d.get() == 250
    assert B.takeBatch(q, d, 200).size() == 200
    List last = B.takeBatch(q, d, 200)
    assert last.size() == 50 && d.get() == 0 && q.isEmpty()
    assert B.takeBatch(q, d, 200).isEmpty()
}
t('a full queue accepts again after a drain') {
    def q = new java.util.concurrent.ConcurrentLinkedQueue<String>()
    def d = new java.util.concurrent.atomic.AtomicInteger()
    (1..2000).each { B.offerCapped(q, d, 2000, "f${it}") }
    assert !B.offerCapped(q, d, 2000, 'x')
    B.takeBatch(q, d, 200)
    assert B.offerCapped(q, d, 2000, 'x') && d.get() == 1801
}
t('sustained 500/s in, 200/s out: depth stops at the cap and drops are counted') {
    def q = new java.util.concurrent.ConcurrentLinkedQueue<String>()
    def d = new java.util.concurrent.atomic.AtomicInteger()
    int dropped = 0, taken = 0
    (1..10).each { sec ->
        (1..500).each { if (!B.offerCapped(q, d, 2000, 'f')) dropped++ }
        taken += B.takeBatch(q, d, 200).size()
    }
    assert taken == 2000 && d.get() <= 2000 && (5000 - dropped) == taken + d.get() && dropped > 0
}

// --- backlog timer
t('backlogExceeded') {
    assert !B.backlogExceeded(null, 100000L, 30000L)
    assert !B.backlogExceeded(70000L, 100000L, 30000L)   // exactly 30 s is not over
    assert B.backlogExceeded(69999L, 100000L, 30000L)
}

// --- parent wanted set
t('wantedPairs: union over filters for this bridge or all bridges') {
    List fs = [
        [bridgeDni: '', monitorDev: true, monitorApp: false, monitorSys: false, levels: ['error']],
        [bridgeDni: 'b1', monitorDev: false, monitorApp: true, monitorSys: true, levels: ['warn', 'error']],
        [bridgeDni: 'b2', monitorDev: true, monitorApp: true, monitorSys: true, levels: ['info']],
    ]
    assert P.wantedPairs(fs, 'b1') as Set == ['dev/error', 'app/warn', 'app/error', 'sys/warn', 'sys/error'] as Set
    assert P.wantedPairs(fs, 'b3') as Set == ['dev/error'] as Set
    assert P.wantedPairs([], 'b1') == []
}

println "\n${pass} passed, ${fail} failed"
System.exit(fail ? 1 : 0)

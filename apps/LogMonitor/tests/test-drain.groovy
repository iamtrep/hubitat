// Copyright (c) 2025-2026 PJ
// SPDX-License-Identifier: MIT
// Mode 4 extraction test for LogMonitor.groovy's single-drainer pattern (drain), dedupe
// (isDuplicate) and per-minute rate limit (isRateLimited). The shipped functions and the static
// maps they use are cut out of the source and run in a small host class; drainPass, now() and the
// logger are stubs, so the test checks the guard and the re-check, not the outputs.
// Run: groovy apps/LogMonitor/tests/test-drain.groovy      (no hub; ~3 s)
import groovy.transform.Field
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
@Field int pass = 0
@Field int fail = 0
@Field File dir = new File(new File(getClass().protectionDomain.codeSource.location.path).parentFile, '..')
@Field String src = new File(dir, 'LogMonitor.groovy').text

String fn(String startMarker, String endMarker) {
    int a = src.indexOf(startMarker)
    assert a >= 0 : "${startMarker} not found"
    int b = src.indexOf(endMarker, a)
    assert b > a : "end of ${startMarker} not found"
    return src.substring(a, b)
}
// Static maps and constants, exactly as shipped (minus the @Field annotation)
String fields = src.readLines().findAll { it =~ /^@Field static final .* (MAX_DEDUPE_HISTORY|RECENT_EVENTS|EVENTS_THIS_MINUTE|WORK|DRAINING)\b/ }
    .collect { it.replace('@Field ', '') }.join('\n')
assert fields.readLines().size() == 5 : 'expected five shipped @Field lines'
String drainSrc = fn('private void drain()', '\n}\n') + '\n}\n'
String filterKeySrc = fn('private String filterKey', '\n}\n') + '\n}\n'
String dedupeSrc = fn('private boolean isDuplicate', '// ====')

String host = """
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
class H {
${fields}
    Map app = [id: 7]
    long clock = 1000000L
    Closure pass = { ConcurrentLinkedQueue<Map> q -> }
    long now() { clock }
    void logDebug(String m) {}
    void drainPass(ConcurrentLinkedQueue<Map> q) { pass.call(q) }
${filterKeySrc}
${drainSrc}
${dedupeSrc}
}
return H
"""
Class H = new GroovyShell().parse(host).run()
def newHost = {
    H.RECENT_EVENTS.clear(); H.EVENTS_THIS_MINUTE.clear(); H.WORK.clear(); H.DRAINING.clear()
    H.newInstance()
}

void t(String name, Closure c) { try { c(); pass++; println "  ok   ${name}" } catch (Throwable e) { fail++; println "  FAIL ${name}\n         ${e.message}" } }

Map entry(String msg, String name = 'Dev') { [type: 'dev', level: 'error', name: name, msg: msg] }

// --- isDuplicate
t('same entry inside the window is a duplicate, after the window it is not') {
    def h = newHost(); Map f = [id: 1, dedupeWindow: 5]
    assert !h.isDuplicate(entry('boom'), f)
    h.clock += 4000
    assert h.isDuplicate(entry('boom'), f)
    h.clock += 2000          // 6 s after the first sighting
    assert !h.isDuplicate(entry('boom'), f)
}
t('a different message or device name is not a duplicate') {
    def h = newHost(); Map f = [id: 1, dedupeWindow: 5]
    assert !h.isDuplicate(entry('boom'), f)
    assert !h.isDuplicate(entry('bang'), f)
    assert !h.isDuplicate(entry('boom', 'Other'), f)
}
t('window 0 turns dedupe off; a missing window takes the 5 s default') {
    def h = newHost()
    assert !h.isDuplicate(entry('x'), [id: 1, dedupeWindow: 0])
    assert !h.isDuplicate(entry('x'), [id: 1, dedupeWindow: 0])
    assert !h.isDuplicate(entry('y'), [id: 2])
    assert h.isDuplicate(entry('y'), [id: 2])
    h.clock += 5000
    assert !h.isDuplicate(entry('y'), [id: 2])
}
t('each filter keeps its own history') {
    def h = newHost()
    assert !h.isDuplicate(entry('boom'), [id: 1, dedupeWindow: 5])
    assert !h.isDuplicate(entry('boom'), [id: 2, dedupeWindow: 5])
    assert h.isDuplicate(entry('boom'), [id: 1, dedupeWindow: 5])
}
t('history is capped at MAX_DEDUPE_HISTORY, the oldest entries go first') {
    def h = newHost(); Map f = [id: 1, dedupeWindow: 3600]
    int cap = H.MAX_DEDUPE_HISTORY
    for (int i = 0; i < cap + 20; i++) { h.clock += 10; h.isDuplicate(entry("m${i}"), f) }
    assert H.RECENT_EVENTS['7:1'].size() <= cap
    assert !h.isDuplicate(entry('m0'), f)               // evicted, seen as new
    assert h.isDuplicate(entry("m${cap + 19}"), f)      // newest kept
}

// --- isRateLimited
t('the limit-th event passes, the next is limited') {
    def h = newHost(); Map f = [id: 1, maxPerMinute: 3]
    assert !h.isRateLimited(f) && !h.isRateLimited(f) && !h.isRateLimited(f)
    assert h.isRateLimited(f)
    assert h.isRateLimited(f)
}
t('maxPerMinute 0 means 30') {
    def h = newHost(); Map f = [id: 1, maxPerMinute: 0]
    30.times { assert !h.isRateLimited(f) }
    assert h.isRateLimited(f)
}
t('counters are per filter') {
    def h = newHost()
    assert !h.isRateLimited([id: 1, maxPerMinute: 1])
    assert h.isRateLimited([id: 1, maxPerMinute: 1])
    assert !h.isRateLimited([id: 2, maxPerMinute: 1])
}

// --- drain
t('threads that find a drainer leave; never two drainers; nothing left after the last caller returns') {
    def h = newHost()
    AtomicInteger inPass = new AtomicInteger(), maxIn = new AtomicInteger(), done = new AtomicInteger()
    h.pass = { q ->
        int n = inPass.incrementAndGet()
        maxIn.accumulateAndGet(n, { a, b -> Math.max(a, b) })
        Map item
        while ((item = q.poll()) != null) { Thread.sleep(1); done.incrementAndGet() }
        inPass.decrementAndGet()
    }
    int threads = 16, each = 25
    CountDownLatch go = new CountDownLatch(1)
    def q = H.WORK.computeIfAbsent('7', { k -> new java.util.concurrent.ConcurrentLinkedQueue<Map>() } as java.util.function.Function)
    List<Thread> ts = (1..threads).collect { int tn ->
        Thread.start {
            go.await()
            each.times { i -> q.add([kind: 'match', n: "${tn}-${i}"]); h.drain() }
        }
    }
    go.countDown()
    ts*.join()
    assert maxIn.get() == 1 : "drainers at once: ${maxIn.get()}"
    assert q.isEmpty() : "left in queue: ${q.size()}"
    assert done.get() == threads * each : "processed ${done.get()} of ${threads * each}"
    assert H.DRAINING.isEmpty()
}
t('an item added after the drainer\'s last poll is picked up by the drainer (re-check after release)') {
    def h = newHost()
    List seen = []
    int calls = 0
    def q = H.WORK.computeIfAbsent('7', { k -> new java.util.concurrent.ConcurrentLinkedQueue<Map>() } as java.util.function.Function)
    h.pass = { qq ->
        Map item
        while ((item = qq.poll()) != null) seen << item.n
        if (calls++ == 0) {
            // another thread queues work and calls drain() while this one still holds the drain
            Thread.start { qq.add([n: 'late']); h.drain() }.join()
        }
    }
    q.add([n: 'first'])
    h.drain()
    assert seen == ['first', 'late'] : "seen ${seen}"
    assert q.isEmpty()
}
t('drain with no queue or an empty queue does nothing and takes no lock') {
    def h = newHost()
    boolean ran = false
    h.pass = { qq -> ran = true }
    h.drain()
    H.WORK.put('7', new java.util.concurrent.ConcurrentLinkedQueue<Map>())
    h.drain()
    assert !ran
    assert H.DRAINING.isEmpty()
}
t('the drain lock is released when a pass throws') {
    def h = newHost()
    def q = H.WORK.computeIfAbsent('7', { k -> new java.util.concurrent.ConcurrentLinkedQueue<Map>() } as java.util.function.Function)
    q.add([n: 1])
    h.pass = { qq -> qq.poll(); throw new IllegalStateException('boom') }
    try { h.drain() } catch (IllegalStateException e) {}
    assert H.DRAINING.isEmpty()
}

println "\n${pass} passed, ${fail} failed"
System.exit(fail ? 1 : 0)

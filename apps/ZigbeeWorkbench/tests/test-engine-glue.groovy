// Copyright (c) 2025-2026 PJ
// SPDX-License-Identifier: MIT
//
// Extraction unit test for the Zigbee Workbench request-engine glue: the stateful methods that
// schedule the timeout sweep, expire overdue requests, fail pending requests, and flush unsent
// frames to the radio child. Each method is brace-extracted by name from the shipped
// ZigbeeWorkbench.groovy, with the codec and engine regions, and run against a stub hub (state,
// now(), runInMillis, a fake radio child). No hub needed.
//
// Run: groovy apps/ZigbeeWorkbench/tests/test-engine-glue.groovy

File scriptFile = new File(getClass().protectionDomain.codeSource.location.toURI())
File groovySrc = new File(scriptFile.parentFile.parentFile, 'ZigbeeWorkbench.groovy')
assert groovySrc.exists() : "source not found: ${groovySrc}"
String src = groovySrc.text

String region(String text, String name) {
    int b = text.indexOf("// ==== ${name} begin ====")
    int e = text.indexOf("// ==== ${name} end ====")
    assert b >= 0 && e > b : "signature not found: ${name} region markers"
    return text.substring(b, e)
}

// The source of one method, from its signature line to the matching closing brace.
String method(String text, String name) {
    def m = (text =~ /(?m)^(?:private |static |void )*[\w<>]+ ${name}\(/)
    assert m.find() : "signature not found: ${name}"
    int start = m.start()
    int open = text.indexOf('{', start)
    int depth = 0
    for (int i = open; i < text.length(); i++) {
        char c = text.charAt(i)
        if (c == (char) '{') depth++
        else if (c == (char) '}') { depth--; if (depth == 0) return text.substring(start, i + 1) }
    }
    assert false : "unbalanced braces: ${name}"
    return ''
}

List<String> names = ['scheduleSweep', 'sweepPending', 'expireOverdue', 'failAllPending', 'flushRadio',
                      'radioDown', 'putResult', 'interviewSweep']
// The app-level constants the glue uses, taken from their @Field lines in the shipped source.
String consts = ['BACKSTOP_GRACE_MS', 'REPLY_TIMEOUT_MS', 'RADIO_DRIVER'].collect { String c ->
    def m = (src =~ /(?m)^@Field static final .* ${c} = .*$/)
    assert m.find() : "signature not found: ${c}"
    m.group()
}.join('\n')
String glue = names.collect { method(src, it) }.join('\n')

String harness = """
import groovy.transform.CompileStatic
import groovy.transform.Field
${region(src, 'Codec')}
${region(src, 'Engine')}
@Field static final String RADIO_DNI = 'radio'
${consts}
@Field Map state = [:]
@Field long clock = 1000000L
@Field List scheduled = []
@Field List logs = []
@Field Object radio = null
long now() { return clock }
void runInMillis(long ms, String fn) { scheduled << [ms: ms, fn: fn] }
void checkVersion() { }
void logWarn(String s) { logs << s }
void logDebug(String s) { logs << s }
void logError(String s) { logs << s }
String targetName(String ieee) { return ieee }
Object getChildDevice(String dni) { return radio }
void interviewAdvance(String ieee, Map iv, Map entry) { }
void interviewFinish(String ieee, Map iv, String why) { }
${glue}
this
"""
def app = new GroovyShell().evaluate(harness)

class FakeRadio {
    String status = 'connected'
    List watch = []
    List sent = []
    int sessions = 0
    void setWatch(List w) { watch = w }
    String socketStatus() { return status }
    Object currentValue(String a, boolean b) { return status }
    void ensureSession() { sessions++ }
    void sendFrames(List f) { sent.addAll(f) }
}

int passed = 0
int failed = 0
def check = { String name, Closure c ->
    boolean ok
    String why = ''
    try { ok = c() } catch (Throwable t) { ok = false; why = " (${t.class.simpleName}: ${t.message})" }
    if (ok) { passed++; println "[PASS] ${name}" } else { failed++; println "[FAIL] ${name}${why}" }
}
def reset = {
    app.state = [:]
    app.clock = 1000000L
    app.scheduled = []
    app.logs = []
    app.radio = new FakeRadio()
}
Closure req = { String addr, Map extra = [:] ->
    [ieee: addr + '0000000000000000'.substring(addr.length()), addr: addr, kind: 'k' + addr, cluster: '0006',
     frame: 'F' + addr, queuedAt: 1L] + extra
}

// ---- scheduleSweep ----
reset()
app.scheduleSweep()
check('no deadline: sweep 5 s ahead, sweepAt recorded') {
    app.scheduled.size() == 1 && app.scheduled[0].fn == 'sweepPending' && app.scheduled[0].ms == 5000L && app.state.sweepAt == 1005000L
}
app.scheduleSweep()
check('a sweep already scheduled is left alone') { app.scheduled.size() == 1 }
app.clock += 6000L
app.scheduleSweep()
check('a sweep due more than a second ago is scheduled again') { app.scheduled.size() == 2 }
reset()
app.state.pending = ['A:6:01': req('A', [sent: true, deadline: 1010000L]), 'B:6:02': req('B', [sent: true, deadline: 1004000L])]
app.scheduleSweep()
check('sweep targets the earliest deadline plus 200 ms') { app.scheduled[0].ms == 4200L }
reset()
app.state.pending = ['A:6:01': req('A', [sent: true, deadline: 1000100L])]
app.scheduleSweep()
check('delay never drops below 500 ms') { app.scheduled[0].ms == 500L }

// ---- expireOverdue / sweepPending ----
reset()
app.state.pending = ['A:6:01': req('A', [sent: true, deadline: 999000L]), 'B:6:02': req('B', [sent: true, deadline: 1005000L]),
                     'C:6:03': req('C')]
app.expireOverdue(1000000L)
check('overdue request is removed and stored as a timeout') {
    !app.state.pending.containsKey('A:6:01') && app.state.results.values().any { it.values().any { e -> e.status == 'timeout' } }
}
check('a request not yet due and an unsent one stay pending') { app.state.pending.keySet() == ['B:6:02', 'C:6:03'] as Set }
reset()
app.state.pending = ['A:6:01': req('A', [sent: true, deadline: 990000L])]
app.expireOverdue()
check('default asOf gives a 60 s grace') { app.state.pending.size() == 1 }
app.clock += 60000L
app.expireOverdue()
check('past the grace the request expires') { app.state.pending.isEmpty() }
reset()
app.expireOverdue()
check('nothing pending is a no-op') { app.state.results == null }

reset()
app.state.pending = ['A:6:01': req('A', [sent: true, deadline: 1001000L, sentAt: 991000L])]
app.state.sweepAt = 1002000L
app.clock = 1003000L
app.sweepPending()
check('sweep judges at the time it was due and clears sweepAt') {
    app.state.pending.isEmpty() && app.state.results.values().any { it.values().any { e -> e.status == 'timeout' } }
}
reset()
app.state.pending = ['A:6:01': req('A', [sent: true, deadline: 1001000L])]
app.state.sweepAt = 1000500L
app.clock = 1003000L
app.sweepPending()
check('a reply that beat the due time is not timed out') { app.state.pending.size() == 1 }
check('sweep then flushes: a sweep is scheduled while requests remain') { app.scheduled.any { it.fn == 'sweepPending' } }

reset()
app.state.pending = ['A:6:01': req('A', [sent: true, deadline: 1010000L])]
app.state.sweepAt = 1003500L
app.clock = 1003000L
app.sweepPending()
check('sweep clears its own sweepAt so the next one is re-armed') {
    app.scheduled.count { it.fn == 'sweepPending' } == 1 && app.state.sweepAt == 1003000L + app.scheduled.find { it.fn == 'sweepPending' }.ms
}

// ---- failAllPending / radioDown ----
reset()
app.state.pending = ['A:6:01': req('A', [sent: true]), 'B:6:02': req('B')]
app.failAllPending('The hub restarted')
check('every pending request is stored lost with the reason') {
    app.state.pending == [:] && app.state.results.size() == 2 &&
        app.state.results.values().every { it.values().every { e -> e.status == 'lost' && e.error == 'The hub restarted' } }
}
int before = app.logs.size()
app.failAllPending('again')
check('failAllPending is idempotent') { app.logs.size() == before && app.state.results.size() == 2 }
reset()
app.state.pending = ['A:6:01': req('A', [sent: true])]
app.state.watchSent = ['A']
app.radioDown('socket closed')
check('radioDown fails all, clears watchSent, queues a flush') {
    app.state.pending == [:] && app.state.watchSent == null && app.scheduled.any { it.fn == 'flushRadio' }
}

// ---- flushRadio ----
reset()
app.radio = null
app.state.pending = ['A:6:01': req('A')]
app.flushRadio()
check('missing radio child fails every pending request') {
    app.state.pending == [:] && app.state.results.values().any { it.values().any { e -> e.status == 'lost' } }
}

reset()
app.state.pending = ['A:6:01': req('A')]
app.flushRadio()
check('connected radio: frame sent, marked sent with deadline, watch list set') {
    app.radio.sent == ['FA'] && app.radio.watch == ['A'] && app.state.pending['A:6:01'].sent == true &&
        app.state.pending['A:6:01'].deadline == 1010000L && app.state.pending['A:6:01'].sentAt == 1000000L
}
check('a sweep is scheduled while a request is on the air') { app.scheduled.any { it.fn == 'sweepPending' } }

reset()
app.radio.status = 'closed'
app.state.pending = ['A:6:01': req('A')]
app.flushRadio()
check('closed socket: waits, opens one session, nothing sent') {
    app.radio.sent.isEmpty() && app.radio.sessions == 1 && app.state.connectWaitSince == 1000000L && !app.state.pending['A:6:01'].sent
}
app.clock += 5000L
app.flushRadio()
check('still closed within the wait: no second session') { app.radio.sessions == 1 && app.radio.sent.isEmpty() }
app.radio.status = 'connected'
app.flushRadio()
check('socket opens: frame sent and the wait is cleared') { app.radio.sent == ['FA'] && !app.state.containsKey('connectWaitSince') }

reset()
app.radio.status = 'closed'
app.state.pending = ['A:6:01': req('A'), 'B:6:02': req('B', [sent: true, deadline: 1008000L])]
app.state.connectWaitSince = 1000000L - 16000L
app.flushRadio()
check('socket down past CONNECT_WAIT_MS: unsent request lost, the one on the air stays') {
    app.state.pending.keySet() == ['B:6:02'] as Set && app.radio.sent.isEmpty() &&
        app.state.results.values().any { it.values().any { e -> e.status == 'lost' } } &&
        !app.state.containsKey('connectWaitSince') && app.radio.watch == ['B'] && app.scheduled.any { it.fn == 'flushRadio' }
}

reset()
app.state.pending = [:]
app.state.connectWaitSince = 5L
app.flushRadio()
check('nothing pending: wait state cleared, no sweep, no send') {
    !app.state.containsKey('connectWaitSince') && app.scheduled.isEmpty() && app.radio.sent.isEmpty()
}

println "\n${passed} passed, ${failed} failed"
System.exit(failed == 0 ? 0 : 1)

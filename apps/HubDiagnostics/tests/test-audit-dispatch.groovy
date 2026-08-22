// Copyright (c) 2025-2026 PJ
// SPDX-License-Identifier: MIT
//
// Mode 4 (extraction variant) unit test for the Device Usage Audit async fan-out.
//
// Covers the two failure modes the fan-out is hardened against, neither of which has a
// natural trigger on a live hub:
//   1. asynchttpGet throwing synchronously, before the request is accepted — the reserved
//      inFlight slot must be rolled back, or the pool shrinks for the rest of the scan and
//      eight such throws hang the audit at inFlight == 8 with nothing logged.
//   2. an accepted request that never calls back — auditClaimReaper must retire the claim,
//      or inFlight never returns to 0 and the scan hangs with the progress bar stopped.
//
// Like test-zwave-mesh-quality.groovy, this BRACE-EXTRACTS the pipeline methods from the
// shipped HubDiagnostics.groovy and runs the real Groovy semantics, so it stays bound to
// the shipped code rather than to a transcription of it. Everything the pipeline calls out
// to (asynchttpGet, runIn, unschedule, now, finalizeAudit, logging) is stubbed, which is
// what lets the test drive platform failures directly.
//
// LIMITS, stated because a green run here is easy to over-read: the harness is
// single-threaded, so it cannot open a genuine callback-vs-reaper race. Where ownership
// matters, the contract is driven directly instead (see "double retire"). scan.finalizeGuard's
// exactly-once CAS is therefore not empirically covered either — sequentially, removing the
// scan from AUDIT_SCANS already blocks a second finalize.
//
// Run: groovy apps/HubDiagnostics/tests/test-audit-dispatch.groovy

File scriptFile = new File(getClass().protectionDomain.codeSource.location.toURI())
File groovySrc = new File(scriptFile.parentFile.parentFile, 'HubDiagnostics.groovy')
assert groovySrc.exists() : "source not found: ${groovySrc}"
String src = groovySrc.text

// Extract a `<ReturnType> NAME(...) { ... }` method by brace matching.
String extract(String src, String signature) {
    int start = src.indexOf(signature)
    assert start >= 0 : "signature not found: ${signature}"
    int open = src.indexOf('{', start)
    int depth = 0
    for (int i = open; i < src.length(); i++) {
        if (src[i] == '{') depth++
        else if (src[i] == '}') { depth--; if (depth == 0) return src.substring(start, i + 1) }
    }
    throw new IllegalStateException("unbalanced braces for: ${signature}")
}

String pipeline = [
    'private void refillAuditPipeline(String scanId) {',
    'private boolean dispatchOne(String scanId) {',
    'private boolean retireAuditClaim(ConcurrentHashMap scan, Long deviceId, Map claim, String reason) {',
    'private void maybeFinalizeAudit(String scanId) {',
    'void fullJsonCb(resp, data) {',
    'void auditClaimReaper(data) {',
].collect { extract(src, it) }.join('\n\n')

// Scaffold is a non-interpolating string: the extracted bodies contain their own ${...}
// GStrings and must reach the compiler verbatim.
String scaffold = '''
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

class AuditPipeline {
    // Mirrored from the @Field constants in HubDiagnostics.groovy.
    static final int  AUDIT_MAX_INFLIGHT      = 8
    static final int  AUDIT_ATTEMPT_CAP       = 2
    static final int  AUDIT_REAP_INTERVAL_SEC = 10
    static final long AUDIT_REAP_DEADLINE_MS  = 25_000
    static final String HUB_BASE = "http://127.0.0.1:8080"
    static final String FULL_JSON_PATH_PREFIX = "/device/fullJson/"

    ConcurrentHashMap AUDIT_SCANS = new ConcurrentHashMap()
    Map state = [audit: [:]]

    // ---- stubbed platform surface ----
    long clock = 1_000_000L
    long now() { return clock }

    List inflightRequests = []      // accepted requests awaiting a callback
    Set throwFor = [] as Set        // device ids whose dispatch throws synchronously
    Set blackholeFor = [] as Set    // device ids whose request is accepted but never calls back
    List scheduled = []
    List warnings = []
    int finalizeCalls = 0
    Map finalizedAs = null

    void asynchttpGet(String cb, Map params, Map data) {
        Long did = data.deviceId as Long
        if (throwFor.contains(did)) throw new IllegalArgumentException("simulated coercion failure")
        if (blackholeFor.contains(did)) return          // accepted, but no callback will ever come
        inflightRequests << [cb: cb, data: data]
    }
    void runIn(int sec, String handler, Map opts = null) { scheduled << [sec: sec, handler: handler, data: opts?.data] }
    void unschedule(String handler) { scheduled.removeAll { it.handler == handler } }
    void logWarn(String m) { warnings << m }
    void logInfo(String m) {}
    void logDebug(String m) {}
    String getObjectClassName(Object o) { return o.getClass().simpleName }
    Map extractAuditFields(Map fj, Long did) { return [id: did, name: "dev" + did] }

    // Stands in for the real finalizeAudit, keeping only what the pipeline depends on:
    // the exactly-once CAS guard, the reaper unschedule, and removal from AUDIT_SCANS.
    void finalizeAudit(String scanId) {
        ConcurrentHashMap scan = AUDIT_SCANS[scanId]
        if (scan == null) return
        if (!(scan.finalizeGuard as AtomicInteger).compareAndSet(0, 1)) return
        unschedule('auditClaimReaper')
        finalizeCalls++
        finalizedAs = [
            succeeded: (scan.devices as Map).size(),
            failed:    (scan.failed as Map).size(),
            processed: (scan.processed as AtomicInteger).get(),
            total:     scan.total as Integer,
            inFlight:  (scan.inFlight as AtomicInteger).get(),
            claims:    (scan.claims as Map).size(),
            pending:   (scan.pending as ConcurrentLinkedQueue).size()
        ]
        AUDIT_SCANS.remove(scanId)
    }

    // ---- test helpers: mirror apiAuditStart's scan construction ----
    String startScan(List<Long> ids) {
        String scanId = "audit-test"
        ConcurrentHashMap scan = new ConcurrentHashMap()
        scan.total     = ids.size()
        scan.startedAt = now()
        scan.inFlight  = new AtomicInteger(0)
        scan.processed = new AtomicInteger(0)
        scan.pending   = new ConcurrentLinkedQueue<Long>(ids)
        scan.devices   = new ConcurrentHashMap<Long, Map>()
        scan.failed    = new ConcurrentHashMap<Long, String>()
        scan.claims    = new ConcurrentHashMap<Long, Map>()
        scan.tokenSeq  = new AtomicInteger(0)
        scan.finalizeGuard = new AtomicInteger(0)
        AUDIT_SCANS[scanId] = scan
        state.audit = [scanId: scanId, status: 'scanning', processed: 0, total: ids.size()]
        runIn(AUDIT_REAP_INTERVAL_SEC, 'auditClaimReaper', [data: [scanId: scanId]])
        refillAuditPipeline(scanId)
        return scanId
    }
    void deliverOne(int status = 200) {
        Map req = inflightRequests.remove(0)
        fullJsonCb([status: status, json: [device: [:]]], req.data)
    }
    void deliverAll(int status = 200) { while (inflightRequests) deliverOne(status) }
    void fireReaper(String scanId) {
        scheduled.removeAll { it.handler == 'auditClaimReaper' }
        auditClaimReaper([scanId: scanId])
    }
    boolean reaperArmed() { return scheduled.any { it.handler == 'auditClaimReaper' } }

'''

def newPipeline = { ->
    new GroovyShell().evaluate(scaffold + pipeline + "\n}\nreturn new AuditPipeline()")
}

int pass = 0, fail = 0
def check = { String name, boolean cond ->
    if (cond) { pass++; println "  [PASS] ${name}" } else { fail++; println "  [FAIL] ${name}" }
}

println 'Device Usage Audit fan-out (dispatchOne / fullJsonCb / auditClaimReaper)'

// --- 1. Baseline: the happy path is unchanged ---
println '\n1. Normal completion'
def h = newPipeline()
def sid = h.startScan((1L..20L).toList())
check('initial fan-out fills all 8 slots',        h.inflightRequests.size() == 8)
check('one claim per in-flight request',          h.AUDIT_SCANS[sid].claims.size() == 8)
h.deliverAll()
check('finalize fires exactly once',              h.finalizeCalls == 1)
check('all 20 devices succeeded',                 h.finalizedAs?.succeeded == 20)
check('no leaked slots or claims at finalize',    h.finalizedAs?.inFlight == 0 && h.finalizedAs?.claims == 0)
check('reaper unscheduled once finalized',        !h.reaperArmed())

// --- 2. Synchronous dispatch throw ---
println '\n2. Synchronous dispatch throw'
h = newPipeline()
h.throwFor = [3L, 7L] as Set
sid = h.startScan((1L..20L).toList())
check('slot pool still full — no leak',           h.AUDIT_SCANS[sid].inFlight.get() == 8)
check('throwers did not consume slots',           h.inflightRequests.size() == 8)
h.deliverAll()
check('scan finalizes despite the throws',        h.finalizeCalls == 1)
check('18 succeeded, 2 recorded failed',          h.finalizedAs?.succeeded == 18 && h.finalizedAs?.failed == 2)
check('every device accounted for exactly once',  h.finalizedAs?.processed == 20)
check('each thrower retried once, then gave up',  h.warnings.count { it.contains('dispatch threw') } == 4)

// --- 2b. The pathological case the rollback exists for ---
println '\n2b. Every dispatch throws'
h = newPipeline()
h.throwFor = (1L..12L).toSet()
sid = h.startScan((1L..12L).toList())
check('reaches finalize, no hang at inFlight==8', h.finalizeCalls == 1)
check('all failed, pool fully released',          h.finalizedAs?.failed == 12 && h.finalizedAs?.inFlight == 0)

// --- 3. Callbacks that never arrive ---
println '\n3. Missing callbacks'
h = newPipeline()
h.blackholeFor = [2L, 5L] as Set
sid = h.startScan((1L..20L).toList())
h.deliverAll()
def stuck = h.AUDIT_SCANS[sid]
check('un-reaped, the scan is stuck',             stuck != null && h.finalizeCalls == 0)
check('2 slots and 2 claims outstanding',         stuck.inFlight.get() == 2 && stuck.claims.size() == 2)
check('reaper is armed',                          h.reaperArmed())

h.fireReaper(sid)                                  // too early — not yet past the deadline
check('no retire before the deadline',            h.AUDIT_SCANS[sid].claims.size() == 2)
check('reaper reschedules while scan is live',    h.reaperArmed())

h.clock += 26_000
h.blackholeFor = [] as Set                         // let the retry succeed
h.fireReaper(sid)
check('both stale claims reaped',                 h.warnings.count { it.contains('reaped') } == 2)
check('both devices requeued and re-dispatched',  h.inflightRequests.size() == 2)
h.deliverAll()
check('scan finalizes after recovery',            h.finalizeCalls == 1)
check('retry recovered both devices',             h.finalizedAs?.succeeded == 20 && h.finalizedAs?.failed == 0)

// --- 3b. Permanently silent device reaches a terminal state ---
println '\n3b. Permanently silent device'
h = newPipeline()
h.blackholeFor = [4L] as Set
sid = h.startScan((1L..10L).toList())
h.deliverAll()
h.clock += 26_000; h.fireReaper(sid)               // attempt 1 reaped -> requeued (also silent)
check('retry re-claimed the device',              h.AUDIT_SCANS[sid]?.claims?.size() == 1)
h.clock += 26_000; h.fireReaper(sid)               // attempt 2 reaped -> at cap, terminal
check('finalizes rather than hanging forever',    h.finalizeCalls == 1)
check('silent device recorded failed, not lost',  h.finalizedAs?.failed == 1 && h.finalizedAs?.succeeded == 9)
check('reaper stops rescheduling (terminal)',     !h.reaperArmed())

// --- 3c. A late callback from an attempt the reaper already retired ---
// If it were allowed to resolve, it would credit a device whose attempt 2 is still
// outstanding — releasing a slot it does not own and finalizing while a request is live.
println '\n3c. Reaper / late-callback race'
h = newPipeline()
sid = h.startScan((1L..3L).toList())
def held = h.inflightRequests.remove(0)            // hold attempt 1 back
h.deliverAll()                                     // the other two resolve normally
h.clock += 26_000
h.fireReaper(sid)                                  // attempt 1 reaped -> requeued -> attempt 2 dispatched
check('not finalized — attempt 2 outstanding',    h.finalizeCalls == 0)
check('attempt 2 is in flight',                   h.inflightRequests.size() == 1)
check('attempt 2 holds the only claim',           h.AUDIT_SCANS[sid].claims.size() == 1)

h.fullJsonCb([status: 200, json: [device: [:]]], held.data)   // the "lost" callback lands
check('stale callback must not finalize',         h.finalizeCalls == 0)
check('scan still live after stale callback',     h.AUDIT_SCANS[sid] != null)
check('did not release attempt 2 slot',           h.AUDIT_SCANS[sid].inFlight.get() == 1)
check('did not retire attempt 2 claim',           h.AUDIT_SCANS[sid].claims.size() == 1)
check('did not double-count the device',          h.AUDIT_SCANS[sid].processed.get() == 2)

h.deliverAll()
check('attempt 2 finalizes the scan',             h.finalizeCalls == 1)
check('each device counted exactly once',         h.finalizedAs?.processed == 3 && h.finalizedAs?.succeeded == 3)

// --- 3d. Ownership contract, driven directly ---
// A callback and the reaper can genuinely overlap on a hub, and both may try to retire the
// same attempt. Only the holder of that exact claim object may release the slot. Driven
// directly because a single-threaded harness cannot open the real race window.
println '\n3d. Double retire of one attempt'
h = newPipeline()
sid = h.startScan((1L..3L).toList())
def scan3d = h.AUDIT_SCANS[sid]
def victim = scan3d.claims.keySet().iterator().next() as Long
def victimClaim = scan3d.claims[victim] as Map
def beforeInFlight = scan3d.inFlight.get()
def beforeProcessed = scan3d.processed.get()
check('first retire owns the claim',              h.retireAuditClaim(scan3d, victim, victimClaim, 'first') == true)
def afterFirst = scan3d.inFlight.get()
check('first retire released exactly one slot',   afterFirst == beforeInFlight - 1)
check('second retire is refused',                 h.retireAuditClaim(scan3d, victim, victimClaim, 'second') == false)
check('refused retire released no second slot',   scan3d.inFlight.get() == afterFirst)
check('refused retire did not double-count',      scan3d.processed.get() == beforeProcessed)

// --- 3e. The reaper must have a terminal state ---
// A reaper that only stops on successful finalization reschedules forever on a scan that
// can never complete. auditWatchdog CASes finalizeGuard before writing; that must stop it.
println '\n3e. Terminal state on an unfinishable scan'
h = newPipeline()
h.blackholeFor = [1L] as Set
sid = h.startScan([1L])
h.fireReaper(sid)
check('reaper cycles while unfinalized',          h.reaperArmed())
h.AUDIT_SCANS[sid].finalizeGuard.compareAndSet(0, 1)   // as auditWatchdog does
h.fireReaper(sid)
check('reaper stops once finalizeGuard is taken', !h.reaperArmed())

println "\n=== ${pass}/${pass + fail} passed ==="
System.exit(fail == 0 ? 0 : 1)

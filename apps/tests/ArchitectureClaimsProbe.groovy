// Copyright (c) 2025-2026 PJ
// SPDX-License-Identifier: MIT

// Probe app for the platform claims in ARCHITECTURE.md and docs/hubitat-platform-notes.md.
// Driven over OAuth endpoints by apps/tests/test-architecture-claims.sh
// (the reboot checks run with --reboot). The test pushes a second copy with
// singleThreaded: true; keep the name and singleThreaded keys below on their
// own lines so the text substitution in the test keeps working.

import groovy.transform.Field
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

definition(
    name: "Architecture Claims Probe",
    namespace: "tests",
    author: "PJ",
    description: "Probe app for the platform claims in ARCHITECTURE.md. Test use only.",
    menu: "Apps",
    category: "Convenience",
    iconUrl: "",
    iconX2Url: "",
    oauth: true,
    singleThreaded: false
)

preferences {
    page(name: "mainPage", title: "Architecture Claims Probe", install: true, uninstall: true) {
        section {
            input "probeDevice", "capability.actuator", title: "Probe device", required: false
            label title: "Instance label", required: false
        }
    }
}

@Field static final String CODE_VERSION = "1.0.0"
@Field static final String PUSH_MARK = "base"
@Field static final AtomicInteger FIELD_COUNTER = new AtomicInteger(0)
@Field static final ConcurrentHashMap<String, Object> SHARED = new ConcurrentHashMap<String, Object>()
@Field static final ConcurrentHashMap<String, ConcurrentHashMap> FAN_IN = new ConcurrentHashMap<String, ConcurrentHashMap>()
@Field static final ConcurrentHashMap<String, AtomicInteger> FAN_IN_DONE = new ConcurrentHashMap<String, AtomicInteger>()
@Field static final AtomicInteger SLEEP_IN_FLIGHT = new AtomicInteger(0)
@Field static final AtomicInteger SLEEP_MAX_IN_FLIGHT = new AtomicInteger(0)
String TOP_LEVEL_NOT_FIELD = "top"

mappings {
    path("/info")          { action: [GET: "apiInfo"] }
    path("/sleep")         { action: [GET: "apiSleep"] }
    path("/sandbox")       { action: [GET: "apiSandbox"] }
    path("/shared")        { action: [GET: "apiShared"] }
    path("/asyncFire")     { action: [GET: "apiAsyncFire"] }
    path("/asyncRead")     { action: [GET: "apiAsyncRead"] }
    path("/storeDevice")   { action: [GET: "apiStoreDevice"] }
    path("/readDevice")    { action: [GET: "apiReadDevice"] }
    path("/runIn")         { action: [GET: "apiRunIn"] }
    path("/subscribe")     { action: [GET: "apiSubscribe"] }
    path("/commit")        { action: [GET: "apiCommit"] }
    path("/commitRead")    { action: [GET: "apiCommitRead"] }
    path("/throwWrite")    { action: [GET: "apiThrowWrite"] }
    path("/lockArm")       { action: [GET: "apiLockArm"] }
    path("/lockRead")      { action: [GET: "apiLockRead"] }
    path("/rebootArm")     { action: [GET: "apiRebootArm"] }
    path("/rebootRead")    { action: [GET: "apiRebootRead"] }
}

void installed() {
    state.installedCount = ((state.installedCount ?: 0) as int) + 1
    checkOAuth()
    initialize()
}

void updated() {
    state.updatedCount = ((state.updatedCount ?: 0) as int) + 1
    checkOAuth()
    initialize()
}

void initialize() {
    state.initCount = ((state.initCount ?: 0) as int) + 1
}

void uninstalled() { }

private Map renderJson(Map m) {
    return render(contentType: "application/json", data: groovy.json.JsonOutput.toJson(m))
}

private String instanceKey() { return app.id.toString() }

// ── push / lifecycle / @Field ────────────────────────────────────────

Map apiInfo() {
    return renderJson([
        pushMark: PUSH_MARK, fieldCounter: FIELD_COUNTER.incrementAndGet(),
        installedCount: state.installedCount, updatedCount: state.updatedCount, initCount: state.initCount,
        evDefault: atomicState.evDefault ?: [], evUnfiltered: atomicState.evUnfiltered ?: []
    ])
}

Map apiSleep() {
    long start = now()
    int inFlight = SLEEP_IN_FLIGHT.incrementAndGet()
    while (true) {
        int seen = SLEEP_MAX_IN_FLIGHT.get()
        if (inFlight <= seen || SLEEP_MAX_IN_FLIGHT.compareAndSet(seen, inFlight)) break
    }
    try {
        pauseExecution((params.ms ?: "2000") as int)
    } finally {
        SLEEP_IN_FLIGHT.decrementAndGet()
    }
    return renderJson([start: start, end: now()])
}

// ── sandbox behavior ─────────────────────────────────────────────────

Map apiSandbox() {
    String topLevel
    try { topLevel = TOP_LEVEL_NOT_FIELD } catch (Exception e) { topLevel = "exception: " + getObjectClassName(e) }
    String parsed
    try { parsed = Date.parse("yyyy-MM-dd'T'HH:mm:ss.SSSZ", "2026-05-05T23:07:43.088-0400").time.toString() } catch (Exception e) { parsed = "exception: " + e.message }
    String computed
    try { computed = SHARED.computeIfAbsent("cia-" + instanceKey(), { String k -> "made-" + k } as java.util.function.Function).toString() } catch (Exception e) { computed = "exception: " + getObjectClassName(e) }
    return renderJson([objectClassName: getObjectClassName(1), topLevel: topLevel, dateParse: parsed, computeIfAbsent: computed])
}

Map apiShared() {
    if (params.write) SHARED.put("writer", "${instanceKey()}:${params.write}".toString())
    return renderJson([me: instanceKey(), writer: SHARED.get("writer")])
}

// ── async callbacks: state vs atomicState vs @Field concurrent map ───

Map apiAsyncFire() {
    int n = (params.n ?: "20") as int
    String path = params.targetPath as String
    List<String> hosts = ((params.hosts ?: "127.0.0.1") as String).tokenize(",")
    String key = instanceKey()
    SLEEP_MAX_IN_FLIGHT.set(0)
    FAN_IN.put(key, new ConcurrentHashMap())
    FAN_IN_DONE.put(key, new AtomicInteger(0))
    state.remove("plainMap")
    state.keySet().findAll { String k -> k.startsWith("plainKey") }.each { String k -> state.remove(k) }
    atomicState.atomicMap = [:]
    state.remove("fanInFinal")
    state.remove("fanInFinalWrites")
    state.firedAt = now()
    for (int i = 0; i < n; i++) {
        String host = hosts[i % hosts.size()]
        asynchttpGet("asyncHandler", [uri: "http://${host}:8080${path}&tag=${i}", timeout: 60], [i: i, n: n])
    }
    return renderJson([fired: n])
}

void asyncHandler(resp, data) {
    int i = data.i as int
    int n = data.n as int
    String key = instanceKey()
    long t = now()
    String status = resp.hasError() ? "error: " + resp.getErrorMessage() : resp.getStatus().toString()

    Map plain = (state.plainMap ?: [:]) as Map
    plain["k${i}".toString()] = t
    state.plainMap = plain

    Map atomic = (atomicState.atomicMap ?: [:]) as Map
    atomic["k${i}".toString()] = t
    atomicState.atomicMap = atomic

    state["plainKey${i}".toString()] = t

    ConcurrentHashMap fanIn = FAN_IN.get(key)
    pauseExecution(300)
    fanIn?.putIfAbsent("k${i}".toString(), [t: t, out: now(), status: status])
    if (FAN_IN_DONE.get(key)?.incrementAndGet() == n) {
        state.fanInFinal = new TreeMap(fanIn)
        atomicState.fanInFinalWrites = ((atomicState.fanInFinalWrites ?: 0) as int) + 1
    }
}

Map apiAsyncRead() {
    List<String> plainKeys = state.keySet().findAll { String k -> k.startsWith("plainKey") }.sort() as List<String>
    Map fanIn = (state.fanInFinal ?: [:]) as Map
    List<Long> doneAt = plainKeys.collect { String k -> ((state[k] as Long) - (state.firedAt as Long)) }.sort()
    return renderJson([
        plainMap: ((state.plainMap ?: [:]) as Map).size(),
        atomicMap: ((atomicState.atomicMap ?: [:]) as Map).size(),
        plainKeys: plainKeys.size(),
        fanIn: fanIn.size(),
        fanInFinalWrites: atomicState.fanInFinalWrites ?: 0,
        fanInStatuses: fanIn.values().collect { Map v -> v.status }.unique(),
        callbackSpans: fanIn.values().collect { Map v -> [v.t, v.out] },
        maxRequestsInFlight: SLEEP_MAX_IN_FLIGHT.get(),
        completionMs: doneAt
    ])
}

// ── DeviceWrapper in state ───────────────────────────────────────────

Map apiStoreDevice() {
    if (!probeDevice) return renderJson([error: "no probe device selected"])
    state.storedDevice = [probeDevice]
    String sameCall
    try { sameCall = state.storedDevice[0].getLabel() } catch (Exception e) { sameCall = "exception: " + getObjectClassName(e) }
    return renderJson([sameCall: sameCall])
}

Map apiReadDevice() {
    List raw = state.storedDevice as List
    String nextCall
    try { nextCall = raw[0].getLabel() } catch (Exception e) { nextCall = "exception: " + getObjectClassName(e) }
    return renderJson([storedClass: raw ? getObjectClassName(raw[0]) : null, nextCall: nextCall])
}

// ── scheduling ───────────────────────────────────────────────────────

Map apiRunIn() {
    unschedule()
    runIn(3600, "noopDefault")
    runIn(3600, "noopDefault")
    runIn(3600, "noopNoOverwrite", [overwrite: false])
    runIn(3600, "noopNoOverwrite", [overwrite: false])
    return renderJson([scheduled: true])
}

void noopDefault() { }
void noopNoOverwrite() { }

// ── sendEvent dedup vs subscriber filtering ──────────────────────────

Map apiSubscribe() {
    unsubscribe()
    atomicState.evDefault = []
    atomicState.evUnfiltered = []
    String mode = params.mode as String
    if (mode in ["default", "both"]) subscribe(probeDevice, "probe", "defaultHandler")
    if (mode in ["unfiltered", "both"]) subscribe(probeDevice, "probe", "unfilteredHandler", [filterEvents: false])
    return renderJson([mode: mode])
}

void defaultHandler(evt) { atomicState.evDefault = ((atomicState.evDefault ?: []) as List) + [evt.value] }
void unfilteredHandler(evt) { atomicState.evUnfiltered = ((atomicState.evUnfiltered ?: []) as List) + [evt.value] }

// ── commit timing ────────────────────────────────────────────────────

Map apiCommit() {
    String token = "c-${now()}".toString()
    state.commitPlain = token
    atomicState.commitAtomic = token
    Map seen = [:]
    httpGet([uri: "http://127.0.0.1:8080", path: "/apps/api/${app.id}/commitRead",
             query: [access_token: state.accessToken], timeout: 20]) { resp -> seen = resp.data as Map }
    return renderJson([wrote: token, otherRequestSaw: seen])
}

Map apiCommitRead() { return renderJson([plain: state.commitPlain, atomic: atomicState.commitAtomic]) }

Map apiThrowWrite() {
    state.throwPlain = params.token
    atomicState.throwAtomic = params.token
    throw new IllegalStateException("probe: deliberate failure after writes")
}

// ── which handler types wait for each other under singleThreaded ──────
// One call arms a long handler (scheduled or callback) and the handler that
// arrives while it runs, so the arming call itself can't be held up.

Map apiLockArm() {
    atomicState.lockLong = [:]
    atomicState.lockArrive = [:]
    if (params.running == "scheduled") runInMillis(100, "lockLongScheduled")
    if (params.running == "callback") asynchttpGet("lockLongCallback", [uri: "http://127.0.0.1:8080/hub2/hubData", timeout: 30])
    if (params.arriving == "scheduled") runInMillis(2000, "lockArriveScheduled")
    if (params.arriving == "callback") asynchttpGet("lockArriveCallback", [uri: params.slowUrl as String, timeout: 30])
    return renderJson([armed: now()])
}

void lockLongScheduled() { lockLong() }
void lockLongCallback(resp, data) { lockLong() }
private void lockLong() {
    long start = now()
    pauseExecution(6000)
    atomicState.lockLong = [start: start, end: now()]
}

void lockArriveScheduled() { atomicState.lockArrive = [start: now()] }
void lockArriveCallback(resp, data) { atomicState.lockArrive = [start: now()] }

Map apiLockRead() { return renderJson([running: atomicState.lockLong ?: [:], arriving: atomicState.lockArrive ?: [:]]) }

// ── reboot behavior of scheduled jobs (test-reboot-schedules.sh) ─────

Map apiRebootArm() {
    unschedule()
    unsubscribe()
    int dueSecs = (params.dueSecs ?: "90") as int
    atomicState.rebootFired = []
    atomicState.rebootArmedAt = now()
    atomicState.rebootArmUptime = location.hub.uptime
    runIn(dueSecs, "rebootOneShot")
    runIn(3600, "rebootFar")
    schedule("0 * * ? * *", "rebootEveryMinute")
    atomicState.rebootLastBeat = now()
    schedule("0/5 * * ? * *", "rebootHeartbeat")
    subscribe(location, "systemStart", "rebootSystemStart")
    return renderJson([armed: true, dueSecs: dueSecs])
}

private void recordReboot(String what) {
    atomicState.rebootFired = ((atomicState.rebootFired ?: []) as List) + [[what: what, at: now(), uptime: location.hub.uptime]]
}

void rebootOneShot() { recordReboot("oneShot") }
void rebootFar() { recordReboot("far") }
void rebootEveryMinute() { recordReboot("everyMinute") }
void rebootSystemStart(evt) { recordReboot("systemStart") }
// Last moment the hub was running before the reboot (and every 5 s after it).
void rebootHeartbeat() {
    // Uptime restarts at boot, so only beats from before the reboot are recorded.
    if ((location.hub.uptime as Long) >= ((atomicState.rebootArmUptime ?: 0) as Long)) atomicState.rebootLastBeat = now()
}

Map apiRebootRead() {
    return renderJson([armedAt: atomicState.rebootArmedAt, armUptime: atomicState.rebootArmUptime,
                       lastBeatBeforeReboot: atomicState.rebootLastBeat,
                       fired: atomicState.rebootFired ?: [], uptime: location.hub.uptime, now: now()])
}

// ── self-enabling OAuth ──────────────────────────────────────────────

private boolean autoEnableOAuth() {
    String typeId = app.getAppTypeId()?.toString()
    if (!typeId) { log.error "Could not find app type ID."; return false }
    String internalVer = null
    try {
        httpGet([uri: "http://127.0.0.1:8080", path: "/app/ajax/code", query: [id: typeId], timeout: 15]) { resp ->
            internalVer = resp.data?.version?.toString()
        }
    } catch (e) {
        log.error "Failed to fetch app code version: ${e.message}"
        return false
    }
    if (!internalVer) { log.error "Could not determine app code version."; return false }
    boolean success = false
    try {
        httpPost([
            uri: "http://127.0.0.1:8080",
            path: "/app/edit/update",
            requestContentType: "application/x-www-form-urlencoded",
            body: [id: typeId, version: internalVer, oauthEnabled: "true", _action_update: "Update"],
            timeout: 20
        ]) { resp -> success = true }
    } catch (e) {
        log.error "Failed to enable OAuth: ${e.message}"
    }
    return success
}

private boolean checkOAuth() {
    if (state.accessToken) return true
    try {
        createAccessToken()
        return (state.accessToken != null)
    } catch (e) {
        if (autoEnableOAuth()) {
            try {
                createAccessToken()
                return (state.accessToken != null)
            } catch (e2) {
                log.error "OAuth enabled but token creation failed: ${e2.message}"
                return false
            }
        }
        return false
    }
}

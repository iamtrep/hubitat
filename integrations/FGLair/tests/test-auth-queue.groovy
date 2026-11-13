// Copyright (c) 2026 PJ
// SPDX-License-Identifier: MIT
//
// Unit test (TESTING.md Mode 4, whole-file variant) for the FGLair Manager's session and request
// logic: runs FGLairManager.groovy under plain Groovy on a stub of the hub's app API (a
// controllable clock, recorded asynchttp calls, recorded child calls) and drives the 401 chain
// (noteAuthReject, refreshToken, the write 401 retry), the pending-write queue, the request
// budget (fetchNextQueued, hasRequestRoom), the failure streaks and Retry-After. No network.
//
// Run: groovy integrations/FGLair/tests/test-auth-queue.groovy   (a few seconds, no hub)

import org.codehaus.groovy.control.CompilerConfiguration

abstract class StubApp extends Script {
    Map settings = [:]
    Map state = [:]
    Map atomicState = [:]
    long clock = 1_800_000_000_000L
    List<Map> requests = []          // [method, callback, params, data]
    List<List> scheduled = []        // [seconds, handler]
    List<List> logs = []             // [level, message]
    Map<String, Expando> children = [:]

    def definition(Map m) {}
    def preferences(Closure c) {}
    long now() { clock }
    def getLocation() { new Expando(timeZone: TimeZone.getTimeZone('UTC')) }
    def getApp() { new Expando(id: 7, getLabel: { 'FGLair' }) }
    def getLog() {
        def sink = { String lvl -> { String m -> logs << [lvl, m] } }
        new Expando(debug: sink('debug'), info: sink('info'), warn: sink('warn'), error: sink('error'), trace: sink('trace'))
    }
    void runIn(Number s, String h) { scheduled << [s, h] }
    void unschedule(Object... a) {}
    void asynchttpGet(String cb, Map params, Map data) { requests << [method: 'GET', callback: cb, params: params, data: data] }
    void asynchttpPost(String cb, Map params, Map data) { requests << [method: 'POST', callback: cb, params: params, data: data] }
    def getChildDevice(String dni) { children[dni] }
    List getChildDevices() { children.values() as List }
    def propertyMissing(String n) { settings[n] }
}

File scriptFile = new File(getClass().protectionDomain.codeSource.location.toURI())
File src = new File(System.getenv('FGLAIR_MANAGER_SRC') ?: new File(scriptFile.parentFile.parentFile, 'FGLairManager.groovy').path)
assert src.exists() : "source not found: ${src}"

CompilerConfiguration cc = new CompilerConfiguration()
cc.scriptBaseClass = StubApp.name
// The hub's ChildDeviceWrapper isn't on the classpath here; an empty class stands in.
String text = src.text.replace('import com.hubitat.app.ChildDeviceWrapper', '') + '\nclass ChildDeviceWrapper {}\n'
GroovyShell shell = new GroovyShell(this.class.classLoader, new Binding(), cc)

// A signed-in manager with two unit children that record what they are told.
def fresh = {
    def a = shell.parse(text)
    a.run()
    a.state.accessToken = "tok"
    a.state.refreshToken = "ref"
    a.state.tokenExpiry = a.clock + 3_600_000L
    ["D1", "D2"].each { String dsn ->
        List results = []
        List health = []
        a.children["fglair-unit-${dsn}".toString()] = new Expando(
            deviceNetworkId: "fglair-unit-${dsn}", displayName: "Unit ${dsn}", results: results, health: health,
            commandResult: { String n, boolean ok -> results << [n, ok] },
            updateHealth: { String s, String why -> health << s },
            updateState: { Map m -> })
    }
    a
}
def resp = { int status, Map headers = [:] ->
    new Expando(hasError: { status >= 400 }, getStatus: { status }, getHeaders: { headers },
                getErrorMessage: { "err" }, getData: { "" }, getErrorData: { "" })
}
def posts = { a, String cb -> a.requests.findAll { it.callback == cb } }
def hasLog = { a, String lvl, String frag -> a.logs.any { it[0] == lvl && (it[1] as String).contains(frag) } }

int pass = 0, fail = 0
def check = { String name, boolean cond ->
    if (cond) { pass++; println "  [PASS] ${name}" } else { fail++; println "  [FAIL] ${name}" }
}

println "401 chain: refreshes twice, halts on the third"
def a = fresh()
a.noteAuthReject("fetchDevices")
check "first 401 starts one refresh", posts(a, "refreshTokenCallback").size() == 1 && a.atomicState.authRejects == 1
a.noteAuthReject("fetchProperties(D1)")
check "401s from requests sent before the refresh are not counted", a.atomicState.authRejects == 1 && posts(a, "refreshTokenCallback").size() == 1
a.atomicState.remove("authStartedAt")           // the refresh answered
a.noteAuthReject("fetchDevices")
check "second 401 after a refresh refreshes again", a.atomicState.authRejects == 2 && posts(a, "refreshTokenCallback").size() == 2
a.atomicState.remove("authStartedAt")
a.noteAuthReject("fetchDevices")
check "third 401 in a row halts: no further refresh", posts(a, "refreshTokenCallback").size() == 2
check "halt records the reason and drops the session", a.state.authError != null && a.state.accessToken == null && a.state.refreshToken == null
check "halt marks every unit offline", a.children.values().every { it.health == ["offline"] }
check "halt logs one error", a.logs.count { it[0] == 'error' } == 1

println "401 chain: a good response in between resets the count"
a = fresh()
a.noteAuthReject("fetchDevices")
a.atomicState.remove("authStartedAt")
a.clearAuthRejects()
check "count back to 0", a.atomicState.authRejects == 0
a.noteAuthReject("fetchDevices"); a.atomicState.remove("authStartedAt")
a.noteAuthReject("fetchDevices"); a.atomicState.remove("authStartedAt")
check "two more 401s do not halt", a.state.authError == null && a.atomicState.authRejects == 2

println "A lost refresh does not block the next one for ever"
a = fresh()
a.refreshToken()
a.refreshToken()
check "second call inside 30 s is a no-op", posts(a, "refreshTokenCallback").size() == 1
a.clock += 31_000L
a.refreshToken()
check "after AUTH_INFLIGHT_MS a new refresh goes out", posts(a, "refreshTokenCallback").size() == 2
check "refresh posts to the token endpoint", (posts(a, "refreshTokenCallback")[0].params.uri as String).endsWith("/users/refresh_token.json")

println "Write 401: queued once for retry, then dropped"
a = fresh()
a.writeDatapointCallback(resp(401), [dsn: "D1", name: "operation_mode", value: 0, retried: false, report: true])
List pend = a.atomicState.pendingWrites
check "first 401 queues the write marked retried", pend?.size() == 1 && pend[0].retried == true
check "and counts one auth reject, one refresh", a.atomicState.authRejects == 1 && posts(a, "refreshTokenCallback").size() == 1
a.writeDatapointCallback(resp(401), [dsn: "D1", name: "operation_mode", value: 0, retried: true, report: true])
check "a 401 on the retried write is dropped, not queued again", a.atomicState.pendingWrites.size() == 1 && a.atomicState.authRejects == 1
check "and reported failed to the child", a.children["fglair-unit-D1"].results == [["operation_mode", false]]

println "Pending-write queue"
a = fresh()
a.state.remove("tokenExpiry")                   // token near expiry
a.sendCommand("fglair-unit-D1", "adjust_temperature", 230)
check "write while the token is stale is queued, not sent", a.atomicState.pendingWrites.size() == 1 && posts(a, "writeDatapointCallback").isEmpty()
check "and starts a refresh", posts(a, "refreshTokenCallback").size() == 1
a.sendCommand("fglair-unit-D1", "adjust_temperature", 240)
check "a newer write to the same property replaces the queued one", a.atomicState.pendingWrites.size() == 1 && a.atomicState.pendingWrites[0].value == 240
a.sendCommand("fglair-unit-D1", "operation_mode", 6)
a.sendCommand("fglair-unit-D2", "adjust_temperature", 200)
check "other properties and other units queue beside it", a.atomicState.pendingWrites.size() == 3
check "one refresh serves them all", posts(a, "refreshTokenCallback").size() == 1
a.state.tokenExpiry = a.clock + 3_600_000L      // the refresh stored a new token
a.clock += 10_000L
a.flushPendingWrites()
List sent = posts(a, "writeDatapointCallback")
check "flush sends every queued write in order", sent.collect { [it.data.dsn, it.data.name, it.data.value] } ==
    [["D1", "adjust_temperature", 240], ["D1", "operation_mode", 6], ["D2", "adjust_temperature", 200]]
check "flush empties the queue", a.atomicState.pendingWrites == []
check "writes go to the datapoints endpoint", (sent[0].params.uri as String).contains("/dsns/D1/properties/adjust_temperature/datapoints.json")

println "Pending-write queue: too old, ended session, not signed in"
a = fresh()
a.state.remove("tokenExpiry")
a.sendCommand("fglair-unit-D1", "fan_speed", 2)
a.state.tokenExpiry = a.clock + 3_600_000L + 200_000L
a.clock += 121_000L
a.flushPendingWrites()
check "a write older than PENDING_WRITE_MAX_MS is dropped, not sent", posts(a, "writeDatapointCallback").isEmpty()
check "and reported failed", a.children["fglair-unit-D1"].results == [["fan_speed", false]]
a = fresh()
a.state.remove("tokenExpiry")
a.sendCommand("fglair-unit-D1", "fan_speed", 2)
a.sendCommand("fglair-unit-D2", "fan_speed", 3)
a.discardPendingWrites()
check "ending the session reports each queued write failed", a.children["fglair-unit-D1"].results == [["fan_speed", false]] && a.children["fglair-unit-D2"].results == [["fan_speed", false]]
check "and clears the queue", a.atomicState.pendingWrites == null
a = fresh()
a.state.remove("accessToken"); a.state.remove("refreshToken")
a.sendCommand("fglair-unit-D1", "fan_speed", 2)
check "not signed in: write dropped and reported failed, nothing sent", a.requests.isEmpty() && a.children["fglair-unit-D1"].results == [["fan_speed", false]]
a = fresh()
a.sendWrite("fglair-unit-D1", "refresh", 1, false)
check "valid token: sent at once", posts(a, "writeDatapointCallback").size() == 1
a.writeDatapointCallback(resp(500), [dsn: "D1", name: "refresh", value: 1, retried: false, report: false])
check "a manager-initiated write (report=false) stays out of the child's commandStatus", a.children["fglair-unit-D1"].results.isEmpty()
a.writeDatapointCallback(resp(500), [dsn: "D1", name: "fan_speed", value: 1, retried: false, report: true])
check "a failed user write is reported failed", a.children["fglair-unit-D1"].results == [["fan_speed", false]]
a.writeDatapointCallback(resp(200), [dsn: "D1", name: "fan_speed", value: 1, retried: false, report: true])
check "a 200 is reported ok", a.children["fglair-unit-D1"].results.last() == ["fan_speed", true]

println "Request budget"
a = fresh()
a.REQ.inFlight = 7; a.REQ.lastActivity = a.clock
a.sendCommand("fglair-unit-D1", "fan_speed", 2)
check "write with 7 of 8 slots busy is dropped (it keeps 1 slot free)", posts(a, "writeDatapointCallback").isEmpty() && a.children["fglair-unit-D1"].results == [["fan_speed", false]]
a.REQ.inFlight = 6; a.REQ.lastActivity = a.clock
a.sendCommand("fglair-unit-D1", "fan_speed", 3)
check "with 6 busy it goes", posts(a, "writeDatapointCallback").size() == 1
a = fresh()
a.REQ.inFlight = 8; a.REQ.lastActivity = a.clock - 25_000L
a.sendCommand("fglair-unit-D1", "fan_speed", 2)
check "a count with no activity for POLL_IDLE_MS is reset (lost callback), write goes", posts(a, "writeDatapointCallback").size() == 1 && a.REQ.inFlight == 1

println "Poll fetch queue (fetchNextQueued)"
a = fresh()
a.POLL.queue = ["a", "b", "c", "d", "e", "f"]; a.POLL.gen = 1L
6.times { a.fetchNextQueued() }
check "at most MAX_PROPERTY_FETCHES (4) reads in flight", posts(a, "fetchPropertiesCallback").size() == 4 && a.POLL.inFlight == 4
check "the rest wait in the queue", a.POLL.queue == ["e", "f"]
a.fetchPropertiesCallback(resp(200), [dsn: "a", gen: 1L])
check "an answered read frees a slot for the next", posts(a, "fetchPropertiesCallback").size() == 5 && a.POLL.queue == ["f"]
a = fresh()
a.POLL.queue = ["a", "b"]; a.POLL.gen = 1L
a.REQ.inFlight = 7; a.REQ.lastActivity = a.clock
a.fetchNextQueued()
check "no room (7 of 8 busy, 1 reserved): nothing sent, queue kept", a.requests.isEmpty() && a.POLL.queue == ["a", "b"]
a = fresh()
a.POLL.queue = ["a", "b"]; a.POLL.gen = 1L
a.state.remove("tokenExpiry")
a.fetchNextQueued()
check "stale token: one refresh, no read, queue dropped (afterAuth re-polls)", posts(a, "refreshTokenCallback").size() == 1 && posts(a, "fetchPropertiesCallback").isEmpty() && !a.POLL.containsKey("queue")
a = fresh()
a.POLL.queue = ["a", "b"]; a.POLL.gen = 1L
a.fetchPropertiesCallback(resp(401), [dsn: "a", gen: 1L])
check "a 401 on a read clears the queue and counts one reject", !a.POLL.containsKey("queue") && a.atomicState.authRejects == 1
check "a read from a superseded poll is ignored", { ->
    def b = fresh(); b.POLL.gen = 2L; b.fetchPropertiesCallback(resp(401), [dsn: "a", gen: 1L]); b.atomicState.authRejects == null }()

println "Failure streak"
a = fresh()
Map st = [:]
(1..4).each { st = a.streakFailed(st, "FGLair cloud", "boom") }
check "failures 1-4 are warnings only", a.logs.count { it[0] == 'warn' } == 4 && !hasLog(a, 'error', 'unreachable')
st = a.streakFailed(st, "FGLair cloud", "boom")
check "the 5th logs one error", a.logs.count { it[0] == 'error' } == 1 && st.count == 5 && st.reminded == a.clock
a.clock += 60_000L
st = a.streakFailed(st, "FGLair cloud", "boom")
check "the 6th, a minute later, adds no warn or error", a.logs.count { it[0] == 'warn' } == 4 && a.logs.count { it[0] == 'error' } == 1
a.clock += 3_600_000L
st = a.streakFailed(st, "FGLair cloud", "boom")
check "an hour on, one reminder warn", a.logs.count { it[0] == 'warn' } == 5 && hasLog(a, 'warn', 'still unreachable')
check "recovery after the threshold returns a note", a.streakRecovered(st, "FGLair cloud")?.contains("back after") == true
check "recovery of a short streak returns null", a.streakRecovered([count: 2, since: a.clock], "FGLair cloud") == null
a = fresh()
(1..5).each { a.noteTransientFailure("boom") }
check "5 cloud failures mark units offline", a.children.values().every { it.health == ["offline"] }
check "a transient refresh failure is a streak, not a re-sign-in", { ->
    def b = fresh(); b.refreshToken(); b.refreshTokenCallback(resp(503), [:]); b.state.cloudStreak?.count == 1 && b.atomicState.authStartedAt == null }()

println "Retry-After"
a = fresh()
a.noteRetryAfter(resp(429, ["Retry-After": "120"]))
check "seconds honored", a.state.cloudPausedUntil == a.clock + 120_000L
a = fresh()
a.noteRetryAfter(resp(429, ["retry-after": "999999"]))
check "header name is case-insensitive; capped at 1 h", a.state.cloudPausedUntil == a.clock + 3_600_000L
["0", "-5", "soon", ""].each { String v ->
    a = fresh()
    a.noteRetryAfter(resp(429, ["Retry-After": v]))
    check "Retry-After '${v}' ignored", a.state.cloudPausedUntil == null
}
a = fresh()
a.noteRetryAfter(resp(429))
check "no header: nothing", a.state.cloudPausedUntil == null
a = fresh()
a.state.version = a.CODE_VERSION
a.state.cloudPausedUntil = a.clock + 60_000L
a.pollTick()
check "pollTick skips while paused", a.POLL.queue == null && a.requests.isEmpty()
a.clock += 61_000L
a.children.clear()      // wakeDueUnits types its closure on the hub's ChildDeviceWrapper, which is not stubbed
a.pollTick()
check "pollTick polls again once the pause ends", posts(a, "fetchDevicesCallback").size() == 1

println ""
println "${pass} passed, ${fail} failed"
System.exit(fail ? 1 : 0)

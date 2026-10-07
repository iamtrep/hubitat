// Copyright (c) 2025-2026 PJ
// SPDX-License-Identifier: MIT

// Target app for apps/tests/test-singlethreaded.sh: every entry point an app
// instance has (endpoint, scheduled handler, async callback, device and
// location event handlers, child device and child app calls into the parent,
// button handler, page render) runs work() and records its span on the hub
// clock. Child calls also record callerAt, the child's own clock reading as it
// made the call, so a wait inside parent.childWork() shows directly. The test pushes a second copy with singleThreaded: true; keep the
// name, CHILD_APP and singleThreaded keys on their own lines so its text
// substitution keeps working.

import groovy.transform.Field
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

definition(
    name: "Single Threaded Probe",
    namespace: "tests",
    author: "PJ",
    description: "Target app for the singleThreaded entry-point test. Test use only.",
    category: "Convenience",
    iconUrl: "",
    iconX2Url: "",
    oauth: true,
    singleThreaded: false
)

preferences {
    page(name: "mainPage")
    page(name: "workPage")
}

@Field static final String CHILD_APP = "Single Threaded Probe Child App"
@Field static final String CHILD_DRIVER = "Single Threaded Probe Child"
@Field static final ConcurrentHashMap<String, Map> SPANS = new ConcurrentHashMap<String, Map>()
@Field static final ConcurrentHashMap<String, List> QUEUES = new ConcurrentHashMap<String, List>()
@Field static final ConcurrentHashMap<String, AtomicInteger> QUEUE_NEXT = new ConcurrentHashMap<String, AtomicInteger>()
@Field static final ConcurrentHashMap<String, Map> MODE = new ConcurrentHashMap<String, Map>()

mappings {
    path("/work")  { action: [GET: "apiWork"] }
    path("/arm")   { action: [GET: "apiArm"] }
    path("/reset") { action: [GET: "apiReset"] }
    path("/spans") { action: [GET: "apiSpans"] }
    path("/info")  { action: [GET: "apiInfo"] }
    path("/mode")  { action: [GET: "apiMode"] }
}

Map mainPage() {
    dynamicPage(name: "mainPage", title: "Single Threaded Probe", install: true, uninstall: true) {
        section {
            label title: "Instance label", required: false
            input "workButton", "button", title: "Work"
        }
    }
}

Map workPage() {
    popWork("page")
    dynamicPage(name: "workPage", title: "Work") {
        section { paragraph "done" }
    }
}

void installed() { initialize() }

// A settings save from the trigger app is an entry point under test; the
// provisioning saves find nothing armed and only re-initialize.
void updated() {
    if (armed("updated")) popWork("updated")
    initialize()
}

void initialize() {
    checkOAuth()
    unsubscribe()
    // Two source devices, so the two calls of a pair never share one device's event or command queue.
    ["dev", "dev2"].each { String s ->
        String dni = "STP-${app.id}-${s}".toString()
        def child = getChildDevice(dni) ?: addChildDevice("tests", CHILD_DRIVER, dni, [name: "stp-${app.id}-${s}".toString(), isComponent: false])
        subscribe(child, "probe", "devEventHandler", [filterEvents: false])
    }
    subscribe(location, "stpProbe${app.id}".toString(), "locEventHandler")
    // Two child apps, so the two calls of a pair never share one child app instance.
    ["childapp", "childapp2"].each { String s -> if (!childAppFor(s)) addChildApp("tests", CHILD_APP, "stp-${app.id}-${s}".toString()) }
}

private childAppFor(String s) { getChildApps()?.find { it.label == "stp-${app.id}-${s}".toString() } }

// ── the measured work ────────────────────────────────────────────────

// How work() holds the instance: pauseExecution, a busy loop, or a blocking HTTP call.
private void work(String tag, String via, int ms, Long callerAt = null) {
    long start = now()
    Map mode = MODE.get(app.id.toString()) ?: [m: "pause"]
    hold(mode, start + ms)
    SPANS.put("${app.id}:${tag}".toString(), [tag: tag, via: via, start: start, end: now(), mode: mode.m, callerAt: callerAt])
}

private void hold(Map mode, long until) {
    if (mode.m == "busy") {
        long spins = 0
        while (now() < until) spins++
    } else if (mode.m == "http") {
        httpGet([uri: "${mode.slowBase}&until=${until}".toString(), timeout: 60]) { resp -> }
    } else {
        long wait = until - now()
        if (wait > 0) pauseExecution(wait)
    }
}

private boolean armed(String kind) {
    String key = "${app.id}:${kind}".toString()
    List queue = QUEUES.get(key)
    return queue != null && (QUEUE_NEXT.get(key)?.get() ?: 0) < queue.size()
}

private void popWork(String kind) {
    String key = "${app.id}:${kind}".toString()
    List queue = QUEUES.get(key)
    int i = QUEUE_NEXT.computeIfAbsent(key, { String k -> new AtomicInteger(0) } as java.util.function.Function).getAndIncrement()
    if (queue == null || i >= queue.size()) {
        work("${kind}-unarmed-${now()}".toString(), kind, 0)
        return
    }
    Map args = queue[i] as Map
    work(args.tag as String, kind, args.ms as int)
}

private static List<String> splitValue(String value) { return value.tokenize("|") }

void schedWork(Map data) { work(data.tag as String, "scheduled", data.ms as int) }
void cbWork(resp, Map data) { work(data.tag as String, "callback", data.ms as int) }
void devEventHandler(evt) { List<String> p = splitValue(evt.value as String); work(p[0], "devEvent", p[1] as int) }
void locEventHandler(evt) { List<String> p = splitValue(evt.value as String); work(p[0], "locEvent", p[1] as int) }
void appButtonHandler(String btn) { popWork("button") }

// Called by the child device driver and the child app.
void childWork(String tag, Integer ms, String via, Long callerAt = null) { work(tag, via, ms ?: 0, callerAt) }

// ── endpoints ────────────────────────────────────────────────────────

private Map renderJson(Map m) { render(contentType: "application/json", data: groovy.json.JsonOutput.toJson(m)) }

Map apiWork() {
    work(params.tag as String, "endpoint", (params.ms ?: "0") as int)
    renderJson([done: now()])
}

// Sets up an entry point the trigger app can't start directly.
Map apiArm() {
    String kind = params.kind as String
    String tag = params.tag as String
    int ms = (params.ms ?: "0") as int
    long at = (params.at ?: "0") as long
    switch (kind) {
        case "scheduled":
            runInMillis(Math.max(1L, at - now()), "schedWork", [data: [tag: tag, ms: ms], overwrite: false])
            break
        case "callback":
            asynchttpGet("cbWork", [uri: params.slowUri as String, timeout: 60], [tag: tag, ms: ms])
            break
        case "page":
        case "button":
        case "updated":
            String key = "${app.id}:${kind}".toString()
            QUEUES.computeIfAbsent(key, { String k -> [] } as java.util.function.Function).add([tag: tag, ms: ms])
            break
        default:
            return renderJson([error: "unknown kind ${kind}".toString()])
    }
    renderJson([armed: now()])
}

Map apiReset() {
    String prefix = "${app.id}:".toString()
    [SPANS, QUEUES, QUEUE_NEXT].each { Map m -> m.keySet().findAll { String k -> k.startsWith(prefix) }.each { m.remove(it) } }
    renderJson([reset: now()])
}

Map apiSpans() {
    String prefix = "${app.id}:".toString()
    renderJson([spans: SPANS.findAll { k, v -> k.startsWith(prefix) }.values() as List])
}

Map apiMode() {
    MODE.put(app.id.toString(), [m: params.m ?: "pause", slowBase: params.slowBase])
    renderJson([mode: params.m])
}

Map apiInfo() {
    def childApp = childAppFor("childapp")
    def childApp2 = childAppFor("childapp2")
    renderJson([childDevice: getChildDevice("STP-${app.id}-dev".toString())?.id, childDevice2: getChildDevice("STP-${app.id}-dev2".toString())?.id,
                childApp: childApp?.id, childAppToken: childApp?.ensureToken(), childApp2: childApp2?.id, childAppToken2: childApp2?.ensureToken(),
                label: app.label])
}

// ── self-enabling OAuth ──────────────────────────────────────────────

private boolean autoEnableOAuth() {
    String typeId = app.getAppTypeId()?.toString()
    String ver = null
    try {
        httpGet([uri: "http://127.0.0.1:8080", path: "/app/ajax/code", query: [id: typeId], timeout: 15]) { resp -> ver = resp.data?.version?.toString() }
    } catch (e) { log.error "code version: ${e.message}"; return false }
    boolean ok = false
    try {
        httpPost([uri: "http://127.0.0.1:8080", path: "/app/edit/update", requestContentType: "application/x-www-form-urlencoded",
                  body: [id: typeId, version: ver, oauthEnabled: "true", _action_update: "Update"], timeout: 20]) { resp -> ok = true }
    } catch (e) { log.error "enable OAuth: ${e.message}" }
    return ok
}

private boolean checkOAuth() {
    if (state.accessToken) return true
    try { createAccessToken(); return state.accessToken != null } catch (e) {
        if (autoEnableOAuth()) { try { createAccessToken(); return state.accessToken != null } catch (e2) { log.error "token: ${e2.message}" } }
        return false
    }
}

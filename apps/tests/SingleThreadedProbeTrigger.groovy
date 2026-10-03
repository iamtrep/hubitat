// Copyright (c) 2025-2026 PJ
// SPDX-License-Identifier: MIT

// Trigger app for apps/tests/test-singlethreaded.sh. One /run call starts a
// long call into the target and, a fixed gap later, a second call, both at
// set times on the hub clock and from the hub itself, so network delay can't
// blur the measurement. It also parents the Single Threaded Probe Driver
// devices under test. Never singleThreaded itself.

import groovy.json.JsonOutput
import groovy.transform.Field
import java.util.concurrent.ConcurrentHashMap

definition(
    name: "Single Threaded Probe Trigger",
    namespace: "tests",
    author: "PJ",
    description: "Trigger app for the singleThreaded entry-point test. Test use only.",
    category: "Convenience",
    iconUrl: "",
    iconX2Url: "",
    oauth: true,
    singleThreaded: false
)

preferences {
    page(name: "mainPage", title: "Single Threaded Probe Trigger", install: true, uninstall: true) {
        section { label title: "Instance label", required: false }
    }
}

@Field static final String LOOPBACK = "http://127.0.0.1:8080"
@Field static final ConcurrentHashMap<String, Long> ISSUED = new ConcurrentHashMap<String, Long>()

mappings {
    path("/setup")  { action: [GET: "apiSetup"] }
    path("/run")    { action: [GET: "apiRun"] }
    path("/issued") { action: [GET: "apiIssued"] }
    path("/dspans") { action: [GET: "apiDeviceSpans"] }
    path("/slow")   { action: [GET: "apiSlow"] }
    path("/dmode")  { action: [GET: "apiDeviceMode"] }
}

void installed() { checkOAuth() }
void updated() { checkOAuth() }

private Map renderJson(Map m) { render(contentType: "application/json", data: JsonOutput.toJson(m)) }

private static String ipHex(String ip) { ip.tokenize(".").collect { String o -> String.format("%02X", o as int) }.join() }

// Driver targets get the DNI parse() routing needs: the no-flag copy is
// addressed on the hub's LAN IP, the singleThreaded copy on loopback.
Map apiSetup() {
    Map out = [:]
    [MT: [driver: params.driverMT, dni: ipHex(location.hub.localIP as String)],
     ST: [driver: params.driverST, dni: "7F000001"]].each { String k, Map v ->
        def d = getChildDevice(v.dni as String) ?: addChildDevice("tests", v.driver as String, v.dni as String, [name: "stp-driver-${k.toLowerCase()}".toString(), isComponent: false])
        d.makeChild()
        out[k] = [id: d.id, dni: v.dni, childId: d.getChildDevice("${v.dni}-child".toString())?.id,
                  childId2: d.getChildDevice("${v.dni}-child2".toString())?.id]
    }
    renderJson(out)
}

// One running/arriving pair. Starts the running call at now+lead and the
// arriving one gap ms later.
Map apiRun() {
    Map t = [domain: params.domain, tid: params.tid, tok: params.tok, childDev: params.childDev, childDev2: params.childDev2,
             childApp: params.childApp, childAppTok: params.childAppTok, dni: params.dni, parseHost: params.parseHost, label: params.label]
    String rep = params.rep as String
    if (ISSUED.keySet().any { String k -> k.startsWith("${rep}:".toString()) }) return renderJson([error: "rep ${rep} already ran".toString()])
    long t0 = now() + ((params.lead ?: "800") as long)
    long tA = t0 + ((params.gap ?: "700") as long)
    prepare(t, params.running as String, "r${rep}".toString(), (params.msR ?: "2000") as int, t0, rep)
    prepare(t, params.arriving as String, "a${rep}".toString(), (params.msA ?: "100") as int, tA, rep)
    renderJson([t0: t0, tA: tA, armedBy: now()])
}

private void prepare(Map t, String kind, String tag, int ms, long at, String rep) {
    if (t.domain == "app" && kind in ["scheduled", "callback", "page", "button", "updated"]) {
        String slow = "${LOOPBACK}/apps/api/${app.id}/slow?access_token=${state.accessToken}&until=${at}".toString()
        httpGet([uri: LOOPBACK, path: "/apps/api/${t.tid}/arm".toString(), timeout: 20,
                 query: [access_token: t.tok, kind: kind, tag: tag, ms: ms, at: at, slowUri: slow]]) { resp -> }
        if (kind in ["scheduled", "callback"]) { ISSUED.put("${rep}:${tag}".toString(), at); return }
    }
    if (t.domain == "driver" && kind in ["scheduled", "callback"]) {
        String slow = "${LOOPBACK}/apps/api/${app.id}/slow?access_token=${state.accessToken}&until=${at}".toString()
        getChildDevice(t.dni as String).arm(kind, tag, ms as BigDecimal, at as BigDecimal, slow)
        ISSUED.put("${rep}:${tag}".toString(), at)
        return
    }
    Map d = t + [kind: kind, tag: tag, ms: ms, rep: rep]
    // The second call of a pair uses the second source device.
    if (tag.startsWith("a")) d.childDev = t.childDev2
    if (t.domain == "app" && kind == "updated") d.form = settingsForm(t)
    runInMillis(Math.max(1L, at - now()), "fire", [data: d, overwrite: false])
}

// The main page's Done, as the settings page posts it; runs updated().
private String settingsForm(Map t) {
    Map cfg = [:]
    httpGet([uri: LOOPBACK, path: "/installedapp/configure/json/${t.tid}".toString(), timeout: 20]) { resp -> cfg = resp.data as Map }
    Map f = ["_action_update": "Done", formAction: "update", id: t.tid, version: cfg.app?.version ?: 1, appTypeId: "", appTypeName: "",
             currentPage: "mainPage", pageBreadcrumbs: "[]", "label.type": "text", label: t.label ?: "", _cancellable: "false"]
    return f.collect { k, v -> "${URLEncoder.encode(k as String, 'UTF-8')}=${URLEncoder.encode(v as String, 'UTF-8')}" }.join("&")
}

void fire(Map d) {
    String tag = d.tag as String
    int ms = d.ms as int
    ISSUED.put("${d.rep}:${tag}".toString(), now())
    String value = "${tag}|${ms}".toString()
    switch ("${d.domain}:${d.kind}".toString()) {
        case "app:endpoint":
            asynchttpGet("noop", [uri: "${LOOPBACK}/apps/api/${d.tid}/work".toString(), query: [access_token: d.tok, tag: tag, ms: ms], timeout: 60])
            break
        case "app:endpointLan":
            asynchttpGet("noop", [uri: "http://${location.hub.localIP}:8080/apps/api/${d.tid}/work".toString(), query: [access_token: d.tok, tag: tag, ms: ms], timeout: 60])
            break
        case "app:devEvent":
            runMethod(d.childDev, "emit", [[type: "STRING", value: value]])
            break
        case "app:locEvent":
            sendLocationEvent(name: "stpProbe${d.tid}".toString(), value: value, isStateChange: true)
            break
        case "app:childDev":
        case "driver:childDev":
            runMethod(d.childDev, "callParent", [[type: "STRING", value: tag], [type: "NUMBER", value: ms]])
            break
        case "app:childApp":
            asynchttpGet("noop", [uri: "${LOOPBACK}/apps/api/${d.childApp}/callParent".toString(), query: [access_token: d.childAppTok, tag: tag, ms: ms], timeout: 60])
            break
        case "app:button":
            // A Map body arrives unparsed here (404), so the form is encoded by hand.
            String form = "id=${d.tid}&name=workButton&settings%5BworkButton%5D=clicked&workButton.type=button"
            asynchttpPost("noop", [uri: "${LOOPBACK}/installedapp/btn".toString(), requestContentType: "application/x-www-form-urlencoded", timeout: 60,
                                   body: form])
            break
        case "app:updated":
            asynchttpPost("noop", [uri: "${LOOPBACK}/installedapp/update/json".toString(), requestContentType: "application/x-www-form-urlencoded", timeout: 60,
                                   body: d.form])
            break
        case "app:page":
            asynchttpGet("noop", [uri: "${LOOPBACK}/installedapp/configure/json/${d.tid}/workPage".toString(), timeout: 60])
            break
        case "driver:cmdApp":
            getChildDevice(d.dni as String).work(tag, ms as BigDecimal, "cmdApp")
            break
        case "driver:cmdHttp":
            runMethod(d.tid, "work", [[type: "STRING", value: tag], [type: "NUMBER", value: ms], [type: "STRING", value: "cmdHttp"]])
            break
        case "driver:parse":
            asynchttpPost("noop", [uri: "http://${d.parseHost}:39501/".toString(), requestContentType: "text/plain", body: value, timeout: 60])
            break
        default:
            log.warn "unknown kind ${d.domain}:${d.kind}"
    }
}

private void runMethod(deviceId, String method, List args) {
    asynchttpPost("noop", [uri: "${LOOPBACK}/device/runmethod".toString(), requestContentType: "application/json", timeout: 60,
                           body: JsonOutput.toJson([id: deviceId as int, method: method, args: args])])
}

void noop(resp, data) {
    if (resp.hasError() || resp.getStatus() >= 300) log.warn "trigger call failed: ${resp.getStatus()} ${resp.hasError() ? resp.getErrorMessage() : ''}"
}

Map apiIssued() {
    String prefix = "${params.rep}:".toString()
    renderJson(ISSUED.findAll { k, v -> k.startsWith(prefix) }.collectEntries { k, v -> [(k.substring(prefix.size())): v] })
}

Map apiDeviceSpans() {
    def d = getChildDevice(params.dni as String)
    d.dumpSpans()
    List spans = new groovy.json.JsonSlurper().parseText(d.getDataValue("spans") ?: "[]") as List
    if (params.reset) d.resetSpans()
    renderJson([spans: spans])
}

Map apiDeviceMode() {
    getChildDevice(params.dni as String).setMode(params.m as String, params.slowBase as String)
    renderJson([mode: params.m])
}

Map apiSlow() {
    long wait = ((params.until ?: "0") as long) - now()
    if (wait > 0) pauseExecution(wait)
    renderJson([at: now()])
}

// ── self-enabling OAuth ──────────────────────────────────────────────

private boolean autoEnableOAuth() {
    String typeId = app.getAppTypeId()?.toString()
    String ver = null
    try {
        httpGet([uri: LOOPBACK, path: "/app/ajax/code", query: [id: typeId], timeout: 15]) { resp -> ver = resp.data?.version?.toString() }
    } catch (e) { log.error "code version: ${e.message}"; return false }
    boolean ok = false
    try {
        httpPost([uri: LOOPBACK, path: "/app/edit/update", requestContentType: "application/x-www-form-urlencoded",
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

// Copyright (c) 2026 PJ
// SPDX-License-Identifier: MIT

/*
 Thermostat Scheduler+

 Parent of Thermostat Scheduler+ programs. Lists them, creates them, and serves the
 read and command API (OAuth, local by default).
*/

import groovy.transform.Field

@Field static final String CODE_VERSION = "0.1.0"
@Field static final String CHILD_NAME = "Thermostat Scheduler+ Program"

definition(
    name: "Thermostat Scheduler+",
    namespace: "iamtrep",
    author: "pj",
    description: "Thermostat schedules with profiles, holds and an API",
    category: "Convenience",
    singleInstance: true,
    installOnOpen: true,
    oauth: true,
    importUrl: "https://raw.githubusercontent.com/iamtrep/hubitat/main/apps/ThermostatSchedulerPlus/ThermostatSchedulerPlus.groovy",
    iconUrl: "", iconX2Url: ""
)

preferences {
    page(name: "mainPage")
    page(name: "apiPage")
}

mappings {
    path("/programs")             { action: [GET: "apiList", POST: "apiCreate"] }
    path("/programs/:id")         { action: [GET: "apiGet"] }
    path("/programs/:id/command") { action: [POST: "apiCommand"] }
}

Map mainPage() {
    dynamicPage(name: "mainPage", install: true, uninstall: true) {
        section {
            app(name: "programs", appName: CHILD_NAME, namespace: "iamtrep", title: "Create New Program", multiple: true)
        }
        section {
            href "apiPage", title: "API and access",
                 description: "Local access ${allowLocal != false ? 'on' : 'off'} · cloud access ${allowCloud ? 'on' : 'off'}"
        }
        section("Logging") {
            input "txtEnable", "bool", title: "Enable info logging", defaultValue: true
            input "debugEnable", "bool", title: "Enable debug logging", defaultValue: false, submitOnChange: true
            if (debugEnable) input "traceEnable", "bool", title: "Enable trace logging", defaultValue: false
        }
        section { paragraph "<span style='font-size:9px'>Version ${CODE_VERSION}</span>" }
    }
}

Map apiPage() {
    dynamicPage(name: "apiPage", title: "API and access") {
        section("Access") {
            input "allowLocal", "bool", title: "Allow requests from the local network", defaultValue: true
            input "allowCloud", "bool", title: "Allow requests through the Hubitat cloud", defaultValue: false
            paragraph "<div class='p-message p-message-warn' style='padding:12px'>Anyone with the token can control every program. Cloud access makes the API reachable from the internet.</div>"
            if (checkOAuth()) {
                paragraph "<code>${getFullLocalApiServerUrl()}/programs?access_token=${state.accessToken}</code>"
                input "btnResetToken", "button", title: "Reset token", inputClass: "p-button p-button-outlined"
            } else paragraph "OAuth could not be enabled; see the logs."
        }
        section("Routes") {
            paragraph "<table><tr><td><code>GET /programs</code></td><td>All programs with status</td></tr>" +
                      "<tr><td><code>GET /programs/{id}</code></td><td>Configuration and status, with a revision</td></tr>" +
                      "<tr><td><code>POST /programs/{id}/command</code></td><td>Any status device command</td></tr></table>"
        }
    }
}

void installed() { checkOAuth(); initialize() }
void updated() { unsubscribe(); unschedule(); checkOAuth(); initialize() }
void initialize() {
    checkVersion(false)
    if (debugEnable || traceEnable) runIn(1800, "logsOff")
}

void appButtonHandler(String btn) {
    checkVersion()
    if (btn == "btnResetToken") { revokeAccessToken(); state.remove("accessToken"); checkOAuth(); logCfg "token reset" }
}

void logsOff() { app.updateSetting("debugEnable", false); app.updateSetting("traceEnable", false); logWarn "debug logging disabled" }

// ── API ───────────────────────────────────────────────────────────────

private Map denied() {
    String src = request?.requestSource as String
    if (src == "cloud" && !allowCloud) return [status: 403, error: "cloud access is off"]
    if (src != "cloud" && allowLocal == false) return [status: 403, error: "local access is off"]
    return null
}

private Object findProgram() {
    Long id = null
    try { id = params.id?.toString()?.toLong() } catch (Throwable t) { id = null }
    return id == null ? null : getChildAppById(id)
}

private Map parseBody() {
    try {
        Object o = parseJson(request.body ?: "{}")
        return (o instanceof Map) ? (Map) o : null
    } catch (Throwable t) { return null }
}

private Object reply(int status, Map body) { return render(status: status, contentType: "application/json", data: groovy.json.JsonOutput.toJson(body)) }

def apiList() {
    checkVersion()
    Map d = denied(); if (d) { return reply(d.status as int, [error: d.error]) }
    return reply(200, [programs: getChildApps().collect { it.apiStatus() }])
}

def apiGet() {
    checkVersion()
    Map d = denied(); if (d) { return reply(d.status as int, [error: d.error]) }
    def child = findProgram()
    if (!child) { return reply(404, [error: "no program ${params.id}"]) }
    return reply(200, child.apiDocument() as Map)
}

def apiCommand() {
    checkVersion()
    Map d = denied(); if (d) { return reply(d.status as int, [error: d.error]) }
    def child = findProgram()
    if (!child) { return reply(404, [error: "no program ${params.id}"]) }
    Map req
    req = parseBody()
    if (req == null) return reply(400, [error: "body is not JSON"])
    Map res = child.apiCommand(req) as Map
    if (res == null) return reply(500, [error: "program returned no result"])
    return reply(res?.ok == false ? 400 : 200, res)
}

// Debug only: lets test tooling create a program the way the UI does.
def apiCreate() {
    checkVersion()
    if (!debugEnable) { return reply(404, [error: "not found"]) }
    Map d = denied(); if (d) { return reply(d.status as int, [error: d.error]) }
    Map req
    req = parseBody()
    if (req == null) return reply(400, [error: "body is not JSON"])
    String label = req.label?.toString()?.trim()
    if (!label) { return reply(400, [error: "label is required"]) }
    def existing = getChildApps().find { it.label == label }
    if (existing) { return reply(200, [id: existing.id, created: false]) }
    def child = addChildApp("iamtrep", CHILD_NAME, label)
    logCfg "created program ${label} (debug)"
    return reply(201, [id: child.id, created: true])
}

// ── OAuth ─────────────────────────────────────────────────────────────

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
            body: [
                id: typeId,
                version: internalVer,
                oauthEnabled: "true",
                _action_update: "Update"
            ],
            timeout: 20
        ]) { resp ->
            success = true
        }
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
        log.debug "OAuth not enabled yet, attempting auto-enable..."
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

void checkVersion(boolean reinit = true) {
    if (state.version == CODE_VERSION) return
    logVer "version ${CODE_VERSION} (was ${state.version})"
    state.version = CODE_VERSION
    if (reinit) runIn(1, "updated")
}

// ── Logging (app) ─────────────────────────────────────────────────────
//   ⬇️ Evt  ⬆️ Cmd  🔧 Cfg  🌐 Net  ⏰ Sched  🏷️ Ver  ·  ⚠️ Warn  🛑 Error  🔬 Trace
private String logp(String e) { "${e} ${app.getLabel()}: " }

void logEvt  (String m) { if (debugEnable) log.debug logp('⬇️') + m }
void logCmd  (String m) { if (txtEnable != false) log.info  logp('⬆️') + m }
void logCfg  (String m) { if (txtEnable != false) log.info  logp('🔧') + m }
void logNet  (String m) { if (debugEnable) log.debug logp('🌐') + m }
void logSched(String m) { if (debugEnable) log.debug logp('⏰') + m }
void logVer  (String m) { log.warn  logp('🏷️') + m }

void logWarn (String m) { log.warn  logp('⚠️') + m }
void logError(String m) { log.error logp('🛑') + m }
void logTrace(String m) { if (traceEnable) log.trace logp('🔬') + m }
void logInfo (String m) { if (txtEnable != false) log.info  "${app.getLabel()}: ${m}" }
void logDebug(String m) { if (debugEnable) log.debug "${app.getLabel()}: ${m}" }

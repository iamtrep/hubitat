// Copyright (c) 2026 PJ
// SPDX-License-Identifier: MIT

/*
 Thermostat Scheduler+

 Parent of Thermostat Scheduler+ programs. Lists them, creates them, imports built-in
 Thermostat Schedulers, and serves the read, command and configuration API (OAuth,
 local by default).
*/

import groovy.transform.Field

@Field static final String CODE_VERSION = "0.2.0"
@Field static final String CHILD_NAME = "Thermostat Scheduler+ Program"
@Field static final String BUILTIN_TYPE = "Thermostat Scheduler 2.0"

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
    page(name: "importPage")
}

mappings {
    path("/programs")             { action: [GET: "apiList", POST: "apiCreate"] }
    path("/programs/:id")         { action: [GET: "apiGet", PUT: "apiPut"] }
    path("/programs/:id/command") { action: [POST: "apiCommand"] }
    path("/import")               { action: [POST: "apiImport"] }
}

Map mainPage() {
    dynamicPage(name: "mainPage", install: true, uninstall: true) {
        section {
            app(name: "programs", appName: CHILD_NAME, namespace: "iamtrep", title: "Create New Program", multiple: true)
        }
        section {
            href "importPage", title: "Import from Thermostat Scheduler", description: "Create a program from a built-in scheduler"
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
                      "<tr><td><code>PUT /programs/{id}</code></td><td>Replace the configuration; send the revision from GET</td></tr>" +
                      "<tr><td><code>POST /programs/{id}/command</code></td><td>Any status device command</td></tr>" +
                      "<tr><td><code>POST /import</code></td><td>Create a paused program from a built-in scheduler: <code>{\"from\": id}</code></td></tr></table>"
        }
    }
}

void installed() { checkOAuth(); initialize() }
void updated() { unsubscribe(); unschedule(); checkOAuth(); initialize() }
void initialize() {
    checkVersion(false)
    if (debugEnable || traceEnable) runIn(1800, "logsOff")
}

Map importPage() {
    dynamicPage(name: "importPage", title: "Import from Thermostat Scheduler") {
        section {
            paragraph "Creates a paused program from a built-in Thermostat Scheduler, which is left as it is. Open the new program, check it, press Done, disable the built-in scheduler, then turn the program on."
            if (state.importResult) { paragraph state.importResult as String; state.remove('importResult') }
            List<Map> list = null
            try { list = builtinSchedulers() } catch (Exception e) { paragraph "Could not list the schedulers: ${e.message}" }
            if (list != null && !list) paragraph "This hub has no Thermostat Scheduler."
            list?.each { Map b ->
                String done = getChildApps().find { it.importedFrom() == b.id }?.label
                paragraph "${b.name}${done ? " <span style='color:gray'>(imported as ${done})</span>" : ''}", width: 9
                input "imp~${b.id}", "button", title: "Import", width: 3
            }
        }
    }
}

void appButtonHandler(String btn) {
    checkVersion()
    if (btn.startsWith("imp~")) {
        Map r = importScheduler(btn.substring(4) as Long, null)
        state.importResult = r.httpStatus == 201 ? "Created <a href='/installedapp/configure/${r.id}'>${r.name}</a>${r.warnings ? " with ${(r.warnings as List).size()} warning(s), shown on its page" : ''}."
                                                 : "Import failed: ${r.error}${r.errors ? ': ' + (r.errors as List).join('; ') : ''}"
        return
    }
    if (btn == "btnResetToken") { revokeAccessToken(); state.remove("accessToken"); checkOAuth(); logCfg "token reset" }
}

void logsOff() { checkVersion(); app.updateSetting("debugEnable", false); app.updateSetting("traceEnable", false); logWarn "debug logging disabled" }

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

def apiPut() {
    checkVersion()
    Map d = denied(); if (d) { return reply(d.status as int, [error: d.error]) }
    def child = findProgram()
    if (!child) { return reply(404, [error: "no program ${params.id}"]) }
    Map req
    req = parseBody()
    if (req == null) return reply(400, [error: "body is not JSON"])
    Map res = child.apiPut(req) as Map
    return reply(res.httpStatus as int, res.findAll { k, v -> k != 'httpStatus' })
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

// ── Import from the built-in Thermostat Scheduler ──

// Same-hub reads go to the loopback, which hub security does not gate.
private Map loopGet(String path) {
    Map out = null
    httpGet([uri: "http://127.0.0.1:8080", path: path, contentType: "application/json", timeout: 20]) { resp -> out = resp.data as Map }
    return out
}

private void collectBuiltins(List entries, List<Map> out) {
    entries?.each { Map e ->
        Map d = e.data as Map
        if (d?.type == BUILTIN_TYPE) out << [id: d.id as Long, name: stripBadge(d.name as String)]
        collectBuiltins(e.children as List, out)
    }
}

// The built-in appends its state to the label as a coloured span ("Restricted", "Hold").
String stripBadge(String label) { return (label ?: '').replaceAll(/<span[^>]*>.*?<\/span>/, '').replaceAll(/<[^>]*>/, '').trim() }

List<Map> builtinSchedulers() {
    List<Map> out = []
    collectBuiltins((loopGet("/hub2/appsList")?.apps ?: []) as List, out)
    return out
}

String importLabel(String orig, Long from) {
    String l = stripBadge(orig).replaceFirst(/^\s*Thermostat Scheduler\s*:?\s*/, '').trim()
    if (!l) l = "Imported ${from}".toString()
    String n = l
    int i = 2
    while (getChildApps().any { it.label == n }) n = "${l} ${i++}".toString()
    return n
}

// Reply body plus httpStatus.
Map importScheduler(Long from, String label) {
    Map b
    try { b = builtinSchedulers().find { it.id == from } } catch (Exception e) { return [httpStatus: 502, error: "could not list schedulers: ${e.message}".toString()] }
    if (!b) return [httpStatus: 404, error: "no Thermostat Scheduler ${from}".toString()]
    Map raw
    try {
        Map s = (loopGet("/installedapp/configure/json/${from}")?.settings ?: [:]) as Map
        Map st = ((loopGet("/installedapp/statusJson/${from}")?.appState ?: []) as List).collectEntries { Map x -> [(x.name): x.value] }
        raw = [settings: s, appState: st, fromId: from, fromLabel: b.name]
    } catch (Exception e) { return [httpStatus: 502, error: "could not read scheduler ${from}: ${e.message}".toString()] }
    String name = importLabel(label?.trim() ?: ((raw.settings as Map).origLabel as String ?: b.name as String), from)
    def child = addChildApp("iamtrep", CHILD_NAME, name)
    Map r = child.importBuiltin(raw) as Map
    // A program that throws hands back null; the half-made program is removed either way.
    if (!r?.ok) { deleteChildApp(child.id); return [httpStatus: 400, error: "import failed", errors: r?.errors ?: ["the program raised an error; see the logs"]] }
    logCfg "imported ${b.name} as ${name}"
    return [httpStatus: 201, id: child.id, name: name, warnings: r.warnings]
}

def apiImport() {
    checkVersion()
    Map d = denied(); if (d) { return reply(d.status as int, [error: d.error]) }
    Map req
    req = parseBody()
    if (req == null) return reply(400, [error: "body is not JSON"])
    Long from = req.from?.toString()?.isLong() ? (req.from.toString() as Long) : null
    if (from == null) return reply(400, [error: "from is required"])
    Map res = importScheduler(from, req.label?.toString())
    return reply(res.httpStatus as int, res.findAll { k, v -> k != 'httpStatus' })
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
    if (!typeId) { logError "Could not find app type ID."; return false }

    String internalVer = null
    try {
        httpGet([uri: "http://127.0.0.1:8080", path: "/app/ajax/code", query: [id: typeId], timeout: 15]) { resp ->
            internalVer = resp.data?.version?.toString()
        }
    } catch (e) {
        logError "Failed to fetch app code version: ${e.message}"
        return false
    }
    if (!internalVer) { logError "Could not determine app code version."; return false }

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
        logError "Failed to enable OAuth: ${e.message}"
    }
    return success
}

private boolean checkOAuth() {
    if (state.accessToken) return true
    try {
        createAccessToken()
        return (state.accessToken != null)
    } catch (e) {
        logDebug "OAuth not enabled yet, attempting auto-enable..."
        if (autoEnableOAuth()) {
            try {
                createAccessToken()
                return (state.accessToken != null)
            } catch (e2) {
                logError "OAuth enabled but token creation failed: ${e2.message}"
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

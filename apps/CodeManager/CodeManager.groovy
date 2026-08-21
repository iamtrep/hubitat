// Copyright (c) 2025-2026 PJ
// SPDX-License-Identifier: MIT

/**
 * Code Manager
 *
 * Inventory, freshness and (later) safe updates for every app, driver, library and bundle on the hub.
 * 0.1: inventory, classification, usage index, HPM import (observe), freshness sweep. No code writes.
 * Design: apps/CodeManager/docs/DESIGN.md
 */

import groovy.transform.Field
import groovy.transform.CompileStatic
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

@Field static final String CODE_VERSION = "0.1.0"

// ===== Constants =====
@Field static final String HUB_BASE = "http://127.0.0.1:8080"
@Field static final String USER_APP_TYPES_PATH    = "/hub2/userAppTypes"
@Field static final String USER_DEVICE_TYPES_PATH = "/hub2/userDeviceTypes"
@Field static final String USER_LIBRARIES_PATH    = "/hub2/userLibraries"
@Field static final String BUNDLE_LIST_PATH       = "/bundle/list/json"
@Field static final String FILE_LIST_PATH         = "/hub/fileManager/json"
@Field static final String HUB_DATA_PATH          = "/hub2/hubData"
@Field static final String UI_FILE        = "code_manager_ui.html"
@Field static final String SWEEP_FILE     = "cm_sweep.json"
@Field static final String HPM_IMPORT_FILE = "cm_hpm_import.json"
@Field static final String PKG_FILE_PREFIX = "cm_pkg_"
@Field static final String IMPORT_URL_APP = "https://raw.githubusercontent.com/iamtrep/hubitat/refs/heads/main/apps/CodeManager/CodeManager.groovy"
@Field static final String IMPORT_URL_WEB = "https://raw.githubusercontent.com/iamtrep/hubitat/refs/heads/main/apps/CodeManager/code_manager_ui.html"
@Field static final String UPDATE_AVAILABLE_BADGE = ' <span style="color:green; font-weight:bold;">update available</span>'
@Field static final java.util.regex.Pattern UPDATE_BADGE_RE = ~/(?i)(?:\s*(?:<span\b[^>]*>)?\s*update available\s*(?:<\/span>)?)+\s*$/

// Session caches (Tier 2). Wiped on push/reboot; cleared wholesale in updated().
@Field static final ConcurrentHashMap<String, Map> TTL_CACHE = new ConcurrentHashMap<>()
// Live jobs: jobId -> ConcurrentHashMap [kind, total, startedAt, processed(AtomicInteger), inFlight(AtomicInteger), pending(ConcurrentLinkedQueue), status, result, error]
@Field static final ConcurrentHashMap<String, ConcurrentHashMap> JOBS = new ConcurrentHashMap<>()
@Field static final ConcurrentHashMap<String, Long> API_TIMINGS = new ConcurrentHashMap<>()
@Field static volatile String uiVersionCache = null
@Field static volatile boolean githubVersionRefreshPending = false

definition(
    name: "Code Manager",
    namespace: "iamtrep",
    author: "pj",
    description: "Inventory, update detection and safe updates for every app, driver, library and bundle on the hub",
    menu: "Apps",
    category: "Utility",
    singleInstance: true,
    importUrl: IMPORT_URL_APP,
    oauth: true,
    iconUrl: "", iconX2Url: "", iconX3Url: ""
)

preferences { page(name: "mainPage") }

mappings {
    path('/ui.html')               { action: [GET: 'serveUI'] }
    // ===== Aggregator GETs =====
    path('/api/inventory')         { action: [GET: 'apiInventory'] }          // Task 4
    // ===== App-owned GETs =====
    path('/api/item/:kind/:id/source') { action: [GET: 'apiItemSource'] }     // Task 7
    path('/api/jobs/:id')          { action: [GET: 'apiJob'] }
    path('/api/hpm/status')        { action: [GET: 'apiHpmStatus'] }           // Task 5
    path('/api/export')            { action: [GET: 'apiExport'] }              // Task 7
    path('/api/stats')             { action: [GET: 'apiStats'] }
    path('/api/version/check')     { action: [GET: 'apiVersionCheck'] }
    path('/api/settings')          { action: [GET: 'apiGetSettings', POST: 'apiUpdateSettings'] }
    // ===== App-owned mutations (0.1: no hub-code writes) =====
    path('/api/sweep/start')       { action: [POST: 'apiSweepStart'] }         // Task 6
    path('/api/hpm/import')        { action: [POST: 'apiHpmImport'] }          // Task 5
    path('/api/ui/sync')           { action: [POST: 'apiSyncUI'] }
    path('/api/reinit')            { action: [POST: 'apiReinit'] }
}

// ===== Pages =====

Map mainPage() {
    boolean oauthOk = checkOAuth()
    if (!oauthOk) {
        return dynamicPage(name: "mainPage", title: "OAuth Required", install: true, uninstall: true) {
            section("OAuth Setup Failed") {
                paragraph "Code Manager could not enable OAuth automatically. Open <b>Apps Code</b> → <b>Code Manager</b> → ⋮ menu → <b>OAuth</b> → <b>Enable OAuth in app</b> → <b>Update</b>, then re-open this page."
            }
        }
    }
    if (state.installed != true) {
        return dynamicPage(name: "mainPage", title: "Welcome to Code Manager", install: true, uninstall: true) {
            section("Finalize Installation") {
                paragraph "Click <b>Done</b> to initialize, download the UI, and schedule the daily sweep. Then re-open Code Manager from the Apps list."
                paragraph "<b>UI Component:</b> ${getUIVersion() == 'Unknown' ? 'Pending download...' : 'Ready'}"
            }
        }
    }
    dynamicPage(name: "mainPage", title: "Code Manager", install: true, uninstall: true) {
        String uiVer = getUIVersion()
        String remote = checkGithubVersion()
        section("Versions") {
            if (remote && isNewer(remote, CODE_VERSION)) {
                paragraph "<span style='color:orange; font-weight:bold;'>⚠ New version available:</span> v${remote} (you have v${CODE_VERSION})."
            }
            if (isNewer(uiVer, CODE_VERSION)) {
                paragraph "<span style='color:red; font-weight:bold;'>⚠ Update Recommended:</span> UI v${uiVer} is newer than this app (v${CODE_VERSION}). Update the Groovy code."
            }
            paragraph "<b>App Version:</b> ${CODE_VERSION}\n<b>UI Version:</b> ${uiVer}"
        }
        section("Dashboard") {
            href url: "${fullLocalApiServerUrl}/ui.html?access_token=${state.accessToken}", title: "Open Code Manager", style: "external", description: "Inventory, freshness, HPM transition (opens in new tab)"
        }
        section("Notifications") {
            input name: "notifyDevices", type: "capability.notification", title: "Notify these devices when updates are found", multiple: true, required: false
            input name: "labelBadge", type: "bool", title: "Show an 'update available' badge on this app's label", defaultValue: true
        }
        section("Logging") {
            input name: "txtEnable",   type: "bool", title: "Enable descriptionText logging", defaultValue: true
            input name: "debugEnable", type: "bool", title: "Enable debug logging", defaultValue: false, submitOnChange: true
            if (debugEnable) input name: "traceEnable", type: "bool", title: "Enable trace logging", defaultValue: false
        }
        section("Documentation") {
            href url: "https://github.com/iamtrep/hubitat/tree/main/apps/CodeManager", title: "Documentation & README", style: "external"
        }
    }
}

// ===== UI serving and self-sync (HubDiagnostics pattern) =====

Map jsonResponse(Map data) {
    return render(status: 200, contentType: 'application/json', data: JsonOutput.toJson(data))
}

Map serveUI() {
    if (!checkOAuth()) return render(status: 403, contentType: 'text/plain', data: 'OAuth is not enabled for this app.')
    try {
        String html = loadUITemplate()
        if (!html) {
            logError "${UI_FILE} missing from File Manager; attempting emergency sync"
            if (syncUIBlocking()) html = loadUITemplate()
        }
        if (!html) return render(status: 404, contentType: 'text/plain', data: 'UI file not found. Check hub logs.')
        html = html.replace('${access_token}', state.accessToken as String)
                   .replace('${api_base}', fullLocalApiServerUrl as String)
                   .replace('${app_version}', CODE_VERSION)
        return render(status: 200, contentType: 'text/html', data: html)
    } catch (Exception e) {
        logError "Error serving UI: ${e.message}"
        return render(status: 500, contentType: 'text/plain', data: "Error serving UI: ${e.message}")
    }
}

String loadUITemplate() {
    try {
        byte[] bytes = downloadHubFile(UI_FILE)
        if (bytes) return new String(bytes, 'UTF-8')
    } catch (Exception e) { logDebug "UI template read: ${e.message}" }
    return null
}

String getUIVersion() {
    if (uiVersionCache) return uiVersionCache
    String html = loadUITemplate()
    if (html) {
        java.util.regex.Matcher m = (html =~ /const CODE_VERSION = "([^"]+)"/)
        if (m.find()) { uiVersionCache = m.group(1); return uiVersionCache }
    }
    return "Unknown"
}

void syncUI(boolean force = false) {
    if (!force && state.lastInstalledVersion == CODE_VERSION) {
        long lastCheck = (state.lastUIUpdateCheck ?: 0) as long
        if (now() - lastCheck < 86400000L) return
    }
    logNet "Syncing UI from GitHub (async)"
    asynchttpGet('syncUICallback', [uri: IMPORT_URL_WEB, contentType: "text/plain", timeout: 30])
}
void syncUIForced() { syncUI(true) }
void scheduledUISync() { syncUI(false) }

void syncUICallback(resp, data) {
    if (resp.hasError() || resp.status != 200) { logWarn "UI sync failed: HTTP ${resp.status}"; return }
    processSyncUIResponse((resp.data ?: "") as String)
}

private boolean syncUIBlocking() {
    try {
        String text = null
        httpGet([uri: IMPORT_URL_WEB, contentType: "text/plain", timeout: 30]) { resp ->
            if (resp.success && resp.data) text = resp.data.text ?: resp.data.toString()
        }
        return processSyncUIResponse(text ?: "")
    } catch (Exception e) { logWarn "UI sync (blocking) failed: ${e.message}"; return false }
}

private boolean processSyncUIResponse(String html) {
    if (!html || !html.contains("Code Manager")) { logWarn "UI sync: downloaded content invalid"; return false }
    if (!html.contains("const CODE_VERSION = \"${CODE_VERSION}\"")) { logWarn "UI sync: GitHub UI version does not match app v${CODE_VERSION}"; return false }
    byte[] bytes = html.getBytes("UTF-8")
    uploadHubFile(UI_FILE, bytes)
    state.lastInstalledVersion = CODE_VERSION
    state.lastUIUpdateCheck = now()
    uiVersionCache = CODE_VERSION
    logVer "UI updated from GitHub to match app v${CODE_VERSION} (${bytes.length} bytes)"
    return true
}

Map apiSyncUI() { return jsonResponse([success: syncUIBlocking()]) }
Map apiReinit() { updated(); return jsonResponse([success: true, version: CODE_VERSION]) }

// Stale-while-revalidate GitHub version poll (HubDiagnostics/MultiHubInventory engine).
String checkGithubVersion() {
    if (now() - ((state.lastGithubVersionCheck ?: 0) as long) >= 3600000L && !githubVersionRefreshPending) {
        githubVersionRefreshPending = true
        state.lastGithubVersionCheck = now()   // throttle hourly regardless of outcome
        asynchttpGet('githubVersionCallback', [uri: IMPORT_URL_APP, contentType: "text/plain", timeout: 10])
    }
    return state.lastGithubVersion
}

void githubVersionCallback(resp, data) {
    githubVersionRefreshPending = false
    if (resp.hasError() || resp.status != 200) { logDebug "GitHub version check failed: HTTP ${resp.status}"; return }
    java.util.regex.Matcher m = ((resp.data?.toString() ?: '') =~ /CODE_VERSION\s*=\s*"([^"]+)"/)
    if (m.find()) { state.lastGithubVersion = m.group(1); refreshUpdateLabel() }
}
void scheduledVersionCheck() { checkGithubVersion(); refreshUpdateLabel() }

Map apiVersionCheck() {
    String latest = checkGithubVersion()
    if (!latest) return jsonResponse([error: "Unable to check for updates"])
    return jsonResponse([currentVersion: CODE_VERSION, latestVersion: latest, updateAvailable: isNewer(latest, CODE_VERSION), editorPath: getAppEditorPath()])
}

// Badge reflects BOTH a newer Code Manager release AND pending updates found by the sweep (Task 6
// sets state.updatesAvailable). Strip by text with optional tags — Done strips label HTML.
void refreshUpdateLabel() {
    try {
        String current = (app.getLabel() ?: "") as String
        String base = current ? UPDATE_BADGE_RE.matcher(current).replaceAll('') : current
        boolean selfOutdated = state.lastGithubVersion && isNewer(state.lastGithubVersion as String, CODE_VERSION)
        boolean show = (settings.labelBadge != false) && (selfOutdated || state.updatesAvailable == true)
        String desired = show ? (base + UPDATE_AVAILABLE_BADGE) : base
        if (current != desired) app.updateLabel(desired)
    } catch (Exception e) { logDebug "refreshUpdateLabel: ${e.message}" }
}

private String getAppTypeId() {
    Object resp = hubRequest(USER_APP_TYPES_PATH, "user app types", "json", 10)
    if (!(resp instanceof List)) return null
    Map me = (resp as List).find { Map a -> a.name == "Code Manager" && a.namespace == "iamtrep" } as Map
    return me?.id?.toString()
}
private String getAppEditorPath() { String id = getAppTypeId(); return id ? "/app/editor/${id}" : null }

private boolean checkOAuth() {
    if (state.accessToken) return true
    try { createAccessToken(); return state.accessToken != null }
    catch (e) {
        logDebug "OAuth not enabled yet; attempting auto-enable"
        if (autoEnableOAuth()) {
            try { createAccessToken(); return state.accessToken != null }
            catch (e2) { logError "OAuth enabled but token creation failed: ${e2.message}"; return false }
        }
        return false
    }
}

private boolean autoEnableOAuth() {
    String typeId = getAppTypeId()
    if (!typeId) { logError "Could not find Code Manager in user app types"; return false }
    String lock = null
    try { httpGet([uri: HUB_BASE, path: "/app/ajax/code", query: [id: typeId], timeout: 15]) { resp -> lock = resp.data?.version?.toString() } }
    catch (e) { logError "Failed to read app code version: ${e.message}"; return false }
    if (!lock) return false
    boolean ok = false
    try {
        httpPost([uri: HUB_BASE, path: "/app/edit/update", requestContentType: "application/x-www-form-urlencoded",
                  body: [id: typeId, version: lock, oauthEnabled: "true", _action_update: "Update"], timeout: 20]) { resp -> ok = true }
        logCfg "OAuth auto-enabled"
    } catch (e) { logError "Failed to enable OAuth: ${e.message}" }
    return ok
}

// ===== Hub request wrappers (HubDiagnostics pattern: one transient retry, fail-soft) =====

private Object hubRequest(String path, String name, String type = "json", int timeout = 30) {
    return hubRequestInternal(path, name, type, timeout, true)
}
private Map hubMapRequest(String path, String name, int timeout = 30, boolean allowRetry = true) {
    Object raw = hubRequestInternal(path, name, "json", timeout, allowRetry)
    if (raw instanceof Map && ((Map) raw).error) return [ok: false, data: [:], error: (String) ((Map) raw).message]
    return [ok: true, data: (Map) (raw ?: [:]), error: null]
}
private Object hubRequestInternal(String path, String name, String type, int timeout, boolean allowRetry) {
    long start = now()
    try {
        Object result = null
        httpGet([uri: HUB_BASE, path: path, contentType: type == "json" ? "application/json" : "text/plain", timeout: timeout]) { resp ->
            if (resp.success) result = (type == "json") ? resp.data : (resp.data?.text?.trim() ?: resp.data?.toString()?.trim())
        }
        logTrace "Fetched ${name} in ${now() - start}ms"
        return type == "json" ? (result ?: [:]) : result
    } catch (Exception e) {
        String ex = getObjectClassName(e)
        boolean transientErr = (ex == 'java.net.SocketTimeoutException' || ex == 'java.net.ConnectException')
        if (allowRetry && transientErr) { logDebug "Transient error fetching ${name}; retrying once"; return hubRequestInternal(path, name, type, timeout, false) }
        if (type == "json") {
            if (ex == 'groovy.json.JsonException') logDebug "No JSON body for ${name}: ${e.message}"
            else logError "Error fetching ${name} (${now() - start}ms): ${ex}: ${e.message}"
            return [error: true, message: e.message]
        }
        logDebug "Error fetching ${name}: ${e.message}"
        return null
    }
}

// ===== Session TTL cache (Tier 2) — fetch closures return null on failure so misses are never cached =====
private Object cachedFetch(String key, long ttlMs, Closure fetch) {
    Map slot = TTL_CACHE[key]
    if (slot != null && (now() - (slot.at as long)) < ttlMs) return slot.data
    Object data = fetch()
    if (data != null) TTL_CACHE[key] = [data: data, at: now()]
    return data
}
void cacheClear() { TTL_CACHE.clear() }

private Map timed(String name, Closure<Map> body) {
    long start = now()
    Map data = body.call()
    long elapsed = now() - start
    API_TIMINGS[name] = elapsed
    logDebug "api ${name} completed in ${elapsed}ms"
    return jsonResponse(data)
}
Map apiStats() { return jsonResponse([version: CODE_VERSION, timingsMs: new HashMap(API_TIMINGS), jobs: JOBS.keySet().toList()]) }

// ===== Jobs (§10): start/status with terminal status 'done' =====
String newJob(String kind, int total) {
    String id = "${kind}-${now()}-${(int) (Math.random() * 9999)}"
    ConcurrentHashMap j = new ConcurrentHashMap()
    j.kind = kind; j.total = total; j.startedAt = now(); j.status = 'running'
    j.processed = new AtomicInteger(0); j.inFlight = new AtomicInteger(0)
    j.pending = new ConcurrentLinkedQueue<String>()
    JOBS[id] = j
    state.lastJob = [id: id, kind: kind, status: 'running', total: total, startedAt: j.startedAt]   // scalar snapshot survives a push
    return id
}
void jobDone(String jobId, Map result) {
    ConcurrentHashMap j = JOBS[jobId]; if (j == null) return
    j.status = 'done'; j.result = result ?: [:]; j.finishedAt = now()
    state.lastJob = [id: jobId, kind: j.kind, status: 'done', total: j.total, startedAt: j.startedAt, finishedAt: j.finishedAt]
}
void jobFail(String jobId, String error) {
    ConcurrentHashMap j = JOBS[jobId]; if (j == null) return
    j.status = 'error'; j.error = error; j.finishedAt = now()
    state.lastJob = [id: jobId, kind: j.kind, status: 'error', error: error, total: j.total, startedAt: j.startedAt, finishedAt: j.finishedAt]
}
Map jobStatus(String jobId) {
    ConcurrentHashMap j = JOBS[jobId]
    if (j == null) {
        Map snap = (state.lastJob ?: [:]) as Map
        if (snap.id == jobId) return (snap.status == 'running') ? snap + [status: 'error', error: 'Job lost to hub restart or code push'] : snap
        return [id: jobId, status: 'unknown']
    }
    return [id: jobId, kind: j.kind, status: j.status, processed: (j.processed as AtomicInteger).get(), total: j.total,
            startedAt: j.startedAt, finishedAt: j.finishedAt, error: j.error, result: j.status == 'done' ? j.result : null]
}
Map apiJob() { return jsonResponse(jobStatus(params.id as String)) }

// ===== Settings =====
Map apiGetSettings() {
    return jsonResponse([txtEnable: settings.txtEnable != false, debugEnable: settings.debugEnable == true, traceEnable: settings.traceEnable == true,
                         labelBadge: settings.labelBadge != false, notifyDeviceCount: (settings.notifyDevices ?: []).size(),
                         sweepTtlHours: 24, sweepMaxInflight: 6, brokenAfter: 3, appVersion: CODE_VERSION, uiVersion: getUIVersion()])
}
Map apiUpdateSettings() {
    Map body = [:]
    String dataStr = params?.data as String
    if (dataStr) { try { body = (Map) new JsonSlurper().parseText(dataStr) } catch (Exception ignored) {} }
    if (!body) return jsonResponse([success: false, error: "Empty or invalid body"])
    Set boolKeys = ["txtEnable", "debugEnable", "traceEnable", "labelBadge"] as Set
    body.each { String k, Object v -> if (boolKeys.contains(k)) app.updateSetting(k, [type: "bool", value: v as boolean]) }
    if (body.debugEnable == true) runIn(1800, 'logsOff')
    refreshUpdateLabel()
    return jsonResponse([success: true])
}

// ===== Lifecycle =====
void installed() { logCfg "installed"; state.installed = true; if (!state.accessToken) checkOAuth(); runIn(1, 'syncUIForced'); initialize() }
void updated() {
    logCfg "updated"
    state.installed = true
    unsubscribe(); unschedule()
    cacheClear()
    uiVersionCache = null
    if (settings.debugEnable) runIn(1800, 'logsOff')
    runIn(1, 'syncUIForced')
    initialize()
}
void uninstalled() { unschedule(); unsubscribe(); logCfg "uninstalled" }
void logsOff() { app.updateSetting("debugEnable", [type: "bool", value: false]); app.updateSetting("traceEnable", [type: "bool", value: false]); logCfg "debug/trace logging auto-disabled" }

void initialize() {
    if (state.version != CODE_VERSION) { logVer "New version: ${CODE_VERSION} (was: ${state.version})"; state.version = CODE_VERSION }
    // A job that was running when the class was replaced can never finish: mark it.
    Map lj = (state.lastJob ?: [:]) as Map
    if (lj.status == 'running' && !JOBS[lj.id as String]) state.lastJob = lj + [status: 'error', error: 'Job lost to hub restart or code push']
    Random rng = new Random()
    schedule("${rng.nextInt(60)} ${rng.nextInt(60)} 3 * * ?", "scheduledSweep")       // Task 6 defines scheduledSweep
    schedule("0 17 3 * * ?", "scheduledUISync")
    schedule("0 41 3 * * ?", "scheduledVersionCheck")
    subscribe(location, "systemStart", "systemStartHandler")
    refreshUpdateLabel()
    logSched "daily sweep, UI sync and version check scheduled"
}
void systemStartHandler(evt) { runIn(300, 'scheduledSweep') }
void scheduledSweep() { logSched "scheduled sweep (engine arrives in Task 6)" }

// ===== Logging (docs/logging-emoji-scheme.md, app variant) =====
private String logp(String e) { "${e} ${app.getLabel()}: " }
private void logInfo (String m) { if (settings.txtEnable != false) log.info  "${app.getLabel()}: ${m}" }
private void logDebug(String m) { if (settings.debugEnable) log.debug "${app.getLabel()}: ${m}" }
private void logTrace(String m) { if (settings.traceEnable) log.trace logp('🔬') + m }
private void logCfg  (String m) { log.info  logp('🔧') + m }
private void logNet  (String m) { if (settings.debugEnable) log.debug logp('🌐') + m }
private void logSched(String m) { if (settings.debugEnable) log.debug logp('⏰') + m }
private void logVer  (String m) { log.info  logp('🏷️') + m }
private void logWarn (String m) { log.warn  logp('⚠️') + m }
private void logError(String m) { log.error logp('🛑') + m }

// Dotted-numeric compare for CODE_VERSION strings (this app's own versions only; upstream
// versions use the heuristic parser of Task 2).
private boolean isNewer(String a, String b) {
    if (!a || !b || a == "Unknown" || b == "Unknown") return false
    List pa = a.tokenize('.').collect { it.isInteger() ? it.toInteger() : 0 }
    List pb = b.tokenize('.').collect { it.isInteger() ? it.toInteger() : 0 }
    for (int i = 0; i < Math.max(pa.size(), pb.size()); i++) {
        int x = i < pa.size() ? (pa[i] as int) : 0, y = i < pb.size() ? (pb[i] as int) : 0
        if (x != y) return x > y
    }
    return false
}

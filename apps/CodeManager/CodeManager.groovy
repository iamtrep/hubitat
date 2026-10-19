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
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@Field static final String CODE_VERSION = "0.1.1"

// ===== Constants =====
@Field static final String HUB_BASE = "http://127.0.0.1:8080"
@Field static final String USER_APP_TYPES_PATH    = "/hub2/userAppTypes"
@Field static final String USER_DEVICE_TYPES_PATH = "/hub2/userDeviceTypes"
@Field static final String USER_LIBRARIES_PATH    = "/hub2/userLibraries"
@Field static final String BUNDLE_LIST_PATH       = "/bundle/list/json"
@Field static final String FILE_LIST_PATH         = "/hub/fileManager/json"
@Field static final String HUB_DATA_PATH          = "/hub2/hubData"
@Field static final String APPS_LIST_PATH         = "/hub2/appsList"
@Field static final String DEVICES_LIST_PATH      = "/hub2/devicesList"
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
// Shared bulk reads (DESIGN 3.2, 5.1): one read per key in flight, the result shared for BULK_TTL_MS; the slow library list is read at a time.
@Field static final long BULK_TTL_MS   = 30_000L
@Field static final long BULK_WAIT_MS  = 20_000L
@Field static final long LIB_TTL_MS    = 600_000L
@Field static final long LIB_RETRY_MS  = 60_000L          // minimum gap between automatic library refresh attempts after a failed one
@Field static final int  LIB_TIMEOUT_SEC = 30
@Field static final ConcurrentHashMap<String, Map> BULK_CACHE = new ConcurrentHashMap<>()          // key -> [data, at]
@Field static final ConcurrentHashMap<String, Semaphore> BULK_GATES = new ConcurrentHashMap<>()    // key -> single permit
@Field static final ConcurrentHashMap<String, AtomicInteger> BULK_FETCHES = new ConcurrentHashMap<>()
@Field static final AtomicInteger PER_ITEM_READS_IN_REQUEST = new AtomicInteger(0)
@Field static final AtomicInteger REQUESTS_ACTIVE = new AtomicInteger(0)   // endpoint handlers running now (set by timed())
@Field static final long PROVISIONAL_MS = 2_000L          // a fact read this soon after its item's lastModified is provisional
@Field static final long LARGE_SOURCE_BYTES = 524_288L    // a larger source is never read in the background
@Field static final long FACT_GAP_MS = 2_000L             // fact reader: pause between two reads
@Field static final int  FACT_FAIL_LIMIT = 2              // consecutive failed reads before an item is backed off
@Field static final List<Long> FACT_BACKOFF_MS = [3_600_000L, 86_400_000L]   // first back-off 1 h, then 24 h
@Field static final long FACT_READ_TIMEOUT_MS = 30_000L    // fetchItemDetail's request timeout
@Field static final int  FACT_FLUSH_EVERY = 10            // reads between writes of the pending facts to state.pkgs
@Field static final int  FACT_WRITE_WAIT_STEPS = 300      // reader gives up after this many steps (about 10 min) spent waiting for a write
// The single writer lease (DESIGN 6.6). Every code write takes the permit; the fact reader reads nothing while it is taken.
@Field static final Semaphore WRITE = new Semaphore(1)
@Field static volatile Map LIB_SNAPSHOT = [list: null, asOf: null, error: null, attemptedAt: 0L]   // last good list is kept on failure
@Field static volatile String LIB_JOB_ID = null
@Field static volatile boolean simulateLibFail = false
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
    path('/api/libraries/refresh') { action: [POST: 'apiLibrariesRefresh'] }
    path('/api/test/simulateLibFail') { action: [POST: 'apiSimulateLibFail'] }   // answers 404 unless debug logging is on
    path('/api/sweep/start')       { action: [POST: 'apiSweepStart'] }         // Task 6
    path('/api/facts/status')      { action: [GET: 'apiFactsStatus'] }
    path('/api/facts/read')        { action: [POST: 'apiFactsRead'] }
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

private Object hubRequest(String path, String name, String type = "json", int timeout = 30, Map query = null) {
    return hubRequestInternal(path, name, type, timeout, true, query)
}
private Map hubMapRequest(String path, String name, int timeout = 30, boolean allowRetry = true) {
    Object raw = hubRequestInternal(path, name, "json", timeout, allowRetry)
    if (raw instanceof Map && ((Map) raw).error) return [ok: false, data: [:], error: (String) ((Map) raw).message]
    return [ok: true, data: (Map) (raw ?: [:]), error: null]
}
// query goes through httpGet's query: param, never appended to path — firmware 2.5.1.x drops an inline query string.
private Object hubRequestInternal(String path, String name, String type, int timeout, boolean allowRetry, Map query = null) {
    long start = now()
    try {
        Object result = null
        Map reqParams = [uri: HUB_BASE, path: path, contentType: type == "json" ? "application/json" : "text/plain", timeout: timeout]
        if (query) reqParams.query = query
        httpGet(reqParams) { resp ->
            if (resp.success) result = (type == "json") ? resp.data : (resp.data?.text?.trim() ?: resp.data?.toString()?.trim())
        }
        logTrace "Fetched ${name} in ${now() - start}ms"
        return type == "json" ? (result ?: [:]) : result
    } catch (Exception e) {
        String ex = getObjectClassName(e)
        boolean transientErr = (ex == 'java.net.SocketTimeoutException' || ex == 'java.net.ConnectException')
        if (allowRetry && transientErr) { logDebug "Transient error fetching ${name}; retrying once"; return hubRequestInternal(path, name, type, timeout, false, query) }
        if (type == "json") {
            if (ex == 'groovy.json.JsonException') logDebug "No JSON body for ${name}: ${e.message}"
            else logError "Error fetching ${name} (${now() - start}ms): ${ex}: ${e.message}"
            return [error: true, message: e.message]
        }
        logDebug "Error fetching ${name}: ${e.message}"
        return null
    }
}

// ===== Inventory (DESIGN.md §3) =====

List fetchUserAppTypes()    { return (List) bulkPart("apps") ?: [] }
List fetchUserDriverTypes() { return (List) bulkPart("drivers") ?: [] }
List fetchUserLibraries()   { return (List) libraries().list ?: [] }
List fetchBundles()         { return (List) bulkPart("bundles") ?: [] }

// ===== Shared bulk reads =====

private Semaphore gateFor(String key) {
    Semaphore g = BULK_GATES[key]
    if (g == null) { BULK_GATES.putIfAbsent(key, new Semaphore(1)); g = BULK_GATES[key] }
    return g
}
private void countFetch(String key) {
    AtomicInteger c = BULK_FETCHES[key]
    if (c == null) { BULK_FETCHES.putIfAbsent(key, new AtomicInteger(0)); c = BULK_FETCHES[key] }
    c.incrementAndGet()
}
// Single-flight read of one key. A caller that finds the permit taken waits up to BULK_WAIT_MS, then takes the cache entry.
// The fetch closure returns null on failure; the last good data is then kept and returned, and the failure is not cached.
private Object sharedRead(String key, long ttlMs, Closure fetch) {
    Map slot = BULK_CACHE[key]
    if (slot != null && (now() - (slot.at as long)) < ttlMs) return slot.data
    Semaphore gate = gateFor(key)
    if (!gate.tryAcquire(BULK_WAIT_MS, TimeUnit.MILLISECONDS)) return BULK_CACHE[key]?.data
    try {
        Map again = BULK_CACHE[key]
        if (again != null && (now() - (again.at as long)) < ttlMs) return again.data
        countFetch(key)
        Object data = fetch()
        if (data != null) { BULK_CACHE[key] = [data: data, at: now()]; return data }
        return BULK_CACHE[key]?.data
    } finally { gate.release() }
}
private Object bulkPart(String key) {
    return sharedRead(key, BULK_TTL_MS) {
        switch (key) {
            case "apps":    return readAppTypes()
            case "drivers": return readDriverTypes()
            case "bundles": return readBundles()
            case "appsTree": return readAppsTree()
            case "devices": return readDevices()
            case "files":   return readFiles()
        }
        return null
    }
}
// asOf is the age of the oldest cached entry, so it never claims fresher data than any part holds; null when nothing is cached.
// freshKeys names the entries read within one BULK_TTL_MS: a failed read leaves the last good data in place but not fresh.
Map bulk() {
    Map out = [:]
    List<String> keys = ["apps", "drivers", "bundles", "appsTree", "devices", "files"]
    keys.each { String k -> out[k] = (List) bulkPart(k) ?: [] }
    Long oldest = null
    List fresh = []
    long n = now()
    keys.each { String k ->
        Map sl = BULK_CACHE[k]
        if (sl == null) return
        if (oldest == null || (sl.at as long) < oldest) oldest = sl.at as long
        if (n - (sl.at as long) < BULK_TTL_MS) fresh << k
    }
    out.asOf = oldest
    out.freshKeys = fresh
    return out
}
private void bulkInvalidate(String key) { Map sl = BULK_CACHE[key]; if (sl != null) BULK_CACHE[key] = [data: sl.data, at: 0L] }

private List readAppTypes() {
    Object resp = hubRequest(USER_APP_TYPES_PATH, "user app types", "json", 15)
    if (!(resp instanceof List)) return null
    return (resp as List).collect { Map a ->
        List used = (a.usedBy as List) ?: []
        // ids only (I10): the SPA's usedBy consumer (dlistlink) reads .id; names were dead weight in every payload.
        [id: a.id.toString(), name: a.name, namespace: a.namespace, oauth: a.oauth == "enabled", lastModified: a.lastModified,
         usedByCount: used.size(), usedBy: used.collect { Map u -> [id: u.id] }]
    }
}
private List readDriverTypes() {
    Object resp = hubRequest(USER_DEVICE_TYPES_PATH, "user driver types", "json", 15)
    if (!(resp instanceof List)) return null
    return (resp as List).collect { Map d ->
        List used = (d.usedBy as List) ?: []
        [id: d.id.toString(), name: d.name, namespace: d.namespace, lastModified: d.lastModified,
         usedByCount: used.size(), usedBy: used.collect { Map u -> [id: u.id] }]
    }
}
private List readBundles() {
    Object resp = hubRequest(BUNDLE_LIST_PATH, "bundles", "json", 15)
    if (!(resp instanceof List)) return null
    return (resp as List).collect { Map b ->
        [id: b.id.toString(), name: b.name, namespace: b.namespace, isPrivate: b.private == true, sourceUrl: b.sourceUrl,
         appTypeIds: (b.appTypeIds ?: []).collect { it.toString() }, deviceTypeIds: (b.deviceTypeIds ?: []).collect { it.toString() },
         libraryIds: (b.libraryIds ?: []).collect { it.toString() }]
    }
}
// Parent/child app instance tree, as the hub returns it (nodes carry data, children).
private List readAppsTree() {
    Map w = hubMapRequest(APPS_LIST_PATH, "apps list", 20)
    return (w.ok && w.data.apps instanceof List) ? (List) w.data.apps : null
}
// The device list is a tree; flatten it, keeping the ids that tie a device to its type and parent. Zigbee model strings can carry NUL padding.
private List readDevices() {
    Map w = hubMapRequest(DEVICES_LIST_PATH, "devices list", 20)
    if (!w.ok || !(w.data.devices instanceof List)) return null
    List out = []
    flattenDevices((List) w.data.devices, null, out)
    return out
}
private void flattenDevices(List nodes, Object parentId, List out) {
    for (Object n in nodes) {
        Map node = (Map) n
        Map d = (Map) (node.data ?: [:])
        out << [id: d.id, name: d.name, deviceTypeId: d.deviceTypeId, parentDeviceId: parentId, parentAppId: d.parentAppId, model: stripNul(d.model as String)]
        if (node.children instanceof List) flattenDevices((List) node.children, d.id, out)
    }
}
@CompileStatic
static String stripNul(String s) { return s == null ? null : s.replace("\u0000", "").trim() }
private List readFiles() {
    Map wrap = hubMapRequest(FILE_LIST_PATH, "file list", 15)
    return wrap.ok ? ((wrap.data.files ?: []) as List) : null
}

// ===== Library list: read at a time, never in a request handler (the list costs 10-20 s on a hub with many libraries) =====

Map libraries() {
    Map snap = LIB_SNAPSHOT
    return [list: snap.list, asOf: snap.asOf, error: snap.error]
}
private List readLibrariesFromHub() {
    if (libFailActive()) return null
    Object resp = hubRequestInternal(USER_LIBRARIES_PATH, "user libraries", "json", LIB_TIMEOUT_SEC, false)
    if (!(resp instanceof List)) return null
    return (resp as List).collect { Map l ->
        List usedDrv = ((l.usedByDeviceTypes as String) ?: "").split(',\\s*').findAll { it }
        List usedApp = ((l.usedByAppTypes as String) ?: "").split(',\\s*').findAll { it }
        [id: l.id.toString(), name: l.name, namespace: l.namespace, version: l.version?.toString(), lastModified: l.lastModified ?: l.updateTime,
         isPrivate: l.private == true, usedByCount: usedDrv.size() + usedApp.size(), usedBy: (usedApp + usedDrv).collect { [name: it] }]
    }
}
// One read, single-flight. On failure the last good list stays and only the error is recorded. Returns the error text or null.
// The test flag counts only while debug logging is on, so switching debug off always restores normal reads.
private boolean libFailActive() { return simulateLibFail && settings.debugEnable == true }
// force: an explicit user refresh always reads; without it a snapshot under BULK_TTL_MS old is reused.
private String readLibrariesNow(boolean force = false) {
    Semaphore gate = gateFor("libraries")
    if (!gate.tryAcquire(BULK_WAIT_MS, TimeUnit.MILLISECONDS)) return "library read already in progress"
    try {
        Map snap = LIB_SNAPSHOT
        if (snap.asOf != null && (now() - (snap.asOf as long)) < BULK_TTL_MS && snap.error == null && !libFailActive() && !force) return null
        countFetch("libraries")
        long t0 = now()
        List list = readLibrariesFromHub()
        if (list == null) {
            LIB_SNAPSHOT = [list: snap.list, asOf: snap.asOf, error: "library list could not be read", attemptedAt: now()]
            return "library list could not be read"
        }
        LIB_SNAPSHOT = [list: list, asOf: now(), error: null, attemptedAt: now()]
        logDebug "libraries read in ${now() - t0}ms (${list.size()})"
        return null
    } finally { gate.release() }
}
// Starts the background read (or joins the one running) and returns its job id.
String startLibraryRefresh(boolean force = false) {
    String running = LIB_JOB_ID
    if (running && JOBS[running]?.status == 'running') return running
    String jobId = newJob('libraries', 1)
    LIB_JOB_ID = jobId
    runIn(1, 'libRefreshRun', [data: [jobId: jobId, force: force], overwrite: false])
    return jobId
}
void refreshLibraries() { startLibraryRefresh() }
void libRefreshRun(Map data) {
    String jobId = data.jobId as String
    try {
        String err = readLibrariesNow(data.force == true)
        Map snap = LIB_SNAPSHOT
        jobDone(jobId, [asOf: snap.asOf, count: ((List) snap.list)?.size(), error: err])
    } catch (Exception e) {
        jobFail(jobId, "library read failed: ${e.message}")
    } finally { if (LIB_JOB_ID == jobId) LIB_JOB_ID = null }
}
Map apiLibrariesRefresh() { return jsonResponse([jobId: startLibraryRefresh(true)]) }
// Test hook: makes the next library reads fail. Registered always (mappings are static), answers only while debug logging is on.
Map apiSimulateLibFail() {
    if (settings.debugEnable != true) return render(status: 404, contentType: 'application/json', data: JsonOutput.toJson([error: "not found"]))
    simulateLibFail = (params.on == "1")
    return jsonResponse([simulateLibFail: simulateLibFail])
}
private void scheduleLibraryRefreshIfStale() {
    Map snap = LIB_SNAPSHOT
    boolean stale = snap.asOf == null || (now() - (snap.asOf as long)) > LIB_TTL_MS
    if (stale && (now() - (snap.attemptedAt as long)) > LIB_RETRY_MS) startLibraryRefresh()
}
// Per-item: importUrl + source + private flag. One call per item. A call made while an endpoint handler runs counts in
// perItemReadsInRequest, which stays 0 except for the one item the user acted on (DESIGN.md 5.1). The background fact
// reader passes background = true so a request running beside it is not blamed for its reads.
Map fetchItemDetail(String kind, String id, boolean background = false) {
    if (!background && REQUESTS_ACTIVE.get() > 0) PER_ITEM_READS_IN_REQUEST.incrementAndGet()
    String path = kind == "library" ? "/library/ajax/code" : "/${kind}/list/single/data/${id}"
    Object resp = kind == "library" ? hubRequest(path, "library ${id}", "json", 30, [id: id]) : hubRequest(path, "${kind} ${id}", "json", 30)
    Map d = (resp instanceof List) ? ((resp as List) ? (Map) (resp as List)[0] : null) : (resp instanceof Map && !((Map) resp).error ? (Map) resp : null)
    if (d == null) return null
    String source = (d.source ?: "") as String
    String importUrl = kind == "library" ? libraryImportUrl(source) : (d.importUrl as String)
    return [importUrl: importUrl ?: null, source: source, isPrivate: d.private == true]
}

// Hubitat's ISO offset strings are not portable to browsers: convert once here (ARCHITECTURE.md "Date handling").
@CompileStatic
static long isoToMs(String raw) {
    if (!raw) return 0L
    try { return Date.parse("yyyy-MM-dd'T'HH:mm:ss.SSSZ", raw).time } catch (Exception ignored) {}
    try { return Date.parse("yyyy-MM-dd'T'HH:mm:ssZ", raw).time } catch (Exception ignored) {}
    return 0L
}

// Registry compaction (state.pkgs size budget, DESIGN.md §2.4): drop null-valued fields before
// storing a component or package map. buildInventoryPayload() reads every field via ?./?:, so a
// missing key behaves the same as a stored null.
Map compact(Map m) { return m.findAll { k, v -> v != null } }

// ih/ah are stored as this 16-hex-char prefix of the full sha256 (state-size budget); also
// truncates a pre-existing full-length value carried forward from before this was added.
@CompileStatic
static String hashPrefix(String s) { return (s && s.length() > 16) ? s.substring(0, 16) : s }

// The registry's one composite-key shape: a hub object's kind + its hub id. Other key shapes
// (url:, local:, bundle:, cm_pkg_ file names) are built inline as explicitly typed String vars.
@CompileStatic
static String ckey(String kind, Object id) { return kind + ":" + id }

String pkgFileName(String key) { return PKG_FILE_PREFIX + digest16(key) + ".json" }

// null (not []) when there is no usable listing: the read failed, or the last good one is older than one bulk window.
List listHubFiles() {
    List l = (List) bulkPart("files")
    Map sl = BULK_CACHE["files"]
    return (l != null && sl != null && (now() - (sl.at as long)) < BULK_TTL_MS) ? l : null
}
List listCmFiles() { return (listHubFiles() ?: []).findAll { Map f -> (f.name as String)?.startsWith("cm_") } }
void writeCmFile(String name, String text) {
    bulkInvalidate("files")
    try { uploadHubFile(name, text.getBytes("UTF-8")) } catch (Exception e) { logError "write ${name}: ${e.message}" }
}
String readCmFile(String name) {
    try { byte[] b = downloadHubFile(name); return b ? new String(b, "UTF-8") : null } catch (Exception e) { return null }
}
void writePkgDoc(String key, Map pkg, Map manifest) {
    Map doc = manifest + [_cm: [key: key, gen: pkg.g, origin: pkg.o, writtenAt: now()]]
    writeCmFile(pkgFileName(key), JsonOutput.toJson(doc))
}
Map readPkgDoc(String key) {
    String t = readCmFile(pkgFileName(key)); if (!t) return null
    try { Map d = (Map) new JsonSlurper().parseText(t); return (d?._cm?.key == key) ? d : null } catch (Exception e) { return null }
}

// Degenerate manifest for non-HPM origins (DESIGN.md §2.2).
@CompileStatic
static Map degenerateManifest(String origin, String name, List<Map> comps, String bundleUrl) {
    Map m = [packageName: name, version: null, apps: [], drivers: [], libraries: [], bundles: []]
    if (origin == 'bundle') {
        (m.bundles as List) << [name: name, namespace: (comps ? comps[0]['ns'] : null), location: bundleUrl, required: true]
    }
    for (Map c in comps) {
        String arr = c.k == 'app' ? 'apps' : c.k == 'driver' ? 'drivers' : c.k == 'library' ? 'libraries' : null
        if (arr) (m[arr] as List) << [name: c.nm, namespace: c.ns, location: c.loc, required: true]
    }
    return m
}

// ===== Per-item facts (DESIGN.md 3.1): what a stored fact is based on =====

// 'unread' (never read), 'stale' (the item changed since the read), 'provisional' (read within PROVISIONAL_MS of
// the item's lastModified, so a second save in the same second could hide behind it; re-read once), else 'valid'.
@CompileStatic
static String factBasis(Map c, long lmNow) {
    if (c == null || c.flm == null) return 'unread'
    long flm = (c.flm as Long).longValue()
    if (flm != lmNow) return 'stale'
    if (c.frd != null && ((c.frd as Long).longValue() - flm) < PROVISIONAL_MS) return 'provisional'
    return 'valid'
}

// Origin of a component outside its package's own claim. url versus local needs the item's importUrl, a per-item fact,
// so an item whose facts are not usable is 'unread' and never guessed as local.
@CompileStatic
static String classifyOrigin(Map c, boolean inHpm, boolean inBundle, String basis) {
    if (inHpm) return 'hpm'
    if (inBundle) return 'bundle'
    if (basis == 'unread' || basis == 'stale') return 'unread'
    return c?.loc ? 'url' : 'local'
}

// Parents of each type's instances, from the bulk app tree and device list. An app type maps to the app instances
// that parent one of its instances; a driver type maps to the parent devices of its devices. A device created by an
// app has no recorded parent app in the device list, so it has no entry.
@CompileStatic
static Map<String, List<Map>> childOfIndex(List appsTree, List devices) {
    Map<String, List<Map>> out = [:]
    Set<String> seenKeys = new HashSet<String>()
    walkAppTree(appsTree, null, out, seenKeys)
    Map<String, Object> nameById = [:]
    for (Object o in (devices ?: [])) { Map d = (Map) o; nameById[d.id.toString()] = d.name }
    for (Object o in (devices ?: [])) {
        Map d = (Map) o
        if (d.parentDeviceId == null || d.deviceTypeId == null) continue
        String ck = ckey('driver', d.deviceTypeId)
        String dedupe = ck + '<' + d.parentDeviceId
        if (!seenKeys.add(dedupe)) continue
        List<Map> l = out[ck]; if (l == null) { l = []; out[ck] = l }
        l << ([kind: 'device', id: d.parentDeviceId, name: nameById[d.parentDeviceId.toString()]] as Map)
    }
    return out
}
@CompileStatic
static void walkAppTree(List nodes, Map parent, Map<String, List<Map>> out, Set<String> seenKeys) {
    for (Object o in (nodes ?: [])) {
        Map node = (Map) o
        Map d = (Map) (node.data ?: [:])
        if (parent != null && d.appTypeId != null) {
            String ck = ckey('app', d.appTypeId)
            if (seenKeys.add(ck + '<' + parent.id)) {
                List<Map> l = out[ck]; if (l == null) { l = []; out[ck] = l }
                l << ([kind: 'app', id: parent.id, name: parent.name] as Map)
            }
        }
        if (node.children instanceof List) walkAppTree((List) node.children, [id: d.id, name: d.name] as Map, out, seenKeys)
    }
}

// Which package documents to delete and which to hold after a reconcile. oldMembers: package key -> member kind:id list (previous
// registry plus held documents); newKeys: keys of the new registry; unreadCks: members now in the unread group. A key still in the
// registry is neither deleted nor held. A key that left but whose former member is unread is held (its item is expected back).
@CompileStatic
static Map docPruneDecision(Map<String, List> oldMembers, Set<String> newKeys, Set<String> unreadCks) {
    List<String> prune = []
    Map<String, List> hold = [:]
    for (Map.Entry<String, List> e in oldMembers.entrySet()) {
        if (newKeys.contains(e.key)) continue
        boolean expectedBack = false
        for (Object ck in e.value) { if (unreadCks.contains(ck.toString())) expectedBack = true }
        if (expectedBack) hold[e.key] = e.value else prune << e.key
    }
    return [prune: prune, hold: hold]
}

// Whether a component's stored field may be shown (DESIGN.md 3.1). Fields read from an item's source (ih, and loc/iv/ivs for
// non-HPM origins) need a usable basis; an HPM component's loc/iv/ivs come from the package manifest and are always shown.
@CompileStatic
static boolean factVisible(String origin, String basis, String field) {
    if (basis == null || basis == 'valid' || basis == 'provisional') return true
    return origin == 'hpm' && (field == 'loc' || field == 'iv' || field == 'ivs')
}

// Registry reconciliation from bulk reads only (DESIGN.md 3.2): binds and prunes components and packages and writes
// the cm_pkg_ documents. It never reads an item's source; per-item facts (importUrl, hashes, version) are kept as the
// fact reader left them and are shown only while factBasis() says they still describe the item.
Map reconcileRegistry(Map bulk) {
    long t0 = now()
    List apps = (bulk.apps ?: []) as List, drvs = (bulk.drivers ?: []) as List, bundles = (bulk.bundles ?: []) as List
    List fresh = (bulk.freshKeys ?: []) as List
    // The library list is read at a time. Null means not read yet: libraries are neither bound nor pruned.
    List libs = (List) libraries().list
    // Null means the listing is missing or older than one bulk window: neither the file-existence check nor the stale-document prune acts on it.
    List hubFiles = fresh.contains('files') ? ((bulk.files ?: []) as List) : null
    Set<String> fmNames = hubFiles == null ? null : (hubFiles.collect { Map f -> f.name as String } as Set)
    Map pkgs = (state.pkgs ?: [:]) as Map
    // Fail closed: a failed bulk read is indistinguishable from an empty list. If the registry already holds a
    // component of some kind and this pass's list for that kind came back empty, abort before touching the registry.
    Set<String> kindsPresent = pkgs.values().collectMany { Map p -> (p.c as List).collect { Map c -> c.k as String } } as Set
    Map<String, List> bulkByKind = [app: apps, driver: drvs, bundle: bundles]
    if (libs != null) bulkByKind.library = libs
    for (String kind in ['app', 'driver', 'library', 'bundle']) {
        if (bulkByKind.containsKey(kind) && kindsPresent.contains(kind) && (bulkByKind[kind] as List).isEmpty()) {
            logWarn "inventory: aborting pass — ${kind} list came back empty but the registry has ${kind} components (suspected transient fetch failure)"
            return [items: 0, ms: now() - t0, aborted: "empty ${kind} list" as String]
        }
    }
    Map<String, String> memberOf = [:]
    bundles.each { Map b ->
        b.appTypeIds.each { memberOf[ckey('app', it)] = b.id }
        b.deviceTypeIds.each { memberOf[ckey('driver', it)] = b.id }
        b.libraryIds.each { memberOf[ckey('library', it)] = b.id }
    }
    // Index existing components by kind:id so bindings and facts survive a pass. Origin-url app/driver components
    // don't store loc (derivable from the package key), so reconstruct it from the key.
    Map<String, Map> prev = [:]
    pkgs.each { String key, Map p ->
        (p.c as List).each { Map c ->
            Map full = (p.o == 'url' && c.k != 'library' && c.loc == null) ? c + [loc: key.substring(4)] : c
            prev[ckey(c.k as String, c.id)] = full + [_key: key]
        }
    }
    Map<String, Map> seen = [:]
    List<List> all = [['app', apps], ['driver', drvs]]
    if (libs != null) all << ['library', libs]
    for (List pair in all) {
        String kind = pair[0] as String
        for (Map t in (pair[1] as List)) {
            String ck = ckey(kind, t.id)
            Map old = prev[ck]
            long lm = isoToMs(t.lastModified as String)
            // A registry written before facts carried their own read version holds ih for an item read at its stored lm.
            Long flm = old?.flm != null ? (old.flm as Long) : (old?.ih != null ? (old.lm as Long) : null)
            Map comp = [k: kind, id: t.id, ns: t.namespace, nm: t.name, u: t.usedByCount, lm: lm, bm: memberOf[ck],
                        loc: old?.loc, ih: hashPrefix(old?.ih as String), iv: old?.iv, ivs: old?.ivs, pv: old?.pv ?: (t.isPrivate == true), ah: hashPrefix(old?.ah as String),
                        flm: flm, frd: old?.frd, sz: old?.sz, ch: old?.ch, ff: old?.ff, fnext: old?.fnext, fe: old?.fe]
            if (comp.ah == null) comp.ah = comp.ih   // adoption hash: set once, never touched again
            seen[ck] = compact(comp)
        }
    }
    // No library list yet: keep the registry's library components exactly as they are.
    if (libs == null) prev.each { String ck, Map c -> if (c.k == 'library') seen[ck] = compact(c.findAll { k, v -> k != '_key' } as Map) }
    // Rebuild packages: keep HPM-origin packages, regroup everything else.
    Map next = [:]
    // file/bundle components of an HPM package have no counterpart in `seen` — rebind only kinds this pass enumerates.
    Set rebindableKinds = ['app', 'driver', 'library'] as Set
    // An HPM manifest names a bundle by its download location, not by the hub's bundle name, so bundles bind on sourceUrl and files on their File Manager name.
    Map<String, Map> bundleByUrl = [:]
    bundles.each { Map b -> if (b.sourceUrl) bundleByUrl[b.sourceUrl as String] = b }
    pkgs.each { String key, Map p ->
        if (p.o != 'hpm') return
        next[key] = compact(p + [c: (p.c as List).collect { Map c ->
            if (rebindableKinds.contains(c.k)) return seen[ckey(c.k as String, c.id)] ?: c + [id: null]
            if (c.k == 'bundle') { Map b = bundleByUrl[c.loc as String]; return c + [id: b?.id, ns: c.ns ?: b?.namespace] }
            if (c.k == 'file') return fmNames == null ? c : c + [id: fmNames.contains(c.nm as String) ? c.nm : null]
            return c
        }])
    }
    Set bound = next.values().collectMany { Map p -> (p.c as List).findAll { Map c -> c.id }.collect { Map c -> ckey(c.k as String, c.id) } } as Set
    // A bundle an HPM package installed belongs to that package: its members are folded in.
    Map<String, String> bundleOwner = [:]
    next.each { String k, Map p -> if (p.o == 'hpm') (p.c as List).each { Map c -> if (c.k == 'bundle' && c.id) bundleOwner[c.id as String] = k } }
    bundles.each { Map b ->
        String key = "bundle:${b.id}"
        Map bundleComp = compact([k: 'bundle', id: b.id, ns: b.namespace, nm: b.name, u: (b.appTypeIds.size() + b.deviceTypeIds.size() + b.libraryIds.size()),
                                   lm: 0L, bm: null, loc: b.sourceUrl, ih: null, iv: null, ivs: null, pv: b.isPrivate == true, ah: null])
        // classification priority is hpm > bundle: a member id already claimed by an HPM package is not also added under its bundle.
        List members = (b.appTypeIds.findAll { !bound.contains(ckey('app', it)) }.collect { seen[ckey('app', it)] } +
                        b.deviceTypeIds.findAll { !bound.contains(ckey('driver', it)) }.collect { seen[ckey('driver', it)] } +
                        b.libraryIds.findAll { !bound.contains(ckey('library', it)) }.collect { seen[ckey('library', it)] }).findAll { it }
        members.each { Map c -> bound << ckey(c.k as String, c.id) }
        String owner = bundleOwner[b.id as String]
        if (owner) { ((next[owner] as Map).c as List).addAll(members); return }
        List comps = [bundleComp] + members
        bound << ckey('bundle', b.id)
        Map p = pkgs[key] as Map
        next[key] = compact([o: 'bundle', n: b.name, v: null, g: p?.g ?: 1, url: b.sourceUrl, pv: b.isPrivate, c: comps])
    }
    // Regroup every non-bound component fresh from `seen` each pass, so an item deleted from the hub doesn't linger.
    // loc groups a component under url: only while its facts are valid; without usable facts it stands as 'unread'.
    Map grouped = [:]
    seen.each { String ck, Map c ->
        if (bound.contains(ck)) return
        String origin = classifyOrigin(c, false, false, factBasis(c, c.lm as long))
        String key = origin == 'url' ? "url:${c.loc}" : (origin == 'local' ? "local:${c.k}/${c.ns}/${c.nm}" : "unread:${c.k}/${c.ns}/${c.nm}")
        // origin url + app/driver: loc equals the key minus its "url:" prefix, so don't store it twice.
        Map storedC = (origin == 'url' && c.k != 'library') ? c.findAll { String k, v -> k != 'loc' } : c
        ((grouped[key] ?: (grouped[key] = [])) as List) << storedC
    }
    grouped.each { String key, List comps ->
        Map oldP = pkgs[key] as Map
        next[key] = compact([o: key.substring(0, key.indexOf(':')), n: (comps[0] as Map).nm, v: null, g: oldP?.g ?: 1, c: comps])
    }
    // Documents: write for new packages or when the component set changed (compare by kind:id set). An unread package has no manifest yet.
    Map docsBefore = (hubFiles ?: []).findAll { Map f -> (f.name as String)?.startsWith("cm_") }.collectEntries { Map f -> [(f.name as String): f] }
    next.each { String key, Map p ->
        if (p.o == 'hpm' || p.o == 'unread') return
        Set ids = (p.c as List).collect { Map c -> ckey(c.k as String, c.id) } as Set
        Set oldIds = ((pkgs[key] as Map)?.c as List ?: []).collect { Map c -> ckey(c.k as String, c.id) } as Set
        // hubFiles null = no usable listing: don't read "document absent" from it.
        if (ids != oldIds || (hubFiles != null && !docsBefore.containsKey(pkgFileName(key)))) {
            // A package back from the unread group (or re-created) continues the generation of the document it left behind.
            if (pkgs[key] == null && docsBefore.containsKey(pkgFileName(key))) {
                Object stored = readPkgDoc(key)?._cm?.gen
                if (stored != null) p.g = stored as int
            }
            p.g = ((p.g ?: 0) as int) + 1
            writePkgDoc(key, p, degenerateManifest(p.o as String, p.n as String, p.c as List, p.url as String))
        }
    }
    // A manifest document is removed only for a package key that left the registry in this pass, and only when none of its
    // former members stands in the unread group (they are expected back). Such held documents are remembered in state.
    // Own files only (cm_pkg_ prefix), read to confirm the key before deleting (an unregistered document is foreign).
    Map<String, List> oldMembers = [:]
    pkgs.each { String k, Map p -> oldMembers[k] = (p.c as List).findAll { Map c -> c.id != null }.collect { Map c -> ckey(c.k as String, c.id) } }
    ((state.heldDocs ?: [:]) as Map).each { k, v -> if (!oldMembers.containsKey(k as String)) oldMembers[k as String] = (v as List) }
    Set<String> unreadCks = next.values().findAll { Map p -> p.o == 'unread' }.collectMany { Map p -> (p.c as List).collect { Map c -> ckey(c.k as String, c.id) } } as Set
    Map decision = docPruneDecision(oldMembers, next.keySet() as Set, unreadCks)
    Map<String, List> hold = (decision.hold as Map).collectEntries { k, v -> [(k): v] } as Map
    (decision.prune as List).each { String k ->
        String name = pkgFileName(k)
        if (hubFiles == null) { hold[k] = oldMembers[k]; return }          // no usable listing: try again next pass
        if (!docsBefore.containsKey(name)) return
        try {
            Map doc = readPkgDoc(k)
            if (doc != null) { deleteHubFile(name); logInfo "inventory: removed stale manifest document ${name} (package ${k} no longer exists)" }
        } catch (Exception e) { logError "prune stale manifest ${name}: ${e.message}" }
    }
    if (hold != ((state.heldDocs ?: [:]) as Map)) state.heldDocs = hold
    if (next != pkgs) state.pkgs = next
    state.lastInventory = [at: now(), asOf: bulk.asOf, items: seen.size()]
    logInfo "inventory: ${seen.size()} items, ${bundles.size()} bundles, ${next.size()} packages in ${now() - t0}ms"
    return [items: seen.size(), ms: now() - t0]
}

// ===== HPM import (DESIGN.md §8.1–8.2, HPM-PARITY.md §2) =====

// HPM is singleInstance (Package_Manager.groovy:77): usedBy has at most one instance.
// Return shapes distinguish "list fetched, HPM absent" ([absent: true]) from "fetch failed" (null,
// M1) — a transient fetchUserAppTypes() failure must never be read as "HPM is not installed".
Map findHpm() {
    Object raw = hubRequest(USER_APP_TYPES_PATH, "user app types", "json", 15)
    if (!(raw instanceof List)) return null
    Map type = (raw as List).find { Map a -> a.namespace == "dcm.hpm" } as Map
    if (!type) return [absent: true]
    String instanceId = ((type.usedBy as List) ? ((type.usedBy as List)[0] as Map).id : null)?.toString()
    Map out = [typeId: type.id?.toString(), instanceId: instanceId, disabled: null, autoUpdateMode: null]
    if (instanceId) {
        Map wrap = hubMapRequest("/installedapp/statusJson/${instanceId}", "HPM status", 30)
        if (wrap.ok) {
            out.disabled = ((wrap.data.installedApp ?: [:]) as Map).disabled == true
            Map setting = ((wrap.data.appSettings ?: []) as List).find { Map s -> s.name == "autoUpdateMode" } as Map
            out.autoUpdateMode = setting?.value ?: null
            out._statusJson = wrap.data
        }
    }
    return out
}

Map apiHpmStatus() {
    Map h = findHpm()
    Map st = (state.hpm ?: [:]) as Map
    if (h == null) return jsonResponse([mode: st.mode ?: 'unknown', error: "HPM status unavailable"])
    if (h.absent == true) { state.hpm = [mode: 'absent']; return jsonResponse([mode: 'absent']) }
    return jsonResponse([mode: st.mode ?: 'observe', typeId: h.typeId, instanceId: h.instanceId, disabled: h.disabled, autoUpdateMode: h.autoUpdateMode,
                         importedAt: st.importedAt, packages: st.packages ?: 0, dangling: st.dangling ?: 0])
}

Map apiHpmImport() {
    Map h = findHpm()
    if (h == null) return jsonResponse([success: false, error: "HPM status unavailable"])
    Map r = importHpm(h)
    if (r) reconcileNow()                                  // re-binds the imported packages' bundles/files to the hub
    return jsonResponse(r ? [success: true] + r : [success: false, error: "HPM not installed"])
}

// One-to-one copy of HPM's state.manifests into origin-hpm packages. Idempotent; nothing is written to HPM.
Map importHpm(Map h = null) {
    h = h ?: findHpm()
    if (!h?._statusJson) return null
    List appState = (h._statusJson.appState ?: []) as List
    Map manifests = (appState.find { Map e -> e.name == "manifests" } as Map)?.value as Map ?: [:]
    Map customRepos = (appState.find { Map e -> e.name == "customRepositories" } as Map)?.value as Map ?: [:]
    List appSettings = (h._statusJson.appSettings ?: []) as List
    List subscribed = parseHpmListSetting(appSettings.find { Map s -> s.name == "installedRepositories" } as Map)
    List betaOn = parseHpmListSetting(appSettings.find { Map s -> s.name == "pkgBetaOn" } as Map)
    List autoUpdate = parseHpmListSetting(appSettings.find { Map s -> s.name == "appsToAutoUpdate" } as Map)

    Set liveApps = fetchUserAppTypes().collect { Map a -> a.id as String } as Set
    Set liveDrvs = fetchUserDriverTypes().collect { Map d -> d.id as String } as Set
    Map pkgs = (state.pkgs ?: [:]) as Map
    Map prevByCk = [:]
    pkgs.each { String k, Map p -> (p.c as List).each { Map c -> if (c.id) prevByCk[ckey(c.k as String, c.id)] = c } }

    int dangling = 0, components = 0
    Set hpmBound = [] as Set
    Map next = pkgs.findAll { String k, Map p -> p.o != 'hpm' }           // rebuild all hpm packages from HPM's truth
    manifests.each { String url, Map m ->
        List comps = []
        // required unless explicitly false (HPM-PARITY.md 2); HPM itself treats a missing field as
        // neither required nor optional — 0.3's install path decides what to do with that case.
        [['app', 'apps'], ['driver', 'drivers']].each { List pair ->
            String kind = pair[0], arr = pair[1]
            ((m[arr] ?: []) as List).each { Map item ->
                String heId = item.heID?.toString()
                boolean live = heId && ((kind == 'app' ? liveApps : liveDrvs).contains(heId))
                if (heId && !live) dangling++
                Map prev = live ? (prevByCk[ckey(kind, heId)] as Map) : null
                Map c = compact([k: kind, id: live ? heId : null, ns: item.namespace, nm: item.name, loc: item.location, mid: item.id, req: item.required != false && item.required != "false",
                         beta: item.beta == true, ih: prev?.ih, ah: prev?.ah, iv: prev?.iv ?: item.version, ivs: prev?.ivs, lm: prev?.lm, u: prev?.u ?: 0, pv: prev?.pv == true, bm: prev?.bm,
                         flm: prev?.flm, frd: prev?.frd, sz: prev?.sz, ch: prev?.ch, ff: prev?.ff, fnext: prev?.fnext, fe: prev?.fe])
                if (live) { hpmBound << ckey(kind, heId); components++ }
                comps << c
            }
        }
        ((m.bundles ?: []) as List).each { Map b -> comps << compact([k: 'bundle', id: null, ns: b.namespace, nm: b.name, loc: b.location, mid: b.id, req: b.required != false && b.required != "false", beta: b.beta == true]) }
        ((m.files ?: []) as List).each { Map f -> comps << compact([k: 'file', id: f.name, ns: null, nm: f.name, loc: f.location, mid: f.id, req: f.required != false && f.required != "false"]) }
        Map old = pkgs[url] as Map
        Map p = compact([o: 'hpm', n: m.packageName, v: m.version, beta: m.beta == true, g: old?.g ?: 1, c: comps, auto: autoUpdate.contains(url)])
        // document: the manifest as HPM holds it (HPM already stripped releaseNotes/licenseFile; we keep what is there)
        Set ids = comps.collect { Map c -> "${c.k}:${c.mid ?: c.id}".toString() } as Set
        Set oldIds = ((old?.c ?: []) as List).collect { Map c -> "${c.k}:${c.mid ?: c.id}".toString() } as Set
        if (old == null || ids != oldIds || old.v != p.v) { p.g = ((old?.g ?: 0) as int) + 1; writePkgDoc(url, p, m) }
        next[url] = p
    }
    // A url/local package whose component HPM now claims is folded into the HPM package (DESIGN.md §2.2).
    next.each { String k, Map p -> if (p.o != 'hpm') p.c = (p.c as List).findAll { Map c -> !hpmBound.contains(ckey(c.k as String, c.id)) } }
    next = next.findAll { String k, Map p -> p.o == 'hpm' || !(p.c as List).isEmpty() }

    state.pkgs = next
    state.repos = [subscribed: subscribed, custom: customRepos]
    state.betaOn = betaOn
    Map prevHpm = (state.hpm ?: [:]) as Map
    state.hpm = [mode: prevHpm.mode == 'manage' ? 'manage' : 'observe', typeId: h.typeId, instanceId: h.instanceId, importedAt: now(),
                 packages: manifests.size(), dangling: dangling, disabled: h.disabled, autoUpdateMode: h.autoUpdateMode]
    writeCmFile(HPM_IMPORT_FILE, JsonOutput.toJson([importedAt: now(), instanceId: h.instanceId, manifests: manifests, installedRepositories: subscribed, customRepositories: customRepos, pkgBetaOn: betaOn, appsToAutoUpdate: autoUpdate]))
    logInfo "HPM import: ${manifests.size()} packages, ${components} bound components, ${dangling} dangling, ${subscribed.size()} repositories"
    return [packages: manifests.size(), components: components, dangling: dangling, repos: subscribed.size()]
}

void initialHpmImport() {
    Map h = findHpm()
    if (h == null) return                       // fetch failed; the daily sweep retries
    if (h.absent == true) { state.hpm = [mode: 'absent'] } else { importHpm(h); reconcileNow() }
}

// HPM enum-multiple settings are stored as a JSON array string or a comma list, depending on firmware.
@CompileStatic
static List parseHpmListSetting(Map setting) {
    Object v = setting?.value
    if (v == null) return []
    if (v instanceof List) return (List) v
    String s = v.toString().trim()
    if (s.startsWith("[")) { try { return (List) new JsonSlurper().parseText(s) } catch (Exception ignored) {} }
    return s ? s.split(',\\s*').toList() : []
}

// Single source of truth for a component's status; shared by buildInventoryPayload and finalizeSweep (I6).
// p = package map, c = component map, s = the component's own sweep entry (null or [:] if none — both
// tolerated), pe = the package row's sweep entry ([:] if none — HPM manifest fetch result, or empty
// for non-hpm origins).
static String componentStatus(Map p, Map c, Map s, Map pe) {
    // Declared by the package but absent from the hub. An HPM manifest declares every component the
    // package *can* install and most are optional, so an optional one that was never installed is a
    // normal state, not a defect — only a required one missing is.
    if (c.id == null) return (c.req == false) ? 'not-installed' : 'missing'
    if (c.pv) return 'private'
    // A component whose facts are not usable (never read, or changed since the read) is not judged against an upstream.
    // HPM components are judged by the package manifest, which needs no per-item fact.
    if (p.o != 'hpm' && (p.o == 'unread' || c.basis == 'unread' || c.basis == 'stale')) return 'unknown'
    Map ss = s ?: [:]
    String status
    if (p.o == 'local') status = 'unmanaged'                  // no import URL: there is nothing upstream to compare against
    else if (p.o == 'hpm' && ss.isEmpty()) {
        // HPM component with no own sweep entry (manifest unchanged, body never fetched): derive
        // from the package manifest's declared version instead of a per-component hash compare.
        // A manifest that answered 404/410 or has struck out never reads as current (I4).
        boolean deadManifest = pe.broken == true || (pe.httpStatus in [404, 410])
        Integer cmp = compareVersions(p.v as String, pe.rver as String)
        status = deadManifest ? 'unknown' : ((cmp != null && cmp < 0) ? 'update-available' : ((pe.checkedAt && (!pe.rver || (cmp != null && cmp >= 0))) ? 'current' : 'unknown'))
    }
    else {
        boolean localMod = isLocallyModified(c.ih as String, c.ah as String, ss.rhash as String)
        status = freshnessStatus(c.ih as String, ss.rhash as String, c.iv as String, ss.rver as String, localMod)
    }
    if (ss.broken == true) status = 'broken-source'
    // the package's own manifest location has struck out (3 consecutive 404/410) — every component
    // under it is broken-source regardless of its own location's health (I4).
    if (p.o == 'hpm' && pe.broken == true) status = 'broken-source'
    return status
}

// Package roll-up over its components' statuses. The neutral ones mean "nothing to compare here",
// which is not the same claim as "matches upstream" (I8), so a package made only of those is never
// Current. A required component that isn't on the hub outranks a pending update: the package is not
// fully installed, which is the rarer and more actionable fact.
static String packageStatus(String origin, List statuses, Map pe) {
    if (pe?.broken == true) return 'broken-source'
    if (statuses.contains('missing')) return 'incomplete'
    if (statuses.contains('update-available')) return 'update-available'
    if (statuses.contains('changed-upstream')) return 'changed-upstream'
    if (statuses.contains('broken-source')) return 'broken-source'
    if (statuses && statuses.every { it == 'not-installed' }) return 'not-installed'
    if (!statuses || statuses.every { it in ['unknown', 'unmanaged', 'private', 'not-installed'] }) return origin == 'local' ? 'unmanaged' : 'unknown'
    return 'current'
}

// GET /api/inventory — aggregator: registry + sweep cache (Task 6) + HPM mode (Task 5). No sources, no secrets.
Map buildInventoryPayload(Map bulk) {
    Map pkgs = (state.pkgs ?: [:]) as Map
    Map sweep = readSweepCache()                                  // Task 6; returns [:] until then
    // Usage and parent links come from the same bulk reads the registry was reconciled from; nothing here reads the hub.
    Map usedBy = [:]
    (bulk.apps as List ?: []).each { Map a -> usedBy[ckey('app', a.id)] = a.usedBy }
    (bulk.drivers as List ?: []).each { Map d -> usedBy[ckey('driver', d.id)] = d.usedBy }
    (libraries().list as List ?: []).each { Map l -> usedBy[ckey('library', l.id)] = l.usedBy }
    Map<String, List<Map>> childOf = childOfIndex(bulk.appsTree as List, bulk.devices as List)
    Long invAsOf = (bulk.asOf ?: (state.lastInventory?.asOf ?: (state.lastInventory?.at ?: 0L))) as Long   // never newer than the data behind the payload
    Map factCounts = [total: 0, valid: 0, stale: 0, unread: 0, failing: 0, large: 0]
    List comps = []
    List packages = pkgs.collect { String key, Map p ->
        Map pe = (sweep["pkg:" + key] ?: [:]) as Map                       // package-row sweep entry (Task 6)
        List pc = (p.c as List).collect { Map c ->
            Map s = (sweep[ckey(c.k as String, c.id)] ?: [:]) as Map
            // A per-item fact is shown only while it still describes the item (DESIGN.md 3.1). Bundles and files carry none.
            boolean isItem = c.k in ['app', 'driver', 'library']
            String basis = (c.id != null && isItem) ? factBasis(c, (c.lm ?: 0L) as long) : null
            boolean usable = basis == null || basis == 'valid' || basis == 'provisional'
            if (basis != null) {
                factCounts.total++
                if (usable) factCounts.valid++ else factCounts[basis]++
                if ((c.ff ?: 0) > 0) factCounts.failing++
                if ((c.sz ?: 0) > LARGE_SOURCE_BYTES) factCounts.large++
            }
            String status = componentStatus(p, basis ? c + [basis: basis] : c, s, pe)
            // origin-url app/driver components don't store loc — reconstruct it from the key (see reconcileRegistry)
            String location = factVisible(p.o as String, basis, 'loc') ? (c.loc ?: (p.o == 'url' ? key.substring(4) : null)) : null
            Map row = [key: key, kind: c.k, hubId: c.id, namespace: c.ns, name: c.nm, origin: p.o, location: location, managedBy: p.o == 'hpm' ? (state.hpm?.mode == 'manage' ? 'cm' : 'hpm') : 'cm',
                       status: status, ihash: factVisible(p.o as String, basis, 'ih') ? c.ih : null, ahash: c.ah, iver: factVisible(p.o as String, basis, 'iv') ? c.iv : null, iverSrc: factVisible(p.o as String, basis, 'ivs') ? c.ivs : null, rhash: s.rhash, rver: s.rver, checkedAtMs: s.checkedAt, changedAtMs: s.changedAt,
                       httpStatus: s.httpStatus, failCount: s.failCount ?: 0, error: s.error, lastModifiedMs: c.lm, usedByCount: c.u ?: 0, required: c.req != false,
                       usedBy: usedBy[ckey(c.k as String, c.id)] ?: [], bundleOf: c.bm, isPrivate: c.pv == true,
                       basis: basis, factsReadAtMs: c.frd, factError: c.fe, childOf: (c.id != null ? childOf[ckey(c.k as String, c.id)] : null) ?: [], asOf: invAsOf]
            comps << row
            return row
        }
            // HPM decides a package by its manifest version, so the files inside a bundle it installed
        // don't drive the package's status — an upstream file that moved without a manifest version
        // bump is real (the component says changed-upstream) but no HPM update would fetch it.
        List rollup = (p.o == 'hpm' ? pc.findAll { !it.bundleOf } : pc).collect { it.status }
        String pkgStatus = packageStatus(p.o as String, rollup, pe)
        [key: key, origin: p.o, name: p.n, version: p.v, gen: p.g, doc: p.o == 'unread' ? null : pkgFileName(key), isPrivate: p.pv == true, url: p.url, componentCount: pc.size(),
         kind: pc.size() == 1 ? (pc[0] as Map).kind : null,
         checkedAtMs: pe.checkedAt, rver: pe.rver, broken: pe.broken == true, error: pe.error, httpStatus: pe.httpStatus,
         status: pkgStatus]
    }
    Map hub = cachedFetch("hubData", 300_000L) { Map w = hubMapRequest(HUB_DATA_PATH, "hub data", 10); w.ok ? [name: w.data.name, firmware: w.data.version, model: w.data.model] : null } as Map ?: [:]
    Map summary = [packages: packages.size(), components: comps.size(),
                   byOrigin: comps.countBy { it.origin }, byStatus: comps.countBy { it.status }, byKind: comps.countBy { it.kind },
                   unused: comps.count { (it.usedByCount ?: 0) == 0 && it.kind != 'bundle' }]
    return [generatedAt: now(), asOf: invAsOf, libraries: [asOf: libraries().asOf, error: libraries().error], facts: factCounts, appVersion: CODE_VERSION, hub: hub, packages: packages, components: comps, summary: summary,
            hpm: (state.hpm ?: [mode: 'absent']), sweep: (state.sweep ?: [:]), lastInventory: (state.lastInventory ?: [:])]
}
// Reconciliation is idempotent but writes documents and bumps generations, so concurrent callers take turns.
Map reconcileNow(Map b = null) {
    Semaphore gate = gateFor("reconcile")
    if (!gate.tryAcquire(BULK_WAIT_MS, TimeUnit.MILLISECONDS)) return [skipped: true]
    try { flushFactsLocked(); return reconcileRegistry(b ?: bulk()) } finally { gate.release() }
}
Map apiInventory() {
    return timed("inventory") {
        Map b = bulk()
        reconcileNow(b)
        scheduleLibraryRefreshIfStale()
        Map payload = buildInventoryPayload(b)
        Map fc = payload.facts as Map
        if (((fc.unread ?: 0) as int) + ((fc.stale ?: 0) as int) > 0) startFactReader()   // only schedules the first read
        payload
    }
}

// ===== Fact reader (DESIGN.md §5.2) =====
// Reads per-item facts (importUrl, version, hash, size, child calls) in the background under the hub budget (5.1): one read
// in flight, FACT_GAP_MS between reads, none while a write is outstanding, large sources never, back-off after repeated
// failures. The source is hashed and dropped inside readFacts(); nothing of it is kept.

// Components worth a read now, newest-changed first, as registry keys. Excludes items backed off into the future, items
// whose recorded size is above the background limit, kinds that have no source and components not bound to a hub id.
@CompileStatic
static List<String> factQueue(List<Map> comps, long now) {
    List<Map> picked = comps.findAll { Map c ->
        c.id != null && (c.k == 'app' || c.k == 'driver' || c.k == 'library') &&
            ((c.sz ?: 0L) as long) <= LARGE_SOURCE_BYTES && ((c.fnext ?: 0L) as long) <= now &&
            factBasis(c, (c.lm ?: 0L) as long) != 'valid'
    }
    picked.sort { Map a, Map b -> ((b.lm ?: 0L) as long) <=> ((a.lm ?: 0L) as long) }
    return picked.collect { Map c -> ckey(c.k as String, c.id) }
}

// A failed read: counts, keeps the message, and from the FACT_FAIL_LIMIT-th failure in a row sets when to try again.
@CompileStatic
static Map recordFailure(Map c, String error, long now) {
    Map m = new LinkedHashMap(c)
    int ff = ((c.ff ?: 0) as int) + 1
    m.ff = ff
    m.fe = error?.take(160)
    if (ff >= FACT_FAIL_LIMIT) m.fnext = now + FACT_BACKOFF_MS[Math.min(ff - FACT_FAIL_LIMIT, FACT_BACKOFF_MS.size() - 1)]
    return m
}

// A good read: the facts replace the stored ones (a null fact is removed, an unlisted one is kept), the failure fields clear,
// and the read is stamped with the item's lastModified it was taken at and the time it was taken.
@CompileStatic
static Map recordSuccess(Map c, Map facts, long lm, long now) {
    Map m = new LinkedHashMap(c)
    m.remove('ff'); m.remove('fnext'); m.remove('fe')
    for (Object o in facts.entrySet()) {
        Map.Entry e = (Map.Entry) o
        if (e.value == null) m.remove(e.key) else m.put(e.key, e.value)
    }
    m.flm = lm
    m.frd = now
    if (m.ah == null && m.ih != null) m.ah = m.ih
    return m
}

// What a step does next. s = [writeOutstanding, waits, queueEmpty]. A step waits out a write (up to FACT_WRITE_WAIT_STEPS in a row),
// finishes when nothing is queued, and otherwise reads.
@CompileStatic
static String factNextAction(Map s) {
    if (s.writeOutstanding == true) return ((s.waits ?: 0) as int) + 1 > FACT_WRITE_WAIT_STEPS ? 'giveup' : 'defer'
    return s.queueEmpty == true ? 'finish' : 'read'
}

// A fact read within PROVISIONAL_MS of its item's lastModified is read once more, so a second save in the same second is not missed.
@CompileStatic
static boolean factNeedsRetry(long lm, long readAt, boolean alreadyRetried) {
    return !alreadyRetried && (readAt - lm) < PROVISIONAL_MS
}

// A running reader job whose last step is older than this has lost its chain of steps (an error ended it, or the hub restarted under it).
@CompileStatic
static boolean factJobStale(long lastStep, long now) {
    return (now - lastStep) > 5 * FACT_GAP_MS + FACT_READ_TIMEOUT_MS
}

private boolean writeOutstanding() { return WRITE.availablePermits() == 0 }

// Read results wait here (facts and counters only, never a source) and are written to state.pkgs in batches:
// every FACT_FLUSH_EVERY reads, at job end and on job failure. reconcileNow() and the status/read endpoints flush first.
@Field static final ConcurrentHashMap<String, Map> PENDING_FACTS = new ConcurrentHashMap<>()
@Field static final List<String> FACT_KEYS = ['loc', 'ih', 'ah', 'iv', 'ivs', 'sz', 'ch', 'pv', 'flm', 'frd', 'ff', 'fnext', 'fe']

private Map withPending(Map c) {
    Map pend = PENDING_FACTS[ckey(c.k as String, c.id)]
    return pend == null ? c : (pend + [lm: c.lm, u: c.u])
}

// Every bound app, driver and library in the registry, with its package origin (as `_o`) and any unflushed facts.
private List<Map> itemComponents() {
    List<Map> out = []
    ((state.pkgs ?: [:]) as Map).each { String key, Map p ->
        (p.c as List).each { Map c -> if (c.id != null && c.k in ['app', 'driver', 'library']) out << (withPending(c) + [_o: p.o]) }
    }
    return out
}

// One component by registry key, without copying the registry.
private Map findItem(String ck) {
    for (Map.Entry e in ((state.pkgs ?: [:]) as Map).entrySet()) {
        Map p = e.value as Map
        for (Object o in (p.c as List)) {
            Map c = o as Map
            if (c.id != null && c.k in ['app', 'driver', 'library'] && ckey(c.k as String, c.id) == ck) return withPending(c) + [_o: p.o]
        }
    }
    return null
}

// Queues one read's outcome. The component it is based on already carries any earlier unflushed outcome.
private void storeFact(String ck, Map base, long lm, Map outcome) {
    Map clean = base.findAll { k, v -> k != '_o' } as Map
    PENDING_FACTS[ck] = (outcome.facts != null ? recordSuccess(clean, outcome.facts as Map, lm, now()) : recordFailure(clean, outcome.error as String, now()))
}

// Writes the pending facts into state.pkgs. The caller holds the registry gate. Only the fact fields are copied, so a reconcile
// that changed anything else in the meantime is kept.
private void flushFactsLocked() {
    if (PENDING_FACTS.isEmpty()) return
    Map snap = new HashMap(PENDING_FACTS)
    Map pkgs = (state.pkgs ?: [:]) as Map
    Map newPkgs = null
    for (Map.Entry e in pkgs.entrySet()) {
        List comps = (e.value as Map).c as List
        List newComps = null
        for (int i = 0; i < comps.size(); i++) {
            Map c = comps[i] as Map
            if (c.id == null) continue
            Map pend = snap[ckey(c.k as String, c.id)] as Map
            if (pend == null) continue
            Map m = new LinkedHashMap(c)
            for (String k in FACT_KEYS) { if (pend[k] != null) m[k] = pend[k] else m.remove(k) }
            if (newComps == null) newComps = new ArrayList(comps)
            newComps[i] = m
        }
        if (newComps != null) {
            if (newPkgs == null) newPkgs = new LinkedHashMap(pkgs)
            newPkgs[e.key] = (e.value as Map) + [c: newComps]
        }
    }
    if (newPkgs != null) state.pkgs = newPkgs
    snap.each { k, v -> PENDING_FACTS.remove(k, v) }
}
// False when the registry is busy; the facts stay pending and go out with the next flush.
private boolean flushFacts() {
    if (PENDING_FACTS.isEmpty()) return true
    Semaphore gate = gateFor("reconcile")
    if (!gate.tryAcquire(2000L, TimeUnit.MILLISECONDS)) return false
    try { flushFactsLocked(); return true } finally { gate.release() }
}

// Facts from one read. The source lives only in this frame: it is hashed, measured and dropped.
private Map readFacts(String kind, String id, String origin, boolean background) {
    Map d = fetchItemDetail(kind, id, background)
    if (d == null) return [error: "read failed"]
    String src = d.source as String
    if (!src) return [error: "empty source"]
    Map v = parseVersionLiteral(src)
    boolean child = src.contains("addChildDevice") || src.contains("addChildApp")
    Map facts = [ih: hashPrefix(sourceHash(src)), sz: src.length(), ch: child ?: null, pv: d.isPrivate == true ? true : null]
    // An HPM item keeps the location and version its manifest declares; every other origin takes them from the source.
    if (origin != 'hpm') {
        facts.loc = d.importUrl ?: ((origin == 'url' && kind != 'library') ? '' : null)   // '' stops a url package re-deriving the location from its key
        facts.iv = v.ver; facts.ivs = v.src
    }
    return [facts: facts]
}

// Starts the reader unless one is running. Schedules the first read and returns; it never reads inside the caller.
void startFactReader() {
    Semaphore gate = gateFor("factStart")
    if (!gate.tryAcquire()) return
    try {
        for (Map.Entry e in JOBS.entrySet()) {
            ConcurrentHashMap r = e.value as ConcurrentHashMap
            if (r.kind != 'facts' || r.status != 'running') continue
            if (!factJobStale((r.lastStep ?: r.startedAt) as long, now())) return
            jobFail(e.key as String, "Job lost: the reader stopped taking steps")      // an error ended its chain; carry on with a new job
            flushFacts()
        }
        List<String> queue = factQueue(itemComponents(), now())
        if (queue.isEmpty()) return
        String jobId = newJob('facts', queue.size())
        ConcurrentHashMap j = JOBS[jobId]
        (j.pending as ConcurrentLinkedQueue).addAll(queue)
        j.counts = new ConcurrentHashMap([read: new AtomicInteger(0), failed: new AtomicInteger(0), skipped: new AtomicInteger(0)])
        j.retried = new ConcurrentHashMap()
        j.waits = new AtomicInteger(0)
        j.unflushed = new AtomicInteger(0)
        j.lastStep = now()
        runInMillis(1000L, 'factStep', [data: [jobId: jobId], overwrite: false])
        logSched "fact reader ${jobId}: ${queue.size()} queued"
    } finally { gate.release() }
}

// One read, then the next step FACT_GAP_MS later.
void factStep(Map data) {
    String jobId = data.jobId as String
    ConcurrentHashMap j = JOBS[jobId]
    if (j == null || j.status != 'running') return                 // lost to a push or a restart; the next start resumes
    j.lastStep = now()
    try {
        ConcurrentLinkedQueue<String> pending = j.pending as ConcurrentLinkedQueue<String>
        AtomicInteger waits = j.waits as AtomicInteger
        String action = factNextAction([writeOutstanding: writeOutstanding(), waits: waits.get(), queueEmpty: pending.isEmpty()])
        if (action == 'defer') {
            waits.incrementAndGet()
            runInMillis(FACT_GAP_MS, 'factStep', [data: [jobId: jobId], overwrite: false])
            return
        }
        if (action == 'giveup') { flushFacts(); jobFail(jobId, "gave up waiting for a write to finish"); return }
        if (action == 'finish') {
            flushFacts()
            Map counts = (j.counts as Map).collectEntries { k, v -> [(k): (v as AtomicInteger).get()] }
            jobDone(jobId, counts)
            logInfo "fact reader done: ${counts}"
            return
        }
        waits.set(0)
        String ck = pending.poll()
        Map c = findItem(ck)
        if (c != null && !factQueue([c], now()).isEmpty()) {          // an earlier read, a back-off or a size may have settled it
            long lm = (c.lm ?: 0L) as long
            Map outcome
            try { outcome = readFacts(c.k as String, c.id as String, c._o as String, true) }
            catch (Exception e) { outcome = [error: "${getObjectClassName(e)}: ${e.message}" as String] }
            storeFact(ck, c, lm, outcome)
            ((j.counts as Map)[outcome.facts != null ? 'read' : 'failed'] as AtomicInteger).incrementAndGet()
            if (outcome.facts != null && factNeedsRetry(lm, now(), (j.retried as Map).putIfAbsent(ck, true) != null)) {
                pending.add(ck); (j.total as AtomicInteger).incrementAndGet()
            }
            if ((j.unflushed as AtomicInteger).incrementAndGet() >= FACT_FLUSH_EVERY && flushFacts()) (j.unflushed as AtomicInteger).set(0)
        } else {
            ((j.counts as Map).skipped as AtomicInteger).incrementAndGet()
        }
        (j.processed as AtomicInteger).incrementAndGet()
        j.lastStep = now()
        runInMillis(FACT_GAP_MS, 'factStep', [data: [jobId: jobId], overwrite: false])
    } catch (Throwable e) {
        logError "fact reader ${jobId}: ${e.message}"
        try { flushFacts() } catch (Throwable ignored) {}
        jobFail(jobId, "fact reader failed: ${e.message}")
    }
}

// Fact counts as the inventory payload reports them (a provisional fact counts as valid: it is shown).
private Map factSummary() {
    Map counts = [total: 0, valid: 0, stale: 0, unread: 0, failing: 0, large: 0]
    itemComponents().each { Map c ->
        String basis = factBasis(c, (c.lm ?: 0L) as long)
        counts.total++
        if (basis == 'valid' || basis == 'provisional') counts.valid++ else counts[basis]++
        if ((c.ff ?: 0) > 0) counts.failing++
        if ((c.sz ?: 0) > LARGE_SOURCE_BYTES) counts.large++
    }
    return counts
}

Map apiFactsStatus() {
    String id = jobSlot('facts')?.id as String
    Map job = id ? jobStatus(id) : [status: 'none']
    return jsonResponse([jobId: id, status: job.status, processed: job.processed, total: job.total, startedAt: job.startedAt, finishedAt: job.finishedAt,
                         error: job.error, result: job.result, facts: factSummary(), gapMs: FACT_GAP_MS, largeSourceBytes: LARGE_SOURCE_BYTES])
}

// POST /api/facts/read?kind=&id= — the one per-item read an endpoint handler may make: the item the user acted on. Synchronous; ignores the size limit.
Map apiFactsRead() {
    String kind = params.kind as String, id = params.id as String
    if (!(kind in ['app', 'driver', 'library']) || !id?.isInteger()) return jsonResponse([error: "kind must be app|driver|library and id numeric"])
    return timed("factsRead") {
        String ck = ckey(kind, id)
        Map c = findItem(ck)
        if (c == null) return [error: "not in the registry: ${ck}" as String]
        long lm = (c.lm ?: 0L) as long
        Map outcome
        try { outcome = readFacts(kind, id, c._o as String, false) }         // counts as the request's one allowed read
        catch (Exception e) { outcome = [error: "${getObjectClassName(e)}: ${e.message}" as String] }
        storeFact(ck, c, lm, outcome)
        boolean stored = flushFacts()
        Map after = findItem(ck) ?: c
        [kind: kind, hubId: id, read: outcome.facts != null, stored: stored, error: outcome.error, basis: factBasis(after, lm), importUrl: after.loc ?: null,
         version: after.iv, hash: after.ih, size: after.sz, createsChildren: after.ch == true]
    }
}

// ===== Sweep (DESIGN.md §5.2, §7): bounded async freshness check over every upstream location =====
@Field static final int  SWEEP_MAX_INFLIGHT = 6          // two of the eight async slots stay free for endpoint handlers
@Field static final long SWEEP_TTL_MS = 86_400_000L
@Field static final int  SWEEP_WATCHDOG_SEC = 180
@Field static final int  BROKEN_AFTER = 3
@Field static final java.util.regex.Pattern SWEEP_STRIKE_ERROR_RE = ~/(?s).*(UnknownHostException|SSLHandshakeException|SSLException|Name or service not known).*/
@Field static final ConcurrentHashMap<String, Map> SWEEP = new ConcurrentHashMap<>()   // live mirror of cm_sweep.json
@Field static volatile boolean sweepLoaded = false

Map readSweepCache() {
    if (!sweepLoaded) {
        String t = readCmFile(SWEEP_FILE)
        if (t) { try { ((Map) new JsonSlurper().parseText(t)).each { k, v -> SWEEP[(String) k] = (Map) v } } catch (Exception ignored) {} }
        sweepLoaded = true
    }
    return new HashMap(SWEEP)
}
private void persistSweepCache() { writeCmFile(SWEEP_FILE, JsonOutput.toJson(SWEEP)) }

// force=1 ignores the TTL and queues every eligible item (manual "check now" / verification use;
// documented in the README by Task 9).
Map apiSweepStart() {
    Map r = startSweep(params.force == "1")
    return jsonResponse(r)
}

// Effective source location for a component: url-origin app/driver components don't store loc
// redundantly in state.pkgs (reconcileRegistry strips it to derive from the package key on read);
// reconstruct it the same way buildInventoryPayload does. Library/HPM/bundle components always
// carry loc directly on the stored component.
private String componentLoc(String key, Map p, Map c) { return (c.loc as String) ?: (p.o == 'url' ? key.substring(4) : null) }

// Builds the queue and fans out. Idempotent under concurrent triggers.
Map startSweep(boolean force = false) {
    Map prev = (state.sweep ?: [:]) as Map
    if (prev.status == 'running' && JOBS[prev.jobId as String]?.status == 'running') return [jobId: prev.jobId, total: prev.total, alreadyRunning: true]
    readSweepCache()
    // Import first, then inventory: importHpm() rebuilds HPM packages from HPM's own records, and the
    // inventory pass is what binds their bundle/file components to the hub and folds bundle members in.
    Map hpmProbe = findHpm()
    if (hpmProbe != null && hpmProbe.absent != true) importHpm(hpmProbe)
    reconcileNow()
    Map pkgs = (state.pkgs ?: [:]) as Map
    List<String> queue = []
    long cutoff = force ? Long.MAX_VALUE : now() - SWEEP_TTL_MS
    pkgs.each { String key, Map p ->
        if (p.o == 'hpm') { Map e = SWEEP["pkg:" + key]; if (!e || ((e.checkedAt ?: 0) as long) < cutoff) queue << ("pkg:" + key) }
        (p.c as List).each { Map c ->
            if (!c.id || c.pv || c.k == 'bundle' || c.k == 'file' || p.o == 'local' || p.o == 'unread') return
            // HPM component hashes refresh only when the manifest changed (see applyManifestResult) —
            // except members of a bundle folded into an HPM package, which carry their own upstream
            // file location and were swept as bundle components before the fold.
            if (p.o == 'hpm' && !c.bm) return
            String loc = componentLoc(key, p, c); if (!loc) return
            Map e = SWEEP[ckey(c.k as String, c.id)]
            if (!e || ((e.checkedAt ?: 0) as long) < cutoff) queue << ckey(c.k as String, c.id)
        }
    }
    String jobId = newJob('sweep', queue.size())
    ConcurrentHashMap j = JOBS[jobId]
    (j.pending as ConcurrentLinkedQueue).addAll(queue)
    j.counts = new ConcurrentHashMap([checked: new AtomicInteger(0), notModified: new AtomicInteger(0), changed: new AtomicInteger(0), broken: new AtomicInteger(0), unreachable: new AtomicInteger(0)])
    state.sweep = [jobId: jobId, status: 'running', total: queue.size(), processed: 0, startedAt: j.startedAt]
    runIn(SWEEP_WATCHDOG_SEC, 'sweepWatchdog', [data: [jobId: jobId]])
    if (queue.isEmpty()) { finalizeSweep(jobId); return [jobId: jobId, total: 0, alreadyRunning: false] }
    SWEEP_MAX_INFLIGHT.times { dispatchSweep(jobId) }
    logSched "sweep ${jobId}: ${queue.size()} queued"
    return [jobId: jobId, total: queue.size(), alreadyRunning: false]
}

// Locate a component or package by queue key.
private Map resolveTarget(String qk) {
    Map pkgs = (state.pkgs ?: [:]) as Map
    if (qk.startsWith("pkg:")) { String key = qk.substring(4); Map p = pkgs[key] as Map; return p ? [kind: 'pkg', key: key, loc: key, pkg: p] : null }
    for (Map.Entry e in pkgs.entrySet()) {
        String key = e.key as String; Map p = e.value as Map
        Map c = (p.c as List).find { Map c2 -> ckey(c2.k as String, c2.id) == qk } as Map
        if (c) return [kind: c.k, key: key, loc: componentLoc(key, p, c), comp: c, pkg: p]
    }
    return null
}

// CAS-bounded dispatch (HubDiagnostics audit pattern). Conditional GET via If-None-Match when an ETag is known.
private boolean dispatchSweep(String jobId) {
    ConcurrentHashMap j = JOBS[jobId]; if (j == null || j.status != 'running') return false
    AtomicInteger inFlight = j.inFlight as AtomicInteger
    while (true) { int n = inFlight.get(); if (n >= SWEEP_MAX_INFLIGHT) return false; if (inFlight.compareAndSet(n, n + 1)) break }
    String qk = (j.pending as ConcurrentLinkedQueue<String>).poll()
    if (qk == null) { inFlight.decrementAndGet(); return false }
    Map t = resolveTarget(qk)
    if (t == null || !(t.loc as String)?.startsWith("https://")) {       // http:// locations are not fetched (DESIGN.md §6.3)
        SWEEP[qk] = (SWEEP[qk] ?: [:]) + [checkedAt: now(), error: t == null ? 'not in registry' : 'non-https location', httpStatus: null]
        sweepStep(jobId, null); return true
    }
    Map entry = SWEEP[qk] ?: [:]
    Map headers = entry.etag ? ['If-None-Match': entry.etag] : [:]
    asynchttpGet('sweepCallback', [uri: t.loc, contentType: 'text/plain', headers: headers, timeout: 20], [jobId: jobId, qk: qk])
    return true
}

void sweepCallback(resp, data) {
    String jobId = data.jobId as String, qk = data.qk as String
    ConcurrentHashMap j = JOBS[jobId]; if (j == null) return
    Map entry = new HashMap(SWEEP[qk] ?: [:])
    int status = resp?.status ?: 0
    String outcome = 'checked'
    try {
        if (status == 304) { entry.checkedAt = now(); entry.httpStatus = 304; entry.failCount = 0; entry.unreachable = false; entry.error = null; outcome = 'notModified' }
        else if (status == 200) {
            String body = (resp.data ?: '') as String
            if (!body) {                                                  // an empty 200 body is not a valid source snapshot — never hash ''
                entry.httpStatus = 200; entry.checkedAt = now(); entry.unreachable = true; entry.error = 'empty response body'; outcome = 'unreachable'
            } else {
                // resp.headers may be a Map or a List of key/value entries depending on firmware; either
                // iterates as Map.Entry-shaped items under Groovy's find{}, and the name is matched
                // case-insensitively since some servers/CDNs send it lowercased.
                String etag = resp.headers?.find { it.key?.toString()?.equalsIgnoreCase('ETag') }?.value?.toString()
                entry.etag = etag; entry.httpStatus = 200; entry.checkedAt = now(); entry.failCount = 0; entry.unreachable = false; entry.broken = false; entry.error = null
                if (qk.startsWith("pkg:")) outcome = applyManifestResult(jobId, qk, entry, body)
                else {
                    // rhash stored as the same 16-hex prefix as ih/ah (DESIGN.md §2.4 state-size budget) so freshnessStatus compares like with like
                    String rh = hashPrefix(sourceHash(body))
                    if (entry.rhash != rh) { entry.changedAt = now(); outcome = 'changed' }
                    entry.rhash = rh
                    Map pv = parseVersionLiteral(body); entry.rver = pv.ver; entry.rverSrc = pv.src
                }
            }
        } else if (status in [404, 410]) {                                 // definite "gone": strikes toward broken-source
            entry.httpStatus = status; entry.checkedAt = now(); entry.error = resp?.errorMessage ?: "HTTP ${status}"
            entry.failCount = ((entry.failCount ?: 0) as int) + 1
            if ((entry.failCount as int) >= BROKEN_AFTER) { entry.broken = true; outcome = 'broken' }
        } else if (status == 0 && resp?.hasError()) {
            // DNS/TLS failures mean the host itself is gone or misconfigured: strike. Everything else at
            // this branch (timeouts, connect resets) is transient network noise: no strike (DESIGN.md §7).
            String errMsg = (resp?.errorMessage ?: resp?.errorData?.toString() ?: '') as String
            entry.httpStatus = null; entry.checkedAt = now(); entry.error = errMsg ?: 'transport error'
            if (SWEEP_STRIKE_ERROR_RE.matcher(errMsg).matches()) {
                entry.failCount = ((entry.failCount ?: 0) as int) + 1
                if ((entry.failCount as int) >= BROKEN_AFTER) { entry.broken = true; outcome = 'broken' }
            } else {
                entry.unreachable = true; outcome = 'unreachable'
            }
        } else {                                                            // 5xx, 429: transient, no strike
            entry.httpStatus = status ?: null; entry.checkedAt = now(); entry.unreachable = true; entry.error = "HTTP ${status}"; outcome = 'unreachable'
        }
    } catch (Exception e) { entry.error = "${getObjectClassName(e)}: ${e.message}"; entry.checkedAt = now(); outcome = 'unreachable' }
    SWEEP[qk] = entry
    sweepStep(jobId, outcome)
}

// HPM package manifest result: compare declared versions; refresh component hashes only when the manifest changed.
private String applyManifestResult(String jobId, String qk, Map entry, String body) {
    String key = qk.substring(4)
    Map m = null
    try { m = (Map) new JsonSlurper().parseText(body) } catch (Exception e) { entry.error = "manifest parse: ${e.message}"; entry.broken = false; return 'unreachable' }
    Map p = ((state.pkgs ?: [:]) as Map)[key] as Map
    // manifest rhash stored as the same 16-hex prefix as component rhash (ruling: hash prefixes)
    String rh = hashPrefix(sha256Hex(body))
    boolean changed = entry.rhash != rh
    entry.rhash = rh; entry.rver = m.version?.toString(); entry.rverSrc = 'manifest'; entry.rbeta = m.betaVersion?.toString()
    if (changed) {
        entry.changedAt = now()
        // queue this package's components for a body fetch (their own entries decide update/changed by hash)
        ConcurrentHashMap j = JOBS[jobId]
        if (j != null && p) (p.c as List).each { Map c -> if (c.id && c.loc && !c.pv && c.k in ['app', 'driver', 'library']) { (j.pending as ConcurrentLinkedQueue).add(ckey(c.k as String, c.id)); (j.total as AtomicInteger).addAndGet(1) } }
    }
    return changed ? 'changed' : 'checked'
}

// Per-callback bookkeeping; dispatches the next item or finalizes. processed is incremented before inFlight is released.
private void sweepStep(String jobId, String outcome) {
    ConcurrentHashMap j = JOBS[jobId]; if (j == null) return
    if (outcome) ((j.counts as Map)[outcome] as AtomicInteger)?.incrementAndGet()
    int processed = (j.processed as AtomicInteger).incrementAndGet()
    int inFlight = (j.inFlight as AtomicInteger).decrementAndGet()
    if (!(j.pending as ConcurrentLinkedQueue).isEmpty()) dispatchSweep(jobId)
    else if (inFlight == 0 && (j.processed as AtomicInteger).get() >= (j.total as AtomicInteger).get()) finalizeSweep(jobId)
}

void sweepWatchdog(Map data) {
    String jobId = data.jobId as String
    ConcurrentHashMap j = JOBS[jobId]
    if (j != null && j.status == 'running') {
        logWarn "sweep ${jobId} watchdog: ${(j.processed as AtomicInteger).get()}/${(j.total as AtomicInteger).get()} after ${SWEEP_WATCHDOG_SEC}s; finalizing with what completed"
        (j.pending as ConcurrentLinkedQueue).clear()          // stop the fan-out before finalizing so no late dispatch races the finalize
        finalizeSweep(jobId)
    }
}

// Detect local modifications (installed hash moved since adoption), compute package statuses, persist, badge, notify.
private void finalizeSweep(String jobId) {
    ConcurrentHashMap j = JOBS[jobId]; if (j == null || j.status != 'running') return
    unschedule('sweepWatchdog')
    Map pkgs = (state.pkgs ?: [:]) as Map
    int updates = 0, broken = 0
    pkgs.each { String key, Map p ->
        Map pe = (SWEEP["pkg:" + key] ?: [:]) as Map
        boolean pkgHasUpdate = false
        (p.c as List).each { Map c ->
            if (!c.id || c.k in ['bundle', 'file']) return
            String ck = ckey(c.k as String, c.id)
            Map e = SWEEP[ck]                                   // may be null — an HPM component with no own entry; componentStatus tolerates it (I6)
            String st = componentStatus(p, c, e, pe)
            if (st == 'update-available') pkgHasUpdate = true
            if (e?.broken == true) broken++
        }
        if (pkgHasUpdate) updates++                            // updates counts packages with >=1 update-available component, not components
    }
    persistSweepCache()
    Map counts = (j.counts as Map).collectEntries { k, v -> [(k): (v as AtomicInteger).get()] }
    int total = (j.total as AtomicInteger).get()
    // counts also has an outcome key literally named 'broken' (how many callbacks flipped an item to
    // broken-source this run) — merge it first so the registry-wide updates/broken totals below win
    Map result = counts + [queued: total, updates: updates, broken: broken]
    jobDone(jobId, result)
    state.sweep = [jobId: jobId, status: 'done', total: total, processed: (j.processed as AtomicInteger).get(), startedAt: j.startedAt, finishedAt: now(), updates: updates, broken: broken]
    boolean had = state.updatesAvailable == true
    state.updatesAvailable = updates > 0
    refreshUpdateLabel()
    if (updates > 0 && !had && settings.notifyDevices && state.updatesNotified != true) {
        settings.notifyDevices.each { d ->
            try { d.deviceNotification("Code Manager: ${updates} package update${updates == 1 ? '' : 's'} available${broken ? ", ${broken} broken source${broken == 1 ? '' : 's'}" : ''}") }
            catch (Exception e) { logWarn "notify ${d}: ${e.message}" }
        }
        state.updatesNotified = true
    }
    if (updates == 0) state.updatesNotified = false
    logInfo "sweep done: ${result}"
}

// ===== Item source and export (DESIGN.md §9) =====
Map apiItemSource() {
    String kind = params.kind as String, id = params.id as String
    if (!(kind in ['app', 'driver', 'library']) || !id?.isInteger()) return jsonResponse([error: "kind must be app|driver|library and id numeric"])
    String path = "/${kind}/ajax/code"
    Object resp = hubRequest(path, "${kind} ${id} source", "json", 30, [id: id])
    if (!(resp instanceof Map) || ((Map) resp).error || ((Map) resp).source == null) return jsonResponse([error: "no source for ${kind} ${id}"])
    Map d = (Map) resp
    String source = d.source as String
    Map t = resolveTarget("${kind}:${id}")
    Map c = t?.comp as Map
    String cur = hashPrefix(sourceHash(source))
    Map pv = parseVersionLiteral(source)
    // same rule as the inventory status (C1): a re-import that lands back on the upstream hash is
    // Current again, not modified-locally forever.
    Map sw = readSweepCache()
    boolean modifiedLocally = isLocallyModified(cur, c?.ah as String, (sw[ckey(kind, id)] as Map)?.rhash as String)
    return jsonResponse([kind: kind, hubId: id, lock: d.version, ihash: c?.ih, ahash: c?.ah, currentHash: cur, modifiedLocally: modifiedLocally,
                         version: pv.ver, versionSrc: pv.src, location: c?.loc, packageKey: t?.key, source: source])
}

Map apiExport() {
    Map pkgs = (state.pkgs ?: [:]) as Map
    List docs = pkgs.keySet().collect { String key -> [name: pkgFileName(key), key: key, doc: readPkgDoc(key)] }
    Map out = [exportedAt: now(), appVersion: CODE_VERSION, registry: pkgs, documents: docs, sweep: readSweepCache(), hpm: state.hpm ?: [mode: 'absent'],
               repos: state.repos ?: [:], betaOn: state.betaOn ?: []]
    writeCmFile("cm_registry_export.json", JsonOutput.toJson(out))
    return jsonResponse(out)
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
    REQUESTS_ACTIVE.incrementAndGet()
    Map data
    try { data = body.call() } finally { REQUESTS_ACTIVE.decrementAndGet() }
    long elapsed = now() - start
    API_TIMINGS[name] = elapsed
    logDebug "api ${name} completed in ${elapsed}ms"
    return jsonResponse(data)
}
Map apiStats() {
    Map fetches = [:]
    BULK_FETCHES.each { String k, AtomicInteger v -> fetches[k] = v.get() }
    return jsonResponse([version: CODE_VERSION, timingsMs: new HashMap(API_TIMINGS), jobs: JOBS.keySet().toList(),
                         bulkFetches: fetches, perItemReadsInRequest: PER_ITEM_READS_IN_REQUEST.get()])
}

// ===== Jobs (§10): start/status with terminal status 'done' =====
String newJob(String kind, int total) {
    String id = "${kind}-${now()}-${(int) (Math.random() * 9999)}"
    ConcurrentHashMap j = new ConcurrentHashMap()
    j.kind = kind; j.total = new AtomicInteger(total); j.startedAt = now(); j.status = 'running'
    j.processed = new AtomicInteger(0); j.inFlight = new AtomicInteger(0)
    j.pending = new ConcurrentLinkedQueue<String>()
    JOBS[id] = j
    saveJobSnapshot(kind, [id: id, kind: kind, status: 'running', total: total, startedAt: j.startedAt])   // scalar snapshot survives a push
    return id
}
void jobDone(String jobId, Map result) {
    ConcurrentHashMap j = JOBS[jobId]; if (j == null) return
    j.status = 'done'; j.result = result ?: [:]; j.finishedAt = now()
    saveJobSnapshot(j.kind as String, [id: jobId, kind: j.kind, status: 'done', total: (j.total as AtomicInteger).get(), startedAt: j.startedAt, finishedAt: j.finishedAt])
}
void jobFail(String jobId, String error) {
    ConcurrentHashMap j = JOBS[jobId]; if (j == null) return
    j.status = 'error'; j.error = error; j.finishedAt = now()
    saveJobSnapshot(j.kind as String, [id: jobId, kind: j.kind, status: 'error', error: error, total: (j.total as AtomicInteger).get(), startedAt: j.startedAt, finishedAt: j.finishedAt])
}
// Each job kind has its own snapshot slot, so a library refresh, a fact read and a sweep never overwrite one another's status.
private void saveJobSnapshot(String kind, Map snap) {
    Map slots = new LinkedHashMap((state.jobSlots ?: [:]) as Map)
    slots[kind] = snap
    state.jobSlots = slots
}
private Map jobSlot(String kind) { return ((state.jobSlots ?: [:]) as Map)[kind] as Map }
Map jobStatus(String jobId) {
    ConcurrentHashMap j = JOBS[jobId]
    if (j == null) {
        Map snap = jobSlot(jobId.contains('-') ? jobId.substring(0, jobId.indexOf('-')) : jobId) ?: [:]
        if (snap.id == jobId) return (snap.status == 'running') ? snap + [status: 'error', error: 'Job lost to hub restart or code push'] : snap
        return [id: jobId, status: 'unknown']
    }
    return [id: jobId, kind: j.kind, status: j.status, processed: (j.processed as AtomicInteger).get(), total: (j.total as AtomicInteger).get(),
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
void installed() { logCfg "installed"; state.installed = true; if (!state.accessToken) checkOAuth(); runIn(1, 'syncUIForced'); runIn(60, 'startFactReader'); initialize() }
void updated() {
    logCfg "updated"
    state.installed = true
    unsubscribe(); unschedule()
    cacheClear()
    uiVersionCache = null
    if (settings.debugEnable) runIn(1800, 'logsOff')
    runIn(1, 'syncUIForced')
    // unschedule() above stopped any running reader's chain of steps; its job can never finish.
    JOBS.each { String id, ConcurrentHashMap j -> if (j.kind == 'facts' && j.status == 'running') jobFail(id, 'Job lost: the app was re-initialized') }
    flushFacts()
    runIn(60, 'startFactReader')
    initialize()
}
void uninstalled() { unschedule(); unsubscribe(); logCfg "uninstalled" }
void logsOff() { app.updateSetting("debugEnable", [type: "bool", value: false]); app.updateSetting("traceEnable", [type: "bool", value: false]); logCfg "debug/trace logging auto-disabled" }

void initialize() {
    if (state.version != CODE_VERSION) { logVer "New version: ${CODE_VERSION} (was: ${state.version})"; state.version = CODE_VERSION }
    // A job that was running when the class was replaced can never finish: mark it.
    state.remove('lastJob')                                   // single slot of 0.1, replaced by per-kind slots
    ((state.jobSlots ?: [:]) as Map).each { k, Map snap ->
        if (snap.status == 'running' && !JOBS[snap.id as String]) saveJobSnapshot(k as String, snap + [status: 'error', error: 'Job lost to hub restart or code push'])
    }
    Map sw = (state.sweep ?: [:]) as Map
    if (sw.status == 'running' && JOBS[sw.jobId as String]?.status != 'running') state.sweep = sw + [status: 'stale']
    schedule("0 17 3 * * ?", "scheduledUISync")
    schedule("0 41 3 * * ?", "scheduledVersionCheck")
    subscribe(location, "systemStart", "systemStartHandler")
    refreshUpdateLabel()
    if (!state.hpm) runIn(30, 'initialHpmImport')
    logSched "daily UI sync and version check scheduled"
}
void systemStartHandler(evt) { runIn(300, 'startFactReader') }

// ===== Version heuristics (DESIGN.md §4) — self-contained, extracted by tests/test-version-compare.groovy =====

// Ordered regex list over the first 400 lines; the first hit wins and the matched idiom is recorded.
@CompileStatic
static Map parseVersionLiteral(String source) {
    if (!source) return [ver: null, src: null]
    List<String> lines = source.readLines()
    String head = (lines.size() > 400 ? lines.subList(0, 400) : lines).join('\n')
    List<List> rules = [
        ['field',      /(?m)@Field\s+(?:static\s+)?(?:final\s+)?(?:String\s+|def\s+)?\w*(?:[vV]ersion|VERSION)\w*\s*=\s*['"]([^'"]+)['"]/],
        ['field',      /(?m)^\s*static\s+(?:final\s+)?(?:String\s+|def\s+)?\w*(?:[vV]ersion|VERSION)\w*\s*=\s*['"]([^'"]+)['"]/],
        ['method',     /(?m)(?:def|static\s+String|String)\s+(?:version|appVersion|driverVersion)\s*\(\s*\)\s*\x7B\s*(?:return\s+)?['"]([^'"]+)['"]/],
        ['library',    /(?s)library\s*\(.*?\bversion\s*:\s*['"]([^'"]+)['"]/],
        ['definition', /(?s)definition\s*\(.*?\bversion\s*:\s*['"]([^'"]+)['"]/],
        ['comment',    /(?mi)^\s*\*?\s*version\s*[:=]?\s*v?(\d+(?:\.\d+)+)/],
    ]
    for (List rule in rules) {
        java.util.regex.Matcher m = head =~ (String) rule[1]
        if (m.find()) return [ver: m.group(1).trim(), src: (String) rule[0]]
    }
    return [ver: null, src: null]
}

// Leading v stripped, cut at first space/paren, split on '.' or '-'; a segment that is not an
// integer after dropping a trailing letter run makes the whole value unparsable (null).
@CompileStatic
static List<Integer> normalizeVersion(String v) {
    if (!v) return null
    String s = v.trim().replaceFirst(/^[vV]/, '')
    int cut = s.indexOf(' '); if (cut > 0) s = s.substring(0, cut)
    cut = s.indexOf('('); if (cut > 0) s = s.substring(0, cut)
    if (!s) return null
    List<Integer> out = []
    for (String seg in s.split(/[.\-]/)) {
        String digits = seg.replaceFirst(/[A-Za-z]+$/, '')
        if (!digits || !digits.isInteger()) return null
        out << digits.toInteger()
    }
    return out
}

// -1 when a < b, 0 when equal, 1 when a > b; null when either side is unparsable.
@CompileStatic
static Integer compareVersions(String a, String b) {
    List<Integer> pa = normalizeVersion(a), pb = normalizeVersion(b)
    if (pa == null || pb == null) return null
    int n = Math.max(pa.size(), pb.size())
    for (int i = 0; i < n; i++) {
        int x = i < pa.size() ? pa[i] : 0, y = i < pb.size() ? pb[i] : 0
        if (x != y) return x < y ? -1 : 1
    }
    return 0
}

// The truth table of DESIGN.md §4.2. localModified = installed source hash != ihash recorded at adoption.
@CompileStatic
static String freshnessStatus(String ihash, String rhash, String iver, String rver, boolean localModified) {
    if (localModified) return 'modified-locally'
    if (!rhash) return 'unknown'
    if (ihash == rhash) return 'current'
    Integer cmp = compareVersions(iver, rver)
    return (cmp != null && cmp < 0) ? 'update-available' : 'changed-upstream'
}

// True when the installed hash has moved since adoption AND still doesn't match the upstream hash
// (a user import that lands back on the upstream file is Current again, not modified-locally forever, C1).
@CompileStatic
static boolean isLocallyModified(String ih, String ah, String rhash) { return ih && ah && ih != ah && ih != rhash }

// ===== Source normalization and hashing (DESIGN.md §4.1) =====
@CompileStatic
static String normalizeSource(String s) {
    if (!s) return ""
    String t = s.startsWith("﻿") ? s.substring(1) : s
    t = t.replace("\r\n", "\n").replace("\r", "\n")
    List<String> lines = t.split("\n", -1).collect { String l -> l.replaceFirst(/[ \t]+$/, '') }
    while (lines && lines[-1] == "") lines.remove(lines.size() - 1)
    return lines ? lines.join("\n") + "\n" : ""
}
@CompileStatic
static String sha256Hex(String s) {
    byte[] d = MessageDigest.getInstance("SHA-256").digest((s ?: "").getBytes("UTF-8"))
    StringBuilder sb = new StringBuilder(64)
    for (byte b in d) sb.append(String.format("%02x", b))
    return sb.toString()
}
@CompileStatic static String sourceHash(String s) { return sha256Hex(normalizeSource(s)) }
@CompileStatic static String digest16(String s) { return sha256Hex(s ?: "").substring(0, 16) }
@CompileStatic
static String libraryImportUrl(String librarySource) {
    if (!librarySource) return null
    java.util.regex.Matcher m = librarySource =~ /(?s)library\s*\(.*?\bimportUrl\s*:\s*['"]([^'"]+)['"]/
    return m.find() ? m.group(1) : null
}

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

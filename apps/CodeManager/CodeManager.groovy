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

List fetchUserAppTypes() {
    Object resp = hubRequest(USER_APP_TYPES_PATH, "user app types", "json", 15)
    if (!(resp instanceof List)) return []
    return (resp as List).collect { Map a ->
        List used = (a.usedBy as List) ?: []
        [id: a.id.toString(), name: a.name, namespace: a.namespace, oauth: a.oauth == "enabled", lastModified: a.lastModified,
         usedByCount: used.size(), usedBy: used.collect { Map u -> [id: u.id, name: u.name] }]
    }
}
List fetchUserDriverTypes() {
    Object resp = hubRequest(USER_DEVICE_TYPES_PATH, "user driver types", "json", 15)
    if (!(resp instanceof List)) return []
    return (resp as List).collect { Map d ->
        List used = (d.usedBy as List) ?: []
        [id: d.id.toString(), name: d.name, namespace: d.namespace, lastModified: d.lastModified,
         usedByCount: used.size(), usedBy: used.collect { Map u -> [id: u.id, name: u.name] }]
    }
}
List fetchUserLibraries() {
    Object resp = hubRequest(USER_LIBRARIES_PATH, "user libraries", "json", 15)
    if (!(resp instanceof List)) return []
    return (resp as List).collect { Map l ->
        List usedDrv = ((l.usedByDeviceTypes as String) ?: "").split(',\\s*').findAll { it }
        List usedApp = ((l.usedByAppTypes as String) ?: "").split(',\\s*').findAll { it }
        [id: l.id.toString(), name: l.name, namespace: l.namespace, version: l.version?.toString(), lastModified: l.lastModified ?: l.updateTime,
         isPrivate: l.private == true, usedByCount: usedDrv.size() + usedApp.size(), usedBy: (usedApp + usedDrv).collect { [name: it] }]
    }
}
List fetchBundles() {
    Object resp = hubRequest(BUNDLE_LIST_PATH, "bundles", "json", 15)
    if (!(resp instanceof List)) return []
    return (resp as List).collect { Map b ->
        [id: b.id.toString(), name: b.name, namespace: b.namespace, isPrivate: b.private == true, sourceUrl: b.sourceUrl,
         appTypeIds: (b.appTypeIds ?: []).collect { it.toString() }, deviceTypeIds: (b.deviceTypeIds ?: []).collect { it.toString() },
         libraryIds: (b.libraryIds ?: []).collect { it.toString() }]
    }
}
// Per-item: importUrl + source + private flag. One call per item, only for new/changed items.
Map fetchItemDetail(String kind, String id) {
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

List listCmFiles() {
    Map wrap = hubMapRequest(FILE_LIST_PATH, "file list", 15)
    if (!wrap.ok) return []
    return ((wrap.data.files ?: []) as List).findAll { Map f -> (f.name as String)?.startsWith("cm_") }
}
void writeCmFile(String name, String text) {
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

// One inventory pass: bulk lists, per-item detail for new/changed items, registry + documents update.
Map runInventoryPass() {
    long t0 = now()
    List apps = fetchUserAppTypes(), drvs = fetchUserDriverTypes(), libs = fetchUserLibraries(), bundles = fetchBundles()
    Map pkgs = (state.pkgs ?: [:]) as Map
    // Fail closed: hubRequest's bulk fetchers fail-soft to [] on a fetch error, indistinguishable
    // from a genuinely empty list. If the registry already holds a component of some kind and this
    // pass's bulk list for that kind came back empty, treat it as a suspected transient failure and
    // abort before touching the registry or any document — never let one bad fetch prune everything.
    Set<String> kindsPresent = pkgs.values().collectMany { Map p -> (p.c as List).collect { Map c -> c.k as String } } as Set
    Map<String, List> bulkByKind = [app: apps, driver: drvs, library: libs, bundle: bundles]
    for (String kind in ['app', 'driver', 'library', 'bundle']) {
        if (kindsPresent.contains(kind) && (bulkByKind[kind] as List).isEmpty()) {
            logWarn "inventory: aborting pass — ${kind} list came back empty but the registry has ${kind} components (suspected transient fetch failure)"
            return [items: 0, fetched: 0, changed: 0, ms: now() - t0, aborted: "empty ${kind} list" as String]
        }
    }
    Map<String, String> memberOf = [:]
    bundles.each { Map b ->
        b.appTypeIds.each { memberOf[ckey('app', it)] = b.id }
        b.deviceTypeIds.each { memberOf[ckey('driver', it)] = b.id }
        b.libraryIds.each { memberOf[ckey('library', it)] = b.id }
    }
    // index existing components by kind:id so bindings (ihash, iver, loc, ah) survive a pass. Origin-url
    // app/driver components don't store loc (derivable from the package key, dropped at storage below),
    // so reconstruct it here from the key before it's carried into this pass's comp map.
    Map<String, Map> prev = [:]
    pkgs.each { String key, Map p ->
        (p.c as List).each { Map c ->
            Map full = (p.o == 'url' && c.k != 'library' && c.loc == null) ? c + [loc: key.substring(4)] : c
            prev[ckey(c.k as String, c.id)] = full + [_key: key]
        }
    }
    Map<String, Map> seen = [:]
    int fetched = 0, changed = 0
    List<List> all = [['app', apps], ['driver', drvs], ['library', libs]]
    for (List pair in all) {
        String kind = pair[0] as String
        for (Map t in (pair[1] as List)) {
            String ck = ckey(kind, t.id)
            Map old = prev[ck]
            long lm = isoToMs(t.lastModified as String)
            // ih/ah are stored as 16-hex-char sha256 prefixes (state-size budget, DESIGN.md §2.4); a
            // pre-existing full-length value is truncated here too, so the sweep (Task 6) and the
            // item-source endpoint (Task 7) can rely on always comparing 16-char prefixes.
            Map comp = [k: kind, id: t.id, ns: t.namespace, nm: t.name, u: t.usedByCount, lm: lm, bm: memberOf[ck],
                        loc: old?.loc, ih: hashPrefix(old?.ih as String), iv: old?.iv, ivs: old?.ivs, pv: old?.pv ?: (t.isPrivate == true), ah: hashPrefix(old?.ah as String)]
            if (old == null || (old.lm as Long ?: 0L) != lm || old.ih == null) {
                Map d = fetchItemDetail(kind, t.id as String); fetched++
                if (d != null) {
                    Map pv = parseVersionLiteral(d.source as String)
                    comp.loc = d.importUrl; comp.ih = hashPrefix(sourceHash(d.source as String)); comp.iv = (kind == 'library' && t.version) ? t.version : pv.ver; comp.ivs = pv.src; comp.pv = d.isPrivate
                    if (old != null && hashPrefix(old.ih as String) != comp.ih) changed++
                }
            }
            if (comp.ah == null) comp.ah = comp.ih   // adoption hash: set once (new component, or migrating from a registry with none), never touched again
            seen[ck] = compact(comp)
        }
    }
    // Rebuild packages: keep HPM-origin packages (Task 5 owns them), regroup everything else.
    Map next = [:]
    // file/bundle components of an HPM package have no counterpart in `seen` (it holds only
    // app/driver/library, DESIGN.md 2.1) — rebind only kinds this pass enumerates, or a forced
    // pass wipes every file's hubId to null even though nothing about it changed.
    Set rebindableKinds = ['app', 'driver', 'library'] as Set
    pkgs.each { String key, Map p -> if (p.o == 'hpm') next[key] = compact(p + [c: (p.c as List).collect { Map c -> rebindableKinds.contains(c.k) ? (seen[ckey(c.k as String, c.id)] ?: c + [id: null]) : c }]) }
    Set bound = next.values().collectMany { Map p -> (p.c as List).findAll { Map c -> c.id }.collect { Map c -> ckey(c.k as String, c.id) } } as Set
    bundles.each { Map b ->
        String key = "bundle:${b.id}"
        // The bundle itself is a component too (DESIGN.md §2.1 kind: bundle), distinct from its members.
        Map bundleComp = compact([k: 'bundle', id: b.id, ns: b.namespace, nm: b.name, u: (b.appTypeIds.size() + b.deviceTypeIds.size() + b.libraryIds.size()),
                                   lm: 0L, bm: null, loc: b.sourceUrl, ih: null, iv: null, ivs: null, pv: b.isPrivate == true, ah: null])
        // classification priority is hpm > bundle (DESIGN.md §3): a member id already claimed by an
        // HPM package (already in `bound`, added above) is not also added under its bundle's package.
        List members = (b.appTypeIds.findAll { !bound.contains(ckey('app', it)) }.collect { seen[ckey('app', it)] } +
                        b.deviceTypeIds.findAll { !bound.contains(ckey('driver', it)) }.collect { seen[ckey('driver', it)] } +
                        b.libraryIds.findAll { !bound.contains(ckey('library', it)) }.collect { seen[ckey('library', it)] }).findAll { it }
        List comps = [bundleComp] + members
        comps.each { Map c -> bound << ckey(c.k as String, c.id) }
        Map p = pkgs[key] as Map
        next[key] = compact([o: 'bundle', n: b.name, v: null, g: p?.g ?: 1, url: b.sourceUrl, pv: b.isPrivate, c: comps])
    }
    // Regroup every non-bound component fresh from `seen` each pass — never carry an old package's
    // component list forward, so an item deleted from the hub (and so absent from `seen`) doesn't
    // linger as a ghost member; only `g` (and, via `prev` above, each component's `ah`) survives.
    Map grouped = [:]
    seen.each { String ck, Map c ->
        if (bound.contains(ck)) return
        String key = c.loc ? "url:${c.loc}" : "local:${c.k}/${c.ns}/${c.nm}"
        // origin url + app/driver: loc equals the key minus its "url:" prefix, so don't store it twice;
        // libraries keep loc stored (their key isn't guaranteed to carry it verbatim — see ruling).
        Map storedC = (c.loc && c.k != 'library') ? c.findAll { String k, v -> k != 'loc' } : c
        ((grouped[key] ?: (grouped[key] = [])) as List) << storedC
    }
    grouped.each { String key, List comps ->
        Map oldP = pkgs[key] as Map
        next[key] = compact([o: key.startsWith('url:') ? 'url' : 'local', n: (comps[0] as Map).nm, v: null, g: oldP?.g ?: 1, c: comps])
    }
    // Documents: write for new packages or when the component set changed (compare by kind:id set)
    Map docsBefore = listCmFiles().collectEntries { Map f -> [(f.name as String): f] }
    next.each { String key, Map p ->
        if (p.o == 'hpm') return                               // Task 5 writes HPM documents
        Set ids = (p.c as List).collect { Map c -> ckey(c.k as String, c.id) } as Set
        Set oldIds = ((pkgs[key] as Map)?.c as List ?: []).collect { Map c -> ckey(c.k as String, c.id) } as Set
        if (ids != oldIds || !docsBefore.containsKey(pkgFileName(key))) {
            p.g = ((p.g ?: 0) as int) + 1
            writePkgDoc(key, p, degenerateManifest(p.o as String, p.n as String, p.c as List, p.url as String))
        }
    }
    // A url:/local: package with no components left this pass (its item(s) were deleted from the hub)
    // is simply absent from `next` — its manifest document is now orphaned; remove it from File Manager.
    // Own files only (cm_pkg_ prefix), guarded and logged — never touches a document this pass didn't write.
    docsBefore.keySet().findAll { String name -> name.startsWith(PKG_FILE_PREFIX) }.each { String name ->
        try {
            String text = readCmFile(name); if (!text) return
            Map doc = (Map) new JsonSlurper().parseText(text)
            String k = doc?._cm?.key as String
            if (k && !next.containsKey(k)) {
                deleteHubFile(name)
                logInfo "inventory: removed stale manifest document ${name} (package ${k} no longer exists)"
            }
        } catch (Exception e) { logError "prune stale manifest ${name}: ${e.message}" }
    }
    state.pkgs = next
    state.lastInventory = [at: now(), items: seen.size(), fetched: fetched, changed: changed]
    logInfo "inventory: ${seen.size()} items, ${fetched} fetched, ${changed} changed, ${bundles.size()} bundles, ${next.size()} packages in ${now() - t0}ms"
    return [items: seen.size(), fetched: fetched, changed: changed, ms: now() - t0]
}

// ===== HPM import (DESIGN.md §8.1–8.2, HPM-PARITY.md §2) =====

// HPM is singleInstance (Package_Manager.groovy:77): usedBy has at most one instance.
Map findHpm() {
    Map type = fetchUserAppTypes().find { Map a -> a.namespace == "dcm.hpm" } as Map
    if (!type) return null
    String instanceId = ((type.usedBy as List) ? ((type.usedBy as List)[0] as Map).id : null)?.toString()
    Map out = [typeId: type.id, instanceId: instanceId, disabled: null, autoUpdateMode: null]
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
    if (!h) { state.hpm = [mode: 'absent']; return jsonResponse([mode: 'absent']) }
    return jsonResponse([mode: st.mode ?: 'observe', typeId: h.typeId, instanceId: h.instanceId, disabled: h.disabled, autoUpdateMode: h.autoUpdateMode,
                         importedAt: st.importedAt, packages: st.packages ?: 0, dangling: st.dangling ?: 0])
}

Map apiHpmImport() {
    Map r = importHpm()
    return jsonResponse(r ? [success: true] + r : [success: false, error: "HPM not installed"])
}

// One-to-one copy of HPM's state.manifests into origin-hpm packages. Idempotent; nothing is written to HPM.
Map importHpm() {
    Map h = findHpm()
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
                         beta: item.beta == true, ih: prev?.ih, ah: prev?.ah, iv: prev?.iv ?: item.version, ivs: prev?.ivs, lm: prev?.lm, u: prev?.u ?: 0, pv: prev?.pv == true, bm: prev?.bm])
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

void initialHpmImport() { if (findHpm()) importHpm() else state.hpm = [mode: 'absent'] }

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

// usedBy names are too costly to keep in state.pkgs (2.4's ~60 KB budget) — re-read from the bulk
// lists on demand, cached for the SPA's rapid-fire drill-down calls (short TTL, not the item's own registry binding).
Map usedByIndex() {
    return cachedFetch("usedByIndex", 60_000L) {
        Map idx = [:]
        fetchUserAppTypes().each { Map a -> idx[ckey('app', a.id)] = a.usedBy }
        fetchUserDriverTypes().each { Map d -> idx[ckey('driver', d.id)] = d.usedBy }
        fetchUserLibraries().each { Map l -> idx[ckey('library', l.id)] = l.usedBy }
        idx
    } as Map
}

// GET /api/inventory — aggregator: registry + sweep cache (Task 6) + HPM mode (Task 5). No sources, no secrets.
Map buildInventoryPayload() {
    Map pkgs = (state.pkgs ?: [:]) as Map
    Map sweep = readSweepCache()                                  // Task 6; returns [:] until then
    Map usedBy = usedByIndex()
    List comps = []
    List packages = pkgs.collect { String key, Map p ->
        Map pe = (sweep["pkg:" + key] ?: [:]) as Map                       // package-row sweep entry (Task 6)
        List pc = (p.c as List).collect { Map c ->
            Map s = (sweep[ckey(c.k as String, c.id)] ?: [:]) as Map
            boolean localMod = c.ah && c.ih && c.ih != c.ah
            String status
            if (c.id == null) status = 'missing'
            else if (c.pv) status = 'private'
            else if (p.o == 'local') status = 'unknown'
            else if (p.o == 'hpm' && s.isEmpty()) {
                // HPM component with no own sweep entry (manifest unchanged, body never fetched): derive
                // from the package manifest's declared version instead of a per-component hash compare.
                Integer cmp = compareVersions(p.v as String, pe.rver as String)
                status = (cmp != null && cmp < 0) ? 'update-available' : ((pe.checkedAt && (!pe.rver || (cmp != null && cmp >= 0))) ? 'current' : 'unknown')
            }
            else status = freshnessStatus(c.ih as String, s.rhash as String, c.iv as String, s.rver as String, localMod)
            if (s.broken == true) status = 'broken-source'
            // origin-url app/driver components don't store loc — reconstruct it from the key (see runInventoryPass)
            String location = c.loc ?: (p.o == 'url' ? key.substring(4) : null)
            Map row = [key: key, kind: c.k, hubId: c.id, namespace: c.ns, name: c.nm, origin: p.o, location: location, managedBy: p.o == 'hpm' ? (state.hpm?.mode == 'manage' ? 'cm' : 'hpm') : 'cm',
                       status: status, ihash: c.ih, ahash: c.ah, iver: c.iv, iverSrc: c.ivs, rhash: s.rhash, rver: s.rver, checkedAtMs: s.checkedAt, changedAtMs: s.changedAt,
                       httpStatus: s.httpStatus, failCount: s.failCount ?: 0, error: s.error, lastModifiedMs: c.lm, usedByCount: c.u ?: 0,
                       usedBy: usedBy[ckey(c.k as String, c.id)] ?: [], bundleOf: c.bm, isPrivate: c.pv == true]
            comps << row
            return row
        }
        [key: key, origin: p.o, name: p.n, version: p.v, gen: p.g, doc: pkgFileName(key), isPrivate: p.pv == true, url: p.url, componentCount: pc.size(),
         checkedAtMs: pe.checkedAt, rver: pe.rver,
         status: pc.collect { it.status }.contains('update-available') ? 'update-available' : pc.collect { it.status }.contains('changed-upstream') ? 'changed-upstream' : pc.collect { it.status }.contains('broken-source') ? 'broken-source' : 'current']
    }
    Map hub = cachedFetch("hubData", 300_000L) { Map w = hubMapRequest(HUB_DATA_PATH, "hub data", 10); w.ok ? [name: w.data.name, firmware: w.data.version, model: w.data.model] : null } as Map ?: [:]
    Map summary = [packages: packages.size(), components: comps.size(),
                   byOrigin: comps.countBy { it.origin }, byStatus: comps.countBy { it.status }, byKind: comps.countBy { it.kind },
                   unused: comps.count { (it.usedByCount ?: 0) == 0 && it.kind != 'bundle' }]
    return [generatedAt: now(), appVersion: CODE_VERSION, hub: hub, packages: packages, components: comps, summary: summary,
            hpm: (state.hpm ?: [mode: 'absent']), sweep: (state.sweep ?: [:]), lastInventory: (state.lastInventory ?: [:])]
}
Map apiInventory() {
    return timed("inventory") {
        boolean force = params.force == "1"
        if (force || !state.pkgs || now() - ((state.lastInventory?.at ?: 0) as long) > 3600_000L) runInventoryPass()
        buildInventoryPayload()
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

void scheduledSweep() { startSweep() }

// force=1 ignores the TTL and queues every eligible item (manual "check now" / verification use;
// documented in the README by Task 9).
Map apiSweepStart() {
    Map r = startSweep(params.force == "1")
    return jsonResponse(r)
}

// Effective source location for a component: url-origin app/driver components don't store loc
// redundantly in state.pkgs (runInventoryPass strips it to derive from the package key on read);
// reconstruct it the same way buildInventoryPayload does. Library/HPM/bundle components always
// carry loc directly on the stored component.
private String componentLoc(String key, Map p, Map c) { return (c.loc as String) ?: (p.o == 'url' ? key.substring(4) : null) }

// Builds the queue and fans out. Idempotent under concurrent triggers.
Map startSweep(boolean force = false) {
    Map prev = (state.sweep ?: [:]) as Map
    if (prev.status == 'running' && JOBS[prev.jobId as String]?.status == 'running') return [jobId: prev.jobId, total: prev.total, alreadyRunning: true]
    readSweepCache()
    runInventoryPass()
    if (findHpm()) importHpm()
    Map pkgs = (state.pkgs ?: [:]) as Map
    List<String> queue = []
    long cutoff = force ? Long.MAX_VALUE : now() - SWEEP_TTL_MS
    pkgs.each { String key, Map p ->
        if (p.o == 'hpm') { Map e = SWEEP["pkg:" + key]; if (!e || ((e.checkedAt ?: 0) as long) < cutoff) queue << ("pkg:" + key) }
        (p.c as List).each { Map c ->
            if (!c.id || c.pv || c.k == 'bundle' || c.k == 'file' || p.o == 'local') return
            if (p.o == 'hpm') return                         // component hashes refresh only when the manifest changed (see applyManifestResult)
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
        boolean pkgHasUpdate = false
        (p.c as List).each { Map c ->
            if (!c.id || c.k in ['bundle', 'file']) return
            String ck = ckey(c.k as String, c.id)
            Map e = SWEEP[ck]; if (e == null) return
            String st = freshnessStatus(c.ih as String, e.rhash as String, c.iv as String, e.rver as String, false)
            if (p.o == 'hpm') { Map pe = SWEEP["pkg:" + key]; if (pe?.rver && compareVersions(p.v as String, pe.rver as String) != null && compareVersions(p.v as String, pe.rver as String) < 0) st = 'update-available' }
            if (st == 'update-available') pkgHasUpdate = true
            if (e.broken == true) broken++
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
        settings.notifyDevices.each { it.deviceNotification("Code Manager: ${updates} package update${updates == 1 ? '' : 's'} available${broken ? ", ${broken} broken source${broken == 1 ? '' : 's'}" : ''}") }
        state.updatesNotified = true
    }
    if (updates == 0) state.updatesNotified = false
    logInfo "sweep done: ${result}"
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
    j.kind = kind; j.total = new AtomicInteger(total); j.startedAt = now(); j.status = 'running'
    j.processed = new AtomicInteger(0); j.inFlight = new AtomicInteger(0)
    j.pending = new ConcurrentLinkedQueue<String>()
    JOBS[id] = j
    state.lastJob = [id: id, kind: kind, status: 'running', total: total, startedAt: j.startedAt]   // scalar snapshot survives a push
    return id
}
void jobDone(String jobId, Map result) {
    ConcurrentHashMap j = JOBS[jobId]; if (j == null) return
    j.status = 'done'; j.result = result ?: [:]; j.finishedAt = now()
    state.lastJob = [id: jobId, kind: j.kind, status: 'done', total: (j.total as AtomicInteger).get(), startedAt: j.startedAt, finishedAt: j.finishedAt]
}
void jobFail(String jobId, String error) {
    ConcurrentHashMap j = JOBS[jobId]; if (j == null) return
    j.status = 'error'; j.error = error; j.finishedAt = now()
    state.lastJob = [id: jobId, kind: j.kind, status: 'error', error: error, total: (j.total as AtomicInteger).get(), startedAt: j.startedAt, finishedAt: j.finishedAt]
}
Map jobStatus(String jobId) {
    ConcurrentHashMap j = JOBS[jobId]
    if (j == null) {
        Map snap = (state.lastJob ?: [:]) as Map
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
    Map sw = (state.sweep ?: [:]) as Map
    if (sw.status == 'running' && JOBS[sw.jobId as String]?.status != 'running') state.sweep = sw + [status: 'stale']
    Random rng = new Random()
    schedule("${rng.nextInt(60)} ${rng.nextInt(60)} 3 * * ?", "scheduledSweep")       // Task 6 defines scheduledSweep
    schedule("0 17 3 * * ?", "scheduledUISync")
    schedule("0 41 3 * * ?", "scheduledVersionCheck")
    subscribe(location, "systemStart", "systemStartHandler")
    refreshUpdateLabel()
    if (!state.hpm) runIn(30, 'initialHpmImport')
    logSched "daily sweep, UI sync and version check scheduled"
}
void systemStartHandler(evt) { runIn(300, 'scheduledSweep') }

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

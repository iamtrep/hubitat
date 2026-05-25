/*
 * Multi-Hub Inventory — read-only cross-hub device aggregator.
 * Copyright (c) 2026 PJ. SPDX-License-Identifier: MIT
 */
import groovy.transform.Field

@Field static final String APP_VERSION = "0.4.0"
@Field static final String UI_FILE = "multi_hub_inventory_ui.html"
@Field static final String IMPORT_URL_APP = "https://raw.githubusercontent.com/iamtrep/hubitat/refs/heads/main/apps/MultiHubInventory/MultiHubInventory.groovy"
@Field static final String IMPORT_URL_WEB = "https://raw.githubusercontent.com/iamtrep/hubitat/refs/heads/main/apps/MultiHubInventory/multi_hub_inventory_ui.html"
@Field static volatile String uiVersionCache = null
@Field static volatile boolean githubVersionRefreshPending = false

definition(
    name: "Multi-Hub Inventory",
    namespace: "iamtrep",
    author: "pj",
    description: "Read-only cross-hub device inventory, aggregated from each hub's Hub Diagnostics audit API",
    menu: "Apps",
    category: "Utility",
    singleInstance: true,
    importUrl: IMPORT_URL_APP,
    oauth: true,
    iconUrl: "", iconX2Url: "", iconX3Url: ""
)

preferences { page(name: "mainPage") }

mappings {
    path('/ui.html')   { action: [GET: 'serveUI'] }
    path('/api/peers') { action: [GET: 'apiPeers'] }
    path('/api/peer')  { action: [GET: 'apiPeer'] }
}

// ===== CONFIG PAGE =====
def mainPage() {
    if (state.peerIds == null) state.peerIds = [1]
    dynamicPage(name: "mainPage", title: "Multi-Hub Inventory v${APP_VERSION}", install: true, uninstall: true) {
        section("Hubs") {
            paragraph "For each hub running Hub Diagnostics, paste its API base URL with the access token, e.g.<br><code>http://192.168.1.86/apps/api/247/api/?access_token=abcd…</code><br>(the <code>/api/</code> path, not the <code>ui.html</code> link). Include this hub as a peer too, pointing at its own Hub Diagnostics."
            (state.peerIds as List).each { Integer p ->
                input "peer_${p}_label", "text", title: "Hub ${p} label",   required: false, width: 4
                input "peer_${p}_url",   "text", title: "Hub ${p} API URL", required: false, width: 6, submitOnChange: true
                input "btnRemoveHub_${p}", "button", title: "Remove ${p}", width: 2
                Map pinfo = (state.peerList ?: []).find { (it as Map).pid == p } as Map
                String st = pinfo?.reachable
                if (st) {
                    String icon = (st == 'ok') ? '✅' : (st == 'auth') ? '🔒 auth failed' : (st == 'unreachable') ? '⚠️ unreachable' : "⚠️ ${st}"
                    paragraph "&nbsp;&nbsp;↳ ${icon}${pinfo?.self ? ' · this hub (loopback)' : ''}", width: 12
                }
            }
            input "btnAddHub", "button", title: "➕ Add hub"
        }
        section("Dashboard") {
            if (state.accessToken) {
                href url: "${fullLocalApiServerUrl}/ui.html?access_token=${state.accessToken}",
                     title: "Open Multi-Hub Inventory", style: "external", required: false
            } else {
                paragraph "Enable OAuth (Apps Code → this app → OAuth) and re-open to get the dashboard link."
            }
        }
        section {
            String uiVer = getUIVersion()
            String latest = checkGithubVersion()
            if (uiVer != "Unknown" && uiVer != APP_VERSION) {
                paragraph "⚠️ <b>Version mismatch:</b> dashboard HTML is v${uiVer} but the app is v${APP_VERSION}. It auto-syncs from GitHub on save; if this persists, re-open the app or upload the HTML to File Manager manually."
            } else {
                paragraph "<small>App v${APP_VERSION} · Dashboard UI v${uiVer}</small>"
            }
            if (isNewer(latest, APP_VERSION)) {
                paragraph "🔄 <b>Update available:</b> v${latest} on GitHub (you have v${APP_VERSION}). Use <b>Import</b> on the Apps Code page to update."
            }
        }
    }
}

// Parse a Hub Diagnostics API base URL + access token, accepting BOTH shapes:
//   local: http://<ip>/apps/api/<id>/api/?access_token=<tok>
//   cloud: https://cloud.hubitat.com/api/<cloudHubId>/apps/<id>/api/?access_token=<tok>
// Capture everything up to the LAST "/api" segment before the query as the base (the proxy
// appends /audit/... to it), then the token. Greedy [^?\s]+/api lands on the app's /api base
// in either shape (the cloud URL also has an earlier /api/<cloudHubId> which is correctly skipped).
private Map parsePeerUrl(String raw) {
    java.util.regex.Matcher m = (raw =~ /(https?:\/\/[^?\s]+\/api)\/?\?.*?access_token=([a-fA-F0-9\-]+)/)
    if (m.find()) return [baseUrl: m.group(1).replaceAll(/\/+$/, ''), token: m.group(2)]
    return null
}

void appButtonHandler(String btn) {
    List ids = (state.peerIds ?: []) as List
    if (btn == 'btnAddHub') {
        Integer next = ids ? ((ids.max() as Integer) + 1) : 1
        ids << next; state.peerIds = ids
    } else if (btn?.startsWith('btnRemoveHub_')) {
        Integer p = btn.replace('btnRemoveHub_', '') as Integer
        ids.remove((Object) p); state.peerIds = ids
        app.removeSetting("peer_${p}_label"); app.removeSetting("peer_${p}_url")
    }
}

// ===== LIFECYCLE =====
void installed() { state.peerIds = [1]; checkOAuth(); runIn(1, 'syncUIForced'); initialize() }
void updated()   { uiVersionCache = null; runIn(1, 'syncUIForced'); initialize() }
void initialize() {
    if (!state.accessToken) checkOAuth()
    String hubIp = location?.hubs ? location.hubs[0]?.localIP : null
    List peerList = []
    (state.peerIds ?: []).each { Integer p ->
        String raw = settings["peer_${p}_url"] as String
        if (!raw) return
        Map parsed = parsePeerUrl(raw)
        if (!parsed) { logWarn "peer ${p}: could not parse URL"; return }
        String label = (settings["peer_${p}_label"] as String) ?: parsed.baseUrl
        // webBase: scheme+host of the peer (everything before /apps/) — used for browser device links.
        String webBase = (parsed.baseUrl =~ /^(https?:\/\/[^\/]+)/)[0][1] ?: ''
        // Self-peer: a hub can't HTTP its own external IP, so route this app's server-side calls
        // through the loopback while keeping webBase = real IP (browser device links resolve to it).
        boolean isSelf = (hubIp && webBase.contains(hubIp))
        String callBase = isSelf ? parsed.baseUrl.replaceFirst(/^https?:\/\/[^\/]+/, 'http://127.0.0.1:8080') : parsed.baseUrl
        if (isSelf) logInfo "peer ${p} is this hub — routing API calls via loopback"
        peerList << [pid: p, label: label, baseUrl: callBase, token: parsed.token, reachable: null, webBase: webBase, self: isSelf]
    }
    state.peerList = peerList
    logInfo "Multi-Hub Inventory initialized with ${peerList.size()} peer(s)"
    if (peerList) runIn(2, 'probePeers')
}

// ===== OAUTH + HELPERS =====
// Self-enabling OAuth: try createAccessToken(); if OAuth isn't enabled on the app type yet,
// enable it via the hub loopback API and retry — so no manual code-editor toggle is needed.
private boolean checkOAuth() {
    if (state.accessToken) return true
    try {
        createAccessToken()
        return (state.accessToken != null)
    } catch (Exception e) {
        logDebug "OAuth not enabled yet, attempting auto-enable..."
        if (autoEnableOAuth()) {
            try {
                createAccessToken()
                return (state.accessToken != null)
            } catch (Exception e2) {
                logError "OAuth enabled but token creation failed: ${e2.message}"
                return false
            }
        }
        return false
    }
}

// Look up this app's type ID by its definition name, via the hub loopback API.
private String getAppTypeId() {
    String typeId = null
    try {
        httpGet([uri: "http://127.0.0.1:8080", path: "/hub2/userAppTypes", timeout: 15]) { resp ->
            List apps = resp.data instanceof List ? (List) resp.data : []
            Map match = apps.find { it.name == "Multi-Hub Inventory" }
            if (match) typeId = match.id?.toString()
        }
    } catch (e) {
        logDebug "Failed to fetch user app types: ${e.message}"
    }
    return typeId
}

// Enable OAuth on this app type via the hub loopback API (loopback is trusted, no session needed).
private boolean autoEnableOAuth() {
    String typeId = getAppTypeId()
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
            body: [id: typeId, version: internalVer, oauthEnabled: "true", _action_update: "Update"],
            timeout: 20
        ]) { resp -> success = true }
    } catch (e) {
        logError "Failed to enable OAuth: ${e.message}"
    }
    return success
}
private Map jsonResponse(Object data) { return render(contentType: 'application/json', data: groovy.json.JsonOutput.toJson(data)) }
private void logInfo(String m)  { log.info  "MultiHubInventory: ${m}" }
private void logWarn(String m)  { log.warn  "MultiHubInventory: ${m}" }
private void logError(String m) { log.error "MultiHubInventory: ${m}" }
private void logDebug(String m) { log.debug "MultiHubInventory: ${m}" }

// ===== UPDATE MANAGEMENT =====
// Self-healing SPA: on install/update (and on File Manager loss), download the matching
// multi_hub_inventory_ui.html from GitHub and store it in File Manager — so the dashboard HTML
// never has to be uploaded by hand and can't silently drift behind the app version.
void syncUIForced() { syncUI(true) }

void syncUI(boolean force = false) {
    if (!force && state.lastInstalledUIVersion == APP_VERSION && (now() - (state.lastUISyncCheck ?: 0) < 86400000)) return
    logInfo "Syncing dashboard UI from GitHub (async)..."
    asynchttpGet('syncUICallback', [uri: IMPORT_URL_WEB, contentType: "text/plain", timeout: 30])
}

void syncUICallback(resp, data) {
    if (resp.hasError() || resp.status != 200) { logWarn "UI sync failed: HTTP ${resp.status}"; return }
    processSyncUIResponse(resp.data ?: "")
}

// Blocking sync — only for emergency recovery from serveUI when the File Manager copy is missing.
private boolean syncUIBlocking() {
    try {
        String html = null
        httpGet([uri: IMPORT_URL_WEB, contentType: "text/plain", timeout: 30]) { resp ->
            if (resp.success && resp.data) html = resp.data.text ?: resp.data.toString()
        }
        return processSyncUIResponse(html ?: "")
    } catch (Exception e) {
        logWarn "Failed to sync UI from GitHub: ${e.message}"
        return false
    }
}

// Validate the download (right app + UI_VERSION matches APP_VERSION) before storing it. The version
// gate is the lockstep guard: a GitHub UI whose UI_VERSION != APP_VERSION is refused, so File Manager
// never holds a SPA that mismatches the installed app.
private boolean processSyncUIResponse(String html) {
    if (!html || !html.contains("Multi-Hub Inventory")) { logWarn "UI sync: downloaded content looks invalid"; return false }
    if (!html.contains("const UI_VERSION = \"${APP_VERSION}\"")) {
        logWarn "UI sync: GitHub UI version does not match app v${APP_VERSION} — not storing"
        return false
    }
    uploadHubFile(UI_FILE, html.getBytes("UTF-8"))
    state.lastInstalledUIVersion = APP_VERSION
    state.lastUISyncCheck = now()
    uiVersionCache = APP_VERSION
    logInfo "Dashboard UI synced from GitHub to v${APP_VERSION}"
    return true
}

// UI_VERSION embedded in the File Manager copy — for display + drift detection on the config page.
private String getUIVersion() {
    if (uiVersionCache) return uiVersionCache
    try {
        byte[] bytes = downloadHubFile(UI_FILE)
        if (bytes) {
            java.util.regex.Matcher m = (new String(bytes, 'UTF-8') =~ /const UI_VERSION = "([^"]+)"/)
            if (m.find()) { uiVersionCache = m.group(1); return uiVersionCache }
        }
    } catch (Exception e) { logDebug "Error reading UI version: ${e.message}" }
    return "Unknown"
}

// Stale-while-revalidate GitHub version check: returns the last-known latest version immediately and
// kicks off an async refresh at most hourly. The config page compares it against APP_VERSION.
String checkGithubVersion() {
    if (now() - (state.lastGithubVersionCheck ?: 0) >= 3600000 && !githubVersionRefreshPending) {
        githubVersionRefreshPending = true
        state.lastGithubVersionCheck = now()   // throttle to hourly regardless of outcome
        asynchttpGet('githubVersionCallback', [uri: IMPORT_URL_APP, contentType: "text/plain", timeout: 10])
    }
    return state.lastGithubVersion
}

void githubVersionCallback(resp, data) {
    githubVersionRefreshPending = false
    if (resp.hasError() || resp.status != 200) { logDebug "GitHub version check failed: HTTP ${resp?.status}"; return }
    try {
        java.util.regex.Matcher m = ((resp.data?.toString() ?: '') =~ /APP_VERSION = "([^"]+)"/)
        if (m.find()) state.lastGithubVersion = m.group(1)
    } catch (Exception e) { logDebug "GitHub version parse failed: ${e.message}" }
}

// True if dotted-numeric version a is strictly newer than b (so a GitHub copy that is BEHIND the
// installed app never shows as an available update).
private boolean isNewer(String a, String b) {
    if (!a || !b) return false
    List pa = a.tokenize('.').collect { it.isInteger() ? it.toInteger() : 0 }
    List pb = b.tokenize('.').collect { it.isInteger() ? it.toInteger() : 0 }
    for (int i = 0; i < Math.max(pa.size(), pb.size()); i++) {
        int x = i < pa.size() ? (pa[i] as int) : 0
        int y = i < pb.size() ? (pb[i] as int) : 0
        if (x != y) return x > y
    }
    return false
}

// ===== REACHABILITY =====
// Probe each peer's audit/status (runIn'd off initialize) so the config page shows ok / auth /
// unreachable per hub before a scan is ever run.
void probePeers() {
    List peers = (state.peerList ?: []) as List
    peers.each { Map peer ->
        try {
            httpGet([uri: "${peer.baseUrl}/audit/status?access_token=${peer.token}", contentType: 'application/json', timeout: 8]) { resp ->
                peer.reachable = (resp.status == 200) ? 'ok' : "http ${resp.status}"
            }
        } catch (Exception e) {
            String msg = (e.message ?: '')
            peer.reachable = (msg.contains('401') || msg.contains('403')) ? 'auth' : 'unreachable'
        }
    }
    state.peerList = peers
}

// ===== API =====
// GET /api/peers — labels + index + reachability. NEVER returns tokens.
Map apiPeers() {
    if (!checkOAuth()) return render(status: 403, contentType: 'text/plain', data: 'OAuth not enabled')
    List out = []
    (state.peerList ?: []).eachWithIndex { Map p, int i -> out << [index: i, label: p.label, reachable: p.reachable, webBase: p.webBase ?: ''] }
    return jsonResponse([peers: out])
}

// GET /api/peer?hub=<idx>&op=start|status|data[&scanId=...] — same-origin forwarder.
// op is whitelisted; the caller passes a hub INDEX, never a URL or token.
Map apiPeer() {
    if (!checkOAuth()) return render(status: 403, contentType: 'text/plain', data: 'OAuth not enabled')
    String op = (params.op ?: '') as String
    if (!(op in ['start', 'status', 'data'])) return jsonResponse([error: "invalid op"])
    List peers = (state.peerList ?: []) as List
    String hubParam = (params.hub ?: '') as String
    Integer idx = hubParam.isInteger() ? hubParam.toInteger() : null
    if (idx == null || idx < 0 || idx >= peers.size()) return jsonResponse([error: "unknown hub"])
    Map peer = peers[idx] as Map
    String base = peer.baseUrl, token = peer.token
    String url; String method = 'GET'
    if (op == 'start')       { url = "${base}/audit/start?access_token=${token}"; method = 'POST' }
    else if (op == 'status') { String rawSid = params.scanId as String; String sid = (rawSid && rawSid ==~ /[A-Za-z0-9_\-]+/) ? "&scanId=${rawSid}" : ''; url = "${base}/audit/status?access_token=${token}${sid}" }
    else                     { url = "${base}/audit/data?access_token=${token}" }
    try {
        Object body = null
        Closure handler = { resp -> body = resp.data }
        if (method == 'POST') httpPost([uri: url, requestContentType: 'application/json', contentType: 'application/json', timeout: 30], handler)
        else                  httpGet([uri: url, contentType: 'application/json', timeout: 90], handler)
        return jsonResponse(body ?: [:])
    } catch (Exception e) {
        String safeMsg = (e.message ?: '')?.replaceAll(/access_token=[^&\s]+/, 'access_token=REDACTED')
        logWarn "peer ${idx} ${op} failed: ${e.class?.simpleName}: ${safeMsg}"
        return jsonResponse([error: "peer call failed"])
    }
}

// ===== UI SERVING =====
Map serveUI() {
    if (!checkOAuth()) return render(status: 403, contentType: 'text/plain', data: 'OAuth is not enabled for this app.')
    try {
        byte[] bytes = downloadHubFile(UI_FILE)
        if (!bytes) {
            logError "${UI_FILE} missing from File Manager — attempting emergency sync from GitHub..."
            if (syncUIBlocking()) bytes = downloadHubFile(UI_FILE)
        }
        if (!bytes) return render(status: 404, contentType: 'text/plain', data: "${UI_FILE} not found in File Manager. Re-open the app to auto-sync, or upload it manually.")
        String html = new String(bytes, 'UTF-8')
            .replace('${access_token}', state.accessToken)
            .replace('${api_base}', fullLocalApiServerUrl)
        return render(status: 200, contentType: 'text/html', data: html)
    } catch (Exception e) {
        logError "serveUI: ${e.message}"
        return render(status: 500, contentType: 'text/plain', data: "Error serving UI: ${e.message}")
    }
}

/*
 * Multi-Hub Inventory — read-only cross-hub device aggregator.
 * Copyright (c) 2026 PJ. SPDX-License-Identifier: MIT
 */
import groovy.transform.Field
import java.util.concurrent.ConcurrentHashMap

@Field static final String CODE_VERSION = "0.8.8"
@Field static final String UI_FILE = "multi_hub_inventory_ui.html"
@Field static final String IMPORT_URL_APP = "https://raw.githubusercontent.com/iamtrep/hubitat/refs/heads/main/apps/MultiHubInventory/MultiHubInventory.groovy"
@Field static final String IMPORT_URL_WEB = "https://raw.githubusercontent.com/iamtrep/hubitat/refs/heads/main/apps/MultiHubInventory/multi_hub_inventory_ui.html"
@Field static volatile String uiVersionCache = null
@Field static volatile boolean githubVersionRefreshPending = false
// Peer probe results, keyed "${app.id}:${pid}" -> [baseUrl, reachable]. Kept out of state.peerList so
// probe callbacks never rewrite the list initialize() owns. In memory only: the result is transient
// and re-probed by initialize(); after a reboot the config page triggers a fresh probe.
@Field static final ConcurrentHashMap<String, Map> peerReachability = new ConcurrentHashMap<>()
// Green badge appended to the app label (visible in the Apps list) when a newer release is
// published on GitHub. UPDATE_BADGE_RE strips any prior badge so re-applying is idempotent and
// survives the label being saved verbatim through the "Assign a name" input on Done.
@Field static final String UPDATE_AVAILABLE_BADGE = ' <span style="color:green; font-weight:bold;">update available</span>'
@Field static final java.util.regex.Pattern UPDATE_BADGE_RE = ~/(?i)\s*<span\b[^>]*>\s*update available\s*<\/span>\s*$/

definition(
    name: "Multi-Hub Inventory",
    namespace: "iamtrep",
    author: "pj",
    description: "Read-only cross-hub device inventory, aggregated from each hub's Hub Inspector audit API",
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
    path('/api/reinit') { action: [POST: 'apiReinit'] }
    path('/api/version/check') { action: [GET: 'apiVersionCheck'] }
    path('/api/ui/sync')       { action: [POST: 'apiSyncUI'] }
}

// ===== CONFIG PAGE =====
Map mainPage() {
    if (state.peerIds == null) state.peerIds = [1]
    reprobeIfNoResults()
    dynamicPage(name: "mainPage", title: "Multi-Hub Inventory v${CODE_VERSION}", install: true, uninstall: true) {
        section("Hubs") {
            paragraph "For each hub running Hub Inspector, paste its API base URL with the access token, e.g.<br><code>http://192.168.0.10/apps/api/247/api/?access_token=abcd…</code><br>(the <code>/api/</code> path, not the <code>ui.html</code> link). Include this hub as a peer too, pointing at its own Hub Inspector. On save, the token moves into encrypted storage and leaves the field; paste a full URL again to change it."
            (state.peerIds as List).each { Integer p ->
                input "peer_${p}_label", "text", title: "Hub ${p} label",   required: false, width: 4
                input "peer_${p}_url",   "text", title: "Hub ${p} API URL", required: false, width: 6, submitOnChange: true
                input "btnRemoveHub_${p}", "button", title: "Remove ${p}", width: 2
                Map pinfo = (state.peerList ?: []).find { (it as Map).pid == p } as Map
                String st = pinfo ? peerReachable(pinfo) : null
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
                String ep0 = getAppEditorPath()
                String codeLink0 = ep0 ? "<a href='${ep0}' target='_blank'>Apps Code</a>" : "Apps Code"
                paragraph "Enable OAuth (${codeLink0} → this app → OAuth) and re-open to get the dashboard link."
            }
        }
        section {
            String uiVer = getUIVersion()
            String latest = checkGithubVersion()
            if (uiVer != "Unknown" && uiVer != CODE_VERSION) {
                paragraph "⚠️ <b>Version mismatch:</b> dashboard HTML is v${uiVer} but the app is v${CODE_VERSION}. It auto-syncs from GitHub on save; if this persists, re-open the app or upload the HTML to File Manager manually."
            } else {
                paragraph "<small>App v${CODE_VERSION} · Dashboard UI v${uiVer}</small>"
            }
            if (isNewer(latest, CODE_VERSION)) {
                String ep = getAppEditorPath()
                String importLink = ep ? "<a href='${ep}' target='_blank'>Open App Code Editor</a> and use Import to update." : "Use <b>Import</b> on the Apps Code page to update."
                paragraph "🔄 <b>Update available:</b> v${latest} on GitHub (you have v${CODE_VERSION}). ${importLink}"
            }
        }
        section("Logging") {
            input name: "txtEnable", type: "bool", title: "Enable info logging", defaultValue: true
            input name: "debugEnable", type: "bool", title: "Enable debug logging", defaultValue: false, submitOnChange: true
            if (debugEnable) {
                input name: "traceEnable", type: "bool", title: "Enable trace logging", defaultValue: false
            }
        }
    }
}

// Parse a Hub Inspector API base URL + access token, accepting BOTH shapes:
//   local: http://<ip>/apps/api/<id>/api/?access_token=<tok>
//   cloud: https://cloud.hubitat.com/api/<cloudHubId>/apps/<id>/api/?access_token=<tok>
// Capture everything up to the LAST "/api" segment before the query as the base (the proxy
// appends /audit/... to it), then the token. Greedy [^?\s]+/api lands on the app's /api base
// in either shape (the cloud URL also has an earlier /api/<cloudHubId> which is correctly skipped).
// The token is null for a URL whose token was already moved into state.peerTokens.
private Map parsePeerUrl(String raw) {
    java.util.regex.Matcher m = (raw =~ /(https?:\/\/[^?\s]+\/api)\/?(?:\?.*?access_token=([a-fA-F0-9\-]+))?/)
    if (m.find()) return [baseUrl: m.group(1).replaceAll(/\/+$/, ''), token: m.group(2)]
    return null
}

// Peer tokens are kept encrypted with the hub's key, so the app's settings and state pages
// don't show them. A hub migration loses the key: paste each peer's full URL again after one.
private String peerToken(Map peer) {
    String enc = ((state.peerTokens ?: [:]) as Map)["${peer.pid}".toString()] as String
    if (!enc) return null
    try {
        return decrypt(enc)
    } catch (Exception e) {
        logWarn "peer ${peer.pid}: could not decrypt its token (${e.message}); paste its full URL again"
        return null
    }
}

void appButtonHandler(String btn) {
    checkVersion()
    List ids = (state.peerIds ?: []) as List
    if (btn == 'btnAddHub') {
        Integer next = ids ? ((ids.max() as Integer) + 1) : 1
        ids << next; state.peerIds = ids
    } else if (btn?.startsWith('btnRemoveHub_')) {
        Integer p = btn.replace('btnRemoveHub_', '') as Integer
        ids.remove((Object) p); state.peerIds = ids
        app.removeSetting("peer_${p}_label"); app.removeSetting("peer_${p}_url")
        Map tokens = (state.peerTokens ?: [:]) as Map
        tokens.remove("${p}".toString()); state.peerTokens = tokens
    }
}

// ===== LIFECYCLE =====
void installed() { state.peerIds = [1]; checkOAuth(); runIn(1, 'syncUIForced'); initialize() }
void updated()   { uiVersionCache = null; runIn(1, 'syncUIForced'); initialize() }
void initialize() {
    checkVersion(false)
    if (!state.accessToken) checkOAuth()
    String hubIp = location?.hubs ? location.hubs[0]?.localIP : null
    List peerList = []
    Map tokens = (state.peerTokens ?: [:]) as Map
    (state.peerIds ?: []).each { Integer p ->
        String raw = settings["peer_${p}_url"] as String
        if (!raw) return
        Map parsed = parsePeerUrl(raw)
        if (!parsed) { logWarn "peer ${p}: could not parse URL"; return }
        if (parsed.token) {
            tokens["${p}".toString()] = encrypt(parsed.token as String)
            app.updateSetting("peer_${p}_url", [type: "text", value: "${parsed.baseUrl}/".toString()])
        } else if (!tokens["${p}".toString()]) {
            logWarn "peer ${p}: no access token; paste its full URL"
            return
        }
        String label = (settings["peer_${p}_label"] as String) ?: parsed.baseUrl
        // webBase: scheme+host of the peer (everything before /apps/) — used for browser device links.
        String webBase = (parsed.baseUrl =~ /^(https?:\/\/[^\/]+)/)[0][1] ?: ''
        // Self-peer: a hub can't HTTP its own external IP, so route this app's server-side calls
        // through the loopback while keeping webBase = real IP (browser device links resolve to it).
        // A loopback URL is this hub too; give the browser its LAN address so device links and
        // Hub Mesh source matching (remote URLs carry the LAN IP) resolve.
        if (hubIp && webBase ==~ /^https?:\/\/(127\.0\.0\.1|localhost)(:\d+)?$/) webBase = "http://${hubIp}"
        boolean isSelf = (hubIp && webBase.contains(hubIp))
        String callBase = isSelf ? parsed.baseUrl.replaceFirst(/^https?:\/\/[^\/]+/, 'http://127.0.0.1:8080') : parsed.baseUrl
        if (isSelf) logCfg "peer ${p} is this hub — routing API calls via loopback"
        peerList << [pid: p, label: label, baseUrl: callBase, webBase: webBase, self: isSelf]
    }
    state.peerTokens = tokens.findAll { k, v -> (state.peerIds ?: []).any { "${it}" == k } }
    state.peerList = peerList
    String keyPrefix = "${app.id}:"
    peerReachability.keySet().removeIf { String k -> k.startsWith(keyPrefix) }
    logCfg "Multi-Hub Inventory initialized with ${peerList.size()} peer(s)"
    // Keep the Apps-list "update available" badge current even when the config page is never opened:
    // poll GitHub daily for a newer release, and reconcile the label now so the badge clears
    // immediately after the user updates the installed code. (Same-handler reschedule is idempotent.)
    schedule("0 41 3 * * ?", "scheduledVersionCheck")
    refreshUpdateLabel()
    if (peerList) runIn(2, 'probePeers')
    if (debugEnable || traceEnable) runIn(1800, "logsOff")
}

private void checkVersion(boolean reinit = true) {
    if (state.version == CODE_VERSION) return
    logVer "New version: ${CODE_VERSION} (was: ${state.version})"
    state.version = CODE_VERSION
    if (reinit) runIn(1, "updated")
}

void logsOff() {
    app.updateSetting("debugEnable", [value: "false", type: "bool"])
    app.updateSetting("traceEnable", [value: "false", type: "bool"])
    logWarn "Debug/trace logging auto-disabled"
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
        logNet "OAuth not enabled yet, attempting auto-enable..."
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

private String getAppTypeId() {
    return app.getAppTypeId()?.toString()
}

private String getAppEditorPath() {
    String typeId = getAppTypeId()
    return typeId ? "/app/editor/${typeId}" : null
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
private Map jsonResponse(def data, int status = 200) { return render(status: status, contentType: 'application/json', data: groovy.json.JsonOutput.toJson(data)) }

// ===== UPDATE MANAGEMENT =====
// Self-healing SPA: on install/update (and on File Manager loss), download the matching
// multi_hub_inventory_ui.html from GitHub and store it in File Manager — so the dashboard HTML
// never has to be uploaded by hand and can't silently drift behind the app version.
void syncUIForced() { checkVersion(); syncUI(true) }

void syncUI(boolean force = false) {
    if (!force && state.lastInstalledUIVersion == CODE_VERSION && (now() - (state.lastUISyncCheck ?: 0) < 86400000)) return
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

// Validate the download (right app + HTML CODE_VERSION matches this app's) before storing it. The
// version gate is the lockstep guard: a GitHub UI whose CODE_VERSION mismatches the app is refused,
// so File Manager never holds a SPA that mismatches the installed app.
private boolean processSyncUIResponse(String html) {
    if (!html || !html.contains("Multi-Hub Inventory")) { logWarn "UI sync: downloaded content looks invalid"; return false }
    if (!html.contains("const CODE_VERSION = \"${CODE_VERSION}\"")) {
        logWarn "UI sync: GitHub UI version does not match app v${CODE_VERSION} — not storing"
        return false
    }
    uploadHubFile(UI_FILE, html.getBytes("UTF-8"))
    state.lastInstalledUIVersion = CODE_VERSION
    state.lastUISyncCheck = now()
    uiVersionCache = CODE_VERSION
    logVer "Dashboard UI synced from GitHub to v${CODE_VERSION}"
    return true
}

// CODE_VERSION embedded in the File Manager copy — for display + drift detection on the config page.
private String getUIVersion() {
    if (uiVersionCache) return uiVersionCache
    try {
        byte[] bytes = downloadHubFile(UI_FILE)
        if (bytes) {
            java.util.regex.Matcher m = (new String(bytes, 'UTF-8') =~ /const CODE_VERSION = "([^"]+)"/)
            if (m.find()) { uiVersionCache = m.group(1); return uiVersionCache }
        }
    } catch (Exception e) { logDebug "Error reading UI version: ${e.message}" }
    return "Unknown"
}

// Stale-while-revalidate GitHub version check: returns the last-known latest version immediately and
// kicks off an async refresh at most hourly. The config page compares it against CODE_VERSION.
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
    if (resp.hasError() || resp.status != 200) { logNet "GitHub version check failed: HTTP ${resp?.status}"; return }
    try {
        java.util.regex.Matcher m = ((resp.data?.toString() ?: '') =~ /CODE_VERSION = "([^"]+)"/)
        if (m.find()) { state.lastGithubVersion = m.group(1); refreshUpdateLabel() }
    } catch (Exception e) { logDebug "GitHub version parse failed: ${e.message}" }
}

// Reflect GitHub update status in the app label so the Apps list shows a green "update available"
// badge without opening the app. Idempotent: strips any prior badge before deciding whether to
// re-append, so it never stacks and clears itself once the installed code catches up to GitHub.
private void refreshUpdateLabel() {
    try {
        String current = (app.getLabel() ?: "") as String
        String base = stripUpdateBadge(current)
        String remote = state.lastGithubVersion as String
        boolean outdated = remote && isNewer(remote, CODE_VERSION)
        String desired = outdated ? (base + UPDATE_AVAILABLE_BADGE) : base
        if (current != desired) {
            app.updateLabel(desired)
            logDebug "App label ${outdated ? "badged 'update available' (remote v${remote})" : 'badge cleared'}"
        }
    } catch (Exception e) {
        logDebug "refreshUpdateLabel error: ${e.message}"
    }
}

private String stripUpdateBadge(String label) {
    return label ? UPDATE_BADGE_RE.matcher(label).replaceAll('') : label
}

void scheduledVersionCheck() {
    checkVersion()
    logSched "Running scheduled GitHub version check"
    checkGithubVersion()   // stale-while-revalidate; the async callback refreshes the label
    refreshUpdateLabel()   // also reconcile the label against the already-cached version
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
// Peer auth goes in the Authorization header rather than ?access_token=, so the
// token stays out of the peer hub's access log. The platform's scheme match is
// case-sensitive: it must be exactly "Bearer".
private Map bearer(String token) {
    return ['Authorization': "Bearer ${token}".toString()]
}

// Probe each peer's audit/status (runIn'd off initialize) so the config page shows ok / auth /
// unreachable per hub before a scan is ever run. One probe at a time: each callback starts the next.
void probePeers() {
    checkVersion()
    probePeer(0)
}

private void probePeer(int i) {
    List peers = (state.peerList ?: []) as List
    if (i >= peers.size()) return
    Map peer = peers[i] as Map
    try {
        asynchttpGet('probePeerCallback', [uri: "${peer.baseUrl}/audit/status", headers: bearer(peerToken(peer)), contentType: 'application/json', timeout: 8], [i: i, pid: peer.pid, baseUrl: peer.baseUrl])
    } catch (Exception e) {
        logNet "Peer probe ${peer.label} failed: ${e.message}"
        setPeerReachable(peer, 'unreachable')
        probePeer(i + 1)
    }
}

void probePeerCallback(resp, data) {
    int i = data.i as int
    int status = resp.getStatus()
    String reachable
    if (resp.hasError()) {
        logNet "Peer probe ${data.baseUrl} failed: HTTP ${status}: ${resp.getErrorMessage()}"
        reachable = (status == 401 || status == 403) ? 'auth' : 'unreachable'
    } else {
        reachable = (status == 200) ? 'ok' : "http ${status}"
    }
    setPeerReachable([pid: data.pid, baseUrl: data.baseUrl], reachable)
    probePeer(i + 1)
}

private String reachabilityKey(Map peer) { "${app.id}:${peer.pid}".toString() }

private void setPeerReachable(Map peer, String reachable) {
    peerReachability.put(reachabilityKey(peer), [baseUrl: peer.baseUrl as String, reachable: reachable])
}

// The stored baseUrl guards against a result from a probe started before initialize() changed the peer's URL.
private String peerReachable(Map peer) {
    Map r = peerReachability.get(reachabilityKey(peer))
    return (r && r.baseUrl == peer.baseUrl) ? r.reachable as String : null
}

private void reprobeIfNoResults() {
    List peers = (state.peerList ?: []) as List
    if (peers && !peers.any { peerReachable(it as Map) }) runIn(2, 'probePeers')
}

// ===== API =====
// POST /api/reinit — re-run the lifecycle as if the user had clicked Done (re-syncs the UI, re-probes
// peers, arms the version-check cron, refreshes the update-label badge). A code push alone does NOT
// fire updated()/initialize(), so the deploy chain calls this after pushing new code.
Map apiReinit() {
    checkVersion()
    if (!checkOAuth()) return render(status: 403, contentType: 'text/plain', data: 'OAuth not enabled')
    logCfg "Reinitialize requested via API (running updated())"
    updated()
    return jsonResponse([success: true, version: CODE_VERSION])
}

// GET /api/version/check — current vs. latest GitHub version, for the SPA update badge.
Map apiVersionCheck() {
    checkVersion()
    if (!checkOAuth()) return render(status: 403, contentType: 'text/plain', data: 'OAuth not enabled')
    String latest = checkGithubVersion()
    if (!latest) return jsonResponse([error: "Unable to check for updates"])
    return jsonResponse([
        currentVersion: CODE_VERSION,
        latestVersion: latest,
        updateAvailable: isNewer(latest, CODE_VERSION),
        editorPath: getAppEditorPath()
    ])
}

// POST /api/ui/sync — manual re-download of the UI HTML from GitHub, for the SPA's Check-for-updates button.
Map apiSyncUI() {
    checkVersion()
    if (!checkOAuth()) return render(status: 403, contentType: 'text/plain', data: 'OAuth not enabled')
    logInfo "Manual UI sync requested via API..."
    boolean success = syncUIBlocking()
    return jsonResponse([success: success, version: CODE_VERSION])
}

// GET /api/peers — labels + index + reachability. NEVER returns tokens.
Map apiPeers() {
    checkVersion()
    if (!checkOAuth()) return render(status: 403, contentType: 'text/plain', data: 'OAuth not enabled')
    List out = []
    (state.peerList ?: []).eachWithIndex { Map p, int i -> out << [index: i, label: p.label, reachable: peerReachable(p), webBase: p.webBase ?: ''] }
    return jsonResponse([peers: out])
}

// GET /api/peer?hub=<idx>&op=start|status|data[&scanId=...] — same-origin forwarder.
// op is whitelisted; the caller passes a hub INDEX, never a URL or token.
Map apiPeer() {
    checkVersion()
    if (!checkOAuth()) return render(status: 403, contentType: 'text/plain', data: 'OAuth not enabled')
    String op = (params.op ?: '') as String
    if (!(op in ['start', 'status', 'data'])) return jsonResponse([error: "invalid op"], 400)
    List peers = (state.peerList ?: []) as List
    String hubParam = (params.hub ?: '') as String
    Integer idx = hubParam.isInteger() ? hubParam.toInteger() : null
    if (idx == null || idx < 0 || idx >= peers.size()) return jsonResponse([error: "unknown hub"], 400)
    Map peer = peers[idx] as Map
    String base = peer.baseUrl, token = peerToken(peer)
    // Query via the query: map, not inline in the uri: 2.5.1.x drops an inline uri query
    // string (token/scanId are URL-safe, so the map carries them cleanly).
    String url; String method = 'GET'
    Map q = [:]
    if (op == 'start')       { url = "${base}/audit/start"; method = 'POST' }
    else if (op == 'status') { url = "${base}/audit/status"; String rawSid = params.scanId as String; if (rawSid && rawSid ==~ /[A-Za-z0-9_\-]+/) q.scanId = rawSid }
    else                     { url = "${base}/audit/data" }
    try {
        def body = null
        Closure handler = { resp -> body = resp.data }
        Map common = [uri: url, headers: bearer(token), contentType: 'application/json']
        if (q) common.query = q
        if (method == 'POST') httpPost(common + [requestContentType: 'application/json', timeout: 30], handler)
        else                  httpGet(common + [timeout: 90], handler)
        return jsonResponse(body ?: [:])
    } catch (groovyx.net.http.HttpResponseException e) {
        // Hub Inspector answers 404 when it has no scan in memory (cleared by a restart or update).
        Integer st = (e.response?.status ?: e.statusCode) as Integer
        // Non-2xx statuses so the SPA stops polling instead of retrying through the cloud relay.
        if (st == 404) return jsonResponse([error: "no scan"], 404)
        logWarn "peer ${idx} ${op} failed: HTTP ${st}"
        return jsonResponse([error: "HTTP ${st}".toString()], 502)
    } catch (Exception e) {
        String safeMsg = (e.message ?: '')?.replaceAll(/access_token=[^&\s]+/, 'access_token=REDACTED')
                                           ?.replaceAll(/Bearer\s+\S+/, 'Bearer REDACTED')
        logWarn "peer ${idx} ${op} failed: ${e.class?.simpleName}: ${safeMsg}"
        return jsonResponse([error: "peer call failed"], 502)
    }
}

// ===== UI SERVING =====
Map serveUI() {
    checkVersion()
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
            .replace('${app_version}', CODE_VERSION)
        return render(status: 200, contentType: 'text/html', data: html)
    } catch (Exception e) {
        logError "serveUI: ${e.message}"
        return render(status: 500, contentType: 'text/plain', data: "Error serving UI: ${e.message}")
    }
}

// ── Logging (app) ─────────────────────────────────────────────────────
//   ⬇️ Evt  ⬆️ Cmd  🔧 Cfg  🌐 Net  ⏰ Sched  🏷️ Ver  ·  ⚠️ Warn  🛑 Error  🔬 Trace
private String logp(String e) { "${e} ${stripUpdateBadge(app.getLabel())}: " }

void logEvt  (String m) { if (debugEnable) log.debug logp('⬇️') + m }
void logCmd  (String m) { if (txtEnable != false) log.info  logp('⬆️') + m }
void logCfg  (String m) { if (txtEnable != false) log.info  logp('🔧') + m }
void logNet  (String m) { if (debugEnable) log.debug logp('🌐') + m }
void logSched(String m) { if (debugEnable) log.debug logp('⏰') + m }
void logVer  (String m) { log.warn  logp('🏷️') + m }

void logWarn (String m) { log.warn  logp('⚠️') + m }
void logError(String m) { log.error logp('🛑') + m }
void logTrace(String m) { if (traceEnable) log.trace logp('🔬') + m }
void logInfo (String m) { if (txtEnable != false) log.info  "${stripUpdateBadge(app.getLabel())}: ${m}" }
void logDebug(String m) { if (debugEnable) log.debug "${stripUpdateBadge(app.getLabel())}: ${m}" }

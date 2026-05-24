/*
 * Multi-Hub Inventory — read-only cross-hub device aggregator.
 * Copyright (c) 2026 PJ. SPDX-License-Identifier: MIT
 */
import groovy.transform.Field

@Field static final String APP_VERSION = "0.1.0"
@Field static final String UI_FILE = "multi_hub_inventory_ui.html"
@Field static final String IMPORT_URL_APP = "https://raw.githubusercontent.com/iamtrep/hubitat/refs/heads/main/apps/MultiHubInventory/MultiHubInventory.groovy"

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
    }
}

// Matches the /apps/api/<id>/api base + token even if the user pastes a longer path (e.g. a ui.html link); the \b + [^?]* lets extra path segments be consumed before the query string.
// Parse "http://<ip>/apps/api/<id>/api/...?access_token=<tok>" into baseUrl (through /api) + token.
private Map parsePeerUrl(String raw) {
    java.util.regex.Matcher m = (raw =~ /(https?:\/\/[^?\s]*?\/apps\/api\/\d+\/api)\b[^?]*\?.*?access_token=([a-fA-F0-9\-]+)/)
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
void installed() { state.peerIds = [1]; checkOAuth(); initialize() }
void updated()   { initialize() }
void initialize() {
    if (!state.accessToken) checkOAuth()
    List peerList = []
    (state.peerIds ?: []).each { Integer p ->
        String raw = settings["peer_${p}_url"] as String
        if (!raw) return
        Map parsed = parsePeerUrl(raw)
        if (!parsed) { logWarn "peer ${p}: could not parse URL"; return }
        String label = (settings["peer_${p}_label"] as String) ?: parsed.baseUrl
        peerList << [label: label, baseUrl: parsed.baseUrl, token: parsed.token, reachable: null]
    }
    state.peerList = peerList
    logInfo "Multi-Hub Inventory initialized with ${peerList.size()} peer(s)"
}

// ===== OAUTH + HELPERS =====
private boolean checkOAuth() {
    if (!state.accessToken) {
        try { createAccessToken() } catch (Exception e) { logError "OAuth not enabled: ${e.message}"; return false }
    }
    return state.accessToken != null
}
private Map jsonResponse(Object data) { return render(contentType: 'application/json', data: groovy.json.JsonOutput.toJson(data)) }
private void logInfo(String m)  { log.info  "MultiHubInventory: ${m}" }
private void logWarn(String m)  { log.warn  "MultiHubInventory: ${m}" }
private void logError(String m) { log.error "MultiHubInventory: ${m}" }

// ===== API =====
// GET /api/peers — labels + index + reachability. NEVER returns tokens.
Map apiPeers() {
    if (!checkOAuth()) return render(status: 403, contentType: 'text/plain', data: 'OAuth not enabled')
    List out = []
    (state.peerList ?: []).eachWithIndex { Map p, int i -> out << [index: i, label: p.label, reachable: p.reachable] }
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
        if (!bytes) return render(status: 404, contentType: 'text/plain', data: "${UI_FILE} not found in File Manager. Upload it.")
        String html = new String(bytes, 'UTF-8')
            .replace('${access_token}', state.accessToken)
            .replace('${api_base}', fullLocalApiServerUrl)
        return render(status: 200, contentType: 'text/html', data: html)
    } catch (Exception e) {
        logError "serveUI: ${e.message}"
        return render(status: 500, contentType: 'text/plain', data: "Error serving UI: ${e.message}")
    }
}

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
    if (!state.peerIds) state.peerIds = [1]
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

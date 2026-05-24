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

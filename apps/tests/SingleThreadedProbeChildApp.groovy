// Copyright (c) 2025-2026 PJ
// SPDX-License-Identifier: MIT

// Child app of Single Threaded Probe: its endpoint calls into the parent so the
// test can time a child-app-to-parent call. The test pushes a second copy for
// the singleThreaded parent; keep the name and parent keys on their own lines.

definition(
    name: "Single Threaded Probe Child App",
    namespace: "tests",
    author: "PJ",
    description: "Child app for the singleThreaded entry-point test. Test use only.",
    category: "Convenience",
    iconUrl: "",
    iconX2Url: "",
    parent: "tests:Single Threaded Probe",
    oauth: true
)

preferences {
    page(name: "mainPage", title: "Single Threaded Probe Child App", install: true, uninstall: true) {
        section { label title: "Instance label", required: false }
    }
}

mappings {
    path("/callParent") { action: [GET: "apiCallParent"] }
}

void installed() { checkOAuth() }
void updated() { checkOAuth() }

String ensureToken() {
    checkOAuth()
    return state.accessToken
}

Map apiCallParent() {
    parent.childWork(params.tag as String, (params.ms ?: "0") as Integer, "childApp", now())
    render(contentType: "application/json", data: groovy.json.JsonOutput.toJson([done: now()]))
}

private boolean autoEnableOAuth() {
    String typeId = app.getAppTypeId()?.toString()
    String ver = null
    try {
        httpGet([uri: "http://127.0.0.1:8080", path: "/app/ajax/code", query: [id: typeId], timeout: 15]) { resp -> ver = resp.data?.version?.toString() }
    } catch (e) { log.error "code version: ${e.message}"; return false }
    boolean ok = false
    try {
        httpPost([uri: "http://127.0.0.1:8080", path: "/app/edit/update", requestContentType: "application/x-www-form-urlencoded",
                  body: [id: typeId, version: ver, oauthEnabled: "true", _action_update: "Update"], timeout: 20]) { resp -> ok = true }
    } catch (e) { log.error "enable OAuth: ${e.message}" }
    return ok
}

private boolean checkOAuth() {
    if (state.accessToken) return true
    try { createAccessToken(); return state.accessToken != null } catch (e) {
        if (autoEnableOAuth()) { try { createAccessToken(); return state.accessToken != null } catch (e2) { log.error "token: ${e2.message}" } }
        return false
    }
}

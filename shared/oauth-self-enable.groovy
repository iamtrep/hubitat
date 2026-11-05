// Copyright (c) 2026 PJ
// SPDX-License-Identifier: MIT
//
// Shared block: an app with mappings turns OAuth on for its own app type, so nobody has to
// toggle it in the code editor. Needs oauth: true in definition(). Call checkOAuth() from
// installed(), updated(), each mappings handler and any page that shows the token.
// See shared/README.md.
// block-version: 1
// requires: logDebug, logError

// Creates the access token, turning OAuth on first when the token can't be created yet.
private boolean checkOAuth() {
    if (state.accessToken) return true
    try {
        createAccessToken()
        return state.accessToken != null
    } catch (Exception e) {
        logDebug "OAuth is not enabled yet, turning it on"
        if (!autoEnableOAuth()) return false
        try {
            createAccessToken()
            return state.accessToken != null
        } catch (Exception e2) {
            logError "OAuth is on but token creation failed: ${e2.message}"
            return false
        }
    }
}

// Turns OAuth on for this app type through the hub's loopback API, which needs no login.
// The save needs the code's current version number, read first.
private boolean autoEnableOAuth() {
    String base = "http://127.0.0.1:8080"
    String typeId = app.getAppTypeId()?.toString()
    if (!typeId) { logError "Could not find this app's type id"; return false }
    String internalVer = null
    try {
        httpGet([uri: base, path: "/app/ajax/code", query: [id: typeId], timeout: 15]) { resp ->
            internalVer = resp.data?.version?.toString()
        }
    } catch (Exception e) {
        logError "Could not read the app code version: ${e.message}"
        return false
    }
    if (!internalVer) { logError "Could not determine the app code version"; return false }
    boolean success = false
    try {
        httpPost([uri: base, path: "/app/edit/update", requestContentType: "application/x-www-form-urlencoded",
                  body: [id: typeId, version: internalVer, oauthEnabled: "true", _action_update: "Update"], timeout: 20]) { resp ->
            success = true
        }
    } catch (Exception e) {
        logError "Could not enable OAuth: ${e.message}"
    }
    return success
}

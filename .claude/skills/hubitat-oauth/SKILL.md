---
name: hubitat-oauth
description: Add self-enabling OAuth to a Hubitat Groovy app so the user never has to manually enable it in the code editor
allowed-tools: Read, Edit, Bash, Grep
---

<!--
Copyright (c) 2025-2026 PJ
SPDX-License-Identifier: MIT
-->

# Hubitat Self-Enabling OAuth Skill

Integrate automatic OAuth bootstrapping into a Hubitat Groovy app so it enables OAuth and creates
its own access token at runtime — no code-editor clicks needed.

Hubitat requires OAuth to be explicitly enabled per app type (code editor → OAuth toggle).
`createAccessToken()` throws if OAuth is not enabled. This skill adds three helper methods that
catch that exception, call the hub's loopback API (`http://127.0.0.1:8080`) to enable OAuth, and
retry — all without a session cookie (hub trusts local loopback).

## Step 1 — Set `oauth: true` in the definition block

Add `oauth: true` to the app's `definition()` block (necessary but not sufficient — must also
be enabled programmatically, which Step 2 does). Note the exact `name:` string — Step 2 uses
it to look up the app's type ID.

```groovy
definition(name: "My App Name", namespace: "myns", oauth: true, ...)
```

## Step 2 — Add the three helper methods

Add these three private methods anywhere in the file (by convention near other hub-API helpers).
Replace `"My App Name"` in `getAppTypeId()` with the app's exact `name:` from `definition()`.

### `getAppTypeId()` — looks up the app type ID via loopback

```groovy
private String getAppTypeId() {
    String typeId = null
    try {
        httpGet([uri: "http://127.0.0.1:8080", path: "/hub2/userAppTypes", timeout: 15]) { resp ->
            List apps = resp.data instanceof List ? (List) resp.data : []
            Map match = apps.find { it.name == "My App Name" }  // must match definition() name exactly
            if (match) typeId = match.id?.toString()
        }
    } catch (e) {
        log.debug "Failed to fetch user app types: ${e.message}"
    }
    return typeId
}
```

### `autoEnableOAuth()` — fetches current version, then POSTs to enable OAuth

```groovy
private boolean autoEnableOAuth() {
    String typeId = getAppTypeId()
    if (!typeId) { log.error "Could not find app type ID."; return false }

    String internalVer = null
    try {
        httpGet([uri: "http://127.0.0.1:8080", path: "/app/ajax/code", query: [id: typeId], timeout: 15]) { resp ->
            internalVer = resp.data?.version?.toString()
        }
    } catch (e) {
        log.error "Failed to fetch app code version: ${e.message}"
        return false
    }
    if (!internalVer) { log.error "Could not determine app code version."; return false }

    boolean success = false
    try {
        httpPost([
            uri: "http://127.0.0.1:8080",
            path: "/app/edit/update",
            requestContentType: "application/x-www-form-urlencoded",
            body: [
                id: typeId,
                version: internalVer,
                oauthEnabled: "true",
                _action_update: "Update"
            ],
            timeout: 20
        ]) { resp ->
            success = true
        }
    } catch (e) {
        log.error "Failed to enable OAuth: ${e.message}"
    }
    return success
}
```

### `checkOAuth()` — main entry point; stores token in `state` so it only runs once

```groovy
private boolean checkOAuth() {
    if (state.accessToken) return true
    try {
        createAccessToken()
        return (state.accessToken != null)
    } catch (e) {
        log.debug "OAuth not enabled yet, attempting auto-enable..."
        if (autoEnableOAuth()) {
            try {
                createAccessToken()
                return (state.accessToken != null)
            } catch (e2) {
                log.error "OAuth enabled but token creation failed: ${e2.message}"
                return false
            }
        }
        return false
    }
}
```

## Step 3 — Call `checkOAuth()` in the right places

**On install/update** — so the token is ready before the user hits any page:
```groovy
void installed() { checkOAuth(); initialize() }
void updated()   { checkOAuth(); initialize() }
```

**In every `mappings` handler** — guard all API endpoints at entry:
```groovy
def handleData() {
    if (!checkOAuth()) { return render(status: 403, contentType: "text/plain", data: "OAuth not enabled.") }
    // ... handler logic
}
```

**On any preferences page that shows the access token or an OAuth-gated URL** — call
`checkOAuth()` at the top of the page closure so the token exists before building the UI.

## Step 4 — Handle the failure case

If `checkOAuth()` returns false, guard any page or handler that depends on OAuth and show a
useful error. For the full manual-fallback UI snippet and guidance on diagnosing a partial OAuth
state (mismatched `getAppTypeId()` name, version conflicts, stale `state.accessToken`, missing
toggle, conflicting redirect URIs), load `references/recovery.md`.

## Key constraints

- `127.0.0.1:8080` only — loopback calls only work from Groovy running on the hub itself.
- The `version` field in the `autoEnableOAuth()` POST is **mandatory**. Omitting it causes a
  silent failure or version conflict error.
- The `name` string in `getAppTypeId()` must **exactly** match `name:` in `definition()`.
- `createAccessToken()` is a Hubitat platform method; do not define it yourself.
- `state.accessToken` is set automatically by `createAccessToken()` on success.

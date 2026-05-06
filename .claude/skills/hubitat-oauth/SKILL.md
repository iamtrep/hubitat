---
name: hubitat-oauth
description: Add self-enabling OAuth to a Hubitat Groovy app so the user never has to manually enable it in the code editor
allowed-tools: Read, Edit, Bash, Grep
---

# Hubitat Self-Enabling OAuth Skill

Integrate automatic OAuth bootstrapping into a Hubitat Groovy app. The app detects at runtime
that OAuth isn't enabled, enables it via the hub's internal API, and creates its own access
token — all without requiring the user to touch the code editor.

## Background

Hubitat requires OAuth to be explicitly enabled per app type (in the code editor → OAuth toggle).
`createAccessToken()` throws an exception if OAuth is not enabled. This skill wires up a
self-healing pattern: catch that exception, call the hub's internal loopback API to enable OAuth,
then retry token creation.

All HTTP calls use `http://127.0.0.1:8080` — this only works from within the hub (Groovy sandbox).
The `/app/edit/update` POST requires no session cookie; the hub trusts local loopback.

## Step 1 — Set `oauth: true` in the definition block

Confirm the app's `definition()` block includes `oauth: true`. This is necessary but not
sufficient — it also must be enabled via the hub UI or programmatically (which this skill does).

```groovy
definition(
    name: "My App Name",
    namespace: "myns",
    oauth: true,
    ...
)
```

Note the exact `name:` string — it is used to look up the app's type ID in Step 3.

## Step 2 — Add the three helper methods

Add the following three private methods to the app. They can go anywhere in the file; by
convention place them near other hub-API helpers.

### `getAppTypeId()`

Calls `GET /hub2/userAppTypes` and matches on the app's exact `name` string:

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

Replace `"My App Name"` with the actual `name:` value from the app's `definition()` block.

### `autoEnableOAuth()`

Fetches the current internal version number (required by the POST — acts as a concurrency guard),
then POSTs to `/app/edit/update` with `oauthEnabled: "true"`:

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

### `checkOAuth()`

The main entry point. Tries `createAccessToken()`, catches the exception that fires when OAuth
isn't enabled, auto-enables, then retries. Stores the token in `state` so this only runs once:

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

**In every `mappings` handler** — guard all API endpoints:
```groovy
mappings {
    path("/data") { action: [GET: "handleData"] }
}

def handleData() {
    if (!checkOAuth()) {
        return render(status: 403, contentType: "text/plain", data: "OAuth not enabled.")
    }
    // ... handler logic
}
```

**On any preferences page that shows the access token or an OAuth-gated URL** — call
`checkOAuth()` at the top of the page closure so the token exists before building the UI.

## Step 4 — Handle the failure case in the UI

If `checkOAuth()` returns false (auto-enable failed — e.g., the hub is locked down), show a
clear manual fallback in the preferences page rather than a broken UI:

```groovy
if (!checkOAuth()) {
    return dynamicPage(name: "mainPage", title: "OAuth Required") {
        section("OAuth Setup Failed") {
            paragraph "Could not auto-enable OAuth. Enable it manually:\n" +
                      "1. Go to Apps Code in the Hubitat UI.\n" +
                      "2. Click your app name.\n" +
                      "3. Click <b>OAuth</b>, then <b>Enable OAuth in App</b>, then <b>Update</b>.\n" +
                      "4. Return here and re-open the app."
        }
    }
}
```

## Key constraints

- `127.0.0.1:8080` only — loopback calls only work from Groovy running on the hub itself.
- The `version` field in the `autoEnableOAuth()` POST is **mandatory**. Omitting it causes a
  silent failure or version conflict error.
- The `name` string in `getAppTypeId()` must **exactly** match `name:` in `definition()`.
- `createAccessToken()` is a Hubitat platform method; do not define it yourself.
- `state.accessToken` is set automatically by `createAccessToken()` on success.

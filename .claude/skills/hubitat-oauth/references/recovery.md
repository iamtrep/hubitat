<!--
Copyright (c) 2025-2026 PJ
SPDX-License-Identifier: MIT
-->

# OAuth Recovery Procedures

> **Load this when:** `checkOAuth()` returns false, auto-enable failed, OAuth is already partly configured but not working, or the user reports that an app can't create an access token even after the skill ran.

## When auto-enable fails — manual fallback

If `autoEnableOAuth()` returns false (hub locked down, loopback blocked, version mismatch),
show a clear manual path in the preferences page rather than a broken UI:

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

## Diagnosing a partial OAuth state

Symptoms of a partial state:

- `createAccessToken()` throws even though `oauth: true` is in `definition()`.
- `state.accessToken` is null after the app was previously working.
- Hub logs show `OAuth not enabled yet` on every app open (auto-enable is running but failing silently).

Check these in order:

1. **`oauth: true` present?** Open Apps Code, confirm the `definition()` block has `oauth: true`.
   If missing, add it, push the code, and re-save preferences to trigger `updated()`.

2. **OAuth toggle enabled in hub?** In Apps Code → click the app → click **OAuth**. If the toggle
   shows "Enable OAuth in App" (i.e. not yet enabled), the POST in `autoEnableOAuth()` is failing.
   Enable manually to unblock, then investigate the loopback call.

3. **`getAppTypeId()` returning null?** The `name:` string in `getAppTypeId()` must match
   `definition()` exactly (case-sensitive, including spaces). Check hub logs for
   `"Failed to fetch user app types"` or `"Could not find app type ID"`.

4. **Version mismatch in `autoEnableOAuth()`?** If the app code was pushed externally between the
   GET (fetch version) and the POST (enable OAuth), the hub may reject the POST with a version
   conflict. The POST is idempotent for the OAuth toggle; re-running is safe.

5. **`state.accessToken` stale or corrupted?** Clear it manually:
   - Open the hub's Groovy console and run:
     ```groovy
     def app = getApp(<installedAppId>)
     app.state.remove("accessToken")
     ```
   - Then re-open the app's preferences page to trigger `checkOAuth()` fresh.

## Mismatched or missing displayName / displayLink

These are OAuth metadata fields optionally added inside the `definition()` block. They appear in
the hub's OAuth consent screen. If they're missing, OAuth still works — they're cosmetic. If
they're present but wrong, update them in `definition()` and push the code; no hub toggle is
needed.

```groovy
definition(
    name: "My App Name",
    namespace: "myns",
    oauth: true,
    displayName: "My App",
    displayLink: "https://example.com",
    ...
)
```

## Conflicting redirect URI

Hubitat's OAuth flow uses a redirect URI that the hub constructs from the installed app ID.
Apps cannot override this URI. If a cloud service rejects the hub's redirect URI:

- Confirm the cloud service's allowed redirect URIs include the hub's URI format:
  `http://<hub-ip>/oauth/callback` or `https://<hub-cloud-url>/oauth/callback`.
- The hub does not support custom redirect URIs per-app; the URI is fixed by the platform.
- If the service requires a specific URI, file a feature request with Hubitat; this cannot
  be worked around in Groovy code alone.

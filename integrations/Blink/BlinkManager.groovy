// Copyright (c) 2025-2026 PJ
// SPDX-License-Identifier: MIT

/*
 * Blink Manager — Parent Integration App
 *
 * Authenticates against the Blink cloud API (OAuth + PKCE + 2FA), discovers
 * cameras and networks, polls the homescreen endpoint, and dispatches state
 * to child Blink Network / Blink Camera drivers.
 *
 * Authentication flow follows the empirically-derived six-step sequence used
 * by the iOS app: authorize → signin (CSRF) → credentials → 2FA PIN →
 * authorize-for-code → token exchange. Refresh tokens persist in state and
 * a refresh is scheduled 5 minutes before expiry.
 */

import com.hubitat.app.ChildDeviceWrapper
import groovy.json.JsonOutput
import groovy.transform.CompileStatic
import groovy.transform.Field
import java.security.MessageDigest

definition(
    name: "Blink Manager",
    namespace: "iamtrep",
    author: "pj",
    description: "Blink camera integration with OAuth authentication and per-camera child drivers",
    menu: "Integrations",
    category: "Safety & Security",
    singleInstance: true,
    importUrl: "https://raw.githubusercontent.com/iamtrep/hubitat/refs/heads/main/integrations/Blink/BlinkManager.groovy",
    iconUrl: "",
    iconX2Url: ""
)

// --- Constants ---

@Field static final String CODE_VERSION = "1.0.1"

@Field static final String OAUTH_BASE_URL = "https://api.oauth.blink.com"
@Field static final String CLIENT_ID = "ios"
@Field static final String APP_BRAND = "blink"
@Field static final String BLINK_CLIENT_APP_VERSION = "50.1"
@Field static final String SCOPE = "client"
@Field static final String REDIRECT_URI = "immedia-blink://applinks.blink.com/signin/callback"

// User agents match blinkpy's OAUTH_USER_AGENT / OAUTH_TOKEN_USER_AGENT.
@Field static final String UA_HTML = "Mozilla/5.0 (iPhone; CPU iPhone OS 18_7 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.1 Mobile/15E148 Safari/604.1"
@Field static final String UA_TOKEN = "Blink/2511191620 CFNetwork/3860.200.71 Darwin/25.1.0"

@Field static final long TOKEN_REFRESH_BUFFER_MS = 300_000L

@Field static final String DNI_PREFIX_NETWORK = "blink-network-"
@Field static final String DNI_PREFIX_CAMERA = "blink-camera-"

@Field static final String DRIVER_NETWORK = "Blink Network"
@Field static final String DRIVER_CAMERA = "Blink Camera"

@Field static final int HTTP_TIMEOUT = 15
@Field static final int DEBUG_LOG_TIMEOUT = 1800

// /media/changed initial-window lookback on first fetch — picks up clips that
// happened in the hour before the integration started, so a new install with
// recent activity isn't entirely silent. Subsequent fetches use the persisted
// cursor (atomicState.lastClipFetchTime).
@Field static final long CLIP_INITIAL_WINDOW_SEC = 3600L

preferences {
    page(name: "mainPage")
    page(name: "loginPage")
    page(name: "diagnosticsPage")
}

// --- Pages ---

Map mainPage() {
    dynamicPage(name: "mainPage", title: "Blink Manager", install: true, uninstall: true) {
        if (isAuthenticated()) {
            section("🟢 Status") {
                paragraph "Connected to Blink — region <b>${state.tier ?: '?'}</b>, account <b>${state.accountId ?: '?'}</b>"
                if (state.tokenExpiry) {
                    String exp = new Date((long) state.tokenExpiry).format("yyyy-MM-dd HH:mm", location.timeZone)
                    paragraph "<small>Token expires ${exp}.</small>"
                }
                if (atomicState.homescreenSummary) paragraph atomicState.homescreenSummary
            }
            section(hideable: true, hidden: false, "📷 Devices") {
                renderChildDeviceList()
                List<Map> orphans = (atomicState.orphanedDevices ?: []) as List<Map>
                if (orphans.size() > 0) {
                    paragraph "<span style='color:orange'><b>⚠️ Orphaned (${orphans.size()}):</b></span>"
                    orphans.each { Map o ->
                        paragraph "<a href='/device/edit/${o.id}' target='_blank'>${o.label}</a> <small>(${o.dni})</small>"
                    }
                    input "btnRemoveOrphans", "button", title: "Remove orphaned devices"
                }
            }
            section("⏱️ Polling") {
                input "pollRate", "enum", title: "Poll interval (seconds)", options: ["60", "120", "300"], defaultValue: "60", submitOnChange: true
                input "btnRefreshNow", "button", title: "🔄 Refresh now"
            }
            section(hideable: true, hidden: true, "🔔 Blink notifications") {
                paragraph "<small>These mirror the notification toggles in the Blink mobile app. They are <b>account-wide</b> (not per-network or per-camera) and control whether Blink itself fires push notifications for events like low battery, camera offline, motion, etc. Toggle here and click <b>Done</b> at the top to save. See <a href='https://github.com/iamtrep/hubitat/tree/main/integrations/Blink#blink-notifications' target='_blank'>the README</a> for what each flag does — the exact set Blink exposes varies by account and changes over time.</small>"
                Map flags = (atomicState.notificationFlags ?: [:]) as Map
                if (flags.size() == 0) {
                    paragraph "<small><i>Not fetched yet — click the button below.</i></small>"
                } else {
                    flags.sort().each { String name, Object value ->
                        input "notif_${name}", "bool", title: humanizeFlag(name), defaultValue: (value as boolean), description: "<code>${name}</code>"
                    }
                }
                input "btnRefreshNotifications", "button", title: "🔄 Refresh notification flags"
            }
            section("🛠️ Diagnostics & advanced") {
                href "diagnosticsPage", title: "Open diagnostics", description: "Token state, manual re-fetch, disconnect, reset auth"
            }
        } else {
            section("🔴 Not connected") {
                paragraph "Connect your Blink account to get started."
                href "loginPage", title: "Login to Blink", description: "Enter your Blink credentials"
            }
        }
        section(hideable: true, hidden: true, "⚙️ Settings") {
            label title: "Assign a name", required: false
            input name: "txtEnable", type: "bool", title: "Enable descriptionText logging", defaultValue: true
            input name: "debugEnable", type: "bool", title: "Enable debug logging", defaultValue: false, submitOnChange: true
            if (debugEnable) {
                input name: "traceEnable", type: "bool", title: "Enable trace logging", defaultValue: false
            }
        }
    }
}

// --- Diagnostics sub-page ---
//
// Houses the rare/destructive actions and raw state inspection so the main
// page stays focused on day-to-day use.

Map diagnosticsPage() {
    dynamicPage(name: "diagnosticsPage", title: "Blink Manager — Diagnostics", nextPage: "mainPage") {
        section("🩺 State snapshot") {
            String at = state.accessToken ? "set (${state.accessToken.toString().length()} chars)" : "<i>null</i>"
            String rt = state.refreshToken ? "set (${state.refreshToken.toString().length()} chars)" : "<i>null</i>"
            String exp = state.tokenExpiry ? new Date((long) state.tokenExpiry).format('HH:mm:ss', location.timeZone) : "<i>null</i>"
            paragraph """
                <b>isAuthenticated:</b> ${isAuthenticated()}<br>
                <b>accessToken:</b> ${at}<br>
                <b>refreshToken:</b> ${rt}<br>
                <b>tokenExpiry:</b> ${exp}<br>
                <b>tier:</b> ${state.tier ?: '<i>null</i>'} &nbsp; <b>accountId:</b> ${state.accountId ?: '<i>null</i>'}<br>
                <b>hardwareId:</b> <small>${state.hardwareId ?: '<i>null</i>'}</small><br>
                <b>needs2fa:</b> ${state.needs2fa ?: '<i>null</i>'}
            """.stripIndent()
        }
        if (!state.accountId) {
            section("Recovery") {
                paragraph "<span style='color:orange'><b>No account ID</b> — tier discovery failed.</span>"
                input "btnFetchTier", "button", title: "Re-fetch tier info"
            }
        }
        section("🚪 Disconnect / reset") {
            paragraph "<small>Use these only when something is wrong. <b>Disconnect</b> clears auth tokens but keeps tier/account info for one-click re-auth; <b>Reset</b> nukes everything and requires a full re-login.</small>"
            input "btnDisconnect", "button", title: "Disconnect"
            input "btnResetAuth", "button", title: "Reset auth state (full wipe)"
        }
    }
}

// snake_case Blink flag → "Snake case" — humanize the dynamic flag names Blink
// returns so users see "Low battery" rather than "low_battery" as the toggle label.
@CompileStatic
private static String humanizeFlag(String name) {
    if (!name) return ""
    String s = name.replace("_", " ").replace("-", " ").trim()
    if (s.length() == 0) return s
    return s.substring(0, 1).toUpperCase() + s.substring(1)
}

private void renderChildDeviceList() {
    List<ChildDeviceWrapper> kids = getChildDevices()
    if (kids.size() == 0) {
        paragraph "<small>No child devices yet — wait for the first poll or click <b>Refresh now</b>.</small>"
        return
    }
    List<ChildDeviceWrapper> networks = kids.findAll { it.deviceNetworkId?.startsWith(DNI_PREFIX_NETWORK) }
    List<ChildDeviceWrapper> cameras = kids.findAll { it.deviceNetworkId?.startsWith(DNI_PREFIX_CAMERA) }
    if (networks.size() > 0) {
        StringBuilder sb = new StringBuilder("<b>🛡️ Networks (${networks.size()})</b><br>")
        networks.each { ChildDeviceWrapper c ->
            sb.append("&nbsp;&nbsp;• <a href='/device/edit/${c.id}' target='_blank'>${c.label ?: c.name}</a><br>")
        }
        paragraph sb.toString()
    }
    if (cameras.size() > 0) {
        StringBuilder sb = new StringBuilder("<b>📹 Cameras (${cameras.size()})</b><br>")
        cameras.each { ChildDeviceWrapper c ->
            String typeTag = c.getDataValue("cameraType") ?: ""
            String typeSuffix = (typeTag && typeTag != "camera") ? " <small>(${typeTag})</small>" : ""
            sb.append("&nbsp;&nbsp;• <a href='/device/edit/${c.id}' target='_blank'>${c.label ?: c.name}</a>${typeSuffix}<br>")
        }
        paragraph sb.toString()
    }
}

Map loginPage() {
    dynamicPage(name: "loginPage", title: "Blink Login", nextPage: "mainPage") {
        if (state.authError) {
            section {
                paragraph "<span style='color:red;'><b>Error:</b> ${state.authError}</span>"
                state.remove("authError")
            }
        }
        if (isAuthenticated()) {
            section {
                paragraph "<b>✓ Connected to Blink.</b> Click <b>Next</b> to return to the main page."
            }
            return
        }
        if (state.needs2fa) {
            section("Two-Factor Authentication") {
                paragraph "A verification PIN has been sent to your device. Enter it below."
                input "blinkPin", "text", title: "Verification PIN", required: true, submitOnChange: true
                input "btnVerifyPin", "button", title: "Verify PIN"
            }
        } else {
            section("Credentials") {
                input "blinkEmail", "text", title: "Email", required: true, submitOnChange: true
                input "blinkPassword", "password", title: "Password", required: true, submitOnChange: true
                input "btnLogin", "button", title: "Login"
            }
        }
    }
}

// --- Lifecycle ---

void installed() {
    logDebug "installed"
    state.version = CODE_VERSION
    initialize()
}

void updated() {
    logDebug "updated"
    unsubscribe()
    unschedule()
    if (debugEnable) runIn(DEBUG_LOG_TIMEOUT, "turnOffDebugLogging")
    initialize()
    pushNotificationFlagChanges()
}

void uninstalled() {
    logInfo "uninstalled"
    unschedule()
    getChildDevices()?.each { ChildDeviceWrapper c ->
        try { deleteChildDevice(c.deviceNetworkId) } catch (Exception ignored) {}
    }
}

void initialize() {
    logDebug "initialize"
    if (state.version != CODE_VERSION) {
        logWarn "version change: ${state.version} -> ${CODE_VERSION}"
        state.version = CODE_VERSION
    }
    subscribe(location, "systemStart", "systemStartHandler")
    if (!isAuthenticated()) {
        logDebug "not authenticated yet, skipping schedules"
        return
    }
    if (state.tokenExpiry && now() >= ((long) state.tokenExpiry)) {
        logInfo "token expired, refreshing"
        refreshAccessToken()
    } else {
        scheduleTokenRefresh()
    }
    schedulePolling()
    runIn(2, "pollHomescreen")
}

void systemStartHandler(evt) {
    logInfo "systemStart: re-evaluating tokens + polling"
    if (!isAuthenticated()) return
    if (state.tokenExpiry && now() >= ((long) state.tokenExpiry)) {
        refreshAccessToken()
    } else {
        scheduleTokenRefresh()
    }
    schedulePolling()
    runIn(3, "pollHomescreen")
}

private void schedulePolling() {
    // 60 s is the lower bound per blinkpy's README ("API calls faster than 60 s
    // is not recommended as it can overwhelm Blink's servers"). Force-clamp in
    // case a user has a stale "30" value persisted from a prior release.
    int rate = Math.max(60, (pollRate ?: "60").toString().toInteger())
    Random rng = new Random()
    int offset = rng.nextInt(60)
    String cron
    if (rate == 60) {
        cron = "${offset} * * ? * *"
    } else {
        int minutes = rate / 60 as int
        cron = "${offset} */${minutes} * ? * *"
    }
    logDebug "scheduling poll: ${cron}"
    schedule(cron, "pollHomescreen")
}

void turnOffDebugLogging() {
    logWarn "debug logging disabled"
    app.updateSetting("debugEnable", [value: "false", type: "bool"])
    app.updateSetting("traceEnable", [value: "false", type: "bool"])
}

// --- Button Handler ---

void appButtonHandler(String btn) {
    logDebug "appButtonHandler: ${btn}"
    switch (btn) {
        case "btnLogin":          startOAuthFlow(); break
        case "btnVerifyPin":      verifyPin(); break
        case "btnRefreshNow":            pollHomescreen(); break
        case "btnFetchTier":             fetchTierInfo(); break
        case "btnDisconnect":            logout(); break
        case "btnRemoveOrphans":         removeOrphans(); break
        case "btnResetAuth":             clearAuthState(); break
        case "btnRefreshNotifications":  fetchNotificationFlags(); break
    }
}

private void removeOrphans() {
    List<Map> orphans = (atomicState.orphanedDevices ?: []) as List<Map>
    orphans.each { Map o ->
        String dni = o.dni as String
        logWarn "removing orphan: ${dni} (${o.label})"
        try { deleteChildDevice(dni) } catch (Exception e) { logError "delete ${dni}: ${e.message}" }
    }
    atomicState.orphanedDevices = []
    logInfo "removed ${orphans.size()} orphaned devices"
}

// --- OAuth: Steps 1-3 (CSRF + Credentials) ---

void startOAuthFlow() {
    logInfo "starting OAuth flow"

    if (!blinkEmail || !blinkPassword) {
        state.authError = "Email and password are required"
        return
    }

    Map pkce = generatePkce()
    state.codeVerifier = pkce.verifier
    state.hardwareId = UUID.randomUUID().toString().toUpperCase()
    state.cookieJar = [:]

    String authorizeUrl = buildAuthorizeUrl((String) pkce.challenge)
    logDebug "step 1: GET authorize"
    httpGetNoRedirect(authorizeUrl, headersHtmlGet())
    logDebug "step 1 cookies: ${state.cookieJar?.keySet()}"

    logDebug "step 2: GET signin"
    String csrfToken = null
    String step2Html = httpGetNoRedirect("${OAUTH_BASE_URL}/oauth/v2/signin", headersHtmlGetWithCookie())
    if (step2Html) {
        csrfToken = extractCsrfFromHtml(step2Html)
        logDebug "step 2 CSRF from HTML: ${csrfToken ? 'found' : 'not found'}"
    }
    if (!csrfToken && state.cookieJar?.containsKey("csrf-protection")) {
        csrfToken = ((Map) state.cookieJar)["csrf-protection"] as String
        logDebug "using csrf-protection cookie as CSRF token"
    }
    if (!csrfToken) {
        state.authError = "Failed to extract CSRF token"
        cleanupEphemeralState()
        return
    }
    state.csrfToken = csrfToken

    logDebug "step 3: POST credentials"
    String postBody = toQueryString([
        username    : blinkEmail,
        password    : blinkPassword,
        "csrf-token": csrfToken
    ])

    try {
        httpPost([
            uri        : "${OAUTH_BASE_URL}/oauth/v2/signin",
            headers    : headersHtmlPostWithCookie(),
            body       : postBody,
            textParser : true,
            timeout    : HTTP_TIMEOUT
        ]) { resp ->
            captureCookies(resp)
            logDebug "step 3 status: ${resp.status}"
            if (resp.status == 412) {
                logInfo "2FA required (HTTP 412)"
                state.needs2fa = true
            } else {
                String location = getRedirectLocation(resp)
                String code = extractCodeFromUrl(location)
                if (!code) {
                    String body = readResponseBody(resp)
                    code = extractCodeFromUrl(body)
                    String newCsrf = extractCsrfFromHtml(body)
                    if (newCsrf) state.csrfToken = newCsrf
                }
                if (code) {
                    logInfo "auth code received without 2FA"
                    exchangeCodeForTokens(code)
                    return
                }
                logInfo "assuming 2FA required"
                state.needs2fa = true
            }
        }
    } catch (groovyx.net.http.HttpResponseException e) {
        logDebug "step 3 exception: HTTP ${e.statusCode}"
        if (e.statusCode == 412) {
            logInfo "2FA required (HTTP 412)"
            state.needs2fa = true
            try { captureCookies(e.response) } catch (Exception ignored) {}
        } else {
            String errBody = readResponseBody(e.response)
            logError "step 3 failed: HTTP ${e.statusCode}, body: ${errBody?.take(500)}"
            state.authError = "Login failed (HTTP ${e.statusCode})"
            cleanupEphemeralState()
            return
        }
    } catch (Exception e) {
        logError "step 3 failed: ${e.message}"
        state.authError = "Login failed: ${e.message}"
        cleanupEphemeralState()
        return
    }

    app.removeSetting("blinkPassword")
}

// --- OAuth: Steps 4-6 (PIN + Auth Code + Token Exchange) ---

void verifyPin() {
    logInfo "verifying 2FA PIN"
    if (!blinkPin) {
        state.authError = "PIN is required"
        return
    }

    String csrfToken = state.csrfToken ?: ""
    String postBody = toQueryString([
        "2fa_code"   : blinkPin,
        "csrf-token" : csrfToken,
        "remember_me": "false"
    ])

    boolean pinAccepted = false
    try {
        httpPost([
            uri        : "${OAUTH_BASE_URL}/oauth/v2/2fa/verify",
            headers    : headersHtmlPostWithCookie(),
            body       : postBody,
            textParser : true,
            timeout    : HTTP_TIMEOUT
        ]) { resp ->
            captureCookies(resp)
            logDebug "step 4 status: ${resp.status}"
            String body = readResponseBody(resp)
            logTrace "step 4 response: ${body?.take(500)}"
            if (resp.status == 200 || resp.status == 201 || body?.contains("auth-completed")) {
                pinAccepted = true
            }
        }
    } catch (groovyx.net.http.HttpResponseException e) {
        if (e.statusCode == 201) {
            pinAccepted = true
            try { captureCookies(e.response) } catch (Exception ignored) {}
        } else if (e.statusCode in [400, 401, 403]) {
            state.authError = "Invalid PIN. Please try again."
            return
        } else {
            logError "step 4 failed: HTTP ${e.statusCode} ${e.message}"
            state.authError = "PIN verification failed (HTTP ${e.statusCode})"
            return
        }
    } catch (Exception e) {
        logError "step 4 failed: ${e.message}"
        state.authError = "PIN verification failed: ${e.message}"
        return
    }

    if (!pinAccepted) {
        state.authError = "PIN verification did not succeed. Please try again."
        return
    }

    app.removeSetting("blinkPin")
    runStep5()
}

void runStep5() {
    logDebug "step 5: GET authorize (with session cookies)"
    String authorizeUrl = "${OAUTH_BASE_URL}/oauth/v2/authorize"
    String authCode = null

    try {
        httpGet([
            uri             : authorizeUrl,
            headers         : headersHtmlGetWithCookie(),
            textParser      : true,
            followRedirects : false,
            timeout         : HTTP_TIMEOUT
        ]) { resp ->
            captureCookies(resp)
            String location = getRedirectLocation(resp)
            logDebug "step 5 Location: ${location?.take(200)}"
            if (location) authCode = extractCodeFromUrl(location)
        }
    } catch (groovyx.net.http.HttpResponseException e) {
        logDebug "step 5 exception status: ${e.statusCode}"
        try {
            String locValue = getRedirectLocation(e.response)
            if (locValue) authCode = extractCodeFromUrl(locValue)
            captureCookies(e.response)
        } catch (Exception ignored) {}
    } catch (Exception e) {
        logDebug "step 5 non-HTTP exception: ${e.message}, retrying with redirects"
        try {
            httpGet([
                uri        : authorizeUrl,
                headers    : headersHtmlGetWithCookie(),
                textParser : true,
                timeout    : HTTP_TIMEOUT
            ]) { resp ->
                captureCookies(resp)
                String location = getRedirectLocation(resp)
                if (location) authCode = extractCodeFromUrl(location)
            }
        } catch (Exception e2) {
            // Custom-scheme redirect (immedia-blink://) raises an error; extract code from message.
            authCode = extractCodeFromUrl(e2.message ?: "")
        }
    }

    if (!authCode) {
        state.authError = "Failed to obtain authorization code. Please try logging in again."
        cleanupEphemeralState()
        return
    }

    exchangeCodeForTokens(authCode)
}

void exchangeCodeForTokens(String code) {
    logDebug "step 6: POST token exchange"

    String postBody = toQueryString([
        app_brand    : APP_BRAND,
        client_id    : CLIENT_ID,
        code         : code,
        code_verifier: state.codeVerifier,
        grant_type   : "authorization_code",
        hardware_id  : state.hardwareId,
        redirect_uri : REDIRECT_URI,
        scope        : SCOPE
    ])

    try {
        httpPost([
            uri               : "${OAUTH_BASE_URL}/oauth/token",
            headers           : [
                "User-Agent": UA_TOKEN,
                "Accept"    : "application/json"
            ],
            body              : postBody,
            requestContentType: "application/x-www-form-urlencoded",
            contentType       : "application/json",
            timeout           : HTTP_TIMEOUT
        ]) { resp ->
            Map json = (resp.data instanceof String) ? (new groovy.json.JsonSlurper().parseText(resp.data as String) as Map) : (resp.data as Map)
            logTrace "token exchange response keys: ${json?.keySet()}"
            state.accessToken = json.access_token
            state.refreshToken = json.refresh_token
            long expiresIn = (json.expires_in ?: 3600L) as long
            state.tokenExpiry = now() + (expiresIn * 1000L)
            logInfo "token exchange OK, expires in ${expiresIn}s, accessToken: ${state.accessToken ? 'set' : 'NULL'}"
            scheduleTokenRefresh()
            cleanupEphemeralState()
            fetchTierInfo()
            schedulePolling()
            runIn(2, "pollHomescreen")
            runIn(4, "fetchNotificationFlags")
        }
    } catch (Exception e) {
        logError "token exchange failed: ${e.message}"
        state.authError = "Token exchange failed: ${e.message}"
        cleanupEphemeralState()
    }
}

void refreshAccessToken() {
    logDebug "refreshing access token"
    if (!state.refreshToken) {
        logError "no refresh token available"
        return
    }

    String postBody = toQueryString([
        client_id    : CLIENT_ID,
        grant_type   : "refresh_token",
        hardware_id  : state.hardwareId ?: UUID.randomUUID().toString().toUpperCase(),
        refresh_token: state.refreshToken,
        scope        : SCOPE
    ])

    try {
        httpPost([
            uri               : "${OAUTH_BASE_URL}/oauth/token",
            headers           : [
                "User-Agent": UA_TOKEN,
                "Accept"    : "application/json"
            ],
            body              : postBody,
            requestContentType: "application/x-www-form-urlencoded",
            contentType       : "application/json",
            timeout           : HTTP_TIMEOUT
        ]) { resp ->
            Map json = (resp.data instanceof String) ? (new groovy.json.JsonSlurper().parseText(resp.data as String) as Map) : (resp.data as Map)
            state.accessToken = json.access_token
            if (json.refresh_token) state.refreshToken = json.refresh_token
            long expiresIn = (json.expires_in ?: 3600L) as long
            state.tokenExpiry = now() + (expiresIn * 1000L)
            logInfo "token refreshed, expires in ${expiresIn}s"
            scheduleTokenRefresh()
        }
    } catch (Exception e) {
        logError "token refresh failed: ${e.message}"
        String msg = e.message ?: ""
        if (msg.contains("401") || msg.contains("400")) {
            logWarn "refresh token rejected; tokens cleared (tier/accountId/hardwareId preserved for one-click re-auth)"
            clearTokensOnly()
        }
    }
}

void scheduleTokenRefresh() {
    unschedule("refreshAccessToken")
    if (!state.tokenExpiry) return
    long refreshAt = ((long) state.tokenExpiry) - TOKEN_REFRESH_BUFFER_MS
    long delaySecs = Math.max(60L, (long) ((refreshAt - now()) / 1000L))
    logDebug "scheduling token refresh in ${delaySecs}s"
    runIn(delaySecs, "refreshAccessToken")
}

private void ensureValidToken() {
    if (!state.accessToken) return
    if (state.tokenExpiry && now() >= (((long) state.tokenExpiry) - TOKEN_REFRESH_BUFFER_MS)) {
        logDebug "token near expiry, refreshing inline"
        refreshAccessToken()
    }
}

// --- Tier & Account ---

void fetchTierInfo() {
    logDebug "fetching tier info"
    String url = "https://rest-prod.immedia-semi.com/api/v1/users/tier_info"
    // blinkpy's tier_info call shape: android UA + form-urlencoded Content-Type.
    Map headers = [
        "User-Agent"   : "27.0ANDROID_28373244",
        "Authorization": "Bearer ${state.accessToken}",
        "Accept"       : "application/json",
        "Content-Type" : "application/x-www-form-urlencoded"
    ]
    try {
        httpGet([uri: url, headers: headers, timeout: HTTP_TIMEOUT]) { resp ->
            Map json = resp.data as Map
            if (json.tier) state.tier = json.tier
            if (json.account_id) state.accountId = json.account_id
        }
    } catch (groovyx.net.http.HttpResponseException e) {
        logWarn "tier_info failed: HTTP ${e.statusCode}"
    } catch (Exception e) {
        logWarn "tier_info failed: ${e.message}"
    }
    if (!state.tier) state.tier = "prod"
    logInfo "tier: ${state.tier}, accountId: ${state.accountId}"
}

// --- Polling & Discovery ---

void pollHomescreen() {
    if (!isAuthenticated()) {
        logDebug "pollHomescreen: not authenticated"
        return
    }
    if (!state.accountId) {
        logDebug "pollHomescreen: no accountId, fetching tier info first"
        fetchTierInfo()
        if (!state.accountId) return
    }
    ensureValidToken()

    String url = "https://rest-${state.tier}.immedia-semi.com/api/v3/accounts/${state.accountId}/homescreen"
    logTrace "polling: ${url}"
    asynchttpGet("handleHomescreenResponse", [
        uri     : url,
        headers : bearerHeaders(),
        timeout : HTTP_TIMEOUT
    ])
}

void handleHomescreenResponse(resp, data) {
    try {
        if (resp.hasError()) {
            logError "homescreen error: ${resp.getErrorMessage()}"
            return
        }
        int status = resp.getStatus()
        if (status == 401) {
            logWarn "homescreen 401, refreshing token and retrying"
            refreshAccessToken()
            runIn(3, "pollHomescreen")
            return
        }
        if (status != 200) {
            logWarn "homescreen: HTTP ${status}${describeHttpBody(resp)}"
            return
        }
        Map json = resp.json as Map
        if (!json) {
            logWarn "homescreen: empty response"
            return
        }
        int nNet = (json.networks ?: []).size()
        Map acc = (json.accessories ?: [:]) as Map
        int nCam = (json.cameras ?: []).size() + (json.owls ?: []).size() + (json.doorbells ?: []).size() + (json.superiors ?: []).size() + (acc.storm ?: []).size()
        logInfo "homescreen OK: ${nNet} networks, ${nCam} cameras"
        processHomescreen(json)
    } catch (Exception e) {
        logError "handleHomescreenResponse: ${e.message}"
    }
}

private void processHomescreen(Map json) {
    List<Map> networks = (json.networks ?: []) as List<Map>
    List<Map> cameras = []

    // Normalize all camera-shaped entries to a single list, tagging type.
    ((json.cameras ?: []) as List<Map>).each { Map c -> cameras << tagType(c, "camera") }
    ((json.owls ?: []) as List<Map>).each { Map c -> cameras << tagType(c, "owl") }
    ((json.doorbells ?: []) as List<Map>).each { Map c -> cameras << tagType(c, "doorbell") }
    ((json.superiors ?: []) as List<Map>).each { Map c -> cameras << tagType(c, "superior") }
    Map accessories = (json.accessories ?: [:]) as Map
    ((accessories.storm ?: []) as List<Map>).each { Map c -> cameras << tagType(c, "storm") }

    List<Map> syncModules = (json.sync_modules ?: []) as List<Map>

    syncChildren(networks, cameras, syncModules)
    dispatchToChildren(networks, cameras, syncModules)
    updateHomescreenSummary(networks, cameras, syncModules)
    rotateSignalsFetch(cameras)
    fetchRecentClips()
}

// Fetch /signals for one default camera per poll cycle, rotating through the
// eligible set. Picks up calibrated temperature (blinkpy `temperature_calibrated`).
// One extra async HTTP call per poll cycle — well under the 8-call cap.
private void rotateSignalsFetch(List<Map> cameras) {
    if (!cameras || cameras.size() == 0) return
    // /signals endpoint pattern only covers default cameras; mini/doorbell/etc
    // have their own per-type config endpoints we don't currently call.
    List<Map> eligible = cameras.findAll { ((it.__type as String) ?: "camera") == "camera" }
    if (eligible.size() == 0) return
    int cursor = ((atomicState.signalsCursor ?: 0) as Number).intValue()
    int idx = cursor % eligible.size()
    Map c = eligible[idx]
    String camId = c.id?.toString()
    String netId = (c.network_id ?: c.networkId)?.toString()
    if (camId && netId) {
        logTrace "/signals rotation: camera ${camId} (${idx + 1}/${eligible.size()})"
        fetchCameraSignals(camId, netId)
    }
    atomicState.signalsCursor = (idx + 1) % eligible.size()
}

@CompileStatic
private static Map tagType(Map c, String type) {
    Map copy = [:] as Map
    copy.putAll(c)
    copy.put("__type", type)
    return copy
}

private void syncChildren(List<Map> networks, List<Map> cameras, List<Map> syncModules) {
    Set<String> activeDnis = [] as Set<String>

    // Index sync modules by network for the per-network sync module facts.
    Map<String, Map> smByNetwork = [:]
    syncModules.each { Map sm ->
        String netId = (sm.network_id ?: sm.networkId)?.toString()
        if (netId) smByNetwork[netId] = sm
    }

    networks.each { Map n ->
        String netId = n.id?.toString()
        if (!netId) return
        String dni = "${DNI_PREFIX_NETWORK}${netId}"
        activeDnis << dni
        ChildDeviceWrapper child = getChildDevice(dni)
        if (!child) {
            String label = (n.name ?: "Blink Network ${netId}") as String
            logInfo "creating child: ${DRIVER_NETWORK} '${label}' (${dni})"
            try {
                child = addChildDevice("iamtrep", DRIVER_NETWORK, dni, [name: DRIVER_NETWORK, label: label])
            } catch (Exception e) {
                logError "create ${dni}: ${e.message}"
                return
            }
        }
        child.updateDataValue("networkId", netId)
        Map sm = smByNetwork[netId]
        if (sm?.serial) child.updateDataValue("syncModuleSerial", sm.serial as String)
    }

    cameras.each { Map c ->
        String camId = c.id?.toString()
        if (!camId) return
        String dni = "${DNI_PREFIX_CAMERA}${camId}"
        activeDnis << dni
        ChildDeviceWrapper child = getChildDevice(dni)
        if (!child) {
            String label = (c.name ?: "Blink Camera ${camId}") as String
            logInfo "creating child: ${DRIVER_CAMERA} '${label}' (${dni})"
            try {
                child = addChildDevice("iamtrep", DRIVER_CAMERA, dni, [name: DRIVER_CAMERA, label: label])
            } catch (Exception e) {
                logError "create ${dni}: ${e.message}"
                return
            }
        }
        child.updateDataValue("cameraId", camId)
        child.updateDataValue("networkId", (c.network_id ?: c.networkId)?.toString() ?: "")
        child.updateDataValue("cameraType", c.__type as String ?: "camera")
        // Blink reports clips in /media/changed by `device_name` (not id); store
        // the Blink-side name so we can match clip entries back to this child.
        if (c.name != null) child.updateDataValue("blinkName", c.name.toString())
    }

    // Orphan tracking (no auto-delete).
    // atomicState because syncChildren runs inside the asynchttpGet callback chain
    // (handleHomescreenResponse → processHomescreen → syncChildren) — plain state
    // writes from async paths can silently vanish per ARCHITECTURE.md §"State tiers".
    List<Map> orphans = []
    getChildDevices().each { ChildDeviceWrapper c ->
        if (!activeDnis.contains(c.deviceNetworkId)) {
            orphans << [dni: c.deviceNetworkId, label: c.label ?: c.name, id: c.id]
        }
    }
    atomicState.orphanedDevices = orphans
}

private void dispatchToChildren(List<Map> networks, List<Map> cameras, List<Map> syncModules) {
    Map<String, Map> smByNetwork = [:]
    syncModules.each { Map sm ->
        String netId = (sm.network_id ?: sm.networkId)?.toString()
        if (netId) smByNetwork[netId] = sm
    }

    int netsDispatched = 0
    int camsDispatched = 0
    int missingChildren = 0

    networks.each { Map n ->
        String netId = n.id?.toString()
        if (!netId) return
        ChildDeviceWrapper child = getChildDevice("${DNI_PREFIX_NETWORK}${netId}")
        if (!child) { missingChildren++; return }
        Map sm = smByNetwork[netId] ?: [:]
        Map update = [
            armed           : n.armed == true,
            online          : (sm.status ?: "unknown") as String,
            firmwareVersion : sm.fw_version ?: sm.firmware ?: "",
            syncModuleSerial: sm.serial ?: "",
            cameraCount     : countCamerasForNetwork(cameras, netId)
        ]
        try {
            child.handleNetworkUpdate(update)
            netsDispatched++
        } catch (Exception e) {
            logError "handleNetworkUpdate failed on ${child.deviceNetworkId}: ${e.message}"
        }
    }

    String tier = state.tier as String
    String acct = state.accountId?.toString()
    cameras.each { Map c ->
        String camId = c.id?.toString()
        if (!camId) return
        ChildDeviceWrapper child = getChildDevice("${DNI_PREFIX_CAMERA}${camId}")
        if (!child) { missingChildren++; return }
        try {
            child.handleCameraUpdate(cameraSnapshot(c, tier, acct))
            camsDispatched++
        } catch (Exception e) {
            logError "handleCameraUpdate failed on ${child.deviceNetworkId}: ${e.message}"
        }
    }

    logInfo "dispatched: ${netsDispatched} networks, ${camsDispatched} cameras${missingChildren > 0 ? ', ' + missingChildren + ' missing' : ''}"
}

@CompileStatic
private static int countCamerasForNetwork(List<Map> cameras, String netId) {
    int n = 0
    cameras.each { Map c ->
        String nid = (c.get("network_id") ?: c.get("networkId"))?.toString()
        if (nid == netId) n++
    }
    return n
}

@CompileStatic
private static Map cameraSnapshot(Map c, String tier, String accountId) {
    Map<String, Object> out = [:]
    // Note: `motion` is NOT derived from `c.motion_detected` — Blink doesn't
    // reliably populate that flag in the homescreen response. Motion events are
    // fired from the /media/changed clip-arrival path in recentClipsResponse,
    // matching blinkpy's sync_module.py behaviour (motion = a new clip arrived).
    out["motionEnabled"] = (c.get("enabled") == true)
    Object battery = c.get("battery_voltage")
    if (battery instanceof Number) {
        Number v = (Number) battery
        int pct = batteryPercent(v.intValue())
        out["battery"] = pct
    }
    if (c.get("battery") != null) {
        out["batteryState"] = c.get("battery")?.toString()
    }
    Object temp = c.get("temperature")
    if (temp instanceof Number) out["temperature"] = (Number) temp
    Object wifi = c.get("wifi_strength")
    if (wifi instanceof Number) out["wifiSignal"] = (Number) wifi
    // Bar-scale indicators are nested under `signals` on the homescreen entry.
    // signals.lfr (1-5) = sync-module link strength.
    // signals.battery (1-3) = Blink's app-displayed battery bars.
    // signals.temp duplicates the top-level uncalibrated temp (we use the calibrated
    // value from the separate /signals endpoint via fetchCameraSignals instead).
    Map signals = (c.get("signals") instanceof Map) ? (Map) c.get("signals") : null
    if (signals != null) {
        Object lfr = signals.get("lfr")
        if (lfr instanceof Number) out["lfrSignal"] = (Number) lfr
        Object batBars = signals.get("battery")
        if (batBars instanceof Number) out["batteryBars"] = (Number) batBars
    }
    out["online"] = ((c.get("status") ?: "unknown") as String)
    Object fw = c.get("fw_version") ?: c.get("firmware")
    if (fw) out["firmwareVersion"] = fw.toString()
    // AC power: passed through as string. Wired cameras (doorbells, floodlights)
    // populate this to indicate mains-power presence; battery-only cameras may omit it.
    Object acPower = c.get("ac_power")
    if (acPower != null) out["acPower"] = acPower.toString()
    // Thumbnail: c.thumbnail is a token (often a Unix timestamp integer) or a relative
    // path; assemble into a full URL per blinkpy's update_images logic. This is a
    // STILL IMAGE, not a recorded clip — true clip URLs come from /media/changed (M2).
    Object thumb = c.get("thumbnail")
    if (thumb != null) {
        String url = buildThumbnailUrl(c, thumb, tier, accountId)
        if (url) out["lastThumbnailUrl"] = url
    }
    // Generic camera-config update time. NOT clip-recorded time.
    Object updatedAt = c.get("updated_at")
    if (updatedAt) out["lastUpdated"] = updatedAt.toString()
    return out
}

@CompileStatic
private static String buildThumbnailUrl(Map c, Object thumb, String tier, String accountId) {
    if (thumb == null || !tier || !accountId) return null
    String s = thumb.toString()
    if (s.startsWith("http")) return s
    String netId = (c.get("network_id") ?: c.get("networkId"))?.toString()
    String camId = c.get("id")?.toString()
    // Blink's own `type` field is the slug Blink expects in the thumbnail URL
    // path (e.g. "catalina" for default cameras, "owl" for mini, etc.).
    // Our internal `__type` tag is for variant dispatch and is NOT the right
    // slug here — blinkpy `camera.py` uses config.get("type") directly.
    String type = (c.get("type") ?: c.get("__type") ?: "camera") as String
    if (!netId || !camId) return null
    String base = "https://rest-${tier}.immedia-semi.com"
    // Numeric thumbnail token == Unix timestamp; assemble v3 media URL (blinkpy
    // camera.py update_images path).
    if (s ==~ /\d+/) {
        return "${base}/api/v3/media/accounts/${accountId}/networks/${netId}/${type}/${camId}/thumbnail/thumbnail.jpg?ts=${s}&ext="
    }
    // Old-API: relative path, append .jpg unless it already has the v3 query suffix.
    if (s.endsWith("&ext=")) {
        return s.startsWith("/") ? "${base}${s}" : "${base}/${s}"
    }
    String prefix = s.startsWith("/") ? "" : "/"
    return "${base}${prefix}${s}.jpg"
}

@CompileStatic
private static int batteryPercent(int voltageHundredths) {
    // Blink reports battery voltage in 100ths of volts per cell (e.g. 163 = 1.63V/cell;
    // two cells in series ≈ 3.26V pack). Map per-cell 1.40V..1.80V to 0..100%.
    if (voltageHundredths <= 0) return 0
    int low = 140
    int high = 180
    if (voltageHundredths <= low) return 0
    if (voltageHundredths >= high) return 100
    return (int) Math.round(((voltageHundredths - low) * 100.0d) / (high - low))
}

private void updateHomescreenSummary(List<Map> networks, List<Map> cameras, List<Map> syncModules) {
    StringBuilder sb = new StringBuilder()
    sb.append("Last poll <b>${new Date().format('HH:mm:ss', location.timeZone)}</b> — ")
    sb.append("${networks.size()} networks, ${cameras.size()} cameras, ${syncModules.size()} sync modules<br>")
    networks.each { Map n ->
        String marker = (n.armed == true) ? "🔒" : "🔓"
        String state = (n.armed == true) ? "armed" : "disarmed"
        sb.append("&nbsp;&nbsp;${marker} <b>${n.name ?: n.id}</b> — ${state}<br>")
    }
    // atomicState: same async-chain reasoning as syncChildren's orphan write.
    atomicState.homescreenSummary = sb.toString()
}

// --- Child Callbacks ---

// Per-action command-verification budgets. Network arming completes within seconds;
// per-camera capture (clip, thumbnail) on a sleeping battery camera can take 30s+.
@Field static final int CMD_RETRY_NETWORK = 10  // 10 × 2s = 20s
@Field static final int CMD_RETRY_CAMERA  = 30  // 30 × 2s = 60s

void arm(String networkId) {
    logInfo "arming network ${networkId}"
    String path = "/api/v1/accounts/${state.accountId}/networks/${networkId}/state/arm"
    blinkPostAsync(path, "commandPostResponse", [label: "arm ${networkId}", maxAttempts: CMD_RETRY_NETWORK])
}

void disarm(String networkId) {
    logInfo "disarming network ${networkId}"
    String path = "/api/v1/accounts/${state.accountId}/networks/${networkId}/state/disarm"
    blinkPostAsync(path, "commandPostResponse", [label: "disarm ${networkId}", maxAttempts: CMD_RETRY_NETWORK])
}

void enableMotion(String cameraId) {
    Map cc = cameraContext(cameraId)
    if (!cc) { logWarn "enableMotion: unknown camera ${cameraId}"; return }
    if (cc.type != "camera") {
        logWarn "enableMotion not implemented for type=${cc.type} (camera ${cameraId})"
        return
    }
    String path = "/network/${cc.networkId}/camera/${cameraId}/enable"
    blinkPostAsync(path, "commandPostResponse", [label: "enableMotion ${cameraId}", maxAttempts: CMD_RETRY_NETWORK])
}

void disableMotion(String cameraId) {
    Map cc = cameraContext(cameraId)
    if (!cc) { logWarn "disableMotion: unknown camera ${cameraId}"; return }
    if (cc.type != "camera") {
        logWarn "disableMotion not implemented for type=${cc.type} (camera ${cameraId})"
        return
    }
    String path = "/network/${cc.networkId}/camera/${cameraId}/disable"
    blinkPostAsync(path, "commandPostResponse", [label: "disableMotion ${cameraId}", maxAttempts: CMD_RETRY_NETWORK])
}

void snapThumbnail(String cameraId) {
    Map cc = cameraContext(cameraId)
    if (!cc) { logWarn "snapThumbnail: unknown camera ${cameraId}"; return }
    if (cc.type != "camera") {
        logWarn "snapThumbnail not implemented for type=${cc.type} (camera ${cameraId})"
        return
    }
    String path = "/network/${cc.networkId}/camera/${cameraId}/thumbnail"
    blinkPostAsync(path, "commandPostResponse", [label: "snapThumbnail ${cameraId}", maxAttempts: CMD_RETRY_CAMERA])
}

void recordClip(String cameraId) {
    Map cc = cameraContext(cameraId)
    if (!cc) { logWarn "recordClip: unknown camera ${cameraId}"; return }
    if (cc.type != "camera") {
        logWarn "recordClip not implemented for type=${cc.type} (camera ${cameraId})"
        return
    }
    String path = "/network/${cc.networkId}/camera/${cameraId}/clip"
    blinkPostAsync(path, "commandPostResponse", [label: "recordClip ${cameraId}", maxAttempts: CMD_RETRY_CAMERA])
}

// --- Command verification (blinkpy wait_for_command pattern) ---

void commandPostResponse(resp, data) {
    String label = data?.label ?: "command"
    try {
        if (resp.hasError()) {
            logError "${label}: ${resp.getErrorMessage()}${describeHttpBody(resp)}"
            return
        }
        int status = resp.getStatus()
        if (status != 200) {
            logWarn "${label}: HTTP ${status}${describeHttpBody(resp)}"
            return
        }
        Map json = resp.json as Map
        Object cmdIdObj = json?.id
        String netId = (json?.network_id ?: json?.networkId)?.toString()
        if (cmdIdObj == null || !netId) {
            logInfo "${label} accepted (no command id); refreshing in 3s"
            runIn(3, "pollHomescreen")
            return
        }
        int cmdId = (cmdIdObj as Number).intValue()
        int maxAttempts = ((data?.maxAttempts ?: CMD_RETRY_NETWORK) as Number).intValue()
        logDebug "${label} accepted: networkId=${netId}, cmdId=${cmdId}, maxAttempts=${maxAttempts}"
        pollCommandStatus([networkId: netId, cmdId: cmdId, attempt: 0, label: label, maxAttempts: maxAttempts])
    } catch (Exception e) {
        logError "commandPostResponse: ${e.message}"
        runIn(3, "pollHomescreen")
    }
}

private void pollCommandStatus(Map ctx) {
    String url = "https://rest-${state.tier}.immedia-semi.com/network/${ctx.networkId}/command/${ctx.cmdId}"
    asynchttpGet("commandStatusResponse", [
        uri        : url,
        headers    : bearerHeaders(),
        contentType: "application/json",
        timeout    : HTTP_TIMEOUT
    ], ctx)
}

void commandStatusResponse(resp, data) {
    Map ctx = data as Map
    int attempt = ((ctx.attempt ?: 0) as Number).intValue() + 1
    String label = (ctx.label ?: "command") as String

    int statusCode = 0
    boolean complete = false
    try {
        if (resp.hasError()) {
            logWarn "${label} cmd-status: ${resp.getErrorMessage()}${describeHttpBody(resp)}"
        } else {
            int httpStatus = resp.getStatus()
            if (httpStatus != 200) {
                logWarn "${label} cmd-status: HTTP ${httpStatus}${describeHttpBody(resp)}"
            } else {
                Map json = resp.json as Map
                statusCode = ((json?.status_code ?: 0) as Number).intValue()
                complete = json?.complete == true
            }
        }
    } catch (Exception e) {
        logWarn "commandStatusResponse parse: ${e.message}"
    }

    if (complete && statusCode == 908) {
        logInfo "${label} confirmed (${attempt} ${attempt == 1 ? 'check' : 'checks'})"
        pollHomescreen()
        return
    }
    if (statusCode != 0 && statusCode != 908) {
        logError "${label} failed server-side: status_code=${statusCode}"
        pollHomescreen()
        return
    }
    int maxAttempts = ((ctx.maxAttempts ?: CMD_RETRY_NETWORK) as Number).intValue()
    if (attempt >= maxAttempts) {
        logWarn "${label} not confirmed after ${attempt} checks (cap=${maxAttempts}); polling anyway"
        pollHomescreen()
        return
    }
    ctx.attempt = attempt
    runInMillis(2000, "pollCommandStatusDeferred", [data: ctx, overwrite: false])
}

void pollCommandStatusDeferred(Map data) {
    pollCommandStatus(data)
}

void refreshNetwork(String networkId) {
    logDebug "refresh requested by network ${networkId}, triggering poll"
    pollHomescreen()
}

void refreshCamera(String cameraId) {
    Map cc = cameraContext(cameraId)
    if (!cc) { pollHomescreen(); return }
    logDebug "refresh requested by camera ${cameraId}, fetching /signals + homescreen"
    pollHomescreen()
    fetchCameraSignals(cameraId, cc.networkId as String)
}

private void fetchCameraSignals(String cameraId, String networkId) {
    if (!networkId) return
    String url = "https://rest-${state.tier}.immedia-semi.com/network/${networkId}/camera/${cameraId}/signals"
    asynchttpGet("cameraSignalsResponse", [
        uri        : url,
        headers    : bearerHeaders(),
        contentType: "application/json",
        timeout    : HTTP_TIMEOUT
    ], [cameraId: cameraId])
}

void cameraSignalsResponse(resp, data) {
    try {
        if (resp.hasError()) {
            logWarn "signals ${data?.cameraId}: ${resp.getErrorMessage()}${describeHttpBody(resp)}"
            return
        }
        int status = resp.getStatus()
        if (status != 200) {
            logWarn "signals ${data?.cameraId}: HTTP ${status}${describeHttpBody(resp)}"
            return
        }
        Map json = resp.json as Map
        if (!json) return
        ChildDeviceWrapper child = getChildDevice("${DNI_PREFIX_CAMERA}${data.cameraId}")
        if (!child) return
        // The /signals endpoint only returns `{temp: <calibrated F>}` per blinkpy
        // (camera.py:281). Bar-scale lfr/wifi/battery come from homescreen, not
        // here. Use this to override the uncalibrated homescreen temperature.
        if (json.temp instanceof Number) {
            child.handleCameraUpdate([temperature: json.temp])
            logDebug "signals ${data.cameraId}: calibrated temp=${json.temp}"
        }
    } catch (Exception e) {
        logError "cameraSignalsResponse: ${e.message}"
    }
}

// --- Recent clips (M2) ---
//
// Fetches /api/v1/accounts/{aid}/media/changed?since=<unix-seconds>&page=1 once
// per poll cycle. Response is `{media: [{device_name, media: "/clip.mp4",
// created_at, deleted}, ...]}` per blinkpy sync_module.py:335. Each new clip
// for a camera updates that child's lastClipUrl + lastClipTime attributes —
// the actual recorded video, distinct from the still-image lastThumbnailUrl.
private void fetchRecentClips() {
    if (!isAuthenticated() || !state.accountId || !state.tier) return
    long sinceUnix = ((atomicState.lastClipFetchTime ?: ((now() / 1000L) - CLIP_INITIAL_WINDOW_SEC)) as Number).longValue()
    // since = ISO 8601 with a UTC offset ("YYYY-MM-DDTHH:MM:SS+0000"); a raw Unix
    // epoch is rejected. Pass the query via the `query:` map, NOT inline in the URI:
    // the 2.5.1.x asynchttpGet path drops an inline query string, so Blink sees no
    // `since` and returns "Bad Time Format" (809); the query map is sent intact.
    String sinceIso = new Date(sinceUnix * 1000L).format("yyyy-MM-dd'T'HH:mm:ssZ", TimeZone.getTimeZone("UTC"))
    String url = "https://rest-${state.tier}.immedia-semi.com/api/v1/accounts/${state.accountId}/media/changed"
    logTrace "fetchRecentClips: since=${sinceIso}"
    asynchttpGet("recentClipsResponse", [
        uri        : url,
        query      : [since: sinceIso, page: 1],
        headers    : bearerHeaders(),
        contentType: "application/json",
        timeout    : HTTP_TIMEOUT
    ])
}

void recentClipsResponse(resp, data) {
    long fetchedAt = (now() / 1000L) as long
    List<Map> media = []
    Map<String, Map> latestByName = [:]
    String errorMsg = null

    try {
        if (resp.hasError()) {
            errorMsg = "error: ${resp.getErrorMessage()}${describeHttpBody(resp)}"
        } else {
            int status = resp.getStatus()
            if (status != 200) {
                errorMsg = "HTTP ${status}${describeHttpBody(resp)}"
            } else {
                Map json = resp.json as Map
                media = (json?.media ?: []) as List<Map>
                // Pick the latest non-deleted clip per Blink camera name.
                media.each { Map entry ->
                    if (entry.deleted == true) return
                    String name = entry.device_name?.toString()
                    if (!name) return
                    String t = entry.created_at?.toString()
                    Map prev = latestByName[name]
                    String prevT = prev?.get("_t") as String
                    if (prev == null || (t != null && (prevT == null || t > prevT))) {
                        Map copy = [:]
                        copy.putAll(entry)
                        copy.put("_t", t)
                        latestByName[name] = copy
                    }
                }
            }
        }
    } catch (Exception e) {
        errorMsg = "parse: ${e.message}"
    }

    // Always dispatch motion (and lastClipUrl/lastClipTime when applicable) to every
    // Blink Camera child — including motion=inactive when nothing arrived. Hubitat's
    // sendEvent will dedup the inactive case after the first fire, so this is cheap.
    // Skipping this loop (the previous behaviour) meant the motion attribute never
    // settled to a value and motion=active alone couldn't be diagnosed.
    String base = "https://rest-${state.tier}.immedia-semi.com"
    int active = 0, inactive = 0, missingName = 0
    Set<String> activeNames = [] as Set<String>
    getChildDevices().each { ChildDeviceWrapper child ->
        if (!child.deviceNetworkId?.startsWith(DNI_PREFIX_CAMERA)) return
        String blinkName = child.getDataValue("blinkName")
        if (!blinkName) { missingName++; return }
        Map entry = latestByName[blinkName]
        String motionValue = (entry != null) ? "active" : "inactive"
        Map update = [motion: motionValue]
        if (entry != null) {
            activeNames << blinkName
            active++
            String clipPath = entry.media?.toString()
            if (clipPath) {
                String fullUrl = clipPath.startsWith("http") ? clipPath : "${base}${clipPath}"
                update.lastClipUrl = fullUrl
            }
            if (entry._t) update.lastClipTime = entry._t as String
        } else {
            inactive++
        }
        try {
            child.handleCameraUpdate(update)
        } catch (Exception e) {
            logError "clip/motion dispatch to ${child.deviceNetworkId}: ${e.message}"
        }
    }

    // Visibility: log on every response so we can diagnose silence vs. response issues.
    long sinceUnix = ((atomicState.lastClipFetchTime ?: 0L) as Number).longValue()
    String sinceIsoForLog = new Date(sinceUnix * 1000L).format("yyyy-MM-dd'T'HH:mm:ssZ", TimeZone.getTimeZone("UTC"))
    if (errorMsg) {
        logWarn "clips fetch (since=${sinceIsoForLog}): ${errorMsg}"
    } else {
        String activeSuffix = activeNames.isEmpty() ? "" : " — motion=active on ${activeNames.join(', ')}"
        String missingSuffix = (missingName > 0) ? ", ${missingName} cameras missing blinkName" : ""
        logInfo "clips fetch (since=${sinceIsoForLog}): ${media.size()} entries, ${active} active, ${inactive} inactive${missingSuffix}${activeSuffix}"
    }

    // Cursor advances even on error to avoid getting permanently stuck on a bad window.
    atomicState.lastClipFetchTime = fetchedAt
}

// --- Notification flags (M1) ---
//
// Blink keeps an account-wide notification posture (low-battery alerts,
// camera-offline alerts, motion alerts, etc.) — toggled in the Blink mobile
// app's settings. Read/write surface is GET/POST to
// /api/v1/accounts/{aid}/notifications/configuration. The Hubitat-side
// pattern: fetch flags after auth (and on-demand via the Refresh button);
// surface each flag as a bool preference under the manager app; on Done,
// diff settings against atomicState and POST changed flags.

void fetchNotificationFlags() {
    if (!isAuthenticated() || !state.accountId || !state.tier) return
    String url = "https://rest-${state.tier}.immedia-semi.com/api/v1/accounts/${state.accountId}/notifications/configuration"
    logDebug "fetching notification flags"
    asynchttpGet("notificationFlagsResponse", [
        uri        : url,
        headers    : bearerHeaders(),
        contentType: "application/json",
        timeout    : HTTP_TIMEOUT
    ])
}

void notificationFlagsResponse(resp, data) {
    try {
        if (resp.hasError()) {
            logWarn "notification flags: ${resp.getErrorMessage()}${describeHttpBody(resp)}"
            return
        }
        int status = resp.getStatus()
        if (status != 200) {
            logWarn "notification flags: HTTP ${status}${describeHttpBody(resp)}"
            return
        }
        Map json = resp.json as Map
        if (!json) return
        // Response shape: blinkpy POSTs {notifications: {...}}; the GET likely
        // mirrors that. Be defensive and accept either {notifications: {...}}
        // or {...} directly.
        Map flagsMap = (json.notifications instanceof Map) ? (json.notifications as Map) : json
        Map<String, Boolean> clean = [:]
        flagsMap.each { k, v ->
            if (v instanceof Boolean) {
                clean[k.toString()] = (Boolean) v
            } else if (v instanceof String && (v == "true" || v == "false")) {
                clean[k.toString()] = (v == "true")
            }
        }
        atomicState.notificationFlags = clean
        // Mirror to settings so the prefs UI reflects current server state,
        // not stale user-side toggles.
        clean.each { String name, Boolean value ->
            app.updateSetting("notif_${name}", [value: value, type: "bool"])
        }
        logInfo "fetched ${clean.size()} notification flags"
    } catch (Exception e) {
        logError "notificationFlagsResponse: ${e.message}"
    }
}

private void pushNotificationFlagChanges() {
    if (!isAuthenticated() || !state.accountId) return
    Map current = (atomicState.notificationFlags ?: [:]) as Map
    if (current.size() == 0) return
    Map<String, Boolean> changes = [:]
    current.each { String name, Object oldVal ->
        Object newVal = settings["notif_${name}"]
        if (newVal != null && (newVal as boolean) != (oldVal as boolean)) {
            changes[name] = (newVal as boolean)
        }
    }
    if (changes.size() == 0) {
        logTrace "no notification flag changes to push"
        return
    }
    setNotificationFlags(changes)
}

private void setNotificationFlags(Map changes) {
    String url = "https://rest-${state.tier}.immedia-semi.com/api/v1/accounts/${state.accountId}/notifications/configuration"
    String body = groovy.json.JsonOutput.toJson([notifications: changes])
    logInfo "updating notification flags: ${changes.keySet().join(', ')}"
    asynchttpPost("notificationUpdateResponse", [
        uri               : url,
        headers           : bearerHeaders(),
        body              : body,
        requestContentType: "application/json",
        contentType       : "application/json",
        timeout           : HTTP_TIMEOUT
    ], [changes: changes])
}

void notificationUpdateResponse(resp, data) {
    try {
        if (resp.hasError()) {
            logError "notification update: ${resp.getErrorMessage()}${describeHttpBody(resp)}"
            return
        }
        int status = resp.getStatus()
        if (status != 200) {
            logWarn "notification update: HTTP ${status}${describeHttpBody(resp)}"
            return
        }
        Map changes = (data?.changes ?: [:]) as Map
        Map flags = (atomicState.notificationFlags ?: [:]) as Map
        flags.putAll(changes)
        atomicState.notificationFlags = flags
        logInfo "notification flags updated: ${changes.keySet().join(', ')}"
    } catch (Exception e) {
        logError "notificationUpdateResponse: ${e.message}"
    }
}

private Map cameraContext(String cameraId) {
    ChildDeviceWrapper child = getChildDevice("${DNI_PREFIX_CAMERA}${cameraId}")
    if (!child) return null
    return [
        networkId: child.getDataValue("networkId"),
        type     : child.getDataValue("cameraType") ?: "camera"
    ]
}

// --- API HTTP ---

private void blinkPostAsync(String path, String handler, Map context) {
    if (!isAuthenticated() || !state.tier) {
        logWarn "blinkPostAsync: not authenticated"
        return
    }
    ensureValidToken()
    String url = "https://rest-${state.tier}.immedia-semi.com${path}"
    Map req = [
        uri               : url,
        headers           : bearerHeaders(),
        body              : "{}",
        requestContentType: "application/json",
        contentType       : "application/json",
        timeout           : HTTP_TIMEOUT
    ]
    asynchttpPost(handler, req, context)
}

private Map bearerHeaders() {
    return [
        "User-Agent"   : UA_TOKEN,
        "Authorization": "Bearer ${state.accessToken}",
        "Accept"       : "application/json"
    ]
}

// Extract the response body from an async HTTP response on a non-2xx path, so
// non-200 log lines surface the actual server message (e.g. "{"message":"Camera
// disabled","code":900}") instead of just an opaque status code. Returns a
// pre-prefixed " body=..." string for direct concatenation into a log message,
// or empty string when no body is available. Capped at 400 chars so a large
// HTML error page doesn't blow up the log.
private String describeHttpBody(resp) {
    if (!resp) return ""
    String body = null
    // 4xx/5xx responses populate getErrorData(); 2xx and IOError paths populate
    // getData() / resp.data. Try all three in sequence for portability across
    // hub firmware versions.
    try { body = resp.getErrorData()?.toString() } catch (Exception ignored) {}
    if (!body) {
        try { body = resp.getData()?.toString() } catch (Exception ignored) {}
    }
    if (!body) {
        try { body = resp.data?.toString() } catch (Exception ignored) {}
    }
    return (body && body.length() > 0) ? " body=${body.take(400)}" : ""
}

// --- Auth State ---

void logout() {
    logInfo "disconnecting from Blink"
    clearAuthState()
    atomicState.remove("homescreenSummary")
    atomicState.remove("orphanedDevices")
}

private boolean isAuthenticated() {
    return state.accessToken != null && state.refreshToken != null
}

private void clearAuthState() {
    unschedule()
    clearTokensOnly()
    state.remove("tier")
    state.remove("accountId")
    state.remove("hardwareId")
    cleanupEphemeralState()
}

private void clearTokensOnly() {
    state.remove("accessToken")
    state.remove("refreshToken")
    state.remove("tokenExpiry")
}

private void cleanupEphemeralState() {
    state.remove("codeVerifier")
    state.remove("cookieJar")
    state.remove("csrfToken")
    state.remove("needs2fa")
    app.removeSetting("blinkPassword")
    app.removeSetting("blinkPin")
}

// --- PKCE ---

private Map generatePkce() {
    String seed = (UUID.randomUUID().toString().replaceAll("-", "") +
                   UUID.randomUUID().toString().replaceAll("-", "")).toLowerCase()
    String verifier = base64UrlEncode(seed.getBytes("UTF-8"))
    if (verifier.size() > 128) verifier = verifier.substring(0, 128)
    byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes("UTF-8"))
    String challenge = base64UrlEncode(digest)
    return [verifier: verifier, challenge: challenge]
}

@CompileStatic
private static String base64UrlEncode(byte[] data) {
    return data.encodeBase64().toString()
        .replace("=", "")
        .replace("+", "-")
        .replace("/", "_")
}

// --- URL Building ---

private String buildAuthorizeUrl(String challenge) {
    String qs = toQueryString([
        app_brand            : APP_BRAND,
        app_version          : BLINK_CLIENT_APP_VERSION,
        client_id            : CLIENT_ID,
        code_challenge       : challenge,
        code_challenge_method: "S256",
        device_brand         : "Apple",
        device_model         : "iPhone16,1",
        device_os_version    : "26.1",
        hardware_id          : state.hardwareId,
        redirect_uri         : REDIRECT_URI,
        response_type        : "code",
        scope                : SCOPE
    ])
    return "${OAUTH_BASE_URL}/oauth/v2/authorize?${qs}"
}

@CompileStatic
private static String toQueryString(Map params) {
    return params.collect { k, v ->
        "${URLEncoder.encode(k.toString(), 'UTF-8')}=${URLEncoder.encode(v.toString(), 'UTF-8')}"
    }.join("&")
}

// --- HTTP Headers ---

@CompileStatic
private static Map headersHtmlGet() {
    return [
        "User-Agent"     : UA_HTML,
        "Accept"         : "text/html,application/xhtml+xml,application/xml;q=0.9",
        "Accept-Language": "en-US,en;q=0.9"
    ]
}

private Map headersHtmlGetWithCookie() {
    Map h = headersHtmlGet()
    h["Cookie"] = cookieHeader()
    return h
}

private Map headersHtmlPostWithCookie() {
    return [
        "User-Agent"     : UA_HTML,
        "Accept"         : "*/*",
        "Accept-Language": "en-US,en;q=0.9",
        "Content-Type"   : "application/x-www-form-urlencoded",
        "Origin"         : OAUTH_BASE_URL,
        "Referer"        : "${OAUTH_BASE_URL}/oauth/v2/signin" as String,
        "Cookie"         : cookieHeader()
    ]
}

// --- HTTP No-Redirect ---

private String httpGetNoRedirect(String url, Map headers) {
    String body = ""
    try {
        httpGet([
            uri             : url,
            headers         : headers,
            textParser      : true,
            followRedirects : false,
            timeout         : HTTP_TIMEOUT
        ]) { resp ->
            captureCookies(resp)
            body = readResponseBody(resp) ?: ""
        }
    } catch (groovyx.net.http.HttpResponseException e) {
        try { captureCookies(e.response) } catch (Exception ignored) {}
        body = readResponseBody(e.response) ?: ""
    } catch (Exception e) {
        logDebug "httpGetNoRedirect: non-HTTP exception: ${e.message}, falling back"
        try {
            httpGet([
                uri        : url,
                headers    : headers,
                textParser : true,
                timeout    : HTTP_TIMEOUT
            ]) { resp ->
                captureCookies(resp)
                body = readResponseBody(resp) ?: ""
            }
        } catch (Exception ignored) {}
    }
    return body
}

private String readResponseBody(resp) {
    if (!resp) return null
    try { return resp.data?.text } catch (Exception ignored) {}
    try { return resp.data?.toString() } catch (Exception ignored) {}
    return null
}

// --- Cookie Management ---

private void captureCookies(response) {
    if (!response) return
    if (!state.cookieJar) state.cookieJar = [:]

    try {
        response.headers?.each { header ->
            String name = headerName(header)
            if (name?.equalsIgnoreCase("Set-Cookie")) {
                String val = headerValue(header)
                if (val) storeCookie(val)
            }
        }
    } catch (Exception e) {
        logTrace "cookie iter failed: ${e.message}"
    }

    try {
        def sc = response.headers?."Set-Cookie"
        if (sc) {
            if (sc instanceof List) {
                ((List) sc).each { storeCookie(it.toString()) }
            } else {
                storeCookie(sc.toString())
            }
        }
    } catch (Exception e) {
        logTrace "cookie direct failed: ${e.message}"
    }
}

private static String headerName(header) {
    try { return header.name } catch (Exception ignored) {}
    try { return header.getName() } catch (Exception ignored) {}
    return null
}

private static String headerValue(header) {
    try { return header.value } catch (Exception ignored) {}
    try { return header.getValue() } catch (Exception ignored) {}
    return null
}

private void storeCookie(String setCookieValue) {
    if (!setCookieValue) return
    String mainPart = setCookieValue.split(";")[0].trim()
    String[] parts = mainPart.split("=", 2)
    if (parts.length == 2) {
        Map jar = (state.cookieJar ?: [:]) as Map
        jar[parts[0].trim()] = parts[1].trim()
        state.cookieJar = jar
    }
}

private String cookieHeader() {
    Map jar = (state.cookieJar ?: [:]) as Map
    if (jar.isEmpty()) return ""
    return jar.collect { k, v -> "${k}=${v}" }.join("; ")
}

// --- HTML Parsing ---

@CompileStatic
private static String extractCsrfFromHtml(String html) {
    if (!html) return null

    def matcher = html =~ /"csrf-token"\s*:\s*"([^"]+)"/
    if (matcher.find() && matcher.group(1).length() >= 20) return matcher.group(1)

    matcher = html =~ /"csrfToken"\s*:\s*"([^"]+)"/
    if (matcher.find() && matcher.group(1).length() >= 20) return matcher.group(1)

    matcher = html =~ /name=["']csrf-token["']\s+value=["']([^"']+)["']/
    if (matcher.find()) return matcher.group(1)

    matcher = html =~ /name=["']_?csrf["']\s*value=["']([^"']+)["']/
    if (matcher.find()) return matcher.group(1)

    matcher = html =~ /value=["']([^"']+)["']\s*name=["']_?csrf["']/
    if (matcher.find()) return matcher.group(1)

    matcher = html =~ /name="csrf[_-]?token"[^>]*content="([^"]+)"/
    if (matcher.find()) return matcher.group(1)

    matcher = html =~ /data-csrf="([^"]+)"/
    if (matcher.find()) return matcher.group(1)

    return null
}

@CompileStatic
private static String extractCodeFromUrl(String url) {
    if (!url) return null
    def matcher = url =~ /[?&]code=([^&\s"]+)/
    return matcher.find() ? matcher.group(1) : null
}

private String getRedirectLocation(response) {
    try {
        def location = response?.headers?.find { it.name?.equalsIgnoreCase("Location") }
        return location?.value ?: location?.getValue()
    } catch (Exception ignored) {
        return null
    }
}

// --- Logging ---

private void logTrace(String message) {
    if (traceEnable) log.trace "${app.label ?: 'Blink Manager'} : ${message}"
}

private void logDebug(String message) {
    if (debugEnable) log.debug "${app.label ?: 'Blink Manager'} : ${message}"
}

private void logInfo(String message) {
    if (txtEnable) log.info "${app.label ?: 'Blink Manager'} : ${message}"
}

private void logWarn(String message) {
    log.warn "${app.label ?: 'Blink Manager'} : ${message}"
}

private void logError(String message) {
    log.error "${app.label ?: 'Blink Manager'} : ${message}"
}

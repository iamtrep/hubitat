// Copyright (c) 2026 PJ
// SPDX-License-Identifier: MIT

/*
 * FGLair Manager — Parent Integration App
 *
 * Authenticates against the Ayla Networks IoT platform (Fujitsu FGLair cloud),
 * discovers Fujitsu indoor units, polls properties on a schedule, and dispatches
 * state to child FujitsuMiniSplit drivers.
 *
 * Protocol facts lifted from ayla-iot-unofficial (MIT) — endpoints, body shapes,
 * integer codes. No source incorporated.
 */

import com.hubitat.app.ChildDeviceWrapper
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.Field

definition(
    name: "FGLair Manager",
    namespace: "iamtrep",
    author: "pj",
    description: "Fujitsu FGLair mini-split integration (Ayla Networks cloud)",
    menu: "Integrations",
    category: "Climate Control",
    singleInstance: true,
    importUrl: "https://raw.githubusercontent.com/iamtrep/hubitat/refs/heads/main/integrations/FGLair/FGLairManager.groovy",
    iconUrl: "",
    iconX2Url: ""
)

@Field static final String APP_VERSION = "0.1.0"

// Region-specific Ayla endpoints + app credentials, lifted from
// ayla-iot-unofficial/src/ayla_iot_unofficial/const.py and fujitsu_consts.py.
// app_id/app_secret are Fujitsu-FGLair-specific identifiers.
@Field static final Map<String, Map<String, String>> REGION = [
    us: [
        userField : "https://user-field.aylanetworks.com",
        ads       : "https://ads-field.aylanetworks.com",
        appId     : "CJIOSP-id",
        appSecret : "CJIOSP-Vb8MQL_lFiYQ7DKjN0eCFXznKZE"
    ],
    eu: [
        userField : "https://user-field-eu.aylanetworks.com",
        ads       : "https://ads-eu.aylanetworks.com",
        appId     : "FGLair-eu-id",
        appSecret : "FGLair-eu-gpFbVBRoiJ8E3fWljDR_Q74YONQ"
    ]
]

@Field static final String DNI_PREFIX_UNIT = "fglair-unit-"
@Field static final String DRIVER_UNIT = "Fujitsu Mini-Split"

@Field static final long TOKEN_REFRESH_BUFFER_MS = 300_000L
@Field static final int HTTP_TIMEOUT = 15
@Field static final int DEBUG_LOG_TIMEOUT = 1800

preferences {
    page(name: "mainPage")
    page(name: "loginPage")
}

Map mainPage() {
    dynamicPage(name: "mainPage", title: "FGLair Manager", install: true, uninstall: true) {
        if (isAuthenticated()) {
            section("Status") {
                paragraph "<b>Connected</b> to FGLair (${settings.region ?: 'us'})"
                if (state.tokenExpiry) {
                    String exp = new Date((long) state.tokenExpiry).format("yyyy-MM-dd HH:mm:ss", location.timeZone)
                    paragraph "Token expires: <b>${exp}</b>"
                }
                input "btnDisconnect", "button", title: "Disconnect"
            }
        } else {
            section {
                paragraph "Connect your FGLair account to get started."
                href "loginPage", title: "Login to FGLair", description: "Enter your FGLair credentials"
            }
        }
        section("Settings") {
            label title: "Assign a name", required: false
            input name: "region",      type: "enum", title: "Region", options: ["us", "eu"], defaultValue: "us"
            input name: "txtEnable",   type: "bool", title: "Enable descriptionText logging", defaultValue: true
            input name: "debugEnable", type: "bool", title: "Enable debug logging",           defaultValue: false, submitOnChange: true
            if (debugEnable) {
                input name: "traceEnable", type: "bool", title: "Enable trace logging", defaultValue: false
            }
        }
    }
}

Map loginPage() {
    dynamicPage(name: "loginPage", title: "FGLair Login", nextPage: "mainPage") {
        if (state.authError) {
            section { paragraph "<span style='color:red;'><b>Error:</b> ${state.authError}</span>" }
            state.remove("authError")
        }
        if (isAuthenticated()) {
            section { paragraph "<b>✓ Connected.</b> Click <b>Next</b> to return to the main page." }
            return
        }
        section("Credentials") {
            input "fglairEmail",    "text",     title: "Email",    required: true, submitOnChange: true
            input "fglairPassword", "password", title: "Password", required: true, submitOnChange: true
            input "btnLogin",       "button",   title: "Login"
        }
    }
}

// --- Lifecycle ---

void installed()   { logDebug "installed"; state.version = APP_VERSION; initialize() }
void updated()     { logDebug "updated"; unsubscribe(); unschedule(); initialize() }
void uninstalled() { logDebug "uninstalled" }
void initialize()  {
    logDebug "initialize"
    if (state.version != APP_VERSION) {
        logWarn "new version: ${APP_VERSION} (was: ${state.version})"
        state.version = APP_VERSION
    }
    if (isAuthenticated()) {
        scheduleTokenRefresh()
    }
    subscribe(location, "systemStart", "systemStartHandler")
}

void systemStartHandler(evt) {
    logInfo "systemStart — re-asserting schedule"
    if (isAuthenticated()) {
        scheduleTokenRefresh()
    }
}

private void scheduleTokenRefresh() {
    if (!state.tokenExpiry) return
    long ms = ((long) state.tokenExpiry) - now() - TOKEN_REFRESH_BUFFER_MS
    int secs = Math.max(60, (int) (ms / 1000L))
    logDebug "scheduleTokenRefresh in ${secs}s"
    runIn(secs, "refreshToken")
}

// --- Buttons ---

void appButtonHandler(String btn) {
    logDebug "appButtonHandler(${btn})"
    switch (btn) {
        case "btnLogin":      signIn(); break
        case "btnDisconnect": disconnect(); break
        default: logWarn "unhandled button: ${btn}"
    }
}

// --- Authentication ---

private boolean isAuthenticated() { return state.accessToken != null }

private Map<String, String> regionConfig() {
    String r = settings.region ?: "us"
    return REGION[r] ?: REGION.us
}

void signIn() {
    String email = settings.fglairEmail
    String password = settings.fglairPassword
    if (!email || !password) {
        state.authError = "Email and password required."
        return
    }
    Map<String, String> rc = regionConfig()
    Map body = [
        user: [
            email: email,
            password: password,
            application: [app_id: rc.appId, app_secret: rc.appSecret]
        ]
    ]
    Map params = [
        uri: "${rc.userField}/users/sign_in.json",
        contentType: "application/json",
        requestContentType: "application/json",
        body: JsonOutput.toJson(body),
        timeout: HTTP_TIMEOUT
    ]
    logDebug "signIn POST ${params.uri}"
    asynchttpPost("signInCallback", params, [:])
}

void signInCallback(resp, data) {
    if (resp.hasError()) {
        logError "signIn HTTP error: ${resp.getErrorMessage()}"
        state.authError = "Sign-in failed: ${resp.getErrorMessage()}"
        return
    }
    int status = resp.getStatus()
    if (status != 200) {
        logError "signIn HTTP ${status}: ${resp.getData()}"
        state.authError = "Sign-in failed (HTTP ${status}). Check email/password and region."
        return
    }
    Map parsed
    try {
        parsed = new JsonSlurper().parseText(resp.getData())
    } catch (Exception e) {
        logError "signIn JSON parse error: ${e.message}"
        state.authError = "Sign-in response could not be parsed."
        return
    }
    storeTokens(parsed)
    logInfo "FGLair sign-in successful"
}

private void storeTokens(Map parsed) {
    state.accessToken  = parsed.access_token
    state.refreshToken = parsed.refresh_token
    long expiresInMs = ((parsed.expires_in ?: 3600L) as long) * 1000L
    state.tokenExpiry = now() + expiresInMs
    state.remove("authError")
    scheduleTokenRefresh()
}

void refreshToken() {
    logDebug "refreshToken"
    String rt = state.refreshToken
    if (!rt) {
        logWarn "no refresh token — attempting full re-sign-in"
        signIn()
        return
    }
    Map<String, String> rc = regionConfig()
    Map body = [user: [refresh_token: rt]]
    Map params = [
        uri: "${rc.userField}/users/refresh_token.json",
        contentType: "application/json",
        requestContentType: "application/json",
        body: JsonOutput.toJson(body),
        timeout: HTTP_TIMEOUT
    ]
    asynchttpPost("refreshTokenCallback", params, [:])
}

void refreshTokenCallback(resp, data) {
    if (resp.hasError()) {
        logWarn "refreshToken HTTP error: ${resp.getErrorMessage()} — falling back to re-sign-in"
        signIn()
        return
    }
    int status = resp.getStatus()
    if (status != 200) {
        logWarn "refreshToken HTTP ${status} — falling back to re-sign-in"
        signIn()
        return
    }
    Map parsed
    try {
        parsed = new JsonSlurper().parseText(resp.getData())
    } catch (Exception e) {
        logError "refreshToken JSON parse error: ${e.message}"
        signIn()
        return
    }
    storeTokens(parsed)
    logInfo "FGLair token refreshed"
}

private boolean tokenNearExpiry() {
    if (!state.tokenExpiry) return true
    return now() > ((long) state.tokenExpiry) - 60_000L
}

void disconnect() {
    logInfo "disconnecting"
    state.remove("accessToken")
    state.remove("refreshToken")
    state.remove("tokenExpiry")
    unschedule()
}

// --- Logging ---

private void logTrace(String msg) { if (settings.traceEnable) log.trace "${app.label} ${msg}" }
private void logDebug(String msg) { if (settings.debugEnable) log.debug "${app.label} ${msg}" }
private void logInfo(String msg)  { if (settings.txtEnable)   log.info  "${app.label} ${msg}" }
private void logWarn(String msg)  { log.warn  "${app.label} ${msg}" }
private void logError(String msg) { log.error "${app.label} ${msg}" }

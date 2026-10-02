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
import java.util.concurrent.ConcurrentHashMap

definition(
    name: "FGLair Manager",
    namespace: "iamtrep",
    author: "pj",
    description: "Fujitsu FGLair mini-split integration (Ayla Networks cloud)",
    menu: "Integrations",
    category: "Climate Control",
    singleInstance: true,
    // Async callbacks read-modify-write shared state (tokens, queues, counters).
    singleThreaded: true,
    importUrl: "https://raw.githubusercontent.com/iamtrep/hubitat/refs/heads/main/integrations/FGLair/FGLairManager.groovy",
    iconUrl: "",
    iconX2Url: ""
)

@Field static final String CODE_VERSION = "0.2.6"

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
// Transient cloud failures (timeouts, 5xx) are common with Ayla and self-clear on
// the next poll. Log at warn until this many in a row, then escalate to error so a
// real outage surfaces.
@Field static final int FAILURE_ERROR_THRESHOLD = 5
// A refresh or sign-in older than this is treated as lost, so a new one may start.
@Field static final long AUTH_INFLIGHT_MS = 30_000L
// Consecutive 401s, each followed by a successful refresh, before auth is halted.
@Field static final int AUTH_REJECT_LIMIT = 3
// display_temperature / outdoor_temperature are "sensed" properties: the Ayla cloud
// only returns a fresh reading after the unit is told to wake its sensors (refresh=1).
// Without that, a plain GET serves the last cached value — which the cloud refreshes
// on its own roughly once a day, so the temps look frozen. Each unit is woken at most
// once per settings.sensedMinutes, never per poll: per-poll trigger writes jammed the
// per-DSN write queue in v0.1.1/v0.1.2. The next GET picks up the woken values.
@Field static final List<String> SENSED_MINUTES = ["10", "15", "30", "60"]
// Floor between wakes of one unit when refresh() is called repeatedly.
@Field static final long MANUAL_WAKE_MIN_MS = 60_000L
// Delay between a manual wake and the GET that reads the woken values.
@Field static final int MANUAL_WAKE_READ_DELAY = 15
// Property GETs in flight at once during a poll; the platform caps an app at 8
// concurrent async calls, and wakes, writes and auth calls need headroom.
@Field static final int MAX_PROPERTY_FETCHES = 4
// A poll with no request sent or answered for this long has nothing left in
// flight (every request times out after HTTP_TIMEOUT, 15 s), so a new one may start.
@Field static final long POLL_IDLE_MS = 20_000L
// Sensor wakes sent per poll tick. Units still due wait for the next tick.
@Field static final int MAX_WAKES_PER_TICK = 2
// Longest response body echoed to debug logs.
@Field static final int LOG_BODY_MAX = 200
// A write queued behind a token refresh is dropped if the refresh takes longer;
// sending it later would replay a command the user has moved on from.
@Field static final long PENDING_WRITE_MAX_MS = 120_000L
// Poll pipeline (gen, queue, inFlight, lastActivity). In memory: its async
// callbacks don't survive a reboot or code update, so neither should it.
// One map is enough because the app is singleInstance.
@Field static final Map<String, Object> POLL = new ConcurrentHashMap<String, Object>()
// Platform cap on concurrent async calls per app. Every request is counted in
// REQ.inFlight; each path leaves slots free for the ones that matter more:
// token calls always go, writes keep 1 free, wakes and single-unit reads keep 2.
@Field static final int ASYNC_CAP = 8
@Field static final Map<String, Object> REQ = new ConcurrentHashMap<String, Object>()

preferences {
    page(name: "mainPage")
    page(name: "loginPage")
    page(name: "discoveredPropertiesPage")
    page(name: "confirmRemoveOrphansPage")
    page(name: "removeOrphansPage")
    page(name: "confirmDisconnectPage")
    page(name: "disconnectPage")
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
            }
            section("Polling") {
                input "pollRate", "enum", title: "Poll interval (seconds)",
                      options: ["30", "60", "120", "300"], defaultValue: "60", submitOnChange: true
                input "sensedMinutes", "enum", title: "Wake sensed temps every (minutes)",
                      options: SENSED_MINUTES, defaultValue: "15"
                input "btnRefreshNow", "button", title: "Refresh now"
            }
            section("Devices") {
                if (state.childError) paragraph "<span style='color:red;'><b>Error:</b> ${state.childError}</span>"
                renderChildList()
                List<Map> orphans = (atomicState.orphanedDevices ?: []) as List<Map>
                if (orphans.size() > 0) {
                    paragraph "<span style='color:orange'><b>Orphaned devices (${orphans.size()}):</b></span>"
                    orphans.each { Map o ->
                        paragraph "<a href='/device/edit/${o.id}' target='_blank'>${o.label}</a> (${o.dni})"
                    }
                    href "confirmRemoveOrphansPage", title: "Remove orphaned devices",
                         description: "Review and confirm before anything is deleted"
                }
            }
            section {
                Map<String, Object> known = (atomicState.knownProperties ?: [:]) as Map<String, Object>
                href "discoveredPropertiesPage",
                     title: "Discovered properties (debug)",
                     description: known.isEmpty()
                        ? "No properties observed yet — wait for one poll cycle."
                        : "${known.size()} property name(s) observed — tap to view"
            }
            section("Actions") {
                href "confirmDisconnectPage", title: "Disconnect", description: "Review and confirm"
            }
        } else {
            section {
                if (state.authError) paragraph "<span style='color:red;'><b>Error:</b> ${state.authError}</span>"
                paragraph "Connect your FGLair account to get started."
                href "loginPage", title: "Login to FGLair", description: "Enter your FGLair credentials"
            }
        }
        section("Settings") {
            label title: "Assign a name", required: false
            input name: "region",      type: "enum", title: "Region", options: ["us", "eu"], defaultValue: "us"
            input name: "txtEnable",   type: "bool", title: "Enable info logging", defaultValue: true
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

void installed()   { logDebug "installed"; state.version = CODE_VERSION; initialize() }
void updated() {
    logDebug "updated"
    unsubscribe()
    unschedule()
    // Tokens are issued per region; the other region's endpoints reject them.
    if (isAuthenticated() && state.sessionRegion && state.sessionRegion != currentRegion()) {
        logWarn "region changed to ${currentRegion()} — signed out"
        disconnect()
        state.authError = "Region changed. Log in again."
    }
    initialize()
    if (settings.debugEnable || settings.traceEnable) runIn(DEBUG_LOG_TIMEOUT, "logsOff")
}
void uninstalled() { logDebug "uninstalled" }
void initialize()  {
    logDebug "initialize"
    if (state.version != CODE_VERSION) {
        logVer "new version: ${CODE_VERSION} (was: ${state.version})"
        state.version = CODE_VERSION
    }
    migrateSensedSetting()
    // Sessions from before 0.2.5 didn't record their region.
    if (isAuthenticated() && !state.sessionRegion) state.sessionRegion = currentRegion()
    if (isAuthenticated()) {
        scheduleTokenRefresh()
        schedulePolling()
    }
    subscribe(location, "systemStart", "systemStartHandler")
}

// v0.2.0 replaced "wake every N polls" with a wake interval in minutes.
// sensedRefreshRate is an unused setting left by an earlier build.
private void migrateSensedSetting() {
    state.remove("lastSensedWakeMs")  // unused since the June build
    if (settings.sensedRefreshRate != null) app.removeSetting("sensedRefreshRate")
    if (settings.sensedPolls == null) return
    if (settings.sensedMinutes == null) app.updateSetting("sensedMinutes", [type: "enum", value: sensedMinutes()])
    app.removeSetting("sensedPolls")
    state.remove("pollCount")
}

// Converts a leftover sensedPolls setting to the nearest interval, never below 10 min.
private String sensedMinutes() {
    if (settings.sensedMinutes) return settings.sensedMinutes
    if (settings.sensedPolls == null) return "15"
    int rate = (settings.pollRate ?: "60") as int
    int mins = ((settings.sensedPolls as int) * rate).intdiv(60)
    return SENSED_MINUTES.min { Math.abs((it as int) - mins) }
}

void systemStartHandler(evt) {
    logInfo "systemStart — re-asserting schedules"
    if (isAuthenticated()) {
        scheduleTokenRefresh()
        schedulePolling()
        runIn(5, "fetchDevices")
    }
}

private void scheduleTokenRefresh() {
    if (!state.tokenExpiry) return
    long ms = ((long) state.tokenExpiry) - now() - TOKEN_REFRESH_BUFFER_MS
    int secs = Math.max(60, (int) (ms / 1000L))
    logSched "scheduleTokenRefresh in ${secs}s"
    runIn(secs, "refreshToken")
}

// --- Buttons ---

void appButtonHandler(String btn) {
    logDebug "appButtonHandler(${btn})"
    switch (btn) {
        case "btnLogin":            signIn(); break
        case "btnRefreshNow":       fetchDevices(); break
        case "btnResetDiscovered":  resetDiscoveredProperties(); break
        default: logWarn "unhandled button: ${btn}"
    }
}

private void renderChildList() {
    List<ChildDeviceWrapper> kids = unitChildren()
    if (kids.size() == 0) { paragraph "No child devices yet."; return }
    paragraph "<b>Units (${kids.size()}):</b>"
    kids.each { ChildDeviceWrapper c ->
        paragraph "<a href='/device/edit/${c.id}' target='_blank'>${c.label ?: c.name}</a>"
    }
}

// --- Confirmation pages ---
// Each destructive action is an href to a confirm page, whose Confirm link carries
// a single-use token to the page that acts. A refresh or a stale link finds the
// token gone and does nothing.

Map confirmRemoveOrphansPage() {
    List<Map> orphans = (atomicState.orphanedDevices ?: []) as List<Map>
    dynamicPage(name: "confirmRemoveOrphansPage", title: "Remove orphaned devices?") {
        section(sectionClass: "fglair-confirm") {
            hideDoneButton()
            if (!orphans) {
                paragraph "No orphaned devices."
            } else {
                paragraph "These devices will be deleted. Rules and dashboards that use them stop working. This can't be undone."
                orphans.each { Map o -> paragraph "${o.label} (${o.dni})" }
                href "removeOrphansPage", title: "Delete ${orphans.size()} device(s)",
                     params: [token: issueConfirmToken("removeOrphans")]
            }
            href "mainPage", title: "Cancel"
        }
    }
}

Map removeOrphansPage(Map params) {
    String result = consumeConfirmToken("removeOrphans", params)
    if (result == "act") removeOrphans()
    dynamicPage(name: "removeOrphansPage", title: "Remove orphaned devices", nextPage: "mainPage") {
        section {
            paragraph result == "stale" ? "Nothing removed: this link was already used." : "Orphaned devices removed."
        }
    }
}

Map confirmDisconnectPage() {
    dynamicPage(name: "confirmDisconnectPage", title: "Disconnect from FGLair?") {
        section(sectionClass: "fglair-confirm") {
            hideDoneButton()
            paragraph "Polling stops and every unit shows offline until you log in again. The units' devices and your stored login are kept."
            href "disconnectPage", title: "Disconnect", params: [token: issueConfirmToken("disconnect")]
            href "mainPage", title: "Cancel"
        }
    }
}

Map disconnectPage(Map params) {
    String result = consumeConfirmToken("disconnect", params)
    if (result == "act") disconnect()
    dynamicPage(name: "disconnectPage", title: "Disconnect", nextPage: "mainPage") {
        section {
            paragraph result == "stale" ? "Nothing done: this link was already used." : "Disconnected."
        }
    }
}

private void hideDoneButton() {
    paragraph rawHtml: true, "<style>#formApp:has(.fglair-confirm) #fieldsetAppButtons button[value='Done'] { display:none !important; }</style>"
}

private String issueConfirmToken(String action) {
    String token = UUID.randomUUID().toString()
    state.confirmToken = [action: action, token: token]
    return token
}

// "act" the first time a token is presented, "done" if the same page renders
// again for that click, "stale" otherwise.
private String consumeConfirmToken(String action, Map params) {
    String token = params?.token
    if (!token) return "stale"
    Map pending = state.confirmToken as Map
    if (pending?.action == action && pending.token == token) {
        state.remove("confirmToken")
        state.confirmUsed = token
        return "act"
    }
    return state.confirmUsed == token ? "done" : "stale"
}

void removeOrphans() {
    List<Map> orphans = (atomicState.orphanedDevices ?: []) as List<Map>
    orphans.each { Map o ->
        try {
            deleteChildDevice((String) o.dni)  // safety-lint:ok delete-in-loop — behind confirmRemoveOrphansPage
            logCfg "removed orphan ${o.dni}"
        } catch (Exception e) {
            logError "remove orphan ${o.dni} failed: ${e.message}"
        }
    }
    atomicState.orphanedDevices = []
}

Map discoveredPropertiesPage() {
    dynamicPage(name: "discoveredPropertiesPage", title: "Discovered properties (debug)", nextPage: "mainPage") {
        Map<String, Object> known = (atomicState.knownProperties ?: [:]) as Map<String, Object>
        if (known.isEmpty()) {
            section { paragraph "No properties observed yet — wait for one poll cycle, then return here." }
            return
        }
        section {
            paragraph "<b>${known.size()} property name(s) observed.</b> Tap <b>Next</b> to return to the manager."
        }
        section("Properties") {
            known.keySet().sort().each { String name ->
                paragraph "<code>${name}</code>: ${known[name]}"
            }
        }
        section {
            input "btnResetDiscovered", "button", title: "Reset discovered properties"
        }
    }
}

void resetDiscoveredProperties() {
    logCfg "resetting discovered properties"
    atomicState.knownProperties = [:]
}

private void schedulePolling() {
    int rate = (settings.pollRate ?: "60") as int
    Random rng = new Random()
    int offset = rng.nextInt(60)
    String cron = "${offset} */${Math.max(1, rate.intdiv(60))} * ? * *"
    if (rate < 60) {
        logSched "schedulePolling: ${rate}s loop"
        unschedule("pollTick")
        runIn(rate, "pollTick")
    } else {
        logSched "schedulePolling: cron '${cron}'"
        unschedule("pollTick")
        schedule(cron, "pollTick")
    }
}

void pollTick() {
    logTrace "pollTick"
    int rate = (settings.pollRate ?: "60") as int
    // Re-arm first so an error below can't end the loop.
    if (rate < 60) {
        int jitter = -7 + new Random().nextInt(15)
        runIn(Math.max(15, rate + jitter), "pollTick")
    }
    // A code push doesn't run updated(); converge here on the first poll after one.
    if (state.version != CODE_VERSION) {
        updated()
        return
    }
    if (pollInFlight()) {
        logSched "pollTick: previous poll still running — skipped"
    } else {
        // Units due a wake are woken here; this poll's GET still reads the current
        // values and a later poll picks up the woken ones.
        wakeDueUnits()
        fetchDevices()
    }
}

private void wakeDueUnits() {
    long interval = ((sensedMinutes() as int) * 60_000L) - 5_000L  // slack for poll jitter
    unitChildren().findAll { sinceLastWake(it.deviceNetworkId) >= interval }
                  .sort { sinceLastWake(it.deviceNetworkId) }.reverse()
                  .take(MAX_WAKES_PER_TICK)
                  .each { ChildDeviceWrapper c -> wakeUnit(c.deviceNetworkId) }
}

private boolean wakeUnit(String dni) {
    if (!hasRequestRoom(2)) { logNet "wake ${dni}: ${requestsInFlight()} requests in flight — skipped"; return false }
    Map<String, Long> wakes = (atomicState.lastWakeAt ?: [:]) as Map<String, Long>
    wakes[dni] = now()
    atomicState.lastWakeAt = wakes
    sendWrite(dni, "refresh", 1, false)
    return true
}

private long sinceLastWake(String dni) {
    Long last = ((atomicState.lastWakeAt ?: [:]) as Map)[dni] as Long
    return last == null ? Long.MAX_VALUE : now() - last
}

private String dsnFromDni(String dni) {
    return dni?.startsWith(DNI_PREFIX_UNIT) ? dni.substring(DNI_PREFIX_UNIT.length()) : null
}

private List<ChildDeviceWrapper> unitChildren() {
    return getChildDevices().findAll { it.deviceNetworkId?.startsWith(DNI_PREFIX_UNIT) }
}

// --- Authentication ---

private boolean isAuthenticated() { return state.accessToken != null }

private String currentRegion() { return (settings.region ?: "us") as String }

private Map<String, String> regionConfig() {
    return REGION[currentRegion()] ?: REGION.us
}

// Manual login (button). Automatic re-sign-in after a failed refresh goes
// through postSignIn(false) inside the refresh's in-flight window.
void signIn() {
    atomicState.authStartedAt = now()
    postSignIn(true)
}

private void postSignIn(boolean manual) {
    String email = settings.fglairEmail
    String password = settings.fglairPassword
    if (!email || !password) {
        atomicState.remove("authStartedAt")
        if (manual) state.authError = "Email and password required."
        else haltAuth("Stored FGLair credentials are missing.")
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
    logNet "signIn POST ${params.uri} (manual=${manual})"
    asyncRequest("POST", "signInCallback", params, [manual: manual])
}

void signInCallback(resp, data) {
    requestDone()
    atomicState.remove("authStartedAt")
    boolean manual = data?.manual as Boolean
    int status = resp.getStatus()
    if (isTransientStatus(status)) {
        // Network failure or cloud outage: keep the tokens; the next poll tries again.
        if (manual) {
            logError "signIn ${httpError(resp)}"
            state.authError = "Sign-in failed: ${httpError(resp)}"
        } else {
            noteTransientFailure "re-sign-in ${httpError(resp)}"
        }
        return
    }
    if (status != 200) {
        String msg = "Sign-in failed (HTTP ${status}). Check email/password and region."
        if (manual) {
            logError "signIn ${httpError(resp)}"
            state.authError = msg
        } else {
            haltAuth(msg)
        }
        return
    }
    Map parsed = parseTokens(resp, "signIn")
    if (parsed == null) {
        state.authError = "Sign-in response carried no session token."
        if (!manual) noteTransientFailure "re-sign-in: no session token in the response"
        return
    }
    storeTokens(parsed)
    logInfo "FGLair sign-in successful"
    if (manual) schedulePolling()
    afterAuth()
}

// Null when the body isn't JSON or has no access token.
private Map parseTokens(resp, String what) {
    try {
        def parsed = new JsonSlurper().parseText(resp.getData())
        if (parsed instanceof Map && ((Map) parsed).access_token) return (Map) parsed
        logError "${what}: response has no access token"
    } catch (Exception e) {
        logError "${what} JSON parse error: ${e.message}"
    }
    return null
}

private void storeTokens(Map parsed) {
    state.accessToken  = parsed.access_token
    state.refreshToken = parsed.refresh_token
    long expiresInMs = ((parsed.expires_in ?: 3600L) as long) * 1000L
    state.tokenExpiry = now() + expiresInMs
    state.sessionRegion = currentRegion()
    state.remove("authError")
    scheduleTokenRefresh()
}

// Single-flight: callers that need a fresh token call this and return; afterAuth()
// re-runs the poll and flushes queued writes. Nothing retries on a timer, so a
// failed refresh waits for the next poll tick.
void refreshToken() {
    if (authInFlight()) { logNet "refreshToken: already in flight"; return }
    atomicState.authStartedAt = now()
    String rt = state.refreshToken
    if (!rt) {
        logWarn "no refresh token — attempting full re-sign-in"
        postSignIn(false)
        return
    }
    logNet "refreshToken"
    Map<String, String> rc = regionConfig()
    Map body = [user: [refresh_token: rt]]
    Map params = [
        uri: "${rc.userField}/users/refresh_token.json",
        contentType: "application/json",
        requestContentType: "application/json",
        body: JsonOutput.toJson(body),
        timeout: HTTP_TIMEOUT
    ]
    asyncRequest("POST", "refreshTokenCallback", params, [:])
}

void refreshTokenCallback(resp, data) {
    requestDone()
    int status = resp.getStatus()
    if (isTransientStatus(status)) {
        atomicState.remove("authStartedAt")
        noteTransientFailure "refreshToken ${httpError(resp)}"
        return
    }
    if (status != 200) {
        logWarn "refreshToken ${httpError(resp)} — falling back to re-sign-in"
        atomicState.authStartedAt = now()
        postSignIn(false)
        return
    }
    Map parsed = parseTokens(resp, "refreshToken")
    if (parsed == null) {
        atomicState.authStartedAt = now()
        postSignIn(false)
        return
    }
    atomicState.remove("authStartedAt")
    storeTokens(parsed)
    logInfo "FGLair token refreshed"
    afterAuth()
}

private boolean authInFlight() {
    Long started = atomicState.authStartedAt as Long
    return started != null && now() - started < AUTH_INFLIGHT_MS
}

private void afterAuth() {
    flushPendingWrites()
    runIn(2, "fetchDevices")
}

private boolean tokenNearExpiry() {
    if (!state.tokenExpiry) return true
    return now() > ((long) state.tokenExpiry) - 60_000L
}

// A 401 on a token we just obtained means refreshing won't help. Count them so
// a rejected token can't drive a fetch -> 401 -> refresh -> fetch loop.
private void noteAuthReject(String what) {
    // Requests sent before the refresh started all fail with the same token.
    if (authInFlight()) { logNet "${what} HTTP 401 — refresh already in flight"; return }
    int n = ((atomicState.authRejects ?: 0) as int) + 1
    atomicState.authRejects = n
    if (n >= AUTH_REJECT_LIMIT) {
        haltAuth("FGLair rejected the session ${n} times in a row (last: ${what}).")
        return
    }
    logWarn "${what} HTTP 401 — refreshing token"
    refreshToken()
}

private void clearAuthRejects() {
    if (atomicState.authRejects) atomicState.authRejects = 0
}

// Stops polling and drops the session so the manager page shows the login link.
private void haltAuth(String reason) {
    logError "${reason} Polling stopped; log in again from the manager page."
    state.authError = reason
    clearSession()
    markUnitsOffline()
}

private void clearSession() {
    state.remove("accessToken")
    state.remove("refreshToken")
    state.remove("tokenExpiry")
    atomicState.remove("authStartedAt")
    atomicState.remove("authRejects")
    discardPendingWrites()
    state.remove("unitFailures")
    state.remove("sessionRegion")
    POLL.clear()
    // Only the session's own handlers; a pending logsOff must still run.
    ["pollTick", "refreshToken", "fetchDevices", "fetchPropertiesDeferred"].each { unschedule(it) }
}

// --- Device discovery ---

private Map authHeader() { return ["Authorization": "auth_token ${state.accessToken}"] }

void fetchDevices() {
    logNet "fetchDevices"
    if (!isAuthenticated()) { logNet "fetchDevices: not signed in"; return }
    // Wait for the running poll instead of stacking a second one on top of it.
    if (pollInFlight() || !hasRequestRoom(1)) {
        logSched "fetchDevices: poll or requests still in flight — retrying in 5 s"
        runIn(5, "fetchDevices")
        return
    }
    if (tokenNearExpiry()) {
        logNet "token near expiry — fetchDevices runs after the refresh"
        refreshToken()
        return
    }
    // Responses from an earlier poll that went idle are ignored. A timestamp
    // stays unique across code updates, which reset POLL.
    long gen = now()
    POLL.clear()
    POLL.gen = gen
    POLL.queue = []
    POLL.inFlight = 0
    POLL.lastActivity = gen
    Map<String, String> rc = regionConfig()
    Map params = [
        uri: "${rc.ads}/apiv1/devices.json",
        headers: authHeader(),
        contentType: "application/json",
        timeout: HTTP_TIMEOUT
    ]
    asyncRequest("GET", "fetchDevicesCallback", params, [gen: gen])
}

private boolean pollInFlight() {
    Long last = POLL.lastActivity as Long
    return last != null && now() - last < POLL_IDLE_MS
}

private boolean isCurrentPoll(Map data) {
    boolean current = data?.gen != null && POLL.gen != null && (data.gen as long) == (POLL.gen as long)
    if (current) POLL.lastActivity = now()
    return current
}

private boolean pollDrained() {
    return !POLL.queue && !POLL.inFlight
}

private void endPoll() {
    POLL.remove("lastActivity")
    POLL.remove("queue")
    POLL.remove("inFlight")
}

void fetchDevicesCallback(resp, data) {
    requestDone()
    if (!isCurrentPoll(data)) { logNet "fetchDevices: response from a superseded poll ignored"; return }
    try {
        handleDevicesResponse(resp)
    } finally {
        if (pollDrained()) endPoll()
    }
}

private void handleDevicesResponse(resp) {
    int status = resp.getStatus()
    if (status == 401) {
        noteAuthReject("fetchDevices")
        return
    }
    if (status != 200) {
        noteTransientFailure "fetchDevices ${httpError(resp)}"
        if (!isTransientStatus(status)) logNet "fetchDevices response: ${bodyExcerpt(resp)}"
        return
    }
    List parsed
    try {
        parsed = (List) new JsonSlurper().parseText(resp.getData())
    } catch (Exception e) {
        noteTransientFailure "fetchDevices JSON parse error: ${e.message}"
        return
    }
    clearTransientFailureStreak()
    clearAuthRejects()
    handleDeviceList(parsed)
}

private void handleDeviceList(List rawDevices) {
    // Ayla wraps each device under {"device": {...}}.
    List<Map> devices = rawDevices.collect { it instanceof Map ? ((Map) it).device : null }
                                  .findAll { it instanceof Map } as List<Map>
    if (devices.size() != state.lastDeviceCount) {
        logInfo "fetchDevices returned ${devices.size()} device(s)"
        state.lastDeviceCount = devices.size()
    } else {
        logNet "fetchDevices returned ${devices.size()} device(s)"
    }
    logTrace "raw device list: ${JsonOutput.toJson(devices)}"

    Set<String> liveDnis = [] as Set
    String childError = null
    devices.each { Map d ->
        String dsn = d.dsn?.toString()
        if (!dsn) return
        // For MVP, accept all returned devices as Fujitsu indoor units. If multi-vendor
        // accounts surface later, filter on d.oem_model or d.product_name here.
        String dni = "${DNI_PREFIX_UNIT}${dsn}"
        liveDnis << dni
        ChildDeviceWrapper child = getChildDevice(dni)
        if (!child) {
            String label = d.product_name?.toString() ?: "Fujitsu Mini-Split ${dsn}"
            logCfg "creating child device ${dni} (${label})"
            try {
                child = addChildDevice("iamtrep", DRIVER_UNIT, dni,
                    [name: DRIVER_UNIT, label: label, isComponent: false])
            } catch (Exception e) {
                childError = "Could not create a device for ${label}: ${e.message}. Is the ${DRIVER_UNIT} driver installed?"
                logError childError
                return
            }
        }
        // Ayla reports "Online" / "Offline" for the unit's link to the cloud.
        String conn = d.connection_status?.toString()
        // A unit whose own property reads keep failing stays offline until one succeeds.
        boolean reachable = unitFailures(dsn) < FAILURE_ERROR_THRESHOLD
        if (conn) child.updateHealth(conn.equalsIgnoreCase("Online") && reachable ? "online" : "offline")
    }
    if (childError) state.childError = childError
    else state.remove("childError")
    // An empty list is more likely a cloud glitch than every unit removed from the
    // account; flagging all units as orphans would let one click delete them all.
    if (devices) computeOrphans(liveDnis)
    else if (unitChildren()) logWarn "FGLair returned no devices — orphan check skipped"

    // After ensuring children exist, fetch each DSN's properties and dispatch,
    // a few at a time so large accounts stay under the async-call cap.
    POLL.queue = devices.collect { it.dsn?.toString() }.findAll { it }
    MAX_PROPERTY_FETCHES.times { fetchNextQueued() }
}

private void fetchNextQueued() {
    List<String> queue = (POLL.queue ?: []) as List<String>
    if (!queue) return
    if (((POLL.inFlight ?: 0) as int) >= MAX_PROPERTY_FETCHES || !hasRequestRoom(1)) return
    String dsn = queue.remove(0)
    POLL.queue = queue
    if (sendPropertiesGet(dsn, POLL.gen as Long)) {
        POLL.inFlight = ((POLL.inFlight ?: 0) as int) + 1
        POLL.lastActivity = now()
    } else {
        // Token refresh started; afterAuth() re-polls every unit.
        POLL.remove("queue")
    }
}

// Plain GET — mirrors ayla-iot-unofficial's device.async_update(). No trigger
// write precedes it: v0.1.1 (refresh=1) and v0.1.2 (get_prop=1) wrote one before
// every GET and jammed the unit's per-DSN write queue. Sensed temps are woken
// separately and sparingly by wakeDueUnits() and refreshUnit().
void fetchProperties(String dsn) {
    if (!hasRequestRoom(2)) { logNet "fetchProperties(${dsn}): ${requestsInFlight()} requests in flight — next poll reads it"; return }
    sendPropertiesGet(dsn, null)
}

// gen ties a fetch to the poll that queued it; null for a single-unit refresh.
// Returns false when the GET was deferred behind a token refresh.
private boolean sendPropertiesGet(String dsn, Long gen) {
    // A deferred fetch is covered by afterAuth(), which re-polls every unit.
    if (tokenNearExpiry()) { refreshToken(); return false }
    Map<String, String> rc = regionConfig()
    Map params = [
        uri: "${rc.ads}/apiv1/dsns/${dsn}/properties.json",
        headers: authHeader(),
        contentType: "application/json",
        timeout: HTTP_TIMEOUT
    ]
    asyncRequest("GET", "fetchPropertiesCallback", params, [dsn: dsn, gen: gen])
    return true
}

void fetchPropertiesCallback(resp, data) {
    releaseRequest()  // the next read starts in finally, after a 401 has cleared the queue
    boolean queued = data?.gen != null
    if (queued) {
        if (!isCurrentPoll(data)) { logNet "fetchProperties(${data?.dsn}): response from a superseded poll ignored"; fetchNextQueued(); return }
        POLL.inFlight = Math.max(0, ((POLL.inFlight ?: 0) as int) - 1)
    }
    try {
        handlePropertiesResponse(resp, (String) data?.dsn)
    } finally {
        // A single-unit read frees a slot too, which a waiting poll read may need.
        fetchNextQueued()
        if (queued && pollDrained()) endPoll()
    }
}

private void handlePropertiesResponse(resp, String dsn) {
    int status = resp.getStatus()
    if (status == 401) {
        POLL.remove("queue")  // the rest would fail on the same token
        noteAuthReject("fetchProperties(${dsn})")
        return
    }
    if (status != 200) { noteUnitFailure(dsn, "fetchProperties(${dsn}) ${httpError(resp)}"); return }
    List parsed
    try { parsed = (List) new JsonSlurper().parseText(resp.getData()) }
    catch (Exception e) { noteUnitFailure(dsn, "fetchProperties(${dsn}) parse: ${e.message}"); return }
    clearTransientFailureStreak()
    clearUnitFailures(dsn)
    clearAuthRejects()

    Map<String, Object> props = [:]
    parsed.each { item ->
        def p = item instanceof Map ? ((Map) item).property : null
        if (p instanceof Map && p.name) props[p.name.toString().toLowerCase()] = p.value
    }
    if (parsed && !props) {
        noteUnitFailure(dsn, "fetchProperties(${dsn}): unrecognized response shape — keeping last state")
        return
    }
    Map<String, Object> known = (atomicState.knownProperties ?: [:]) as Map<String, Object>
    Map<String, Object> merged = known + props
    if (merged != known) atomicState.knownProperties = merged
    Map stateMap = [
        opMode          : props["operation_mode"],
        fanSpeed        : props["fan_speed"],
        adjustTemp      : props["adjust_temperature"],
        displayTemp     : props["display_temperature"],
        outdoorTemp     : props["outdoor_temperature"],
        errorCode       : props["error_code"],
        opStatus        : props["op_status"],
        modelName       : props["model_name"],
        firmwareVersion : props["mcu_fw_version"],
        deviceName      : props["device_name"],
        commVersion     : props["comm_version"]
    ]
    logTrace "fetchProperties(${dsn}) -> ${stateMap}"
    ChildDeviceWrapper child = getChildDevice("${DNI_PREFIX_UNIT}${dsn}")
    try {
        child?.updateState(stateMap)
    } catch (Exception e) {
        logError "fetchProperties(${dsn}): unexpected property values: ${e.message}"
    }
}

// Child refresh(): mirrors the lib's refresh_sensed_temp() — wake the sensors,
// then read once they have reported. Wakes are rate-limited per unit; within
// the limit this is a plain read.
void refreshUnit(String dni) {
    String dsn = dsnFromDni(dni)
    if (!dsn) return
    if (sinceLastWake(dni) >= MANUAL_WAKE_MIN_MS && wakeUnit(dni)) {
        runIn(MANUAL_WAKE_READ_DELAY, "fetchPropertiesDeferred", [data: [dsn: dsn], overwrite: false])
        return
    }
    logDebug "refreshUnit(${dsn}): reading only"
    fetchProperties(dsn)
}

void fetchPropertiesDeferred(Map data) { fetchProperties((String) data.dsn) }

// --- Write commands ---

void sendCommand(String dni, String propertyName, def value) {
    sendWrite(dni, propertyName, value, true)
}

// report=false for the manager's own writes (sensor wakes): the child didn't ask
// for them, so their outcome stays out of its commandStatus.
private void sendWrite(String dni, String propertyName, def value, boolean report) {
    String dsn = dsnFromDni(dni)
    if (!dsn) { logError "sendCommand: invalid dni ${dni}"; return }
    Map w = [dsn: dsn, name: propertyName, value: value, retried: false, report: report]
    if (!isAuthenticated()) {
        logWarn "sendCommand(${propertyName}): not signed in — dropped"
        reportWrite(w, false)
        return
    }
    // While a refresh runs the current token may be the one just rejected.
    if (tokenNearExpiry() || authInFlight()) {
        queuePendingWrite(w)
        refreshToken()
        return
    }
    writeDatapoint(w)
}

// Writes waiting on a token refresh, flushed in order by afterAuth(). A newer
// write to the same property replaces the queued one.
private void queuePendingWrite(Map w) {
    List<Map> pending = ((atomicState.pendingWrites ?: []) as List<Map>).findAll {
        !(it.dsn == w.dsn && it.name == w.name)
    }
    pending << (w + [at: now()])
    atomicState.pendingWrites = pending
    logDebug "queued ${w.name}=${w.value} until token refresh (${pending.size()} pending)"
}

private void flushPendingWrites() {
    List<Map> pending = (atomicState.pendingWrites ?: []) as List<Map>
    if (!pending) return
    atomicState.pendingWrites = []
    logDebug "flushing ${pending.size()} queued write(s)"
    pending.each { Map w ->
        if (w.at == null || now() - (w.at as long) > PENDING_WRITE_MAX_MS) {
            logWarn "writeDatapoint(${w.name}): queued too long behind the token refresh — dropped"
            reportWrite(w, false)
        } else {
            writeDatapoint(w)
        }
    }
}

// Writes still queued when the session ends are reported failed, never sent.
private void discardPendingWrites() {
    List<Map> pending = (atomicState.pendingWrites ?: []) as List<Map>
    atomicState.remove("pendingWrites")
    if (!pending) return
    logWarn "discarding ${pending.size()} queued write(s)"
    pending.each { Map w -> reportWrite(w, false) }
}

private void writeDatapoint(Map w) {
    String propertyName = w.name
    if (!hasRequestRoom(1)) {
        logWarn "writeDatapoint(${propertyName}): ${requestsInFlight()} requests in flight — dropped"
        reportWrite(w, false)
        return
    }
    Map<String, String> rc = regionConfig()
    Map body = [datapoint: [value: w.value]]
    Map params = [
        uri: "${rc.ads}/apiv1/dsns/${w.dsn}/properties/${propertyName}/datapoints.json",
        headers: authHeader(),
        contentType: "application/json",
        requestContentType: "application/json",
        body: JsonOutput.toJson(body),
        timeout: HTTP_TIMEOUT
    ]
    logDebug "writeDatapoint dsn=${w.dsn} ${propertyName}=${w.value}"
    asyncRequest("POST", "writeDatapointCallback", params,
                 [dsn: w.dsn, name: propertyName, value: w.value, retried: w.retried, report: w.report])
}

// Failed writes are not retried: a write the unit can't honor jams the per-DSN
// queue. The child reports the failure and the next poll restores true state.
void writeDatapointCallback(resp, data) {
    requestDone()
    Map w = (data ?: [:]) as Map
    String name = w.name
    int status = resp.getStatus()
    if (status == 401) {
        if (w.retried) {
            logError "writeDatapoint(${name}) HTTP 401 after token refresh — dropped"
            reportWrite(w, false)
            return
        }
        queuePendingWrite(w + [retried: true])
        noteAuthReject("writeDatapoint(${name})")
        return
    }
    if (status != 200 && status != 201) {
        if (isTransientStatus(status)) {
            logWarn "writeDatapoint(${name}) ${httpError(resp)}"
        } else {
            logError "writeDatapoint(${name}) ${httpError(resp)}"
            logNet "writeDatapoint(${name}) response: ${bodyExcerpt(resp)}"
        }
        reportWrite(w, false)
        return
    }
    clearAuthRejects()
    logNet "writeDatapoint(${name}) ok"
    reportWrite(w, true)
}

// Every async call goes through here so all paths share one count against ASYNC_CAP.
// Each callback calls requestDone() first; callbacks always fire, at the latest on timeout.
private void asyncRequest(String method, String callback, Map params, Map data) {
    REQ.inFlight = requestsInFlight() + 1
    REQ.lastActivity = now()
    try {
        if (method == "GET") asynchttpGet(callback, params, data)
        else asynchttpPost(callback, params, data)
    } catch (Exception e) {
        releaseRequest()
        throw e
    }
}

// Every request times out after HTTP_TIMEOUT, so after POLL_IDLE_MS with no
// request sent or answered nothing can still be in flight; the count resets
// in case a callback was lost.
private int requestsInFlight() {
    Long last = REQ.lastActivity as Long
    if (last == null || now() - last >= POLL_IDLE_MS) REQ.inFlight = 0
    return (REQ.inFlight ?: 0) as int
}

private boolean hasRequestRoom(int reserve) { return requestsInFlight() < ASYNC_CAP - reserve }

private void releaseRequest() {
    REQ.inFlight = Math.max(0, requestsInFlight() - 1)
    REQ.lastActivity = now()
}

// A freed slot may unblock poll reads that were waiting on other requests.
private void requestDone() {
    releaseRequest()
    fetchNextQueued()
}

// Writes from before report existed carry no flag and are reported.
private void reportWrite(Map w, boolean ok) {
    if (w.report == false) return
    getChildDevice("${DNI_PREFIX_UNIT}${w.dsn}")?.commandResult((String) w.name, ok)
}

private void computeOrphans(Set<String> liveDnis) {
    List<Map> orphans = []
    getChildDevices().each { ChildDeviceWrapper c ->
        String dni = c.deviceNetworkId
        if (dni?.startsWith(DNI_PREFIX_UNIT) && !(dni in liveDnis)) {
            orphans << [id: c.id, label: c.label ?: c.name, dni: dni]
        }
    }
    atomicState.orphanedDevices = orphans
    if (orphans.size() > 0) logWarn "${orphans.size()} orphaned device(s) detected"
}

void disconnect() {
    logInfo "disconnecting"
    clearSession()
    markUnitsOffline()
}

// hasError() is true for every non-2xx status, so callbacks branch on getStatus().
// Timeouts and connection failures carry no real HTTP status (408, or below 100).
private boolean isTransientStatus(int status) {
    return status < 100 || status == 408 || status == 429 || status >= 500
}

// getErrorMessage() and getErrorData() throw on a success, getData() on an error.
private String httpError(resp) {
    return resp.hasError() ? "HTTP ${resp.getStatus()}: ${resp.getErrorMessage()}" : "HTTP ${resp.getStatus()}"
}

private String bodyExcerpt(resp) {
    String body
    try { body = (resp.hasError() ? resp.getErrorData() : resp.getData())?.toString() ?: "" }
    catch (Exception e) { return "" }
    return body.length() > LOG_BODY_MAX ? body.take(LOG_BODY_MAX) + "…" : body
}

void logsOff() {
    logWarn "debug and trace logging disabled"
    app.updateSetting("debugEnable", [value: "false", type: "bool"])
    app.updateSetting("traceEnable", [value: "false", type: "bool"])
}

// Cloud-level failures (auth, device list). Per-unit property reads use noteUnitFailure().
private void noteTransientFailure(String msg) {
    int n = ((state.consecutiveFetchFailures ?: 0) as int) + 1
    state.consecutiveFetchFailures = n
    if (n == FAILURE_ERROR_THRESHOLD) markUnitsOffline()
    if (n >= FAILURE_ERROR_THRESHOLD) {
        logError "${msg} (failure streak: ${n})"
    } else {
        logWarn msg
    }
}

// The cloud can't be reached, so unit state is unknown. The next successful
// device list restores health from connection_status.
private void markUnitsOffline() {
    unitChildren().each { it.updateHealth("offline") }
}

private void clearTransientFailureStreak() {
    if (state.consecutiveFetchFailures) state.consecutiveFetchFailures = 0
}

// A unit whose property reads keep failing is marked offline on its own,
// without counting against the cloud streak.
private void noteUnitFailure(String dsn, String msg) {
    Map<String, Integer> fails = (state.unitFailures ?: [:]) as Map<String, Integer>
    int n = ((fails[dsn] ?: 0) as int) + 1
    fails[dsn] = n
    state.unitFailures = fails
    if (n == FAILURE_ERROR_THRESHOLD) getChildDevice("${DNI_PREFIX_UNIT}${dsn}")?.updateHealth("offline")
    if (n >= FAILURE_ERROR_THRESHOLD) {
        logError "${msg} (failure streak: ${n})"
    } else {
        logWarn msg
    }
}

private int unitFailures(String dsn) {
    return (((state.unitFailures ?: [:]) as Map)[dsn] ?: 0) as int
}

private void clearUnitFailures(String dsn) {
    Map<String, Integer> fails = (state.unitFailures ?: [:]) as Map<String, Integer>
    if (!fails.containsKey(dsn)) return
    fails.remove(dsn)
    state.unitFailures = fails
    // Health comes back with the next device list, which carries the unit's own link state.
}

// ── Logging (app) ─────────────────────────────────────────────────────
//   ⬇️ Evt  ⬆️ Cmd  🔧 Cfg  🌐 Net  ⏰ Sched  🏷️ Ver  ·  ⚠️ Warn  🛑 Error  🔬 Trace
private String logp(String e) { "${e} ${app.getLabel()}: " }

void logEvt  (String m) { if (settings.debugEnable) log.debug logp('⬇️') + m }
void logCmd  (String m) { if (txtEnable != false) log.info  logp('⬆️') + m }
void logCfg  (String m) { if (txtEnable != false) log.info  logp('🔧') + m }
void logNet  (String m) { if (settings.debugEnable) log.debug logp('🌐') + m }
void logSched(String m) { if (settings.debugEnable) log.debug logp('⏰') + m }
void logVer  (String m) { log.warn  logp('🏷️') + m }

void logWarn (String m) { log.warn  logp('⚠️') + m }
void logError(String m) { log.error logp('🛑') + m }
void logTrace(String m) { if (settings.traceEnable) log.trace logp('🔬') + m }
void logInfo (String m) { if (txtEnable != false) log.info  "${app.getLabel()}: ${m}" }
void logDebug(String m) { if (settings.debugEnable) log.debug "${app.getLabel()}: ${m}" }

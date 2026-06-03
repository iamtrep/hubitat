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

@Field static final String APP_VERSION = "0.1.3"

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
    page(name: "discoveredPropertiesPage")
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
                input "btnRefreshNow", "button", title: "Refresh now"
            }
            section("Devices") {
                renderChildList()
                List<Map> orphans = (atomicState.orphanedDevices ?: []) as List<Map>
                if (orphans.size() > 0) {
                    paragraph "<span style='color:orange'><b>Orphaned devices (${orphans.size()}):</b></span>"
                    orphans.each { Map o ->
                        paragraph "<a href='/device/edit/${o.id}' target='_blank'>${o.label}</a> (${o.dni})"
                    }
                    input "btnRemoveOrphans", "button", title: "Remove orphaned devices"
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
            section("Actions") { input "btnDisconnect", "button", title: "Disconnect" }
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
        schedulePolling()
    }
    subscribe(location, "systemStart", "systemStartHandler")
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
    logDebug "scheduleTokenRefresh in ${secs}s"
    runIn(secs, "refreshToken")
}

// --- Buttons ---

void appButtonHandler(String btn) {
    logDebug "appButtonHandler(${btn})"
    switch (btn) {
        case "btnLogin":            signIn(); break
        case "btnDisconnect":       disconnect(); break
        case "btnRefreshNow":       fetchDevices(); break
        case "btnRemoveOrphans":    removeOrphans(); break
        case "btnResetDiscovered":  resetDiscoveredProperties(); break
        default: logWarn "unhandled button: ${btn}"
    }
}

private void renderChildList() {
    List<ChildDeviceWrapper> kids = getChildDevices().findAll {
        it.deviceNetworkId?.startsWith(DNI_PREFIX_UNIT)
    }
    if (kids.size() == 0) { paragraph "No child devices yet."; return }
    paragraph "<b>Units (${kids.size()}):</b>"
    kids.each { ChildDeviceWrapper c ->
        paragraph "<a href='/device/edit/${c.id}' target='_blank'>${c.label ?: c.name}</a>"
    }
}

void removeOrphans() {
    List<Map> orphans = (atomicState.orphanedDevices ?: []) as List<Map>
    orphans.each { Map o ->
        try {
            deleteChildDevice((String) o.dni)
            logInfo "removed orphan ${o.dni}"
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
    logInfo "resetting discovered properties"
    atomicState.knownProperties = [:]
}

private void schedulePolling() {
    int rate = (settings.pollRate ?: "60") as int
    Random rng = new Random()
    int offset = rng.nextInt(60)
    String cron = "${offset} */${Math.max(1, rate.intdiv(60))} * ? * *"
    if (rate < 60) {
        logDebug "schedulePolling: ${rate}s loop"
        unschedule("pollTick")
        runIn(rate, "pollTick")
    } else {
        logDebug "schedulePolling: cron '${cron}'"
        unschedule("pollTick")
        schedule(cron, "pollTick")
    }
}

void pollTick() {
    logTrace "pollTick"
    fetchDevices()
    int rate = (settings.pollRate ?: "60") as int
    if (rate < 60) {
        int jitter = -7 + new Random().nextInt(15)
        runIn(Math.max(15, rate + jitter), "pollTick")
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
    runIn(2, "fetchDevices")
    schedulePolling()
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

// --- Device discovery ---

private Map authHeader() { return ["Authorization": "auth_token ${state.accessToken}"] }

void fetchDevices() {
    logDebug "fetchDevices"
    if (tokenNearExpiry()) {
        logDebug "token near expiry — refreshing inline before fetchDevices"
        refreshToken()
        runIn(3, "fetchDevices")
        return
    }
    Map<String, String> rc = regionConfig()
    Map params = [
        uri: "${rc.ads}/apiv1/devices.json",
        headers: authHeader(),
        contentType: "application/json",
        timeout: HTTP_TIMEOUT
    ]
    asynchttpGet("fetchDevicesCallback", params, [:])
}

void fetchDevicesCallback(resp, data) {
    if (resp.hasError()) {
        logError "fetchDevices HTTP error: ${resp.getErrorMessage()}"
        return
    }
    int status = resp.getStatus()
    if (status == 401) {
        logWarn "fetchDevices HTTP 401 — refreshing token and retrying"
        refreshToken()
        runIn(3, "fetchDevices")
        return
    }
    if (status != 200) {
        logError "fetchDevices HTTP ${status}: ${resp.getData()}"
        return
    }
    List parsed
    try {
        parsed = (List) new JsonSlurper().parseText(resp.getData())
    } catch (Exception e) {
        logError "fetchDevices JSON parse error: ${e.message}"
        return
    }
    handleDeviceList(parsed)
}

private void handleDeviceList(List rawDevices) {
    // Ayla wraps each device under {"device": {...}}.
    List<Map> devices = rawDevices.collect { (Map) ((Map) it).device }.findAll { it != null }
    logInfo "fetchDevices returned ${devices.size()} device(s)"
    logTrace "raw device list: ${JsonOutput.toJson(devices)}"

    Set<String> liveDnis = [] as Set
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
            logInfo "creating child device ${dni} (${label})"
            addChildDevice("iamtrep", DRIVER_UNIT, dni,
                [name: DRIVER_UNIT, label: label, isComponent: false])
        }
    }
    computeOrphans(liveDnis)

    // After ensuring children exist, fetch each DSN's properties and dispatch.
    devices.each { Map d ->
        String dsn = d.dsn?.toString()
        if (dsn) fetchProperties(dsn)
    }
}

// Plain GET — mirrors ayla-iot-unofficial's device.async_update(), which does
// no trigger write before reading. Earlier versions wrote refresh=1 (v0.1.1)
// then get_prop=1 (v0.1.2) before each GET, on the theory that the cloud
// would otherwise return stale values; both turned out to feed the Fujitsu
// WLAN module's per-DSN write queue once per minute and could jam setpoint
// writes from any source (driver or the FGLair app on cloud). The lib
// applies refresh=1 only narrowly, inside refresh_sensed_temp(), to wake a
// single sensor reading — not before a full poll. We follow that.
//
// If display_temperature staleness is later observed in normal operation,
// add a separate refreshSensedTemp() that mirrors the lib's narrow pattern.
void fetchProperties(String dsn) {
    if (tokenNearExpiry()) { refreshToken(); runIn(3, "fetchProperties", [data: [dsn: dsn]]); return }
    Map<String, String> rc = regionConfig()
    Map params = [
        uri: "${rc.ads}/apiv1/dsns/${dsn}/properties.json",
        headers: authHeader(),
        contentType: "application/json",
        timeout: HTTP_TIMEOUT
    ]
    asynchttpGet("fetchPropertiesCallback", params, [dsn: dsn])
}

void fetchPropertiesCallback(resp, data) {
    String dsn = data?.dsn
    if (resp.hasError()) { logError "fetchProperties(${dsn}) error: ${resp.getErrorMessage()}"; return }
    int status = resp.getStatus()
    if (status == 401) { logWarn "fetchProperties(${dsn}) 401 — refresh"; refreshToken(); runIn(3, "fetchProperties", [data: [dsn: dsn]]); return }
    if (status != 200) { logError "fetchProperties(${dsn}) HTTP ${status}"; return }
    List parsed
    try { parsed = (List) new JsonSlurper().parseText(resp.getData()) }
    catch (Exception e) { logError "fetchProperties parse: ${e.message}"; return }

    Map<String, Object> props = [:]
    parsed.each { item ->
        Map p = (Map) ((Map) item).property
        if (p?.name) props[((String) p.name).toLowerCase()] = p.value
    }
    Map<String, Object> known = (atomicState.knownProperties ?: [:]) as Map<String, Object>
    atomicState.knownProperties = (known + props)
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
        commVersion     : props["comm_version"],
        online          : true
    ]
    logTrace "fetchProperties(${dsn}) -> ${stateMap}"
    ChildDeviceWrapper child = getChildDevice("${DNI_PREFIX_UNIT}${dsn}")
    child?.updateState(stateMap)
}

void refreshUnit(String dni) {
    String dsn = dni?.replaceFirst("^${java.util.regex.Pattern.quote(DNI_PREFIX_UNIT)}", "")
    if (dsn) fetchProperties(dsn)
}

// --- Write commands ---

void sendCommand(String dni, String propertyName, Object value) {
    String dsn = dni?.replaceFirst("^${java.util.regex.Pattern.quote(DNI_PREFIX_UNIT)}", "")
    if (!dsn) { logError "sendCommand: invalid dni ${dni}"; return }
    if (tokenNearExpiry()) {
        refreshToken()
        runIn(3, "sendCommandDeferred", [data: [dsn: dsn, name: propertyName, value: value]])
        return
    }
    writeDatapoint(dsn, propertyName, value)
}

void sendCommandDeferred(Map data) {
    writeDatapoint((String) data.dsn, (String) data.name, data.value)
}

private void writeDatapoint(String dsn, String propertyName, Object value) {
    Map<String, String> rc = regionConfig()
    Map body = [datapoint: [value: value]]
    Map params = [
        uri: "${rc.ads}/apiv1/dsns/${dsn}/properties/${propertyName}/datapoints.json",
        headers: authHeader(),
        contentType: "application/json",
        requestContentType: "application/json",
        body: JsonOutput.toJson(body),
        timeout: HTTP_TIMEOUT
    ]
    logDebug "writeDatapoint dsn=${dsn} ${propertyName}=${value}"
    asynchttpPost("writeDatapointCallback", params, [dsn: dsn, name: propertyName])
}

void writeDatapointCallback(resp, data) {
    if (resp.hasError()) {
        logError "writeDatapoint(${data?.name}) HTTP error: ${resp.getErrorMessage()}"
        return
    }
    int status = resp.getStatus()
    if (status == 401) {
        logWarn "writeDatapoint(${data?.name}) 401 — refresh and retry once"
        refreshToken()
        return
    }
    if (status != 200 && status != 201) {
        logError "writeDatapoint(${data?.name}) HTTP ${status}: ${resp.getData()}"
        return
    }
    logDebug "writeDatapoint(${data?.name}) ok"
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

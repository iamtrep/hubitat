// Copyright (c) 2026 PJ
// SPDX-License-Identifier: MIT

/*
 * Hilo Manager — Parent Integration App
 *
 * Logs in to a Hilo account, reads one location's hourly consumption from Hilo's
 * history endpoint, and publishes it on a Hilo Energy Meter child device. The
 * child receives whole-home power by push from Hilo's GraphQL subscription and
 * asks this app for an access token on each connect.
 *
 * One instance serves one Hilo location; add another instance for another location.
 *
 * Login borrows the Home Assistant integration's public client, whose only
 * registered redirect is my.home-assistant.io, so the user pastes the URL they
 * land on after logging in. Client id, scope and subscription key are those of
 * python-hilo (MIT). No source incorporated.
 */

import com.hubitat.app.ChildDeviceWrapper
import groovy.json.JsonOutput
import groovy.transform.CompileStatic
import groovy.transform.Field
import java.security.MessageDigest
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.concurrent.ConcurrentHashMap

definition(
    name: "Hilo Manager",
    namespace: "iamtrep",
    author: "pj",
    description: "Hilo (Hydro-Québec) home energy: hourly consumption and live power",
    menu: "Integrations",
    category: "Utility",
    // Token refresh, poll callbacks and child calls all read and rewrite the tokens.
    singleThreaded: true,
    importUrl: "https://raw.githubusercontent.com/iamtrep/hubitat/refs/heads/main/integrations/Hilo/HiloManager.groovy",
    documentationLink: "https://github.com/iamtrep/hubitat/tree/main/integrations/Hilo",
    iconUrl: "",
    iconX2Url: ""
)

@Field static final String CODE_VERSION = "0.1.0"

@Field static final String AUTH_URL = "https://connexion.hiloenergie.com/HiloDirectoryB2C.onmicrosoft.com/B2C_1A_SIGN_IN/oauth2/v2.0/"
@Field static final String CLIENT_ID = "1ca9f585-4a55-4085-8e30-9746a65fa561"
@Field static final String REDIRECT_URI = "https://my.home-assistant.io/redirect/oauth"
@Field static final String SCOPE = "openid https://HiloDirectoryB2C.onmicrosoft.com/hiloapis/user_impersonation offline_access"
@Field static final String SUBSCRIPTION_KEY = "20eeaedcb86945afa3fe792cea89b8bf"
@Field static final String API_URL = "https://api.hiloenergie.com/Automation/v1/api"
// Version kept in step with CODE_VERSION by hand: a static initializer cannot read another @Field.
@Field static final String USER_AGENT = "HiloManager/0.1.0 (Hubitat)"

@Field static final String DNI_PREFIX = "hilo-"
@Field static final String DRIVER_METER = "Hilo Energy Meter"
@Field static final String VIEWER_FILE = "hilo-history.html"

@Field static final long TOKEN_REFRESH_BUFFER_MS = 300_000L
@Field static final int HTTP_TIMEOUT = 20
@Field static final int DEBUG_LOG_TIMEOUT = 1800
// Hourly polls: warn on each failure below this many in a row, then one error,
// then a warn every OUTAGE_REMINDER_MS until they clear.
@Field static final int FAILURE_ERROR_THRESHOLD = 3
@Field static final long OUTAGE_REMINDER_MS = 3_600_000L
// Longest Retry-After honored, so a bad header can't stop polling for good.
@Field static final long RETRY_AFTER_MAX_MS = 3_600_000L
@Field static final int LOG_BODY_MAX = 200
// History files: seconds between days read into File Manager while catching up (one call
// a minute, the Home Assistant integration's minimum poll), the furthest back the start of
// the data is looked for, and how long an unanswered history request blocks the next one.
@Field static final int BACKFILL_INTERVAL_SECS = 60
@Field static final int HISTORY_MAX_WEEKS = 520
@Field static final long HISTORY_STALE_MS = 600_000L

// Access tokens, in memory only: [token, expiresAt]. Keyed by app id, since every
// instance of the app shares this map. Lost on reboot or code push, which costs one refresh.
@Field static final ConcurrentHashMap<String, List> ACCESS = new ConcurrentHashMap<>()
// Decrypted secrets: decrypt() costs ~350 ms on a C-8 Pro, ~830 ms on a C-7.
// Keyed by app id + state key; the stored ciphertext rides along so a rotated value misses.
@Field static final ConcurrentHashMap<String, List<String>> SECRET_CACHE = new ConcurrentHashMap<>()

preferences {
    page(name: "mainPage")
}

Map mainPage() {
    if (settings.pastedUrl) {
        redeemPastedUrl(settings.pastedUrl as String)
        app.removeSetting("pastedUrl")
    }
    if (settings.locationId && settings.locationId != state.locationId) selectLocation(settings.locationId as String)

    dynamicPage(name: "mainPage", title: "Hilo Manager", install: true, uninstall: true) {
        if (state.loginError) {
            section { paragraph "<span style='color:red;'><b>Error:</b> ${state.loginError}</span>" }
        }
        if (!isLoggedIn()) {
            if (!state.codeVerifier) newPkce()
            section("Log in") {
                paragraph "1. Log in to Hilo. After the login you land on a my.home-assistant.io page; it may say the link is not set up. That page is expected."
                href url: authorizeUrl(), style: "external", title: "Log in to Hilo", description: "Opens Hilo's login page in a new tab"
                input "pastedUrl", "text", title: "2. Copy the address of that page from the browser's address bar and paste it here",
                      submitOnChange: true, required: false
            }
        } else if (!state.locationId) {
            section("Location") {
                Map<String, String> opts = ((state.locations ?: []) as List<Map>).collectEntries { [(it.id as String): it.name as String] }
                if (opts) {
                    input "locationId", "enum", title: "Hilo location", options: opts, submitOnChange: true, required: true
                } else {
                    paragraph "No location found on this Hilo account."
                }
            }
        } else {
            section("Status") {
                paragraph "Location: <b>${state.locationName}</b>"
                if (state.childError) paragraph "<span style='color:red;'><b>Error:</b> ${state.childError}</span>"
                ChildDeviceWrapper meter = meterChild()
                if (meter) paragraph "Device: <a href='/device/edit/${meter.id}' target='_blank'>${meter.displayName}</a>"
                else paragraph "The device is created when you press Done."
                if (state.lastPoll) paragraph "Last history read: ${formatClock(state.lastPoll as long)}"
                paragraph historySummary()
                if (state.pollStreak) paragraph "History reads failing since ${formatClock((state.pollStreak as Map).since as long)}"
                input "btnRefreshNow", "button", title: "Read history now"
            }
            section("Account") {
                input "btnLogout", "button", title: "Log out of Hilo"
            }
        }
        section("Logging") {
            input "txtEnable", "bool", title: "Enable info logging", defaultValue: true
            input "debugEnable", "bool", title: "Enable debug logging", defaultValue: false, submitOnChange: true
            if (settings.debugEnable) input "traceEnable", "bool", title: "Enable trace logging", defaultValue: false
        }
    }
}

void installed() { logDebug "installed"; state.version = CODE_VERSION; initialize() }

void updated() {
    logDebug "updated"
    unsubscribe()
    unschedule()
    // unschedule() drops a pending backfill step; let the next poll resume it.
    state.remove("historyBusy")
    initialize()
    if (settings.debugEnable || settings.traceEnable) runIn(DEBUG_LOG_TIMEOUT, "logsOff")
}

void uninstalled() {
    logDebug "uninstalled"
    ACCESS.remove(app.id.toString())
    dropSecret("refreshTokenEnc")
}

void initialize() {
    logDebug "initialize"
    checkVersion(false)
    if (!isLoggedIn() || !state.locationId) return
    // Without its device the instance doesn't poll: another instance may own the location.
    if (!ensureChild()) return
    schedulePolling()
    runIn(5, "poll")
}

// A code push doesn't run updated(); entry points call this so the first one after a
// push converges as if Done were pressed.
private void checkVersion(boolean reinit = true) {
    if (state.version == CODE_VERSION) return
    logVer "new version: ${CODE_VERSION} (was: ${state.version})"
    state.version = CODE_VERSION
    if (reinit) runIn(1, "updated")
}

void appButtonHandler(String btn) {
    logDebug "appButtonHandler(${btn})"
    checkVersion()
    switch (btn) {
        case "btnRefreshNow": poll(); break
        case "btnLogout":     logout(); break
        default: logWarn "unhandled button: ${btn}"
    }
}

// ── Login ─────────────────────────────────────────────────────────────

private boolean isLoggedIn() { return state.refreshTokenEnc != null }

private void newPkce() {
    String seed = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "")
    state.codeVerifier = base64UrlEncode(seed.getBytes("UTF-8"))
    state.oauthState = UUID.randomUUID().toString()
}

private String authorizeUrl() {
    byte[] digest = MessageDigest.getInstance("SHA-256").digest((state.codeVerifier as String).getBytes("US-ASCII"))
    String qs = toQueryString([
        client_id            : CLIENT_ID,
        response_type        : "code",
        redirect_uri         : REDIRECT_URI,
        scope                : SCOPE,
        code_challenge       : base64UrlEncode(digest),
        code_challenge_method: "S256",
        state                : state.oauthState
    ])
    return "${AUTH_URL}authorize?${qs}"
}

// Runs inside the page render: the page needs the result before it returns.
private void redeemPastedUrl(String pasted) {
    state.remove("loginError")
    Map<String, String> q = queryParams(pasted)
    if (q.error) { state.loginError = "Hilo refused the login: ${q.error_description ?: q.error}"; return }
    if (!q.code) { state.loginError = "No login code in the pasted address. Paste the whole address of the page you landed on."; return }
    if (q.state != state.oauthState) { state.loginError = "That address comes from an older login link. Log in again with the link below."; newPkce(); return }
    String verifier = state.codeVerifier as String
    // A code is redeemed once; the next login gets a fresh verifier.
    newPkce()
    Map tokens = tokenRequest([grant_type: "authorization_code", client_id: CLIENT_ID, code: q.code,
                               redirect_uri: REDIRECT_URI, code_verifier: verifier])
    if (tokens.error) { state.loginError = "Login failed: ${tokens.error}"; return }
    storeTokens(tokens)
    logCfg "logged in to Hilo"
    loadLocations()
}

private void loadLocations() {
    List<Map> locs = []
    try {
        httpGet([uri: "${API_URL}/Locations", headers: apiHeaders(hiloAccessToken()), contentType: "application/json", timeout: HTTP_TIMEOUT]) { resp ->
            (resp.data as List).each { Map l -> locs << [id: l.id as String, hiloId: l.locationHiloId as String, name: (l.name ?: "Location ${l.id}") as String] }
        }
    } catch (Exception e) {
        state.loginError = "Could not read the Hilo locations: ${exceptionText(e)}"
        return
    }
    state.locations = locs
    logDebug "locations: ${locs.collect { it.name }}"
    if (locs.size() == 1) {
        app.updateSetting("locationId", [type: "enum", value: locs[0].id])
        selectLocation(locs[0].id as String)
    }
}

private void selectLocation(String id) {
    Map loc = ((state.locations ?: []) as List<Map>).find { it.id == id }
    if (!loc) return
    state.locationId = loc.id
    state.locationHiloId = loc.hiloId
    state.locationName = loc.name
    if (!app.label || app.label == "Hilo Manager") app.updateLabel("Hilo Manager - ${loc.name}")
    logCfg "location: ${loc.name}"
}

void logout() {
    logCfg "logged out of Hilo"
    unschedule()
    ACCESS.remove(app.id.toString())
    dropSecret("refreshTokenEnc")
    ["refreshTokenEnc", "locations", "locationId", "locationHiloId", "locationName", "pollStreak", "childError",
     "historyStart", "lastCompleteDay", "historyBusy", "backfilling", "finalDay", "lastHourMs"].each { state.remove(it) }
    app.removeSetting("locationId")
    meterChild()?.stopLiveStream()
    meterChild()?.updateHealth("offline", "logged out of Hilo")
}

// ── Tokens ────────────────────────────────────────────────────────────

// Called by the child before each stream connect, and by the polls. Returns null when
// no token can be had; the reason is already logged.
String hiloAccessToken() {
    List cached = ACCESS.get(app.id.toString())
    if (cached && (cached[1] as long) - now() > TOKEN_REFRESH_BUFFER_MS) return cached[0] as String
    String refresh = decryptState("refreshTokenEnc")
    if (!refresh) return null
    Map tokens = tokenRequest([grant_type: "refresh_token", client_id: CLIENT_ID, refresh_token: refresh, scope: SCOPE])
    if (tokens.error) {
        if (tokens.rejected) loginLost(tokens.error as String)
        else logWarn "token refresh failed: ${tokens.error}"
        return null
    }
    storeTokens(tokens)
    return tokens.access_token as String
}

// Sync: the caller needs the token before it can do anything else.
// Returns the token response, or [error: text, rejected: true when Hilo refused the grant].
private Map tokenRequest(Map body) {
    Map out = [:]
    try {
        httpPost([uri: "${AUTH_URL}token", requestContentType: "application/x-www-form-urlencoded", contentType: "application/json",
                  headers: ["User-Agent": USER_AGENT], body: body, timeout: HTTP_TIMEOUT]) { resp -> out = resp.data as Map }
    } catch (groovyx.net.http.HttpResponseException e) {
        Map err = [:]
        try { err = e.response?.data as Map } catch (Exception ignored) { }
        int status = e.statusCode
        return [error: "HTTP ${status}: ${err?.error_description ?: err?.error ?: e.message}", rejected: status == 400 || status == 401]
    } catch (Exception e) {
        return [error: exceptionText(e)]
    }
    if (!out.access_token) return [error: "no access token in Hilo's answer"]
    return out
}

// Hilo issues a new refresh token on every refresh, so each one replaces the last.
private void storeTokens(Map t) {
    long expiresIn = ((t.expires_in ?: 3600) as long) * 1000L
    ACCESS.put(app.id.toString(), [t.access_token as String, now() + expiresIn])
    if (t.refresh_token) {
        state.refreshTokenEnc = encrypt(t.refresh_token as String)
        SECRET_CACHE.put("${app.id}:refreshTokenEnc".toString(), [state.refreshTokenEnc as String, t.refresh_token as String])
    }
    state.remove("loginError")
}

private void loginLost(String why) {
    logError "Hilo refused the saved login (${why}); log in again from the app page"
    unschedule("poll")
    ACCESS.remove(app.id.toString())
    dropSecret("refreshTokenEnc")
    state.remove("refreshTokenEnc")
    state.loginError = "Hilo refused the saved login. Log in again."
    meterChild()?.updateHealth("offline", "log in to Hilo again")
}

private String decryptState(String key) {
    String enc = state[key] as String
    if (!enc) return null
    List<String> hit = SECRET_CACHE.get("${app.id}:${key}".toString())
    if (hit != null && hit[0] == enc) return hit[1]
    try {
        String plain = decrypt(enc)
        SECRET_CACHE.put("${app.id}:${key}".toString(), [enc, plain])
        return plain
    } catch (Exception e) {
        logWarn "could not decrypt ${key} (${e.message}); log in again"
        state.remove(key)
        dropSecret(key)
        return null
    }
}

private void dropSecret(String key) { SECRET_CACHE.remove("${app.id}:${key}".toString()) }

private Map<String, String> apiHeaders(String token) {
    return ["Authorization": "Bearer ${token}", "Ocp-Apim-Subscription-Key": SUBSCRIPTION_KEY,
            "Hilo-Tenant": "hilo", "User-Agent": USER_AGENT]
}

// ── Child device ──────────────────────────────────────────────────────

private String meterDni() { return "${DNI_PREFIX}${state.locationHiloId}-energy" }

private ChildDeviceWrapper meterChild() { return state.locationHiloId ? getChildDevice(meterDni()) : null }

private boolean ensureChild() {
    ChildDeviceWrapper meter = meterChild()
    if (!meter) {
        try {
            meter = addChildDevice("iamtrep", DRIVER_METER, meterDni(), [name: DRIVER_METER, label: "Hilo ${state.locationName}"])
            logCfg "created ${meter.displayName}"
            state.remove("childError")
        } catch (Exception e) {
            // DNIs are hub-wide: the usual cause is another instance serving this location.
            state.childError = "Could not create the device (${e.message}). Is this location already connected in another Hilo Manager?"
            logError state.childError as String
            return false
        }
    }
    meter.updateDataValue("locationHiloId", state.locationHiloId as String)
    meter.startLiveStream()
    return true
}

// Called by the child's refresh().
void refreshNow() { checkVersion(); poll() }

// ── History polling ───────────────────────────────────────────────────

// Hourly at a random minute from :10 to :50, so hubs don't call Hilo in step.
private void schedulePolling() {
    Random rng = new Random()
    schedule("${rng.nextInt(60)} ${10 + rng.nextInt(41)} * ? * *", "poll")
}

void poll() {
    checkVersion()
    if (!isLoggedIn() || !state.locationId) return
    if (state.pausedUntil && now() < (state.pausedUntil as long)) { logDebug "paused by Retry-After"; return }
    String token = hiloAccessToken()
    if (!token) { if (isLoggedIn()) pollFailed("no access token"); return }
    Date nowDate = new Date()
    String today = nowDate.format("yyyy-MM-dd", location.timeZone)
    String yesterday = (nowDate - 1).format("yyyy-MM-dd", location.timeZone)
    fetchDay(token, today)
    if (state.finalDay != yesterday) fetchDay(token, yesterday)
    continueHistory()
}

private void fetchDay(String token, String day) {
    logNet "Energy2 ${day}"
    asynchttpGet("energyCallback", [uri: "${API_URL}/Locations/${state.locationId}/History/Energy2",
                                    query: [date: day, timescale: "Day"], headers: apiHeaders(token),
                                    contentType: "application/json", timeout: HTTP_TIMEOUT], [day: day])
}

void energyCallback(resp, data) {
    String day = data.day as String
    if (resp.hasError() || resp.getStatus() != 200) {
        int status = resp.getStatus()
        if (status == 429 || status == 503) noteRetryAfter(resp)
        if (status == 401) ACCESS.remove(app.id.toString())
        pollFailed("Energy2 ${day}: ${httpError(resp)} ${bodyExcerpt(resp)}")
        return
    }
    List<Map> hours
    try {
        hours = parseHours(resp.getJson())
    } catch (Exception e) {
        pollFailed("Energy2 ${day}: unreadable answer (${e.message})")
        return
    }
    pollSucceeded()
    logTrace "Energy2 ${day}: ${hours}"
    Map ev = [:]
    String today = new Date().format("yyyy-MM-dd", location.timeZone)
    List<Map> known = hours.findAll { it.kWh != null }
    if (day == today) {
        ev.energy = round3(known.sum(0.0) { it.kWh as BigDecimal } as BigDecimal)
    } else if (known && known.size() == hours.size()) {
        // Every hour in: yesterday is final and is not read again once it is on file.
        ev.energyYesterday = round3(known.sum(0.0) { it.kWh as BigDecimal } as BigDecimal)
        if (writeDay(day, hours)) {
            state.finalDay = day
            if (state.lastCompleteDay == LocalDate.parse(day).minusDays(1).toString()) state.lastCompleteDay = day
        }
    }
    Map last = known ? known.max { it.ms as long } : null
    if (last && (last.ms as long) > ((state.lastHourMs ?: 0L) as long)) {
        state.lastHourMs = last.ms
        ev.energyLastHour = round3(last.kWh as BigDecimal)
        ev.lastHourStart = last.start
    }
    state.lastPoll = now()
    meterChild()?.updateEnergy(ev)
}

// Each hour keeps its own start time, so 23- and 25-hour days need no special case.
private List<Map> parseHours(json) {
    List vals = (json?.points?.values ?: []) as List
    return vals.collect { Map v ->
        String start = v.date as String
        [start: start, ms: OffsetDateTime.parse(start).toInstant().toEpochMilli(), kWh: v.energy == null ? null : (v.energy as BigDecimal)]
    }
}

// ── History files ─────────────────────────────────────────────────────
// One File Manager file per month: {unit, location, days: {"YYYY-MM-DD": [{start, kWh}, …]}}.
// state.historyStart is the first day Hilo has data for; state.lastCompleteDay is how far
// the files are complete. Yesterday is written by the poll once all its hours are in;
// older days are read here, one request at a time, and kept with any null hours.

private String historyFile(String month) { return "hilo-${state.locationId}-energy-${month}.json" }

private LocalDate localToday() { return LocalDate.now(location.timeZone.toZoneId()) }

private boolean historyBusy() {
    return state.historyBusy && now() - (state.historyBusy as long) < HISTORY_STALE_MS
}

private void continueHistory() {
    if (historyBusy()) return
    if (state.historyStart) { backfillNext(); return }
    state.historyBusy = now()
    logInfo "looking for the start of the Hilo history"
    fetchWeek(localToday().minusDays(6).toString(), 0, null)
}

// Steps back a week at a time until a week has no data at all. Week answers give 0.0, not
// null, for days before the data starts (Day answers give null), so 0 counts as no data.
private void fetchWeek(String start, int weeks, String laterFirst) {
    String token = hiloAccessToken()
    if (!token) { state.remove("historyBusy"); return }
    logNet "Energy2 week from ${start}"
    asynchttpGet("weekCallback", [uri: "${API_URL}/Locations/${state.locationId}/History/Energy2",
                                  query: [date: start, timescale: "Week"], headers: apiHeaders(token),
                                  contentType: "application/json", timeout: HTTP_TIMEOUT],
                 [start: start, weeks: weeks, laterFirst: laterFirst])
}

void weekCallback(resp, data) {
    if (resp.hasError() || resp.getStatus() != 200) {
        // The poll reports outages; the search resumes at the next poll.
        logDebug "history search stopped: ${httpError(resp)}"
        state.remove("historyBusy")
        return
    }
    String first
    try {
        Map hit = ((resp.getJson()?.points?.values ?: []) as List<Map>).find { it.energy != null && (it.energy as BigDecimal) > 0 }
        first = hit ? LocalDate.parse((hit.date as String).take(10)).toString() : null
    } catch (Exception e) {
        logDebug "history search stopped: unreadable answer (${e.message})"
        state.remove("historyBusy")
        return
    }
    int weeks = data.weeks as int
    String found = first == null ? data.laterFirst as String : (weeks >= HISTORY_MAX_WEEKS ? first : null)
    if (first == null && found == null) {
        logInfo "no Hilo history yet"
        state.remove("historyBusy")
        return
    }
    if (found) {
        state.historyStart = found
        state.lastCompleteDay = LocalDate.parse(found).minusDays(1).toString()
        long days = localToday().toEpochDay() - LocalDate.parse(found).toEpochDay()
        logInfo "Hilo history starts on ${found}; reading ${days} days into File Manager, one a minute"
        state.remove("historyBusy")
        backfillNext()
        return
    }
    fetchWeek(LocalDate.parse(data.start as String).minusDays(7).toString(), weeks + 1, first)
}

private void backfillNext() {
    String next = LocalDate.parse(state.lastCompleteDay as String).plusDays(1).toString()
    // Yesterday and today belong to the poll.
    if (LocalDate.parse(next) >= localToday().minusDays(1)) {
        if (state.backfilling) { logInfo "Hilo history complete in File Manager from ${state.historyStart}"; state.remove("backfilling") }
        state.remove("historyBusy")
        return
    }
    String token = hiloAccessToken()
    if (!token) { state.remove("historyBusy"); return }
    state.historyBusy = now()
    state.backfilling = true
    logNet "Energy2 ${next} (history)"
    asynchttpGet("backfillCallback", [uri: "${API_URL}/Locations/${state.locationId}/History/Energy2",
                                      query: [date: next, timescale: "Day"], headers: apiHeaders(token),
                                      contentType: "application/json", timeout: HTTP_TIMEOUT], [day: next])
}

void backfillTick() { backfillNext() }

void backfillCallback(resp, data) {
    String day = data.day as String
    if (resp.hasError() || resp.getStatus() != 200) {
        logDebug "history read of ${day} stopped: ${httpError(resp)}"
        state.remove("historyBusy")
        return
    }
    List<Map> hours
    try {
        hours = parseHours(resp.getJson())
    } catch (Exception e) {
        logWarn "history for ${day} unreadable (${e.message}); skipped"
        hours = null
    }
    if (hours != null && !writeDay(day, hours)) { state.remove("historyBusy"); return }
    state.lastCompleteDay = day
    // historyBusy stays set between days, so the hourly poll doesn't start a second chain.
    if (LocalDate.parse(day).plusDays(1) >= localToday().minusDays(1)) backfillNext()
    else runIn(BACKFILL_INTERVAL_SECS, "backfillTick")
}

// Rewrites the day's month file with that day replaced. Returns false when the file can't be written.
private boolean writeDay(String day, List<Map> hours) {
    String name = historyFile(day.take(7))
    Map doc = readHistoryFile(name)
    if (doc == null) return false
    Map days = (doc.days ?: [:]) as Map
    days[day] = hours.collect { [start: it.start, kWh: it.kWh == null ? null : round3(it.kWh as BigDecimal)] }
    doc.days = days.sort { it.key }
    try {
        uploadHubFile(name, JsonOutput.toJson(doc).getBytes("UTF-8"))
    } catch (Exception e) {
        logWarn "could not write ${name}: ${getObjectClassName(e)}: ${e.message}"
        return false
    }
    logDebug "wrote ${day} to ${name}"
    return true
}

// A new month starts empty. downloadHubFile returns null for a missing file from 2.5.3 and
// throws NoSuchFileException before. Returns null when an existing file can't be read,
// so it is never overwritten with less than it held.
private Map readHistoryFile(String name) {
    Map empty = [unit: "kWh", location: state.locationId, days: [:]]
    byte[] bytes
    try {
        bytes = downloadHubFile(name)
    } catch (Exception e) {
        if (getObjectClassName(e).endsWith("NoSuchFileException")) return empty
        logWarn "could not read ${name}: ${getObjectClassName(e)}: ${e.message}"
        return null
    }
    if (bytes == null) return empty
    try {
        return parseJson(new String(bytes, "UTF-8")) as Map
    } catch (Exception e) {
        logWarn "${name} is not valid JSON (${e.message}); left as it is"
        return null
    }
}

private String historySummary() {
    if (!state.historyStart) return historyBusy() ? "History: looking for where it starts" : "History: not read yet"
    long left = localToday().minusDays(1).toEpochDay() - LocalDate.parse(state.lastCompleteDay as String).toEpochDay() - 1
    String files = "<a href='/hub/fileManager' target='_blank'>File Manager</a>, ${historyFile('YYYY-MM')}"
    String text = left > 0 ? "History: from ${state.historyStart}, ${left} days still to read (${files})" :
                             "History: complete from ${state.historyStart} (${files})"
    if (viewerInstalled()) text += "<br><a href='/local/${VIEWER_FILE}' target='_blank'>Browse the history</a>"
    return text
}

// The viewer page is uploaded to File Manager by hand; see the README.
private boolean viewerInstalled() {
    try { return downloadHubFile(VIEWER_FILE) != null } catch (Exception e) { return false }
}

@CompileStatic
private static BigDecimal round3(BigDecimal v) { return v.setScale(3, BigDecimal.ROUND_HALF_UP) }

// ── Outages ───────────────────────────────────────────────────────────

private void pollFailed(String msg) {
    Map streak = streakFailed((state.pollStreak ?: [:]) as Map, "Hilo", msg)
    state.pollStreak = streak
    if ((streak.count as int) == FAILURE_ERROR_THRESHOLD) meterChild()?.updateHealth("offline", "Hilo unreachable")
}

private void pollSucceeded() {
    if (!state.pollStreak) return
    String recovery = streakRecovered(state.pollStreak as Map, "Hilo")
    state.remove("pollStreak")
    if (recovery) meterChild()?.updateHealth("online", recovery)
}

// Logs one failure of a streak and returns the updated streak [count, since, reminded]:
// warn below FAILURE_ERROR_THRESHOLD, one error at it, then a warn every OUTAGE_REMINDER_MS.
private Map streakFailed(Map streak, String what, String msg) {
    long t = now()
    int n = ((streak.count ?: 0) as int) + 1
    long since = (streak.since ?: t) as long
    Map updated = [count: n, since: since, reminded: streak.reminded]
    if (n < FAILURE_ERROR_THRESHOLD) {
        logWarn "${msg} (failure ${n}/${FAILURE_ERROR_THRESHOLD})"
    } else if (n == FAILURE_ERROR_THRESHOLD) {
        logError "${what} unreachable since ${formatClock(since)}: ${msg}"
        updated.reminded = t
    } else if (t - ((streak.reminded ?: 0L) as long) >= OUTAGE_REMINDER_MS) {
        logWarn "${what} still unreachable after ${formatDuration(t - since)} (${n} failures): ${msg}"
        updated.reminded = t
    } else {
        logDebug "${msg} (failure ${n})"
    }
    return updated
}

// Returns the recovery line when the streak had reached the threshold, else null.
private String streakRecovered(Map streak, String what) {
    int n = (streak.count ?: 0) as int
    if (n >= FAILURE_ERROR_THRESHOLD) {
        String recovery = "${what} back after ${formatDuration(now() - (streak.since as long))} (${n} failures)"
        logInfo recovery
        return recovery
    }
    if (n > 0) logDebug "${what} recovered after ${n} failure(s)"
    return null
}

// Honors a Retry-After in seconds by skipping polls until then.
private void noteRetryAfter(resp) {
    Long secs = null
    try {
        String v = resp.getHeaders()?.find { k, val -> (k as String)?.equalsIgnoreCase("Retry-After") }?.value as String
        if (v?.trim()?.isLong()) secs = v.trim().toLong()
    } catch (Exception e) {
        return
    }
    if (secs == null || secs <= 0) return
    long ms = Math.min(secs * 1000L, RETRY_AFTER_MAX_MS)
    state.pausedUntil = now() + ms
    logWarn "Hilo asked to retry after ${secs} s; polling paused until ${formatClock(now() + ms)}"
}

// ── Helpers ───────────────────────────────────────────────────────────

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

private String exceptionText(Exception e) {
    if (e instanceof groovyx.net.http.HttpResponseException) return "HTTP ${e.statusCode}: ${e.message}"
    return "${getObjectClassName(e)}: ${e.message}"
}

private Map<String, String> queryParams(String url) {
    int q = url.indexOf("?")
    if (q < 0) return [:]
    String qs = url.substring(q + 1)
    int hash = qs.indexOf("#")
    if (hash >= 0) qs = qs.substring(0, hash)
    return qs.split("&").findAll { it.contains("=") }.collectEntries { String p ->
        int i = p.indexOf("=")
        [(URLDecoder.decode(p.substring(0, i), "UTF-8")): URLDecoder.decode(p.substring(i + 1).trim(), "UTF-8")]
    }
}

@CompileStatic
private static String base64UrlEncode(byte[] data) {
    return data.encodeBase64().toString().replace("=", "").replace("+", "-").replace("/", "_")
}

@CompileStatic
private static String toQueryString(Map params) {
    return params.collect { k, v -> "${URLEncoder.encode(k.toString(), 'UTF-8')}=${URLEncoder.encode(v.toString(), 'UTF-8').replace('+', '%20')}" }.join("&")
}

private String formatClock(long t) {
    return new Date(t).format("yyyy-MM-dd HH:mm", location.timeZone)
}

private static String formatDuration(long ms) {
    long minutes = ms.intdiv(60000L)
    if (minutes < 60) return "${minutes} min"
    return "${minutes.intdiv(60L)} h ${minutes % 60} min"
}

void logsOff() {
    logWarn "debug and trace logging disabled"
    app.updateSetting("debugEnable", [value: "false", type: "bool"])
    app.updateSetting("traceEnable", [value: "false", type: "bool"])
}

// ── Logging (app) ─────────────────────────────────────────────────────
//   🔧 Cfg  🌐 Net  🏷️ Ver  ·  ⚠️ Warn  🛑 Error  🔬 Trace
private String logp(String e) { "${e} ${app.getLabel()}: " }

void logCfg  (String m) { if (txtEnable != false) log.info  logp('🔧') + m }
void logNet  (String m) { if (settings.debugEnable) log.debug logp('🌐') + m }
void logVer  (String m) { log.warn  logp('🏷️') + m }

void logWarn (String m) { log.warn  logp('⚠️') + m }
void logError(String m) { log.error logp('🛑') + m }
void logTrace(String m) { if (settings.traceEnable) log.trace logp('🔬') + m }
void logInfo (String m) { if (txtEnable != false) log.info  "${app.getLabel()}: ${m}" }
void logDebug(String m) { if (settings.debugEnable) log.debug "${app.getLabel()}: ${m}" }

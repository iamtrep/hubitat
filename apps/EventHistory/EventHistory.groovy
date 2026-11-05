// Copyright (c) 2026 PJ
// SPDX-License-Identifier: MIT

/*
 Event History

 Keeps the hub's hub and location events past the limits of its own lists, in capped File Manager
 files. A location wildcard subscription catches each event as it happens; an hourly read of both
 lists adds what the subscription can't see: events from before install, events during a restart,
 and events with no value, which reach no subscriber. Rows merge by event id.

 Secrets are masked before a row is stored, always. See "Masking" below.
 */

import groovy.json.JsonOutput
import groovy.transform.Field
import java.nio.file.NoSuchFileException
import java.text.SimpleDateFormat
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.regex.Matcher
import java.util.regex.Pattern

@Field static final String CODE_VERSION = "0.1.0"
@Field static final String BASE_URL = "http://127.0.0.1:8080"
@Field static final String UI_FILE = "eventhistory-ui.html"
@Field static final String UI_URL = "https://raw.githubusercontent.com/iamtrep/hubitat/refs/heads/main/apps/EventHistory/eventhistory-ui.html"
@Field static final Map<String, String> LIST_PATHS = [hub: "/hub/eventsJson", location: "/logs/eventsJson"]
@Field static final int DEFAULT_ROWS_PER_FILE = 2000
@Field static final Map<String, Integer> DEFAULT_KEEP_FILES = [hub: 5, location: 25]
@Field static final int FLUSH_ROWS = 100
@Field static final int MAX_BUFFER = 5000
@Field static final int MAX_SEEN = 5000
@Field static final int MAX_FIELD = 1000
// What a stored event takes, for the capacity shown on the page: about 120 bytes on two hubs.
@Field static final int ROW_BYTES = 125
@Field static final int MAX_NAMES = 2000
// Generic names that would only make text harder to read if replaced.
@Field static final List<String> GENERIC_NAMES = ["Home", "Hubitat", "Hub", "Local Hub"]

// Rows waiting for a flush, keyed "appId:stream". Lost on a restart or code save; the next read of
// the lists brings back what they still hold.
@Field static final ConcurrentHashMap<String, ConcurrentLinkedQueue<Map>> BUFFER = new ConcurrentHashMap<>()
// Recent event ids, keyed by app id, so a row seen by the subscription and the list is stored once.
@Field static final ConcurrentHashMap<String, LinkedHashSet<Long>> SEEN = new ConcurrentHashMap<>()
@Field static final ConcurrentHashMap<String, List<String>> NAME_ORDER = new ConcurrentHashMap<>()

definition(
    name: "Event History",
    namespace: "iamtrep",
    author: "pj",
    description: "Keeps hub and location events past the hub's own limits, in capped File Manager files",
    menu: "Utilities",
    category: "Utility",
    iconUrl: "",
    iconX2Url: "",
    importUrl: "https://raw.githubusercontent.com/iamtrep/hubitat/refs/heads/main/apps/EventHistory/EventHistory.groovy",
    documentationLink: "https://github.com/iamtrep/hubitat/tree/main/apps/EventHistory",
    oauth: true,
    singleInstance: true,
    singleThreaded: true
)

preferences {
    page(name: "mainPage")
}

mappings {
    path("/ui")                   { action: [GET: "serveUI"] }
    path("/api/index")            { action: [GET: "apiIndex"] }
    path("/api/file/:stream/:n")  { action: [GET: "apiFile"] }
    path("/api/tail")             { action: [GET: "apiTail"] }
}

Map mainPage() {
    boolean oauth = checkOAuth()
    dynamicPage(name: "mainPage", install: true, uninstall: true) {
        section {
            if (oauth) {
                paragraph rawHtml: true, "<a class='p-button bg-hubitat-primary-green no-underline' style='color:#10202a !important;font-weight:600' target='_blank' " +
                    "href='${getFullLocalApiServerUrl()}/ui?access_token=${state.accessToken}'>Open event history</a>"
            } else {
                paragraph rawHtml: true, "<div class='p-message p-message-warn p-2'>The event history page needs OAuth, and it could not be turned on. " +
                    "In Apps Code, open Event History, click OAuth, then Enable OAuth in App, then Update. Events are still being kept.</div>"
            }
            paragraph rawHtml: true, statusHtml()
        }
        section("How much to keep", hideable: true, hidden: true) {
            paragraph capacityText()
            input name: "rowsPerFile", type: "number", title: "Events per file", defaultValue: DEFAULT_ROWS_PER_FILE, range: "100..10000", required: true, width: 3
            input name: "keepLocationFiles", type: "number", title: "Location files", defaultValue: DEFAULT_KEEP_FILES.location, range: "1..200", required: true, width: 3
            input name: "keepHubFiles", type: "number", title: "Hub files", defaultValue: DEFAULT_KEEP_FILES.hub, range: "1..200", required: true, width: 3
            input name: "maxAgeDays", type: "number", title: "Maximum age in days (0 for none)", defaultValue: 0, range: "0..3650", required: true, width: 3
            input name: "deleteOnRemove", type: "bool", title: "Delete the stored events when this app is removed", defaultValue: false
        }
        section("Events to skip", hideable: true, hidden: !settings.excludeNames) {
            input name: "excludeNames", type: "textarea", title: "Event names, one per line. End a name with * to match its start, as in variable:hydro_*", required: false
        }
        section("Privacy", hideable: true, hidden: !settings.hidePrivate) {
            paragraph "Passwords, tokens and keys are always masked before an event is stored. A secret in a plain value under a harmless name can't be recognized, so add that event to Events to skip."
            input name: "hidePrivate", type: "bool", title: "Also hide names and addresses in events stored from now on", defaultValue: false
            paragraph "<small>Replaces device, app, room and hub names with aliases, and masks IP and MAC addresses, emails, phone numbers, coordinates and serial numbers.</small>"
        }
        section("Maintenance and logging", hideable: true, hidden: true) {
            input name: "btnPoll", type: "button", title: "Read the hub's lists now", width: 4
            input name: "btnFlush", type: "button", title: "Save waiting events now", width: 4
            input name: "txtEnable", type: "bool", title: "Enable info logging", defaultValue: true
            input name: "debugEnable", type: "bool", title: "Enable debug logging", defaultValue: false
        }
    }
}

String statusHtml() {
    Map streams = (state.streams ?: [:]) as Map
    Map polls = (state.lastPoll ?: [:]) as Map
    String cell = "style='padding:2px 24px 2px 0;text-align:left;font-weight:normal'"
    String head = "style='padding:2px 24px 2px 0;text-align:left;font-weight:normal;color:var(--text-color-secondary,inherit)'"
    List<String> rows = [["hub", "Hub events"], ["location", "Location events"]].collect { List<String> r ->
        List<Map> files = (((streams[r[0]] ?: [:]) as Map).files ?: []) as List<Map>
        int count = (files.sum { (it.rows ?: 0) as int } ?: 0) as int
        String first = files && files[0].first != null ? shownTime(files[0].first as long) : "none yet"
        String read = polls[r[0]] != null ? shownTime(polls[r[0]] as long) : "not yet"
        "<tr><th ${cell}>${r[1]}</th><td ${cell}>${String.format('%,d', count)}</td><td ${cell}>${first}</td><td ${cell}>${read}</td></tr>"
    }
    String html = "<table style='border-collapse:collapse;margin-top:8px'><tr><th ${head}></th><th ${head}>Stored</th>" +
        "<th ${head}>Going back to</th><th ${head}>Last checked</th></tr>${rows.join('')}</table>"
    if (state.lastPollError) html += warning("The last check of the hub's lists failed: ${escapeHtml(state.lastPollError as String)}")
    if (state.droppedRows) html += warning("${state.droppedRows} events were lost because they could not be saved.")
    return html
}

private String warning(String text) {
    return "<div class='p-message p-message-warn p-2 mt-2'>${text}</div>"
}

private static String escapeHtml(String s) {
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("'", "&#39;")
}

String capacityText() {
    int location = rowsCap() * keepFiles("location")
    int hub = rowsCap() * keepFiles("hub")
    long mb = Math.max(1L, Math.round((location + hub) * ROW_BYTES / 1000000.0d) as long)
    return "Keeps up to ${String.format('%,d', location)} location events and ${String.format('%,d', hub)} hub events, about ${mb} MB. " +
        "The oldest go first. Files are left out of local backups and count toward the cloud backup limit."
}

private String shownTime(long t) {
    return new Date(t).format("MMM d, yyyy HH:mm", location.timeZone)
}

// ── Lifecycle ─────────────────────────────────────────────────────────

void installed() {
    checkOAuth()
    initialize()
}

void updated() {
    unsubscribe()
    unschedule()
    checkOAuth()
    initialize()
}

void uninstalled() {
    if (settings.deleteOnRemove) {
        ((state.streams ?: [:]) as Map).each { String s, Map st ->
            ((st.files ?: []) as List<Map>).each { Map f -> deleteQuietly(fileName(s, f.n as int)) }
        }
    }
    LIST_PATHS.keySet().each { String s -> BUFFER.remove(bufferKey(s)) }
    SEEN.remove(app.id.toString())
}

void initialize() {
    checkVersion(false)
    if (!state.fileToken) state.fileToken = UUID.randomUUID().toString().replace("-", "")
    if (!state.aliasSalt) state.aliasSalt = UUID.randomUUID().toString()
    if (state.streams == null) state.streams = LIST_PATHS.collectEntries { k, v -> [(k): [files: [], next: 1]] }
    if (state.highId == null) state.highId = [:]
    if (state.droppedRows == null) state.droppedRows = 0
    subscribe(location, "eventHandler")
    Random r = new Random()
    // Each cron has its own handler: a runIn and a schedule on one handler replace each other.
    schedule("${r.nextInt(60)} ${r.nextInt(60)} * ? * *", "hourlyPoll")
    runEvery5Minutes("flush")
    runIn(5, "poll")
    if (settings.hidePrivate) {
        runIn(2, "seedNames")
        schedule("${r.nextInt(60)} ${r.nextInt(60)} 4 * * ?", "dailySeedNames")
    }
}

void checkVersion(boolean reinit = true) {
    if (state.version == CODE_VERSION) return
    logVer "New version: ${CODE_VERSION} (was: ${state.version})"
    state.version = CODE_VERSION
    if (reinit) runIn(1, "updated")
}

void appButtonHandler(String btn) {
    if (btn == "btnPoll") poll()
    if (btn == "btnFlush") flush()
}

// ── Capture ───────────────────────────────────────────────────────────

void eventHandler(evt) {
    checkVersion()
    addRow(evt.id as Long, evt.date?.time ?: now(), evt.source?.toString(), evt.name as String, evt.value as String,
        evt.unit as String, evt.descriptionText as String, evt.type as String)
    // The lists are read again after a restart, for what happened while the app wasn't running.
    if (evt.name == "systemStart") runIn(120, "poll")
    if (buffered("hub") + buffered("location") >= FLUSH_ROWS) flush()
}

void hourlyPoll() { poll() }

void poll() {
    LIST_PATHS.each { String s, String path ->
        asynchttpGet("pollCallback", [uri: BASE_URL, path: path, timeout: 60], [stream: s])
    }
}

void pollCallback(resp, data) {
    String s = data.stream as String
    if (resp.hasError() || resp.getStatus() != 200) {
        state.lastPollError = maskSecrets("${s}: HTTP ${resp.getStatus()} ${resp.hasError() ? resp.getErrorMessage() : ''}".toString())
        logWarn "Could not read the ${s} list: ${state.lastPollError}"
        return
    }
    Map high = (state.highId ?: [:]) as Map
    long mark = (high[s] ?: 0L) as long
    long top = mark
    int added = 0
    (resp.getJson() as List<Map>).each { Map r ->
        long id = r.id as long
        if (id > top) top = id
        // A row at or below the mark was read before; it may be in a sealed file by now.
        if (id > mark && addRow(id, listTime(r.date), s == "hub" ? "HUB" : "LOCATION", r.name as String, r.value as String,
                r.unit as String, r.descriptionText as String, r.type as String)) added++
    }
    high[s] = top
    state.highId = high
    state.lastPoll = ((state.lastPoll ?: [:]) as Map) + [(s): now()]
    state.remove("lastPollError")
    logDebug "${s} list: ${added} new rows"
    flush()
}

static long listTime(Object date) {
    return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ").parse(date as String).time
}

private boolean addRow(Long id, long at, String source, String name, String value, String unit, String desc, String type) {
    if (id != null) {
        Set<Long> seen = seenIds()
        if (!seen.add(id)) return false
        if (seen.size() > MAX_SEEN) seen.remove(seen.find { true })
    }
    if (isExcluded(name, settings.excludeNames as String)) return false
    String s = source == "HUB" ? "hub" : "location"
    Map shown = maskEvent(name, value, unit, desc)
    if (settings.hidePrivate) {
        Map<String, String> aliases = (state.nameAliases ?: [:]) as Map<String, String>
        List<String> order = nameOrder(aliases)
        ["n", "v", "d"].each { String k -> shown[k] = maskIdentifiers(aliasNames(shown[k] as String, order, aliases)) }
    }
    Map row = [i: id, t: at, n: shown.n, v: shown.v, u: shown.u, d: shown.d, y: type].findAll { k, v -> v != null && v != "" }
    row = row.collectEntries { k, v -> [(k): v instanceof String ? clip(v as String) : v] }
    ConcurrentLinkedQueue<Map> q = queue(s)
    q.add(row)
    trimBuffer(q)
    return true
}

// Clipping comes after masking: a token cut first could slip past the patterns and be stored in part.
static String clip(String v) {
    return v.length() > MAX_FIELD ? v.substring(0, MAX_FIELD) + "…" : v
}

// The list is matched against the event's real name, before masking.
static boolean isExcluded(String name, String list) {
    if (!name || !list) return false
    return list.split(/[,\n]/).any { String raw ->
        String p = raw.trim()
        p && (p.endsWith("*") ? name.startsWith(p.substring(0, p.length() - 1)) : name == p)
    }
}

// An event keeps key and value apart, so the text patterns can't tie them together: the value of
// variable:apiKey is a bare string. Its name decides, and the description repeats the value.
static Map maskEvent(String name, String value, String unit, String desc) {
    String key = name?.startsWith("variable:") ? name.substring(9) : name
    String v = value, d = desc
    if (value && secretValue(key, value, true)) {
        v = PASSWORD_KEY.matcher(key).find() ? BULLETS : tokenMask(value)
        if (d) d = d.replace(value, v)
    }
    return [n: maskSecrets(name), v: maskSecrets(v), u: maskSecrets(unit), d: maskSecrets(d)]
}

private Set<Long> seenIds() {
    String key = app.id.toString()
    LinkedHashSet<Long> seen = SEEN.get(key)
    if (seen == null) {
        seen = new LinkedHashSet<Long>()
        // After a restart: what the newest files hold, so a list read doesn't store it again.
        ((state.streams ?: [:]) as Map).each { String s, Map st ->
            (((st.files ?: []) as List<Map>).takeRight(2)).each { Map f ->
                String text = readFile(fileName(s, f.n as int)) ?: ""
                text.eachLine { String l ->
                    Matcher m = (l =~ /"i":(\d+)/)
                    if (m.find()) seen.add(m.group(1) as Long)
                }
            }
        }
        SEEN.put(key, seen)
    }
    return seen
}

private String bufferKey(String s) { "${app.id}:${s}".toString() }

private ConcurrentLinkedQueue<Map> queue(String s) {
    String k = bufferKey(s)
    ConcurrentLinkedQueue<Map> q = BUFFER.get(k)
    if (q == null) {
        q = new ConcurrentLinkedQueue<Map>()
        BUFFER.put(k, q)
    }
    return q
}

private int buffered(String s) { queue(s).size() }

private void trimBuffer(ConcurrentLinkedQueue<Map> q) {
    int lost = 0
    while (q.size() > MAX_BUFFER) { q.poll(); lost++ }
    if (lost) {
        state.droppedRows = ((state.droppedRows ?: 0) as int) + lost
        logWarn "Dropped ${lost} rows that could not be written"
    }
}

// ── Files ─────────────────────────────────────────────────────────────

void flush() {
    LIST_PATHS.keySet().each { String s -> flushStream(s) }
}

private void flushStream(String s) {
    ConcurrentLinkedQueue<Map> q = queue(s)
    List<Map> rows = []
    for (Map r = q.poll(); r != null; r = q.poll()) rows << r
    if (!rows) return
    Map streams = state.streams as Map
    Map st = streams[s] as Map
    int cap = rowsCap()
    int i = 0
    while (i < rows.size()) {
        List<Map> files = st.files as List<Map>
        Map f = files ? files[-1] : null
        if (f == null || (f.rows as int) >= cap) {
            f = [n: st.next, rows: 0]
            st.next = (st.next as int) + 1
            files << f
        }
        int end = Math.min(rows.size(), i + cap - (f.rows as int))
        if (!appendRows(s, f, rows.subList(i, end))) {
            List<Map> newer = []
            for (Map r = q.poll(); r != null; r = q.poll()) newer << r
            q.addAll(rows.subList(i, rows.size()))
            q.addAll(newer)
            trimBuffer(q)
            break
        }
        i = end
    }
    expireFiles(s, st)
    state.streams = streams
}

private boolean appendRows(String s, Map f, List<Map> rows) {
    String name = fileName(s, f.n as int)
    String existing
    try {
        existing = readFile(name)
    } catch (Exception e) {
        // Never write over a file that couldn't be read.
        logWarn "Could not read ${name}: ${maskSecrets(e.message)}"
        return false
    }
    StringBuilder out = new StringBuilder(existing ?: "")
    if (out.length() && out.charAt(out.length() - 1) != ('\n' as char)) out.append('\n')
    rows.each { Map r -> out.append(JsonOutput.toJson(r)).append('\n') }
    if (!writeFile(name, out.toString())) return false
    List<Long> times = rows.collect { it.t as Long }
    f.first = ([f.first as Long] + times).findAll { it != null }.min()
    f.last = ([f.last as Long] + times).findAll { it != null }.max()
    f.rows = out.toString().count("\n")
    return true
}

private void expireFiles(String s, Map st) {
    List<Map> files = st.files as List<Map>
    int keep = keepFiles(s)
    int days = (settings.maxAgeDays ?: 0) as int
    long cutoff = now() - days * 86400000L
    while (files.size() > 1 && (files.size() > keep || (days > 0 && (files[0].last as Long) < cutoff))) {
        if (!deleteQuietly(fileName(s, files[0].n as int))) return
        files.remove(0)
    }
}

private int rowsCap() { (settings.rowsPerFile ?: DEFAULT_ROWS_PER_FILE) as int }

private int keepFiles(String s) {
    def v = s == "hub" ? settings.keepHubFiles : settings.keepLocationFiles
    return (v ?: DEFAULT_KEEP_FILES[s]) as int
}

private String fileName(String s, int n) {
    return "eventhistory-${state.fileToken}-${s}-${String.format('%06d', n)}.jsonl".toString()
}

// Null for a missing file: it throws up to 2.5.2 and on 2.5.3.105 and later, and returns null on 2.5.3.102.
private String readFile(String name) {
    for (int i = 1; ; i++) {
        try {
            byte[] bytes = downloadHubFile(name)
            return bytes == null ? null : new String(bytes, "UTF-8")
        } catch (NoSuchFileException ignored) {
            return null
        } catch (Exception e) {
            if (i >= 3) throw e
            pauseExecution(500)
        }
    }
}

private boolean writeFile(String name, String text) {
    for (int i = 1; i <= 3; i++) {
        try {
            uploadHubFile(name, text.getBytes("UTF-8"))
            return true
        } catch (Exception e) {
            logWarn "Could not write ${name} (${i} of 3): ${maskSecrets(e.message)}"
            if (i < 3) pauseExecution(500)
        }
    }
    return false
}

private boolean deleteQuietly(String name) {
    try {
        deleteHubFile(name)
        return true
    } catch (Exception e) {
        logWarn "Could not delete ${name}: ${maskSecrets(e.message)}"
        return false
    }
}

// ── Page and data ─────────────────────────────────────────────────────

Map serveUI() {
    String html = null
    try {
        html = readFile(UI_FILE)
    } catch (Exception e) {
        logWarn "Could not read ${UI_FILE}: ${maskSecrets(e.message)}"
    }
    if (html == null) {
        syncUI()
        return render(status: 200, contentType: "text/html", data: "<html><head><meta http-equiv='refresh' content='10'></head><body>" +
            "Downloading the page from GitHub. This page reloads every 10 seconds. If it never appears, upload " +
            "<code>${UI_FILE}</code> to the hub's File Manager and check the hub logs.</body></html>")
    }
    if (!html.contains("const CODE_VERSION = '${CODE_VERSION}'")) syncUI()
    return render(status: 200, contentType: "text/html",
        data: html.replace('${access_token}', state.accessToken as String).replace('${ui_theme}', hubTheme()))
}

// Dark mode came with 2.5.3. A hub rolled back from it still answers the setting it kept, and its UI is light.
static boolean hasDarkMode(String firmware) {
    List<Integer> v = (firmware ?: "0").tokenize(".").collect { it.isInteger() ? it as int : 0 }
    return (v[0] ?: 0) > 2 || ((v[0] ?: 0) == 2 && ((v[1] ?: 0) > 5 || ((v[1] ?: 0) == 5 && (v[2] ?: 0) >= 3)))
}

// "dark" or "light"; empty when the setting can't be read, and the page then falls back.
private String hubTheme() {
    if (!hasDarkMode(location.hubs[0]?.firmwareVersionString as String)) return "light"
    String theme = ""
    try {
        httpGet([uri: BASE_URL, path: "/hub2/getSetting/uiTheme", textParser: true, timeout: 3]) { resp ->
            theme = resp.data?.text?.trim() ?: ""
        }
    } catch (Exception e) {
        logDebug "No hub theme setting: ${maskSecrets(e.message)}"
    }
    return theme in ["dark", "light"] ? theme : ""
}

void syncUI() {
    if (state.uiSyncAt && now() - (state.uiSyncAt as long) < 3600000L) return
    state.uiSyncAt = now()
    asynchttpGet("syncUICallback", [uri: UI_URL, textParser: true, timeout: 30], [:])
}

void syncUICallback(resp, data) {
    if (resp.hasError() || resp.getStatus() != 200) {
        logWarn "Could not download the page: HTTP ${resp.getStatus()}"
        return
    }
    String html = resp.getData() as String
    // A partial body, or a commit where app and page haven't been changed together yet, is refused.
    if (!html?.contains("const CODE_VERSION = '${CODE_VERSION}'")) {
        logWarn "The page on GitHub does not match version ${CODE_VERSION}"
        return
    }
    if (writeFile(UI_FILE, html)) state.remove("uiSyncAt")
}

Map apiIndex() {
    int cap = rowsCap()
    Map streams = ((state.streams ?: [:]) as Map).collectEntries { String s, Map st ->
        List<Map> files = (st.files ?: []) as List<Map>
        [(s): files.withIndex().collect { Map f, int k ->
            [n: f.n, rows: f.rows, first: f.first, last: f.last, sealed: k < files.size() - 1 || (f.rows as int) >= cap]
        }]
    }
    return jsonOk([version: CODE_VERSION, hubName: location.name, timeZone: location.timeZone?.ID, key: state.fileToken,
                   streams: streams, droppedRows: state.droppedRows ?: 0,
                   lastPoll: state.lastPoll, lastPollError: state.lastPollError])
}

Map apiFile() {
    String s = params.stream as String
    Map st = LIST_PATHS.containsKey(s) ? ((state.streams as Map)[s] as Map) : null
    Integer n = (params.n as String)?.isInteger() ? (params.n as Integer) : null
    // Only a file in the index can be read, by number: a request can't name a path.
    if (st == null || n == null || !(st.files as List<Map>).any { (it.n as int) == n }) {
        return render(status: 404, contentType: "application/json", data: JsonOutput.toJson([error: "no such file"]))
    }
    try {
        return render(status: 200, contentType: "text/plain; charset=utf-8", data: readFile(fileName(s, n)) ?: "")
    } catch (Exception e) {
        logWarn "Could not read ${s} file ${n}: ${maskSecrets(e.message)}"
        return render(status: 500, contentType: "application/json", data: JsonOutput.toJson([error: "could not read the file"]))
    }
}

// Rows waiting for the next write, so the page shows what happened a minute ago.
Map apiTail() {
    return jsonOk(LIST_PATHS.keySet().collectEntries { String s -> [(s): new ArrayList(queue(s))] })
}

private Map jsonOk(Object body) {
    return render(status: 200, contentType: "application/json", data: JsonOutput.toJson(body))
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

// ── Name aliases ──────────────────────────────────────────────────────

// Names for "Hide private details", from the hub's device and app lists.
void dailySeedNames() { seedNames() }

void seedNames() {
    registerNames(location.modes*.name, location.modes*.name)
    registerNames([location.name, location.hub.name])
    asynchttpGet("seedDevicesCallback", [uri: BASE_URL, path: "/hub2/devicesList", timeout: 30])
    asynchttpGet("seedAppsCallback", [uri: BASE_URL, path: "/hub2/appsList", timeout: 30])
}

void seedDevicesCallback(resp, data) {
    if (resp.hasError() || resp.getStatus() != 200) {
        logWarn "Device list for name aliases unavailable: HTTP ${resp.getStatus()}"
        return
    }
    List names = [], skip = []
    collectDeviceNames((resp.getJson()?.devices ?: []) as List, names, skip)
    registerNames(skip + names, skip)
}

private void collectDeviceNames(List nodes, List names, List skip) {
    nodes.each { Map node ->
        Map d = (node.data ?: [:]) as Map
        [d.name, d.secondaryName].each { String n -> if (n) (n == d.type ? skip : names) << n }
        if (d.roomName) names << d.roomName
        collectDeviceNames((node.children ?: []) as List, names, skip)
    }
}

void seedAppsCallback(resp, data) {
    if (resp.hasError() || resp.getStatus() != 200) {
        logWarn "App list for name aliases unavailable: HTTP ${resp.getStatus()}"
        return
    }
    Map json = resp.getJson() as Map
    Map<String, String> typeNames = [:]
    ((json.systemAppTypes ?: []) + (json.userAppTypes ?: [])).each { Map t -> typeNames[t.id as String] = t.name as String }
    List names = [], skip = typeNames.values() as List
    collectAppNames((json.apps ?: []) as List, typeNames, names, skip)
    registerNames(skip + names, skip)
}

// An app label can carry HTML, such as a paused badge; the text is what events show.
private void collectAppNames(List nodes, Map<String, String> typeNames, List names, List skip) {
    nodes.each { Map node ->
        Map d = (node.data ?: [:]) as Map
        String n = (d.name as String ?: "").replaceAll(/<[^>]*>/, "").trim()
        if (n) (n == typeNames[d.appTypeId as String] ? skip : names) << n
        collectAppNames((node.children ?: []) as List, typeNames, names, skip)
    }
}

// A name in skip (a driver or app type name, a mode) maps to itself and is never replaced.
private void registerNames(Collection names, Collection skip = []) {
    Map<String, String> aliases = new LinkedHashMap<String, String>((state.nameAliases ?: [:]) as Map)
    Set<String> used = new HashSet<String>(aliases.values())
    names.each { n ->
        String k = n as String
        if (!k || aliases.containsKey(k) || aliases.size() >= MAX_NAMES) return
        if (k in skip || k in GENERIC_NAMES) {
            aliases[k] = k
        } else {
            String a = newAlias(k, state.aliasSalt as String, used)
            used << a
            aliases[k] = a
        }
    }
    state.nameAliases = aliases
}

private List<String> nameOrder(Map<String, String> aliases) {
    String key = "${app.id}:${aliases.size()}".toString()
    List<String> order = NAME_ORDER.get(key)
    if (order == null) {
        NAME_ORDER.keySet().removeIf { it.startsWith("${app.id}:") }
        order = orderNames(aliases)
        NAME_ORDER.put(key, order)
    }
    return order
}

// ============================================================================
// Masking
// ============================================================================
// Everything from here to "End of masking" is static, so tests/test-masking.groovy runs it off
// the hub. Shown forms follow ARCHITECTURE.md "Secrets: storage and display": a token or key
// keeps its last 4 characters after bullets; a password, PIN or Basic credential is bullets only.

@Field static final String DOT = "•"
@Field static final String BULLETS = "••••••••"

// A key whose value is a secret, and keys that only describe one (tokenExpiry, authUrl).
@Field static final Pattern SECRET_KEY = ~/(?i)token|passw|passphrase|secret|credential|cookie|bearer|auth(?!or(?!ization))|api_?key|private_?key|signature|[a-z_]key$|^(?:pin|pass|pwd|key|sig)$/
@Field static final Pattern META_KEY = ~/(?i)(?:error|errors|message|msg|status|url|uri|enabled|failed|expiry|expires|expires_?in|type|count|time|date|name|label|title|description|hint|scope|\.(?:py|sh|md|json|groovy|html|js|txt))$/
@Field static final Pattern PASSWORD_KEY = ~/(?i)passw|passphrase|pwd|^pin$|pin_?code|^pass$/
// Source code next to a secret-looking key, not a value: a call or a dotted property path.
@Field static final Pattern CODE_REF = ~/^[A-Za-z_][\w.]*\(|^[A-Za-z_]+(?:\.[A-Za-z_]+)+$/
@Field static final List<String> AUTH_SCHEMES = ["basic", "bearer", "digest", "token", "negotiate"]

@Field static final Pattern PEM_BEGIN = ~/-----BEGIN (?:[A-Z]+ )?PRIVATE KEY-----/
@Field static final Pattern PEM_END = ~/-----END (?:[A-Z]+ )?PRIVATE KEY-----/
@Field static final Pattern PEM_BODY = ~/[A-Za-z0-9+\/=]{16,}/
@Field static final Pattern AUTH_SCHEME = ~/(?i)\b(?:proxy-)?authorization["']?\s*[=:]\s*["']?(basic|bearer|digest|token|negotiate)\s+([^\s"',;)\]}<>]+)/
@Field static final Pattern AUTH_BARE = ~/(?i)\b(?:proxy-)?authorization["']?\s*[=:]\s*["']?(?!(?:basic|bearer|digest|token|negotiate)\b)([^\s&"',;)\]}<>]{4,})/
@Field static final Pattern BEARER = ~/(?i)\bbearer\s+([\w.~+\/=-]{8,})/
@Field static final Pattern URL_IN_TEXT = ~/(?i)\b[a-z][a-z0-9+.-]*:\/\/[^\s<>"'`)\]}]+/
@Field static final Pattern JWT = ~/\beyJ[\w-]{8,}\.[\w-]{8,}\.[\w-]{8,}/
// Hubitat's access tokens are UUIDs, so a bare UUID is treated as one.
@Field static final Pattern UUID_IN_TEXT = ~/(?<![\w•-])[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}(?![\w-])/
// [pattern, key group, value group, quoted]: "key": "value", key: 'value', key=value / key: value.
@Field static final List<List> KEY_VALUE = [
    [~/(["'])([A-Za-z_][A-Za-z0-9_.-]*)\1\s*:\s*(["'])((?:\\.|(?!\3).)*)\3/, 2, 4, true],
    [~/(?<![A-Za-z0-9_"'])([A-Za-z_][A-Za-z0-9_.-]*)\s*[:=]\s*(["'])((?:\\.|(?!\2).)*)\2/, 1, 3, true],
    [~/(?<![A-Za-z0-9_"'])([A-Za-z_][A-Za-z0-9_.-]*)(?:=|:[ \t]*)([^\s"',;&\]\)}<>]+)/, 1, 2, false]
]

// Identifiers, masked only for a filter that hides private details.
@Field static final Pattern EMAIL = ~/\b[\w.+-]+@[\w-]+(?:\.[\w-]+)+\b/
@Field static final Pattern MAC = ~/(?<![0-9A-Fa-f])(?<![0-9A-Fa-f]{2}[:-])(?:[0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}(?![0-9A-Fa-f]|[:-][0-9A-Fa-f])/
@Field static final Pattern MAC_KEYED = ~/(?i)\bmac(?:\s*address)?["']?\s*[=:]\s*["']?([0-9a-f]{12})\b/
@Field static final Pattern EUI64 = ~/(?<![0-9A-Fa-f])(?<![0-9A-Fa-f]{2}:)(?:[0-9A-Fa-f]{2}:){7}[0-9A-Fa-f]{2}(?![0-9A-Fa-f]|:[0-9A-Fa-f])/
@Field static final Pattern EUI64_PACKED = ~/(?<![0-9A-Za-z])[0-9A-F]{16}(?![0-9A-Za-z])/
@Field static final Pattern IPV6 = ~/(?<![\w:.])(?:[0-9a-fA-F]{0,4}:){2,7}[0-9a-fA-F]{0,4}(?![\w:.])/
@Field static final Pattern IPV4_HEX = ~/(?i)\bip["']?\s*[=:]\s*["']?([0-9a-f]{8})\b/
@Field static final Pattern IPV4 = ~/(?<![\d.])(?:25[0-5]|2[0-4]\d|1?\d?\d)(?:\.(?:25[0-5]|2[0-4]\d|1?\d?\d)){3}(?![\d.])/
@Field static final Pattern PHONE = ~/(?<![\w+])(?:\+?1[ .-]?)?\(?[2-9]\d{2}\)?[ .-]\d{3}[ .-]\d{4}(?!\d)/
@Field static final Pattern GEO_KEYED = ~/(?i)\b(?:lat(?:itude)?|lon(?:gitude)?|lng)["']?\s*[=:]\s*["']?(-?\d{1,3}\.\d{2,})/
@Field static final Pattern GEO_PAIR = ~/(?<![\d.])-?\d{1,2}\.\d{3,},\s*-?\d{1,3}\.\d{3,}(?![\d.])/
@Field static final Pattern SERIAL = ~/(?i)\b(?:serial(?:\s*(?:number|no))?|s\/n|sn)["']?\s*[=:]\s*["']?([\w-]{4,})/
@Field static final Pattern SSID = ~/(?i)\b(?:ssid|wifi\s*name|network\s*name)["']?\s*[=:]\s*["']?([^\s,;"'<>]{1,32})/
@Field static final Pattern ZWAVE_HOME_ID = ~/(?i)\bhome\s*id["']?\s*[=:]\s*["']?((?:0x)?[0-9a-f]{8})\b/
@Field static final Pattern MDNS_HOST = ~/(?i)\b(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+local\b/

// Name aliases, as Hub Inspector makes them.
@Field static final List<String> ADJECTIVES = ['amber','azure','brave','brisk','bronze','calm','clever','coral','crimson','dapper','eager','fierce','frosty','gentle','glossy','golden','grand','hardy','hidden','humble','icy','indigo','jolly','keen','lively','lucky','merry','misty','mossy','noble','olive','plucky','quiet','rapid','rosy','rustic','sandy','silent','silver','sleek','smooth','snowy','solid','spry','stormy','sturdy','sunny','swift','tame','tidy','vivid','warm','wild','witty','zesty']
@Field static final List<String> NOUNS = ['acorn','badger','beacon','beetle','bramble','canyon','cedar','cliff','comet','cricket','dahlia','delta','ember','falcon','ferret','finch','garnet','geode','glade','harbor','heron','iris','jade','kestrel','lagoon','lichen','lotus','marble','meadow','mesa','moss','newt','onyx','opal','otter','pebble','plume','quartz','quill','raven','reef','river','sage','shoal','silt','slate','spark','spruce','sumac','thistle','tundra','vale','vine','weave','willow','yarrow']

static String tokenMask(String v) {
    return v.length() >= 16 ? BULLETS + v.substring(v.length() - 4) : BULLETS
}

static boolean isSecretName(String key) {
    return key && SECRET_KEY.matcher(key).find() && !META_KEY.matcher(key).find()
}

// Each span is [start, end, replacement]; one that overlaps an earlier span is dropped.
static boolean addSpan(List<List> spans, int start, int end, String shown) {
    if (start >= end || spans.any { List sp -> start < (sp[1] as int) && (sp[0] as int) < end }) return false
    spans << [start, end, shown]
    return true
}

static String applySpans(String s, List<List> spans) {
    if (!spans) return s
    StringBuilder out = new StringBuilder()
    int i = 0
    spans.sort { List sp -> sp[0] as int }.each { List sp ->
        out.append(s, i, sp[0] as int).append(sp[2] as String)
        i = sp[1] as int
    }
    return out.append(s, i, s.length()).toString()
}

static boolean secretValue(String key, String val, boolean quoted) {
    if (!isSecretName(key) || val == null || val.contains(DOT)) return false
    if (val.length() < (PASSWORD_KEY.matcher(key).find() ? 3 : 6)) return false
    String low = val.trim().toLowerCase()
    if (low in ["true", "false", "null", "none"] || low in AUTH_SCHEMES || val.contains('${')) return false
    return quoted || !CODE_REF.matcher(val.replaceAll(/[`'.,:;!?]+$/, "")).find()
}

// Secrets in free text: private key bodies, Authorization credentials, URL credentials and
// webhook keys (maskUrl), JWTs, values of secret-named keys, and bare UUIDs.
static String maskSecrets(String s) {
    if (!s) return s
    List<List> spans = []
    Matcher m = PEM_BEGIN.matcher(s)
    while (m.find()) {
        Matcher end = PEM_END.matcher(s)
        Matcher body = PEM_BODY.matcher(s).region(m.end(), end.find(m.end()) ? end.start() : s.length())
        while (body.find()) addSpan(spans, body.start(), body.end(), BULLETS)
    }
    m = AUTH_SCHEME.matcher(s)
    while (m.find()) {
        boolean token = m.group(1).toLowerCase() in ["bearer", "token"]
        addSpan(spans, m.start(2), m.end(2), token ? tokenMask(m.group(2)) : BULLETS)
    }
    m = AUTH_BARE.matcher(s)
    while (m.find()) addSpan(spans, m.start(1), m.end(1), BULLETS)
    m = BEARER.matcher(s)
    while (m.find()) addSpan(spans, m.start(1), m.end(1), tokenMask(m.group(1)))
    m = URL_IN_TEXT.matcher(s)
    while (m.find()) {
        String url = m.group().replaceAll(/[.,;:!?]+$/, "")
        String shown = maskUrl(url)
        if (shown != url) addSpan(spans, m.start(), m.start() + url.length(), shown)
    }
    m = JWT.matcher(s)
    while (m.find()) addSpan(spans, m.start(), m.end(), tokenMask(m.group()))
    KEY_VALUE.each { List kv ->
        Pattern p = kv[0] as Pattern
        int kg = kv[1] as int, vg = kv[2] as int
        Matcher mm = p.matcher(s)
        int pos = 0
        // A match that isn't a secret is searched again from inside its value: a URL first
        // matches as key "https" and would step over its ?access_token=.
        while (pos < s.length() && mm.find(pos)) {
            String key = mm.group(kg), val = mm.group(vg)
            if (secretValue(key, val, kv[3] as boolean) &&
                    addSpan(spans, mm.start(vg), mm.end(vg), PASSWORD_KEY.matcher(key).find() ? BULLETS : tokenMask(val))) {
                pos = mm.end()
            } else {
                pos = Math.max(mm.start() + 1, mm.start(vg))
            }
        }
    }
    m = UUID_IN_TEXT.matcher(s)
    while (m.find()) addSpan(spans, m.start(), m.end(), tokenMask(m.group()))
    return applySpans(s, spans)
}

// The URL as pages and logs show it: the password in user:pass@ as bullets, the value of a
// query or fragment parameter whose name reads as a credential, and a path segment that looks
// generated (16+ letters, digits, - or _, mixing letters and digits), which is where Slack,
// IFTTT and Home Assistant webhooks keep their key. The rest stays readable.
static String maskUrl(String url) {
    String rest = url ?: ""
    String fragment = null
    int hash = rest.indexOf('#')
    if (hash >= 0) { fragment = rest.substring(hash + 1); rest = rest.substring(0, hash) }
    String query = null
    int q = rest.indexOf('?')
    if (q >= 0) { query = rest.substring(q + 1); rest = rest.substring(0, q) }
    Matcher m = rest =~ /^([A-Za-z][A-Za-z0-9+.-]*:\/\/)(?:([^\/@:]*):[^\/@]*@)?([^\/]*)(.*)$/
    if (!m.find()) return "(URL)"
    String path = m.group(4).split("/", -1).collect { String seg ->
        (seg.length() >= 16 && seg ==~ /[A-Za-z0-9_-]+/ && seg ==~ /.*[A-Za-z].*/ && seg ==~ /.*[0-9].*/) ? tokenMask(seg) : seg
    }.join("/")
    String out = m.group(1) + (m.group(2) != null ? m.group(2) + ":" + BULLETS + "@" : "") + m.group(3) + path
    if (query != null) out += "?" + maskParams(query)
    if (fragment != null) out += "#" + (fragment.contains("=") ? maskParams(fragment) : fragment)
    return out
}

static String maskParams(String params) {
    return params.split("&", -1).collect { String pair ->
        int eq = pair.indexOf('=')
        String name = eq >= 0 ? pair.substring(0, eq) : pair
        String value = eq >= 0 ? pair.substring(eq + 1) : ""
        if (!value || !isSecretName(name) || value.contains(DOT) || value ==~ /(?i)true|false|yes|no|on|off|\d{1,3}/) return pair
        return name + "=" + (PASSWORD_KEY.matcher(name).find() ? BULLETS : tokenMask(value))
    }.join("&")
}

// Identifiers, in the forms the log-scan masking uses: the part that still helps
// troubleshooting stays (an IP's first and last octet, a MAC's vendor prefix).
static String maskIdentifiers(String s) {
    if (!s) return s
    List<List> spans = []
    Closure<Void> scan = { Pattern p, int g, Closure<String> shown ->
        Matcher m = p.matcher(s)
        while (m.find()) {
            int a = m.start(g), b = m.end(g)
            if ((a > 0 && s.charAt(a - 1) == DOT as char) || (b < s.length() && s.charAt(b) == DOT as char)) continue
            String r = shown(m.group(g))
            if (r != null) addSpan(spans, a, b, r)
        }
    }
    scan(EMAIL, 0) { String v -> int at = v.indexOf('@'); v.substring(0, 1) + DOT * 3 + v.substring(at) }
    scan(MAC, 0) { String v -> maskHexId(v) }
    scan(MAC_KEYED, 1) { String v -> maskHexId(v) }
    scan(EUI64, 0) { String v -> maskHexId(v) }
    scan(EUI64_PACKED, 0) { String v -> maskHexId(v) }
    scan(IPV6, 0) { String v -> maskIpv6(v) }
    scan(IPV4_HEX, 1) { String v -> maskIpv4((0..3).collect { Integer.parseInt(v.substring(it * 2, it * 2 + 2), 16) }.join(".")) }
    scan(IPV4, 0) { String v -> maskIpv4(v) }
    scan(PHONE, 0) { String v -> String d = v.replaceAll(/\D/, ""); DOT * (d.length() - 2) + d.substring(d.length() - 2) }
    scan(GEO_KEYED, 1) { String v -> roundCoordinates(v) }
    scan(GEO_PAIR, 0) { String v -> roundCoordinates(v) }
    scan(SERIAL, 1) { String v -> DOT * 4 + v.substring(v.length() - (v.length() >= 12 ? 4 : 2)) }
    scan(SSID, 1) { String v -> BULLETS }
    scan(ZWAVE_HOME_ID, 1) { String v -> BULLETS }
    scan(MDNS_HOST, 0) { String v -> DOT * 4 + ".local" }
    return applySpans(s, spans)
}

// Vendor prefix and last byte: 34:E1:D1:••:••:18.
static String maskHexId(String v) {
    String h = v.replaceAll(/[^0-9A-Fa-f]/, "").toUpperCase()
    List<String> b = (0..<(h.length().intdiv(2))).collect { int i -> h.substring(i * 2, i * 2 + 2) }
    return (b.take(3) + [DOT * 2] * (b.size() - 4) + [b[-1]]).join(":")
}

// Private: first and last octet. Public: first octet. Loopback, link-local, multicast,
// documentation and reserved addresses, and firmware builds (2.5.2.129), stay as they are.
static String maskIpv4(String ip) {
    if (ip ==~ /2\.\d\.\d\.\d+/) return null
    List<Integer> o = ip.tokenize(".").collect { it as int }
    int a = o[0], b = o[1]
    if (a == 0 || a == 127 || a >= 224 || (a == 169 && b == 254) ||
            ip.startsWith("192.0.2.") || ip.startsWith("198.51.100.") || ip.startsWith("203.0.113.")) return null
    boolean priv = a == 10 || (a == 172 && b >= 16 && b <= 31) || (a == 192 && b == 168) || (a == 100 && b >= 64 && b <= 127)
    return priv ? "${a}.${DOT}.${DOT}.${o[3]}" : "${a}.${DOT}.${DOT}.${DOT}"
}

static String maskIpv6(String v) {
    String low = v.toLowerCase()
    if (!(low =~ /[0-9a-f]{3,}/) || low.indexOf("::") != low.lastIndexOf("::")) return null
    List<String> groups = low.split(":", -1) as List
    if (groups.any { it.length() > 4 } || (!low.contains("::") && groups.size() != 8)) return null
    String first = groups.find { it } ?: ""
    if (low.startsWith("fe8") || low.startsWith("fe9") || low.startsWith("fea") || low.startsWith("feb") ||
            low.startsWith("ff") || low.startsWith("2001:db8:") || low in ["::1", "::"]) return null
    if (low.startsWith("fc") || low.startsWith("fd")) return "${first}:${DOT}${DOT}${DOT}:${groups[-1]}"
    return "${first}:${DOT}${DOT}${DOT}"
}

// One decimal, about 10 km.
static String roundCoordinates(String v) {
    return v.replaceAll(/-?\d+\.\d+/) { String n -> String.valueOf(Math.round((n as double) * 10) / 10.0d) }
}

// Replaces each registered name, longest first, where it stands as a whole word.
static String aliasNames(String s, List<String> order, Map<String, String> aliases) {
    if (!s || !order) return s
    List<List> spans = []
    order.each { String n ->
        int i = s.indexOf(n)
        while (i >= 0) {
            int e = i + n.length()
            if ((i == 0 || !Character.isLetterOrDigit(s.charAt(i - 1))) &&
                    (e == s.length() || !Character.isLetterOrDigit(s.charAt(e)))) {
                addSpan(spans, i, e, aliases[n])
            }
            i = s.indexOf(n, i + 1)
        }
    }
    return applySpans(s, spans)
}

// Names that get replaced in text, longest first. A name mapped to itself is never replaced,
// nor is one under 3 characters.
static List<String> orderNames(Map<String, String> aliases) {
    return aliases.findAll { k, v -> k.length() >= 3 && k != v }.keySet().sort { a, b -> b.length() <=> a.length() ?: a <=> b }
}

// A stable adjective-noun alias, mixed with the install's salt so it can't be worked out from a
// guessed name. Same hash and collision walk as Hub Inspector.
static String newAlias(String name, String salt, Set<String> used) {
    String s = salt ? salt + "\u0000" + name : name
    int hv = (int) 2166136261L
    for (char c : s.toCharArray()) {
        hv ^= (int) c
        hv *= 16777619
    }
    long u = hv & 0xffffffffL
    int ai = (int) (u % ADJECTIVES.size()), ni = (int) ((u >>> 8) % NOUNS.size())
    String a = null
    for (int t = 0; t < ADJECTIVES.size() * NOUNS.size(); t++) {
        a = ADJECTIVES[ai] + "-" + NOUNS[ni]
        if (!used.contains(a)) return a
        ni++
        if (ni >= NOUNS.size()) { ni = 0; ai = (ai + 1) % ADJECTIVES.size() }
    }
    return a + "-" + used.size()
}

// End of masking

// ── Logging ───────────────────────────────────────────────────────────

//   ⬇️ Evt  ⬆️ Cmd  🔧 Cfg  🌐 Net  ⏰ Sched  🏷️ Ver  ·  ⚠️ Warn  🛑 Error  🔬 Trace
private String logp(String e) { "${e} ${app.getLabel()}: " }
void logVer  (String m) { log.warn  logp('🏷️') + m }
void logWarn (String m) { log.warn  logp('⚠️') + m }
void logError(String m) { log.error logp('🛑') + m }
void logInfo (String m) { if (txtEnable != false) log.info  "${app.getLabel()}: ${m}" }
void logDebug(String m) { if (debugEnable) log.debug "${app.getLabel()}: ${m}" }
void logEvt  (String m) { if (debugEnable) log.debug logp('⬇️') + m }
void logCmd  (String m) { if (txtEnable != false) log.info  logp('⬆️') + m }
void logCfg  (String m) { if (txtEnable != false) log.info  logp('🔧') + m }
void logNet  (String m) { if (debugEnable) log.debug logp('🌐') + m }
void logSched(String m) { if (debugEnable) log.debug logp('⏰') + m }
void logTrace(String m) { if (traceEnable) log.trace logp('🔬') + m }

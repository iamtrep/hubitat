// Copyright (c) 2025-2026 PJ
// SPDX-License-Identifier: MIT

/*
 Hydro-Québec Peak Period Manager

 Reads Hydro-Québec's peak events from its open data and publishes where the next one stands
 on a "Hydro peak" device: its phase (announced, anchor, gap, active, skipped, aborted), window
 and times, with switches for the phases rules and apps follow. skip() and abort() on the device
 make every consumer stand down for one event. The phase is recomputed from the event list on
 every tick, so a missed transition corrects itself within minutes.
 */
import groovy.transform.CompileStatic
import groovy.transform.Field
import groovy.json.JsonOutput
import java.text.SimpleDateFormat

@Field static final String APP_NAME = "Hydro-Québec Peak Period Manager"
@Field static final String CODE_VERSION = "0.4.0"

definition(
    name: APP_NAME,
    namespace: "iamtrep",
    author: "pj",
    description: "Publishes Hydro-Québec peak events and their phases on a device",
    menu: "Automations", // new in platform 2.5.0
    category: "Utility",
    iconUrl: "",
    iconX2Url: "",
    importUrl: "https://raw.githubusercontent.com/iamtrep/hubitat/refs/heads/main/apps/HydroPeakEvents/HydroPeakEvents.groovy"
)

// Only current and future events: the dataset keeps every past winter, and an ascending
// page of 100 from the start would lose the future ones once the offer has over 100 records.
@Field static final Map API_PARAMS = [
    uri: "https://donnees.hydroquebec.com",
    path: "/api/explore/v2.1/catalog/datasets/evenements-pointe/records",
    query: [
        order_by: "datedebut",
        limit: 100,
        timezone: "America/New_York",
        where: "datefin >= now(days=-1)"
    ],
    contentType: "application/json"
]

@Field static final String API_TEST_FILE_NAME = "testPeakPeriods.json"

@Field static final Map API_TEST_PARAMS = [
    uri: "http://127.0.0.1:8080",
    path: "/local/testPeakPeriods.json",
    contentType: "application/json"
]

// Failed fetches log at warn until this many in a row, then once at error, then at
// warn every OUTAGE_REMINDER_MS until a fetch succeeds. The last fetched events stay in effect.
@Field static final int FAILURE_ERROR_THRESHOLD = 3
@Field static final long OUTAGE_REMINDER_MS = 3_600_000L
@Field static final String DATE_FORMAT_ISO8601 = "yyyy-MM-dd'T'HH:mm:ssXXX"
@Field static final String DATE_FORMAT_HUBITAT = "yyyy-MM-dd'T'HH:mm:ss.SSSXX"
// What the Hub Variables page writes for a DateTime with no value; firmware 2.5.2.133+ rejects ""
@Field static final String DATETIME_UNSET = "9999-99-99T99:99:99.999-9999"
@Field static final String DATE_FORMAT_DISPLAY = 'yyyy-MM-dd HH:mm'

@Field static final Integer DEFAULT_UPDATE_INTERVAL_HOURS = 1
@Field static final Integer DEFAULT_PRE_EVENT_MINUTES = 60

@Field static final Map OFFERS = ["CPC-D": "Winter Credit Option (CPC-D)", "TPC-DPC": "Flex D (TPC-DPC)"]
@Field static final String DEFAULT_OFFER = "CPC-D"

@Field static final String DEVICE_DRIVER = "Hydro Peak"
@Field static final List<String> CHILD_SWITCHES = ["announced", "anchor", "active"]

// Pre-0.4 state machine keys, removed on the first run of 0.4
@Field static final List<String> OLD_STATE_KEYS = ["currentState", "eventStart", "eventEnd", "preEventStart",
                                                   "scheduledPeakStart", "scheduledPeakEnd", "scheduledPreEventStart"]

preferences {
    page(name: "mainPage")
}

Map mainPage() {
    dynamicPage(name: "mainPage", title: "", install: true, uninstall: true) {
        section("Current status") {
            paragraph getStatusText()
        }

        section("Offer") {
            input "offer", "enum", title: "Hydro-Québec offer", options: OFFERS, defaultValue: DEFAULT_OFFER, required: true
            paragraph "<small>The Winter Credit Option has an anchor window before each event: 01:00–04:00 for a morning event, 12:00–14:00 for an evening one. Flex D has none.</small>"
        }

        section("Hydro peak device") {
            paragraph "The app creates a <b>Hydro peak</b> device with the phase, and three switches: <i>announced</i> (on from the announcement to the start), <i>anchor</i> (on during the anchor window) and <i>active</i> (on during the event). A skipped or aborted event turns them all off. Use the device's <b>skip</b>, <b>unskip</b> and <b>abort</b> commands to stand down for one event."
        }

        section("Your own switches (optional)", hideable: true, hidden: !(settings.eventSwitch || settings.preEventSwitch || settings.upcomingEventSwitch)) {
            input "eventSwitch", "capability.switch", title: "Event switches (on during the event)", required: false, multiple: true, submitOnChange: true
            input "preEventSwitch", "capability.switch", title: "Pre-event switches (on for the minutes below before the event)", required: false, multiple: true, submitOnChange: true
            input "preEventMinutes", "number", title: "Minutes before the event to turn the pre-event switches on", defaultValue: DEFAULT_PRE_EVENT_MINUTES, required: true
            input "upcomingEventSwitch", "capability.switch", title: "Upcoming event switches (on while an event is coming, until the pre-event period)", required: false, multiple: true, submitOnChange: true
        }

        section("Event hub variables", hideable: true, hidden: !(settings.eventStartVariableName || settings.eventEndVariableName)) {
            paragraph "Hub variables set to the next event's start and end times"
            input "eventStartVariableName", "enum", title: "Hub variable for the next event's start (dateDebut)",
                options: getGlobalVarsByType("datetime").keySet().sort(), required: false, submitOnChange: true
            input "eventEndVariableName", "enum", title: "Hub variable for the next event's end (dateFin)",
                options: getGlobalVarsByType("datetime").keySet().sort(), required: false, submitOnChange: true
            paragraph "<small>Create DateTime hub variables in Settings → Hub Variables if none appear above</small>"
        }

        section("Update schedule") {
            input "updateInterval", "number", title: "Check for event schedule updates every X hours", defaultValue: DEFAULT_UPDATE_INTERVAL_HOURS, required: true, range: "1..24"
        }

        section("Debug", hideable: true, hidden: !settings.testMode) {
            input "testMode", "bool", title: "Test mode: read one made-up event from a local file", defaultValue: false, submitOnChange: true
            if (settings.testMode) {
                input "testStartMinutes", "number", title: "Test event starts this many minutes after Done (negative: already started)", defaultValue: 5, range: "-600..2880"
                input "testDurationMinutes", "number", title: "Test event length, minutes", defaultValue: 30, range: "1..600"
            }
            input name: "txtEnable", type: "bool", title: "Enable info logging", defaultValue: true
            input name: "enableDebug", type: "bool", title: "Enable debug logging", defaultValue: true, submitOnChange: true
            if (settings.enableDebug) {
                input name: "enableTrace", type: "bool", title: "Enable trace logging", defaultValue: false
            }
        }
        section("") {
            paragraph "<small>${APP_NAME} v${CODE_VERSION}</small>"
        }
    }
}

// ── Lifecycle ─────────────────────────────────────────────────────────

void installed() {
    logDebug("Installed with settings: ${settings}")
    initialize()
}

void updated() {
    logDebug("Updated with settings: ${settings}")
    unsubscribe()
    unschedule()
    initialize()
}

void uninstalled() {
    removeAllInUseGlobalVar()
    getChildDevices().each { deleteChildDevice(it.deviceNetworkId) }
}

void initialize() {
    checkVersion(false)
    logDebug("Initializing...")
    unschedule()
    migrateState()

    registerHubVariables()
    peakDevice()
    CHILD_SWITCHES.each { childSwitch(it) }

    if (settings.testMode) generateTestFile()

    subscribe(location, "systemStart", "systemStartHandler")

    // Random second and minute so installs don't all poll Hydro-Québec at the same instant
    Random rng = new Random()
    int interval = (settings.updateInterval ?: DEFAULT_UPDATE_INTERVAL_HOURS) as int
    schedule("${rng.nextInt(60)} ${rng.nextInt(60)} */${interval} * * ?", "fetchPeakPeriods")
    schedule("${rng.nextInt(60)} */5 * ? * *", "tick")
    logSched("Fetching every ${interval} hour(s), phase check every 5 minutes")

    evaluate()
    fetchPeakPeriods()

    if (settings.enableDebug || settings.enableTrace) runIn(1800, "logsOff")
}

private void checkVersion(boolean reinit = true) {
    if (state.version == CODE_VERSION) return
    logVer "New version: ${CODE_VERSION} (was: ${state.version})"
    state.version = CODE_VERSION
    if (reinit) runIn(1, "updated")
}

// 0.3.x kept the next event in a state machine; 0.4 keeps the event list and derives the rest.
private void migrateState() {
    List<String> old = OLD_STATE_KEYS.findAll { state.containsKey(it) }
    if (!old) return
    old.each { state.remove(it) }
    logCfg("Removed pre-0.4 state: ${old.join(', ')}")
}

void logsOff() {
    logWarn("Debug and trace logging disabled")
    app.updateSetting("enableDebug", [value: "false", type: "bool"])
    app.updateSetting("enableTrace", [value: "false", type: "bool"])
}

void systemStartHandler(evt) {
    checkVersion()
    evaluate()
    fetchPeakPeriods()
}

void tick() {
    checkVersion()
    evaluate()
}

// ── Fetch ─────────────────────────────────────────────────────────────

void fetchPeakPeriods() {
    checkVersion()
    logNet("Fetching peak period data...")
    Map params = settings.testMode ? API_TEST_PARAMS : apiParams()
    try {
        asynchttpGet("handlePeakPeriodsResponse", params)
    } catch (Exception e) {
        fetchFailed("cannot start fetch: ${e.message}")
    }
}

private Map apiParams() {
    Map q = new LinkedHashMap(API_PARAMS.query as Map)
    q.refine = "offre:\"${currentOffer()}\"".toString()
    return API_PARAMS + [query: q]
}

void handlePeakPeriodsResponse(hubitat.scheduling.AsyncResponse response, Map data) {
    checkVersion()
    try {
        if (response.hasError()) {
            fetchFailed("HTTP error: ${response.getErrorMessage()}")
            return
        }
        if (response.status != 200) {
            fetchFailed("HTTP ${response.status}")
            return
        }
        Map responseData = parseJson(response.data as String) as Map
        processPeakPeriods(responseData)
    } catch (Exception e) {
        // A payload that can't be parsed or processed is a failed poll like any other.
        fetchFailed("cannot process response: ${e.message}")
        return
    }
    fetchSucceeded()
}

private void processPeakPeriods(Map data) {
    List records = (data.results ?: []) as List
    Map parsed = parseEvents(records, now(), zone())
    int dated = parsed.dated as int
    int bad = parsed.bad as int
    // No parseable record means the feed's date format changed: fail the poll (counted in the
    // outage streak) rather than clear the stored events. A few bad records are skipped instead,
    // since one stale bad record would otherwise fail every poll for the season.
    if (dated > 0 && bad == dated) throw new Exception("no record has parseable dates (${bad} records)")
    noteBadRecords(bad)

    List events = parsed.events as List
    if (events != state.events) {
        logInfo("Events: ${events ? events.collect { describeEvent(it as Map) }.join('; ') : 'none'}")
    }
    state.events = events
    state.lastUpdate = now()
    evaluate()
}

// --- Outage handling ---

// Warn below FAILURE_ERROR_THRESHOLD, one error at it, then a warn every OUTAGE_REMINDER_MS.
private void fetchFailed(String msg) {
    long t = now()
    int n = ((state.fetchFailures ?: 0) as int) + 1
    state.fetchFailures = n
    if (n == 1) state.fetchFailingSince = t
    long since = state.fetchFailingSince as long
    if (n < FAILURE_ERROR_THRESHOLD) {
        logWarn("fetching peak periods failed (${n}/${FAILURE_ERROR_THRESHOLD}): ${msg}")
    } else if (n == FAILURE_ERROR_THRESHOLD) {
        logError("Hydro-Québec data unreachable since ${formatClock(since)}, keeping the last fetched events: ${msg}")
        state.lastOutageReminder = t
    } else if (t - ((state.lastOutageReminder ?: 0L) as long) >= OUTAGE_REMINDER_MS) {
        logWarn("Hydro-Québec data still unreachable after ${formatDuration(t - since)} (${n} fetches failed): ${msg}")
        state.lastOutageReminder = t
    } else {
        logDebug("fetching peak periods failed (${n}): ${msg}")
    }
}

private void fetchSucceeded() {
    int n = (state.fetchFailures ?: 0) as int
    if (n >= FAILURE_ERROR_THRESHOLD) {
        logInfo("Hydro-Québec data back after ${formatDuration(now() - (state.fetchFailingSince as long))} (${n} fetches failed)")
    } else if (n > 0) {
        logDebug("fetch recovered after ${n} failed")
    }
    state.remove("fetchFailures")
    state.remove("fetchFailingSince")
    state.remove("lastOutageReminder")
}

// Warn when the number of skipped records changes, not on every poll.
private void noteBadRecords(int bad) {
    int prev = (state.badRecordCount ?: 0) as int
    if (bad > 0 && bad != prev) logWarn("Skipping ${bad} peak period record(s) with unparseable dates")
    if (bad > 0) state.badRecordCount = bad
    else state.remove("badRecordCount")
}

// ── Phase ─────────────────────────────────────────────────────────────

// Derives everything from the stored events, the clock and the skip and abort marks, then
// publishes it. Called by every tick, fetch, command and phase boundary, so a dropped
// one-shot or a reboot through a boundary corrects itself at the next call.
void evaluate() {
    long t = now()
    List events = (state.events ?: []) as List
    List skipped = pruneMarks((state.skipped ?: []) as List, events, t)
    List aborted = pruneMarks((state.aborted ?: []) as List, events, t)
    if (skipped != state.skipped) state.skipped = skipped
    if (aborted != state.aborted) state.aborted = aborted

    Map ph = phaseAt(t, events, skipped, aborted, currentOffer(), zone())
    String prev = state.phase as String
    if (ph.phase != prev) {
        logInfo("Phase ${prev ?: 'unknown'} → ${ph.phase}${ph.event ? " (${describeEvent(ph.event as Map)})" : ''}")
        state.phase = ph.phase
    }

    publishDevice(ph)
    Map sw = switchesFor(ph.phase as String)
    CHILD_SWITCHES.each { String k -> setChildSwitch(k, sw[k] as boolean) }

    int preMins = settings.preEventSwitch ? ((settings.preEventMinutes ?: 0) as int) : 0
    Map legacy = legacySwitches(ph, t, preMins)
    setSwitches(settings.eventSwitch, legacy.event as boolean)
    setSwitches(settings.preEventSwitch, legacy.pre as boolean)
    setSwitches(settings.upcomingEventSwitch, legacy.upcoming as boolean)

    updateHubVariables(ph.event as Map)
    updateAppLabel(ph.phase as String)

    Long next = nextBoundary(ph, t, preMins)
    if (next != null) {
        runOnce(new Date(next + 1000L), "boundaryHandler")
        logSched("Next phase check at ${formatClock(next)}")
    } else {
        unschedule("boundaryHandler")
    }
}

void boundaryHandler() {
    checkVersion()
    evaluate()
}

// ── Device and switches ───────────────────────────────────────────────

String dni() { "hydropeak-${app.id}" }

String childDni(String k) { "hydropeak-${app.id}-${k}" }

def peakDevice() {
    def d = getChildDevice(dni())
    if (!d) {
        d = addChildDevice("iamtrep", DEVICE_DRIVER, dni(), [name: DEVICE_DRIVER, label: "Hydro peak", isComponent: true])
        logCfg("created ${d.displayName}")
    }
    return d
}

def childSwitch(String k) {
    def d = getChildDevice(childDni(k))
    if (!d) {
        d = addChildDevice("hubitat", "Generic Component Switch", childDni(k), [name: "Hydro peak ${k}", label: "Hydro peak ${k}", isComponent: true])
        logCfg("created ${d.displayName}")
    }
    return d
}

private void publishDevice(Map ph) {
    TimeZone tz = zone()
    Map e = ph.event as Map
    Map a = ph.anchor as Map
    peakDevice().updateStatus([
        phase      : ph.phase,
        window     : e ? (e.window as String).toUpperCase() : "none",
        eventDate  : e ? formatLocal(e.start as long, "yyyy-MM-dd", tz) : "none",
        eventStart : e ? formatLocal(e.start as long, DATE_FORMAT_ISO8601, tz) : "none",
        eventEnd   : e ? formatLocal(e.end as long, DATE_FORMAT_ISO8601, tz) : "none",
        anchorStart: a ? formatLocal(a.start as long, DATE_FORMAT_ISO8601, tz) : "none",
        anchorEnd  : a ? formatLocal(a.end as long, DATE_FORMAT_ISO8601, tz) : "none",
        offer      : currentOffer()
    ])
}

private void setChildSwitch(String k, boolean on) {
    def d = childSwitch(k)
    String want = on ? "on" : "off"
    if (d.currentValue("switch") != want) d.parse([[name: "switch", value: want, descriptionText: "${d.displayName} was turned ${want}"]])
}

private void setSwitches(def devs, boolean on) {
    String want = on ? "on" : "off"
    devs?.each { if (it.currentValue("switch") != want) { on ? it.on() : it.off() } }
}

// The child switches follow the phase: a command from a rule or the device page is put back.
void componentOn(def cd) { componentChanged(cd, "on") }
void componentOff(def cd) { componentChanged(cd, "off") }
void componentRefresh(def cd) { componentChanged(cd, null) }

private void componentChanged(def cd, String asked) {
    checkVersion()
    String k = CHILD_SWITCHES.find { childDni(it) == cd.deviceNetworkId }
    if (!k) return
    String want = (switchesFor((state.phase ?: "none") as String)[k] as boolean) ? "on" : "off"
    if (asked != null && asked != want) {
        logWarn("${cd.displayName} follows the event phase; use skip, unskip or abort on Hydro peak instead")
    }
    // The device passed to component callbacks refuses parse(); the one getChildDevice returns takes it.
    getChildDevice(cd.deviceNetworkId as String)?.parse([[name: "switch", value: want]])
}

// Called by the Hydro peak device: skip, unskip, abort, refresh.
Map peakCommand(Map req) {
    checkVersion()
    String cmd = req?.command as String
    if (cmd == "refresh") {
        fetchPeakPeriods()
        return [ok: true]
    }
    long t = now()
    List skipped = (state.skipped ?: []) as List
    List aborted = (state.aborted ?: []) as List
    Map ph = phaseAt(t, (state.events ?: []) as List, skipped, aborted, currentOffer(), zone())
    Map r = commandCheck(cmd, ph, t)
    if (!r.ok) {
        logWarn("${cmd}: ${r.error}")
        return r
    }
    long key = (ph.event as Map).start as long
    switch (cmd) {
        case "skip":   state.skipped = (skipped + [key]).unique(); break
        case "unskip": state.skipped = skipped.findAll { (it as long) != key }; break
        case "abort":  state.aborted = (aborted + [key]).unique(); break
    }
    logCmd("${cmd}: ${describeEvent(ph.event as Map)}")
    evaluate()
    return [ok: true]
}

// ── Hub variables ─────────────────────────────────────────────────────

private void updateHubVariables(Map e) {
    Long s = e ? e.start as Long : null
    Long f = e ? e.end as Long : null
    if (state.varStart == s && state.varEnd == f && state.varNames == varNames()) return
    try {
        setDateTimeVar(settings.eventStartVariableName as String, s)
        setDateTimeVar(settings.eventEndVariableName as String, f)
        state.varStart = s
        state.varEnd = f
        state.varNames = varNames()
    } catch (Exception ex) {
        logError("Error updating hub variables: ${ex.message}")
    }
}

private String varNames() { "${settings.eventStartVariableName ?: ''}|${settings.eventEndVariableName ?: ''}".toString() }

private void setDateTimeVar(String name, Long ms) {
    if (!name) return
    String value = ms != null ? formatDateForHubitat(new Date(ms)) : DATETIME_UNSET
    if (setGlobalVar(name, value)) {
        logDebug("Set hub variable '${name}' to: ${value}")
    } else {
        logWarn("Hub variable '${name}' rejected value: ${value}")
    }
}

@CompileStatic
private String formatDateForHubitat(Date date) {
    SimpleDateFormat sdf = new SimpleDateFormat(DATE_FORMAT_HUBITAT)
    sdf.setTimeZone(TimeZone.getTimeZone("America/Montreal"))
    return sdf.format(date)
}

private void registerHubVariables() {
    removeAllInUseGlobalVar()
    List<String> varsInUse = [settings.eventStartVariableName, settings.eventEndVariableName].findAll { it } as List<String>
    if (varsInUse) {
        addInUseGlobalVar(varsInUse)
        logDebug("Registered hub variables as in use: ${varsInUse}")
    }
    state.remove("varNames")   // rewrite the variables on the next evaluate
}

void renameVariable(String oldName, String newName) {
    logDebug("Hub variable renamed: ${oldName} -> ${newName}")
    if (settings.eventStartVariableName == oldName) {
        app.updateSetting("eventStartVariableName", newName)
        logCfg("Updated eventStartVariableName setting to: ${newName}")
    }
    if (settings.eventEndVariableName == oldName) {
        app.updateSetting("eventEndVariableName", newName)
        logCfg("Updated eventEndVariableName setting to: ${newName}")
    }
}

// ── Test mode ─────────────────────────────────────────────────────────

private void generateTestFile() {
    try {
        long start = now() + ((settings.testStartMinutes != null ? settings.testStartMinutes : 5) as long) * 60_000L
        long end = start + ((settings.testDurationMinutes ?: 30) as long) * 60_000L
        String json = testFileContents(start, end, currentOffer(), zone())
        uploadHubFile(API_TEST_FILE_NAME, json.getBytes("UTF-8"))
        logDebug("Generated test file: ${json}")
    } catch (Exception e) {
        logError("Error generating test file: ${e}")
    }
}

private static String testFileContents(long start, long end, String offer, TimeZone tz) {
    SimpleDateFormat f = new SimpleDateFormat(DATE_FORMAT_ISO8601)
    f.setTimeZone(tz)
    String plage = hourOf(start, tz) < 12 ? "AM" : "PM"
    return JsonOutput.toJson([total_count: 1, results: [[datedebut: f.format(new Date(start)), datefin: f.format(new Date(end)),
                                                         plagehoraire: plage, offre: offer]]])
}

// ── Page and label ────────────────────────────────────────────────────

private String currentOffer() { (settings.offer ?: DEFAULT_OFFER) as String }

private TimeZone zone() { location.timeZone ?: TimeZone.getDefault() }

@Field static final Map PHASE_TEXT = [
    none     : "No event announced",
    announced: "Event announced",
    anchor   : "Anchor window",
    gap      : "Between the anchor window and the event",
    active   : "Peak event in progress",
    skipped  : "Event skipped",
    aborted  : "Event aborted"
]

@Field static final Map PHASE_COLOR = [
    none     : "var(--hubitat-success-color, #356700)",
    announced: "var(--hubitat-link-color, #1a77c9)",
    anchor   : "var(--hubitat-warning-color, #8a5a00)",
    gap      : "var(--hubitat-warning-color, #8a5a00)",
    active   : "var(--hubitat-validation-color, #d32f2f)",
    skipped  : "var(--hubitat-link-color, #1a77c9)",
    aborted  : "var(--hubitat-link-color, #1a77c9)"
]

private String getStatusText() {
    TimeZone tz = zone()
    Map ph = phaseAt(now(), (state.events ?: []) as List, (state.skipped ?: []) as List, (state.aborted ?: []) as List, currentOffer(), tz)
    String p = ph.phase as String
    StringBuilder s = new StringBuilder("<b style='color:${PHASE_COLOR[p]}'>${PHASE_TEXT[p]}</b><br/>")
    Map e = ph.event as Map
    if (e) {
        s.append("Event: ${describeEvent(e)}<br/>")
        Map a = ph.anchor as Map
        if (a) s.append("Anchor window: ${formatLocal(a.start as long, 'HH:mm', tz)}–${formatLocal(a.end as long, 'HH:mm', tz)}<br/>")
    }
    List more = ((state.events ?: []) as List).findAll { Map x -> e == null || (x.start as long) > (e.start as long) }
    if (more) s.append("<small>Then: ${more.collect { describeEvent(it as Map) }.join('; ')}</small><br/>")
    if (state.lastUpdate) s.append("<br/><small>Event schedule last checked: ${formatClock(state.lastUpdate as long)}</small>")
    if (state.fetchFailures) s.append("<br/><small>Last ${state.fetchFailures} fetch(es) failed; the events above are from the last good one.</small>")
    return s.toString()
}

private void updateAppLabel(String phase) {
    String label = "${APP_NAME} <span style='color:${PHASE_COLOR[phase]}'>${PHASE_TEXT[phase]}</span>"
    if (app.getLabel() != label) app.updateLabel(label)
}

private String describeEvent(Map e) {
    TimeZone tz = zone()
    return "${formatLocal(e.start as long, 'yyyy-MM-dd HH:mm', tz)}–${formatLocal(e.end as long, 'HH:mm', tz)} ${(e.window as String).toUpperCase()}"
}

private String formatClock(long t) {
    return new Date(t).format(DATE_FORMAT_DISPLAY, location.timeZone)
}

private static String formatLocal(long t, String fmt, TimeZone tz) {
    SimpleDateFormat f = new SimpleDateFormat(fmt)
    f.setTimeZone(tz)
    return f.format(new Date(t))
}

private static String formatDuration(long ms) {
    long minutes = ms.intdiv(60000L)
    if (minutes < 60) return "${minutes} min"
    return "${minutes.intdiv(60L)} h ${minutes % 60} min"
}

// ── Core (pure) ───────────────────────────────────────────────────────
// No hub calls below this line: tests/test_core.groovy runs this block as is.

// Feed records → current and future events, sorted by start, one per start time:
// [start: ms, end: ms, window: "am" | "pm"]. dated counts records with both dates, bad those that didn't parse.
static Map parseEvents(List records, long nowMs, TimeZone tz) {
    Map<Long, Map> byStart = new TreeMap<Long, Map>()
    int dated = 0
    int bad = 0
    for (Object o : records) {
        Map r = o as Map
        String deb = r?.datedebut as String
        String fin = r?.datefin as String
        if (!deb || !fin) continue
        dated++
        long s
        long e
        try {
            s = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX").parse(deb).time
            e = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX").parse(fin).time
        } catch (Exception ignored) {
            bad++
            continue
        }
        if (e <= nowMs || e <= s) continue
        String plage = (r.plagehoraire as String)?.toLowerCase()
        String w = plage in ["am", "pm"] ? plage : (hourOf(s, tz) < 12 ? "am" : "pm")
        byStart[s] = [start: s, end: e, window: w]
    }
    return [events: new ArrayList(byStart.values()), dated: dated, bad: bad]
}

static int hourOf(long t, TimeZone tz) {
    Calendar c = Calendar.getInstance(tz)
    c.setTimeInMillis(t)
    return c.get(Calendar.HOUR_OF_DAY)
}

// The Winter Credit Option's anchor window, on the event's day: 01:00–04:00 for a morning event,
// 12:00–14:00 for an evening one. Other offers have none.
static Map anchorOf(Map e, String offer, TimeZone tz) {
    if (offer != "CPC-D" || e == null) return null
    boolean am = e.window == "am"
    return [start: atHour(e.start as long, am ? 1 : 12, tz), end: atHour(e.start as long, am ? 4 : 14, tz)]
}

static long atHour(long day, int hour, TimeZone tz) {
    Calendar c = Calendar.getInstance(tz)
    c.setTimeInMillis(day)
    c.set(Calendar.HOUR_OF_DAY, hour)
    c.set(Calendar.MINUTE, 0)
    c.set(Calendar.SECOND, 0)
    c.set(Calendar.MILLISECOND, 0)
    return c.timeInMillis
}

// The phase of the first event that hasn't ended. next is when the phase can next change
// without a fetch or a command (null when nothing is coming).
static Map phaseAt(long now, List events, List skipped, List aborted, String offer, TimeZone tz) {
    Map e = (events ?: []).find { Map x -> (x.end as long) > now } as Map
    if (e == null) return [phase: "none", event: null, anchor: null, next: null]
    long s = e.start as long
    long end = e.end as long
    Map a = anchorOf(e, offer, tz)
    boolean isSkipped = (skipped ?: []).any { (it as long) == s }
    boolean isAborted = (aborted ?: []).any { (it as long) == s }
    String p
    Long next
    if (now >= s) {
        p = isAborted ? "aborted" : (isSkipped ? "skipped" : "active")
        next = end
    } else if (isSkipped) {
        p = "skipped"
        next = end
    } else if (a != null && now >= (a.start as long) && now < (a.end as long)) {
        p = "anchor"
        next = a.end as long
    } else if (a != null && now >= (a.end as long)) {
        p = "gap"
        next = s
    } else {
        p = "announced"
        next = (a != null && (a.start as long) < s) ? (a.start as long) : s
    }
    return [phase: p, event: e, anchor: a, next: next]
}

static Map switchesFor(String phase) {
    return [announced: phase in ["announced", "anchor", "gap"], anchor: phase == "anchor", active: phase == "active"]
}

// The user's own switches, as before 0.4: upcoming while an event is coming, pre-event for
// preMins before it (instead of upcoming), event during it. Skipped and aborted events turn all off.
static Map legacySwitches(Map ph, long now, int preMins) {
    String p = ph.phase as String
    if (p == "active") return [upcoming: false, pre: false, event: true]
    if (!(p in ["announced", "anchor", "gap"])) return [upcoming: false, pre: false, event: false]
    long s = (ph.event as Map).start as long
    boolean pre = preMins > 0 && now >= s - preMins * 60_000L
    return [upcoming: !pre, pre: pre, event: false]
}

// The earliest time the published state can change: the phase's next boundary, or the
// pre-event switches' start when that comes first.
static Long nextBoundary(Map ph, long now, int preMins) {
    Long next = ph.next as Long
    if (preMins > 0 && ph.phase in ["announced", "anchor", "gap"]) {
        long pre = ((ph.event as Map).start as long) - preMins * 60_000L
        if (pre > now && (next == null || pre < next)) next = pre
    }
    return next
}

// Marks for events that have ended, or that the feed no longer lists, are dropped.
static List pruneMarks(List marks, List events, long now) {
    return (marks ?: []).findAll { m -> (events ?: []).any { Map e -> (e.start as long) == (m as long) && (e.end as long) > now } }
}

static Map commandCheck(String cmd, Map ph, long now) {
    String p = ph.phase as String
    switch (cmd) {
        case "skip":
            return p in ["announced", "anchor", "gap"] ? [ok: true] : [ok: false, error: "skip works before an event starts (phase is ${p})".toString()]
        case "unskip":
            if (p != "skipped") return [ok: false, error: "no skipped event (phase is ${p})".toString()]
            return now < ((ph.event as Map).start as long) ? [ok: true] : [ok: false, error: "the skipped event has started; it stays skipped"]
        case "abort":
            return p == "active" ? [ok: true] : [ok: false, error: "abort works during an event (phase is ${p})".toString()]
        default:
            return [ok: false, error: "unknown command ${cmd}".toString()]
    }
}
// ── End core ──────────────────────────────────────────────────────────

// ── Logging (app) ─────────────────────────────────────────────────────
//   ⬇️ Evt  ⬆️ Cmd  🔧 Cfg  🌐 Net  ⏰ Sched  🏷️ Ver  ·  ⚠️ Warn  🛑 Error  🔬 Trace
private String logp(String e) { "${e} ${app.getLabel()}: " }

void logEvt  (String m) { if (settings.enableDebug) log.debug logp('⬇️') + m }
void logCmd  (String m) { if (txtEnable != false) log.info  logp('⬆️') + m }
void logCfg  (String m) { if (txtEnable != false) log.info  logp('🔧') + m }
void logNet  (String m) { if (settings.enableDebug) log.debug logp('🌐') + m }
void logSched(String m) { if (settings.enableDebug) log.debug logp('⏰') + m }
void logVer  (String m) { log.warn  logp('🏷️') + m }

void logWarn (String m) { log.warn  logp('⚠️') + m }
void logError(String m) { log.error logp('🛑') + m }
void logTrace(String m) { if (settings.enableTrace) log.trace logp('🔬') + m }
void logInfo (String m) { if (txtEnable != false) log.info  "${app.getLabel()}: ${m}" }
void logDebug(String m) { if (settings.enableDebug) log.debug "${app.getLabel()}: ${m}" }

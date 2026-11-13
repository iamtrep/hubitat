// Copyright (c) 2026 PJ
// SPDX-License-Identifier: MIT

/*
 Android TV Remote Tester: runs the Android TV Remote driver through a fixed set of tests against
 your box and writes a report of where the box differs from what the driver expects.
 */

definition(
    name: "Android TV Remote Tester",
    namespace: "iamtrep",
    author: "pj",
    description: "Tests the Android TV Remote driver against your box and writes a report to paste in the forum",
    category: "Utility",
    oauth: true,
    // Installed when first opened: an instance never saved with Done drops its runIn calls, and a run would hang.
    installOnOpen: true,
    importUrl: "https://raw.githubusercontent.com/iamtrep/hubitat/refs/heads/main/drivers/androidtv/tests/AndroidTVRemoteTester.groovy",
    iconUrl: "",
    iconX2Url: "",
    // One execution at a time: Abort or Done must not interleave with a step that is reading the log.
    singleThreaded: true
)

import groovy.transform.Field
import groovy.json.JsonOutput
import java.nio.file.NoSuchFileException
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.text.SimpleDateFormat

@Field static final String CODE_VERSION = "0.7.0"

@Field static final String BUSY = "a run is in progress"

@Field static final String DROP_RE = '^(remote connect returned|Socket \\(|No message from the TV|Connect failed)'

// The driver logs a gap between pings when it is over 7 s or under 3 s. Boxes ping every 7 to 9 s while
// off, and the driver's idle limit is 20 s, so only a gap over 12 s says anything about the box.
@Field static final long PING_GAP_MS = 12000L

@Field static final String YOUTUBE_APP = "com.google.android.youtube.tv"

// The pad turns trace logging on for the driver; it goes back after this many seconds without a press.
@Field static final int PAD_TRACE_S = 600
@Field static final int PAD_LOG_MAX = 50

// The page lives in File Manager; the GitHub copy is fetched when it is missing or for another version.
@Field static final String UI_FILE = "androidtv-tester-ui.html"
@Field static final String UI_URL = "https://raw.githubusercontent.com/iamtrep/hubitat/refs/heads/main/drivers/androidtv/tests/androidtv-tester-ui.html"

// Numbers follow the run order.
@Field static final Map TEST_NAMES = [
    0: "Connection", 1: "Session start", 2: "Pings", 3: "Power off/on", 4: "Volume", 5: "Launch by URL",
    6: "Launch by package", 7: "Recovery after a box error", 8: "HOME", 9: "Discovery", 10: "Text input",
    11: "Media keys", 12: "Long standby", 13: "Power cut", 14: "Wrong pairing code", 15: "Pairing"
]

// The device's attributes the run watches, to check what the driver made of the box's messages.
@Field static final List TV_ATTRIBUTES = ["switch", "currentApp", "networkStatus", "pairing", "volume", "mute"]

// What the driver assumes about a box, and what it must do in answer. A field outside its rule is a
// difference. Fields marked driver: true judge the driver (its events, its reconnects); the others
// judge the box, where a difference describes the model and is often harmless.
@Field static final Map EXPECT = [
    0: [connected: [eq: true]],
    1: [setupFirst: [eq: true], powerReported: [eq: true], appReported: [eq: true], startMs: [max: 5000],
        onlineEvent: [eq: true, driver: true], switchEvent: [eq: true, driver: true], appEvent: [eq: true, driver: true]],
    2: [firstPingMs: [between: [0, 7000]]],
    3: [offReportMs: [max: 3000], onReportMs: [max: 3000], sessionKept: [eq: true], pingGaps: [eq: 0],
        offEvent: [eq: true, driver: true], onEvent: [eq: true, driver: true], volumeEventOk: [eq: true, driver: true]],
    4: [volumeReported: [eq: true], volumeEventMatches: [eq: true, driver: true]],
    5: [accepted: [eq: true], app: [eq: "com.google.android.youtube.tv"], appEventMatches: [eq: true, driver: true]],
    // Since a Google Play update in August 2026, boxes refuse the market:// link a package name is sent as.
    6: [accepted: [eq: false], appEventMatches: [eq: true, driver: true]],
    7: [reconnectMs: [max: 5000, driver: true], nextCommandOk: [eq: true, driver: true], onlineEvent: [eq: true, driver: true]],
    8: [appChanged: [eq: true], appEventMatches: [eq: true, driver: true]],
    // The driver finds the box by its mDNS announcement; a box that stops announcing while off delays the driver's IP repair until it wakes.
    9: [announced: [eq: true], deviceTvFound: [ne: false], announcedOff: [eq: true]],
    // The driver echoes the counters of the box's last message 21, so it assumes the box sends one for a focused field.
    10: [textSent: [eq: true, driver: true], accepted: [eq: true], editReported: [eq: true]],
    // The protocol has no playback-state message, so the driver expects nothing back from a media key. The keys come in
    // another order when the person has a video playing (pause first), so they are compared as a set.
    11: [keysSent: [sameKeys: "86,126,127", driver: true], accepted: [eq: true], sessionKept: [eq: true], replies: [none: true]],
    12: [drops: [eq: 0], pingGaps: [eq: 0], onWorked: [eq: true], onEvent: [eq: true, driver: true]],
    13: [noticeMs: [max: 50000, driver: true], backMs: [max: 180000], stillPaired: [eq: true],
         offlineEvent: [eq: true, driver: true], onlineEvent: [eq: true, driver: true]],
    14: [hexCodeOffered: [ne: false], status: [eq: 402], oldPairingWorks: [eq: true], resumedOnOwn: [eq: true, driver: true], pairingAfter: [eq: "paired", driver: true]],
    15: [certRead: [eq: true], pairingReply: [eq: true], hexCodeOffered: [ne: false], codeShown: [eq: true], wrongCodeStatus: [eq: 402],
         pairingAfter: [eq: "unpaired", driver: true]]
]

// Applies to every test: the box sent a message type the driver doesn't act on.
@Field static final Map EXPECT_ALL = [otherMessages: [none: true]]


preferences {
    page(name: "mainPage")
}

mappings {
    path("/ui.html") { action: [GET: "apiUi"] }
    path("/run")     { action: [GET: "apiRun"] }
    path("/status")  { action: [GET: "apiStatus"] }
    path("/report")  { action: [GET: "apiReport"] }
    path("/abort")   { action: [GET: "apiAbort"] }
    path("/answer")  { action: [GET: "apiAnswer"] }
    path("/key")     { action: [GET: "apiKey"] }
    path("/padlog/clear") { action: [GET: "apiPadLogClear"] }
}

Map mainPage() {
    checkOAuth()
    checkStall()
    Map run = state.run as Map
    boolean running = run?.status == "running"
    // The page reloads itself while a run is in progress, so the summary follows it.
    dynamicPage(name: "mainPage", title: "Android TV Remote Tester", install: true, uninstall: true,
                refreshInterval: running ? 5 : 0) {
        // Locked while a run is in progress: the run and its cleanup act on the devices it started with.
        section("Devices") {
            if (running) {
                paragraph "Testing <b>${escapeHtml(tv?.displayName ?: '?')}</b>" + (plug ? ", plug <b>${escapeHtml(plug.displayName)}</b>" : "") +
                          ". The devices can be changed once the run ends."
            } else {
                input "tv", "device.AndroidTVRemote", title: "Android TV Remote device", required: true, submitOnChange: true
                input "plug", "capability.switch", title: "Smart plug feeding the box (optional, for the power-cut test)", required: false
            }
        }
        section("Run") {
            paragraph "The tests change what is on the TV: they turn the box off and on, launch YouTube, press HOME and open its search screen with the text \"hubitat\", " +
                      "and send play, pause and stop, which act on whatever app plays media. A run takes about 3 minutes; the power cut adds about 3, " +
                      "the wrong code about 1, and the standby its minutes. The tester page asks you a few questions about what the TV shows."
            if (tv && tv.currentValue("pairing") != "paired") {
                paragraph "The device isn't paired, so Run only checks how far pairing gets: it opens a pairing dialog on the TV and answers it with a wrong code. Takes about 1 minute."
            }
            if (state.accessToken) {
                href name: "openTester", title: "Open tester", style: "external", required: false,
                     url: "${getFullLocalApiServerUrl()}/ui.html?access_token=${state.accessToken}",
                     description: "Opens the tester page, where you start the run and answer its questions"
            } else {
                paragraph rawHtml: true, message("warn", "OAuth is being turned on for this app. Reopen this page in a few seconds.")
            }
            if (run?.tests) paragraph rawHtml: true, summaryHtml(run)
        }
        if (state.report) {
            section("Last report") {
                paragraph "<textarea readonly rows='30' style='width:100%;font-family:monospace'>${escapeHtml(state.report as String)}</textarea>"
            }
        }
        section("Logging") {
            input name: "txtEnable", type: "bool", title: "Enable info logging", defaultValue: true
            input name: "debugEnable", type: "bool", title: "Enable debug logging", defaultValue: false
        }
    }
}

void installed() { initialize() }

void uninstalled() {
    padTraceRestore()
    if ((state.run as Map)?.status == "running") finishRun("aborted", "the app was removed")
}

void updated() {
    boolean wasRunning = (state.run as Map)?.status == "running"
    if (wasRunning) finishRun("aborted", "settings saved during the run")
    padTraceRestore()      // unschedule() below drops its timer
    unsubscribe()
    unschedule()
    initialize()
    // unschedule() just cancelled finishRun's plug retries.
    if (wasRunning && (state.run as Map)?.ctx?.plugCut && plug) runIn(10, "checkPlug", [data: [n: 1]])
}

void initialize() {
    checkVersion(false)
    checkOAuth()
    subscribe(location, "systemStart", "onSystemStart")
    if (debugEnable) runIn(1800, "logsOff")
}

void logsOff() {
    logWarn "Debug logging turned off"
    app.updateSetting("debugEnable", [value: "false", type: "bool"])
}

boolean checkVersion(boolean reinit = true) {
    if (state.version == CODE_VERSION) return false
    logVer "Version ${CODE_VERSION} (was ${state.version})"
    state.version = CODE_VERSION
    if (reinit) runIn(1, "updated")
    return true
}

void onSystemStart(evt) {
    if ((state.run as Map)?.status == "running") finishRun("interrupted", "hub restart")
}

private String escapeHtml(String s) {
    s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("'", "&#39;")
}

private String message(String severity, String html) {
    "<div class='p-message p-message-${severity}' style='margin:0'><div class='p-message-wrapper' style='padding:.6rem 1rem'>" +
        "<span class='p-message-text'>${html}</span></div></div>"
}

private String summaryHtml(Map run) {
    List<Map> rows = testRows(run, TEST_NAMES)
    int box = rows.count { it.state == "box" } as int
    int drv = rows.count { it.state == "driver" } as int
    String when = run.startedAt ? new Date(run.startedAt as Long).format("MMM d HH:mm", location.timeZone) : ""
    switch (run.status) {
        case "running":
            String where = (run.idx as int) == 0 ? "checking the connection" : "test ${run.idx} of ${(run.tests as List).size() - 1}"
            return message("info", "Running since ${when}: ${where}.")
        case "done":
            if (drv) return message("error", "<b>Run of ${when}: the driver did not do what it should in ${drv} test${drv > 1 ? 's' : ''}.</b> " +
                                    "Please post the report in the forum thread.")
            if (box) return message("warn", "<b>Run of ${when}: your TV differs from what the driver assumes in ${box} test${box > 1 ? 's' : ''}.</b> " +
                                    "That is often harmless; the report tells us about your model.")
            return message("success", "<b>Run of ${when}: every test as expected.</b>")
        default:
            return message("error", "<b>Run of ${when} ${run.status}</b>: ${escapeHtml((run.reason ?: '') as String)}")
    }
}


// ── Run control ───────────────────────────────────────────────────────

String startRun(Map opts) {
    checkStall()
    if ((state.run as Map)?.status == "running") return BUSY
    if (!tv) return "select the Android TV Remote device"
    // driverVersion is set when the driver initializes and at every session start, so a current driver
    // that was updated without saving its preferences still passes once it has connected.
    if (tv.getDataValue("driverVersion") == null) return "the driver is too old, or has not connected since it was updated"
    if (!tv.getSetting("tvIP")) return "set up the device first: save its preferences so it finds the TV, or enter the TV's IP address"
    String pairing = tv.currentValue("pairing") as String
    if (pairing == "awaiting code") return "the TV is waiting for a pairing code; enter it, or wait 5 minutes for it to time out"
    String kind = (opts.deviceKind ?: "unknown") as String
    if (!(kind in ["tv", "box", "unknown"])) return "deviceKind must be tv, box or unknown"
    // An unpaired device can't run the session tests; the run then checks how far pairing gets.
    boolean pairingOnly = pairing != "paired"
    List<Integer> tests = pairingOnly ? [15] : (opts.tests ? (opts.tests as List<Integer>) : [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11])
    if (!opts.tests && !pairingOnly) {
        if (opts.standby) tests << 12
        if (opts.powerCut) tests << 13
        if (opts.wrongCode) tests << 14
    }
    // Whether anyone is at the TV to answer questions; without them no prompt opens.
    Map setup = [deviceKind: kind, attended: opts.attended == true]
    state.options = [standby: opts.standby == true, standbyMinutes: (opts.standbyMin ?: 2) as Integer,
                     powerCut: opts.powerCut == true, wrongCode: opts.wrongCode == true]
    state.setup = setup
    state.slowestReadMs = 0L
    state.readFailures = 0
    state.plugWarning = null
    state.report = null
    state.prompt = null
    state.lastTimeout = null
    // The pad may have turned trace on: the run restores what the person had before the pad, not the pad's own setting.
    boolean startTrace = runStartTrace(state.padTrace as Map, tv.getSetting("traceEnable"))
    unschedule("padTraceRestore")
    state.remove("padTrace")
    state.run = [status: "running", reason: null, tests: [0] + tests, idx: 0, step: 0, results: [], events: [], startedAt: now(),
                 standbyMin: (opts.standbyMin ?: 2) as Integer, pairingOnly: pairingOnly, setup: setup, prompts: [:],
                 needConnect: !pairingOnly && tv.currentValue("networkStatus") != "online",
                 ctx: [startSwitch: tv.currentValue("switch"), startTrace: startTrace,
                       startTxt: tv.getSetting("txtEnable") != false, plugCut: false, youtubeMissing: false]]
    tv.updateSetting("traceEnable", [value: "true", type: "bool"])
    tv.updateSetting("txtEnable", [value: "true", type: "bool"])
    // filterEvents: false delivers repeats too, such as the same app or power state after a reconnect.
    TV_ATTRIBUTES.each { subscribe(tv, it as String, "onTvEvent", [filterEvents: false]) }
    logCmd "Run started: tests ${tests.join(', ')}"
    next("runStep", 1, state.run as Map)
    return null
}

// Every run callback is scheduled here, so the run knows when its next step is due and a stall can be told from a wait.
private void next(String handler, int seconds, Map run) {
    run.nextDueMs = now() + seconds * 1000L
    state.run = run
    runIn(seconds, handler)
}

// Ends a run whose next step never came (the hub dropped the callback), so its cleanup still runs.
private void checkStall() {
    Map run = state.run as Map
    if (runStalled(run, now())) {
        logWarn "The run stopped making progress; ending it"
        finishRun("stalled", "its next step never ran")
    }
}

void finishRun(String status, String reason) {
    unschedule("runStep")
    unschedule("pollWait")
    unschedule("sleepDone")
    unschedule("pollPrompt")
    unschedule("pollHold")
    Map run = state.run as Map
    if (run?.status != "running") return       // a stale Abort after the run ended changes nothing
    state.prompt = null
    run.status = status
    run.reason = maskLine(reason as String)     // an error text can carry the box's IP
    state.run = run
    Map ctx = run.ctx as Map
    // Commands go through the hub's own request, so a plug or a box that doesn't answer never holds this app.
    if (ctx.plugCut && plug) {
        deviceCommand("on", [], plug.idAsLong)
        runIn(10, "checkPlug", [data: [n: 1]])
    }
    try {
        tv.updateSetting("traceEnable", [value: ctx.startTrace ? "true" : "false", type: "bool"])
        tv.updateSetting("txtEnable", [value: ctx.startTxt ? "true" : "false", type: "bool"])
        if (ctx.startSwitch == "off" && tv.currentValue("switch") == "on") deviceCommand("off")
    } catch (e) {
        logError "Cleanup failed: ${maskLine(e.message)}"
    }
    unsubscribe()
    subscribe(location, "systemStart", "onSystemStart")
    // An exception here would roll back this execution's state writes and leave the run "running".
    try {
        state.report = buildReport(state.run as Map)
    } catch (e) {
        logError "Report failed: ${e}"
        state.report = "Android TV Remote Tester report\nrun ${status}; the report could not be built: ${maskLine(e.message)}"
    }
    logCmd "Run ${status}${reason ? ': ' + reason : ''}"
}

void onTvEvent(evt) {
    Map run = state.run as Map
    if (run?.status != "running") return
    List ev = (run.events ?: []) as List
    if (ev.size() < 300) ev << [name: evt.name, value: evt.value, at: evt.date.time]
    run.events = ev
    state.run = run
}

// The driver's logsOff can turn trace off part-way through a run. Turn it back on and remember the
// test, whose differences may then come from missing lines.
private void keepTrace(Map run) {
    if (tv.getSetting("traceEnable") == true) return
    tv.updateSetting("traceEnable", [value: "true", type: "bool"])
    int test = run.tests[run.idx] as int
    List off = (run.traceOff ?: []) as List
    if (!(test in off)) off << test
    run.traceOff = off
    state.run = run
    logWarn "Trace logging was off during test ${test}; turned it back on"
}

void checkPlug(Map data) {
    if (plug.currentValue("switch") == "on") return
    int n = data.n as int
    if (n < 3) {
        deviceCommand("on", [], plug.idAsLong)
        runIn(10, "checkPlug", [data: [n: n + 1]])
        return
    }
    state.plugWarning = "the plug did not report on after 3 attempts; check the box's power"
    logError state.plugWarning as String
    state.report = buildReport(state.run as Map)
}

String buildReport(Map run) {
    Map first = ((run.results as List).find { it.test == 1 }?.observed ?: [:]) as Map
    String warning = state.plugWarning ? state.plugWarning as String : (run.status != "done" ? "run ${run.status}: ${run.reason}" : null)
    Map setup = (run.setup ?: [:]) as Map
    Map header = [appVersion: CODE_VERSION, driverVersion: tv.getDataValue("driverVersion"),
                  firmware: location.hub.firmwareVersionString,
                  vendor: first.vendor ?: tv.getDataValue("manufacturer"), model: first.model ?: tv.getDataValue("model"),
                  androidVersion: first.androidVersion, remoteServiceVersion: first.remoteServiceVersion,
                  features: first.features, slowestReadMs: state.slowestReadMs, status: run.status, warning: warning,
                  deviceKind: setup.deviceKind ?: "unknown", attended: setup.attended == true]
    // Pad presses made since the previous report was built.
    long upTo = now()
    header.manualKeys = padSection(state.padLog as List, (state.padReportedMs ?: 0L) as long, upTo, location.timeZone)
    state.padReportedMs = upTo
    return formatReport(header, run.results as List<Map>)
}


// ── Runner ────────────────────────────────────────────────────────────

private int pollSeconds(Map s) { pollGap((s.timeout ?: 0) as int, state.lastReadMs as Long) }

private String tsAt(long ms) { new Date(ms).format("yyyy-MM-dd HH:mm:ss.SSS", location.timeZone) }

private String nowTs() { tsAt(now()) }

// True when the step is the one a callback was started for; a late reply for an earlier step is dropped.
private boolean sameStep(Map run, Map data) {
    return run?.status == "running" && (run.idx as int) == (data.idx as int) && (run.step as int) == (data.step as int)
}

void runStep() {
    try {
        Map run = state.run as Map
        if (run?.status != "running") return
        keepTrace(run)
        int test = run.tests[run.idx] as int
        if ((run.step as int) == 0) {
            run.testSince = nowTs()
            run.testStartMs = now()
            run.stepResults = [:]
            run.events = []      // finishTest reads only this test's events; the 300-event cap is for one test
            run.testState = null
            run.cur = null
            String skip = skipReason(test, run)
            if (skip) {
                (run.results as List) << [test: test, name: TEST_NAMES[test], skipped: skip]
                advance(run)
                return
            }
            if (!(test in [0, 13, 15]) && tv.currentValue("networkStatus") != "online") {
                if (run.offlineSince == null) run.offlineSince = now()
                if (now() - (run.offlineSince as Long) > 60000L) {
                    (run.results as List) << [test: test, name: TEST_NAMES[test], skipped: "no session"]
                    run.offlineSince = null
                    advance(run)
                    return
                }
                run.cur = [test: test, step: "online", label: "Waiting for the box to connect", startedAtMs: now(),
                           deadlineMs: (run.offlineSince as Long) + 60000L]
                next("runStep", 10, run)
                return
            }
            run.offlineSince = null
        }
        List<Map> steps = stepsFor(test, run)
        run.stepCount = steps.size()
        if ((run.step as int) >= steps.size()) { state.run = run; finishTest(); return }
        Map s = steps[run.step as int]
        run.stepSince = s.sinceTest ? run.testSince : nowTs()
        run.stepStartMs = now()
        run.cur = [test: test, step: s.name, label: s.label ?: s.name, startedAtMs: run.stepStartMs,
                   deadlineMs: stepDeadline(s, run.stepStartMs as long)]
        state.run = run
        if (s.skipIfOk && ((run.stepResults as Map)[s.skipIfOk] as Map)?.ok) { recordStep(s, true, 0L, "not needed"); return }
        if (s.needOk && !((run.stepResults as Map)[s.needOk] as Map)?.ok) { recordStep(s, false, 0L, "not run"); return }
        if (s.sleep) { next("sleepDone", s.sleep as int, run); return }
        if (s.onlyIf) {
            // The question is for a result the log decides; the read is async, like every other one.
            run.nextDueMs = now() + 35000L
            state.run = run
            readTvLinesAsync("onlyIfLines", run.testSince as String, [idx: run.idx, step: run.step])
            return
        }
        startStep(s, test, run)
    } catch (e) {
        logError "Step failed: ${maskLine(e.toString())}"
        finishRun("aborted", "error: ${e.message}")
    }
}

// The step's time limit as the page's countdown: its wait, sleep or hold; an await-prompt step ends with the open prompt.
private Long stepDeadline(Map s, long startMs) {
    if (s.awaitPrompt) return (state.prompt as Map)?.deadlineMs as Long
    long durS = ((s.timeout ?: s.sleep ?: s.hold ?: 0) as long)
    return durS ? startMs + durS * 1000L : null
}

private void skipStep(Map run) {
    run.step = (run.step as int) + 1
    next("runStep", 1, run)
}

void onlyIfLines(resp, Map data) {
    try {
        Map run = state.run as Map
        if (!sameStep(run, data)) return
        int test = run.tests[run.idx] as int
        Map s = stepsFor(test, run)[run.step as int]
        List<Map> lines = linesFrom(resp, data) ?: []
        if (!promptNeeded(s.onlyIf as String, run.stepResults as Map, lines)) { skipStep(run); return }
        // The question's clock starts now, not when the log read began.
        run.stepStartMs = now()
        run.cur = [test: test, step: s.name, label: s.label ?: s.name, startedAtMs: run.stepStartMs, deadlineMs: stepDeadline(s, run.stepStartMs as long)]
        state.run = run
        startStep(s, test, run)
    } catch (e) {
        logError "Step failed: ${maskLine(e.toString())}"
        finishRun("aborted", "error: ${e.message}")
    }
}

private void startStep(Map s, int test, Map run) {
    if (sendStep(s, run)) return        // the send waits for an async read and continues from its callback
    continueStep(s, test, run)
}

// What follows a step's command: its question, then the wait, hold or prompt that ends it.
private void continueStep(Map s, int test, Map run) {
    if (s.prompt) openPrompt(s, test, run)
    if (s.hold) {
        // The hold lasts its time, and until the question beside it is over.
        Map p = state.prompt as Map
        boolean open = p != null && promptOutcome(p, now()) == "open"
        if (open) { run.cur.deadlineMs = Math.max((run.cur.deadlineMs ?: 0L) as long, p.deadlineMs as long); state.run = run }
        next("pollHold", open ? 1 : (s.hold as int), run)
        return
    }
    if ((s.prompt && !s.wait) || s.awaitPrompt) {
        // Nobody to ask: the step ends at once as unanswered, with no deadline to wait out.
        if (!isAttended(run)) { recordStep(s, false, null, "no answer"); return }
        if (s.awaitPrompt) {
            run.cur.deadlineMs = (state.prompt as Map)?.deadlineMs
            state.run = run
        }
        next("pollPrompt", 1, run)
        return
    }
    if (!s.wait) { recordStep(s, true, null, null); return }
    next("pollWait", pollSeconds(s), run)
}

private void advance(Map run) {
    run.idx = (run.idx as int) + 1
    run.step = 0
    state.run = run
    if ((run.idx as int) >= (run.tests as List).size()) finishRun("done", null) else next("runStep", 2, run)
}

private String skipReason(int test, Map run) {
    if (test == 13 && !plug) return "no plug selected"
    if (test == 9 && !tv.hasCommand("findTvs")) return "the driver has no Find TVs command; update it"
    if (test == 10 && !tv.hasCommand("sendText")) return "the driver has no Send Text command; update it"
    if (test == 11 && !tv.hasCommand("pause")) return "the driver has no Pause command; update it"
    return null
}

// Sends the step's command. True when the send has to wait for a read of the device's state first.
private boolean sendStep(Map s, Map run) {
    if (!s.cmd) return false
    List args = (s.args ?: []) as List
    if (s.dev == "plug") {
        if (s.cmd == "off") { deviceCommand("off", [], plug.idAsLong); run.ctx.plugCut = true }
        else if (s.cmd == "on") { deviceCommand("on", [], plug.idAsLong); run.ctx.plugCut = false }
        state.run = run
        return false
    }
    switch (s.cmd) {
        case "on": case "off": case "connect": case "disconnect": case "volumeUp": case "volumeDown":
        case "play": case "pause": case "stop": case "startPairing": case "findTvs":
            deviceCommand(s.cmd as String); break
        case "connectIfIdle": deviceCommand("connect"); break
        case "sendKey": case "launchApp": case "sendText":
            deviceCommand(s.cmd as String, [[type: "STRING", value: args[0] as String]]); break
        case "wrongCode":
            run.nextDueMs = now() + 35000L
            state.run = run
            readStateAsync("wrongCodeState", ["cert", "tvCert"], [idx: run.idx, step: run.step])
            return true
        default: throw new IllegalArgumentException("unknown step command ${s.cmd}")
    }
    return false
}

// Runs a driver command in a hub request, not in this app's execution. A command waits for the box
// (a connect to one that doesn't answer blocks the driver about 31 s, a pairing start 4 to 5 s), and
// this single-threaded app would hold the page's /status and /answer calls all that time. A plug is
// addressed by its id; its driver may be a cloud one.
void deviceCommand(String method, List args = [], Long devId = null) {
    asynchttpPost("deviceCommandDone", [uri: "http://127.0.0.1:8080", path: "/device/runmethod",
                  requestContentType: "application/json", contentType: "application/json",
                  body: [id: devId ?: tv.idAsLong, method: method, args: args], timeout: 180],
                  [method: method])
}

void deviceCommandDone(resp, Map data) {
    // A timeout only means the command is still running on the hub.
    if (resp.hasError()) logDebug "Command ${data.method}: ${maskLine(resp.getErrorMessage())}"
}

// Reads only the named fields of the device's state, over loopback, in a callback; the private key is never copied.
void readStateAsync(String handler, List names, Map data) {
    asynchttpGet(handler, [uri: "http://127.0.0.1:8080", path: "/device/fullJson/${tv.id}", timeout: 20], data + [names: names])
}

// The named fields of the reply, or null when the read failed.
private Map stateFrom(resp, Map data) {
    try {
        if (resp.hasError()) return null
        def st = resp.json?.deviceState
        return pickFields(st instanceof Map ? st as Map : null, data.names as List)
    } catch (e) {
        logWarn "Could not read the device's state: ${maskLine(e.message)}"
        return null
    }
}

void wrongCodeState(resp, Map data) {
    try {
        Map run = state.run as Map
        if (!sameStep(run, data)) return
        int test = run.tests[run.idx] as int
        Map s = stepsFor(test, run)[run.step as int]
        Map st = stateFrom(resp, data)
        if (st == null || !st.cert || !st.tvCert) { recordStep(s, false, null, "certificates not readable"); return }
        deviceCommand("submitPairingCode", [[type: "STRING", value: wrongPairingCode(st.cert as String, st.tvCert as String)]])
        continueStep(s, test, run)
    } catch (e) {
        logError "Step failed: ${maskLine(e.toString())}"
        finishRun("aborted", "error: ${e.message}")
    }
}

void pollWait() {
    try {
        Map run = state.run as Map
        if (run?.status != "running") return
        keepTrace(run)
        // The log read is async: a sync read of 1-3 s holds this single-threaded app, and the page's
        // /status and /answer calls queue behind it.
        run.nextDueMs = now() + 35000L      // the read's own 30 s timeout, so a slow read isn't a stall
        state.run = run
        readTvLinesAsync("pollWaitLines", run.stepSince as String, [idx: run.idx, step: run.step])
    } catch (e) {
        logError "Wait failed: ${maskLine(e.toString())}"
        finishRun("aborted", "error: ${e.message}")
    }
}

void pollWaitLines(resp, Map data) {
    try {
        Map run = state.run as Map
        if (!sameStep(run, data)) return
        Map s = stepsFor(run.tests[run.idx] as int, run)[run.step as int]
        List<Map> lines = linesFrom(resp, data)
        if (lines == null) {
            if (((state.readFailures ?: 0) as int) >= 3) { finishRun("aborted", "could not read the hub logs"); return }
            next("pollWait", pollSeconds(s), run)
            return
        }
        Map hit = lines.find { (it.msg as String) =~ (s.wait as String) }
        if (hit) { recordStep(s, true, tsMs(hit.ts as String, location.timeZone) - (run.stepStartMs as Long), null); return }
        Map bad = s.fail ? lines.find { (it.msg as String) =~ (s.fail as String) } : null
        if (bad) { recordStep(s, false, tsMs(bad.ts as String, location.timeZone) - (run.stepStartMs as Long), "failed"); return }
        if (now() - (run.stepStartMs as Long) > ((s.timeout as Long) * 1000L)) { recordStep(s, false, null, "timed out"); return }
        next("pollWait", pollSeconds(s), run)
    } catch (e) {
        logError "Wait failed: ${maskLine(e.toString())}"
        finishRun("aborted", "error: ${e.message}")
    }
}

void sleepDone() {
    Map run = state.run as Map
    if (run?.status != "running") return
    recordStep(stepsFor(run.tests[run.idx] as int, run)[run.step as int], true, null, null)
}

void recordStep(Map s, boolean ok, Long ms, String note) {
    Map run = state.run as Map
    if (run?.status != "running") return
    // A concurrent prompt (screen dark, picture back) outlives its step; the hold or await step that follows ends it.
    if (((s.prompt && !s.concurrent) || s.endsPrompt) && state.prompt) closePrompt(s)
    run = state.run as Map
    (run.stepResults as Map)[s.name] = [ok: ok, ms: ms, at: run.stepStartMs, note: note]
    run.step = (run.step as int) + 1
    if (note == "timed out" && !s.quiet) setLastTimeout(run, s)
    state.run = run
    next("runStep", Math.max(1, (s.after ?: 1) as int), run)
}

private void setLastTimeout(Map run, Map s) {
    state.lastTimeout = [test: run.tests[run.idx] as int, step: s.name, label: s.label ?: s.name, atMs: now()]
}


// ── Prompts ───────────────────────────────────────────────────────────

private boolean isAttended(Map run) { (run.setup as Map)?.attended == true }

private void openPrompt(Map s, int test, Map run) {
    if (!isAttended(run)) {
        Map prompts = (run.prompts ?: [:]) as Map
        prompts[s.prompt] = [notAsked: true]
        run.prompts = prompts
        state.run = run
        return
    }
    int seq = ((state.promptSeq ?: 0) as int) + 1
    state.promptSeq = seq
    int promptS = (s.promptTimeout ?: s.timeout) as int
    state.prompt = newPrompt(s.prompt as String, "p${seq}".toString(), test, now(), promptS)
    logCmd "Question ${seq} for the person at the TV: ${(state.prompt as Map).text}"
}

// Ends the open prompt and keeps what the person answered; an unanswered one past its deadline is a timeout the page shows.
private void closePrompt(Map s) {
    Map p = state.prompt as Map
    if (p == null) return
    Map run = state.run as Map
    Map prompts = (run.prompts ?: [:]) as Map
    prompts[p.kind] = [choice: (p.answer as Map)?.choice, atMs: (p.answer as Map)?.atMs, askedAtMs: p.askedAtMs]
    run.prompts = prompts
    state.run = run
    if (promptOutcome(p, now()) == "expired") setLastTimeout(run, s)
    state.prompt = null
}

// A prompt-only step ends on the answer or the deadline; the page answers through /answer.
void pollPrompt() {
    try {
        Map run = state.run as Map
        if (run?.status != "running") return
        keepTrace(run)
        Map s = stepsFor(run.tests[run.idx] as int, run)[run.step as int]
        Map p = state.prompt as Map
        String outcome = p ? promptOutcome(p, now()) : "expired"
        if (outcome == "open") { next("pollPrompt", 1, run); return }
        boolean answered = outcome == "answered"
        recordStep(s, answered, answered ? (p.answer.atMs as Long) - (p.askedAtMs as Long) : null, answered ? null : "no answer")
    } catch (e) {
        logError "Prompt failed: ${maskLine(e.toString())}"
        finishRun("aborted", "error: ${e.message}")
    }
}

// A hold step keeps the box in its state for a set time, and past it until the question beside it is over.
void pollHold() {
    try {
        Map run = state.run as Map
        if (run?.status != "running") return
        keepTrace(run)
        Map s = stepsFor(run.tests[run.idx] as int, run)[run.step as int]
        Map p = state.prompt as Map
        boolean promptOver = p == null || promptOutcome(p, now()) != "open"
        long elapsed = now() - (run.stepStartMs as long)
        if (elapsed >= (s.hold as long) * 1000L && promptOver) { recordStep(s, true, elapsed, null); return }
        next("pollHold", 1, run)
    } catch (e) {
        logError "Hold failed: ${maskLine(e.toString())}"
        finishRun("aborted", "error: ${e.message}")
    }
}


// ── Log reads and the end of a test ───────────────────────────────────

void readTvLinesAsync(String handler, String since, Map data) {
    asynchttpGet(handler, [uri: "http://127.0.0.1:8080", path: "/logs/past/json", timeout: 30],
                 data + [since: since, sentAt: now()])
}

// The device's lines from an async log read, or null when the read failed.
List<Map> linesFrom(resp, Map data) {
    List raw = null
    try {
        if (!resp.hasError()) raw = resp.json as List
    } catch (e) { raw = null }
    if (raw == null) {
        state.readFailures = ((state.readFailures ?: 0) as int) + 1
        logWarn "Log read failed: ${resp.hasError() ? maskLine(resp.getErrorMessage()) : 'unreadable reply'}"
        return null
    }
    state.readFailures = 0
    long ms = now() - (data.sentAt as long)
    state.lastReadMs = ms
    if (ms > ((state.slowestReadMs ?: 0L) as Long)) state.slowestReadMs = ms
    return deviceLines(raw, tv.idAsLong, tv.displayName, data.since as String)
}

// The device state a test's verdict needs, read before its log.
private List<String> stateNeeds(int test) {
    switch (test) {
        // The driver acts on volume it wasn't asked for only once the box has answered a volume key.
        case 3: return ["volumeTrusted"]
        case 9: return ["tvId", "foundTvs"]
        default: return []
    }
}

void finishTest() {
    Map run = state.run as Map
    int test = run.tests[run.idx] as int
    run.nextDueMs = now() + 35000L
    state.run = run
    List<String> need = stateNeeds(test)
    if (need) readStateAsync("finishTestState", need, [idx: run.idx])
    else readTvLinesAsync("finishTestLines", run.testSince as String, [idx: run.idx])
}

void finishTestState(resp, Map data) {
    Map run = state.run as Map
    if (run?.status != "running" || (run.idx as int) != (data.idx as int)) return
    run.testState = stateFrom(resp, data) ?: [:]
    run.nextDueMs = now() + 35000L
    state.run = run
    readTvLinesAsync("finishTestLines", run.testSince as String, [idx: run.idx])
}

void finishTestLines(resp, Map data) {
    try {
        Map run = state.run as Map
        if (run?.status != "running" || (run.idx as int) != (data.idx as int)) return
        int test = run.tests[run.idx] as int
        Map steps = run.stepResults as Map
        List<Map> lines = linesFrom(resp, data) ?: []
        run = state.run as Map
        List<Map> events = ((run.events ?: []) as List<Map>).findAll { (it.at as Long) >= (run.testStartMs as Long) }
        if (test == 0) {
            if (steps.connect != null && !steps.connect.ok) {
                Map f = evalConnect(lines)
                List d = differences(f, EXPECT[0] as Map)
                (run.results as List) << ([test: 0, name: TEST_NAMES[0], observed: f, diffs: d, trace: traceLines(lines)] + splitDiffs(d, EXPECT[0] as Map))
                state.run = run
                finishRun("aborted", "the device did not connect")
                return
            }
        } else {
            Map r = evaluateTest(test, lines, steps, run, events)
            if (test in ((run.traceOff ?: []) as List)) r.note = "trace logging was off for part of this test, so lines may be missing"
            (run.results as List) << r
            if (test == 5 && r.skipped == "YouTube not installed") run.ctx.youtubeMissing = true
        }
        run.testState = null
        advance(run)
    } catch (e) {
        logError "Test failed: ${maskLine(e.toString())}"
        finishRun("aborted", "error: ${e.message}")
    }
}

Map evaluateTest(int test, List<Map> lines, Map steps, Map run, List<Map> events) {
    Map r = [test: test, name: TEST_NAMES[test]]
    if (run.ctx.youtubeMissing && test in [6, 7, 8]) { r.skipped = "YouTube not installed"; return r }
    TimeZone tz = location.timeZone
    Map st = (run.testState ?: [:]) as Map
    Map prompts = (run.prompts ?: [:]) as Map
    Map f
    switch (test) {
        case 1: f = evalSessionStart(lines, steps); break
        case 2: f = evalPings(lines, tz); break
        case 3:
            f = evalPower(lines, steps)
            f.volumeTrusted = st.volumeTrusted == true
            // Times run from the on() call.
            Object pic = pictureMs(prompts.picture as Map, (steps.on?.at ?: run.testStartMs) as Long)
            if (pic != null) f.pictureMs = pic
            break
        case 4: f = evalVolume(lines); break
        case 5: f = evalLaunch(lines, steps); break
        case 6: f = evalLaunch(lines, steps); break
        case 7: f = evalRecovery(lines, steps, tz); break
        case 8: f = evalHome(lines, steps); break
        case 9: f = evalDiscovery(lines, st.tvId as String, st.foundTvs instanceof Map ? st.foundTvs as Map : null, tv.getSetting("tvIP") as String); break
        case 10:
            f = evalText(lines)
            r.note = textNote(f, prompts)
            break
        case 11:
            f = evalMedia(lines)
            r.note = mediaNote(prompts)
            break
        case 12: f = evalStandby(lines, steps); break
        case 13: f = evalPowerCut(lines, steps, [pairing: tv.currentValue("pairing"), switch: tv.currentValue("switch")], tz); break
        case 14: f = evalWrongCode(lines, steps) + evalPairingOptions(lines); break
        case 15: f = evalPairing(lines) + evalPairingOptions(lines); break
        default: f = [:]
    }
    f.putAll(evalEvents(test, events, f))
    f.putAll(promptFields(test, prompts))
    String other = otherMessages(lines)
    if (other) f.otherMessages = other
    r.observed = f
    // Assumption (untested: no box without YouTube was available): the URL then opens the Play Store.
    if (test == 5 && f.app == "com.android.vending") { r.skipped = "YouTube not installed"; return r }
    if (test == 7 && f.boxError == null) { r.skipped = "the box accepted the invalid link"; return r }
    Map expect = (EXPECT[test] as Map) + EXPECT_ALL
    r.diffs = differences(f, expect)
    r.putAll(splitDiffs(r.diffs as List, expect))
    // Session start always: its messages are what a mock of the box replays. Text input and media keys
    // always: what the box sends around a text field or a media key is still being learned.
    if (r.diffs || test in [1, 10, 11]) r.trace = traceLines(lines)
    return r
}

List<Map> stepsFor(int test, Map run) {
    switch (test) {
        case 0:
            List<Map> pre = []
            if (run.pairingOnly) return pre
            // 40 s: a connect to an address that doesn't answer blocks about 31 s before the driver logs why.
            if (run.needConnect) pre << [name: "connect", label: "Waiting for the box to connect", cmd: "connect", wait: '^rx 40:', timeout: 40]
            if (run.ctx.startSwitch == "off") pre << [name: "powerOn", label: "Waiting for the box to report on", cmd: "on", wait: '^rx 40:\\{1:1\\}', timeout: 15, after: 3]
            return pre
        case 1: return [[name: "disconnect", label: "Closing the session", cmd: "disconnect", after: 3],
                        [name: "ready", label: "Waiting for the box to start a session", cmd: "connect", wait: '^rx 20:', timeout: 15, after: 2]]
        case 2: return [[name: "disconnect", label: "Closing the session", cmd: "disconnect", after: 3],
                        [name: "connect", label: "Waiting for the box to answer", cmd: "connect", wait: '^rx 40:', timeout: 15],
                        [name: "ping1", label: "Waiting for the first ping", wait: '^ping #1 ', timeout: 15, sinceTest: true]]
        // Off for at least 12 s, two ping intervals: a box that stops pinging while off shows as a long gap.
        // The person is asked whether the screen went dark during that hold, and whether the picture came back.
        case 3: return [[name: "off", label: "Waiting for the box to report off", cmd: "off", wait: '^rx 40:(\\{1:0\\}|"")', timeout: 15,
                         prompt: "dark", promptTimeout: 20, concurrent: true],
                        [name: "hold", label: "Keeping the box off", hold: 12, endsPrompt: true],
                        [name: "on", label: "Waiting for the box to report on", cmd: "on", wait: '^rx 40:\\{1:1\\}', timeout: 15,
                         prompt: "picture", promptTimeout: 60, concurrent: true],
                        [name: "picture", label: "Waiting for your answer", awaitPrompt: true, endsPrompt: true, after: 3]]
        case 4: return [[name: "up", label: "Pressing volume up", cmd: "volumeUp", after: 2],
                        [name: "down", label: "Pressing volume down", cmd: "volumeDown", after: 2],
                        [name: "mute1", label: "Pressing mute", cmd: "sendKey", args: ["VOLUME_MUTE"], after: 2],
                        [name: "mute2", label: "Pressing mute again", cmd: "sendKey", args: ["VOLUME_MUTE"], after: 3]]
        // HOME first: from inside YouTube, the launch changes nothing and the box looks different from what it is.
        case 5: return [[name: "home", label: "Pressing HOME", cmd: "sendKey", args: ["HOME"], wait: '^rx 20:', timeout: 15, after: 3, quiet: true],
                        [name: "launch", label: "Waiting for the box to open YouTube", cmd: "launchApp", args: ["https://www.youtube.com"], wait: '^rx 20:|^TV rejected', timeout: 20, after: 5],
                        [name: "app", label: "Waiting for your answer", prompt: "app", onlyIf: "noYoutube", timeout: 30]]
        case 6: return [[name: "home", label: "Pressing HOME", cmd: "sendKey", args: ["HOME"], wait: '^rx 20:', timeout: 15, after: 3, quiet: true],
                        [name: "launch", label: "Waiting for the box to open YouTube", cmd: "launchApp", args: ["com.google.android.youtube.tv"], wait: '^rx 20:|^TV rejected', timeout: 20, after: 5]]
        case 7: return [[name: "badLink", label: "Sending a link the box refuses", cmd: "launchApp", args: ["market://launch?id=invalid.tester.nosuchapp"], wait: '^TV rejected|^rx 20:', timeout: 15],
                        [name: "reconnect", label: "Waiting for the session to come back", wait: '^rx 40:', timeout: 20, sinceTest: true, after: 2],
                        [name: "home", label: "Pressing HOME", cmd: "sendKey", args: ["HOME"], wait: '^tx 10:\\{1:3 ', timeout: 10, after: 3]]
        case 8: return [[name: "launch", label: "Waiting for the box to open YouTube", cmd: "launchApp", args: ["https://www.youtube.com"], wait: '^rx 20:', timeout: 20, after: 5],
                        [name: "home", label: "Pressing HOME", cmd: "sendKey", args: ["HOME"], wait: '^rx 20:', timeout: 15, after: 2, quiet: true],
                        [name: "homeSeen", label: "Waiting for your answer", prompt: "home", onlyIf: "homeUnchanged", timeout: 30]]
        // A search takes up to 5 s when nothing answers. Off for 30 s before the second search, as a box may stop announcing once asleep.
        case 9: return [[name: "find", label: "Searching the network for the box", cmd: "findTvs", wait: '^Found |^No Android TV', timeout: 20, after: 2],
                        [name: "off", label: "Waiting for the box to report off", cmd: "off", wait: '^rx 40:(\\{1:0\\}|"")', timeout: 15],
                        [name: "standby", label: "Letting the box sleep", sleep: 30],
                        [name: "findOff", label: "Searching the network while the box is off", cmd: "findTvs", wait: '^Found |^No Android TV', timeout: 20, after: 2],
                        [name: "on", label: "Waiting for the box to report on", cmd: "on", wait: '^rx 40:\\{1:1\\}', timeout: 20, after: 3]]
        case 10: return textSteps(run)
        case 11: return mediaSteps(run)
        case 12: return [[name: "off", label: "Waiting for the box to report off", cmd: "off", wait: '^rx 40:(\\{1:0\\}|"")', timeout: 15],
                         [name: "standby", label: "Leaving the box in standby", sleep: ((run.standbyMin ?: 2) as int) * 60],
                         [name: "on", label: "Waiting for the box to report on", cmd: "on", wait: '^rx 40:\\{1:1\\}', timeout: 20, after: 3]]
        case 13: return [[name: "cut", label: "Turning the plug off", dev: "plug", cmd: "off"],
                         [name: "dark", label: "Keeping the power off", sleep: 30],
                         [name: "restore", label: "Turning the plug on", dev: "plug", cmd: "on"],
                         [name: "back", label: "Waiting for the box to boot and reconnect", wait: '^rx 40:', timeout: 600, after: 5]]
        case 14: return [[name: "pair", label: "Waiting for the pairing dialog", cmd: "startPairing", wait: '^Enter the code shown', timeout: 30, after: 2],
                         [name: "submit", label: "Sending a wrong code", cmd: "wrongCode", wait: '^Pairing failed with status|^Paired', timeout: 20],
                         [name: "resume", label: "Waiting for the session to resume", wait: '^rx 40:', timeout: 30, sinceTest: true],
                         [name: "reconnect", label: "Reconnecting", cmd: "connectIfIdle", wait: '^rx 40:', timeout: 20, skipIfOk: "resume", after: 2]]
        // A wrong code ends the pairing attempt without leaving the TV waiting; the settle covers the driver's 10 s pairing timeout.
        case 15: return [[name: "pair", label: "Waiting for the pairing dialog", cmd: "startPairing", wait: '^Enter the code shown',
                          fail: '^(Pairing failed|No pairing response|Socket \\(|Connect failed|The client key)|Exception', timeout: 45, after: 2],
                         [name: "submit", label: "Sending a wrong code", cmd: "wrongCode", needOk: "pair", wait: '^Pairing failed with status|^Paired', timeout: 20],
                         [name: "settle", label: "Letting the box settle", sleep: 12]]
        default:
            return []
    }
}


// Attended: the person opens a text field first, and the text goes into it with no HOME and SEARCH (on Google TV
// SEARCH opens a voice prompt). Otherwise the text follows SEARCH by 2 s with no log read between: the search
// screen closes itself 9-11 s after it opens. The text stays on screen 6 s, for whoever watches the TV.
// The path is chosen once the field answer is in; step 0 is the same on both.
List<Map> textSteps(Map run) {
    List<Map> fieldStep = [[name: "field", label: "Waiting for you to open a text field", prompt: "field", timeout: 90]]
    if (choseReady(run.prompts as Map, "field")) {
        return fieldStep + [[name: "text", label: "Sending the text", cmd: "sendText", args: ["hubitat"], after: 2],
                            [name: "textSeen", label: "Waiting for your answer", prompt: "text", timeout: 30],
                            [name: "back", label: "Pressing BACK", cmd: "sendKey", args: ["BACK"], after: 2],
                            [name: "home2", label: "Pressing HOME", cmd: "sendKey", args: ["HOME"], after: 2]]
    }
    return fieldStep + [[name: "home", label: "Pressing HOME", cmd: "sendKey", args: ["HOME"], after: 3],
                        [name: "search", label: "Pressing SEARCH", cmd: "sendKey", args: ["SEARCH"], after: 2],
                        [name: "text", label: "Sending the text", cmd: "sendText", args: ["hubitat"], after: 6],
                        [name: "back", label: "Pressing BACK", cmd: "sendKey", args: ["BACK"], after: 2],
                        [name: "home2", label: "Pressing HOME", cmd: "sendKey", args: ["HOME"], after: 2]]
}

// Ends on stop, so nothing the play key resumed keeps playing. Attended: the person starts a video first, and each
// key is followed by a question (pause, play, stop). Otherwise play, pause, stop.
List<Map> mediaSteps(Map run) {
    List<Map> playingStep = [[name: "playing", label: "Waiting for you to start a video", prompt: "playing", timeout: 90]]
    if (choseReady(run.prompts as Map, "playing")) {
        return playingStep + [[name: "pause", label: "Pressing pause", cmd: "pause", wait: '^tx 10:\\x7B1:127 ', timeout: 10, after: 2],
                              [name: "pauseSeen", label: "Waiting for your answer", prompt: "paused", timeout: 20],
                              [name: "play", label: "Pressing play", cmd: "play", wait: '^tx 10:\\x7B1:126 ', timeout: 10, after: 2],
                              [name: "playSeen", label: "Waiting for your answer", prompt: "resumed", timeout: 20],
                              [name: "stop", label: "Pressing stop", cmd: "stop", wait: '^tx 10:\\x7B1:86 ', timeout: 10, after: 2],
                              [name: "stopSeen", label: "Waiting for your answer", prompt: "stopped", timeout: 20]]
    }
    return playingStep + [[name: "play", label: "Pressing play", cmd: "play", wait: '^tx 10:\\x7B1:126 ', timeout: 10, after: 3],
                          [name: "pause", label: "Pressing pause", cmd: "pause", wait: '^tx 10:\\x7B1:127 ', timeout: 10, after: 3],
                          [name: "stop", label: "Pressing stop", cmd: "stop", wait: '^tx 10:\\x7B1:86 ', timeout: 10, after: 3]]
}

// ── Script endpoints ──────────────────────────────────────────────────

private def json(Map data, int status = 200) {
    render(status: status, contentType: "application/json", data: JsonOutput.toJson(data))
}

private Map failure(String text) { [ok: false, error: text] }

def apiUi() {
    String html = null
    try {
        html = readFile(UI_FILE)
    } catch (Exception e) {
        logWarn "Could not read ${UI_FILE}: ${maskLine(e.message)}"
    }
    if (html == null) {
        syncUI()
        return render(status: 200, contentType: "text/html", data: "<html><head><meta http-equiv='refresh' content='10'></head><body>" +
            "Downloading the page from GitHub. This page reloads every 10 seconds. If it never appears, upload " +
            "<code>${UI_FILE}</code> to the hub's File Manager and check the hub logs.</body></html>")
    }
    if (!html.contains("const CODE_VERSION = '${CODE_VERSION}'")) syncUI()
    return render(status: 200, contentType: "text/html", data: html)
}

def apiRun() {
    if (checkVersion(false)) updated()     // a new version re-initializes before it starts a run
    Map parsed = runOptions([tests: params.tests, attended: params.attended, deviceKind: params.deviceKind,
                             standby: params.standby, cut: params.cut, wrongcode: params.wrongcode], TEST_NAMES)
    if (!parsed.ok) return json(failure(parsed.error as String), 400)
    String err = startRun(parsed.opts as Map)
    if (err) return json(failure(err), err == BUSY ? 409 : 400)
    return json([ok: true, started: true])
}

def apiStatus() {
    checkVersion()
    checkStall()
    Map run = (state.run ?: [:]) as Map
    Map prompt = publicPrompt(state.prompt as Map, now())
    return json([appVersion: CODE_VERSION, nowMs: now(),
                 device: [name: tv?.displayName, switch: tv?.currentValue("switch"), networkStatus: tv?.currentValue("networkStatus"),
                          pairing: tv?.currentValue("pairing"), currentApp: tv?.currentValue("currentApp"), selected: tv != null, plug: plug != null],
                 setup: (state.setup ?: [deviceKind: "unknown", attended: true]),
                 options: (state.options ?: [standby: false, standbyMinutes: 2, powerCut: false, wrongCode: false]),
                 run: statusRun(run, TEST_NAMES), prompt: prompt, lastTimeout: state.lastTimeout, reportReady: state.report != null,
                 padLog: padLogView(state.padLog as List, 20)])
}

def apiAbort() {
    checkVersion()
    finishRun("aborted", "aborted by the user")
    return json([ok: true, status: (state.run as Map)?.status])
}

def apiAnswer() {
    Map res = answerPrompt(state.prompt as Map, params.id as String, params.choice as String, now())
    if (!res.ok) return json(failure(res.error as String))
    state.prompt = res.prompt
    return json([ok: true])
}

def apiReport() {
    checkVersion()
    Map run = (state.run ?: [:]) as Map
    Map profile = profileFields((run.results ?: []) as List<Map>)
    // results and reason are for tests/test-live-tv.sh
    return json([status: run.status, reason: run.reason, report: state.report, results: run.results, profile: profile ?: null])
}

// The remote pad: one key at a time, never during a run, which owns the box's session.
def apiKey() {
    checkStall()
    if ((state.run as Map)?.status == "running") return json(failure(BUSY))
    if (!tv) return json(failure("select the Android TV Remote device"))
    Map cmd = padCommand(params.name as String, params.key as String, params.text as String, params.link as String)
    if (!cmd.ok) return json(failure(cmd.error as String))
    padPressed(params.name as String, cmd)
    deviceCommand(cmd.method as String, cmd.args as List)
    return json([ok: true])
}

def apiPadLogClear() {
    state.padLog = []
    return json([ok: true])
}


// ── Pad log ───────────────────────────────────────────────────────────

// A pad press outside a run: trace logging goes on for the driver (remembering how it was), the press is
// recorded, and 4 s later the driver's lines since it are read and attached to the entry.
private void padPressed(String name, Map cmd) {
    long t = now()
    Map pt = (state.padTrace ?: [:]) as Map
    if (!pt.active) {
        pt = [active: true, prev: tv.getSetting("traceEnable") == true]
        tv.updateSetting("traceEnable", [value: "true", type: "bool"])
    }
    state.padTrace = pt
    runIn(PAD_TRACE_S, "padTraceRestore")      // re-armed by each press
    state.padLog = padLogAppend(state.padLog as List, [atMs: t, since: tsAt(t), button: name, key: cmd.label, lines: []], PAD_LOG_MAX)
    runIn(4, "padCollect", [data: [atMs: t], overwrite: false])
}

void padTraceRestore() {
    Map pt = state.padTrace as Map
    if (!pt?.active) return
    state.remove("padTrace")
    try {
        if (tv) tv.updateSetting("traceEnable", [value: pt.prev ? "true" : "false", type: "bool"])
    } catch (e) {
        logWarn "Could not restore trace logging: ${maskLine(e.message)}"
    }
}

void padCollect(Map data) {
    Map e = ((state.padLog ?: []) as List<Map>).find { (it.atMs as long) == (data.atMs as long) }
    if (e == null) return
    readTvLinesAsync("padLines", e.since as String, [atMs: data.atMs])
}

void padLines(resp, Map data) {
    try {
        List<Map> lines = linesFrom(resp, data)
        if (lines == null) return
        List<Map> log = (state.padLog ?: []) as List<Map>
        Map entry = log.find { (it.atMs as long) == (data.atMs as long) }
        if (entry == null) return
        // Up to the next press, or the 4 s window when that comes first.
        Map later = log.find { (it.atMs as long) > (entry.atMs as long) }
        String windowEnd = tsAt((entry.atMs as long) + 4000L)
        String until = later && (later.since as String) < windowEnd ? later.since as String : windowEnd
        entry.lines = padEntryLines(lines, until)
        state.padLog = log
    } catch (ex) {
        logWarn "Pad log read failed: ${maskLine(ex.message)}"
    }
}


// ── Page file ─────────────────────────────────────────────────────────

// Null for a missing file: it throws up to 2.5.2 and from 2.5.3.105, and returns null on 2.5.3.102.
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
            logWarn "Could not write ${name} (${i} of 3): ${maskLine(e.message)}"
            if (i < 3) pauseExecution(500)
        }
    }
    return false
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


// ── OAuth ─────────────────────────────────────────────────────────────
// Turning OAuth on is two loopback requests, each in a callback, so opening the app page never waits for them.

private boolean checkOAuth() {
    if (state.accessToken) return true
    try {
        createAccessToken()
        return (state.accessToken != null)
    } catch (e) {
        logDebug "OAuth not enabled yet, turning it on"
        oauthEnableStart()
        return false
    }
}

private void oauthEnableStart() {
    // One attempt a minute: the app page and every endpoint ask.
    if (state.oauthAt && now() - (state.oauthAt as long) < 60000L) return
    state.oauthAt = now()
    String typeId = app.getAppTypeId()?.toString()
    if (!typeId) { logError "Could not find the app type id"; return }
    asynchttpGet("oauthCodeDone", [uri: "http://127.0.0.1:8080", path: "/app/ajax/code", query: [id: typeId], timeout: 15], [typeId: typeId])
}

void oauthCodeDone(resp, Map data) {
    String ver = null
    try {
        if (!resp.hasError()) ver = resp.json?.version?.toString()
    } catch (e) { ver = null }
    if (!ver) { logError "Could not read the app code version"; return }
    asynchttpPost("oauthEnableDone", [uri: "http://127.0.0.1:8080", path: "/app/edit/update",
                  requestContentType: "application/x-www-form-urlencoded",
                  // asynchttpPost sends a Map body as JSON whatever the content type: the form goes encoded, as a String.
                  body: "id=${data.typeId}&version=${ver}&oauthEnabled=true&_action_update=Update".toString(), timeout: 20], data)
}

void oauthEnableDone(resp, Map data) {
    if (resp.hasError()) logDebug "OAuth update answered: ${maskLine(resp.getErrorMessage())}"
    try {
        createAccessToken()
        if (state.accessToken) state.remove("oauthAt")
    } catch (e) {
        logError "OAuth enabled but the token could not be made: ${maskLine(e.message)}"
    }
}


// ── Pure helpers (extracted and tested by test-tester-helpers.groovy) ─

// Message types the driver doesn't act on, such as 22 (ime_show_request) or 51 (adjust_volume_level).
// The driver traces every message but pings as "rx N:"; 1, 2, 3, 20, 21, 40 and 50 are the ones it handles.
String otherMessages(List<Map> lines) {
    Set<Integer> seen = new TreeSet<Integer>()
    for (Map l : lines) {
        def m = (l.msg as String) =~ /^rx (\d+):/
        if (!m.find()) continue
        Integer t = m.group(1) as Integer
        if (!(t in [1, 2, 3, 20, 21, 22, 23, 40, 50])) seen << t
    }
    return seen ? seen.join(",") : null
}

// One /logs/past/json line: "{ts}\t{LEVEL} \tdev|{id}|{name}|{message}". Null unless it is the given device's.
Map parseLogLine(String raw, Long devId, String label) {
    if (raw == null) return null
    String[] parts = raw.split("\t", 3)
    if (parts.length < 3 || parts[0].length() != 23) return null
    String[] src = parts[2].split("\\|", 4)
    if (src.length < 4 || src[0] != "dev" || src[1] != devId.toString()) return null
    String msg = src[3]
    String prefix = label + ": "
    int i = msg.indexOf(prefix)
    if (i >= 0) msg = msg.substring(i + prefix.length())
    return [ts: parts[0], level: parts[1].trim().toLowerCase(), msg: msg]
}

// The buffer is not in time order, so filter by timestamp and sort.
List<Map> deviceLines(List raw, Long devId, String label, String since) {
    List<Map> out = []
    for (Object r : raw) {
        Map m = parseLogLine(r as String, devId, label)
        if (m != null && (m.ts as String) >= since) out << m
    }
    return out.sort { Map a, Map b -> (a.ts as String) <=> (b.ts as String) }
}

Long tsMs(String ts, TimeZone tz) {
    SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS")
    f.setTimeZone(tz)
    return f.parse(ts).time
}

String maskLine(String s) {
    if (s == null) return null
    String out = s.replaceAll(/-----BEGIN [A-Z ]+-----.*?-----END [A-Z ]+-----/, "<pem>")
    out = out.replaceAll(/\b(?:\d{1,3}\.){3}\d{1,3}\b/, "<ip>")
    out = out.replaceAll(/(?:0x)?[0-9A-Fa-f]{33,}/, "<hex>")
    return out
}

// The driver's trace shows typed text (tx 21, rx 21, rx 22, "Text field:", and the field part of rx 20),
// which can be a password. Its quoted strings are replaced; the structure stays for diagnosis.
String maskTyped(String msg) {
    if (msg == null) return null
    if (msg.startsWith("Text field: ")) return 'Text field: "<text>"'
    if (msg ==~ /(?s)^(tx|rx) (21|22):.*/) return msg.replaceAll(/(?s)".*"/, '"<text>"')
    if (msg.startsWith("rx 20:")) {
        // \x7D 2:\x7B is "} 2:{", the status field after the app field (written so the test's brace matcher is not thrown)
        java.util.regex.Matcher m = msg =~ /\x7D 2:\x7B/
        if (m.find()) return msg.substring(0, m.start()) + msg.substring(m.start()).replaceAll(/(?s)".*"/, '"<text>"')
    }
    return msg
}

List<String> traceLines(List<Map> lines) {
    List<String> out = []
    for (Map l : lines) {
        String msg = l.msg as String
        if (msg.startsWith("chunk ")) continue
        out << "${(l.ts as String).substring(11)} ${maskLine(maskTyped(msg))}".toString()
        if (out.size() >= 40) break
    }
    return out
}

private String lastApp(List<Map> lines) {
    String app = null
    for (Map l : lines) {
        def m = (l.msg as String) =~ /^rx 20:\x7B1:\x7B12:"([^"]+)"/
        if (m.find()) app = m.group(1)
    }
    return app
}

Map evalSessionStart(List<Map> lines, Map steps) {
    List<Integer> order = []
    String cfg = null
    String startApp = null
    for (Map l : lines) {
        def m = (l.msg as String) =~ /^rx (\d+):/
        if (!m.find()) continue
        order << (m.group(1) as Integer)
        if (m.group(1) == "1" && cfg == null) cfg = l.msg as String
        if (m.group(1) == "20" && startApp == null) {
            def am = (l.msg as String) =~ /^rx 20:\x7B1:\x7B12:"([^"]+)"/
            if (am.find()) startApp = am.group(1)
        }
    }
    Map kv = [:]
    Long features = null
    if (cfg) {
        def fm = cfg =~ /^rx 1:\x7B1:(\d+)/
        if (fm.find()) features = fm.group(1) as Long
        int i = cfg.indexOf("2:\u007B")
        if (i >= 0) {
            def km = cfg.substring(i + 3) =~ /(\d+):"([^"]*)"/
            while (km.find()) { if (!kv.containsKey(km.group(1))) kv[km.group(1)] = km.group(2) }
        }
    }
    return [vendor: kv["2"], model: kv["1"], androidVersion: kv["4"], remoteService: kv["5"],
            remoteServiceVersion: kv["6"], features: features, firstMessages: order.take(6).join(","), startApp: startApp,
            setupFirst: order.size() >= 2 && order[0] == 1 && order[1] == 2,
            powerReported: order.contains(40), appReported: order.contains(20),
            startMs: steps.ready?.ok ? steps.ready.ms : null]
}

// First ping, timed from the session's power message. The driver also logs any gap over 7 s or under 3 s.
Map evalPings(List<Map> lines, TimeZone tz) {
    Long startAt = null
    Long first = null
    int gaps = 0
    for (Map l : lines) {
        String msg = l.msg as String
        if (startAt == null) {
            if (msg.startsWith("rx 40:")) startAt = tsMs(l.ts as String, tz)
            continue
        }
        def m = msg =~ /^ping #(\d+) val1=\d+ gap=(\d+) ms/
        if (!m.find()) continue
        if ((m.group(1) as Integer) == 1) {
            if (first == null) first = tsMs(l.ts as String, tz) - startAt
            continue
        }
        long g = m.group(2) as Long
        if (g > 7000L || g < 3000L) gaps++
    }
    return [firstPingMs: first, loggedGaps: gaps]
}

Map evalPower(List<Map> lines, Map steps) {
    Map vol = lines.findResult { volumeOf(it.msg as String) } as Map
    List<Long> gaps = unusualPingGaps(lines)
    return [offReportMs: steps.off?.ok ? steps.off.ms : null, onReportMs: steps.on?.ok ? steps.on.ms : null,
            sessionKept: !lines.any { (it.msg as String) =~ DROP_RE }, pingGaps: gaps.count { it > PING_GAP_MS } as Integer, longestPingGapMs: gaps ? gaps.max() : null,
            volumeDuringChange: vol != null, changeVolumeRange: vol?.range, changeVolumeLevel: vol?.level, playerModel: vol?.player]
}

Map evalVolume(List<Map> lines) {
    Map vol = lines.findResult { volumeOf(it.msg as String) } as Map
    return [volumeReported: vol != null, volumeRange: vol?.range, volumeLevel: vol?.level, playerModel: vol?.player]
}

// A volume message with a range, as the driver acts on it; null for anything else. Protobuf leaves out
// fields at their default, so a level of 0 has no 7: and 6: may follow the brace.
Map volumeOf(String msg) {
    if (!msg.startsWith("rx 50:")) return null
    def r = msg =~ /[\x7B ]6:(\d+)/
    if (!r.find() || (r.group(1) as Long) == 0L) return null
    def v = msg =~ /[\x7B ]7:(\d+)/
    def m = msg =~ /[\x7B ]8:(\d+)/
    def p = msg =~ /[\x7B ]3:"([^"]*)"/
    return [range: r.group(1) as Long, level: v.find() ? (v.group(1) as Long) : 0L, muted: m.find() && m.group(1) != "0",
            player: p.find() ? p.group(1) : null]
}

// Gaps the driver logged between pings: it logs only those over 7 s or under 3 s, after the first ping of a session.
// Tests 3 and 12 count only those over PING_GAP_MS; the longest of all stays in longestPingGapMs.
List<Long> unusualPingGaps(List<Map> lines) {
    List<Long> out = []
    for (Map l : lines) {
        def m = (l.msg as String) =~ /^ping #(\d+) val1=\d+ gap=(\d+) ms/
        if (m.find() && (m.group(1) as Integer) > 1) out << (m.group(2) as Long)
    }
    return out
}

Map evalLaunch(List<Map> lines, Map steps) {
    String code = null
    boolean rejected = false
    for (Map l : lines) {
        def m = (l.msg as String) =~ /^TV rejected .*\(error (\S+)\)/
        if (m.find()) { rejected = true; code = m.group(1); break }
    }
    return [accepted: !rejected, errorCode: code, app: lastApp(lines), launchMs: steps.launch?.ok ? steps.launch.ms : null]
}

// Test 10: the app the search key opened, whether the box reported a focused text field (message 20 with
// field 2, or message 21), and whether it refused the text.
Map evalText(List<Map> lines) {
    String searchApp = null, code = null
    boolean searched = false, sent = false, rejected = false, field = false, edit = false, editAfterText = false, echoed = false
    for (Map l : lines) {
        String msg = l.msg as String
        if (msg == "Key 84") searched = true
        else if (msg ==~ /^Text \(\d+ characters\)/) sent = true
        if (searched && !sent && searchApp == null) {
            def m = msg =~ /^rx 20:\x7B1:\x7B12:"([^"]+)"/
            if (m.find()) searchApp = m.group(1)
        }
        if (msg =~ /^rx 20:.*\x7D 2:\x7B/) field = true
        if (msg.startsWith("rx 21:")) { edit = true; if (sent) editAfterText = true }
        if (sent && msg.startsWith("rx 22:")) echoed = true
        if (sent && !rejected) {
            def m = msg =~ /^TV rejected .*\(error (\S+)\)/
            if (m.find()) { rejected = true; code = m.group(1) }
        }
    }
    return [searchApp: searchApp, fieldReported: field, editReported: edit, editAfterText: editAfterText,
            textSent: sent, accepted: !rejected, textEchoed: echoed, errorCode: code]
}

// Test 11: the media keys the driver sent, and the message types the box sent after the first one.
Map evalMedia(List<Map> lines) {
    List<String> keys = []
    Set<Integer> replies = new TreeSet<Integer>()
    String code = null
    for (Map l : lines) {
        String msg = l.msg as String
        def k = msg =~ /^tx 10:\x7B1:(126|127|86) /
        if (k.find()) { keys << k.group(1); continue }
        if (!keys) continue
        def r = msg =~ /^rx (\d+):/
        if (r.find()) replies << (r.group(1) as Integer)
        def e = msg =~ /^TV rejected .*\(error (\S+)\)/
        if (code == null && e.find()) code = e.group(1)
    }
    return [keysSent: keys.join(","), accepted: code == null, errorCode: code, replies: replies ? replies.join(",") : null,
            sessionKept: !lines.any { (it.msg as String) =~ DROP_RE }]
}

Map evalRecovery(List<Map> lines, Map steps, TimeZone tz) {
    Integer rejIdx = null
    String code = null
    for (int i = 0; i < lines.size(); i++) {
        def m = (lines[i].msg as String) =~ /^TV rejected .*\(error (\S+)\)/
        if (m.find()) { rejIdx = i; code = m.group(1); break }
    }
    if (rejIdx == null) return [boxError: null, reconnectMs: null, nextCommandOk: null]
    Long reconnect = null
    Integer homeIdx = null
    for (int i = rejIdx + 1; i < lines.size(); i++) {
        String msg = lines[i].msg as String
        if (reconnect == null && msg.startsWith("rx 40:")) reconnect = tsMs(lines[i].ts as String, tz) - tsMs(lines[rejIdx].ts as String, tz)
        if (homeIdx == null && msg.startsWith("tx 10:\u007B1:3 ")) homeIdx = i
    }
    boolean sendError = homeIdx != null && lines.drop(homeIdx).any { (it.msg as String).contains("send error") }
    return [boxError: code, reconnectMs: reconnect, nextCommandOk: (steps.home?.ok == true) && homeIdx != null && !sendError]
}

Map evalHome(List<Map> lines, Map steps) {
    return [homeApp: lastApp(lines), appChanged: steps.home?.ok == true]
}

Map evalStandby(List<Map> lines, Map steps) {
    List<Long> gaps = unusualPingGaps(lines)
    return [drops: lines.count { (it.msg as String) =~ DROP_RE } as Integer, pingGaps: gaps.count { it > PING_GAP_MS } as Integer, longestPingGapMs: gaps ? gaps.max() : null,
            onWorked: steps.on?.ok == true]
}

Map evalPowerCut(List<Map> lines, Map steps, Map attrs, TimeZone tz) {
    String by = "not noticed"
    Long notice = null
    for (Map l : lines) {
        String msg = l.msg as String
        if (msg.startsWith("Socket (")) by = "socket error"
        else if (msg.startsWith("No message from the TV")) by = "watchdog"
        else continue
        if (steps.cut?.at != null) notice = tsMs(l.ts as String, tz) - (steps.cut.at as Long)
        break
    }
    // The box came up between the last refused connect and the first that went through, both timed from the power's return;
    // backMs adds the driver's reconnect back-off.
    Long lastFailed = null
    Long firstOk = null
    Long restoredAt = steps.restore?.at as Long
    if (restoredAt != null) {
        for (Map l : lines) {
            String msg = l.msg as String
            long t = tsMs(l.ts as String, tz) - restoredAt
            if (t < 0) continue
            if (msg.startsWith("remote connect returned")) { firstOk = t; break }
            if (msg.startsWith("The TV refused the connection") || msg.startsWith("Connect failed")) lastFailed = t
        }
    }
    return [noticedBy: by, noticeMs: notice, backMs: steps.back?.ok ? steps.back.ms : null,
            lastFailedConnectMs: lastFailed, firstConnectMs: firstOk,
            stillPaired: attrs.pairing == "paired", switchAfterBoot: attrs.switch]
}

Map evalWrongCode(List<Map> lines, Map steps) {
    Long status = null
    for (Map l : lines) {
        def m = (l.msg as String) =~ /^Pairing failed with status (\d+)/
        if (m.find()) { status = m.group(1) as Long; break }
        if ((l.msg as String).startsWith("Paired")) { status = 200L; break }
    }
    boolean resumed = steps.resume?.ok == true
    return [status: status, oldPairingWorks: resumed || steps.reconnect?.ok == true, resumedOnOwn: resumed]
}

// How far pairing got: the TV's certificate read, the pairing session answered, the code shown, a wrong code refused.
Map evalPairing(List<Map> lines) {
    Long status = null
    String failure = null
    for (Map l : lines) {
        String msg = l.msg as String
        def m = msg =~ /^Pairing failed with status (\d+)/
        if (m.find()) { if (status == null) status = m.group(1) as Long; continue }
        if (failure == null && l.level in ["warn", "error"] && !socketCloseNoise(msg)) failure = maskLine(msg).take(200)
    }
    return [certRead: lines.any { (it.msg as String).startsWith("TV certificate:") },
            pairingReply: lines.any { (it.msg as String).startsWith("pair rx") },
            codeShown: lines.any { (it.msg as String).startsWith("Enter the code shown") },
            wrongCodeStatus: status, firstError: failure]
}

// The code encodings the TV offers in its options reply. The driver always answers hexadecimal, 6 symbols.
Map evalPairingOptions(List<Map> lines) {
    Map names = [1: "alphanumeric", 2: "numeric", 3: "hexadecimal", 4: "qrcode"]
    String body = null
    for (Map l : lines) {
        String msg = l.msg as String
        int i = msg.indexOf(" 20:\u007B")
        if (msg.startsWith("pair rx ") && i >= 0) { body = msg.substring(i + 5); break }
    }
    if (body == null) return [inputEncodings: null, outputEncodings: null, preferredRole: null, hexCodeOffered: null]
    Map<String, List<String>> enc = ["1": [], "2": []]
    def m = body =~ /(?:^| )([12]):\x7B(?:1:(\d+) )?2:(\d+)\x7D/
    while (m.find()) enc[m.group(1)] << "${names[(m.group(2) ?: '0') as Integer] ?: 'unknown'}/${m.group(3)}".toString()
    def r = body =~ /(?:^| )3:(\d+)\x7D?\z/
    return [inputEncodings: enc["1"].join(","), outputEncodings: enc["2"].join(","),
            preferredRole: r.find() ? ([1: "input", 2: "output"][r.group(1) as Integer] ?: r.group(1)) : null,
            hexCodeOffered: (enc["1"] + enc["2"]).contains("hexadecimal/6")]
}

// The hub logs this against the device whenever a TLS socket closes, as after a refused pairing code; it says nothing about the box.
boolean socketCloseNoise(String msg) {
    return msg.contains("InterruptedException (method socketStatus)")
}

// Why a paired device did not connect: the driver's first warning or error, masked.
Map evalConnect(List<Map> lines) {
    Map bad = lines.find { it.level in ["warn", "error"] && !socketCloseNoise(it.msg as String) }
    return [connected: lines.any { (it.msg as String).startsWith("rx 40:") },
            connectReturned: lines.any { (it.msg as String).startsWith("remote connect returned") },
            firstError: bad ? maskLine(bad.msg as String).take(200) : null]
}

// A Find TVs result from the driver's info line, or null for any other line.
Map searchResult(String msg) {
    if (msg?.startsWith("No Android TV")) return [count: 0, many: false, other: false]
    def many = msg =~ /^Found (\d+) TVs/
    if (many) return [count: many[0][1] as Integer, many: true, other: false]
    if (msg ==~ /^Found .+ at \S+.*/) return [count: 1, many: false, other: msg.contains("to use it")]
    return null
}

// Test 9: the first search runs with the box on, the second while it is off.
// With several TVs, ipFound says whether one listed is at the device's address, without naming it:
// true with deviceTvFound false means the TV there announces another id than the one the device holds.
Map evalDiscovery(List<Map> lines, String tvId, Map foundTvs, String tvIP = null) {
    List<Map> rs = lines.collect { searchResult(it.msg as String) }.findAll { it != null }
    Map on = rs ? rs[0] : null
    Map off = rs.size() > 1 ? rs[1] : null
    Boolean mine = null
    if (on?.count) mine = on.many ? (foundTvs != null && tvId ? foundTvs.containsKey(tvId) : null) : !on.other
    Boolean ipFound = on?.many && foundTvs != null && tvIP ? foundTvs.values().any { it.toString().endsWith("(${tvIP})") } : null
    return [announced: on == null ? null : on.count > 0, tvCount: on?.count, deviceTvFound: mine, ipFound: ipFound,
            announcedOff: off == null ? null : off.count > 0, idKnown: tvId != null]
}

boolean hasEvent(List<Map> events, String name, String value) {
    events.any { it.name == name && (value == null || it.value == value) }
}

String lastEvent(List<Map> events, String name) {
    List<Map> e = events.findAll { it.name == name }
    return e ? e[-1].value as String : null
}

// What the driver made of the box's messages: the events it sent during the test.
Map evalEvents(int test, List<Map> events, Map f) {
    switch (test) {
        case 1: return [onlineEvent: hasEvent(events, "networkStatus", "online"), switchEvent: hasEvent(events, "switch", null),
                        appEvent: hasEvent(events, "currentApp", null)]
        case 3:
            boolean vol = hasEvent(events, "volume", null)
            return [offEvent: hasEvent(events, "switch", "off"), onEvent: hasEvent(events, "switch", "on"), volumeEvent: vol,
                    volumeEventOk: vol == (f.volumeDuringChange == true && f.volumeTrusted == true)]
        case 4:
            boolean vol = hasEvent(events, "volume", null)
            return [volumeEvent: vol, volumeEventMatches: vol == (f.volumeReported == true)]
        case 5:
        case 6:
            String app = lastEvent(events, "currentApp")
            return [appEvent: app, appEventMatches: f.app == null || app == f.app]
        case 8:
            String app = lastEvent(events, "currentApp")
            return [appEvent: app, appEventMatches: f.homeApp == null || app == f.homeApp]
        case 7: return [onlineEvent: hasEvent(events, "networkStatus", "online")]
        case 12: return [onEvent: hasEvent(events, "switch", "on")]
        case 13: return [offlineEvent: hasEvent(events, "networkStatus", "offline"), onlineEvent: hasEvent(events, "networkStatus", "online")]
        case 14:
        case 15: return [pairingAfter: lastEvent(events, "pairing")]
        default: return [:]
    }
}

// Stalled: running, and the next callback is over a minute late. The minute covers a slow step (a log read,
// a connect that blocks 30 s) and a busy hub.
boolean runStalled(Map run, long nowMs) {
    return run?.status == "running" && run.nextDueMs != null && nowMs > (run.nextDueMs as Long) + 60000L
}

Map splitDiffs(List diffs, Map expect) {
    List drv = diffs.findAll { (expect[it] as Map)?.driver == true }
    return [driverDiffs: drv, boxDiffs: diffs - drv]
}

String verdictOf(Map r) {
    if (r.skipped) return "skipped"
    if (r.driverDiffs) return "driver"
    if (r.boxDiffs || r.diffs) return "box"
    return "ok"
}

String verdictText(Map r) {
    if (r.skipped) return "skipped: ${r.skipped}".toString()
    List<String> parts = []
    if (r.driverDiffs) parts << "driver differs (${(r.driverDiffs as List).join(', ')})".toString()
    List box = (r.boxDiffs != null ? r.boxDiffs : (r.driverDiffs ? [] : r.diffs)) as List
    if (box) parts << "box differs (${box.join(', ')})".toString()
    return parts ? parts.join("; ") : "as expected"
}

// One row per test of a run: done with its verdict, running, waiting, or not run. The precheck shows only when it failed.
List<Map> testRows(Map run, Map names) {
    Map byTest = [:]
    for (Map r : ((run.results ?: []) as List<Map>)) byTest[r.test as Integer] = r
    List tests = (run.tests ?: []) as List
    int idx = (run.idx ?: 0) as int
    List<Map> rows = []
    for (int i = 0; i < tests.size(); i++) {
        Integer t = tests[i] as Integer
        Map r = byTest[t] as Map
        if (t == 0 && r == null) continue
        String st
        String detail = ""
        if (r != null) { st = verdictOf(r); detail = verdictText(r) }
        else if (run.status == "running" && i == idx) {
            // The current step's plain label, as the page shows it; a step count only before the first label is set.
            st = "running"
            int count = (run.stepCount ?: 0) as int
            int n = ((run.step ?: 0) as int) + 1
            detail = (run.cur?.test == t && run.cur?.label) ? run.cur.label as String :
                     (count > 0 ? "step ${Math.min(n, count)} of ${count}" : "step ${n}").toString()
        }
        else if (run.status == "running" && i > idx) st = "waiting"
        else st = "not run"
        rows << [test: t, name: names[t] ?: "Test ${t}".toString(), state: st, detail: detail]
    }
    return rows
}

List<String> differences(Map fields, Map expect) {
    List<String> out = []
    expect?.each { k, rule ->
        def v = fields[k]
        boolean ok = true
        if (rule.containsKey("eq")) ok = (v == rule.eq)
        else if (rule.containsKey("max")) ok = v != null && (v as Long) <= (rule.max as Long)
        else if (rule.containsKey("sameKeys")) ok = v != null && ((v as String).tokenize(",") as Set) == ((rule.sameKeys as String).tokenize(",") as Set)
        else if (rule.containsKey("none")) ok = v == null
        else if (rule.containsKey("ne")) ok = v != rule.ne
        else if (rule.containsKey("between")) ok = v != null && (v as Long) >= (rule.between[0] as Long) && (v as Long) <= (rule.between[1] as Long)
        if (!ok) out << (k as String)
    }
    return out
}

String formatReport(Map header, List<Map> results) {
    List<String> out = ["Android TV Remote Tester report"]
    if (header.warning) out << "WARNING: ${header.warning}".toString()
    out << "app ${header.appVersion} · driver ${header.driverVersion ?: 'unknown'} · hub firmware ${header.firmware}".toString()
    out << "box: ${header.vendor ?: '?'} ${header.model ?: '?'} · Android ${header.androidVersion ?: '?'} · remote service ${header.remoteServiceVersion ?: '?'} · features ${header.features ?: '?'}".toString()
    String kinds = [tv: "TV with Android TV built in", box: "box plugged into a TV"][header.deviceKind as String] ?: "not given"
    out << "device: ${kinds} · at the TV: ${header.attended == true ? 'yes' : 'no'}".toString()
    out << "run: ${header.status} · slowest log read ${header.slowestReadMs ?: 0} ms".toString()
    out << "box differs: the TV does something the driver doesn't assume · driver differs: the driver didn't act on what the TV sent"
    for (Map r : results) {
        out << ""
        out << "${r.test}. ${r.name} — ${verdictText(r)}".toString()
        if (r.observed) out << ("   " + (r.observed as Map).collect { k, v -> "${k}=${v}" }.join(" "))
        if (r.note) out << "   note: ${r.note}".toString()
        if (r.trace) {
            out << "   trace:"
            (r.trace as List).each { out << "     ${it}".toString() }
        }
    }
    if (header.manualKeys) {
        out << ""
        out << (header.manualKeys as String)
    }
    return out.join("\n")
}

// A code the driver accepts (its first byte matches the hash) but the box rejects: suffix 0000 instead of the shown one.
String wrongPairingCode(String clientPem, String tvPem) {
    CertificateFactory cf = CertificateFactory.getInstance("X.509")
    def c = cf.generateCertificate(new ByteArrayInputStream(clientPem.getBytes("US-ASCII"))).publicKey
    def t = cf.generateCertificate(new ByteArrayInputStream(tvPem.getBytes("US-ASCII"))).publicKey
    MessageDigest md = MessageDigest.getInstance("SHA-256")
    [c.modulus, c.publicExponent, t.modulus, t.publicExponent].each { BigInteger v -> md.update(unsignedBytes(v)) }
    md.update([0, 0] as byte[])
    return String.format("%02X", md.digest()[0] & 0xFF) + "0000"
}

byte[] unsignedBytes(BigInteger v) {
    byte[] b = v.toByteArray()
    if (b.length > 1 && b[0] == 0) {
        byte[] out = new byte[b.length - 1]
        for (int i = 0; i < out.length; i++) out[i] = b[i + 1]
        return out
    }
    return b
}


// The named fields of the driver's state; null state gives null values.
Map pickFields(Map st, List names) {
    return names.collectEntries { [(it): st?.get(it)] }
}

// True when the box reported the app (message 20) at some point in the lines.
boolean appReported(List<Map> lines, String pkg) {
    for (Map l : lines) {
        def m = (l.msg as String) =~ /^rx 20:\x7B1:\x7B12:"([^"]+)"/
        if (m.find() && m.group(1) == pkg) return true
    }
    return false
}

// Whether a conditional question is asked: only where the measurement alone leaves the answer open.
// noYoutube: no message 20 named YouTube after the launch. homeUnchanged: after the HOME key, no message 20, or
// one naming the app already in front (a box that refused the YouTube link starts this test on its home screen).
boolean promptNeeded(String cond, Map steps, List<Map> lines) {
    switch (cond) {
        case "noYoutube": return !appReported(lines, "com.google.android.youtube.tv")
        case "homeUnchanged": return !homeChanged(lines)
        default: return true
    }
}

// Whether the app in front changed after the last HOME key the driver sent: the first message 20 after it names
// another app than the last one before it (or any app, when none was reported before).
boolean homeChanged(List<Map> lines) {
    int at = -1
    for (int i = 0; i < lines.size(); i++) if ((lines[i].msg as String).startsWith("tx 10:\u007B1:3 ")) at = i
    if (at < 0) return false
    String before = lastApp(lines.take(at))
    String after = null
    for (Map l : lines.drop(at + 1)) {
        def m = (l.msg as String) =~ /^rx 20:\x7B1:\x7B12:"([^"]+)"/
        if (m.find()) { after = m.group(1); break }
    }
    return after != null && after != before
}

// Test 10's note: which app the search key opened when no text field was reported (a voice prompt on Google TV).
String textNote(Map f, Map prompts = [:]) {
    if (answeredAny(prompts, ["text"])) return null
    String note = "say in your post whether \"hubitat\" appeared in the search box"
    if (f.fieldReported != true && f.editReported != true && f.searchApp) {
        return "the search key opened ${f.searchApp}; no text field reported; ${note}".toString()
    }
    return note
}

// Test 11's note: asked for in the post only when the person answered none of the questions.
String mediaNote(Map prompts) {
    if (answeredAny(prompts, ["playing", "paused", "resumed", "stopped"])) return null
    return "say in your post whether anything on the TV reacted to play, pause or stop"
}

// True when the person chose "ready" at this prompt: the attended path of tests 10 and 11 follows from it.
boolean choseReady(Map prompts, String kind) {
    return (prompts?.get(kind) as Map)?.choice == "ready"
}

// True when any of these prompts holds an answer (not "not asked", not "no answer").
boolean answeredAny(Map prompts, List kinds) {
    return kinds.any { (prompts?.get(it) as Map)?.choice != null }
}

// The /run parameters as startRun wants them, or the first error. tests are run-order numbers.
Map runOptions(Map p, Map names) {
    List<Integer> tests = null
    if (p.tests) {
        tests = []
        for (String t : (p.tests as String).tokenize(",")) {
            String x = t.trim()
            if (!(x ==~ /\d{1,2}/)) return [ok: false, error: "tests must be comma-separated test numbers"]
            int n = x as int
            if (n == 0 || !names.containsKey(n)) return [ok: false, error: "unknown test ${n}".toString()]
            tests << n
        }
    }
    String attended = p.attended as String
    if (attended != null && !(attended in ["true", "false"])) return [ok: false, error: "attended must be true or false"]
    String kind = (p.deviceKind ?: "unknown") as String
    if (!(kind in ["tv", "box", "unknown"])) return [ok: false, error: "deviceKind must be tv, box or unknown"]
    Integer standbyMin = null
    if (p.standby != null) {
        String sb = p.standby as String
        if (!(sb ==~ /\d{1,3}/) || (sb as int) < 1 || (sb as int) > 240) return [ok: false, error: "standby must be minutes from 1 to 240"]
        standbyMin = sb as Integer
    }
    return [ok: true, opts: [tests: tests, attended: attended == "true", deviceKind: kind, standby: standbyMin != null,
                             standbyMin: standbyMin ?: 2, powerCut: p.cut == "true", wrongCode: p.wrongcode == "true"]]
}

// ── Prompts: what the person is asked, and the bookkeeping of the answer ──

// kind -> [text, choices]. The page shows the text and one button per choice.
Map promptSpec(String kind) {
    switch (kind) {
        case "dark": return [text: "Did the screen go dark?",
                             choices: [[id: "dark", label: "The screen is dark"], [id: "on", label: "It stayed on"]]]
        case "picture": return [text: "Click when the picture is back.",
                                choices: [[id: "on", label: "Picture is back"], [id: "never", label: "It didn't come back"]]]
        case "app": return [text: "Did YouTube open on the TV?", choices: [[id: "opened", label: "YouTube opened"], [id: "not", label: "It didn't open"]]]
        case "home": return [text: "Is the home screen showing?", choices: [[id: "yes", label: "Home screen is showing"], [id: "no", label: "Not the home screen"]]]
        case "field": return [text: "On the TV, open a text field (YouTube's search box works), then click Ready.",
                              choices: [[id: "ready", label: "A text field is open"], [id: "cannot", label: "I can't open one"]]]
        case "text": return [text: "Did hubitat appear in the text field?", choices: [[id: "yes", label: "hubitat appeared"], [id: "no", label: "It didn't"]]]
        case "playing": return [text: "Start playing a video on the TV, then click Ready.",
                                choices: [[id: "ready", label: "A video is playing"], [id: "cannot", label: "I can't start one"]]]
        case "paused": return [text: "Did the video pause?", choices: [[id: "yes", label: "It paused"], [id: "no", label: "It didn't"]]]
        case "resumed": return [text: "Did it play again?", choices: [[id: "yes", label: "It played again"], [id: "no", label: "It didn't"]]]
        case "stopped": return [text: "Did it stop?", choices: [[id: "yes", label: "It stopped"], [id: "no", label: "It didn't"]]]
        default: throw new IllegalArgumentException("unknown prompt ${kind}")
    }
}

Map newPrompt(String kind, String id, int test, long nowMs, int deadlineS) {
    Map spec = promptSpec(kind)
    return [id: id, test: test, kind: kind, text: spec.text, choices: spec.choices, askedAtMs: nowMs,
            deadlineMs: nowMs + deadlineS * 1000L, answer: null]
}

// "open" until answered or past its deadline.
String promptOutcome(Map prompt, long nowMs) {
    if (prompt.answer != null) return "answered"
    return nowMs >= (prompt.deadlineMs as long) ? "expired" : "open"
}

// An answer for the open prompt only: a stale id, an answered or expired prompt, or an unknown choice is refused.
Map answerPrompt(Map prompt, String id, String choice, long nowMs) {
    if (prompt == null || prompt.id != id || promptOutcome(prompt, nowMs) != "open") return [ok: false, error: "stale prompt"]
    if (!((prompt.choices as List<Map>)*.id).contains(choice)) return [ok: false, error: "unknown choice"]
    return [ok: true, prompt: prompt + [answer: [choice: choice, atMs: nowMs]]]
}

// The prompt as the page sees it: null once answered or past its deadline.
Map publicPrompt(Map prompt, long nowMs) {
    if (prompt == null || promptOutcome(prompt, nowMs) != "open") return null
    return [id: prompt.id, test: prompt.test, kind: prompt.kind, text: prompt.text, choices: prompt.choices,
            askedAtMs: prompt.askedAtMs, deadlineMs: prompt.deadlineMs]
}

// Milliseconds from on() to the person's "picture is back"; null when no question was asked.
Object pictureMs(Map prompt, Long startMs) {
    if (prompt == null) return null
    if (prompt.notAsked) return "not asked"
    if (prompt.choice == "on") return (prompt.atMs as Long) - startMs
    if (prompt.choice == "never") return "never"
    return "no answer"
}

// Report fields from the answers of one test: nothing for a question that wasn't asked of this box, "not asked" for one
// nobody was there to answer, "no answer" for one that was left open. The picture question is pictureMs.
Map promptFields(int test, Map prompts) {
    List kinds = [3: ["dark"], 5: ["app"], 8: ["home"], 10: ["field", "text"], 11: ["playing", "paused", "resumed", "stopped"]][test] ?: []
    Map names = [dark: "screenDark", app: "appSeen", home: "homeSeen", field: "fieldOpened", text: "textSeen",
                 playing: "playbackStarted", paused: "pauseSeen", resumed: "playSeen", stopped: "stopSeen"]
    Map out = [:]
    for (String kind : kinds) {
        Map p = prompts[kind] as Map
        // A follow-up question the person was never asked (no field, no video, nobody there) is "not asked".
        if (p == null && kind != kinds[0] && prompts[kinds[0]] != null) p = [notAsked: true]
        if (p == null) continue
        String c = p.choice
        String yesId = kind in ["dark", "app", "field", "playing"] ? [dark: "dark", app: "opened", field: "ready", playing: "ready"][kind] : "yes"
        out[names[kind]] = p.notAsked == true ? "not asked" : (c == null ? "no answer" : (c == yesId ? "yes" : "no"))
    }
    return out
}

// The run for /status: rows, and the step in progress with its deadline for the countdown.
Map statusRun(Map run, Map names) {
    if (!run?.status) return [status: null, reason: null, startedAtMs: null, rows: [], current: null]
    Map current = null
    List tests = (run.tests ?: []) as List
    if (run.status == "running" && (run.idx as int) < tests.size()) {
        Integer t = tests[run.idx as int] as Integer
        Map cur = (run.cur?.test == t ? run.cur : [:]) as Map
        current = [test: t, name: names[t] ?: "Test ${t}".toString(), step: cur.step, label: cur.label ?: "Starting",
                   startedAtMs: cur.startedAtMs, deadlineMs: cur.deadlineMs]
    }
    return [status: run.status, reason: run.reason, startedAtMs: run.startedAt, rows: testRows(run, names), current: current]
}

// ── Remote pad ──

// The pad's commands: the driver's own command ids, plus sendKey, sendText and launchApp with their argument.
// Anything else is refused; the arguments are checked here because the driver would act on whatever it is sent.
Map padCommand(String name, String key = null, String text = null, String link = null) {
    List ids = ["on", "off", "volumeUp", "volumeDown", "play", "pause", "stop", "connect", "disconnect"]
    switch (name) {
        case "sendKey":
            if (key == null || !(key ==~ /[A-Za-z0-9_]{1,40}/)) return [ok: false, error: "unknown key"]
            return [ok: true, method: "sendKey", args: [[type: "STRING", value: key]], label: key.toUpperCase()]
        case "sendText":
            if (text == null || text.length() < 1 || text.length() > 200) return [ok: false, error: "unknown key"]
            // The log shows the length only, as the driver's info line does.
            return [ok: true, method: "sendText", args: [[type: "STRING", value: text]], label: "${text.length()} characters".toString()]
        case "launchApp":
            if (link == null || link.length() > 300 || !(link ==~ /[A-Za-z][A-Za-z0-9+.\-]*:.+/)) return [ok: false, error: "unknown key"]
            return [ok: true, method: "launchApp", args: [[type: "STRING", value: link]], label: maskLine(link).take(60)]
        default:
            if (!(name in ids)) return [ok: false, error: "unknown key"]
            return [ok: true, method: name, args: [], label: null]
    }
}

// ── Pad log: pure helpers ──

// A run restores the trace setting the person had before the pad touched it (the pad's remembered
// value while it is active), else the device's current one.
boolean runStartTrace(Map padTrace, Object current) {
    return padTrace?.active ? padTrace.prev == true : current == true
}

// The log with one more entry, the oldest dropped beyond the cap.
List<Map> padLogAppend(List log, Map entry, int cap) {
    List<Map> out = ((log ?: []) + [entry]) as List<Map>
    if (out.size() > cap) out = out.drop(out.size() - cap) as List<Map>
    return out
}

// The driver's trace, warn and error lines of one press, masked: up to the cut-off timestamp, at most
// maxLines and about 2000 characters, so fifty entries stay small in the app's state.
List<String> padEntryLines(List<Map> lines, String untilTs, int maxLines = 15) {
    List<String> out = []
    int chars = 0
    for (String l : traceLines(lines.findAll { (it.ts as String) < untilTs && (it.level in ["trace", "warn", "error"]) })) {
        String cut = l.take(200)
        if (out.size() >= maxLines || chars + cut.length() > 2000) break
        out << cut
        chars += cut.length()
    }
    return out
}

// The last n entries as the page sees them (newest last).
List<Map> padLogView(List log, int n) {
    List<Map> l = ((log ?: []) as List<Map>)
    if (l.size() > n) l = l.drop(l.size() - n) as List<Map>
    return l.collect { Map e -> [atMs: e.atMs, button: e.button, key: e.key, lines: (e.lines ?: [])] }
}

String padEntryText(Map e, TimeZone tz) {
    List<String> out = ["${new Date(e.atMs as long).format('HH:mm:ss', tz)} ${e.button}${e.key ? ' ' + e.key : ''}".toString()]
    ((e.lines ?: []) as List).each { out << "   ${it}".toString() }
    return out.join("\n")
}

// The report's "Manual keys" section: presses after fromMs up to toMs; empty when there are none.
String padSection(List log, long fromMs, long toMs, TimeZone tz) {
    List<Map> hit = ((log ?: []) as List<Map>).findAll { (it.atMs as long) > fromMs && (it.atMs as long) <= toMs }
    if (!hit) return ""
    List<String> out = ["Manual keys (remote pad presses since the previous report)"]
    hit.each { out << padEntryText(it, tz) }
    return out.join("\n")
}

// The report's fields a mock box profile uses, under the profile's names, taken from the tests that ran;
// otherMessages become extraMessages. Null-free: a field the run did not measure is left out.
Map profileFields(List<Map> results) {
    Map keys = [1: ["vendor", "model", "androidVersion", "remoteService", "remoteServiceVersion", "features", "firstMessages", "startApp"],
                3: ["volumeDuringChange", "sessionKept"], 4: ["volumeReported", "playerModel"], 8: ["homeApp"],
                9: ["announcedOff"], 10: ["searchApp"], 14: ["inputEncodings", "outputEncodings", "preferredRole"],
                15: ["inputEncodings", "outputEncodings", "preferredRole"]]
    Map prof = [:]
    Set<Integer> other = new TreeSet<Integer>()
    for (Map r : results) {
        Map obs = (r.observed ?: [:]) as Map
        ((keys[r.test as Integer] ?: []) as List).each { k -> if (obs.get(k) != null) prof[k] = obs[k] }
        // Volume: the volume keys' message when there was one, else the one around a power change.
        if (r.test == 3 && obs.changeVolumeRange != null) { prof.volumeRange = obs.changeVolumeRange; prof.volumeLevel = obs.changeVolumeLevel }
        if (r.test == 4 && obs.volumeRange != null) { prof.volumeRange = obs.volumeRange; prof.volumeLevel = obs.volumeLevel }
        if (r.test == 6 && obs.errorCode != null) prof.errorCode = obs.errorCode as Integer
        if (r.test == 7 && obs.boxError != null) prof.errorCode = obs.boxError as Integer
        if (obs.otherMessages) other.addAll((obs.otherMessages as String).tokenize(",").collect { it as Integer })
    }
    if (prof) prof.extraMessages = other.toList()
    return prof
}

// Seconds until the next log read: at least as long as the last read took, so reading the log
// never takes more than about half the hub's time; at least 1 s for short waits, 10 s for long ones.
int pollGap(int timeoutS, Long lastReadMs) {
    int base = timeoutS <= 30 ? 1 : 10
    int read = lastReadMs ? (int) Math.ceil(lastReadMs / 1000.0d) : 0
    return Math.max(base, read)
}

// ── Logging ───────────────────────────────────────────────────────────
//   ⬆️ Cmd  🔧 Cfg  ⏰ Sched  🏷️ Ver  ·  ⚠️ Warn  🛑 Error
private String logp(String e) { "${e} ${app.getLabel()}: " }

void logCmd  (String m) { if (txtEnable != false) log.info  logp('⬆️') + m }
void logCfg  (String m) { if (txtEnable != false) log.info  logp('🔧') + m }
void logSched(String m) { if (debugEnable) log.debug logp('⏰') + m }
void logVer  (String m) { log.warn  logp('🏷️') + m }
void logWarn (String m) { log.warn  logp('⚠️') + m }
void logError(String m) { log.error logp('🛑') + m }
void logInfo (String m) { if (txtEnable != false) log.info  "${app.getLabel()}: ${m}" }
void logDebug(String m) { if (debugEnable) log.debug "${app.getLabel()}: ${m}" }

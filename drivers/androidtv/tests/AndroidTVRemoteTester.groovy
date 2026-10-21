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
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.text.SimpleDateFormat

@Field static final String CODE_VERSION = "0.5.2"

@Field static final String BUSY = "a run is in progress"

@Field static final String DROP_RE = '^(remote connect returned|Socket \\(|No message from the TV|Connect failed)'

@Field static final Map TEST_NAMES = [
    0: "Connection", 1: "Session start", 2: "Pings", 3: "Power off/on", 4: "Volume", 5: "Launch by URL",
    6: "Launch by package", 7: "Recovery after a box error", 8: "HOME", 9: "Long standby",
    10: "Power cut", 11: "Wrong pairing code", 12: "Pairing", 13: "Discovery", 14: "Text input"
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
    9: [drops: [eq: 0], pingGaps: [eq: 0], onWorked: [eq: true], onEvent: [eq: true, driver: true]],
    10: [noticeMs: [max: 50000, driver: true], backMs: [max: 180000], stillPaired: [eq: true],
         offlineEvent: [eq: true, driver: true], onlineEvent: [eq: true, driver: true]],
    11: [hexCodeOffered: [ne: false], status: [eq: 402], oldPairingWorks: [eq: true], resumedOnOwn: [eq: true, driver: true], pairingAfter: [eq: "paired", driver: true]],
    12: [certRead: [eq: true], pairingReply: [eq: true], hexCodeOffered: [ne: false], codeShown: [eq: true], wrongCodeStatus: [eq: 402],
         pairingAfter: [eq: "unpaired", driver: true]],
    // The driver finds the box by its mDNS announcement; a box that stops announcing while off delays the driver's IP repair until it wakes.
    13: [announced: [eq: true], deviceTvFound: [ne: false], announcedOff: [eq: true]],
    // The driver echoes the counters of the box's last message 21, so it assumes the box sends one for a focused field.
    14: [textSent: [eq: true, driver: true], accepted: [eq: true], editReported: [eq: true]]
]

// Applies to every test: the box sent a message type the driver doesn't act on.
@Field static final Map EXPECT_ALL = [otherMessages: [none: true]]


preferences {
    page(name: "mainPage")
}

mappings {
    path("/run")    { action: [GET: "apiRun"] }
    path("/status") { action: [GET: "apiStatus"] }
    path("/report") { action: [GET: "apiReport"] }
    path("/abort")  { action: [GET: "apiAbort"] }
}

Map mainPage() {
    if (scriptAccess) checkOAuth() else dropAccessToken()
    checkStall()
    Map run = state.run as Map
    boolean running = run?.status == "running"
    // The page reloads itself while a run is in progress, so the table follows it.
    dynamicPage(name: "mainPage", title: "Android TV Remote Tester", install: true, uninstall: true,
                refreshInterval: run?.status == "running" ? 5 : 0) {
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
        if (!running) section("Optional tests") {
            input "optStandby", "bool", title: "Long standby", defaultValue: false, submitOnChange: true
            if (optStandby) input "standbyMinutes", "number", title: "Standby minutes", defaultValue: 2, range: "1..240"
            input "optPowerCut", "bool", title: "Power cut: turns the plug off for 30 seconds, then waits for the box to come back", defaultValue: false
            input "optWrongCode", "bool", title: "Wrong pairing code: shows a pairing dialog on the TV", defaultValue: false
        }
        section("Run") {
            paragraph "Tests 3 to 10, 13 and 14 change what is on the TV. Tests 1 to 8, 13 and 14 take about 3 minutes; the power cut adds about 3, the wrong code about 1, and the standby its minutes. " +
                      "Watch the TV during test 14: it opens the search screen and types \"hubitat\"."
            if (tv && tv.currentValue("pairing") != "paired") {
                paragraph "The device isn't paired, so Run only checks how far pairing gets: it opens a pairing dialog on the TV and answers it with a wrong code. Takes about 1 minute."
            }
            if (state.startError) paragraph rawHtml: true, message("error", "Cannot start: ${escapeHtml(state.startError as String)}")
            input "btnRun", "button", title: "Run", disabled: running, width: 2,
                  inputClass: "p-button bg-hubitat-primary-green text-white"
            if (running) input "btnAbort", "button", title: "Abort", width: 2, inputClass: "p-button p-button-outlined"
            if (run?.tests) {
                paragraph rawHtml: true, summaryHtml(run)
                paragraph rawHtml: true, statusHtml(run)
            }
        }
        if (state.report) {
            section("Report: copy it into your forum post") {
                paragraph "<textarea readonly rows='30' style='width:100%;font-family:monospace'>${escapeHtml(state.report as String)}</textarea>"
            }
        }
        section("Script access") {
            input "scriptAccess", "bool", title: "Let a script run the tests (turns on OAuth for this app and makes an access token)",
                  defaultValue: false, submitOnChange: true
            if (scriptAccess && state.accessToken) paragraph "<a href='${getFullLocalApiServerUrl()}/report?access_token=${state.accessToken}' target='_blank'>Report as JSON</a>"
        }
        section("Logging") {
            input name: "txtEnable", type: "bool", title: "Enable info logging", defaultValue: true
            input name: "debugEnable", type: "bool", title: "Enable debug logging", defaultValue: false
        }
    }
}

void installed() { initialize() }

void uninstalled() {
    if ((state.run as Map)?.status == "running") finishRun("aborted", "the app was removed")
}

void updated() {
    boolean wasRunning = (state.run as Map)?.status == "running"
    if (wasRunning) finishRun("aborted", "settings saved during the run")
    unsubscribe()
    unschedule()
    initialize()
    // unschedule() just cancelled finishRun's plug retries.
    if (wasRunning && (state.run as Map)?.ctx?.plugCut && plug) runIn(10, "checkPlug", [data: [n: 1]])
}

void initialize() {
    checkVersion(false)
    if (scriptAccess) checkOAuth() else dropAccessToken()
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

void appButtonHandler(String btn) {
    // Re-initialize now rather than in 1 s: the scheduled updated() would abort the run this press starts.
    if (checkVersion(false)) updated()
    if (btn == "btnRun") {
        String err = startRun([tests: null, standby: optStandby == true, standbyMin: (standbyMinutes ?: 2) as Integer,
                               powerCut: optPowerCut == true, wrongCode: optWrongCode == true])
        if (err == BUSY) return              // a second press, or one from a stale page: the run goes on
        state.startError = err
        if (err) logWarn "Cannot start: ${err}"
    } else if (btn == "btnAbort") {
        finishRun("aborted", "aborted by the user")
    }
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
            return message("info", "Running since ${when}: ${where}. This page updates every 5 s.")
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

@Field static final Map ROW_STYLE = [
    ok: ["✓", "text-green-600"], box: ["⚠", "text-orange-500"], driver: ["✗", "text-red-600"], skipped: ["–", "text-color-secondary"],
    running: ["▶", "text-blue-500"], waiting: ["·", "text-color-secondary"], "not run": ["·", "text-color-secondary"]
]

private String statusHtml(Map run) {
    StringBuilder sb = new StringBuilder("<table style='width:100%;border-collapse:collapse'>")
    for (Map r : testRows(run, TEST_NAMES)) {
        List st = ROW_STYLE[r.state] as List
        sb << "<tr style='border-bottom:1px solid var(--surface-border,#ddd)'>"
        sb << "<td class='${st[1]}' style='width:1.6em;padding:.35rem 0;font-weight:bold;text-align:center'>${st[0]}</td>"
        sb << "<td style='width:1.8em;padding:.35rem 0' class='text-color-secondary'>${r.test}</td>"
        sb << "<td style='padding:.35rem .5rem'>${escapeHtml(r.name as String)}</td>"
        sb << "<td class='${st[1]}' style='padding:.35rem 0'>${escapeHtml(r.detail as String)}</td></tr>"
    }
    sb << "</table><div class='text-color-secondary' style='font-size:.85em;margin-top:.4rem'>" +
          "⚠ box differs: your TV does something the driver doesn't assume, often harmless. " +
          "✗ driver differs: the driver didn't act on what the TV sent.</div>"
    return sb.toString()
}

private void dropAccessToken() {
    if (!state.accessToken) return
    try { revokeAccessToken() } catch (e) { state.remove("accessToken") }     // throws while OAuth is off
    logCfg "Script access turned off; the access token is revoked"
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
    // An unpaired device can't run the session tests; the run then checks how far pairing gets.
    boolean pairingOnly = pairing != "paired"
    List<Integer> tests = pairingOnly ? [12] : (opts.tests ? (opts.tests as List<Integer>) : [1, 2, 3, 4, 5, 6, 7, 8, 13, 14])
    if (!opts.tests && !pairingOnly) {
        if (opts.standby) tests << 9
        if (opts.powerCut) tests << 10
        if (opts.wrongCode) tests << 11
    }
    state.slowestReadMs = 0L
    state.readFailures = 0
    state.plugWarning = null
    state.report = null
    state.startError = null
    state.run = [status: "running", reason: null, tests: [0] + tests, idx: 0, step: 0, results: [], events: [], startedAt: now(),
                 standbyMin: (opts.standbyMin ?: 2) as Integer, pairingOnly: pairingOnly,
                 needConnect: !pairingOnly && tv.currentValue("networkStatus") != "online",
                 ctx: [startSwitch: tv.currentValue("switch"), startTrace: tv.getSetting("traceEnable") == true,
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
    Map run = state.run as Map
    if (run?.status != "running") return       // a stale Abort after the run ended changes nothing
    run.status = status
    run.reason = reason
    state.run = run
    Map ctx = run.ctx as Map
    if (ctx.plugCut && plug) {
        plug.on()
        runIn(10, "checkPlug", [data: [n: 1]])
    }
    try {
        tv.updateSetting("traceEnable", [value: ctx.startTrace ? "true" : "false", type: "bool"])
        tv.updateSetting("txtEnable", [value: ctx.startTxt ? "true" : "false", type: "bool"])
        if (ctx.startSwitch == "off" && tv.currentValue("switch") == "on") tv.off()
    } catch (e) {
        logError "Cleanup failed: ${e.message}"
    }
    unsubscribe()
    subscribe(location, "systemStart", "onSystemStart")
    // An exception here would roll back this execution's state writes and leave the run "running".
    try {
        state.report = buildReport(state.run as Map)
    } catch (e) {
        logError "Report failed: ${e}"
        state.report = "Android TV Remote Tester report\nrun ${status}; the report could not be built: ${e.message}"
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
        plug.on()
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
    Map header = [appVersion: CODE_VERSION, driverVersion: tv.getDataValue("driverVersion"),
                  firmware: location.hub.firmwareVersionString,
                  vendor: first.vendor ?: tv.getDataValue("manufacturer"), model: first.model ?: tv.getDataValue("model"),
                  androidVersion: first.androidVersion, remoteServiceVersion: first.remoteServiceVersion,
                  features: first.features, slowestReadMs: state.slowestReadMs, status: run.status, warning: warning]
    return formatReport(header, run.results as List<Map>)
}


// ── Runner ────────────────────────────────────────────────────────────

private int pollSeconds(Map s) { pollGap((s.timeout ?: 0) as int, state.lastReadMs as Long) }

private String nowTs() { new Date().format("yyyy-MM-dd HH:mm:ss.SSS", location.timeZone) }

void runStep() {
    try {
        Map run = state.run as Map
        if (run?.status != "running") return
        keepTrace(run)
        int test = run.tests[run.idx] as int
        List<Map> steps = stepsFor(test, run)
        run.stepCount = steps.size()
        if ((run.step as int) == 0) {
            run.testSince = nowTs()
            run.testStartMs = now()
            run.stepResults = [:]
            String skip = test == 10 && !plug ? "no plug selected" :
                          test == 13 && !tv.hasCommand("findTvs") ? "the driver has no Find TVs command; update it" :
                          test == 14 && !tv.hasCommand("sendText") ? "the driver has no Send Text command; update it" : null
            if (skip) {
                (run.results as List) << [test: test, name: TEST_NAMES[test], skipped: skip]
                run.idx = (run.idx as int) + 1
                state.run = run
                if ((run.idx as int) >= (run.tests as List).size()) finishRun("done", null) else next("runStep", 1, run)
                return
            }
            if (!(test in [0, 10, 12]) && tv.currentValue("networkStatus") != "online") {
                if (run.offlineSince == null) run.offlineSince = now()
                if (now() - (run.offlineSince as Long) > 60000L) {
                    (run.results as List) << [test: test, name: TEST_NAMES[test], skipped: "no session"]
                    run.offlineSince = null
                    run.idx = (run.idx as int) + 1
                    state.run = run
                    if ((run.idx as int) >= (run.tests as List).size()) finishRun("done", null) else next("runStep", 1, run)
                    return
                }
                next("runStep", 10, run)
                return
            }
            run.offlineSince = null
        }
        if ((run.step as int) >= steps.size()) { state.run = run; finishTest(); return }
        Map s = steps[run.step as int]
        run.stepSince = s.sinceTest ? run.testSince : nowTs()
        run.stepStartMs = now()
        state.run = run
        if (s.skipIfOk && ((run.stepResults as Map)[s.skipIfOk] as Map)?.ok) { recordStep(s, true, 0L, "not needed"); return }
        if (s.needOk && !((run.stepResults as Map)[s.needOk] as Map)?.ok) { recordStep(s, false, 0L, "not run"); return }
        if (s.sleep) { next("sleepDone", s.sleep as int, run); return }
        sendStep(s, run)
        if (!s.wait) { recordStep(s, true, null, null); return }
        next("pollWait", pollSeconds(s), run)
    } catch (e) {
        logError "Step failed: ${e}"
        finishRun("aborted", "error: ${e.message}")
    }
}

void sendStep(Map s, Map run) {
    if (!s.cmd) return
    List args = (s.args ?: []) as List
    if (s.dev == "plug") {
        if (s.cmd == "off") { plug.off(); run.ctx.plugCut = true }
        else if (s.cmd == "on") { plug.on(); run.ctx.plugCut = false }
        state.run = run
        return
    }
    switch (s.cmd) {
        case "on": tv.on(); break
        case "off": tv.off(); break
        case "connect": tv.connect(); break
        case "disconnect": tv.disconnect(); break
        case "volumeUp": tv.volumeUp(); break
        case "volumeDown": tv.volumeDown(); break
        case "sendKey": tv.sendKey(args[0] as String); break
        case "launchApp": tv.launchApp(args[0] as String); break
        case "sendText": tv.sendText(args[0] as String); break
        case "startPairing": tv.startPairing(); break
        case "connectIfIdle": tv.connect(); break
        case "wrongCode": tv.submitPairingCode(wrongCodeForTv()); break
        case "findTvs": tv.findTvs(); break
        default: throw new IllegalArgumentException("unknown step command ${s.cmd}")
    }
}

// Reads only the named fields of the device's state; the private key is never copied.
private Map tvStateFields(List<String> names) {
    Map st = null
    httpGet([uri: "http://127.0.0.1:8080", path: "/device/fullJson/${tv.id}", timeout: 20]) { resp -> st = resp.data?.deviceState as Map }
    return names.collectEntries { [(it): st?.get(it)] }
}

private String wrongCodeForTv() {
    Map st = tvStateFields(["cert", "tvCert"])
    return wrongPairingCode(st.cert as String, st.tvCert as String)
}

void pollWait() {
    try {
        Map run = state.run as Map
        if (run?.status != "running") return
        keepTrace(run)
        Map s = stepsFor(run.tests[run.idx] as int, run)[run.step as int]
        List<Map> lines = readTvLines(run.stepSince as String)
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
        logError "Wait failed: ${e}"
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
    (run.stepResults as Map)[s.name] = [ok: ok, ms: ms, at: run.stepStartMs, note: note]
    run.step = (run.step as int) + 1
    state.run = run
    next("runStep", Math.max(1, (s.after ?: 1) as int), run)
}

List<Map> readTvLines(String since) {
    long t0 = now()
    List raw = null
    try {
        httpGet([uri: "http://127.0.0.1:8080", path: "/logs/past/json", timeout: 30]) { resp -> raw = resp.data as List }
    } catch (e) {
        state.readFailures = ((state.readFailures ?: 0) as int) + 1
        logWarn "Log read failed: ${e.message}"
        return null
    }
    state.readFailures = 0
    long ms = now() - t0
    state.lastReadMs = ms
    if (ms > ((state.slowestReadMs ?: 0L) as Long)) state.slowestReadMs = ms
    return deviceLines(raw, tv.idAsLong, tv.displayName, since)
}

void finishTest() {
    Map run = state.run as Map
    int test = run.tests[run.idx] as int
    Map steps = run.stepResults as Map
    if (test == 0) {
        if (steps.connect != null && !steps.connect.ok) {
            List<Map> lines = readTvLines(run.testSince as String) ?: []
            run = state.run as Map
            Map f = evalConnect(lines)
            List d = differences(f, EXPECT[0] as Map)
            (run.results as List) << ([test: 0, name: TEST_NAMES[0], observed: f, diffs: d, trace: traceLines(lines)] + splitDiffs(d, EXPECT[0] as Map))
            state.run = run
            finishRun("aborted", "the device did not connect")
            return
        }
    } else {
        List<Map> lines = readTvLines(run.testSince as String) ?: []
        run = state.run as Map
        List<Map> events = ((run.events ?: []) as List<Map>).findAll { (it.at as Long) >= (run.testStartMs as Long) }
        Map r = evaluateTest(test, lines, steps, run, events)
        if (test in ((run.traceOff ?: []) as List)) r.note = "trace logging was off for part of this test, so lines may be missing"
        (run.results as List) << r
        if (test == 5 && r.skipped == "YouTube not installed") run.ctx.youtubeMissing = true
    }
    run.idx = (run.idx as int) + 1
    run.step = 0
    state.run = run
    if ((run.idx as int) >= (run.tests as List).size()) finishRun("done", null)
    else next("runStep", 2, run)
}

Map evaluateTest(int test, List<Map> lines, Map steps, Map run, List<Map> events) {
    Map r = [test: test, name: TEST_NAMES[test]]
    if (run.ctx.youtubeMissing && test in [6, 7, 8]) { r.skipped = "YouTube not installed"; return r }
    TimeZone tz = location.timeZone
    Map f
    switch (test) {
        case 1: f = evalSessionStart(lines, steps); break
        case 2: f = evalPings(lines, tz); break
        case 3:
            f = evalPower(lines, steps)
            // The driver acts on volume it wasn't asked for only once the box has answered a volume key.
            f.volumeTrusted = tvStateFields(["volumeTrusted"]).volumeTrusted == true
            break
        case 4: f = evalVolume(lines); break
        case 5: f = evalLaunch(lines, steps); break
        case 6: f = evalLaunch(lines, steps); break
        case 7: f = evalRecovery(lines, steps, tz); break
        case 8: f = evalHome(lines, steps); break
        case 9: f = evalStandby(lines, steps); break
        case 10: f = evalPowerCut(lines, steps, [pairing: tv.currentValue("pairing"), switch: tv.currentValue("switch")], tz); break
        case 11: f = evalWrongCode(lines, steps) + evalPairingOptions(lines); break
        case 12: f = evalPairing(lines) + evalPairingOptions(lines); break
        case 13:
            Map st = tvStateFields(["tvId", "foundTvs"])
            f = evalDiscovery(lines, st.tvId as String, st.foundTvs as Map)
            break
        case 14:
            f = evalText(lines)
            r.note = "say in your post whether \"hubitat\" appeared in the search box"
            break
        default: f = [:]
    }
    f.putAll(evalEvents(test, events, f))
    String other = otherMessages(lines)
    if (other) f.otherMessages = other
    r.observed = f
    // Assumption (untested: no box without YouTube was available): the URL then opens the Play Store.
    if (test == 5 && f.app == "com.android.vending") { r.skipped = "YouTube not installed"; return r }
    if (test == 7 && f.boxError == null) { r.skipped = "the box accepted the invalid link"; return r }
    Map expect = (EXPECT[test] as Map) + EXPECT_ALL
    r.diffs = differences(f, expect)
    r.putAll(splitDiffs(r.diffs as List, expect))
    // Session start always: its messages are what a mock of the box replays. Text input always: what the
    // box sends around a text field is still being learned.
    if (r.diffs || test in [1, 14]) r.trace = traceLines(lines)
    return r
}

List<Map> stepsFor(int test, Map run) {
    switch (test) {
        case 0:
            List<Map> pre = []
            if (run.pairingOnly) return pre
            // 40 s: a connect to an address that doesn't answer blocks about 31 s before the driver logs why.
            if (run.needConnect) pre << [name: "connect", cmd: "connect", wait: '^rx 40:', timeout: 40]
            if (run.ctx.startSwitch == "off") pre << [name: "powerOn", cmd: "on", wait: '^rx 40:\\{1:1\\}', timeout: 15, after: 3]
            return pre
        case 1: return [[name: "disconnect", cmd: "disconnect", after: 3],
                        [name: "ready", cmd: "connect", wait: '^rx 20:', timeout: 15, after: 2]]
        case 2: return [[name: "disconnect", cmd: "disconnect", after: 3],
                        [name: "connect", cmd: "connect", wait: '^rx 40:', timeout: 15],
                        [name: "ping1", wait: '^ping #1 ', timeout: 15, sinceTest: true]]
        // Off for 12 s, two ping intervals: a box that stops pinging while off shows as a long gap.
        case 3: return [[name: "off", cmd: "off", wait: '^rx 40:(\\{1:0\\}|"")', timeout: 15, after: 12],
                        [name: "on", cmd: "on", wait: '^rx 40:\\{1:1\\}', timeout: 15, after: 3]]
        case 4: return [[name: "up", cmd: "volumeUp", after: 2],
                        [name: "down", cmd: "volumeDown", after: 2],
                        [name: "mute1", cmd: "sendKey", args: ["VOLUME_MUTE"], after: 2],
                        [name: "mute2", cmd: "sendKey", args: ["VOLUME_MUTE"], after: 3]]
        case 5: return [[name: "launch", cmd: "launchApp", args: ["https://www.youtube.com"], wait: '^rx 20:|^TV rejected', timeout: 20, after: 5]]
        case 6: return [[name: "home", cmd: "sendKey", args: ["HOME"], wait: '^rx 20:', timeout: 15, after: 3],
                        [name: "launch", cmd: "launchApp", args: ["com.google.android.youtube.tv"], wait: '^rx 20:|^TV rejected', timeout: 20, after: 5]]
        case 7: return [[name: "badLink", cmd: "launchApp", args: ["market://launch?id=invalid.tester.nosuchapp"], wait: '^TV rejected|^rx 20:', timeout: 15],
                        [name: "reconnect", wait: '^rx 40:', timeout: 20, sinceTest: true, after: 2],
                        [name: "home", cmd: "sendKey", args: ["HOME"], wait: '^tx 10:\\{1:3 ', timeout: 10, after: 3]]
        case 8: return [[name: "launch", cmd: "launchApp", args: ["https://www.youtube.com"], wait: '^rx 20:', timeout: 20, after: 5],
                        [name: "home", cmd: "sendKey", args: ["HOME"], wait: '^rx 20:', timeout: 15, after: 2]]
        case 9: return [[name: "off", cmd: "off", wait: '^rx 40:(\\{1:0\\}|"")', timeout: 15],
                        [name: "standby", sleep: ((run.standbyMin ?: 2) as int) * 60],
                        [name: "on", cmd: "on", wait: '^rx 40:\\{1:1\\}', timeout: 20, after: 3]]
        case 10: return [[name: "cut", dev: "plug", cmd: "off"],
                         [name: "dark", sleep: 30],
                         [name: "restore", dev: "plug", cmd: "on"],
                         [name: "back", wait: '^rx 40:', timeout: 600, after: 5]]
        case 11: return [[name: "pair", cmd: "startPairing", wait: '^Enter the code shown', timeout: 30, after: 2],
                         [name: "submit", cmd: "wrongCode", wait: '^Pairing failed with status|^Paired', timeout: 20],
                         [name: "resume", wait: '^rx 40:', timeout: 30, sinceTest: true],
                         [name: "reconnect", cmd: "connectIfIdle", wait: '^rx 40:', timeout: 20, skipIfOk: "resume", after: 2]]
        // A wrong code ends the pairing attempt without leaving the TV waiting; the settle covers the driver's 10 s pairing timeout.
        case 12: return [[name: "pair", cmd: "startPairing", wait: '^Enter the code shown',
                          fail: '^(Pairing failed|No pairing response|Socket \\(|Connect failed|The client key)|Exception', timeout: 45, after: 2],
                         [name: "submit", cmd: "wrongCode", needOk: "pair", wait: '^Pairing failed with status|^Paired', timeout: 20],
                         [name: "settle", sleep: 12]]
        // A search takes up to 5 s when nothing answers. Off for 30 s before the second search, as a box may stop announcing once asleep.
        case 13: return [[name: "find", cmd: "findTvs", wait: '^Found |^No Android TV', timeout: 20, after: 2],
                         [name: "off", cmd: "off", wait: '^rx 40:(\\{1:0\\}|"")', timeout: 15],
                         [name: "standby", sleep: 30],
                         [name: "findOff", cmd: "findTvs", wait: '^Found |^No Android TV', timeout: 20, after: 2],
                         [name: "on", cmd: "on", wait: '^rx 40:\\{1:1\\}', timeout: 20, after: 3]]
        // The text follows SEARCH by 2 s with no log read between: the search screen closes itself 9-11 s
        // after it opens. The text stays on screen 6 s, for whoever watches the TV.
        case 14: return [[name: "home", cmd: "sendKey", args: ["HOME"], after: 3],
                         [name: "search", cmd: "sendKey", args: ["SEARCH"], after: 2],
                         [name: "text", cmd: "sendText", args: ["hubitat"], after: 6],
                         [name: "back", cmd: "sendKey", args: ["BACK"], after: 2],
                         [name: "home2", cmd: "sendKey", args: ["HOME"], after: 2]]
        default:
            return []
    }
}


// ── Script endpoints ──────────────────────────────────────────────────

def apiRun() {
    if (checkVersion(false)) updated()     // as in appButtonHandler
    List<Integer> tests = params.tests ? (params.tests as String).tokenize(",").collect { it.trim() as Integer } : null
    String err = startRun([tests: tests, standby: params.standby != null,
                           standbyMin: params.standby ? (params.standby as Integer) : 2,
                           powerCut: params.cut == "true", wrongCode: params.wrongcode == "true"])
    if (err) return render(status: err.contains("in progress") ? 409 : 400, contentType: "application/json", data: JsonOutput.toJson([error: err]))
    return render(status: 200, contentType: "application/json", data: JsonOutput.toJson([started: true]))
}

def apiStatus() {
    checkVersion()
    checkStall()
    Map run = (state.run ?: [:]) as Map
    Integer test = run.tests ? (run.tests[run.idx as int] as Integer) : null
    return render(status: 200, contentType: "application/json",
                  data: JsonOutput.toJson([status: run.status, test: test, idx: run.idx, of: (run.tests as List)?.size(), step: run.step]))
}

def apiAbort() {
    checkVersion()
    finishRun("aborted", "aborted by the script")
    return render(status: 200, contentType: "application/json", data: JsonOutput.toJson([status: (state.run as Map)?.status]))
}

def apiReport() {
    checkVersion()
    Map run = (state.run ?: [:]) as Map
    return render(status: 200, contentType: "application/json",
                  data: JsonOutput.toJson([status: run.status, reason: run.reason, report: state.report, results: run.results]))
}


// ── OAuth ─────────────────────────────────────────────────────────────

private boolean autoEnableOAuth() {
    String typeId = app.getAppTypeId()?.toString()
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

List<String> traceLines(List<Map> lines) {
    List<String> out = []
    for (Map l : lines) {
        String msg = l.msg as String
        if (msg.startsWith("chunk ")) continue
        out << "${(l.ts as String).substring(11)} ${maskLine(msg)}".toString()
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
    for (Map l : lines) {
        def m = (l.msg as String) =~ /^rx (\d+):/
        if (!m.find()) continue
        order << (m.group(1) as Integer)
        if (m.group(1) == "1" && cfg == null) cfg = l.msg as String
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
            remoteServiceVersion: kv["6"], features: features, firstMessages: order.take(6).join(","),
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
            sessionKept: !lines.any { (it.msg as String) =~ DROP_RE }, pingGaps: gaps.size(), longestPingGapMs: gaps ? gaps.max() : null,
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

// Test 14: the app the search key opened, whether the box reported a focused text field (message 20 with
// field 2, or message 21), and whether it refused the text.
Map evalText(List<Map> lines) {
    String searchApp = null, code = null
    boolean searched = false, sent = false, rejected = false, field = false, edit = false, editAfterText = false, echoed = false
    for (Map l : lines) {
        String msg = l.msg as String
        if (msg == "Key 84") searched = true
        else if (msg.startsWith("Text ")) sent = true
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
    return [drops: lines.count { (it.msg as String) =~ DROP_RE } as Integer, pingGaps: gaps.size(), longestPingGapMs: gaps ? gaps.max() : null,
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

// Test 13: the first search runs with the box on, the second while it is off.
Map evalDiscovery(List<Map> lines, String tvId, Map foundTvs) {
    List<Map> rs = lines.collect { searchResult(it.msg as String) }.findAll { it != null }
    Map on = rs ? rs[0] : null
    Map off = rs.size() > 1 ? rs[1] : null
    Boolean mine = null
    if (on?.count) mine = on.many ? (foundTvs != null && tvId ? foundTvs.containsKey(tvId) : null) : !on.other
    return [announced: on == null ? null : on.count > 0, tvCount: on?.count, deviceTvFound: mine,
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
        case 9: return [onEvent: hasEvent(events, "switch", "on")]
        case 10: return [offlineEvent: hasEvent(events, "networkStatus", "offline"), onlineEvent: hasEvent(events, "networkStatus", "online")]
        case 11:
        case 12: return [pairingAfter: lastEvent(events, "pairing")]
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
            // The step count moves on when a step ends, before the pause that lets the TV settle; cap it at the last step.
            st = "running"
            int count = (run.stepCount ?: 0) as int
            int n = ((run.step ?: 0) as int) + 1
            detail = (count > 0 ? "step ${Math.min(n, count)} of ${count}" : "step ${n}").toString()
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

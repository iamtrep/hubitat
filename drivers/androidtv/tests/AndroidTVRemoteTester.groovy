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

@Field static final String CODE_VERSION = "0.1.0"

@Field static final String DROP_RE = '^(remote connect returned|Socket \\(|No message from the TV|Connect failed)'

@Field static final Map TEST_NAMES = [
    1: "Session start", 2: "Pings", 3: "Power off/on", 4: "Volume", 5: "Launch by URL",
    6: "Launch by package", 7: "Recovery after a box error", 8: "HOME", 9: "Long standby",
    10: "Power cut", 11: "Wrong pairing code"
]

// What the driver assumes about a box. A field outside its rule is a difference.
@Field static final Map EXPECT = [
    1: [setupFirst: [eq: true], powerReported: [eq: true], appReported: [eq: true], startMs: [max: 5000]],
    2: [firstPingMs: [between: [0, 7000]]],
    3: [offReportMs: [max: 3000], onReportMs: [max: 3000], sessionKept: [eq: true]],
    4: [volumeReported: [eq: true]],
    5: [accepted: [eq: true], app: [eq: "com.google.android.youtube.tv"]],
    6: [accepted: [eq: true], app: [eq: "com.google.android.youtube.tv"]],
    7: [reconnectMs: [max: 5000], nextCommandOk: [eq: true]],
    8: [appChanged: [eq: true]],
    9: [drops: [eq: 0], onWorked: [eq: true]],
    10: [noticeMs: [max: 50000], backMs: [max: 180000], stillPaired: [eq: true]],
    11: [status: [eq: 402], oldPairingWorks: [eq: true], resumedOnOwn: [eq: true]]
]


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
    dynamicPage(name: "mainPage", title: "Android TV Remote Tester", install: true, uninstall: true) {
        section("Devices") {
            input "tv", "device.AndroidTVRemote", title: "Android TV Remote device", required: true
            input "plug", "capability.switch", title: "Smart plug feeding the box (optional, for the power-cut test)", required: false
        }
        section("Optional tests") {
            input "optStandby", "bool", title: "Long standby", defaultValue: false, submitOnChange: true
            if (optStandby) input "standbyMinutes", "number", title: "Standby minutes", defaultValue: 2, range: "1..240"
            input "optPowerCut", "bool", title: "Power cut: turns the plug off for 2 minutes", defaultValue: false
            input "optWrongCode", "bool", title: "Wrong pairing code: shows a pairing dialog on the TV", defaultValue: false
        }
        section("Run") {
            paragraph "Tests 3 to 10 change what is on the TV. Tests 1 to 8 take about 2 minutes; the power cut adds about 3, the wrong code about 1, and the standby its minutes."
            Map run = state.run as Map
            if (run?.status == "running") {
                paragraph "Running ${TEST_NAMES[run.tests[run.idx]] ?: 'precheck'} (${(run.idx as int) + 1} of ${(run.tests as List).size()}), step ${(run.step as int) + 1}"
                input "btnAbort", "button", title: "Abort"
            } else {
                input "btnRun", "button", title: "Run"
            }
        }
        if (state.report) {
            section("Report: copy it into your forum post") {
                paragraph "<textarea readonly rows='30' style='width:100%;font-family:monospace'>${escapeHtml(state.report as String)}</textarea>"
            }
        }
        section("Script access") {
            if (state.accessToken) paragraph "<a href='${getFullLocalApiServerUrl()}/report?access_token=${state.accessToken}' target='_blank'>Report as JSON</a>"
        }
        section("Logging") {
            input name: "txtEnable", type: "bool", title: "Enable info logging", defaultValue: true
            input name: "debugEnable", type: "bool", title: "Enable debug logging", defaultValue: false
        }
    }
}

void installed() { initialize() }

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
    checkOAuth()
    subscribe(location, "systemStart", "onSystemStart")
    if (debugEnable) runIn(1800, "logsOff")
}

void logsOff() {
    logWarn "Debug logging turned off"
    app.updateSetting("debugEnable", [value: "false", type: "bool"])
}

void checkVersion(boolean reinit = true) {
    if (state.version == CODE_VERSION) return
    logVer "Version ${CODE_VERSION} (was ${state.version})"
    state.version = CODE_VERSION
    if (reinit) runIn(1, "updated")
}

void onSystemStart(evt) {
    if ((state.run as Map)?.status == "running") finishRun("interrupted", "hub restart")
}

void appButtonHandler(String btn) {
    checkVersion()
    if (btn == "btnRun") {
        String err = startRun([tests: null, standby: optStandby == true, standbyMin: (standbyMinutes ?: 2) as Integer,
                               powerCut: optPowerCut == true, wrongCode: optWrongCode == true])
        if (err) logWarn "Cannot start: ${err}"
    } else if (btn == "btnAbort") {
        finishRun("aborted", "aborted by the user")
    }
}

private String escapeHtml(String s) {
    s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("'", "&#39;")
}


// ── Run control ───────────────────────────────────────────────────────

String startRun(Map opts) {
    if ((state.run as Map)?.status == "running") return "a run is in progress"
    if (!tv) return "select the Android TV Remote device"
    // driverVersion is set when the driver initializes and at every session start, so a current driver
    // that was updated without saving its preferences still passes once it has connected.
    if (tv.getDataValue("driverVersion") == null) return "the driver is too old, or has not connected since it was updated"
    if (tv.currentValue("pairing") != "paired") return "the device is not paired"
    List<Integer> tests = opts.tests ? (opts.tests as List<Integer>) : [1, 2, 3, 4, 5, 6, 7, 8]
    if (!opts.tests) {
        if (opts.standby) tests << 9
        if (opts.powerCut) tests << 10
        if (opts.wrongCode) tests << 11
    }
    state.slowestReadMs = 0L
    state.readFailures = 0
    state.plugWarning = null
    state.report = null
    state.run = [status: "running", reason: null, tests: [0] + tests, idx: 0, step: 0, results: [],
                 standbyMin: (opts.standbyMin ?: 2) as Integer,
                 needConnect: tv.currentValue("networkStatus") != "online",
                 ctx: [startSwitch: tv.currentValue("switch"), startTrace: tv.getSetting("traceEnable") == true,
                       startTxt: tv.getSetting("txtEnable") != false, plugCut: false, youtubeMissing: false]]
    tv.updateSetting("traceEnable", [value: "true", type: "bool"])
    tv.updateSetting("txtEnable", [value: "true", type: "bool"])
    logCmd "Run started: tests ${tests.join(', ')}"
    runIn(1, "runStep")
    return null
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
    // An exception here would roll back this execution's state writes and leave the run "running".
    try {
        state.report = buildReport(state.run as Map)
    } catch (e) {
        logError "Report failed: ${e}"
        state.report = "Android TV Remote Tester report\nrun ${status}; the report could not be built: ${e.message}"
    }
    logCmd "Run ${status}${reason ? ': ' + reason : ''}"
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
        int test = run.tests[run.idx] as int
        List<Map> steps = stepsFor(test, run)
        if ((run.step as int) == 0) {
            run.testSince = nowTs()
            run.stepResults = [:]
            if (test == 10 && !plug) {
                (run.results as List) << [test: test, name: TEST_NAMES[test], skipped: "no plug selected"]
                run.idx = (run.idx as int) + 1
                state.run = run
                if ((run.idx as int) >= (run.tests as List).size()) finishRun("done", null) else runIn(1, "runStep")
                return
            }
            if (test != 0 && test != 10 && tv.currentValue("networkStatus") != "online") {
                if (run.offlineSince == null) run.offlineSince = now()
                if (now() - (run.offlineSince as Long) > 60000L) {
                    (run.results as List) << [test: test, name: TEST_NAMES[test], skipped: "no session"]
                    run.offlineSince = null
                    run.idx = (run.idx as int) + 1
                    state.run = run
                    if ((run.idx as int) >= (run.tests as List).size()) finishRun("done", null) else runIn(1, "runStep")
                    return
                }
                state.run = run
                runIn(10, "runStep")
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
        if (s.sleep) { runIn(s.sleep as int, "sleepDone"); return }
        sendStep(s, run)
        if (!s.wait) { recordStep(s, true, null, null); return }
        runIn(pollSeconds(s), "pollWait")
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
        case "startPairing": tv.startPairing(); break
        case "connectIfIdle": tv.connect(); break
        case "wrongCode": tv.submitPairingCode(wrongCodeForTv()); break
        default: throw new IllegalArgumentException("unknown step command ${s.cmd}")
    }
}

// Reads only the two certificate fields of the device's state.
private String wrongCodeForTv() {
    Map st = null
    httpGet([uri: "http://127.0.0.1:8080", path: "/device/fullJson/${tv.id}", timeout: 20]) { resp -> st = resp.data?.deviceState as Map }
    return wrongPairingCode(st?.cert as String, st?.tvCert as String)
}

void pollWait() {
    try {
        Map run = state.run as Map
        if (run?.status != "running") return
        Map s = stepsFor(run.tests[run.idx] as int, run)[run.step as int]
        List<Map> lines = readTvLines(run.stepSince as String)
        if (lines == null) {
            if (((state.readFailures ?: 0) as int) >= 3) { finishRun("aborted", "could not read the hub logs"); return }
            runIn(pollSeconds(s), "pollWait")
            return
        }
        Map hit = lines.find { (it.msg as String) =~ (s.wait as String) }
        if (hit) { recordStep(s, true, tsMs(hit.ts as String, location.timeZone) - (run.stepStartMs as Long), null); return }
        if (now() - (run.stepStartMs as Long) > ((s.timeout as Long) * 1000L)) { recordStep(s, false, null, "timed out"); return }
        runIn(pollSeconds(s), "pollWait")
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
    runIn(Math.max(1, (s.after ?: 1) as int), "runStep")
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
        if (steps.connect != null && !steps.connect.ok) { finishRun("aborted", "the device did not connect"); return }
    } else {
        List<Map> lines = readTvLines(run.testSince as String) ?: []
        run = state.run as Map
        Map r = evaluateTest(test, lines, steps, run)
        (run.results as List) << r
        if (test == 5 && r.skipped == "YouTube not installed") run.ctx.youtubeMissing = true
    }
    run.idx = (run.idx as int) + 1
    run.step = 0
    state.run = run
    if ((run.idx as int) >= (run.tests as List).size()) finishRun("done", null)
    else runIn(2, "runStep")
}

Map evaluateTest(int test, List<Map> lines, Map steps, Map run) {
    Map r = [test: test, name: TEST_NAMES[test]]
    if (run.ctx.youtubeMissing && test in [6, 7, 8]) { r.skipped = "YouTube not installed"; return r }
    TimeZone tz = location.timeZone
    Map f
    switch (test) {
        case 1: f = evalSessionStart(lines, steps); break
        case 2: f = evalPings(lines, tz); break
        case 3: f = evalPower(lines, steps); break
        case 4: f = evalVolume(lines); break
        case 5: f = evalLaunch(lines, steps); break
        case 6: f = evalLaunch(lines, steps); break
        case 7: f = evalRecovery(lines, steps, tz); break
        case 8: f = evalHome(lines, steps); break
        case 9: f = evalStandby(lines, steps); break
        case 10: f = evalPowerCut(lines, steps, [pairing: tv.currentValue("pairing"), switch: tv.currentValue("switch")], tz); break
        case 11: f = evalWrongCode(lines, steps); break
        default: f = [:]
    }
    r.observed = f
    // Assumption (untested: no box without YouTube was available): the URL then opens the Play Store.
    if (test == 5 && f.app == "com.android.vending") { r.skipped = "YouTube not installed"; return r }
    if (test == 7 && f.boxError == null) { r.skipped = "the box accepted the invalid link"; return r }
    r.diffs = differences(f, EXPECT[test] as Map)
    if (r.diffs) r.trace = traceLines(lines)
    return r
}

List<Map> stepsFor(int test, Map run) {
    switch (test) {
        case 0:
            List<Map> pre = []
            if (run.needConnect) pre << [name: "connect", cmd: "connect", wait: '^rx 40:', timeout: 20]
            if (run.ctx.startSwitch == "off") pre << [name: "powerOn", cmd: "on", wait: '^rx 40:\\{1:1\\}', timeout: 15, after: 3]
            return pre
        case 1: return [[name: "disconnect", cmd: "disconnect", after: 3],
                        [name: "ready", cmd: "connect", wait: '^rx 20:', timeout: 15, after: 2]]
        case 2: return [[name: "disconnect", cmd: "disconnect", after: 3],
                        [name: "connect", cmd: "connect", wait: '^rx 40:', timeout: 15],
                        [name: "ping1", wait: '^ping #1 ', timeout: 15, sinceTest: true]]
        case 3: return [[name: "off", cmd: "off", wait: '^rx 40:(\\{1:0\\}|"")', timeout: 15, after: 3],
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
        default:
            return []
    }
}


// ── Script endpoints ──────────────────────────────────────────────────

def apiRun() {
    checkVersion()
    List<Integer> tests = params.tests ? (params.tests as String).tokenize(",").collect { it.trim() as Integer } : null
    String err = startRun([tests: tests, standby: params.standby != null,
                           standbyMin: params.standby ? (params.standby as Integer) : 2,
                           powerCut: params.cut == "true", wrongCode: params.wrongcode == "true"])
    if (err) return render(status: err.contains("in progress") ? 409 : 400, contentType: "application/json", data: JsonOutput.toJson([error: err]))
    return render(status: 200, contentType: "application/json", data: JsonOutput.toJson([started: true]))
}

def apiStatus() {
    checkVersion()
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
        if (msg.startsWith("ping #") || msg.startsWith("chunk ")) continue
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
    return [offReportMs: steps.off?.ok ? steps.off.ms : null, onReportMs: steps.on?.ok ? steps.on.ms : null,
            sessionKept: !lines.any { (it.msg as String) =~ DROP_RE },
            volumeDuringChange: lines.any { (it.msg as String) =~ /^rx 50:.*[\x7B ]6:\d+/ }]
}

// Protobuf leaves out fields at their default, so a level of 0 has no 7: and 6: may follow the brace.
Map evalVolume(List<Map> lines) {
    for (Map l : lines) {
        String msg = l.msg as String
        if (!msg.startsWith("rx 50:")) continue
        def r = msg =~ /[\x7B ]6:(\d+)/
        if (!r.find()) continue
        def v = msg =~ /[\x7B ]7:(\d+)/
        return [volumeReported: true, volumeRange: r.group(1) as Long, volumeLevel: v.find() ? (v.group(1) as Long) : 0L]
    }
    return [volumeReported: false, volumeRange: null, volumeLevel: null]
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
    return [drops: lines.count { (it.msg as String) =~ DROP_RE } as Integer, onWorked: steps.on?.ok == true]
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
    return [noticedBy: by, noticeMs: notice, backMs: steps.back?.ok ? steps.back.ms : null,
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

List<String> differences(Map fields, Map expect) {
    List<String> out = []
    expect?.each { k, rule ->
        def v = fields[k]
        boolean ok = true
        if (rule.containsKey("eq")) ok = (v == rule.eq)
        else if (rule.containsKey("max")) ok = v != null && (v as Long) <= (rule.max as Long)
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
    for (Map r : results) {
        out << ""
        List diffs = (r.diffs ?: []) as List
        String verdict = r.skipped ? "skipped: ${r.skipped}" : (diffs ? "differs (${diffs.join(', ')})" : "as expected")
        out << "${r.test}. ${r.name} — ${verdict}".toString()
        if (r.observed) out << ("   " + (r.observed as Map).collect { k, v -> "${k}=${v}" }.join(" "))
        if (diffs && r.trace) {
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

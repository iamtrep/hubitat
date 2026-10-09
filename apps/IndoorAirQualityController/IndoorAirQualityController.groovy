// Copyright (c) 2026 PJ
// SPDX-License-Identifier: MIT

/*
 Indoor Air Quality Controller

 Turns ventilation on in stages as indoor CO2 rises, suggests opening a window when
 CO2 stays high with every stage running, and warns about low indoor humidity.
*/

import com.hubitat.app.ChildDeviceWrapper
import com.hubitat.app.DeviceWrapper
import com.hubitat.hub.domain.State
import groovy.transform.CompileStatic
import groovy.transform.Field

@Field static final String CODE_VERSION = "0.1.3"

definition(
    name: "Indoor Air Quality Controller",
    namespace: "iamtrep",
    author: "pj",
    description: "Runs ventilation in stages from indoor CO2 and warns when a window should be opened or the air is too dry",
    menu: "Automations", // new in platform 2.5.0
    category: "Convenience",
    singleThreaded: true,
    importUrl: "https://raw.githubusercontent.com/iamtrep/hubitat/main/apps/IndoorAirQualityController/IndoorAirQualityController.groovy",
    iconUrl: "", iconX2Url: ""
)

@Field static final List<Map> STAGE_DEFAULTS = [[on: 625, off: 575, dwell: 5], [on: 1150, off: 1050, dwell: 6],
                                                [on: 1250, off: 1150, dwell: 6], [on: 1350, off: 1250, dwell: 6]]
@Field static final long STALE_MS = 7200000L        // a sensor silent for 2 hours drops out
@Field static final long PENDING_MS = 60000L        // our command's event arrives within a minute
@Field static final long REASSERT_GAP_MS = 300000L  // at most one re-assertion per switch per 5 minutes
@Field static final Map STOP_TEXT = [safety: "Stopped: smoke or CO detected, switches left as they are",
                                     disabled: "Stopped: turned off on the Air device",
                                     mode: "Stopped: the mode is not selected",
                                     pause: "Paused by a switch"]

preferences {
    page(name: "mainPage")
}

// ── UI ────────────────────────────────────────────────────────────────

String esc(Object s) { s == null ? '' : s.toString().replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;').replace("'", '&#39;') }

String fmtTime(Long t) { t == null ? '' : new Date(t).format('HH:mm', location.timeZone) }

Map mainPage() {
    List<String> errs = validateCfg(currentCfg())
    dynamicPage(name: "mainPage", title: "", install: errs.isEmpty(), uninstall: true) {
        section {
            label title: "App name", required: false
            if (errs) paragraph "<div class='p-message p-message-error p-3 border-round'>${errs.collect { esc(it) }.join('<br>')}</div>"
            if (state.stages != null) paragraph rawHtml: true, statusHtml()
        }
        section("CO2") {
            input "co2Sensors", "capability.carbonDioxideMeasurement", title: "CO2 sensors", multiple: true, required: true
            input "co2Mode", "enum", title: "Combine the readings", options: [highest: "Highest reading", average: "Average"],
                  defaultValue: "highest", required: true
        }
        section("Stages") {
            input "stageCount", "enum", title: "Number of stages", options: ["1", "2", "3", "4"], defaultValue: "3",
                  required: true, submitOnChange: true
            for (int n = 1; n <= stageCount(); n++) {
                Map d = STAGE_DEFAULTS[n - 1]
                paragraph "<b>Stage ${n}</b>"
                input "stage${n}Switches", "capability.switch", title: "Switches", multiple: true, required: true
                input "stage${n}On", "number", title: "On above (ppm)", defaultValue: d.on, required: true, width: 4
                input "stage${n}Off", "number", title: "Off below (ppm)", defaultValue: d.off, required: true, width: 4
                input "stage${n}Dwell", "number", title: "For (minutes)", defaultValue: d.dwell, range: "1..120", required: true, width: 4
            }
            input "holdMinutes", "number", title: "After a switch is turned off by hand, leave its stage off for (minutes)",
                  defaultValue: 60, range: "1..1440", required: true
        }
        section("When to manage air") {
            input "activeModes", "mode", title: "Modes (none selected: every mode except Away)", multiple: true, required: false
            input "pauseWhenOn", "capability.switch", title: "Pause while any of these is on", multiple: true, required: false
            input "pauseWhenOff", "capability.switch", title: "Pause while any of these is off", multiple: true, required: false
            input "smokeDetectors", "capability.smokeDetector", title: "Smoke detectors: while smoke is detected, stop and leave the switches as they are",
                  multiple: true, required: false
            input "coDetectors", "capability.carbonMonoxideDetector", title: "CO detectors: the same, while CO is detected", multiple: true, required: false
        }
        section("Threshold offset") {
            input "offsetPpm", "number", title: "Add to every stage threshold (ppm)", defaultValue: 0, required: true
            input "offsetWhenOn", "capability.switch", title: "While any of these is on", multiple: true, required: false
            input "offsetWhenOff", "capability.switch", title: "While any of these is off", multiple: true, required: false
        }
        section("Open-window advisory") {
            input "advPpm", "number", title: "CO2 above (ppm)", defaultValue: 1400, required: true, width: 4
            input "advMinutes", "number", title: "For (minutes)", defaultValue: 20, range: "1..240", required: true, width: 4
            input "advRepeatHours", "number", title: "Repeat every (hours)", defaultValue: 3, range: "1..24", required: true, width: 4
            input "advAllClear", "bool", title: "Notify when CO2 is back down", defaultValue: false
        }
        section("Low-humidity advisory") {
            input "rhSensors", "capability.relativeHumidityMeasurement", title: "Humidity sensors (the lowest reading is used)",
                  multiple: true, required: false
            input "rhPct", "number", title: "Humidity below (%RH)", defaultValue: 30, range: "5..80", required: true, width: 6
            input "rhHours", "number", title: "For (hours)", defaultValue: 12, range: "1..72", required: true, width: 6
            input "seasonDevice", "device.HVACSeason", title: "Only in winter, from this HVAC Season Manager device (optional)", required: false
        }
        section("Notifications") {
            input "notifyDevices", "capability.notification", title: "Send both advisories to", multiple: true, required: false
        }
        section("Logging and testing", hideable: true, hidden: true) {
            input "txtEnable", "bool", title: "Enable info logging", defaultValue: true
            input "debugEnable", "bool", title: "Enable debug logging (turns off after 30 minutes)", defaultValue: false
            input "testFast", "bool", title: "Testing: a minute lasts a second and an hour a minute", defaultValue: false
        }
    }
}

String statusHtml() {
    Map cfg = currentCfg()
    boolean offOn = offsetApplies()
    BigDecimal o = offOn ? numOrNull(cfg.offset) : 0G
    List<Map> st = resized((state.stages ?: []) as List<Map>, (cfg.stages as List).size())
    StringBuilder h = new StringBuilder()
    String head = validateCfg(cfg) ? 'Not managing: settings incomplete' : (state.lastStop ? STOP_TEXT[state.lastStop] : 'Managing air')
    h << "<p><b>${esc(head)}</b> · CO2 "
    h << (state.co2 != null ? "${fmtInt(state.co2)} ppm" : "no reading")
    if (offOn) h << " · offset +${fmtInt(o)} ppm applies"
    h << "</p><table class='table'><tr><th>Stage</th><th>On above</th><th>Off below</th><th>State</th></tr>"
    (cfg.stages as List<Map>).eachWithIndex { Map d, int i ->
        Map x = st[i]
        String s = x.s == "held" ? "held until ${fmtTime(x.heldUntil as Long)}" : x.s as String
        h << "<tr><td>${i + 1}</td><td>${fmtInt(numOrNull(d.on) + o)}</td><td>${fmtInt(numOrNull(d.off) + o)}</td><td>${esc(s)}</td></tr>"
    }
    h << "</table><p>Open-window advisory: ${(state.window as Map)?.active ? 'active' : 'inactive'}"
    h << " · Low-humidity advisory: ${(state.rh as Map)?.active ? 'active' : 'inactive'}</p>"
    return h.toString()
}

// ── Lifecycle ─────────────────────────────────────────────────────────

void installed() { checkVersion(false); state.enabled = false; initialize() }

void updated() { checkVersion(false); unsubscribe(); unschedule(); initialize() }

void uninstalled() {
    if (state.lastStop != 'safety') releaseAll()
    if (getChildDevice(dni())) deleteChildDevice(dni())
}

void initialize() {
    airDevice()
    Map cfg = currentCfg()
    restartTimers((cfg.stages as List).size())
    for (int n = stageCount() + 1; n <= 4; n++) {
        Map claims = (state.claims ?: [:]) as Map
        ((settings["stage${n}Switches".toString()] ?: []) as List).each { Object o ->
            DeviceWrapper d = o as DeviceWrapper
            if (claims[d.id.toString()]) switchCmd(d, "off")
        }
    }
    pruneClaims()
    if (debugEnable) runIn(1800, "logsOff")
    List<String> errs = validateCfg(cfg)
    if (errs) {
        logWarn "settings incomplete, nothing is managed: ${errs.join('; ')}"
        releaseAll()
        state.stages = resized([], (cfg.stages as List).size())
        publish(null, null, state.stages as List<Map>)
        return
    }
    subscribe(co2Sensors, "carbonDioxide", "inputHandler")
    if (rhSensors) subscribe(rhSensors, "humidity", "inputHandler")
    stageDevices().each { DeviceWrapper d -> subscribe(d, "switch", "stageSwitchHandler") }
    [pauseWhenOn, pauseWhenOff, offsetWhenOn, offsetWhenOff].each { if (it) subscribe(it, "switch", "inputHandler") }
    if (smokeDetectors) subscribe(smokeDetectors, "smoke", "inputHandler")
    if (coDetectors) subscribe(coDetectors, "carbonMonoxide", "inputHandler")
    if (seasonDevice) subscribe(seasonDevice, "season", "inputHandler")
    subscribe(location, "mode", "inputHandler")
    subscribe(location, "systemStart", "systemStartHandler")
    evaluateAir("settings saved")
}

// Dwell timers start over from the current value; engaged and held stages keep their state.
void restartTimers(int n) {
    state.stages = resized((state.stages ?: []) as List<Map>, n).collect { Map m -> [s: m.s, since: null, heldUntil: m.heldUntil] }
}

// A switch no longer in any stage loses its claim and is left as it is.
void pruneClaims() {
    Set<String> ids = stageDevices().collect { it.id.toString() } as Set
    Map c = (state.claims ?: [:]) as Map
    Map kept = c.findAll { k, v -> ids.contains(k as String) }
    if (kept.size() != c.size()) logInfo "${c.size() - kept.size()} switch(es) no longer in a stage, left as they are"
    state.claims = kept
}

void systemStartHandler(evt) {
    checkVersion()
    restartTimers((currentCfg().stages as List).size())
    evaluateAir("hub restart")
}

void logsOff() { checkVersion(); app.updateSetting("debugEnable", false); logWarn "debug logging disabled" }

void checkVersion(boolean reinit = true) {
    if (state.version == CODE_VERSION) return
    logVer "version ${CODE_VERSION} (was ${state.version})"
    state.version = CODE_VERSION
    if (reinit) runIn(1, "updated")
}

// ── Configuration ─────────────────────────────────────────────────────

int stageCount() { (settings.stageCount ?: "3") as int }

Object stageSetting(int n, String key) { settings["stage${n}${key}".toString()] }

BigDecimal numOr(Object v, Object dflt) {
    BigDecimal n = numOrNull(v)
    return n == null ? (dflt as BigDecimal) : n
}

Map currentCfg() {
    List<Map> stages = []
    for (int n = 1; n <= stageCount(); n++) {
        Map d = STAGE_DEFAULTS[n - 1]
        stages << [on: numOr(stageSetting(n, "On"), d.on), off: numOr(stageSetting(n, "Off"), d.off),
                   dwell: numOr(stageSetting(n, "Dwell"), d.dwell),
                   switches: stageDevices(n).collect { it.id.toString() }]
    }
    return [stages: stages, offset: settings.offsetPpm == null ? 0G : numOrNull(settings.offsetPpm),
            holdMin: numOr(holdMinutes, 60),
            adv: [ppm: numOr(advPpm, 1400), minutes: numOr(advMinutes, 20), repeatHours: numOr(advRepeatHours, 3), allClear: advAllClear == true],
            rh: [pct: numOr(rhPct, 30), hours: numOr(rhHours, 12)],
            unitMs: testFast ? 1000L : 60000L]
}

List<DeviceWrapper> stageDevices(int n) { (stageSetting(n, "Switches") ?: []) as List<DeviceWrapper> }

List<DeviceWrapper> stageDevices() {
    List<DeviceWrapper> all = []
    for (int n = 1; n <= stageCount(); n++) all.addAll(stageDevices(n))
    return all
}

// 0-based stage index of a switch, -1 when it is in none.
int stageOf(String id) {
    for (int n = 1; n <= stageCount(); n++) if (stageDevices(n).any { it.id.toString() == id }) return n - 1
    return -1
}

// ── Inputs ────────────────────────────────────────────────────────────

// A reading's time is the device's last activity, so a sensor repeating the same value is not silent.
List<Map> readings(List devs, String attr) {
    List<Map> out = []
    (devs ?: []).each { DeviceWrapper d ->
        State s = d.currentState(attr)
        Date seen = d.getLastActivity() ?: s?.date
        if (s?.value != null && seen != null) out << [v: s.value, t: seen.time]
    }
    return out
}

List<String> switchValues(List devs) { (devs ?: []).collect { it.currentValue("switch") as String } }

boolean offsetApplies() { anySwitchCondition(switchValues(offsetWhenOn), switchValues(offsetWhenOff)) }

boolean safetyAlarm() {
    return (smokeDetectors ?: []).any { it.currentValue("smoke") == "detected" } ||
           (coDetectors ?: []).any { it.currentValue("carbonMonoxide") == "detected" }
}

void inputHandler(evt) {
    checkVersion()
    logEvt "${evt.displayName} ${evt.name} ${evt.value}"
    evaluateAir(evt.name as String)
}

void wakeHandler() {
    checkVersion()
    evaluateAir("timer")
}

// ── Evaluation ────────────────────────────────────────────────────────

String currentStopReason() {
    return stopReason(safetyAlarm(), state.enabled == true,
                      modeActive(location.mode as String, location.currentMode?.id?.toString(), (activeModes ?: []) as List),
                      anySwitchCondition(switchValues(pauseWhenOn), switchValues(pauseWhenOff)))
}

void evaluateAir(String why) {
    Map cfg = currentCfg()
    if (validateCfg(cfg)) {
        releaseAll()
        state.stages = resized([], (cfg.stages as List).size())
        return
    }
    long t = now()
    logDebug "evaluateAir (${why})"
    BigDecimal co2 = combineReadings(readings(co2Sensors, "carbonDioxide"), (co2Mode ?: "highest") as String, t, STALE_MS)
    BigDecimal rh = rhSensors ? combineReadings(readings(rhSensors, "humidity"), "lowest", t, STALE_MS) : null
    String reason = currentStopReason()
    boolean offOn = offsetApplies()
    int n = (cfg.stages as List).size()
    List<Map> st = resized((state.stages ?: []) as List<Map>, n)
    Long wake = null
    if (reason != state.lastStop) logInfo(reason ? STOP_TEXT[reason] as String : "managing air")
    if (reason == "safety") {
        st = resized([], n)
    } else {
        if (state.lastStop == "safety") {
            state.claims = [:]
            logInfo "detectors clear: claims dropped"
        }
        if (reason) {
            releaseAll()
            st = resized([], n)
        } else {
            if (co2 == null && state.co2Missing != true) logWarn "no CO2 reading in the last 2 hours: stages held"
            state.co2Missing = co2 == null
            Map r = stepStages(st, co2, t, cfg, offOn)
            st = r.stages as List<Map>
            wake = r.wakeAt as Long
            state.stages = st
            (r.actions as List<Map>).each { Map a -> applyAction(a) }
        }
    }
    state.lastStop = reason
    state.stages = st
    state.co2 = co2
    BigDecimal topOff = numOrNull((cfg.stages as List<Map>).last().off) + (offOn ? numOrNull(cfg.offset) : 0G)
    String wmode = reason == null ? "run" : (reason == "pause" ? "pause" : "stop")
    Map w = stepWindow(state.window as Map, co2, allRunning(st), wmode, topOff, t, cfg)
    state.window = w.w
    sendNote(windowMessage(app.getLabel(), w.notify as String, co2))
    wake = earliest(wake, w.wakeAt as Long)
    if (!rhSensors) state.rh = null
    if (rhSensors) {
        boolean seasonOk = !seasonDevice || seasonDevice.currentValue("season") == "winter"
        Map h = stepHumidity(state.rh as Map, rh, seasonOk, t, cfg)
        state.rh = h.h
        if (h.notify == "raise") sendNote(humidityMessage(app.getLabel(), rh, (cfg.rh as Map).hours))
        wake = earliest(wake, h.wakeAt as Long)
    }
    publish(co2, rh, st)
    if (wake == null) unschedule("wakeHandler")
    else runInMillis(Math.max(wake - t, 500L), "wakeHandler")
}

// ── Switches and claims ───────────────────────────────────────────────

void applyAction(Map a) {
    int n = (a.stage as int) + 1
    Map claims = (state.claims ?: [:]) as Map
    logCmd "stage ${n} ${a.cmd}"
    stageDevices(n).each { DeviceWrapper d ->
        if (a.cmd == "on") {
            if (d.currentValue("switch") == "on") logDebug "${d.displayName} already on, not claimed"
            else switchCmd(d, "on")
        } else if (claims[d.id.toString()]) switchCmd(d, "off")
        else logDebug "${d.displayName} not turned on by this app, left as is"
    }
}

void switchCmd(DeviceWrapper d, String cmd) {
    String id = d.id.toString()
    Map p = (state.pending ?: [:]) as Map
    p[id] = [cmd: cmd, at: now()]
    state.pending = p
    Map c = (state.claims ?: [:]) as Map
    if (cmd == "on") c[id] = true
    else c.remove(id)
    state.claims = c
    if (cmd == "on") d.on()
    else d.off()
}

void dropClaim(String id) {
    Map c = (state.claims ?: [:]) as Map
    if (c.remove(id) != null) state.claims = c
}

void releaseAll() {
    state.reasserted = [:]
    Map c = (state.claims ?: [:]) as Map
    if (!c) return
    stageDevices().each { DeviceWrapper d -> if (c[d.id.toString()]) switchCmd(d, "off") }
    state.claims = [:]
}

void stageSwitchHandler(evt) {
    checkVersion()
    String id = evt.deviceId.toString()
    boolean physical = evt.type == "physical"
    logEvt "${evt.displayName} ${evt.value}${physical ? ' (physical)' : ''}"
    Map p = (state.pending ?: [:]) as Map
    Map mine = p[id] as Map
    String pending = mine && now() - (mine.at as long) < PENDING_MS ? mine.cmd as String : null
    if (mine) {
        p.remove(id)
        state.pending = p
    }
    int i = stageOf(id)
    if (i < 0) return
    List<Map> st = resized((state.stages ?: []) as List<Map>, stageCount())
    Map re = (state.reasserted ?: [:]) as Map
    String live = currentStopReason()
    String act = switchEventAction(evt.value as String, physical, pending, st[i].s as String, (state.lastStop != null || live != null),
                                   re[id] as Long, now(), REASSERT_GAP_MS)
    if (act != "own" && evt.value == "off") dropClaim(id)
    if (act == "hold") {
        long mins = numOr(holdMinutes, 60).longValue()
        st[i] = [s: "held", since: null, heldUntil: now() + mins * (testFast ? 1000L : 60000L)]
        state.stages = st
        logInfo "${evt.displayName} turned off by hand: stage ${i + 1} stays off for ${mins} minutes"
        evaluateAir("manual off")
    } else if (act == "reassert") {
        re[id] = now()
        state.reasserted = re
        logInfo "${evt.displayName} turned off by another source while stage ${i + 1} runs: turning it back on"
        switchCmd(evt.device as DeviceWrapper, "on")
    } else if (act == "throttled") {
        logWarn "${evt.displayName} turned off again within 5 minutes of being turned back on: left off"
    }
    if (live != null && live != state.lastStop) evaluateAir("stop")
}

// ── Air device ────────────────────────────────────────────────────────

String dni() { "iaq-${app.id}" }

String deviceLabel() { app.getLabel() == "Indoor Air Quality Controller" ? "Indoor Air" : "${app.getLabel()} Air" }

ChildDeviceWrapper airDevice() {
    ChildDeviceWrapper d = getChildDevice(dni())
    if (!d) {
        d = addChildDevice("iamtrep", "Indoor Air", dni(), [name: "Indoor Air", label: deviceLabel(), isComponent: true])
        logCfg "created ${d.displayName}, switched off"
    }
    return d
}

Map airCommand(Map req) {
    checkVersion()
    String c = req?.command as String
    if (c != "on" && c != "off") return [ok: false, error: "unknown command ${c}"]
    state.enabled = c == "on"
    airDevice().updateStatus(["switch": c])
    evaluateAir("Air device ${c}")
    return [ok: true]
}

void publish(BigDecimal co2, BigDecimal rh, List<Map> st) {
    Map a = ["switch": state.enabled == true ? "on" : "off", ventilationStage: stageLevel(st),
             windowAdvisory: (state.window as Map)?.active ? "active" : "inactive",
             lowHumidityAdvisory: (state.rh as Map)?.active ? "active" : "inactive"]
    if (co2 != null) a.carbonDioxide = co2.setScale(0, BigDecimal.ROUND_HALF_UP)
    if (rh != null) a.humidity = rh
    airDevice().updateStatus(a)
}

void sendNote(String msg) {
    if (!msg) return
    logInfo msg
    (notifyDevices ?: []).each { it.deviceNotification(msg) }
}

// ── Core (pure) ───────────────────────────────────────────────────────
// Self-contained: arguments in, values out. No settings, state, devices or logs.
// tests/test_core.groovy parses and runs this block off-hub.

@CompileStatic
BigDecimal numOrNull(Object o) {
    if (o instanceof Number) return new BigDecimal(o.toString())
    if (o instanceof String && ((String) o).trim().isBigDecimal()) return new BigDecimal(((String) o).trim())
    return null
}

@CompileStatic
String fmtInt(Object n) {
    BigDecimal b = numOrNull(n)
    return b == null ? '' : b.setScale(0, BigDecimal.ROUND_HALF_UP).toPlainString()
}

// readings: [[v: value, t: epoch ms]]. A reading older than staleMs is left out.
// how: 'highest', 'average' (rounded to a whole number) or 'lowest'. null when nothing is left.
@CompileStatic
BigDecimal combineReadings(List<Map> readings, String how, long now, long staleMs) {
    List<BigDecimal> vals = []
    for (Map r : readings) {
        BigDecimal v = numOrNull(r?.v)
        if (v != null && r.t != null && now - (r.t as long) <= staleMs) vals << v
    }
    if (vals.isEmpty()) return null
    if (how == 'average') {
        BigDecimal sum = 0G
        for (BigDecimal v : vals) sum += v
        return sum.divide(new BigDecimal(vals.size()), 0, BigDecimal.ROUND_HALF_UP)
    }
    return how == 'lowest' ? vals.min() : vals.max()
}

@CompileStatic
boolean anySwitchCondition(List<String> whenOn, List<String> whenOff) {
    return (whenOn ?: []).contains('on') || (whenOff ?: []).contains('off')
}

// chosen holds mode ids, as a mode input stores them
@CompileStatic
boolean modeActive(String mode, String modeId, List chosen) {
    return chosen ? chosen*.toString().contains(modeId) : mode != 'Away'
}

// Why the app is not managing air, most important first; null when it is.
@CompileStatic
String stopReason(boolean safety, boolean enabled, boolean modeOk, boolean paused) {
    if (safety) return 'safety'
    if (!enabled) return 'disabled'
    if (!modeOk) return 'mode'
    if (paused) return 'pause'
    return null
}

@CompileStatic
Long earliest(Long a, Long b) {
    if (a == null) return b
    if (b == null) return a
    return Math.min(a, b)
}

@CompileStatic
List<Map> resized(List<Map> cur, int n) {
    List<Map> out = []
    for (int i = 0; i < n; i++) {
        Map c = (cur != null && i < cur.size() && cur[i] != null) ? cur[i] : [:]
        out << [s: (c.s ?: 'off') as String, since: c.since as Long, heldUntil: c.heldUntil as Long]
    }
    return out
}

@CompileStatic
List<String> validateCfg(Map cfg) {
    List<String> errs = []
    List<Map> st = cfg.stages as List<Map>
    if (!st || st.size() > 4) { errs << 'Choose 1 to 4 stages'; return errs }
    Map<String, Integer> seen = [:]
    for (int i = 0; i < st.size(); i++) {
        int n = i + 1
        BigDecimal on = numOrNull(st[i].on)
        BigDecimal off = numOrNull(st[i].off)
        BigDecimal dw = numOrNull(st[i].dwell)
        BigDecimal prevOn = i > 0 ? numOrNull(st[i - 1].on) : null
        if (on == null) errs << "Stage ${n}: enter the on threshold".toString()
        if (off == null) errs << "Stage ${n}: enter the off threshold".toString()
        if (on != null && off != null && off >= on) errs << "Stage ${n}: the off threshold must be below the on threshold".toString()
        if (dw == null || dw < 1) errs << "Stage ${n}: enter a time of at least 1 minute".toString()
        if (on != null && prevOn != null && on <= prevOn) errs << "Stage ${n}: the on threshold must be above stage ${i}'s".toString()
        List sw = (st[i].switches ?: []) as List
        if (!sw) errs << "Stage ${n}: select at least one switch".toString()
        for (Object id : sw) {
            String k = id.toString()
            if (seen.containsKey(k)) errs << "A switch is selected in stages ${seen[k]} and ${n}".toString()
            else seen[k] = n
        }
    }
    if (numOrNull(cfg.offset) == null) errs << 'Threshold offset: enter a number'
    return errs
}

long dwellMs(Map stageDef, long unitMs) { return ((numOrNull(stageDef.dwell) ?: 1G) * unitMs).longValue() }

// Stage states: [s: 'off' | 'engaged' | 'held', since: epoch ms the value crossed toward a change
// (null when it has not), heldUntil: epoch ms]. One call evaluates the ladder once.
// Returns [stages: List<Map>, actions: [[stage: index, cmd: 'on' | 'off']], wakeAt: Long or null].
@CompileStatic
Map stepStages(List<Map> cur, BigDecimal co2, long now, Map cfg, boolean offsetOn) {
    List<Map> defs = cfg.stages as List<Map>
    int n = defs.size()
    BigDecimal o = offsetOn ? (numOrNull(cfg.offset) ?: 0G) : 0G
    long unit = cfg.unitMs as long
    List<Map> st = resized(cur, n)
    List<Map> actions = []
    // A hold that ended: back on while CO2 is above the on threshold, or while a stage above
    // still runs (stages release from the top down).
    for (int i = n - 1; i >= 0; i--) {
        Map x = st[i]
        if (x.s != 'held' || now < (x.heldUntil as long)) continue
        boolean above = i < n - 1 && st[i + 1].s != 'off'
        boolean below = i == 0 || st[i - 1].s != 'off'
        boolean back = above || (co2 != null && below && co2 > numOrNull(defs[i].on) + o)
        x.s = back ? 'engaged' : 'off'
        x.since = null
        x.heldUntil = null
        actions << [stage: i, cmd: back ? 'on' : 'off']
    }
    if (co2 != null) {
        // Release from the top down: a stage goes off only once the stage above is off.
        for (int i = n - 1; i >= 0; i--) {
            Map x = st[i]
            if (x.s != 'engaged') continue
            if (co2 < numOrNull(defs[i].off) + o) {
                if (x.since == null) x.since = now
                boolean upperOff = i == n - 1 || st[i + 1].s == 'off'
                if (upperOff && now - (x.since as long) >= dwellMs(defs[i], unit)) {
                    x.s = 'off'
                    x.since = null
                    actions << [stage: i, cmd: 'off']
                }
            } else x.since = null
        }
        // Engage from the bottom up: a stage goes on only once the stage below runs.
        for (int i = 0; i < n; i++) {
            Map x = st[i]
            if (x.s != 'off') continue
            if (co2 > numOrNull(defs[i].on) + o) {
                if (x.since == null) x.since = now
                boolean lowerOn = i == 0 || st[i - 1].s != 'off'
                if (lowerOn && now - (x.since as long) >= dwellMs(defs[i], unit)) {
                    x.s = 'engaged'
                    x.since = null
                    actions << [stage: i, cmd: 'on']
                }
            } else x.since = null
        }
    }
    // Earliest future change. A dwell already elapsed is waiting on another stage, whose own
    // timer wakes the app.
    Long wake = null
    for (int i = 0; i < n; i++) {
        Map x = st[i]
        Long t = x.s == 'held' ? x.heldUntil as Long : (x.since != null ? (x.since as long) + dwellMs(defs[i], unit) : null)
        if (t != null && t > now) wake = earliest(wake, t)
    }
    return [stages: st, actions: actions, wakeAt: wake]
}

@CompileStatic
int stageLevel(List<Map> st) {
    int level = 0
    for (int i = 0; i < (st ?: []).size(); i++) if (st[i]?.s != null && st[i].s != 'off') level = i + 1
    return level
}

@CompileStatic
boolean allRunning(List<Map> st) {
    if (!st) return false
    for (Map x : st) if (x?.s == null || x.s == 'off') return false
    return true
}

// What a stage switch event means. pending: the command this app sent and has not seen come back.
// 'own' our command arriving; 'hold' a physical off on a running stage; 'reassert' another source
// turned a running stage's switch off; 'throttled' the same, too soon after the last re-assertion;
// 'ignore' anything else.
@CompileStatic
String switchEventAction(String value, boolean physical, String pending, String stageState, boolean stopped,
                         Long lastReassert, long now, long gapMs) {
    if (pending != null && pending == value) return 'own'
    if (value != 'off' || stageState != 'engaged' || stopped) return 'ignore'
    if (physical) return 'hold'
    if (lastReassert != null && now - lastReassert < gapMs) return 'throttled'
    return 'reassert'
}

// w: [active, since, lastSent]. mode: 'run', 'pause' (no stage may run, CO2 alone decides) or 'stop'.
// Raised once CO2 has stayed above the threshold with every stage running (or paused) for the
// duration; repeated every repeatHours; cleared below the top stage's off threshold.
Map stepWindow(Map w, BigDecimal co2, boolean allRunning, String mode, BigDecimal topOff, long now, Map cfg) {
    Map adv = cfg.adv as Map
    long unit = cfg.unitMs as long
    long dur = (numOrNull(adv.minutes) * unit).longValue()
    long rep = (numOrNull(adv.repeatHours) * 60 * unit).longValue()
    Map x = [active: w?.active == true, since: w?.since as Long, lastSent: w?.lastSent as Long]
    if (mode == 'stop') return [w: [active: false, since: null, lastSent: null], notify: null, wakeAt: null]
    if (co2 == null) return [w: x, notify: null, wakeAt: null]
    String note = null
    if (!x.active) {
        if (co2 > numOrNull(adv.ppm) && (mode == 'pause' || allRunning)) {
            if (x.since == null) x.since = now
            if (now - (x.since as long) >= dur) {
                x = [active: true, since: null, lastSent: now]
                note = 'raise'
            }
        } else x.since = null
    } else if (co2 < topOff) {
        x = [active: false, since: null, lastSent: null]
        note = adv.allClear == true ? 'clear' : null
    } else if (now - (x.lastSent as long) >= rep) {
        x.lastSent = now
        note = 'repeat'
    }
    Long wake = x.active ? (x.lastSent as long) + rep : (x.since != null ? (x.since as long) + dur : null)
    return [w: x, notify: note, wakeAt: wake]
}

// h: [active, since, recSince]. Raised once the reading has stayed below the threshold for the
// duration; cleared once it has stayed 3 points above for 1 hour, or when the season does not allow it.
Map stepHumidity(Map h, BigDecimal rh, boolean seasonOk, long now, Map cfg) {
    Map r = cfg.rh as Map
    long unit = cfg.unitMs as long
    BigDecimal pct = numOrNull(r.pct)
    long dur = (numOrNull(r.hours) * 60 * unit).longValue()
    long rec = 60 * unit
    Map x = [active: h?.active == true, since: h?.since as Long, recSince: h?.recSince as Long]
    if (!seasonOk) return [h: [active: false, since: null, recSince: null], notify: null, wakeAt: null]
    if (rh == null) return [h: x, notify: null, wakeAt: null]
    String note = null
    if (!x.active) {
        if (rh < pct) {
            if (x.since == null) x.since = now
            if (now - (x.since as long) >= dur) {
                x = [active: true, since: null, recSince: null]
                note = 'raise'
            }
        } else x.since = null
    } else if (rh >= pct + 3) {
        if (x.recSince == null) x.recSince = now
        if (now - (x.recSince as long) >= rec) x = [active: false, since: null, recSince: null]
    } else x.recSince = null
    Long wake = x.since != null ? (x.since as long) + dur : (x.recSince != null ? (x.recSince as long) + rec : null)
    return [h: x, notify: note, wakeAt: wake]
}

@CompileStatic
String windowMessage(String label, String kind, BigDecimal co2) {
    if (kind == 'raise' || kind == 'repeat') return "${label}: CO2 at ${fmtInt(co2)} ppm despite ventilation. Consider opening a window.".toString()
    if (kind == 'clear') return "${label}: CO2 back to ${fmtInt(co2)} ppm.".toString()
    return null
}

@CompileStatic
String humidityMessage(String label, BigDecimal rh, Object hours) {
    return "${label}: indoor humidity at ${fmtInt(rh)}% for ${fmtInt(hours)} h.".toString()
}

// ── End core ──────────────────────────────────────────────────────────

// ── Logging (app) ─────────────────────────────────────────────────────
//   ⬇️ Evt  ⬆️ Cmd  🔧 Cfg  🌐 Net  ⏰ Sched  🏷️ Ver  ·  ⚠️ Warn  🛑 Error  🔬 Trace
private String logp(String e) { "${e} ${app.getLabel()}: " }

void logEvt  (String m) { if (debugEnable) log.debug logp('⬇️') + m }
void logCmd  (String m) { if (txtEnable != false) log.info  logp('⬆️') + m }
void logCfg  (String m) { if (txtEnable != false) log.info  logp('🔧') + m }
void logNet  (String m) { if (debugEnable) log.debug logp('🌐') + m }
void logSched(String m) { if (debugEnable) log.debug logp('⏰') + m }
void logVer  (String m) { log.warn  logp('🏷️') + m }

void logWarn (String m) { log.warn  logp('⚠️') + m }
void logError(String m) { log.error logp('🛑') + m }
void logTrace(String m) { if (traceEnable) log.trace logp('🔬') + m }
void logInfo (String m) { if (txtEnable != false) log.info  "${app.getLabel()}: ${m}" }
void logDebug(String m) { if (debugEnable) log.debug "${app.getLabel()}: ${m}" }

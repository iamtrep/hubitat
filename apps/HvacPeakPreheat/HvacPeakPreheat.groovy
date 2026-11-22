// Copyright (c) 2026 PJ
// SPDX-License-Identifier: MIT

/*
 HVAC Peak Pre-heat

 Holds a thermostat through a Hydro-Québec Winter Credit peak event, by the plan for the event's
 anchor window. The phase comes from a Hydro peak device (Hydro-Québec Peak Period Manager), the
 plan from a Hilo Winter Credit device. The app only holds the heating setpoint and only releases
 a hold it placed, with resumeProgram.

 Level-triggered: every trigger re-runs the whole decision from the current inputs and whether
 the app owns the hold, so a missed event, a lost timer or a reboot is corrected at the next
 evaluation. The decision is the pure decide() in the core block, tested off the hub.
*/
import com.hubitat.app.DeviceWrapper
import groovy.transform.CompileStatic
import groovy.transform.Field

@Field static final String APP_NAME = "HVAC Peak Pre-heat"
@Field static final String CODE_VERSION = "0.1.0"
@Field static final Integer DEBUG_AUTO_OFF_MINUTES = 30

@Field static final List<String> PHASE_ATTRS = ["phase", "window", "eventDate"]
// Every plan attribute re-runs the decision.
@Field static final List<String> PLAN_ATTRS = ["anchorPlan", "anchorTarget", "anchorUsed", "anchorStop", "anchorChecked",
                                               "advice", "keepUse", "eventUsed", "eventDate", "window"]
// Only the plan device's stop check feeds the silence fallback: anchorChecked changes each minute
// of an announced "at most" anchor window while that check runs. Other attributes (anchorUsed from
// the power stream) keep arriving when the check doesn't run, so they don't count as a heartbeat.
@Field static final List<String> HEARTBEAT_ATTRS = ["anchorChecked", "anchorStop"]
@Field static final int MODE_SETTLE_SECONDS = 5     // lets another app's resumeProgram on Away -> Home land first
// A command's events past its 2nd are applied 2 per 100-130 ms (worst stall seen 1.3 s), and each event's handler
// re-arms the settle, so 2 s outlasts any gap between them: 8 peak attributes end about 0.4 s after the first.
@Field static final long EVENT_SETTLE_MS = 2000L
@Field static final long SETTLE_GRACE_MS = 5000L    // a settle job this late is taken as lost
@Field static final int VERIFY_SECONDS = 30
@Field static final String DEFAULT_NIGHT = "22:30"
@Field static final int DEFAULT_SILENCE_MINUTES = 10

@Field static final Map<String, Map<String, BigDecimal>> DEFAULT_HOLDS = [
    C: [nightHold: 20.5G, preheatHold: 22.5G, amStopHold: 18.0G, pmStopHold: 19.5G, eventHold: 18.0G, keepHold: 20.5G],
    F: [nightHold: 69G, preheatHold: 72G, amStopHold: 64G, pmStopHold: 67G, eventHold: 64G, keepHold: 69G]
]
@Field static final Map<String, String> HOLD_TITLES = [
    nightHold  : "Night before a morning event, from the nightly time",
    preheatHold: "Pre-heat: anchor window and the gap before the event",
    amStopHold : "Anchor window, after an \"at most\" stop, morning event",
    pmStopHold : "Anchor window, after an \"at most\" stop, evening event",
    eventHold  : "During the event",
    keepHold   : "During the event, advice keep, until the event has used keepUse"
]

// singleThreaded: reevaluate() reads the owned flag, commands the thermostat and writes the flag back.
// A phase event (release) and a plan event (hold) evaluated at once can interleave so that a hold
// lands after the release while the flag reads false, and that hold would never be released.
// Handlers are short and the event rate is about one a minute, so the flag costs nothing.
definition(
    name: APP_NAME,
    namespace: "iamtrep",
    author: "pj",
    description: "Holds a thermostat through a Hydro-Québec Winter Credit peak event, by the anchor window's plan",
    menu: "Automations",
    category: "Green Living",
    singleInstance: false,
    singleThreaded: true,
    importUrl: "https://raw.githubusercontent.com/iamtrep/hubitat/refs/heads/main/apps/HvacPeakPreheat/HvacPeakPreheat.groovy",
    documentationLink: "https://github.com/iamtrep/hubitat/tree/main/apps/HvacPeakPreheat",
    iconUrl: "",
    iconX2Url: "",
    iconX3Url: ""
)

preferences {
    page(name: "mainPage")
}

Map mainPage() {
    String scale = scale()
    dynamicPage(name: "mainPage", title: "${APP_NAME} v${CODE_VERSION}", install: true, uninstall: true) {
        if (state.last) {
            section("Status") { paragraph statusText() }
        }
        section("Inputs") {
            input "phaseDevice", "capability.actuator", title: "Hydro peak device (phase, window, eventDate)", required: true
            input "planDevice", "capability.sensor", title: "Winter credit plan device (anchorPlan, anchorStop, advice, keepUse, eventUsed)", required: true
            input "thermostat", "capability.thermostat", title: "Thermostat to hold", required: true
            input "awayModes", "mode", title: "Away modes: another app owns the thermostat, no hold (none selected: the mode named Away)",
                  multiple: true, required: false
        }
        section("Holds (°${scale})") {
            HOLD_TITLES.each { String k, String t ->
                input k, "decimal", title: t, defaultValue: DEFAULT_HOLDS[scale][k], required: false, width: 6
            }
        }
        section("Timing") {
            input "nightTime", "time", title: "Nightly start before a morning event, the thermostat's Sleep start (default ${DEFAULT_NIGHT})", required: false
            input "silenceMinutes", "number", title: "Stop an \"at most\" pre-heat when the plan device's stop check (anchorChecked) has been silent this many minutes",
                  defaultValue: DEFAULT_SILENCE_MINUTES, range: "1..60", required: false
        }
        section("Options") {
            label title: "App name", required: false
            input name: "txtEnable", type: "bool", title: "Enable info logging", defaultValue: true
            input name: "debugEnable", type: "bool", title: "Enable debug logging", defaultValue: false, submitOnChange: true
            if (settings.debugEnable) {
                input name: "traceEnable", type: "bool", title: "Enable trace logging", defaultValue: false
                paragraph "Debug logging turns off automatically after ${DEBUG_AUTO_OFF_MINUTES} minutes."
            }
        }
    }
}

private String statusText() {
    Map l = state.last as Map
    TimeZone tz = location.timeZone
    StringBuilder s = new StringBuilder()
    s.append("Phase: ${phaseDevice?.currentValue('phase') ?: '?'} ${phaseDevice?.currentValue('window') ?: ''}<br/>")
    s.append("Plan: ${planDevice?.currentValue('anchorPlan') ?: 'none'}, stop ${planDevice?.currentValue('anchorStop') ?: '?'}, advice ${planDevice?.currentValue('advice') ?: '?'}<br/>")
    s.append("Decision: ${l.reason} (row ${l.rule})")
    if (l.setpoint != null) s.append(", ${l.setpoint} °${scale()}")
    s.append(" at ${new Date(l.at as long).format('yyyy-MM-dd HH:mm:ss', tz)}<br/>")
    s.append("Hold placed by this app: ${state.owned ? 'yes' : 'no'}")
    if (state.wakeAt) s.append("<br/>\"At most\" pre-heat stops at ${new Date(state.wakeAt as long).format('HH:mm:ss', tz)} unless the plan device's stop check answers")
    return s.toString()
}

// ── Lifecycle ─────────────────────────────────────────────────────────

void installed() {
    initialize()
}

void updated() {
    unsubscribe()
    unschedule()
    initialize()
}

// A hold this app placed is released when the app is removed.
void uninstalled() {
    if (state.owned && thermostat) {
        thermostat.resumeProgram()
        logCmd "${thermostat.displayName}: resumeProgram (app removed)"
    }
}

void initialize() {
    checkVersion(false)
    if (settings.debugEnable || settings.traceEnable) runIn(DEBUG_AUTO_OFF_MINUTES * 60, "logsOff")
    if (!phaseDevice || !planDevice || !thermostat) {
        logWarn "select the Hydro peak device, the plan device and the thermostat"
        return
    }
    PHASE_ATTRS.each { subscribe(phaseDevice, it, "phaseHandler") }
    PLAN_ATTRS.each { subscribe(planDevice, it, "planHandler") }
    subscribe(location, "mode", "modeHandler")
    subscribe(location, "systemStart", "startHandler")
    int m = nightMinutes()
    schedule("0 ${m % 60} ${m.intdiv(60)} * * ?", "nightlyHandler")
    state.remove("settleDue")      // updated() unscheduled any settle; this evaluation stands for it
    reevaluate("saved")
}

// ── Handlers ──────────────────────────────────────────────────────────

// Device events are evaluated once they settle. Both devices send several attributes in one command, and on a
// hub the first handler runs before the later attributes are applied (a night hold was sent at a PM event's end
// with the next event's date not yet in).
void phaseHandler(evt) {
    checkVersion()
    logEvt "${evt.displayName} ${evt.name} ${evt.value}"
    settleThenEvaluate("${evt.name} ${evt.value}")
}

void planHandler(evt) {
    checkVersion()
    if (evt.name in HEARTBEAT_ATTRS) state.beatAt = now()
    logEvt "${evt.displayName} ${evt.name} ${evt.value}"
    settleThenEvaluate("${evt.name} ${evt.value}")
}

private void settleThenEvaluate(String why) {
    state.settleDue = now() + EVENT_SETTLE_MS
    runInMillis(EVENT_SETTLE_MS, "eventsSettled", [data: [why: why]])
}

void eventsSettled(Map data) {
    checkVersion()
    state.remove("settleDue")
    reevaluate((data?.why ?: "device events") as String)
}

// While device events settle, a timed evaluation would read a command half applied; the settle evaluates for it.
private boolean settling(String why) {
    Long due = state.settleDue as Long
    if (due == null || now() >= due + SETTLE_GRACE_MS) return false
    logSched "${why}: device events settling, evaluated with them"
    return true
}

void modeHandler(evt) {
    checkVersion()
    logEvt "mode ${evt.value}"
    runIn(MODE_SETTLE_SECONDS, "modeSettled")
}

void modeSettled() {
    checkVersion()
    if (settling("mode ${location.mode}")) return
    reevaluate("mode ${location.mode}")
}

void nightlyHandler() {
    checkVersion()
    if (settling("nightly time")) return
    reevaluate("nightly time")
}

void silenceCheck() {
    checkVersion()
    if (settling("plan device silence check")) return
    reevaluate("plan device silence check")
}

// A settle job that fell due while the hub was down was dropped: this evaluation stands for it (its settleDue is
// minutes old by now, so settling() is false). A settle armed since the start runs instead.
void startHandler(evt) {
    checkVersion()
    if (settling("hub start")) return
    state.remove("settleDue")
    reevaluate("hub start")
}

// Warns when the thermostat doesn't show the setpoint sent; resending is Hubitat's Command Retry.
void verifyHold() {
    checkVersion()
    Map p = state.pending as Map
    state.remove("pending")
    if (!p || !state.owned || !thermostat) return
    BigDecimal cur = toNum(thermostat.currentValue("heatingSetpoint", true))
    if (differs(cur, toNum(p.setpoint))) {
        logWarn "${thermostat.displayName}: heating setpoint ${cur} ${VERIFY_SECONDS} s after setting ${p.setpoint}"
    }
}

// ── Evaluation ────────────────────────────────────────────────────────

void reevaluate(String why) {
    if (!phaseDevice || !planDevice || !thermostat) return
    long t = now()
    String phase = phaseDevice.currentValue("phase") as String
    if (phase == "anchor") {
        if (state.anchorSince == null) state.anchorSince = t
    } else {
        state.remove("anchorSince")
    }
    Map inp = [
        now          : t,
        tz           : location.timeZone,
        away         : awayMatch(location.mode as String, location.currentMode?.id?.toString(), (settings.awayModes ?: []) as List),
        phase        : phase,
        window       : phaseDevice.currentValue("window"),
        eventDate    : phaseDevice.currentValue("eventDate"),
        plan         : planDevice.currentValue("anchorPlan"),
        anchorStop   : planDevice.currentValue("anchorStop"),
        planEventDate: planDevice.currentValue("eventDate"),
        planWindow   : planDevice.currentValue("window"),
        advice       : planDevice.currentValue("advice"),
        eventUsed    : toNum(planDevice.currentValue("eventUsed")),
        keepUse      : toNum(planDevice.currentValue("keepUse")),
        quietRef     : quietRef(t)
    ]
    Map d = decide(inp, holdConfig())
    logDebug "${why}: row ${d.rule} ${d.action}${d.setpoint != null ? ' ' + d.setpoint : ''} (${d.reason})"
    apply(d)
    if (d.wakeAt != null) {
        state.wakeAt = d.wakeAt
        runIn(Math.max(1L, ((d.wakeAt as long) - t + 999L).intdiv(1000L)) as Long, "silenceCheck")
    } else if (state.wakeAt != null) {
        state.remove("wakeAt")
        unschedule("silenceCheck")
    }
    Map last = state.last as Map
    if (last?.rule != d.rule || last?.action != d.action || last?.setpoint != d.setpoint || last?.reason != d.reason) {
        logInfo "${d.reason} (${why})"
    }
    state.last = [rule: d.rule, action: d.action, setpoint: d.setpoint, reason: d.reason, at: t]
}

private void apply(Map d) {
    switch (d.action) {
        case "disown":
            if (state.owned) logInfo "away mode: the hold on ${thermostat.displayName} is no longer this app's"
            state.owned = false
            break
        case "release":
            if (state.owned) {
                thermostat.resumeProgram()
                logCmd "${thermostat.displayName}: resumeProgram"
            }
            state.owned = false
            break
        case "hold":
            BigDecimal sp = d.setpoint as BigDecimal
            BigDecimal cur = toNum(thermostat.currentValue("heatingSetpoint", true))
            // Sent when the setpoint differs, and also when the hold isn't ours yet. An Ecobee makes a hold
            // of any setpoint written, even an equal one; skipping the write would leave the program in
            // charge, and it would move the setpoint later. Only resumeProgram returns to the schedule.
            if (!state.owned || differs(cur, sp)) {
                thermostat.setHeatingSetpoint(sp)
                logCmd "${thermostat.displayName}: setHeatingSetpoint ${sp} (was ${cur})"
                state.pending = [setpoint: sp, at: now()]
                runIn(VERIFY_SECONDS, "verifyHold")
            }
            state.owned = true
            break
    }
}

// The silence fallback counts from the later of the last stop-check heartbeat and the anchor window's start.
private Long quietRef(long t) {
    Long heard = state.beatAt as Long
    Long since = state.anchorSince as Long
    if (heard == null && since == null) return t
    return Math.max(heard ?: 0L, since ?: 0L)
}

private String scale() { (location.temperatureScale ?: "C") as String }

private Map holdConfig() {
    Map<String, BigDecimal> d = DEFAULT_HOLDS[scale()] ?: DEFAULT_HOLDS.C
    Map cfg = [nightMins: nightMinutes(), silenceMs: ((settings.silenceMinutes ?: DEFAULT_SILENCE_MINUTES) as long) * 60000L]
    d.each { String k, BigDecimal v -> cfg[k] = settings[k] != null ? toNum(settings[k]) : v }
    return cfg
}

private int nightMinutes() {
    String s = settings.nightTime as String
    if (!s) return minutesOf(DEFAULT_NIGHT)
    Date d = toDateTime(s)
    TimeZone tz = location.timeZone
    return (d.format("H", tz) as int) * 60 + (d.format("m", tz) as int)
}

// ── Core (pure) ───────────────────────────────────────────────────────
// No hub calls below this line, down to "End core": tests/test_core.groovy runs this block off the hub.

// The decision, first match wins. inp: now (ms), tz, away, phase, window, eventDate (the phase
// device's), plan (anchorPlan), anchorStop, planEventDate, planWindow, advice, eventUsed, keepUse,
// quietRef (ms the stop-check silence counts from: the later of its last heartbeat and the
// window's start). cfg: nightMins, silenceMs and the six holds.
// Returns rule (1-7), action (disown, release, hold), setpoint, reason, and wakeAt (ms) while an
// "at most" pre-heat waits on the plan device.
static Map decide(Map inp, Map cfg) {
    long t = inp.now as long
    TimeZone tz = inp.tz as TimeZone
    String phase = low(inp.phase)
    String window = windowOf(inp.window, t, tz)
    boolean planOk = planMatches(inp.eventDate, inp.window, inp.planEventDate, inp.planWindow)
    if (inp.away) return [rule: 1, action: "disown", reason: "away mode: no hold"]
    if (phase in ["none", "skipped", "aborted"]) return [rule: 2, action: "release", reason: "phase ${phase}: back to the program".toString()]
    if (phase == "announced" && window == "am" && inNight(t, inp.eventDate as String, cfg.nightMins as int, tz)) {
        return hold(3, cfg.nightHold, "night before a morning event")
    }
    if (phase == "anchor") {
        BigDecimal stopSp = (window == "am" ? cfg.amStopHold : cfg.pmStopHold) as BigDecimal
        String plan = planOk ? low(inp.plan) : null
        if (plan == "at least") return hold(4, cfg.preheatHold, "anchor window, plan at least: pre-heat")
        if (plan != "at most") {
            // No usable plan: no pre-heat. A morning event keeps the night hold; an evening one stays on the program.
            String why = planOk ? "no plan" : "plan is for another event"
            if (window == "am") return hold(4, cfg.nightHold, "anchor window, ${why}: night hold, no pre-heat".toString())
            return [rule: 4, action: "release", reason: "anchor window, ${why}: program, no pre-heat".toString()]
        }
        if (low(inp.anchorStop) == "true") return hold(4, stopSp, "anchor window, plan at most: stop reached")
        Long ref = inp.quietRef as Long
        long silenceMs = cfg.silenceMs as long
        if (ref == null || t - ref >= silenceMs) {
            return hold(4, stopSp, "anchor window, plan at most: no stop check for ${silenceMs.intdiv(60000L)} min, pre-heat stopped".toString())
        }
        Map h = hold(4, cfg.preheatHold, "anchor window, plan at most: pre-heat until the stop")
        h.wakeAt = ref + silenceMs
        return h
    }
    if (phase == "gap") return hold(5, cfg.preheatHold, "between the anchor window and the event: pre-heat")
    if (phase == "active") {
        BigDecimal used = toNum(inp.eventUsed), keep = toNum(inp.keepUse)
        if (planOk && low(inp.advice) == "keep" && used != null && keep != null && used < keep) {
            return hold(6, cfg.keepHold, "event, advice keep: ${used} of ${keep} kWh used".toString())
        }
        return hold(6, cfg.eventHold, "event")
    }
    return [rule: 7, action: "release", reason: "phase ${phase ?: 'unknown'}${phase == 'announced' ? ' ' + window : ''}: nothing to hold".toString()]
}

static Map hold(int rule, Object sp, String reason) {
    return [rule: rule, action: "hold", setpoint: toNum(sp), reason: reason]
}

// am or pm, from the phase device's window; when it has none, from the hour.
static String windowOf(Object w, long t, TimeZone tz) {
    String s = low(w)
    if (s == "am" || s == "pm") return s
    return minuteOfDay(t, tz) < 720 ? "am" : "pm"
}

// The plan belongs to the phase's event unless both devices name an event and they differ.
@CompileStatic
static boolean planMatches(Object eventDate, Object window, Object planEventDate, Object planWindow) {
    String e = known(eventDate), pe = known(planEventDate), w = known(window), pw = known(planWindow)
    if (e != null && pe != null && e != pe) return false
    if (w != null && pw != null && w != pw) return false
    return true
}

// Whether t is in the night before a morning event: from the nightly time on the evening before
// eventDate (or on eventDate itself when the nightly time is before 06:00). Without a readable
// eventDate, from the nightly time to 06:00 on any night.
static boolean inNight(long t, String eventDate, int nightMins, TimeZone tz) {
    Long start = nightStart(eventDate, nightMins, tz)
    if (start != null) return t >= start
    int m = minuteOfDay(t, tz)
    return nightMins < 360 ? (m >= nightMins && m < 360) : (m >= nightMins || m < 360)
}

static Long nightStart(String eventDate, int nightMins, TimeZone tz) {
    if (!(eventDate ==~ /\d{4}-\d{2}-\d{2}/)) return null
    List<Integer> p = eventDate.tokenize('-').collect { it as int }
    Calendar c = Calendar.getInstance(tz)
    c.clear()
    c.set(p[0], p[1] - 1, p[2], 0, 0, 0)
    if (nightMins >= 360) c.add(Calendar.DAY_OF_MONTH, -1)
    c.set(Calendar.HOUR_OF_DAY, nightMins.intdiv(60))
    c.set(Calendar.MINUTE, nightMins % 60)
    return c.timeInMillis
}

@CompileStatic
static int minuteOfDay(long t, TimeZone tz) {
    Calendar c = Calendar.getInstance(tz)
    c.setTimeInMillis(t)
    return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
}

@CompileStatic
static int minutesOf(String hhmm) {
    List<String> p = hhmm.tokenize(':')
    return (p[0] as int) * 60 + (p[1] as int)
}

// The selected away modes may hold names or ids; none selected means the mode named Away.
static boolean awayMatch(String mode, String modeId, List chosen) {
    if (!chosen) return mode == "Away"
    return chosen.any { it?.toString() == mode || (modeId != null && it?.toString() == modeId) }
}

static boolean differs(BigDecimal cur, BigDecimal want) {
    if (want == null) return false
    if (cur == null) return true
    return (cur - want).abs() > 0.05G
}

static BigDecimal toNum(Object v) {
    if (v == null) return null
    if (v instanceof BigDecimal) return (BigDecimal) v
    if (v instanceof Number) return new BigDecimal(v.toString())
    String s = v.toString().trim()
    return s.isBigDecimal() ? new BigDecimal(s) : null
}

@CompileStatic
static String low(Object v) { v == null ? null : v.toString().trim().toLowerCase() }

@CompileStatic
static String known(Object v) {
    String s = low(v)
    return (s == null || s == "" || s == "none" || s == "null") ? null : s
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

void checkVersion(boolean reinit = true) {
    if (state.version == CODE_VERSION) return
    logVer "New version: ${CODE_VERSION} (was: ${state.version})"
    state.version = CODE_VERSION
    if (reinit) runIn(1, "updated")
}

void logsOff() {
    checkVersion()
    app.updateSetting("debugEnable", [value: "false", type: "bool"])
    app.updateSetting("traceEnable", [value: "false", type: "bool"])
    logWarn "debug and trace logging disabled"
}

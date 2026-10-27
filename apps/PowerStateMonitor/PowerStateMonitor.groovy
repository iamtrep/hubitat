// Copyright (c) 2026 PJ
// SPDX-License-Identifier: MIT

/*
 Power State Monitor

 Turns a power meter's readings into an operating state (off, normal, turbo, ...) and
 publishes it on a device. A state is taken only from a reading that has held for the
 settle time, so start-up surges and ramps between speeds never show as a state.
*/

import com.hubitat.app.ChildDeviceWrapper
import com.hubitat.app.DeviceWrapper
import com.hubitat.hub.domain.State
import groovy.transform.CompileStatic
import groovy.transform.Field

@Field static final String CODE_VERSION = "0.1.0"

definition(
    name: "Power State Monitor",
    namespace: "iamtrep",
    author: "pj",
    description: "Publishes a load's operating state, read from its power meter with surges filtered out",
    menu: "Automations", // new in platform 2.5.0
    category: "Convenience",
    singleThreaded: true,
    importUrl: "https://raw.githubusercontent.com/iamtrep/hubitat/main/apps/PowerStateMonitor/PowerStateMonitor.groovy",
    documentationLink: "https://github.com/iamtrep/hubitat/tree/main/apps/PowerStateMonitor",
    iconUrl: "", iconX2Url: ""
)

@Field static final List<Map> STATE_DEFAULTS = [[name: "off", from: 0], [name: "on", from: 15],
                                                [name: "high", from: 60], [name: "state 4", from: 100],
                                                [name: "state 5", from: 150]]

preferences {
    page(name: "mainPage")
}

// ── UI ────────────────────────────────────────────────────────────────

String esc(Object s) { s == null ? '' : s.toString().replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;').replace("'", '&#39;') }

Map mainPage() {
    List<String> errs = validateCfg(currentCfg())
    dynamicPage(name: "mainPage", title: "", install: errs.isEmpty(), uninstall: true) {
        section {
            label title: "App name", required: false
            if (errs) paragraph "<div class='p-message p-message-error p-3 border-round'>${errs.collect { esc(it) }.join('<br>')}</div>"
            if (state.last != null) paragraph rawHtml: true, statusHtml()
        }
        section("Power meter") {
            input "meter", "capability.powerMeter", title: "Power meter", required: true
        }
        section("States") {
            input "stateCount", "enum", title: "Number of states", options: ["2", "3", "4", "5"], defaultValue: "3",
                  required: true, submitOnChange: true
            for (int n = 1; n <= stateCount(); n++) {
                Map d = STATE_DEFAULTS[n - 1]
                input "state${n}Name", "text", title: "State ${n} name", defaultValue: d.name, required: true, width: 6
                if (n == 1) paragraph "From 0 W"
                else input "state${n}From", "decimal", title: "From (W)", defaultValue: d.from, required: true, width: 6
            }
            input "ceiling", "decimal", title: "Readings above this are surges, never a state (W, blank: none)", required: false
            input "hysteresis", "decimal", title: "Hysteresis: a reading must pass a state's edge by this much to leave it (W)",
                  defaultValue: 5, required: true
        }
        section("Filtering") {
            input "settleSeconds", "number", title: "A reading becomes the state after it has held for (seconds)",
                  defaultValue: 20, range: "1..3600", required: true
            input "unknownSeconds", "number", title: "A surge reading that holds this long sets the state to unknown (seconds)",
                  defaultValue: 120, range: "1..86400", required: true
            input "refreshMinutes", "number", title: "Ask the meter for a reading every (minutes, 0: never)",
                  defaultValue: 0, range: "0..1440", required: true
        }
        section("Source") {
            input "sourceSwitch", "capability.switch", title: "Switch that commands one of the states (optional)", required: false,
                  submitOnChange: true
            if (sourceSwitch) input "sourceState", "text", title: "The state it commands", required: true
        }
        section("Logging", hideable: true, hidden: true) {
            input "txtEnable", "bool", title: "Enable info logging", defaultValue: true
            input "debugEnable", "bool", title: "Enable debug logging (turns off after 30 minutes)", defaultValue: false, submitOnChange: true
            if (debugEnable) input "traceEnable", "bool", title: "Enable trace logging (turns off with debug)", defaultValue: false
        }
    }
}

String statusHtml() {
    Map last = state.last as Map
    long ageS = ((now() - (last.t as long)).intdiv(1000L)) as long
    StringBuilder h = new StringBuilder()
    h << "<p><b>State: ${esc(state.cur ?: 'not known yet')}</b>"
    if (sourceSwitch && state.cur == settings.sourceState) h << " · source: ${esc(state.source)}"
    h << " · last reading ${esc(fmtW(last.v))} W, ${ageS} s ago</p>"
    return h.toString()
}

// ── Lifecycle ─────────────────────────────────────────────────────────

void installed() { checkVersion(false); initialize() }

void updated() { checkVersion(false); unsubscribe(); unschedule(); initialize() }

void uninstalled() {
    if (getChildDevice(dni())) deleteChildDevice(dni())
}

void initialize() {
    stateDevice()
    if (debugEnable || traceEnable) runIn(1800, "logsOff")
    List<String> errs = validateCfg(currentCfg())
    if (errs) {
        logWarn "settings incomplete, no state is published: ${errs.join('; ')}"
        return
    }
    subscribe(meter, "power", "powerHandler", [filterEvents: false])
    if (sourceSwitch) subscribe(sourceSwitch, "switch", "sourceHandler")
    subscribe(location, "systemStart", "systemStartHandler")
    int rm = (refreshMinutes ?: 0) as int
    if (rm > 0) schedule("${new Random().nextInt(60)} */${rm} * ? * *", "refreshMeter")
    if (state.last == null) seedFromMeter()
    evaluatePower("settings saved")
}

// The meter's current value counts from the time it was reported.
void seedFromMeter() {
    State s = (meter as DeviceWrapper).currentState("power")
    BigDecimal v = numOrNull(s?.value)
    if (v != null && s?.date != null) state.last = [v: v, t: s.date.time]
}

void systemStartHandler(evt) {
    checkVersion()
    evaluatePower("hub restart")
}

void logsOff() {
    checkVersion()
    app.updateSetting("debugEnable", false)
    app.updateSetting("traceEnable", false)
    logWarn "debug and trace logging disabled"
}

void checkVersion(boolean reinit = true) {
    if (state.version == CODE_VERSION) return
    logVer "version ${CODE_VERSION} (was ${state.version})"
    state.version = CODE_VERSION
    if (reinit) runIn(1, "updated")
}

// ── Configuration ─────────────────────────────────────────────────────

int stateCount() { (settings.stateCount ?: "3") as int }

Map currentCfg() {
    List<Map> states = []
    for (int n = 1; n <= stateCount(); n++) {
        String name = settings["state${n}Name".toString()] as String
        states << [name: name == null ? STATE_DEFAULTS[n - 1].name : name.trim(),
                   from: n == 1 ? 0G : numOrNull(settings["state${n}From".toString()] ?: STATE_DEFAULTS[n - 1].from)]
    }
    return [states: states, ceiling: numOrNull(settings.ceiling), hyst: numOrNull(settings.hysteresis ?: 0),
            settleMs: ((settleSeconds ?: 20) as long) * 1000L, unknownMs: ((unknownSeconds ?: 120) as long) * 1000L,
            sourceState: sourceSwitch ? (settings.sourceState as String)?.trim() : null]
}

// ── Inputs ────────────────────────────────────────────────────────────

void powerHandler(evt) {
    checkVersion()
    BigDecimal v = numOrNull(evt.value)
    if (v == null) { logWarn "ignored a power reading that is not a number: ${evt.value}"; return }
    logEvt "${v} W"
    state.last = takeReading((Map) state.last, v, evt.date.time as long)
    evaluatePower("reading")
}

void sourceHandler(evt) {
    checkVersion()
    logEvt "${evt.displayName} ${evt.value}"
    evaluatePower("switch")
}

void wakeHandler() {
    checkVersion()
    evaluatePower("timer")
}

void refreshMeter() {
    checkVersion()
    logSched "asking ${meter.displayName} for a reading"
    meter.refresh()
}

// ── Evaluation ────────────────────────────────────────────────────────

void evaluatePower(String why) {
    Map cfg = currentCfg()
    if (validateCfg(cfg)) return
    Map r = stepState((String) state.cur, (Map) state.last, cfg, now())
    String next = r.cur as String
    if (next != state.cur) {
        logInfo "${state.cur ?: 'not known'} → ${next} (${fmtW((state.last as Map)?.v)} W, ${why})"
        if (next == "unknown") logWarn "a reading above ${fmtW(cfg.ceiling)} W has held for ${(cfg.unknownMs as long).intdiv(1000L)} s"
        state.cur = next
    }
    if (r.wakeAt != null) runInMillis(Math.max(1L, (r.wakeAt as long) - now()), "wakeHandler")
    state.source = sourceOf((String) state.cur, cfg.sourceState as String, sourceSwitch?.currentValue("switch") as String)
    stateDevice().updateStatus([powerState: state.cur, stateSource: state.source])
}

// ── State device ──────────────────────────────────────────────────────

String dni() { "psm-${app.id}" }

String deviceLabel() { app.getLabel() == "Power State Monitor" ? "Power State" : "${app.getLabel()} State" }

ChildDeviceWrapper stateDevice() {
    ChildDeviceWrapper d = getChildDevice(dni())
    if (!d) {
        d = addChildDevice("iamtrep", "Power State", dni(), [name: "Power State", label: deviceLabel(), isComponent: true])
        logCfg "created ${d.displayName}"
    }
    return d
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
String fmtW(Object n) {
    BigDecimal b = numOrNull(n)
    return b == null ? '' : b.stripTrailingZeros().toPlainString()
}

@CompileStatic
List<String> validateCfg(Map cfg) {
    List<String> errs = []
    List<Map> st = cfg.states as List<Map>
    List<String> names = st.collect { Map s -> s.name as String }
    if (names.any { String n -> !n }) errs << 'Every state needs a name'
    if (names.findAll { String n -> n }.unique(false).size() != names.findAll { String n -> n }.size()) errs << 'State names must differ'
    if (names.contains('unknown')) errs << '"unknown" is reserved for a surge that holds'
    for (int i = 1; i < st.size(); i++) {
        BigDecimal f = numOrNull(st[i].from), p = numOrNull(st[i - 1].from)
        if (f == null) errs << "State ${i + 1}: enter where it starts (W)".toString()
        else if (p != null && f <= p) errs << "State ${i + 1} must start above state ${i}".toString()
    }
    BigDecimal top = numOrNull(st[st.size() - 1].from)
    if (cfg.ceiling != null && top != null && (cfg.ceiling as BigDecimal) <= top) errs << 'The surge ceiling must be above the top state'
    if (cfg.hyst == null || (cfg.hyst as BigDecimal) < 0G) errs << 'Hysteresis: enter 0 or more'
    if (cfg.sourceState != null && !names.contains(cfg.sourceState)) errs << 'The state the switch commands must be one of the states'
    return errs
}

// A reading equal to the one held does not restart its hold: a repeat (a refresh) confirms it.
@CompileStatic
Map takeReading(Map last, BigDecimal v, long t) {
    if (last != null && numOrNull(last.v) == v) return last
    return [v: v, t: t]
}

// The state a reading falls in, or null above the ceiling. A reading inside the current state's
// band widened by the hysteresis keeps the current state.
@CompileStatic
String classify(BigDecimal w, List<Map> states, BigDecimal ceiling, BigDecimal hyst, String cur) {
    if (ceiling != null && w > ceiling) return null
    int j = states.findIndexOf { Map s -> s.name == cur }
    if (j >= 0) {
        BigDecimal lo = j == 0 ? null : (numOrNull(states[j].from) - hyst)
        BigDecimal hi = j == states.size() - 1 ? null : (numOrNull(states[j + 1].from) + hyst)
        if ((lo == null || w >= lo) && (hi == null || w < hi)) return cur
    }
    int i = 0
    for (int k = 1; k < states.size(); k++) if (w >= numOrNull(states[k].from)) i = k
    return states[i].name as String
}

// The power meter reports when its reading changes by a set step (send-on-delta), so the last
// reading stands until the next one. A reading that has held for settleMs is steady and sets the
// state; surges and ramps are replaced before they settle. A reading above the ceiling sets
// 'unknown' once it has held for unknownMs.
// last: [v: watts, t: epoch ms] or null. Returns [cur: state name or null, wakeAt: epoch ms or null].
@CompileStatic
Map stepState(String cur, Map last, Map cfg, long now) {
    if (last == null) return [cur: cur, wakeAt: null]
    long t = last.t as long
    String cls = classify(numOrNull(last.v), cfg.states as List<Map>, cfg.ceiling as BigDecimal,
                          (cfg.hyst ?: 0G) as BigDecimal, cur)
    long need = cls == null ? (cfg.unknownMs as long) : (cfg.settleMs as long)
    if (now - t < need) return [cur: cur, wakeAt: t + need]
    return [cur: cls ?: 'unknown', wakeAt: null]
}

// 'switch' or 'other' while in the state the switch commands, depending on the switch; else 'none'.
@CompileStatic
String sourceOf(String cur, String sourceState, String switchValue) {
    if (sourceState == null || cur != sourceState) return 'none'
    return switchValue == 'on' ? 'switch' : 'other'
}

// ── End core ──────────────────────────────────────────────────────────

// ── Logging ───────────────────────────────────────────────────────────
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

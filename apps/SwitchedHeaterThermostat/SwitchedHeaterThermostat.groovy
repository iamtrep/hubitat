// Copyright (c) 2026 PJ
// SPDX-License-Identifier: MIT

/*
 Switched Heater Thermostat

 Drives heater switches from a Virtual Thermostat fed by temperature sensors. The
 thermostat decides heating or idle; the app writes its temperature, follows its
 operating state with the heaters, checks that they respond, draw power and warm the
 room, caps the heating time, protects against frost, and raises an alert when it
 cannot do its job.
*/

import com.hubitat.app.DeviceWrapper
import groovy.json.JsonOutput
import groovy.transform.Field

@Field static final String CODE_VERSION = "0.1.0"

definition(
    name: "Switched Heater Thermostat",
    namespace: "iamtrep",
    author: "pj",
    description: "Heater switches driven by a Virtual Thermostat and temperature sensors, with alerts",
    category: "Convenience",
    singleThreaded: true,
    importUrl: "https://raw.githubusercontent.com/iamtrep/hubitat/main/apps/SwitchedHeaterThermostat/SwitchedHeaterThermostat.groovy",
    iconUrl: "", iconX2Url: ""
)

@Field static final Map AGG_OPTS = [average: "Average", minimum: "Minimum"]
@Field static final Map SENSOR_FAULT_OPTS = [off: "Keep the heaters off", on: "Keep the heaters on", cycle: "Run the heaters on a fixed cycle"]
@Field static final String VT_DRIVER = "Virtual Thermostat"
@Field static final Integer QUIET_FLOOR_MIN = 30
@Field static final Integer POWER_GRACE_MIN = 2
@Field static final Integer MIN_GAPS = 5
@Field static final Integer KEEP_GAPS = 48
@Field static final Integer MAX_ATTEMPTS = 2

preferences {
    page(name: "mainPage")
}

// ── Core (pure) ───────────────────────────────────────────────────────
// No settings, state, devices, logging or @Field here: tests/test_core.groovy runs this block off-hub.

BigDecimal aggregateTemp(List values, String how) {
    List<BigDecimal> v = []
    for (Object x : values) { if (x != null) v << new BigDecimal(x.toString()) }
    if (v.isEmpty()) return null
    BigDecimal r
    if (how == 'minimum') {
        r = v[0]
        for (BigDecimal x : v) { if (x < r) r = x }
    } else {
        BigDecimal sum = 0
        for (BigDecimal x : v) sum += x
        r = sum.divide(new BigDecimal(v.size()), 6, BigDecimal.ROUND_HALF_UP)
    }
    return r.setScale(1, BigDecimal.ROUND_HALF_UP)
}

Map recordActivity(Map s, Long at, Integer keep) {
    Long last = (s?.last != null) ? (s.last as Long) : null
    List<Long> gaps = []
    for (Object g : (List) (s?.gaps ?: [])) gaps << (g as Long)
    if (at == null || (last != null && at <= last)) return [last: last, gaps: gaps]
    if (last != null) {
        gaps << (at - last)
        while (gaps.size() > keep) gaps.remove(0)
    }
    return [last: at, gaps: gaps]
}

// Twice the second-longest gap: one outage in the history does not stretch the limit.
Long quietLimitMs(List gaps, Long fallbackMs, Long floorMs, Integer minGaps) {
    if (gaps == null || gaps.size() < minGaps) return fallbackMs
    List<Long> sorted = []
    for (Object g : gaps) sorted << (g as Long)
    sorted.sort()
    return Math.max(floorMs, 2L * sorted[sorted.size() - 2])
}

boolean isQuiet(Map s, Long now, Long limitMs) {
    if (s?.last == null) return true
    return now - (s.last as Long) > limitMs
}

Long numOrNull(Object v) {
    if (v == null) return null
    String t = v.toString().trim()
    return t.isNumber() ? (t as BigDecimal).longValue() : null
}

BigDecimal decOrNull(Object v) {
    if (v == null) return null
    String t = v.toString().trim()
    return t.isNumber() ? new BigDecimal(t) : null
}

// s: frost, mode, opState, sensorFault, policy ('off' | 'on' | 'cycle'), cycleOn, resting.
// Returns the wanted state and why: 'frost', 'mode', 'rest', 'sensor' or 'thermostat'.
Map decideLoad(Map s) {
    if (s.frost == true) return [want: 'on', why: 'frost']
    if (s.mode != 'heat') return [want: 'off', why: 'mode']
    if (s.resting == true) return [want: 'off', why: 'rest']
    if (s.sensorFault == true) {
        boolean on = s.policy == 'on' || (s.policy == 'cycle' && s.cycleOn == true)
        return [want: on ? 'on' : 'off', why: 'sensor']
    }
    return [want: s.opState == 'heating' ? 'on' : 'off', why: 'thermostat']
}

// Minimum on and off times delay only the thermostat's own changes; every other reason acts at once.
// Returns the state to apply and, while held, when the hold ends.
Map holdForMinTimes(String want, String before, String why, Long lastChange, Long now, Long minOnMs, Long minOffMs) {
    if (why != 'thermostat' || before == null || want == before || lastChange == null) return [want: want, until: null]
    Long min = before == 'on' ? minOnMs : minOffMs
    if (min == null || min <= 0L) return [want: want, until: null]
    Long until = lastChange + min
    if (now >= until) return [want: want, until: null]
    return [want: before, until: until]
}

// On below frostTemp, off again at frostTemp + 1 degree; off with no reading or no setting.
boolean frostStep(boolean active, Object temp, Object frostTemp) {
    if (temp == null || frostTemp == null) return false
    BigDecimal t = new BigDecimal(temp.toString()), f = new BigDecimal(frostTemp.toString())
    return active ? t < f + 1 : t < f
}

String verifyOutcome(String wanted, String current, Integer attempts, Integer maxAttempts) {
    if (current == wanted) return 'ok'
    return attempts < maxAttempts ? 'retry' : 'fault'
}

// The cycle starts with its on phase at `start`; `next` is when the phase changes.
Map cyclePhase(Long now, Long start, Long periodMs, Integer dutyPct) {
    Long s = (start != null) ? start : now
    Long onMs = (periodMs * dutyPct).intdiv(100) as Long
    Long pos = (now - s) % periodMs
    boolean on = pos < onMs
    return [on: on, next: now + (on ? onMs - pos : periodMs - pos)]
}

boolean overLimit(Long onSince, Long now, Long maxMs) {
    if (maxMs == null || onSince == null) return false
    return now - onSince >= maxMs
}

boolean riseOk(Object startTemp, Object temp, Object minRise) {
    if (startTemp == null || temp == null) return false
    return new BigDecimal(temp.toString()) - new BigDecimal(startTemp.toString()) >= new BigDecimal(minRise.toString())
}

boolean noRise(Long onSince, Long now, Object startTemp, Object temp, Long windowMs, Object minRise) {
    if (windowMs == null || onSince == null || startTemp == null || temp == null) return false
    if (now - onSince < windowMs) return false
    return !riseOk(startTemp, temp, minRise)
}

String tempText(Object t, String scale) {
    if (t == null) return 'no temperature'
    return "${new BigDecimal(t.toString()).setScale(1, BigDecimal.ROUND_HALF_UP).toPlainString()} °${scale}".toString()
}

String riseDetail(Object startTemp, Object temp, Object minRise, String scale) {
    BigDecimal r = new BigDecimal(temp.toString()) - new BigDecimal(startTemp.toString())
    return "${tempText(r, scale)} (expected ${tempText(minRise, scale)})".toString()
}

String powerVerdict(String sw, Object power, Object minW) {
    if (power == null) return 'unknown'
    BigDecimal p = new BigDecimal(power.toString())
    BigDecimal m = new BigDecimal(minW.toString())
    if (sw == 'on' && p < m) return 'noDraw'
    if (sw == 'off' && p >= m) return 'drawWhileOff'
    return 'ok'
}

// A mismatch counts as a fault once it has lasted graceMs.
Map powerStep(Long badSince, String verdict, Long now, Long graceMs) {
    if (verdict == 'ok' || verdict == 'unknown') return [badSince: null, fault: false]
    Long since = (badSince != null) ? badSince : now
    return [badSince: since, fault: now - since >= graceMs]
}

String powerDetail(String label, String sw, Object power) {
    BigDecimal p = new BigDecimal(power.toString()).setScale(0, BigDecimal.ROUND_HALF_UP)
    return "${label} (${sw}, draws ${p.toPlainString()} W)".toString()
}

Map alertSwitchTargets(Map ids, Map faults) {
    Map out = [:]
    for (String kind : ['sensor', 'load', 'limit', 'rise']) {
        String id = ids?.get(kind) as String
        if (!id) continue
        boolean on = faults?.get(kind) == true
        if (on || !out.containsKey(id)) out[id] = on ? 'on' : 'off'
    }
    return out
}

// rows: [label, available, enabled] per heater. Names the heaters that could use Command Retry but don't.
List<String> retryAdvice(List rows) {
    List<String> out = []
    for (Object r : rows) {
        Map m = r as Map
        if (m.available == true && m.enabled != true) out << (m.label as String)
    }
    return out.sort()
}

Map listDiff(List before, List after) {
    List b = (before ?: []) as List, a = (after ?: []) as List
    return [added: (a - b).sort(), removed: (b - a).sort()]
}

String namesText(List names) {
    List<String> n = []
    for (Object x : names) n << x.toString()
    return n.sort().join(', ')
}

String policyText(String policy, Object duty, Object period) {
    if (policy == 'on') return 'Heaters kept on'
    if (policy == 'cycle') return "Heaters on ${duty}% of every ${period} minutes".toString()
    return 'Heat is off'
}

// kind: 'sensor', 'load', 'limit', 'rise', 'frost' or 'quiet'. `extra` is the sensor policy text,
// the rest minutes or the rise detail.
String faultMessage(String app, String kind, boolean raised, String detail, String extra) {
    if (kind == 'sensor') return raised ? "${app}: no temperature, all sensors quiet (${detail}). ${extra}.".toString() : "${app}: temperature back (${detail}).".toString()
    if (kind == 'load') return raised ? "${app}: lost control of ${detail}.".toString() : "${app}: heaters under control again.".toString()
    if (kind == 'limit') return raised ? "${app}: heaters on for ${detail} minutes, the limit. Off for ${extra} minutes.".toString() : "${app}: heating time back to normal.".toString()
    if (kind == 'rise') return raised ? "${app}: heaters on for ${detail} minutes and the temperature rose ${extra}.".toString() : "${app}: warming check back to normal.".toString()
    if (kind == 'frost') return raised ? "${app}: frost protection on (${detail}).".toString() : "${app}: frost protection off (${detail}).".toString()
    return raised ? "${app}: ${detail} quiet, left out of the temperature.".toString() : "${app}: ${detail} reporting again.".toString()
}

// ── End core ──────────────────────────────────────────────────────────

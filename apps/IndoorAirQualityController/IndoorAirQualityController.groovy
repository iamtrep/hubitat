// Copyright (c) 2026 PJ
// SPDX-License-Identifier: MIT

/*
 Indoor Air Quality Controller

 Turns ventilation on in stages as indoor CO2 rises, suggests opening a window when
 CO2 stays high with every stage running, and warns about low indoor humidity.
*/

import com.hubitat.app.ChildDeviceWrapper
import com.hubitat.app.DeviceWrapper
import groovy.transform.Field

@Field static final String CODE_VERSION = "0.1.0"

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

preferences {
    page(name: "mainPage")
}

Map mainPage() {
    dynamicPage(name: "mainPage", title: "Indoor Air Quality Controller", install: true, uninstall: true) {
        section { label title: "App name", required: false }
    }
}

void installed() { initialize() }
void updated() { unsubscribe(); unschedule(); initialize() }
void initialize() { }

// ── Core (pure) ───────────────────────────────────────────────────────
// Self-contained: arguments in, values out. No settings, state, devices or logs.
// tests/test_core.groovy parses and runs this block off-hub.

BigDecimal numOrNull(Object o) {
    if (o instanceof Number) return new BigDecimal(o.toString())
    if (o instanceof String && ((String) o).trim().isBigDecimal()) return new BigDecimal(((String) o).trim())
    return null
}

String fmtInt(Object n) {
    BigDecimal b = numOrNull(n)
    return b == null ? '' : b.setScale(0, BigDecimal.ROUND_HALF_UP).toPlainString()
}

// readings: [[v: value, t: epoch ms]]. A reading older than staleMs is left out.
// how: 'highest', 'average' (rounded to a whole number) or 'lowest'. null when nothing is left.
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

boolean anySwitchCondition(List<String> whenOn, List<String> whenOff) {
    return (whenOn ?: []).contains('on') || (whenOff ?: []).contains('off')
}

boolean modeActive(String mode, List<String> chosen) {
    return chosen ? chosen.contains(mode) : mode != 'Away'
}

// Why the app is not managing air, most important first; null when it is.
String stopReason(boolean safety, boolean enabled, boolean modeOk, boolean paused) {
    if (safety) return 'safety'
    if (!enabled) return 'disabled'
    if (!modeOk) return 'mode'
    if (paused) return 'pause'
    return null
}

Long earliest(Long a, Long b) {
    if (a == null) return b
    if (b == null) return a
    return Math.min(a, b)
}

List<Map> resized(List<Map> cur, int n) {
    List<Map> out = []
    for (int i = 0; i < n; i++) {
        Map c = (cur != null && i < cur.size() && cur[i] != null) ? cur[i] : [:]
        out << [s: (c.s ?: 'off') as String, since: c.since as Long, heldUntil: c.heldUntil as Long]
    }
    return out
}

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

int stageLevel(List<Map> st) {
    int level = 0
    for (int i = 0; i < (st ?: []).size(); i++) if (st[i]?.s != null && st[i].s != 'off') level = i + 1
    return level
}

boolean allRunning(List<Map> st) {
    if (!st) return false
    for (Map x : st) if (x?.s == null || x.s == 'off') return false
    return true
}

// What a stage switch event means. pending: the command this app sent and has not seen come back.
// 'own' our command arriving; 'hold' a physical off on a running stage; 'reassert' another source
// turned a running stage's switch off; 'throttled' the same, too soon after the last re-assertion;
// 'ignore' anything else.
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

String windowMessage(String label, String kind, BigDecimal co2) {
    if (kind == 'raise' || kind == 'repeat') return "${label}: CO2 at ${fmtInt(co2)} ppm despite ventilation. Consider opening a window.".toString()
    if (kind == 'clear') return "${label}: CO2 back to ${fmtInt(co2)} ppm.".toString()
    return null
}

String humidityMessage(String label, BigDecimal rh, Object hours) {
    return "${label}: indoor humidity at ${fmtInt(rh)}% for ${fmtInt(hours)} h.".toString()
}

// ── End core ──────────────────────────────────────────────────────────

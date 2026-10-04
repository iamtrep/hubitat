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

// ── End core ──────────────────────────────────────────────────────────

// Copyright (c) 2026 PJ
// SPDX-License-Identifier: MIT

/*
 Season Manager

 Publishes the heating and cooling season on a component device, from date windows
 and the outdoor temperature, plus a winter credit flag between two dates. The winter
 boundary changes once a year each way; summer follows the weather in and out.
*/

import com.hubitat.app.ChildDeviceWrapper
import groovy.transform.Field

@Field static final String CODE_VERSION = "0.1.0"

definition(
    name: "Season Manager",
    namespace: "iamtrep",
    author: "pj",
    description: "Publishes the heating and cooling season on a device, from dates and the outdoor temperature",
    menu: "Automations", // new in platform 2.5.0
    category: "Convenience",
    singleThreaded: true,
    importUrl: "https://raw.githubusercontent.com/iamtrep/hubitat/main/apps/SeasonManager/SeasonManager.groovy",
    iconUrl: "", iconX2Url: ""
)

preferences {
    page(name: "mainPage")
}

Map mainPage() {
    dynamicPage(name: "mainPage", title: "Season Manager", install: true, uninstall: true) {
        section { label title: "App name", required: false }
    }
}

void installed() { initialize() }
void updated() { unsubscribe(); unschedule(); initialize() }
void initialize() { }

// ── Core (pure) ───────────────────────────────────────────────────────
// Self-contained: arguments in, values out. No settings, state, devices or logs.
// tests/test_core.groovy parses and runs this block off-hub.

List<String> seasonList() { return ['winter', 'spring', 'summer', 'fall'] }

boolean validMd(Object s) {
    if (!(s instanceof String) || !(((String) s) ==~ /\d\d-\d\d/)) return false
    int m = ((String) s).substring(0, 2) as int
    int d = ((String) s).substring(3) as int
    List<Integer> max = [31, 29, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31]
    return m >= 1 && m <= 12 && d >= 1 && d <= max[m - 1]
}

boolean validIso(Object s) {
    return s instanceof String && ((String) s) ==~ /\d{4}-\d\d-\d\d/ && validMd(((String) s).substring(5))
}

// Day of the year on a non-leap calendar; Feb 29 counts as Feb 28.
int doyOf(String md) {
    List<Integer> start = [0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334]
    int m = md.substring(0, 2) as int
    int d = md.substring(3) as int
    return start[m - 1] + (m == 2 ? Math.min(d, 28) : d)
}

String mdOf(String iso) { return iso.substring(5) }

boolean sameDay(String iso, String md) { return doyOf(mdOf(iso)) == doyOf(md) }

// iso's month-day in [from, until], both ends included, wrapping over the new year.
boolean inMd(String iso, String from, String until) {
    int x = doyOf(mdOf(iso))
    int a = doyOf(from)
    int b = doyOf(until)
    return a <= b ? (x >= a && x <= b) : (x >= a || x <= b)
}

// iso's month-day in [from, until): the last day excluded.
boolean inSpan(String iso, String from, String until) {
    return inMd(iso, from, until) && !sameDay(iso, until)
}

// The month-days follow each other through the year in this order, going round once.
boolean cyclicOrdered(List<String> mds) {
    int total = 0
    for (int i = 0; i < mds.size(); i++) {
        int a = doyOf(mds[i])
        int b = doyOf(mds[(i + 1) % mds.size()])
        total += (b - a + 365) % 365
    }
    return total == 365
}

String addDays(String iso, int n) {
    TimeZone gmt = TimeZone.getTimeZone('GMT')
    Calendar c = Calendar.getInstance(gmt)
    c.clear()
    String[] p = iso.split('-')
    c.set(p[0] as int, (p[1] as int) - 1, p[2] as int)
    c.add(Calendar.DAY_OF_MONTH, n)
    java.text.SimpleDateFormat f = new java.text.SimpleDateFormat('yyyy-MM-dd')
    f.setTimeZone(gmt)
    return f.format(c.getTime())
}

BigDecimal numOrNull(Object o) {
    if (o instanceof Number) return new BigDecimal(o.toString())
    if (o instanceof String && ((String) o).trim().isBigDecimal()) return new BigDecimal(((String) o).trim())
    return null
}

String fmtNum(Object n) {
    BigDecimal b = numOrNull(n)
    return b == null ? '' : b.setScale(1, BigDecimal.ROUND_HALF_UP).toPlainString()
}

BigDecimal inScale(BigDecimal celsius, String scale) {
    return scale == 'F' ? (celsius * 9 / 5 + 32).setScale(1, BigDecimal.ROUND_HALF_UP) : celsius
}

// Defaults calibrated on 2005-2025 climate (spec, "Calibration from local climate").
Map defaultCfg(String scale) {
    return [w2s:    [from: '03-11', until: '04-08', above: inScale(3.0, scale)],
            f2w:    [from: '10-26', until: '11-11', below: inScale(3.0, scale)],
            summer: [from: '05-10', until: '09-14', enter: inScale(17.0, scale), leave: inScale(12.0, scale), fallFrom: '08-15'],
            credit: [from: '12-01', until: '03-31']]
}

List<String> validateCfg(Map cfg) {
    List<String> errs = []
    Map w = cfg.w2s as Map
    Map f = cfg.f2w as Map
    Map s = cfg.summer as Map
    Map c = cfg.credit as Map
    Map dates = ['Winter to spring window from': w.from, 'Winter to spring window until': w.until,
                 'Fall to winter window from': f.from, 'Fall to winter window until': f.until,
                 'Summer possible from': s.from, 'Summer possible until': s.until, 'Fall from': s.fallFrom,
                 'Winter credit from': c.from, 'Winter credit until': c.until]
    dates.each { String k, v -> if (!validMd(v)) errs << "${k}: enter a month and day as MM-DD".toString() }
    Map nums = ['Winter to spring threshold': w.above, 'Fall to winter threshold': f.below,
                'Enter summer threshold': s.enter, 'Leave summer threshold': s.leave]
    nums.each { String k, v -> if (numOrNull(v) == null) errs << "${k}: enter a number".toString() }
    if (errs) return errs
    if (numOrNull(s.leave) >= numOrNull(s.enter)) errs << 'Leave summer threshold must be below the enter summer threshold'
    if (!cyclicOrdered([w.from, w.until, s.from, s.fallFrom, s.until, f.from, f.until] as List<String>))
        errs << 'Dates must follow each other through the year in this order: winter to spring window, summer possible from, fall from, summer possible until, fall to winter window'
    return errs
}

// ── End core ──────────────────────────────────────────────────────────

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

@Field static final String CODE_VERSION = "0.1.1"

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

@Field static final Map SEASON_OPTS = [winter: "Winter", spring: "Spring", summer: "Summer", fall: "Fall"]
@Field static final long DAY_MS = 86400000L
@Field static final long STALE_MS = 86400000L

preferences {
    page(name: "mainPage")
}

// ── UI ────────────────────────────────────────────────────────────────

String esc(Object s) { s == null ? '' : s.toString().replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;').replace("'", '&#39;') }

String unit() { "°${location.temperatureScale}" }

String fmtTime(Long t) { t == null ? '' : new Date(t).format('yyyy-MM-dd HH:mm', location.timeZone) }

Map mainPage() {
    dynamicPage(name: "mainPage", title: "Season Manager", install: true, uninstall: true) {
        Map cfg = currentCfg()
        List<String> errs = validateCfg(cfg)
        String u = unit()
        Map d = defaultCfg(location.temperatureScale as String)
        section {
            if (errs) paragraph "<div class='p-message p-message-error p-3 border-round'>${errs.collect { esc(it) }.join('<br>')}</div>"
            if (state.season != null) paragraph statusHtml(cfg)
        }
        if (state.season == null) {
            List<String> poss = startOptions(cfg, errs)
            section("Current season") {
                if (poss.size() == 1) paragraph "Season right now: <b>${esc(SEASON_OPTS[poss[0]])}</b>, from today's date."
                else input "initialSeason", "enum", title: "Season right now: today's date allows ${poss.collect { SEASON_OPTS[it] }.join(' or ')}, depending on recent weather",
                           options: SEASON_OPTS.subMap(poss), required: true
            }
        } else {
            section("Change the season") {
                input "manualSeason", "enum", title: "Season", options: SEASON_OPTS, required: false, width: 4
                input "manualHoldDays", "number", title: "Suspend automatic changes for (days)", defaultValue: 3, range: "0..365", required: false, width: 4
                input "btnSetSeason", "button", title: "Set the season"
                if (state.holdUntil) input "btnResumeAuto", "button", title: "Resume automatic changes"
            }
        }
        section("Outdoor temperature") {
            input "tempSensor", "capability.temperatureMeasurement", title: "Outdoor temperature sensor", required: true
            paragraph "Read every hour. The season follows the mean of the three previous daily means, checked every day at 05:00."
        }
        section("Winter boundary: changes once a year each way") {
            input "w2sFrom", "text", title: "Winter to spring, from (MM-DD)", defaultValue: d.w2s.from, width: 4, submitOnChange: true
            input "w2sUntil", "text", title: "…until (MM-DD), the last possible day", defaultValue: d.w2s.until, width: 4, submitOnChange: true
            input "w2sAbove", "decimal", title: "…when the 3-day mean is above (${u})", defaultValue: d.w2s.above, width: 4, submitOnChange: true
            input "f2wFrom", "text", title: "Fall to winter, from (MM-DD)", defaultValue: d.f2w.from, width: 4, submitOnChange: true
            input "f2wUntil", "text", title: "…until (MM-DD), the last possible day", defaultValue: d.f2w.until, width: 4, submitOnChange: true
            input "f2wBelow", "decimal", title: "…when the 3-day mean is below (${u})", defaultValue: d.f2w.below, width: 4, submitOnChange: true
        }
        section("Summer boundary: follows the weather both ways") {
            input "summerFrom", "text", title: "Summer possible from (MM-DD)", defaultValue: d.summer.from, width: 4, submitOnChange: true
            input "summerUntil", "text", title: "Summer possible until (MM-DD); summer ends on this day", defaultValue: d.summer.until, width: 4, submitOnChange: true
            input "fallFrom", "text", title: "Fall from (MM-DD); outside summer, spring becomes fall on this day", defaultValue: d.summer.fallFrom, width: 4, submitOnChange: true
            input "summerEnter", "decimal", title: "Enter summer when the 3-day mean is above (${u})", defaultValue: d.summer.enter, width: 4, submitOnChange: true
            input "summerLeave", "decimal", title: "Leave summer when the 3-day mean is below (${u})", defaultValue: d.summer.leave, width: 4, submitOnChange: true
        }
        section("Winter credit period") {
            input "creditFrom", "text", title: "On from (MM-DD)", defaultValue: d.credit.from, width: 4, submitOnChange: true
            input "creditUntil", "text", title: "On until (MM-DD), included", defaultValue: d.credit.until, width: 4, submitOnChange: true
        }
        section("Hub variable mirror") {
            input "mirrorEnable", "bool", title: "Mirror the season to hub variables, for rules not yet moved to the Season device", defaultValue: false, submitOnChange: true
            if (mirrorEnable) {
                input "mirrorSeasonVar", "enum", title: "String variable for the season", options: varNames("string"), required: false, submitOnChange: true
                input "labelWinter", "text", title: "Label for winter", defaultValue: "winter", width: 3
                input "labelSpring", "text", title: "Label for spring", defaultValue: "spring", width: 3
                input "labelSummer", "text", title: "Label for summer", defaultValue: "summer", width: 3
                input "labelFall", "text", title: "Label for fall", defaultValue: "fall", width: 3
                input "mirrorCreditVar", "enum", title: "Boolean variable for the winter credit period", options: varNames("boolean"), required: false, submitOnChange: true
            }
        }
        section("Logging") {
            input "txtEnable", "bool", title: "Enable info logging", defaultValue: true
            input "debugEnable", "bool", title: "Enable debug logging (turns off after 30 minutes)", defaultValue: false, submitOnChange: true
        }
        if (debugEnable) {
            section("Testing") {
                input "testDate", "text", title: "Test day (yyyy-MM-dd, blank = today)", required: false, width: 4
                input "testMean", "text", title: "Test 3-day mean (${u}; blank or none = from the sensor)", required: false, width: 4
                input "btnEvaluate", "button", title: "Evaluate now"
            }
        }
        section { label title: "App name", required: false }
    }
}

String statusHtml(Map cfg) {
    List<String> rows = []
    rows << "<b>${esc(SEASON_OPTS[state.season])}</b> since ${esc(state.since)}".toString()
    if (state.holdUntil) rows << "Automatic changes suspended until ${esc(fmtTime(state.holdUntil as Long))}".toString()
    Map ev = state.lastEval as Map
    if (ev) rows << "Last evaluation ${esc(ev.day)}: 3-day mean ${ev.mean != null ? esc(ev.mean) + ' ' + unit() : 'not available'}".toString()
    recentMeans((state.samples ?: [:]) as Map, realToday()).each { Map m ->
        rows << "${esc(m.day)}: ${m.mean != null ? esc(m.mean) + ' ' + unit() : 'no mean'} (${m.n} readings)".toString()
    }
    if (!validateCfg(cfg)) rows << "Next possible change: ${esc(nextPossible(state.season as String, cfg, unit()))}".toString()
    rows << "Winter credit period: ${state.credit ?: 'off'}".toString()
    return rows.join('<br>')
}

// ── Lifecycle ─────────────────────────────────────────────────────────

void installed() { checkVersion(false); initialize() }

void updated() { checkVersion(false); unsubscribe(); unschedule(); initialize() }

void uninstalled() {
    removeAllInUseGlobalVar()
    if (getChildDevice(dni())) deleteChildDevice(dni())
}

void initialize() {
    if (state.season == null) {
        Map cfg = currentCfg()
        List<String> poss = startOptions(cfg, validateCfg(cfg))
        String s = poss.size() == 1 ? poss[0] : (poss.contains(settings.initialSeason) ? settings.initialSeason as String : null)
        if (s) {
            state.season = s
            state.since = realToday()
            logCfg "season set to ${s} on install${poss.size() == 1 ? ' from the date' : ''}"
        }
    }
    state.remove('mirroredSeason')
    state.remove('mirroredCredit')
    registerVars()
    seasonDevice()
    schedule("0 7 * * * ?", "sampleHandler")
    schedule("0 0 5 * * ?", "evaluateHandler")
    schedule("0 1 0 * * ?", "creditHandler")
    if (debugEnable) runIn(1800, "logsOff")
    updateCredit(realToday(), currentCfg())
    publish()
}

void logsOff() { checkVersion(); app.updateSetting("debugEnable", false); logWarn "debug logging disabled" }

void checkVersion(boolean reinit = true) {
    if (state.version == CODE_VERSION) return
    logVer "version ${CODE_VERSION} (was ${state.version})"
    state.version = CODE_VERSION
    if (reinit) runIn(1, "updated")
}

// ── Configuration ─────────────────────────────────────────────────────

BigDecimal numOr(Object v, Object dflt) {
    BigDecimal n = numOrNull(v)
    return n == null ? (dflt as BigDecimal) : n
}

Map currentCfg() {
    Map d = defaultCfg(location.temperatureScale as String)
    return [w2s:    [from: settings.w2sFrom ?: d.w2s.from, until: settings.w2sUntil ?: d.w2s.until, above: numOr(settings.w2sAbove, d.w2s.above)],
            f2w:    [from: settings.f2wFrom ?: d.f2w.from, until: settings.f2wUntil ?: d.f2w.until, below: numOr(settings.f2wBelow, d.f2w.below)],
            summer: [from: settings.summerFrom ?: d.summer.from, until: settings.summerUntil ?: d.summer.until,
                     enter: numOr(settings.summerEnter, d.summer.enter), leave: numOr(settings.summerLeave, d.summer.leave),
                     fallFrom: settings.fallFrom ?: d.summer.fallFrom],
            credit: [from: settings.creditFrom ?: d.credit.from, until: settings.creditUntil ?: d.credit.until]]
}

String realToday() { return new Date().format('yyyy-MM-dd', location.timeZone) }

List<String> startOptions(Map cfg, List<String> errs) { errs ? seasonList() : seasonsOn(realToday(), cfg) }


// ── Device ────────────────────────────────────────────────────────────

String dni() { "season-${app.id}" }

String deviceLabel() { app.getLabel() == "Season Manager" ? "Season" : "${app.getLabel()} Season" }

ChildDeviceWrapper seasonDevice() {
    ChildDeviceWrapper d = getChildDevice(dni())
    if (!d) {
        d = addChildDevice("iamtrep", "Season Manager Season", dni(), [name: "Season", label: deviceLabel(), isComponent: true])
        logCfg "created ${d.displayName}"
    }
    return d
}

void publish() {
    seasonDevice().updateStatus([season: state.season, winterCredit: state.credit])
    mirror()
}

List<String> varNames(String type) { return ((getGlobalVarsByType(type) ?: [:]) as Map).keySet().collect { it as String }.sort() }

String mirrorLabel(String season) {
    Map labels = [winter: settings.labelWinter ?: 'winter', spring: settings.labelSpring ?: 'spring',
                  summer: settings.labelSummer ?: 'summer', fall: settings.labelFall ?: 'fall']
    return labels[season] as String
}

void mirror() {
    if (!settings.mirrorEnable) return
    if (settings.mirrorSeasonVar && state.season) {
        String label = mirrorLabel(state.season as String)
        if (state.mirroredSeason != label) {
            if (setGlobalVar(settings.mirrorSeasonVar as String, label)) {
                state.mirroredSeason = label
                logCfg "mirrored season to ${settings.mirrorSeasonVar} = ${label}"
            } else logWarn "could not set hub variable ${settings.mirrorSeasonVar}"
        }
    }
    if (settings.mirrorCreditVar && state.credit) {
        boolean on = state.credit == 'on'
        if (state.mirroredCredit != on) {
            if (setGlobalVar(settings.mirrorCreditVar as String, on)) {
                state.mirroredCredit = on
                logCfg "mirrored winter credit to ${settings.mirrorCreditVar} = ${on}"
            } else logWarn "could not set hub variable ${settings.mirrorCreditVar}"
        }
    }
}

void registerVars() {
    removeAllInUseGlobalVar()
    List<String> vars = settings.mirrorEnable ? ([settings.mirrorSeasonVar, settings.mirrorCreditVar].findAll { it } as List<String>) : []
    if (vars) addInUseGlobalVar(vars)
}

void renameVariable(String oldName, String newName) {
    checkVersion()
    ['mirrorSeasonVar', 'mirrorCreditVar'].each { String k ->
        if (settings[k] == oldName) {
            app.updateSetting(k, [type: "enum", value: newName])
            logCfg "hub variable ${oldName} renamed to ${newName}"
        }
    }
}

// ── Handlers ──────────────────────────────────────────────────────────

void sampleHandler() {
    checkVersion()
    Object st = tempSensor?.currentState("temperature")
    BigDecimal v = numOrNull(st?.value)
    if (v == null || st?.date == null || now() - (st.date as Date).getTime() > STALE_MS) {
        if (!state.staleWarned) {
            logWarn "outdoor sensor has not reported for 24 hours; season changes wait for the date limits"
            state.staleWarned = true
        }
        return
    }
    if (state.staleWarned) { logInfo "outdoor sensor reporting again"; state.remove('staleWarned') }
    state.samples = addSample((state.samples ?: [:]) as Map, realToday(), v)
    logSched "reading ${v}"
}

void evaluateHandler() { checkVersion(); evaluateSeason("daily") }

void creditHandler() { checkVersion(); updateCredit(realToday(), currentCfg()); publish() }

void updateCredit(String day, Map cfg) {
    Map c = cfg.credit as Map
    if (validMd(c.from) && validMd(c.until)) state.credit = creditOn(day, c) ? 'on' : 'off'
}

void evaluateSeason(String why) {
    Map cfg = currentCfg()
    List<String> errs = validateCfg(cfg)
    if (errs) { logWarn "season not evaluated, settings invalid: ${errs.join('; ')}"; return }
    Map inputs = evalInputs(debugEnable && why == "button", settings.testDate, settings.testMean, realToday())
    String day = inputs.day as String
    BigDecimal mean = inputs.mean as BigDecimal
    if (mean == null) mean = mean3((state.samples ?: [:]) as Map, day)
    state.lastEval = [day: day, mean: mean?.toPlainString(), why: why]
    updateCredit(day, cfg)
    if (state.season == null) { logWarn "no current season; open the app and pick one"; publish(); return }
    if (state.holdUntil && now() >= (state.holdUntil as Long)) { state.remove('holdUntil'); logInfo "automatic season changes resumed" }
    if (state.holdUntil) { logSched "automatic changes suspended until ${fmtTime(state.holdUntil as Long)}"; publish(); return }
    if (mean == null) logWarn "no 3-day mean (outdoor sensor silent or too few readings); only the date limits apply"
    Map r = nextSeason(state.season as String, day, mean, cfg)
    if (r.reason) {
        logInfo "season ${state.season} → ${r.season}: ${r.reason}"
        state.season = r.season
        state.since = day
    } else {
        logDebug "${why}: season stays ${state.season}, 3-day mean ${mean}"
    }
    publish()
}

// ── Commands ──────────────────────────────────────────────────────────

Map seasonCommand(Map req) {
    checkVersion()
    if (req.command == 'setSeason') return setSeasonManual(req.season, req.holdDays)
    if (req.command == 'resumeAuto') { resumeAuto(); return [ok: true] }
    return [ok: false, error: "unknown command ${req.command}".toString()]
}

Map setSeasonManual(Object season, Object holdDays) {
    Map p = parseSeasonArgs(season, holdDays)
    if (!p.ok) { logWarn "setSeason: ${p.error}"; return p }
    String was = state.season
    int days = p.days as int
    state.season = p.season
    state.since = realToday()
    if (days > 0) state.holdUntil = now() + days * DAY_MS
    else state.remove('holdUntil')
    logCmd "season set to ${p.season} by hand (was ${was})${days > 0 ? ', automatic changes suspended for ' + days + ' days' : ''}"
    publish()
    return [ok: true]
}

void resumeAuto() {
    state.remove('holdUntil')
    logCmd "automatic season changes resumed"
    publish()
}

void appButtonHandler(String btn) {
    checkVersion()
    if (btn == "btnEvaluate") evaluateSeason("button")
    else if (btn == "btnSetSeason") setSeasonManual(settings.manualSeason, settings.manualHoldDays)
    else if (btn == "btnResumeAuto") resumeAuto()
}


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

// One change at most per evaluation. mean is the 3-day mean, or null when there is none.
Map nextSeason(String season, String iso, BigDecimal mean, Map cfg) {
    Map w = cfg.w2s as Map
    Map f = cfg.f2w as Map
    Map s = cfg.summer as Map
    BigDecimal above = numOrNull(w.above)
    BigDecimal below = numOrNull(f.below)
    BigDecimal enter = numOrNull(s.enter)
    BigDecimal leave = numOrNull(s.leave)
    String m = fmtNum(mean)
    boolean summerOpen = inSpan(iso, s.from as String, s.until as String)
    boolean fallHalf = inSpan(iso, s.fallFrom as String, w.from as String)
    if (season == 'winter') {
        if (inMd(iso, w.from as String, w.until as String)) {
            if (mean != null && mean > above) return [season: 'spring', reason: "3-day mean ${m} above ${fmtNum(above)}".toString()]
            if (sameDay(iso, w.until as String)) return [season: 'spring', reason: 'last day of the winter to spring window']
        } else if (inSpan(iso, w.until as String, s.fallFrom as String)) {
            // A winter from fall-from on is the coming winter, set early by hand: keep it.
            return [season: 'spring', reason: 'past the winter to spring window']
        }
    } else if (season == 'spring' || season == 'fall') {
        if (summerOpen && mean != null && mean > enter) return [season: 'summer', reason: "3-day mean ${m} above ${fmtNum(enter)}".toString()]
        if (season == 'spring' && fallHalf) return [season: 'fall', reason: "fall from ${s.fallFrom}".toString()]
        if (season == 'fall') {
            if (inMd(iso, f.from as String, f.until as String)) {
                if (mean != null && mean < below) return [season: 'winter', reason: "3-day mean ${m} below ${fmtNum(below)}".toString()]
                if (sameDay(iso, f.until as String)) return [season: 'winter', reason: 'last day of the fall to winter window']
            } else if (inSpan(iso, f.until as String, w.from as String)) {
                return [season: 'winter', reason: 'past the fall to winter window']
            }
        }
    } else if (season == 'summer') {
        if (!summerOpen) return [season: fallHalf ? 'fall' : 'spring', reason: "outside the summer dates (${s.from} to ${s.until})".toString()]
        if (mean != null && mean < leave) return [season: fallHalf ? 'fall' : 'spring', reason: "3-day mean ${m} below ${fmtNum(leave)}".toString()]
    }
    return [season: season, reason: null]
}

// The seasons possible on iso from the date alone. Two inside a window where the weather decides.
List<String> seasonsOn(String iso, Map cfg) {
    Map w = cfg.w2s as Map
    Map f = cfg.f2w as Map
    Map s = cfg.summer as Map
    List<List> spans = [[f.until, w.from, ['winter']], [w.from, w.until, ['winter', 'spring']],
                        [w.until, s.from, ['spring']], [s.from, s.fallFrom, ['spring', 'summer']],
                        [s.fallFrom, s.until, ['summer', 'fall']], [s.until, f.from, ['fall']],
                        [f.from, f.until, ['fall', 'winter']]]
    List hit = spans.find { inSpan(iso, it[0] as String, it[1] as String) }
    return hit ? (hit[2] as List<String>) : seasonList()
}

boolean creditOn(String iso, Map credit) { return inMd(iso, credit.from as String, credit.until as String) }

Map parseSeasonArgs(Object season, Object holdDays) {
    String s = season == null ? '' : season.toString().trim().toLowerCase()
    if (!seasonList().contains(s)) return [ok: false, error: "unknown season '${season}'; use winter, spring, summer or fall".toString()]
    boolean blank = holdDays == null || holdDays.toString().trim() == ''
    BigDecimal d = blank ? new BigDecimal(3) : numOrNull(holdDays)
    if (d == null || d < 0 || d > 365 || d.stripTrailingZeros().scale() > 0)
        return [ok: false, error: "hold days must be a whole number from 0 to 365, got '${holdDays}'".toString()]
    return [ok: true, season: s, days: d.intValue()]
}

String nextPossible(String season, Map cfg, String unit) {
    Map w = cfg.w2s as Map
    Map f = cfg.f2w as Map
    Map s = cfg.summer as Map
    if (season == 'winter') return "spring between ${w.from} and ${w.until}, as soon as the 3-day mean is above ${fmtNum(w.above)} ${unit}".toString()
    if (season == 'spring') return "summer between ${s.from} and ${s.until} when the 3-day mean is above ${fmtNum(s.enter)} ${unit}; fall from ${s.fallFrom}".toString()
    if (season == 'summer') return "spring or fall when the 3-day mean is below ${fmtNum(s.leave)} ${unit}; fall on ${s.until} at the latest".toString()
    if (season == 'fall') return "winter between ${f.from} and ${f.until}, as soon as the 3-day mean is below ${fmtNum(f.below)} ${unit}; summer until ${s.until} when it is above ${fmtNum(s.enter)} ${unit}".toString()
    return ''
}

// samples: ISO day -> [s: sum of readings, n: number of readings]. Keeps the last 10 days.
Map addSample(Map samples, String iso, BigDecimal v) {
    Map out = [:]
    String oldest = addDays(iso, -10)
    (samples ?: [:]).each { k, x -> if ((k as String) >= oldest) out[k as String] = x }
    Map day = (out[iso] ?: [s: 0, n: 0]) as Map
    out[iso] = [s: (day.s as BigDecimal) + v, n: (day.n as int) + 1]
    return out
}

// Test day and test mean apply to button-driven evaluations only, never to scheduled runs.
Map evalInputs(boolean useTest, Object testDate, Object testMean, String today) {
    return [day: (useTest && validIso(testDate)) ? (testDate as String) : today, mean: useTest ? numOrNull(testMean) : null]
}

String omCoord(Object v) {
    BigDecimal b = numOrNull(v)
    return b == null ? null : b.setScale(1, BigDecimal.ROUND_HALF_UP).toPlainString()
}

// kind 'archive': start to end; 'forecast': today and the next 6 days. Null without coordinates.
String omUrl(String kind, Object lat, Object lon, String scale, String start, String end) {
    String la = omCoord(lat)
    String lo = omCoord(lon)
    if (la == null || lo == null) return null
    String base = kind == 'archive' ? 'https://archive-api.open-meteo.com/v1/archive' : 'https://api.open-meteo.com/v1/forecast'
    String q = "latitude=${la}&longitude=${lo}&daily=temperature_2m_mean&timezone=auto"
    if (scale == 'F') q += '&temperature_unit=fahrenheit'
    q += kind == 'archive' ? "&start_date=${start}&end_date=${end}" : '&forecast_days=7'
    return "${base}?${q}".toString()
}

// Open-Meteo daily response -> [ISO day: BigDecimal]; days without a mean left out.
Map parseDaily(Object json) {
    Map out = [:]
    Map daily = json instanceof Map ? ((Map) json).daily as Map : null
    List t = daily?.time as List
    List v = daily?.temperature_2m_mean as List
    if (!t || !v) return out
    for (int i = 0; i < Math.min(t.size(), v.size()); i++) {
        BigDecimal m = numOrNull(v[i])
        if (m != null && validIso(t[i])) out[t[i] as String] = m.setScale(2, BigDecimal.ROUND_HALF_UP)
    }
    return out
}

BigDecimal sensorMean(Map samples, String day, int minSamples) {
    Map x = (samples ?: [:])[day] as Map
    int n = x ? (x.n as int) : 0
    return n >= minSamples ? ((x.s as BigDecimal) / n).setScale(2, BigDecimal.ROUND_HALF_UP) : null
}

// Mean of sensor minus Open-Meteo over the last 7 days before today that have both; null when none.
BigDecimal sensorOffset(Map samples, Map om, String today, int minSamples = 12) {
    List<BigDecimal> diffs = []
    List<String> days = (om ?: [:]).keySet().collect { it as String }.findAll { it < today }.sort().reverse()
    for (String d : days) {
        BigDecimal s = sensorMean(samples, d, minSamples)
        BigDecimal o = numOrNull(om[d])
        if (s != null && o != null) diffs << (s - o)
        if (diffs.size() == 7) break
    }
    if (!diffs) return null
    return ((diffs.sum() as BigDecimal) / diffs.size()).setScale(2, BigDecimal.ROUND_HALF_UP)
}

// One mean per day before today: Open-Meteo first, else the sensor corrected by its offset.
Map mergeDays(Map samples, Map om, String today, int minSamples = 12) {
    BigDecimal off = sensorOffset(samples, om, today, minSamples)
    Set<String> days = new TreeSet<String>()
    (samples ?: [:]).keySet().each { days << (it as String) }
    (om ?: [:]).keySet().each { days << (it as String) }
    Map out = [:]
    days.findAll { it < today }.each { String d ->
        BigDecimal o = numOrNull((om ?: [:])[d])
        BigDecimal s = sensorMean(samples, d, minSamples)
        if (o != null) out[d] = [mean: o, src: 'openmeteo']
        else if (s != null) out[d] = [mean: (off == null ? s : s - off).setScale(2, BigDecimal.ROUND_HALF_UP), src: 'sensor']
    }
    return out
}

Map meanValues(Map merged) {
    Map out = [:]
    (merged ?: [:]).each { k, v -> out[k as String] = (v as Map).mean }
    return out
}

// 3-day mean of the three days before day; null unless all three have a mean.
BigDecimal mean3At(Map means, String day) {
    List<BigDecimal> xs = (1..3).collect { int i -> numOrNull((means ?: [:])[addDays(day, -i)]) }
    if (xs.any { it == null }) return null
    return ((xs.sum() as BigDecimal) / 3).setScale(2, BigDecimal.ROUND_HALF_UP)
}

// The keep days before today.
Map pruneDays(Map days, String today, int keep) {
    String oldest = addDays(today, -keep)
    Map out = [:]
    (days ?: [:]).each { k, v -> if ((k as String) >= oldest && (k as String) < today) out[k as String] = v }
    return out
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

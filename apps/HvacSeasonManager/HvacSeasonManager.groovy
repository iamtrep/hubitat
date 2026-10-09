// Copyright (c) 2026 PJ
// SPDX-License-Identifier: MIT

/*
 HVAC Season Manager

 Publishes the heating and cooling season on a component device, from date windows
 and the outdoor temperature. The winter boundary changes once a year each way;
 summer follows the weather in and out.
*/

import com.hubitat.app.ChildDeviceWrapper
import groovy.transform.Field

@Field static final String CODE_VERSION = "0.3.1"

definition(
    name: "HVAC Season Manager",
    namespace: "iamtrep",
    author: "pj",
    description: "Publishes the heating and cooling season on a device, from dates and the outdoor temperature",
    menu: "Automations", // new in platform 2.5.0
    category: "Convenience",
    singleThreaded: true,
    importUrl: "https://raw.githubusercontent.com/iamtrep/hubitat/main/apps/HvacSeasonManager/HvacSeasonManager.groovy",
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
    dynamicPage(name: "mainPage", title: "", install: true, uninstall: true) {
        if (state.season == null && state.calibAuto == null && settings.omEnable != false && settings.w2sFrom == null)
            state.calibAuto = runCalibration(currentCfg())
        Map cfg = currentCfg()
        if (state.calib && (state.calib as Map).thr != calibThr(cfg)) state.remove('calib')
        Map cal = state.calib as Map
        List<String> errs = validateCfg(cfg)
        String u = unit()
        Map d = baseCfg()
        boolean fold = state.season != null
        Map edited = [outdoor: editedSince("outdoor", ["omEnable", "tempSensor"]), rules: editedSince("rules", RULE_INPUTS),
                      mirror: editedSince("mirror", ["mirrorEnable", "mirrorSeasonVar"]),
                      logging: editedSince("logging", ["txtEnable", "debugEnable"])]
        section {
            if (errs) paragraph "<div class='p-message p-message-error p-3 border-round'>${errs.collect { esc(it) }.join('<br>')}</div>"
            paragraph rawHtml: true, pageScript() + (state.season != null ? statusHtml(cfg) : '')
        }
        if (state.season == null) {
            List<String> poss = startOptions(cfg, errs)
            Map rep = poss.size() > 1 && !errs ? installReplay(cfg) : null
            section("Current season") {
                if (poss.size() == 1) paragraph "Season right now: <b>${esc(SEASON_OPTS[poss[0]])}</b>, from today's date."
                else if (rep) paragraph "Season right now: <b>${esc(SEASON_OPTS[rep.season])}</b>, from the daily means since ${esc(dayLabel(rep.from as String))}."
                else input "initialSeason", "enum", title: "Season right now: today's date allows ${poss.collect { SEASON_OPTS[it] }.join(' or ')}, depending on recent weather",
                           options: SEASON_OPTS.subMap(poss), required: true
            }
        } else {
            section("Change the season") {
                input "manualSeason", "enum", title: "Season", options: SEASON_OPTS, required: false, width: 4
                input "manualHoldDays", "number", title: "Suspend automatic changes for (days)", defaultValue: 3, range: "0..365", required: false, width: 4
                input "btnSetSeason", "button", title: "Set the season", width: 4, inputClass: "p-button bg-hubitat-primary-green text-white"
                if (state.holdUntil) input "btnResumeAuto", "button", title: "Resume automatic changes", width: 4, inputClass: "p-button p-button-outlined"
            }
        }
        section(sectionTitle("Outdoor temperature", outdoorSummary()), hideable: true, hidden: fold && !edited.outdoor) {
            input "omEnable", "bool", title: "Use Open-Meteo daily means (needs internet)", defaultValue: true, submitOnChange: true
            input "tempSensor", "capability.temperatureMeasurement", title: "Outdoor temperature sensor, used on days Open-Meteo has no mean", required: false, submitOnChange: true
            if (omEnable != false && omCoord(location.latitude) == null) paragraph "The hub has no location set, so Open-Meteo is not used."
            if (omEnable == false && !tempSensor) paragraph "No temperature source: the season changes on the date limits only."
            if (state.season == null) paragraph "The season follows the mean of the three previous daily means, checked every day at 05:00."
        }
        section(sectionTitle("Season rules", errs ? null : rulesSummary(cfg)), hideable: true, hidden: fold && !errs && !edited.rules && !cal) {
            if (!errs) paragraph rawHtml: true, yearHtml(cfg)
            if (state.season == null && state.calibAuto) paragraph rawHtml: true, autoCalibNote(state.calibAuto as Map)
            if (cal) {
                List<String> calErrs = cal.error ? [] : validateCfg(withDates(cfg, cal))
                paragraph rawHtml: true, calibHtml(cfg, cal, calErrs)
                if (!cal.error && !calErrs && (cal.w2s || cal.f2w || cal.summer))
                    input "btnCalibApply", "button", title: "Use these dates", width: 4, inputClass: "p-button bg-hubitat-primary-green text-white"
                input "btnCalibDiscard", "button", title: cal.error ? "Close" : "Keep the current dates", width: 4, inputClass: "p-button p-button-outlined"
            } else {
                input "btnCalibrate", "button", title: "Compute dates from local climate", width: 4, inputClass: "p-button p-button-outlined"
            }
            paragraph rawHtml: true, subhead("Winter → spring", "once a year")
            input "w2sFrom", "text", title: "From (MM-DD)", defaultValue: d.w2s.from, width: 4, submitOnChange: true
            input "w2sUntil", "text", title: "By (MM-DD, last day)", defaultValue: d.w2s.until, width: 4, submitOnChange: true
            input "w2sAbove", "decimal", title: "When the mean is above (${u})", defaultValue: d.w2s.above, width: 4, submitOnChange: true
            paragraph rawHtml: true, subhead("Fall → winter", "once a year")
            input "f2wFrom", "text", title: "From (MM-DD)", defaultValue: d.f2w.from, width: 4, submitOnChange: true
            input "f2wUntil", "text", title: "By (MM-DD, last day)", defaultValue: d.f2w.until, width: 4, submitOnChange: true
            input "f2wBelow", "decimal", title: "When the mean is below (${u})", defaultValue: d.f2w.below, width: 4, submitOnChange: true
            paragraph rawHtml: true, subhead("Summer", "follows the weather both ways")
            input "summerFrom", "text", title: "Summer from (MM-DD)", defaultValue: d.summer.from, width: 4, submitOnChange: true
            input "summerUntil", "text", title: "Summer until (MM-DD)", defaultValue: d.summer.until, width: 4, submitOnChange: true
            input "fallFrom", "text", title: "Fall from (MM-DD)", defaultValue: d.summer.fallFrom, width: 4, submitOnChange: true
            input "summerEnter", "decimal", title: "Enter summer above (${u})", defaultValue: d.summer.enter, width: 4, submitOnChange: true
            input "summerLeave", "decimal", title: "Leave summer below (${u})", defaultValue: d.summer.leave, width: 4, submitOnChange: true
            input "summerEnd", "decimal", title: "Summer end, for computing dates (${u})", defaultValue: d.summer.end, width: 4, submitOnChange: true
            paragraph "<span class='text-sm text-color-secondary'>Summer ends on its until date. Outside summer, spring becomes fall on the fall date. The summer-end threshold only sets those two dates when they are computed from local climate.</span>"
        }
        section(sectionTitle("Hub variable mirror", mirrorEnable ? "on" : "off"), hideable: true, hidden: fold && !edited.mirror) {
            input "mirrorEnable", "bool", title: "Mirror the season to hub variables, for rules not yet moved to the Season device", defaultValue: false, submitOnChange: true
            if (mirrorEnable) {
                input "mirrorSeasonVar", "enum", title: "String variable for the season", options: varNames("string"), required: false, submitOnChange: true
                input "labelWinter", "text", title: "Label for winter", defaultValue: "winter", width: 3
                input "labelSpring", "text", title: "Label for spring", defaultValue: "spring", width: 3
                input "labelSummer", "text", title: "Label for summer", defaultValue: "summer", width: 3
                input "labelFall", "text", title: "Label for fall", defaultValue: "fall", width: 3
            }
        }
        section(sectionTitle("Logging", "info ${txtEnable != false ? 'on' : 'off'}, debug ${debugEnable ? 'on' : 'off'}"), hideable: true, hidden: fold && !edited.logging) {
            input "txtEnable", "bool", title: "Enable info logging", defaultValue: true
            input "debugEnable", "bool", title: "Enable debug logging (turns off after 30 minutes)", defaultValue: false, submitOnChange: true
        }
        if (debugEnable) {
            section("Testing") {
                input "testDate", "text", title: "Test day (yyyy-MM-dd, blank = today)", required: false, width: 4
                input "testMean", "text", title: "Test 3-day mean (${u}; blank or none = from the daily means)", required: false, width: 4
                input "btnEvaluate", "button", title: "Evaluate now"
                input "btnRunDaily", "button", title: "Run the daily evaluation now"
            }
        }
        section { label title: "App name", required: false }
    }
}

@Field static final List<String> RULE_INPUTS = ['w2sFrom', 'w2sUntil', 'w2sAbove', 'f2wFrom', 'f2wUntil', 'f2wBelow', 'summerFrom', 'summerUntil',
                                                'fallFrom', 'summerEnter', 'summerLeave', 'summerEnd']

// True on the render right after one of the section's settings changed, so the section stays open while it is being edited.
boolean editedSince(String key, List<String> names) {
    String v = names.collect { "${settings[it]}" }.join('|')
    Map seen = (state.uiSeen ?: [:]) as Map
    boolean changed = seen.containsKey(key) && seen[key] != v
    seen[key] = v
    state.uiSeen = seen
    return changed
}

@Field static final List<String> MD_INPUTS = ['w2sFrom', 'w2sUntil', 'f2wFrom', 'f2wUntil', 'summerFrom', 'summerUntil', 'fallFrom']

// Bold section titles, and a month-day picker on the MM-DD inputs from the jQuery UI the page already loads.
String pageScript() {
    String names = MD_INPUTS.collect { "'${it}'" }.join(',')
    return """<style>
.sm-sum{font-weight:400;font-size:.85em;color:var(--text-color-secondary,#6c757d);margin-left:.75em}
#ui-datepicker-div .ui-datepicker-year{display:none}
#ui-datepicker-div .ui-state-active{color:#fff !important}
</style>
<script>
(function(){
  function attach(){
    var \$ = window.jQuery;
    if (!\$ || !\$.fn.datepicker) return;
    [${names}].forEach(function(n){
      var el = \$("input[name='settings[" + n + "]']");
      if (!el.length || el.hasClass('hasDatepicker')) return;
      el.attr('autocomplete', 'off').datepicker({dateFormat: 'mm-dd', showOtherMonths: true, selectOtherMonths: true,
        onSelect: function(){ \$(this).trigger('change'); }});
    });
  }
  setTimeout(attach, 0);
})();
</script>"""
}

String sectionTitle(String title, String summary) { summary ? "${title}<span class='sm-sum'>${esc(summary)}</span>".toString() : title }

String subhead(String title, String note) {
    return "<div class='text-sm font-semibold mt-2'>${esc(title)}${note ? " <span class='font-normal text-color-secondary'>${esc(note)}</span>" : ''}</div>".toString()
}

String outdoorSummary() {
    List<String> p = []
    if (omEnable != false) p << 'Open-Meteo'
    if (tempSensor) p << "sensor ${tempSensor.displayName}".toString()
    return p ? p.join(', ') : 'dates only'
}

String rulesSummary(Map cfg) {
    Map w = cfg.w2s as Map
    Map f = cfg.f2w as Map
    Map s = cfg.summer as Map
    return "Winter ${mdLabel(f.from as String)} – ${mdLabel(f.until as String)} · Spring ${mdLabel(w.from as String)} – ${mdLabel(w.until as String)} · Summer ${mdLabel(s.from as String)} – ${mdLabel(s.until as String)}".toString()
}

String calibHtml(Map cfg, Map cal, List<String> calErrs) {
    StringBuilder h = new StringBuilder("<div class='border-1 surface-border border-round p-3'>")
    h << "<div class='font-semibold mb-2'>Dates from ${esc(cal.years)} climate at this location</div>"
    if (cal.error) return h.append("<div class='p-message p-message-error p-2 m-0 border-round'>${esc(cal.error)}</div></div>").toString()
    String th = "class='text-color-secondary' style='text-align:left;font-weight:600;font-size:12px;text-transform:uppercase;padding:4px 8px;border-bottom:1px solid #dfe3e8'"
    h << "<table style='border-collapse:collapse;font-size:14px'><tr><th ${th}></th><th ${th}>Now</th><th ${th}>Computed</th></tr>"
    [['Winter → spring from', 'w2s', 'from'], ['Winter → spring by', 'w2s', 'until'], ['Fall → winter from', 'f2w', 'from'],
     ['Fall → winter by', 'f2w', 'until'], ['Summer from', 'summer', 'from'], ['Summer until', 'summer', 'until'],
     ['Fall from', 'summer', 'fallFrom']].each { List r ->
        String now = cfg[r[1]][r[2]] as String
        String got = (cal[r[1]] as Map)?.get(r[2]) as String
        String shown = got ? (got == now ? mdLabel(got) : "<b>${mdLabel(got)}</b>") : '<span class="text-color-secondary">not computed</span>'
        h << "<tr><td style='padding:4px 8px'>${r[0]}</td><td style='padding:4px 8px'>${mdLabel(now)}</td><td style='padding:4px 8px'>${shown}</td></tr>"
    }
    h << "</table>"
    ((cal.notes ?: []) as List).each { h << "<div class='text-sm mt-2'>${esc(it)}</div>" }
    if (calErrs) h << "<div class='p-message p-message-warn p-2 mt-2 mb-0 border-round'>With these dates: ${calErrs.collect { esc(it) }.join('; ')}. Adjust the thresholds and compute again.</div>"
    h << "<div class='text-sm text-color-secondary mt-2'>Computed for the thresholds above. Each window runs from the 10th to the 90th percentile of the day the 3-day mean first crossed its threshold.</div></div>"
    return h.toString()
}

String autoCalibNote(Map c) {
    if (c.error) return "<div class='text-sm text-color-secondary'>The dates could not be computed from local climate (${esc(c.error)}); they are defaults for a cold-winter climate.</div>".toString()
    String notes = ((c.notes ?: []) as List).collect { esc(it) }.join('<br>')
    return "<div class='text-sm text-color-secondary'>Dates computed from ${esc(c.years)} climate at this location.${notes ? '<br>' + notes : ''}</div>".toString()
}

// One 12-month bar per rule, with today marked. Positions are percentages of a 365-day year.
String yearHtml(Map cfg) {
    Map w = cfg.w2s as Map
    Map f = cfg.f2w as Map
    Map s = cfg.summer as Map
    List<String> months = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec']
    List<String> starts = ['01-01', '02-01', '03-01', '04-01', '05-01', '06-01', '07-01', '08-01', '09-01', '10-01', '11-01', '12-01']
    String today = realToday().substring(5)
    List<Map> rows = [
        [label: 'Winter → spring', bars: [[w.from, w.until, '#3f7a12']]],
        [label: 'Summer possible', bars: [[s.from, s.until, '#c98a0b']], mark: [s.fallFrom, '#b4470a', "Fall from ${mdLabel(s.fallFrom as String)}"]],
        [label: 'Fall → winter', bars: [[f.from, f.until, '#2f6fb3']]]
    ]
    String grid = starts.collect { "<div style='position:absolute;top:0;bottom:0;width:1px;background:#e1e5ea;left:${yearPct(doyOf(it))}%'></div>" }.join('')
    String todayLine = "<div style='position:absolute;top:-3px;bottom:-3px;width:2px;background:var(--text-color,#212529);left:${yearPct(doyOf(today))}%'></div>"
    StringBuilder h = new StringBuilder("<div style='overflow-x:auto;overflow-y:hidden;padding-right:16px'><div style='min-width:560px;display:grid;grid-template-columns:130px minmax(0,1fr);row-gap:6px;align-items:center;font-size:13px'>")
    h << "<div></div><div style='position:relative;height:18px'>"
    months.eachWithIndex { String m, int i -> h << "<div class='text-color-secondary' style='position:absolute;top:0;font-size:12px;left:${yearPct(doyOf(starts[i]))}%'>${m}</div>" }
    h << "</div>"
    rows.each { Map r ->
        h << "<div>${esc(r.label)}</div><div style='position:relative;height:22px;background:#f5f7f9;border-radius:3px'>${grid}"
        (r.bars as List<List>).each { List b ->
            String tip = "${mdLabel(b[0] as String)} – ${mdLabel(b[1] as String)}".toString()
            yearSpans(doyOf(b[0] as String), doyOf(b[1] as String)).each { List<Integer> sp ->
                h << "<div title='${esc(tip)}' style='position:absolute;top:3px;bottom:3px;border-radius:3px;background:${b[2]};left:${yearPct(sp[0])}%;width:${yearWidth(sp[0], sp[1])}%'></div>"
            }
        }
        List m = r.mark as List
        if (m) h << "<div title='${esc(m[2])}' style='position:absolute;top:0;bottom:0;width:4px;margin-left:-2px;background:${m[1]};left:${yearPct(doyOf(m[0] as String))}%'></div>"
        h << todayLine << "</div>"
    }
    h << "<div></div><div style='position:relative;height:16px'><div style='position:absolute;top:0;font-size:12px;transform:translateX(-50%);left:${yearPct(doyOf(today))}%'>today</div></div>"
    h << "</div></div>"
    return h.toString()
}

String yearPct(int doy) { new BigDecimal((doy - 1) * 100).divide(new BigDecimal(365), 2, BigDecimal.ROUND_HALF_UP).toPlainString() }

String yearWidth(int a, int b) { new BigDecimal((b - a + 1) * 100).divide(new BigDecimal(365), 2, BigDecimal.ROUND_HALF_UP).toPlainString() }

// A span that wraps past Dec 31 splits in two.
List<List<Integer>> yearSpans(int a, int b) { b >= a ? [[a, b]] : [[a, 365], [1, b]] }

@Field static final Map SEASON_CHIP = [winter: 'bg-blue-50 text-blue-800', spring: 'bg-green-50 text-green-800',
                                      summer: 'bg-yellow-50 text-yellow-900', fall: 'bg-orange-50 text-orange-800']

String statusHtml(Map cfg) {
    String u = unit()
    String season = state.season as String
    String since = validIso(state.since) ? dayLabel(state.since as String) : esc(state.since)
    StringBuilder h = new StringBuilder("<div class='border-1 surface-border border-round p-3 flex flex-column gap-3'>")
    h << "<div class='flex flex-wrap align-items-center gap-3'>"
    h << "<span class='${SEASON_CHIP[season] ?: ''} border-round-3xl px-3 py-1 text-2xl font-bold'>${esc(SEASON_OPTS[season])}</span>"
    h << "<span class='text-lg'>since ${since}</span>"
    h << "</div>"
    if (state.holdUntil) h << "<div class='p-message p-message-warn p-2 m-0 border-round'>Automatic changes suspended until ${esc(fmtTime(state.holdUntil as Long))}</div>"
    if (!validateCfg(cfg)) h << "<div><b>Next change:</b> ${esc(nextPossible(season, cfg, u))}</div>"

    String td = "style='padding:6px 8px;border-bottom:1px solid #eef0f3'"
    String tdn = "style='padding:6px 8px;border-bottom:1px solid #eef0f3;text-align:right'"
    String th = "class='text-color-secondary' style='text-align:left;font-weight:600;font-size:12px;text-transform:uppercase;padding:6px 8px;border-bottom:1px solid #dfe3e8'"
    String today = realToday()
    Map merged = mergeDays((state.samples ?: [:]) as Map, (state.om ?: [:]) as Map, today)
    h << "<div class='flex flex-wrap gap-4'><div style='flex:1 1 280px;min-width:0'><div class='font-semibold mb-1'>Last 3 days</div>"
    h << "<table style='border-collapse:collapse;width:100%;font-size:14px'><tr><th ${th}>Day</th><th ${th} style='text-align:right'>Mean</th><th ${th}>Source</th></tr>"
    (1..3).each { int i ->
        String d = addDays(today, -i)
        Map x = merged[d] as Map
        h << "<tr><td ${td}>${dayLabel(d)}</td><td ${tdn}>${x ? esc(fmtNum(x.mean)) + ' ' + u : '–'}</td><td ${td}>${x ? (x.src == 'openmeteo' ? 'Open-Meteo' : 'sensor') : 'no mean'}</td></tr>"
    }
    Map ev = state.lastEval as Map
    if (ev) h << "<tr><td style='padding:6px 8px'><b>3-day mean</b></td><td style='padding:6px 8px;text-align:right'><b>${ev.mean != null ? esc(ev.mean) + ' ' + u : 'not available'}</b></td><td class='text-color-secondary' style='padding:6px 8px'>evaluated ${validIso(ev.day) ? dayLabel(ev.day as String) : esc(ev.day)}</td></tr>"
    h << "</table></div>"

    Map o = state.outlook as Map
    if (o) {
        h << "<div style='flex:1.4 1 360px;min-width:0'><div class='font-semibold mb-1'>Forecast daily means</div>"
        List<Map> days = (o.days ?: []) as List<Map>
        if (days) {
            h << "<div style='overflow-x:auto'><div style='display:grid;grid-template-columns:repeat(${days.size()},minmax(52px,1fr));gap:6px'>"
            days.each { Map x -> h << "<div class='surface-100 border-round text-center py-2'><div class='text-xs text-color-secondary'>${dayLabel(x.day as String)}</div><div class='font-semibold'>${esc(fmtNum(x.mean))}</div></div>" }
            h << "</div></div>"
        }
        h << "<div class='mt-2'>${outlookText(o, u).collect { esc(it) }.join('<br>')}${days ? '. Values in ' + u + '.' : ''}</div></div>"
    }
    h << "</div>"

    String foot = "Checked every day at 05:00 against the mean of the three previous daily means."
    if (tempSensor) {
        BigDecimal off = sensorOffset((state.samples ?: [:]) as Map, (state.om ?: [:]) as Map, today)
        foot += " Sensor offset from Open-Meteo: ${off == null ? 'not known yet' : esc(fmtNum(off)) + ' ' + u}."
    }
    h << "<div class='text-sm text-color-secondary'>${foot}</div></div>"
    return h.toString()
}

// ── Lifecycle ─────────────────────────────────────────────────────────

void installed() { checkVersion(false); initialize() }

void updated() { checkVersion(false); unsubscribe(); unschedule(); initialize() }

void uninstalled() {
    removeAllInUseGlobalVar()
    if (getChildDevice(dni())) deleteChildDevice(dni())
}

void initialize() {
    // initialize() takes the replay only from the page's cache: a fresh Open-Meteo fetch here
    // would hold up Done for up to 15 s, so a cache miss goes to installSeasonJob() instead.
    if (state.season == null && !setInstallSeason(true)) runIn(1, "installSeasonJob")
    state.remove('installReplay')
    if (!state.lastDaily) state.lastDaily = addDays(realToday(), -1)
    state.remove('mirroredSeason')
    state.remove('mirroredCredit')
    state.remove('credit')
    ['creditFrom', 'creditUntil', 'mirrorCreditVar'].each { if (settings.containsKey(it)) app.removeSetting(it) }
    registerVars()
    seasonDevice()
    if (tempSensor) schedule("0 7 * * * ?", "sampleHandler")
    schedule("0 0 5 * * ?", "evaluateHandler")
    if (debugEnable) runIn(1800, "logsOff")
    startEvaluation("refresh")
    publish()
}

// Sets the season on install from the date, the archive replay or the user's pick. Returns false
// only when the replay is needed but not cached (cachedOnly) or Open-Meteo did not answer.
boolean setInstallSeason(boolean cachedOnly) {
    Map cfg = currentCfg()
    List<String> errs = validateCfg(cfg)
    List<String> poss = startOptions(cfg, errs)
    boolean wantReplay = poss.size() > 1 && !errs && settings.omEnable != false
    Map rep = wantReplay ? installReplay(cfg, cachedOnly) : null
    String s = poss.size() == 1 ? poss[0] : (rep ? rep.season as String : (poss.contains(settings.initialSeason) ? settings.initialSeason as String : null))
    if (!s) return !(wantReplay && cachedOnly)
    state.season = s
    state.since = (rep?.since ?: realToday()) as String
    logCfg "season set to ${s} on install${poss.size() == 1 ? ' from the date' : (rep ? ' from the daily means' : '')}"
    return true
}

void installSeasonJob() {
    checkVersion()
    if (state.season != null) return
    setInstallSeason(false)
    state.remove('installReplay')
    if (state.season == null) { logWarn "season not set: Open-Meteo did not answer; choose it on the app page"; return }
    startEvaluation("refresh")
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

// The defaults, with the dates computed from local climate on install when there are some.
Map baseCfg() { withDates(defaultCfg(location.temperatureScale as String), (state.calibAuto ?: [:]) as Map) }

Map currentCfg() {
    Map d = baseCfg()
    return [w2s:    [from: settings.w2sFrom ?: d.w2s.from, until: settings.w2sUntil ?: d.w2s.until, above: numOr(settings.w2sAbove, d.w2s.above)],
            f2w:    [from: settings.f2wFrom ?: d.f2w.from, until: settings.f2wUntil ?: d.f2w.until, below: numOr(settings.f2wBelow, d.f2w.below)],
            summer: [from: settings.summerFrom ?: d.summer.from, until: settings.summerUntil ?: d.summer.until,
                     enter: numOr(settings.summerEnter, d.summer.enter), leave: numOr(settings.summerLeave, d.summer.leave),
                     fallFrom: settings.fallFrom ?: d.summer.fallFrom, end: numOr(settings.summerEnd, d.summer.end)]]
}

String realToday() { return new Date().format('yyyy-MM-dd', location.timeZone) }

List<String> startOptions(Map cfg, List<String> errs) { errs ? seasonList() : seasonsOn(realToday(), cfg) }

// Replays the rules on archive means for the install page. Cached per day and settings: the page re-renders on every change.
Map installReplay(Map cfg, boolean cachedOnly = false) {
    if (settings.omEnable == false) return null
    String today = realToday()
    String key = "${today} ${cfg}".toString()
    Map c = state.installReplay as Map
    if (c?.key == key) return c.result as Map
    if (cachedOnly) return null
    Map a = replayAnchor(today, cfg)
    String url = a ? omUrl('archive', location.latitude, location.longitude, location.temperatureScale as String, addDays(a.day as String, -2), addDays(today, -1)) : null
    Map om = url ? omGet(url) : null
    Map h = om ? seasonFromHistory(today, om, cfg) : null
    if (h == null) return null
    Map res = [season: h.season, since: h.since, from: a.day]
    state.installReplay = [key: key, result: res]
    return res
}

Map omGet(String url, int timeout = 15) {
    Map out = null
    try {
        httpGet([uri: url, contentType: 'application/json', timeout: timeout]) { resp -> if (resp.status == 200) out = parseDaily(resp.data) }
    } catch (Exception e) {
        logWarn "Open-Meteo request failed: ${e.message}"
    }
    return out
}

// Proposed dates from the last 21 full years of daily means at the hub's location, for the current thresholds.
Map runCalibration(Map cfg) {
    int y = realToday().substring(0, 4) as int
    String first = "${y - 21}-01-01".toString()
    String last = "${y - 1}-12-31".toString()
    Map thr = [w2s: cfg.w2s.above, f2w: cfg.f2w.below, enter: cfg.summer.enter, end: cfg.summer.end]
    Map res = [made: realToday(), years: "${y - 21}–${y - 1}".toString(), thr: thr.toString()]
    String url = omUrl('archive', location.latitude, location.longitude, location.temperatureScale as String, first, last)
    if (url == null) return res + [error: 'The hub has no location set.']
    Map om = omGet(url, 60)
    if (!om) return res + [error: 'Open-Meteo did not answer; try again later.']
    Map c = calibrate(om, first, last, thr, (numOrNull(location.latitude) ?: 0) < 0)
    logCfg "dates from ${res.years} climate: ${c}"
    return res + c
}

String calibThr(Map cfg) { [w2s: cfg.w2s.above, f2w: cfg.f2w.below, enter: cfg.summer.enter, end: cfg.summer.end].toString() }


// ── Device ────────────────────────────────────────────────────────────

String dni() { "season-${app.id}" }

String deviceLabel() { app.getLabel() == "HVAC Season Manager" ? "HVAC Season" : "${app.getLabel()} Season" }

ChildDeviceWrapper seasonDevice() {
    ChildDeviceWrapper d = getChildDevice(dni())
    if (!d) {
        d = addChildDevice("iamtrep", "HVAC Season", dni(), [name: "HVAC Season", label: deviceLabel(), isComponent: true])
        logCfg "created ${d.displayName}"
    }
    return d
}

void publish() {
    seasonDevice().updateStatus([season: state.season])
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
}

void registerVars() {
    removeAllInUseGlobalVar()
    List<String> vars = settings.mirrorEnable ? ([settings.mirrorSeasonVar].findAll { it } as List<String>) : []
    if (vars) addInUseGlobalVar(vars)
}

void renameVariable(String oldName, String newName) {
    checkVersion()
    ['mirrorSeasonVar'].each { String k ->
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

void evaluateHandler() { checkVersion(); startEvaluation("daily") }

// Fetches Open-Meteo means when enabled and evaluates in the callback; evaluates at once otherwise.
// why "refresh" (on save) only fetches the means and the forecast.
void startEvaluation(String why) {
    String today = realToday()
    String start = addDays(today, -10)
    String last = state.lastDaily as String
    if (last && addDays(last, -2) < start) start = addDays(last, -2)
    if (start < addDays(today, -120)) start = addDays(today, -120)
    String url = settings.omEnable != false ? omUrl('archive', location.latitude, location.longitude, location.temperatureScale as String, start, addDays(today, -1)) : null
    if (url) {
        asynchttpGet("archiveHandler", [uri: url, contentType: 'application/json', timeout: 30], [why: why])
    } else {
        if (why != "refresh") evaluateSeason(why, null)
        updateOutlook([:])
    }
}

void archiveHandler(resp, Map data) {
    checkVersion()
    Map om = null
    if (!resp.hasError() && resp.status == 200) {
        try { om = parseDaily(resp.json) } catch (Exception e) { logDebug "Open-Meteo archive response unreadable: ${e.message}" }
    }
    if (!om) {
        if (!state.omWarned) { logWarn "Open-Meteo unavailable (HTTP ${resp.status}); using the sensor"; state.omWarned = true }
    } else {
        if (state.omWarned) { logInfo "Open-Meteo available again"; state.remove('omWarned') }
        Map kept = [:]
        kept.putAll((state.om ?: [:]) as Map)
        kept.putAll(om)
        state.om = pruneDays(kept, realToday(), 10)
    }
    if (data.why != "refresh") evaluateSeason(data.why as String, om)
    String url = omUrl('forecast', location.latitude, location.longitude, location.temperatureScale as String, null, null)
    if (url) asynchttpGet("forecastHandler", [uri: url, contentType: 'application/json', timeout: 30])
}

void forecastHandler(resp, Map data) {
    checkVersion()
    Map fc = [:]
    if (!resp.hasError() && resp.status == 200) {
        try { fc = parseDaily(resp.json) } catch (Exception e) { logDebug "Open-Meteo forecast response unreadable: ${e.message}" }
    }
    updateOutlook(fc)
}

void updateOutlook(Map forecast) {
    if (state.season == null) return
    Map cfg = currentCfg()
    if (validateCfg(cfg)) return
    String today = realToday()
    Map observed = meanValues(mergeDays((state.samples ?: [:]) as Map, (state.om ?: [:]) as Map, today))
    state.outlook = [made: today] + outlook(state.season as String, today, observed, forecast, cfg, holdLastDay())
}

// The last day whose 05:00 evaluation the hold suspends, or null.
String holdLastDay() { holdLastDayOf(state.holdUntil as Long, location.timeZone) }

void evaluateSeason(String why, Map fetched = null) {
    Map cfg = currentCfg()
    List<String> errs = validateCfg(cfg)
    if (errs) { logWarn "season not evaluated, settings invalid: ${errs.join('; ')}"; return }
    String today = realToday()
    Map om = [:]
    om.putAll((state.om ?: [:]) as Map)
    om.putAll(fetched ?: [:])
    Map means = meanValues(mergeDays((state.samples ?: [:]) as Map, om, today))
    Map inputs = evalInputs(debugEnable && why == "button", settings.testDate, settings.testMean, today)
    String day = inputs.day as String
    BigDecimal mean = inputs.mean != null ? inputs.mean as BigDecimal : mean3At(means, day)
    state.lastEval = [day: day, mean: mean?.toPlainString(), why: why]
    if (state.season == null) { logWarn "no current season; open the app and pick one"; publish(); return }
    String holdLast = holdLastDay()
    if (state.holdUntil && now() >= (state.holdUntil as Long)) { state.remove('holdUntil'); logInfo "automatic season changes resumed" }
    if (mean == null) logWarn "no 3-day mean (no Open-Meteo or sensor mean for one of the three previous days); only the date limits apply"
    if (why == "daily") {
        String first = state.lastDaily ? addDays(state.lastDaily as String, 1) : today
        if (first < addDays(today, -120)) first = addDays(today, -120)
        if (first > today) first = today
        String was = state.season as String
        Map r = runDays(was, first, today, means, cfg, holdLast)
        (r.changes as List<Map>).each { Map c ->
            logInfo "season ${was} → ${c.season}${c.day == today ? '' : ' on ' + c.day}: ${c.reason}"
            was = c.season as String
        }
        if (r.changes) {
            state.season = r.season
            state.since = ((r.changes as List<Map>)[-1]).day
        } else {
            logDebug "daily: season stays ${state.season}, 3-day mean ${mean}"
        }
        state.lastDaily = today
    } else {
        if (state.holdUntil) { logSched "automatic changes suspended until ${fmtTime(state.holdUntil as Long)}"; publish(); return }
        Map r = nextSeason(state.season as String, day, mean, cfg)
        if (r.reason) {
            logInfo "season ${state.season} → ${r.season}: ${r.reason}"
            state.season = r.season
            state.since = day
        } else {
            logDebug "${why}: season stays ${state.season}, 3-day mean ${mean}"
        }
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
    else if (btn == "btnRunDaily") startEvaluation("daily")
    else if (btn == "btnCalibrate") state.calib = runCalibration(currentCfg())
    else if (btn == "btnCalibDiscard") state.remove('calib')
    else if (btn == "btnCalibApply") applyCalibration()
}

void applyCalibration() {
    Map c = state.calib as Map
    if (!c || c.error) return
    Map names = [w2s: [from: 'w2sFrom', until: 'w2sUntil'], f2w: [from: 'f2wFrom', until: 'f2wUntil'],
                 summer: [from: 'summerFrom', until: 'summerUntil', fallFrom: 'fallFrom']]
    names.each { String rule, Map m -> m.each { String k, String n -> if (c[rule]?.get(k)) app.updateSetting(n, [type: 'text', value: c[rule][k]]) } }
    logCfg "dates set from ${c.years} climate"
    state.remove('calib')
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
            summer: [from: '05-10', until: '09-14', enter: inScale(17.0, scale), leave: inScale(12.0, scale), fallFrom: '08-15', end: inScale(17.0, scale)]]
}

List<String> validateCfg(Map cfg) {
    List<String> errs = []
    Map w = cfg.w2s as Map
    Map f = cfg.f2w as Map
    Map s = cfg.summer as Map
    Map dates = ['Winter to spring window from': w.from, 'Winter to spring window until': w.until,
                 'Fall to winter window from': f.from, 'Fall to winter window until': f.until,
                 'Summer possible from': s.from, 'Summer possible until': s.until, 'Fall from': s.fallFrom]
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
    if (season == 'winter') return "spring between ${mdLabel(w.from as String)} and ${mdLabel(w.until as String)}, as soon as the 3-day mean is above ${fmtNum(w.above)} ${unit}".toString()
    if (season == 'spring') return "summer between ${mdLabel(s.from as String)} and ${mdLabel(s.until as String)} when the 3-day mean is above ${fmtNum(s.enter)} ${unit}; fall from ${mdLabel(s.fallFrom as String)}".toString()
    if (season == 'summer') return "spring or fall when the 3-day mean is below ${fmtNum(s.leave)} ${unit}; fall on ${mdLabel(s.until as String)} at the latest".toString()
    if (season == 'fall') return "winter between ${mdLabel(f.from as String)} and ${mdLabel(f.until as String)}, as soon as the 3-day mean is below ${fmtNum(f.below)} ${unit}; summer until ${mdLabel(s.until as String)} when it is above ${fmtNum(s.enter)} ${unit}".toString()
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

// The daily evaluation applied to each day from first to last, in order, one change a day at most.
// Days up to holdLast (ISO, or null) are skipped.
Map runDays(String season, String first, String last, Map means, Map cfg, String holdLast = null) {
    String s = season
    List<Map> changes = []
    List<String> missing = []
    String d = first
    int guard = 0
    while (d <= last && guard++ < 400) {
        if (holdLast == null || d > holdLast) {
            BigDecimal m = mean3At(means, d)
            if (m == null) missing << d
            Map r = nextSeason(s, d, m, cfg)
            if (r.reason) {
                changes << [day: d, season: r.season, reason: r.reason, mean: m]
                s = r.season as String
            }
        }
        d = addDays(d, 1)
    }
    return [season: s, changes: changes, missing: missing]
}

// The most recent day at or before today whose season the date alone settles.
Map replayAnchor(String today, Map cfg) {
    for (int i = 0; i <= 366; i++) {
        String d = addDays(today, -i)
        List<String> p = seasonsOn(d, cfg)
        if (p.size() == 1) return [day: d, season: p[0]]
    }
    return null
}

// Today's season, replayed from the anchor on daily means. Null when a replayed day lacks its 3-day mean.
Map seasonFromHistory(String today, Map means, Map cfg) {
    Map a = replayAnchor(today, cfg)
    if (a == null) return null
    if (a.day == today) return [season: a.season, since: null]
    Map r = runDays(a.season as String, addDays(a.day as String, 1), today, means, cfg)
    if (r.missing) return null
    List<Map> ch = r.changes as List<Map>
    return [season: r.season, since: ch ? ch[-1].day : null]
}

// A month-day as "Mar 11".
String mdLabel(String md) { return dayLabel("2001-${md}".toString()) }

String dayLabel(String iso) {
    List<String> m = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec']
    return "${m[(iso.substring(5, 7) as int) - 1]} ${iso.substring(8) as int}".toString()
}

// The rules run forward over the next 7 evaluations. observed: means before today; forecast: means from today on.
Map outlook(String season, String today, Map observed, Map forecast, Map cfg, String holdLast) {
    Map means = [:]
    (observed ?: [:]).each { k, v -> if ((k as String) < today) means[k as String] = v }
    (forecast ?: [:]).each { k, v -> if ((k as String) >= today) means[k as String] = v }
    String last = addDays(today, 7)
    Map r = runDays(season, addDays(today, 1), last, means, cfg, holdLast)
    List<Map> changes = (r.changes as List<Map>).collect { Map c -> c + [kind: (c.reason as String).startsWith('3-day mean') ? 'forecast' : 'date'] }
    List<Map> days = (forecast ?: [:]).keySet().collect { it as String }.findAll { it >= today }.sort().collect { String d -> [day: d, mean: forecast[d]] }
    return [through: last, days: days, changes: changes, forecast: !(forecast ?: [:]).isEmpty()]
}

List<String> outlookText(Map o, String unit) {
    List<String> out = []
    if (!o.forecast) out << 'No forecast available; only the date limits are shown.'
    List<Map> ch = (o.changes ?: []) as List<Map>
    if (!ch) out << "The forecast predicts no season change through ${dayLabel(o.through as String)}".toString()
    ch.each { Map c ->
        String day = dayLabel(c.day as String)
        if (c.kind == 'date') out << "${(c.season as String).capitalize()} on ${day} (${c.reason})".toString()
        else out << "Forecast: ${c.season} on ${day} (3-day mean ${fmtNum(c.mean)} ${unit})".toString()
    }
    return out
}

// The last day whose 05:00 local evaluation a hold ending at holdUntil suspends, or null.
String holdLastDayOf(Long holdUntil, TimeZone tz) {
    if (holdUntil == null) return null
    Calendar c = Calendar.getInstance(tz)
    c.setTimeInMillis(holdUntil)
    long msOfDay = ((c.get(Calendar.HOUR_OF_DAY) * 60L + c.get(Calendar.MINUTE)) * 60L + c.get(Calendar.SECOND)) * 1000L + c.get(Calendar.MILLISECOND)
    boolean after5 = msOfDay > 5 * 3600000L
    java.text.SimpleDateFormat f = new java.text.SimpleDateFormat('yyyy-MM-dd')
    f.setTimeZone(tz)
    String day = f.format(c.getTime())
    return after5 ? day : addDays(day, -1)
}

// Day of the year on a leap calendar, so Feb 29 and every later date line up across years.
int leapDoy(String md) {
    List<Integer> start = [0, 31, 60, 91, 121, 152, 182, 213, 244, 274, 305, 335]
    return start[(md.substring(0, 2) as int) - 1] + (md.substring(3) as int)
}

String leapMd(int doy) {
    List<Integer> len = [31, 29, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31]
    int d = doy
    int m = 0
    while (d > len[m]) { d -= len[m]; m++ }
    return "${(m + 1).toString().padLeft(2, '0')}-${d.toString().padLeft(2, '0')}".toString()
}

// Where each first-crossing search starts, early enough to precede every window. Half a year later south of the equator.
Map calibAnchors(boolean south) {
    Map n = [spring: '02-15', summer: '04-01', fall: '08-01', winter: '09-15']
    return south ? n.collectEntries { k, v -> [k, leapMd((leapDoy(v as String) - 1 + 183) % 366 + 1)] } : n
}

// The p-th percentile of month-days, counted from the anchor so a span past Dec 31 still sorts. Rounds half to even.
String percentileMd(List<String> mds, String anchor, int p) {
    int a = leapDoy(anchor)
    List<Integer> xs = mds.collect { (leapDoy(it) - a + 366) % 366 }.sort()
    BigDecimal k = new BigDecimal((xs.size() - 1) * p).divide(new BigDecimal(100), 6, BigDecimal.ROUND_HALF_EVEN)
    int f = k.intValue()
    int c = Math.min(f + 1, xs.size() - 1)
    int off = (xs[f] + (xs[c] - xs[f]) * (k - f)).setScale(0, BigDecimal.ROUND_HALF_EVEN).intValue()
    return leapMd((a - 1 + off) % 366 + 1)
}

// Proposed dates from daily means (ISO day -> mean) between first and last, the way tools/season-calibration does it:
// for each year, the first day from the anchor on which the mean of the 3 previous daily means crosses the threshold;
// a window runs from the 10th to the 90th percentile of those days. thr: w2s, f2w, enter, end, in the means' scale.
// A rule is left out when its threshold was crossed in fewer than two thirds of the years.
Map calibrate(Map means, String first, String last, Map thr, boolean south) {
    List<String> days = []
    List<BigDecimal> vals = []
    Map<String, Integer> at = [:]
    for (String d = first; d <= last; d = addDays(d, 1)) { at[d] = vals.size(); days << d; vals << numOrNull(means[d]) }
    Map a = calibAnchors(south)
    int y0 = first.substring(0, 4) as int
    int y1 = last.substring(0, 4) as int
    List<String> notes = []
    Closure cross = { String name, String anchor, boolean above, Object t ->
        BigDecimal th = numOrNull(t)
        List<String> hits = []
        int years = 0
        for (int y = y0; y <= y1; y++) {
            Integer s = at["${y}-${anchor}".toString()]
            if (s == null || s < 3) continue
            years++
            for (int i = s; i < Math.min(s + 365, vals.size()); i++) {
                BigDecimal x = vals[i - 1], y2 = vals[i - 2], z = vals[i - 3]
                if (x == null || y2 == null || z == null) continue
                BigDecimal m = (x + y2 + z) / 3
                if (above ? m > th : m < th) { hits << days[i].substring(5); break }
            }
        }
        if (years == 0 || hits.size() * 3 < years * 2) {
            notes << "${name}: the 3-day mean went ${above ? 'above' : 'below'} ${fmtNum(th)} in ${hits.size()} of ${years} years, too few to set dates".toString()
            return null
        }
        return hits
    }
    Map out = [:]
    List<String> w = cross('Winter → spring', a.spring as String, true, thr.w2s)
    if (w) out.w2s = [from: percentileMd(w, a.spring as String, 10), until: percentileMd(w, a.spring as String, 90)]
    List<String> f = cross('Fall → winter', a.winter as String, false, thr.f2w)
    if (f) out.f2w = [from: percentileMd(f, a.winter as String, 10), until: percentileMd(f, a.winter as String, 90)]
    List<String> s = cross('Summer', a.summer as String, true, thr.enter)
    List<String> e = cross('Summer end', a.fall as String, false, thr.end)
    if (s && e) out.summer = [from: percentileMd(s, a.summer as String, 10), until: percentileMd(e, a.fall as String, 90),
                              fallFrom: percentileMd(e, a.fall as String, 10)]
    out.notes = notes
    return out
}

// cfg with the dates of a calibration result in place of its own, where the result has them.
Map withDates(Map cfg, Map cal) {
    Map out = [w2s: [:] + (cfg.w2s as Map), f2w: [:] + (cfg.f2w as Map), summer: [:] + (cfg.summer as Map)]
    if (cal?.w2s) out.w2s.putAll(cal.w2s as Map)
    if (cal?.f2w) out.f2w.putAll(cal.f2w as Map)
    if (cal?.summer) out.summer.putAll(cal.summer as Map)
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

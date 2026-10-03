// Copyright (c) 2026 PJ
// SPDX-License-Identifier: MIT

/*
 Thermostat Scheduler+ Program

 One zone: schedules thermostats from named profiles, with holds, mode overrides,
 an eco offset and pause handling. Publishes its state on a component device.
 Spec: docs/thermostat-scheduler-replacement-spec.md.
*/

import groovy.transform.Field

@Field static final String CODE_VERSION = "0.1.0"

definition(
    name: "Thermostat Scheduler+ Program",
    namespace: "iamtrep",
    author: "pj",
    description: "One Thermostat Scheduler+ program",
    category: "Convenience",
    parent: "iamtrep:Thermostat Scheduler+",
    singleThreaded: true,
    importUrl: "https://raw.githubusercontent.com/iamtrep/hubitat/main/apps/ThermostatSchedulerPlus/ThermostatSchedulerPlusProgram.groovy",
    iconUrl: "", iconX2Url: ""
)

preferences {
    page(name: "mainPage")
    page(name: "profilesPage")
    page(name: "schedulesPage")
    page(name: "optionsPage")
}

// ── UI ────────────────────────────────────────────────────────────────

@Field static final String PRI = "p-button bg-hubitat-primary-green text-white"
@Field static final String SEC = "p-button p-button-outlined"
@Field static final Map KIND_OPTS = [sunrise: "Sunrise", sunset: "Sunset", time: "A specific time", var: "Time from a hub variable"]
@Field static final List<String> DAY_ABBR = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"]
@Field static final List<String> DAY_NAME = ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"]

@Field static final String TSP_CSS = """<style>
.tsp-t{border-collapse:collapse;width:100%;font-size:15px}
.tsp-t th{text-align:left;font-weight:500;opacity:.7;border-bottom:1px solid rgba(128,128,128,.4);padding:10px 12px;white-space:nowrap;font-size:14px}
.tsp-t td{border-bottom:1px solid rgba(128,128,128,.25);padding:4px 12px;vertical-align:middle}
.tsp-t.tsp-dg th,.tsp-t.tsp-dg td{text-align:center}
.tsp-t .tsp-l{text-align:left!important;font-weight:500}
.tsp-tw{overflow-x:auto}
.tsp-heat{color:#b3261e;font-weight:500}.tsp-cool{color:#1a5fa3;font-weight:500}.tsp-dim{opacity:.65}
.tsp-sel{background:rgba(61,127,31,.1)}
.tsp-now{font-size:12px;font-weight:700;color:#2c5d16;margin-left:6px}
.tsp-note{font-size:14px;opacity:.75;margin:4px 0}
.tsp-big{font-size:24px;font-weight:500}
.tsp-h{font-size:16px;font-weight:700;margin:12px 0 6px;padding-bottom:6px;border-bottom:1px solid rgba(128,128,128,.3)}
.tsp-edit{background:rgba(128,128,128,.08);border-radius:4px}
</style>"""

String esc(Object s) { s == null ? '' : s.toString().replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;').replace("'", '&#39;') }

// Same markup the firmware's Thermostat Scheduler 2.0.3 emits for grid buttons (2.5.2.129),
// without submitOnChange: the click reloads the page instead of re-posting the form, which
// would overwrite the values the handler just wrote. Edit inputs submit on change instead.
String cellButton(String name, String label, boolean raw = false) {
    return "<div class='form-group'><input type='hidden' name='${name}.type' value='button'></div>" +
           "<div><div class='app-button-link' onclick='buttonClick(this); return false;' " +
           "style='cursor:pointer;color:#1a5fa3;min-height:44px;display:flex;align-items:center'>${raw ? label : esc(label)}</div></div>" +
           "<input type='hidden' name='settings[${name}]' value=''>"
}

String unit() { "°${location.temperatureScale}" }

String fmtT(Object v) {
    BigDecimal n = numOrNull(v)
    return n == null ? '' : n.setScale(1, BigDecimal.ROUND_HALF_UP).toPlainString()
}

String heatSpan(Object v) { v == null ? '' : "<span class='tsp-heat'>${fmtT(v)}</span>" }
String coolSpan(Object v) { v == null ? '' : "<span class='tsp-cool'>${fmtT(v)}</span>" }

Map uiCfg() { return (state.config ?: defaultConfig()) as Map }

Map cfgCopy() { return parseJson(groovy.json.JsonOutput.toJson(uiCfg())) as Map }

Map uiState() { return (state.ui ?: [:]) as Map }

String modeName(Object id) { location.modes.find { (it.id as Long) == (id as Long) }?.name ?: "mode ${id}" }

Map modeOptions() { location.modes.collectEntries { [(it.id as String): it.name] } }

List<String> varNamesOfType(List<String> types) {
    Map all = getAllGlobalVars() ?: [:]
    return all.findAll { k, v -> types.contains(v?.type?.toString()?.toLowerCase()) }.keySet().collect { it as String }.sort()
}

void clearEdits() { settings.keySet().findAll { it.startsWith('ed') }.each { app.removeSetting(it) } }

String errorPara() {
    return state.uiError ? "<div class='p-message p-message-error p-3 border-round'>${esc(state.uiError)}</div>" : ''
}

// "Name · 21.0" for a profile reference, or the custom values.
String refLabel(Map cfg, String profile, Map custom) {
    if (custom) return "Custom · ${heatSpan(custom.heat)}${custom.cool != null ? ' / ' + coolSpan(custom.cool) : ''}"
    Map p = (cfg.profiles as List<Map>).find { it.name == profile }
    if (!p) return esc(profile)
    String h = p.heatVar ? esc(p.heatVar) : heatSpan(p.heat)
    return "${esc(profile)}${h ? ' · ' + h : ''}"
}

String usage(String profile, Map cfg = null) {
    Map c = cfg ?: uiCfg()
    List<String> out = []
    (c.schedules as List<Map>).each { Map s ->
        if (s.type == 'time') {
            int n = (s.groups as List<Map>).sum(0) { Map g -> (g.periods as List<Map>).count { Map p -> !p.custom && p.profile == profile } } as int
            if (n) out << "${s.name} (${n})".toString()
        } else if ((s.rows as List<Map>)?.any { Map r -> !r.custom && r.profile == profile }) out << (s.name as String)
    }
    (c.overrides as List<Map>).findAll { it.profile == profile }.each { out << "Mode override: ${modeName(it.modeId)}".toString() }
    Map hold = (state.rt as Map)?.hold as Map
    if (hold?.kind == 'profile' && hold.profile == profile) out << "the hold now in effect"
    return out.join(', ')
}

void renameProfileRefs(Map c, String from, String to) {
    (c.schedules as List<Map>).each { Map s ->
        (s.groups as List<Map>)?.each { Map g -> (g.periods as List<Map>).each { Map p -> if (p.profile == from) p.profile = to } }
        (s.rows as List<Map>)?.each { Map r -> if (r.profile == from) r.profile = to }
    }
    (c.overrides as List<Map>).each { Map o -> if (o.profile == from) o.profile = to }
}

// The running hold and the main page's hold choice name profiles too; called once the renamed config is stored.
void renameHeldProfile(String from, String to) {
    Map rt = state.rt as Map
    Map hold = rt?.hold as Map
    if (hold?.kind == 'profile' && hold.profile == from) { hold.profile = to; state.rt = rt }
    if (settings.holdSel == from) app.updateSetting("holdSel", [type: "enum", value: to])
}

boolean saveConfig(Map doc, Map renamed = null) {
    List<String> errs = validateConfig(doc, validationEnv())
    if (errs) { state.uiError = errs.join('; '); logWarn "not saved: ${state.uiError}"; return false }
    state.remove('uiError')
    state.config = doc
    if (renamed) renameHeldProfile(renamed.from as String, renamed.to as String)
    clearEdits()
    logCfg "configuration saved"
    evaluate("config edited", false)
    return true
}

String uniqueName(List<String> taken, String base) {
    if (!taken.contains(base)) return base
    int i = 2
    while (taken.contains("${base} ${i}".toString())) i++
    return "${base} ${i}".toString()
}

Map mainPage() {
    boolean installed = app.getInstallationState() == "COMPLETE"
    dynamicPage(name: "mainPage", install: true, uninstall: true) {
        section {
            paragraph TSP_CSS
            input "thermostats", "capability.thermostat", title: "Select Thermostats", multiple: true, required: true, submitOnChange: true
        }
        if (installed && thermostats) {
            Map cfg = coreConfig()
            Map rt = state.rt as Map
            Map ctx = buildCtx(cfg)
            Map target = (rt.lastTarget ?: [layer: 'none']) as Map
            Map sm = statusMap(cfg, rt, target, ctx)
            section("Now") {
                paragraph nowHtml(cfg, rt, target, ctx, sm), width: 7
                paragraph thermostatTable(), width: 5
            }
            section("Control") {
                Map sched = (cfg.schedules as List<Map>).find { it.name == cfg.active }
                Map nx = sched?.type == 'time' ? nextTransitionOf(sched, ctx) : null
                input "btnApply", "button", title: "Apply now", width: 2, inputClass: PRI
                if (nx) input "btnAdvance", "button", title: "Advance to ${esc(nx.custom ? 'custom values' : nx.profile)}", width: 3, inputClass: SEC
                input "btnEco", "button", title: rt.eco ? "Turn eco off" : "Turn eco on", width: 2, inputClass: SEC
                input "btnPause", "button", title: rt.paused ? "Resume" : "Pause", width: 2, inputClass: SEC
                if (rt.hold) input "btnResume", "button", title: "End hold", width: 2, inputClass: SEC
                Map holdOpts = (cfg.profiles as List<Map>).collectEntries { [(it.name): it.name] } + [custom: "Custom setpoints"]
                input "holdSel", "enum", title: "Hold", options: holdOpts, width: 3, submitOnChange: true, required: false
                if (holdSel == 'custom') {
                    input "holdHeat", "decimal", title: "Heating setpoint (${unit()})", width: 2, submitOnChange: true, required: false
                    input "holdCool", "decimal", title: "Cooling setpoint (${unit()})", width: 2, submitOnChange: true, required: false
                }
                String nxText = nx ? "Next transition (${hhmmOf(nx.at as long, ctx.tz as TimeZone)})" : "Next transition"
                input "holdUntil", "enum", title: "Until", width: 3, submitOnChange: true, defaultValue: "next",
                      options: [next: nxText, minutes: "For a duration", at: "A date and time", indefinite: "Resumed by hand"]
                if (holdUntil == 'minutes') input "holdMinutes", "number", title: "Minutes", width: 2, submitOnChange: true, required: false
                if (holdUntil == 'at') {
                    input "holdDate", "date", title: "Date", width: 2, submitOnChange: true, required: false
                    input "holdTime", "time", title: "Time", width: 2, submitOnChange: true, required: false
                }
                input "btnHold", "button", title: "Start hold", width: 2, inputClass: SEC
            }
        }
        section("Setup") {
            Map cfg = uiCfg()
            href "profilesPage", title: "Profiles", description: esc((cfg.profiles as List<Map>)*.name.join(', '))
            href "schedulesPage", title: "Schedules", description: esc((cfg.schedules as List<Map>).collect { it.name == cfg.active ? "${it.name} (active)" : it.name }.join(', '))
            href "optionsPage", title: "Overrides and options", description: esc(optionsSummary(cfg))
            def d = installed ? getChildDevice("tsp-${app.id}") : null
            if (d) href "statusDevice", title: "Status device", description: esc(d.displayName), url: "/device/edit/${d.id}", style: "external"
        }
        section {
            label title: "Program name", required: true
        }
        if (debugEnable) {
            section("Testing") {
                input "testClock", "text", title: "Test clock (yyyy-MM-dd HH:mm, blank = real time)", required: false
                input "btnEvaluate", "button", title: "Evaluate now"
                input "btnStart", "button", title: "Run the hub-start handler"
                input "testConfigJson", "textarea", title: "Configuration JSON", required: false
                input "btnLoadConfig", "button", title: "Load configuration"
            }
        }
    }
}

String optionsSummary(Map cfg) {
    List<String> parts = (cfg.overrides as List<Map>).collect { "${modeName(it.modeId)} mode uses ${it.profile}".toString() }
    parts << "eco ${fmtT(settings.ecoOffset != null ? settings.ecoOffset : 2.0)} ${unit()}".toString()
    parts << (settings.whilePaused == 'off' ? "when paused, turn thermostats off" : "when paused, leave thermostats alone")
    return parts.join(' · ')
}

String nowHtml(Map cfg, Map rt, Map target, Map ctx, Map sm) {
    TimeZone tz = ctx.tz as TimeZone
    String status = sm.status as String
    String prof = target.profile == 'custom' ? 'Custom setpoints' : (target.profile ?: '')
    StringBuilder h = new StringBuilder()
    boolean good = status in ['schedule', 'mode', 'hold'] && rt.lastApply == 'ok'
    h << "<div class='p-message ${good ? 'p-message-success' : 'p-message-warn'} p-3 border-round'>"
    if (target.layer in ['schedule', 'mode', 'hold']) {
        h << "<div class='tsp-big'>${esc(prof)} · ${heatSpan(target.heat)}${target.cool != null ? ' / ' + coolSpan(target.cool) : ''} ${unit()}</div>"
    } else h << "<div class='tsp-big'>${status == 'paused' ? 'Paused' : (status == 'restricted' ? 'Restricted' : 'Nothing scheduled')}</div>"
    Map sched = (cfg.schedules as List<Map>).find { it.name == cfg.active }
    String why
    if (status == 'paused') why = "The status device switch is off. While paused: ${(cfg.options as Map).whilePaused == 'off' ? 'thermostats off' : 'thermostats left as they are'}."
    else if (status == 'restricted') why = "A restriction is active. While paused: ${(cfg.options as Map).whilePaused == 'off' ? 'thermostats off' : 'thermostats left as they are'}."
    else if (target.layer == 'hold') {
        Map hold = rt.hold as Map
        String until = hold.end == 'next' ? 'the next transition' : (hold.end == 'indefinite' ? 'resumed by hand' : "${isoDate(hold.until as long, tz)} ${hhmmOf(hold.until as long, tz)}")
        why = "Hold until ${until}"
    } else if (target.layer == 'mode') why = "Mode override: ${modeName(target.overrideModeId)}"
    else if (sched?.type == 'mode') why = target.layer == 'none' ? "${sched.name} schedule has no row for ${modeName(ctx.modeId)}: thermostats left as they are" : "${sched.name} schedule, hub mode ${modeName(ctx.modeId)}"
    else {
        Map cur = sched ? currentTransition(sched, ctx) : null
        why = cur ? "${sched.name} schedule, ${cur.group}, ${cur.period} since ${hhmmOf(cur.at as long, tz)}" : "${cfg.active} schedule has no period yet"
    }
    h << "<div>${esc(why)}</div>"
    if (status == 'manual') {
        List names = (thermostats ?: []).findAll { (rt.manual as List).contains(it.id as String) }*.displayName
        h << "<div>Changed by hand on ${esc(names.join(', '))}; the next program write replaces it</div>"
    }
    Map nx = sched?.type == 'time' ? nextTransitionOf(sched, ctx) : null
    if (nx) {
        Map v = nx.custom ? valuesOf(nx.custom as Map, ctx) : profileValues(cfg, nx.profile as String, ctx)
        h << "<div style='margin-top:8px'>Next: <strong>${esc(nx.custom ? 'custom values' : nx.profile)}</strong> at ${hhmmOf(nx.at as long, tz)}"
        if (v) h << " (${heatSpan(v.heat)}${v.cool != null ? ' / ' + coolSpan(v.cool) : ''} ${unit()})"
        h << "</div>"
    }
    String off = "${fmtT((cfg.eco as Map).offset)} ${unit()}"
    String eco = rt.eco ? "Eco offset on (${off})" : "Eco offset off (${off} when on)"
    String chk = rt.verify ? "write check pending" : (rt.lastApply == 'ok' ? "last writes confirmed" : (rt.lastApply == 'partial' ? "some writes not confirmed" : "writes not confirmed"))
    if (rt.checkedAt && !rt.verify) chk += " at ${hhmmOf(rt.checkedAt as long, tz)}"
    h << "<div>${esc(eco)} · ${esc(chk)}</div></div>"
    return h.toString()
}

String thermostatTable() {
    StringBuilder h = new StringBuilder("<div class='tsp-tw'><table class='tsp-t'><thead><tr><th>Thermostat</th><th>Mode</th><th>Heat</th><th>Cool</th><th>Temp</th><th>State</th></tr></thead><tbody>")
    (thermostats ?: []).each { d ->
        h << "<tr><td>${esc(d.displayName)}</td><td>${esc(d.currentValue('thermostatMode'))}</td><td>${heatSpan(d.currentValue('heatingSetpoint'))}</td>"
        h << "<td>${coolSpan(d.currentValue('coolingSetpoint'))}</td><td>${fmtT(d.currentValue('temperature'))}</td><td>${esc(d.currentValue('thermostatOperatingState'))}</td></tr>"
    }
    return h.append("</tbody></table></div>").toString()
}

Map profilesPage() {
    dynamicPage(name: "profilesPage", title: "Profiles") {
        Map cfg = uiCfg()
        Map ui = uiState()
        List<Map> profiles = cfg.profiles as List<Map>
        section {
            paragraph TSP_CSS + "<p class='tsp-note'>Schedules, mode overrides and holds use these by name. A blank value leaves the thermostat as it is.</p>"
            StringBuilder t = new StringBuilder("<div class='tsp-tw'><table class='tsp-t'><thead><tr><th>Profile</th><th>Heat</th><th>Cool</th><th>Fan</th><th>Mode</th><th>Used in</th></tr></thead><tbody>")
            profiles.eachWithIndex { Map p, int i ->
                String u = usage(p.name as String, cfg)
                String heat = p.heatVar ? "${esc(p.heatVar)} (${fmtT(getGlobalVar(p.heatVar as String)?.value)} ${unit()})" : (p.heat != null ? "${fmtT(p.heat)} ${unit()}" : '')
                String cool = p.coolVar ? "${esc(p.coolVar)} (${fmtT(getGlobalVar(p.coolVar as String)?.value)} ${unit()})" : (p.cool != null ? "${fmtT(p.cool)} ${unit()}" : '')
                t << "<tr${ui.profile == i ? " class='tsp-sel'" : ''}><td>${cellButton("epf~${i}", p.name as String)}</td>"
                t << "<td>${heat ? "<span class='tsp-heat'>${heat}</span>" : "<span class='tsp-dim'>unchanged</span>"}</td>"
                t << "<td>${cool ? "<span class='tsp-cool'>${cool}</span>" : "<span class='tsp-dim'>unchanged</span>"}</td>"
                t << "<td>${p.fan ? esc(p.fan) : "<span class='tsp-dim'>unchanged</span>"}</td><td>${p.mode ? esc(p.mode) : "<span class='tsp-dim'>unchanged</span>"}</td>"
                t << "<td>${u ? esc(u) : "<span class='tsp-dim'>Not used</span>"}</td></tr>"
            }
            paragraph t.append("</tbody></table></div>").toString()
            input "btnProfileAdd", "button", title: "Add profile", width: 3, inputClass: SEC
        }
        if (ui.profile != null && (ui.profile as int) < profiles.size()) {
            Map p = profiles[ui.profile as int]
            section("Edit profile: ${esc(p.name)}", sectionClass: "tsp-edit") {
                if (state.uiError) paragraph errorPara()
                input "edName", "text", title: "Name", width: 3, submitOnChange: true, required: false
                if (edUseVars) {
                    List<String> nv = varNamesOfType(['bigdecimal', 'integer'])
                    input "edHeatVar", "enum", title: "Heating setpoint variable", options: nv, width: 3, submitOnChange: true, required: false
                    input "edCoolVar", "enum", title: "Cooling setpoint variable", options: nv, width: 3, submitOnChange: true, required: false
                } else {
                    input "edHeat", "decimal", title: "Heating setpoint (${unit()})", width: 2, submitOnChange: true, required: false
                    input "edCool", "decimal", title: "Cooling setpoint (${unit()})", width: 2, submitOnChange: true, required: false
                }
                input "edFan", "enum", title: "Fan mode", options: [unchanged: "Leave unchanged", auto: "auto", circulate: "circulate", on: "on"],
                      defaultValue: "unchanged", width: 2, submitOnChange: true
                input "edMode", "enum", title: "Thermostat mode", options: [unchanged: "Leave unchanged", heat: "heat", cool: "cool", auto: "auto", off: "off"],
                      defaultValue: "unchanged", width: 2, submitOnChange: true
                input "edUseVars", "bool", title: "Take setpoints from hub variables", submitOnChange: true
                input "btnProfileSave", "button", title: "Save", width: 2, inputClass: PRI
                input "btnProfileCancel", "button", title: "Cancel", width: 2, inputClass: SEC
                String u = usage(p.name as String, cfg)
                input "btnProfileDelete", "button", title: u ? "Delete (in use)" : "Delete", width: 3, inputClass: SEC, disabled: u as boolean
            }
        }
    }
}

int uiSchedIndex(Map cfg) {
    List<Map> scheds = cfg.schedules as List<Map>
    Map ui = uiState()
    if (ui.schedule != null && (ui.schedule as int) < scheds.size()) return ui.schedule as int
    int a = scheds.findIndexOf { it.name == cfg.active }
    return a < 0 ? 0 : a
}

Map schedulesPage() {
    dynamicPage(name: "schedulesPage", title: "Schedules") {
        Map cfg = uiCfg()
        Map ui = uiState()
        List<Map> scheds = cfg.schedules as List<Map>
        int si = uiSchedIndex(cfg)
        Map s = scheds[si]
        section {
            paragraph TSP_CSS
            if (state.uiError && ui.group == null && ui.row == null) paragraph errorPara()
            scheds.eachWithIndex { Map x, int i ->
                input "esc~${i}", "button", title: esc(x.name == cfg.active ? "${x.name} · active" : x.name), width: 2, inputClass: i == si ? PRI : SEC
            }
            input "btnScheduleNew", "button", title: "New schedule", width: 2, inputClass: SEC
        }
        section {
            input "edSchedName", "text", title: "Schedule name", defaultValue: s.name, width: 3, submitOnChange: true, required: false
            input "edSchedType", "enum", title: "Schedule by", options: [time: "Time of day", mode: "Hub mode"], defaultValue: s.type, width: 3, submitOnChange: true, required: false
            if ((settings.edSchedName && settings.edSchedName != s.name) || (settings.edSchedType && settings.edSchedType != s.type))
                input "btnScheduleSave", "button", title: "Save name and type", width: 3, inputClass: PRI
            input "btnScheduleCopy", "button", title: "Copy to a new schedule", width: 3, inputClass: SEC
        }
        if (s.type == 'time') timeScheduleSections(cfg, s, si, ui)
        else modeScheduleSections(cfg, s, si, ui)
        section {
            if (s.name != cfg.active) input "btnScheduleActivate", "button", title: "Make ${esc(s.name)} the active schedule", width: 4, inputClass: PRI
            input "btnScheduleDelete", "button", title: "Delete schedule", width: 3, inputClass: SEC, disabled: s.name == cfg.active
        }
    }
}

void timeScheduleSections(Map cfg, Map s, int si, Map ui) {
    List<Map> groups = s.groups as List<Map>
    section("Day groups") {
        StringBuilder t = new StringBuilder("<div class='tsp-tw' style='max-width:720px'><table class='tsp-t tsp-dg'><thead><tr><th class='tsp-l'>Group</th>")
        DAY_ABBR.each { t << "<th>${it}</th>" }
        t << "</tr></thead><tbody>"
        groups.eachWithIndex { Map g, int gi ->
            t << "<tr><td class='tsp-l'>${cellButton("egn~${gi}", g.name as String)}</td>"
            (1..7).each { int d ->
                boolean on = (g.days as List).collect { it as int }.contains(d)
                t << "<td>${cellButton("edg~${gi}~${d}", on ? '&#9745;' : '&#9744;', true)}</td>"
            }
            t << "</tr>"
        }
        t << "<tr><td class='tsp-l tsp-dim'>New group</td>"
        (1..7).each { int d -> t << "<td>${cellButton("edg~new~${d}", '+')}</td>" }
        paragraph t.append("</tbody></table></div><p class='tsp-note'>Each day is in one group. Taking a day out of its group starts a new group with a copy of its periods.</p>").toString()
        if (ui.groupName != null && ui.schedule == si) {
            if (state.uiError) paragraph errorPara()
            input "edGroupName", "text", title: "Group name", width: 3, submitOnChange: true, required: false
            input "btnGroupSave", "button", title: "Save", width: 2, inputClass: PRI
            input "btnGroupCancel", "button", title: "Cancel", width: 2, inputClass: SEC
        }
    }
    Map ctx = buildCtx(coreConfig())
    Map cur = s.name == cfg.active ? currentTransition(s, ctx) : null
    section {
        groups.eachWithIndex { Map g, int gi ->
            StringBuilder t = new StringBuilder("<div class='tsp-h'>${esc(g.name)}</div><div class='tsp-tw'><table class='tsp-t'><thead><tr><th>Period</th><th>Starts</th><th>Profile</th></tr></thead><tbody>")
            (g.periods as List<Map>).eachWithIndex { Map p, int pi ->
                boolean sel = ui.group == gi && ui.period == pi
                boolean now = cur && cur.group == g.name && cur.period == p.name
                t << "<tr${sel ? " class='tsp-sel'" : ''}><td>${cellButton("epd~${gi}~${pi}", p.name as String)}${now ? "<span class='tsp-now'>NOW</span>" : ''}</td>"
                t << "<td>${esc(startLabel(p.start as Map))}</td><td>${refLabel(cfg, p.profile as String, p.custom as Map)}</td></tr>"
            }
            if (!g.periods) t << "<tr><td colspan='3' class='tsp-dim'>No periods: thermostats left as they are</td></tr>"
            t << "</tbody></table></div>${cellButton("apd~${gi}", "Add period")}"
            paragraph t.toString(), width: 6
        }
    }
    if (ui.group != null && ui.period != null && ui.schedule == si && (ui.group as int) < groups.size()) {
        Map g = groups[ui.group as int]
        boolean isNew = (ui.period as int) < 0
        String pname = isNew ? 'new period' : (g.periods as List<Map>)[ui.period as int]?.name
        section("Edit period: ${esc(g.name)} · ${esc(pname)}", sectionClass: "tsp-edit") {
            if (state.uiError) paragraph errorPara()
            input "edName", "text", title: "Period name", width: 3, submitOnChange: true, required: false
            input "edKind", "enum", title: "Starts at", options: KIND_OPTS, defaultValue: "time", width: 3, submitOnChange: true
            String kind = settings.edKind ?: 'time'
            if (kind == 'time') input "edAt", "time", title: "Time", width: 2, submitOnChange: true, required: false
            if (kind in ['sunrise', 'sunset']) input "edOffset", "number", title: "Offset in minutes (+/−)", width: 2, submitOnChange: true, required: false
            if (kind == 'var') input "edVar", "enum", title: "Hub variable", options: varNamesOfType(['datetime']), width: 3, submitOnChange: true, required: false
            Map popts = (cfg.profiles as List<Map>).collectEntries { [(it.name): it.name] } + [custom: "Custom values for this period"]
            input "edProfile", "enum", title: "Profile", options: popts, width: 3, submitOnChange: true, required: false
            if (settings.edProfile == 'custom') {
                input "edHeat", "decimal", title: "Heating setpoint (${unit()})", width: 2, submitOnChange: true, required: false
                input "edCool", "decimal", title: "Cooling setpoint (${unit()})", width: 2, submitOnChange: true, required: false
            }
            if (kind in ['sunrise', 'sunset']) {
                Map ctx2 = buildCtx(coreConfig())
                String today = isoDate(ctx2.now as long, ctx2.tz as TimeZone)
                Long base = startOf([kind: kind, offset: 0], today, ctx2)
                Long at = startOf([kind: kind, offset: (settings.edOffset ?: 0) as int], today, ctx2)
                if (base != null) paragraph "<p class='tsp-note'>Today ${kind} is at ${hhmmOf(base, ctx2.tz as TimeZone)}, so this period starts at ${hhmmOf(at, ctx2.tz as TimeZone)}.</p>"
            }
            input "btnPeriodSave", "button", title: "Save", width: 2, inputClass: PRI
            input "btnPeriodCancel", "button", title: "Cancel", width: 2, inputClass: SEC
            if (!isNew) input "btnPeriodRemove", "button", title: "Remove period", width: 3, inputClass: SEC
        }
    }
}

String startLabel(Map st) {
    if (!st) return ''
    if (st.kind == 'time') return st.at as String
    if (st.kind == 'var') return "Variable ${st.name}"
    int off = (st.offset ?: 0) as int
    String base = st.kind == 'sunrise' ? 'Sunrise' : 'Sunset'
    return off == 0 ? base : "${base} ${off < 0 ? '−' : '+'} ${Math.abs(off)} min"
}

void modeScheduleSections(Map cfg, Map s, int si, Map ui) {
    List<Map> rows = (s.rows ?: []) as List<Map>
    section("By hub mode") {
        StringBuilder t = new StringBuilder("<div class='tsp-tw' style='max-width:760px'><table class='tsp-t'><thead><tr><th>When the hub mode is</th><th>Profile</th></tr></thead><tbody>")
        rows.eachWithIndex { Map r, int i ->
            String modes = (r.modes as List).collect { modeName(it) }.join(', ')
            t << "<tr${ui.row == i ? " class='tsp-sel'" : ''}><td>${cellButton("emr~${i}", modes ?: '(no mode)')}</td><td>${refLabel(cfg, r.profile as String, r.custom as Map)}</td></tr>"
        }
        List covered = rows.collectMany { (it.modes as List).collect { m -> m as Long } }
        location.modes.findAll { !covered.contains(it.id as Long) }.each { m ->
            t << "<tr><td class='tsp-dim'>${esc(m.name)}</td><td class='tsp-dim'>No row: thermostats left as they are</td></tr>"
        }
        paragraph t.append("</tbody></table></div>").toString()
        input "btnRowAdd", "button", title: "Add row", width: 2, inputClass: SEC
        paragraph "<p class='tsp-note'>A row can cover several modes. Mode overrides still apply on top of this schedule.</p>"
    }
    if (ui.row != null && ui.schedule == si) {
        boolean isNew = (ui.row as int) < 0
        String title = isNew ? 'new row' : (rows[ui.row as int]?.modes as List)?.collect { modeName(it) }?.join(', ')
        section("Edit row: ${esc(title)}", sectionClass: "tsp-edit") {
            if (state.uiError) paragraph errorPara()
            input "edModes", "mode", title: "Modes", multiple: true, width: 4, submitOnChange: true, required: false
            Map popts = (cfg.profiles as List<Map>).collectEntries { [(it.name): it.name] } + [custom: "Custom values for this row"]
            input "edProfile", "enum", title: "Profile", options: popts, width: 4, submitOnChange: true, required: false
            if (settings.edProfile == 'custom') {
                input "edHeat", "decimal", title: "Heating setpoint (${unit()})", width: 2, submitOnChange: true, required: false
                input "edCool", "decimal", title: "Cooling setpoint (${unit()})", width: 2, submitOnChange: true, required: false
            }
            input "btnRowSave", "button", title: "Save", width: 2, inputClass: PRI
            input "btnRowCancel", "button", title: "Cancel", width: 2, inputClass: SEC
            if (!isNew) input "btnRowRemove", "button", title: "Remove row", width: 3, inputClass: SEC
        }
    }
}

Map optionsPage() {
    dynamicPage(name: "optionsPage", title: "Overrides and options") {
        Map cfg = uiCfg()
        Map ui = uiState()
        List<Map> ovs = cfg.overrides as List<Map>
        section("Mode overrides") {
            StringBuilder t = new StringBuilder(TSP_CSS + "<div class='tsp-tw'><table class='tsp-t'><thead><tr><th>While the hub mode is</th><th>Use profile</th></tr></thead><tbody>")
            ovs.eachWithIndex { Map o, int i ->
                t << "<tr${ui.override == i ? " class='tsp-sel'" : ''}><td>${cellButton("eov~${i}", modeName(o.modeId))}</td><td>${refLabel(cfg, o.profile as String, null)}</td></tr>"
            }
            if (!ovs) t << "<tr><td colspan='2' class='tsp-dim'>No overrides</td></tr>"
            paragraph t.append("</tbody></table></div>").toString()
            input "btnOverrideAdd", "button", title: "Add override", width: 3, inputClass: SEC
            paragraph "<p class='tsp-note'>Overrides apply on top of any schedule. A hold set to end at the next transition also ends when an override starts or ends.</p>"
            if (ui.override != null) {
                if (state.uiError) paragraph errorPara()
                input "edOvMode", "mode", title: "While the hub mode is", width: 4, submitOnChange: true, required: false
                input "edProfile", "enum", title: "Use profile", options: (cfg.profiles as List<Map>)*.name, width: 4, submitOnChange: true, required: false
                input "btnOverrideSave", "button", title: "Save", width: 2, inputClass: PRI
                input "btnOverrideCancel", "button", title: "Cancel", width: 2, inputClass: SEC
                if ((ui.override as int) >= 0) input "btnOverrideRemove", "button", title: "Remove override", width: 3, inputClass: SEC
            }
        }
        section("Eco offset") {
            input "ecoOffset", "decimal", title: "Offset (${unit()})", defaultValue: 2.0, width: 3
            if (app.getInstallationState() == "COMPLETE") input "btnEco", "button", title: (state.rt as Map)?.eco ? "Turn eco off" : "Turn eco on", width: 3, inputClass: SEC
            input "ecoOnOverrides", "bool", title: "Also apply eco on top of mode overrides", defaultValue: true
            paragraph "<p class='tsp-note'>Heating setpoint minus the offset, cooling setpoint plus the offset. Changing the offset while eco is on recalculates from the schedule.</p>"
        }
        section("Pause and restrictions") {
            paragraph "<p class='tsp-note'>The ${esc(app.label)} scheduler device switch pauses the program when off. These optional restrictions pause it too.</p>"
            input "pauseSwitch", "capability.switch", title: "Pause when this switch is…", required: false, submitOnChange: true
            if (pauseSwitch) input "pauseWhenSwitch", "enum", title: "…in this state", options: ["off", "on"], defaultValue: "off", width: 3
            restrictTimeInputs("restrictFrom", "Only between")
            if (settings.restrictFrom && settings.restrictFrom != 'any') restrictTimeInputs("restrictTo", "and")
            input "restrictDays", "enum", title: "Only on days", multiple: true, required: false, width: 4,
                  options: ["1": "Monday", "2": "Tuesday", "3": "Wednesday", "4": "Thursday", "5": "Friday", "6": "Saturday", "7": "Sunday"]
            input "restrictModes", "mode", title: "Only in modes", multiple: true, required: false, width: 4
            input "whilePaused", "enum", title: "While paused", options: ["leave": "Leave thermostats as they are", "off": "Turn thermostats off"], defaultValue: "leave", width: 6
            input "onResume", "enum", title: "When the pause ends", options: ["restore": "Restore the thermostat mode and apply the schedule", "leaveOff": "Leave thermostats off"], defaultValue: "restore", width: 6
        }
        section("Writing to thermostats") {
            input "separation", "decimal", title: "Required heat/cool separation (${unit()})", defaultValue: 2.0, width: 4
            input "verifyWrites", "bool", title: "Check each write and report thermostats that do not take the new value (resending is Hubitat's Command Retry)", defaultValue: true
            input "applyOnStart", "bool", title: "Apply the schedule after a hub restart", defaultValue: true
        }
        section("Logging") {
            input "txtEnable", "bool", title: "Info logging", defaultValue: true
            input "debugEnable", "bool", title: "Debug logging (turns off after 30 minutes)", defaultValue: false, submitOnChange: true
            if (debugEnable) input "traceEnable", "bool", title: "Trace logging", defaultValue: false
        }
    }
}

void restrictTimeInputs(String n, String title) {
    input n, "enum", title: title, options: [any: "Any time", time: "A specific time", sunrise: "Sunrise", sunset: "Sunset"],
          defaultValue: "any", width: 3, submitOnChange: true
    String k = settings[n] as String
    if (k == 'time') input "${n}At", "time", title: "Time", width: 3, required: false
    if (k in ['sunrise', 'sunset']) input "${n}Offset", "number", title: "Offset in minutes (+/−)", width: 3, required: false
}

Map restrictTime(String n) {
    String k = settings[n] as String
    if (!k || k == 'any') return null
    if (k == 'time') return settings["${n}At"] ? [kind: 'time', at: hhmmSetting(settings["${n}At"])] : null
    return [kind: k, offset: (settings["${n}Offset"] ?: 0) as int]
}

String hhmmSetting(Object v) {
    if (v == null) return null
    String s = v.toString()
    if (s ==~ /\d{1,2}:\d{2}/) return s.length() == 4 ? "0${s}" : s
    return hhmmOf(timeToday(s, location.timeZone).getTime(), location.timeZone)
}

// ── UI edits (buttons) ────────────────────────────────────────────────

void prefill(String name, String type, Object value) {
    if (value == null || value == '') app.removeSetting(name)
    else app.updateSetting(name, [type: type, value: value])
}

void prefillTargets(String profile, Map custom) {
    prefill("edProfile", "enum", custom ? 'custom' : profile)
    if (custom) { prefill("edHeat", "decimal", custom.heat); prefill("edCool", "decimal", custom.cool) }
}

Map customFromEdits() {
    return settings.edProfile == 'custom' ? [heat: settings.edHeat, cool: settings.edCool] : null
}

boolean uiButton(String btn) {
    Map ui = uiState()
    if (btn ==~ /epf~\d+/) {
        int i = btn.split('~')[1] as int
        Map p = (uiCfg().profiles as List<Map>)[i]
        if (!p) return true
        clearEdits(); state.remove('uiError')
        state.ui = [profile: i]
        prefill("edName", "text", p.name)
        prefill("edHeat", "decimal", p.heat); prefill("edCool", "decimal", p.cool)
        prefill("edFan", "enum", p.fan ?: 'unchanged'); prefill("edMode", "enum", p.mode ?: 'unchanged')
        prefill("edUseVars", "bool", (p.heatVar || p.coolVar) ? true : null)
        prefill("edHeatVar", "enum", p.heatVar); prefill("edCoolVar", "enum", p.coolVar)
        return true
    }
    if (btn == "btnProfileAdd") {
        Map c = cfgCopy()
        String n = uniqueName((c.profiles as List<Map>)*.name as List<String>, "New profile")
        (c.profiles as List<Map>) << [name: n]
        if (saveConfig(c)) { state.ui = [profile: (c.profiles as List).size() - 1]; prefill("edName", "text", n) }
        return true
    }
    if (btn == "btnProfileCancel" || btn == "btnPeriodCancel" || btn == "btnRowCancel" || btn == "btnOverrideCancel" || btn == "btnGroupCancel") {
        state.ui = ui.schedule != null ? [schedule: ui.schedule] : [:]
        state.remove('uiError'); clearEdits()
        return true
    }
    if (btn == "btnProfileSave") {
        if (ui.profile == null) return true
        Map c = cfgCopy(); int i = ui.profile as int; String oldName = (c.profiles as List<Map>)[i].name
        boolean vars = settings.edUseVars == true
        Map np = [name: (settings.edName ?: oldName) as String, heat: vars ? null : settings.edHeat, cool: vars ? null : settings.edCool,
                  fan: settings.edFan in [null, 'unchanged'] ? null : settings.edFan, mode: settings.edMode in [null, 'unchanged'] ? null : settings.edMode,
                  heatVar: vars ? settings.edHeatVar : null, coolVar: vars ? settings.edCoolVar : null].findAll { k, v -> v != null }
        (c.profiles as List<Map>)[i] = np
        Map renamed = np.name != oldName ? [from: oldName, to: np.name] : null
        if (renamed) renameProfileRefs(c, oldName, np.name as String)
        if (saveConfig(c, renamed)) state.ui = [:]
        return true
    }
    if (btn == "btnProfileDelete") {
        if (ui.profile == null) return true
        String n = (uiCfg().profiles as List<Map>)[ui.profile as int].name
        String u = usage(n)
        if (u) { state.uiError = "${n} is used by ${u}".toString(); logWarn state.uiError; return true }
        Map c = cfgCopy(); (c.profiles as List).remove(ui.profile as int)
        if (saveConfig(c)) state.ui = [:]
        return true
}
    return scheduleButton(btn, ui) || overrideButton(btn, ui)
}

boolean scheduleButton(String btn, Map ui) {
    Map cfg = uiCfg()
    int si = uiSchedIndex(cfg)
    if (btn ==~ /esc~\d+/) {
        clearEdits(); state.remove('uiError')
        state.ui = [schedule: btn.split('~')[1] as int]
        return true
    }
    if (btn ==~ /edg~(\d+|new)~\d/) {
        String[] parts = btn.split('~')
        Map c = cfgCopy(); Map s = (c.schedules as List<Map>)[si]
        if (moveDay(s, parts[1], parts[2] as int) && saveConfig(c)) state.ui = [schedule: si]
        return true
    }
    if (btn ==~ /egn~\d+/) {
        int gi = btn.split('~')[1] as int
        clearEdits(); state.remove('uiError')
        state.ui = [schedule: si, groupName: gi]
        prefill("edGroupName", "text", ((cfg.schedules as List<Map>)[si].groups as List<Map>)[gi]?.name)
        return true
    }
    if (btn ==~ /epd~\d+~\d+/ || btn ==~ /apd~\d+/) {
        String[] parts = btn.split('~')
        int gi = parts[1] as int
        Map g = ((cfg.schedules as List<Map>)[si].groups as List<Map>)[gi]
        if (!g) return true
        clearEdits(); state.remove('uiError')
        if (parts[0] == 'apd') {
            state.ui = [schedule: si, group: gi, period: -1]
            prefill("edName", "text", uniqueName((g.periods as List<Map>)*.name as List<String>, "New period"))
            prefill("edKind", "enum", "time")
            prefill("edProfile", "enum", (cfg.profiles as List<Map>)[0]?.name)
            return true
        }
        int pi = parts[2] as int
        Map p = (g.periods as List<Map>)[pi]
        if (!p) return true
        state.ui = [schedule: si, group: gi, period: pi]
        Map st = p.start as Map
        prefill("edName", "text", p.name)
        prefill("edKind", "enum", st.kind)
        if (st.kind == 'time') prefill("edAt", "time", timeIso(st.at as String))
        if (st.kind in ['sunrise', 'sunset']) prefill("edOffset", "number", st.offset ?: 0)
        if (st.kind == 'var') prefill("edVar", "enum", st.name)
        prefillTargets(p.profile as String, p.custom as Map)
        return true
    }
    if (btn ==~ /emr~\d+/ || btn == 'btnRowAdd') {
        clearEdits(); state.remove('uiError')
        if (btn == 'btnRowAdd') { state.ui = [schedule: si, row: -1]; return true }
        int ri = btn.split('~')[1] as int
        Map r = ((cfg.schedules as List<Map>)[si].rows as List<Map>)[ri]
        if (!r) return true
        state.ui = [schedule: si, row: ri]
        prefill("edModes", "mode", (r.modes as List).collect { it.toString() })
        prefillTargets(r.profile as String, r.custom as Map)
        return true
    }
    if (btn == "btnGroupSave") {
        if (ui.groupName == null) return true
        Map c = cfgCopy(); Map g = ((c.schedules as List<Map>)[si].groups as List<Map>)[ui.groupName as int]
        String nn = (settings.edGroupName ?: '').toString().trim()
        if (!nn) { state.uiError = "A group needs a name"; return true }
        if (((c.schedules as List<Map>)[si].groups as List<Map>).any { it != g && it.name == nn }) { state.uiError = "Group name used twice: ${nn}".toString(); return true }
        g.name = nn
        if (saveConfig(c)) state.ui = [schedule: si]
        return true
    }
    if (btn == "btnPeriodSave") {
        if (ui.group == null || ui.period == null) return true
        Map c = cfgCopy(); Map g = ((c.schedules as List<Map>)[si].groups as List<Map>)[ui.group as int]
        String kind = settings.edKind ?: 'time'
        Map start = kind == 'time' ? [kind: 'time', at: hhmmSetting(settings.edAt)]
                  : (kind == 'var' ? [kind: 'var', name: settings.edVar] : [kind: kind, offset: (settings.edOffset ?: 0) as int])
        Map custom = customFromEdits()
        Map np = [name: (settings.edName ?: 'Period') as String, start: start, profile: custom ? null : settings.edProfile, custom: custom]
        List<Map> periods = g.periods as List<Map>
        if ((ui.period as int) < 0) periods << np else periods[ui.period as int] = np
        if (periods.count { it.name == np.name } > 1) { state.uiError = "Period name used twice: ${np.name}".toString(); return true }
        if (saveConfig(c)) state.ui = [schedule: si]
        return true
    }
    if (btn == "btnPeriodRemove") {
        if (ui.group == null || ui.period == null || (ui.period as int) < 0) return true
        Map c = cfgCopy(); (((c.schedules as List<Map>)[si].groups as List<Map>)[ui.group as int].periods as List).remove(ui.period as int)
        if (saveConfig(c)) state.ui = [schedule: si]
        return true
    }
    if (btn == "btnRowSave") {
        if (ui.row == null) return true
        Map c = cfgCopy(); Map s = (c.schedules as List<Map>)[si]
        Map custom = customFromEdits()
        Map nr = [modes: (settings.edModes ?: []).collect { it as Long }, profile: custom ? null : settings.edProfile, custom: custom]
        if (!nr.modes) { state.uiError = "Choose at least one mode"; return true }
        List<Map> rows = (s.rows ?: []) as List<Map>
        if ((ui.row as int) < 0) rows << nr else rows[ui.row as int] = nr
        s.rows = rows
        if (saveConfig(c)) state.ui = [schedule: si]
        return true
    }
    if (btn == "btnRowRemove") {
        if (ui.row == null || (ui.row as int) < 0) return true
        Map c = cfgCopy(); ((c.schedules as List<Map>)[si].rows as List).remove(ui.row as int)
        if (saveConfig(c)) state.ui = [schedule: si]
        return true
    }
    if (btn == "btnScheduleNew" || btn == "btnScheduleCopy") {
        Map c = cfgCopy(); List<Map> scheds = c.schedules as List<Map>
        Map ns = btn == 'btnScheduleNew' ? [name: uniqueName(scheds*.name as List<String>, "New schedule"), type: 'time',
                                             groups: [[name: 'Every day', days: [1, 2, 3, 4, 5, 6, 7], periods: []]]]
                                           : (parseJson(groovy.json.JsonOutput.toJson(scheds[si])) as Map) + [name: uniqueName(scheds*.name as List<String>, "${scheds[si].name} copy".toString())]
        scheds << ns
        if (saveConfig(c)) state.ui = [schedule: scheds.size() - 1]
        return true
    }
    if (btn == "btnScheduleSave") {
        Map c = cfgCopy(); Map s = (c.schedules as List<Map>)[si]
        String oldName = s.name
        String nn = (settings.edSchedName ?: oldName).toString().trim()
        String nt = (settings.edSchedType ?: s.type) as String
        if (nt != s.type) {
            if (nt == 'mode') { s.remove('groups'); s.rows = [] }
            else { s.remove('rows'); s.groups = [[name: 'Every day', days: [1, 2, 3, 4, 5, 6, 7], periods: []]] }
            s.type = nt
        }
        s.name = nn
        if (c.active == oldName) c.active = nn
        if (saveConfig(c)) state.ui = [schedule: si]
        return true
    }
    if (btn == "btnScheduleActivate") {
        apiCommand([command: 'setSchedule', schedule: (cfg.schedules as List<Map>)[si].name])
        return true
    }
    if (btn == "btnScheduleDelete") {
        String n = (cfg.schedules as List<Map>)[si].name
        if (n == cfg.active) { state.uiError = "${n} is the active schedule".toString(); logWarn state.uiError; return true }
        Map c = cfgCopy(); (c.schedules as List).remove(si)
        if (saveConfig(c)) state.ui = [:]
        return true
}
    return false
}

// Moves day d into group gTok, or into a new group copying its current group's periods.
boolean moveDay(Map s, String gTok, int d) {
    List<Map> groups = s.groups as List<Map>
    Map from = groups.find { Map g -> (g.days as List).collect { it as int }.contains(d) }
    Map to = gTok == 'new' ? null : groups[gTok as int]
    if (to != null && to.is(from)) to = null
    if (from == null && to == null) return false
    if (to == null) {
        if (from && (from.days as List).size() <= 1) return false
        if (from) from.days = (from.days as List).findAll { (it as int) != d }
        Map ng = [name: uniqueName(groups*.name as List<String>, DAY_NAME[d - 1]), days: [d],
                  periods: from ? parseJson(groovy.json.JsonOutput.toJson(from.periods)) : []]
        groups.add(from ? groups.indexOf(from) + 1 : groups.size(), ng)
    } else {
        if (from) from.days = (from.days as List).findAll { (it as int) != d }
        to.days = ((to.days as List).collect { it as int } + [d]).sort()
        if (from && !(from.days as List)) groups.remove(from)
    }
    return true
}

String timeIso(String hhmm) {
    TimeZone tz = location.timeZone
    java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ")
    f.setTimeZone(tz)
    return f.format(new Date(atLocal(isoDate(now(), tz), hhmm, tz)))
}

boolean overrideButton(String btn, Map ui) {
    Map cfg = uiCfg()
    if (btn ==~ /eov~\d+/ || btn == 'btnOverrideAdd') {
        clearEdits(); state.remove('uiError')
        if (btn == 'btnOverrideAdd') { state.ui = [override: -1]; return true }
        int i = btn.split('~')[1] as int
        Map o = (cfg.overrides as List<Map>)[i]
        if (!o) return true
        state.ui = [override: i]
        prefill("edOvMode", "mode", o.modeId?.toString())
        prefill("edProfile", "enum", o.profile)
        return true
    }
    if (btn == "btnOverrideSave") {
        if (ui.override == null) return true
        if (!settings.edOvMode || !settings.edProfile) { state.uiError = "Choose a mode and a profile"; return true }
        Map c = cfgCopy(); List<Map> ovs = c.overrides as List<Map>
        Map no = [modeId: settings.edOvMode as Long, profile: settings.edProfile]
        int i = ui.override as int
        if (ovs.findIndexOf { (it.modeId as Long) == (no.modeId as Long) } >= 0 && ovs.findIndexOf { (it.modeId as Long) == (no.modeId as Long) } != i) {
            state.uiError = "${modeName(no.modeId)} already has an override".toString(); return true
        }
        if (i < 0) ovs << no else ovs[i] = no
        if (saveConfig(c)) state.ui = [:]
        return true
    }
    if (btn == "btnOverrideRemove") {
        if (ui.override == null || (ui.override as int) < 0) return true
        Map c = cfgCopy(); (c.overrides as List).remove(ui.override as int)
        if (saveConfig(c)) state.ui = [:]
        return true
}
    return false
}

void controlButton(String btn) {
    Map rt = state.rt as Map
    if (btn == "btnAdvance") apiCommand([command: 'advance'])
    else if (btn == "btnEco") apiCommand([command: 'setEco', state: rt.eco ? 'off' : 'on'])
    else if (btn == "btnPause") apiCommand([command: rt.paused ? 'on' : 'off'])
    else if (btn == "btnResume") apiCommand([command: 'resume'])
    else if (btn == "btnHold") {
        String end = settings.holdUntil ?: 'next'
        if (end == 'minutes') end = (settings.holdMinutes ?: '') as String
        if (end == 'at') end = holdAtIso()
        Map req = settings.holdSel == 'custom' ? [command: 'holdSetpoints', heating: settings.holdHeat, cooling: settings.holdCool, end: end]
                                               : [command: 'holdProfile', profile: settings.holdSel, end: end]
        apiCommand(req)
    }
}

String holdAtIso() {
    if (!settings.holdDate || !settings.holdTime) return ''
    TimeZone tz = location.timeZone
    String d = settings.holdDate.toString().take(10)
    java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX")
    f.setTimeZone(tz)
    return f.format(new Date(atLocal(d, hhmmSetting(settings.holdTime), tz)))
}

void installed() { initialize() }
void updated() { unsubscribe(); unschedule(); initialize() }
void uninstalled() { getChildDevices().each { deleteChildDevice(it.deviceNetworkId) } }

void initialize() {
    checkVersion(false)
    if (!state.config) state.config = defaultConfig()
    if (state.rt == null) state.rt = [paused: false, eco: false, hold: null, pausedApplied: false, recordedModes: [:],
                                      sent: [:], manual: [], lastTarget: null, lastApply: 'ok', verify: null]
    if (!settings.testClock) app.removeSetting("testClock")
    statusDevice()
    if ((state.rt as Map).verify) runIn(30, "verifyWrites")
    subscribe(thermostats, "heatingSetpoint", "thermostatEvent")
    subscribe(thermostats, "coolingSetpoint", "thermostatEvent")
    subscribe(thermostats, "thermostatMode", "thermostatEvent")
    subscribe(location, "mode", "modeHandler")
    subscribe(location, "systemStart", "startHandler")
    if (pauseSwitch) subscribe(pauseSwitch, "switch", "pauseSwitchHandler")
    if (debugEnable || traceEnable) runIn(1800, "logsOff")
    evaluate("initialize", false)
}

void logsOff() {
    app.updateSetting("debugEnable", false); app.updateSetting("traceEnable", false)
    app.removeSetting("testClock")
    logWarn "debug logging and the test clock turned off"
}

// ── Runtime ───────────────────────────────────────────────────────────

long nowMillis() {
    if (debugEnable && settings.testClock) {
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm")
        f.setTimeZone(location.timeZone)
        return f.parse(settings.testClock as String).getTime()
    }
    return now()
}

def statusDevice() {
    String dni = "tsp-${app.id}"
    def d = getChildDevice(dni)
    if (!d) {
        d = addChildDevice("iamtrep", "Thermostat Scheduler+ Status", dni, [name: "${app.label} scheduler", label: "${app.label} scheduler", isComponent: true])
        logCfg "created ${d.displayName}"
    } else if (d.label != "${app.label} scheduler") d.setLabel("${app.label} scheduler")
    return d
}

Map coreConfig() {
    Map c = (state.config as Map) + [
        eco: [offset: settings.ecoOffset != null ? settings.ecoOffset : 2.0, onOverrides: settings.ecoOnOverrides != false],
        options: [separation: settings.separation != null ? settings.separation : 2.0, verify: settings.verifyWrites != false, applyOnStart: settings.applyOnStart != false,
                  whilePaused: settings.whilePaused ?: 'leave', onResume: settings.onResume ?: 'restore'],
        restrictions: [days: (settings.restrictDays ?: []).collect { it as int }, modeIds: (settings.restrictModes ?: []).collect { it as Long },
                       from: restrictTime('restrictFrom'), to: restrictTime('restrictTo')]]
    return c
}

List<String> varNames(Map cfg) {
    Set<String> n = [] as Set
    (cfg.profiles as List<Map>)?.each { Map p -> if (p.heatVar) n << (p.heatVar as String); if (p.coolVar) n << (p.coolVar as String) }
    (cfg.schedules as List<Map>)?.each { Map s -> (s.groups as List<Map>)?.each { Map g -> (g.periods as List<Map>)?.each { Map p -> if (p.start?.kind == 'var') n << (p.start.name as String) } } }
    return n as List
}

Map buildCtx(Map cfg) {
    long t = nowMillis()
    TimeZone tz = location.timeZone
    String today = isoDate(t, tz)
    Map sun = [:]
    [-1, 0, 1].each { int d ->
        String iso = addDays(today, d, tz)
        Map s = getSunriseAndSunset(date: new Date(atLocal(iso, "12:00", tz)))
        sun[iso] = [rise: (s.sunrise as Date)?.getTime(), set: (s.sunset as Date)?.getTime()]
    }
    Map vars = [:]
    varNames(cfg).each { String n ->
        Object v = getGlobalVar(n)?.value
        if (v instanceof Date) v = ((Date) v).getTime()
        else if (v instanceof String && ((String) v).contains("T")) v = toDateTime(v as String).getTime()
        vars[n] = v
    }
    Map ctx = [now: t, tz: tz, modeId: location.currentMode?.id as Long, sun: sun, vars: vars]
    ctx.restricted = restrictedNow(cfg.restrictions as Map, ctx) || switchRestricted()
    return ctx
}

boolean switchRestricted() {
    return pauseSwitch && pauseSwitch.currentValue("switch") == (settings.pauseWhenSwitch ?: "off")
}

List<Map> thermostatStates(Map modeOverride = [:]) {
    return (thermostats ?: []).collect { d ->
        String id = d.id as String
        [id: id, mode: modeOverride[id] ?: d.currentValue("thermostatMode"), heat: d.currentValue("heatingSetpoint"),
         cool: d.currentValue("coolingSetpoint"), fan: d.currentValue("thermostatFanMode")]
    }
}

boolean sameTarget(Map a, Map b) {
    if (a == null || b == null) return false
    return ['layer', 'profile', 'heat', 'cool', 'fan', 'mode', 'eco', 'transitionKey', 'overrideModeId'].every { String k -> a[k]?.toString() == b[k]?.toString() }
}

void evaluate(String why, boolean force, String onlyId = null) {
    Map cfg = coreConfig()
    Map rt = state.rt as Map
    Map ctx = buildCtx(cfg)
    Map target = resolveTarget(cfg, rt, ctx)
    if (holdExpired(rt.hold as Map, target, ctx.now as long)) {
        logCfg "hold ended"
        rt.hold = null
        target = resolveTarget(cfg, rt, ctx)
    }
    Map modeOverride = [:]
    List<String> noModeIds = []
    if (target.layer == 'paused' && !rt.pausedApplied) {
        rt.pausedApplied = true
        if ((cfg.options as Map).whilePaused == 'off') {
            rt.recordedModes = [:]
            thermostats.each { d ->
                String m = d.currentValue("thermostatMode") as String
                if (m != 'off') { rt.recordedModes[d.id as String] = m; d.setThermostatMode("off"); remember(rt, d.id as String, 'thermostatMode', 'off') }
            }
            logCmd "paused: thermostats off"
        } else logCfg "paused"
    } else if (target.layer != 'paused' && rt.pausedApplied) {
        rt.pausedApplied = false
        if ((cfg.options as Map).onResume == 'restore') {
            (rt.recordedModes as Map).each { String id, String m ->
                def d = thermostats.find { (it.id as String) == id }
                if (d) { d.setThermostatMode(m); remember(rt, id, 'thermostatMode', m); modeOverride[id] = m }
            }
            logCmd "resumed: modes restored"
        } else {
            noModeIds = thermostats.findAll { it.currentValue("thermostatMode") == 'off' }.collect { it.id as String }
            logCfg "resumed: thermostats left off"
        }
        rt.recordedModes = [:]
        force = true
    }
    boolean changed = !sameTarget(target, rt.lastTarget as Map)
    if (changed) rt.manual = []
    if (target.layer != 'paused' && (changed || force || onlyId)) {
        List<Map> states = thermostatStates(modeOverride).findAll { changed || force || onlyId == null || it.id == onlyId }
        if (onlyId && !changed && !force) noModeIds << onlyId
        List<Map> writes = planWrites(target, states, [separation: (cfg.options as Map).separation, force: force, noModeIds: noModeIds])
        rt.manual = (rt.manual as List) - writes*.id.unique()
        sendWrites(writes, rt, cfg)
    }
    rt.lastTarget = target
    state.rt = rt
    publish(cfg, rt, target, ctx)
    logSched "evaluate (${why}): ${target.layer} ${target.profile ?: ''}"
    if (!(debugEnable && settings.testClock)) runOnce(new Date(nextWake(cfg, rt, ctx)), "wakeHandler")
}

void remember(Map rt, String id, String attr, Object value) {
    Map byDev = ((rt.sent as Map)[id] ?: [:]) as Map
    byDev[attr] = [value: value?.toString(), at: now()]
    (rt.sent as Map)[id] = byDev
}

@Field static final Map ATTR_FOR = [setHeatingSetpoint: 'heatingSetpoint', setCoolingSetpoint: 'coolingSetpoint',
                                     setThermostatMode: 'thermostatMode', setThermostatFanMode: 'thermostatFanMode']

void sendWrites(List<Map> writes, Map rt, Map cfg) {
    if (!writes) return
    writes.each { Map w ->
        def d = thermostats.find { (it.id as String) == w.id }
        if (!d) return
        d."${w.command}"(w.value)
        remember(rt, w.id as String, ATTR_FOR[w.command] as String, w.value)
        logCmd "${d.displayName}: ${w.command} ${w.value}"
    }
    if ((cfg.options as Map).verify) {
        rt.verify = [writes: writes]
        runIn(30, "verifyWrites")
    } else rt.lastApply = 'ok'
}

void verifyWrites() {
    checkVersion()
    Map rt = state.rt as Map
    Map v = rt.verify as Map
    if (!v) return
    List<Map> batch = v.writes as List<Map>
    List<Map> missing = batch.findAll { Map w ->
        if ((rt.manual as List)?.contains(w.id)) return false
        def d = thermostats.find { (it.id as String) == w.id }
        Object cur = d?.currentValue(ATTR_FOR[w.command] as String)
        w.value instanceof Number || numOrNull(w.value) != null ? differs(numOrNull(w.value), cur) : cur?.toString() != w.value?.toString()
    }
    List<String> missingIds = missing.collect { it.id as String }
    List<String> confirmed = batch.collect { it.id as String }.unique().findAll { !missingIds.contains(it) }
    rt.lastApply = applyOutcome(batch, confirmed)
    rt.checkedAt = now()
    rt.verify = null
    if (missing) logWarn "${missing.size()} write(s) not confirmed after 30 s: " + missing.collect { Map w ->
        "${thermostats.find { (it.id as String) == w.id }?.displayName ?: w.id} ${w.command} ${w.value}" }.join(', ')
    state.rt = rt
    statusDevice().updateStatus([lastApply: rt.lastApply])
}

Map statusMap(Map cfg, Map rt, Map target, Map ctx) {
    TimeZone tz = ctx.tz as TimeZone
    Map sched = (cfg.schedules as List<Map>)?.find { it.name == cfg.active }
    Map next = sched?.type == 'time' ? nextTransitionOf(sched, ctx) : null
    java.text.SimpleDateFormat iso = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX")
    iso.setTimeZone(tz)
    String status = target.layer == 'paused' ? (rt.paused ? 'paused' : 'restricted')
                  : (rt.manual ? 'manual' : (target.layer == 'none' ? 'schedule' : target.layer))
    Map hold = rt.hold as Map
    String holdEnd = !hold ? 'none' : (hold.end == 'at' ? iso.format(new Date(hold.until as long)) : hold.end as String)
    return [
        switch: rt.paused ? 'off' : 'on', status: status, schedule: cfg.active, profile: target.profile ?: 'none',
        heatingTarget: target.heat, coolingTarget: target.cool, holdEnd: holdEnd,
        nextTransition: next ? iso.format(new Date(next.at as long)) : 'none', nextProfile: next?.profile ?: 'none',
        eco: rt.eco ? 'on' : 'off', ecoOffset: (cfg.eco as Map).offset, lastApply: rt.lastApply]
}

void publish(Map cfg, Map rt, Map target, Map ctx) {
    statusDevice().updateStatus(statusMap(cfg, rt, target, ctx))
}

// ── Handlers ──────────────────────────────────────────────────────────

void wakeHandler() { checkVersion(); evaluate("wake", false) }
void modeHandler(evt) { checkVersion(); logEvt "mode ${evt.value}"; evaluate("mode", false) }
void pauseSwitchHandler(evt) { checkVersion(); logEvt "pause switch ${evt.value}"; evaluate("pause switch", false) }
void startHandler(evt = null) {
    checkVersion()
    Map rt = state.rt as Map
    rt.verify = null
    state.rt = rt
    evaluate("hub start", coreConfig().options.applyOnStart as boolean)
}

void thermostatEvent(evt) {
    checkVersion()
    Map rt = state.rt as Map
    String id = evt.deviceId as String
    Map sent = (((rt.sent as Map)[id] ?: [:]) as Map)[evt.name] as Map
    boolean own = sent && (now() - (sent.at as long) < 120000L) &&
                  (numOrNull(evt.value) != null ? !differs(numOrNull(sent.value), evt.value) : sent.value == evt.value)
    if (own) return
    logEvt "${evt.displayName} ${evt.name} ${evt.value} (not sent by this program)"
    if (evt.name == 'thermostatMode') { evaluate("mode change on ${evt.displayName}", false, id); return }
    if (!(rt.manual as List).contains(id)) (rt.manual as List) << id
    state.rt = rt
    publish(coreConfig(), rt, (rt.lastTarget ?: [layer: 'none']) as Map, buildCtx(coreConfig()))
}

void appButtonHandler(String btn) {
    checkVersion()
    if (!state.config) state.config = defaultConfig()
    if (btn == "btnEvaluate") evaluate("button", false)
    else if (btn == "btnApply") evaluate("apply now", true)
    else if (btn == "btnStart") startHandler()
    else if (btn == "btnLoadConfig") loadConfigJson()
    else if (btn in ["btnAdvance", "btnEco", "btnPause", "btnResume", "btnHold"]) controlButton(btn)
    else uiButton(btn)
}

void loadConfigJson() {
    Map doc
    try { doc = parseJson(settings.testConfigJson as String) as Map } catch (Exception e) { logWarn "configuration is not JSON"; return }
    List<String> errs = validateConfig(doc, validationEnv())
    if (errs) { logWarn "configuration rejected: ${errs}"; return }
    state.config = doc
    state.ui = [:]
    logCfg "configuration loaded"
    evaluate("config loaded", true)
}

Map validationEnv() {
    return [scale: location.temperatureScale, vars: getAllGlobalVars()?.keySet()?.collect { it as String } ?: [],
            modeIds: location.modes.collect { it.id as Long }]
}

// ── Commands (device, API) ────────────────────────────────────────────

Map deviceCommand(Map req) { return apiCommand(req) }

Map apiCommand(Map req) {
    checkVersion()
    Map p = parseCommand(req)
    if (!p.ok) { logWarn "${req?.command}: ${p.error}"; return p }
    Map cfg = coreConfig()
    Map rt = state.rt as Map
    Map a = p.args as Map
    long t = nowMillis()
    boolean force = false
    switch (p.name) {
        case 'on': rt.paused = false; break
        case 'off': rt.paused = true; break
        case 'resume': rt.hold = null; force = true; break
        case 'applyNow': force = true; break
        case 'refresh': break
        case 'setEco': rt.eco = a.state == 'on'; break
        case 'setEcoOffset': app.updateSetting("ecoOffset", [type: "decimal", value: a.offset]); cfg = coreConfig(); break
        case 'setSchedule':
            if (!(cfg.schedules as List<Map>).find { it.name == a.schedule }) { logWarn "unknown schedule ${a.schedule}"; return [ok: false, error: "unknown schedule ${a.schedule}".toString()] }
            Map c = state.config as Map; c.active = a.schedule; state.config = c; cfg = coreConfig(); break
        case 'holdProfile':
        case 'holdSetpoints':
        case 'advance':
            Map ctx = buildCtx(cfg)
            Map cur = resolveTarget(cfg, rt + [hold: null], ctx)
            Map hold
            if (p.name == 'advance') {
                Map sched = (cfg.schedules as List<Map>).find { it.name == cfg.active }
                Map nx = sched?.type == 'time' ? nextTransitionOf(sched, ctx) : null
                if (!nx) return [ok: false, error: 'no next transition']
                hold = nx.custom ? [kind: 'setpoints', heat: (nx.custom as Map).heat, cool: (nx.custom as Map).cool] : [kind: 'profile', profile: nx.profile]
                a = [end: 'next']
            } else if (p.name == 'holdProfile') {
                if (!(cfg.profiles as List<Map>).find { it.name == a.profile }) { logWarn "unknown profile ${a.profile}"; return [ok: false, error: "unknown profile ${a.profile}".toString()] }
                hold = [kind: 'profile', profile: a.profile]
            } else hold = [kind: 'setpoints', heat: a.heat, cool: a.cool]
            hold.end = a.end == 'minutes' ? 'at' : a.end
            if (a.end == 'minutes') hold.until = t + (a.minutes as long) * 60000L
            if (a.end == 'at') hold.until = a.until
            hold.transitionKey = cur.transitionKey
            hold.overrideModeId = cur.overrideModeId
            rt.hold = hold
            break
    }
    state.rt = rt
    logCmd "${p.name} ${a ?: ''}"
    evaluate(p.name as String, force)
    return [ok: true] + apiStatus()
}

Map apiStatus() {
    Map rt = state.rt as Map
    Map cfg = coreConfig()
    return [id: app.id, name: app.label] + statusMap(cfg, rt, (rt.lastTarget ?: [layer: 'none']) as Map, buildCtx(cfg))
}

Map apiDocument() {
    Map cfg = coreConfig()
    return [id: app.id, name: app.label, revision: (state.config as Map).hashCode(),
            thermostats: (thermostats ?: []).collect { [id: it.id, name: it.displayName] },
            config: cfg, status: apiStatus()]
}

void checkVersion(boolean reinit = true) {
    if (state.version == CODE_VERSION) return
    logVer "version ${CODE_VERSION} (was ${state.version})"
    state.version = CODE_VERSION
    if (reinit) runIn(1, "updated")
}

// ── Core (pure) ───────────────────────────────────────────────────────
// Self-contained: arguments in, values out. No settings, state, devices or logs.
// tests/test_core.groovy parses and runs this block off-hub.

Calendar calFor(String iso, TimeZone tz) {
    Calendar c = Calendar.getInstance(tz)
    c.clear()
    String[] p = iso.split('-')
    c.set(p[0] as int, (p[1] as int) - 1, p[2] as int, 0, 0, 0)
    return c
}

String isoDate(long t, TimeZone tz) {
    java.text.SimpleDateFormat f = new java.text.SimpleDateFormat('yyyy-MM-dd')
    f.setTimeZone(tz)
    return f.format(new Date(t))
}

String hhmmOf(long t, TimeZone tz) {
    java.text.SimpleDateFormat f = new java.text.SimpleDateFormat('HH:mm')
    f.setTimeZone(tz)
    return f.format(new Date(t))
}

String addDays(String iso, int n, TimeZone tz) {
    Calendar c = calFor(iso, tz)
    c.add(Calendar.DAY_OF_MONTH, n)
    return isoDate(c.getTimeInMillis(), tz)
}

int isoDow(String iso, TimeZone tz) {
    int d = calFor(iso, tz).get(Calendar.DAY_OF_WEEK)
    return d == Calendar.SUNDAY ? 7 : d - 1
}

long atLocal(String iso, String hhmm, TimeZone tz) {
    Calendar c = calFor(iso, tz)
    String[] p = hhmm.split(':')
    c.set(Calendar.HOUR_OF_DAY, p[0] as int)
    c.set(Calendar.MINUTE, p[1] as int)
    return c.getTimeInMillis()
}

BigDecimal numOrNull(Object o) {
    if (o instanceof Number) return new BigDecimal(o.toString())
    if (o instanceof String && ((String) o).trim().isBigDecimal()) return new BigDecimal(((String) o).trim())
    return null
}

Long startOf(Map start, String iso, Map ctx) {
    TimeZone tz = ctx.tz as TimeZone
    String kind = start?.kind as String
    if (kind == 'time') return start.at ? atLocal(iso, start.at as String, tz) : null
    if (kind == 'sunrise' || kind == 'sunset') {
        Map day = (ctx.sun as Map)?.get(iso) as Map
        Long base = day ? (day.get(kind == 'sunrise' ? 'rise' : 'set') as Long) : null
        return base == null ? null : base + ((start.offset ?: 0) as long) * 60000L
    }
    if (kind == 'var') {
        Object v = (ctx.vars as Map)?.get(start.name)
        if (!(v instanceof Number)) return null
        return atLocal(iso, hhmmOf(((Number) v).longValue(), tz), tz)
    }
    return null
}

Map groupFor(Map sched, int dow) {
    return (sched.groups as List<Map>)?.find { Map g -> (g.days as List)?.collect { it as int }?.contains(dow) }
}

List<Map> transitionsForDay(Map sched, String iso, Map ctx) {
    Map g = groupFor(sched, isoDow(iso, ctx.tz as TimeZone))
    if (!g) return []
    List<Map> out = []
    for (Map p in (g.periods as List<Map>)) {
        Long at = startOf(p.start as Map, iso, ctx)
        if (at != null) {
            out << [at: at, key: "${iso}|${g.name}|${p.name}".toString(), group: g.name, period: p.name,
                    profile: p.profile, custom: p.custom]
        }
    }
    return out.sort { Map t -> t.at as Long }
}

Map currentTransition(Map sched, Map ctx) {
    long now = ctx.now as long
    TimeZone tz = ctx.tz as TimeZone
    String today = isoDate(now, tz)
    for (int back = 0; back <= 7; back++) {
        List<Map> past = transitionsForDay(sched, addDays(today, -back, tz), ctx).findAll { Map t -> (t.at as long) <= now }
        if (past) return past.last()
    }
    return null
}

Map nextTransitionOf(Map sched, Map ctx) {
    long now = ctx.now as long
    TimeZone tz = ctx.tz as TimeZone
    String today = isoDate(now, tz)
    for (int fwd = 0; fwd <= 7; fwd++) {
        List<Map> ahead = transitionsForDay(sched, addDays(today, fwd, tz), ctx).findAll { Map t -> (t.at as long) > now }
        if (ahead) return ahead.first()
    }
    return null
}

Map valuesOf(Map v, Map ctx) {
    Map vars = (ctx.vars ?: [:]) as Map
    BigDecimal heat = v.heatVar ? numOrNull(vars.get(v.heatVar)) : numOrNull(v.heat)
    BigDecimal cool = v.coolVar ? numOrNull(vars.get(v.coolVar)) : numOrNull(v.cool)
    return [heat: heat, cool: cool, fan: v.fan ?: null, mode: v.mode ?: null]
}

Map profileValues(Map cfg, String name, Map ctx) {
    Map p = (cfg.profiles as List<Map>)?.find { Map x -> x.name == name }
    return p ? valuesOf(p, ctx) + [profile: name] : null
}

Map resolveTarget(Map cfg, Map rt, Map ctx) {
    Long modeId = ctx.modeId as Long
    Map sched = (cfg.schedules as List<Map>)?.find { Map s -> s.name == cfg.active }
    Map cur = sched?.type == 'time' ? currentTransition(sched, ctx) : null
    String key = sched?.type == 'mode' ? "mode|${modeId}".toString() : (cur?.key as String)
    Map ov = (cfg.overrides as List<Map>)?.find { Map o -> (o.modeId as Long) == modeId }
    if (rt.paused == true || ctx.restricted == true) return [layer: 'paused', transitionKey: key, overrideModeId: ov?.modeId as Long]
    Map hold = rt.hold as Map
    Map base = null
    String layer = 'none'
    if (hold) {
        base = hold.kind == 'profile' ? profileValues(cfg, hold.profile as String, ctx)
             : [heat: numOrNull(hold.heat), cool: numOrNull(hold.cool), fan: null, mode: null, profile: 'custom']
        layer = 'hold'
    } else if (ov) {
        base = profileValues(cfg, ov.profile as String, ctx)
        layer = 'mode'
    } else if (sched?.type == 'mode') {
        Map row = (sched.rows as List<Map>)?.find { Map rw -> (rw.modes as List)?.collect { it as Long }?.contains(modeId) }
        if (row) {
            base = row.custom ? valuesOf(row.custom as Map, ctx) + [profile: 'custom'] : profileValues(cfg, row.profile as String, ctx)
            layer = 'schedule'
        }
    } else if (cur) {
        base = cur.custom ? valuesOf(cur.custom as Map, ctx) + [profile: 'custom'] : profileValues(cfg, cur.profile as String, ctx)
        layer = 'schedule'
    }
    if (base == null) return [layer: 'none', transitionKey: key, overrideModeId: ov?.modeId as Long]
    Map eco = (cfg.eco ?: [:]) as Map
    boolean ecoOn = rt.eco == true && (layer != 'mode' || eco.onOverrides != false)
    BigDecimal off = numOrNull(eco.offset) ?: 0.0G
    BigDecimal heat = base.heat as BigDecimal
    BigDecimal cool = base.cool as BigDecimal
    if (ecoOn) {
        if (heat != null) heat = heat - off
        if (cool != null) cool = cool + off
    }
    return [layer: layer, profile: base.profile, heat: heat, cool: cool, fan: base.fan, mode: base.mode,
            eco: ecoOn, transitionKey: key, overrideModeId: ov?.modeId as Long]
}

boolean holdExpired(Map hold, Map target, long now) {
    if (!hold || hold.end == 'indefinite') return false
    if (hold.end == 'at') return now >= (hold.until as long)
    return target.transitionKey != hold.transitionKey || (target.overrideModeId as Long) != (hold.overrideModeId as Long)
}

boolean differs(BigDecimal want, Object cur) {
    BigDecimal c = numOrNull(cur)
    return c == null || (want - c).abs() >= 0.05G
}

List<Map> planWrites(Map target, List<Map> therms, Map opts) {
    List<Map> out = []
    if (target == null || target.layer == 'paused' || target.layer == 'none') return out
    boolean force = opts?.force == true
    BigDecimal sep = numOrNull(opts?.separation) ?: 0.0G
    List noMode = ((opts?.noModeIds ?: []) as List).collect { it.toString() }
    for (Map t in therms) {
        String want = noMode.contains(t.id?.toString()) ? null : target.mode as String
        String mode = (want ?: t.mode) as String
        if (want && (force || want != t.mode)) out << [id: t.id, command: 'setThermostatMode', value: want]
        if (target.fan && (force || target.fan != t.fan)) out << [id: t.id, command: 'setThermostatFanMode', value: target.fan]
        if (mode == null || mode == 'off') continue
        BigDecimal heat = target.heat as BigDecimal
        BigDecimal cool = target.cool as BigDecimal
        if (mode == 'auto') {
            BigDecimal curHeat = numOrNull(t.heat), curCool = numOrNull(t.cool)
            if (heat != null && cool != null) { if (cool - heat < sep) cool = heat + sep }
            else if (heat != null && curCool != null && curCool - heat < sep) heat = curCool - sep
            else if (cool != null && curHeat != null && cool - curHeat < sep) cool = curHeat + sep
        }
        boolean wantHeat = mode in ['heat', 'emergency heat', 'auto']
        boolean wantCool = mode in ['cool', 'auto']
        if (wantHeat && heat != null && (force || differs(heat, t.heat))) out << [id: t.id, command: 'setHeatingSetpoint', value: heat]
        if (wantCool && cool != null && (force || differs(cool, t.cool))) out << [id: t.id, command: 'setCoolingSetpoint', value: cool]
    }
    return out
}

boolean restrictedNow(Map r, Map ctx) {
    if (!r) return false
    TimeZone tz = ctx.tz as TimeZone
    long now = ctx.now as long
    String today = isoDate(now, tz)
    List days = r.days as List
    if (days && !days.collect { it as int }.contains(isoDow(today, tz))) return true
    List modes = r.modeIds as List
    if (modes && !modes.collect { it as Long }.contains(ctx.modeId as Long)) return true
    if (r.from && r.to) {
        Long a = startOf(r.from as Map, today, ctx), b = startOf(r.to as Map, today, ctx)
        if (a != null && b != null) {
            boolean inside = a <= b ? (now >= a && now < b) : (now >= a || now < b)
            if (!inside) return true
        }
    }
    return false
}

long nextWake(Map cfg, Map rt, Map ctx) {
    long now = ctx.now as long
    TimeZone tz = ctx.tz as TimeZone
    List<Long> c = [atLocal(addDays(isoDate(now, tz), 1, tz), '00:00', tz)]
    Map sched = (cfg.schedules as List<Map>)?.find { Map s -> s.name == cfg.active }
    if (sched?.type == 'time') {
        Long n = nextTransitionOf(sched, ctx)?.at as Long
        if (n != null) c << n
    }
    Map hold = rt.hold as Map
    if (hold?.end == 'at') c << (hold.until as Long)
    Map r = cfg.restrictions as Map
    if (r?.from && r?.to) {
        String today = isoDate(now, tz)
        [r.from, r.to].each { Object s ->
            Long t = startOf(s as Map, today, ctx)
            if (t != null && t > now) c << t
        }
    }
    return Math.max(c.min() as long, now + 1000L)
}

Map defaultConfig() {
    return [v: 1,
        profiles: [[name: 'Home', heat: 21.0, cool: 24.0], [name: 'Sleep', heat: 18.0, cool: 26.0], [name: 'Away', heat: 16.0, cool: 29.0]],
        schedules: [[name: 'Normal', type: 'time', groups: [[name: 'Every day', days: [1, 2, 3, 4, 5, 6, 7], periods: [
            [name: 'Wake', start: [kind: 'time', at: '06:30'], profile: 'Home', custom: null],
            [name: 'Night', start: [kind: 'time', at: '22:00'], profile: 'Sleep', custom: null]]]]]],
        active: 'Normal', overrides: []]
}

List<String> validateConfig(Map doc, Map env) {
    List<String> e = []
    BigDecimal lo = env.scale == 'F' ? 32.0G : 0.0G, hi = env.scale == 'F' ? 104.0G : 40.0G
    List<String> names = (doc.profiles as List<Map>)?.collect { it.name as String } ?: []
    names.findAll { String n -> !n?.trim() }.each { e << 'A profile has no name' }
    names.countBy { it }.findAll { k, v -> v > 1 }.each { k, v -> e << "Profile name used twice: ${k}".toString() }
    Closure checkValues = { Map v, String where ->
        ['heat', 'cool'].each { String f ->
            BigDecimal n = numOrNull(v?.get(f))
            if (n != null && (n < lo || n > hi)) e << "${where}: ${f} ${n} is out of range".toString()
            String var = v?.get(f + 'Var') as String
            if (var && !(env.vars as List).contains(var)) e << "${where}: unknown variable ${var}".toString()
        }
    }
    (doc.profiles as List<Map>)?.each { Map p -> checkValues(p, "Profile ${p.name}".toString()) }
    Closure profileRef = { String name, Map custom, String where ->
        if (custom) checkValues(custom, where)
        else if (!names.contains(name)) e << "${where}: unknown profile ${name}".toString()
    }
    List<String> scheds = (doc.schedules as List<Map>)?.collect { it.name as String } ?: []
    scheds.countBy { it }.findAll { k, v -> v > 1 }.each { k, v -> e << "Schedule name used twice: ${k}".toString() }
    if (!scheds.contains(doc.active)) e << "Active schedule ${doc.active} does not exist".toString()
    (doc.schedules as List<Map>)?.each { Map s ->
        if (s.type == 'time') {
            (1..7).each { int d ->
                int n = (s.groups as List<Map>).count { Map g -> (g.days as List)?.collect { it as int }?.contains(d) } as int
                if (n != 1) e << "Schedule ${s.name}: day ${d} is in ${n} groups".toString()
            }
            (s.groups as List<Map>).each { Map g ->
                List<String> fixed = (g.periods as List<Map>).findAll { it.start?.kind == 'time' }.collect { it.start.at as String }
                fixed.countBy { it }.findAll { k, v -> v > 1 }.each { k, v -> e << "Schedule ${s.name}, ${g.name}: two periods at ${k}".toString() }
                (g.periods as List<Map>).each { Map p ->
                    String where = "Schedule ${s.name}, ${g.name}, ${p.name}".toString()
                    String kind = p.start?.kind as String
                    if (!(kind in ['time', 'sunrise', 'sunset', 'var'])) e << "${where}: bad start".toString()
                    if (kind == 'time' && !((p.start.at as String) ==~ /([01]\d|2[0-3]):[0-5]\d/)) e << "${where}: bad time ${p.start.at}".toString()
                    if (kind == 'var' && !(env.vars as List).contains(p.start.name)) e << "${where}: unknown variable ${p.start.name}".toString()
                    profileRef(p.profile as String, p.custom as Map, where)
                }
            }
        } else if (s.type == 'mode') {
            List<Long> seen = []
            (s.rows as List<Map>).each { Map row ->
                (row.modes as List).collect { it as Long }.each { Long m ->
                    if (!(env.modeIds as List).collect { it as Long }.contains(m)) e << "Schedule ${s.name}: unknown mode ${m}".toString()
                    if (seen.contains(m)) e << "Schedule ${s.name}: mode ${m} in two rows".toString()
                    seen << m
                }
                profileRef(row.profile as String, row.custom as Map, "Schedule ${s.name}".toString())
            }
        } else e << "Schedule ${s.name}: unknown type ${s.type}".toString()
    }
    (doc.overrides as List<Map>)?.each { Map o ->
        if (!(env.modeIds as List).collect { it as Long }.contains(o.modeId as Long)) e << "Override: unknown mode ${o.modeId}".toString()
        profileRef(o.profile as String, null, 'Override')
    }
    return e
}

Map parseEnd(Object raw) {
    String s = raw == null ? '' : raw.toString().trim()
    if (s == '' || s == 'next') return [end: 'next']
    if (s == 'indefinite') return [end: 'indefinite']
    if (s ==~ /\d{1,6}/) return (s as int) > 0 ? [end: 'minutes', minutes: s as int] : [error: 'minutes must be positive']
    if (s ==~ /\d+/) return [error: 'minutes must be at most 999999']
    for (String fmt in ["yyyy-MM-dd'T'HH:mm:ssXXX", "yyyy-MM-dd'T'HH:mmXXX"]) {
        try {
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat(fmt)
            f.setLenient(false)
            return [end: 'at', until: f.parse(s).getTime()]
        } catch (Exception ignored) { }
    }
    return [error: "end must be next, indefinite, minutes or an ISO time: ${s}".toString()]
}

Map parseCommand(Map req) {
    String name = req?.command as String
    Closure err = { String m -> [ok: false, error: m] }
    if (name in ['on', 'off', 'resume', 'applyNow', 'advance', 'refresh']) return [ok: true, name: name, args: [:]]
    if (name == 'holdProfile') {
        if (!req.profile?.toString()?.trim()) return err('profile is required')
        Map end = parseEnd(req.end)
        return end.error ? err(end.error as String) : [ok: true, name: name, args: [profile: req.profile.toString().trim()] + end]
    }
    if (name == 'holdSetpoints') {
        BigDecimal h = numOrNull(req.heating), c = numOrNull(req.cooling)
        if (h == null && c == null) return err('heating or cooling is required')
        Map end = parseEnd(req.end)
        return end.error ? err(end.error as String) : [ok: true, name: name, args: [heat: h, cool: c] + end]
    }
    if (name == 'setSchedule') {
        String s = req.schedule?.toString()?.trim()
        return s ? [ok: true, name: name, args: [schedule: s]] : err('schedule is required')
    }
    if (name == 'setEco') {
        String s = req.state?.toString()?.trim()
        return s in ['on', 'off'] ? [ok: true, name: name, args: [state: s]] : err('state must be on or off')
    }
    if (name == 'setEcoOffset') {
        BigDecimal o = numOrNull(req.offset)
        return (o != null && o.abs() <= 10.0G) ? [ok: true, name: name, args: [offset: o]] : err('offset must be a number from -10 to 10')
    }
    return err("unknown command: ${name}".toString())
}

String applyOutcome(List<Map> batch, List<String> confirmedIds) {
    List<String> ids = batch.collect { it.id as String }.unique()
    int ok = ids.count { confirmedIds.contains(it) } as int
    return ok == ids.size() ? 'ok' : (ok == 0 ? 'failed' : 'partial')
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

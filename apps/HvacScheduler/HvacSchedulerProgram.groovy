// Copyright (c) 2026 PJ
// SPDX-License-Identifier: MIT

/*
 HVAC Scheduler Program

 One zone: schedules thermostats from named profiles, with holds, mode overrides,
 an eco offset and pause handling. Publishes its state on a component device.
 Spec: docs/thermostat-scheduler-replacement-spec.md.
*/

import com.hubitat.app.ChildDeviceWrapper
import com.hubitat.app.DeviceWrapper
import com.hubitat.hub.domain.State
import groovy.transform.CompileStatic
import groovy.transform.Field

@Field static final String CODE_VERSION = "0.4.0"

definition(
    name: "HVAC Scheduler Program",
    namespace: "iamtrep",
    author: "pj",
    description: "One HVAC Scheduler program",
    menu: "Automations", // new in platform 2.5.0
    category: "Convenience",
    parent: "iamtrep:HVAC Scheduler",
    singleThreaded: true,
    importUrl: "https://raw.githubusercontent.com/iamtrep/hubitat/main/apps/HvacScheduler/HvacSchedulerProgram.groovy",
    documentationLink: "https://github.com/iamtrep/hubitat/tree/main/apps/HvacScheduler",
    iconUrl: "", iconX2Url: ""
)

preferences {
    page(name: "mainPage")
    page(name: "profilesPage")
    page(name: "schedulesPage")
    page(name: "optionsPage")
    page(name: "advancedPage")
}

// ── UI ────────────────────────────────────────────────────────────────

@Field static final String PRI = "p-button bg-hubitat-primary-green text-white"
@Field static final String SEC = "p-button p-button-outlined"
@Field static final String DANGER = "p-button p-button-outlined p-button-danger"
@Field static final Map KIND_OPTS = [sunrise: "Sunrise", sunset: "Sunset", time: "A specific time", var: "Time from a hub variable"]
@Field static final List<String> DAY_ABBR = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"]
@Field static final List<String> DAY_NAME = ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"]

@Field static final String PILL_ON = 'min-height:36px;min-width:52px;justify-content:center;border-radius:18px;background:#2e8b2e;color:#fff;font-weight:500;font-size:14px'
@Field static final String PILL_OFF = 'min-height:36px;min-width:52px;justify-content:center;border-radius:18px;border:1px solid rgba(128,128,128,.55);color:inherit;opacity:.8;font-size:14px'
@Field static final String TSP_CSS = """<style>
.mdl-grid>.mdl-cell.app-ui-field,.mdl-grid>.mdl-cell.capability,.mdl-grid>.mdl-cell:has(>button.app-ui-navigation-control){margin-left:8px!important;margin-right:8px!important;padding-right:0!important}
.mdl-grid>.mdl-cell:has(>a.app-ui-navigation-control){margin-left:8px!important;margin-right:8px!important;width:calc(100% - 16px)!important}
.mdl-grid:has(>.mdl-cell:only-child>div>style:only-child){display:none}
.mdl-cell:has(>button.p-button){align-self:flex-end;margin-bottom:8px}
.app-ui-field .safariOnly>.mdl-cell:first-child{margin-left:0!important}
.tsp-heat{color:var(--hubitat-validation-color, #d32f2f);font-weight:500}.tsp-cool{color:var(--hubitat-link-color, #1a77c9);font-weight:500}.tsp-dim{opacity:.65}
.tsp-sel{background:rgba(61,127,31,.1)}
.tsp-now{font-size:11px;font-weight:700;letter-spacing:.05em;color:var(--hubitat-success-color, #356700);border:1px solid currentColor;border-radius:3px;padding:0 4px;margin-left:8px}
.tsp-note{font-size:14px;opacity:.75;margin:4px 0}
.tsp-small{font-size:12px}
.tsp-mono{font-variant-numeric:tabular-nums}
.tsp-big{font-size:24px;font-weight:500;margin:2px 0 6px}
.tsp-cap{font-size:12px;letter-spacing:.06em;text-transform:uppercase;opacity:.75;font-weight:500;margin-bottom:4px}
.tsp-nowcard>div{margin:2px 0}
.tsp-panel{border:1px solid rgba(128,128,128,.35);border-radius:6px;padding:14px 16px}
.tsp-tstat{margin:6px 0 10px}
.tsp-kv{display:grid;grid-template-columns:auto minmax(0,1fr);gap:4px 12px;font-size:14px;margin-top:4px}
.tsp-swatch{display:inline-block;width:10px;height:10px;border-radius:2px;margin-right:6px}
.tsp-rows{border-top:1px solid rgba(128,128,128,.35)}
.tsp-row{display:grid;gap:12px;align-items:center;min-height:46px;padding:0 12px;border-bottom:1px solid rgba(128,128,128,.25);color:inherit!important;cursor:pointer;font-size:14px}
.tsp-row:hover{background:rgba(128,128,128,.08)}
.tsp-rowh{min-height:32px;font-size:12px;opacity:.7;cursor:default}.tsp-rowh:hover{background:none}
.tsp-pills,.tsp-ctl{display:flex;flex-wrap:wrap;gap:6px;align-items:center}
.tsp-ctl{gap:4px 36px}
.tsp-pills>.form-group,.tsp-ctl>.form-group{display:none}
.tsp-swrow{display:flex;align-items:center;gap:10px;color:inherit!important;min-height:36px;cursor:pointer;font-size:15px}
.tsp-sw{position:relative;display:inline-block;flex:0 0 auto;width:38px;height:22px;border-radius:11px;background:rgba(128,128,128,.55)}
.tsp-sw>span{position:absolute;top:3px;left:3px;width:16px;height:16px;border-radius:50%;background:#fff}
.tsp-sw.on{background:#2e8b2e}.tsp-sw.on>span{left:19px}
.tsp-tlhead{display:flex;justify-content:space-between;flex-wrap:wrap;gap:6px 14px;margin-bottom:8px}
.tsp-legend{display:flex;flex-wrap:wrap;gap:4px 14px;font-size:12px;opacity:.85}
.tsp-tl{display:grid;grid-template-columns:36px minmax(0,1fr);gap:5px 8px;align-items:center}
.tsp-tlax{position:relative;height:14px;font-size:11px;opacity:.7}.tsp-tlax span{position:absolute;top:0}
.tsp-tlday{font-size:12px;opacity:.8}.tsp-today{font-weight:700;opacity:1;color:var(--hubitat-success-color, #356700)}
.tsp-tlrow{position:relative;height:24px;background:rgba(128,128,128,.15);border-radius:3px;overflow:hidden}
.tsp-tlb{position:absolute;top:0;bottom:0;box-sizing:border-box;border-right:1px solid rgba(0,0,0,.3);color:#fff;font-size:11.5px;font-weight:500;display:flex;align-items:center;padding-left:6px;overflow:hidden;white-space:nowrap}
.tsp-tlnow{position:absolute;top:-2px;bottom:-2px;width:2px;background:#4caf50;box-shadow:0 0 0 1px rgba(0,0,0,.45)}
@media (max-width:640px){.tsp-swrow{min-height:44px}.tsp-hide-sm{display:none}.tsp-prow{grid-template-columns:56px minmax(0,1fr) minmax(0,1.4fr) 12px!important}
.tsp-frow,.tsp-mrow{grid-template-columns:minmax(0,1fr) minmax(0,1.3fr) 12px!important}.tsp-tlb span{display:none}.tsp-tlrow{height:16px}}
</style>"""

// While an editor fills the page, the page's own Done button would leave without saving: hide it.
String pageCss(boolean editing) {
    return editing ? TSP_CSS.replace('</style>', "#fieldsetAppButtons button[value='Done']{display:none!important}</style>") : TSP_CSS
}

String esc(Object s) { s == null ? '' : s.toString().replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;').replace("'", '&#39;') }

// Same markup the firmware's Thermostat Scheduler 2.0.3 emits for grid buttons (2.5.2.129),
// without submitOnChange: the click reloads the page instead of re-posting the form, which
// would overwrite the values the handler just wrote. Edit inputs submit on change instead.
String cellButton(String name, String label, boolean raw = false, String style = '') {
    return "<div class='form-group'><input type='hidden' name='${name}.type' value='button'></div>" +
           "<div><div class='app-button-link' onclick='buttonClick(this); return false;' " +
           "style='cursor:pointer;color:var(--hubitat-link-color, #1a77c9);min-height:44px;display:flex;align-items:center;${style}'>${raw ? label : esc(label)}</div></div>" +
           "<input type='hidden' name='settings[${name}]' value=''>"
}

String unit() { "°${location.temperatureScale}" }

String fmtT(Object v) {
    BigDecimal n = numOrNull(v)
    return n == null ? '' : n.setScale(1, BigDecimal.ROUND_HALF_UP).toPlainString()
}

String heatSpan(Object v) { v == null ? '' : "<span class='tsp-heat'>${fmtT(v)}</span>" }
String coolSpan(Object v) { v == null ? '' : "<span class='tsp-cool'>${fmtT(v)}</span>" }

Map uiCfg() { return (state.config ?: newConfig()) as Map }

// The occupied profile's name, when a sensor and a profile are both set.
String occupiedProfile(Map cfg) { return occupancySensor ? ((cfg.occupancy as Map)?.profile as String) : null }

Map newConfig() { return seedOverrides(defaultConfig(), location.modes.collect { [id: it.id as Long, name: it.name as String] }) }

Map cfgCopy() { return parseJson(groovy.json.JsonOutput.toJson(uiCfg())) as Map }

Map uiState() { return (state.ui ?: [:]) as Map }

String modeName(Object id) { location.modes.find { (it.id as Long) == (id as Long) }?.name ?: "mode ${id}" }

Map modeOptions() { location.modes.collectEntries { [(it.id as String): it.name] } }

List<String> varNamesOfType(List<String> types) {
    Map all = getAllGlobalVars() ?: [:]
    return all.findAll { k, v -> types.contains(v?.type?.toString()?.toLowerCase()) }.keySet().collect { it as String }.sort()
}

void clearEdits() { settings.keySet().findAll { it.startsWith('ed') }.each { app.removeSetting(it) } }

// Edit errors belong to the page whose button raised them; they show there once and are
// gone on the next button press or render.
String errorPara(String page) {
    Object raw = state.uiError
    if (raw != null && !(raw instanceof Map)) { state.remove('uiError'); return '' }
    Map e = raw as Map
    if (!e || e.page != page) return ''
    if (e.shown) { state.remove('uiError'); return '' }
    e.shown = true
    state.uiError = e
    return "<div class='p-message p-message-error p-3 border-round'>${esc(e.text)}</div>"
}

void uiErr(String text) { state.uiError = [page: state.uiPage ?: 'mainPage', text: text, shown: false] }

String pageForButton(String btn) {
    if (btn ==~ /epf~\d+/ || btn.startsWith('btnProfile')) return 'profilesPage'
    if (btn ==~ /eov~\d+/ || btn.startsWith('btnOverride') || btn == 'btnOccSave') return 'optionsPage'
    if (btn in ['btnApply', 'btnAdvance', 'btnEco', 'btnPause', 'btnResume', 'btnHold', 'btnImportDismiss']) return 'mainPage'
    return 'schedulesPage'
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
    if ((c.occupancy as Map)?.profile == profile) out << "occupancy (the occupied profile)"
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
    Map occ = c.occupancy as Map
    if (occ?.profile == from) occ.profile = to
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
    if (errs) { uiErr(errs.join('; ')); logWarn "not saved: ${errs.join('; ')}"; return false }
    state.remove('uiError')
    state.config = doc
    if (renamed) renameHeldProfile(renamed.from as String, renamed.to as String)
    clearEdits()
    resubscribe()
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
            if (!installed || !thermostats) input "thermostats", "capability.thermostat", title: "Select Thermostats", multiple: true, required: true, submitOnChange: true
        }
        Map rep = state.importReport as Map
        if (rep) section("Imported") {
            StringBuilder h = new StringBuilder("<p>Imported from <b>${esc(rep.from)}</b>, which is left as it is. This program is paused: check it, press Done, disable the built-in scheduler, then turn this program on.</p>")
            if (rep.warnings) h << "<ul>" << (rep.warnings as List).collect { "<li>${esc(it)}</li>" }.join('') << "</ul>"
            paragraph h.toString()
            input "btnImportDismiss", "button", title: "Dismiss", width: 2, inputClass: SEC
        }
        if (installed && thermostats) {
            Map cfg = coreConfig()
            Map rt = state.rt as Map
            Map ctx = buildCtx(cfg)
            Map target = (rt.lastTarget ?: [layer: 'none']) as Map
            Map sm = statusMap(cfg, rt, target, ctx)
            Map sched = (cfg.schedules as List<Map>).find { it.name == cfg.active }
            Map nx = sched?.type == 'time' ? nextTransitionOf(sched, ctx) : null
            section {
                paragraph nowHtml(cfg, rt, target, ctx, sm), width: 8
                paragraph thermostatPanel(), width: 4
                if (sched?.type == 'time') paragraph timelineHtml(cfg, sched, ctx)
            }
            section("Control") {
                String err = errorPara('mainPage')
                if (err) paragraph err
                paragraph "<div class='tsp-ctl'>" +
                          switchButton("btnPause", "Running", !rt.paused, "off pauses the program") +
                          switchButton("btnEco", "Eco", rt.eco as boolean, "heat ${fmtT((cfg.eco as Map).offset)} ${unit()} lower, cool ${fmtT((cfg.eco as Map).offset)} ${unit()} higher") +
                          "</div>", width: 9
                input "btnApply", "button", title: "Apply now", width: 3, inputClass: SEC
                Map holdOpts = (cfg.profiles as List<Map>).collectEntries { Map p -> [(p.name): profileText(cfg, p.name as String)] } + [custom: "Custom setpoints"]
                input "holdSel", "enum", title: "Hold", options: holdOpts, width: 4, submitOnChange: true, required: false
                String nxText = nx ? "Next period (${nx.period} at ${hhmmOf(nx.at as long, ctx.tz as TimeZone)})" : "Next period"
                input "holdUntil", "enum", title: "Until", width: 4, submitOnChange: true, defaultValue: "next",
                      options: [next: nxText, minutes: "For a duration", at: "A date and time", indefinite: "Resumed by hand"]
                input "btnHold", "button", title: "Hold", width: 2, inputClass: PRI
                if (rt.hold) input "btnResume", "button", title: "Resume schedule", width: 2, inputClass: SEC
                else if (nx) input "btnAdvance", "button", title: "Skip to ${esc(nx.period)}", width: 2, inputClass: SEC
                if (holdSel == 'custom') {
                    input "holdHeat", "decimal", title: "Heating setpoint (${unit()})", width: 3, submitOnChange: true, required: false
                    input "holdCool", "decimal", title: "Cooling setpoint (${unit()})", width: 3, submitOnChange: true, required: false
                }
                if (holdUntil == 'minutes') input "holdMinutes", "number", title: "Minutes", width: 3, submitOnChange: true, required: false
                if (holdUntil == 'at') {
                    input "holdDate", "date", title: "Date", width: 3, submitOnChange: true, required: false
                    input "holdTime", "time", title: "Time", width: 3, submitOnChange: true, required: false
                }
            }
        }
        section("Setup") {
            Map cfg = uiCfg()
            href "schedulesPage", title: "Schedule", description: esc((cfg.schedules as List<Map>).collect { Map s -> s.name == cfg.active ? "${s.name} (active)${s.type == 'time' ? ': ' + (s.groups as List<Map>)*.name.join(', ') : ''}" : s.name }.join(' · ')), width: 6
            String occ = occupiedProfile(cfg)
            href "profilesPage", title: "Comfort", description: esc((cfg.profiles as List<Map>)*.name.join(', ') + (occ ? " · occupied: ${occ}" : '')), width: 6
            href "optionsPage", title: "When it changes", description: esc(optionsSummary(cfg)), width: 6
            href "advancedPage", title: "Thermostats and advanced", description: esc(((thermostats ?: [])*.displayName.join(', ') ?: 'No thermostats') + " · write checks ${settings.verifyWrites != false ? 'on' : 'off'}"), width: 6
        }
        section {
            label title: "Program name", required: true
        }
    }
}

String optionsSummary(Map cfg) {
    List<String> parts = (cfg.overrides as List<Map>).collect { "${modeName(it.modeId)} mode → ${it.profile}".toString() }
    if (occupancySensor) parts << "occupancy from ${occupancySensor.displayName}".toString()
    int pauses = ((pauseWhenOn ?: []) as List).size() + ((pauseWhenOff ?: []) as List).size()
    boolean limits = settings.restrictDays || settings.restrictModes || (settings.restrictFrom && settings.restrictFrom != 'any')
    parts << (pauses || limits ? "pause conditions set" : "no pause conditions")
    return parts.join(' · ')
}

String nowHtml(Map cfg, Map rt, Map target, Map ctx, Map sm) {
    TimeZone tz = ctx.tz as TimeZone
    String status = sm.status as String
    String prof = target.profile == 'custom' ? 'Custom setpoints' : (target.profile ?: '')
    StringBuilder h = new StringBuilder()
    boolean good = status in ['schedule', 'mode', 'hold'] && rt.lastApply == 'ok'
    h << "<div class='p-message ${good ? 'p-message-success' : 'p-message-warn'} p-3 border-round tsp-nowcard'>"
    h << "<div class='tsp-cap'>Now</div>"
    if (target.layer in ['schedule', 'mode', 'hold']) {
        h << "<div class='tsp-big'>${esc(prof)} · ${heatSpan(target.heat)}${target.cool != null ? ' / ' + coolSpan(target.cool) : ''} ${unit()}</div>"
    } else h << "<div class='tsp-big'>${status == 'paused' ? 'Paused' : (status == 'restricted' ? 'Paused by a condition' : 'Nothing scheduled')}</div>"
    Map sched = (cfg.schedules as List<Map>).find { it.name == cfg.active }
    Map cur = sched?.type == 'time' ? currentTransition(sched, ctx) : null
    String why
    if (status == 'paused') why = "The program is switched off. While paused: ${(cfg.options as Map).whilePaused == 'off' ? 'thermostats off' : 'thermostats left as they are'}."
    else if (status == 'restricted') why = "A pause condition is met. While paused: ${(cfg.options as Map).whilePaused == 'off' ? 'thermostats off' : 'thermostats left as they are'}."
    else if (target.layer == 'hold') {
        Map hold = rt.hold as Map
        String until = hold.end == 'next' ? 'the next period' : (hold.end == 'indefinite' ? 'resumed by hand' : "${isoDate(hold.until as long, tz)} ${hhmmOf(hold.until as long, tz)}")
        why = "Held until ${until}"
    } else if (target.layer == 'mode') why = "The hub is in ${modeName(target.overrideModeId)} mode"
    else if (sched?.type == 'mode') why = target.layer == 'none' ? "${sched.name} has no row for ${modeName(ctx.modeId)}: thermostats left as they are" : "${sched.name} schedule, hub mode ${modeName(ctx.modeId)}"
    else why = cur ? "${cur.group} · ${cur.period} since ${hhmmOf(cur.at as long, tz)}" : "${cfg.active} has no period yet"
    Map occ = rt.occ as Map
    if (occupancySensor && occ && target.occupied && cur) {
        Map pv = cur.custom ? valuesOf(cur.custom as Map, ctx) : profileValues(cfg, cur.profile as String, ctx)
        h << "<div>Occupied since ${hhmmOf(occ.since as long, tz)} (${esc(occupancySensor.displayName)}), so ${esc(cur.period)} uses ${esc((cfg.occupancy as Map)?.profile)} instead of ${esc(cur.custom ? 'its own values' : cur.profile)}${pv ? " (${fmtT(pv.heat)}${pv.cool != null ? ' / ' + fmtT(pv.cool) : ''})" : ''}</div>"
    } else if (occupancySensor && occ) {
        h << "<div>${occ.state == 'occupied' ? 'Occupied' : 'Vacant'} since ${hhmmOf(occ.since as long, tz)} (${esc(occupancySensor.displayName)})</div>"
    }
    h << "<div>${esc(why)}</div>"
    if (status == 'manual') {
        List names = (thermostats ?: []).findAll { (rt.manual as List).contains(it.id as String) }*.displayName
        h << "<div>Changed by hand on ${esc(names.join(', '))}; the program's next write replaces it</div>"
    }
    Map nx = sched?.type == 'time' ? nextTransitionOf(sched, ctx) : null
    if (nx) {
        Map v = nx.custom ? valuesOf(nx.custom as Map, ctx) : profileValues(cfg, nx.profile as String, ctx)
        h << "<div style='margin-top:8px'>Next: <strong>${esc(nx.period)}</strong> at ${hhmmOf(nx.at as long, tz)} · ${esc(nx.custom ? 'custom values' : nx.profile)}"
        if (v) h << " ${heatSpan(v.heat)}${v.cool != null ? ' / ' + coolSpan(v.cool) : ''}"
        h << "</div>"
    }
    if (rt.eco) h << "<div>Eco is on: ${fmtT((cfg.eco as Map).offset)} ${unit()} applied</div>"
    return h.append("</div>").toString()
}

// One block per thermostat; a setpoint the thermostat's mode doesn't use is dimmed.
String thermostatPanel() {
    StringBuilder h = new StringBuilder("<div class='tsp-panel'><div class='tsp-cap'>Thermostat${(thermostats ?: []).size() > 1 ? 's' : ''}</div>")
    (thermostats ?: []).each { d ->
        String mode = d.currentValue('thermostatMode') as String
        boolean heatUsed = mode in ['heat', 'emergency heat', 'auto'], coolUsed = mode in ['cool', 'auto']
        h << "<div class='tsp-tstat'><div style='font-weight:500'>${esc(d.displayName)}</div><div class='tsp-kv'>"
        h << "<span class='tsp-dim'>Mode</span><span>${esc(mode)} · ${esc(d.currentValue('thermostatOperatingState'))}</span>"
        h << "<span class='tsp-dim'>Room</span><span>${fmtT(d.currentValue('temperature'))} ${unit()}</span>"
        h << "<span class='tsp-dim'>Heating to</span><span>${heatUsed ? heatSpan(d.currentValue('heatingSetpoint')) : "<span class='tsp-dim'>${fmtT(d.currentValue('heatingSetpoint'))} · not used in ${esc(mode)}</span>"}</span>"
        h << "<span class='tsp-dim'>Cooling to</span><span>${coolUsed ? coolSpan(d.currentValue('coolingSetpoint')) : "<span class='tsp-dim'>${fmtT(d.currentValue('coolingSetpoint'))} · not used in ${esc(mode)}</span>"}</span>"
        h << "</div></div>"
    }
    Map rt = state.rt as Map
    String chk = rt?.verify ? "Write check pending" : (rt?.lastApply == 'ok' ? "Last writes confirmed" : (rt?.lastApply == 'partial' ? "Some writes not confirmed" : "Writes not confirmed"))
    if (rt?.checkedAt && !rt?.verify) chk += " at ${hhmmOf(rt.checkedAt as long, location.timeZone)}"
    return h.append("<div class='tsp-note'>${esc(chk)}</div></div>").toString()
}

// ── Shared UI pieces ──

@Field static final List<String> PROFILE_COLORS = ['#b9773a', '#56739b', '#59716b', '#8a5a9b', '#9b5656', '#5d8a3a', '#3a8a8a', '#7a7a3a']

String profileColor(Map cfg, String name) {
    int i = (cfg.profiles as List<Map>).findIndexOf { it.name == name }
    return i < 0 ? '#777777' : PROFILE_COLORS[i % PROFILE_COLORS.size()]
}

// Current heating and cooling values of a profile, hub variables resolved.
Map profileNow(Map cfg, String name) {
    Map p = (cfg.profiles as List<Map>).find { it.name == name }
    if (!p) return null
    return [heat: p.heatVar ? getGlobalVar(p.heatVar as String)?.value : p.heat, cool: p.coolVar ? getGlobalVar(p.coolVar as String)?.value : p.cool]
}

String profileText(Map cfg, String name) {
    Map v = profileNow(cfg, name)
    if (!v) return name
    List<String> parts = []
    if (v.heat != null) parts << "heat ${fmtT(v.heat)}".toString()
    if (v.cool != null) parts << "cool ${fmtT(v.cool)}".toString()
    return parts ? "${name} · ${parts.join(' / ')}" : name
}

// A swatch, the name and both setpoints, for tables and rows.
String profileHtml(Map cfg, String name, Map custom) {
    if (custom) return "Custom ${heatSpan(custom.heat)}${custom.cool != null ? ' / ' + coolSpan(custom.cool) : ''}"
    Map v = profileNow(cfg, name)
    if (!v) return esc(name)
    String sw = "<span class='tsp-swatch' style='background:${profileColor(cfg, name)}'></span>"
    String vals = [v.heat != null ? heatSpan(v.heat) : null, v.cool != null ? coolSpan(v.cool) : null].findAll { it }.join(' / ')
    return "${sw}${esc(name)}${vals ? " <span style='white-space:nowrap'>${vals}</span>" : ''}"
}

// A whole row that acts as a button: the same markup as cellButton, holding grid cells.
String rowButton(String name, String cells, String cols, String cls = '') {
    return "<div class='form-group'><input type='hidden' name='${name}.type' value='button'></div>" +
           "<div><div class='app-button-link tsp-row ${cls}' role='button' tabindex='0' style='grid-template-columns:${cols}' onclick='buttonClick(this); return false;'>${cells}</div></div>" +
           "<input type='hidden' name='settings[${name}]' value=''>"
}

String rowHeader(String cells, String cols, String cls = '') { return "<div class='tsp-row tsp-rowh ${cls}' style='grid-template-columns:${cols}'>${cells}</div>" }

String setpointsHtml(Map v) {
    if (!v) return ''
    return [v.heat != null ? heatSpan(v.heat) : null, v.cool != null ? coolSpan(v.cool) : null].findAll { it }.join(' / ')
}

// A switch that shows its state; clicking it runs the button.
String switchButton(String name, String label, boolean on, String note) {
    return "<div class='form-group'><input type='hidden' name='${name}.type' value='button'></div>" +
           "<div><div class='app-button-link tsp-swrow' role='switch' aria-checked='${on}' tabindex='0' onclick='buttonClick(this); return false;'>" +
           "<span class='tsp-sw${on ? ' on' : ''}'><span></span></span><span>${esc(label)}</span>${note ? "<span class='tsp-dim tsp-small'>· ${esc(note)}</span>" : ''}</div></div>" +
           "<input type='hidden' name='settings[${name}]' value=''>"
}

// The week of the active time schedule: one row per day, a block per period, occupied periods striped.
String timelineHtml(Map cfg, Map sched, Map ctx) {
    TimeZone tz = ctx.tz as TimeZone
    String today = isoDate(ctx.now as long, tz)
    String monday = addDays(today, 1 - isoDow(today, tz), tz)
    Map wctx = ctx + [sun: weekSun(monday, tz)]
    List<Map> days = weekSegments(sched, wctx, monday)
    String occ = occupiedProfile(cfg)
    StringBuilder h = new StringBuilder("<div class='tsp-panel'><div class='tsp-tlhead'><span style='font-weight:500'>This week</span><span class='tsp-legend'>")
    List<String> used = days.collectMany { (it.segs as List<Map>).findAll { !it.custom }.collect { it.profile as String } }.unique()
    used.each { String n -> h << "<span><span class='tsp-swatch' style='background:${profileColor(cfg, n)}'></span>${esc(n)}</span>" }
    if (occ && days.any { (it.segs as List<Map>).any { it.useOccupied } })
        h << "<span><span class='tsp-swatch' style='background:repeating-linear-gradient(135deg,#777 0 3px,${profileColor(cfg, occ)} 3px 6px)'></span>${esc(occ)} while occupied</span>"
    h << "</span></div><div class='tsp-tl'><span></span><div class='tsp-tlax'>"
    [0, 6, 12, 18, 24].each { int t -> h << "<span style='left:${t * 100 / 24}%;transform:translateX(${t == 0 ? '0' : (t == 24 ? '-100%' : '-50%')})'>${String.format('%02d', t)}</span>" }
    h << "</div>"
    days.eachWithIndex { Map d, int i ->
        boolean isToday = d.iso == today
        h << "<span class='tsp-tlday${isToday ? ' tsp-today' : ''}'>${DAY_ABBR[i]}</span><div class='tsp-tlrow'>"
        (d.segs as List<Map>).each { Map s ->
            double from = s.from as double, to = s.to as double
            String base = s.custom ? '#777777' : profileColor(cfg, s.profile as String)
            boolean striped = occ && s.useOccupied
            String bg = striped ? "repeating-linear-gradient(135deg,${base} 0 6px,${profileColor(cfg, occ)} 6px 12px)" : base
            String name = s.custom ? 'Custom' : s.profile as String
            String title = "${s.period} ${minHhmm(from)}–${minHhmm(to)} · ${name}${striped ? ' (' + occ + ' while occupied)' : ''}"
            String text = (to - from) >= 240 ? name : ''
            h << "<div class='tsp-tlb' title='${esc(title)}' style='left:${from * 100 / 1440}%;width:${(to - from) * 100 / 1440}%;background:${bg}'><span>${esc(text)}</span></div>"
        }
        if (isToday) {
            long dayStart = atLocal(today, '00:00', tz)
            h << "<div class='tsp-tlnow' style='left:${((ctx.now as long) - dayStart) / 600.0 / 1440}%'></div>"
        }
        h << "</div>"
    }
    return h.append("</div></div>").toString()
}

String minHhmm(double m) { int v = Math.round(m) as int; return v >= 1440 ? '24:00' : String.format('%02d:%02d', v.intdiv(60), v % 60) }

Map weekSun(String monday, TimeZone tz) {
    Map sun = [:]
    (-1..7).each { int d ->
        String iso = addDays(monday, d, tz)
        Map s = getSunriseAndSunset(date: new Date(atLocal(iso, "12:00", tz)))
        sun[iso] = [rise: (s.sunrise as Date)?.getTime(), set: (s.sunset as Date)?.getTime()]
    }
    return sun
}

// ── Comfort page (profiles) ──

Map profilesPage() {
    dynamicPage(name: "profilesPage", title: "Comfort") {
        Map cfg = uiCfg()
        Map ui = uiState()
        List<Map> profiles = cfg.profiles as List<Map>
        boolean editing = ui.profile != null && (ui.profile as int) < profiles.size()
        section {
            paragraph pageCss(editing)
            String err = errorPara('profilesPage')
            if (err) paragraph err
        }
        if (editing) {
            Map p = profiles[ui.profile as int]
            section("Edit profile: ${esc(p.name)}") {
                input "edName", "text", title: "Name", width: 6, submitOnChange: true, required: false
                paragraph "", width: 6
                input "edUseVars", "bool", title: "Take setpoints from hub variables", width: 12, submitOnChange: true
                if (edUseVars) {
                    List<String> nv = varNamesOfType(['bigdecimal', 'integer'])
                    input "edHeatVar", "enum", title: "Heating setpoint variable", options: nv, width: 6, submitOnChange: true, required: false
                    input "edCoolVar", "enum", title: "Cooling setpoint variable", options: nv, width: 6, submitOnChange: true, required: false
                } else {
                    input "edHeat", "decimal", title: "Heating setpoint (${unit()})", width: 6, submitOnChange: true, required: false
                    input "edCool", "decimal", title: "Cooling setpoint (${unit()})", width: 6, submitOnChange: true, required: false
                }
                input "edFan", "enum", title: "Fan mode", options: [unchanged: "Leave unchanged", auto: "auto", circulate: "circulate", on: "on"],
                      defaultValue: "unchanged", width: 6, submitOnChange: true
                input "edMode", "enum", title: "Thermostat mode", options: [unchanged: "Leave unchanged", heat: "heat", cool: "cool", auto: "auto", off: "off"],
                      defaultValue: "unchanged", width: 6, submitOnChange: true
                String u = usage(p.name as String, cfg)
                paragraph "<p class='tsp-note'>${u ? 'Used by ' + esc(u) + '.' : 'Not used yet.'} A blank value leaves the thermostat as it is.</p>"
                input "btnProfileSave", "button", title: "Save", width: 2, inputClass: PRI
                input "btnProfileCancel", "button", title: "Cancel", width: 2, inputClass: SEC
                paragraph "", width: 5
                input "btnProfileDelete", "button", title: u ? "Delete (in use)" : "Delete", width: 3, inputClass: u ? SEC : DANGER, disabled: u as boolean
            }
            return
        }
        section("Profiles") {
            String cols = "minmax(0,1.2fr) minmax(0,1.3fr) minmax(0,1fr) minmax(0,2fr) 16px"
            StringBuilder t = new StringBuilder("<div class='tsp-rows'>")
            t << rowHeader("<span>Profile</span><span>Heat / cool</span><span class='tsp-hide-sm'>Fan · mode</span><span class='tsp-hide-sm'>Used by</span><span></span>", cols, 'tsp-frow')
            profiles.eachWithIndex { Map p, int i ->
                String u = usage(p.name as String, cfg)
                String hc = setpointsHtml(profileNow(cfg, p.name as String))
                String vars = [p.heatVar, p.coolVar].findAll { it }.collect { esc(it) }.join(', ')
                t << rowButton("epf~${i}", "<span><span class='tsp-swatch' style='background:${profileColor(cfg, p.name as String)}'></span>${esc(p.name)}</span>" +
                    "<span>${hc ?: "<span class='tsp-dim'>unchanged</span>"}${vars ? "<div class='tsp-dim tsp-small'>from ${vars}</div>" : ''}</span>" +
                    "<span class='tsp-hide-sm'>${esc(p.fan ?: '–')} · ${esc(p.mode ?: '–')}</span>" +
                    "<span class='tsp-hide-sm ${u ? '' : 'tsp-dim'}'>${esc(u ?: 'Not used')}</span><span class='tsp-dim'>›</span>", cols, 'tsp-frow')
            }
            paragraph t.append("</div>").toString()
            input "btnProfileAdd", "button", title: "+ Add profile", width: 3, inputClass: SEC
            paragraph "<p class='tsp-note'>Schedules, mode overrides, holds and occupancy use these by name, so editing one changes every place that uses it.</p>"
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

// ── Schedule page ──

Map schedulesPage() {
    dynamicPage(name: "schedulesPage", title: "Schedule") {
        Map cfg = uiCfg()
        Map ui = uiState()
        List<Map> scheds = cfg.schedules as List<Map>
        int si = uiSchedIndex(cfg)
        Map s = scheds[si]
        boolean editing = ui.schedule == si && ((s.type == 'time' && ((ui.group != null && ui.period != null) || ui.groupName != null)) || (s.type == 'mode' && ui.row != null))
        section {
            paragraph pageCss(editing)
            String err = errorPara('schedulesPage')
            if (err) paragraph err
        }
        // Editing one item takes the whole page; Save or Cancel brings the list back.
        if (ui.schedule == si && s.type == 'time' && ui.group != null && ui.period != null && (ui.group as int) < (s.groups as List).size()) { periodEditor(cfg, s, ui); return }
        if (ui.schedule == si && s.type == 'mode' && ui.row != null) { rowEditor(cfg, s, ui); return }
        if (ui.schedule == si && s.type == 'time' && ui.groupName != null) { groupNameEditor(s, ui); return }
        section {
            scheds.eachWithIndex { Map x, int i ->
                input "esc~${i}", "button", title: esc(x.name == cfg.active ? "${x.name} · active" : x.name), width: 2, inputClass: i == si ? PRI : SEC
            }
            input "btnScheduleNew", "button", title: "+ New schedule", width: 2, inputClass: SEC
        }
        Map ctx = buildCtx(coreConfig())
        if (s.type == 'time') {
            section { paragraph timelineHtml(cfg, s, ctx) }
            timeScheduleSections(cfg, s, si, ui, ctx)
        } else modeScheduleSections(cfg, s, si, ui)
        section("Schedule settings", hideable: true, hidden: !(settings.edSchedName || settings.edSchedType)) {
            input "edSchedName", "text", title: "Schedule name", defaultValue: s.name, width: 6, submitOnChange: true, required: false
            input "edSchedType", "enum", title: "Schedule by", options: [time: "Time of day", mode: "Hub mode"], defaultValue: s.type, width: 6, submitOnChange: true, required: false
            if ((settings.edSchedName && settings.edSchedName != s.name) || (settings.edSchedType && settings.edSchedType != s.type))
                input "btnScheduleSave", "button", title: "Save name and type", width: 3, inputClass: PRI
            input "btnScheduleCopy", "button", title: "Copy to a new schedule", width: 3, inputClass: SEC
            if (s.name != cfg.active) input "btnScheduleActivate", "button", title: "Make ${esc(s.name)} the active schedule", width: 4, inputClass: PRI
            if (s.name != cfg.active) input "btnScheduleDelete", "button", title: "Delete schedule", width: 3, inputClass: DANGER
            else paragraph "<p class='tsp-note'>The active schedule can't be deleted; make another one active first.</p>"
        }
    }
}

@Field static final String PERIOD_COLS = "64px minmax(0,1fr) minmax(0,1.6fr) minmax(0,1fr) 16px"

void timeScheduleSections(Map cfg, Map s, int si, Map ui, Map ctx) {
    List<Map> groups = s.groups as List<Map>
    Map cur = s.name == cfg.active ? currentTransition(s, ctx) : null
    String occ = occupiedProfile(cfg)
    groups.eachWithIndex { Map g, int gi ->
        section(esc(g.name as String)) {
            StringBuilder pills = new StringBuilder("<div class='tsp-pills'>")
            (1..7).each { int d ->
                boolean on = (g.days as List).collect { it as int }.contains(d)
                pills << cellButton("edg~${gi}~${d}", DAY_ABBR[d - 1], false, on ? PILL_ON : PILL_OFF)
            }
            pills << "<span class='tsp-small tsp-dim' style='align-self:center'>${cellButton("egn~${gi}", 'Rename', false, 'min-height:32px')}</span></div>"
            paragraph pills.toString()
            StringBuilder t = new StringBuilder("<div class='tsp-rows'>")
            t << rowHeader("<span>Starts</span><span>Period</span><span>Profile</span><span class='tsp-hide-sm'>While occupied</span><span></span>", PERIOD_COLS, 'tsp-prow')
            (g.periods as List<Map>).eachWithIndex { Map p, int pi ->
                boolean now = cur && cur.group == g.name && cur.period == p.name
                t << rowButton("epd~${gi}~${pi}", "<span class='tsp-mono'>${esc(startLabel(p.start as Map))}</span>" +
                    "<span>${esc(p.name)}${now ? "<span class='tsp-now'>NOW</span>" : ''}</span>" +
                    "<span>${profileHtml(cfg, p.profile as String, p.custom as Map)}</span>" +
                    "<span class='tsp-hide-sm${occ && p.useOccupied ? '' : ' tsp-dim'}'>${occ && p.useOccupied ? esc(occ) : '–'}</span><span class='tsp-dim'>›</span>", PERIOD_COLS, 'tsp-prow')
            }
            if (!g.periods) t << "<div class='tsp-note' style='padding:10px 12px'>No periods: thermostats left as they are</div>"
            t << "</div>${cellButton("apd~${gi}", "+ Add period")}"
            paragraph t.toString()
        }
    }
    section {
        paragraph "<p class='tsp-note'>Click a day to move it into that group. Click a day a group already has to give it a group of its own, with a copy of its periods.</p>"
    }
}

void groupNameEditor(Map s, Map ui) {
    Map g = (s.groups as List<Map>)[ui.groupName as int]
    section("Rename group: ${esc(g?.name)}") {
        input "edGroupName", "text", title: "Group name", width: 6, submitOnChange: true, required: false
        paragraph "", width: 6
        input "btnGroupSave", "button", title: "Save", width: 2, inputClass: PRI
        input "btnGroupCancel", "button", title: "Cancel", width: 2, inputClass: SEC
    }
}

void periodEditor(Map cfg, Map s, Map ui) {
    Map g = (s.groups as List<Map>)[ui.group as int]
    boolean isNew = (ui.period as int) < 0
    String pname = isNew ? 'new period' : (g.periods as List<Map>)[ui.period as int]?.name
    section("${isNew ? 'Add a period to' : 'Edit period:'} ${esc(g.name)}${isNew ? '' : ' · ' + esc(pname)}") {
        input "edName", "text", title: "Period name", width: 6, submitOnChange: true, required: false
        paragraph "", width: 6
        input "edKind", "enum", title: "Starts at", options: KIND_OPTS, defaultValue: "time", width: 6, submitOnChange: true
        paragraph "", width: 6
        String kind = settings.edKind ?: 'time'
        // The platform's time widget is taller than a drop-down; on its own row nothing sits beside it.
        if (kind == 'time') { input "edAt", "time", title: "Time", width: 6, submitOnChange: true, required: false; paragraph "", width: 6 }
        if (kind in ['sunrise', 'sunset']) input "edOffset", "number", title: "Offset in minutes (+/−)", width: 6, submitOnChange: true, required: false
        if (kind == 'var') input "edVar", "enum", title: "Hub variable", options: varNamesOfType(['datetime']), width: 6, submitOnChange: true, required: false
        if (kind in ['sunrise', 'sunset']) {
            Map ctx2 = buildCtx(coreConfig())
            String today = isoDate(ctx2.now as long, ctx2.tz as TimeZone)
            Long base = startOf([kind: kind, offset: 0], today, ctx2)
            Long at = startOf([kind: kind, offset: (settings.edOffset ?: 0) as int], today, ctx2)
            if (base != null) paragraph "<p class='tsp-note'>Today ${kind} is at ${hhmmOf(base, ctx2.tz as TimeZone)}, so this period starts at ${hhmmOf(at, ctx2.tz as TimeZone)}.</p>"
        }
        Map popts = (cfg.profiles as List<Map>).collectEntries { Map p -> [(p.name): profileText(cfg, p.name as String)] } + [custom: "Custom values for this period"]
        input "edProfile", "enum", title: "Profile", options: popts, width: 6, submitOnChange: true, required: false
        if (settings.edProfile == 'custom') {
            input "edHeat", "decimal", title: "Heating setpoint (${unit()})", width: 3, submitOnChange: true, required: false
            input "edCool", "decimal", title: "Cooling setpoint (${unit()})", width: 3, submitOnChange: true, required: false
        } else paragraph "", width: 6
        occupiedToggle(cfg)
        editorButtons("btnPeriodSave", "btnPeriodCancel", isNew ? null : "btnPeriodRemove", "Remove period")
    }
}

void rowEditor(Map cfg, Map s, Map ui) {
    List<Map> rows = (s.rows ?: []) as List<Map>
    boolean isNew = (ui.row as int) < 0
    String title = isNew ? 'new row' : (rows[ui.row as int]?.modes as List)?.collect { modeName(it) }?.join(', ')
    section("${isNew ? 'Add a row' : 'Edit row: ' + esc(title)}") {
        input "edModes", "mode", title: "While the hub mode is", multiple: true, width: 6, submitOnChange: true, required: false
        Map popts = (cfg.profiles as List<Map>).collectEntries { Map p -> [(p.name): profileText(cfg, p.name as String)] } + [custom: "Custom values for this row"]
        input "edProfile", "enum", title: "Profile", options: popts, width: 6, submitOnChange: true, required: false
        if (settings.edProfile == 'custom') {
            input "edHeat", "decimal", title: "Heating setpoint (${unit()})", width: 3, submitOnChange: true, required: false
            input "edCool", "decimal", title: "Cooling setpoint (${unit()})", width: 3, submitOnChange: true, required: false
        }
        occupiedToggle(cfg)
        editorButtons("btnRowSave", "btnRowCancel", isNew ? null : "btnRowRemove", "Remove row")
    }
}

void occupiedToggle(Map cfg) {
    String occ = occupiedProfile(cfg)
    if (!occ) return
    input "edUseOccupied", "bool", title: "Use ${esc(occ)} while someone is here", width: 12, submitOnChange: true, required: false
    paragraph "<p class='tsp-note'>When ${esc(occupancySensor.displayName)} sees motion for ${settings.occupiedAfter != null ? settings.occupiedAfter : 5} min, this period takes ${esc(profileText(cfg, occ))} where it is more comfortable, until ${settings.vacantAfter != null ? settings.vacantAfter : 30} min without motion.</p>"
}

void editorButtons(String save, String cancel, String remove, String removeTitle) {
    input save, "button", title: "Save", width: 2, inputClass: PRI
    input cancel, "button", title: "Cancel", width: 2, inputClass: SEC
    if (remove) {
        paragraph "", width: 5
        input remove, "button", title: removeTitle, width: 3, inputClass: DANGER
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
    String occ = occupiedProfile(cfg)
    String cols = "minmax(0,1.4fr) minmax(0,1.6fr) minmax(0,1fr) 16px"
    section("By hub mode") {
        StringBuilder t = new StringBuilder("<div class='tsp-rows'>")
        t << rowHeader("<span>When the hub mode is</span><span>Profile</span><span class='tsp-hide-sm'>While occupied</span><span></span>", cols, 'tsp-mrow')
        rows.eachWithIndex { Map r, int i ->
            String modes = (r.modes as List).collect { modeName(it) }.join(', ')
            t << rowButton("emr~${i}", "<span>${esc(modes ?: '(no mode)')}</span><span>${profileHtml(cfg, r.profile as String, r.custom as Map)}</span>" +
                "<span class='tsp-hide-sm${occ && r.useOccupied ? '' : ' tsp-dim'}'>${occ && r.useOccupied ? esc(occ) : '–'}</span><span class='tsp-dim'>›</span>", cols, 'tsp-mrow')
        }
        List covered = rows.collectMany { (it.modes as List).collect { m -> m as Long } }
        location.modes.findAll { !covered.contains(it.id as Long) }.each { m ->
            t << "<div class='tsp-row tsp-dim' style='grid-template-columns:${cols}'><span>${esc(m.name)}</span><span>No row: thermostats left as they are</span><span></span><span></span></div>"
        }
        paragraph t.append("</div>").toString()
        input "btnRowAdd", "button", title: "+ Add row", width: 2, inputClass: SEC
        paragraph "<p class='tsp-note'>A row can cover several modes. Mode overrides still apply on top of this schedule.</p>"
    }
}

// ── When it changes page ──

Map optionsPage() {
    dynamicPage(name: "optionsPage", title: "When it changes") {
        applyLiveSettings()
        Map cfg = uiCfg()
        Map ui = uiState()
        List<Map> ovs = cfg.overrides as List<Map>
        section {
            paragraph pageCss(ui.override != null) + "<p class='tsp-note'>What moves the program off its schedule. Changes on this page apply as soon as you make them.</p>"
            String err = errorPara('optionsPage')
            if (err) paragraph err
        }
        if (ui.override != null) {
            section((ui.override as int) < 0 ? "Add a mode override" : "Edit mode override: ${esc(modeName(ovs[ui.override as int]?.modeId))}") {
                input "edOvMode", "mode", title: "While the hub mode is", width: 6, submitOnChange: true, required: false
                input "edProfile", "enum", title: "Use profile", options: (cfg.profiles as List<Map>).collectEntries { Map p -> [(p.name): profileText(cfg, p.name as String)] }, width: 6, submitOnChange: true, required: false
                paragraph "<p class='tsp-note'>Overrides apply on top of any schedule. A hold set to end at the next period also ends when an override starts or ends.</p>"
                editorButtons("btnOverrideSave", "btnOverrideCancel", (ui.override as int) >= 0 ? "btnOverrideRemove" : null, "Remove override")
            }
            return
        }
        section("Hub mode") {
            String cols = "minmax(0,1fr) minmax(0,1.6fr) 16px"
            StringBuilder t = new StringBuilder("<div class='tsp-rows'>")
            t << rowHeader("<span>While the hub mode is</span><span>Use</span><span></span>", cols)
            ovs.eachWithIndex { Map o, int i ->
                t << rowButton("eov~${i}", "<span>${esc(modeName(o.modeId))}</span><span>${profileHtml(cfg, o.profile as String, null)}</span><span class='tsp-dim'>›</span>", cols)
            }
            if (!ovs) t << "<div class='tsp-note' style='padding:10px 12px'>No overrides</div>"
            paragraph t.append("</div>").toString()
            input "btnOverrideAdd", "button", title: "+ Add a mode", width: 3, inputClass: SEC
        }
        section("Someone is here") {
            Map occ = (state.rt as Map)?.occ as Map
            if (occupancySensor && occ) paragraph "<p class='tsp-note'>${occ.state == 'occupied' ? 'Occupied' : 'Vacant'} since ${hhmmOf(occ.since as long, location.timeZone)}</p>"
            input "occupancySensor", "capability.motionSensor", title: "Motion sensor", required: false, submitOnChange: true, width: 6
            if (occupancySensor) {
                String cur = (cfg.occupancy as Map)?.profile as String
                input "occProfileSel", "enum", title: "While occupied, use", options: [none: "Nothing (occupancy off)"] + (cfg.profiles as List<Map>).collectEntries { Map p -> [(p.name): profileText(cfg, p.name as String)] },
                      defaultValue: cur ?: 'none', width: 6, submitOnChange: true, required: false
                input "occupiedAfter", "number", title: "Occupied after (minutes of motion)", defaultValue: 5, range: "0..1440", width: 6, submitOnChange: true
                input "vacantAfter", "number", title: "Vacant after (minutes without motion)", defaultValue: 30, range: "0..1440", width: 6, submitOnChange: true
                List<String> users = occupiedUsers(cfg)
                paragraph "<p class='tsp-note'>${users ? 'Used by ' + esc(users.join(', ')) + '.' : 'No period uses it yet: turn it on in a period on the Schedule page.'} It never lowers a period's comfort. To use several sensors, combine them first with Zone Motion Controller or Sensor Aggregator.</p>"
            }
        }
        section("Pause") {
            input "pauseWhenOn", "capability.switch", title: "Pause while any of these is on", multiple: true, required: false, submitOnChange: true, width: 6
            input "pauseWhenOff", "capability.switch", title: "Pause while any of these is off", multiple: true, required: false, submitOnChange: true, width: 6
            restrictTimeInputs("restrictFrom", "Only run from")
            if (settings.restrictFrom && settings.restrictFrom != 'any') restrictTimeInputs("restrictTo", "until")
            input "restrictDays", "enum", title: "Only run on these days", multiple: true, required: false, width: 6, submitOnChange: true,
                  options: ["1": "Monday", "2": "Tuesday", "3": "Wednesday", "4": "Thursday", "5": "Friday", "6": "Saturday", "7": "Sunday"]
            input "restrictModes", "mode", title: "Only run in these modes", multiple: true, required: false, width: 6, submitOnChange: true
            input "whilePaused", "enum", title: "While paused", options: ["leave": "Leave the thermostats as they are", "off": "Turn the thermostats off"], defaultValue: "leave", width: 6, submitOnChange: true
            input "onResume", "enum", title: "When the pause ends", options: ["restore": "Restore the mode and apply the schedule", "leaveOff": "Leave the thermostats off"], defaultValue: "restore", width: 6, submitOnChange: true
        }
    }
}

List<String> occupiedUsers(Map cfg) {
    List<String> out = []
    (cfg.schedules as List<Map>).each { Map s ->
        (s.groups as List<Map>)?.each { Map g -> (g.periods as List<Map>).findAll { it.useOccupied }.each { out << "${g.name} · ${it.name}".toString() } }
        (s.rows as List<Map>)?.findAll { it.useOccupied }?.each { out << "${s.name} · ${(it.modes as List).collect { m -> modeName(m) }.join(', ')}".toString() }
    }
    return out
}

void restrictTimeInputs(String n, String title) {
    input n, "enum", title: title, options: [any: "Any time", time: "A specific time", sunrise: "Sunrise", sunset: "Sunset"],
          defaultValue: "any", width: 3, submitOnChange: true
    String k = settings[n] as String
    if (k == 'time') input "${n}At", "time", title: "Time", width: 3, required: false, submitOnChange: true
    else if (k in ['sunrise', 'sunset']) input "${n}Offset", "number", title: "Offset in minutes (+/−)", width: 3, required: false, submitOnChange: true
    else paragraph "", width: 3
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

// ── Thermostats and advanced page ──

Map advancedPage() {
    dynamicPage(name: "advancedPage", title: "Thermostats and advanced") {
        applyLiveSettings()
        section {
            paragraph TSP_CSS + "<p class='tsp-note'>Changes on this page apply as soon as you make them.</p>"
            input "thermostats", "capability.thermostat", title: "Thermostats", multiple: true, required: true, submitOnChange: true
            ChildDeviceWrapper d = app.getInstallationState() == "COMPLETE" ? getChildDevice("tsp-${app.id}") : null
            if (d) href "programDevice", title: "Program device", description: esc(d.displayName), url: "/device/edit/${d.id}", style: "external"
        }
        section("Eco") {
            input "ecoOffset", "decimal", title: "Eco offset (${unit()})", defaultValue: 2.0, width: 6, submitOnChange: true
            input "ecoOnOverrides", "bool", title: "Also apply eco on top of mode overrides", defaultValue: true, width: 6, submitOnChange: true
            paragraph "<p class='tsp-note'>Eco lowers the heating setpoint and raises the cooling setpoint by the offset. Changing the offset while eco is on recalculates from the schedule.</p>"
        }
        section("Writing to thermostats") {
            input "separation", "decimal", title: "Required heat/cool separation (${unit()})", defaultValue: 2.0, width: 6, submitOnChange: true
            paragraph "", width: 6
            input "verifyWrites", "bool", title: "Check each write and report thermostats that do not take the new value (resending is Hubitat's Command Retry)", defaultValue: true, submitOnChange: true
            input "applyOnStart", "bool", title: "Apply the schedule after a hub restart", defaultValue: true, submitOnChange: true
        }
        section("Logging") {
            input "txtEnable", "bool", title: "Info logging", defaultValue: true, submitOnChange: true
            input "debugEnable", "bool", title: "Debug logging (turns off after 30 minutes)", defaultValue: false, submitOnChange: true
            if (debugEnable) input "traceEnable", "bool", title: "Trace logging", defaultValue: false, submitOnChange: true
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

// Settings changed on a page apply on the next render, without waiting for Done: the occupied
// profile picker writes the configuration, and any other change re-subscribes and re-evaluates.
@Field static final List<String> LIVE_KEYS = ['thermostats', 'occupancySensor', 'occupiedAfter', 'vacantAfter', 'pauseWhenOn', 'pauseWhenOff',
    'restrictFrom', 'restrictFromAt', 'restrictFromOffset', 'restrictTo', 'restrictToAt', 'restrictToOffset', 'restrictDays', 'restrictModes',
    'whilePaused', 'onResume', 'ecoOffset', 'ecoOnOverrides', 'separation', 'verifyWrites', 'applyOnStart']

String liveSig() {
    return LIVE_KEYS.collect { String k ->
        Object v = settings[k]
        String s = v instanceof Collection ? (v as Collection).collect { it instanceof DeviceWrapper ? (it as DeviceWrapper).id.toString() : it.toString() }.sort().join(',')
                 : (v instanceof DeviceWrapper ? (v as DeviceWrapper).id.toString() : v?.toString())
        "${k}=${s}"
    }.join('|')
}

void applyLiveSettings() {
    if (app.getInstallationState() != "COMPLETE") return
    String sel = settings.occProfileSel as String
    if (sel != null) {
        Map c = cfgCopy()
        String cur = (c.occupancy as Map)?.profile as String
        app.removeSetting("occProfileSel")
        if ((sel == 'none' ? null : sel) != cur) {
            if (sel == 'none') c.remove('occupancy') else c.occupancy = [profile: sel]
            saveConfig(c)
        }
    }
    String sig = liveSig()
    if (state.liveSig == sig) return
    boolean first = state.liveSig == null
    state.liveSig = sig
    if (first) return
    logCfg "settings changed"
    resubscribe()
    occupancySeed()
    evaluate("settings changed", false)
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
        if (u) { uiErr("${n} is used by ${u}".toString()); logWarn "${n} is used by ${u}"; return true }
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
        prefill("edUseOccupied", "bool", p.useOccupied == true ? true : null)
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
        prefill("edUseOccupied", "bool", r.useOccupied == true ? true : null)
        return true
    }
    if (btn == "btnGroupSave") {
        if (ui.groupName == null) return true
        Map c = cfgCopy(); Map g = ((c.schedules as List<Map>)[si].groups as List<Map>)[ui.groupName as int]
        String nn = (settings.edGroupName ?: '').toString().trim()
        if (!nn) { uiErr("A group needs a name"); return true }
        if (((c.schedules as List<Map>)[si].groups as List<Map>).any { it != g && it.name == nn }) { uiErr("Group name used twice: ${nn}"); return true }
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
        if (settings.edUseOccupied == true) np.useOccupied = true
        List<Map> periods = g.periods as List<Map>
        if ((ui.period as int) < 0) periods << np else periods[ui.period as int] = np
        if (periods.count { it.name == np.name } > 1) { uiErr("Period name used twice: ${np.name}"); return true }
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
        if (settings.edUseOccupied == true) nr.useOccupied = true
        if (!nr.modes) { uiErr("Choose at least one mode"); return true }
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
        if (n == cfg.active) { uiErr("${n} is the active schedule".toString()); logWarn "${n} is the active schedule"; return true }
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
        if (!settings.edOvMode || !settings.edProfile) { uiErr("Choose a mode and a profile"); return true }
        Map c = cfgCopy(); List<Map> ovs = c.overrides as List<Map>
        Map no = [modeId: settings.edOvMode as Long, profile: settings.edProfile]
        int i = ui.override as int
        if (ovs.findIndexOf { (it.modeId as Long) == (no.modeId as Long) } >= 0 && ovs.findIndexOf { (it.modeId as Long) == (no.modeId as Long) } != i) {
            uiErr("${modeName(no.modeId)} already has an override"); return true
        }
        if (i < 0) ovs << no else ovs[i] = no
        if (saveConfig(c)) state.ui = [:]
        return true
    }
    if (btn == "btnOccSave") {
        Map c = cfgCopy()
        String p = settings.edOccProfile as String
        if (!p || p == 'none') c.remove('occupancy') else c.occupancy = [profile: p]
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
void uninstalled() { if (getChildDevice("tsp-${app.id}")) deleteChildDevice("tsp-${app.id}") }

Map newRt() {
    return [paused: false, eco: false, hold: null, pausedApplied: false, recordedModes: [:],
            sent: [:], manual: [], lastTarget: null, lastApply: 'ok', verify: null]
}

void initialize() {
    checkVersion(false)
    if (!state.config) state.config = newConfig()
    if (state.rt == null) state.rt = newRt()
    if (!settings.testClock) app.removeSetting("testClock")
    programDevice()
    if ((state.rt as Map).verify) runIn(30, "verifyWrites")
    occupancySeed()
    subscribeAll()
    state.liveSig = liveSig()
    if (debugEnable || traceEnable) runIn(1800, "logsOff")
    evaluate("initialize", false)
}

// Also run after every configuration save, so the hub variables in use stay registered and watched.
void subscribeAll() {
    subscribe(thermostats, "heatingSetpoint", "thermostatEvent")
    subscribe(thermostats, "coolingSetpoint", "thermostatEvent")
    subscribe(thermostats, "thermostatMode", "thermostatEvent")
    subscribe(location, "mode", "modeHandler")
    subscribe(location, "systemStart", "startHandler")
    [pauseWhenOn, pauseWhenOff].each { if (it) subscribe(it, "switch", "pauseSwitchHandler") }
    if (occupancySensor) subscribe(occupancySensor, "motion", "occupancyHandler")
    List<String> vars = varNames((state.config ?: [:]) as Map)
    removeAllInUseGlobalVar()
    if (vars) addInUseGlobalVar(vars)
    vars.each { String n -> subscribe(location, "variable:${n}".toString(), "variableHandler") }
}

void resubscribe() { unsubscribe(); subscribeAll() }

// Called by the hub when a hub variable this program uses is renamed.
void renameVariable(String oldName, String newName) {
    checkVersion()
    Map c = cfgCopy()
    if (!renameVarRefs(c, oldName, newName)) return
    state.config = c
    logCfg "hub variable ${oldName} renamed to ${newName}"
    resubscribe()
}

void variableHandler(evt) { checkVersion(); logEvt "${evt.name} ${evt.value}"; evaluate("variable ${evt.name}", false) }

void logsOff() {
    checkVersion()
    app.updateSetting("debugEnable", false); app.updateSetting("traceEnable", false)
    app.removeSetting("testClock")
    logWarn "debug logging and the test clock turned off"
    evaluate("test clock cleared", false)
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

ChildDeviceWrapper programDevice() {
    String dni = "tsp-${app.id}"
    ChildDeviceWrapper d = getChildDevice(dni)
    if (!d) {
        d = addChildDevice("iamtrep", "HVAC Scheduler Program Device", dni, [name: "${app.label} scheduler", label: "${app.label} scheduler", isComponent: true])
        logCfg "created ${d.displayName}"
    } else if (d.label != "${app.label} scheduler") d.setLabel("${app.label} scheduler")
    return d
}

Map coreConfig() {
    Map c = ((state.config ?: newConfig()) as Map) + [
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
        else if (v instanceof String && ((String) v).contains("T")) {
            try { v = toDateTime(v as String).getTime() } catch (Exception e) { v = null }
        }
        vars[n] = v
    }
    Map ctx = [now: t, tz: tz, modeId: location.currentMode?.id as Long, sun: sun, vars: vars,
               occupied: occupancySensor ? (((state.rt as Map)?.occ as Map)?.state == 'occupied') : null]
    ctx.restricted = restrictedNow(cfg.restrictions as Map, ctx) || switchRestricted()
    return ctx
}

// ── Occupancy ──
// Set apart from evaluate(): sensor events only decide and schedule; a commit that changes the
// zone's state is the only path from the sensor to the setpoints.

long occDelayMs(String name, int dflt) {
    Object v = settings[name]
    return ((v != null ? v : dflt) as long) * 60000L
}

Map occupancyDecision(String reading, Long changedAt) {
    return occupancyStep(((state.rt as Map)?.occ as Map)?.state as String, reading, changedAt, now(),
                         occDelayMs("occupiedAfter", 5), occDelayMs("vacantAfter", 30))
}

void occupancyWait(long at) {
    int secs = Math.max(1L, (long) Math.ceil((at - now()) / 1000.0d)) as int
    runIn(secs, "commitOccupancy")
    logSched "occupancy commit in ${secs} s"
}

// Install, Done and hub start: re-read the sensor, keep the committed state, commit or wait out the time left.
void occupancySeed() {
    Map rt = state.rt as Map
    if (!occupancySensor) {
        if (rt.occ != null) { rt.occ = null; state.rt = rt }
        return
    }
    State cs = occupancySensor.currentState("motion")
    if (cs == null) return
    Map step = occupancyDecision(cs.value as String, cs.date?.getTime())
    if (step.commit) {
        rt.occ = [state: step.commit, since: step.since]
        state.rt = rt
        logCfg "zone ${step.commit} (${occupancySensor.displayName})"
    } else if (step.at != null) occupancyWait(step.at as long)
}

void occupancyHandler(evt) {
    checkVersion()
    logEvt "${evt.displayName} motion ${evt.value}"
    Map step = occupancyDecision(evt.value as String, (evt.date as Date)?.getTime())
    if (step.cancel) unschedule("commitOccupancy")
    else if (step.at != null) occupancyWait(step.at as long)
    else commitOccupancy()
}

void commitOccupancy() {
    checkVersion()
    if (!occupancySensor) return
    State cs = occupancySensor.currentState("motion")
    if (cs == null) return
    Map step = occupancyDecision(cs.value as String, cs.date?.getTime())
    if (step.at != null) { occupancyWait(step.at as long); return }
    if (!step.commit) return
    Map rt = state.rt as Map
    rt.occ = [state: step.commit, since: step.since]
    state.rt = rt
    logCfg "zone ${step.commit} (${occupancySensor.displayName})"
    evaluate("occupancy ${step.commit}", false)
}

boolean switchRestricted() {
    return (pauseWhenOn ?: []).any { it.currentValue("switch") == "on" } ||
           (pauseWhenOff ?: []).any { it.currentValue("switch") == "off" }
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
    // A write check whose job was lost (crash, push) runs at the next evaluation.
    if (verifyOverdue(rt.verify as Map, now())) runIn(1, "verifyWrites")
    Map ctx = buildCtx(cfg)
    Map target = resolveTarget(cfg, rt, ctx)
    if (holdExpired(rt.hold as Map, target, ctx.now as long)) {
        logCfg "hold ended"
        rt.hold = null
        target = resolveTarget(cfg, rt, ctx)
    }
    // Armed before any device command, so a failing write cannot leave the program without a wake.
    armWake(cfg, rt, ctx)
    try {
        applyTarget(cfg, rt, target, force, onlyId)
    } catch (Exception e) {
        logError "evaluate (${why}): ${e}"
    }
    rt.lastTarget = target
    state.rt = rt
    publish(cfg, rt, target, ctx)
    logSched "evaluate (${why}): ${target.layer} ${target.profile ?: ''}"
    armWake(cfg, rt, ctx)
}

void armWake(Map cfg, Map rt, Map ctx) {
    if (!(debugEnable && settings.testClock)) runOnce(new Date(nextWake(cfg, rt, ctx)), "wakeHandler")
}

void applyTarget(Map cfg, Map rt, Map target, boolean force, String onlyId) {
    Map modeOverride = [:]
    List<String> noModeIds = []
    if (target.layer == 'paused' && !rt.pausedApplied) {
        rt.pausedApplied = true
        if ((cfg.options as Map).whilePaused == 'off') {
            rt.recordedModes = [:]
            thermostats.each { d ->
                String m = d.currentValue("thermostatMode") as String
                if (m != 'off' && send(d, 'setThermostatMode', 'off')) { rt.recordedModes[d.id as String] = m; remember(rt, d.id as String, 'thermostatMode', 'off') }
            }
            logCmd "paused: thermostats off"
        } else logCfg "paused"
    } else if (target.layer != 'paused' && rt.pausedApplied) {
        rt.pausedApplied = false
        if ((cfg.options as Map).onResume == 'restore') {
            (rt.recordedModes as Map).each { String id, String m ->
                DeviceWrapper d = thermostats.find { (it.id as String) == id }
                if (d && send(d, 'setThermostatMode', m)) { remember(rt, id, 'thermostatMode', m); modeOverride[id] = m }
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
    if (target.layer != 'paused' && (changed || force || onlyId)) {
        List<Map> states = thermostatStates(modeOverride).findAll { changed || force || onlyId == null || it.id == onlyId }
        if (onlyId && !changed && !force) noModeIds << onlyId
        List<Map> writes = planWrites(target, states, [separation: (cfg.options as Map).separation, force: force, noModeIds: noModeIds])
        // Only thermostats that get a write lose their manual status.
        rt.manual = ((rt.manual ?: []) as List) - writes*.id.unique()
        sendWrites(writes, rt, cfg)
    }
}

boolean send(DeviceWrapper d, String command, Object value) {
    try {
        d."${command}"(value)
        return true
    } catch (Exception e) {
        logWarn "${d.displayName}: ${command} ${value} failed: ${e}"
        return false
    }
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
        DeviceWrapper d = thermostats.find { (it.id as String) == w.id }
        if (!d) return
        if (!send(d, w.command as String, w.value)) return
        remember(rt, w.id as String, ATTR_FOR[w.command] as String, w.value)
        logCmd "${d.displayName}: ${w.command} ${w.value}"
    }
    if ((cfg.options as Map).verify) {
        // A batch sent while another waits for its check joins it; the check runs 30 s after the latest write.
        Map pending = rt.verify as Map
        rt.verify = [writes: mergeWrites((pending?.writes ?: []) as List<Map>, writes), at: pending?.at ?: now(), last: now()]
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
        DeviceWrapper d = thermostats.find { (it.id as String) == w.id }
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
    programDevice().updateStatus([lastApply: rt.lastApply])
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
        eco: rt.eco ? 'on' : 'off', ecoOffset: (cfg.eco as Map).offset, lastApply: rt.lastApply,
        occupancy: occupancySensor ? (((rt.occ as Map)?.state ?: 'none') as String) : 'none']
}

void publish(Map cfg, Map rt, Map target, Map ctx) {
    programDevice().updateStatus(statusMap(cfg, rt, target, ctx))
}

// ── Handlers ──────────────────────────────────────────────────────────

void wakeHandler() { checkVersion(); evaluate("wake", false) }
void modeHandler(evt) { checkVersion(); logEvt "mode ${evt.value}"; evaluate("mode", false) }
void pauseSwitchHandler(evt) { checkVersion(); logEvt "pause switch ${evt.displayName} ${evt.value}"; evaluate("pause switch", false) }
void startHandler(evt = null) {
    checkVersion()
    Map rt = state.rt as Map
    rt.verify = null
    state.rt = rt
    occupancySeed()
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
    if (!state.config) state.config = newConfig()
    state.remove('uiError')
    state.uiPage = pageForButton(btn)
    if (btn == "btnEvaluate") evaluate("button", false)
    else if (btn == "btnApply") evaluate("apply now", true)
    else if (btn == "btnStart") startHandler()
    else if (btn == "btnLoadConfig") loadConfigJson()
    else if (btn == "btnImportDismiss") state.remove('importReport')
    else if (btn in ["btnAdvance", "btnEco", "btnPause", "btnResume", "btnHold"]) controlButton(btn)
    else uiButton(btn)
}

void loadConfigJson() {
    Map doc
    try { doc = parseJson(settings.testConfigJson as String) as Map } catch (Exception e) { logWarn "configuration is not JSON"; return }
    List<String> errs = validateConfig(doc, validationEnv())
    if (errs) { logWarn "configuration rejected: ${errs}"; return }
    replaceConfig(doc, [:], true)
}

// One save path for the configuration JSON input, PUT and the importer. The caller has validated.
void replaceConfig(Map doc, Map optionGroups, boolean force) {
    optionSettings(optionGroups ?: [:]).each { Map w ->
        if (w.value == null) app.removeSetting(w.name as String)
        else app.updateSetting(w.name as String, [type: w.type, value: w.value])
    }
    Map rt = state.rt as Map
    Map hold = rt?.hold as Map
    if (hold?.kind == 'profile' && !(doc.profiles as List<Map>).any { it.name == hold.profile }) {
        rt.hold = null
        state.rt = rt
        logCfg "hold on ${hold.profile} ended: the profile was removed"
    }
    state.config = doc
    state.ui = [:]
    clearEdits()
    logCfg "configuration replaced"
    if (app.getInstallationState() == "COMPLETE") {
        resubscribe()
        evaluate("config replaced", force)
    }
}

// Reply body plus httpStatus; the document carries its own `status` field.
Map apiPut(Map body) {
    checkVersion()
    if (!(body?.config instanceof Map)) return [httpStatus: 400, error: "config is required"]
    if (body.revision == null) return [httpStatus: 400, error: "revision is required"]
    int current = revisionOf(coreConfig())
    if (body.revision.toString() != current.toString()) return [httpStatus: 409, error: "configuration changed since it was read", revision: current]
    Map c = body.config as Map
    Map env = validationEnv()
    List<String> errs = putShapeErrors(c)
    Map doc = errs ? null : putDocument(c)
    if (!errs) errs = validateConfig(doc, env) + validateOptions(c, env)
    if (errs) { logWarn "PUT rejected: ${errs.join('; ')}"; return [httpStatus: 400, error: "invalid configuration", errors: errs] }
    replaceConfig(doc, c.subMap(['eco', 'options', 'restrictions']), false)
    return [httpStatus: 200] + apiDocument()
}

Map validationEnv() {
    return [scale: location.temperatureScale, vars: getAllGlobalVars()?.keySet()?.collect { it as String } ?: [],
            modeIds: location.modes.collect { it.id as Long }]
}

// ── Commands (device, API) ────────────────────────────────────────────

Map deviceCommand(Map req) { return apiCommand(req) }

Map apiCommand(Map req) {
    checkVersion()
    Map p = parseCommand(req, nowMillis())
    if (!p.ok) { logWarn "${req?.command}: ${p.error}"; return p }
    if (p.name == 'holdSetpoints') {
        String rangeErr = setpointRangeError((p.args as Map).heat, (p.args as Map).cool, location.temperatureScale as String)
        if (rangeErr) { logWarn "holdSetpoints: ${rangeErr}"; return [ok: false, error: rangeErr] }
    }
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

// Builds this program from a built-in scheduler read by the parent. The program starts paused with the
// pause already applied, so saving it with Done writes nothing while the built-in still runs.
Map importBuiltin(Map raw) {
    checkVersion(false)
    Map conv = convertBuiltin((raw.settings ?: [:]) as Map, (raw.appState ?: [:]) as Map,
                              location.modes.collect { [id: it.id as Long, name: it.name as String] })
    Map env = validationEnv()
    List<String> errs = (conv.errors as List<String>) + validateConfig(conv.doc as Map, env) + validateOptions(conv.options as Map, env)
    if (errs) { logWarn "import of ${raw.fromLabel} rejected: ${errs.join('; ')}"; return [ok: false, errors: errs] }
    app.updateSetting("thermostats", [type: "capability.thermostat", value: conv.thermostats])
    if (conv.pauseSwitch) {
        app.updateSetting(conv.pauseWhen == 'on' ? "pauseWhenOn" : "pauseWhenOff", [type: "capability.switch", value: [conv.pauseSwitch]])
    }
    state.rt = newRt() + [paused: true, pausedApplied: true, eco: conv.eco]
    replaceConfig(conv.doc as Map, conv.options as Map, false)
    state.importedFrom = raw.fromId
    state.importReport = [from: raw.fromLabel, warnings: conv.warnings]
    logCfg "imported from ${raw.fromLabel}"
    return [ok: true, warnings: conv.warnings]
}

Long importedFrom() { return state.importedFrom as Long }

Map apiStatus() {
    Map rt = (state.rt ?: [:]) as Map
    Map cfg = coreConfig()
    return [id: app.id, name: app.label] + statusMap(cfg, rt, (rt.lastTarget ?: [layer: 'none']) as Map, buildCtx(cfg))
}

Map apiDocument() {
    Map cfg = coreConfig()
    return [id: app.id, name: app.label, revision: revisionOf(coreConfig()),
            thermostats: (thermostats ?: []).collect { [id: it.id, name: it.displayName] },
            config: cfg, status: apiStatus()]
}

void checkVersion(boolean reinit = true) {
    if (state.version == CODE_VERSION) return
    logVer "version ${CODE_VERSION} (was ${state.version})"
    state.version = CODE_VERSION
    migratePauseSwitch()
    if (reinit) runIn(1, "updated")
}

// Before 0.3.0 a program had one pause switch and a state; it joins the matching list.
void migratePauseSwitch() {
    DeviceWrapper sw = settings.pauseSwitch
    if (sw) {
        String key = settings.pauseWhenSwitch == 'on' ? "pauseWhenOn" : "pauseWhenOff"
        List<String> ids = (((settings[key] ?: []) as List)*.id + [sw.id]).collect { it.toString() }.unique()
        app.updateSetting(key, [type: "capability.switch", value: ids])
        logCfg "pause switch ${sw.displayName} moved to ${key == 'pauseWhenOn' ? 'pause while on' : 'pause while off'}"
    }
    app.removeSetting("pauseSwitch")
    app.removeSetting("pauseWhenSwitch")
}

// ── Core (pure) ───────────────────────────────────────────────────────
// Self-contained: arguments in, values out. No settings, state, devices or logs.
// tests/test_core.groovy parses and runs this block off-hub.

@CompileStatic
Calendar calFor(String iso, TimeZone tz) {
    Calendar c = Calendar.getInstance(tz)
    c.clear()
    String[] p = iso.split('-')
    c.set(p[0] as int, (p[1] as int) - 1, p[2] as int, 0, 0, 0)
    return c
}

@CompileStatic
String isoDate(long t, TimeZone tz) {
    java.text.SimpleDateFormat f = new java.text.SimpleDateFormat('yyyy-MM-dd')
    f.setTimeZone(tz)
    return f.format(new Date(t))
}

@CompileStatic
String hhmmOf(long t, TimeZone tz) {
    java.text.SimpleDateFormat f = new java.text.SimpleDateFormat('HH:mm')
    f.setTimeZone(tz)
    return f.format(new Date(t))
}

@CompileStatic
String addDays(String iso, int n, TimeZone tz) {
    Calendar c = calFor(iso, tz)
    c.add(Calendar.DAY_OF_MONTH, n)
    return isoDate(c.getTimeInMillis(), tz)
}

@CompileStatic
int isoDow(String iso, TimeZone tz) {
    int d = calFor(iso, tz).get(Calendar.DAY_OF_WEEK)
    return d == Calendar.SUNDAY ? 7 : d - 1
}

@CompileStatic
long atLocal(String iso, String hhmm, TimeZone tz) {
    Calendar c = calFor(iso, tz)
    String[] p = hhmm.split(':')
    c.set(Calendar.HOUR_OF_DAY, p[0] as int)
    c.set(Calendar.MINUTE, p[1] as int)
    return c.getTimeInMillis()
}

@CompileStatic
BigDecimal numOrNull(Object o) {
    if (o instanceof Number) return new BigDecimal(o.toString())
    if (o instanceof String && ((String) o).trim().isBigDecimal()) return new BigDecimal(((String) o).trim())
    return null
}

@CompileStatic
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

@CompileStatic
Map groupFor(Map sched, int dow) {
    return (sched.groups as List<Map>)?.find { Map g -> (g.days as List)?.collect { it as int }?.contains(dow) }
}

@CompileStatic
List<Map> transitionsForDay(Map sched, String iso, Map ctx) {
    Map g = groupFor(sched, isoDow(iso, ctx.tz as TimeZone))
    if (!g) return []
    List<Map> out = []
    for (Map p in (g.periods as List<Map>)) {
        Long at = startOf(p.start as Map, iso, ctx)
        if (at != null) {
            out << [at: at, key: "${iso}|${g.name}|${p.name}".toString(), group: g.name, period: p.name,
                    profile: p.profile, custom: p.custom, useOccupied: p.useOccupied == true]
        }
    }
    return out.sort { Map t -> t.at as Long }
}

@CompileStatic
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

@CompileStatic
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

// The week from mondayIso: per day, the periods in effect from midnight to midnight, in minutes.
@CompileStatic
List<Map> weekSegments(Map sched, Map ctx, String mondayIso) {
    TimeZone tz = ctx.tz as TimeZone
    List<Map> out = []
    for (int d = 0; d < 7; d++) {
        String iso = addDays(mondayIso, d, tz)
        long start = atLocal(iso, '00:00', tz), end = atLocal(addDays(iso, 1, tz), '00:00', tz)
        Map atStart = new LinkedHashMap(ctx)
        atStart.put('now', start)
        Map cur = currentTransition(sched, atStart)
        long from = start
        List<Map> segs = []
        for (Map tr in transitionsForDay(sched, iso, ctx).findAll { Map x -> (x.at as long) > start }) {
            if (cur) segs << weekSeg(cur, from, tr.at as long, start, end)
            cur = tr
            from = tr.at as long
        }
        if (cur) segs << weekSeg(cur, from, end, start, end)
        out << [iso: iso, segs: segs]
    }
    return out
}

@CompileStatic
Map weekSeg(Map t, long from, long to, long start, long end) {
    double span = (end - start) as double
    return [from: (from - start) * 1440.0d / span, to: (to - start) * 1440.0d / span, period: t.period, group: t.group,
            profile: t.profile, custom: t.custom, useOccupied: t.useOccupied == true]
}

@CompileStatic
Map valuesOf(Map v, Map ctx) {
    Map vars = (ctx.vars ?: [:]) as Map
    BigDecimal heat = v.heatVar ? numOrNull(vars.get(v.heatVar)) : numOrNull(v.heat)
    BigDecimal cool = v.coolVar ? numOrNull(vars.get(v.coolVar)) : numOrNull(v.cool)
    return [heat: heat, cool: cool, fan: v.fan ?: null, mode: v.mode ?: null]
}

@CompileStatic
Map profileValues(Map cfg, String name, Map ctx) {
    Map p = (cfg.profiles as List<Map>)?.find { Map x -> x.name == name }
    return p ? valuesOf(p, ctx) + [profile: name] : null
}

@CompileStatic
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
    boolean ticked = false
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
            ticked = row.useOccupied == true
        }
    } else if (cur) {
        base = cur.custom ? valuesOf(cur.custom as Map, ctx) + [profile: 'custom'] : profileValues(cfg, cur.profile as String, ctx)
        layer = 'schedule'
        ticked = cur.useOccupied == true
    }
    if (base == null) return [layer: 'none', transitionKey: key, overrideModeId: ov?.modeId as Long]
    boolean occupied = false
    if (layer == 'schedule' && ticked && ctx.occupied == true) {
        Map merged = occupiedMerge(base, profileValues(cfg, (cfg.occupancy as Map)?.profile as String, ctx))
        occupied = merged.occupied == true
        base = merged
    }
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
            eco: ecoOn, occupied: occupied, transitionKey: key, overrideModeId: ov?.modeId as Long]
}

// While occupied, a ticked period takes the occupied profile's setpoints where they are more comfortable:
// the higher heat, the lower cool; a blank side takes the other's. Fan and mode stay the period's.
@CompileStatic
Map occupiedMerge(Map base, Map occ) {
    if (occ == null) return base
    BigDecimal bh = base.heat as BigDecimal, bc = base.cool as BigDecimal
    BigDecimal oh = occ.heat as BigDecimal, oc = occ.cool as BigDecimal
    BigDecimal heat = bh == null ? oh : (oh == null ? bh : bh.max(oh))
    BigDecimal cool = bc == null ? oc : (oc == null ? bc : bc.min(oc))
    boolean raised = (heat != null && (bh == null || heat > bh)) || (cool != null && (bc == null || cool < bc))
    Map out = new LinkedHashMap(base)
    out.heat = heat
    out.cool = cool
    out.occupied = raised
    if (raised) out.profile = occ.profile
    return out
}

// What one occupancy sensor reading means. Same as the committed state: drop any pending commit.
// Otherwise commit once the reading is older than its delay (`since` is when that was), or wait until `at`.
// Before the first commit a vacant reading commits at once.
@CompileStatic
Map occupancyStep(String committed, String reading, Long changedAt, long now, long occupiedAfterMs, long vacantAfterMs) {
    String want = reading == 'active' ? 'occupied' : 'vacant'
    if (want == committed) return [cancel: true]
    if (committed == null && want == 'vacant') return [commit: want, since: now]
    long due = (changedAt != null ? (long) changedAt : now) + (want == 'occupied' ? occupiedAfterMs : vacantAfterMs)
    return due <= now ? [commit: want, since: due] : [at: due]
}

@CompileStatic
boolean holdExpired(Map hold, Map target, long now) {
    if (!hold || hold.end == 'indefinite') return false
    if (hold.end == 'at') return now >= (hold.until as long)
    return target.transitionKey != hold.transitionKey || (target.overrideModeId as Long) != (hold.overrideModeId as Long)
}

@CompileStatic
boolean differs(BigDecimal want, Object cur) {
    BigDecimal c = numOrNull(cur)
    return c == null || (want - c).abs() >= 0.05G
}

@CompileStatic
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
        if (target.fan && mode != 'off' && (force || target.fan != t.fan)) out << [id: t.id, command: 'setThermostatFanMode', value: target.fan]
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

@CompileStatic
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

@CompileStatic
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

@CompileStatic
Map defaultConfig() {
    return [v: 1,
        profiles: [[name: 'Home', heat: 21.0, cool: 24.0], [name: 'Sleep', heat: 18.0, cool: 26.0], [name: 'Away', heat: 16.0, cool: 29.0]],
        schedules: [[name: 'Normal', type: 'time', groups: [[name: 'Every day', days: [1, 2, 3, 4, 5, 6, 7], periods: [
            [name: 'Wake', start: [kind: 'time', at: '06:30'], profile: 'Home', custom: null],
            [name: 'Night', start: [kind: 'time', at: '22:00'], profile: 'Sleep', custom: null]]]]]],
        active: 'Normal', overrides: []]
}

// A new program maps the hub mode named Away to the Away profile, as the built-in Away row does.
@CompileStatic
Map seedOverrides(Map cfg, List<Map> modes) {
    Map away = modes?.find { Map m -> (m.name as String)?.trim()?.equalsIgnoreCase('Away') }
    boolean hasProfile = (cfg.profiles as List<Map>)?.any { Map p -> p.name == 'Away' }
    if (away == null || !hasProfile || cfg.overrides) return cfg
    return cfg + [overrides: [[modeId: away.id as Long, profile: 'Away']]]
}

// A write check is due 30 s after the latest write; past 60 s its job was lost.
@CompileStatic
boolean verifyOverdue(Map verify, long now) {
    return verify != null && now - ((verify.last ?: verify.at ?: 0L) as long) > 60000L
}

// Adds a batch to the one waiting for its check: a write replaces an earlier one with the
// same thermostat and command, the others are kept.
List<Map> mergeWrites(List<Map> pending, List<Map> writes) {
    List<Map> out = (pending ?: []).findAll { Map p -> !(writes ?: []).any { Map w -> w.id?.toString() == p.id?.toString() && w.command == p.command } }
    out.addAll(writes ?: [])
    return out
}

// Rewrites references to hub variable `from` as `to`. Returns true when anything changed.
@CompileStatic
boolean renameVarRefs(Map cfg, String from, String to) {
    boolean changed = false
    (cfg?.profiles as List<Map>)?.each { Map p ->
        if (p.heatVar == from) { p.heatVar = to; changed = true }
        if (p.coolVar == from) { p.coolVar = to; changed = true }
    }
    (cfg?.schedules as List<Map>)?.each { Map s ->
        (s.groups as List<Map>)?.each { Map g ->
            (g.periods as List<Map>)?.each { Map p ->
                Map st = p.start as Map
                if (st?.kind == 'var' && st.name == from) { st.name = to; changed = true }
            }
        }
    }
    return changed
}

// Setpoints given to a hold must be in the hub scale's range: C 0 to 40, F 32 to 104.
@CompileStatic
String setpointRangeError(Object heat, Object cool, String scale) {
    BigDecimal lo = scale == 'F' ? 32.0G : 0.0G, hi = scale == 'F' ? 104.0G : 40.0G
    for (String f in ['heating', 'cooling']) {
        BigDecimal n = numOrNull(f == 'heating' ? heat : cool)
        if (n != null && (n < lo || n > hi)) return "${f} ${n} is out of range (${lo} to ${hi} °${scale})".toString()
    }
    return null
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
                    if (p.useOccupied != null && !(p.useOccupied instanceof Boolean)) e << "${where}: useOccupied must be true or false".toString()
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
                if (row.useOccupied != null && !(row.useOccupied instanceof Boolean)) e << "Schedule ${s.name}: useOccupied must be true or false".toString()
            }
        } else e << "Schedule ${s.name}: unknown type ${s.type}".toString()
    }
    (doc.overrides as List<Map>)?.each { Map o ->
        if (!(env.modeIds as List).collect { it as Long }.contains(o.modeId as Long)) e << "Override: unknown mode ${o.modeId}".toString()
        profileRef(o.profile as String, null, 'Override')
    }
    String occ = (doc.occupancy as Map)?.profile as String
    if (occ && !names.contains(occ)) e << "Occupied profile: unknown profile ${occ}".toString()
    return e
}

// `now`, when given, rejects an ISO time that is not in the future.
@CompileStatic
Map parseEnd(Object raw, Long now = null) {
    String s = raw == null ? '' : raw.toString().trim()
    if (s == '' || s == 'next') return [end: 'next']
    if (s == 'indefinite') return [end: 'indefinite']
    if (s ==~ /\d{1,6}/) return (s as int) > 0 ? [end: 'minutes', minutes: s as int] : [error: 'minutes must be positive']
    if (s ==~ /\d+/) return [error: 'minutes must be at most 999999']
    // The whole string must be an ISO time (SimpleDateFormat.parse stops at trailing text; the hub sandbox blocks ParsePosition).
    String fmt = s ==~ /\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(Z|[+-]\d{2}:\d{2})/ ? "yyyy-MM-dd'T'HH:mm:ssXXX"
               : (s ==~ /\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(Z|[+-]\d{2}:\d{2})/ ? "yyyy-MM-dd'T'HH:mmXXX" : null)
    if (fmt) {
        Date d = null
        try {
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat(fmt)
            f.setLenient(false)
            d = f.parse(s)
        } catch (Exception ignored) { }
        if (d != null) {
            if (now != null && d.getTime() <= now) return [error: "end time ${s} is in the past".toString()]
            return [end: 'at', until: d.getTime()]
        }
    }
    return [error: "end must be next, indefinite, minutes or an ISO time: ${s}".toString()]
}

@CompileStatic
Map parseCommand(Map req, Long now = null) {
    String name = req?.command as String
    Closure err = { String m -> [ok: false, error: m] }
    if (name in ['on', 'off', 'resume', 'applyNow', 'advance', 'refresh']) return [ok: true, name: name, args: [:]]
    if (name == 'holdProfile') {
        if (!req.profile?.toString()?.trim()) return err('profile is required')
        Map end = parseEnd(req.end, now)
        return end.error ? err(end.error as String) : [ok: true, name: name, args: [profile: req.profile.toString().trim()] + end]
    }
    if (name == 'holdSetpoints') {
        BigDecimal h = numOrNull(req.heating), c = numOrNull(req.cooling)
        if (h == null && c == null) return err('heating or cooling is required')
        Map end = parseEnd(req.end, now)
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

@CompileStatic
String applyOutcome(List<Map> batch, List<String> confirmedIds) {
    List<String> ids = batch.collect { it.id as String }.unique()
    int ok = ids.count { confirmedIds.contains(it) } as int
    return ok == ids.size() ? 'ok' : (ok == 0 ? 'failed' : 'partial')
}

// ── Configuration PUT ──

@CompileStatic
Map parseJsonLike(Map m) { return new groovy.json.JsonSlurper().parseText(groovy.json.JsonOutput.toJson(m)) as Map }

// Any change to the document or to an option setting changes the revision. Hashed after a JSON
// round trip, so a map read back from state and the map it was saved from agree.
@CompileStatic
int revisionOf(Map cfg) { return groovy.json.JsonOutput.toJson(parseJsonLike(cfg)).hashCode() }

@CompileStatic
List<String> putShapeErrors(Map c) {
    List<String> e = []
    if (!(c.profiles instanceof List)) e << 'config.profiles must be a list'
    if (!(c.schedules instanceof List)) e << 'config.schedules must be a list'
    if (!(c.active instanceof String)) e << 'config.active must be text'
    if (c.overrides != null && !(c.overrides instanceof List)) e << 'config.overrides must be a list'
    Object occ = c.occupancy
    Object occProfile = occ instanceof Map ? ((Map) occ).get('profile') : null
    if (occ != null && !(occ instanceof Map && (occProfile == null || occProfile instanceof String)))
        e << 'config.occupancy must be an object with a profile name'
    return e
}

@CompileStatic
Map putDocument(Map c) {
    Map d = [v: c.v ?: 1, profiles: c.profiles, schedules: c.schedules, active: c.active, overrides: c.overrides ?: []]
    Map occ = c.occupancy as Map
    Object name = occ?.get('profile')
    if (name) d.put('occupancy', [profile: name])
    return d
}

@CompileStatic
boolean validStart(Object s) {
    if (s == null) return true
    if (!(s instanceof Map)) return false
    Map m = (Map) s
    if (m.kind == 'time') return (m.at as String) ==~ /([01]\d|2[0-3]):[0-5]\d/
    if (m.kind in ['sunrise', 'sunset']) return m.offset == null || (m.offset.toString() ==~ /-?\d+/)
    return false
}

@CompileStatic
List<String> validateOptions(Map c, Map env) {
    List<String> e = []
    Map eco = c.eco as Map, o = c.options as Map, r = c.restrictions as Map
    if (eco?.containsKey('offset')) {
        BigDecimal n = numOrNull(eco.offset)
        if (n == null || n < -10 || n > 10) e << 'eco.offset must be a number from -10 to 10'
    }
    if (o?.containsKey('separation')) {
        BigDecimal n = numOrNull(o.separation)
        if (n == null || n < 0 || n > 10) e << 'options.separation must be a number from 0 to 10'
    }
    if (o?.containsKey('whilePaused') && !(o.whilePaused in ['leave', 'off'])) e << 'options.whilePaused must be leave or off'
    if (o?.containsKey('onResume') && !(o.onResume in ['restore', 'leaveOff'])) e << 'options.onResume must be restore or leaveOff'
    if (r?.days != null && !(r.days instanceof List && (r.days as List).every { it.toString() ==~ /[1-7]/ })) e << 'restrictions.days must hold days 1 to 7'
    (r?.modeIds as List)?.each { Object m ->
        if (!(env.modeIds as List).collect { it as Long }.contains(m as Long)) e << "restrictions.modeIds: unknown mode ${m}".toString()
    }
    ['from', 'to'].each { String k -> if (r?.containsKey(k) && !validStart(r.get(k))) e << "restrictions.${k} is not a valid start".toString() }
    return e
}

// Settings writes for the option groups present; a null value removes the setting.
@CompileStatic
List<Map> optionSettings(Map c) {
    List<Map> w = []
    Map eco = c.eco as Map, o = c.options as Map, r = c.restrictions as Map
    if (eco?.containsKey('offset')) w << [name: 'ecoOffset', type: 'decimal', value: eco.offset]
    if (eco?.containsKey('onOverrides')) w << [name: 'ecoOnOverrides', type: 'bool', value: eco.onOverrides == true]
    Map optMap = [separation: ['separation', 'decimal'], verify: ['verifyWrites', 'bool'], applyOnStart: ['applyOnStart', 'bool'],
                  whilePaused: ['whilePaused', 'enum'], onResume: ['onResume', 'enum']]
    optMap.each { String k, List nt -> if (o?.containsKey(k)) w << [name: nt[0], type: nt[1], value: nt[1] == 'bool' ? o.get(k) == true : o.get(k)] }
    if (r?.containsKey('days')) w << [name: 'restrictDays', type: 'enum', value: (r.days as List)?.collect { it.toString() } ?: null]
    if (r?.containsKey('modeIds')) w << [name: 'restrictModes', type: 'mode', value: (r.modeIds as List)?.collect { it.toString() } ?: null]
    [from: 'restrictFrom', to: 'restrictTo'].each { String k, String n ->
        if (!r?.containsKey(k)) return
        Map s = r.get(k) as Map
        w << [name: n, type: 'enum', value: s ? s.kind : 'any']
        w << [name: "${n}At".toString(), type: 'time', value: s?.kind == 'time' ? s.at : null]
        w << [name: "${n}Offset".toString(), type: 'number', value: s?.kind in ['sunrise', 'sunset'] ? ((s.offset ?: 0) as int) : null]
    }
    return w
}

// ── Built-in Thermostat Scheduler 2.0 import ──
// Key by key layout of the built-in's settings and state: BUILTIN-STORAGE.md.
// Only appState.timeSort (periods) and appState.dayGroups (groups) are live; the scheduler keeps
// settings and state of deleted periods and groups, which are ignored here.

@CompileStatic
String builtinText(Object v) {
    if (v == null || (v instanceof Collection && ((Collection) v).isEmpty()) || (v instanceof Map && ((Map) v).isEmpty())) return null
    return v.toString().trim() ?: null
}

@CompileStatic
Map builtinCell(Map src, String suffix) {
    Map v = [:]
    String hv = builtinText(src.get("heat${suffix}V".toString())), cv = builtinText(src.get("cool${suffix}V".toString()))
    BigDecimal heat = numOrNull(src.get("heat${suffix}".toString())), cool = numOrNull(src.get("cool${suffix}".toString()))
    if (hv) v.heatVar = hv
    else if (heat != null) v.heat = heat
    if (cv) v.coolVar = cv
    else if (cool != null) v.cool = cool
    String fan = builtinText(src.get("fan${suffix}".toString())), mode = builtinText(src.get("mod${suffix}".toString()))
    if (fan) v.fan = fan.toLowerCase()
    if (mode) v.mode = mode.toLowerCase()
    return v
}

@CompileStatic
String builtinHhmm(Object v) {
    String s = builtinText(v)
    if (!s) return null
    java.util.regex.Matcher m = s =~ /^(\d{1,2}):(\d{2})$/
    if (m.find()) return "${m.group(1).padLeft(2, '0')}:${m.group(2)}".toString()
    m = s =~ /T(\d{2}):(\d{2})/
    return m.find() ? "${m.group(1)}:${m.group(2)}".toString() : null
}

// null: no start; [bad: kind]: a start this importer does not know.
@CompileStatic
Map builtinStart(Map s, String p, String g) {
    String sfx = "${p}.${g}".toString()
    String kind = builtinText(s.get("time${sfx}".toString()))
    if (!kind) return null
    if (kind == 'A specific time') {
        String at = builtinHhmm(s.get("atTime${sfx}".toString()))
        return at ? [kind: 'time', at: at] : null
    }
    if (kind == 'Sunrise' || kind == 'Sunset') {
        String off = builtinText(s.get("at${kind}Offset${sfx}".toString()))
        return [kind: kind.toLowerCase(), offset: off && off ==~ /-?\d+/ ? (off as int) : 0]
    }
    if (kind == 'Variable time') {
        String name = builtinText(s.get("timeX${sfx}".toString()))
        return name ? [kind: 'var', name: name] : null
    }
    return [bad: kind]
}

// One end of the restriction window (`end` is "start" or "end"). null: unset; [bad: ...]: unreadable.
// The built-in names the offset inputs "<end><Sunrise|Sunset>Offsetnull".
@CompileStatic
Map builtinWindowEnd(Map s, String end) {
    String kind = builtinText(s.get("${end}ingX".toString()))
    if (kind == 'Sunrise' || kind == 'Sunset') {
        String off = builtinText(s.get("${end}${kind}Offsetnull".toString())) ?: builtinText(s.get("${end}${kind}Offset".toString()))
        return [kind: kind.toLowerCase(), offset: off && off ==~ /-?\d+/ ? (off as int) : 0]
    }
    String at = builtinHhmm(s.get("${end}ing".toString()))
    if (kind == null || kind == 'A specific time') return at ? [kind: 'time', at: at] : null
    return [bad: kind]
}

Map convertBuiltin(Map s, Map st, List<Map> modes) {
    List<String> warn = [], errs = []
    // Only time-period schedulers use the stored Away values; a Hub Modes scheduler treats Away as an ordinary mode.
    boolean byModes = s.schedTypeL == 'Hub Modes'
    Map away = byModes ? [:] : builtinCell(st, 'Away')
    // Identical values share one profile, named after every period or mode that uses it.
    // Periods and rows hold an index into `sets` until the names are known.
    List<Map> sets = []
    Closure profileFor = { Map values, String user ->
        int i = sets.findIndexOf { Map x -> x.values == values }
        if (i < 0) { sets << [values: values, users: []]; i = sets.size() - 1 }
        if (!(sets[i].users as List).contains(user)) (sets[i].users as List) << user
        return i
    }
    Map sched
    if (byModes) {
        List<Map> rows = []
        ((st.modeTable ?: [:]) as Map).each { Object k, Object v ->
            Map m = v as Map
            if (m.used == false) return
            Long id = k.toString().isLong() ? (k.toString() as Long) : null
            String modeName = modes.find { (it.id as Long) == id }?.name
            if (modeName == null) { warn << "Hub mode ${k} no longer exists; its row was not imported".toString(); return }
            int prof = profileFor(builtinCell(m, ''), modeName) as int
            Map row = rows.find { it.profile == prof }
            if (row) (row.modes as List) << id
            else rows << [modes: [id], profile: prof]
        }
        sched = [name: 'Hub modes', type: 'mode', rows: rows]
    } else {
        List<String> periods = ((st.timeSort ?: []) as List).collect { it as String }
        Map dg = (st.dayGroups ?: [:]) as Map
        List<Map> groups = []
        dg.keySet().collect { it.toString() }.sort { it as int }.each { String g ->
            List<Integer> days = []
            (dg.get(g) as List).eachWithIndex { Object f, int i -> if (f == true) days << (i + 1) }
            String gName = builtinText(((st.dayGroupsList ?: [:]) as Map).get(g)) ?: "Group ${g}".toString()
            List<Map> cells = []
            periods.each { String p ->
                Map vals = builtinCell(st, "${p}.${g}".toString())
                Map start = builtinStart(s, p, g)
                if (start == null && !vals) return
                cells << [p: p, vals: vals, start: start]
            }
            List<Map> ps = []
            cells.each { Map c ->
                Map start = c.start as Map
                if (start?.bad) { warn << "${gName}, ${c.p}: start \"${start.bad}\" was not imported".toString(); return }
                if (start == null) {
                    if (cells.size() > 1) { warn << "${gName}, ${c.p}: no start time; period not imported".toString(); return }
                    start = [kind: 'time', at: '00:00']
                    warn << "${gName}, ${c.p}: no start time; imported as starting at 00:00 every day".toString()
                }
                ps << [name: c.p, start: start, profile: profileFor(c.vals as Map, c.p as String), custom: null]
            }
            groups << [name: gName, days: days, periods: ps]
        }
        sched = [name: 'Imported', type: 'time', groups: groups]
    }
    List<String> names = away ? ['Away'] : []
    sets.each { Map x ->
        String base = (x.users as List).join(' / ')
        String n = base
        int i = 2
        while (names.contains(n)) n = "${base} ${i++}".toString()
        names << n
    }
    int first = away ? 1 : 0
    List<Map> profiles = (away ? [[name: 'Away'] + away] : []) + sets.withIndex().collect { Map x, int i -> [name: names[first + i]] + (x.values as Map) }
    if (sched.type == 'mode') (sched.rows as List<Map>).each { Map r -> r.profile = names[first + (r.profile as int)] }
    else (sched.groups as List<Map>).each { Map g -> (g.periods as List<Map>).each { Map p -> p.profile = names[first + (p.profile as int)] } }
    if (sets.isEmpty()) warn << 'The scheduler has nothing scheduled; the program has an empty schedule'

    List<Map> overrides = []
    if (away) {
        Map awayMode = modes.find { (it.name as String)?.trim()?.equalsIgnoreCase('Away') }
        if (awayMode) overrides << [modeId: awayMode.id as Long, profile: 'Away']
        else warn << 'The hub has no Away mode; the Away profile was imported without an override'
    }
    if (!byModes && st.useEcoModeAway == true) warn << 'Away used the EcoMode offset; the program uses the Away profile instead'
    if (st.manHold == true) warn << 'The scheduler was on hold; the program starts without a hold'
    Map restrictions = [:]
    List<String> dayNames = ['Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday', 'Sunday']
    if (s.days instanceof List && s.days) restrictions.days = (s.days as List).collect { dayNames.indexOf(it as String) + 1 }.findAll { it > 0 }
    if (s.modesR instanceof List && s.modesR) {
        List<Long> ids = []
        (s.modesR as List).each { Object m ->
            Long id = m.toString().isLong() ? (m.toString() as Long) : null
            if (modes.any { (it.id as Long) == id }) ids << id
            else warn << "Hub mode ${m} no longer exists; it was left out of the mode restriction".toString()
        }
        if (ids) restrictions.modeIds = ids
    }
    Map from = builtinWindowEnd(s, 'start'), to = builtinWindowEnd(s, 'end')
    if (from?.bad || to?.bad) warn << 'The time restriction could not be read and was not imported'
    else if (from && to) { restrictions.from = from; restrictions.to = to }
    else if (from || to) warn << 'The time restriction has only one end set and was not imported'

    Map eco = [onOverrides: false]   // the built-in never applies EcoMode to Away
    BigDecimal off = numOrNull(st.ecoSet)
    if (off != null && off.abs() <= 10) eco.offset = off
    else if (off != null) warn << "EcoMode offset ${off} is outside -10 to 10 and was not imported".toString()
    Map opts = [eco: eco, options: [applyOnStart: !(s.setOnStart in ['false', false]),
                                    whilePaused: s.turnThermOff in ['true', true] ? 'off' : 'leave', onResume: 'restore']]
    BigDecimal sep = numOrNull(s.reqOffset)
    if (sep != null && sep >= 0 && sep <= 10) (opts.options as Map).separation = sep
    else if (sep != null) warn << "Required separation ${sep} is outside 0 to 10 and was not imported".toString()
    if (restrictions) opts.restrictions = restrictions
    List<String> therms = ((s.therm instanceof Map ? s.therm : [:]) as Map).keySet().collect { it.toString() }
    if (!therms) errs << 'The scheduler has no thermostat'
    List<String> sw = ((s.disabled instanceof Map ? s.disabled : [:]) as Map).keySet().collect { it.toString() }
    return [doc: [v: 1, profiles: profiles, schedules: [sched], active: sched.name, overrides: overrides],
            options: opts, thermostats: therms, pauseSwitch: sw ? sw[0] : null,
            pauseWhen: s.disabledOff in ['true', true] ? 'off' : 'on', eco: st.inEcoMode == true,
            warnings: warn, errors: errs]
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

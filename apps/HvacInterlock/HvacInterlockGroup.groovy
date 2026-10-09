// Copyright (c) 2026 PJ
// SPDX-License-Identifier: MIT

/*
 HVAC Interlock Group

 One group of equipment that shares a season policy and the same openings. A permit
 group drives a status switch that its schedulers pause on; a direct group sets the
 thermostat mode itself. Publishes its state on a "<group> status" device.
*/

import com.hubitat.app.ChildDeviceWrapper
import com.hubitat.hub.domain.State
import groovy.transform.CompileStatic
import groovy.transform.Field

@Field static final String CODE_VERSION = "0.1.4"

definition(
    name: "HVAC Interlock Group",
    namespace: "iamtrep",
    author: "pj",
    description: "One HVAC Interlock group: season policy and openings for a set of equipment",
    category: "Convenience",
    parent: "iamtrep:HVAC Interlock",
    singleThreaded: true,
    importUrl: "https://raw.githubusercontent.com/iamtrep/hubitat/main/apps/HvacInterlock/HvacInterlockGroup.groovy",
    iconUrl: "", iconX2Url: ""
)

@Field static final Map STYLE_OPTS = [permit: "Permit: schedulers own the thermostats; the group drives a switch they pause on",
                                      direct: "Direct: the group sets the thermostat mode"]
@Field static final List<String> MODE_OPTS = ["off", "heat", "cool", "auto"]
@Field static final Map RESPONSE_OPTS = [block: "Block: turn the equipment off", warn: "Warn: raise the alert only"]

preferences {
    page(name: "mainPage")
}

// ── UI ────────────────────────────────────────────────────────────────

String esc(Object s) { s == null ? '' : s.toString().replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;').replace("'", '&#39;') }

String styleOf() { return (settings.style ?: 'permit') as String }

Map mainPage() {
    dynamicPage(name: "mainPage", install: true, uninstall: true) {
        section {
            label title: "Group name", required: true
            if (app.getInstallationState() == "COMPLETE") paragraph statusHtml()
        }
        section("Equipment") {
            input "style", "enum", title: "Control style", options: STYLE_OPTS, defaultValue: "permit", required: true, submitOnChange: true
            if (styleOf() == "direct") input "thermostats", "capability.thermostat", title: "Thermostats", multiple: true, required: true
        }
        section("Season policy") {
            seasonList().each { String k ->
                if (styleOf() == "direct") input "mode${k.capitalize()}".toString(), "enum", title: "Mode in ${k}", options: MODE_OPTS, defaultValue: "off", width: 3
                else input "allow${k.capitalize()}".toString(), "bool", title: "Allowed in ${k}", defaultValue: k != "summer", width: 3
            }
        }
        section("Openings") {
            input "openings", "capability.contactSensor", title: "Contact sensors", multiple: true, required: false
            input "response", "enum", title: "When something is open", options: RESPONSE_OPTS, defaultValue: "block", required: true
            input "openDelay", "number", title: "Open for at least (minutes)", defaultValue: 10, range: "0..240"
            paragraph "The alert is raised only while the group wants to run, and clears once everything has been closed for 5 minutes."
        }
        if (styleOf() == "permit") section("Scheduler setup") { paragraph permitInstructions() }
        section("Logging") {
            input "txtEnable", "bool", title: "Enable info logging", defaultValue: true
            input "debugEnable", "bool", title: "Enable debug logging (turns off after 30 minutes)", defaultValue: false, submitOnChange: true
            if (debugEnable) input "traceEnable", "bool", title: "Enable trace logging (turns off with debug)", defaultValue: false
        }
        if (debugEnable) {
            section("Testing") {
                input "testCloseDelay", "number", title: "Close delay for tests (seconds; blank or none = 5 minutes)", required: false
                input "btnRestart", "button", title: "Run the hub restart handler"
            }
        }
    }
}

String permitInstructions() {
    String dev = esc(statusDevice()?.displayName ?: "${app.getLabel()} status")
    return "Set this once on each scheduler in the group.<br>" +
           "<b>Thermostat Scheduler+ program:</b> add ${dev} to <i>Pause while any of these is off</i>, " +
           "<i>While paused</i> = Turn thermostats off, <i>When the pause ends</i> = Restore the thermostat mode and apply the schedule.<br>" +
           "<b>Built-in Thermostat Scheduler:</b> <i>Disable when ${dev} is off</i> and <i>Turn thermostats off when restricted</i>. " +
           "The built-in restores the mode when the restriction lifts but keeps the old setpoints until its next period."
}

String statusHtml() {
    List<String> rows = []
    rows << "Season: ${esc(state.season ?: 'not received yet')}".toString()
    rows << "State: ${esc(state.effective ?: 'not evaluated yet')}".toString()
    if (state.raised) rows << "Openings alert raised: ${esc(openList(contactList()))}".toString()
    return rows.join('<br>')
}

// ── Lifecycle ─────────────────────────────────────────────────────────

void installed() { checkVersion(false); initialize() }

void updated() { checkVersion(false); unsubscribe(); unschedule(); initialize() }

void uninstalled() {
    if (getChildDevice(dni())) deleteChildDevice(dni())
}

void initialize() {
    statusDevice()
    rebuildOpenings()
    if (openings) subscribe(openings, "contact", "contactHandler")
    subscribe(location, "systemStart", "startHandler")
    if (debugEnable || traceEnable) runIn(1800, "logsOff")
    String s = parent?.currentSeason()
    if (s) state.season = s
    evaluateGroup("initialize")
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

// ── Device ────────────────────────────────────────────────────────────

String dni() { "hvacilk-${app.id}" }

// A child app has no label yet when installed() first runs; the device is created on
// the save that sets it, and renamed when the group is.
ChildDeviceWrapper statusDevice() {
    String want = statusLabel(app.getLabel())
    ChildDeviceWrapper d = getChildDevice(dni())
    if (!d && want) {
        d = addChildDevice("iamtrep", "HVAC Interlock Group Status", dni(), [name: want, label: want, isComponent: true])
        logCfg "created ${d.displayName}"
    } else if (d && want && d.getLabel() != want) {
        logCfg "renamed ${d.getLabel()} to ${want}"
        d.setLabel(want)
    }
    return d
}

// ── Openings ──────────────────────────────────────────────────────────

// Rebuilds the open/closed history from the devices: an opening already open counts
// from the time its open state was recorded.
void rebuildOpenings() {
    Map since = [:]
    (openings ?: []).each { dev ->
        State st = dev.currentState("contact")
        if (st?.value == "open") since[dev.id as String] = (st.date as Date)?.getTime() ?: now()
    }
    state.openSince = since
    if (since) state.remove('allClosedSince')
    else if (state.allClosedSince == null) state.allClosedSince = now()
}

List<Map> contactList() {
    Map since = (state.openSince ?: [:]) as Map
    return (openings ?: []).collect { dev ->
        String id = dev.id as String
        [id: id, label: dev.displayName as String, open: since.containsKey(id), since: since[id] as Long]
    }
}

void contactHandler(evt) {
    checkVersion()
    String id = evt.device.id as String
    Map since = (state.openSince ?: [:]) as Map
    if (evt.value == "open") {
        if (!since.containsKey(id)) since[id] = now()
        state.remove('allClosedSince')
    } else {
        since.remove(id)
        if (since.isEmpty()) state.allClosedSince = now()
    }
    state.openSince = since
    logEvt "${evt.device.displayName} ${evt.value}"
    evaluateGroup("contact")
}

// ── Evaluation ────────────────────────────────────────────────────────

void seasonChanged(String season) {
    checkVersion()
    state.season = season
    evaluateGroup("season ${season}")
}

void timerHandler() { checkVersion(); evaluateGroup("timer") }

void startHandler(evt) { checkVersion(); rebuildOpenings(); evaluateGroup("restart") }

void evaluateGroup(String why) {
    String style = styleOf()
    String season = state.season as String
    String wanted = wantedFor(style, policyFrom(style, settings), season)
    if (wanted == null) { logWarn "no season yet; set the Season device on the HVAC Interlock parent"; return }
    List<Map> contacts = contactList()
    boolean wasRaised = state.raised == true
    Map a = alertStep([raised: wasRaised, wanted: wanted, openSince: earliestOpen(contacts),
                       allClosedSince: state.allClosedSince, now: now(),
                       openDelayMs: openDelayMs(settings.openDelay),
                       closeDelayMs: closeDelayMs(debugEnable == true, settings.testCloseDelay)])
    boolean raised = a.raised as boolean
    state.raised = raised
    String open = raised ? openList(contacts) : ''
    if (raised && !wasRaised) { logInfo "openings alert: ${open} open"; parent?.notifyAlert(alertText(app.getLabel(), open)) }
    if (!raised && wasRaised) { logInfo "openings alert cleared"; parent?.notifyAlert(clearText(app.getLabel())) }
    if (a.nextCheck != null) runIn(Math.max(1L, ((a.nextCheck as long) - now()).intdiv(1000L) + 1L), "timerHandler")
    else unschedule("timerHandler")
    Map eff = effective(wanted, raised, (settings.response ?: 'block') as String)
    statusDevice()?.updateStatus([switch: eff.state == 'off' ? 'off' : 'on', contact: raised ? 'open' : 'closed',
                                 blockReason: eff.blockReason, openContacts: open ?: 'none'])
    if (style == 'direct' && (eff.state != state.effective || why == 'restart')) applyModes(eff.state as String)
    state.effective = eff.state
    logDebug "${why}: season ${season}, wanted ${wanted}, effective ${eff.state}, alert ${raised}"
}

void applyModes(String target) {
    List<Map> ts = (thermostats ?: []).collect { [id: it.id as String, mode: it.currentValue("thermostatMode") as String] }
    List<String> ids = modeWrites(ts, target)
    (thermostats ?: []).findAll { ids.contains(it.id as String) }.each { dev ->
        if (!supportsMode(dev.currentValue("supportedThermostatModes"), target)) {
            logWarn "${dev.displayName} does not support mode ${target}; left as it is"
            return
        }
        dev.setThermostatMode(target)
        logCmd "${dev.displayName}: mode ${target}"
    }
}


void appButtonHandler(String btn) {
    checkVersion()
    if (btn == "btnRestart") startHandler(null)
}

// ── Core (pure) ───────────────────────────────────────────────────────
// Self-contained: arguments in, values out. No settings, state, devices or logs.
// tests/test_core.groovy parses and runs this block off-hub.

@CompileStatic
List<String> seasonList() { return ['winter', 'spring', 'summer', 'fall'] }

@CompileStatic
BigDecimal numOrNull(Object o) {
    if (o instanceof Number) return new BigDecimal(o.toString())
    if (o instanceof String && ((String) o).trim().isBigDecimal()) return new BigDecimal(((String) o).trim())
    return null
}

// Per-season policy from the settings map: booleans for permit, modes for direct.
@CompileStatic
Map policyFrom(String style, Map s) {
    Map out = [:]
    Map dflt = [winter: true, spring: true, summer: false, fall: true]
    seasonList().each { String k ->
        if (style == 'permit') {
            Object v = s["allow${k.capitalize()}".toString()]
            out[k] = v == null ? dflt[k] : (v == true || v == 'true')
        } else {
            out[k] = (s["mode${k.capitalize()}".toString()] ?: 'off') as String
        }
    }
    return out
}

// What the group wants this season: 'on'/'off' for permit, a mode or 'off' for direct.
// Null when the season is unknown: the caller then changes nothing.
@CompileStatic
String wantedFor(String style, Map policy, String season) {
    if (!seasonList().contains(season)) return null
    Object p = policy?.get(season)
    if (style == 'permit') return (p == true || p == 'true') ? 'on' : 'off'
    return ['heat', 'cool', 'auto'].contains(p) ? (p as String) : 'off'
}

@CompileStatic
Map effective(String wanted, boolean raised, String response) {
    if (wanted == 'off') return [state: 'off', blockReason: 'season']
    if (raised && response != 'warn') return [state: 'off', blockReason: 'openings']
    return [state: wanted, blockReason: 'none']
}

// Raise when an opening has been open for the open delay while the group wants to run;
// clear when everything has been closed for the close delay, or when the group no longer
// wants to run. nextCheck is when the answer can next change without a new event.
@CompileStatic
Map alertStep(Map a) {
    boolean raised = a.raised == true
    long now = a.now as long
    Long openSince = a.openSince as Long
    Long closedSince = a.allClosedSince as Long
    if (a.wanted == 'off') return [raised: false, nextCheck: null]
    if (!raised) {
        if (openSince == null) return [raised: false, nextCheck: null]
        long due = openSince + (a.openDelayMs as long)
        return now >= due ? [raised: true, nextCheck: null] : [raised: false, nextCheck: due]
    }
    if (closedSince == null) return [raised: true, nextCheck: null]
    long due = closedSince + (a.closeDelayMs as long)
    return now >= due ? [raised: false, nextCheck: null] : [raised: true, nextCheck: due]
}

@CompileStatic
Long earliestOpen(List<Map> contacts) {
    List<Long> t = contacts.findAll { it.open }.collect { it.since as Long }.findAll { it != null }
    return t ? t.min() : null
}

@CompileStatic
String openList(List<Map> contacts) { return contacts.findAll { it.open }.collect { it.label as String }.sort().join(', ') }

@CompileStatic
String alertText(String group, String open) { return "${group}: ${open} open".toString() }

@CompileStatic
String clearText(String group) { return "${group}: alert cleared".toString() }

@CompileStatic
List<String> modeWrites(List<Map> thermostats, String target) {
    return thermostats.findAll { it.mode != target }.collect { it.id as String }
}

// supportedThermostatModes is a JSON-like list string; missing or empty means unknown.
@CompileStatic
boolean supportsMode(Object supported, String mode) {
    if (supported == null) return true
    List<String> modes = supported.toString().replaceAll(/[\[\]"\s]/, '').split(',').findAll { it }.collect { it as String }
    return modes.isEmpty() || modes.contains(mode)
}

@CompileStatic
long openDelayMs(Object minutes) {
    BigDecimal n = numOrNull(minutes)
    return n == null ? 600000L : (n * 60000).longValue()
}

@CompileStatic
long closeDelayMs(boolean debug, Object testSeconds) {
    BigDecimal n = debug ? numOrNull(testSeconds) : null
    return n == null ? 300000L : (n * 1000).longValue()
}

// The status device's label; null until the group has a name (its first save).
@CompileStatic
String statusLabel(Object group) {
    String g = group == null ? '' : group.toString().trim()
    return g ? "${g} status".toString() : null
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

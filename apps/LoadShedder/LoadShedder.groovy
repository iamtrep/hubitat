// Copyright (c) 2026 PJ
// SPDX-License-Identifier: MIT

/*
 Load Shedder

 Keeps a group of switches off while any of a set of reasons holds (a switch on, a switch
 off, the hub in a chosen mode), and turns back on, once no reason holds, exactly the ones
 it turned off. One instance per group of loads.
*/

import com.hubitat.app.ChildDeviceWrapper
import com.hubitat.app.DeviceWrapper
import groovy.transform.CompileStatic
import groovy.transform.Field

@Field static final String CODE_VERSION = "0.1.0"

// singleThreaded: state.mem (which loads this app turned off), state.attempts and
// state.unresponsive are read, changed and written back by reason events, mode events, the
// loads' own switch events (the echo of this app's commands), the verify timer, the restore
// timer, the periodic check and hub start. Two of these together (a rule that sets the mode
// and a reason switch at once; a command echo during an evaluation) would lose a load from
// the owned list, and a lost owner means a load left off for good. Handlers are short, never
// block, and see a handful of devices, so the queue stays far below the 120 s gate.
definition(
    name: "Load Shedder",
    namespace: "iamtrep",
    author: "pj",
    description: "Keeps switches off while any of a set of reasons holds, then turns back on the ones it turned off",
    menu: "Automations", // new in platform 2.5.0
    category: "Convenience",
    singleThreaded: true,
    importUrl: "https://raw.githubusercontent.com/iamtrep/hubitat/main/apps/LoadShedder/LoadShedder.groovy",
    documentationLink: "https://github.com/iamtrep/hubitat/tree/main/apps/LoadShedder",
    iconUrl: "", iconX2Url: ""
)

@Field static final int MAX_ATTEMPTS = 2               // a command and one retry, then the load is unresponsive
@Field static final long REASSERT_GAP_MS = 300000L     // "turn it off again": at most once per load per 5 minutes
@Field static final long STARTUP_MS = 600000L          // a shed load reading on in the first 10 min of uptime came back with the power
@Field static final long DOWNTIME_GAP_MS = 660000L     // no evaluation for 11 min (the periodic check runs every 5): the hub was down

preferences {
    page(name: "mainPage")
}

// ── UI ────────────────────────────────────────────────────────────────

String esc(Object s) { s == null ? '' : s.toString().replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;').replace("'", '&#39;') }

Map mainPage() {
    List<String> errs = validateCfg(loadIds(), switchIds(shedWhenOn), switchIds(shedWhenOff), (shedModes ?: []) as List)
    dynamicPage(name: "mainPage", title: "", install: errs.isEmpty(), uninstall: true) {
        section {
            label title: "App name", required: false
            if (errs) paragraph "<div class='p-message p-message-error p-3 border-round'>${errs.collect { esc(it) }.join('<br>')}</div>"
            if (state.mem != null) paragraph rawHtml: true, statusHtml()
        }
        section("Loads") {
            input "loads", "capability.switch", title: "Switches to shed", multiple: true, required: true
        }
        section("Reasons: shed while any of these holds") {
            input "shedWhenOn", "capability.switch", title: "Shed while any of these is on", multiple: true, required: false
            input "shedWhenOff", "capability.switch", title: "Shed while any of these is off", multiple: true, required: false
            input "shedModes", "mode", title: "Shed while the mode is any of these", multiple: true, required: false
        }
        section("Behaviour") {
            input "manualOn", "enum", title: "When a shed switch is turned back on during a shed",
                  options: [respect: "Leave it on (the app no longer restores it)", enforce: "Turn it off again"],
                  defaultValue: "respect", required: true
            input "restoreDelay", "number", title: "Wait before restoring (minutes)", defaultValue: 0, range: "0..1440", required: true
            input "restoreStagger", "number", title: "Time between restoring two switches (seconds)", defaultValue: 0, range: "0..3600", required: true
            input "verifyTimeout", "number", title: "Time a switch has to confirm a command (seconds)", defaultValue: 30, range: "5..300", required: true
        }
        section("Notifications") {
            input "notifyDevices", "capability.notification", title: "Tell these devices when a switch does not obey, and when it obeys again",
                  multiple: true, required: false
        }
        section("Logging", hideable: true, hidden: true) {
            input "txtEnable", "bool", title: "Enable info logging", defaultValue: true
            input "debugEnable", "bool", title: "Enable debug logging (turns off after 30 minutes)", defaultValue: false, submitOnChange: true
            if (debugEnable) input "traceEnable", "bool", title: "Enable trace logging (turns off with debug)", defaultValue: false
        }
    }
}

String statusHtml() {
    Map m = (state.mem ?: [:]) as Map
    String phase = (m.phase ?: 'idle') as String
    List<String> reasons = (state.reasons ?: []) as List<String>
    StringBuilder h = new StringBuilder("<p><b>")
    h << (phase == 'shed' ? 'Shedding' : phase == 'restore' ? 'Restoring' : 'Not shedding') << "</b>"
    if (reasons) h << " · because: " << esc(reasons.join(', '))
    h << "</p><p>Held off by this app: " << esc(namesOf((m.owned ?: []) as List) ?: 'none') << "</p>"
    if (m.released) h << "<p>Turned back on by hand, left on: " << esc(namesOf(m.released as List)) << "</p>"
    Map un = (state.unresponsive ?: [:]) as Map
    if (un) h << "<p><b>Not obeying:</b> " << esc(un.values().collect { (it as Map).detail }.join(', ')) << "</p>"
    return h.toString()
}

// ── Lifecycle ─────────────────────────────────────────────────────────

void installed() { checkVersion(false); initialize() }

void updated() { checkVersion(false); unsubscribe(); unschedule(); initialize() }

void uninstalled() {
    // Nothing must stay off with no owner: put back what this app holds off.
    Map m = (state.mem ?: [:]) as Map
    List owned = (m.owned ?: []) as List
    (loads ?: []).each { DeviceWrapper d -> if (owned.contains(d.id.toString()) && d.currentValue("switch") != "on") { logCmd "${d.displayName} on (app removed)"; d.on() } }
    if (getChildDevice(dni())) deleteChildDevice(dni())
}

void initialize() {
    statusDevice()
    if (debugEnable || traceEnable) runIn(1800, "logsOff")
    if (loads) subscribe(loads, "switch", "loadHandler")
    [shedWhenOn, shedWhenOff].each { if (it) subscribe(it, "switch", "reasonHandler") }
    subscribe(location, "mode", "reasonHandler")
    subscribe(location, "systemStart", "systemStartHandler")
    schedule("${new Random().nextInt(60)} */5 * ? * *", "periodicCheck")
    List<String> errs = validateCfg(loadIds(), switchIds(shedWhenOn), switchIds(shedWhenOff), (shedModes ?: []) as List)
    if (errs) logWarn "settings incomplete: ${errs.join('; ')}"
    evaluateShed("settings saved")
    verifyLoads(true)
}

void systemStartHandler(evt) {
    checkVersion()
    // One-shot timers that fell due while the hub was down are gone: verify and restore re-derive here.
    evaluateShed("hub restart", true)
    verifyLoads(true)
}

void periodicCheck() {
    checkVersion()
    retryUnresponsive()
    evaluateShed("periodic check")
    verifyLoads(true)
}

void restoreTimer() {
    checkVersion()
    evaluateShed("restore timer")
}

// ── Configuration ─────────────────────────────────────────────────────

List<String> switchIds(Object devs) { ((devs ?: []) as List).collect { (it as DeviceWrapper).id.toString() } }

List<String> loadIds() { switchIds(loads) }

DeviceWrapper loadById(String id) { ((loads ?: []) as List<DeviceWrapper>).find { it.id.toString() == id } }

String namesOf(List ids) { ids.collect { String id -> loadById(id)?.displayName ?: "device ${id}" }.join(', ') }

Map currentCfg() {
    return [manualOn: (manualOn ?: 'respect') as String,
            delayMs: ((numOrNull(restoreDelay) ?: 0G) * 60000G).longValue(),
            staggerMs: ((numOrNull(restoreStagger) ?: 0G) * 1000G).longValue(),
            gapMs: REASSERT_GAP_MS]
}

long verifyMs() { ((numOrNull(verifyTimeout) ?: 30G) * 1000G).longValue() }

boolean earlyUptime() {
    Object up = location?.hub?.uptime
    return up != null && (up as long) * 1000L < STARTUP_MS
}

// ── Evaluation ────────────────────────────────────────────────────────

List<String> currentReasons() {
    List<Map> on = ((shedWhenOn ?: []) as List<DeviceWrapper>).collect { [name: it.displayName, value: it.currentValue("switch")] }
    List<Map> off = ((shedWhenOff ?: []) as List<DeviceWrapper>).collect { [name: it.displayName, value: it.currentValue("switch")] }
    return activeReasons(on, off, location.mode as String, location.currentMode?.id?.toString(), (shedModes ?: []) as List)
}

void reasonHandler(evt) {
    checkVersion()
    logEvt "${evt.displayName ?: evt.name} ${evt.name} ${evt.value}"
    evaluateShed("${evt.name} ${evt.value}")
}

void loadHandler(evt) {
    checkVersion()
    String id = evt.deviceId.toString()
    logEvt "${evt.displayName} ${evt.value}"
    Map att = (state.attempts ?: [:]) as Map
    Map a = att[id] as Map
    if (a != null && a.want == evt.value) {
        att.remove(id)
        state.attempts = att
        clearUnresponsive(id)
    }
    evaluateShed("${evt.displayName} ${evt.value}")
}

void evaluateShed(String why, boolean restart = false) {
    List<String> ids = loadIds()
    Map<String, String> values = [:]
    ((loads ?: []) as List<DeviceWrapper>).each { values[it.id.toString()] = it.currentValue("switch") as String }
    List<String> reasons = currentReasons()
    Map busy = busyCommands((state.attempts ?: [:]) as Map, (state.unresponsive ?: [:]) as Map)
    logDebug "evaluate (${why}): reasons ${reasons ?: 'none'}"
    // A held load reading on after the hub was down came back with the power, not by hand. The first
    // evaluation after the outage may be the periodic check or the load's own event, before systemStart.
    boolean back = restart || earlyUptime() || cameBack(state.lastEval as Long, now(), DOWNTIME_GAP_MS)
    state.lastEval = now()
    Map r = step((state.mem ?: [:]) as Map, !reasons.isEmpty(), values, ids, busy, currentCfg(), now(), back)
    Map mem = r.mem as Map
    state.mem = mem
    state.reasons = reasons
    dropStaleCommands(mem)
    (r.notes as List<String>).each { String n -> logInfo n.replaceAll(/\{(\w+)\}/) { all, String id -> loadById(id)?.displayName ?: "device ${id}" } }
    (r.actions as List<Map>).each { Map a -> sendLoad(a.id as String, a.cmd as String, 1) }
    Long wake = r.wakeAt as Long
    if (wake != null) runInMillis(Math.max(wake - now(), 500L), "restoreTimer")
    else unschedule("restoreTimer")
    publish()
}

// ── Commands and their checks ─────────────────────────────────────────

void sendLoad(String id, String cmd, int attempt) {
    DeviceWrapper d = loadById(id)
    if (d == null) return
    Map att = (state.attempts ?: [:]) as Map
    att[id] = [want: cmd, n: attempt, at: now()]
    state.attempts = att
    logCmd "${d.displayName} ${cmd}${attempt > 1 ? " (command ${attempt})" : ''}"
    if (cmd == "on") d.on() else d.off()
    runInMillis(verifyMs(), "verifyLoadsTimer")
}

void verifyLoadsTimer() {
    checkVersion()
    verifyLoads(false)
}

// onlyDue: check only commands older than the confirm time (hub start, periodic check), so a lost timer can't strand one.
void verifyLoads(boolean onlyDue) {
    Map att = (state.attempts ?: [:]) as Map
    if (!att) return
    long t = now()
    Map un = (state.unresponsive ?: [:]) as Map
    boolean changed = false
    new HashMap(att).each { k, v ->
        String id = k as String
        Map a = v as Map
        if (onlyDue && t - (a.at as long) < verifyMs()) return
        DeviceWrapper d = loadById(id)
        if (d == null) { att.remove(id); return }
        String cur = d.currentValue("switch") as String
        String out = verifyOutcome(a.want as String, cur, a.n as int, MAX_ATTEMPTS)
        if (out == 'ok') {
            att.remove(id)
            if (un.remove(id) != null) { changed = true; logInfo "${d.displayName} obeys again" }
        } else if (out == 'retry') {
            state.attempts = att
            sendLoad(id, a.want as String, (a.n as int) + 1)
            att = (state.attempts ?: [:]) as Map
        } else {
            att.remove(id)
            if (!un.containsKey(id)) {
                changed = true
                logWarn "${d.displayName} did not turn ${a.want} (reads ${cur ?: 'nothing'}); retried every 5 minutes"
            }
            un[id] = [want: a.want, detail: "${d.displayName} (commanded ${a.want}, reads ${cur ?: 'nothing'})".toString()]
        }
    }
    state.attempts = att
    state.unresponsive = un
    if (changed) faultChanged()
}

// A load that did not obey gets its command again at each periodic check, while it is still wanted.
void retryUnresponsive() {
    Map un = (state.unresponsive ?: [:]) as Map
    Map att = (state.attempts ?: [:]) as Map
    un.each { k, v -> if (!att.containsKey(k)) sendLoad(k as String, (v as Map).want as String, MAX_ATTEMPTS) }
}

void clearUnresponsive(String id) {
    Map un = (state.unresponsive ?: [:]) as Map
    if (un.remove(id) == null) return
    state.unresponsive = un
    logInfo "${loadById(id)?.displayName ?: id} obeys again"
    faultChanged()
}

// A command no longer wanted (the shed ended before an off landed, a new shed began before an on landed) is dropped.
void dropStaleCommands(Map mem) {
    boolean changed = false
    ['attempts', 'unresponsive'].each { String key ->
        Map m = (state[key] ?: [:]) as Map
        Map kept = m.findAll { k, v -> commandWanted((v as Map).want as String, k as String, mem) }
        if (kept.size() != m.size()) {
            state[key] = kept
            if (key == 'unresponsive') changed = true
        }
    }
    if (changed) faultChanged()
}

void faultChanged() {
    Map un = (state.unresponsive ?: [:]) as Map
    boolean fault = !un.isEmpty()
    if ((state.fault == true) == fault) { publish(); return }
    state.fault = fault
    String msg = fault ? "${app.getLabel()}: ${un.values().collect { (it as Map).detail }.join(', ')} does not obey".toString()
                       : "${app.getLabel()}: every switch obeys again".toString()
    (notifyDevices ?: []).each { it.deviceNotification(msg) }
    publish()
}

// ── Status device ─────────────────────────────────────────────────────

String dni() { "loadshedder-${app.id}" }

String deviceLabel() { "${app.getLabel()} status" }

ChildDeviceWrapper statusDevice() {
    ChildDeviceWrapper d = getChildDevice(dni())
    if (!d) {
        d = addChildDevice("iamtrep", "Load Shedder Status", dni(), [name: "Load Shedder Status", label: deviceLabel(), isComponent: true])
        logCfg "created ${d.displayName}"
    } else if (d.getLabel() != deviceLabel()) d.setLabel(deviceLabel())
    return d
}

void publish() {
    Map m = (state.mem ?: [:]) as Map
    List<String> reasons = (state.reasons ?: []) as List<String>
    String held = namesOf((m.owned ?: []) as List)
    statusDevice()?.updateStatus(["switch": (m.phase ?: 'idle') == 'idle' ? "off" : "on",
                                  contact: ((state.unresponsive ?: [:]) as Map) ? "open" : "closed",
                                  reasons: reasons ? reasons.join(', ') : "none",
                                  shedLoads: held ?: "none"])
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

// chosen holds what a mode input stores (mode ids); a name matches too.
@CompileStatic
boolean modeChosen(String mode, String modeId, List chosen) {
    if (!chosen) return false
    List<String> c = chosen.collect { it.toString() }
    return (modeId != null && c.contains(modeId)) || (mode != null && c.contains(mode))
}

// whenOn / whenOff: [[name, value]] for each reason switch. Returns what holds, in a readable form.
@CompileStatic
List<String> activeReasons(List<Map> whenOn, List<Map> whenOff, String mode, String modeId, List modes) {
    List<String> out = []
    if (whenOn) for (Map s : whenOn) { if (s.value == 'on') out << "${s.name} on".toString() }
    if (whenOff) for (Map s : whenOff) { if (s.value == 'off') out << "${s.name} off".toString() }
    if (modeChosen(mode, modeId, modes)) out << "mode ${mode}".toString()
    return out
}

@CompileStatic
List<String> validateCfg(List<String> loadIds, List<String> onIds, List<String> offIds, List modes) {
    List<String> errs = []
    if (!loadIds) errs << 'Choose at least one switch to shed'
    if (!onIds && !offIds && !modes) errs << 'Choose at least one reason: a switch or a mode'
    List<String> reasonIds = []
    if (onIds) reasonIds.addAll(onIds)
    if (offIds) reasonIds.addAll(offIds)
    if (loadIds && loadIds.any { String id -> reasonIds.contains(id) }) errs << 'A switch to shed cannot also be a reason'
    return errs
}

// The commands still waiting or failed, by load id: id -> the state commanded.
@CompileStatic
Map busyCommands(Map attempts, Map unresponsive) {
    Map out = [:]
    for (Object k : (unresponsive ?: [:]).keySet()) out[k.toString()] = ((Map) unresponsive[k]).want
    for (Object k : (attempts ?: [:]).keySet()) out[k.toString()] = ((Map) attempts[k]).want
    return out
}

// An off is wanted while the load is held off in a shed; an on while it is not held off.
@CompileStatic
boolean commandWanted(String want, String id, Map mem) {
    boolean held = mem?.phase == 'shed' && ((List) (mem.owned ?: [])).contains(id)
    boolean owned = ((List) (mem?.owned ?: [])).contains(id)
    return want == 'off' ? held : !owned
}

@CompileStatic
String verifyOutcome(String wanted, String current, int attempts, int maxAttempts) {
    if (current == wanted) return 'ok'
    return attempts < maxAttempts ? 'retry' : 'fault'
}

// One evaluation. mem: [phase: 'idle' | 'shed' | 'restore', owned: ids this app turned off, released: ids
// turned back on by hand during the shed and left alone, known: ids present when the shed began (a load
// added later is treated as at the start), restoreAt: epoch ms of the next restore, reassertAt: id -> ms].
// shed: a reason holds. values: id -> switch value. busy: id -> state of a command waiting or failed.
// cfg: [manualOn: 'respect' | 'enforce', delayMs, staggerMs, gapMs]. restart: the hub just started, so a
// held load reading on came back with the power, not by hand.
// Returns [mem, actions: [[id, cmd]], wakeAt: Long or null, notes: [text, {id} for a load's name]].
@CompileStatic
Map step(Map mem0, boolean shed, Map values, List<String> ids, Map busy, Map cfg, long now, boolean restart) {
    String phase = (mem0?.phase ?: 'idle') as String
    List<String> owned = ((List) (mem0?.owned ?: [])).collect { it.toString() }
    List<String> released = ((List) (mem0?.released ?: [])).collect { it.toString() }
    List<String> known = ((List) (mem0?.known ?: [])).collect { it.toString() }
    Long restoreAt = mem0?.restoreAt as Long
    Map reassertAt = new LinkedHashMap((Map) (mem0?.reassertAt ?: [:]))
    List<Map> actions = []
    List<String> notes = []
    Map bz = busy ?: [:]
    Map vals = values ?: [:]
    boolean enforce = cfg.manualOn == 'enforce'
    long gap = (cfg.gapMs ?: 300000L) as long
    Long wake = null

    for (String id : new ArrayList<String>(owned)) {
        if (!ids.contains(id)) { owned.remove(id); notes << "{${id}} is no longer in the app while held off: left as it is".toString() }
    }
    released.retainAll(ids)
    known.retainAll(ids)

    if (shed) {
        if (phase != 'shed') {
            // A shed begins. A load still waiting to be restored stays ours; one whose restore did not land counts as ours too.
            List<String> carried = phase == 'restore' ? owned : new ArrayList<String>()
            owned = new ArrayList<String>(carried)
            released = []
            known = new ArrayList<String>(ids)
            restoreAt = null
            reassertAt = [:]
            for (String id : ids) {
                if (owned.contains(id)) continue
                if (vals[id] == 'on') { owned << id; actions << [id: id, cmd: 'off'] }
                else if (bz[id] == 'on') owned << id
            }
            notes << (phase == 'restore' ? 'a reason holds again: shedding resumes' : 'a reason holds: shedding starts').toString()
            phase = 'shed'
        }
        for (String id : ids) {
            String v = vals[id] as String
            if (!known.contains(id)) {
                known << id
                if (v == 'on') { owned << id; actions << [id: id, cmd: 'off'] }
                continue
            }
            if (released.contains(id) || v != 'on' || actions.any { it.id == id }) continue
            if (owned.contains(id)) {
                if (bz[id] == 'off') continue                   // our off is on its way, or failed: the checks handle it
                if (restart) {
                    actions << [id: id, cmd: 'off']
                    notes << "{${id}} is on after the hub restart: turned off again".toString()
                } else if (enforce) {
                    Long last = reassertAt[id] as Long
                    if (last != null && now - last < gap) { wake = minOf(wake, last + gap); continue }
                    reassertAt[id] = now
                    actions << [id: id, cmd: 'off']
                    notes << "{${id}} turned back on during the shed: turned off again".toString()
                } else {
                    owned.remove(id)
                    released << id
                    notes << "{${id}} turned back on during the shed: left on, no longer restored by this app".toString()
                }
            } else if (enforce) {
                Long last = reassertAt[id] as Long
                if (last != null && now - last < gap) { wake = minOf(wake, last + gap); continue }
                reassertAt[id] = now
                owned << id
                actions << [id: id, cmd: 'off']
                notes << "{${id}} turned on during the shed: turned off".toString()
            } else {
                released << id
                notes << "{${id}} turned on during the shed: left on".toString()
            }
        }
    } else {
        if (phase == 'shed') {
            phase = owned ? 'restore' : 'idle'
            restoreAt = now + ((cfg.delayMs ?: 0L) as long)
            notes << (owned ? 'no reason holds: restoring' : 'no reason holds: nothing to restore').toString()
        }
        if (phase == 'restore') {
            for (String id : new ArrayList<String>(owned)) {
                if (vals[id] == 'on') { owned.remove(id); notes << "{${id}} is already back on".toString() }
            }
            if (restoreAt == null || now >= restoreAt) {
                long stagger = (cfg.staggerMs ?: 0L) as long
                if (stagger <= 0L) {
                    for (String id : owned) actions << [id: id, cmd: 'on']
                    owned = []
                } else if (owned) {
                    actions << [id: owned.remove(0), cmd: 'on']
                    restoreAt = now + stagger
                }
            }
            if (!owned) phase = 'idle'
            else wake = minOf(wake, restoreAt)
        }
    }
    if (phase == 'idle') return [mem: [phase: 'idle', owned: [], released: [], known: [], restoreAt: null, reassertAt: [:]],
                                 actions: actions, wakeAt: null, notes: notes]
    return [mem: [phase: phase, owned: owned, released: released, known: known, restoreAt: phase == 'restore' ? restoreAt : null,
                  reassertAt: reassertAt],
            actions: actions, wakeAt: wake, notes: notes]
}

// The app evaluates at least every periodic check; a longer silence means the hub (or the app) was stopped.
@CompileStatic
boolean cameBack(Long lastEval, long now, long gapMs) {
    return lastEval != null && now - lastEval > gapMs
}

@CompileStatic
Long minOf(Long a, Long b) {
    if (a == null) return b
    if (b == null) return a
    return Math.min(a, b)
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

void checkVersion(boolean reinit = true) {
    if (state.version == CODE_VERSION) return
    logVer "New version: ${CODE_VERSION} (was: ${state.version})"
    state.version = CODE_VERSION
    if (reinit) runIn(1, "updated")
}

void logsOff() {
    checkVersion()
    app.updateSetting("debugEnable", [value: "false", type: "bool"])
    app.updateSetting("traceEnable", [value: "false", type: "bool"])
    logWarn "debug and trace logging disabled"
}

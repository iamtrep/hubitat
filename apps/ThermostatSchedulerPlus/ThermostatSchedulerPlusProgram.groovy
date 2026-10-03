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
}

Map mainPage() {
    dynamicPage(name: "mainPage", install: true, uninstall: true) {
        section {
            label title: "Program name", required: true
            input "thermostats", "capability.thermostat", title: "Select Thermostats", multiple: true, required: true, submitOnChange: true
        }
        section("Options") {
            input "ecoOffset", "decimal", title: "Eco offset (°${location.temperatureScale})", defaultValue: 2.0
            input "ecoOnOverrides", "bool", title: "Also apply eco on top of mode overrides", defaultValue: true
            input "separation", "decimal", title: "Required heat/cool separation (°${location.temperatureScale})", defaultValue: 2.0
            input "verifyWrites", "bool", title: "Check each write and report thermostats that do not take the new value", defaultValue: true,
                  description: "Checks once, 30 s after writing. Resending is left to Hubitat's Command Retry."
            input "applyOnStart", "bool", title: "Apply the schedule after a hub restart", defaultValue: true
            input "pauseSwitch", "capability.switch", title: "Pause when this switch is…", required: false, submitOnChange: true
            if (pauseSwitch) input "pauseWhenSwitch", "enum", title: "…in this state", options: ["off", "on"], defaultValue: "off"
            input "restrictDays", "enum", title: "Only on days", multiple: true, required: false,
                  options: ["1": "Monday", "2": "Tuesday", "3": "Wednesday", "4": "Thursday", "5": "Friday", "6": "Saturday", "7": "Sunday"]
            input "restrictModes", "mode", title: "Only in modes", multiple: true, required: false
            input "whilePaused", "enum", title: "While paused", options: ["leave": "Leave thermostats as they are", "off": "Turn thermostats off"], defaultValue: "leave"
            input "onResume", "enum", title: "When the pause ends", options: ["restore": "Restore the thermostat mode and apply the schedule", "leaveOff": "Leave thermostats off"], defaultValue: "restore"
        }
        section("Logging") {
            input "txtEnable", "bool", title: "Enable info logging", defaultValue: true
            input "debugEnable", "bool", title: "Enable debug logging", defaultValue: false, submitOnChange: true
            if (debugEnable) {
                input "traceEnable", "bool", title: "Enable trace logging", defaultValue: false
                input "testClock", "text", title: "Test clock (yyyy-MM-dd HH:mm, blank = real time)", required: false
                input "btnEvaluate", "button", title: "Evaluate now"
                input "btnStart", "button", title: "Run the hub-start handler"
                input "testConfigJson", "textarea", title: "Configuration JSON", required: false
                input "btnLoadConfig", "button", title: "Load configuration"
            }
        }
    }
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
        restrictions: [days: (settings.restrictDays ?: []).collect { it as int }, modeIds: (settings.restrictModes ?: []).collect { it as Long }]]
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
    switch (btn) {
        case "btnEvaluate": evaluate("button", false); break
        case "btnApply": evaluate("apply now", true); break
        case "btnStart": startHandler(); break
        case "btnLoadConfig":
            Map doc
            try { doc = parseJson(settings.testConfigJson as String) as Map } catch (Exception e) { logWarn "configuration is not JSON"; return }
            List<String> errs = validateConfig(doc, validationEnv())
            if (errs) { logWarn "configuration rejected: ${errs}"; return }
            state.config = doc
            logCfg "configuration loaded"
            evaluate("config loaded", true)
            break
    }
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

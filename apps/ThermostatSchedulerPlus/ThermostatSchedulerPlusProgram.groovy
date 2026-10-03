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
    importUrl: "https://raw.githubusercontent.com/iamtrep/hubitat/main/apps/ThermostatSchedulerPlus/ThermostatSchedulerPlusProgram.groovy",
    iconUrl: "", iconX2Url: ""
)

preferences {
    page(name: "mainPage")
}

Map mainPage() {
    dynamicPage(name: "mainPage", install: true, uninstall: true) {
        section { label title: "Program name", required: true }
    }
}

void installed() { initialize() }
void updated() { unsubscribe(); unschedule(); initialize() }
void initialize() { }

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
    if (rt.paused == true || ctx.restricted == true) return [layer: 'paused']
    Long modeId = ctx.modeId as Long
    Map sched = (cfg.schedules as List<Map>)?.find { Map s -> s.name == cfg.active }
    Map cur = sched?.type == 'time' ? currentTransition(sched, ctx) : null
    String key = sched?.type == 'mode' ? "mode|${modeId}".toString() : (cur?.key as String)
    Map ov = (cfg.overrides as List<Map>)?.find { Map o -> (o.modeId as Long) == modeId }
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
    for (Map t in therms) {
        String mode = (target.mode ?: t.mode) as String
        if (target.mode && (force || target.mode != t.mode)) out << [id: t.id, command: 'setThermostatMode', value: target.mode]
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

// ── End core ──────────────────────────────────────────────────────────

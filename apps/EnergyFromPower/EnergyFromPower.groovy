// Copyright (c) 2026 PJ
// SPDX-License-Identifier: MIT

/*
 Energy From Power

 Builds an energy total (kWh) from a power meter that reports only power, and publishes it
 on an energy meter device. Each reading counts until the next one (a zero-order hold), so a
 load that steps between fixed levels, such as a water heater element, is counted exactly.
*/

import com.hubitat.app.ChildDeviceWrapper
import com.hubitat.app.DeviceWrapper
import com.hubitat.hub.domain.State
import groovy.transform.CompileStatic
import groovy.transform.Field
import java.math.RoundingMode
import java.util.concurrent.ConcurrentHashMap

@Field static final String CODE_VERSION = "0.1.0"
@Field static final String OUTPUT_DRIVER = "Virtual Power/Energy Meter"

// Concurrency. One record per instance (total, power held, its time, what was published, the step
// timer) is read, changed and written back by every entry point: each power reading, the step timer,
// the end-of-hour publish, the energy meter's echo and the reset button, and these run in parallel.
// Every change goes through ConcurrentHashMap.compute() on the instance's key, which runs one at a
// time per key, so no change is lost; inside it the record is saved to atomicState (saved at once,
// in the order of the changes) and the total is published. The map is memory only: after a reboot
// or a code push the record is read back from atomicState. No singleThreaded: a chatty meter's
// readings don't queue behind each other or hold up the hub's other event handlers.
@Field static final ConcurrentHashMap<String, Map> RECORDS = new ConcurrentHashMap<String, Map>()

definition(
    name: "Energy From Power",
    namespace: "iamtrep",
    author: "pj",
    description: "Builds an energy total (kWh) from a power meter's readings and publishes it on an energy meter device",
    menu: "Automations", // new in platform 2.5.0
    category: "Convenience",
    importUrl: "https://raw.githubusercontent.com/iamtrep/hubitat/main/apps/EnergyFromPower/EnergyFromPower.groovy",
    documentationLink: "https://github.com/iamtrep/hubitat/tree/main/apps/EnergyFromPower",
    iconUrl: "", iconX2Url: ""
)

preferences {
    page(name: "mainPage")
}

// ── UI ────────────────────────────────────────────────────────────────

String esc(Object s) { s == null ? '' : s.toString().replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;').replace("'", '&#39;') }

String hint(String html) { "<span style='font-size:90%;color:var(--hubitat-secondary-text-color, #666)'>${html}</span>" }

String devLink(DeviceWrapper d) { "<a href='/device/edit/${d.id}' target='_blank'>${esc(d.displayName)}</a>" }

Map mainPage() {
    dynamicPage(name: "mainPage", title: "", install: true, uninstall: true) {
        section {
            label title: "App name", required: false
            paragraph hint("Adds up the power meter's readings over time (power × time) into a total in kWh and publishes it " +
                           "on an energy meter device. The defaults suit a meter that reports each change in power, as most plugs " +
                           "and meters do: pick the meter and press Done. " +
                           "<a href='https://github.com/iamtrep/hubitat/tree/main/apps/EnergyFromPower' target='_blank'>How it works</a>")
            String err = outputError()
            if (err) paragraph "<div class='p-message p-message-error p-3 border-round'>${esc(err)}</div>"
            if (record() != null) paragraph rawHtml: true, statusHtml()
        }
        section("Power meter") {
            input "meter", "capability.powerMeter", title: "Power meter", required: true
        }
        section("Energy meter") {
            input "outputChoice", "enum", title: "Publish the total on",
                  options: [own: "a device this app creates", pick: "a device I pick"], defaultValue: "own", required: true,
                  submitOnChange: true
            if (outputChoice == "pick") {
                input "output", "capability.energyMeter", title: "Energy meter (needs a Set Energy command)", required: true,
                      submitOnChange: true
            }
            DeviceWrapper cur = currentOutput()
            if (cur) paragraph "Publishing on ${devLink(cur)}"
            else if (outputChoice != "pick") paragraph hint("Pressing Done creates <b>${esc(deviceLabel())}</b> " +
                                                            "with the hub's ${OUTPUT_DRIVER} driver.")
            ChildDeviceWrapper own = getChildDevice(dni())
            if (outputChoice == "pick" && own) {
                paragraph hint("${devLink(own)}, which this app created, is no longer used. Delete it from Devices if nothing else uses it.")
            }
            input "stepKwh", "decimal", title: "Publish the total each time it grows by (kWh)", defaultValue: 1,
                  range: "0.01..100", required: true
            paragraph hint(stepHint())
            if (record() != null) {
                input "resetTotal", "button", title: "Reset the total to 0 kWh"
                paragraph hint("The Power and Energy Tracker counts a reset as negative usage in the hour it happens.")
            }
        }
        section("Advanced", hideable: true, hidden: true) {
            paragraph hint("Most setups need none of these.")
            input "hourlyPublish", "bool", title: "Publish at the end of each hour", defaultValue: true
            paragraph hint("At 59 min 50 s past the hour, when the total has grown at all. Keeps hourly charts, such as the " +
                           "Power and Energy Tracker's, in the right hour.")
            input "decimals", "number", title: "Decimals in the published total", defaultValue: 3, range: "0..6", required: true
            input "limitHold", "bool", title: "Stop counting when the power meter goes offline", defaultValue: false,
                  submitOnChange: true
            paragraph hint("A meter that drops off the network keeps showing its last reading, so a load that was on would keep " +
                           "counting. Turn this on only for a meter that goes offline now and then. A plug that reports only when " +
                           "the power changes can stay silent for hours while the load runs steadily; with this on, that time is lost.")
            if (limitHold) {
                input "maxHoldMinutes", "number", title: "Count the load as off after this long without a reading (minutes)",
                      defaultValue: 60, range: "1..10080", required: true
            }
            input "pollMeter", "bool", title: "Ask the power meter for a reading on a schedule", defaultValue: false, submitOnChange: true
            paragraph hint("For a meter that does not send readings on its own.")
            if (pollMeter) {
                input "refreshMinutes", "number", title: "Every (minutes)", defaultValue: 15, range: "1..1440", required: true
            }
            input "copyPower", "bool", title: "Forward each power reading to the energy meter", defaultValue: false, submitOnChange: true
            paragraph "<div class='p-message p-message-warn p-3 border-round'><b>Adds load to the hub.</b> Every power reading " +
                      "becomes a second event on the energy meter. With a meter that reports often, this can slow the hub.</div>"
        }
        section("Logging", hideable: true, hidden: true) {
            input "txtEnable", "bool", title: "Enable info logging", defaultValue: true
            input "debugEnable", "bool", title: "Enable debug logging (turns off after 30 minutes)", defaultValue: false, submitOnChange: true
            if (debugEnable) input "traceEnable", "bool", title: "Enable trace logging (turns off with debug)", defaultValue: false
        }
    }
}

// The publish rule in words, with how long a step takes at the meter's current power.
String stepHint() {
    BigDecimal step = stepSetting()
    String rule = hourlyPublish != false ? "Also published at the end of every hour, whichever comes first." : ""
    BigDecimal w = meter ? toWatts(numOrNull((meter as DeviceWrapper).currentValue("power")), null) : null
    if (w == null || w <= 0.0G) return rule
    long s = (step * 3600000.0G / w).setScale(0, java.math.RoundingMode.HALF_UP).longValue()
    return "${rule} At the meter's ${fmtNum(w)} W now, ${fmtNum(step)} kWh takes ${fmtDuration(s)}.".trim()
}

BigDecimal stepSetting() { numOrNull(stepKwh) ?: 1.0G }

String fmtDuration(long s) {
    if (s < 120L) return "${s} s"
    if (s < 7200L) return "${s.intdiv(60L)} min"
    return "${s.intdiv(3600L)} h"
}

String statusHtml() {
    Map rec = record()
    StringBuilder h = new StringBuilder()
    h << "<p><b>Total: ${esc(fmtKwh(whToKwh(numOrNull(rec.wh), decimalPlaces())))} kWh</b>"
    h << " · power ${esc(fmtNum(rec.p))} W"
    if (rec.pub != null) h << " · last published ${esc(fmtNum(rec.pub))} kWh"
    h << "</p>"
    return h.toString()
}

// The energy meter the app publishes on, without creating one.
DeviceWrapper currentOutput() {
    if (outputChoice == "pick") return settings.output as DeviceWrapper
    return getChildDevice(dni())
}

// A device the user picks must take setEnergy; the app's own device always does.
String outputError() {
    if (outputChoice != "pick") return null
    if (!settings.output) return "Pick the energy meter to publish on, or choose a device this app creates."
    if (!(settings.output as DeviceWrapper).hasCommand("setEnergy")) {
        return "${(settings.output as DeviceWrapper).displayName} has no Set Energy command. Pick another device, or let the app create one."
    }
    return null
}

// ── Lifecycle ─────────────────────────────────────────────────────────

void installed() { checkVersion(false); initialize() }

void updated() { checkVersion(false); unsubscribe(); unschedule(); initialize() }

void uninstalled() {
    RECORDS.remove(recKey())
    if (getChildDevice(dni())) deleteChildDevice(dni())
}

void initialize() {
    if (debugEnable || traceEnable) runIn(1800, "logsOff")
    if (outputError()) {
        logWarn "nothing is published: ${outputError()}"
        return
    }
    DeviceWrapper out = outputDevice()
    subscribe(meter, "power", "powerHandler")
    subscribe(out, "energy", "outputEnergyHandler")
    subscribe(location, "systemStart", "systemStartHandler")
    int rm = pollMeter ? ((refreshMinutes ?: 15) as int) : 0
    if (rm > 0) schedule("${new Random().nextInt(60)} */${rm} * ? * *", "refreshMeter")
    if (hourlyPublish != false) schedule("50 59 * * * ?", "hourlyHandler")
    // unschedule() above cancelled the step timer, so the record must forget it.
    change("settings saved", false) { Map rec -> rec.wakeAt = null; rec }
    state.remove("published")   // kept in the record since 0.1.0's first builds
    state.remove("wakeAt")
}

void systemStartHandler(evt) {
    checkVersion()
    change("hub restart", false) { Map rec -> rec }
}

void logsOff() {
    checkVersion()
    app.updateSetting("debugEnable", [value: "false", type: "bool"])
    app.updateSetting("traceEnable", [value: "false", type: "bool"])
    logWarn "debug and trace logging disabled"
}

void checkVersion(boolean reinit = true) {
    if (state.version == CODE_VERSION) return
    logVer "New version: ${CODE_VERSION} (was: ${state.version})"
    state.version = CODE_VERSION
    if (reinit) runIn(1, "updated")
}

// ── Inputs ────────────────────────────────────────────────────────────

void powerHandler(evt) {
    checkVersion()
    BigDecimal w = toWatts(numOrNull(evt.value), evt.unit as String)
    if (w == null) { logWarn "ignored a power reading that is not a number: ${evt.value}"; return }
    logEvt "${fmtNum(w)} W"
    long t = evt.date.time as long
    change("reading", false) { Map rec -> takeReading(rec, w, t, maxHoldMs()) }
    if (copyPower) {
        DeviceWrapper out = outputDevice()
        if (out?.hasCommand("setPower")) out.setPower(w)
    }
}

// An energy value the app did not publish (the device's Reset Energy, or Set Energy from its
// page) becomes the new total. The app's own echoes match what it published.
void outputEnergyHandler(evt) {
    checkVersion()
    BigDecimal v = numOrNull(evt.value)
    if (v == null || record() == null) return
    boolean adopted = false
    change("energy meter set", false) { Map rec ->
        if (!isForeignTotal(v, numOrNull(rec.pub), stepSetting())) return rec
        Map next = integrate(rec, now(), maxHoldMs())
        next.wh = v * 1000.0G
        next.pub = v
        adopted = true
        return next
    }
    if (adopted) logInfo "the energy meter was set to ${fmtNum(v)} kWh outside the app; counting on from there"
}

void wakeHandler() {
    checkVersion()
    change("step reached", false) { Map rec -> rec.wakeAt = null; rec }
}

void hourlyHandler() {
    checkVersion()
    change("hourly", true) { Map rec -> rec }
}

void refreshMeter() {
    checkVersion()
    logSched "asking ${meter.displayName} for a reading"
    meter.refresh()
}

void appButtonHandler(String btn) {
    checkVersion()
    if (btn != "resetTotal" || record() == null) return
    change("reset", false) { Map rec ->
        Map next = integrate(rec, now(), maxHoldMs())
        next.wh = 0.0G
        next.pub = 0.0G
        outputDevice()?.setEnergy(0.0G)
        return next
    }
    logInfo "total reset to 0 kWh"
}

// ── Record ────────────────────────────────────────────────────────────

String recKey() { app.id.toString() }

// The record as it stands, from memory or, after a reboot or code push, from atomicState.
// [wh: Wh, p: W held, t: counted up to, r: when that power was read, pub: kWh published, wakeAt: step timer]
Map record() { RECORDS.get(recKey()) ?: loadRecord() }

Map loadRecord() {
    Map saved = atomicState.acc as Map
    if (saved == null) return null
    Map rec = new LinkedHashMap(saved)
    if (rec.pub == null) rec.pub = numOrNull(atomicState.published) ?: 0.0G
    return rec
}

// Starts from the energy meter's current total, so a device that already holds a reading counts
// on from it, and from the meter's current power, counted from now.
Map newRecord() {
    BigDecimal startKwh = numOrNull(outputDevice()?.currentValue("energy")) ?: 0.0G
    State s = (meter as DeviceWrapper).currentState("power")
    BigDecimal w = toWatts(numOrNull(s?.value), s?.unit as String) ?: 0.0G
    long t = now()
    logCfg "starting at ${fmtNum(startKwh)} kWh, ${fmtNum(w)} W"
    return [wh: startKwh * 1000.0G, p: w, t: t, r: t, pub: startKwh]
}

// The one way the record changes. Inside compute(), one at a time for this instance: apply the
// change, count the energy up to now, publish when the total has grown by the step (or, with
// anyGrowth, at all), note when the next step falls, and save. The step timer is set afterwards,
// outside the lock.
void change(String why, boolean anyGrowth, Closure<Map> f) {
    if (outputError()) return
    Long arm = null
    Map done = RECORDS.compute(recKey(), { String k, Map held ->
        Map cur = held ?: loadRecord() ?: newRecord()
        Map rec = f.call(new LinkedHashMap(cur)) as Map
        long t = now()
        rec = integrate(rec, t, maxHoldMs())
        BigDecimal step = stepSetting()
        BigDecimal kwh = whToKwh(numOrNull(rec.wh), decimalPlaces())
        BigDecimal pub = numOrNull(rec.pub) ?: 0.0G
        if (kwh - pub >= step || (anyGrowth && kwh != pub)) {
            logDebug "publishing ${fmtNum(kwh)} kWh (${why})"
            outputDevice()?.setEnergy(kwh)
            rec.pub = kwh
            pub = kwh
        }
        // A chatty meter changes the step time on every reading. Moving the timer only when the
        // step comes sooner keeps that to about one schedule write per step; a timer that fires
        // early finds nothing to publish and sets the next one.
        Long at = nextStepAt(rec, pub, step, maxHoldMs())
        Long armed = rec.wakeAt as Long
        if (at != null && (armed == null || armed <= t || at < armed)) {
            rec.wakeAt = at
            arm = at
        }
        atomicState.acc = rec
        return rec
    } as java.util.function.BiFunction)
    if (arm != null) runInMillis(Math.max(1000L, (arm as long) - now()), "wakeHandler")
    logTrace "${why}: ${done}"
}

long maxHoldMs() { limitHold ? (((maxHoldMinutes ?: 60) as long) * 60000L) : 0L }

int decimalPlaces() { (decimals == null ? 3 : decimals) as int }

// ── Energy meter ──────────────────────────────────────────────────────

String dni() { "efp-${app.id}" }

String deviceLabel() { app.getLabel() == "Energy From Power" ? "Energy From Power" : "${app.getLabel()} Energy" }

DeviceWrapper outputDevice() {
    if (outputChoice == "pick") return settings.output as DeviceWrapper
    ChildDeviceWrapper d = getChildDevice(dni())
    if (!d) {
        d = addChildDevice("hubitat", OUTPUT_DRIVER, dni(), [name: OUTPUT_DRIVER, label: deviceLabel(), isComponent: false])
        logCfg "created ${d.displayName}"
    }
    return d
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

@CompileStatic
String fmtNum(Object n) {
    BigDecimal b = numOrNull(n)
    return b == null ? '' : b.stripTrailingZeros().toPlainString()
}

@CompileStatic
String fmtKwh(BigDecimal kwh) { kwh == null ? '' : kwh.toPlainString() }

// Watts from a reading and its unit: kW readings are scaled, anything else is taken as W.
@CompileStatic
BigDecimal toWatts(BigDecimal v, String unit) {
    if (v == null) return null
    return unit?.trim()?.equalsIgnoreCase('kW') ? v * 1000.0G : v
}

@CompileStatic
BigDecimal whToKwh(BigDecimal wh, int decimals) {
    BigDecimal x = wh != null ? wh : 0.0G
    return x.divide(1000.0G, decimals, RoundingMode.HALF_UP)
}

// The power held since acc.t counts until t: power × time, in Wh. With maxHoldMs above 0 a
// reading stops counting maxHoldMs after it arrived (acc.r), however often the total is brought
// up to date. A time before acc.t (an out-of-order event) adds nothing.
// acc: [wh: Wh so far, p: W held, t: counted up to (epoch ms), r: when the reading arrived].
// Returns a copy with those keys changed; other keys are kept.
@CompileStatic
Map integrate(Map acc, long t, long maxHoldMs) {
    long from = acc.t as long
    long r = acc.r != null ? (acc.r as long) : from
    BigDecimal wh = numOrNull(acc.wh) ?: 0.0G
    BigDecimal p = numOrNull(acc.p) ?: 0.0G
    Map next = new LinkedHashMap(acc)
    next.p = p
    next.r = r
    if (t <= from) { next.wh = wh; next.t = from; return next }
    long until = maxHoldMs > 0L ? Math.min(t, r + maxHoldMs) : t
    long counted = Math.max(0L, until - from)
    next.wh = wh + (p > 0.0G ? p * new BigDecimal(counted) / 3600000.0G : 0.0G)
    next.t = t
    return next
}

// A new reading: count the one held so far up to its time, then hold the new one from then.
// A reading can be handled after the total was brought up to date past its own time (it waited
// behind another execution); then the time between the reading and acc.t is corrected from the
// old power to the new one. A reading older than the one held is stale and ignored.
@CompileStatic
Map takeReading(Map acc, BigDecimal w, long t, long maxHoldMs) {
    BigDecimal nw = w < 0.0G ? 0.0G : w
    long from = acc.t as long
    long r = acc.r != null ? (acc.r as long) : from
    if (t < r) return integrate(acc, from, maxHoldMs)
    if (t >= from) {
        Map next = integrate(acc, t, maxHoldMs)
        next.p = nw
        next.r = t
        return next
    }
    BigDecimal old = numOrNull(acc.p) ?: 0.0G
    long oldEnd = maxHoldMs > 0L ? Math.min(from, r + maxHoldMs) : from
    long newEnd = maxHoldMs > 0L ? Math.min(from, t + maxHoldMs) : from
    BigDecimal fix = (nw * new BigDecimal(Math.max(0L, newEnd - t)) - old * new BigDecimal(Math.max(0L, oldEnd - t))) / 3600000.0G
    Map next = new LinkedHashMap(acc)
    next.wh = (numOrNull(acc.wh) ?: 0.0G) + fix
    next.p = nw
    next.t = from
    next.r = t
    return next
}

// When the total will have grown by step past the published value at the power held, or null
// when it won't: no power, or the hold limit ends first.
@CompileStatic
Long nextStepAt(Map acc, BigDecimal published, BigDecimal step, long maxHoldMs) {
    BigDecimal p = numOrNull(acc.p) ?: 0.0G
    if (p <= 0.0G) return null
    BigDecimal needWh = (published + step) * 1000.0G - (numOrNull(acc.wh) ?: 0.0G)
    long ms = needWh <= 0.0G ? 0L : (needWh * 3600000.0G / p).setScale(0, RoundingMode.CEILING).longValue()
    long at = (acc.t as long) + ms
    if (maxHoldMs > 0L) {
        long r = acc.r != null ? (acc.r as long) : (acc.t as long)
        if (at > r + maxHoldMs) return null
    }
    return at
}

// True when an energy value on the meter is not the app's own echo: it differs from what was
// published by more than a rounding error. A device that rounds coarser than the app still matches.
@CompileStatic
boolean isForeignTotal(BigDecimal v, BigDecimal published, BigDecimal step) {
    if (published == null) return true
    BigDecimal tol = step > 0.01G ? step : 0.01G
    return (v - published).abs() > tol
}

// ── End core ──────────────────────────────────────────────────────────

// ── Logging ───────────────────────────────────────────────────────────
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

// Copyright (c) 2026 PJ
// SPDX-License-Identifier: MIT

/*
 Switched Heater Thermostat

 Drives heater switches from a Virtual Thermostat fed by temperature sensors. The
 thermostat decides heating or idle; the app writes its temperature, follows its
 operating state with the heaters, checks that they respond, draw power and warm the
 room, caps the heating time, protects against frost, and raises an alert when it
 cannot do its job.
*/

import com.hubitat.app.DeviceWrapper
import groovy.json.JsonOutput
import groovy.transform.Field

@Field static final String CODE_VERSION = "0.1.0"

definition(
    name: "Switched Heater Thermostat",
    namespace: "iamtrep",
    author: "pj",
    description: "Heater switches driven by a Virtual Thermostat and temperature sensors, with alerts",
    category: "Convenience",
    singleThreaded: true,
    importUrl: "https://raw.githubusercontent.com/iamtrep/hubitat/main/apps/SwitchedHeaterThermostat/SwitchedHeaterThermostat.groovy",
    iconUrl: "", iconX2Url: ""
)

@Field static final Map AGG_OPTS = [average: "Average", minimum: "Minimum"]
@Field static final Map SENSOR_FAULT_OPTS = [off: "Keep the heaters off", on: "Keep the heaters on", cycle: "Run the heaters on a fixed cycle"]
@Field static final String VT_DRIVER = "Virtual Thermostat"
@Field static final Integer QUIET_FLOOR_MIN = 30
@Field static final Integer POWER_GRACE_MIN = 2
@Field static final Integer MIN_GAPS = 5
@Field static final Integer KEEP_GAPS = 48
@Field static final Integer MAX_ATTEMPTS = 2

preferences {
    page(name: "mainPage")
}

// ── UI ────────────────────────────────────────────────────────────────

String esc(Object s) { s == null ? '' : s.toString().replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;').replace("'", '&#39;') }

boolean anyMeter() { (heaters ?: []).any { DeviceWrapper d -> d.hasAttribute("power") } }

// Command Retry is a per-device setting with no DeviceWrapper accessor; the device record has it.
// Returns null when the record can't be read, so a failed check stays silent.
private Map retryInfo(DeviceWrapper d) {
    try {
        Map rec = null
        httpGet([uri: "http://127.0.0.1:8080", path: "/device/fullJson/${d.id}", contentType: "application/json", timeout: 5]) { resp -> rec = resp.data as Map }
        Map dev = rec?.device as Map
        if (dev == null || !dev.containsKey("retryAvailable")) return null
        return [label: d.displayName as String, available: dev.retryAvailable == true, enabled: dev.retryEnabled == true]
    } catch (Exception e) {
        logDebug "command retry check for ${d.displayName}: ${e.message}"
        return null
    }
}

List<String> heatersWithoutRetry() { retryAdvice((heaters ?: []).collect { DeviceWrapper d -> retryInfo(d) }.findAll { it != null }) }

String retryAdviceText(List<String> names) {
    return "Command Retry is off for ${namesText(names)}. Turning it on (Settings, Command Retry) lets the hub resend a missed on or off before this app reports a load fault.".toString()
}

Map mainPage() {
    dynamicPage(name: "mainPage", install: true, uninstall: true) {
        section {
            label title: "Name", required: true
            if (app.getInstallationState() == "COMPLETE") paragraph statusHtml()
        }
        section("Thermostat") {
            input "thermostat", "capability.thermostat", title: "Virtual Thermostat (built-in driver)", required: false, submitOnChange: true
            if (!thermostat) input "createThermostat", "bool", title: "Create a Virtual Thermostat for this app", defaultValue: false, submitOnChange: true
            if (thermostat && getChildDevice(childDni())) {
                paragraph "The thermostat this app created is no longer used."
                input "btnRemoveChild", "button", title: "Remove the created thermostat"
            }
            input "frostTemp", "decimal", title: "Frost protection: heat below this temperature (°${location.temperatureScale}) whatever the thermostat mode; blank = off", required: false
        }
        section("Heat") {
            input "heaters", "capability.switch", title: "Heater switches", multiple: true, required: true, submitOnChange: true
            List<String> noRetry = heatersWithoutRetry()
            if (noRetry) paragraph "<i>${esc(retryAdviceText(noRetry))}</i>"
            input "verifyTimeout", "number", title: "Time a heater has to confirm a command (seconds)", defaultValue: 30, range: "5..300"
            if (anyMeter()) {
                input "usePower", "bool", title: "Check the power draw of heaters that report it", defaultValue: true, submitOnChange: true
                if (usePower != false) input "minPowerW", "number", title: "A heater that is on draws at least (W)", defaultValue: 20, range: "1..5000"
            }
            input "minOnMinutes", "number", title: "Keep the heaters on for at least (minutes; blank = no minimum)", required: false, range: "1..60"
            input "minOffMinutes", "number", title: "Keep the heaters off for at least (minutes; blank = no minimum)", required: false, range: "1..60"
            input "maxHeatMinutes", "number", title: "Longest time the heaters may stay on (minutes; blank = no limit)", required: false, range: "10..1440", submitOnChange: true
            if (numOrNull(maxHeatMinutes) != null) input "restMinutes", "number", title: "Then keep them off for (minutes)", defaultValue: 15, range: "1..240"
            input "noRiseMinutes", "number", title: "Alert when the heaters have been on this long without warming the room (minutes; blank = off)", required: false, range: "10..240", submitOnChange: true
            if (numOrNull(noRiseMinutes) != null) input "minRiseDegrees", "decimal", title: "Warming means a rise of at least (°${location.temperatureScale})", defaultValue: 0.3
            input "keepAliveMinutes", "number", title: "Re-send the heaters' state every (minutes; blank = only when it is wrong)", required: false, range: "1..120"
        }
        section("Temperature") {
            input "sensors", "capability.temperatureMeasurement", title: "Temperature sensors", multiple: true, required: true
            input "aggregate", "enum", title: "Combine the readings by", options: AGG_OPTS, defaultValue: "average", required: true
            input "quietAfter", "number", title: "Count a sensor as quiet after (minutes), until the app has learned its usual gaps", defaultValue: 120, range: "10..1440"
        }
        section("When no sensor reports") {
            input "onSensorFault", "enum", title: "Heaters", options: SENSOR_FAULT_OPTS, defaultValue: "off", required: true, submitOnChange: true
            if (onSensorFault == "cycle") {
                input "cycleDutyPercent", "number", title: "On for (% of each cycle)", defaultValue: 30, range: "5..95"
                input "cyclePeriodMinutes", "number", title: "Cycle length (minutes)", defaultValue: 30, range: "10..120"
            }
        }
        section("Alerts") {
            input "notifyDevices", "capability.notification", title: "Notification devices", multiple: true, required: false
            input "sensorFaultSwitch", "capability.switch", title: "Switch to turn on while no sensor reports", required: false, submitOnChange: true
            input "loadFaultSwitch", "capability.switch", title: "Switch to turn on while a heater does not respond or draws the wrong power", required: false, submitOnChange: true
            input "limitFaultSwitch", "capability.switch", title: "Switch to turn on when the heaters reach the time limit", required: false, submitOnChange: true
            input "riseFaultSwitch", "capability.switch", title: "Switch to turn on while the heaters run without warming the room", required: false, submitOnChange: true
            input "notifyPartial", "bool", title: "Also notify when some sensors go quiet", defaultValue: true
        }
        String err = configError()
        if (err) section { paragraph "<b>${esc(err)}</b>" }
        section("Logging") {
            input "txtEnable", "bool", title: "Enable info logging", defaultValue: true
            input "debugEnable", "bool", title: "Enable debug logging (turns off after 30 minutes)", defaultValue: false, submitOnChange: true
        }
        if (debugEnable) {
            section("Testing") {
                input "testSecondsPerMinute", "number", title: "Seconds per minute, for tests (blank or none = 60)", required: false
                input "btnCheck", "button", title: "Run the 5-minute check now"
                input "btnForgetGaps", "button", title: "Forget the sensors' learned gaps"
            }
        }
    }
}

String statusHtml() {
    DeviceWrapper t = thermostatDevice()
    List<String> rows = []
    if (t) rows << "<a href='/device/edit/${t.id}' target='_blank'>${esc(t.displayName)}</a>: ${esc(t.currentValue('temperature'))}°${esc(location.temperatureScale)}, ${esc(t.currentValue('thermostatMode'))}, ${esc(t.currentValue('thermostatOperatingState'))}".toString()
    (heaters ?: []).each { DeviceWrapper d ->
        String p = d.hasAttribute("power") ? ", ${d.currentValue('power')} W" : ""
        rows << "${esc(d.displayName)}: ${esc(d.currentValue('switch'))}${esc(p)}".toString()
    }
    List q = (state.quiet ?: []) as List
    if (q) rows << "Quiet: ${esc(namesText(q))}".toString()
    if (state.frost) rows << "<b>Frost protection running</b>"
    Map f = faults()
    if (f.sensor) rows << "<b>Sensor fault</b>"
    if (f.load) rows << "<b>Load fault</b>"
    if (f.limit) rows << "<b>Limit fault</b>"
    if (f.rise) rows << "<b>Not warming</b>"
    if (state.restUntil) rows << "Resting until ${esc(new Date(state.restUntil as Long).format('HH:mm', location.timeZone))}".toString()
    return rows.join("<br>")
}

String configError() {
    if (thermostat && thermostat.getTypeName() != VT_DRIVER)
        return "${thermostat.displayName} uses the ${thermostat.getTypeName()} driver. Pick a device on the built-in Virtual Thermostat driver.".toString()
    if (!thermostat && !createThermostat) return "Pick a Virtual Thermostat, or turn on Create a Virtual Thermostat."
    List<String> heaterIds = (heaters ?: []).collect { it.id.toString() }
    for (DeviceWrapper d : [sensorFaultSwitch, loadFaultSwitch, limitFaultSwitch, riseFaultSwitch]) {
        if (d && heaterIds.contains(d.id.toString())) return "${d.displayName} is a heater. Pick another fault switch.".toString()
    }
    return null
}

// ── Thermostat device ─────────────────────────────────────────────────

String childDni() { "heaterthermo-${app.id}" }

DeviceWrapper thermostatDevice() { thermostat ?: getChildDevice(childDni()) }

private void ensureThermostat() {
    if (thermostat || !createThermostat || getChildDevice(childDni())) return
    addChildDevice("hubitat", VT_DRIVER, childDni(), [name: VT_DRIVER, label: app.getLabel(), isComponent: true])
    logCfg "created thermostat ${app.getLabel()}"
}

// ── Lifecycle ─────────────────────────────────────────────────────────

void installed() { initialize() }

void updated() { unsubscribe(); unschedule(); initialize() }

void uninstalled() {
    heatersOff()
    for (DeviceWrapper d : [sensorFaultSwitch, loadFaultSwitch, limitFaultSwitch, riseFaultSwitch]) { if (d) d.off() }
    if (getChildDevice(childDni())) deleteChildDevice(childDni())
}

void initialize() {
    checkVersion(false)
    if (debugEnable) runIn(1800, "logsOff")
    String err = configError()
    if (err) { logError err; heatersOff(); return }
    ensureThermostat()
    DeviceWrapper t = thermostatDevice()
    if (t == null) { logError "no thermostat"; heatersOff(); return }
    t.setSupportedThermostatModes(JsonOutput.toJson(["off", "heat"]))
    if (state.faults == null) state.faults = [sensor: false, load: false, limit: false, rise: false]
    if (state.quiet == null) state.quiet = []
    if (state.sensors == null) state.sensors = [:]
    if (state.unresponsive == null) state.unresponsive = [:]
    state.attempts = [:]
    // Drop per-heater state for heaters no longer in the list.
    List<String> heaterIds = (heaters ?: []).collect { it.id.toString() }
    for (String key : ["unresponsive", "powerBad", "powerFault"]) {
        Map m = (state[key] ?: [:]) as Map
        state[key] = m.findAll { Object k, Object v -> heaterIds.contains(k.toString()) }
    }
    List<String> noRetry = heatersWithoutRetry()
    if (noRetry) logWarn retryAdviceText(noRetry)
    subscribe(location, "systemStart", "startHandler")
    subscribe(sensors, "temperature", "sensorHandler")
    subscribe(t, "thermostatOperatingState", "opStateHandler")
    subscribe(t, "thermostatMode", "opStateHandler")
    subscribe(heaters, "switch", "heaterHandler")
    List metered = (heaters ?: []).findAll { DeviceWrapper d -> d.hasAttribute("power") }
    if (usePower != false && metered) subscribe(metered, "power", "powerHandler")
    schedule("${new Random().nextInt(60)} */5 * ? * *", "checkAll")
    scheduleKeepAlive()
    checkAll()
}

void checkVersion(boolean reinit = true) {
    if (state.version == CODE_VERSION) return
    logVer "version ${CODE_VERSION} (was ${state.version})"
    state.version = CODE_VERSION
    if (reinit) runIn(1, "updated")
}

void startHandler(evt) {
    checkVersion()
    logInfo "hub started"
    checkAll()
}

void checkAll() {
    checkVersion()
    logSched "check"
    sampleActivity()
    updateTemperature()
    applyLoads()
    applyFaultSwitches()
}

void appButtonHandler(String btn) {
    checkVersion()
    if (btn == "btnCheck") checkAll()
    else if (btn == "btnForgetGaps") {
        Map all = [:]
        ((state.sensors ?: [:]) as Map).each { k, v -> all[k] = [last: (v as Map)?.last, gaps: []] }
        state.sensors = all
    }
    else if (btn == "btnRemoveChild" && thermostat && getChildDevice(childDni())) {
        deleteChildDevice(childDni())
        logCfg "removed the created thermostat"
    }
}

Map faults() { (state.faults ?: [sensor: false, load: false, limit: false, rise: false]) as Map }

// The test hook applies only while debug logging is on, so a forgotten value can't shorten real timers.
Long minuteMs() {
    Long t = debugEnable ? numOrNull(testSecondsPerMinute) : null
    return (t ?: 60L) * 1000L
}

Long minutesMs(Object setting) {
    Long n = numOrNull(setting)
    return n == null ? null : n * minuteMs()
}

Integer secondsUntil(Long at) { Math.max(1L, (at - now() + 999L).intdiv(1000L) as Long) as Integer }

// ── Alerts ────────────────────────────────────────────────────────────

private void setFault(String kind, boolean raised, String detail, String extra = "") {
    Map f = faults()
    if ((f[kind] == true) == raised) return
    f[kind] = raised
    state.faults = f
    String msg = faultMessage(app.getLabel(), kind, raised, detail, extra)
    if (raised) logWarn(msg) else logInfo(msg)
    sendAlert(msg)
    applyFaultSwitches()
}

private void sendAlert(String msg) {
    (notifyDevices ?: []).each { it.deviceNotification(msg) }
}

// The app owns the fault switches: they follow the faults and are re-asserted at each check.
private void applyFaultSwitches() {
    Map ids = [sensor: sensorFaultSwitch?.id?.toString(), load: loadFaultSwitch?.id?.toString(),
               limit: limitFaultSwitch?.id?.toString(), rise: riseFaultSwitch?.id?.toString()]
    Map targets = alertSwitchTargets(ids, faults())
    Map<String, DeviceWrapper> devs = [:]
    for (DeviceWrapper d : [sensorFaultSwitch, loadFaultSwitch, limitFaultSwitch, riseFaultSwitch]) { if (d) devs[d.id.toString()] = d }
    devs.each { String id, DeviceWrapper d ->
        String want = targets[id] as String
        if (d.currentValue("switch") != want) {
            logCmd "${d.displayName} ${want}"
            if (want == "on") d.on() else d.off()
        }
    }
}

// ── Temperature ───────────────────────────────────────────────────────

void sensorHandler(evt) {
    checkVersion()
    logEvt "${evt.device.displayName} temperature ${evt.value}"
    noteActivity(evt.device.id.toString(), now())
    updateTemperature()
}

private void noteActivity(String id, Long at) {
    Map all = (state.sensors ?: [:]) as Map
    all[id] = recordActivity(all[id] as Map, at, KEEP_GAPS)
    state.sensors = all
}

private void sampleActivity() {
    (sensors ?: []).each { DeviceWrapper d ->
        Date la = d.getLastActivity()
        if (la != null) noteActivity(d.id.toString(), la.time)
    }
}

private Long limitFor(String id) {
    Map s = ((state.sensors ?: [:]) as Map)[id] as Map
    return quietLimitMs((s?.gaps ?: []) as List, ((quietAfter ?: 120) as Long) * minuteMs(), QUIET_FLOOR_MIN * minuteMs(), MIN_GAPS)
}

void updateTemperature() {
    Long t = now()
    Map all = (state.sensors ?: [:]) as Map
    List<DeviceWrapper> live = []
    List<String> quietNames = []
    (sensors ?: []).each { DeviceWrapper d ->
        String id = d.id.toString()
        if (isQuiet(all[id] as Map, t, limitFor(id))) quietNames << (d.displayName as String)
        else live << d
    }
    BigDecimal temp = aggregateTemp(live.collect { it.currentValue("temperature") }, (aggregate ?: "average") as String)
    state.temp = temp
    boolean sensorWas = faults().sensor == true
    noteQuiet(quietNames, temp == null)
    boolean frostChanged = noteFrost(temp)
    if (temp == null) { applyLoads(); return }
    logDebug "temperature ${temp} from ${live.size()} sensors"
    thermostatDevice()?.setTemperature(temp)
    if (frostChanged || sensorWas) applyLoads()
    evaluateRise()
}

private void noteQuiet(List<String> quietNames, boolean noTemp) {
    List<String> names = (sensors ?: []).collect { it.displayName as String }
    Map d = listDiff(((state.quiet ?: []) as List).findAll { names.contains(it) }, quietNames)
    state.quiet = quietNames.sort()
    if (noTemp) {
        if (!faults().sensor) state.cycleStart = now()
        setFault("sensor", true, namesText(names), policyText((onSensorFault ?: "off") as String, cycleDutyPercent ?: 30, cyclePeriodMinutes ?: 30))
        return
    }
    if (faults().sensor) {
        state.cycleStart = null
        unschedule("cycleTick")
        setFault("sensor", false, namesText(names - quietNames))
        return
    }
    if (notifyPartial == false) return
    if (d.added) sendAlert(faultMessage(app.getLabel(), "quiet", true, namesText(d.added as List), ""))
    if (d.removed) sendAlert(faultMessage(app.getLabel(), "quiet", false, namesText(d.removed as List), ""))
}

// Frost protection needs a live reading: with no temperature it is off and the sensor-fault choice applies.
private boolean noteFrost(BigDecimal temp) {
    boolean was = state.frost == true
    boolean on = frostStep(was, temp, decOrNull(frostTemp))
    state.frost = on
    if (on == was) return false
    String msg = faultMessage(app.getLabel(), "frost", on, tempText(temp, location.temperatureScale as String), "")
    if (on) logWarn(msg) else logInfo(msg)
    sendAlert(msg)
    return true
}

// ── Heaters ───────────────────────────────────────────────────────────

void opStateHandler(evt) {
    checkVersion()
    logEvt "thermostat ${evt.name} ${evt.value}"
    applyLoads()
}

private Integer verifySeconds() { ((verifyTimeout ?: 30) as Integer) }

// A pending command expires after two verification windows, so a lost check can't leave a heater unmanaged.
private Long pendingWindowMs() { 2000L * verifySeconds() }

// Used when the configuration is unusable: the app can't control the heaters, so it leaves them off.
private void heatersOff() {
    (heaters ?: []).each { DeviceWrapper dev -> if (dev.currentValue("switch") != "off") dev.off() }
}

private void sendHeater(DeviceWrapper d, String want, Integer attempt) {
    logCmd "${d.displayName} ${want} (command ${attempt})"
    if (want == "on") d.on() else d.off()
    runIn(verifySeconds(), "verifyHeaters")
}

// Re-applies the wanted state to every heater; also the 5-minute retry while a load fault is raised.
void applyLoads() {
    DeviceWrapper th = thermostatDevice()
    if (th == null) { logError "no thermostat"; heatersOff(); return }
    Long t = now()
    boolean sf = faults().sensor == true
    String policy = (onSensorFault ?: "off") as String
    Long restUntil = state.restUntil as Long
    boolean resting = restUntil != null && t < restUntil
    if (resting) runIn(secondsUntil(restUntil), "restEnd")
    else state.restUntil = null
    if (sf && state.cycleStart == null) state.cycleStart = t
    Map cyc = null
    if (sf && policy == "cycle") {
        cyc = cyclePhase(t, state.cycleStart as Long, ((cyclePeriodMinutes ?: 30) as Long) * minuteMs(), (cycleDutyPercent ?: 30) as Integer)
        runIn(secondsUntil(cyc.next as Long), "cycleTick")
    }
    Map d = decideLoad([frost: state.frost == true && !sf, mode: th.currentValue("thermostatMode"), opState: th.currentValue("thermostatOperatingState"),
                        sensorFault: sf, policy: policy, cycleOn: cyc?.on, resting: resting])
    String before = state.wanted as String
    Map h = holdForMinTimes(d.want as String, before, d.why as String, state.lastChange as Long, t, minutesMs(minOnMinutes), minutesMs(minOffMinutes))
    if (h.until != null) runIn(secondsUntil(h.until as Long), "holdEnd")
    String want = h.want as String
    if (want != before) state.lastChange = t
    state.wanted = want
    trackOnTime(want, before, d.why as String, t)
    if (sf) {
        String op = want == "on" ? "heating" : "idle"
        if (th.currentValue("thermostatOperatingState") != op) th.setThermostatOperatingState(op)
    }
    Map att = (state.attempts ?: [:]) as Map
    Map un = (state.unresponsive ?: [:]) as Map
    (heaters ?: []).each { DeviceWrapper dev ->
        String id = dev.id.toString()
        if (dev.currentValue("switch") == want) { att.remove(id); un.remove(id) }
        else if (!pendingFor(att[id] as Map, want, t, pendingWindowMs())) { att[id] = [want: want, n: 1, at: t]; sendHeater(dev, want, 1) }
    }
    state.attempts = att
    state.unresponsive = un
    evaluatePower()
    updateLoadFault()
}

// onSince starts every on run (for the warming check); limitSince counts only runs that are not frost
// protection, so frost never counts toward the heating time limit.
private void trackOnTime(String want, String before, String why, Long t) {
    if (want == "on") {
        if (before != "on" || state.onSince == null) {
            state.onSince = t
            state.startTemp = state.temp
        }
        if (why == "frost") {
            state.limitSince = null
            unschedule("limitTick")
        } else {
            if (state.limitSince == null) state.limitSince = t
            Long maxMs = minutesMs(maxHeatMinutes)
            if (maxMs != null) runIn(secondsUntil((state.limitSince as Long) + maxMs), "limitTick")
        }
        Long riseMs = minutesMs(noRiseMinutes)
        if (riseMs != null) runIn(secondsUntil((state.onSince as Long) + riseMs), "riseTick")
        return
    }
    state.onSince = null
    state.limitSince = null
    state.startTemp = null
    unschedule("limitTick")
    unschedule("riseTick")
    if (!(state.restUntil != null && t < (state.restUntil as Long)) && faults().limit) setFault("limit", false, "")
    setFault("rise", false, "")
}

void limitTick() {
    checkVersion()
    Long t = now()
    if (state.frost || state.wanted != "on" || !overLimit(state.limitSince as Long, t, minutesMs(maxHeatMinutes))) return
    Long restMin = (restMinutes ?: 15) as Long
    state.restUntil = t + restMin * minuteMs()
    setFault("limit", true, numOrNull(maxHeatMinutes).toString(), restMin.toString())
    applyLoads()
}

void restEnd() { checkVersion(); applyLoads() }

void cycleTick() { checkVersion(); applyLoads() }

void holdEnd() { checkVersion(); applyLoads() }

private void scheduleKeepAlive() {
    Long ms = minutesMs(keepAliveMinutes)
    if (ms != null) runIn(secondsUntil(now() + ms), "keepAliveTick")
}

// Re-sends the wanted state to heaters that already report it, for switches that lose state silently.
void keepAliveTick() {
    checkVersion()
    scheduleKeepAlive()
    if (thermostatDevice() == null) return
    String want = state.wanted as String
    Map att = (state.attempts ?: [:]) as Map
    if (want != null) {
        (heaters ?: []).each { DeviceWrapper dev ->
            if (att.containsKey(dev.id.toString())) return
            logDebug "keep-alive ${dev.displayName} ${want}"
            if (want == "on") dev.on() else dev.off()
        }
    }
}

void heaterHandler(evt) {
    checkVersion()
    String want = state.wanted as String
    logEvt "${evt.device.displayName} ${evt.value}"
    if (want == null) return
    String id = evt.device.id.toString()
    Map att = (state.attempts ?: [:]) as Map
    Map un = (state.unresponsive ?: [:]) as Map
    if (evt.value == want) {
        att.remove(id)
        un.remove(id)
    } else if (!pendingFor(att[id] as Map, want, now(), pendingWindowMs())) {
        logWarn "${evt.device.displayName} turned ${evt.value} without the app; setting it back to ${want}"
        att[id] = [want: want, n: 1, at: now()]
        sendHeater(evt.device, want, 1)
    }
    state.attempts = att
    state.unresponsive = un
    evaluatePower()
    updateLoadFault()
}

// Checks each pending command against the state it asked for; a newer wanted state replaces the entry in applyLoads.
void verifyHeaters() {
    checkVersion()
    Map att = (state.attempts ?: [:]) as Map
    Map un = (state.unresponsive ?: [:]) as Map
    (heaters ?: []).each { DeviceWrapper dev ->
        String id = dev.id.toString()
        Map a = att[id] as Map
        if (a == null) return
        String want = a.want as String
        String cur = dev.currentValue("switch") as String
        Integer n = a.n as Integer
        String out = verifyOutcome(want, cur, n, MAX_ATTEMPTS)
        if (out == "ok") { att.remove(id); un.remove(id) }
        else if (out == "retry") { att[id] = [want: want, n: n + 1, at: now()]; sendHeater(dev, want, n + 1) }
        else { att.remove(id); un[id] = "${dev.displayName} (commanded ${want}, reads ${cur})".toString() }
    }
    state.attempts = att
    state.unresponsive = un
    updateLoadFault()
}

private void updateLoadFault() {
    List<String> details = []
    for (Object v : ((state.unresponsive ?: [:]) as Map).values()) details << (v as String)
    for (Object v : ((state.powerFault ?: [:]) as Map).values()) details << (v as String)
    setFault("load", !details.isEmpty(), namesText(details))
}

// ── Power ─────────────────────────────────────────────────────────────

void powerHandler(evt) {
    checkVersion()
    logEvt "${evt.device.displayName} ${evt.value} W"
    evaluatePower()
    updateLoadFault()
}

void powerTick() {
    checkVersion()
    evaluatePower()
    updateLoadFault()
}

private void evaluatePower() {
    Map bad = [:]
    Map fault = [:]
    if (usePower != false) {
        Long t = now()
        Long grace = POWER_GRACE_MIN * minuteMs()
        Map prev = (state.powerBad ?: [:]) as Map
        BigDecimal minW = (minPowerW ?: 20) as BigDecimal
        Long next = null
        (heaters ?: []).each { DeviceWrapper d ->
            if (!d.hasAttribute("power")) return
            String id = d.id.toString()
            String sw = d.currentValue("switch") as String
            Object p = d.currentValue("power")
            Map st = powerStep(prev[id] as Long, powerVerdict(sw, p, minW), t, grace)
            if (st.badSince == null) return
            bad[id] = st.badSince
            if (st.fault) fault[id] = powerDetail(d.displayName as String, sw, p)
            else {
                Long due = (st.badSince as Long) + grace
                if (next == null || due < next) next = due
            }
        }
        if (next != null) runIn(secondsUntil(next), "powerTick")
    }
    state.powerBad = bad
    state.powerFault = fault
}

// ── Warming ───────────────────────────────────────────────────────────

void riseTick() {
    checkVersion()
    evaluateRise()
}

// Raised while the heaters run without warming the room; cleared once it warms or the heaters turn off.
private void evaluateRise() {
    Long windowMs = minutesMs(noRiseMinutes)
    if (windowMs == null) { setFault("rise", false, ""); return }
    if (state.wanted != "on" || state.temp == null) return
    if (state.startTemp == null) {
        // The run began with no reading (a sensor fault, or code pushed mid-run): the window starts now.
        state.startTemp = state.temp
        state.onSince = now()
        runIn(secondsUntil(now() + windowMs), "riseTick")
        return
    }
    BigDecimal minRise = decOrNull(minRiseDegrees) ?: 0.3
    if (noRise(state.onSince as Long, now(), state.startTemp, state.temp, windowMs, minRise)) {
        setFault("rise", true, numOrNull(noRiseMinutes).toString(), riseDetail(state.startTemp, state.temp, minRise, location.temperatureScale as String))
    } else if (riseOk(state.startTemp, state.temp, minRise)) {
        setFault("rise", false, "")
    }
}

// ── Core (pure) ───────────────────────────────────────────────────────
// No settings, state, devices, logging or @Field here: tests/test_core.groovy runs this block off-hub.

BigDecimal aggregateTemp(List values, String how) {
    List<BigDecimal> v = []
    for (Object x : values) { if (x != null) v << new BigDecimal(x.toString()) }
    if (v.isEmpty()) return null
    BigDecimal r
    if (how == 'minimum') {
        r = v[0]
        for (BigDecimal x : v) { if (x < r) r = x }
    } else {
        BigDecimal sum = 0
        for (BigDecimal x : v) sum += x
        r = sum.divide(new BigDecimal(v.size()), 6, BigDecimal.ROUND_HALF_UP)
    }
    return r.setScale(1, BigDecimal.ROUND_HALF_UP)
}

Map recordActivity(Map s, Long at, Integer keep) {
    Long last = (s?.last != null) ? (s.last as Long) : null
    List<Long> gaps = []
    for (Object g : (List) (s?.gaps ?: [])) gaps << (g as Long)
    if (at == null || (last != null && at <= last)) return [last: last, gaps: gaps]
    if (last != null) {
        gaps << (at - last)
        while (gaps.size() > keep) gaps.remove(0)
    }
    return [last: at, gaps: gaps]
}

// Twice the second-longest gap: one outage in the history does not stretch the limit.
Long quietLimitMs(List gaps, Long fallbackMs, Long floorMs, Integer minGaps) {
    if (gaps == null || gaps.size() < minGaps) return fallbackMs
    List<Long> sorted = []
    for (Object g : gaps) sorted << (g as Long)
    sorted.sort()
    return Math.max(floorMs, 2L * sorted[sorted.size() - 2])
}

boolean isQuiet(Map s, Long now, Long limitMs) {
    if (s?.last == null) return true
    return now - (s.last as Long) > limitMs
}

Long numOrNull(Object v) {
    if (v == null) return null
    String t = v.toString().trim()
    return t.isNumber() ? (t as BigDecimal).longValue() : null
}

BigDecimal decOrNull(Object v) {
    if (v == null) return null
    String t = v.toString().trim()
    return t.isNumber() ? new BigDecimal(t) : null
}

// s: frost, mode, opState, sensorFault, policy ('off' | 'on' | 'cycle'), cycleOn, resting.
// Returns the wanted state and why: 'frost', 'mode', 'rest', 'sensor' or 'thermostat'.
Map decideLoad(Map s) {
    if (s.frost == true) return [want: 'on', why: 'frost']
    if (s.mode != 'heat') return [want: 'off', why: 'mode']
    if (s.resting == true) return [want: 'off', why: 'rest']
    if (s.sensorFault == true) {
        boolean on = s.policy == 'on' || (s.policy == 'cycle' && s.cycleOn == true)
        return [want: on ? 'on' : 'off', why: 'sensor']
    }
    return [want: s.opState == 'heating' ? 'on' : 'off', why: 'thermostat']
}

// Minimum on and off times delay only the thermostat's own changes; every other reason acts at once.
// Returns the state to apply and, while held, when the hold ends.
Map holdForMinTimes(String want, String before, String why, Long lastChange, Long now, Long minOnMs, Long minOffMs) {
    if (why != 'thermostat' || before == null || want == before || lastChange == null) return [want: want, until: null]
    Long min = before == 'on' ? minOnMs : minOffMs
    if (min == null || min <= 0L) return [want: want, until: null]
    Long until = lastChange + min
    if (now >= until) return [want: want, until: null]
    return [want: before, until: until]
}

// On below frostTemp, off again at frostTemp + 1 degree; off with no reading or no setting.
boolean frostStep(boolean active, Object temp, Object frostTemp) {
    if (temp == null || frostTemp == null) return false
    BigDecimal t = new BigDecimal(temp.toString()), f = new BigDecimal(frostTemp.toString())
    return active ? t < f + 1 : t < f
}

// A command for `want` is still pending (sent, unconfirmed, not expired); entry: [want, n, at].
boolean pendingFor(Map entry, String want, Long now, Long windowMs) {
    if (entry == null || entry.want != want || entry.at == null) return false
    return now - (entry.at as Long) < windowMs
}

String verifyOutcome(String wanted, String current, Integer attempts, Integer maxAttempts) {
    if (current == wanted) return 'ok'
    return attempts < maxAttempts ? 'retry' : 'fault'
}

// The cycle starts with its on phase at `start`; `next` is when the phase changes.
Map cyclePhase(Long now, Long start, Long periodMs, Integer dutyPct) {
    Long s = (start != null) ? start : now
    Long onMs = (periodMs * dutyPct).intdiv(100) as Long
    Long pos = (now - s) % periodMs
    boolean on = pos < onMs
    return [on: on, next: now + (on ? onMs - pos : periodMs - pos)]
}

boolean overLimit(Long onSince, Long now, Long maxMs) {
    if (maxMs == null || onSince == null) return false
    return now - onSince >= maxMs
}

boolean riseOk(Object startTemp, Object temp, Object minRise) {
    if (startTemp == null || temp == null) return false
    return new BigDecimal(temp.toString()) - new BigDecimal(startTemp.toString()) >= new BigDecimal(minRise.toString())
}

boolean noRise(Long onSince, Long now, Object startTemp, Object temp, Long windowMs, Object minRise) {
    if (windowMs == null || onSince == null || startTemp == null || temp == null) return false
    if (now - onSince < windowMs) return false
    return !riseOk(startTemp, temp, minRise)
}

String tempText(Object t, String scale) {
    if (t == null) return 'no temperature'
    return "${new BigDecimal(t.toString()).setScale(1, BigDecimal.ROUND_HALF_UP).toPlainString()} °${scale}".toString()
}

String riseDetail(Object startTemp, Object temp, Object minRise, String scale) {
    BigDecimal r = new BigDecimal(temp.toString()) - new BigDecimal(startTemp.toString())
    return "${tempText(r, scale)} (expected ${tempText(minRise, scale)})".toString()
}

String powerVerdict(String sw, Object power, Object minW) {
    if (power == null) return 'unknown'
    BigDecimal p = new BigDecimal(power.toString())
    BigDecimal m = new BigDecimal(minW.toString())
    if (sw == 'on' && p < m) return 'noDraw'
    if (sw == 'off' && p >= m) return 'drawWhileOff'
    return 'ok'
}

// A mismatch counts as a fault once it has lasted graceMs.
Map powerStep(Long badSince, String verdict, Long now, Long graceMs) {
    if (verdict == 'ok' || verdict == 'unknown') return [badSince: null, fault: false]
    Long since = (badSince != null) ? badSince : now
    return [badSince: since, fault: now - since >= graceMs]
}

String powerDetail(String label, String sw, Object power) {
    BigDecimal p = new BigDecimal(power.toString()).setScale(0, BigDecimal.ROUND_HALF_UP)
    return "${label} (${sw}, draws ${p.toPlainString()} W)".toString()
}

Map alertSwitchTargets(Map ids, Map faults) {
    Map out = [:]
    for (String kind : ['sensor', 'load', 'limit', 'rise']) {
        String id = ids?.get(kind) as String
        if (!id) continue
        boolean on = faults?.get(kind) == true
        if (on || !out.containsKey(id)) out[id] = on ? 'on' : 'off'
    }
    return out
}

// rows: [label, available, enabled] per heater. Names the heaters that could use Command Retry but don't.
List<String> retryAdvice(List rows) {
    List<String> out = []
    for (Object r : rows) {
        Map m = r as Map
        if (m.available == true && m.enabled != true) out << (m.label as String)
    }
    return out.sort()
}

Map listDiff(List before, List after) {
    List b = (before ?: []) as List, a = (after ?: []) as List
    return [added: (a - b).sort(), removed: (b - a).sort()]
}

String namesText(List names) {
    List<String> n = []
    for (Object x : names) n << x.toString()
    return n.sort().join(', ')
}

String policyText(String policy, Object duty, Object period) {
    if (policy == 'on') return 'Heaters kept on'
    if (policy == 'cycle') return "Heaters on ${duty}% of every ${period} minutes".toString()
    return 'Heat is off'
}

// kind: 'sensor', 'load', 'limit', 'rise', 'frost' or 'quiet'. `extra` is the sensor policy text,
// the rest minutes or the rise detail.
String faultMessage(String app, String kind, boolean raised, String detail, String extra) {
    if (kind == 'sensor') return raised ? "${app}: no temperature, all sensors quiet (${detail}). ${extra}.".toString() : "${app}: temperature back (${detail}).".toString()
    if (kind == 'load') return raised ? "${app}: lost control of ${detail}.".toString() : "${app}: heaters under control again.".toString()
    if (kind == 'limit') return raised ? "${app}: heaters on for ${detail} minutes, the limit. Off for ${extra} minutes.".toString() : "${app}: heating time back to normal.".toString()
    if (kind == 'rise') return raised ? "${app}: heaters on for ${detail} minutes and the temperature rose ${extra}.".toString() : "${app}: warming check back to normal.".toString()
    if (kind == 'frost') return raised ? "${app}: frost protection on (${detail}).".toString() : "${app}: frost protection off (${detail}).".toString()
    return raised ? "${app}: ${detail} quiet, left out of the temperature.".toString() : "${app}: ${detail} reporting again.".toString()
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

void logsOff() { checkVersion(); app.updateSetting("debugEnable", false); logWarn "debug logging disabled" }

// Copyright (c) 2025-2026 PJ
// SPDX-License-Identifier: MIT

import groovy.transform.Field
import groovy.transform.CompileStatic

@Field static final String CODE_VERSION = "0.2.2"

// Attributes (beyond the universal "motion") that some presence sensors expose.
// Subscribed opportunistically per device and surfaced as extra context.
@Field static final List<String> AUX_ATTRS =
    ["roomState", "presence", "pirDetection", "distance", "targetDistance", "existanceTime"]

definition(
    name: "mmWave Sensor Comparison",
    namespace: "iamtrep",
    author: "pj",
    // Co-located sensors fire within ms of each other; serialize handler
    // execution so the state read-modify-write in the accumulators is safe.
    singleThreaded: true,
    description: "Subscribes to several co-located presence/motion sensors and derives comparative metrics: activation latency, agreement, and sustained-occupancy hold.",
    category: "Utility",
    importUrl: "https://raw.githubusercontent.com/iamtrep/hubitat/refs/heads/main/apps/mmWaveSensorComparison.groovy",
    iconUrl: "",
    iconX2Url: ""
)

preferences {
    page(name: "mainPage")
}

// ─── Lifecycle ──────────────────────────────────────────────────────────────

void installed() {
    logDebug "installed()"
    initialize()
}

void updated() {
    logDebug "updated()"
    unsubscribe()
    unschedule()
    initialize()
}

void uninstalled() {
    logDebug "uninstalled()"
}

void initialize() {
    logDebug "initialize()"
    checkVersion()

    if (state.observingSince == null) state.observingSince = now()
    if (state.stats == null)         state.stats = [:]
    if (state.agg == null)           state.agg = [allActive: 0L, allClear: 0L, mixed: 0L]
    if (state.pairAgg == null)       state.pairAgg = [:]
    if (state.recent == null)        state.recent = []
    if (state.extras == null)        state.extras = [:]
    if (state.totalWaves == null)    state.totalWaves = 0
    if (state.waves == null)         state.waves = []
    if (state.devActiveWave == null) state.devActiveWave = [:]

    seedBaseline()

    subscribe(compareDevices, "motion", "motionHandler")
    compareDevices?.each { dev ->
        AUX_ATTRS.each { String a ->
            if (dev.hasAttribute(a)) subscribe(dev, a, "auxHandler")
        }
    }

    if (debugEnable) runIn(1800, "logsOff")
}

private void checkVersion() {
    if (state.version != CODE_VERSION) {
        logInfo "version ${state.version} -> ${CODE_VERSION}"
        state.version = CODE_VERSION
    }
}

// Refresh per-device stats entries + reset the agreement baseline to the
// devices' *current* motion state. Does NOT wipe accumulated counters, so a
// plain Done/Update is safe.
private void seedBaseline() {
    Map cs = [:]
    compareDevices?.each { dev ->
        String id = dev.id.toString()
        ensureStats(id, dev.displayName)
        String mv = dev.currentValue("motion")
        boolean active = (mv == "active")
        cs[id] = active
        Map stats = state.stats as Map
        Map s = stats[id] as Map
        s.curActive = active
        s.lastActiveTs = active ? now() : null
        stats[id] = s
        state.stats = stats
    }
    state.curStates = cs
    state.aggLastTs = now()
}

private void ensureStats(String id, String label) {
    Map stats = (state.stats as Map) ?: [:]
    if (!stats.containsKey(id)) {
        stats[id] = [
            label: label, activations: 0, deactivations: 0,
            curActive: false, lastActiveTs: null,
            totalActive: 0L, longestActive: 0L,
            leadCount: 0, offsetSum: 0L, offsetCount: 0, wavesJoined: 0
        ]
    } else {
        Map s = stats[id] as Map
        s.label = label
    }
    state.stats = stats
}

// ─── Event handlers ─────────────────────────────────────────────────────────

void motionHandler(evt) {
    String devId = evt.deviceId.toString()
    String label = evt.displayName
    String val = evt.value
    long t = eventTime(evt)
    logTrace "motion ${label}=${val} unixTime=${evt.unixTime} now=${now()} -> t=${t}"

    ensureStats(devId, label)
    recordRecent(t, label, "motion", val)

    boolean active = (val == "active")

    // Integrate agreement over the interval that just ended, then update state.
    integrateAgreement(t)
    Map cs = (state.curStates as Map) ?: [:]
    // Snapshot before mutating cs: if any *other* sensor is already active,
    // the prior wave's real-world motion event is still ongoing — a fresh
    // activation here is a delayed joiner, not a new "1st".
    boolean ongoing = cs.any { k, v -> k != devId && (v as boolean) }
    cs[devId] = active
    state.curStates = cs

    Map stats = state.stats as Map
    Map s = stats[devId] as Map
    if (active) {
        if (!(s.curActive as boolean)) {
            s.curActive = true
            s.lastActiveTs = t
            s.activations = ((s.activations ?: 0) as int) + 1
        }
        stats[devId] = s
        state.stats = stats
        recordActivationWave(devId, t, ongoing)
    } else {
        if (s.curActive as boolean) {
            s.curActive = false
            long la = (s.lastActiveTs ?: t) as long
            long dur = Math.max(0L, t - la)
            s.totalActive = ((s.totalActive ?: 0L) as long) + dur
            if (dur > ((s.longestActive ?: 0L) as long)) s.longestActive = dur
            s.deactivations = ((s.deactivations ?: 0) as int) + 1
            s.lastActiveTs = null
            fillWaveHold(devId, t)
        }
        stats[devId] = s
        state.stats = stats
    }
}

void auxHandler(evt) {
    long t = eventTime(evt)
    String devId = evt.deviceId.toString()
    recordRecent(t, evt.displayName, evt.name, evt.value)
    Map ex = (state.extras as Map) ?: [:]
    Map de = (ex[devId] as Map) ?: [:]
    de[evt.name as String] = [val: evt.value, t: t]
    ex[devId] = de
    state.extras = ex

    // While a sensor is active in a wave, fold its radar distance into that
    // wave so each event shows the target's distance spread (fixed = likely
    // noise, roaming = likely a real moving target).
    if (evt.name in ["distance", "targetDistance"]) updateWaveDistance(devId, evt.value as String)
}

// Group near-simultaneous activations into a "wave". Drives both the aggregate
// stats (via bumpWave: leadCount / offset / wavesJoined) and a per-wave record
// in state.waves so each correlated event can be inspected on its own:
//   {n, t0, leaderId, dev:{ devId:{off, holdMs} }}
// off = ms behind the wave's first detection; holdMs filled when that device
// later goes inactive (fillWaveHold). A device absent from .dev missed the wave.
private void recordActivationWave(String devId, long t, boolean ongoing) {
    int win = (settings.corrWindowMs ?: 5000) as int
    List waves = (state.waves as List) ?: []
    Map cur = waves ? (waves[-1] as Map) : null
    Long t0 = cur != null ? (cur.t0 as Long) : null

    // A new wave only starts when the prior wave's window has expired AND no
    // sensor is still actively holding. Otherwise the current real-world
    // motion event is still in progress — fold this activation in as a
    // delayed joiner so late starters don't get credited as "1st".
    if (t0 == null || ((t - t0) > win && !ongoing)) {
        // New wave — this sensor fired first (leader).
        int n = ((state.totalWaves ?: 0) as int) + 1
        state.totalWaves = n
        waves << [n: n, t0: t, leaderId: devId, dev: [(devId): [off: 0L, holdMs: null]]]
        int cap = 30
        if (waves.size() > cap) waves = waves[-cap..-1]
        state.waves = waves
        bumpWave(devId, 0L, true)
        setActiveWave(devId, n)
    } else {
        Map dev = (cur.dev as Map)
        if (!dev.containsKey(devId)) {
            long off = t - t0
            dev[devId] = [off: off, holdMs: null]
            cur.dev = dev
            waves[-1] = cur
            state.waves = waves
            bumpWave(devId, off, false)
            setActiveWave(devId, cur.n as int)
        }
    }
}

// Remember which wave a device is currently active in, so its later inactive
// event can be attributed back to the right wave for the hold duration.
private void setActiveWave(String devId, int n) {
    Map daw = (state.devActiveWave as Map) ?: [:]
    daw[devId] = n
    state.devActiveWave = daw
}

private void fillWaveHold(String devId, long t) {
    Map daw = (state.devActiveWave as Map) ?: [:]
    Integer n = daw[devId] as Integer
    if (n == null) return
    List waves = (state.waves as List) ?: []
    for (int i = waves.size() - 1; i >= 0; i--) {
        Map w = waves[i] as Map
        if ((w.n as int) == n) {
            Map dev = w.dev as Map
            Map d = dev[devId] as Map
            if (d != null && d.holdMs == null) {
                long activeTs = (w.t0 as long) + ((d.off ?: 0L) as long)
                d.holdMs = Math.max(0L, t - activeTs)
                dev[devId] = d
                w.dev = dev
                waves[i] = w
                state.waves = waves
            }
            break
        }
    }
    daw.remove(devId)
    state.devActiveWave = daw
}

// Fold one radar distance reading into the device's current wave: min / max /
// last / sample count, so the per-event view can show the distance spread.
private void updateWaveDistance(String devId, String valStr) {
    BigDecimal v
    try { v = valStr as BigDecimal } catch (ignored) { return }
    Map daw = (state.devActiveWave as Map) ?: [:]
    Integer n = daw[devId] as Integer
    if (n == null) return
    List waves = (state.waves as List) ?: []
    for (int i = waves.size() - 1; i >= 0; i--) {
        Map w = waves[i] as Map
        if ((w.n as int) == n) {
            Map dev = w.dev as Map
            Map d = dev[devId] as Map
            if (d != null) {
                d.dLast = v
                d.dMin = (d.dMin == null) ? v : (v < (d.dMin as BigDecimal) ? v : d.dMin)
                d.dMax = (d.dMax == null) ? v : (v > (d.dMax as BigDecimal) ? v : d.dMax)
                d.dN = ((d.dN ?: 0) as int) + 1
                dev[devId] = d
                w.dev = dev
                waves[i] = w
                state.waves = waves
            }
            break
        }
    }
}

private void bumpWave(String devId, long offset, boolean leader) {
    Map stats = state.stats as Map
    Map s = stats[devId] as Map
    s.offsetSum = ((s.offsetSum ?: 0L) as long) + offset
    s.offsetCount = ((s.offsetCount ?: 0) as int) + 1
    s.wavesJoined = ((s.wavesJoined ?: 0) as int) + 1
    if (leader) s.leadCount = ((s.leadCount ?: 0) as int) + 1
    stats[devId] = s
    state.stats = stats
}

private void integrateAgreement(long t) {
    Long last = state.aggLastTs as Long
    Map cs = (state.curStates as Map) ?: [:]
    if (last != null && !cs.isEmpty()) {
        long dt = Math.max(0L, t - last)
        int n = cs.size()
        int nActive = cs.values().count { it } as int

        Map agg = (state.agg as Map) ?: [allActive: 0L, allClear: 0L, mixed: 0L]
        if (nActive == n)        agg.allActive = ((agg.allActive ?: 0L) as long) + dt
        else if (nActive == 0)   agg.allClear  = ((agg.allClear ?: 0L) as long) + dt
        else                     agg.mixed     = ((agg.mixed ?: 0L) as long) + dt
        state.agg = agg

        List<String> ids = (cs.keySet() as List).sort()
        Map pa = (state.pairAgg as Map) ?: [:]
        for (int i = 0; i < ids.size(); i++) {
            for (int j = i + 1; j < ids.size(); j++) {
                String key = "${ids[i]}|${ids[j]}".toString()
                Map p = (pa[key] as Map) ?: [agree: 0L, disagree: 0L]
                if (cs[ids[i]] == cs[ids[j]]) p.agree = ((p.agree ?: 0L) as long) + dt
                else                          p.disagree = ((p.disagree ?: 0L) as long) + dt
                pa[key] = p
            }
        }
        state.pairAgg = pa
    }
    state.aggLastTs = t
}

// Event.getUnixTime() is the documented, reliable epoch-ms timestamp for when
// the hub recorded the device event — more accurate than handler-execution
// now() (handlers can be queued). See docs2.hubitat.com event-object.
private long eventTime(evt) {
    try {
        Long ut = evt.unixTime as Long
        if (ut != null) return ut
    } catch (ignored) { /* fall through */ }
    return now()
}

private void recordRecent(long t, String dev, String name, String val) {
    List r = (state.recent as List) ?: []
    r << [t: t, dev: dev, name: name, val: val]
    int cap = 60
    if (r.size() > cap) r = r[-cap..-1]
    state.recent = r
}

// ─── Buttons ────────────────────────────────────────────────────────────────

void appButtonHandler(String btn) {
    if (btn == "resetStats") resetStats()
}

private void resetStats() {
    state.stats = [:]
    state.agg = [allActive: 0L, allClear: 0L, mixed: 0L]
    state.pairAgg = [:]
    state.recent = []
    state.extras = [:]
    state.totalWaves = 0
    state.waves = []
    state.devActiveWave = [:]
    state.observingSince = now()
    seedBaseline()
    logInfo "statistics reset"
}

// ─── UI ─────────────────────────────────────────────────────────────────────

Map mainPage() {
    dynamicPage(name: "mainPage", title: "mmWave Sensor Comparison v${CODE_VERSION}", install: true, uninstall: true) {
        section("App Name", hideable: true, hidden: true) {
            label title: "Set App Label", required: false
        }
        section("Sensors to compare") {
            input "compareDevices", "capability.motionSensor",
                title: "Co-located presence/motion sensors",
                required: true, multiple: true, submitOnChange: true
            input "corrWindowMs", "number",
                title: "Activation correlation window (ms)",
                description: "Activations across sensors within this window count as the same real-world event",
                required: true, defaultValue: 5000, range: "250..60000"
        }

        if (compareDevices) {
            section("Comparison") {
                paragraph renderObserving()
                paragraph renderVerdict()
                paragraph renderSummaryTable()
                input "resetStats", "button", title: "Reset statistics"
            }
            section("Per-event breakdown", hideable: true, hidden: false) {
                paragraph renderWaves()
            }
            section("Agreement (share of observed time)", hideable: true, hidden: false) {
                paragraph renderAgreement()
            }
            section("Recent events", hideable: true, hidden: true) {
                paragraph renderRecent()
            }
        }

        section("Logging", hideable: true, hidden: true) {
            input name: "txtEnable", type: "bool", title: "Enable descriptionText logging", defaultValue: true
            input name: "debugEnable", type: "bool", title: "Enable debug logging", defaultValue: false, submitOnChange: true
            if (debugEnable) {
                input name: "traceEnable", type: "bool", title: "Enable trace logging (per-event timestamps)", defaultValue: false
            }
        }
    }
}

private String renderObserving() {
    long since = (state.observingSince ?: now()) as long
    long span = now() - since
    String start = new Date(since).format("yyyy-MM-dd HH:mm:ss", location.timeZone)
    return "Observing since <b>${start}</b> (${formatMs(span)}) — ${state.totalWaves ?: 0} activation wave(s) recorded."
}

private String renderVerdict() {
    Map stats = (state.stats as Map) ?: [:]
    if (stats.isEmpty()) return ""
    int total = (state.totalWaves ?: 0) as int

    // Fastest = most leads, tie-broken by lowest average offset.
    Map fastest = null; String fastestId = null
    Map holder = null; String holderId = null
    Map detector = null; String detectorId = null
    stats.each { k, v ->
        Map s = v as Map
        if (fastest == null || (s.leadCount as int) > (fastest.leadCount as int)) { fastest = s; fastestId = k }
        if (holder == null || (s.longestActive as long) > (holder.longestActive as long)) { holder = s; holderId = k }
        if (detector == null || (s.wavesJoined as int) > (detector.wavesJoined as int)) { detector = s; detectorId = k }
    }
    List<String> lines = []
    if (fastest && (fastest.leadCount as int) > 0)
        lines << "⚡ <b>Fastest to activate:</b> ${fastest.label} (first in ${fastest.leadCount}/${total} waves)"
    if (holder && (holder.longestActive as long) > 0)
        lines << "⏱ <b>Longest sustained hold:</b> ${holder.label} (${formatMs(holder.longestActive as long)})"
    if (detector && total > 0)
        lines << "🎯 <b>Caught the most activations:</b> ${detector.label} (${detector.wavesJoined}/${total})"
    return lines ? lines.join("<br>") : "Not enough data yet — trigger some motion."
}

private String renderSummaryTable() {
    Map stats = (state.stats as Map) ?: [:]
    Map extras = (state.extras as Map) ?: [:]
    int total = (state.totalWaves ?: 0) as int
    if (stats.isEmpty()) return "No sensors selected."

    String td = "style='border:1px solid #999;padding:4px 8px'"
    String tc = "style='border:1px solid #999;padding:4px 8px;text-align:center'"
    String h = "<table style='border-collapse:collapse;width:100%'><thead><tr style='background:#ddd'>" +
        "<th ${td}>Sensor</th><th ${tc}>Motion</th><th ${tc}>Acts</th><th ${tc}>Deacts</th>" +
        "<th ${tc}>Total active</th><th ${tc}>Longest hold</th>" +
        "<th ${tc}>Caught</th><th ${tc}>First×</th><th ${tc}>Avg lag</th><th ${td}>Latest extras</th></tr></thead><tbody>"

    stats.sort { a, b -> (a.value.label as String) <=> (b.value.label as String) }.each { k, v ->
        Map s = v as Map
        String id = k as String
        boolean act = s.curActive as boolean
        String motion = act ? "<span style='color:#c00'><b>active</b></span>" : "<span style='color:#080'>inactive</span>"
        int oc = (s.offsetCount ?: 0) as int
        String avgLag = oc > 0 ? formatMs(((s.offsetSum ?: 0L) as long).intdiv(oc)) : "—"
        String caught = total > 0 ? "${s.wavesJoined ?: 0}/${total}" : "—"

        // live "active since" duration if currently active
        String totalActive = formatMs(s.totalActive as long)
        if (act && s.lastActiveTs) {
            totalActive += " (+${formatMs(now() - (s.lastActiveTs as long))})"
        }

        Map de = (extras[id] as Map) ?: [:]
        List exParts = []
        ["roomState", "pirDetection", "presence", "distance", "targetDistance"].each { String a ->
            if (de[a]) exParts << "${a}=${(de[a] as Map).val}"
        }
        String exStr = exParts ? exParts.join(", ") : "—"

        h += "<tr><td ${td}><a href='/device/edit/${id}'>${s.label}</a></td>" +
            "<td ${tc}>${motion}</td><td ${tc}>${s.activations ?: 0}</td><td ${tc}>${s.deactivations ?: 0}</td>" +
            "<td ${tc}>${totalActive}</td><td ${tc}>${formatMs(s.longestActive as long)}</td>" +
            "<td ${tc}>${caught}</td><td ${tc}>${s.leadCount ?: 0}</td><td ${tc}>${avgLag}</td><td ${td}>${exStr}</td></tr>"
    }
    h += "</tbody></table>" +
        "<div style='font-size:0.85em;color:#666;margin-top:4px'>" +
        "<b>Caught</b> = activation waves this sensor detected. <b>First×</b> = times it fired first. " +
        "<b>Avg lag</b> = mean delay behind the wave's first detection (lower = faster). " +
        "Deactivation timing reflects each sensor's configured hold/fade timeout, not raw speed.</div>"
    return h
}

// One row per correlated activation event: each sensor's order/lag behind the
// first to fire, and how long it then held active. "—" = the sensor missed it.
private String renderWaves() {
    List waves = (state.waves as List) ?: []
    if (waves.isEmpty()) return "No correlated events captured yet — trigger some motion."
    Map labels = labelMap()
    List<String> ids = (labels.keySet() as List).sort { labels[it] }

    String td = "style='border:1px solid #999;padding:4px 8px'"
    String tc = "style='border:1px solid #999;padding:4px 8px;text-align:center'"
    String h = "<table style='border-collapse:collapse;width:100%'><thead><tr style='background:#ddd'>" +
        "<th ${tc}>#</th><th ${td}>Started</th>"
    ids.each { String id -> h += "<th ${tc}>${labels[id]}</th>" }
    h += "</tr></thead><tbody>"

    int show = Math.min(12, waves.size())
    waves.reverse().take(12).each { wv ->
        Map w = wv as Map
        Map dev = (w.dev as Map) ?: [:]
        String tm = new Date(w.t0 as long).format("HH:mm:ss", location.timeZone)
        h += "<tr><td ${tc}>${w.n}</td><td ${td}>${tm}</td>"
        ids.each { String id ->
            Map d = dev[id] as Map
            if (d == null) {
                h += "<td ${tc} title='did not detect'><span style='color:#999'>—</span></td>"
            } else {
                boolean leader = (w.leaderId == id)
                String lag = leader ? "🥇 1st" : "+${formatMs((d.off ?: 0L) as long)}"
                String hold = d.holdMs != null ? "held ${formatMs(d.holdMs as long)}" : "<i>active…</i>"
                String dist = ""
                if (d.dMin != null) {
                    String range = (d.dMin == d.dMax) ? "${d.dMin}m"
                        : "${d.dMin}–${d.dMax}m"
                    dist = "<br><span style='font-size:0.85em;color:#06c'>↔ ${range} (${d.dN ?: 0})</span>"
                }
                h += "<td ${tc}><b>${lag}</b><br><span style='font-size:0.85em;color:#555'>${hold}</span>${dist}</td>"
            }
        }
        h += "</tr>"
    }
    h += "</tbody></table>" +
        "<div style='font-size:0.85em;color:#666;margin-top:4px'>" +
        "Each row is one correlated activation (sensors firing within ${(settings.corrWindowMs ?: 5000)} ms). " +
        "Top of cell = order/lag behind the first sensor; then how long it stayed active; " +
        "<b>↔</b> = radar distance spread (min–max, sample count) — a tight range suggests a fixed noise source, a wide range a moving target. " +
        "<b>—</b> = sensor missed this event. Showing newest ${show} of ${state.totalWaves ?: 0}.</div>"
    return h
}

private String renderAgreement() {
    Map agg = (state.agg as Map) ?: [:]
    long aa = (agg.allActive ?: 0L) as long
    long ac = (agg.allClear ?: 0L) as long
    long mx = (agg.mixed ?: 0L) as long
    long tot = aa + ac + mx
    if (tot <= 0) return "No observed time yet."

    Map labels = labelMap()
    String td = "style='border:1px solid #999;padding:4px 8px'"
    String tc = "style='border:1px solid #999;padding:4px 8px;text-align:center'"

    String s = "All agree — occupied: <b>${pct(aa, tot)}</b> &nbsp; | &nbsp; " +
        "All agree — clear: <b>${pct(ac, tot)}</b> &nbsp; | &nbsp; " +
        "Disagree: <b>${pct(mx, tot)}</b><br><br>"

    Map pa = (state.pairAgg as Map) ?: [:]
    if (!pa.isEmpty()) {
        s += "<table style='border-collapse:collapse'><thead><tr style='background:#ddd'>" +
            "<th ${td}>Pair</th><th ${tc}>Agree</th><th ${tc}>Disagree</th></tr></thead><tbody>"
        pa.each { k, v ->
            Map p = v as Map
            String[] ids = (k as String).split('\\|')
            long ag = (p.agree ?: 0L) as long
            long dg = (p.disagree ?: 0L) as long
            long pt = ag + dg
            String pair = "${labels[ids[0]] ?: ids[0]} ↔ ${labels[ids[1]] ?: ids[1]}"
            s += "<tr><td ${td}>${pair}</td><td ${tc}>${pt > 0 ? pct(ag, pt) : '—'}</td>" +
                "<td ${tc}>${pt > 0 ? pct(dg, pt) : '—'}</td></tr>"
        }
        s += "</tbody></table>"
    }
    return s
}

private String renderRecent() {
    List r = (state.recent as List) ?: []
    if (r.isEmpty()) return "No events captured yet."
    String td = "style='border:1px solid #999;padding:2px 6px'"
    String h = "<table style='border-collapse:collapse'><thead><tr style='background:#ddd'>" +
        "<th ${td}>Time</th><th ${td}>Sensor</th><th ${td}>Attribute</th><th ${td}>Value</th></tr></thead><tbody>"
    r.reverse().take(25).each { e ->
        Map m = e as Map
        String tm = new Date(m.t as long).format("HH:mm:ss", location.timeZone)
        h += "<tr><td ${td}>${tm}</td><td ${td}>${m.dev}</td><td ${td}>${m.name}</td><td ${td}>${m.val}</td></tr>"
    }
    h += "</tbody></table>"
    return h
}

// ─── Pure helpers ───────────────────────────────────────────────────────────

private Map labelMap() {
    Map out = [:]
    ((state.stats as Map) ?: [:]).each { k, v -> out[k] = (v as Map).label }
    return out
}

@CompileStatic
private static String pct(long part, long whole) {
    if (whole <= 0) return "0%"
    return String.format("%.1f%%", (100.0d * part) / whole)
}

@CompileStatic
private static String formatMs(long ms) {
    if (ms < 1000) return "${ms} ms"
    if (ms < 60000) return String.format("%.1f s", ms / 1000.0d)
    long s = ms.intdiv(1000)
    long h = s.intdiv(3600)
    long m = (s % 3600).intdiv(60)
    long sec = s % 60
    if (h > 0) return "${h}h ${m}m ${sec}s"
    return "${m}m ${sec}s"
}

// ─── Logging ────────────────────────────────────────────────────────────────

private void logTrace(String m) { if (traceEnable) log.trace "${app.label}: ${m}" }
private void logDebug(String m) { if (debugEnable) log.debug "${app.label}: ${m}" }
private void logInfo(String m)  { if (txtEnable)   log.info  "${app.label}: ${m}" }
private void logWarn(String m)  { log.warn  "${app.label}: ${m}" }
private void logError(String m) { log.error "${app.label}: ${m}" }

void logsOff() {
    app.updateSetting("debugEnable", [value: false, type: "bool"])
    app.updateSetting("traceEnable", [value: false, type: "bool"])
    logInfo "debug/trace logging auto-disabled"
}

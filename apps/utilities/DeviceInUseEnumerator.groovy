// Copyright (c) 2025-2026 PJ
// SPDX-License-Identifier: MIT

/*
 For each device, lists the apps that use it, grouped by app type, with the parent device or app
 of child devices.
 */
import groovy.transform.Field

@Field static final String CODE_VERSION = "0.1.1"
@Field static final String HUB = "http://127.0.0.1:8080"
// Parent of the mobile dashboards the hub generates per room and for "All Devices".
@Field static final String DASHBOARD_PARENT_TYPE = "Easy Mobile Dashboard Parent"

definition(
    name: "Device \"in use by\" Enumerator",
    namespace: "iamtrep",
    author: "pj",
    description: "For each device, enumerates the apps referencing them",
    menu: "Apps", // new in platform 2.5.0
    category: "Utility",
    importUrl: "https://raw.githubusercontent.com/iamtrep/hubitat/refs/heads/main/apps/utilities/DeviceInUseEnumerator.groovy",
    iconUrl: "",
    iconX2Url: ""
)

preferences {
    page(name: "mainPage")
}

Map mainPage() {
    dynamicPage(name: "mainPage", title: "", install: true, uninstall: true) {
        section("Settings", hideable: true, hidden: true) {
            input name: "txtEnable", type: "bool", title: "Enable info logging", defaultValue: true
            input name: "debugEnable", type: "bool", title: "Enable debug logging", defaultValue: false, submitOnChange: true
            if (debugEnable) {
                input name: "traceEnable", type: "bool", title: "Enable trace logging", defaultValue: false
            }
            input "appName", "text", title: "Rename this app", defaultValue: app.getLabel(), multiple: false, required: false, submitOnChange: true
            if (appName != app.getLabel()) app.updateLabel(appName)
            input "forceLoopback", "bool", title: "Use the slower loopback method instead of the platform API (for testing)", defaultValue: false, submitOnChange: true
        }
        section("Options") {
            input "devices", "capability.*", title: "Only report on these specific devices", multiple: true, required: false, submitOnChange: true
            input "onlyChildDevices", "bool", title: "Process and output only child devices?", defaultValue: false, submitOnChange: true
        }
        section("") {
            input "generateReport", "button", title: "Generate Report", submitOnChange: true
            if (state.generateReport) {
                state.generateReport = false
                paragraph generateReport()
            } else {
                paragraph "No report generated yet."
            }
        }
    }
}

void installed() {
    logTrace("installed()")
    initialize()
}

void updated() {
    logTrace("updated()")
    unsubscribe()
    initialize()
    if (debugEnable || traceEnable) runIn(1800, "logsOff")
}

void logsOff() {
    app.updateSetting("debugEnable", [value: "false", type: "bool"])
    app.updateSetting("traceEnable", [value: "false", type: "bool"])
    logWarn "debug and trace logging disabled"
}

void initialize() {
    app.removeSetting("logLevel")
    logTrace "initialize()"
    state.remove("reportOutput")
}

void uninstalled() {
    logTrace("uninstalled()")
}

void appButtonHandler(evt) {
    if (evt == "generateReport") {
        state.generateReport = true
    }
}

String generateReport() {
    logInfo "Generating report"
    long started = now()
    Map src = referenceSource()
    List<Map> devs = loadDevices(src)
    Map<Long, Map> byId = devs.collectEntries { [(it.id): it] }
    Map<Long, List<Map>> refs = devs.collectEntries { Map d ->
        [(d.id): appsUsing(d.id as Long, src).findAll { it.id != (app.id as Long) }]
    }
    Map<String, String> parents = [:]
    devs.each { Map d ->
        if (d.parentDeviceId && !parents.containsKey("d${d.parentDeviceId}".toString())) {
            parents["d${d.parentDeviceId}".toString()] = byId[d.parentDeviceId as Long]?.label ?: deviceLabel(d.parentDeviceId as Long, src)
        }
        if (d.parentAppId && !parents.containsKey("a${d.parentAppId}".toString())) {
            parents["a${d.parentAppId}".toString()] = appLabel(d.parentAppId as Long, src)
        }
    }
    String html = buildReport(devs, refs, parents,
        [loopback: src.loopback, reason: src.reason, onlyChildren: onlyChildDevices == true, base: hubBaseUrl()])
    logInfo "Report generated for ${devs.size()} devices in ${now() - started} ms (${src.loopback ? 'loopback' : 'platform API'})"
    return html
}

// ---- Device reference source ----
// Every read about devices and the apps using them goes through the functions below, which
// return plain maps; nothing else touches the platform objects. getDevicesByIds,
// getAppsUsingDevice and getAppByAppId are fast but reach devices and apps this app was never
// granted, so the platform may restrict them. When one fails, these functions fall back to the
// loopback endpoints (/hub2/devicesList, /device/fullJson, /installedapp/statusJson): slower, and
// without app-type namespaces or the mobile dashboards. A missing method or a sandbox
// block is remembered until the firmware changes; any other error falls back for this run only.
//
// getAppsUsingDevice also returns the mobile dashboards (children of the hub's Easy Mobile
// Dashboard Parent), which /device/fullJson leaves out. A removed device just drops off them,
// so they are flagged `dashboard`, listed apart and kept out of the counts. The flag comes from
// the parent's app type; if that ever misses, a dashboard is counted as a user, the safe side.

private Map referenceSource() {
    String fw = location.hub.firmwareVersionString
    if (state.refSourceFw != fw) {
        state.remove("refSource")
        state.remove("refSourceFw")
    }
    if (forceLoopback) return [loopback: true, reason: "forced in settings", fj: [:]]
    if (state.refSource == "loopback") return [loopback: true, reason: "platform API unavailable on ${fw}", fj: [:]]
    return [loopback: false, fj: [:]]
}

private void platformFailed(Map src, Exception e) {
    src.loopback = true
    src.reason = e.toString()
    if (e instanceof MissingMethodException || e instanceof SecurityException) {
        state.refSource = "loopback"
        state.refSourceFw = location.hub.firmwareVersionString
    }
    logWarn "platform device-reference API failed (${e}); using the loopback endpoints"
}

private List<Map> loadDevices(Map src) {
    // Devices the user picked are granted to this app: use them directly.
    if (devices) return devices.collect { deviceMap(it) }
    List<Long> ids = allDeviceIds()
    if (!src.loopback) {
        try {
            return getDevicesByIds(ids).collect { deviceMap(it) }
        } catch (Exception e) {
            platformFailed(src, e)
        }
    }
    return ids.collect { fullJsonDevice(it, src) }.findAll { it != null }
}

private Map deviceMap(d) {
    [id: d.getIdAsLong(), label: d.getDisplayName(), parentDeviceId: d.getParentDeviceId(),
     parentAppId: d.getParentAppId(), room: d.getRoomName(), disabled: d.isDisabled()]
}

private List<Map> appsUsing(Long deviceId, Map src) {
    if (!src.loopback) {
        try {
            return (getAppsUsingDevice(deviceId) ?: []).collect { a ->
                [id: a.id as Long, label: (a.label ?: a.name) as String, type: (a.appType?.name ?: a.name) as String,
                 namespace: a.appType?.namespace as String, disabled: a.disabled == true,
                 dashboard: isDashboardParent(a.parentAppId as Long, src)]
            }
        } catch (Exception e) {
            platformFailed(src, e)
        }
    }
    List used = (fullJson(deviceId, src)?.appsUsing ?: []) as List
    return used.collect { Map a ->
        [id: a.id as Long, label: (a.label ?: a.name) as String, type: a.name as String, namespace: null,
         disabled: a.disabled == true, dashboard: false]
    }
}

private boolean isDashboardParent(Long parentAppId, Map src) {
    if (parentAppId == null) return false
    Map cache = (src.parentTypes ?: (src.parentTypes = [:])) as Map
    if (!cache.containsKey(parentAppId)) {
        def p = getAppByAppId(parentAppId)
        cache[parentAppId] = p?.appType?.namespace == "hubitat" && p?.appType?.name == DASHBOARD_PARENT_TYPE
    }
    return cache[parentAppId] as boolean
}

private String deviceLabel(Long deviceId, Map src) {
    if (!src.loopback) {
        try {
            List found = getDevicesByIds([deviceId])
            if (found) return found[0].getDisplayName()
        } catch (Exception e) {
            platformFailed(src, e)
        }
    }
    return fullJsonDevice(deviceId, src)?.label ?: "Device ${deviceId}"
}

private String appLabel(Long appId, Map src) {
    if (!src.loopback) {
        try {
            def a = getAppByAppId(appId)
            if (a) return (a.label ?: a.name) as String
        } catch (Exception e) {
            platformFailed(src, e)
        }
    }
    String label = null
    try {
        httpGet([uri: HUB, path: "/installedapp/statusJson/${appId}", timeout: 15]) { resp ->
            Map ia = resp.data?.installedApp as Map
            label = (ia?.label ?: ia?.name) as String
        }
    } catch (Exception e) {
        logWarn "statusJson ${appId}: ${e.message}"
    }
    return label ?: "App ${appId}"
}

private List<Long> allDeviceIds() {
    List<Long> ids = []
    try {
        httpGet([uri: HUB, path: "/hub2/devicesList", timeout: 30]) { resp ->
            collectIds(resp.data?.devices as List, ids)
        }
    } catch (Exception e) {
        logError "could not list devices: ${e.message}"
    }
    return ids
}

// devicesList is a tree: child devices are nested under their parent.
private void collectIds(List nodes, Collection<Long> ids) {
    nodes?.each { Map n ->
        Object id = (n.data as Map)?.id
        if (id != null) ids << (id as Long)
        collectIds(n.children as List, ids)
    }
}

private Map fullJson(Long deviceId, Map src) {
    Map cache = src.fj as Map
    if (cache.containsKey(deviceId)) return cache[deviceId] as Map
    Map out = null
    try {
        httpGet([uri: HUB, path: "/device/fullJson/${deviceId}", timeout: 15]) { resp ->
            if (resp.status == 200) out = resp.data as Map
        }
    } catch (Exception e) {
        logWarn "fullJson ${deviceId}: ${e.message}"
    }
    cache[deviceId] = out
    return out
}

private Map fullJsonDevice(Long deviceId, Map src) {
    Map d = fullJson(deviceId, src)?.device as Map
    if (!d) return null
    return [id: deviceId, label: (d.displayName ?: d.label ?: d.name) as String,
            parentDeviceId: d.parentDeviceId as Long, parentAppId: d.parentAppId as Long,
            room: d.roomName as String, disabled: d.disabled == true]
}

// ---- Report (pure: plain maps in, HTML out) ----

String buildReport(List<Map> devs, Map refs, Map parents, Map opts) {
    String base = opts.base ?: ""
    List<Map> rows = devs.findAll { !opts.onlyChildren || it.parentDeviceId || it.parentAppId }
    // Mobile dashboards drop a removed device by themselves: listed apart, not counted.
    Closure appsOf = { Map d -> ((refs[d.id] ?: []) as List<Map>).findAll { !it.dashboard } }
    Closure dashboardsOf = { Map d -> ((refs[d.id] ?: []) as List<Map>).findAll { it.dashboard } }

    int unused = rows.count { appsOf(it).isEmpty() }
    int onlyDisabled = rows.count { List a = appsOf(it); a && a.every { it.disabled } }
    int childrenUsed = rows.count { (it.parentDeviceId || it.parentAppId) && appsOf(it) }

    StringBuilder sb = new StringBuilder()
    if (opts.loopback) {
        sb << "<div style='padding:6px;margin-bottom:8px;border:1px solid #c80;border-radius:4px'>"
        sb << "Using the slower loopback method (${esc(opts.reason as String)}). "
        sb << "App-type namespaces and mobile dashboards are not shown.</div>"
    }
    sb << "<p>${rows.size()} devices &middot; ${unused} used by no app &middot; ${onlyDisabled} used only by disabled apps"
    sb << " &middot; ${childrenUsed} child devices used directly by apps</p>"
    sb << "<table><tr><th>Device</th><th>Room</th><th>Parent</th><th>Apps</th><th>Used by</th></tr>"

    rows.sort { Map a, Map b -> appsOf(b).size() <=> appsOf(a).size() ?: (a.label ?: "") <=> (b.label ?: "") }.each { Map d ->
        List<Map> apps = appsOf(d)
        String parent = "&ndash;"
        if (d.parentDeviceId) parent = "<a href='${base}/device/edit/${d.parentDeviceId}' target='_blank'>${esc(parents["d${d.parentDeviceId}".toString()] as String)}</a>"
        else if (d.parentAppId) parent = "<a href='${base}/installedapp/configure/${d.parentAppId}' target='_blank'>${esc(parents["a${d.parentAppId}".toString()] as String)}</a>"
        String usedBy = groupedLinks(apps, base)
        List<Map> dashboards = dashboardsOf(d)
        if (dashboards) {
            String line = "<span style='opacity:.6'>Mobile dashboards: " + dashboards.sort { it.label }.collect { Map a ->
                "<a href='${base}/installedapp/configure/${a.id}' target='_blank'>${esc(a.label as String)}</a>"
            }.join(", ") + "</span>"
            usedBy = usedBy ? usedBy + "<br>" + line : line
        }
        String name = "<a href='${base}/device/edit/${d.id}' target='_blank'>${esc(d.label as String)}</a>"
        if (d.disabled) name += " (disabled)"
        sb << "<tr><td>${name}</td><td>${esc((d.room ?: "") as String)}</td><td>${parent}</td>"
        sb << "<td>${apps.size()}</td><td>${usedBy ?: '&ndash;'}</td></tr>"
    }
    sb << "</table>"
    return sb.toString()
}

String groupedLinks(List<Map> apps, String base) {
    return apps.groupBy { it.type ?: "Other" }.sort { it.key }.collect { type, group ->
        "<b>${esc(type as String)}</b>: " + (group as List<Map>).sort { it.label }.collect { Map a ->
            String link = "<a href='${base}/installedapp/configure/${a.id}' target='_blank'>${esc(a.label as String)}</a>"
            a.disabled ? "<span style='opacity:.6'>${link} (disabled)</span>" : link
        }.join(", ")
    }.join("<br>")
}

String esc(String s) {
    if (s == null) return ""
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("'", "&#39;").replace('"', "&quot;")
}

private String hubBaseUrl() {
    return "http://${location.hubs[0].getDataValue("localIP")}"
}

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

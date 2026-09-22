// Copyright (c) 2026 PJ
// SPDX-License-Identifier: MIT

/*
 Tool Access Prototype

 A UI experiment, not an automation: switching a catalogue of MCP gateways and
 tools on and off, at true scale (23 gateways, 102 tools).

 The picker is a page the app serves from its own OAuth endpoint and the config
 page opens in a modal over itself — the shape the hub's own room device picker
 uses, and the shape HubDiagnostics already uses for its dashboard. Two earlier
 passes built this out of preference inputs; both cost a page round-trip per
 move and neither could offer a filter.

 The stored truth is the OFF set. Presenting the ON side first is what makes
 "on by default" safe across upgrades: a name added by a later release is absent
 from the off-set, so it is on, with no known-items bookkeeping.
*/
import groovy.transform.Field
import groovy.json.JsonOutput

@Field static final String APP_NAME = "Tool Access Prototype"
@Field static final String CODE_VERSION = "0.3.0"
@Field static final String UI_FILE = "tool_access_ui.html"
@Field static final Integer DEBUG_AUTO_OFF_MINUTES = 30

// Gateway and tool names lifted from the local MCP server app so the prototype
// runs at true catalogue scale. They are labels here; nothing dispatches on them.
@Field static final List<String> GATEWAYS = [
    "hub_manage_custom_rules", "hub_manage_variables", "hub_manage_rooms",
    "hub_manage_destructive_ops", "hub_read_apps_code", "hub_manage_backup",
    "hub_manage_code", "hub_manage_logs", "hub_manage_diagnostics",
    "hub_manage_radio", "hub_manage_files", "hub_read_diagnostics",
    "hub_read_rules", "hub_manage_native_rules_and_apps", "hub_manage_mcp",
    "hub_read_devices", "hub_read_rooms", "hub_read_files", "hub_read_variables",
    "hub_manage_devices", "hub_manage_rule_machine", "hub_manage_dashboards",
    "hub_read_dashboards"
]

@Field static final List<String> TOOLS = [
    "hub_get_custom_rule", "hub_create_custom_rule", "hub_update_custom_rule",
    "hub_delete_custom_rule", "hub_test_custom_rule", "hub_export_custom_rule",
    "hub_import_custom_rule", "hub_clone_custom_rule", "hub_list_variables",
    "hub_get_variable", "hub_set_variable", "hub_create_variable", "hub_delete_variable",
    "hub_create_connector", "hub_delete_connector", "hub_list_variable_changes",
    "hub_list_rooms", "hub_get_room", "hub_create_room", "hub_delete_room", "hub_update_room",
    "hub_reboot", "hub_shutdown", "hub_delete_device", "hub_call_destructive_ops",
    "hub_list_apps", "hub_list_drivers", "hub_get_source", "hub_list_libraries",
    "hub_list_bundles", "hub_list_backups", "hub_get_backup", "hub_list_device_dependents",
    "hub_get_app_config", "hub_list_app_pages", "hub_list_hpm_packages", "hub_restore_backup",
    "hub_delete_backup", "hub_create_app", "hub_create_driver", "hub_update_app",
    "hub_update_driver", "hub_delete_item", "hub_create_library", "hub_update_library",
    "hub_install_bundle", "hub_delete_bundle", "hub_export_bundle", "hub_get_logs",
    "hub_get_performance_stats", "hub_get_jobs", "hub_delete_debug_logs", "hub_set_log_level",
    "hub_get_metrics", "hub_get_memory_history", "hub_call_gc", "hub_get_device_health",
    "hub_get_radio_details", "hub_list_captured_states", "hub_delete_captured_state",
    "hub_set_zwave", "hub_set_zigbee", "hub_call_zwave", "hub_call_zigbee", "hub_call_matter",
    "hub_list_files", "hub_read_file", "hub_write_file", "hub_delete_file", "hub_list_rules",
    "hub_get_rule_health", "hub_list_rule_local_variables", "hub_get_visual_rule",
    "hub_call_rule", "hub_set_rule_paused", "hub_set_rule_private_boolean",
    "hub_set_native_app", "hub_set_app_disabled", "hub_delete_native_app",
    "hub_clone_native_app", "hub_export_native_app", "hub_import_native_app",
    "hub_update_mcp_settings", "hub_list_devices", "hub_get_device",
    "hub_get_device_attribute", "hub_list_device_events", "hub_get_compatible_devices",
    "hub_call_device_command", "hub_call_device_swap", "hub_call_device_replace",
    "hub_update_device", "hub_create_device", "hub_set_rule", "hub_set_visual_rule",
    "hub_delete_visual_rule", "hub_list_dashboards", "hub_get_dashboard",
    "hub_create_dashboard", "hub_update_dashboard", "hub_delete_dashboard",
    "hub_clone_dashboard"
]

definition(
    name: APP_NAME,
    namespace: "iamtrep",
    author: "pj",
    description: "UI experiment: a two-pane tool access picker served as a modal over the app config page.",
    menu: "Utilities",
    category: "Convenience",
    singleInstance: false,
    singleThreaded: true,
    oauth: true,
    importUrl: "",
    iconUrl: "",
    iconX2Url: "",
    iconX3Url: ""
)

preferences {
    page(name: "mainPage")
}

mappings {
    path('/ui.html')      { action: [GET:  'serveUI'] }
    path('/api/catalog')  { action: [GET:  'apiCatalog'] }
    path('/api/access')   { action: [POST: 'apiAccess'] }
}

// ── Page ──────────────────────────────────────────────────────────────

def mainPage() {
    if (!checkOAuth()) {
        return dynamicPage(name: "mainPage", title: APP_NAME, install: true, uninstall: true) {
            section("OAuth required") {
                paragraph "Could not enable OAuth automatically, so the picker cannot be served. " +
                          "Enable it by hand: Apps Code &rarr; ${APP_NAME} &rarr; OAuth &rarr; " +
                          "Enable OAuth in App &rarr; Update, then reopen this app."
            }
        }
    }

    List<String> gwOff = offSet("gw")
    List<String> gwOn = GATEWAYS - gwOff
    List<String> tlOff = offSet("tl")
    List<String> tlOn = TOOLS - tlOff
    String pickerUrl = "${fullLocalApiServerUrl}/ui.html?access_token=${state.accessToken}"

    dynamicPage(name: "mainPage", title: "${APP_NAME} v${CODE_VERSION}", install: true, uninstall: true) {
        section {
            paragraph "Everything is on until you switch it off. The picker opens over this page."
            paragraph modalLauncherHtml(pickerUrl)
        }

        section("Gateways") {
            paragraph shuttleHtml(gwOn, gwOff)
        }

        section("Individual tools") {
            paragraph shuttleHtml(tlOn, tlOff)
        }

        section("Options") {
            label title: "App name", required: false
            input name: "txtEnable", type: "bool", title: "Enable descriptionText logging",
                  defaultValue: true
            input name: "debugEnable", type: "bool", title: "Enable debug logging",
                  defaultValue: false, submitOnChange: true
            if (settings.debugEnable) {
                input name: "traceEnable", type: "bool", title: "Enable trace logging",
                      defaultValue: false
                paragraph "Debug logging turns off automatically after ${DEBUG_AUTO_OFF_MINUTES} minutes."
            }
        }
    }
}

// The button and the overlay it opens. The config page is same-origin with the
// endpoint, so the picker can postMessage its way back out; the listener is
// wired once because submitOnChange re-runs this markup on every page rebuild.
// If the script never runs, the plain link below it still reaches the picker.

private String modalLauncherHtml(String pickerUrl) {
    return "<button type='button' id='ta-open' style='padding:11px 20px;min-height:44px;" +
             "background:#2e7d32;border:0;border-radius:3px;color:#fff;font-size:14px;cursor:pointer'>" +
             "Edit tool access</button>" +
           "<div style='margin-top:8px;font-size:12px'>" +
             "<a href='${pickerUrl}' target='_blank' rel='noopener'>open in a new tab instead</a>" +
           "</div>" +
           "<script>(function(){" +
             "var U=" + JsonOutput.toJson(pickerUrl) + ";" +
             "function close(){var o=document.getElementById('ta-modal');if(o&&o.parentNode)o.parentNode.removeChild(o);}" +
             "function open(){" +
               "if(document.getElementById('ta-modal'))return;" +
               "var o=document.createElement('div');o.id='ta-modal';" +
               "o.style.cssText='position:fixed;top:0;left:0;right:0;bottom:0;z-index:9999;" +
                 "background:rgba(38,50,56,.55);display:flex;align-items:center;justify-content:center;padding:24px';" +
               "var f=document.createElement('iframe');f.src=U;f.title='Tool access';" +
               "f.style.cssText='width:100%;max-width:1100px;height:100%;max-height:780px;border:0;" +
                 "border-radius:4px;background:#fff;box-shadow:0 18px 48px rgba(0,0,0,.35)';" +
               "o.appendChild(f);" +
               "o.addEventListener('click',function(e){if(e.target===o)close();});" +
               "document.body.appendChild(o);f.focus();" +
             "}" +
             "if(!window.__taWired){window.__taWired=1;window.__taSaved=0;" +
               "window.addEventListener('message',function(e){" +
                 "if(!e.data||e.data.source!=='toolAccess')return;" +
                 "if(e.data.kind==='saved'){window.__taSaved=1;}" +
                 "if(e.data.kind==='close'){close();if(window.__taSaved){window.__taSaved=0;location.reload();}}" +
               "});}" +
             "var b=document.getElementById('ta-open');if(b)b.addEventListener('click',open);" +
           "})();</script>"
}

// ── The two boxes on the config page (read-only summary) ───────────────

private String shuttleHtml(List<String> onList, List<String> offList) {
    return "<div style='display:flex;gap:14px;align-items:stretch;flex-wrap:wrap'>" +
             boxHtml("On", onList, "#e8f5e9", "#2e7d32", "#37474f", "nothing switched on") +
             boxHtml("Off", offList, "#eceff1", "#546e7a", "#78909c", "nothing switched off") +
           "</div>"
}

private String boxHtml(String heading, List<String> names, String headBg, String headFg,
                       String rowFg, String emptyText) {
    return "<div style='flex:1 1 300px;min-width:0;border:1px solid #cfd8dc;border-radius:3px;overflow:hidden'>" +
             "<div style='display:flex;justify-content:space-between;padding:7px 10px;" +
                         "background:${headBg};color:${headFg};border-bottom:1px solid #cfd8dc;" +
                         "font-size:12.5px;font-weight:500'>" +
               "<span>${heading}</span><span>${names.size()}</span>" +
             "</div>" +
             "<div style='height:150px;overflow-y:auto;padding:4px 0'>" +
               itemRows(names, rowFg, emptyText) +
             "</div>" +
           "</div>"
}

private String itemRows(List<String> names, String rowFg, String emptyText) {
    if (!names) {
        return "<div style='padding:10px;font-size:12.5px;color:#90a4ae;font-style:italic'>${emptyText}</div>"
    }
    return names.collect { String name ->
        "<div style='padding:3px 10px;font-family:monospace;font-size:12px;color:${rowFg}'>${name}</div>"
    }.join("")
}

// ── Endpoints ─────────────────────────────────────────────────────────

Map serveUI() {
    if (!checkOAuth()) {
        return render(status: 403, contentType: 'text/plain', data: 'OAuth is not enabled for this app.')
    }
    String html = loadUITemplate()
    if (!html) {
        logError "${UI_FILE} is missing from the hub File Manager"
        return render(status: 404, contentType: 'text/plain', data: "${UI_FILE} not found in File Manager.")
    }
    html = html.replace('${access_token}', state.accessToken)
               .replace('${api_base}', fullLocalApiServerUrl)
    return render(status: 200, contentType: 'text/html', data: html)
}

Map apiCatalog() {
    if (!checkOAuth()) {
        return render(status: 403, contentType: 'application/json', data: '{"error":"oauth"}')
    }
    return render(status: 200, contentType: 'application/json', data: JsonOutput.toJson([
        gateways: GATEWAYS,
        tools: TOOLS,
        gwOff: offSet("gw"),
        tlOff: offSet("tl")
    ]))
}

Map apiAccess() {
    if (!checkOAuth()) {
        return render(status: 403, contentType: 'application/json', data: '{"error":"oauth"}')
    }
    Map body = request?.JSON as Map
    if (body == null) {
        return render(status: 400, contentType: 'application/json', data: '{"error":"expected a JSON body"}')
    }
    // Only names the catalogue knows are stored, so a stale or hand-made client
    // cannot park junk in the off-set.
    storeOff("gw", GATEWAYS, (body.gwOff ?: []) as List<String>)
    storeOff("tl", TOOLS, (body.tlOff ?: []) as List<String>)
    return render(status: 200, contentType: 'application/json', data: JsonOutput.toJson([
        gwOff: offSet("gw").size(), tlOff: offSet("tl").size()
    ]))
}

private String loadUITemplate() {
    try {
        byte[] data = downloadHubFile(UI_FILE)
        if (data) return new String(data, 'UTF-8')
    } catch (Exception e) {
        logError "could not read ${UI_FILE}: ${e.message}"
    }
    return null
}

// ── Off-set accounting ────────────────────────────────────────────────
//
// The off-set is the stored truth. No input renders it; it is written only
// through updateSetting, from the endpoint.

private List<String> offSet(String key) {
    return (settings["${key}Off"] ?: []) as List<String>
}

private void storeOff(String key, List<String> all, List<String> off) {
    List<String> ordered = all.findAll { String name -> off.contains(name) }
    app.updateSetting("${key}Off", [type: "enum", value: ordered])
    logCfg "${key}: ${ordered.size()} off, ${all.size() - ordered.size()} on"
}

// ── OAuth bootstrap ───────────────────────────────────────────────────

private boolean checkOAuth() {
    if (state.accessToken) return true
    try {
        createAccessToken()
        return (state.accessToken != null)
    } catch (e) {
        logDebug "OAuth not enabled yet, attempting auto-enable"
        if (autoEnableOAuth()) {
            try {
                createAccessToken()
                return (state.accessToken != null)
            } catch (e2) {
                logError "OAuth enabled but token creation failed: ${e2.message}"
                return false
            }
        }
        return false
    }
}

private String getAppTypeId() {
    String typeId = null
    try {
        httpGet([uri: "http://127.0.0.1:8080", path: "/hub2/userAppTypes", timeout: 15]) { resp ->
            List apps = resp.data instanceof List ? (List) resp.data : []
            Map match = apps.find { it.name == APP_NAME }
            if (match) typeId = match.id?.toString()
        }
    } catch (e) {
        logDebug "could not fetch user app types: ${e.message}"
    }
    return typeId
}

private boolean autoEnableOAuth() {
    String typeId = getAppTypeId()
    if (!typeId) { logError "could not find this app's type id"; return false }

    String internalVer = null
    try {
        httpGet([uri: "http://127.0.0.1:8080", path: "/app/ajax/code", query: [id: typeId], timeout: 15]) { resp ->
            internalVer = resp.data?.version?.toString()
        }
    } catch (e) {
        logError "could not fetch app code version: ${e.message}"
        return false
    }
    if (!internalVer) { logError "could not determine app code version"; return false }

    boolean success = false
    try {
        httpPost([
            uri: "http://127.0.0.1:8080",
            path: "/app/edit/update",
            requestContentType: "application/x-www-form-urlencoded",
            body: [id: typeId, version: internalVer, oauthEnabled: "true", _action_update: "Update"],
            timeout: 20
        ]) { resp -> success = true }
    } catch (e) {
        logError "could not enable OAuth: ${e.message}"
    }
    return success
}

// ── Lifecycle ─────────────────────────────────────────────────────────

def installed() {
    checkOAuth()
    initialize()
}

def updated() {
    unsubscribe()
    unschedule()
    checkOAuth()
    initialize()
}

def initialize() {
    if (state.version != CODE_VERSION) {
        logVer "now running v${CODE_VERSION}"
        state.version = CODE_VERSION
        // v0.3.0 moved the picker off the config page; the old pick-list
        // settings have no input rendering them any more.
        ["gwPickOff", "gwPickOn", "tlPickOff", "tlPickOn"].each { String old ->
            if (settings[old] != null) app.removeSetting(old)
        }
    }
    if (settings.debugEnable) runIn(DEBUG_AUTO_OFF_MINUTES * 60, "logsOff")
}

void logsOff() {
    app.updateSetting("debugEnable", [type: "bool", value: false])
    app.updateSetting("traceEnable", [type: "bool", value: false])
    logCfg "debug and trace logging disabled automatically"
}

// ── Logging (app) ─────────────────────────────────────────────────────
//   ⬇️ Evt  ⬆️ Cmd  🔧 Cfg  🌐 Net  ⏰ Sched  🏷️ Ver  ·  ⚠️ Warn  🛑 Error  🔬 Trace
private String logp(String e) { "${e} ${app.getLabel()}: " }

void logEvt  (String m) { if (settings.debugEnable) log.debug logp('⬇️') + m }
void logCmd  (String m) { log.info  logp('⬆️') + m }
void logCfg  (String m) { log.info  logp('🔧') + m }
void logNet  (String m) { if (settings.debugEnable) log.debug logp('🌐') + m }
void logSched(String m) { if (settings.debugEnable) log.debug logp('⏰') + m }
void logVer  (String m) { log.info  logp('🏷️') + m }

void logWarn (String m) { log.warn  logp('⚠️') + m }
void logError(String m) { log.error logp('🛑') + m }
void logTrace(String m) { if (settings.traceEnable) log.trace logp('🔬') + m }
void logInfo (String m) { log.info  "${app.getLabel()}: ${m}" }
void logDebug(String m) { if (settings.debugEnable) log.debug "${app.getLabel()}: ${m}" }

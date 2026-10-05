// Copyright (c) 2025-2026 PJ
// SPDX-License-Identifier: MIT

import groovy.transform.Field

@Field static final String CODE_VERSION = "0.4.2"
@Field static final String BASE_URL = "http://127.0.0.1:8080"
// File Manager file with one line per input each swap or undo changed.
@Field static final String AUDIT_FILE = "device_replacement_audit.txt"
// Parent of the mobile dashboards the hub generates per room and for "All Devices".
@Field static final String DASHBOARD_PARENT_TYPE = "Easy Mobile Dashboard Parent"
// Dashboards whose tiles hold device ids of their own.
@Field static final List<String> TILE_DASHBOARD_TYPES = ["Dashboard", "Easy Dashboard"]

definition(
    name: "Device Replacement Helper",
    namespace: "iamtrep",
    author: "pj",
    description: "Replace a device across all installed apps in one shot",
    menu: "Apps", // new in platform 2.5.0
    category: "Utility",
    iconUrl: "",
    iconX2Url: "",
    importUrl: "https://raw.githubusercontent.com/iamtrep/hubitat/refs/heads/main/apps/utilities/DeviceReplacement.groovy"
)

preferences {
    page(name: "mainPage")
    page(name: "previewPage")
    page(name: "confirmSwapPage")
    page(name: "resultsPage")
    page(name: "nativeSwapPage")
    page(name: "nativeUndoPage")
    page(name: "nativeCheckPage")
}

// ---- Page 1: Device Selection ----

Map mainPage(Map params = null) {
    // Clear scan/results state when returning to main page
    state.remove("pendingScan")
    state.remove("swapResults")
    state.remove("swapSelections")
    // A Swap Apps Device page left open without a check is settled now; an unused one is deleted.
    nativeFinalize()
    nativeDiscard()
    state.remove("native")

    dynamicPage(name: "mainPage", title: "", install: true, uninstall: true) {
        section("App Name", hideable: true, hidden: true) {
            label title: "Set App Label", required: false
        }
        section("Device Selection") {
            input "sourceDevice", "capability.*",
                title: "Device to replace (source)",
                required: false, multiple: false, submitOnChange: true
            input "targetDevice", "capability.*",
                title: "Replacement device (target)",
                required: false, multiple: false, submitOnChange: true
        }

        // Show device capabilities once selected
        if (sourceDevice || targetDevice) {
            section("Device Capabilities") {
                if (sourceDevice) {
                    List<String> srcCaps = sourceDevice.getCapabilities().collect { it.name as String }.sort()
                    paragraph "<b>Source:</b> ${sourceDevice.displayName}<br/>${srcCaps.join(', ')}"
                }
                if (targetDevice) {
                    List<String> tgtCaps = targetDevice.getCapabilities().collect { it.name as String }.sort()
                    paragraph "<b>Target:</b> ${targetDevice.displayName}<br/>${tgtCaps.join(', ')}"
                }
                if (sourceDevice && targetDevice) {
                    List<String> srcCaps = sourceDevice.getCapabilities().collect { it.name as String }
                    List<String> tgtCaps = targetDevice.getCapabilities().collect { it.name as String }
                    List<String> missing = (srcCaps - tgtCaps).sort()
                    List<String> extra = (tgtCaps - srcCaps).sort()
                    if (missing) {
                        paragraph "<span style='color:orange'>Target is missing: ${missing.join(', ')}</span>"
                    }
                    if (extra) {
                        paragraph "<span style='color:gray'>Target has extra: ${extra.join(', ')}</span>"
                    }
                    if (!missing && !extra) {
                        paragraph "<span style='color:green'>Capabilities match</span>"
                    }
                }
            }
        }

        // Show navigation to preview when both devices are selected
        if (sourceDevice && targetDevice) {
            if (sourceDevice.id == targetDevice.id) {
                section {
                    paragraph "<span style='color:red'>Source and target are the same device. Please select different devices.</span>"
                }
            } else {
                section {
                    href "previewPage", title: "Scan & Preview Swap", description: "Find all apps using ${sourceDevice.displayName} and preview changes"
                }
            }
        }

        section("Options", hideable: true, hidden: true) {
            input "skipSelf", "bool",
                title: "Skip this app's own config",
                defaultValue: true, required: false
            input "forceLoopback", "bool",
                title: "Find apps with the slower loopback method instead of the platform API (for testing)",
                defaultValue: false, required: false
        }

        Map lastSwap = state.lastSwap as Map
        if (lastSwap?.method == "native") {
            section("Last Swap") {
                paragraph "Swap Apps Device swapped ${lastSwap.sourceLabel} (${lastSwap.sourceId}) and ${lastSwap.targetLabel} (${lastSwap.targetId}) in all apps."
                href "nativeUndoPage", title: "Undo: swap them back", description: "Opens the hub's swap page with the same two devices"
            }
        } else if (lastSwap) {
            section("Last Swap") {
                String summary = "Swapped device ${lastSwap.sourceId} → ${lastSwap.targetId}"
                int successCount = (lastSwap.results as List)?.count { (it as Map).success } ?: 0
                summary += " (${successCount} app${successCount == 1 ? '' : 's'} updated)"
                paragraph summary
                input "undoLastSwap", "button", title: "Undo Last Swap"
            }
        }

        List<Map> lastUndo = state.lastUndo as List<Map>
        if (lastUndo) {
            section("Last Undo") {
                lastUndo.each { Map r ->
                    String icon = (r.success as boolean) ? "<span style='color:green'>&#10003;</span>" : "<span style='color:red'>&#10007;</span>"
                    paragraph "${icon} ${r.appLabel}: ${r.inputName}: ${r.message}"
                }
            }
        }

        section("Logging", hideable: true, hidden: true) {
            input "txtEnable", "bool",
                title: "Enable info logging",
                defaultValue: true
            input "debugEnable", "bool",
                title: "Enable debug logging",
                defaultValue: false, submitOnChange: true
            if (debugEnable) {
                input "traceEnable", "bool",
                    title: "Enable trace logging",
                    defaultValue: false
            }
        }
        section("") {
            paragraph "<a href='/local/${AUDIT_FILE}' target='_blank'>Audit log</a> of every swap and undo (File Manager: ${AUDIT_FILE})"
            paragraph "Version ${CODE_VERSION}"
        }
    }
}

// ---- Page 2: Scan & Preview ----

Map previewPage(Map params = null) {
    dynamicPage(name: "previewPage", title: "Preview") {
        section {
            href "mainPage", title: "Back to Device Selection", description: ""
            input "refreshScan", "button", title: "Refresh Scan"
        }
        if (!sourceDevice || !targetDevice) {
            section { paragraph "Please select both a source and target device." }
            return
        }
        if (sourceDevice.id == targetDevice.id) {
            section { paragraph "<span style='color:red'>Source and target are the same device. Please go back and select different devices.</span>" }
            return
        }

        int sourceId = sourceDevice.id as int
        int targetId = targetDevice.id as int

        section {
            paragraph "<b>Source:</b> ${sourceDevice.displayName} (ID: ${sourceId})"
            paragraph "<b>Target:</b> ${targetDevice.displayName} (ID: ${targetId})"
        }

        // Step 1: Find apps using the source device
        Map src = referenceSource()
        List<Map> appsUsing = appsUsing(sourceId as Long, src)
        if (appsUsing == null) {
            section { paragraph "<span style='color:red'>Could not read which apps use ${sourceDevice.displayName}; see the logs.</span>" }
            return
        }
        if (src.loopback) {
            section { paragraph "<span style='color:orange'>Using the slower loopback method (${src.reason}). Mobile dashboards are not shown.</span>" }
        }

        // Filter out self if skipSelf is enabled
        if (skipSelf != false) {
            appsUsing = appsUsing.findAll { (it.id as int) != (app.id as int) }
        }
        List<Map> dashboards = appsUsing.findAll { it.dashboard }
        appsUsing = appsUsing.findAll { !it.dashboard }

        if (!appsUsing && !dashboards) {
            section("Results") {
                paragraph "No apps reference this device. Nothing to swap."
            }
            state.pendingScan = []
            return
        }

        // Step 2 & 3: For each app, check statusJson and configure/json
        List<Map> swappable = []
        List<Map> manual = []
        List<Map> unmatched = []   // apps that use the source but hold it in no device input we can see
        Set<String> allSubscribedAttrs = [] as Set<String>

        appsUsing.each { Map appRef ->
            int appId = appRef.id as int
            String appLabel = (appRef.label ?: "App ${appId}") as String
            String appType = (appRef.type ?: "") as String

            // Get statusJson for device input details
            Map statusData = null
            try {
                httpGet("${BASE_URL}/installedapp/statusJson/${appId}") { response ->
                    if (response.status == 200) {
                        statusData = response.data as Map
                    }
                }
            } catch (Exception e) {
                logWarn "Error fetching statusJson for app ${appId}: ${e.message}"
            }
            if (!statusData) {
                unmatched << [appId: appId, appLabel: appLabel, appType: appType, reason: "Could not read the app's settings"]
                return
            }

            // Extract subscribed attributes for the source device
            List eventSubs = (statusData.eventSubscriptions ?: []) as List
            List<String> subscribedAttrs = eventSubs.findAll { sub ->
                Map s = sub as Map
                (s.typeId as int) == sourceId && s.type == "DEVICE"
            }.collect { sub ->
                (sub as Map).name as String
            }.unique().sort()
            allSubscribedAttrs.addAll(subscribedAttrs)

            // Scan appSettings for device inputs containing source ID
            List appSettings = (statusData.appSettings ?: []) as List
            List<Map> matchingInputs = []
            appSettings.each { setting ->
                Map s = setting as Map
                String type = (s.type ?: "") as String
                if (!type.startsWith("capability.")) return
                List deviceIds = (s.deviceIdsForDeviceList ?: []) as List
                boolean hasSource = deviceIds.any { (it as int) == sourceId }
                if (hasSource) {
                    matchingInputs << [
                        name: s.name as String,
                        type: type,
                        multiple: s.multiple as boolean,
                        deviceIds: deviceIds.collect { it as int }
                    ]
                }
            }

            if (!matchingInputs) {
                unmatched << [appId: appId, appLabel: appLabel, appType: appType,
                              reason: "No device input holds it; the app may keep the device in its own data"]
                return
            }

            // Check app state for source device ID references
            List appState = (statusData.appState ?: []) as List
            String sourceIdStr = sourceId.toString()
            boolean stateHasDeviceRef = appState.any { entry ->
                Map e = entry as Map
                String key = (e.name ?: "") as String
                String val = (e.value ?: "") as String
                key.contains(sourceIdStr) || val.contains(sourceIdStr)
            }

            // Walk the app's full page graph so we can locate each input's home page.
            Map<String,Map> pageGraph = discoverPageGraph(appId)

            matchingInputs.each { Map inputMatch ->
                String inputName = inputMatch.name
                Map loc = locateInputPage(pageGraph, inputName)
                String homePage = loc.page as String
                List<String> homeBreadcrumbs = (loc.breadcrumbs ?: []) as List<String>
                boolean isOnMainPage = (homePage == "mainPage")

                // Capability compatibility check
                String capWarning = null
                String capType = inputMatch.type as String
                if (capType != "capability.*") {
                    try {
                        List compatDevices = []
                        httpGet("${BASE_URL}/device/listJson?capability=${capType}") { response ->
                            if (response.status == 200) {
                                compatDevices = response.data as List
                            }
                        }
                        boolean targetCompatible = compatDevices.any { (it.id as int) == targetId }
                        if (!targetCompatible) {
                            capWarning = "Target device may not have ${capType}"
                        }
                    } catch (Exception e) {
                        logDebug "Could not check capability compatibility: ${e.message}"
                    }
                }

                // Check if target already present
                boolean targetAlreadyPresent = (inputMatch.deviceIds as List).any { (it as int) == targetId }
                String targetWarning = targetAlreadyPresent ? "Target already in this input; swap will just remove source" : null

                // Single-select warning
                String singleSelectWarning = !(inputMatch.multiple as boolean) ? "Single-device input" : null

                // App state warning
                String stateWarning = stateHasDeviceRef ? "App state references device ID; may need manual attention" : null

                // Dashboard tiles keep the old device id when only the dashboard's device list changes.
                String tileWarning = appType in TILE_DASHBOARD_TYPES
                    ? "Tiles on this dashboard keep pointing at ${sourceDevice.displayName}; use Swap Apps Device, or re-pick the tiles after swapping"
                    : null

                // Deeplink straight to the page that holds this input (mainPage if unknown)
                String pageForLink = homePage ?: "mainPage"
                String pageDeepLink = "/installedapp/configure/${appId}/${pageForLink}"

                Map entry = [
                    appId: appId,
                    appLabel: appLabel,
                    appType: appType,
                    inputName: inputName,
                    inputType: capType,
                    multiple: inputMatch.multiple,
                    currentDeviceIds: inputMatch.deviceIds,
                    targetAlreadyPresent: targetAlreadyPresent,
                    subscribedAttrs: subscribedAttrs,
                    capWarning: capWarning,
                    targetWarning: targetWarning,
                    singleSelectWarning: singleSelectWarning,
                    stateWarning: stateWarning,
                    tileWarning: tileWarning,
                    homePage: homePage,
                    homeBreadcrumbs: homeBreadcrumbs,
                    pageDeepLink: pageDeepLink
                ]

                if (isOnMainPage) {
                    swappable << entry
                } else {
                    entry.reason = homePage
                        ? "Input lives on sub-page '${homePage}' — auto-swap not supported; use deeplink"
                        : (appRef.disabled
                            ? "The app is disabled, so its page shows no inputs; enable it to swap automatically, or edit it by hand"
                            : "Input not on any discoverable page (dynamic render path) — open the app to edit")
                    manual << entry
                }
            }
        }

        nativeSwapSection(sourceId, targetId, allSubscribedAttrs)

        // Display results
        String td = "style='border:1px solid #999;padding:4px 8px'"
        String tdC = "style='border:1px solid #999;padding:4px 8px;text-align:center'"

        if (swappable) {
            // Initialize selection state for new scans
            if (state.swapSelections == null) {
                state.swapSelections = [:]
            }
            swappable.eachWithIndex { Map entry, int idx ->
                String key = idx.toString()
                if (!state.swapSelections.containsKey(key)) {
                    boolean hasWarnings = entry.capWarning || entry.targetWarning || entry.singleSelectWarning || entry.stateWarning || entry.tileWarning
                    state.swapSelections[key] = hasWarnings ? "off" : "on"
                }
            }

            String X = "<i class='he-checkbox-checked'></i>"
            String O = "<i class='he-checkbox-unchecked'></i>"

            section("Auto-Swap Eligible — on mainPage (${swappable.size()})") {
                String table = "<style>.mdl-data-table tbody tr:hover{background-color:inherit} .swap-tbl td,.swap-tbl th {padding:8px;text-align:left;font-size:14px}</style>" +
                    "<div style='overflow-x:auto'><table class='mdl-data-table swap-tbl' style='border:2px solid black;width:100%'>" +
                    "<thead><tr style='border-bottom:2px solid black'>" +
                    "<th style='text-align:center;border-right:2px solid black'><strong>Swap</strong></th>" +
                    "<th><strong>App</strong></th><th><strong>Type</strong></th><th><strong>Page</strong></th><th><strong>Input</strong></th><th><strong>Capability</strong></th><th><strong>Subscriptions</strong></th><th><strong>Warnings</strong></th>" +
                    "</tr></thead><tbody>"
                swappable.eachWithIndex { Map entry, int idx ->
                    List<String> entryWarnings = []
                    if (entry.capWarning) entryWarnings << (entry.capWarning as String)
                    if (entry.targetWarning) entryWarnings << (entry.targetWarning as String)
                    if (entry.singleSelectWarning) entryWarnings << (entry.singleSelectWarning as String)
                    if (entry.stateWarning) entryWarnings << (entry.stateWarning as String)
                    if (entry.tileWarning) entryWarnings << (entry.tileWarning as String)
                    String warningCell = entryWarnings ? "<span style='color:orange'>${entryWarnings.join('<br>')}</span>" : "<span style='color:green'>&#10003;</span>"
                    List<String> attrs = (entry.subscribedAttrs ?: []) as List<String>
                    String subsCell = attrs ? attrs.join(", ") : "<span style='color:gray'>-</span>"
                    boolean selected = state.swapSelections[idx.toString()] != "off"
                    table += "<tr>" +
                        "<td style='text-align:center;border-right:2px solid black'>${buttonLink("btnSwapSel:${idx}", selected ? X : O, "#1A77C9")}</td>" +
                        "<td><a href='/installedapp/configure/${entry.appId}' target='_blank'>${entry.appLabel}</a></td>" +
                        "<td>${entry.appType}</td>" +
                        "<td><a href='${entry.pageDeepLink}' target='_blank'>${entry.homePage ?: 'mainPage'}</a></td>" +
                        "<td>${entry.inputName}</td>" +
                        "<td>${entry.inputType}</td>" +
                        "<td>${subsCell}</td>" +
                        "<td>${warningCell}</td>" +
                        "</tr>"
                }
                table += "</tbody></table></div>"
                paragraph table
            }
        }

        if (manual) {
            section("Manual Edit Required (${manual.size()})") {
                paragraph "These inputs live on sub-pages or in app state that the auto-swap can't safely write to. Use the <b>Edit</b> deeplink to open the right page directly — set the input to <b>${targetDevice.displayName}</b> (ID ${targetId}) and remove <b>${sourceDevice.displayName}</b> (ID ${sourceId})."
                String table = "<table style='border-collapse:collapse;width:100%'>" +
                    "<thead><tr style='background:#ddd'>" +
                    "<th ${td}>App</th><th ${td}>Type</th><th ${td}>Page</th><th ${td}>Input</th><th ${td}>Capability</th><th ${td}>Current Devices</th><th ${td}>Subscriptions</th><th ${td}>Reason</th><th ${tdC}>Edit</th>" +
                    "</tr></thead><tbody>"
                manual.each { Map entry ->
                    List<String> attrs = (entry.subscribedAttrs ?: []) as List<String>
                    String subsCell = attrs ? attrs.join(", ") : "<span style='color:gray'>-</span>"
                    List currentIds = (entry.currentDeviceIds ?: []) as List
                    String currentCell = currentIds ? currentIds.collect { id ->
                        ((id as int) == sourceId)
                            ? "<b style='color:red'>${id}</b>"
                            : id.toString()
                    }.join(", ") : "<span style='color:gray'>-</span>"
                    String pageLabel = (entry.homePage ?: '(unknown)') as String
                    String editBtn = "<a href='${entry.pageDeepLink}' target='_blank' style='display:inline-block;padding:4px 10px;background:#1A77C9;color:white;border-radius:3px;text-decoration:none'>Edit &rarr;</a>"
                    table += "<tr>" +
                        "<td ${td}><a href='/installedapp/configure/${entry.appId}' target='_blank'>${entry.appLabel}</a></td>" +
                        "<td ${td}>${entry.appType}</td>" +
                        "<td ${td}>${pageLabel}</td>" +
                        "<td ${td}>${entry.inputName}</td>" +
                        "<td ${td}>${entry.inputType}</td>" +
                        "<td ${td}>${currentCell}</td>" +
                        "<td ${td}>${subsCell}</td>" +
                        "<td ${td}>${entry.reason}</td>" +
                        "<td ${tdC}>${editBtn}</td>" +
                        "</tr>"
                }
                table += "</tbody></table>"
                paragraph table
            }
        }

        if (unmatched) {
            section("Other Apps Using the Source (${unmatched.size()})") {
                paragraph "These apps use <b>${sourceDevice.displayName}</b> but the scan found no device input to change. Open each app and replace the device by hand."
                String table = "<table style='border-collapse:collapse;width:100%'>" +
                    "<thead><tr style='background:#ddd'><th ${td}>App</th><th ${td}>Type</th><th ${td}>Why</th><th ${tdC}>Open</th></tr></thead><tbody>"
                unmatched.each { Map entry ->
                    table += "<tr><td ${td}>${entry.appLabel}</td><td ${td}>${entry.appType}</td><td ${td}>${entry.reason}</td>" +
                        "<td ${tdC}><a href='/installedapp/configure/${entry.appId}' target='_blank'>Open &rarr;</a></td></tr>"
                }
                table += "</tbody></table>"
                paragraph table
            }
        }

        if (dashboards) {
            section("Mobile Dashboards (${dashboards.size()})") {
                String links = dashboards.sort { it.label }.collect { Map d ->
                    "<a href='/installedapp/configure/${d.id}' target='_blank'>${d.label}</a>"
                }.join(", ")
                paragraph "Showing ${sourceDevice.displayName}: ${links}"
                paragraph dashboardAdvice(sourceDevice.getRoomName(), targetDevice.getRoomName(),
                                          targetDevice.displayName as String, targetId as Long)
            }
        }

        if (!swappable && !manual && !unmatched && !dashboards) {
            section("Results") {
                paragraph "No device inputs found containing the source device."
            }
        }

        if (swappable) {
            // Store indexed entries so resultsPage can filter by selection
            swappable.eachWithIndex { Map entry, int idx ->
                entry.index = idx
            }
            state.pendingScan = swappable

            Map selections = state.swapSelections ?: [:]
            int selectedCount = swappable.count { Map entry ->
                selections[entry.index.toString()] != "off"
            }
            if (selectedCount > 0) {
                section {
                    href "confirmSwapPage", title: "Swap…", description: "Review the swap of ${sourceDevice.displayName} → ${targetDevice.displayName} in ${selectedCount} of ${swappable.size()} app(s)"
                }
            }
        } else {
            state.pendingScan = []
        }
    }
}

// ---- Page 3: Confirm, Execute & Report ----
// The confirm page's Swap link carries a single-use token to resultsPage, which swaps only the
// first time it sees that token. A refresh or a stale link shows the stored results instead.

Map confirmSwapPage(Map params = null) {
    List<Map> pending = selectedPending()
    dynamicPage(name: "confirmSwapPage", title: "Swap devices?") {
        section(sectionClass: "swap-confirm") {
            hideDoneButton()
            if (!pending) {
                paragraph "Nothing to swap. Go back and select at least one app."
            } else {
                paragraph "<span style='color:red'><b>&#9888; Recommended:</b> <a href='/hub/backup' target='_blank'>Create a local hub backup</a> before executing the swap.</span>"
                paragraph "${sourceDevice.displayName} (ID ${sourceDevice.id}) will be replaced by ${targetDevice.displayName} (ID ${targetDevice.id}) in:"
                pending.each { Map e -> paragraph "${e.appLabel}: ${e.inputName}" }
                paragraph "Each app's settings are saved, so each one runs its updated()."
                href "resultsPage", title: "Swap in ${pending.size()} input(s)", params: [token: issueConfirmToken("swap")]
            }
            href "previewPage", title: "Cancel"
        }
    }
}

Map resultsPage(Map params) {
    String result = consumeConfirmToken("swap", params)
    if (result == "act") executeSwap()
    dynamicPage(name: "resultsPage", title: "Swap Results", nextPage: "mainPage") {
        section {
            href "mainPage", title: "Back to Device Selection", description: ""
        }
        List<Map> results = (state.swapResults ?: []) as List<Map>
        if (result == "stale" || !results) {
            section { paragraph result == "stale" ? "Nothing swapped: this link was already used." : "Nothing to swap." }
            return
        }
        String td = "style='border:1px solid #999;padding:4px 8px'"
        String tdC = "style='border:1px solid #999;padding:4px 8px;text-align:center'"
        section("Results") {
            String table = "<table style='border-collapse:collapse;width:100%'>" +
                "<thead><tr style='background:#ddd'>" +
                "<th ${td}>App</th><th ${td}>Input</th><th ${td}>Status</th><th ${td}>Details</th>" +
                "</tr></thead><tbody>"
            results.each { Map r ->
                String statusIcon = (r.success as boolean) ? "<span style='color:green'>&#10003;</span>" : "<span style='color:red'>&#10007;</span>"
                table += "<tr>" +
                    "<td ${td}><a href='/installedapp/configure/${r.appId}' target='_blank'>${r.appLabel}</a></td>" +
                    "<td ${td}>${r.inputName}</td>" +
                    "<td ${tdC}>${statusIcon}</td>" +
                    "<td ${td}>${r.message}</td>" +
                    "</tr>"
            }
            table += "</tbody></table>"
            paragraph table
            paragraph state.auditError
                ? "<span style='color:orange'>Not written to the <a href='/local/${AUDIT_FILE}' target='_blank'>audit log</a>: ${state.auditError}</span>"
                : "Written to the <a href='/local/${AUDIT_FILE}' target='_blank'>audit log</a>."
        }
    }
}

private List<Map> selectedPending() {
    Map selections = state.swapSelections ?: [:]
    return ((state.pendingScan ?: []) as List<Map>).findAll { Map entry ->
        selections[entry.index.toString()] != "off"
    }
}

private void executeSwap() {
    List<Map> pending = selectedPending()
    int sourceId = sourceDevice.id as int
    int targetId = targetDevice.id as int
    logCmd "Swapping ${sourceId} → ${targetId} in ${pending.size()} input(s)"
    List<Map> results = pending.collect { Map entry -> swapInput(entry, sourceId, targetId) }
    // The writes are done. An exception from here on would drop this render's state, so the undo
    // record and audit lines go first and the checks below cannot throw.
    try {
        checkApps(pending, results, targetId, sourceDevice.displayName as String)
    } catch (Exception e) {
        logWarn "Post-swap checks failed: ${e}"
    }
    String devices = "${sourceDevice.displayName} (${sourceId}) -> ${targetDevice.displayName} (${targetId})"
    state.auditError = appendAudit(results.collect { Map r -> auditLine("swap", devices, r) })

    List<Map> done = results.findAll { it.success }
    if (done) {
        state.lastSwap = [
            sourceId: sourceId,
            targetId: targetId,
            sourceLabel: sourceDevice.displayName,
            targetLabel: targetDevice.displayName,
            results: done
        ]
        state.remove("lastUndo")
    }
    state.remove("pendingScan")
    state.swapResults = results
}

private void hideDoneButton() {
    paragraph rawHtml: true, "<style>#formApp:has(.swap-confirm) #fieldsetAppButtons button[value='Done'] { display:none !important; }</style>"
}

private String issueConfirmToken(String action) {
    String token = UUID.randomUUID().toString()
    state.confirmToken = [action: action, token: token]
    return token
}

// "act" the first time a token is presented, "done" if the same page renders
// again for that click, "stale" otherwise.
private String consumeConfirmToken(String action, Map params) {
    String token = params?.token
    if (!token) return "stale"
    Map pending = state.confirmToken as Map
    if (pending?.action == action && pending.token == token) {
        state.remove("confirmToken")
        state.confirmUsed = token
        return "act"
    }
    return state.confirmUsed == token ? "done" : "stale"
}

// ---- Page Graph Discovery ----

// Recursively walks an app's preference pages starting at mainPage, following
// every body element with element=='href' and a 'page' attribute. Returns a map
// keyed by page name with rendered input names and the parent-page breadcrumb
// chain that leads to it (mainPage's chain is empty). Cycles are protected by
// a visited set; total pages capped at 30 for safety.
private Map<String,Map> discoverPageGraph(int appId) {
    Map<String,Map> graph = [:]
    List<List> queue = []
    queue << ["mainPage", [] as List<String>]
    Set<String> seen = [] as Set<String>
    int hardCap = 30

    while (!queue.isEmpty() && graph.size() < hardCap) {
        List head = queue.remove(0)
        String pageName = head[0] as String
        List<String> parents = head[1] as List<String>
        if (seen.contains(pageName)) continue
        seen << pageName

        Map pageData = null
        try {
            httpGet("${BASE_URL}/installedapp/configure/json/${appId}/${pageName}") { response ->
                if (response.status == 200) pageData = response.data as Map
            }
        } catch (Exception e) {
            logDebug "discoverPageGraph: cannot fetch ${pageName}: ${e.message}"
            continue
        }
        if (!pageData) continue

        Map cp = (pageData.configPage ?: [:]) as Map
        String actualName = (cp.name ?: pageName) as String
        Set<String> inputs = [] as Set<String>
        List<String> hrefPages = []
        ((cp.sections ?: []) as List).each { sec ->
            ((sec as Map).input ?: []).each { inp ->
                inputs << (((inp as Map).name) as String)
            }
            ((sec as Map).body ?: []).each { b ->
                Map elem = b as Map
                if (elem.element == "href" && elem.page) {
                    hrefPages << (elem.page as String)
                }
            }
        }
        graph[actualName] = [breadcrumbs: parents, inputs: inputs, hrefs: hrefPages]

        List<String> childParents = (parents + [actualName]) as List<String>
        hrefPages.each { String childPage ->
            if (!seen.contains(childPage)) {
                queue << [childPage, childParents]
            }
        }
    }
    return graph
}

// Resolves the home page for a settings input by scanning the page graph.
// Returns null if the input doesn't appear on any discoverable page (e.g. it
// only exists in stored settings but the page that renders it isn't reachable
// without further user input).
private Map locateInputPage(Map<String,Map> graph, String inputName) {
    String home = null
    List<String> breadcrumbs = []
    graph.each { String page, Map info ->
        if (home != null) return
        Set inputs = (info.inputs ?: ([] as Set)) as Set
        if (inputs.contains(inputName)) {
            home = page
            breadcrumbs = (info.breadcrumbs ?: []) as List<String>
        }
    }
    return [page: home, breadcrumbs: breadcrumbs]
}

// ---- Device Input Write ----
// A save to /installedapp/update/json keeps every setting it leaves out, so a swap posts the one
// device input it changes. Echoing the rest of the page corrupts some of them: configure/json
// returns an enum-multiple as a List whose toString() is not the form the save expects, and an
// unset input echoed as "[]" is stored as that string.

// Replaces sourceId with targetId in one main-page device input, then re-reads the app to check
// the input and that no other setting moved. Returns the result row; `before` and `after` hold
// the input's device ids, for undo.
private Map swapInput(Map entry, int sourceId, int targetId) {
    int appId = entry.appId as int
    String inputName = entry.inputName as String
    Map result = [appId: appId, appLabel: entry.appLabel, inputName: inputName, success: false, message: ""]
    try {
        Map cfg = fetchConfig(appId)
        Map input = cfg ? findMainInput(cfg, inputName) : null
        if (!input) {
            result.message = cfg ? "The input is no longer on the app's main page" : "Could not read the app's settings"
            return result
        }
        List<Integer> before = deviceIds((cfg.settings as Map)?.get(inputName))
        if (!before.contains(sourceId)) {
            result.message = "The source is no longer in this input"
            return result
        }
        List<Integer> after = before.collect { it == sourceId ? targetId : it }.unique()
        String err = writeDeviceInput(appId, cfg, input, after)
        if (err) {
            result.message = err
            return result
        }
        result.before = before
        result.after = after
        result.success = true
        result.message = verifyWrite(appId, inputName, after, cfg.settings as Map)
    } catch (Exception e) {
        result.message = "Error: ${e.message}"
        logError "Swap failed for ${entry.appLabel}/${inputName}: ${e.message}"
    }
    return result
}

private Map fetchConfig(int appId) {
    Map cfg = null
    httpGet([uri: BASE_URL, path: "/installedapp/configure/json/${appId}", timeout: 15]) { response ->
        if (response.status == 200) cfg = response.data as Map
    }
    return cfg
}

private Map findMainInput(Map cfg, String inputName) {
    Map cp = (cfg.configPage ?: [:]) as Map
    if ((cp.name ?: "mainPage") != "mainPage") return null
    for (sec in (cp.sections ?: [])) {
        for (inp in ((sec as Map).input ?: [])) {
            if ((inp as Map).name == inputName) return inp as Map
        }
    }
    return null
}

// A device setting as configure/json returns it: a Map of id -> label, a List, or a scalar id.
private List<Integer> deviceIds(Object value) {
    if (value instanceof Map) return (value as Map).keySet().collect { it.toString().toInteger() }
    if (value instanceof List) return (value as List).collect { it.toString().toInteger() }
    if (value == null || value.toString() in ["", "null"]) return []
    return value.toString().split(",").findAll { it.trim() }.collect { it.trim().toInteger() }
}

// Saves one device input with Done, so the app's updated() re-arms its subscriptions. Returns
// null on success, else the reason. An empty list is refused: the save can't clear a multi-select.
private String writeDeviceInput(int appId, Map cfg, Map input, List<Integer> ids) {
    if (!ids) return "Refusing to save an empty device list"
    String name = input.name as String
    List<List<String>> fields = [
        ["_action_update", "Done"],
        ["formAction", "update"],
        ["id", appId.toString()],
        ["version", (((cfg.app ?: [:]) as Map).version ?: "1").toString()],
        ["appTypeId", ""],
        ["appTypeName", ""],
        ["currentPage", "mainPage"],
        ["pageBreadcrumbs", "[]"],
        ["${name}.type".toString(), (input.type ?: "") as String],
        ["${name}.multiple".toString(), (input.multiple as boolean).toString()],
        ["settings[${name}]".toString(), ids.join(",")],
        ["deviceList", name],
        ["", ""],
        ["referrer", "${BASE_URL}/installedapp/list".toString()],
        ["url", "${BASE_URL}/installedapp/configure/${appId}/mainPage".toString()],
        ["_cancellable", "false"]
    ]
    String body = fields.collect { pair ->
        URLEncoder.encode(pair[0], "UTF-8") + "=" + URLEncoder.encode(pair[1], "UTF-8")
    }.join("&")
    logNet "POST body for app ${appId}: ${body}"

    String err = "No response"
    httpPost([
        uri: BASE_URL,
        path: "/installedapp/update/json",
        requestContentType: "application/x-www-form-urlencoded",
        body: body,
        textParser: true,
        timeout: 30
    ]) { response ->
        String respText = response.data?.text ?: ""
        err = (response.status == 200 && respText.contains('"success"')) ? null : "Save rejected: HTTP ${response.status} ${respText.take(200)}"
    }
    return err
}

// ---- Post-Write Verification ----

private String verifyWrite(int appId, String inputName, List<Integer> expected, Map settingsBefore) {
    List<String> notes = []
    Map cfg = fetchConfig(appId)
    if (!cfg) return "Saved; could not re-read the app to verify"
    Map settingsAfter = (cfg.settings ?: [:]) as Map
    List<Integer> now = deviceIds(settingsAfter[inputName])
    notes << ((now as Set) == (expected as Set) ? "Verified: input now ${now.join(', ')}" : "Warning: input is ${now.join(', ')}, expected ${expected.join(', ')}")

    Set<String> keys = ((settingsBefore ?: [:]).keySet() + settingsAfter.keySet()).collect { it.toString() } as Set<String>
    List<String> moved = keys.findAll { it != inputName && settingsBefore?.get(it) != settingsAfter[it] }.sort()
    if (moved) notes << "Warning: other settings changed: ${moved.join(', ')}"
    return notes.join("; ")
}

// Once per app, after all its inputs are swapped. The subscriptions the app had on the source
// (names as the hub lists them, e.g. "switch.on") should now be on the target. Some apps name
// themselves after their devices at setup and keep that name, so a name still holding the old
// device's name is pointed out.
private void checkApps(List<Map> pending, List<Map> results, int targetId, String oldName) {
    results.findAll { it.success }.groupBy { it.appId }.each { appId, List<Map> rows ->
        Map last = rows[-1]
        String label = ((((fetchConfig(appId as int) ?: [:]).app ?: [:]) as Map).label ?: "") as String
        if (oldName && label.contains(oldName)) last.message = "${last.message}; The app's name still mentions ${oldName}; rename it if you like"
        List<String> expected = pending.findAll { (it.appId as int) == (appId as int) }
            .collectMany { (it.subscribedAttrs ?: []) as List }.collect { it as String }.unique()
        if (!expected) return
        Map status = null
        httpGet([uri: BASE_URL, path: "/installedapp/statusJson/${appId}", timeout: 15]) { response ->
            if (response.status == 200) status = response.data as Map
        }
        Set<String> onTarget = ((status?.eventSubscriptions ?: []) as List).findAll { sub ->
            Map s = sub as Map
            s.type == "DEVICE" && (s.typeId as int) == targetId
        }.collect { (it as Map).name as String } as Set<String>
        List<String> missing = (expected - onTarget).sort()
        last.message = "${last.message}; " + (missing ? "Warning: not subscribed to ${missing.join(', ')} on the target" : "Subscriptions confirmed")
    }
}

// ---- Undo ----

void appButtonHandler(String evt) {
    if (evt == "undoLastSwap") {
        performUndo()
    } else if (evt == "refreshScan") {
        state.remove("swapSelections")
    } else if (evt.startsWith("btnSwapSel:")) {
        String idx = evt.split(":")[1]
        Map selections = state.swapSelections ?: [:]
        selections[idx] = (selections[idx] == "off") ? "on" : "off"
        state.swapSelections = selections
    }
}

private String buttonLink(String btnName, String linkText, String color = "#1A77C9", String font = "15px") {
    return "<div class='form-group'><input type='hidden' name='${btnName}.type' value='button'></div>" +
        "<div><div class='submitOnChange' onclick='buttonClick(this)' style='color:${color};cursor:pointer;font-size:${font}'>${linkText}</div></div>" +
        "<input type='hidden' name='settings[${btnName}]' value=''>"
}

// Puts each swapped input back to the exact device list it held before the swap. An input that
// changed since the swap is left alone and reported.
private void performUndo() {
    Map lastSwap = state.lastSwap as Map
    if (!lastSwap) {
        logWarn "No swap to undo"
        return
    }
    List<Map> swapResults = (lastSwap.results ?: []) as List<Map>
    logCmd "Undoing swap: ${lastSwap.targetId} → ${lastSwap.sourceId} across ${swapResults.size()} input(s)"

    List<Map> undone = swapResults.collect { Map entry ->
        int appId = entry.appId as int
        String inputName = entry.inputName as String
        Map row = [appId: appId, appLabel: entry.appLabel, inputName: inputName, success: false]
        try {
            if (entry.before == null) {
                row.message = "Swapped by an older version of this app; change it by hand"
                return row
            }
            List<Integer> before = (entry.before as List).collect { it as int }
            List<Integer> after = (entry.after as List).collect { it as int }
            Map cfg = fetchConfig(appId)
            Map input = cfg ? findMainInput(cfg, inputName) : null
            if (!input) {
                row.message = cfg ? "The input is no longer on the app's main page" : "Could not read the app's settings"
                return row
            }
            List<Integer> now = deviceIds((cfg.settings as Map)?.get(inputName))
            if ((now as Set) != (after as Set)) {
                row.message = "Changed since the swap (now ${now.join(', ')}); left alone"
                return row
            }
            String err = writeDeviceInput(appId, cfg, input, before)
            if (err) {
                row.message = err
                return row
            }
            row.before = now
            row.after = before
            row.success = true
            row.message = verifyWrite(appId, inputName, before, cfg.settings as Map)
        } catch (Exception e) {
            row.message = "Error: ${e.message}"
        }
        if (row.success) logInfo "Undo ${row.appLabel}/${inputName}: ${row.message}"
        else logError "Undo ${row.appLabel}/${inputName}: ${row.message}"
        return row
    }

    String devices = "${lastSwap.targetLabel} (${lastSwap.targetId}) -> ${lastSwap.sourceLabel} (${lastSwap.sourceId})"
    String auditError = appendAudit(undone.collect { Map r -> auditLine("undo", devices, r) })
    if (auditError) undone << [appLabel: "Audit log", inputName: AUDIT_FILE, success: false, message: "Not written: ${auditError}"]
    state.lastUndo = undone
    state.remove("lastSwap")
}

// ---- Swap Apps Device ----
// The hub's Swap Apps Device exchanges the two device records (name, network id, driver, room,
// current states), so every app keeps its device id and reaches the new hardware. This app opens a
// pending instance, selects both devices on it and frames the hub's page; the user clicks the
// hub's own swap button. The hub swaps when its page renders after that click, and every later
// render swaps back. So an instance is framed once, and after that it is only deleted, never fetched.

// Shown in the preview when the hub accepts both devices.
private void nativeSwapSection(int sourceId, int targetId, Set<String> subscribedAttrs) {
    Map n = nativePrepare("swap", sourceId, targetId)
    if (!n.aid) {
        section("Swap Apps Device") {
            paragraph "<span style='color:gray'>The hub's Swap Apps Device can't do this swap: ${n.reason}. Use the inputs below.</span>"
        }
        return
    }
    List<Map> onTarget = (appsUsing(targetId as Long, referenceSource()) ?: []).findAll { Map a ->
        !a.dashboard && (a.id as int) != (app.id as int)
    }
    List<String> targetAttrs = targetDevice.getSupportedAttributes().collect { it.name as String }
    // A subscription can carry a value filter ("switch.on"); the attribute is the part before it.
    List<String> missing = ((subscribedAttrs ?: []).collect { (it as String).tokenize(".")[0] }.unique() - targetAttrs).sort()
    n.onTarget = onTarget.collect { Map a -> [id: a.id, label: a.label] }
    n.missingAttrs = missing
    state.native = n

    section("Swap everywhere with Swap Apps Device") {
        List<String> notes = [
            "Every app that uses ${sourceDevice.displayName} keeps using device ${sourceId}, which takes over the new hardware. This includes the inputs listed below for manual editing. No app setting changes.",
            "The name, label, room and driver move with the hardware: device ${sourceId} takes ${targetDevice.displayName}'s, and the old hardware becomes device ${targetId}.",
            "Event history stays with the device id."
        ]
        if (onTarget) {
            notes << "<span style='color:orange'>Apps that use ${targetDevice.displayName} now will move to the old hardware: " +
                onTarget.collect { Map a -> "<a href='/installedapp/configure/${a.id}' target='_blank'>${a.label}</a>" }.join(", ") + "</span>"
        }
        if (missing) {
            notes << "<span style='color:orange'>Apps subscribe to ${missing.join(', ')}, which ${targetDevice.displayName} doesn't report.</span>"
        }
        paragraph "<ul>" + notes.collect { "<li>${it}</li>" }.join("") + "</ul>"
        href "nativeSwapPage", title: "Swap with Swap Apps Device…", description: "Opens the hub's swap page with both devices selected"
    }
}

// Returns the stored swap for this pair, or opens a new pending instance with both devices
// selected. Without an `aid`, `reason` says why the hub won't do it.
private Map nativePrepare(String mode, int oldId, int newId) {
    Map n = state.native as Map
    if (n?.framed && !n.result) {
        nativeFinalize()   // a swap shown but never checked is settled and logged first
        n = state.native as Map
    }
    if (n?.aid && !n.framed && n.mode == mode && (n.oldId as int) == oldId && (n.newId as int) == newId) return n
    nativeDiscard()
    n = [mode: mode, oldId: oldId, newId: newId]
    try {
        Integer aid = openSwapInstance()
        if (aid == null) {
            n.reason = "the hub did not open its swap page"
        } else {
            n.aid = aid
            state.native = n   // stored first, so nativeDiscard() can delete it if a step below fails
            String why = swapSelect(aid, "oldDev", oldId, "the hub does not offer this device for swapping (it skips most child devices)") ?:
                swapSelect(aid, "newDev", newId, "the hub does not offer this replacement (it skips most child devices and devices whose capabilities don't match)")
            if (!why && !swapButtonShown(aid)) why = "the hub did not show its swap button"
            if (why) {
                nativeDiscard()
                n = [mode: mode, oldId: oldId, newId: newId, reason: why]
            }
        }
    } catch (Exception e) {
        logWarn "Swap Apps Device: ${e}"
        nativeDiscard()
        n = [mode: mode, oldId: oldId, newId: newId, reason: e.message ?: e.toString()]
    }
    state.native = n
    return n
}

// The Settings link /installedapp/direct/swapDevice redirects to create, then to the new instance.
private Integer openSwapInstance() {
    String path = "/installedapp/direct/swapDevice"
    for (int hop = 1; hop <= 2; hop++) {
        String loc = null
        httpGet([uri: BASE_URL, path: path, followRedirects: false, textParser: true, timeout: 15]) { resp ->
            loc = resp.headers?."Location"?.toString() ?: resp.getFirstHeader("Location")?.value
        }
        def m = (loc =~ /\/installedapp\/configure\/(\d+)/)
        if (m.find()) return m.group(1) as Integer
        def c = (loc =~ /\/installedapp\/create\/(\d+)/)
        if (hop == 1 && c.find()) {
            path = "/installedapp/create/${c.group(1)}"
        } else {
            logWarn "Swap Apps Device: unexpected redirect ${loc}"
            return null
        }
    }
    return null
}

// Selects a device on the pending instance after checking the hub offers it. Reading the page is
// safe here: the instance has not been framed, so its swap button has not been clicked.
private String swapSelect(int aid, String inputName, int deviceId, String notOffered) {
    Map cfg = fetchConfig(aid)
    Map input = cfg ? findMainInput(cfg, inputName) : null
    if (!input) return "the hub's swap page has no ${inputName} selector"
    List ids = []
    def opts = input.options
    if (opts instanceof Map) ids = (opts as Map).keySet().collect { it.toString() }
    else ((opts ?: []) as List).each { o -> if (o instanceof Map) ids.addAll((o as Map).keySet().collect { it.toString() }) }
    if (!ids.contains(deviceId.toString())) return notOffered
    String body = [
        ["formAction", "update"], ["id", aid.toString()],
        ["version", (((cfg.app ?: [:]) as Map).version ?: "1").toString()],
        ["currentPage", "mainPage"], ["pageBreadcrumbs", "[]"],
        ["${inputName}.type".toString(), "enum"], ["${inputName}.multiple".toString(), "false"],
        ["settings[${inputName}]".toString(), deviceId.toString()],
        ["referrer", "${BASE_URL}/installedapp/list".toString()],
        ["url", "${BASE_URL}/installedapp/configure/${aid}/mainPage".toString()], ["_cancellable", "false"]
    ].collect { p -> URLEncoder.encode(p[0], "UTF-8") + "=" + URLEncoder.encode(p[1], "UTF-8") }.join("&")
    String err = "no response"
    httpPost([uri: BASE_URL, path: "/installedapp/update/json", requestContentType: "application/x-www-form-urlencoded",
              body: body, textParser: true, timeout: 30]) { resp ->
        err = resp.status == 200 ? null : "HTTP ${resp.status}"
    }
    return err ? "the hub did not take the ${inputName} selection (${err})" : null
}

private boolean swapButtonShown(int aid) {
    Map cp = ((fetchConfig(aid) ?: [:]).configPage ?: [:]) as Map
    return ((cp.sections ?: []) as List).any { sec ->
        ((sec as Map).input ?: []).any { inp -> (inp as Map).type == "button" && (inp as Map).name != "closeApp" }
    }
}

// Deletes the stored pending instance without rendering it.
private void nativeDiscard() {
    Map n = state.native as Map
    if (!n?.aid) return
    try {
        httpGet([uri: BASE_URL, path: "/installedapp/delete/${n.aid}", followRedirects: false, textParser: true, timeout: 15]) { }
    } catch (Exception e) {
        logDebug "Swap Apps Device: delete of instance ${n.aid}: ${e.message}"
    }
    n.remove("aid")
    state.native = n
}

// The hub passes the last link's params to every page that follows, so the page name, never a
// param, says whether this is a swap or an undo.
Map nativeSwapPage(Map params = null) {
    return nativeFramePage("nativeSwapPage")
}

Map nativeUndoPage(Map params = null) {
    Map n = state.native as Map
    Map lastSwap = state.lastSwap as Map
    if (!(n?.mode == "undo" && n.framed) && lastSwap?.method == "native") {
        nativePrepare("undo", lastSwap.sourceId as int, lastSwap.targetId as int)
    }
    return nativeFramePage("nativeUndoPage")
}

private Map nativeFramePage(String pageName) {
    Map n = state.native as Map
    boolean undo = n?.mode == "undo"
    dynamicPage(name: pageName, title: undo ? "Undo with Swap Apps Device" : "Swap with Swap Apps Device", install: false, uninstall: false) {
        if (n?.framed) {
            section {
                paragraph "The hub's swap page was already shown. Showing it again could reverse the swap."
                href "nativeCheckPage", title: "Check the result", description: ""
            }
            return
        }
        if (!n?.aid) {
            section {
                paragraph n?.reason ? "The hub's Swap Apps Device can't do this: ${n.reason}." : "Nothing to swap."
                href "mainPage", title: "Back to Device Selection", description: ""
            }
            return
        }
        n.before = deviceRecords([n.oldId as int, n.newId as int])
        n.framed = true
        state.native = n
        section(sectionClass: "swap-confirm") {
            hideDoneButton()
            paragraph undo
                ? "Both devices are selected so the hub swaps them back. Click the hub's swap button below to undo the swap."
                : "Both devices are selected. Click the hub's swap button below to swap them in every app."
            paragraph rawHtml: true, nativeFrameHtml(n.aid as int)
            href "nativeCheckPage", title: "Check the result", description: "Also opens on its own after the swap, Done or Cancel"
        }
    }
}

// Frames the hub's page without its menu and header. Once the hub shows its result, the script
// deletes the instance (a reload would swap back) and opens the check page. It waits for the
// page's Cancel/Done button before deciding, because the hub draws the page after the frame loads.
private String nativeFrameHtml(int aid) {
    String checkUrl = "/installedapp/configure/${app.id}/nativeCheckPage"
    return """
<iframe id="swapFrame" src="/installedapp/configure/${aid}/mainPage" style="width:100%;height:360px;border:1px solid #ccc"></iframe>
<script>
(function () {
    let finished = false
    const leave = () => { if (!finished) { finished = true; window.location.href = '${checkUrl}' } }
    const timer = setInterval(() => {
        let d
        try { d = document.getElementById('swapFrame').contentDocument } catch (e) { return }
        if (!d || !d.location || d.location.href === 'about:blank') return
        if (d.location.pathname.indexOf('/installedapp/configure/${aid}') !== 0) { clearInterval(timer); leave(); return }
        d.querySelectorAll('#divSideMenu, #divMainUIMenu, #divMainUIHeader, #divMainUIFooter').forEach(e => e.remove())
        if (!d.getElementById('settings[closeApp]') || d.getElementById('settings[oldDev]')) return
        clearInterval(timer)
        fetch('/installedapp/delete/${aid}', {cache: 'no-store'}).finally(() => setTimeout(leave, 2500))
    }, 500)
})()
</script>"""
}

// The hub can pass along the params of the link that opened the swap page; they are not used here.
Map nativeCheckPage(Map params = null) {
    Map result = nativeFinalize()
    dynamicPage(name: "nativeCheckPage", title: "Swap Apps Device Result", install: false, uninstall: false, nextPage: "mainPage") {
        section {
            href "mainPage", title: "Back to Device Selection", description: ""
        }
        if (!result) {
            section { paragraph "No Swap Apps Device swap to check." }
            return
        }
        section {
            String color = result.outcome == "swapped" ? "green" : (result.outcome == "unchanged" ? "gray" : "red")
            paragraph "<span style='color:${color}'>${result.message}</span>"
            ((result.cleanup ?: []) as List).each { String line -> paragraph line }
            paragraph state.auditError
                ? "<span style='color:orange'>Not written to the <a href='/local/${AUDIT_FILE}' target='_blank'>audit log</a>: ${state.auditError}</span>"
                : "Written to the <a href='/local/${AUDIT_FILE}' target='_blank'>audit log</a>."
        }
    }
}

// Settles a framed swap once: deletes the instance unrendered, compares the two device records
// with the ones read before framing, logs the outcome and records the swap for undo.
private Map nativeFinalize() {
    Map n = state.native as Map
    if (!n?.framed) return null
    if (n.result) return n.result as Map
    nativeDiscard()
    n = state.native as Map
    String oldKey = n.oldId.toString()
    String newKey = n.newId.toString()
    Map before = (n.before ?: [:]) as Map
    Map after = deviceRecords([n.oldId as int, n.newId as int])
    Map bOld = (before[oldKey] ?: [:]) as Map, bNew = (before[newKey] ?: [:]) as Map
    Map aOld = (after[oldKey] ?: [:]) as Map, aNew = (after[newKey] ?: [:]) as Map
    boolean undo = n.mode == "undo"
    String outcome = (aOld.dni == bNew.dni && aNew.dni == bOld.dni) ? "swapped" :
        ((aOld.dni == bOld.dni && aNew.dni == bNew.dni) ? "unchanged" : "unclear")

    Map result = [outcome: outcome, cleanup: []]
    String pair = "${bOld.label} (${oldKey}) <-> ${bNew.label} (${newKey})"
    if (outcome == "swapped") {
        result.message = undo ? "Swapped back: the apps use the original hardware again." :
            "Swapped: every app that used device ${oldKey} now reaches the hardware that was ${bNew.label}."
        if (undo) {
            state.remove("lastSwap")
            state.lastUndo = [[appLabel: "Swap Apps Device", inputName: "all apps", success: true, message: "swapped back"]]
        } else {
            state.lastSwap = [method: "native", sourceId: n.oldId, targetId: n.newId,
                              sourceLabel: bOld.label, targetLabel: bNew.label]
            state.remove("lastUndo")
            app.removeSetting("sourceDevice")
            app.removeSetting("targetDevice")
            result.cleanup = nativeCleanup(n, bOld, aOld, aNew)
        }
    } else if (outcome == "unchanged") {
        result.message = "Nothing changed: the swap was not done."
    } else {
        result.message = "The devices changed in an unexpected way; check devices ${oldKey} and ${newKey}."
    }
    String line = "${new Date().format('yyyy-MM-dd HH:mm:ss z', location.timeZone)} | Swap Apps Device ${undo ? 'undo' : 'swap'} | ${pair} | all apps | " +
        (outcome == "swapped" ? "ok: network ids exchanged" : "not done: ${outcome}")
    state.auditError = appendAudit([line])
    logCmd "Swap Apps Device ${undo ? 'undo' : 'swap'} ${pair}: ${outcome}"

    n.result = result
    state.native = n
    return result
}

private List<String> nativeCleanup(Map n, Map bOld, Map aOld, Map aNew) {
    List<String> lines = []
    lines << "Device ${n.oldId}, the one your apps use, is now named <b>${aOld.label}</b>. " +
        "<a href='/device/edit/${n.oldId}' target='_blank'>Open it</a> to rename it" +
        (bOld.room && bOld.room != aOld.room ? " or put it back in room <b>${bOld.room}</b>" : "") + "."
    lines << "The old hardware is now device ${n.newId}, <b>${aNew.label}</b>. " +
        "<a href='/device/edit/${n.newId}' target='_blank'>Open it</a> to remove it or reuse it."
    List onTarget = (n.onTarget ?: []) as List
    if (onTarget) {
        lines << "<span style='color:orange'>These apps used the replacement before the swap and now use the old hardware: " +
            onTarget.collect { a -> "<a href='/installedapp/configure/${(a as Map).id}' target='_blank'>${(a as Map).label}</a>" }.join(", ") + "</span>"
    }
    List missing = (n.missingAttrs ?: []) as List
    if (missing) {
        lines << "<span style='color:orange'>Apps subscribe to ${missing.join(', ')}, which the new hardware doesn't report.</span>"
    }
    return lines
}

// [id: [dni, label, room]] for the given device ids, read from /device/fullJson.
private Map deviceRecords(List<Integer> ids) {
    Map out = [:]
    ids.each { Integer id ->
        httpGet([uri: BASE_URL, path: "/device/fullJson/${id}", timeout: 15]) { resp ->
            Map d = ((resp.data as Map)?.device ?: [:]) as Map
            out[id.toString()] = [dni: d.deviceNetworkId, label: (d.label ?: d.name) as String, room: d.roomName]
        }
    }
    return out
}

// ---- Audit Log ----
// One line per input a swap or undo touched, appended to AUDIT_FILE. File Manager has no append,
// so the file is read and written back whole.

private String auditLine(String action, String devices, Map r) {
    String ts = new Date().format("yyyy-MM-dd HH:mm:ss z", location.timeZone)
    String ids = r.before != null ? " | [${(r.before as List).join(',')}] -> [${(r.after as List).join(',')}]" : ""
    String outcome = (r.success as boolean) ? "ok" : "not done"
    return "${ts} | ${action} | ${devices} | app ${r.appLabel} (${r.appId}) input ${r.inputName}${ids} | ${outcome}: ${r.message}"
}

// Returns null on success, else the reason. A missing file is started fresh; any other read
// failure skips the write, so a log that can't be read is never replaced by a partial one.
private String appendAudit(List<String> lines) {
    if (!lines) return null
    String existing = ""
    try {
        byte[] bytes = downloadHubFile(AUDIT_FILE)
        if (bytes) existing = new String(bytes, "UTF-8")
    } catch (Exception e) {
        if (e.class.simpleName != "NoSuchFileException") {
            logWarn "Audit log not written: could not read ${AUDIT_FILE}: ${e}"
            return "could not read ${AUDIT_FILE} (${e.message})"
        }
    }
    try {
        uploadHubFile(AUDIT_FILE, (existing + lines.join("\n") + "\n").getBytes("UTF-8"))
    } catch (Exception e) {
        logWarn "Audit log not written: ${e}"
        return e.message ?: e.toString()
    }
    return null
}

// The hub's mobile dashboards list devices by room (plus "All Devices" and "Devices without
// room"), so they follow the target once it is in the source's room; nothing in them is swapped.
String dashboardAdvice(String sourceRoom, String targetRoom, String targetName, Long targetId) {
    if (sourceRoom && sourceRoom != targetRoom) {
        return "These dashboards list devices by room. Put <a href='/device/edit/${targetId}' target='_blank'>${targetName}</a> " +
            "in room <b>${sourceRoom}</b>" + (targetRoom ? " (it is in <b>${targetRoom}</b> now)" : "") +
            " so they show it. The source drops off them once it is removed."
    }
    return "Nothing to do: these dashboards list devices by room, and they will show ${targetName} on their own."
}

// ---- Apps using a device ----
// getAppsUsingDevice is fast but reaches apps this app was never granted, so the platform may
// restrict it. When it fails, appsUsing() falls back to /device/fullJson: slower, and without the
// mobile dashboards. A missing method or a sandbox block is remembered until the firmware changes;
// any other error falls back for this run only. Results are plain maps; nothing else here touches
// the platform's InstalledApp objects.
//
// Mobile dashboards (children of the hub's Easy Mobile Dashboard Parent) are flagged `dashboard`:
// they list devices by room and hold no input to swap. The flag comes from the parent's app type;
// if that ever misses, a dashboard shows up as an app to check by hand, the safe side.

private Map referenceSource() {
    String fw = location.hub.firmwareVersionString
    if (state.refSourceFw != fw) {
        state.remove("refSource")
        state.remove("refSourceFw")
    }
    if (forceLoopback) return [loopback: true, reason: "forced in settings"]
    if (state.refSource == "loopback") return [loopback: true, reason: "platform API unavailable on ${fw}"]
    return [loopback: false]
}

private void platformFailed(Map src, Exception e) {
    src.loopback = true
    src.reason = e.toString()
    if (e instanceof MissingMethodException || e instanceof SecurityException) {
        state.refSource = "loopback"
        state.refSourceFw = location.hub.firmwareVersionString
    }
    logWarn "platform API failed (${e}); using the loopback endpoints"
}

// Some built-in apps (Notifier) can hold the label "null"; fall back to the app type and id.
private String appLabelOf(Object label, Object name, Object id) {
    String l = label as String
    return (l && l != "null") ? l : "${name} ${id}"
}

// Apps using the device as [id, label, type, disabled, dashboard] maps, or null when it can't be read.
private List<Map> appsUsing(Long deviceId, Map src) {
    if (!src.loopback) {
        try {
            return (getAppsUsingDevice(deviceId) ?: []).collect { a ->
                [id: a.id as Long, label: appLabelOf(a.label, a.name, a.id), type: (a.appType?.name ?: a.name) as String,
                 disabled: a.disabled == true, dashboard: isDashboardParent(a.parentAppId as Long, src)]
            }
        } catch (Exception e) {
            platformFailed(src, e)
        }
    }
    List<Map> out = null
    try {
        httpGet([uri: BASE_URL, path: "/device/fullJson/${deviceId}", timeout: 15]) { response ->
            if (response.status == 200) {
                out = ((response.data.appsUsing ?: []) as List).collect { Map a ->
                    [id: a.id as Long, label: appLabelOf(a.label, a.name, a.id), type: a.name as String,
                     disabled: a.disabled == true, dashboard: false]
                }
            }
        }
    } catch (Exception e) {
        logError "fullJson ${deviceId}: ${e.message}"
    }
    return out
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

// ---- Lifecycle ----

void installed() {
    logDebug "installed()"
    initialize()
}

void updated() {
    logDebug "updated()"
    unsubscribe()
    initialize()
    if (debugEnable || traceEnable) runIn(1800, "logsOff")
}

void uninstalled() {
    logDebug "uninstalled()"
    nativeDiscard()
}

void initialize() {
    logDebug "initialize()"
    if (state.version != CODE_VERSION) {
        logVer "New version: ${CODE_VERSION} (was: ${state.version})"
        state.version = CODE_VERSION
    }
}

void logsOff() {
    app.updateSetting("debugEnable", [value: "false", type: "bool"])
    app.updateSetting("traceEnable", [value: "false", type: "bool"])
    logWarn "debug and trace logging disabled"
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

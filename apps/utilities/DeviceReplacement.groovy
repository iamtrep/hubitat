/*
MIT License

Copyright (c) 2025 pj

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
*/

import groovy.transform.Field
import groovy.transform.CompileStatic

@Field static final String app_version = "0.0.1"
@Field static final String BASE_URL = "http://127.0.0.1:8080"

definition(
    name: "Device Replacement Helper",
    namespace: "iamtrep",
    author: "pj",
    description: "Replace a device across all installed apps in one shot",
    category: "Utility",
    iconUrl: "",
    iconX2Url: ""
)

preferences {
    page(name: "mainPage")
    page(name: "previewPage")
    page(name: "resultsPage")
}

// ---- Page 1: Device Selection ----

Map mainPage() {
    // Clear scan/results state when returning to main page
    state.remove("pendingScan")
    state.remove("swapResults")
    state.remove("swapSelections")

    dynamicPage(name: "mainPage", title: "", install: true, uninstall: true) {
        section("App Name", hideable: true, hidden: true) {
            label title: "Set App Label", required: false
        }
        section("Device Selection") {
            input "sourceDevice", "capability.*",
                title: "Device to replace (source)",
                required: true, multiple: false, submitOnChange: true
            input "targetDevice", "capability.*",
                title: "Replacement device (target)",
                required: true, multiple: false, submitOnChange: true
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
        }

        Map lastSwap = state.lastSwap as Map
        if (lastSwap) {
            section("Last Swap") {
                String summary = "Swapped device ${lastSwap.sourceId} → ${lastSwap.targetId}"
                int successCount = (lastSwap.results as List)?.count { (it as Map).success } ?: 0
                summary += " (${successCount} app${successCount == 1 ? '' : 's'} updated)"
                paragraph summary
                input "undoLastSwap", "button", title: "Undo Last Swap"
            }
        }

        section("Logging", hideable: true, hidden: true) {
            input "logLevel", "enum",
                title: "Log level",
                options: ["warn", "info", "debug"],
                defaultValue: "info", required: true
        }
        section("") {
            paragraph "Version ${app_version}"
        }
    }
}

// ---- Page 2: Scan & Preview ----

Map previewPage() {
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
        List appsUsing = []
        try {
            httpGet("${BASE_URL}/device/fullJson/${sourceId}") { response ->
                if (response.status == 200) {
                    appsUsing = (response.data.appsUsing ?: []) as List
                }
            }
        } catch (Exception e) {
            section { paragraph "<span style='color:red'>Error fetching device info: ${e.message}</span>" }
            return
        }

        // Filter out self if skipSelf is enabled
        if (skipSelf != false) {
            appsUsing = appsUsing.findAll { (it.id as int) != (app.id as int) }
        }

        if (!appsUsing) {
            section("Results") {
                paragraph "No apps reference this device. Nothing to swap."
            }
            state.pendingScan = []
            return
        }

        // Step 2 & 3: For each app, check statusJson and configure/json
        List<Map> swappable = []
        List<Map> manual = []

        appsUsing.each { appRef ->
            int appId = appRef.id as int
            String appLabel = (appRef.label ?: "App ${appId}") as String
            String appType = (appRef.name ?: "") as String

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
                return
            }
            if (!statusData) return

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

            if (!matchingInputs) return

            // Check app state for source device ID references
            List appState = (statusData.appState ?: []) as List
            String sourceIdStr = sourceId.toString()
            boolean stateHasDeviceRef = appState.any { entry ->
                Map e = entry as Map
                String key = (e.name ?: "") as String
                String val = (e.value ?: "") as String
                key.contains(sourceIdStr) || val.contains(sourceIdStr)
            }

            // Extract subscribed attributes for the source device
            List eventSubs = (statusData.eventSubscriptions ?: []) as List
            List<String> subscribedAttrs = eventSubs.findAll { sub ->
                Map s = sub as Map
                (s.typeId as int) == sourceId && s.type == "DEVICE"
            }.collect { sub ->
                (sub as Map).name as String
            }.unique().sort()

            // Get configure/json to check if inputs are on the main config page
            Map configData = null
            try {
                httpGet("${BASE_URL}/installedapp/configure/json/${appId}") { response ->
                    if (response.status == 200) {
                        configData = response.data as Map
                    }
                }
            } catch (Exception e) {
                logWarn "Error fetching configure/json for app ${appId}: ${e.message}"
            }

            // Extract input names from configure/json
            Set<String> configInputNames = [] as Set
            if (configData) {
                Map configPage = (configData.configPage ?: [:]) as Map
                List sections = (configPage.sections ?: []) as List
                sections.each { sec ->
                    List inputs = ((sec as Map).input ?: []) as List
                    inputs.each { inp ->
                        configInputNames << ((inp as Map).name as String)
                    }
                }
            }

            matchingInputs.each { Map inputMatch ->
                String inputName = inputMatch.name
                boolean isOnConfigPage = configInputNames.contains(inputName)

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
                    stateWarning: stateWarning
                ]

                if (isOnConfigPage) {
                    swappable << entry
                } else {
                    entry.reason = "Device input not on main config page (multi-page app)"
                    manual << entry
                }
            }
        }

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
                    boolean hasWarnings = entry.capWarning || entry.targetWarning || entry.singleSelectWarning || entry.stateWarning
                    state.swapSelections[key] = hasWarnings ? "off" : "on"
                }
            }

            String X = "<i class='he-checkbox-checked'></i>"
            String O = "<i class='he-checkbox-unchecked'></i>"

            section("Swappable Apps (${swappable.size()})") {
                String table = "<style>.mdl-data-table tbody tr:hover{background-color:inherit} .swap-tbl td,.swap-tbl th {padding:8px;text-align:left;font-size:14px}</style>" +
                    "<div style='overflow-x:auto'><table class='mdl-data-table swap-tbl' style='border:2px solid black;width:100%'>" +
                    "<thead><tr style='border-bottom:2px solid black'>" +
                    "<th style='text-align:center;border-right:2px solid black'><strong>Swap</strong></th>" +
                    "<th><strong>App</strong></th><th><strong>Type</strong></th><th><strong>Input</strong></th><th><strong>Capability</strong></th><th><strong>Subscriptions</strong></th><th><strong>Warnings</strong></th>" +
                    "</tr></thead><tbody>"
                swappable.eachWithIndex { Map entry, int idx ->
                    List<String> entryWarnings = []
                    if (entry.capWarning) entryWarnings << (entry.capWarning as String)
                    if (entry.targetWarning) entryWarnings << (entry.targetWarning as String)
                    if (entry.singleSelectWarning) entryWarnings << (entry.singleSelectWarning as String)
                    if (entry.stateWarning) entryWarnings << (entry.stateWarning as String)
                    String warningCell = entryWarnings ? "<span style='color:orange'>${entryWarnings.join('<br>')}</span>" : "<span style='color:green'>&#10003;</span>"
                    List<String> attrs = (entry.subscribedAttrs ?: []) as List<String>
                    String subsCell = attrs ? attrs.join(", ") : "<span style='color:gray'>-</span>"
                    boolean selected = state.swapSelections[idx.toString()] != "off"
                    table += "<tr>" +
                        "<td style='text-align:center;border-right:2px solid black'>${buttonLink("btnSwapSel:${idx}", selected ? X : O, "#1A77C9")}</td>" +
                        "<td><a href='/installedapp/configure/${entry.appId}' target='_blank'>${entry.appLabel}</a></td>" +
                        "<td>${entry.appType}</td>" +
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
            section("Manual Swap Required (${manual.size()})") {
                String table = "<table style='border-collapse:collapse;width:100%'>" +
                    "<thead><tr style='background:#ddd'>" +
                    "<th ${td}>App</th><th ${td}>Type</th><th ${td}>Input</th><th ${td}>Subscriptions</th><th ${td}>Reason</th>" +
                    "</tr></thead><tbody>"
                manual.each { Map entry ->
                    List<String> attrs = (entry.subscribedAttrs ?: []) as List<String>
                    String subsCell = attrs ? attrs.join(", ") : "<span style='color:gray'>-</span>"
                    table += "<tr>" +
                        "<td ${td}><a href='/installedapp/configure/${entry.appId}' target='_blank'>${entry.appLabel}</a></td>" +
                        "<td ${td}>${entry.appType}</td>" +
                        "<td ${td}>${entry.inputName}</td>" +
                        "<td ${td}>${subsCell}</td>" +
                        "<td ${td}>${entry.reason}</td>" +
                        "</tr>"
                }
                table += "</tbody></table>"
                paragraph table
            }
        }

        if (!swappable && !manual) {
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
                    paragraph "<span style='color:red'><b>&#9888; Recommended:</b> <a href='/hub/backup' target='_blank'>Create a local hub backup</a> before executing the swap.</span>"
                    href "resultsPage", title: "Execute Swap", description: "Swap ${sourceDevice.displayName} → ${targetDevice.displayName} in ${selectedCount} of ${swappable.size()} app(s)"
                }
            }
        } else {
            state.pendingScan = []
        }
    }
}

// ---- Page 3: Execute & Report ----

Map resultsPage() {
    dynamicPage(name: "resultsPage", title: "Swap Results", install: true) {
        section {
            href "mainPage", title: "Back to Device Selection", description: ""
        }
        List<Map> allPending = (state.pendingScan ?: []) as List<Map>

        // Filter to only user-selected entries
        Map selections = state.swapSelections ?: [:]
        List<Map> pending = allPending.findAll { Map entry ->
            selections[entry.index.toString()] != "off"
        }

        if (!pending) {
            section { paragraph "Nothing to swap. Go back and select at least one app." }
            return
        }

        int sourceId = sourceDevice.id as int
        int targetId = targetDevice.id as int
        List<Map> results = []

        pending.each { Map entry ->
            int appId = entry.appId as int
            String appLabel = entry.appLabel as String
            String inputName = entry.inputName as String
            boolean targetAlreadyPresent = entry.targetAlreadyPresent as boolean

            Map result = [appId: appId, appLabel: appLabel, inputName: inputName, success: false, message: ""]

            try {
                // Fetch full config for POST body construction
                Map configData = null
                httpGet("${BASE_URL}/installedapp/configure/json/${appId}") { response ->
                    if (response.status == 200) {
                        configData = response.data as Map
                    }
                }
                if (!configData) {
                    result.message = "Could not fetch app config"
                    results << result
                    return
                }

                // Store original device IDs for undo
                result.originalDeviceIds = entry.currentDeviceIds

                // Build and send POST
                String postResult = buildAndSendSwap(configData, appId, inputName, sourceId, targetId, targetAlreadyPresent)
                if (postResult == "success") {
                    // Verify
                    String verifyResult = verifySwap(appId, inputName, sourceId, targetId)
                    result.success = true
                    result.message = verifyResult
                } else {
                    result.message = postResult
                }
            } catch (Exception e) {
                result.message = "Error: ${e.message}"
                logWarn "Swap failed for ${appLabel}/${inputName}: ${e.message}"
            }

            results << result
        }

        // Display results
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
        }

        // Store for undo
        List successfulSwaps = results.findAll { it.success }
        if (successfulSwaps) {
            state.lastSwap = [
                sourceId: sourceId,
                targetId: targetId,
                sourceLabel: sourceDevice.displayName,
                targetLabel: targetDevice.displayName,
                results: successfulSwaps
            ]
        }

        state.remove("pendingScan")
        state.swapResults = results
    }
}

// ---- POST Body Construction & Execution ----

private String buildAndSendSwap(Map configData, int appId, String inputName, int sourceId, int targetId, boolean targetAlreadyPresent) {
    Map appInfo = (configData.app ?: [:]) as Map
    Map configPage = (configData.configPage ?: [:]) as Map
    Map settings = (configData.settings ?: [:]) as Map
    String pageName = (configPage.name ?: "mainPage") as String
    String appVersion = (appInfo.version ?: "1") as String
    String appLabel = (appInfo.label ?: "") as String

    // Build form fields as ordered list of tuples
    List<List<String>> fields = []

    // Header fields
    fields << ["_action_update", "Done"]
    fields << ["formAction", "update"]
    fields << ["id", appId.toString()]
    fields << ["version", appVersion]
    fields << ["appTypeId", ""]
    fields << ["appTypeName", ""]
    fields << ["currentPage", pageName]
    fields << ["pageBreadcrumbs", "%5B%5D"]

    // Label inputs from body elements
    List sections = (configPage.sections ?: []) as List
    sections.each { sec ->
        List bodyElements = ((sec as Map).body ?: []) as List
        bodyElements.each { elem ->
            Map e = elem as Map
            if (e.element == "label") {
                String labelName = (e.name ?: "label") as String
                fields << ["${labelName}.type", "text"]
                fields << [labelName, appLabel]
            }
        }
    }

    // Regular inputs in order
    sections.each { sec ->
        List inputs = ((sec as Map).input ?: []) as List
        inputs.each { inp ->
            Map input = inp as Map
            String name = input.name as String
            String type = (input.type ?: "") as String
            boolean multiple = input.multiple as boolean

            fields << ["${name}.type", type]
            fields << ["${name}.multiple", multiple.toString()]

            if (type == "bool") {
                // Bool inputs
                fields << ["checkbox[${name}]", "on"]
                String val = settingToString(settings[name])
                fields << ["settings[${name}]", val]
            } else if (type.startsWith("capability.")) {
                // Device input — compute new device ID list
                List<Integer> currentIds = []
                if (settings[name] instanceof Map) {
                    currentIds = (settings[name] as Map).keySet().collect { it.toString().toInteger() }
                }

                List<Integer> newIds
                if (name == inputName) {
                    // This is the input we're swapping
                    newIds = currentIds.collect() as List<Integer>
                    newIds.removeAll { it == sourceId }
                    if (!targetAlreadyPresent) {
                        // Insert target at the position where source was
                        int sourceIdx = currentIds.indexOf(sourceId)
                        if (sourceIdx >= 0 && sourceIdx <= newIds.size()) {
                            newIds.add(sourceIdx, targetId)
                        } else {
                            newIds << targetId
                        }
                    }
                } else {
                    newIds = currentIds
                }

                String idStr = newIds.collect { it.toString() }.join(",")
                fields << ["settings[${name}]", idStr]
                fields << ["deviceList", name]
                fields << ["", ""]
            } else {
                // Other inputs
                String val = settingToString(settings[name])
                fields << ["settings[${name}]", val]
            }
        }
    }

    // Footer fields
    fields << ["referrer", "${BASE_URL}/installedapp/list"]
    fields << ["url", "${BASE_URL}/installedapp/configure/${appId}/${pageName}"]
    fields << ["_cancellable", "false"]

    // URL-encode and POST
    String body = fields.collect { pair ->
        URLEncoder.encode(pair[0], "UTF-8") + "=" + URLEncoder.encode(pair[1], "UTF-8")
    }.join("&")

    logDebug "POST body for app ${appId}: ${body}"

    String postResult = "unknown"
    httpPost([
        uri: BASE_URL,
        path: "/installedapp/update/json",
        requestContentType: "application/x-www-form-urlencoded",
        body: body,
        textParser: true
    ]) { response ->
        if (response.status == 200) {
            String respText = response.data?.text ?: ""
            if (respText.contains('"success"')) {
                postResult = "success"
            } else {
                postResult = "Unexpected response: ${respText}"
            }
        } else {
            postResult = "HTTP ${response.status}"
        }
    }

    return postResult
}

@CompileStatic
private String settingToString(Object value) {
    if (value == null) return "[]"
    if (value instanceof Map) return (value as Map).keySet().collect { it.toString() }.join(",")
    return value.toString()
}

// ---- Post-Swap Verification ----

private String verifySwap(int appId, String inputName, int sourceId, int targetId) {
    List<String> notes = []

    try {
        httpGet("${BASE_URL}/installedapp/statusJson/${appId}") { response ->
            if (response.status == 200) {
                Map data = response.data as Map
                List appSettings = (data.appSettings ?: []) as List
                Map targetSetting = appSettings.find { (it as Map).name == inputName } as Map

                if (targetSetting) {
                    List deviceIds = (targetSetting.deviceIdsForDeviceList ?: []) as List
                    boolean hasTarget = deviceIds.any { (it as int) == targetId }
                    boolean hasSource = deviceIds.any { (it as int) == sourceId }

                    if (hasTarget && !hasSource) {
                        notes << "Verified: target present, source removed"
                    } else {
                        if (hasSource) notes << "Warning: source still present"
                        if (!hasTarget) notes << "Warning: target not found"
                    }
                } else {
                    notes << "Could not find input in statusJson"
                }

                // Check event subscriptions
                List subs = (data.eventSubscriptions ?: []) as List
                boolean subFound = subs.any { (it as Map).typeId == targetId }
                if (subFound) {
                    notes << "Subscriptions confirmed"
                }
            }
        }
    } catch (Exception e) {
        notes << "Verification error: ${e.message}"
    }

    return notes.join("; ") ?: "Done"
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

private void performUndo() {
    Map lastSwap = state.lastSwap as Map
    if (!lastSwap) {
        logWarn "No swap to undo"
        return
    }

    int originalSource = lastSwap.sourceId as int
    int originalTarget = lastSwap.targetId as int
    List<Map> swapResults = (lastSwap.results ?: []) as List<Map>

    logInfo "Undoing swap: ${originalTarget} → ${originalSource} across ${swapResults.size()} app(s)"

    swapResults.each { Map entry ->
        int appId = entry.appId as int
        String inputName = entry.inputName as String

        try {
            Map configData = null
            httpGet("${BASE_URL}/installedapp/configure/json/${appId}") { response ->
                if (response.status == 200) {
                    configData = response.data as Map
                }
            }
            if (configData) {
                // Reverse: swap target back to source
                // Target is already present (it's what we swapped in), source is not
                String result = buildAndSendSwap(configData, appId, inputName, originalTarget, originalSource, false)
                if (result == "success") {
                    logInfo "Undo successful for ${entry.appLabel}/${inputName}"
                } else {
                    logWarn "Undo failed for ${entry.appLabel}/${inputName}: ${result}"
                }
            }
        } catch (Exception e) {
            logWarn "Undo error for app ${appId}: ${e.message}"
        }
    }

    state.remove("lastSwap")
    logInfo "Undo complete"
}

// ---- Lifecycle ----

void installed() {
    logDebug "installed()"
    initialize()
}

void updated() {
    logDebug "updated()"
    initialize()
}

void uninstalled() {
    logDebug "uninstalled()"
}

void initialize() {
    logDebug "initialize()"
}

// ---- Logging helpers ----

private void logDebug(String msg) {
    if (logLevel == "debug") log.debug "${app.getLabel()}: ${msg}"
}

private void logInfo(String msg) {
    if (logLevel in ["info", "debug"]) log.info "${app.getLabel()}: ${msg}"
}

private void logWarn(String msg) {
    log.warn "${app.getLabel()}: ${msg}"
}

private void logError(String msg) {
    log.error "${app.getLabel()}: ${msg}"
}

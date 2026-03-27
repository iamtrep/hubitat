/**
 * Hub Diagnostics
 *
 * Comprehensive hub diagnostics: inventory, performance tracking, network analysis,
 * health scoring, snapshot comparison, and exportable reports.
 *
 * Consolidates Hub Diagnostic Analyzer, Hub Performance Analyzer, and
 * Hub Configuration Analyzer into a single unified app.
 *
 * Author: PJ
 * Version: 3.0.0
 */

import groovy.transform.Field
import groovy.transform.CompileStatic

// API Endpoint URLs (localhost access)
@Field static final String DEVICES_LIST_URL = "http://127.0.0.1:8080/hub2/devicesList"
@Field static final String DEVICE_FULL_JSON_URL = "http://127.0.0.1:8080/device/fullJson/"
@Field static final String APPS_LIST_URL = "http://127.0.0.1:8080/hub2/appsList"
@Field static final String APP_FULL_JSON_URL = "http://127.0.0.1:8080/app/fullJson/"
@Field static final String NETWORK_CONFIG_URL = "http://127.0.0.1:8080/hub2/networkConfiguration"
@Field static final String ZWAVE_DETAILS_URL = "http://127.0.0.1:8080/hub/zwaveDetails/json"
@Field static final String ZIGBEE_DETAILS_URL = "http://127.0.0.1:8080/hub/zigbeeDetails/json"
@Field static final String MATTER_DETAILS_URL = "http://127.0.0.1:8080/hub/matterDetails/json"
@Field static final String HUB_DATA_URL = "http://127.0.0.1:8080/hub2/hubData"
@Field static final String MODES_URL = "http://127.0.0.1:8080/modes/json"
@Field static final String HUB_MESH_URL = "http://127.0.0.1:8080/hub2/hubMeshJson"
@Field static final String STATE_COMPRESSION_URL = "http://127.0.0.1:8080/hub/advanced/stateCompressionStatus"
@Field static final String FREE_MEMORY_URL = "http://127.0.0.1:8080/hub/advanced/freeOSMemoryLast"
@Field static final String RUNTIME_STATS_URL = "http://127.0.0.1:8080/logs/json"

// Protocol constants
@Field static final String PROTOCOL_ZIGBEE = "zigbee"
@Field static final String PROTOCOL_ZWAVE = "zwave"
@Field static final String PROTOCOL_MATTER = "matter"
@Field static final String PROTOCOL_LAN = "lan"
@Field static final String PROTOCOL_VIRTUAL = "virtual"
@Field static final String PROTOCOL_MAKER = "maker"
@Field static final String PROTOCOL_CLOUD = "cloud"
@Field static final String PROTOCOL_HUBMESH = "hubmesh"
@Field static final String PROTOCOL_OTHER = "other"

@Field static final long ONE_DAY_MS = 86400000

// Protocol display names
@Field static final Map PROTOCOL_DISPLAY = [
    "zwave": "Z-Wave",
    "zigbee": "Zigbee",
    "matter": "Matter",
    "hubmesh": "Hub Mesh",
    "lan": "LAN/IP",
    "virtual": "Virtual",
    "cloud": "Cloud",
    "maker": "Maker API",
    "other": "Other"
]

// File names for persistence
@Field static final String SNAPSHOTS_FILE = "hub_diagnostics_snapshots.json"
@Field static final String CHECKPOINTS_FILE = "hub_diagnostics_checkpoints.json"

definition(
    name: "Hub Diagnostics",
    namespace: "iamtrep",
    author: "PJ",
    description: "Comprehensive hub diagnostics: inventory, performance tracking, network analysis, health scoring, and snapshot comparison",
    category: "Utility",
    singleInstance: true,
    importUrl: "https://raw.githubusercontent.com/iamtrep/hubitat/refs/heads/main/apps/utilities/HubDiagnostics.groovy",
    iconUrl: "",
    iconX2Url: "",
    iconX3Url: ""
)

preferences {
    page(name: "dashboardPage")
    page(name: "devicesPage")
    page(name: "appsPage")
    page(name: "networkPage")
    page(name: "performancePage")
    page(name: "systemHealthPage")
    page(name: "snapshotsPage")
    page(name: "settingsPage")
}

// ===== PAGE METHODS =====

Map dashboardPage() {
    dynamicPage(name: "dashboardPage", title: "Hub Diagnostics", install: true, uninstall: true) {
        section("Quick Summary") {
            paragraph generateQuickSummary()
        }

        section("Navigation") {
            href "devicesPage", title: "Devices", description: "Device inventory and protocol analysis"
            href "appsPage", title: "Applications", description: "App inventory and parent/child hierarchy"
            href "networkPage", title: "Network & Wireless", description: "Z-Wave, Zigbee, Matter, Hub Mesh"
            href "performancePage", title: "Performance", description: "Runtime stats and checkpoint comparison"
            href "systemHealthPage", title: "System Health", description: "Resources, health score, and alerts"
            href "snapshotsPage", title: "Snapshots", description: "Configuration snapshots and diff"
            href "settingsPage", title: "Settings", description: "Thresholds, auto-scheduling, and options"
        }

        section("Actions") {
            input "btnDashSnapshot", "button", title: "Create Snapshot"
            input "btnDashCheckpoint", "button", title: "Create Checkpoint"
        }

        section("Installation") {
            label title: "Assign a name", required: false
        }
    }
}

Map devicesPage() {
    Map deviceStats = analyzeDevices()

    dynamicPage(name: "devicesPage", title: "Device Analysis") {
        section("Device Summary") {
            paragraph formatMetricsTable([
                ["Total Devices", deviceStats.totalDevices],
                ["Active Devices", "${deviceStats.activeDevices} (${settings.inactivityDays ?: 7} days)"],
                ["Inactive Devices", deviceStats.inactiveDevices],
                ["Disabled Devices", deviceStats.disabledDevices],
                ["Parent Devices", deviceStats.parentDevices],
                ["Child Devices", deviceStats.childDevices],
                ["Hub Mesh Linked", deviceStats.linkedDevices],
                ["Battery-Powered", deviceStats.batteryDevices]
            ])
        }

        section("Protocol Distribution") {
            paragraph formatProtocolTable(deviceStats.byProtocol)
        }

        section("All Devices") {
            paragraph generateSortableTable("devTable", [
                [label: "Name", field: "name", type: "string"],
                [label: "Type", field: "type", type: "string"],
                [label: "Protocol", field: "protocol", type: "string"],
                [label: "Status", field: "status", type: "string"],
                [label: "Last Activity", field: "lastActivity", type: "string"],
                [label: "Battery", field: "battery", type: "number"],
                [label: "Parent App", field: "parentApp", type: "string"],
                [label: "Room", field: "room", type: "string"]
            ], deviceStats.allDevices.collect { Map dev ->
                Map row = [
                    name: dev.name ?: "",
                    type: dev.type ?: "",
                    protocol: PROTOCOL_DISPLAY[dev.protocol] ?: (dev.protocol ?: "").toString().capitalize(),
                    status: dev.status ?: "",
                    lastActivity: dev.lastActivity ?: "Never",
                    battery: dev.battery != null ? dev.battery : "",
                    parentApp: dev.parentApp ?: "-",
                    room: dev.room ?: "-"
                ]
                // Color overrides
                if (dev.status == "Active") row._statusColor = "#388e3c"
                else if (dev.status == "Disabled") row._statusColor = "#d32f2f"
                else if (dev.status == "Inactive") row._statusColor = "#ff9800"
                if (dev.battery != null) {
                    if (dev.battery <= 20) row._batteryColor = "#d32f2f"
                    else if (dev.battery <= 50) row._batteryColor = "#ff9800"
                    else row._batteryColor = "#388e3c"
                }
                return row
            })
        }

        if (deviceStats.byParentApp && deviceStats.byParentApp.size() > 0) {
            section("Devices by Parent App") {
                paragraph formatSimpleTable(deviceStats.byParentApp, "Parent App", "Count")
            }
        }

        if (deviceStats.lowBatteryDevices && deviceStats.lowBatteryDevices.size() > 0) {
            section("Low Battery Alerts") {
                String batteryHtml = deviceStats.lowBatteryDevices.collect { Map dev ->
                    "<span style='color: #d32f2f;'>${dev.name}: ${dev.battery}%</span>"
                }.join("<br>")
                paragraph batteryHtml
            }
        }

        if (deviceStats.inactiveDevicesList && deviceStats.inactiveDevicesList.size() > 0) {
            section("Inactive Devices (Last ${settings.inactivityDays ?: 7} Days)") {
                String inactiveHtml = deviceStats.inactiveDevicesList.take(20).collect { Map dev ->
                    "${dev.name} - Last Activity: ${dev.lastActivity ?: 'Never'}"
                }.join("<br>")
                if (deviceStats.inactiveDevicesList.size() > 20) {
                    inactiveHtml += "<br><i>Showing 20 of ${deviceStats.inactiveDevicesList.size()} inactive devices</i>"
                }
                paragraph inactiveHtml
            }
        }
    }
}

Map appsPage() {
    Map appStats = analyzeApps()

    dynamicPage(name: "appsPage", title: "Application Analysis") {
        section("Application Summary") {
            int runtimeTotal = appStats.runtimeTotalApps ?: 0
            int apiTotal = appStats.totalApps
            List metrics = [
                ["App Instances (from API)", apiTotal],
                ["System Apps", appStats.builtInApps],
                ["User Apps", appStats.userApps],
                ["Parent Apps", appStats.parentApps],
                ["Child Apps", appStats.childApps]
            ]
            if (runtimeTotal > apiTotal) {
                metrics.add(0, ["Total Apps (incl. platform)", runtimeTotal])
            }
            paragraph formatMetricsTable(metrics)
        }

        if (appStats.parentChildHierarchy && appStats.parentChildHierarchy.size() > 0) {
            section("Parent/Child App Hierarchy") {
                paragraph formatParentChildHierarchy(appStats.parentChildHierarchy)
            }
        }

        section("App Instances by Type") {
            paragraph formatAppsByTypeTable(appStats)
        }
    }
}

Map networkPage() {
    Map networkData = analyzeNetwork()

    dynamicPage(name: "networkPage", title: "Network & Wireless Analysis") {
        section("Network Configuration") {
            if (networkData.network && !networkData.network.error) {
                Map net = networkData.network
                paragraph "<b>IP Address:</b> ${net.lanAddr ?: 'N/A'}"
                paragraph "<b>Connection Type:</b> ${net.usingStaticIP ? 'Static IP' : 'DHCP'}"
                if (net.usingStaticIP && net.staticIP) {
                    paragraph "<b>Static IP:</b> ${net.staticIP}"
                }
                paragraph "<b>Gateway:</b> ${net.staticGateway ?: 'N/A'}"
                paragraph "<b>Subnet Mask:</b> ${net.staticSubnetMask ?: 'N/A'}"
                if (net.dnsServers && net.dnsServers.size() > 0) {
                    paragraph "<b>DNS Servers:</b> ${net.dnsServers.join(', ')}"
                }
                paragraph "<b>WiFi Available:</b> ${net.hasWiFi ? 'Yes' : 'No'}"
                paragraph "<b>Ethernet:</b> ${net.hasEthernet ? 'Connected' : 'Not Connected'}"
            } else {
                paragraph "<i>Network configuration unavailable</i>"
            }
        }

        section("Z-Wave Network") {
            if (networkData.zwave && !networkData.zwave.error) {
                Map zw = networkData.zwave
                paragraph "<b>Enabled:</b> ${zw.enabled ? 'Yes' : 'No'}"
                paragraph "<b>Healthy:</b> ${zw.healthy ? 'Yes' : 'No'}"
                paragraph "<b>Region:</b> ${zw.region ?: 'N/A'}"
                paragraph "<b>Node Count:</b> ${zw.nodes ? zw.nodes.size() : 0}"
                paragraph "<b>Z-Wave JS Available:</b> ${zw.zwaveJSAvailable ? 'Yes' : 'No'}"
                paragraph "<b>Z-Wave JS Enabled:</b> ${zw.zwaveJS ? 'Yes' : 'No'}"
                if (zw.isRadioUpdateNeeded) {
                    paragraph "<b style='color: #ff9800;'>Radio Update Recommended</b>"
                }
            } else {
                paragraph "<i>Z-Wave details unavailable</i>"
            }
        }

        section("Zigbee Network") {
            if (networkData.zigbee && !networkData.zigbee.error) {
                Map zb = networkData.zigbee
                paragraph "<b>Enabled:</b> ${zb.enabled ? 'Yes' : 'No'}"
                paragraph "<b>Healthy:</b> ${zb.healthy ? 'Yes' : 'No'}"
                paragraph "<b>Network State:</b> ${zb.networkState ?: 'Unknown'}"
                paragraph "<b>Channel:</b> ${zb.channel ?: 'N/A'}"
                paragraph "<b>PAN ID:</b> ${zb.panId ?: 'N/A'}"
                paragraph "<b>Extended PAN ID:</b> ${zb.extendedPanId ?: 'N/A'}"
                paragraph "<b>Device Count:</b> ${zb.devices ? zb.devices.size() : 0}"
                paragraph "<b>Join Mode:</b> ${zb.inJoinMode ? 'Active' : 'Inactive'}"
                if (zb.weakChannel) {
                    paragraph "<b style='color: #d32f2f;'>Weak Channel Detected</b>"
                }
            } else {
                paragraph "<i>Zigbee details unavailable</i>"
            }
        }

        section("Matter Network") {
            if (networkData.matter && !networkData.matter.error) {
                Map mt = networkData.matter
                paragraph "<b>Enabled:</b> ${mt.enabled ? 'Yes' : 'No'}"
                paragraph "<b>Installed:</b> ${mt.installed ? 'Yes' : 'No'}"
                paragraph "<b>Network State:</b> ${mt.networkState ?: 'Unknown'}"
                paragraph "<b>Fabric ID:</b> ${mt.fabricId ?: 'N/A'}"
                paragraph "<b>Device Count:</b> ${mt.devices ? mt.devices.size() : 0}"
                if (mt.ipAddresses && mt.ipAddresses.size() > 0) {
                    paragraph "<b>IPv6 Addresses:</b>"
                    mt.ipAddresses.each { addr ->
                        paragraph "&nbsp;&nbsp;${addr.interface}: ${addr.address}"
                    }
                }
                if (mt.rebootRequired) {
                    paragraph "<b style='color: #d32f2f;'>Reboot Required</b>"
                }
            } else {
                paragraph "<i>Matter details unavailable or no Matter devices</i>"
            }
        }

        section("Hub Mesh") {
            if (networkData.hubMesh && !networkData.hubMesh.error) {
                Map hm = networkData.hubMesh
                paragraph "<b>Status:</b> ${hm.hubMeshEnabled ? 'Enabled' : 'Disabled'}"

                if (hm.hubList && hm.hubList.size() > 0) {
                    paragraph "<b>Linked Hubs:</b> ${hm.hubList.size()}"
                    hm.hubList.each { hub ->
                        String status = hub.offline ? 'Offline' : 'Online'
                        String statusColor = hub.offline ? '#d32f2f' : '#388e3c'
                        paragraph "<span style='color: ${statusColor};'><b>${hub.name}</b> (${hub.ipAddress}) ${status}</span>"
                        paragraph "&nbsp;&nbsp;Shared Devices: ${hub.deviceIds ? hub.deviceIds.size() : 0}"
                        paragraph "&nbsp;&nbsp;Shared Variables: ${hub.hubVarNames ? hub.hubVarNames.size() : 0}"
                    }
                } else {
                    paragraph "<i>No linked hubs</i>"
                }

                paragraph "<b>Shared Devices from this Hub:</b> ${hm.sharedDevices ? hm.sharedDevices.size() : 0}"
                paragraph "<b>Devices Linked from Other Hubs:</b> ${hm.localLinkedDevices ? hm.localLinkedDevices.size() : 0}"
                paragraph "<b>Shared Hub Variables:</b> ${hm.sharedHubVariables ? hm.sharedHubVariables.size() : 0}"
                paragraph "<b>Linked Hub Variables:</b> ${hm.localLinkedHubVariables ? hm.localLinkedHubVariables.size() : 0}"
            } else {
                paragraph "<i>Hub Mesh details unavailable</i>"
            }
        }
    }
}

Map performancePage() {
    dynamicPage(name: "performancePage", title: "Performance Analysis") {
        section("Current Runtime Stats") {
            paragraph getCurrentStatsSummary()
        }

        List checkpoints = loadCheckpoints()

        section("Checkpoint Management") {
            paragraph "Current checkpoints: ${checkpoints.size()}/${settings.maxCheckpoints ?: 10}"
            input "btnCreateCheckpoint", "button", title: "Create Checkpoint Now"
            if (checkpoints.size() > 0) {
                input "btnClearCheckpoints", "button", title: "Clear All Checkpoints"
            }
        }

        if (checkpoints.size() > 0) {
            section("Saved Checkpoints") {
                paragraph generateCheckpointTable(checkpoints)
            }

            section("Compare Performance") {
                // Build baseline options including "Since Startup"
                Map baselineOptions = ["startup": "Since Startup (all zeros)"]
                checkpoints.eachWithIndex { cp, idx ->
                    baselineOptions["${idx}"] = "Checkpoint ${idx + 1} - ${cp.timestamp}"
                }
                input "compareBaseline", "enum", title: "Baseline (earlier)", options: baselineOptions, required: false, submitOnChange: true

                if (compareBaseline != null) {
                    Map checkpointOptions = ["now": "Now"]

                    if (compareBaseline == "startup") {
                        checkpoints.eachWithIndex { cp, idx ->
                            checkpointOptions["${idx}"] = "Checkpoint ${idx + 1} - ${cp.timestamp}"
                        }
                    } else {
                        int baselineIdx = compareBaseline.toInteger()
                        Map baselineCp = checkpoints[baselineIdx]

                        checkpoints.eachWithIndex { cp, idx ->
                            if (idx < baselineIdx) {
                                long baselineDevTotal = baselineCp.stats.deviceStats.sum { it.total ?: 0 } ?: 0
                                long checkpointDevTotal = cp.stats.deviceStats.sum { it.total ?: 0 } ?: 0

                                if (checkpointDevTotal >= baselineDevTotal) {
                                    checkpointOptions["${idx}"] = "Checkpoint ${idx + 1} - ${cp.timestamp}"
                                }
                            }
                        }
                    }

                    if (checkpointOptions.size() <= 1) {
                        paragraph "<span style='color: #ff9800;'>No valid checkpoints for comparison with selected baseline (reboot may have occurred). Select 'Now' to compare current state.</span>"
                    }
                    input "compareCheckpoint", "enum", title: "Checkpoint (later)", options: checkpointOptions, required: false, submitOnChange: true

                    if (compareBaseline != null && compareCheckpoint != null) {
                        input "btnCompare", "button", title: "Compare Selected"
                    }
                }
            }

            if (state.lastPerformanceComparison) {
                section("Comparison Results") {
                    paragraph state.lastPerformanceComparison
                }
            }
        }
    }
}

Map systemHealthPage() {
    Map systemHealth = analyzeSystemHealth()

    Map hubInfo = getHubInfo()

    dynamicPage(name: "systemHealthPage", title: "System Health") {
        section("Hub Information") {
            if (location.hubs && location.hubs.size() > 0) {
                def hub = location.hubs[0]
                paragraph "<b>Hub Name:</b> ${hubInfo.name}"
                paragraph "<b>Hub ID:</b> ${hub.id ?: 'N/A'}"
                paragraph "<b>Firmware Version:</b> ${hubInfo.firmware}"
                paragraph "<b>Hardware Model:</b> ${hubInfo.hardware}"
                paragraph "<b>Zigbee ID:</b> ${hub.zigbeeId ?: 'N/A'}"
                paragraph "<b>Local IP:</b> ${hubInfo.ip}"
            } else {
                paragraph "<span style='color: red;'>Hub information not available</span>"
            }
        }

        section("System Resources") {
            if (systemHealth.memory) {
                Map mem = systemHealth.memory
                paragraph "<b>Free OS Memory:</b> ${formatMemory(mem.freeOSMemory ?: 0)}"
                paragraph "<b>CPU Average (5m):</b> ${String.format('%.2f', (mem.cpuAvg5min ?: 0) as float)}%"
                paragraph "<b>Total Java Memory:</b> ${formatMemory(mem.totalJavaMemory ?: 0)}"
                paragraph "<b>Free Java Memory:</b> ${formatMemory(mem.freeJavaMemory ?: 0)}"
                paragraph "<b>Direct Java Memory:</b> ${formatMemory(mem.directJavaMemory ?: 0)}"

                if (mem.freeOSMemory && mem.freeOSMemory < 102400) {
                    paragraph "<span style='color: #d32f2f;'><b>Warning: Low OS memory</b></span>"
                }
            } else {
                paragraph "<i>Memory information unavailable</i>"
            }
        }

        section("Runtime Statistics") {
            if (systemHealth.runtime) {
                Map rt = systemHealth.runtime
                paragraph "<b>Hub Uptime:</b> ${rt.uptime ?: 'N/A'}"
                paragraph "<b>Devices Runtime:</b> ${rt.totalDevicesRuntime ?: 'N/A'} (${rt.devicePct ?: 'N/A'})"
                paragraph "<b>Apps Runtime:</b> ${rt.totalAppsRuntime ?: 'N/A'} (${rt.appPct ?: 'N/A'})"
            } else {
                paragraph "<i>Runtime statistics unavailable</i>"
            }
        }

        section("Database & Storage") {
            if (systemHealth.stateCompression) {
                Map sc = systemHealth.stateCompression
                paragraph "<b>State Compression:</b> ${sc.enabled ? 'Enabled' : 'Disabled'}"
                if (sc.size) {
                    paragraph "<b>Database Size:</b> ${sc.size}"
                }
            }
        }

        section("Health Score") {
            int score = calculateHealthScore(systemHealth)
            String scoreColor = score >= 80 ? "#388e3c" : (score >= 60 ? "#ff9800" : "#d32f2f")
            paragraph "<div style='text-align: center; font-size: 48px; color: ${scoreColor};'><b>${score}</b></div>"
            paragraph "<div style='text-align: center;'>Overall Health Score (0-100)</div>"

            if (systemHealth.alerts && systemHealth.alerts.size() > 0) {
                paragraph "<b style='color: #d32f2f;'>Alerts:</b>"
                systemHealth.alerts.each { String alert ->
                    paragraph alert
                }
            }
        }

        section("Location Information") {
            paragraph "<b>Location Name:</b> ${location.name ?: 'N/A'}"
            paragraph "<b>Current Mode:</b> ${location.currentMode ?: 'N/A'}"
            paragraph "<b>Available Modes:</b> ${location.modes?.collect { it.name }?.join(', ') ?: 'N/A'}"
            paragraph "<b>Temperature Scale:</b> ${location.temperatureScale ?: 'N/A'}"
            paragraph "<b>Time Zone:</b> ${location.timeZone?.ID ?: 'N/A'}"
        }
    }
}

Map snapshotsPage() {
    List snapshots = loadSnapshots()

    dynamicPage(name: "snapshotsPage", title: "Snapshots & Reports") {
        section("Snapshot Management") {
            paragraph "Snapshots capture the complete hub configuration for historical tracking and comparison."
            paragraph "<b>Current Snapshots:</b> ${snapshots.size()} / ${settings.maxSnapshots ?: 10}"
        }

        section("Actions") {
            input "btnCreateSnapshot", "button", title: "Create Snapshot Now"
            if (snapshots.size() > 0) {
                input "btnClearSnapshots", "button", title: "Clear All Snapshots"
            }
            input "btnFullReport", "button", title: "Generate Full Report"
        }

        if (snapshots.size() > 0) {
            section("Available Snapshots") {
                paragraph generateSnapshotsTable(snapshots)
            }
        } else {
            section {
                paragraph "<i>No snapshots available. Create your first snapshot to get started.</i>"
            }
        }

        if (snapshots.size() >= 2) {
            section("Compare Snapshots") {
                Map olderOptions = [:]
                snapshots.eachWithIndex { snap, idx ->
                    olderOptions["${idx}"] = "${snap.timestamp} (${snap.devices?.totalDevices ?: 0} devices)"
                }
                input "diffOlder", "enum", title: "Older snapshot", options: olderOptions, required: false, submitOnChange: true
                input "diffNewer", "enum", title: "Newer snapshot", options: olderOptions, required: false, submitOnChange: true

                if (diffOlder != null && diffNewer != null && diffOlder != diffNewer) {
                    input "btnDiffSnapshots", "button", title: "Compare Selected"
                }
            }

            if (state.lastSnapshotDiff) {
                section("Snapshot Comparison Results") {
                    paragraph state.lastSnapshotDiff
                }
            }
        }
    }
}

Map settingsPage() {
    dynamicPage(name: "settingsPage", title: "Settings") {
        section("Automatic Snapshots") {
            input "autoSnapshot", "bool", title: "Enable automatic snapshots", defaultValue: false, submitOnChange: true
            if (autoSnapshot) {
                input "snapshotInterval", "enum", title: "Snapshot interval",
                    options: ["1": "1 hour", "6": "6 hours", "12": "12 hours", "24": "24 hours"],
                    defaultValue: "24", required: true
            }
            input "maxSnapshots", "number", title: "Maximum snapshots to retain", defaultValue: 10, range: "1..50", required: true
        }

        section("Automatic Performance Checkpoints") {
            input "autoCheckpoint", "bool", title: "Enable automatic checkpoints", defaultValue: false, submitOnChange: true
            if (autoCheckpoint) {
                input "checkpointInterval", "enum", title: "Checkpoint interval",
                    options: ["5": "5 minutes", "15": "15 minutes", "30": "30 minutes",
                             "60": "1 hour", "360": "6 hours", "720": "12 hours", "1440": "24 hours"],
                    defaultValue: "60", required: true
            }
            input "maxCheckpoints", "number", title: "Maximum checkpoints to keep", defaultValue: 10, range: "1..50", required: true
        }

        section("Device Monitoring") {
            input "inactivityDays", "number", title: "Device inactivity threshold (days)", defaultValue: 7, range: "1..90", required: true
            input "lowBatteryThreshold", "number", title: "Low battery threshold (%)", defaultValue: 20, range: "1..50", required: true
        }

        section("Logging") {
            input "debugLogging", "bool", title: "Enable debug logging", defaultValue: false
        }

        section("Installation") {
            label title: "Assign a name", required: false
        }
    }
}

// ===== BUTTON HANDLER =====

void appButtonHandler(String btn) {
    switch (btn) {
        case "btnDashSnapshot":
        case "btnCreateSnapshot":
            createSnapshot()
            break
        case "btnDashCheckpoint":
        case "btnCreateCheckpoint":
            createCheckpoint()
            break
        case "btnClearCheckpoints":
            clearAllCheckpoints()
            break
        case "btnClearSnapshots":
            clearAllSnapshots()
            break
        case "btnFullReport":
            generateFullReport()
            break
        case "btnCompare":
            executePerformanceComparison()
            break
        case "btnDiffSnapshots":
            executeSnapshotDiff()
            break
        default:
            if (btn.startsWith("btnDeleteCheckpoint_")) {
                int idx = btn.replace("btnDeleteCheckpoint_", "").toInteger()
                deleteCheckpoint(idx)
            } else if (btn.startsWith("btnDeleteSnapshot_")) {
                int idx = btn.replace("btnDeleteSnapshot_", "").toInteger()
                deleteSnapshot(idx)
            } else {
                log.warn "Unknown button: ${btn}"
            }
    }
}

// ===== DATA COLLECTION =====

Map fetchEndpoint(String url, String name, int timeout = 30) {
    try {
        Map params = [
            uri: url,
            contentType: "application/json",
            timeout: timeout
        ]
        Map result = null
        httpGet(params) { resp ->
            if (resp.success && resp.data) {
                result = resp.data
            }
        }
        return result ?: [:]
    } catch (Exception e) {
        log.error "Error fetching ${name}: ${getObjectClassName(e)}: ${e.message}"
        return [error: true, message: e.message]
    }
}

Map fetchSystemResources() {
    try {
        Map params = [
            uri: FREE_MEMORY_URL,
            contentType: "text/plain",
            timeout: 15
        ]
        Map resourceData = null
        httpGet(params) { resp ->
            if (resp.success) {
                String[] lines = resp.data.text.split('\n')
                if (lines.size() > 1) {
                    String[] values = lines[1].split(',')
                    if (values.size() >= 6) {
                        resourceData = [
                            timestamp: values[0].trim(),
                            freeOSMemory: values[1].trim().toInteger(),
                            cpuAvg5min: values[2].trim().toFloat(),
                            totalJavaMemory: values[3].trim().toInteger(),
                            freeJavaMemory: values[4].trim().toInteger(),
                            directJavaMemory: values[5].trim().toInteger()
                        ]
                    }
                }
            }
        }
        return resourceData
    } catch (Exception e) {
        log.error "Error fetching system resources: ${e.message}"
        return null
    }
}

Map fetchStateCompression() {
    try {
        Map params = [
            uri: STATE_COMPRESSION_URL,
            contentType: "text/plain",
            timeout: 10
        ]
        Map result = null
        httpGet(params) { resp ->
            if (resp.success) {
                String text = resp.data?.text?.trim() ?: resp.data?.toString()?.trim() ?: ""
                result = [enabled: text.toLowerCase() == "enabled", status: text]
            }
        }
        return result ?: [enabled: false, status: "unknown"]
    } catch (Exception e) {
        log.error "Error fetching state compression: ${e.message}"
        return [enabled: false, status: "unavailable"]
    }
}

Map fetchCurrentStats() {
    try {
        Map params = [
            uri: RUNTIME_STATS_URL,
            contentType: "application/json",
            timeout: 30
        ]
        Map stats = null
        httpGet(params) { resp ->
            if (resp.success) {
                stats = resp.data
            }
        }
        return stats
    } catch (Exception e) {
        log.error "Error fetching runtime stats: ${e.message}"
        return null
    }
}

// ===== ANALYSIS MODULES =====

Map analyzeDevices() {
    Map response = fetchEndpoint(DEVICES_LIST_URL, "devices list")

    if (!response || response.error || !response.devices) {
        if (debugLogging) log.warn "Failed to fetch devices list"
        return getEmptyDeviceStats()
    }

    List devicesList = response.devices
    Map stats = [
        totalDevices: 0,
        activeDevices: 0,
        inactiveDevices: 0,
        disabledDevices: 0,
        parentDevices: 0,
        childDevices: 0,
        linkedDevices: 0,
        batteryDevices: 0,
        lowBatteryDevices: [],
        inactiveDevicesList: [],
        allDevices: [],
        byType: [:],
        byProtocol: [(PROTOCOL_ZIGBEE): 0, (PROTOCOL_ZWAVE): 0, (PROTOCOL_MATTER): 0,
                     (PROTOCOL_LAN): 0, (PROTOCOL_VIRTUAL): 0, (PROTOCOL_MAKER): 0,
                     (PROTOCOL_CLOUD): 0, (PROTOCOL_HUBMESH): 0, (PROTOCOL_OTHER): 0],
        byStatus: [active: 0, inactive: 0, disabled: 0],
        byParentApp: [:]
    ]

    long inactivityThresholdMs = now() - ((settings.inactivityDays ?: 7) * ONE_DAY_MS)
    List devicesNeedingFullData = []

    // First pass: analyze basic data
    devicesList.each { deviceEntry ->
        try {
            Map device = deviceEntry.data
            if (!device || !(device instanceof Map)) return

            stats.totalDevices++

            // Activity status
            Long lastActivity = null
            try {
                if (device.lastActivity && !(device.lastActivity instanceof Boolean)) {
                    lastActivity = parseDate(device.lastActivity)
                }
            } catch (Exception e) {
                // Ignore parse errors
            }

            if (device.disabled) {
                stats.disabledDevices++
                stats.byStatus.disabled++
                stats.inactiveDevices++
            } else if (lastActivity && lastActivity > inactivityThresholdMs) {
                stats.activeDevices++
                stats.byStatus.active++
            } else {
                stats.inactiveDevices++
                stats.byStatus.inactive++
                stats.inactiveDevicesList << [
                    name: device.name ?: "Unknown",
                    lastActivity: lastActivity ? new Date(lastActivity).format("yyyy-MM-dd HH:mm:ss") : "Never"
                ]
            }

            // Parent/child tracking
            if (deviceEntry.parent == true) stats.parentDevices++
            if (deviceEntry.child == true) stats.childDevices++
            if (device.linked == true) stats.linkedDevices++

            // Device type
            String typeName = safeToString(device.type, "Unknown")
            stats.byType[typeName] = (stats.byType[typeName] ?: 0) + 1

            // Protocol detection
            String protocol = PROTOCOL_OTHER
            if (device.linked == true) {
                protocol = PROTOCOL_HUBMESH
            } else {
                protocol = determineProtocolQuick(device)
                if (protocol == PROTOCOL_OTHER && device.id) {
                    devicesNeedingFullData << device.id
                }
            }
            stats.byProtocol[protocol] = (stats.byProtocol[protocol] ?: 0) + 1

            // Battery tracking
            Integer batteryLevel = null
            if (device.battery != null) {
                stats.batteryDevices++
                batteryLevel = device.battery instanceof Number ? device.battery.intValue() : 100
                if (batteryLevel <= (settings.lowBatteryThreshold ?: 20)) {
                    stats.lowBatteryDevices << [name: device.name ?: "Unknown", battery: batteryLevel]
                }
            }

            // Add to full device list
            stats.allDevices << [
                id: device.id,
                name: device.name ?: "Unknown",
                label: device.label ?: device.name ?: "Unknown",
                type: typeName,
                protocol: protocol,
                status: device.disabled ? "Disabled" : (lastActivity && lastActivity > inactivityThresholdMs ? "Active" : "Inactive"),
                lastActivity: lastActivity ? new Date(lastActivity).format("yyyy-MM-dd HH:mm") : "Never",
                battery: batteryLevel,
                parent: deviceEntry.parent ?: false,
                child: deviceEntry.child ?: false,
                linked: device.linked ?: false,
                room: device.room ?: "",
                parentApp: ""
            ]
        } catch (Exception e) {
            log.warn "Error processing device ${deviceEntry.key}: ${e.message}"
        }
    }

    // Second pass: fetch full data for protocol detection and parent app tracking
    if (devicesNeedingFullData.size() > 0) {
        if (debugLogging) log.debug "Fetching full data for ${devicesNeedingFullData.size()} devices"
        devicesNeedingFullData.each { deviceId ->
            try {
                Map fullDevice = fetchEndpoint(DEVICE_FULL_JSON_URL + deviceId, "device ${deviceId}", 10)
                if (fullDevice && !fullDevice.error) {
                    String newProtocol = determineProtocolFromFullData(fullDevice)
                    if (newProtocol != PROTOCOL_OTHER) {
                        stats.byProtocol[PROTOCOL_OTHER]--
                        stats.byProtocol[newProtocol] = (stats.byProtocol[newProtocol] ?: 0) + 1

                        Map deviceRecord = stats.allDevices.find { it.id == deviceId }
                        if (deviceRecord) deviceRecord.protocol = newProtocol
                    }

                    if (fullDevice.device && fullDevice.device.parentAppId) {
                        String parentAppName = fullDevice.parentApp?.label ?: fullDevice.parentApp?.name ?: "Unknown App"
                        stats.byParentApp[parentAppName] = (stats.byParentApp[parentAppName] ?: 0) + 1

                        Map deviceRecord = stats.allDevices.find { it.id == deviceId }
                        if (deviceRecord) deviceRecord.parentApp = parentAppName
                    }
                }
            } catch (Exception e) {
                log.warn "Error fetching full data for device ${deviceId}: ${e.message}"
            }
        }
    }

    // Third pass: system devices parent app tracking
    List systemDevicesToCheck = devicesList.findAll { deviceEntry ->
        Map device = deviceEntry.data
        device.source == "System" && !device.linked && deviceEntry.parent == false && deviceEntry.child == false
    }.take(50)

    systemDevicesToCheck.each { deviceEntry ->
        try {
            def deviceId = deviceEntry.data.id
            Map fullDevice = fetchEndpoint(DEVICE_FULL_JSON_URL + deviceId, "device ${deviceId}", 10)
            if (fullDevice && !fullDevice.error && fullDevice.device && fullDevice.device.parentAppId) {
                String parentAppName = fullDevice.parentApp?.label ?: fullDevice.parentApp?.name ?: "Unknown App"
                stats.byParentApp[parentAppName] = (stats.byParentApp[parentAppName] ?: 0) + 1

                Map deviceRecord = stats.allDevices.find { it.id == deviceId }
                if (deviceRecord) deviceRecord.parentApp = parentAppName
            }
        } catch (Exception e) {
            log.warn "Error fetching parent app for device ${deviceEntry.data.id}: ${e.message}"
        }
    }

    stats.inactiveDevicesList = stats.inactiveDevicesList.sort { a, b ->
        (a.lastActivity ?: "0") <=> (b.lastActivity ?: "0")
    }

    return stats
}

Map analyzeApps() {
    Map response = fetchEndpoint(APPS_LIST_URL, "apps list")

    if (!response || response.error || !response.apps) {
        return getEmptyAppStats()
    }

    List appsList = response.apps
    Map stats = [
        totalApps: 0,
        userApps: 0,
        builtInApps: 0,
        parentApps: 0,
        childApps: 0,
        builtInInstances: [:],
        userAppsList: [],
        byNamespace: [:],
        parentChildHierarchy: [],
        runtimeTotalApps: 0
    ]

    // Recursive closure to count and catalog all apps at any nesting depth
    Closure processAppList
    processAppList = { List entries, boolean isChildLevel, List parentHierarchyList ->
        entries.each { appEntry ->
            try {
                Map app = appEntry.data
                if (!app || !(app instanceof Map)) return

                stats.totalApps++

                boolean isUserApp = app.user ?: false
                String appType = app.type ?: "Unknown App"
                String appLabel = app.name ?: appType
                def appId = appEntry.key ?: app.id
                List children = appEntry.children ?: []

                if (isChildLevel) {
                    stats.childApps++
                }
                if (children.size() > 0) {
                    stats.parentApps++
                }

                if (isUserApp) {
                    stats.userApps++
                    stats.userAppsList << [name: appType, label: appLabel, id: appId]
                } else {
                    stats.builtInApps++
                    stats.builtInInstances[appType] = (stats.builtInInstances[appType] ?: 0) + 1
                }

                stats.byNamespace[appType] = (stats.byNamespace[appType] ?: 0) + 1

                if (children.size() > 0) {
                    Map parentInfo = [
                        id: app.id,
                        type: appType,
                        label: appLabel,
                        childCount: 0,
                        children: []
                    ]

                    // Recursively process children
                    processAppList(children, true, parentInfo.children)
                    parentInfo.childCount = parentInfo.children.size()

                    parentHierarchyList << parentInfo
                } else if (isChildLevel) {
                    // Leaf child — add to parent's hierarchy children list
                    parentHierarchyList << [
                        id: app.id,
                        type: appType,
                        name: appLabel,
                        disabled: app.disabled ?: false
                    ]
                }
            } catch (Exception e) {
                log.warn "Error processing app ${appEntry.key}: ${e.message}"
            }
        }
    }

    processAppList(appsList, false, stats.parentChildHierarchy)

    // Supplement with runtime stats total for apps not exposed by appsList
    try {
        Map runtimeResponse = fetchEndpoint(RUNTIME_STATS_URL, "runtime stats")
        if (runtimeResponse && !runtimeResponse.error) {
            List appStats = runtimeResponse.appStats ?: []
            stats.runtimeTotalApps = appStats.size()
        }
    } catch (Exception e) {
        log.debug "Could not fetch runtime stats for app count: ${e.message}"
    }

    stats.userAppsList = stats.userAppsList.sort { it.name }
    stats.parentChildHierarchy = stats.parentChildHierarchy.sort { it.type }

    return stats
}

Map analyzeNetwork() {
    return [
        network: fetchEndpoint(NETWORK_CONFIG_URL, "network configuration", 15),
        zwave: fetchEndpoint(ZWAVE_DETAILS_URL, "Z-Wave details", 20),
        zigbee: fetchEndpoint(ZIGBEE_DETAILS_URL, "Zigbee details", 20),
        matter: fetchEndpoint(MATTER_DETAILS_URL, "Matter details", 15),
        hubMesh: fetchEndpoint(HUB_MESH_URL, "Hub Mesh", 15)
    ]
}

Map analyzeSystemHealth() {
    Map memory = fetchSystemResources()
    Map runtime = fetchCurrentStats()
    Map stateCompression = fetchStateCompression()

    Map health = [
        memory: memory,
        runtime: runtime,
        stateCompression: stateCompression,
        alerts: []
    ]

    if (memory && memory.freeOSMemory < 102400) {
        health.alerts << "Low OS memory: ${formatMemory(memory.freeOSMemory)}"
    }
    if (memory && memory.cpuAvg5min > 80) {
        health.alerts << "High CPU usage: ${String.format('%.1f', memory.cpuAvg5min as float)}%"
    }

    return health
}

int calculateHealthScore(Map systemHealth) {
    int score = 100

    // Memory health (20 points)
    if (systemHealth.memory) {
        int freeOS = (systemHealth.memory.freeOSMemory ?: 0) as int
        if (freeOS < 51200) score -= 20
        else if (freeOS < 102400) score -= 10
        else if (freeOS < 204800) score -= 5
    }

    // CPU health (20 points)
    if (systemHealth.memory) {
        float cpu = (systemHealth.memory.cpuAvg5min ?: 0) as float
        if (cpu > 90) score -= 20
        else if (cpu > 70) score -= 10
        else if (cpu > 50) score -= 5
    }

    // Alert count (20 points)
    if (systemHealth.alerts) {
        score -= Math.min(20, ((List) systemHealth.alerts).size() * 10)
    }

    return Math.max(0, score)
}

// ===== PROTOCOL DETECTION =====

String determineProtocolQuick(Map device) {
    if (device.protocol) {
        String protocol = safeToString(device.protocol, "").toLowerCase()
        if (protocol == "zigbee") return PROTOCOL_ZIGBEE
        if (protocol == "zwave") return PROTOCOL_ZWAVE
        if (protocol == "matter") return PROTOCOL_MATTER
        if (protocol == "lan") return PROTOCOL_LAN
    }

    String typeName = safeToString(device.type, "").toLowerCase()

    if (typeName.contains("virtual")) return PROTOCOL_VIRTUAL
    if (typeName.contains("maker") || typeName.contains("webhook")) return PROTOCOL_MAKER
    if (typeName.contains("cloud") || typeName.contains("google") ||
        typeName.contains("alexa") || typeName.contains("homekit")) return PROTOCOL_CLOUD

    if (typeName.contains("zigbee") || typeName.contains("aqara") || typeName.contains("ikea") ||
        typeName.contains("sengled") || typeName.contains("hue") || typeName.contains("tradfri") ||
        typeName.contains("xiaomi") || typeName.contains("tuya zigbee")) {
        return PROTOCOL_ZIGBEE
    }

    if (typeName.contains("z-wave") || typeName.contains("zwave") || typeName.contains("zooz") ||
        typeName.contains("inovelli") || typeName.contains("ge zwave") || typeName.contains("aeotec")) {
        return PROTOCOL_ZWAVE
    }

    if (typeName.contains("matter")) return PROTOCOL_MATTER

    if (typeName.contains("lan") || typeName.contains("http") || typeName.contains("wifi") ||
        typeName.contains("ip") || typeName.contains("sonos") || typeName.contains("chromecast") ||
        typeName.contains("bond") || typeName.contains("lutron") || typeName.contains("ecobee") ||
        typeName.contains("kasa") || typeName.contains("lifx") || typeName.contains("wiz") ||
        typeName.contains("yeelight") || typeName.contains("rachio") || typeName.contains("govee") ||
        typeName.contains("shelly")) {
        return PROTOCOL_LAN
    }

    return PROTOCOL_OTHER
}

String determineProtocolFromFullData(Map fullDevice) {
    Map device = fullDevice.device
    if (!device) return PROTOCOL_OTHER

    if (device.zigbee == true) return PROTOCOL_ZIGBEE
    if (device.ZWave == true) return PROTOCOL_ZWAVE
    if (device.matter == true) return PROTOCOL_MATTER
    if (device.virtual == true) return PROTOCOL_VIRTUAL

    String dni = safeToString(device.deviceNetworkId, "").toUpperCase()
    if (dni.matches(/^[0-9A-F]{4}$/)) return PROTOCOL_ZIGBEE
    if (dni.matches(/^[0-9A-F]{1,2}$/)) return PROTOCOL_ZWAVE
    if (dni.contains(":") || dni.contains(".")) return PROTOCOL_LAN

    return PROTOCOL_OTHER
}

// ===== PERFORMANCE CHECKPOINT SYSTEM =====

void createCheckpoint() {
    log.info "Creating performance checkpoint..."

    Map stats = fetchCurrentStats()
    if (!stats) {
        log.error "Failed to fetch current stats"
        return
    }

    Map resources = fetchSystemResources()

    Map checkpoint = [
        timestamp: new Date().format("yyyy-MM-dd HH:mm:ss"),
        timestampMs: now(),
        stats: stats,
        resources: resources
    ]

    List checkpoints = loadCheckpoints()
    checkpoints.add(0, checkpoint)

    int maxCp = (settings.maxCheckpoints ?: 10) as int
    if (checkpoints.size() > maxCp) {
        checkpoints = checkpoints.take(maxCp)
    }

    saveCheckpoints(checkpoints)
    log.info "Checkpoint created successfully"
}

void executePerformanceComparison() {
    if (compareBaseline == null || compareCheckpoint == null) {
        log.warn "compareBaseline or compareCheckpoint is null"
        return
    }

    if (compareCheckpoint == "now") {
        performComparisonWithNow(compareBaseline as String)
    } else if (compareBaseline == "startup") {
        performComparisonSinceStartup((compareCheckpoint as String).toInteger())
    } else {
        performComparison((compareBaseline as String).toInteger(), (compareCheckpoint as String).toInteger())
    }
}

void performComparisonWithNow(String baseline) {
    Map currentStats = fetchCurrentStats()
    if (!currentStats) {
        log.error "Failed to fetch current stats"
        return
    }

    Map currentResources = fetchSystemResources()
    currentStats.resources = currentResources

    if (baseline == "startup") {
        Map zeroBaseline = buildZeroBaseline(currentStats, currentResources)
        state.lastPerformanceComparison = generateComparison(zeroBaseline, currentStats,
            "Startup (0:00:00)", "Now (${new Date().format('yyyy-MM-dd HH:mm:ss')})")
    } else {
        List checkpoints = loadCheckpoints()
        int baselineIdx = baseline.toInteger()
        if (baselineIdx >= checkpoints.size()) {
            log.error "Invalid baseline index"
            return
        }

        Map baselineCp = checkpoints[baselineIdx]
        Map baselineStats = baselineCp.stats
        baselineStats.resources = baselineCp.resources

        state.lastPerformanceComparison = generateComparison(baselineStats, currentStats,
            baselineCp.timestamp, "Now (${new Date().format('yyyy-MM-dd HH:mm:ss')})")
    }
}

void performComparison(int baselineIdx, int checkpointIdx) {
    List checkpoints = loadCheckpoints()

    if (baselineIdx >= checkpoints.size() || checkpointIdx >= checkpoints.size()) {
        log.error "Invalid checkpoint indices"
        return
    }

    Map baselineCp = checkpoints[baselineIdx]
    Map checkpointCp = checkpoints[checkpointIdx]

    Map baselineStats = baselineCp.stats
    baselineStats.resources = baselineCp.resources
    Map checkpointStats = checkpointCp.stats
    checkpointStats.resources = checkpointCp.resources

    state.lastPerformanceComparison = generateComparison(baselineStats, checkpointStats,
        baselineCp.timestamp, checkpointCp.timestamp)
}

void performComparisonSinceStartup(int checkpointIdx) {
    List checkpoints = loadCheckpoints()

    if (checkpointIdx >= checkpoints.size()) {
        log.error "Invalid checkpoint index"
        return
    }

    Map checkpointCp = checkpoints[checkpointIdx]
    Map zeroBaseline = buildZeroBaseline(checkpointCp.stats, checkpointCp.resources)

    state.lastPerformanceComparison = generateComparison(zeroBaseline, checkpointCp.stats,
        "Startup (0:00:00)", checkpointCp.timestamp)
}

Map buildZeroBaseline(Map stats, Map resources) {
    return [
        uptime: "0h 0m 0s",
        devicesUptime: "0h 0m 0s",
        appsUptime: "0h 0m 0s",
        totalDevicesRuntime: "0ms",
        totalAppsRuntime: "0ms",
        devicePct: "0.000%",
        appPct: "0.000%",
        resources: resources ? [
            timestamp: "0:00:00",
            freeOSMemory: 0,
            cpuAvg5min: 0.0,
            totalJavaMemory: 0,
            freeJavaMemory: 0,
            directJavaMemory: 0
        ] : null,
        deviceStats: (stats.deviceStats ?: []).collect { dev ->
            [id: dev.id, name: dev.name, total: 0, pct: 0, count: 0, average: 0,
             stateSize: 0, hubActionCount: 0, cloudCallCount: 0]
        },
        appStats: (stats.appStats ?: []).collect { app ->
            [id: app.id, name: app.name, total: 0, pct: 0, count: 0, average: 0,
             stateSize: 0, hubActionCount: 0, cloudCallCount: 0]
        }
    ]
}

String generateComparison(Map baselineStats, Map checkpointStats, String baselineLabel, String checkpointLabel) {
    StringBuilder sb = new StringBuilder()
    sb.append("<b>Comparison: Activity Since Baseline</b><br>")
    sb.append("<b>Baseline:</b> ${baselineLabel}<br>")
    sb.append("<b>Checkpoint:</b> ${checkpointLabel}<br><br>")

    // Time comparison
    int baselineUptime = parseUptime(baselineStats.uptime as String)
    int checkpointUptime = parseUptime(checkpointStats.uptime as String)
    int uptimeDiff = checkpointUptime - baselineUptime
    sb.append("<b>Time Since Baseline:</b> ${formatDuration(uptimeDiff)}<br><br>")

    // Runtime comparison
    int baselineDevTime = parseUptime(baselineStats.totalDevicesRuntime as String)
    int checkpointDevTime = parseUptime(checkpointStats.totalDevicesRuntime as String)
    int baselineAppTime = parseUptime(baselineStats.totalAppsRuntime as String)
    int checkpointAppTime = parseUptime(checkpointStats.totalAppsRuntime as String)

    int devTimeDiff = checkpointDevTime - baselineDevTime
    int appTimeDiff = checkpointAppTime - baselineAppTime

    // System resources
    Map baselineResources = baselineStats.resources
    Map checkpointResources = checkpointStats.resources

    if (baselineResources && checkpointResources) {
        sb.append("<b>System Resources:</b><br>")

        int freeOSDelta = (checkpointResources.freeOSMemory ?: 0) - (baselineResources.freeOSMemory ?: 0)
        String freeOSSign = freeOSDelta > 0 ? "+" : ""
        String freeOSColor = freeOSDelta < 0 ? "#d32f2f" : "#388e3c"
        sb.append("&nbsp;&nbsp;Free OS Memory: ${formatMemory(baselineResources.freeOSMemory ?: 0)} -> ${formatMemory(checkpointResources.freeOSMemory ?: 0)} (<span style='color: ${freeOSColor};'>${freeOSSign}${formatMemory(freeOSDelta)}</span>)<br>")

        float cpuDelta = ((checkpointResources.cpuAvg5min ?: 0) as float) - ((baselineResources.cpuAvg5min ?: 0) as float)
        String cpuSign = cpuDelta > 0 ? "+" : ""
        String cpuColor = cpuDelta > 0 ? "#d32f2f" : "#388e3c"
        sb.append("&nbsp;&nbsp;CPU Average (5m): ${String.format('%.2f', (baselineResources.cpuAvg5min ?: 0) as float)}% -> ${String.format('%.2f', (checkpointResources.cpuAvg5min ?: 0) as float)}% (<span style='color: ${cpuColor};'>${cpuSign}${String.format('%.2f', cpuDelta)}%</span>)<br>")

        int freeJavaDelta = (checkpointResources.freeJavaMemory ?: 0) - (baselineResources.freeJavaMemory ?: 0)
        String freeJavaSign = freeJavaDelta > 0 ? "+" : ""
        String freeJavaColor = freeJavaDelta < 0 ? "#d32f2f" : "#388e3c"
        sb.append("&nbsp;&nbsp;Free Java Memory: ${formatMemory(baselineResources.freeJavaMemory ?: 0)} -> ${formatMemory(checkpointResources.freeJavaMemory ?: 0)} (<span style='color: ${freeJavaColor};'>${freeJavaSign}${formatMemory(freeJavaDelta)}</span>)<br>")

        sb.append("<br>")
    }

    // Device activity
    sb.append("<b>Device Activity Since Baseline:</b><br>")
    sb.append("&nbsp;&nbsp;Total Runtime: ${formatDuration(devTimeDiff)}<br>")
    sb.append("<i>Click column headers to sort. % Busy is calculated for the period between checkpoints.</i><br><br>")
    sb.append(generateComparisonTable(baselineStats.deviceStats as List, checkpointStats.deviceStats as List, "device", (devTimeDiff * 1000L)))
    sb.append("<br><br>")

    // App activity
    sb.append("<b>App Activity Since Baseline:</b><br>")
    sb.append("&nbsp;&nbsp;Total Runtime: ${formatDuration(appTimeDiff)}<br>")
    sb.append("<i>Click column headers to sort. % Busy is calculated for the period between checkpoints.</i><br><br>")
    sb.append(generateComparisonTable(baselineStats.appStats as List, checkpointStats.appStats as List, "app", (appTimeDiff * 1000L)))

    return sb.toString()
}

String generateComparisonTable(List baselineItems, List checkpointItems, String type, long overallDeltaMs) {
    if (!baselineItems || baselineItems.size() == 0) {
        return "No ${type} data available"
    }

    Map checkpointItemMap = checkpointItems.collectEntries { [(it.id): it] }

    List comparisonData = []
    baselineItems.each { baselineItem ->
        Map checkpointItem = checkpointItemMap[baselineItem.id]
        if (checkpointItem) {
            long totalMs = ((checkpointItem.total ?: 0) as long) - ((baselineItem.total ?: 0) as long)
            long count = ((checkpointItem.count ?: 0) as long) - ((baselineItem.count ?: 0) as long)
            float avgMs = count > 0 ? (totalMs / count) as float : 0
            int stateSize = (checkpointItem.stateSize ?: 0) as int
            long hubActions = ((checkpointItem.hubActionCount ?: 0) as long) - ((baselineItem.hubActionCount ?: 0) as long)
            long cloudCalls = ((checkpointItem.cloudCallCount ?: 0) as long) - ((baselineItem.cloudCallCount ?: 0) as long)

            float periodPctBusy = overallDeltaMs > 0 ? ((totalMs / (float) overallDeltaMs) * 100) : 0

            if (totalMs != 0 || count != 0 || stateSize != 0 || hubActions != 0 || cloudCalls != 0) {
                comparisonData << [
                    name: baselineItem.name, id: baselineItem.id,
                    totalMs: totalMs, periodPctBusy: periodPctBusy,
                    count: count, avgMs: avgMs, stateSize: stateSize,
                    hubActions: hubActions, cloudCalls: cloudCalls
                ]
            }
        }
    }

    if (comparisonData.size() == 0) {
        return "No changes detected for ${type}s"
    }

    String tableId = "${type}CompTable_${now()}"
    List columns = [
        [label: "Name", field: "name", type: "string"],
        [label: "Total (ms)", field: "totalMs", type: "number"],
        [label: "% Busy", field: "periodPctBusy", type: "number"],
        [label: "Count", field: "count", type: "number"],
        [label: "Avg (ms)", field: "avgMs", type: "number"],
        [label: "State Size", field: "stateSize", type: "number"],
        [label: "Hub Actions", field: "hubActions", type: "number"],
        [label: "Cloud Calls", field: "cloudCalls", type: "number"]
    ]

    List rows = comparisonData.collect { Map item ->
        String linkUrl = type == "device" ? "/device/edit/${item.id}" : "/installedapp/configure/${item.id}"
        Map row = [
            name: "<a href='${linkUrl}' target='_blank' style='color: #1A77C9; text-decoration: none;'>${escapeHtml(item.name as String)}</a>",
            _nameSort: item.name,
            totalMs: item.totalMs,
            periodPctBusy: String.format('%.1f', (item.periodPctBusy as Number).floatValue()) + "%",
            _periodPctBusySort: item.periodPctBusy,
            count: item.count,
            avgMs: String.format('%.1f', (item.avgMs as Number).floatValue()),
            _avgMsSort: item.avgMs,
            stateSize: item.stateSize,
            hubActions: item.hubActions,
            cloudCalls: item.cloudCalls
        ]
        // Color coding
        if (item.totalMs > 10000) row._totalMsColor = "#d32f2f"
        else if (item.totalMs > 1000) row._totalMsColor = "#ff9800"
        if (item.periodPctBusy > 1.0) row._periodPctBusyColor = "#d32f2f"
        else if (item.periodPctBusy > 0.1) row._periodPctBusyColor = "#ff9800"
        if (item.avgMs > 100) row._avgMsColor = "#d32f2f"
        else if (item.avgMs > 10) row._avgMsColor = "#ff9800"
        return row
    }

    return generateSortableTable(tableId, columns, rows)
}

void deleteCheckpoint(int index) {
    List checkpoints = loadCheckpoints()
    if (index >= 0 && index < checkpoints.size()) {
        log.info "Deleting checkpoint at index ${index}"
        checkpoints.remove(index)
        saveCheckpoints(checkpoints)
    }
}

void clearAllCheckpoints() {
    deleteFile(CHECKPOINTS_FILE)
    state.lastPerformanceComparison = null
    log.info "All checkpoints cleared"
}

// ===== SNAPSHOT SYSTEM =====

void createSnapshot() {
    log.info "Creating configuration snapshot..."

    Map snapshot = [
        timestamp: new Date().format("yyyy-MM-dd HH:mm:ss"),
        timestampMs: now(),
        devices: analyzeDevices(),
        apps: analyzeApps(),
        network: analyzeNetwork(),
        systemHealth: analyzeSystemHealth(),
        hubInfo: getHubInfo()
    ]

    // Strip allDevices down to compact form for storage
    if (snapshot.devices.allDevices) {
        snapshot.devices.allDevices = snapshot.devices.allDevices.collect { Map dev ->
            [id: dev.id, name: dev.name, type: dev.type, protocol: dev.protocol, status: dev.status]
        }
    }

    List snapshots = loadSnapshots()
    snapshots.add(0, snapshot)

    int maxSnap = (settings.maxSnapshots ?: 10) as int
    if (snapshots.size() > maxSnap) {
        snapshots = snapshots.take(maxSnap)
    }

    saveSnapshots(snapshots)
    log.info "Snapshot created successfully (${snapshots.size()} total)"
}

void executeSnapshotDiff() {
    if (diffOlder == null || diffNewer == null || diffOlder == diffNewer) {
        log.warn "Invalid snapshot selection for diff"
        return
    }

    List snapshots = loadSnapshots()
    int olderIdx = (diffOlder as String).toInteger()
    int newerIdx = (diffNewer as String).toInteger()

    if (olderIdx >= snapshots.size() || newerIdx >= snapshots.size()) {
        log.error "Invalid snapshot indices"
        return
    }

    // Ensure older is actually older
    Map older = olderIdx > newerIdx ? snapshots[olderIdx] : snapshots[newerIdx]
    Map newer = olderIdx > newerIdx ? snapshots[newerIdx] : snapshots[olderIdx]

    state.lastSnapshotDiff = generateSnapshotDiff(older, newer)
}

String generateSnapshotDiff(Map older, Map newer) {
    StringBuilder sb = new StringBuilder()
    sb.append("<b>Snapshot Comparison</b><br>")
    sb.append("<b>Older:</b> ${older.timestamp}<br>")
    sb.append("<b>Newer:</b> ${newer.timestamp}<br><br>")

    // Device changes
    List olderDevices = older.devices?.allDevices ?: []
    List newerDevices = newer.devices?.allDevices ?: []

    Set olderIds = olderDevices.collect { it.id }.toSet()
    Set newerIds = newerDevices.collect { it.id }.toSet()

    List added = newerDevices.findAll { !olderIds.contains(it.id) }
    List removed = olderDevices.findAll { !newerIds.contains(it.id) }

    // Devices that changed status or protocol
    Map olderDeviceMap = olderDevices.collectEntries { [(it.id): it] }
    Map newerDeviceMap = newerDevices.collectEntries { [(it.id): it] }
    List changed = []
    newerDevices.each { Map dev ->
        Map olderDev = olderDeviceMap[dev.id]
        if (olderDev) {
            List changes = []
            if (dev.status != olderDev.status) changes << "status: ${olderDev.status} -> ${dev.status}"
            if (dev.protocol != olderDev.protocol) changes << "protocol: ${olderDev.protocol} -> ${dev.protocol}"
            if (changes) changed << [name: dev.name, changes: changes.join(", ")]
        }
    }

    sb.append("<b>Device Changes:</b><br>")
    int deviceCountDelta = (newer.devices?.totalDevices ?: 0) - (older.devices?.totalDevices ?: 0)
    String deviceSign = deviceCountDelta > 0 ? "+" : ""
    sb.append("&nbsp;&nbsp;Total: ${older.devices?.totalDevices ?: 0} -> ${newer.devices?.totalDevices ?: 0} (${deviceSign}${deviceCountDelta})<br>")

    if (added) {
        sb.append("<br>&nbsp;&nbsp;<span style='color: #388e3c;'><b>Added (${added.size()}):</b></span><br>")
        added.each { sb.append("&nbsp;&nbsp;&nbsp;&nbsp;+ ${it.name} (${PROTOCOL_DISPLAY[it.protocol] ?: it.protocol})<br>") }
    }
    if (removed) {
        sb.append("<br>&nbsp;&nbsp;<span style='color: #d32f2f;'><b>Removed (${removed.size()}):</b></span><br>")
        removed.each { sb.append("&nbsp;&nbsp;&nbsp;&nbsp;- ${it.name} (${PROTOCOL_DISPLAY[it.protocol] ?: it.protocol})<br>") }
    }
    if (changed) {
        sb.append("<br>&nbsp;&nbsp;<span style='color: #ff9800;'><b>Changed (${changed.size()}):</b></span><br>")
        changed.each { sb.append("&nbsp;&nbsp;&nbsp;&nbsp;~ ${it.name}: ${it.changes}<br>") }
    }
    if (!added && !removed && !changed) {
        sb.append("&nbsp;&nbsp;<i>No device changes detected</i><br>")
    }

    // Protocol deltas
    Map olderProtocol = older.devices?.byProtocol ?: [:]
    Map newerProtocol = newer.devices?.byProtocol ?: [:]
    List protocolChanges = []
    (olderProtocol.keySet() + newerProtocol.keySet()).unique().each { String key ->
        int olderCount = (olderProtocol[key] ?: 0) as int
        int newerCount = (newerProtocol[key] ?: 0) as int
        if (olderCount != newerCount) {
            String sign = (newerCount - olderCount) > 0 ? "+" : ""
            protocolChanges << "${PROTOCOL_DISPLAY[key] ?: key}: ${olderCount} -> ${newerCount} (${sign}${newerCount - olderCount})"
        }
    }
    if (protocolChanges) {
        sb.append("<br><b>Protocol Changes:</b><br>")
        protocolChanges.each { sb.append("&nbsp;&nbsp;${it}<br>") }
    }

    // App changes
    sb.append("<br><b>App Changes:</b><br>")
    int appCountDelta = (newer.apps?.totalApps ?: 0) - (older.apps?.totalApps ?: 0)
    String appSign = appCountDelta > 0 ? "+" : ""
    sb.append("&nbsp;&nbsp;Total: ${older.apps?.totalApps ?: 0} -> ${newer.apps?.totalApps ?: 0} (${appSign}${appCountDelta})<br>")

    // Health score delta
    int olderHealth = calculateHealthScore(older.systemHealth ?: [:])
    int newerHealth = calculateHealthScore(newer.systemHealth ?: [:])
    int healthDelta = newerHealth - olderHealth
    String healthSign = healthDelta > 0 ? "+" : ""
    String healthColor = healthDelta > 0 ? "#388e3c" : (healthDelta < 0 ? "#d32f2f" : "#666")
    sb.append("<br><b>Health Score:</b> ${olderHealth} -> ${newerHealth} (<span style='color: ${healthColor};'>${healthSign}${healthDelta}</span>)<br>")

    // Memory delta
    if (older.systemHealth?.memory && newer.systemHealth?.memory) {
        int olderMem = (older.systemHealth.memory.freeOSMemory ?: 0) as int
        int newerMem = (newer.systemHealth.memory.freeOSMemory ?: 0) as int
        int memDelta = newerMem - olderMem
        String memSign = memDelta > 0 ? "+" : ""
        String memColor = memDelta < 0 ? "#d32f2f" : "#388e3c"
        sb.append("<b>Free OS Memory:</b> ${formatMemory(olderMem)} -> ${formatMemory(newerMem)} (<span style='color: ${memColor};'>${memSign}${formatMemory(memDelta)}</span>)<br>")
    }

    return sb.toString()
}

void deleteSnapshot(int index) {
    List snapshots = loadSnapshots()
    if (index >= 0 && index < snapshots.size()) {
        log.info "Deleting snapshot at index ${index}"
        snapshots.remove(index)
        saveSnapshots(snapshots)
    }
}

void clearAllSnapshots() {
    deleteFile(SNAPSHOTS_FILE)
    state.lastSnapshotDiff = null
    log.info "All snapshots cleared"
}

// ===== REPORT GENERATION =====

void generateFullReport() {
    log.info "Generating full configuration report..."

    String timestamp = new Date().format("yyyy-MM-dd HH:mm:ss")
    Map deviceStats = analyzeDevices()
    Map appStats = analyzeApps()
    Map systemHealth = analyzeSystemHealth()
    Map hubInfo = getHubInfo()

    String html = """<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
    <title>Hub Diagnostics Report - ${timestamp}</title>
    <style>
        body { font-family: Arial, sans-serif; margin: 20px; background-color: #f5f5f5; }
        .container { max-width: 1200px; margin: 0 auto; background-color: white; padding: 30px; box-shadow: 0 2px 4px rgba(0,0,0,0.1); }
        h1 { color: #1A77C9; border-bottom: 3px solid #1A77C9; padding-bottom: 10px; }
        h2 { color: #333; border-bottom: 2px solid #ddd; padding-bottom: 8px; margin-top: 30px; }
        table { width: 100%; border-collapse: collapse; margin: 15px 0; }
        th, td { border: 1px solid #ddd; padding: 10px; text-align: left; }
        th { background-color: #1A77C9; color: white; }
        tr:nth-child(even) { background-color: #f9f9f9; }
        tr:hover { background-color: #e3f2fd; }
        .metric { display: inline-block; margin: 10px 20px 10px 0; }
        .metric-label { font-weight: bold; color: #555; }
        .metric-value { color: #1A77C9; font-size: 1.2em; }
        .warning { background-color: #f8d7da; border-left: 4px solid #d32f2f; padding: 10px; margin: 10px 0; }
        .footer { margin-top: 40px; padding-top: 20px; border-top: 1px solid #ddd; text-align: center; color: #999; font-size: 0.9em; }
    </style>
</head>
<body>
    <div class="container">
        <h1>Hub Diagnostics Report</h1>
        <p><strong>Generated:</strong> ${timestamp}</p>
        <p><strong>Hub:</strong> ${hubInfo.name}</p>
        <p><strong>Firmware:</strong> ${hubInfo.firmware}</p>

        <h2>Executive Summary</h2>
        <div class="metric"><div class="metric-label">Total Devices</div><div class="metric-value">${deviceStats.totalDevices}</div></div>
        <div class="metric"><div class="metric-label">Total Apps</div><div class="metric-value">${appStats.totalApps}</div></div>
        <div class="metric"><div class="metric-label">Health Score</div><div class="metric-value">${calculateHealthScore(systemHealth)}/100</div></div>

        <h2>Device Analysis</h2>
        <table>
            <thead><tr><th>Metric</th><th>Count</th></tr></thead>
            <tbody>
                <tr><td>Total Devices</td><td>${deviceStats.totalDevices}</td></tr>
                <tr><td>Active Devices</td><td>${deviceStats.activeDevices}</td></tr>
                <tr><td>Inactive Devices</td><td>${deviceStats.inactiveDevices}</td></tr>
                <tr><td>Disabled Devices</td><td>${deviceStats.disabledDevices}</td></tr>
                <tr><td>Parent Devices</td><td>${deviceStats.parentDevices}</td></tr>
                <tr><td>Child Devices</td><td>${deviceStats.childDevices}</td></tr>
                <tr><td>Hub Mesh Linked</td><td>${deviceStats.linkedDevices}</td></tr>
                <tr><td>Battery-Powered</td><td>${deviceStats.batteryDevices}</td></tr>
            </tbody>
        </table>

        <h3>All Devices</h3>
        ${generateHtmlReportDeviceTable(deviceStats.allDevices)}

        <h3>Protocol Distribution</h3>
        <table>
            <thead><tr><th>Protocol</th><th>Count</th></tr></thead>
            <tbody>
                ${deviceStats.byProtocol.collect { k, v -> v > 0 ? "<tr><td>${PROTOCOL_DISPLAY[k] ?: k.capitalize()}</td><td>${v}</td></tr>" : "" }.join('\n')}
            </tbody>
        </table>

        <h2>Application Analysis</h2>
        <table>
            <tr><th>Metric</th><th>Count</th></tr>
            <tr><td>Total Apps</td><td>${appStats.totalApps}</td></tr>
            <tr><td>User Apps</td><td>${appStats.userApps}</td></tr>
            <tr><td>Built-in Apps</td><td>${appStats.builtInApps}</td></tr>
        </table>

        <h2>System Health</h2>
        ${systemHealth.alerts && systemHealth.alerts.size() > 0 ?
            '<div class="warning"><strong>Alerts:</strong><ul>' +
            systemHealth.alerts.collect { "<li>${it}</li>" }.join('') +
            '</ul></div>' :
            '<p>No system alerts</p>'}

        <h3>Memory & Resources</h3>
        <table>
            <tr><th>Resource</th><th>Value</th></tr>
            ${systemHealth.memory ? """
            <tr><td>Free OS Memory</td><td>${formatMemory(systemHealth.memory.freeOSMemory ?: 0)}</td></tr>
            <tr><td>CPU Average (5m)</td><td>${String.format('%.2f', (systemHealth.memory.cpuAvg5min ?: 0) as float)}%</td></tr>
            <tr><td>Free Java Memory</td><td>${formatMemory(systemHealth.memory.freeJavaMemory ?: 0)}</td></tr>
            """ : '<tr><td colspan="2">Memory data unavailable</td></tr>'}
        </table>

        <div class="footer">
            <p>Generated by Hub Diagnostics v3.0.0</p>
            <p>Hubitat Elevation - ${hubInfo.name}</p>
        </div>
    </div>
</body>
</html>"""

    String filename = "hub_report_${new Date().format('yyyyMMdd_HHmmss')}.html"
    writeFile(filename, html)
    log.info "Report generated: ${filename}"
}

String generateHtmlReportDeviceTable(List allDevices) {
    if (!allDevices || allDevices.isEmpty()) {
        return "<p>No devices found</p>"
    }

    StringBuilder sb = new StringBuilder()
    sb.append("<table><thead><tr>")
    sb.append("<th>Name</th><th>Type</th><th>Protocol</th><th>Status</th>")
    sb.append("<th>Last Activity</th><th>Battery</th><th>Parent App</th><th>Room</th>")
    sb.append("</tr></thead><tbody>")

    List sorted = allDevices.sort { it.name }
    sorted.each { Map device ->
        String statusColor = device.status == "Disabled" ? "#d32f2f" : (device.status == "Active" ? "#388e3c" : "#ff9800")
        String protocolDisplay = PROTOCOL_DISPLAY[device.protocol] ?: (device.protocol ?: "").toString().capitalize()

        sb.append("<tr>")
        sb.append("<td><strong>${device.name}</strong></td>")
        sb.append("<td style='font-size: 0.9em;'>${device.type}</td>")
        sb.append("<td>${protocolDisplay}</td>")
        sb.append("<td style='color: ${statusColor};'><strong>${device.status}</strong></td>")
        sb.append("<td>${device.lastActivity ?: 'Never'}</td>")
        sb.append("<td>${device.battery != null ? device.battery + '%' : '-'}</td>")
        sb.append("<td>${device.parentApp ?: '-'}</td>")
        sb.append("<td>${device.room ?: '-'}</td>")
        sb.append("</tr>")
    }

    sb.append("</tbody></table>")
    return sb.toString()
}

// ===== SHARED TABLE GENERATION =====

String generateSortableTable(String tableId, List columns, List rows) {
    if (!rows || rows.size() == 0) {
        return "No data available"
    }

    String uniqueId = "${tableId}_${now()}"

    StringBuilder sb = new StringBuilder()

    // Sort script
    sb.append("""<script>
function sortTable_${uniqueId}(colIdx, dataType) {
    var table = document.getElementById('${uniqueId}');
    var tbody = table.getElementsByTagName('tbody')[0];
    var trs = Array.from(tbody.getElementsByTagName('tr'));
    var curCol = table.getAttribute('data-sort-col');
    var curOrd = table.getAttribute('data-sort-order');
    var newOrd = (curCol == colIdx && curOrd == 'desc') ? 'asc' : 'desc';
    trs.sort(function(a, b) {
        var aV = a.cells[colIdx].getAttribute('data-value');
        var bV = b.cells[colIdx].getAttribute('data-value');
        if (dataType == 'number') { aV = parseFloat(aV) || 0; bV = parseFloat(bV) || 0; }
        else { aV = aV.toLowerCase(); bV = bV.toLowerCase(); }
        if (aV < bV) return newOrd == 'asc' ? -1 : 1;
        if (aV > bV) return newOrd == 'asc' ? 1 : -1;
        return 0;
    });
    trs.forEach(function(r, i) { r.style.backgroundColor = i % 2 == 0 ? '#f9f9f9' : '#fff'; tbody.appendChild(r); });
    table.setAttribute('data-sort-col', colIdx);
    table.setAttribute('data-sort-order', newOrd);
    var ths = table.getElementsByTagName('th');
    for (var i = 0; i < ths.length; i++) {
        var arrow = ths[i].getElementsByClassName('sort-arrow')[0];
        if (arrow) arrow.innerHTML = (i == colIdx) ? (newOrd == 'desc' ? ' \\u25BC' : ' \\u25B2') : ' \\u21C5';
    }
}
</script>""")

    sb.append("<div style='overflow-x: auto;'>")
    sb.append("<table id='${uniqueId}' data-sort-col='-1' data-sort-order='desc' style='width:100%; border-collapse: collapse; font-size: 11px;'>")

    // Header
    sb.append('<thead><tr style="background-color: #1A77C9; color: white;">')
    columns.eachWithIndex { Map col, int idx ->
        String align = col.type == "number" ? "right" : "left"
        sb.append("<th onclick='sortTable_${uniqueId}(${idx}, \"${col.type}\")' style='padding: 6px; text-align: ${align}; border: 1px solid #ddd; cursor: pointer;'>${col.label}<span class='sort-arrow'> \u21C5</span></th>")
    }
    sb.append('</tr></thead><tbody>')

    // Rows
    rows.eachWithIndex { Map row, int rowIdx ->
        String rowColor = rowIdx % 2 == 0 ? "#f9f9f9" : "#ffffff"
        sb.append("<tr style='background-color: ${rowColor};'>")

        columns.each { Map col ->
            String field = col.field
            def value = row[field]
            String displayValue = value != null ? value.toString() : ""

            // Check for sort override value
            def sortValue = row["_${field}Sort"] ?: value
            String sortStr = sortValue != null ? sortValue.toString() : ""

            // Check for color override
            String color = row["_${field}Color"] ?: ""
            String colorStyle = color ? " color: ${color};" : ""

            String align = col.type == "number" ? "right" : "left"
            sb.append("<td data-value='${escapeAttr(sortStr)}' style='padding: 6px; text-align: ${align}; border: 1px solid #ddd;${colorStyle}'>${displayValue}</td>")
        }

        sb.append('</tr>')
    }

    sb.append('</tbody></table></div>')
    return sb.toString()
}

// ===== FORMATTING HELPERS =====

String generateQuickSummary() {
    try {
        // Lightweight summary — single API call per section, no per-device fetches
        Map deviceStats = analyzeDevicesQuick()
        Map appStats = analyzeAppsQuick()
        Map hubInfo = getHubInfo()

        // Lightweight health: just memory + CPU
        Map resources = fetchSystemResources()
        String memoryLine = ""
        int healthScore = 100
        if (resources) {
            memoryLine = "\n<b>Resources:</b> ${formatMemory(resources.freeOSMemory ?: 0)} free | CPU: ${String.format('%.1f', (resources.cpuAvg5min ?: 0) as float)}%"
            int freeOS = resources.freeOSMemory ?: 0
            float cpu = (resources.cpuAvg5min ?: 0) as float
            if (freeOS < 51200) healthScore -= 20
            else if (freeOS < 102400) healthScore -= 10
            else if (freeOS < 204800) healthScore -= 5
            if (cpu > 90) healthScore -= 20
            else if (cpu > 70) healthScore -= 10
            else if (cpu > 50) healthScore -= 5
        }

        return """<b>Hub Diagnostics Summary</b>
<b>Hub:</b> ${hubInfo.name} | Firmware: ${hubInfo.firmware} | Hardware: ${hubInfo.hardware}

<b>Devices:</b> ${deviceStats.totalDevices} total
  Active: ${deviceStats.activeDevices} | Inactive: ${deviceStats.inactiveDevices} | Disabled: ${deviceStats.disabledDevices}
  Z-Wave: ${deviceStats.byProtocol[PROTOCOL_ZWAVE]} | Zigbee: ${deviceStats.byProtocol[PROTOCOL_ZIGBEE]} | Matter: ${deviceStats.byProtocol[PROTOCOL_MATTER]}
  Hub Mesh: ${deviceStats.byProtocol[PROTOCOL_HUBMESH]} | Virtual: ${deviceStats.byProtocol[PROTOCOL_VIRTUAL]} | LAN: ${deviceStats.byProtocol[PROTOCOL_LAN]}

<b>Applications:</b> ${appStats.totalApps} total (System: ${appStats.builtInApps} | User: ${appStats.userApps})
${memoryLine}
<b>Health Score:</b> ${healthScore}/100"""
    } catch (Exception e) {
        log.error "Error generating summary: ${getObjectClassName(e)}: ${e.message}"
        return "Error generating summary. Please check logs."
    }
}

Map analyzeDevicesQuick() {
    // Fast single-pass device analysis — one API call, heuristic-only protocol detection
    Map response = fetchEndpoint(DEVICES_LIST_URL, "devices list")

    if (!response || response.error || !response.devices) {
        return getEmptyDeviceStats()
    }

    List devicesList = response.devices
    Map stats = [
        totalDevices: 0, activeDevices: 0, inactiveDevices: 0, disabledDevices: 0,
        byProtocol: [(PROTOCOL_ZIGBEE): 0, (PROTOCOL_ZWAVE): 0, (PROTOCOL_MATTER): 0,
                     (PROTOCOL_LAN): 0, (PROTOCOL_VIRTUAL): 0, (PROTOCOL_MAKER): 0,
                     (PROTOCOL_CLOUD): 0, (PROTOCOL_HUBMESH): 0, (PROTOCOL_OTHER): 0]
    ]

    long inactivityThresholdMs = now() - ((settings.inactivityDays ?: 7) * ONE_DAY_MS)

    devicesList.each { deviceEntry ->
        try {
            Map device = deviceEntry.data
            if (!device || !(device instanceof Map)) return

            stats.totalDevices++

            Long lastActivity = null
            try {
                if (device.lastActivity && !(device.lastActivity instanceof Boolean)) {
                    lastActivity = parseDate(device.lastActivity)
                }
            } catch (Exception e) { /* ignore */ }

            if (device.disabled) {
                stats.disabledDevices++
                stats.inactiveDevices++
            } else if (lastActivity && lastActivity > inactivityThresholdMs) {
                stats.activeDevices++
            } else {
                stats.inactiveDevices++
            }

            String protocol = (device.linked == true) ? PROTOCOL_HUBMESH : determineProtocolQuick(device)
            stats.byProtocol[protocol] = (stats.byProtocol[protocol] ?: 0) + 1
        } catch (Exception e) { /* skip */ }
    }

    return stats
}

Map analyzeAppsQuick() {
    // Fast app count — single API call, counts children recursively
    Map response = fetchEndpoint(APPS_LIST_URL, "apps list")

    if (!response || response.error || !response.apps) {
        return [totalApps: 0, userApps: 0, builtInApps: 0]
    }

    int totalApps = 0
    int userApps = 0
    int builtInApps = 0

    Closure countApps
    countApps = { List appList ->
        appList.each { appEntry ->
            Map app = appEntry.data
            if (!app || !(app instanceof Map)) return
            totalApps++
            if (app.user) userApps++
            else builtInApps++
            List children = appEntry.children ?: []
            if (children) countApps(children)
        }
    }
    countApps(response.apps)

    return [totalApps: totalApps, userApps: userApps, builtInApps: builtInApps]
}

String getCurrentStatsSummary() {
    Map stats = fetchCurrentStats()
    if (!stats) {
        return "Unable to fetch current runtime stats"
    }

    Map resources = fetchSystemResources()

    String resourceInfo = ""
    if (resources) {
        resourceInfo = """<br><b>Free OS Memory:</b> ${formatMemory(resources.freeOSMemory ?: 0)}
<br><b>CPU Average (5m):</b> ${String.format('%.2f', (resources.cpuAvg5min ?: 0) as float)}%
<br><b>Free Java Memory:</b> ${formatMemory(resources.freeJavaMemory ?: 0)}"""
    }

    return """<b>Uptime:</b> ${stats.uptime}<br>
<b>Devices Runtime:</b> ${stats.totalDevicesRuntime} (${stats.devicePct})<br>
<b>Apps Runtime:</b> ${stats.totalAppsRuntime} (${stats.appPct})<br>
<b>Total Devices:</b> ${stats.deviceStats?.size() ?: 0}<br>
<b>Total Apps:</b> ${stats.appStats?.size() ?: 0}<br>
<b>Scheduled Jobs:</b> ${stats.jobs?.size() ?: 0}${resourceInfo}"""
}

String generateCheckpointTable(List checkpoints) {
    StringBuilder sb = new StringBuilder()
    sb.append("<table style='width:100%; border-collapse: collapse; font-size: 12px;'>")
    sb.append('<thead><tr style="background-color: #1A77C9; color: white;">')
    sb.append("<th style='padding: 8px; text-align: left; border: 1px solid #ddd;'>Timestamp</th>")
    sb.append("<th style='padding: 8px; text-align: center; border: 1px solid #ddd;'>Uptime</th>")
    sb.append("<th style='padding: 8px; text-align: center; border: 1px solid #ddd;'>Device Runtime</th>")
    sb.append("<th style='padding: 8px; text-align: center; border: 1px solid #ddd;'>App Runtime</th>")
    sb.append("<th style='padding: 8px; text-align: center; border: 1px solid #ddd; width: 80px;'>Action</th>")
    sb.append('</tr></thead><tbody>')

    checkpoints.eachWithIndex { Map cp, int idx ->
        String rowColor = idx % 2 == 0 ? "#f9f9f9" : "#ffffff"
        sb.append("<tr style='background-color: ${rowColor};'>")
        sb.append("<td style='padding: 8px; border: 1px solid #ddd;'>${cp.timestamp}</td>")
        sb.append("<td style='padding: 8px; text-align: center; border: 1px solid #ddd;'>${cp.stats?.uptime ?: 'N/A'}</td>")
        sb.append("<td style='padding: 8px; text-align: center; border: 1px solid #ddd;'>${cp.stats?.totalDevicesRuntime ?: 'N/A'}</td>")
        sb.append("<td style='padding: 8px; text-align: center; border: 1px solid #ddd;'>${cp.stats?.totalAppsRuntime ?: 'N/A'}</td>")
        sb.append("<td style='padding: 8px; text-align: center; border: 1px solid #ddd;'>")
        sb.append("<input type='button' name='btnDeleteCheckpoint_${idx}' value='Delete' class='mdl-button' style='font-size: 10px;' />")
        sb.append("</td></tr>")
    }

    sb.append('</tbody></table>')
    return sb.toString()
}

String generateSnapshotsTable(List snapshots) {
    if (!snapshots || snapshots.size() == 0) {
        return "No snapshots available"
    }

    StringBuilder sb = new StringBuilder()
    sb.append("<table style='width:100%; border-collapse: collapse; font-size: 12px;'>")
    sb.append('<thead><tr style="background-color: #1A77C9; color: white;">')
    sb.append("<th style='padding: 8px; text-align: left; border: 1px solid #ddd;'>Timestamp</th>")
    sb.append("<th style='padding: 8px; text-align: center; border: 1px solid #ddd;'>Devices</th>")
    sb.append("<th style='padding: 8px; text-align: center; border: 1px solid #ddd;'>Apps</th>")
    sb.append("<th style='padding: 8px; text-align: center; border: 1px solid #ddd;'>Health</th>")
    sb.append("<th style='padding: 8px; text-align: center; border: 1px solid #ddd; width: 80px;'>Action</th>")
    sb.append('</tr></thead><tbody>')

    snapshots.eachWithIndex { Map snap, int idx ->
        String rowColor = idx % 2 == 0 ? "#f9f9f9" : "#ffffff"
        int health = calculateHealthScore(snap.systemHealth ?: [:])
        String healthColor = health >= 80 ? "#388e3c" : (health >= 60 ? "#ff9800" : "#d32f2f")

        sb.append("<tr style='background-color: ${rowColor};'>")
        sb.append("<td style='padding: 8px; border: 1px solid #ddd;'>${snap.timestamp}</td>")
        sb.append("<td style='padding: 8px; text-align: center; border: 1px solid #ddd;'>${snap.devices?.totalDevices ?: 0}</td>")
        sb.append("<td style='padding: 8px; text-align: center; border: 1px solid #ddd;'>${snap.apps?.totalApps ?: 0}</td>")
        sb.append("<td style='padding: 8px; text-align: center; border: 1px solid #ddd; color: ${healthColor};'><b>${health}</b></td>")
        sb.append("<td style='padding: 8px; text-align: center; border: 1px solid #ddd;'>")
        sb.append("<input type='button' name='btnDeleteSnapshot_${idx}' value='Delete' class='mdl-button' style='font-size: 10px;' />")
        sb.append("</td></tr>")
    }

    sb.append('</tbody></table>')
    return sb.toString()
}

String formatMetricsTable(List metrics) {
    StringBuilder sb = new StringBuilder()
    sb.append("<table style='width:100%; border-collapse: collapse; font-size: 12px;'>")
    sb.append("<thead><tr style='background-color: #1A77C9; color: white;'>")
    sb.append("<th style='padding: 8px; text-align: left; border: 1px solid #ddd;'>Metric</th>")
    sb.append("<th style='padding: 8px; text-align: right; border: 1px solid #ddd;'>Value</th>")
    sb.append("</tr></thead><tbody>")

    metrics.eachWithIndex { List metric, int idx ->
        String rowColor = idx % 2 == 0 ? "#f9f9f9" : "#ffffff"
        sb.append("<tr style='background-color: ${rowColor};'>")
        sb.append("<td style='padding: 8px; border: 1px solid #ddd;'><b>${metric[0]}</b></td>")
        sb.append("<td style='padding: 8px; text-align: right; border: 1px solid #ddd;'>${metric[1]}</td>")
        sb.append("</tr>")
    }

    sb.append("</tbody></table>")
    return sb.toString()
}

String formatProtocolTable(Map protocolMap) {
    if (!protocolMap || protocolMap.isEmpty()) {
        return "No protocol data available"
    }

    StringBuilder sb = new StringBuilder()
    sb.append("<table style='width:100%; border-collapse: collapse;'>")
    sb.append("<tr><th style='text-align:left; border-bottom: 1px solid #ddd; padding: 4px;'>Protocol</th>")
    sb.append("<th style='text-align:right; border-bottom: 1px solid #ddd; padding: 4px;'>Count</th></tr>")

    PROTOCOL_DISPLAY.each { String key, String displayName ->
        int count = (protocolMap[key] ?: 0) as int
        if (count > 0) {
            sb.append("<tr><td style='padding: 4px;'>${displayName}</td>")
            sb.append("<td style='text-align:right; padding: 4px;'>${count}</td></tr>")
        }
    }
    sb.append("</table>")
    return sb.toString()
}

String formatSimpleTable(Map dataMap, String header1, String header2) {
    if (!dataMap || dataMap.isEmpty()) {
        return "No data available"
    }

    Map sorted = dataMap.sort { -it.value }
    StringBuilder sb = new StringBuilder()
    sb.append("<table style='width:100%; border-collapse: collapse;'>")
    sb.append("<tr><th style='text-align:left; border-bottom: 1px solid #ddd; padding: 4px;'>${header1}</th>")
    sb.append("<th style='text-align:right; border-bottom: 1px solid #ddd; padding: 4px;'>${header2}</th></tr>")

    sorted.each { key, value ->
        if (value > 0 || dataMap.size() < 20) {
            sb.append("<tr><td style='padding: 4px;'>${key.toString().capitalize()}</td>")
            sb.append("<td style='text-align:right; padding: 4px;'>${value}</td></tr>")
        }
    }
    sb.append("</table>")
    return sb.toString()
}

String formatAppsByTypeTable(Map appStats) {
    StringBuilder sb = new StringBuilder()
    sb.append("<table style='width:100%; border-collapse: collapse; font-size: 12px;'>")
    sb.append("<thead><tr style='background-color: #1A77C9; color: white;'>")
    sb.append("<th style='padding: 8px; text-align: left; border: 1px solid #ddd;'>App Type</th>")
    sb.append("<th style='padding: 8px; text-align: center; border: 1px solid #ddd;'>Instances</th>")
    sb.append("<th style='padding: 8px; text-align: center; border: 1px solid #ddd;'>User/System</th>")
    sb.append("</tr></thead><tbody>")

    Map sorted = ((Map) appStats.byNamespace).sort { -it.value }
    sorted.eachWithIndex { entry, int idx ->
        String rowColor = idx % 2 == 0 ? "#f9f9f9" : "#ffffff"
        String appType = entry.key
        boolean userApp = ((List) appStats.userAppsList).any { it.name == appType }

        sb.append("<tr style='background-color: ${rowColor};'>")
        sb.append("<td style='padding: 8px; border: 1px solid #ddd;'>${appType}</td>")
        sb.append("<td style='padding: 8px; text-align: center; border: 1px solid #ddd;'>${entry.value}</td>")
        sb.append("<td style='padding: 8px; text-align: center; border: 1px solid #ddd;'>${userApp ? 'User' : 'System'}</td>")
        sb.append("</tr>")
    }

    sb.append("</tbody></table>")
    return sb.toString()
}

String formatParentChildHierarchy(List hierarchy) {
    if (!hierarchy || hierarchy.isEmpty()) {
        return "No parent/child app relationships found"
    }

    StringBuilder sb = new StringBuilder()
    sb.append("<table style='width:100%; border-collapse: collapse; font-size: 12px;'>")
    sb.append("<thead><tr style='background-color: #1A77C9; color: white;'>")
    sb.append("<th style='padding: 8px; text-align: left; border: 1px solid #ddd;'>Parent App Type</th>")
    sb.append("<th style='padding: 8px; text-align: center; border: 1px solid #ddd;'>Children</th>")
    sb.append("</tr></thead><tbody>")

    hierarchy.each { Map parent ->
        sb.append("<tr style='background-color: #f9f9f9;'>")
        sb.append("<td style='padding: 8px; border: 1px solid #ddd;'><b>${parent.type}</b><br><small>ID: ${parent.id}</small></td>")
        sb.append("<td style='padding: 8px; text-align: center; border: 1px solid #ddd;'><b>${parent.childCount}</b></td>")
        sb.append("</tr>")

        if (parent.children && parent.children.size() > 0) {
            sb.append("<tr style='background-color: #ffffff;'>")
            sb.append("<td colspan='2' style='padding: 8px 8px 8px 30px; border: 1px solid #ddd; font-size: 11px;'>")
            parent.children.each { Map child ->
                String disabledText = child.disabled ? " <span style='color: #d32f2f;'>(Disabled)</span>" : ""
                sb.append("<b>${child.name}</b>${disabledText}<br>")
                sb.append("&nbsp;&nbsp;<small style='color: #666;'>Type: ${child.type} | ID: ${child.id}</small><br>")
            }
            sb.append("</td></tr>")
        }
    }

    sb.append("</tbody></table>")
    return sb.toString()
}

// ===== UTILITY METHODS =====

Map getHubInfo() {
    Map info = [name: location.name ?: "Unknown", firmware: "Unknown", hardware: "Unknown", ip: "Unknown"]
    if (location.hubs && location.hubs.size() > 0) {
        def hub = location.hubs[0]
        info.firmware = hub.firmwareVersionString ?: "Unknown"
        info.hardware = hub.type ?: "Unknown"
        info.ip = hub.localIP ?: "Unknown"
    }
    // Fetch model from hubData for accurate hardware name (e.g. "C-7", "C-8 Pro")
    Map hubData = fetchEndpoint(HUB_DATA_URL, "hub data", 10)
    if (hubData && !hubData.error && hubData.model) {
        info.hardware = hubData.model
    }
    return info
}

Map getEmptyDeviceStats() {
    return [
        totalDevices: 0, activeDevices: 0, inactiveDevices: 0, disabledDevices: 0,
        parentDevices: 0, childDevices: 0, linkedDevices: 0, batteryDevices: 0,
        lowBatteryDevices: [], inactiveDevicesList: [], allDevices: [],
        byType: [:],
        byProtocol: [(PROTOCOL_ZIGBEE): 0, (PROTOCOL_ZWAVE): 0, (PROTOCOL_MATTER): 0,
                     (PROTOCOL_LAN): 0, (PROTOCOL_VIRTUAL): 0, (PROTOCOL_MAKER): 0,
                     (PROTOCOL_CLOUD): 0, (PROTOCOL_HUBMESH): 0, (PROTOCOL_OTHER): 0],
        byStatus: [active: 0, inactive: 0, disabled: 0],
        byParentApp: [:]
    ]
}

Map getEmptyAppStats() {
    return [
        totalApps: 0, userApps: 0, builtInApps: 0, parentApps: 0, childApps: 0,
        builtInInstances: [:], userAppsList: [], byNamespace: [:], parentChildHierarchy: [],
        runtimeTotalApps: 0
    ]
}

String safeToString(value, String defaultValue = "") {
    if (value == null || value instanceof List) return defaultValue
    return value.toString()
}

Long parseDate(dateStr) {
    if (dateStr == null || dateStr instanceof List) return null
    String dateString = safeToString(dateStr, "")
    if (dateString.isEmpty()) return null

    try {
        return Date.parse("yyyy-MM-dd'T'HH:mm:ss.SSSZ", dateString).time
    } catch (Exception e) {
        try {
            return Date.parse("yyyy-MM-dd'T'HH:mm:ssZ", dateString).time
        } catch (Exception e2) {
            return null
        }
    }
}

int parseUptime(String uptime) {
    if (!uptime) return 0

    // Handle milliseconds format like "0ms" or "123ms"
    if (uptime.endsWith('ms')) {
        return (uptime[0..-3].toInteger() / 1000).toInteger()
    }

    int seconds = 0
    List parts = uptime.tokenize()

    parts.each { String part ->
        if (part.endsWith('h')) {
            seconds += part[0..-2].toInteger() * 3600
        } else if (part.endsWith('m') && !part.endsWith('ms')) {
            seconds += part[0..-2].toInteger() * 60
        } else if (part.endsWith('s') && !part.endsWith('ms')) {
            seconds += part[0..-2].toInteger()
        }
    }

    return seconds
}

String formatDuration(int seconds) {
    int hours = (seconds / 3600).toInteger()
    int minutes = ((seconds % 3600) / 60).toInteger()
    int secs = (seconds % 60).toInteger()

    if (hours > 0) return "${hours}h ${minutes}m ${secs}s"
    if (minutes > 0) return "${minutes}m ${secs}s"
    return "${secs}s"
}

String formatMemory(int kb) {
    if (Math.abs(kb) >= 1024 * 1024) {
        return String.format("%.2f GB", kb / (1024.0 * 1024.0))
    } else if (Math.abs(kb) >= 1024) {
        return String.format("%.2f MB", kb / 1024.0)
    } else {
        return "${kb} KB"
    }
}

String escapeHtml(String text) {
    if (!text) return ""
    return text.replaceAll("&", "&amp;").replaceAll("<", "&lt;").replaceAll(">", "&gt;")
        .replaceAll("'", "&#39;").replaceAll('"', "&quot;")
}

String escapeAttr(String text) {
    if (!text) return ""
    return text.replaceAll("'", "&#39;").replaceAll('"', "&quot;")
        .replaceAll("<", "&lt;").replaceAll(">", "&gt;")
}

// ===== FILE I/O HELPERS =====

List loadCheckpoints() {
    try {
        def data = readFile(CHECKPOINTS_FILE)
        if (data) return data
    } catch (Exception e) {
        if (debugLogging) log.debug "No existing checkpoints: ${e.message}"
    }
    return []
}

void saveCheckpoints(List checkpoints) {
    try {
        String json = groovy.json.JsonOutput.toJson(checkpoints)
        writeFile(CHECKPOINTS_FILE, json)
    } catch (Exception e) {
        log.error "Error saving checkpoints: ${e}"
    }
}

List loadSnapshots() {
    try {
        def data = readFile(SNAPSHOTS_FILE)
        if (data) return data
    } catch (Exception e) {
        if (debugLogging) log.debug "No existing snapshots: ${e.message}"
    }
    return []
}

void saveSnapshots(List snapshots) {
    try {
        String json = groovy.json.JsonOutput.toJson(snapshots)
        writeFile(SNAPSHOTS_FILE, json)
    } catch (Exception e) {
        log.error "Error saving snapshots: ${e}"
    }
}

def readFile(String fileName) {
    try {
        byte[] fileData = downloadHubFile(fileName)
        if (fileData) {
            String jsonString = new String(fileData, "UTF-8")
            return new groovy.json.JsonSlurper().parseText(jsonString)
        }
    } catch (Exception e) {
        if (debugLogging) log.debug "File not found or error reading ${fileName}: ${e.message}"
    }
    return null
}

void writeFile(String fileName, String data) {
    try {
        uploadHubFile(fileName, data.getBytes("UTF-8"))
    } catch (Exception e) {
        log.error "Error writing file ${fileName}: ${e}"
    }
}

void deleteFile(String fileName) {
    try {
        deleteHubFile(fileName)
    } catch (Exception e) {
        log.error "Error deleting file ${fileName}: ${e}"
    }
}

// ===== LIFECYCLE METHODS =====

void installed() {
    log.info "Hub Diagnostics installed"
    initialize()
}

void updated() {
    log.info "Hub Diagnostics updated"
    unsubscribe()
    unschedule()
    initialize()
}

void uninstalled() {
    unschedule()
    unsubscribe()
    log.info "Hub Diagnostics uninstalled"
}

void initialize() {
    log.info "Hub Diagnostics initialized"

    if (settings.autoSnapshot) {
        int interval = (settings.snapshotInterval ?: "24").toInteger()
        schedule("0 0 */${interval} * * ?", "createSnapshot")
        log.info "Automatic snapshots scheduled every ${interval} hour(s)"
    }

    if (settings.autoCheckpoint) {
        int interval = (settings.checkpointInterval ?: "60").toInteger()
        if (interval < 60) {
            schedule("0 */${interval} * * * ?", "createCheckpoint")
        } else {
            int hours = (interval / 60).toInteger()
            schedule("0 0 */${hours} * * ?", "createCheckpoint")
        }
        log.info "Automatic checkpoints scheduled every ${interval} minute(s)"
    }
}

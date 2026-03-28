/**
 * Hub Diagnostics
 *
 * Comprehensive hub diagnostics: inventory, performance tracking, network analysis,
 * snapshot comparison, and exportable reports.
 *
 * Consolidates Hub Diagnostic Analyzer, Hub Performance Analyzer, and
 * Hub Configuration Analyzer into a single unified app.
 *
 * Author: PJ
 * Version: 3.2.0
 */

import groovy.transform.Field
import groovy.transform.CompileStatic

@Field static final String APP_VERSION = "3.2.0"
@Field static final String STORAGE_SCHEMA_VERSION = "3.2.0"

// API Endpoint URLs (localhost access)
@Field static final String DEVICES_LIST_URL = "http://127.0.0.1:8080/hub2/devicesList"
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
@Field static final String DATABASE_SIZE_URL = "http://127.0.0.1:8080/hub/advanced/databaseSize"
@Field static final String INTERNAL_TEMP_URL = "http://127.0.0.1:8080/hub/advanced/internalTempCelsius"
@Field static final String ZIGBEE_CHILD_ROUTE_URL = "http://127.0.0.1:8080/hub/zigbee/getChildAndRouteInfo"
@Field static final String ZWAVE_VERSION_URL = "http://127.0.0.1:8080/hub/zwaveVersion"
@Field static final String EVENT_LIMIT_URL = "http://127.0.0.1:8080/hub/advanced/event/limit"
@Field static final String MAX_EVENT_AGE_URL = "http://127.0.0.1:8080/hub/advanced/maxEventAgeDays"
@Field static final String MAX_STATE_AGE_URL = "http://127.0.0.1:8080/hub/advanced/maxDeviceStateAgeDays"
@Field static final String MEMORY_HISTORY_URL = "http://127.0.0.1:8080/hub/advanced/freeOSMemoryHistory"

// Zigbee channels recommended to avoid WiFi interference
@Field static final List RECOMMENDED_ZIGBEE_CHANNELS = [15, 20, 25]

// Hub alert flag display names
@Field static final Map ALERT_DISPLAY_NAMES = [
    hubLoadElevated: "Hub Load Elevated",
    hubLoadSevere: "Hub Load Severe",
    hubHighLoad: "Hub High Load",
    hubLowMemory: "Hub Low Memory",
    hubZwaveCrashed: "Z-Wave Radio Crashed",
    zwaveMigrateFailed: "Z-Wave Migration Failed",
    hubLargeishDatabase: "Database Growing Large",
    hubLargeDatabase: "Database Large",
    hubHugeDatabase: "Database Very Large",
    spammyDevices: "Spammy Devices Detected",
    zwaveOffline: "Z-Wave Offline",
    zigbeeOffline: "Zigbee Offline",
    cloudDisconnected: "Cloud Disconnected",
    localBackupFailed: "Local Backup Failed",
    cloudBackupFailed: "Cloud Backup Failed",
    weakZigbee: "Weak Zigbee Channel",
    platformUpdateAvailable: "Platform Update Available"
]

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
@Field static final String PERFORMANCE_COMPARISON_FILE = "hub_diagnostics_performance_comparison.json"
@Field static final String SNAPSHOT_DIFF_FILE = "hub_diagnostics_snapshot_diff.json"

@Field static final String SORTABLE_TABLE_SCRIPT_TEMPLATE = '''<script>
function sortTable___UNIQUE_ID__(colIdx, dataType) {
    var table = document.getElementById('__UNIQUE_ID__');
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
</script>'''

@Field static final String MEMORY_CHART_TOOLTIP_SCRIPT_TEMPLATE = '''<script>
(function(){
var d=[__TOOLTIP_DATA__];
var svg=document.getElementById('__SVG_ID__');
var container=document.getElementById('__CONTAINER_ID__');
var hr=document.getElementById('__SVG_ID___hover');
var vl=document.getElementById('__SVG_ID___vline');
var tb=document.getElementById('__SVG_ID___tipbg');
var tt=document.getElementById('__SVG_ID___tip');
var ml=__MARGIN_LEFT__,pw=__PLOT_WIDTH__,mt=__MARGIN_TOP__,sw=__SVG_WIDTH__;
container.scrollLeft=container.scrollWidth;
hr.addEventListener('mousemove',function(e){
  var rect=svg.getBoundingClientRect();
  var scaleX=sw/rect.width;
  var mx=(e.clientX-rect.left)*scaleX-ml;
  var idx=Math.round(mx/pw*(d.length-1));
  if(idx<0)idx=0;if(idx>=d.length)idx=d.length-1;
  var x=ml+idx/(d.length-1)*pw;
  vl.setAttribute('x1',x);vl.setAttribute('x2',x);vl.setAttribute('visibility','visible');
  var p=d[idx];
  var mem=(p.m/1024).toFixed(0);
  var txt=p.t+' | OS: '+mem+' MB | CPU: '+p.c.toFixed(2)+' | Java: '+(p.j/1024).toFixed(0)+' MB';
  tt.textContent=txt;
  var tw=txt.length*5.5+10;
  var tx=x+10;if(tx+tw>sw-10)tx=x-tw-10;
  tt.setAttribute('x',tx+5);tt.setAttribute('y',mt+14);tt.setAttribute('visibility','visible');
  tb.setAttribute('x',tx);tb.setAttribute('y',mt+2);tb.setAttribute('width',tw);tb.setAttribute('height',18);tb.setAttribute('visibility','visible');
});
hr.addEventListener('mouseleave',function(){
  vl.setAttribute('visibility','hidden');
  tt.setAttribute('visibility','hidden');
  tb.setAttribute('visibility','hidden');
});
})();
</script>'''

@Field static final String FULL_REPORT_STYLES = '''
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
'''

definition(
    name: "Hub Diagnostics",
    namespace: "iamtrep",
    author: "PJ",
    description: "Comprehensive hub diagnostics: inventory, performance tracking, network analysis, and snapshot comparison",
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
            href "performancePage", title: "Performance", description: "Runtime stats, resource consumers, and perf checkpoint comparison"
            href "systemHealthPage", title: "System Health", description: "Hub info, platform alerts, resources, and database"
            href "snapshotsPage", title: "Config Snapshots", description: "Configuration snapshots and diff"
            href "settingsPage", title: "Settings", description: "Thresholds, auto-scheduling, and options"
        }

        section("Actions") {
            input "btnDashSnapshot", "button", title: "Create Config Snapshot"
            input "btnDashCheckpoint", "button", title: "Create Perf Checkpoint"
        }

        section("Installation") {
            label title: "Assign a name", required: false
        }
    }
}

Map devicesPage() {
    Map deviceStats = analyzeDevices()
    Map pageModel = buildDevicesPageModel(deviceStats)

    dynamicPage(name: "devicesPage", title: "Device Analysis") {
        section("Device Summary") {
            paragraph formatMetricsTable(pageModel.summaryMetrics)
        }

        section("Protocol Distribution") {
            paragraph formatProtocolTable(deviceStats.byProtocol, deviceStats.idsByProtocol)
        }

        section("All Devices (${pageModel.allDeviceCount})", hideable: true, hidden: pageModel.allDeviceCount > 10) {
            paragraph generateSortableTable("devTable", [
                [label: "Name", field: "name", type: "string"],
                [label: "Type", field: "type", type: "string"],
                [label: "Protocol", field: "protocol", type: "string"],
                [label: "Room", field: "room", type: "string"],
                [label: "Status", field: "status", type: "string"],
                [label: "Last Activity", field: "lastActivity", type: "string"],
                [label: "Battery", field: "battery", type: "number"],
                [label: "Parent", field: "parent", type: "string"]
            ], pageModel.deviceRows)
        }

        if (pageModel.lowBatteryHtml) {
            section("Low Battery Alerts") {
                paragraph pageModel.lowBatteryHtml
            }
        }
    }
}

Map appsPage() {
    Map appStats = analyzeApps()
    Map pageModel = buildAppsPageModel(appStats)

    dynamicPage(name: "appsPage", title: "Application Analysis") {
        section("Application Summary") {
            paragraph formatMetricsTable(pageModel.summaryMetrics)
        }

        if (pageModel.hierarchyCount > 0) {
            section("Parent/Child App Hierarchy (${pageModel.hierarchyCount})", hideable: true, hidden: pageModel.hierarchyCount > 10) {
                paragraph formatParentChildHierarchy(appStats.parentChildHierarchy)
            }
        }

        section("App Instances by Type (${pageModel.appTypeCount})", hideable: true, hidden: pageModel.appTypeCount > 10) {
            paragraph formatAppsByTypeTable(appStats)
        }

        if (pageModel.platformAppCount > 0) {
            section("Platform Apps (${pageModel.platformAppCount})", hideable: true, hidden: pageModel.platformAppCount > 10) {
                paragraph "<i>Apps not exposed in the Apps list API but tracked by runtime stats. Includes dashboard room views, mode setters, and internal platform services. Monitor state size for unbounded growth.</i>"

                if (pageModel.largeStateCount > 0) {
                    paragraph "<span style='color: #ff9800;'>\u26A0 ${pageModel.largeStateCount} platform app${pageModel.largeStateCount > 1 ? 's' : ''} with state size > 5 KB — monitor for unbounded growth</span>"
                }

                paragraph generateSortableTable("platformApps", [
                    [label: "Name", field: "name", type: "string"],
                    [label: "State Size", field: "stateSize", type: "number"],
                    [label: "CPU %", field: "pctTotal", type: "number"],
                    [label: "Exec Count", field: "count", type: "number"],
                    [label: "Avg (ms)", field: "average", type: "number"],
                    [label: "Hub Actions", field: "hubActions", type: "number"],
                    [label: "Cloud Calls", field: "cloudCalls", type: "number"]
                ], pageModel.platformRows)
            }
        }
    }
}

Map networkPage() {
    Map networkData = analyzeNetwork()
    Map zigbeeMesh = fetchZigbeeMeshInfo()
    String zwaveVersion = fetchZwaveVersion()
    Map zwaveMesh = extractZwaveMeshQuality(networkData.zwave ?: [:])

    dynamicPage(name: "networkPage", title: "Network & Wireless Analysis") {
        section("Network Configuration") {
            if (networkData.network && !networkData.network.error) {
                Map net = networkData.network
                List netMetrics = [
                    ["IP Address", net.lanAddr ?: "N/A"],
                    ["Connection Type", net.usingStaticIP ? "Static IP" : "DHCP"],
                    ["Gateway", net.staticGateway ?: "N/A"],
                    ["Subnet Mask", net.staticSubnetMask ?: "N/A"]
                ]
                if (net.usingStaticIP && net.staticIP) {
                    netMetrics << ["Static IP", net.staticIP]
                }
                if (net.dnsServers && net.dnsServers.size() > 0) {
                    netMetrics << ["DNS Servers", net.dnsServers.join(", ")]
                }
                netMetrics << ["Ethernet", net.hasEthernet ? "Connected" : "Not Connected"]
                netMetrics << ["WiFi Available", net.hasWiFi ? "Yes" : "No"]
                if (net.hasWiFi && net.wifiNetwork) {
                    netMetrics << ["WiFi Network", net.wifiNetwork]
                }
                paragraph formatMetricsTable(netMetrics)
            } else {
                paragraph "<i>Network configuration unavailable</i>"
            }
        }

        section("Z-Wave Network") {
            if (networkData.zwave && !networkData.zwave.error) {
                Map zw = networkData.zwave
                String healthyColor = zw.healthy ? "#388e3c" : "#d32f2f"
                List zwMetrics = [
                    ["Enabled", zw.enabled ? "Yes" : "No"],
                    ["Healthy", "<span style='color: ${healthyColor};'>${zw.healthy ? 'Yes' : 'No'}</span>"],
                    ["Region", zw.region ?: "N/A"],
                    ["Node Count", zw.nodes ? zw.nodes.size() : 0],
                    ["Z-Wave JS", zw.zwaveJS ? "Enabled" : (zw.zwaveJSAvailable ? "Available" : "N/A")]
                ]
                if (zwaveVersion) {
                    zwMetrics << ["Firmware", zwaveVersion]
                }
                paragraph formatMetricsTable(zwMetrics)

                if (zw.isRadioUpdateNeeded) {
                    paragraph "<span style='color: #ff9800;'>\u26A0 Radio firmware update recommended</span>"
                }

                // Ghost/failed node detection
                if (zw.zwDevices) {
                    List ghostNodes = []
                    zw.zwDevices.each { nodeId, nodeData ->
                        if (nodeData instanceof Map) {
                            boolean isFailed = nodeData.status == "FAILED" || nodeData.failed == true
                            boolean noRoute = nodeData.route == null || nodeData.route == "" || nodeData.route == "No route"
                            boolean noName = !nodeData.name || nodeData.name == "Unknown" || nodeData.name == ""
                            if (isFailed || (noRoute && noName)) {
                                ghostNodes << [id: nodeId, deviceId: nodeData.deviceId, name: nodeData.name ?: "Unknown", status: nodeData.status ?: "No route"]
                            }
                        }
                    }
                    if (ghostNodes) {
                        paragraph "<b style='color: #d32f2f;'>Possible Ghost Nodes (${ghostNodes.size()}):</b>"
                        ghostNodes.each { Map ghost ->
                            String ghostNameHtml = ghost.deviceId ? "<a href='/device/edit/${ghost.deviceId}' target='_blank' style='color: #d32f2f; text-decoration: underline;'>${ghost.name}</a>" : ghost.name
                            paragraph "&nbsp;&nbsp;<span style='color: #d32f2f;'>Node ${ghost.id}: ${ghostNameHtml} (${ghost.status})</span>"
                        }
                        paragraph "<i>Ghost nodes can cause Z-Wave mesh instability. Remove them from Settings > Z-Wave Details.</i>"
                    }
                }
            } else {
                paragraph "<i>Z-Wave details unavailable</i>"
            }
        }

        if (zwaveMesh && zwaveMesh.nodes) {
            int zwNodeCount = zwaveMesh.nodeCount ?: 0
            section("Z-Wave Mesh Quality (${zwNodeCount} nodes)", hideable: true, hidden: zwNodeCount > 10) {
                String avgPerColor = zwaveMesh.avgPer == 0 ? "#388e3c" : (zwaveMesh.avgPer <= 1 ? "#ff9800" : "#d32f2f")
                String errColor = zwaveMesh.nodesWithErrors == 0 ? "#388e3c" : "#d32f2f"
                List meshMetrics = [
                    ["Node Count", zwaveMesh.nodeCount],
                    ["Average PER", "<span style='color: ${avgPerColor};'>${String.format('%.1f', zwaveMesh.avgPer as float)}%</span>"],
                    ["Nodes with Packet Errors", "<span style='color: ${errColor};'>${zwaveMesh.nodesWithErrors}</span>"],
                    ["Total Route Changes", zwaveMesh.totalRouteChanges]
                ]
                if (zwaveMesh.avgRssi != null) {
                    String rssiColor = zwaveMesh.avgRssi >= -60 ? "#388e3c" : (zwaveMesh.avgRssi >= -80 ? "#ff9800" : "#d32f2f")
                    meshMetrics << ["Average RSSI", "<span style='color: ${rssiColor};'>${zwaveMesh.avgRssi} dBm</span>"]
                }
                paragraph formatMetricsTable(meshMetrics)

                // Per-node table
                List nodeRows = zwaveMesh.nodes.collect { Map n ->
                    String stateColor = n.state == "OK" ? "" : "#d32f2f"
                    String perColor = n.per > 1 ? "#d32f2f" : (n.per > 0 ? "#ff9800" : "")
                    String rssiColor = ""
                    if (n.rssi != null) {
                        rssiColor = n.rssi >= -60 ? "#388e3c" : (n.rssi >= -80 ? "#ff9800" : "#d32f2f")
                    }
                    String nameHtml = n.deviceId ? "<a href='/device/edit/${n.deviceId}' target='_blank' style='color: #1A77C9; text-decoration: none;'>${escapeHtml(n.name as String)}</a>" : n.name
                    Map row = [
                        name: nameHtml,
                        _nameSort: n.name,
                        rssi: n.rssiStr ?: "N/A",
                        _rssiSort: n.rssi ?: -999,
                        per: n.per,
                        neighbors: n.neighbors,
                        route: n.route ?: "None",
                        routeChanges: n.routeChanges,
                        state: n.state
                    ]
                    if (stateColor) row["_stateColor"] = stateColor
                    if (perColor) row["_perColor"] = perColor
                    if (rssiColor) row["_rssiColor"] = rssiColor
                    return row
                }

                paragraph generateSortableTable("zwMesh", [
                    [label: "Device", field: "name", type: "string"],
                    [label: "RSSI", field: "rssi", type: "number"],
                    [label: "PER %", field: "per", type: "number"],
                    [label: "Neighbors", field: "neighbors", type: "number"],
                    [label: "Route", field: "route", type: "string"],
                    [label: "Route Changes", field: "routeChanges", type: "number"],
                    [label: "State", field: "state", type: "string"]
                ], nodeRows)

                // Problem node callout
                List problemNodes = zwaveMesh.nodes.findAll { Map n -> n.state != "OK" || n.per > 1 }
                if (problemNodes) {
                    paragraph "<b style='color: #d32f2f;'>Problem Nodes (${problemNodes.size()}):</b>"
                    problemNodes.each { Map n ->
                        List issues = []
                        if (n.state != "OK") issues << "State: ${n.state}"
                        if (n.per > 1) issues << "PER: ${n.per}%"
                        String probNameHtml = n.deviceId ? "<a href='/device/edit/${n.deviceId}' target='_blank' style='color: #d32f2f; text-decoration: underline;'>${escapeHtml(n.name as String)}</a>" : escapeHtml(n.name as String)
                        paragraph "&nbsp;&nbsp;<span style='color: #d32f2f;'>${probNameHtml} — ${issues.join(', ')}</span>"
                    }
                    paragraph "<i>High PER (Packet Error Rate) indicates unreliable communication. Check device distance, interference, or replace failed nodes.</i>"
                }
            }
        }

        section("Zigbee Network") {
            if (networkData.zigbee && !networkData.zigbee.error) {
                Map zb = networkData.zigbee
                String healthyColor = zb.healthy ? "#388e3c" : "#d32f2f"
                String channelDisplay = zb.channel ? zb.channel.toString() : "N/A"
                if (zb.channel && !(zb.channel in RECOMMENDED_ZIGBEE_CHANNELS)) {
                    channelDisplay = "<span style='color: #ff9800;'>${zb.channel}</span> (may overlap WiFi — recommended: ${RECOMMENDED_ZIGBEE_CHANNELS.join(', ')})"
                }
                if (zb.weakChannel) {
                    channelDisplay += " <span style='color: #d32f2f;'>[Weak]</span>"
                }

                List zbMetrics = [
                    ["Enabled", zb.enabled ? "Yes" : "No"],
                    ["Healthy", "<span style='color: ${healthyColor};'>${zb.healthy ? 'Yes' : 'No'}</span>"],
                    ["Network State", zb.networkState ?: "Unknown"],
                    ["Channel", channelDisplay],
                    ["PAN ID", zb.panId ?: "N/A"],
                    ["Extended PAN ID", zb.extendedPanId ?: "N/A"],
                    ["Device Count", zb.devices ? zb.devices.size() : 0],
                    ["Join Mode", zb.inJoinMode ? "Active" : "Inactive"]
                ]
                if (zb.devices) {
                    int totalDevices = zb.devices.size()
                    int activeDevices = zb.devices.count { it.active == true }
                    int inactiveDevices = totalDevices - activeDevices
                    String respColor = inactiveDevices == 0 ? "#388e3c" : (inactiveDevices > 3 ? "#d32f2f" : "#ff9800")
                    zbMetrics << ["Responsive", "<span style='color: ${respColor};'>${activeDevices} / ${totalDevices}</span>"]
                    if (inactiveDevices > 0) {
                        List nonResponsive = zb.devices.findAll { it.active != true }.collect { Map d ->
                            String dName = d.name ?: "Device ${d.id}"
                            d.id ? "<a href='/device/edit/${d.id}' target='_blank' style='color: #d32f2f;'>${dName}</a>" : dName
                        }
                        zbMetrics << ["Non-Responsive", nonResponsive.join(', ')]
                    }
                }
                paragraph formatMetricsTable(zbMetrics)
            } else {
                paragraph "<i>Zigbee details unavailable</i>"
            }
        }

        if (zigbeeMesh && zigbeeMesh.neighbors) {
            section("Zigbee Mesh Quality") {
                List meshMetrics = [
                    ["Repeater Neighbors", zigbeeMesh.neighbors.size()],
                    ["End Devices (Direct)", zigbeeMesh.childDevices?.size() ?: 0],
                    ["Routes", zigbeeMesh.routes?.size() ?: 0]
                ]
                if (zigbeeMesh.avgLqi != null) {
                    String lqiColor = zigbeeMesh.avgLqi >= 200 ? "#388e3c" : (zigbeeMesh.avgLqi >= 150 ? "#ff9800" : "#d32f2f")
                    meshMetrics << ["Average LQI", "<span style='color: ${lqiColor};'>${zigbeeMesh.avgLqi}</span> (min: ${zigbeeMesh.minLqi}, max: ${zigbeeMesh.maxLqi})"]
                }
                if (zigbeeMesh.weakNeighbors) {
                    meshMetrics << ["Weak Neighbors (LQI < 150)", "<span style='color: #d32f2f;'>${zigbeeMesh.weakNeighbors.size()}</span>"]
                }
                if (zigbeeMesh.staleNeighbors) {
                    meshMetrics << ["Stale Neighbors (age > 6)", "<span style='color: #ff9800;'>${zigbeeMesh.staleNeighbors.size()}</span>"]
                }
                paragraph formatMetricsTable(meshMetrics)

                if (zigbeeMesh.weakNeighbors && zigbeeMesh.weakNeighbors.size() > 0) {
                    paragraph "<b style='color: #d32f2f;'>Weak Neighbor Details:</b>"
                    zigbeeMesh.weakNeighbors.each { Map n ->
                        paragraph "&nbsp;&nbsp;${n.shortId ?: 'Unknown'} — LQI: ${n.lqi}"
                    }
                    paragraph "<i>Low LQI indicates poor signal quality. Consider adding Zigbee repeaters near these devices.</i>"
                }
            }
        }

        section("Matter Network") {
            if (networkData.matter && !networkData.matter.error) {
                Map mt = networkData.matter
                List mtMetrics = [
                    ["Enabled", mt.enabled ? "Yes" : "No"],
                    ["Installed", mt.installed ? "Yes" : "No"],
                    ["Network State", mt.networkState ?: "Unknown"],
                    ["Fabric ID", mt.fabricId ?: "N/A"],
                    ["Device Count", mt.devices ? mt.devices.size() : 0]
                ]
                if (mt.ipAddresses && mt.ipAddresses.size() > 0) {
                    mt.ipAddresses.each { addr ->
                        mtMetrics << ["IPv6 (${addr.interface})", addr.address]
                    }
                }
                paragraph formatMetricsTable(mtMetrics)
                if (mt.rebootRequired) {
                    paragraph "<span style='color: #d32f2f;'>\u26A0 Reboot required for Matter changes</span>"
                }
            } else {
                paragraph "<i>Matter details unavailable or no Matter devices</i>"
            }
        }

        section("Hub Mesh") {
            if (networkData.hubMesh && !networkData.hubMesh.error) {
                Map hm = networkData.hubMesh
                List hmMetrics = [
                    ["Status", hm.hubMeshEnabled ? "Enabled" : "Disabled"],
                    ["Shared Devices from this Hub", hm.sharedDevices ? hm.sharedDevices.size() : 0],
                    ["Devices Linked from Other Hubs", hm.localLinkedDevices ? hm.localLinkedDevices.size() : 0],
                    ["Shared Hub Variables", hm.sharedHubVariables ? hm.sharedHubVariables.size() : 0],
                    ["Linked Hub Variables", hm.localLinkedHubVariables ? hm.localLinkedHubVariables.size() : 0]
                ]
                paragraph formatMetricsTable(hmMetrics)

                if (hm.hubList && hm.hubList.size() > 0) {
                    paragraph "<b>Linked Hubs (${hm.hubList.size()}):</b>"
                    hm.hubList.each { hub ->
                        String status = hub.offline ? "Offline" : "Online"
                        String statusColor = hub.offline ? "#d32f2f" : "#388e3c"
                        int sharedDevCount = hub.deviceIds ? hub.deviceIds.size() : 0
                        int sharedVarCount = hub.hubVarNames ? hub.hubVarNames.size() : 0
                        paragraph "&nbsp;&nbsp;<span style='color: ${statusColor};'><b>${hub.name}</b></span> (${hub.ipAddress}) — ${status} | ${sharedDevCount} devices, ${sharedVarCount} variables"
                    }
                }
            } else {
                paragraph "<i>Hub Mesh details unavailable</i>"
            }
        }
    }
}

Map performancePage() {
    Map stats = fetchCurrentStats()
    Map resources = fetchSystemResources()
    List checkpoints = loadCheckpoints()
    Map pageModel = buildPerformancePageModel(stats, resources, checkpoints)

    dynamicPage(name: "performancePage", title: "Performance Analysis") {
        section("Current Runtime Stats") {
            paragraph generateRuntimeSummary(stats, resources)
        }

        section("Perf Checkpoints") {
            paragraph "<i>Create perf checkpoints to compare activity over a specific time window. By default, the tables below show all activity since the last reboot.</i>"
            paragraph "Current checkpoints: ${pageModel.checkpointCount}/${settings.maxCheckpoints ?: 10}"
            input "btnCreateCheckpoint", "button", title: "Create Perf Checkpoint Now"
            if (pageModel.checkpointCount > 0) {
                input "btnClearCheckpoints", "button", title: "Clear All Perf Checkpoints"
            }
        }

        if (pageModel.checkpointCount > 0) {
            section("Saved Checkpoints", hideable: true, hidden: pageModel.checkpointCount > 10) {
                paragraph generateCheckpointTable(checkpoints)
            }

            section("Compare Perf Checkpoints") {
                input "compareBaseline", "enum", title: "Baseline (earlier)", options: pageModel.baselineOptions, required: false, submitOnChange: true

                if (compareBaseline != null) {
                    if (pageModel.checkpointOptions.size() <= 1) {
                        paragraph "<span style='color: #ff9800;'>No valid checkpoints for comparison with selected baseline (reboot may have occurred). Select 'Now' to compare current state.</span>"
                    }
                    input "compareCheckpoint", "enum", title: "Compare to (later)", options: pageModel.checkpointOptions, required: false, submitOnChange: true

                    if (compareBaseline != null && compareCheckpoint != null) {
                        input "btnCompare", "button", title: "Compare Selected"
                    }
                }
                if (hasSavedPerformanceComparison()) {
                    input "btnClearComparison", "button", title: "Reset to Since Startup"
                }
            }
        }

        // Performance comparison — custom if set, otherwise startup→now
        if (stats) {
            section("Performance Breakdown") {
                paragraph pageModel.comparisonHtml
            }
        }
    }
}

Map systemHealthPage() {
    Map systemHealth = analyzeSystemHealth()
    Map hubInfo = getHubInfo()
    Map pageModel = buildSystemHealthPageModel(systemHealth, hubInfo)

    dynamicPage(name: "systemHealthPage", title: "System Health") {
        section("Hub Information") {
            if (location.hubs && location.hubs.size() > 0) {
                def hub = location.hubs[0]
                paragraph formatMetricsTable(pageModel.hubMetrics)
            } else {
                paragraph "<span style='color: red;'>Hub information not available</span>"
            }
        }

        if (systemHealth.alerts && systemHealth.alerts.size() > 0) {
            section("Alerts & Warnings") {
                systemHealth.alerts.each { String alert ->
                    paragraph "\u26A0 ${alert}"
                }
            }
        }

        section("System Resources") {
            if (pageModel.resourceMetrics) {
                paragraph formatMetricsTable(pageModel.resourceMetrics)
            } else {
                paragraph "<i>Memory information unavailable</i>"
            }
        }

        section("Resource History") {
            List memHistory = fetchMemoryHistory()
            if (memHistory && memHistory.size() >= 2) {
                paragraph generateResourceChart(memHistory)
            } else {
                paragraph "<i>Resource history not available</i>"
            }
        }

        section("Database & Storage") {
            if (pageModel.databaseMetrics) {
                paragraph formatMetricsTable(pageModel.databaseMetrics)
            } else {
                paragraph "<i>Database information unavailable</i>"
            }
        }
    }
}

Map snapshotsPage() {
    List snapshots = loadSnapshots()
    Map pageModel = buildSnapshotsPageModel(snapshots)

    dynamicPage(name: "snapshotsPage", title: "Config Snapshots & Reports") {
        section("Config Snapshot Management") {
            paragraph "Config snapshots capture the complete hub configuration (devices, apps, settings) for historical tracking and comparison."
            paragraph "<b>Current Snapshots:</b> ${pageModel.snapshotCount} / ${settings.maxSnapshots ?: 10}"
        }

        section("Actions") {
            input "btnCreateSnapshot", "button", title: "Create Config Snapshot Now"
            if (snapshots.size() > 0) {
                input "btnClearSnapshots", "button", title: "Clear All Config Snapshots"
            }
            input "btnFullReport", "button", title: "Generate Full Report"
        }

        if (snapshots.size() > 0) {
            section("Available Snapshots") {
                paragraph generateSnapshotsTable(snapshots)
            }

            section("View Snapshot", hideable: true, hidden: true) {
                input "viewSnapshotIdx", "enum", title: "Select snapshot to view", options: pageModel.viewSnapshotOptions, required: false, submitOnChange: true

                if (viewSnapshotIdx != null) {
                    int viewIdx = parseSnapshotSelectionIndex(viewSnapshotIdx, "view_")
                    if (viewIdx >= 0 && viewIdx < snapshots.size()) {
                        paragraph renderSnapshotView(snapshots[viewIdx])
                    }
                }
            }
        } else {
            section {
                paragraph "<i>No config snapshots available. Create your first snapshot to get started.</i>"
            }
        }

        if (snapshots.size() > 0) {
            section("Compare Config Snapshots") {
                input "diffOlder", "enum", title: "Older snapshot", options: pageModel.diffOlderOptions, required: false, submitOnChange: true
                input "diffNewer", "enum", title: "Newer snapshot", options: pageModel.newerSnapshotOptions, required: false, submitOnChange: true

                if (diffOlder != null && diffNewer != null && diffOlder != diffNewer) {
                    input "btnDiffSnapshots", "button", title: "Compare Selected"
                }
            }

            if (hasSavedSnapshotDiff()) {
                section("Config Snapshot Comparison Results") {
                    paragraph buildSnapshotDiffHtml()
                }
            }
        }
    }
}

// ===== PAGE VIEW MODELS =====

Map buildDevicesPageModel(Map deviceStats) {
    return [
        summaryMetrics: buildDeviceSummaryMetrics(deviceStats),
        allDeviceCount: deviceStats.allDevices?.size() ?: 0,
        deviceRows: buildDeviceTableRows(deviceStats.allDevices ?: []),
        lowBatteryHtml: renderLowBatteryAlerts(deviceStats.lowBatteryDevices ?: [])
    ]
}

List buildDeviceSummaryMetrics(Map deviceStats) {
    Map idsByStatus = deviceStats.idsByStatus ?: [active: [], inactive: [], disabled: []]
    return [
        ["Total Devices", "<a href='/device/list' target='_blank' style='color: #1A77C9; text-decoration: none;'>${deviceStats.totalDevices}</a>"],
        ["Active Devices", "${deviceListLink(deviceStats.activeDevices, idsByStatus.active)} (${settings.inactivityDays ?: 7} days)"],
        ["Inactive Devices", deviceListLink(deviceStats.inactiveDevices, (idsByStatus.inactive ?: []) + (idsByStatus.disabled ?: []))],
        ["Disabled Devices", deviceListLink(deviceStats.disabledDevices, idsByStatus.disabled)],
        ["Parent Devices", deviceListLink(deviceStats.parentDevices, deviceStats.parentIds)],
        ["Child Devices", deviceListLink(deviceStats.childDevices, deviceStats.parentIds)],
        ["Hub Mesh Linked", deviceListLink(deviceStats.linkedDevices, deviceStats.linkedIds)],
        ["Battery-Powered", deviceListLink(deviceStats.batteryDevices, deviceStats.batteryIds)]
    ]
}

List buildDeviceTableRows(List allDevices) {
    return allDevices.collect { Map dev ->
        String nameLink = "<a href='/device/edit/${dev.id}' target='_blank' style='color: #1A77C9; text-decoration: none;'>${escapeHtml(dev.name as String)}</a>"
        String typeDisplay = escapeHtml(dev.type as String)
        if (dev.userType && dev.deviceTypeId) {
            typeDisplay = "<a href='/driver/editor/${dev.deviceTypeId}' target='_blank' style='color: #1A77C9; text-decoration: none;'>${typeDisplay}</a>"
        }

        String parentDisplay = "-"
        if (dev.parentAppName) {
            parentDisplay = "<a href='/installedapp/configure/${dev.parentAppId}' target='_blank' style='color: #1A77C9; text-decoration: none;'>${escapeHtml(dev.parentAppName as String)}</a>"
        } else if (dev.parentDeviceName) {
            parentDisplay = "<a href='/device/edit/${dev.parentDeviceId}' target='_blank' style='color: #1A77C9; text-decoration: none;'>${escapeHtml(dev.parentDeviceName as String)}</a>"
        }

        Map row = [
            name: nameLink,
            _nameSort: dev.name,
            type: typeDisplay,
            _typeSort: dev.type,
            protocol: PROTOCOL_DISPLAY[dev.protocol] ?: (dev.protocol ?: "").toString().capitalize(),
            room: dev.room ?: "-",
            status: dev.status ?: "",
            lastActivity: dev.lastActivity ?: "Never",
            battery: dev.battery != null ? dev.battery : "",
            parent: parentDisplay,
            _parentSort: dev.parentAppName ?: dev.parentDeviceName ?: ""
        ]
        if (dev.status == "Active") row._statusColor = "#388e3c"
        else if (dev.status == "Disabled") row._statusColor = "#d32f2f"
        else if (dev.status == "Inactive") row._statusColor = "#ff9800"
        if (dev.battery != null) {
            if (dev.battery <= 20) row._batteryColor = "#d32f2f"
            else if (dev.battery <= 50) row._batteryColor = "#ff9800"
            else row._batteryColor = "#388e3c"
        }
        return row
    }
}

String renderLowBatteryAlerts(List lowBatteryDevices) {
    if (!lowBatteryDevices) return null
    return lowBatteryDevices.collect { Map dev ->
        "<span style='color: #d32f2f;'><a href='/device/edit/${dev.id}' target='_blank' style='color: #d32f2f;'>${dev.name}</a>: ${dev.battery}%</span>"
    }.join("<br>")
}

Map buildAppsPageModel(Map appStats) {
    List platformApps = appStats.platformApps ?: []
    return [
        summaryMetrics: buildAppSummaryMetrics(appStats),
        hierarchyCount: appStats.parentChildHierarchy?.size() ?: 0,
        appTypeCount: appStats.byNamespace?.size() ?: 0,
        platformAppCount: platformApps.size(),
        largeStateCount: platformApps.count { (it.stateSize as int) > 5000 },
        platformRows: buildPlatformAppRows(platformApps)
    ]
}

List buildAppSummaryMetrics(Map appStats) {
    int platformCount = appStats.platformApps?.size() ?: 0
    List metrics = [
        ["Visible App Instances", appStats.totalApps],
        ["System Apps", appStats.builtInApps],
        ["User Apps", appStats.userApps],
        ["Parent Apps", appStats.parentApps],
        ["Child Apps", appStats.childApps]
    ]
    if (platformCount > 0) {
        metrics << ["Platform Apps (hidden)", platformCount]
        metrics << ["Total (from runtime)", appStats.runtimeTotalApps ?: 0]
    }
    return metrics
}

List buildPlatformAppRows(List platformApps) {
    return platformApps.collect { Map app ->
        int stateSize = app.stateSize as int
        String stateSizeColor = stateSize > 10000 ? "#d32f2f" : (stateSize > 5000 ? "#ff9800" : "")
        String appName = escapeHtml(app.name as String)
        String nameDisplay = app.id ? "<a href='/installedapp/status/${app.id}' target='_blank' style='color: #1A77C9; text-decoration: none;'>${appName}</a>" : appName
        Map row = [
            name: nameDisplay,
            _nameSort: app.name,
            stateSize: stateSize,
            pctTotal: String.format('%.3f', (app.pctTotal as Number).floatValue()) + "%",
            _pctTotalSort: app.pctTotal,
            count: app.count,
            average: app.count > 0 ? String.format('%.1f', (app.average as Number).floatValue()) : "0",
            _averageSort: app.average,
            hubActions: app.hubActionCount,
            cloudCalls: app.cloudCallCount
        ]
        if (stateSizeColor) row._stateSizeColor = stateSizeColor
        if (app.largeState) row._stateSizeColor = "#d32f2f"
        return row
    }
}

Map buildPerformancePageModel(Map stats, Map resources, List checkpoints) {
    return [
        checkpointCount: checkpoints?.size() ?: 0,
        baselineOptions: buildPerformanceBaselineOptions(checkpoints),
        checkpointOptions: buildPerformanceCheckpointOptions(checkpoints, compareBaseline),
        comparisonHtml: buildPerformanceComparisonHtml(stats, resources)
    ]
}

Map buildPerformanceBaselineOptions(List checkpoints) {
    Map baselineOptions = ["startup": "Since Startup (default)"]
    (checkpoints ?: []).eachWithIndex { Map cp, int idx ->
        baselineOptions["${idx}"] = "Checkpoint ${idx + 1} - ${cp.timestamp}"
    }
    return baselineOptions
}

Map buildPerformanceCheckpointOptions(List checkpoints, Object selectedBaseline) {
    if (selectedBaseline == null) return [:]

    Map checkpointOptions = ["now": "Now (default)"]
    if (selectedBaseline == "startup") {
        (checkpoints ?: []).eachWithIndex { Map cp, int idx ->
            checkpointOptions["${idx}"] = "Checkpoint ${idx + 1} - ${cp.timestamp}"
        }
        return checkpointOptions
    }

    int baselineIdx = selectedBaseline.toString().toInteger()
    if (baselineIdx < 0 || baselineIdx >= (checkpoints?.size() ?: 0)) return checkpointOptions

    Map baselineCp = checkpoints[baselineIdx]
    checkpoints.eachWithIndex { Map cp, int idx ->
        if (idx < baselineIdx) {
            long baselineDevTotal = baselineCp.stats.deviceStats.sum { it.total ?: 0 } ?: 0
            long checkpointDevTotal = cp.stats.deviceStats.sum { it.total ?: 0 } ?: 0
            if (checkpointDevTotal >= baselineDevTotal) {
                checkpointOptions["${idx}"] = "Checkpoint ${idx + 1} - ${cp.timestamp}"
            }
        }
    }
    return checkpointOptions
}

String buildPerformanceComparisonHtml(Map stats, Map resources) {
    if (!stats) return null
    Map savedPayload = loadPerformanceComparisonPayload()
    if (savedPayload) return renderPerformanceComparisonPayload(savedPayload)

    stats.resources = resources
    Map zwaveData = fetchEndpoint(ZWAVE_DETAILS_URL, "Z-Wave details", 20)
    Map zigbeeData = fetchEndpoint(ZIGBEE_DETAILS_URL, "Zigbee details", 20)
    stats.radioStats = [
        zwave: extractZwaveMessageCounts(zwaveData),
        zigbee: extractZigbeeMessageCounts(zigbeeData)
    ]
    Map zeroBaseline = buildZeroBaseline(stats, resources)
    return generateComparison(zeroBaseline, stats,
        "Startup (0:00:00)", "Now (${new Date().format('yyyy-MM-dd HH:mm:ss')})")
}

Map buildSystemHealthPageModel(Map systemHealth, Map hubInfo) {
    return [
        hubMetrics: buildHubMetrics(hubInfo),
        resourceMetrics: buildSystemResourceMetrics(systemHealth),
        databaseMetrics: buildDatabaseMetrics(systemHealth)
    ]
}

List buildHubMetrics(Map hubInfo) {
    def hub = (location.hubs && location.hubs.size() > 0) ? location.hubs[0] : null
    return [
        ["Hub Name", hubInfo.name],
        ["Hub ID", hub?.id ?: "N/A"],
        ["Hardware Model", hubInfo.hardware],
        ["Firmware Version", hubInfo.firmware],
        ["Local IP", hubInfo.ip],
        ["Zigbee ID", hub?.zigbeeId ?: "N/A"],
        ["Location", location.name ?: "N/A"],
        ["Mode", location.currentMode ?: "N/A"],
        ["Time Zone", location.timeZone?.ID ?: "N/A"]
    ]
}

List buildSystemResourceMetrics(Map systemHealth) {
    if (!systemHealth.memory) return null

    Map mem = systemHealth.memory
    String memColor = (mem.freeOSMemory ?: 0) < 76800 ? "#d32f2f" : ((mem.freeOSMemory ?: 0) < 102400 ? "#ff9800" : "#388e3c")
    String cpuColor = (mem.cpuAvg5min ?: 0) > 8.0 ? "#d32f2f" : ((mem.cpuAvg5min ?: 0) > 4.0 ? "#ff9800" : "#388e3c")
    List resourceMetrics = [
        ["Free OS Memory", "<span style='color: ${memColor};'>${formatMemory(mem.freeOSMemory ?: 0)}</span>"],
        ["CPU Load Avg (5m)", "<span style='color: ${cpuColor};'>${String.format('%.2f', (mem.cpuAvg5min ?: 0) as float)}</span>"],
        ["Total Java Memory", formatMemory(mem.totalJavaMemory ?: 0)],
        ["Free Java Memory", formatMemory(mem.freeJavaMemory ?: 0)],
        ["Direct Java Memory", formatMemory(mem.directJavaMemory ?: 0)]
    ]
    if (systemHealth.temperature != null) {
        Float temp = systemHealth.temperature
        String tempColor = temp > 77 ? "#d32f2f" : (temp > 50 ? "#ff9800" : "#388e3c")
        String tempF = String.format("%.1f", temp * 9.0 / 5.0 + 32.0)
        resourceMetrics << ["Hub Temperature", "<span style='color: ${tempColor};'>${String.format('%.1f', temp)}\u00B0C (${tempF}\u00B0F)</span>"]
    }
    return resourceMetrics
}

List buildDatabaseMetrics(Map systemHealth) {
    List dbMetrics = []
    if (systemHealth.databaseSize != null) {
        String dbColor = "#388e3c"
        if (systemHealth.hubAlerts?.alerts?.hubHugeDatabase) dbColor = "#d32f2f"
        else if (systemHealth.hubAlerts?.alerts?.hubLargeDatabase) dbColor = "#d32f2f"
        else if (systemHealth.hubAlerts?.alerts?.hubLargeishDatabase) dbColor = "#ff9800"
        dbMetrics << ["Database Size", "<span style='color: ${dbColor};'>${systemHealth.databaseSize} MB</span>"]
    }
    if (systemHealth.stateCompression) {
        dbMetrics << ["State Compression", systemHealth.stateCompression.enabled ? "Enabled" : "<span style='color: #ff9800;'>Disabled</span>"]
    }
    if (systemHealth.eventStateLimits) {
        Map lim = systemHealth.eventStateLimits
        if (lim.maxEvents) dbMetrics << ["Max Events per Device", lim.maxEvents]
        if (lim.maxEventAgeDays) dbMetrics << ["Max Event Age", "${lim.maxEventAgeDays} days"]
        if (lim.maxStateAgeDays) dbMetrics << ["Max Device State Age", "${lim.maxStateAgeDays} days"]
    }
    return dbMetrics ?: null
}

Map buildSnapshotsPageModel(List snapshots) {
    Map snapshotOptions = buildSnapshotOptions(snapshots)
    return [
        snapshotCount      : snapshots?.size() ?: 0,
        viewSnapshotOptions: prefixSnapshotOptions(snapshotOptions, "view_"),
        diffOlderOptions   : prefixSnapshotOptions(snapshotOptions, "diff_"),
        newerSnapshotOptions: ["now": "Now (create new snapshot)"] + prefixSnapshotOptions(snapshotOptions, "diff_")
    ]
}

Map buildSnapshotOptions(List snapshots) {
    Map options = [:]
    (snapshots ?: []).eachWithIndex { Map snap, int idx ->
        String fw = snap.hubInfo?.firmware ? " | fw ${snap.hubInfo.firmware}" : ""
        options["${idx}"] = "${snap.timestamp} (${snap.devices?.totalDevices ?: 0} devices${fw})"
    }
    return options
}

Map prefixSnapshotOptions(Map options, String prefix) {
    Map prefixed = [:]
    (options ?: [:]).each { String key, String value ->
        prefixed["${prefix}${key}"] = value
    }
    return prefixed
}

int parseSnapshotSelectionIndex(Object selection, String prefix) {
    if (selection == null) return -1
    String value = selection as String
    if (!value.startsWith(prefix)) return -1
    return value.substring(prefix.length()).toInteger()
}

Map settingsPage() {
    dynamicPage(name: "settingsPage", title: "Settings") {
        section("Automatic Config Snapshots") {
            input "autoSnapshot", "bool", title: "Enable automatic config snapshots", defaultValue: false, submitOnChange: true
            if (autoSnapshot) {
                input "snapshotInterval", "enum", title: "Config snapshot interval",
                    options: ["1": "1 hour", "6": "6 hours", "12": "12 hours", "24": "24 hours"],
                    defaultValue: "24", required: true
            }
            input "maxSnapshots", "number", title: "Maximum config snapshots to retain", defaultValue: 10, range: "1..50", required: true
        }

        section("Automatic Perf Checkpoints") {
            input "autoCheckpoint", "bool", title: "Enable automatic perf checkpoints", defaultValue: false, submitOnChange: true
            if (autoCheckpoint) {
                input "checkpointInterval", "enum", title: "Perf checkpoint interval",
                    options: ["5": "5 minutes", "15": "15 minutes", "30": "30 minutes",
                             "60": "1 hour", "360": "6 hours", "720": "12 hours", "1440": "24 hours"],
                    defaultValue: "60", required: true
            }
            input "maxCheckpoints", "number", title: "Maximum perf checkpoints to keep", defaultValue: 10, range: "1..50", required: true
        }

        section("Device Monitoring") {
            input "inactivityDays", "number", title: "Device inactivity threshold (days)", defaultValue: 7, range: "1..90", required: true
            input "lowBatteryThreshold", "number", title: "Low battery threshold (%)", defaultValue: 20, range: "1..50", required: true
            input "chattyDeviceThreshold", "number", title: "Chatty device threshold (msgs/min)", defaultValue: 10, range: "1..1000", required: true
            paragraph "<i>Devices exceeding this message rate between perf checkpoints will be flagged as chatty.</i>"
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
        case "btnClearComparison":
            clearPerformanceComparison()
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

List fetchMemoryHistory() {
    try {
        Map params = [
            uri: MEMORY_HISTORY_URL,
            contentType: "text/plain",
            timeout: 30
        ]
        List dataPoints = []
        httpGet(params) { resp ->
            if (resp.success) {
                String[] lines = resp.data.text.split('\n')
                // Skip header line
                for (int i = 1; i < lines.length; i++) {
                    String line = lines[i].trim()
                    if (!line) continue
                    String[] parts = line.split(',')
                    if (parts.length >= 6) {
                        dataPoints << [
                            time: parts[0].trim(),
                            freeOS: parts[1].trim().toInteger(),
                            cpuLoad: parts[2].trim().toFloat(),
                            freeJava: parts[4].trim().toInteger(),
                            directJava: parts[5].trim().toInteger()
                        ]
                    }
                }
            }
        }
        return dataPoints
    } catch (Exception e) {
        log.error "Error fetching memory history: ${e.message}"
        return []
    }
}

String generateResourceChart(List dataPoints) {
    if (!dataPoints || dataPoints.size() < 2) return "<i>Insufficient history data for chart</i>"

    // Fixed density: pixels per data point — gives ~7 days at 5-min intervals in 800px viewport
    float pxPerPoint = 4.0
    int viewportWidth = 800
    int height = 300
    int marginLeft = 65
    int marginRight = 65
    int marginTop = 25
    int marginBottom = 50
    int plotH = height - marginTop - marginBottom

    int numPoints = dataPoints.size()
    int plotW = Math.max(viewportWidth - marginLeft - marginRight, (numPoints * pxPerPoint) as int)
    int svgWidth = plotW + marginLeft + marginRight

    // Calculate value ranges across ALL data (not just viewport)
    int maxFreeOS = dataPoints.collect { it.freeOS }.max()
    float maxCpu = dataPoints.collect { it.cpuLoad }.max()

    // Pad max to nice round numbers, both axes start at zero
    maxFreeOS = (Math.ceil(maxFreeOS / 50000.0) * 50000) as int
    int minFreeOS = 0
    maxCpu = Math.ceil(maxCpu)
    if (maxCpu < 1) maxCpu = 1

    int freeOSRange = maxFreeOS - minFreeOS
    if (freeOSRange == 0) freeOSRange = 1

    // Build polyline points
    StringBuilder freeOSPath = new StringBuilder()
    StringBuilder cpuPath = new StringBuilder()
    StringBuilder freeJavaPath = new StringBuilder()

    dataPoints.eachWithIndex { Map pt, int idx ->
        float x = marginLeft + (idx / (float)(numPoints - 1)) * plotW
        float yFreeOS = marginTop + plotH - ((pt.freeOS - minFreeOS) / (float) freeOSRange) * plotH
        float yCpu = marginTop + plotH - (pt.cpuLoad / maxCpu) * plotH
        float yFreeJava = marginTop + plotH - ((pt.freeJava - minFreeOS) / (float) freeOSRange) * plotH

        // Clamp to plot area
        if (yFreeJava < marginTop) yFreeJava = marginTop
        if (yFreeJava > marginTop + plotH) yFreeJava = marginTop + plotH

        String sep = idx == 0 ? "" : " "
        freeOSPath.append("${sep}${String.format('%.1f', x)},${String.format('%.1f', yFreeOS)}")
        cpuPath.append("${sep}${String.format('%.1f', x)},${String.format('%.1f', yCpu)}")
        freeJavaPath.append("${sep}${String.format('%.1f', x)},${String.format('%.1f', yFreeJava)}")
    }

    // Threshold lines
    float warningY = marginTop + plotH - ((102400 - minFreeOS) / (float) freeOSRange) * plotH
    boolean showWarningLine = warningY > marginTop && warningY < (marginTop + plotH)
    float criticalY = marginTop + plotH - ((76800 - minFreeOS) / (float) freeOSRange) * plotH
    boolean showCriticalLine = criticalY > marginTop && criticalY < (marginTop + plotH)

    // Y-axis labels
    int ySteps = 4
    int yStepVal = freeOSRange / ySteps
    int cpuSteps = 4

    // Time axis: label every ~6 hours of data (72 points at 5-min intervals)
    int labelEvery = Math.max(1, 72)
    // Detect day boundaries for date labels
    String prevDay = ""

    String uniqueId = "resChart_${now()}"
    String containerId = "resChartDiv_${now()}"

    StringBuilder svg = new StringBuilder()

    // Scrollable container — viewport is fixed width, SVG may be wider
    svg.append("<div id='${containerId}' style='overflow-x: auto; max-width: 100%; border: 1px solid #e0e0e0; border-radius: 4px;'>")
    svg.append("<svg id='${uniqueId}' width='${svgWidth}' height='${height}' style='font-family: sans-serif; font-size: 10px; display: block;'>")

    // Background
    svg.append("<rect width='${svgWidth}' height='${height}' fill='#fafafa'/>")

    // Horizontal grid lines + left Y-axis labels
    for (int i = 0; i <= ySteps; i++) {
        float y = marginTop + (i / (float) ySteps) * plotH
        svg.append("<line x1='${marginLeft}' y1='${y}' x2='${marginLeft + plotW}' y2='${y}' stroke='#e0e0e0' stroke-width='0.5'/>")
        int memVal = maxFreeOS - (i * yStepVal)
        svg.append("<text x='${marginLeft - 5}' y='${y + 3}' text-anchor='end' fill='#1565C0' font-size='9'>${(memVal / 1024) as int} MB</text>")
    }

    // Right Y-axis labels (CPU Load) — fixed to right side of SVG
    for (int i = 0; i <= cpuSteps; i++) {
        float y = marginTop + (i / (float) cpuSteps) * plotH
        float cpuVal = maxCpu - (i / (float) cpuSteps) * maxCpu
        svg.append("<text x='${marginLeft + plotW + 5}' y='${y + 3}' text-anchor='start' fill='#E65100' font-size='9'>${String.format('%.1f', cpuVal)}</text>")
    }

    // Time axis labels — show time every labelEvery points, date on day changes
    for (int i = 0; i < numPoints; i++) {
        String timeStr = dataPoints[i].time
        String day = timeStr.substring(0, 5)
        float x = marginLeft + (i / (float)(numPoints - 1)) * plotW

        if (day != prevDay) {
            // Day boundary — draw date label and vertical separator
            svg.append("<line x1='${x}' y1='${marginTop}' x2='${x}' y2='${marginTop + plotH}' stroke='#bbb' stroke-width='0.5' stroke-dasharray='4,4'/>")
            svg.append("<text x='${x + 3}' y='${marginTop + plotH + 28}' text-anchor='start' fill='#333' font-size='9' font-weight='bold'>${day}</text>")
            prevDay = day
        } else if (i % labelEvery == 0) {
            // Time tick
            String timeOnly = timeStr.length() > 6 ? timeStr.substring(6, 11) : timeStr
            svg.append("<line x1='${x}' y1='${marginTop + plotH}' x2='${x}' y2='${marginTop + plotH + 4}' stroke='#999'/>")
            svg.append("<text x='${x}' y='${marginTop + plotH + 16}' text-anchor='middle' fill='#666' font-size='9'>${timeOnly}</text>")
        }
    }

    // Threshold lines (span full plot width)
    if (showWarningLine) {
        svg.append("<line x1='${marginLeft}' y1='${warningY}' x2='${marginLeft + plotW}' y2='${warningY}' stroke='#ff9800' stroke-width='1' stroke-dasharray='6,3' opacity='0.7'/>")
    }
    if (showCriticalLine) {
        svg.append("<line x1='${marginLeft}' y1='${criticalY}' x2='${marginLeft + plotW}' y2='${criticalY}' stroke='#d32f2f' stroke-width='1' stroke-dasharray='6,3' opacity='0.7'/>")
    }

    // Plot area border
    svg.append("<rect x='${marginLeft}' y='${marginTop}' width='${plotW}' height='${plotH}' fill='none' stroke='#ccc' stroke-width='1'/>")

    // Data lines
    svg.append("<polyline points='${freeJavaPath}' fill='none' stroke='#66BB6A' stroke-width='1.2' opacity='0.7'/>")
    svg.append("<polyline points='${freeOSPath}' fill='none' stroke='#1565C0' stroke-width='1.8'/>")
    svg.append("<polyline points='${cpuPath}' fill='none' stroke='#E65100' stroke-width='1.2' opacity='0.8'/>")

    // Axis labels
    svg.append("<text x='${marginLeft - 5}' y='${marginTop - 8}' text-anchor='end' fill='#1565C0' font-size='10' font-weight='bold'>Memory</text>")
    svg.append("<text x='${marginLeft + plotW + 5}' y='${marginTop - 8}' text-anchor='start' fill='#E65100' font-size='10' font-weight='bold'>CPU Load</text>")

    // Legend (positioned near top-right of current viewport via JS)
    int legendX = marginLeft + plotW - 90
    int legendY = marginTop + 12
    svg.append("<g id='${uniqueId}_legend'>")
    svg.append("<rect x='${legendX}' y='${legendY - 9}' width='85' height='52' fill='white' fill-opacity='0.9' stroke='#ddd' rx='3'/>")
    svg.append("<line x1='${legendX + 4}' y1='${legendY}' x2='${legendX + 18}' y2='${legendY}' stroke='#1565C0' stroke-width='2'/>")
    svg.append("<text x='${legendX + 22}' y='${legendY + 3}' fill='#333' font-size='9'>Free OS</text>")
    svg.append("<line x1='${legendX + 4}' y1='${legendY + 13}' x2='${legendX + 18}' y2='${legendY + 13}' stroke='#66BB6A' stroke-width='1.5'/>")
    svg.append("<text x='${legendX + 22}' y='${legendY + 16}' fill='#333' font-size='9'>Free Java</text>")
    svg.append("<line x1='${legendX + 4}' y1='${legendY + 26}' x2='${legendX + 18}' y2='${legendY + 26}' stroke='#E65100' stroke-width='1.5'/>")
    svg.append("<text x='${legendX + 22}' y='${legendY + 29}' fill='#333' font-size='9'>CPU Load</text>")
    if (showWarningLine) {
        svg.append("<line x1='${legendX + 4}' y1='${legendY + 39}' x2='${legendX + 18}' y2='${legendY + 39}' stroke='#ff9800' stroke-width='1' stroke-dasharray='4,2'/>")
        svg.append("<text x='${legendX + 22}' y='${legendY + 42}' fill='#ff9800' font-size='8'>100 / 75 MB</text>")
    }
    svg.append("</g>")

    // Hover tooltip elements
    svg.append("<rect id='${uniqueId}_hover' x='0' y='0' width='${plotW}' height='${plotH}' transform='translate(${marginLeft},${marginTop})' fill='transparent'/>")
    svg.append("<line id='${uniqueId}_vline' x1='0' y1='${marginTop}' x2='0' y2='${marginTop + plotH}' stroke='#999' stroke-width='0.5' stroke-dasharray='3,3' visibility='hidden'/>")
    svg.append("<rect id='${uniqueId}_tipbg' x='0' y='0' width='1' height='1' fill='white' stroke='#ccc' rx='3' visibility='hidden'/>")
    svg.append("<text id='${uniqueId}_tip' x='0' y='0' font-size='10' fill='#333' visibility='hidden'></text>")

    svg.append("</svg>")

    // Tooltip JS + scroll-to-end
    List tooltipData = dataPoints.collect { Map pt ->
        "{t:'${pt.time}',m:${pt.freeOS},c:${pt.cpuLoad},j:${pt.freeJava}}"
    }

    svg.append(renderMemoryChartTooltipScript(uniqueId, containerId, tooltipData.join(','), marginLeft, plotW, marginTop, svgWidth))

    svg.append("</div>")

    // Summary line
    String firstTimeStr = dataPoints[0].time
    String lastTimeStr = dataPoints[-1].time
    svg.append("<div style='font-size: 11px; color: #666; margin-top: 4px;'>${numPoints} samples from ${firstTimeStr} to ${lastTimeStr} (since last reboot)</div>")

    return svg.toString()
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

String fetchPlainText(String url, String name, int timeout = 10) {
    try {
        Map params = [
            uri: url,
            contentType: "text/plain",
            timeout: timeout
        ]
        String result = null
        httpGet(params) { resp ->
            if (resp.success) {
                result = resp.data?.text?.trim() ?: resp.data?.toString()?.trim()
            }
        }
        return result
    } catch (Exception e) {
        if (debugLogging) log.debug "Error fetching ${name}: ${e.message}"
        return null
    }
}

Integer fetchDatabaseSize() {
    String text = fetchPlainText(DATABASE_SIZE_URL, "database size")
    if (text) {
        try { return text.toInteger() } catch (Exception e) { /* ignore */ }
    }
    return null
}

Float fetchTemperature() {
    String text = fetchPlainText(INTERNAL_TEMP_URL, "internal temperature")
    if (text) {
        try { return text.toFloat() } catch (Exception e) { /* ignore */ }
    }
    return null
}

Map fetchHubAlerts() {
    Map hubData = fetchEndpoint(HUB_DATA_URL, "hub data", 10)
    if (!hubData || hubData.error) return [:]

    Map result = [
        alerts: hubData.alerts ?: [:],
        databaseSize: hubData.alerts?.databaseSize,
        spammyDevicesMessage: hubData.spammyDevicesMessage,
        devMode: hubData.baseModel?.devMode ?: false
    ]
    return result
}

Map fetchEventStateLimits() {
    String eventLimit = fetchPlainText(EVENT_LIMIT_URL, "event limit")
    String maxEventAge = fetchPlainText(MAX_EVENT_AGE_URL, "max event age")
    String maxStateAge = fetchPlainText(MAX_STATE_AGE_URL, "max state age")

    Map limits = [:]

    // Event limit comes as "Maximum event count: [11]"
    if (eventLimit) {
        java.util.regex.Matcher m = (eventLimit =~ /\[(\d+)\]/)
        if (m.find()) limits.maxEvents = m.group(1).toInteger()
    }
    if (maxEventAge) {
        try { limits.maxEventAgeDays = maxEventAge.toInteger() } catch (Exception e) { /* ignore */ }
    }
    if (maxStateAge) {
        try { limits.maxStateAgeDays = maxStateAge.toInteger() } catch (Exception e) { /* ignore */ }
    }
    return limits
}

Map fetchZigbeeMeshInfo() {
    String text = fetchPlainText(ZIGBEE_CHILD_ROUTE_URL, "zigbee child/route info", 15)
    if (!text) return [:]

    Map result = [childDevices: [], neighbors: [], routes: [], raw: text]

    String currentSection = ""
    text.split('\n').each { String line ->
        line = line.trim()
        if (!line) return

        if (line.startsWith("Child Table")) {
            currentSection = "child"
        } else if (line.startsWith("Neighbor Table")) {
            currentSection = "neighbor"
        } else if (line.startsWith("Route Table")) {
            currentSection = "route"
        } else if (currentSection == "neighbor" && line.contains("LQI:")) {
            // Parse neighbor entries like: "0xABCD (Name) LQI: 255 age: 3 inCost: 1 outCost: 1"
            Map neighbor = [raw: line]
            java.util.regex.Matcher lqiMatch = (line =~ /LQI:\s*(\d+)/)
            java.util.regex.Matcher ageMatch = (line =~ /age:\s*(\d+)/)
            java.util.regex.Matcher idMatch = (line =~ /^(0x[0-9A-Fa-f]+)/)
            if (lqiMatch.find()) neighbor.lqi = lqiMatch.group(1).toInteger()
            if (ageMatch.find()) neighbor.age = ageMatch.group(1).toInteger()
            if (idMatch.find()) neighbor.shortId = idMatch.group(1)
            result.neighbors << neighbor
        } else if (currentSection == "child") {
            result.childDevices << [raw: line]
        } else if (currentSection == "route") {
            result.routes << [raw: line]
        }
    }

    // Compute mesh stats
    if (result.neighbors) {
        List lqiValues = result.neighbors.findAll { it.lqi != null }.collect { it.lqi }
        if (lqiValues) {
            result.avgLqi = (lqiValues.sum() / lqiValues.size()).toInteger()
            result.minLqi = lqiValues.min()
            result.maxLqi = lqiValues.max()
            result.weakNeighbors = result.neighbors.findAll { it.lqi != null && it.lqi < 150 }
            result.staleNeighbors = result.neighbors.findAll { it.age != null && it.age > 6 }
        }
    }

    return result
}

String fetchZwaveVersion() {
    return fetchPlainText(ZWAVE_VERSION_URL, "Z-Wave version")
}

Map extractZwaveMeshQuality(Map zwaveData) {
    if (!zwaveData || zwaveData.error || !zwaveData.nodes) return [:]

    List nodes = []
    int totalPer = 0
    int nodesWithErrors = 0
    int totalRouteChanges = 0
    int rssiCount = 0
    int rssiSum = 0

    zwaveData.nodes.each { Map node ->
        int per = (node.per ?: 0) as int
        int neighborCount = (node.neighbors ?: 0) as int
        int routeChanges = (node.routeChanges ?: 0) as int
        String rssiStr = node.lwrRssi ?: ""
        Integer rssiVal = null
        if (rssiStr) {
            java.util.regex.Matcher m = (rssiStr =~ /(-?\d+)/)
            if (m.find()) {
                rssiVal = m.group(1).toInteger()
                rssiSum += rssiVal
                rssiCount++
            }
        }

        totalPer += per
        if (per > 0) nodesWithErrors++
        totalRouteChanges += routeChanges

        nodes << [
            nodeId: node.nodeId,
            deviceId: node.deviceId,
            name: node.deviceName ?: "Node ${node.nodeId}",
            msgCount: (node.msgCount ?: 0) as int,
            rssi: rssiVal,
            rssiStr: rssiStr,
            per: per,
            neighbors: neighborCount,
            route: node.route ?: "",
            routeChanges: routeChanges,
            state: node.nodeState ?: "Unknown",
            lastTime: node.lastTime ?: "",
            listening: node.listening ?: false,
            security: node.security ?: ""
        ]
    }

    return [
        nodes: nodes,
        nodeCount: nodes.size(),
        avgPer: nodes.size() > 0 ? (totalPer / nodes.size()).toFloat() : 0,
        nodesWithErrors: nodesWithErrors,
        totalRouteChanges: totalRouteChanges,
        avgRssi: rssiCount > 0 ? (rssiSum / rssiCount).toInteger() : null
    ]
}

List extractZwaveMessageCounts(Map zwaveData) {
    if (!zwaveData || zwaveData.error || !zwaveData.nodes) return []
    return zwaveData.nodes.collect { Map node ->
        [id: node.nodeId, deviceId: node.deviceId, name: node.deviceName ?: "Node ${node.nodeId}",
         msgCount: (node.msgCount ?: 0) as int, routeChanges: (node.routeChanges ?: 0) as int]
    }
}

List extractZigbeeMessageCounts(Map zigbeeData) {
    if (!zigbeeData || zigbeeData.error || !zigbeeData.devices) return []
    return zigbeeData.devices.collect { Map device ->
        [id: device.id, name: device.name ?: "Device ${device.id}",
         msgCount: (device.messageCount ?: 0) as int]
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

    // Flatten the device list while preserving parent device context from nested child entries
    List devicesList = []
    Closure flattenDevices
    flattenDevices = { List entries, Object parentDeviceId = null, String parentDeviceName = null ->
        entries.each { entry ->
            devicesList << [
                data: entry.data,
                key: entry.key,
                parent: entry.parent,
                child: entry.child,
                linked: entry.linked,
                parentDeviceId: parentDeviceId,
                parentDeviceName: parentDeviceName
            ]

            Map entryDevice = entry.data instanceof Map ? (Map) entry.data : null
            Object entryDeviceId = entryDevice?.id
            String entryDeviceName = entryDevice?.label ?: entryDevice?.name ?: (entryDeviceId != null ? "Device ${entryDeviceId}" : null)
            if (entry.children) {
                flattenDevices(entry.children as List, entryDeviceId, entryDeviceName)
            }
        }
    }
    flattenDevices(response.devices as List)

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
        allDevices: [],
        byType: [:],
        byProtocol: [(PROTOCOL_ZIGBEE): 0, (PROTOCOL_ZWAVE): 0, (PROTOCOL_MATTER): 0,
                     (PROTOCOL_LAN): 0, (PROTOCOL_VIRTUAL): 0, (PROTOCOL_MAKER): 0,
                     (PROTOCOL_CLOUD): 0, (PROTOCOL_HUBMESH): 0, (PROTOCOL_OTHER): 0],
        byStatus: [active: 0, inactive: 0, disabled: 0],
        // ID lists for device list links
        idsByStatus: [active: [], inactive: [], disabled: []],
        idsByProtocol: [(PROTOCOL_ZIGBEE): [], (PROTOCOL_ZWAVE): [], (PROTOCOL_MATTER): [],
                        (PROTOCOL_LAN): [], (PROTOCOL_VIRTUAL): [], (PROTOCOL_MAKER): [],
                        (PROTOCOL_CLOUD): [], (PROTOCOL_HUBMESH): [], (PROTOCOL_OTHER): []],
        parentIds: [],
        childIds: [],
        linkedIds: [],
        batteryIds: []
    ]

    long inactivityThresholdMs = now() - ((settings.inactivityDays ?: 7) * ONE_DAY_MS)
    Map radioProtocols = buildRadioProtocolMap()
    Map appLookup = buildAppLookupMap()

    // Single bulk-backed pass: analyze basic data, protocol, and parent tracking
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
                stats.idsByStatus.disabled << device.id
                stats.inactiveDevices++
            } else if (lastActivity && lastActivity > inactivityThresholdMs) {
                stats.activeDevices++
                stats.byStatus.active++
                stats.idsByStatus.active << device.id
            } else {
                stats.inactiveDevices++
                stats.byStatus.inactive++
                stats.idsByStatus.inactive << device.id
            }

            // Parent/child tracking
            if (deviceEntry.parent == true) { stats.parentDevices++; stats.parentIds << device.id }
            if (deviceEntry.child == true) { stats.childDevices++; stats.childIds << device.id }
            if (device.linked == true) { stats.linkedDevices++; stats.linkedIds << device.id }

            // Device type
            String typeName = safeToString(device.type, "Unknown")
            stats.byType[typeName] = (stats.byType[typeName] ?: 0) + 1

            // Protocol detection — radio map first, then heuristic fallback
            String protocol = PROTOCOL_OTHER
            if (device.linked == true) {
                protocol = PROTOCOL_HUBMESH
            } else if (radioProtocols.containsKey(device.id)) {
                protocol = radioProtocols[device.id]
            } else {
                protocol = determineProtocolQuick(device)
            }
            stats.byProtocol[protocol] = (stats.byProtocol[protocol] ?: 0) + 1
            if (stats.idsByProtocol[protocol] != null) stats.idsByProtocol[protocol] << device.id

            // Battery tracking — battery level is in currentStates, not a top-level field
            Integer batteryLevel = null
            List currentStates = device.currentStates ?: []
            Map batteryState = currentStates.find { it.key == "battery" }
            if (batteryState?.value != null) {
                try {
                    batteryLevel = batteryState.value.toString().toFloat().toInteger()
                    stats.batteryDevices++
                    stats.batteryIds << device.id
                    if (batteryLevel <= (settings.lowBatteryThreshold ?: 20)) {
                        stats.lowBatteryDevices << [id: device.id, name: device.name ?: "Unknown", battery: batteryLevel]
                    }
                } catch (Exception e) { /* non-numeric battery value */ }
            }

            Object parentAppId = extractParentAppId(device)
            String normalizedParentAppId = normalizeAppLookupId(parentAppId)
            String parentAppName = normalizedParentAppId ? (appLookup[normalizedParentAppId] ?: "App ${normalizedParentAppId}") : null

            // Add to full device list
            stats.allDevices << [
                id: device.id,
                name: device.name ?: "Unknown",
                label: device.label ?: device.name ?: "Unknown",
                type: typeName,
                userType: device.user ?: false,
                deviceTypeId: device.deviceTypeId,
                protocol: protocol,
                status: device.disabled ? "Disabled" : (lastActivity && lastActivity > inactivityThresholdMs ? "Active" : "Inactive"),
                lastActivity: lastActivity ? new Date(lastActivity).format("yyyy-MM-dd HH:mm") : "Never",
                battery: batteryLevel,
                isParent: deviceEntry.parent ?: false,
                isChild: deviceEntry.child ?: false,
                linked: device.linked ?: false,
                room: device.roomName ?: "",
                parentAppId: normalizedParentAppId,
                parentAppName: parentAppName,
                parentDeviceId: deviceEntry.parentDeviceId,
                parentDeviceName: deviceEntry.parentDeviceName
            ]
        } catch (Exception e) {
            log.warn "Error processing device ${deviceEntry.key}: ${e.message}"
        }
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

    // Identify platform-only apps by comparing runtime stats against appsList
    stats.platformApps = []
    try {
        Map runtimeResponse = fetchEndpoint(RUNTIME_STATS_URL, "runtime stats")
        if (runtimeResponse && !runtimeResponse.error) {
            List runtimeAppStats = runtimeResponse.appStats ?: []
            stats.runtimeTotalApps = runtimeAppStats.size()

            // Collect all IDs from appsList (including nested children)
            Set apiIds = new HashSet()
            Closure collectIds
            collectIds = { List entries ->
                entries.each { entry ->
                    if (entry.data?.id) apiIds << entry.data.id
                    if (entry.children) collectIds(entry.children as List)
                }
            }
            collectIds(appsList)

            // Platform apps = in runtime stats but not in appsList
            runtimeAppStats.each { Map app ->
                if (!apiIds.contains(app.id)) {
                    stats.platformApps << [
                        id: app.id,
                        name: app.name ?: "App ${app.id}",
                        stateSize: (app.stateSize ?: 0) as int,
                        largeState: app.largeState ?: false,
                        pctTotal: (app.pctTotal ?: 0) as float,
                        count: (app.count ?: 0) as int,
                        total: (app.total ?: 0) as long,
                        average: (app.average ?: 0) as float,
                        hubActionCount: (app.hubActionCount ?: 0) as int,
                        cloudCallCount: (app.cloudCallCount ?: 0) as int
                    ]
                }
            }
            stats.platformApps = stats.platformApps.sort { -(it.stateSize as int) }
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
    Map stateCompression = fetchStateCompression()
    Map hubAlerts = fetchHubAlerts()
    Integer databaseSize = fetchDatabaseSize()
    Float temperature = fetchTemperature()
    Map eventStateLimits = fetchEventStateLimits()

    Map health = [
        memory: memory,
        stateCompression: stateCompression,
        hubAlerts: hubAlerts,
        databaseSize: databaseSize,
        temperature: temperature,
        eventStateLimits: eventStateLimits,
        alerts: []
    ]

    // Generate alerts from observed data
    if (memory && memory.freeOSMemory < 76800) {
        health.alerts << "<span style='color: #d32f2f;'>Critical: OS memory critically low (${formatMemory(memory.freeOSMemory)}) — hub may become unresponsive</span>"
    } else if (memory && memory.freeOSMemory < 102400) {
        health.alerts << "<span style='color: #ff9800;'>Warning: Low OS memory (${formatMemory(memory.freeOSMemory)})</span>"
    }
    if (memory && memory.cpuAvg5min > 8.0) {
        health.alerts << "<span style='color: #d32f2f;'>Critical: Very high CPU load (${String.format('%.2f', memory.cpuAvg5min as float)} — 4 cores)</span>"
    } else if (memory && memory.cpuAvg5min > 4.0) {
        health.alerts << "<span style='color: #ff9800;'>Warning: Elevated CPU load (${String.format('%.2f', memory.cpuAvg5min as float)} — 4 cores fully saturated)</span>"
    }
    if (temperature != null && temperature > 77) {
        health.alerts << "<span style='color: #d32f2f;'>Critical: Hub temperature very high (${String.format('%.1f', temperature)}\u00B0C)</span>"
    } else if (temperature != null && temperature > 50) {
        health.alerts << "<span style='color: #ff9800;'>Warning: Hub temperature elevated (${String.format('%.1f', temperature)}\u00B0C)</span>"
    }

    // Incorporate platform alerts
    if (hubAlerts.alerts) {
        Map platformAlerts = hubAlerts.alerts
        ALERT_DISPLAY_NAMES.each { String key, String displayName ->
            if (platformAlerts[key] == true) {
                String severity = (key in ["hubLoadSevere", "hubZwaveCrashed", "hubHugeDatabase", "zwaveOffline", "zigbeeOffline"]) ? "#d32f2f" : "#ff9800"
                health.alerts << "<span style='color: ${severity};'>${displayName}</span>"
            }
        }
    }
    if (hubAlerts.spammyDevicesMessage) {
        health.alerts << "<span style='color: #ff9800;'>Spammy Devices: ${hubAlerts.spammyDevicesMessage}</span>"
    }

    return health
}

// ===== PROTOCOL DETECTION =====

String deviceListLink(Object count, List ids) {
    if (!ids || ids.size() == 0) return count.toString()
    String idStr = ids.collect { it.toString() }.join(',')
    return "<a href='/device/list?ids=${idStr}' target='_blank' style='color: #1A77C9; text-decoration: none;'>${count}</a>"
}

Map buildRadioProtocolMap() {
    Map protocols = [:]
    try {
        Map zigbeeData = fetchEndpoint(ZIGBEE_DETAILS_URL, "Zigbee details", 20)
        if (zigbeeData && !zigbeeData.error && zigbeeData.devices) {
            zigbeeData.devices.each { Map d -> if (d.id) protocols[d.id] = PROTOCOL_ZIGBEE }
        }
        Map zwaveData = fetchEndpoint(ZWAVE_DETAILS_URL, "Z-Wave details", 20)
        if (zwaveData && !zwaveData.error && zwaveData.nodes) {
            zwaveData.nodes.each { Map n -> if (n.deviceId) protocols[n.deviceId] = PROTOCOL_ZWAVE }
        }
        Map matterData = fetchEndpoint(MATTER_DETAILS_URL, "Matter details", 15)
        if (matterData && !matterData.error && matterData.devices) {
            matterData.devices.each { Map d -> if (d.id) protocols[d.id] = PROTOCOL_MATTER }
        }
    } catch (Exception e) {
        log.debug "Error building radio protocol map: ${e.message}"
    }
    return protocols
}

Map buildAppLookupMap() {
    Map response = fetchEndpoint(APPS_LIST_URL, "apps list", 20)
    if (!response || response.error || !response.apps) {
        return [:]
    }

    Map appLookup = [:]
    Closure visitApps
    visitApps = { List entries ->
        (entries ?: []).each { Map appEntry ->
            Map app = appEntry?.data instanceof Map ? (Map) appEntry.data : null
            String appId = normalizeAppLookupId(appEntry?.key ?: app?.id)
            if (appId) {
                appLookup[appId] = app?.label ?: app?.name ?: "App ${appId}"
            }
            if (appEntry?.children) {
                visitApps(appEntry.children as List)
            }
        }
    }
    visitApps(response.apps as List)
    return appLookup
}

String normalizeAppLookupId(Object value) {
    if (value == null) return null
    String appId = value.toString().trim()
    if (!appId) return null
    appId = appId.replaceFirst(/^APP-/, "")
    return appId
}

Object extractParentAppId(Map device) {
    if (!device) return null
    List candidateKeys = [
        "parentAppId",
        "parentApp",
        "parentInstalledAppId",
        "appId",
        "installedAppId"
    ]
    for (String key in candidateKeys) {
        Object value = device[key]
        if (value != null && safeToString(value, "")) {
            return value
        }
    }
    return null
}

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

// ===== PERFORMANCE CHECKPOINT SYSTEM =====

void createCheckpoint() {
    log.info "Creating perf checkpoint..."

    Map stats = fetchCurrentStats()
    if (!stats) {
        log.error "Failed to fetch current stats"
        return
    }

    Map resources = fetchSystemResources()

    // Capture radio message counts for Z-Wave and Zigbee
    Map zwaveData = fetchEndpoint(ZWAVE_DETAILS_URL, "Z-Wave details", 20)
    Map zigbeeData = fetchEndpoint(ZIGBEE_DETAILS_URL, "Zigbee details", 20)
    List zwaveRadio = extractZwaveMessageCounts(zwaveData)
    List zigbeeRadio = extractZigbeeMessageCounts(zigbeeData)

    Map checkpoint = [
        timestamp: new Date().format("yyyy-MM-dd HH:mm:ss"),
        timestampMs: now(),
        stats: stats,
        resources: resources,
        radioStats: [
            zwave: zwaveRadio,
            zigbee: zigbeeRadio
        ]
    ]

    List checkpoints = loadCheckpoints()
    checkpoints.add(0, checkpoint)

    int maxCp = (settings.maxCheckpoints ?: 10) as int
    if (checkpoints.size() > maxCp) {
        checkpoints = checkpoints.take(maxCp)
    }

    saveCheckpoints(checkpoints)
    log.info "Perf checkpoint created successfully"
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

    // Fetch current radio stats for "now" comparison
    Map zwaveData = fetchEndpoint(ZWAVE_DETAILS_URL, "Z-Wave details", 20)
    Map zigbeeData = fetchEndpoint(ZIGBEE_DETAILS_URL, "Zigbee details", 20)
    currentStats.radioStats = [
        zwave: extractZwaveMessageCounts(zwaveData),
        zigbee: extractZigbeeMessageCounts(zigbeeData)
    ]

    if (baseline == "startup") {
        Map zeroBaseline = buildZeroBaseline(currentStats, currentResources)
        savePerformanceComparisonPayload(buildPerformanceComparisonPayload(
            zeroBaseline,
            currentStats,
            "Startup (0:00:00)",
            "Now (${new Date().format('yyyy-MM-dd HH:mm:ss')})"
        ))
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
        baselineStats.radioStats = baselineCp.radioStats

        savePerformanceComparisonPayload(buildPerformanceComparisonPayload(
            baselineStats,
            currentStats,
            baselineCp.timestamp,
            "Now (${new Date().format('yyyy-MM-dd HH:mm:ss')})"
        ))
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
    baselineStats.radioStats = baselineCp.radioStats
    Map checkpointStats = checkpointCp.stats
    checkpointStats.resources = checkpointCp.resources
    checkpointStats.radioStats = checkpointCp.radioStats

    savePerformanceComparisonPayload(buildPerformanceComparisonPayload(
        baselineStats,
        checkpointStats,
        baselineCp.timestamp,
        checkpointCp.timestamp
    ))
}

void performComparisonSinceStartup(int checkpointIdx) {
    List checkpoints = loadCheckpoints()

    if (checkpointIdx >= checkpoints.size()) {
        log.error "Invalid checkpoint index"
        return
    }

    Map checkpointCp = checkpoints[checkpointIdx]
    Map checkpointStats = checkpointCp.stats
    checkpointStats.resources = checkpointCp.resources
    checkpointStats.radioStats = checkpointCp.radioStats
    Map zeroBaseline = buildZeroBaseline(checkpointStats, checkpointCp.resources)

    savePerformanceComparisonPayload(buildPerformanceComparisonPayload(
        zeroBaseline,
        checkpointStats,
        "Startup (0:00:00)",
        checkpointCp.timestamp
    ))
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
        },
        radioStats: [
            zwave: (stats.radioStats?.zwave ?: []).collect { node ->
                [id: node.id, deviceId: node.deviceId, name: node.name, msgCount: 0, routeChanges: 0]
            },
            zigbee: (stats.radioStats?.zigbee ?: []).collect { dev ->
                [id: dev.id, name: dev.name, msgCount: 0]
            }
        ]
    ]
}

Map buildPerformanceComparisonPayload(Map baselineStats, Map checkpointStats, String baselineLabel, String checkpointLabel) {
    return [
        generatedAt     : new Date().format("yyyy-MM-dd HH:mm:ss"),
        baselineLabel   : baselineLabel,
        checkpointLabel : checkpointLabel,
        baselineStats   : baselineStats ?: [:],
        checkpointStats : checkpointStats ?: [:]
    ]
}

String renderPerformanceComparisonPayload(Map payload) {
    if (!payload) return null
    return generateComparison(
        payload.baselineStats ?: [:],
        payload.checkpointStats ?: [:],
        payload.baselineLabel ?: "Baseline",
        payload.checkpointLabel ?: "Current"
    )
}

String buildSnapshotDiffHtml() {
    Map payload = loadSnapshotDiffPayload()
    if (!payload) return null
    return generateSnapshotDiff(payload.older ?: [:], payload.newer ?: [:])
}

String generateComparison(Map baselineStats, Map checkpointStats, String baselineLabel, String checkpointLabel) {
    StringBuilder sb = new StringBuilder()
    sb.append("<b>Comparison: Activity Since Baseline</b><br>")
    sb.append("<b>Baseline:</b> ${baselineLabel}<br>")
    sb.append("<b>Current:</b> ${checkpointLabel}<br><br>")

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
        sb.append("&nbsp;&nbsp;CPU Load Avg (5m): ${String.format('%.2f', (baselineResources.cpuAvg5min ?: 0) as float)} -> ${String.format('%.2f', (checkpointResources.cpuAvg5min ?: 0) as float)} (<span style='color: ${cpuColor};'>${cpuSign}${String.format('%.2f', cpuDelta)}</span>)<br>")

        int freeJavaDelta = (checkpointResources.freeJavaMemory ?: 0) - (baselineResources.freeJavaMemory ?: 0)
        String freeJavaSign = freeJavaDelta > 0 ? "+" : ""
        String freeJavaColor = freeJavaDelta < 0 ? "#d32f2f" : "#388e3c"
        sb.append("&nbsp;&nbsp;Free Java Memory: ${formatMemory(baselineResources.freeJavaMemory ?: 0)} -> ${formatMemory(checkpointResources.freeJavaMemory ?: 0)} (<span style='color: ${freeJavaColor};'>${freeJavaSign}${formatMemory(freeJavaDelta)}</span>)<br>")

        sb.append("<br>")
    }

    // Device activity
    int devCount = countComparisonRows(baselineStats.deviceStats as List, checkpointStats.deviceStats as List)
    String devOpen = devCount <= 10 ? " open" : ""
    sb.append("<details${devOpen}><summary style='cursor: pointer; font-weight: bold; margin: 8px 0;'>Device Activity (${devCount} active) — Total Runtime: ${formatDuration(devTimeDiff)}</summary>")
    sb.append("<i>Click column headers to sort. % Busy is calculated for the selected period.</i><br><br>")
    sb.append(generateComparisonTable(baselineStats.deviceStats as List, checkpointStats.deviceStats as List, "device", (devTimeDiff * 1000L)))
    sb.append("</details><br>")

    // App activity
    int appCount = countComparisonRows(baselineStats.appStats as List, checkpointStats.appStats as List)
    String appOpen = appCount <= 10 ? " open" : ""
    sb.append("<details${appOpen}><summary style='cursor: pointer; font-weight: bold; margin: 8px 0;'>App Activity (${appCount} active) — Total Runtime: ${formatDuration(appTimeDiff)}</summary>")
    sb.append("<i>Click column headers to sort. % Busy is calculated for the selected period.</i><br><br>")
    sb.append(generateComparisonTable(baselineStats.appStats as List, checkpointStats.appStats as List, "app", (appTimeDiff * 1000L)))
    sb.append("</details>")

    // Radio message activity
    Map baselineRadio = baselineStats.radioStats ?: [:]
    Map checkpointRadio = checkpointStats.radioStats ?: [:]
    long periodMinutes = uptimeDiff > 0 ? Math.max(1, (uptimeDiff / 60) as long) : 1

    if (baselineRadio.zwave || checkpointRadio.zwave || baselineRadio.zigbee || checkpointRadio.zigbee) {
        int zwRadioCount = countRadioRows(checkpointRadio.zwave ?: [], baselineRadio.zwave ?: [])
        int zbRadioCount = countRadioRows(checkpointRadio.zigbee ?: [], baselineRadio.zigbee ?: [])
        int totalRadioCount = zwRadioCount + zbRadioCount
        String radioOpen = totalRadioCount <= 10 ? " open" : ""
        sb.append("<br><details${radioOpen}><summary style='cursor: pointer; font-weight: bold; margin: 8px 0;'>Radio Message Activity (${totalRadioCount} active devices)</summary>")
        sb.append("<i>Message counts reset on hub reboot. If counts went backwards, a reboot occurred between the two points in time.</i><br><br>")

        sb.append(generateRadioComparisonTable(baselineRadio.zwave ?: [], checkpointRadio.zwave ?: [], "zwave", periodMinutes))
        sb.append("<br><br>")
        sb.append(generateRadioComparisonTable(baselineRadio.zigbee ?: [], checkpointRadio.zigbee ?: [], "zigbee", periodMinutes))

        // Chatty device detection
        float threshold = (settings.chattyDeviceThreshold ?: 10) as float
        List chattyDevices = []
        [baselineRadio.zwave ?: [], baselineRadio.zigbee ?: []].eachWithIndex { List baseList, int protoIdx ->
            String proto = protoIdx == 0 ? "Z-Wave" : "Zigbee"
            List cpList = protoIdx == 0 ? (checkpointRadio.zwave ?: []) : (checkpointRadio.zigbee ?: [])
            Map baseMap = baseList.collectEntries { [(it.id): it] }
            cpList.each { Map cpItem ->
                Map blItem = baseMap[cpItem.id]
                int delta = ((cpItem.msgCount ?: 0) as int) - ((blItem?.msgCount ?: 0) as int)
                if (delta > 0) {
                    float msgsPerMin = periodMinutes > 0 ? (delta / (float) periodMinutes) : 0
                    if (msgsPerMin >= threshold) {
                        chattyDevices << [name: cpItem.name, deviceId: cpItem.deviceId ?: cpItem.id, protocol: proto, msgsPerMin: msgsPerMin, total: delta]
                    }
                }
            }
        }

        if (chattyDevices) {
            chattyDevices.sort { -it.msgsPerMin }
            sb.append("<br><br><b style='color: #d32f2f;'>\u26A0 Chatty Devices Detected (>${threshold.intValue()} msgs/min):</b><br>")
            chattyDevices.each { Map dev ->
                String chattyNameHtml = dev.deviceId ? "<a href='/device/edit/${dev.deviceId}' target='_blank' style='color: #d32f2f; text-decoration: underline;'>${dev.name}</a>" : dev.name
                sb.append("&nbsp;&nbsp;<span style='color: #d32f2f;'><b>${chattyNameHtml}</b> (${dev.protocol}) — ${String.format('%.1f', dev.msgsPerMin)} msgs/min (${dev.total} total)</span><br>")
            }
            sb.append("<i>Chatty devices can degrade hub performance. Check if device polling intervals are too aggressive or if the device is malfunctioning.</i><br>")
        }
        sb.append("</details>")
    }

    return sb.toString()
}

int countComparisonRows(List baselineItems, List checkpointItems) {
    return buildComparisonData(baselineItems, checkpointItems, 0L).size()
}

int countRadioRows(List checkpointItems, List baselineItems) {
    if (!checkpointItems) return 0
    Map blMap = (baselineItems ?: []).collectEntries { [(it.id): it] }
    int count = 0
    checkpointItems.each { Map cpItem ->
        int cpMsg = (cpItem.msgCount ?: 0) as int
        int blMsg = (blMap[cpItem.id]?.msgCount ?: 0) as int
        if (cpMsg - blMsg > 0) count++
    }
    return count
}

String generateComparisonTable(List baselineItems, List checkpointItems, String type, long overallDeltaMs) {
    if ((!baselineItems || baselineItems.size() == 0) && (!checkpointItems || checkpointItems.size() == 0)) {
        return "No ${type} data available"
    }

    List comparisonData = buildComparisonData(baselineItems, checkpointItems, overallDeltaMs)

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

Map normalizeComparisonItem(Map item, Object fallbackId = null) {
    Map source = item ?: [:]
    Object itemId = source.id != null ? source.id : fallbackId
    return [
        id: itemId,
        name: source.name ?: (itemId != null ? "${itemId}" : "Unknown"),
        total: (source.total ?: 0) as long,
        count: (source.count ?: 0) as long,
        stateSize: (source.stateSize ?: 0) as int,
        hubActionCount: (source.hubActionCount ?: 0) as long,
        cloudCallCount: (source.cloudCallCount ?: 0) as long
    ]
}

Map buildComparisonItemMap(List items) {
    Map result = [:]
    (items ?: []).each { Map item ->
        if (item?.id != null) {
            result[item.id] = normalizeComparisonItem(item, item.id)
        }
    }
    return result
}

List buildComparisonData(List baselineItems, List checkpointItems, long overallDeltaMs) {
    Map baselineMap = buildComparisonItemMap(baselineItems)
    Map checkpointMap = buildComparisonItemMap(checkpointItems)

    List comparisonData = []
    checkpointMap.each { Object itemId, Map checkpointItem ->
        Map baselineItem = baselineMap[itemId] ?: normalizeComparisonItem([id: itemId, name: checkpointItem.name], itemId)

        long totalMs = checkpointItem.total - baselineItem.total
        long count = checkpointItem.count - baselineItem.count
        int stateSize = checkpointItem.stateSize
        long hubActions = checkpointItem.hubActionCount - baselineItem.hubActionCount
        long cloudCalls = checkpointItem.cloudCallCount - baselineItem.cloudCallCount
        float avgMs = count > 0 ? (totalMs / count) as float : 0
        float periodPctBusy = overallDeltaMs > 0 ? ((totalMs / (float) overallDeltaMs) * 100) : 0

        if (totalMs != 0 || count != 0 || stateSize != 0 || hubActions != 0 || cloudCalls != 0) {
            comparisonData << [
                name: checkpointItem.name ?: baselineItem.name,
                id: itemId,
                totalMs: totalMs,
                periodPctBusy: periodPctBusy,
                count: count,
                avgMs: avgMs,
                stateSize: stateSize,
                hubActions: hubActions,
                cloudCalls: cloudCalls
            ]
        }
    }

    return comparisonData
}

String generateRadioComparisonTable(List baselineItems, List checkpointItems, String protocol, long periodMinutes) {
    String label = protocol == "zwave" ? "Z-Wave" : "Zigbee"
    if (!checkpointItems || checkpointItems.size() == 0) {
        return "<b>${label} Messages:</b> No data"
    }

    Map baselineMap = (baselineItems ?: []).collectEntries { [(it.id): it] }

    List comparisonData = []
    boolean rebootDetected = false

    checkpointItems.each { Map cpItem ->
        Map blItem = baselineMap[cpItem.id]
        int cpMsgCount = (cpItem.msgCount ?: 0) as int
        int blMsgCount = (blItem?.msgCount ?: 0) as int
        int delta = cpMsgCount - blMsgCount

        if (delta < 0) {
            rebootDetected = true
            return
        }

        float msgsPerMin = periodMinutes > 0 ? (delta / (float) periodMinutes) : 0

        if (protocol == "zwave") {
            int cpRouteChanges = (cpItem.routeChanges ?: 0) as int
            int blRouteChanges = (blItem?.routeChanges ?: 0) as int
            int routeDelta = cpRouteChanges - blRouteChanges
            if (routeDelta < 0) routeDelta = 0

            if (delta > 0 || routeDelta > 0) {
                comparisonData << [
                    name: cpItem.name, id: cpItem.deviceId ?: cpItem.id,
                    messages: delta, msgsPerMin: msgsPerMin,
                    routeChanges: routeDelta
                ]
            }
        } else {
            if (delta > 0) {
                comparisonData << [
                    name: cpItem.name, id: cpItem.id,
                    messages: delta, msgsPerMin: msgsPerMin
                ]
            }
        }
    }

    if (rebootDetected) {
        return "<b>${label} Messages:</b> <i>Reboot detected — message counts reset, comparison not available.</i>"
    }

    if (comparisonData.size() == 0) {
        return "<b>${label} Messages:</b> No activity detected"
    }

    // Sort by messages descending
    comparisonData.sort { -it.messages }

    String tableId = "${protocol}RadioComp"
    List columns = [
        [label: "Device", field: "name", type: "string"],
        [label: "Messages", field: "messages", type: "number"],
        [label: "Msgs/min", field: "msgsPerMin", type: "number"]
    ]
    if (protocol == "zwave") {
        columns << [label: "Route Changes", field: "routeChanges", type: "number"]
    }

    // Calculate average for color coding
    float avgMsgs = comparisonData.sum { it.messages } / comparisonData.size()

    List rows = comparisonData.collect { Map item ->
        String rcNameHtml = item.id ? "<a href='/device/edit/${item.id}' target='_blank' style='color: #1A77C9; text-decoration: none;'>${escapeHtml(item.name as String)}</a>" : escapeHtml(item.name as String)
        Map row = [
            name: rcNameHtml,
            _nameSort: item.name,
            messages: item.messages,
            msgsPerMin: String.format('%.1f', (item.msgsPerMin as Number).floatValue()),
            _msgsPerMinSort: item.msgsPerMin
        ]
        if (protocol == "zwave") {
            row.routeChanges = item.routeChanges
            if (item.routeChanges > 5) row._routeChangesColor = "#d32f2f"
            else if (item.routeChanges > 0) row._routeChangesColor = "#ff9800"
        }
        // Highlight devices significantly above average
        if (item.messages > avgMsgs * 3) row._messagesColor = "#d32f2f"
        else if (item.messages > avgMsgs * 2) row._messagesColor = "#ff9800"
        return row
    }

    StringBuilder sb = new StringBuilder()
    sb.append("<b>${label} Messages (${comparisonData.size()} active devices):</b><br>")
    sb.append(generateSortableTable(tableId, columns, rows))
    return sb.toString()
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
    clearPerformanceComparison()
    log.info "All perf checkpoints cleared"
}

// ===== SNAPSHOT SYSTEM =====

void createSnapshot() {
    log.info "Creating config snapshot..."

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
    log.info "Config snapshot created successfully (${snapshots.size()} total)"
}

void executeSnapshotDiff() {
    if (diffOlder == null || diffNewer == null || diffOlder == diffNewer) {
        log.warn "Invalid snapshot selection for diff"
        return
    }

    List snapshots = loadSnapshots()
    int olderIdx = parseSnapshotSelectionIndex(diffOlder, "diff_")
    if (olderIdx < 0) {
        log.error "Invalid older snapshot selection"
        return
    }

    Map newer
    if (diffNewer == "now") {
        createSnapshot()
        snapshots = loadSnapshots()
        newer = snapshots[0]  // createSnapshot adds at index 0
    } else {
        int newerIdx = parseSnapshotSelectionIndex(diffNewer, "diff_")
        if (newerIdx < 0) {
            log.error "Invalid newer snapshot selection"
            return
        }
        if (newerIdx >= snapshots.size()) {
            log.error "Invalid snapshot index"
            return
        }
        newer = snapshots[newerIdx]
    }

    if (olderIdx >= snapshots.size()) {
        log.error "Invalid snapshot index"
        return
    }
    Map older = snapshots[olderIdx]

    // Ensure older is actually older by timestamp
    if ((older.timestampMs ?: 0) > (newer.timestampMs ?: 0)) {
        Map temp = older
        older = newer
        newer = temp
    }

    saveSnapshotDiffPayload([
        generatedAt: new Date().format("yyyy-MM-dd HH:mm:ss"),
        older      : older,
        newer      : newer
    ])
}

String generateSnapshotDiff(Map older, Map newer) {
    StringBuilder sb = new StringBuilder()
    sb.append("<b>Config Snapshot Comparison</b><br>")
    sb.append("<b>Older:</b> ${older.timestamp}<br>")
    sb.append("<b>Newer:</b> ${newer.timestamp}<br><br>")

    // Firmware change
    String olderFw = older.hubInfo?.firmware ?: "Unknown"
    String newerFw = newer.hubInfo?.firmware ?: "Unknown"
    if (olderFw != newerFw) {
        sb.append("<b>Firmware:</b> <span style='color: #1A77C9;'>${olderFw} \u2192 ${newerFw}</span><br><br>")
    } else {
        sb.append("<b>Firmware:</b> ${newerFw}<br><br>")
    }

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
            if (changes) changed << [id: dev.id, name: dev.name, changes: changes.join(", ")]
        }
    }

    sb.append("<b>Device Changes:</b><br>")
    int deviceCountDelta = (newer.devices?.totalDevices ?: 0) - (older.devices?.totalDevices ?: 0)
    String deviceSign = deviceCountDelta > 0 ? "+" : ""
    sb.append("&nbsp;&nbsp;Total: ${older.devices?.totalDevices ?: 0} -> ${newer.devices?.totalDevices ?: 0} (${deviceSign}${deviceCountDelta})<br>")

    if (added) {
        sb.append("<br>&nbsp;&nbsp;<span style='color: #388e3c;'><b>Added (${added.size()}):</b></span><br>")
        added.each { sb.append("&nbsp;&nbsp;&nbsp;&nbsp;+ <a href='/device/edit/${it.id}' target='_blank' style='color: #388e3c;'>${it.name}</a> (${PROTOCOL_DISPLAY[it.protocol] ?: it.protocol})<br>") }
    }
    if (removed) {
        sb.append("<br>&nbsp;&nbsp;<span style='color: #d32f2f;'><b>Removed (${removed.size()}):</b></span><br>")
        removed.each { sb.append("&nbsp;&nbsp;&nbsp;&nbsp;- <a href='/device/edit/${it.id}' target='_blank' style='color: #d32f2f;'>${it.name}</a> (${PROTOCOL_DISPLAY[it.protocol] ?: it.protocol})<br>") }
    }
    if (changed) {
        sb.append("<br>&nbsp;&nbsp;<span style='color: #ff9800;'><b>Changed (${changed.size()}):</b></span><br>")
        changed.each { sb.append("&nbsp;&nbsp;&nbsp;&nbsp;~ <a href='/device/edit/${it.id}' target='_blank' style='color: #ff9800;'>${it.name}</a>: ${it.changes}<br>") }
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

String renderSnapshotView(Map snap) {
    StringBuilder sb = new StringBuilder()
    Map snapshotHealth = normalizeSnapshotSystemHealth(snap.systemHealth)

    // Header
    Map info = snap.hubInfo ?: [:]
    sb.append("<b>Config Snapshot — ${snap.timestamp}</b><br>")
    sb.append("<b>Hub:</b> ${info.name ?: 'Unknown'} | Firmware: ${info.firmware ?: 'Unknown'} | Hardware: ${info.hardware ?: 'Unknown'}<br><br>")

    // System health
    if (snapshotHealth.memory) {
        Map mem = snapshotHealth.memory
        sb.append("<b>System Resources:</b><br>")
        if (mem.freeOSMemory) sb.append("&nbsp;&nbsp;Free OS Memory: ${formatMemory(mem.freeOSMemory as int)}<br>")
        if (mem.cpuAvg5min != null) sb.append("&nbsp;&nbsp;CPU Load Avg (5m): ${String.format('%.2f', (mem.cpuAvg5min ?: 0) as float)}<br>")
        if (mem.freeJavaMemory) sb.append("&nbsp;&nbsp;Free Java Memory: ${formatMemory(mem.freeJavaMemory as int)}<br>")
        sb.append("<br>")
    }
    if (snapshotHealth.databaseSize != null) {
        sb.append("<b>Database:</b> ${snapshotHealth.databaseSize} MB<br><br>")
    }

    // Device summary
    Map devs = snap.devices ?: [:]
    sb.append("<b>Devices:</b> ${devs.totalDevices ?: 0} total<br>")
    sb.append("&nbsp;&nbsp;Active: ${devs.activeDevices ?: 0} | Inactive: ${devs.inactiveDevices ?: 0} | Disabled: ${devs.disabledDevices ?: 0}<br>")

    Map byProto = devs.byProtocol ?: [:]
    List protoItems = []
    byProto.each { String key, val ->
        if ((val as int) > 0) protoItems << "${PROTOCOL_DISPLAY[key] ?: key}: ${val}"
    }
    if (protoItems) sb.append("&nbsp;&nbsp;${protoItems.join(' | ')}<br>")
    sb.append("<br>")

    // Device list
    List allDevs = devs.allDevices ?: []
    if (allDevs) {
        sb.append("<b>Device List (${allDevs.size()}):</b><br>")
        sb.append("<table style='width:100%; border-collapse: collapse; font-size: 11px;'>")
        sb.append("<thead><tr style='background-color: #1A77C9; color: white;'>")
        sb.append("<th style='padding: 4px 8px; text-align: left; border: 1px solid #ddd;'>Name</th>")
        sb.append("<th style='padding: 4px 8px; text-align: left; border: 1px solid #ddd;'>Type</th>")
        sb.append("<th style='padding: 4px 8px; text-align: center; border: 1px solid #ddd;'>Protocol</th>")
        sb.append("<th style='padding: 4px 8px; text-align: center; border: 1px solid #ddd;'>Status</th>")
        sb.append("</tr></thead><tbody>")
        allDevs.sort { (it.name ?: "").toString().toLowerCase() }.eachWithIndex { Map dev, int idx ->
            String rowBg = idx % 2 == 0 ? "#f9f9f9" : "#ffffff"
            String statusColor = dev.status == "Active" ? "#388e3c" : (dev.status == "Disabled" ? "#d32f2f" : "#ff9800")
            String nameLink = "<a href='/device/edit/${dev.id}' target='_blank' style='color: #1A77C9; text-decoration: none;'>${escapeHtml(dev.name as String)}</a>"
            sb.append("<tr style='background-color: ${rowBg};'>")
            sb.append("<td style='padding: 4px 8px; border: 1px solid #ddd;'>${nameLink}</td>")
            sb.append("<td style='padding: 4px 8px; border: 1px solid #ddd;'>${dev.type ?: ''}</td>")
            sb.append("<td style='padding: 4px 8px; text-align: center; border: 1px solid #ddd;'>${PROTOCOL_DISPLAY[dev.protocol] ?: dev.protocol ?: ''}</td>")
            sb.append("<td style='padding: 4px 8px; text-align: center; border: 1px solid #ddd;'><span style='color: ${statusColor};'>${dev.status ?: ''}</span></td>")
            sb.append("</tr>")
        }
        sb.append("</tbody></table><br>")
    }

    // App summary
    Map apps = snap.apps ?: [:]
    sb.append("<b>Apps:</b> ${apps.totalApps ?: 0} total (System: ${apps.builtInApps ?: 0} | User: ${apps.userApps ?: 0})<br>")

    // App type breakdown
    Map byNs = apps.byNamespace ?: [:]
    if (byNs) {
        sb.append("<br><b>App Types (${byNs.size()}):</b><br>")
        sb.append("<table style='width:100%; border-collapse: collapse; font-size: 11px;'>")
        sb.append("<thead><tr style='background-color: #1A77C9; color: white;'>")
        sb.append("<th style='padding: 4px 8px; text-align: left; border: 1px solid #ddd;'>App Type</th>")
        sb.append("<th style='padding: 4px 8px; text-align: center; border: 1px solid #ddd;'>Instances</th>")
        sb.append("</tr></thead><tbody>")
        byNs.sort { -it.value }.eachWithIndex { entry, int idx ->
            String rowBg = idx % 2 == 0 ? "#f9f9f9" : "#ffffff"
            sb.append("<tr style='background-color: ${rowBg};'>")
            sb.append("<td style='padding: 4px 8px; border: 1px solid #ddd;'>${escapeHtml(entry.key as String)}</td>")
            sb.append("<td style='padding: 4px 8px; text-align: center; border: 1px solid #ddd;'>${entry.value}</td>")
            sb.append("</tr>")
        }
        sb.append("</tbody></table><br>")
    }

    // User app instances
    List userApps = apps.userAppsList ?: []
    if (userApps) {
        sb.append("<b>User App Instances (${userApps.size()}):</b><br>")
        sb.append("<table style='width:100%; border-collapse: collapse; font-size: 11px;'>")
        sb.append("<thead><tr style='background-color: #1A77C9; color: white;'>")
        sb.append("<th style='padding: 4px 8px; text-align: left; border: 1px solid #ddd;'>Label</th>")
        sb.append("<th style='padding: 4px 8px; text-align: left; border: 1px solid #ddd;'>Type</th>")
        sb.append("</tr></thead><tbody>")
        userApps.sort { (it.label ?: it.name ?: "").toString().toLowerCase() }.eachWithIndex { Map app, int idx ->
            String rowBg = idx % 2 == 0 ? "#f9f9f9" : "#ffffff"
            String appId = (app.id ?: "").toString().replace("APP-", "")
            String nameLink = appId ? "<a href='/installedapp/configure/${appId}' target='_blank' style='color: #1A77C9; text-decoration: none;'>${escapeHtml((app.label ?: app.name) as String)}</a>" : escapeHtml((app.label ?: app.name) as String)
            sb.append("<tr style='background-color: ${rowBg};'>")
            sb.append("<td style='padding: 4px 8px; border: 1px solid #ddd;'>${nameLink}</td>")
            sb.append("<td style='padding: 4px 8px; border: 1px solid #ddd;'>${escapeHtml(app.name as String)}</td>")
            sb.append("</tr>")
        }
        sb.append("</tbody></table><br>")
    }

    // Platform apps
    List platformApps = apps.platformApps ?: []
    if (platformApps) {
        sb.append("<b>Platform Apps (${platformApps.size()}):</b><br>")
        sb.append("<table style='width:100%; border-collapse: collapse; font-size: 11px;'>")
        sb.append("<thead><tr style='background-color: #1A77C9; color: white;'>")
        sb.append("<th style='padding: 4px 8px; text-align: left; border: 1px solid #ddd;'>Name</th>")
        sb.append("<th style='padding: 4px 8px; text-align: center; border: 1px solid #ddd;'>State Size</th>")
        sb.append("</tr></thead><tbody>")
        platformApps.sort { (it.name ?: "").toString().toLowerCase() }.eachWithIndex { Map app, int idx ->
            String rowBg = idx % 2 == 0 ? "#f9f9f9" : "#ffffff"
            int stateSize = (app.stateSize ?: 0) as int
            String stateSizeColor = stateSize > 10000 ? "#d32f2f" : (stateSize > 5000 ? "#ff9800" : "")
            String stateSizeHtml = stateSizeColor ? "<span style='color: ${stateSizeColor};'>${stateSize}</span>" : "${stateSize}"
            String appName = escapeHtml(app.name as String)
            String appNameHtml = app.id ? "<a href='/installedapp/status/${app.id}' target='_blank' style='color: #1A77C9; text-decoration: none;'>${appName}</a>" : appName
            sb.append("<tr style='background-color: ${rowBg};'>")
            sb.append("<td style='padding: 4px 8px; border: 1px solid #ddd;'>${appNameHtml}</td>")
            sb.append("<td style='padding: 4px 8px; text-align: center; border: 1px solid #ddd;'>${stateSizeHtml}</td>")
            sb.append("</tr>")
        }
        sb.append("</tbody></table><br>")
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
    clearSnapshotDiff()
    log.info "All config snapshots cleared"
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
${FULL_REPORT_STYLES}
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

        <h3>Resources</h3>
        <table>
            <tr><th>Resource</th><th>Value</th></tr>
            ${systemHealth.memory ? """
            <tr><td>Free OS Memory</td><td>${formatMemory(systemHealth.memory.freeOSMemory ?: 0)}</td></tr>
            <tr><td>CPU Load Avg (5m)</td><td>${String.format('%.2f', (systemHealth.memory.cpuAvg5min ?: 0) as float)}</td></tr>
            <tr><td>Free Java Memory</td><td>${formatMemory(systemHealth.memory.freeJavaMemory ?: 0)}</td></tr>
            """ : '<tr><td colspan="2">Memory data unavailable</td></tr>'}
            ${systemHealth.temperature != null ? "<tr><td>Hub Temperature</td><td>${String.format('%.1f', systemHealth.temperature)}\u00B0C</td></tr>" : ""}
            ${systemHealth.databaseSize != null ? "<tr><td>Database Size</td><td>${systemHealth.databaseSize} MB</td></tr>" : ""}
        </table>

        <div class="footer">
            <p>Generated by Hub Diagnostics v${APP_VERSION}</p>
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
    sb.append("<th>Name</th><th>Type</th><th>Protocol</th><th>Room</th><th>Status</th>")
    sb.append("<th>Last Activity</th><th>Battery</th><th>Parent</th>")
    sb.append("</tr></thead><tbody>")

    List sorted = allDevices.sort { it.name }
    sorted.each { Map device ->
        String statusColor = device.status == "Disabled" ? "#d32f2f" : (device.status == "Active" ? "#388e3c" : "#ff9800")
        String protocolDisplay = PROTOCOL_DISPLAY[device.protocol] ?: (device.protocol ?: "").toString().capitalize()
        String parentDisplay = device.parentAppName ?: device.parentDeviceName ?: "-"

        sb.append("<tr>")
        sb.append("<td><strong><a href='/device/edit/${device.id}' target='_blank' style='color: #1A77C9; text-decoration: none;'>${device.name}</a></strong></td>")
        sb.append("<td style='font-size: 0.9em;'>${device.type}</td>")
        sb.append("<td>${protocolDisplay}</td>")
        sb.append("<td>${device.room ?: '-'}</td>")
        sb.append("<td style='color: ${statusColor};'><strong>${device.status}</strong></td>")
        sb.append("<td>${device.lastActivity ?: 'Never'}</td>")
        sb.append("<td>${device.battery != null ? device.battery + '%' : '-'}</td>")
        sb.append("<td>${parentDisplay}</td>")
        sb.append("</tr>")
    }

    sb.append("</tbody></table>")
    return sb.toString()
}

// ===== SHARED TABLE GENERATION =====

String renderSortableTableScript(String uniqueId) {
    return SORTABLE_TABLE_SCRIPT_TEMPLATE.replace("__UNIQUE_ID__", uniqueId)
}

String renderMemoryChartTooltipScript(String uniqueId, String containerId, String tooltipData, int marginLeft, int plotW, int marginTop, int svgWidth) {
    return MEMORY_CHART_TOOLTIP_SCRIPT_TEMPLATE
        .replace("__TOOLTIP_DATA__", tooltipData)
        .replace("__SVG_ID__", uniqueId)
        .replace("__CONTAINER_ID__", containerId)
        .replace("__MARGIN_LEFT__", "${marginLeft}")
        .replace("__PLOT_WIDTH__", "${plotW}")
        .replace("__MARGIN_TOP__", "${marginTop}")
        .replace("__SVG_WIDTH__", "${svgWidth}")
}

String generateSortableTable(String tableId, List columns, List rows) {
    if (!rows || rows.size() == 0) {
        return "No data available"
    }

    String uniqueId = "${tableId}_${now()}"

    StringBuilder sb = new StringBuilder()

    sb.append(renderSortableTableScript(uniqueId))

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

        // Lightweight health: memory, CPU, hub alerts
        Map resources = fetchSystemResources()
        Map hubAlerts = fetchHubAlerts()
        Float temperature = fetchTemperature()
        Integer databaseSize = fetchDatabaseSize()

        String resourceLine = ""
        if (resources) {
            String memColor = (resources.freeOSMemory ?: 0) < 76800 ? "#d32f2f" : ((resources.freeOSMemory ?: 0) < 102400 ? "#ff9800" : "#388e3c")
            String cpuColor = (resources.cpuAvg5min ?: 0) > 8.0 ? "#d32f2f" : ((resources.cpuAvg5min ?: 0) > 4.0 ? "#ff9800" : "#388e3c")
            resourceLine = "\n<b>Resources:</b> <span style='color: ${memColor};'>${formatMemory(resources.freeOSMemory ?: 0)} free</span> | CPU Load: <span style='color: ${cpuColor};'>${String.format('%.2f', (resources.cpuAvg5min ?: 0) as float)}</span>"
            if (temperature != null) {
                String tempColor = temperature > 77 ? "#d32f2f" : (temperature > 50 ? "#ff9800" : "#388e3c")
                resourceLine += " | Temp: <span style='color: ${tempColor};'>${String.format('%.1f', temperature)}\u00B0C</span>"
            }
            if (databaseSize != null) {
                resourceLine += " | DB: ${databaseSize} MB"
            }
        }

        // Count active platform alerts
        int alertCount = 0
        if (hubAlerts.alerts) {
            ALERT_DISPLAY_NAMES.each { String key, String name ->
                if (hubAlerts.alerts[key] == true) alertCount++
            }
        }
        String alertLine = alertCount > 0 ? "\n<span style='color: #d32f2f;'><b>Platform Alerts:</b> ${alertCount} active — check System Health for details</span>" : ""

        Map idsByP = deviceStats.idsByProtocol ?: [:]
        Map idsByS = deviceStats.idsByStatus ?: [:]

        return """<b>Hub Diagnostics Summary</b>
<b>Hub:</b> ${hubInfo.name} | Firmware: ${hubInfo.firmware} | Hardware: ${hubInfo.hardware}

<b>Devices:</b> <a href='/device/list' target='_blank' style='color: #1A77C9; text-decoration: none;'>${deviceStats.totalDevices}</a> total
  Active: ${deviceListLink(deviceStats.activeDevices, idsByS.active)} | Inactive: ${deviceListLink(deviceStats.inactiveDevices, (idsByS.inactive ?: []) + (idsByS.disabled ?: []))} | Disabled: ${deviceListLink(deviceStats.disabledDevices, idsByS.disabled)}
  Z-Wave: ${deviceListLink(deviceStats.byProtocol[PROTOCOL_ZWAVE], idsByP[PROTOCOL_ZWAVE])} | Zigbee: ${deviceListLink(deviceStats.byProtocol[PROTOCOL_ZIGBEE], idsByP[PROTOCOL_ZIGBEE])} | Matter: ${deviceListLink(deviceStats.byProtocol[PROTOCOL_MATTER], idsByP[PROTOCOL_MATTER])}
  Hub Mesh: ${deviceListLink(deviceStats.byProtocol[PROTOCOL_HUBMESH], idsByP[PROTOCOL_HUBMESH])} | Virtual: ${deviceListLink(deviceStats.byProtocol[PROTOCOL_VIRTUAL], idsByP[PROTOCOL_VIRTUAL])} | LAN: ${deviceListLink(deviceStats.byProtocol[PROTOCOL_LAN], idsByP[PROTOCOL_LAN])}

<b>Applications:</b> ${appStats.totalApps} total (System: ${appStats.builtInApps} | User: ${appStats.userApps})
${resourceLine}${alertLine}"""
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

    // Flatten the device list — child devices are nested inside parent entries' 'children' arrays
    List devicesList = []
    Closure flattenDevices
    flattenDevices = { List entries ->
        entries.each { entry ->
            devicesList << entry
            if (entry.children) {
                flattenDevices(entry.children as List)
            }
        }
    }
    flattenDevices(response.devices as List)

    Map stats = [
        totalDevices: 0, activeDevices: 0, inactiveDevices: 0, disabledDevices: 0,
        byProtocol: [(PROTOCOL_ZIGBEE): 0, (PROTOCOL_ZWAVE): 0, (PROTOCOL_MATTER): 0,
                     (PROTOCOL_LAN): 0, (PROTOCOL_VIRTUAL): 0, (PROTOCOL_MAKER): 0,
                     (PROTOCOL_CLOUD): 0, (PROTOCOL_HUBMESH): 0, (PROTOCOL_OTHER): 0],
        allIds: [],
        idsByStatus: [active: [], inactive: [], disabled: []],
        idsByProtocol: [(PROTOCOL_ZIGBEE): [], (PROTOCOL_ZWAVE): [], (PROTOCOL_MATTER): [],
                        (PROTOCOL_LAN): [], (PROTOCOL_VIRTUAL): [], (PROTOCOL_MAKER): [],
                        (PROTOCOL_CLOUD): [], (PROTOCOL_HUBMESH): [], (PROTOCOL_OTHER): []]
    ]

    // Build definitive protocol sets from radio detail endpoints (3 fast bulk calls)
    Map radioProtocols = buildRadioProtocolMap()

    long inactivityThresholdMs = now() - ((settings.inactivityDays ?: 7) * ONE_DAY_MS)

    devicesList.each { deviceEntry ->
        try {
            Map device = deviceEntry.data
            if (!device || !(device instanceof Map)) return

            stats.totalDevices++
            stats.allIds << device.id

            Long lastActivity = null
            try {
                if (device.lastActivity && !(device.lastActivity instanceof Boolean)) {
                    lastActivity = parseDate(device.lastActivity)
                }
            } catch (Exception e) { /* ignore */ }

            if (device.disabled) {
                stats.disabledDevices++
                stats.inactiveDevices++
                stats.idsByStatus.disabled << device.id
            } else if (lastActivity && lastActivity > inactivityThresholdMs) {
                stats.activeDevices++
                stats.idsByStatus.active << device.id
            } else {
                stats.inactiveDevices++
                stats.idsByStatus.inactive << device.id
            }

            // Protocol: check radio map first, then fall back to heuristic
            // If heuristic says radio but device isn't in radio endpoints, classify as OTHER
            String protocol
            if (device.linked == true) {
                protocol = PROTOCOL_HUBMESH
            } else if (radioProtocols.containsKey(device.id)) {
                protocol = radioProtocols[device.id]
            } else {
                protocol = determineProtocolQuick(device)
                if (protocol in [PROTOCOL_ZIGBEE, PROTOCOL_ZWAVE, PROTOCOL_MATTER]) {
                    protocol = PROTOCOL_OTHER
                }
            }
            stats.byProtocol[protocol] = (stats.byProtocol[protocol] ?: 0) + 1
            stats.idsByProtocol[protocol] << device.id
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

String generateRuntimeSummary(Map stats, Map resources) {
    if (!stats) {
        return "Unable to fetch current runtime stats"
    }

    String devPctColor = "#388e3c"
    String appPctColor = "#388e3c"
    try {
        float devPct = (stats.devicePct ?: "0%").toString().replace('%', '').toFloat()
        float appPct = (stats.appPct ?: "0%").toString().replace('%', '').toFloat()
        if (devPct + appPct > 10) { devPctColor = "#d32f2f"; appPctColor = "#d32f2f" }
        else if (devPct + appPct > 6) { devPctColor = "#ff9800"; appPctColor = "#ff9800" }
    } catch (Exception e) { /* ignore */ }

    List metrics = [
        ["Hub Uptime", stats.uptime ?: "N/A"],
        ["Devices Runtime", "${stats.totalDevicesRuntime ?: 'N/A'} (<span style='color: ${devPctColor};'>${stats.devicePct ?: 'N/A'}</span>)"],
        ["Apps Runtime", "${stats.totalAppsRuntime ?: 'N/A'} (<span style='color: ${appPctColor};'>${stats.appPct ?: 'N/A'}</span>)"],
        ["Total Devices", stats.deviceStats?.size() ?: 0],
        ["Total Apps", stats.appStats?.size() ?: 0],
        ["Scheduled Jobs", stats.jobs?.size() ?: 0]
    ]

    if (resources) {
        String memColor = (resources.freeOSMemory ?: 0) < 76800 ? "#d32f2f" : ((resources.freeOSMemory ?: 0) < 102400 ? "#ff9800" : "#388e3c")
        String cpuColor = (resources.cpuAvg5min ?: 0) > 8.0 ? "#d32f2f" : ((resources.cpuAvg5min ?: 0) > 4.0 ? "#ff9800" : "#388e3c")
        metrics << ["Free OS Memory", "<span style='color: ${memColor};'>${formatMemory(resources.freeOSMemory ?: 0)}</span>"]
        metrics << ["CPU Load Avg (5m)", "<span style='color: ${cpuColor};'>${String.format('%.2f', (resources.cpuAvg5min ?: 0) as float)}</span>"]
    }

    return formatMetricsTable(metrics)
}

String generateCheckpointTable(List checkpoints) {
    StringBuilder sb = new StringBuilder()
    sb.append("<table style='width:100%; border-collapse: collapse; font-size: 12px;'>")
    sb.append('<thead><tr style="background-color: #1A77C9; color: white;">')
    sb.append("<th style='padding: 8px; text-align: left; border: 1px solid #ddd;'>Timestamp</th>")
    sb.append("<th style='padding: 8px; text-align: center; border: 1px solid #ddd;'>Uptime</th>")
    sb.append("<th style='padding: 8px; text-align: center; border: 1px solid #ddd;'>Device Runtime</th>")
    sb.append("<th style='padding: 8px; text-align: center; border: 1px solid #ddd;'>App Runtime</th>")
    sb.append("<th style='padding: 8px; text-align: center; border: 1px solid #ddd;'></th>")
    sb.append('</tr></thead><tbody>')

    checkpoints.eachWithIndex { Map cp, int idx ->
        String rowColor = idx % 2 == 0 ? "#f9f9f9" : "#ffffff"
        sb.append("<tr style='background-color: ${rowColor};'>")
        sb.append("<td style='padding: 8px; border: 1px solid #ddd;'>${cp.timestamp}</td>")
        sb.append("<td style='padding: 8px; text-align: center; border: 1px solid #ddd;'>${cp.stats?.uptime ?: 'N/A'}</td>")
        sb.append("<td style='padding: 8px; text-align: center; border: 1px solid #ddd;'>${cp.stats?.totalDevicesRuntime ?: 'N/A'}</td>")
        sb.append("<td style='padding: 8px; text-align: center; border: 1px solid #ddd;'>${cp.stats?.totalAppsRuntime ?: 'N/A'}</td>")
        sb.append("<td style='padding: 8px; text-align: center; border: 1px solid #ddd;'>")
        sb.append("<input type='button' name='btnDeleteCheckpoint_${idx}' value='\uD83D\uDDD1' title='Delete checkpoint' style='font-size: 14px; background: none; border: none; cursor: pointer; padding: 2px 6px;' />")
        sb.append("</td></tr>")
    }

    sb.append('</tbody></table>')
    return sb.toString()
}

String generateSnapshotsTable(List snapshots) {
    if (!snapshots || snapshots.size() == 0) {
        return "No config snapshots available"
    }

    StringBuilder sb = new StringBuilder()
    sb.append("<table style='width:100%; border-collapse: collapse; font-size: 12px;'>")
    sb.append('<thead><tr style="background-color: #1A77C9; color: white;">')
    sb.append("<th style='padding: 8px; text-align: left; border: 1px solid #ddd;'>Timestamp</th>")
    sb.append("<th style='padding: 8px; text-align: center; border: 1px solid #ddd;'>Firmware</th>")
    sb.append("<th style='padding: 8px; text-align: center; border: 1px solid #ddd;'>Devices</th>")
    sb.append("<th style='padding: 8px; text-align: center; border: 1px solid #ddd;'>Apps</th>")
    sb.append("<th style='padding: 8px; text-align: center; border: 1px solid #ddd;'>Memory</th>")
    sb.append("<th style='padding: 8px; text-align: center; border: 1px solid #ddd;'></th>")
    sb.append('</tr></thead><tbody>')

    snapshots.eachWithIndex { Map snap, int idx ->
        String rowColor = idx % 2 == 0 ? "#f9f9f9" : "#ffffff"
        String memDisplay = "N/A"
        if (snap.systemHealth?.memory?.freeOSMemory) {
            memDisplay = formatMemory(snap.systemHealth.memory.freeOSMemory as int)
        }

        sb.append("<tr style='background-color: ${rowColor};'>")
        String firmware = snap.hubInfo?.firmware ?: "N/A"
        sb.append("<td style='padding: 8px; border: 1px solid #ddd;'>${snap.timestamp}</td>")
        sb.append("<td style='padding: 8px; text-align: center; border: 1px solid #ddd;'>${firmware}</td>")
        sb.append("<td style='padding: 8px; text-align: center; border: 1px solid #ddd;'>${snap.devices?.totalDevices ?: 0}</td>")
        sb.append("<td style='padding: 8px; text-align: center; border: 1px solid #ddd;'>${snap.apps?.totalApps ?: 0}</td>")
        sb.append("<td style='padding: 8px; text-align: center; border: 1px solid #ddd;'>${memDisplay}</td>")
        sb.append("<td style='padding: 8px; text-align: center; border: 1px solid #ddd; white-space: nowrap;'>")
        sb.append("<input type='button' name='btnDeleteSnapshot_${idx}' value='\uD83D\uDDD1' title='Delete snapshot' style='font-size: 14px; background: none; border: none; cursor: pointer; padding: 2px 6px;' />")
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

String formatProtocolTable(Map protocolMap, Map idsByProtocol = [:]) {
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
            List ids = idsByProtocol[key] ?: []
            String countDisplay = ids ? deviceListLink(count, ids) : count.toString()
            sb.append("<tr><td style='padding: 4px;'>${displayName}</td>")
            sb.append("<td style='text-align:right; padding: 4px;'>${countDisplay}</td></tr>")
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
                sb.append("<b><a href='/installedapp/configure/${child.id}' target='_blank' style='color: #1A77C9; text-decoration: none;'>${child.name}</a></b>${disabledText}<br>")
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
        lowBatteryDevices: [], allDevices: [],
        byType: [:],
        byProtocol: [(PROTOCOL_ZIGBEE): 0, (PROTOCOL_ZWAVE): 0, (PROTOCOL_MATTER): 0,
                     (PROTOCOL_LAN): 0, (PROTOCOL_VIRTUAL): 0, (PROTOCOL_MAKER): 0,
                     (PROTOCOL_CLOUD): 0, (PROTOCOL_HUBMESH): 0, (PROTOCOL_OTHER): 0],
        byStatus: [active: 0, inactive: 0, disabled: 0],
        idsByStatus: [active: [], inactive: [], disabled: []],
        idsByProtocol: [(PROTOCOL_ZIGBEE): [], (PROTOCOL_ZWAVE): [], (PROTOCOL_MATTER): [],
                        (PROTOCOL_LAN): [], (PROTOCOL_VIRTUAL): [], (PROTOCOL_MAKER): [],
                        (PROTOCOL_CLOUD): [], (PROTOCOL_HUBMESH): [], (PROTOCOL_OTHER): []],
        parentIds: [],
        childIds: [],
        linkedIds: [],
        batteryIds: []
    ]
}

Map normalizeSnapshotSystemHealth(Map health) {
    Map source = health ?: [:]
    return [
        memory: source.memory instanceof Map ? source.memory : null,
        databaseSize: source.databaseSize != null ? source.databaseSize : source.database?.databaseSize
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

Map loadPerformanceComparisonPayload() {
    def data = readFile(PERFORMANCE_COMPARISON_FILE)
    return data instanceof Map ? (Map) data : null
}

void savePerformanceComparisonPayload(Map payload) {
    if (payload) {
        writeFile(PERFORMANCE_COMPARISON_FILE, groovy.json.JsonOutput.toJson(payload))
    }
}

void clearPerformanceComparison() {
    deleteFile(PERFORMANCE_COMPARISON_FILE)
}

boolean hasSavedPerformanceComparison() {
    return loadPerformanceComparisonPayload() != null
}

Map loadSnapshotDiffPayload() {
    def data = readFile(SNAPSHOT_DIFF_FILE)
    return data instanceof Map ? (Map) data : null
}

void saveSnapshotDiffPayload(Map payload) {
    if (payload) {
        writeFile(SNAPSHOT_DIFF_FILE, groovy.json.JsonOutput.toJson(payload))
    }
}

void clearSnapshotDiff() {
    deleteFile(SNAPSHOT_DIFF_FILE)
}

boolean hasSavedSnapshotDiff() {
    return loadSnapshotDiffPayload() != null
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
    migrateStorageIfNeeded()

    if (settings.autoSnapshot) {
        int interval = (settings.snapshotInterval ?: "24").toInteger()
        schedule("0 0 */${interval} * * ?", "createSnapshot")
        log.info "Automatic config snapshots scheduled every ${interval} hour(s)"
    }

    if (settings.autoCheckpoint) {
        int interval = (settings.checkpointInterval ?: "60").toInteger()
        if (interval < 60) {
            schedule("0 */${interval} * * * ?", "createCheckpoint")
        } else {
            int hours = (interval / 60).toInteger()
            schedule("0 0 */${hours} * * ?", "createCheckpoint")
        }
        log.info "Automatic perf checkpoints scheduled every ${interval} minute(s)"
    }
}

void migrateStorageIfNeeded() {
    String currentSchema = (state.storageSchemaVersion ?: "") as String
    if (currentSchema == STORAGE_SCHEMA_VERSION) {
        return
    }

    boolean migratedLegacyState = false
    if (state.lastPerformanceComparison != null) {
        state.remove("lastPerformanceComparison")
        migratedLegacyState = true
    }
    if (state.lastSnapshotDiff != null) {
        state.remove("lastSnapshotDiff")
        migratedLegacyState = true
    }

    state.storageSchemaVersion = STORAGE_SCHEMA_VERSION
    if (migratedLegacyState) {
        log.info "Migrated legacy comparison state to file-backed storage for v${APP_VERSION}"
    }
}

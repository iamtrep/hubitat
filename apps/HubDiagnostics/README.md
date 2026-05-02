# Hub Diagnostics

A comprehensive diagnostic dashboard for Hubitat Elevation hubs. Provides real-time and historical visibility into devices, apps, network health, performance, and configuration — all in a single web UI served directly from your hub.

**Current version:** 5.6.0

---

## Table of Contents

1. [Installation](#installation)
2. [Opening the Dashboard](#opening-the-dashboard)
3. [Updating](#updating)
4. [Dashboard Tab](#dashboard-tab)
5. [Devices Tab](#devices-tab)
6. [Apps Tab](#apps-tab)
7. [Network Tab](#network-tab)
8. [Health Tab](#health-tab)
9. [Performance Tab](#performance-tab)
10. [Snapshots Tab](#snapshots-tab)
11. [App Settings Tab](#app-settings-tab)
12. [Forum Export](#forum-export)
13. [Alerts & Warnings Reference](#alerts--warnings-reference)
14. [REST API](#rest-api)

---

## Installation

Hub Diagnostics consists of two files:

| File | Purpose |
|---|---|
| `HubDiagnostics.groovy` | The Hubitat app (backend logic, API, data collection) |
| `hub_diagnostics_ui.html` | The web dashboard UI (served from hub File Manager) |

### Step 1 — Install the Groovy app

1. In the Hubitat admin UI, go to **Apps Code → + New App**
2. Paste the contents of `HubDiagnostics.groovy`, or use the **Import** button with this URL:
   ```
   https://raw.githubusercontent.com/hubitrep/hubitat/refs/heads/main/HubDiagnostics/HubDiagnostics.groovy
   ```
3. Click **Save**
4. **Enable OAuth**: on the app code page, click **OAuth** and enable it. This is required — the dashboard communicates with the app via OAuth-protected API calls.

### Step 2 — Create an app instance

1. Go to **Apps → + Add User App → Hub Diagnostics**
2. The app will initialize, configure OAuth, and verify the UI file is present.
3. A link to open the dashboard appears on the main page.

> **Note:** If OAuth auto-enable fails during install, the app will show instructions to enable it manually from the Apps Code page.

---

## Opening the Dashboard

From the Hubitat admin UI: **Apps → Hub Diagnostics → Open Dashboard**

The dashboard URL follows the pattern:
```
http://{hub-ip}/apps/api/{app-id}/ui.html?access_token={token}
```

Bookmark this URL for direct access. The access token is tied to the app instance and does not expire unless OAuth is re-configured.

---

## Updating

### Check for updates
The Dashboard tab shows the current App Version and UI Version. Click **Check for updates** to query GitHub for a newer release.

### Updating the Groovy app
Use **Apps Code → Hub Diagnostics → Import** (same import URL as above). After saving, re-open the app preferences page once to re-initialize.

### Updating the UI
The app enforces version sync: on each dashboard load it checks whether the installed UI file matches the app version. If the UI is missing or the last check is more than 24 hours old, it downloads the latest UI from GitHub — but only installs it if the downloaded file's embedded version exactly matches the installed app version. In practice this means: after updating the Groovy app code, the matching UI is installed automatically on the next dashboard load. You can also trigger an immediate sync via **Sync UI from GitHub** on the Dashboard tab.

---

## Dashboard Tab

A summary of hub health at a glance.

**Overview** — Device counts: total, active, inactive, disabled. Installed apps: total, built-in, user. All counts are linked to the relevant filtered list.

**Resources** — Free OS memory, CPU load (5-minute average), hub temperature, and database size. Values are color-coded against configurable thresholds (see [App Settings](#app-settings-tab)).

**Connection Types / Integrations** — Distribution of devices by how they connect (Paired, LAN Direct, LAN Bridge, Cloud, Virtual, Hub Mesh) and by which integration manages them. Counts are linked to the device list filtered to that group.

**Platform Alerts** — Active alerts reported by the Hubitat hub itself (load warnings, radio crashes, backup failures, etc.) plus any threshold-based alerts calculated by this app (memory, CPU, temperature). See [Alerts & Warnings Reference](#alerts--warnings-reference) for the full list.

**Reports** — Buttons to generate a full HTML report or copy a forum-ready Markdown export to the clipboard.

---

## Devices Tab

A full inventory of every device on the hub.

**Summary cards** — Total devices with active/inactive/disabled counts (the inactivity threshold is shown). Parent/child/Hub Mesh/battery device counts. Connection type breakdown and integration breakdown tables.

**Device table** columns:

| Column | Description |
|---|---|
| Name | Linked to the device edit page |
| Type | Driver type; community drivers are linked to the driver editor |
| Connection | How the device connects: Paired, LAN (Direct), LAN (Bridge), Cloud, Virtual, Hub Mesh |
| Integration | Which integration manages the device (Zigbee, Z-Wave, Kasa, Lutron, etc.) |
| Room | Assigned room |
| Status | Active / Inactive / Disabled badge |
| Last Activity | Timestamp or "Never" |
| Battery | Percentage, color-coded; blank for non-battery devices |
| Parent | Parent app or device, if any |

### Device Classification

The **Connection** and **Integration** columns are inferred from Hubitat's device metadata: protocol flags (Zigbee, Z-Wave, Matter, Bluetooth, Hub Mesh, Virtual), then the device's parent app matched against a known integration list (Philips Hue, Kasa, Lutron, Mobile App, etc.). Devices that don't match any known pattern show **Other** as the connection type.

Community driver authors can force a specific classification by calling `updateDataValue` in the driver:

```
updateDataValue("hubdiag:conn", "cloud")       // cloud integration
updateDataValue("hubdiag:conn", "lan_direct")  // direct LAN integration
updateDataValue("hubdiag:conn", "lan_bridge")  // LAN bridge (hub-based) integration
updateDataValue("hubdiag:conn", "virtual")     // virtual device
updateDataValue("hubdiag:conn", "paired")      // radio-paired device
```

This value is read during device enrichment and cached. Use **Clear Enrichment Cache** (App Settings → Maintenance) after changing a data value for it to take effect.

**Low Battery Alerts** — Devices below the configured battery threshold are listed separately above the main table.

**Stale Devices** — Devices with no activity for more than 2× the inactivity threshold (default: 14 days) are shown in a separate card. These could have lost connectivity or been abandoned.

---

## Apps Tab

A flat, sortable table of all installed app instances, including built-in system apps and community apps.

**Columns:**

| Column | Description |
|---|---|
| App | Instance name, linked to its configure page (or status page for platform apps) |
| Type | Internal app type name |
| Source | **Platform** (hub system app) / **Community** (HPM/user) / **Built-in** (Hubitat) |
| Menu | Menu category if firmware ≥ 2.5.0.x: Automations / Integrations / Apps |
| Role | **Parent (N)** if the app has N child instances; **Child of [parent name]** if it is a child |
| Status | **Disabled** badge if the app is disabled |

The table is sortable and filterable. Hidden apps and setting-only apps (no user-visible instance) are excluded.

---

## Network Tab

Detailed status for all network interfaces and radio protocols.

### Network Configuration

IP address, connection type (DHCP/static), gateway, subnet, DNS. Shows a warning if both Ethernet and WiFi are active simultaneously (this is known to cause instability on some hub models).

### Z-Wave

- Radio status (enabled, healthy, firmware version, region, node count)
- Firmware update alert if a radio firmware upgrade is available

**Ghost Nodes** — Z-Wave radio nodes with no associated Hubitat device (the device was deleted without Z-Wave exclusion), or nodes in FAILED state, or nodes with no route and an unknown name. Each ghost shows the signals that triggered it: `no device`, `FAILED`, `no route`, `unknown name`. These should be removed from the Z-Wave mesh.

**Problem Nodes** — Nodes with state ≠ OK or packet error rate > 1%.

**Mesh Quality table** columns: Device, Security (S0/S2/None — S0 on non-lock devices is flagged), RTT (ms), RSSI (dBm), PER %, Neighbors, Route, Route Changes, Messages, Driver (Built-in/User), State.

**Message Counts table** — Devices ranked by message volume, with messages/minute color-coded against the chatty device threshold.

**S0 Security flag** — Devices paired with S0 security that are not locks or garage doors are highlighted. S0 generates ~3× the mesh traffic of S2; re-pairing with S2 improves mesh performance.

**Isolated Nodes** — Non-failed nodes with zero neighbors cannot participate in mesh routing. This usually indicates a device out of range or with a firmware issue.

### Zigbee

- Radio status (enabled, healthy, channel, PAN ID, device count, join mode, power level)
- Responsive vs total device count, color-coded by ratio (warning < 100%, critical < 80%)
- Non-responsive device list

**Mesh Quality** — Average LQI (Link Quality Indicator), min/max, weak neighbors (LQI < 150), stale neighbors (age ≥ 7 rounds).

**Neighbor Detail table** — Short ID, LQI (color-coded), age (stale badge at age 7), In Cost, Out Cost.

**Zigbee–WiFi Spectrum Chart** — Visual overlay of Zigbee channels and 2.4 GHz WiFi channels, showing potential interference. Recommended Zigbee channels (15, 20, 25) avoid overlap with WiFi channels 1, 6, and 11.

### Matter

Device list, network state, fabric ID.

### Hub Mesh

Enabled status, shared/linked device and variable counts. Table of peer hubs with name, IP, online status, and their device/variable counts.

---

## Health Tab

**Hub Information** — Hardware model, firmware version, hub ID, IP address, Zigbee ID, location, current mode, time zone.

**Alerts** — All active platform and calculated alerts. Shows a green checkmark when there are none.

**System Resources** — Free OS memory, CPU load, Java heap usage (total/free/direct), temperature (°C and °F). Values are color-coded against configured thresholds.

**Resource History** — Time-series chart of free OS memory and CPU load over recent checkpoints. Horizontal reference lines mark the warning and critical memory thresholds.

**Database & Storage** — Database size, state compression status, max events per device, max event age (days), max state age (days).

---

## Performance Tab

### Current Runtime Statistics

Overview of how the hub is spending its execution time: device runtime %, app runtime %, uptime, resources (memory and CPU).

**Top Talkers** — The 3 most message-active Z-Wave and Zigbee devices by total message count since last restart.

**App Runtime Detail** — Sortable table of every app with: total execution time (ms), % busy, execution count, average time per call, state size, source badge (Platform/Community/Built-in).

**Device Runtime Detail** — Same structure for device drivers.

**Radio Message Counts** — Per-device message counts and msgs/min for Z-Wave and Zigbee. Devices exceeding the chatty device threshold are highlighted with a critical alert banner; devices in the warn band (60% of threshold) show a warning banner.

### Performance Checkpoints

A checkpoint captures a point-in-time snapshot of runtime statistics and resources. Use checkpoints to compare performance before and after changes (new app, added devices, firmware update, etc.).

- **Take Perf Checkpoint** — manually capture the current state
- **Auto-checkpoints** — optionally schedule automatic captures (5m–24h intervals, up to 50 retained)

**Checkpoint comparison** — Select a baseline (hub startup or any checkpoint) and a comparison point (now or any checkpoint). The diff view shows:
- Delta in device and app runtime %
- Memory and CPU delta
- Radio message count delta (msgs/min per device, since the interval)

---

## Snapshots Tab

A configuration snapshot captures the full state of devices, apps, hub metadata, and system resources at a point in time. Snapshots are used to detect configuration drift — what changed between two points in time.

**Taking a snapshot** — Click **Take Config Snapshot**, or enable auto-snapshots on a schedule (1h–24h, up to 50 retained).

**Snapshot table** — Lists all saved snapshots with timestamp, firmware version, device count, app count, and free memory. Individual snapshots can be viewed or deleted.

**Viewing a snapshot** — Shows a full breakdown at the time of capture: device counts by status, connection types, integrations, full device list, app counts, app type list, user app instances, and parent/child hierarchy.

**Snapshot diff (Compare)** — Select an older and a newer snapshot and click **Compare**. The diff shows:

- Firmware version changes
- Devices added, removed, or changed (connection type, integration, status)
- Connection type count deltas
- Integration count deltas
- Apps added or removed
- Free OS memory delta

Changes are color-coded: green for additions/improvements, red for removals/degradations, yellow for modifications.

---

## App Settings Tab

Most settings are accessible from the Hubitat admin UI under **Apps → Hub Diagnostics → Preferences**. Two settings are available only through the dashboard's App Settings tab and are not shown in the Hubitat admin UI: **Obfuscate labels in forum export** (Export section) and **Clear Enrichment Cache** (Maintenance section).

### Config Snapshot Scheduling
- Enable automatic snapshots: on/off
- Interval: 1h / 6h / 12h / 24h
- Max snapshots to retain: 1–50 (default 10; oldest are pruned when the limit is reached)

### Perf Checkpoint Scheduling
- Enable automatic checkpoints: on/off
- Interval: 5m / 15m / 30m / 1h / 6h / 12h / 24h
- Max checkpoints to retain: 1–50 (default 10)

### Device Monitoring
| Setting | Default | Range | Effect |
|---|---|---|---|
| Inactivity threshold (days) | 7 | 1–90 | Devices with no activity beyond this period are marked Inactive |
| Low battery alert (%) | 20 | 1–50 | Devices at or below this level appear in the Low Battery Alerts card |
| Chatty device threshold (msgs/min) | 10 | 1–1000 | Devices exceeding this rate trigger a critical alert on the Performance tab and in forum exports |

### Alert Thresholds

These thresholds control when resource metrics turn orange (warning) or red (critical) across the Dashboard, Health, and Performance tabs. Adjust them to match your hub model and environment — a C-7 hub will have different normal ranges than a C-8 Pro.

| Setting | Default | Range |
|---|---|---|
| Free memory warning (MB) | 100 | 10–2000 |
| Free memory critical (MB) | 75 | 10–2000 |
| CPU load average warning | 4.0 | 0.1–32 |
| CPU load average critical | 8.0 | 0.1–32 |
| Hub temperature warning (°C) | 50 | 20–100 |
| Hub temperature critical (°C) | 77 | 20–100 |

Changes take effect immediately in the dashboard without a page reload.

### Logging
- **Debug logging** — Enables verbose logging in the Hubitat Logs page. Useful for troubleshooting; leave off during normal use.

### Export
- **Report link mode** — Controls how device/app links are formatted in generated HTML reports: Relative (recommended, works via Remote Admin) or Absolute (full local IP URL).
- **Obfuscate labels in forum export** — Replaces device and app names with their driver/app type in forum exports. Useful for privacy when posting diagnostics in public forums.

### Maintenance
- **Clear Enrichment Cache** — Clears the cached per-device classification data (controller type, parent app). The next analysis re-fetches this from the hub. Use this if device classifications appear stale after adding or re-pairing devices.

---

## Forum Export

The forum export generates a concise Markdown-formatted summary suitable for pasting into a community forum post when seeking help.

**Access:** Dashboard tab → **Copy for Forum**. This opens a dialog with the Markdown text pre-selected. Press Ctrl+C (or Cmd+C) and close.

**Contents of the export:**

- System basics: model, firmware, uptime, network config, CPU, memory, temperature, database size
- Active alerts (all severities)
- Device inventory: counts by status, connection type, and integration; low battery devices
- App inventory: counts by source; top 5 apps by CPU %
- Z-Wave: health, ghost nodes, problem nodes, S0 flag, mesh quality stats, top talkers
- Zigbee: health, channel, LQI stats, weak/stale neighbors, top talkers
- Hub Mesh and Matter status

**Obfuscation:** Enable "Obfuscate labels in forum export" in App Settings to replace device names with driver types, so you can share diagnostics publicly without revealing device names or room labels.

---

## Alerts & Warnings Reference

### System Resource Alerts
*Thresholds are configurable in App Settings → Alert Thresholds.*

| Alert | Condition | Default Threshold | Severity |
|---|---|---|---|
| OS Memory Critical | Free memory < critMemMb | 75 MB | Critical |
| OS Memory Warning | Free memory < warnMemMb | 100 MB | Warning |
| CPU Load Critical | 5-min avg > critCpuLoad | 8.0 | Critical |
| CPU Load Warning | 5-min avg > warnCpuLoad | 4.0 | Warning |
| Temperature Critical | Hub temp > critTempC | 77 °C | Critical |
| Temperature Warning | Hub temp > warnTempC | 50 °C | Warning |

### Platform Alerts (binary flags from the hub — no configurable threshold)

| Alert | Severity |
|---|---|
| Hub Load Elevated | Warning |
| Hub Load Severe | Critical |
| Hub High Load | Warning |
| Hub Low Memory | Warning |
| Z-Wave Radio Crashed | Critical |
| Z-Wave Migration Failed | Warning |
| Z-Wave Offline | Critical |
| Zigbee Offline | Critical |
| Cloud Disconnected | Warning |
| Database Growing Large | Warning |
| Database Large | Warning |
| Database Very Large | Critical |
| Spammy Devices Detected (hub-detected) | Warning |
| Local Backup Failed | Warning |
| Cloud Backup Failed | Warning |
| Weak Zigbee Channel (hub-detected) | Warning |
| Platform Update Available | Warning |

### Network Alerts (hardcoded)

| Alert | Condition | Severity |
|---|---|---|
| Ethernet + WiFi both active | Both interfaces enabled | Warning |
| Z-Wave radio firmware update available | Hub flag | Warning |
| Matter reboot required | Hub flag | Warning |

### Z-Wave Mesh Alerts (hardcoded)

| Alert | Condition | Threshold | Severity |
|---|---|---|---|
| Ghost Nodes | No associated device ID (primary), or FAILED, or no route + unknown name | — | Critical |
| Problem Nodes | State ≠ OK, or PER > 1% | 1% PER | Warning |
| Isolated Nodes | 0 neighbors, not FAILED | 0 neighbors | Warning |
| Avg PER Critical | Mesh average PER > 1% | 1% | Critical |
| Avg PER Warning | Mesh average PER > 0% | 0% | Warning |
| Avg RSSI Critical | Mesh average RSSI < −80 dBm | −80 dBm | Critical |
| Avg RSSI Warning | Mesh average RSSI < −60 dBm | −60 dBm | Warning |
| S0 Security Overhead | S0 on non-lock/non-garage device | device type | Warning |

### Zigbee Mesh Alerts (hardcoded)

| Alert | Condition | Threshold | Severity |
|---|---|---|---|
| Non-Responsive Critical | Responsive < 80% of total | 80% ratio | Critical |
| Non-Responsive Warning | Responsive < 100% but ≥ 80% | 80% ratio | Warning |
| Avg LQI Critical | Average LQI < 150 | 150 | Critical |
| Avg LQI Warning | Average LQI < 200 | 200 | Warning |
| Weak Neighbors | Any neighbor LQI < 150 | 150 | Critical |
| Stale Neighbors | Any neighbor age ≥ 7 rounds | 7 | Warning |
| Channel Not Recommended | Channel not in {15, 20, 25} | fixed list | Warning |

### Device Alerts

| Alert | Condition | Default Threshold | Configurable | Severity |
|---|---|---|---|---|
| Low Battery | Battery ≤ lowBatteryThreshold | 20% | ✅ App Settings | Warning |
| Inactive Devices | No activity > inactivityDays | 7 days | ✅ App Settings | Warning |
| Stale Devices | No activity > 2 × inactivityDays | 14 days | ✅ base; 2× multiplier hardcoded | Warning |
| Disabled Devices | Device disabled flag | — | ❌ | Critical |

### Performance Alerts

| Alert | Condition | Default Threshold | Configurable | Severity |
|---|---|---|---|---|
| High Message Rate | Device msgs/min ≥ chattyDeviceThreshold | 10/min | ✅ App Settings | Critical |
| Elevated Message Rate | Device msgs/min ≥ chattyDeviceThreshold × 0.6 | 6/min | ✅ base; 0.6× multiplier hardcoded | Warning |

---

## REST API

The app exposes a REST API used by the dashboard. All endpoints require the OAuth `access_token` parameter.

Base URL: `http://{hub-ip}/apps/api/{app-id}/`

### Data endpoints (GET)

| Endpoint | Description |
|---|---|
| `ui.html` | Serves the dashboard UI |
| `api/dashboard` | Overview data (devices, apps, resources, alerts) |
| `api/devices` | Device inventory and details |
| `api/apps` | Installed app listing |
| `api/network` | Network config and radio protocol status |
| `api/health` | Hub health, alerts, resource details |
| `api/health/history` | Memory/CPU history for charting |
| `api/performance` | Runtime stats and checkpoints |
| `api/snapshots` | List of config snapshots |
| `api/snapshot/view?index=N` | View a specific snapshot |
| `api/snapshot/diff?older=O&newer=N` | Compare two snapshots |
| `api/reports` | List saved HTML diagnostic reports (name, size, date) and last generated filename |
| `api/stats` | Internal API timing metrics (median latency, call count, and recent samples per endpoint) |
| `api/export/forum` | Generate forum export (Markdown) |
| `api/version/check` | Check for app updates on GitHub |
| `api/settings` | Retrieve current settings |

### Action endpoints (POST)

| Endpoint | Description |
|---|---|
| `api/settings` | Update one or more settings |
| `api/snapshot/create` | Take a config snapshot |
| `api/snapshot/delete` | Delete snapshot by index |
| `api/snapshots/clear` | Delete all snapshots |
| `api/checkpoint/create` | Take a performance checkpoint |
| `api/checkpoint/delete` | Delete checkpoint by index |
| `api/checkpoints/clear` | Delete all checkpoints |
| `api/performance/compare` | Run a performance comparison |
| `api/report/generate` | Generate a full HTML report |
| `api/ui/sync` | Force-sync the UI file from GitHub |
| `api/cache/clear` | Clear the device enrichment cache |

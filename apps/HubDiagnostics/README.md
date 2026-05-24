<!--
Copyright (c) 2025-2026 PJ
SPDX-License-Identifier: MIT
-->

# Hub Diagnostics

A comprehensive diagnostic dashboard for Hubitat Elevation hubs. Provides real-time and historical visibility into devices, apps, network health, performance, and configuration — all in a single web UI served directly from your hub.

<!-- AUTO:hubdiag-version -->
**Current version:** 5.47.0
<!-- /AUTO -->

---

## Table of Contents

1. [Installation](#installation)
2. [Opening the Dashboard](#opening-the-dashboard)
3. [Updating](#updating)
4. [Dashboard Tab](#dashboard-tab)
5. [Devices Tab](#devices-tab)
6. [Apps Tab](#apps-tab)
7. [Code Tab](#code-tab)
8. [Network Tab](#network-tab)
9. [Health Tab](#health-tab)
10. [Performance Tab](#performance-tab)
11. [Snapshots Tab](#snapshots-tab)
12. [Radio Capture Tab](#radio-capture-tab)
13. [Device Usage Audit](#device-usage-audit)
14. [App Settings Tab](#app-settings-tab)
15. [Forum Export](#forum-export)
16. [Alerts & Warnings Reference](#alerts--warnings-reference)
17. [REST API](#rest-api)

---

## Installation

You only need to install one file — the Groovy app. The app downloads and installs its own web dashboard onto the hub.

### Step 1 — Install the Groovy app

1. In the Hubitat admin UI, go to **Apps Code → + New App**
2. Paste the contents of `HubDiagnostics.groovy`, or use the **Import** button with this URL:
   ```
   https://raw.githubusercontent.com/iamtrep/hubitat/refs/heads/main/apps/HubDiagnostics/HubDiagnostics.groovy
   ```
3. Click **Save**
4. **Enable OAuth**: on the app code page, click **OAuth** and enable it. This is required — the dashboard communicates with the app via OAuth-protected API calls.

### Step 2 — Create an app instance

1. Go to **Apps → + Add User App → Hub Diagnostics**
2. On first run the app initializes, configures OAuth, and automatically downloads its web dashboard to the hub's File Manager — you don't need to install that file yourself.
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
The app keeps the UI in sync with the app version automatically: a nightly job downloads the latest UI from GitHub and installs it once its version matches the installed app. In practice: after updating the Groovy app code, open the app preferences once and re-save (or wait for the nightly job) and the matching UI will install automatically. You can also trigger an immediate sync via **Sync UI from GitHub** on the Dashboard tab.

---

## Dashboard Tab

A summary of hub health at a glance.

The header bar contains a **Docs ↗** link (this document) and a **↻ Refresh** button that forces a full re-fetch and re-render of whichever tab is currently open.

**Hub Information** — Hardware model, firmware version, hub ID, IP address, Zigbee ID, location, current mode, time zone.

**Overview** — Device counts: total, active, inactive, disabled. Installed apps: total, built-in, user. All counts are linked to the relevant filtered list.

**Resources** — Free OS memory, CPU load (5-minute average), hub temperature, and database size. Values are color-coded against configurable thresholds (see [App Settings](#app-settings-tab)). This card **auto-refreshes** in the background on a configurable interval (default 30 s); the last refresh time is shown in the card header.

**Connection Types / Integrations** — Distribution of devices by how they connect (Paired, LAN Direct, LAN Bridge, Cloud, Virtual, Hub Mesh) and by which integration manages them. Counts are linked to the device list filtered to that group.

**Platform Alerts** — A roll-up of hub health: alerts reported by the Hubitat hub itself (load warnings, radio crashes, backup failures, etc.), threshold-based alerts derived in the browser from the configured thresholds (memory, CPU, temperature), Z-Wave radio health (ghost / failed / problem nodes and radio-firmware-update), hub-firmware and app-update status, and messages from `/hub/messages` (info-severity). This same roll-up drives the **alert-aware favicon** (a colored dot on the browser tab showing the highest active severity) and the Health tab's *Alerts* list. Device-inventory conditions — low battery, stale devices, and chatty devices — are shown on their own tabs (Devices / Performance), **not** in this roll-up or the favicon. See [Alerts & Warnings Reference](#alerts--warnings-reference) for the full list.

**Hub Firmware** — When `/hub/cloud/checkForUpdate` reports an upgrade is available, an orange badge appears with the current version, the available version, and a link to the release notes. The check is cached for 1 hour to avoid hammering the cloud API on every dashboard refresh.

**Reports** — Buttons to generate a full HTML report or copy a forum-ready Markdown export to the clipboard.

---

## Devices Tab

A full inventory of every device on the hub.

**Summary cards** — Total devices with active/inactive/disabled counts (the inactivity threshold is shown). Parent/child/Hub Mesh/battery device counts. Connection type breakdown and integration breakdown tables.

**Device Audit card** — Sits below the Device Summary card. Contains the **Generate Device Audit** button that triggers the per-device cross-reference scan; on completion a **View report** link opens the result. See [Device Usage Audit](#device-usage-audit) for details on what the audit produces.

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

## Code Tab

The canonical place for all user-installed source code on this hub. The Apps and Devices tabs show installed *instances*; this tab shows the *source code* those instances are built from, plus user-defined libraries, bundles, and hub variables.

**Code Summary** — Top-of-tab metrics: App Types, Driver Types, Bundles, Libraries, Hub Variables.

**User App Types** (`/hub2/userAppTypes`) — Sortable + filterable table of every user-installed app type:

| Column | Description |
|---|---|
| App Type | Name, linked to `/app/edit/<id>` |
| Namespace | Author/maintainer namespace |
| OAuth | Green badge when OAuth is enabled on this app type |
| Instances | Count of installed instances of this app type — installed-instance names appear in a hover tooltip |
| Last Modified | YYYY-MM-DD |

**User Driver Types** (`/hub2/userDeviceTypes`) — Same shape, for driver code:

| Column | Description |
|---|---|
| Driver Type | Name, linked to `/driver/editor/<id>` |
| Namespace | Author/maintainer namespace |
| Capabilities | Count — capability list appears in a hover tooltip |
| Devices | Count of devices using this driver — device names appear in a hover tooltip |
| Last Modified | YYYY-MM-DD |

**User Bundles** (`/hub2/userBundles`) — Bundles installed via Hubitat Package Manager or manually:

| Column | Description |
|---|---|
| Bundle | Bundle name |
| Namespace | Bundle namespace |
| Contents | Free-form contents summary (lists apps/drivers/libraries inside the bundle) |
| Visibility | Public / Private badge |

**User Libraries** (`/hub2/userLibraries`) — Reusable Groovy libraries that apps and drivers can include:

| Column | Description |
|---|---|
| Library | Name, linked to `/library/editor/<id>` |
| Namespace | Library namespace |
| Author | Library author |
| Version | Numeric version |
| Description | Short description |
| Used by Drivers | Count — driver names appear in a hover tooltip |
| Used by Apps | Count — app names appear in a hover tooltip |
| Updated | YYYY-MM-DD |

**Hub Variables** — Read directly via Hubitat's `getAllGlobalVars()` platform API (no Hub Variables app instance required). Shows "API not available on this firmware" on older firmware.

| Column | Description |
|---|---|
| Name | Variable name |
| Type | string / integer / decimal / boolean / datetime |
| Value | Current value, rendered as monospace code |
| Last Updated | Timestamp if exposed by the platform |

---

## Network Tab

Detailed status for all network interfaces and radio protocols. Card order: **Radio Health Badges → Network Configuration → Zigbee → Z-Wave → Matter → Hub Mesh → mDNS Discovery**.

### Radio Health Badges

Strip at the very top with a green / red / N/A badge for Z-Wave and Zigbee, fed by `/hub/zwave/healthStatus` and `/hub/zigbee/healthStatus`. Version-gated to firmware ≥ 2.4.1.154; on older firmware the card is not rendered.

### Network Configuration

- IP address, connection type (DHCP/static), gateway, subnet, DNS
- Ethernet / WiFi status; if WiFi is active, the WiFi network name
- **NTP Server** — current time-source IP, or "(not configured)" when unset
- **Limited Access** — green when IP allowlist is enabled (with the allowed IPs listed), orange-warn when off ("any LAN IP can reach the hub UI")
- **Allowed Subnets** — comma-separated subnet allowlist, or "(none restricted)"
- **DNS Fallback** — Enabled / Disabled

Shows a warning if both Ethernet and WiFi are active simultaneously (known to cause instability on some hub models).

### Zigbee

- Radio status (enabled, healthy, channel, PAN ID, device count, join mode, power level)

**Mesh Quality** — Average LQI (Link Quality Indicator), min/max, weak neighbors (LQI < 150), stale neighbors (age ≥ 7 rounds).

**Neighbor Detail table** — Short ID, LQI (color-coded), age (stale badge at age 7), In Cost, Out Cost.

**Zigbee–WiFi Spectrum Chart** — Visual overlay of Zigbee channels and 2.4 GHz WiFi channels, showing potential interference. Recommended Zigbee channels (15, 20, 25) avoid overlap with WiFi channels 1, 6, and 11. The chart fills the full container width and re-renders on browser resize.

**Live channel scan** (`/hub/zigbeeChannelScanJson`) — Click **Run channel scan** to perform a real Zigbee scan (~15–30 s, briefly impacts Zigbee join activity). The scan detects other Zigbee networks (PANs) operating in the surrounding RF environment and overlays them on the spectrum chart as colored bars at each channel position (red = strong, orange = medium, yellow = weak; bar height proportional to RSSI strength). Tooltip on hover shows PAN ID, RSSI, LQI. Scan results are cached in app state so subsequent page loads display the last-known scan without re-running it.

Below the chart, scan results are summarized as text grouped by interference level:
- **Strong on:** channels with detected PANs at RSSI > -50 dBm
- **Medium on:** channels with PANs at RSSI -50 to -70
- **Weak on:** channels with PANs at RSSI < -70
- **No detections on Zigbee channels:** the quiet channels — best candidates if changing channel

The user's own channel is annotated with "(your channel)" when it appears in the scan.

### Z-Wave

- Radio status (enabled, healthy, firmware version, region, node count)
- **Zip Gateway** version (`/hub/advanced/zipgatewayVersion`) — the Z-Wave SDK version
- Firmware update alert if a radio firmware upgrade is available
- Compatible with both the legacy Z/IP stack and the Z-Wave JS stack

**Ghost Nodes** — Z-Wave radio nodes flagged by any of: no associated Hubitat device ID (primary signal — the device was deleted without Z-Wave exclusion), node in FAILED state, or (no route AND unknown name). Each ghost shows the signals that triggered it: `no device`, `FAILED`, `no route`, `unknown name`. These should be removed from the Z-Wave mesh.

**Problem Nodes** — Nodes with state ≠ OK or packet error rate > 1%.

**Mesh Quality table** columns: Device, Security (S0/S2/None — S0 on non-lock devices is flagged), RTT (ms), RSSI (dBm), PER %, Neighbors, Route, Route Changes, Messages, Driver (Built-in/User), State.

**Message Counts table** — Devices ranked by message volume, with messages/minute color-coded against the chatty device threshold.

**S0 Security flag** — Devices paired with S0 security that are not locks or garage doors are highlighted. S0 generates ~3× the mesh traffic of S2; re-pairing with S2 improves mesh performance.

#### Z-Wave JS Controller (Z-Wave JS hubs only)

Renders only when the Z-Wave JS stack is detected (via `/hub/zwave2/status`). Sources data from `/hub/zwave2/getControllerState`:

- Controller firmware, SDK version, Home ID, own node ID, Primary / SUC / SIS-present flags, Long Range support
- "Rebuilding routes" warning when active
- **Statistics chips:** TX, RX, dropped TX/RX, CAN, NAK, timeout ACK/Callback/Response — non-zero error counters are colored warn
- **Background RSSI** per channel (channel0–3): current dBm with average in parentheses, colored by quietness (lower RSSI = quieter, better)

#### Z-Wave Topology

Pairwise neighbor adjacency matrix as reported by the Z-Wave controller (`/hub/zwaveTopology`), showing connectivity between each node pair. The card is hidden when no Z-Wave nodes are paired.

### Matter

Enabled / installed flags, network state, fabric ID, device count.

### Hub Mesh

Enabled status, shared/linked device and variable counts. Table of peer hubs with name, IP, online status, and their device/variable counts.

### mDNS Discovery

Lists devices visible to the hub via mDNS / Bonjour / Avahi (`/hub/mdnsDevices/json`). Sortable + filterable table with Service (e.g. `airplay._tcp`, `hap._tcp`, `lutron._tcp`), Name, IP (clickable HTTP link), Port, MAC, Server, Model, Last Updated. Useful for confirming HomeKit/AirPlay/Lutron/Chromecast devices are reachable.

---

## Health Tab

**Alerts** — All active platform and calculated alerts plus messages from `/hub/messages` (info-severity, blue). Shows a green checkmark when there are none.

**System Resources** — Free OS memory, CPU load (5-min avg), **Processors** (count, from `/hub/cpuInfo`), **Load Avg (1m)** (from `/hub/cpuInfo`), **Hub Load Threshold** (% from `/hub/advanced/getExcessiveLoadThreshold` — the level Hubitat itself considers "excessive"), Java heap usage (total/free/direct), temperature (°C and °F), and database size. Values are color-coded against configured thresholds. This card **auto-refreshes** in the background on a configurable interval (default 30 s); the last refresh time is shown in the card header.

**Resource History** — Time-series chart of free OS memory and CPU load over recent checkpoints. Horizontal reference lines mark the warning and critical memory thresholds. Re-renders on browser resize.

**Database & Storage** — Database size, state compression status, max events per device, max event age (days), max state age (days). A separate **File Manager** sub-section shows the total number of files stored in the hub's File Manager, total bytes used, and free storage space.

**Backups** (`/hub2/localBackups` + `/hub2/cloudBackups`) — Local backup count + latest backup timestamp + age in days (orange-warn when > 2 days, red-crit when 0 backups). Cloud backups for this hub plus an expandable list of cloud backups for other hubs on the same Hubitat account. Cloud backup and restore entitlement flags.

---

## Performance Tab

### Compare Performance (top card)

The **Compare Performance** card sits at the top of the tab and acts as a mode switch for all cards below. Select a baseline (hub startup or any saved checkpoint) and a comparison point (now or any checkpoint), then click **Compare**. While a comparison is active, all runtime and resource cards switch from showing current values to showing deltas since the baseline.

Directly below it, the **Perf Checkpoints** list (collapsed by default) shows all saved checkpoints with timestamps; individual checkpoints can be deleted.

### Current Runtime Statistics

When no comparison is active, these cards show current values:

**Runtime** — Hub uptime, device runtime %, app runtime %.

**Resources** — Free OS memory and CPU load (5-minute average). This card **auto-refreshes** in the background on a configurable interval (default 30 s) when no comparison is active; the last refresh time is shown in the card header.

**Top Talkers** — The 3 most message-active Z-Wave and Zigbee devices by total message count since last restart.

**App Runtime Detail** — Sortable table of every app with: total execution time (ms), % busy, execution count, average time per call, state size, source badge (Platform/Community/Built-in).

**Device Runtime Detail** — Same structure for device drivers.

**Radio Message Counts** — Per-device message counts and msgs/min for Z-Wave and Zigbee. Devices exceeding the chatty device threshold are highlighted with a critical alert banner; devices in the warn band (60% of threshold) show a warning banner.

### Performance Checkpoints

A checkpoint captures a point-in-time snapshot of runtime statistics, resources, radio message counts, hub temperature, and database size. Use checkpoints to compare performance before and after changes (new app, added devices, firmware update, etc.).

- **Take Perf Checkpoint** — manually capture the current state
- **Auto-checkpoints** — optionally schedule automatic captures (5m–24h intervals, up to 50 retained)

---

## Snapshots Tab

A configuration snapshot captures the state of devices, apps, network configuration, hub metadata, and file storage at a point in time. Snapshots are used to detect configuration drift — what changed between two points in time.

**Taking a snapshot** — Click **Take Config Snapshot**, or enable auto-snapshots on a schedule (interval 1–30 days, up to 50 retained).

**Snapshot table** — Lists all saved snapshots with timestamp, firmware version, device count, app count, and free memory. Individual snapshots can be viewed or deleted.

**Viewing a snapshot** — Shows a full breakdown at the time of capture: device counts by status, connection types, integrations, full device list, app counts, app type list, user app instances (with disabled status), parent/child hierarchy, network configuration summary (Zigbee channel, Z-Wave region, Hub Mesh peers, Matter status), file manager stats, backup counts, security settings (limited access, allowed subnets, DNS fallback, cloud controller), NTP server, hub load threshold, and code inventory (bundles, libraries, hub variable names + types).

**Snapshot diff (Compare)** — Select an older and a newer snapshot (or choose **Now** to capture a new one on the spot) and click **Compare**. After comparing with Now, the snapshot list and dropdowns refresh in place while the diff remains visible. The diff shows:

- Firmware version changes
- Devices added, removed, or changed (connection type, integration, status)
- Connection type count deltas
- Integration count deltas
- Apps added, removed, or toggled enabled/disabled
- Network configuration changes (Zigbee channel, Z-Wave region, Hub Mesh peers added/removed, Matter enabled/disabled, NTP server, load threshold)
- Security changes (limited access on/off, allowed addresses, allowed subnets, DNS fallback, cloud controller)
- File Manager changes (file count, free space)
- Backup count changes (local and cloud)
- Code inventory changes (bundles added/removed, libraries added/removed/version-changed, hub variables added/removed/type-changed)

Changes are color-coded: green for additions/improvements, red for removals/degradations, yellow for modifications.

---

## Radio Capture Tab

Live capture of radio traffic from the hub's internal log sockets, intended for mesh troubleshooting. **Zigbee** capture (via `ws://${hub_ip}/zigbeeLogsocket`) is fully featured. A **Z-Wave** sub-tab (via `ws://${hub_ip}/zwaveLogsocket`) provides capture, live tail, and download; it auto-detects the active Z-Wave stack and picks a per-stack renderer. On the **Z/IP (legacy)** stack the tail shows the per-message IME radio telemetry — **Time · Node · Device · Type · RSSI · Seq** (RSSI per hop, in dBm). On the **Z-Wave JS** stack frames are shown raw for now (field mapping pending a sample). Aggregates and command-level decoding are not yet built for Z-Wave; the recording is full-fidelity raw lines on both stacks. The capture controls, recording cap, pause/stop/clear/download semantics, and the click-to-expand raw JSON behaviour match the Zigbee sub-tab described below.

**Capture controls** — in order: Start capture, Pause capture, Stop capture, Download, Clear buffer.

- **Start capture** opens a WebSocket to the hub's `/zigbeeLogsocket` endpoint directly from your browser. Each click begins a fresh capture — any prior buffer is discarded, so Download first if you want to keep it. All frames are buffered client-side; the hub does not relay or transform anything. Closing the browser tab ends the capture and discards the buffer.
- **Pause capture** toggles ingest. While paused, incoming frames are dropped and the frame-rate counter falls to zero; click again to resume. The recording buffer is preserved across pause.
- **Stop capture** closes the socket but keeps the recording buffer, so Download remains useful afterwards.
- **Download** is enabled as soon as the buffer has any frames (running or stopped). It saves the recording as a JSON-lines file (`zigbee-capture-<hub>-<timestamp>.jsonl`) compatible with `scripts/zigbee-ota-analyser.py`. The first line is a `#`-prefixed JSON metadata header that the analyser silently skips.
- **Clear buffer** empties the recording buffer without disconnecting.
- **Recording cap** selects the recording buffer's byte cap (10 / 50 / 200 MB). When the buffer is full, oldest frames are dropped and the "Dropped: N" counter advances so you know the capture is no longer complete from `t0`.

**Live tail** shows every captured frame (newest first) inside a scrollable, fixed-height panel — the visible area is bounded but the buffer is not, so older frames are reachable by scrolling. Columns are **Time · Name · DNI · LQI · RSSI · Cluster · Command · Decoded**. The **DNI** column renders the 16-bit short address as `0xHHHH`; the **Name** column links to that device's edit page on the hub when the frame carries a Hubitat device id. The **Command** column decodes the ZCL command to a friendly name (e.g. `Report Attributes (0x0A)`, `On (0x01)`) and folds in a direction arrow — `←hub` for device→hub frames, `→dev` for hub→device; a `(mfr)` marker flags manufacturer-specific frames (whose command id is read at the correct offset). The **Decoded** column shows the human-readable payload: reported/read attributes as `Name=value` (e.g. `OnOff=true`, `MeasuredValue=2348`) and Default Responses as `Command→STATUS`; hover the cell for the full form with attribute ids and ZCL data types. Values are shown raw (no unit scaling). Click any row to expand a decoded breakdown — frame-control flags (global/cluster-specific, direction, manufacturer-specific, default-response-disabled), the command, and the attribute/response detail — above the pretty-printed raw JSON (which carries the raw payload bytes); click again to collapse.

**Filter** — a single box (like the table filter used elsewhere in the SPA) substring-matches, case-insensitively, against everything the row shows: name, DNI, cluster, command (decoded name + direction), decoded value, LQI, and RSSI. Space-separated terms must all match, so `kitchen onoff` or `0402 report` narrow progressively. It applies only to the live tail view — the recording buffer is always full-fidelity.

**Aggregates** are derived live from the recording buffer at 1 Hz:

- **Top talkers** in 1-min and 5-min trailing windows. Device names link to their edit page when known.
- **Cluster breakdown** for the last 5 min.
- **Signal quality** for the last 5 min — per-device last-hop **LQI** and **RSSI** (min / avg / max + sample count) in two tables, sorted weakest-first so devices with poor links surface at the top. Device names link to their edit page when known.
- **OTA progress** per device when cluster `0x0019` traffic is present — manufacturer code, frame count, block-request count, last command, last seen. Device names link to their edit page when known.

When the recording buffer rolls past a window (high traffic + small cap), the affected aggregate shows a "Window truncated to last N s of buffer" warning so the metric isn't silently misreported.

---

## Device Usage Audit

Generates a one-time, per-device cross-reference report covering:

- **Unreferenced devices** — no apps subscribe to them, no dashboards display them, no parent integration manages them; cleanup candidates.
- **Mesh orphans** — Hubitat reports `orphan: true` (radio/network state).
- **Stuck scheduled jobs** — `nextRunTime` in the past, with a "Last run" (`prevRunTime`) column to disambiguate "never ran" vs "ran once and lingered".
- **Manually-tuned devices** — devices with non-default `spammyThreshold`, `maxStates`, or `maxEvents` values. The audit detects the fleet's mode value for each setting and highlights divergent devices in bold.
- **Critical devices** — top 20 by combined apps + dashboards reference count.
- **Devices by Room** — devices grouped by their assigned room (sourced from `/hub2/roomsList`). Surfaces empty rooms (cleanup targets) and high-density rooms (split candidates). Hubitat provides a synthetic "Unassigned" room for devices not assigned anywhere.
- **Z-Wave JS Mesh Health** (Z-Wave JS hubs only) — per-Z-Wave-device row from `/hub/zwave2/getNodeState?node=N`: state, status, interview stage, RTT, RSSI, PER %, TX/RX command counts, last-seen timestamp.
- **Hub Mesh Linked Devices** — for each device this hub consumes from another hub via Hub Mesh, source hub + source device ID + status from `/hubMesh/localLinkedDevice/<id>`.
- **Apps → devices** and **Dashboards → devices reverse indices** — disabled app subscribers are rendered with strikethrough so "ghost references" stand out.
- **Device inventory** — every device's hardware identity: protocol, manufacturer, model, and firmware revision, parsed from each device's pairing-time data values (no extra hub calls — it rides on the scan the audit already runs). Sortable and filterable, with **Download CSV** / **Copy CSV** export for documentation or firmware tracking. Z-Wave manufacturer/model appear as hex IDs (shown verbatim); virtual and cloud devices have blank cells.
- **Per-device detail table** with all subscribers as clickable links. Type cells link to `/driver/editor/<id>` for community drivers.

### How it works

The scan crawls every device via `/device/fullJson/{id}` — one call per device, throttled to the Hubitat platform's 8-concurrent-async-call cap. On a 350-device hub this takes ~30–60 s. After the main scan it adds room assignments, Z-Wave JS per-node detail (on JS hubs), and Hub Mesh linked-device detail. The result is held in memory (the single most recent audit) and rendered live in the browser when you open the **View report** link — it is **not** written to a file and does not survive a hub restart. Re-run the audit to regenerate it.

### Trigger

**Devices tab → Device Audit card → Generate Device Audit**. Progress is polled live; the "View report" link appears on completion and opens the report rendered from the latest in-memory result.

### Limitations

- The scan is one-shot; subscriptions can change between audits. Re-run for fresh data.
- A single device fetch failure ratio above 10 % marks the scan as `error` instead of `done` (a partial result is still retained in memory).

---

## App Settings Tab

Most settings are accessible from the Hubitat admin UI under **Apps → Hub Diagnostics → Preferences**. Three settings are available only through the dashboard's App Settings tab and are not shown in the Hubitat admin UI: **Auto-refresh interval** (Live Data section), **Obfuscate labels in forum export** (Export section), and **Clear Enrichment Cache** (Maintenance section).

### Config Snapshot Scheduling
- Enable automatic snapshots: on/off
- Interval: 1–30 days (default 1)
- Max snapshots to retain: 1–50 (default 10; oldest are pruned when the limit is reached)
- On-demand trigger switch (optional): pick a switch; turning it ON captures a snapshot immediately

### Perf Checkpoint Scheduling
- Enable automatic checkpoints: on/off
- Interval: 5m / 15m / 30m / 1h / 6h / 12h / 24h
- Max checkpoints to retain: 1–50 (default 10)
- On-demand trigger switch (optional): pick a switch; turning it ON records a checkpoint immediately

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

### Live Data
- **Auto-refresh interval** — How frequently the Resources cards on Dashboard, Health, and Performance tabs update in the background (10–300 seconds, default 30). Changes take effect immediately on save without a page reload.

### Logging
- **Debug logging** — Enables verbose logging in the Hubitat Logs page. Useful for troubleshooting; leave off during normal use.

### Export
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
- App inventory: counts by source; list of user-installed app types
- Z-Wave: health, ghost nodes, problem nodes, S0 flag, mesh quality stats, top talkers
- Zigbee: health, channel, LQI stats, weak/stale neighbors, top talkers
- Hub Mesh and Matter status

**Obfuscation:** Enable "Obfuscate labels in forum export" in App Settings to replace device names with driver types, so you can share diagnostics publicly without revealing device names or room labels.

---

## Alerts & Warnings Reference

Severity levels: **Critical** (red), **Warning** (orange), **Info** (blue), **OK** (green).

The **alert-aware favicon** and the Dashboard *Platform Alerts* / Health *Alerts* lists share one roll-up: System Resource Alerts, Platform Alerts, Hub Messages, Ethernet + WiFi, the Z-Wave ghost / failed / problem-node counts, Z-Wave radio-firmware-update, hub-firmware-update, and app-update status. The remaining entries below — Matter reboot, the per-mesh quality metrics (Avg PER / RSSI, S0 overhead, Zigbee LQI), and the device-inventory alerts (low battery, stale devices, chatty devices) — are surfaced on their own tabs and do **not** affect the favicon or the *Alerts* count. The Performance tab's chatty-device banner is a separate, browser-computed metric (per-device messages/min vs the chatty-device threshold) and is distinct from the hub's *Spammy Devices Detected* flag; only the hub flag feeds the roll-up.

### Hub Messages
| Alert | Source | Severity |
|---|---|---|
| Hub messages from `/hub/messages` | Hubitat platform admin notifications | Info |

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
| Ghost Nodes | No associated device ID (orphaned in the radio), or no route + unknown name | — | Critical |
| Failed Nodes | Has a Hubitat device but the radio reports it FAILED / down | — | Warning |
| Problem Nodes | State ≠ OK, or PER > 1% | 1% PER | Warning |
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

The app exposes a REST API consumed by the dashboard SPA. It is not a stable public contract — routes are added and renamed as the SPA evolves. All endpoints require the OAuth `access_token` query parameter.

Base URL: `http://{hub-ip}/apps/api/{app-id}/`

The canonical list of routes (with HTTP methods and handler names) is the `mappings { }` block in `HubDiagnostics.groovy` (search for `mappings {`). It groups routes into aggregator GETs, app-owned GETs, app-owned mutations, long-running orchestration, and side-effectful network actions.

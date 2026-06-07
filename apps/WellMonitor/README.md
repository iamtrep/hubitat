<!--
Copyright (c) 2025-2026 PJ
SPDX-License-Identifier: MIT
-->

# Well Monitor

A Hubitat Elevation app that monitors a residential well: pump cycles (via power metering on the pump switch), downstream water flow events (via a flow-rate meter), tank usage between cycles, daily/hourly consumption patterns, and emergency shutoff. CSV-logged history with a self-hosted SPA dashboard served via OAuth.

The flow meter sits *downstream* of the pressure tank, so it measures household consumption — not pump output. The dashboard distinguishes between "downstream consumption" (real water used) and "coincident flow during pump cycles" (consumption that overlapped with a refill).

## Features

### Pump Cycle Tracking
- Detects pump start/stop via configurable power thresholds on a metered switch
- Records each cycle's duration, coincident downstream flow, and the meter readings at start/end
- Tracks **tank usage** (consumption between pump cycles) by comparing meter readings — the working volume of the pressure tank
- Maintains a rolling 100-cycle in-memory history plus persistent CSV logs

### Water Flow Tracking
- Independently tracks water flow events via the meter's rate attribute
- Detects flow start (rate > 0) and flow stop (rate = 0) transitions
- Records flow duration and volume delivered per event
- Rolling 100-event in-memory history plus persistent CSV log

### Emergency Shutoff
- Automatically turns off the pump switch if it runs beyond a configurable timeout
- Defense-in-depth: both scheduled callback and power-event-driven check
- Activates an optional virtual leak sensor (for integration with other automations)
- Sends push notifications on shutoff

### Hub Reboot Recovery
- Subscribes to `systemStart` so it re-evaluates pump state after a hub restart
- Reschedules the emergency timer for the remaining time, or triggers immediate shutoff if the timeout already passed
- Handles the case where the pump stopped while the hub was down

### Statistics & Aggregation
- **All-time statistics** from running counters (O(1) computation):
  - Total cycles, total downstream consumption, total tank usage, total pump duration
  - Mean and standard deviation for cycle duration and coincident flow (via sum-of-squares)
  - Cycles per day, longest/shortest runs
- **Recent window statistics** from the 100-entry history:
  - Median/mean/stddev for duration and coincident flow
  - Mean tank working volume (between-cycle draw)
- **Daily summaries**: flow-event volumes grouped by day
- **Hourly distribution**: pump cycles bucketed by hour of day (0-23)
- Migrations are idempotent and run on every initialization until no work remains

### Web Dashboard
- Single-page HTML/JS dashboard served through the app's own OAuth endpoints
- **Overview tab** has 24h/7d toggles on three charts:
  - Flow Events (scatter: time × volume, point size = √duration)
  - Pump Cycle Events (mirror scatter for cycles)
  - Hourly Consumption (bar)
- Plus the long-running views: Daily Water Consumption, Pump Cycles by Hour of Day
- **Cycles tab**: sortable table of all recorded pump cycles with tank usage
- **Flow tab**: sortable table of all flow events
- **Statistics tab**: all-time and recent-window stats with sample-size annotations
- Live status updates via the hub's `/eventsocket` WebSocket, with a 60-second slow-poll safety net for dropped sockets and a tab-hidden pause
- Update banner when a newer app version is published on GitHub
- CSV full-history loading via button click
- Responsive mobile-first layout
- Charts rendered with [Chart.js](https://www.chartjs.org/)

### Dashboard Auto-Sync
- On first install and on every "Done" save, the app fetches its dashboard HTML from GitHub and writes it to File Manager — no manual upload step required
- Validates the downloaded file (must contain the app name AND a `CODE_VERSION` matching the app's) before overwriting the local copy
- Daily scheduled re-check at 03:17 local for slow drift catch-up
- An in-SPA **Sync UI from GitHub** button forces an immediate refresh from the dashboard itself
- Emergency recovery: if `/dashboard` is requested and the local file is missing, a blocking GitHub fetch is attempted before failing

### CSV Logging
- Pump cycles: `datetime, duration_s, coincident_flow_L, coincident_lpm, vol_at_start, vol_at_end`
- Flow events: `datetime, duration_s, volume_L`
- Persistent hub file storage with configurable file names
- Downloadable via the dashboard or API endpoints

## Requirements

### Devices
- **Pump switch with power metering** (required) -- a switch with the `powerMeter` capability that controls and monitors the well pump (e.g., Zooz ZEN15 with a Grundfos submersible pump)
- **Water meter** (required) -- a device with `liquidFlowRate` capability that reports volume and rate attributes (e.g., Sinope VA4220ZB)

### Optional Devices
- **Virtual pump active switch** -- toggled on/off to reflect pump state (for dashboards or rules)
- **Emergency leak sensor** -- virtual water sensor activated on emergency shutoff (for triggering alerts)
- **Water flow indicator switch** -- toggled on/off to reflect water flow state
- **Notification devices** -- for push notifications on emergency shutoff

## Installation

### 1. Install the App Code

In the Hubitat web UI:
1. Go to **Apps Code** > **New App** > **Import**
2. Paste the import URL:
   `https://raw.githubusercontent.com/iamtrep/hubitat/refs/heads/main/apps/WellMonitor/WellMonitor.groovy`
3. Click **Save**

### 2. Enable OAuth

In the app code editor (required for the dashboard):
1. Click **OAuth** > **Enable OAuth in App**
2. Click **Update**

### 3. Upload Chart.js

Go to **Settings** > **File Manager** and upload:
- `wellpump-chart.min.js` -- Chart.js 4.x UMD bundle, download from [jsdelivr](https://cdn.jsdelivr.net/npm/chart.js@4/dist/chart.umd.min.js) and rename

(The dashboard HTML is fetched automatically from GitHub on first save -- no manual upload required.)

### 4. Create App Instance

1. Go to **Apps** > **Add User App** > **Well Monitor**
2. Select your pump switch and water meter
3. Configure thresholds and options (sensible defaults are provided)
4. Click **Done**

The **Dashboard** link will appear at the top of the app's configuration page.

## Configuration

### Power Thresholds

| Setting | Default | Description |
|---|---|---|
| Power ON threshold | 100W | Power above this means the pump is running |
| Power OFF threshold | 10W | Power below this means the pump is off |

The OFF threshold must be lower than the ON threshold. The gap between them provides hysteresis to avoid false transitions from power fluctuations.

### Emergency Shutoff

| Setting | Default | Description |
|---|---|---|
| Enable emergency shutoff | true | Auto-turn-off if pump runs too long |
| Emergency timeout | 300s | Maximum allowed pump run time |

### CSV Logging

| Setting | Default | Description |
|---|---|---|
| Enable CSV logging | true | Write pump cycles and flow events to files |
| Pump CSV file name | pumpCycles.csv | File name for pump cycle log |
| Flow CSV file name | waterFlow.csv | File name for flow event log |

### Logging

Three boolean preferences in the project standard pattern:

| Preference | Default | Description |
|---|---|---|
| `txtEnable` | true | descriptionText / info-level operational messages |
| `debugEnable` | false | Verbose diagnostic output. Auto-disables after 30 minutes. |
| `traceEnable` | false | Very chatty per-event tracing. Only visible when `debugEnable` is on; auto-disables with debug. |

## API Endpoints

The app exposes the following endpoints via OAuth-secured mappings. The access token is required as a query parameter (`?access_token=...`).

| Endpoint | Content-Type | Description |
|---|---|---|
| `/dashboard` | text/html | Web dashboard UI |
| `/chart.js` | application/javascript | Chart.js library |
| `/api/status` | application/json | Live pump/flow state, power, volume |
| `/api/cycles` | application/json | Cycle history (100 entries) with derived tank usage |
| `/api/flow` | application/json | Flow history (100 entries) |
| `/api/stats` | application/json | All-time + recent stats, daily summaries, hourly distribution, 24h/7d windows |
| `/api/version` | application/json | Installed `CODE_VERSION`, latest version on GitHub, updateAvailable flag |
| `/csv/cycles` | text/csv | Full pump cycle CSV file |
| `/csv/flow` | text/csv | Full flow event CSV file |

## File Structure

<!-- AUTO:wellmonitor-files -->
```
apps/WellMonitor/
  WellMonitor.groovy            # Hubitat app (Groovy)
  wellmonitor-dashboard.html    # Web dashboard (HTML/CSS/JS)
```
<!-- /AUTO -->

On the hub's File Manager:
```
wellmonitor-dashboard.html      # Dashboard HTML (auto-fetched from GitHub)
wellpump-chart.min.js           # Chart.js UMD bundle (uploaded manually)
pumpCycles.csv                  # Pump cycle log (created automatically)
waterFlow.csv                   # Flow event log (created automatically)
```

## License

MIT License. See source file header for full text.

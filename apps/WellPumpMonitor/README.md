# Well Pump Monitor

A Hubitat Elevation app that monitors a well pump via power metering, tracks water consumption using a flow meter, logs pump cycles to CSV, and provides emergency shutoff protection. Includes a web-based dashboard with charts and statistics.

## Features

### Pump Cycle Tracking
- Detects pump start/stop via configurable power thresholds on a metered switch
- Records each cycle's duration, volume pumped, and average flow rate
- Tracks **tank usage** (water consumed between pump cycles) by comparing meter readings
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

### Flow Alerts
- Warns when the pump runs longer than expected for the observed flow rate
- Dynamic alert threshold: base time + bonus seconds for each flow-rate threshold exceeded
- Detects potential dry-run or pipe-blockage conditions

### Hub Reboot Recovery
- On initialization, checks if the pump was running before a hub restart
- Reschedules the emergency timer for the remaining time, or triggers immediate shutoff if the timeout already passed
- Handles the case where the pump stopped while the hub was down

### Statistics & Aggregation
- **All-time statistics** from running counters (O(1) computation):
  - Total cycles, total volume, total pump duration
  - Mean and standard deviation for cycle duration and volume (via sum-of-squares)
  - Cycles per day, longest/shortest runs
- **Recent window statistics** from the 100-entry history:
  - Median, mean, and standard deviation for duration and volume
- **Daily summaries**: cycles, volume, and duration grouped by day
- **Hourly distribution**: pump cycles bucketed by hour of day (0-23)
- One-time migration seeds counters from existing history on upgrade

### Web Dashboard
- Single-page HTML/JS dashboard served through the app's own OAuth endpoints
- **Overview tab**: status cards, daily consumption chart, hourly distribution chart
- **Cycles tab**: sortable table of all recorded pump cycles with tank usage
- **Flow tab**: sortable table of all flow events
- **Statistics tab**: all-time and recent-window stats, duration trend chart, daily summaries table
- Auto-refreshing pump status (every 10 seconds)
- CSV full-history loading via button click
- Responsive mobile-first layout
- Charts rendered with [Chart.js](https://www.chartjs.org/)

### CSV Logging
- Pump cycles: `datetime, duration_s, volume_L, avg_lpm, vol_at_start, vol_at_end`
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
- **Notification devices** -- for push notifications on emergency shutoff and flow alerts

## Installation

### 1. Install the App Code

In the Hubitat web UI:
1. Go to **Apps Code** > **New App**
2. Paste the contents of `WellPumpMonitor.groovy`
3. Click **Save**

### 2. Enable OAuth

In the app code editor (this is required for the dashboard):
1. Click **Enable OAuth**
2. Click **Update** (then **Save** again if prompted)

### 3. Upload Dashboard Files

Go to **Settings** > **File Manager** and upload:
- `wellpump-dashboard.html`
- `wellpump-chart.min.js` -- Chart.js 4.x UMD bundle, download from [jsdelivr](https://cdn.jsdelivr.net/npm/chart.js@4/dist/chart.umd.min.js) and rename

### 4. Create App Instance

1. Go to **Apps** > **Add User App** > **Well Pump Monitor**
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

### Flow Alerts

| Setting | Default | Description |
|---|---|---|
| Enable flow alerts | true | Notify on no-flow/low-flow conditions |
| Base alert time | 40s | Minimum run time before alerting with no flow |
| Flow threshold 1 | 10 LPM | First flow rate tier |
| Flow threshold 2 | 20 LPM | Second flow rate tier |
| Flow threshold 3 | 30 LPM | Third flow rate tier |
| Bonus seconds per tier | 10s | Additional time allowed per threshold exceeded |

The alert time is dynamic: `baseTime + (bonusSeconds * thresholdsExceeded)`. A pump running at 25 LPM exceeds thresholds 1 and 2, giving `40 + 20 = 60s` before an alert fires.

### CSV Logging

| Setting | Default | Description |
|---|---|---|
| Enable CSV logging | true | Write pump cycles and flow events to files |
| Pump CSV file name | pumpCycles.csv | File name for pump cycle log |
| Flow CSV file name | waterFlow.csv | File name for flow event log |

### Logging

| Level | Description |
|---|---|
| warn | Warnings and errors only |
| info | Normal operational messages (default) |
| debug | Verbose diagnostic output |

## API Endpoints

The app exposes the following endpoints via OAuth-secured mappings. The access token is required as a query parameter (`?access_token=...`).

| Endpoint | Content-Type | Description |
|---|---|---|
| `/dashboard` | text/html | Web dashboard UI |
| `/chart.js` | application/javascript | Chart.js library |
| `/api/status` | application/json | Live pump/flow state, power, volume |
| `/api/cycles` | application/json | Cycle history (100 entries) with derived tank usage |
| `/api/flow` | application/json | Flow history (100 entries) |
| `/api/stats` | application/json | All-time stats, recent stats, daily summaries, hourly distribution |
| `/csv/cycles` | text/csv | Full pump cycle CSV file |
| `/csv/flow` | text/csv | Full flow event CSV file |

## File Structure

```
apps/WellPumpMonitor/
  WellPumpMonitor.groovy      # Hubitat app (Groovy)
  wellpump-dashboard.html     # Web dashboard (HTML/CSS/JS)
  README.md                   # This file
```

On the hub's File Manager:
```
wellpump-dashboard.html       # Dashboard HTML (uploaded)
wellpump-chart.min.js         # Chart.js UMD bundle (uploaded)
pumpCycles.csv                # Pump cycle log (created automatically)
waterFlow.csv                 # Flow event log (created automatically)
```

## License

MIT License. See source file header for full text.

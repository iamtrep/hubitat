# Hubitat

Personal collection of apps, drivers, and integrations for the [Hubitat Elevation](https://hubitat.com/) home automation platform.

## Apps

| App | Description |
|-----|-------------|
| **Attribute Logger** | Logs selected device attributes to CSV files (parent/child) |
| **Battery Change Logger** | Tracks battery levels and logs replacement history |
| **Humidity-Based Fan Controller** | Controls a bathroom fan using a humidity state machine |
| **Hydro-Québec Peak Period Manager** | Manages thermostats during Hydro-Québec peak demand periods |
| **Location Event Mapper** | Maps hub location events to virtual contact sensors for Rule Machine (parent/child) |
| **Startup and Shutdown Monitor** | Toggles a virtual contact sensor on hub lifecycle events |

### Sensors

| App | Description |
|-----|-------------|
| **Sensor Aggregator** | Aggregates numeric or discrete sensor values into a single virtual device (parent + continuous/discrete children) |
| **Sensor Filter** | Applies moving average or median filters to sensor attributes (parent/child) |

### Utilities

| App | Description |
|-----|-------------|
| **Device "in use by" Enumerator** | HTML report of which apps reference each device |
| **Device Replacement Helper** | Replaces one device with another across all installed apps |
| **Rule Tracker** | Monitors Rule Machine 5.0 rules for subscription/schedule changes |

See the detailed READMEs in [`apps/`](apps/README.md), [`apps/sensors/`](apps/sensors/README.md), [`apps/utilities/`](apps/utilities/README.md), and [`apps/WellPumpMonitor/`](apps/WellPumpMonitor/README.md).

### Well Pump Monitor

Monitors a well pump via power metering, tracks water flow, logs pump cycles to CSV, provides emergency shutoff, and serves a web dashboard. See [`apps/WellPumpMonitor/README.md`](apps/WellPumpMonitor/README.md).

### Test & Stress Test Apps

Async HTTP, File Manager API, UDP, and hub stress tests. See [`apps/tests/README.md`](apps/tests/README.md).

## Drivers

### Standalone

| Driver | Description |
|--------|-------------|
| **Awair Element** | Local API driver for Awair Element air quality monitors |
| **BTHome v2 Motion/Occupancy Sensor** | BLE motion/occupancy sensor via BTHome v2 |
| **Device Inspector** | Diagnostic driver that dumps DeviceWrapper properties to logs |
| **Device Ping** | Pings a device and reports connectivity as a contact sensor |
| **Ecobee Companion** | Advanced Ecobee thermostat control via OAuth API |
| **Generic WebSocket Test** | Development driver for testing WebSocket connections |
| **IKEA Window Blinds** | Zigbee driver for IKEA window blinds |
| **Log Event Monitor** | Monitors the hub log stream and fires events on pattern matches |
| **Xfinity Contact Sensor** | Zigbee contact sensor with battery, tamper, and temperature |

### Sinopé

| Driver | Description |
|--------|-------------|
| **Sinope Dimmer (DM2500ZB)** | Zigbee dimmer switch |
| **Sinope Switch (SW2500ZB)** | Zigbee on/off switch |
| **Sinope Thermostat (TH13X0ZB)** | Zigbee thermostat |
| **Sinope Water Valve (VA422xZB)** | Zigbee water valve with optional flow sensor support |

### Stelpro

| Driver | Description |
|--------|-------------|
| **Stelpro Allia Zigbee Thermostat** | Zigbee thermostat for Allia / Stello Hilo HT402 |
| **Stelpro Ki ZigBee Thermostat** | Zigbee thermostat for Stelpro Ki |

## Integrations

### VisiblAir

Parent/child integration for [VisiblAir](https://visiblair.com/) indoor air quality sensors. The manager app auto-discovers sensors and creates child devices using model-specific drivers.

| Component | Description |
|-----------|-------------|
| **VisiblAir Manager** | Parent app — discovery, bulk polling, firmware commands |
| **Sensor C** | CO₂, temperature, humidity |
| **Sensor E** | CO₂, temperature, humidity, VOC, pressure, PM, AQI |
| **Sensor O** | CO₂, temperature, humidity, VOC, PM, smoke/vape detection |
| **Sensor X** | Temperature, humidity, pressure, PM (1 / 2.5 / 4 / 10) |
| **Sensor XW** | Wind speed, direction, compass heading |

A [standalone single-device driver](drivers/visiblair/visiblair.groovy) is also available as an alternative to the full integration.

Files are in [`integrations/visiblair/`](integrations/visiblair/).

## Scripts

| Script | Description |
|--------|-------------|
| [`hubitat-app-backup.sh`](scripts/hubitat-app-backup.sh) | Back up installed app configurations (settings, state, subscriptions, jobs) |
| [`hydroquebec_peakevent.js`](scripts/hydroquebec_peakevent.js) | Google Apps Script — detect Hydro-Québec peak event emails and trigger a Hubitat switch |
| [`ws_to_file.sh`](scripts/ws_to_file.sh) | Stream a WebSocket to a file with optional timestamps and auto-reconnect |
| [`zigbee-log-analyser.py`](scripts/zigbee-log-analyser.py) | Analyze Zigbee log captures — cluster activity, attribute reports, device patterns |
| [`zigbee-ota-analyser.py`](scripts/zigbee-ota-analyser.py) | Analyze Zigbee OTA update traffic from log captures |

## License

MIT — see individual source file headers.

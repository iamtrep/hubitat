<!--
Copyright (c) 2025-2026 PJ
SPDX-License-Identifier: MIT
-->

# Drivers

Hubitat Elevation device drivers for Zigbee devices, BLE sensors, and cloud APIs.

## Standalone

<!-- AUTO:drivers-standalone -->
| Driver | Description |
|---|---|
| **Awair Element** | Local API driver for Awair Element air quality monitors |
| **Bluetooth Home v2 Motion/Occupancy Sensor** | BLE motion/occupancy sensor via BTHome v2 |
| **Ecobee Companion** | Advanced Ecobee thermostat control via OAuth API |
| **Environment Canada AQHI** | Air Quality Health Index driver using the Environment Canada GeoMet OGC API — current observations, hourly forecasts, and alerts |
| **IKEA Window Blinds** | Zigbee driver for IKEA window blinds |
| **IP Reachability Sensor** | Checks whether a host is reachable by ICMP ping and/or HTTP GET and reports it as a contact sensor |
| **Log Event Monitor** | Monitors the hub log stream and fires events on pattern matches |
| **Québec Air Quality Index (IQA)** | Hourly Québec air quality index (IQA) from the nearest provincial or Montréal monitoring station |
| **Universal Electronics / Visonic / Xfinity Contact Sensor** | Zigbee contact sensor with battery, tamper, and temperature |
| **Virtual Switch + PowerSource** | Virtual device with synced Switch and PowerSource capabilities for testing power outage detection |
<!-- /AUTO -->

## Subfolders

Vendor and utility driver groups. Each subfolder's own README lists its drivers.

<!-- AUTO:drivers-subfolders -->
| Folder | Description |
|---|---|
| [aqara/](./aqara/) |  |
| [sinope/](./sinope/) |  |
| [stelpro/](./stelpro/) |  |
| [tests/](./tests/) |  |
| [thirdreality/](./thirdreality/) |  |
| [visiblair/](./visiblair/) | Standalone single-device driver for a VisiblAir indoor air quality sensor |
| [zigbee_helpers/](./zigbee_helpers/) |  |
<!-- /AUTO -->

## License

MIT — see individual source files for the full license text.

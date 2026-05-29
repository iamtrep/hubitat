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
| **Device Ping** | Pings a device and reports connectivity as a contact sensor |
| **Ecobee Companion** | Advanced Ecobee thermostat control via OAuth API |
| **Environment Canada AQHI** | Air Quality Health Index driver using the Environment Canada GeoMet OGC API — current observations, hourly forecasts, and alerts |
| **IKEA Window Blinds** | Zigbee driver for IKEA window blinds |
| **Log Event Monitor** | Monitors the hub log stream and fires events on pattern matches |
| **ThirdReality Presence Sensor R3 (3RPL01084Z)** | 60 GHz mmWave presence sensor with RGB night light, illuminance, and TVOC air quality (Zigbee 3.0) |
| **Universal Electronics / Visonic / Xfinity Contact Sensor** | Zigbee contact sensor with battery, tamper, and temperature |
| **Virtual Switch + PowerSource** | Virtual device with synced Switch and PowerSource capabilities for testing power outage detection |
<!-- /AUTO -->

## Sinopé

<!-- AUTO:drivers-sinope -->
| Driver | Description |
|---|---|
| **Sinope Dimmer (DM2500ZB)** | Zigbee dimmer switch |
| **Sinope Switch (SW2500ZB)** | Zigbee on/off switch |
| **Sinope Thermostat TH13X0ZB DEV** | Zigbee thermostat |
| **Sinope Water Valve (VA422xZB)** | Zigbee water valve with optional flow sensor support |
<!-- /AUTO -->

## Stelpro

<!-- AUTO:drivers-stelpro -->
| Driver | Description |
|---|---|
| **Stelpro Allia Zigbee Thermostat** | Zigbee thermostat for Allia / Stello Hilo HT402 |
| **Stelpro Ki ZigBee Thermostat** | Zigbee thermostat for Stelpro Ki |
<!-- /AUTO -->

## Tests & Utilities

<!-- AUTO:drivers-tests -->
| Driver | Description |
|---|---|
| **Device Inspector** | Diagnostic driver that dumps DeviceWrapper properties to logs |
| **Generic WebSocket Test** | Development driver for testing WebSocket connections |
| **Log Event Monitor Test** | Companion test device for Log Event Monitor |
| **Virtual Flow Meter (Test)** | Virtual liquid-flow-rate device for testing Well Monitor flow tracking |
| **Virtual mmWave PIR Sensor** | Virtual device combining PIR + mmWave motion attributes for testing Motion Fusion |
| **Virtual Well Pump Switch (Test)** | Virtual switch + powerMeter device for testing Well Monitor pump cycle detection |
<!-- /AUTO -->

## VisiblAir

A standalone single-device driver for VisiblAir indoor air quality sensors lives at `visiblair/visiblair.groovy`. For multi-sensor setups, see the full [VisiblAir integration](../integrations/visiblair/).

## License

MIT — see individual source files for the full license text.

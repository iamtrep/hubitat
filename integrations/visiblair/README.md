<!--
Copyright (c) 2025-2026 PJ
SPDX-License-Identifier: MIT
-->

# VisiblAir Integration

Parent/child integration for [VisiblAir](https://visiblair.com/) indoor air quality sensors. The manager app auto-discovers sensors linked to your account and creates child devices using model-specific drivers.

## Components

<!-- AUTO:visiblair-components -->
| Component | Type | Description |
|---|---|---|
| **VisiblAir Manager** | App | Auto-discovers VisiblAir sensors and creates child devices with model-specific drivers |
| **VisiblAir Sensor C** | Driver | CO₂, temperature, humidity |
| **VisiblAir Sensor E** | Driver | CO₂, temperature, humidity, VOC, pressure, PM, AQI |
| **VisiblAir Sensor O** | Driver | CO₂, temperature, humidity, VOC, PM, smoke/vape detection |
| **VisiblAir Sensor X** | Driver | Temperature, humidity, pressure, PM (1 / 2.5 / 4 / 10) |
| **VisiblAir Sensor XW** | Driver | Wind speed, direction, compass heading |
<!-- /AUTO -->

## Standalone Alternative

A standalone single-device driver also exists at `drivers/visiblair/visiblair.groovy` if you only need to monitor one sensor without the full parent/child setup.

## License

MIT — see individual source files for the full license text.

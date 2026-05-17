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
| **VisiblAir Sensor C** | Driver |  |
| **VisiblAir Sensor E** | Driver |  |
| **VisiblAir Sensor O** | Driver |  |
| **VisiblAir Sensor X** | Driver |  |
| **VisiblAir Sensor XW** | Driver |  |
<!-- /AUTO -->

## Standalone Alternative

A [standalone single-device driver](../../drivers/visiblair/visiblair.groovy) is also available if you only need to monitor one sensor without the full parent/child setup.

## License

MIT — see individual source files for the full license text.

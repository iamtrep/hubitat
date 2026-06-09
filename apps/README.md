<!--
Copyright (c) 2025-2026 PJ
SPDX-License-Identifier: MIT
-->

# Apps

Hubitat Elevation apps for home automation, monitoring, and hub administration.

## Standalone Apps

<!-- AUTO:apps-table -->
| App | Description |
|---|---|
| **Attribute Logger (parent/child)** | Manages multiple Attribute Logger app instances |
| **Battery Change Logger** | Monitors battery levels and logs replacements to app history and an on-hub JSON file |
| **Humidity-Based Fan Controller** | Controls a bathroom extractor fan based on humidity levels compared to a reference sensor |
| **Hydro-Québec Peak Period Manager** | Manages devices during Hydro-Québec peak periods |
| **Location Event Mapper (parent/child)** | TBD |
| **mmWave Sensor Comparison** | Subscribes to several co-located presence/motion sensors and derives comparative metrics: activation latency, agreement, and sustained-occupancy hold. |
| **Startup and Shutdown Monitor** | Controls a virtual contact sensor based on system events related to startup, shutdown and reboot |
| **Switch Monitor** | Monitors switches that must remain on or off, organized in groups with independent timing, notifications, and load monitoring. |
<!-- /AUTO -->

## Subfolders

<!-- AUTO:apps-subfolders -->
| Folder | Description |
|---|---|
| [HubDiagnostics/](./HubDiagnostics/) | Comprehensive hub diagnostics: inventory, performance tracking, network analysis, and snapshot comparison |
| [LogMonitor/](./LogMonitor/) |  |
| [MultiHubInventory/](./MultiHubInventory/) | Read-only cross-hub device inventory, aggregated from each hub's Hub Diagnostics audit API |
| [sensors/](./sensors/) |  |
| [tests/](./tests/) |  |
| [utilities/](./utilities/) |  |
| [WellMonitor/](./WellMonitor/) | Monitors well pump cycles, downstream consumption, tank usage, and emergency shutoff |
<!-- /AUTO -->

## License

MIT — see individual source files for the full license text.

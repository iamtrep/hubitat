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
| **Bathroom Lighting Shadow** | Runs multiple lighting-control policies in parallel against shared sensors, drives an auto-created virtual switch per policy, and scores each policy without touching real lights. |
| **Battery Change Logger** | Monitors battery levels and logs replacements to app history and an on-hub JSON file |
| **Contact State Setter** | Sets selected contact sensors open or closed by injecting a contact event via sendEvent. |
| **Humidity-Based Fan Controller** | Controls a bathroom extractor fan based on humidity levels compared to a reference sensor |
| **Hydro-Québec Peak Period Manager** | Manages devices during Hydro-Québec peak periods |
| **Mirror Switch** | Keeps a group of on/off devices in sync; any member changing drives the rest to match. |
| **mmWave Sensor Comparison** | Subscribes to several co-located presence/motion sensors and derives comparative metrics: activation latency, agreement, and sustained-occupancy hold. |
| **Startup and Shutdown Monitor** | Controls a virtual contact sensor based on system events related to startup, shutdown and reboot |
| **Switch Monitor** | Monitors switches that must remain on or off, organized in groups with independent timing, notifications, and load monitoring. |
<!-- /AUTO -->

## Subfolders

<!-- AUTO:apps-subfolders -->
| Folder | Description |
|---|---|
| [HubInspector/](./HubInspector/) | Comprehensive hub diagnostics: inventory, performance tracking, network analysis, and snapshot comparison |
| [HvacInterlock/](./HvacInterlock/) | Gates heating and cooling equipment by season and open windows |
| [HvacSeasonManager/](./HvacSeasonManager/) | Publishes the heating and cooling season on a device, from dates and the outdoor temperature |
| [IndoorAirQualityController/](./IndoorAirQualityController/) | Runs ventilation in stages from indoor CO2 and warns when a window should be opened or the air is too dry |
| [LocationEventMapper/](./LocationEventMapper/) | TBD |
| [LogMonitor/](./LogMonitor/) | Monitor hub logs with multiple filters and output actions |
| [MultiHubInventory/](./MultiHubInventory/) | Read-only cross-hub device inventory, aggregated from each hub's Hub Inspector audit API |
| [sensors/](./sensors/) |  |
| [SwitchedHeaterThermostat/](./SwitchedHeaterThermostat/) | Heater switches driven by a Virtual Thermostat and temperature sensors, with alerts |
| [tests/](./tests/) |  |
| [ThermostatSchedulerPlus/](./ThermostatSchedulerPlus/) | Thermostat schedules with profiles, holds and an API |
| [utilities/](./utilities/) |  |
| [WellMonitor/](./WellMonitor/) | Monitors well pump cycles, downstream consumption, tank usage, and emergency shutoff |
<!-- /AUTO -->

## License

MIT — see individual source files for the full license text.

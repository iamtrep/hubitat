# Apps

Hubitat Elevation apps for home automation, monitoring, and hub administration.

## Standalone Apps

| App | Description |
|---|---|
| **Attribute Logger** | Parent/child app that logs device attribute changes. Create multiple logger instances to track different devices and attributes. |
| **Battery Change Logger** | Monitors battery levels across devices and logs replacement history to an on-hub JSON file. |
| **Humidity Fan Controller** | Controls a bathroom extractor fan using a state machine that compares bathroom humidity against a reference sensor, with debounced transitions and configurable thresholds. |
| **Hydro-Québec Peak Period Manager** | Manages thermostats and appliances during Hydro-Québec peak demand periods using data from the Hydro-Québec API. |
| **Location Event Mapper** | Parent/child app that maps hub location events (startup, shutdown, reboot, etc.) to virtual contact sensor states for use in Rule Machine automations. |
| **Startup and Shutdown Monitor** | Opens/closes a virtual contact sensor on hub shutdown, reboot, and startup events for use in automations. |

## Subfolders

| Folder | Description |
|---|---|
| [sensors/](sensors/) | Sensor Aggregator and Sensor Filter apps for combining or smoothing multiple sensor readings into a single virtual device. |
| [tests/](tests/) | Hub stress test apps for benchmarking async HTTP, UDP, and File Manager API performance. |
| [utilities/](utilities/) | Hub administration tools: device "in use by" report, device replacement helper, and Rule Machine subscription tracker. |
| [WellPumpMonitor/](WellPumpMonitor/) | Well pump monitoring with cycle tracking, water flow metering, emergency shutoff, and a web dashboard. |

## License

MIT — see individual source files for the full license text.

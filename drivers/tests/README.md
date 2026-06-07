<!--
Copyright (c) 2025-2026 PJ
SPDX-License-Identifier: MIT
-->

# Driver Test Fixtures & Diagnostic Utilities

Virtual drivers, sandbox introspection tools, and companion test apps that exercise the production drivers and apps in this repo. None of these are intended for everyday device use — they're installed temporarily to back a test or surface platform behaviour.

## Virtual devices

- `VirtualFlowMeter.groovy` — virtual `liquidFlowRate` device used by Well Monitor's behaviour tests
- `VirtualWellPumpSwitch.groovy` — virtual switch + `powerMeter` device for the same suite
- `VirtualMmwavePirSensor.groovy` — virtual device that exposes both PIR and mmWave motion attributes for Motion Fusion testing

## Diagnostic / introspection drivers

- `DeviceInspector.groovy` — dumps `DeviceWrapper` properties to the logs (one-shot diagnostic)
- `ZigbeeIntrospect.groovy` — reflective dump of the platform's `zigbee` helper class; output feeds `docs/hubitat-zigbee-helper.md`
- `GenericWebsocket.groovy` — development scaffold for testing arbitrary WebSocket connections
- `LogEventMonitorTest.groovy` — companion test device for the Log Event Monitor driver

## Companion apps

- `XfinityContactSensorMonitor.groovy` — **app** (lives here next to the drivers it watches): logs `battery`, `batteryVoltage`, `lowBattery`, and `batteryDefect` events for Xfinity contact sensors and optionally notifies

## Test runner

- `test-well-monitor-drivers.sh` — bash harness for the Well Monitor virtual-driver pair

## License

MIT — see individual source files for the full license text.

See the parent [drivers/README.md](../README.md) for the full driver index.

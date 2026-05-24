<!--
Copyright (c) 2025-2026 PJ
SPDX-License-Identifier: MIT
-->

# Test Coverage Ledger

Inventory of every app and driver in this repo and its current automated-test coverage. The bar for what *qualifies* as a test, and the meaning of each Mode number, is defined in [`TESTING.md`](TESTING.md). This file is the ledger; that file is the policy.

**Conventions used in the tables below**
- **Mode** values: `1` (behavior, the lead tier), `2` (API integration), `3` (pure-JS unit), `4` (Python mirror), `5` (in-hub stress, exempt from §1.1). `—` means no automated test.
- **Required?** column reflects the tiered bar in [`TESTING.md`](TESTING.md) §2.2.
  - `Y` — tier requires a test for new or significantly-changed code.
  - `N` — no test required by the bar, but one would still be useful.
  - `exempt` — Mode 5 stress apps, parent shells, or sub-files that aren't independently invocable.
- **Test artifact** is the canonical entry point (the `.sh` / `.js` / `.py` the agent runs).

When you ship a new test, add a row here in the same commit as the test artifact.

## Apps

| Source | Role | Mode | Required? | Test artifact | Notes |
|---|---|---|---|---|---|
| [`apps/AttributeLogger.groovy`](apps/AttributeLogger.groovy) | Parent shell | — | exempt | — | Holds `AttributeLoggerChild` instances; no logic to test. |
| [`apps/AttributeLoggerChild.groovy`](apps/AttributeLoggerChild.groovy) | Automation | 1 | Y | [`apps/tests/test-attribute-logger-child.sh`](apps/tests/test-attribute-logger-child.sh) ([spec](apps/tests/spec-attribute-logger-child.yaml)) | Subscribes to device attribute → emits log entries. |
| [`apps/BatteryChangeLogger.groovy`](apps/BatteryChangeLogger.groovy) | Automation | 1 | Y | [`apps/tests/test-battery-change-logger.sh`](apps/tests/test-battery-change-logger.sh) ([spec](apps/tests/spec-battery-change-logger.yaml)) | Subscribes to battery events → logs replacement candidates. |
| [`apps/HubDiagnostics/HubDiagnostics.groovy`](apps/HubDiagnostics/HubDiagnostics.groovy) | OAuth API + UI | 2, 3, 4 | Y | [`tests/test-hub-diagnostics-api.sh`](apps/HubDiagnostics/tests/test-hub-diagnostics-api.sh), [`tests/test-diffStats.js`](apps/HubDiagnostics/tests/test-diffStats.js), [`test_classification.py`](apps/HubDiagnostics/test_classification.py) | Phased plan in [`tests/TEST_PLAN.md`](apps/HubDiagnostics/tests/TEST_PLAN.md). Mode 2 audit-HTML + snapshot-diff still planned. |
| [`apps/HumidityFanController.groovy`](apps/HumidityFanController.groovy) | Automation | 1 | Y | [`apps/tests/test-humidity-fan-controller.sh`](apps/tests/test-humidity-fan-controller.sh) ([spec](apps/tests/spec-humidity-fan-controller.yaml)) | 10 cases, full state-machine convergence. `max-fan-run-time-expiry` uncovered (60s minimum — needs slow-test variant). |
| [`apps/HydroPeakEvents.groovy`](apps/HydroPeakEvents.groovy) | Automation | — | Y | — | Uncovered by automation. Has a built-in `testMode` preference that uploads a canned `testPeakPeriods.json` via `generateTestFile()` and shortens `preEventMinutes` to 2 — useful for manual smoke-tests, but the canned schedule hardcodes `eventStart = now + 5min`, so a Mode 1 wrapper around it would need the `RUN_SLOW_TESTS=1` gate. |
| [`apps/LocationEventMapper.groovy`](apps/LocationEventMapper.groovy) | Parent shell | — | exempt | — | Holds `LocationEventMapperChild` instances. |
| [`apps/LocationEventMapperChild.groovy`](apps/LocationEventMapperChild.groovy) | Automation | — | Y | — | Uncovered. Drives device commands from location-mode transitions. |
| [`apps/LogMonitor/LogMonitor.groovy`](apps/LogMonitor/LogMonitor.groovy) | Parent shell | — | exempt | — | Holds the bridge child. |
| [`apps/LogMonitor/LogMonitorBridge.groovy`](apps/LogMonitor/LogMonitorBridge.groovy) | Automation | — | Y | — | Uncovered. Recent CME fix in `parse()` would benefit from regression coverage. |
| [`apps/sensors/MotionFusionChild.groovy`](apps/sensors/MotionFusionChild.groovy) | Automation | 1 | Y | [`apps/sensors/tests/test-motion-fusion-child.sh`](apps/sensors/tests/test-motion-fusion-child.sh) ([spec](apps/sensors/tests/spec-motion-fusion-child.yaml)) | Currently exercises only `pirQuickMmwaveHold` mode; six other fusion modes still uncovered. Uses paired [`drivers/tests/VirtualMmwavePirSensor.groovy`](drivers/tests/VirtualMmwavePirSensor.groovy). |
| [`apps/sensors/SensorAggregator.groovy`](apps/sensors/SensorAggregator.groovy) | Parent shell (singleInstance) | — | exempt | — | Holds SAC / SADC / MotionFusion children. |
| [`apps/sensors/SensorAggregatorChild.groovy`](apps/sensors/SensorAggregatorChild.groovy) | Automation | 1 | Y | [`apps/sensors/tests/test-sac.sh`](apps/sensors/tests/test-sac.sh) ([spec](apps/sensors/tests/spec-sac.yaml)) | Aggregates numeric values across N inputs into a virtual output. |
| [`apps/sensors/SensorAggregatorDiscreteChild.groovy`](apps/sensors/SensorAggregatorDiscreteChild.groovy) | Automation | 1 | Y | [`apps/sensors/tests/test-sadc.sh`](apps/sensors/tests/test-sadc.sh) ([spec](apps/sensors/tests/spec-sadc.yaml)) | Canonical Mode 1 worked example per [`TESTING.md`](TESTING.md) §2.1. |
| [`apps/sensors/SensorFilterChild.groovy`](apps/sensors/SensorFilterChild.groovy) | Automation | 1 | Y | [`apps/sensors/tests/test-sensor-filter.sh`](apps/sensors/tests/test-sensor-filter.sh) ([spec](apps/sensors/tests/spec-sensor-filter.yaml)) | Filters / smooths sensor readings. |
| [`apps/sensors/SensorFilterManager.groovy`](apps/sensors/SensorFilterManager.groovy) | Parent shell (singleInstance) | — | exempt | — | Holds `SensorFilterChild` instances. |
| [`apps/StartupShutdownMonitor.groovy`](apps/StartupShutdownMonitor.groovy) | Automation | — | Y | — | Uncovered. Hub lifecycle hooks — non-trivial to drive (would need a way to simulate hub start/stop events). |
| [`apps/SwitchMonitor.groovy`](apps/SwitchMonitor.groovy) | Automation | 1 | Y | [`apps/tests/test-switch-monitor.sh`](apps/tests/test-switch-monitor.sh) ([spec](apps/tests/spec-switch-monitor.yaml)) | Mode 1 with `setup_buttons` (the worked example for the `app.setup_buttons` spec field). |
| [`apps/tests/asyncHttpStressTest.groovy`](apps/tests/asyncHttpStressTest.groovy) | In-hub stress | 5 | exempt | (the app itself) | Manual button press to start; log-asserted via wrapper would close the §2.4 gap. |
| [`apps/tests/fileManagerTests.groovy`](apps/tests/fileManagerTests.groovy) | In-hub stress | 5 | exempt | (the app itself) | File Manager API benchmark. |
| [`apps/tests/hubStressTests.groovy`](apps/tests/hubStressTests.groovy) | In-hub stress | 5 | exempt | (the app itself) | Generic hub-load harness. |
| [`apps/tests/SampleApp.groovy`](apps/tests/SampleApp.groovy) | In-hub sample / scratch | 5 | exempt | (the app itself) | Reference scaffold, not behavior. |
| [`apps/tests/udpStressTest.groovy`](apps/tests/udpStressTest.groovy) | In-hub stress | 5 | exempt | (the app itself) | UDP round-trip latency. |
| [`apps/utilities/DeviceInUseEnumerator.groovy`](apps/utilities/DeviceInUseEnumerator.groovy) | Utility (UI report) | — | N | — | Uncovered. Computation-heavy with a UI surface; Mode 4 mirror could test the enumeration logic if regressions appear. |
| [`apps/utilities/DeviceReplacement.groovy`](apps/utilities/DeviceReplacement.groovy) | Utility | — | N | — | Uncovered. |
| [`apps/utilities/RuleLoggingManager.groovy`](apps/utilities/RuleLoggingManager.groovy) | OAuth API | 2 | Y | [`apps/utilities/tests/test-rule-logging-manager.sh`](apps/utilities/tests/test-rule-logging-manager.sh) | Snapshot-diff coverage of undocumented hub endpoints. |
| [`apps/WellMonitor/WellMonitor.groovy`](apps/WellMonitor/WellMonitor.groovy) | Automation | 1 | Y | [`apps/WellMonitor/tests/test-well-monitor-app.sh`](apps/WellMonitor/tests/test-well-monitor-app.sh) ([spec](apps/WellMonitor/tests/spec-well-monitor.yaml)) | 5 cases: pump start/stop, coincident-flow math, water-flow start/stop, emergency shutoff. Uses paired [`drivers/tests/VirtualWellPumpSwitch.groovy`](drivers/tests/VirtualWellPumpSwitch.groovy) + [`drivers/tests/VirtualFlowMeter.groovy`](drivers/tests/VirtualFlowMeter.groovy). |

## Drivers

Per [`TESTING.md`](TESTING.md) §2.2, driver tests are not required by default; the four options when one *is* warranted are documented in [`TESTING.md`](TESTING.md) §3 (paired test driver, Mode 4 mirror, `parse()` injection via fixture app, mock-service app). The latter two are parked.

| Source | Connection | Mode | Required? | Test artifact | Notes |
|---|---|---|---|---|---|
| [`drivers/AwairElement.groovy`](drivers/AwairElement.groovy) | Polling (local API) | — | N | — | Mock-service pattern would apply (parked). |
| [`drivers/BTHomeV2-Motion.groovy`](drivers/BTHomeV2-Motion.groovy) | BLE advertisement | — | N | — | `parse()`-injection pattern would apply (parked). |
| [`drivers/DevicePing.groovy`](drivers/DevicePing.groovy) | HTTP polling | — | N | — | Mock-service pattern would apply (parked). |
| [`drivers/EcobeeCompanion.groovy`](drivers/EcobeeCompanion.groovy) | Cloud OAuth | — | N | — | Mock-service pattern would apply (parked); OAuth state machine makes mock non-trivial. |
| [`drivers/EnvironmentCanada_AQHI.groovy`](drivers/EnvironmentCanada_AQHI.groovy) | HTTP polling (public XML) | — | N | — | Smallest-surface candidate for the parked mock-service pattern (no auth). |
| [`drivers/IKEA-Blinds.groovy`](drivers/IKEA-Blinds.groovy) | Zigbee | — | N | — | `parse()`-injection pattern would apply (parked). |
| [`drivers/LogEventMonitor.groovy`](drivers/LogEventMonitor.groovy) | Hub log subscription | (paired) | N | — | Has a companion: [`drivers/tests/LogEventMonitorTest.groovy`](drivers/tests/LogEventMonitorTest.groovy) — used to drive the LogEventMonitor app's filtering tests (no standalone test for this driver itself). |
| [`drivers/sinope/Sinope_DM2500ZB.groovy`](drivers/sinope/Sinope_DM2500ZB.groovy) | Zigbee | — | N | — | `parse()`-injection pattern would apply (parked). Shares structure with the rest of the Sinope family. |
| [`drivers/sinope/Sinope_SW2500ZB.groovy`](drivers/sinope/Sinope_SW2500ZB.groovy) | Zigbee | — | N | — | Recommended first target if/when the `parse()`-injection pattern is built (parked) — in-repo, in production use, reasonable attribute surface. |
| [`drivers/sinope/Sinope_switch_specs.groovy`](drivers/sinope/Sinope_switch_specs.groovy) | (sub-file) | — | exempt | — | Library file with shared switch spec constants; not independently invocable. |
| [`drivers/sinope/Sinope_TH1300ZB.groovy`](drivers/sinope/Sinope_TH1300ZB.groovy) | Zigbee | — | N | — | Thermostat — richest attribute surface in the Sinope family. |
| [`drivers/sinope/Sinope_VA422xZB.groovy`](drivers/sinope/Sinope_VA422xZB.groovy) | Zigbee | — | N | — | Valve actuator. |
| [`drivers/stelpro/Stelpro_orleans_specs.groovy`](drivers/stelpro/Stelpro_orleans_specs.groovy) | (sub-file) | — | exempt | — | Library file with shared Stelpro spec constants. |
| [`drivers/stelpro/StelproAllia.groovy`](drivers/stelpro/StelproAllia.groovy) | Zigbee | — | N | — | Thermostat. |
| [`drivers/stelpro/StelproKi.groovy`](drivers/stelpro/StelproKi.groovy) | Zigbee | — | N | — | Thermostat. |
| [`drivers/tests/DeviceInspector.groovy`](drivers/tests/DeviceInspector.groovy) | Fixture / introspection | (fixture) | exempt | — | Diagnostic driver, not a device driver. |
| [`drivers/tests/GenericWebsocket.groovy`](drivers/tests/GenericWebsocket.groovy) | LAN (WebSocket) | (fixture) | exempt | — | Test-only generic WS client. |
| [`drivers/tests/LogEventMonitorTest.groovy`](drivers/tests/LogEventMonitorTest.groovy) | Fixture (paired) | (fixture) | exempt | — | Companion driver — drives LogEventMonitor app behavior tests. |
| [`drivers/tests/VirtualMmwavePirSensor.groovy`](drivers/tests/VirtualMmwavePirSensor.groovy) | Virtual (fixture) | (fixture) | exempt | — | Test driver for `MotionFusionChild` Mode 1. Mirrors Aqara FP300 attribute surface. |
| [`drivers/tests/VirtualWellPumpSwitch.groovy`](drivers/tests/VirtualWellPumpSwitch.groovy) | Virtual (fixture) | (fixture) | exempt | [`drivers/tests/test-well-monitor-drivers.sh`](drivers/tests/test-well-monitor-drivers.sh) | Test driver for `WellMonitor` Mode 1. Combines `PowerMeter` + `Switch` (the app calls `pumpSwitch.off()` during emergency); paired bash script smoke-tests the command→event contract. |
| [`drivers/tests/VirtualFlowMeter.groovy`](drivers/tests/VirtualFlowMeter.groovy) | Virtual (fixture) | (fixture) | exempt | [`drivers/tests/test-well-monitor-drivers.sh`](drivers/tests/test-well-monitor-drivers.sh) | Test driver for `WellMonitor` Mode 1. Mirrors the `Sinope_VA422xZB` attribute surface (`LiquidFlowRate` + `volume`). Shares the smoke-test script with the pump-switch fixture. |
| [`drivers/ThirdReality_3RPL01084Z.groovy`](drivers/ThirdReality_3RPL01084Z.groovy) | Zigbee | — | N | — | Smart plug. `parse()`-injection pattern would apply (parked). |
| [`drivers/VirtualSwitchPowerSource.groovy`](drivers/VirtualSwitchPowerSource.groovy) | Virtual | — | N | — | Virtual driver — typically driven by direct command, no `parse()`. |
| [`drivers/visiblair/visiblair.groovy`](drivers/visiblair/visiblair.groovy) | HTTP polling | — | N | — | Standalone driver. Coexists with the `apps/sensors/visiblair/` parent-child integration. Mock-service pattern would apply (parked). |
| [`drivers/XfinityContactSensor.groovy`](drivers/XfinityContactSensor.groovy) | Zigbee | — | N | — | Smallest-surface candidate for the parked `parse()`-injection pattern (single attribute). |

## Summary

- Apps tested: **12** of **22** non-exempt apps (55%). Six exempt (parents + in-hub stress apps).
- Drivers tested: **0** of **15** non-fixture drivers. Driver tests are not required by the bar — see [`TESTING.md`](TESTING.md) §2.2.
- Mode 1 (behavior) is by far the dominant test mode (9 apps). Mode 2 (API integration) covers 2 apps. Mode 3 / Mode 4 cover HubDiagnostics only.
- Named uncovered automation apps: `HydroPeakEvents`, `LocationEventMapperChild`, `LogMonitor` / `LogMonitorBridge`, `StartupShutdownMonitor`.

Update this ledger when you add a test or a new source file. The summary counts are easy to forget — recount when you change rows.

<!--
Copyright (c) 2025-2026 PJ
SPDX-License-Identifier: MIT
-->

# YAML Spec Examples

> **Load this when:** writing specs with parameterized commands, multi-device rigs, button clicks inside cases, command spacing, tolerance-based assertions, or any pattern beyond the minimal single-device example.

## Parameterized commands (args)

Use `args: [v1, v2, ...]` for commands that take arguments (e.g. `setTemperature`, `setLevel`). Hubitat's Maker API encodes args as extra URL path segments after the command name.

```yaml
cases:
  - name: set temperature then verify aggregate
    setup:
      - { device: sensor-1, command: setTemperature, args: [22.5] }
      - { device: sensor-2, command: setTemperature, args: [24.0] }
    actions:
      - { device: trigger-switch, command: "on" }
    wait_seconds: 3
    assert:
      - { device: output-avg, attribute: temperature, value: 23.25, tolerance: 0.1 }
```

## Command spacing for same-device rapid commands

Hubitat coalesces same-device events <1s apart, causing silent test failures when you fire multiple Maker API commands to the same device in quick succession. Use `command_spacing_seconds` to add a pause between consecutive commands in `setup` or `actions`.

```yaml
cases:
  - name: toggle device twice in sequence
    setup:
      - { device: test-switch, command: "on" }
      - { device: test-switch, command: "off" }
    command_spacing_seconds: 0.5   # 0.3–0.5s is usually enough
    actions:
      - { device: test-switch, command: "on" }
    wait_seconds: 2
    assert:
      - { device: test-output, attribute: switch, value: "on" }
```

## Button-click step inside a case

A `{ button: btnName }` step in `setup` or `actions` clicks a button-type preference on the app under test, invoking `appButtonHandler(String btn)` in the SUT. Use sparingly inside cases — a click that initializes state typically belongs in `app.setup_buttons` (provisioning time), not per-case.

```yaml
cases:
  - name: force-run via button
    setup:
      - { button: btnRunNow }
    actions:
      - { device: test-sensor, command: setTemperature, args: [30.0] }
    wait_seconds: 5
    assert_logs:
      - { pattern: "Threshold exceeded", level: warn }
```

## setup_buttons — provisioning-time button clicks

Declare in `app.setup_buttons` when the app's state shape is established by UI button clicks rather than direct settings (e.g. SwitchMonitor's `btnNewGroup`, which creates the group whose per-group settings then become valid keys for the config POST).

```yaml
app:
  source: apps/switches/SwitchMonitor.groovy
  type_name: Switch Monitor
  instance_label: test-switchmonitor-app
  setup_buttons:
    - btnNewGroup          # creates group 1
    - btnNewGroup          # creates group 2 (declared twice for two groups)
  config:
    1.targetState: "on"
    1.devices: <device-id-list>
    2.targetState: "off"
    2.devices: <device-id-list>
```

After each button click, re-fetch `/installedapp/configure/json/{instanceId}` before Step 11's config POST — the new settings keys only appear after the click.

## Tolerance-based assertions

Use `tolerance: <float>` when comparing numeric attributes (temperature, humidity, illuminance, etc.) that Hubitat may format or round differently across firmware. Without it, comparison is exact string match — correct default for discrete attributes like `contact`/`motion`.

```yaml
assert:
  - { device: test-aggregate, attribute: temperature, value: 22.0, tolerance: 0.1 }
  - { device: test-contact, attribute: contact, value: "open" }   # exact match
```

## Multi-device rig example

A spec with separate input and output devices, a parent app, and multiple cases:

```yaml
app:
  source: apps/sensors/SensorAggregator.groovy
  type_name: Sensor Aggregator Child
  instance_label: test-sadc-app
  parent:
    source: apps/sensors/SensorAggregatorParent.groovy
    type_name: Sensor Aggregator
    instance_label: test-sap-app
  config:
    excludeAfter: 30

inputs:
  - { name: test-sadc-in-1, driver: Virtual Temperature Sensor, role: temperatureSensors }
  - { name: test-sadc-in-2, driver: Virtual Temperature Sensor, role: temperatureSensors }

outputs:
  - { name: test-sadc-out, driver: Virtual Temperature Sensor, role: aggregateDevice }

maker_api:
  label: test-sadc-maker-api

cases:
  - name: two-sensor average
    setup:
      - { device: test-sadc-in-1, command: setTemperature, args: [20.0] }
      - { device: test-sadc-in-2, command: setTemperature, args: [24.0] }
    actions:
      - { device: test-sadc-in-1, command: setTemperature, args: [22.0] }
    wait_seconds: 3
    assert:
      - { device: test-sadc-out, attribute: temperature, value: 23.0, tolerance: 0.1 }

  - name: exclude stale sensor
    setup:
      - { device: test-sadc-in-1, command: setTemperature, args: [20.0] }
    actions:
      - { device: test-sadc-in-2, command: setTemperature, args: [30.0] }
    wait_seconds: 3
    assert:
      - { device: test-sadc-out, attribute: temperature, value: 30.0, tolerance: 0.1 }

runtime_budget_seconds: 60
```

## YAML 1.1 boolean trap

Bare `on`, `off`, `yes`, `no`, `true`, `false` parse as Python booleans in YAML 1.1, which then become URL segments like `/dev/123/True` → 404 from Maker API, silent test failure. **Always quote these values**:

```yaml
# Correct:
- { device: test-switch, command: "off" }
assert:
  - { device: test-switch, attribute: switch, value: "on" }

# WRONG — parses to Python False:
- { device: test-switch, command: off }
assert:
  - { device: test-switch, attribute: switch, value: on }
```

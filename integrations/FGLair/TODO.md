<!--
Copyright (c) 2026 PJ
SPDX-License-Identifier: MIT
-->

# FGLair Integration — TODO

Items deferred from MVP/Tier 2 work. Captured so future sessions can pick them up cold without re-doing the discovery and design work that's already been done.

## Tier 3 — swing, mode toggles

**Deferred 2026-05-18.** Reason: integration has too little runtime to know which of these the user actually wants. Revisit after a few weeks of normal use.

Property names confirmed populated on the test unit (`ASUG15LZTD : AP-WF1E`) — see the manager's "Discovered Properties (debug)" section to re-verify on any future unit.

### Properties

| Property | Range | Meaning |
|---|---|---|
| `af_horizontal_swing` | 0 / 1 | horizontal louver swing on/off |
| `af_horizontal_direction` | 0–20 | horizontal louver fixed position |
| `af_horizontal_num_dir` | 21 | number of positions (model-dependent) |
| `af_vertical_swing` | 0 / 1 | vertical louver swing on/off |
| `af_vertical_direction` | 0–3 | vertical louver fixed position |
| `af_vertical_num_dir` | 4 | number of positions (model-dependent) |
| `economy_mode` | 0 / 1 | Eco mode |
| `powerful_mode` | 0 / 1 | Powerful (boost) mode |
| `min_heat` | 0 / 1 | 10°C minimum-heat mode |
| `coil_dry_mode` | 0 / 1 | anti-mold dry-after-cool cycle |

### Design forks already worked out (apply when revisiting)

1. **Swing** — model as pure custom: separate `horizontalSwing` (number 0..max) / `verticalSwing` (number 0..max) direction attributes and `horizontalSwingMode` / `verticalSwingMode` (on/off) toggle attributes. Direction and swing-on are independent on the unit; mirror that.
2. **Mode toggles** — one bool attribute per mode (`economyMode`, `powerfulMode`, `minHeatMode`, `coilDryMode` with values `on` / `off`) plus a matching `setX` command per mode.
3. **Eco vs Powerful mutual exclusion** — write what the user asked, let the unit/cloud enforce, let the next poll reconcile. No driver-side guard.
4. **Optimistic updates** — gate Tier 3 attribute updates behind the existing `optimisticUpdates` preference, same as MVP.
5. **`thermostatOperatingState`** — no change. Min-heat is still mode `heat`, so existing derivation works unchanged.

### Dropped from original Tier 3 scope

- **Sleep mode** — no `sleep_mode` property reported by this unit. Not designing speculatively.
- **Model variant handling** (`HORIZ_SWING_PARAM_MAP` from `ayla-iot-unofficial`) — this unit uses the standard `af_horizontal_swing` / `af_vertical_swing` names. ModelType A/B/F branching only matters if a future unit reports those properties as 65535 sentinel. Revisit then.

## errorCode / opStatus decoding

`errorCode` and `opStatus` ship as raw integers (Tier 2). Decoding to named states (e.g. `running`, `defrost`, `error: refrigerant leak`) is empirical work — needs observations of non-zero values across normal operation.

To capture:
- Watch the trace log for `opStatus changed: X -> Y` and `errorCode set to N` lines as the unit operates over days/weeks.
- Note what the unit is physically doing when each non-zero value appears.
- Build value→name maps once enough observations accumulate.

## human_det — disposition

**MotionSensor capability dropped 2026-05-19.** It mapped `human_det` to `motion` active/inactive, but the value sat stuck at `1` day and night.

Research findings (so this isn't re-done):
- No open-source FGLair/Fujitsu project exposes presence/motion/occupancy in any form — checked `ayla-iot-unofficial` (the lib HA core's `fujitsu_fglair` uses), HA core `fujitsu_fglair`, `pyfujitseu`, `bigmoby/fglair_for_homeassistant`. `ayla-iot-unofficial` doesn't even list `human_det` among its Fujitsu properties.
- Conclusion: Fujitsu's "human sensor" is a local hardware feature (airflow steering / energy-save); the cloud does not appear to publish a live occupancy datapoint. `human_det` is almost certainly the feature enable flag, not a reading.

To revisit: dump this unit's full `properties.json` (HAR from the FGLair app, or a trace dump from the manager) and inspect the `human_det` property object — `direction` (`input` = app→device setting, `output` = device reading), `read_only`, `base_type`, `data_updated_at`. If it's a writable input property, expose it as a switch (`humanSensorMode` on/off + `setHumanSensorMode`). If read-only and never changing, leave it dropped.

## Discovery debug section refinements

The `atomicState.knownProperties` accumulator is currently single-unit-blind (one map for all units combined). Two future improvements if the discovery surface ever gets reused in a multi-unit account:

- **Per-DSN segmentation** — `atomicState.knownProperties[dsn] = ...` keyed by DSN so different units' property sets don't collide.
- **Value-range tracking** — capture min/max observed values per property. Useful for inferring scaling factors (the lesson from the sensor formula episode where we'd have caught the wrong formula faster if we'd seen a wider raw range).

Neither is needed for the current single-unit case.

## Testing harness

Cloud-integration regression coverage. Original FGLair spec flagged this as a "captured-HAR replay against a fake Ayla endpoint" — never built because the protocol surface kept moving and the MVP was a single physical unit. Worth revisiting when:

- A second unit gets added (heterogeneity surfaces).
- A second user takes the integration (regressions become other people's problems).
- The Ayla cloud changes shape under us (current behavior frozen by tests would surface the drift).

Until then, the manual smoke test pass against `@maison-pro` is the test suite.

## Memory references

- `feedback_har_files.md` — preferred workflow for capturing API payloads when in doubt.
- `fglair_temperature_scaling.md` — sensor scale (hundredths of °F, empirical) and setpoint scale (tenths of °C) facts; warns against the ayla-iot-unofficial range-map formula for this unit.
- `hubitat_supported_thermostat_modes.md` — the `setSupported*()` workaround used here (sendEvent the attribute with pre-quoted string values).

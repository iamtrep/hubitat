<!--
Copyright (c) 2026 PJ
SPDX-License-Identifier: MIT
-->

# FGLair Integration — TODO

Items deferred from MVP/Tier 2 work. Captured so future sessions can pick them up cold without re-doing the discovery and design work that's already been done.

## To verify after the 2026-09-30 changes

Manager v0.2.0 / driver v0.2.0 shipped behavior that was only partly exercised live:

- **Write-queue safety of the new wake paths.** Only the 15-min scheduled wake has months of runtime. The 10-min option and the device `refresh()` wake (rate-limited to one per minute per unit) are untested against the queue jam. Signature: setpoint writes stop landing from every source, including the FGLair app over cloud (see the `fglair_cloud_write_quirks` memory).
- **`dry` → `cool` and `fan_only` → `off` thermostatMode mapping.** Not exercised live; it needs the unit switched into each mode.
- **Operating-state estimate.** `thermostatOperatingState` is derived from room temperature vs setpoint with a 0.5°C band (start heating at setpoint − 0.5, stop on reaching setpoint; cooling mirrors). Check it against what the unit is physically doing over a few heating days.
- **Auth failure paths** (single-flight refresh, halt after 3 consecutive 401s or a rejected re-sign-in, queued writes replayed after refresh). Only the happy path ran live.

Manager / driver v0.2.1 (2026-09-30) added the following, not yet run live:

- **Poll pipeline.** Property GETs run at most 4 at a time from an in-memory per-poll queue, at most 2 sensor wakes go out per tick, a tick is skipped while the previous poll still has a request out (no activity for 20 s means none), and responses from a superseded poll are ignored. With one unit only the single-fetch path runs; the queue and wake cap only matter with more units.
- **Request budget.** Every async call counts against one app-wide limit of 8. Token calls always go, writes keep 1 slot free (else dropped and reported `failed`), wakes and single-unit reads keep 2 (else skipped). "Refresh now" and post-auth polls wait for a running poll, retrying every 5 s. The count resets after 20 s with no request activity. A 3× `refresh()` burst ran correctly live (one wake, two reads, the delayed read 15 s later); the limits themselves were never reached.
- **Per-unit health.** 5 failed property reads in a row mark that unit offline; the next device list after a success brings it back.
- **`commandStatus`.** Set on every write outcome. Check that a failed write shows `failed` and the next poll restores the optimistic attributes.
- **`singleThreaded`.** Child commands now wait for any running manager handler. All manager handlers are short (HTTP is async), so command latency should not change noticeably.

## Tier 3 — swing, mode toggles

**Deferred 2026-05-18, re-deferred 2026-09-30** (no current need). All findings below are current as of 2026-09-30.

Property names confirmed populated on the test unit (`ASUG15LZTD : AP-WF1E`). The manager's "Discovered Properties (debug)" page shows values only; to see `direction` / `read_only` / `base_type`, GET `/apiv1/dsns/{dsn}/properties.json` directly (one read, no writes).

### Properties

Every Tier 3 property is `direction: input`, `read_only: false`, so the existing `sendCommand()` write path covers them all.

| Property | Range | Meaning |
|---|---|---|
| `af_horizontal_swing` | 0 / 1 | horizontal louver swing on/off |
| `af_horizontal_direction` | probably 1–21, **unconfirmed** | horizontal louver fixed position (observed 4) |
| `af_horizontal_num_dir` | 21 (read-only output) | number of positions (model-dependent) |
| `af_vertical_swing` | 0 / 1 | vertical louver swing on/off |
| `af_vertical_direction` | probably 1–4, **unconfirmed** | vertical louver fixed position. Observed value 4 with `num_dir` 4, so the earlier 0–3 assumption is wrong |
| `af_vertical_num_dir` | 4 (read-only output) | number of positions (model-dependent) |
| `economy_mode` | 0 / 1 | Eco mode |
| `powerful_mode` | 0 / 1 | Powerful (boost) mode. Remotes end it after ~20 min; untested whether the cloud flag clears itself |
| `min_heat` | 0 / 1 | 10°C minimum-heat mode |
| `coil_dry_mode` | 0 / 1 | anti-mold dry-after-cool cycle |
| `outdoor_low_noise` | 0 / 1 | outdoor unit quiet mode (not in the original scope) |
| `human_det_auto_save` | 0 / 1 | human-sensor energy save (not in the original scope) |
| `human_det_auto_off` | 0 / 1 | human-sensor auto-off (not in the original scope) |
| `human_det_auto_on_off` | 0 / 1 | human-sensor auto on/off (not in the original scope) |
| `auto_save_time` | integer, observed 20 | presumably the energy-save delay in minutes, unconfirmed |

Writable but meaning unknown, leave alone: `indoor_fan_control` (bool), `external_thermostat_off` (integer).

**Louver ranges must be confirmed before any direction write.** An out-of-range value is the same class of unhonorable datapoint that jammed the write queue in June. Confirm without writing: move each louver to both ends with the IR remote or FGLair app and read the values on the debug page.

### Value and effort, ranked

1. **Eco, outdoor quiet, Powerful** — the ones worth automating (away/night setback, night noise, boost on arrival). Trivial: property map entry + attribute + command each.
2. **Min heat** — freeze protection while away. Medium, see the setpoint interaction below.
3. **Human-sensor energy save / auto-off, coil dry, swing on/off** — trivial, but usually set once in the app, so low automation value.
4. **Louver positions** — small, blocked on confirming the range. Little automation value.

All are custom attributes/commands (Hubitat has no standard capability for any of them): usable from rules and attribute tiles, not from Alexa/Google or thermostat tiles.

### Design forks already worked out (apply when revisiting)

1. **Swing** — model as pure custom: separate `horizontalSwing` (number) / `verticalSwing` (number) direction attributes and `horizontalSwingMode` / `verticalSwingMode` (on/off) toggle attributes. Direction and swing-on are independent on the unit; mirror that.
2. **Mode toggles** — one bool attribute per mode (`economyMode`, `powerfulMode`, `minHeatMode`, `coilDryMode`, `outdoorQuietMode`, with values `on` / `off`) plus a matching `setX` command per mode.
3. **Eco vs Powerful mutual exclusion** — write what the user asked, let the unit/cloud enforce, let the next poll reconcile. No driver-side guard.
4. **Optimistic updates** — gate Tier 3 attribute updates behind the existing `optimisticUpdates` preference, same as MVP.
5. **Min heat vs the dual-setpoint model** — min heat most likely runs as mode `heat` with `adjust_temperature` at 10°C (unconfirmed; check what the unit reports while it runs). If so, `updateState` would mirror 10°C into `heatingSetpoint`, and the next switch into heat after min heat ends would push 10°C back to the unit. Suppress the setpoint mirroring while `min_heat` is 1. 10°C is also below the driver's 16°C heat clamp, so it can't be set as a manual setpoint. The operating-state estimate needs no change: it compares against whatever setpoint the unit reports.

### Dropped from original Tier 3 scope

- **Sleep mode** — no `sleep_mode` property reported by this unit. Not designing speculatively.
- **Model variant handling** (`HORIZ_SWING_PARAM_MAP` from `ayla-iot-unofficial`) — this unit uses the standard `af_horizontal_swing` / `af_vertical_swing` names. ModelType A/B/F branching only matters if a future unit reports those properties as 65535 sentinel. Revisit then.

## errorCode / opStatus decoding

`errorCode` and `opStatus` ship as raw integers (Tier 2). Decoding to named states (e.g. `running`, `defrost`, `error: refrigerant leak`) is empirical work.

Both have read 0 continuously from 2026-05-19 to 2026-09-30, so passive watching has produced nothing. A deliberate capture is needed: the most likely trigger is a defrost cycle in heat mode at outdoor temperatures near or below 0°C. The manager logs `opStatus changed: X -> Y` at info and `errorCode set to N` at warn. Note what the unit is physically doing when each value appears, then build value→name maps.

Decoding `opStatus` (if it reflects compressor activity) would replace the setpoint-based `thermostatOperatingState` estimate with real state.

## human_det — disposition

**MotionSensor capability dropped 2026-05-19.** It mapped `human_det` to `motion` active/inactive, but the value sat stuck at `1` day and night.

**Settled 2026-09-30:** the property object is `direction: output`, `read_only: true`, `base_type: integer`, value 1. It is a device-reported value that doesn't change, so it stays dropped. The writable human-sensor features are the separate `human_det_auto_*` properties listed under Tier 3.

One cheap check left: the May observation predates the 15-min sensor wakes. Logging `human_det` changes would show whether it moves now. Low priority.

Research findings (so this isn't re-done): no open-source FGLair/Fujitsu project exposes presence/motion/occupancy — checked `ayla-iot-unofficial`, HA core `fujitsu_fglair`, `pyfujitseu`, `bigmoby/fglair_for_homeassistant`.

## LAN mode

The FGLair app sends control commands to the unit over Ayla Local LAN Mode, never through the cloud write path this integration uses. Moving writes to LAN would remove the cloud queue-jam risk at its source. Investigation notes and captures: `LAN_MODE.md`.

## Discovery debug section refinements

The `atomicState.knownProperties` accumulator is currently single-unit-blind (one map for all units combined). Future improvements if the discovery surface ever gets reused in a multi-unit account:

- **Per-DSN segmentation** — `atomicState.knownProperties[dsn] = ...` keyed by DSN so different units' property sets don't collide.
- **Value-range tracking** — capture min/max observed values per property. Useful for inferring scaling factors (the lesson from the sensor formula episode where we'd have caught the wrong formula faster if we'd seen a wider raw range).
- **Property metadata** — store `direction` / `read_only` alongside values so writability is visible without a direct GET.

None is needed for the current single-unit case.

## Testing harness

Cloud-integration regression coverage. Original FGLair spec flagged this as a "captured-HAR replay against a fake Ayla endpoint" — never built because the protocol surface kept moving and the MVP was a single physical unit. Worth revisiting when:

- A second unit gets added (heterogeneity surfaces).
- A second user takes the integration (regressions become other people's problems).
- The Ayla cloud changes shape under us (current behavior frozen by tests would surface the drift).

Until then, a manual smoke test on the dev hub is the test suite.

## Memory references

- `feedback_har_files.md` — preferred workflow for capturing API payloads when in doubt.
- `fglair_cloud_write_quirks.md` — the per-DSN write-queue jam, 0.5°C setpoint snapping, and why sensor wakes must stay sparse.
- `fglair_temperature_scaling.md` — sensor scale (hundredths of °F, empirical) and setpoint scale (tenths of °C) facts; warns against the ayla-iot-unofficial range-map formula for this unit.
- `hubitat_supported_thermostat_modes.md` — the `setSupported*()` workaround used here (sendEvent the attribute with pre-quoted string values).

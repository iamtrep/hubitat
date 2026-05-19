<!--
Copyright (c) 2026 PJ
SPDX-License-Identifier: MIT
-->

# FGLair (Fujitsu Mini-Split) Integration

Parent/child integration for Fujitsu mini-split heat pumps that authenticate against the FGLair cloud (hosted on Ayla Networks' IoT platform). The manager app handles sign-in, token refresh, and periodic property polling. Each indoor unit is exposed as a child device implementing the standard `Thermostat` and `MotionSensor` capabilities plus a parallel custom surface for Fujitsu-specific modes and fan speeds.

## Components

| Component | Type | Description |
|---|---|---|
| **FGLair Manager** | App | Ayla authentication, periodic poll, command dispatch, orphan tracking, property discovery debug section |
| **Fujitsu Mini-Split** | Driver | One device per indoor unit; mode, setpoints, fan speed, room temp, outdoor temp, built-in occupancy sensor, fault and operational status, device metadata |

## Installation

1. Add `FGLairManager.groovy` as **Apps Code**, `FujitsuMiniSplit.groovy` as **Drivers Code** in the Hubitat UI.
2. Add the **FGLair Manager** user app.
3. Click **Login to FGLair**, enter your FGLair email + password, save.
4. The integration discovers indoor units within ~5 seconds and creates one child device per unit.

## Authentication

Email + password against Ayla's `/users/sign_in.json`. The access token (~24 h) is refreshed automatically 5 minutes before expiry. On refresh failure, the manager re-signs in using stored credentials. If both paths fail, the login error surfaces on the manager page.

Region (US / EU) is configurable on the manager page; the region setting drives the base URL and the `app_id` / `app_secret` pair used for sign-in.

## Devices

### Fujitsu Mini-Split

**Standard capability surface (canonical values only):**
- `thermostatMode` — `off`, `heat`, `cool`, `auto`
- `thermostatFanMode` — `auto`
- `temperature` — room temperature from `display_temperature`
- `heatingSetpoint` / `coolingSetpoint` / `thermostatSetpoint`
- `thermostatOperatingState` — `idle` / `heating` / `cooling` / `fan only`
- `motion` — `active` / `inactive` from the unit's built-in human-detection sensor (units that ship it)

**Custom Fujitsu surface (full unit enum):**
- `fujitsuMode` — `off`, `heat`, `cool`, `auto`, `dry`, `fan_only`
- `fanSpeed` — `auto`, `quiet`, `low`, `medium`, `high`
- `outdoorTemperature` — temperature reported by the outdoor unit (number)
- `errorCode` — raw Fujitsu error code (number; `0` = no fault). Transitions `0 → non-0` log a warning; `non-0 → 0` logs an info-level clear.
- `opStatus` — raw operational status (number). Logged at info level on change.
- `setFujitsuMode(String)` command — accepts the full mode enum
- `setFanSpeed(String)` command — accepts the full fan-speed enum

The standard surface keeps dashboards, Rule Machine triggers, and Alexa/Google integrations working for the canonical 80% case. The custom surface gives full access to `dry`/`fan_only` modes and `quiet`/`low`/`medium`/`high` fan speeds for automations that need them.

Setpoint range: 16–30°C (heat), 18–30°C (cool). Values outside the range are clamped with a warning logged.

**Device metadata** (visible on the device edit page's **Data** section, written via `device.updateDataValue`):
- `modelName` — e.g. `ASUG15LZTD : AP-WF1E`
- `firmwareVersion` — Fujitsu MCU firmware identifier
- `deviceName` — the WiFi adapter's identifier
- `commVersion` — Ayla communication protocol version

### Dual setpoint model

Fujitsu units have one physical setpoint (`adjust_temperature`), but the driver tracks heating and cooling setpoints independently:

- `setHeatingSetpoint(t)` always updates `heatingSetpoint` locally. Writes to the unit when mode is `heat`, `auto`, `dry`, or `off`.
- `setCoolingSetpoint(t)` always updates `coolingSetpoint` locally. Writes to the unit when mode is `cool`, `auto`, `dry`, or `off`.
- When mode transitions to `heat`, the stored `heatingSetpoint` is auto-pushed to the unit. Same for `cool`.
- `thermostatSetpoint` is the device-confirmed value — updated only on the next poll, mirroring the built-in Ecobee integration. `heatingSetpoint` / `coolingSetpoint` are user-intent presets and update immediately on the command.

### Standard command policy

The standard Hubitat `Thermostat` and `Thermostat Fan Mode` capabilities define commands that exist regardless of the supported-mode list. The driver maps them as follows:

| Command | Behavior |
|---|---|
| `auto()` / `cool()` / `heat()` / `off()` | route to `setThermostatMode(...)` |
| `emergencyHeat()` | warn, route to `setThermostatMode("heat")` (no aux strip on mini-splits) |
| `fanAuto()` | route to `setThermostatFanMode("auto")` |
| `fanOn()` | warn, route to `setFanSpeed("high")` (closest to "continuous full fan") |
| `fanCirculate()` | warn, route to `setFanSpeed("low")` (closest to "gentle circulation") |

### Optimistic updates

The `optimisticUpdates` driver preference (default **on**) controls how non-setpoint attributes behave on write:

- **On** — `thermostatMode`, `fujitsuMode`, `thermostatOperatingState`, `thermostatFanMode`, and `fanSpeed` flip immediately on the command, before the cloud or unit confirms.
- **Off** — these attributes wait for the next poll cycle (truthful device state).

`heatingSetpoint` / `coolingSetpoint` always update immediately on command (user intent, not device state). `thermostatSetpoint` always waits for poll confirmation.

## Polling

Default poll interval is 60 s. Configurable to 30 / 60 / 120 / 300 s. Each cycle fetches the device list, then issues one `properties.json` request per indoor unit.

The Ayla cloud caches property values aggressively (raw values can sit unchanged for hours despite real-world change), so before every property read the manager POSTs `refresh: 1` to the unit's `refresh` property and waits 2 seconds before issuing the GET. This forces the unit to publish fresh values to the cloud. Effective end-to-end staleness from physical event to Hubitat event is bounded by `pollRate + ~2 s`.

## Orphans

If a unit is removed from your FGLair account, the Hubitat child is flagged as orphaned on the manager page but **not auto-deleted**. Click **Remove orphaned devices** on the manager to remove them explicitly.

## Discovered Properties (debug)

The manager's main page lists every Ayla property name observed across polls, with each property's last-seen value. This is a development aid for adding coverage of properties the integration doesn't expose yet — different Fujitsu models report different property sets, and the discovery section makes the unit's actual property surface visible without dumping logs. A **Reset discovered properties** button clears the accumulator if you want a fresh snapshot.

## Quirks worth knowing

- **Sensor scale is `°F` hundredths.** Empirically, the unit reports `display_temperature` and `outdoor_temperature` as integers in hundredths of Fahrenheit (e.g. `7000` = 70.0°F). The driver converts to the hub's `getTemperatureScale()` regardless. If your unit reports sensors in Celsius, this assumption needs a per-unit override (not currently implemented).
- **Setpoint scale is `°C` tenths.** `adjust_temperature` is reported as tenths of Celsius (e.g. `180` = 18.0°C), independent of the unit's display scale.
- **`supportedThermostatModes` / `supportedThermostatFanModes`** are written via `sendEvent` directly. The platform's `setSupported*()` methods are not reliably bound to user-namespaced drivers on current firmware.

## Acknowledgments

Protocol facts (endpoints, body shapes, integer codes) lifted from:

- **[ayla-iot-unofficial](https://github.com/rewardone/ayla-iot-unofficial)** (MIT) — Python reference for the Ayla IoT platform and Fujitsu FGLair.
- **[Home Assistant `fujitsu_fglair` component](https://github.com/home-assistant/core/tree/dev/homeassistant/components/fujitsu_fglair)** (Apache 2.0) — for capability mapping inspiration.

This integration is an independent reimplementation; no source from either project is incorporated.

## License

MIT — see individual source files for the full license text.

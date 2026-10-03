<!--
Copyright (c) 2026 PJ
SPDX-License-Identifier: MIT
-->

# Thermostat Scheduler+

A thermostat scheduler for Hubitat Elevation with named profiles, several schedules per zone, holds, mode overrides, an eco offset and pause handling. Each program publishes what it is doing on its own status device, so rules, dashboards, Maker API and other apps can read and control it. The parent app also serves a small HTTP API.

It covers what the built-in Thermostat Scheduler does and fixes four of its known problems: the eco offset stacking when changed while on, eco off resuming the time schedule during Away, Away not being restorable after a manual change, and Rule Machine reaching only one scheduler per thermostat.

## Files

| File | Type | Name on the hub |
|---|---|---|
| `ThermostatSchedulerPlus.groovy` | App (parent) | Thermostat Scheduler+ |
| `ThermostatSchedulerPlusProgram.groovy` | App (child) | Thermostat Scheduler+ Program |
| `ThermostatSchedulerPlusStatus.groovy` | Driver | Thermostat Scheduler+ Status |

## Install

1. Add the two apps in **Apps Code** and the driver in **Drivers Code**.
2. In **Apps**, choose **Add user app** and pick **Thermostat Scheduler+**. The parent enables OAuth for itself on install.
3. Open the parent and choose **Create New Program**. Pick the thermostats, name the program and press **Done**.

A program controls one or more thermostats that share a schedule, usually one zone. Saving it creates the status device **"<program> scheduler"** (for example "Living room scheduler"). The device belongs to the program and is deleted with it.

## Programs

The program's main page shows what is in effect and why, the next transition, a table of the thermostats, and the controls: **Apply now**, **Advance**, eco on or off, **Pause** or **Resume**, **End hold**, and a hold picker. Three pages hold the setup: **Profiles**, **Schedules**, and **Overrides and options**.

A new program starts with the profiles Home, Sleep and Away, one schedule, Normal, with Wake at 06:30 (Home) and Night at 22:00 (Sleep) every day, and an override from the Away mode to the Away profile.

### Profiles

A profile is a named set of values: heating setpoint, cooling setpoint, fan mode and thermostat mode. Each value is optional; a blank value leaves the thermostat as it is. Setpoints can come from number hub variables. The program marks the hub variables it uses as in use, applies a change to one at once, and follows a rename. Schedules, mode overrides and holds use profiles by name, so editing a profile changes every place that uses it. Renaming a profile updates those references. A profile in use cannot be deleted; the page lists where it is used.

Setpoints use the hub's temperature scale. Valid values are 0 to 40 °C or 32 to 104 °F.

### Schedules

A program can have several schedules, for example Normal and Vacation. One is active, and the `setSchedule` command switches it. Each schedule is one of two types:

- **Time of day:** day groups, each with its own periods. Every day belongs to exactly one group. A period starts at a fixed time, at sunrise or sunset with an offset in minutes, or at the time held in a DateTime hub variable, and uses a profile or its own custom setpoints. Before the first period of a day, the previous day's last period is in effect. Taking a day out of its group starts a new group with a copy of its periods.
- **Hub mode:** rows that map one or more hub modes to a profile or custom setpoints. A mode with no row leaves the thermostats as they are. Modes are matched by id, so renaming a mode keeps its row.

### Mode overrides

An override maps a hub mode to a profile and applies on top of any schedule while the hub is in that mode. A new program maps the hub mode named Away, when there is one, to the Away profile, which reproduces the built-in Away row. Modes are matched by id, so renaming the mode keeps the override.

### Eco offset

Eco lowers the heating setpoint and raises the cooling setpoint by the offset (default 2.0 °C or °F, in the hub's scale). It is applied to whatever the schedule, override or hold resolves to and is never stored as a setpoint, so changing the offset while eco is on recalculates from the schedule. By default eco also applies on top of mode overrides; the **Also apply eco on top of mode overrides** option turns that off.

### Holds

A hold replaces the scheduled values until it ends. It holds a profile or explicit setpoints, with one of four ends:

| End | Meaning |
|---|---|
| `next` | Until the next schedule transition, or until a mode override starts or ends |
| minutes | For that many minutes (1 to 999999) |
| ISO time | Until a date and time, for example `2026-10-03T22:00:00-04:00` |
| `indefinite` | Until resumed |

**Advance** starts a `next` hold on the next period's values. **Resume** (or **End hold**) clears the hold and writes the whole target again. Holds with a time or no end stay in force through mode changes.

### Precedence

The program resolves one target from these layers, highest first, and applies the eco offset last: paused or restricted, hold, mode override, active schedule. Turning one layer off re-resolves the rest, so nothing stale is left behind.

### Writing to thermostats

- Only values that differ from the thermostat's current attributes are written. In heat or cool mode only that setpoint is written; in auto mode the required heat/cool separation (default 2.0 °C or °F, in the hub's scale) is enforced. A thermostat that is off gets no setpoints.
- **Apply now** writes the whole target even when nothing differs.
- A thermostat mode changed by someone else (a rule, another app, a person) triggers an apply for that thermostat, so the matching setpoint is written at once.
- A setpoint changed at the thermostat stays until the program's next write. Meanwhile `status` is `manual`.
- With **Check each write** on (default), the program reads the attributes back 30 seconds after a write and sets `lastApply` to `ok`, `partial` or `failed`, with a warning in the log for writes that did not take. It never resends; resending is Hubitat's Command Retry, a per-device platform setting.
- After a hub restart the program re-applies the schedule; **Apply the schedule after a hub restart** turns that off.

### Pause and restrictions

The status device's switch is the program's own on/off: off pauses it. Optional restrictions pause it too: a switch in a chosen state, a time window (fixed times or sunrise/sunset with offsets in minutes), days of the week, and hub modes. Settings choose what happens:

- **While paused:** leave the thermostats as they are, or turn them off.
- **When the pause ends:** restore the recorded thermostat mode and apply the schedule (default), or leave the thermostats off.

## Status device

Driver **Thermostat Scheduler+ Status**, capabilities Switch, Actuator and Refresh. It does not declare Thermostat, so it stays out of thermostat pickers. Attributes with nothing to report read `none`.

| Attribute | Values |
|---|---|
| `switch` | `on` running, `off` paused |
| `status` | `schedule`, `mode`, `hold`, `manual`, `paused`, `restricted` |
| `schedule` | Active schedule name |
| `profile` | Profile in effect, `custom` for explicit setpoints, or `none` |
| `heatingTarget`, `coolingTarget` | Resolved setpoints in °C or °F (hub's scale), eco offset included. When no target applies to one of them, it keeps its last value; `status` and `profile` show when no target applies |
| `holdEnd` | `next`, `indefinite`, an ISO time, or `none` |
| `nextTransition` | ISO time of the next period, or `none` |
| `nextProfile` | Profile of the next period, or `none` |
| `eco` | `on`, `off` |
| `ecoOffset` | Offset in °C or °F (hub's scale) |
| `lastApply` | `ok`, `partial`, `failed` |

| Command | Arguments |
|---|---|
| `on`, `off` | Run, pause |
| `resume` | Clear the hold and apply |
| `applyNow` | Write the whole target now |
| `advance` | Apply the next period's values now, as a `next` hold (time schedules only) |
| `holdProfile` | profile, end (`next`, `indefinite`, minutes or ISO time; blank means `next`) |
| `holdSetpoints` | heating, cooling (at least one), end |
| `setSchedule` | Schedule name |
| `setEco` | `on` or `off` |
| `setEcoOffset` | Number from -10 to 10 |
| `refresh` | Re-publish the status |

Through Maker API, a command with several arguments takes them comma-joined in one path segment: `/devices/<id>/holdProfile/Sleep,next`.

## HTTP API

The parent app serves the API with one OAuth token for all programs. The **API and access** page shows the base URL and token, and has **Reset token**. Requests from the local network are allowed by default; requests through the Hubitat cloud are refused until **Allow requests through the Hubitat cloud** is on.

| Route | Purpose |
|---|---|
| `GET /programs` | Every program with its status |
| `GET /programs/{id}` | Configuration, thermostats and status, with a `revision` |
| `POST /programs/{id}/command` | Any status device command, as JSON |

Example, holding Sleep until the next transition:

```bash
curl -s -X POST -H 'Content-Type: application/json' \
  -d '{"command":"holdProfile","profile":"Sleep","end":"next"}' \
  "http://192.0.2.10/apps/api/<parent-app-id>/programs/<program-id>/command?access_token=<token>"
```

The reply carries the new status:

```json
{"ok": true, "id": 12, "name": "Living room", "switch": "on", "status": "hold", "schedule": "Normal",
 "profile": "Sleep", "heatingTarget": 18.0, "coolingTarget": 26.0, "holdEnd": "next",
 "nextTransition": "2026-10-04T06:30:00-04:00", "nextProfile": "Home", "eco": "off", "ecoOffset": 2.0, "lastApply": "ok"}
```

Errors carry an `error` field: 400 for a body that is not a JSON object or a bad command or argument, 403 when local or cloud access is off, 404 for an unknown program. A malformed JSON body sent as `application/json` gets an empty 200 from the platform before the app runs.

While the parent's debug logging is on, `POST /programs` with `{"label": "<name>"}` creates a program (201) or returns the existing one with that label (200). Test tooling uses it; with debug logging off the route answers 404.

## Tests

Run from the repository root. On-hub tests target the default hub in `.hubitat.json` (an `@hubname` argument picks another). The slow ones are listed with `RUN_SLOW_TESTS=1`. Phases and known gaps are in `tests/TEST_PLAN.md`.

| Test | What it covers |
|---|---|
| `java -cp groovy-all-2.4.21.jar groovy.ui.GroovyMain apps/ThermostatSchedulerPlus/tests/test_core.groovy` | Core unit tests, off the hub |
| `RUN_SLOW_TESTS=1 bash apps/ThermostatSchedulerPlus/tests/test-tsp.sh` | Behavior on virtual thermostats with a test clock |
| `RUN_SLOW_TESTS=1 bash apps/ThermostatSchedulerPlus/tests/test-tsp-retry.sh` | Write check, with the Stubborn Thermostat test driver |
| `bash apps/ThermostatSchedulerPlus/tests/test-tsp-api.sh` | HTTP API, on the program `test-tsp.sh` provisions |
| `RUN_SLOW_TESTS=1 bash apps/ThermostatSchedulerPlus/tests/test-tsp-parity.sh` | Side by side with a built-in Thermostat Scheduler |

## More

- [Apps](../README.md)

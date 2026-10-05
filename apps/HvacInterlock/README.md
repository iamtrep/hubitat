<!--
Copyright (c) 2026 PJ
SPDX-License-Identifier: MIT
-->

# HVAC Interlock

Gates heating and cooling equipment by season and open windows.

HVAC Interlock decides, for each group of equipment, whether it may run: the season must allow it, and in a group with openings, nothing may have been left open. It reads the season from the device [HVAC Season Manager](../HvacSeasonManager/README.md) creates. A group is a set of equipment that shares the same season policy and the same openings, usually one scheduler or one directly driven unit.

A group works in one of two ways:

- **Permit:** a scheduler owns the thermostats (setpoints and mode). The group only drives a switch the scheduler pauses on.
- **Direct:** the group sets the thermostat mode itself, for equipment without a scheduler or with one that leaves the mode alone.

## Files

| File | Type | Name on the hub |
|---|---|---|
| `HvacInterlock.groovy` | App (parent) | HVAC Interlock |
| `HvacInterlockGroup.groovy` | App (child) | HVAC Interlock Group |
| `HvacInterlockGroupStatus.groovy` | Driver | HVAC Interlock Group Status |

## Install

1. Add the driver in **Drivers Code**, then the two apps in **Apps Code**: the parent first, then the group.
2. In **Apps**, choose **Add user app** and pick **HVAC Interlock**. Pick the Season device and, if you want alerts, the notification and speech devices.
3. Choose **Add a group** for each group of equipment. Name it, pick the style, the season policy and the openings, and press **Done**.

Saving a group creates its device **"<group> status"**. The device belongs to the group, follows its name, and is deleted with it.

## Group settings

| Setting | Default | Notes |
|---|---|---|
| Group name | | Required; names the status device |
| Control style | Permit | Permit or Direct |
| Thermostats | | Direct only |
| Season policy | Permit: allowed in winter, spring and fall, not in summer. Direct: off in every season | Permit: allowed or not per season. Direct: off, heat, cool or auto per season |
| Contact sensors | none | The openings this group cares about |
| When something is open | Block | Block turns the equipment off; Warn only raises the alert |
| Open for at least | 10 minutes | How long an opening must stay open before the alert |

## How a group decides

1. The season policy gives what the group **wants** this season: run or not (permit), or a mode (direct).
2. The **openings alert** is raised when an opening has been open for the open delay while the group wants to run. It clears when everything has been closed for 5 minutes (fixed), or when the season stops the group. A window opened again during those 5 minutes keeps the alert up.
3. With **Block**, the alert stops the equipment. With **Warn**, the equipment keeps running and only the alert is raised.
4. The **effective state** is off when the season or a blocking alert stops the group, and what the group wants otherwise.

An open window in summer next to a baseboard group stays quiet, since the group does not want to run then; the same window next to a cooling mini-split raises the alert.

Before the group has received a season, it changes nothing and logs a warning. A window already open when the group starts counts from when it opened.

## Permit groups and schedulers

The status device's switch is on while the equipment may run. Set this once on each scheduler in the group; the group's page shows the exact device name:

- **Thermostat Scheduler+ program:** *Pause when this switch is…* the status device, *…in this state* off, *While paused* = Turn thermostats off (its default is to leave them), *When the pause ends* = Restore the thermostat mode and apply the schedule.
- **Built-in Thermostat Scheduler:** *Disable when <status device> is off* and *Turn thermostats off when restricted*.

Thermostat Scheduler+ is recommended. The built-in works, with limits:

| | Thermostat Scheduler+ | Built-in Thermostat Scheduler |
|---|---|---|
| When the group allows the equipment again | Restores the mode and applies the current setpoints | Restores the mode; the old setpoints stay until its next period |
| Checking the pause took effect | Its device's `status` reads `restricted` | Nothing to read |

HVAC Interlock never talks to the scheduler, so one group can gate a mix of both.

## Direct groups

A direct group writes the thermostat mode when its effective state changes, and only then: a mode changed by hand stays until the group's state next changes. After a hub restart it corrects any thermostat whose mode differs from the group's state. It skips a thermostat whose supported modes do not include the target, with a warning. It never reacts to hub mode changes.

## The status device

| Attribute | Values |
|---|---|
| `switch` | `on` while the equipment may run |
| `contact` | `open` while the openings alert is raised, `closed` otherwise |
| `blockReason` | `none`, `season`, `openings` |
| `openContacts` | The openings behind the alert, comma-separated, or `none` |

The `on` and `off` commands do nothing: the group is the switch's only writer.

## Notifications

With notification or speech devices set on the parent, a group raising its alert sends "<group>: <open contacts> open", and "<group>: alert cleared" when it clears. For anything else, a Notifier instance can watch the status devices' `contact` attribute, which reads `open` during the alert.

## Tests

- `tests/test_core.groovy`: unit tests of the season policy, alert timing and mode decisions, run off the hub under Groovy 2.4.21: `java -cp groovy-all-2.4.21.jar groovy.ui.GroovyMain tests/test_core.groovy`.
- `tests/test-ilk-permit.sh` and `tests/test-ilk-direct.sh`: behavior tests on a hub, generated from the two `tests/spec-interlock-*.yaml` files: `bash tests/test-ilk-permit.sh [@hub]`. They take the season from HVAC Season Manager's test device, so HVAC Season Manager's test rig must exist, and a debug-only close delay in seconds keeps them short.

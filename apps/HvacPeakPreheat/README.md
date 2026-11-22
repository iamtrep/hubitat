<!--
Copyright (c) 2026 PJ
SPDX-License-Identifier: MIT
-->

# HVAC Peak Pre-heat

Holds a thermostat through a Hydro-Québec Winter Credit peak event, by the plan for the event's anchor window.

The app reads the event's phase from the **Hydro peak** device of [Hydro-Québec Peak Period Manager](../HydroPeakEvents/) and the plan from a **Hilo Winter Credit** device ([Hilo integration](../../integrations/Hilo/)). It only holds the thermostat's heating setpoint, and it releases only a hold it placed, with `resumeProgram`. It never edits the thermostat's schedule or comfort settings.

## Files

| File | Type | Name on the hub |
|---|---|---|
| `HvacPeakPreheat.groovy` | App | HVAC Peak Pre-heat |

## Install

1. Add the app in **Apps Code**.
2. In **Apps**, choose **Add user app** and pick **HVAC Peak Pre-heat**.
3. Pick the Hydro peak device, the plan device and the thermostat, then press **Done**.

The thermostat needs `setHeatingSetpoint` and `resumeProgram` (an Ecobee, for example).

## What it does

Each time something changes, the app works out the whole decision again from the current values. The first row that matches wins. A change on the Hydro peak or plan device is acted on 2 seconds after the device's last change, because both devices send several values at once and the hub applies them over a few hundred milliseconds: acting on the first would mix new and old values. A mode change waits 5 seconds. The nightly time, the silence fallback and hub start act at once, unless device changes are still settling, and then they wait for them.

| # | When | Does |
|---|---|---|
| 1 | The hub is in an away mode | Nothing: another app owns the thermostat in Away. A hold the app placed stops being its own |
| 2 | Phase `none`, `skipped` or `aborted` | Releases its hold with `resumeProgram` |
| 3 | Phase `announced`, morning event, from the nightly time on the evening before the event | Holds 20.5 °C (no night setback) |
| 4 | Phase `anchor` | Plan "at least": holds 22.5 °C. Plan "at most": holds 22.5 °C until `anchorStop` is true, then 18 °C (morning) or 19.5 °C (evening). No usable plan (none, or the plan device names another event): no pre-heat. A morning event keeps the night hold (20.5 °C); an evening one stays on the thermostat's program, and a hold the app placed is released. When a plan arrives, the rows above apply |
| 5 | Phase `gap` | Holds 22.5 °C |
| 6 | Phase `active` | Advice `keep` and `eventUsed` below `keepUse`: holds 20.5 °C; otherwise 18 °C |
| 7 | Anything else (an evening event announced, a morning event before the nightly time) | Releases its hold |

**The silence fallback.** During an "at most" pre-heat, the stop comes from the plan device's stop check, whose heartbeat is `anchorChecked`: it changes every minute of an announced event's "at most" anchor window. When neither `anchorChecked` nor `anchorStop` has changed for 10 minutes, the app holds the stop temperature, so the pre-heat can't run to the end of the window without its stop check. Other plan attributes, such as `anchorUsed` from the power stream, still re-run the decision but don't count as a heartbeat: they keep arriving when the stop check doesn't run (a plan device that hasn't learned of the event). The 10 minutes count from the later of the last heartbeat and the start of the anchor window, so with no heartbeat at all the stop comes 10 minutes into the window. When the heartbeat comes back with `anchorStop` false, the pre-heat resumes. The fallback can stop the pre-heat early, never late.

**Holds.** A hold is sent when the thermostat's heating setpoint differs from it, and also when the app doesn't own the hold yet. On an Ecobee, any setpoint written creates a hold, even one equal to the current setpoint, and only `resumeProgram` returns to the schedule; sending it anyway makes sure the program can't move the setpoint later. 30 seconds after a hold, the app warns in the log if the thermostat doesn't show it.

**The plan's event.** When the plan device and the Hydro peak device both name an event (`eventDate`, `window`) and they differ, the plan is treated as missing. The 22.5 °C pre-heat never runs on a missing plan.

**Triggers.** A change of the Hydro peak device's `phase`, `window` or `eventDate`; any change on the plan device; a mode change, acted on 5 seconds later so that another app's `resumeProgram` on the way back from Away lands first; the nightly time; hub start; Done.

## Settings

| Setting | Default | Notes |
|---|---|---|
| Hydro peak device | | `phase`, `window`, `eventDate` |
| Winter credit plan device | | `anchorPlan`, `anchorStop`, `anchorChecked`, `advice`, `keepUse`, `eventUsed`, `eventDate`, `window` |
| Thermostat | | Needs `setHeatingSetpoint` and `resumeProgram` |
| Away modes | the mode named Away | No hold while the hub is in one of them |
| Holds | 20.5, 22.5, 18, 19.5, 18, 20.5 °C | Night before, pre-heat, morning stop, evening stop, event, event with keep advice. On a °F hub: 69, 72, 64, 67, 64, 69 |
| Nightly start | 22:30 | The thermostat's Sleep start. Before 06:00, it counts on the event's own day |
| Silence fallback | 10 minutes | 1 to 60; minutes without `anchorChecked` or `anchorStop` before an "at most" pre-heat stops |

The status section of the app page shows the current decision and whether the app owns the hold.

## Tests

`tests/test_core.groovy` runs the decision off the hub: every row, morning and evening, both plans, keep and cut, away, missing and stale inputs, and the silence fallback.

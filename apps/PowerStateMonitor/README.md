<!--
Copyright (c) 2026 PJ
SPDX-License-Identifier: MIT
-->

# Power State Monitor

Publishes a load's operating state (off, normal, turbo, ...), read from its power meter with start-up surges and ramps filtered out.

## What it does

The app reads one power meter and maps its readings to 2 to 5 named states, each starting at a set wattage. It publishes the state on a Power State device, which rules, dashboards and other apps can subscribe to. One instance watches one load.

A reading becomes the state only after it has held for the settle time (20 s by default). Surges at start-up and ramps between speeds last a few seconds, so they are replaced by the next reading before they settle and never show as a state. A reading above the surge ceiling never becomes a state; if one holds for the unknown time (2 minutes by default), the state becomes `unknown` and the app logs a warning, so an operating mode the states don't cover shows up instead of being taken for the top state. Hysteresis keeps a state until a reading passes its edge by the set margin.

With a source switch selected, the device also reports what put the load in the state that switch commands: `switch` while the switch is on, `other` while it is off (a wall control, a timer in the load itself). In every other state it reports `none`.

## Why a hold time

Smart plugs report power on change, not on a clock: a report goes out when the reading moves by a set step (the plug's "power report delta"). This is send-on-delta, or level-crossing, sampling [1]. Two things follow:

- **Silence is information.** Between reports, the power has stayed within one step of the last report, so the last reading stands until the next one (a zero-order hold).
- **Sample-count filters don't fit.** A moving average or a median over the last N readings assumes evenly spaced samples [2]. Here the reports bunch up when power changes: on one ventilator plug, a quarter of a month's reports were surges that held 7 s at most. Counted by reports, a surge outweighs hours of steady draw. Weighted by how long each reading held, it is a few seconds.

So the filter is a steady-state test on held time: a reading that has held for the settle time is a steady state, and the state changes between steady states. This is how nonintrusive load monitoring treats a multi-state appliance: a finite state machine whose states are steady power levels, with the transients between them set aside [3][4]. Hysteresis around each edge plays the part of a Schmitt trigger for a level that sits near an edge.

Pick the settle time above the longest surge the load shows and the edges between its steady levels, at least one report step away from each level. The plug only reports a change of one step or more, so a lost report leaves a wrong state until the next change; **Ask the meter for a reading every** sets a periodic refresh that corrects it.

## Install

1. Add `PowerStateMonitor.groovy` in **Apps Code** and `PowerStateMonitorState.groovy` in **Drivers Code**.
2. In **Apps**, choose **Add user app** and pick **Power State Monitor**.
3. Pick the power meter, name the states and set where each starts, then press **Done**.

Saving creates the Power State device. Its label follows the app's: an app named "HRV" gets "HRV State".

## Settings

| Setting | Default | Notes |
|---|---|---|
| Power meter | | Any device with the Power Meter capability |
| Number of states | 3 | 2 to 5 |
| State N name, From (W) | off from 0, on from 15, high from 60 | The first state starts at 0 W; each next one must start higher. `unknown` is reserved |
| Surge ceiling (W) | none | Readings above it never become a state |
| Hysteresis (W) | 5 | |
| Settle time (seconds) | 20 | 1 to 3600 |
| Unknown time (seconds) | 120 | How long a reading above the ceiling holds before the state is `unknown` |
| Refresh every (minutes) | 0 (never) | Calls the meter's `refresh()` |
| Source switch, the state it commands | | Optional; sets `stateSource` |

Example, a ventilator on a plug that reports in 10 W steps: off from 0, normal from 20, turbo from 60, ceiling 90 W. Its steady levels are about 47 W and 75 W, and its surges reach 135 W for up to 7 s.

## The Power State device

| Attribute | Values |
|---|---|
| `powerState` | a state name, or `unknown` |
| `stateSource` | `switch`, `other`, `none` |

The device has no commands. The state changes one settle time after the last reading of a change, so it lags the load by about that much.

## Tests

`tests/test_core.groovy` runs the app's pure core off-hub, including replays of a ventilator plug's real report sequences.

## References

1. M. Miskowicz, "Send-On-Delta Concept: An Event-Based Data Reporting Strategy," *Sensors* 6(1), 49–63, 2006. [mdpi.com/1424-8220/6/1/49](https://www.mdpi.com/1424-8220/6/1/49)
2. K. J. Åström and B. Bernhardsson, "Comparison of Riemann and Lebesgue sampling for first order stochastic systems," *Proc. 41st IEEE CDC*, 2011–2016, 2002. [lup.lub.lu.se/record/537765](https://lup.lub.lu.se/record/537765)
3. G. W. Hart, "Nonintrusive appliance load monitoring," *Proc. IEEE* 80(12), 1870–1891, 1992. doi:10.1109/5.192069
4. C. Klemenjak and P. Goldsborough, "Non-Intrusive Load Monitoring: A Review and Outlook," 2016, §2.2 (multi-state appliances) and §3.1 (steady-state signatures). [arXiv:1610.01191](https://arxiv.org/abs/1610.01191)

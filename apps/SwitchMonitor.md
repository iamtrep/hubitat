<!--
Copyright (c) 2025-2026 PJ
SPDX-License-Identifier: MIT
-->

# Switch Monitor

A Hubitat Elevation app that watches switches that must stay on, or stay off, such as a freezer outlet or a pump. When a switch stays in the wrong state past a grace period, the app sets it back and notifies. For switches that report power, it can also notify when the load drops below a minimum, for example a freezer compressor that stopped.

## How it works

Switches are organized in groups. Each group has its own target state, devices, timing, notifications and load monitoring.

**Correction.** When a switch leaves its target state, the app waits the grace period. If the switch is still wrong, it notifies and sends the command, waits the verification delay and checks again. It repeats at the retry interval until the switch responds or the maximum number of attempts is reached (0 retries without limit).

**Load monitoring** (must-stay-on groups only). When a switch that is on reports power below the group's minimum for the load grace period, the app notifies, then repeats at the reminder interval until power recovers.

**Startup and save.** On hub start the app refreshes the switches that support it. On start and on save it checks every group: a switch wrong for longer than the grace period is corrected at once, and one wrong for less is checked when its grace period ends.

**Power outage** (optional). Select a switch that is on during an outage, or a device with the PowerSource capability that reports something other than mains. During an outage the app stops correcting must-stay-on groups and clears low-load alerts. When power returns it refreshes every monitored switch and checks them again.

The top of the app's page lists switches in the wrong state, with a button to correct each one, and **Check all devices now**.

## Settings

### Per group

| Setting | Default | Meaning |
|---|---|---|
| Switches must stay | ON | Target state |
| Switches to monitor | _(none)_ | |
| Name this group | Group *n* | |
| Notification devices | _(none)_ | |
| Notification prefix | _(none)_ | Added to the start of each message, e.g. for a Pushover title |
| Notify when correcting a switch | on | |
| Notify when a switch fails to recover | on | |
| Notify when load drops below minimum | on | |
| Enable load monitoring | off | Must-stay-on groups with at least one power-reporting switch |
| Minimum load (W) | 0 | 0–10000 |
| Minutes to wait before alerting on low load | 2 | 1–60 |
| Minutes between repeat notifications | 30 | 5–1440 |
| Minutes to wait before taking action | 5 | 0–60 |
| Additional seconds to wait | 0 | 0–59, added to the minutes |
| Seconds between retries | 30 | 5–300 |
| Seconds to wait before verifying switch state | 10 | 1–60 |
| Maximum retry attempts | 10 | 0–100; 0 is unlimited |

Use separate groups for devices that need a different minimum load or timing.

### App-wide

| Setting | Meaning |
|---|---|
| Outage indicator switch | On means a power outage |
| Power source device | Not on mains means a power outage |

Info, debug and trace logging can be turned on separately; debug and trace logging turn themselves off after 30 minutes.

## Installation

In **Apps Code** → **New App** → **Import**, paste:

```
https://raw.githubusercontent.com/iamtrep/hubitat/main/apps/SwitchMonitor.groovy
```

Then **Apps** → **Add User App** → **Switch Monitor**, and **Add new group**.

## License

MIT License. See the source file header for the full text.

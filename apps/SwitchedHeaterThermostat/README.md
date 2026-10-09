<!--
Copyright (c) 2026 PJ
SPDX-License-Identifier: MIT
-->

# Switched Heater Thermostat

Turns plain switches (smart plugs or relays feeding resistive heaters) into a thermostat, and tells you when it cannot do the job.

The app reads one or more temperature sensors and feeds the combined reading to a Virtual Thermostat. It turns the heater switches on and off as that thermostat's operating state changes. It protects against frost whatever the thermostat mode, and it raises an alert when a heater does not respond or draws the wrong power, when the heaters run without warming the room or past a time limit, or when no sensor reports.

## Files

| File | Type | Name on the hub |
|---|---|---|
| `SwitchedHeaterThermostat.groovy` | App | Switched Heater Thermostat |

## Install

1. Add the app in **Apps Code**, then choose **Add user app** and pick **Switched Heater Thermostat**.
2. Under **Thermostat**, pick an existing Virtual Thermostat (built-in driver) or turn on **Create a Virtual Thermostat for this app**. Another driver is refused. A created thermostat is deleted with the app. If you later pick a different thermostat, a button removes the unused created one. Uninstalling the app turns the current heaters and fault switches off. A heater you removed from the list earlier is left as it was.
3. Pick the heater switches and the temperature sensors.
4. Choose what the heaters do when no sensor reports, and the alert devices.
5. Press **Done**.
6. Set the thermostat's mode to Heat. A created thermostat starts in mode Off.

On every Done the app sets the thermostat's supported modes to `off` and `heat`. Setpoints and mode stay on the thermostat, so dashboards, voice assistants and schedulers control it like any thermostat.

Keep the Virtual Thermostat's **Enable demonstration mode** preference off. With it on, the device changes its own temperature every 2 seconds and the heaters follow that instead of the room.

## How heating is decided

The app writes the combined sensor reading to the thermostat with `setTemperature`. The sensors are combined by average (default) or minimum, rounded to 0.1 degree (°C or °F, following the hub's scale), ignoring sensors that are quiet (see Quiet sensors). The thermostat decides `heating` or `idle` itself, using its hysteresis preference (default 0.5 degree). With heat mode, setpoint 20 and the default hysteresis (checked on firmware 2.5.2.134), heating starts at 19.4, continues through 20.4, stops at 20.6 and stays off down to 19.6. Change the hysteresis on the thermostat to change that band.

The heaters' wanted state, in order of precedence:

1. Frost protection running: on.
2. Thermostat mode not `heat`: off.
3. Resting after the heating time limit: off.
4. No sensor reports: your choice of off, on or a fixed cycle.
5. Otherwise on while the thermostat is `heating`, off when it is anything else.

The app is the only writer of the heater switches. A heater switched by hand, or by another automation, is set back at once. If another automation keeps writing the same switches, the app reports a load fault. The fault switches below are owned the same way.

When the configuration is unusable (no thermostat, a thermostat on another driver, a heater also picked as a fault switch), the app turns the heaters off and does nothing else until the page is fixed.

## Frost protection

Set **Frost protection** (°C or °F) to turn the heaters on when the combined temperature falls below it, whatever the thermostat mode and the heating time limit rest. The heaters stay on until the temperature is 1 degree above the setting. It needs a live reading: with every sensor quiet, frost protection is off and the choice for no sensor applies. A notification goes out when it starts and when it stops, and the status at the top of the page shows it while it runs. It is not a fault, turns on no switch, and does not count toward the heating time limit. Blank turns it off.

## Minimum on and off times

**Keep the heaters on for at least** and **off for at least** (1 to 60 minutes, blank for none) hold the heaters in their state after they last changed. They delay only changes that come from the thermostat. Frost protection, thermostat mode off, the heating time limit and a sensor fault act at once.

## Keep-alive

**Re-send the heaters' state every** (1 to 120 minutes, blank for none) sends the wanted state to every heater at that interval, even when it already reports it. Use it for switches that turn themselves off without reporting it, and for one-way relays whose reported state is only an echo of the last command. Re-sends are not verified.

## Checking the heaters

After a command, the app checks each heater after **Time a heater has to confirm a command** (5 to 300 seconds, default 30). A heater that has not reached the commanded state gets a second command. If it still has not, it is unresponsive and raises the load fault. Every 5-minute check re-applies the wanted state, which retries unresponsive heaters.

### Power checks

For heaters that report `power`, and while **Check the power draw of heaters that report it** is on (default on), the app compares the draw with the switch state. The line between drawing and not drawing is **A heater that is on draws at least** (W, default 20):

- Switch on and draw under the line: the heater is not heating (unplugged, tripped, its own thermostat or safety cut-out open).
- Switch off and draw at or above the line: the switch does not cut the load (welded relay, or a switch that reports the wrong state).

A mismatch must last 2 minutes before it counts, which covers meters that report some seconds after the switch. A space heater with its own thermostat stops drawing when that thermostat is satisfied, which looks like the first case. Set the heater's own thermostat to its highest setting, or turn the power check off. Heaters without a meter get the switch-state checks only.

### Command Retry

Hubitat's Command Retry (Settings, Command Retry) makes the hub resend a command that a radio device did not acknowledge, up to 5 times. For heaters that can use it but have it off, the page and the log (a warning on every Done) say: *Command Retry is off for <heaters>. Turning it on (Settings, Command Retry) lets the hub resend a missed on or off before this app reports a load fault.* This is advice only: no notification, no fault, and the app never changes the setting. Virtual, LAN and most cloud devices cannot use Command Retry and get no advice.

## Warming check

**Alert when the heaters have been on this long without warming the room** (10 to 240 minutes, blank for off) notes the temperature when the heaters turn on. If they are still on that long afterwards and the temperature has not risen by **Warming means a rise of at least** (°C or °F, default 0.3), the warming fault is raised. It clears when the temperature has risen that much, or when the heaters turn off. The check suggests a cause (an open window, an undersized heater, a misplaced sensor) and turns nothing off. It is skipped while no sensor reports.

## Heating time limit

**Longest time the heaters may stay on** (10 to 1440 minutes, blank for no limit) caps how long the heaters stay on without a break. At the limit, the app turns them off for **Then keep them off for** (1 to 240 minutes, default 15), raises the limit fault, then lets them follow the thermostat again. The fault clears the next time the heaters turn off for another reason. The on-time survives a hub restart. The limit applies in every case except frost protection, including "keep on" when no sensor reports.

## When no sensor reports

The choice under **When no sensor reports** decides what the heaters do while every sensor is quiet or none has a reading:

| Choice | Heaters |
|---|---|
| Off (default) | off |
| On | on, still subject to the heating time limit |
| Cycle | on for a share of each cycle (default 30 % of every 30 minutes), starting with the on phase when the fault is raised |

Frost protection needs a live reading, so for rooms where frost matters pick on or cycle. Thermostat mode `off` always wins. During this fault the app sets the thermostat's operating state to match the heaters (`heating` or `idle`), since the thermostat has no temperature to decide from.

## Quiet sensors

A sensor is quiet when its last activity (any event, including battery and check-ins) is older than its quiet limit. Until the app has seen 5 gaps between a sensor's activity, the limit is **Count a sensor as quiet after** (10 to 1440 minutes, default 120). After that, the limit is twice the second-longest of the last 48 gaps, never under 30 minutes, so a setting under 30 minutes applies only while the app learns. Using the second-longest keeps a single outage from teaching the app that outages are normal.

A sensor that comes back with an unchanged value sends no event, so the app sees it at the next 5-minute check. One quiet sensor among several is left out of the temperature and notifies once (turn off **Also notify when some sensors go quiet** to skip it). It is not a fault.

## Faults and alerts

| Fault | Raised when | Cleared when |
|---|---|---|
| Sensor fault | every sensor is quiet, or none has a reading | any sensor reports again |
| Load fault | a heater stays unresponsive after its second command, or a power mismatch lasts 2 minutes | every heater responds and draws as expected |
| Limit fault | the heaters reach the heating time limit | the heaters next turn off for another reason |
| Warming fault | the heaters run the set time without the temperature rising enough | the temperature rises that much, or the heaters turn off |

Each fault has two outlets, both optional. **Notification devices** get one message when the fault is raised and one when it clears, with no repeats while it stays raised. A **switch per fault** is on while that fault is raised and off otherwise. One switch may serve several faults; it is then on while any of them is raised. Use these switches to start a backup heater, a siren or a Rule Machine rule. The app owns them and re-asserts them at each check, so do not switch them by hand.

Messages (`<name>` is the app label; temperatures show the hub's scale, such as `4.9 °C`):

- `<name>: no temperature, all sensors quiet (<sensors>). <policy>.` where the policy reads `Heat is off`, `Heaters kept on` or `Heaters on <d>% of every <p> minutes`
- `<name>: temperature back (<sensors>).`
- `<name>: lost control of <heaters>.` where each heater reads `<label> (commanded <on|off>, reads <value>)` or `<label> (<on|off>, draws <n> W)`
- `<name>: heaters under control again.`
- `<name>: heaters on for <max> minutes, the limit. Off for <rest> minutes.`
- `<name>: heating time back to normal.`
- `<name>: heaters on for <n> minutes and the temperature rose <rise> (expected <min>).`
- `<name>: warming check back to normal.`
- `<name>: frost protection on (<temperature>).` and `<name>: frost protection off (<temperature>).`
- `<name>: <sensors> quiet, left out of the temperature.`
- `<name>: <sensors> reporting again.`

## Restarts and code updates

On hub start, on every Done and on the first event after a code update, the app samples sensor activity, rewrites the temperature, re-applies the heaters (restarting the limit, rest, cycle, minimum-time, warming and keep-alive timers from its saved state), re-evaluates power and re-asserts the fault switches.

## Not in this version

Time-proportional control with an outdoor temperature, cooling, and per-heater power thresholds.

## Tests

`tests/test_core.groovy` (unit tests, run off the hub under Groovy 2.4.21), `tests/test-sht.sh` (behavior test on a hub, generated from `tests/spec-switched-heater-thermostat.yaml`: `bash tests/test-sht.sh [@hub]`) and `tests/TEST_PLAN.md` (coverage and known gaps).

<!--
Copyright (c) 2026 PJ
SPDX-License-Identifier: MIT
-->

# Switched Heater Thermostat test plan

Modes are those of TESTING.md. The on-hub test follows its closed-loop contract: `[PASS]`/`[FAIL]` lines, exit 0 when all pass, 1 on a failure, 2 on a setup error.

| Phase | Title | Mode | Test | Status |
|---|---|---|---|---|
| A | Core unit tests | 4, extraction variant | `test_core.groovy` | Done |
| B | Behavior | 1, with test drivers | `test-sht.sh` (from `spec-switched-heater-thermostat.yaml`) | Done |

## Phase A: core unit tests

`test_core.groovy` (119 checks) parses the block between the "Core (pure)" and "End core" markers of `SwitchedHeaterThermostat.groovy` and runs it under the pinned Groovy 2.4.21 jar, so the tests bind to shipped code. Groups:

- Readings: combining sensors by average and minimum, rounding to 0.1 degree, quiet sensors left out.
- Activity and quiet limit: the `quietAfter` limit until 5 gaps are seen, then twice the second-longest of the last 48 gaps with the 30-minute floor, and one outage not raising the limit.
- Wanted state: the order of precedence (frost, mode, rest, sensor fault, thermostat), for each sensor-fault choice.
- Minimum on and off times: holds that delay only the thermostat's own changes.
- Frost: start below the setting, stop 1 degree above, no reading.
- Warming: the rise against the temperature when the heaters turned on, a run that began without a reading.
- Verification: second command, unresponsive heater, an unconfirmed command blocking or not blocking a repeat.
- Cycle and limit: the on and off phases, the limit and rest times, the limit with "keep on".
- Power: both mismatch directions, the 2-minute grace, heaters without a meter.
- Alerts: every message text, fault raise and clear, and the lists of heaters and sensors.

Run: `java -cp groovy-all-2.4.21.jar groovy.ui.GroovyMain apps/SwitchedHeaterThermostat/tests/test_core.groovy`.

## Phase B: behavior

`test-sht.sh` runs the app instance `test-sht` on one adopted Virtual Thermostat (heat mode, setpoint 20, default hysteresis), two virtual temperature sensors, two Virtual Heater Plug (Test) devices (a switch with a simulated 1000 W meter that can be stuck, unplugged or set to any draw, and counts its commands), the four fault switches and a Notification Capture (Test) device, driven through a dedicated Maker API. The debug-only `testSecondsPerMinute` is 1, so every minute-based setting runs in seconds: the power grace is 2 s and `quietAfter` 10 is 10 s. The 24 cases run in order, and some start from the previous case's end state (named below). Each case also fails on an unexpected warn or error log line, except that 20 of the 24 cases allow warnings (set `allow_warnings`), whether or not they raise an alert.

| Case | What it checks |
|---|---|
| `heats-when-cold` | Cold sensors: thermostat `heating`, both heaters on, no load fault |
| `stops-when-warm` | Warm sensors: thermostat `idle`, both heaters off |
| `average-reaches-thermostat` | Sensors at 19 and 21 give 20.0 on the thermostat |
| `manual-toggle-reverted` | A heater switched off by hand is set back on |
| `mode-off-turns-heaters-off` | Thermostat mode `off` turns both heaters off |
| `stuck-heater-load-fault` | A heater that ignores commands raises the load fault, its switch and the message |
| `load-fault-clears` | The heater responds again: fault switch off, "under control again" message |
| `unplugged-heater-load-fault` | Switch on with no draw raises the load fault with the draw in the message |
| `power-while-off-load-fault` | Switch off with a draw raises the load fault |
| `limit-rests` | The heating time limit turns the heaters off and raises the limit fault |
| `limit-resumes` | After the rest, the heaters follow the thermostat again (starts in the rest of `limit-rests`) |
| `min-on-holds` | The minimum on time holds the heaters on after the room warms |
| `min-on-releases` | The heaters turn off after the minimum (starts from `min-on-holds`) |
| `frost-overrides-mode-off` | Frost protection turns the heaters on with the thermostat off, and notifies |
| `frost-stops` | With the setting at 15, frost keeps running at 15.0 and stops at 16.0 (starts from the previous case) |
| `keep-alive-resends` | A heater already on receives the state again at the keep-alive interval |
| `no-warming-fault` | Heaters on without a rise raise the warming fault and its message |
| `warming-clears` | A rise clears the warming fault (starts from `no-warming-fault`) |
| `all-sensors-quiet-fault` | Every sensor quiet: sensor fault, heaters off, thermostat `idle`, message with the policy |
| `sensor-back-clears-fault` | A sensor reports again: fault off, heaters follow the thermostat (starts from the previous case) |
| `sensor-fault-keep-on` | With "on", the heaters stay on during the sensor fault |
| `sensor-fault-cycle` | With "cycle", the heaters are on for the duty share, starting with the on phase |
| `cycle-off-phase` | The off phase of the cycle turns the heaters off (starts from the previous case) |
| `one-sensor-quiet-partial` | One quiet sensor among two: no fault, a "left out" notification |

Run: `bash apps/SwitchedHeaterThermostat/tests/test-sht.sh [@hub]`, about 450 s (7.5 minutes). The spec is the source; regenerate the script from it.

## Known gaps

- The learned quiet limit is tested only in the core. The rig forgets the sensors' learned gaps before each quiet case (the *Forget the sensors' learned gaps* button), since they would otherwise learn from the test's own timing.
- `minOffMinutes` is tested only in the core. Phase B covers `minOnMinutes`.
- A hub restart is tested through the check button (`btnCheck`), not a real restart. The quiet cases use the same button: a sensor that resumes with an unchanged value fires no event, so the app sees it at the next 5-minute check, and `sensor-back-clears-fault` exercises that path through the button.
- The 5-minute retry of an unresponsive heater is tested through the button, not the schedule.
- A real meter's report lag is represented only by the 2 s scaled grace.
- Keep-alive is checked by a command count with a tolerance of 1, since timer jitter moves one re-send in or out of the window.
- The Command Retry advice is checked by hand, since virtual devices cannot use Command Retry: with a Zigbee or Z-Wave heater that has it off, the page shows the advice and the log warns on Done; with it on, neither does.

<!--
Copyright (c) 2026 PJ
SPDX-License-Identifier: MIT
-->

# Switched Heater Thermostat test plan

Modes are those of TESTING.md. The on-hub test follows its closed-loop contract: `[PASS]`/`[FAIL]` lines, exit 0 when all pass, 1 on a failure, 2 on a setup error.

| Phase | Title | Mode | Test | Status |
|---|---|---|---|---|
| A | Core unit tests | 4, extraction variant | `test_core.groovy` | Done |
| B | Behavior, control | 1, with test drivers | `test-sht.sh` (from `spec-switched-heater-thermostat.yaml`) | Done |
| C | Behavior, faults | 1, with test drivers | `test-sht-faults.sh` (from `spec-switched-heater-thermostat-faults.yaml`) | Done |

## Phase A: core unit tests

`test_core.groovy` (126 checks) parses the block between the "Core (pure)" and "End core" markers of `SwitchedHeaterThermostat.groovy` and runs it under the pinned Groovy 2.4.21 jar, so the tests bind to shipped code. Groups:

- Readings: combining sensors by average and minimum, rounding to 0.1 degree (negative readings too), quiet sensors left out.
- Activity and quiet limit: the `quietAfter` limit until 5 gaps are seen, then twice the second-longest of the last 48 gaps with the 30-minute floor, and one outage not raising the limit.
- Wanted state: the order of precedence (frost, mode, rest, sensor fault, thermostat), for each sensor-fault choice.
- Minimum on and off times: holds that delay only the thermostat's own changes.
- Frost: start below the setting, stop 1 degree above, no reading.
- Warming: the rise against the temperature when the heaters turned on, a run that began without a reading.
- Verification: second command, unresponsive heater, an unconfirmed command blocking or not blocking a repeat.
- Cycle and limit: the on and off phases, a clock that steps back, the limit and rest times, the limit with "keep on".
- Power: both mismatch directions, a reading exactly at the threshold, the 2-minute grace, heaters without a meter.
- Alerts: every message text, fault raise and clear, and the lists of heaters and sensors.

Run, from the repo root:

```
java -cp groovy-all-2.4.21.jar groovy.ui.GroovyMain apps/SwitchedHeaterThermostat/tests/test_core.groovy
```

`java` must be a real JDK 11 or later. On a Mac, `/usr/bin/java` may be Apple's stub that asks to install Java; use the Homebrew one instead (`/opt/homebrew/opt/openjdk/bin/java`). The jar is on Maven Central as `org.codehaus.groovy:groovy-all:2.4.21`.

## Phases B and C: behavior

Both scripts run the app instance `test-sht` on the same rig: one adopted Virtual Thermostat (heat mode, setpoint 20, default hysteresis), two virtual temperature sensors, two Virtual Heater Plug (Test) devices (a switch with a simulated 1000 W meter that can be stuck, unplugged or set to any draw, and counts its commands), the four fault switches and a Notification Capture (Test) device, driven through a dedicated Maker API. The debug-only `testSecondsPerMinute` is 1, so every minute-based setting runs in seconds: the power grace is 2 s and `quietAfter` 10 is 10 s. Each script resets the rig in its first case, so the two run in either order, but not at the same time. Cases run in order, and some start from the previous case's end state (named below).

Each case also fails on a warn or error log line from the rig that it does not expect. A case that expects one of the app's warn lines (a manual toggle undone, a fault raised, frost protection on) names it in `allow_log_patterns`; no case turns the guard off. A "no repeat notification" check is a negated `lastMessage` event on the capture device over the case window.

Both specs share the instance label, so the generator would name both scripts `test-sht.sh`; the fault script is rendered to `test-sht-faults.sh` by hand from the same template.

### Phase B: control (`test-sht.sh`, 25 cases, 99 checks)

| Case | What it checks |
|---|---|
| `heats-when-cold` | Resets the rig. Done writes the thermostat's supported modes (`["off","heat"]`); cold sensors: thermostat `heating`, both heaters on, no load fault |
| `stops-when-warm` | Warm sensors: thermostat `idle`, both heaters off |
| `average-reaches-thermostat` | Sensors at 19 and 21 give 20.0 on the thermostat |
| `minimum-reaches-thermostat` | With "minimum", sensors at 19.4 and 21.2 give 19.4 |
| `manual-toggle-reverted` | A heater switched off by hand is set back on |
| `mode-off-turns-heaters-off` | Thermostat mode `off` turns both heaters off |
| `manual-on-reverted` | A heater switched on by hand while the app wants it off is set back off (starts from the previous case) |
| `fault-switches-reasserted` | Fault switches turned on by hand with no fault raised are turned off at the next check (starts from the previous case) |
| `limit-rests` | The heating time limit turns the heaters off and raises the limit fault |
| `limit-resumes` | After the rest, the heaters follow the thermostat again (starts in the rest of `limit-rests`) |
| `limit-fault-clears` | The room warms after the rest: heaters off, limit fault switch off, message (starts from `limit-resumes`) |
| `min-on-holds` | The minimum on time holds the heaters on after the room warms |
| `min-on-releases` | The heaters turn off after the minimum (starts from `min-on-holds`) |
| `mode-off-overrides-min-on` | Mode `off` during a minimum-on hold turns the heaters off at once |
| `min-off-holds` | The minimum off time holds the heaters off after the room cools |
| `min-off-releases` | The heaters turn on after the minimum (starts from `min-off-holds`) |
| `keep-alive-resends` | A heater already on receives the state again at the keep-alive interval |
| `no-warming-fault` | Heaters on without a rise raise the warming fault and its message |
| `warming-clears` | A rise clears the warming fault (starts from `no-warming-fault`) |
| `warming-check-off-clears` | The fault rises again, then blanking `noRiseMinutes` clears it with its message (starts from `warming-clears`) |
| `warming-clears-when-heaters-off` | The warming fault rises, then mode `off` clears it with its message |
| `frost-overrides-mode-off` | Frost protection turns the heaters on with the thermostat off, and notifies |
| `frost-stops` | With the setting at 15, frost keeps running at 15.0 and stops at 16.0 (starts from the previous case) |
| `frost-not-counted` | Frost runs past the heating time limit without raising the limit fault |
| `frost-overrides-rest` | The limit rests the heaters, then frost turns them on during the rest (starts from `frost-not-counted`) |

### Phase C: faults (`test-sht-faults.sh`, 22 cases, 104 checks)

| Case | What it checks |
|---|---|
| `stuck-heater-load-fault` | Resets the rig. A heater that ignores commands raises the load fault, its switch and the message |
| `load-fault-no-repeat` | The load fault switch turned off by hand is turned on at the next check; the retry fails again with no new notification (starts from the previous case) |
| `load-fault-clears` | The heater responds again: fault switch off, "under control again" message (starts from the previous case) |
| `load-fault-clear-no-repeat` | Another check after the clear sends no new notification (starts from the previous case) |
| `stuck-on-heater-load-fault` | A heater that won't turn off raises the load fault; the other heater turns off |
| `stuck-on-clears-with-failing-notifier` | The fault clears and its switch turns off while the notifier throws (starts from the previous case) |
| `unplugged-heater-load-fault` | Switch on with no draw raises the load fault with the draw in the message |
| `power-while-off-load-fault` | Switch off with a draw raises the load fault |
| `power-fault-clears` | The draw stops: load fault switch off, "under control again" message (starts from the previous case) |
| `power-check-off` | With `usePower` off, a heater on with no draw raises nothing |
| `frost-stops-when-sensors-quiet` | Frost running with the thermostat off, then every sensor quiet: sensor fault, frost off with "no temperature", heaters off |
| `all-sensors-quiet-fault` | Every sensor quiet: sensor fault, heaters off, thermostat `idle`, message with the policy |
| `sensor-back-clears-fault` | A sensor reports again: fault off, heaters follow the thermostat (starts from the previous case) |
| `sensor-fault-keep-on` | With "on", the heaters stay on during the sensor fault |
| `warming-window-starts-at-return` | The warming check is set during that run, which began with no reading; a sensor returns at 18.5 and no warming fault rises yet (starts from the previous case) |
| `warming-fault-after-return` | One window after the return the warming fault rises, with the rise measured from the returning reading (starts from the previous case) |
| `limit-under-keep-on` | With "on", the heating time limit rests the heaters and the thermostat shows `idle` |
| `sensor-fault-cycle` | With "cycle", the heaters are on for the duty share, starting with the on phase |
| `cycle-off-phase` | The off phase of the cycle turns the heaters off (starts from the previous case) |
| `one-sensor-quiet-partial` | One quiet sensor among two: no fault, a "left out" notification |
| `one-sensor-reporting-again` | The quiet sensor reports: no fault, a "reporting again" notification (starts from the previous case) |
| `partial-quiet-not-notified` | With partial notifications off, a quiet sensor sends nothing |

Run: `RUN_SLOW_TESTS=1 bash apps/SwitchedHeaterThermostat/tests/test-sht.sh [@hub]` (about 330 s, under 6 minutes) and `RUN_SLOW_TESTS=1 bash apps/SwitchedHeaterThermostat/tests/test-sht-faults.sh [@hub]` (about 445 s, under 8 minutes). The specs are the source; regenerate the scripts from them.

## Known gaps

- The learned quiet limit is tested only in the core. The rig forgets the sensors' learned gaps before each quiet case (the *Forget the sensors' learned gaps* button), since they would otherwise learn from the test's own timing.
- Creating the thermostat (`createThermostat`), removing an unused created one (`btnRemoveChild`), refused configurations (another thermostat driver, a heater picked as a fault switch, no thermostat) and uninstall are checked by hand: each needs its own instance. The heaters turning off when the thermostat is deleted was checked by hand the same way.
- Removing a heater from the list clears its stale load fault (`initialize` drops state for heaters no longer listed). This is not tested on the hub: a settings step takes device ids, and the spec only knows device labels.
- A hub restart is tested through the check button (`btnCheck`), not a real restart. The quiet cases use the same button: a sensor that resumes with an unchanged value fires no event, so the app sees it at the next 5-minute check, and `sensor-back-clears-fault` exercises that path through the button.
- The 5-minute retry of an unresponsive heater is tested through the button, not the schedule.
- A shared fault switch (one switch picked for two faults) is tested only in the core.
- A heater without a meter is tested only in the core: both rig heaters report power.
- A real meter's report lag is represented only by the 2 s scaled grace.
- Keep-alive is checked by a command count with a tolerance of 1, since timer jitter moves one re-send in or out of the window.
- The Command Retry advice is checked by hand, since virtual devices cannot use Command Retry: with a Zigbee or Z-Wave heater that has it off, the page shows the advice and the log warns on Done; with it on, neither does.
- The log guard matches the rig's labels by prefix, so it also sees the capture device's own error when it is set to fail; `stuck-on-clears-with-failing-notifier` allows that one line.

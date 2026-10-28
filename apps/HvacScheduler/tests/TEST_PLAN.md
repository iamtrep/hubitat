<!--
Copyright (c) 2026 PJ
SPDX-License-Identifier: MIT
-->

# HVAC Scheduler test plan

Modes are those of TESTING.md. Every on-hub test follows its closed-loop contract: `[PASS]`/`[FAIL]` lines, exit 0 when all pass, 1 on a failure, 2 on a setup error.

| Phase | Title | Mode | Test | Status |
|---|---|---|---|---|
| A | Core unit tests | 4, extraction variant | `test_core.groovy` | Done |
| B | Behavior | 1 | `test-tsp.sh` (from `spec-tsp.yaml`) | Done |
| C | Write check | 1, with a test driver | `test-tsp-retry.sh` (from `spec-tsp-retry.yaml`) | Done |
| D | HTTP API | 2 | `test-tsp-api.sh` | Done |
| E | Parity with the built-in scheduler | 1, differential | `test-tsp-parity.sh` | Done |
| F | Configuration `PUT` and importer | 2 and 4 | `test-tsp-api.sh` (PUT section), `test-tsp-import.sh`, `test_core.groovy` (PUT and import cases) | Done |
| G | HVAC Interlock and hub variables | 1 | `test-tsp-interlock.sh` | Done |
| H | Occupancy | 1 and 4 | `test-tsp-occupancy.sh`, `test_core.groovy` (occupancy cases) | Done |

B, C, E and H take minutes each and belong to `RUN_SLOW_TESTS=1` runs. E and H check the flag and, without it, prints `[INFO] ... skipped` and exits 0; the generated B and C run whether the flag is set or not. B and C use the program's test inputs (configuration JSON, test clock), which render only while its debug logging is on, so their first case turns debug logging on; it turns itself off after 30 minutes.

## Phase A: core unit tests

`test_core.groovy` parses the block between the "Core (pure)" and "End core" markers of `HvacSchedulerProgram.groovy` and runs it under the pinned Groovy 2.4.21 jar, so the tests bind to shipped code. Covered: date helpers (week days, month ends, both DST changes), transitions (fixed times, sunrise and sunset offsets, DateTime variables set and missing, the first period of a day, day groups, a period inside the skipped and the repeated DST hour), the resolver (every layer, eco on schedules and overrides, eco offset changes, eco from the eco lists alone and with eco by command, mode schedules, variable setpoints, missing profiles), hold expiry for each end, write planning (heat, cool, auto with separation in both directions, off thermostats, blank fan and mode, `noModeIds`), restrictions, the next wake time, configuration validation, command parsing for every command and end format, `applyOutcome` (`ok`, `partial`, `failed`), the Away override a new program gets (`seedOverrides`), when a write check is overdue (`verifyOverdue`), merging a second write batch into a pending check (`mergeWrites`), hub variable renames (`renameVarRefs`), hold setpoint ranges (`setpointRangeError`), no fan writes to off thermostats, and strict ISO hold ends (trailing text, past times).

## Phase B: behavior

`test-tsp.sh` runs the program `test-tsp` on two virtual thermostats and a pause switch, driven through a dedicated Maker API and the program's test clock. Cases: configuration load and apply, a transition on the test clock, profile holds ending `next` and `indefinite`, resume, advance, schedule switch, bad commands, eco offset 0, mode overrides in and out with and without a `next` hold, eco on and off on an override, the eco stacking regression, manual changes (set, kept through other events, kept through a target change that writes nothing to that thermostat, cleared by the next write), a thermostat mode changed by someone else (off writes nothing, heat gets its setpoint), pause through the restriction switch and the status switch with both *while paused* and both *when the pause ends* settings, the hub-start handler, and UI edits through the page buttons (profiles, rename and delete refusal, periods, splitting a day group, overrides, mode schedules, the active schedule, holds from the main page). Every case also fails on any unexpected warn or error log line.

## Phase C: write check

`test-tsp-retry.sh` runs the program `test-tsp-retry` on a Stubborn Thermostat (a test driver that ignores the next N setpoint commands) and a virtual thermostat. Cases: all writes confirmed (`lastApply` `ok`), an ignored write reported as `partial` and not resent, and a second batch sent before the first one's check, which still reports the first batch's ignored write.

## Phase D: HTTP API

`test-tsp-api.sh` exercises every parent route: requests without a token (401), list and get (with `revision` and configuration), unknown and non-numeric program ids (404), a hold and a resume whose replies carry the new status and match the program device, unknown commands, missing arguments, an out-of-range hold setpoint and bad hold ends (400), and the debug-only `POST /programs`: refused (404) while the parent's debug logging is off, without a label (400), and creating the program `test-tsp-new`, whose configuration must map the Away mode to the Away profile. With `test-tsp-new` disabled, the list shows it with `status` `disabled`, and a get or a command answers 409. The test turns the parent's debug logging off for the 404 check, then on, and leaves `test-tsp-new` in place. It needs the `test-tsp` program from phase B. The cloud case (a relayed request refused with 403 while the app's cloud access is off) is skipped with `[INFO]` when the hub's cloud address cannot be found, which is the case while the hub's own cloud access is off.

## Phase E: parity

`test-tsp-parity.sh` runs a built-in Thermostat Scheduler and an HVAC Scheduler program built from its configuration on twin virtual thermostats, and compares them: apply now, Away mode in and out, a restriction on and off, and the intended difference after a manual change in Away. It exits 2 with the time to re-run when a period boundary is less than 150 seconds away.

## Phase F: configuration `PUT` and importer

Core cases in `test_core.groovy`: the revision (stable across a JSON round trip, changed by any value), the document shape, option group validation and the settings each group writes; the converter on synthetic built-in data: day groups, fixed, sunrise, sunset and ISO starts, blank and text offsets, leftover period and group keys ignored, shared profiles named after every period or mode that uses them, hub variables, lower-cased fan and mode, the Away profile and override, the EcoMode offset and its range, options, the restriction switch and its polarity, a lone period without a start, a period without a start among others, empty-list settings, Hub Modes schedules (merged rows, unused rows, a deleted mode), empty schedules, variable start times, the required separation, the day, mode and time-window restrictions (a sunrise offset, an ISO time, an unset window, a window with one end, a deleted mode), Hub Modes schedulers without an Away override (the built-in was checked on the hub to ignore its stored Away values when scheduling by mode), and the warnings for EcoMode for Away, a hold and a hub without an Away mode. The keys were read back from a built-in scheduler configured through its own page.

`test-tsp-api.sh` (PUT section) on the `test-tsp` program: no token (401), unknown program (404), missing revision or configuration (400), a round trip that keeps the revision, an invalid document (400 with errors), an edit that adds a profile and changes the eco offset, a stale revision (409 with the current one), a PUT that drops the profile a hold uses, which ends the hold, and ten rounds of two PUTs sent at once with the same revision, each giving one 200 and one 409. It removes a `Temp` profile left by an interrupted run.

`test-tsp-import.sh` imports the parity test's reference scheduler: missing or unknown `from`, an app that is not a built-in scheduler, no token; then the program's pause state, thermostat, pause switch and polarity, every live period with its heating setpoint, the Away profile and override, and the eco offset. It sets *while paused* to *turn thermostats off*, saves the program with Done, and checks that the thermostat did not change and that the program never applied its pause (no `paused` log line). A second import without a label takes the built-in's name. It deletes the programs it creates.

## Phase G: HVAC Interlock and hub variables

`test-tsp-interlock.sh` runs a program it creates (`test-tsp-ilk`) on the virtual thermostat `test-tsp-ilk-th`, with a profile whose heating setpoint is the hub variable `zz_tsp_ilk_heat` (created and removed by the test through the hub variables admin API, firmware 2.5.2.133 and later), and pausing on the status device of the HVAC Interlock permit group `test-ilk-tsp`. Device commands go through `/device/runmethod`, the same device command a Rule Machine custom action sends. Cases: the applied setpoint comes from the variable and follows a change of it; `setEco on`/`off` on the program device lowers and restores the setpoint; an open window turns the group's status off, the thermostat off and the program `restricted`, a variable change while restricted writes nothing, and closing the window restores the mode and applies the current setpoint over one changed by hand; the program paused by the group and by its own switch at once, lifted in both orders, stays off until the second lift; the program paused by the group's status switch in *pause while any of these is off* and a second switch in *pause while any of these is on*, each holding the pause while the other clears; that second switch moved to *eco while any of these is on*: eco without a pause, `setEco off` leaving the switch's eco, the switch clearing leaving eco set by command, and the setpoint restored once both clear.

Right after a lift that follows a setpoint changed by hand, `status` read `manual` once in eight runs and `schedule` a moment later; the case reads it after 10 seconds.

## Phase H: occupancy

Core cases in `test_core.groovy`: the occupied profile in a period that uses it (higher heat, lower cool, its name as the profile, fan kept), vacant and no sensor, a period that doesn't use it, a warmer period keeping its own values, blank values on either side, no effect on a hold, a mode override or a pause, eco on top, a hub-mode row with and without it, an occupied profile from a hub variable, no occupied profile set, a `next` hold kept across a change, validation (unknown occupied profile, a non-boolean `useOccupied`), and the `PUT` document and shape; `occupancyStep` in both directions on either side of each delay, a reading equal to the committed state, delay 0, the first commit with either reading, a reading with no date, and an unknown reading.

`test-tsp-occupancy.sh` runs a program it creates (`test-tsp-occ`) on the virtual thermostat `test-tsp-occ-th`, with the virtual motion sensor `test-tsp-occ-motion` as its occupancy sensor and both delays at 1 minute. Cases: vacant at start; motion shorter than the delay writes nothing; past it, the occupied profile's setpoint, `occupancy` `occupied`, `profile` the occupied profile, `status` `schedule`; a 20-second break keeps the zone occupied; eco on top; a `next` hold kept while the zone goes vacant, then resume; a period that doesn't use the occupied profile, then a `PUT` that ticks it while occupied; a pause while occupied and the resume; Done while occupied writes no setpoint; removing the sensor reports `none` and writes the period's setpoint. The delays are real time, since the test clock does not move `runIn`.

## Known gaps

- The API test does not cover local access turned off.
- No on-hub case loses a write-check job to prove the re-arm at the next evaluation: losing a scheduled job on purpose needs a hub restart or editing the program's state. The core tests cover the overdue decision (`verifyOverdue`).
- `lastApply` `failed` is covered by the core tests only.
- Minute and ISO-time holds and `holdSetpoints` are covered by the core tests only (parsing, expiry, resolution); no on-hub case runs one.
- No case runs two programs on one thermostat (the spec's regression case for the built-in's by-thermostat targeting). Each program has its own program device, so the commands cannot reach another program.
- The warning logged for an unconfirmed write is checked by hand.
- Parity covers only the built-in's leave-thermostats-on restriction branch; its turn-off branch is untested.
- Calls from the parent into one program are serialized with each other (the concurrent PUT case). Whether they are serialized with the program's own scheduled and event handlers, and program device calls, is unmeasured. To measure before release.
- An import whose program throws after it was created is checked by hand with an injected fault: the parent gets null, deletes the program and its program device, and answers 400.
- The reference scheduler leaves thermostats alone while restricted, so no on-hub case imports a scheduler set to turn them off; the import test covers it by switching the imported program to that setting before Done.
- The parent's import page is checked by hand.
- Hub variable rename is covered by the core tests (`renameVarRefs`) only; phase G covers a change of a setpoint variable on the hub.
- Occupancy across a hub restart is not run on the hub; the start rule is the core's `occupancyStep` with the sensor's state date, which the Done case exercises.
- Renaming the occupied profile, and the refusal to delete it while in use, have no test: both are page-button paths (`renameProfileRefs`, `usage`).

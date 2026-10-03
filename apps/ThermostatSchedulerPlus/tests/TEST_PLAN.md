<!--
Copyright (c) 2026 PJ
SPDX-License-Identifier: MIT
-->

# Thermostat Scheduler+ test plan

Modes are those of TESTING.md. Every on-hub test follows its closed-loop contract: `[PASS]`/`[FAIL]` lines, exit 0 when all pass, 1 on a failure, 2 on a setup error.

| Phase | Title | Mode | Test | Status |
|---|---|---|---|---|
| A | Core unit tests | 4, extraction variant | `test_core.groovy` | Done |
| B | Behavior | 1 | `test-tsp.sh` (from `spec-tsp.yaml`) | Done |
| C | Write check | 1, with a test driver | `test-tsp-retry.sh` (from `spec-tsp-retry.yaml`) | Done |
| D | HTTP API | 2 | `test-tsp-api.sh` | Done |
| E | Parity with the built-in scheduler | 1, differential | `test-tsp-parity.sh` | Done |
| F | Configuration `PUT` and importer | 2 and 4 | `test-tsp-api.sh` (PUT section), `test-tsp-import.sh`, `test_core.groovy` (PUT and import cases) | Done |

B, C and E take minutes each and belong to `RUN_SLOW_TESTS=1` runs. E checks the flag and, without it, prints `[INFO] ... skipped` and exits 0; the generated B and C run whether the flag is set or not. B and C use the program's test inputs (configuration JSON, test clock), which render only while its debug logging is on, so their first case turns debug logging on; it turns itself off after 30 minutes.

## Phase A: core unit tests

`test_core.groovy` parses the block between the "Core (pure)" and "End core" markers of `ThermostatSchedulerPlusProgram.groovy` and runs it under the pinned Groovy 2.4.21 jar, so the tests bind to shipped code. Covered: date helpers (week days, month ends, both DST changes), transitions (fixed times, sunrise and sunset offsets, DateTime variables set and missing, the first period of a day, day groups, a period inside the skipped and the repeated DST hour), the resolver (every layer, eco on schedules and overrides, eco offset changes, mode schedules, variable setpoints, missing profiles), hold expiry for each end, write planning (heat, cool, auto with separation in both directions, off thermostats, blank fan and mode, `noModeIds`), restrictions, the next wake time, configuration validation, command parsing for every command and end format, `applyOutcome` (`ok`, `partial`, `failed`), the Away override a new program gets (`seedOverrides`), when a write check is overdue (`verifyOverdue`), merging a second write batch into a pending check (`mergeWrites`), hub variable renames (`renameVarRefs`), hold setpoint ranges (`setpointRangeError`), no fan writes to off thermostats, and strict ISO hold ends (trailing text, past times).

## Phase B: behavior

`test-tsp.sh` runs the program `test-tsp` on two virtual thermostats and a pause switch, driven through a dedicated Maker API and the program's test clock. Cases: configuration load and apply, a transition on the test clock, profile holds ending `next` and `indefinite`, resume, advance, schedule switch, bad commands, eco offset 0, mode overrides in and out with and without a `next` hold, eco on and off on an override, the eco stacking regression, manual changes (set, kept through other events, kept through a target change that writes nothing to that thermostat, cleared by the next write), a thermostat mode changed by someone else (off writes nothing, heat gets its setpoint), pause through the restriction switch and the status switch with both *while paused* and both *when the pause ends* settings, the hub-start handler, and UI edits through the page buttons (profiles, rename and delete refusal, periods, splitting a day group, overrides, mode schedules, the active schedule, holds from the main page). Every case also fails on any unexpected warn or error log line.

## Phase C: write check

`test-tsp-retry.sh` runs the program `test-tsp-retry` on a Stubborn Thermostat (a test driver that ignores the next N setpoint commands) and a virtual thermostat. Cases: all writes confirmed (`lastApply` `ok`), an ignored write reported as `partial` and not resent, and a second batch sent before the first one's check, which still reports the first batch's ignored write.

## Phase D: HTTP API

`test-tsp-api.sh` exercises every parent route: requests without a token (401), list and get (with `revision` and configuration), unknown and non-numeric program ids (404), a hold and a resume whose replies carry the new status and match the status device, unknown commands, missing arguments, an out-of-range hold setpoint and bad hold ends (400), and the debug-only `POST /programs`: refused (404) while the parent's debug logging is off, without a label (400), and creating the program `test-tsp-new`, whose configuration must map the Away mode to the Away profile. The test turns the parent's debug logging off for the 404 check, then on, and leaves `test-tsp-new` in place. It needs the `test-tsp` program from phase B. The cloud case (a relayed request refused with 403 while the app's cloud access is off) is skipped with `[INFO]` when the hub's cloud address cannot be found, which is the case while the hub's own cloud access is off.

## Phase E: parity

`test-tsp-parity.sh` runs a built-in Thermostat Scheduler and a Thermostat Scheduler+ program built from its configuration on twin virtual thermostats, and compares them: apply now, Away mode in and out, a restriction on and off, and the intended difference after a manual change in Away. It exits 2 with the time to re-run when a period boundary is less than 150 seconds away.

## Phase F: configuration `PUT` and importer

Core cases in `test_core.groovy`: the revision (stable across a JSON round trip, changed by any value), the document shape, option group validation and the settings each group writes; the converter on synthetic built-in data: day groups, fixed, sunrise, sunset and ISO starts, blank and text offsets, leftover period and group keys ignored, shared profiles named after every period or mode that uses them, hub variables, lower-cased fan and mode, the Away profile and override, the EcoMode offset and its range, options, the restriction switch and its polarity, a lone period without a start, a period without a start among others, empty-list settings, Hub Modes schedules (merged rows, unused rows, a deleted mode), empty schedules, and the warnings for restrictions, EcoMode for Away, a hold and a hub without an Away mode.

`test-tsp-api.sh` (PUT section) on the `test-tsp` program: no token (401), unknown program (404), missing revision or configuration (400), a round trip that keeps the revision, an invalid document (400 with errors), an edit that adds a profile and changes the eco offset, a stale revision (409 with the current one), a PUT that drops the profile a hold uses, which ends the hold, and ten rounds of two PUTs sent at once with the same revision, each giving one 200 and one 409. It removes a `Temp` profile left by an interrupted run.

`test-tsp-import.sh` imports the parity test's reference scheduler: missing or unknown `from`, an app that is not a built-in scheduler, no token; then the program's pause state, thermostat, pause switch and polarity, every live period with its heating setpoint, the Away profile and override, and the eco offset. It sets *while paused* to *turn thermostats off*, saves the program with Done, and checks that the thermostat did not change and that the program never applied its pause (no `paused` log line). A second import without a label takes the built-in's name. It deletes the programs it creates.

## Known gaps

- The API test does not cover local access turned off.
- No on-hub case loses a write-check job to prove the re-arm at the next evaluation: losing a scheduled job on purpose needs a hub restart or editing the program's state. The core tests cover the overdue decision (`verifyOverdue`).
- `lastApply` `failed` is covered by the core tests only.
- Minute and ISO-time holds and `holdSetpoints` are covered by the core tests only (parsing, expiry, resolution); no on-hub case runs one.
- No case runs two programs on one thermostat (the spec's regression case for the built-in's by-thermostat targeting). Each program has its own status device, so the commands cannot reach another program.
- The warning logged for an unconfirmed write is checked by hand.
- Parity covers only the built-in's leave-thermostats-on restriction branch; its turn-off branch is untested.
- Calls from the parent into one program are serialized with each other (the concurrent PUT case). Whether they are serialized with the program's own scheduled and event handlers, and status device calls, is unmeasured. To measure before release.
- An import whose program throws after it was created is checked by hand with an injected fault: the parent gets null, deletes the program and its status device, and answers 400.
- The built-in's cooling, fan and thermostat-mode keys for periods, Away and Hub Modes rows are inferred from its heating keys; no built-in instance with those values has been read yet.
- The reference scheduler leaves thermostats alone while restricted, so no on-hub case imports a scheduler set to turn them off; the import test covers it by switching the imported program to that setting before Done.
- The parent's import page is checked by hand.
- Hub variable registration, rename and change events are covered by the core tests (`renameVarRefs`) only; no on-hub case renames or changes a hub variable.

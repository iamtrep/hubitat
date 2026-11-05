<!--
Copyright (c) 2026 PJ
SPDX-License-Identifier: MIT
-->

# Shared code blocks

Hubitat apps and drivers go to the hub as one file each, so a helper several of them need is copied into each file. This folder holds the one source of each shared helper. `scripts/shared_sync.groovy` keeps the copies identical to it, and `manifest.json` lists which files take which block. The copies carry no markers: the tool finds each function by name in the file's syntax tree.

**Before writing a helper, check this list.** To use a block in a file, add the file to `manifest.json` with the block name and run `groovy scripts/shared_sync.groovy sync <file>`; it inserts the functions the file lacks. Edit a shared function here, never in a copy, then run `sync` and commit the copies with it.

| Block | What it does | Used by |
|---|---|---|
| [logging-driver](logging-driver.groovy) | The driver logging helpers of `docs/logging-emoji-scheme.md`: `logp` and the twelve level and category helpers, gated on `txtEnable`, `debugEnable`, `traceEnable`. | 43 drivers. A file gated on another setting name keeps its own copy. |
| [logging-app](logging-app.groovy) | The same for apps: `logEvt` instead of `logRx`, no `logOta`, `app.getLabel()` prefix. | 39 apps. Not Hub Inspector, Hub Diagnostics or Multi-Hub Inventory (own prefix), nor apps with another gate name. |
| [logs-off-driver](logs-off-driver.groovy) | `logsOff`, run by `runIn(1800, "logsOff")`: clears `debugEnable` and `traceEnable`. A driver without a `traceEnable` input is fine: nothing is stored for it. | 33 drivers |
| [logs-off-app](logs-off-app.groovy) | `logsOff` for apps: checks the version, then clears both settings. The app needs a `traceEnable` input (an app stores an undeclared name) and `checkVersion`. | 33 apps |
| [version-check-app](version-check-app.groovy) | `checkVersion(boolean reinit = true)`: logs and records a new `CODE_VERSION` and re-runs `updated()` (ARCHITECTURE.md). Apps with migration steps keep their own. | 32 apps |
| [quiet-sensors](quiet-sensors.groovy) | Counts a sensor as quiet from its last activity (`getLastActivity()`, so check-ins count). The limit is learned per sensor: twice its second-longest recent gap, never under the app's setting or floor. `recordActivity`, `quietLimitMs`, `isQuiet`. | Switched Heater Thermostat, Sensor Aggregator (both children), HVAC Interlock group |
| [oauth-self-enable](oauth-self-enable.groovy) | An app with mappings turns OAuth on for its own app type and creates its token, so nobody toggles OAuth in the code editor. `checkOAuth`, `autoEnableOAuth`. Needs `oauth: true`, `logDebug`, `logError`. | Code Manager, Event History, Hub Diagnostics, Hub Inspector, HVAC Scheduler, Multi-Hub Inventory, Tool Access Prototype |
| [github-version](github-version.groovy) | The latest `CODE_VERSION` of the app on GitHub, asked at most hourly, for an "update available" note. `checkGithubVersion`, `githubVersionCallback`. Needs `IMPORT_URL_APP`, `refreshUpdateLabel()`, `logNet`. | Code Manager, Hub Diagnostics, Hub Inspector, Multi-Hub Inventory, Well Monitor |

## Commands

```
groovy scripts/shared_sync.groovy sync [files…]     # rewrite the copies from this folder
groovy scripts/shared_sync.groovy --check --all     # report copies that differ; changes nothing
groovy scripts/shared_sync.groovy --names <files…>  # warn about a function named like a shared one in a file not listed for it
groovy scripts/shared_sync.groovy --report          # names repeated in 3+ files that no block covers yet
```

## Writing a block

- One block per file, named `<block>.groovy`, with a `// block-version: N` line in its header. Raise N when a body changes.
- `// requires-import: a.b.C` lines name imports the block needs; `sync` refuses a file that lacks them.
- `// requires: name, name` lines name functions or `@Field` constants the host file must define (its logging helpers, a URL constant, a hook the block calls); `sync` and `--check` refuse a file that lacks one.
- The comment lines directly above a function belong to it and are copied with it. A comment line starting `// ──` is a section header and stays with the file.
- Keep blocks free of settings, state and logging where possible, and pass values in as arguments, so each app keeps its own tuning. A block that has to call the platform or log names what it needs in `// requires:`. Test each block in `tests/`, against this source.
- A function removed from a block is reported in each file that still has it, not deleted.

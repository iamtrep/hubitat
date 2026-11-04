<!--
Copyright (c) 2026 PJ
SPDX-License-Identifier: MIT
-->

# Shared code blocks

Hubitat apps and drivers go to the hub as one file each, so a helper several of them need is copied into each file. This folder holds the one source of each shared helper. `scripts/shared_sync.groovy` keeps the copies identical to it, and `manifest.json` lists which files take which block. The copies carry no markers: the tool finds each function by name in the file's syntax tree.

**Before writing a helper, check this list.** To use a block in a file, add the file to `manifest.json` with the block name and run `groovy scripts/shared_sync.groovy sync <file>`; it inserts the functions the file lacks. Edit a shared function here, never in a copy, then run `sync` and commit the copies with it.

| Block | What it does | Used by |
|---|---|---|
| [quiet-sensors](quiet-sensors.groovy) | Counts a sensor as quiet from its last activity (`getLastActivity()`, so check-ins count). The limit is learned per sensor: twice its second-longest recent gap, never under the app's setting or floor. `recordActivity`, `quietLimitMs`, `isQuiet`. | Switched Heater Thermostat, Sensor Aggregator (both children) |

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
- The comment lines directly above a function belong to it and are copied with it. A comment line starting `// ──` is a section header and stays with the file.
- Keep blocks free of settings, state and logging where possible, and pass values in as arguments, so each app keeps its own tuning. Test each block in `tests/`, against this source.
- A function removed from a block is reported in each file that still has it, not deleted.

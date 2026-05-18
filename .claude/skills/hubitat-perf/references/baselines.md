<!--
Copyright (c) 2025-2026 PJ
SPDX-License-Identifier: MIT
-->

# Baseline Management

> **Load this when:** the user invokes `--list`, `--delete`, or `--save`, or asks about managing saved baselines.

### `--list`

List `.hubitat-perf/{hubname}/*.json`. For each file print `LABEL  captured_at  (firmware_version, uptime)` extracted from the file's JSON (`jq -r '"\(.captured_at)  (\(.firmware_version), \(.uptime))"'`). If the directory doesn't exist or is empty, say so.

### `--delete LABEL`

If `.hubitat-perf/{hubname}/{label}.json` doesn't exist, say so and exit. Otherwise:
- Print the file's `captured_at` and `firmware_version`.
- Ask the user `Delete this baseline? [y/N]:` and read a line from stdin.
- Only on exact `y` or `Y` do `rm` the file. Anything else aborts.

### `--save LABEL`

1. Ensure `.hubitat-perf/{hubname}/` exists (`mkdir -p`).
2. If `.hubitat-perf/{hubname}/{label}.json` already exists, read its `captured_at` and `firmware_version`, print them, and ask `Overwrite baseline '{label}' from {captured_at}? [y/N]:`. Only proceed on `y` or `Y`.
3. Write the snapshot JSON in this exact shape:
   ```json
   {
     "hub_name":         "...",
     "hub_ip":           "...",
     "captured_at":      "...",
     "firmware_version": "...",
     "uptime":           "...",
     "app_stats":        [ ... ]
   }
   ```
   Use `jq` to assemble it — do not hand-build JSON.
4. Print: `Saved baseline '{label}' for {hubname} ({N} apps).`

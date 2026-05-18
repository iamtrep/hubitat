---
name: hubitat-perf
description: Snapshot, save baseline, and diff per-app /logs/json stats from the Hubitat hub
argument-hint: "[@hubname] [--save LABEL | --diff LABEL | --list | --delete LABEL]"
allowed-tools: Bash, Read
---


<!--
Copyright (c) 2025-2026 PJ
SPDX-License-Identifier: MIT
-->

# Hubitat Performance Skill

Pull per-app performance stats (`appStats[]` from `/logs/json`) and compare against saved baselines. Designed for ad-hoc diagnostic use during a coding session — answers "which app is hot?" and "did my last code push regress something?"

`/logs/json` can take ~15 seconds on a loaded hub. The skill always prints a warning before the fetch so the user knows to wait.

## Instructions

Follow these steps exactly.

### Step 1: Parse `$ARGUMENTS`

Tokens, in any order, but only one mode flag per invocation:

- Leading `@hubname` → target hub. If absent, use `default_hub` from `.hubitat.json`.
- `--save LABEL` → capture current stats and write to baseline LABEL.
- `--diff LABEL` → capture current stats and diff against baseline LABEL.
- `--list` → list saved baselines for the chosen hub.
- `--delete LABEL` → delete a saved baseline (with confirmation).
- (no mode flag) → print current snapshot only.

LABEL must match `^[A-Za-z0-9_.-]+$`. If a provided LABEL contains anything else, refuse with a clear error and exit.

If multiple mode flags are passed, refuse with a clear error.

### Step 2: Read Configuration and Authenticate

Read `.hubitat.json` from the project root.

1. Look up `hubs[hubname]` to get `hub_ip`. If the hub name doesn't exist in the config, error out and list the valid hub names.
2. If the hub has non-null `username` and `password`, authenticate:
   ```bash
   curl -s -c /tmp/hubitat_cookies_{hubname} -X POST "http://{hub_ip}/login" \
     -d "username={username}&password={password}"
   ```
   Then add `-b /tmp/hubitat_cookies_{hubname}` to **all** subsequent curl commands for this hub.
3. Otherwise no auth is needed (e.g. `maison-pro`).

### Step 3: Handle `--list` and `--delete` Without Fetching

If the mode is `--list` or `--delete`, do NOT contact the hub. See `references/baselines.md` for the full `--list` and `--delete` procedures.

### Step 4: Fetch Current Stats (for snapshot, --save, --diff)

Print this exact line first (verbatim):
```
Querying /logs/json on {hubname} (can take ~15s on a loaded hub)…
```

Then fetch both endpoints (no auth header on maison-pro; cookie on others):

```bash
curl -s --max-time 25 "http://{hub_ip}/logs/json" > /tmp/hubitat_perf_{hubname}_logs.json
curl -s --max-time 10 "http://{hub_ip}/hub2/hubData" > /tmp/hubitat_perf_{hubname}_hub.json
```

If either curl fails (non-zero exit) or the response isn't valid JSON (`jq empty < FILE` non-zero), error out with the HTTP body and abort.

Extract these for the in-memory snapshot:
- `hub_name` = the hubname argument
- `hub_ip` = from config
- `captured_at` = `date -u +%Y-%m-%dT%H:%M:%SZ`
- `firmware_version` = `jq -r '.version' < /tmp/hubitat_perf_{hubname}_hub.json`
- `uptime` = `jq -r '.uptime' < /tmp/hubitat_perf_{hubname}_logs.json`
- `app_stats` = `jq '.appStats' < /tmp/hubitat_perf_{hubname}_logs.json` (the array verbatim)

### Step 5: Execute the Mode

#### Current snapshot (no mode flag)

Print a markdown table sorted by `total` (cumulative ms) descending. Columns and source fields from `app_stats[i]`:

| Column      | Source                       | Notes                              |
|-------------|------------------------------|------------------------------------|
| id          | `.id`                        |                                    |
| name        | `.name`                      |                                    |
| count       | `.count`                     | execution count                    |
| total_ms    | `.total`                     |                                    |
| avg_ms      | `.average`                   | 2 decimal places                   |
| pct_total   | `.pctTotal`                  | 1 decimal place, with `%`          |
| state_kb    | `.stateSize / 1024`          | 1 decimal place                    |
| hub_actions | `.hubActionCount`            |                                    |
| cloud_calls | `.cloudCallCount`            |                                    |

After the table, print a summary line:
```
{N} apps, {sum of count} total executions, hub uptime {uptime}
```

Then a one-line hint:
```
Save as baseline: /hubitat-perf @{hubname} --save <label>
```

#### `--save LABEL`

See `references/baselines.md` for the full `--save` procedure (overwrite confirmation, JSON shape, jq assembly).

#### `--diff LABEL`

See `references/diff-format.md` for the full `--diff` procedure (delta buckets, regression gates, sanity warnings, rendering).

### Step 6: Cleanup

Remove `/tmp/hubitat_perf_{hubname}_*.json` after a successful run.

## Formatting Conventions

- All tables are markdown, no raw JSON in user-facing output.
- Numbers: integers as-is; floats to 1-2 decimal places.
- Percent deltas: signed, e.g. `+45.2%`, `-30.0%`.
- App names that contain `|` should be escaped to `\|` for the table.
- Truncate names longer than 60 chars with `…`.

## Hard Rules

- The skill MUST NOT depend on anything beyond `curl`, `jq`, `mkdir`, `rm`, `date`, `awk` (standard on macOS).
- The skill MUST NOT call `/logs/json` more than once per invocation. It is the slow endpoint.
- The skill MUST NOT write anywhere outside `.hubitat-perf/` and `/tmp/hubitat_perf_*`.
- The skill MUST NOT modify any hub state (no POSTs, no app commands).

<!--
Copyright (c) 2025-2026 PJ
SPDX-License-Identifier: MIT
-->

# Diff Output Format

> **Load this when:** the user invokes `--diff LABEL` or asks how to interpret the diff output.

#### `--diff LABEL`

1. If `.hubitat-perf/{hubname}/{label}.json` doesn't exist, error and suggest `/hubitat-perf @{hubname} --list`.
2. Load the baseline. Build two maps keyed by app `id`: `BASE` and `CUR`.
3. Print header:
   ```
   Comparing current vs baseline '{label}' captured {baseline.captured_at}
   ```
4. Sanity warnings (one line each, only if condition is true):
   - If `baseline.firmware_version != current.firmware_version`: `⚠ firmware changed: {baseline.firmware_version} → {current.firmware_version}; metric semantics may have shifted.`
   - If current uptime is **less than** baseline uptime (parse `Nd Nh Nm Ns` strings to seconds and compare): `⚠ hub rebooted since baseline; cumulative counters reset, comparison is unsound.`
5. Compute per-app deltas and bucket each app:

   - **Regressions** — id in both, AND (`(cur.total - base.total) / max(base.total, 1) >= 0.20` OR `(cur.average - base.average) / max(base.average, 0.001) >= 0.20`). Include rows with non-trivial baseline values only (skip if `base.total < 100` ms AND `base.count < 10` — too noisy to call a regression).
   - **Improvements** — same gate but ratio `<= -0.20`.
   - **New apps** — id only in CUR.
   - **Removed apps** — id only in BASE.
   - **Notable state-size changes** — id in both AND `abs(cur.stateSize - base.stateSize) >= 10240` bytes.

   An app may appear in both a delta bucket and the state-size bucket; that's expected.

6. Render each non-empty bucket as a markdown section:

   ```
   ### Regressions
   | name | Δcount | Δtotal_ms (Δ%) | Δavg_ms (Δ%) | Δstate_kb |
   |------|-------:|---------------:|-------------:|----------:|
   ...
   ```

   For new/removed apps, just list `id  name` (the deltas don't apply).

7. If every bucket is empty, print: `No material changes detected.`

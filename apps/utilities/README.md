<!--
Copyright (c) 2025-2026 PJ
SPDX-License-Identifier: MIT
-->

# Hub Administration Utilities

Hubitat Elevation apps for hub administration and maintenance. These are not automations — they are tools to help inspect, manage, and troubleshoot your hub.

## Apps

<!-- AUTO:utilities-index -->
| File | App | Description |
|---|---|---|
| `DeviceInUseEnumerator.groovy` | **Device "in use by" Enumerator** | For each device, enumerates the apps referencing them |
| `DeviceReplacement.groovy` | **Device Replacement Helper** | Replace a device across all installed apps in one shot |
| `RuleLoggingManager.groovy` | **Rule Logging Manager** | Reports Rule Machine and Button Controller rules that have Actions, Events, or Triggers logging selected. |
<!-- /AUTO -->

### Device "in use by" Enumerator (`DeviceInUseEnumerator.groovy`)

Generates an HTML report showing which installed apps reference each device.

- Queries the hub's internal APIs (`/hub2/devicesList`, `/device/fullJson/{id}`, `/installedapp/statusJson/{id}`) to build a cross-reference map.
- Reports can cover all devices or a user-selected subset.
- Optional filter to show only **child devices** (those with a parent app or parent device).
- Output table is sorted by number of referencing apps (most-referenced first) and includes clickable links to both the device and each app's configuration page.
- Also resolves and displays the parent app or parent device for child devices.

### Device Replacement Helper (`DeviceReplacement.groovy`)

Replaces one device with another across all installed apps in a single operation — useful when swapping out hardware (e.g. replacing a failed sensor with a new one).

**Three-page workflow:**

1. **Device Selection** — pick the source (old) device and target (new) device. Displays a capability comparison highlighting any mismatches.
2. **Scan & Preview** — queries every app that references the source device, checks whether each device input is on the app's main config page (and therefore automatable), and presents a table with per-app warnings:
   - Capability incompatibility
   - Target already present in the input
   - Single-device inputs
   - App state referencing the device ID (may need manual attention)
   - Multi-page apps where the input is not on the main config page (flagged for manual swap)
   - Per-app checkboxes to include/exclude from the swap
3. **Execute & Report** — performs the swap via `POST /installedapp/update/json`, verifies each result by re-reading `statusJson`, and displays a pass/fail table.

Additional features:
- **Undo** — stores the last swap and offers a one-click undo from the main page.
- Recommends creating a hub backup before executing.

### Rule Logging Manager (`RuleLoggingManager.groovy`)

Audits every Rule Machine and Button Controller rule on the hub and reports which ones have **Actions**, **Events**, or **Triggers** logging enabled — a frequent cause of log noise and hub overhead.

- Async HTTP fan-out over `/hub2/appsList` and `/installedapp/statusJson/{id}` scans the full rule set in one pass.
- Results table shows each rule with Disabled, Paused, Last Run, and the three logging flags. Columns and row groups (Disabled, Paused, "No logging ON") can be toggled to filter the view.
- **Click a logging cell to flip it in place** — the toggle posts to `/installedapp/configure/json/{id}` + `/installedapp/update/json` without leaving the page. Disabled and Paused cells are click-toggleable the same way.
- **Turn OFF All Logging (All Rules)** runs an async batch to disable Actions/Events/Triggers logging across every rule in one shot.
- `singleInstance: true`. Uses undocumented internal hub endpoints that could change in a future platform release.

## Installation

1. Install the app code on your hub.
2. Add the app from **Apps > Add User App**.
3. Configure and use as needed — these apps have no ongoing automation side-effects.

## License

MIT — see individual source files for the full license text.

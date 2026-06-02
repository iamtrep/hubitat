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

Helps replace one device with another across every installed app — auto-swapping where it can, and producing a deeplinked punch list for everything else.

**Three-page workflow:**

1. **Device Selection** — pick the source (old) device and target (new) device. Displays a capability comparison highlighting any mismatches.
2. **Scan & Preview** — for every app that references the source device, walks the app's full preference page graph (`mainPage` plus every sub-page reachable via `href`), then locates each affected input on its home page. Findings split into two tables:
   - **Auto-Swap Eligible (mainPage)** — inputs on the main page, with per-row checkboxes to include/exclude from the swap. Per-app warnings flag capability mismatch, target already present, single-device inputs, and `state.*` references to the device ID.
   - **Manual Edit Required** — inputs on sub-pages or in places the auto-swap can't safely write. Each row carries an **Edit →** deeplink straight to the right page (`/installedapp/configure/{id}/{pageName}`), the current device list with the source highlighted, and the same warning columns.
3. **Execute & Report** — performs the auto-swap via `POST /installedapp/update/json` for selected eligible entries, verifies each by re-reading `statusJson`, and displays a pass/fail table.

Additional features:
- **Undo** — stores the last auto-swap and offers a one-click undo from the main page.
- Recommends creating a hub backup before executing.

**Why some apps still need a manual edit.** The hub's form-save endpoint (`/installedapp/update/json`) addresses one page at a time, and the wire format for sub-page saves (`pageBreadcrumbs`, `_action_previous`, conditionally-rendered dynamic input names) is per-app-family — generalizing the write path across every built-in (Basic Rule, Notifier, Rule Machine, …) would mean re-validating undocumented form shapes on every firmware release. The deeplinked punch list trades clicks for stability: zero firmware-coupling, and the user stays in the loop for ambiguous cases.

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

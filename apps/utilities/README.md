# Hub Administration Utilities

Hubitat Elevation apps for hub administration and maintenance. These are not automations — they are tools to help inspect, manage, and troubleshoot your hub.

## Apps

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

### Rule Tracker (`ruletracker.groovy`)

A debugging tool that monitors Rule Machine 5.0 rules for changes to their event subscriptions and scheduled jobs.

- Select one or more RM 5.0 rules to monitor.
- Configurable check frequency: 1 min to 3 hours (or disabled).
- Scrapes the app status page (`/installedapp/status/{id}`), parses the Event Subscriptions and Scheduled Jobs HTML tables, and logs the results.
- "Run check now" button for on-demand checks.
- Supports Hub Security login when enabled.
- Includes a test mode that parses a sample HTML file from the hub's File Manager.

## Installation

1. Install the app code on your hub.
2. Add the app from **Apps > Add User App**.
3. Configure and use as needed — these apps have no ongoing automation side-effects.

## License

MIT — see individual source files for the full license text.

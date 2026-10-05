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
<!-- /AUTO -->

### Device "in use by" Enumerator (`DeviceInUseEnumerator.groovy`)

Generates an HTML report showing which installed apps reference each device.

- Groups each device's apps by app type and marks disabled apps and devices. A summary line counts devices used by no app, devices used only by disabled apps, and child devices used directly by apps.
- Shows each device's room and, for child devices, the parent device or parent app.
- Mobile dashboards (the hub's per-room and "All Devices" Easy Mobile Dashboards) are listed apart and not counted: a removed device just drops off them.
- Reports can cover all devices, child devices included, or a user-selected subset. Optional filter to show only **child devices**.
- Reads through the platform API (`getDevicesByIds`, `getAppsUsingDevice`, `getAppByAppId`), which takes a couple of seconds for a few hundred devices. If the platform restricts those calls, the report falls back to the hub's internal endpoints (`/hub2/devicesList`, `/device/fullJson/{id}`, `/installedapp/statusJson/{id}`), several times slower and without mobile dashboards, and says so at the top. A setting forces the fallback for testing.

### Device Replacement Helper (`DeviceReplacement.groovy`)

Helps replace one device with another across every installed app — auto-swapping where it can, and producing a deeplinked punch list for everything else.

**Three-page workflow:**

1. **Device Selection** — pick the source (old) device and target (new) device. Displays a capability comparison highlighting any mismatches.
2. **Scan & Preview** — when the hub's **Swap Apps Device** accepts both devices, the preview offers it first: every app keeps its device id and reaches the new hardware, sub-pages and app data included, with no app setting changed. The preview says what moves with the hardware (name, label, room, driver), that event history stays with the id, which apps already using the replacement will move to the old hardware, and which subscribed attributes the replacement doesn't report. The app opens the hub's swap page with both devices selected and shows it inside its own page; you click the hub's swap button. The hub page is shown once, because reloading it after a swap swaps the devices back. A result page then checks the swap, logs it and lists the clean-up (rename, room, the old hardware's leftover device). The main page offers undo, a second swap of the same two devices.

   For every app that references the source device, the scan also walks the app's full preference page graph (`mainPage` plus every sub-page reachable via `href`), then locates each affected input on its home page. These per-input swaps are the way for devices the hub won't swap (most child devices) or for swapping in only some apps. Findings split into four sections:
   - **Auto-Swap Eligible (mainPage)** — inputs on the main page, with per-row checkboxes to include/exclude from the swap. Per-app warnings flag capability mismatch, target already present, single-device inputs, the device's id in the app's own data (some apps, such as Mode Switches, keep per-device settings there that the new device won't get), and Hubitat or Easy Dashboards, whose tiles keep the old device id when only the dashboard's device list changes. A row with a warning starts unselected. After a swap, the results name apps whose label still mentions the old device.
   - **Manual Edit Required** — inputs on sub-pages or in places the auto-swap can't safely write. Each row carries an **Edit →** deeplink straight to the right page (`/installedapp/configure/{id}/{pageName}`), the current device list with the source highlighted, and the same warning columns.
   - **Other Apps Using the Source** — apps that use the source but hold it in no device input the scan can see (Hub Mesh sharing, apps that keep devices in their own data). Each gets an **Open →** link to replace the device by hand.
   - **Mobile Dashboards** — the hub's per-room and "All Devices" dashboards showing the source. Nothing in them is swapped; they list devices by room, so the page says whether to put the target in the source's room.
3. **Confirm, Execute & Report**: a confirmation page lists the selected inputs and recommends a hub backup. Its Swap link works once, so a refresh or a stale link doesn't swap again. Each swap saves only the changed input via `POST /installedapp/update/json`, leaving the app's other settings as they were. It then re-reads the app to check the input, the app's other settings, that the app's page still renders, and its subscriptions on the target. If the input didn't land, another setting moved or the page now fails, it puts the input back and reports the row as rolled back. A pass/fail table shows the results.

Apps using the source are found through the platform API (`getAppsUsingDevice`). If the platform restricts it, the scan falls back to `/device/fullJson/{id}`, without the mobile dashboards, and says so at the top. An option forces the fallback for testing.

Additional features:
- **Undo**: stores each swapped input's device list from before the swap, and a one-click undo on the main page puts back exactly that list. An input changed since the swap is left alone and reported.
- **Audit log**: every swap and undo appends one line per input to `device_replacement_audit.txt` in File Manager: time, devices, app, input, device ids before and after, and the outcome. The main page links to it.

**Why some apps still need a manual edit.** The hub's form-save endpoint (`/installedapp/update/json`) addresses one page at a time, and the wire format for sub-page saves (`pageBreadcrumbs`, `_action_previous`, conditionally-rendered dynamic input names) is per-app-family — generalizing the write path across every built-in (Basic Rule, Notifier, Rule Machine, …) would mean re-validating undocumented form shapes on every firmware release. The deeplinked punch list trades clicks for stability: zero firmware-coupling, and the user stays in the loop for ambiguous cases.

## Installation

1. Install the app code on your hub.
2. Add the app from **Apps > Add User App**.
3. Configure and use as needed — these apps have no ongoing automation side-effects.

## License

MIT — see individual source files for the full license text.

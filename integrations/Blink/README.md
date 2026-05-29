<!--
Copyright (c) 2025-2026 PJ
SPDX-License-Identifier: MIT
-->

# Blink Integration

Integration for [Blink](https://blinkforhome.com/) home security cameras. 

The manager app authenticates against the Blink cloud (OAuth 2.0 + PKCE + 2FA), discovers cameras and networks, and creates lean child devices that expose only the attributes that are useful on Hubitat.

There exists a full-featured Hubitat community integration for Blink devices, please see Acknowledgements below.  This integration was built to be lean and does not expose as many features, attributes, and specific device functions.

## Components

| Component | Type | Description |
|---|---|---|
| **Blink Manager** | App | OAuth authentication, device discovery, polling, dispatch |
| **Blink Network** | Driver | One device per Blink network; arm/disarm via the `Switch` capability |
| **Blink Camera** | Driver | One device per camera; motion, motion-enable, battery, temperature, clip metadata |

## Installation

1. Add `BlinkManager.groovy`, `BlinkNetwork.groovy`, and `BlinkCamera.groovy` as Apps Code / Drivers Code in the Hubitat UI.
2. Add the **Blink Manager** user app and click **Login to Blink**.
3. Enter your Blink credentials. A two-factor PIN is sent to your email or SMS; enter it on the next page.
4. The integration runs an initial discovery within a few seconds and creates one Network device per Blink network and one Camera device per camera.

## Authentication

The integration uses the same OAuth 2.0 Authorization-Code + PKCE flow as the iOS Blink app, mirroring the implementation in [blinkpy](https://github.com/fronzbot/blinkpy):

1. `GET /oauth/v2/authorize` with PKCE challenge
2. `GET /oauth/v2/signin` to extract the CSRF token
3. `POST /oauth/v2/signin` with credentials
4. On `HTTP 412`: `POST /oauth/v2/2fa/verify` with the 2FA PIN
5. `GET /oauth/v2/authorize` again to obtain the auth code
6. `POST /oauth/token` to exchange the code for access + refresh tokens

The access token is refreshed automatically 5 minutes before expiry. Refresh tokens are persisted in the app's `state` and survive both hub restarts and code pushes — only an explicit **Disconnect** or **Reset auth state** clears them.

## Devices

### Blink Network

- `Switch` — `on` = armed network, `off` = disarmed
- `online` — sync module connectivity (`online` / `offline`)
- `firmwareVersion` — sync module firmware
- `syncModuleSerial` — hardware serial
- `cameraCount` — number of cameras attached

### Blink Camera

- `MotionSensor` — `motion` flips to `active` when a clip is recorded
- `Switch` — `on` = per-camera motion detection enabled
- `Battery` — percent (for battery cameras; null for wired)
- `TemperatureMeasurement` — built-in temperature sensor (refreshed against the calibrated `/signals` endpoint on rotation)
- `lastClipUrl` / `lastClipTime` — most recent recorded clip (the actual `.mp4`)
- `lastThumbnailUrl` / `lastUpdated` — most recent still-image snapshot and camera-config update time
- `wifiSignal` (dBm) / `lfrSignal` (1–5 bars, sync-module link quality)
- `online` / `firmwareVersion` / `batteryState` (`"ok"` / `"low"`) / `batteryBars` (1–3) / `acPower` (wired cameras only)
- `snapThumbnail` / `recordClip` commands

Per-camera motion enable/disable currently works only for standard cameras (not Mini/Owl, doorbells, or floodlights). The other variants are exposed but their `on()` / `off()` will log a warning; this is the path that will be split into per-type child drivers in a follow-up.

## Example rules

A handful of automations this integration enables, shown in trigger → action form:

**Arm/disarm with Hubitat mode.** When location mode changes to *Away*, turn on the Blink Network device's `switch`. Reverse on *Home*. Equivalent to arming the system in the Blink mobile app, but triggered by Hubitat's authoritative mode.

**Clip-recorded notification.** Trigger on `lastClipTime` changing for a camera; send a push notification with the camera label and `lastClipUrl`. The URL plays in any browser or video app — a one-tap recording link directly from the camera. (Blink-issued clip URLs are short-lived signed URLs; deliver promptly.)

**Motion-triggered outdoor lighting.** Trigger on `motion` becoming `active` for a camera, with a condition of "between sunset and sunrise," and turn on the matching outdoor light for 5 minutes. Blink does not push events — `motion` reflects what the most recent poll reported, so reaction latency is bounded by the poll interval (up to 60 s at the default). Lowering the interval helps but Blink throttles short intervals; see *Polling* below.

**Low-battery alert.** Trigger on `batteryState` becoming `"low"` for any Blink Camera (or on `battery` dropping below your own threshold); send a notification with the device label.

**Doorbell mains-power loss.** Trigger on `acPower` changing for the doorbell; if the new value reports no AC, notify *"Doorbell on backup battery."* Only fires for cameras that report `acPower` (wired models).

## Settings reference

Everything you can interact with on the Blink Manager app, section by section.

### 🟢 Status (read-only)

Connection state, region tier, account ID, token expiry, and a summary of the last poll (timestamps and per-network armed state). No knobs — informational only. Token refresh is automatic five minutes before expiry; the displayed expiry is for diagnostics.

### 📷 Devices (read-only)

The list of `Blink Network` and `Blink Camera` children, with links to each device's edit page. Camera entries that aren't default-type cameras show a small tag (`mini`, `doorbell`, `superior`, `storm`) since variant-specific commands aren't yet implemented for those. Orphans (cameras or networks deleted in the Blink mobile app but still present in Hubitat) appear with a **Remove orphaned devices** button.

### ⏱️ Polling

- **Poll interval (seconds)** — how often the integration calls Blink's homescreen endpoint. Options: `60` (default), `120`, `300`. Sub-60 s is not exposed; see the *Polling* section below for the why.
- **🔄 Refresh now** — fires an immediate poll, bypassing the schedule.

<a id="blink-notifications"></a>
### 🔔 Blink notifications

Mirrors the notification toggles in the Blink mobile app. **Account-wide** — not per-network or per-camera. Controls whether Blink fires its own push notifications for camera events. Toggling here does not affect Hubitat rule behaviour; it changes what Blink emails / push-notifies / SMSes the account owner about.

- The exact flag set is **whatever Blink returns** at fetch time. Common flag names you may see:
  - `low_battery` — Blink notifies you when a camera battery is low
  - `camera_offline` — camera lost contact with the sync module
  - `sync_module_offline` — the sync module itself went offline
  - `motion` — motion-clip notifications (the main one for most users)
  - `doorbell_button` — doorbell-press notifications
  - `local_storage` — local-storage-related notifications (USB-equipped sync modules)
- Blink may add or remove flags without notice. The integration discovers and exposes whatever is currently in the response.
- The label shown next to each toggle is a humanized version of the underlying flag name; the raw `snake_case` name is shown below in `<code>` style for unambiguity.
- **🔄 Refresh notification flags** — re-fetches the current set from Blink. Click after changing notification settings in the Blink mobile app to pull the new values in.

To change a flag: toggle the input, then click **Done** at the top of the page. The integration diffs your settings against Blink's last-fetched state and pushes only the changed flags.

### 🛠️ Diagnostics & advanced (sub-page)

Houses the rare or destructive actions. The link on the main page opens a sub-page with:

- **🩺 State snapshot** — read-only dump of internal auth/discovery state (token presence, tier, account ID, hardware ID). Useful when reporting a bug.
- **Recovery** *(only when account ID is null)* — manual retry for the tier-discovery call. Visible only when something went wrong with first-time setup.
- **🚪 Disconnect / reset** —
  - **Disconnect** — clears auth tokens but preserves tier/account/hardware ID for one-click re-auth.
  - **Reset auth state (full wipe)** — clears everything; requires a fresh login from scratch. Use this only if Disconnect's lighter cleanup didn't resolve the problem.

### ⚙️ Settings

- **Assign a name** — rename the Manager app's label.
- **Enable descriptionText logging** *(default on)* — info-level event logs.
- **Enable debug logging** *(default off)* — verbose diagnostic logs; auto-disables after 30 minutes.
- **Enable trace logging** *(default off, only shown when debug is on)* — even more verbose; intended for debugging the integration itself.

## Polling

Default poll interval is 60 s. Configurable to 60 / 120 / 300 s from the manager app's settings page. The integration calls `/api/v3/accounts/{accountId}/homescreen` once per cycle (cheap), reads everything from a single response, and dispatches to children.

Because Blink does not push events, every observable state change (motion, arm/disarm result, new clip) becomes visible to Hubitat only on the next poll. Polling more often shortens reaction latency, but **60 s is the minimum recommended by [blinkpy's maintainer](https://github.com/fronzbot/blinkpy/blob/dev/README.rst)** ("API calls faster than 60 seconds is not recommended as it can overwhelm Blink's servers"). Intervals below that risk `Endpoint possibly down or throttled` errors from Blink — observed in the wild on the [Home Assistant community](https://community.home-assistant.io/t/blink-integration-seems-to-be-inconsistent-and-giving-possibly-down-or-throttled-error/231015) — and are no longer exposed as a setting option.

## Orphans

If a camera or network is removed from your Blink account, the corresponding Hubitat child device is flagged as orphaned on the manager app's main page but **not auto-deleted**. Click the **Remove orphaned devices** button to remove them explicitly — this preserves user-edited labels, dashboard pins, and rule references until you say otherwise.

## Acknowledgments

Blink does not publish API documentation. Two open-source projects shaped this integration's understanding of the Blink cloud and deserve credit:

- **[blinkpy](https://github.com/fronzbot/blinkpy)** (MIT) — the Python reference implementation for the Blink API.
- **[Blink API driver by Snell](https://community.hubitat.com/t/project-driver-for-blink-api/51257)** (Apache 2.0) — the pre-existing Hubitat integration this project replaces.

This integration is an independent reimplementation; both sources were consulted for protocol facts (endpoints, payload shapes, field names) only, not structural code. No source from either project is incorporated.

## License

MIT — see individual source files for the full license text.

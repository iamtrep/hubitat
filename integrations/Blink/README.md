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

The access token is refreshed automatically 5 minutes before expiry. Refresh tokens are persisted in the app's `state` and survive hub restarts. **They are not preserved across an app code push** — after pushing new code you may need to log in again.

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

## Polling

Default poll interval is 60 s. Configurable to 30 / 60 / 120 / 300 s from the manager app's settings page. The integration calls `/api/v3/accounts/{accountId}/homescreen` once per cycle (cheap), reads everything from a single response, and dispatches to children.

Because Blink does not push events, every observable state change (motion, arm/disarm result, new clip) becomes visible to Hubitat only on the next poll. Polling more often shortens reaction latency, but Blink throttles aggressively short intervals; 30 s is the safe lower bound (matches blinkpy's default).

## Orphans

If a camera or network is removed from your Blink account, the corresponding Hubitat child device is flagged as orphaned on the manager app's main page but **not auto-deleted**. Click the **Remove orphaned devices** button to remove them explicitly — this preserves user-edited labels, dashboard pins, and rule references until you say otherwise.

## Acknowledgments

Blink does not publish API documentation. Two open-source projects shaped this integration's understanding of the Blink cloud and deserve credit:

- **[blinkpy](https://github.com/fronzbot/blinkpy)** (MIT) — the Python reference implementation for the Blink API.
- **[Blink API driver by Snell](https://community.hubitat.com/t/project-driver-for-blink-api/51257)** (Apache 2.0) — the pre-existing Hubitat integration this project replaces.

This integration is an independent reimplementation; both sources were consulted for protocol facts (endpoints, payload shapes, field names) only, not structural code. No source from either project is incorporated.

## License

MIT — see individual source files for the full license text.

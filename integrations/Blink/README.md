<!--
Copyright (c) 2025-2026 PJ
SPDX-License-Identifier: MIT
-->

# Blink Integration

Parent/child integration for [Blink](https://blinkforhome.com/) home security cameras. The manager app authenticates against the Blink cloud (OAuth 2.0 + PKCE + 2FA), discovers cameras and networks, and creates lean child devices that expose only the attributes that are useful on Hubitat.

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
- `TemperatureMeasurement` — built-in temperature sensor
- `lastClipUrl` / `lastClipTime` — most recent recorded clip
- `wifiSignal` — dBm
- `online` / `firmwareVersion` / `batteryState`
- `snapThumbnail` command — request a fresh still image

Per-camera motion enable/disable currently works only for standard cameras (not Mini/Owl, doorbells, or floodlights). The other variants are exposed but their `on()` / `off()` will log a warning; this is the path that will be split into per-type child drivers in a follow-up.

## Polling

Default poll interval is 60 s. Configurable to 30 / 60 / 120 / 300 s from the manager app's settings page. The integration calls `/api/v3/accounts/{accountId}/homescreen` once per cycle (cheap), reads everything from a single response, and dispatches to children.

## Orphans

If a camera or network is removed from your Blink account, the corresponding Hubitat child device is flagged as orphaned on the manager app's main page but **not auto-deleted**. Click the **Remove orphaned devices** button to remove them explicitly — this preserves user-edited labels, dashboard pins, and rule references until you say otherwise.

## Acknowledgments

Blink does not publish API documentation. Two open-source projects shaped this integration's understanding of the Blink cloud and deserve credit:

- **[blinkpy](https://github.com/fronzbot/blinkpy)** — the Python reference implementation for the Blink API.
- **[Blink API driver by Snell](https://community.hubitat.com/t/project-driver-for-blink-api/51257)** — the pre-existing Hubitat integration this project replaces.

## License

MIT — see individual source files for the full license text.

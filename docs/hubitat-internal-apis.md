<!--
Copyright (c) 2025-2026 PJ
SPDX-License-Identifier: MIT
-->

# Hubitat internal hub APIs

Reference for the internal admin HTTP APIs of a Hubitat Elevation hub. Hubitat officially documents only Maker API; the endpoints below drive the hub's admin web UI and are used by this repo's skills and scripts to provision apps, push code, list devices, etc. Reverse-engineered from HAR captures and the hub's Vue JS. Not officially supported — endpoints and payload shapes can change between firmware versions.

`{hub_ip}` below is a placeholder for the hub's LAN address.

## Read-only inventory endpoints

- `GET /hub2/hubData` — hub metadata: `name`, `model`, `version` (firmware), `ipAddress` — no auth required
- `GET /hub2/userDeviceTypes` — list user drivers (includes `usedBy` with device IDs/names)
- `GET /hub2/userAppTypes` — list user app types
- `GET /hub2/devicesList` — list all devices
- `GET /hub2/appsList` — list installed apps
- `GET /driver/ajax/code?id={ID}` — get driver source + version
- `POST /driver/ajax/update` — push driver code (id, version, source form-encoded)
- Same pattern for apps: `/app/ajax/code`, `/app/ajax/update`

## Code push details

- POST body: `id={ID}&version={VERSION}&source={URL_ENCODED_SOURCE}`
- Use `--data-urlencode "source@{FILEPATH}"` to auto-encode file contents
- Response: `{"id":..., "version":..., "status":"success"}` on success

## Live logs and events

- `ws://{hub_ip}/logsocket` — WebSocket for real-time hub log stream
- `ws://{hub_ip}:80/eventsocket` — WebSocket for real-time device-state events (used by the admin UI)

## Install an app instance

- `GET /installedapp/create/{appTypeId}` — creates an installed instance of an app type, returns the installed app ID
- `GET /installedapp/configure/{installedAppId}` — opens the configuration page for the instance
- Newly-created instances are **not visible to `/hub2/appsList` until configured at least once.** Capture the new id from the `302 Location` header on the `create` response — don't poll appsList.

## Cross-hub publish

- `GET /hub/publishCode/{type}/{id}` — start publishing to other hubs (`{type}` is `driver` or `app`)
- `GET /hub/publishCode/status` — poll distribution status
- Response: `{"success":true,"completed":bool,"hubs":[{"id":"...","name":"...","status":"Pending|Done"}]}`

## App configuration (add/remove devices, change settings)

- `GET /installedapp/configure/json/{installedAppId}` — get full app config as JSON (inputs, settings, etc.)
- `POST /installedapp/update/json` — save app configuration. Content-Type must be **`application/x-www-form-urlencoded`** despite the `json` suffix; the `json` refers to the response format. Sending `application/json` returns HTTP 500 with no useful diagnostic.
- **Session cookie required**: hub issues a `HUBSESSION` cookie on any GET; must be captured and sent with POST (`curl -c cookiejar -b cookiejar`)
- `settings[{deviceInput}]` = comma-separated device IDs is the definitive device list
- All inputs must be echoed back with `.type`, `.multiple`, and `settings[{name}]` metadata
- Bool inputs additionally need `checkbox[{name}]=on`
- Enum-multiple inputs: value must be a **JSON array string** — `["Events","Actions"]`, `[]` for empty — NOT comma-separated
- Label input needs both `label.type=text` and `label={app label value}`
- Required POST fields (confirmed from HAR): `id`, `version`, `currentPage`, `formAction=update`, `url` (full configure URL), `pageBreadcrumbs=%5B%5D`, `referrer`, `_action_update=Done`, `_cancellable=false`
- `appTypeId` and `appTypeName` may be omitted on updates (were empty in HAR)
- Label inputs use `app.label` value (NOT from `settings` object)
- Null settings values should be sent as `[]` (not empty string) **for fields without a `type`**. For typed fields, omit instead — see the `[]` warning in [`hubitat-platform-notes.md`](hubitat-platform-notes.md).
- Success response: `{"status":"success","location":"/installedapp/list"}`
- Sub-page settings are not yet POSTable via this endpoint — `/installedapp/update/json` only addresses `mainPage`. Sub-pages (`SwitchMonitor` groupPage, etc.) need a HAR-captured POST format that hasn't been derived.
- On `/hub2/appsList`, the installed-app's user-set label is stored as `data.name`; `data.label` is always null. Match installed-app labels via `data.name`.

## Device discovery

- `GET /device/listJson?capability={capability}` — list devices with a specific capability
  - e.g. `capability=capability.battery`, `capability=capability.notification`
  - Returns `[{"id":N,"name":"...","label":"...","displayName":"..."},...]`
  - Useful for populating device picker inputs programmatically
- Multiple drivers can share a name; filter by `source=='System'` when disambiguating. (E.g., on some hubs, "Virtual Switch" has both a System variant and a Linked variant; the Linked variant's `on()`/`off()` are no-ops.)

## Create new app/driver types

- `POST /app/saveOrUpdateJson` — create new app (or `/driver/saveOrUpdateJson` for drivers)
- Content-Type: `application/json`
- Body: `{"source": "...", "version": 1}`
- Response: `{"success":true, "message":"", "id":..., "version":1}`
- No auth cookie needed

## Device creation (virtual devices)

- `POST /device/save` with form-encoded fields: `name`, `label`, `deviceNetworkId`, `deviceTypeId`
- Returns HTTP 302 on success (redirect to the new device's edit page)
- Field names discovered from `vue-hub2.min.js`: the `deviceModel` object
- Delete a virtual device: `GET /device/forceDelete/{id}/json` → `{"status":"success"}`
- Delete a user driver type: `GET /driver/deleteDeviceType/{id}` (delete any devices using it first)

## Run a device command (no Maker API)

- `POST /device/runmethod` — invoke any command on a device via the admin-UI channel, without a Maker API token. JSON body: `{"id": <deviceId>, "method": "<commandName>", "args": [{"type": "<paramType>", "value": <v>}, ...]}` (use `"args": []` for no-arg commands). Response: `{"success":true,"message":null}`
- Command processing is **async** — the POST returns before the command runs. Poll `GET /device/fullJson/{id}` (current attribute values are under `device.currentStates[].value`) until the expected state appears.
- A device's invokable commands are listed in `GET /device/fullJson/{id}` under `device…commands[]` (each has `name`, `parameters`).
- This is the **web-UI invocation channel**; its script-instance/binding lifecycle could differ from app- or Maker-API-driven calls. For production-representative behavior — and any test that cares about cross-invocation state — prefer the Maker API route `GET /apps/api/{appId}/devices/{deviceId}/{command}?access_token={token}`. (The two channels matched for command dispatch and binding persistence on firmware 2.5.0.143 — see [`hubitat-platform-notes.md`](hubitat-platform-notes.md).)

## Maker API specifics

### Token discovery

- The access token is **not** in `settings` — it's embedded in HTML links inside `configPage.sections[].body[]` paragraphs
- Look for `description` fields containing `access_token=` in paragraph body elements
- Example: `<a href='http://{hub_ip}/apps/api/{id}/devices?access_token={TOKEN}'>`

### Device events via Maker API

- `GET /apps/api/{appId}/devices/{deviceId}/events?access_token={TOKEN}`
- Returns array: `[{"device_id","label","name","value","date","unit","isStateChange","source"},...]`
- Most recent events first; useful for verifying app behavior in tests

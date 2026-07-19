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
- `GET /hub/mdnsDevices` (firmware 2.5.0.126+) — services the hub has discovered via mDNS/Bonjour on the LAN (pre-commissioning visibility — HomeKit accessories, ESPHome devices, printers, AirPlay receivers, integration bridges). Response shape not yet HAR-verified; capture before parsing.
  - mDNS is **always on** — the hub is a permanent mDNS/Bonjour responder (HomeKit and Hub Mesh depend on it); there is no disable. The only toggle is **`restartBonjourOnSchedule`** (bool) on `GET /hub2/networkConfiguration` (firmware 2.3.9.184) — "periodically restart Bonjour," recommended OFF (periodic restarts trigger a LAN multicast storm). Neither `/hub2/hubData` nor `/hub/details/json` carries the flag.
  - `GET /hub/details/json` carries **`mdnsName`** — the advertised `.local` name (e.g. `hub-<name>`). Only mDNS field there; not an enable/disable flag.
- `GET /hub/zigbee/getChildAndRouteInfoJson` — the coordinator's own Zigbee tables. Response `{status, devices, children, neighbors, routes}`: `neighbors` is the coordinator's neighbor table (`{id, lqi, age, inCost, outCost}`), `routes` its routing table (`{id, nextHopId, used, status, age, routeRecordState, concentratorType}`), `children` its directly-parented end devices. This is the **coordinator's view only** — asymmetric; to learn how another router sees the coordinator you must still ZDO-probe that router.
- `GET /hub2/chart/data?deviceId={id}&attribute={name}` (firmware 2.5.0.135+) — historical attribute trace from a **separate** store than the main events DB; retention 31 days OR 1000 values, whichever caps first. Independent of events-DB churn — useful when the events store has been compacted but a long-window trace is still wanted. Response shape not yet HAR-verified.
- `GET /hub/zigbee/healthStatus` and `GET /hub/zwave/healthStatus` (firmware 2.4.1.154+) — dedicated per-radio health probes. **Response is a plain text body of `true` or `false`** (verified 2026-05-29 on firmware 2.5.0.143). Content-Type is `text/html;charset=utf-8` — misleading, the body is the literal text `true`/`false`, NOT JSON. Use for cheap up/down badges; use `/hub/zigbeeDetails/json` / `/hub/zwaveDetails/json` when per-device mesh info is needed.

## Radio health and topology

- The radio **detail** endpoints (`/hub/zigbeeDetails/json`, `/hub/zwaveDetails/json`, `/hub/zigbee/getChildAndRouteInfoJson`, the `healthStatus` probes above) read **cached** state — they do NOT poke the radio chip, so calling them is cheap and safe.
- **Radio reboots logged in `/hub/eventsJson` indicate hub-wide overload, not a radio fault** — an overloaded hub can't service the Zigbee NCP heartbeat in time, the NCP times out, and the firmware reboots the radio to recover. Investigate broad resource use (blocking sync HTTP on the app thread, multi-MB file I/O, heavy JSON parse), not the radio or specific endpoints.
- **Z-Wave controller is node 01** (hex `01`) — the coordinator, not a paired device. It is **absent** from `/hub/zwaveDetails/json` (`zwDevices` / nodes list paired devices only) but **present** in the `/hub/zwaveTopology` matrix (always the first row and column). Treat `01` as "Hub"; don't expect it in device lists or synthesize a Hubitat `deviceId` for it.

## Process monitor / auto-reboot controls

- `GET /hub/advanced/disableHubProcessMonitor` / `GET /hub/advanced/enableHubProcessMonitor` — toggle the hub's process watchdog. **Widened in firmware 2.4.3.137**: these now ALSO control the critical-CPU auto-reboot added in 2.4.3.133 (the platform auto-reboots if CPU stays at a critical level for ≥15 min; suppressed during the first hour of uptime).

## Code push details

- POST body: `id={ID}&version={VERSION}&source={URL_ENCODED_SOURCE}`
- Use `--data-urlencode "source@{FILEPATH}"` to auto-encode file contents
- Response: `{"id":..., "version":..., "status":"success"}` on success
- `POST /app/ajax/update` bumps the source body and `appType.version` but does **NOT re-parse the `definition()` block** — app-level flags (`singleThreaded`, `menu`, `parent`, `category`, …) stay whatever they were at the last create/re-parse. Force a re-parse by POSTing the existing `id` + current `version` + source to `/app/saveOrUpdateJson` (what the editor's Save button does). Scope is app `definition()` flags only: a driver push recompiles, so new driver commands/capabilities/preference inputs surface on a device-page reload with no save.

## Live logs and events

- `ws://{hub_ip}/logsocket` — WebSocket for real-time hub log stream
- `ws://{hub_ip}:80/eventsocket` — WebSocket for real-time device-state events (used by the admin UI)
- **All of these log/event/radio websockets accept the WS upgrade with NO auth** — `/logsocket`, `/eventsocket`, `/zigbeeLogsocket`, `/zwaveLogsocket` upgrade with no cookie or login handshake even on hub-security-enabled hubs (cookie auth only gates the plain HTTP `/` paths). External (Python/Node) tooling against a secured hub can use the socket path with no login code.
- `ws://{hub_ip}/zigbeeLogsocket` / `ws://{hub_ip}/zwaveLogsocket` — WebSocket streams of raw received radio frames (the hub's Zigbee/Z-Wave "logging" pages consume these). The Zigbee socket emits one JSON object per received frame:

  `{name, id (DNI as 16-bit int), profileId, clusterId, sourceEndpoint, destinationEndpoint, groupId, sequence, lastHopLqi, lastHopRssi, time ("YYYY-MM-DD HH:MM:SS.mmm"), type: "zigbeeRx", deviceId, payload: [hex-string bytes]}`

  - **Carries `sourceEndpoint` / `destinationEndpoint`** (2-hex strings, e.g. `"01"`, `"19"`, `"00"`). Without them, multi-endpoint devices (dual-relay plugs, multi-endpoint thermostats) are indistinguishable on the wire.
  - **`sourceEndpoint == "00"` = ZDO** (network layer). ZDO request cluster IDs (0x0000–0x003F) collide with ZCL cluster IDs — 0x0006 is both `Match_Desc_req` and On/Off — so route endpoint-0 frames to a ZDO parser by endpoint, not by cluster ID. ZDO requests carry no status byte; responses (cluster 0x80xx) do.
  - **All frames are `type: "zigbeeRx"`** — received by the hub (device→hub). The envelope carries endpoints but no src/dst node addresses, so direction is inferred from the ZCL frame-control direction bit, not the envelope. (Verified on a C-7, firmware 2.5.0.146.)
  - **ZDO `Mgmt_Rtg_req` (0x0032) is spec-OPTIONAL** — many router firmwares reply `0x84 NOT_SUPPORTED` (a compliance gap, not a fault). Their routing tables become a blind spot in the mesh map, which still matters on deep meshes where routing tables carry rich multi-hop forwarding paths and route-status flags (Active / Discovery Failed / …). For the 0x8031/0x8032 response decode, see [`hubitat-zigbee-helper.md`](hubitat-zigbee-helper.md) → ZDO responses.
  - **`zwaveLogsocket`** is the Z-Wave sibling socket; its per-message frame shape is not yet verified in this repo.

- `GET /hub/matterLogs/json` → `{"text": "<ANSI-colored CHIP/Matter SDK dump>"}` — **polling only, no socket** (the native page polls ~every 2 s). Each response is the **full rolling buffer** (no `offset`/`since`/`Range` params, same byte size each poll), so a live tail must dedup against the previous poll — anchor on a block of trailing lines (lines repeat verbatim), not single lines. CHIP line format (ANSI-stripped): `[<epoch>.<ms>] [<pid>:<tid>] [<COMPONENT>] <message>`, with indented multi-line continuations. Components seen: `DMG`, `EM` (busier hubs add `SC`, `IM`, `BLE`, `DL`, `IN`, …).

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
- **Loopback bypasses hub security:** `http://127.0.0.1:8080` (same-hub app→app calls and `/installedapp/`, `/hub2/`, `/device/`, `/hub/*` from on-hub code) is NOT gated by hub security — only off-hub LAN-IP access is. An on-hub integration hitting loopback needs no credential handling.
- `settings[{deviceInput}]` = comma-separated device IDs is the definitive device list
- A bare `{name}=value` (no brackets) returns `{"status":"success"}` but **persists nothing** — only the `settings[{name}]` bracket form sticks (keep the `.type` metadata bare alongside it).
- **Dynamically-added input rows must be seeded first** by POSTing the add-button via `/installedapp/btn` (fires `appButtonHandler`, whose `state` write persists). A dynamicPage only *declares* its grown inputs once `state` holds their ids, and `state` writes inside a page-render closure do NOT persist — so until the row exists, a settings POST for it is silently dropped. (`/installedapp/update/json` addresses `mainPage` only; genuine sub-pages remain un-POSTable — see below.)
- A **device multi-select** input (`capability.X`, `multiple: true`) needs `{name}.multiple=true` sent on the POST — without it the input stores `multiple:false`, so only the FIRST device id's subscription arms even though all ids look bound (`settings` show every device, but `eventSubscriptions` covers one). Multiple ids go comma-joined in one `settings[{name}]=id1,id2,id3` field, not repeated keys.
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
- Multiple drivers can share a name; `data.source` on `/hub2/devicesList` disambiguates — **`System`** (built-in Hubitat driver, usually what you want), **`Linked`** (Hub Mesh receiver-side proxy whose `on()`/`off()` are no-ops that emit no events), **`User`** (user-installed custom driver). Filter `source=='System'` for a built-in when the hub already has a device using it — **no endpoint lists all available built-in drivers** (there is no built-in analogue to `/hub2/userDeviceTypes`). (E.g. "Virtual Switch" often has both a System and a Linked variant sharing the name.)
- **Label fields differ across endpoints:** on `/hub2/appsList` the user label is in `data.name` (`data.label` is always null); on `/hub2/devicesList` the label is in `data.label` (with `data.name` = the driver-internal name); `installedapp/configure/json/{id}` exposes it as `app.label`.
- **Find a device by hardware model:** read `device.data.model` from `GET /device/fullJson/{id}` (e.g. a Zigbee model string) — do NOT filter by driver name, since a device can be paired to any generic driver whose name won't mention the model. Pre-filter candidates via `/hub2/devicesList` `data.isZigbee` to bound the per-device `fullJson` calls (`/hub2/devicesList` itself does not carry `data.model`).
- **Hub Mesh remote (consumed) device identity:** a consumed remote carries its source in **`remoteDeviceUrl`** (in both `/hub2/devicesList` `data.remoteDeviceUrl` and `/device/fullJson/{id}` `device.remoteDeviceUrl`), format `http://<sourceHubIP>:<port>/device/edit/<sourceDeviceId>` — parse for source hub IP + device id. The remote marker is `data.isLinked == true`; `remoteDeviceUrl == '#'` marks an **orphaned link** (source hub gone/unreachable). On `fullJson`, `device.meshEnabled` is false and `device.isLinked` is null on remotes — rely on `remoteDeviceUrl` (non-empty, non-`#`) instead.
- **Field semantics — `isOrphan`:** `isOrphan` (in `/hub2/devicesList`) / `device.orphan` (in `fullJson`) is a **mesh/radio orphan** state — a lost radio parent/route — NOT "no apps subscribe." For unreferenced devices ("no apps subscribe") use `appsUsing[]` / `appsUsingCount` from `GET /device/fullJson/{id}`.

### Per-device event history

- `GET /device/eventsJson/{id}` — the device's recent event list as a JSON array (works on secured hubs via an authenticated session). Each row: `name`, `value`, `unit`, `descriptionText`, `source` (DEVICE/APP/…), `type`, `date` (ISO-8601 with tz offset), `producedBy`, `triggered`, `isStateChange`, `physical`, `digital`, `deviceId`.
  - **`physical` vs `digital`** discriminates a hand at the wall switch/button from an automation/command. Matter devices set neither (both false → treat as unknown).
  - **Count-capped per device** by the events DB — busy devices have a shorter window (observed ~11–190 rows). `unixTime` is present but null; parse `date`.
  - `/device/events/{id}` (no `Json`) is a Vue SPA shell — use `eventsJson`, not the HTML.

### Device state variables (driver `state`)

- `GET /device/fullJson/{id}` exposes a driver's Groovy `state.*` map as the **top-level `deviceState`** object — NOT under `device.state` (that key is empty/absent), and there is no `/device/state/{id}` endpoint. Keys are entirely driver-author-defined (no platform contract), so use `deviceState` for per-device display/backup only — never for cross-device aggregation or assuming a key exists across drivers.

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

### Maker API additions in 2.4.4 (HAR NEEDED before consumption)

Firmware 2.4.4.146 added room-management endpoints + a device-data endpoint; 2.4.4.151 added set-device-name + set-device-driver endpoints. **The exact paths, methods, query params, and response shapes are not yet HAR-verified in this repo.** Capture a HAR via the Maker API instance in the UI (DevTools → Network → "Preserve log" → perform the operation in the UI → save as HAR) before writing code against these. Once verified, fill in the contract here and resolve the stub in `memory/hubitat_maker_api_2_4_4_endpoints.md`.

Capabilities advertised (per the 2.4.4 release-notes thread):
- Room management (create / list / rename / delete?)
- Per-device "device data" retrieval
- Set device name
- Set device driver

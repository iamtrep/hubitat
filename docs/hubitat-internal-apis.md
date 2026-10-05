<!--
Copyright (c) 2025-2026 PJ
SPDX-License-Identifier: MIT
-->

# Hubitat internal hub APIs

Reference for the internal admin HTTP APIs of a Hubitat Elevation hub. Hubitat officially documents only Maker API; the endpoints below drive the hub's admin web UI and are used by tooling and tests to provision apps, push code, list devices, etc. Reverse-engineered from HAR captures and the hub's Vue JS. Not officially supported — endpoints and payload shapes can change between firmware versions.

`{hub_ip}` below is a placeholder for the hub's LAN address.

## Read-only inventory endpoints

- `GET /hub2/hubData` — hub metadata: `name`, `model`, `version` (firmware), `ipAddress` — no auth required. Carries no hub-security setting: `baseModel.userLoggedIn` is **session state** ("this request is authenticated"), not "security is enabled" — it reads `false` on a hub with security off. To read the setting, use the `/logout` probe under [App configuration](#app-configuration-addremove-devices-change-settings).
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
- `GET /hub/zwave2/getControllerState` (Z-Wave JS stack only) returns the controller identity + live statistics as JSON: `firmwareVersion`, `sdkVersion`, `homeId`, `ownNodeId`, `isPrimary`/`isSUC`/`isSISPresent`, `rfRegion`, `supportsLongRange`, and a `statistics` map (`messagesRX`/`TX`, `messagesDroppedRX`/`TX`, `CAN`, `NAK`, `timeoutACK`/`Callback`/`Response`, `backgroundRSSI`). It is the **only** source of these — `/hub/zwaveDetails/json` carries nodes/region/`firmwareVersion`/`zwaveJSVersion` but no controller identity or stats. Gate on the stack probe `GET /hub/zwave2/status` → body `true` (legacy Z/IP hubs return `false`); `sdkVersion` and `firmwareVersion` here describe the **radio chip** (Silicon Labs SDK / module firmware), distinct from the node-zwave-js library version in `zwaveDetails.zwaveJSVersion`.
- **`getControllerState` quirk — bare `null` body on some hubs:** at least one C-8 Pro (firmware 2.5.1.134, ZWave-JS 15.25.3) with a healthy 57-node mesh returns the literal body `null`, reproduced hitting the URL **directly in a browser** — so it is endpoint-intrinsic, not app concurrency or SPA load (the same call returns the full object under rapid/concurrent requests on a 0-node C-8 Pro). `null` is valid JSON but a scalar, so `httpGet` with `contentType:"application/json"` throws `groovy.json.JsonException` (`payload should start with { or [`) on parse — handle it as **no data available**, not a fault. `zwaveDetails/json` and `zwave2/status` still return normally on the same hub, so node/mesh/version data is unaffected. Cause is a platform/hub-side quirk, unrecoverable from an app.
- **Provenance:** `getControllerState` is undocumented by Hubitat and not referenced by the Z-Wave Details admin page's JS bundle (which uses `getNodeState`, `enable`/`disable`, `antennaTest*`). Which admin view, if any, calls it is unknown.

## Process monitor / auto-reboot controls

- `GET /hub/advanced/disableHubProcessMonitor` / `GET /hub/advanced/enableHubProcessMonitor` — toggle the hub's process watchdog. **Widened in firmware 2.4.3.137**: these now ALSO control the critical-CPU auto-reboot added in 2.4.3.133 (the platform auto-reboots if CPU stays at a critical level for ≥15 min; suppressed during the first hour of uptime).

## Platform versions and the Diagnostic Tool (port 8081)

The Diagnostic Tool is a separate Jetty process at `http://{hub_ip}:8081` (plain HTTP only, not relayed by `cloud.hubitat.com`). It keeps running when the main platform is down. Its UI asks for the hub's MAC address and stores the returned token in the browser's `localStorage` under `hubitat-diagnostic-tool-token`; the MAC is in `GET /hub/details/json` → `macAddress`, which needs an admin session when hub security is on. The two reads below answer **without** that login, on hubs with and without hub security (verified 2026-09-27 on firmware 2.5.1.183 and 2.5.2.124). Both return `application/json`. CORS allows only `https://findmyhub.hubitat.com`, so a browser page served from the hub can't read them; call them server-side (an app's `httpPost` to `http://127.0.0.1:8081` works, sync and async).

- `POST :8081/api/versions`: platform versions stored on the hub and restorable from the tool, `{"success":true, "hubList":["hub-2.5.2.120", …], "currentVersionExecuting":"hub-2.5.2.124"}`. Strip the `hub-` prefix. `GET` returns 404.
- `POST :8081/api/hubInfo`: `{hubId, uv, hasServices, hubAvailable, pv, ip, hubTime, hv, stableVersion}`. `pv` is the running platform (`hub-` prefixed), `hv` the hardware model, `uv` the tool's own version, `stableVersion` the fallback release the tool's "switch to stable" installs. `hubId` carries a trailing `\n`.

Downloading a release so it becomes restorable is a main-platform endpoint:

- `GET /hub/advanced/downloadPlatform/{line}`: downloads the latest stable release of a version line and adds it to the `/api/versions` list. `{line}` is the line's digits without dots: `250` for 2.5.0.x, `234` for 2.3.4.x. The request blocks until the download completes (about 97 s on a C-8 Pro), then answers plain text `success`. An unknown line answers `incorrect version` immediately. Both are HTTP 200 with `text/html`, so check the body. On a C-8 Pro, downloading 2.5.0.159 dropped free OS memory from about 900 MB to 370 MB during the download (settling near 725 MB) and removed no stored version. It is a GET that changes hub storage: call it from a button, never from a link that prefetching could follow.

## Code push details

- POST body: `id={ID}&version={VERSION}&source={URL_ENCODED_SOURCE}`
- Use `--data-urlencode "source@{FILEPATH}"` to auto-encode file contents
- Response: `{"id":..., "version":..., "status":"success"}` on success
- `POST /app/ajax/update` recompiles and re-reads `definition()`: on firmware 2.5.2.128 a push changed `category`, `description`, `menu`, `singleInstance` and `importUrl` with no Save in the editor. Read them back from `GET /app/list/single/data/{id}`. `oauth: true` in `definition()` does not enable OAuth on push or install; that takes the editor's OAuth button (`oauthClientId` stays null, `/hub2/userAppTypes` `oauth` stays empty). `singleThreaded` is not exposed by any endpoint, so whether a push applies it is unconfirmed. A driver push likewise picks up new commands, capabilities and preference inputs on a device-page reload.

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

### Logs page endpoints

The Logs page (`/logs`, tabs `?tab=past` etc.) is a Vue chunk (`/ui2/js/vue-hub2-logs.min.js`) that reads these:

- `GET /logs/json`: runtime stats since boot. Top-level `uptime`, `appStats[]`, `deviceStats[]`, `jobs[]`, `runningJobs[]`, `hubCommands`, `totalAppsRuntime`, `totalDevicesRuntime`, `appsUptime`, `devicesUptime`, `appPct`, `devicePct`, `maxEvents`, `maxStates`, `showAppStatDetails`, `showDeviceStatDetails`, plus `check*` column-visibility flags (strings `"true"`/`"false"`). Each stats entry: `id`, `name`, `total`, `count`, `average`, `pct`, `pctTotal`, `grandTotal`, `stateSize`, `largeState`, `hubActionCount`, `cloudCallCount`, `pendingEventsCount`, `customAttributes`, and `formatted*` display strings. Each `jobs[]` entry: `id` (`app{id}Once.{method}`, `dev{id}Recur.{method}`), `name`, `link`, `recurring`, `methodName`, `nextRun`, `nextRunDt`. Slow on a loaded hub (15 s or more). 2.5.2.129 removed the `check*` flags for the average, total and percent-of-total columns and the uptime/total headers, and added `checkDriversStatesCountColumn`; the stats fields did not change.
- `GET /logs/past/json`: the hub's past-log buffer as a JSON array of strings, one per line: `"{yyyy-MM-dd HH:mm:ss.SSS}\t{LEVEL} \t{type}|{id}|{name}|{message}"`, where `type` is `app`, `dev` or `sys`. Covers several hours; read it instead of a live capture to answer "what logged in the last N minutes".
- `GET /logs/eventsJson` and `GET /hub/eventsJson`: event lists for the page's events views.
- `GET /logs/cloudCalls/json` (new in 2.5.2.129, 404 before): inbound cloud-relay requests per app, in hourly buckets. Backs the "Cloud calls" tab.

  `{apps: [{id, name, installed, total, currentHour}], hours: [{appId, hourStart, count}], startedAt, generatedAt, timeZone}`

  Times are epoch ms; `hourStart` is the bucket start. Counts start at `startedAt`, which looked like hub boot in one sample. Requests to an app ID that no longer exists still count: that app is listed with `installed: false` and `name: "Deleted app ({id})"`. The hub also logs each one as a `sys` WARN, `Received cloud request for App {id} that does not exist, path: {path} from {ip}`, where `{ip}` is the caller's public address. The caller can be the hub itself, for example a driver whose cloud-check URL still names the deleted app. A LAN DNS log for `cloud.hubitat.com` lookups on the same cadence identifies the device.

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
- **Detecting whether hub security is enabled — no credentials needed:** on-hub, `GET http://127.0.0.1:8080/logout` with redirects disabled. `Location: http://127.0.0.1:8080/login` means security is ON; any other target means OFF. The OFF case is verified (302 to `/`, 2026-09-20, firmware 2.5.2.113); the ON case is taken from thebearmay's Hub Information Driver v3, which ships this probe as its `securityInUse` attribute (`hubInfoV3.groovy`, `checkSecurity()`/`getSecurity()`, added v3.1.7) — not independently re-verified here. Only discriminates from on-hub — off-hub against a secured hub every path redirects to `/login`. An app config page therefore never needs to ask the user whether hub security is on, and — given the loopback bullet above — an app whose HTTP calls all target loopback needs neither the flag nor the credentials.
- `settings[{deviceInput}]` = comma-separated device IDs is the definitive device list
- A bare `{name}=value` (no brackets) returns `{"status":"success"}` but **persists nothing** — only the `settings[{name}]` bracket form sticks (keep the `.type` metadata bare alongside it).
- **Press an app's button input:** `POST /installedapp/btn`, form-encoded: `id` (installed app id), `name` (the button input's name), `settings[<name>]=clicked`, `<name>.type=button`. It calls the app's `appButtonHandler(name)`, so tests can drive in-hub apps without the UI.
- **Buttons inside paragraph HTML** (grids of `app-button-link` divs, as built-in apps draw them) may carry a `data-stateAttribute`; the page then adds `stateAttribute=<that value>` to the `/installedapp/btn` POST, and the app uses it to tell apart buttons that share a name (Thermostat Scheduler's heating and cooling cells are both named `Wake.1`, with `tsH` and `tsC`). A button without the attribute sends no `stateAttribute`; adding one anyway made toggle buttons do nothing. After the click, a button with class `submitOnChange` is followed by a form save without Done (next bullet) *(observed 2.5.2.129, from `/ui2/js/appUI.js`)*.
- **Save-on-change** (an input with `submitOnChange`, or the save that follows such a button): the page POSTs the whole form to `/installedapp/update/json` exactly as for Done but **without `_action_update=Done`**, so `updated()` does not run. Use this form to drive an app's in-page editors. A Done save while a built-in app had an editor open left that app's page throwing on every render. Some inputs appear only after an earlier value is saved (a sunrise offset after choosing *Sunrise*), so set them in a second POST *(observed 2.5.2.129)*.
- A setting left out of an `update/json` POST is **kept**, not removed.
- **Create a child of a built-in parent app** (a new Thermostat Scheduler, Rule, ...): `GET /installedapp/createchild/{namespace}/{appName}/parent/{parentAppId}` (e.g. `hubitat/Thermostat%20Scheduler%202.0/parent/{parentAppId}`) → 302 to `/installedapp/configure/{newId}`. The new instance has no subscriptions until its page is saved with Done.
- To see what the page itself sends when the browser's network log misses it, wrap jQuery's post in the page and read it back: `window.__posts=[]; const op=$.post; $.post=(u,d,...r)=>{__posts.push({u,d}); return op(u,d,...r)}`.
- **Dynamically-added input rows must be seeded first** by POSTing the add-button via `/installedapp/btn` (fires `appButtonHandler`, whose `state` write persists). A dynamicPage only *declares* its grown inputs once `state` holds their ids, and `state` writes inside a page-render closure do NOT persist — so until the row exists, a settings POST for it is silently dropped. (Sub-page inputs: see the sub-page bullet below.)
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
- Sub-page settings are POSTable through the same endpoint. Read the page with `GET /installedapp/configure/json/{id}/{page}`, then POST that page's inputs with `currentPage={page}`, `pageBreadcrumbs=["mainPage"]` and the page name at the end of `url` (`/installedapp/configure/{id}/{page}`). Save one page per POST; each save runs `updated()`, so re-read the next page before saving it *(verified 2.5.2.129)*.
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
- Change a device's driver (or name, label, room): `POST /device/update`, form-encoded, the device page's Save. It replaces the whole record, so send every field: `id`, `version` (from `/device/fullJson/{id}` `device.version`), `name`, `label`, `deviceNetworkId`, `deviceTypeId`, `zigbeeId`, `maxEvents`, `maxStates`, `spammyThreshold`, `roomId`, `groupId`, `locationId`, `hubId`, `meshEnabled`, `retryEnabled`, `homeKitEnabled`, `dashboardIds`, `tags`, `defaultIcon`, `notes`, `controllerType`, `deviceTypeReadableType`, and `meshFullSync=on` when Hub Mesh full sync is on. `meshFullSync` is a checkbox: leaving it out turns full sync off. The page sends unset fields as `""`, which the hub stores as `0` for `roomId`/`groupId`. `notes` keeps line breaks. A stale `version` is not rejected, so the last save wins, including a device page left open with older values. Field list from HARs of the device page on 2.5.2.128 and 2.5.2.129; a save built from it changed only the edited field *(verified 2.5.2.129)*. `apps/tests/test-architecture-claims.sh` uses it to switch drivers. It is also the only way to write a device's notes: an app gets `getNotes()` but `setNotes()` is not supported and `notes` is read-only on the device *(verified 2.5.2.129)*.
- Delete a user driver type: `GET /driver/deleteDeviceType/{id}` (delete any devices using it first)

## Device swap and replace

- **Swap Apps Device** (Settings) is a built-in app. `GET /installedapp/direct/swapDevice` → 302 `/installedapp/create/{typeId}` → 302 `/installedapp/configure/{id}`, a pending instance. Its `mainPage` has enum inputs `oldDev` and `newDev` (submitOnChange; options as `[{"<id>":"<label>"}]`). `newDev` gets its options only after `oldDev` is saved, and they are the devices the hub accepts as compatible. A `doSwap` button appears once both are set. Click it with `POST /installedapp/btn`. The pending instance survives the click; remove it with `GET /installedapp/delete/{id}` *(verified 2.5.2.129)*.
- **What the swap does:** it exchanges the two device records. Name, label, DNI, driver, capabilities, current states and `createTime` move between the two ids. App settings, subscriptions and app `state` are not touched, and `updated()` does not run, so every reference to the old id now reaches the new hardware. Event history stays with the id, so the old id's history continues with the new device's events. The old hardware ends up under the new device's id. Verified with two virtual switches and with a Virtual Contact Sensor swapped for a Virtual Omni Sensor *(2.5.2.129)*. A check that the old id has lost its dependents therefore reports a successful swap as a failure. bravenel describes it as a DNI swap ("It swaps the DNIs of the two devices", [community thread 148552](https://community.hubitat.com/t/feedback-clarification-of-swap-versus-replace-device/148552)); the test shows more than the DNI moving. Hubitat excludes child devices because a child and its parent call each other's methods, and nothing maps those links ([thread 125101](https://community.hubitat.com/t/connector-support-in-swap-apps-device/125101), [110895](https://community.hubitat.com/t/feature-request-swap-devices-that-works-for-child-devices/110895)).
- **Eligibility:** `oldDev` lists only devices some app uses. App-child devices are excluded except those of an allowlist of built-in integrations, added in 2.4.4.129: "whitelisted AirPlay, Wiz, HomeKit, Bluetooth and Tuya integrations to allow child device swaps" ([release notes](https://community.hubitat.com/t/release-2-4-4-available/161597)). On one hub, children of AirPlay, Bluetooth and HomeKit Controller were listed; children of Lutron, Ecobee and every user app were not. Hub Mesh linked devices are listed *(2.5.2.129)*. The same release added a capability check on the target device.
- `GET /device/getReplacementOptions/{id}` → `[{"id":N,"name":"...","deviceTypes":[...]}]`, the candidates for the device page's Replace. `GET /device/replace?oldId=&newId=` returned `{"success":false,"message":"Failed to replace device"}` for two virtual switches the options call offered; it likely works only for radio devices. Its semantics are unverified *(2.5.2.129)*.
- `configure/json` returns an enum-multiple setting as a List (`["a","c"]`) and `statusJson` returns it as a JSON string. Echoing the List with Groovy `toString()` stores the string `"[a, c]"` *(verified 2.5.2.129)*.

## Run a device command (no Maker API)

- `POST /device/runmethod` — invoke any command on a device via the admin-UI channel, without a Maker API token. JSON body: `{"id": <deviceId>, "method": "<commandName>", "args": [{"type": "<paramType>", "value": <v>}, ...]}` (use `"args": []` for no-arg commands). Response: `{"success":true,"message":null}`
- Command processing is **async** — the POST returns before the command runs. Poll `GET /device/fullJson/{id}` (current attribute values are under `device.currentStates[].value`) until the expected state appears.
- A device's invokable commands are listed in `GET /device/fullJson/{id}` under `device…commands[]` (each has `name`, `parameters`).
- This is the **web-UI invocation channel**; its script-instance/binding lifecycle could differ from app- or Maker-API-driven calls. For production-representative behavior — and any test that cares about cross-invocation state — prefer the Maker API route `GET /apps/api/{appId}/devices/{deviceId}/{command}?access_token={token}`. (The two channels matched for command dispatch and binding persistence on firmware 2.5.0.143 — see [`hubitat-platform-notes.md`](hubitat-platform-notes.md).)

## App OAuth endpoint authentication (`Bearer` header)

Every app OAuth endpoint (`/apps/api/{appId}/{path}` — Maker API and any custom Groovy app with a `mappings` block) accepts the token as an **`Authorization: Bearer {TOKEN}` header** instead of the `?access_token={TOKEN}` query parameter. Prefer the header: a query-string token lands in hub and proxy access logs, DNS/filtering logs, browser history, and outbound `Referer` headers, and on the cloud relay it crosses the public internet in the URL.

This is enforced by the platform's OAuth filter, not by app code — a rejected request never reaches the mapped handler and returns `401` with an XML body, `<oauth><error_description>null</error_description><error>invalid_token</error></oauth>`, rather than the JSON `AppException` shape an app-level error produces.

Verified 2026-09-20 on firmware 2.5.2.113 (C-8 Pro, hub security off) and 2.5.1.183 (C-7, hub security on), against both a Maker API instance and a custom app's own endpoint:

| Request | Result |
|---|---|
| `?access_token={TOKEN}` | 200 |
| `Authorization: Bearer {TOKEN}`, no query param | 200 |
| `Authorization: Bearer {WRONG}` | 401 |
| no auth | 401 |

Scope of the behavior:

- Applies to **POST as well as GET** — a POST carrying only the header to a GET-only path returns `405 Method Not Allowed` (auth passed, routing failed), while the same POST with no auth returns `401`.
- Applies to the **cloud relay** (`https://cloud.hubitat.com/api/{hubUID}/apps/{appId}/{path}`) identically. This is where the header matters most.
- Independent of hub security — app OAuth endpoints never consult the hub login session.

Three traps:

- **The scheme is case-sensitive before 2.5.2.120.** On earlier firmware only exactly `Bearer` authenticates; `bearer` and `BEARER` both return 401. This is off-spec (RFC 6750 §2.1 defines the scheme as case-insensitive), so an HTTP client or gateway that normalizes the scheme's case will break the request. From 2.5.2.120 the match is case-insensitive, locally and through the cloud relay. Send exactly `Bearer` while any target hub runs older firmware.
- **No other form works.** A bare `Authorization: {TOKEN}` with no scheme, and `X-Auth-Token: {TOKEN}`, both return 401.
- **The query parameter wins when present.** Wrong query param + correct header → 401; correct query param + wrong header → 200. The header is consulted only when `access_token` is absent from the URL, so a header cannot override a stale token left in a URL.

Undocumented by Hubitat — nothing promises this across firmware versions. Code that relies on it should keep the query-param form as a fallback.

## Maker API specifics

- Device notes are read-only: `GET /devices/{id}` carries a `notes` key (added in 2.5.0), and nothing writes them. The documented device writers are `setLabel`, `setDriver` and `deleteDevice`. `/devices/{id}/setNotes?notes=…` is treated as a device command and returns 404 *(verified 2.5.2.129)*.

### Token discovery

- **Preferred: `GET /installedapp/statusJson/{id}` → `appState[]`, the entry named `accessToken`.** A structured field, no HTML parsing. This is not Maker-API-specific — it works for any app instance, because `createAccessToken()` stores the token in `state.accessToken`. Verified 2026-09-20 on firmware 2.5.2.113 against a Maker API instance and a custom app; the token read this way authenticates the app's endpoints.
- The entry is **absent** for an app that never called `createAccessToken()` (no OAuth endpoints), so treat a missing `accessToken` as "this app has no endpoints", not as a read failure.
- Fallback: the token is also embedded in HTML links inside `configPage.sections[].body[]` paragraphs — look for `description` fields containing `access_token=`. Example: `<a href='http://{hub_ip}/apps/api/{id}/devices?access_token={TOKEN}'>`. Use this only if `appState` is unavailable; it breaks whenever the app's config page markup changes.
- The token is **not** in `settings`.

### Device commands with arguments

- `GET /apps/api/{appId}/devices/{deviceId}/{command}/{args}?access_token={TOKEN}`. Several arguments go comma-joined in one path segment (`/holdProfile/Sleep,next`); separate segments (`/holdProfile/Sleep/next`) answer 404 *(observed 2.5.2.129)*.

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

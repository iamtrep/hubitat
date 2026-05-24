<!--
Copyright (c) 2025-2026 PJ
SPDX-License-Identifier: MIT
-->

# `/device/fullJson/{id}` — Field Reference

Reference for the per-device payload returned by Hubitat's `GET /device/fullJson/{id}` endpoint, captured for the Device Usage Audit feature in HubDiagnostics.

The bulk `/hub2/devicesList` endpoint is fast and returns most identity fields, but **does not include subscription, dashboard, or driver-configuration data**. Those require one `fullJson` call per device. The Hubitat platform caps concurrent async HTTP calls at 8 per app, so a 350-device hub crawls in roughly 30–60 seconds — making this suitable for a one-time scan / report generation, not interactive views.

## Top-level keys observed

```
appsUsing, appsUsingCount, appsUsingForDialog, appsUsingForDialogMore,
dashboards, dashboardTypes, hasDashboards,
parentApp, hasChildren, childDevices,
scheduledJobs,
settings, inputValues,
commands, commandRetrySelectionEnabled,
deviceState, device,
controllerType, tags,
showInstructionSearchLink, extraBreadcrumb, virtualFirst,
homeKitEnabled, homeKitSelectionEnabled,
hubMeshRefreshEnabled
```

The `device` object contains a separate set of keys (driver identity, timestamps, protocol IDs, mesh flags, etc.) — see Section C below.

---

## Section A — Cross-reference core (in scope: v1)

The primary content of the audit report.

| Field | Purpose |
|---|---|
| `appsUsing[]` (id, label, name, disabled, trueLabel) | Device → apps subscription list |
| `appsUsingCount` | Subscriber total; powers criticality ranking and "unreferenced" detection |
| `dashboards[]` (id, name, selected) | Device → dashboards (separate from apps; novel surface) |
| `parentApp` (or null) | Distinguishes integration ownership from automation use |

**"Unreferenced" device** = `appsUsingCount == 0` AND `dashboards` empty AND `parentApp == null`.

---

## Section B — Diagnostic flags (in scope: v1)

Operational signals worth surfacing alongside the cross-reference.

| Field | Why it matters |
|---|---|
| `device.orphan` (also `data.isOrphan` in bulk) | Mesh/radio orphan state — a hardware diagnostic, NOT a subscription condition |
| `device.disabled` | Disabled but still subscribed-to is suspicious |
| `device.linkedAndDisabled` | Linked-mesh edge case |
| `scheduledJobs[]` (handler, schedule, nextRunTime, prevRunTime, status) | Stuck job detection — `nextRunTime` in the past |
| `device.spammyThreshold` | Non-default values flag manual tuning |
| `device.maxStates` / `device.maxEvents` | Non-default buffer sizes flag manual tuning |

---

## Section C — Identity & driver attribution (in scope: v1)

Per-device driver and metadata enrichment beyond what `devicesList` provides.

| Field | Purpose |
|---|---|
| `device.deviceTypeName` / `deviceTypeNamespace` / `deviceTypeId` / `deviceTypeReadableType` | Driver identity |
| `device.driverType` (`usr` vs system) | Confirms user-driver-installed vs platform |
| `device.deviceTypeSingleThreaded` | Threading model — performance hint |
| `device.createTime` / `device.updateTime` | Device record age and last config change |
| `device.parentDeviceId` / `childDevices` | Parent/child hierarchy (richer than bulk) |
| `device.notes` / `device.tags` | User-curated metadata worth preserving in the report |

---

## Section D — Configuration backup (mostly deferred)

Useful for a "device configuration snapshot/restore" use case. The full preference set is not in v1 because it can balloon (a complex driver may have 20+ preferences) and isn't core to the usage audit.

**Exception (promoted v5.37.0):** the make/model/firmware subset of `device.dataJson` *is* extracted to
power the **Device Inventory** section of the report (`extractAuditFields` parses `dataJson` and pulls
`manufacturer`, `model`, and firmware via the first non-blank of `softwareBuild` / `application` /
`firmwareVersion` / `softwareVersion`). Keys vary by protocol and driver, so reads are defensive:
Zigbee firmware is `softwareBuild`/`application`, Z-Wave is `firmwareVersion`, Matter is
`softwareVersion` (often empty → blank). Z-Wave manufacturer arrives as a numeric id (e.g. `634` =
ZOOZ 0x027A), not a friendly name — shown verbatim. **Model (v5.50.0):** Z-Wave has no `model` key, so
`model` reads from `deviceModel` (e.g. `ZEN55`), falling back to the unique `deviceType:deviceId` pair
when `deviceModel` is absent. Without this, distinct products sharing one numeric `manufacturer` id (every
ZOOZ device is `634`) collapse into a single group and false-flag as firmware drift. The top-level `controllerType` code
(`ZGB`→Zigbee, `ZWV`→Z-Wave, `MAT`→Matter, `LNK`→Linked, `LAN`, `BLE`/`BTH`→Bluetooth; unknown codes
pass through) supplies the inventory's Protocol column. The broader settings/config backup below
stays deferred.

**Firmware identification (added v5.48.0; `firmwareSource` added v5.49.0):** three firmware fields are now present in the audit record:

- `firmware` — the human-readable display version, drawn from the first non-blank of `softwareBuild` > `application` > `firmwareVersion` > `softwareVersion`. Representation varies by device: e.g. `1.01.01` from `softwareBuild`, `10013065` from `application` on an otherwise identical unit. Use for display only.
- `firmwareSource` — the data-value key that `firmware` was actually read from (one of `softwareBuild`, `application`, `firmwareVersion`, `softwareVersion`; null when all are blank). Two devices with the same `firmware` string but different `firmwareSource` values are not necessarily on the same build. Compare `firmware` only among devices sharing the same `firmwareSource` — or use `firmwareOta` for cross-source comparison.
- `firmwareOta` — the canonical, comparable identifier for firmware drift detection. Extracted from `firmwareMT`, the Zigbee OTA image descriptor (`mfrCode-imageType-fileVersion`; e.g. `1233-D3A6-10013065`). `firmwareOta` is the last `-` segment (`fileVersion`; e.g. `10013065`). Null when `firmwareMT` is absent (Z-Wave, Matter, virtual, cloud). Use this field for cross-device firmware-version comparison — `firmware` can differ in representation across identical hardware even when the OTA image is the same.
- `firmwareTargets` — map of Z-Wave firmware target index → version string, present **only when a device exposes more than one target** (e.g. a lock with primary + secondary chip: `{'0': '1.05', '1': '2.01'}`). Populated from `firmwareVersion` (→ index `'0'`) and `firmware1Version`, `firmware2Version`, … (→ indices `'1'`, `'2'`, …) in `dataJson`. Null for single-firmware devices and all non-Z-Wave protocols. `firmware` / `firmwareVersion` remain the primary firmware reference; `firmwareTargets` is the additive multi-chip record.

| Field | Notes |
|---|---|
| `settings[]` | Driver preferences with `type`, `defaultValue`, `description`, `range`, etc. |
| `inputValues[]` | Current preference values (the "what's set") |
| `device.data` / `device.dataJson` | Pairing-time data (Zigbee clusters, Z-Wave model, manufacturer info) — useful for re-pairing. **Make/model/firmware subset extracted as of v5.37.0** (Device Inventory); `firmwareOta` added v5.48.0; `firmwareSource` added v5.49.0; `firmwareTargets` added v5.51.0 (see above); rest deferred. |

---

## Section E — Reference data (deferred)

Per-device interface and protocol detail. Not in v1.

| Field | Notes |
|---|---|
| `capabilities[]` | Explicit capability list (only inferred from device type today) |
| `commands[]` (name, capability, arguments, parameters, version) | Available commands per device |
| `device.displayAttributes` | Which attributes are pinned to UI |
| `device.ZWave` / `device.zigbee` / `device.network` / `device.bluetooth` / `device.matter` | Protocol-specific config blocks |
| `device.meshEnabled` / `device.meshFullSync` | Hub Mesh participation per device |

---

## Section F — Display verbatim only (deferred)

Useful for a per-device deep-detail view but bloats the audit report. Not portable for cross-device aggregation.

| Field | Caveat |
|---|---|
| `deviceState` | Driver-specific Groovy `state` map. Keys vary entirely by driver (no platform contract). Display verbatim only — never aggregate or interpret across drivers. |
| `currentStates` | Live attribute values at scan time. Useful for backup, noisy in an audit report. |

---

## Section G — Excluded (UI metadata, derivable, or noise)

UI-only flags, paginated dialog variants of data already captured, or values that are derivable / constant across the scan.

`showInstructionSearchLink`, `extraBreadcrumb`, `virtualFirst`, `dashboardTypes`, `appsUsingForDialog`, `appsUsingForDialogMore`, `commandRetrySelectionEnabled`, `homeKitSelectionEnabled`, `meshSelectionEnabled`, `homeKitEnabled`, `hubMeshRefreshEnabled`, `hasChildren`, `hasDashboards`, `device.defaultIcon`, `device.displayAsChild`, `device.doNotUseInSummary`, `device.roomAssigned`, `device.showOnHome`, `device.remoteDeviceUrl`, `device.groupId`, `device.roomId`, `device.locationId`, `device.hubId`, `device.lanId`, `device.hubName`.

---

## Scope summary

| Section | v1 | Notes |
|---|---|---|
| A — Cross-reference core | ✓ | The point of the report |
| B — Diagnostic flags | ✓ | Mesh orphans, stuck jobs, tuning flags |
| C — Identity & driver attribution | ✓ | Per-device enrichment |
| D — Configuration backup | partial | Make/model/firmware extracted (Device Inventory, v5.37.0); `firmwareOta` added v5.48.0; `firmwareSource` added v5.49.0; `firmwareTargets` added v5.51.0; rest deferred to a future "device config snapshot" |
| E — Reference data | deferred | Capability/command/protocol detail |
| F — Verbatim-only fields | deferred | `deviceState`, `currentStates` |
| G — Excluded | — | UI flags, derivable, constants |

---

## Integration detection model (v5.56.0)

Device integration and connection type are derived by `classifyDevice` / `enrichDevices` using an algorithm-primary model. The guiding principle: **derive everything possible from the hub's own signals; hold only the exceptions the derivation can't infer.**

1. **Radio / protocol flags** (`isZigbee`, `isZwave`, `isMatter`, `isBluetooth`) → `paired` + protocol name. `isLinked` → `hubmesh`. `isVirtual` (or Virtual driver name heuristic) → `virtual`.
2. **Parent app present** (bulk `parentAppId` → `appLookup`, or `parentApp.appType.name` from `fullJson`):
   - **Integration name**: always `cleanIntegrationName(appType)` — strips trailing noise tokens (`Device Manager`, `Device Service`, `Integration`, `Service`, `Manager`, etc.), e.g. `"YoLink Device Service"` → `"YoLink"`. There are **no name overrides**.
   - **Built-in vs community**: from the hub's own `appInfo.user` flag (`user == true` ⇒ community).
   - **Connection type**: `device.isNetwork == true` ⇒ `lan_direct`; otherwise `cloud`.
   - **`INTEGRATION_OVERRIDES`** (built-in table) holds **connection-type exceptions only** — the handful the `isNetwork` signal mis-derives. It is *not* a roster of integrations:

     | Key(s) | conn | Why the derivation is wrong without it |
     |---|---|---|
     | `philips hue` / `hue bridge` | `lan_bridge` | the Hue Bridge fronts the bulbs; children report `isNetwork=true` ⇒ would derive `lan_direct` |
     | `lutron` | `lan_bridge` | behind the Lutron bridge; `isNetwork=true` ⇒ would derive `lan_direct` |
     | `bond` | `lan_bridge` | the Bond Bridge fronts the devices; `isNetwork=true` ⇒ would derive `lan_direct` |
     | `airplay` | `lan_direct` | MAC-format DNI with `isNetwork=false` ⇒ would derive `cloud` |

     Everything else — Kasa, Sonos, Ecobee, Blink, FGLair, Govee, LIFX, WiZ, HomeKit, Google Home, Amazon Echo, the Mobile App, … — needs **no entry**: name comes from `cleanIntegrationName`, conn from `isNetwork`, built-in/community from the `user` flag. (HomeKit Controller devices are `isNetwork=true` ⇒ `lan_direct`, which is correct, so the old `homekit→paired` guess was removed.)
   - **User exceptions** go in the `hub_diagnostics_integration_overrides.json` File Manager config — the escape hatch for a connection type *your* hub can't infer (a template ships with the app). The loader merges the file over the built-in table with user entries first.
3. **`isNetwork` only, no parent app** → `lan_direct`, `"LAN Device"`.
4. **Fallback** → `other`, `"Other"`.

This model auto-scales to any integration without table changes; the override map grows only when a genuinely new connection-type exception is discovered.

### Integration overrides config file

A documented template (`apps/HubDiagnostics/integration_overrides.json`) ships with the app. It contains a `_README` and a single commented-out example — **not** a list of integrations, because the algorithm classifies almost everything correctly on its own. Add an entry only to correct a connection type your hub can't infer. Upload it to File Manager as `hub_diagnostics_integration_overrides.json` to activate it.

- **File**: `hub_diagnostics_integration_overrides.json`
- **Location**: Hubitat File Manager
- **Format**: JSON object mapping lowercase keyword strings to `{conn?, name?}` entries:
  ```json
  {
    "_README": "…how-to text…",
    "my lan bridge": { "conn": "lan_bridge", "name": "My Bridge" }
  }
  ```
- **Keys**: lowercase substrings matched against the device's parent-app name (same substring logic as the built-in overrides). **Any key starting with `_` is ignored** — used for documentation (`_README`) and commented-out examples.
- **`conn`** (optional): one of `paired`, `lan_direct`, `lan_bridge`, `cloud`, `virtual`, `hubmesh`, `other`. Unknown values are silently ignored.
- **`name`** (optional): display name shown in reports — rarely needed, since `cleanIntegrationName` usually produces the right name. Either field may be omitted.
- **Merge**: file entries are placed first (they win on key collision and on substring-match precedence), followed by built-in entries not overridden.
- **Apply**: save the Hub Diagnostics settings page after uploading the file — this invalidates the in-memory cache and triggers a reload on next use.
- **Error handling**: a missing file or malformed JSON logs a warning and falls back to the built-in defaults; the app never throws.

---

## Audit result — top-level fields (v5.52.0)

The object stored in `lastAuditResult` (and returned by the audit API endpoint) carries these result-level fields alongside the per-device map:

| Field | Source | Notes |
|---|---|---|
| `hubName` | `getHubInfo().name` | Hub's user-assigned name |
| `hubModel` | `getHubInfo().hardware` | Hardware model string (e.g. `C-8 Pro`) — added v5.52.0 |
| `hubFirmware` | `getHubInfo().firmware` | Platform firmware version string — added v5.52.0 |
| `generatedAt` | Server time | UTC timestamp of scan completion |
| `failed[]` | Scan errors | List of `{id, reason}` for devices that could not be fetched |

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
`softwareVersion` (often empty → blank). Z-Wave manufacturer/model arrive as numeric/hex IDs (e.g.
`634`, `0x0312`), not friendly names — shown verbatim. The top-level `controllerType` code
(`ZGB`→Zigbee, `ZWV`→Z-Wave, `MAT`→Matter, `LNK`→Linked, `LAN`, `BLE`/`BTH`→Bluetooth; unknown codes
pass through) supplies the inventory's Protocol column. The broader settings/config backup below
stays deferred.

**Firmware identification (added v5.48.0):** two firmware fields are now present in the audit record:

- `firmware` — the human-readable display version, drawn from the first non-blank of `softwareBuild` > `application` > `firmwareVersion` > `softwareVersion`. Representation varies by device: e.g. `1.01.01` from `softwareBuild`, `10013065` from `application` on an otherwise identical unit. Use for display only.
- `firmwareOta` — the canonical, comparable identifier for firmware drift detection. Extracted from `firmwareMT`, the Zigbee OTA image descriptor (`mfrCode-imageType-fileVersion`; e.g. `1233-D3A6-10013065`). `firmwareOta` is the last `-` segment (`fileVersion`; e.g. `10013065`). Null when `firmwareMT` is absent (Z-Wave, Matter, virtual, cloud). Use this field for cross-device firmware-version comparison — `firmware` can differ in representation across identical hardware even when the OTA image is the same.

| Field | Notes |
|---|---|
| `settings[]` | Driver preferences with `type`, `defaultValue`, `description`, `range`, etc. |
| `inputValues[]` | Current preference values (the "what's set") |
| `device.data` / `device.dataJson` | Pairing-time data (Zigbee clusters, Z-Wave model, manufacturer info) — useful for re-pairing. **Make/model/firmware subset extracted as of v5.37.0** (Device Inventory); `firmwareOta` added v5.48.0 (see above); rest deferred. |

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
| D — Configuration backup | partial | Make/model/firmware extracted (Device Inventory, v5.37.0); `firmwareOta` added v5.48.0; rest deferred to a future "device config snapshot" |
| E — Reference data | deferred | Capability/command/protocol detail |
| F — Verbatim-only fields | deferred | `deviceState`, `currentStates` |
| G — Excluded | — | UI flags, derivable, constants |

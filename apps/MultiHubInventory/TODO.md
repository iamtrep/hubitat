# Multi-Hub Inventory — TODO

Backlog for the app. v0.1.0 is functional (deployed + verified on maison-pro); this is the
remaining work.

## High priority

### Device classification parity with Hub Diagnostics
The register/summary currently display the audit record's raw `protocol` field, which is just
`controllerTypeLabel(controllerType)` — a thin mapper. Two consequences seen in live data
(165 devices on maison-pro):

- **~130 devices show as "Unknown"** because `controllerType` is blank for virtual / cloud /
  LAN / integration-owned devices. Hub Diagnostics' own code notes the protocol field is
  "unreliable (null on many hubs)".
- **Unmapped controller codes leak** (e.g. `HKC` for HomeKit), because `controllerTypeLabel`
  hits `default: return ct` for anything outside ZGB/ZWV/MAT/LNK/LAN/BLE/BTH.

Hub Diagnostics classifies properly via `classifyDevice`: authoritative `isZigbee`/`isZwave`/
`isMatter`/`isBluetooth`/`isLinked`/`isVirtual`/`isNetwork` flags, a virtual-driver heuristic,
and a parent-app → `INTEGRATION_TABLE` lookup (Hue, Shelly, Kasa, WLED, Sonos, …) yielding both
an `integration` name and a `connectionType`; `enrichDevices` resolves stragglers from `fullJson`
(`parentApp` + `controllerType`).

**Fix (single-source it):** add `integration` and `connectionType` to each audit record in
Hub Diagnostics' `extractAuditFields` (the `fullJson` path has `parentApp` + `controllerType`, so
the `enrichDevices`-style lookup is available there). Then this app renders `integration` instead
of the raw `protocol`. This is a Hub Diagnostics-side enrichment — classifying devices is its own
job; the audit contract just isn't exposing what it already computes. Avoids duplicating the
integration table in the SPA.

## Medium priority

- **Cloud-relay peers: large `audit/data` 504s.** Cloud URLs now parse and the courier reaches
  them server-side — `op=start`/`op=status` (small responses) cross the relay fine. But fetching
  the full `audit/data` for a sizeable hub **times out: the Hubitat cloud relay returns HTTP 504
  after ~10s** (verified 2026-05-24: chalet, 190 devices, scan completes `done` but `audit/data`
  → 504, ~10s, even on a direct curl). This is a Hubitat cloud-relay gateway-timeout/payload limit,
  not our code. Fix options: (a) add a **slim/projection mode** to HubDiagnostics `/api/audit/data`
  returning only the ~11 fields MHI uses (id, label, deviceTypeName, manufacturer, model, firmware,
  firmwareOta, protocol, lastActivityTimeMs, appsUsingCount, flag bits) — a much smaller payload
  that should deliver under the relay timeout (also speeds up all peers); (b) reach remote hubs over
  LAN/VPN instead of cloud (no relay timeout).
- **Self-peer UX.** When the serving hub is its own peer it must use the loopback URL
  (`http://127.0.0.1:8080/...`) because a hub can't HTTP its own external IP. Auto-detect the
  host hub and rewrite to loopback, instead of requiring the user to know this.
- **Reachability probe.** `/api/peers` always returns `reachable: null`; wire up the optional
  per-peer `audit/status` probe on save so the summary can show ok / auth-failed / unreachable.
- **Parallel rescan + per-hub progress bars.** Rescan currently scans hubs sequentially; fine
  for 2 hubs, but parallelize (bounded) with progress as the fleet grows.
- **Compare preferences across identical devices.** For devices of the same hardware
  (manufacturer+model) and the same driver type, show their settings/preferences side by side to
  spot misconfiguration. Needs device preferences in the audit record — `extractAuditFields`
  currently carries only `spammyThreshold`/`maxStates`/`maxEvents`, not full preferences.
- **Flag same hardware, different driver.** Group by manufacturer+model; flag groups where the
  driver (`deviceTypeName`/`deviceTypeId`) differs across devices — surfaces devices that arguably
  should share a driver. Computable from current audit fields (adjacent to firmware-drift).

## Low priority

- **Row-click detail drawer** in the register (full audit record: apps-using, scheduled jobs,
  parent/children, namespace/driver).
- **"Flagged only" register toggle** to show just attention-flagged devices.
- **CSV formula-injection hardening** (prefix `=`/`+`/`-`/`@` cells); values are quoted today.

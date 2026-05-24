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

- **Remote hubs via cloud URLs.** v1 is same-LAN only; the courier could call peers' cloud
  endpoints server-side to reach chalet/andree.
- **Self-peer UX.** When the serving hub is its own peer it must use the loopback URL
  (`http://127.0.0.1:8080/...`) because a hub can't HTTP its own external IP. Auto-detect the
  host hub and rewrite to loopback, instead of requiring the user to know this.
- **Reachability probe.** `/api/peers` always returns `reachable: null`; wire up the optional
  per-peer `audit/status` probe on save so the summary can show ok / auth-failed / unreachable.
- **Parallel rescan + per-hub progress bars.** Rescan currently scans hubs sequentially; fine
  for 2 hubs, but parallelize (bounded) with progress as the fleet grows.

## Low priority

- **Row-click detail drawer** in the register (full audit record: apps-using, scheduled jobs,
  parent/children, namespace/driver).
- **"Flagged only" register toggle** to show just attention-flagged devices.
- **CSV formula-injection hardening** (prefix `=`/`+`/`-`/`@` cells); values are quoted today.

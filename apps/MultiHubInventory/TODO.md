# Multi-Hub Inventory — TODO

Backlog for the app. v0.1.0 is functional (deployed + verified on maison-pro); this is the
remaining work.

## Done

### Device classification parity with Hub Diagnostics ✅ (MHI v0.3.0 / HubDiag v5.59.0)
The register/summary previously displayed the audit record's raw `protocol`
(`controllerTypeLabel(controllerType)`), which left ~130/165 devices as "Unknown" (blank
`controllerType` on virtual/cloud/LAN/integration devices) and leaked unmapped codes (`HKC`).

Single-sourced in Hub Diagnostics. Rather than the originally-sketched fullJson-only
classification in `extractAuditFields` (which would have reproduced the "Unknown" problem, since
fullJson lacks the authoritative `isZigbee`/`isZwave`/`isNetwork` bulk-list flags), `finalizeAudit`
now reuses `analyzeDevices()` — the full `classifyDevice` + `enrichDevices` passes that already feed
the Dashboard — and joins the per-device `connectionType` + `integration` onto each audit record by
id. This guarantees the audit classification exactly matches the SPA's, with no duplicated table.
MHI renders the `integration` column + a `connectionType` (CONN_DISPLAY) column, and the summary's
"By integration" card, all falling back to `protocol` for old/unclassified peers.

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
- **Multi-target Z-Wave firmware — drift comparison.** Extraction + display shipped: HubDiagnostics
  v5.51.0 emits `firmwareTargets` (e.g. a lock's `{0:'1.05', 1:'2.01'}`), and the SPA shows them where
  firmware is displayed. **Remaining:** factor secondary targets into the drift *comparison* —
  `firmwareDrift` still compares only the primary firmware basis, so identical devices that match on
  the primary but differ on a secondary chip aren't flagged.

  *(Done — Z-Wave manufacturer id→name mapping shipped SPA-side: 786-entry zwave-js table in the MHI
  SPA, `displayMfr()` maps `634`→Zooz, `541`→Kaadas, etc. Kept out of the Groovy per the "derivable
  lookups belong in the SPA" architecture principle.)*
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

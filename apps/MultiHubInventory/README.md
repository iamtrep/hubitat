<!--
Copyright (c) 2026 PJ
SPDX-License-Identifier: MIT
-->

# Multi-Hub Inventory

A standalone Hubitat app that aggregates each hub's existing Hub Diagnostics `/api/audit/*` data
into one read-only, cross-hub view. It does **not** modify Hub Diagnostics in any way.

## Requirements

Multi-Hub Inventory has **no data of its own** — it only reads and merges the audit data that
**Hub Diagnostics already produces** on each hub. Before a hub can appear in any view, it must
already be running Hub Diagnostics:

- **Hub Diagnostics must be installed *and* instantiated on every hub you want to include** —
  including the host hub that serves this app. Importing the Hub Diagnostics app *code* is not
  enough; each hub needs a configured Hub Diagnostics *instance* (Apps → Add User App → Hub
  Diagnostics) that has finished its setup.
- **That instance must have OAuth enabled with an access token**, so it exposes a local
  `/apps/api/<id>/api/` endpoint. (Hub Diagnostics enables its own OAuth on install, so this is
  normally automatic.)
- **The endpoint must be reachable on the LAN** from the hub that serves Multi-Hub Inventory.

A hub without a configured Hub Diagnostics instance cannot be added: there is no audit API for the
proxy to call, so its peer probe reports it unreachable and it contributes nothing to the merged
views. Install and configure Hub Diagnostics on each target hub *first*, then add it here as
described under **Adding a hub** below.

## What it does

Multi-Hub Inventory has three tabs across all hubs in your fleet:

- **Summary** — total device count, per-hub counts, connection/integration/manufacturer
  breakdowns, and an **Attention** card rolling up stale, orphaned, disabled, and
  unreferenced devices across hubs.
- **Device Register** — every device across all hubs in one sortable, filterable table
  with CSV export. Hub Mesh mirror devices are excluded and counted once on their home hub.
- **Device Drift** — groups of identical devices flagged when they run mixed firmware
  (or, in a separate section, mixed driver types) across hubs, sorted most-drifted first.

## How it works

A minimal "courier" Groovy app installed on one hub serves the single-page application and
exposes a hardened same-origin proxy (`/api/peer?hub=<n>&op=start|status|data`). This
server-side proxying is required because browsers cannot fetch another hub's API directly —
Hubitat's local OAuth API sends no CORS headers, so cross-origin calls are blocked. The SPA
loads the peer list from `/api/peers`, orchestrates audit collection through the proxy one hub
at a time, then merges and correlates all the data client-side. The serving hub registers one
of its own peer entries pointing at its co-located Hub Diagnostics instance, so all hubs
(including the host) are treated identically.

## Install / deploy

1. **Import/push the Groovy app** to your primary hub (the hub that will serve the SPA):
   `apps/MultiHubInventory/MultiHubInventory.groovy` (the Import URL is set, so Hubitat can pull
   updates).

2. **OAuth enables itself.** On first install the app auto-enables OAuth (via the hub's
   loopback API) and creates its own access token — no manual code-editor toggle needed.

3. **The SPA self-syncs.** On install and on every save, the app downloads the matching
   `multi_hub_inventory_ui.html` from GitHub into File Manager — you don't normally upload it by
   hand. The download is version-gated: only HTML whose `UI_VERSION` matches the app's `APP_VERSION`
   is stored, and the config page warns on a mismatch. If you're running an unpublished local build
   (or the hub is offline), upload the HTML to File Manager manually under that exact filename.

4. **Install the app** (Apps → Add User App → Multi-Hub Inventory). Once installed, the
   settings page shows a dashboard link — open it to verify the UI loads.

When a newer release is published on GitHub, the app appends a green **update available** badge to
its label on the Apps list, so you can spot an available update without opening the app. The badge
is reconciled daily (and whenever the app re-initializes) and clears itself once the installed code
catches up.

## Adding a hub

A hub can only be added once it has a configured Hub Diagnostics instance (see **Requirements**
above). In the app's settings page, click **"Add hub"**. Paste that hub's Hub Diagnostics **API
base URL with its access token**, for example:

```
http://192.168.0.10/apps/api/247/api/?access_token=<token>
```

This is the `/api/` path — **not** the `ui.html` dashboard link. You can find it on the Hub
Diagnostics settings page under "Open Hub Diagnostics Dashboard."

Add an entry for **this hub itself** too, pointing at its own Hub Diagnostics instance — but
for the host hub use the **loopback** address, because a hub cannot make an HTTP call to its
own external IP (the call fails with "peer call failed"):

```
http://127.0.0.1:8080/apps/api/247/api/?access_token=<token>
```

Peer hubs use their normal LAN IP. Tokens are stored only in the app's settings; they never
leave the hub and are never returned by `/api/peers`.

## v1 limitations

The following are known constraints in v1:

- **Same-LAN hubs only.** Remote hubs accessible via cloud URLs are not yet supported.
- **No battery or live-attribute data.** The audit endpoint exposes inventory and lifecycle
  fields only; current attribute values are not included.
- **Z-Wave models still appear as raw hex IDs.** Manufacturers are looked up against a
  bundled Z-Wave maker-id table and shown by name; the model column has no equivalent
  mapping yet, so firmware-drift labels remain less readable than Zigbee entries.
- **Hub Mesh mirrors are filtered, not deduped in a separate view.** Devices with
  `protocol == 'Linked'` are excluded from all counts and the register; they do not appear in
  a dedicated mesh-link view.

The following are planned but not in v1:

- Row-click device detail drawer (full audit record with apps-using, scheduled jobs,
  parent/children).
- "Flagged only" register toggle to show just attention-flagged devices.
- Parallel rescan with per-hub progress bars (v1 scans hubs sequentially).

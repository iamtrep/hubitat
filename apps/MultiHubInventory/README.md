# Multi-Hub Inventory

A standalone Hubitat app that aggregates each hub's existing Hub Diagnostics `/api/audit/*` data
into one read-only, cross-hub view. It does **not** modify Hub Diagnostics in any way.

## What it does

Multi-Hub Inventory provides four views across all hubs in your fleet:

- **Fleet Summary** — total device count, per-hub counts, protocol and manufacturer breakdowns,
  and a cross-hub attention badge (stale / orphaned / disabled / unreferenced).
- **Unified Device Register** — every device across all hubs in one sortable, filterable table
  with CSV export. Hub Mesh mirror devices are excluded and counted once on their home hub.
- **Firmware Drift** — groups of identical (manufacturer + model) devices are flagged when they
  run mixed firmware across hubs, sorted most-drifted first.
- **Maintenance / Attention** — cross-hub roll-up of stale, orphaned, disabled, and
  unreferenced devices.

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

Deployment is a two-part process — **both files must ship**. Pushing only the Groovy leaves
the UI absent.

1. **Push the Groovy app** to your primary hub (the hub that will serve the SPA):
   `apps/MultiHubInventory/MultiHubInventory.groovy`

2. **OAuth enables itself.** On first install the app auto-enables OAuth (via the hub's
   loopback API) and creates its own access token — no manual code-editor toggle needed.

3. **Upload the SPA** to that hub's File Manager:
   `apps/MultiHubInventory/multi_hub_inventory_ui.html`
   The Groovy serves it via `downloadHubFile` — it must be in File Manager under that exact
   filename.

4. **Install the app** (Apps → Add User App → Multi-Hub Inventory). Once installed, the
   settings page shows a dashboard link — open it to verify the UI loads.

## Adding a hub

In the app's settings page, click **"Add hub"**. Paste that hub's Hub Diagnostics **API base
URL with its access token**, for example:

```
http://192.168.1.86/apps/api/247/api/?access_token=4c0edefe-55ed-4001-bd44-3abd33734536
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
- **Z-Wave manufacturer and model appear as numeric / hex IDs.** Firmware-drift grouping still
  works, but the labels are less readable than Zigbee entries.
- **Hub Mesh mirrors are filtered, not deduped in a separate view.** Devices with
  `protocol == 'Linked'` are excluded from all counts and the register; they do not appear in
  a dedicated mesh-link view.

The following are planned but not in v1:

- Row-click device detail drawer (full audit record with apps-using, scheduled jobs,
  parent/children).
- "Flagged only" register toggle to show just attention-flagged devices.
- Parallel rescan with per-hub progress bars (v1 scans hubs sequentially).

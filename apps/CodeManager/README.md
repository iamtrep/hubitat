<!--
Copyright (c) 2025-2026 PJ
SPDX-License-Identifier: MIT
-->

# Code Manager

Inventory, update detection and (from 0.2) safe updates for every app, driver, library and bundle on a Hubitat hub — including code installed by `importUrl` or with no provenance at all. Keeps the Hubitat Package Manager ecosystem (master manifest, author repositories, package manifests) and, from 0.3, replaces the HPM client. Design: [`docs/DESIGN.md`](docs/DESIGN.md); measured hub limits and endpoints: [`docs/FEASIBILITY.md`](docs/FEASIBILITY.md); HPM parity: [`docs/HPM-PARITY.md`](docs/HPM-PARITY.md).

**0.1 is read-only.** It never writes app, driver, library or bundle code. The only files it creates are its own `cm_*` entries in File Manager.

## Install

1. Add `CodeManager.groovy` under **Apps Code** (Import from the raw GitHub URL or paste), save, then **Add User App → Code Manager**. OAuth is enabled automatically; click **Done** once.
2. Re-open the app and follow **Open Code Manager**. The UI (`code_manager_ui.html`) is downloaded from GitHub on first run and kept in lockstep with the app version by a daily sync.

## What 0.1 shows

- **Inventory** — every user app, driver, library and bundle, with origin (`hpm`, `bundle`, `url`, `local`), installed version (declared by the HPM manifest or parsed from source; the parse idiom is shown when it is not the standard `CODE_VERSION` field), upstream version, freshness status, usage counts (instances for apps, devices for drivers, consumer types for libraries), and the upstream location.
- **Packages** — the package view: one row per HPM package, bundle, `importUrl` file, or local orphan, with its manifest document name in File Manager.
- **HPM** — the transition panel: whether HPM is installed, its instance and `autoUpdateMode`, how many packages were imported from its registry, and how many of its records point at code that no longer exists.
- **Settings** — versions, the fixed behaviour values, logging toggles, export.

### Freshness <a id="freshness"></a>

The content hash of the installed source (normalized: BOM stripped, CRLF→LF, trailing whitespace trimmed, one trailing newline) is compared with the hash of the upstream file. `Current` means the hashes match. `Update available` means they differ *and* the upstream version compares newer by an ordered numeric comparison. `Changed upstream` means they differ but no ordered comparison is possible (no version literal on one side, or a form that cannot be ordered such as `2.3.9l`), or the upstream version is not newer. `Modified locally` means the installed source no longer matches the hash recorded when Code Manager first saw it — it was edited in the code editor. `No upstream` is a local orphan with nowhere to check. `Private bundle` members cannot be read at all. HPM packages are judged by the manifest `version`, as HPM does; component hashes are refreshed only when the manifest changes.

### Broken sources <a id="broken-source"></a>

A location that answers `404` or `410`, or fails DNS/TLS, on **three consecutive sweeps** is marked `Broken source`. `5xx`, timeouts and `429` count as unreachable and do not add a strike. This is about whether the code on *this* hub still resolves upstream, never about package quality.

### Safe to remove <a id="safe-to-remove"></a>

A component is listed as removable when it has zero usage (no instances, devices or consumer types), it is not a member of a bundle that has any used member, and no installed parent app references it as a child type. The list is advisory: the hub refuses to delete in-use types regardless, and 0.1 has no delete action.

### HPM observe mode <a id="hpm-observe"></a>

When HPM is installed, Code Manager imports HPM's own registry (the exact package → installed-code mapping HPM keeps in its state) and shows those packages as *managed by HPM*. HPM stays the only writer for them; Code Manager reports freshness and never changes HPM's state or settings. The import is re-run on every sweep. Removing Code Manager leaves HPM untouched.

### Fixed values <a id="fixed-values"></a>

These are not settings; they are documented here and linked from the UI.

| value | why |
|---|---|
| Freshness TTL 24 h | one upstream fetch per item per day is enough; conditional requests make unchanged items cost nothing |
| Daily sweep at a random minute of 03:xx | off-peak, spread across hubs |
| 6 upstream fetches in flight | the platform allows 8 async calls per app; two stay free for the UI |
| Broken after 3 consecutive failures | one bad day is not a dead repository |
| 3-wide API limiter in the UI | each API call fans out to several hub calls; the hub's HTTP pool is ~20 |

## Endpoints (OAuth)

`GET /ui.html` · `GET /api/inventory` · `GET /api/item/{app|driver|library}/{id}/source` · `POST /api/sweep/start` · `GET /api/jobs/{id}` · `GET /api/hpm/status` · `POST /api/hpm/import` · `GET /api/export` · `GET|POST /api/settings` · `GET /api/version/check` · `POST /api/ui/sync` · `POST /api/reinit` · `GET /api/stats`

`GET /api/inventory` and `POST /api/sweep/start` both accept `?force=1`. On inventory it re-runs the pass regardless of the 1-hour freshness of the cached pass; on sweep it ignores the 24-hour TTL and re-checks every item — unchanged items still answer `304`.

## Tests

`bash apps/CodeManager/tests/test-code-manager-api.sh [@hub]` (add `RUN_SLOW_TESTS=1` to run a sweep), `node apps/CodeManager/tests/test-*.js`, `groovy apps/CodeManager/tests/test-*.groovy`.

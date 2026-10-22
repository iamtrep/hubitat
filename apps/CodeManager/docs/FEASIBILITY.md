<!--
Copyright (c) 2025-2026 PJ
SPDX-License-Identifier: MIT
-->

# Code Manager — feasibility and decomposition

Companion documents: [`HPM-PARITY.md`](HPM-PARITY.md) (what must be reimplemented) and [`DESIGN.md`](DESIGN.md) (data model, refresh, safety, migration, endpoint split).

Scope under evaluation: a single Hubitat app that replaces the Hubitat Package Manager client (HPM) while keeping the HPM ecosystem (master manifest, author `repository.json`, `packageManifest.json`, category/tag enum), and additionally inventories, update-checks, and safely updates code installed from any `importUrl`, bundles, and code with no provenance, with a File Manager SPA over an OAuth endpoint as the UI.

## Verdict

The plan is feasible as one app plus one SPA. Source size is not the constraint that decides this; the constraints that shape the decomposition are the per-app async ceiling, state size, and the cost of every compile on the hub. The decomposition is: one Groovy app holding the write path, the scheduled sweep, and the hub-local aggregators (target ≤ 2,500 lines / ≤ 120 KB), one SPA holding all presentation, diffing, catalog browsing, and planning (≤ 400 KB), File Manager holding snapshots, the sweep cache, and the catalog index, and one off-hub-built static index for driver fingerprints. Parent/child apps and `#include` libraries are rejected for this app. No external service is required for any feature except the fingerprint index, which is a static file on GitHub.

Two premises were checked and hold on firmware 2.5.1.147: stock Hubitat has no update detection for user code (the Apps Code / Drivers Code pages and the editor carry no version check; Import is a browser-side `fetch(importUrl)` into the editor textarea), and an app can enumerate every user code item on its own hub, including `importUrl`, source, library and bundle membership, through loopback admin endpoints.

## Measured limits (C-8 Pro, firmware 2.5.1.147)

Method: synthetic app sources posted to `POST /app/saveOrUpdateJson` (the editor's Save path), each a `definition()` plus generated code of a chosen shape. Three shapes were tried: many small methods (each ~480 bytes with a GString, a loop, and a map literal), a single large string literal, and a single method with thousands of statements. Types created by the test were deleted afterwards.

| shape | source size | result |
|---|---|---|
| many small methods | 393,632 B | compiled; 32.7 s |
| many small methods | 524,513 B | compiled; 12.6 s |
| many small methods | 786 KB | compiled (type appeared on the hub after the client had given up waiting) |
| one string literal, 65,000 chars | 65,463 B | compiled; 0.2 s |
| one string literal, 66,000 chars | 66,463 B | rejected after 88 s: `String too long. The given string is 66000 Unicode code units long, but only a maximum of 65535 is allowed.` |
| one string literal, 200,000 chars | 200,464 B | no response within 49 min |
| one method of 1,500 / 4,000 / 8,000 statements | 119 KB / 319 KB / 639 KB | no response within the client timeout |

Timings above were taken while the hub was under load from the preceding compiles, so they are upper bounds for an idle hub, not a scaling curve.

Consequences:

- The ceiling on total source size is far above HubDiagnostics' 213 KB and HPM's 159 KB: 786 KB of ordinary method-shaped code compiles. Raw capacity is not what limits a combined app.
- The hard per-class limits are the JVM's, not Hubitat's: a string literal is capped at 65,535 UTF-16 code units (measured), and bytecode per method is capped at 64 KB by the JVM class-file format (the on-hub measurement of that limit did not return; the limit itself is a JVM constant). Embedding the SPA HTML as a Groovy string is therefore impossible above 64 KB, which confirms the File Manager pattern.
- A compile that fails or runs long is not free. While the oversized-literal compile was outstanding, every subsequent code save on the hub, including a 413-byte one, timed out for roughly two hours, and `/hub2/hubData` response time rose from its usual ~1 s to ~3 s while the cheap listing endpoints stayed under 100 ms. The hub recovered without a reboot. Code Manager must therefore refuse to push sources it can predict will fail (a quoted literal over 65,535 units is detectable by regex before the push) and must never push more than one component at a time.
- State size has no documented limit; the one recorded data point in this repo is a 244 KB `state` that aborted the execution reading it (`docs/automation-map-audit.md`). HPM's state on the test hub is 55 KB for 15 packages (26.5 KB of trimmed manifests, 26.7 KB of master-manifest copy). A 160-component inventory with hashes fits in ~25 KB if only identity and last-known values are stored there; sweep results, catalog, and snapshots must not live in `state`.

## Why one app, and why not the alternatives

**One app.** Every feature reads the same inventory and writes through the same apply path; splitting them creates two copies of the loopback wrappers, the registry, and the safety checks. HPM's own 4,830 lines are not evidence that the engine needs that much: its four write paths (`performInstallation`, `performModify`, `performRepair`, `performUpdates`, ~1,420 lines) are near-duplicates of one download→write→commit skeleton, its UI pages are ~1,600 lines of `dynamicPage` that a SPA replaces, and ~800 lines are dual implementations kept for firmware back to 2.2.1. A deduplicated engine with one write path is estimated at 400–500 lines.

**Parent/child apps — rejected.** A child app type would bring its own 8-slot async pool and its own `state`, which is the only benefit. The sweep does not need a second pool: a 200-item sweep at 6 in flight and ~1 s per fetch completes in under a minute, and conditional requests (`If-None-Match`, supported by raw.githubusercontent.com with a 304 response) make unchanged items cost no body transfer. The cost is a second app type to install, keep in lockstep, and explain, plus parent↔child method calls for every engine operation.

**Libraries via `#include` — rejected for this app.** The hub flattens includes into the consumer's compiled class (`/driver/downloadFull/{id}` returns the flattened source with `// library marker` lines; `/driver/ajax/code` returns the original with `#include` lines), so a library changes nothing about per-class limits. Libraries also cannot be installed by HPM except inside a bundle, which would make Code Manager's own install path bundle-only. The repo's three existing copies of the GitHub version poll (HubDiagnostics, MultiHubInventory, WellMonitor) are a separate refactoring question.

**Off-hub into the SPA — adopted.** Everything that is not hub-local I/O, not scheduled, and not secret-bearing moves to the browser: catalog browsing and search, diff rendering, sort/filter/classification display, install planning, and the fingerprint lookup. Two facts make this more than a rendering split: raw.githubusercontent.com serves `Access-Control-Allow-Origin: *`, so the browser can fetch manifests and sources directly for interactive use (no async-pool pressure, no hub timeouts, no hub CPU), and the hub only needs to fetch during the scheduled sweep and at apply time, where it re-fetches and re-hashes what the user reviewed. Hosts that do not send CORS headers (the master list includes codeberg.org, bitbucket.org, and several self-hosted servers) fall back to a hub-side fetch endpoint restricted to URLs already present in the registry or catalog.

**External service — rejected for everything except the fingerprint index.** HPM depends on two third-party PHP endpoints (keyword search and "Fast Match", the latter POSTing the hub's complete list of user app and driver types to the server). Code Manager replaces both with a catalog index built in the browser from the published `repository.json` files and persisted in File Manager, and a local match-up that uses `importUrl`, which HPM never reads. The fingerprint index cannot be built on-hub (it requires parsing every community driver source in the catalog) and ships as a static JSON generated by tooling in this repository.

## Size budget

| component | budget | contents |
|---|---|---|
| `CodeManager.groovy` | ≤ 2,500 lines, ≤ 120 KB | definition, mappings, lifecycle (150); loopback + remote HTTP wrappers (150); inventory fetchers and normalizers (250); package model, synthesis, HPM import (250); sweep engine: queue, hashing, version parse, staleness (300); apply engine: preflight, snapshot, write, verify, rollback (400); install/uninstall/modify/repair orchestration (350); endpoints (250); settings, notifications, label badge (150); logging and utilities (150) |
| `code_manager_ui.html` | ≤ 400 KB | tables, classification, diff view, catalog browse/search, apply wizard, HPM transition panel, fingerprint lookup |
| File Manager | `cm_registry_export.json` (on demand), `cm_sweep.json` (≤ 100 KB), `cm_catalog.json` (≤ 1 MB), `cm_snap_<kind><id>_<epoch>.groovy` (3 per item by default) | sweep cache, catalog index, pre-write snapshots |
| `state` | ≤ 60 KB | package registry (identity, hub ids, installed version, installed hash per component), settings, HPM transition flags |
| fingerprint index (GitHub) | ≤ 5 MB | generated off-hub; fetched by the SPA only |

The Groovy budget is a policy ceiling chosen to stay well under HubDiagnostics' 213 KB, which this repository already treats as the limit for one file; it is not a compile limit.

## Platform verification (firmware 2.5.1.147)

All endpoints below are the admin UI's own, reached from on-hub code via `http://127.0.0.1:8080` (loopback is not gated by hub security). None are officially supported; they are the same family this repository's tooling already depends on ([hubitat-internal-apis.md](../../../docs/hubitat-internal-apis.md)).

**Enumerate all user code, including code the app did not install.** `GET /hub2/userAppTypes` → `[{id, name, namespace, oauth, lastModified, usedBy[{id,name}]}]`; `GET /hub2/userDeviceTypes` → `[{id, name, namespace, capabilities, lastModified, usedBy[{id,name}]}]`; `GET /hub2/userLibraries` → `[{id, version, name, namespace, author, category, description, updateTime, lastModified, private, usedByAppTypes, usedByDeviceTypes}]` (the last two are comma-separated type names); `GET /bundle/list/json` → `[{id, name, namespace, private, sourceUrl, appTypeIds[], deviceTypeIds[], libraryIds[], …}]`. Four bulk calls give identity, instance/device counts, library consumers, and bundle membership by id. `/hub2/userBundles` carries only a human-readable `content` string; `/bundle/list/json` is the one with ids and the source zip URL.

**Read `importUrl`.** Not in any bulk listing. `GET /app/list/single/data/{id}` → `[{id, version, name, namespace, author, description, private, source, importUrl, oauthClientId, oauthClientSecret, installedAppCount, …}]` and `GET /driver/list/single/data/{id}` → `[{…, source, importUrl, installedDriverCount}]` return it together with the full source, so one call per item yields both. Libraries have no `importUrl` field anywhere; it is parsed from the `library(...)` header in `GET /library/ajax/code?id=`. Bundles expose `sourceUrl` in `/bundle/list/json`. On the test hub (162 items: 60 apps, 92 drivers, 7 libraries, 3 bundles) the per-item pass took ~1–2 s per call under load; at idle it is a one-time cost cached in File Manager and refreshed only for items whose `lastModified` changed.

**Source for hashing.** `GET /app/ajax/code?id=` and `/driver/ajax/code?id=` return the source as saved, with `#include` lines intact and no library expansion; `/app/downloadFull/{id}` returns the flattened form. Hashing uses `ajax/code` so the installed form and the upstream file have the same shape. Code from a private bundle is not readable or editable in the UI and is treated as opaque (update only by re-importing the bundle).

**Write code back.** `POST /app/saveOrUpdateJson` (JSON `{id?, version, source}`) creates when `id` is absent and updates otherwise, and re-parses `definition()`; `POST /app/ajax/update` (form `id, version, source`) updates without re-parsing `definition()`; both carry an optimistic-lock `version` read from `ajax/code`. Driver endpoints mirror these. Measured failure modes: a compile error returns `{"status":"error","errorMessage":"unexpected token: { @ line 5, column 15."}` (ajax) or `{"success":false,"message":"…"}` (saveOrUpdateJson) and leaves the previous source and lock version untouched; a stale lock version returns `{"status":"error","errorMessage":"Version does not match, attempting to update version 0, current version is 1"}`. Bundles: `GET /bundle2/uploadZipFromUrl?url=&pwd=&private=` (the hub downloads the zip itself); `GET /bundle/deleteJson/{id}?full=true` deletes the bundle and its member code. Libraries: `POST /library/saveOrUpdateJson` with the same `{id, source, version}` body (the Libraries Code editor's save path); `GET /library/list/single/data/{id}` carries `usedByAppTypeCount` / `usedByDeviceTypeCount`, which the editor uses to block deletion. Files: `uploadHubFile` / `deleteHubFile`. Deletes: `GET /app/edit/deleteJson/{id}` (`deleteJsonSafe` refuses in-use types), `GET /driver/deleteDeviceType/{id}`, `GET /library/edit/deleteJson/{id}`.

**Read another app's persisted state.** `GET /installedapp/statusJson/{installedAppId}` → `{installedApp, appSettings[], appState[{name,value,type}], scheduledJobs[], …}`. HPM's `state.manifests`, its settings (`autoUpdateMode`, `installedRepositories`, …), and its scheduled `checkForUpdates` job are all readable this way, which is the migration read path.

**Upstream fetch.** raw.githubusercontent.com returns `ETag`, `Cache-Control: max-age=300`, and `Access-Control-Allow-Origin: *`; a conditional `GET` with `If-None-Match` returns `304` with an empty body. Published changes can lag up to five minutes behind the commit.

## Evidence still wanted

- The oldest firmware on which `/app/list/single/data/{id}`, `/driver/list/single/data/{id}`, and `/bundle/list/json` exist. HPM's own gates put `/hub2/userAppTypes` at 2.3.6.126, `/bundle2/uploadZipFromUrl` at 2.3.8.108, and `deleteJsonSafe` at 2.3.8.128; the provisional `minimumHEVersion` is 2.3.8.128 pending a check on a hub at that level.
- Compile time for 200–500 KB sources on an idle hub, to set the SPA's expectation text for a large-driver apply.
- Whether a library save refreshes its consumers' compiled classes. Established so far: the save returns in ~1 s, the consumers' `lastModified` and lock `version` do not change, and `downloadFull` for a consumer shows the new library text at once. Not established: whether the running consumer executes the new code before it is re-saved. A behavioral test (a library method whose return value changes, read through a consumer that is not re-saved) would settle it; until then the design re-saves consumers after a library write.

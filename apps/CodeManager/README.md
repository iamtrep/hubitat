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

## What it shows

- **Inventory** — every user app, driver, library and bundle, with origin (`hpm`, `bundle`, `url`, `local`, or `unread` until its source has been read), installed version (declared by the HPM manifest or parsed from source; the parse idiom is shown when it is not the standard `CODE_VERSION` field), upstream version, freshness status, usage counts (instances for apps, devices for drivers, consumer types for libraries), and the upstream location.
- **Packages** — HPM packages, bundles and multi-file imports: code installed and updated as a set. Each links to its rows on Inventory; single-file code is listed only on Inventory. See [Packages](#packages).
- **HPM** — the transition panel: whether HPM is installed, its instance and `autoUpdateMode`, how many packages were imported from its registry, and how many of its records point at code that no longer exists.
- **Settings** — versions, the fixed behaviour values, logging toggles, export.

### Packages <a id="packages"></a>

Every item on the hub belongs to exactly one package, and never to two. A package is an HPM package, a bundle, or — for code installed from a URL or written on the hub — a package of one. Ownership is decided in that order: HPM first, then bundles, then everything left over. A bundle an HPM package installed is owned by that package, and the bundle's contents are listed under it rather than as a second package of the same name.

Most hand-installed code is a package of one, which is why the Packages tab lists those separately, collapsed: they carry no more information than their Inventory row. The uniform model is what makes one update path serve all three kinds; it is not a claim that every file is interesting.

### Freshness <a id="freshness"></a>

Every fact on a row says what it is based on (the **Facts** column). Usage, packages and parents are *live*: read from the hub's own lists for this page. An item's version, hash and import URL are *read at a time*: a background reader reads each source once and the fact stays valid while the item's `lastModified` is unchanged. After an edit the row reads *not read since change* until it is read again; an item never read reads *not read yet*, and one that keeps failing reads *could not read*. A freshness status is the result of a drift check *checked at a time* against the code installed then; it is void once the code changes. A drift check that fails keeps the last good comparison and shows the failure beside it.

The content hash of the installed source (normalized: BOM stripped, CRLF→LF, trailing whitespace trimmed, one trailing newline) is compared with the hash of the upstream file.

| status | meaning |
|---|---|
| `Current` | the hashes match |
| `Update available` | they differ *and* the upstream version compares newer by an ordered numeric comparison |
| `Changed upstream` | they differ but no ordered comparison is possible (no version literal on one side, or a form that cannot be ordered such as `2.3.9l`), or the upstream version is not newer |
| `Modified locally` | the installed source no longer matches the hash recorded when Code Manager first saw it — it was edited in the code editor — unless it now matches upstream, in which case it is `Current` |
| `Broken source` | the upstream URL struck out — see [Broken sources](#broken-source) |
| `Missing on hub` | the package requires this component and it is not installed |
| `Not installed` | the package declares this component as optional and it is not installed. HPM packages typically declare every driver a repository ships; this is the normal state for the ones you never chose |
| `Private bundle` | private code, whose source is never read |
| `No upstream` | no import URL, so there is nothing to compare against |
| `Not checked` | no drift check has compared this code with its upstream, or the code changed since the last one. Run a drift check |

A package rolls up its components: `Incomplete` if a required one is missing, then `Update available`, then `Changed upstream`, then `Current`. A package whose components are all uninstalled reads `Not installed`; one with nothing comparable reads `Not checked` (`No upstream` for hub-local code) — never `Current`, which is a claim about a comparison that did happen.

HPM packages are judged by the manifest `version`, as HPM does, and component bodies are fetched only when the manifest changes. A file an HPM package declares is `Not checked`: the manifest says nothing about its content. The files inside a bundle an HPM package installed are checked individually and can read `Changed upstream` on their own, but they do not drive the package's status: no HPM update would fetch them without a manifest version bump.

### Broken sources <a id="broken-source"></a>

A location that answers `404` or `410`, or fails DNS/TLS, on **three consecutive drift checks of the same code** is marked `Broken source`. `5xx`, timeouts and `429` count as unreachable and do not add a strike. This is about whether the code on *this* hub still resolves upstream, never about package quality.

### Safe to remove <a id="safe-to-remove"></a>

A component reads **no current use** when it is installed, has zero usage (no instances, devices or consumer types) and it is not a member of a bundle that has any used member. That list cannot see code that would create the type later, so an app or driver row in it has a **Check removable** button: it reads every app, driver and library that could create the type and answers *no code on the hub creates it*, *may be created by* (with the names), or *not decided* when a source could not be read. The answer is advisory: the hub refuses to delete in-use types regardless, and Code Manager has no delete action yet.

### HPM observe mode <a id="hpm-observe"></a>

When HPM is installed, Code Manager imports HPM's own registry (the exact package → installed-code mapping HPM keeps in its state) and shows those packages as *managed by HPM*. HPM stays the only writer for them; Code Manager reports freshness and never changes HPM's state or settings. The import is re-run at the start of a package or full drift check. Removing Code Manager leaves HPM untouched.

### Fixed values <a id="fixed-values"></a>

These are not settings; they are documented here and linked from the UI.

| value | why |
|---|---|
| Drift checks only on request | there is no scheduled check, update badge or notification; a result is shown with its age and is void once the code changes |
| 6 upstream fetches in flight | the platform allows 8 async calls per app; two stay free for the UI |
| Broken after 3 consecutive failures | one bad day is not a dead repository |
| 3-wide API limiter in the UI | each API call fans out to several hub calls; the hub's HTTP pool is ~20 |

### Hub cost <a id="hub-cost"></a>

A page load reads only the hub's bulk lists (app, driver and library types, bundles, File Manager, apps and devices), shared by every open page for 30 s; the slow library list is read in the background at most every 10 min, or on **Refresh libraries**. No item's source is read inside a request, except the one item a user checks. Sources are read in the background under a fixed budget:

| value | why |
|---|---|
| One source read in flight, 2 s between reads | the fact reader, a drift check's fact phase and a remove check share one read slot |
| No read while a code write is outstanding | a write and a large read compete for the same compile and memory |
| After 2 failed reads, retry after 1 h, then every 24 h | an item whose read never answers costs two timeouts, not one per pass |
| Sources above 512 KB are not read again in the background | the hub's lists carry no size, so the first read of each item measures it; after that it is read only when a user asks (a single check or a remove check) |
| A read within 2 s of the item's save is read once more | two saves in the same second leave `lastModified` unchanged |
| Read results written to the registry every 10 reads | a restart loses at most 10 reads, which are read again |

Measured with 0.2.0 on a C-8 Pro holding 211 user apps, drivers and libraries. "Handler time" is Code Manager's own time in the hub's app statistics, including time spent waiting on HTTP; "memory dip" is the largest fall in the hub's free OS memory while the operation ran, sampled every 5 s.

| operation | wall time | handler time | memory dip |
|---|---|---|---|
| Page load (`/api/inventory`), bulk lists older than 30 s | 2–4 s | 1.7 s | under 1 MB |
| Page load, bulk lists shared from another page | under 1 s | 0.5 s | under 1 MB |
| **Refresh libraries** | 13 s | 10 s | 2 MB |
| Drift check of everything (114 upstream URLs) | 17–22 s | 112 s, mostly waiting on upstream hosts | 8 MB |
| Remove check of one driver (13 sources read) | 58 s | 28 s | 6 MB |
| First full read of every item (211 sources, after install) | 12 min | 5.6 min | 76 MB |

After the first full read, the reader reads only items saved since, so its cost follows how often code changes.

## Endpoints (OAuth)

`GET /ui.html` · `GET /api/inventory` · `GET /api/item/{app|driver|library}/{id}/source` · `POST /api/drift/check` · `POST /api/drift/start` · `GET /api/facts/status` · `POST /api/facts/read` · `POST /api/libraries/refresh` · `POST /api/remove-check/start` · `GET /api/jobs/{id}` · `GET /api/hpm/status` · `POST /api/hpm/import` · `POST /api/export` · `GET|POST /api/settings` · `GET /api/version/check` · `POST /api/ui/sync` · `POST /api/reinit` · `GET /api/stats`

`POST /api/drift/check?key=app:12` checks one item and answers in the request. `POST /api/drift/start?scope=all` or `?scope=package&key=<package key>` starts a job: it brings the facts of the items in scope up to date under the fact reader's rules, then fetches upstream (unchanged items answer `304`). Items above the background size limit are not read by the job and are counted as `skippedLarge`.

`POST /api/remove-check/start?kind=driver&id=N` (or `kind=app`) starts a job that reads every app, driver and library that could create that type: those whose facts record an `addChildDevice`/`addChildApp` call, and those whose facts are not current, large sources included. Its result is `verdict` (`no-creator`, `may-be-created` or `incomplete` when a source could not be read), with the `creators` and `unread` items listed.

## Tests

`bash apps/CodeManager/tests/test-code-manager-api.sh [@hub]`, `node apps/CodeManager/tests/test-*.js`, `groovy apps/CodeManager/tests/test-*.groovy`.

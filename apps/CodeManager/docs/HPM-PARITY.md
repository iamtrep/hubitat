<!--
Copyright (c) 2025-2026 PJ
SPDX-License-Identifier: MIT
-->

# HPM parity inventory

Source analyzed: `apps/Package_Manager.groovy` in HubitatCommunity/hubitatpackagemanager, commit `c855314` (2026-05-05), version `v1.9.11` (`:63`), 4,830 lines; HubitatCommunity/hubitat-packagerepositories commit `2f15a89` (2026-08-18). Line references are `Package_Manager.groovy:LINE`. Live-instance facts come from an HPM 1.9.11 install on a C-8 Pro at firmware 2.5.1.147 with 15 managed packages, read through `GET /installedapp/statusJson/{id}`. The manifest schema itself is documented in the host repo's `docs/HPM-MANIFESTS.md` and is not repeated here.

Verdict column: **match** = Code Manager must do this equivalently; **improve** = must do it, and the HPM implementation has a defect or limitation worth fixing; **drop** = not carried, with the user cost stated.

## 1. Feature-by-feature

| feature | HPM behavior (cited) | verdict | notes |
|---|---|---|---|
| Install from URL | `prefPkgInstallUrl` `:517-534` → `performInstallation` `:818-1015`: downloads every app/driver/file body first (`:851-914`), then writes bundles → apps → drivers → files (`:918-1011`); `state.manifests[url]` is written **before** any hub write (`:846`) and removed on some failure paths only (file failures at `:1008` roll back but keep the entry) | improve | keep the download-everything-first phase; commit the registry entry after the last successful write, not before |
| Install by browse (category/tag) | async batch fetch of every subscribed `repository.json` (`:543`), package list rebuilt in `@Field static allPackages` on every visit (`:696-732`); tag filter validated against settings.json (`:561-583`) | match | catalog index built in the SPA and cached in File Manager; same enum, same validation |
| Install by keyword search | `httpPost` to `searchHPMpkgs2.php` with a GraphQL body (`:460-476`); standard repositories only (`:423`) | improve | search the cached catalog (name, description, author, tags) in the SPA; no server dependency; covers custom repositories too |
| Optional components at install | `appsToInstall`/`driversToInstall`/`bundlesToInstall` (`:667-671`); optional = `required == false` strictly (`:3437-3459`) | improve | same semantics; selecting an optional bundle throws at `:939` (`.value.beta` on a String loop variable) and leaves the spinner stuck |
| `minimumHEVersion` gate | `verifyHEVersion` `:3496-3515` at install `:650` and update check `:1749`; not applied at repair or modify; field stripped from stored manifests `:4488` | improve | apply at install, update, repair, modify; keep the field |
| License click-through, release notes, beta notes | `prefInstallVerify` `:734-783` | match | |
| Launch primary app after install | `/installedapp/create/{heID}` via injected JS (`:809-813`, `:4730-4732`) | match | SPA link |
| OAuth enable on install/upgrade | `enableOAuth` `:3728-3752`: `POST /app/edit/update` with `oauthEnabled=true`; always returns true, no try/catch | improve | verify by re-reading `/app/list/single/data/{id}.oauthClientId` |
| Update check (manual) | `performUpdateCheck` `:1725-1873`: one synchronous `getJSONFile` per installed package, sequential, 300 s timeout each (`:1731`, `:3284`); package-level version compare, else per-item compare for items with `version` (`:1769-1863`) | improve | background sweep with ≤ 6 in flight, conditional GETs, per-item staleness; see DESIGN.md |
| Update check (scheduled) + label badge + notifications | daily cron at `updateCheckTime` (`:195`); `app.updateLabel("… <span style='color:green'>Updates Available</span>")` (`:3093`); one-shot `deviceNotification` gated by `state.updatesNotified` (`:3023-3027`) | match | same badge text; same notification inputs |
| Automatic updates | `autoUpdateMode` `Always\|Never\|Include\|Exclude` with per-package include/exclude lists (`:3029-3048`), applied unattended from the scheduler | improve | carried as an explicit **per-package opt-in** only: unattended apply runs for packages the user has selected, through the same snapshot → write → verify → rollback path as an interactive apply, one package at a time, and never for a package whose last sweep showed a local modification or an upstream change without an ordered version (DESIGN.md 6.5). HPM's `Always` (every package, including ones added later) and `Exclude` modes are not carried: a newly installed package is never auto-applied until selected. HPM's `appsToAutoUpdate` list seeds the opt-in set at import. Cost: users who want every package updated forever must opt in as they add packages |
| Update apply | `performUpdates` `:2071-2433`: phase 1 downloads every body for every selected package and aborts the whole batch on any failure (`:2085-2232`); phase 2 writes per package and **returns at the first failure**, leaving earlier packages upgraded and later ones untouched (`:2234-2424`); all bundles are re-imported on every update (`:2245-2254`) | improve | per-package transactions with per-component outcome; bundles re-imported only when their manifest `version` or `location` changed |
| Rollback | `rollback` `:4308-4366` driven by `@Field static completedActions`; update rollback calls `upgradeApp(upgradedApp.heID, …)` `:4355-4360` but entries were stored as `[id:…, source:…]` `:2269`, so the previous source is never restored; rolled-back files are deleted, not restored (`:4320-4326`) | improve | snapshot to File Manager before every write; restore from snapshot; files restored from snapshot too |
| Beta channel | `betaLocation`/`betaVersion`, `includeBetas`, per-package `pkgBetaOn` (`:341-344`, `:823-844`, `:3540-3568`); `manifest.beta` forced true for every package when `includeBetas` is on (`:2242-2243`) | match | per-package opt-in only; no global override |
| Modify (add/remove optional components) | `performModify` `:1229-1333`; bundle modify is dead code (no input rendered, `:1045-1066`); uninstall snapshot uses `getDriverSource` for apps (`:1288`) | improve | |
| Repair | `performRepair` `:1391-1560`: re-pushes items whose `heID` still exists, installs missing required items | match | same, through the snapshot/verify path |
| Uninstall | `performUninstall` `:1648-1711`: apps `deleteJsonSafe` (refuses in-use), drivers, files, bundles by name (`:4174`); uninstall rollback looks up the manifest with a List key (`:4312`) and would NPE | improve | usage index shown before confirming; per-component outcome |
| Match-up (adopt existing code) | fast path POSTs all user app/driver `[id,title,namespace]` to `hpmRepoCache.php` (`:2554-2571`); fallback fetches every package manifest of every repository (`:2649`); match = exact `name`+`namespace`, then `alternateNames` (`:4513-4525`); `importUrl` is never read | improve | local match on `importUrl == location` first, then `name`+`namespace`/`alternateNames`, against the cached catalog; no data leaves the hub |
| Un-match | `state.manifests.remove(pkg)` (`:2848`) | match | package becomes origin `url` or `local` rather than disappearing |
| View installed / unmanaged | `prefPkgView` `:2863-2927`, `prefPkgViewUnmanaged` `:2929-2970` | match | subsumed by the inventory |
| Repository management | master list + custom repos; auto-add newly listed, auto-remove delisted (`:4410-4444`); `deleteCustomRepository` `:4561-4572` | match | |
| Bundles | install `GET /bundle2/uploadZipFromUrl?url=&pwd=&private=` (`:4116`; the manifest `primary` flag is sent as `private`); uninstall by name; **no hub id is ever stored for a bundle**, so `isBundleInstalled` `:3200-3210` is always false | improve | record the bundle's hub id from `/bundle/list/json` |
| File Manager files (`files[]`) | `uploadHubFile` / `deleteHubFile` (`:3979`, `:4075`); binary download failure undetected (`:4505`) | improve | |
| Dependency resolution | none (`:2.9`); no library concept | match (none) | libraries appear only inside bundles in the HPM schema; Code Manager inventories them but does not add a dependency field to the manifest schema |
| Backup/restore of HPM's own registry | none | improve | export/import JSON; required for migration and exit |
| Hub security login | `hpmSecurity`/`hpmUsername`/`hpmPassword`, `POST /login`, cookie persisted in `state.cookie` (`:3586-3626`), `sslEnabled` 8443 | **drop** | loopback is not gated by hub security, so no credentials are needed; cost: none on current firmware. HPM's own calls already go to `127.0.0.1` (`:4826-4830`) |
| Legacy firmware paths | ten firmware-gated dual implementations (HTML scraping of `/app/list`, multipart upload, `/bundle` vs `/bundle2`) keep `minimumHEVersion` at 2.2.1 | **drop** | Code Manager requires the JSON endpoints (provisionally ≥ 2.3.8.128). Cost: hubs below that stay on HPM |
| Self-update | new version parked in a location variable `hpmVersion` before the code swap (`:2222-2223`, `:4402-4406`); `upgradeApp` hoisted to `:150` | improve | `atomicState.pendingSelf` set before the write; finalized by the `CODE_VERSION != state.version` check on the next invocation |

## 2. Persisted state model (exact)

`state.manifests` is the install registry: `Map<String manifestUrl, Map manifest>`. The value is the downloaded `packageManifest.json` mutated in place (`:846`, `:2419`, `:1549`, `:2760`):

- Stripped by `minimizeStoredManifests` `:4480-4491`: `licenseFile`, `releaseNotes`, `dateReleased`, `minimumHEVersion`.
- Package level, added or overwritten: `beta` (Boolean), `payPalUrl`, `gitHubUrl`; `version` is set to `"0.0"` by match-up when the user does not assert the code is current (`:2736`), which forces the next check to report an update.
- `apps[]` entries: author fields (`id`, `name`, `namespace`, `location`, `required`, `oauth?`, `primary?`, `version?`, `betaLocation?`, `betaVersion?`, `alternateNames?`) plus **`heID`** = the hub app-type id as a String, plus `beta`. `heID` comes from the `Location` header of `/app/save` (`:3651`), from `/hub2/userAppTypes` (`:4787`), or from match-up (`:2740`). `heID == null` means not installed (`:3176-3186`), which is how optional components that were never selected are represented.
- `drivers[]` entries: same minus `oauth`/`primary`.
- `bundles[]` entries: author fields plus `beta`; never a `heID`.
- `files[]` entries: untouched; tracked by `name` only (`:3976`).
- Installed version is whatever the stored manifest says (`manifest.version`, `betaVersion`, per-item `version`); HPM never reads a version out of Groovy source (`:4.7`).

Live-instance observations (15 packages): top-level keys seen were `apps, author, beta, bundles, category, communityLink, documentationLink, drivers, files, packageName, payPalUrl, tags, version`; two manifests had no `version` at all; one driver `heID` referred to a type id that no longer exists on the hub (a dangling record that HPM does not detect until repair). Other keys: `repositoryListingJSON` (master manifest copy plus custom repos, 216 repositories), `customRepositories` (`url → author`), `categoriesAndTags`, `firstRun`, `updatesNotified`, `fastTrackResults`, `manifestsHavePayPalAndGitHub`, `decompressTimestamp`, `hpmUpgraded`, `backgroundActionInProgress` (tri-state `null`/`true`/`false`, `:114`), `cookie`, and button flags. Settings that carry configuration: `installedRepositories` (repo URLs), `includeBetas`, `pkgBetaOn`, `updateCheckTime`, `notifyUpdatesAvailable`, `notifyDevices`, `autoUpdateMode`, `appsToAutoUpdate`, `appsNotToAutoUpdate`, `notifyOn{Success,Failure}`, `notifyIncludeHubName`, `notifySpecificPackages`, `matchMethod`, `hpmSecurity`/`hpmUsername`/`hpmPassword`, `sslEnabled`, `debugOutput`, `txtEnable`.

Migration reads exactly this: for each `state.manifests` entry, the URL key, `packageName`, `version`, `beta`, and for each component its manifest `id`, `name`, `namespace`, `location`, `required`, `heID`; plus `installedRepositories`, `customRepositories`, and `pkgBetaOn` for repository and beta preferences. Everything else is derivable or HPM-internal.

## 3. Install / update / uninstall code paths and failure modes

Hub access: loopback `getBaseUrl()` `:4826-4830`, every `perform*` starts with `login()`; enumeration `getAppList()` `:4773-4824` and `getDriverList()` `:4586-4629` keep only `id`, `title`, `namespace`.

Write helpers: `installApp` `:3629-3664` `POST /app/save` form `{id:"", version:"", create:"", source}`, id from the `Location` header, timeout 420 s; `upgradeApp` `:150-178` `POST /app/ajax/update` `{id, version: getAppVersion(id), source}`, success iff `status == "success"`; `uninstallApp` `:3668-3726` `GET /app/edit/deleteJsonSafe/{id}`; drivers mirror these (`:3802-3926`); `installBundle` `:4112-4164`; `uninstallBundle` `:4167-4260`; files `:3976-4109`.

Failure modes:

- **Dead source URL.** `downloadFile` `:3277-3296` returns null after a 300 s timeout; the install aborts before any hub write. In the update check a dead manifest is dereferenced before the null test (`manifest.beta` at `:1735`/`:1742` precedes `if (manifest == null)` at `:1744`), the NPE is swallowed by the outer catch (`:1866`), and the package is silently skipped that day.
- **Compile error in pushed code.** `/app/save` returns no `Location` header, or `/app/ajax/update` returns `status != "success"`; the hub's `errorMessage` is discarded and the user sees a generic failure (`:4365`).
- **Partial failure mid-package.** Rollback deletes newly created items (`:4317-4326`) but cannot restore upgraded sources (`:4355-4360`, see §1); a multi-package update stops at the first failing package (`:2273-2277`).
- **Uncaught exception in a background job** (`enableOAuth`, `getAppVersion`, `:939`, `:4347`): `atomicState.backgroundActionInProgress` stays `true` and the 2-second refresh page spins until the user returns to the main menu (`:249`).
- **In-use type on uninstall.** `deleteJsonSafe` returns `status != true` → "it may be in use" (`:1665`).
- **Version compare** `compareVersions` `:3517-3538` strips non-digits and returns "newer" at the first segment where old < new without checking earlier segments, so installed `2.0` vs available `1.5` reports an update; empty segments throw `NumberFormatException`.
- **Firmware gates** compare `location.hub.firmwareVersionString` as Strings (`:3669` and nine others), so a future `2.10.x` would sort below `2.3.x`.

## 4. Bulk manifest fetching and concurrency

Update check: sequential synchronous `httpGet` per package, no cache, no retry, 300 s timeout each (`:1728-1731`); run inside a `runInMillis` job when manual, or on the scheduler thread when scheduled. Browse and the match-up fallback use `getMultipleJSONFiles` `:3387-3412`, an async batch with `maxDownloadQueueSize = 10` (`:126`) requests in flight per batch, no per-batch timeout, and no retry: a callback that never fires leaves the page spinning. Ten exceeds the eight-concurrent-async-per-app ceiling this repository has measured; HPM does not guard for it. The same package manifest is fetched three times on the install path (`:641`, `:750`, `:822`). Nothing is cached with a TTL; `state.repositoryListingJSON` is the only persisted catalog artifact.

## 5. Existing overlap with the planned features

- **Match-up between installed code and packages exists** (§1) and is the thing to reuse in shape: exact `name`+`namespace` with `alternateNames`, committed by assigning `heID`. Code Manager keeps that rule and adds `importUrl == location` in front of it, which resolves most matches without touching the catalog at all (on the test hub 65 of 159 non-bundle items carry an `importUrl`, 92 of the 100 URLs on raw.githubusercontent.com).
- **Unmanaged-items view** (`:2972-2987`) is the seed of the inventory: everything not carrying a `heID`. Code Manager's inventory is this list plus libraries and bundles plus usage counts.
- **Label badge + notification** logic is reused as-is.
- **The download-first phase** is the right skeleton for the apply path; Code Manager keeps it and adds snapshot, verify, and per-component outcomes.
- Nothing in HPM reads `importUrl`, hashes source, tracks library or bundle membership, or indexes usage.

## 6. Known HPM defects the replacement must not inherit

Compile-blind rollback (`:4355-4360`), manifest persisted before install (`:846`), bundle hub id never recorded (`:3200-3210`), first-failure abort in multi-package updates (`:2273`), spinner without watchdog (§3), ordering bug in `compareVersions` (`:3533`), 10-wide async batches (`:126`), dead-manifest NPE in the update check (`:1735`), `String.value` access on optional bundle install (`:939`), wrong source helper on modify-uninstall (`:1288`), global beta override (`:2242`), credentials and session cookie in settings/state (`:325-328`, `:3613`), and the hub's complete user-type list sent to a third-party server by default (`:2554-2571`).

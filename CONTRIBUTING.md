<!--
Copyright (c) 2025-2026 PJ
SPDX-License-Identifier: MIT
-->

# Contributing

This repository is a personal collection of apps, drivers, and integrations for the
[Hubitat Elevation](https://hubitat.com/) platform. This guide covers the conventions to
follow when contributing or adapting the code.

## Code conventions

[`ARCHITECTURE.md`](ARCHITECTURE.md) is the authoritative reference; in brief:

- **Statically typed Groovy** — declare concrete types, avoid `def`; the more involved
  apps/drivers use `@CompileStatic` where the platform allows it.
- **A single version constant** per app/driver (e.g. `APP_VERSION` / `DRIVER_VERSION`),
  bumped with every functional change.
- **One convergence point:** `installed()` and `updated()` both delegate to `initialize()`
  so configuration and subscriptions are wired up in exactly one place.
- **Three-bool logging discipline** — `txtEnable` / `debugEnable` / `traceEnable` gate
  their respective log levels; debug/trace default off (or auto-off after a delay).
- **Every source file carries an `SPDX-License-Identifier` header** (see License below).

See [`ARCHITECTURE.md`](ARCHITECTURE.md) for the full set, including platform semantics
(event dedup, `state` vs `atomicState`, scheduling, async HTTP) and the sandbox rules.

## Repo layout

```
apps/             Automation and dashboard apps          (see apps/README.md)
drivers/          Zigbee / BLE / cloud device drivers    (see drivers/README.md)
integrations/     Parent-app + child-driver vendor integrations (see integrations/README.md)
ARCHITECTURE.md   Coding conventions & platform semantics (authoritative)
TESTING.md        Testing methodology & the closed-loop contract
TEST_COVERAGE.md  Current test-coverage ledger
```

## Building a dashboard SPA — conventions

Some apps in this repo aren't automations — they're **dashboards**: a Groovy app that serves a single-page HTML app and backs it with `/api/*` JSON endpoints (e.g. [`apps/HubDiagnostics/`](apps/HubDiagnostics/), [`apps/MultiHubInventory/`](apps/MultiHubInventory/)). [`apps/HubDiagnostics/hub_diagnostics_ui.html`](apps/HubDiagnostics/hub_diagnostics_ui.html) is the reference implementation — match its conventions rather than inventing simpler ones (a leaner dashboard built without them tends to look done but be unusable).

**The served-HTML pattern**

- The HTML lives in the hub's File Manager; the Groovy serves it via `downloadHubFile()` from a `mappings` route (`path('/ui.html') { action: [GET: 'serveUI'] }`) behind an OAuth check.
- `serveUI()` substitutes `${access_token}` and `${api_base}` placeholders into the HTML so the page can call its own `/api/*` endpoints **same-origin** (browsers can't reach another hub directly — see [`ARCHITECTURE.md`](ARCHITECTURE.md) "Cross-origin (CORS)…").
- **Two-part deploy:** push the Groovy *and* upload the HTML to File Manager — pushing only the Groovy leaves the UI stale. Keep `APP_VERSION` (Groovy) and `UI_VERSION` (HTML) in lockstep.
- Develop hub-free behind a `WORKBENCH = true` toggle + mock data, and unit-test the pure SPA helpers in Node (Mode 3 — see [`TESTING.md`](TESTING.md) §2.1).

**Display conventions (these are the difference between a demo and a usable tool)**

- **Device names are always links.** HubDiagnostics' helper: `dlink(id,n) → <a href="/device/edit/{id}" target="_blank">{name}</a>`. A flagged device you can't click is a device you can't find. In a *multi-hub* SPA the link must point at the device's **own hub** (`{hubWebBase}/device/edit/{id}`), not the serving hub — so expose each hub's web base (scheme+host only, never the token) to the page.
- **Counts link too:** `dlistlink(count, ids) → /device/list?ids=...`.
- **Inactivity is absolute days since last activity, color-thresholded** — never a bare timestamp. Tiers mirror HubDiagnostics: `< inactivityDays` (default 7) green (`--ok`); `≥ inactivityDays` orange (`--warn`); `≥ 2× inactivityDays` (14d) red (`--crit`); `"Never"` if no activity.
- **Classify devices properly.** The raw `controllerType`/protocol field is unreliable (null on most devices; leaks codes like `HKC`). Reuse HubDiagnostics' `classifyDevice` cascade (authoritative `isZigbee`/`isZwave`/… flags + a parent-app → integration lookup), not the raw field.
- **Reuse the UI primitives:** the sortable/filterable table helper (`tbl()`), the badge classes, and the card helpers — over one-off implementations.
- **Header links:** the SPA header should include a **⚙ Settings** link to the app's Hubitat config page (`/installedapp/configure/{id}`, derived from `api_base` by matching `^(https?://[^/]+)/apps/api/(\d+)`) and a **Docs ↗** link to the app's README on GitHub — matching HubDiagnostics. Omit the Settings link when `api_base` is the literal placeholder (WORKBENCH mode — no match).

## Submitting changes

- New source files must carry an `SPDX-License-Identifier: MIT` header.
- A few files under `drivers/` are derived from upstream community work and remain under
  **Apache-2.0** — preserve each such file's existing upstream attribution header; don't
  relicense it.
- Keep commits focused; let the diff and the docs explain the change rather than the
  commit body.

## Further reading

- [`ARCHITECTURE.md`](ARCHITECTURE.md) — coding conventions & platform semantics.
- [`TESTING.md`](TESTING.md) — testing methodology and the closed-loop test contract.

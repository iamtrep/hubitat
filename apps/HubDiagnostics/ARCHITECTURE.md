<!--
Copyright (c) 2025-2026 PJ
SPDX-License-Identifier: MIT
-->

# Hub Diagnostics Architecture Guide

This document is the contributor-facing architecture guide for Hub Diagnostics.

Use it to make feature additions that reinforce the current design instead of eroding it. It is intentionally prescriptive. `README.md` explains what the app does. `CODE_REVIEW.md` records review findings and accepted tradeoffs. This file defines how future changes should be built.

> **Read first:** [`/ARCHITECTURE.md`](../../ARCHITECTURE.md) — repo-wide Hubitat development principles and platform constraints (sandbox restrictions, `state` semantics, async-call ceiling, static typing, date handling, generic API endpoint design, app/UI version sync, fail-soft defaults). This guide assumes that document and adds Hub Diagnostics specifics on top.

## Purpose

Hub Diagnostics is a Hubitat app plus a single-file SPA:

- `HubDiagnostics.groovy` owns hub data collection, API shaping, scheduling, caching, enrichment, and lifecycle behavior.
- `hub_diagnostics_ui.html` owns rendering and interaction, but should stay thin and consume server-shaped data rather than inventing its own data model.

The architecture is optimized for:

- graceful degradation when hub endpoints fail or time out
- low repeated load on the hub during dashboard and health refreshes
- server-side normalization of irregular Hubitat payloads
- a small set of reusable UI primitives instead of ad hoc rendering paths

## Guiding Principle: The Hub Is The Constrained Side

The hub is a 4-core ARM Cortex-A53 sharing memory with every other Hubitat app on the device. The browser is, in practice, an order of magnitude faster with effectively unbounded RAM relative to this workload. Optimize accordingly.

- Ship slightly larger normalized payloads to the browser rather than do CPU-intensive transformation on the hub.
- *Aggregation* means coalescing duplicate fetches and providing fail-soft semantics — not computing derived data the browser can compute trivially.
- *Normalization* means stable field names, payload shape, and date-to-epoch conversion — not sorting, ranking, diffing, threshold-evaluating, or HTML-cleaning.
- Sorts, top-N selection, set differences, snapshot diffs, threshold-based severity, HTML stripping, and percentage rollups all belong in the SPA.

When in doubt, ship raw and let the SPA derive.

## System Shape

### Backend layers

The Groovy app is intentionally layered:

1. Constants, caches, and lifecycle state
2. Low-level request wrappers
3. Feature-specific fetch helpers
4. Aggregation and analysis methods
5. API endpoints
6. Lifecycle, scheduling, UI sync, and migrations

Those layers should stay in that order. New code should fit one of them instead of mixing responsibilities.

### Frontend model

The UI is a vanilla JS SPA with one renderer per tab or feature area. It relies on reusable helpers, especially:

- `mc()` for metric cards
- `ni()` for name/value grid items
- `tbl()` for sortable, filterable, collapsible tables

The UI should render API data, not become a second backend.

## API Endpoint Boundaries

The repo-wide guide defines the three endpoint categories — *app-owned*, *aggregator*, and *pure passthrough* — and the rule that pure-passthrough routes should not exist. The notes below are Hub Diagnostics specifics on top of that rule.

The Groovy app acts as an application server for the SPA, providing four things the browser cannot get from raw hub endpoints:

1. **Auth stability** via OAuth app endpoints, so the SPA does not require an active Hubitat admin session.
2. **App-owned persistence** — snapshots, checkpoints, generated reports, scan caches, settings.
3. **Aggregation** — collapsing multiple hub requests into a single response with shared-cache and fail-soft semantics centralized. Aggregation is about *coalescing fetches*, not about computing derived values; if the only thing the SPA cannot do directly is sort, slice, or threshold-check the result, that does not belong on the hub.
4. **Normalization** — stable field names, payload shape, date-to-epoch conversion, firmware/version compatibility.

Most Hub Diagnostics routes are app-owned. The notable aggregators are `/api/dashboard`, `/api/health`, and `/api/live`; they are justified by shared-cache, fail-soft behavior, and normalization the SPA should not duplicate.

The `mappings { }` block in `HubDiagnostics.groovy` is grouped by category. Place new routes in the matching section.

## Contributor Prerequisites

Read this section — and the platform constraints in the repo-wide guide — before writing any code. These are not style preferences; they are facts that will cause silent failures or lost work if ignored.

### Test script structure

`tests/test-hub-diagnostics-api.sh` is a bash script that embeds a Python 3 program as a heredoc. The test logic is entirely Python; bash handles argument parsing and invocation. When adding new test coverage, write Python that matches the existing pattern — `ok()`, `fail()`, `warn()`, `section()`, `api_get()`, etc. Do not add pure bash test assertions.

### UI file deployment

The SPA (`hub_diagnostics_ui.html`) is deployed to the hub's File Manager. Editing the local file has no effect on the running app until it is uploaded. Use the `/hubitat-filemanager upload` skill to push changes. The Groovy app's `/ui.html` endpoint reads the uploaded file from File Manager, injects runtime values like the access token and API base, and serves the resulting HTML directly.

## Required Patterns

The repo-wide guide already covers backend-owns-normalization, date handling, state/cache invalidation, and app/UI version sync. The patterns below are Hub Diagnostics specifics.

### 1. All hub HTTP calls go through the request wrappers

Do not add raw `httpGet` calls inside feature code when an existing wrapper pattern already covers the use case.

Use:

- `hubMapRequest()` for JSON responses that should behave like maps
- `hubRequest()` for text responses or non-map JSON payloads
- `hubRequestInternal()` only as shared infrastructure, not as a normal call site for new feature code

**Array vs map responses:** `hubMapRequest()` always casts its result to `Map`. If an endpoint returns a JSON array, this silently produces an empty map or throws at the call site. Use `hubRequest()` for any endpoint that returns an array, then check `instanceof List` before casting:

```groovy
Object raw = hubRequest(MY_PATH, "my data", "json", 10)
if (!(raw instanceof List)) return []
List items = (List) raw
```

New fetch helpers must follow the repo-wide fail-soft defaults — map-shaped failures degrade to `null`/`{}`, list-shaped failures degrade to `[]`, text-fetch failures degrade to `null` — so the UI degrades partially instead of the endpoint collapsing.

### 2. Request-scoped shared data must stay shared

When one request path needs the same expensive data more than once, fetch it once and pass it down through a shared map.

Current examples include:

- `hubData`
- system resources
- temperature
- database size
- hub alerts

If a new Dashboard, Health, or Network feature needs data that is already naturally request-scoped, extend the shared-cache pattern instead of re-fetching the same endpoint in multiple helpers.

Do not introduce duplicate fetches on the same request path just because each helper can fetch its own fallback independently.

### 3. Hot paths are strict change zones

Some methods have large blast radius and must not gain new fetches casually. They are not equally sensitive:

**Hardest change zones** — these run on common refresh paths or have broad fan-out across the app:

- `buildSharedCache()` — runs on every dashboard and health request
- `getStructuredAlerts()` — feeds alert content across multiple tabs and endpoints
- `apiLive()` — called automatically by the frontend every few seconds

**High blast radius** — these run on common tab loads and already contain multiple HTTP calls; new additions need justification but are not forbidden:

- `getDashboardData()` and `getHealthData()` — tab-specific, not recurring, but called on every open of those tabs
- `apiNetwork()` / `getNetworkData()` — narrower than Dashboard/Health, but still substantial and easy to bloat

Any new fetch added to any of these must satisfy all of the following:

- broad enough value to justify running on that path
- safe to fail closed without breaking the endpoint
- bounded enough not to materially slow routine refreshes
- not better isolated to a narrower endpoint or tab-specific payload

If a new data source is unstable, expensive, or poorly understood, keep it out of shared alert generation and out of live-refresh paths.

### 4. Reuse existing UI primitives

New UI should use the existing card, metric, badge, and table helpers unless there is a real reason not to.

Prefer:

- `tbl()` over one-off sortable/filterable table implementations
- existing badge classes over new isolated color systems
- existing card structure over ad hoc blocks
- existing API response shapes where a nearby tab already exposes the same concept

Small visual duplication is acceptable. Structural duplication of logic is not.

**`tbl()` volume policy:** When a data source can return an unbounded number of rows, decide before merging whether to cap the list. Some tables should show every row regardless of count; others should be capped for UX reasons. This is not a default — discuss with the project owner which applies. If a cap is used, `slice(0, N)` in the SPA before passing to `tbl()` is the correct place to apply it.

**Variable shadowing in `tbl()` renderers:** The global `h()` function (HTML escaping) is used inside column renderer callbacks. Avoid naming any arrow-function parameter `h` in the same scope — the shadowing is silent and will produce unescaped output or incorrect behavior. `.then(h => {...})` is a common offender. Use `hist`, `data`, `resp`, or any other name.

## Patterns To Avoid

In addition to the repo-wide patterns-to-avoid list, these are Hub Diagnostics specifics:

- raw `httpGet` calls in feature logic when a wrapper covers the case
- new endpoint-specific error contracts when the wrappers already define the norm
- duplicate fetches of the same endpoint within a request path
- adding experimental or expensive fetches to `getStructuredAlerts()` or `apiLive()`
- pushing backend parsing and compatibility logic into the SPA
- computing derivable data hub-side that the SPA can compute trivially — sorts, top-N, set differences, snapshot diffs, threshold-based severity, HTML cleanup, percentage rollups
- blocking hot paths (`apiLive()`, `getStructuredAlerts()`, `buildSharedCache()`) on data that does not change between ticks
- hub-side string templating or HTML assembly when the SPA owns rendering
- one-off tables when `tbl()` is sufficient
- "cleanup" refactors that ignore Hubitat platform constraints already accepted by the project

## Accepted Architectural Tradeoffs

These are deliberate tradeoffs, not accidental debt to "fix" blindly:

### Some large methods are acceptable

Not every long method is a design failure. In this codebase, some pipelines are intentionally end-to-end and coherent. Split only when the split reduces duplication or blast radius, not just to satisfy a style preference.

### Audit internals use a different cost model

The device audit path is allowed to do heavier work because it is explicitly user-triggered and infrequent. Do not copy audit-time behavior into dashboard, health, or live-refresh paths.

This relaxed budget applies to *collection* — per-device `fullJson` fan-out, async orchestration, the radio-state enrichment phase. It does not extend to *post-processing*: sorts, indexes, mode computation, top-N selection, and cross-reference assembly should still move to the SPA where practical, even on the audit path.

### Some UI/report duplication is tolerated

There is known duplication between the SPA and the audit report rendering. That is an accepted compromise under current platform constraints. Do not expand that duplication casually, but do not destabilize working code for purity alone.

## What Counts As Architecture Regression

Treat these as regressions even if the feature "works" locally:

- a shared endpoint path now fetches the same hub resource multiple times per request
- a new unstable hub endpoint is added to alert generation or other hot paths
- a feature bypasses the wrapper/error-normalization layer
- `hubMapRequest()` is used for an endpoint that returns a JSON array
- the SPA learns raw payload quirks that should have been normalized server-side
- raw ISO offset date strings are passed to the SPA as display fields
- a new cache grows without expiration or invalidation
- the same logical payload shape is duplicated across endpoints and drifts
- a new UI feature reimplements table or card behavior already covered by shared helpers
- hub-side computation of values that depend only on data already in the same response payload — those should be derived in the SPA
- new sorting or top-N selection done in Groovy when the SPA already sorts or filters the same column via `tbl()`
- hub-side string/HTML/markdown assembly when the SPA owns rendering for the same surface

## Change Design Rules

When adding a feature, decide where it belongs before writing code.

### Add a new `/api/*` endpoint

Before adding a route, decide which category it belongs to (see *API Endpoint Boundaries* above and the repo-wide guide):

1. Does it own state, perform a write, or run orchestration? → app-owned. Add to the matching section in `mappings { }`.
2. Does it fetch and join multiple hub resources, or normalize a single hub payload the SPA should not learn? → aggregator. Add to the aggregator section in `mappings { }` and a one-line rationale comment on the action method explaining what the SPA cannot do directly.
3. Is it a pure passthrough over one hub endpoint? → do not add it. Fold the data into an existing aggregator, or leave the hub endpoint for the SPA to fetch directly under a future admin-session mode.

Do not add a new route just because the data is hub-side. The Groovy layer earns its place by aggregation, normalization, persistence, or orchestration.

### Add a new hub endpoint fetch

Default approach:

1. Add a path constant near related constants
2. Add a focused fetch helper near similar helpers
3. Normalize the payload there — including converting any date strings to epoch milliseconds
4. Decide whether the data belongs on a narrow endpoint or a shared request path
5. Add regression coverage in the API test script if the contract changed

Do not jump straight from a raw endpoint to a tab renderer.

### Add a new field to an existing API response

Ask:

- is this field derived from data already fetched in the same request?
- should the payload shape be shared with another endpoint that exposes the same concept?
- does this belong in a hot path or a tab-specific payload?

If the field is only for one tab and fetches risky data, keep it on that tab's endpoint path.

### Add a new card or table to the SPA

Default approach:

1. For each new field, decide whether it is *irreducible* or *derivable*:
   - **Irreducible** — comes from a hub HTTP call, requires normalization, or requires a cross-endpoint join only practical hub-side. These belong in the Groovy payload.
   - **Derivable** — computed from data already in the response (sort order, top-N, ranks, set differences, threshold-based severity, formatted strings, percentages, indexes). These belong in the SPA renderer.
2. Add irreducible fields to the Groovy payload; normalize/shape there, including date-to-epoch conversion. Reuse existing payloads or extend a nearby endpoint instead of inventing a new one.
3. Compute derivable fields in the SPA at render time. `tbl()` already handles per-column sort and filter — do not pre-sort hub-side just because you can.
4. Render with existing card helpers (`mc()`, `ni()`, etc.).
5. Use `tbl()` for table behavior unless the UI truly needs a simpler static table.
6. If the data source is unbounded, discuss row-cap policy with the project owner before merging.

The UI should rarely be the place where event names, raw dates, or cross-endpoint joins are invented — but it is the right place for sorts, top-N, threshold-based styling, and other pure derivations from the response payload.

## Pre-Merge Checklist

Before pushing a feature, answer these:

- Does this change add any new `/api/*` routes?
- If yes, does each route own state, aggregate, or normalize — or is it a pure passthrough that should not exist?
- Is each new route placed in the correct category section in `mappings { }`?
- Does this change add any new hub endpoint fetches?
- If yes, is each fetch using the correct wrapper for its response shape?
- If an endpoint returns a JSON array, is `hubRequest()` used instead of `hubMapRequest()`?
- Does any new fetch run on Dashboard, Health, Alerts, or Live paths?
- If yes, is the blast radius justified and is failure guaranteed to degrade safely?
- Does the change duplicate data already available through a request-scoped shared map?
- Is payload normalization happening in Groovy instead of the SPA?
- Are all timestamps converted to epoch milliseconds before reaching the SPA?
- Is the UI reusing `tbl()`, existing cards, and existing badge/metric patterns?
- If `tbl()` is used on potentially unbounded data, has the row-cap question been discussed?
- Does any new cache have explicit expiration and invalidation behavior?
- If API/UI contract changed, were both `APP_VERSION` and `UI_VERSION` updated?
- Did `tests/test-hub-diagnostics-api.sh` gain or update coverage where needed?
- Would this change still behave acceptably if the new endpoint timed out, returned an empty payload, or changed shape slightly?

If any answer is "no" or "not sure," the design should be revisited before merge.

## Relationship To Other Docs

- [`/ARCHITECTURE.md`](../../ARCHITECTURE.md): repo-wide Hubitat development principles and platform constraints
- `README.md`: user-facing behavior, installation, features, and REST API overview
- `CODE_REVIEW.md`: review findings, debt ledger, and rationale for accepted tradeoffs
- `ARCHITECTURE.md` (this file): contributor rules for safely extending Hub Diagnostics

Keep all four aligned, but do not collapse them into one document.

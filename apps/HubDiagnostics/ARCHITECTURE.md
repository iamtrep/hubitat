# Hub Diagnostics Architecture Guide

This document is the contributor-facing architecture guide for Hub Diagnostics.

Use it to make feature additions that reinforce the current design instead of eroding it. It is intentionally prescriptive. `README.md` explains what the app does. `CODE_REVIEW.md` records review findings and accepted tradeoffs. This file defines how future changes should be built.

## Purpose

Hub Diagnostics is a Hubitat app plus a single-file SPA:

- `HubDiagnostics.groovy` owns hub data collection, API shaping, scheduling, caching, enrichment, and lifecycle behavior.
- `hub_diagnostics_ui.html` owns rendering and interaction, but should stay thin and consume server-shaped data rather than inventing its own data model.

The architecture is optimized for:

- graceful degradation when hub endpoints fail or time out
- low repeated load on the hub during dashboard and health refreshes
- server-side normalization of irregular Hubitat payloads
- a small set of reusable UI primitives instead of ad hoc rendering paths

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

## Contributor Prerequisites

Read this section before writing any code. These are not style preferences — they are platform constraints and structural facts that will cause silent failures or lost work if ignored.

### Test script structure

`tests/test-hub-diagnostics-api.sh` is a bash script that embeds a Python 3 program as a heredoc. The test logic is entirely Python; bash handles argument parsing and invocation. When adding new test coverage, write Python that matches the existing pattern — `ok()`, `fail()`, `warn()`, `section()`, `api_get()`, etc. Do not add pure bash test assertions.

### UI file deployment

The SPA (`hub_diagnostics_ui.html`) is deployed to the hub's File Manager. Editing the local file has no effect on the running app until it is uploaded. Use the `/hubitat-filemanager upload` skill to push changes. The Groovy app's `/ui.html` endpoint reads the uploaded file from File Manager, injects runtime values like the access token and API base, and serves the resulting HTML directly.

### Hubitat sandbox constraints

Several standard Groovy and Java patterns are blocked or behave differently inside the Hubitat sandbox:

- **`value.getClass()` is sandbox-blocked.** Use the global `getObjectClassName(value)` instead to get a runtime class name string.

- **`state` in-place mutation may not persist.** Writing `state.myList << item` is not reliably detected as a change by the platform. Always use explicit reassignment: `state.myList = modifiedList`.

- **Pushing source does not trigger `updated()`.** Updated Groovy takes effect immediately (the platform interprets it on the fly), but `updated()` and `initialize()` are not called. Subscriptions and `state` from the previous code version persist until the user re-saves the app's preferences in the hub UI.

## Required Patterns

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

New fetch helpers must fail closed unless there is a strong reason not to:

- map-shaped failures should degrade to `null` or `{}` through the normalized wrapper path
- list-shaped failures should degrade to `[]`
- text fetch failures should degrade to `null`

The goal is partial UI degradation, not endpoint collapse.

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

### 4. Backend owns normalization

Normalize raw Hubitat payloads in Groovy whenever practical.

Examples of backend-owned work:

- mapping raw endpoint responses into stable field names
- converting response shapes into UI-friendly maps/lists
- computing labels, classifications, and derived status
- handling firmware/version compatibility differences
- shaping dates or timestamps into safe fields for the UI

The SPA should not be the place that learns hub payload quirks. If a field needs parsing or interpretation, prefer doing it once in Groovy.

**Date handling:** Hub endpoints return ISO 8601 strings with numeric timezone offsets (e.g., `"2026-05-05T23:07:43.088-0400"`). This format is not consistently parsed by `new Date()` in all browsers — Safari/WebKit in particular fails silently or returns `Invalid Date`. Always convert timestamps to epoch milliseconds in Groovy before including them in API responses. In the SPA, format with `new Date(ts).toLocaleString()`.

```groovy
long ts = 0
try { ts = Date.parse("yyyy-MM-dd'T'HH:mm:ss.SSSZ", (String) raw.date).time } catch (Exception ignored) {}
```

Never pass a raw ISO offset date string through to the SPA as a string field intended for display.

### 5. Reuse existing UI primitives

New UI should use the existing card, metric, badge, and table helpers unless there is a real reason not to.

Prefer:

- `tbl()` over one-off sortable/filterable table implementations
- existing badge classes over new isolated color systems
- existing card structure over ad hoc blocks
- existing API response shapes where a nearby tab already exposes the same concept

Small visual duplication is acceptable. Structural duplication of logic is not.

**`tbl()` volume policy:** When a data source can return an unbounded number of rows, decide before merging whether to cap the list. Some tables should show every row regardless of count; others should be capped for UX reasons. This is not a default — discuss with the project owner which applies. If a cap is used, `slice(0, N)` in the SPA before passing to `tbl()` is the correct place to apply it.

**Variable shadowing in `tbl()` renderers:** The global `h()` function (HTML escaping) is used inside column renderer callbacks. Avoid naming any arrow-function parameter `h` in the same scope — the shadowing is silent and will produce unescaped output or incorrect behavior. `.then(h => {...})` is a common offender. Use `hist`, `data`, `resp`, or any other name.

### 6. `state` requires an invalidation story

Use persistent `state` only for data that should survive app reloads or is intentionally durable across requests.

Use volatile/static in-memory fields only when loss on JVM reload is acceptable.

Avoid storing cached data in state when it is readily available from the hub in a single fetch; use volatile/static in-memory fields instead.

Before adding a new cache, define:

- what is cached
- when it expires
- what event clears it
- whether loss on reboot is acceptable

If you cannot explain invalidation in one or two sentences, the cache design is not ready.

### 7. Versioned app and UI changes stay in sync

The app and SPA are version-coupled by design.

If a change alters the API/UI contract or UI behavior:

- bump `APP_VERSION`
- bump `UI_VERSION`
- preserve the current sync assumptions

Do not add new UI-visible API fields and forget the version-sync model.

## Patterns To Avoid

Avoid these unless there is a deliberate, documented exception:

- raw `httpGet` calls in feature logic
- new endpoint-specific error contracts when wrappers already define the norm
- duplicate fetches of the same endpoint within a request path
- adding experimental or expensive fetches to `getStructuredAlerts()` or `apiLive()`
- pushing backend parsing and compatibility logic into the SPA
- passing raw ISO offset date strings through to the SPA
- one-off tables when `tbl()` is sufficient
- persistent caches with no clear invalidation
- "cleanup" refactors that ignore Hubitat platform constraints already accepted by the project

## Accepted Architectural Tradeoffs

These are deliberate tradeoffs, not accidental debt to "fix" blindly:

### Hubitat libraries are not real modularity here

Do not assume moving code into Hubitat libraries creates clean module boundaries. On this platform, that does not provide the kind of architectural separation it would in a normal application.

### Some large methods are acceptable

Not every long method is a design failure. In this codebase, some pipelines are intentionally end-to-end and coherent. Split only when the split reduces duplication or blast radius, not just to satisfy a style preference.

### Audit internals use a different cost model

The device audit path is allowed to do heavier work because it is explicitly user-triggered and infrequent. Do not copy audit-time behavior into dashboard, health, or live-refresh paths.

### Some UI/report duplication is tolerated

There is known duplication between the SPA and the audit report rendering. That is an accepted compromise under current platform constraints. Do not expand that duplication casually, but do not destabilize working code for purity alone.

### Fail-soft behavior is preferred

For diagnostics, partial data is usually better than a hard endpoint failure. Preserve the existing preference for returning incomplete-but-usable payloads over brittle strictness.

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

## Change Design Rules

When adding a feature, decide where it belongs before writing code.

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

1. extend the relevant API payload in Groovy
2. normalize/shape data in Groovy, including date-to-epoch conversion
3. render with existing card helpers
4. use `tbl()` for table behavior unless the UI truly needs a simpler static table
5. if the data source is unbounded, discuss row-cap policy with the project owner before merging

The UI should rarely be the place where event names, raw dates, or cross-endpoint joins are invented.

## Pre-Merge Checklist

Before pushing a feature, answer these:

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
- If API/UI contract changed, were both versions updated appropriately?
- Did `tests/test-hub-diagnostics-api.sh` gain or update coverage where needed?
- Would this change still behave acceptably if the new endpoint timed out, returned an empty payload, or changed shape slightly?

If any answer is "no" or "not sure," the design should be revisited before merge.

## Relationship To Other Docs

- `README.md`: user-facing behavior, installation, features, and REST API overview
- `CODE_REVIEW.md`: review findings, debt ledger, and rationale for accepted tradeoffs
- `ARCHITECTURE.md`: contributor rules for safely extending the system

Keep all three aligned, but do not collapse them into one document.

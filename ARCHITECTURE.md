# Hubitat Development Architecture Guide

This document captures architectural principles and platform constraints that apply across all Hubitat Groovy development in this repository — apps, drivers, and integrations alike. Per-project guides (for example, `apps/HubDiagnostics/ARCHITECTURE.md`) build on top of this one and add the specifics of their own design.

Treat this guide as the default. Project-level guides may extend specific sections, but the platform constraints below are not negotiable: violating them produces silent failures or lost work.

## Hubitat Platform Constraints

These are facts about the platform, not style preferences. Code that ignores them fails in subtle, hard-to-debug ways.

### Sandbox restrictions

Several standard Groovy and Java patterns are blocked or behave differently in the Hubitat sandbox.

- **`value.getClass()` is sandbox-blocked.** Use the global `getObjectClassName(value)` instead to get a runtime class name string.

- **In-place mutation of `state` may not persist.** Writing `state.myList << item` is not reliably detected as a change by the platform. Always use explicit reassignment: `state.myList = modifiedList`.

- **Pushing source code does not trigger `updated()`.** Updated Groovy takes effect immediately — the platform interprets it on the fly — but `updated()` and `initialize()` are not called. Subscriptions and `state` from the previous code version persist until the user re-saves the app's preferences in the hub UI.

### `state` vs `atomicState`

`state` is committed to the database when the method exits, not on each write. `atomicState` commits immediately on each write. Pick the one that matches the access pattern: prefer `state` for the common case, `atomicState` only when a method may be preempted or when concurrent writers race.

### `sendEvent()` deduplicates silently

If the value hasn't changed and `isStateChange` is not set to `true`, the event is filtered out and not fired. Do not rely on `sendEvent()` to "tick the state forward" — set `isStateChange: true` explicitly when an event must fire even with an unchanged value.

### Async HTTP call ceiling

The platform caps concurrent async HTTP calls at 8 per app. Code that fans out one request per device will silently lose calls at scale. Prefer batched or aggregated endpoints, or serialize work behind a small worker pool.

### Hubitat libraries are not real modularity

Moving Groovy code into Hubitat libraries does not provide the architectural separation it would in a normal application. Library code shares the host app's namespace, lifecycle, and sandbox. Treat libraries as include files for code reuse, not as modules with enforced boundaries.

## Coding Conventions

### Static typing

Use explicit types for return values, parameters, and local variables. Avoid `def`.

```groovy
void refresh() { ... }
String formatLabel(int id, String prefix) { ... }
Map jsonData = parseJson(raw)
```

Two exceptions:

- Hubitat async callback parameters (`resp`, `data`) stay untyped — platform convention.
- Genuinely polymorphic values (for example, `aValue` passed straight to `sendEvent`) stay untyped. Don't use `Object` as a substitute for `def`; it adds no value.

Use `@CompileStatic` on pure computation methods that do not access Hubitat dynamic properties (`settings`, `state`, `device`, etc.).

### Capabilities

Use current capabilities, not deprecated ones. For example, prefer `capability "Refresh"` over the deprecated `capability "Polling"` for pollable devices.

## Date Handling

Hubitat hub endpoints return ISO 8601 strings with numeric timezone offsets, e.g. `"2026-05-05T23:07:43.088-0400"`. This format is **not consistently parsed by `new Date()` in browsers** — Safari/WebKit in particular fails silently or returns `Invalid Date`.

Always convert timestamps to epoch milliseconds in Groovy before including them in any UI or external API response:

```groovy
long ts = 0
try { ts = Date.parse("yyyy-MM-dd'T'HH:mm:ss.SSSZ", (String) raw.date).time } catch (Exception ignored) {}
```

In a SPA or other consumer, format with `new Date(ts).toLocaleString()`.

Never pass a raw ISO offset date string through to a UI as a string field intended for display.

## State and Caching Discipline

Use persistent `state` only for data that should survive app reloads or is intentionally durable across requests. Use volatile or static in-memory fields when loss on JVM reload is acceptable.

Avoid storing cached data in `state` when it is readily available from the hub in a single fetch — use volatile or static in-memory fields instead.

Before adding a new cache, define:

- what is cached
- when it expires
- what event clears it
- whether loss on reboot is acceptable

If you cannot explain invalidation in one or two sentences, the cache design is not ready.

## Backend Owns Normalization

When a Groovy app exposes data to a UI, mobile client, or external consumer, normalize raw Hubitat payloads in Groovy whenever practical. Backend-owned work includes:

- mapping raw endpoint responses into stable field names
- converting response shapes into consumer-friendly maps and lists
- computing labels, classifications, and derived status
- handling firmware/version compatibility differences
- shaping dates and timestamps into safe fields for the consumer

The consumer should not be the place that learns hub payload quirks. If a field needs parsing or interpretation, do it once in Groovy.

## API Endpoint Design

Apps that expose `/api/*` routes (typically OAuth-mounted) should classify each route into one of three categories:

- **App-owned** — exposes app state, performs a write, runs long orchestration, or composes data only the app can produce. Always justified.
- **Aggregator** — fetches multiple hub resources, normalizes them, and serves a consumer-specific contract. Justified by aggregation, normalization, or shared-cache and fail-soft behavior the consumer should not duplicate.
- **Pure passthrough** — a thin wrapper over a single hub endpoint with no aggregation, normalization, or app state. **This category should be empty.** A passthrough route earns no architectural benefit; the consumer can fetch the hub directly under an admin session.

If a new route would be a pure passthrough, do not add it. Fold it into an existing aggregator, add real normalization, or leave the data for the consumer to fetch directly.

## App and UI Version Sync

When a Groovy app is paired with a UI artifact (single-page app, dashboard tile, file-manager HTML), the two are version-coupled by design. Any change that alters the API/UI contract or UI behavior must:

- bump the app's `APP_VERSION` constant
- bump the UI's `UI_VERSION` constant

Do not add new UI-visible API fields and forget the version-sync model.

## Fail-Soft Defaults

For monitoring, diagnostics, and dashboard-style apps, partial data is usually better than a hard endpoint failure. Prefer returning incomplete-but-usable payloads over brittle strictness:

- map-shaped failures should degrade to `null` or `{}`
- list-shaped failures should degrade to `[]`
- text-fetch failures should degrade to `null`

The goal is partial UI degradation, not endpoint collapse. This default does not apply to writes or destructive actions, which should fail loudly.

## Patterns To Avoid

Avoid these unless there is a deliberate, documented exception:

- relying on `value.getClass()` instead of `getObjectClassName(value)`
- in-place mutation of `state` collections without reassignment
- using `def` where a concrete type would do
- passing raw ISO offset date strings through to UIs
- caches in `state` with no invalidation story
- pure-passthrough `/api/*` routes that exist only to forward a hub call
- treating Hubitat libraries as architectural module boundaries
- per-device async fan-out that exceeds the 8-call concurrency ceiling

## Per-Project Architecture Guides

Project-level architecture guides inherit everything in this document and add their own specifics — request wrappers, UI primitives, hot paths, accepted tradeoffs, change-design rules, and pre-merge checklists.

When writing or reviewing a project's architecture guide, prefer extending or referencing this document over duplicating its contents. Existing examples:

- [`apps/HubDiagnostics/ARCHITECTURE.md`](apps/HubDiagnostics/ARCHITECTURE.md) — Hub Diagnostics app and SPA
